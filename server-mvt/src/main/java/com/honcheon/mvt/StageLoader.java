package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * ★B-194 무대 로더 — 층별 도면({@code config/stages/<이름>.stage.yml})을 블록으로 찍는다.
 *
 * <p><b>도면이 좌표의 정본이다.</b> 설계·검수는 {@code tools/stage_render.py} 가 한다 —
 * 조성 전에 그림으로 보고 확정하는 것이 이 파이프라인의 뜻이다 (사용자 2026-07-31:
 * 「사진 찍고 좌표 찍는 방식으론 원하는 느낌이 안 나온다」).
 *
 * <p>멱등: 같은 도면 → 언제나 같은 무대. <b>air 도 찍는다</b> — 도면 범위 안은 도면이 전부다
 * (다시 찍으면 손댄 것이 되돌아간다). 도면 밖은 건드리지 않는다.
 */
final class StageLoader {

    /** 한 무대 — 도면의 순수 데이터 (월드 무관) */
    record Stage(String id, String name, int ox, String oyToken, int oz, int width, int depth,
                 List<String[][]> layers, Map<String, int[]> spots) {
    }

    private StageLoader() {
    }

    @SuppressWarnings("unchecked")
    static Stage load(Path configDir, String id) {
        Map<String, Object> root = RulesConfig.load(configDir.resolve("stages").resolve(id + ".stage.yml"));
        Map<String, Object> meta = (Map<String, Object>) root.get("meta");
        List<Object> origin = (List<Object>) meta.get("origin");
        List<Object> size = (List<Object>) meta.get("size");
        int w = ((Number) size.get(0)).intValue();
        int d = ((Number) size.get(1)).intValue();
        Map<String, Object> legend = (Map<String, Object>) root.get("legend");
        List<String[][]> layers = new ArrayList<>();
        for (Map.Entry<String, Object> e : new TreeMap<>((Map<String, Object>) root.get("layers")).entrySet()) {
            String[] rows = String.valueOf(e.getValue()).stripTrailing().split("\n");
            if (rows.length != d) {
                throw new IllegalStateException("도면 " + id + " " + e.getKey() + " — 행 " + rows.length + " ≠ " + d);
            }
            String[][] grid = new String[d][w];
            for (int r = 0; r < d; r++) {
                if (rows[r].length() != w) {
                    throw new IllegalStateException("도면 " + id + " " + e.getKey() + " r" + r
                            + " — 폭 " + rows[r].length() + " ≠ " + w);
                }
                for (int c = 0; c < w; c++) {
                    Object mat = legend.get(String.valueOf(rows[r].charAt(c)));
                    if (mat == null) {
                        throw new IllegalStateException("도면 " + id + " — 범례 밖 문자: " + rows[r].charAt(c));
                    }
                    grid[r][c] = String.valueOf(mat);
                }
            }
            layers.add(grid);
        }
        Map<String, int[]> spots = new LinkedHashMap<>();
        ((Map<String, Object>) root.getOrDefault("spots", Map.of())).forEach((k, v) -> {
            List<Object> cr = (List<Object>) v;
            spots.put(k, new int[]{((Number) cr.get(0)).intValue(), ((Number) cr.get(1)).intValue()});
        });
        return new Stage(id, String.valueOf(meta.get("name")),
                ((Number) origin.get(0)).intValue(), String.valueOf(origin.get(1)),
                ((Number) origin.get(2)).intValue(), w, d, layers, spots);
    }

    /** 도면의 바닥 y — "sea_level" 은 실제 수면(seaTop) 으로 푼다 (FLAT 월드의 getSeaLevel 은 거짓말한다) */
    static int originY(Stage s, World w, Voyage voyage) {
        return "sea_level".equals(s.oyToken()) ? voyage.seaTop(w) : Integer.parseInt(s.oyToken());
    }

    /** 무대를 찍는다 — 도면 그대로, 멱등. 바닥 밑 2겹은 흙 기초 (밤바다 위에 뜬 땅이 물을 안 비치게) */
    static void build(Stage s, World w, int oy) {
        String[][] floor = s.layers().get(0);
        for (int r = 0; r < s.depth(); r++) {
            for (int c = 0; c < s.width(); c++) {
                if (!"air".equals(floor[r][c])) {
                    w.getBlockAt(s.ox() + c, oy - 1, s.oz() + r).setType(Material.DIRT, false);
                    w.getBlockAt(s.ox() + c, oy - 2, s.oz() + r).setType(Material.DIRT, false);
                }
            }
        }
        for (int y = 0; y < s.layers().size(); y++) {
            String[][] grid = s.layers().get(y);
            for (int r = 0; r < s.depth(); r++) {
                for (int c = 0; c < s.width(); c++) {
                    stamp(w, s.ox() + c, oy + y, s.oz() + r, grid[r][c]);
                }
            }
        }
    }

    private static void stamp(World w, int x, int y, int z, String mat) {
        if (mat.indexOf('[') >= 0 || mat.indexOf(':') >= 0) {
            BlockData d = Bukkit.createBlockData(mat);
            w.getBlockAt(x, y, z).setBlockData(d, false);
            return;
        }
        Material m = Material.matchMaterial(mat.toUpperCase());
        if (m == null) {
            throw new IllegalStateException("도면의 재질을 모른다: " + mat);
        }
        w.getBlockAt(x, y, z).setType(m, false);
    }

    /** 자리 — 도면 spots 의 [col,row] 를 월드 좌표로 (바닥 위 한 칸, 칸 중앙) */
    static Location spot(Stage s, World w, int oy, String name) {
        int[] cr = s.spots().get(name);
        if (cr == null) {
            throw new IllegalStateException("도면 " + s.id() + " 에 자리가 없다: " + name);
        }
        return new Location(w, s.ox() + cr[0] + 0.5, oy + 1, s.oz() + cr[1] + 0.5);
    }
}
