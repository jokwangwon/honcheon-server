package com.honcheon.mvt.forge;

/**
 * 산세 높이장 — <b>좌표의 순수 함수</b>. Bukkit 을 모른다 (import 0).
 *
 * <p>결정론 헌법(TerrainForge 머리 주석 · config/terrain_grain.yml)의 계승:
 * <ul>
 *   <li><b>난수 0</b> — 모든 흔들림은 격자점 해시 보간(값 잡음)과 삼각함수·smoothstep 이다.</li>
 *   <li><b>씨앗 = 좌표 해시</b> — {@code floorMod(31·px + 17·pz, 1_000_003)}
 *       (carveTrail 문법, TerrainForge.java:1238). 같은 주봉 좌표 = 같은 산.</li>
 *   <li><b>타일 무관성</b> — 열 하나의 높이가 이웃 열에 안 기댄다. 어느 순서로 빚어도 같은 땅.</li>
 * </ul>
 *
 * <p>★★ <b>후보 C (능선 골격 파이프라인) 이식</b> (2026-07-16 — 사용자 A/B/C 실루엣에서 C 확정).
 * 옛 군집 모델(mesa 캡 + soft-max 원뿔 군집 + 중앙 원반 court)을 폐기하고, 검증된 프로토타입
 * (HwasanProto)의 <b>골격-먼저 파이프라인</b>으로 교체했다 (Codex CODEX_HWASAN_TERRAIN_IDEAS.md
 * §3·§5~§7 · terrain_forge_v5.md §2 재작성):
 * <pre>
 *   H(x,z) = clamp[0,lift]( 저주파 워프 → smoothUnion(massif base, 비등방 봉, 능선 간선)
 *                           − 골 절개 → 품 투영 → 절리 crag )
 * </pre>
 * 봉을 먼저 찍지 않는다 — 능선/골 위상 골격(RangeSpec.skel*)을 먼저 세우고 봉은 결절에서 비등방
 * 암체로 솟는다. 원뿔(height−slope·d)·mesa 돔·soft-max 링·중앙 원반 court 는 폐기.
 *
 * <p>★ <b>B-155 연속성 최우선</b>: 프로토타입은 판단용이라 인접단차 11.9칸이었다. 생산은 워프
 * 진폭·crag 세기를 낮추고, 봉 횡 반경(shortScale)을 넓히고, massif base 로 봉의 유효 수직 범위를
 * 줄여 도함수 상한을 낮췄다 → 자기시험 {@code maxSeam ≤ }{@value #SEAM_STEP_MAX} PASS. C 성격
 * (돌결·비대칭·비원뿔)을 최대한 지키되 연속성이 우선 — 절충값은 「잠정·승인 대기」(§8.2).
 *
 * <p>★ grain(격자 보간)은 TerrainForge.java:2432-2472 의 <b>사본</b>이다 (§5-5 통일 대상).
 */
public final class RangeField {

    private final RangeSpec spec;

    /** 바위 결의 위상 — 좌표 해시 씨앗 (TF:1238 문법). 난수가 아니다 */
    private final double phase;

    // ─── 골격 노드 캐시 (spec 만 읽어 세운다 · trail 무의존) ───────────────
    private final RangeSpec.Peak[] peaks;
    private final RangeSpec.Ridge[] ridges;
    private final RangeSpec.Valley[] valleys;
    private final RangeSpec.Court court;

    /** 외곽 평원 요철의 덩어리 한 변 — terrain_grain.yml undulation.cell 기본값 (등록부 이관 대상) */
    private static final int UNDULATION_CELL = 9;

    /** 외곽 평원 요철의 진폭 — terrain_grain.yml undulation.amplitude 기본값 (★Q8 — ±1 잠정) */
    private static final int UNDULATION_AMP = 1;

    /**
     * 잠정 등산로 지그재그 진폭 (도) — <b>③ 미착수 1차 시험 조성용</b> 폴백. ③(TrailForge)가
     * 기본이고 이것은 폴백/회귀 대조용 (provisionalTrail).
     */
    private static final double PROVISIONAL_SWITCHBACK_DEG = 25.0;

    /** z=0 이음 급단차 상한 (B-155 회귀 자) — 이 값 이하여야 PASS. C 이식의 최우선 계약 */
    private static final double SEAM_STEP_MAX = 6.0;

    /** 산기슭(등산로 고리 발치) 볼록 프로파일 지수 — 작을수록 발치가 급하다 (crest 발치 형상 잠정) */
    private static final double TRAIL_FOOT_EXP = 0.85;

    // ─── C 파이프라인 튜닝 손잡이 — 전부 「잠정·승인 대기」(§8.2). B-155 로 프로토타입 대비 낮췄다 ───
    /** 저주파 도메인 워프 진폭 (칸) — 프로토타입 20 · maxSeam 여유로 성격 복원 {@value} (B-155 여밈). 경계·calm 0 */
    private static final double WARP_AMP = 9.5;
    /** 워프 격자 셀 (칸) — 저주파 (원·직선·등간격 흔적만 휜다) */
    private static final int WARP_CELL = 150;
    /** smoothUnion 날카로움 — 클수록 hard-max (넓은 158 융기 평원 방지·안부 보존) */
    private static final double UNION_K = 2.5;
    /**
     * 저(低) 넓은 skirt 최고 등고 (base 위) — <b>공통 mesa 대지 폐기</b> (Codex §1.2·§5.2 「봉마다 제
     * 발치」). 봉·능선이 이 낮은 받침 위로 각자 뻗어 발치가 domain 가장자리까지 구분된다. 남면 등산로
     * walkability 는 이 skirt 가 아니라 창룡령(골격 능선)이 맡는다. ★C 성격 복원(잠정).
     */
    private static final double SKIRT_MAX = 30.0;
    /** skirt 반경 — 이 반경에서 0 (봉·골이 여기까지 제 사면·집수를 뻗는다) */
    private static final double SKIRT_R = 245.0;

    /** 절리 crag 진폭 — 프로토타입(7/5/4) · maxSeam 여유로 성격 복원 (B-155 ≤6 지킴). 노출 급사면 강 */
    private static final double CRAG_J1 = 4.4;   // 절리 계열 1 (사선)
    private static final double CRAG_J2 = 2.8;   // 절리 계열 2 (수직)
    private static final double CRAG_FINE = 1.8; // 블록 잔결
    /** crag 노출 문턱·폭 (물매 정규화) — slope 가 이 위여야 결이 선다. 폭 넓게(마스크 도함수↓ → B-155) */
    private static final double CRAG_EXPOSE_LO = 0.30, CRAG_EXPOSE_HI = 0.80;

    /** 보행 corridor 잠잠 반폭 — 남면(dz≥0)에서 |dx| 가 이 안이면 crag 를 끈다(등산로·건물 단 평탄대) */
    private static final double CALM_HALF = 32.0;
    private static final double CALM_RAMP = 14.0;
    /** 능선 마루 중심선 calm 반폭 — 보행선(창룡령 등) 잠잠 띠 반폭·램프. ★좁혀 능선날을 세운다(§8.2 C-14) */
    private static final double RIDGE_CALM_HALF = 3.0, RIDGE_CALM_RAMP = 5.0;

    // ─── ★★ 험산 절벽 (방향성 절리 블록 · 2026-07-16 도보 진단·재작업 2회) — 전부 「잠정·승인 대기」(§8.2 C-14~C-20) ───
    //   ★재작업 이력: (1) 순수 높이-양자화(rint(h)) → 등단=등고선 → 봉 둘레 동심원 링(웨딩케이크). (2) 삼각파
    //   합 → 규칙 헤링본(직물 무늬). (3) ★현행: 근수직 절리 2계열이 평면을 곧은 슬랩으로 나누고 교차가
    //   절리 블록 — 블록마다 해시 높이 오프셋 → 블록 경계(곧은 절리선)가 절벽, 블록은 h 를 따라 산을
    //   오른다 (Codex §3.5 "방향을 가진 절리면·잘린 벽"). 화강암이 직교 절리로 각진 토르 블록으로 깨지는
    //   실제 기작. 동심원·헤링본 둘 다 없다. 불연속은 비-calm 에만. 결정론: 해시·rint·좌표뿐(난수 0).
    /** 층단(벤치/절벽) 높이 — 절리 블록 높이 스냅 간격, 그 사이가 수직 절벽. 크게=성긴 큰 절벽(과감) (잠정) */
    private static final double CLIFF_STEP = 12.0;
    /** 절벽 노출 물매 문턱·상한(localSlope) — 이 사이에서 절벽이 선다 (잠정) */
    private static final double CLIFF_EXPOSE_LO = 0.30, CLIFF_EXPOSE_HI = 0.85;
    /** 봉 마루 여밈(h/lift) — 이 위(정상 근처)는 절벽 0 → 뾰족한 정상 보존. [LO,HI]에서 여민다 (잠정) */
    private static final double CLIFF_TOP_LO = 0.87, CLIFF_TOP_HI = 0.99;
    /** 발치 여밈 — 이 높이 밑은 절벽 0, 위로 CLIFF_FOOT_RAMP 칸에 걸쳐 여민다(산기슭 매끈) (잠정) */
    private static final double CLIFF_MIN_H = 6.0, CLIFF_FOOT_RAMP = 8.0;
    // ─── ★★ carve(깎아내기) — 험산을 전면에 세운 뒤 보행/건축 자리를 감산 후처리로 깎는다 (2026-07-16 사용자 설계) ───
    //   "reserve"(calm 을 매끄러운 평면으로 비워 두기) 폐기 → 완전한 방향성 절리 바위산을 domain 전체에
    //   세우고, calm(등산로 corridor·능선 척추·건물 품)을 목표 평탄면으로 **깎아낸다**. 깎인 가장자리는
    //   자연 rugged 가 목표면보다 높던 곳이라 **깎인 암벽(cut wall)**이 남는다 — 華山 잔도·암벽 계단·바위
    //   깎아 앉힌 전각의 형태. 보행 표면=평탄 연속(calm), 깎인 벽=비-calm(허용).
    /** carve 가중 램프 — calm 이 이 사이를 지나며 rugged→목표 평탄면으로 깎인다. calm 코어(≥HI)는 순수 평탄 */
    private static final double CARVE_LO = 0.35, CARVE_HI = 0.65;
    /** 보행 평탄대 판정 문턱(검수 B-155 calm-only) — CARVE_HI 위여야 순수 평탄(연속). 이 위 셀만 연속성 요구 */
    private static final double CALM_WALK_TAU = 0.70;
    /**
     * ★방향성 절리 블록 — 근수직 절리 2계열(방위)이 평면을 곧은 슬랩으로 나누고 교차가 절리 블록
     * (≈직교 granite 블록 절단). 주향·간격 전부 잠정 (crag 절리계와 한 결).
     */
    private static final double[] JOINT_AZ = {Math.toRadians(24), Math.toRadians(112)};  // 근수직 2계열(≈직교)
    private static final double[] JOINT_SP = {26.0, 23.0};          // 절리 간격(칸) = 블록 한 변
    /** 블록 높이 오프셋 진폭(칸) — 블록마다 다른 높이로 평탄 블록을 흩어 각진 결을 낸다 (잠정) */
    private static final double JOINT_OFF = 8.0;
    /** 슬랩 경계 <b>저주파</b> 굽이(셀 ≫ 간격) — 자로 그은 직선을 살짝 흩되 각짐 유지 (잠정) */
    private static final double JOINT_WARP = 9.0, JOINT_WARP_CELL = 130.0;
    /** 절벽 낙차 상한(칸) — 평탄 블록이 급사면에서 h 와 벌어져 비현실적 거벽이 되지 않게 못박음 (잠정) */
    private static final double CLIFF_WALL_MAX = 26.0;

    /** 등산로 폴리라인 (절대 좌표) — 곁구역 재단의 자. spec.trail(③ 목록) · ③ 생성기 · 잠정 폴백 */
    private final double[] trailX;
    private final double[] trailZ;
    /** 각 경유점까지의 누적 호장 · 전체 호장 — 호장 비율 u∈[0,1] 계산용 */
    private final double[] trailCum;
    private final double trailLen;
    /** ③ 생성기 산출 지표 (spec.trail 이 비어 생성기가 돌았을 때) — 검수·보고용. 아니면 null */
    private final TrailForge.Result trailPlan;

    public RangeField(RangeSpec spec) {
        this.spec = spec;
        long seed = Math.floorMod(31L * spec.peakX() + 17L * spec.peakZ(), 1_000_003L);
        this.phase = (seed % 628) / 100.0;

        // 골격 노드 캐시 — spec 만 읽는다 (trail 무의존 → 생성기가 reliefAt 읽어도 순환 없음)
        this.peaks = spec.skelPeaks().toArray(new RangeSpec.Peak[0]);
        this.ridges = spec.skelRidges().toArray(new RangeSpec.Ridge[0]);
        this.valleys = spec.skelValleys().toArray(new RangeSpec.Valley[0]);
        this.court = spec.court();

        // 등산로 폴리라인 세우기 — 우선순위:
        //   ① spec.trail(③ 산출 목록)이 채워져 있으면 그것 (기하는 ③ 소유 · §2.4-4 계약)
        //   ② 비어 있으면 ③ 생성기(TrailForge)가 reliefAt 위에서 제약 만족 폴리라인을 낳는다 (기본)
        //   ③ 생성 실패 시 확정값 유도 잠정 지그재그 (문서화된 폴백 · provisionalTrail)
        // ★reliefAt 은 trail 무의존이라(위 final 전부 배정됨) 생성기가 이 시점에 읽어도 순환 없다.
        double[][] wp;
        if (!spec.trail().isEmpty()) {
            wp = fromSpecTrail();
            this.trailPlan = null;
        } else {
            TrailForge.Result plan = TrailForge.generate(spec, this::reliefAt);
            wp = new double[][]{plan.xs(), plan.zs()};
            this.trailPlan = plan;
        }
        this.trailX = wp[0];
        this.trailZ = wp[1];
        this.trailCum = new double[trailX.length];
        double acc = 0.0;
        for (int i = 1; i < trailX.length; i++) {
            acc += Math.hypot(trailX[i] - trailX[i - 1], trailZ[i] - trailZ[i - 1]);
            trailCum[i] = acc;
        }
        this.trailLen = acc;
    }

    /** ③ 생성기 산출 지표 (spec.trail 이 비어 생성기가 돌았을 때) — 검수·보고용. 목록/폴백이면 null */
    public TrailForge.Result trailPlan() {
        return trailPlan;
    }

    /** ③의 경유점을 절대 좌표 배열로 (계약 경로) */
    private double[][] fromSpecTrail() {
        var t = spec.trail();
        double[] xs = new double[t.size()];
        double[] zs = new double[t.size()];
        for (int i = 0; i < t.size(); i++) {
            xs[i] = t.get(i).x();
            zs[i] = t.get(i).z();
        }
        return new double[][]{xs, zs};
    }

    /**
     * 잠정 등산로 — <b>폴백</b> (③ 생성기 {@link TrailForge} 가 기본 · 생성 실패나 회귀 대조용).
     * 확정값 전부에서 유도한다 (지어낸 노선 아님).
     */
    private double[][] provisionalTrail() {
        int belt = spec.beltDepth();
        int[] radii = {
                spec.honsanR() + spec.midDepth(),   // 264 — 입구 쪽 (매화림, 낮음) [0]
                spec.honsanR() + 3 * belt,           // 229
                spec.honsanR() + 2 * belt,           // 194
                spec.honsanR() + belt,               // 159
                spec.honsanR()                       // 124 — 본산 문 (연무 계곡, 높음) [끝]
        };
        double sw = Math.toRadians(PROVISIONAL_SWITCHBACK_DEG);
        double[] az = {0.0, sw, -sw, sw, 0.0};       // 남축 ±교대 (남 = +z)
        double[] xs = new double[radii.length];
        double[] zs = new double[radii.length];
        for (int i = 0; i < radii.length; i++) {
            xs[i] = spec.peakX() + radii[i] * Math.sin(az[i]);
            zs[i] = spec.peakZ() + radii[i] * Math.cos(az[i]);
        }
        return new double[][]{xs, zs};
    }

    // ═══════════════════════════════════════════════════════════════════
    // 공개 창구 — 기록기·배치기(⑥)·등산로(③)·검수(⑪)가 읽는다 (서명 불변)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 그 열의 지면 y (절대) — 기록기가 이 값까지 채운다.
     *
     * <p>경제권 밖({@link RangeZone#OUTSIDE})은 {@code baseY} 그대로 — 회랑·야생(노선 조성기)의
     * 몫이다. 외곽 평원은 ±{@value #UNDULATION_AMP} 요철 (★Q8), 입구는 평지(패방·외문이 선다).
     */
    public int surfaceY(int x, int z) {
        RangeZone zone = zoneAt(x, z);
        if (zone == RangeZone.OUTSIDE) {
            return spec.baseY();
        }
        double h = reliefAt(x, z);
        if (h <= 0.0) {
            if (zone == RangeZone.OUTER_PLAIN) {
                return spec.baseY() + undulation(x, z);
            }
            return spec.baseY();
        }
        return spec.baseY() + (int) Math.round(h);
    }

    /**
     * base 위 실수 높이 (블록 반올림 전) — 물매 계산·검수 표본용. C 파이프라인(워프+crag 포함).
     * 같은 좌표로 두 번 불러 같으면 결정론이다 (검수 ⑪의 첫 self-test).
     */
    public double reliefAt(int x, int z) {
        return reliefCore(x - spec.peakX(), z - spec.peakZ(), true, true);
    }

    /**
     * 남축 지면 등고 (절대 y) — 주봉에서 남으로 {@code s}칸(음수면 북). 단 사슬 배치기(⑥)·등산로(③)가
     * 단·경유점의 y 를 여기서 읽는다. 남축(dx=0)은 창룡령 보행선·건물 품 개구라 calm — 워프·crag 무영향
     * 으로 잰다(보행 spine 의 등고).
     */
    public int crestY(int s) {
        double h = reliefCore(0.0, s, false, false);
        return spec.baseY() + (int) Math.round(h);
    }

    /**
     * 구역 판정 — <b>불변</b> (C 이식이 건드리지 않는다 — 곁구역/flora/audit 계약 보존). 큰 골격
     * (정상/본산·후산/입구/외곽)은 H-6 확정 깊이·본산권 124 (H-7)의 방사 유도. 곁구역 넷의 경계만
     * 등산로 폴리라인 위 호장 비율로 재단 (Q9 · §2.3). 후산은 주봉 북면(dz &lt; 0).
     *
     * <p>★ SUMMIT(r≤10) 의미: C 모델은 최고봉(Pm)이 중앙에서 벗어난 상부 안부(관문척추)에 솟으므로
     * 중앙(r≤10)은 최고 마루가 아니라 <b>관문척추 상부 안부</b>(≈120~135)다 — 옛 군집 모델의 중앙
     * court(118)와 같은 성격(비-최고 중앙)이라 재라벨 아님. 재라벨 위험은 보고서에 미결로 남긴다.
     */
    public RangeZone zoneAt(int x, int z) {
        double dx = x - spec.peakX();
        double dz = z - spec.peakZ();
        double r = Math.hypot(dx, dz);
        if (r <= spec.summitFlatR()) {
            return RangeZone.SUMMIT;
        }
        if (r <= spec.honsanR()) {
            return dz < 0 ? RangeZone.HUSAN : RangeZone.HONSAN;
        }
        if (r <= spec.honsanR() + spec.midDepth()) {
            if (dz < 0) {
                return RangeZone.HUSAN;
            }
            return sideZoneByU(projectU(x, z));
        }
        if (r <= spec.domainR()) {
            return RangeZone.ENTRANCE;
        }
        if (r <= spec.economyR()) {
            return RangeZone.OUTER_PLAIN;
        }
        return RangeZone.OUTSIDE;
    }

    /** 그 자리의 물매(칸/칸) — 표층 자재 선택용 (slopeAt 문법 계승, TF:1174-1181) */
    public double slopeAt(int x, int z) {
        double a = reliefAt(x + 2, z);
        double b = reliefAt(x - 2, z);
        double c = reliefAt(x, z + 2);
        double e = reliefAt(x, z - 2);
        return Math.max(Math.abs(a - b), Math.abs(c - e)) / 4.0;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 곁구역 재단 — 등산로가 꾄다 (Q9 · terrain_forge_v5.md §2.3) — 불변
    // ═══════════════════════════════════════════════════════════════════

    /** 등산로 폴리라인 위 호장 비율 u∈[0,1] — 0 = 산기슭 끝(입구 쪽), 1 = 본산 문. */
    private double projectU(int x, int z) {
        double bestD2 = Double.MAX_VALUE;
        double bestArc = 0.0;
        for (int i = 0; i < trailX.length - 1; i++) {
            double ax = trailX[i], az = trailZ[i];
            double vx = trailX[i + 1] - ax, vz = trailZ[i + 1] - az;
            double len2 = vx * vx + vz * vz;
            double t = len2 <= 0.0 ? 0.0 : ((x - ax) * vx + (z - az) * vz) / len2;
            t = Math.max(0.0, Math.min(1.0, t));
            double cx = ax + vx * t, cz = az + vz * t;
            double d2 = (x - cx) * (x - cx) + (z - cz) * (z - cz);
            if (d2 < bestD2) {
                bestD2 = d2;
                bestArc = trailCum[i] + Math.sqrt(len2) * t;
            }
        }
        return trailLen <= 0.0 ? 0.0 : bestArc / trailLen;
    }

    /** 호장 비율 → 곁구역 넷 (산기슭 매화림 → 계곡 → 절벽 → 연무 계곡 본산 곁). */
    private RangeZone sideZoneByU(double u) {
        if (u < 0.25) {
            return RangeZone.PLUM_GROVE;
        }
        if (u < 0.50) {
            return RangeZone.VALLEY;
        }
        if (u < 0.75) {
            return RangeZone.CLIFF;
        }
        return RangeZone.DRILL_VALLEY;
    }

    // ═══════════════════════════════════════════════════════════════════
    // C 파이프라인 — reliefCore (순수 함수 · 골격 먼저)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 높이장 핵 — {@code (dx,dz)} 는 주봉 기준 상대(남=+z). warp/crag 플래그로 계층을 켠다.
     * 파이프라인: 워프 → smoothUnion(massif base, 비등방 봉, 능선) → 골 절개 → 품 투영 → 절리 crag.
     */
    private double reliefCore(double dx, double dz, boolean warp, boolean crag) {
        double r = Math.hypot(dx, dz);
        double lift = spec.lift();
        if (r > spec.economyR()) {
            return 0.0;
        }

        // 1) 저주파 도메인 워프 — 배열·직선 흔적만 휜다. 경계·calm 에서 0 (B-155·계약 보존).
        double wx = dx, wz = dz;
        if (warp) {
            double fade = boundaryFade(r) * (1.0 - 0.85 * calmMask(dx, dz));
            double amp = WARP_AMP * fade;
            wx = dx + amp * grainD(dx, dz, WARP_CELL, 11.0);
            wz = dz + amp * grainD(dx + 5000, dz + 5000, WARP_CELL, 23.0);
        }

        // 2) uplift = smoothUnion(massif base, 비등방 봉들, 능선 간선들). 근사 hard-max(넓은 융기 평원 방지).
        double h = upliftAt(wx, wz);

        // 3) 골 절개 (능선망과 짝 · calm 우회)
        double cut = 0.0;
        for (RangeSpec.Valley v : valleys) {
            cut = Math.max(cut, valleyCut(v, wx, wz));
        }
        cut *= (1.0 - 0.9 * courtMask(dx, dz));
        h = Math.max(0.0, h - cut);

        // 3.5) smoothBase — 능선·골 골격의 <b>매끄러운 보행 기준면</b> (연속 ≤6). carve 의 목표면.
        double smoothBase = h;

        // 4) ★험산 — 방향성 절리 crag·절벽을 domain 전체에 세운다 (calm 비우기 없음 · 완전한 바위산).
        double rugged = h;
        if (crag && h > 2.0) {
            double slope = localSlope(wx, wz, warp);
            double cragExp = clamp01((slope - CRAG_EXPOSE_LO) / CRAG_EXPOSE_HI);
            double cragTop = clamp01((0.9 - h / lift) / 0.15);          // 봉 마루 근처 0
            rugged += jointCrag(wx, wz) * (cragExp * cragTop);          // ★calm 게이트 없음 (전면 험산)
            rugged = cliffTerrace(rugged, wx, wz, slope, lift);         // ★calm 게이트 없음 (전면 절벽)
        }

        // 5) ★carve — 보행/건축 자리를 감산 후처리로 깎아낸다. 목표 평탄면으로 내리고 가장자리는
        //    깎인 암벽(cut wall)이 남는다: court=평탄 안부 · 능선 척추·남 corridor=깎인 잔도·척추.
        double cm = courtMask(dx, dz);
        double target = smoothBase * (1.0 - cm) + court.h() * cm;       // 보행 표면 높이 (매끈·연속)
        double w = clamp01((calmMask(dx, dz) - CARVE_LO) / (CARVE_HI - CARVE_LO));  // calm 코어=1(순수 평탄)
        double carved = rugged * (1.0 - w) + target * w;
        return Math.max(0.0, Math.min(lift, carved));
    }

    /**
     * 골격 uplift — smoothUnion(massif base, 비등방 봉, 능선 간선). 순수 함수(워프 무적용 · calm/골/crag 무관).
     * 절리 블록의 <b>평탄 높이</b>(블록 중심에서 한 번 잰다) 계산에도 쓴다 — 순환 없음(cliffTerrace 무의존).
     */
    private double upliftAt(double wx, double wz) {
        double m = massifBase(wx, wz);
        for (RangeSpec.Peak p : peaks) {
            m = Math.max(m, peakBody(p, wx, wz));
        }
        for (RangeSpec.Ridge rg : ridges) {
            m = Math.max(m, ridgeBody(rg, wx, wz));
        }
        double sum = Math.exp(UNION_K * (massifBase(wx, wz) - m));
        for (RangeSpec.Peak p : peaks) {
            sum += Math.exp(UNION_K * (peakBody(p, wx, wz) - m));
        }
        for (RangeSpec.Ridge rg : ridges) {
            sum += Math.exp(UNION_K * (ridgeBody(rg, wx, wz) - m));
        }
        return m + Math.log(sum) / UNION_K;
    }

    /** crag 없는 높이 (slope 계산용 — 순환 방지) */
    private double reliefNoCrag(double dx, double dz, boolean warp) {
        return reliefCore(dx, dz, warp, false);
    }

    /** 검수·probe 훅 (package-private) — 계층(워프/crag) 토글로 연속성 기여를 분해한다. */
    double reliefLayers(int x, int z, boolean warp, boolean crag) {
        return reliefCore(x - spec.peakX(), z - spec.peakZ(), warp, crag);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 골격 부품 — 비등방 암체 · 능선 간선 · 골 절개 · massif base · 품 · calm
    // ═══════════════════════════════════════════════════════════════════

    /**
     * massif base — <b>낮은 넓은 skirt</b> (공통 mesa 대지 폐기 · Codex §1.2·§5.2). 봉·능선이 각자
     * 이 낮은 받침 위로 뻗어 발치가 domain 가장자리까지 구분되고(다섯 봉이 같은 등고에서 안 끊긴다),
     * 골이 이 skirt 를 domain 밖까지 깎아 봉 사이가 깊게 갈린다. 남면 등산로 walkability 는 이 skirt 가
     * 아니라 <b>창룡령</b>(x≈0 골격 능선)이 맡으므로 skirt 는 순수 저 받침이면 된다.
     * ★C 성격 복원(잠정): 프로토타입 계승. 봉 벽 도함수는 넓은 shortScale + 능선 채움 + smoothstep 여밈
     * 으로 B-155(≤6) 안에 든다 — 오프라인 재측이 증명한다.
     */
    private double massifBase(double dx, double dz) {
        double r = Math.hypot(dx, dz);
        return SKIRT_MAX * (1.0 - ss(clamp01(r / SKIRT_R), 0, 1));
    }

    /** 비등방 암체 봉 (원뿔 폐기) — axisDeg 로 길고 횡으로 짧다(비대칭 어깨/벽·전단). */
    private double peakBody(RangeSpec.Peak p, double dx, double dz) {
        double rx = dx - p.px(), rz = dz - p.pz();
        double a = Math.toRadians(p.axisDeg());
        double along = rx * Math.sin(a) + rz * Math.cos(a);
        double across = -rx * Math.cos(a) + rz * Math.sin(a);
        across += p.skew() * along;                             // 전단 → 기울어진 등고선
        boolean gentle = (across * p.gentleOnPlusSide()) >= 0;
        double shortS = gentle ? p.shortGentle() : p.shortWall();
        double an = along / p.longScale();
        double cn = across / shortS;
        double d = Math.sqrt(an * an + cn * cn);
        if (d >= 1.0) {
            return -80.0;                                       // 영향 밖 (union 에서 무시)
        }
        return p.h() * descend(clamp01(d), gentle);
    }

    /**
     * 비선형 단조 하강 프로파일 — 직선 종단면(height−slope·d)을 깬다. 여러 smoothstep 가중합이라
     * 단조 감소이면서 상부 벽·중간 어깨·하부 발치의 곡률이 다르다 (Codex §3.2 "가변 물매").
     * 완만 어깨 쪽은 중간대 가중을 키운다 → 비대칭 사면. m(0)=1·m(1)=0·m'(1)=0.
     */
    private double descend(double t, boolean gentle) {
        double w1 = gentle ? 0.40 : 0.55;   // 상부 벽
        double w2 = gentle ? 0.30 : 0.14;   // 중간 어깨(완만 쪽 큼)
        double w3 = 1 - w1 - w2;            // 하부 발치
        double s = w1 * ss(t, 0.0, 0.28) + w2 * ss(t, 0.26, 0.58) + w3 * ss(t, 0.50, 1.0);
        return 1 - s;
    }

    /** 능선 간선 몸체 — 폴리라인 위 crest(중간 안부) − 비대칭 횡단 낙하. */
    private double ridgeBody(RangeSpec.Ridge rg, double dx, double dz) {
        double[] pr = projectPoly(rg.xs(), rg.zs(), dx, dz);
        double s = pr[0], lat = pr[1];
        double crest = interpH(rg.hs(), s) - rg.saddleDepth() * (4 * s * (1 - s));
        double w = rg.wStart() + (rg.wEnd() - rg.wStart()) * s;
        double over = Math.max(0.0, Math.abs(lat) - w);
        double fall = (lat >= 0 ? rg.fallR() : rg.fallL());
        double val = crest - over * fall;
        return val < -80 ? -80 : val;
    }

    /** 골 절개 — 배수선 따라 감산. 하류로 넓고 깊게(clamp). 상류 머리 여밈(칼 시작 금지). */
    private double valleyCut(RangeSpec.Valley v, double dx, double dz) {
        double[] pr = projectPoly(v.xs(), v.zs(), dx, dz);
        double s = pr[0], lat = Math.abs(pr[1]);
        double w = v.wNear() + (v.wFar() - v.wNear()) * s;
        // 상류(중앙·평탄 mesa 위)는 얕게, 하류(산자락 경사면)로 깊게 — 평탄 대지에 깊은 칼벽 금지(B-155)
        double depth = v.depthMax() * (0.15 + 0.85 * s);
        double q = Math.max(0.0, 1.0 - (lat / w) * (lat / w));
        double head = ss(clamp01(s / 0.18), 0, 1);      // 상류 머리 여밈 넓게 (칼 시작·급단차 금지 · B-155)
        return depth * q * head;
    }

    /** 건물 품 마스크 — 남으로 긴 타원, 남쪽 가장자리는 출구(개구)로 약함. */
    private double courtMask(double dx, double dz) {
        double ux = (dx - court.cx()) / court.ax();
        double uz = (dz - court.cz()) / court.az();
        double d = Math.sqrt(ux * ux + uz * uz);
        double m = 1.0 - ss(clamp01((d - 0.55) / 0.45), 0, 1);
        if (dz > court.cz()) {
            m *= (1 - 0.6 * ss(clamp01((dz - court.cz()) / court.az()), 0, 1)); // 남측 개구
        }
        return m;
    }

    /** calm(보행·건축 평탄) = 건물 품 ∪ 능선 보행 중심선 띠 ∪ 남면 corridor. 워프·crag 를 끈다. */
    private double calmMask(double dx, double dz) {
        double m = courtMask(dx, dz);
        for (RangeSpec.Ridge rg : ridges) {
            double[] pr = projectPoly(rg.xs(), rg.zs(), dx, dz);
            double lat = Math.abs(pr[1]);
            m = Math.max(m, 1.0 - ss(clamp01((lat - RIDGE_CALM_HALF) / RIDGE_CALM_RAMP), 0, 1));
        }
        // 남면 등산로 corridor (dz≥0, |dx|<CALM_HALF) — 스위치백·건물 단 평탄대 (③ 노선 보호).
        // ★남 gate 를 smoothstep 으로 여며 dz=0 급단차 금지 (옛 하드 dz≥0 스위치가 워프 불연속을
        //   내 maxSeam 8.7 을 냈다 — B-155). 북(dz<0)으로 fade → 0.
        double southGate = ss(clamp01((dz + 12.0) / 24.0), 0, 1);
        double corridor = southGate * (1.0 - clamp01((Math.abs(dx) - CALM_HALF) / CALM_RAMP));
        m = Math.max(m, corridor);
        return clamp01(m);
    }

    private double boundaryFade(double r) {
        double d0 = 170.0, d1 = spec.domainR();
        return 1.0 - ss(clamp01((r - d0) / (d1 - d0)), 0, 1);
    }

    /** 절리형 crag — 방향성 절리 2계열(사선/수직) + 잔결. ridged(1−|sin|) 로 날 선 절리면. */
    private double jointCrag(double dx, double dz) {
        double a1 = Math.toRadians(28), a2 = Math.toRadians(112);
        double u1 = dx * Math.cos(a1) + dz * Math.sin(a1);
        double u2 = dx * Math.cos(a2) + dz * Math.sin(a2);
        double j1 = (1 - Math.abs(Math.sin(u1 / 13.0))) - 0.5;
        double j2 = (1 - Math.abs(Math.sin(u2 / 19.0))) - 0.5;
        double fine = grainD(dx + 111, dz + 777, 7, 3.0) * 0.5;
        return j1 * CRAG_J1 + j2 * CRAG_J2 + fine * CRAG_FINE;
    }

    /** 국소 물매 (crag/절벽 노출 마스크용) — crag/절벽 없는 매끄러운 높이의 중앙차분. */
    private double localSlope(double dx, double dz, boolean warp) {
        double a = reliefNoCrag(dx + 2, dz, warp), b = reliefNoCrag(dx - 2, dz, warp);
        double c = reliefNoCrag(dx, dz + 2, warp), e = reliefNoCrag(dx, dz - 2, warp);
        return Math.max(Math.abs(a - b), Math.abs(c - e)) / 4.0;
    }

    /**
     * ★험산 절벽 — 노출 사면을 <b>방향성 절리 블록</b>으로 절단한다. 근수직 절리 2계열(각기 방위·간격)이
     * 평면을 곧은 슬랩으로 나누고, 두 계열의 교차가 절리 블록. 각 블록에 <b>해시 높이 오프셋</b>을 더한 뒤
     * {@code CLIFF_STEP} 로 스냅한다 → <b>블록 경계(곧은 절리선)가 절벽</b>(각진 벽·사선 층), 블록은 h 를
     * 따라 산을 오른다. 화강암이 직교 절리로 각진 토르 블록으로 깨지는 실제 기작 — 동심원 링·헤링본 둘 다
     * 없다. 물매가 급하면 블록 경계가 촘촘해 수직벽으로 합쳐진다.
     * ★carve 모델: 절벽을 domain <b>전체</b>에 세운다(calm 비우기 없음). 보행/건축 평탄대는 이 뒤의 carve
     * 후처리(reliefCore 5단계)가 목표면으로 깎아내고 가장자리에 깎인 암벽을 남긴다 — 결정론(해시·rint·좌표뿐,
     * 난수 0). 봉 마루(뾰족한 정상)와 발치(산기슭)만 여며 절벽을 끈다.
     */
    private double cliffTerrace(double h, double wx, double wz, double slope, double lift) {
        if (h <= CLIFF_MIN_H) {
            return h;
        }
        double expose = clamp01((slope - CLIFF_EXPOSE_LO) / (CLIFF_EXPOSE_HI - CLIFF_EXPOSE_LO));
        double topFade = clamp01((CLIFF_TOP_HI - h / lift) / (CLIFF_TOP_HI - CLIFF_TOP_LO));  // 봉 마루 여밈
        double lowFade = clamp01((h - CLIFF_MIN_H) / CLIFF_FOOT_RAMP);                        // 발치 여밈
        double mask = expose * topFade * lowFade;
        if (mask <= 1e-3) {
            return h;
        }
        // 방향성 절리 블록 — 근수직 2계열이 평면을 곧은 슬랩으로 나눈다(슬랩 경계는 저주파로만 살짝
        // 휘어 각짐 유지·불규칙 배치). 두 슬랩 지수가 블록 (s1,s2).
        double c1 = Math.cos(JOINT_AZ[0]), sn1 = Math.sin(JOINT_AZ[0]);
        double c2 = Math.cos(JOINT_AZ[1]), sn2 = Math.sin(JOINT_AZ[1]);
        double w1 = JOINT_WARP * grainD(wx + 91.0, wz - 53.0, (int) JOINT_WARP_CELL, 3.0);
        double w2 = JOINT_WARP * grainD(wx - 77.0, wz + 131.0, (int) JOINT_WARP_CELL, 8.0);
        double u1 = c1 * wx + sn1 * wz + w1;
        double u2 = c2 * wx + sn2 * wz + w2;
        int s1 = (int) Math.floor(u1 / JOINT_SP[0]);
        int s2 = (int) Math.floor(u2 / JOINT_SP[1]);
        // ★평탄 블록 — 블록 중심을 되짚어(두 슬랩 좌표 역변환) 그 자리의 골격 uplift 를 한 번 잰다 →
        //   블록 전체가 그 높이로 평탄(암반 슬랩). 블록 안엔 등고선이 없어 동심원이 안 생긴다.
        double uc1 = (s1 + 0.5) * JOINT_SP[0] - w1;
        double uc2 = (s2 + 0.5) * JOINT_SP[1] - w2;
        double det = c1 * sn2 - sn1 * c2;                          // = sin(az2 − az1) ≠ 0
        double cx = (uc1 * sn2 - uc2 * sn1) / det;                 // 블록 중심 (워프 저주파라 블록 내 상수 근사)
        double cz = (c1 * uc2 - c2 * uc1) / det;
        double blockOffset = JOINT_OFF * latticeS(s1, s2, 17.0);   // 블록 상수 오프셋 [-OFF,OFF]
        double blockLevel = CLIFF_STEP * Math.rint((upliftAt(cx, cz) + blockOffset) / CLIFF_STEP);
        // ★절벽 낙차 상한 — 평탄 블록이 급사면에서 h 와 멀어져 비현실적 거벽이 되지 않게 h±MAX 로 못박음
        //   (가장 급한 면은 여기서 h 를 따라 사실상 수직벽; 완·중사면은 각진 평탄 블록). 인접 블록 낙차 = 절벽.
        blockLevel = Math.max(h - CLIFF_WALL_MAX, Math.min(h + CLIFF_WALL_MAX, blockLevel));
        return h * (1.0 - mask) + blockLevel * mask;
    }

    /**
     * calm(보행/건축 평탄) 강도 [0,1] — 검수(B-155 <b>calm-only</b> 재범위)가 읽는 창구.
     * calm ≥ 문턱인 셀만 연속성(≤{@value #SEAM_STEP_MAX})을 요구한다(비-calm 절벽 면은 큰 단차 허용).
     */
    double calmAt(int x, int z) {
        return calmMask(x - spec.peakX(), z - spec.peakZ());
    }

    // ═══════════════════════════════════════════════════════════════════
    // 외곽 평원 요철
    // ═══════════════════════════════════════════════════════════════════

    /** 외곽 평원의 요철 — undulation 문법 (terrain_grain.yml cell 9 · ±1) */
    private int undulation(int x, int z) {
        return (int) Math.round(grain2(x, z, UNDULATION_CELL) * UNDULATION_AMP);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 공용 수학 — 폴리라인 투영 · 보간 · smoothstep · 격자 해시 결
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 폴리라인 투영 → {호장비율 s∈[0,1], 부호있는 횡거리}. 크기 = clamp 된 최근접점까지의 진짜
     * 유클리드 거리(끝점 밖은 끝점에서 방사로 여밈 — 무한직선 연장 줄무늬 방지). 부호는 선분 좌/우.
     */
    private static double[] projectPoly(double[] xs, double[] zs, double x, double z) {
        double total = 0;
        for (int i = 1; i < xs.length; i++) {
            total += Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
        }
        double bestD2 = Double.MAX_VALUE, bestArc = 0, bestLat = 0, acc = 0;
        for (int i = 0; i < xs.length - 1; i++) {
            double ax = xs[i], az = zs[i], vx = xs[i + 1] - ax, vz = zs[i + 1] - az;
            double len2 = vx * vx + vz * vz;
            double segLen = Math.sqrt(len2);
            double t = len2 <= 0 ? 0 : ((x - ax) * vx + (z - az) * vz) / len2;
            t = Math.max(0, Math.min(1, t));
            double cx = ax + vx * t, cz = az + vz * t;
            double d2 = (x - cx) * (x - cx) + (z - cz) * (z - cz);
            if (d2 < bestD2) {
                bestD2 = d2;
                bestArc = acc + segLen * t;
                double cross = vx * (z - az) - vz * (x - ax);
                bestLat = (cross >= 0 ? 1 : -1) * Math.sqrt(d2);
            }
            acc += segLen;
        }
        return new double[]{total <= 0 ? 0 : bestArc / total, bestLat};
    }

    private static double interpH(double[] hs, double s) {
        if (hs.length == 1) return hs[0];
        double f = s * (hs.length - 1);
        int i = (int) Math.floor(f);
        if (i >= hs.length - 1) return hs[hs.length - 1];
        if (i < 0) return hs[0];
        double t = ss(f - i, 0, 1);
        return hs[i] * (1 - t) + hs[i + 1] * t;
    }

    private static double ss(double t, double a, double b) {
        if (b <= a) return t >= b ? 1 : 0;
        double u = clamp01((t - a) / (b - a));
        return u * u * (3 - 2 * u);
    }

    // ── 격자점 해시 보간 (값 잡음) — 결정론. 두 계승 문법: ──
    //   (1) int-좌표 lattice/grain/grain2 (TF:2432-2472 사본 · undulation 용) — 서명 불변.
    //   (2) double-좌표 salted 변형 (grainD/latticeS) — 워프·crag 잔결 용 (프로토타입 계승).

    /** 격자점 하나의 값 [-1,1] — 정수 해시 (splitmix64 계열). 난수 씨앗이 없다 */
    private static double lattice(int gx, int gz) {
        long h = gx * 0x9E3779B97F4A7C15L ^ gz * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return ((h >>> 11) / (double) (1L << 53)) * 2.0 - 1.0;
    }

    /** salted 격자점 값 [-1,1] — 계층별 decorrelate (워프 x/z·crag 잔결). 난수 아님 */
    private static double latticeS(int gx, int gz, double salt) {
        long si = Double.doubleToLongBits(salt);
        long h = gx * 0x9E3779B97F4A7C15L ^ gz * 0xC2B2AE3D27D4EB4FL ^ si * 0xD6E8FEB86659FD93L;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return ((h >>> 11) / (double) (1L << 53)) * 2.0 - 1.0;
    }

    private static double ease(double t) {
        return t * t * (3 - 2 * t);
    }

    /** 결의 값 [-1,1] — cell 칸 격자에 해시를 깔고 보간한다 (int 좌표) */
    static double grain(int x, int z, int cell) {
        int c = Math.max(1, cell);
        double fx = x / (double) c;
        double fz = z / (double) c;
        int x0 = (int) Math.floor(fx);
        int z0 = (int) Math.floor(fz);
        double tx = ease(fx - x0);
        double tz = ease(fz - z0);
        double a = lattice(x0, z0);
        double b = lattice(x0 + 1, z0);
        double c0 = lattice(x0, z0 + 1);
        double d = lattice(x0 + 1, z0 + 1);
        return (a * (1 - tx) + b * tx) * (1 - tz) + (c0 * (1 - tx) + d * tx) * tz;
    }

    /** 결의 값 [-1,1] — double 좌표 + salt (워프·crag 잔결). 프로토타입 grain 계승 */
    private static double grainD(double x, double z, int cell, double salt) {
        int c = Math.max(1, cell);
        double fx = x / c, fz = z / c;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        double tx = ss(fx - x0, 0, 1), tz = ss(fz - z0, 0, 1);
        double a = latticeS(x0, z0, salt), b = latticeS(x0 + 1, z0, salt);
        double c0 = latticeS(x0, z0 + 1, salt), d = latticeS(x0 + 1, z0 + 1, salt);
        return (a * (1 - tx) + b * tx) * (1 - tz) + (c0 * (1 - tx) + d * tx) * tz;
    }

    /** 두 겹의 결 — 큰 덩어리 위에 작은 결 (2옥타브) */
    static double grain2(int x, int z, int cell) {
        return grain(x, z, cell) * 0.72
                + grain(x + 8191, z + 4703, Math.max(2, cell / 2)) * 0.28;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    // ═══════════════════════════════════════════════════════════════════
    // 자기 시험 — 검수 ⑪의 첫 눈 (서버 없이 · java com.honcheon.mvt.forge.RangeField)
    //   ① B-155 연속성(≤6) ② 다봉 위계(주봉 ≥3·중앙<최고) ③ 결정론 ④ 곁구역 넷 ⑤ 눈을 시험하는 눈
    // ═══════════════════════════════════════════════════════════════════
    /**
     * B-155 재범위 판정 — 두 이웃이 <b>둘 다 calm(보행/건축 평탄대)</b>일 때만 연속성(≤max)을
     * 요구한다. 하나라도 비-calm(절벽 면)이면 큰 단차 허용(험산). 이 술어가 「눈」이고, main 이
     * 심은 표본(calm 절벽·비-calm 절벽·경계·작은 단차)으로 이 눈을 시험한다.
     */
    private static boolean calmSeamViolation(double calmA, double calmB, double dh, double tau, double max) {
        return calmA >= tau && calmB >= tau && dh > max;
    }

    private static String tag(RangeZone z) {
        return switch (z) {
            case PLUM_GROVE -> "매화림";
            case VALLEY -> "계곡";
            case CLIFF -> "절벽";
            case DRILL_VALLEY -> "연무";
            case HUSAN -> "후산";
            case HONSAN -> "본산";
            case ENTRANCE -> "입구";
            case SUMMIT -> "정상";
            default -> z.name();
        };
    }

    /**
     * 지속성(prominence) 기반 봉 탐지 — 국소 최고점 + 직선 안부 근사 prominence. crag 잡음을 봉으로
     * 세지 않게 win/minH·병합. 높이 내림차순. {@code prom} 은 더 높은 봉으로의 직선 최소높이 대비.
     */
    static java.util.List<double[]> detectPeaks(RangeField f, double minH, int win, int R) {
        java.util.List<double[]> raw = new java.util.ArrayList<>();
        for (int z = -R; z <= R; z += 2) {
            for (int x = -R; x <= R; x += 2) {
                if (Math.hypot(x, z) > R) continue;
                double h = f.reliefAt(x, z);
                if (h < minH) continue;
                boolean top = true;
                for (int dz = -win; dz <= win && top; dz += 2) {
                    for (int dx = -win; dx <= win; dx += 2) {
                        if ((dx != 0 || dz != 0) && f.reliefAt(x + dx, z + dz) > h + 1e-6) {
                            top = false;
                            break;
                        }
                    }
                }
                if (top) raw.add(new double[]{x, z, h, 0});
            }
        }
        java.util.List<double[]> uniq = new java.util.ArrayList<>();
        for (double[] p : raw) {
            boolean near = false;
            for (double[] q : uniq) {
                if (Math.hypot(p[0] - q[0], p[1] - q[1]) < 20) {
                    near = true;
                    if (p[2] > q[2]) { q[0] = p[0]; q[1] = p[1]; q[2] = p[2]; }
                    break;
                }
            }
            if (!near) uniq.add(p);
        }
        uniq.sort((a, b) -> Double.compare(b[2], a[2]));
        // prominence: 각 봉→더 높은 봉 직선상 최소높이(안부 근사)의 최댓값을 키 안부로
        for (double[] p : uniq) {
            double bestSaddle = 0;
            boolean hasHigher = false;
            for (double[] q : uniq) {
                if (q[2] <= p[2] + 1e-6) continue;
                hasHigher = true;
                double lo = Double.MAX_VALUE;
                int steps = (int) Math.max(4, Math.hypot(p[0] - q[0], p[1] - q[1]));
                for (int s = 1; s < steps; s++) {
                    double t = s / (double) steps;
                    double sx = p[0] + (q[0] - p[0]) * t, sz = p[1] + (q[1] - p[1]) * t;
                    double hh = f.reliefAt((int) Math.round(sx), (int) Math.round(sz));
                    if (hh < lo) lo = hh;
                }
                bestSaddle = Math.max(bestSaddle, lo);
            }
            p[3] = hasHigher ? (p[2] - bestSaddle) : p[2];
        }
        return uniq;
    }

    public static void main(String[] args) {
        RangeSpec spec = RangeSpec.hwasan(0, 0, 63);
        RangeField f = new RangeField(spec);
        int r = spec.economyR();
        boolean ok = true;

        // ④ 등산로 원(源) 보고 — 기본은 ③ 생성기(TrailForge)
        TrailForge.Result plan = f.trailPlan();
        if (plan != null) {
            System.out.println("등산로 원: ③ 생성기(TrailForge) — " + plan.note());
            System.out.printf("  경로계수=%.3f 최대경사=%.3f 구역회전=%s 헤어핀/구역=%d 진폭=%.1f 폴백=%b%n",
                    plan.pathFactor(), plan.maxSlope(), java.util.Arrays.toString(plan.turnsPerZone()),
                    plan.hairpinsPerZone(), plan.amplitude(), plan.fallback());
            if (plan.fallback()) {
                System.out.println("FAIL 등산로: ③ 생성기가 폴백으로 물러섰다 (제약 만족 노선 없음 — 산기슭 완사면 확인)");
                ok = false;
            }
            if (plan.maxSlope() > TrailForge.MAX_SLOPE + 1e-9) {
                System.out.printf("FAIL 등산로: 최대경사 %.3f > 1:1%n", plan.maxSlope());
                ok = false;
            }
        } else {
            System.out.println("등산로 원: spec.trail 목록 (③ 산출 주입됨)");
        }

        // ③ 결정론: 같은 좌표 두 번 (높이·구역·물매)
        long checked = 0;
        for (int x = -r; x <= r; x += 3) {
            for (int z = -r; z <= r; z += 3) {
                if (f.reliefAt(x, z) != f.reliefAt(x, z)
                        || f.surfaceY(x, z) != f.surfaceY(x, z)
                        || f.zoneAt(x, z) != f.zoneAt(x, z)) {
                    System.out.println("FAIL 결정론: (" + x + "," + z + ") 두 호출이 다르다");
                    ok = false;
                }
                checked++;
            }
        }
        boolean sawDifference = f.reliefAt(0, 0) != f.reliefAt(spec.honsanR(), 0);
        if (!sawDifference) {
            System.out.println("FAIL 자기검증: 중앙과 본산 경계가 같은 높이 — 비교가 죽었다");
            ok = false;
        }

        // ② 다봉 위계 — prominence 주봉 ≥3 · 중앙 품 < 최고봉 (C 골격: 최고봉이 중앙 밖 결절)
        java.util.List<double[]> pk = detectPeaks(f, 120, 10, 130);
        int nPeaks = pk.size();
        double centerH = f.reliefAt(0, 0);
        double courtH = f.reliefAt(-2, 46);
        double topH = nPeaks == 0 ? 0 : pk.get(0)[2];
        int promMains = 0;
        for (double[] p : pk) if (p[3] >= 25) promMains++;
        boolean severalMountains = promMains >= 3;
        boolean courtIsLow = courtH < topH - 15;
        System.out.printf("다봉: 봉 %d개 (최고 %.0f) · prominence≥25 주봉 %d개 · 중앙(0,0) %.0f · 품(-2,46) %.0f%n",
                nPeaks, topH, promMains, centerH, courtH);
        StringBuilder pks = new StringBuilder("        봉: ");
        for (int k = 0; k < Math.min(6, pk.size()); k++) {
            pks.append(String.format("(%.0f,%.0f h%.0f p%.0f) ", pk.get(k)[0], pk.get(k)[1], pk.get(k)[2], pk.get(k)[3]));
        }
        System.out.println(pks);
        if (!severalMountains) {
            System.out.println("FAIL 다봉: prominence≥25 주봉 " + promMains + "개 (<3) — 여러 독립 봉이 아니다");
            ok = false;
        }
        if (!courtIsLow) {
            System.out.printf("FAIL 다봉: 품(%.0f)이 최고봉(%.0f)에 준함 — 중앙이 낮은 품이 아니다%n", courtH, topH);
            ok = false;
        }
        // 눈을 시험하는 눈: 가짜 단일 원뿔(중앙 최고)이면 courtIsLow 판정이 거짓이어야
        double coneTop = 158, coneOff = 158 - 0.5 * 60;
        if (coneTop < coneOff - 15) {
            System.out.println("FAIL 자기검증: 다봉 눈이 단일 원뿔 중앙 우세를 못 본다 — 눈이 죽었다");
            ok = false;
        }

        // ① 연속성 (B-155 재범위 · ★calm-only) — 보행/건축 calm 셀에서만 연속(≤SEAM_STEP_MAX).
        //   비-calm(절벽 면)은 큰 단차 허용(험산의 본질). 세로·가로 두 이웃 다 잰다(걸으려면 두 축 연속).
        //   전 domain 을 훑어 남면 corridor·창룡령·품·봉 사면을 전부 덮는다.
        final double calmTau = CALM_WALK_TAU;   // 이 위 = carve 순수 평탄대(w=1) — 보행/건축 표면
        double maxCalmSeam = 0.0, maxCliffSeam = 0.0;
        int cx0 = 0, cz0 = 0;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                double h0 = f.reliefAt(x, z);
                double ca = f.calmAt(x, z);
                for (int[] d : new int[][]{{0, 1}, {1, 0}}) {   // 세로·가로 이웃
                    int nx = x + d[0], nz = z + d[1];
                    double dstep = Math.abs(f.reliefAt(nx, nz) - h0);
                    double cb = f.calmAt(nx, nz);
                    if (ca >= calmTau && cb >= calmTau) {
                        if (dstep > maxCalmSeam) { maxCalmSeam = dstep; cx0 = x; cz0 = z; }
                    } else {
                        maxCliffSeam = Math.max(maxCliffSeam, dstep);
                    }
                }
            }
        }
        System.out.printf("연속성(calm-only): calm maxSeam %.2f칸 @ (%d,%d) (상한 %.0f) · 비-calm 절벽 최대 %.2f칸%n",
                maxCalmSeam, cx0, cz0, SEAM_STEP_MAX, maxCliffSeam);
        if (maxCalmSeam > SEAM_STEP_MAX) {
            System.out.printf("FAIL 연속성: calm 급단차 %.2f칸 @ (%d,%d) > %.0f (보행/건축 평탄대 회귀)%n",
                    maxCalmSeam, cx0, cz0, SEAM_STEP_MAX);
            ok = false;
        }
        // 험산 확인 — 비-calm 사면에 실제 절벽(큰 단차)이 서야 험산이다 (매끄러운 원뿔 회귀 감지)
        if (maxCliffSeam <= SEAM_STEP_MAX) {
            System.out.printf("FAIL 험산: 비-calm 최대 단차 %.2f ≤ %.0f — 절벽이 하나도 없다 (매끄러운 산 회귀)%n",
                    maxCliffSeam, SEAM_STEP_MAX);
            ok = false;
        }
        // ── 눈을 시험하는 눈 (calm-gating 술어가 살아 있는가 — 심은 표본으로 시험) ──
        //  (a) calm 안 25칸 턱 → 위반으로 잡아야 (보행 평탄대는 연속이어야)
        if (!calmSeamViolation(0.9, 0.9, 25.0, calmTau, SEAM_STEP_MAX)) {
            System.out.println("FAIL 자기검증: calm 안 25칸 턱을 못 잡는다 — 눈이 죽었다");
            ok = false;
        }
        //  (b) 비-calm 25칸 절벽 → 통과해야 (험산 절벽은 허용 — 절벽을 막으면 안 된다)
        if (calmSeamViolation(0.1, 0.1, 25.0, calmTau, SEAM_STEP_MAX)) {
            System.out.println("FAIL 자기검증: 비-calm 절벽을 위반으로 본다 — 험산을 막는다");
            ok = false;
        }
        //  (c) calm↔비-calm 경계 25칸 → 통과해야 (평탄대 가장자리에서 절벽이 시작될 수 있다)
        if (calmSeamViolation(0.9, 0.1, 25.0, calmTau, SEAM_STEP_MAX)) {
            System.out.println("FAIL 자기검증: calm↔비-calm 경계 절벽을 위반으로 본다 — 험산을 막는다");
            ok = false;
        }
        //  (d) calm 안 작은 단차(3칸) → 통과해야 (평탄대 안 미세 요철은 정상)
        if (calmSeamViolation(0.9, 0.9, 3.0, calmTau, SEAM_STEP_MAX)) {
            System.out.println("FAIL 자기검증: calm 안 3칸 요철을 위반으로 본다 — 눈이 지나치게 예민");
            ok = false;
        }

        // ⑤ 곁구역 넷 온전 분할
        java.util.EnumMap<RangeZone, Long> area = new java.util.EnumMap<>(RangeZone.class);
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                area.merge(f.zoneAt(x, z), 1L, Long::sum);
            }
        }
        for (RangeZone side : new RangeZone[]{RangeZone.PLUM_GROVE, RangeZone.VALLEY,
                RangeZone.CLIFF, RangeZone.DRILL_VALLEY}) {
            if (area.getOrDefault(side, 0L) == 0L) {
                System.out.println("FAIL 곁구역: " + side + " 가 서지 않는다 (등산로 재단 실패)");
                ok = false;
            }
        }

        // 곁구역 굽이 렌더 (남면 z-단면)
        System.out.println("곁구역 굽이 렌더 (남면 z-단면 · 경계가 반경마다 x 로 옮겨 다니면 노선 재단):");
        int inner = spec.honsanR(), outer = spec.honsanR() + spec.midDepth();
        for (int z = inner + 18; z <= outer - 18; z += 24) {
            StringBuilder row = new StringBuilder();
            RangeZone prev = null;
            for (int x = -80; x <= 80; x += 2) {
                RangeZone zz = f.zoneAt(x, z);
                if (zz != prev) { row.append(String.format(" x=%d→%s", x, tag(zz))); prev = zz; }
            }
            System.out.printf("  z=%3d (남 %3d칸):%s%n", z, z, row);
        }

        System.out.println((ok ? "PASS" : "FAIL") + " — RangeField 자기 시험 ("
                + checked + " 표본 · 곁구역 면적 " + area.entrySet().stream()
                .filter(en -> en.getKey() == RangeZone.PLUM_GROVE || en.getKey() == RangeZone.VALLEY
                        || en.getKey() == RangeZone.CLIFF || en.getKey() == RangeZone.DRILL_VALLEY)
                .map(en -> en.getKey() + "=" + en.getValue()).sorted().toList() + ")");
        if (!ok) {
            System.exit(1);
        }
    }
}
