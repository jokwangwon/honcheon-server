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

    /**
     * 조성 결과 — 로그가 읽는다.
     *
     * <p>★★★<b>덮임 감사</b> (Codex 권고 2026-08-10) — 「482/482 와 유출 0 은 <b>생성 성공</b>이지
     * <b>최종 생존</b>을 보증하지 않는다」. 실제로 공포의 첫 단이 적주 몸통의 맨 윗 통나무를
     * <b>덮고</b> 있었고, 도면 신고 7켜와 실물 6켜가 어긋났는데 <b>아무 눈도 못 잡았다</b>.
     *
     * <p>그래서 이제 <b>좌표마다 누가 썼는지</b>를 적고, <b>다른 부재</b>가 덮으면 센다.
     * 신고는 숫자 하나가 아니라 <b>생성 / 생존 / 덮임</b> 셋이다.
     */
    public static final class Count {
        public int cells;
        public int blocks;
        public int cleared;
        public int roofs;
        /** 좌표 → 그 자리를 마지막으로 쓴 부재 */
        /** 안허리곡이 <b>패드 밖이라 못 놓은</b> 칸 — 「했다 치고」 넘어가지 않기 위해 센다 */
        public int curveSkipped;
        public final java.util.Map<Long, String> claim = new java.util.HashMap<>();
        /** 「다른 부재가 덮었다」 — 부재쌍별 횟수 */
        public final java.util.Map<String, Integer> covered = new java.util.TreeMap<>();

        void mark(int x, int y, int z, String member) {
            long k = (((long) x & 0x3FFFFFF) << 38) | (((long) y & 0xFFF) << 26)
                    | ((long) z & 0x3FFFFFF);
            String prev = claim.put(k, member);
            if (prev != null && !prev.equals(member)) {
                covered.merge(prev + " ← " + member, 1, Integer::sum);
            }
        }

        /**
         * ★<b>의도된 결합</b> — 깊이 문법이 만드는 필연적 겹침이다 (문제가 아니다):
         * 기단 단(N·F)과 몸체 칸이 같은 바닥 켜를 쓰고, 마감 조각이 문설주 위에 앉는다.
         * 그 밖의 겹침은 <b>도면이 신고한 블록을 다른 부재가 덮은 것</b>이라 문제다.
         */
        private static boolean joined(String pair) {
            return pair.contains("칸:N") || pair.contains("칸:F") || pair.contains("마감:");
        }

        /** 덮임 보고 — 신고는 숫자 하나가 아니라 <b>생성 / 결합 / 덮임</b> 셋이다 */
        public String coverReport() {
            int join = 0;
            java.util.List<String> bad = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, Integer> e : covered.entrySet()) {
                if (joined(e.getKey())) {
                    join += e.getValue();
                } else {
                    bad.add(e.getKey() + " " + e.getValue());
                }
            }
            return "결합 " + join + " · 덮임 "
                    + (bad.isEmpty() ? "0" : String.join(" · ", bad));
        }
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

        stampLevel(world, bp, bp, place, ox, oy, oz, n);
        trims(world, bp, bp, bp.trims(), place, ox, oy, oz, n);


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
                // ★층간 지붕에는 용마루를 안 얹는다 — 가운데를 상층 몸체가 차지한다 (사용자)
                HwasanCampusBuilder.sweepRoofGrand(world, pad, ox + bx0, ox + bx1,
                        oy + rf.baseY(), oz + bz0, oz + bz1, rf.eaveX(), rf.eaveZ(), 0, rf.roofTiles(), tally);
            } else {
                HwasanCampusBuilder.sweepRoof(world, pad, cx, oy + rf.baseY(), cz, hf, hl,
                        rf.eave(), tally);
            }
            // ★D2 ③ 처마 밑 서까래 — 지붕이 「검은 덩어리」로 읽히지 않게.
            //   도면이 rafters: true 라 적은 지붕에만 넣는다 (LOD — 모든 곳에 넣지 않는다).
            if (rf.rafters()) {
                int rex = rf.grand() ? rf.eaveX() : rf.eave();
                int rez = rf.grand() ? rf.eaveZ() : rf.eave();
                // ★겹처마는 <b>격이 높은 전각만</b> — 본전 전용 판(grand)이 곧 그 격이다.
                //   강당·산문은 홑처마 그대로다 (한 블록도 안 바뀐다).
                HwasanCampusBuilder.rafters(world, pad,
                        ox + bx0 - rex, ox + bx1 + rex, oy + rf.baseY(),
                        oz + bz0 - rez, oz + bz1 + rez, rf.grand(), rf.grand(), tally);
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
                // ★★★상층이 <b>문법</b>을 받았다 (2026-08-11 · Codex 판정 B).
                //   여기 있던 사각 루프는 <b>사라졌다</b>: 테두리를 돌며 {@code (c2-bx0)%3==0}
                //   으로 기둥을 찍던 코드다. 그 %3 때문에 상층 기둥 아홉 중 <b>일곱이</b>
                //   하층 창 위에 섰다 (하층 주기는 4 였다 — 최소공배수 12 에서만 만난다).
                //   이제 상층도 제 평면을 갖고 하층과 <b>같은 세 줄</b>을 지난다.
                //   ※평면을 안 적은 옛 도면은 {@link Blueprint} 가 옛 규칙대로 <b>합성</b>해
                //     준다 — 조성에는 층이 한 갈래뿐이다.
                Level up = bp.upperLevel();
                stampLevel(world, bp, up, place, ox, upBase, oz, n);
                trims(world, bp, up, bp.upperLevel().trims(), place, ox, upBase, oz, n);
                brackets(world, bp, up, place, ox, upBase, oz, n,
                        rf.upperWall(), Math.max(rf.upperEaveX(), rf.upperEaveZ()));
                if (rf.grand()) {
                    HwasanCampusBuilder.sweepRoofGrand(world, pad, ox + bx0 + ix, ox + bx1 - ix,
                            upBase + rf.upperWall(), oz + bz0 + iz, oz + bz1 - iz,
                            rf.upperEaveX(), rf.upperEaveZ(), rf.upperRidge(), rf.roofTiles(), tally);
                } else {
                    HwasanCampusBuilder.sweepRoof(world, pad, cx, upBase + rf.upperWall(), cz,
                            hf - ix, hl - iz, rf.upperEave(), tally);
                }
                n.roofs++;
            }
        }

        // ★★REF-3B — 공포는 <b>지붕 뒤에</b> 놓는다. 앞서 놓으면 서까래가
        //   맨 윗단을 덮어 elaborate 3단이 <b>2단으로만 보였다</b> (실측 0/8).

        n.curveSkipped += tally.curveSkipped;   // 안허리곡이 못 나간 칸 — 장부로 올린다
        brackets(world, bp, bp, place, ox, oy, oz, n,
                bp.roofs().isEmpty() ? 0 : bp.roofs().get(0).baseY(),
                bp.roofs().isEmpty() ? 1 : bp.roofs().get(0).eave());
    }

    // ═══════════════════════════════════════════════════════════════════
    // ★★★층 — 하층도 상층도 <b>같은 파이프라인</b>을 탄다 (2026-08-11 · Codex 판정 B)
    // ═══════════════════════════════════════════════════════════════════
    /**
     * 도면 한 층을 찍는다 — {@code plan} → {@code columns} → {@code depth}.
     *
     * <p>전에는 상층이 이 파이프라인 <b>밖</b>에 있었다: {@code BlueprintBuilder} 안의
     * 사각 루프가 테두리를 돌며 {@code (c-bx0)%3==0} 으로 기둥을 찍었다. 그래서 상층은
     * 깊이도 공포도 bay 역할도 마감도 <b>가질 수 없었고</b>, 주기 3 이 코드에 박혀
     * 하층 주기 4 와 어긋나 상층 기둥 아홉 중 일곱이 하층 창 위에 섰다.
     * 이제 층은 자기 {@code plan} 을 갖고, 나머지는 전부 하층과 같은 코드를 지난다.
     */
    private static void stampLevel(World world, Blueprint bp, Level lv,
                                   Blueprint.Placement place, int ox, int oy, int oz, Count n) {
        for (int r = 0; r < lv.depth(); r++) {
            for (int c = 0; c < lv.width(); c++) {
                int[] d = place.map(bp, c, r);      // ★회전 — 도면 (c,r) 이 어디로 가는가
                int x = ox + d[0];
                int z = oz + d[1];
                char ch = lv.at(c, r);
                org.bukkit.block.BlockFace nf = place.turn(faceOf(lv, c, r));
                int dep = lv.depthOf(ch);
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
                    char back = lv.backingChar();
                    if (back != 0) {
                        stampColumn(world, bp, lv, back, x, oy, z, nf, n, 0);
                    }
                    stampColumn(world, bp, lv, ch, x + nf.getModX() * dep, oy,
                            z + nf.getModZ() * dep, nf, n, 0);
                } else if (dep < 0) {
                    // ★★★툇간 (사용자 2026-08-10 · Codex 검토) — 「앞으로 튀어나온 기둥이 벽과
                    //   붙어 있어 튀어나옴은 보이지만 형태가 명확하지 않다. 오히려 한 칸 띄워져
                    //   있는 게 낫지 않나」. 레퍼런스의 적주는 <b>돌바닥 위에 홀로 서고 벽이
                    //   한 겹 물러나</b> 있다 — 자립 열주 + 물러난 벽 = <b>툇간</b>이다.
                    //   ★기둥을 <b>내보내지</b> 않는다 (Codex 반론): 처마가 2 라 기둥을 +2 로
                    //     빼면 공포가 밖으로 나갈 자리가 없어진다. 대신 <b>벽을 물린다</b> —
                    //     적주 +1 · 툇간 0 · 회벽 −1 · 살창 −2 로 <b>세 평면 + 실제 빈 칸</b>.
                    //   기준면에는 <b>바닥만</b> 남는다 (딛는 자리) — 벽은 통째로 안으로 간다.
                    stampColumn(world, bp, lv, ch, x, oy, z, nf, n, 3);           // 기준면 = 툇간 바닥
                    if (dep <= -2) {
                        stampColumn(world, bp, lv, ch, x + nf.getModX(), oy,
                                z + nf.getModZ(), nf, n, 1);                  // 벽 평면 = 창틀
                        stampColumn(world, bp, lv, ch, x + nf.getModX() * dep, oy,
                                z + nf.getModZ() * dep, nf, n, 2);            // 그 뒤 = 살창만
                    } else {
                        stampColumn(world, bp, lv, ch, x + nf.getModX() * dep, oy,
                                z + nf.getModZ() * dep, nf, n, 0);            // 물러난 벽
                    }
                } else {
                    stampColumn(world, bp, lv, ch, x, oy, z, nf, n, 0);
                }
            }
        }
    }

    /** 마감 조각 — 층이 선언한 것만. 빈 목록도 정상이다 (Codex) */
    private static void trims(World world, Blueprint bp, Level lv,
                              java.util.List<Blueprint.Trim> list, Blueprint.Placement place,
                              int ox, int oy, int oz, Count n) {
        for (Blueprint.Trim tr : list) {
        
            for (int c = tr.cols()[0]; c <= tr.cols()[1]; c++) {
                int[] d3 = place.map(bp, c, tr.row());
                org.bukkit.block.BlockFace tf = place.turn(faceOf(lv, c, tr.row()));
                int tx = ox + d3[0] + tf.getModX() * tr.depth();
                int tz = oz + d3[1] + tf.getModZ() * tr.depth();
                stamp(world, tx, oy + tr.y(), tz, tr.material(), tf);
                n.blocks++;
                n.mark(tx, oy + tr.y(), tz, "마감:" + tr.id());
            }
        }
    }

    /**
     * 공포 — 층이 제 지붕 값을 준다.
     *
     * @param roofBaseY 이 층 지붕의 밑면 (도면 y)
     * @param eaveOut   이 층 처마의 내밈 — 공포는 <b>여기서 멈춘다</b>
     */
    private static void brackets(World world, Blueprint bp, Level lv,
                                 Blueprint.Placement place, int ox, int oy, int oz, Count n,
                                 int roofBaseY, int eaveOut) {
        // ★D2 ⑥ 공포 — <b>적주의 머리 위에서만</b> 시작한다 (자리 계약이 이름보다 먼저다).
        //   ★1차 medium 은 작게: 기둥 머리 위 2단 · 밖으로 1 · 좌우 1. 강당엔 이미
        //   깊이·주초·창방·도리·서까래·2단 기단이 들어갔다 — 공포는 <b>마지막 리듬</b>이면 된다.
        int brk = switch (lv.bracket().toLowerCase()) {
            case "simple" -> 1;
            case "medium" -> 2;
            case "elaborate" -> 3;
            default -> 0;
        };
        if (brk > 0) {
            for (int r = 0; r < lv.depth(); r++) {
                for (int c = 0; c < lv.width(); c++) {
                    // ★★★REF-1c-A 진범 (2026-08-09) — <b>「적주」 판정이 너무 넓었다.</b>
                    //   전에는 「기둥 처방에 mangrove_log 가 <b>들어 있는가</b>」로 골랐다.
                    //   그런데 본전은 회벽·격자의 <b>인방</b>도 mangrove 한 켜다 —
                    //   그래서 <b>모든 벽 칸이 적주로 세어져</b> 공포가 정면 전 폭에 깔렸고,
                    //   그것이 「밝은 갈색 3단 띠」였다. 재료를 세 번 바꿔도 안 없어진 까닭이다.
                    //   ★강당은 회벽에 목재가 없어 우연히 멀쩡했다 — <b>우연은 계약이 아니다.</b>
                    //   → 적주는 <b>세로로 이어진 기둥</b>이다: 한 켜짜리 인방은 기둥이 아니다.
                    //   ★★★그리고 <b>여섯 번째</b> (REF-2C · 2026-08-10): 「mangrove_log 를
                    //     포함하는가」는 팔레트가 붉은 <b>테라코타</b>로 바뀌자 그 자리에서 죽었다.
                    //     자가 아니라 <b>조성이</b> 재료 이름에 얹혀 있었던 것이다 — 공포가
                    //     한 칸도 안 앉는다. 정의는 이제 {@link Blueprint#shaftIndex} 하나뿐이다.
                    if (!lv.isPost(lv.at(c, r))) {
                        continue;                    // ★적주가 아니면 공포도 없다
                    }
                    // ★★고주(실내 기둥)에는 공포를 안 얹는다 — 공포는 <b>처마를 받치는</b>
                    //   부재라 처마가 있는 자리, 곧 몸체 <b>둘레</b>에만 뜻이 있다.
                    //   글자 `G` 로 거르지 않는다: 글자는 바뀌고 자리는 안 바뀐다.
                    if (!lv.onBodyEdge(c, r)) {
                        continue;
                    }
                    int[] d2 = place.map(bp, c, r);
                    // ★★★REF-3B — <b>모서리 적주는 두 축을 받는다</b> (사용자 2026-08-10).
                    //   모서리는 「굵은 기둥」이 아니라 <b>두 지붕 방향의 하중이 모이는 곳</b>이다.
                    //   {@link #outward} 가 한 방향만 주므로, 모서리에서는 <b>두 면 모두</b>에 얹는다.
                    //   판정은 이름이 아니라 자리다 — 가로로도 세로로도 벽이 이어지지 않는 칸.
                    //   ★★자를 고쳤다 (상층 문법 · 2026-08-11): 이 두 줄이 아직 <b>하층</b>
                    //     평면을 보고 있었다. 상층 기둥의 이웃은 하층에선 맨바닥(3켜)이라
                    //     <b>상층 기둥이 전부 모서리로</b> 세어졌고, 저마다 곁가지 공포를
                    //     하나씩 더 뻗어 <b>이웃 창을 덮었다</b> (덮임 12건).
                    //     모서리인지는 <b>그 층에서</b> 물어야 한다.
                    boolean ewRun = tall(lv, c - 1, r) && tall(lv, c + 1, r);
                    boolean nsRun = tall(lv, c, r - 1) && tall(lv, c, r + 1);
                    java.util.List<org.bukkit.block.BlockFace> faces = new java.util.ArrayList<>();
                    faces.add(place.turn(faceOf(lv, c, r)));
                    if (bp.bracketContour() && !ewRun && !nsRun) {
                        org.bukkit.block.BlockFace other =
                                place.turn(faceOf(lv, c, r) == org.bukkit.block.BlockFace.NORTH
                                        || faceOf(lv, c, r) == org.bukkit.block.BlockFace.SOUTH
                                        ? (c > (lv.width() - 1) / 2.0
                                                ? org.bukkit.block.BlockFace.EAST
                                                : org.bukkit.block.BlockFace.WEST)
                                        : (r > (lv.depth() - 1) / 2.0
                                                ? org.bukkit.block.BlockFace.SOUTH
                                                : org.bukkit.block.BlockFace.NORTH));
                        faces.add(other);
                    }
                    for (org.bukkit.block.BlockFace nf : faces) {
                    // ★공포는 <b>처마를 받친다</b> — 기둥 머리 <b>위</b>가 아니라 지붕 <b>바로 아래</b>다.
                    //   처음 기둥 머리 위에 놓았더니 지붕 위로 튀어나갔다 (눈이 잡았다).
                    int roofBase = roofBaseY;                 // ← 층이 준다
                    // ★★D2 전파가 드러낸 것 (2026-08-09 · 본전) — <b>내밈은 처마를 넘을 수 없다.</b>
                    //   본전은 위계가 가장 높으니 elaborate(3단) 인데 처마는 실측이 2 로 묶여 있다
                    //   (「3 은 45° 시선에서 벽면을 통째로 가린다」 — D-36). 3 을 그대로 내밀면
                    //   공포가 처마 <b>밖</b>으로 나가 하늘 아래 드러난다 —「공포는 처마를 받친다」가 깨진다.
                    //   → 위계는 <b>더 멀리</b>가 아니라 <b>더 높이·더 촘촘히</b>로 표현한다 (다포).
                    //     단 수는 그대로 3, 내밈만 처마에서 멈춘다.
                    int eave = eaveOut;                       // ← 층이 준다
                    // ★★★REF-1c-A 그림자 홈 (사용자 확정 2026-08-09) — 공포 머리가 처마 밑에
                    //   <b>바로 붙어</b> 있으면 처마·공포·도리가 한 덩어리 띠로 읽힌다.
                    //   한 켜를 비워 <b>어두운 골</b>을 만든다: 처마 밑 y−1 이 벽 밖에서 비고,
                    //   그 그늘이 위의 지붕과 아래의 목구조를 갈라 준다.
                    //   ★공포는 여전히 적주 머리에만 앉고 처마를 넘지 않는다 (계약 불변).
                    int groove = GROOVE;
                    int top = oy + roofBase - brk - groove;
                    int postDep = lv.depthOf(lv.at(c, r));
                    for (int s2 = 0; s2 < brk; s2++) {
                        int reach = Math.min(s2 + 1, Math.max(1, eave));   // 처마에서 멈춘다
                        // ★★덮임 감사가 잡은 것 (2026-08-10): 첫 단이 <b>적주가 이미 선 자리</b>에
                        //   겹쳐 쓰고 있었다 (23회). 재료를 같게 해 눈에는 안 보였지만
                        //   <b>도면이 신고한 블록을 다른 부재가 덮는</b> 상태였다.
                        //   → 겹치면 <b>아예 안 쓴다.</b> 그 칸은 적주의 것이다.
                        if (reach == postDep) {
                            continue;
                        }
                        // ★★★REF-1c-A 진범 (2026-08-09) — <b>공포는 좌우로 번지지 않는다.</b>
                        //   전에는 위 단이 좌우 ±1 로 퍼졌다. 그런데 본전 적주는 <b>3칸 주기</b>라
                        //   ±1 이면 3칸을 다 덮어 <b>이웃 공포끼리 손을 잡고</b> 정면 전체를
                        //   가로지르는 <b>연속 띠</b>가 됐다. 「밝은 갈색 3단 띠」의 진범이 이것이다 —
                        //   재료(단청·도리)를 세 번 바꿔도 안 없어졌던 까닭이다.
                        //   ★공포는 <b>적주 위에서만</b> 있어야 리듬이 산다. 번지면 그 리듬을
                        //     스스로 지운다.
                        for (int side = 0; side <= 0; side++) {
                            int bx = ox + d2[0] + nf.getModX() * reach - nf.getModZ() * side;
                            int bz = oz + d2[1] + nf.getModZ() * reach + nf.getModX() * side;
                            // ★★★REF-2.5 S4 — 공포는 <b>블록 더미가 아니라 계단형 받침</b>이다.
                            //   같은 판재를 세 켜 쌓으면 흑백으로 보면 그냥 네모다. 아래 단은
                            //   계단(벽에 붙어 밖으로 낮아진다) · 가운데는 반블록 · 위는 다시
                            //   계단으로 두어 <b>위로 갈수록 밖으로 받쳐 나가는 윤곽</b>을 만든다.
                            //   ★S1R — <b>첫 단이 곧 주두다</b>: 적주와 같은 붉은 목재 계단으로
                            //     기둥 머리에서 벌어지고, 그 위 두 단이 어두운 목재로 처마를 받는다.
                            // ★flat — 옛 판재 더미. 서까래 켜는 <b>서까래가 갖는다</b>.
                            if (!bp.bracketContour()) {
                                if (top + s2 >= oy + roofBase - 1) {
                                    continue;      // 서까래 자리를 안 뺏는다 (강당 D2 승인 상태)
                                }
                                world.getBlockAt(bx, top + s2, bz)
                                        .setType(Material.DARK_OAK_PLANKS, false);
                                n.blocks++;
                                n.mark(bx, top + s2, bz, "공포@" + nf.name().charAt(0));
                                continue;
                            }
                            //   ★★★기둥에 <b>선반이 붙어 보이던</b> 진범 (사용자 2026-08-10):
                            //     첫 단이 <b>계단</b>이라 적주 머리에서 옆으로 튀어나왔다.
                            //     레퍼런스의 공포는 기둥 위 <b>보 구역</b>에 있지 몸통에 붙지 않는다.
                            //     → 첫 단은 <b>통짜</b>로 둔다 (기둥이 그대로 이어져 보이게).
                            //     벌어짐은 처마 평면에 있는 위 두 단이 맡는다.
                            //   ★★★「기둥에 붙은 블럭이 뭔지 이해가 안 간다」(사용자 2026-08-10).
                            //     실측이 답했다: 그건 <b>공포의 첫 단</b>이었고, 적주 평면(+1)에
                            //     앉아 <b>몸통의 맨 윗 통나무를 먹고</b> 있었다 —
                            //     도면은 몸통 7켜라 적었는데 실물은 6켜였다
                            //     (<b>신고가 실물과 다르다</b> — 이 저장소가 가장 싫어하는 것).
                            //   → 첫 단을 <b>몸통과 같은 재료</b>로 둔다. 그러면 기둥이 보까지
                            //     <b>한 줄로</b> 이어지고, 신고와 실물이 같아지며,
                            //     공포는 <b>처마 평면으로 나온 두 단</b>으로만 보인다
                            //     (레퍼런스도 공포는 기둥 위 보 구역에서 앞으로 나온다).
                            BlockData bd = Bukkit.createBlockData(
                                    //   ★REF-2C — 「몸통과 같은 재료」는 <b>재료 이름</b>이 아니라
                                    //     <b>약속</b>이다. 팔레트를 갈면 여기도 같이 갈려야
                                    //     기둥이 보까지 한 줄로 이어진다 (안 그러면 색 마감이
                                    //     그때 그 병 — 첫 단이 다시 「기둥에 붙은 딴 블록」이 된다).
                                    s2 == 0 ? mat(bp.palette("post", "stripped_mangrove_log"))
                                            //   ★청색은 <b>철회됐다</b> (사용자 2026-08-11):
                                            //     「레퍼런스에도 청색은 없었다. 레퍼런스 위주의
                                            //      건축이 되어야 한다 — 조사는 형태 참고만.」
                                            : s2 == 1 ? Material.DARK_OAK_SLAB
                                            //   ★REF-3B 3단째 = <b>서까래 신발</b>. 처마를 키우지
                                            //     않는다. 3단이 세로로 세 켜일 필요는 없다 —
                                            //     기둥→주두→받침→받침→서까래로 이어지는
                                            //     <b>윤곽</b>이 보이면 된다 (사용자).
                                            : Material.DARK_OAK_STAIRS);
                            world.getBlockAt(bx, top + s2, bz)
                                    .setBlockData(stand(bd, nf), false);
                            n.blocks++;
                            n.mark(bx, top + s2, bz, "공포@" + nf.name().charAt(0));
                        }
                    }
                    // ★★★REF-3B-Q1 (Codex 반박 · 2026-08-10) — <b>입구 옆은 중앙으로 뻗는다.</b>
                    //   내가 「뻗을 칸이 없다」고 판단했던 것은 <b>같은 평면에서만</b> 참이었다.
                    //   문설주(+1)는 적주 평면에 있으므로, <b>처마 평면(+eave)으로 한 칸 비킨 뒤</b>
                    //   중앙 쪽으로 꺾으면 부딪치지 않는다.
                    //   ★방향은 좌표에 안 박는다 — <b>축을 향해</b> 계산한다 (도면이 회전해도 산다).
                    //   ★역할 글자는 도면이 선언한다 (meta.bay_roles) — 코드는 역할만 안다.
                    char adjCh = lv.bayRole("entrance_adjacent");
                    if (bp.bracketContour() && adjCh != 0 && lv.at(c, r) == adjCh && brk >= 2) {
                        int toward = -Integer.signum(lv.axisCol() - c);
                        org.bukkit.block.BlockFace nf0 = place.turn(faceOf(lv, c, r));
                        int reach = Math.max(1, eave);
                        int dx = nf0.getModX() * reach - nf0.getModZ() * toward;
                        int dz = nf0.getModZ() * reach + nf0.getModX() * toward;
                        int lx = -nf0.getModZ() * toward;
                        int lz = nf0.getModX() * toward;
                        org.bukkit.block.BlockFace lat = lx > 0 ? org.bukkit.block.BlockFace.EAST
                                : lx < 0 ? org.bukkit.block.BlockFace.WEST
                                : lz > 0 ? org.bukkit.block.BlockFace.SOUTH
                                        : org.bukkit.block.BlockFace.NORTH;
                        org.bukkit.block.data.type.Stairs sd =
                                (org.bukkit.block.data.type.Stairs)
                                        Bukkit.createBlockData(Material.MANGROVE_STAIRS);
                        sd.setFacing(lat);          // 축을 향해 기운다
                        world.getBlockAt(ox + d2[0] + dx, top + 1, oz + d2[1] + dz)
                                .setBlockData(sd, false);
                        n.blocks++;
                    }
                    }
                }
            }
        }

        // ══════ ★★★간포 (다포) — Codex 2026-08-11 ══════
        if (brk > 1 && lv.intercolumnar() && lv.bodyBox() != null) {
            int[] bb = lv.bodyBox();
            for (int side = 0; side < 4; side++) {
                boolean horiz = side < 2;                 // 0 북 · 1 남 · 2 서 · 3 동
                int fixed = side == 0 ? bb[1] : side == 1 ? bb[3] : side == 2 ? bb[0] : bb[2];
                int from = horiz ? bb[0] : bb[1];
                int to = horiz ? bb[2] : bb[3];
                java.util.List<Integer> posts = new java.util.ArrayList<>();
                for (int k = from; k <= to; k++) {
                    int c2 = horiz ? k : fixed;
                    int r2 = horiz ? fixed : k;
                    if (lv.isPost(lv.at(c2, r2))) {
                        posts.add(k);
                    }
                }
                for (int k : midBays(lv, posts)) {
                    int c2 = horiz ? k : fixed;
                    int r2 = horiz ? fixed : k;
                    int[] d4 = place.map(bp, c2, r2);
                    org.bukkit.block.BlockFace nf2 = place.turn(faceOf(lv, c2, r2));
                    int top2 = oy + roofBaseY - brk - GROOVE;
                    for (int s2 = 1; s2 < brk; s2++) {     // ★첫 단(주두)은 안 갖는다
                        int reach = Math.min(s2 + 1, Math.max(1, eaveOut));
                        int bx = ox + d4[0] + nf2.getModX() * reach;
                        int bz = oz + d4[1] + nf2.getModZ() * reach;
                        BlockData bd = Bukkit.createBlockData(
                                s2 == 1 ? Material.DARK_OAK_SLAB : Material.DARK_OAK_STAIRS);
                        world.getBlockAt(bx, top2 + s2, bz).setBlockData(stand(bd, nf2), false);
                        n.blocks++;
                        n.mark(bx, top2 + s2, bz, "간포@" + nf2.name().charAt(0));
                    }
                }
            }
        }
    }

    /**
     * 기둥 처방 한 벌을 그 자리에 찍는다.
     *
     * @param mode 0 = 그대로 · 1 = <b>창틀만</b> (채움 켜를 비운다) · 2 = <b>채움 켜만</b>
     *             · 3 = <b>바닥만</b> (맨 아래 석재 켜까지 — 툇간처럼 딛는 자리만 남긴다)
     */
    private static void stampColumn(World world, Blueprint bp, Level lv, char ch,
                                    int x, int oy, int z,
                                    org.bukkit.block.BlockFace nf, Count n, int mode) {
        // ★★Codex 2026-08-10 — 부재 이름에 <b>면 방향</b>을 붙인다. 안 붙이면
        //   서로 다른 면의 같은 글자가 부딪친 것(D@남 ← D@서)이 「D ← D」로 <b>숨는다</b>.
        String member = mode == 3 ? "바닥" : "칸:" + ch + "@" + nf.name().charAt(0);
        int y = oy;
        for (Blueprint.Course course : lv.columnOf(ch)) {
            if (mode == 3 && !(course.material().contains("stone")
                    || course.material().contains("andesite"))) {
                return;                          // 바닥까지만 — 그 위는 물러난 벽의 것이다
            }
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
                    Material lat = mat(bp.palette("lattice", "dark_oak_trapdoor"));
                    Material lm = switch (bp.windowFamily().toUpperCase()) {
                        case "W1" -> Material.DARK_OAK_FENCE;
                        case "W3" -> mid ? mat(bp.palette("lattice_accent", "dark_oak_planks")) : lat;
                        default -> lat;
                    };
                    world.getBlockAt(x, y, z)
                            .setBlockData(stand(Bukkit.createBlockData(lm), nf), false);
                    n.blocks++;
                    n.mark(x, y, z, member);
                } else if ("air".equals(course.material())) {
                    // ★빈 켜도 찍는다 — 개구는 「안 짓는 것」이 아니라 「비우는 것」이다
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                    n.cleared++;
                } else {
                    stamp(world, x, y, z, course.material(), nf);
                    n.blocks++;
                    n.mark(x, y, z, member);
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
    /**
     * <b>간포</b> — 기둥 <b>사이</b>의 포. 이것이 있어야 <b>다포</b>다.
     *
     * <p>2026-08-11 실물 대조에서 드러난 것: 우리 본전은 {@code rank: principal ·
     * bracket: elaborate} 로 다포를 의도하면서 포를 <b>기둥 위에만</b> 얹어
     * 형식상 <b>주심포계</b>로 서 있었다. 화엄사 각황전 같은 주불전은 다포계다 —
     * 「공포가 기둥 위뿐 아니라 <b>기둥 사이에도</b> 있다」.
     *
     * <p>★예전에 죽인 병(REF-1c-A 「공포가 좌우로 번져 정면을 가로지르는 띠」)과 <b>다르다</b>:
     * 그건 <b>같은 공포가 옆으로 퍼진</b> 것이고, 간포는 칸 한가운데 서는 <b>독립 단위</b>라
     * 양옆이 비어 있다. 그래서 계약을 <b>틈</b>으로 적는다 (Codex 2026-08-11):
     *
     * <blockquote>간포 중심은 기둥에서 최소 2칸 떨어지고, 간포끼리도 2칸 이상 떨어진다.
     * 같은 높이에서 공포 블록 사이에는 반드시 공기가 한 칸 이상 남는다.</blockquote>
     *
     * <p>그 규칙이 칸 너비를 스스로 읽는다: 협칸 4 → 1개 · 어칸 6 → 2개 ·
     * 3칸 → <b>0개</b>(넣을 자리가 없다). 수를 도면에 안 적는다.
     *
     * <p>간포는 <b>주두 단(첫 단)을 안 갖는다</b> — 첫 단은 기둥 몸통의 연장이고,
     * 간포 밑에는 기둥이 아니라 창방이 있기 때문이다.
     */
    private static java.util.List<Integer> midBays(Level lv, java.util.List<Integer> posts) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int i = 1; i < posts.size(); i++) {
            int a = posts.get(i - 1);
            int b = posts.get(i);
            if (b - a < 4) {
                continue;                       // 3칸 이하 · 겹기둥 — 넣을 자리가 없다
            }
            int lo = a + 2;
            int hi = b - 2;
            int n = (hi - lo) / 2 + 1;
            int span = (n - 1) * 2;
            int start = lo + (hi - lo - span) / 2;   // 칸 한가운데로 모은다
            for (int k = 0; k < n; k++) {
                out.add(start + k * 2);
            }
        }
        return out;
    }

    /** 층이 제 자리로 방향을 안다면 그것을 쓴다 — 모르면 이웃의 높이로 짐작한다 */
    static org.bukkit.block.BlockFace faceOf(Level lv, int col, int row) {
        org.bukkit.block.BlockFace f = lv.outwardFace(col, row);
        return f != null ? f : outward(lv, col, row);
    }

    public static org.bukkit.block.BlockFace outward(Level lv, int col, int row) {
        boolean ew = tall(lv, col - 1, row) && tall(lv, col + 1, row);
        boolean ns = tall(lv, col, row - 1) && tall(lv, col, row + 1);
        double cx = (lv.width() - 1) / 2.0;
        double cz = (lv.depth() - 1) / 2.0;
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

    /**
     * ★REF-1c-A — 처마 밑과 공포 머리 사이의 그림자 골.
     *
     * <p>★★S1R (2026-08-10) — <b>0 으로 내렸다.</b> 1 이던 동안 공포의 아랫단이 적주 평면(+1)에
     * 앉으면서 <b>적주 몸통의 맨 윗칸을 먹었다</b> (5켜 중 4켜만 붉게 남았다). 레퍼런스의 차례는
     * <b>적주 → 주두 → 공포 → 도리</b>인데 우리 것은 적주 → 공포 → 주두로 뒤집혀 있었다.
     * 이제 아랫단이 <b>주두 자리</b>에 앉는다 — 그래서 공포의 첫 단이 곧 주두다.
     * <p>골은 사라지지 않는다: 아랫단만 적주 평면(+1)이고 위 두 단은 처마 평면(+2)이라,
     * 처마 평면의 그 아래 칸이 <b>비어 그늘이 진다</b> — 골을 켜로 비우는 대신
     * <b>윤곽이 스스로 만든다.</b>
     */
    public static final int GROOVE = 0;

    /** ★REF-1c-A — 적주로 세려면 목재가 세로로 이어져야 하는 켜 수 (인방 한 켜는 기둥이 아니다) */
    public static final int POST_MIN_COURSES = 3;

    private static boolean tall(Level lv, int col, int row) {
        return col >= 0 && col < lv.width() && row >= 0 && row < lv.depth()
                && lv.heightAt(col, row) >= WALL_MIN_COURSES;
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
            return d;
        }
        // ★★★REF-2.5 (사용자 2026-08-10) — <b>계단도 자리가 방향을 정한다.</b>
        //   도면 한 장이 네 벽에 다 쓰이므로 도면은 방향을 못 적는다. 살창과 같은 규약으로,
        //   계단은 <b>오르는 쪽이 안쪽</b>을 향한다 — 그래야 높은 면이 벽에 붙고 낮은 단이
        //   밖으로 나와 <b>처마 밑에 그늘이 지는 몰딩</b>이 된다 (지붕의 putRoofStair 와 같은 규약).
        if (d instanceof org.bukkit.block.data.type.Stairs st) {
            st.setFacing(face.getOppositeFace());
        }
        return d;
    }

    /** 도면이 부른 이름을 재료로 — 모르면 <b>거기서</b> 죽는다 (빌드 뒤에 조용히 틀리지 않게) */
    private static Material mat(String name) {
        Material m = Material.matchMaterial(name.toUpperCase());
        if (m == null) {
            throw new IllegalStateException("도면 팔레트의 재질을 모른다: " + name);
        }
        return m;
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
