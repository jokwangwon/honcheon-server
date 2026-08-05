package com.honcheon.mvt.forge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★★ <b>설계도</b> — {@code config/blueprints/*.yml} 을 읽는 순수 자료 (월드 무관).
 *
 * <p><b>사용자 확정 (2026-08-05)</b>: 「레퍼런스를 토대로 <b>설계도를 그리고 그걸 바탕으로
 * 건축</b>하는 형태를 취해봅시다」. 그러므로 <b>도면이 좌표의 정본이다</b> — 코드 안의
 * 수치가 도면과 다르면 도면이 이긴다. (선례: {@code config/stages/geunal_bam.stage.yml} 의
 * 「★이 도면이 좌표의 정본이다」 — B-194 무대가 같은 계율로 섰다)
 *
 * <h2>왜 stage.yml 을 그대로 안 쓰고 확장했는가</h2>
 * {@code stage.yml} 은 <b>층별 ASCII</b>다 (y0/y1/… 각각 한 장). 30×22×10층이면 훌륭하지만
 * 산문 구역은 60×22×20층이라 그대로 쓰면 <b>1,300줄을 손으로 정렬</b>해야 하고 오타가 반드시
 * 난다. 한편 <b>건축은 대개 압출</b>이다 — 기둥은 위로 서고 벽은 위로 쌓인다. 그래서 갈랐다:
 * <ul>
 *   <li>{@code plan} — 평면 한 장 (1문자 = 1칸): <b>무엇이 어디 있는가</b></li>
 *   <li>{@code columns} — 문자 → y 단면 (아래→위): <b>그것이 위로 어떻게 서는가</b></li>
 *   <li>{@code roof} — 압출로 못 만드는 것: 코드의 지붕 문법(귀솟음·겹처마)을 부른다</li>
 * </ul>
 *
 * <p>★1문자 = 1칸이므로 <b>평면은 ASCII 만 쓴다</b> — 한글은 폭이 둘로 보여 정렬이 깨진다
 * (첫 판에서 실제로 「곁적」 두 글자가 한 칸을 차지해 폭이 어긋났다).
 */
public final class Blueprint {

    /** 한 켜 — 재료 한 종류가 몇 켜 (「air*6」 처럼 빈 켜도 센다: 개구를 비우는 데 쓴다) */
    public record Course(String material, int count) {
    }

    /** 지붕 한 채 — 압출이 못 만드는 것. box=[col0,row0,col1,row1] */
    public record Roof(String name, int[] box, int baseY, int eave, int upperWall, int upperEave) {
        public boolean hasUpper() {
            return upperWall > 0;
        }
    }

    private final String name;
    private final int pad;
    private final int width;
    private final int depth;
    private final int axisCol;
    private final char[][] plan;
    private final Map<Character, List<Course>> columns;
    private final List<Roof> roofs;
    private final Map<String, int[]> spots;

    private Blueprint(String name, int pad, int width, int depth, int axisCol,
                      char[][] plan, Map<Character, List<Course>> columns,
                      List<Roof> roofs, Map<String, int[]> spots) {
        this.name = name;
        this.pad = pad;
        this.width = width;
        this.depth = depth;
        this.axisCol = axisCol;
        this.plan = plan;
        this.columns = columns;
        this.roofs = roofs;
        this.spots = spots;
    }

    public String name() {
        return name;
    }

    public int pad() {
        return pad;
    }

    public int width() {
        return width;
    }

    public int depth() {
        return depth;
    }

    public int axisCol() {
        return axisCol;
    }

    public char at(int col, int row) {
        return plan[row][col];
    }

    public List<Course> columnOf(char ch) {
        return columns.getOrDefault(ch, List.of());
    }

    public List<Roof> roofs() {
        return roofs;
    }

    public Map<String, int[]> spots() {
        return spots;
    }

    /** 그 칸이 몇 켜 높이인가 (air 켜도 센다 — 도면의 총 높이) */
    public int heightAt(int col, int row) {
        int h = 0;
        for (Course c : columnOf(plan[row][col])) {
            h += c.count();
        }
        return h;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 읽기 — RulesConfig 가 푼 Map 을 받는다 (파일 입출력은 부르는 쪽)
    // ═══════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    public static Blueprint of(Map<String, Object> root) {
        Map<String, Object> meta = (Map<String, Object>) req(root, "meta");
        List<Object> size = (List<Object>) req(meta, "size");
        int w = ((Number) size.get(0)).intValue();
        int d = ((Number) size.get(1)).intValue();
        String nm = String.valueOf(meta.getOrDefault("name", "(이름 없음)"));
        int pad = ((Number) meta.getOrDefault("origin_pad", 1)).intValue();
        int axis = ((Number) meta.getOrDefault("axis_col", w / 2)).intValue();

        // 기둥 처방 — "재료*n" 을 켜 목록으로
        Map<Character, List<Course>> cols = new LinkedHashMap<>();
        ((Map<String, Object>) req(root, "columns")).forEach((k, v) -> {
            if (k.length() != 1) {
                throw new IllegalStateException("설계도 " + nm + " — 기둥 처방의 열쇠는 한 글자여야 한다: \"" + k
                        + "\" (한글은 폭이 둘로 보여 평면 정렬이 깨진다)");
            }
            List<Course> courses = new ArrayList<>();
            for (Object o : (List<Object>) v) {
                String s = String.valueOf(o).trim();
                int star = s.indexOf('*');
                courses.add(star < 0
                        ? new Course(s, 1)
                        : new Course(s.substring(0, star).trim(), Integer.parseInt(s.substring(star + 1).trim())));
            }
            cols.put(k.charAt(0), courses);
        });

        // 평면 — 폭·행 수를 자로 잰다 (어긋나면 여기서 죽는다: 조성 중에 죽는 것보다 낫다)
        String[] rows = String.valueOf(req(root, "plan")).stripTrailing().split("\n");
        if (rows.length != d) {
            throw new IllegalStateException("설계도 " + nm + " — 평면 행 " + rows.length + " ≠ 도면 깊이 " + d);
        }
        char[][] plan = new char[d][w];
        for (int r = 0; r < d; r++) {
            String line = rows[r].strip();
            if (line.length() != w) {
                throw new IllegalStateException("설계도 " + nm + " r" + r
                        + " — 폭 " + line.length() + " ≠ " + w);
            }
            for (int c = 0; c < w; c++) {
                char ch = line.charAt(c);
                if (!cols.containsKey(ch)) {
                    throw new IllegalStateException("설계도 " + nm + " r" + r + "c" + c
                            + " — 기둥 처방에 없는 문자: '" + ch + "'");
                }
                plan[r][c] = ch;
            }
        }

        List<Roof> roofs = new ArrayList<>();
        ((Map<String, Object>) root.getOrDefault("roof", Map.of())).forEach((rn, rv) -> {
            Map<String, Object> m = (Map<String, Object>) rv;
            List<Object> b = (List<Object>) req(m, "box");
            int[] box = new int[]{
                    ((Number) b.get(0)).intValue(), ((Number) b.get(1)).intValue(),
                    ((Number) b.get(2)).intValue(), ((Number) b.get(3)).intValue()};
            Map<String, Object> up = (Map<String, Object>) m.get("upper");
            roofs.add(new Roof(rn, box,
                    ((Number) req(m, "base_y")).intValue(),
                    ((Number) m.getOrDefault("eave", 2)).intValue(),
                    up == null ? 0 : ((Number) up.getOrDefault("wall", 0)).intValue(),
                    up == null ? 0 : ((Number) up.getOrDefault("eave", 2)).intValue()));
        });

        Map<String, int[]> spots = new LinkedHashMap<>();
        ((Map<String, Object>) root.getOrDefault("spots", Map.of())).forEach((k, v) -> {
            List<Object> cr = (List<Object>) v;
            spots.put(k, new int[]{((Number) cr.get(0)).intValue(), ((Number) cr.get(1)).intValue()});
        });

        return new Blueprint(nm, pad, w, d, axis, plan, cols, roofs, spots);
    }

    private static Object req(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) {
            throw new IllegalStateException("설계도에 " + k + " 가 없다");
        }
        return v;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 눈 — 도면이 스스로 지켜야 하는 계약 (순수 · 월드 없이 잰다)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 도면의 자기 계약 — 어기면 던진다.
     *
     * <p>★{@code plan} 의 폭·행·범례는 {@link #of} 가 이미 잰다 (읽는 순간 죽는다).
     * 여기서 재는 것은 <b>뜻</b>이다: 통행이 되는가, 축선이 비었는가, 자리가 도면 안인가.
     */
    public void validate() {
        // ① 중앙 통로 — 축선에 걸어서 지나는 개구가 있어야 한다 (문은 지나는 것이다)
        boolean walkable = false;
        for (int r = 0; r < depth; r++) {
            List<Course> col = columnOf(plan[r][axisCol]);
            int air = 0;
            for (Course c : col) {
                if ("air".equals(c.material())) {
                    air += c.count();
                }
            }
            if (air >= 5) {
                walkable = true;
                break;
            }
        }
        if (!walkable) {
            throw new IllegalStateException("설계도 " + name + " — 축선(col " + axisCol
                    + ")에 통행 개구(빈 켜 ≥5)가 없다. 문은 지나는 것이다.");
        }
        // ② 자리는 도면 안
        spots.forEach((k, cr) -> {
            if (cr[0] < 0 || cr[0] >= width || cr[1] < 0 || cr[1] >= depth) {
                throw new IllegalStateException("설계도 " + name + " — 자리 " + k + " 가 도면 밖: "
                        + cr[0] + "," + cr[1]);
            }
        });
        // ③ 지붕 상자는 도면 안이고 뒤집히지 않았다
        for (Roof rf : roofs) {
            int[] b = rf.box();
            if (b[0] > b[2] || b[1] > b[3]) {
                throw new IllegalStateException("설계도 " + name + " — 지붕 " + rf.name() + " 상자가 뒤집혔다");
            }
            if (b[0] < 0 || b[2] >= width || b[1] < 0 || b[3] >= depth) {
                throw new IllegalStateException("설계도 " + name + " — 지붕 " + rf.name() + " 상자가 도면 밖");
            }
        }
        // ④ ★축선 시야 — 통로 앞(남쪽)에 서면 문이 보여야 한다. 축선 열의 남쪽 구간에
        //    사람 키를 막는 기둥이 서면 안 된다 (계율: 통행의 폭과 시야의 폭은 다르다).
        for (int r = 0; r < depth; r++) {
            List<Course> col = columnOf(plan[r][axisCol]);
            if (col.isEmpty()) {
                continue;
            }
            int solid = 0;
            for (Course c : col) {
                if (!"air".equals(c.material())) {
                    solid += c.count();
                }
            }
            // 기단(1켜)·통로 인방은 허용 · 그보다 두꺼우면 시야를 막는다
            if (solid > 3 && !isGateRow(r)) {
                throw new IllegalStateException("설계도 " + name + " — 축선 r" + r
                        + " 이 시야를 막는다 (실한 켜 " + solid + "). 통행의 폭과 시야의 폭은 다르다.");
            }
        }
    }

    /** 그 행이 문루 몸체인가 (지붕 상자 안) — 축선 시야 계약의 예외 */
    private boolean isGateRow(int row) {
        for (Roof rf : roofs) {
            if (row >= rf.box()[1] && row <= rf.box()[3]
                    && rf.box()[0] <= axisCol && axisCol <= rf.box()[2]) {
                return true;
            }
        }
        return false;
    }

    /** 도면이 쓰는 재료 전량 — 팔레트 신고(금지 재료 검사)가 읽는다 */
    public List<String> materials() {
        List<String> out = new ArrayList<>();
        for (List<Course> col : columns.values()) {
            for (Course c : col) {
                if (!"air".equals(c.material()) && !out.contains(c.material())) {
                    out.add(c.material());
                }
            }
        }
        return out;
    }
}
