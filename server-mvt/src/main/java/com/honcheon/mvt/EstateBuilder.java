package com.honcheon.mvt;

import org.bukkit.Material;
import org.bukkit.World;

import java.util.List;

/**
 * 오대세가의 집 — <b>사용자가 「장원 하나 + 변주」를 거부했다</b> (§16: Q3 "오대세가는 다섯을 각각 따로").
 *
 * <p>그래서 자재를 바꾼 같은 집 다섯이 아니라, <b>기하가 정반대인 집들</b>이다.
 * 지도가 그 대립을 이미 적어 두었다 —
 * <blockquote>
 *   당가: <b>높은 담 · 좁은 문 하나</b> · 약재 마당 · <b>안이 안 보인다</b><br>
 *   팽가: <b>낮은 담 · 열린 문</b> · <b>넓은 연무장</b>(집의 중심) · <b>숨기지 않는다</b><br>
 *   §16 의 주석: <i>"★ 당가와 같은 반경(48), 정반대의 집 — <b>담의 높이가 곧 그 가문이다</b>"</i>
 * </blockquote>
 *
 * <h2>★ 제갈(기관저택)은 여기 없다 — 지어내지 않았다</h2>
 * 지도가 이름은 주었으나(`archetype: 기관저택`) <b>계약을 pending 으로 두었다</b>:
 * <blockquote>"★ <b>이름은 있는데 계약이 없다</b> — <i>무엇이 있어야 제갈이 제갈인가?</i>
 *   '미로'·'움직이는 돌'은 그림이지 계약이 아니다 (Q-B)"</blockquote>
 * <b>그러므로 짓지 않는다.</b> {@code unbuildableReasons} 가 "청구됐으나 아직 안 지어졌다"고 말한다 —
 * <b>비슷한 원형(폐쇄대저택)으로 대신 짓는 것이 곧 오늘 잡은 그 거짓말이다.</b>
 */
final class EstateBuilder {

    private EstateBuilder() {
    }

    static List<Zone> build(World world, WorldMap.Place place, TerrainForge.SiteSpec spec,
                            RemoteBuilder.Archetype kind) {
        return switch (kind) {
            case 폐쇄대저택 -> closedEstate(world, place, spec);
            case 군사저택 -> martialEstate(world, place, spec);
            case 정원저택 -> gardenEstate(world, place, spec);
            case 북방저택 -> northEstate(world, place, spec);
            default -> List.of();
        };
    }

    // ══════════════════════════════════════════════════════════════════
    //  폐쇄대저택 — 당가. <b>담이 여섯 켜고 문이 하나다</b>
    // ══════════════════════════════════════════════════════════════════

    /**
     * 등록부: <b>"분지의 폐쇄적 대저택 — 높은 담, 좁은 문, 약재 마당. 외부인이 못 들어온다"</b>.
     *
     * <p>이 집의 전부는 <b>담</b>이다. 여섯 켜 — 사람 키의 세 배. 밖에서는 <b>지붕 꼭대기만</b> 보인다.
     * 문은 <b>하나</b>고 <b>세 칸</b>이며, 문 안에는 곧바로 <b>가림벽</b>이 서서 안이 안 보인다.
     * (도관의 정반대다 — 도관은 산문이 활짝 열려 있고 오르는 길이 전부 보인다.)
     *
     * <p>마당은 <b>약재밭</b>이다. 당가가 무엇으로 사는지가 마당에 심겨 있다.
     */
    private static List<Zone> closedEstate(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        RemoteBuilder.Facing fw = RemoteBuilder.entry(spec);
        int cx = spec.cx();
        int cz = spec.cz();
        int rad = spec.radius();
        int cy = spec.groundY();
        int half = 20;

        TerrainForge.terrace(world, spec, cx, cz, cy, half + 1, half + 1, Material.COARSE_DIRT);
        RemoteBuilder.approachPath(world, spec, cx, cz, fw, half + 2, rad + 12, cy, 1, Material.DIRT_PATH);

        // ★ 높은 담 — 여섯 켜. 문은 **하나**, 폭 3
        wall(world, cx, cy, cz, fw, half, 6, 1, Material.STONE_BRICKS);
        // 문루 — 좁다. 문 위에 지붕 한 겹만 (성문의 성루가 아니다)
        for (int l = -3; l <= 3; l++) {
            RemoteBuilder.put(world, fw.x(cx, half, l), cy + 7, fw.z(cz, half, l), Material.DEEPSLATE_TILES);
            RemoteBuilder.put(world, fw.x(cx, half + 1, l), cy + 7, fw.z(cz, half + 1, l),
                    Material.DEEPSLATE_TILE_SLAB);
        }
        RemoteBuilder.lantern(world, fw.x(cx, half + 1, -2), cy + 6, fw.z(cz, half + 1, -2));
        RemoteBuilder.lantern(world, fw.x(cx, half + 1, 2), cy + 6, fw.z(cz, half + 1, 2));

        // ★ 가림벽(影壁) — 문 안 다섯 칸. **들어와도 안이 안 보인다**
        for (int l = -5; l <= 5; l++) {
            for (int y = cy + 1; y <= cy + 5; y++) {
                world.getBlockAt(fw.x(cx, half - 5, l), y, fw.z(cz, half - 5, l))
                        .setType(Material.WHITE_TERRACOTTA);
            }
            RemoteBuilder.put(world, fw.x(cx, half - 5, l), cy + 6, fw.z(cz, half - 5, l),
                    Material.DEEPSLATE_TILE_SLAB);
        }
        // 약재 마당 — 이 가문이 무엇으로 사는가
        for (int f = -8; f <= 6; f++) {
            for (int l = -14; l <= 14; l++) {
                if (Math.floorMod(l + 14, 4) == 3) {
                    continue;   // 이랑 사이의 길
                }
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                world.getBlockAt(x, cy, z).setType(Material.FARMLAND);
                RemoteBuilder.put(world, x, cy + 1, z, Math.floorMod(x * 3 + z, 5) == 0
                        ? Material.SWEET_BERRY_BUSH : Material.SHORT_GRASS);
            }
        }
        for (int l = -14; l <= 14; l += 7) {   // 약재를 담는 통 — 마당 가장자리
            RemoteBuilder.put(world, fw.x(cx, 8, l), cy + 1, fw.z(cz, 8, l), Material.BARREL);
        }
        // 안채 — 담 안쪽 깊은 곳. 회벽·검은 기와. **밖에서는 지붕만 보인다**
        estateHall(world, spec, cx, cy, cz, fw, -13, 0, 9, 5, true);
        estateHall(world, spec, cx, cy, cz, fw, -4, -15, 4, 3, true);   // 곁채 (약 달이는 곳)
        RemoteBuilder.put(world, fw.x(cx, -4, -15), cy + 1, fw.z(cz, -4, -15), Material.CAULDRON);

        for (int[] p : new int[][]{{half - 8, -8}, {half - 8, 8}, {-2, -12}, {-2, 12}, {-9, 0}}) {
            RemoteBuilder.lanternPost(world, fw.x(cx, p[0], p[1]), cy, fw.z(cz, p[0], p[1]));
        }
        return List.of(new Zone(place.name(), "폐쇄대저택 — 높은 담, 좁은 문. 안이 안 보인다", world.getName(),
                cx - rad, cy - 8, cz - rad, cx + rad, cy + 22, cz + rad));
    }

    // ══════════════════════════════════════════════════════════════════
    //  군사저택 — 팽가. <b>담이 두 켜고 문이 아홉 칸이다</b>
    // ══════════════════════════════════════════════════════════════════

    /**
     * 등록부: <b>"평야의 개방적 군사 저택 — 넓은 연무장, 낮은 담. 숨기지 않는다 (도의 가문)"</b>.
     *
     * <p>당가와 <b>같은 반경(48)</b>이고 <b>같은 자재</b>다. 다른 것은 <b>기하</b>뿐이고,
     * 그 기하가 두 가문을 갈라놓는다:
     * <table>
     *   <tr><th></th><th>당가</th><th>팽가</th></tr>
     *   <tr><td>담</td><td>여섯 켜 (안이 안 보인다)</td><td><b>두 켜</b> (걸어오면서 안이 다 보인다)</td></tr>
     *   <tr><td>문</td><td>세 칸 하나 + 가림벽</td><td><b>아홉 칸</b> · 가림벽 없음</td></tr>
     *   <tr><td>마당</td><td>약재밭</td><td><b>연무장</b> — 이 집의 중심이다</td></tr>
     * </table>
     */
    private static List<Zone> martialEstate(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        RemoteBuilder.Facing fw = RemoteBuilder.entry(spec);
        int cx = spec.cx();
        int cz = spec.cz();
        int rad = spec.radius();
        int cy = spec.groundY();
        int half = 22;

        TerrainForge.terrace(world, spec, cx, cz, cy, half + 1, half + 1, Material.POLISHED_ANDESITE);
        RemoteBuilder.approachPath(world, spec, cx, cz, fw, half + 2, rad + 12, cy, 3, Material.DIRT_PATH);

        // ★ 낮은 담 — 두 켜. 담장 블록이라 **너머가 보인다** (숨기지 않는 집이다)
        for (int f = -half; f <= half; f++) {
            for (int l = -half; l <= half; l++) {
                boolean edge = Math.abs(f) == half || Math.abs(l) == half;
                if (!edge || (f == half && Math.abs(l) <= 4)) {
                    continue;   // ★ 문 아홉 칸 — 문짝도 없다
                }
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                world.getBlockAt(x, cy + 1, z).setType(Material.STONE_BRICKS);
                world.getBlockAt(x, cy + 2, z).setType(Material.STONE_BRICK_WALL);
            }
        }
        for (int l : new int[]{-5, 5}) {   // 문설주 둘 — 문루가 아니다. 기둥만 있다
            for (int y = cy + 1; y <= cy + 5; y++) {
                world.getBlockAt(fw.x(cx, half, l), y, fw.z(cz, half, l)).setType(Material.DARK_OAK_LOG);
            }
            RemoteBuilder.lantern(world, fw.x(cx, half, l), cy + 6, fw.z(cz, half, l));
        }
        for (int l = -5; l <= 5; l++) {
            RemoteBuilder.put(world, fw.x(cx, half, l), cy + 6, fw.z(cz, half, l),
                    Material.DARK_OAK_PLANKS);   // 들보 한 줄 — 여기가 문이다
        }

        // ★ 연무장 — **집의 중심**이다 (당가의 중심은 마당이 아니라 안채였다).
        //   문에서 들어오면 곧바로 이것이다. 아무것도 그 앞을 막지 않는다
        for (int f = -10; f <= 14; f++) {
            for (int l = -16; l <= 16; l++) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                world.getBlockAt(x, cy, z).setType(Math.floorMod(x + z, 8) == 0
                        ? Material.ANDESITE : Material.POLISHED_ANDESITE);
            }
        }
        for (int i = -2; i <= 2; i++) {   // 목인장 다섯 줄로 늘어선다 (군대의 열이다)
            for (int j = 0; j < 2; j++) {
                int x = fw.x(cx, 2 + j * 8, i * 7);
                int z = fw.z(cz, 2 + j * 8, i * 7);
                for (int y = cy + 1; y <= cy + 3; y++) {
                    world.getBlockAt(x, y, z).setType(Material.STRIPPED_DARK_OAK_LOG);
                }
                RemoteBuilder.put(world, x + 1, cy + 2, z, Material.DARK_OAK_FENCE);
                RemoteBuilder.put(world, x - 1, cy + 2, z, Material.DARK_OAK_FENCE);
            }
        }
        // 병기 시렁 — 연무장 가장자리. **도(刀)의 가문**이다
        long seed = Math.floorMod(31L * cx + cz, 1_000_003L);
        for (int i = 0; i < 4; i++) {
            int l = -12 + i * 8;
            for (int y = cy + 1; y <= cy + 3; y++) {
                world.getBlockAt(fw.x(cx, 17, l), y, fw.z(cz, 17, l)).setType(Material.DARK_OAK_LOG);
            }
            RemoteBuilder.shelf(world, fw.x(cx, 17, l), cy + 2, fw.z(cz, 17, l), fw.out(),
                    Weapons.makeSeeded(Weapons.Series.도, Weapons.Grade.정련, seed + i),
                    Weapons.makeSeeded(Weapons.Series.도, Weapons.Grade.보병, seed + i + 5),
                    Weapons.makeSeeded(Weapons.Series.도, Weapons.Grade.범철, seed + i + 9));
        }
        // 본채 — 연무장 **뒤**에 물러선다 (연무장이 중심이므로 집은 배경이다)
        estateHall(world, spec, cx, cy, cz, fw, -16, 0, 10, 5, false);
        estateHall(world, spec, cx, cy, cz, fw, -14, -17, 4, 4, false);
        estateHall(world, spec, cx, cy, cz, fw, -14, 17, 4, 4, false);

        for (int f = 16; f >= -12; f -= 9) {
            RemoteBuilder.lanternPost(world, fw.x(cx, f, -18), cy, fw.z(cz, f, -18));
            RemoteBuilder.lanternPost(world, fw.x(cx, f, 18), cy, fw.z(cz, f, 18));
        }
        return List.of(new Zone(place.name(), "군사저택 — 낮은 담. 숨기지 않는다", world.getName(),
                cx - rad, cy - 8, cz - rad, cx + rad, cy + 22, cz + rad));
    }

    // ══════════════════════════════════════════════════════════════════
    //  정원저택 — 남궁. <b>물이 이 집이다</b>
    // ══════════════════════════════════════════════════════════════════

    /**
     * 등록부: <b>"강남의 검 — 물과 정원. 회랑이 연못을 끼고 돈다. 검각이 정원 가운데"</b>.
     *
     * <p>세가 중 유일하게 <b>반경 64</b>다 (영향력 10 — 화산과 같다).
     * 그리고 유일하게 <b>기하의 중심이 건물이 아니다</b>: 가운데가 <b>물</b>이고,
     * 회랑이 그 물을 <b>끼고 돌고</b>, 검각(劍閣)이 물 위에 <b>섬처럼</b> 선다.
     *
     * <h3>★ 물을 어떻게 얻었는가 — 정직하게 적는다</h3>
     * 지도가 경고했다: <i>"물이 필요하다 — 지형 계층에 청구해야 한다
     * (config/land_requests.yml — ★ requests: 가 <b>비어 있다</b>. Q-J)"</i>.
     * 그 문(門)이 비어 있으므로, <b>이 조성기는 자연 지형을 파지 않는다</b>.
     * 대신 <b>제가 깐 단(壇) 안</b>을 두 칸 파고 <b>돌로 두르고</b> 물을 채운다 —
     * 아미의 세검지와 같은 손이다. 이것은 <b>땅이 아니라 집의 일부</b>다 (기단 위의 수조).
     * <b>그러나 이것이 최선인지는 확신하지 않는다</b> — 등록부에 {@code land_requests} 를
     * 여는 편이 더 옳을 수 있고, 그것은 사람이 정할 일이다.
     */
    private static List<Zone> gardenEstate(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        RemoteBuilder.Facing fw = RemoteBuilder.entry(spec);
        int cx = spec.cx();
        int cz = spec.cz();
        int rad = spec.radius();
        int cy = spec.groundY();
        int half = 26;

        TerrainForge.terrace(world, spec, cx, cz, cy, half + 1, half + 1, Material.POLISHED_ANDESITE);
        RemoteBuilder.approachPath(world, spec, cx, cz, fw, half + 2, rad + 12, cy, 2, Material.DIRT_PATH);
        wall(world, cx, cy, cz, fw, half, 3, 2, Material.WHITE_TERRACOTTA);   // 담 — 중간 높이. 강남은 우아하다

        // ★ 연못 — 조성이 깐 단 **안**을 판다. 자연 지형은 안 만진다
        int pr = 15;
        for (int f = -pr; f <= pr; f++) {
            for (int l = -pr; l <= pr; l++) {
                double d = RemoteBuilder.dist(f, l);
                if (d > pr) {
                    continue;
                }
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                if (d > pr - 1.2) {
                    world.getBlockAt(x, cy, z).setType(Material.COBBLESTONE);   // 못의 테두리 — 돌로 두른다
                    continue;
                }
                world.getBlockAt(x, cy, z).setType(Material.WATER);
                world.getBlockAt(x, cy - 1, z).setType(Material.WATER);
                world.getBlockAt(x, cy - 2, z).setType(Material.COBBLESTONE);   // 바닥을 깐다 (구덩이가 아니라 수조다)
                TerrainForge.sealBelow(world, x, cy - 2, z);
            }
        }
        // ★ 회랑 — **연못을 끼고 돈다**. 물 위로 한 칸 뜬 마루 + 반복되는 기둥 + 처마
        for (int i = 0; i < 96; i++) {
            double a = Math.PI * 2 * i / 96.0;
            int f = (int) Math.round(Math.cos(a) * (pr + 2));
            int l = (int) Math.round(Math.sin(a) * (pr + 2));
            int x = fw.x(cx, f, l);
            int z = fw.z(cz, f, l);
            world.getBlockAt(x, cy + 1, z).setType(Material.DARK_OAK_PLANKS);
            if (Math.floorMod(i, 6) == 0) {
                for (int y = cy + 2; y <= cy + 4; y++) {
                    world.getBlockAt(x, y, z).setType(Material.DARK_OAK_FENCE);
                }
            }
            RemoteBuilder.put(world, x, cy + 5, z, Material.DEEPSLATE_TILES);   // 처마 — 비를 피해 걷는다
            if (Math.floorMod(i, 12) == 0) {
                RemoteBuilder.lantern(world, x, cy + 4, z);   // 물에 비치는 불 — 강남의 밤
            }
        }
        // 못을 건너는 다리 — 검각으로 간다 (물 위를 걸어서 들어간다)
        for (int f = pr + 1; f >= 6; f--) {
            for (int l = -1; l <= 1; l++) {
                world.getBlockAt(fw.x(cx, f, l), cy + 1, fw.z(cz, f, l)).setType(Material.DARK_OAK_PLANKS);
            }
            RemoteBuilder.put(world, fw.x(cx, f, -2), cy + 2, fw.z(cz, f, -2), Material.DARK_OAK_FENCE);
            RemoteBuilder.put(world, fw.x(cx, f, 2), cy + 2, fw.z(cz, f, 2), Material.DARK_OAK_FENCE);
        }
        // ★ 검각(劍閣) — 정원 **가운데**. 물 한복판에 선 2층 각(閣)
        int gy = cy + 1;
        for (int f = -6; f <= 6; f++) {
            for (int l = -6; l <= 6; l++) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                for (int y = cy - 2; y <= gy; y++) {
                    world.getBlockAt(x, y, z).setType(y == gy ? Material.POLISHED_ANDESITE
                            : Material.STONE_BRICKS);   // 물 속의 기단 — 각은 물 위에 떠 있지 않다
                }
            }
        }
        for (int floor = 0; floor < 2; floor++) {
            int hy = gy + floor * 6;
            int hh = 5 - floor;
            for (int f = -hh; f <= hh; f++) {
                for (int l = -hh; l <= hh; l++) {
                    int x = fw.x(cx, f, l);
                    int z = fw.z(cz, f, l);
                    RemoteBuilder.put(world, x, hy, z, Material.DARK_OAK_PLANKS);
                    boolean edge = Math.abs(f) == hh || Math.abs(l) == hh;
                    boolean corner = Math.abs(f) == hh && Math.abs(l) == hh;
                    if (!edge) {
                        continue;
                    }
                    for (int y = hy + 1; y <= hy + 4; y++) {
                        world.getBlockAt(x, y, z).setType(corner ? Material.DARK_OAK_LOG
                                : Material.WHITE_TERRACOTTA);
                    }
                    if (Math.floorMod(f + l, 2) == 0) {
                        world.getBlockAt(x, hy + 2, z).setType(Material.GLASS_PANE);
                    }
                }
            }
            for (int l = -1; l <= 1; l++) {   // 문 — 다리 쪽
                for (int y = hy + 1; y <= hy + 3; y++) {
                    world.getBlockAt(fw.x(cx, hh, l), y, fw.z(cz, hh, l)).setType(Material.AIR);
                }
            }
            for (int i = 0; i <= hh; i++) {   // 처마
                int y = hy + 5 + i;
                for (int f = -hh - 1 + i; f <= hh + 1 - i; f++) {
                    RemoteBuilder.put(world, fw.x(cx, f, -hh - 1 + i), y, fw.z(cz, f, -hh - 1 + i),
                            Material.DEEPSLATE_TILES);
                    RemoteBuilder.put(world, fw.x(cx, f, hh + 1 - i), y, fw.z(cz, f, hh + 1 - i),
                            Material.DEEPSLATE_TILES);
                }
                for (int l = -hh + i; l <= hh - i; l++) {
                    RemoteBuilder.put(world, fw.x(cx, -hh - 1 + i, l), y, fw.z(cz, -hh - 1 + i, l),
                            Material.DEEPSLATE_TILES);
                    RemoteBuilder.put(world, fw.x(cx, hh + 1 - i, l), y, fw.z(cz, hh + 1 - i, l),
                            Material.DEEPSLATE_TILES);
                }
            }
        }
        // 검각의 살림 — 전승의 검. 남궁은 **검**의 가문이다
        long seed = Math.floorMod(31L * cx + cz, 1_000_003L);
        RemoteBuilder.shelf(world, fw.x(cx, -4, 0), gy + 2, fw.z(cz, -4, 0), fw.out(),
                Weapons.makeSeeded(Weapons.Series.검, Weapons.Grade.보병, seed),
                Weapons.makeSeeded(Weapons.Series.검, Weapons.Grade.정련, seed + 1),
                Weapons.makeSeeded(Weapons.Series.검, Weapons.Grade.보병, seed + 2));
        RemoteBuilder.lantern(world, cx, gy + 4, cz);
        // 정원 — 못 밖. 나무와 바위 (꽃밭이 아니다: 수묵의 규약)
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8.0 + 0.4;
            int gx = cx + (int) Math.round(Math.cos(a) * 22);
            int gz = cz + (int) Math.round(Math.sin(a) * 22);
            if (!spec.inside(gx, gz)) {
                continue;
            }
            for (int y = cy + 1; y <= cy + 4; y++) {
                RemoteBuilder.put(world, gx, y, gz, Material.OAK_LOG);
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > 3) {
                        continue;
                    }
                    RemoteBuilder.put(world, gx + dx, cy + 5, gz + dz, Material.OAK_LEAVES);
                }
            }
        }
        return List.of(new Zone(place.name(), "정원저택 — 회랑이 연못을 끼고 돈다", world.getName(),
                cx - rad, cy - 12, cz - rad, cx + rad, cy + 24, cz + rad));
    }

    // ══════════════════════════════════════════════════════════════════
    //  북방저택 — 모용. <b>벽이 세 칸이고 굴뚝에서 연기가 난다</b>
    // ══════════════════════════════════════════════════════════════════

    /**
     * 등록부: <b>"요동의 북방 세가 — 두꺼운 벽, 온돌"</b>.
     *
     * <p>중원의 집은 <b>얇다</b> (회벽 한 켜). 요동의 집은 <b>세 켜</b>다 — 추위를 막는 벽이기 때문이다.
     * 그리고 <b>온돌</b>: 집마다 굴뚝이 있고, 굴뚝에서 <b>연기가 오른다</b>.
     * 이 세계에서 <b>연기 나는 굴뚝은 여기뿐이다</b> — 밤에도 낮에도, 멀리서 이 집은 <b>연기</b>로 읽힌다.
     *
     * <p><b>★ 못 정한 것</b>: 지도가 물었다 — <i>"어디까지 지을지가 미결. sect_lineage.md 5장:
     * <b>입문 불가</b>. 담 안을 지을 것인가? (Q-C)"</i>. 지금은 <b>담 안까지 짓되 안채의 안은 비운다</b>
     * (문은 열려 있으나 살림이 얇다 — 손님을 받지 않는 집이라는 뜻이다). <b>사람이 정하면 고친다.</b>
     */
    private static List<Zone> northEstate(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        RemoteBuilder.Facing fw = RemoteBuilder.entry(spec);
        int cx = spec.cx();
        int cz = spec.cz();
        int rad = spec.radius();
        int cy = spec.groundY();
        int half = 20;

        TerrainForge.terrace(world, spec, cx, cz, cy, half + 1, half + 1, Material.COARSE_DIRT);
        RemoteBuilder.approachPath(world, spec, cx, cz, fw, half + 2, rad + 12, cy, 2, Material.DIRT_PATH);
        wall(world, cx, cy, cz, fw, half, 4, 2, Material.STONE_BRICKS);

        // 세 채 — 벽이 **세 칸 두껍다**. 그래서 창이 작고 문이 좁다
        thickHall(world, spec, cx, cy, cz, fw, -12, 0, 9, 6);
        thickHall(world, spec, cx, cy, cz, fw, 4, -12, 5, 4);
        thickHall(world, spec, cx, cy, cz, fw, 4, 12, 5, 4);
        // 마당 — 눈이 쌓이는 땅이다. 가운데에 큰 불 하나 (밖에서 몸을 녹인다)
        world.getBlockAt(cx, cy, cz).setType(Material.COBBLESTONE);
        world.getBlockAt(cx, cy + 1, cz).setType(Material.CAMPFIRE);
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * i / 3.0;
            RemoteBuilder.put(world, cx + (int) Math.round(Math.cos(a) * 3), cy + 1,
                    cz + (int) Math.round(Math.sin(a) * 3), Material.SPRUCE_SLAB);
        }
        // 장작 — 북방의 살림은 장작이다 (겨울이 반년이다)
        for (int f = 12; f <= 15; f++) {
            for (int l = -18; l <= -14; l++) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                int h = 1 + Math.floorMod(x * 5 + z * 3, 3);
                for (int y = cy + 1; y <= cy + h; y++) {
                    RemoteBuilder.logAxis(world, x, y, z, Material.SPRUCE_LOG,
                            fw.swapped() ? org.bukkit.Axis.Z : org.bukkit.Axis.X);
                }
            }
        }
        for (int[] p : new int[][]{{half - 6, -6}, {half - 6, 6}, {-6, -14}, {-6, 14}}) {
            RemoteBuilder.lanternPost(world, fw.x(cx, p[0], p[1]), cy, fw.z(cz, p[0], p[1]));
        }
        return List.of(new Zone(place.name(), "북방저택 — 두꺼운 벽, 온돌의 연기", world.getName(),
                cx - rad, cy - 8, cz - rad, cx + rad, cy + 26, cz + rad));
    }

    /**
     * 두꺼운 벽의 집 — 벽이 <b>세 칸</b>이고, 굴뚝이 <b>연기</b>를 낸다.
     *
     * <p>온돌을 블록으로 어떻게 짓는가: 마루 밑에 불길이 지나가고 굴뚝으로 나간다 —
     * 그러니 보이는 것은 <b>굴뚝과 연기</b>다. 벽돌 굴뚝을 지붕 위로 세우고 그 위에 모닥불을 얹는다
     * (모닥불이 연기를 낸다 — 그것이 이 집이 <b>따뜻하다</b>는 유일한 증거다).
     */
    private static void thickHall(World world, TerrainForge.SiteSpec spec, int cx, int cy, int cz,
                                  RemoteBuilder.Facing fw, int f0, int l0, int hf, int hl) {
        for (int f = -hf; f <= hf; f++) {
            for (int l = -hl; l <= hl; l++) {
                int x = fw.x(cx, f0 + f, l0 + l);
                int z = fw.z(cz, f0 + f, l0 + l);
                world.getBlockAt(x, cy, z).setType(Material.STONE_BRICKS);
                TerrainForge.sealBelow(world, x, cy, z);
                // ★ 벽이 세 칸 — 가장자리에서 두 칸 안까지 전부 벽이다 (중원의 집은 한 칸이다)
                boolean wall = Math.abs(f) >= hf - 2 || Math.abs(l) >= hl - 2;
                if (!wall) {
                    continue;
                }
                for (int y = cy + 1; y <= cy + 4; y++) {
                    world.getBlockAt(x, y, z).setType(Material.STONE_BRICKS);
                }
            }
        }
        for (int l = -1; l <= 1; l++) {   // 문 — 좁다. 그리고 벽이 두꺼우니 **터널**이다
            for (int f = hf; f >= hf - 2; f--) {
                for (int y = cy + 1; y <= cy + 2; y++) {
                    world.getBlockAt(fw.x(cx, f0 + f, l0 + l), y, fw.z(cz, f0 + f, l0 + l))
                            .setType(Material.AIR);
                }
            }
        }
        for (int l : new int[]{-hl, hl}) {   // 작은 창 (추위를 막는다 — 큰 창은 없다)
            world.getBlockAt(fw.x(cx, f0, l0 + l), cy + 3, fw.z(cz, f0, l0 + l))
                    .setType(Material.GLASS_PANE);
        }
        for (int i = 0; i <= Math.min(hf, hl); i++) {   // 지붕
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
        // ★ 굴뚝 — 지붕을 뚫고 오른다. 꼭대기의 모닥불이 **연기**를 낸다 (이 세계에서 여기뿐이다)
        int chx = fw.x(cx, f0 - hf + 2, l0 + hl - 2);
        int chz = fw.z(cz, f0 - hf + 2, l0 + hl - 2);
        int top = cy + 6 + Math.min(hf, hl) + 2;
        for (int y = cy + 1; y <= top; y++) {
            world.getBlockAt(chx, y, chz).setType(Material.BRICKS);
        }
        world.getBlockAt(chx, top + 1, chz).setType(Material.CAMPFIRE);   // 온돌의 불 — 연기가 오른다
        RemoteBuilder.lantern(world, fw.x(cx, f0, l0), cy + 4, fw.z(cz, f0, l0));
        // 온돌 — 안의 짚자리는 **벽에 붙는다** (구들이 벽을 따라 놓인다)
        RemoteBuilder.put(world, fw.x(cx, f0 - hf + 3, l0), cy + 1, fw.z(cz, f0 - hf + 3, l0),
                Material.HAY_BLOCK);
    }

    // ─── 공용 손 ───

    /**
     * 담 — <b>이 손 하나가 다섯 세가를 가른다.</b> 높이와 문의 폭만 다르다:
     * 당가 6켜/3칸 · 모용 4켜/3칸 · 남궁 3켜/5칸 · 팽가 2켜/9칸(딴 손이 짓는다 — 담장 블록이다).
     */
    private static void wall(World world, int cx, int cy, int cz, RemoteBuilder.Facing fw,
                             int half, int h, int gateHalf, Material mat) {
        for (int f = -half; f <= half; f++) {
            for (int l = -half; l <= half; l++) {
                boolean edge = Math.abs(f) == half || Math.abs(l) == half;
                if (!edge || (f == half && Math.abs(l) <= gateHalf)) {
                    continue;   // 문 — 딱 하나. 그리고 좁다
                }
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                for (int y = cy + 1; y <= cy + h; y++) {
                    world.getBlockAt(x, y, z).setType(mat);
                }
                RemoteBuilder.put(world, x, cy + h + 1, z, Material.DEEPSLATE_TILE_SLAB);   // 담의 기와 갓
                TerrainForge.sealBelow(world, x, cy, z);
            }
        }
    }

    /** 저택의 채 — 회벽·검은 기와. 배치가 가문을 가르지, 이 손이 가르는 게 아니다 */
    private static void estateHall(World world, TerrainForge.SiteSpec spec, int cx, int cy, int cz,
                                   RemoteBuilder.Facing fw, int f0, int l0, int hl, int hf, boolean shut) {
        for (int f = -hf; f <= hf; f++) {
            for (int l = -hl; l <= hl; l++) {
                int x = fw.x(cx, f0 + f, l0 + l);
                int z = fw.z(cz, f0 + f, l0 + l);
                world.getBlockAt(x, cy, z).setType(Material.DARK_OAK_PLANKS);
                boolean edge = Math.abs(f) == hf || Math.abs(l) == hl;
                if (!edge) {
                    continue;
                }
                boolean corner = Math.abs(f) == hf && Math.abs(l) == hl;
                for (int y = cy + 1; y <= cy + 4; y++) {
                    world.getBlockAt(x, y, z).setType(corner ? Material.DARK_OAK_LOG
                            : Material.WHITE_TERRACOTTA);
                }
                // 닫힌 집은 창이 적고, 열린 집은 창이 많다 (당가 ↔ 팽가)
                if (!corner && Math.floorMod(f + l, shut ? 5 : 2) == 0) {
                    world.getBlockAt(x, cy + 2, z).setType(Material.GLASS_PANE);
                }
            }
        }
        for (int l = -1; l <= 1; l++) {
            for (int y = cy + 1; y <= cy + 3; y++) {
                world.getBlockAt(fw.x(cx, f0 + hf, l0 + l), y, fw.z(cz, f0 + hf, l0 + l))
                        .setType(Material.AIR);
            }
        }
        for (int i = 0; i <= Math.min(hf, hl); i++) {
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
        TerrainForge.terrace(world, spec, fw.x(cx, f0, l0), fw.z(cz, f0, l0), cy, 0, 0,
                Material.DARK_OAK_PLANKS);
    }
}
