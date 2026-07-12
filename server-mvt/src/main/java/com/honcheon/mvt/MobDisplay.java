package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 몹의 3D 형체 — 바닐라 몸을 감추고 커스텀 모델을 태우는 층.
 *
 * <p><b>왜 이 파일이 생겼나.</b> 우리 호랑이는 {@link EntityType#RAVAGER} 다. 몸집·돌진 AI 때문에
 * 그 몸을 징발했지만, <b>눈에 보이는 것은 약탈수</b>다. 리소스팩은 텍스처를 바꿀 뿐
 * <b>지오메트리를 못 바꾼다</b> — 바닐라 클라이언트에서 몹 모델은 팩으로 교체되지 않는다. 그래서
 * 호피 무늬를 입힌 뿔 달린 회색 소가 북산의 주인 행세를 했다.
 *
 * <p>바닐라 클라이언트에서 되는 유일한 길: <b>본체를 감추고 그 위에 {@link ItemDisplay} 를 태운다.</b>
 * 본체는 그대로 산다 — <b>AI·판정·물리·경로탐색·히트박스가 전부 거기서 돈다</b>. 형체를 바꾼다고
 * 그 몸을 고른 이유가 사라지지 않는다.
 *
 * <p><b>불변식 ㄱ — 형체는 덧칠이다.</b> 이 클래스의 모든 실패(팩 없음 · 예산 초과 · 미등록 ·
 * 볼 눈이 없음)는 <b>본체가 그대로 보이는 것</b>으로 끝난다. 라바저로 보일지언정 사냥은 된다.
 * {@code require-resource-pack=false} 는 이 층에도 불가침이다 — 3D 는 특권이 아니라 사치다
 * ({@link SkillDisplay} 불변식 ㅁ 과 같은 문법).
 *
 * <p><b>불변식 ㄴ — 판정은 본체다.</b> Display 는 히트박스가 없다(때릴 수 없는 몸이다). 때리는 것도
 * 맞는 것도 언제나 본체다. 형체가 한 틱 어긋나도 판정은 불변이다.
 *
 * <p><b>탑승이 아니라 추종이다</b> ({@link #follow}). 근거 넷:
 * <ol>
 *   <li><b>탑승 오프셋은 질의할 수 없다.</b> 승객이 앉는 높이는 탈것의 종류마다 다르고(RAVAGER 등 위
 *       ~2.1m · WOLF ~0.9m) Bukkit API 로 물어볼 방법이 없다. 등록부의 {@code offset} 이 진실이려면
 *       그 매직 넘버를 코드에 박아야 한다 — 등록제 규약 위반이다.</li>
 *   <li><b>회전은 어차피 매 틱 갱신해야 한다.</b> 승객 Display 는 탈것의 yaw 를 물려받지 않는다
 *       (자기 yaw 로 그려진다). "탑승은 공짜"의 절반은 거짓이다.</li>
 *   <li><b>죽을 때 쓰러져야 한다.</b> 탈것이 죽으면 승객은 즉시 하차·낙하한다. 형체는 몸이 사라진
 *       뒤에도 남아 <b>옆으로 넘어져야</b> 한다({@link Corpse}).</li>
 *   <li><b>값이 싸다.</b> 형체 64개 상한 × (거리 비교 + 벡터 3 + teleport 1) ≈ 0.1ms 미만.
 *       {@code performance.yml npc_logic} 6ms 중 이 층의 몫은 1ms 다.</li>
 * </ol>
 * 부드러움은 {@code setTeleportDuration}(이동 보간)과 {@code setInterpolationDuration}(형체 보간)이
 * 낸다. 이 둘이 0이면 형체가 <b>튄다</b>.
 *
 * <p><b>움직임의 표정</b> — 조각은 걷지 않는다. 그래서 흉내 낸다:
 * <ul>
 *   <li><b>걷기</b> — 상하 흔들림({@code walk_bob}) · 좌우 기울기({@code walk_roll}) · 꼬리 흔들림.
 *       위상은 <b>시간이 아니라 이동거리</b>로 돈다 — 멈추면 흔들림도 멈춘다. 그것이 걷기다.</li>
 *   <li><b>공격</b> — 앞으로 기운다({@code attack_lean}). 달릴수록 더 기운다({@code charge_lean}).</li>
 *   <li><b>사망</b> — 옆으로 넘어지며 가라앉는다({@code death_topple} · {@code death_sink}).</li>
 *   <li><b>머리</b> — 몸이 안 돌아도 <b>고개가 먼저 표적을 본다</b>({@code head_yaw_max}).</li>
 * </ul>
 *
 * <p><b>팩 게이트</b> ({@link #gate}) — 팩 있는 눈과 없는 눈이 섞이면 <b>한쪽은 진다</b>. 본체의
 * 투명은 엔티티 단위 플래그라 플레이어별로 갈 수 없고({@code hideEntity} 로 숨긴 몸은 <b>그
 * 플레이어가 때릴 수 없다</b> — 판정이 무너진다), 그러므로 피할 수 없는 선택이다. <b>지는 쪽은
 * 3D 다</b>: 3D 를 잃은 자는 여전히 잘 논다(라바저를 본다). 본체를 잃은 자는 못 논다.
 */
final class MobDisplay implements Listener {

    /** 유령 표식 — 플러그인이 죽어도 이 표를 단 형체는 다음 기동에 청소된다 (SkillDisplay.KEY_VFX 와 같은 문법) */
    static final NamespacedKey KEY_RIG = new NamespacedKey("honcheon", "mob_rig");

    // ══════════════ 등록부 (config/mob_models.yml 판독 — 코드가 config 보다 앞서지 않는다) ══════════════

    /** 한 조각 — 몸통·머리·꼬리. 파츠를 나누면 살아나지만 엔티티 수가 곱절이 된다 */
    record Part(String id, String role, String key, Material base,
                Vector3f size, Vector3f offset,
                float headYawMax, float headPitchMax, float swayDeg) {
    }

    /** 움직임의 표정 — 조각이 살아 있는 척하는 법 */
    record Motion(float walkBob, float walkBobRate, float walkRoll,
                  float attackLean, int attackLeanTicks,
                  float chargeLean, double chargeSpeedFull,
                  float deathTopple, float deathSink, int deathTicks) {
    }

    /** 등록부의 한 줄 — 적 하나의 형체 */
    record Model(String id, String name, EntityType body, boolean replace, String reason,
                 boolean hideEquipment, boolean glowToDisplay,
                 List<Part> parts, Motion motion, float viewRange) {
    }

    /** 예산 (performance.yml npc_logic 6ms 중 1ms · vfx_entities 와는 다른 풀) */
    record Budget(int globalCap, int degradeAt, int perMobMax, float viewRange,
                  int attachScanTicks, int attachScanRange,
                  int followInterval, int teleportDuration, int interpolation,
                  double moveEpsilon, double yawEpsilon) {
    }

    /** 팩 게이트 — adaptive | forced | off */
    record Gate(String mode, int reevaluateTicks, int graceTicks) {
    }

    private static final Map<String, Model> MODELS = new LinkedHashMap<>();
    private static Budget budget = new Budget(64, 48, 4, 0.75f, 20, 48, 1, 2, 3, 0.02, 2.0);
    private static Gate gate = new Gate("adaptive", 20, 200);

    /** 형체 게이트의 세 답 — 짓는다 · 거둔다 · 그대로 둔다 */
    private enum Verdict { BUILD, TEAR, HOLD }

    // ══════════════ 판독 ══════════════

    /** config/mob_models.yml — 없어도 돈다 (등록부가 없으면 형체 층 전체가 조용히 잠든다) */
    @SuppressWarnings("unchecked")
    static void init(Path cfg) {
        MODELS.clear();
        Map<String, Object> root;
        try {
            root = RulesConfig.load(cfg.resolve("mob_models.yml"));
        } catch (RuntimeException missing) {
            return;   // 등록부 없음 — 본체가 그대로 보인다 (불변식 ㄱ)
        }
        if (root == null) {
            return;
        }
        Map<String, Object> b = sub(root, "budget");
        Map<String, Object> f = sub(b, "follow");
        budget = new Budget(
                (int) num(b.get("global_cap"), 64),
                (int) num(b.get("degrade_at"), 48),
                (int) num(b.get("per_mob_max"), 4),
                (float) num(b.get("view_range"), 0.75),
                (int) num(b.get("attach_scan_ticks"), 20),
                (int) num(b.get("attach_scan_range"), 48),
                Math.max(1, (int) num(f.get("interval_ticks"), 1)),
                (int) num(f.get("teleport_duration"), 2),
                (int) num(f.get("interpolation"), 3),
                num(f.get("move_epsilon"), 0.02),
                num(f.get("yaw_epsilon"), 2.0));

        Map<String, Object> g = sub(root, "pack_gate");
        gate = new Gate(String.valueOf(g.getOrDefault("mode", "adaptive")),
                (int) num(g.get("reevaluate_ticks"), 20),
                (int) num(g.get("grace_ticks"), 200));

        sub(root, "foes").forEach((id, raw) -> {
            if (raw instanceof Map<?, ?> entry) {
                Model model = parse(id, (Map<String, Object>) entry);
                if (model != null) {
                    MODELS.put(id, model);
                }
            }
        });
    }

    /** 없는 절은 빈 절이다 — {@link RulesConfig#section} 은 던진다. 등록부의 일부가 비어도 이 층은 돈다 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> root, String key) {
        return root != null && root.get(key) instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Model parse(String id, Map<String, Object> e) {
        EntityType body = null;
        try {
            body = EntityType.valueOf(String.valueOf(e.get("body")).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            // 본체를 못 읽어도 등록부의 한 줄로는 남는다 (audit 가 잡는다). 형체는 붙지 않는다
        }
        boolean replace = "replace".equals(String.valueOf(e.getOrDefault("shape", "vanilla")));
        String name = String.valueOf(e.getOrDefault("name", id));
        String reason = e.get("reason") == null ? null : String.valueOf(e.get("reason"));

        List<Part> parts = new ArrayList<>();
        if (e.get("parts") instanceof List<?> list) {
            for (Object row : list) {
                if (!(row instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> p = (Map<String, Object>) m;
                Material base = Material.matchMaterial(String.valueOf(p.getOrDefault("base", "LEATHER")));
                if (base == null || p.get("key") == null) {
                    continue;   // 실을 몸이 없다 — 이 조각은 뜨지 않는다 (audit 가 잡는다)
                }
                parts.add(new Part(
                        String.valueOf(p.getOrDefault("id", "body")),
                        String.valueOf(p.getOrDefault("role", "body")),
                        String.valueOf(p.get("key")),
                        base,
                        vec(p.get("size"), 1, 1, 1),
                        vec(p.get("offset"), 0, 0, 0),
                        (float) num(p.get("head_yaw_max"), 0),
                        (float) num(p.get("head_pitch_max"), 0),
                        (float) num(p.get("sway_deg"), 0)));
            }
        }
        Map<String, Object> mo = sub(e, "motion");
        Motion motion = new Motion(
                (float) num(mo.get("walk_bob"), 0),
                (float) num(mo.get("walk_bob_rate"), 2.5),
                (float) num(mo.get("walk_roll_deg"), 0),
                (float) num(mo.get("attack_lean_deg"), 0),
                (int) num(mo.get("attack_lean_ticks"), 6),
                (float) num(mo.get("charge_lean_deg"), 0),
                num(mo.get("charge_speed_full"), 0.3),
                (float) num(mo.get("death_topple_deg"), 90),
                (float) num(mo.get("death_sink"), 0.3),
                (int) num(mo.get("death_ticks"), 24));

        return new Model(id, name, body, replace && !parts.isEmpty(), reason,
                bool(e.get("hide_equipment")), bool(e.get("body_glow_to_display")),
                List.copyOf(parts), motion,
                (float) num(e.get("view_range"), budget.viewRange()));
    }

    private static double num(Object v, double fallback) {
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private static boolean bool(Object v) {
        return v instanceof Boolean b ? b : "true".equals(String.valueOf(v));
    }

    private static Vector3f vec(Object v, float x, float y, float z) {
        if (v instanceof List<?> l && l.size() == 3) {
            return new Vector3f((float) num(l.get(0), x), (float) num(l.get(1), y), (float) num(l.get(2), z));
        }
        return new Vector3f(x, y, z);
    }

    /** 등록부 판독 — mob_model_audit · MvtCommand 가 물을 수 있다 */
    static Model model(String foeId) {
        return MODELS.get(foeId);
    }

    // ══════════════ 살아 있는 형체 ══════════════

    /** 몸 하나에 붙은 형체 — 파츠가 비어 있으면 <b>강등 상태</b>(본체가 그대로 보인다) */
    private static final class Rig {
        final Model model;
        final LivingEntity body;
        final List<ItemDisplay> parts = new ArrayList<>(3);
        Location last;
        double phase;        // 걷기 위상 — 이동거리로 돈다 (시간이 아니다)
        double speed;        // 직전 틱의 이동거리 (m/틱)
        long leanUntil;      // 공격 기울기가 끝나는 틱
        boolean bodyGlow;    // 본체가 원래 빛나고 있었나 (백영묘의 검기)

        Rig(Model model, LivingEntity body) {
            this.model = model;
            this.body = body;
        }
    }

    /** 주검 — 몸은 사라졌고 형체만 남아 쓰러진다 */
    private record Corpse(List<ItemDisplay> parts, long dieAt) {
    }

    private final HoncheonMvt plugin;
    private final Map<UUID, Rig> rigs = new HashMap<>();
    private final List<Corpse> corpses = new ArrayList<>();

    /** 팩을 받은 눈 (SUCCESSFULLY_LOADED) */
    private final Set<UUID> packed = new HashSet<>();
    /** 팩 상태를 <b>보고한</b> 눈 — 보고 전의 눈은 유예 중이다 (관중에서 뺀다) */
    private final Set<UUID> answered = new HashSet<>();
    private final Map<UUID, Long> joinTick = new HashMap<>();

    private long tick;

    MobDisplay(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    // ══════════════ 수명 ══════════════

    /**
     * 기동 — 지난 생의 유령을 걷는다.
     *
     * <p>둘을 쓸어야 한다: <b>남은 형체</b>(Display 는 {@code setPersistent(false)} 라 저장되지
     * 않지만 그 세션의 청소는 못 한다)와 <b>투명한 채 굳은 본체</b>. 두 번째가 더 무섭다 —
     * 우리 짐승은 {@code setPersistent(true)} 다. 크래시로 형체가 사라지면 <b>보이지 않는 호랑이</b>가
     * 산길에 남는다. 그래서 태그 몸의 투명을 전부 되돌린다 ({@link #reevaluate} 가 다시 입힌다).
     */
    void start() {
        int swept = 0;
        int unveiled = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e instanceof Display && e.getPersistentDataContainer().has(KEY_RIG)) {
                    e.remove();
                    swept++;
                } else if (e instanceof LivingEntity body && HuntingGrounds.tag(e, HuntingGrounds.KEY_ID) != null
                        && body.isInvisible()) {
                    body.setInvisible(false);   // 보이지 않는 호랑이는 버그가 아니라 재앙이다
                    unveiled++;
                }
            }
        }
        if (swept + unveiled > 0) {
            plugin.getLogger().info("[혼천] 지난 생의 몹 형체 " + swept + "개를 걷고, 투명한 몸 "
                    + unveiled + "구를 되돌렸다");
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, Metrics.wrap("mob_display", this::tick), 40L, budget.followInterval());
    }

    /** 플러그인이 내려간다 — <b>형체를 걷고 본체를 되돌린다</b> (투명한 몸을 세계에 남기지 않는다) */
    void clearAll() {
        for (Rig rig : rigs.values()) {
            tear(rig);
        }
        rigs.clear();
        for (Corpse corpse : corpses) {
            despawn(corpse.parts());
        }
        corpses.clear();
    }

    // ══════════════ 부착 — HuntingGrounds.spawn 이 부른다 ══════════════

    /**
     * 등록부의 한 마리가 형체를 입는 자리. 형체를 못 입어도 <b>아무 일도 일어나지 않는다</b> —
     * 그 몸은 바닐라 모습으로 선다 (불변식 ㄱ).
     */
    void attach(LivingEntity body, String foeId) {
        Model model = MODELS.get(foeId);
        if (model == null || !model.replace() || "off".equals(gate.mode())) {
            return;   // 등록되지 않았거나 그대로 둘 몸이거나 층이 꺼져 있다
        }
        Rig rig = new Rig(model, body);
        rigs.put(body.getUniqueId(), rig);
        reevaluate(rig);   // 스폰 즉시 판단 — 1초를 기다리면 그동안 라바저가 서 있다
    }

    /** 형체가 붙어 있는가 — /혼천 검수 · audit 배선 확인용 */
    boolean isRigged(Entity entity) {
        Rig rig = rigs.get(entity.getUniqueId());
        return rig != null && !rig.parts.isEmpty();
    }

    int liveDisplays() {
        return live();
    }

    // ══════════════ 중앙 티커 하나 (performance.yml F-P2 — 개체별 태스크 생성 금지) ══════════════

    private void tick() {
        tick += budget.followInterval();

        for (Iterator<Rig> it = rigs.values().iterator(); it.hasNext(); ) {
            Rig rig = it.next();
            if (!rig.body.isValid() || rig.body.isDead()) {
                // 청크가 내려갔거나 몸이 사라졌다 — 형체를 걷는다 (죽음은 onDeath 가 먼저 가져간다)
                despawn(rig.parts);
                it.remove();
                continue;
            }
            if (!rig.parts.isEmpty()) {
                follow(rig);
            }
        }

        if (tick % gate.reevaluateTicks() == 0) {
            for (Rig rig : rigs.values()) {
                reevaluate(rig);
            }
        }
        if (tick % budget.attachScanTicks() == 0) {
            scan();
        }
        corpses.removeIf(corpse -> {
            if (tick < corpse.dieAt()) {
                return false;
            }
            despawn(corpse.parts());
            return true;
        });
    }

    /**
     * 줍기 — 형체 없는 태그 몸을 찾는다. <b>사람 곁에서만 훑는다</b> (npc_logic 예산).
     *
     * <p>이것이 없으면 청크 재적재·서버 재기동으로 태어난 몸은 영원히 라바저다 (형체는 저장되지
     * 않는다 — 몸만 저장된다). 그리고 <b>투명한 채 굳은 몸</b>도 여기서 되살아난다.
     */
    private void scan() {
        int r = budget.attachScanRange();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            for (Entity e : player.getNearbyEntities(r, r / 2.0, r)) {
                if (!(e instanceof LivingEntity body) || rigs.containsKey(e.getUniqueId())) {
                    continue;
                }
                String id = HuntingGrounds.tag(e, HuntingGrounds.KEY_ID);
                if (id == null) {
                    continue;
                }
                Model model = MODELS.get(id);
                if (model == null || !model.replace() || "off".equals(gate.mode())) {
                    if (body.isInvisible()) {
                        body.setInvisible(false);   // 자가 치유 — 형체 없이 투명한 몸은 없다
                    }
                    continue;
                }
                Rig rig = new Rig(model, body);
                rigs.put(e.getUniqueId(), rig);
                reevaluate(rig);
            }
        }
    }

    // ══════════════ 팩 게이트 — 지는 쪽은 3D 다 ══════════════

    /**
     * 이 몸에 형체를 씌울 것인가.
     *
     * <ul>
     *   <li><b>TEAR</b> — 관중 중 하나라도 팩이 없다. 형체를 거두고 본체를 되돌린다.
     *       그 자리의 모두가 라바저를 본다. <b>덜 아름답지만 모두가 논다.</b></li>
     *   <li><b>BUILD</b> — 관중이 있고 전원이 팩을 받았다. 본체를 감추고 형체를 씌운다.</li>
     *   <li><b>HOLD</b> — 셀 눈이 없다 (아무도 안 보거나 전원이 유예 중). 새로 짓지 않고,
     *       있는 것은 두었다. 접속 직후 팩을 내려받는 사람이 지나갈 때마다 형체가 깜빡이지 않게.</li>
     * </ul>
     */
    private Verdict verdict(Rig rig) {
        if ("off".equals(gate.mode())) {
            return Verdict.TEAR;
        }
        if ("forced".equals(gate.mode())) {
            return Verdict.BUILD;   // 팩 강제 서버 — 켠 자가 책임을 진다 (불가침 규약과 충돌한다)
        }
        LivingEntity body = rig.body;
        World world = body.getWorld();
        double range = 64.0 * rig.model.viewRange();   // setViewRange 는 64m 기준의 배율이다
        double r2 = range * range;
        int counted = 0;
        for (Player viewer : world.getPlayers()) {
            if (viewer.getLocation().distanceSquared(body.getLocation()) > r2) {
                continue;
            }
            UUID id = viewer.getUniqueId();
            if (!answered.contains(id) && tick - joinTick.getOrDefault(id, 0L) < gate.graceTicks()) {
                continue;   // 유예 — 아직 팩을 내려받는 중이다. 이 눈은 세지 않는다
            }
            counted++;
            if (!packed.contains(id)) {
                return Verdict.TEAR;   // 한 사람이라도 팩이 없으면 형체는 진다
            }
        }
        return counted == 0 ? Verdict.HOLD : Verdict.BUILD;
    }

    private void reevaluate(Rig rig) {
        Verdict v = verdict(rig);
        if (v == Verdict.BUILD && rig.parts.isEmpty()) {
            build(rig);
        } else if (v == Verdict.TEAR && !rig.parts.isEmpty()) {
            tear(rig);
        } else if (!rig.parts.isEmpty()) {
            audience(rig);   // 관중이 바뀌었을 수 있다 — 팩 받은 눈에만 보여 준다
        }
    }

    // ══════════════ 짓기 · 걷기 ══════════════

    /** 본체를 감추고 형체를 태운다. 예산이 없으면 <b>아무것도 하지 않는다</b> — 그 몸은 라바저로 보인다 */
    private void build(Rig rig) {
        int need = Math.min(rig.model.parts().size(), budget.perMobMax());
        if (need == 0 || live() + need > budget.degradeAt()) {
            return;   // over_cap → 바닐라 몹으로 강등. 강등의 종착지는 '보이지 않는 몹'이 아니다
        }
        LivingEntity body = rig.body;
        Location at = body.getLocation();
        if (at.getWorld() == null) {
            return;
        }

        body.setInvisible(true);            // 명패는 남는다 — 투명한 몸도 이름표는 뜬다
        if (rig.model.hideEquipment()) {
            EntityEquipment gear = body.getEquipment();
            if (gear != null) {
                gear.clear();               // 형체 위로 삐져나오는 장비는 없다 (짐승은 원래 비어 있다)
            }
        }
        if (rig.model.glowToDisplay() && body.isGlowing()) {
            // 【함정】 투명한 몸에 발광을 걸면 **바닐라 모델의 윤곽선**이 그대로 빛난다 —
            //   고양이 실루엣이 허공에 뜬다. 발광을 형체로 옮긴다 (검기는 그대로 보인다).
            rig.bodyGlow = true;
            body.setGlowing(false);
        }

        for (int i = 0; i < need; i++) {
            Part part = rig.model.parts().get(i);
            ItemDisplay d = spawnPart(at, rig, part);
            if (d != null) {
                rig.parts.add(d);
            }
        }
        if (rig.parts.isEmpty()) {
            tear(rig);   // 한 조각도 못 띄웠다 — 감춘 몸을 되돌린다 (투명한 유령을 만들지 않는다)
            return;
        }
        rig.last = at.clone();
        audience(rig);
        follow(rig);
    }

    private ItemDisplay spawnPart(Location at, Rig rig, Part part) {
        ItemStack stack = new ItemStack(part.base());
        ItemMeta meta = stack.getItemMeta();
        NamespacedKey key = NamespacedKey.fromString(part.key());
        if (meta == null || key == null) {
            return null;
        }
        meta.setItemModel(key);   // 팩과의 유일한 접점 — 팩이 이 키로 3D 를 굽는다
        stack.setItemMeta(meta);

        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(stack);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);   // 손 변형 없이 모델 그대로
            d.setBillboard(Display.Billboard.FIXED);   // 짐승은 카메라를 따라 돌지 않는다 — 세계에 서 있다
            d.setViewRange(rig.model.viewRange());
            d.setTeleportDuration(budget.teleportDuration());     // 이동 보간 — 0이면 형체가 튄다
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(budget.interpolation());   // 표정 보간
            d.setPersistent(false);                    // 저장되지 않는다 (유령의 첫 방벽)
            d.setVisibleByDefault(false);              // 팩 받은 눈에만 보여 준다 (없는 모델 = 보라 큐브)
            d.setGlowing(rig.bodyGlow);                // 본체에서 옮겨 온 발광 (백영묘의 검기)
            d.getPersistentDataContainer().set(KEY_RIG, PersistentDataType.BYTE, (byte) 1);
            d.setTransformation(new Transformation(
                    new Vector3f(part.offset()), new Quaternionf(),
                    new Vector3f(part.size()), new Quaternionf()));
        });
    }

    /** 형체를 걷고 <b>본체를 되돌린다</b>. 이 함수가 실패하면 세계에 보이지 않는 짐승이 남는다 */
    private void tear(Rig rig) {
        despawn(rig.parts);
        rig.parts.clear();
        if (rig.body.isValid()) {
            rig.body.setInvisible(false);
            if (rig.bodyGlow) {
                rig.body.setGlowing(true);
                rig.bodyGlow = false;
            }
        }
    }

    /** 팩 받은 눈에만 보여 준다 — 없는 모델을 얹으면 클라이언트는 보라·검정 큐브를 그린다 */
    private void audience(Rig rig) {
        double range = 64.0 * rig.model.viewRange();
        double r2 = range * range;
        for (Player viewer : rig.body.getWorld().getPlayers()) {
            boolean show = packed.contains(viewer.getUniqueId())
                    && viewer.getLocation().distanceSquared(rig.body.getLocation()) <= r2;
            for (ItemDisplay d : rig.parts) {
                if (show) {
                    viewer.showEntity(plugin, d);
                } else {
                    viewer.hideEntity(plugin, d);   // Display 는 히트박스가 없다 — 숨겨도 판정에 영향이 없다
                }
            }
        }
    }

    // ══════════════ 추종 — 형체가 몸에서 떨어지면 그 즉시 거짓말이 된다 ══════════════

    private void follow(Rig rig) {
        LivingEntity body = rig.body;
        Location at = body.getLocation();
        Motion m = rig.model.motion();

        double moved = 0;
        if (rig.last != null && rig.last.getWorld() == at.getWorld()) {
            moved = rig.last.distance(at);
        }
        rig.speed = moved;
        rig.phase += moved * m.walkBobRate();   // 【위상은 이동거리로 돈다】 멈추면 흔들림도 멈춘다
        double yawDelta = rig.last == null ? 999 : Math.abs(wrap(at.getYaw() - rig.last.getYaw()));
        boolean move = moved > budget.moveEpsilon() || yawDelta > budget.yawEpsilon();

        // ─── 몸통의 자세 — 모든 파츠가 이것을 함께 탄다 (한 조각만 기울면 목이 떨어진다) ───
        float lean = 0;
        if (rig.leanUntil > tick && m.attackLean() != 0) {
            lean += m.attackLean();
        }
        if (m.chargeLean() != 0 && m.chargeSpeedFull() > 0) {
            lean += (float) (m.chargeLean() * Math.min(1.0, rig.speed / m.chargeSpeedFull()));
        }
        float roll = (float) (m.walkRoll() * Math.sin(rig.phase));
        float bob = (float) (m.walkBob() * Math.abs(Math.sin(rig.phase)));

        Quaternionf pose = new Quaternionf()
                .rotateX((float) Math.toRadians(lean))    // 앞으로 기운다 (공격·돌진)
                .rotateZ((float) Math.toRadians(roll));   // 어깨가 번갈아 내려간다 (걷기)

        Location stand = at.clone();
        stand.setPitch(0);   // 형체는 언제나 땅에 선다 — 본체의 pitch(고개)는 머리 파츠가 받는다

        for (int i = 0; i < rig.parts.size(); i++) {
            ItemDisplay d = rig.parts.get(i);
            if (!d.isValid()) {
                continue;
            }
            if (move) {
                d.teleport(stand);   // 위치·yaw 를 몸에서 그대로 가져온다 (보간이 끊김을 메운다)
            }
            apply(d, rig, rig.model.parts().get(i), pose, bob, budget.interpolation());
        }
        rig.last = at.clone();
    }

    /**
     * 한 조각의 변환 — {@code translation → leftRotation → scale} 순으로 먹는다.
     *
     * <p>몸통의 자세({@code pose})는 <b>오프셋까지 함께 돌린다</b>: 호랑이가 앞으로 기울면 머리와
     * 꼬리도 같이 기울어야 한다. 그러지 않으면 몸통만 숙이고 머리가 허공에 남는다.
     */
    private void apply(ItemDisplay d, Rig rig, Part part, Quaternionf pose, float bob, int interp) {
        Vector3f t = new Vector3f(part.offset());
        pose.transform(t);       // 오프셋을 몸통 자세로 돌린다 — 머리·꼬리가 몸을 따라간다
        t.y += bob;              // 상하 흔들림은 세계의 위쪽으로 — 기운 몸도 위아래로 출렁인다

        Quaternionf q = new Quaternionf(pose);
        switch (part.role()) {
            case "head" -> {
                float[] look = look(rig, part);
                q.rotateY((float) Math.toRadians(look[0]));    // 고개가 먼저 표적을 본다
                q.rotateX((float) Math.toRadians(look[1]));
            }
            case "tail" -> q.rotateY((float) Math.toRadians(part.swayDeg() * Math.sin(rig.phase)));
            default -> {
                // body · limb — 몸통의 자세 그대로
            }
        }
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(interp);
        d.setTransformation(new Transformation(t, q, new Vector3f(part.size()), new Quaternionf()));
    }

    /** 머리가 표적을 보는 각 — 몸의 yaw 에서 얼마나 더 돌아가는가 (등록부의 상한으로 자른다) */
    private float[] look(Rig rig, Part part) {
        Mob mob = rig.body instanceof Mob m ? m : null;
        LivingEntity target = mob == null ? null : mob.getTarget();
        if (target == null || part.headYawMax() <= 0) {
            return new float[]{0, 0};
        }
        Location at = rig.body.getLocation();
        double dx = target.getLocation().getX() - at.getX();
        double dz = target.getLocation().getZ() - at.getZ();
        double dy = target.getEyeLocation().getY() - (at.getY() + part.offset().y);
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 0.1) {
            return new float[]{0, 0};
        }
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yaw = clamp(wrap(want - at.getYaw()), -part.headYawMax(), part.headYawMax());
        float pitch = clamp((float) Math.toDegrees(-Math.atan2(dy, flat)),
                -part.headPitchMax(), part.headPitchMax());
        // Display 의 yaw 는 시계 반대 — 모델 로컬 회전은 부호가 뒤집힌다
        return new float[]{-yaw, pitch};
    }

    private static float wrap(float deg) {
        float d = deg % 360f;
        if (d > 180f) {
            d -= 360f;
        }
        if (d < -180f) {
            d += 360f;
        }
        return d;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ══════════════ 사건 — 공격 · 죽음 · 팩 ══════════════

    /** 칠 때 앞으로 기운다 — 덮치는 짐승의 무게 (형체가 없으면 아무 일도 없다) */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStrike(EntityDamageByEntityEvent event) {
        Rig rig = rigs.get(event.getDamager().getUniqueId());
        if (rig != null && !rig.parts.isEmpty()) {
            rig.leanUntil = tick + rig.model.motion().attackLeanTicks();
        }
    }

    /**
     * 죽음 — 몸은 사라지지만 <b>형체는 남아 쓰러진다</b>.
     *
     * <p>이것이 탑승(addPassenger)을 버린 이유 중 하나다: 탈것이 죽으면 승객은 즉시 하차하고
     * 낙하한다. 호랑이는 <b>옆으로 넘어져야</b> 한다.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        Rig rig = rigs.remove(event.getEntity().getUniqueId());
        if (rig == null) {
            return;
        }
        if (rig.parts.isEmpty()) {
            return;   // 강등 상태였다 — 바닐라 사망 연출이 이미 돌고 있다
        }
        Motion m = rig.model.motion();
        Quaternionf topple = new Quaternionf().rotateZ((float) Math.toRadians(m.deathTopple()));
        for (int i = 0; i < rig.parts.size(); i++) {
            ItemDisplay d = rig.parts.get(i);
            if (!d.isValid()) {
                continue;
            }
            Part part = rig.model.parts().get(i);
            Vector3f t = new Vector3f(part.offset());
            topple.transform(t);
            t.y -= m.deathSink();   // 쓰러지며 가라앉는다 — 땅에 눕는다
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(m.deathTicks());
            d.setTransformation(new Transformation(t, new Quaternionf(topple),
                    new Vector3f(part.size()), new Quaternionf()));
        }
        corpses.add(new Corpse(List.copyOf(rig.parts), tick + m.deathTicks() + 4));
        rig.parts.clear();
        // 본체는 어차피 사라진다 — 투명을 되돌릴 필요가 없다 (되돌리면 바닐라 사망 연출이 겹쳐 보인다)
    }

    /** 팩 수락 — 3D 층의 유일한 분기점 (SkillListener 와 같은 사건을 따로 듣는다. 서로 간섭하지 않는다) */
    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        answered.add(id);
        if (event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            packed.add(id);
        } else {
            packed.remove(id);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        joinTick.put(event.getPlayer().getUniqueId(), tick);   // 유예 시작 — 팩을 내려받는 중이다
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        packed.remove(id);
        answered.remove(id);
        joinTick.remove(id);
    }

    // ══════════════ 예산 (performance.yml) ══════════════

    private int live() {
        int n = 0;
        for (Rig rig : rigs.values()) {
            n += rig.parts.size();
        }
        for (Corpse corpse : corpses) {
            n += corpse.parts().size();
        }
        return n;
    }

    private static void despawn(List<ItemDisplay> parts) {
        for (ItemDisplay d : parts) {
            if (d.isValid()) {
                d.remove();
            }
        }
    }
}
