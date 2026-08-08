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

    /**
     * 지붕 한 채 — 압출이 못 만드는 것. box=[col0,row0,col1,row1]
     *
     * <p>{@code upperInfill} — 상층 몸체의 기둥 <b>사이</b>를 무엇으로 채우는가.
     * ★7호 실측이 드러낸 것: <b>산문과 본전은 벽의 문법이 다르다.</b> 산문 상층은 회벽이
     * 지배하지만 <b>본전 상층은 격자창이 띠를 이루고 회벽이 없다</b>. 코드가 한 가지로만
     * 채우면 둘 중 하나는 반드시 틀리므로, <b>도면이 고르게</b> 했다.
     */
    public record Roof(String name, int[] box, int baseY, int eave, int upperWall, int upperEave,
                       String upperInfill, int[] upperInset, String type, int rise, int ridgeCap,
                       String profile, boolean rafters) {

        /** ★D2 ③ 처마 밑에 서까래를 넣는가 — 근경 디테일이라 <b>도면이 고른다</b> (LOD) */
        public boolean rafters() {
            return rafters;
        }

        /** 사모지붕인가 — 네 면이 한 꼭지로 수렴한다 (정자·망루) */
        public boolean hipPyramid() {
            return "hip_pyramid".equalsIgnoreCase(type);
        }

        /**
         * 지붕의 <b>종류</b> — 안 적으면 {@code sweep}(귀솟음 팔작). 산문·본전이 그것이다.
         * ★{@code low_gable} 은 <b>부속급</b>의 낮은 맞배다 (E-03 행각 · 사용자 확정 2026-08-06):
         * 정문급과 부속급은 지붕으로 갈린다 — 같은 어두운 계열을 쓰되 결이 다르다.
         */
        public boolean lowGable() {
            return "low_gable".equalsIgnoreCase(type);
        }
        public boolean hasUpper() {
            return upperWall > 0;
        }

        /** 상층이 좌우로 물러나는 칸 수 (x) — ★코드 상수가 아니라 <b>도면이 정한다</b> */
        public int insetX() {
            return upperInset[0];
        }

        /** 상층이 앞뒤로 물러나는 칸 수 (z) */
        public int insetZ() {
            return upperInset[1];
        }

        /** 상층 기둥 사이가 창인가 (도면이 {@code infill: lattice} 라 적었는가) */
        public boolean upperLattice() {
            return "lattice".equalsIgnoreCase(upperInfill);
        }
    }

    /**
     * 도면 한 채가 <b>어디에 · 어느 방향으로</b> 앉는가 (패드 로컬 좌표 · 도수는 90 배수).
     *
     * <p>★2026-08-07 (E-08 · 사용자 확정): 「네 모듈 모두를 같은 yml 에서 뽑는 것이 가장
     * 자연스럽다」. 그 전까지 도면은 <b>패드 한가운데 한 장</b>만 찍을 수 있어서, 행각처럼
     * <b>같은 모듈이 방향만 달리 네 번</b> 서는 것을 도면으로 못 그렸다 (그래서 코드가 정본이었다).
     * {@code instances} 를 안 적은 도면(산문·본전)은 <b>종전대로</b> 가운데 한 장이다.
     */
    public record Placement(String id, int col, int row, int rotate, int pad) {

        /** 이 자리가 앉을 패드 — 0 이면 도면의 {@code meta.origin_pad} (E-06 · 2026-08-07) */
        public int padOr(int fallback) {
            return pad == 0 ? fallback : pad;
        }

        /** 회전 뒤의 폭 (도수가 90·270 이면 도면의 깊이가 폭이 된다) */
        public int widthOf(Blueprint bp) {
            return rotate % 180 == 0 ? bp.width() : bp.depth();
        }

        /** 회전 뒤의 깊이 */
        public int depthOf(Blueprint bp) {
            return rotate % 180 == 0 ? bp.depth() : bp.width();
        }

        /** 도면 좌표 (c, r) 이 회전 뒤 어디로 가는가 — {dx, dz} */
        public int[] map(Blueprint bp, int c, int r) {
            return switch (rotate) {
                case 90 -> new int[]{bp.depth() - 1 - r, c};
                case 180 -> new int[]{bp.width() - 1 - c, bp.depth() - 1 - r};
                case 270 -> new int[]{r, bp.width() - 1 - c};
                default -> new int[]{c, r};
            };
        }

        /** 도면 안에서의 방향을 그만큼 돌린다 (살창 법선이 회전을 안 따라가면 뒤집힌다) */
        public org.bukkit.block.BlockFace turn(org.bukkit.block.BlockFace f) {
            org.bukkit.block.BlockFace[] cw = {org.bukkit.block.BlockFace.NORTH,
                    org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.SOUTH,
                    org.bukkit.block.BlockFace.WEST};
            for (int i = 0; i < 4; i++) {
                if (cw[i] == f) {
                    return cw[(i + rotate / 90) % 4];
                }
            }
            return f;
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
    private final List<Placement> placements;
    private final String rank;
    private final int courtyardRow;
    private final String usage;
    private final String family;
    private final Map<Character, Integer> depths;
    private final int foundation;

    private Blueprint(String name, int pad, int width, int depth, int axisCol,
                      char[][] plan, Map<Character, List<Course>> columns,
                      List<Roof> roofs, Map<String, int[]> spots, List<Placement> placements,
                      String rank, int courtyardRow, String usage, String family,
                      Map<Character, Integer> depths, int foundation) {
        this.placements = placements;
        this.rank = rank;
        this.courtyardRow = courtyardRow;
        this.usage = usage;
        this.family = family;
        this.depths = depths;
        this.foundation = foundation;
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

    /**
     * 도면의 <b>위계</b> — {@code principal}(정문·본전 급) 또는 {@code auxiliary}(행각 급).
     * ★계약이 갈린다: 정문급은 「축선에 통행 개구가 있는가」를 지켜야 하지만, 부속급은
     * <b>문이 아니다</b> — 대신 「마당 쪽이 열렸는가 · 외곽 쪽이 닫혔는가」를 지킨다.
     * 안 적으면 정문급이라 <b>이 문법이 생기기 전 도면들이 그대로 선다</b>.
     */
    public String rank() {
        return rank;
    }

    /** 부속급이 지키는 계약인가 */
    public boolean auxiliary() {
        return "auxiliary".equalsIgnoreCase(rank);
    }

    /**
     * 쓰임 — {@code corridor}(행각) · {@code pavilion}(정자). <b>부속급 안에서도 계약이 갈린다</b>:
     * 행각은 「마당 쪽이 열리고 외곽 쪽이 닫혔는가」를, 정자는 「사방이 열렸는가」를 지킨다.
     * ★안 적으면 지붕에서 유추한다 (사모면 정자, 아니면 행각) — 조용히 틀린 계약을 재는 대신.
     */
    public String usage() {
        return usage;
    }

    /** 마당(열리는) 쪽 행 — {@code meta.south_row} */
    public int courtyardRow() {
        return courtyardRow;
    }

    /**
     * 유형의 <b>계열</b> — 같은 계약을 쓰되 크기·비례가 다른 변형을 묶는다.
     * ★장로회는 강당과 <b>같은 assembly 계열</b>의 <b>얕은 변형</b>이다 (사용자 2026-08-08):
     * 「별도의 완전히 새로운 건물 유형보다는 assembly 계열의 얕은 변형으로 가는 것이 자연스럽다」.
     * 안 적으면 {@code usage} 와 같다.
     */
    public String family() {
        return family;
    }

    /**
     * ★<b>입면 깊이</b> — 그 칸이 벽면에서 앞뒤로 몇 칸인가 (바깥이 +). D2 모델링 계약 ①.
     *
     * <p>색이 아무리 좋아도 <b>한 평면에 다 붙어 있으면 평평하다</b>. 적주는 나오고
     * 격자창은 들어가야 입면에 그림자가 생긴다. 방향은 도면이 적지 않는다 —
     * {@link BlueprintBuilder#outward} 가 <b>자리에서</b> 읽는다 (한 처방이 네 벽에 다 쓰이므로).
     */
    public int depthOf(char ch) {
        return depths.getOrDefault(ch, 0);
    }

    /**
     * ★D2 ⑤ <b>기단 층수</b> — 도면이 그 위에 선다 (안 적으면 1 · 옛 도면은 그대로).
     *
     * <p>이걸 도면이 알아야 하는 까닭: {@link BlueprintBuilder} 는 앉기 전에 제 부피를
     * <b>비운다</b>. 기단을 두 단 쌓아 놓고 도면이 한 단 위에 앉으면 <b>윗단을 제가 지운다</b>.
     * 위계가 기단을 정하므로(auxiliary 1 · principal 2 · ceremonial 3), 그 값이 여기 온다.
     */
    public int foundation() {
        return foundation;
    }

    /** 이 도면이 앉는 자리들 — <b>비어 있으면</b> 패드 한가운데 한 장 (종전 문법) */
    public List<Placement> placements() {
        return placements;
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
            // ★상층 물러남 — 스칼라 하나든 [x, z] 둘이든 받는다. 안 적으면 2 (산문의 값이
            //   기본이 되어, 이 문법이 생기기 전 도면들이 그대로 선다)
            int[] inset = {2, 2};
            if (up != null && up.get("inset") != null) {
                Object iv = up.get("inset");
                if (iv instanceof List<?> il && il.size() >= 2) {
                    inset = new int[]{((Number) il.get(0)).intValue(), ((Number) il.get(1)).intValue()};
                } else if (iv instanceof Number num) {
                    inset = new int[]{num.intValue(), num.intValue()};
                } else {
                    throw new IllegalStateException("설계도 " + rn + " 의 upper.inset 은 수 하나이거나 [x, z] 다: " + iv);
                }
            }
            roofs.add(new Roof(rn, box,
                    ((Number) req(m, "base_y")).intValue(),
                    ((Number) m.getOrDefault("eave", 2)).intValue(),
                    up == null ? 0 : ((Number) up.getOrDefault("wall", 0)).intValue(),
                    up == null ? 0 : ((Number) up.getOrDefault("eave", 2)).intValue(),
                    up == null ? "plaster" : String.valueOf(up.getOrDefault("infill", "plaster")),
                    inset,
                    String.valueOf(m.getOrDefault("type", "sweep")),
                    // ★사모의 비례 — <b>같은 타입이되 같은 지붕을 복사하지 않는다</b>
                    //   (사용자 확정 2026-08-07: 정자는 낮고 넓게, 망루는 높고 급하게).
                    //   profile 이 기본값을 고르고, 적어 준 값이 있으면 그것이 이긴다.
                    ((Number) m.getOrDefault("rise",
                            "tower".equals(String.valueOf(m.getOrDefault("profile", "pavilion")))
                                    ? 5 : 3)).intValue(),
                    ((Number) m.getOrDefault("ridge_cap", 1)).intValue(),
                    String.valueOf(m.getOrDefault("profile", "pavilion")),
                    Boolean.parseBoolean(String.valueOf(m.getOrDefault("rafters", "false")))));
        });

        Map<String, int[]> spots = new LinkedHashMap<>();
        ((Map<String, Object>) root.getOrDefault("spots", Map.of())).forEach((k, v) -> {
            List<Object> cr = (List<Object>) v;
            spots.put(k, new int[]{((Number) cr.get(0)).intValue(), ((Number) cr.get(1)).intValue()});
        });

        // ★자리들 — 안 적으면 비운다 (그러면 종전대로 패드 한가운데 한 장이다)
        List<Placement> places = new ArrayList<>();
        Object inst = root.get("instances");
        if (inst instanceof List<?> il) {
            for (Object o : il) {
                Map<String, Object> m = (Map<String, Object>) o;
                List<Object> at = (List<Object>) req(m, "at");
                int rot = ((Number) m.getOrDefault("rotate", 0)).intValue();
                if (Math.floorMod(rot, 90) != 0) {
                    throw new IllegalStateException("설계도 " + nm + " — rotate 는 90 의 배수다: " + rot);
                }
                places.add(new Placement(String.valueOf(m.getOrDefault("id", "(이름 없음)")),
                        ((Number) at.get(0)).intValue(), ((Number) at.get(1)).intValue(),
                        Math.floorMod(rot, 360),
                        ((Number) m.getOrDefault("pad", 0)).intValue()));
            }
            if (places.isEmpty()) {
                throw new IllegalStateException("설계도 " + nm + " — instances 를 적었는데 비어 있다");
            }
        }
        Map<Character, Integer> depths = new LinkedHashMap<>();
        ((Map<String, Object>) root.getOrDefault("depth", Map.of())).forEach((k, v) -> {
            if (k.length() != 1) {
                throw new IllegalStateException("설계도 " + nm + " — depth 의 열쇠는 한 글자여야 한다: " + k);
            }
            int dep = ((Number) v).intValue();
            if (dep < -1 || dep > 1) {
                throw new IllegalStateException("설계도 " + nm + " — 입면 깊이는 -1..+1 이다: "
                        + k + "=" + dep + " (더 나오면 벽이 아니라 다른 건물이 된다)");
            }
            depths.put(k.charAt(0), dep);
        });
        String usage = String.valueOf(meta.getOrDefault("usage",
                roofs.stream().anyMatch(Roof::hipPyramid) ? "pavilion" : "corridor"));
        return new Blueprint(nm, pad, w, d, axis, plan, cols, roofs, spots, places,
                String.valueOf(meta.getOrDefault("rank", "principal")),
                ((Number) meta.getOrDefault("south_row", d - 1)).intValue(), usage,
                String.valueOf(meta.getOrDefault("family", usage)), depths,
                ((Number) meta.getOrDefault("foundation", 1)).intValue());
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
        if (auxiliary()) {
            if ("pavilion".equalsIgnoreCase(usage)) {
                validatePavilion();
            } else if ("residence".equalsIgnoreCase(usage)) {
                validateResidence();
            } else if ("storage".equalsIgnoreCase(usage)) {
                validateStorage();
            } else if ("hermitage".equalsIgnoreCase(usage)) {
                validateHermitage();
            } else {
                validateCorridor();
            }
            return;
        }
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
            // ★상층이 물러나다 못해 사라지지 않는다. 물러남이 반폭을 넘으면 상층 루프가
            //   한 칸도 안 돌아 **지붕만 공중에 뜬다** — 조용히 없어지는 대신 여기서 죽는다
            //   (계율: 조용한 실종은 세 번 당했다).
            if (rf.hasUpper()) {
                int uw = (b[2] - b[0]) - 2 * rf.insetX() + 1;
                int ud = (b[3] - b[1]) - 2 * rf.insetZ() + 1;
                if (uw < 3 || ud < 3) {
                    throw new IllegalStateException("설계도 " + name + " — 지붕 " + rf.name()
                            + " 상층이 너무 물러났다 (" + uw + "×" + ud + " · 물러남 "
                            + rf.insetX() + "/" + rf.insetZ() + ")");
                }
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

    /**
     * ★부속급(행각)의 자기 계약 — {@code corridor_facade} (사용자 확정 2026-08-06).
     *
     * <p>수치는 건축 정답이 아니다: <b>「기둥과 지붕만 남은 퍼걸러」로 되돌아가는 것을 잡는
     * 자</b>다. 문이 아니므로 「축선 통행 개구」는 안 재고, 대신 <b>안팎</b>을 잰다.
     */
    private void validateCorridor() {
        int open = 0;
        int closed = 0;
        for (int c = 0; c < width; c++) {
            int air = 0;
            int solid = 0;
            for (Course cs : columnOf(plan[courtyardRow][c])) {
                if ("air".equals(cs.material())) {
                    air += cs.count();
                } else {
                    solid += cs.count();
                }
            }
            if (air > solid) {
                open++;
            }
            int oAir = 0;
            int oSolid = 0;
            for (Course cs : columnOf(plan[0][c])) {
                if ("air".equals(cs.material())) {
                    oAir += cs.count();
                } else {
                    oSolid += cs.count();
                }
            }
            if (oSolid > oAir) {
                closed++;
            }
        }
        double openRatio = open / (double) width;
        double closedRatio = closed / (double) width;
        if (openRatio < 0.60) {
            throw new IllegalStateException("설계도 " + name + " — 마당 쪽이 안 열렸다 ("
                    + String.format("%.2f", openRatio) + " < 0.60). 다 막으면 회랑의 개방감을 잃는다.");
        }
        if (closedRatio < 0.55) {
            throw new IllegalStateException("설계도 " + name + " — 외곽 쪽이 안 닫혔다 ("
                    + String.format("%.2f", closedRatio) + " < 0.55). 기둥과 지붕만 남으면 퍼걸러다.");
        }
        // ★긴 벽이 한 덩어리로 보이지 않게 — 같은 문자가 셋 이상 잇달지 않는다
        int run = 1;
        for (int c = 1; c < width; c++) {
            run = plan[0][c] == plan[0][c - 1] ? run + 1 : 1;
            if (run >= 3) {
                throw new IllegalStateException("설계도 " + name + " — 외곽 면에 같은 칸이 "
                        + run + "개 잇달았다 (col " + c + "). 반복 단위는 적주|회벽|격자창|회벽 이다.");
            }
        }
        for (Roof rf : roofs) {
            if (!rf.lowGable()) {
                throw new IllegalStateException("설계도 " + name + " — 부속급의 지붕은 low_gable 이다: "
                        + rf.name() + " (" + rf.type() + "). 정문급 팔작은 위계를 흐린다.");
            }
        }
    }

    /**
     * ★정자의 자기 계약 (사용자 확정 2026-08-07 · E-07).
     *
     * <p>정자는 <b>사방이 열린 독립 건물</b>이다 — 행각과 계약이 다르다. 행각은 「마당 쪽이
     * 열리고 외곽 쪽이 닫혔는가」를 지키지만, 정자를 그 자로 재면 <b>닫으라고 요구</b>하게 된다.
     *
     * <p>★사모지붕을 <b>맞배로 통일하지 않는</b> 까닭 (사용자): 맞배는 두 방향의 처마가 강하고
     * 두 면에 박공이 생겨 <b>방향성</b>이 붙는다. 외원의 역할이 「중앙=이동 · 행각=측면 이동 ·
     * 정자=머무름」으로 갈렸는데, 정자까지 맞배로 만들면 행각과 실루엣이 가까워져 그 차이가
     * 약해진다.
     */
    private void validatePavilion() {
        int openSides = 0;
        for (int side = 0; side < 4; side++) {
            int cells = 0;
            int open = 0;
            for (int i = 0; i < (side < 2 ? width : depth); i++) {
                int c = side == 0 ? i : side == 1 ? i : side == 2 ? 0 : width - 1;
                int r = side == 0 ? 0 : side == 1 ? depth - 1 : i;
                int air = 0;
                int solid = 0;
                for (Course cs : columnOf(plan[r][c])) {
                    if ("air".equals(cs.material())) {
                        air += cs.count();
                    } else {
                        solid += cs.count();
                    }
                }
                cells++;
                if (air >= solid) {
                    open++;
                }
            }
            if (open * 2 > cells) {          // 그 면의 절반 넘게 열렸으면 「열린 면」
                openSides++;
            }
        }
        if (openSides < 3) {
            throw new IllegalStateException("설계도 " + name + " — 정자가 사방으로 안 열렸다 (열린 면 "
                    + openSides + " < 3). 정자는 머무는 곳이지 방 안이 아니다.");
        }
        for (Roof rf : roofs) {
            if (!rf.hipPyramid()) {
                throw new IllegalStateException("설계도 " + name + " — 정자의 지붕은 hip_pyramid 다: "
                        + rf.name() + " (" + rf.type() + "). 맞배는 방향성을 만든다.");
            }
            int[] b = rf.box();
            if ((b[2] - b[0]) != (b[3] - b[1])) {
                throw new IllegalStateException("설계도 " + name + " — 사모지붕 1호는 <b>정사각</b>만 받는다: "
                        + rf.name() + " (" + (b[2] - b[0] + 1) + "×" + (b[3] - b[1] + 1) + ")");
            }
            if (rf.rise() < 2) {
                throw new IllegalStateException("설계도 " + name + " — 사모의 오름(rise)이 "
                        + rf.rise() + " 이라 지붕이 안 선다");
            }
        }
    }

    /**
     * ★거처(생활관)의 자기 계약 (사용자 확정 2026-08-08 · 생활 5·8).
     *
     * <p>★<b>강당의 축소판을 만들지 않는다.</b> 두 집은 동선이 다르다:
     * <pre>
     *   강당   : 밖 → <b>중앙 문</b> → 큰 내부 공간
     *   생활관 : 밖 → <b>여러 작은 출입구</b> → 반복되는 생활 단위
     * </pre>
     * 그래서 「축선 통행 개구」(모임·정문급의 계약)를 <b>안 잰다</b>. 대신 재는 것은
     * <b>출입구가 여럿인가</b> · <b>큰 문이 아닌가</b> · <b>긴 정면에 리듬이 있는가</b>다.
     *
     * <p>★지붕·재료는 행각과 공유해도 <b>{@code usage} 계약은 공유하지 않는다</b> —
     * 그러지 않으면 하나가 다시 여러 쓰임을 먹기 시작한다 ({@code plasterHall} 이 그랬다).
     */
    private void validateResidence() {
        int doors = 0;
        int run = 0;
        int worstRun = 0;
        for (int c = 0; c < width; c++) {
            int air = 0;
            int solid = 0;
            for (Course cs : columnOf(plan[courtyardRow][c])) {
                if ("air".equals(cs.material())) {
                    air += cs.count();
                } else {
                    solid += cs.count();
                }
            }
            boolean open = air > solid;
            if (open) {
                doors++;
                run++;
                worstRun = Math.max(worstRun, run);
            } else {
                run = 0;
            }
        }
        if (doors < 2) {
            throw new IllegalStateException("설계도 " + name + " — 거처의 출입구가 " + doors
                    + "곳이다. 생활관은 <b>여러 작은 출입구</b>로 드나든다 (중앙 대문은 모임의 것).");
        }
        if (worstRun > 2) {
            throw new IllegalStateException("설계도 " + name + " — 개구가 " + worstRun
                    + "칸 잇달았다. 그건 <b>대문</b>이다 — 거처는 작은 문 여럿이다.");
        }
        // ★긴 정면의 리듬 — 같은 칸이 셋 잇달면 한 덩어리로 읽힌다 (행각과 같은 자)
        for (int r : new int[]{0, courtyardRow}) {
            int same = 1;
            for (int c = 1; c < width; c++) {
                same = plan[r][c] == plan[r][c - 1] ? same + 1 : 1;
                if (same >= 3) {
                    throw new IllegalStateException("설계도 " + name + " — 정면 r" + r
                            + " 에 같은 칸이 " + same + "개 잇달았다 (col " + c + "). 리듬이 죽는다.");
                }
            }
        }
        for (Roof rf : roofs) {
            if (!rf.lowGable()) {
                throw new IllegalStateException("설계도 " + name + " — 거처의 지붕은 low_gable 이다: "
                        + rf.name() + " (" + rf.type() + "). sweep 는 핵심 전각의 것이라 위계가 죽는다.");
            }
        }
    }

    /**
     * ★저장(창고)의 자기 계약 (사용자 확정 2026-08-08 · 창고 17).
     *
     * <p>★<b>지붕·재료는 생활관과 공유해도 평면 계약은 공유하지 않는다.</b> 사용자가 짚은 함정:
     * 「생활용 low_gable 하나 만듦 → 창고도 비슷하니 그대로 사용 → 다시 하나가 여러 쓰임을
     * 먹기 시작」. 그래서 두 계약은 <b>서로 어긋나게</b> 세운다:
     * <pre>
     *   생활 : 작은 문 여럿 (연속 ≤2) · 창 많음 · 같은 칸 3연속 금지
     *   저장 : <b>큰 문</b> (연속 ≥3) · 창 적음(≤20%) · 벽 면적 큼(≥50%)
     * </pre>
     * 한 도면이 둘 다 통과하면 그건 계약이 아니라 <b>같은 말을 두 번 적은 것</b>이다.
     */
    private void validateStorage() {
        // ★문은 <b>정면</b>에서 재고, 벽·창 비율은 <b>둘레 전체</b>에서 잰다.
        //   처음 둘 다 정면에서 쟀더니 「큰 문이 벽을 먹어」 벽 면적이 0.40 으로 떨어졌다 —
        //   자가 틀린 것이다. 큰 문은 이 집의 <b>정의</b>이지 벽이 적다는 뜻이 아니다.
        int lattice = 0;
        int plasterCells = 0;
        int cells = 0;
        for (int r = 0; r < depth; r++) {
            for (int c = 0; c < width; c++) {
                boolean edge = r == 0 || r == depth - 1 || c == 0 || c == width - 1;
                if (!edge || columnOf(plan[r][c]).isEmpty()) {
                    continue;
                }
                cells++;
                boolean hasLattice = false;
                boolean hasPlaster = false;
                for (Course cs : columnOf(plan[r][c])) {
                    if (cs.material().contains("trapdoor")) {
                        hasLattice = true;
                    }
                    if ("plaster".equals(cs.material())) {
                        hasPlaster = true;
                    }
                }
                if (hasLattice) {
                    lattice++;
                }
                if (hasPlaster) {
                    plasterCells++;
                }
            }
        }
        int bestOpen = 0;
        int run = 0;
        for (int c = 0; c < width; c++) {
            int air = 0;
            for (Course cs : columnOf(plan[courtyardRow][c])) {
                if ("air".equals(cs.material())) {
                    air += cs.count();
                }
            }
            if (air >= 4) {
                run++;
                bestOpen = Math.max(bestOpen, run);
            } else {
                run = 0;
            }
        }
        if (bestOpen < 3) {
            throw new IllegalStateException("설계도 " + name + " — 창고의 문이 " + bestOpen
                    + "칸이다. 물자가 드나들려면 <b>큰 문</b>이어야 한다 (작은 문 여럿은 거처의 것).");
        }
        double win = lattice / (double) cells;
        double wall = plasterCells / (double) cells;
        if (win > 0.20) {
            throw new IllegalStateException("설계도 " + name + " — 창고에 창이 많다 ("
                    + String.format("%.2f", win) + " > 0.20). 저장은 벽이 많고 창이 적다.");
        }
        if (wall < 0.50) {
            throw new IllegalStateException("설계도 " + name + " — 창고의 벽 면적이 적다 ("
                    + String.format("%.2f", wall) + " < 0.50).");
        }
        // ★벽이 많아도 <b>통짜 백면</b>은 안 된다 — 적주가 끊는다 (거처보다 느슨한 자: 5).
        //   ★단 <b>실한 칸만</b> 센다: 처음 개구까지 세었더니 다섯 칸 큰 문이 「통짜 백면」으로
        //   걸렸다 — 그 다섯 칸은 이 집의 <b>정의</b>다. 자가 과했다.
        for (int r : new int[]{0, courtyardRow}) {
            int same = 0;
            char prev = 0;
            for (int c = 0; c < width; c++) {
                char ch = plan[r][c];
                int air = 0;
                int solid = 0;
                for (Course cs : columnOf(ch)) {
                    if ("air".equals(cs.material())) {
                        air += cs.count();
                    } else {
                        solid += cs.count();
                    }
                }
                if (air > solid) {            // 개구는 벽이 아니다 — 세지 않는다
                    same = 0;
                    prev = 0;
                    continue;
                }
                same = ch == prev ? same + 1 : 1;
                prev = ch;
                if (same >= 5) {
                    throw new IllegalStateException("설계도 " + name + " — r" + r + " 에 같은 벽칸이 "
                            + same + "개 잇달았다 (col " + c + "). 벽이 많아도 통짜 백면은 안 된다.");
                }
            }
        }
        for (Roof rf : roofs) {
            if (!rf.lowGable()) {
                throw new IllegalStateException("설계도 " + name + " — 창고의 지붕은 low_gable 이다: "
                        + rf.name() + " (" + rf.type() + ").");
            }
        }
    }

    /**
     * ★암자의 자기 계약 (2026-08-08 · 정상 13 · 부속 20).
     *
     * <p>암자는 <b>홀로 사는 작은 집</b>이다. 거처(생활관)와 무엇이 다른가로 갈린다:
     * <pre>
     *   생활관 : 작은 문 <b>여럿</b> — 생활 단위가 반복된다 (여러 사람)
     *   암자   : 문 <b>하나</b>      — 한 사람이 든다
     *   창고   : <b>큰 문</b> 하나   — 물자가 든다 (문 폭으로 갈린다)
     * </pre>
     * 셋이 같은 {@code low_gable} 지붕과 재료를 쓰되 <b>문의 수와 폭</b>, 그리고 <b>크기</b>로
     * 갈린다 — 그래서 서로의 자로 재면 셋 다 떨어진다.
     */
    private void validateHermitage() {
        int groups = 0;
        int widest = 0;
        int run = 0;
        for (int c = 0; c < width; c++) {
            int air = 0;
            int solid = 0;
            for (Course cs : columnOf(plan[courtyardRow][c])) {
                if ("air".equals(cs.material())) {
                    air += cs.count();
                } else {
                    solid += cs.count();
                }
            }
            if (air > solid) {
                run++;
                widest = Math.max(widest, run);
                if (run == 1) {
                    groups++;
                }
            } else {
                run = 0;
            }
        }
        if (groups != 1) {
            throw new IllegalStateException("설계도 " + name + " — 암자의 문이 " + groups
                    + "곳이다. 암자는 <b>홀로 사는 집</b>이라 문이 하나다 (여럿은 생활관의 것).");
        }
        if (widest > 2) {
            throw new IllegalStateException("설계도 " + name + " — 암자의 문이 " + widest
                    + "칸이다. 그건 <b>큰 문</b>이라 창고의 것이다.");
        }
        if (width > 9 || depth > 7) {
            throw new IllegalStateException("설계도 " + name + " — 암자가 " + width + "×" + depth
                    + " 다. 산정의 암자는 <b>작다</b> (≤ 9×7).");
        }
        for (Roof rf : roofs) {
            if (!rf.lowGable()) {
                throw new IllegalStateException("설계도 " + name + " — 암자의 지붕은 low_gable 이다: "
                        + rf.name() + " (" + rf.type() + ").");
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
