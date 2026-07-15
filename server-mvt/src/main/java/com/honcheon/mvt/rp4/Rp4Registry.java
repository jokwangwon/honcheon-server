package com.honcheon.mvt.rp4;

import com.honcheon.core.rules.RulesConfig;
import org.bukkit.Material;
import org.joml.Vector3f;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RP-4 데모의 등록부 판독 — {@code config/mob_models.yml} 의 호랑이 절과
 * {@code performance.yml load_test.combat_cluster_size} 를 읽는다.
 *
 * <p><b>왜 MobDisplay 의 판독을 다시 쓰나.</b> {@code com.honcheon.mvt.MobDisplay} 는
 * package-private 이고, RP-4 파일럿 규약은 <b>기존 파일 수정 금지</b>다 — 새 패키지에서
 * 그 판독기를 부를 길이 없다. 그래서 데모가 필요한 최소 절만 <b>같은 문법으로</b> 다시 읽는다.
 * 수치의 정본은 여전히 등록부 하나다 (이 클래스는 기본값까지 MobDisplay 와 같은 값을 쓴다) —
 * 두 판독이 갈라지면 그것은 등록부가 아니라 판독의 병이고, 파일럿이 끝나면 이 사본은
 * 채택안과 함께 정리된다 (임시 무대 장치다. docs/design/rp4_pilot.md §8).
 */
final class Rp4Registry {

    /** 한 조각 — MobDisplay.Part 와 같은 절을 읽는다 (parts / lod_parts 공용 문법) */
    record Part(String id, String role, String key, Material base,
                Vector3f size, Vector3f offset,
                float headYawMax, float headPitchMax, float swayDeg,
                float phaseDeg, float attackRaiseDeg) {
    }

    /** 움직임의 표정 — MobDisplay.Motion 과 같은 절 (5동작의 눈금 전부가 여기 있다) */
    record Motion(float walkBob, float walkBobRate, float walkRoll,
                  float attackLean, int attackLeanTicks,
                  float chargeLean, double chargeSpeedFull,
                  float hurtRecoil, int hurtTicks,
                  float idleBreath, float idleRate,
                  float deathTopple, float deathSink, int deathTicks) {
    }

    /** 등록부의 한 줄 — 데모는 본체(body)·게이트를 안 쓰므로 형체 절만 남긴다 */
    record Model(String id, String name, List<Part> parts, List<Part> lodParts,
                 Motion motion, float viewRange) {
    }

    /** 추종 보간 눈금 — budget.follow 절 (0 이면 형체가 튄다 — MobDisplay 와 같은 이유) */
    record Follow(int teleportDuration, int interpolation, double moveEpsilon, double yawEpsilon) {
    }

    /** 데모가 먹는 판 전부 — 모델 + 보간 + 군집 눈금(load_test.combat_cluster_size) */
    record Sheet(Model model, Follow follow, int clusterSize) {
    }

    private Rp4Registry() {
    }

    /** 판독 — 등록부가 없거나 호랑이 절이 없으면 {@code null} (데모는 조용히 서지 않는다) */
    @SuppressWarnings("unchecked")
    static Sheet load(Path cfg) {
        Map<String, Object> root;
        try {
            root = RulesConfig.load(cfg.resolve("mob_models.yml"));
        } catch (RuntimeException missing) {
            return null;
        }
        if (root == null) {
            return null;
        }
        Map<String, Object> b = sub(root, "budget");
        Map<String, Object> f = sub(b, "follow");
        Follow follow = new Follow(
                (int) num(f.get("teleport_duration"), 2),
                (int) num(f.get("interpolation"), 3),
                num(f.get("move_epsilon"), 0.02),
                num(f.get("yaw_epsilon"), 2.0));
        float viewRange = (float) num(b.get("view_range"), 0.75);

        Object raw = sub(root, "foes").get("horangi");
        if (!(raw instanceof Map<?, ?> entry)) {
            return null;   // 파일럿의 기준 원본은 호랑이다 — 그 절이 없으면 무대가 없다
        }
        Model model = parse((Map<String, Object>) entry, viewRange);
        if (model == null || model.parts().isEmpty()) {
            return null;
        }

        int cluster = 20;
        try {
            Map<String, Object> perf = RulesConfig.load(cfg.resolve("performance.yml"));
            cluster = (int) num(sub(perf, "load_test").get("combat_cluster_size"), 20);
        } catch (RuntimeException ignored) {
            // 부하 눈금이 없으면 rp4_pilot.md §5.1 이 부르는 값(20)이 남는다
        }
        return new Sheet(model, follow, cluster);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> root, String key) {
        return root != null && root.get(key) instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
    }

    private static Model parse(Map<String, Object> e, float defaultViewRange) {
        List<Part> parts = partList(e.get("parts"));
        List<Part> lodParts = partList(e.get("lod_parts"));
        Map<String, Object> mo = sub(e, "motion");
        Motion motion = new Motion(
                (float) num(mo.get("walk_bob"), 0),
                (float) num(mo.get("walk_bob_rate"), 2.5),
                (float) num(mo.get("walk_roll_deg"), 0),
                (float) num(mo.get("attack_lean_deg"), 0),
                (int) num(mo.get("attack_lean_ticks"), 6),
                (float) num(mo.get("charge_lean_deg"), 0),
                num(mo.get("charge_speed_full"), 0.3),
                (float) num(mo.get("hurt_recoil_deg"), 0),
                (int) num(mo.get("hurt_ticks"), 4),
                (float) num(mo.get("idle_breath"), 0),
                (float) num(mo.get("idle_rate"), 0),
                (float) num(mo.get("death_topple_deg"), 90),
                (float) num(mo.get("death_sink"), 0.3),
                (int) num(mo.get("death_ticks"), 24));
        return new Model("horangi",
                String.valueOf(e.getOrDefault("name", "horangi")),
                List.copyOf(parts), List.copyOf(lodParts), motion,
                (float) num(e.get("view_range"), defaultViewRange));
    }

    @SuppressWarnings("unchecked")
    private static List<Part> partList(Object raw) {
        List<Part> parts = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object row : list) {
                if (!(row instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> p = (Map<String, Object>) m;
                Material base = Material.matchMaterial(String.valueOf(p.getOrDefault("base", "LEATHER")));
                if (base == null || p.get("key") == null) {
                    continue;
                }
                parts.add(new Part(
                        String.valueOf(p.getOrDefault("id", "body")),
                        String.valueOf(p.getOrDefault("role", "body")),
                        String.valueOf(p.get("key")),
                        base,
                        vec(p.get("size")),
                        vec(p.get("offset"), 0, 0, 0),
                        (float) num(p.get("head_yaw_max"), 0),
                        (float) num(p.get("head_pitch_max"), 0),
                        (float) num(p.get("sway_deg"), 0),
                        (float) num(p.get("phase_deg"), 0),
                        (float) num(p.get("attack_raise_deg"), 0)));
            }
        }
        return parts;
    }

    private static double num(Object v, double fallback) {
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private static Vector3f vec(Object v) {
        return vec(v, 1, 1, 1);
    }

    private static Vector3f vec(Object v, float x, float y, float z) {
        if (v instanceof List<?> l && l.size() == 3) {
            return new Vector3f((float) num(l.get(0), x), (float) num(l.get(1), y), (float) num(l.get(2), z));
        }
        return new Vector3f(x, y, z);
    }
}
