package com.honcheon.mvt;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 무공의 3D 모션 — 파티클 위에 얹는 층 (디스플레이 엔티티).
 *
 * <p>검기가 날아가면 그것은 점 무리가 아니라 <b>한 자루의 빛나는 획</b>이어야 한다. 이 클래스는
 * {@code config/skill_motion.yml} 의 {@code display:} 절을 읽어 {@link ItemDisplay} 로 그 획을 띄운다.
 *
 * <p><b>불변식 ㅁ — 디스플레이는 덧칠이다.</b> 이 클래스가 하는 일 전부가 실패해도(팩 없음 · 예산 초과 ·
 * 미등록 · 월드 없음) 파티클 층은 그대로 돈다. 그러므로 {@code require-resource-pack=false} 는
 * 이 층에서도 불가침이다 — 3D 는 특권이 아니라 사치다. 모든 진입점은 <b>조용히 아무것도 하지 않는</b>
 * 것으로 실패한다.
 *
 * <p><b>팩 유무의 분기</b> ({@link org.bukkit.event.player.PlayerResourcePackStatusEvent} — SkillListener 가 먹인다):
 * <ul>
 *   <li>팩을 받은 눈 → {@code item_model} 컴포넌트(honcheon:qi/blade_arc)를 얹은 아이템. 팩이 구운 3D 획</li>
 *   <li>팩이 없는 눈 → 키를 <b>아예 얹지 않는다</b>. 등록부의 {@code fallback} 바닐라 아이템이 그대로 뜬다
 *       (철검이 날아가고 · 흰 막대가 몸을 돌고 · 검이 손을 떠난다). {@code HELD} = 든 무기 그 자체</li>
 * </ul>
 * 키만 얹고 모델이 없으면 클라이언트는 '없는 모델'(보라·검정 큐브)을 그린다 — 그것이 이 분기의 이유다.
 * 두 부류의 눈이 한 자리에 섞여 있으면 <b>변주 2개</b>를 띄우고 서로에게 숨긴다
 * ({@code setVisibleByDefault(false)} + {@code showEntity}). 한 부류뿐이면 1개다 (보통은 여기다).
 *
 * <p><b>보간이 부드러움의 전부다</b> — {@code setInterpolationDuration}(형체의 자람·수축·회전) ·
 * {@code setTeleportDuration}(날아가는 것의 이동). 이 둘이 0이면 형체가 <b>튄다</b>.
 */
final class SkillDisplay {

    /** 유령 표식 — 플러그인이 죽어도 이 표를 단 몸은 다음 기동에 청소된다 (crash-safe cleanup) */
    static final NamespacedKey KEY_VFX = new NamespacedKey("honcheon", "vfx");

    private final HoncheonMvt plugin;
    private final SkillEngine engine;

    /** 팩을 받은 눈 — 이 집합에 없으면 폴백(바닐라 아이템)을 본다 */
    private final Set<UUID> packed = new HashSet<>();
    /** 살아 있는 일회성 형체 (궤 · 투사 · 개화) */
    private final List<Piece> pieces = new ArrayList<>();
    /** 몸을 두르는 고리 — 심장박동으로 산다 (갱신이 끊기면 스스로 사라진다) */
    private final Map<UUID, Ring> rings = new HashMap<>();
    private final Map<String, Material> materials = new HashMap<>();

    private long tick;

    SkillDisplay(HoncheonMvt plugin, SkillEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    // ══════════ 수명 ══════════

    /** 한 발 = 같은 자리에 뜬 1~2개의 변주(팩 / 폴백). use_held 면 언제나 1개다 */
    private static final class Piece {
        final List<Display> parts = new ArrayList<>(2);
        final SkillEngine.DisplayMotion m;
        final UUID owner;
        long dieAt;
        boolean fading;
        // 투사 전용
        Vector dir;
        Location head;
        double flown;
        double range;
        boolean landed;
        // 개화 전용 — 개시의 순간 (선딜이 끝나는 틱)
        long burstAt = -1;
        double burstRadius;
        // 휘두름 전용 — 히트박스를 훑는다 (origin·flat 은 시작 틱에 고정: 6틱 사이에 몸은 순간이동하지 않는다)
        SkillEngine.Swing swing;
        String shape;
        Location origin;
        Vector flat;
        double reachRange;
        double halfAngle;
        int span;
        int age;

        Piece(SkillEngine.DisplayMotion m, UUID owner) {
            this.m = m;
            this.owner = owner;
        }
    }

    /** 호신강기의 고리 — 조각들이 궤도를 돈다 */
    private static final class Ring {
        final List<Display> parts = new ArrayList<>();
        final SkillEngine.DisplayMotion m;
        long lastBeat;

        Ring(SkillEngine.DisplayMotion m) {
            this.m = m;
        }
    }

    /**
     * 기동 — 지난 생의 유령을 걷어낸다.
     *
     * <p>디스플레이는 엔티티다. 플러그인이 예고 없이 죽으면(크래시·강제 종료) 획이 공중에 <b>얼어붙는다</b>.
     * {@code setPersistent(false)} 가 저장은 막지만 그 세션의 청소는 못 한다 — 그래서 표식을 달고,
     * 다음 기동에 표식을 단 몸을 전부 지운다. 이 청소가 없으면 세계는 남의 검기로 채워진다.
     */
    void start() {
        int swept = 0;
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e instanceof Display && e.getPersistentDataContainer().has(KEY_VFX)) {
                    e.remove();
                    swept++;
                }
            }
        }
        if (swept > 0) {
            plugin.getLogger().info("[혼천] 지난 생의 무공 형체 " + swept + "개를 걷었다");
        }
    }

    /** 중앙 티커에서 매 틱 — 투사 전진 · 수축 · 회수 (효과별 개별 태스크 생성 금지, F-P2) */
    void tick(long now) {
        this.tick = now;
        for (Iterator<Piece> it = pieces.iterator(); it.hasNext(); ) {
            Piece p = it.next();
            if (p.parts.isEmpty() || !p.parts.get(0).isValid()) {
                despawn(p.parts);
                it.remove();
                continue;
            }
            if (p.m.isBolt() && !p.landed) {
                fly(p);
            }
            if (p.m.isSwing() && p.age < p.span) {
                sweep(p);
            }
            if (p.burstAt >= 0 && now >= p.burstAt) {
                bloom(p);
            }
            if (!p.fading && p.m.fade() > 0 && now >= p.dieAt - p.m.fade()) {
                p.fading = true;
                for (Display d : p.parts) {
                    scale(d, 0.001f, p.m.fade());   // 사라지는 것도 연출이다 — 획이 스러진다
                }
            }
            if (now >= p.dieAt) {
                despawn(p.parts);
                it.remove();
            }
        }
        rings.entrySet().removeIf(e -> {
            Ring ring = e.getValue();
            if (now - ring.lastBeat <= engine.displayBudget().ringHeartbeatTicks()) {
                return false;
            }
            despawn(ring.parts);   // 심장이 멎었다 — 두름을 거뒀거나, 죽었거나, 세계를 떠났다
            return true;
        });
    }

    /** 정리 (performance.yml effects.cleanup_on · onDisable) */
    void clear(UUID body) {
        Ring ring = rings.remove(body);
        if (ring != null) {
            despawn(ring.parts);
        }
        packed.remove(body);
        pieces.removeIf(p -> {
            if (!body.equals(p.owner)) {
                return false;
            }
            despawn(p.parts);
            return true;
        });
    }

    /** 플러그인이 내려간다 — 형체를 남기지 않는다 */
    void clearAll() {
        for (Piece p : pieces) {
            despawn(p.parts);
        }
        pieces.clear();
        for (Ring r : rings.values()) {
            despawn(r.parts);
        }
        rings.clear();
    }

    /** 팩 수락 여부 — SkillListener 의 PlayerResourcePackStatusEvent 가 먹인다 */
    void packStatus(UUID viewer, boolean accepted) {
        if (accepted) {
            packed.add(viewer);
        } else {
            packed.remove(viewer);
        }
    }

    // ══════════ 발행 — 격 · 형태 · 오의 ══════════

    /**
     * <b>병기 휘두름(握)</b> — 손에 든 병기 그 자체가 히트박스를 훑는다.
     *
     * <p>바닐라의 손 흔들기는 무협의 검이 아니다. 검은 <b>베는 자리를 지나가야</b> 한다. 그래서 한 타마다
     * {@link ItemDisplay} 하나가 뜨고 거기에 <b>플레이어의 실제 {@link ItemStack}</b> 이 실린다 —
     * 팩이 켜진 눈에는 팩이 구운 3D 병기가, 꺼진 눈에는 바닐라 모델이 <b>같은 엔티티 하나로</b> 돈다.
     * 팩 게이트가 저절로 충족되고, 변주 2벌이 필요 없으니 값도 절반이다.
     *
     * <p><b>보이는 것 = 맞는 것</b> — {@code range}·{@code angle} 은 판정이 쓴 값(skill_mechanics 의
     * 히트박스)이 그대로 들어온다. 등록부({@code swings}) 가 할 수 있는 일은 {@code reach ≤ 1.0} 으로
     * <b>줄이는 것뿐</b>이다. 히트박스 3.5m 에 획 6m 를 그릴 방법이 이 구조에는 없다.
     *
     * @param swingTicks 자세가 돌아오는 데 걸리는 틱 (프레임·계열 공속 중 긴 쪽) — 공속이 곧 리듬이다
     * @return 형체가 실제로 떴는가 (떴으면 궤적 파티클은 물러선다 — display.blend)
     */
    boolean sweep(Player caster, String hitType, String weaponClass, double range, double angle,
                  int swingTicks) {
        SkillEngine.Swing sw = engine.swing(weaponClass);
        SkillEngine.DisplayMotion m = engine.swingMotion();
        SkillEngine.Traj traj = engine.trajectory(hitType);
        SkillEngine.DisplayBudget b = engine.displayBudget();
        if (sw == null || m == null || traj == null || "aura".equals(traj.shape())) {
            return false;   // 맨손·무관·짐승·활 — 휘두를 병기가 없다 / 태세 — 벨 것이 없다
        }
        ItemStack held = caster.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            return false;   // 빈손은 형체를 남기지 않는다
        }
        Vector flat = flat(caster);
        Piece p = spawn(m, caster.getEyeLocation(), caster, 1, false);
        if (p == null) {
            return false;   // 예산 강등 — 파티클이 그 자리를 지킨다
        }
        p.swing = sw;
        p.shape = traj.shape();
        p.origin = caster.getEyeLocation().subtract(0, 0.25, 0);
        p.flat = flat;
        p.reachRange = range * sw.reach();          // 【정직의 못】 판정이 준 사거리를 넘지 못한다
        p.halfAngle = Math.toRadians(angle / 2.0);  // 부채꼴도 판정이 준 각 그대로
        p.span = (int) clamp(Math.round(swingTicks * sw.spanRatio()),
                b.swingMinTicks(), Math.min(b.swingMaxTicks(), m.lifetime() - b.swingFadeTicks()));
        p.dieAt = tick + p.span + b.swingFadeTicks();

        for (Display d : p.parts) {
            d.setTeleportDuration(1);               // 이동 보간 — 이것이 없으면 검이 순간이동한다
            transform(d, size(m, 1.0f), 0.0f, Math.max(1, m.birth()));
        }
        sweep(p);                                   // 첫 자리 — 다음 틱을 기다리지 않는다
        return true;
    }

    /** 한 틱의 휘두름 — 병기가 히트박스 위의 다음 자리로 간다 (보간이 그 사이를 잇는다) */
    private void sweep(Piece p) {
        double t = p.span <= 1 ? 1.0 : p.age / (double) (p.span - 1);   // 0 → 1
        p.age++;
        Vector out;
        double lift = 0.0;
        switch (p.shape) {
            case "arc" -> {                          // 호 — 부채꼴을 한쪽 끝에서 다른 끝으로 훑는다
                double a = -p.halfAngle + 2 * p.halfAngle * t;
                out = p.flat.clone().rotateAroundY(-a).multiply(p.reachRange);
                lift = Math.sin(Math.PI * t) * 0.35;                    // 호는 살짝 솟았다 내려온다
            }
            case "line", "shot" -> {                 // 선 — 곧게 뻗는다 (창의 간격이 눈에 보인다)
                double ease = t < 0.6 ? t / 0.6 : 1.0 - (t - 0.6) / 0.4 * 0.35;   // 찌르고 조금 거둔다
                out = p.flat.clone().multiply(p.reachRange * ease);
            }
            case "circle", "ring" -> {               // 원 — 몸을 한 바퀴 돈다
                double a = Math.PI * 2 * t;
                out = p.flat.clone().rotateAroundY(-a).multiply(p.reachRange);
            }
            case "dash" -> {                         // 돌 — 지나온 자리에 남는다 (뒤로 흐른다)
                out = p.flat.clone().multiply(-p.reachRange * t);
            }
            default -> {
                return;
            }
        }
        // 베는 궤적은 **위치가 그린다** — 병기가 히트박스 위를 실제로 지나간다 (등록부는 이 경로를 못 바꾼다)
        Location at = p.origin.clone().add(out).add(0, lift, 0);
        at.setDirection(out.lengthSquared() < 1.0e-6 ? p.flat : out.clone().normalize());
        at.setPitch(at.getPitch() + p.swing.tilt());   // MC 규약: pitch 양수 = 아래. 부는 날을 아래로 세운다
        for (Display d : p.parts) {
            if (!d.isValid()) {
                continue;
            }
            d.teleport(at);
            if (p.swing.roll() != 0.0f) {
                // 그 위에 얹는 비틀림 — 병기가 제 길이축(X)을 돈다 (겸은 음수: 반대로 감아 당긴다)
                spin(d, p.m, (float) (p.swing.roll() * t));
            }
        }
    }

    /**
     * 근접 한 타에 겹치는 <b>기의 획</b> — 격이 실렸을 때만 (검기 이상).
     *
     * <p>병기의 궤적 위에 기의 획이 겹친다: 검기를 두른 검은 쇠의 자국만 남기지 않는다.
     * 격이 없으면(외공기·발경) 이 층은 아무것도 하지 않는다 — <b>쇠의 궤적은 쇠 그 자체가 그린다</b>.
     *
     * @return 형체가 실제로 떴는가
     */
    boolean streak(Player caster, String hitType, String grade, double range, double angle) {
        SkillEngine.DisplayMotion m = engine.displayForSwing(grade);
        SkillEngine.Traj traj = engine.trajectory(hitType);
        if (m == null || traj == null) {
            return false;   // 발경·외공기 — 기가 밖으로 형체를 내지 않는다 (파티클만으로 읽힌다)
        }
        Vector dir = caster.getLocation().getDirection().normalize();
        Vector flat = flat(caster);

        Location at;
        float stretch = 1.0f;
        switch (traj.shape()) {
            case "line", "shot" -> {                       // 선 — 앞으로 뻗은 획 (찌름·참격)
                at = caster.getEyeLocation().add(dir.clone().multiply(range * 0.5));
                at.setDirection(dir);
                stretch = (float) clamp(range / 3.0, 0.8, 1.8);
            }
            case "circle", "ring" -> {                     // 원·진 — 사방을 훑는 고리
                at = caster.getLocation().add(0, 1.0, 0);
                at.setDirection(flat);
                stretch = (float) clamp(range / 2.5, 1.0, 2.4);
            }
            case "dash" -> {                               // 돌 — 지나온 자리에 남는 잔영
                at = caster.getLocation().add(0, 0.9, 0).subtract(flat.clone().multiply(1.2));
                at.setDirection(flat);
            }
            case "arc" -> {                                // 호 — 벤 자리에 걸리는 초승달
                at = caster.getEyeLocation()
                        .add(flat.clone().multiply(range * traj.radiusRatio() * 0.6))
                        .subtract(0, 0.25, 0);
                at.setDirection(flat);
                stretch = (float) clamp(angle / 90.0, 0.8, 1.6);
            }
            default -> {
                return false;   // aura(태세·가드태세·시전) — 몸에 머무는 것에는 획이 없다
            }
        }
        Piece p = spawn(m, at, caster, 1, false);
        if (p == null) {
            return false;
        }
        p.dieAt = tick + m.lifetime();
        float[] full = size(m, stretch);
        for (Display d : p.parts) {
            transform(d, full, 0.0f, m.birth() > 0 ? m.birth() : m.interpolation());
        }
        return true;
    }

    /** 시선의 수평 성분 — 획은 하늘을 보지 않는다 (베는 것은 땅 위의 일이다) */
    private static Vector flat(Player caster) {
        Vector flat = caster.getLocation().getDirection().setY(0);
        if (flat.lengthSquared() < 1.0e-6) {
            return new Vector(1, 0, 0);
        }
        return flat.normalize();
    }

    /**
     * 발출 — 기가 <b>날아간다</b>. 한 자루의 획이 궤도를 그으며 뻗어 나가고, 끝에서 확장하며 스러진다.
     *
     * <p><b>판정은 즉발이다</b> (선 히트박스 — 쏜 틱에 사거리 안의 것이 다 맞는다). 그러므로 이 획은
     * 판정을 뒤따르는 잔상이 아니라 <b>같은 틱에 태어나 다섯 틱 안에 끝을 보는 것</b>이다
     * (검기 1.6m/틱). 느리게 날면 "맞고 나서 검기가 온다" — 그것이 거짓말이다.
     *
     * @return 형체가 실제로 떴는가 (떴으면 광선 파티클은 물러선다 — display.blend)
     */
    boolean bolt(Player caster, String formName, double range) {
        SkillEngine.DisplayMotion m = engine.displayForForm(formName);
        if (m == null || !m.isBolt() || m.speed() <= 0) {
            return false;
        }
        Vector dir = caster.getLocation().getDirection().normalize();
        Location from = caster.getEyeLocation().add(dir.clone().multiply(0.8));
        from.setDirection(dir);

        Piece p = spawn(m, from, caster, 1, false);
        if (p == null) {
            return false;
        }
        p.dir = dir;
        p.head = from;
        p.range = range;
        // 수명 = 나는 시간 + 착탄 확장. 등록부의 lifetime 은 그 상한이다 (예산의 못)
        p.dieAt = tick + Math.min(m.lifetime(),
                (int) Math.ceil(range / m.speed()) + m.impactTicks() + m.fade());
        float[] full = size(m, 1.0f);
        for (Display d : p.parts) {
            d.setTeleportDuration(1);   // 이동 보간 — 이것이 없으면 획이 순간이동한다
            transform(d, full, 0.0f, Math.max(1, m.birth()));
        }
        return true;
    }

    /** 한 틱의 비행 — 벽을 만나거나 사거리를 다하면 그 자리에서 확장하며 스러진다 */
    private void fly(Piece p) {
        Location next = p.head.clone().add(p.dir.clone().multiply(p.m.speed()));
        p.flown += p.m.speed();
        boolean wall = next.getWorld() != null && next.getBlock().getType().isSolid();
        if (wall || p.flown >= p.range) {
            land(p);
            return;
        }
        p.head = next;
        for (Display d : p.parts) {
            d.teleport(next);
            if (p.m.spin() != 0.0f) {
                spin(d, p.m, (float) (p.flown * p.m.spin()));   // 자전 — 살아 있다는 표시
            }
        }
    }

    /** 착탄 — 형체가 부풀며 터진다 (강기는 2.2배 — 뚫는 것의 무게) */
    private void land(Piece p) {
        p.landed = true;
        p.dieAt = tick + p.m.impactTicks() + p.m.fade();
        float[] burst = size(p.m, p.m.impactScale());
        for (Display d : p.parts) {
            transform(d, burst, 0.0f, p.m.impactTicks());
        }
    }

    /**
     * 호신강기 — 몸을 두르는 <b>회전 고리</b>. 손끝 잔광(두름)과 실루엣이 다르다: 그 차이가 곧 정보다.
     *
     * <p>심장박동으로 산다 — {@code sustain()} 이 4틱마다 이것을 부르고, 부름이 끊기면
     * ({@code ring_heartbeat_ticks} 12틱) 고리는 스스로 사라진다. 두름을 거둔 순간·죽은 순간·
     * 세계를 떠난 순간에 코드가 따로 지울 필요가 없다 — <b>유령이 남지 않는 구조</b>다.
     */
    void ring(LivingEntity body, String formName) {
        SkillEngine.DisplayMotion m = engine.displayForForm(formName);
        if (m == null || !m.isRing() || body.getWorld() == null) {
            return;
        }
        Ring ring = rings.get(body.getUniqueId());
        if (ring == null) {
            if (!afford(m.count() * variants(body.getLocation()), false)) {
                return;   // 예산이 없다 — 고리는 가장 먼저 강등된다 (파티클 고리가 이미 돌고 있다)
            }
            ring = new Ring(m);
            for (int i = 0; i < m.count(); i++) {
                Piece part = spawn(m, orbit(body, m, i), body instanceof Player pl ? pl : null,
                        1, false, body.getUniqueId());
                if (part == null) {
                    break;
                }
                pieces.remove(part);            // 고리는 수명으로 죽지 않는다 — 심장박동으로 산다
                ring.parts.addAll(part.parts);
                for (Display d : part.parts) {
                    d.setTeleportDuration(engine.displayBudget().ringHeartbeatTicks() / 3);
                    transform(d, size(m, 1.0f), 0.0f, m.interpolation());
                }
            }
            if (ring.parts.isEmpty()) {
                return;
            }
            rings.put(body.getUniqueId(), ring);
        }
        ring.lastBeat = tick;
        int n = Math.max(1, ring.m.count());
        for (int i = 0; i < ring.parts.size(); i++) {
            Display d = ring.parts.get(i);
            if (d.isValid()) {
                d.teleport(orbit(body, ring.m, i % n));   // 궤도를 돈다 (보간이 끊김을 메운다)
            }
        }
    }

    /** 궤도 위의 한 자리 — 조각은 바깥을 본다 (몸을 두른 판이 세상을 향한다) */
    private Location orbit(LivingEntity body, SkillEngine.DisplayMotion m, int i) {
        double a = Math.PI * 2 * i / Math.max(1, m.count()) + tick * m.orbit();
        Vector out = new Vector(Math.cos(a), 0, Math.sin(a));
        Location at = body.getLocation().add(0, m.height(), 0)
                .add(out.clone().multiply(m.radius()));
        at.setDirection(out);
        return at;
    }

    /**
     * 오의 — 한 번 보면 잊지 못할 형체. 응집 내내 <b>자라고</b>, 개시의 순간 <b>활짝 편다</b>.
     *
     * <p>다른 어떤 모션도 이렇게 움직이지 않는다 (격의 획은 스치고 지나갈 뿐이다).
     * 파티클 층의 구분 수단 셋(응집 고리 · 개시 섬광 · 이름) 위에 <b>넷째 — 형체</b>가 얹힌다.
     */
    void bloom(Player caster, String ultimateId, int startup, double range) {
        SkillEngine.DisplayMotion m = engine.displayForUltimate(ultimateId);
        if (m == null || !m.isBloom()) {
            return;
        }
        Location at = caster.getLocation().add(0, 1.0, 0);
        at.setDirection(caster.getLocation().getDirection().clone().setY(0));
        Piece p = spawn(m, at, caster, 1, true);   // 오의는 예약분을 쓴다 — 깎이지 않는다
        if (p == null) {
            return;
        }
        p.burstAt = tick + startup;
        p.burstRadius = range;
        p.dieAt = tick + startup + m.lifetime();
        // 응집 — 오므린 채 선딜 내내 자란다 (상대가 보고 물러설 시간이 곧 이 보간이다)
        float[] bud = size(m, (float) Math.max(0.35, range * 0.25));
        for (Display d : p.parts) {
            transform(d, bud, 0.0f, Math.max(1, startup));
        }
    }

    /** 개시 — 형체가 사거리만큼 펴진다 (매화가 핀다 · 원반이 선다 · 칼이 떨어진다 · 피가 퍼진다) */
    private void bloom(Piece p) {
        p.burstAt = -1;
        float[] open = size(p.m, (float) (p.burstRadius * p.m.burstScale()));
        for (Display d : p.parts) {
            transform(d, open, p.m.spin() * p.m.interpolation(), p.m.interpolation());
        }
    }

    // ══════════ 발행 (내부) — 예산 · 팩 분기 · 변환 ══════════

    private Piece spawn(SkillEngine.DisplayMotion m, Location at, Player owner,
                        int units, boolean ultimate) {
        return spawn(m, at, owner, units, ultimate, owner == null ? null : owner.getUniqueId());
    }

    /**
     * 한 발을 띄운다.
     *
     * <p><b>use_held</b>(병기 휘두름 · 이기어검) — 실은 것이 사람의 병기 그 자체이므로 팩 유무가
     * 그림을 바꾸지 않는다. <b>엔티티 하나</b>를 모두에게 보인다 (분기 없음 · 값도 절반).
     *
     * <p>그 밖 — 팩을 받은 눈과 못 받은 눈이 섞여 있으면 <b>변주 2개</b>를 띄우고 서로에게 숨긴다
     * (팩 키를 얹은 것 / 폴백 바닐라 아이템). 한 부류뿐이면 1개다.
     *
     * @return 예산이 없거나 볼 사람이 없으면 null (그 자리에 파티클은 이미 떠 있다 — 강등이지 실종이 아니다)
     */
    private Piece spawn(SkillEngine.DisplayMotion m, Location at, Player owner,
                        int units, boolean ultimate, UUID ownerId) {
        SkillEngine.DisplayModel model = engine.displayModel(m.model());
        if (model == null || at.getWorld() == null) {
            return null;
        }
        ItemStack held = owner == null ? null : owner.getInventory().getItemInMainHand();

        if (model.useHeld()) {
            ItemStack stack = held == null || held.getType().isAir() ? null : held.clone();
            if (stack == null || !afford(units, ultimate)
                    || (ownerId != null && !affordPlayer(ownerId, units))) {
                return null;
            }
            Piece p = new Piece(m, ownerId);
            p.parts.add(create(at, m, stack, null));   // 모두의 눈에 — 팩 분기가 없다
            p.dieAt = tick + m.lifetime();
            pieces.add(p);
            return p;
        }

        List<Player> withPack = new ArrayList<>();
        List<Player> without = new ArrayList<>();
        audience(at, withPack, without);
        if (withPack.isEmpty() && without.isEmpty()) {
            return null;   // 볼 눈이 없다 — 32m 안에 아무도 없는 산속의 검기는 뜨지 않는다
        }
        int need = units * ((withPack.isEmpty() ? 0 : 1) + (without.isEmpty() ? 0 : 1));
        if (!afford(need, ultimate) || (ownerId != null && !affordPlayer(ownerId, need))) {
            return null;   // 예산 초과 — over_cap: "파티클 연출로 강등" (performance.yml vfx_entities)
        }

        Piece p = new Piece(m, ownerId);
        if (!withPack.isEmpty()) {
            ItemStack packedItem = item(model, true, held);
            if (packedItem != null) {
                p.parts.add(create(at, m, packedItem, withPack));
            }
        }
        if (!without.isEmpty()) {
            ItemStack plainItem = item(model, false, held);
            if (plainItem != null) {
                p.parts.add(create(at, m, plainItem, without));
            }
        }
        if (p.parts.isEmpty()) {
            return null;
        }
        p.dieAt = tick + m.lifetime();
        pieces.add(p);
        return p;
    }

    /** @param viewers null 이면 32m 안의 모두에게 보인다 (use_held — 팩 분기가 없는 형체) */
    private Display create(Location at, SkillEngine.DisplayMotion m, ItemStack stack,
                           List<Player> viewers) {
        SkillEngine.DisplayBudget b = engine.displayBudget();
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(stack);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);   // 손 변형 없이 모델 그대로
            e.setBillboard(billboard(m.billboard()));
            e.setBrightness(new Display.Brightness(m.blockLight(), m.skyLight()));   // 기는 스스로 빛난다
            e.setViewRange(b.viewRange());              // 32m — 파티클 cull_beyond 와 같은 눈금
            e.setInterpolationDelay(0);
            e.setInterpolationDuration(m.interpolation());
            e.setPersistent(false);                     // 저장되지 않는다 (유령의 첫 방벽)
            e.setVisibleByDefault(viewers == null);     // 팩 분기 — 보여 줄 눈에만 보여 준다
            e.getPersistentDataContainer().set(KEY_VFX, PersistentDataType.BYTE, (byte) 1);
            // 태어날 때는 씨앗만 하다 — 곧바로 제 크기로 자란다 (birth 보간)
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(0.001f, 0.001f, 0.001f), new Quaternionf()));
        });
        if (viewers != null) {
            for (Player viewer : viewers) {
                viewer.showEntity(plugin, d);
            }
        }
        return d;
    }

    /** 이 자리에 몇 벌의 변주가 필요한가 — 관람석이 둘로 갈려 있으면 2, 한 부류뿐이면 1 */
    private int variants(Location at) {
        if (at.getWorld() == null) {
            return 0;
        }
        List<Player> withPack = new ArrayList<>();
        List<Player> without = new ArrayList<>();
        audience(at, withPack, without);
        return (withPack.isEmpty() ? 0 : 1) + (without.isEmpty() ? 0 : 1);
    }

    /**
     * 팩 유무로 관람석을 가른다 — 32m(cull_beyond) 안의 눈만 센다.
     * 지금 서버는 팩을 배포하지 않는다({@code resource-pack=} 빈 값) → 전원이 폴백 관람석이다.
     * 팩이 배포되는 날 이 함수가 알아서 두 관람석을 만든다.
     */
    private void audience(Location at, List<Player> withPack, List<Player> without) {
        double cull = engine.cullBeyond();
        for (Player viewer : at.getWorld().getPlayers()) {
            if (viewer.getLocation().distanceSquared(at) > cull * cull) {
                continue;
            }
            (packed.contains(viewer.getUniqueId()) ? withPack : without).add(viewer);
        }
    }

    /**
     * 실을 아이템 — 팩의 유일한 접점.
     *
     * @param withPack true 면 {@code item_model} 키를 얹는다 (팩이 그 키로 3D 를 굽는다).
     *                 false 면 <b>키를 얹지 않는다</b> — 폴백 바닐라 아이템이 그대로 보인다
     */
    @SuppressWarnings("deprecation")
    private ItemStack item(SkillEngine.DisplayModel model, boolean withPack, ItemStack held) {
        if (model.useHeld()) {
            // 병기 그 자체 — 팩 유무와 무관하다 (팩이 켜지면 그 병기의 3D 모델이 그대로 돈다)
            return held == null || held.getType().isAir() ? null : held.clone();
        }
        Material base = material(withPack ? model.base() : model.fallback());
        if (base == null || base.isAir()) {
            return null;
        }
        ItemStack stack = new ItemStack(base);
        if (withPack && model.key() != null) {
            ItemMeta meta = stack.getItemMeta();
            NamespacedKey key = NamespacedKey.fromString(model.key());
            if (meta != null && key != null) {
                meta.setItemModel(key);
                stack.setItemMeta(meta);
            }
        }
        return stack;
    }

    /** 등록부의 아이템 이름 → 바닐라 Material. <b>코드가 아이템을 고르지 않는다</b> (등록제 규약) */
    private Material material(String name) {
        if (name == null) {
            return null;
        }
        return materials.computeIfAbsent(name, n -> {
            Material m = Material.matchMaterial(n);
            if (m == null) {
                plugin.getLogger().warning("[혼천] 모션 등록부에 없는 아이템: " + n
                        + " (config/skill_motion.yml display.models)");
            }
            return m;
        });
    }

    // ─── 예산 (performance.yml vfx_entities) ───

    private int live() {
        int n = 0;
        for (Piece p : pieces) {
            n += p.parts.size();
        }
        for (Ring r : rings.values()) {
            n += r.parts.size();
        }
        return n;
    }

    /**
     * 예산 — 오의는 <b>예약분</b>을 쓴다 (파티클 층의 우선권과 같은 문법: 생략이 아니라 우선).
     * 그 밖의 형체는 {@code degrade_at} 에서 멈춘다 — 잡졸의 참격이 오의의 자리를 먹지 못한다.
     */
    private boolean afford(int need, boolean ultimate) {
        SkillEngine.DisplayBudget b = engine.displayBudget();
        int cap = ultimate ? b.globalCap()
                : Math.min(b.degradeAt(), b.globalCap() - b.reserveForUltimate());
        return live() + need <= cap;
    }

    /** 한 몸이 세계를 형체로 덮지 못한다 (고리 4 + 궤 1 + 투사 1 + 여유 2) */
    private boolean affordPlayer(UUID owner, int need) {
        int mine = 0;
        for (Piece p : pieces) {
            if (owner.equals(p.owner)) {
                mine += p.parts.size();
            }
        }
        Ring ring = rings.get(owner);
        if (ring != null) {
            mine += ring.parts.size();
        }
        return mine + need <= engine.displayBudget().perPlayerMax();
    }

    // ─── 변환 (보간이 부드러움의 전부다) ───

    /** 등록부의 치수 × 배율 × 그 자리의 늘림 */
    private float[] size(SkillEngine.DisplayMotion m, float stretch) {
        SkillEngine.DisplayModel model = engine.displayModel(m.model());
        float[] s = model == null ? new float[]{1, 1, 1} : model.size();
        return new float[]{
                s[0] * m.scale()[0] * stretch,
                s[1] * m.scale()[1] * (stretch > 1.0f ? 1.0f : stretch),   // 길이만 늘린다 — 두께는 그대로
                s[2] * m.scale()[2]};
    }

    private void transform(Display d, float[] scale, float roll, int ticks) {
        if (!d.isValid()) {
            return;
        }
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(Math.max(1, ticks));
        d.setTransformation(new Transformation(new Vector3f(), roll(roll),
                new Vector3f(scale[0], scale[1], scale[2]), new Quaternionf()));
    }

    private void scale(Display d, float factor, int ticks) {
        if (!d.isValid()) {
            return;
        }
        Transformation t = d.getTransformation();
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(Math.max(1, ticks));
        d.setTransformation(new Transformation(t.getTranslation(), t.getLeftRotation(),
                new Vector3f(t.getScale()).mul(factor), t.getRightRotation()));
    }

    private void spin(Display d, SkillEngine.DisplayMotion m, float angle) {
        if (!d.isValid()) {
            return;
        }
        Transformation t = d.getTransformation();
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(Math.max(1, m.interpolation()));
        d.setTransformation(new Transformation(t.getTranslation(), roll(angle),
                t.getScale(), t.getRightRotation()));
    }

    /** 자전 — 축은 진행 방향(X). 모델 규약이 "획을 +X 로 눕힌다"인 이유가 여기 있다 */
    private static Quaternionf roll(float angle) {
        return new Quaternionf(new AxisAngle4f(angle, 1.0f, 0.0f, 0.0f));
    }

    private static Display.Billboard billboard(String name) {
        try {
            return Display.Billboard.valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Display.Billboard.FIXED;   // 진행 방향을 본다 (획에는 방향이 있다)
        }
    }

    private static void despawn(List<Display> parts) {
        for (Display d : parts) {
            if (d.isValid()) {
                d.remove();
            }
        }
        parts.clear();
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
