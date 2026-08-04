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
 * @param sideRidges     곁능선 — <b>레거시 레코드 성분</b> (서명 불변용 · MvtCommand 로그가 개수를
 *                       읽는다). ★후보 C 이식(2026-07-16) 이후 <b>reliefAt 은 {@link #skelRidges}
 *                       (골격 능선)를 쓴다</b> — 이 항은 산세 형상에 안 쓰인다 (withTrail 재구성·
 *                       호출부 계약 보존용으로만 남긴다). 근거는 {@link #hwasan} 팩토리 주석
 * @param gorges         협곡 — <b>레거시 레코드 성분</b> (서명 불변). ★C 이식 이후 골 절개는
 *                       {@link #skelValleys}(골격 골 4줄)가 맡는다 — 이 항은 안 쓰인다
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

    // ═══════════════════════════════════════════════════════════════════
    // 골격 노드 (TerrainSkeleton) — C 파이프라인 (능선 골격 먼저 · 봉은 결절에서 비등방 암체)
    //   ★★ 군집 모델(SubPeak 원뿔 + soft-max + 중앙 원반 court) 폐기 → 골격-먼저 파이프라인
    //   (사용자 오프라인 A/B/C 실루엣에서 C 확정 — Codex CODEX_HWASAN_TERRAIN_IDEAS.md §3·§5~§7).
    //   전부 **잠정·승인 대기**: 좌표는 위계/역할이지 실측 복사 아님. 프로토타입(HwasanProto)에서
    //   유도하고 생산 envelope(lift 160·honsanR 124·domain 294)에 맞춰 축소했다. 난수 0.
    //   좌표계: 주봉 기준 상대 (dx,dz) · 남 = +z. 접근·건물 품 개구는 남, 최고 봉군은 북(−z).
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 비등방 암체 봉 — 원뿔 아님. axisDeg 방향으로 길고(longScale), 횡으로 짧다(shortGentle/shortWall
     * 비대칭). 직선 종단면(height−slope·d)을 깬다 — 상부 벽·중간 어깨·하부 발치의 곡률이 다르다.
     * ★B-155: shortScale 을 넓혀(생산 튜닝) 횡 도함수 상한을 낮췄다 (프로토타입 대비 완만한 벽).
     *
     * @param id          역할 이름
     * @param px,pz        봉 중심 (주봉 기준 상대 · 남=+z) (★잠정)
     * @param h            봉 마루 등고(base 위) — lift 미만 (★잠정)
     * @param axisDeg      장축(능선 접선) 방위, 남축(+z) 기준 시계 (★잠정)
     * @param longScale    장축 반경(정규화 d=1 발치) (★잠정)
     * @param shortGentle  완만 어깨 쪽 횡 반경 (★잠정 — B-155 여유로 넓힘)
     * @param shortWall    급벽 쪽 횡 반경 (★잠정)
     * @param skew         전단(등고선 기울임) (★잠정)
     * @param gentleOnPlusSide +1 이면 across>0 쪽이 완만 어깨
     */
    public record Peak(String id, double px, double pz, double h, double axisDeg,
                       double longScale, double shortGentle, double shortWall,
                       double skew, double gentleOnPlusSide) {
    }

    /**
     * 능선 간선 — 폴리라인 위 crest(안부 포함) − 비대칭 횡단 낙하. 창룡령·칼능선을 1급으로 소유
     * (우연한 soft-max 교차선이 아니라). xs/zs 는 주봉 기준 상대 경유점.
     *
     * @param saddleDepth 중간 안부 깊이 (봉이 봉으로 읽히게)
     * @param fallL,fallR 좌/우 횡단 낙하 배율(비대칭)
     */
    public record Ridge(String id, double[] xs, double[] zs, double[] hs,
                        double wStart, double wEnd, double saddleDepth,
                        double fallL, double fallR) {
    }

    /** 골 간선 — 배수선 따라 감산. 하류(s→1)로 넓어지고 깊어지되 clamp. domain 밖으로 배수. */
    public record Valley(String id, double[] xs, double[] zs,
                         double depthMax, double wNear, double wFar) {
    }

    /**
     * 건물 품(court) — <b>중앙 원반 폐기</b> (Codex §7). 상부 관문 남쪽 어깨의 <b>상부 안부</b>.
     * 남으로 긴 타원, 남쪽 가장자리는 배수 출구로 약해진다(남향 개구). 자연 산을 만든 뒤 이
     * 마스크 안에서만 최소 평탄화 (봉·능선이 만든 자리).
     *
     * @param cx,cz 품 중심 (주봉 기준 상대 · 남=+z) (★잠정)
     * @param h     품 평탄 등고(base 위) — 봉보다 낮음 (★잠정)
     * @param ax,az x 반폭 / z 반폭 (남으로 긴 타원) (★잠정)
     */
    public record Court(double cx, double cz, double h, double ax, double az) {
    }

    // ─── 골격 잠정 배치 — 華山 위상 유도 · 전부 승인 대기 (§8.2 R-3~R-7·R-11~R-13) ──────────
    //   위상: 접근(남·낮음) → 전초봉 O → 창룡령 → 상부 관문/안부(spine 뿌리) →
    //     주봉군{최고 Pm(북) ─칼능선─ 서봉 Wm · 주동릉 ─ 동봉 Em ─ 동종속 Es} · 건물 품(상부 안부·남 개구).
    //   골 4줄은 능선 사이에서 시작해 domain 밖으로 배수(중앙 폐쇄 분지 없음).
    //   높이(서로 준함): Pm 최고 158 · Em 152 · Wm 150 · Es 128(종속) · O 80(전초). 전부 lift 미만.

    /**
     * 골격 봉 목록 — 확정 envelope 에서 유도한 잠정 배치. 레코드 <b>성분이 아니라 파생 메서드</b>
     * (레코드 서명 불변 → withTrail·호출부·TrailForge 무영향 · 소유 파일 밖 무수정 규약).
     * RangeField 가 이 노드로 비등방 암체·능선·골을 합성한다 (군집 SubPeak 대체).
     */
    public java.util.List<Peak> skelPeaks() {
        return java.util.List.of(
                //         id     px    pz    h   axis long gentle wall skew  gentleSide
                new Peak("Pm",  -24,  -54, 158,  90,  50,   46,   40,  0.20, -1),  // 최고 주봉(북)·남(품)쪽 급벽
                new Peak("Wm", -104,  -16, 150,  60,  48,   46,   40, -0.20, +1),  // 서봉·칼능선(NE)으로 멀리 김
                new Peak("Em",   62,  -46, 152, 116,  48,   46,   40,  0.18, +1),  // 동봉·ESE 로 멀리 김
                new Peak("Es",   98,  -18, 128, 120,  32,   34,   28,  0.0,  +1),  // 동봉 붙은 종속 로브(낮음)
                new Peak("O",     6,  180,  56, 180,  52,   52,   48,  0.0,  +1));  // 전초봉(남·낮음·완만)·창룡령 위
    }

    /** 골격 능선 간선 목록 — 창룡령·관문척추·칼능선·주동릉·동종속릉 (전부 잠정). */
    public java.util.List<Ridge> skelRidges() {
        return java.util.List.of(
                new Ridge("창룡령", new double[]{4, 2, 0, -2}, new double[]{250, 180, 86, 30},
                        new double[]{10, 58, 104, 116}, 18, 10, 8, 1.5, 1.5),  // 산자락→O→관문 (x≈0 접근 능선·보행 calm·③ 노선이 딛는다)
                new Ridge("관문척추", new double[]{-2, -24}, new double[]{30, -54},
                        new double[]{116, 158}, 12, 10, 8, 1.6, 1.6),        // 관문→Pm
                new Ridge("칼능선", new double[]{-24, -104}, new double[]{-54, -16},
                        new double[]{158, 150}, 6, 6, 32, 2.2, 2.2),         // Pm→Wm 좁고 급·깊은 안부(prominence)
                new Ridge("주동릉", new double[]{-24, 62}, new double[]{-54, -46},
                        new double[]{158, 152}, 9, 9, 32, 1.7, 1.7),         // Pm→Em·깊은 안부(prominence)
                new Ridge("동종속릉", new double[]{62, 98}, new double[]{-46, -18},
                        new double[]{152, 128}, 9, 8, 14, 1.7, 1.7));        // Em→Es
    }

    /** 골격 골 간선 목록 — 4줄, 능선 사이에서 시작해 domain 밖 배수 (품출구는 남향 개구). */
    public java.util.List<Valley> skelValleys() {
        return java.util.List.of(
                new Valley("골서", new double[]{-56, -130, -235}, new double[]{-20, 4, 28}, 26, 12, 26),   // 칼능선/관문 사이 → SW 밖
                new Valley("골남동", new double[]{20, 72, 155}, new double[]{-34, 64, 180}, 26, 14, 30),   // 관문/주동릉 사이 → SE 밖
                new Valley("골동", new double[]{86, 158, 238}, new double[]{-44, -58, -60}, 22, 12, 24),   // 동봉군 동쪽 → E 밖
                new Valley("골남서", new double[]{-24, -44, -58}, new double[]{56, 150, 255}, 16, 14, 28)); // 서 앞자락 배수 → S-SW 밖 (x≈0 노선 비켜감)
    }

    /** 건물 품 — 상부 관문 남 어깨의 상부 안부 (중앙 원반 폐기 · 남 개구). 잠정. */
    public Court court() {
        return new Court(-2, 46, 118, 28, 40);
    }

    // ─── mesa 대지 — 골격이 딛는 등고 앵커 (연속성) ──────────
    /** 본산 몫 등고 = mesa 대지 높이 (base 위) = crest(honsanR). 골격 base·crag topFade 앵커 */
    public int mesaLevel() {
        return lift - honsanRise;               // 160−72 = 88 — honsanR 이음의 등고 (연속성 앵커)
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
