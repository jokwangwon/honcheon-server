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
 * @param sideRidges     곁능선 — <b>3가닥 확정</b> (Q4 — 웨이브-2 결정 회차 A-2 · 재유도
 *                       terrain_forge_v5.md §2.4-3·§7 딸린 재작업 1). 방위·규모의 근거는
 *                       {@link #hwasan} 팩토리 주석 참조
 * @param gorges         협곡 (감산 채널 — 계곡 구역·수계 ②의 자리) — 2줄 확정 (Q5 · A-2)
 * @param trail          등산로 경유점 — <b>곁구역(매화림·계곡·절벽·연무 계곡) 경계가 이것을
 *                       "꿴다"</b> (Q9 확정 — A-2 · 재단 방식 §2.3·§7 딸린 재작업 2).
 *                       ★계약: 이 폴리라인은 <b>등산로 생성기(기계 ③)의 산출</b>이다 —
 *                       ③가 아직 없으면 빈 목록으로 두고, RangeField 가 확정값에서 유도한
 *                       <b>잠정 지그재그 노선</b>(1차 시험 조성용)을 쓴다. ③가 서면 이 목록으로
 *                       갈아끼운다 (기하는 ③ 소유, 곁구역 태그 순서는 RangeField 소유 — §2.3)
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
        List<Gorge> gorges,
        List<TrailWaypoint> trail) {

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

    /**
     * 돌 봉우리 — 다봉 <b>군집(cluster)</b>의 한 봉. <b>봉우리 부품</b> (군집 재설계 · 사용자 도보
     * 피드백 2026-07-16 반복 3회차: "큰 산 하나에 봉우리가 붙은" 게 아니라 "여러 산이 조율적으로
     * 붙은" — 華山 다섯 봉이 각각 독립 화강암 첨봉·능선으로 이어진 돌산 군집).
     *
     * <p>★★ 단일 중앙 돔 폐기: 옛 모델은 중앙 (0,0) 단일 원뿔(radialBody, +160)이 지배하고 봉은
     * 그 어깨에 얹힌 낮은 융기였다. 새 모델은 봉을 <b>1차 질량</b>으로 세운다 — 각 봉은 mesa
     * (본산 몫 등고 = lift−honsanRise = 88) 위로 솟는 <b>가파른 원뿔 첨봉</b>이고, 봉들이
     * <b>부드러운 max(soft-max)</b>로 합성돼 <b>안부(saddle)로 조율적으로 이어진다</b>. 중앙은
     * 봉이 아니라 봉들에 감싸인 <b>낮은 건물 단(court)</b>이다 (남으로 열림). 봉 마루는 전부
     * lift(160) 미만이나 서로 준하는 높이(150~158)라 하나가 압도하지 않는다.
     *
     * <p>원뿔 물매 = {@code (height − mesa) / spread} — spread 안에서 mesa 로 내려온다. spread 를
     * radius 와 합쳐 <b>honsanR(124) 안</b>에 가두므로(r≥124 는 옛 skirt 그대로 → 등산로·TrailForge
     * 불변), 군집은 mesa 대지 위의 「모자」다. 첨봉은 가파르게(spread 작게) 세워 넓은 평탄 정상이 없다.
     *
     * <p>결정론: 좌표의 순수 함수(원뿔·삼각함수·soft-max — 난수 0). 봉우리는 방위·반경으로 못박은
     * 절대 자리다. B-155 연속성: 원뿔은 립시츠(물매 유한)·soft-max 는 C∞ 라 z=0 급단차를 안 만든다.
     *
     * @param azimuthDeg 남축(+z) 기준 방위각(도) — 봉우리 중심의 방위 (★잠정·승인 대기)
     * @param radius     주봉(중앙 단)에서 봉우리 중심까지 방사(칸) — 감싸는 링 (★잠정·승인 대기)
     * @param height     봉우리 마루 등고(base 위) — <b>lift 미만</b> 강제 · 서로 준함 (★잠정·승인 대기)
     * @param spread     첨봉 반경(칸) — 이 안에서 height→mesa 로 내려온다. 작을수록 가파른 첨봉.
     *                   {@code radius + spread ≤ ~120} 로 honsanR 안에 가둔다 (★잠정·승인 대기)
     */
    public record SubPeak(double azimuthDeg, int radius, double height, int spread) {
    }

    // ─── 돌 봉우리(다봉 군집) 잠정 파라미터 — 華山 다섯 봉 유도 · 전부 승인 대기 ──────────
    //   구성: 중앙 = 낮은 건물 단(court, 봉 아님) + 감싸는 봉 5 (서·동·북·남서·남동). 정확한
    //     수·배치는 사용자 결정(§8.2 R-3~R-7).
    //   방위 유도(감싸되 남으로 열림): 서(−100)·동(+100) 좌우 측봉 · 북(180) 뒤 봉(후산 방위) ·
    //     남서(−50)·남동(+50) 앞 봉이 진입로를 좌우에서 낀다. 남축(az0)은 비운다 — 앞 두 봉
    //     사이가 안부(mesa)로 낮아 등산로·조망이 남으로 열린 품이 된다.
    //   높이 유도(서로 준함): 150~158 · 전부 lift(160) 미만. 서봉(蓮花峰 모티프)이 최고(158),
    //     북봉이 최저(150 — 실제 華山 北峰 최저 계승). 하나가 압도하지 않는 「여러 산」.
    //   반경·spread: 링 반경 66~72 · spread 44~50 → radius+spread ≤ 116~120(honsanR 안). 봉 마루가
    //     인접 안부보다 20~40 솟아 봉으로 읽히고, soft-max 가 안부를 바깥 사면보다 높게 이어 준다.
    private static final double PK_W_H = 158, PK_E_H = 152, PK_N_H = 150, PK_SW_H = 156, PK_SE_H = 150;

    /**
     * 다봉 군집 봉우리 목록 — <b>확정값(honsanR·mesa)에서 유도한 잠정 배치</b>. 레코드 성분이
     * 아니라 파생 메서드다(레코드 서명 불변 → withTrail·호출부·TrailForge 무영향 · 소유 파일 밖
     * 무수정 규약). RangeField.clusterCap 이 이 목록을 원뿔 soft-max 로 합성해 봉·안부를 이룬다.
     */
    public java.util.List<SubPeak> subPeaks() {
        return java.util.List.of(
                new SubPeak(-100, 70, PK_W_H, 46),  // 서봉(蓮花峰) — 좌측, 최고봉
                new SubPeak(100, 70, PK_E_H, 46),   // 동봉(朝陽峰) — 우측
                new SubPeak(180, 66, PK_N_H, 50),   // 북봉 — 중앙 단 뒤(후산 방위), 최저봉
                new SubPeak(-50, 72, PK_SW_H, 44),  // 남서봉 — 진입로 좌측 문기둥
                new SubPeak(50, 72, PK_SE_H, 44));  // 남동봉 — 진입로 우측 문기둥
    }

    // ─── 중앙 건물 단(court) · mesa 대지 — 군집이 딛는 두 등고 (전부 잠정·승인 대기) ──────────
    /** 본산 몫 등고 = mesa 대지 높이 (base 위) — 봉·단이 이 위로 솟는다. crest(honsanR) 과 동일 */
    public int mesaLevel() {
        return lift - honsanRise;               // 160−72 = 88 — honsanR 이음의 등고 (연속성 앵커)
    }

    /** 중앙 건물 단(본전 court) 등고(base 위) — 봉이 아니라 봉들에 감싸인 낮은 평탄 자리 (★잠정) */
    public int platformHeight() {
        return mesaLevel() + 30;                // 88+30 = 118 — 봉(150~158)보다 32~40 낮은 court (★잠정)
    }

    /** 건물 단 평탄 반경(칸) — 이 안은 court 평탄(건물이 앉는다) (★잠정·승인 대기) */
    public int platformR() {
        return 30;                              // ★잠정
    }

    /** 건물 단 가장자리 램프 폭(칸) — platformHeight→mesa 로 내려오는 전이 (물매 ≈30/40 <6, B-155) (★잠정) */
    public int platformRamp() {
        return 40;                              // ★잠정 — 물매 (118−88... 실은 118→0 유도 아님) 완만
    }

    /**
     * 등산로 경유점 — 곁구역 재단의 자 (Q9). <b>등산로 생성기(기계 ③)가 이 좌표를 준다</b>.
     * 순서가 뜻이다: {@code trail.get(0)} = 산기슭(입구 쪽, 낮음) … 마지막 = 본산 문(높음).
     * RangeField 가 폴리라인 위 호장(弧長) 비율로 곁구역 넷을 순서대로 태그한다
     * (매화림→계곡→절벽→연무 계곡 — hwasan_domain_design.md 경험 순서 · 태그는 설계 소유).
     *
     * @param x 절대 x
     * @param z 절대 z
     */
    public record TrailWaypoint(int x, int z) {
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
                // ─────────────────────────────────────────────────────────────
                // 곁능선 3가닥 — Q4 확정(회차 A-2 「3가닥 이상」) 재유도. 근거 정본:
                //   terrain_forge_v5.md §2.4-3 · §7 딸린 재작업 1.
                // · 길이 264 = honsanR(124) + midDepth(140) = crest() 지지 반경 =
                //   축 위 유효 최대 길이(넘으면 crest()=0 → 기여 없음). 곁구역 사슬
                //   (연무 계곡·절벽·계곡·매화림) 전 띠를 관통하고 입구 완사면에서 사라진다.
                //   (구후보 200 은 절벽/계곡 경계 194 에서 죽어 계곡 구역이 능선에 안 섰다.)
                // · 방위: 협곡각 28°의 격자. ±56 = 2×28 — ±28 협곡(계곡의 물길)을 주능선과
                //   함께 골(계곡)로 감싸는 좌우 대칭 한 쌍. +84 = 3×28 — 홀수 셋은 남축 대칭이
                //   불가(축 위 곁능선은 주능선과 겹쳐 퇴화)하므로, 협곡각과 겹치지 않는 유일한
                //   자리인 바깥 확장(동쪽 갈래 — 바깥 골 + 절벽 면 하나 더). 갈래의 좌/우는
                //   자유 선택(동 기본) — 근거 없는 동/서 편향을 짓지 않는다 (미결 세부 §2.4-3).
                // · 등고 축척 0.55: 절벽 띠(r≈176)에서 곁능선 마루 ≈+27, 그 방위 방사 몸체는
                //   ≈+2 로 꺼져 있어 ~25칸 곁능선 → 실제 절벽 면 · 그러나 주능선(본산 척추)보다
                //   확실히 낮다. · 반폭 10 = 주능선 반폭 20 의 절반 → 종속 능선 · 날카로운 절벽 면.
                List.of(
                        new SideRidge(56, 264, 0.55, 10),
                        new SideRidge(-56, 264, 0.55, 10),
                        new SideRidge(84, 264, 0.55, 10)),
                List.of(    // 협곡 2줄 확정 (Q5 · A-2) — 주능선과 ±56 곁능선 사이의 골 둘
                        new Gorge(28, 60, 264, 16, 8),
                        new Gorge(-28, 60, 264, 16, 8)),
                // 등산로 경유점 — 비었다: ③ 미착수 → RangeField 가 확정값 유도 잠정 노선을 쓴다
                // (Q9 · §2.3 계약). ③가 서면 등록부/③가 이 목록을 채운다.
                List.of());
    }
}
