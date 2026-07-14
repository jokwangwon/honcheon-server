package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 지형의 관문 — <b>땅은 건축의 허락을 받고 서지 않는다.</b>
 *
 * <p>정본: {@code docs/design/gate_and_watertown.md} (사용자가 직접 쓴 계약, 2026-07-14).
 * <blockquote><b>"건축의 미결은 건축만 막을 수 있다. 건축의 미결이 땅을 막아서는 안 된다."</b></blockquote>
 *
 * <p><b>구조가 거꾸로 서 있었다.</b> {@code /혼천 지역조성} 은 {@link RemoteBuilder#unbuildableReasons}
 * 로 <b>먼저 거절하고 돌아섰다</b> — 그래서 집의 등급(commercial_class)이 미결이면 <b>땅조차 안 빚어졌다.</b>
 * 강남이 그 인질이었다: 물길도 지형도 등록부에 적혀 있는데 <b>등급 한 줄 때문에</b> 땅이 못 섰다.
 *
 * <p>이제 순서는 이렇다:
 * <pre>
 *   ① 지형 게이트 → ② 지형 조성 → ③ 영수증 → ④ 건축 게이트 → ⑤ 건축 / ⑥ 땅만 남기고 사유 출력
 * </pre>
 * 그리고 <b>이 문은 땅의 일만 묻는다</b>: 좌표 · 프로파일 · <b>그 프로파일의 구현</b> ·
 * <b>TerrainForge 판(版)의 승인</b> · 이미 선 땅인가. 건축 원형·규모·{@code build_radius} 는
 * <b>여기서 묻지 않는다.</b>
 *
 * <h2>★★ 승인 (forge_version)</h2>
 * <b>땅은 한 번만 선다.</b> 그러므로 <b>나쁜 판(版)으로 빚은 땅은 영원히 나쁘다.</b> 점묘판(3)으로
 * 빚힌 땅이 이미 있고, 점묘를 고친 판(4)이 지금 여기 있다 — 그러나 <b>사람이 눈으로 보고 승인하기
 * 전까지는 월드에 쓰지 않는다</b> ({@code mode_when_unapproved: preview}).
 * 승인은 {@code config/terrain_gate.yml} 의 {@code approved_forge_version} 한 줄이다.
 */
final class TerrainGate {

    private TerrainGate() {
    }

    /** 무엇을 할 것인가 — 월드에 <b>쓴다</b>(commit) · <b>안 쓴다</b>(preview: 검사와 예정만 말한다) */
    enum Mode {
        commit, preview
    }

    /**
     * 관문의 판정.
     *
     * @param state   {@code ready} (빚어도 된다) · {@code pending} (아직) · {@code committed} (이미 섰다)
     * @param reason  {@code pending} 일 때의 사유 — 기계가 읽는 낱말 (계약의 어휘 그대로)
     * @param mode    {@code commit} 이면 월드에 쓴다. {@code preview} 면 <b>한 블록도 안 건드린다</b>
     * @param notes   사람에게 하는 말 (조용히 넘어가지 않는다)
     */
    record Verdict(String state, String reason, Mode mode, List<String> notes) {

        boolean forgeable() {
            return "ready".equals(state);
        }

        boolean writes() {
            return forgeable() && mode == Mode.commit;
        }
    }

    private static int forgeVersion = 4;
    private static int approvedVersion = 0;
    private static Mode unapprovedMode = Mode.preview;
    private static Set<String> implemented = new LinkedHashSet<>(
            List.of("봉우리", "중턱단", "들", "사막", "그대로"));
    private static Map<String, String> pending = new LinkedHashMap<>();
    private static String receiptsFile = "terrain_receipts.yml";
    private static Path receipts;

    /** 이미 커밋된 땅 — 영수증이 있는 곳 (땅은 한 번만 선다) */
    private static final Map<String, Map<String, Object>> committed = new LinkedHashMap<>();

    /** {@code config/terrain_gate.yml} — 없어도 돈다 (그때는 <b>승인이 없는 것</b>으로 본다: preview) */
    @SuppressWarnings("unchecked")
    static void load(Path configDir, Path dataDir) {
        Path file = configDir.resolve("terrain_gate.yml");
        if (Files.isRegularFile(file)) {
            Map<String, Object> root = RulesConfig.load(file);
            if (root != null) {
                forgeVersion = num(root.get("forge_version"), forgeVersion);
                approvedVersion = num(root.get("approved_forge_version"), approvedVersion);
                unapprovedMode = "commit".equals(String.valueOf(root.get("mode_when_unapproved")))
                        ? Mode.commit : Mode.preview;
                if (root.get("profiles_implemented") instanceof List<?> l) {
                    implemented = new LinkedHashSet<>();
                    l.forEach(p -> implemented.add(String.valueOf(p)));
                }
                pending = new LinkedHashMap<>();
                if (root.get("pending") instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> pending.put(String.valueOf(k), String.valueOf(v)));
                }
                if (root.get("receipts") != null) {
                    receiptsFile = String.valueOf(root.get("receipts"));
                }
            }
        }
        receipts = dataDir == null ? null : dataDir.resolve(receiptsFile);
        committed.clear();
        if (receipts != null && Files.isRegularFile(receipts)) {
            Map<String, Object> root = RulesConfig.load(receipts);
            if (root != null) {
                root.forEach((id, v) -> {
                    if (v instanceof Map) {
                        committed.put(id, (Map<String, Object>) v);
                    }
                });
            }
        }
    }

    static int forgeVersion() {
        return forgeVersion;
    }

    static boolean approved() {
        return approvedVersion == forgeVersion;
    }

    /** 이 땅은 이미 커밋되었는가 — 영수증이 있다 (일반 명령으로 다시 조성하지 않는다) */
    static boolean committed(String placeId) {
        return committed.containsKey(placeId);
    }

    /**
     * <b>지형 게이트.</b> 땅의 일만 묻는다 — 건축은 한 마디도 묻지 않는다.
     *
     * @param forgeRadius 지형 반경 (world_map.yml §1-b land.forge_radius)
     */
    static Verdict judge(WorldMap.Place place, int forgeRadius) {
        List<String> notes = new ArrayList<>();

        // ① 이미 선 땅인가 — 영수증 또는 지형 원장 (땅은 한 번만 선다)
        if (committed(place.id()) || TerrainLedger.forged(place.id())) {
            notes.add("이미 빚어진 땅이다 — 다시 빚지 않는다 "
                    + "(갈아엎으려면 /혼천 땅갈아엎기 " + place.id() + ")");
            return new Verdict("committed", null, Mode.preview, notes);
        }

        // ② 좌표가 확정되어 있는가
        if (place.x() == 0 && place.z() == 0) {
            notes.add("좌표가 확정되지 않았다 (world_map.yml)");
            return new Verdict("pending", "terrain_coordinates_unresolved", Mode.preview, notes);
        }

        // ③ 조성에 필요한 입력이 있는가
        if (forgeRadius <= 0) {
            notes.add("지형 반경이 없다 (world_map.yml §1-b land.forge_radius)");
            return new Verdict("pending", "terrain_input_missing", Mode.preview, notes);
        }

        // ④ 프로파일이 확정되어 있고, **그 구현이 끝났는가**
        //    ★ 여태 terrain.yml 이 모르는 윤곽을 적으면 **조용히 넘어갔다** ("모르는 윤곽 — 조용히 넘긴다").
        //      그러면 「수향」이라 적힌 강남이 **들판으로** 서 버린다. 그것은 침묵이지 판정이 아니다.
        String want = TerrainForge.requestedProfile(place);   // 등록부가 적은 이름 (없으면 지형이 읽는다)
        if (want == null || want.isBlank()) {
            notes.add("지형 프로파일이 확정되지 않았다 (terrain.yml shaping:)");
            return new Verdict("pending", "terrain_profile_unresolved", Mode.preview, notes);
        }
        if (!implemented.contains(want)) {
            notes.add("「" + want + "」 프로파일은 **아직 구현되지 않았다** "
                    + "(terrain_gate.yml profiles_implemented: " + implemented + ")");
            return new Verdict("pending", "terrain_profile_unresolved", Mode.preview, notes);
        }

        // ⑤ 사람이 못 박은 보류
        String why = pending.get(place.id());
        if (why != null) {
            notes.add("사람이 보류했다 — " + why + " (config/terrain_gate.yml pending:)");
            return new Verdict("pending", why, Mode.preview, notes);
        }

        // ⑥ ★★ 이 판(版)은 승인되었는가 — **땅은 한 번만 선다**
        if (!approved()) {
            notes.add("TerrainForge 판 " + forgeVersion + " 이 **승인되지 않았다** (승인된 판: "
                    + approvedVersion + ")");
            notes.add("★ 땅은 한 번만 선다 — 승인 전에는 월드에 쓰지 않는다. "
                    + "조감으로 보고 좋으면 config/terrain_gate.yml 의 "
                    + "approved_forge_version 을 " + forgeVersion + " 으로 올려라");
            return new Verdict("pending", "terrain_forge_version_not_approved", unapprovedMode, notes);
        }
        return new Verdict("ready", null, Mode.commit, notes);
    }

    /**
     * ③ <b>조성 영수증</b> — 이 땅이 <b>언제·어느 판으로·어떤 입력으로</b> 섰는가.
     *
     * <p>계약의 모양 그대로 적는다 ({@code profile · forge_radius · forge_version · world_seed ·
     * location_key · input_hash · state}). 이 줄이 있으면 <b>일반 명령으로 다시 조성하지 않는다.</b>
     */
    static void receipt(WorldMap.Place place, int cx, int cz, int radius, long worldSeed) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("profile", TerrainForge.profile(place).name());
        r.put("forge_radius", radius);
        r.put("forge_version", forgeVersion);
        r.put("world_seed", worldSeed);
        r.put("location_key", place.id() + "@" + cx + "," + cz);
        r.put("input_hash", inputHash(place, cx, cz, radius, worldSeed));
        r.put("state", "committed");
        committed.put(place.id(), r);
        save();
    }

    /**
     * 입력의 지문 — <b>같은 입력이면 같은 땅이 나온다</b>(결정론)는 주장의 증거.
     * 입력이 바뀌었는데 영수증이 그대로면 그것은 거짓말이다 — 다음 검수가 이 값을 대조한다.
     */
    private static String inputHash(WorldMap.Place place, int cx, int cz, int radius, long seed) {
        long h = 1125899906842597L;
        for (char c : (place.id() + "|" + place.terrain() + "|" + TerrainForge.profile(place)
                + "|" + TerrainForge.surface(place) + "|" + cx + "," + cz + "|" + radius
                + "|" + seed + "|v" + forgeVersion).toCharArray()) {
            h = 31 * h + c;
        }
        return Long.toHexString(h);
    }

    private static void save() {
        if (receipts == null) {
            return;
        }
        List<String> out = new ArrayList<>();
        out.add("# 조성 영수증 — **땅은 한 번만 선다.** 여기 적힌 곳은 일반 명령으로 다시 조성하지 않는다.");
        out.add("# 계약: docs/design/gate_and_watertown.md");
        committed.forEach((id, r) -> {
            out.add(id + ":");
            r.forEach((k, v) -> out.add("  " + k + ": " + (v instanceof String s ? "\"" + s + "\"" : v)));
        });
        try {
            Files.createDirectories(receipts.getParent());
            Files.write(receipts, out);
        } catch (IOException e) {
            // 조용한 실패는 없다 — 영수증이 없으면 다음 조성이 이 땅을 다시 빚는다
            // (★ Bukkit.getLogger() 를 안 쓴다: 서버 없이 도는 눈에서 그것이 NPE 를 낸다)
            java.util.logging.Logger.getLogger("HoncheonMVT")
                    .warning("[지형/관문] 영수증을 못 적었다: " + e.getMessage());
        }
    }

    private static int num(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }
}
