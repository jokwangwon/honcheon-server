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
    private final SkillEngine engine;

    /** 팩을 받은 눈 — 이 집합에 없으면 폴백을 보거나, 아무것도 못 본다 (그 자리는 파티클이 지킨다) */
    private final Set<UUID> packed = new HashSet<>();
    private final List<Piece> pieces = new ArrayList<>();
    /** 지속 형체 — 몸 → (자리 → 형체). 심장박동으로 산다 (갱신이 끊기면 스스로 사라진다) */
    private final Map<UUID, Map<String, Persist>> persistent = new HashMap<>();
    private final Map<String, Material> materials = new HashMap<>();

    /** 진단 — 최근 발행 기록 ({@code /혼천 모션진단}). 인게임에 못 들어가는 눈을 위한 창구 */
    private final List<Log> log = new ArrayList<>();
    private int denied;
    private long tick;

    private record Log(long tick, String kind, String detail) {
    }

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
        // 참격선 — 그렸다가 꼬리부터 지운다
        float[] full;
        long fadeAt = -1;
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
        if (log.size() > 400) {
            log.subList(0, log.size() - 400).clear();
        }
    }

    /** 정리 (performance.yml effects.cleanup_on: death · quit · world_change) */
    void clear(UUID body) {
        Map<String, Persist> slots = persistent.remove(body);
        if (slots != null) {
            slots.values().forEach(p -> despawn(p.parts));
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
        Location at = ring
                ? caster.getLocation().add(0, 1.0, 0)
                : caster.getEyeLocation().subtract(0, 0.25, 0);
        at.setDirection(back ? flat.clone().multiply(-1) : flat);
        if (!ring) {
            at.setPitch(at.getPitch() + sw.tilt());   // MC 규약: pitch 양수 = 아래 (부는 내려찍는다)
        }

        Piece p = spawn(m, at, caster, 1, false);
        if (p == null) {
            return false;   // 강등 — 파티클이 그 자리를 지킨다 (강등이지 실종이 아니다)
        }
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
            transform(d, p.full, 0.0f, draw);         // 그린다 — 칼끝 뒤로 꼬리가 자란다
        }
        note("참격선", hitType + "·" + grade + "·" + weaponClass
                + " 길이" + String.format("%.1f", length) + "m 굵기" + String.format("%.2f", thick)
                + " 그리기" + draw + "틱");
        return true;
    }

    /** 지워진다 — <b>꼬리부터</b>. 획이 살짝 퍼지며(spread) 사라진다 (연기처럼) */
    private void erase(Piece p) {
        p.fading = true;
        float s = p.m.spread();
        for (Display d : p.parts) {
            // scale.x → 0 : 머리(원점)로 수축한다 = 꼬리가 먼저 사라진다
            transform(d, new float[]{0.001f, p.full[1] * s, p.full[2] * s}, 0.0f, p.m.fade());
        }
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

    /** 병기의 날이 있는 자리 — 눈에서 앞으로. 1인칭 시야에도 들어온다 */
    private static Location hand(LivingEntity body) {
        Vector dir = body.getEyeLocation().getDirection().normalize();
        Location at = body.getEyeLocation().add(dir.clone().multiply(0.55)).subtract(0, 0.15, 0);
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
                transform(d, sizeOf(m, variant), 0.0f, m.interpolation());
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
            transform(d, sizeUnit(m), 0.0f, Math.max(1, m.birth()));
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
                transform(d, burst, 0.0f, Math.max(1, p.m.impactTicks()));
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
        float[] bud = sizeUnit(m);
        float k = (float) Math.max(0.35, range * 0.25);
        for (int i = 0; i < 3; i++) {
            bud[i] *= k;
        }
        for (Display d : p.parts) {
            transform(d, bud, 0.0f, Math.max(1, startup));
        }
        note("오의", ultimateId + " 선딜" + startup + "틱 → " + range + "m 개화");
    }

    private void bloom(Piece p) {
        p.burstAt = -1;
        float[] open = sizeUnit(p.m);
        float k = (float) (p.burstRadius * p.m.burstScale());
        for (int i = 0; i < 3; i++) {
            open[i] *= k;
        }
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
     * <p><b>use_held</b> (던진 암기 · 이기어검) — 실은 것이 사람의 아이템 그 자체이므로 팩 유무가 그림을
     * 바꾸지 않는다. 엔티티 하나를 모두에게 보인다 (분기 없음 · 값도 절반).
     *
     * <p>그 밖 — 팩을 받은 눈과 못 받은 눈이 섞여 있으면 변주 2개를 띄우고 서로에게 숨긴다.
     * <b>폴백이 null 인 모델</b>(참격선 · 날의 기)은 팩 없는 눈에게 <b>아예 띄우지 않는다</b> —
     * 억지 폴백(검을 복제한 획)보다 파티클이 낫다.
     *
     * @return 예산이 없거나 볼 사람이 없으면 null
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
            if (stack == null) {
                return null;   // 빈손 — 던질 것도 날릴 것도 없다
            }
            if (!afford(units, ultimate) || (ownerId != null && !affordPlayer(ownerId, units))) {
                denied++;
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
                p.parts.add(create(at, m, packedItem, withPack));
            }
        }
        if (!without.isEmpty() && plainOk) {
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
            e.setBrightness(new Display.Brightness(m.blockLight(), m.skyLight()));
            e.setViewRange(b.viewRange());              // 32m — 파티클 cull_beyond 와 같은 눈금
            e.setInterpolationDelay(0);
            e.setInterpolationDuration(m.interpolation());
            e.setPersistent(false);                     // 저장되지 않는다 (유령의 첫 방벽)
            e.setVisibleByDefault(viewers == null);
            e.getPersistentDataContainer().set(KEY_VFX, PersistentDataType.BYTE, (byte) 1);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(0.001f, 0.001f, 0.001f), new Quaternionf()));   // 씨앗에서 자란다
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
            }
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

    /** 자전 — 축은 진행 방향(X). 모델 규약이 "획을 X 축에 눕힌다"인 이유가 여기 있다 */
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
