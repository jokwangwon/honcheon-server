package com.honcheon.mvt;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;

/**
 * 문파의 집 여섯 — <b>여덟이 「화산의 도관」으로 서던 거짓말의 자리</b>.
 *
 * <p>2026-07-13, 배선 담당이 잡아낸 것: {@code RemoteBuilder.SECTS} 한 줄이
 * <b>소림·무당·곤륜·종남·점창·청성·아미·해남 여덟을 전부 화산의 도관으로 세우고 있었다.</b>
 * 숭산 대찰에 매화 20장을 심고, 설선 위 곤륜에 회벽을 발랐다. 지도는 그 여덟을
 * <b>사찰·전각·암자·석조도관·목조검문</b>이라 이미 적어 두었는데 코드가 안 읽었을 뿐이다.
 *
 * <p>그래서 이 파일이 있다. <b>인수 기준은 하나다 — 한눈에 달라 보이는가.</b>
 * 자재도 실루엣도 층수도 배치의 문법도 다르다:
 * <table>
 *   <tr><th>원형</th><th>세력</th><th>실루엣</th><th>이 집만의 자재</th></tr>
 *   <tr><td>도관(기존)</td><td>화산</td><td>산문 → 천 계단 → 본전 한 채</td><td>회벽 · 검은 기와 · <b>매화</b></td></tr>
 *   <tr><td>사찰</td><td>소림</td><td><b>긴 축선</b> + 9층 <b>전탑</b></td><td><b>벽돌</b>(탑) · <b>보리수</b></td></tr>
 *   <tr><td>전각</td><td>무당</td><td><b>3층 누각</b> — 높이가 곧 말이다</td><td><b>금정(金頂)</b></td></tr>
 *   <tr><td>암자</td><td>종남</td><td><b>본전이 없다</b> — 흩어진 집 여섯</td><td>초가 · 거친 돌 · <b>등롱이 없다</b></td></tr>
 *   <tr><td>석조도관</td><td>곤륜</td><td>낮고 두껍고 <b>닫혔다</b></td><td><b>돌만</b> — 회벽도 매화도 없다</td></tr>
 *   <tr><td>목조검문</td><td>해남</td><td><b>담이 없다</b> — 물 위로 뜬 고상 마루</td><td>대나무 · <b>삭은 나무</b></td></tr>
 *   <tr><td>비구니원</td><td>아미</td><td><b>산비탈 6단</b> + 절벽의 연검대</td><td>백벽 · 짙은 회기와 · 회색 석축</td></tr>
 * </table>
 *
 * <h2>2계층 계약 — 땅은 안 만진다</h2>
 * 이 파일은 지형 함수를 갖지 않는다. 평평한 바닥이 필요하면 <b>요청</b>한다
 * ({@link TerrainForge#terrace}). 특히 <b>아미</b>는 요청도 잘게 한다 —
 * 사용자의 말이 그것이다: <i>"건축이 산을 누르지 않고 산비탈을 따라 올라간다"</i>.
 * 그래서 큰 단 하나를 깎지 않고 <b>여섯 단을 각자 제 지면에서</b> 뜬다.
 *
 * <p><b>결정론</b> — 난수 없음. 흔들림은 전부 좌표 해시다.
 */
final class SectBuilder {

    private SectBuilder() {
    }

    static List<Zone> build(World world, WorldMap.Place place, TerrainForge.SiteSpec spec,
                            RemoteBuilder.Archetype kind) {
        return switch (kind) {
            case 사찰 -> temple(world, place, spec);
            case 전각 -> pavilion(world, place, spec);
            case 암자 -> hermitages(world, place, spec);
            case 석조도관 -> stoneCloister(world, place, spec);
            case 목조검문 -> seaSwordGate(world, place, spec);
            case 비구니원 -> nunnery(world, place, spec);
            default -> List.of();
        };
    }

    // ══════════════════════════════════════════════════════════════════
    //  사찰(寺刹) — 소림. <b>축선이 이 집이다</b>
    // ══════════════════════════════════════════════════════════════════

    /**
     * 등록부의 한 줄: <b>"숭산의 거대 사찰 — 산문·천왕전·대웅보전. 무승이 마당을 쓸고 무를 익힌다"</b>.
     *
     * <p>도관과 무엇이 다른가 — <b>도관은 한 채고 사찰은 한 줄이다.</b> 도관은 오르는 길 끝에 본전 하나가
     * 서고 끝나지만, 사찰은 <b>산문 → 천왕전 → 대웅보전</b>이 한 축 위에 차례로 서고, 그 축 옆에
     * <b>전탑(塼塔)</b>이 아홉 층으로 오른다. 멀리서 보면 이 집은 <b>수평의 축선과 수직의 탑</b>이다.
     * (그리고 매화가 없다 — <b>보리수</b>다. 불문의 나무는 부처가 그 아래서 깨친 나무다.)
     *
     * <p>영향력 13 — 등록된 무림 최대다(§17). 그 크기가 <b>축선의 길이</b>로 나온다: 반경 80.
     */
    private static List<Zone> temple(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        RemoteBuilder.Facing fw = RemoteBuilder.entry(spec);
        int cx = spec.cx();
        int cz = spec.cz();
        int rad = spec.radius();
        int base = spec.groundY();

        int gateF = rad - 12;          // 산문
        int kingF = rad - 38;          // 천왕전
        int mainF = rad - 66;          // 대웅보전 — 축선의 끝
        int axisY = base;

        // 【단】 축선 — 마당은 관의 돌바닥이 아니라 **다져 쓴 마당**이다 (무승이 쓴다)
        for (int f = mainF - 12; f <= gateF + 4; f++) {
            for (int l = -12; l <= 12; l++) {
                TerrainForge.terrace(world, spec, fw.x(cx, f, l), fw.z(cz, f, l), axisY, 0, 0,
                        Math.floorMod(fw.x(cx, f, l) + fw.z(cz, f, l), 6) == 0
                                ? Material.ANDESITE : Material.POLISHED_ANDESITE);
            }
        }
        RemoteBuilder.approachPath(world, spec, cx, cz, fw, gateF + 5, rad + 14, axisY, 2,
                Material.POLISHED_ANDESITE);

        mountainGate(world, cx, axisY, cz, fw, gateF, 6);                          // 산문 — 넓다 (대찰이다)
        hall(world, spec, cx, axisY, cz, fw, kingF, 8, 6, 4, true);                // 천왕전 — 삼문
        podiumHall(world, spec, cx, axisY, cz, fw, mainF, 11, 8);                  // 대웅보전 — 월대 위

        pagoda(world, spec, cx, axisY, cz, fw, kingF - 4, -22);                    // ★ 전탑 — 9층
        cloisterWing(world, spec, cx, axisY, cz, fw, kingF, 20);                   // 승방 — 축선 오른쪽
        cloisterWing(world, spec, cx, axisY, cz, fw, kingF, -20);                  // 승방 — 왼쪽 (탑 뒤)

        // 마당 — 무승이 무를 익힌다. 목인장 여섯 (연무장이 아니라 **마당**이다: 담이 없다)
        for (int i = -2; i <= 2; i += 2) {
            woodenDummy(world, fw.x(cx, gateF - 10, i * 5), axisY, fw.z(cz, gateF - 10, i * 5), fw);
            woodenDummy(world, fw.x(cx, gateF - 18, i * 5), axisY, fw.z(cz, gateF - 18, i * 5), fw);
        }
        // 보리수 — ★ **매화가 아니다.** 대웅보전 앞 둘 (넓은 잎, 굵은 줄기)
        bodhiTree(world, fw.x(cx, mainF + 14, -13), axisY, fw.z(cz, mainF + 14, -13));
        bodhiTree(world, fw.x(cx, mainF + 14, 13), axisY, fw.z(cz, mainF + 14, 13));

        for (int f = gateF - 6; f >= mainF + 8; f -= 8) {   // 축선의 불 — 등은 축을 따라만 선다
            RemoteBuilder.lanternPost(world, fw.x(cx, f, -10), axisY, fw.z(cz, f, -10));
            RemoteBuilder.lanternPost(world, fw.x(cx, f, 10), axisY, fw.z(cz, f, 10));
        }
        return List.of(new Zone(place.name(), "사찰 — 산문에서 대웅보전까지 한 축선", world.getName(),
                cx - rad, base - 8, cz - rad, cx + rad, base + 46, cz + rad));
    }

    /**
     * <b>전탑(塼塔)</b> — 벽돌로 쌓은 9층 탑. <b>이 집의 실루엣은 이것이다.</b>
     *
     * <p>왜 벽돌인가: 회벽·검은 기와는 도관·관아·성루가 이미 다 쓴다. 그 어휘로는 소림이 안 보인다.
     * 벽돌(塼)은 중원 불탑의 자재이고, 이 세계에서 <b>탑에만</b> 쓴다 —
     * 그러므로 <b>벽돌이 보이면 그것은 소림이다</b> (검수가 그렇게 센다).
     * 오를수록 한 켜씩 좁아지고 층마다 처마가 나온다.
     */
    private static void pagoda(World world, TerrainForge.SiteSpec spec, int cx, int cy, int cz,
                               RemoteBuilder.Facing fw, int f0, int l0) {
        int px = fw.x(cx, f0, l0);
        int pz = fw.z(cz, f0, l0);
        TerrainForge.terrace(world, spec, px, pz, cy, 6, 6, Material.STONE_BRICKS);   // 탑 기단

        int y = cy + 1;
        for (int floor = 0; floor < 9; floor++) {
            int half = 4 - floor / 3;                       // 4 → 3 → 2 (오를수록 좁아진다)
            int h = 4 - floor / 4;                          // 층고도 준다
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    boolean edge = Math.abs(dx) == half || Math.abs(dz) == half;
                    if (!edge) {
                        RemoteBuilder.put(world, px + dx, y - 1, pz + dz, Material.BRICKS);   // 층 마루
                        continue;
                    }
                    for (int i = 0; i < h; i++) {
                        world.getBlockAt(px + dx, y + i, pz + dz).setType(Material.BRICKS);
                    }
                }
            }
            // 창 — 사면에 하나씩 (탑은 안이 비어 있다)
            for (int[] d : new int[][]{{half, 0}, {-half, 0}, {0, half}, {0, -half}}) {
                world.getBlockAt(px + d[0], y + 1, pz + d[1]).setType(Material.AIR);
            }
            // 처마 — 층을 세는 눈금. 이것이 있어야 탑이 탑으로 읽힌다
            int eave = y + h;
            for (int dx = -half - 1; dx <= half + 1; dx++) {
                for (int dz = -half - 1; dz <= half + 1; dz++) {
                    if (Math.abs(dx) <= half && Math.abs(dz) <= half) {
                        continue;
                    }
                    RemoteBuilder.put(world, px + dx, eave, pz + dz, Material.DEEPSLATE_TILE_SLAB);
                }
            }
            y = eave + 1;
        }
        for (int i = 0; i < 3; i++) {   // 상륜(相輪) — 탑의 꼭대기
            RemoteBuilder.put(world, px, y + i, pz, Material.IRON_BARS);
        }
        RemoteBuilder.lantern(world, px, y + 3, pz);   // 탑의 불 — 멀리서 이 불이 소림이다
    }

    /** 승방 회랑 — 축선 옆의 긴 집. 회벽 · 검은 기와 · 문은 마당(축선)을 본다 */
    private static void cloisterWing(World world, TerrainForge.SiteSpec spec, int cx, int cy, int cz,
                                     RemoteBuilder.Facing fw, int f0, int l0) {
        for (int i = 0; i < 3; i++) {
            plasterHall(world, spec, cx, cy, cz, fw, f0 - i * 14, l0, 5, 4, l0 > 0 ? -1 : 1);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  전각(殿閣) — 무당. <b>높이가 이 집이다</b>
    // ══════════════════════════════════════════════════════════════════

    /**
     * 등록부의 한 줄: <b>"구름 위 전각 — 도가. 금정(金頂)이 안개 위로 솟는다"</b>.
     *
     * <p>사찰이 <b>수평</b>이면 무당은 <b>수직</b>이다. 축선도 마당도 없다 —
     * 봉우리 위에 <b>팔각의 단</b>을 놓고 그 위에 <b>3층 누각</b> 하나를 세운다.
     * 그리고 최상층 지붕만 <b>금(金)</b>이다. 이 세계에서 금은 <b>여기밖에 없다</b> —
     * 수묵의 규약이 금을 금하지만, <b>등록부가 "금정"이라고 이름을 대었다.</b>
     * 등록부가 부른 이름은 계약이고, <b>계약은 규약보다 세다</b>.
     */
    private static List<Zone> pavilion(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        RemoteBuilder.Facing fw = RemoteBuilder.entry(spec);
        int cx = spec.cx();
        int cz = spec.cz();
        int rad = spec.radius();
        int base = spec.groundY();
        int px = spec.peakX();
        int pz = spec.peakZ();
        int peakF = (px - cx) * fw.fx() + (pz - cz) * fw.fz();

        // 오를 수 있는 만큼만 오른다 (못 오르는 계단은 벽이다 — 도관이 이미 배운 것)
        int stepFrom = peakF + 14;
        int stepTo = rad - 20;
        int run = stepTo - stepFrom;
        boolean climb = spec.twoTier() && run >= 8;
        int rise = climb ? Math.max(0, Math.min(spec.peakY() - base, run - 4)) : 0;
        int top = base + rise;

        // 【단】 팔각의 단 — 도가의 단(壇)이다. 사각이 아니다 (그것이 관의 것이다)
        octagon(world, spec, px, pz, top, 15, Material.POLISHED_ANDESITE);
        TerrainForge.terrace(world, spec, fw.x(cx, stepTo + 6, 0), fw.z(cz, stepTo + 6, 0), base,
                fw.swapped() ? 5 : 7, fw.swapped() ? 7 : 5, Material.POLISHED_ANDESITE);
        RemoteBuilder.approachPath(world, spec, cx, cz, fw, stepTo + 12, rad + 14, base, 2,
                Material.POLISHED_ANDESITE);
        if (top > base) {
            steps(world, spec, cx, cz, fw, stepFrom, stepTo, top, base);
        }

        // 3층 누각 — 각 층이 한 켜씩 물러선다. **높이가 곧 말이다**
        int y = top;
        for (int floor = 0; floor < 3; floor++) {
            int half = 8 - floor * 2;
            storey(world, px, y, pz, half, floor == 2);
            y += 6;
        }
        // 금정(金頂) — 최상층 지붕. ★ 이 세계에서 금은 여기뿐이다
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 5) {
                    continue;
                }
                RemoteBuilder.put(world, px + dx, y + Math.max(0, 3 - (Math.abs(dx) + Math.abs(dz)) / 2),
                        pz + dz, Material.GOLD_BLOCK);
            }
        }
        // 도가의 단 — 향로와 촛불 (불상은 없다. 도가는 상을 세우지 않는다)
        RemoteBuilder.put(world, px, top + 1, pz + 6, Material.CAULDRON);
        RemoteBuilder.candlesAt(world, px - 1, top + 1, pz + 6);
        RemoteBuilder.candlesAt(world, px + 1, top + 1, pz + 6);
        for (int i = 0; i < 8; i++) {   // 단 둘레의 불 — 팔각의 여덟 귀
            double a = Math.PI * i / 4.0;
            RemoteBuilder.lanternPost(world, px + (int) Math.round(Math.cos(a) * 13), top,
                    pz + (int) Math.round(Math.sin(a) * 13));
        }
        return List.of(new Zone(place.name(), "전각 — 금정이 안개 위로 솟는다", world.getName(),
                cx - rad, base - 8, cz - rad, cx + rad, Math.max(top, base) + 30, cz + rad));
    }

    /** 누각 한 층 — 회벽 · 노출 기둥 · 처마 기와. 위층은 아래층보다 좁다 */
    private static void storey(World world, int px, int y, int pz, int half, boolean topFloor) {
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                boolean edge = Math.abs(dx) == half || Math.abs(dz) == half;
                boolean corner = Math.abs(dx) == half && Math.abs(dz) == half;
                RemoteBuilder.put(world, px + dx, y, pz + dz, Material.DARK_OAK_PLANKS);   // 마루
                if (!edge) {
                    continue;
                }
                for (int i = 1; i <= 4; i++) {
                    world.getBlockAt(px + dx, y + i, pz + dz)
                            .setType(corner ? Material.DARK_OAK_LOG : Material.WHITE_TERRACOTTA);
                }
                if (Math.floorMod(dx + dz, 2) == 0) {
                    world.getBlockAt(px + dx, y + 2, pz + dz).setType(Material.GLASS_PANE);
                }
            }
        }
        for (int d : new int[]{-1, 0, 1}) {   // 문 — 사면이 아니라 한 면 (도가의 집은 안을 다 안 보여준다)
            for (int i = 1; i <= 3; i++) {
                world.getBlockAt(px + d, y + i, pz + half).setType(Material.AIR);
            }
        }
        if (topFloor) {
            return;   // 최상층은 금정이 덮는다
        }
        for (int dx = -half - 1; dx <= half + 1; dx++) {   // 처마 — 층을 센다
            for (int dz = -half - 1; dz <= half + 1; dz++) {
                if (Math.abs(dx) <= half - 1 && Math.abs(dz) <= half - 1) {
                    continue;
                }
                RemoteBuilder.put(world, px + dx, y + 5, pz + dz, Material.DEEPSLATE_TILES);
            }
        }
        RemoteBuilder.lantern(world, px, y + 4, pz);
    }

    /** 팔각의 단 — 도가의 기하. 사각은 관(官)의 것이고 원은 없다 */
    private static void octagon(World world, TerrainForge.SiteSpec spec, int cx, int cz, int y,
                                int r, Material floor) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > r + r / 2) {
                    continue;   // 팔각 — 사각의 네 귀를 자른다
                }
                TerrainForge.terrace(world, spec, cx + dx, cz + dz, y, 0, 0, floor);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  암자(庵子) — 종남. <b>이 원형의 정의는 「흩어짐」이다</b>
    // ══════════════════════════════════════════════════════════════════

    /**
     * 등록부의 한 줄: <b>"도가 은거 — 봉우리에 흩어진 암자. 화산보다 가난하고 조용하다"</b>.
     * 그리고 지도의 주석: <b>"★ 이 원형의 정의는 흩어짐이다. 도관이 '오르는 길 하나'라면 암자는 '길이 여럿'이다"</b>.
     *
     * <p>그래서 <b>본전이 없다.</b> 중심도 없고 축선도 없고 산문도 없다 —
     * 작은 집 여섯이 봉우리 여기저기에 앉고, 그 사이를 <b>가는 산길</b>이 잇는다.
     * <b>집이 제 지면에 앉는다</b>: 여섯 자리의 높이가 다 다르고, 우리는 그것을 안 고른다
     * ({@code spec.groundAt} 이 답한다 — 땅을 깎아 여섯을 같은 높이로 만들면 그건 암자가 아니라 마을이다).
     *
     * <p>가난하다 — <b>기와가 없다</b>(초가). <b>등롱이 없다</b>(횃불). 회벽이 없다(거친 돌과 통나무).
     */
    private static List<Zone> hermitages(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        int cx = spec.cx();
        int cz = spec.cz();
        int rad = spec.radius();
        int base = spec.groundY();

        // 여섯 자리 — 좌표 해시로 흔든다 (난수 금지). 방위각 6등분 · 거리 14~34
        List<int[]> huts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * 2 * i / 6.0;
            int jitter = Math.floorMod(cx * 13 + cz * 7 + i * 31, 12);
            int d = 14 + jitter + (i % 2) * 6;
            int hx = cx + (int) Math.round(Math.cos(a) * d);
            int hz = cz + (int) Math.round(Math.sin(a) * d);
            if (!spec.inside(hx, hz) || spec.wet(hx, hz)) {
                continue;
            }
            huts.add(new int[]{hx, hz, spec.groundAt(hx, hz)});   // ★ 제 지면 — 우리가 안 고른다
        }
        int lo = base;
        int hi = base;
        for (int[] h : huts) {
            hermitage(world, spec, h[0], h[1], h[2], Math.floorMod(h[0] * 7 + h[1] * 3, 3));
            lo = Math.min(lo, h[2]);
            hi = Math.max(hi, h[2]);
        }
        // 산길 — ★ **길이 여럿이다.** 이웃한 암자끼리 잇는다 (중심으로 모이지 않는다)
        for (int i = 0; i < huts.size(); i++) {
            int[] a = huts.get(i);
            int[] b = huts.get((i + 1) % huts.size());
            trail(world, spec, a[0], a[2], a[1], b[0], b[2], b[1]);
        }
        return List.of(new Zone(place.name(), "암자 — 본전이 없다. 흩어져 산다", world.getName(),
                cx - rad, lo - 8, cz - rad, cx + rad, hi + 20, cz + rad));
    }

    /**
     * 암자 한 채 — 5×5. 거친 돌 기초 · 통나무 벽 · <b>초가</b>. 살림은 얇다.
     *
     * <p>세 변주(좌표 해시): ① 그냥 집 ② 우물이 딸린 집 ③ 약초밭이 딸린 집.
     * 여섯이 다 같으면 그것도 하나의 집이다 — <b>흩어짐은 자리만이 아니라 살림에도 있다.</b>
     */
    private static void hermitage(World world, TerrainForge.SiteSpec spec, int hx, int hz, int hy, int variant) {
        TerrainForge.terrace(world, spec, hx, hz, hy, 4, 4, Material.COARSE_DIRT);   // 작은 단 — 산을 안 누른다
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.getBlockAt(hx + dx, hy, hz + dz).setType(Material.COBBLESTONE);   // 거친 돌 기초
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                if (!edge) {
                    continue;
                }
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                for (int y = hy + 1; y <= hy + 3; y++) {
                    world.getBlockAt(hx + dx, y, hz + dz)
                            .setType(corner ? Material.SPRUCE_LOG : Material.SPRUCE_PLANKS);
                }
            }
        }
        world.getBlockAt(hx, hy + 1, hz + 2).setType(Material.AIR);   // 문
        world.getBlockAt(hx, hy + 2, hz + 2).setType(Material.AIR);
        for (int i = 0; i <= 2; i++) {   // 초가 — 기와가 없다 (가난하다)
            int y = hy + 3 + i;
            for (int dx = -3 + i; dx <= 3 - i; dx++) {
                RemoteBuilder.thatchStair(world, hx + dx, y, hz - 3 + i, BlockFace.NORTH);
                RemoteBuilder.thatchStair(world, hx + dx, y, hz + 3 - i, BlockFace.SOUTH);
            }
            for (int dz = -2 + i; dz <= 2 - i; dz++) {
                RemoteBuilder.thatchStair(world, hx - 3 + i, y, hz + dz, BlockFace.WEST);
                RemoteBuilder.thatchStair(world, hx + 3 - i, y, hz + dz, BlockFace.EAST);
            }
        }
        world.getBlockAt(hx, hy + 1, hz - 1).setType(Material.HAY_BLOCK);        // 짚자리
        world.getBlockAt(hx - 1, hy + 1, hz - 1).setType(Material.BARREL);
        RemoteBuilder.candlesAt(world, hx + 1, hy + 1, hz - 1);                  // 등롱이 없다 — 촛불이다
        // 문 앞 횃불 — 이 집의 유일한 등불. 밤에 산을 보면 **점이 여섯** 뜬다 (그것이 종남이다)
        world.getBlockAt(hx, hy + 1, hz + 4).setType(Material.COBBLESTONE_WALL);
        world.getBlockAt(hx, hy + 2, hz + 4).setType(Material.TORCH);

        switch (variant) {
            case 0 -> {   // 우물
                world.getBlockAt(hx + 4, hy + 1, hz).setType(Material.COBBLESTONE_WALL);
                world.getBlockAt(hx + 4, hy, hz).setType(Material.WATER);
            }
            case 1 -> {   // 약초밭 — 종남은 산에서 캐어 먹는다
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = 4; dz <= 6; dz++) {
                        TerrainForge.terrace(world, spec, hx + dx, hz + dz, hy, 0, 0, Material.FARMLAND);
                        RemoteBuilder.put(world, hx + dx, hy + 1, hz + dz, Material.SHORT_GRASS);
                    }
                }
            }
            default -> {   // 장작과 그루터기 — 사는 사람의 흔적
                world.getBlockAt(hx - 4, hy + 1, hz + 1).setType(Material.SPRUCE_LOG);
                world.getBlockAt(hx - 4, hy + 1, hz).setType(Material.SPRUCE_SLAB);
            }
        }
    }

    /** 암자를 잇는 산길 — 한 걸음 ±1. 좁다(폭 1). 이것이 도관의 「천 계단」과 다른 점이다 */
    private static void trail(World world, TerrainForge.SiteSpec spec,
                              int x0, int y0, int z0, int x1, int y1, int z1) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
        if (steps == 0) {
            return;
        }
        int prev = y0;
        for (int i = 0; i <= steps; i++) {
            int x = x0 + (x1 - x0) * i / steps;
            int z = z0 + (z1 - z0) * i / steps;
            int y = spec.inside(x, z) ? spec.groundAt(x, z) : prev;
            y = Math.max(prev - 1, Math.min(prev + 1, y));
            TerrainForge.terrace(world, spec, x, z, y, 0, 0, Material.DIRT_PATH);
            prev = y;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  석조도관(石造道觀) — 곤륜. <b>돌만으로 선다</b>
    // ══════════════════════════════════════════════════════════════════

    /**
     * 등록부의 한 줄: <b>"설선(雪線) 위의 석조 도관. 서역으로 나가는 마지막 문"</b>.
     * 그리고 지도의 경고: <b>"★ terrain.yml surfacing.gonryun: 설원 · snowline 120 —
     * 눈 위에 매화는 피지 않는다"</b>.
     *
     * <p>그러므로 <b>없는 것</b>이 이 집을 말한다:
     * <ul>
     *   <li><b>회벽이 없다</b> — 눈보라에 회를 바르지 않는다. 벽은 <b>돌</b>이고, 두께가 <b>세 칸</b>이다</li>
     *   <li><b>기와가 없다</b> — 지붕도 돌이다 (돌 슬래브·계단). 검은 기와는 비를 흘리는 물건이고 여기는 눈이다</li>
     *   <li><b>매화가 없다</b> — 나무가 한 그루도 없다. 설선 위다</li>
     *   <li><b>마당이 없다</b> — 연무장을 밖에 두지 않는다. 안에서 익힌다 (그래서 이 집은 <b>닫혀</b> 있다)</li>
     * </ul>
     * 대신 하나가 있다 — <b>축선이 관통한다.</b> 앞문으로 들어와 뒷문으로 나가면 그것이 <b>서역</b>이다.
     */
    private static List<Zone> stoneCloister(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        RemoteBuilder.Facing fw = RemoteBuilder.entry(spec);
        int cx = spec.cx();
        int cz = spec.cz();
        int rad = spec.radius();
        int base = spec.groundY();

        int half = 16;
        TerrainForge.terrace(world, spec, cx, cz, base, half + 2, half + 2, Material.POLISHED_DEEPSLATE);
        RemoteBuilder.approachPath(world, spec, cx, cz, fw, half + 3, rad + 12, base, 1,
                Material.POLISHED_DEEPSLATE);

        // 두꺼운 벽 — 세 칸. 낮다(5켜). 무겁다. **오르는 집이 아니라 버티는 집이다**
        for (int f = -half; f <= half; f++) {
            for (int l = -half; l <= half; l++) {
                int ring = Math.max(Math.abs(f), Math.abs(l));
                if (ring < half - 2) {
                    continue;
                }
                if (Math.abs(l) <= 2 && Math.abs(f) == half) {
                    continue;   // 문 둘 — 앞(중원)과 뒤(서역). 축선이 관통한다
                }
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                for (int y = base + 1; y <= base + 6; y++) {
                    world.getBlockAt(x, y, z).setType(coldStone(x, y, z));
                }
                if (ring == half) {   // 좁은 세로 창 — 눈보라를 막고 빛만 들인다
                    if (Math.floorMod(f * 3 + l * 5, 7) == 0) {
                        world.getBlockAt(x, base + 4, z).setType(Material.AIR);
                    }
                    world.getBlockAt(x, base + 7, z).setType(Material.STONE_BRICK_SLAB);   // 돌 갓 (기와가 아니다)
                }
            }
        }
        // 본당 — 벽 안. 지붕도 돌이다
        int hh = 8;
        for (int f = -hh; f <= hh; f++) {
            for (int l = -hh; l <= hh; l++) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                boolean edge = Math.abs(f) == hh || Math.abs(l) == hh;
                if (!edge) {
                    continue;
                }
                for (int y = base + 1; y <= base + 5; y++) {
                    world.getBlockAt(x, y, z).setType(coldStone(x, y, z));
                }
            }
        }
        for (int l = -1; l <= 1; l++) {   // 본당의 문 하나
            for (int y = base + 1; y <= base + 3; y++) {
                world.getBlockAt(fw.x(cx, hh, l), y, fw.z(cz, hh, l)).setType(Material.AIR);
            }
        }
        for (int i = 0; i <= hh; i++) {   // 돌 지붕 — 계단으로 접는다 (기와가 한 장도 없다)
            int y = base + 6 + i;
            for (int f = -hh - 1 + i; f <= hh + 1 - i; f++) {
                RemoteBuilder.put(world, fw.x(cx, f, -hh - 1 + i), y, fw.z(cz, f, -hh - 1 + i),
                        Material.STONE_BRICK_STAIRS);
                RemoteBuilder.put(world, fw.x(cx, f, hh + 1 - i), y, fw.z(cz, f, hh + 1 - i),
                        Material.STONE_BRICK_STAIRS);
            }
            for (int l = -hh + i; l <= hh - i; l++) {
                RemoteBuilder.put(world, fw.x(cx, -hh - 1 + i, l), y, fw.z(cz, -hh - 1 + i, l),
                        Material.STONE_BRICK_STAIRS);
                RemoteBuilder.put(world, fw.x(cx, hh + 1 - i, l), y, fw.z(cz, hh + 1 - i, l),
                        Material.STONE_BRICK_STAIRS);
            }
        }
        // 안 — 제단(향로·촛불)과 경전 시렁. 밖에 없는 살림이 전부 안에 있다
        RemoteBuilder.put(world, cx, base + 1, cz, Material.CAULDRON);
        RemoteBuilder.candlesAt(world, fw.x(cx, 0, -2), base + 1, fw.z(cz, 0, -2));
        RemoteBuilder.candlesAt(world, fw.x(cx, 0, 2), base + 1, fw.z(cz, 0, 2));
        RemoteBuilder.put(world, fw.x(cx, -3, 0), base + 1, fw.z(cz, -3, 0), Material.CHISELED_BOOKSHELF);
        for (int l : new int[]{-4, 4}) {   // 새김 돌 — 서역으로 가는 자의 이름을 적는다
            RemoteBuilder.put(world, fw.x(cx, -hh + 1, l), base + 1, fw.z(cz, -hh + 1, l),
                    Material.CHISELED_STONE_BRICKS);
        }
        for (int[] p : new int[][]{{half - 3, 0}, {-half + 3, 0}, {0, half - 3}, {0, -half + 3}}) {
            RemoteBuilder.lanternPost(world, fw.x(cx, p[0], p[1]), base, fw.z(cz, p[0], p[1]));
        }
        return List.of(new Zone(place.name(), "석조도관 — 돌만으로 선다. 서역으로 나가는 문", world.getName(),
                cx - rad, base - 8, cz - rad, cx + rad, base + 24, cz + rad));
    }

    /** 설선 위의 돌 — 결정론(좌표 해시). 회벽이 한 칸도 섞이지 않는다 */
    private static Material coldStone(int x, int y, int z) {
        return switch (Math.floorMod(x * 7 + y * 5 + z * 11, 6)) {
            case 0 -> Material.POLISHED_DEEPSLATE;
            case 1 -> Material.COBBLESTONE;
            case 2 -> Material.ANDESITE;
            case 3 -> Material.CRACKED_STONE_BRICKS;
            default -> Material.STONE_BRICKS;
        };
    }

    // ══════════════════════════════════════════════════════════════════
    //  목조검문(木造劍門) — 해남. <b>담이 없다</b>
    // ══════════════════════════════════════════════════════════════════

    /**
     * 등록부의 한 줄: <b>"바닷가 검문 — 소금기에 삭은 목조"</b>.
     * 그리고 지도의 못박음: <b>"산문도 천 계단도 없다"</b>.
     *
     * <p>구파 중 <b>유일하게 오르지 않는 집</b>이다. 곤륜이 닫혀 있다면 해남은 <b>열려 있다</b> —
     * 담도 성벽도 산문도 없고, <b>바닥에서 뜬 마루</b>와 <b>지붕</b>과 <b>기둥</b>뿐이다
     * (남방의 고상 가옥: 습기와 물이 마루 밑으로 지나간다).
     *
     * <p>자재도 혼자다: <b>대나무</b>와 <b>껍질 벗긴(삭은) 나무</b>. 회벽·기와·돌은 한 장도 없다 —
     * 소금바람이 회를 먹고 기와를 깨기 때문이다.
     */
    private static List<Zone> seaSwordGate(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        RemoteBuilder.Facing fw = RemoteBuilder.entry(spec);
        int cx = spec.cx();
        int cz = spec.cz();
        int rad = spec.radius();
        int base = spec.groundY();
        int deck = base + 2;   // ★ 고상 — 마루가 땅에서 두 칸 뜬다

        TerrainForge.terrace(world, spec, cx, cz, base, 22, 22, Material.SAND);   // 물가의 흙 — 모래다
        RemoteBuilder.approachPath(world, spec, cx, cz, fw, 24, rad + 12, base, 1, Material.SAND);

        // 검당(劍堂) — 이 문파의 유일한 집. 21×15 마루가 기둥 위에 뜬다
        int hw = 10;
        int hd = 7;
        for (int f = -hd; f <= hd; f++) {
            for (int l = -hw; l <= hw; l++) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                world.getBlockAt(x, deck, z).setType(Math.floorMod(x * 3 + z * 5, 7) == 0
                        ? Material.BAMBOO_MOSAIC : Material.BAMBOO_PLANKS);
                // 고상 기둥 — 마루 밑을 받친다 (밑이 비어야 고상이다: 물과 바람이 지난다)
                if (Math.floorMod(f, 3) == 0 && Math.floorMod(l, 3) == 0) {
                    for (int y = deck - 1; y >= base; y--) {
                        world.getBlockAt(x, y, z).setType(Material.STRIPPED_OAK_LOG);   // 소금기에 삭았다
                    }
                }
            }
        }
        // 기둥과 지붕 — ★ **벽이 아니라 난간이다.** 사방이 트여 있다 (검을 휘두르는 집이다)
        for (int f = -hd; f <= hd; f++) {
            for (int l = -hw; l <= hw; l++) {
                boolean edge = Math.abs(f) == hd || Math.abs(l) == hw;
                if (!edge) {
                    continue;
                }
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                world.getBlockAt(x, deck + 1, z).setType(Material.BAMBOO_FENCE);   // 난간 — 무릎까지만
                boolean post = Math.floorMod(f, 4) == 0 && Math.floorMod(l, 5) == 0;
                if (post || (Math.abs(f) == hd && Math.abs(l) == hw)) {
                    for (int y = deck + 1; y <= deck + 5; y++) {
                        world.getBlockAt(x, y, z).setType(Material.STRIPPED_OAK_LOG);
                    }
                }
            }
        }
        for (int i = 0; i <= 5; i++) {   // 지붕 — 대나무. 물매가 급하다 (남방은 비가 많다)
            int y = deck + 6 + i;
            for (int f = -hd - 2 + i; f <= hd + 2 - i; f++) {
                RemoteBuilder.put(world, fw.x(cx, f, -hw - 2 + i), y, fw.z(cz, f, -hw - 2 + i),
                        Material.BAMBOO_PLANKS);
                RemoteBuilder.put(world, fw.x(cx, f, hw + 2 - i), y, fw.z(cz, f, hw + 2 - i),
                        Material.BAMBOO_PLANKS);
            }
            for (int l = -hw - 1 + i; l <= hw + 1 - i; l++) {
                RemoteBuilder.put(world, fw.x(cx, -hd - 2 + i, l), y, fw.z(cz, -hd - 2 + i, l),
                        Material.BAMBOO_PLANKS);
                RemoteBuilder.put(world, fw.x(cx, hd + 2 - i, l), y, fw.z(cz, hd + 2 - i, l),
                        Material.BAMBOO_PLANKS);
            }
        }
        // 오르는 계단 — 마루가 떴으므로 오르는 자리가 있어야 한다 (없으면 이 집은 못 들어간다)
        for (int i = 0; i <= 2; i++) {
            for (int l = -2; l <= 2; l++) {
                TerrainForge.terrace(world, spec, fw.x(cx, hd + 3 - i, l), fw.z(cz, hd + 3 - i, l),
                        base + i, 0, 0, Material.BAMBOO_PLANKS);
            }
        }
        // 검 시렁 — 이 집이 무엇을 하는 집인가. 벽이 없으니 **기둥에** 건다
        long seed = Math.floorMod(31L * cx + cz, 1_000_003L);
        for (int i = 0; i < 4; i++) {
            int l = -6 + i * 4;
            RemoteBuilder.shelf(world, fw.x(cx, -hd, l), deck + 2, fw.z(cz, -hd, l), fw.out(),
                    Weapons.makeSeeded(Weapons.Series.검, Weapons.Grade.정련, seed + i),
                    Weapons.makeSeeded(Weapons.Series.검, Weapons.Grade.범철, seed + i + 10),
                    null);
        }
        // 세워 둔 배 한 척과 그물 — 해남은 바다로 먹고산다
        for (int l = hw + 6; l <= hw + 12; l++) {
            RemoteBuilder.put(world, fw.x(cx, 2, l), base + 1, fw.z(cz, 2, l), Material.STRIPPED_OAK_LOG);
            RemoteBuilder.put(world, fw.x(cx, 3, l), base + 1, fw.z(cz, 3, l), Material.BAMBOO_PLANKS);
        }
        for (int f = -2; f <= 2; f++) {
            RemoteBuilder.put(world, fw.x(cx, f, -hw - 6), base + 3, fw.z(cz, f, -hw - 6),
                    Material.BAMBOO_FENCE);
            RemoteBuilder.put(world, fw.x(cx, f, -hw - 6), base + 2, fw.z(cz, f, -hw - 6),
                    Material.IRON_CHAIN);   // 그물
        }
        for (int[] p : new int[][]{{hd + 4, -6}, {hd + 4, 6}, {-hd - 4, 0}, {0, hw + 4}, {0, -hw - 4}}) {
            RemoteBuilder.lanternPost(world, fw.x(cx, p[0], p[1]), base, fw.z(cz, p[0], p[1]));
        }
        return List.of(new Zone(place.name(), "목조검문 — 담이 없다. 소금기에 삭은 나무", world.getName(),
                cx - rad, base - 8, cz - rad, cx + rad, base + 22, cz + rad));
    }

    // ══════════════════════════════════════════════════════════════════
    //  비구니원(比丘尼院) — 아미 「운해 비구니원」
    //  ★ 사용자가 직접 설계했다 (docs/design/ami_architecture.md). 추측이 아니다
    // ══════════════════════════════════════════════════════════════════

    /**
     * <b>산비탈 여섯 단</b> — 청심문 · 접객원 · 수행원 · <b>연검대</b> · 장문원 · 금정별원.
     *
     * <p>사용자의 두 못:
     * <ol>
     *   <li><b>"건축이 산을 누르지 않고 산비탈을 따라 올라간다"</b> —
     *       그래서 큰 단 하나를 깎지 않는다. 여섯 단이 <b>각자 제 지면</b>에서 뜨고,
     *       올라갈 수 있는 만큼만 오른다({@code groundAt} 이 정한다. 우리가 아니다)</li>
     *   <li><b>"'여성적 장식'을 붙이지 않는다 — 분홍·꽃문양·과도한 곡선 금지"</b> —
     *       그래서 <b>벚나무(매화)가 한 그루도 없고 분홍이 한 칸도 없다</b>(검수가 0을 확인한다).
     *       정체성은 <b>청결함·절제·정돈된 반복·가느다란 구조선</b>으로 낸다:
     *       백벽에 <b>세로로 긴 창</b>, 처마의 <b>풍경</b>, 반복되는 회랑의 기둥</li>
     * </ol>
     */
    private static List<Zone> nunnery(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        RemoteBuilder.Facing fw = RemoteBuilder.entry(spec);
        int cx = spec.cx();
        int cz = spec.cz();
        int rad = spec.radius();
        int base = spec.groundY();

        // 여섯 단의 축 위치 (밖 → 안). 높이는 **땅이 정한다** — 우리는 읽기만 한다
        int[] axis = {rad - 8, rad - 20, rad - 30, rad - 40, rad - 48, rad - 56};
        int[] y = new int[6];
        int prev = base;
        for (int i = 0; i < 6; i++) {
            int gx = fw.x(cx, axis[i], 0);
            int gz = fw.z(cz, axis[i], 0);
            int g = spec.inside(gx, gz) ? spec.groundAt(gx, gz) : prev;
            // 한 단은 이웃한 단보다 최대 6칸만 오른다 — 그 이상이면 계단이 벽이 된다
            y[i] = Math.max(prev, Math.min(prev + 6, g));
            prev = y[i];
        }
        RemoteBuilder.approachPath(world, spec, cx, cz, fw, axis[0] + 2, rad + 12, y[0], 1,
                Material.COBBLESTONE);

        // ① 청심문 — 작은 산문. 사천왕도 홍문도 **없다**. 그리고 **문 안이 한눈에 안 보인다**
        pureHeartGate(world, spec, cx, cz, fw, axis[0], y[0]);
        // ② 접객원 — ★ **중심부와 직접 연결되지 않는다.** 축에서 옆으로 뺀다
        pad(world, spec, cx, cz, fw, axis[1], 12, y[1], 7, 6, Material.COBBLESTONE);
        plasterHall(world, spec, cx, y[1], cz, fw, axis[1], 12, 5, 4, -1);          // 객당
        plasterHall(world, spec, cx, y[1], cz, fw, axis[1] - 10, 12, 4, 3, -1);     // 약방
        // ③ 수행원 — 작은 중정 둘레에 선방·경당. 건물 사이는 **좁은 회랑**
        pad(world, spec, cx, cz, fw, axis[2], 0, y[2], 12, 9, Material.COBBLESTONE);
        plasterHall(world, spec, cx, y[2], cz, fw, axis[2] + 4, -8, 4, 4, 1);       // 선방
        plasterHall(world, spec, cx, y[2], cz, fw, axis[2] + 4, 8, 4, 4, -1);       // 경당
        plasterHall(world, spec, cx, y[2], cz, fw, axis[2] - 6, 0, 6, 4, 1);        // 식당·침방
        corridor(world, spec, cx, cz, fw, axis[2] + 6, axis[2] - 4, -4, y[2]);      // 회랑 — 반복되는 기둥
        corridor(world, spec, cx, cz, fw, axis[2] + 6, axis[2] - 4, 4, y[2]);
        bell(world, cx, y[2], cz, fw, axis[2] - 2, -11);                            // 작은 범종각 (대형 종루가 아니다)
        washPool(world, spec, cx, cz, fw, axis[2] - 2, 11, y[2]);                   // 세검지(洗劍池)
        // ④ ★ 연검대 — 아미의 대표 공간. 절벽/운해를 내려다본다. **기둥·장식이 없다**
        swordTerrace(world, spec, cx, cz, fw, axis[3], y[3]);
        // ⑤ 장문원 — 가장 높은 곳의 **작고 폐쇄적인** 원. 작지만 전체를 내려다본다
        pad(world, spec, cx, cz, fw, axis[4], 0, y[4], 8, 6, Material.COBBLESTONE);
        walledCourt(world, spec, cx, cz, fw, axis[4], y[4], 8, 6);
        plasterHall(world, spec, cx, y[4], cz, fw, axis[4] - 1, 0, 5, 4, 1);
        // ⑥ 금정별원 — 정상의 단독 수행처. 잇는 것은 **가느다란 석계단** 하나뿐이다
        pad(world, spec, cx, cz, fw, axis[5], 0, y[5], 5, 5, Material.COBBLESTONE);
        plasterHall(world, spec, cx, y[5], cz, fw, axis[5], 0, 4, 3, 1);

        // 여섯 단을 잇는 **가느다란** 계단 (폭 3 — 도관의 천 계단은 폭 11이다)
        for (int i = 0; i < 5; i++) {
            narrowStair(world, spec, cx, cz, fw, axis[i] - 4, axis[i + 1] + 4, y[i], y[i + 1]);
        }
        // 대나무와 약초밭 — 사용자의 목록 그대로 (꽃이 아니다)
        for (int i = 0; i < 12; i++) {
            int f = axis[2] + 8 - i * 2;
            int l = 14 + Math.floorMod(i * 7, 3);
            bamboo(world, fw.x(cx, f, l), y[2], fw.z(cz, f, l));
            bamboo(world, fw.x(cx, f, -l), y[2], fw.z(cz, f, -l));
        }
        return List.of(new Zone(place.name(), "운해 비구니원 — 산비탈을 따라 여섯 단", world.getName(),
                cx - rad, base - 8, cz - rad, cx + rad, y[5] + 24, cz + rad));
    }

    /** 청심문 — 작다. 그리고 <b>꺾인 길</b>: 문을 지나도 안이 한눈에 안 보인다 (사용자의 못) */
    private static void pureHeartGate(World world, TerrainForge.SiteSpec spec, int cx, int cz,
                                      RemoteBuilder.Facing fw, int gf, int gy) {
        pad(world, spec, cx, cz, fw, gf, 0, gy, 6, 4, Material.COBBLESTONE);
        for (int l : new int[]{-2, 2}) {
            for (int i = 1; i <= 4; i++) {
                world.getBlockAt(fw.x(cx, gf, l), gy + i, fw.z(cz, gf, l)).setType(Material.SPRUCE_LOG);
            }
        }
        for (int l = -3; l <= 3; l++) {
            RemoteBuilder.put(world, fw.x(cx, gf, l), gy + 5, fw.z(cz, gf, l), Material.DEEPSLATE_TILES);
            RemoteBuilder.put(world, fw.x(cx, gf - 1, l), gy + 5, fw.z(cz, gf - 1, l),
                    Material.DEEPSLATE_TILE_SLAB);
            RemoteBuilder.put(world, fw.x(cx, gf + 1, l), gy + 5, fw.z(cz, gf + 1, l),
                    Material.DEEPSLATE_TILE_SLAB);
        }
        chime(world, fw.x(cx, gf - 1, -3), gy + 4, fw.z(cz, gf - 1, -3));   // 풍경
        chime(world, fw.x(cx, gf - 1, 3), gy + 4, fw.z(cz, gf - 1, 3));
        // ★ 꺾인 길 — 문 뒤에 가림벽(影壁)을 세워 시선을 막고, 길이 좌우로 돈다
        for (int l = -4; l <= 4; l++) {
            for (int i = 1; i <= 3; i++) {
                world.getBlockAt(fw.x(cx, gf - 5, l), gy + i, fw.z(cz, gf - 5, l))
                        .setType(Material.WHITE_TERRACOTTA);
            }
            RemoteBuilder.put(world, fw.x(cx, gf - 5, l), gy + 4, fw.z(cz, gf - 5, l),
                    Material.DEEPSLATE_TILE_SLAB);
        }
        for (int f = gf - 6; f <= gf - 4; f++) {   // 가림벽을 돌아가는 길
            for (int l = 5; l <= 7; l++) {
                TerrainForge.terrace(world, spec, fw.x(cx, f, l), fw.z(cz, f, l), gy, 0, 0,
                        Material.COBBLESTONE);
            }
        }
    }

    /**
     * ★ <b>연검대(演劍臺)</b> — 아미의 대표 공간. 사용자가 못박은 셋을 그대로 짓는다:
     * <b>절벽/운해를 내려다본다</b> · <b>기둥·장식물을 적게 둔다</b>(검술 동작이 선명하게) ·
     * <b>바닥에 원형이 아니라 길고 흐르는 검로(劍路) 문양</b>.
     */
    private static void swordTerrace(World world, TerrainForge.SiteSpec spec, int cx, int cz,
                                     RemoteBuilder.Facing fw, int f0, int ty) {
        // 넓은 대 — 목제·석제. **비어 있다**: 이 안에 기둥이 하나도 안 선다
        for (int f = f0 - 7; f <= f0 + 7; f++) {
            for (int l = -13; l <= 13; l++) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                // 검로(劍路) — 길고 흐르는 선. 원이 아니다 (사용자의 못)
                boolean swordPath = Math.floorMod(l * 2 + f * 5, 13) < 2;
                TerrainForge.terrace(world, spec, x, z, ty, 0, 0,
                        swordPath ? Material.POLISHED_ANDESITE : Material.SPRUCE_PLANKS);
            }
        }
        // 난간 — 절벽 쪽(측면 바깥)에만. 앞은 트여 있다 (운해를 본다)
        for (int f = f0 - 7; f <= f0 + 7; f += 1) {
            for (int l : new int[]{-13, 13}) {
                RemoteBuilder.put(world, fw.x(cx, f, l), ty + 1, fw.z(cz, f, l), Material.SPRUCE_FENCE);
            }
        }
        // 얕은 석수로 — 빗물과 산수를 모은다 (사용자의 목록). 대 가장자리를 따라 한 칸
        for (int l = -12; l <= 12; l++) {
            int x = fw.x(cx, f0 - 8, l);
            int z = fw.z(cz, f0 - 8, l);
            TerrainForge.terrace(world, spec, x, z, ty - 1, 0, 0, Material.COBBLESTONE);
            world.getBlockAt(x, ty, z).setType(Material.WATER);
        }
        // 불 — 대 **밖**에만 (안에 세우면 검이 등을 친다)
        for (int l : new int[]{-15, 15}) {
            RemoteBuilder.lanternPost(world, fw.x(cx, f0 + 4, l), ty, fw.z(cz, f0 + 4, l));
            RemoteBuilder.lanternPost(world, fw.x(cx, f0 - 4, l), ty, fw.z(cz, f0 - 4, l));
        }
    }

    /** 세검지(洗劍池) — 검을 씻고 마음을 가라앉히는 작은 못. 조성이 깐 단 안을 판다 */
    private static void washPool(World world, TerrainForge.SiteSpec spec, int cx, int cz,
                                 RemoteBuilder.Facing fw, int f0, int l0, int py) {
        for (int f = -2; f <= 2; f++) {
            for (int l = -2; l <= 2; l++) {
                int x = fw.x(cx, f0 + f, l0 + l);
                int z = fw.z(cz, f0 + f, l0 + l);
                boolean rim = Math.abs(f) == 2 || Math.abs(l) == 2;
                TerrainForge.terrace(world, spec, x, z, py - 1, 0, 0, Material.COBBLESTONE);
                world.getBlockAt(x, py, z).setType(rim ? Material.COBBLESTONE_SLAB : Material.WATER);
            }
        }
    }

    /** 작은 범종각 — 대형 종루가 아니다 (사용자의 못). 기둥 넷과 종 하나 */
    private static void bell(World world, int cx, int cy, int cz, RemoteBuilder.Facing fw, int f0, int l0) {
        for (int f : new int[]{-1, 1}) {
            for (int l : new int[]{-1, 1}) {
                for (int y = cy + 1; y <= cy + 4; y++) {
                    world.getBlockAt(fw.x(cx, f0 + f, l0 + l), y, fw.z(cz, f0 + f, l0 + l))
                            .setType(Material.SPRUCE_LOG);
                }
            }
        }
        for (int f = -2; f <= 2; f++) {
            for (int l = -2; l <= 2; l++) {
                RemoteBuilder.put(world, fw.x(cx, f0 + f, l0 + l), cy + 5, fw.z(cz, f0 + f, l0 + l),
                        Material.DEEPSLATE_TILE_SLAB);
            }
        }
        world.getBlockAt(fw.x(cx, f0, l0), cy + 4, fw.z(cz, f0, l0)).setType(Material.IRON_CHAIN);
        world.getBlockAt(fw.x(cx, f0, l0), cy + 3, fw.z(cz, f0, l0)).setType(Material.BELL);
    }

    /** 풍경(風磬) — 처마 아래 매다는 것. 쇠사슬 + 등롱 (금이 아니다) */
    private static void chime(World world, int x, int y, int z) {
        world.getBlockAt(x, y, z).setType(Material.IRON_CHAIN);
        RemoteBuilder.lantern(world, x, y - 1, z);
    }

    /** 대나무 — 아미의 목록. 벚나무가 아니다 */
    private static void bamboo(World world, int x, int cy, int z) {
        for (int y = cy + 1; y <= cy + 3 + Math.floorMod(x * 3 + z * 7, 3); y++) {
            RemoteBuilder.put(world, x, y, z, Material.BAMBOO);
        }
    }

    /** 회랑 — <b>반복되는 기둥</b>이 질서를 만든다 (장식이 아니라 반복이 아미의 언어다) */
    private static void corridor(World world, TerrainForge.SiteSpec spec, int cx, int cz,
                                 RemoteBuilder.Facing fw, int fFrom, int fTo, int l, int cy) {
        for (int f = fTo; f <= fFrom; f++) {
            int x = fw.x(cx, f, l);
            int z = fw.z(cz, f, l);
            TerrainForge.terrace(world, spec, x, z, cy, 0, 0, Material.SPRUCE_PLANKS);
            if (Math.floorMod(f, 3) == 0) {
                for (int y = cy + 1; y <= cy + 3; y++) {
                    world.getBlockAt(x, y, z).setType(Material.SPRUCE_FENCE);   // 가느다란 구조선
                }
            }
            RemoteBuilder.put(world, x, cy + 4, z, Material.DEEPSLATE_TILE_SLAB);   // 좁은 처마 (비와 안개를 피한다)
        }
    }

    /** 장문원의 담 — 작지만 **닫혔다**. 크기가 아니라 폐쇄가 권위를 만든다 */
    private static void walledCourt(World world, TerrainForge.SiteSpec spec, int cx, int cz,
                                    RemoteBuilder.Facing fw, int f0, int cy, int hw, int hd) {
        for (int f = -hw; f <= hw; f++) {
            for (int l = -hd; l <= hd; l++) {
                boolean edge = Math.abs(f) == hw || Math.abs(l) == hd;
                if (!edge || (f == hw && Math.abs(l) <= 1)) {
                    continue;   // 문 하나
                }
                int x = fw.x(cx, f0 + f, l);
                int z = fw.z(cz, f0 + f, l);
                for (int y = cy + 1; y <= cy + 4; y++) {
                    world.getBlockAt(x, y, z).setType(Material.WHITE_TERRACOTTA);
                }
                RemoteBuilder.put(world, x, cy + 5, z, Material.DEEPSLATE_TILE_SLAB);
            }
        }
    }

    /** 가느다란 석계단 — 폭 3. 절벽을 잇는다 (도관의 천 계단은 폭 11이다 — 그 차이가 이 문파다) */
    private static void narrowStair(World world, TerrainForge.SiteSpec spec, int cx, int cz,
                                    RemoteBuilder.Facing fw, int fFrom, int fTo, int yLow, int yHigh) {
        int run = fFrom - fTo;
        if (run <= 0) {
            return;
        }
        for (int i = 0; i <= run; i++) {
            int f = fFrom - i;
            int y = yLow + (int) Math.round((double) (yHigh - yLow) * i / run);
            for (int l = -1; l <= 1; l++) {
                TerrainForge.terrace(world, spec, fw.x(cx, f, l), fw.z(cz, f, l), y, 0, 0,
                        Material.COBBLESTONE);
            }
        }
    }

    // ─── 공용 손 ───

    /** 작은 단 하나 — <b>산을 누르지 않는다</b>. 큰 평지를 깎는 대신 필요한 만큼만 요청한다 */
    private static void pad(World world, TerrainForge.SiteSpec spec, int cx, int cz,
                            RemoteBuilder.Facing fw, int f0, int l0, int y, int hf, int hl, Material floor) {
        for (int f = -hf; f <= hf; f++) {
            for (int l = -hl; l <= hl; l++) {
                TerrainForge.terrace(world, spec, fw.x(cx, f0 + f, l0 + l), fw.z(cz, f0 + f, l0 + l),
                        y, 0, 0, floor);
            }
        }
    }

    /**
     * 회벽 집 한 채 — 백벽 · 검은 기와 · 노출 기둥. 그리고 <b>가늘고 긴 세로 창</b>.
     *
     * <p>이 손은 사찰의 승방과 아미의 선방이 같이 쓴다. <b>같은 손이 다른 집을 짓는 것은 문제가 아니다</b> —
     * 문제였던 것은 <b>같은 집이 여덟 세력에 선 것</b>이다. 원형을 가르는 것은 벽돌 한 장이 아니라
     * <b>배치·실루엣·무엇이 있고 무엇이 없는가</b>이다.
     */
    private static void plasterHall(World world, TerrainForge.SiteSpec spec, int cx, int cy, int cz,
                                    RemoteBuilder.Facing fw, int f0, int l0, int hf, int hl, int doorSide) {
        pad(world, spec, cx, cz, fw, f0, l0, cy, hf + 1, hl + 1, Material.COBBLESTONE);
        for (int f = -hf; f <= hf; f++) {
            for (int l = -hl; l <= hl; l++) {
                int x = fw.x(cx, f0 + f, l0 + l);
                int z = fw.z(cz, f0 + f, l0 + l);
                world.getBlockAt(x, cy, z).setType(Material.SPRUCE_PLANKS);
                boolean edge = Math.abs(f) == hf || Math.abs(l) == hl;
                if (!edge) {
                    continue;
                }
                boolean corner = Math.abs(f) == hf && Math.abs(l) == hl;
                for (int y = cy + 1; y <= cy + 4; y++) {
                    world.getBlockAt(x, y, z).setType(corner ? Material.SPRUCE_LOG
                            : Material.WHITE_TERRACOTTA);
                }
                // 가늘고 긴 세로 창 — 아미의 표식 (가로로 넓은 격자창이 아니다)
                if (!corner && Math.floorMod(f * 3 + l * 5, 4) == 0) {
                    world.getBlockAt(x, cy + 2, z).setType(Material.GLASS_PANE);
                    world.getBlockAt(x, cy + 3, z).setType(Material.GLASS_PANE);
                }
            }
        }
        int dl = doorSide > 0 ? hl : -hl;
        for (int y = cy + 1; y <= cy + 2; y++) {
            world.getBlockAt(fw.x(cx, f0, l0 + dl), y, fw.z(cz, f0, l0 + dl)).setType(Material.AIR);
        }
        for (int i = 0; i <= Math.min(hf, hl); i++) {   // 검은 기와 — 능선으로 수렴
            int y = cy + 5 + i;
            for (int f = -hf - 1 + i; f <= hf + 1 - i; f++) {
                RemoteBuilder.put(world, fw.x(cx, f0 + f, l0 - hl - 1 + i), y,
                        fw.z(cz, f0 + f, l0 - hl - 1 + i), Material.DEEPSLATE_TILES);
                RemoteBuilder.put(world, fw.x(cx, f0 + f, l0 + hl + 1 - i), y,
                        fw.z(cz, f0 + f, l0 + hl + 1 - i), Material.DEEPSLATE_TILES);
            }
            for (int l = -hl + i; l <= hl - i; l++) {
                RemoteBuilder.put(world, fw.x(cx, f0 - hf - 1 + i, l0 + l), y,
                        fw.z(cz, f0 - hf - 1 + i, l0 + l), Material.DEEPSLATE_TILES);
                RemoteBuilder.put(world, fw.x(cx, f0 + hf + 1 - i, l0 + l), y,
                        fw.z(cz, f0 + hf + 1 - i, l0 + l), Material.DEEPSLATE_TILES);
            }
        }
        RemoteBuilder.lantern(world, fw.x(cx, f0, l0), cy + 4, fw.z(cz, f0, l0));
    }

    /** 전(殿) 한 채 — 사찰의 어휘. 문이 <b>셋</b>이다 (삼문 — 불문의 격) */
    private static void hall(World world, TerrainForge.SiteSpec spec, int cx, int cy, int cz,
                             RemoteBuilder.Facing fw, int f0, int hl, int hf, int wallH, boolean triple) {
        pad(world, spec, cx, cz, fw, f0, 0, cy, hf + 2, hl + 2, Material.POLISHED_ANDESITE);
        for (int f = -hf; f <= hf; f++) {
            for (int l = -hl; l <= hl; l++) {
                int x = fw.x(cx, f0 + f, l);
                int z = fw.z(cz, f0 + f, l);
                world.getBlockAt(x, cy, z).setType(Material.POLISHED_ANDESITE);
                boolean edge = Math.abs(f) == hf || Math.abs(l) == hl;
                if (!edge) {
                    continue;
                }
                boolean corner = Math.abs(f) == hf && Math.abs(l) == hl;
                for (int y = cy + 1; y <= cy + wallH; y++) {
                    world.getBlockAt(x, y, z).setType(corner ? Material.DARK_OAK_LOG
                            : Material.WHITE_TERRACOTTA);
                }
            }
        }
        int[] doors = triple ? new int[]{-4, 0, 4} : new int[]{0};
        for (int d : doors) {
            for (int l = d - 1; l <= d + 1; l++) {
                for (int y = cy + 1; y <= cy + 3; y++) {
                    world.getBlockAt(fw.x(cx, f0 + hf, l), y, fw.z(cz, f0 + hf, l)).setType(Material.AIR);
                }
            }
        }
        tiledRoof(world, cx, cy + wallH, cz, fw, f0, hf, hl);
        RemoteBuilder.put(world, fw.x(cx, f0 - hf + 1, 0), cy + 1, fw.z(cz, f0 - hf + 1, 0),
                Material.CAULDRON);
        RemoteBuilder.candlesAt(world, fw.x(cx, f0 - hf + 1, -2), cy + 1, fw.z(cz, f0 - hf + 1, -2));
        RemoteBuilder.candlesAt(world, fw.x(cx, f0 - hf + 1, 2), cy + 1, fw.z(cz, f0 - hf + 1, 2));
        RemoteBuilder.lantern(world, fw.x(cx, f0, 0), cy + wallH, fw.z(cz, f0, 0));
    }

    /** 대웅보전 — <b>월대(月臺)</b> 위에 선다. 기단 세 켜가 이 집을 다른 전각보다 <b>들어올린다</b> */
    private static void podiumHall(World world, TerrainForge.SiteSpec spec, int cx, int cy, int cz,
                                   RemoteBuilder.Facing fw, int f0, int hl, int hf) {
        int py = cy + 3;
        for (int f = -hf - 4; f <= hf + 4; f++) {   // 월대 — 세 켜 돌기단
            for (int l = -hl - 4; l <= hl + 4; l++) {
                int x = fw.x(cx, f0 + f, l);
                int z = fw.z(cz, f0 + f, l);
                for (int y = cy; y <= py; y++) {
                    world.getBlockAt(x, y, z).setType(Material.STONE_BRICKS);
                }
                TerrainForge.sealBelow(world, x, cy, z);
            }
        }
        for (int i = 0; i < 3; i++) {   // 월대로 오르는 계단 (오르는 집이다)
            for (int l = -5; l <= 5; l++) {
                world.getBlockAt(fw.x(cx, f0 + hf + 5 + i, l), py - i, fw.z(cz, f0 + hf + 5 + i, l))
                        .setType(Material.STONE_BRICKS);
                TerrainForge.sealBelow(world, fw.x(cx, f0 + hf + 5 + i, l), py - i,
                        fw.z(cz, f0 + hf + 5 + i, l));
            }
        }
        hall(world, spec, cx, py, cz, fw, f0, hl, hf, 6, true);
    }

    /** 검은 기와 지붕 — 능선으로 수렴 (관·문파가 함께 쓰는 물매) */
    private static void tiledRoof(World world, int cx, int cy, int cz, RemoteBuilder.Facing fw,
                                  int f0, int hf, int hl) {
        for (int i = 0; i <= Math.min(hf, hl); i++) {
            int y = cy + 1 + i;
            for (int f = -hf - 1 + i; f <= hf + 1 - i; f++) {
                RemoteBuilder.put(world, fw.x(cx, f0 + f, -hl - 1 + i), y, fw.z(cz, f0 + f, -hl - 1 + i),
                        Material.DEEPSLATE_TILES);
                RemoteBuilder.put(world, fw.x(cx, f0 + f, hl + 1 - i), y, fw.z(cz, f0 + f, hl + 1 - i),
                        Material.DEEPSLATE_TILES);
            }
            for (int l = -hl + i; l <= hl - i; l++) {
                RemoteBuilder.put(world, fw.x(cx, f0 - hf - 1 + i, l), y, fw.z(cz, f0 - hf - 1 + i, l),
                        Material.DEEPSLATE_TILES);
                RemoteBuilder.put(world, fw.x(cx, f0 + hf + 1 - i, l), y, fw.z(cz, f0 + hf + 1 - i, l),
                        Material.DEEPSLATE_TILES);
            }
        }
    }

    /** 산문(패방) — 폭은 부르는 자가 정한다. 대찰은 넓고 암자는 없다 */
    private static void mountainGate(World world, int cx, int cy, int cz, RemoteBuilder.Facing fw,
                                     int gf, int half) {
        for (int l : new int[]{-half, -half + 1, half - 1, half}) {
            for (int y = cy + 1; y <= cy + 6; y++) {
                world.getBlockAt(fw.x(cx, gf, l), y, fw.z(cz, gf, l)).setType(Material.STONE_BRICKS);
            }
        }
        for (int l = -half - 1; l <= half + 1; l++) {
            world.getBlockAt(fw.x(cx, gf, l), cy + 7, fw.z(cz, gf, l)).setType(Material.POLISHED_ANDESITE);
            world.getBlockAt(fw.x(cx, gf, l), cy + 8, fw.z(cz, gf, l)).setType(Material.DEEPSLATE_TILES);
        }
        for (int l = -half - 2; l <= half + 2; l++) {
            RemoteBuilder.put(world, fw.x(cx, gf - 1, l), cy + 8, fw.z(cz, gf - 1, l),
                    Material.DEEPSLATE_TILE_SLAB);
            RemoteBuilder.put(world, fw.x(cx, gf + 1, l), cy + 8, fw.z(cz, gf + 1, l),
                    Material.DEEPSLATE_TILE_SLAB);
        }
        world.getBlockAt(fw.x(cx, gf, 0), cy + 6, fw.z(cz, gf, 0)).setType(Material.DARK_OAK_PLANKS);
    }

    /** 목인장 — 무승이 치는 나무 사람 */
    private static void woodenDummy(World world, int x, int cy, int z, RemoteBuilder.Facing fw) {
        for (int y = cy + 1; y <= cy + 3; y++) {
            world.getBlockAt(x, y, z).setType(Material.STRIPPED_DARK_OAK_LOG);
        }
        RemoteBuilder.put(world, x + 1, cy + 2, z, Material.DARK_OAK_FENCE);
        RemoteBuilder.put(world, x - 1, cy + 2, z, Material.DARK_OAK_FENCE);
    }

    /** 보리수 — ★ <b>매화가 아니다.</b> 불문의 나무는 넓은 잎이고 줄기가 굵다 */
    private static void bodhiTree(World world, int x, int cy, int z) {
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                for (int y = cy + 1; y <= cy + 6; y++) {
                    world.getBlockAt(x + dx, y, z + dz).setType(Material.OAK_LOG);
                }
            }
        }
        for (int dx = -4; dx <= 5; dx++) {
            for (int dz = -4; dz <= 5; dz++) {
                int d = Math.abs(dx) + Math.abs(dz);
                if (d > 7) {
                    continue;
                }
                RemoteBuilder.put(world, x + dx, cy + 7, z + dz, Material.OAK_LEAVES);
                if (d <= 4) {
                    RemoteBuilder.put(world, x + dx, cy + 8, z + dz, Material.OAK_LEAVES);
                }
                if (d <= 2) {
                    RemoteBuilder.put(world, x + dx, cy + 9, z + dz, Material.OAK_LEAVES);
                }
            }
        }
    }

    /** 천 계단의 축소판 — 전각(무당)이 봉우리로 오르는 길 */
    private static void steps(World world, TerrainForge.SiteSpec spec, int cx, int cz,
                              RemoteBuilder.Facing fw, int fFrom, int fTo, int yHigh, int yLow) {
        int run = fTo - fFrom;
        for (int i = 0; i <= run; i++) {
            int f = fTo - i;
            int y = yLow + (int) Math.round((double) (yHigh - yLow) * i / run);
            int half = Math.max(2, 4 - i / 6);
            for (int l = -half; l <= half; l++) {
                TerrainForge.terrace(world, spec, fw.x(cx, f, l), fw.z(cz, f, l), y, 0, 0,
                        Material.POLISHED_ANDESITE);
            }
            if (Math.floorMod(i, 5) == 0) {
                for (int l : new int[]{-half, half}) {
                    world.getBlockAt(fw.x(cx, f, l), y + 1, fw.z(cz, f, l)).setType(Material.COBBLESTONE_WALL);
                    world.getBlockAt(fw.x(cx, f, l), y + 2, fw.z(cz, f, l)).setType(Material.LANTERN);
                }
            }
        }
    }
}
