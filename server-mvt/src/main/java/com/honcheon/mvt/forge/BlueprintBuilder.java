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
 * <p><b>★★자리를 먼저 비운다</b> (2026-08-05 · B-196 진범). 이 회차 전까지 이 클래스는
 * <b>평면이 이름 부른 칸만</b> 찍었다 — {@code .} (빈 칸) 은 아무것도 안 썼다. 그래서
 * 캠퍼스시험이 같은 패드에 세워 둔 <b>통짜 건물이 도면 껍데기 안에 그대로 남아</b>,
 * 격자칸(트랩도어는 바닥에 눕는다 = 사실상 구멍) 너머로 그 <b>가문비 판벽·자갈심층암 지붕</b>이
 * 비쳤다. 「본전 회벽이 넓다」(D-34)·「지붕이 벽을 덮는다」(D-36) 의 진범이 이것이다 —
 * <b>우리가 재던 것은 도면이 아니라 두 건물이 겹친 자리였다.</b>
 * 이제 도면은 앉기 전에 제 부피를 <b>먼저 비운다</b>. 비우는 높이는 {@link #clearHeight}
 * 가 <b>도면에서 세어</b> 낸다 (상수를 박으면 도면이 자랄 때 다시 파묻힌다).
 * ※바닥(포장면)은 안 건드린다 — {@code oy} <b>위</b>만 비우므로 패드 포장·계단 착지는 산다.
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

        // ★★자리를 먼저 비운다 — 클래스 주석의 B-196. 도면이 앉는 부피는 도면의 것이다.
        //   여기가 없으면 앞서 선 건물이 도면 껍데기 **안에** 남아, 격자칸 너머로 비쳐
        //   「회벽이 넓다」로 잘못 읽힌다. 판정 회차 전체가 거짓이 된다.
        int clearH = clearHeight(bp);
        for (int r = 0; r < bp.depth(); r++) {
            for (int c = 0; c < bp.width(); c++) {
                for (int k = 0; k < clearH; k++) {
                    if (world.getBlockAt(ox + c, oy + k, oz + r).getType() != Material.AIR) {
                        world.getBlockAt(ox + c, oy + k, oz + r).setType(Material.AIR, false);
                        n.cleared++;
                    }
                }
            }
        }

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
                            stamp(world, x, y, z, course.material(), outward(bp, c, r));
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
            HwasanCampusBuilder.sweepRoof(world, pad, cx, oy + rf.baseY(), cz, hf, hl,
                    rf.eave(), tally);
            n.roofs++;
            if (rf.hasUpper()) {
                // 상층 누각 — 하층 지붕 위에 몸체를 세우고 그 위에 다시 지붕
                int upBase = oy + rf.baseY() + 3;   // 하층 지붕 두께만큼 올린다
                // ★상층이 얼마나 물러나는가는 **도면이 정한다** — 코드에 2 로 박혀 있던 값이다.
                //   7호 실측: 본전은 하층:상층 = 38:25 (0.66) 라 좌우 5칸씩 물러나야 하고,
                //   2 로는 0.87 이 되어 상층이 하층만큼 넓은 다른 건물이 선다.
                //   (산문은 2 가 맞았다 — 그래서 한 상수로 둘을 다 지을 수 없다)
                int ix = rf.insetX();
                int iz = rf.insetZ();
                for (int r2 = b[1] + iz; r2 <= b[3] - iz; r2++) {
                    for (int c2 = b[0] + ix; c2 <= b[2] - ix; c2++) {
                        boolean edge = r2 == b[1] + iz || r2 == b[3] - iz || c2 == b[0] + ix || c2 == b[2] - ix;
                        if (!edge) {
                            continue;
                        }
                        boolean post = ((c2 - b[0]) % 3 == 0) || ((r2 - b[1]) % 3 == 0);
                        for (int k = 0; k < rf.upperWall(); k++) {
                            // ★기둥 사이를 무엇으로 채우는가는 **도면이 정한다** — 7호 실측이
                            //   「본전 상층은 창이 띠를 이루고 회벽이 없다」를 밝혔고, 산문은
                            //   그 반대다. 코드가 한 가지로만 채우면 둘 중 하나는 반드시 틀린다.
                            //   ※격자는 위·아래 한 켜를 인방(planks)으로 끊어 「창」이 되게 한다 —
                            //     통짜 trapdoor 는 블라인드로 읽힌다 (문짝 교정에서 배운 것).
                            Material fill;
                            if (post) {
                                fill = Material.STRIPPED_MANGROVE_LOG;
                            } else if (rf.upperLattice()) {
                                boolean rail = k == 0 || k == rf.upperWall() - 1;
                                fill = rail ? Material.DARK_OAK_PLANKS : Material.DARK_OAK_TRAPDOOR;
                            } else {
                                fill = HwasanCampusBuilder.plaster(ox + c2, upBase + k, oz + r2);
                            }
                            // ★상층 살창도 세운다 — 하층과 같은 병 (눕히면 구멍). 상층은 사각
                            //   테두리라 바깥이 자명하다: 어느 변에 앉았는가가 곧 법선이다.
                            if (fill == Material.DARK_OAK_TRAPDOOR) {
                                org.bukkit.block.BlockFace uf = r2 == b[1] + iz
                                        ? org.bukkit.block.BlockFace.NORTH
                                        : r2 == b[3] - iz ? org.bukkit.block.BlockFace.SOUTH
                                        : c2 == b[0] + ix ? org.bukkit.block.BlockFace.WEST
                                        : org.bukkit.block.BlockFace.EAST;
                                world.getBlockAt(ox + c2, upBase + k, oz + r2)
                                        .setBlockData(stand(Bukkit.createBlockData(fill), uf), false);
                            } else {
                                world.getBlockAt(ox + c2, upBase + k, oz + r2).setType(fill, false);
                            }
                            n.blocks++;
                        }
                    }
                }
                HwasanCampusBuilder.sweepRoof(world, pad, cx, upBase + rf.upperWall(), cz,
                        hf - ix, hl - iz, rf.upperEave(), tally);
                n.roofs++;
            }
        }
        return n;
    }

    /**
     * <b>도면이 앉기 전에 비울 높이</b> — {@code oy} 부터 몇 켜인가. 순수 함수라 눈이 직접 잰다.
     *
     * <p>★상수를 박지 않는 까닭: 도면의 벽이 한 켜 자라거나 상층이 얹히면 상수는 즉시 낮아지고,
     * 그러면 앞서 선 건물의 <b>윗도리만</b> 남아 지붕 위로 삐져나온다 — 눈에 안 띄는 채로
     * 판정을 다시 오염시킨다. 그래서 <b>도면에서 세어</b> 낸다.
     *
     * <p>세는 것 셋: ① 가장 높은 기둥 처방 ② 지붕 꼭대기 (하층 지붕 + 상층 몸체 + 상층 지붕)
     * ③ 용마루·치미가 더 솟는 몫. {@link HwasanCampusBuilder#sweepRoof} 는 반폭만큼 올라가
     * 수렴하므로 지붕이 솟는 높이는 <b>반폭 + 내밈</b>이고, 치미가 두 켜 더 얹힌다.
     */
    public static int clearHeight(Blueprint bp) {
        int top = 0;
        for (int r = 0; r < bp.depth(); r++) {
            for (int c = 0; c < bp.width(); c++) {
                top = Math.max(top, bp.heightAt(c, r));
            }
        }
        for (Blueprint.Roof rf : bp.roofs()) {
            int[] b = rf.box();
            int hf = (b[2] - b[0]) / 2;
            int hl = (b[3] - b[1]) / 2;
            // 하층 지붕 — 처마 끝에서 용마루까지 (반폭 + 내밈) 오르고 치미가 두 켜 더
            top = Math.max(top, rf.baseY() + Math.max(hf, hl) + rf.eave() + 2);
            if (rf.hasUpper()) {
                int up = rf.baseY() + 3 + rf.upperWall();
                int uhf = hf - rf.insetX();
                int uhl = hl - rf.insetZ();
                top = Math.max(top, up + Math.max(uhf, uhl) + rf.upperEave() + 2);
            }
        }
        return top + 1;                 // 한 켜 여유 — 경계에서 남는 것이 없게
    }

    /**
     * <b>그 칸의 바깥쪽</b> — 격자창이 어느 면에 서야 하는가. 순수 함수라 눈이 직접 잰다.
     *
     * <p>★★왜 필요한가 (2026-08-05 · B-196 둘째 진범): 트랩도어를 {@code setType} 으로 놓으면
     * {@code half=bottom, open=false} 라 <b>바닥에 눕는다</b>. 그러면 격자칸은 창이 아니라
     * <b>구멍</b>이고, 벽 너머가 훤히 비친다 (실물 확인: 칸 사이로 바깥 포장이 보였다).
     * 세워야(={@code open=true}) 벽면을 채우는 <b>살창</b>이 된다.
     *
     * <p>바깥을 <b>도면에서</b> 읽는다: 그 칸의 벽이 동서로 달리면 법선은 남북이고, 남북으로
     * 달리면 법선은 동서다. 어느 쪽이 바깥인지는 평면 <b>중심에서 먼 쪽</b>이 정한다.
     * ★한 칸의 처방(예 {@code D})이 네 벽에 다 쓰이므로 도면에 방향을 적을 수 없다 —
     * 적으면 세 벽이 틀린다. 그래서 <b>자리가</b> 방향을 정한다.
     */
    public static org.bukkit.block.BlockFace outward(Blueprint bp, int col, int row) {
        boolean ew = tall(bp, col - 1, row) && tall(bp, col + 1, row);
        boolean ns = tall(bp, col, row - 1) && tall(bp, col, row + 1);
        double cx = (bp.width() - 1) / 2.0;
        double cz = (bp.depth() - 1) / 2.0;
        boolean useNs;
        if (ew != ns) {
            useNs = ew;                                     // 동서로 달리는 벽 → 법선은 남북
        } else {
            useNs = Math.abs(row - cz) >= Math.abs(col - cx);
        }
        if (useNs) {
            return row > cz ? org.bukkit.block.BlockFace.SOUTH : org.bukkit.block.BlockFace.NORTH;
        }
        return col > cx ? org.bukkit.block.BlockFace.EAST : org.bukkit.block.BlockFace.WEST;
    }

    private static boolean tall(Blueprint bp, int col, int row) {
        return col >= 0 && col < bp.width() && row >= 0 && row < bp.depth() && bp.heightAt(col, row) > 0;
    }

    /**
     * 트랩도어를 <b>세워</b> 놓는다 — 눕히면 구멍이 된다 ({@link #outward} 참조).
     * 트랩도어가 아니면 그대로 돌려준다.
     */
    static BlockData stand(BlockData d, org.bukkit.block.BlockFace face) {
        if (d instanceof org.bukkit.block.data.type.TrapDoor td) {
            td.setOpen(true);
            td.setFacing(face);
            td.setHalf(org.bukkit.block.data.Bisected.Half.BOTTOM);
        }
        return d;
    }

    private static void stamp(World world, int x, int y, int z, String mat,
                              org.bukkit.block.BlockFace face) {
        // ★「plaster」는 재료 이름이 아니라 <b>처방</b>이다 — 도면이 재료를 직접 적으면
        //   회벽을 갈 때 도면과 코드 두 곳을 고쳐야 하고, 그 둘이 어긋난다.
        //   (지금 처방은 bone_block 단일 — 근거는 HwasanCampusBuilder.plaster javadoc)
        //   도면에 두 재료를 손으로 흩뿌리면 조성과 눈이 두 식이 되므로 이름 하나로 부른다.
        if ("plaster".equals(mat)) {
            world.getBlockAt(x, y, z).setType(HwasanCampusBuilder.plaster(x, y, z), false);
            return;
        }
        if (mat.indexOf('[') >= 0 || mat.indexOf(':') >= 0) {
            BlockData d = Bukkit.createBlockData(mat);
            world.getBlockAt(x, y, z).setBlockData(stand(d, face), false);
            return;
        }
        Material m = Material.matchMaterial(mat.toUpperCase());
        if (m == null) {
            throw new IllegalStateException("설계도의 재질을 모른다: " + mat);
        }
        // ★트랩도어는 세워야 살창이 된다 — 그냥 setType 하면 바닥에 누워 칸이 구멍이 된다
        BlockData d = Bukkit.createBlockData(m);
        if (d instanceof org.bukkit.block.data.type.TrapDoor) {
            world.getBlockAt(x, y, z).setBlockData(stand(d, face), false);
            return;
        }
        world.getBlockAt(x, y, z).setType(m, false);
    }
}
