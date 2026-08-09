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
    /**
     * ★E-06 (2026-08-07) — <b>한 도면이 여러 패드에 앉는다</b>. 외원에서 확정한 행각 문법을
     * 종문·중정에 <b>재사용</b>하려면 자리마다 패드가 달라야 한다 (사용자: 「외원에서 확정한
     * hwasan_common / low_gable 재사용」). 자리에 {@code pad} 를 안 적으면 종전대로
     * {@code meta.origin_pad} 다 — 옛 도면은 그대로 선다.
     */
    public static Count build(World world, Blueprint bp, java.util.List<TerraceForge.Pad> pads) {
        Count n = new Count();
        java.util.List<Blueprint.Placement> places = bp.placements();
        if (places.isEmpty()) {
            TerraceForge.Pad only = padOf(pads, bp.pad(), bp);
            return build(world, bp, only);
        }
        for (Blueprint.Placement place : places) {
            TerraceForge.Pad pad = padOf(pads, place.padOr(bp.pad()), bp);
            int padW = pad.x1() - pad.x0() + 1;
            int padD = pad.zS() - pad.zN() + 1;
            stampAt(world, bp, pad, place, padW, padD, n);
        }
        return n;
    }

    private static TerraceForge.Pad padOf(java.util.List<TerraceForge.Pad> pads, int zone,
                                          Blueprint bp) {
        return pads.stream().filter(p -> p.spec().zone() == zone).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "설계도 " + bp.name() + " 가 앉을 패드(구역 " + zone + ")를 못 찾았다"));
    }

    public static Count build(World world, Blueprint bp, TerraceForge.Pad pad) {
        Count n = new Count();
        int padW = pad.x1() - pad.x0() + 1;
        int padD = pad.zS() - pad.zN() + 1;
        if (bp.width() > padW || bp.depth() > padD) {
            throw new IllegalStateException("설계도 " + bp.name() + " (" + bp.width() + "×" + bp.depth()
                    + ") 가 패드 " + pad.spec().name() + " (" + padW + "×" + padD + ") 를 넘는다");
        }
        // ★2026-08-07 (E-08) — 자리가 여럿일 수 있다. 안 적은 도면은 <b>가운데 한 장</b>이라
        //   산문·본전은 종전 그대로다 (좌상단 = 패드 북서 모서리에서 가운데 맞춤).
        java.util.List<Blueprint.Placement> places = bp.placements();
        if (places.isEmpty()) {
            places = java.util.List.of(new Blueprint.Placement("(가운데)",
                    (padW - bp.width()) / 2, (padD - bp.depth()) / 2, 0, 0));
        }
        for (Blueprint.Placement place : places) {
            stampAt(world, bp, pad, place, padW, padD, n);
        }
        return n;
    }

    /** 도면 한 장을 그 자리에 · 그 방향으로 찍는다 */
    private static void stampAt(World world, Blueprint bp, TerraceForge.Pad pad,
                                Blueprint.Placement place, int padW, int padD, Count n) {
        int fw = place.widthOf(bp);
        int fd = place.depthOf(bp);
        if (place.col() < 0 || place.row() < 0
                || place.col() + fw > padW || place.row() + fd > padD) {
            throw new IllegalStateException("설계도 " + bp.name() + " 의 자리 " + place.id()
                    + " (" + place.col() + "," + place.row() + " · " + place.rotate() + "도 · "
                    + fw + "×" + fd + ") 가 패드 " + pad.spec().name() + " 밖으로 나간다");
        }
        int ox = pad.x0() + place.col();
        int oz = pad.zN() + place.row();
        // ★D2 ⑤ — 도면은 <b>기단 위</b>에 선다. 안 적으면 1 (포장면 위 한 칸 · 옛 도면 그대로).
        //   이걸 안 맞추면 도면이 앉으며 <b>제가 기단 윗단을 지운다</b>.
        int oy = pad.y() + bp.foundation();

        // ★★자리를 먼저 비운다 — 클래스 주석의 B-196. 도면이 앉는 부피는 도면의 것이다.
        //   여기가 없으면 앞서 선 건물이 도면 껍데기 **안에** 남아, 격자칸 너머로 비쳐
        //   「회벽이 넓다」로 잘못 읽힌다. 판정 회차 전체가 거짓이 된다.
        int clearH = clearHeight(bp);
        for (int r = 0; r < fd; r++) {
            for (int c = 0; c < fw; c++) {
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
                int[] d = place.map(bp, c, r);      // ★회전 — 도면 (c,r) 이 어디로 가는가
                int x = ox + d[0];
                int z = oz + d[1];
                char ch = bp.at(c, r);
                org.bukkit.block.BlockFace nf = place.turn(outward(bp, c, r));
                int dep = bp.depthOf(ch);
                n.cells++;
                // ★★★REF-1b (사용자 확정 2026-08-09) — <b>깊이는 「옮기는 양」이 아니다.</b>
                //   본전을 정면에서 찍고서야 드러났다: {@code +1} 을 <b>이동</b>으로 구현하니
                //   적주가 나가면서 <b>벽면에 제 폭만큼 세로 구멍</b>을 남겼고, 정면 평면의
                //   세 칸 중 둘이 비어 하층이 <b>폐쇄 전각이 아니라 주랑</b>으로 읽혔다.
                //   레퍼런스의 하층은 「붉은 목구조가 흰 회벽·창호 <b>앞에 덧대어진</b> 폐쇄 전각」이다.
                //   → 부호가 <b>방식</b>을 정한다 (도면에 새 글자를 안 늘린다):
                //       +  overlay : 기준면은 <b>배경(회벽)으로 채우고</b> 한 칸 밖에 덧댄다
                //       0  base    : 기준면 그대로
                //       −  recess  : 기준면엔 <b>창틀만</b> 남기고 채움 켜만 안으로 물린다
                //     개구(air 켜)는 종전대로 스스로 판다 — carve 는 처방이 이미 갖고 있다.
                if (dep > 0) {
                    char back = bp.backingChar();
                    if (back != 0) {
                        stampColumn(world, bp, back, x, oy, z, nf, n, 0);
                    }
                    stampColumn(world, bp, ch, x + nf.getModX() * dep, oy,
                            z + nf.getModZ() * dep, nf, n, 0);
                } else if (dep < 0) {
                    stampColumn(world, bp, ch, x, oy, z, nf, n, 1);           // 기준면 = 창틀
                    stampColumn(world, bp, ch, x + nf.getModX() * dep, oy,
                            z + nf.getModZ() * dep, nf, n, 2);                // 안쪽 = 살창만
                } else {
                    stampColumn(world, bp, ch, x, oy, z, nf, n, 0);
                }
            }
        }

        // ★D2 ⑥ 공포 — <b>적주의 머리 위에서만</b> 시작한다 (자리 계약이 이름보다 먼저다).
        //   ★1차 medium 은 작게: 기둥 머리 위 2단 · 밖으로 1 · 좌우 1. 강당엔 이미
        //   깊이·주초·창방·도리·서까래·2단 기단이 들어갔다 — 공포는 <b>마지막 리듬</b>이면 된다.
        int brk = switch (bp.bracket().toLowerCase()) {
            case "simple" -> 1;
            case "medium" -> 2;
            case "elaborate" -> 3;
            default -> 0;
        };
        if (brk > 0) {
            for (int r = 0; r < bp.depth(); r++) {
                for (int c = 0; c < bp.width(); c++) {
                    boolean post = bp.columnOf(bp.at(c, r)).stream()
                            .anyMatch(cs -> cs.material().contains("mangrove_log"));
                    if (!post) {
                        continue;                    // ★적주가 아니면 공포도 없다
                    }
                    int[] d2 = place.map(bp, c, r);
                    org.bukkit.block.BlockFace nf = place.turn(outward(bp, c, r));
                    // ★공포는 <b>처마를 받친다</b> — 기둥 머리 <b>위</b>가 아니라 지붕 <b>바로 아래</b>다.
                    //   처음 기둥 머리 위에 놓았더니 지붕 위로 튀어나갔다 (눈이 잡았다).
                    int roofBase = bp.roofs().isEmpty() ? bp.heightAt(c, r)
                            : bp.roofs().get(0).baseY();
                    // ★★D2 전파가 드러낸 것 (2026-08-09 · 본전) — <b>내밈은 처마를 넘을 수 없다.</b>
                    //   본전은 위계가 가장 높으니 elaborate(3단) 인데 처마는 실측이 2 로 묶여 있다
                    //   (「3 은 45° 시선에서 벽면을 통째로 가린다」 — D-36). 3 을 그대로 내밀면
                    //   공포가 처마 <b>밖</b>으로 나가 하늘 아래 드러난다 —「공포는 처마를 받친다」가 깨진다.
                    //   → 위계는 <b>더 멀리</b>가 아니라 <b>더 높이·더 촘촘히</b>로 표현한다 (다포).
                    //     단 수는 그대로 3, 내밈만 처마에서 멈춘다.
                    int eave = bp.roofs().isEmpty() ? brk : bp.roofs().get(0).eave();
                    int top = oy + roofBase - brk;
                    for (int s2 = 0; s2 < brk; s2++) {
                        int reach = Math.min(s2 + 1, Math.max(1, eave));   // 처마에서 멈춘다
                        for (int side = -1; side <= 1; side++) {
                            int bx = ox + d2[0] + nf.getModX() * reach - nf.getModZ() * side;
                            int bz = oz + d2[1] + nf.getModZ() * reach + nf.getModX() * side;
                            if (side != 0 && s2 == 0) {
                                continue;            // 아래 단은 가운데만 (좌우는 위 단에서)
                            }
                            world.getBlockAt(bx, top + s2, bz)
                                    .setType(Material.DARK_OAK_PLANKS, false);
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
            // ★상자도 돌린다 — 두 모서리를 옮겨 다시 최소·최대를 잡는다
            int[] p0 = place.map(bp, b[0], b[1]);
            int[] p1 = place.map(bp, b[2], b[3]);
            int bx0 = Math.min(p0[0], p1[0]);
            int bx1 = Math.max(p0[0], p1[0]);
            int bz0 = Math.min(p0[1], p1[1]);
            int bz1 = Math.max(p0[1], p1[1]);
            int cx = ox + (bx0 + bx1) / 2;
            int cz = oz + (bz0 + bz1) / 2;
            int hf = (bx1 - bx0) / 2;               // 반폭 (x)
            int hl = (bz1 - bz0) / 2;               // 반깊이 (z)
            if (rf.hipPyramid()) {
                // ★사모 — 정자·망루. <b>회전 상자를 그대로</b> 넘긴다: 정사각이라 겉보기엔
                //   회전이 무의미해 보여도, 장식·개구가 붙으면 방향이 생긴다 (사용자 지적).
                //   지붕 빌더에서 회전을 생략하지 않는 것이 계약이다.
                HwasanCampusBuilder.hipRoof(world, pad, ox + bx0, ox + bx1, oy + rf.baseY(),
                        oz + bz0, oz + bz1, rf.eave(), rf.rise(), rf.ridgeCap(), tally);
                n.roofs++;
                continue;                            // 사모에는 상층이 없다 (1호)
            }
            if (rf.lowGable()) {
                // ★부속급 — 낮은 맞배. 결은 코드 한 곳에 남는다 (7.5 계율)
                HwasanCampusBuilder.gableRoof(world, pad, ox + bx0, ox + bx1,
                        oy + rf.baseY(), oz + bz0, oz + bz1, rf.eave(), tally);
                n.roofs++;
                continue;                            // 맞배에는 상층이 없다 (부속급)
            }
            // ★★REF-1 — <b>본전만</b> 새 판을 탄다 (사용자: 「기존 sweep 코드는 수정하지 않는다」).
            //   grand 는 좌우≠앞뒤 내밈이고 <b>말없는 축소가 없다</b>.
            if (rf.grand()) {
                HwasanCampusBuilder.sweepRoofGrand(world, pad, ox + bx0, ox + bx1,
                        oy + rf.baseY(), oz + bz0, oz + bz1, rf.eaveX(), rf.eaveZ(), tally);
            } else {
                HwasanCampusBuilder.sweepRoof(world, pad, cx, oy + rf.baseY(), cz, hf, hl,
                        rf.eave(), tally);
            }
            // ★D2 ③ 처마 밑 서까래 — 지붕이 「검은 덩어리」로 읽히지 않게.
            //   도면이 rafters: true 라 적은 지붕에만 넣는다 (LOD — 모든 곳에 넣지 않는다).
            if (rf.rafters()) {
                int rex = rf.grand() ? rf.eaveX() : rf.eave();
                int rez = rf.grand() ? rf.eaveZ() : rf.eave();
                HwasanCampusBuilder.rafters(world, pad,
                        ox + bx0 - rex, ox + bx1 + rex, oy + rf.baseY(),
                        oz + bz0 - rez, oz + bz1 + rez, tally);
            }
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
                for (int r2 = bz0 + iz; r2 <= bz1 - iz; r2++) {
                    for (int c2 = bx0 + ix; c2 <= bx1 - ix; c2++) {
                        boolean edge = r2 == bz0 + iz || r2 == bz1 - iz || c2 == bx0 + ix || c2 == bx1 - ix;
                        if (!edge) {
                            continue;
                        }
                        boolean post = ((c2 - bx0) % 3 == 0) || ((r2 - bz0) % 3 == 0);
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
                                org.bukkit.block.BlockFace uf = r2 == bz0 + iz
                                        ? org.bukkit.block.BlockFace.NORTH
                                        : r2 == bz1 - iz ? org.bukkit.block.BlockFace.SOUTH
                                        : c2 == bx0 + ix ? org.bukkit.block.BlockFace.WEST
                                        : org.bukkit.block.BlockFace.EAST;
                                world.getBlockAt(ox + c2, upBase + k, oz + r2)
                                        .setBlockData(stand(Bukkit.createBlockData(fill), uf), false);
                                // ★★REF-1b — 살창 <b>뒤에 회벽</b>을 댄다. 안 대면 상층이
                                //   「적주 | 검은 빈칸」의 되풀이로 읽힌다 (사용자 지적).
                                //   레퍼런스의 상층은 창호가 <b>꽉 찬 벽체</b>다 — 살은 그 앞의 격자다.
                                world.getBlockAt(ox + c2 - uf.getModX(), upBase + k,
                                                oz + r2 - uf.getModZ())
                                        .setType(HwasanCampusBuilder.plaster(ox + c2, upBase + k,
                                                oz + r2), false);
                                n.blocks++;
                            } else {
                                world.getBlockAt(ox + c2, upBase + k, oz + r2).setType(fill, false);
                            }
                            n.blocks++;
                        }
                    }
                }
                if (rf.grand()) {
                    HwasanCampusBuilder.sweepRoofGrand(world, pad, ox + bx0 + ix, ox + bx1 - ix,
                            upBase + rf.upperWall(), oz + bz0 + iz, oz + bz1 - iz,
                            rf.upperEaveX(), rf.upperEaveZ(), tally);
                } else {
                    HwasanCampusBuilder.sweepRoof(world, pad, cx, upBase + rf.upperWall(), cz,
                            hf - ix, hl - iz, rf.upperEave(), tally);
                }
                n.roofs++;
            }
        }
    }

    /**
     * 기둥 처방 한 벌을 그 자리에 찍는다.
     *
     * @param mode 0 = 그대로 · 1 = <b>창틀만</b> (채움 켜를 비운다) · 2 = <b>채움 켜만</b>
     */
    private static void stampColumn(World world, Blueprint bp, char ch, int x, int oy, int z,
                                    org.bukkit.block.BlockFace nf, Count n, int mode) {
        int y = oy;
        for (Blueprint.Course course : bp.columnOf(ch)) {
            for (int k = 0; k < course.count(); k++, y++) {
                boolean fill = "lattice".equals(course.material());
                if (mode == 2 && !fill) {
                    continue;                        // 채움만 옮긴다 — 벽은 기준면에 남는다
                }
                if (mode == 1 && fill) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                    n.cleared++;                     // 창은 기준면에서 <b>뚫린다</b>
                    continue;
                }
                if (fill) {
                    // ★D2 ④ — 「lattice」는 재료가 아니라 <b>처방</b>이다. 모양은
                    //   창호 family 가 정한다: W1 세로살만 · W2 세로+가로 · W3 + 중앙 강조 한 켜
                    boolean mid = course.count() >= 3 && k == course.count() / 2;
                    Material lm = switch (bp.windowFamily().toUpperCase()) {
                        case "W1" -> Material.DARK_OAK_FENCE;
                        case "W3" -> mid ? Material.DARK_OAK_PLANKS : Material.DARK_OAK_TRAPDOOR;
                        default -> Material.DARK_OAK_TRAPDOOR;
                    };
                    world.getBlockAt(x, y, z)
                            .setBlockData(stand(Bukkit.createBlockData(lm), nf), false);
                    n.blocks++;
                } else if ("air".equals(course.material())) {
                    // ★빈 켜도 찍는다 — 개구는 「안 짓는 것」이 아니라 「비우는 것」이다
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                    n.cleared++;
                } else {
                    stamp(world, x, y, z, course.material(), nf);
                    n.blocks++;
                }
            }
        }
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
            if (rf.hipPyramid()) {
                // 사모 — 오름 + 정상 캡 (반폭이 아니라 도면이 준 rise 가 높이를 정한다)
                top = Math.max(top, rf.baseY() + rf.rise() + Math.max(1, rf.ridgeCap()) + 1);
                continue;
            }
            if (rf.lowGable()) {
                top = Math.max(top, rf.baseY() + 2);   // 용마루 한 켜 + 여유
                continue;
            }
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

    /**
     * <b>기단·바닥은 벽이 아니다.</b> ★★2026-08-09 · D2 를 본전에 전파하다 드러난 것.
     *
     * <p>{@link #outward} 는 「이웃에 벽이 있는가」로 면의 법선을 정한다. 그런데 판정이
     * {@code heightAt > 0} 이었다 — 그러면 <b>한 켜짜리 월대도 벽으로 세어진다.</b>
     * 본전에 월대 두름(1켜)과 몸체 바닥(2켜)을 넣자 정면 벽의 남·북 이웃이 둘 다 「벽」이 되어
     * 법선이 <b>남에서 서로 뒤집혔고</b>, 격자창 세 눈이 한꺼번에 짖었다 (남 1 · 그 밖 7).
     *
     * <p>고침은 문턱을 낮추는 쪽이 아니라 <b>자를 바로잡는</b> 쪽이다:
     * <b>사람이 지나갈 수 없는 높이는 가리는 것이 아니라 딛는 것이다.</b>
     * 4켜 미만(월대·기단·툇마루)은 바닥으로 세고, 그 이상만 벽으로 센다.
     * 이 자로 재면 산문의 성벽(6켜)·문루 벽(9켜)은 그대로 벽이고, 앞 기단 {@code T}·
     * 문루 발치 {@code B}(각 1켜)만 바닥으로 빠진다.
     */
    public static final int WALL_MIN_COURSES = 4;

    private static boolean tall(Blueprint bp, int col, int row) {
        return col >= 0 && col < bp.width() && row >= 0 && row < bp.depth()
                && bp.heightAt(col, row) >= WALL_MIN_COURSES;
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
