package com.honcheon.mvt;

import org.bukkit.Material;
import org.bukkit.World;

import java.util.List;

/**
 * 관(官)의 집 둘 — <b>관아</b>와 <b>관문</b>.
 *
 * <p>지도가 두 원형을 가른 한 줄이 이것이다:
 * <blockquote>★ <b>성문은 도시를 두르고, 관문은 길을 가로막는다</b> — 그 한 줄이 두 원형을 가른다</blockquote>
 * 그래서 {@link RemoteBuilder} 의 <b>성문</b>과 이 파일의 <b>관문</b>은 자재가 같아도 <b>기하가 다르다</b>:
 * 성문은 성벽이 <b>부지를 두르고</b> 문 앞에 저잣거리가 서지만,
 * 관문은 성벽이 <b>길을 가로질러 양옆 산으로 사라지고</b> 저잣거리가 없다 —
 * 그리고 <b>길이 문 양쪽으로 이어진다</b> (성문은 한쪽에서 끝난다: 성 안은 짓지 않기 때문이다).
 *
 * <p>관문도 둘로 갈린다 (등록부):
 * <ul>
 *   <li><b>군진 = 막는 문</b> (변군 30,000) — 병영이 붙는다. 반경 64</li>
 *   <li><b>관문 = 거르는 문</b> (수졸 300) — <b>문서 보는 자리</b>(독서대)가 붙는다. 반경 48.
 *       <i>"싸우는 곳이 아니다 — 문서를 보는 곳이다"</i></li>
 * </ul>
 * 코드는 그 둘을 <b>세력이 아니라 반경</b>으로 가른다 — 반경은 등록부의 {@code force} 가 정했으므로
 * (변군 30,000 → 64 · 수졸 300 → 48), 반경을 읽는 것이 곧 <b>등록부를 읽는 것</b>이다.
 */
final class OfficeBuilder {

    private OfficeBuilder() {
    }

    static List<Zone> build(World world, WorldMap.Place place, TerrainForge.SiteSpec spec,
                            RemoteBuilder.Archetype kind) {
        return switch (kind) {
            case 관아 -> yamen(world, place, spec);
            case 관문 -> barrier(world, place, spec);
            default -> List.of();
        };
    }

    // ══════════════════════════════════════════════════════════════════
    //  관아(官衙) — 도시 안의 집. <b>문 앞에 북이 있다</b>
    // ══════════════════════════════════════════════════════════════════

    /**
     * 지도의 계약: <b>"정청(政廳) · 마당 · 방(榜) 붙이는 벽 · 옥(獄) · 문 앞의 북"</b>.
     *
     * <p>다섯이 다 있어야 관아다. 그중 <b>둘은 이 세계 어디에도 없는 물건</b>이라
     * 관아를 <b>한눈에</b> 만든다:
     * <ul>
     *   <li><b>문 앞의 북</b> — 억울한 자가 친다. 이 세계에서 북은 여기뿐이다</li>
     *   <li><b>옥(獄)</b> — 쇠창살. 성문에도 저택에도 쇠창살은 없다</li>
     * </ul>
     * 그리고 <b>방(榜) 붙이는 벽</b>: 관이 백성에게 말하는 유일한 입이다 (수배·포고·세).
     *
     * <p>크기는 <b>force 가 정한다</b>: 현(포쾌 12) 32 · 부(부병 500) 48.
     * 반경이 크면 정청이 커지고 옥이 커지고 마당이 커진다 — <b>같은 집이 부풀지 않는다.</b>
     */
    private static List<Zone> yamen(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        RemoteBuilder.Facing fw = RemoteBuilder.entry(spec);
        int cx = spec.cx();
        int cz = spec.cz();
        int rad = spec.radius();
        int cy = spec.groundY();
        boolean prefecture = rad >= 40;              // 부(府) — 등록부의 force 가 이 반경을 정했다
        int half = prefecture ? 20 : 14;
        int hallHalf = prefecture ? 9 : 6;

        TerrainForge.terrace(world, spec, cx, cz, cy, half + 1, half + 1, Material.POLISHED_ANDESITE);
        RemoteBuilder.approachPath(world, spec, cx, cz, fw, half + 2, rad + 10, cy, 2, Material.DIRT_PATH);

        // 담 — 관의 담은 낮지도 높지도 않다 (네 켜). 문은 하나, 폭 5
        for (int f = -half; f <= half; f++) {
            for (int l = -half; l <= half; l++) {
                boolean edge = Math.abs(f) == half || Math.abs(l) == half;
                if (!edge || (f == half && Math.abs(l) <= 2)) {
                    continue;
                }
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                for (int y = cy + 1; y <= cy + 4; y++) {
                    world.getBlockAt(x, y, z).setType(Material.STONE_BRICKS);
                }
                RemoteBuilder.put(world, x, cy + 5, z, Material.DEEPSLATE_TILE_SLAB);
                TerrainForge.sealBelow(world, x, cy, z);
            }
        }
        // 문 — 붉은 기둥이 아니라 검은 목재. 그리고 위에 기와 한 겹
        for (int l : new int[]{-3, 3}) {
            for (int y = cy + 1; y <= cy + 5; y++) {
                world.getBlockAt(fw.x(cx, half, l), y, fw.z(cz, half, l)).setType(Material.DARK_OAK_LOG);
            }
        }
        for (int l = -4; l <= 4; l++) {
            RemoteBuilder.put(world, fw.x(cx, half, l), cy + 6, fw.z(cz, half, l), Material.DEEPSLATE_TILES);
            RemoteBuilder.put(world, fw.x(cx, half + 1, l), cy + 6, fw.z(cz, half + 1, l),
                    Material.DEEPSLATE_TILE_SLAB);
            RemoteBuilder.put(world, fw.x(cx, half - 1, l), cy + 6, fw.z(cz, half - 1, l),
                    Material.DEEPSLATE_TILE_SLAB);
        }
        RemoteBuilder.lantern(world, fw.x(cx, half + 1, -3), cy + 5, fw.z(cz, half + 1, -3));
        RemoteBuilder.lantern(world, fw.x(cx, half + 1, 3), cy + 5, fw.z(cz, half + 1, 3));

        // ★ 문 앞의 북 — 억울한 자가 친다. **이 세계에서 북은 여기뿐이다**
        int bx = fw.x(cx, half + 3, -5);
        int bz = fw.z(cz, half + 3, -5);
        world.getBlockAt(bx, cy + 1, bz).setType(Material.DARK_OAK_FENCE);
        world.getBlockAt(bx + 1, cy + 1, bz).setType(Material.DARK_OAK_FENCE);
        world.getBlockAt(bx, cy + 2, bz).setType(Material.NOTE_BLOCK);
        RemoteBuilder.put(world, bx, cy + 3, bz, Material.DARK_OAK_SLAB);   // 북의 갓

        // ★ 방(榜) 붙이는 벽 — 관이 백성에게 말하는 유일한 입 (수배·포고·세)
        for (int l = 3; l <= 9; l++) {
            int x = fw.x(cx, half + 2, l);
            int z = fw.z(cz, half + 2, l);
            for (int y = cy + 1; y <= cy + 3; y++) {
                world.getBlockAt(x, y, z).setType(Material.STONE_BRICKS);
            }
            world.getBlockAt(x, cy + 2, z).setType(Material.DARK_OAK_PLANKS);   // 방이 붙는 판
            RemoteBuilder.put(world, x, cy + 4, z, Material.DEEPSLATE_TILE_SLAB);
        }
        RemoteBuilder.lanternPost(world, fw.x(cx, half + 2, 11), cy, fw.z(cz, half + 2, 11));

        // 정청(政廳) — 담 안 깊은 곳. **마당 건너**에 있다 (들어와서 걸어가야 만난다)
        officeHall(world, spec, cx, cy, cz, fw, -half + hallHalf + 3, hallHalf, hallHalf - 2);
        // 마당 — 돌바닥. 여기서 판결이 난다 (관아의 마당은 법정이다)
        for (int f = -2; f <= half - 4; f++) {
            for (int l = -8; l <= 8; l++) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                world.getBlockAt(x, cy, z).setType(Math.floorMod(x + z, 9) == 0
                        ? Material.ANDESITE : Material.POLISHED_ANDESITE);
            }
        }
        // ★ 옥(獄) — 쇠창살. 마당 옆에 붙는다 (죄인이 판결을 듣는 자리다)
        gaol(world, spec, cx, cy, cz, fw, 2, -half + 6, prefecture ? 3 : 2);
        // 창고와 병기 — 관은 무장한다 (포쾌·부병)
        long seed = Math.floorMod(31L * cx + cz, 1_000_003L);
        for (int y = cy + 1; y <= cy + 3; y++) {   // 병기 시렁이 걸릴 벽 (시렁은 벽에 박힌다)
            world.getBlockAt(fw.x(cx, 2, half - 4), y, fw.z(cz, 2, half - 4)).setType(Material.STONE_BRICKS);
        }
        RemoteBuilder.shelf(world, fw.x(cx, 2, half - 4), cy + 2, fw.z(cz, 2, half - 4), fw.side(-1),
                Weapons.makeSeeded(Weapons.Series.도, Weapons.Grade.정련, seed),
                Weapons.makeSeeded(Weapons.Series.창, Weapons.Grade.정련, seed + 1),
                Weapons.makeSeeded(Weapons.Series.창, Weapons.Grade.범철, seed + 2));

        for (int[] p : new int[][]{{half - 5, -9}, {half - 5, 9}, {0, -9}, {0, 9}, {-half + 8, 0}}) {
            RemoteBuilder.lanternPost(world, fw.x(cx, p[0], p[1]), cy, fw.z(cz, p[0], p[1]));
        }
        return List.of(new Zone(place.name(), "관아 — 문 앞의 북, 마당의 판결, 담 안의 옥", world.getName(),
                cx - rad, cy - 8, cz - rad, cx + rad, cy + 22, cz + rad));
    }

    /** 옥(獄) — <b>쇠창살</b>. 이 세계에서 쇠창살로 사람을 가두는 곳은 여기뿐이다 */
    private static void gaol(World world, TerrainForge.SiteSpec spec, int cx, int cy, int cz,
                             RemoteBuilder.Facing fw, int f0, int l0, int cells) {
        for (int c = 0; c < cells; c++) {
            int base = l0 + c * 5;
            for (int f = -2; f <= 2; f++) {
                for (int l = 0; l <= 4; l++) {
                    int x = fw.x(cx, f0 + f, base + l);
                    int z = fw.z(cz, f0 + f, base + l);
                    world.getBlockAt(x, cy, z).setType(Material.STONE_BRICKS);
                    TerrainForge.sealBelow(world, x, cy, z);
                    boolean edge = Math.abs(f) == 2 || l == 0 || l == 4;
                    if (!edge) {
                        continue;
                    }
                    for (int y = cy + 1; y <= cy + 3; y++) {
                        // 마당 쪽 면은 **쇠창살** — 갇힌 자가 판결을 본다
                        world.getBlockAt(x, y, z).setType(f == 2 ? Material.IRON_BARS
                                : Material.STONE_BRICKS);
                    }
                }
            }
            for (int f = -3; f <= 3; f++) {
                for (int l = -1; l <= 5; l++) {
                    RemoteBuilder.put(world, fw.x(cx, f0 + f, base + l), cy + 4,
                            fw.z(cz, f0 + f, base + l), Material.DEEPSLATE_TILES);
                }
            }
            RemoteBuilder.put(world, fw.x(cx, f0 - 1, base + 2), cy + 1, fw.z(cz, f0 - 1, base + 2),
                    Material.HAY_BLOCK);   // 짚자리 — 옥의 살림은 이것뿐이다
            RemoteBuilder.candlesAt(world, fw.x(cx, f0, base + 2), cy + 1, fw.z(cz, f0, base + 2));
        }
    }

    /** 정청(政廳) — 관이 앉는 집. 회벽·검은 기와 (관의 어휘는 세계 어디서나 같다) */
    private static void officeHall(World world, TerrainForge.SiteSpec spec, int cx, int cy, int cz,
                                   RemoteBuilder.Facing fw, int f0, int hl, int hf) {
        for (int f = -hf; f <= hf; f++) {
            for (int l = -hl; l <= hl; l++) {
                int x = fw.x(cx, f0 + f, l);
                int z = fw.z(cz, f0 + f, l);
                for (int y = cy - 1; y <= cy + 1; y++) {
                    world.getBlockAt(x, y, z).setType(Material.STONE_BRICKS);   // 기단 — 관은 들어올려 앉는다
                }
                TerrainForge.sealBelow(world, x, cy - 1, z);
                boolean edge = Math.abs(f) == hf || Math.abs(l) == hl;
                if (!edge) {
                    continue;
                }
                boolean corner = Math.abs(f) == hf && Math.abs(l) == hl;
                for (int y = cy + 2; y <= cy + 5; y++) {
                    world.getBlockAt(x, y, z).setType(corner ? Material.DARK_OAK_LOG
                            : Material.WHITE_TERRACOTTA);
                }
                if (!corner && Math.floorMod(f + l, 2) == 0) {
                    world.getBlockAt(x, cy + 3, z).setType(Material.GLASS_PANE);
                }
            }
        }
        for (int i = 0; i <= 2; i++) {   // 기단으로 오르는 계단 (마당에서 정청으로)
            for (int l = -3; l <= 3; l++) {
                world.getBlockAt(fw.x(cx, f0 + hf + 1 + i, l), cy + 1 - i, fw.z(cz, f0 + hf + 1 + i, l))
                        .setType(Material.STONE_BRICKS);
                TerrainForge.sealBelow(world, fw.x(cx, f0 + hf + 1 + i, l), cy + 1 - i,
                        fw.z(cz, f0 + hf + 1 + i, l));
            }
        }
        for (int l = -2; l <= 2; l++) {   // 문 — 넓다. 관은 백성을 들인다 (당가의 좁은 문과 반대다)
            for (int y = cy + 2; y <= cy + 4; y++) {
                world.getBlockAt(fw.x(cx, f0 + hf, l), y, fw.z(cz, f0 + hf, l)).setType(Material.AIR);
            }
        }
        for (int i = 0; i <= Math.min(hf, hl); i++) {
            int y = cy + 6 + i;
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
        RemoteBuilder.put(world, fw.x(cx, f0 - hf + 1, 0), cy + 2, fw.z(cz, f0 - hf + 1, 0),
                Material.LECTERN);   // 판결이 적히는 자리
        RemoteBuilder.put(world, fw.x(cx, f0 - hf + 1, -2), cy + 2, fw.z(cz, f0 - hf + 1, -2),
                Material.CHEST);
        RemoteBuilder.lantern(world, fw.x(cx, f0, 0), cy + 5, fw.z(cz, f0, 0));
    }

    // ══════════════════════════════════════════════════════════════════
    //  관문(關門) — <b>성벽이 길을 가로지른다</b>
    // ══════════════════════════════════════════════════════════════════

    /**
     * 지도의 계약: <b>"★ 성벽이 길을 가로지른다(도시를 두르지 않는다) · 문 하나 · 검문 탁자 ·
     * 군진은 병영(막는 문) · 관문은 문서 보는 자리(거르는 문)"</b>.
     *
     * <p>성문과 <b>정확히 무엇이 다른가</b> — 자재는 같다(돌벽돌·여장·기와). 다른 것은 셋:
     * <ol>
     *   <li>성벽이 <b>부지를 두르지 않는다</b>. 한 줄로 뻗어 부지 밖으로 <b>사라진다</b>
     *       (산과 산 사이를 막는 것이지 무엇을 감싸는 것이 아니다)</li>
     *   <li><b>길이 문 양쪽으로 이어진다</b>. 성문은 한쪽에서 끝난다 — 성 안은 짓지 않으니까.
     *       관문은 <b>지나가는 문</b>이라 양쪽이 다 밖이다</li>
     *   <li><b>저잣거리가 없다</b>. 여기서 장사하는 자는 없다 — 여기는 <b>지나가는 곳</b>이다</li>
     * </ol>
     */
    private static List<Zone> barrier(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        RemoteBuilder.Facing fw = RemoteBuilder.entry(spec);
        int cx = spec.cx();
        int cz = spec.cz();
        int rad = spec.radius();
        int cy = spec.groundY();
        boolean garrison = rad >= 56;   // 군진 — 변군 30,000 (등록부의 force 가 반경 64 를 정했다)
        int wallH = garrison ? 10 : 7;  // 막는 문은 높고 거르는 문은 낮다
        int wallT = garrison ? 2 : 1;   // 두께 (반칸씩 양옆)

        // ★ 성벽 — **부지 끝까지 한 줄로 뻗는다.** 도시를 두르지 않는다
        for (int l = -rad; l <= rad; l++) {
            for (int f = -wallT; f <= wallT; f++) {
                if (Math.abs(l) <= 3) {
                    continue;   // 문 자리
                }
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                if (!spec.inside(x, z)) {
                    continue;
                }
                int base = RemoteBuilder.groundOf(world, spec, x, z, cy);
                for (int y = Math.min(base, cy); y <= cy + wallH; y++) {
                    world.getBlockAt(x, y, z).setType(masonry(x, y, z, cy));
                }
                if (Math.floorMod(l, 3) != 0 && Math.abs(f) == wallT) {
                    world.getBlockAt(x, cy + wallH + 1, z).setType(Material.STONE_BRICK_WALL);   // 여장
                }
            }
        }
        // 문 — 개구 7 x 높이 5. 아치. 문 밑도 돌이다
        for (int f = -wallT; f <= wallT; f++) {
            for (int l = -3; l <= 3; l++) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                world.getBlockAt(x, cy, z).setType(Material.STONE_BRICKS);
                TerrainForge.sealBelow(world, x, cy, z);
                for (int y = cy + 1; y <= cy + 5; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR);
                }
            }
            for (int s : new int[]{-1, 1}) {
                RemoteBuilder.stair(world, fw.x(cx, f, 3 * s), cy + 5, fw.z(cz, f, 3 * s),
                        Material.STONE_BRICK_STAIRS, fw.side(-s), true);
            }
            for (int l = -3; l <= 3; l++) {   // 문 위를 성벽이 덮는다 — 지나가는 문이다
                for (int y = cy + 6; y <= cy + wallH; y++) {
                    world.getBlockAt(fw.x(cx, f, l), y, fw.z(cz, f, l)).setType(masonry(
                            fw.x(cx, f, l), y, fw.z(cz, f, l), cy));
                }
            }
        }
        // 문루 — 성벽 위. 작다 (성문의 성루가 아니다 — 여긴 도시가 없다)
        int floor = cy + wallH;
        for (int f = -wallT - 1; f <= wallT + 1; f++) {
            for (int l = -5; l <= 5; l++) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                RemoteBuilder.put(world, x, floor, z, Material.STONE_BRICKS);
                boolean edge = Math.abs(f) == wallT + 1 || Math.abs(l) == 5;
                if (!edge) {
                    continue;
                }
                boolean corner = Math.abs(f) == wallT + 1 && Math.abs(l) == 5;
                for (int y = floor + 1; y <= floor + 3; y++) {
                    world.getBlockAt(x, y, z).setType(corner ? Material.DARK_OAK_LOG
                            : Material.WHITE_TERRACOTTA);
                }
            }
        }
        for (int i = 0; i <= 2; i++) {
            int y = floor + 4 + i;
            for (int l = -6 + i; l <= 6 - i; l++) {
                RemoteBuilder.put(world, fw.x(cx, -wallT - 2 + i, l), y, fw.z(cz, -wallT - 2 + i, l),
                        Material.DEEPSLATE_TILES);
                RemoteBuilder.put(world, fw.x(cx, wallT + 2 - i, l), y, fw.z(cz, wallT + 2 - i, l),
                        Material.DEEPSLATE_TILES);
            }
            for (int f = -wallT - 2 + i; f <= wallT + 2 - i; f++) {
                RemoteBuilder.put(world, fw.x(cx, f, -6 + i), y, fw.z(cz, f, -6 + i),
                        Material.DEEPSLATE_TILES);
                RemoteBuilder.put(world, fw.x(cx, f, 6 - i), y, fw.z(cz, f, 6 - i),
                        Material.DEEPSLATE_TILES);
            }
        }
        RemoteBuilder.lantern(world, fw.x(cx, 0, 0), floor + 3, fw.z(cz, 0, 0));
        // 마도 — 문루로 오른다 (못 오르는 벽은 담이다)
        for (int i = 0; i <= wallH; i++) {
            int l = 7 + i;
            for (int f = -wallT - 3; f <= -wallT - 1; f++) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                if (!spec.inside(x, z)) {
                    continue;
                }
                int base = RemoteBuilder.groundOf(world, spec, x, z, cy);
                for (int y = Math.min(base, cy); y <= cy + i; y++) {
                    world.getBlockAt(x, y, z).setType(y <= cy + 1 ? Material.COBBLESTONE
                            : Material.STONE_BRICKS);
                }
                for (int y = cy + i + 1; y <= cy + i + 4; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR);
                }
            }
        }
        // ★ 관도 — **문 양쪽으로** 이어진다 (성문은 한쪽에서 끝난다. 그 차이가 이 원형이다)
        RemoteBuilder.approachPath(world, spec, cx, cz, fw, wallT + 2, rad - 2, cy, 3, Material.DIRT_PATH);
        for (int f = -wallT - 2; f >= -rad + 2; f--) {   // 문 안쪽 — 여기도 밖이다 (지나가는 문이므로)
            for (int l = -3; l <= 3; l++) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                if (!spec.inside(x, z)) {
                    continue;
                }
                TerrainForge.terrace(world, spec, x, z, RemoteBuilder.groundOf(world, spec, x, z, cy),
                        0, 0, Material.DIRT_PATH);
            }
        }
        for (int f = 10; f <= rad - 6; f += 8) {   // 관도의 등롱 — 양쪽 다
            RemoteBuilder.lanternPost(world, fw.x(cx, f, -5), cy, fw.z(cz, f, -5));
            RemoteBuilder.lanternPost(world, fw.x(cx, -f, 5), cy, fw.z(cz, -f, 5));
        }

        if (garrison) {
            garrisonYard(world, spec, cx, cy, cz, fw, wallT);   // 막는 문 — 병영이 붙는다
        } else {
            checkDesk(world, spec, cx, cy, cz, fw, wallT);      // 거르는 문 — 문서 보는 자리가 붙는다
        }
        return List.of(new Zone(place.name(),
                garrison ? "군진 — 막는 문 (변군이 있다)" : "관문 — 거르는 문 (문서를 본다)",
                world.getName(), cx - rad, cy - 10, cz - rad, cx + rad, cy + 30, cz + rad));
    }

    /**
     * ★ <b>검문 탁자</b> — 거르는 문의 본체. 등록부: <i>"싸우는 곳이 아니다 — <b>문서를 보는 곳</b>이다"</i>.
     *
     * <p>그래서 <b>독서대(讀書臺)</b>가 선다. 이 세계에서 독서대는 관아의 정청과 여기뿐이고,
     * 성문에는 <b>한 대도 없다</b> — 그것이 검수가 성문과 관문을 가르는 눈금이다.
     */
    private static void checkDesk(World world, TerrainForge.SiteSpec spec, int cx, int cy, int cz,
                                  RemoteBuilder.Facing fw, int wallT) {
        int f0 = wallT + 6;
        TerrainForge.terrace(world, spec, fw.x(cx, f0, 0), fw.z(cz, f0, 0), cy,
                fw.swapped() ? 6 : 8, fw.swapped() ? 8 : 6, Material.POLISHED_ANDESITE);
        for (int l : new int[]{-6, 6}) {   // 문서를 보는 두 자리 — 길 양옆에 앉는다
            int x = fw.x(cx, f0, l);
            int z = fw.z(cz, f0, l);
            for (int f = -2; f <= 2; f++) {
                for (int ll = -2; ll <= 2; ll++) {
                    RemoteBuilder.put(world, fw.x(cx, f0 + f, l + ll), cy + 4,
                            fw.z(cz, f0 + f, l + ll), Material.DEEPSLATE_TILES);   // 차양
                }
            }
            for (int f : new int[]{-2, 2}) {
                for (int y = cy + 1; y <= cy + 3; y++) {
                    world.getBlockAt(fw.x(cx, f0 + f, l - 2), y, fw.z(cz, f0 + f, l - 2))
                            .setType(Material.DARK_OAK_LOG);
                    world.getBlockAt(fw.x(cx, f0 + f, l + 2), y, fw.z(cz, f0 + f, l + 2))
                            .setType(Material.DARK_OAK_LOG);
                }
            }
            world.getBlockAt(x, cy + 1, z).setType(Material.LECTERN);        // ★ 문서 보는 자리
            RemoteBuilder.put(world, fw.x(cx, f0 - 1, l), cy + 1, fw.z(cz, f0 - 1, l), Material.CHEST);
            RemoteBuilder.put(world, fw.x(cx, f0 + 1, l), cy + 1, fw.z(cz, f0 + 1, l), Material.BARREL);
            RemoteBuilder.lantern(world, x, cy + 3, z);
        }
        // 통관첩을 못 받은 자가 기다리는 자리 — 길 옆의 빈 마루 (돌아가야 하는 자들이다)
        for (int f = f0 + 4; f <= f0 + 8; f++) {
            for (int l = -10; l <= -8; l++) {
                RemoteBuilder.put(world, fw.x(cx, f, l), cy + 1, fw.z(cz, f, l), Material.DARK_OAK_SLAB);
            }
        }
    }

    /**
     * <b>병영</b> — 막는 문의 본체. 등록부: <i>"군진은 <b>막는 문</b>이다 (변군이 있다)"</i>.
     *
     * <p>독서대가 없다. 대신 <b>막사가 줄지어 서고 창(槍)이 시렁에 걸린다</b> —
     * 여기서는 문서를 안 본다. 문 밖은 <b>적</b>이기 때문이다.
     */
    private static void garrisonYard(World world, TerrainForge.SiteSpec spec, int cx, int cy, int cz,
                                     RemoteBuilder.Facing fw, int wallT) {
        int f0 = -wallT - 10;   // ★ 병영은 **문 안쪽**에 있다 (문 밖은 적이다)
        TerrainForge.terrace(world, spec, fw.x(cx, f0, 0), fw.z(cz, f0, 0), cy,
                fw.swapped() ? 10 : 20, fw.swapped() ? 20 : 10, Material.COARSE_DIRT);
        boolean swap = fw.swapped();
        long seed = Math.floorMod(31L * cx + cz, 1_000_003L);
        for (int i = -1; i <= 1; i++) {   // 막사 셋 — 줄지어 선다 (군대의 배치다)
            int[] box = RemoteBuilder.localBox(cx, cz, fw, f0 - 3, f0 + 3, i * 12 - 3, i * 12 + 3);
            RemoteBuilder.barrack(world, box[0], cy, box[1], swap ? 7 : 7, swap ? 7 : 7, fw.out());
            // 창 시렁 — 문서가 아니라 창이다
            for (int y = cy + 1; y <= cy + 3; y++) {
                world.getBlockAt(fw.x(cx, f0 + 6, i * 12), y, fw.z(cz, f0 + 6, i * 12))
                        .setType(Material.STONE_BRICKS);
            }
            RemoteBuilder.shelf(world, fw.x(cx, f0 + 6, i * 12), cy + 2, fw.z(cz, f0 + 6, i * 12), fw.out(),
                    Weapons.makeSeeded(Weapons.Series.창, Weapons.Grade.정련, seed + i + 3),
                    Weapons.makeSeeded(Weapons.Series.창, Weapons.Grade.정련, seed + i + 6),
                    Weapons.makeSeeded(Weapons.Series.도, Weapons.Grade.범철, seed + i + 9));
        }
        // 연병 마당의 불 — 밤에도 지킨다
        for (int l : new int[]{-16, 0, 16}) {
            RemoteBuilder.lanternPost(world, fw.x(cx, f0 + 8, l), cy, fw.z(cz, f0 + 8, l));
        }
        // ★ 병적부(兵籍簿) — 막는 문도 **적는다**. 다만 적는 것이 문서가 아니라 **사람 수**다
        //   (거르는 문은 지나가는 자를 적고, 막는 문은 지키는 자를 적는다. 둘 다 성문에는 없다)
        RemoteBuilder.put(world, fw.x(cx, f0 + 8, 6), cy + 1, fw.z(cz, f0 + 8, 6), Material.LECTERN);
        // 봉수(烽燧) — 적이 오면 이 불을 올린다. 군진에만 있다
        int bx = fw.x(cx, f0 - 8, 0);
        int bz = fw.z(cz, f0 - 8, 0);
        for (int y = cy + 1; y <= cy + 8; y++) {
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    world.getBlockAt(bx + dx, y, bz + dz).setType(Material.STONE_BRICKS);
                }
            }
        }
        world.getBlockAt(bx, cy + 9, bz).setType(Material.CAMPFIRE);
        world.getBlockAt(bx + 1, cy + 9, bz + 1).setType(Material.CAMPFIRE);
    }

    /** 관의 돌 — 성문과 같은 결이다 (관은 어디서나 같은 돌을 쌓는다). 결정론: 좌표 해시 */
    private static Material masonry(int x, int y, int z, int cy) {
        if (y <= cy + 1) {
            return Material.COBBLESTONE;
        }
        if (Math.floorMod(x * 7 + y * 13 + z * 3, 13) == 0) {
            return Material.CRACKED_STONE_BRICKS;
        }
        return Material.STONE_BRICKS;
    }
}
