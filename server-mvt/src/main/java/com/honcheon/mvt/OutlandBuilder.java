package com.honcheon.mvt;

import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>사용자가 직접 설계한 원형 넷</b> — 녹림 석채 · 흑성 · 천막 · 유배지.
 *
 * <p>넷 다 <b>중원 건축의 문법을 쓰지 않는다.</b> 그리고 셋은 <b>담이 없다</b> —
 * 담이 없는 것이 그 집의 <b>말</b>이기 때문이다:
 * <ul>
 *   <li><b>천막</b> — <i>"★ 성이 없다. 대칸의 게르와 말 떼. <b>담도 성벽도 없다</b>"</i>.
 *       유목민에게 담은 자기를 가두는 물건이다</li>
 *   <li><b>유배지</b> — <i>"감옥이 아니라 유배다. <b>담이 없다</b>. 도망칠 데가 없으므로"</i>.
 *       ★ <b>담이 없다는 사실 자체가 이 장소의 잔인함</b>이다 — 가둘 필요가 없다</li>
 *   <li><b>흑성</b> — 반대로 <b>담밖에 없다</b>. <i>"초원의 흑성. 돌과 철, 불. 중원 건축이 아니다.
 *       압도적인 단일 권력과 폐쇄성"</i></li>
 * </ul>
 *
 * <h2>★ 녹림 석채 — 석축 예외</h2>
 * {@code docs/design/noklim_seokchae.md} 를 그대로 따른다. 그 문서의 마지막 못이 이것이다:
 * <blockquote>★ <b>녹림 총채 이외의 산채에 석축 예외가 전파되지 않는가</b></blockquote>
 * <b>구조적으로 불가능하게 만들었다</b>: 석축을 쌓는 손({@link #stoneStronghold})은
 * {@code Archetype.녹림석채} 에서만 불린다. {@code Archetype.산채}(RemoteBuilder.stockade)는
 * <b>이 파일을 부르지 않는다</b> — 그러므로 전파는 코드 경로상 일어날 수 없다.
 * ({@link ArchetypeAudit} 가 그것을 <b>매번 확인한다</b> — 구조가 옳다고 눈을 감지 않는다.)
 */
final class OutlandBuilder {

    private OutlandBuilder() {
    }

    static List<Zone> build(World world, WorldMap.Place place, TerrainForge.SiteSpec spec,
                            RemoteBuilder.Archetype kind) {
        return switch (kind) {
            case 녹림석채 -> stoneStronghold(world, place, spec);
            case 흑성 -> blackCitadel(world, place, spec);
            case 천막 -> gerCamp(world, place, spec);
            case 유배지 -> exile(world, place, spec);
            default -> List.of();
        };
    }

    // ══════════════════════════════════════════════════════════════════
    //  녹림 석채(綠林石寨) — docs/design/noklim_seokchae.md (사용자 설계)
    // ══════════════════════════════════════════════════════════════════

    /**
     * <b>"돌로 지은 도시"가 아니라 「돌로 지킨 거대한 산적 공동체」다.</b>
     *
     * <p>사용자의 검수 기준 여덟을 그대로 못으로 박는다:
     * <ol>
     *   <li><b>일반 목책 산채와 즉시 구별</b> — 목책({@code SPRUCE_LOG})이 <b>한 줄도 없다</b>. 석벽이다</li>
     *   <li><b>석축이 자연 산세와 연결</b> — 벽은 <b>능선과 협곡에만</b> 선다:
     *       열마다 지면을 재서 <b>이미 높은 곳(자연 암벽)은 건너뛴다.</b>
     *       <i>"자연 암벽과 인공 석벽이 구분되지 않게"</i> — 그래서 벽이 <b>끊긴다</b>. 그것이 옳다</li>
     *   <li><b>반경 48을 실제 기능 공간이 사용</b> — 일곱 구역이 다 있다 (아래)</li>
     *   <li><b>시장과 대창고가 핵심</b> — 부지 한복판이 시장이다. 두목의 집이 아니다</li>
     *   <li><b>여러 산채의 연합 본부</b> — 취의당에 <b>깃발이 여럿</b> 걸린다 (한 폭이 아니다)</li>
     *   <li><b>관부의 성곽도시처럼 정형화되지 않았는가</b> — ★ <b>직선축·대칭·정규 도로망을 쓰지 않는다.</b>
     *       벽의 반경이 방위마다 흔들리고(좌표 해시), 구역들이 축 위에 안 놓인다</li>
     *   <li><b>마교 흑성과 다른 재료·배치</b> — 흑성은 흑요석·철·냉색 불. 여기는 <b>빼앗은 잡석</b>이다</li>
     *   <li><b>★ 석축 예외가 다른 산채로 전파되지 않는가</b> — 위 클래스 주석 참조</li>
     * </ol>
     */
    private static List<Zone> stoneStronghold(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        RemoteBuilder.Facing fw = RemoteBuilder.entry(spec);
        int cx = spec.cx();
        int cz = spec.cz();
        int rad = spec.radius();
        int cy = spec.groundY();

        // ─ ① 외채문과 석벽 — ★ **접근 가능한 능선과 협곡만** 막는다 (사방을 두르지 않는다)
        raggedWall(world, spec, cx, cy, cz, fw, rad - 8);
        killingCorridor(world, spec, cx, cy, cz, fw, rad - 8);
        RemoteBuilder.approachPath(world, spec, cx, cz, fw, rad - 5, rad + 12, cy, 2, Material.COARSE_DIRT);

        // ─ ② 하채 — 외부인·행상·포로. **핵심부와 직접 연결되지 않도록 중간 관문**
        int lowF = rad - 22;
        pad(world, spec, cx, cz, fw, lowF, -6, cy, 8, 8, Material.COARSE_DIRT);
        shack(world, spec, cx, cy, cz, fw, lowF + 4, -10, 3, 3);      // 검문소
        shack(world, spec, cx, cy, cz, fw, lowF - 4, -2, 4, 3);       // 임시 숙소
        for (int l = 2; l <= 10; l++) {   // 마구간 — 말을 매는 줄
            RemoteBuilder.put(world, fw.x(cx, lowF, l), cy + 1, fw.z(cz, lowF, l), Material.SPRUCE_FENCE);
            RemoteBuilder.put(world, fw.x(cx, lowF, l), cy + 2, fw.z(cz, lowF, l), Material.IRON_CHAIN);
            RemoteBuilder.put(world, fw.x(cx, lowF - 1, l), cy + 1, fw.z(cz, lowF - 1, l),
                    Material.HAY_BLOCK);
        }
        innerGate(world, spec, cx, cy, cz, fw, lowF - 10);            // ★ 중간 관문 — 하채와 핵심부를 가른다

        // ─ ③ 녹림 시장 — ★ **총채의 핵심.** 부지 한복판이다 (두목의 집이 아니다)
        market(world, spec, cx, cy, cz, fw);

        // ─ ④ 대창고군 — ★ 생활 구역과 **분리**한다 (화재와 내부 약탈)
        warehouses(world, spec, cx, cy, cz, fw);

        // ─ ⑤ 중채 — 두령과 정예. 목조다 (★ "석축이라고 모든 건물을 돌로 짓지 않는다")
        int midF = -14;
        shack(world, spec, cx, cy, cz, fw, midF, 14, 6, 5);
        shack(world, spec, cx, cy, cz, fw, midF - 9, 16, 5, 4);
        shack(world, spec, cx, cy, cz, fw, midF + 8, 20, 4, 4);
        for (int f = midF - 4; f <= midF + 4; f++) {   // 연무장 — 정제되지 않았다 (다진 흙이다)
            for (int l = 2; l <= 10; l++) {
                TerrainForge.terrace(world, spec, fw.x(cx, f, l), fw.z(cz, f, l), cy, 0, 0,
                        Material.COARSE_DIRT);
            }
        }

        // ─ ⑥ 총표파자 / 취의당 — 권력의 중심. ★ **왕좌도 궁전도 없다.** 넓은 회의 공간과 **여러 산채의 깃발**
        int hallF = -26;
        assemblyHall(world, spec, cx, cy, cz, fw, hallF);

        // ─ ⑦ 상채와 비상 퇴로 — 가장 높은 능선. 총채주 거처 · 은밀한 하산로
        int upF = -rad + 12;
        int upY = spec.inside(fw.x(cx, upF, 0), fw.z(cz, upF, 0))
                ? spec.groundAt(fw.x(cx, upF, 0), fw.z(cz, upF, 0)) : cy;
        upY = Math.max(cy, Math.min(cy + 10, upY));   // 오를 수 있는 만큼만 (땅이 정한다)
        pad(world, spec, cx, cz, fw, upF, 0, upY, 7, 7, Material.COBBLESTONE);
        shack(world, spec, cx, upY, cz, fw, upF, 0, 5, 4);
        RemoteBuilder.put(world, fw.x(cx, upF - 2, 0), upY + 1, fw.z(cz, upF - 2, 0), Material.CHEST);
        RemoteBuilder.put(world, fw.x(cx, upF - 2, 2), upY + 1, fw.z(cz, upF - 2, 2), Material.BARREL);
        for (int i = 0; i <= (upY - cy) + 8; i++) {   // 상채로 오르는 길 (한 걸음 ±1)
            int f = hallF - 6 - i;
            int y = cy + Math.min(upY - cy, i);
            for (int l = -2; l <= 0; l++) {
                TerrainForge.terrace(world, spec, fw.x(cx, f, l), fw.z(cz, f, l), y, 0, 0,
                        Material.COBBLESTONE);
            }
        }
        // 은밀한 하산로 — ★ **총채 내부에서만** 보인다 (광역 이동망으로 확대하지 않는다)
        for (int i = 0; i < 10; i++) {
            int f = upF - 2 - i;
            int y = upY - i / 2;
            RemoteBuilder.put(world, fw.x(cx, f, -8), y, fw.z(cz, f, -8), Material.COBBLESTONE);
        }
        return List.of(new Zone(place.name(), "녹림 석채 — 돌로 지킨 거대한 산적 공동체", world.getName(),
                cx - rad, cy - 10, cz - rad, cx + rad, Math.max(cy, upY) + 24, cz + rad));
    }

    /**
     * ★ <b>너덜너덜한 석벽</b> — 이 원형의 핵심.
     *
     * <p><i>"관군이 설계한 성이 아니라 <b>여러 세대의 산적이 조금씩 확장한 결과물</b>처럼 보여야 한다"</i>
     * (사용자). 그래서:
     * <ul>
     *   <li><b>반경이 방위마다 흔들린다</b> (좌표 해시 — 자로 잰 원은 관군의 것이다)</li>
     *   <li><b>높이가 들쭉날쭉하다</b> (3~7켜 — 증축한 자리와 안 한 자리)</li>
     *   <li>★ <b>이미 높은 땅(자연 암벽)에는 안 쌓는다</b> — 벽이 <b>끊긴다</b>.
     *       <i>"자연 암벽과 인공 석벽이 구분되지 않게"</i>. 산이 벽인 곳에 벽을 또 쌓으면
     *       그것은 관부의 성곽이고, 이 문서가 금한 바로 그것이다</li>
     *   <li><b>빼앗은 자재가 섞인다</b> — 잡석 사이에 돌벽돌·기와가 박힌다
     *       (<i>"빼앗은 석재·목재·기와가 섞여 재료가 통일되지 않음"</i>)</li>
     * </ul>
     */
    private static void raggedWall(World world, TerrainForge.SiteSpec spec, int cx, int cy, int cz,
                                   RemoteBuilder.Facing fw, int r) {
        for (int f = -r - 4; f <= r + 4; f++) {
            for (int l = -r - 4; l <= r + 4; l++) {
                double d = RemoteBuilder.dist(f, l);
                // 방위마다 다른 반경 — 12등분 + 좌표 해시. **대칭이 아니다**
                int sector = (int) Math.floor((Math.atan2(l, f) + Math.PI) / (Math.PI / 6));
                double ring = r - 2 + Math.floorMod(cx * 13 + cz * 7 + sector * 41, 6);
                if (d < ring - 1.2 || d > ring + 1.2) {
                    continue;
                }
                if (f > r - 6 && Math.abs(l) <= 4) {
                    continue;   // 외채문 자리
                }
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                if (!spec.inside(x, z)) {
                    continue;
                }
                int g = spec.groundAt(x, z);
                // ★ 자연이 이미 막은 곳에는 안 쌓는다 — 산이 벽이다 (벽이 끊긴다. 그것이 이 집이다)
                if (g >= cy + 6) {
                    continue;
                }
                int h = 3 + Math.floorMod(x * 7 + z * 11 + sector * 3, 5);   // 3~7켜 — 증축의 흔적
                for (int y = g + 1; y <= g + h; y++) {
                    world.getBlockAt(x, y, z).setType(looted(x, y, z));
                }
                TerrainForge.sealBelow(world, x, g, z);
            }
        }
    }

    /**
     * 외채문 — <i>"거대한 정문이 아니라 <b>좁고 깊은 살상 통로</b>. 공격자가 한꺼번에 못 들어오게
     * <b>길을 꺾는다</b>"</i> (사용자).
     */
    private static void killingCorridor(World world, TerrainForge.SiteSpec spec, int cx, int cy, int cz,
                                        RemoteBuilder.Facing fw, int r) {
        // 통로 — 폭 3, 깊이 10. 그리고 **꺾인다** (중간에 옆으로 4칸)
        int[][] path = new int[14][];
        for (int i = 0; i < 14; i++) {
            int f = r + 3 - i;
            int l = i < 5 ? 0 : i < 9 ? (i - 4) : 4;   // ★ 꺾인다
            path[i] = new int[]{f, l};
        }
        for (int[] p : path) {
            for (int l = p[1] - 1; l <= p[1] + 1; l++) {
                TerrainForge.terrace(world, spec, fw.x(cx, p[0], l), fw.z(cz, p[0], l), cy, 0, 0,
                        Material.COBBLESTONE);
                for (int y = cy + 1; y <= cy + 4; y++) {
                    world.getBlockAt(fw.x(cx, p[0], l), y, fw.z(cz, p[0], l)).setType(Material.AIR);
                }
            }
            // 통로의 벽 — 높다. 위에서 내려다본다 (낙석 장치가 여기 있다)
            for (int s : new int[]{-2, 2}) {
                int x = fw.x(cx, p[0], p[1] + s);
                int z = fw.z(cz, p[0], p[1] + s);
                for (int y = cy + 1; y <= cy + 6; y++) {
                    world.getBlockAt(x, y, z).setType(looted(x, y, z));
                }
                if (Math.floorMod(p[0], 3) == 0) {
                    RemoteBuilder.put(world, x, cy + 7, z, Material.COBBLESTONE_SLAB);   // 낙석 — 얹어 둔 돌
                }
            }
        }
        // 철문 — ★ 목책이 아니다 (사용자의 못: "목책 대신 석벽과 철문")
        for (int l = -1; l <= 1; l++) {
            for (int y = cy + 1; y <= cy + 4; y++) {
                world.getBlockAt(fw.x(cx, r + 3, l), y, fw.z(cz, r + 3, l)).setType(Material.IRON_BARS);
            }
        }
        // 감시대 — 문 위
        for (int l = -3; l <= 3; l++) {
            RemoteBuilder.put(world, fw.x(cx, r + 3, l), cy + 7, fw.z(cz, r + 3, l), Material.SPRUCE_PLANKS);
            RemoteBuilder.put(world, fw.x(cx, r + 3, l), cy + 8, fw.z(cz, r + 3, l), Material.SPRUCE_FENCE);
        }
        world.getBlockAt(fw.x(cx, r + 3, 0), cy + 9, fw.z(cz, r + 3, 0)).setType(Material.TORCH);
    }

    /**
     * ★ <b>녹림 시장</b> — 등록부의 "약탈물 창고와 시장"의 핵심.
     * <i>"정식 도시 시장처럼 깨끗하고 계획적이지 않다 — 천막·가판대·창고·개조 건물이 밀집해
     * <b>끊임없이 형태가 변한다</b>"</i> (사용자).
     *
     * <p>그래서 <b>격자가 아니다</b>: 가판이 좌표 해시로 흩어지고, 차양의 색이 제각각이며
     * (약탈품이라 짝이 안 맞는다), 골목이 곧지 않다.
     */
    private static void market(World world, TerrainForge.SiteSpec spec, int cx, int cy, int cz,
                               RemoteBuilder.Facing fw) {
        Material[] cloth = {Material.RED_WOOL, Material.BROWN_WOOL, Material.LIGHT_GRAY_WOOL,
                Material.GREEN_WOOL, Material.YELLOW_WOOL};
        pad(world, spec, cx, cz, fw, 0, 0, cy, 16, 14, Material.COARSE_DIRT);
        for (int i = 0; i < 22; i++) {
            // 좌표 해시로 흩는다 — **줄이 안 맞는다** (관의 저잣거리는 줄이 맞는다)
            int f = -12 + Math.floorMod(i * 37 + cx, 26);
            int l = -12 + Math.floorMod(i * 53 + cz, 25);
            int x = fw.x(cx, f, l);
            int z = fw.z(cz, f, l);
            if (!spec.inside(x, z)) {
                continue;
            }
            world.getBlockAt(x, cy + 1, z).setType(Material.BAMBOO_PLANKS);   // 가판
            RemoteBuilder.put(world, x + 1, cy + 1, z, Material.BAMBOO_PLANKS);
            world.getBlockAt(x, cy + 2, z).setType(Math.floorMod(i, 3) == 0
                    ? Material.BARREL : Material.DECORATED_POT);
            for (int y = cy + 1; y <= cy + 3; y++) {   // 차양 기둥
                RemoteBuilder.put(world, x - 1, y, z, Material.SCAFFOLDING);
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    RemoteBuilder.put(world, x + dx, cy + 4, z + dz, cloth[Math.floorMod(i, cloth.length)]);
                }
            }
            if (Math.floorMod(i, 4) == 0) {
                RemoteBuilder.lanternPost(world, x + 2, cy, z + 2);
            }
        }
        // 장물의 저울과 궤 — 여기서 약탈품이 값이 된다
        world.getBlockAt(cx, cy + 1, cz).setType(Material.CAMPFIRE);
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * i / 3.0;
            RemoteBuilder.put(world, cx + (int) Math.round(Math.cos(a) * 3), cy + 1,
                    cz + (int) Math.round(Math.sin(a) * 3),
                    i % 2 == 0 ? Material.CHEST : Material.BARREL);
        }
    }

    /** 대창고군 — ★ <b>생활 구역과 분리</b> (화재와 내부 약탈). 석축이다 (핵심 창고는 돌로 짓는다) */
    private static void warehouses(World world, TerrainForge.SiteSpec spec, int cx, int cy, int cz,
                                   RemoteBuilder.Facing fw) {
        for (int i = 0; i < 3; i++) {
            int f0 = 6 - i * 11;
            int l0 = -20;
            pad(world, spec, cx, cz, fw, f0, l0, cy, 5, 5, Material.COBBLESTONE);
            for (int f = -4; f <= 4; f++) {
                for (int l = -4; l <= 4; l++) {
                    int x = fw.x(cx, f0 + f, l0 + l);
                    int z = fw.z(cz, f0 + f, l0 + l);
                    world.getBlockAt(x, cy, z).setType(Material.COBBLESTONE);
                    boolean edge = Math.abs(f) == 4 || Math.abs(l) == 4;
                    if (!edge) {
                        continue;
                    }
                    for (int y = cy + 1; y <= cy + 4; y++) {
                        world.getBlockAt(x, y, z).setType(looted(x, y, z));   // 석축 — 빼앗은 돌이 섞인다
                    }
                }
            }
            for (int l = -1; l <= 1; l++) {   // 철문 — 잠근다 (별도 잠금 보물고)
                for (int y = cy + 1; y <= cy + 3; y++) {
                    world.getBlockAt(fw.x(cx, f0 + 4, l0 + l), y, fw.z(cz, f0 + 4, l0 + l))
                            .setType(i == 0 ? Material.IRON_BARS : Material.AIR);
                }
            }
            for (int f = -5; f <= 5; f++) {   // 지붕 — 빼앗은 기와 (짝이 안 맞는다)
                for (int l = -5; l <= 5; l++) {
                    RemoteBuilder.put(world, fw.x(cx, f0 + f, l0 + l), cy + 5, fw.z(cz, f0 + f, l0 + l),
                            Math.floorMod(f * 3 + l * 5, 4) == 0 ? Material.DEEPSLATE_TILES
                                    : Material.SPRUCE_PLANKS);
                }
            }
            for (int f = -2; f <= 2; f += 2) {   // 안 — 쟁인 것
                for (int l = -2; l <= 2; l += 2) {
                    RemoteBuilder.put(world, fw.x(cx, f0 + f, l0 + l), cy + 1, fw.z(cz, f0 + f, l0 + l),
                            Math.floorMod(f + l, 4) == 0 ? Material.CHEST : Material.BARREL);
                }
            }
            RemoteBuilder.lanternPost(world, fw.x(cx, f0 + 6, l0), cy, fw.z(cz, f0 + 6, l0));
        }
    }

    /**
     * 취의당(聚義堂) — 권력의 중심. ★ <b>호화로운 왕좌나 궁전을 두지 않는다.</b>
     * 권위는 장식이 아니라 <b>넓은 회의 공간 · 무기 · 깃발 · 여러 산채의 표식</b>으로 (사용자).
     */
    private static void assemblyHall(World world, TerrainForge.SiteSpec spec, int cx, int cy, int cz,
                                     RemoteBuilder.Facing fw, int f0) {
        pad(world, spec, cx, cz, fw, f0, 0, cy, 11, 9, Material.COBBLESTONE);
        for (int f = -9; f <= 9; f++) {
            for (int l = -8; l <= 8; l++) {
                int x = fw.x(cx, f0 + f, l);
                int z = fw.z(cz, f0 + f, l);
                world.getBlockAt(x, cy, z).setType(Material.SPRUCE_PLANKS);   // 마루 — 넓다. 비어 있다
                boolean edge = Math.abs(f) == 9 || Math.abs(l) == 8;
                if (!edge) {
                    continue;
                }
                boolean corner = Math.abs(f) == 9 && Math.abs(l) == 8;
                for (int y = cy + 1; y <= cy + 5; y++) {
                    world.getBlockAt(x, y, z).setType(corner ? Material.SPRUCE_LOG
                            : (y <= cy + 2 ? looted(x, y, z) : Material.SPRUCE_PLANKS));   // 아래는 돌, 위는 나무
                }
            }
        }
        for (int l = -2; l <= 2; l++) {   // 문 — 넓다 (두령들이 한꺼번에 들어온다)
            for (int y = cy + 1; y <= cy + 4; y++) {
                world.getBlockAt(fw.x(cx, f0 + 9, l), y, fw.z(cz, f0 + 9, l)).setType(Material.AIR);
            }
        }
        for (int i = 0; i <= 8; i++) {
            int y = cy + 6 + i;
            for (int f = -10 + i; f <= 10 - i; f++) {
                RemoteBuilder.put(world, fw.x(cx, f0 + f, -9 + i), y, fw.z(cz, f0 + f, -9 + i),
                        Material.SPRUCE_PLANKS);
                RemoteBuilder.put(world, fw.x(cx, f0 + f, 9 - i), y, fw.z(cz, f0 + f, 9 - i),
                        Material.SPRUCE_PLANKS);
            }
        }
        // ★ 여러 산채의 깃발 — **연합**이다 (한 폭이 아니다. 그것이 이 집이 흑성과 다른 점이다)
        Material[] flags = {Material.GREEN_WOOL, Material.RED_WOOL, Material.BROWN_WOOL,
                Material.BLACK_WOOL, Material.YELLOW_WOOL, Material.LIGHT_GRAY_WOOL};
        for (int i = 0; i < flags.length; i++) {
            int l = -7 + i * 3;
            int x = fw.x(cx, f0 - 8, l);
            int z = fw.z(cz, f0 - 8, l);
            world.getBlockAt(x, cy + 3, z).setType(flags[i]);
            world.getBlockAt(x, cy + 4, z).setType(Material.SPRUCE_FENCE);
        }
        // 회의 자리 — 왕좌가 아니라 **둘러앉는 자리**다
        for (int i = 0; i < 10; i++) {
            double a = Math.PI * 2 * i / 10.0;
            int x = fw.x(cx, f0, 0) + (int) Math.round(Math.cos(a) * 5);
            int z = fw.z(cz, f0, 0) + (int) Math.round(Math.sin(a) * 5);
            RemoteBuilder.put(world, x, cy + 1, z, Material.SPRUCE_SLAB);
        }
        world.getBlockAt(fw.x(cx, f0, 0), cy + 1, fw.z(cz, f0, 0)).setType(Material.CAMPFIRE);
        long seed = Math.floorMod(31L * cx + cz, 1_000_003L);
        for (int y = cy + 1; y <= cy + 3; y++) {
            world.getBlockAt(fw.x(cx, f0 - 9, 0), y, fw.z(cz, f0 - 9, 0)).setType(Material.SPRUCE_PLANKS);
        }
        RemoteBuilder.shelf(world, fw.x(cx, f0 - 9, 0), cy + 2, fw.z(cz, f0 - 9, 0), fw.out(),
                Weapons.makeSeeded(Weapons.Series.도, Weapons.Grade.정련, seed),
                Weapons.makeSeeded(Weapons.Series.검, Weapons.Grade.보병, seed + 1),   // 표국에게서 뺏은 것
                Weapons.makeSeeded(Weapons.Series.부, Weapons.Grade.범철, seed + 2));
        RemoteBuilder.lantern(world, fw.x(cx, f0, 0), cy + 5, fw.z(cz, f0, 0));
    }

    /** 중간 관문 — 하채와 핵심부를 가른다 (외부인은 여기서 멈춘다) */
    private static void innerGate(World world, TerrainForge.SiteSpec spec, int cx, int cy, int cz,
                                  RemoteBuilder.Facing fw, int f0) {
        for (int l = -12; l <= 12; l++) {
            if (Math.abs(l) <= 2) {
                continue;
            }
            int x = fw.x(cx, f0, l);
            int z = fw.z(cz, f0, l);
            if (!spec.inside(x, z)) {
                continue;
            }
            int g = spec.groundAt(x, z);
            for (int y = g + 1; y <= g + 4; y++) {
                world.getBlockAt(x, y, z).setType(looted(x, y, z));
            }
        }
        for (int l : new int[]{-3, 3}) {
            for (int y = cy + 1; y <= cy + 5; y++) {
                world.getBlockAt(fw.x(cx, f0, l), y, fw.z(cz, f0, l)).setType(Material.SPRUCE_LOG);
            }
            RemoteBuilder.lantern(world, fw.x(cx, f0, l), cy + 6, fw.z(cz, f0, l));
        }
    }

    /**
     * ★ <b>빼앗은 돌</b> — 재료가 통일되지 않는다 (사용자: <i>"빼앗은 석재·목재·기와가 섞여"</i>).
     * 잡석 사이에 <b>관가에서 뜯어 온 돌벽돌</b>과 <b>기와</b>가 박힌다. 결정론: 좌표 해시.
     */
    private static Material looted(int x, int y, int z) {
        return switch (Math.floorMod(x * 7 + y * 3 + z * 11, 9)) {
            case 0 -> Material.STONE_BRICKS;          // 뜯어 온 관가의 돌
            case 1 -> Material.MOSSY_COBBLESTONE;
            case 2 -> Material.ANDESITE;
            case 3 -> Material.CRACKED_STONE_BRICKS;
            case 4 -> Material.DEEPSLATE_TILES;       // 뜯어 온 기와가 벽에 박혔다
            case 5 -> Material.STONE;
            default -> Material.COBBLESTONE;
        };
    }

    // ══════════════════════════════════════════════════════════════════
    //  흑성(黑城) — 마교 본교. <b>중원 건축이 아니다</b>
    // ══════════════════════════════════════════════════════════════════

    /**
     * 사용자의 말: <b>"초원의 흑성. 돌과 철, 불. 중원 건축이 아니다. 압도적인 <b>단일 권력</b>과 폐쇄성"</b>.
     *
     * <p>그러므로 중원의 어휘를 <b>하나도 안 쓴다</b>:
     * <ul>
     *   <li><b>기와가 없다</b> — 처마도 없다. 지붕은 평평하다 (물매 있는 지붕은 중원의 것이다)</li>
     *   <li><b>회벽이 없다</b> — 검은 돌(흑요석·심층암·흑암)이다</li>
     *   <li><b>등롱이 없다</b> — 불은 <b>냉색</b>이다 (영혼 등불·영혼 모닥불)</li>
     *   <li><b>마당이 없다</b> — 나눠 앉는 자리를 두지 않는다. <b>단일 권력</b>이다</li>
     * </ul>
     * 대신 하나가 있다 — <b>가운데의 탑 하나.</b> 녹림 석채의 취의당이 <b>둘러앉는 자리</b>였다면
     * 여기는 <b>우러러보는 자리</b>다. 그 대비가 두 세력의 정치다.
     */
    private static List<Zone> blackCitadel(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        RemoteBuilder.Facing fw = RemoteBuilder.entry(spec);
        int cx = spec.cx();
        int cz = spec.cz();
        int rad = spec.radius();
        int cy = spec.groundY();
        int half = Math.min(rad - 10, 44);

        TerrainForge.terrace(world, spec, cx, cz, cy, half + 1, half + 1, Material.BLACKSTONE);
        RemoteBuilder.approachPath(world, spec, cx, cz, fw, half + 2, rad + 10, cy, 2, Material.BLACKSTONE);

        // 성벽 — 각지고 높고 두껍다. 곡선이 없다 (초원에서 이것이 솟는다)
        for (int f = -half; f <= half; f++) {
            for (int l = -half; l <= half; l++) {
                int ring = Math.max(Math.abs(f), Math.abs(l));
                if (ring < half - 2) {
                    continue;
                }
                if (f == half && Math.abs(l) <= 2) {
                    continue;   // 문 하나 — 좁다 (폐쇄)
                }
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                int g = RemoteBuilder.groundOf(world, spec, x, z, cy);
                for (int y = Math.min(g, cy); y <= cy + 14; y++) {
                    world.getBlockAt(x, y, z).setType(darkStone(x, y, z));
                }
                if (ring == half && Math.floorMod(l + f, 4) != 0) {
                    world.getBlockAt(x, cy + 15, z).setType(Material.POLISHED_BLACKSTONE_BRICK_WALL);
                }
                TerrainForge.sealBelow(world, x, Math.min(g, cy), z);
            }
        }
        // 철문 — 돌과 철. 열두 칸 높이의 쇠창살
        for (int l = -2; l <= 2; l++) {
            for (int y = cy + 1; y <= cy + 10; y++) {
                world.getBlockAt(fw.x(cx, half, l), y, fw.z(cz, half, l)).setType(Material.IRON_BARS);
            }
            for (int y = cy + 11; y <= cy + 14; y++) {
                world.getBlockAt(fw.x(cx, half, l), y, fw.z(cz, half, l))
                        .setType(darkStone(fw.x(cx, half, l), y, fw.z(cz, half, l)));
            }
        }
        // 문 앞의 불 둘 — ★ 냉색이다 (마을의 온색과 정반대. 이 불은 사람을 부르지 않는다)
        RemoteBuilder.soulLantern(world, fw.x(cx, half + 2, -4), cy + 3, fw.z(cz, half + 2, -4));
        RemoteBuilder.soulLantern(world, fw.x(cx, half + 2, 4), cy + 3, fw.z(cz, half + 2, 4));
        for (int l : new int[]{-4, 4}) {
            for (int y = cy + 1; y <= cy + 2; y++) {
                world.getBlockAt(fw.x(cx, half + 2, l), y, fw.z(cz, half + 2, l))
                        .setType(Material.POLISHED_BLACKSTONE_BRICK_WALL);
            }
        }
        // ★ 탑 하나 — 가운데. **우러러보는 자리**다. 층이 없다 (오르는 곳이 아니다 — 보는 곳이다)
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                if (ring > 8) {
                    continue;
                }
                int h = ring <= 5 ? 30 : 6;   // 심(芯)이 솟고 발치가 넓다
                for (int y = cy + 1; y <= cy + h; y++) {
                    if (ring <= 3 && y > cy + 6 && y < cy + 28) {
                        continue;   // 안은 비었다 (계단 없음 — 사람은 못 오른다. 그것이 권력이다)
                    }
                    world.getBlockAt(cx + dx, y, cz + dz).setType(darkStone(cx + dx, y, cz + dz));
                }
            }
        }
        for (int dx = -3; dx <= 3; dx++) {   // 탑 꼭대기의 불 — 초원 어디서나 이것이 보인다
            for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 3) {
                    continue;
                }
                world.getBlockAt(cx + dx, cy + 31, cz + dz).setType(Material.SOUL_CAMPFIRE);
            }
        }
        // 문 → 탑 — 직선. 아무것도 없다 (마당도 시장도 나무도 없다. 걸어가는 것뿐이다)
        for (int f = half - 3; f >= 9; f--) {
            for (int l = -3; l <= 3; l++) {
                TerrainForge.terrace(world, spec, fw.x(cx, f, l), fw.z(cz, f, l), cy, 0, 0,
                        Material.POLISHED_BLACKSTONE);
            }
            if (Math.floorMod(f, 8) == 0) {   // 길가의 불 — 냉색. 여덟 칸마다
                RemoteBuilder.put(world, fw.x(cx, f, -5), cy + 1, fw.z(cz, f, -5),
                        Material.POLISHED_BLACKSTONE_BRICK_WALL);
                RemoteBuilder.soulLantern(world, fw.x(cx, f, -5), cy + 2, fw.z(cz, f, -5));
                RemoteBuilder.put(world, fw.x(cx, f, 5), cy + 1, fw.z(cz, f, 5),
                        Material.POLISHED_BLACKSTONE_BRICK_WALL);
                RemoteBuilder.soulLantern(world, fw.x(cx, f, 5), cy + 2, fw.z(cz, f, 5));
            }
        }
        // 철의 집 넷 — 성벽 안쪽에 붙는다 (독립된 채가 아니라 벽의 일부다. 그것이 폐쇄다)
        for (int i = 0; i < 4; i++) {
            int f = -half + 6 + (i / 2) * 14;
            int l = -20 + (i % 2) * 40;
            ironCell(world, spec, cx, cy, cz, fw, f, l);
        }
        return List.of(new Zone(place.name(), "흑성 — 돌과 철, 불. 중원 건축이 아니다", world.getName(),
                cx - rad, cy - 10, cz - rad, cx + rad, cy + 40, cz + rad));
    }

    /** 흑성의 집 — 문 하나, 창 없음, 평지붕. 안은 어둡다 (촛불 하나) */
    private static void ironCell(World world, TerrainForge.SiteSpec spec, int cx, int cy, int cz,
                                 RemoteBuilder.Facing fw, int f0, int l0) {
        for (int f = -5; f <= 5; f++) {
            for (int l = -5; l <= 5; l++) {
                int x = fw.x(cx, f0 + f, l0 + l);
                int z = fw.z(cz, f0 + f, l0 + l);
                if (!spec.inside(x, z)) {
                    continue;
                }
                world.getBlockAt(x, cy, z).setType(Material.POLISHED_BLACKSTONE);
                boolean edge = Math.abs(f) == 5 || Math.abs(l) == 5;
                for (int y = cy + 1; y <= cy + 5; y++) {
                    if (edge) {
                        world.getBlockAt(x, y, z).setType(darkStone(x, y, z));
                    }
                }
                RemoteBuilder.put(world, x, cy + 6, z, Material.POLISHED_BLACKSTONE_SLAB);   // ★ 평지붕
            }
        }
        for (int l = -1; l <= 1; l++) {
            for (int y = cy + 1; y <= cy + 2; y++) {
                world.getBlockAt(fw.x(cx, f0 + 5, l0 + l), y, fw.z(cz, f0 + 5, l0 + l)).setType(Material.AIR);
            }
        }
        RemoteBuilder.candlesAt(world, fw.x(cx, f0, l0), cy + 1, fw.z(cz, f0, l0));
        RemoteBuilder.put(world, fw.x(cx, f0 - 3, l0), cy + 1, fw.z(cz, f0 - 3, l0), Material.CHEST);
        RemoteBuilder.put(world, fw.x(cx, f0 - 3, l0 + 2), cy + 1, fw.z(cz, f0 - 3, l0 + 2),
                Material.HAY_BLOCK);
    }

    /** 검은 돌 — 흑요석·흑암·심층암. 회벽도 기와도 한 칸 없다. 결정론: 좌표 해시 */
    private static Material darkStone(int x, int y, int z) {
        return switch (Math.floorMod(x * 7 + y * 5 + z * 11, 10)) {
            case 0 -> Material.OBSIDIAN;                      // 드물다 — 광택이 있어야 검은 돌이 읽힌다
            case 1, 2 -> Material.POLISHED_BLACKSTONE;
            case 3 -> Material.BLACKSTONE;
            case 4 -> Material.CHISELED_POLISHED_BLACKSTONE;
            case 5 -> Material.DEEPSLATE_BRICKS;
            default -> Material.POLISHED_BLACKSTONE_BRICKS;
        };
    }

    // ══════════════════════════════════════════════════════════════════
    //  천막(天幕) — 북막 한정. <b>★ 성이 없다</b>
    // ══════════════════════════════════════════════════════════════════

    /**
     * 사용자의 말: <b>"★ 성이 없다. 대칸의 게르와 말 떼. <b>담도 성벽도 없다</b>"</b>.
     *
     * <p>이것은 <b>흑성의 정반대</b>다 — 같은 새외(塞外)인데 하나는 담밖에 없고 하나는 담이 없다.
     * 그 차이가 두 세력의 전부다: 마교는 <b>가둔다</b>, 북막은 <b>움직인다</b>.
     *
     * <p>그래서 이 조성기는 <b>땅에 못을 박지 않는다</b>:
     * 게르는 <b>지면 위에 얹힐 뿐</b>이고(기단도 축대도 없다), 큰 단을 깎지 않으며,
     * 각 게르는 <b>제 자리의 지면</b>에 앉는다. 내일 이 자리를 뜨면 <b>흔적은 재와 말똥뿐</b>이다.
     *
     * <p>대칸의 게르가 가장 크고 가운데에 있으며 <b>흰</b>다. 나머지는 회색이다 —
     * 위계는 담이 아니라 <b>크기와 색</b>으로 말한다.
     */
    private static List<Zone> gerCamp(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        int cx = spec.cx();
        int cz = spec.cz();
        int rad = spec.radius();
        int cy = spec.groundY();

        // 대칸의 게르 — 가운데, 가장 크고 희다
        ger(world, spec, cx, cz, spec.groundAt(cx, cz), 9, Material.WHITE_WOOL);
        // 부족의 게르 — 둘레. ★ **줄이 안 맞는다** (유목민은 도면을 안 쓴다)
        int lo = cy;
        int hi = cy;
        for (int i = 0; i < 14; i++) {
            double a = Math.PI * 2 * i / 14.0;
            int d = 18 + Math.floorMod(cx * 7 + cz * 3 + i * 29, 22);
            int gx = cx + (int) Math.round(Math.cos(a) * d);
            int gz = cz + (int) Math.round(Math.sin(a) * d);
            if (!spec.inside(gx, gz) || spec.wet(gx, gz)) {
                continue;
            }
            int gy = spec.groundAt(gx, gz);   // ★ 제 지면 — 땅을 안 고른다
            ger(world, spec, gx, gz, gy, 4 + Math.floorMod(i, 3),
                    Math.floorMod(i, 4) == 0 ? Material.LIGHT_GRAY_WOOL : Material.WHITE_WOOL);
            lo = Math.min(lo, gy);
            hi = Math.max(hi, gy);
        }
        // 말 떼 — 매어 두는 줄. ★ 이것이 이 세력의 부(富)다 (창고도 시장도 없다)
        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i < 24; i++) {
                int hx = cx + side * 26 + Math.floorMod(i * 3, 2);
                int hz = cz - 12 + i;
                if (!spec.inside(hx, hz)) {
                    continue;
                }
                int hy = spec.groundAt(hx, hz);
                RemoteBuilder.put(world, hx, hy + 1, hz, Material.SPRUCE_FENCE);
                if (Math.floorMod(i, 4) == 0) {
                    RemoteBuilder.put(world, hx, hy + 2, hz, Material.SPRUCE_FENCE);
                    RemoteBuilder.put(world, hx + side, hy + 1, hz, Material.HAY_BLOCK);   // 말 먹이
                }
            }
        }
        // 모닥불 — 게르 사이. 이것이 이 야영지의 유일한 불이다 (등롱이 없다 — 들고 다닐 수 없으므로)
        for (int i = 0; i < 5; i++) {
            double a = Math.PI * 2 * i / 5.0 + 0.6;
            int fx = cx + (int) Math.round(Math.cos(a) * 13);
            int fz = cz + (int) Math.round(Math.sin(a) * 13);
            if (!spec.inside(fx, fz)) {
                continue;
            }
            int fy = spec.groundAt(fx, fz);
            world.getBlockAt(fx, fy, fz).setType(Material.COBBLESTONE);
            world.getBlockAt(fx, fy + 1, fz).setType(Material.CAMPFIRE);
            for (int j = 0; j < 4; j++) {
                double b = Math.PI * j / 2.0;
                RemoteBuilder.put(world, fx + (int) Math.round(Math.cos(b) * 2), fy + 1,
                        fz + (int) Math.round(Math.sin(b) * 2), Material.SPRUCE_SLAB);
            }
            world.getBlockAt(fx + 1, fy + 2, fz + 1).setType(Material.TORCH);
        }
        // 대칸의 깃발 — 이 야영지에서 가장 높은 것. 성이 없으므로 **깃대가 성이다**
        int py = spec.groundAt(cx, cz);
        for (int y = py + 8; y <= py + 16; y++) {
            world.getBlockAt(cx, y, cz).setType(Material.SPRUCE_FENCE);
        }
        world.getBlockAt(cx, py + 17, cz).setType(Material.BLACK_WOOL);
        world.getBlockAt(cx, py + 18, cz).setType(Material.TORCH);
        return List.of(new Zone(place.name(), "천막 — 성이 없다. 게르와 말 떼", world.getName(),
                cx - rad, lo - 8, cz - rad, cx + rad, hi + 24, cz + rad));
    }

    /**
     * 게르 — <b>둥글다</b>. 이 세계에서 둥근 집은 여기뿐이다
     * (중원의 집은 전부 사각이다 — 그 대비가 새외를 만든다).
     *
     * <p>땅을 <b>안 판다</b>: 지면 위에 그대로 얹는다. 바닥은 양탄자(양털)고, 벽은 원통,
     * 지붕은 돔이며, 꼭대기에 <b>연기 구멍</b>이 뚫린다.
     */
    private static void ger(World world, TerrainForge.SiteSpec spec, int cx, int cz, int cy,
                            int r, Material felt) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = RemoteBuilder.dist(dx, dz);
                if (d > r) {
                    continue;
                }
                int x = cx + dx;
                int z = cz + dz;
                if (!spec.inside(x, z)) {
                    continue;
                }
                // 바닥 — 얹는다. 파지도 깎지도 않는다 (땅은 남의 것이다)
                RemoteBuilder.put(world, x, cy + 1, z, Material.BROWN_WOOL);
                if (d > r - 1) {   // 벽 — 원통. 세 켜
                    for (int y = cy + 1; y <= cy + 3; y++) {
                        world.getBlockAt(x, y, z).setType(felt);
                    }
                    world.getBlockAt(x, cy + 1, z).setType(Material.SPRUCE_FENCE);   // 뼈대가 밑에 보인다
                }
            }
        }
        // 지붕 — 돔. 좁아지며 오른다
        for (int i = 0; i <= r; i++) {
            int rr = r - i;
            int y = cy + 4 + i / 2;
            for (int dx = -rr; dx <= rr; dx++) {
                for (int dz = -rr; dz <= rr; dz++) {
                    double d = RemoteBuilder.dist(dx, dz);
                    if (d > rr || d < rr - 1.4) {
                        continue;
                    }
                    RemoteBuilder.put(world, cx + dx, y, cz + dz, felt);
                }
            }
        }
        // 문 — 남쪽 하나 (게르의 문은 늘 한 쪽이다)
        for (int y = cy + 1; y <= cy + 2; y++) {
            world.getBlockAt(cx, y, cz + r).setType(Material.AIR);
            world.getBlockAt(cx + 1, y, cz + r).setType(Material.AIR);
        }
        // 연기 구멍과 화덕 — 게르의 중심 (담이 없으니 중심은 불이다)
        world.getBlockAt(cx, cy + 1, cz).setType(Material.CAMPFIRE);
        for (int y = cy + 2; y <= cy + 4 + r / 2; y++) {
            world.getBlockAt(cx, y, cz).setType(Material.AIR);
        }
        RemoteBuilder.put(world, cx - 1, cy + 2, cz - 1, Material.BARREL);
    }

    // ══════════════════════════════════════════════════════════════════
    //  유배지(流配地) — <b>★ 감옥이 아니라 유배다</b>
    // ══════════════════════════════════════════════════════════════════

    /**
     * 사용자의 말: <b>"감옥이 아니라 유배다 — 초가 몇 채 + 감시 초소. <b>담이 없다</b>. 도망칠 데가 없으므로"</b>.
     *
     * <p>★ <b>담이 없다는 사실 자체가 이 장소의 잔인함이다.</b> 관아의 옥은 쇠창살로 가두지만,
     * 여기는 <b>아무도 안 가둔다</b> — 가둘 필요가 없기 때문이다. 나가면 밀림이고, 밀림은 죽음이다.
     * 그래서 <b>쇠창살이 한 칸도 없고, 담이 한 켜도 없다.</b> 있는 것은:
     * <ul>
     *   <li>초가 몇 채 — 살림이 <b>가장 얇다</b>. 궤 하나, 짚자리, 솥 하나</li>
     *   <li>감시 초소 하나 — <b>가두려는 것이 아니라 세려는 것</b>이다 (몇이 살아 있는가)</li>
     *   <li>밭 — 먹을 것을 스스로 지어야 한다 (아무도 안 준다)</li>
     * </ul>
     */
    private static List<Zone> exile(World world, WorldMap.Place place, TerrainForge.SiteSpec spec) {
        RemoteBuilder.Facing fw = RemoteBuilder.entry(spec);
        int cx = spec.cx();
        int cz = spec.cz();
        int rad = spec.radius();
        int cy = spec.groundY();

        // 초가 넷 — 줄이 대충 맞는다 (관이 세운 것이라 줄은 있으나, 성의는 없다)
        List<int[]> huts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int hx = fw.x(cx, -6 + (i / 2) * 12, -10 + (i % 2) * 20);
            int hz = fw.z(cz, -6 + (i / 2) * 12, -10 + (i % 2) * 20);
            if (!spec.inside(hx, hz) || spec.wet(hx, hz)) {
                continue;
            }
            int hy = spec.groundAt(hx, hz);
            hut(world, spec, hx, hy, hz);
            huts.add(new int[]{hx, hy, hz});
        }
        // 감시 초소 — 하나. 높다. **가두는 것이 아니라 세는 것**이다
        int wx = fw.x(cx, 14, 0);
        int wz = fw.z(cz, 14, 0);
        int wy = spec.inside(wx, wz) ? spec.groundAt(wx, wz) : cy;
        RemoteBuilder.watchtowerAt(world, wx, wy, wz);
        // 초소 곁의 관리 — 통 하나와 문서 (몇이 살아 있는가를 적는다)
        RemoteBuilder.put(world, wx + 3, wy + 1, wz, Material.LECTERN);
        RemoteBuilder.put(world, wx + 3, wy + 1, wz + 1, Material.BARREL);

        // 밭 — 먹을 것을 스스로 짓는다 (아무도 안 준다)
        for (int f = -18; f <= -8; f++) {
            for (int l = -8; l <= 8; l++) {
                int x = fw.x(cx, f, l);
                int z = fw.z(cz, f, l);
                if (!spec.inside(x, z) || spec.wet(x, z)) {
                    continue;
                }
                if (Math.floorMod(l + 8, 4) == 3) {
                    continue;
                }
                int y = spec.groundAt(x, z);
                world.getBlockAt(x, y, z).setType(Material.FARMLAND);
                RemoteBuilder.put(world, x, y + 1, z, Material.SHORT_GRASS);
            }
        }
        // 길 — 초소에서 초가로. 그것뿐이다. **밖으로 나가는 길이 없다** (그것이 이 장소의 말이다)
        for (int[] h : huts) {
            trail(world, spec, wx, wy, wz, h[0], h[1], h[2]);
        }
        int lo = cy;
        int hi = cy;
        for (int[] h : huts) {
            lo = Math.min(lo, h[1]);
            hi = Math.max(hi, h[1]);
        }
        return List.of(new Zone(place.name(), "유배지 — 담이 없다. 도망칠 데가 없으므로", world.getName(),
                cx - rad, lo - 8, cz - rad, cx + rad, Math.max(hi, wy) + 20, cz + rad));
    }

    /** 초가 한 채 — 살림이 가장 얇다. 궤 하나 · 짚자리 · 솥 하나. <b>문짝이 없다</b> */
    private static void hut(World world, TerrainForge.SiteSpec spec, int hx, int hy, int hz) {
        TerrainForge.terrace(world, spec, hx, hz, hy, 4, 4, Material.COARSE_DIRT);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.getBlockAt(hx + dx, hy, hz + dz).setType(Material.COARSE_DIRT);
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                if (!edge) {
                    continue;
                }
                for (int y = hy + 1; y <= hy + 3; y++) {
                    world.getBlockAt(hx + dx, y, hz + dz).setType(Material.SPRUCE_PLANKS);
                }
            }
        }
        world.getBlockAt(hx, hy + 1, hz + 2).setType(Material.AIR);
        world.getBlockAt(hx, hy + 2, hz + 2).setType(Material.AIR);
        for (int i = 0; i <= 2; i++) {
            int y = hy + 3 + i;
            for (int dx = -3 + i; dx <= 3 - i; dx++) {
                RemoteBuilder.thatchStair(world, hx + dx, y, hz - 3 + i, org.bukkit.block.BlockFace.NORTH);
                RemoteBuilder.thatchStair(world, hx + dx, y, hz + 3 - i, org.bukkit.block.BlockFace.SOUTH);
            }
            for (int dz = -2 + i; dz <= 2 - i; dz++) {
                RemoteBuilder.thatchStair(world, hx - 3 + i, y, hz + dz, org.bukkit.block.BlockFace.WEST);
                RemoteBuilder.thatchStair(world, hx + 3 - i, y, hz + dz, org.bukkit.block.BlockFace.EAST);
            }
        }
        world.getBlockAt(hx - 1, hy + 1, hz - 1).setType(Material.HAY_BLOCK);
        world.getBlockAt(hx + 1, hy + 1, hz - 1).setType(Material.BARREL);
        world.getBlockAt(hx + 1, hy + 1, hz + 1).setType(Material.CAULDRON);   // 솥 하나
        world.getBlockAt(hx, hy + 1, hz + 4).setType(Material.TORCH);
    }

    // ─── 공용 손 ───

    private static void pad(World world, TerrainForge.SiteSpec spec, int cx, int cz,
                            RemoteBuilder.Facing fw, int f0, int l0, int y, int hf, int hl, Material floor) {
        for (int f = -hf; f <= hf; f++) {
            for (int l = -hl; l <= hl; l++) {
                TerrainForge.terrace(world, spec, fw.x(cx, f0 + f, l0 + l), fw.z(cz, f0 + f, l0 + l),
                        y, 0, 0, floor);
            }
        }
    }

    /** 목조 채 — ★ 석축이라고 모든 건물을 돌로 짓지 않는다 (사용자의 표: 생활 건물 = 목조) */
    private static void shack(World world, TerrainForge.SiteSpec spec, int cx, int cy, int cz,
                              RemoteBuilder.Facing fw, int f0, int l0, int hf, int hl) {
        boolean swap = fw.swapped();
        int[] box = RemoteBuilder.localBox(cx, cz, fw, f0 - hf, f0 + hf, l0 - hl, l0 + hl);
        RemoteBuilder.barrack(world, box[0], cy, box[1],
                swap ? hf * 2 + 1 : hl * 2 + 1, swap ? hl * 2 + 1 : hf * 2 + 1, fw.out());
    }

    /** 길 — 한 걸음 ±1 */
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
}
