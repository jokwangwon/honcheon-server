package com.honcheon.mvt;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 무공의 모션 — 조작을 실제 시전으로 바꾸는 층. 규칙은 SkillEngine, 연출은 여기.
 *
 * <p>조작 매핑 (docs/design/mc_action_mapping.md 1·3-B장 + skill_motion.md):
 * <table>
 *   <tr><td>좌클릭 (검·도)</td><td>기본 무공 콤보 — 육합검 1·2타(외공기 0) → 3타(발경 1)</td></tr>
 *   <tr><td>Shift + 우클릭</td><td>격 태세 순환 — 외공 → 발경 → 검기 → 강기 → 외공 (경지 게이트 통과분만)</td></tr>
 *   <tr><td>Shift + 좌클릭 (검기+ 태세)</td><td>기 발출(쏨) — 검기 참격 3 / 강기 포 6</td></tr>
 * </table>
 *
 * <p>불변식:
 * <ul>
 *   <li><b>연출도 config 다</b> — 이 파일에는 파티클 이름도 사운드 키도 없다. 전부
 *       {@code config/skill_motion.yml} 의 등록부를 이름으로 부른다 (등록제 규약).
 *       {@code tools/motion_audit.py} ⑦이 하드코딩을 잡는다.</li>
 *   <li>중앙 티커 1개 (performance.yml F-P2) — 예약 타격·텔레그래프·유지비·HUD 가 한 태스크를 공유한다</li>
 *   <li>파티클은 SkillHud.emit() 을 통해서만 — 예산 초과 시 연출만 강등, 판정은 불변</li>
 *   <li><b>보이는 것 = 맞는 것</b> — 궤적(호·선·원·시·돌·진)은 히트박스와 같은 모양으로 그린다.
 *       상대가 본 것으로 물러설 수 있어야 규칙이 정직하다.</li>
 * </ul>
 */
public final class SkillListener implements Listener {

    /** 손에 든 것 → 그 손에 실리는 무공. 액션 데이터(skill_mechanics.yml)가 없으면 바닐라로 흘려보낸다 */
    private static final Map<String, String> SKILL_BY_WEAPON_CLASS = Map.of(
            "검", "yukhap_geom",
            "도", "yukhap_geom",
            "맨손", "taejo_jangkwon");   // skill_mechanics.yml 에 combo 가 생기는 날 자동 점등

    private static final String CD_SHOT = "발출";
    /** NPC 격 시전 간격 — 응집과 응집 사이 (라운드 = 두름 과금 주기와 같은 눈금) */
    private static final String CD_QI = "npc_격";
    /** NPC 근접 사거리 — 이 안에 들어와야 응집을 시작한다 (config 등록 대기: npc_combat.yml reach) */
    private static final double NPC_REACH = 3.5;
    /** 응집이 끝난 뒤 격이 실려 있는 창 — 바닐라 근접 AI 의 스윙 타이밍을 우리가 못 정한다 (근사) */
    private static final int NPC_HOT_TICKS = 20;
    // NPC 내력 회복의 하드코딩(라운드당 1)은 제거됐다 — 이제 조식(internal_energy.yml
    // recovery.in_combat.조식)을 플레이어와 **같은 함수**로 탄다 (regulateBreath).

    private final HoncheonMvt plugin;
    private final SkillEngine engine;
    private final SkillHud hud;
    /** 3D 모션 층 — 파티클 위에 얹는다. 이것이 통째로 실패해도 무공은 보인다 (불변식 ㅁ) */
    private final SkillDisplay display;
    private final Map<UUID, SkillEngine.State> states = new HashMap<>();
    /** NPC 의 격 — 대칭 원칙. 같은 State 를 쓴다 (내력·두름·다운캐스트가 같은 규칙이라는 뜻이다) */
    private final Map<UUID, SkillEngine.State> npcStates = new HashMap<>();
    /** 무기 격돌 누적 — 몸(플레이어·NPC)당. breaks_at 회째에 병기가 부러진다 (weapon_break) */
    private final Map<UUID, Integer> clashCounts = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>();

    private long tick;
    /** 자기 피해 재진입 가드 — 엔진이 준 피해를 넣을 때 이 리스너가 다시 잡지 않도록 */
    private boolean applying;

    private record Pending(long due, Runnable action) {
    }

    public SkillListener(HoncheonMvt plugin, SkillEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
        this.hud = new SkillHud(engine);
        this.display = new SkillDisplay(plugin, engine);
    }

    /** 중앙 티커 기동 — HoncheonMvt.onEnable 에서 1회 (효과별 개별 태스크 생성 금지, F-P2) */
    public void start() {
        display.start();   // 지난 생의 유령(공중에 얼어붙은 획)을 걷는다
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /**
     * 정지 — 세계에 형체를 남기지 않는다.
     * <p><b>배선 필요</b>: {@code HoncheonMvt.onDisable()} 에서 {@code skillListener.shutdown()} 을 부른다.
     * 안 불러도 다음 기동의 {@link SkillDisplay#start()} 가 표식(honcheon:vfx)을 보고 걷어낸다 —
     * 이중 방벽이다 (크래시엔 onDisable 이 돌지 않으므로).
     */
    public void shutdown() {
        display.clearAll();
    }

    /**
     * 팩을 받았는가 — 3D 층의 유일한 분기점 ({@code require-resource-pack=false} 이므로 <b>물어볼 수밖에 없다</b>).
     *
     * <p>수락한 눈에는 팩이 구운 3D 획(item_model)을, 거절한 눈에는 <b>바닐라 아이템</b>을 실은 형체를 보인다
     * (철검이 날아가고 · 흰 막대가 몸을 돈다). 어느 쪽이든 무엇이 일어났는지 읽힌다.
     */
    @EventHandler
    public void onPackStatus(org.bukkit.event.player.PlayerResourcePackStatusEvent event) {
        display.packStatus(event.getPlayer().getUniqueId(),
                event.getStatus() == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
    }

    /** 무공 상태를 갈아 끼운다 — 연무장의 경지·내력은 세계의 것이 아니다 */
    public SkillEngine.State swapState(java.util.UUID playerId, SkillEngine.State replacement) {
        SkillEngine.State previous = states.get(playerId);
        if (replacement == null) {
            states.remove(playerId);
        } else {
            states.put(playerId, replacement);
        }
        return previous;
    }

    public SkillEngine.State state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), id -> new SkillEngine.State());
    }

    // ══════════ 중앙 티커 ══════════

    private void tick() {
        tick++;
        hud.newTick();
        display.tick(tick);   // 3D 층 — 투사 전진 · 수축 · 회수 (중앙 티커 하나를 같이 쓴다, F-P2)

        if (!pending.isEmpty()) {
            List<Pending> due = new ArrayList<>();
            pending.removeIf(p -> {
                if (p.due() <= tick) {
                    due.add(p);
                    return true;
                }
                return false;
            });
            for (Pending p : due) {
                try {
                    p.action().run();
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("무공 예약 처리 실패: " + e.getMessage());
                }
            }
        }

        if (tick % engine.npcThinkTicks() == 0) {
            npcSweep();   // NPC 격 — 매 틱 사고 금지 (npc_combat think_interval_ticks)
        }
        if (tick % 4 != 0) {
            return;   // HUD·유지비는 4틱(0.2초)마다 — 액션바 갱신 비용 절감
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            SkillEngine.State state = states.get(player.getUniqueId());
            if (state == null) {
                continue;
            }
            if (state.combatUntil > 0 && tick > state.combatUntil) {
                endCombat(player, state);   // 전투가 끝났다 — 흐름은 흩어지고 오의 횟수는 돌아온다
            }
            sustain(player, state);
            regulateBreath(state, engine.pool(state.naegong));   // 조식 — 격을 싣지 않은 합에 단전이 돈다
            // 생명 — 경지·장비가 바뀌면 다음 틱에 몸이 따라온다 (훅은 빠뜨리면 조용히 틀리고, 대조는 못 빠뜨린다)
            hud.vitalityTick(player, state);
            hud.energyBar(player, state);
            // 내구·부상은 격이 없어도 보인다 — 게이트를 두면 **삼류가 제 목숨을 영영 못 본다**
            hud.statusBar(player, state, tick);
        }
    }

    /**
     * 조식(調息) — 전투 중의 숨. {@code internal_energy.yml recovery.in_combat.조식} 의 배선.
     *
     * <p><b>운기조식(앉는 것)이 아니다.</b> 격을 싣지 않은 합에는 단전이 돈다 — 초식은 외공이니까
     * (cost_bands 외공기). 태우기와 고르기는 같은 합에 못 한다({@code only_if_unspent}).
     *
     * <p>이것이 없던 시절 개화 직후(내공 0.33 → 내력 풀 1)의 발경은 <b>전투당 한 번</b>이었다 —
     * 자원 관리가 아니라 형벌이었다. 이제 '한 합 태우고 한 합 고른다'(7합 전투에 발경 4회).
     * 축기가 사는 것은 총량이 아니라 <b>몰아 쓸 수 있는 합</b>이다 (내력 풀 3 = 3합 연발).
     *
     * <p>플레이어와 NPC 가 같은 함수를 탄다 — npc_combat.yml symmetry("NPC 자원 = 동일")의 이행이다.
     * 그전엔 NPC 만 하드코딩된 라운드당 1을 받고 있었고, 그 대칭은 거짓이었다.
     */
    private void regulateBreath(SkillEngine.State state, int pool) {
        int regen = engine.combatRegen();
        if (regen <= 0 || pool <= 0) {
            return;   // config 가 조식을 등록하지 않았다 — 코드가 수치를 지어내지 않는다
        }
        if (state.energyAtRoundStart < 0 || state.nextRegenTick < 0) {
            state.energyAtRoundStart = state.energy;
            state.nextRegenTick = tick + engine.roundTicks();
            return;
        }
        if (tick < state.nextRegenTick) {
            return;
        }
        boolean spent = state.energy < state.energyAtRoundStart;   // 시전·발출·두름 유지비 — 전부 '쓴 것'이다
        if (!(engine.regenOnlyIfUnspent() && spent) && state.energy < pool) {
            state.energy = Math.min(pool, state.energy + regen);
        }
        state.energyAtRoundStart = state.energy;
        state.nextRegenTick = tick + engine.roundTicks();
    }

    /** 전투의 끝 — 흐름(발동권)은 그 전투의 것이다. 다음 싸움은 처음부터 읽어내야 한다 */
    private void endCombat(Player player, SkillEngine.State state) {
        state.combatUntil = -1;
        state.ultimateUses = 0;
        if (state.flow > 0) {
            state.flow = 0;
            SkillHud.actionBar(player, ChatColor.DARK_GRAY + "숨을 고른다 — 흐름이 흩어졌다");
        }
    }

    /** 공방이 오갈 때마다 전투 창을 민다 (오의 횟수·흐름의 수명) */
    private void touchCombat(SkillEngine.State state) {
        state.combatUntil = tick + engine.combatWindowTicks();
    }

    // ══════════ NPC 격 — 대칭 원칙 (npc_combat.yml symmetry) ══════════

    /**
     * 격을 쓰는 NPC 는 <b>플레이어와 같은 규칙</b>으로 쓴다: 경지 게이트 · 내력 · 두름 유지비 ·
     * 다운캐스트 · 텔레그래프. 사람이 가까이 있을 때만 돈다 (performance.yml npc_logic 6ms).
     */
    private void npcSweep() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            for (org.bukkit.entity.Entity entity : player.getNearbyEntities(
                    engine.cullBeyond(), 12, engine.cullBeyond())) {
                if (!(entity instanceof Mob mob) || !mob.isValid()) {
                    continue;
                }
                SkillEngine.Npc npc = npcOf(mob);
                if (npc == null || !npc.manifests()) {
                    continue;   // 외공의 몸 — 갈호(이류)도, 졸개도, 들짐승·맹수도 여기서 끝난다
                }
                npcThink(mob, npc, npcState(mob, npc));
            }
        }
        npcStates.keySet().removeIf(id -> {
            org.bukkit.entity.Entity e = plugin.getServer().getEntity(id);
            return e == null || e.isDead();   // 죽은 몸의 내력은 남지 않는다 (F-P2 cleanup_on death)
        });
    }

    private SkillEngine.Npc npcOf(org.bukkit.entity.Entity entity) {
        String id = HuntingGrounds.tag(entity, HuntingGrounds.KEY_ID);   // 등록부의 몸인가 (읽기 전용)
        return id == null ? null : engine.npc(id);
    }

    /** NPC 의 내력 장부 — 처음 보는 몸이면 등록부대로 세운다 (경지·내력 풀·두를 격) */
    private SkillEngine.State npcState(Mob mob, SkillEngine.Npc npc) {
        return npcStates.computeIfAbsent(mob.getUniqueId(), id -> {
            SkillEngine.State state = new SkillEngine.State();
            state.realm = npc.realm();
            state.naegong = npc.pool() / 3.0;   // 표시용 — 풀이 진실이다 (등록부가 내력을 직접 적는다)
            state.energy = npc.pool();
            if (engine.sustainCost(npc.grade()) > 0) {
                state.armed = npc.grade();      // 두름형(검기·강기) — 켠 채로 선다. 유지비는 아래에서 낸다
                state.nextSustainTick = tick;
            }
            return state;
        });
    }

    /**
     * 한 번의 사고 — 유지비 · 회복 · 응집(텔레그래프).
     *
     * <p><b>두름</b>(검기·강기): 라운드마다 유지비. 못 내면 기가 흩어진다 — 플레이어가 그것을 본다.
     * <p><b>발경</b>: 유지비가 없다. 사거리에 들어오면 <b>응집을 시작</b>하고(선딜 = 텔레그래프),
     * 응집이 끝난 창 안에 들어간 타격에만 격이 실린다. 그 창을 보고 물러서면 맨 주먹이 온다.
     */
    private void npcThink(Mob mob, SkillEngine.Npc npc, SkillEngine.State state) {
        Location hand = mob.getEyeLocation();
        boolean fighting = mob.getTarget() instanceof Player;

        // 조식 — 플레이어와 같은 함수, 같은 config (npc_combat.yml symmetry).
        //   전투 중이든 밖이든 같은 규칙이다: 내력을 쓴 합에는 돌지 않고, 쉰 합에는 돈다.
        regulateBreath(state, npc.pool());

        if (!fighting) {
            if (state.armed == null && state.energy >= engine.sustainCost(npc.grade())
                    && engine.sustainCost(npc.grade()) > 0) {
                state.armed = npc.grade();   // 숨을 고르고 다시 두른다 (다운캐스트는 영구형이 아니다)
            }
            return;
        }

        int sustain = engine.sustainCost(state.armed);
        if (state.armed != null && sustain > 0) {
            if (tick >= state.nextSustainTick) {
                if (state.energy < sustain) {
                    state.armed = null;   // 다운캐스트 — 규칙이 대칭이어야 세계가 정직하다
                    event(hand, "격_소산");
                    return;
                }
                state.energy -= sustain;
                state.nextSustainTick = tick + engine.roundTicks();
            }
            aura(hand, mob, state.armed);   // 두름 잔광 — 플레이어와 같은 등록부를 탄다
            return;
        }

        // 발경 — 두름이 없는 격. 사거리 + 쿨다운이 맞으면 응집한다
        if (state.energy < engine.npcStrikeCost(npc.grade())
                || mob.getLocation().distance(mob.getTarget().getLocation()) > NPC_REACH
                || state.onCooldown(CD_QI, tick)) {
            return;
        }
        SkillEngine.Frames f = engine.comboFrames("yukhap_geom", 2);   // 발경이 실리는 칸 (3타)
        state.cooldownUntil.put(CD_QI, tick + engine.roundTicks());
        state.qiHotUntil = tick + f.startup() + NPC_HOT_TICKS;
        // 응집 — 플레이어와 **같은 함수**를 탄다 (npc_combat.yml symmetry: "응집은 빛으로 보인다").
        // 그 창을 보고 물러서면 맨 주먹이 온다 — NPC 의 텔레그래프는 규칙의 일부다
        scheduleTelegraph(mob::getEyeLocation, npc.grade(), f.startup(), 0);
    }

    /** 지금 이 몸의 타격에 실리는 격 — 두름(상시) 또는 응집이 끝난 창(발경). 그 밖엔 외공기 */
    private String npcActiveGrade(SkillEngine.Npc npc, SkillEngine.State state) {
        if (state.armed != null) {
            return state.armed;
        }
        return tick <= state.qiHotUntil && tick >= state.qiHotUntil - NPC_HOT_TICKS
                ? npc.grade() : SkillEngine.BARE;
    }

    /** 두름 유지비 — 라운드마다 과금. 못 내면 기가 흩어진다 (상대가 읽을 수 있는 고갈 신호) */
    private void sustain(Player player, SkillEngine.State state) {
        if (state.armed == null) {
            return;
        }
        int cost = engine.sustainCost(state.armed);
        if (cost <= 0) {
            // 발경은 유지비가 없다 — 타격 순간에만 실린다. 그래도 <b>켜져 있다는 사실</b>은 보여야 한다
            // (등록부의 발경 잔광은 1개 — 검기 3, 강기 5 보다 흐리다. 격의 사다리는 잔광에서도 단조 증가한다)
            aura(handLocation(player), player, state.armed);
            return;
        }
        if (tick < state.nextSustainTick) {
            // 두름은 잔광으로만 알린다 — 켜져 있다는 사실 자체가 상대에게 정보다 (소리는 응집의 몫)
            aura(handLocation(player), player, state.armed);
            return;
        }
        if (state.energy < cost) {
            dispel(player, state, "기가 흩어진다 — 내력이 다했다");
            return;
        }
        state.energy -= cost;
        state.nextSustainTick = tick + engine.roundTicks();
        aura(handLocation(player), player, state.armed);
    }

    // ══════════ 입력 → 시전 ══════════

    /**
     * 한 대가 오가는 자리. 세 갈래다.
     * <ul>
     *   <li><b>플레이어의 칼</b> — 바닐라 피해를 취소하고 무공 판정으로 대체한다 (좌클릭 = 공격)</li>
     *   <li><b>NPC 의 칼</b> — 격이 실려 있으면 격 위력을 얹고, 방어자의 기 방어·무기를 판정한다</li>
     *   <li><b>그 밖</b> — 격 없는 타격(외공기). 그래도 호신강기·반격 오의는 반응한다</li>
     * </ul>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMelee(EntityDamageByEntityEvent event) {
        if (applying || !(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        if (event.getDamager() instanceof Player player) {
            String skillId = skillInHand(player);
            if (skillId == null) {
                return;   // 무공이 실리지 않는 손 — 바닐라 그대로
            }
            event.setCancelled(true);
            swing(player, skillId, target);
            return;
        }
        if (!(event.getDamager() instanceof LivingEntity attacker)) {
            return;
        }
        npcStrike(event, attacker, target);
    }

    /**
     * NPC 의 한 대 — 대칭 원칙의 마지막 칸.
     *
     * <p>격이 실려 있으면 <b>격 위력</b>(combat.yml qi_power)이 피해에 더해지고, 발경은 그 자리에서
     * 내력을 낸다 (못 내면 다운캐스트 — 맨 주먹이 온다). 그리고 방어자의 <b>기 방어</b>(호신강기)와
     * <b>무기</b>(격돌 — 범철은 검기를 세 합 못 견딘다)가 같은 규칙으로 판정된다.
     */
    private void npcStrike(EntityDamageByEntityEvent event, LivingEntity attacker, LivingEntity target) {
        SkillEngine.Npc npc = npcOf(attacker);
        SkillEngine.State state = npc == null || !npc.manifests()
                ? null : npcStates.get(attacker.getUniqueId());
        String grade = state == null ? SkillEngine.BARE : npcActiveGrade(npc, state);

        if (state != null && !SkillEngine.BARE.equals(grade)) {
            int cost = engine.npcStrikeCost(grade);
            if (state.energy < cost) {
                grade = SkillEngine.BARE;                     // 다운캐스트 — 빈약함이 곧 정보다
                state.armed = null;
                event(attacker.getEyeLocation(), "다운캐스트");
            } else {
                state.energy -= cost;
                state.qiHotUntil = -1;                        // 응집을 태웠다 — 다시 모아야 한다
                event.setDamage(event.getDamage() + engine.qiPower(grade));
                impact(target.getLocation().add(0, 1, 0), grade,
                        new SkillEngine.Strike(0, 0, "success", "성공", true, 0), 1);
            }
        }

        Defense defense = defend(target, attacker, grade, event.getDamage());
        if (defense.blocked()) {
            event.setCancelled(true);
            return;
        }
        event.setDamage(defense.damage());
        clashWeapon(target, grade);                            // 무기가 격을 견뎌야 한다 (원칙 3)
    }

    /** 허공 좌클릭 = 헛손질(콤보는 진행된다) / Shift+좌클릭 = 발출 / Shift+우클릭 = 격 태세 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != null && event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        Action action = event.getAction();

        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            if (!player.isSneaking()) {
                return;   // 평범한 우클릭 — 상호작용은 세계의 몫 (가드·패링은 후속 배선)
            }
            event.setCancelled(true);
            cycleArmed(player);
            return;
        }
        if (action != Action.LEFT_CLICK_AIR) {
            return;   // LEFT_CLICK_BLOCK 은 건드리지 않는다 — 채굴을 무공이 잡아먹으면 안 된다
        }
        SkillEngine.State state = state(player);
        if (player.isSneaking() && engine.gradeRank(offense(state)) >= 2) {
            shoot(player, state);
            return;
        }
        String skillId = skillInHand(player);
        if (skillId != null) {
            swing(player, skillId, null);
        }
    }

    // ─── 콤보 ───

    private void swing(Player player, String skillId, LivingEntity primary) {
        SkillEngine.State state = state(player);
        if (tick - state.lastCastTick < engine.duplicateWindowTicks()) {
            return;   // F-R1 — 같은 틱 중복 시전 폐기
        }
        if (tick < state.busyUntil) {
            SkillHud.actionBar(player, ChatColor.DARK_GRAY + "아직 자세가 돌아오지 않았다");
            return;   // 경직·후딜 — 연타 방지
        }
        if (tick > state.comboDeadline) {
            state.comboIndex = 0;   // 입력 유예창을 놓쳤다 — 처음부터
        }

        String weaponClass = engine.weaponClassOf(player.getInventory().getItemInMainHand(), materialName(player));
        SkillEngine.Cast cast = engine.planCombo(
                skillId, state.comboIndex, state.realm, state.energy, offense(state), weaponClass);

        int shown = state.comboIndex + 1;
        int size = engine.comboSize(skillId);
        state.comboIndex = (state.comboIndex + 1) % size;
        state.comboDeadline = tick + cast.frames().total() + engine.comboWindow(skillId);
        // 공속이 거짓말하지 않게 — 무공이 실리면 바닐라 피해가 취소되고 프레임이 스윙 간격을 정한다.
        // 계열 공속(부 0.9/s = 22틱)이 프레임(9틱)보다 느리면 "가장 느린 병기"가 연출로만 남는다.
        state.busyUntil = tick + Math.max(cast.frames().total(), swingInterval(player));
        state.lastCastTick = tick;
        state.energy -= cast.paid();

        commit(player, state, cast, primary, shown + "타", shown - 1);
    }

    // ─── 발출 (쏨) ───

    private void shoot(Player player, SkillEngine.State state) {
        if (tick < state.busyUntil) {
            SkillHud.actionBar(player, ChatColor.DARK_GRAY + "아직 자세가 돌아오지 않았다");
            return;
        }
        if (state.onCooldown(CD_SHOT, tick)) {
            return;
        }
        String weaponClass = engine.weaponClassOf(player.getInventory().getItemInMainHand(), materialName(player));
        SkillEngine.Cast cast = engine.planShot(offense(state), state.realm, state.energy, weaponClass);
        if (cast.downcast() || engine.gradeRank(cast.grade()) < 2) {
            // 발출만은 다운캐스트가 없다 — 프레임이 아니라 기 그 자체가 본체다 (skill_motion.md 4장)
            SkillHud.actionBar(player, ChatColor.RED + "기가 흩어진다 — 쏠 것이 없다");
            event(handLocation(player), "발출_불발");
            state.cooldownUntil.put(CD_SHOT, tick + 10L);
            return;
        }
        // 공속이 거짓말하지 않게 — 무공이 실리면 바닐라 피해가 취소되고 프레임이 스윙 간격을 정한다.
        // 계열 공속(부 0.9/s = 22틱)이 프레임(9틱)보다 느리면 "가장 느린 병기"가 연출로만 남는다.
        state.busyUntil = tick + Math.max(cast.frames().total(), swingInterval(player));
        state.lastCastTick = tick;
        state.energy -= cast.paid();
        state.cooldownUntil.put(CD_SHOT, tick + cast.cooldownTicks());
        itemCooldown(player, cast.cooldownTicks());

        commit(player, state, cast, null, "발출", 0);
    }

    /** 선딜 텔레그래프 → 지속 프레임에 판정 → 후딜. 프레임은 다운캐스트해도 남는다 */
    private void commit(Player player, SkillEngine.State state, SkillEngine.Cast cast,
                        LivingEntity primary, String label, int stepIndex) {
        if (cast.gated()) {
            SkillHud.actionBar(player, ChatColor.GRAY + "그 격은 아직 이 몸의 것이 아니다 ("
                    + engine.gradeGate(offense(state) == null ? "발경" : offense(state)) + "부터)");
        }
        if (cast.downcast()) {
            // 【빈약함이 곧 정보다】 격이 외공기로 떨어지면 연출도 회색으로 떨어진다 — 상대가 그것을 읽는다
            SkillHud.actionBar(player, ChatColor.DARK_GRAY + "기가 실리지 않는다 — 맨 기술");
            event(handLocation(player), "다운캐스트");
        }

        SkillEngine.Frames f = cast.frames();
        // 시전 중 이동 제약 — 무거운 수일수록 발이 묶인다 (프레임 총량이 곧 자세의 무게다)
        if (f.total() >= 8) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                    Math.max(1, f.total()), f.total() >= 16 ? 2 : 1, false, false, false));
        }
        // 텔레그래프 — 응집은 빛으로 보인다. 등록부가 개수·주기·소리·예산을 전부 정한다.
        // 텔레그래프 가중(telegraph_boost)은 초식이 정한다: 제왕검형·만천화우는 선딜이 곧 예고다
        SkillEngine.SkillMotion motion = engine.motionSkill(cast.skillId());
        SkillEngine.Step step = motion == null ? null : motion.step(stepIndex);
        int boost = step == null ? 0 : step.telegraphBoost();
        if (cast.manifested() || boost > 0) {
            scheduleTelegraph(() -> handLocation(player), cast.grade(), f.startup(), boost);
        }
        player.swingMainHand();
        pending.add(new Pending(tick + f.startup(),
                () -> resolve(player, state, cast, primary, label, stepIndex)));
    }

    // ══════════ 판정 · 적용 ══════════

    private void resolve(Player player, SkillEngine.State state, SkillEngine.Cast cast,
                         LivingEntity primary, String label, int stepIndex) {
        if (!player.isOnline()) {
            return;
        }
        SkillEngine.Ultimate art = cast.ultimate();
        if (art != null && art.isCounter()) {
            // 반격 오의 — 벨 것을 찾지 않는다. 상대가 오기를 기다린다 (지속 창 = window_ticks)
            state.counterUntil = tick + cast.frames().active() + art.counterWindow();
            SkillHud.actionBar(player, ChatColor.LIGHT_PURPLE + art.name()
                    + ChatColor.WHITE + " — 기다린다 (" + art.counterWindow() + "틱)");
            return;
        }
        List<LivingEntity> targets = switch (cast.hitType()) {
            case "선" -> lineTargets(player, cast);
            case "원" -> circleTargets(player, cast);
            default -> arcTargets(player, cast, primary);
        };
        int swings = art == null ? 1 : Math.max(1, art.multiHit());

        Location origin = swingLocation(player, cast);
        // 궤적 — 판정과 같은 틱에 같은 모양을 그린다 (보이는 것 = 맞는 것).
        // 헛쳐도 그린다: 상대가 '지나간 자리'를 보고 다음 수를 읽을 수 있어야 공방이 성립한다
        String weaponClass = engine.weaponClassOf(player.getInventory().getItemInMainHand(),
                materialName(player));
        if (art == null) {
            trail(player, cast, weaponClass, stepIndex);
        }
        int shown = Math.max(1, targets.size()) * swings;   // 타격 풀을 나눌 몫 (광역이 예산을 터뜨리지 않게)
        int hits = 0;
        for (LivingEntity target : targets) {
            for (int swing = 0; swing < swings; swing++) {
                if (!target.isValid()) {
                    break;
                }
                // 실행력 = 무공 숙련 + 무기 보정 + 경지 격차(gm_modifiers realm_gap) + 상태 보정
                // MVT 근사: 능력치 시트가 없다 — 경지가 능력치의 자리를 대신한다 (skill_motion.md 4장)
                boolean hostile = target instanceof Monster;
                int mastery = plugin.ledger(player.getUniqueId())
                        .levelOf(engine.skillName(cast.skillId()), plugin.progression());
                int execBase = mastery + engine.weaponJudgmentBonus(weaponGrade(player))
                        + engine.realmGapBonus(state.realm, foeRealm(target, hostile))
                        + (engine.isDepleted(state.energy) ? -2 : 0);   // 내공 고갈 = 판정 -2
                int resist = engine.difficulty(hostile ? "보통" : "쉬움");
                int roll = ThreadLocalRandom.current().nextInt(6) + 1
                        + ThreadLocalRandom.current().nextInt(6) + 1;   // 전투는 주사위를 쓴다

                SkillEngine.Strike strike = engine.strike(cast, execBase, roll, resist);
                touchCombat(state);
                if (engine.isFlowTier(strike.tierId())) {
                    gainFlow(player, state);   // 발동권 — 읽어낸 순간이 쌓인다 (오의 충전의 실체)
                }
                if (!strike.hit()) {
                    continue;
                }
                // 상대의 기 방어·무기가 같은 규칙으로 판정된다 (대칭)
                Defense defense = defend(target, player, cast.grade(), strike.damage());
                if (defense.blocked()) {
                    continue;   // 기 방어가 먹었다 — 검이 닿지 않았으니 격돌도 없다
                }
                clashWeapon(target, cast.grade());   // 무기가 격을 견뎌야 한다 (원칙 3)
                hits++;
                applying = true;
                try {
                    target.damage(defense.damage(), player);
                } finally {
                    applying = false;
                }
                stagger(target, player, cast);
                impact(target.getLocation().add(0, 1, 0), cast.grade(), strike, shown);
                if (art != null) {
                    ultimateEffect(player, state, art, target, defense.damage());
                }
            }
        }

        // 세계 다리 — **격은 목격될 때 비로소 강호의 일이 된다** ("그자가 검기를 뿜었다더라").
        // 아무도 없는 산속에서 강기를 터뜨린 것은 소문이 아니다. 본 사람이 있어야 이야기가 된다.
        if (hits > 0 && cast.manifested() && !Dojang.suppressWorldEvents(player.getWorld())) {
            Location me = player.getLocation();
            int seen = (int) me.getWorld().getNearbyEntities(me, 24, 12, 24).stream()
                    .filter(e -> (e instanceof Player p && !p.equals(player))
                            || e instanceof org.bukkit.entity.Villager).count();
            Location market = plugin.anchor("장터");
            String where = market != null && market.getWorld() == me.getWorld()
                    && market.distance(me) <= 60 ? "장터_광장" : "산길_어귀";
            WorldBridge.qiManifested(cast.grade(), where, seen, player.getUniqueId(), player.getName());
        }

        if (hits == 0) {
            event(origin, "헛손질");   // 마른 붓 자국 — 빗나갔다는 것도 정보다
        }
        strain(player, state, cast);

        SkillHud.actionBar(player, hud.gradeColor(cast.grade()) + label + " "
                + ChatColor.DARK_GRAY + "│ " + SkillHud.gradeLabel(cast.grade())
                + (hits > 0 ? ChatColor.WHITE + " · " + hits + "타" : ChatColor.GRAY + " · 헛손질"));
        hud.energyBar(player, state);
    }

    /** 상대의 경지 — 등록부의 몸이면 그 경지로 격차를 잰다 (몹 = 삼류 취급은 미등록 몸의 폴백) */
    private String foeRealm(LivingEntity target, boolean hostile) {
        SkillEngine.Npc npc = npcOf(target);
        if (npc != null) {
            return npc.realm();
        }
        if (target instanceof Player other) {
            return state(other).realm;
        }
        return hostile ? "삼류" : "범인";
    }

    /** 흐름 — 아슬아슬한 성공 이상 공방 n회 누적. 차는 순간을 연출로 고지한다 (mc_action_mapping) */
    private void gainFlow(Player player, SkillEngine.State state) {
        if (engine.ultimateStage(state.realm) == null || state.flow >= engine.flowRequired()) {
            return;
        }
        state.flow++;
        if (state.flow >= engine.flowRequired()) {
            event(player.getLocation().add(0, 1, 0), "흐름_충전");
            SkillHud.actionBar(player, ChatColor.LIGHT_PURPLE + "흐름을 읽었다 — 오의 (F)");
        }
    }

    // ══════════ 방어 — 기 방어(호신강기) · 반격 오의 · 무기 격돌 ══════════

    /** 한 대를 받아 낸 결과 — 무효(blocked) 이거나, 깎여서 들어온다 (관통 = 격 위력 차) */
    private record Defense(boolean blocked, double damage) {
    }

    /** 방어자의 장부 — 플레이어는 없으면 만든다 (무공을 한 번도 안 쓴 몸도 맞기는 한다) */
    private SkillEngine.State stateOf(LivingEntity entity) {
        return entity instanceof Player player
                ? state(player) : npcStates.get(entity.getUniqueId());
    }

    /**
     * 방어의 두 층 — 반격 오의(무효 + 반사) · 기 방어(호신강기).
     *
     * <p><b>절대 방어는 없다</b> (qi_manifestation forms.두름_몸.호신강기.on_hit): 하위 격을 무효화하는
     * 것은 공짜가 아니라 유지 내력을 깎아 낸다(상쇄 소모 1). 동격은 2. 상위 격은 <b>관통</b>한다 —
     * 격 위력 차만큼 들어오고, 방어자는 그 위에 2를 더 잃는다. 지불할 내력이 없으면 강기는 그 자리에서
     * 흩어진다 (collapse) — 다음 타격은 맨몸으로 받는다.
     */
    private Defense defend(LivingEntity target, LivingEntity attacker, String grade, double incoming) {
        SkillEngine.State state = stateOf(target);
        if (state == null) {
            return new Defense(false, incoming);
        }
        touchCombat(state);

        // 무기 접촉 격돌 — 방패로 받아 낸 순간 (defense_states.패링 / weapon_break.trigger "가드·패링·합").
        // 판정을 바꾸지 않는다 (바닐라가 이미 피해를 깎는다) — 그 순간이 **보이고 들리게** 할 뿐이다.
        if (target instanceof Player guarding && guarding.isBlocking()) {
            event(target.getLocation().add(0, 1.2, 0), "패링_성공");
        }

        // 태극혜검 — 패링의 극의. 지속 중 받은 공격 1회를 무효화하고 위력 그대로 반사한다
        if (tick <= state.counterUntil) {
            state.counterUntil = -1;
            Location at = target.getLocation().add(0, 1, 0);
            event(at, "반격_오의");
            applying = true;
            try {
                attacker.damage(incoming, target);   // 위력 그대로 되돌아간다
            } finally {
                applying = false;
            }
            if (target instanceof Player player) {
                SkillHud.actionBar(player, ChatColor.LIGHT_PURPLE + "태극 — 그대의 힘이 그대에게 돌아간다");
            }
            return new Defense(true, 0);
        }

        if (!SkillEngine.GUARD.equals(state.armed)) {
            return new Defense(false, incoming);
        }
        SkillEngine.Guard guard = engine.guard(grade, state.armed);
        if (state.energy < guard.drain()) {
            state.armed = null;                      // 상쇄 소모를 못 냈다 — 강기가 흩어진다
            state.nextSustainTick = -1;
            event(target.getLocation().add(0, 1, 0), "호신강기_붕괴");
            if (target instanceof Player player) {
                SkillHud.actionBar(player, ChatColor.RED + "호신강기가 깨진다 — 상쇄할 내력이 없다");
            }
            return new Defense(false, incoming);
        }
        state.energy -= guard.drain();
        Location at = target.getLocation().add(0, 1, 0);
        event(at, guard.blocked() ? "호신강기_무효" : "호신강기_관통");
        if (target instanceof Player player) {
            SkillHud.actionBar(player, guard.blocked()
                    ? ChatColor.YELLOW + "호신강기 — 튕겨 낸다 " + ChatColor.DARK_GRAY + "(상쇄 −" + guard.drain() + ")"
                    : ChatColor.RED + "관통 — " + grade + "가 강기를 갈랐다 " + ChatColor.DARK_GRAY
                            + "(피해 " + guard.pierce() + " · 상쇄 −" + guard.drain() + ")");
            hud.energyBar(player, states.get(player.getUniqueId()));
        }
        return guard.blocked()
                ? new Defense(true, 0)
                : new Defense(false, guard.pierce());   // 관통 피해 = 격 위력 차 (원칙 1)
    }

    /**
     * 무기 격돌 — 원칙 3: 무기가 격을 견뎌야 한다 (보검이 비싼 이유).
     * 범철이 검기를 받으면 <b>3합째 부러진다</b>(CRACK 누적), 범철이 검강을 받으면 <b>한 합에 잘린다</b>(SEVER).
     * 발경은 예외 — 기가 몸·무기 안에 머문다.
     */
    private void clashWeapon(LivingEntity defender, String grade) {
        if (engine.gradeRank(grade) < 2) {
            return;
        }
        EntityEquipment gear = defender.getEquipment();
        ItemStack held = gear == null ? null : gear.getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            return;   // 맨손은 부러지지 않는다 (짐승도 여기서 빠진다 — 무기를 들지 않으니 격돌이 없다)
        }
        String weaponGrade = engine.weaponGradeOf(held, held.getType().name());
        var clash = engine.clash(weaponGrade, grade, 0);
        if (clash == com.honcheon.core.rules.QiManifestationEngine.Clash.NONE) {
            return;
        }
        boolean sever = clash == com.honcheon.core.rules.QiManifestationEngine.Clash.SEVER;
        int count = clashCounts.merge(defender.getUniqueId(), 1, Integer::sum);
        Location at = defender.getLocation().add(0, 1.2, 0);

        if (sever || count % engine.breaksAt() == 0) {
            breakWeapon(defender, gear, held, sever, weaponGrade, grade);
            clashCounts.remove(defender.getUniqueId());
            return;
        }
        wear(held, gear);   // 금이 간다 — 1회째부터 예고 (weapon_break telegraph)
        event(at, "무기_균열");
        if (defender instanceof Player player) {
            player.sendMessage(ChatColor.YELLOW + "날에 금이 간다 — " + weaponGrade + "의 몸으로 " + grade
                    + "를 받았다 (" + count + "/" + engine.breaksAt() + "합)");
        }
    }

    /** 병기의 끝 — 절단(2격 초과)은 한 합, 누적 파괴는 3합째. 그 뒤는 맨손이다 (after_break) */
    private void breakWeapon(LivingEntity holder, EntityEquipment gear, ItemStack held,
                             boolean sever, String weaponGrade, String grade) {
        Location at = holder.getLocation().add(0, 1.2, 0);
        gear.setItemInMainHand(null);
        // 절단(2격 초과, 한 합)과 파괴(누적 3합)는 다른 사건이다 — 소리와 파편 수가 다르다
        event(at, sever ? "무기_절단" : "무기_파괴", held);
        // 무기가 사라졌으니 손이 가벼워진다 — NPC 의 공격력을 맨손으로 되돌린다 (combat.yml weapon_power)
        if (!(holder instanceof Player)) {
            var attribute = holder.getAttribute(org.bukkit.attribute.Attribute.ATTACK_DAMAGE);
            if (attribute != null) {
                double drop = engine.weaponPower(engine.weaponClassOf(held, held.getType().name()))
                        - engine.weaponPower("맨손");
                attribute.setBaseValue(Math.max(1, attribute.getBaseValue() - drop));
            }
        }
        if (holder instanceof Player player) {
            player.sendMessage(ChatColor.RED + (sever
                    ? "검이 잘렸다 — " + weaponGrade + "이(가) " + grade + "를 한 합도 못 견딘다."
                    : "검이 부러졌다 — " + engine.breaksAt() + "합을 버텼다. 남은 것은 맨손이다."));
        }
    }

    /** 내구 1 — 등급별 최대 내구를 넘기면 그 자리에서 부러진다 (바닐라는 '사용'해야 부러진다) */
    private static boolean wear(ItemStack item, EntityEquipment gear) {
        if (!(item.getItemMeta() instanceof Damageable meta)) {
            return false;
        }
        int max = item.getType().getMaxDurability();
        int damage = meta.getDamage() + 1;
        if (max > 0 && damage >= max) {
            gear.setItemInMainHand(null);
            return true;
        }
        meta.setDamage(damage);
        item.setItemMeta(meta);
        return false;
    }

    /** 호(arc) 히트박스 — 정면 부채꼴. max_targets 상한 (F-P3) */
    private List<LivingEntity> arcTargets(Player player, SkillEngine.Cast cast, LivingEntity primary) {
        double range = cast.range();
        Vector facing = player.getLocation().getDirection().setY(0).normalize();
        Location eye = player.getEyeLocation();
        List<LivingEntity> out = new ArrayList<>();
        if (primary != null && primary.isValid()) {
            out.add(primary);
        }
        for (org.bukkit.entity.Entity e : player.getNearbyEntities(range, range, range)) {
            if (out.size() >= cast.maxTargets()) {
                break;
            }
            if (!(e instanceof LivingEntity le) || le.equals(player) || out.contains(le) || !le.isValid()) {
                continue;
            }
            Vector to = le.getLocation().toVector().subtract(eye.toVector()).setY(0);
            if (to.lengthSquared() < 1e-6 || to.length() > range) {
                continue;
            }
            double angle = Math.toDegrees(facing.angle(to.normalize()));
            if (angle <= cast.angle() / 2.0) {
                out.add(le);
            }
        }
        return out;
    }

    /** 선(線) 히트박스 — 참격·포. 시선 방향으로 길이만큼, 폭 안의 것을 벤다 */
    private List<LivingEntity> lineTargets(Player player, SkillEngine.Cast cast) {
        double length = cast.range();
        double halfWidth = Math.max(0.5, cast.angle() / 2.0);   // 발출의 angle 칸은 폭(width)이다
        Vector dir = player.getLocation().getDirection().normalize();
        Location eye = player.getEyeLocation();
        List<LivingEntity> out = new ArrayList<>();
        for (org.bukkit.entity.Entity e : player.getNearbyEntities(length, length, length)) {
            if (out.size() >= cast.maxTargets()) {
                break;
            }
            if (!(e instanceof LivingEntity le) || le.equals(player) || !le.isValid()) {
                continue;
            }
            Vector to = le.getEyeLocation().toVector().subtract(eye.toVector());
            double along = to.dot(dir);
            if (along < 0 || along > length) {
                continue;
            }
            double perp = to.clone().subtract(dir.clone().multiply(along)).length();
            if (perp <= halfWidth) {
                out.add(le);
            }
        }
        return out;
    }

    /** 경직 — 등급별 틱 (약2·중5·강10·다운20). MC 근사: 이동 봉쇄 + 넉백 */
    private void stagger(LivingEntity target, Player from, SkillEngine.Cast cast) {
        int ticks = cast.staggerTicks();
        if (ticks <= 0) {
            return;
        }
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 4, false, false, true));
        if (ticks >= 10 && target instanceof Mob) {
            Vector push = target.getLocation().toVector()
                    .subtract(from.getLocation().toVector()).setY(0);
            if (push.lengthSquared() > 1e-6) {
                target.setVelocity(push.normalize().multiply(ticks >= 20 ? 0.8 : 0.45).setY(0.25));
            }
        }
    }

    /** 자기 무기가 자기 격을 못 견딘다 — 범철에 검기를 두르면 3회마다 손상 1 (weapon_break self_damage) */
    private void strain(Player player, SkillEngine.State state, SkillEngine.Cast cast) {
        if (!cast.manifested()) {
            return;
        }
        String grade = weaponGrade(player);
        if (!engine.selfDamages(grade, cast.grade(), 0)) {
            state.selfStrainCount = 0;
            return;
        }
        state.selfStrainCount++;
        int every = engine.selfDamageEvery();
        if (state.selfStrainCount % every != 0) {
            return;   // 아직 견딘다 — 1회째부터 '금이 간다'는 예고는 아래 손상 시점에만
        }
        EntityEquipment gear = player.getEquipment();
        ItemStack item = gear.getItemInMainHand();
        if (item.getType().isAir()) {
            return;
        }
        // 【고침】 바닐라는 '사용'해야 부러진다 — 내구를 넘긴 손상은 그대로 부러뜨린다 (after_break: 맨손)
        if (wear(item, gear)) {
            event(player.getLocation().add(0, 1.2, 0), "무기_파괴", item);
            player.sendMessage(ChatColor.RED + "검이 부러졌다 — 제 격을 못 견딘 쇠는 결국 제 주인을 버린다.");
            state.selfStrainCount = 0;
            return;
        }
        event(handLocation(player), "자기_손상");
        player.sendMessage(ChatColor.RED + "검이 운다 — " + grade + "의 몸으로 "
                + cast.grade() + "를 감당하지 못한다 (" + every + "합마다 금이 간다)");
    }

    // ══════════ 격 태세 ══════════

    private void cycleArmed(Player player) {
        SkillEngine.State state = state(player);
        List<String> armable = engine.armableGrades(state.realm);
        if (armable.isEmpty()) {
            SkillHud.actionBar(player, ChatColor.GRAY + "단전이 열리지 않았다 — 몸과 무기가 전부다");
            return;
        }
        String next = engine.cycleArmed(state.realm, state.armed);
        if (next == null) {
            dispel(player, state, "기를 거둔다");
            return;
        }
        int deploy = engine.deployCost(next);   // 호신강기는 전개비 4 를 따로 낸다 (두름은 유지비 선납)
        if (deploy > 0 && state.energy < deploy) {
            SkillHud.actionBar(player, ChatColor.RED + "기를 두를 내력이 없다 (" + next + " 전개 " + deploy + ")");
            return;
        }
        state.energy -= deploy;
        state.armed = next;
        state.nextSustainTick = tick + engine.roundTicks();

        // 태세 진입 — 두름은 손끝에, 호신강기는 몸에. 형태가 다르면 실루엣도 소리도 달라야 한다
        if (SkillEngine.GUARD.equals(next)) {
            SkillEngine.FormMotion form = engine.motionForm(SkillEngine.GUARD);
            guardRing(player);
            hud.emit(player.getLocation().add(0, 1, 0), form.aura(), false);
            sfx(player.getLocation(), form.deploySounds());
        } else {
            SkillEngine.GradeMotion m = engine.motionGrade(next);
            hud.emit(handLocation(player), m.charge(),
                    Math.min(m.charge().count() * 2, engine.motionBudget().perPointTickMax()), false);
            sfx(player.getLocation(), m.armSounds());
        }
        SkillHud.actionBar(player, hud.gradeColor(next) + next + " — " + gradeFlavor(next));
        hud.energyBar(player, state);
    }

    private void dispel(Player player, SkillEngine.State state, String why) {
        state.armed = null;
        state.nextSustainTick = -1;
        event(handLocation(player), "격_소산");
        SkillHud.actionBar(player, ChatColor.GRAY + why);
    }

    private static String gradeFlavor(String grade) {
        return switch (grade) {
            case "발경" -> "타격에 기를 싣는다";
            case "검기" -> "기가 날에 서린다";
            case "강기" -> "기가 응집한다";
            case SkillEngine.GUARD -> "기를 몸에 두른다 — 하위 격은 닿지 않는다";
            case "어검" -> "검이 손을 떠난다";
            case "심검" -> "형(形)이 사라진다";
            default -> grade;
        };
    }

    /** 공격에 실리는 격 — 호신강기는 <b>몸</b>에 두른 것이지 검에 두른 것이 아니다 (형태가 다르다) */
    private static String offense(SkillEngine.State state) {
        return SkillEngine.GUARD.equals(state.armed) ? null : state.armed;
    }

    // ══════════ 오의(奧義) — 격이 아니다. 별개의 사다리다 ══════════

    /**
     * F (스왑) = 오의 · Shift+F = 오의 선택 (mc_action_mapping 1장 "F = 오의, 발동권 획득 시에만 활성").
     * 오의를 느끼지 못하는 몸(초절정 미만)에게 F 는 그냥 손 바꾸기다 — 세계를 건드리지 않는다.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        SkillEngine.State state = state(player);
        if (engine.ultimateStage(state.realm) == null) {
            return;   // 벽 너머를 아직 훔쳐보지 못한 몸 — 바닐라 스왑 그대로
        }
        event.setCancelled(true);
        if (player.isSneaking()) {
            cycleUltimate(player, state);
            return;
        }
        castUltimate(player, state);
    }

    /** 전승 오의 순환 — MVT 엔 전승(사문·비급)이 없다. 손으로 고른다 (검증 수단) */
    private void cycleUltimate(Player player, SkillEngine.State state) {
        List<String> ids = engine.ultimateIds();
        int idx = state.ultimateId == null ? -1 : ids.indexOf(state.ultimateId);
        state.ultimateId = ids.get((idx + 1) % ids.size());
        SkillEngine.Ultimate art = engine.ultimate(state.ultimateId);
        int cost = engine.ultimateCost(art, engine.pool(state.naegong));
        player.sendMessage(ChatColor.LIGHT_PURPLE + "「" + art.name() + "」"
                + ChatColor.GRAY + " · " + art.faction() + " · 내력 " + cost
                + " (" + Math.round(art.costRatio() * 100) + "%)"
                + (art.bloodline() ? ChatColor.DARK_RED + " · 혈통 제한" : "")
                + (art.demonic() ? ChatColor.DARK_RED + " · 마공" : ""));
        SkillHud.actionBar(player, ChatColor.LIGHT_PURPLE + art.name() + ChatColor.DARK_GRAY
                + " — " + engine.ultimateStage(state.realm) + "  (F: 시전 · Shift+F: 다음 오의)");
    }

    /**
     * 오의 시전 — 발동권(흐름) · 횟수 · 내력. 셋을 다 채워야 나간다.
     *
     * <p>초절정(개안)은 <b>불완전 시전</b>이다: 위력 절반, 직후 내력 전소 + 내상.
     * 화경(완성)은 전투당 1회. 현경(자재)부터 횟수 제한이 풀린다.
     */
    private void castUltimate(Player player, SkillEngine.State state) {
        if (state.ultimateId == null) {
            SkillHud.actionBar(player, ChatColor.GRAY + "펼칠 오의가 없다 — Shift+F 로 고른다");
            return;
        }
        if (tick < state.busyUntil) {
            SkillHud.actionBar(player, ChatColor.DARK_GRAY + "아직 자세가 돌아오지 않았다");
            return;
        }
        if (state.flow < engine.flowRequired()) {
            SkillHud.actionBar(player, ChatColor.GRAY + "흐름이 없다 — 오의는 버튼이 아니라 읽어낸 순간이다 ("
                    + state.flow + "/" + engine.flowRequired() + ")");
            return;
        }
        if (state.ultimateUses >= engine.ultimateLimit(state.realm)) {
            SkillHud.actionBar(player, ChatColor.GRAY + "이 전투에서 이미 한 번 펼쳤다 (자재는 현경부터다)");
            return;
        }
        SkillEngine.Ultimate art = engine.ultimate(state.ultimateId);
        int pool = engine.pool(state.naegong);
        SkillEngine.Cast cast = engine.planUltimate(art, state.realm, state.energy, pool,
                state.armed, engine.weaponClassOf(player.getInventory().getItemInMainHand(), materialName(player)));
        if (cast == null) {
            SkillHud.actionBar(player, ChatColor.RED + "내력이 반도 남지 않았다 — 태울 것이 없다 ("
                    + state.energy + "/" + engine.ultimateCost(art, pool) + ")");
            return;
        }
        state.energy -= cast.paid();
        state.flow = 0;
        state.ultimateUses++;
        state.busyUntil = tick + cast.frames().total();
        state.lastCastTick = tick;
        touchCombat(state);

        // 세계가 알게 된다 — 시전 목격 = 강도 3 소문 (world_weight.rumor). MVT: 목격자에게 그대로 고지
        String stage = engine.ultimateStage(state.realm);
        for (Player viewer : player.getWorld().getPlayers()) {
            if (viewer.getLocation().distance(player.getLocation()) <= engine.cullBeyond()) {
                viewer.sendTitle(ChatColor.LIGHT_PURPLE + "「" + art.name() + "」",
                        ChatColor.GRAY + player.getName() + " — " + stage
                                + (cast.halved() ? " (불완전)" : ""), 5, 30, 15);
            }
        }
        ultimateTelegraph(player, cast, art);

        // 시전 중 무적(상한 20틱) 또는 슈퍼아머 — 오의별 명시 (skill_mechanics iframe_caps.오의)
        if (art.iframeTicks() > 0) {
            state.invulnerableUntil = tick + art.iframeTicks();
            player.setInvulnerable(true);
            pending.add(new Pending(tick + art.iframeTicks(), () -> {
                if (tick >= state.invulnerableUntil) {
                    player.setInvulnerable(false);
                }
            }));
        }
        if (art.superArmor()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,
                    cast.frames().total(), 2, false, false, false));
        }
        pending.add(new Pending(tick + cast.frames().startup(),
                () -> resolve(player, state, cast, null, art.name(), 0)));
        if (cast.halved()) {
            // 개안 — 벽 너머를 훔쳐본 대가: 내력 전소 + 내상 (원기 계통은 MVT 미배선 — 몸이 무거워진다)
            pending.add(new Pending(tick + cast.frames().total(), () -> {
                state.energy = 0;
                player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 600, 0, true, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 600, 0, true, true));
                player.sendMessage(ChatColor.RED + "내상 — 벽 너머의 것을 억지로 끌어왔다. 내력이 전소했다.");
                hud.energyBar(player, state);
            }));
        }
    }

    /**
     * 긴 선딜 = 긴 텔레그래프 — "세계가 멈춘 듯한 연출". 파티클 예산 내 <b>최우선권</b>.
     *
     * <p>【오의는 어떤 격과도 헷갈리지 않는다】 구분 수단이 셋이다:
     * ① <b>응집 고리</b> — 사방에서 몸으로 빨려드는 고리 (다른 어떤 모션도 고리를 쓰지 않는다)
     * ② <b>개시 섬광</b> — flash 1개 + 뇌격음 (팔레트에서 flash 를 쓰는 것은 오의와 심검뿐이다)
     * ③ <b>이름</b> — 32m 안의 모든 눈에 오의명 (world_weight: 시전 목격 = 강도 3 소문)
     * 파티클·소리·개수는 전부 등록부(skill_motion.yml ultimates)가 정한다.
     */
    private void ultimateTelegraph(Player player, SkillEngine.Cast cast, SkillEngine.Ultimate art) {
        SkillEngine.UltimateMotion m = engine.motionUltimate(art.id());
        if (m == null) {
            return;   // 등록되지 않은 오의는 연출이 없다 (판정은 그대로 나간다)
        }
        SkillEngine.Budget b = engine.motionBudget();
        sfx(player.getLocation(), m.chargeSounds());
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                cast.frames().startup(), 3, false, false, false));
        // 3D — 오의의 형체. 응집 내내 오므린 채 자라고, 개시의 순간 활짝 편다 (예약분을 쓴다: 깎이지 않는다)
        display.bloom(player, art.id(), cast.frames().startup(), art.range());

        int points = Math.min(m.ringPoints(), b.ultimateRingPoints());
        for (int t = 0; t < cast.frames().startup(); t += b.telegraphStepTicks()) {
            double phase = t / (double) Math.max(1, cast.frames().startup());
            pending.add(new Pending(tick + t, () -> {
                if (!player.isOnline()) {
                    return;
                }
                Location at = player.getLocation().add(0, 1, 0);
                double radius = art.range() * (1.0 - phase);   // 고리가 좁아지며 몸으로 빨려든다
                for (int i = 0; i < points; i++) {
                    double angle = Math.PI * 2 * i / points + phase * Math.PI;
                    hud.emitPriority(at.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius),
                            m.chargeParticle(), m.ringPerPoint(), 0.02, 0.0);
                }
                hud.emitPriority(at, m.coreParticle(), m.coreCount(), 0.3, 0.02);
            }));
        }
        pending.add(new Pending(tick + cast.frames().startup(), () -> {
            if (player.isOnline()) {
                Location at = player.getLocation().add(0, 1, 0);
                sfx(at, m.releaseSounds());
                hud.emitPriority(at, m.accent().particle(), m.accent().count(), 0.0, 0.0);
                hud.emitPriority(at, m.burst().particle(), m.burst().count(),
                        art.range() * m.burstSpreadRatio(), m.burst().extra());
            }
        }));
    }

    /** 오의별 부가 효과 — ultimate_arts.yml legacy_arts effect (수치는 config 가 정한다) */
    private void ultimateEffect(Player player, SkillEngine.State state, SkillEngine.Ultimate art,
                                LivingEntity target, double damage) {
        String effect = art.effect();
        SkillEngine.UltimateMotion m = engine.motionUltimate(art.id());
        if (m != null) {
            sfx(target.getLocation(), m.hitSounds());   // 오의의 적중음 — 오의마다 다르다 (혈해만리는 마시는 소리)
        }
        if (effect.contains("내구")) {
            // 제왕검형·군림 — 적중 시 다운 + 내구 50% 피해 (내구 = 이 세계의 체력. combat.yml durability)
            var max = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            double half = (max == null ? 20 : max.getValue()) * 0.5;
            applying = true;
            try {
                target.damage(half, player);
            } finally {
                applying = false;
            }
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                    engine.staggerTicks("다운"), 6, false, false, true));
        }
        if (effect.contains("약탈") || effect.contains("흡수")) {
            // 혈해만리 — 원기·내력 약탈 (마공 ①). 흡수량으로 자기를 채운다
            var max = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            player.setHealth(Math.min(max == null ? 20 : max.getValue(),
                    player.getHealth() + damage * 0.5));
            SkillEngine.State prey = stateOf(target);
            if (prey != null && prey.energy > 0) {
                int stolen = Math.min(prey.energy, 2);
                prey.energy -= stolen;
                state.energy = Math.min(engine.pool(state.naegong), state.energy + stolen);
            }
            event(target.getLocation().add(0, 1, 0), "흡성");   // 【채색 예외】 붉은 점 — 예외 자체가 경고다
        }
    }

    /** 원(圓) 히트박스 — 사방. 매화만개·혈해만리의 자리 (skill_mechanics hitbox_types 원) */
    private List<LivingEntity> circleTargets(Player player, SkillEngine.Cast cast) {
        double range = cast.range();
        List<LivingEntity> out = new ArrayList<>();
        for (org.bukkit.entity.Entity e : player.getNearbyEntities(range, range, range)) {
            if (out.size() >= cast.maxTargets()) {
                break;
            }
            if (e instanceof LivingEntity le && !le.equals(player) && le.isValid()
                    && le.getLocation().distance(player.getLocation()) <= range) {
                out.add(le);
            }
        }
        return out;
    }

    // ══════════ 연출 — 전부 등록부(config/skill_motion.yml) 판독 ══════════
    // 【등록제 규약】 이 아래에는 파티클 이름도 사운드 키도 없다. 이름을 등록부에서 받아 옮길 뿐이다.
    // 【팩 없이도 읽힌다】 격의 사다리(밝기·개수) · 응집(제자리, 2틱마다) · 발출(날아간다) ·
    //   두름(머문다) · 호신강기(몸을 두르는 고리) · 오의(고리 + 섬광 + 이름) — 여섯이 서로 다르다.

    /** 등록부의 소리 한 줄 — 1.21 의 Sound 는 열거형이 아니다. 바닐라 키를 문자열로 재생한다 */
    private void sfx(Location at, SkillEngine.Sfx sfx) {
        if (sfx != null && at.getWorld() != null) {
            at.getWorld().playSound(at, sfx.key(), sfx.volume(), sfx.pitch());
        }
    }

    private void sfx(Location at, List<SkillEngine.Sfx> sounds) {
        for (SkillEngine.Sfx s : sounds) {
            sfx(at, s);
        }
    }

    /**
     * 사건의 모션 — 무기 균열·파괴·절단 · 다운캐스트 · 호신강기 무효/관통/붕괴 · 패링 · 흐름 …
     * 등록되지 않은 이름은 조용히 아무것도 하지 않는다 (연출이 없다고 판정이 멈추면 안 된다).
     */
    private void event(Location at, String name) {
        event(at, name, null);
    }

    private void event(Location at, String name, Object data) {
        SkillEngine.EventMotion e = engine.motionEvent(name);
        if (e == null) {
            return;
        }
        SkillEngine.Fx f = e.fx();
        if (f.present()) {
            hud.emit(at, f.particle(), f.count(), f.spread(), f.extra(), data);
        }
        sfx(at, e.sounds());
    }

    /** 두름의 잔광 — 켜져 있다는 사실 자체가 정보다 (상대가 보고 판단한다). 호신강기는 몸을 두르는 고리 */
    private void aura(Location hand, LivingEntity body, String stance) {
        if (SkillEngine.GUARD.equals(stance)) {
            guardRing(body);
            return;
        }
        hud.emit(hand, engine.motionGrade(stance).aura(), false);
    }

    /**
     * 호신강기 — <b>몸을 두르는 고리</b> (forms.두름_몸.ring). 손끝 잔광(두름)과 실루엣이 다르다.
     * 그 차이가 곧 정보다: "저 자는 검에 기를 실은 것이 아니라 몸에 둘렀다 — 하위 격은 닿지 않는다."
     *
     * <p>두 층으로 돈다: <b>파티클 고리</b>(언제나) 위에 <b>3D 판 4장</b>(디스플레이 — 예산이 허락하면).
     * 3D 가 강등돼도 파티클 고리는 그대로 돈다 — 켜져 있다는 사실은 어떤 경우에도 보인다.
     */
    private void guardRing(LivingEntity body) {
        SkillEngine.FormMotion form = engine.motionForm(SkillEngine.GUARD);
        if (form == null || form.ringPoints() <= 0) {
            return;
        }
        Location at = body.getLocation().add(0, form.ringHeight(), 0);
        for (int i = 0; i < form.ringPoints(); i++) {
            double angle = Math.PI * 2 * i / form.ringPoints() + tick * 0.05;   // 천천히 돈다
            hud.emit(at.clone().add(Math.cos(angle) * form.ringRadius(), 0,
                            Math.sin(angle) * form.ringRadius()),
                    form.ringParticle(), form.ringPerPoint(), 0.02, 0.0);
        }
        display.ring(body, SkillEngine.GUARD);   // 그 위에 3D 판이 돈다 (심장박동 — 갱신이 끊기면 사라진다)
    }

    /**
     * 텔레그래프(응집) — 상대가 읽을 수 있어야 한다 ("응집은 빛으로 보인다", npc_combat 대칭 원칙).
     *
     * <p>선딜 2틱마다(budget.telegraph_step_ticks) 손끝에 격의 응집이 맺힌다. 예산은 <b>풀</b>이다:
     * 선딜이 길다고 파티클이 비례해 늘지 않는다 (budget.telegraph_pool). 응집을 보고 물러서면 맨 주먹이 온다.
     */
    private void telegraph(Location at, String grade, int boost) {
        SkillEngine.GradeMotion m = engine.motionGrade(grade);
        int n = m.charge().count() + boost;
        if (n <= 0) {
            return;
        }
        hud.emit(at, m.charge(), Math.min(n, engine.motionBudget().perPointTickMax()), false);
    }

    /** 응집 예약 — 선딜 동안 몇 번 발행할지는 예산 풀이 정한다 (프레임이 길어도 풀이 마르면 멈춘다) */
    private void scheduleTelegraph(java.util.function.Supplier<Location> at, String grade, int startup,
                                   int boost) {
        SkillEngine.GradeMotion m = engine.motionGrade(grade);
        SkillEngine.Budget b = engine.motionBudget();
        int per = m.charge().count() + boost;
        if (per <= 0 || startup < b.telegraphStepTicks()) {
            return;
        }
        int max = Math.max(1, b.telegraphPool() / per);          // 응집 풀 — 이만큼만 발행한다
        int emits = Math.min(max, startup / b.telegraphStepTicks());
        for (int i = 0; i < emits; i++) {
            pending.add(new Pending(tick + (long) i * b.telegraphStepTicks(),
                    () -> telegraph(at.get(), grade, boost)));
        }
        sfx(at.get(), m.chargeSounds());                          // 귀로도 예고한다 (감각이 낮아도 읽힌다)
    }

    /**
     * 타격 순간 — 격을 눈에 보이게.
     *
     * <p><b>타격 풀</b>(budget.impact_pool)을 대상 수로 나눈다: 광역 8인을 쳐도 예산이 터지지 않는다.
     * 아무리 나눠도 대상당 최소치(min_impact_per_target)는 남긴다 —
     * <i>맞았다는 사실</i>은 언제나 보여야 한다 (performance.yml over_budget: "핵심 타격 표시만 유지").
     */
    private void impact(Location at, String grade, SkillEngine.Strike strike, int targets) {
        SkillEngine.GradeMotion m = engine.motionGrade(grade);
        SkillEngine.Budget b = engine.motionBudget();
        HuntingGrounds.witnessQi(at, grade);   // 격을 본 자는 전의가 꺾인다 (npc_combat morale 상대_위세)

        int share = Math.max(b.minImpactPerTarget(), b.impactPool() / Math.max(1, targets));
        hud.emit(at, m.impact(), Math.min(m.impact().count(), share), false);
        hud.emit(at, m.accent(), false);
        sfx(at, m.impactSounds());
        if ("critical_success".equals(strike.tierId())) {
            event(at, "대성공");   // 급소 — 격과 무관하게 타격 위에 겹친다
        }
    }

    /**
     * 궤적(軌跡) — <b>보이는 모양 = 맞는 모양</b>. 히트박스 type 을 그대로 그린다 (motion_audit ②).
     *
     * <p>검은 벤다(호 — 부채꼴에 획이 남는다) · 창은 찌른다(선 — 점이 앞으로 뻗는다) ·
     * 매화검법 4타는 원을 그린다 · 보법은 지나온 자리에 잔상을 남긴다(돌).
     * 계열의 지문(weapon_styles)이 같은 '호'라도 검과 봉을 가른다.
     */
    private void trail(Player player, SkillEngine.Cast cast, String weaponClass, int stepIndex) {
        SkillEngine.Traj traj = engine.trajectory(cast.hitType());
        if (traj == null) {
            return;
        }
        SkillEngine.Style style = engine.weaponStyle(weaponClass);
        SkillEngine.SkillMotion motion = engine.motionSkill(cast.skillId());
        SkillEngine.Step step = motion == null ? null : motion.step(stepIndex);
        SkillEngine.GradeMotion grade = engine.motionGrade(cast.grade());
        // 발출(쏨)은 무공이 아니다 — 형태(forms.쏨)가 제 광선과 발사음을 갖는다.
        // 【발출이 두름·응집과 헷갈리지 않는 이유】 기가 **날아간다**: 8m 직선 광선 + 큰 발사음 + 끝의 작렬
        SkillEngine.FormMotion shot = SkillEngine.SHOT.equals(cast.skillId()) ? engine.shotForm(cast.grade()) : null;

        // ─── 3D 층 (파티클 위에 얹는다) ───
        // ① 병기 휘두름 — 손에 든 것 그 자체가 히트박스를 훑는다 (팩 게이트 자동 충족)
        // ② 기의 획 — 격이 실렸을 때만 그 위에 겹친다 (검기 이상)
        // ③ 발출 — 기가 날아간다 (한 자루의 빛나는 획)
        // 셋 다 실패해도(예산·팩·미등록) 아래 파티클 층이 그대로 돈다 — 강등이지 실종이 아니다
        boolean solid;
        if (shot != null) {
            solid = display.bolt(player, shot.name(), cast.range());
        } else {
            int swingTicks = (int) Math.max(cast.frames().total(), swingInterval(player));
            solid = display.sweep(player, cast.hitType(), weaponClass,
                    cast.range(), cast.angle(), swingTicks);
            solid |= display.streak(player, cast.hitType(), cast.grade(), cast.range(), cast.angle());
        }

        // 점당 파티클: 초식이 정한 개수. 격이 실렸으면 격의 궤적 파티클이 무기의 것을 덮는다
        //   (검기를 두른 검은 '검의 획'이 아니라 '기의 획'을 남긴다 — 그것이 격이 보인다는 말이다)
        String particle = shot != null ? shot.beamParticle()
                : cast.manifested() ? grade.trailParticle()
                : step != null && step.particle() != null ? step.particle()
                : "선".equals(cast.hitType()) || "시".equals(cast.hitType()) ? style.thrust() : style.arc();
        int per = shot != null ? shot.beamPerPoint() : step == null ? 1 : step.count();
        // 3D 획이 실제로 떴으면 궤적 파티클은 물러선다 (display.blend) — 둘 다 뿌리면 지저분하고 비싸다.
        // 0 으로는 만들지 않는다: 획이 지나간 자리는 어떤 경우에도 남아야 한다 (강등 ≠ 실종)
        if (solid) {
            per = engine.displayBlend().damp(per);
        }
        SkillEngine.Budget b = engine.motionBudget();
        int points = Math.min(traj.points(), Math.max(1, b.trailPool() / Math.max(1, per)));

        Location eye = player.getEyeLocation();
        Vector dir = player.getLocation().getDirection().normalize();
        Vector flat = dir.clone().setY(0);
        if (flat.lengthSquared() < 1e-6) {
            flat = new Vector(1, 0, 0);
        }
        flat.normalize();

        switch (traj.shape()) {
            case "arc" -> {          // 호 — 정면 부채꼴을 훑는다 (angle 을 points 로 나눈다)
                double radius = cast.range() * traj.radiusRatio();
                double half = Math.toRadians(cast.angle() / 2.0);
                for (int i = 0; i < points; i++) {
                    double t = points == 1 ? 0.5 : i / (double) (points - 1);
                    double a = -half + 2 * half * t;
                    Vector v = flat.clone().rotateAroundY(a).multiply(radius);
                    hud.emit(eye.clone().add(v).subtract(0, 0.3, 0), particle, per, 0.05, 0.0);
                }
            }
            case "line", "shot" -> { // 선·시 — 앞으로 뻗는다 (찌르기·참격·투척)
                for (int i = 1; i <= points; i++) {
                    Location p = eye.clone().add(dir.clone().multiply(
                            Math.min(cast.range(), i * traj.step())));
                    hud.emit(p, particle, per, 0.05, 0.0);
                }
            }
            case "circle", "ring" -> {   // 원·진 — 사방
                double radius = cast.range() * traj.radiusRatio();
                Location center = player.getLocation().add(0, 1, 0);
                for (int i = 0; i < points; i++) {
                    double a = Math.PI * 2 * i / points;
                    hud.emit(center.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius),
                            particle, per, 0.05, 0.0);
                }
            }
            case "dash" -> {         // 돌 — 지나온 자리에 잔상 (뒤로 그린다)
                for (int i = 1; i <= points; i++) {
                    hud.emit(player.getLocation().add(0, 0.8, 0)
                                    .subtract(dir.clone().multiply(i * traj.step())),
                            particle, per, 0.08, 0.0);
                }
            }
            default -> {             // aura — 태세·가드태세·시전: 몸에 머문다
                hud.emit(player.getLocation().add(0, 1, 0), particle,
                        Math.min(per * points, b.trailPool()), 0.35, 0.0);
            }
        }
        if (shot != null) {
            // 광선의 끝에서 작렬한다 — 어디까지 갔는지 눈에 남는다 (빗나가면 그 자리에 코스트만 흩어진다)
            hud.emit(eye.clone().add(dir.clone().multiply(cast.range())), shot.burst(), false);
            sfx(player.getLocation(), shot.releaseSounds());
            return;
        }
        sfx(player.getLocation(), step != null && step.sound() != null ? step.sound() : style.swing());
    }

    // ══════════ 관리 명령 접합 (MvtCommand 가 부른다) ══════════

    /** /혼천 경지 — MVT 는 캐릭터 시트가 없다. 경지·내공을 손으로 세운다 (검증 도구) */
    public void setRealm(Player player, String realm, double naegong) {
        SkillEngine.State state = state(player);
        state.realm = realm;
        state.naegong = naegong;
        state.energy = engine.pool(naegong);
        state.armed = null;
        state.flow = 0;
        state.ultimateUses = 0;
        state.combatUntil = -1;
        hud.energyBar(player, state);
        player.sendMessage(ChatColor.GOLD + Glyphs.realmCrest(realm) + " " + realm
                + ChatColor.WHITE + " — 내공 " + String.format("%.2f", naegong)
                + " / 내력 " + state.energy
                + ChatColor.GRAY + " · 열린 태세: "
                + (engine.armableStances(realm).isEmpty() ? "없음 (외공기뿐)"
                        : String.join(" → ", engine.armableStances(realm))));
        String stage = engine.ultimateStage(realm);
        if (stage != null) {
            player.sendMessage(ChatColor.LIGHT_PURPLE + "오의 — " + stage
                    + ChatColor.GRAY + " (Shift+F: 오의 선택 · F: 시전 · 발동권 = 아슬아슬한 성공 이상 공방 "
                    + engine.flowRequired() + "회)"
                    + (engine.isAwakening(realm) ? ChatColor.DARK_GRAY
                            + "  — 개안: 불완전 시전 (위력 절반 · 직후 내력 전소 + 내상)" : ""));
        }
    }

    /** /혼천 운기 — 운기조식 1구간 (전투 밖). 하한 1 + 순도 배율 (internal_energy recovery) */
    public void meditate(Player player) {
        SkillEngine.State state = state(player);
        int pool = engine.pool(state.naegong);
        if (pool <= 0) {
            player.sendMessage(ChatColor.GRAY + "단전이 비어 있다 — 돌릴 기가 없다.");
            return;
        }
        int before = state.energy;
        state.energy = Math.min(pool, state.energy + engine.meditationRecover(state.naegong, 1.0));
        hud.energyBar(player, state);
        event(player.getLocation().add(0, 1, 0), "운기조식");
        player.sendMessage(ChatColor.AQUA + "한 구간을 앉았다 — 내력 " + before + " → " + state.energy
                + "/" + pool);
    }

    // ══════════ 정리 (performance.yml effects.cleanup_on) ══════════

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
        clashCounts.remove(event.getPlayer().getUniqueId());
        display.clear(event.getPlayer().getUniqueId());   // 떠난 몸의 형체는 남지 않는다
    }

    /** 죽은 몸의 내력은 남지 않는다 (F-P2 cleanup_on death) */
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        npcStates.remove(event.getEntity().getUniqueId());
        clashCounts.remove(event.getEntity().getUniqueId());
        display.clear(event.getEntity().getUniqueId());   // 죽은 몸의 고리도 (심장박동이 멎기 전에)
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        SkillEngine.State state = states.get(event.getPlayer().getUniqueId());
        if (state != null) {
            state.armed = null;
            state.comboIndex = 0;
        }
        display.clear(event.getPlayer().getUniqueId());   // 세계를 건너간 몸의 형체는 저쪽에 남기지 않는다
    }

    // ══════════ 도우미 ══════════

    private String skillInHand(Player player) {
        String id = SKILL_BY_WEAPON_CLASS.get(engine.weaponClassOf(player.getInventory().getItemInMainHand(), materialName(player)));
        return id != null && engine.hasActionData(id) ? id : null;
    }

    /** 계열 공속 → 스윙 간격(틱). 혼천 병기가 아니면 0 (프레임이 전부다) */
    private long swingInterval(Player player) {
        Weapons.Series series = Weapons.seriesOf(player.getInventory().getItemInMainHand());
        return series == null ? 0L : Math.round(20.0 / series.attackSpeed());
    }

    private static String materialName(Player player) {
        Material m = player.getInventory().getItemInMainHand().getType();
        return m.isAir() ? "AIR" : m.name();
    }

    private String weaponGrade(Player player) {
        return engine.weaponGradeOf(player.getInventory().getItemInMainHand(), materialName(player));
    }

    private static Location handLocation(Player player) {
        return player.getEyeLocation().add(player.getLocation().getDirection().multiply(0.8)).subtract(0, 0.2, 0);
    }

    private Location swingLocation(Player player, SkillEngine.Cast cast) {
        return player.getEyeLocation()
                .add(player.getLocation().getDirection().multiply(Math.min(2.0, cast.range() / 2)));
    }

    private void itemCooldown(Player player, int ticks) {
        Material m = player.getInventory().getItemInMainHand().getType();
        if (!m.isAir() && ticks > 0) {
            player.setCooldown(m, ticks);   // 바닐라 아이템 쿨다운 스와이프 = 스킬 쿨다운 (mc_action_mapping 2장)
        }
    }
}
