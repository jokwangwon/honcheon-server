package com.honcheon.mvt.forge;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/**
 * ★★★ <b>설계도대로 짓는다</b> — {@link Blueprint} 를 블록으로 찍는 유일한 길.
 *
 * <p><b>사용자 확정 (2026-08-05)</b>: 「레퍼런스를 토대로 설계도를 그리고 <b>그걸 바탕으로
 * 건축</b>하는 형태를 취해봅시다」. 그러므로 이 클래스는 <b>도면을 해석하지 않는다</b> —
 * 평면과 기둥 처방을 글자 그대로 옮길 뿐이고, 어떻게 보일지는 도면을 고쳐서 정한다.
 *
 * <p><b>멱등</b>: 같은 도면 → 언제나 같은 결과. 도면 범위 안은 도면이 전부다 —
 * {@code air} 켜도 실제로 찍어 지운다 (다시 찍으면 손댄 것이 되돌아간다).
 * 도면 <b>밖</b>은 건드리지 않는다.
 *
 * <p><b>지붕은 코드가 얹는다</b> — 압출로 못 만들기 때문이다. 도면의 {@code roof} 절이
 * 「어디에·몇 층」만 정하고, 결(귀솟음·겹처마·치미)은
 * {@link HwasanCampusBuilder#sweepRoof} 한 곳에 남는다 (두 번 적으면 어긋난다 — 7.5 계율).
 */
public final class BlueprintBuilder {

    private BlueprintBuilder() {
    }

    /** 조성 결과 — 로그가 읽는다 */
    public static final class Count {
        public int cells;
        public int blocks;
        public int cleared;
        public int roofs;
    }

    /**
     * 도면을 패드 위에 앉힌다.
     *
     * @param pad 도면이 앉는 패드 — 원점(좌상단)과 바닥 높이를 여기서 얻는다.
     *            도면 크기와 패드 크기가 다르면 던진다 (도면은 패드를 넘지 않는다).
     */
    public static Count build(World world, Blueprint bp, TerraceForge.Pad pad) {
        Count n = new Count();
        int padW = pad.x1() - pad.x0() + 1;
        int padD = pad.zS() - pad.zN() + 1;
        if (bp.width() > padW || bp.depth() > padD) {
            throw new IllegalStateException("설계도 " + bp.name() + " (" + bp.width() + "×" + bp.depth()
                    + ") 가 패드 " + pad.spec().name() + " (" + padW + "×" + padD + ") 를 넘는다");
        }
        // 좌상단(col0,row0) = 패드 북서 모서리에서 가운데 맞춤 — 축선이 패드 중심에 오게
        int ox = pad.x0() + (padW - bp.width()) / 2;
        int oz = pad.zN() + (padD - bp.depth()) / 2;
        int oy = pad.y() + 1;                       // 포장면 위 한 칸이 도면의 y0

        for (int r = 0; r < bp.depth(); r++) {
            for (int c = 0; c < bp.width(); c++) {
                int x = ox + c;
                int z = oz + r;
                int y = oy;
                n.cells++;
                for (Blueprint.Course course : bp.columnOf(bp.at(c, r))) {
                    for (int k = 0; k < course.count(); k++, y++) {
                        if ("air".equals(course.material())) {
                            // ★빈 켜도 찍는다 — 개구는 「안 짓는 것」이 아니라 「비우는 것」이다
                            world.getBlockAt(x, y, z).setType(Material.AIR, false);
                            n.cleared++;
                        } else {
                            stamp(world, x, y, z, course.material());
                            n.blocks++;
                        }
                    }
                }
            }
        }

        // 지붕 — 도면이 자리를, 코드가 결을 안다
        HwasanCampusBuilder.Tally tally = new HwasanCampusBuilder.Tally();
        for (Blueprint.Roof rf : bp.roofs()) {
            int[] b = rf.box();
            int cx = ox + (b[0] + b[2]) / 2;
            int cz = oz + (b[1] + b[3]) / 2;
            int hf = (b[2] - b[0]) / 2;             // 반폭 (x)
            int hl = (b[3] - b[1]) / 2;             // 반깊이 (z)
            HwasanCampusBuilder.sweepRoof(world, pad, cx, oy + rf.baseY(), cz, hf, hl, tally);
            n.roofs++;
            if (rf.hasUpper()) {
                // 상층 누각 — 하층 지붕 위에 몸체를 세우고 그 위에 다시 지붕
                int upBase = oy + rf.baseY() + 3;   // 하층 지붕 두께만큼 올린다
                for (int r2 = b[1] + 2; r2 <= b[3] - 2; r2++) {
                    for (int c2 = b[0] + 2; c2 <= b[2] - 2; c2++) {
                        boolean edge = r2 == b[1] + 2 || r2 == b[3] - 2 || c2 == b[0] + 2 || c2 == b[2] - 2;
                        if (!edge) {
                            continue;
                        }
                        boolean post = ((c2 - b[0]) % 3 == 0) || ((r2 - b[1]) % 3 == 0);
                        for (int k = 0; k < rf.upperWall(); k++) {
                            world.getBlockAt(ox + c2, upBase + k, oz + r2).setType(
                                    post ? Material.STRIPPED_MANGROVE_LOG : Material.SMOOTH_QUARTZ, false);
                            n.blocks++;
                        }
                    }
                }
                HwasanCampusBuilder.sweepRoof(world, pad, cx, upBase + rf.upperWall(), cz,
                        hf - 2, hl - 2, tally);
                n.roofs++;
            }
        }
        return n;
    }

    private static void stamp(World world, int x, int y, int z, String mat) {
        if (mat.indexOf('[') >= 0 || mat.indexOf(':') >= 0) {
            BlockData d = Bukkit.createBlockData(mat);
            world.getBlockAt(x, y, z).setBlockData(d, false);
            return;
        }
        Material m = Material.matchMaterial(mat.toUpperCase());
        if (m == null) {
            throw new IllegalStateException("설계도의 재질을 모른다: " + mat);
        }
        world.getBlockAt(x, y, z).setType(m, false);
    }
}
