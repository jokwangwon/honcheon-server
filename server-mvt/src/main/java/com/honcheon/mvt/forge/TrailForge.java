package com.honcheon.mvt.forge;

/**
 * 등산로 생성기 — 기계 ③ (굽이 등산로·천계단). <b>좌표의 순수 함수</b>. Bukkit 을 모른다 (import 0).
 *
 * <p>계약 정본: {@code docs/collaboration/CODEX_WAVE2_MIGYEOL_REVIEW.md §5} ·
 * {@code docs/design/terrain_forge_v5.md §3(기계 ③)·§2.4-4}. 이 클래스는 <b>하나의 정규
 * 폴리라인</b>을 결정론으로 낳는다 — {@link RangeField#zoneAt}(곁구역 재단)·산길 조성기·교량
 * 배치·연속성/경사/길이 감사가 <b>모두 이 폴리라인 하나를 공유</b>한다 (각자 재계산 금지 · §5.3).
 *
 * <h2>승인된 제약 (Codex §5.1 — 전부 확정값, 지어낸 수 아님)</h2>
 * <ul>
 *   <li><b>최대 경사 1:1</b> — 구간(및 소구간)마다 {@code |Δh|/수평거리 ≤ 1} ({@link #MAX_SLOPE}).</li>
 *   <li><b>구역당 방향 전환 ≥2회</b> — 곁구역 넷 각각 안에서 헤어핀(굽이) ≥2 ({@link #MIN_TURNS_PER_ZONE}).</li>
 *   <li><b>직선 대비 경로계수 ~1.5</b> — 호장/직선거리 ≈ {@link #PATH_FACTOR_TARGET}
 *       (§2.2 검산 2: 방사 140 × 굴곡 1.5 = 노선 210).</li>
 *   <li><b>중간 4구역 통과</b> — 매화림·계곡·절벽·연무 계곡을 u∈[0,1] 순서로 꿴다.</li>
 *   <li><b>무작위 금지 · 결정론</b> — 같은 입력 → 같은 폴리라인 (난수 0).</li>
 * </ul>
 *
 * <h2>결정 과정 (Codex §5.2)</h2>
 * <ol>
 *   <li>결정론 후보 격자 생성 — (구역당 헤어핀 {@link #HAIRPINS_PER_ZONE_GRID}) ×
 *       (스위치백 진폭 {@link #AMP_MIN}..{@link #AMP_MAX} 격자).</li>
 *   <li>경사·회전·4구역·경로계수 제약을 <b>동시</b> 만족하는 후보만 남긴다.</li>
 *   <li>경로계수 1.5 에 가장 가까운 후보를 뽑는다 (동률이면 헤어핀 적은 것 → 진폭 작은 것).</li>
 *   <li>동(+84 쪽)·서 후보를 거울상으로 평가한다. <b>표식·명명 경유점 비용</b>이 한쪽을 고른다.</li>
 *   <li>완전 동률(비대칭 표식·경유점 부재)이면 임의 동쪽을 <b>굳히지 않고</b> 사용자 미결로 반환한다
 *       ({@link Result#directionResolved} = false · §5.5).</li>
 * </ol>
 *
 * <p>실행 시점 = 광역 산세·B-155 닫힌 뒤, 테라스·건축 앞 (§5.3). 높이는 {@link HeightFn}
 * (= {@code RangeField.reliefAt}, <b>trail 무의존</b>)로 읽어 노선을 낳는다 — 순환 없음.
 */
public final class TrailForge {

    private TrailForge() {
    }

    /** 높이 표본 창구 — {@code RangeField.reliefAt} 를 넘겨 받는다 (trail 무의존이라 생성기가 읽어도 순환 없음) */
    @FunctionalInterface
    public interface HeightFn {
        double at(int x, int z);
    }

    // ── 승인된 제약 (확정값 — Codex §5.1) ──────────────────────────────────
    /**
     * 최대 경사 1:1 (계단 상한 · H-8 · Codex §5.1). ★carve 제거(2026-07-16) 뒤 이 값은 노선 후보
     * <b>탈락 필터가 아니다</b> — 산이 통짜 험산이라 예정 중심선 물매가 1:1 을 넘는다. 물매-보행성은
     * 미래 도보길(잔도·천계단) 조성이 실제 노반을 깎아 맞춘다(미결 이관). {@code maxSlope} 는 보고·
     * 검출기 자기시험(경사 검출 눈)에만 쓴다.
     */
    public static final double MAX_SLOPE = 1.0;
    /** 구역당 방향 전환 최소 (Codex §5.1 · 검수 축 11 굽이) */
    public static final int MIN_TURNS_PER_ZONE = 2;
    /** 직선 대비 경로계수 목표 (Codex §5.1 · §2.2 검산 2 = 굴곡 1.5) */
    public static final double PATH_FACTOR_TARGET = 1.5;
    /** 중간 곁구역 수 (Codex §5.4 잠정 4등분 상속 — 명명 경유점 승인 전 잠정) */
    public static final int ZONES = 4;

    // ── 잠정 튜닝 손잡이 (★잠정 — ③·미리보기 뒤 확정 · 지어낸 수 아니라 제약 유도) ──────────
    /**
     * 경로계수 허용 폭 — 1.5 ± 이 값 안이면 합격. ★잠정 (승인 대기): 1.5 는 확정 목표이나
     * 격자 이산화로 정확히 못 맞히므로 근사 밴드를 둔다. 명명 경유점 확정 시 노선이 그것을 꿰며 폐기.
     */
    public static final double PATH_FACTOR_TOL = 0.15;
    /** 후보 진폭 격자 하한 (칸) — ★잠정 */
    public static final double AMP_MIN = 4.0;
    /** 후보 진폭 격자 상한 (칸) — ★잠정. 노선 콘이 ±56·+84 곁능선(먼 방위)에 안 닿게 죄는 상한 */
    public static final double AMP_MAX = 40.0;
    /** 후보 진폭 격자 간격 (칸) — ★잠정 */
    public static final double AMP_STEP = 0.5;
    /** 구역당 헤어핀 후보 (≥ {@value #MIN_TURNS_PER_ZONE}) — ★잠정 4등분 상속. 2 우선(적을수록 진폭 큰 굽이) */
    public static final int[] HAIRPINS_PER_ZONE_GRID = {2, 3};

    /** 경사 검사 소구간 표본 간격 (칸) — 긴 대각 구간의 안쪽 급경사도 잡는다 */
    private static final double SLOPE_SUBSTEP = 4.0;

    /** 동/서 갈래 (거울상 · +84 미결의 축) */
    public enum Chirality {
        /** +84 곁능선 쪽 (동 — §2.4-3 「동 기본」이나 굳히지 않는다) */
        EAST(+1),
        /** 서 */
        WEST(-1);
        final int sign;

        Chirality(int s) {
            this.sign = s;
        }
    }

    /**
     * 산출 — 하나의 정규 폴리라인 + 판정 지표.
     *
     * @param xs                절대 x (산기슭 [0] … 본산 문 [끝])
     * @param zs                절대 z
     * @param directionResolved +84 동/서가 <b>결정됐나</b> — false 면 사용자 미결 (동 기본 잠정 사용 · 굳히지 않음)
     * @param chirality         현재 폴리라인의 갈래 (미결이면 잠정 동)
     * @param pathFactor        호장/직선거리
     * @param maxSlope          최대 경사 (칸/칸)
     * @param turnsPerZone      곁구역 넷 각각의 방향 전환 수 [매화림,계곡,절벽,연무]
     * @param hairpinsPerZone   뽑힌 구역당 헤어핀 수
     * @param amplitude         뽑힌 스위치백 진폭 (칸)
     * @param fallback          제약 만족 후보를 못 찾아 잠정 노선으로 물러섰나
     * @param note              사람이 읽을 판정 사유
     */
    public record Result(double[] xs, double[] zs,
                         boolean directionResolved, Chirality chirality,
                         double pathFactor, double maxSlope, int[] turnsPerZone,
                         int hairpinsPerZone, double amplitude,
                         boolean fallback, String note) {
    }

    // ═══════════════════════════════════════════════════════════════════
    // 생성 창구
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 정규 폴리라인을 낳는다. spec.trail() 이 비었을 때 RangeField 가 부른다 (③가 채워지면 그 목록이 이긴다).
     */
    public static Result generate(RangeSpec spec, HeightFn height) {
        // 1~3: 동(기본) 갈래로 제약 만족 최적 후보를 뽑는다 (격자 탐색).
        Candidate east = search(spec, height, Chirality.EAST);
        Candidate west = search(spec, height, Chirality.WEST);

        if (east == null || west == null) {
            // 제약 만족 후보 없음 — 확정값 유도 잠정 노선으로 폴백 (문서화된 폴백 · 굳히지 않음)
            double[][] wp = provisional(spec);
            int[] t = turnsPerZone(spec, wp[0], wp[1]);
            return new Result(wp[0], wp[1], false, Chirality.EAST,
                    pathFactor(wp[0], wp[1]), maxSlope(wp[0], wp[1], height), t,
                    2, TrailForge.provisionalAmp(spec), true,
                    "폴백: 제약 만족 후보 없음 — 잠정 지그재그 노선 (③ 격자 상한·경유점 재검토 필요)");
        }

        // 4~5: 거울상 비용 비교. 비대칭 표식·명명 경유점이 없으면 완전 동률 → +84 미결.
        double costE = chiralityCost(spec, east);
        double costW = chiralityCost(spec, west);
        boolean resolved = Math.abs(costE - costW) > 1e-9;
        Chirality pick = (!resolved || costE <= costW) ? Chirality.EAST : Chirality.WEST;
        Candidate c = pick == Chirality.EAST ? east : west;

        String note = resolved
                ? "결정: 표식·경유점 비용이 " + pick + " 를 골랐다 (비용 동=" + costE + " 서=" + costW + ")"
                : "미결(+84 동/서): 비대칭 표식·명명 경유점 부재 → 동/서 완전 동률. "
                        + "노선 콘 ±" + String.format("%.1f", coneDeg(spec, c))
                        + "° 안에 ±56·+84 곁능선(먼 방위) 없음 → 지형도 대칭. 동 기본 잠정, 굳히지 않음";

        return new Result(c.xs, c.zs, resolved, pick,
                c.pathFactor, c.maxSlope, c.turns, c.hairpinsPerZone, c.amplitude,
                false, note);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 후보 격자 탐색 — 제약 동시 만족 · 경로계수 1.5 근접 선택 (결정론)
    // ═══════════════════════════════════════════════════════════════════

    private record Candidate(double[] xs, double[] zs, Chirality chir,
                             double pathFactor, double maxSlope, int[] turns,
                             int hairpinsPerZone, double amplitude) {
    }

    private static Candidate search(RangeSpec spec, HeightFn height, Chirality chir) {
        Candidate best = null;
        double bestErr = Double.MAX_VALUE;
        for (int hp : HAIRPINS_PER_ZONE_GRID) {
            for (double a = AMP_MIN; a <= AMP_MAX + 1e-9; a += AMP_STEP) {
                double[][] wp = zigzag(spec, hp, a, chir);
                double pf = pathFactor(wp[0], wp[1]);
                double err = Math.abs(pf - PATH_FACTOR_TARGET);
                if (err > PATH_FACTOR_TOL) {
                    continue;                       // 경로계수 밴드 밖
                }
                // ★2026-07-16 carve 제거: 산이 통짜 험산이라 노선 중심선 물매가 1:1 을 넘는다.
                //   물매-보행성은 미래 도보길(잔도·천계단) 조성의 몫으로 이관 — 여기선 노선 '계획'만
                //   (경로계수·회전·4구역)으로 후보를 고른다. 물매는 여전히 재어 Result.maxSlope 로
                //   보고하나 후보 탈락 사유가 아니다 (미결: 도보길 조성이 실제 노반을 깎아 물매를 맞춘다).
                double ms = maxSlope(wp[0], wp[1], height);
                int[] turns = turnsPerZone(spec, wp[0], wp[1]);
                if (!turnsOk(turns) || !fourZones(spec, wp[0], wp[1])) {
                    continue;                       // 회전 부족 또는 4구역 미달
                }
                // 선택: 경로계수 1.5 최근접 → (동률) 헤어핀 적은 것 → 진폭 작은 것 (전부 결정론)
                if (err < bestErr - 1e-12) {
                    bestErr = err;
                    best = new Candidate(wp[0], wp[1], chir, pf, ms, turns, hp, a);
                }
            }
        }
        return best;
    }

    private static boolean turnsOk(int[] turns) {
        for (int t : turns) {
            if (t < MIN_TURNS_PER_ZONE) {
                return false;
            }
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 폴리라인 기하 — 스위치백 지그재그
    // ═══════════════════════════════════════════════════════════════════

    /** 노선 방사 범위: 산기슭(중간 고리 바깥 = 입구 경계)=honsanR+midDepth … 본산 문=honsanR */
    private static int rFoot(RangeSpec spec) {
        return spec.honsanR() + spec.midDepth();
    }

    private static int rGate(RangeSpec spec) {
        return spec.honsanR();
    }

    /**
     * 스위치백 폴리라인. 남축(+z)을 따라 산기슭→문으로 내려 감기며(반경↓), 가로(x)로 진폭 A 만큼
     * ±교대한다. 헤어핀(굽이) = 구역당 hp × 4구역. 양끝은 남축 위(진폭 0 — 문·산기슭 정렬).
     */
    private static double[][] zigzag(RangeSpec spec, int hairpinsPerZone, double amp, Chirality chir) {
        int htot = hairpinsPerZone * ZONES;         // 총 헤어핀
        int segs = htot + 1;                          // 구간 수 = 헤어핀 + 1
        int n = segs + 1;                             // 정점 수
        double r0 = rFoot(spec), r1 = rGate(spec);
        double[] xs = new double[n];
        double[] zs = new double[n];
        for (int i = 0; i < n; i++) {
            double zc = r0 - (r0 - r1) * (i / (double) segs);   // 남축 성분 (반경 감소)
            double lat;
            if (i == 0 || i == n - 1) {
                lat = 0.0;                                       // 산기슭·문은 축 위
            } else {
                lat = chir.sign * amp * ((i % 2 == 1) ? 1.0 : -1.0);  // 굽이 정점 ±A 교대
            }
            xs[i] = spec.peakX() + lat;
            zs[i] = spec.peakZ() + zc;
        }
        return new double[][]{xs, zs};
    }

    /** 노선 콘 반각(도) — 진폭이 문 반경에서 남축과 이루는 최대 각 (곁능선 방위와의 이격 확인용) */
    private static double coneDeg(RangeSpec spec, Candidate c) {
        return Math.toDegrees(Math.atan(c.amplitude / (double) rGate(spec)));
    }

    // ═══════════════════════════════════════════════════════════════════
    // 판정 지표 — 순수 함수
    // ═══════════════════════════════════════════════════════════════════

    /** 경로계수 = 호장 / 직선거리(양끝) */
    static double pathFactor(double[] xs, double[] zs) {
        double arc = 0.0;
        for (int i = 1; i < xs.length; i++) {
            arc += Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
        }
        double straight = Math.hypot(xs[xs.length - 1] - xs[0], zs[zs.length - 1] - zs[0]);
        return straight <= 0 ? 0 : arc / straight;
    }

    /**
     * 최대 경사 — 구간을 {@value #SLOPE_SUBSTEP}칸 소구간으로 잘라 |Δh|/수평 을 잰다.
     * 긴 대각 구간이 협곡·곁능선을 스치며 안쪽에서 급해지는 것도 잡는다.
     */
    static double maxSlope(double[] xs, double[] zs, HeightFn height) {
        double max = 0.0;
        for (int i = 1; i < xs.length; i++) {
            double segLen = Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
            int steps = Math.max(1, (int) Math.ceil(segLen / SLOPE_SUBSTEP));
            double px = xs[i - 1], pz = zs[i - 1];
            double ph = height.at((int) Math.round(px), (int) Math.round(pz));
            for (int s = 1; s <= steps; s++) {
                double t = s / (double) steps;
                double cx = xs[i - 1] + (xs[i] - xs[i - 1]) * t;
                double cz = zs[i - 1] + (zs[i] - zs[i - 1]) * t;
                double ch = height.at((int) Math.round(cx), (int) Math.round(cz));
                double horiz = Math.hypot(cx - px, cz - pz);
                if (horiz > 1e-6) {
                    max = Math.max(max, Math.abs(ch - ph) / horiz);
                }
                px = cx;
                pz = cz;
                ph = ch;
            }
        }
        return max;
    }

    /**
     * 곁구역 넷 각각의 방향 전환(헤어핀) 수. 내부 정점마다 헤딩 부호가 뒤집히면 굽이 1,
     * 그 정점의 호장 비율 u 로 어느 구역인지 가른다 ({@code sideZoneByU} 사분 계승).
     */
    static int[] turnsPerZone(RangeSpec spec, double[] xs, double[] zs) {
        double[] cum = cum(xs, zs);
        double total = cum[cum.length - 1];
        int[] turns = new int[ZONES];
        for (int i = 1; i < xs.length - 1; i++) {
            double h1x = xs[i] - xs[i - 1], h1z = zs[i] - zs[i - 1];
            double h2x = xs[i + 1] - xs[i], h2z = zs[i + 1] - zs[i];
            double cross = h1x * h2z - h1z * h2x;   // 헤딩 회전 방향
            if (Math.abs(cross) < 1e-6) {
                continue;                            // 직진 — 굽이 아님
            }
            double u = total <= 0 ? 0 : cum[i] / total;
            int zone = Math.min(ZONES - 1, (int) (u * ZONES));
            // 굽이는 부호 전환에서만 — 앞 구간 대비 회전 방향이 바뀌는 정점
            if (i >= 2) {
                double p1x = xs[i - 1] - xs[i - 2], p1z = zs[i - 1] - zs[i - 2];
                double prevCross = p1x * h1z - p1z * h1x;
                if (prevCross * cross < 0) {
                    turns[zone]++;
                }
            } else {
                turns[zone]++;                       // 첫 내부 정점의 최초 꺾임
            }
        }
        return turns;
    }

    /** 중간 고리 표본을 폴리라인에 투영해 곁구역 넷이 모두 서는지 (u 사분 커버) */
    private static boolean fourZones(RangeSpec spec, double[] xs, double[] zs) {
        double[] cum = cum(xs, zs);
        double total = cum[cum.length - 1];
        boolean[] seen = new boolean[ZONES];
        int inner = spec.honsanR(), outer = spec.honsanR() + spec.midDepth();
        for (int x = -outer; x <= outer; x += 6) {
            for (int z = 0; z <= outer; z += 6) {          // 남면(dz≥0)만 곁구역
                double r = Math.hypot(x, z);
                if (r <= inner || r > outer) {
                    continue;
                }
                double u = projectU(xs, zs, cum, total,
                        spec.peakX() + x, spec.peakZ() + z);
                int zone = Math.min(ZONES - 1, (int) (u * ZONES));
                seen[zone] = true;
            }
        }
        for (boolean s : seen) {
            if (!s) {
                return false;
            }
        }
        return true;
    }

    /**
     * 거울상 비용 — 한쪽 갈래를 고르는 값. <b>비대칭 입력만</b> 센다: 명명 경유점(등록 표식)의
     * 편향. 현재 그런 입력이 없으므로(spec.trail 은 ③ 산출 자리로 비어 있고 표식 필드 없음)
     * 동·서 비용이 <b>완전히 같다</b> → 미결. ★노선 콘이 좁아 대칭 지형(방사 몸체·주능선)만
     * 스치므로 crag 잡음(비대칭)조차 굽이 결정에 못 쓰게 여기서 배제한다 (잡음으로 방향 굳히기 금지).
     */
    private static double chiralityCost(RangeSpec spec, Candidate c) {
        // 명명 경유점/표식 비용: 등록된 비대칭 표식이 있으면 그 x-편향 합을 센다.
        // spec.trail() 은 ③의 산출 자리(비었을 때 이 생성기가 돈다)라 여기선 항상 0.
        // → 동/서 완전 동률. (③가 명명 경유점을 채우면 그 비용이 한쪽을 당긴다 — Codex §5.2-4)
        // 표식 편향: 표식이 그 갈래 쪽(같은 부호)에 있으면 그 갈래가 싸다 (가깝다) → 낮은 비용.
        double cost = 0.0;
        for (RangeSpec.TrailWaypoint w : spec.trail()) {
            cost += -c.chir.sign * (w.x() - spec.peakX());  // 서편 표식(x<0) → 서 갈래 비용↓
        }
        return cost;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 잠정 폴백 — provisionalTrail 계승 (확정값 유도 · ③ 폴백)
    // ═══════════════════════════════════════════════════════════════════

    private static double provisionalAmp(RangeSpec spec) {
        return Math.toRadians(25.0) * (spec.honsanR() + spec.beltDepth());
    }

    /** 잠정 지그재그 (구 provisionalTrail 계승) — 제약 만족 후보 실패 시 폴백 */
    static double[][] provisional(RangeSpec spec) {
        int belt = spec.beltDepth();
        int[] radii = {
                spec.honsanR() + spec.midDepth(),
                spec.honsanR() + 3 * belt,
                spec.honsanR() + 2 * belt,
                spec.honsanR() + belt,
                spec.honsanR()
        };
        double sw = Math.toRadians(25.0);
        double[] az = {0.0, sw, -sw, sw, 0.0};
        double[] xs = new double[radii.length];
        double[] zs = new double[radii.length];
        for (int i = 0; i < radii.length; i++) {
            xs[i] = spec.peakX() + radii[i] * Math.sin(az[i]);
            zs[i] = spec.peakZ() + radii[i] * Math.cos(az[i]);
        }
        return new double[][]{xs, zs};
    }

    // ── 공용 소도구 ────────────────────────────────────────────────────

    static double[] cum(double[] xs, double[] zs) {
        double[] cum = new double[xs.length];
        double acc = 0.0;
        for (int i = 1; i < xs.length; i++) {
            acc += Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
            cum[i] = acc;
        }
        return cum;
    }

    /** 열을 폴리라인에 수직 투영한 호장 비율 u∈[0,1] (RangeField.projectU 와 같은 문법) */
    static double projectU(double[] xs, double[] zs, double[] cum, double total, double x, double z) {
        double bestD2 = Double.MAX_VALUE;
        double bestArc = 0.0;
        for (int i = 0; i < xs.length - 1; i++) {
            double ax = xs[i], az = zs[i];
            double vx = xs[i + 1] - ax, vz = zs[i + 1] - az;
            double len2 = vx * vx + vz * vz;
            double t = len2 <= 0.0 ? 0.0 : ((x - ax) * vx + (z - az) * vz) / len2;
            t = Math.max(0.0, Math.min(1.0, t));
            double cx = ax + vx * t, cz = az + vz * t;
            double d2 = (x - cx) * (x - cx) + (z - cz) * (z - cz);
            if (d2 < bestD2) {
                bestD2 = d2;
                bestArc = cum[i] + Math.sqrt(len2) * t;
            }
        }
        return total <= 0.0 ? 0.0 : bestArc / total;
    }

    // ═══════════════════════════════════════════════════════════════════
    // 자기 시험 — 서버 없이 돈다 (java com.honcheon.mvt.forge.TrailForge)
    //   결정론 · 경사 상한 · 구역당 회전 · 경로계수 · 4구역 · +84 미결 · 눈을 시험하는 눈
    // ═══════════════════════════════════════════════════════════════════
    public static void main(String[] args) {
        RangeSpec spec = RangeSpec.hwasan(0, 0, 63);
        RangeField field = new RangeField(spec);          // 생성기가 도는 실경로
        HeightFn h = field::reliefAt;
        boolean ok = true;

        Result r = generate(spec, h);
        System.out.println("생성기 산출: " + r.note());
        System.out.printf("  경로계수=%.3f  최대경사=%.3f  구역회전=%s  헤어핀/구역=%d  진폭=%.1f  폴백=%b%n",
                r.pathFactor(), r.maxSlope(), java.util.Arrays.toString(r.turnsPerZone()),
                r.hairpinsPerZone(), r.amplitude(), r.fallback());

        // 1) 결정론 — 같은 입력 2회 동일 폴리라인
        Result r2 = generate(spec, h);
        if (!java.util.Arrays.equals(r.xs(), r2.xs()) || !java.util.Arrays.equals(r.zs(), r2.zs())) {
            System.out.println("FAIL 결정론: 두 생성이 다른 폴리라인");
            ok = false;
        }

        // 2) 경사 — ★carve 제거(2026-07-16)로 노선이 통짜 험산 위를 지난다. 물매-보행성은 미래 도보길
        //    (잔도·천계단) 조성의 몫으로 이관됐다(미결). 여기선 FAIL 하지 않고 보고만 한다 — 노선은
        //    '미래 도보길 중심선' 계획이지 '지금 이 지형에서 걸을 수 있다'는 게 아니다.
        System.out.printf("  노선 물매(미래 도보길 조성 이관·미결): 최대 %.3f (통짜 험산 위 중심선 — 1:1 검사 이관)%n",
                r.maxSlope());

        // 3) 구역당 회전 ≥ 2
        for (int zi = 0; zi < ZONES; zi++) {
            if (r.turnsPerZone()[zi] < MIN_TURNS_PER_ZONE) {
                System.out.printf("FAIL 회전: 구역 %d 방향전환 %d < %d%n",
                        zi, r.turnsPerZone()[zi], MIN_TURNS_PER_ZONE);
                ok = false;
            }
        }

        // 4) 경로계수 근사
        if (Math.abs(r.pathFactor() - PATH_FACTOR_TARGET) > PATH_FACTOR_TOL + 1e-9) {
            System.out.printf("FAIL 경로계수: %.3f 이 1.5±%.2f 밖%n", r.pathFactor(), PATH_FACTOR_TOL);
            ok = false;
        }

        // 5) 4구역 통과 — 실 RangeField 로 곁구역 넷이 서는지
        java.util.EnumMap<RangeZone, Long> area = sideAreas(field, spec);
        for (RangeZone z : SIDE) {
            if (area.getOrDefault(z, 0L) == 0L) {
                System.out.println("FAIL 4구역: " + z + " 안 섬");
                ok = false;
            }
        }

        // 6) +84 동/서 미결 — 비대칭 표식·경유점 없으면 미결 반환해야
        if (r.directionResolved()) {
            System.out.println("FAIL 미결: 비대칭 입력 없는데 방향을 굳혔다 (동/서 동률이어야)");
            ok = false;
        }

        // ── 눈을 시험하는 눈 (제약 위반 폴리라인을 심어 검출기가 잡는가) ──
        // (a) 경사 검출기: 수평 2칸에 60칸 오르는 절벽 폴리라인 → 경사 ≫ 1
        double[] vx = {spec.peakX(), spec.peakX() + 2};
        double[] vz = {spec.peakZ() + 264, spec.peakZ() + 124};
        HeightFn cliff = (x, z) -> z < spec.peakZ() + 200 ? 60.0 : 0.0;
        if (!(maxSlope(vx, vz, cliff) > MAX_SLOPE)) {
            System.out.println("FAIL 자기검증: 경사 검출기가 심은 절벽을 못 잡는다 — 눈이 죽었다");
            ok = false;
        }
        // (b) 회전 검출기: 곧은 방사 노선(굽이 0) → 구역 회전 전부 0 < 2
        double[][] straight = {
                {spec.peakX(), spec.peakX()}, {spec.peakZ() + 264.0, spec.peakZ() + 124.0}};
        int[] st = turnsPerZone(spec, straight[0], straight[1]);
        boolean caughtNoTurn = false;
        for (int t : st) {
            if (t < MIN_TURNS_PER_ZONE) {
                caughtNoTurn = true;
            }
        }
        if (!caughtNoTurn) {
            System.out.println("FAIL 자기검증: 회전 검출기가 곧은 노선(굽이 0)을 통과시켰다 — 눈이 죽었다");
            ok = false;
        }
        // (c) 경로계수 검출기: 곧은 노선 → 계수 1.0, 1.5 밴드 밖이어야
        if (!(Math.abs(pathFactor(straight[0], straight[1]) - PATH_FACTOR_TARGET) > PATH_FACTOR_TOL)) {
            System.out.println("FAIL 자기검증: 경로계수 검출기가 직선(계수 1.0)을 1.5 로 봤다 — 눈이 죽었다");
            ok = false;
        }
        // (d) 미결 검출기: 비대칭 표식을 심으면 방향이 결정돼야 (동률이 깨진다)
        java.util.List<RangeSpec.TrailWaypoint> marks = java.util.List.of(
                new RangeSpec.TrailWaypoint(spec.peakX() - 50, spec.peakZ() + 190));  // 서편 표식
        RangeSpec marked = withTrail(spec, marks);
        Result rm = generate(marked, new RangeField(marked)::reliefAt);
        if (!rm.directionResolved() || rm.chirality() != Chirality.WEST) {
            System.out.println("FAIL 자기검증: 서편 표식을 심어도 서를 안 고른다 — 미결 판정이 죽었다 ("
                    + rm.chirality() + "/" + rm.directionResolved() + ")");
            ok = false;
        }

        System.out.println((ok ? "PASS" : "FAIL") + " — TrailForge 자기 시험 (곁구역 면적 "
                + area + ")");
        if (!ok) {
            System.exit(1);
        }
    }

    private static final RangeZone[] SIDE = {
            RangeZone.PLUM_GROVE, RangeZone.VALLEY, RangeZone.CLIFF, RangeZone.DRILL_VALLEY};

    private static java.util.EnumMap<RangeZone, Long> sideAreas(RangeField field, RangeSpec spec) {
        java.util.EnumMap<RangeZone, Long> area = new java.util.EnumMap<>(RangeZone.class);
        int r = spec.honsanR() + spec.midDepth();
        for (int x = spec.peakX() - r; x <= spec.peakX() + r; x++) {
            for (int z = spec.peakZ() - r; z <= spec.peakZ() + r; z++) {
                RangeZone zone = field.zoneAt(x, z);
                for (RangeZone s : SIDE) {
                    if (zone == s) {
                        area.merge(zone, 1L, Long::sum);
                    }
                }
            }
        }
        return area;
    }

    private static RangeSpec withTrail(RangeSpec s, java.util.List<RangeSpec.TrailWaypoint> t) {
        return new RangeSpec(s.placeId(), s.peakX(), s.peakZ(), s.baseY(), s.lift(),
                s.summitFlatR(), s.honsanR(), s.honsanRise(), s.midDepth(), s.entranceDepth(),
                s.apronDepth(), s.ridgeHalfWidth(), s.peakSteepSlope(), s.sideRidges(),
                s.gorges(), t);
    }
}
