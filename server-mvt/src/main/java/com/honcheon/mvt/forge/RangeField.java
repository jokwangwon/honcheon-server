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

    /** 등산로 폴리라인 (절대 좌표) — 곁구역 재단의 자. spec.trail(③) 또는 잠정 노선 */
    private final double[] trailX;
    private final double[] trailZ;
    /** 각 경유점까지의 누적 호장 · 전체 호장 — 호장 비율 u∈[0,1] 계산용 */
    private final double[] trailCum;
    private final double trailLen;

    public RangeField(RangeSpec spec) {
        this.spec = spec;
        long seed = Math.floorMod(31L * spec.peakX() + 17L * spec.peakZ(), 1_000_003L);
        this.phase = (seed % 628) / 100.0;
        this.kMin = spec.peakSteepSlope() * spec.lift()
                / (double) (spec.domainR() - spec.summitFlatR());
        double crestSlope = spec.honsanRise()
                / (double) Math.max(1, spec.honsanR() - spec.summitFlatR());
        this.lateralFall = (1.0 / spec.peakSteepSlope()) / Math.max(0.05, crestSlope);

        // 등산로 폴리라인 세우기 — ③의 산출(spec.trail)이 있으면 그것, 없으면 잠정 노선.
        // 순서: [0] 산기슭(입구 쪽·낮음) … [끝] 본산 문(높음). 호장 비율 u 는 이 순서를 딛는다.
        double[][] wp = spec.trail().isEmpty()
                ? provisionalTrail()
                : fromSpecTrail();
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
     * 잠정 등산로 — ③ 미착수 1차 시험 조성용 (확정값 전부에서 유도, 지어낸 노선 아님).
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
            return spec.trailRise() * (1.0 - Math.pow(t, 0.85));
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
        if (dz < 0) {
            return 0.0;                                  // 북쪽은 험면(후산)의 것
        }
        double lat = Math.abs(dx);
        double over = Math.max(0.0, lat - spec.ridgeHalfWidth()) * lateralFall;
        return crest(Math.hypot(dz, over));
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
     * 바위 결 — crag 문법 계승 (파장 11~23칸, TF:1155-1159). 정상 평탄부와 등마루
     * 보행선은 매끈하고 중턱이 가장 험하다 (cragFactor 문법, TF:1168-1171).
     */
    private double rockRelief(int x, int z, double h, double dx, double dz) {
        double crag = Math.sin(x / 13.0 + phase) * Math.cos(z / 11.0 - phase) * 3.5
                + Math.sin((x + z) / 23.0 + phase) * 2.5
                + Math.sin((x - z) / 17.0) * 1.5;
        double tNorm = 1.0 - Math.min(1.0, h / spec.lift());   // 0 = 정상, 1 = 발치
        double ramp = clamp01((tNorm - 0.05) / 0.25);           // 정상 부근은 0 (매끈한 단)
        double factor = ramp * (0.5 + 0.5 * (1.0 - tNorm));     // 중턱이 가장 험하다
        double calm = 1.0;                                       // 등마루 보행선은 잠잠하다
        if (dz >= 0) {
            calm = clamp01((Math.abs(dx) - (spec.ridgeHalfWidth() + 4)) / 8.0);
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
    public static void main(String[] args) {
        RangeSpec spec = RangeSpec.hwasan(0, 0, 63);
        RangeField f = new RangeField(spec);
        int r = spec.economyR();
        boolean ok = true;

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
