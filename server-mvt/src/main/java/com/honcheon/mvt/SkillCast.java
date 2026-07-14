package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 절기(絶技) 시전 체계 — <b>삼문(三門)</b>.
 *
 * <p><b>절기는 버튼이 아니다.</b> MMO 의 문법("쿨다운이 돌면 누른다")을 쓰지 않는다.
 * 절기는 무공의 결에서 <b>피어나는 한 수</b>이고, 세 문이 <b>동시에</b> 열려야 나간다
 * (등록부 정본: skill_mechanics.yml {@code cast_gate}):
 *
 * <ul>
 *   <li><b>承(승)</b> — 어미 무공의 지정 타 직후 창(窓) 안. 독립 버튼이 아니라 <b>파생</b>이다.</li>
 *   <li><b>間(간)</b> — 간격. 너무 붙어도, 너무 멀어도 안 나간다 (창궁일섬은 3.0 안쪽에서 죽는다).</li>
 *   <li><b>虛(허)</b> — 상대의 허. 경직·배후·반격 — 살초는 허가 없으면 손이 나가지 않는다.</li>
 * </ul>
 *
 * <p>쿨다운과 내력은 <b>과금</b>이지 발동의 주역이 아니다. 그래서 이 체계의 HUD 는
 * 쿨다운 바가 아니라 <b>"지금"</b>을 보여준다 — 세 문이 열리는 순간 초식의 이름이 뜨고 소리가 난다.
 * 플레이어가 보는 것은 남은 초가 아니라 <b>기회</b>다.
 *
 * <p><b>입력</b> (mc_action_mapping.md 1장 · skill_lifecycle.yml loadout.mc.slots 정본):
 * <ul>
 *   <li><b>핫바 6~9</b> — 겨눔(구결) 4칸. 누르면 그 절기가 겨눠지고 <b>손은 병장기로 되돌아온다</b>
 *       (검법 절기는 검을 든 손에서 나간다 — 손에서 검이 떠나면 그것은 이미 검법이 아니다).</li>
 *   <li><b>같은 칸 두 번</b> (10틱 내, 비전투·정지) — 경락도(GUI) 개방. 편성은 전투 중에 못 바꾼다
 *       (skill_lifecycle.yml swap_rule).</li>
 *   <li><b>우클릭</b> (Shift 없이) — 겨눈 절기 시전. 겨눈 것이 없으면 SkillListener 의
 *       <b>방어 선언</b>(B-015 active_guard)이 그 손을 받는다 — 겨눔이 선언보다 앞선다 (이 리스너가
 *       NORMAL 로 먼저 굴러 취소하면 선언 층은 물러선다).
 *       (좌클릭=콤보 / Shift+좌클릭=발출 / Shift+우클릭=격 태세 / F=오의 와 충돌하지 않는다).</li>
 * </ul>
 *
 * <p><b>기존 체계와의 맞물림</b> — 새 규칙을 만들지 않았다:
 * <ul>
 *   <li>내력·격: {@link SkillEngine#planCombo} 이 그대로 판정한다 (격 라이더·다운캐스트·두름 승격).</li>
 *   <li>조식: 절기가 내력을 태우면 그 합의 단전이 돌지 않는다 — {@code state.energy} 를 공유하므로
 *       SkillListener 의 조식 정산이 <b>저절로</b> 맞는다 (internal_energy.yml only_if_unspent).</li>
 *   <li>연출: 파티클·소리를 하드코딩하지 않는다. {@link SkillEngine#motionSkill} 등록부만 읽는다.</li>
 * </ul>
 *
 * <p><b>배선 주의</b> — 타격 착지는 지금 이 파일이 근사한다. SkillListener 의 정본 파이프라인
 * (commit→resolve: 기 방어·무기 격돌·사냥 적립)을 쓰려면 {@code SkillListener.castArt(...)} 가 필요하다
 * (보고서의 API 요청 참조). 그 메서드가 오면 {@link #land} 는 세 줄로 대체된다.
 */
public final class SkillCast implements Listener {

    /** 겨눔 슬롯 — 핫바 6~9 (index 5~8). skill_lifecycle.yml loadout.mc.slots.스킬 count 4 */
    private static final int SLOT_FIRST = 5;
    private static final int SLOTS = 4;
    /** 병장기 칸 — 겨눔에서 손이 돌아갈 자리 (핫바 1~2 = 무기, mc_action_mapping) */
    private static final int WEAPON_SLOT = 0;
    private static final int DOUBLE_TAP_TICKS = 10;
    // COUNTER_WINDOW(피격 직후 창)는 폐지됐다 (B-015) — 虛(패링·회피·반격)는 이제 SkillListener 의
    // 방어 성공 기록(opening)을 읽는다. 창의 값은 combat.yml active_guard.opening_window_ticks 가 정본.
    /** SkillListener.stagger 가 심는 경직의 지문 (SLOWNESS amplifier 4) — 이것으로 '허'를 읽는다 */
    private static final int STAGGER_AMPLIFIER = 4;
    private static final String GUI_TITLE = "경락도 — 절기 편성";

    private final HoncheonMvt plugin;
    private final SkillEngine engine;
    private final SkillListener skills;

    /** 절기 등록부 — skill_mechanics.yml 의 art:true (config 가 정본. 코드가 앞서지 않는다) */
    private final Map<String, Art> arts;

    private final Map<UUID, Loadout> loadouts = new HashMap<>();
    private final Map<UUID, Long> lastHurt = new HashMap<>();
    private final Map<UUID, Long> lastCombo = new HashMap<>();
    private final Map<UUID, Long> busyUntil = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    /** 액션바 고지의 가장자리 검출 — 매 틱 떠들지 않는다. 문이 열리는 '순간'만 말한다 */
    private final Map<UUID, String> announced = new HashMap<>();

    private long tick;

    // ══════════ 등록부 ══════════

    /** 삼문 — skill_mechanics.yml cast_gate */
    private record Gate(String after, String alsoAfter, int step, int window,
                        double gapMin, double gapMax, boolean airborne,
                        List<String> heo, String heoFallback) {
    }

    /** 절기 한 수 — 엔진이 안 읽는 칸만 여기서 든다 (프레임·격·위력은 planCombo 가 정본) */
    private record Art(String id, String name, List<String> weaponClass, Gate gate,
                       int cooldown, int hitCount, String property, boolean superArmor,
                       double distance, String type, String parent, int parentMastery) {
    }

    /** 겨눔 4칸 + 지금 겨눈 칸 */
    private static final class Loadout {
        final String[] slot = new String[SLOTS];
        int aimed = -1;
        long lastTap = -1;
        int lastTapSlot = -1;
    }

    @SuppressWarnings("unchecked")
    public SkillCast(HoncheonMvt plugin, SkillEngine engine, SkillListener skills, Path cfg) {
        this.plugin = plugin;
        this.engine = engine;
        this.skills = skills;

        Map<String, Object> mech = RulesConfig.section(
                RulesConfig.load(cfg.resolve("skill_mechanics.yml")), "skills");
        Map<String, Object> catalog = RulesConfig.section(
                RulesConfig.load(cfg.resolve("skills.yml")), "martial_arts");

        Map<String, Art> found = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : mech.entrySet()) {
            Map<String, Object> m = asMap(e.getValue());
            if (!Boolean.TRUE.equals(m.get("art"))) {
                continue;   // 절기가 아니다 — 무공의 콤보는 SkillListener 의 몫
            }
            Map<String, Object> cg = asMap(m.get("cast_gate"));
            Map<String, Object> seung = asMap(cg.get("승"));
            Map<String, Object> gan = asMap(cg.get("간"));

            List<String> heo = new ArrayList<>();
            Object rawHeo = cg.get("허");
            if (rawHeo instanceof List<?> l) {
                l.forEach(v -> heo.add(String.valueOf(v)));
            } else if (rawHeo != null) {
                heo.add(String.valueOf(rawHeo));
            }

            Gate gate = new Gate(
                    str(seung.get("after")), str(seung.get("also_after")),
                    intOr(seung.get("step"), 0), intOr(seung.get("window_ticks"), 10),
                    dbl(gan.get("min"), 0.0), dbl(gan.get("max"), 4.0),
                    Boolean.TRUE.equals(cg.get("airborne")),
                    heo, str(cg.get("허_fallback")));

            List<Object> combo = (List<Object>) m.get("combo");
            Map<String, Object> hit = combo == null || combo.isEmpty()
                    ? Map.of() : asMap(combo.get(0));

            Map<String, Object> art = asMap(catalog.get(e.getKey()));
            Map<String, Object> req = asMap(art.get("requires_skill"));

            found.put(e.getKey(), new Art(
                    e.getKey(), engine.skillName(e.getKey()),
                    weaponClasses(m.get("weapon_class")), gate,
                    intOr(m.get("cooldown_ticks"), 100),
                    Math.max(1, intOr(m.get("hit_count"), 1)),
                    str(m.get("property")),
                    m.get("super_armor") != null,
                    dbl(hit.get("distance"), 0.0),
                    String.valueOf(hit.getOrDefault("type", "호")),
                    str(req.get("id")), intOr(req.get("mastery"), 0)));
        }
        this.arts = Map.copyOf(found);
    }

    /** 중앙 티커 — 효과별 개별 태스크 생성 금지 (performance.yml F-P2) */
    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, Metrics.wrap("skill_cast", this::tick), 1L, 1L);
        plugin.getLogger().info("절기 시전 체계 — " + arts.size() + "수 등록 (삼문: 承·間·虛)");
    }

    // ══════════ 티커 — 문이 열리는 '순간'을 고지한다 ══════════

    private void tick() {
        tick++;
        if (tick % 2 != 0) {
            return;   // 2틱마다 — 액션바는 그보다 빨리 읽히지 않는다
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Loadout lo = loadouts.get(player.getUniqueId());
            if (lo == null || lo.aimed < 0) {
                continue;
            }
            Art art = art(lo.slot[lo.aimed]);
            if (art == null) {
                continue;
            }
            // 【MMO 문법을 쓰지 않는 자리】 쿨다운 바를 그리지 않는다. '지금'을 그린다.
            String why = gate(player, art);
            String key = art.id() + "/" + (why == null ? "열림" : why);
            if (key.equals(announced.get(player.getUniqueId()))) {
                continue;   // 가장자리 검출 — 상태가 바뀔 때만 말한다
            }
            announced.put(player.getUniqueId(), key);
            if (why == null) {
                skills.flash(player, ChatColor.GOLD + "❖ " + art.name()
                        + ChatColor.WHITE + " — 지금");
                sound(player, "block.note_block.pling", 0.6f, 1.9f);
            }
        }
    }

    // ══════════ 입력 ① 겨눔 — 핫바 6~9 (손은 병장기를 놓지 않는다) ══════════

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onAim(PlayerItemHeldEvent event) {
        int slot = event.getNewSlot();
        if (slot < SLOT_FIRST || slot >= SLOT_FIRST + SLOTS) {
            return;   // 병장기·소모품 칸 — 세계의 몫이다
        }
        Player player = event.getPlayer();
        Loadout lo = loadout(player);
        int idx = slot - SLOT_FIRST;

        // 손에서 검이 떠나면 그것은 이미 검법이 아니다 — 겨눔만 바꾸고 손은 되돌린다
        event.setCancelled(true);
        int back = event.getPreviousSlot();
        player.getInventory().setHeldItemSlot(
                back >= SLOT_FIRST && back < SLOT_FIRST + SLOTS ? WEAPON_SLOT : back);

        // 같은 칸 두 번 = 경락도 (비전투·정지 — skill_lifecycle swap_rule)
        if (lo.lastTapSlot == idx && tick - lo.lastTap <= DOUBLE_TAP_TICKS) {
            lo.lastTap = -1;
            lo.lastTapSlot = -1;
            openMeridian(player);
            return;
        }
        lo.lastTap = tick;
        lo.lastTapSlot = idx;

        Art art = art(lo.slot[idx]);
        if (art == null) {
            lo.aimed = -1;
            skills.flash(player, ChatColor.DARK_GRAY + String.valueOf(SLOT_FIRST + idx + 1)
                    + "번 자리 — 비어 있다 " + ChatColor.GRAY + "(한 번 더 눌러 경락도)");
            return;
        }
        lo.aimed = idx;
        announced.remove(player.getUniqueId());
        skills.flash(player, ChatColor.YELLOW + "겨눔 ─ " + art.name()
                + ChatColor.DARK_GRAY + " │ " + gateHint(art));
        sound(player, "item.flintandsteel.use", 0.4f, 1.6f);
    }

    // ══════════ 입력 ② 시전 — 우클릭 (Shift 없이) ══════════

    @EventHandler(priority = EventPriority.NORMAL)
    public void onCast(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;   // RIGHT_CLICK_BLOCK 은 건드리지 않는다 — 상호작용을 무공이 잡아먹으면 안 된다
        }
        Player player = event.getPlayer();
        if (player.isSneaking()) {
            return;   // Shift+우클릭 = 격 태세 (SkillListener 의 것)
        }
        Loadout lo = loadouts.get(player.getUniqueId());
        if (lo == null || lo.aimed < 0) {
            return;   // 겨눈 것이 없다 — 평범한 우클릭은 세계의 몫
        }
        Art art = art(lo.slot[lo.aimed]);
        if (art == null) {
            return;
        }
        event.setCancelled(true);
        cast(player, art);
    }

    // ══════════ 입력 ③ 관찰 — 承(콤보의 결)과 虛(피격)의 출처 ══════════

    /** 콤보가 한 타 나갔다 — 承의 시계는 여기서 돈다 (MONITOR: SkillListener 가 먼저 굴린 뒤) */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwingAir(PlayerInteractEvent event) {
        if (event.getAction() == Action.LEFT_CLICK_AIR && !event.getPlayer().isSneaking()) {
            lastCombo.put(event.getPlayer().getUniqueId(), tick);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwingHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p && !p.isSneaking()) {
            lastCombo.put(p.getUniqueId(), tick);
        }
        if (event.getEntity() instanceof Player victim && !event.isCancelled()) {
            // '전투 중' 판정(openMeridian)의 시계 — 虛 의 출처는 더 이상 아니다 (B-015: opening 이 정본)
            lastHurt.put(victim.getUniqueId(), tick);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        loadouts.remove(id);
        lastHurt.remove(id);
        lastCombo.remove(id);
        busyUntil.remove(id);
        cooldowns.remove(id);
        announced.remove(id);
    }

    // ══════════ 삼문(三門) — 왜 지금은 안 나가는가 ══════════

    /** 열려 있으면 null. 닫혀 있으면 <b>어느 문이 닫혔는지</b>를 돌려준다 (닫힌 이유가 곧 배울 것이다) */
    private String gate(Player player, Art art) {
        SkillEngine.State state = skills.state(player);
        UUID id = player.getUniqueId();

        if (tick < busyUntil.getOrDefault(id, -1L)) {
            return "자세";
        }
        long cd = cooldowns.getOrDefault(id, Map.of()).getOrDefault(art.id(), -1L);
        if (tick < cd) {
            return "숨";   // 쿨다운 — 주역이 아니라 과금이다
        }
        if (!art.weaponClass().contains(engine.weaponClassOf(
                player.getInventory().getItemInMainHand(), materialName(player)))) {
            return "병장기";
        }
        if (engine.realmIndex(state.realm) < engine.realmIndex(engine.requiredRealm(art.id()))) {
            return "경지";
        }

        // ── 門1 承 — 어미 무공의 결에서만 핀다 ──
        long since = tick - lastCombo.getOrDefault(id, -999L);
        if (art.gate().step() > 0) {
            if (since > art.gate().window()) {
                return "승";   // 콤보의 창이 닫혔다
            }
            // state.comboIndex 는 '다음' 칸이다 — 방금 나간 타(1-based)를 되짚는다
            int size = Math.max(1, engine.comboSize(parentOrSelf(art)));
            int justCast = state.comboIndex == 0 ? size : state.comboIndex;
            if (justCast != art.gate().step()) {
                return "승";
            }
        }

        // ── 門2 間 — 간격 ──
        if (art.gate().airborne() && player.isOnGround()) {
            return "공중";   // 매화낙수 — 발이 땅에 붙어 있으면 떨어지는 물이 아니다
        }
        LivingEntity foe = nearest(player, art.gate().gapMax());
        if (foe == null) {
            return "간격";
        }
        double gap = player.getLocation().distance(foe.getLocation());
        if (gap < art.gate().gapMin() || gap > art.gate().gapMax()) {
            return "간격";
        }

        // ── 門3 虛 — 상대의 허 ──
        if (!art.gate().heo().isEmpty() && !art.gate().heo().contains("없음")) {
            if (!openingOf(player, foe, art)) {
                return "허";
            }
        }
        return null;
    }

    /** 虛 — 상대의 빈틈이 실제로 열려 있는가 */
    private boolean openingOf(Player player, LivingEntity foe, Art art) {
        for (String heo : art.gate().heo()) {
            switch (heo) {
                case "경직" -> {
                    PotionEffect slow = foe.getPotionEffect(PotionEffectType.SLOWNESS);
                    if (slow != null && slow.getAmplifier() >= STAGGER_AMPLIFIER) {
                        return true;   // SkillListener.stagger 의 지문 — 굳어 있다
                    }
                }
                case "배후" -> {
                    Vector facing = foe.getLocation().getDirection().setY(0);
                    Vector toMe = player.getLocation().toVector()
                            .subtract(foe.getLocation().toVector()).setY(0);
                    if (facing.lengthSquared() > 1e-6 && toMe.lengthSquared() > 1e-6
                            && facing.normalize().dot(toMe.normalize()) < -0.3) {
                        return true;   // 등 뒤에 섰다
                    }
                }
                // 【B-015 · 배선 완료】 근사를 그만뒀다 — 옛 코드는 피격 직후 창(lastHurt)으로 읽었다:
                //   막는 행위가 아니라 **맞은 뒤의 보상**이었다. 이제 SkillListener 의 방어 성공 기록을
                //   읽는다: 패링(우클릭 선언의 앞머리에서 받아 냄) · 회피(몸을 뺌) · 반격(어느 태세든 이긴 직후).
                case "패링", "회피", "반격" -> {
                    if (skills.opening(player, heo)) {
                        return true;
                    }
                }
                default -> {
                    return true;
                }
            }
        }
        return false;
    }

    // ══════════ 시전 ══════════

    private void cast(Player player, Art art) {
        String shut = gate(player, art);
        if (shut != null) {
            skills.flash(player, ChatColor.DARK_GRAY + art.name() + " — " + reason(shut));
            sound(player, "block.note_block.bass", 0.3f, 0.6f);
            return;
        }
        skills.breakGuard(player);   // 절기도 공격이다 — 방어 전념(+2)과 살초를 동시에 가질 수 없다 (B-015)
        SkillEngine.State state = skills.state(player);
        String weaponClass = engine.weaponClassOf(
                player.getInventory().getItemInMainHand(), materialName(player));

        // 격·내력·다운캐스트는 엔진이 정본이다 — 여기서 다시 계산하지 않는다
        SkillEngine.Cast plan = engine.planCombo(
                art.id(), 0, state.realm, state.energy, state.armed, weaponClass);

        state.energy -= plan.paid();
        SkillEngine.Frames f = plan.frames();
        busyUntil.put(player.getUniqueId(), tick + f.total());
        cooldowns.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .put(art.id(), tick + art.cooldown());
        announced.remove(player.getUniqueId());

        // 쿨다운은 바닐라 아이템 쿨다운으로 보인다 (mc_action_mapping: 아이템 쿨다운 = 스킬 쿨다운)
        Material held = player.getInventory().getItemInMainHand().getType();
        if (held != Material.AIR) {
            player.setCooldown(held, art.cooldown());
        }
        // 무거운 수일수록 발이 묶인다 (SkillListener.commit 과 같은 규약)
        if (f.total() >= 8) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                    Math.max(1, f.total()), f.total() >= 16 ? 2 : 1, false, false, false));
        }
        if (art.superArmor()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,
                    f.total(), 1, false, false, false));   // 근사: 맞으면서 들어간다
        }
        if (plan.downcast()) {
            skills.flash(player, ChatColor.DARK_GRAY + "기가 실리지 않는다 — 맨 기술");
        }

        player.swingMainHand();
        // 돌진 — 몸이 먼저 간다 (나한복호)
        if ("돌".equals(art.type()) && art.distance() > 0) {
            player.setVelocity(player.getLocation().getDirection().setY(0.08)
                    .normalize().multiply(art.distance() / 8.0));
        }
        trail(player, art, plan);

        // 선딜 뒤에 판정한다 — 보이는 것이 곧 맞는 것이다
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> resolve(player, art, plan), Math.max(1, f.startup()));
    }

    private void resolve(Player player, Art art, SkillEngine.Cast plan) {
        if (!player.isOnline()) {
            return;
        }
        SkillEngine.State state = skills.state(player);
        // ★ 절기도 **같은 손으로 가려낸다** — 아군을 안 베고, 벽 너머를 안 벤다, 가까운 것부터.
        //   그전엔 이 경로만 규칙 밖에 있었다 (히트박스에 든 모든 몸을 그대로 벴다).
        //   가려내는 규칙이 두 벌이면 그중 하나는 반드시 낡는다 — 그래서 SkillListener 의 것을 부른다.
        List<String[]> vetoes = new ArrayList<>();
        List<LivingEntity> targets = skills.admit(player, plan, null,
                targets(player, art, plan), vetoes);
        int hits = 0;

        for (LivingEntity foe : targets) {
            for (int n = 0; n < art.hitCount(); n++) {
                if (!foe.isValid()) {
                    break;
                }
                if (land(player, art, plan, foe, state)) {
                    hits++;
                }
            }
        }

        // 격은 목격될 때 비로소 강호의 일이 된다 (SkillListener.resolve 와 같은 규약)
        if (hits > 0 && plan.manifested() && !Dojang.suppressWorldEvents(player.getWorld())) {
            Location me = player.getLocation();
            int seen = (int) me.getWorld().getNearbyEntities(me, 24, 12, 24).stream()
                    .filter(e -> (e instanceof Player p && !p.equals(player))
                            || e instanceof org.bukkit.entity.Villager).count();
            WorldBridge.qiManifested(plan.grade(), "산길_어귀", seen,
                    player.getUniqueId(), player.getName());
        }

        skills.flash(player, ChatColor.GOLD + "❖ " + art.name()
                + ChatColor.DARK_GRAY + " │ " + SkillHud.gradeLabel(plan.grade())
                + (hits > 0 ? ChatColor.WHITE + " · " + hits + "타"
                            : ChatColor.GRAY + " · 헛손질"));
    }

    /**
     * 한 대를 넣는다.
     *
     * <p><b>【배선 요청 지점】</b> 지금은 {@code damage(double)} 로 넣는다 — 출처(Player)를 붙이면
     * SkillListener.onMelee 가 그 사건을 <b>다시 잡아 콤보로 되돌린다</b>(event.setCancelled + swing).
     * 그 대가로 이 경로는 <b>기 방어(호신강기)·무기 격돌·사냥 적립</b>을 타지 못한다.
     * {@code SkillListener.castArt(player, skillId, step, primary)} 가 오면 이 메서드 전체가 그 한 줄로 대체된다.
     */
    private boolean land(Player player, Art art, SkillEngine.Cast plan,
                         LivingEntity foe, SkillEngine.State state) {
        boolean hostile = foe instanceof Monster;
        int mastery = plugin.ledger(player.getUniqueId())
                .levelOf(art.name(), plugin.progression());
        int execBase = mastery
                + engine.weaponJudgmentBonus(engine.weaponGradeOf(
                        player.getInventory().getItemInMainHand(), materialName(player)))
                + engine.realmGapBonus(state.realm, foeRealm(foe))
                + (engine.isDepleted(state.energy) ? -2 : 0);
        int roll = ThreadLocalRandom.current().nextInt(6) + 1
                + ThreadLocalRandom.current().nextInt(6) + 1;

        SkillEngine.Strike strike = engine.strike(
                plan, execBase, roll, engine.difficulty(hostile ? "보통" : "쉬움"));
        if (!strike.hit()) {
            return false;
        }
        foe.damage(strike.damage());   // 출처 없이 — 위 주석 참조 (재진입 방지)
        if (foe instanceof Mob mob && mob.getTarget() == null) {
            mob.setTarget(player);     // 출처가 없으니 적의(敵意)는 손으로 심는다
        }
        int stun = plan.staggerTicks();
        if (stun > 0) {
            foe.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                    stun, STAGGER_AMPLIFIER, false, false, true));
            if (stun >= 10) {
                Vector push = foe.getLocation().toVector()
                        .subtract(player.getLocation().toVector()).setY(0);
                if (push.lengthSquared() > 1e-6) {
                    foe.setVelocity(push.normalize()
                            .multiply(stun >= 20 ? 0.8 : 0.45).setY(0.25));
                }
            }
        }
        return true;
    }

    // ══════════ 히트박스 ══════════

    private List<LivingEntity> targets(Player player, Art art, SkillEngine.Cast plan) {
        double range = plan.range();
        List<LivingEntity> out = new ArrayList<>();
        Location eye = player.getEyeLocation();
        Vector dir = player.getLocation().getDirection().normalize();
        Vector flat = dir.clone().setY(0);
        if (flat.lengthSquared() < 1e-6) {
            flat = new Vector(0, 0, 1);
        }
        flat.normalize();

        // 【상한은 여기서 물리지 않는다】 admit() 이 **가까운 것부터** 골라 max_targets 만큼 담는다
        //   (여기서 자르면 청크 순회 순서대로 "아무 8" 이 된다 — 그것이 바로 그 버그였다)
        for (org.bukkit.entity.Entity e : player.getNearbyEntities(range, range, range)) {
            if (!(e instanceof LivingEntity foe) || foe.equals(player) || !foe.isValid()) {
                continue;
            }
            // ★ 히트박스의 기하는 **한 벌뿐이다** (SkillListener.inArc·inLine·inCone·inCircle).
            //   이 파일과 SkillListener 가 각자 제 원뿔을 갖고 있었다 — 두 개의 진실은 곧 하나의 거짓말이다.
            //   판정도 이 함수를 부르고, **판정의 눈도 이 함수를 부른다** (그래서 눈이 판정을 못 속인다).
            Location where = foe.getLocation();
            boolean inside = switch (plan.hitType()) {
                case "원" -> SkillListener.inCircle(eye, range, where);
                case "시" -> SkillListener.inCone(eye, dir, range, plan.angle(), where);
                case "선" -> SkillListener.inLine(eye, dir, range,
                        Math.max(0.5, plan.angle() / 2.0), where);   // 선: angle 칸이 폭(width)이다
                default -> SkillListener.inArc(eye, flat, range, plan.angle(), where);   // 호 · 돌
            };
            if (inside) {
                out.add(foe);
            }
        }
        return out;
    }

    /** 가장 가까운 앞의 몸 — 間(간격)을 재는 자 */
    private LivingEntity nearest(Player player, double max) {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        Vector facing = player.getLocation().getDirection().setY(0);
        if (facing.lengthSquared() < 1e-6) {
            return null;
        }
        facing.normalize();
        for (org.bukkit.entity.Entity e : player.getNearbyEntities(max + 2, max + 2, max + 2)) {
            if (!(e instanceof LivingEntity foe) || foe.equals(player) || !foe.isValid()) {
                continue;
            }
            Vector to = foe.getLocation().toVector()
                    .subtract(player.getLocation().toVector()).setY(0);
            if (to.lengthSquared() < 1e-6 || facing.dot(to.clone().normalize()) < 0.35) {
                continue;   // 등 뒤의 것은 간격이 아니다
            }
            double d = player.getLocation().distance(foe.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = foe;
            }
        }
        return best;
    }

    // ══════════ 연출 — 등록부만 읽는다 (파티클·소리 하드코딩 금지) ══════════

    private void trail(Player player, Art art, SkillEngine.Cast plan) {
        SkillEngine.SkillMotion motion = engine.motionSkill(art.id());
        if (motion == null) {
            return;   // 모션 미등록 — 등록되면 저절로 보인다 (skill_motion.yml 은 다른 작업자의 것)
        }
        SkillEngine.Step step = motion.step(0);
        if (step == null) {
            return;
        }
        Particle particle = particle(step.particle());
        if (particle != null && step.count() > 0) {
            Location at = player.getEyeLocation()
                    .add(player.getLocation().getDirection().multiply(1.2));
            at.getWorld().spawnParticle(particle, at, step.count(), 0.3, 0.3, 0.3, 0.0);
        }
        SkillEngine.Sfx sfx = step.sound();
        if (sfx != null && sfx.key() != null) {
            sound(player, sfx.key(), sfx.volume(), sfx.pitch());
        }
    }

    private static Particle particle(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;   // 등록부가 앞서고 코드가 따른다 — 모르는 이름은 조용히 넘긴다
        }
    }

    private void sound(Player player, String key, float volume, float pitch) {
        player.playSound(player.getLocation(), key, volume, pitch);
    }

    // ══════════ 경락도 — 편성 (비전투·정지) ══════════

    private void openMeridian(Player player) {
        if (tick - lastCombo.getOrDefault(player.getUniqueId(), -999L) < 200
                || tick - lastHurt.getOrDefault(player.getUniqueId(), -999L) < 200) {
            skills.flash(player, ChatColor.RED
                    + "전투 중에는 경락을 바꾸지 않는다");   // skill_lifecycle swap_rule
            return;
        }
        Inventory gui = plugin.getServer().createInventory(null, 27, GUI_TITLE);
        SkillEngine.State state = skills.state(player);

        int i = 0;
        for (Art art : arts.values()) {
            if (i >= 18) {
                break;
            }
            boolean known = known(player, state, art);
            ItemStack icon = new ItemStack(known ? Material.PAPER : Material.GRAY_DYE);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.setDisplayName((known ? ChatColor.GOLD : ChatColor.DARK_GRAY) + art.name());
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "요구 경지 " + engine.requiredRealm(art.id()));
                if (art.parent() != null) {
                    lore.add(ChatColor.GRAY + "어미 무공 " + engine.skillName(art.parent())
                            + " 숙련 " + art.parentMastery());
                }
                lore.add(ChatColor.DARK_GRAY + gateHint(art));
                if (!known) {
                    lore.add(ChatColor.RED + "아직 전수받지 못했다");
                }
                meta.setLore(lore);
                icon.setItemMeta(meta);
            }
            gui.setItem(i++, icon);
        }
        // 아래 줄 = 겨눔 4칸
        Loadout lo = loadout(player);
        for (int s = 0; s < SLOTS; s++) {
            String id = lo.slot[s];
            ItemStack slot = new ItemStack(id == null ? Material.LIGHT_GRAY_STAINED_GLASS_PANE
                    : Material.FILLED_MAP);
            ItemMeta meta = slot.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + "겨눔 " + (SLOT_FIRST + s + 1) + "번 — "
                        + (id == null ? ChatColor.DARK_GRAY + "비었다"
                                      : ChatColor.WHITE + engine.skillName(id)));
                meta.setLore(List.of(ChatColor.DARK_GRAY + "클릭 — 비운다"));
                slot.setItemMeta(meta);
            }
            gui.setItem(18 + s, slot);
        }
        player.openInventory(gui);
    }

    @EventHandler
    public void onMeridianClick(InventoryClickEvent event) {
        if (!GUI_TITLE.equals(event.getView().getTitle())
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        int raw = event.getRawSlot();
        Loadout lo = loadout(player);

        if (raw >= 18 && raw < 18 + SLOTS) {
            lo.slot[raw - 18] = null;   // 비운다
            openMeridian(player);
            return;
        }
        if (raw < 0 || raw >= 18) {
            return;
        }
        List<Art> list = new ArrayList<>(arts.values());
        if (raw >= list.size()) {
            return;
        }
        Art art = list.get(raw);
        if (!known(player, skills.state(player), art)) {
            skills.flash(player, ChatColor.RED + art.name() + " — 아직 전수받지 못했다");
            return;
        }
        for (int s = 0; s < SLOTS; s++) {
            if (lo.slot[s] == null) {
                lo.slot[s] = art.id();
                skills.flash(player, ChatColor.GOLD + art.name()
                        + ChatColor.WHITE + " — " + (SLOT_FIRST + s + 1) + "번 자리에 걸었다");
                openMeridian(player);
                return;
            }
        }
        skills.flash(player, ChatColor.RED + "겨눔 네 자리가 모두 찼다");
    }

    /** 전수 조건 — 경지 + 어미 무공의 숙련 (skills.yml requires_skill 이 정본) */
    private boolean known(Player player, SkillEngine.State state, Art art) {
        if (engine.realmIndex(state.realm) < engine.realmIndex(engine.requiredRealm(art.id()))) {
            return false;
        }
        if (art.parent() == null) {
            return true;
        }
        return plugin.ledger(player.getUniqueId())
                .levelOf(engine.skillName(art.parent()), plugin.progression()) >= art.parentMastery();
    }

    // ══════════ 잡동사니 ══════════

    private String gateHint(Art art) {
        StringBuilder sb = new StringBuilder();
        if (art.gate().step() > 0 && art.gate().after() != null) {
            sb.append(engine.skillName(art.gate().after()))
              .append(' ').append(art.gate().step()).append("타 뒤");
        }
        if (art.gate().airborne()) {
            sb.append(sb.isEmpty() ? "" : " · ").append("공중");
        }
        String heo = String.join("·", art.gate().heo());
        if (!heo.isEmpty() && !"없음".equals(heo)) {
            sb.append(sb.isEmpty() ? "" : " · ").append(heo);
        }
        sb.append(sb.isEmpty() ? "" : " · ")
          .append(String.format("간격 %.1f~%.1f", art.gate().gapMin(), art.gate().gapMax()));
        return sb.toString();
    }

    private static String reason(String shut) {
        return switch (shut) {
            case "자세" -> "아직 자세가 돌아오지 않았다";
            case "숨" -> "아직 숨이 고르지 않다";
            case "병장기" -> "이 병장기로는 펼 수 없다";
            case "경지" -> "이 몸의 것이 아니다";
            case "승" -> "결이 이어지지 않는다";
            case "간격" -> "간격이 아니다";
            case "공중" -> "발이 땅에 붙어 있다";
            case "허" -> "허가 보이지 않는다";
            default -> "펼 수 없다";
        };
    }

    /** 承의 콤보 칸 수 — 어미 무공의 것으로 잰다 (없으면 자기 것) */
    private String parentOrSelf(Art art) {
        return art.parent() != null && engine.hasActionData(art.parent()) ? art.parent() : art.id();
    }

    private String foeRealm(LivingEntity foe) {
        return foe instanceof Player other ? skills.state(other).realm : "삼류";
    }

    private String materialName(Player player) {
        return player.getInventory().getItemInMainHand().getType().name();
    }

    private Art art(String id) {
        return id == null ? null : arts.get(id);
    }

    private Loadout loadout(Player player) {
        return loadouts.computeIfAbsent(player.getUniqueId(), id -> new Loadout());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<String> weaponClasses(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> l) {
            l.forEach(v -> out.add(String.valueOf(v)));
        } else if (raw != null) {
            out.add(String.valueOf(raw));
        }
        return List.copyOf(out);
    }

    private static String str(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static int intOr(Object raw, int fallback) {
        return raw instanceof Number n ? n.intValue() : fallback;
    }

    private static double dbl(Object raw, double fallback) {
        return raw instanceof Number n ? n.doubleValue() : fallback;
    }
}
