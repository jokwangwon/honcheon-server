package com.honcheon.mvt.forge;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Stairs;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 석축 테라스 기계 — <b>화산 캠퍼스의 단(段) 패드를 산비탈에 앉힌다</b> (B-146 의 처방).
 *
 * <p>설계 정본: {@code docs/design/hwasan_build_enhancement_v1.md} §2(★v2 — 20구역 마스터플랜이
 * 배치의 정본)·§3(기계). 레퍼런스({@code 화산파/} 13장)의 문법 — 건물은 절벽을 깎은 자리가
 * 아니라 <b>쌓은 석축 위</b>에 선다. 옹벽이 패드 가장자리에서 실지형까지 내려가 닿고, 대계단
 * 몸체도 전 열이 접지하므로, <b>건물이 뜰 자리가 구조적으로 없다</b> — 「본전이 떴다」(B-146 ·
 * 허공 블록 41)의 재발을 배치가 아니라 구조가 막는다.
 *
 * <p>★v2 파라메트릭: 마스터플랜은 일렬 축선이 아니라 <b>남(산문)→북(정상) 척추 + 좌우로
 * 벌어지는 구역</b>이다. 그래서 이 기계는 단을 「축선상 일렬」로 받지 않고 <b>패드 목록
 * (중심·크기·목표고)</b>과 <b>계단 링크(윗패드의 어느 면에서 아랫패드로 내려가는가)</b>로 받는다.
 * {@link #pavePad} 는 월드+패드(중심·크기·y)만 알면 되므로 곁봉 정상 패드(슬라이스 3 —
 * 19·20구역)에도 그대로 재사용된다.
 *
 * <p><b>깎기 최소 · 쌓기 우선</b> (사용자 계율 — 캠퍼스 문서 「자연 우선」): 목표고 위로 솟은
 * 지형만 깎고 나머지는 석축이 덮는다. 계획({@link #plan})이 패드마다 실지형(p85)과 목표고의
 * 어긋남을 재어 알려 준다 — 잠정 목표고는 골격 유도값 【제안】이고, 어긋남이 크면 빨간펜이
 * 수를 고친다 (코드가 조용히 지어내지 않는다).
 *
 * <p>★금지 재료 (B-195 · HANDOFF 함정): {@code BARREL}(가구_3D 오버라이드가 이웃 벽을 뚫는다) ·
 * {@code LIGHT}(컬링 누명 전과). 이 기계의 팔레트는 {@link #palette()}가 전부이고,
 * 눈({@code tools/TerraceForgeSelfTest.java})이 그 표에 금지 재료가 없는지 잰다.
 *
 * <p>계획({@link #plan}·순수 검증 {@link #validate})은 지형을 읽고, 조성({@link #pavePad}·
 * {@link #paveStair})은 블록을 얹으며, 검수({@link #audit})는 선 것을 다시 잰다 —
 * ①패드 표면 평탄 ②전 열 접지(허공 0) ③계단 보행 연속(단차 ≤1). 산세 높이장은 안 건드린다.
 */
public final class TerraceForge {

    private TerraceForge() {
    }

    /** 대계단 반폭 — 폭 7 (레퍼런스 1·6호의 중앙 대계단) */
    public static final int STAIR_HALF = 3;

    /** 대계단 난간(측석) 오프셋 — 계단 중심 ±4 */
    public static final int RAIL_OFF = STAIR_HALF + 1;

    /** 패드 사이 최소 낙차 — 이보다 얕으면 단이 단으로 안 읽힌다 (계단 링크의 하한) */
    public static final int MIN_STAIR_DY = 3;

    /** 패드 사이 최대 낙차 — 대계단 하나의 상한. 더 크면 계단참 패드를 끼운다 (척추가 그렇다) */
    public static final int MAX_STAIR_DY = 26;

    /** 램프가 끝난 뒤 아랫패드까지 허용하는 평탄 보도 길이 */
    public static final int MAX_WALK = 12;

    /** 패드 한 변 상한 — H-3 「최대 단 폭 35 (연무장 단)」 ({@link RangeSpec} ridgeHalfWidth 주석) */
    public static final int MAX_TIER_WIDTH = 35;

    /** 패드 위 머리 공간 — 이 높이까지 걷어 하늘을 연다 (수목·바위 돌출 제거) */
    private static final int HEADROOM = 8;

    /** 지형 판독 상위 백분위 — 쌓기 우선의 눈금 (상위 15%만 깎는다) */
    private static final int PERCENTILE = 85;

    /** 잠정 목표고와 실지형(p85)의 어긋남 경고 문턱 — 넘으면 계획이 소리 낸다 */
    public static final int TERRAIN_MISMATCH_WARN = 10;

    private static final long SALT_FACE = 0x5EA_57ACL;                  // 옹벽 결
    private static final long SALT_PAVE = 0x0BAD_5EEDL ^ 0x7E44ACE5L;   // 포장 결

    // ═══════════════════════════════════════════════════════════════════
    // 명세 — 패드(폴리곤)와 계단 링크
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 패드 명세 — 마스터플랜 한 구역의 테라스 자리.
     *
     * @param zone  마스터플랜 구역 번호 (§2 표 — 15 는 결번 보존 · 101/102 는 내부 계단참)
     * @param name  구역 이름 (검수·로그가 부른다)
     * @param dx    주봉 상대 중심 x (골격 좌표계 — 남 = +z)
     * @param dz    주봉 상대 중심 z
     * @param width 동서 폭 (칸) — ≤ {@value #MAX_TIER_WIDTH} (H-3)
     * @param depth 남북 깊이 (칸) — ≤ {@value #MAX_TIER_WIDTH}
     * @param h     목표 포장면 높이 (baseY 위) — 골격 유도 잠정값 【제안】
     */
    public record PadSpec(int zone, String name, int dx, int dz, int width, int depth, int h) {
    }

    /**
     * 계단 링크 — 윗패드의 한 면에서 아랫패드로 내려가는 대계단.
     *
     * @param upperZone 윗패드 구역 번호 (h 가 더 높아야 한다)
     * @param lowerZone 아랫패드 구역 번호
     * @param side      윗패드의 어느 면에서 나가는가 — 'S'·'N'·'E'·'W'
     */
    public record StairLink(int upperZone, int lowerZone, char side) {
    }

    /** 캠퍼스 명세 — 패드 목록 + 계단 링크. {@link #validate} 가 순수하게 전부 잰다. */
    public record Campus(List<PadSpec> pads, List<StairLink> links) {
    }

    /**
     * 화산 캠퍼스 기본값 【제안】 — 마스터플랜 20구역 중 슬라이스 1 몫:
     * <b>척추 공공 단 6</b> (1 산문 → 2 외원 → 6 종문 → 9 본전 → 12 장로회 → 13 정상) +
     * <b>좌우 로브 단 7</b> (3·4·5·7·8·14·17) + 척추 낙차를 죄려고 끼운
     * <b>내부 계단참 3</b> (101·102·103 — 마스터플랜 결번 아님, 이 기계의 살림).
     *
     * <p>높이는 <b>실기동 1차 p85 판독(2026-08-02 · 캠퍼스시험 지형 어긋남 경고 9건)으로
     * 재조정한 잠정값</b>이다 【제안】 — 초판(골격 보간 유도)이 지형보다 11~29 높아 옹벽이
     * 레퍼런스의 「석축 위 단」 감(4~12칸)을 넘었다. 이제 각 패드는 p85 ±2 를 딛고,
     * 창-안이던 척추 앵커 넷(6 종문 92 · 9 본전 116 · 12 장로회 128 · 13 정상 148)은 살렸다.
     * 그 대가로 세 링크의 높낮이가 뒤집혔다 — 종문>훈련장·훈련장>생활중·산문>창고
     * (마스터플랜은 평면도라 상하는 지형이 정한다). 실지형과의 어긋남은 {@link #plan} 이
     * 계속 재어 소리 낸다.
     *
     * <p>척추 하부(외원→종문)는 낙차 34 라 계단참 병(103)을 끼워 12+22 로 갈랐다 —
     * 22 구간은 레퍼런스 1호의 긴 천계단 문법이다 (계단참 둘을 더 끼울 자리가 없다:
     * 램프가 계단참 깊이를 다 먹는다).
     *
     * <p>16(측문)·10(정원)·11(망루)은 슬라이스 2(구역 배치기), 18·19·20(운무교·곁봉)은
     * 슬라이스 3 몫 — 여기 없다.
     */
    public static Campus hwasanCampus() {
        List<PadSpec> pads = List.of(
                // ── 척추 (남 → 북 · 공공) ── h = p85 재조정 (앵커 넷은 초판 유지) ──
                new PadSpec(1, "산문", -2, 176, 24, 16, 46),          // p85 46
                new PadSpec(2, "외원 광장", -2, 150, 30, 22, 58),      // p85 59
                new PadSpec(103, "계단참 병", -2, 130, 14, 13, 80),    // 외원↔종문 낙차 34 의 허리
                new PadSpec(6, "종문 중정", -2, 110, 28, 20, 92),      // 앵커 (창 안)
                new PadSpec(101, "계단참 갑", -2, 80, 12, 12, 104),
                new PadSpec(9, "본전", -2, 52, 34, 26, 116),           // 앵커 (창 안)
                new PadSpec(12, "장로회", -8, 12, 20, 14, 128),        // 앵커 (창 안)
                new PadSpec(102, "계단참 을", -14, -12, 14, 12, 138),
                new PadSpec(13, "정상 암자", -16, -34, 18, 14, 148),   // 앵커 (창 안) · 사당+정자 몫으로 확장
                // ── 로브 (척추 좌우 · 수련/지원) ── h = p85 ±2 ──────────
                new PadSpec(3, "연무장 하", -36, 148, 30, 24, 40),     // p85 38
                // ★강당: 종문 서편(z112)에서 계단참 병 서편(z128)으로 이사 — 종문→강당 낙차 24 램프가
                //   패드 중앙을 관통해 홀 처마가 보행선에 걸렸다 (2.5 실기동 보행 단차의 한 갈래).
                //   병(80)→강당(68) 낙차 12 로 갈아 끼우고 홀은 램프 서쪽에 앉는다.
                new PadSpec(4, "강당·무기고", -32, 128, 22, 16, 48),   // 3차 p85 y-14(h47) 실측 반영 (Δ1)
                new PadSpec(5, "생활 하", 28, 144, 26, 20, 64),        // p85 64
                new PadSpec(7, "훈련장 중", 28, 106, 24, 18, 80),      // p85 80
                new PadSpec(8, "생활 중", 32, 84, 22, 16, 76),         // p85 75 · 훈련장 곁으로 남하
                new PadSpec(14, "연무장 상", -34, 86, 24, 20, 86),     // ★「중단 서편」 · 2차 p85 24(y) 재조정
                new PadSpec(17, "물자 창고", 26, 174, 20, 14, 42),     // p85 43
                // ── 슬라이스 2 신설 (마스터플랜 10·11·16) ── 2차 p85 재조정 ──
                // ★정원·망루 h108: p85(h99)+9 — 본전(116) 곁이라 p85±2 로 내리면 낙차 16 램프가
                //   소패드 안을 관통해 소품(연못·탑)을 밀어낸다. 계단참을 낄 틈(본전과 2칸)도 없다.
                //   옹벽 9칸은 석축 감(4~12) 안 — 본전 곁 위계로 읽힌다 【제안 · 빨간펜 대상】.
                new PadSpec(10, "장문인 정원", -28, 52, 14, 16, 108),
                // ★망루 패드를 남북 22 로 늘렸다 — 탑을 계단 회랑(z48~56) 남쪽으로 내리기 위해.
                //   2.5 실기동: 탑이 램프 착지선을 가로막아 보행 단차 3·4·5 가 났다.
                new PadSpec(11, "망루", 24, 56, 12, 22, 106),   // 3차 p85 h96 — Δ10 (경고 창 안)
                // ★측문: 3차 p85 y-41(h20) 반영 h30 (Δ10 — 석축 감 상단 · 정원·망루와 같은 근거).
                //   창고(42)→측문 낙차 12. 폭 16 — 램프(11칸)가 서편을 지나고 문루는 동편 평지에 선다.
                new PadSpec(16, "측문", 48, 174, 16, 12, 28));   // 3차 p85 h19 — Δ9 (경고 창 안) · 창고 낙차 14
        List<StairLink> links = List.of(
                // 척추 대계단 — 항상 남면으로 내려간다 (남→북 오름)
                new StairLink(2, 1, 'S'),      // 낙차 12
                new StairLink(103, 2, 'S'),    // 낙차 22 — 긴 천계단 구간
                new StairLink(6, 103, 'S'),    // 낙차 12
                new StairLink(101, 6, 'S'),    // 낙차 12
                new StairLink(9, 101, 'S'),    // 낙차 12
                new StairLink(12, 9, 'S'),     // 낙차 12
                new StairLink(102, 12, 'S'),   // 낙차 10
                new StairLink(13, 102, 'S'),   // 낙차 10
                // 로브 협계단 — 척추에서 좌우로 (p85 재조정으로 셋은 상하가 뒤집혔다)
                new StairLink(2, 3, 'W'),      // 외원 → 연무장 하 (서 · 낙차 18)
                new StairLink(5, 2, 'W'),      // 생활 하 → 외원 (서 · 낙차 6)
                new StairLink(4, 3, 'S'),      // ★강당·무기고 → 연무장 하 (남 · 낙차 8 — 수련 단끼리 잇닿는다)
                new StairLink(6, 7, 'E'),      // ★역전: 종문 → 훈련장 중 (동 · 낙차 12)
                new StairLink(7, 8, 'N'),      // ★역전: 훈련장 중 → 생활 중 (북 · 낙차 4)
                new StairLink(101, 14, 'W'),   // 계단참 갑 → 연무장 상 (서 · 낙차 18 — 2차 재조정)
                new StairLink(1, 17, 'E'),     // ★역전: 산문 → 물자 창고 (동 · 낙차 4)
                // 슬라이스 2 신설 연결
                new StairLink(9, 10, 'W'),     // 본전 → 장문인 정원 (서 · 낙차 8)
                new StairLink(9, 11, 'E'),     // 본전 → 망루 (동 · 낙차 8)
                new StairLink(17, 16, 'E'));   // 물자 창고 → 측문 (동 · 낙차 8 — 이사에 따라 재배선)
        return new Campus(pads, links);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 계획 — 명세를 실좌표에 앉히고 (순수) · 지형을 읽는다 (월드)
    // ═══════════════════════════════════════════════════════════════════

    /** 앉힌 패드 — 실좌표. 북 = −z. */
    public record Pad(PadSpec spec, int y, int x0, int x1, int zN, int zS) {

        int cx() {
            return x0 + spec.width() / 2;
        }

        int cz() {
            return zN + spec.depth() / 2;
        }

        boolean contains(int x, int z) {
            return x >= x0 && x <= x1 && z >= zN && z <= zS;
        }
    }

    /**
     * 앉힌 대계단 — 윗패드 면에서 한 칸 밖이 첫 디딤({@code start}), {@code dir} 로 내려간다.
     * 디딤 {@code treads} = 낙차−1, 그 뒤 보도 {@code walk} 칸이 아랫패드에 닿는다 (붙어 있으면 0).
     * 몸체 전 열은 실지형/아랫포장까지 채워 <b>접지</b>한다 — 뜬 계단은 없다.
     */
    public record StairLane(StairLink link, int startX, int startZ, int dirX, int dirZ,
                            int topY, int lowY, int treads, int walk) {

        int length() {
            return treads + walk;
        }

        /** 이 계단 몸체(폭 {@code 2·RAIL_OFF+1})가 그 열을 덮는가 — 여장·평탄 검수가 비켜 갈 자리 */
        boolean covers(int x, int z) {
            for (int t = 0; t <= length(); t++) {
                int cx = startX + dirX * (t - 1);
                int cz = startZ + dirZ * (t - 1);
                int off = dirZ != 0 ? x - cx : z - cz;
                boolean onCell = dirZ != 0 ? z == cz : x == cx;
                if (onCell && Math.abs(off) <= RAIL_OFF) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 계획 — 앉힌 패드·계단과 지형 어긋남 메모. */
    public record Plan(String placeId, List<Pad> pads, List<StairLane> lanes, List<String> terrainNotes) {
    }

    /** 기본 캠퍼스로 계획한다 — 지형(p85)을 읽어 어긋남을 메모에 남긴다. */
    public static Plan plan(World world, RangeSpec spec) {
        return plan(world, spec, hwasanCampus());
    }

    /** 명세를 밖에서 주는 판 — 시험·다른 산이 제 표를 들고 온다. */
    public static Plan plan(World world, RangeSpec spec, Campus campus) {
        List<Pad> pads = resolvePads(campus, spec.peakX(), spec.peakZ(), spec.baseY());
        List<StairLane> lanes = resolveLanes(campus, pads);
        List<String> notes = new ArrayList<>();
        for (Pad p : pads) {
            int p85 = percentileGround(world, p.x0(), p.x1(), p.zN(), p.zS(), PERCENTILE);
            int delta = p.y() - p85;
            if (Math.abs(delta) > TERRAIN_MISMATCH_WARN) {
                notes.add(p.spec().zone() + " " + p.spec().name() + ": 목표 y" + p.y()
                        + " vs 지형 p85 y" + p85 + " (Δ" + delta + ") — 잠정 높이를 빨간펜하라");
            }
        }
        return new Plan(spec.placeId(), pads, lanes, List.copyOf(notes));
    }

    /**
     * 명세 → 실좌표 — <b>순수 함수</b> (눈이 이것을 잰다). 한 변 상한(H-3)·구역 번호 중복·
     * 패드 겹침을 여기서 거절한다.
     */
    public static List<Pad> resolvePads(Campus campus, int peakX, int peakZ, int baseY) {
        List<Pad> out = new ArrayList<>(campus.pads().size());
        Set<Integer> zones = new HashSet<>();
        for (PadSpec ps : campus.pads()) {
            if (ps.width() > MAX_TIER_WIDTH || ps.depth() > MAX_TIER_WIDTH) {
                throw new IllegalArgumentException("패드 한 변 상한 위반 (H-3 ≤" + MAX_TIER_WIDTH
                        + "): " + ps.name() + " " + ps.width() + "×" + ps.depth());
            }
            if (!zones.add(ps.zone())) {
                throw new IllegalArgumentException("구역 번호 중복: " + ps.zone());
            }
            int x0 = peakX + ps.dx() - ps.width() / 2;
            int zN = peakZ + ps.dz() - ps.depth() / 2;
            Pad pad = new Pad(ps, baseY + ps.h(), x0, x0 + ps.width() - 1, zN, zN + ps.depth() - 1);
            for (Pad prev : out) {
                if (pad.x0() <= prev.x1() && prev.x0() <= pad.x1()
                        && pad.zN() <= prev.zS() && prev.zN() <= pad.zS()) {
                    throw new IllegalArgumentException("패드 겹침: " + ps.name() + " ↔ " + prev.spec().name());
                }
            }
            out.add(pad);
        }
        return List.copyOf(out);
    }

    /**
     * 링크 → 실계단 — <b>순수 함수</b>. 낙차 창·담김·닿음(보도 상한)을 거절하고,
     * ★계단 몸체가 <b>남의 패드를 지나는 것</b>도 여기서 거절한다 (제 위·아래 패드는 예외 —
     * 침범은 조성이 아니라 계획이 막는다).
     */
    public static List<StairLane> resolveLanes(Campus campus, List<Pad> pads) {
        List<StairLane> lanes = new ArrayList<>(campus.links().size());
        for (StairLink link : campus.links()) {
            StairLane lane = laneOf(padOf(pads, link.upperZone()), padOf(pads, link.lowerZone()), link);
            int px = lane.dirZ() != 0 ? 1 : 0;
            int pz = lane.dirZ() != 0 ? 0 : 1;
            for (int t = 1; t <= lane.length(); t++) {
                for (int o = -RAIL_OFF; o <= RAIL_OFF; o++) {
                    int x = lane.startX() + lane.dirX() * (t - 1) + px * o;
                    int z = lane.startZ() + lane.dirZ() * (t - 1) + pz * o;
                    for (Pad p : pads) {
                        int zone = p.spec().zone();
                        if (zone != link.upperZone() && zone != link.lowerZone() && p.contains(x, z)) {
                            throw new IllegalArgumentException("계단 " + link.upperZone() + "→"
                                    + link.lowerZone() + " 이 남의 패드를 지난다: " + p.spec().name()
                                    + " (" + x + "," + z + ") — 링크나 패드 자리를 고쳐라");
                        }
                    }
                }
            }
            lanes.add(lane);
        }
        return List.copyOf(lanes);
    }

    private static Pad padOf(List<Pad> pads, int zone) {
        for (Pad p : pads) {
            if (p.spec().zone() == zone) {
                return p;
            }
        }
        throw new IllegalArgumentException("링크가 없는 구역을 부른다: " + zone);
    }

    /** 계단 하나를 앉힌다 — 순수 기하. 명세가 안 앉으면 이유를 말하고 던진다. */
    public static StairLane laneOf(Pad upper, Pad lower, StairLink link) {
        int dy = upper.y() - lower.y();
        String who = link.upperZone() + "→" + link.lowerZone();
        if (dy < MIN_STAIR_DY || dy > MAX_STAIR_DY) {
            throw new IllegalArgumentException("계단 " + who + ": 낙차 " + dy + " 이 ["
                    + MIN_STAIR_DY + "," + MAX_STAIR_DY + "] 밖 — 계단참 패드를 끼우거나 높이를 고쳐라");
        }
        int dirX = 0;
        int dirZ = 0;
        int sx;
        int sz;
        switch (link.side()) {
            case 'S' -> {
                dirZ = 1;
                sx = upper.cx();
                sz = upper.zS() + 1;
            }
            case 'N' -> {
                dirZ = -1;
                sx = upper.cx();
                sz = upper.zN() - 1;
            }
            case 'E' -> {
                dirX = 1;
                sx = upper.x1() + 1;
                sz = upper.cz();
            }
            case 'W' -> {
                dirX = -1;
                sx = upper.x0() - 1;
                sz = upper.cz();
            }
            default -> throw new IllegalArgumentException("계단 " + who + ": 면 '" + link.side()
                    + "' 은 S·N·E·W 가 아니다");
        }
        // 폭 방향 담김 — 계단 몸체(±RAIL_OFF)가 아랫패드 안에 들어야 닿아서 이인다
        if (dirZ != 0) {
            if (sx - RAIL_OFF < lower.x0() || sx + RAIL_OFF > lower.x1()) {
                throw new IllegalArgumentException("계단 " + who + ": 몸체(x" + (sx - RAIL_OFF) + ".."
                        + (sx + RAIL_OFF) + ")가 아랫패드 폭(x" + lower.x0() + ".." + lower.x1() + ") 밖");
            }
        } else {
            if (sz - RAIL_OFF < lower.zN() || sz + RAIL_OFF > lower.zS()) {
                throw new IllegalArgumentException("계단 " + who + ": 몸체(z" + (sz - RAIL_OFF) + ".."
                        + (sz + RAIL_OFF) + ")가 아랫패드 깊이(z" + lower.zN() + ".." + lower.zS() + ") 밖");
            }
        }
        int treads = dy - 1;
        int entry = -1;
        for (int t = 1; t <= treads + MAX_WALK + 1; t++) {
            if (lower.contains(sx + dirX * (t - 1), sz + dirZ * (t - 1))) {
                entry = t;
                break;
            }
        }
        if (entry < 0) {
            throw new IllegalArgumentException("계단 " + who + ": 램프+보도 " + (treads + MAX_WALK)
                    + "칸 안에 아랫패드에 닿지 않는다 — 패드를 당기거나 링크를 고쳐라");
        }
        int walk = Math.max(0, entry - treads);
        return new StairLane(link, sx, sz, dirX, dirZ, upper.y(), lower.y(), treads, walk);
    }

    /** 순수 전수 검증 — 명세만으로 앉힘 전체를 재본다 (월드 불요 · 눈과 계획이 같은 길을 쓴다). */
    public static void validate(Campus campus) {
        resolveLanes(campus, resolvePads(campus, 0, 0, 0));
    }

    /** 발자국 안 실지형(수목 제외)의 상위 백분위 y — 쌓기 우선의 눈금 */
    private static int percentileGround(World world, int x0, int x1, int zN, int zS, int pct) {
        int cols = (x1 - x0 + 1) * (zS - zN + 1);
        int[] gs = new int[cols];
        int k = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = zN; z <= zS; z++) {
                gs[k++] = groundY(world, x, z);
            }
        }
        java.util.Arrays.sort(gs);
        int idx = Math.min(cols - 1, (int) Math.ceil(cols * (pct / 100.0)) - 1);
        return gs[Math.max(0, idx)];
    }

    /** 실지면 — 수목·잎을 뚫고 밟는 땅을 찾는다 ({@link TrailBuilder#groundSolid} 재사용) */
    private static int groundY(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        int min = world.getMinHeight();
        while (y > min && !TrailBuilder.groundSolid(world.getBlockAt(x, y, z).getType())) {
            y--;
        }
        return y;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 조성 — 패드 하나·계단 하나씩 (TickBudget 이 사이사이 예산을 물린다)
    // ═══════════════════════════════════════════════════════════════════

    /** 조성 대장 — census 가 읽는다 */
    public static final class Tally {
        public long pavement;
        public long core;
        public long wallFace;
        public long parapet;
        public long stairTreads;
        public long cut;
        public long lanterns;
    }

    /**
     * 패드 하나를 앉힌다 — 걷기(수목·돌출 깎기) → 채움(속은 돌, 가장자리는 석축 결) →
     * 포장(가장자리 테두리는 석전) → 여장(계단이 드나드는 자리는 비운다 · 모서리 등롱).
     * <b>월드+패드 기하만 쓴다</b> — 곁봉 패드(슬라이스 3)가 그대로 재사용한다.
     */
    public static void pavePad(World world, Plan plan, Pad pad, Tally tally) {
        for (int x = pad.x0(); x <= pad.x1(); x++) {
            for (int z = pad.zN(); z <= pad.zS(); z++) {
                int g = groundY(world, x, z);
                // 걷기 — 패드 위 하늘을 연다 (목표 위로 솟은 바위·수목 = 상위 15%의 깎기)
                int clearTop = Math.max(g + 2, pad.y() + HEADROOM);
                for (int y = pad.y() + 1; y <= clearTop; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (!b.getType().isAir()) {
                        b.setType(Material.AIR, false);
                        tally.cut++;
                    }
                }
                boolean edge = x == pad.x0() || x == pad.x1() || z == pad.zN() || z == pad.zS();
                // 채움 — 실지면에서 포장면 밑까지. 가장자리 열은 석축 결(보이는 면)
                for (int y = g + 1; y < pad.y(); y++) {
                    world.getBlockAt(x, y, z).setType(edge ? faceMaterial(x, y, z) : Material.STONE, false);
                    if (edge) {
                        tally.wallFace++;
                    } else {
                        tally.core++;
                    }
                }
                // 포장면 — 가장자리는 석전 테두리, 안은 박석 섞음
                world.getBlockAt(x, pad.y(), z)
                        .setType(edge ? Material.STONE_BRICKS : paveMaterial(x, z), false);
                tally.pavement++;
            }
        }
        skirt(world, plan, pad, tally);
        parapet(world, plan, pad, tally);
    }

    /** 패드 둘레 스커트 폭 — 가장자리에 걸린 지형 어깨를 이만큼 밀어낸다 */
    public static final int SKIRT = 2;

    /**
     * 스커트 — 패드 둘레 {@value #SKIRT}칸 띠에서 포장면 위로 솟은 지형(어깨 혹)을 걷는다.
     * 실기동 컷 4 의 병: 본전단 동편에서 지형 어깨가 여장에 바로 붙어 단을 내려다봤다.
     * 포장면 아래는 안 건드린다 — 옹벽이 딛는 비탈은 자연 그대로다.
     *
     * <p>★가드 (슬라이스 2 실기동의 병): 띠가 <b>남의 패드·계단 발자국</b>에 걸치면 그 열은
     * 건너뛴다 — 측문(16)의 스커트가 맞붙은 생활 하(5)의 가장자리 열(여장·포장·속채움)을
     * 파먹어 접지 32·평탄 6 이 났다. 남의 것은 남의 것이다.
     */
    private static void skirt(World world, Plan plan, Pad pad, Tally tally) {
        for (int x = pad.x0() - SKIRT; x <= pad.x1() + SKIRT; x++) {
            for (int z = pad.zN() - SKIRT; z <= pad.zS() + SKIRT; z++) {
                if (pad.contains(x, z) || onOtherPad(plan, pad, x, z) || laneCovered(plan, x, z)) {
                    continue;
                }
                for (int y = pad.y() + 1; y <= pad.y() + HEADROOM; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (!b.getType().isAir()) {
                        b.setType(Material.AIR, false);
                        tally.cut++;
                    }
                }
            }
        }
    }

    private static boolean onOtherPad(Plan plan, Pad self, int x, int z) {
        for (Pad p : plan.pads()) {
            if (p != self && p.contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    /** 여장 — 네 가장자리. 계단 몸체가 덮는 자리는 비운다 (드나드는 문). 모서리엔 등롱. */
    private static void parapet(World world, Plan plan, Pad pad, Tally tally) {
        int y = pad.y() + 1;
        for (int x = pad.x0(); x <= pad.x1(); x++) {
            for (int z : new int[]{pad.zN(), pad.zS()}) {
                if (!laneCovered(plan, x, z)) {
                    world.getBlockAt(x, y, z).setType(Material.STONE_BRICK_WALL, false);
                    tally.parapet++;
                }
            }
        }
        for (int z = pad.zN(); z <= pad.zS(); z++) {
            for (int x : new int[]{pad.x0(), pad.x1()}) {
                if (!laneCovered(plan, x, z)) {
                    world.getBlockAt(x, y, z).setType(Material.STONE_BRICK_WALL, false);
                    tally.parapet++;
                }
            }
        }
        // 네 모서리 — 석전 기둥 위 등롱 (레퍼런스의 온광 점정)
        for (int cx : new int[]{pad.x0(), pad.x1()}) {
            for (int cz : new int[]{pad.zN(), pad.zS()}) {
                if (laneCovered(plan, cx, cz)) {
                    continue;
                }
                world.getBlockAt(cx, y, cz).setType(Material.STONE_BRICKS, false);
                world.getBlockAt(cx, y + 1, cz).setType(Material.LANTERN, false);
                tally.lanterns++;
            }
        }
    }

    private static boolean laneCovered(Plan plan, int x, int z) {
        for (StairLane lane : plan.lanes()) {
            if (lane.covers(x, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 대계단 하나를 앉힌다 — 윗패드 면에서 한 칸 계단(1:1)으로 내려가고, 램프가 끝나면
     * 아랫포장 높이의 보도가 아랫패드까지 잇는다. <b>몸체 전 열이 실지형/아랫포장까지 채워져
     * 접지한다</b> — 패드 사이가 벌어져 있어도 계단은 비탈을 딛고 선다 (뜬 계단 없음 · B-146).
     * 양옆 측석 위 여장, 네 칸마다 등롱 — 레퍼런스 1호(산길 대계단)의 열주 문법.
     */
    public static void paveStair(World world, StairLane lane, Tally tally) {
        int px = lane.dirZ() != 0 ? 1 : 0;   // 폭 방향
        int pz = lane.dirZ() != 0 ? 0 : 1;
        for (int t = 1; t <= lane.length(); t++) {
            int cx = lane.startX() + lane.dirX() * (t - 1);
            int cz = lane.startZ() + lane.dirZ() * (t - 1);
            boolean ramp = t <= lane.treads();
            int standY = ramp ? lane.topY() - t : lane.lowY();
            for (int o = -STAIR_HALF; o <= STAIR_HALF; o++) {
                int x = cx + px * o;
                int z = cz + pz * o;
                // ★걷기 먼저 — 노선 위로 솟은 지형(능선 혹)을 실지면까지 재서 걷어낸다.
                //   슬라이스 1 실기동이 잡은 병: 아래로는 접지하는데 위로는 안 깎아,
                //   계단이 지형 혹을 타넘었다 (보행 단차 4~8 · 상부 척추 3링크). 디딤이 정본이다.
                clearAbove(world, x, standY, z, tally);
                // 접지 채움 — 밟는 면 밑을 실지형/아랫포장까지 (뜬 계단 없음)
                fillDown(world, x, standY - 1, z, tally);
                Block top = world.getBlockAt(x, standY, z);
                if (ramp) {
                    Stairs data = (Stairs) Material.STONE_BRICK_STAIRS.createBlockData();
                    data.setFacing(ascent(lane));
                    top.setBlockData(data, false);
                    tally.stairTreads++;
                } else {
                    top.setType(Material.STONE_BRICKS, false);
                    tally.pavement++;
                }
            }
            // 측석 + 여장/등롱 — 여기도 위를 먼저 걷는다 (바위에 묻힌 난간 금지)
            for (int side : new int[]{-RAIL_OFF, RAIL_OFF}) {
                int x = cx + px * side;
                int z = cz + pz * side;
                clearAbove(world, x, standY, z, tally);
                fillDown(world, x, standY, z, tally);
                world.getBlockAt(x, standY, z).setType(Material.STONE_BRICKS, false);
                if (t % 4 == 0) {
                    world.getBlockAt(x, standY + 1, z).setType(Material.STONE_BRICKS, false);
                    world.getBlockAt(x, standY + 2, z).setType(Material.LANTERN, false);
                    tally.lanterns++;
                } else {
                    world.getBlockAt(x, standY + 1, z).setType(Material.STONE_BRICK_WALL, false);
                    tally.parapet++;
                }
            }
        }
    }

    /**
     * 밟는 면 위를 하늘까지 연다 — 실지면({@link #groundY})이 밟는 면보다 높으면 그 위 2칸까지,
     * 아니면 머리 공간({@value #HEADROOM})까지 걷는다 (패드 걷기와 같은 결).
     */
    private static void clearAbove(World world, int x, int standY, int z, Tally tally) {
        int g = groundY(world, x, z);
        int clearTop = Math.max(g + 2, standY + HEADROOM);
        for (int y = standY + 1; y <= clearTop; y++) {
            Block b = world.getBlockAt(x, y, z);
            if (!b.getType().isAir()) {
                b.setType(Material.AIR, false);
                tally.cut++;
            }
        }
    }

    /** 오름 방향 — 계단 facing 은 오르는 쪽 (도보길과 같은 결) */
    private static BlockFace ascent(StairLane lane) {
        if (lane.dirZ() > 0) {
            return BlockFace.NORTH;
        }
        if (lane.dirZ() < 0) {
            return BlockFace.SOUTH;
        }
        return lane.dirX() > 0 ? BlockFace.WEST : BlockFace.EAST;
    }

    /** (x, fromY, z) 에서 아래로, 이미 솟은 것(포장·지형)을 만날 때까지 석전으로 채운다 */
    private static void fillDown(World world, int x, int fromY, int z, Tally tally) {
        int min = world.getMinHeight();
        for (int y = fromY; y > min; y--) {
            Block b = world.getBlockAt(x, y, z);
            if (!b.getType().isAir()) {
                return;
            }
            b.setType(Material.STONE_BRICKS, false);
            tally.core++;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 검수 — 선 것을 다시 잰다 (계획을 안 믿는다)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 검수 결과.
     *
     * @param flatViolations  패드 안쪽인데 밟는 면이 포장면이 아닌 열
     * @param floatViolations 포장면에서 내려가다 <b>허공을 만난</b> 열 (B-146 — 0 이어야 한다)
     * @param walkBreaks      계단 보행에서 단차 >1 인 걸음
     * @param checkedCols     잰 열 수
     * @param notes           위반 표본 (앞 몇 개)
     */
    public record Audit(int flatViolations, int floatViolations, int walkBreaks,
                        int checkedCols, List<String> notes) {

        public boolean clean() {
            return flatViolations == 0 && floatViolations == 0 && walkBreaks == 0;
        }
    }

    /** 조성 뒤 전수 검수 — 건물 없는 판 (슬라이스 1 호환: 발자국 예외 없음). */
    public static Audit audit(World world, Plan plan) {
        return audit(world, plan, p -> null);
    }

    /**
     * 조성 뒤 전수 검수 — 위반은 세고, 표본은 남긴다 (호출자가 소리친다).
     *
     * @param buildingBox 패드 → 건물 발자국 [x0,x1,zN,zS] (없으면 null) — 평탄 눈이 그 안을
     *                    비켜 간다 (건물은 포장면 위로 솟는 것이 정상이다). 접지 눈은 안 비켜
     *                    간다 — 건물 밑도 떠 있으면 안 된다 (B-146).
     */
    public static Audit audit(World world, Plan plan,
                              java.util.function.Function<Pad, int[]> buildingBox) {
        int flat = 0;
        int floats = 0;
        int cols = 0;
        // ★표본 쿼터 — 종류별 4건씩 (한 종류가 표본을 독식해 다른 위반의 좌표를 가리지 않게)
        List<String> flatNotes = new ArrayList<>();
        List<String> floatNotes = new ArrayList<>();
        List<String> walkNotes = new ArrayList<>();
        for (Pad pad : plan.pads()) {
            int[] box = buildingBox.apply(pad);
            for (int x = pad.x0(); x <= pad.x1(); x++) {
                for (int z = pad.zN(); z <= pad.zS(); z++) {
                    cols++;
                    boolean edge = x == pad.x0() || x == pad.x1() || z == pad.zN() || z == pad.zS();
                    boolean inBox = box != null
                            && x >= box[0] && x <= box[1] && z >= box[2] && z <= box[3];
                    // ① 평탄 — 안쪽 열의 밟는 면은 정확히 포장면 (여장·계단 몸체·건물 자리는 예외)
                    if (!edge && !inBox && !laneCovered(plan, x, z)) {
                        int top = topSolid(world, x, pad.y() + HEADROOM, z);
                        if (top != pad.y()) {
                            flat++;
                            note(flatNotes, "평탄: " + pad.spec().name() + " (" + x + "," + z
                                    + ") 밟는 면 y" + top + " ≠ 포장 y" + pad.y());
                        }
                    }
                    // ② 접지 — 포장면에서 내려가며 허공을 만나면 그 열이 떠 있다 (B-146)
                    int min = world.getMinHeight();
                    for (int y = pad.y(); y > min; y--) {
                        Material m = world.getBlockAt(x, y, z).getType();
                        if (m.isAir()) {
                            floats++;
                            note(floatNotes, "접지: " + pad.spec().name() + " (" + x + "," + z
                                    + ") y" + y + " 허공 — 열이 떠 있다");
                            break;
                        }
                        if (TrailBuilder.groundSolid(m) && !placedMasonry(m)) {
                            break;   // 자연 지반에 닿았다 — 이 열은 접지
                        }
                    }
                }
            }
        }
        // ③ 계단 보행 — 링크마다 윗패드 두 칸 앞에서 아랫패드 두 칸 안까지 한 칸 계단 원칙
        int breaks = 0;
        for (StairLane lane : plan.lanes()) {
            int prev = Integer.MIN_VALUE;
            for (int t = -2; t <= lane.length() + 2; t++) {
                int x = lane.startX() + lane.dirX() * (t - 1);
                int z = lane.startZ() + lane.dirZ() * (t - 1);
                // ★걷는 자의 눈 — 다음 발판은 「이전 발판 +2」에서 내려 찾는다. 하늘에서 찾으면
                //   문루·처마의 지붕을 발판으로 오독한다 (2.6 실기동: 측문 문루 처마가 단차 8 로 짖음 —
                //   보행자는 그 밑을 지나간다). 머리 높이(+2)에 막힌 것은 여전히 단차로 잡힌다.
                int stand = prev == Integer.MIN_VALUE
                        ? topSolid(world, x, lane.topY() + 2, z)
                        : topSolid(world, x, prev + 2, z);
                if (prev != Integer.MIN_VALUE && Math.abs(stand - prev) > 1) {
                    breaks++;
                    note(walkNotes, "보행: 계단 " + lane.link().upperZone() + "→" + lane.link().lowerZone()
                            + " (" + x + "," + z + ") 단차 " + Math.abs(stand - prev)
                            + " (y" + prev + "→y" + stand + ")");
                }
                prev = stand;
            }
        }
        List<String> notes = new ArrayList<>(flatNotes);
        notes.addAll(floatNotes);
        notes.addAll(walkNotes);
        return new Audit(flat, floats, breaks, cols, List.copyOf(notes));
    }

    private static void note(List<String> notes, String s) {
        if (notes.size() < 4) {   // 종류별 쿼터 — 셋을 합쳐 최대 12건이 표본이 된다
            notes.add(s);
        }
    }

    /**
     * 이 기계가 놓았을 수 있는 석재인가 — 접지 검수의 하강이 <b>어디서 멈출지</b>를 가른다
     * (석재를 지나 자연 지반에 닿으면 접지, 도중에 허공이면 부양). {@code STONE}·{@code TUFF}
     * 는 자연에도 있지만 상관없다 — 판정 기준은 「허공을 만나느냐」이지 「누가 놓았느냐」가
     * 아니다 (자연 돌을 더 지나 내려가도 결론은 같다 · 산세 시험 월드는 속이 꽉 찬 조성이다).
     */
    private static boolean placedMasonry(Material m) {
        return m == Material.STONE || m == Material.STONE_BRICKS
                || m == Material.CRACKED_STONE_BRICKS || m == Material.MOSSY_STONE_BRICKS
                || m == Material.ANDESITE || m == Material.POLISHED_ANDESITE
                || m == Material.TUFF;
    }

    private static int topSolid(World world, int x, int fromY, int z) {
        int y = fromY;
        int min = world.getMinHeight();
        while (y > min && world.getBlockAt(x, y, z).getType().isAir()) {
            y--;
        }
        return y;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 팔레트 — 결정론 섞음 (난수 없음 · 재실행 멱등)
    // ═══════════════════════════════════════════════════════════════════

    /** 이 기계가 쓰는 재료 전부 — 눈이 금지 재료(B-195: barrel·light)를 이 표로 잰다. */
    public static Set<Material> palette() {
        return EnumSet.of(
                Material.STONE, Material.STONE_BRICKS, Material.CRACKED_STONE_BRICKS,
                Material.MOSSY_STONE_BRICKS, Material.ANDESITE, Material.POLISHED_ANDESITE,
                Material.TUFF, Material.STONE_BRICK_STAIRS, Material.STONE_BRICK_WALL,
                Material.LANTERN, Material.AIR);
    }

    /** 옹벽 결 — 층대(4단마다 안산암 띠) 위에 석전·응회암·이끼가 결정론으로 섞인다 */
    private static Material faceMaterial(int x, int y, int z) {
        if (y % 4 == 0) {
            return Material.ANDESITE;   // 층대 띠 — 레퍼런스 석축의 가로 결
        }
        int r = (int) Math.floorMod(mix(SALT_FACE, x, y, z), 100);
        if (r < 62) {
            return Material.STONE_BRICKS;
        }
        if (r < 78) {
            return Material.ANDESITE;
        }
        if (r < 88) {
            return Material.TUFF;
        }
        if (r < 96) {
            return Material.CRACKED_STONE_BRICKS;
        }
        return Material.MOSSY_STONE_BRICKS;
    }

    /** 포장 결 — 박석(연마 안산암) 바탕에 석전이 섞인다 (레퍼런스 광장 바닥) */
    private static Material paveMaterial(int x, int z) {
        int r = (int) Math.floorMod(mix(SALT_PAVE, x, 0, z), 100);
        if (r < 55) {
            return Material.POLISHED_ANDESITE;
        }
        if (r < 82) {
            return Material.STONE_BRICKS;
        }
        if (r < 93) {
            return Material.ANDESITE;
        }
        return Material.CRACKED_STONE_BRICKS;
    }

    private static long mix(long salt, int x, int y, int z) {
        long h = salt ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL)
                ^ (z * 0x165667B19E3779F9L);
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return h;
    }
}
