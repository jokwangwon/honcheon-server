package com.honcheon.mvt.forge;

import java.util.List;

/**
 * 산세 사양 — <b>v5 산세 생성기(기계 ①)의 주문서</b>.
 *
 * <p>웨이브-2 트랙 A (H-12 — 시험 조성 필요분 착수 승인, docs/BACKLOG.md B-148 결정 회차 2).
 * 설계 정본: {@code docs/design/terrain_forge_v5.md} — 모든 수의 근거가 거기 있다.
 * <b>확정값은 출처 주석이 붙어 있고, 유도할 수 없는 값은 「후보」다</b> — 후보는 승인 전에는
 * 수가 아니다 (terrain_forge_v5.md §4 미결 질문 표가 그 원장이다).
 *
 * <p>★ 등록부 이관 예정: 이 팩토리의 기본값은 {@code config/mountain_ranges.yml} (신설 후보 —
 * rivers.yml 문법의 산세판)로 나간다. 수치를 코드에 박아 두는 것은 착수 단계의 임시다
 * (H-14 의 결 — 배선 지점 terrain_forge_v5.md §5-4).
 *
 * <p>좌표계: 주봉 {@code (peakX, peakZ)} 기준. 남 = +z (Minecraft) — 진입·주능선이 남이다
 * (등록부: 산문은 남쪽, TerrainForge 봉우리 문법과 같은 결).
 *
 * @param placeId        등록부 id (world_map.yml) — 검수·로그가 부른다
 * @param peakX          주봉 중심 x
 * @param peakZ          주봉 중심 z — ★부지 중심에서 북으로 8칸 현행 문법 계승 후보 (Q6)
 * @param baseY          superflat 기준 지면 — ★미결 Q2 (D-7 세부). 코드가 정하지 않는다
 * @param lift           산 높이 — 대표 후보 160 (사용자 확정 구간 150~165 「더 높게」 —
 *                       재산출 근거는 terrain_forge_v5.md §2.2 검산 넷 · ★Q1)
 * @param summitFlatR    정상 평탄부 반경 — 화산 후보 10 (판단 ⓑ′: 정상은 수행대·전망대의 것,
 *                       수행대 9×9 → ceil((7+2)/0.9) — hwasan_brief_v5.md §1.3 · ★Q3)
 * @param honsanR        본산권 반경 124 = footprint_radius_min (H-7 확정 — 정상 32 +
 *                       계단 방사 48 + 단 깊이 44, hwasan_brief_v5.md §1.4)
 * @param honsanRise     본산~정상 오름 72 (계단 몫 — 계단 물매 1:1 × 굴곡 1.5 → 방사 48.
 *                       ★이 값이 honsanR 124 유도의 입력이다 — 바꾸면 H-7 이 무너진다)
 * @param midDepth       중간 구역 방사 깊이 140 (H-6 확정 — 곁구역 4개 각 35)
 * @param entranceDepth  화산 입구 구역 깊이 30 (H-6 확정)
 * @param apronDepth     외곽 평원 깊이 150 (H-6 확정 — 화산촌·농경지)
 * @param ridgeHalfWidth 주능선 등마루 반폭 20 — 폭 41 = 최대 단 폭 35 (연무장 단, H-3) +
 *                       여유 6 (hwasan_brief_v5.md §1.5-가 "폭 후보 ≥41")
 * @param peakSteepSlope 주봉 험면(비능선)의 물매 — 1:0.9 원뿔 문법 계승
 *                       (TerrainForge raiseMassif · terrain.yml massif_slope)
 * @param sideRidges     곁능선 — 전부 후보 (★Q4)
 * @param gorges         협곡 (감산 채널 — 계곡 구역·수계 ②의 자리) — 전부 후보 (★Q5)
 */
public record RangeSpec(
        String placeId,
        int peakX, int peakZ,
        int baseY,
        int lift,
        int summitFlatR,
        int honsanR,
        int honsanRise,
        int midDepth,
        int entranceDepth,
        int apronDepth,
        int ridgeHalfWidth,
        double peakSteepSlope,
        List<SideRidge> sideRidges,
        List<Gorge> gorges) {

    /**
     * 곁능선 — 주능선 스와스 문법의 축소판.
     *
     * @param azimuthDeg 남축(+z) 기준 방위각(도) — 양수는 동으로, 음수는 서로 (★후보 Q4)
     * @param length     축 길이 (칸) (★후보)
     * @param crestScale 등고 축척 — 주능선 crest 의 몇 배 높이인가 0..1 (★후보)
     * @param halfWidth  등마루 반폭 (★후보)
     */
    public record SideRidge(double azimuthDeg, int length, double crestScale, int halfWidth) {
    }

    /**
     * 협곡 — 감산 채널. 마른 골로 판다 — <b>물은 수계(기계 ②)의 몫이다</b>.
     *
     * @param azimuthDeg 남축 기준 방위각(도) (★후보 Q5)
     * @param innerR     방사 창 안쪽 (칸) — 이 안쪽은 파지 않는다
     * @param outerR     방사 창 바깥쪽 (칸)
     * @param depth      최대 깊이 (칸) (★후보 — 수계 ② 낙차와 한 몸)
     * @param halfWidth  채널 반폭 (★후보)
     */
    public record Gorge(double azimuthDeg, int innerR, int outerR, int depth, int halfWidth) {
    }

    /** 영향권 설계 범위 (방사) — 124 + 140 + 30 = 294 (domain_extent 296 의 몸통, H-7) */
    public int domainR() {
        return honsanR + midDepth + entranceDepth;
    }

    /** 생활권 설명 범위 (방사) — 294 + 150 = 444 (economy_extent 448 의 몸통, H-7) */
    public int economyR() {
        return domainR() + apronDepth;
    }

    /** 곁구역(연무 계곡·절벽·계곡·매화림) 한 띠의 깊이 — 140 ÷ 4 = 35 (H-6 유도) */
    public int beltDepth() {
        return midDepth / 4;
    }

    /** 등산로 몫의 오름 — lift − 본산 몫 72 (lift 160 이면 88) */
    public int trailRise() {
        return lift - honsanRise;
    }

    /**
     * 화산 산세 — 시험 조성 1호의 사양 (수의 근거는 각 파라미터 주석 · terrain_forge_v5.md §0·§2).
     *
     * @param peakX 주봉 x (등록 좌표 반영은 조율자 — 배선 지점)
     * @param peakZ 주봉 z (부지 중심에서 북 8 계승 후보 — Q6)
     * @param baseY superflat 기준 지면 (★Q2 — 밖에서 정해 넣는다)
     */
    public static RangeSpec hwasan(int peakX, int peakZ, int baseY) {
        return new RangeSpec(
                "hwasan",
                peakX, peakZ,
                baseY,
                160,        // lift 대표 후보 — 150~165 확정 구간 · 8칸 격자 · 검산 넷 (★Q1)
                10,         // 정상 평탄부 — 수행대 유도 후보 (★Q3)
                124,        // footprint_radius_min (H-7 확정)
                72,         // 본산~정상 계단 몫 (§1.4 — honsanR 124 의 유도 입력)
                140,        // 중간 구역 (H-6 확정)
                30,         // 입구 (H-6 확정)
                150,        // 외곽 평원 (H-6 확정)
                20,         // 등마루 반폭 — 폭 41 (브리프 §1.5-가)
                0.9,        // 험면 물매 1:0.9 (원뿔 문법 계승)
                List.of(    // ★전부 순수 후보 (Q4) — 계곡·절벽 구역을 성립시키는 최소 두 가닥
                        new SideRidge(60, 200, 0.55, 10),
                        new SideRidge(-60, 200, 0.55, 10)),
                List.of(    // ★전부 순수 후보 (Q5) — 주능선과 곁능선 사이의 골 둘
                        new Gorge(28, 60, 264, 16, 8),
                        new Gorge(-28, 60, 264, 16, 8)));
    }
}
