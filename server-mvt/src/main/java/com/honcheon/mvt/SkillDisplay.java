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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 무공의 3D 모션 — 파티클 위에 얹는 층 (디스플레이 엔티티).
 *
 * <p><b>세 층</b> (사용자의 정정을 그대로 새긴다):
 * <ol>
 *   <li><b>손의 병기</b> — 팩이 구운 3D 모델. 바닐라가 그린다. <b>우리는 손대지 않는다</b></li>
 *   <li><b>날의 기</b> ({@link #sheath}) — 격을 두르면 손에 든 병기에 <b>겹쳐</b> 기가 서린다 (지속).
 *       태세만 잡아도 남이 안다 — 그래야 전의(戰意) 규칙의 '격 목격'이 거짓말이 아니다</li>
 *   <li><b>참격선</b> ({@link #slash}) — 휘두르면 <b>지나간 자리</b>가 남는다. 만화의 검격이다.
 *       <b>검을 그리지 않는다</b> (병기를 실은 디스플레이는 '검이 복제되어 나는 것'으로 보였다 — 버린 설계)</li>
 * </ol>
 * 그 위에 <b>날아가는 것</b>이 붙는다: 발출(기) · 던진 암기 · 이기어검 — 이것들은 정말로 손을 떠나므로
 * 물건이 나는 것이 맞다 ({@code use_held}).
 *
 * <p><b>불변식 ㅁ — 디스플레이는 덧칠이다.</b> 이 클래스가 하는 일 전부가 실패해도(팩 없음 · 예산 초과 ·
 * 미등록 · 볼 눈 없음) 파티클 층은 그대로 돈다. 모든 진입점은 <b>조용히 아무것도 하지 않는</b> 것으로
 * 실패하고 {@code false} 를 돌려준다 — 그러면 부르는 쪽이 파티클을 온전히 뿌린다.
 *
 * <p><b>불변식 ㅂ — 획은 히트박스를 벗어나지 못한다.</b> {@code range}·{@code angle} 은 판정이 준다.
 * 등록부는 {@code reach ≤ 1.0} 으로 줄일 수만 있다.
 */
final class SkillDisplay {

    /** 유령 표식 — 플러그인이 죽어도 이 표를 단 몸은 다음 기동에 청소된다 (crash-safe) */
    static final NamespacedKey KEY_VFX = new NamespacedKey("honcheon", "vfx");

    private final HoncheonMvt plugin;
    /** 등록부. {@code final} 이 아닌 이유는 {@link #rebind} — 핫 리로드가 이 참조를 갈아끼운다 */
    private SkillEngine engine;

    /** 팩을 받은 눈 — 이 집합에 없으면 폴백을 보거나, 아무것도 못 본다 (그 자리는 파티클이 지킨다) */
    private final Set<UUID> packed = new HashSet<>();
    private final List<Piece> pieces = new ArrayList<>();
    /** ★ 시전자당 검기 평타 판 1장 — 새 스윙이 이전 판을 즉시 거둔다 (연타 상시 점등·누적 방지) */
    private final Map<UUID, Piece> kigiByCaster = new HashMap<>();
    /** 지속 형체 — 몸 → (자리 → 형체). 심장박동으로 산다 (갱신이 끊기면 스스로 사라진다) */
    private final Map<UUID, Map<String, Persist>> persistent = new HashMap<>();
    private final Map<String, Material> materials = new HashMap<>();
    /**
     * <b>인게임 임시 조정</b> — {@code /혼천 획위치} 가 민 값 (모션 → [앞, 높이, 옆]).
     *
     * <p>이 자리는 <b>눈으로 봐야 정해진다</b>. 서버를 세우고 등록부를 고치고 다시 세우는 왕복으로는
     * 한 값도 못 맞춘다 — 그래서 살아 있는 서버에서 밀고 당긴다. <b>등록부를 덮어쓰지 않는다</b>:
     * 재기동하면 사라진다. 맞춘 값은 {@code /혼천 획위치 적기} 로 뽑아 등록부에 <b>사람이 적는다</b>
     * (코드가 등록부를 고치면 등록제가 무너진다).
     */
    private final Map<String, double[]> originOverride = new HashMap<>();

    /** 진단 — 최근 발행 기록 ({@code /혼천 모션진단}). 인게임에 못 들어가는 눈을 위한 창구 */
    private final List<Log> log = new ArrayList<>();
    /** 이미 로그로 말한 (형체 × 팩유무) 조합 — 같은 말을 두 번 하지 않는다 (로그가 진실을 덮지 않게) */
    private final Set<String> witnessed = new HashSet<>();
    private int denied;
    private long tick;

    private record Log(long tick, String kind, String detail) {
    }

    SkillDisplay(HoncheonMvt plugin, SkillEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    /**
     * 핫 리로드 — 새 등록부로 갈아끼운다 ({@code /혼천 모션 재적재}).
     *
     * <p><b>이미 떠 있는 형체({@link Piece})는 안 건드린다.</b> 각 조각은 소환될 때 붙잡은
     * {@link SkillEngine.DisplayMotion}(불변 레코드)으로 제 수명을 마친다 — 리로드 한복판에
     * 날던 검기가 사라지거나 터지지 않는다. 새 값은 <b>다음 발행부터</b> 실린다.
     *
     * <p>{@code materials} 는 등록부의 모델 이름을 바닐라 재료로 옮긴 캐시라 함께 버린다.
     * {@code originOverride}({@code /혼천 획위치} 가 민 값)는 <b>남긴다</b>: 그것은 config 가 아니라
     * 사람이 지금 손으로 밀고 있는 값이고, 되돌리는 손은 이미 따로 있다.
     */
    void rebind(SkillEngine engine) {
        this.engine = engine;
        materials.clear();
        witnessed.clear();
    }

    // ══════════ 수명 ══════════

    /** 한 발 = 같은 자리에 뜬 1~2개의 변주(팩 / 폴백). use_held 면 언제나 1개다 */
    private static final class Piece {
        final List<Display> parts = new ArrayList<>(2);
        final SkillEngine.DisplayMotion m;
        final UUID owner;
        long dieAt;
        boolean fading;
        // 참격선 — 그렸다가 꼬리부터 지운다
        float[] full;
        long fadeAt = -1;
        Quaternionf rot;        // ★ 스윙이 끝난 각 — 지울 때 여기서 지운다 (되돌리면 검이 도로 튄다)
        float rise;             // ★ 스윙이 끝난 높이 (올려베기·내려베기)
        /**
         * ★ <b>공전이 끝난 자리</b> (검기 평타 전용 · 그 밖은 null) — 엔티티 국소축의 translation.
         * 지울 때 이 자리를 <b>그대로</b> 써야 한다: {@link #transform} 의 headAnchor 로 돌아가면
         * 다 휩쓴 초승달이 <b>몸 한복판으로 순간이동</b>하며 사라진다.
         */
        Vector3f orbit;
        /**
         * ★ <b>검기의 단계 아이템</b> (1→2→3 · 검기 평타 전용 · 그 밖은 null) — 중앙 티커가
         * {@code frameTicks} 마다 다음 것으로 갈아끼운다 ({@link #advanceFrames}). Transformation 은
         * <b>건드리지 않는다</b> (공전 보간·크기·수축이 그대로 산다 — 바뀌는 것은 무는 모델뿐이다).
         */
        List<ItemStack> frames;
        int frameTicks = 1;
        int frameIndex;
        long frameStart;
        // 투사 — 날아간다
        Vector dir;
        Location head;
        double flown;
        double range;
        boolean landed;
        // 개화 — 오의
        long burstAt = -1;
        double burstRadius;

        Piece(SkillEngine.DisplayMotion m, UUID owner) {
            this.m = m;
            this.owner = owner;
        }
    }

    /** 지속 형체 (날의 기 · 호신강기 고리) */
    private static final class Persist {
        final List<Display> parts = new ArrayList<>();
        final SkillEngine.DisplayMotion m;
        final String variant;   // 무엇으로 서 있는가 — 격이 바뀌면 다시 세운다
        long lastBeat;

        Persist(SkillEngine.DisplayMotion m, String variant) {
            this.m = m;
            this.variant = variant;
        }
    }

    /**
     * 기동 — 지난 생의 유령을 걷어낸다.
     *
     * <p>디스플레이는 엔티티다. 플러그인이 예고 없이 죽으면(크래시) 획이 공중에 <b>얼어붙는다</b>.
     * {@code setPersistent(false)} 가 저장은 막지만 그 세션의 청소는 못 한다 — 그래서 표식을 달고,
     * 다음 기동에 표식을 단 몸을 전부 지운다.
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

    /** 중앙 티커에서 매 틱 — 참격선 소멸 · 투사 전진 · 지속 형체 회수 (F-P2: 태스크는 하나다) */
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
            if (p.burstAt >= 0 && now >= p.burstAt) {
                bloom(p);
            }
            if (p.frames != null && !p.fading) {
                advanceFrames(p, now);          // ★ 검기 — 스윙에 맞춰 1→2→3 단계를 갈아끼운다
            }
            if (!p.fading && p.fadeAt >= 0 && now >= p.fadeAt) {
                erase(p);                       // 참격선 — 꼬리부터 지워진다
            } else if (!p.fading && p.fadeAt < 0 && p.m.fade() > 0 && now >= p.dieAt - p.m.fade()) {
                p.fading = true;
                for (Display d : p.parts) {
                    scale(d, 0.001f, p.m.fade());
                }
            }
            if (now >= p.dieAt) {
                despawn(p.parts);
                it.remove();
            }
        }
        int beat = engine.displayBudget().heartbeatTicks();
        persistent.values().forEach(slots -> slots.entrySet().removeIf(e -> {
            if (now - e.getValue().lastBeat <= beat) {
                return false;
            }
            despawn(e.getValue().parts);   // 심장이 멎었다 — 태세를 거뒀거나, 죽었거나, 세계를 떠났다
            return true;
        }));
        persistent.entrySet().removeIf(e -> e.getValue().isEmpty());
        // 팩 상태는 **접속이 끊긴 사람만** 잊는다 (1초마다). 죽음·월드 이동은 팩을 벗기지 않는다 —
        // 그것까지 잊으면 그 사람은 재접속 전까지 3D 를 못 본다 (조용한 강등). 여기가 유일한 망각처다.
        if (now % 20 == 0 && !packed.isEmpty()) {
            packed.removeIf(id -> plugin.getServer().getPlayer(id) == null);
        }
        if (log.size() > 400) {
            log.subList(0, log.size() - 400).clear();
        }
    }

    /**
     * 정리 (performance.yml effects.cleanup_on: death · quit · world_change).
     *
     * <p><b>형체를 거둔다. 팩 상태는 건드리지 않는다.</b> 예전엔 여기서 {@code packed.remove(body)} 를
     * 했다 — 그런데 이 부름은 <b>죽음</b>과 <b>월드 이동</b>에도 온다 (SkillListener). 그 사람은 여전히
     * 접속해 있고 팩도 여전히 켜져 있는데, {@link org.bukkit.event.player.PlayerResourcePackStatusEvent}
     * 는 <b>다시 오지 않는다</b>. 그래서 한 번 죽거나 세계를 건너간 사람은 <b>재접속 전까지 3D 를 영영
     * 못 봤다</b> — 아무 로그도 없이. 조용한 강등이다.
     *
     * <p>이제 팩 상태는 {@link #tick} 이 <b>접속이 끊긴 사람만</b> 걷어낸다 (아래). 죽음은 팩을 벗기지 않는다.
     */
    void clear(UUID body) {
        Map<String, Persist> slots = persistent.remove(body);
        if (slots != null) {
            slots.values().forEach(p -> despawn(p.parts));
        }
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
        pieces.forEach(p -> despawn(p.parts));
        pieces.clear();
        persistent.values().forEach(slots -> slots.values().forEach(p -> despawn(p.parts)));
        persistent.clear();
    }

    /** 팩 수락 여부 — SkillListener 의 PlayerResourcePackStatusEvent 가 먹인다 */
    void packStatus(UUID viewer, boolean accepted) {
        if (accepted) {
            packed.add(viewer);
        } else {
            packed.remove(viewer);
        }
    }

    // ══════════ ③ 참격선(斬擊線) — 검이 아니다. 지나간 자리다 ══════════

    /**
     * 한 획 — 히트박스를 훑고 사라진다.
     *
     * <p><b>만화의 검격</b>: 얇고 긴 호가 그려졌다가 <b>꼬리부터</b> 지워진다. 모델의 원점은 획의
     * <b>머리(끝점)</b> 에 있고 획은 거기서 −X 로 늘어지므로, {@code scale.x} 를 0→1 로 키우면
     * 칼끝 뒤로 꼬리가 자라고, 1→0 으로 줄이면 <b>꼬리가 먼저 지워진다</b>
     * (머리가 먼저 지워지면 거꾸로 보인다).
     *
     * <p><b>무공이 없어도 뜬다. 격이 없어도 뜬다</b>(외공기). 격은 그 획을 밝고 굵게 만들 뿐이다 —
     * 무공은 궤적을 <b>바꾸는 것</b>이지, 무공이 없다고 궤적이 <b>없는</b> 것이 아니다.
     *
     * @param swingTicks 자세가 돌아오는 틱 (프레임·계열 공속 중 긴 쪽) — 공속이 곧 리듬이다
     * @return 획이 실제로 떴는가 (떴으면 궤적 파티클은 물러선다 — display.blend)
     */
    boolean slash(Player caster, String hitType, String grade, String weaponClass,
                  double range, double angle, int swingTicks) {
        return slash(caster, hitType, grade, weaponClass, range, angle, swingTicks, null);
    }

    /**
     * @param strokeId ★ <b>이 획이 도는 각</b> ({@code display.swing_arcs.strokes}) — null 이면
     *                 옛 그림(안 도는 획). 도는 것은 <b>검</b>이지 사람의 카메라가 아니다
     */
    boolean slash(Player caster, String hitType, String grade, String weaponClass,
                  double range, double angle, int swingTicks, String strokeId) {
        SkillEngine.DisplayMotion m = engine.slashFor(hitType);
        SkillEngine.Swing sw = engine.swing(weaponClass);
        SkillEngine.Ink ink = engine.ink(grade);
        SkillEngine.DisplayBudget b = engine.displayBudget();
        if (m == null || sw == null || ink == null) {
            return false;   // 투사체(시)·태세·활·무관·짐승 — 그을 획이 없다
        }
        SkillEngine.DisplayModel model = engine.displayModel(m.model());
        if (model == null) {
            return false;
        }

        Vector flat = flat(caster);
        boolean ring = "원".equals(hitType) || "진".equals(hitType);
        boolean back = "돌".equals(hitType);   // 보법의 잔영 — 지나온 자리에 남는다 (뒤로 그린다)

        // 【불변식 ㅂ】 길이는 판정이 준 사거리에서 나온다 (reach ≤ 1.0 — 등록부는 줄일 수만 있다)
        double length = range * sw.reach();
        // ★ 자리는 **등록부**가 준다 (display.stroke_origin) — 팔이 지나가는 자리에 세운다
        Location at = origin(caster, m, length, flat);
        at.setDirection(back ? flat.clone().multiply(-1) : flat);
        if (!ring) {
            at.setPitch(at.getPitch() + sw.tilt());   // MC 규약: pitch 양수 = 아래 (부는 내려찍는다)
        }

        // ★★ 스윙 — 시작 각과 끝 각. 클라이언트가 그 사이를 slerp 한다 = **획이 호를 그리며 쓴다**
        SkillEngine.SwingArc arc = ring ? null : arcOf(strokeId, angle);
        SkillEngine.SwingTuning tune = tuning();
        Quaternionf qStart = arc == null ? pose(m, 0.0f)
                : pose(m, 0.0f).mul(arc.quat(0, tune.arc()));
        Quaternionf qEnd = arc == null ? pose(m, 0.0f)
                : pose(m, 0.0f).mul(arc.quat(1, tune.arc()));

        Piece p = spawn(m, at, caster, 1, false, caster.getUniqueId(), qStart);
        if (p == null) {
            return false;   // 강등 — 파티클이 그 자리를 지킨다 (강등이지 실종이 아니다)
        }
        p.rot = qEnd;
        p.rise = arc == null ? 0.0f : (float) (arc.rise()[1] * tune.rise());
        // 굵기 = 격의 먹(ink) × 계열의 손 — 속도가 곧 두께다 (단검 가늘게 · 부 굵게)
        float thick = ink.thickness() * sw.thickness();
        float[] size = model.size();
        float lenScale = (float) (length / Math.max(0.01f, size[0]));
        p.full = ring
                ? new float[]{lenScale * 2.0f, thick, lenScale * 2.0f}   // 고리의 지름 = 사거리 × 2
                : new float[]{lenScale, thick, thick};

        int draw = (int) clamp(Math.round(swingTicks * sw.spanRatio()),
                b.slashMinTicks(), b.slashMaxTicks());
        p.fadeAt = tick + draw + ink.hold();          // 격이 무거울수록 획이 공중에 오래 머문다
        p.dieAt = p.fadeAt + m.fade();
        for (Display d : p.parts) {
            d.setBrightness(new Display.Brightness(ink.blockLight(), ink.skyLight()));
            // 그린다 — 칼끝 뒤로 꼬리가 자라고, **동시에 획이 호를 그리며 쓸고 지나간다**
            transform(d, m, p.full, qEnd, p.rise, draw);
        }
        note("참격선", hitType + "·" + grade + "·" + weaponClass
                + (arc == null ? "" : "·" + arc.id()
                        + String.format(" 호각%.0f도", arc.arcDeg(tune.arc())))
                + " 길이" + String.format("%.1f", length) + "m 굵기" + String.format("%.2f", thick)
                + " 그리기" + draw + "틱");
        return true;
    }

    /**
     * <b>이 획이 도는 각</b> — 등록부가 준다. 그리고 <b>못</b>: yaw 는 히트박스 부채꼴 밖으로 못 나간다.
     *
     * <p><b>【불변식 ㅂ】</b> "획은 히트박스를 벗어나지 못한다." pitch·roll 은 <b>자유다</b> —
     * 호(弧) 히트박스는 {@code arcTargets} 가 <b>수평각만</b> 재고 높이를 안 본다. 그래서 위아래로 크게
     * 휘둘러도 그림이 판정에 대해 거짓말하지 않는다. 그러나 <b>yaw 는 그 부채꼴 자체다</b> — 등록부가
     * 밖을 청구하면 <b>깎고, 소리내어 짖는다</b> (조용히 깎는 것은 거짓말이다).
     */
    private SkillEngine.SwingArc arcOf(String strokeId, double angle) {
        SkillEngine.SwingArcs arcs = engine.swingArcs();
        SkillEngine.SwingArc a = arcs.stroke(strokeId);
        if (a == null || !arcs.enabled()) {
            return null;
        }
        double half = Math.max(1.0, angle / 2.0);
        double[] yaw = {clamp(a.yaw()[0], -half, half), clamp(a.yaw()[1], -half, half)};
        if ((Math.abs(yaw[0] - a.yaw()[0]) > 0.01 || Math.abs(yaw[1] - a.yaw()[1]) > 0.01)
                && barked.add("호밖|" + strokeId + "|" + (int) angle)) {
            plugin.getLogger().warning(String.format(
                    "[획·위반] 스윙 '%s' 의 yaw [%.0f, %.0f] 가 히트박스 부채꼴 ±%.0f도 밖이다 —"
                            + " 깎아서 그린다 (불변식 ㅂ: 획은 히트박스를 벗어나지 못한다)."
                            + " config/skill_motion.yml display.swing_arcs.strokes.%s",
                    strokeId, a.yaw()[0], a.yaw()[1], half, strokeId));
        }
        double maxArc = arcs.maxArcDeg();
        double deg = a.arcDeg(tuning().arc());
        if (deg > maxArc && barked.add("호과다|" + strokeId)) {
            plugin.getLogger().warning(String.format(
                    "[획·위반] 스윙 '%s' 의 호각 %.0f도 > 상한 %.0f도 — 팔이 아니라 프로펠러다"
                            + " (display.swing_arcs.limits.max_arc_deg)", strokeId, deg, maxArc));
        }
        return new SkillEngine.SwingArc(a.id(), yaw, a.pitch(), a.roll(), a.rise(), a.fan(), a.bow());
    }

    /** 지워진다 — <b>꼬리부터</b>. 획이 살짝 퍼지며(spread) 사라진다 (연기처럼) */
    private void erase(Piece p) {
        p.fading = true;
        float s = p.m.spread();
        if (p.orbit != null) {
            // 검기 평타 — 공전이 끝난 그 자리에서 오므라든다 (자리를 되돌리면 초승달이 몸으로 튄다).
            // 세 축을 같이 줄인다: 공전 초승달은 머리 고정을 쓰지 않으므로 x 만 줄이면 세로 조각이 남는다
            for (Display d : p.parts) {
                kigiTransform(d, new float[]{p.full[0] * s * 0.02f, p.full[1] * s * 0.02f,
                        p.full[2] * s * 0.02f}, p.rot == null ? pose(p.m, 0.0f) : p.rot,
                        p.orbit, p.m.fade());
            }
            return;
        }
        for (Display d : p.parts) {
            // scale.x → 0 : 머리(원점)로 수축한다 = 꼬리가 먼저 사라진다.
            // 각은 **스윙이 끝난 그 각 그대로다** — 여기서 되돌리면 다 벤 검이 도로 튄다
            transform(d, p.m, new float[]{0.001f, p.full[1] * s, p.full[2] * s},
                    p.rot == null ? pose(p.m, 0.0f) : p.rot, p.rise, p.m.fade());
        }
    }

    // ══════════ ★ 전용 검기(劍氣) 평타 — 크고 선명한 초록 초승달 ══════════

    /**
     * 전용 검기 평타 — <b>몸 주위를 공전(公轉)</b>하는 초록 초승달. 기존 무협 참격(작은 획)의 대체.
     *
     * <p><b>【2026-07-19 — 초승달이 앞에 붙박여 자전만 하던 병】</b> 예전 그림은 디스플레이를
     * {@code 발 + 앞×forward} 에 <b>놓고</b> 각만 {@code qStart→qEnd} 로 보간했다. 그래서 초승달은
     * <b>정면 한 자리에서 시계바늘처럼 돌기만</b> 했다 — 사용자의 말 그대로 "정면에서 본 반원".
     * 레퍼런스(마크에이지)의 검기는 <b>크레센트의 자리 자체가 몸 둘레를 돈다</b>: 오른쪽 아래에서
     * 몸을 감으며 왼쪽 위로. 자전이 아니라 <b>공전</b>이다.
     *
     * <p><b>그래서 자리와 각을 갈랐다</b>: 엔티티는 <b>몸의 중심</b>(발 + {@code center_height},
     * {@code forward} 만큼만 앞)에 <b>못 박고</b>, 초승달은 Transformation 의 <b>translation</b> 으로
     * 그 중심에서 {@code orbit_radius} 만큼 떨어져 <b>돈다</b>:
     * <pre>
     *   θ      = toRadians(sweep_deg × 0.5) × phase × dirSign      (phase −1 → +1)
     *   국소축   앞 = +Z · 위 = +Y   (엔티티가 시선을 보고 서 있다)
     *   t(θ)   = Rz(tilt_deg) · ( r·sinθ , 0 , r·cosθ )            ← 공전 자리
     *   R(θ)   = Rz(tilt_deg) · Ry(θ) · Rz(roll_deg)               ← 궤도 접선을 향한 각
     * </pre>
     * {@code Ry(θ)} 가 모델의 길이축(+X)을 궤도의 <b>접선</b>으로 돌려 놓는다 (초승달이 궤도를 따라
     * 눕고, 그 면은 바깥을 본다). {@code Rz(tilt)} 가 <b>수평 공전면을 앞축 둘레로 눕혀</b> 대각
     * 내려베기를 만든다 — 자리와 각에 <b>같은 기울기</b>를 먹이므로 초승달은 끝까지 궤도에 붙어 있다.
     *
     * <p><b>둘 다 phase −1/+1 로 재서</b> 시작값으로 띄우고({@code spawn}) 끝값을 준다
     * ({@link #kigiTransform}) — 클라이언트가 <b>translation 은 lerp · rotation 은 slerp</b> 하므로
     * 초승달이 몸 둘레를 <b>돌며</b> 휩쓴다. 그린 뒤 {@code fade_ticks} 동안 <b>그 자리에서</b>
     * 오므라든다 ({@link #erase} — 자리를 되돌리면 다 벤 검기가 몸으로 도로 튄다).
     *
     * <p>공전 translation 은 {@link #transform} 의 {@code headAnchor} 가 덮어쓰므로 <b>검기 전용
     * 경로</b>({@link #kigiTransform})로 간다. 다른 참격(참격_호·선·원)은 옛 길 그대로다.
     *
     * <p><b>모든 값은 등록부(kigi_slash)가 준다</b> — 자리·반경·호각·기울기·크기·시간·발광. 코드는
     * 옮길 뿐이다. 흰 별 파티클은 발행 층({@link SkillListener#spawnKigiSlash})이 뿌린다 (불변).
     *
     * @param dirSign 스윙 방향 (+1 / −1) — alternate 면 발행 층이 스윙마다 토글한다.
     *                공전 방향(어느 쪽으로 감아 도는가)이 뒤집힌다
     * @return 검기가 실제로 떴는가 (팩 없는 눈뿐이면 못 뜬다 — fallback null · 파티클이 지킨다)
     */
    boolean kigiSlash(Player caster, SkillEngine.KigiSlash cfg, int dirSign) {
        if (cfg == null || !cfg.enabled() || caster.getWorld() == null) {
            return false;
        }
        // ★ 자리 = **공전의 중심**. 발 + center_height, 시선(수평)으로 forward 만큼만 앞.
        //   초승달은 여기에 있지 않다 — 여기를 **중심으로 돈다** (translation 이 반경을 준다)
        Vector flat = flat(caster);
        Location feet = caster.getLocation();
        Location at = feet.clone().add(flat.clone().multiply(cfg.forward()));
        at.setY(feet.getY() + cfg.centerHeight());
        at.setDirection(flat);   // 국소축을 시선에 맞춘다 — 공전은 시전자의 축에서 돈다

        // ★ 매체 축 (재설계 v2 · 2026-07-21 사용자 확정) — 일반 무기는 particle.
        //   plate·model3d 경로는 지우지 않는다: 보스몹·특수 무기가 그 효과만 매체를 갈아탈 자리다.
        if ("particle".equals(cfg.medium())) {
            kigiGeomBand(caster, cfg, at, dirSign);
            return true;
        }

        // ★ **3D 리본을 먼저 시도한다** (D · 2026-07-21). 서면 판·점을 **둘 다** 건너뛴다 —
        //   리본 하나가 A(형태)와 B(각도)를 겸하므로 겹쳐 세우면 덤불이 된다 (A·B 실측의 교훈).
        //   BetterModel 이 없거나 모델을 못 찾으면 false — 그때 아래 판·점이 그대로 메운다 (가역).
        if ("model3d".equals(cfg.medium()) && cfg.model3d() != null && !cfg.model3d().isBlank()) {
            // ★ 팩을 든 눈에게만 리본을 보낸다 — 3D 모델은 팩 없이는 못 본다.
            //   팩 없는 사람은 이 갈래 밖에서 판(바닐라 아이템)이 지킨다.
            List<Player> whom = new ArrayList<>();
            List<Player> noPack = new ArrayList<>();
            audience(at, whom, noPack);
            // 리본은 bbmodel 에서 이미 세로면(XY)에 서 있다 — pitch 로 안 세운다.
            //   tilt_deg 는 그 위에 얹는 대각(내려베기) 기울기다.
            Location ribbonAt = at.clone().add(0.0, cfg.model3dUp(), 0.0);
            boolean stood = plugin.modelBridge().spawnArc(ribbonAt, cfg.model3d(), cfg.model3dAnim(),
                    (float) (cfg.tiltDeg() + cfg.model3dPitch()),
                    caster.getLocation().getYaw() + (float) cfg.model3dYaw(),
                    cfg.drawTicks() + cfg.fadeTicks(), whom);
            if (stood) {
                note("검기리본", "3D 리본 " + cfg.model3d() + " (기울기 " + Math.round(cfg.tiltDeg())
                        + "도 · " + (cfg.drawTicks() + cfg.fadeTicks()) + "틱)");
                return true;
            }
            // 못 섰으면 조용히 판·점으로 — 다만 「왜」를 남긴다 (0 을 침묵으로 넘기지 않는다)
            note("검기리본", "리본을 못 세웠다 — 판·점으로 물러선다 (" + cfg.model3d() + ")");
        }

        // ★ **점을 판보다 먼저, 판과 무관하게 긋는다** (2026-07-20).
        //   왜: 예전엔 이 호출이 판을 세운 뒤에 있었다. 그래서 모델이 없거나 판을 끄면
        //   **점까지 같이 꺼졌다** — 두 층을 갈라서 재려는데 갈라지지가 않았다.
        //   층을 견주려면 층이 **서로 독립**이어야 한다.
        kigiGeomArc(caster, cfg, at);

        if (!cfg.plate()) {
            return true;   // 점만 쓰는 판 — 판(ItemDisplay)은 세우지 않는다 (층 비교용 · 가역)
        }
        // ★ 소환 모델 = 단계 목록의 첫 장 (2026-07-21 · 검토 P0). 예전엔 cfg.model 키로 소환했는데,
        //   frame_models 만 바꾸고 model 을 안 바꾸면 **첫 단계가 조용히 건너뛰어졌다**
        //   (advanceFrames 는 idx 0 을 다시 안 끼운다 — 소환한 그것이 1단계라는 계약).
        //   이제 계약을 구조로 강제한다: 단계 목록이 있으면 그 [0] 이 곧 소환 모델이다.
        List<String> frameNames = kigiFrameNames(cfg, dirSign);
        String modelName = frameNames.isEmpty()
                ? engine.displayModelNameByKey(cfg.model()) : frameNames.get(0);
        if (modelName == null) {
            return false;   // 이 키의 모델이 등록부에 없다 — 조용히 물러선다 (파티클이 지킨다)
        }
        SkillEngine.DisplayModel model = engine.displayModel(modelName);
        if (model == null) {
            if (barked.add("검기소환모델없음|" + modelName)) {
                plugin.getLogger().warning("[혼천] kigi_slash 단계 '" + modelName
                        + "' 가 display.models 에 없다 — 검기를 못 세운다 (config/skill_motion.yml)");
            }
            return false;
        }
        if (!frameNames.isEmpty() && cfg.model() != null && !cfg.model().equals(model.key())
                && barked.add("검기모델표류|" + cfg.model())) {
            plugin.getLogger().warning("[혼천] kigi_slash.model(" + cfg.model() + ") ≠ frame_models[0]("
                    + model.key() + ") — 단계 목록이 이긴다. config 의 model 을 맞춰라");
        }
        SkillEngine.DisplayMotion m = kigiMotion(cfg, modelName);

        // ★ 시전자당 평타 판은 **한 장** (검토: 수명 8틱 vs 공격 간격 → 상시 점등·누적 방지).
        //   새 스윙이 시작되면 이전 판을 즉시 거둔다 — 연타 사이의 시각적 공백이 「빡」을 지킨다.
        Piece prev = kigiByCaster.remove(caster.getUniqueId());
        if (prev != null) {
            for (Display d : prev.parts) {
                if (d.isValid()) {
                    d.remove();
                }
            }
            prev.dieAt = tick;   // 중앙 티커가 다음 순회에서 목록을 정리한다
        }

        // 공전 — 자리와 각을 **같은 phase(−1 → +1)** 로 잰다. 클라이언트가 둘 다 보간한다
        Quaternionf qStart = kigiPose(cfg, dirSign, -1.0);
        Quaternionf qEnd = kigiPose(cfg, dirSign, +1.0);
        Vector3f tStart = kigiOrbit(cfg, dirSign, -1.0);
        Vector3f tEnd = kigiOrbit(cfg, dirSign, +1.0);

        Piece p = spawn(m, at, caster, 1, false, caster.getUniqueId(), qStart, tStart);
        if (p == null) {
            return false;   // 볼 눈이 없거나 예산 초과 — 파티클(흰 별)이 그 자리를 지킨다
        }
        kigiByCaster.put(caster.getUniqueId(), p);
        p.rot = qEnd;
        p.rise = 0.0f;
        p.orbit = tEnd;   // 지울 때도 이 자리다 (되돌리면 초승달이 몸 한복판으로 튄다)
        // 배율 — 모델 size[0](정규화 상수)로 나눠 초승달을 scale(m) 만큼 크게 (셋을 같이 키워 형태 유지)
        float f = (float) (cfg.scale() / Math.max(0.01f, model.size()[0]));
        p.full = new float[]{f, f, f};
        p.fadeAt = tick + cfg.drawTicks();          // 그리기가 끝나면 수축 시작
        p.dieAt = p.fadeAt + cfg.fadeTicks();
        // ★ 단계 — 스윙 중 갈아끼울 아이템을 미리 굽는다 (교체는 중앙 티커가 한다)
        List<ItemStack> frames = kigiFrames(frameNames);
        if (frames.size() > 1) {
            p.frames = frames;
            p.frameTicks = Math.max(1, cfg.frameTicks());
            p.frameIndex = 0;                       // 소환한 그것이 1단계다 (cfg.model() = frame_models[0])
            p.frameStart = tick;
        }
        for (Display d : p.parts) {
            d.setBrightness(new Display.Brightness(cfg.brightness(), cfg.brightness()));
            // 씨앗에서 자라며 **몸 둘레를 돈다** (검기 전용 경로 — headAnchor 가 공전을 덮지 못하게)
            // ★ 버스트 문법 (2026-07-21 · 검토 P0): 평타 피크는 **시작부터 최종 크기**여야 한다.
            //   예전엔 0.001 → full 을 draw_ticks 내내 보간해 피크가 점, 붕괴가 최대가 됐다.
            //   ① 스냅(보간 0) — 태어날 때 이미 완전 크기·시작 각. 스윕 모드에서도 크기는 안 자란다.
            kigiSnap(d, p.full, qStart, tStart);
            //   ② 스윕 — 각·자리만 보간 (sweep=0 이면 시작=끝이라 정지 · 1틱로 마감)
            kigiTransform(d, p.full, qEnd, tEnd, cfg.sweepDeg() == 0.0 ? 1 : cfg.drawTicks());
        }
        note("검기평타", String.format("scale%.1f 공전 반경%.2fm 호각%.0f도 기울기%.0f도 dir%s 그리기%d틱",
                cfg.scale(), cfg.orbitRadius(), cfg.sweepDeg(), cfg.tiltDeg(),
                dirSign < 0 ? "−" : "+", cfg.drawTicks()));
        return true;
    }

    /**
     * ★ <b>몸 둘레를 도는 호</b> — 판이 못 푸는 각도를 점으로 메운다 (EffectLib 기하).
     *
     * <p><b>왜 판만으로 안 되는가 (실측).</b> 납작한 판은 각도에 따라 옆면이 되어 사라진다.
     * 그런데 파티클로 바꾸기만 해서는 <b>등 뒤가 안 풀린다</b> — MagicSpells 로 옮겨 재 봤을 때
     * 뒤 <b>45px</b> 로 우리 판(47px)과 사실상 같았다. 까닭은 원시도형이 아니라 <b>배치</b>다:
     * 호를 몸 <b>앞</b>에 두면 등 뒤에서는 몸이 가린다.
     * ⇒ 그래서 여기서는 {@code orbit_radius} 만큼 <b>몸 둘레로</b> 돌린다.
     *
     * <p>파티클은 {@link QiGeometry} 가 <b>우리 {@link SkillHud#emit} 으로만</b> 흘린다 —
     * 예산·관람자·LOD 게이트가 그대로 걸린다. 남의 기하를 빌리되 <b>발행권은 넘기지 않는다.</b>
     */
    /**
     * ★ <b>파티클 띠 검기</b> — 재설계 v2 의 본체 (docs/design/kigi_particle_v2.md).
     *
     * <p>스윕 {@code band_sweep_ticks} 틱 동안 검끝을 따라 <b>지나간 구간만</b> 새로 긋는다 —
     * 판 세대에서 불가능했던 「스윙과의 인과」가 여기서는 매체 특성으로 생긴다.
     * 잔류·소멸은 dust 파티클 자체 수명이 맡는다 (야마토 실측: 긋고 → 먼지로 남는다).
     * 흰 별(end_rod)은 머리 구간에만 성기게 — 액센트는 몸이 아니라 진행 끝에 산다.
     */
    private void kigiGeomBand(Player caster, SkillEngine.KigiSlash cfg, Location at, int dirSign) {
        QiGeometry geom = plugin.qiGeometry();
        if (geom == null) {
            note("검기띠", "기하가 없다 (EffectLib 미적재) — 검기가 안 나간다");
            return;
        }
        String particle = cfg.geomParticle() == null || cfg.geomParticle().isBlank()
                ? "dust" : cfg.geomParticle();
        String inkBody = cfg.geomInk() == null || cfg.geomInk().isBlank() ? "청회" : cfg.geomInk();
        final int span = Math.max(1, cfg.bandSweepTicks());
        final float yaw = caster.getLocation().getYaw();
        final Location center = at.clone();
        final int[] sent = {0};
        final Location eye = caster.getEyeLocation();
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                // ★ v5 수명 주기 (디자이너 시트): 스윕(피크 ~100ms) → +2·+4틱 번짐 패스 → 파티클 소멸
                if (t >= span + 5 || !caster.isOnline()) {
                    cancel();
                    note("검기띠", "점 " + sent[0] + "개 (폭 " + cfg.bandWidth() + "m · "
                            + cfg.bandRows() + "줄 · 각 " + Math.round(cfg.geomSweepDeg())
                            + "도 · " + span + "틱 스윕 + 번짐 2회)");
                    return;
                }
                java.util.UUID me = caster.getUniqueId();
                if (t < span) {
                    // 본 스윕 — 시전자: 얇게 + 크로스헤어 비움 / 관전자: 넓게 + 외곽 먹선
                    sent[0] += geom.slashBand(center, yaw, cfg.orbitRadius(), cfg.geomSweepDeg(),
                            (double) t / span, (double) (t + 1) / span,
                            cfg.tiltDeg(), cfg.geomStepDeg(),
                            cfg.bandWidth() * cfg.widthSelfMul(), cfg.bandRows(), cfg.bandJitter(),
                            particle, inkBody, dirSign, v -> v.getUniqueId().equals(me),
                            1.0, eye, false, false);
                    sent[0] += geom.slashBand(center, yaw, cfg.orbitRadius(), cfg.geomSweepDeg(),
                            (double) t / span, (double) (t + 1) / span,
                            cfg.tiltDeg(), cfg.geomStepDeg(),
                            cfg.bandWidth() * cfg.widthOthersMul(), cfg.bandRows(), cfg.bandJitter(),
                            particle, inkBody, dirSign, v -> !v.getUniqueId().equals(me),
                            cfg.heightOthersMul(), null, true, false);
                    if (t == span - 1 && cfg.accentCount() > 0) {
                        sent[0] += geom.slashArc(center, yaw, cfg.orbitRadius(), cfg.geomSweepDeg(),
                                0.6, 1.0, cfg.tiltDeg(), Math.max(6.0, cfg.geomStepDeg() * 4.0),
                                "end_rod", null);
                    }
                } else if (t == span + 1 || t == span + 3) {
                    // 번짐 — 밝은 날 없이 먹·본색이 뒤로 성기게 흩어진다 (100→350ms 구간)
                    sent[0] += geom.slashBand(center, yaw, cfg.orbitRadius(), cfg.geomSweepDeg(),
                            0.0, 1.0, cfg.tiltDeg(), cfg.geomStepDeg() * 2.0,
                            cfg.bandWidth(), cfg.bandRows(), cfg.bandJitter(),
                            particle, inkBody, dirSign, null,
                            1.4, null, false, true);
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void kigiGeomArc(Player caster, SkillEngine.KigiSlash cfg, Location at) {
        QiGeometry geom = plugin.qiGeometry();
        if (geom == null || cfg.geomParticle() == null || cfg.geomParticle().isBlank()) {
            // ★ **안 그린 것도 기록한다.** 「호가 0을 냈다」와 「호를 아예 안 불렀다」는 다른 사건인데,
            //   조용히 돌아서면 진단에서 둘이 구별되지 않는다 (실측으로 한 판 날린 자리다).
            note("검기호", geom == null ? "기하가 없다 (EffectLib 미적재)" : "등록부가 안 켰다 (geom_particle 비어 있음)");
            return;
        }
        // ★ **지나간 자리만 새로 긋는다** — 매 틱 호 전체를 다시 뿌리던 판을 버렸다.
        //   왜: 전체를 다시 뿌리면 예산에 막혀 오히려 **성겨지고**, 사용자 평가대로
        //   「칼자국이 아니라 흩어진 티끌」로 읽혔다 (실측 2026-07-20).
        //   파티클은 제 수명 동안 남으므로, 새 구간만 촘촘히 그으면 자국이 **쌓여 궤적**이 된다.
        final int span = Math.max(1, cfg.drawTicks());
        final float yaw = caster.getLocation().getYaw();
        final Location center = at.clone();
        final int[] sent = {0};
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= span || !caster.isOnline()) {
                    cancel();
                    note("검기호", "점 " + sent[0] + "개 (반경 " + String.format("%.2f", cfg.orbitRadius())
                            + "m · 각 " + Math.round(cfg.geomSweepDeg()) + "도 · 간격 "
                            + String.format("%.1f", cfg.geomStepDeg()) + "도 · " + span + "틱)");
                    return;
                }
                sent[0] += geom.slashArc(center, yaw, cfg.orbitRadius(), cfg.geomSweepDeg(),
                        (double) t / span, (double) (t + 1) / span,
                        cfg.tiltDeg(), cfg.geomStepDeg(),
                        cfg.geomParticle(), cfg.geomInk());
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * ★ <b>검기의 단계 아이템</b> — {@code frame_models} 가 적은 순서대로 구운다 (1단계 … 3단계).
     *
     * <p><b>【왜 코드가 갈아끼우는가 — .mcmeta 애니는 스윙을 모른다】</b> 옛 판은 한 텍스처를
     * 3프레임 세로 스트립 + {@code .mcmeta}(frametime 3) 로 구웠다. 그런데 마인크래프트의 텍스처
     * 애니는 <b>전역 시계</b>로 돈다 — 프레임 번호는 월드 틱에서 나오고 모든 인스턴스가 동시에 같은
     * 것을 본다. 검기의 수명은 {@code draw(9) + fade(5) = 14} 틱인데 애니 한 바퀴는 9틱이라
     * <b>소환 순간의 프레임이 매번 제각각</b>이었다 (어떤 스윙은 3단계부터 시작했다).
     * 사용자 실측: "검기 프레임이 전혀 반영이 안 된다".
     *
     * <p>⇒ 단계를 <b>텍스처가 아니라 모델</b>로 가르고, 갈아끼우는 일을 코드가 한다. 그러면 단계는
     * 디스플레이의 <b>제 나이</b>({@code now − frameStart})에 매이므로 <b>스윙마다 반드시 1→2→3</b> 이다.
     *
     * <p><b>팩을 든 눈만 갈아끼운다</b> — 폴백 아이템에는 {@code item_model} 이 없다 (모델이 아니라
     * 바닐라 아이템이므로 갈아끼울 단계 자체가 없다). 검기 모델은 {@code fallback: null} 이라
     * 실제로 폴백 조각이 생기지도 않지만, 규약은 규약대로 지킨다.
     *
     * @return 구워진 단계 아이템 (2개 미만이면 부르는 쪽이 교체를 끈다)
     */
    /**
     * ★ A/B 방향 세트 선택 — dirSign(alternate 토글)이 음이고 B 세트가 있으면 B(올려베기)를 쓴다.
     * 바닐라 스윙 애니메이션에는 올려/내려 구분이 없으므로, 의미 방향이 생기기 전까지는
     * 스윙 교대(alternate)가 A/B 를 번갈아 끼우는 것이 시각 변화의 정직한 최소 구현이다
     * (외부 검토는 의미 방향 명시를 권고 — 자세(posture) 시스템이 방향을 갖게 되면 그리로 옮긴다).
     */
    private List<String> kigiFrameNames(SkillEngine.KigiSlash cfg, int dirSign) {
        if (dirSign < 0 && !cfg.frameModelsB().isEmpty()) {
            return cfg.frameModelsB();
        }
        return cfg.frameModels();
    }

    private List<ItemStack> kigiFrames(List<String> frameNames) {
        List<ItemStack> out = new ArrayList<>(3);
        for (String name : frameNames) {
            SkillEngine.DisplayModel fm = engine.displayModel(name);
            if (fm == null) {
                if (barked.add("검기단계없음|" + name)) {
                    plugin.getLogger().warning("[혼천] kigi_slash.frame_models 의 '" + name
                            + "' 가 display.models 에 없다 — 그 단계를 건너뛴다"
                            + " (config/skill_motion.yml)");
                }
                continue;
            }
            ItemStack s = item(fm, true, null);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * ★ <b>단계를 갈아끼운다</b> — {@code frame_ticks} 마다 다음 모델로. 중앙 티커가 매 틱 부른다.
     *
     * <p>바꾸는 것은 <b>ItemDisplay 가 든 아이템뿐</b>이다. {@link Transformation} 은 한 글자도
     * 건드리지 않으므로 클라이언트가 돌리고 있던 공전 보간(translation lerp · rotation slerp)·크기가
     * <b>끊기지 않는다</b>. 수축({@link #erase})이 시작되면 부르는 쪽이 이 길로 안 온다 —
     * 다 벤 검기는 3단계 그대로 오므라든다.
     */
    private void advanceFrames(Piece p, long now) {
        int last = p.frames.size() - 1;
        long aged = Math.max(0L, now - p.frameStart) / p.frameTicks;
        int idx = (int) Math.min(last, aged);
        if (idx <= p.frameIndex) {
            return;
        }
        p.frameIndex = idx;
        ItemStack next = p.frames.get(idx);
        for (Display d : p.parts) {
            // 팩을 든 조각만 — 폴백 조각(item_model 없음)은 갈아끼울 단계가 없다
            if (d instanceof ItemDisplay id && id.isValid() && itemModelOf(id.getItemStack()) != null) {
                id.setItemStack(next.clone());
            }
        }
    }

    /**
     * <b>공전각</b> θ — phase(−1 → +1) 을 {@code sweep_deg} 의 절반씩 좌우로 편다.
     * {@code dirSign} 이 감아 도는 방향을 뒤집는다 (alternate: 한 번은 오른→왼, 다음은 왼→오른).
     */
    private static double kigiTheta(SkillEngine.KigiSlash cfg, int dirSign, double phase) {
        return Math.toRadians(cfg.sweepDeg() * 0.5) * phase * (dirSign < 0 ? -1.0 : 1.0);
    }

    /**
     * <b>공전 자리</b> — 몸 중심에서 반경 {@code orbit_radius} 만큼 떨어진 점을 θ 만큼 돌린 것.
     *
     * <p>국소축은 시전자의 축이다 (엔티티가 시선을 보고 선다): <b>앞 = +Z · 위 = +Y</b>. 그러므로
     * {@code (r·sinθ, 0, r·cosθ)} 는 <b>수평면 공전</b>이다 — θ=0 이면 정면 r 미터, θ 가 커지면
     * 옆으로 감아 돈다. 거기에 {@code Rz(tilt_deg)} 를 먹여 <b>공전면 자체를 앞축 둘레로 눕힌다</b>:
     * 한쪽 끝은 올라가고 반대 끝은 내려간다 = <b>대각으로 내려베는 궤도</b>.
     * ({@code tilt 0} 이면 순수 수평 공전 — 조율자가 두 극단 사이를 잡는다.)
     */
    private static Vector3f kigiOrbit(SkillEngine.KigiSlash cfg, int dirSign, double phase) {
        double theta = kigiTheta(cfg, dirSign, phase);
        float r = (float) cfg.orbitRadius();
        return new Vector3f((float) (r * Math.sin(theta)), 0.0f, (float) (r * Math.cos(theta)))
                .rotateZ((float) Math.toRadians(cfg.tiltDeg()));   // 공전면을 눕힌다 (자리도 함께)
    }

    /**
     * <b>공전 자리에서의 각</b> — 초승달이 <b>궤도의 접선</b>을 향해 눕는다.
     *
     * <p>모델의 길이축은 +X 다 ({@code models.<키>.size[0]} 이 그 길이다). 궤도
     * {@code (r·sinθ, 0, r·cosθ)} 의 접선은 {@code (cosθ, 0, −sinθ)} 이고, {@code Ry(θ)} 가
     * 정확히 +X 를 그리로 돌린다 — 그래서 초승달은 <b>궤도를 따라 눕고</b> 그 면은 몸 바깥을 본다
     * (예전처럼 시전자 정면을 향해 붙박인 평면이 아니다). {@code Rz(tilt_deg)} 는 자리에 먹인 것과
     * <b>같은 기울기</b>라 초승달이 기울어진 궤도에 끝까지 붙어 있고, {@code Rz(roll_deg)} 는
     * 초승달을 제 평면 안에서 돌리는 정적 오프셋이다 (배가 위로/아래로).
     */
    private static Quaternionf kigiPose(SkillEngine.KigiSlash cfg, int dirSign, double phase) {
        return new Quaternionf()
                .rotateZ((float) Math.toRadians(cfg.tiltDeg()))     // 공전면 기울기 (자리와 같은 것)
                .rotateY((float) kigiTheta(cfg, dirSign, phase))    // 궤도 접선으로 눕힌다
                // ★ 날의 눕힘 (2026-07-20 · 사용자 실측: "아치가 위아래를 보고 있다").
                //   여기까지는 판이 **세로로 선 채** 접선만 따라갔다 — 그래서 ∩ 의 배가 하늘을 봤다.
                //   횡베기는 판이 **누워야** 한다: 볼록한 바깥이 정면(플레이어 밖)을,
                //   오목한 안쪽이 플레이어를 향하도록. 접선축(모델 X)을 중심으로 90도 눕히면 그 자세다.
                .rotateX((float) Math.toRadians(cfg.bladePitchDeg()))
                .rotateZ((float) Math.toRadians(cfg.rollDeg()));    // 정적 롤 오프셋
    }

    /**
     * ★ <b>검기 전용 변환</b> — translation 을 <b>공전 오프셋</b>으로 준다.
     *
     * <p>공유 {@link #transform} 은 translation 을 {@code headAnchor}(모델의 머리 고정)로 <b>덮어쓴다</b>.
     * 그 길로 가면 공전이 매 틱 지워져 초승달이 도로 몸 한복판에 붙는다. 그래서 검기만 이 길로 간다 —
     * <b>공유 메서드는 한 글자도 건드리지 않는다</b> (다른 참격은 옛 그림 그대로다).
     */
    private void kigiTransform(Display d, float[] scale, Quaternionf rot, Vector3f orbit, int ticks) {
        if (!d.isValid()) {
            return;
        }
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(Math.max(1, ticks));
        d.setTransformation(new Transformation(new Vector3f(orbit), new Quaternionf(rot),
                new Vector3f(scale[0], scale[1], scale[2]), new Quaternionf()));
    }

    /** 스냅 — 보간 없이 즉시 적용 (검기 피크: 소환 스케일 0.001 을 한 번에 완전 크기로 덮는다) */
    private void kigiSnap(Display d, float[] scale, Quaternionf rot, Vector3f orbit) {
        if (!d.isValid()) {
            return;
        }
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(0);
        d.setTransformation(new Transformation(new Vector3f(orbit), new Quaternionf(rot),
                new Vector3f(scale[0], scale[1], scale[2]), new Quaternionf()));
    }

    /** 검기 모델을 실은 임시 모션 — kind 참격(그렸다 꼬리부터 지워진다)·등록부 값(billboard·fade·brightness) */
    private SkillEngine.DisplayMotion kigiMotion(SkillEngine.KigiSlash cfg, String modelName) {
        int lifetime = Math.min(cfg.drawTicks() + cfg.fadeTicks() + 2,
                engine.displayBudget().maxLifetimeTicks());
        return new SkillEngine.DisplayMotion(
                "검기_평타", "참격", modelName,
                lifetime, 0, cfg.fadeTicks(), Math.max(1, cfg.drawTicks()),
                1.0f, 0.0f, 0.0, 1.0f, 3, 0,
                1, 0.85, 1.0, 0.0f,
                new float[]{1.0f, 1.0f, 1.0f}, new float[]{0.0f, 0.0f, 0.0f},
                cfg.billboard(), cfg.brightness(), cfg.brightness());
    }

    // ══════════ ★ 획이 서는 자리 — 팔이 지나가는 자리에 세운다 ══════════

    /**
     * <b>획의 원점</b> — 시전자의 <b>발</b>에서 등록부가 적은 만큼 밀어낸 자리.
     *
     * <p><b>【2026-07 — 획이 몸 안에서 나오던 병】</b> 예전 계약은 이랬다:
     * <pre>  at = caster.getEyeLocation().subtract(0, 0.25, 0)   // 앞으로 미는 값이 **없었다**</pre>
     * 눈(1.62m)에서 0.25 내린 자리는 <b>시전자의 가슴 한복판</b>이다 (수평으로는 몸의 축 그대로).
     * 그래서 3인칭에서는 획이 얼굴·몸을 <b>관통</b>했고, 1인칭에서는 카메라가 획의 <b>안쪽</b>에 있어
     * <b>제 검의 궤적이 제 눈에 안 보였다</b>. 사용자가 인게임에서 본 그것이다.
     *
     * <p>이제 자리는 <b>등록부가 쥔다</b> ({@code display.stroke_origin}) — 앞·높이·옆 세 칸.
     * 코드가 하는 일은 <b>못을 박는 것</b>뿐이다:
     * <ul>
     *   <li><b>아래 못</b> — 어떤 값을 적어도 획은 <b>몸 밖</b>에 선다 ({@code body_radius + clearance}).
     *       이 못이 위 못보다 <b>세다</b>: 짧은 획(맨손)도 몸 안으로 후퇴하지 않는다</li>
     *   <li><b>위 못</b> — 획의 길이에 매인다 ({@code 길이 × forward_max_ratio}). 너무 밀면
     *       <b>허공에 뜬 판자</b>가 된다 (사거리와의 관계)</li>
     * </ul>
     * {@code centered: true} 인 자리(고리)는 <b>몸에 겹치는 것이 옳다</b> — 등록부가 그렇게 청구했다.
     *
     * @param flat 시선의 수평 성분 (이미 정규화되어 있다 — 부르는 쪽이 한 번만 잰다)
     */
    private Location origin(Player caster, SkillEngine.DisplayMotion m, double length, Vector flat) {
        SkillEngine.StrokeOrigin o = originOf(m.id());
        Vector right = new Vector(-flat.getZ(), 0, flat.getX());   // 주로 쓰는 손 쪽 (바닐라는 오른손잡이)
        double push = forwardOf(o, length);
        Location feet = caster.getLocation();
        Location at = feet.clone()
                .add(flat.clone().multiply(push))
                .add(right.multiply(o.lateral()));
        at.setY(feet.getY() + o.height());
        eyeOrigin(m, o);   // 【눈】 등록부가 몸 안을 청구했으면 소리내어 잡는다
        return at;
    }

    /**
     * <b>실효 앞거리</b> — 등록값을 두 못 사이로 조인다.
     *
     * <p>아래 못이 <b>이긴다</b>: {@code max(최소, min(등록값, 상한))} 이므로 상한이 최소보다 작아도
     * (아주 짧은 획) 원점은 <b>몸 밖</b>에 남는다. 몸 안으로 후퇴하느니 조금 뜨는 편이 낫다 —
     * 몸 안의 획은 <b>안 보인다</b>(1인칭)는 것을 우리는 이미 봤다.
     */
    double forwardOf(SkillEngine.StrokeOrigin o, double length) {
        if (o.centered()) {
            return o.forward();   // 고리 — 몸을 두르는 것이 옳다 (등록부가 청구했다)
        }
        SkillEngine.StrokeLimits lim = engine.strokeLimits();
        double cap = length * lim.forwardMaxRatio();
        return Math.max(lim.minForward(), Math.min(o.forward(), Math.max(lim.minForward(), cap)));
    }

    /**
     * <b>【눈】 획의 원점이 시전자의 몸 안인가.</b> 위반이면 사유를, 아니면 null.
     *
     * <p>몸 = 반지름 {@code body_radius} · 높이 0~{@code max_height} 의 기둥 (발~키). 그 안에 원점이
     * 있으면 획은 몸을 뚫고, 1인칭에서는 카메라 안쪽이라 <b>안 보인다</b> — 그것이 우리가 고친 병이다.
     *
     * <p><b>재는 것은 등록값이지 실효값이 아니다.</b> {@link #forwardOf} 가 이미 몸 밖으로 밀어내므로
     * 실효값을 재면 이 눈은 <b>영영 안 울린다</b> (제 손으로 고쳐 놓고 "문제 없음"이라 말하는 눈 =
     * 없느니만 못한 눈). 그래서 <b>등록부가 무엇을 청구했는지</b>를 본다: 몸 안을 청구했으면 위반이다.
     *
     * <p>{@code /혼천 획위치} 와 이 눈은 <b>같은 함수</b>에 물어본다 — 두 개의 진실을 만들지 않는다.
     */
    String originFault(SkillEngine.StrokeOrigin o) {
        if (o.centered()) {
            return null;   // 몸에 겹치는 것이 옳다 — 등록부가 소리내어 청구한 면제다
        }
        SkillEngine.StrokeLimits lim = engine.strokeLimits();
        double flatDist = Math.hypot(o.forward(), o.lateral());
        boolean inColumn = flatDist < lim.bodyRadius();
        boolean inHeight = o.height() >= 0.0 && o.height() <= lim.maxHeight();
        if (!inColumn || !inHeight) {
            return null;
        }
        return String.format(
                "원점이 몸 안이다 — 앞 %.2f · 옆 %.2f ⇒ 수평 %.2fm < 몸 반지름 %.2fm"
                        + " (높이 %.2f ∈ 발~키 0~%.2f). 획이 몸을 뚫고, 1인칭에서 안 보인다",
                o.forward(), o.lateral(), flatDist, lim.bodyRadius(), o.height(), lim.maxHeight());
    }

    /** 같은 위반을 두 번 짖지 않는다 (획은 초당 여러 번 그려진다 — 로그가 진실을 덮지 않게) */
    private final Set<String> barked = new HashSet<>();

    private void eyeOrigin(SkillEngine.DisplayMotion m, SkillEngine.StrokeOrigin o) {
        String fault = originFault(o);
        if (fault == null) {
            barked.remove(m.id());   // 고쳐졌다 — 다시 어기면 다시 짖는다
            return;
        }
        if (barked.add(m.id())) {
            plugin.getLogger().warning("[획·위반] " + m.id() + " — " + fault
                    + " (config/skill_motion.yml display.stroke_origin)");
        }
        note("획·위반", m.id() + " — " + fault);
    }

    /** 시선의 수평 성분 — 획은 하늘을 보지 않는다 (베는 것은 땅 위의 일이다) */
    private static Vector flat(Player caster) {
        Vector flat = caster.getLocation().getDirection().setY(0);
        if (flat.lengthSquared() < 1.0e-6) {
            return new Vector(1, 0, 0);
        }
        return flat.normalize();
    }

    // ══════════ ② 날의 기 — 병기에 겹쳐 서린다 (지속) ══════════

    /**
     * 격을 두르면 <b>손에 든 병기의 날을 따라 기가 흐른다</b> — 휘두르지 않아도.
     *
     * <p>이것이 격을 <b>정보</b>로 만든다: {@code npc_combat.yml} 의 전의 규칙(격_목격 — 검기 −2 ·
     * 강기 −5 · 어검 −8)은 "본다"를 전제한다. 휘둘러야만 보이면 그 규칙은 거짓말이다.
     * <b>검을 뽑아 기를 두르면, 그 자리에 있는 모두가 안다.</b>
     *
     * <p>손 앞 0.55m 에 뜨므로 <b>1인칭에서도 제 검에 기가 흐르는 것이 보인다</b> (타인의 눈에도).
     * 심장박동으로 산다 — 태세를 거두면 부름이 끊기고 12틱 뒤 스스로 사라진다.
     * 팩이 없는 눈에는 뜨지 않는다 ({@code fallback: null}) — 그 자리는 <b>손끝 잔광 파티클</b>이 지킨다.
     */
    void sheath(LivingEntity body, String grade) {
        SkillEngine.Sheath sh = engine.sheath(grade);
        if (sh == null || body.getWorld() == null) {
            return;   // 외공기 — 서릴 기가 없다 / 호신강기 — 고리가 그 자리다
        }
        SkillEngine.DisplayMotion m = engine.displayMotion(sh.motion());
        if (m == null || !m.isSheath()) {
            return;
        }
        Persist held = beat(body, "날의_기", m, grade);
        if (held == null) {
            return;
        }
        Location at = hand(body);
        for (Display d : held.parts) {
            if (d.isValid()) {
                d.teleport(at);   // 손을 따라 흐른다 (보간이 그 사이를 잇는다)
            }
        }
    }

    /**
     * 【실측】 플레이어 눈높이 (m) — {@code stroke_origin.limits} 의 실측(키 1.8 · 눈 1.62)과 같은 자.
     * 값이 아니라 <b>단위 환산</b>이다: 등록부의 height(발에서 잰 높이)를 눈에서 내리는 값으로 바꾼다.
     * ({@code getEyeHeight()} 를 안 쓰는 이유 — 몹은 눈높이가 제각각인데, 날의 기는 어떤 몸에서든
     * 눈 아래 같은 거리에 서야 한다. 등록부의 자는 플레이어 실측 하나다.)
     */
    private static final double PLAYER_EYE_LEVEL = 1.62;

    /**
     * 병기의 날이 있는 자리 — 눈에서 앞으로. 1인칭 시야에도 들어온다.
     *
     * <p>자리는 <b>등록부가 쥔다</b> ({@code display.stroke_origin.날의_기} — 획의 stroke_origin 과
     * 같은 문법). 획과 달리 <b>시선(피치 포함)을 따라</b> 민다: 날의 기는 손에 든 병기 위에 겹쳐
     * 살므로, 고개를 숙이면 날도 내려간다. {@code height} 는 수평 시선 기준 발에서 잰 높이라
     * 눈높이에서 내리는 값으로 환산한다 (1.62 − 1.47 = 0.15 — 옛 리터럴 그대로, 행동 불변).
     */
    private Location hand(LivingEntity body) {
        SkillEngine.StrokeOrigin seat = engine.strokeOrigin("날의_기");
        Vector dir = body.getEyeLocation().getDirection().normalize();
        Location at = body.getEyeLocation().add(dir.clone().multiply(seat.forward()))
                .subtract(0, PLAYER_EYE_LEVEL - seat.height(), 0);
        if (seat.lateral() != 0.0) {
            // 시선의 오른쪽 — strikeTest 와 같은 축 (왼손 좌표계 기준). 등록값 0 이면 이 길은 안 탄다
            Vector flat = dir.clone().setY(0);
            if (flat.lengthSquared() > 1.0e-6) {
                at.add(new Vector(-flat.getZ(), 0, flat.getX()).normalize().multiply(seat.lateral()));
            }
        }
        at.setDirection(dir);
        return at;
    }

    // ══════════ 호신강기 — 몸을 두르는 고리 ══════════

    /**
     * 손끝 잔광(두름)과 실루엣이 다르다: 그 차이가 곧 정보다 — "저 자는 검이 아니라 몸에 둘렀다."
     * 파티클 고리 위에 3D 판 4장이 돈다. 3D 가 강등돼도 파티클 고리는 그대로 돈다.
     */
    void ring(LivingEntity body, String formName) {
        SkillEngine.DisplayMotion m = engine.displayForForm(formName);
        if (m == null || !m.isRing() || body.getWorld() == null) {
            return;
        }
        Persist ring = beat(body, "고리", m, formName);
        if (ring == null) {
            return;
        }
        int n = Math.max(1, m.count());
        for (int i = 0; i < ring.parts.size(); i++) {
            Display d = ring.parts.get(i);
            if (d.isValid()) {
                d.teleport(orbit(body, m, i % n));
            }
        }
    }

    /** 궤도 위의 한 자리 — 조각은 바깥을 본다 (몸을 두른 판이 세상을 향한다) */
    private Location orbit(LivingEntity body, SkillEngine.DisplayMotion m, int i) {
        double a = Math.PI * 2 * i / Math.max(1, m.count()) + tick * m.orbit();
        Vector out = new Vector(Math.cos(a), 0, Math.sin(a));
        Location at = body.getLocation().add(0, m.height(), 0).add(out.clone().multiply(m.radius()));
        at.setDirection(out);
        return at;
    }

    /**
     * 지속 형체의 심장박동 — 없으면 세우고, 있으면 박동을 찍는다.
     *
     * <p><b>유령이 남지 않는 구조</b>: 죽음·퇴장·월드 변경·태세 해제에 코드가 따로 지울 필요가 없다.
     * 부름이 끊기면 스스로 사라진다.
     */
    private Persist beat(LivingEntity body, String slot, SkillEngine.DisplayMotion m, String variant) {
        Map<String, Persist> slots = persistent.computeIfAbsent(body.getUniqueId(),
                id -> new LinkedHashMap<>());
        Persist p = slots.get(slot);
        if (p != null && !p.variant.equals(variant)) {
            despawn(p.parts);          // 격이 바뀌었다 — 다시 세운다 (검기와 강기는 다른 형체다)
            slots.remove(slot);
            p = null;
        }
        if (p != null && !p.parts.isEmpty() && p.parts.get(0).isValid()) {
            p.lastBeat = tick;
            return p;
        }
        int units = m.isRing() ? Math.max(1, m.count()) : 1;
        Location seat = m.isRing() ? orbit(body, m, 0) : hand(body);
        if (!afford(units * Math.max(1, variants(seat)), false)) {
            denied++;
            return null;   // 예산이 없다 — 지속 형체가 가장 먼저 강등된다 (파티클이 그 자리를 지킨다)
        }
        Persist fresh = new Persist(m, variant);
        Player owner = body instanceof Player pl ? pl : null;
        for (int i = 0; i < units; i++) {
            Piece part = spawn(m, m.isRing() ? orbit(body, m, i) : seat, owner, 1, false,
                    body.getUniqueId());
            if (part == null) {
                break;
            }
            pieces.remove(part);       // 지속 형체는 수명으로 죽지 않는다 — 심장박동으로 산다
            fresh.parts.addAll(part.parts);
            for (Display d : part.parts) {
                d.setTeleportDuration(Math.max(1, engine.displayBudget().heartbeatTicks() / 3));
                transform(d, m, sizeOf(m, variant), 0.0f, m.interpolation());
            }
        }
        if (fresh.parts.isEmpty()) {
            return null;
        }
        fresh.lastBeat = tick;
        slots.put(slot, fresh);
        note(slot, variant + " × " + fresh.parts.size() + "조각");
        return fresh;
    }

    /** 지속 형체의 크기 — 날의 기는 격이 오를수록 <b>날을 넘어 자란다</b> (강기는 날 밖으로 삐져나온다) */
    private float[] sizeOf(SkillEngine.DisplayMotion m, String variant) {
        SkillEngine.Sheath sh = engine.sheath(variant);
        float[] base = sizeUnit(m);
        if (sh == null) {
            return base;   // 고리 — 조각은 제 크기 그대로
        }
        float[] k = sh.scale();
        return new float[]{base[0] * k[0], base[1] * k[1], base[2] * k[2]};
    }

    // ══════════ 날아가는 것 — 발출 · 던진 암기 · 어검 ══════════

    /**
     * 발출 — 기가 <b>날아간다</b>. 판정은 즉발이므로(선 히트박스) 획은 판정과 같은 틱에 태어나
     * 다섯 틱 안에 끝을 본다 (검기 1.6m/틱). 느리게 날면 "맞고 나서 검기가 온다" — 그것이 거짓말이다.
     */
    boolean bolt(Player caster, String formName, double range) {
        return fling(caster, engine.displayForForm(formName), range, "발출·" + formName);
    }

    /**
     * 암기 투척 — <b>던진 물건은 실제로 날아간다</b> (복제가 아니다: 손을 떠났으므로).
     * 빗나가면 땅에 <b>꽂혀</b> 잠깐 남는다 ({@code stick_ticks}) — 회수는 경제 층의 규칙이지 연출의 몫이 아니다.
     */
    boolean thrown(Player caster, String weaponClass, double range) {
        return fling(caster, engine.throwMotion(weaponClass), range, "투척·" + weaponClass);
    }

    private boolean fling(Player caster, SkillEngine.DisplayMotion m, double range, String what) {
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
        p.dieAt = tick + Math.min(m.lifetime(),
                (int) Math.ceil(range / m.speed()) + m.impactTicks() + m.stickTicks() + m.fade());
        for (Display d : p.parts) {
            d.setTeleportDuration(1);   // 이동 보간 — 이것이 없으면 순간이동한다
            transform(d, m, sizeUnit(m), 0.0f, Math.max(1, m.birth()));
        }
        note("투사", what + " " + String.format("%.1f", range) + "m @" + m.speed() + "m/틱");
        return true;
    }

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

    /** 착탄 — 기는 부풀며 터지고(impact_scale), 던진 물건은 <b>꽂혀 남는다</b>(stick_ticks) */
    private void land(Piece p) {
        p.landed = true;
        p.dieAt = tick + p.m.impactTicks() + p.m.stickTicks() + p.m.fade();
        if (p.m.impactScale() != 1.0f) {
            float[] burst = sizeUnit(p.m);
            for (int i = 0; i < 3; i++) {
                burst[i] *= p.m.impactScale();
            }
            for (Display d : p.parts) {
                transform(d, p.m, burst, 0.0f, Math.max(1, p.m.impactTicks()));
            }
        }
    }

    // ══════════ 오의 — 한 번 보면 잊지 못할 형체 ══════════

    /** 응집 내내 오므린 채 자라고, 개시의 순간 사거리만큼 <b>활짝 편다</b> */
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
        for (Display d : p.parts) {
            transform(d, m, bud(m, range), 0.0f, Math.max(1, startup));
        }
        note("오의", ultimateId + " 선딜" + startup + "틱 → " + range + "m 개화"
                + (m.oriented() ? " (앞으로 뻗는다 — orient " + deg(m.orient()) + ")" : ""));
    }

    private void bloom(Piece p) {
        p.burstAt = -1;
        for (Display d : p.parts) {
            transform(d, p.m, open(p.m, p.burstRadius),
                    p.m.spin() * p.m.interpolation(), p.m.interpolation());
        }
    }

    /**
     * 개시 — 활짝 편 형체. <b>축마다 다르게 자란다</b>({@code burst_scale: [x, y, z]}).
     *
     * <p>예전엔 세 축이 <b>같이</b> 자랐다 — 그래서 선(線) 오의의 형체를 '앞으로 뻗는 창'으로 세울 수 없었다
     * (길이를 사거리까지 늘리면 폭도 같이 하늘을 덮었다). 이제 <b>한 축만</b> 사거리를 따라 뻗고,
     * {@code orient} 가 그 축을 <b>앞으로</b> 눕힌다. 그 둘이 함께여야 창이 된다.
     *
     * <p>스칼라 {@code burst_scale} 은 그대로 돈다 (세 축이 같은 값 — 원 오의의 문법은 변하지 않았다).
     */
    private float[] open(SkillEngine.DisplayMotion m, double range) {
        float[] size = sizeUnit(m);
        float[] k = m.burstScale();
        for (int i = 0; i < 3; i++) {
            size[i] *= (float) (range * k[i]);
        }
        return size;
    }

    /**
     * 응집 — <b>오므린</b> 형체. 개화의 {@code BUD_RATIO} 만큼이다.
     *
     * <p><b>【고침】</b> 예전엔 봉오리를 사거리에서 <b>따로</b> 세웠다({@code size × range × 0.25}) —
     * 그래서 {@code burst_scale} 이 0.25 보다 작은 형체(뇌격 0.15)는 <b>봉오리가 개화보다 컸다</b>.
     * 오의가 <b>피는</b> 것이 아니라 <b>오므라들었다</b>. 이제 봉오리는 언제나 개화의 한 조각이다 —
     * 무엇이 피든 <b>커지는 방향으로만</b> 열린다.
     * ({@code burst_scale: 1.0} 인 형체에서는 예전 값과 정확히 같다 — 원 오의는 그대로다.)
     */
    private float[] bud(SkillEngine.DisplayMotion m, double range) {
        float[] size = sizeUnit(m);
        float[] open = open(m, range);
        for (int i = 0; i < 3; i++) {
            open[i] = Math.max(open[i] * BUD_RATIO, size[i] * BUD_FLOOR);
        }
        return open;
    }

    /** 봉오리는 개화의 4분의 1이다 (예전 계수 그대로 — 기준만 사거리에서 개화로 옮겼다) */
    private static final float BUD_RATIO = 0.25f;
    /** 그래도 보이지 않을 만큼 작아지지는 않는다 (응집을 못 보면 선딜이 예고가 아니다) */
    private static final float BUD_FLOOR = 0.35f;

    private static String deg(float[] o) {
        return String.format("%.0f°/%.0f°/%.0f°", o[0], o[1], o[2]);
    }

    // ══════════ 발행 (내부) — 예산 · 팩 분기 · 변환 ══════════

    private Piece spawn(SkillEngine.DisplayMotion m, Location at, Player owner,
                        int units, boolean ultimate) {
        return spawn(m, at, owner, units, ultimate, owner == null ? null : owner.getUniqueId());
    }

    private Piece spawn(SkillEngine.DisplayMotion m, Location at, Player owner,
                        int units, boolean ultimate, UUID ownerId) {
        return spawn(m, at, owner, units, ultimate, ownerId, null, null);
    }

    private Piece spawn(SkillEngine.DisplayMotion m, Location at, Player owner,
                        int units, boolean ultimate, UUID ownerId, Quaternionf seed) {
        return spawn(m, at, owner, units, ultimate, ownerId, seed, null);
    }

    /**
     * 한 발을 띄운다.
     *
     * <p><b>use_held</b> (던진 암기 · 이기어검) — 실은 것이 사람의 아이템 그 자체이므로 팩 유무가 그림을
     * 바꾸지 않는다. 엔티티 하나를 모두에게 보인다 (분기 없음 · 값도 절반).
     *
     * <p>그 밖 — 팩을 받은 눈과 못 받은 눈이 섞여 있으면 변주 2개를 띄우고 서로에게 숨긴다.
     * <b>폴백이 null 인 모델</b>(참격선 · 날의 기)은 팩 없는 눈에게 <b>아예 띄우지 않는다</b> —
     * 억지 폴백(검을 복제한 획)보다 파티클이 낫다.
     *
     * @param seedOffset ★ <b>씨앗의 자리</b> (엔티티 국소축 translation) — 검기의 <b>공전 시작점</b>.
     *                   null 이면 영(零): 옛 그림 그대로 엔티티 자리에서 자란다
     * @return 예산이 없거나 볼 사람이 없으면 null
     */
    private Piece spawn(SkillEngine.DisplayMotion m, Location at, Player owner, int units,
                        boolean ultimate, UUID ownerId, Quaternionf seed, Vector3f seedOffset) {
        SkillEngine.DisplayModel model = engine.displayModel(m.model());
        if (model == null || at.getWorld() == null) {
            return null;
        }
        ItemStack held = owner == null ? null : owner.getInventory().getItemInMainHand();

        if (model.useHeld()) {
            ItemStack stack = held == null || held.getType().isAir() ? null : held.clone();
            if (stack == null) {
                return null;   // 빈손 — 던질 것도 날릴 것도 없다
            }
            if (!afford(units, ultimate) || (ownerId != null && !affordPlayer(ownerId, units))) {
                denied++;
                return null;
            }
            Piece p = new Piece(m, ownerId);
            p.parts.add(create(at, m, stack, null, seed, seedOffset));   // 모두의 눈에 — 팩 분기가 없다
            p.dieAt = tick + m.lifetime();
            pieces.add(p);
            return p;
        }

        List<Player> withPack = new ArrayList<>();
        List<Player> without = new ArrayList<>();
        audience(at, withPack, without);
        boolean plainOk = material(model.fallback()) != null;
        if (withPack.isEmpty() && (without.isEmpty() || !plainOk)) {
            return null;   // 볼 눈이 없거나, 폴백 없는 형체를 팩 없는 눈만 보고 있다 (파티클이 지킨다)
        }
        int need = units * ((withPack.isEmpty() ? 0 : 1) + (without.isEmpty() || !plainOk ? 0 : 1));
        if (!afford(need, ultimate) || (ownerId != null && !affordPlayer(ownerId, need))) {
            denied++;
            return null;   // 예산 초과 — over_cap: "파티클 연출로 강등" (performance.yml vfx_entities)
        }

        Piece p = new Piece(m, ownerId);
        if (!withPack.isEmpty()) {
            ItemStack packedItem = item(model, true, held);
            if (packedItem != null) {
                p.parts.add(create(at, m, packedItem, withPack, seed, seedOffset));
            }
        }
        if (!without.isEmpty() && plainOk) {
            ItemStack plainItem = item(model, false, held);
            if (plainItem != null) {
                p.parts.add(create(at, m, plainItem, without, seed, seedOffset));
            }
        }
        if (p.parts.isEmpty()) {
            return null;
        }
        p.dieAt = tick + m.lifetime();
        pieces.add(p);
        return p;
    }

    /**
     * @param viewers null 이면 32m 안의 모두에게 보인다 (use_held — 팩 분기가 없는 형체)
     * @param seed    ★ <b>스윙의 시작 각</b> — 여기서 띄우고 {@link #transform} 이 끝 각을 준다.
     *                그 사이를 클라이언트가 slerp 한다 = <b>획이 쓸고 지나간다</b>. null 이면 등록된 각 그대로
     */
    private Display create(Location at, SkillEngine.DisplayMotion m, ItemStack stack,
                           List<Player> viewers, Quaternionf seed, Vector3f seedOffset) {
        SkillEngine.DisplayBudget b = engine.displayBudget();
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(stack);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);   // 손 변형 없이 모델 그대로
            e.setBillboard(billboard(m.billboard()));
            e.setBrightness(new Display.Brightness(m.blockLight(), m.skyLight()));
            e.setViewRange(b.viewRange());              // 32m — 파티클 cull_beyond 와 같은 눈금
            e.setInterpolationDelay(0);
            e.setInterpolationDuration(m.interpolation());
            e.setPersistent(false);                     // 저장되지 않는다 (유령의 첫 방벽)
            e.setVisibleByDefault(viewers == null);
            e.getPersistentDataContainer().set(KEY_VFX, PersistentDataType.BYTE, (byte) 1);
            // 씨앗에서 자란다 — 다만 <b>선 각은 처음부터 제 각</b>이다 (뒤에 돌리면 형체가 튄다)
            e.setTransformation(new Transformation(
                    seedOffset == null ? new Vector3f() : new Vector3f(seedOffset),
                    seed == null ? pose(m, 0.0f) : new Quaternionf(seed),
                    new Vector3f(0.001f, 0.001f, 0.001f), new Quaternionf()));
        });
        if (viewers != null) {
            for (Player viewer : viewers) {
                viewer.showEntity(plugin, d);
            }
        }
        return d;
    }

    /**
     * 팩 유무로 관람석을 가른다 — 32m(cull_beyond) 안의 눈만 센다.
     * 지금 서버는 팩을 배포하지 않는다 ({@code resource-pack=} 빈 값) → 전원이 폴백 관람석이다
     * (그래서 참격선·날의 기는 아직 파티클로만 읽힌다 — {@code /혼천 모션진단} 이 그 사실을 말해 준다).
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
     * 실을 아이템 — 팩의 유일한 접점.
     *
     * @param withPack true 면 {@code item_model} 키를 얹는다. false 면 <b>키를 얹지 않는다</b> —
     *                 폴백 바닐라 아이템이 그대로 보인다 (키만 얹으면 '없는 모델' 큐브가 뜬다)
     */
    @SuppressWarnings("deprecation")
    private ItemStack item(SkillEngine.DisplayModel model, boolean withPack, ItemStack held) {
        if (model.useHeld()) {
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
            } else if (key == null) {
                // 등록부의 키가 legal 하지 않다 — 대문자·한글·공백. 그러면 item_model 이 **안 얹히고**
                // 맨 종이가 뜬다. 조용하다. 소리내어 말한다 (등록제: 이름은 등록부가 짓지만 검사는 코드가 한다)
                plugin.getLogger().warning("[혼천] 모션 등록부의 키가 legal 하지 않다: " + model.key()
                        + " (" + model.id() + ") — item_model 을 못 얹는다");
            }
        }
        // ★ **얹은 것을 말한다.** 정적 검산(등록부 ↔ 팩)이 전부 통과했는데도 보라 큐브가 보였다 —
        //   그렇다면 우리가 얹었다고 **믿는 것**과 실제로 얹힌 것이 다를 수 있다. 실물을 읽어 찍는다.
        //   (조합마다 한 번만 — 획은 초당 여러 번 그려지므로 매번 찍으면 로그가 진실을 덮는다)
        String seen = model.id() + (withPack ? "|팩" : "|폴백");
        if (witnessed.add(seen)) {
            plugin.getLogger().info(String.format(
                    "[획] %s — withPack %s · 밑감 %s · item_model %s",
                    model.id(), withPack ? "예" : "아니오", stack.getType(),
                    itemModelOf(stack) == null ? "(없음)" : itemModelOf(stack)));
        }
        return stack;
    }

    /** 등록부의 아이템 이름 → 바닐라 Material. <b>코드가 아이템을 고르지 않는다</b> (등록제 규약) */
    private Material material(String name) {
        if (name == null || "null".equals(name)) {
            return null;   // 폴백 없음 — 팩 없는 눈에는 띄우지 않는다 (파티클이 그 자리를 지킨다)
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
        for (Map<String, Persist> slots : persistent.values()) {
            for (Persist p : slots.values()) {
                n += p.parts.size();
            }
        }
        return n;
    }

    /** 오의는 <b>예약분</b>을 쓴다 (파티클 층의 우선권과 같은 문법: 생략이 아니라 우선) */
    private boolean afford(int need, boolean ultimate) {
        SkillEngine.DisplayBudget b = engine.displayBudget();
        int cap = ultimate ? b.globalCap()
                : Math.min(b.degradeAt(), b.globalCap() - b.reserveForUltimate());
        return live() + need <= cap;
    }

    /** 한 몸이 세계를 형체로 덮지 못한다 */
    private boolean affordPlayer(UUID owner, int need) {
        int mine = 0;
        for (Piece p : pieces) {
            if (owner.equals(p.owner)) {
                mine += p.parts.size();
            }
        }
        Map<String, Persist> slots = persistent.get(owner);
        if (slots != null) {
            for (Persist p : slots.values()) {
                mine += p.parts.size();
            }
        }
        return mine + need <= engine.displayBudget().perPlayerMax();
    }

    // ─── 변환 (보간이 부드러움의 전부다) ───

    private float[] sizeUnit(SkillEngine.DisplayMotion m) {
        SkillEngine.DisplayModel model = engine.displayModel(m.model());
        float[] s = model == null ? new float[]{1, 1, 1} : model.size();
        return new float[]{s[0], s[1], s[2]};
    }

    /**
     * <b>모델은 중심에 서고, 고정점은 여기서 옮긴다.</b>
     *
     * <p><b>【2026-07 — 획이 시전자의 옆구리에서 나오던 병】</b> 참격선 모델은 예전에
     * <b>머리를 원점에 두고 몸을 전부 −X 로</b> 늘어뜨렸다. {@code scale} 이 원점을 중심으로 먹으니
     * 그래야 "꼬리부터 지워진다"를 얻기 때문이다. 그런데 그 대가로 <b>모델의 기하 중심이 x −12 만큼
     * 치우쳤고</b>, ItemDisplay 는 모델을 엔티티 자리에 <b>중심을 두고</b> 그린다. 게다가 모델의 +X 는
     * ({@link #pose} 의 좌표계 주석대로) <b>앞이 아니라 좌우 축</b>이다 ⇒ 획이 시전자의
     * <b>정면이 아니라 옆구리 한쪽에 통째로</b> 걸렸다. 사용자가 본 그것이다.
     *
     * <p><b>그래서 모델을 중심에 세우고, 머리 고정을 translation 으로 옮겼다:</b>
     * <pre>  translation.x = halfLen × (1 − scale.x)      (halfLen = size[0] / 2, 미터)</pre>
     * <ul>
     *   <li>{@code scale.x = 1} → translation 0 ⇒ 획이 <b>정면에 좌우 대칭</b>으로 걸린다 (고친 것)
     *   <li>{@code scale.x → 0} → translation → halfLen ⇒ 획이 <b>머리 쪽으로 수축</b>한다
     *       = 그리면 머리에서 꼬리로 자라고, 지우면 <b>꼬리부터 지워진다</b> (옛 그림 그대로)
     * </ul>
     * 즉 <b>보이는 그림은 한 틱도 안 잃고</b> 편향만 사라진다.
     *
     * <p>고정점은 <b>등록부가 청구한다</b> ({@code models.<키>.anchor: head}) — 코드가 지어내지 않는다.
     * anchor 가 없으면 translation 0 (원점=중심에서 대칭으로 자란다: 고리·판·덩이).
     *
     * <p><b>주의</b>: Transformation 은 {@code T · LR · S · RR} 이라 translation 은 <b>회전 뒤</b>,
     * 엔티티 국소축에서 먹는다. 참격선의 {@code orient} 는 항등이므로 모델 X == 엔티티 X 다.
     * 머리 고정을 쓰는 모션에 orient 를 달게 되면 이 가정을 다시 재야 한다.
     */
    private void transform(Display d, SkillEngine.DisplayMotion m, float[] scale, float spin,
                           int ticks) {
        transform(d, m, scale, pose(m, spin), 0.0f, ticks);
    }

    /**
     * ★ <b>각을 주는 손</b> — 회전을 명시한다. {@code Display} 의 Transformation 은 <b>회전도 보간된다</b>
     * (클라이언트가 slerp 한다). 시작 각으로 띄우고 여기서 끝 각을 주면 <b>획이 호를 그리며 쓸고 지나간다</b>.
     *
     * <p>이것이 "찌르기 → 베기"의 전부다. <b>엔티티는 하나 그대로다</b> (예산 0 증가).
     *
     * @param rise 획이 오르내리는 높이 (m) — 엔티티 국소축의 위(+Y). 올려베기·내려베기가 쓴다
     */
    private void transform(Display d, SkillEngine.DisplayMotion m, float[] scale, Quaternionf rot,
                           float rise, int ticks) {
        if (!d.isValid()) {
            return;
        }
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(Math.max(1, ticks));
        d.setTransformation(new Transformation(headAnchor(m, scale[0], rot, rise), rot,
                new Vector3f(scale[0], scale[1], scale[2]), new Quaternionf()));
    }

    /**
     * 머리 고정 오프셋 — 등록부가 {@code anchor: head} 를 청구한 모델만. 그 밖은 영(零)이다.
     *
     * <p><b>【회전과 함께 돌아야 한다】</b> Transformation 은 {@code T · LR · S · RR} 이라
     * translation 은 <b>회전 뒤</b>에 먹는다. 그러므로 "모델의 +X 끝을 붙들어 둔다"는 청구를 지키려면
     * 그 오프셋도 <b>같은 각으로 돌려야</b> 한다 — 안 돌리면 획이 회전할수록 머리가 <b>제자리에서 미끄러진다</b>.
     * (예전엔 참격선의 orient 가 항등이라 이 사실이 드러나지 않았다. 스윙이 그것을 드러냈다.)
     */
    private Vector3f headAnchor(SkillEngine.DisplayMotion m, float scaleX, Quaternionf rot,
                                float rise) {
        SkillEngine.DisplayModel model = engine.displayModel(m.model());
        Vector3f t = new Vector3f(0.0f, rise, 0.0f);   // 오르내림은 **몸의 위**다 (회전을 안 탄다)
        if (model == null || !model.headAnchored()) {
            return t;
        }
        float halfLen = model.size()[0] * 0.5f;              // 미터 — 머리는 +X 끝에 있다
        Vector3f head = new Vector3f(halfLen * (1.0f - scaleX), 0.0f, 0.0f);
        return t.add(rot.transform(head));                   // ★ 머리도 획과 함께 돈다
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
        d.setTransformation(new Transformation(t.getTranslation(), pose(m, angle),
                t.getScale(), t.getRightRotation()));
    }

    /**
     * <b>형체가 서는 각</b> — 등록부의 {@code orient: [pitch, yaw, roll]} (도) + 자전.
     *
     * <p><b>【이 층의 좌표계 — 그동안 어디에도 적혀 있지 않았다】</b> 엔티티는 이미 시전자를 향해
     * 세워져 있다({@code setDirection}). 그 몸을 기준으로 모델의 축은
     * <b>+X 왼쪽 · +Y 위 · +Z 앞</b> 이다 (바닐라 규약: yaw 0 인 몸은 +Z 를 본다).
     * <b>앞은 Z 다.</b> 이 사실이 적혀 있지 않아서, 등록부는 <b>앞으로 뻗는 축을 모델에 새길 수 없었다</b> —
     * 오의의 형체는 yaw 로만 서고 scale 로만 자랐다. 그래서 선(線) 오의가 '앞으로 뻗는 창'이 아니라
     * '몸 앞에 선 빗살'이었다. 이제 등록부가 그 축을 <b>직접 댄다</b>.
     *
     * <p><b>차례</b>: 자전(모델의 X) → roll(모델의 Z) → pitch(모델의 X) → yaw(모델의 Y).
     * 제 축으로 굴린 뒤 앞으로 눕히고 마지막에 좌우로 튼다.
     * {@code orient: [90, 0, 0]} 이면 모델의 <b>+Y(길이축)가 +Z(앞)</b> 로 눕는다 — 찌르는 창이 된다.
     *
     * <p>회전은 <b>leftRotation</b> 이다: 크기를 <b>모델의 축에서</b> 먼저 조절하고 <b>그 뒤에</b> 돌린다.
     * 그래야 {@code size}·{@code burst_scale} 의 축이 회전을 따라 흔들리지 않는다
     * (등록부가 "이 축이 길이다"라고 말한 것이 회전 뒤에도 길이로 남는다).
     */
    private static Quaternionf pose(SkillEngine.DisplayMotion m, float spin) {
        float[] o = m.orient();
        Quaternionf q = new Quaternionf()
                .rotateY((float) Math.toRadians(o[1]))     // yaw   — 좌우로 튼다
                .rotateX((float) Math.toRadians(o[0]))     // pitch — 앞으로 눕는다 (양수 = 앞·아래)
                .rotateZ((float) Math.toRadians(o[2]));    // roll  — 제 얼굴을 굴린다
        if (spin != 0.0f) {
            q.mul(new Quaternionf(new AxisAngle4f(spin, 1.0f, 0.0f, 0.0f)));   // 자전 — 모델의 X 축
        }
        return q;
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

    // ══════════ 획시험 — 획의 눈 (/혼천 획시험) ══════════

    /**
     * <b>없는 키.</b> 이 대조군이 이번 진단의 심장이다.
     *
     * <p>팩에 <b>없는 것이 확실한</b> 키를 얹는다. 1.21.4+ 는 {@code item_model} 이 가리키는 정의가 없으면
     * <b>바탕 아이템으로 폴백하지 않고 '없는 모델'(보라-검정 큐브)을 그린다</b> (Weapons.java 가 바로 이
     * 이유로 item_model 을 피하고 custom_model_data 를 쓴다). 그러므로:
     * <ul>
     *   <li>이 칸이 <b>보라 큐브</b>다 → {@code setItemModel} 은 살아서 클라이언트에 닿고 있다.
     *       그렇다면 다른 칸의 보라는 <b>그 키가 팩에 없다</b>는 뜻이다 (팩의 문제).</li>
     *   <li>이 칸이 <b>보라가 아니다</b>(맨 종이로 보인다) → {@code item_model} 이 아예 안 실리고 있다.
     *       그렇다면 원인은 팩이 아니라 <b>코드/서버</b>다.</li>
     * </ul>
     *
     * <p><b>키는 반드시 legal 해야 한다.</b> {@link NamespacedKey#fromString} 은 대문자·한글·공백을 거절하고
     * {@code null} 을 돌려준다 — 그러면 {@code setItemModel} 이 <b>불려지지도 않아</b> 맨 종이가 뜨고,
     * 시험은 "코드가 문제다"라고 <b>거짓 자백</b>을 한다. 그래서 소문자·밑줄만 쓴다.
     */
    private static final String GHOST_KEY = "honcheon:qi/no_such_stroke_control";

    /** 팩이 확실히 가진 다른 키 — 획(qi/ult)이 아닌 곳도 뚫리는가 (경로 문제와 팩 문제를 가른다) */
    private static final String WEAPON_KEY = "honcheon:weapon/sword_beomcheol";

    /** 시험대가 스스로 사라지는 시각 */
    private static final int TEST_TICKS = 15 * 20;

    /**
     * <b>/혼천 획시험</b> — 획 15종을 플레이어 앞에 한 줄로 세우고, 각각 머리 위에 <b>키 이름</b>을 단다.
     *
     * <p>정적 검산은 전부 통과했다 (배급 중인 zip = 최신 · 15개 키 전부 팩에 있음 · items→models→textures
     * 사슬 안 끊김 · 배치본 = 저장소). 그런데 사용자는 보라 큐브를 본다. <b>검산이 볼 수 없는 곳에서
     * 무언가 어긋나 있다</b> — 그러면 눈을 게임 안에 세우는 수밖에 없다.
     *
     * <p>대조군 셋을 <b>같은 줄에</b> 세운다 (따로 세우면 눈이 또 거짓말한다):
     * <ol>
     *   <li><b>맨 종이</b> — {@code item_model} 없음. 팩과 무관하게 종이로 보여야 한다 (엔티티가 뜨는가)</li>
     *   <li><b>없는 키</b> ({@link #GHOST_KEY}) — 위 주석 참조. <b>이 칸이 판결을 내린다</b></li>
     *   <li><b>병기 키</b> ({@link #WEAPON_KEY}) — 팩이 확실히 가진 키를 <b>item_model 경로로</b>.
     *       (실물 병기는 custom_model_data 로 가므로, 이건 그 길이 아니라 <b>획과 같은 길</b>이다)</li>
     * </ol>
     *
     * <p>획은 {@link #item(SkillEngine.DisplayModel, boolean, ItemStack) item(model, true, null)} 이
     * 만든다 — <b>실전과 같은 손</b>이다. 시험용으로 따로 만들면 시험이 실전을 대변하지 못한다.
     *
     * @return 채팅에 뿌릴 줄 (왼쪽부터 몇 번째가 보라인지 셀 수 있게 번호를 붙인다)
     */
    List<String> strokeTest(Player player) {
        List<String> out = new ArrayList<>();
        Location eye = player.getEyeLocation();
        if (eye.getWorld() == null) {
            out.add("§c세계가 없다 — 시험대를 못 세운다");
            return out;
        }
        // 시선 앞 4m, 시선과 직교하는 축을 따라 오른쪽으로 늘어놓는다 (수평만 — 고개를 들어도 줄은 눕지 않는다)
        Vector forward = eye.getDirection().setY(0);
        if (forward.lengthSquared() < 1.0e-6) {
            forward = new Vector(0, 0, 1);   // 바로 위/아래를 보고 있었다
        }
        forward.normalize();
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());   // 왼손 좌표계 기준 오른쪽
        Location origin = eye.clone().add(forward.clone().multiply(4.0));

        // ── 시험대에 오를 것들: 대조군 3 + 등록부의 모든 형체 ──
        record Row(String label, ItemStack stack, String note) {
        }
        List<Row> rows = new ArrayList<>();

        // ★ 대조군은 일부러 등록부를 안 탄다 — 등록부가 병들면 대조군도 같이 병들어 '대조'가 죽는다.
        //   그래서 이 세 줄만은 리터럴이 옳고, motion_audit 에는 [대조] 주석으로 소리내어 청구한다
        //   (stroke_origin 의 centered: true 와 같은 문법 — 면제는 코드가 지어내지 않는다).
        rows.add(new Row("[대조] 맨 종이", new ItemStack(Material.PAPER),   // [대조] 진단 대조군
                "item_model 없음 — 종이로 보여야 정상"));
        rows.add(new Row("[대조] 없는 키", modelled(Material.PAPER, GHOST_KEY),   // [대조] 진단 대조군
                GHOST_KEY + " — ★ 판결의 칸"));
        rows.add(new Row("[대조] 병기 키", modelled(Material.PAPER, WEAPON_KEY),   // [대조] 진단 대조군
                WEAPON_KEY + " — 팩이 가진 키 (item_model 경로)"));

        for (SkillEngine.DisplayModel model : engine.displayModels()) {
            if (model.useHeld()) {
                continue;   // 실을 것이 사람의 손이다 — 팩에 요구하는 것이 없다 (시험할 것도 없다)
            }
            ItemStack stack = item(model, true, null);   // ★ 실전과 같은 손
            if (stack == null) {
                out.add("§c" + model.id() + " — 실을 아이템을 못 만들었다 (base: " + model.base()
                        + " 가 등록부에 없는 이름인가?)");
                continue;
            }
            rows.add(new Row(model.id(), stack, String.valueOf(model.key())));
        }

        // ── 세운다 ──
        double span = 1.6;
        double start = -(rows.size() - 1) / 2.0;
        List<Entity> stand = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            Location at = origin.clone().add(right.clone().multiply((start + i) * span));
            stand.add(testItem(at, row.stack()));
            stand.add(testLabel(at.clone().add(0, 0.9, 0), (i + 1) + ". " + row.label()));
            plugin.getLogger().info(String.format(
                    "[획시험] %2d. %-14s 밑감 %-16s item_model %s",
                    i + 1, row.label(), row.stack().getType(),
                    itemModelOf(row.stack()) == null ? "(없음)" : itemModelOf(row.stack())));
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (Entity e : stand) {
                if (e.isValid()) {
                    e.remove();
                }
            }
        }, TEST_TICKS);

        // ── 채팅: 왼쪽부터 세라 ──
        boolean iAmPacked = packed.contains(player.getUniqueId());
        out.add("§6── 획시험 — 왼쪽부터 " + rows.size() + "칸 (15초 뒤 사라진다) ──");
        out.add("§7이 몸은 팩을 §f" + (iAmPacked ? "받은 눈" : "못 받은 눈")
                + "§7 이다 (SkillDisplay.packed). 팩을 못 받은 눈이라면 §c획은 전부 보라일 것이고, "
                + "그것은 팩이 아니라 배급/수락의 문제다");
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            out.add("§f" + (i + 1) + ". §e" + row.label() + " §8— " + row.note());
        }
        out.add("§7② 없는 키가 §d보라§7 면 setItemModel 은 살아 있다 → 보라인 획은 §f그 키가 팩에 없다");
        out.add("§7② 없는 키가 §f맨 종이§7 면 item_model 이 안 실린다 → 원인은 §f팩이 아니라 코드/서버");
        return out;
    }

    /** 시험대의 한 칸 — 실전과 같은 ItemDisplay (NONE 변형 · 팩의 접점은 아이템이 이미 들고 있다) */
    private ItemDisplay testItem(Location at, ItemStack stack) {
        return at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(stack);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);   // 실전과 같은 변형
            e.setBillboard(Display.Billboard.CENTER);   // 돌아가며 봐도 같은 얼굴 (실전과 다른 유일한 점)
            e.setBrightness(new Display.Brightness(15, 15));   // 밤에도 보인다 — 어둠은 시험의 적이다
            e.setViewRange(4.0f);
            e.setPersistent(false);
            e.getPersistentDataContainer().set(KEY_VFX, PersistentDataType.BYTE, (byte) 1);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(1.0f, 1.0f, 1.0f), new Quaternionf()));
        });
    }

    /**
     * <b>말뚝</b> — 자리에 이름을 박는다 ({@code /혼천 사다리} 가 격마다 하나씩 세운다).
     * {@code ticks} 뒤에 스스로 사라진다 (유령이 남지 않는다 — KEY_VFX 표식도 단다).
     */
    void post(Location at, String text, int ticks) {
        org.bukkit.entity.TextDisplay label = testLabel(at, text);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (label.isValid()) {
                label.remove();
            }
        }, ticks);
    }

    /** 이름표 — 이것이 없으면 사용자가 "몇 번째"라고만 말할 수 있고, 우리는 그 번호를 못 읽는다 */
    private org.bukkit.entity.TextDisplay testLabel(Location at, String text) {
        return at.getWorld().spawn(at, org.bukkit.entity.TextDisplay.class, e -> {
            e.text(net.kyori.adventure.text.Component.text(text));
            e.setBillboard(Display.Billboard.CENTER);
            e.setSeeThrough(true);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(4.0f);
            e.setPersistent(false);
            e.getPersistentDataContainer().set(KEY_VFX, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /**
     * 대조군용 — 바탕 아이템에 {@code item_model} 을 직접 얹는다.
     *
     * <p>키가 <b>legal 하지 않으면</b> {@code fromString} 이 null 을 주고 아무것도 안 얹힌다.
     * 그러면 대조군이 조용히 무력해지므로 — <b>소리내어 경고한다</b> (조용한 실패 금지).
     */
    @SuppressWarnings("deprecation")
    private ItemStack modelled(Material base, String key) {
        ItemStack stack = new ItemStack(base);
        NamespacedKey ns = NamespacedKey.fromString(key);
        if (ns == null) {
            plugin.getLogger().warning("[획시험] 키가 legal 하지 않다: " + key
                    + " — item_model 을 못 얹는다. 이 칸은 대조군 구실을 못 한다");
            return stack;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setItemModel(ns);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** 이 아이템이 실제로 들고 있는 item_model — 로그가 짐작하지 않고 <b>실물을 읽는다</b> */
    @SuppressWarnings("deprecation")
    private static NamespacedKey itemModelOf(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        return meta == null ? null : meta.getItemModel();
    }

    // ══════════ 획위치 — 인게임에서 맞춘다 (/혼천 획위치) ══════════

    /** 등록부 + 인게임 임시 조정 — <b>그리는 코드가 보는 것과 정확히 같은 값</b> */
    SkillEngine.StrokeOrigin originOf(String motionId) {
        SkillEngine.StrokeOrigin base = engine.strokeOrigin(motionId);
        double[] ov = originOverride.get(motionId);
        return ov == null ? base
                : new SkillEngine.StrokeOrigin(motionId, ov[0], ov[1], ov[2], base.centered());
    }

    /** 한 칸을 민다 — {@code 앞} · {@code 높이} · {@code 옆}. 모르는 칸이면 false */
    boolean setOrigin(String motionId, String field, double value) {
        SkillEngine.StrokeOrigin cur = originOf(motionId);
        double[] v = {cur.forward(), cur.height(), cur.lateral()};
        switch (field) {
            case "앞" -> v[0] = value;
            case "높이" -> v[1] = value;
            case "옆" -> v[2] = value;
            default -> {
                return false;
            }
        }
        originOverride.put(motionId, v);
        barked.remove(motionId);   // 새 값은 새로 심판받는다
        return true;
    }

    /** 등록부로 되돌린다 — 인게임에서 민 것을 전부 버린다 */
    void resetOrigins() {
        originOverride.clear();
        barked.clear();
    }

    boolean packed(UUID viewer) {
        return packed.contains(viewer);
    }

    /**
     * 지금 값 — 모션마다 <b>등록값 · 실효값 · 몸 안 검사</b>.
     *
     * @param length 이 사람이 지금 든 병기의 획 길이 (m) — 실효 앞거리가 여기에 매인다
     */
    List<String> originReport(double length, String weaponClass) {
        SkillEngine.StrokeLimits lim = engine.strokeLimits();
        List<String> out = new ArrayList<>();
        out.add("§6── 획이 서는 자리 (/혼천 획위치) ──");
        out.add(String.format("§7몸 반지름 §f%.2f§7m + 여유 §f%.2f§7 ⇒ 앞으로 최소 §f%.2f§7m"
                        + " · 앞 상한 = 획 길이 × §f%.2f",
                lim.bodyRadius(), lim.clearance(), lim.minForward(), lim.forwardMaxRatio()));
        out.add(String.format("§7지금 든 손: §f%s§7 — 획 길이 §f%.2f§7m ⇒ 앞 상한 §f%.2f§7m",
                weaponClass, length, length * lim.forwardMaxRatio()));
        for (SkillEngine.DisplayMotion m : engine.slashMotions()) {
            SkillEngine.StrokeOrigin o = originOf(m.id());
            double eff = forwardOf(o, length);
            String fault = originFault(o);
            boolean moved = originOverride.containsKey(m.id());
            out.add(String.format("§f%s §7앞 §e%.2f§7 · 높이 §e%.2f§7 · 옆 §e%.2f%s%s",
                    m.id(), o.forward(), o.height(), o.lateral(),
                    o.centered() ? " §8[centered — 몸에 겹치는 것이 옳다]" : "",
                    moved ? " §b(인게임에서 민 값)" : ""));
            if (!o.centered() && Math.abs(eff - o.forward()) > 1.0e-6) {
                out.add(String.format("   §8→ 실효 앞 §f%.2f§8m (못에 걸렸다)", eff));
            }
            if (fault != null) {
                out.add("   §c✖ 위반: " + fault);
            }
        }
        out.add("§7밀고 당기기: §f/혼천 획위치 <호|선|원> <앞|높이|옆> <값>§7 — 밀면 §f즉시 획을 한 번 긋는다");
        out.add("§7그냥 그려 보기: §f/혼천 획위치 그려 [호|선|원]§7 · 되돌리기: §f/혼천 획위치 되돌려");
        out.add("§7등록부에 적기: §f/혼천 획위치 적기§7 — 뽑은 줄을 "
                + "§fconfig/skill_motion.yml §7의 §fdisplay.stroke_origin §7에 그대로 붙인다");
        return out;
    }

    // ══════════ ★ 스윙 — 인게임에서 밀고 당긴다 (/혼천 스윙) ══════════

    /**
     * <b>인게임 임시 조정</b> — 호 각도·오르내림·활·전진의 배율.
     *
     * <p><b>왜 명령인가</b>: 사용자의 못 — <i>"이런 값은 눈으로 봐야 정해진다."</i> 스윙의 크기·각도는
     * 계산으로 못 맞춘다. {@code /혼천 획위치} 와 같은 문법이다: <b>밀면 즉시 한 획을 긋는다</b>.
     * 재기동하면 사라지고, {@code /혼천 스윙 적기} 가 등록부에 붙일 줄을 뽑는다 —
     * <b>코드가 등록부를 고치면 등록제가 무너진다</b>.
     */
    private SkillEngine.SwingTuning tuneOverride;

    SkillEngine.SwingTuning tuning() {
        return tuneOverride != null ? tuneOverride : engine.swingArcs().tuning();
    }

    /** 한 칸을 민다 — {@code 호} · {@code 높이} · {@code 활} · {@code 전진}. 모르는 칸이면 false */
    boolean setSwing(String field, double value) {
        SkillEngine.SwingTuning t = tuning();
        double arc = t.arc();
        double rise = t.rise();
        double bow = t.bow();
        double lunge = t.lunge();
        switch (field) {
            case "호" -> arc = value;
            case "높이" -> rise = value;
            case "활" -> bow = value;
            case "전진" -> lunge = value;
            default -> {
                return false;
            }
        }
        tuneOverride = new SkillEngine.SwingTuning(arc, rise, bow, lunge);
        barked.clear();   // 새 값은 새로 심판받는다 (호각 상한·부채꼴 밖은 다시 짖는다)
        return true;
    }

    void resetSwings() {
        tuneOverride = null;
        barked.clear();
    }

    /** 지금 값 + <b>눈</b> (호각 · 전진 · 참격비) — 계열마다 한 줄 */
    List<String> swingReport(String weaponClass) {
        SkillEngine.SwingArcs arcs = engine.swingArcs();
        SkillEngine.SwingTuning t = tuning();
        SkillEngine.SlashEye eye = engine.slashEye();
        List<String> out = new ArrayList<>();
        out.add("§6── 스윙 (/혼천 스윙) ──" + (arcs.enabled() ? "" : " §c[꺼져 있다]"));
        out.add(String.format("§7배율: 호 §e%.2f§7 · 높이 §e%.2f§7 · 활 §e%.2f§7 · 전진 §e%.2f%s",
                t.arc(), t.rise(), t.bow(), t.lunge(),
                tuneOverride != null ? " §b(인게임에서 민 값)" : ""));
        out.add(String.format("§7눈: 호각 ≥ §f%.0f§7도 · 전진 ≤ §f%.2f§7m (무거운 손 §f%.2f§7m)"
                + " · 참격비 ≥ §f%.0f§7 도/m §8(찌르기는 전진이 크고 호가 작다)",
                eye.minArcDeg(), eye.maxLungeM(), eye.maxLungeHeavyM(), eye.minRatio()));
        for (String id : arcs.strokes().keySet()) {
            SkillEngine.SwingArc a = arcs.stroke(id);
            out.add(String.format("§f%-8s §7호각 §e%3.0f§7도 · 오르내림 §e%+.2f§7→§e%+.2f§7m"
                            + " · 부채꼴 §e%.0f%%§7 · 활 §e%+.2f§7m",
                    id, a.arcDeg(t.arc()), a.rise()[0] * t.rise(), a.rise()[1] * t.rise(),
                    a.fan() * 100, a.bow() * t.bow()));
        }
        out.add("§7── 눈: 계열마다 참격인가 찌르기인가 ──");
        for (String line : engine.slashEyeReport()) {
            out.add((line.contains("✖") ? "§c" : line.contains("면제") ? "§8" : "§a") + line);
        }
        out.add("§7지금 든 손: §f" + weaponClass + "§7 — 순번은 연타할 때마다 돈다 "
                + "§8(입력 버퍼 없음 · 우클릭·웅크림·달림의 뜻은 그대로다)");
        out.add("§7밀고 당기기: §f/혼천 스윙 <호|높이|활|전진> <값>§7 — 밀면 §f즉시 획을 한 번 긋는다");
        out.add("§7그냥 그려 보기: §f/혼천 스윙 그려 [횡_좌우|횡_우좌|올려베기|내려베기]");
        out.add("§7되돌리기: §f/혼천 스윙 되돌려§7 · 등록부에 적기: §f/혼천 스윙 적기");
        return out;
    }

    List<String> swingYaml() {
        SkillEngine.SwingTuning t = tuning();
        List<String> out = new ArrayList<>();
        out.add("§6── config/skill_motion.yml · display.swing_arcs.tuning 에 붙일 줄 ──");
        out.add(String.format("§f    tuning:"));
        out.add(String.format("§f      arc_scale: %.2f", t.arc()));
        out.add(String.format("§f      rise_scale: %.2f", t.rise()));
        out.add(String.format("§f      bow_scale: %.2f", t.bow()));
        out.add(String.format("§f      lunge_scale: %.2f", t.lunge()));
        out.add("§7(적고 나서 §fpython3 tools/motion_audit.py§7 — 축 ⑬ 이 참격/찌르기를 다시 잰다)");
        return out;
    }

    /**
     * <b>파티클이 훑는 길</b> — 획과 <b>같은 등록부</b>를 읽는다 (두 층이 갈라지면 그림이 거짓말한다).
     *
     * <p><b>【보이는 것 = 맞는 것】</b> 수평각은 <b>히트박스 부채꼴 안</b>에 있다 ({@code fan ≤ 1}) —
     * 그리는 각이 맞는 각이다. 높이는 자유다: 호 히트박스는 {@code arcTargets} 가 <b>수평각만</b> 재고
     * 높이를 안 보기 때문이다 (기둥이다). 그래서 <b>오르내림·활</b>은 판정에 대해 거짓말하지 않는다.
     *
     * @return 시전자의 <b>발</b>을 원점으로 한 상대 좌표들 — 훑는 <b>순서대로</b>
     */
    List<Vector> sweepPath(String strokeId, Vector flat, double radius, double angle, int points) {
        SkillEngine.SwingArc a = arcOf(strokeId, angle);
        SkillEngine.SwingTuning t = tuning();
        SkillEngine.StrokeOrigin o = originOf("참격_호");
        double half = Math.max(1.0, angle / 2.0) * (a == null ? 1.0 : a.fan());
        double from = a == null ? -half : Math.signum(a.yaw()[1] - a.yaw()[0]) >= 0 ? -half : half;
        double to = -from;
        List<Vector> path = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double u = points == 1 ? 0.5 : i / (double) (points - 1);
            double deg = from + (to - from) * u;
            double bow = a == null ? 0.0 : a.bow() * t.bow() * 4.0 * u * (1.0 - u);   // 중간이 부푼다 = 호
            double rise = a == null ? 0.0
                    : (a.rise()[0] + (a.rise()[1] - a.rise()[0]) * u) * t.rise();
            // rotateAroundY(+θ) 는 앞(+Z)을 왼쪽(+X)으로 돌린다 — 획의 국소 yaw 와 **같은 부호**다
            Vector v = flat.clone().rotateAroundY(Math.toRadians(deg)).multiply(radius);
            path.add(v.setY(o.height() + rise + bow));   // y = 발에서 잰 절대 높이
        }
        return path;
    }

    /** 맞춘 값을 등록부에 <b>사람이 적을</b> 수 있게 뽑는다 (코드가 등록부를 고치지 않는다) */
    List<String> originYaml() {
        List<String> out = new ArrayList<>();
        out.add("§6── config/skill_motion.yml · display.stroke_origin 에 붙일 줄 ──");
        for (SkillEngine.DisplayMotion m : engine.slashMotions()) {
            SkillEngine.StrokeOrigin o = originOf(m.id());
            out.add(String.format("§f    %s:  { forward: %.2f, height: %.2f, lateral: %.2f%s }",
                    m.id(), o.forward(), o.height(), o.lateral(),
                    o.centered() ? ", centered: true" : ""));
        }
        out.add("§7(적고 나서 §fpython3 tools/motion_audit.py§7 를 돌려라 — 몸 안이면 ⑧이 잡는다)");
        return out;
    }

    // ══════════ 진단 — 인게임에 못 들어가는 눈을 위한 창구 ══════════

    private void note(String kind, String detail) {
        log.add(new Log(tick, kind, detail));
    }

    /**
     * 최근 {@code seconds} 초 동안 <b>실제로 뜬</b> 형체를 센다 — {@code /혼천 모션진단} 의 입력.
     *
     * <p>인게임에 들어갈 수 없는 눈(RCON·콘솔)이 "3D 획이 정말 떴는가"를 확인하는 유일한 길이다.
     * 예산 강등으로 <b>거절된 수</b>도 함께 보인다 — <b>안 뜬 것과 못 뜬 것은 다른 사건이다</b>.
     */
    List<String> diagnostics(int seconds) {
        long since = tick - Math.max(1, seconds) * 20L;
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> recent = new ArrayList<>();
        for (Log l : log) {
            if (l.tick() < since) {
                continue;
            }
            counts.merge(l.kind(), 1, Integer::sum);
            recent.add(String.format("  %5.1f초 전  [%s] %s", (tick - l.tick()) / 20.0,
                    l.kind(), l.detail()));
        }
        SkillEngine.DisplayBudget b = engine.displayBudget();
        int online = plugin.getServer().getOnlinePlayers().size();
        List<String> out = new ArrayList<>();
        out.add("── 무공 3D 모션 진단 (최근 " + seconds + "초) ──");
        out.add("살아 있는 형체 " + live() + "개  (강등선 " + b.degradeAt()
                + " · 전역 상한 " + b.globalCap() + ")");
        out.add("팩을 받은 눈 " + packed.size() + "명 / 접속 " + online + "명"
                + (packed.isEmpty() && online > 0
                        ? "  ← 팩 없음: 참격선·날의 기는 안 뜬다 (파티클로 읽힌다 — 설계대로다)" : ""));
        out.add("예산 강등(거절) 누적 " + denied + "회");
        if (counts.isEmpty()) {
            out.add("발행 없음 — 이 창 안에 뜬 형체가 하나도 없다");
            out.add("  · 병기를 들고 좌클릭했는가? (활·무관·짐승 계열은 획이 없다 — 등록부대로다)");
            out.add("  · 팩을 받았는가? 참격선 모델은 팩이 굽는다 (팩이 없으면 파티클만 — 정상이다)");
        } else {
            out.add("발행: " + counts);
            int show = Math.min(12, recent.size());
            out.addAll(recent.subList(recent.size() - show, recent.size()));
        }
        return out;
    }
}
