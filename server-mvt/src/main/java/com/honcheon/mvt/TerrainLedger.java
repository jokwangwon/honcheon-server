package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;
import org.bukkit.block.BlockFace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 지형 원장 — <b>땅은 한 번만 선다.</b>
 *
 * <p>사용자 판정 (이것이 헌법이다):
 * <blockquote>
 * "지형에 맞게 건축물이 재구축되어야 하고 <b>건축이 수정되어도 지형이 변화하면 안 됩니다.</b>
 *  <b>땅에 맞게 건물이 올라가는 것이지 건축에 맞게 지형 생기는 게 아니기 때문입니다.</b>"
 * </blockquote>
 *
 * <p>그런데 지금 구조는 <b>건물을 다시 지으려면 땅을 다시 빚는 수밖에 없다</b>:
 * <pre>   prepare(빚는다) → carve(판다) → digCave(판다) → build(짓는다)   ← 한 세션</pre>
 * 그리고 땅을 다시 빚으면 <b>지난번에 우리가 만든 것 위에 또 만든다</b> —
 * 재조성이 <b>산 위에 산을 쌓던 병</b>이 여기서 나왔다.
 *
 * <p><b>이 원장이 그 고리를 끊는다.</b> 땅이 이미 섰으면 {@link Terraform} 은 <b>측량만 한다</b>.
 *
 * <h2>왜 좌표까지 적는가</h2>
 * 높이만 적으면 <b>재조성마다 부지가 옮겨 앉는다</b> — 우리가 판 강이 다음 부지 탐색의 답을 바꾸기 때문이다
 * (땅이 물을 얻으면 {@code fit()} 의 점수가 달라진다). <b>땅의 자리는 땅이 정한 그 순간 굳는다.</b>
 *
 * <p><b>하지(河誌)</b>({@code rivers_built.yml})가 이미 이 문법이다 — 판 자리를 적고 검수가 읽는다.
 * <b>같은 문법으로</b> 적는다.
 */
final class TerrainLedger {

    private TerrainLedger() {
    }

    /**
     * 한 땅의 기록. <b>이것이 있으면 그 땅은 이미 빚어졌다.</b>
     *
     * @param caveMouthY 굴이 없으면 {@link Integer#MIN_VALUE}
     */
    record Land(String placeId, int cx, int cz, int radius, int groundY,
                int peakX, int peakZ, int peakY,
                String caveKind, int caveMouthX, int caveMouthY, int caveMouthZ, String caveInto,
                int caveRoomX, int caveRoomY, int caveRoomZ, int caveCarved) {

        boolean hasCave() {
            return caveKind != null && !caveKind.isBlank() && caveMouthY != Integer.MIN_VALUE;
        }
    }

    private static Path file;
    private static final Map<String, Land> lands = new LinkedHashMap<>();

    static void load(Path dataDir) {
        lands.clear();
        file = dataDir == null ? null : dataDir.resolve("terrain_built.yml");
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        Map<String, Object> root = RulesConfig.load(file);
        for (Map.Entry<String, Object> e : root.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> m)) {
                continue;
            }
            String id = String.valueOf(e.getKey());
            lands.put(id, new Land(id,
                    i(m, "x"), i(m, "z"), i(m, "radius"), i(m, "ground_y"),
                    i(m, "peak_x"), i(m, "peak_z"), i(m, "peak_y"),
                    m.get("cave_kind") == null ? null : String.valueOf(m.get("cave_kind")),
                    i(m, "cave_x"), m.get("cave_y") == null ? Integer.MIN_VALUE : i(m, "cave_y"), i(m, "cave_z"),
                    m.get("cave_into") == null ? "NORTH" : String.valueOf(m.get("cave_into")),
                    i(m, "cave_room_x"), i(m, "cave_room_y"), i(m, "cave_room_z"), i(m, "cave_carved")));
        }
    }

    private static int i(Map<?, ?> m, String k) {
        return m.get(k) instanceof Number n ? n.intValue() : 0;
    }

    /** <b>이 땅은 이미 빚어졌는가.</b> 참이면 다시 빚지 않는다 */
    static boolean forged(String placeId) {
        return lands.containsKey(placeId);
    }

    static Land land(String placeId) {
        return lands.get(placeId);
    }

    /** 땅을 빚었다고 적는다 — <b>이 줄이 적히는 순간 그 땅은 굳는다</b> */
    static void remember(TerrainForge.SiteSpec spec, TerrainForge.CaveSpec cave) {
        lands.put(spec.placeId(), new Land(spec.placeId(),
                spec.cx(), spec.cz(), spec.radius(), spec.groundY(),
                spec.peakX(), spec.peakZ(), spec.peakY(),
                cave == null ? null : cave.kind().name(),
                cave == null ? 0 : cave.mouthX(),
                cave == null ? Integer.MIN_VALUE : cave.mouthY(),
                cave == null ? 0 : cave.mouthZ(),
                cave == null ? "NORTH" : cave.into().name(),
                cave == null ? 0 : cave.roomX(),
                cave == null ? 0 : cave.roomY(),
                cave == null ? 0 : cave.roomZ(),
                cave == null ? 0 : cave.carved()));
        save();
    }

    /** 이 땅을 잊는다 — <b>다음 조성이 다시 빚는다</b> (땅을 갈아엎겠다는 뜻이다. 함부로 쓰지 말 것) */
    static boolean forget(String placeId) {
        boolean had = lands.remove(placeId) != null;
        save();
        return had;
    }

    static java.util.Set<String> ids() {
        return java.util.Set.copyOf(lands.keySet());
    }

    /** 굴을 원장에서 되살린다 — <b>다시 파지 않는다</b> (같은 굴을 두 번 파면 그것도 땅을 바꾸는 것이다) */
    static TerrainForge.CaveSpec cave(Land l, String world) {
        if (l == null || !l.hasCave()) {
            return null;
        }
        return new TerrainForge.CaveSpec(l.placeId(),
                TerrainForge.CaveKind.valueOf(l.caveKind()), world,
                l.caveMouthX(), l.caveMouthY(), l.caveMouthZ(), BlockFace.valueOf(l.caveInto()),
                l.caveRoomX(), l.caveRoomY(), l.caveRoomZ(), l.caveCarved());
    }

    private static void save() {
        if (file == null) {
            return;
        }
        try {
            List<String> out = new ArrayList<>();
            out.add("# 지형 원장 — **땅은 한 번만 선다.** 여기 적힌 땅은 다시 빚지 않는다.");
            out.add("# 손으로 지우면 다음 조성이 그 땅을 **갈아엎는다** (산 위에 산을 쌓지 않도록 조심하라).");
            for (Land l : lands.values()) {
                out.add(l.placeId() + ":");
                out.add("  x: " + l.cx() + "\n  z: " + l.cz() + "\n  radius: " + l.radius()
                        + "\n  ground_y: " + l.groundY()
                        + "\n  peak_x: " + l.peakX() + "\n  peak_z: " + l.peakZ() + "\n  peak_y: " + l.peakY());
                if (l.hasCave()) {
                    out.add("  cave_kind: " + l.caveKind() + "\n  cave_x: " + l.caveMouthX()
                            + "\n  cave_y: " + l.caveMouthY() + "\n  cave_z: " + l.caveMouthZ()
                            + "\n  cave_into: " + l.caveInto()
                            + "\n  cave_room_x: " + l.caveRoomX() + "\n  cave_room_y: " + l.caveRoomY()
                            + "\n  cave_room_z: " + l.caveRoomZ() + "\n  cave_carved: " + l.caveCarved());
                }
            }
            Files.write(file, out);
        } catch (IOException ignored) {
            // 못 적었으면 다음 조성이 땅을 다시 빚는다 — 그건 사고지만 조용한 사고는 아니다 (검수가 잡는다)
        }
    }
}
