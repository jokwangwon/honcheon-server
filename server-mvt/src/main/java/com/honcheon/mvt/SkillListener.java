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

    private static final String CD_SHOT = "발출";
    /** 기본 초식(무공 없는 손)의 연출 간격 — 바닐라 연타에 획이 겹쳐 쌓이지 않게 */
    private static final String CD_BASIC = "기본초식";
    /** NPC 격 시전 간격 — 응집과 응집 사이 (라운드 = 두름 과금 주기와 같은 눈금) */
    private static final String CD_QI = "npc_격";
    /** NPC 근접 사거리 — 이 안에 들어와야 응집을 시작한다 (config 등록 대기: npc_combat.yml reach) */
    private static final double NPC_REACH = 3.5;
    /** 응집이 끝난 뒤 격이 실려 있는 창 — 바닐라 근접 AI 의 스윙 타이밍을 우리가 못 정한다 (근사) */
    private static final int NPC_HOT_TICKS = 20;
    /**
     * NPC 의 고정 판정치 — {@code combat.yml attack.defender_bonus}: <i>"+2d6(플레이어) 또는 +7(NPC)"</i>.
     * <b>한쪽만 굴린다</b> — 플레이어가 2d6 을 굴리고 NPC 는 기댓값(7)으로 선다.
     * ({@code tools/combat_audit.py margin_dist} 가 쓰는 것과 같은 규약이다 — 도구와 엔진은 같은 셈을 한다)
     */
    private static final int NPC_JUDGMENT = 7;
    /** 태세의 글자가 액션바에 머무는 시간 — statusBar 가 다시 덮기 전 (0.75초. 읽을 수는 있어야 한다) */
    private static final int STANCE_READ_TICKS = 15;
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
    /**
     * <b>몸에 밴 태세</b> — {@code /혼천 태세 <회피|막기|흘리기|자동>}. 없으면 등록부의 기본값(자동).
     * 몸짓(방패·웅크림·질주)이 그 순간 이것을 <b>덮어쓴다</b> (combat.yml defender_stance_mc.precedence).
     */
    private final Map<UUID, String> stancePin = new HashMap<>();
    /** 지금 이 몸이 서 있는 태세 — HUD 가 그린다 (화면이 판정에 대해 거짓말하지 않게) */
    private final Map<UUID, String> stanceNow = new HashMap<>();
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
        plugin.getServer().getScheduler().runTaskTimer(plugin, Metrics.wrap("skill_execution", this::tick), 1L, 1L);
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
     * 모션 진단 — <b>인게임에 못 들어가는 눈</b>(RCON·콘솔)이 "3D 획이 정말 떴는가"를 확인하는 창구.
     *
     * <p><b>배선</b>: {@code MvtCommand} 에 {@code /혼천 모션진단 [초]} 를 붙이고 이 줄들을 그대로 뿌린다.
     * 안 뜬 것(등록부가 획을 안 준 계열)과 못 뜬 것(예산 강등)이 <b>다른 사건</b>으로 보인다.
     */
    public List<String> motionDiagnostics(int seconds) {
        return display.diagnostics(seconds);
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

    /**
     * 이 몸의 무공 상태 — <b>경지는 원장에서 온다</b> (원장의 경지는 봇의 시트에서 왔다).
     *
     * <p>여기가 {@code "이류"} 하드코딩이 살아 있던 자리다. 이제 태어나는 상태는 몸에 실린 원장을 보고,
     * 원장이 비었으면(접합 전) 등록부의 첫 단으로 선다 — <b>코드가 경지를 지어내지 않는다.</b>
     */
    public SkillEngine.State state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), id -> {
            SkillEngine.State fresh = new SkillEngine.State();
            PlayerLedger ledger = plugin.ledger(id);
            fresh.realm = ledger.realm(engine.baseRealm());
            fresh.naegong = ledger.naegong();
            fresh.energy = engine.pool(fresh.naegong);
            return fresh;
        });
    }

    // ══════════ 시트 접합 — 봇의 장부가 마크의 몸에 실린다 ══════════

    /**
     * <b>봇의 시트를 이 몸에 싣는다.</b> 스냅숏이 올 때마다(20초) · 접속할 때마다 부른다.
     *
     * <p><b>정본은 봇이다.</b> 내려오는 값은 <b>절대값</b>(덮어쓰기)이지 증분이 아니다 — 그래서
     * 마크가 수련으로 먼저 더해 둔 값이 있어도 <b>영구히 갈라질 수 없다</b>: 다음 스냅숏이 진실로 되돌린다.
     * 마크가 쌓은 것은 {@code cultivation_logged} 로 올라가 봇의 시트를 바꾸고, 바뀐 시트가 여기로 온다.
     *
     * <p>접합되지 않은 몸에는 시트가 없다. 그 몸은 <b>강호에 없는 사람</b>이다 — 등록부의 첫 단(범인)으로
     * 서고, 화후는 쌓이지 않는다 (쌓을 장부가 없다). {@link #nag} 가 그 사실을 본인에게 말한다.
     *
     * @return 시트가 실렸는가 (false = 접합 전이거나 봇이 꺼져 있다)
     */
    public boolean syncSheet(Player player) {
        PlayerLedger ledger = plugin.ledger(player.getUniqueId());
        WorldBridge.Sheet sheet = WorldBridge.state().sheet(player.getUniqueId());
        if (sheet == null) {
            ledger.setLinked(false);
            return false;
        }
        ledger.applySheet(sheet);
        SkillEngine.State state = state(player);
        String before = state.realm;
        state.realm = ledger.realm(engine.baseRealm());
        state.naegong = ledger.naegong();
        state.energy = Math.min(state.energy < 0 ? 0 : state.energy, engine.pool(state.naegong));
        if (!java.util.Objects.equals(before, state.realm)) {
            // 승급은 강호가 인정하는 것이다 — 마크는 그것을 전해 듣는다
            state.armed = null;
            state.energy = engine.pool(state.naegong);
            player.sendTitle(ChatColor.GOLD + Glyphs.realmCrest(state.realm) + " " + state.realm,
                    ChatColor.GRAY + "강호가 너를 그렇게 부른다", 10, 60, 20);
        }
        return true;
    }

    /** 접속한 모든 몸에 시트를 다시 싣는다 — 스냅숏이 바뀔 때마다 (메인 스레드에서 부를 것) */
    public void syncAllSheets() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            syncSheet(player);
        }
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
            settleTraining(player, state);   // 날이 바뀌면 하루치 수련이 배분대로 갈린다
            // 생명 — 경지·장비가 바뀌면 다음 틱에 몸이 따라온다 (훅은 빠뜨리면 조용히 틀리고, 대조는 못 빠뜨린다)
            hud.vitalityTick(player, state, plugin.ledger(player.getUniqueId()));
            hud.energyBar(player, state);
            // 내구·부상·태세는 격이 없어도 보인다 — 게이트를 두면 **삼류가 제 목숨을 영영 못 본다**
            hud.statusBar(player, state, tick, stanceOf(player));
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
        scheduleTelegraph(mob::getEyeLocation, npc.grade(), f.startup(), 0, 1.0f);
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
                // 무공이 실리지 않는 손 — 판정은 바닐라 그대로. 그러나 **획은 뜬다** (기본 초식)
                basicSwing(player);
                return;
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
                        new SkillEngine.Strike(0, 0, "success", "성공", true, 0), 1, 1.0f);
            }
        }

        // ★ 【맞는 쪽의 선택】 방어자가 태세를 세운다 — 회피(민첩+경공) · 막기(근력) · 흘리기(감각)
        //   여기가 수련의 절반이 사는 자리다. 그전엔 근력·감각·민첩을 아무리 키워도
        //   **맞을 때 아무 일도 일어나지 않았다.**
        int attackers = attackersOn(target);
        boolean surrounded = engine.surrounded(attackers);
        String stance = chooseStance(target, surrounded);
        Guardline line = stance == null ? null : guardline(target, stance, surrounded);
        String note = stanceNote(target, stance, surrounded);

        double incoming = event.getDamage();
        if (line != null) {
            // 대립 판정 — 공격 총합(+7) vs 태세 판정치 + 2d6(플레이어) / +7(NPC)
            int atk = foeAttackScore(attacker, target, attackers);
            int roll = target instanceof Player ? roll2d6() : NPC_JUDGMENT;
            int margin = atk - (line.score() + roll);
            if (margin < 0) {
                // 태세가 이겼다 — 안 맞는다. 회피면 GyeonggongListener(MONITOR)가 몸을 뒤로 뺀다
                event.setCancelled(true);
                stanceSucceeded(target, line, margin, note);
                return;
            }
            // 맞았다 — 그러나 태세는 값을 한다: 피해 = 무기 + 무공 + 격 + floor(마진/2) − 경감
            //   ★ floor(마진/2) 항이 지금까지 NPC 공격에 통째로 빠져 있었다 (combat.yml damage.formula).
            //     그 항이 없으면 방어 판정치가 명중/빗나감만 흔들고 **피해의 크기를 못 흔든다** —
            //     그러면 흘리기의 −2 는 순손해가 되고, 판정을 키우는 수련이 절반만 산다.
            incoming += foeTechniquePower(attacker) + Math.floorDiv(margin, 2);
            incoming -= line.soak();
            stanceFailed(target, line, margin, note);
            if (line.clashes()) {
                clashWeapon(target, grade);   // 막기는 무기를 태워 목숨을 산다 (회피·흘리기는 접촉이 없다)
            }
        }
        incoming -= armorSoak(target, grade);   // 갑옷 — 태세와 무관하게 언제나. 단 강기 앞에서는 0

        Defense defense = defend(target, attacker, grade, Math.max(0.0, incoming));
        if (defense.blocked() || defense.damage() <= 0.0) {
            event.setCancelled(true);
            return;
        }
        event.setDamage(defense.damage());
        if (line == null) {
            clashWeapon(target, grade);   // 태세 층이 없던 시절의 경로 (Growth 미배선) — 옛 동작 그대로
        }
    }

    /**
     * <b>빌드의 대가를 규칙이 아니라 화면이 가르친다.</b>
     *
     * <p>둘 있다: ① 회피를 고르고 서 있었는데 <b>둘에게 잡혔다</b> — "몸을 뺄 자리가 없다"
     * (신법 빌드가 다구리에서 벌거벗는 순간. 그것이 규칙이고, 화면이 그 말을 한다).
     * ② 막기를 골랐는데 <b>손이 비었다</b> — "받을 것이 없다" (경감은 든다. 부러질 물건이 없을 뿐).
     */
    private String stanceNote(LivingEntity body, String stance, boolean surrounded) {
        Growth growth = Growth.get();
        if (growth == null || stance == null || !(body instanceof Player player)) {
            return "";
        }
        String wanted = stancePin.get(player.getUniqueId());
        if (wanted == null && player.isSprinting()) {
            wanted = engine.stanceOfGesture("isSprinting");
        }
        if (surrounded && wanted != null && growth.lostWhenSurrounded(wanted)) {
            return ChatColor.DARK_RED + " │ " + stanceLabel("회피_봉쇄");
        }
        EntityEquipment gear = player.getEquipment();
        if (growth.clashes(stance) && (gear == null || gear.getItemInMainHand().getType().isAir())) {
            return ChatColor.GRAY + " │ " + stanceLabel("맨손_막기");
        }
        return "";
    }

    /**
     * 방어자가 지금 세우는 태세 한 줄 — <b>플레이어든 NPC든 같은 규칙</b> (대칭 원칙).
     * {@code null} 이면 성장 축이 미배선 — 호출자는 조용히 옛 동작(고정 난이도)으로 돌아간다.
     */
    private Guardline defenderStance(LivingEntity target) {
        if (Growth.get() == null) {
            return null;
        }
        boolean surrounded = engine.surrounded(attackersOn(target));
        String stance = chooseStance(target, surrounded);
        return stance == null ? null : guardline(target, stance, surrounded);
    }

    /** 2d6 — 전투는 주사위를 쓴다 (판정 밖의 조성기는 난수를 안 쓴다. 전투만 예외다) */
    private static int roll2d6() {
        return ThreadLocalRandom.current().nextInt(6) + 1 + ThreadLocalRandom.current().nextInt(6) + 1;
    }

    /**
     * <b>태세가 이겼다</b> — 화면이 그 사실을 말한다 (파티클·소리·글자. 팩이 없어도 셋 다 보인다).
     * 회피면 {@code event.setCancelled(true)} 위에서 {@link GyeonggongListener#onDodged} 가
     * 몸을 <b>실제로 뒤로 뺀다</b> — 경공 담당이 깔아 둔 이음매다.
     */
    private void stanceSucceeded(LivingEntity body, Guardline line, int margin, String note) {
        stanceFx(body, line.stance());
        if (!(body instanceof Player player)) {
            return;
        }
        stanceNow.put(player.getUniqueId(), line.stance());
        hud.flash(player, ChatColor.AQUA + stanceLabel(line.stance())
                + ChatColor.DARK_GRAY + " │ 방어 " + line.score() + " (마진 " + margin + ")" + note,
                tick + STANCE_READ_TICKS);
    }

    /** <b>태세가 무너졌다</b> — 그래도 경감은 든다 (막는 것은 판정이 아니라 몸이다) */
    private void stanceFailed(LivingEntity body, Guardline line, int margin, String note) {
        stanceFx(body, line.soak() > 0 ? line.stance() : "실패");
        if (!(body instanceof Player player)) {
            return;
        }
        stanceNow.put(player.getUniqueId(), line.stance());
        String head = line.soak() > 0
                ? ChatColor.YELLOW + stanceLabel(line.stance())
                        + ChatColor.DARK_GRAY + " (경감 −" + line.soak() + ")"
                : ChatColor.RED + stanceLabel("실패");
        hud.flash(player, head + ChatColor.DARK_GRAY + " │ 마진 " + margin + note,
                tick + STANCE_READ_TICKS);
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
            return;
        }
        basicSwing(player);   // 무공이 없어도 병기는 궤적을 그린다 (허공을 갈라도 획은 남는다)
    }

    // ─── 콤보 ───

    /**
     * <b>【무공 없는 손】</b> 병기를 들고 좌클릭한다 — 그것만으로 궤적이 뜬다.
     *
     * <p>사용자 보고("공격 모션은 아직 안 바뀐 거 같고")의 원인이 여기 있었다: 3D 층이 <b>무공 시전 경로
     * 안에만</b> 살아 있었고, 무공을 안 배운 손(= 가장 흔한 손)은 그 경로에 들어오지도 못했다.
     *
     * <p><b>무공은 궤적을 바꾸는 것이지, 무공이 없다고 궤적이 없는 것이 아니다.</b>
     * 히트박스·프레임은 등록부({@code basic_strike})가 준다 — 코드가 지어내지 않는다.
     * 판정은 건드리지 않는다 (바닐라 피해 그대로 = 외공기). 이것은 <b>연출 층</b>이다.
     */
    private void basicSwing(Player player) {
        SkillEngine.State state = state(player);
        if (state.onCooldown(CD_BASIC, tick)) {
            return;   // 바닐라 연타에 획이 겹쳐 쌓이지 않게 (등록부 basic_strike.cooldown_ticks)
        }
        String weaponClass = engine.weaponClassOf(
                player.getInventory().getItemInMainHand(), materialName(player));
        SkillEngine.Basic basic = engine.basicStrike(weaponClass);
        if (basic == null) {
            return;   // 활·무관·짐승 — 우리가 얹을 것이 없다 (바닐라가 제 일을 한다)
        }
        state.cooldownUntil.put(CD_BASIC, tick + engine.basicCooldownTicks());

        // 격은 두른 것을 그대로 쓴다 — 검기를 두르고 그냥 휘둘러도 **기의 획**이 나간다 (무공과 무관하게)
        String grade = offense(state) == null ? SkillEngine.BARE : offense(state);
        int swingTicks = (int) Math.max(basic.frames().total(), swingInterval(player));

        strike(player, basic.trail(), grade, weaponClass, basic.range(), basic.angle(), swingTicks);
    }

    /**
     * 한 번의 손 — <b>3D 층 + 몸의 자세</b>. 무공이 있든 없든 여기를 지난다 (경로가 하나여야 거짓말이 없다).
     *
     * @return 3D 형체가 실제로 떴는가 (떴으면 궤적 파티클은 물러선다)
     */
    private boolean strike(Player player, String hitType, String grade, String weaponClass,
                           double range, double angle, int swingTicks) {
        boolean solid = display.slash(player, hitType, grade, weaponClass, range, angle, swingTicks);
        if ("시".equals(hitType)) {
            // 던진 물건은 **실제로 날아간다** (암기) — 복제가 아니다. 손을 떠났으므로
            solid |= display.thrown(player, weaponClass, range);
        }
        posture(player, weaponClass, hitType);
        return solid;
    }

    /**
     * 몸의 자세(體勢) — <b>되는 것만</b>.
     *
     * <p><b>못 하는 것</b>: 바닐라 클라이언트에서 플레이어의 <b>팔다리 각도는 서버가 줄 수 없다</b>
     * (애니메이션은 클라이언트가 돌리고, 팩으로 플레이어 지오메트리를 바꿀 수 없다). "다리를 뒤로 보내고
     * 허리를 쓰는" 골격 애니메이션은 바닐라 프로토콜에 자리가 없다. 억지로 흉내 내면 조작감만 죽는다.
     *
     * <p><b>하는 것</b>: 전진(lunge — <b>창의 5m 는 몸이 나가야 5m 다</b>) · 자세(pose — 바닐라가 가진
     * 자세만: SWIMMING 은 몸을 눕히고 · SNEAKING 은 낮추고 · SPIN_ATTACK 은 돈다) · 정지(프레임이 이미
     * 발을 묶는다). 자세는 <b>관중의 눈</b>에 보이는 것이고, 제 클라이언트는 제 자세를 스스로 그리므로
     * 1인칭 조작감을 해치지 않는다 — 그것이 이 수단을 고른 이유다.
     *
     * <p>공중·물속·활강 중에는 손대지 않는다 (바닐라 자세와 싸우면 몸이 굳는다).
     */
    private void posture(Player player, String weaponClass, String hitType) {
        SkillEngine.Body body = engine.body(weaponClass, hitType);
        SkillEngine.BodyLimits limits = engine.bodyLimits();
        if (body == null || player.isSwimming() || player.isGliding() || player.isInsideVehicle()) {
            return;
        }
        if (limits.requireGround() && !player.isOnGround()) {
            return;   // 공중에서 밀면 낙하와 싸운다 — 그것은 무공이 아니라 버그로 읽힌다
        }
        if (body.lunge() != 0.0) {
            Vector push = player.getLocation().getDirection().setY(0);
            if (push.lengthSquared() > 1.0e-6) {
                // 체중이 실린다 — 창은 나가고(0.42), 겸은 당긴다(−0.10). 상한은 등록부가 이미 물렸다
                player.setVelocity(player.getVelocity().add(
                        push.normalize().multiply(body.lunge())));
            }
        }
        if (!body.hasPose()) {
            return;
        }
        try {
            player.setPose(org.bukkit.entity.Pose.valueOf(body.pose()), true);
        } catch (IllegalArgumentException e) {
            return;   // 등록부가 모르는 자세를 적었다 — 조용히 지나간다 (연출이 판정을 멈추지 않는다)
        }
        pending.add(new Pending(tick + body.poseTicks(), () -> {
            if (player.isOnline() && player.hasFixedPose()) {
                player.setPose(org.bukkit.entity.Pose.STANDING, false);   // 몸을 돌려준다 (굳지 않게)
            }
        }));
    }

    /**
     * 절기 한 타를 <b>정본 파이프라인</b>으로 흘려보낸다 (commit → resolve).
     *
     * <p>무공 카탈로그 담당의 청구서: 지금 절기는 출처 없는 {@code damage(double)} 로 근사되어
     * <b>기 방어·무기 격돌·사냥 적립을 못 탄다</b>. 이 문을 통과하면 전부 탄다 (한 대는 한 대다).
     *
     * @param step    콤보 칸 (0-기반). 단발형은 0
     * @param primary 주 대상 (없으면 히트박스가 찾는다)
     * @return 시전됐는가 (자세가 안 돌아왔거나 쿨다운이면 false)
     */
    public boolean castArt(Player player, String skillId, int step, LivingEntity primary) {
        if (skillId == null || !engine.hasActionData(skillId)) {
            return false;
        }
        SkillEngine.State state = state(player);
        if (tick < state.busyUntil || state.onCooldown(skillId, tick)
                || tick - state.lastCastTick < engine.duplicateWindowTicks()) {
            return false;
        }
        String weaponClass = engine.weaponClassOf(
                player.getInventory().getItemInMainHand(), materialName(player));
        SkillEngine.Cast cast = engine.planCombo(
                skillId, step, state.realm, state.energy, offense(state), weaponClass);
        state.busyUntil = tick + Math.max(cast.frames().total(), swingInterval(player));
        state.lastCastTick = tick;
        state.energy -= cast.paid();
        applyCooldown(player, state, skillId, cast);
        commit(player, state, cast, primary, engine.skillName(skillId), step);
        return true;
    }

    /**
     * 쿨다운 — <b>【고침】 한 번도 적용된 적이 없던 규칙</b>.
     * {@code planCombo} 가 {@code cooldown_ticks} 를 읽지 않아 {@code Cast.cooldownTicks()} 가 늘 0 이었다.
     * 태조장권 60 · 매화검법 90 · 흑살도법 180 이 등록부에만 있었다.
     */
    private void applyCooldown(Player player, SkillEngine.State state, String skillId,
                               SkillEngine.Cast cast) {
        int cd = cast.cooldownTicks();
        if (cd <= 0) {
            return;   // 콤보의 1·2타는 쿨다운이 없다 — 연타가 권법의 값이다 (마무리 타에만 붙는다)
        }
        state.cooldownUntil.put(skillId, tick + cd);
        state.comboIndex = 0;             // 마무리를 냈다 — 콤보는 처음으로 돌아간다
        itemCooldown(player, cd);         // 바닐라 스와이프 = 쿨다운 (mc_action_mapping 2장)
    }

    private void swing(Player player, String skillId, LivingEntity primary) {
        SkillEngine.State state = state(player);
        if (tick - state.lastCastTick < engine.duplicateWindowTicks()) {
            return;   // F-R1 — 같은 틱 중복 시전 폐기
        }
        if (tick < state.busyUntil) {
            SkillHud.actionBar(player, ChatColor.DARK_GRAY + "아직 자세가 돌아오지 않았다");
            return;   // 경직·후딜 — 연타 방지
        }
        if (state.onCooldown(skillId, tick)) {
            // 무공이 쉬는 동안에도 손은 움직인다 — 기본 초식으로 친다 (획은 뜬다. 무공만 안 나갈 뿐)
            basicSwing(player);
            return;
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
        applyCooldown(player, state, skillId, cast);
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
            // 응집음도 초식의 배율을 탄다 — '조용한 초식'은 예고부터 조용하다
            scheduleTelegraph(() -> handLocation(player), cast.grade(), f.startup(), boost,
                    soundScale(step));
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
                // 실행력 = 능력치 + 무공 숙련 + 무기 보정 + 경지 격차(gm_modifiers realm_gap) + 상태 보정
                // ★ 능력치 항이 오래 빠져 있었다 — 그래서 **같은 경지의 두 사람이 완전히 같은 사람**이었다.
                //   무엇이 실리는지는 **병기가 정한다** (도=근력·검=민첩·암기=감각, combat.yml attacker_attribute).
                //   한 과목이 공격과 방어를 함께 사면 그 과목이 지배 전략이 된다 — 그것이 우리가 버린 MMO 문법이다.
                boolean hostile = target instanceof Monster;
                PlayerLedger led = plugin.ledger(player.getUniqueId());
                int mastery = led.levelOf(engine.skillName(cast.skillId()), plugin.progression());
                Growth growth = Growth.get();
                // ★ 시트가 없는 몸은 **그 경지의 표준 무인**이다 — 갓 접속한 자가 능력치 0.0 이라
                //   +0 으로 싸우는데 같은 경지의 산적은 realmAttr 로 표준치를 받고 있었다.
                //   (Vitality.cheOf 가 이미 같은 답을 갖고 있다 — 원장이 비면 경지 대체값)
                int attrBonus = growth == null ? 0 : growth.attackBonus(led,
                        engine.weaponClassOf(player.getInventory().getItemInMainHand(), null),
                        engine.realmAttr(state.realm));
                int execBase = attrBonus + mastery + engine.weaponJudgmentBonus(weaponGrade(player))
                        + engine.realmGapBonus(state.realm, foeRealm(target, hostile))
                        + (engine.isDepleted(state.energy) ? -2 : 0);   // 내공 고갈 = 판정 -2

                // ★ 【대칭】 상대도 태세를 세운다 — 저항치가 고정 난이도(보통 12)가 아니라
                //   **그 몸이 고른 방어**다. 그전엔 상대가 무엇을 하든 언제나 12 였다
                //   (등록부는 회피·막기·흘리기를 적어 뒀는데, 엔진의 NPC 는 서 있기만 했다).
                //   저항 = 태세 판정치 + 7 (NPC 는 굴리지 않는다 — combat.yml defender_bonus).
                Guardline foeLine = defenderStance(target);
                int resist = foeLine != null ? foeLine.score() + NPC_JUDGMENT
                        : engine.difficulty(hostile ? "보통" : "쉬움");
                int roll = roll2d6();   // 전투는 주사위를 쓴다

                SkillEngine.Strike strike = engine.strike(cast, execBase, roll, resist);
                touchCombat(state);
                if (engine.isFlowTier(strike.tierId())) {
                    gainFlow(player, state);   // 발동권 — 읽어낸 순간이 쌓인다 (오의 충전의 실체)
                }
                if (!strike.hit()) {
                    if (foeLine != null) {
                        stanceFx(target, foeLine.stance());   // 상대가 무엇으로 살아났는지 보인다
                    }
                    continue;
                }
                // 상대의 경감 — 태세는 맞아도 값을 한다 (막기 −3 · 흘리기 −1). 갑옷은 그 뒤에 든다
                double raw = strike.damage();
                if (foeLine != null) {
                    raw -= foeLine.soak();
                    if (foeLine.clashes()) {
                        clashWeapon(target, cast.grade());   // 막기 — 그 몸의 무기가 내 격을 먹는다
                    }
                }
                raw -= armorSoak(target, cast.grade());
                // 상대의 기 방어·무기가 같은 규칙으로 판정된다 (대칭)
                Defense defense = defend(target, player, cast.grade(), Math.max(0.0, raw));
                if (defense.blocked() || defense.damage() <= 0.0) {
                    continue;   // 기 방어·태세·갑옷이 먹었다 — 검이 닿지 않았으니 격돌도 없다
                }
                if (foeLine == null) {
                    clashWeapon(target, cast.grade());   // 태세 층이 없던 시절의 경로 (원칙 3)
                }
                hits++;
                applying = true;
                try {
                    target.damage(defense.damage(), player);
                } finally {
                    applying = false;
                }
                stagger(target, player, cast);
                // 타격음은 **격**의 것이다 — 초식이 그것을 줄이려면 등록부의 sound_scale 한 칸이 필요하다
                impact(target.getLocation().add(0, 1, 0), cast.grade(), strike, shown,
                        soundScale(stepOf(cast, stepIndex)));
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

    // ══════════════════════════════════════════════════════════════════════════
    //  방어 태세 삼문(三門) — 【맞는 쪽의 선택】
    //
    //  combat.yml 은 방어 셋을 **완전히 정의해 두고** 있었다 (회피·막기·흘리기). 엔진에는 하나도 없었다 —
    //  있는 것은 호신강기(내력으로 막는 것)뿐이었고, 그래서 **수련의 절반이 살 곳이 없었다**:
    //  근력·감각·민첩에 구간을 부어도 **맞을 때 아무 일도 일어나지 않았다.**
    //
    //  ★ 누가 고르는가 — **둘 다.** 마인크래프트는 턴제가 아니라 맞는 순간에 메뉴를 못 띄운다.
    //    ① 몸짓이 먼저다 — 바닐라가 **이미 가진 세 자세**를 읽는다:
    //         손을 세우면(isBlocking) 받아 내는 것 · 몸을 낮추면(isSneaking) 흘리는 것 ·
    //         발이 이미 움직이면(isSprinting) 빼는 것.
    //       새 입력 채널을 열지 않았다. 그래서 **남의 눈에도 보인다** — 상대가 내 자세를 읽고 수를 고른다.
    //    ② 몸에 밴 태세 — `/혼천 태세`. 무인은 제 자세가 있다.
    //    ③ 자동 — 몸이 아는 대로 (Growth.bestStance). **기본값이 이것이다.**
    //       그래서 아무것도 안 배운 삼류도 **제 목숨을 본다** (게이트 없음 — 이 프로젝트가 한 번 데인 죄).
    //
    //  ★ 쿨다운이 없다. 태세를 막는 것은 **내력·자세·상황**이다:
    //    갑옷이 회피를 팔고 · 막기가 무기를 태우고 · 포위가 회피를 지운다.
    // ══════════════════════════════════════════════════════════════════════════

    /** 이 몸이 이 합에 서는 태세 — 이름 · 판정치 · 경감 · 무기가 상하는가 */
    private record Guardline(String stance, int score, int soak, boolean clashes) {
    }

    /** {@code /혼천 태세 <회피|막기|흘리기|자동>} — MvtCommand 가 부른다 */
    public void setStance(Player player, String stance) {
        Growth growth = Growth.get();
        String auto = engine.stanceDefault();
        if (growth == null) {
            SkillHud.actionBar(player, ChatColor.GRAY + "성장 축이 배선되지 않았다");
            return;
        }
        if (auto.equals(stance)) {
            stancePin.remove(player.getUniqueId());
            player.sendMessage(ChatColor.AQUA + "태세 — " + auto
                    + ChatColor.GRAY + " (몸이 아는 대로 선다. 판정치 + 경감이 가장 높은 태세)");
            return;
        }
        if (!growth.stanceNames().contains(stance)) {
            player.sendMessage(ChatColor.GRAY + "그런 태세는 없다 — "
                    + String.join(" · ", growth.stanceNames()) + " · " + auto);
            return;
        }
        stancePin.put(player.getUniqueId(), stance);
        Growth.Stance st = growth.stance(stance);
        player.sendMessage(ChatColor.AQUA + "태세 — " + stance
                + ChatColor.GRAY + " (" + st.attribute() + " + " + st.skill()
                + (st.soak() > 0 ? " · 경감 −" + st.soak() : "")
                + (st.penalty() != 0 ? " · 판정 " + st.penalty() : "")
                + (st.weaponSafe() ? " · 무기 안전" : " · 무기가 격을 먹는다") + ")");
    }

    /** 지금 서 있는 태세 — HUD 가 그린다 (아직 한 대도 안 맞았으면 지금 몸짓으로 계산해 보인다) */
    public String stanceOf(Player player) {
        String now = stanceNow.get(player.getUniqueId());
        return now != null ? now : chooseStance(player, false);
    }

    /**
     * <b>태세를 고른다</b> — 몸짓 → 지정 → 자동 (combat.yml defender_stance_mc.precedence).
     *
     * <p>포위되면 회피가 사라진다 ({@code forced_guard.loses}) — 몸을 뺄 자리가 없으면 못 고른다.
     * 회피를 고른 채 포위당한 자는 <b>화면이 그 사실을 가르친다</b>: "몸을 뺄 자리가 없다".
     */
    private String chooseStance(LivingEntity body, boolean surrounded) {
        Growth growth = Growth.get();
        if (growth == null) {
            return null;
        }
        String picked = null;
        if (body instanceof Player player) {
            // ① 몸짓 — 손과 발이 이미 말하고 있다 (등록부가 술어 이름을 준다. 코드가 몸짓을 짓지 않는다)
            if (player.isBlocking()) {
                picked = engine.stanceOfGesture("isBlocking");
            } else if (player.isSneaking()) {
                picked = engine.stanceOfGesture("isSneaking");
            } else if (player.isSprinting()) {
                picked = engine.stanceOfGesture("isSprinting");
            }
            // ② 몸에 밴 태세
            if (picked == null) {
                picked = stancePin.get(player.getUniqueId());
            }
        }
        if (picked != null && surrounded && growth.lostWhenSurrounded(picked)) {
            picked = null;   // 회피를 골랐으나 뺄 자리가 없다 — 몸이 남은 것 중에서 고른다
        }
        if (picked != null) {
            return picked;
        }
        // ③ 자동 — 몸이 아는 대로. 삼류도 여기서 제 목숨을 본다 (게이트 없음)
        if (body instanceof Player player) {
            PlayerLedger led = plugin.ledger(player.getUniqueId());
            return growth.bestStance(led, weaponSkill(player), gyeonggongSkill(player),
                    armorDodge(player), surrounded);
        }
        return npcBestStance(body, surrounded);
    }

    /** 병기 기술 — 막기·흘리기의 '기술' 항 (지금 손에 든 병기로 나가는 주무공의 숙련) */
    private int weaponSkill(Player player) {
        String skillId = skillInHand(player);
        return skillId == null ? 0
                : plugin.ledger(player.getUniqueId())
                        .levelOf(engine.skillName(skillId), plugin.progression());
    }

    /**
     * 경공 — <b>회피의 '기술' 항</b> ({@code combat.yml defender_choice.회피.check = "민첩 + 경공"}).
     * 경공 담당이 깔아 둔 이음매({@link Gyeonggong#gyeonggongMastery})가 여기서 처음으로 쓰인다.
     *
     * <p><b>철갑은 경공을 못 쓴다</b> ({@code gyeonggong.yml armor_gate} — 경공_불가).
     * 그러면 이 항은 <b>0</b> 이다. 갑옷이 회피를 파는 두 번째 방식이다 (판정 −2 위에 기술 항 상실).
     */
    private int gyeonggongSkill(Player player) {
        Gyeonggong gg = Gyeonggong.get();
        if (gg == null) {
            return 0;
        }
        if (gg.blocksGyeonggong(armorOf(player))) {
            return 0;   // 철갑이 몸을 땅에 붙든다 — 발의 기술이 통째로 사라진다
        }
        return gg.gyeonggongMastery(plugin.ledger(player.getUniqueId()), plugin.progression());
    }

    /** 이 몸이 입은 갑옷 계열 (equipment.yml armor) — 바닐라 흉갑을 등록부로 읽는다 */
    private String armorOf(LivingEntity body) {
        Gyeonggong gg = Gyeonggong.get();
        EntityEquipment gear = body.getEquipment();
        ItemStack chest = gear == null ? null : gear.getChestplate();
        String material = chest == null || chest.getType().isAir() ? null : chest.getType().name();
        return gg == null ? "무복" : gg.armorOf(material);
    }

    /** 갑옷이 파는 것 — 회피 판정 (무복 0 · 피갑 −1 · 철갑 −2). 막기·흘리기는 갑옷을 신경 쓰지 않는다 */
    private int armorDodge(LivingEntity body) {
        return engine.armorDodgePenalty(armorOf(body));
    }

    /**
     * 갑옷이 사는 것 — <b>경감</b>. 이것이 없어서 지금까지 <b>갑옷은 손해만 봤다</b>
     * (회피를 팔고 아무것도 못 받았다).
     *
     * <p><b>단, 상위 격 앞에서는 0 이다</b> ({@code equipment.yml mitigation_pierced_from: 강기} —
     * 등록부의 프로즈가 이미 그어 둔 선: <i>"격 상성은 못 이긴다 — 검강 앞 피갑은 종이"</i>).
     * 그래서 갑옷은 <b>졸개에게 강하고 고수에게 무력하다</b> — 그것이 갑옷의 지배 전략을 스스로 막는다.
     */
    private int armorSoak(LivingEntity body, String grade) {
        return engine.armorPierced(grade) ? 0 : engine.armorMitigation(armorOf(body));
    }

    /** 이 몸을 지금 붙잡고 있는 손의 수 — 포위 판정 (둘 이상이면 회피가 사라진다) */
    private int attackersOn(LivingEntity defender) {
        int n = 0;
        for (org.bukkit.entity.Entity e : defender.getNearbyEntities(NPC_REACH + 1.0, 2.5, NPC_REACH + 1.0)) {
            if (e instanceof Mob mob && mob.isValid() && defender.equals(mob.getTarget())) {
                n++;
            }
        }
        return Math.max(1, n);
    }

    /**
     * 이 합의 태세 한 줄 — 판정치 · 경감 · 무기가 상하는가.
     *
     * <p>판정치 = 능력치 + 기술 + 결 − 판정 비용 + 갑옷 ({@link Growth#defenseScore}).
     * NPC 는 등록부의 능력치 시트를, 없으면 <b>그 경지의 표준 무인</b>을 쓴다
     * ({@code combat_audit realm_axis} 와 같은 셈 — 도구와 엔진이 같은 사람을 세워야 도구가 안 거짓말한다).
     */
    private Guardline guardline(LivingEntity body, String stance, boolean surrounded) {
        Growth growth = Growth.get();
        Growth.Stance st = growth == null ? null : growth.stance(stance);
        if (st == null) {
            return new Guardline(stance, 0, 0, false);
        }
        int score;
        if (body instanceof Player player) {
            score = growth.defenseScore(plugin.ledger(player.getUniqueId()), stance,
                    weaponSkill(player), gyeonggongSkill(player), armorDodge(player), surrounded);
        } else {
            score = npcDefenseScore(body, st, surrounded);
        }
        // 맨손은 태울 것이 없다 — 경감은 그대로 들지만 격돌 판정이 없다 (부러질 물건이 없으니까)
        EntityEquipment gear = body.getEquipment();
        ItemStack held = gear == null ? null : gear.getItemInMainHand();
        boolean armed = held != null && !held.getType().isAir();
        return new Guardline(stance, score, st.soak(), !st.weaponSafe() && armed);
    }

    /** NPC 의 방어 판정치 — 등록된 능력치, 없으면 경지의 표준 무인 (코드가 수치를 짓지 않는다) */
    private int npcDefenseScore(LivingEntity body, Growth.Stance st, boolean surrounded) {
        SkillEngine.Npc npc = npcOf(body);
        String realm = foeRealm(body, true);
        int attr = npc == null ? engine.realmAttr(realm)
                : npc.attr(st.attribute(), engine.realmAttr(npc.realm()));
        int skill = engine.realmSkill(realm);
        Growth growth = Growth.get();
        int pen = (surrounded && growth != null && growth.forcedFloor().equals(st.name()))
                ? 0 : st.penalty();
        return attr + skill + pen + (st.usesGyeonggong() ? armorDodge(body) : 0);
    }

    /** NPC 가 고르는 태세 — 기대 피해가 가장 작은 것 (플레이어와 같은 규칙. 대칭 원칙) */
    private String npcBestStance(LivingEntity body, boolean surrounded) {
        Growth growth = Growth.get();
        String best = growth.forcedFloor();
        int bestScore = Integer.MIN_VALUE;
        for (String name : growth.stanceNames()) {
            if (surrounded && growth.lostWhenSurrounded(name)) {
                continue;
            }
            Growth.Stance st = growth.stance(name);
            int score = npcDefenseScore(body, st, surrounded) + st.soak();
            if (score > bestScore) {
                bestScore = score;
                best = name;
            }
        }
        return best;
    }

    /**
     * <b>공격자의 판정 총합</b> — {@code combat.yml attack.attacker}:
     * 능력치 + 무공 숙련 + 보정(경지 격차·협공) + <b>7</b> (NPC 는 굴리지 않는다).
     *
     * <p>능력치가 <b>어느 능력치인가</b>는 <b>병기가 정한다</b> ({@code attacker_attribute} —
     * 도=근력 · 검=민첩 · 활/암기=감각). 등록되지 않은 몸(야생 좀비·짐승)은 그 경지의 표준 무인이다.
     */
    private int foeAttackScore(LivingEntity attacker, LivingEntity defender, int attackers) {
        SkillEngine.Npc npc = npcOf(attacker);
        Growth growth = Growth.get();
        String realm = foeRealm(attacker, true);
        String weaponClass = npc == null ? "맨손" : npc.weaponClass();
        String attr = growth == null ? "근력" : growth.attackAttribute(weaponClass);
        int base = npc == null ? engine.realmAttr(realm) : npc.attr(attr, engine.realmAttr(npc.realm()));
        // 협공 − 피포위 방어 = 0 (같은 눈금이다 — combat.yml 이 그렇게 못 박아 뒀다).
        // 머릿수는 '더 잘 맞히는 것'이 아니라 '더 많이 치는 것'이다 (engage_slots).
        return base + engine.realmSkill(realm)
                + engine.realmGapBonus(realm, defenderRealm(defender))
                + engine.gangNetModifier(attackers)
                + NPC_JUDGMENT;
    }

    /** 방어자의 경지 — 플레이어면 제 경지, NPC면 등록부의 경지 */
    private String defenderRealm(LivingEntity defender) {
        if (defender instanceof Player player) {
            return state(player).realm;
        }
        return foeRealm(defender, true);
    }

    /**
     * <b>공격자의 무공 위력</b> — {@code damage.formula} 의 둘째 항. 짐승·야생 몸은 0 이다
     * (이빨에는 초식이 없다). 등록부의 사람만 제 경지의 무공 위력을 싣는다.
     */
    private int foeTechniquePower(LivingEntity attacker) {
        SkillEngine.Npc npc = npcOf(attacker);
        if (npc == null || npc.isBeast()) {
            return 0;
        }
        return engine.techniquePower(npc.realm());
    }

    /**
     * <b>태세 연출</b> — 화면이 판정에 대해 거짓말하면 안 된다.
     * 막았으면 막았다고, 흘렸으면 흘렸다고, 피했으면 피했다고. <b>팩이 없어도 보인다</b>
     * (파티클·소리·글자 셋 다 바닐라 — 등록부는 이름만 준다: combat.yml defender_stance_mc.vfx).
     */
    private void stanceFx(LivingEntity body, String key) {
        SkillEngine.StanceFx fx = engine.stanceFx(key);
        if (fx == null) {
            return;   // 등록되지 않은 연출 — 조용히 지나간다 (연출이 없다고 판정이 멈추지 않는다)
        }
        Location at = body.getLocation().add(0, 1.2, 0);
        if (fx.hasParticle()) {
            hud.emit(at, fx.particle(), fx.count(), fx.spread(), 0.0);
        }
        if (fx.sound() != null && at.getWorld() != null) {
            at.getWorld().playSound(at, fx.sound(), 1.0f, fx.pitch());
        }
    }

    /** 태세의 글자 — 액션바에 잠깐 뜬다 (statusBar 가 다시 덮기 전까지 read_ticks 동안) */
    private String stanceLabel(String key) {
        SkillEngine.StanceFx fx = engine.stanceFx(key);
        return fx == null ? key : fx.label();
    }

    // ══════════ 방어 — 태세 → 기 방어(호신강기) → 갑옷 ══════════

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
        return defendInner(target, attacker, grade, incoming, state);
    }

    /**
     * <b>기(氣)의 층</b> — 반격 오의 · 호신강기. 태세(몸)가 못 막은 것을 <b>기</b>가 받는다.
     * 태세는 이 앞에 선다 — 회피가 성공하면 여기까지 오지 않고, 호신강기의 내력도 <b>깎이지 않는다</b>
     * (몸을 뺀 자는 기를 아낀다 — 회피의 숨은 값).
     */
    private Defense defendInner(LivingEntity target, LivingEntity attacker, String grade,
                                double incoming, SkillEngine.State state) {
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
        sfx(at, sfx, 1.0f);
    }

    /**
     * 소리 한 줄 — <b>초식의 배율</b>({@code steps[i].sound_scale})이 걸린다.
     *
     * <p>타격음은 <b>격</b>의 것이고(grades[].sounds.impact) 스윙음은 <b>초식·계열</b>의 것이다.
     * 초식이 제 소리만 줄이면 <b>격의 타격음이 그대로 울려</b> "조용한 검"이 성립하지 않았다 —
     * 무성무색(곤륜)은 <b>전 무공 유일의 무음 초식</b>인데, 그 정체성이 등록부만으로 완결되지 않았다.
     * 이 배율이 그 자리다: <b>초식이 제가 내는 모든 소리를 함께 낮춘다</b>.
     *
     * <p><b>0 이면 아예 발행하지 않는다</b> — 다만 <b>타격 파티클은 깎지 않는다</b>: 조용한 것은 정보지만,
     * <i>맞았다는 사실</i>까지 지우면 그것은 화면이 판정에 대해 거짓말하는 것이다.
     */
    private void sfx(Location at, SkillEngine.Sfx sfx, float scale) {
        if (sfx == null || at.getWorld() == null || scale <= 0.0f) {
            return;
        }
        float volume = sfx.volume() * scale;
        if (volume <= 0.0f) {
            return;   // 무음 — 소리를 0 으로 트는 것과 아예 안 트는 것은 다르다 (후자가 정직하다)
        }
        at.getWorld().playSound(at, sfx.key(), volume, sfx.pitch());
    }

    private void sfx(Location at, List<SkillEngine.Sfx> sounds) {
        sfx(at, sounds, 1.0f);
    }

    private void sfx(Location at, List<SkillEngine.Sfx> sounds, float scale) {
        for (SkillEngine.Sfx s : sounds) {
            sfx(at, s, scale);
        }
    }

    /** 이번 초식이 소리를 얼마나 내는가 — 등록되지 않았으면 1.0 (그대로 운다) */
    private static float soundScale(SkillEngine.Step step) {
        return step == null ? 1.0f : step.soundScale();
    }

    /** 이번 수의 초식 한 칸 — 등록부 {@code skills[].steps[i]} (없으면 null: 오의·발출·기본 초식) */
    private SkillEngine.Step stepOf(SkillEngine.Cast cast, int stepIndex) {
        SkillEngine.SkillMotion motion = engine.motionSkill(cast.skillId());
        return motion == null ? null : motion.step(stepIndex);
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

    /**
     * 두름의 잔광 — 켜져 있다는 사실 자체가 정보다 (상대가 보고 판단한다).
     *
     * <p>두 층으로 알린다: <b>손끝 잔광 파티클</b>(언제나) 위에 <b>날의 기</b>(3D — 팩이 있으면).
     * 후자가 사용자가 요구한 것이다: "검기 강기 등 <b>무기에</b> 효과가 발현되어야지, 눈 앞에 발현된다고
     * 다가 아님." 격은 <b>날에 서리는 것</b>이고, 그래야 태세만 잡아도 남이 안다 (전의 규칙의 전제).
     * 호신강기만은 몸에 두른 것이므로 고리다 (실루엣이 달라야 형태가 갈린다).
     */
    private void aura(Location hand, LivingEntity body, String stance) {
        if (SkillEngine.GUARD.equals(stance)) {
            guardRing(body);
            return;
        }
        hud.emit(hand, engine.motionGrade(stance).aura(), false);
        display.sheath(body, stance);   // 날에 기가 흐른다 (지속 — 심장박동으로 산다)
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
                                   int boost, float soundScale) {
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
        sfx(at.get(), m.chargeSounds(), soundScale);              // 귀로도 예고한다 (감각이 낮아도 읽힌다)
    }

    /**
     * 타격 순간 — 격을 눈에 보이게.
     *
     * <p><b>타격 풀</b>(budget.impact_pool)을 대상 수로 나눈다: 광역 8인을 쳐도 예산이 터지지 않는다.
     * 아무리 나눠도 대상당 최소치(min_impact_per_target)는 남긴다 —
     * <i>맞았다는 사실</i>은 언제나 보여야 한다 (performance.yml over_budget: "핵심 타격 표시만 유지").
     */
    private void impact(Location at, String grade, SkillEngine.Strike strike, int targets,
                        float soundScale) {
        SkillEngine.GradeMotion m = engine.motionGrade(grade);
        SkillEngine.Budget b = engine.motionBudget();
        HuntingGrounds.witnessQi(at, grade);   // 격을 본 자는 전의가 꺾인다 (npc_combat morale 상대_위세)

        int share = Math.max(b.minImpactPerTarget(), b.impactPool() / Math.max(1, targets));
        // 【불가침】 파티클은 배율을 타지 않는다 — 초식이 조용할 수는 있어도, 맞았다는 사실을 지울 수는 없다
        hud.emit(at, m.impact(), Math.min(m.impact().count(), share), false);
        hud.emit(at, m.accent(), false);
        sfx(at, m.impactSounds(), soundScale);   // 타격음만 초식의 배율을 탄다 (무성무색이 조용한 이유)
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
        //   참격선 — 지나간 자리가 남는다 (검을 복제하지 않는다) · 던진 암기는 정말로 날아간다 ·
        //   발출은 기가 날아간다. 전부 실패해도(예산·팩·미등록) 아래 파티클 층이 그대로 돈다
        boolean solid;
        if (shot != null) {
            solid = display.bolt(player, shot.name(), cast.range());
        } else {
            int swingTicks = (int) Math.max(cast.frames().total(), swingInterval(player));
            solid = strike(player, cast.hitType(), cast.grade(), weaponClass,
                    cast.range(), cast.angle(), swingTicks);
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
            return;   // 발출은 초식이 아니다 (형태의 것) — 초식의 배율이 걸릴 자리가 없다
        }
        // 스윙음 — 초식이 제 소리를 가졌으면 그것, 아니면 계열의 소리. 둘 다 초식의 배율을 탄다
        sfx(player.getLocation(), step != null && step.sound() != null ? step.sound() : style.swing(),
                soundScale(step));
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

    /**
     * <b>첫 접속의 목소리.</b> 여기 아무것도 없었다 — {@code PlayerJoinEvent} 처리기는 팩과 형체 둘뿐이었고,
     * 아무것도 모르는 사람이 들어와 <b>아무 말도 듣지 못하고</b> 빈 세계에 섰다.
     *
     * <p>세 가지를 한다:
     * <ol>
     *   <li><b>시트를 싣는다</b> — 봇의 장부가 이 몸의 경지·능력치·심법·내공이 된다.</li>
     *   <li><b>접합 안 됐으면 말한다</b> — "너는 아직 강호에 없다". 조용히 반쪽 세계에서 놀게 두지 않는다.</li>
     *   <li><b>첫 배분을 깔고 목표를 준다</b> — 빈 {@code curriculum} 은 {@code Growth.train} 이
     *       즉시 0 을 돌려준다. 그 침묵이 첫 함정이었다 ({@code player_creation.yml mvt_onboarding}).</li>
     * </ol>
     */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerLedger ledger = plugin.ledger(player.getUniqueId());
        boolean carried = syncSheet(player);   // 스냅숏에 이 몸이 있으면 시트가 실린다
        state(player);                         // 상태를 세운다 (경지는 원장에서 — 하드코딩 없음)
        Onboarding on = Onboarding.get();
        if (on == null) {
            return;
        }
        // 첫 배분 — 아직 한 번도 수련을 고르지 않은 몸에만 (플레이어의 선택을 덮지 않는다)
        boolean empty = ledger.curriculum().isEmpty();
        if (empty && !on.defaultCurriculum().isEmpty()) {
            Growth growth = Growth.get();
            on.defaultCurriculum().forEach((subject, segments) -> {
                if (growth != null && growth.subjects().containsKey(subject)) {
                    ledger.setSegments(subject, segments);
                }
            });
        }
        final boolean seeded = empty && !ledger.curriculum().isEmpty();
        final boolean linkedNow = carried || ledger.linked();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (!linkedNow) {
                nag(player, on);
                return;
            }
            String goal = on.goalFor(player.getUniqueId());
            if (goal != null) {
                player.sendMessage(on.goalPrefix() + ChatColor.WHITE + " — " + goal);
            }
            on.linkedLines().forEach(player::sendMessage);
            if (!ledger.curriculum().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                ledger.curriculum().forEach((subject, segments) ->
                        sb.append(sb.isEmpty() ? "" : " · ").append(subject).append(' ')
                                .append(segments).append("구간"));
                player.sendMessage((seeded ? ChatColor.DARK_GRAY + "  기본 배분 — " : ChatColor.DARK_GRAY
                        + "  오늘의 배분 — ") + ChatColor.GRAY + sb);
            }
        }, 40L);   // 접속 직후의 폭포(팩·타이틀) 뒤에 말한다 — 안 그러면 아무도 못 읽는다
    }

    /**
     * <b>"너는 아직 강호에 없다."</b> — 접합되지 않은 몸에게.
     *
     * <p>지금까지 미접합자는 <b>조용히 반쪽 세계에서 놀았다.</b> 그것이 가장 나쁘다: 아무리 베고 익혀도
     * 어디에도 적히지 않는데 본인은 그것을 모른다. 이제는 말한다 — 그리고 화후도 쌓이지 않는다
     * (쌓을 장부가 없다. {@link #settleTraining} 이 접합을 요구한다).
     */
    private void nag(Player player, Onboarding on) {
        if (!on.shouldNag(player.getUniqueId(), System.currentTimeMillis())) {
            return;
        }
        player.sendTitle(ChatColor.RED + on.unlinkedTitle(),
                ChatColor.YELLOW + on.unlinkedSubtitle(), 10, 70, 20);
        on.unlinkedLines().forEach(player::sendMessage);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        // ★ 떠나기 전에 원장을 굽는다 — 재기동이 아니라 로그아웃 하나로도 소멸했다
        plugin.saveLedger(id);
        states.remove(id);
        clashCounts.remove(id);
        stanceNow.remove(id);   // 지정 태세(stancePin)는 남긴다 — 몸에 밴 것은 로그아웃으로 안 풀린다
        hud.forget(id);
        display.clear(id);      // 떠난 몸의 형체는 남지 않는다
        if (Onboarding.get() != null) {
            Onboarding.get().forget(id);
        }
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

    /**
     * 이 손에 실리는 무공 — <b>원장이 고른다</b> (하드코딩된 무공표를 지운 자리).
     *
     * <p>손에 든 병기의 계열로 나갈 수 있는 무공들({@code skills.yml weapon_class}) 중,
     * <b>이 사람이 실제로 익힌</b>(원장에 일수가 쌓인) 것 하나를 고른다 — 가장 오래 판 것이 주무공이다.
     * 아무것도 안 익혔으면 {@code null} → 그 손은 <b>기본 초식</b>(basic_strike)으로 친다.
     * 예전엔 검을 들면 누구나 육합검이 나갔고(배우지 않아도), 매화검법·나한권은 <b>시전 경로가 아예 없었다</b>.
     */
    private String skillInHand(Player player) {
        String weaponClass = engine.weaponClassOf(
                player.getInventory().getItemInMainHand(), materialName(player));
        PlayerLedger ledger = plugin.ledger(player.getUniqueId());
        String best = null;
        double bestDays = 0.0;
        for (String id : engine.artsFor(weaponClass)) {
            double days = ledger.daysOf(engine.skillName(id));
            if (days > bestDays) {
                bestDays = days;
                best = id;
            }
        }
        return best;
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

    /**
     * <b>날이 바뀌면 수련이 갈린다.</b>
     *
     * <p>여기 있는 이유: 원래 {@code rollDay} 는 <b>몹을 죽일 때만</b> 불렸다 ({@code HuntListener}).
     * 수련이 사냥에 묶여 있으면 <b>"앉아서 쌓는 몸"이 성립하지 않는다</b> — 외공·내공·심안은
     * 베어서 느는 것이 아니라 <b>앉아서</b> 느는 것이고, 실전 화후만이 손(초식)에 쌓인다.
     * 그래서 정산은 모두에게 매일 온다. 사냥은 그 위에 얹힌다.
     *
     * <p>넘치는 몫은 버린다 — <b>천장이 몰빵을 꺾는 자리</b>다. 신법을 끝까지 민 자는 어느 날부터
     * 하루치의 일부가 허공에 흩어지는 것을 본다. 배분을 바꾸라는 세계의 말이다.
     */
    private void settleTraining(Player player, SkillEngine.State state) {
        Growth growth = Growth.get();
        if (growth == null) {
            return;
        }
        PlayerLedger ledger = plugin.ledger(player.getUniqueId());

        // ★ 시계는 하나다 — 봇의 세계일. 예전에는 getFullTime()/24000 (마크의 20분 하루)이었고,
        //   그래서 `Growth.attrCostDays`(능력치 +1 = 360일)가 봇의 360일과 **72배 다른 시계** 위에서 돌았다.
        //   마크에 닷새 앉아 있으면 봇에서 1년 걸릴 능력치가 올랐다. 이제 달력은 봇이 굴리고 마크가 읽는다.
        long day = WorldBridge.worldDay();
        if (day <= 0) {
            return;   // 스냅숏이 없다 = 봇이 꺼져 있다. 장부는 봇의 것이므로 움직이지 않는다
        }
        if (!ledger.linked()) {
            return;   // 강호에 없는 몸에는 쌓을 장부가 없다 (onJoin 의 nag 가 그 이유를 말했다)
        }
        if (!ledger.isNewDay(day)) {
            return;
        }
        double wasted = growth.train(ledger, state.realm, 1.0);
        ledger.addWasted(wasted);
        // 몸에 실제로 들어간 일치 — 봇의 `화후_원장` 으로 간다 (범인 → 삼류의 관문: 기초 단련 3개월)
        ledger.pendTrain(Math.max(0.0, 1.0 - wasted));
        ledger.rollDay(day);
        if (wasted > 0.0) {
            player.sendMessage(org.bukkit.ChatColor.DARK_GRAY + "수련 "
                    + String.format("%.1f", wasted) + "일치가 흩어졌다 — 천장에 닿았다 (/혼천 수련)");
        }
        pushLedger(player, ledger, state);
    }

    /**
     * <b>몸에서 쌓인 것을 장부로 올린다.</b> 시트를 적는 손은 봇 하나뿐이다 — 마크는 규칙의 계산기다.
     *
     * <p>마크는 낙관적으로 제 거울에 먼저 더해 두었다(그래야 같은 틱에 몸이 반응한다). 그 값은 곧
     * 스냅숏이 <b>절대값으로 덮어쓴다</b> — 더하는 것이 아니라 덮는 것이므로 <b>이중 계상이 원리적으로 불가능</b>하다.
     */
    void pushLedger(Player player, PlayerLedger ledger, SkillEngine.State state) {
        if (!ledger.hasPending() || !ledger.linked()) {
            return;
        }
        WorldBridge.cultivationLogged(player.getUniqueId(), player.getName(),
                state == null ? ledger.realm(engine.baseRealm()) : state.realm, ledger.takePending());
    }
}
