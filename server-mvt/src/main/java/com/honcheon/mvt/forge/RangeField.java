package com.honcheon.mvt.forge;

/**
 * 산세 높이장 — <b>좌표의 순수 함수</b>. Bukkit 을 모른다 (import 0).
 *
 * <p>결정론 헌법(TerrainForge 머리 주석 · config/terrain_grain.yml)의 계승:
 * <ul>
 *   <li><b>난수 0</b> — 모든 흔들림은 격자점 해시 보간(값 잡음)과 저주파 사인이다.</li>
 *   <li><b>씨앗 = 좌표 해시</b> — {@code floorMod(31·px + 17·pz, 1_000_003)}
 *       (carveTrail 문법, TerrainForge.java:1238). 같은 주봉 좌표 = 같은 산.</li>
 *   <li><b>타일 무관성</b> — 열 하나의 높이가 이웃 열에 안 기댄다. 어느 순서로 빚어도 같은 땅.</li>
 * </ul>
 *
 * <p>형상 문법 (terrain_forge_v5.md §2.4 — 네 부품의 합성):
 * <pre>
 *   H(x,z) = max(방사 몸체, 주능선, 곁능선…) − 협곡 + 바위 결
 * </pre>
 * 방사 몸체는 §2.3 등고 예산(crest)을 방위별 축척 k(θ)로 압축해 두른 것이다 —
 * 남(주능선)은 단면 그대로, 험면은 원뿔 물매 1:0.9 (raiseMassif 계승)까지 죈다.
 *
 * <p>★ grain(격자 보간)은 TerrainForge.java:2432-2472 의 <b>사본</b>이다 — TerrainForge 가
 * package-private 라 이 패키지에서 못 읽는다. 통일은 배선 지점(terrain_forge_v5.md §5-5) —
 * 통일 전까지 두 구현의 동일성 self-test 를 검수 ⑪에 둔다.
 */
public final class RangeField {

    private final RangeSpec spec;

    /** 바위 결의 위상 — 좌표 해시 씨앗 (TF:1238 문법). 난수가 아니다 */
    private final double phase;

    /**
     * 험면(비능선) 방위 축척 하한 — 원뿔 물매 1:0.9 유도:
     * {@code k_min = slope·lift / (domainR − summitFlatR)} (lift 160 → ≈0.51)
     */
    private final double kMin;

    /**
     * 능선 측면 낙하 배율 — 등마루 밖 이격 1칸이 등고를 몇 「단면 칸」만큼 미는가.
     * 측면 물매가 원뿔 물매(1:0.9)와 같아지도록 유도:
     * {@code (1/0.9) ÷ (본산 몫 물매 72/114)} ≈ 1.76
     */
    private final double lateralFall;

    /** 외곽 평원 요철의 덩어리 한 변 — terrain_grain.yml undulation.cell 기본값 (등록부 이관 대상) */
    private static final int UNDULATION_CELL = 9;

    /** 외곽 평원 요철의 진폭 — terrain_grain.yml undulation.amplitude 기본값 (★Q8 — ±1 잠정) */
    private static final int UNDULATION_AMP = 1;

    /** 협곡 방사 창의 여밈 폭 — 골이 칼로 시작하지 않게 하는 전이 (형상 상수 · 후보) */
    private static final double GORGE_EASE = 24.0;

    /**
     * 잠정 등산로 지그재그 진폭 (도) — <b>③ 미착수 1차 시험 조성용</b>. 곁구역 경계를 방사
     * 원에서 떼어 놓는 스위치백의 폭. ③가 서면 spec.trail 이 이것을 대체한다 (미결 세부 §2.3).
     */
    private static final double PROVISIONAL_SWITCHBACK_DEG = 25.0;

    /** 북면(후산) 능선의 하강 배율 — 남면 능선을 이 배수로 더 빨리 내린다 (험면). dz=0 에서 연속 (B-155) */
    private static final double NORTH_STEEP = 2.2;

    /** z=0 이음 급단차 상한 (B-155 회귀 자) — 협곡벽·crag 여유 위, 옛 z=0 벼랑(25+) 아래 */
    private static final double SEAM_STEP_MAX = 6.0;

    /** 산기슭(등산로 고리 발치) 볼록 프로파일 지수 — 작을수록 발치가 급하다 (형상 잠정 · 사용자 피드백 조정) */
    private static final double TRAIL_FOOT_EXP = 0.85;

    /**
     * 바위 결(crag) 진폭 배수 — <b>돌산 재설계</b> (사용자 도보 피드백 2026-07-16: 산이 매끈해
     * 돌산답지 않다). 옛 진폭 합 ~7.5 → 다옥타브 합 ~19.7 (아래 rockRelief). 이 배수는 면(비능선·
     * 비단)의 바위 노출 결 세기다. ★형상 잠정·승인 대기(terrain_forge_v5.md §8.2 — 잠정 ~18~25).
     * 옥타브 파장을 길게(37/41···) 잡아 진폭을 키우되 인접 블록 물매는 &lt;~1칸(연속성 B-155 여유).
     */
    private static final double CRAG_AMP = 1.0;

    /**
     * 보행 corridor 잠잠 반폭 — 남면(dz≥0)에서 |dx| 가 이 안이면 crag 를 끈다(건물 단·등산로가
     * 앉는 평탄대). 등마루 반폭(20) 위에 여유. 옛 값 24(반폭+4)는 등산로 스위치백(dx~30)을 못
     * 덮어 돌산 crag 가 노선 물매를 깨뜨렸다 → 32 로 넓혀 노선까지 덮는다(면은 그 밖이라 험준
     * 유지). ★형상 잠정·승인 대기(§8.2). 램프 폭 CALM_RAMP 로 |dx|=CALM_HALF+CALM_RAMP 에서 완전 crag. */
    private static final double CALM_HALF = 32.0;
    private static final double CALM_RAMP = 14.0;

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
        this.kMin = spec.peakSteepSlope() * spec.lift()
                / (double) (spec.domainR() - spec.summitFlatR());
        double crestSlope = spec.honsanRise()
                / (double) Math.max(1, spec.honsanR() - spec.summitFlatR());
        this.lateralFall = (1.0 / spec.peakSteepSlope()) / Math.max(0.05, crestSlope);

        // 등산로 폴리라인 세우기 — 우선순위:
        //   ① spec.trail(③ 산출 목록)이 채워져 있으면 그것 (기하는 ③ 소유 · §2.4-4 계약)
        //   ② 비어 있으면 ③ 생성기(TrailForge)가 reliefAt 위에서 제약 만족 폴리라인을 낳는다 (기본)
        //   ③ 생성 실패 시 확정값 유도 잠정 지그재그 (문서화된 폴백 · provisionalTrail)
        // 순서: [0] 산기슭(입구 쪽·낮음) … [끝] 본산 문(높음). 호장 비율 u 는 이 순서를 딛는다.
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
     * 잠정 등산로 — <b>폴백</b> (③ 생성기 {@link TrailForge} 가 기본 · 생성 실패나 회귀 대조용으로 남긴다).
     * 확정값 전부에서 유도한다 (지어낸 노선 아님).
     * 남면을 스위치백으로 오른다: 경유점 방사 = 곁구역 옛 띠 경계(264/229/194/159/124 =
     * honsanR + {midDepth, 3·belt, 2·belt, belt, 0}), 방위 = ±{@value #PROVISIONAL_SWITCHBACK_DEG}°
     * 교대 (방위 전환 = 검수 축 11 「굽이」의 씨앗). 이렇게 노선이 옛 띠 경계마다 스위치백으로
     * 가로지르므로, 곁구역은 「스위치백 사이 노선 구간」이 되고 경계가 굽이친다.
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
    // 공개 창구 — 기록기·배치기(⑥)·등산로(③)·검수(⑪)가 읽는다
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
     * base 위 실수 높이 (블록 반올림 전) — 물매 계산·검수 표본용.
     * 같은 좌표로 두 번 불러 같으면 결정론이다 (검수 ⑪의 첫 self-test).
     */
    public double reliefAt(int x, int z) {
        double dx = x - spec.peakX();
        double dz = z - spec.peakZ();
        double r = Math.hypot(dx, dz);
        if (r > spec.economyR()) {
            return 0.0;
        }

        double body = radialBody(r, dx, dz);
        double h = Math.max(body, mainRidge(dx, dz));
        for (RangeSpec.SideRidge sr : spec.sideRidges()) {
            h = Math.max(h, sideRidge(sr, dx, dz));
        }
        h = Math.max(h, subPeaks(dx, dz));   // 다봉 massif — 봉우리 여럿 (돌산 지대)
        h = Math.max(0.0, h - gorgeCut(dx, dz));
        if (h <= 0.0) {
            return 0.0;
        }
        return Math.max(0.0, h + rockRelief(x, z, h, dx, dz));
    }

    /**
     * 등마루 등고 (절대 y) — 주능선 축 위, 주봉에서 남으로 {@code s}칸.
     * 단 사슬 배치기(⑥)와 등산로(③)가 단·경유점의 y 를 여기서 읽는다.
     */
    public int crestY(int s) {
        return spec.baseY() + (int) Math.round(crest(s));
    }

    /**
     * 구역 판정 — 큰 골격(정상/본산·후산/입구/외곽)은 H-6 확정 깊이(140/30/150)·본산권
     * 124 (H-7)의 방사 유도 그대로. <b>곁구역 넷(매화림·계곡·절벽·연무 계곡)의 경계만은
     * 등산로가 꿴다</b> (Q9 확정 — 회차 A-2 · 재단 방식 terrain_forge_v5.md §2.3). 곁구역이
     * 서는 중간 고리(honsanR &lt; r ≤ honsanR+midDepth)의 <b>안쪽 넷 가름</b>은 방사 등분(각
     * 35)이 아니라 <b>등산로 폴리라인 위 호장 비율</b>로 판정한다 → 경계가 노선을 따라 굽이친다.
     * 후산은 주봉 북면(dz &lt; 0) — 정상 뒤가 후산이라는 기하 (브리프 §1.3).
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
            // 곁구역 고리 — 곁구역은 등산로(남면 진입)가 가는 곳에만 산다.
            // 주봉 북면(dz<0)은 후산이 그대로 흘러내린다 (정상 뒤가 후산 — 본산권 갈이 계승,
            // 브리프 §1.3). 남면(dz≥0)만 넷으로 가르되, 가름은 등산로가 꾄다 (방사 띠 폐기 · Q9).
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
    // 곁구역 재단 — 등산로가 꾄다 (Q9 · terrain_forge_v5.md §2.3)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 등산로 폴리라인 위 호장 비율 u∈[0,1] — 0 = 산기슭 끝(입구 쪽), 1 = 본산 문.
     * 열 (x,z)를 노선에 수직 투영해 가장 가까운 구간의 호장 위치를 비율로 돌려준다.
     * u 의 등고선(노선에 수직인 선)이 노선을 따라 굽이치므로 곁구역 경계도 굽이친다.
     */
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

    /**
     * 호장 비율 → 곁구역 넷. 순서 = 오르는 경험 (hwasan_domain_design.md):
     * 산기슭(u≈0) 매화림 → 계곡 → 절벽 → 연무 계곡(u≈1, 본산 곁). 사분 등분은 옛 방사 띠
     * (각 35)의 비율 계승 — 이제 「방사」가 아니라 「노선 호장」으로 잰다 (분할 비율은 튜닝 대상).
     */
    private RangeZone sideZoneByU(double u) {
        if (u < 0.25) {
            return RangeZone.PLUM_GROVE;    // 매화림 — 산기슭 (낮음)
        }
        if (u < 0.50) {
            return RangeZone.VALLEY;        // 계곡
        }
        if (u < 0.75) {
            return RangeZone.CLIFF;         // 절벽
        }
        return RangeZone.DRILL_VALLEY;      // 연무 계곡 — 본산 곁 (높음)
    }

    // ═══════════════════════════════════════════════════════════════════
    // 부품 1 — 등고 예산 단면 (terrain_forge_v5.md §2.3 표 그대로)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 대표 단면 — 주능선 등마루의 등고 (base 위).
     * <pre>
     *   0…flatR          : +lift            (정상 평탄부)
     *   flatR…honsanR    : −72 (본산 몫 — 계단 1:1 × 굴곡 1.5 → 방사 48 + 단 44)
     *   honsanR…+140     : −(lift−72), 오목 t^0.85 (오를수록 가파르다 — TF:1104 문법 계승)
     *   그 밖            : 0 (입구·외곽 평원 — 산기슭 평지)
     * </pre>
     */
    private double crest(double s) {
        if (s <= spec.summitFlatR()) {
            return spec.lift();
        }
        if (s <= spec.honsanR()) {
            double t = (s - spec.summitFlatR()) / (spec.honsanR() - spec.summitFlatR());
            return spec.lift() - spec.honsanRise() * ease(t);
        }
        double trailOuter = spec.honsanR() + spec.midDepth();
        if (s < trailOuter) {
            double t = (s - spec.honsanR()) / spec.midDepth();
            // 산기슭(t→1·외곽)에 경사를 준다 — 옛 오목 프로파일(1−t^0.85)은 발치가 완만해
            // 산이 멀어 보였다(사용자 실측 2026-07-16). 볼록 (1−t)^TRAIL_FOOT_EXP 로 발치를
            // 세운다. 본산 쪽(t→0)은 완만해지되 그 위 본산권(§321)이 다시 가팔라 자연스럽다.
            // honsanR 에서 두 프로파일 다 trailRise(88)라 이음은 연속. TRAIL_FOOT_EXP 는 형상
            // 잠정값(±로 조정) — 작을수록 발치가 급하다 (0.85 ≈ 발치 물매 ~0.8/칸, 옛 ~0.54).
            return spec.trailRise() * Math.pow(1.0 - t, TRAIL_FOOT_EXP);
        }
        return 0.0;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 부품 2 — 방사 몸체 (방위별 축척)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 방위별 축척 k(θ) — 남(주능선 방위) 1.0, 험면 {@link #kMin}.
     * cos 남향값의 거듭제곱으로 로브를 좁힌다 (지수 2 — 형상 상수 · 후보).
     */
    private double azimuthScale(double dx, double dz, double r) {
        if (r < 1.0) {
            return 1.0;
        }
        double cosSouth = dz / r;                        // 남 = +z
        double lobe = Math.max(0.0, cosSouth);
        return kMin + (1.0 - kMin) * lobe * lobe;
    }

    /** 방사 몸체 — 단면을 방위 축척으로 압축해 두른다 */
    private double radialBody(double r, double dx, double dz) {
        int flat = spec.summitFlatR();
        if (r <= flat) {
            return spec.lift();
        }
        double k = azimuthScale(dx, dz, r);
        return crest(flat + (r - flat) / k);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 부품 3 — 주능선·곁능선 (스와스)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 주능선 — 주봉에서 남(+z)으로 뻗는 스와스. 등마루 반폭 안은 단면 그대로
     * (여기 본산 단 사슬이 앉는다 — 폭 41, 브리프 §1.5-가), 밖은 원뿔 물매로 낙하.
     */
    private double mainRidge(double dx, double dz) {
        // 남면(dz≥0)은 완만한 등산로 능선 · 북면(dz<0)은 같은 능선을 NORTH_STEEP 배로 빨리 내린
        // 험면(후산). ★B-155: 옛 하드 컷(dz<0 → 0)은 남 능선(정상 높이)과 북 몸체(압축)가 z=0
        //   에서 만나 최대 33칸 벼랑을 냈다 (화산 오프라인 미리보기가 잡음). 효과 거리를 dz=0 에서
        //   0 으로 잇고 북으로 배증시키면 능선이 z=0 급단차 없이 후산으로 가파르게 흘러내린다.
        double lat = Math.abs(dx);
        double over = Math.max(0.0, lat - spec.ridgeHalfWidth()) * lateralFall;
        double effDz = dz >= 0.0 ? dz : -dz * NORTH_STEEP;
        return crest(Math.hypot(effDz, over));
    }

    /** 곁능선 — 주능선 문법의 축소판 (3가닥 확정 — Q4 · 방위·규모 근거는 RangeSpec.hwasan). 끝은 여며 든다 */
    private double sideRidge(RangeSpec.SideRidge sr, double dx, double dz) {
        double a = Math.toRadians(sr.azimuthDeg());
        double ux = Math.sin(a);                          // 남축 기준 방위 → (x,z) 단위벡터
        double uz = Math.cos(a);
        double along = dx * ux + dz * uz;
        if (along <= 0) {
            return 0.0;
        }
        double lat = Math.abs(dx * uz - dz * ux);
        double over = Math.max(0.0, lat - sr.halfWidth()) * lateralFall;
        double h = sr.crestScale() * crest(Math.hypot(along, over));
        double tipStart = sr.length() * 0.7;
        if (along > tipStart) {
            double t = Math.min(1.0, (along - tipStart) / (sr.length() * 0.3));
            h *= 1.0 - ease(t);
        }
        return h;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 부품 3b — 돌 봉우리 (다봉 massif) — 사용자 피드백 2026-07-16
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 다봉 봉우리 — 봉우리 여럿을 결정론 가우시안 융기로 세운다 (RangeSpec.subPeaks 유도값).
     * 방사 몸체 위에 {@code max} 로 얹혀 <b>독립 국소 최고점</b>이 된다 — 능선·안부가 이들을
     * 이어 하나의 바위 massif 를 이루고, 정상(중앙)은 그중 최고봉으로 남는다(height &lt; lift).
     * 본산 건물 단은 봉우리들 사이 오목한 품(남축 안부)에 감싸인다.
     *
     * <p>가우시안 {@code height·exp(−(d/σ)²)} 는 매끄러워(C∞) z=0 급단차를 안 만든다 (B-155).
     * 봉우리 마루(융기가 몸체를 이기는 곳)만 솟고, 그 밖은 몸체가 이겨 봉우리가 안 보인다.
     * 좌표의 순수 함수(난수 0) — 봉우리 자리는 방위·반경으로 못박은 절대 좌표다.
     */
    private double subPeaks(double dx, double dz) {
        double best = 0.0;
        for (RangeSpec.SubPeak p : spec.subPeaks()) {
            double a = Math.toRadians(p.azimuthDeg());
            double px = p.radius() * Math.sin(a);         // 봉우리 중심 (주봉 기준 상대)
            double pz = p.radius() * Math.cos(a);
            double d = Math.hypot(dx - px, dz - pz);
            double t = d / p.sigma();
            best = Math.max(best, p.height() * Math.exp(-t * t));
        }
        return best;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 부품 4 — 협곡 (감산 채널) · 바위 결
    // ═══════════════════════════════════════════════════════════════════

    /** 협곡 — 마른 골. 물은 수계(②)의 몫. 바닥이 base 아래로 내려가지 않는 것은 합성부의 clamp */
    private double gorgeCut(double dx, double dz) {
        double cut = 0.0;
        for (RangeSpec.Gorge g : spec.gorges()) {
            double a = Math.toRadians(g.azimuthDeg());
            double ux = Math.sin(a);
            double uz = Math.cos(a);
            double along = dx * ux + dz * uz;
            if (along < g.innerR() - GORGE_EASE || along > g.outerR() + GORGE_EASE) {
                continue;
            }
            double win = ease(clamp01((along - g.innerR()) / GORGE_EASE))
                    * ease(clamp01((g.outerR() - along) / GORGE_EASE));
            double lat = Math.abs(dx * uz - dz * ux);
            double q = Math.max(0.0, 1.0 - (lat / g.halfWidth()) * (lat / g.halfWidth()));
            cut = Math.max(cut, g.depth() * win * q);
        }
        return cut;
    }

    /**
     * 바위 결 — <b>돌산 다옥타브 crag</b> (사용자 피드백 2026-07-16). crag 문법 계승·강화:
     * 옛 3항(파장 11~23·진폭합 7.5)을 5옥타브(파장 41→7·진폭합 ~19.7)로 키워 <b>거친 바위 노출
     * 결</b>을 낸다. 큰 파장에 큰 진폭·잔 파장에 작은 진폭 → 진폭은 커도 인접 블록 물매 &lt;~1칸
     * (연속성 B-155 여유 · 자기시험이 잰다). 정상 평탄부·봉우리 마루·등마루 보행선·단은 매끈하고
     * (calm·ramp) <b>면(비능선·비단)과 봉우리 사이가 가장 험하다</b> (cragFactor 문법 강화, TF:1168-1171).
     */
    private double rockRelief(int x, int z, double h, double dx, double dz) {
        double crag = CRAG_AMP * (
                  Math.sin(x / 37.0 + phase) * Math.cos(z / 41.0 - phase) * 8.0   // 큰 바위 덩어리 (저주파·큰 진폭)
                + Math.sin((x + z) / 23.0 + phase) * 5.0                          // 중간 바위 단
                + Math.cos((x - z) / 17.0 - phase) * 3.2                          // 바위 결
                + Math.sin(x / 11.0 - phase) * Math.cos(z / 13.0 + phase) * 2.2   // 잔결
                + Math.sin((x - z) / 7.0 + phase) * 1.3);                         // 아주 잔 결 (jagged)
        double tNorm = 1.0 - Math.min(1.0, h / spec.lift());   // 0 = 정상·봉우리 마루, 1 = 발치
        double ramp = clamp01((tNorm - 0.05) / 0.20);           // 정상·봉우리 마루 부근은 0 (매끈한 단)
        double factor = ramp * (0.55 + 0.45 * (1.0 - tNorm));   // 중·상턱 면이 가장 험하다 (돌산 노출)
        double calm = 1.0;                                       // 등마루·단·등산로 보행선은 잠잠하다
        if (dz >= 0) {
            calm = clamp01((Math.abs(dx) - CALM_HALF) / CALM_RAMP);
        }
        return crag * factor * calm;
    }

    /** 외곽 평원의 요철 — undulation 문법 (terrain_grain.yml cell 9 · ±1) */
    private int undulation(int x, int z) {
        return (int) Math.round(grain2(x, z, UNDULATION_CELL) * UNDULATION_AMP);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 결(結) — 격자점 해시 보간. ★TerrainForge.java:2432-2472 의 사본 (§5-5 통일 대상)
    // ═══════════════════════════════════════════════════════════════════

    /** 격자점 하나의 값 [-1,1] — 정수 해시 (splitmix64 계열 섞기). 난수 씨앗이 없다 */
    private static double lattice(int gx, int gz) {
        long h = gx * 0x9E3779B97F4A7C15L ^ gz * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return ((h >>> 11) / (double) (1L << 53)) * 2.0 - 1.0;
    }

    /** 매끄러운 이음 (smoothstep) */
    private static double ease(double t) {
        return t * t * (3 - 2 * t);
    }

    /** 결의 값 [-1,1] — cell 칸 격자에 해시를 깔고 보간한다 */
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

    /** 두 겹의 결 — 큰 덩어리 위에 작은 결 (2옥타브) */
    static double grain2(int x, int z, int cell) {
        return grain(x, z, cell) * 0.72
                + grain(x + 8191, z + 4703, Math.max(2, cell / 2)) * 0.28;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    // ═══════════════════════════════════════════════════════════════════
    // 자기 시험 — 검수 ⑪의 첫 눈 (서버 없이 돈다 · terrain_forge_v5.md §2.5·§6)
    //   실행: java com.honcheon.mvt.forge.RangeField   (Bukkit 무의존)
    // ═══════════════════════════════════════════════════════════════════
    /** 굽이 렌더용 짧은 구역 표 */
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

    public static void main(String[] args) {
        RangeSpec spec = RangeSpec.hwasan(0, 0, 63);
        RangeField f = new RangeField(spec);
        int r = spec.economyR();
        boolean ok = true;

        // 등산로 원(源) 보고 — 기본은 ③ 생성기(TrailForge). 곁구역 재단의 자가 무엇인지 찍는다.
        TrailForge.Result plan = f.trailPlan();
        if (plan != null) {
            System.out.println("등산로 원: ③ 생성기(TrailForge) — " + plan.note());
            System.out.printf("  경로계수=%.3f 최대경사=%.3f 구역회전=%s 헤어핀/구역=%d 진폭=%.1f 폴백=%b%n",
                    plan.pathFactor(), plan.maxSlope(), java.util.Arrays.toString(plan.turnsPerZone()),
                    plan.hairpinsPerZone(), plan.amplitude(), plan.fallback());
        } else {
            System.out.println("등산로 원: spec.trail 목록 (③ 산출 주입됨)");
        }

        // 축 12 — 결정론: 같은 좌표를 두 번 불러 같아야 한다 (높이·구역·물매)
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

        // 축 12 자기검증(눈을 시험하는 눈): 다른 좌표는 실제로 갈려야 한다 (비교가 살아 있는가)
        boolean sawDifference = f.reliefAt(0, 0) != f.reliefAt(spec.honsanR(), 0);
        if (!sawDifference) {
            System.out.println("FAIL 자기검증: 정상과 본산 경계가 같은 높이 — 비교가 죽었다");
            ok = false;
        }

        // 축 연속성 (B-155) — z=0 이음에 급단차가 없다. 옛 하드 컷은 남 능선과 북 후산이
        //   z=0 에서 만나 최대 33칸 벼랑을 냈다 (화산 오프라인 미리보기가 잡음 · 이 축이 없었다).
        //   후산 험면(kMin) 자체의 가파름은 이 밴드 밖이라 여기 안 걸린다.
        double maxSeam = 0.0;
        int sx = 0, sz = 0;
        for (int x = -r; x <= r; x++) {
            for (int z = -30; z < 10; z++) {
                double d = Math.abs(f.reliefAt(x, z + 1) - f.reliefAt(x, z));
                if (d > maxSeam) {
                    maxSeam = d;
                    sx = x;
                    sz = z;
                }
            }
        }
        if (maxSeam > SEAM_STEP_MAX) {
            System.out.printf("FAIL 연속성: z=0 이음 급단차 %.1f칸 @ (%d,%d) > %.0f (B-155 회귀)%n",
                    maxSeam, sx, sz, SEAM_STEP_MAX);
            ok = false;
        }
        // 눈을 시험하는 눈 — 같은 검출기가 일부러 심은 급단차를 잡는가 (심지 않으면 못 잡는다)
        double[] planted = {5, 5, 5, 30, 30, 30};   // 5→30 = 25칸 턱
        double plantedMax = 0.0;
        for (int i = 0; i < planted.length - 1; i++) {
            plantedMax = Math.max(plantedMax, Math.abs(planted[i + 1] - planted[i]));
        }
        if (!(plantedMax > SEAM_STEP_MAX)) {
            System.out.println("FAIL 자기검증: 연속성 검출기가 심은 25칸 턱을 못 잡는다 — 눈이 죽었다");
            ok = false;
        }

        // 곁구역 재단(Q9): 중간 고리가 넷으로 온전히 갈리고 넷 다 서는가 (검수 축 4~6 분모)
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

        // ── 오프라인 「굽이」 렌더 (수치) — 곁구역 경계가 노선을 따라 굽이치는가 ──
        //   방사 등분이면 각 반경 z-단면에서 구역 경계 x 가 반경마다 같은 각(고정)일 것이다.
        //   노선 재단이면 경계가 노선을 따라 x 로 이동한다 → 반경별 경계 x 가 갈린다 = 굽이.
        System.out.println("곁구역 굽이 렌더 (남면 z-단면 · 경계가 반경마다 x 로 옮겨 다니면 노선 재단):");
        int inner = spec.honsanR(), outer = spec.honsanR() + spec.midDepth();
        for (int z = inner + 18; z <= outer - 18; z += 24) {
            StringBuilder row = new StringBuilder();
            RangeZone prev = null;
            for (int x = -80; x <= 80; x += 2) {
                RangeZone zz = f.zoneAt(x, z);
                if (zz != prev) {
                    row.append(String.format(" x=%d→%s", x, tag(zz)));
                    prev = zz;
                }
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
