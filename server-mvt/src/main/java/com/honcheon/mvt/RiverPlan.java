package com.honcheon.mvt;

/**
 * 물길의 <b>산술</b> — 강이 어디를 지나고, 어디까지 깊고, 어느 높이로 흐르는가.
 *
 * <p><b>이 클래스에는 Bukkit 이 없다.</b> 그것이 설계다 — 강의 참·거짓은 블록을 놓기 전에
 * 정해진다. 서버 없이 시험할 수 있어야 눈을 시험할 수 있고, 눈을 시험할 수 없으면
 * <b>눈이 거짓말하는지 알 수 없다</b> (이 프로젝트에서 열일곱 번 그랬다).
 * 블록을 놓는 일은 {@link RiverForge} 의 것이고, 그것을 검사하는 일은 {@link RiverAudit} 의 것이다.
 * 셋 다 <b>여기 있는 하나의 산술</b>을 본다 — 정본은 하나다.
 *
 * <h2>강의 다섯 가지 참 (이것이 검수의 잣대가 된다)</h2>
 * <ol>
 *   <li><b>흐른다</b> — 수면 y 는 하류로 갈수록 <b>낮아지기만 한다</b> (같거나 낮다. 절대 높아지지 않는다).
 *       그리고 한 굽이에서 적어도 한 칸은 <b>실제로</b> 떨어진다 — 떨어지지 않으면 그건 못이다</li>
 *   <li><b>끊기지 않는다</b> — 상류 끝에서 하류 끝까지 물로 이어진다</li>
 *   <li><b>새지 않는다</b> — 하상 아래 여섯 칸은 단단하다 (계약 ①). 물가는 수면보다 높다</li>
 *   <li><b>배가 다닌다</b> — 물길 한가운데(thalweg)의 수심이 등록된 깊이 이상이다</li>
 *   <li><b>골짜기에 있다</b> — 강가가 수직 절벽이 아니다. 둔치가 자연 지형으로 <b>수렴한다</b></li>
 * </ol>
 *
 * <h2>결정론</h2>
 * 난수가 없다. 굽이는 <b>저주파 사인 둘</b>이다 (좌표 해시조차 쓰지 않는다 —
 * 파장 한 칸짜리 요철은 결이 아니라 <b>노이즈</b>이고, 산이 '점무늬'가 된 병이 바로 그것이었다).
 */
final class RiverPlan {

    /**
     * 젖은 열 — 그 열은 땅이 아니라 물이다.
     *
     * <p>{@code TerrainForge.WET_COLUMN} 과 <b>같은 약속</b>이다. 그 상수가 private 이라 값으로 계승한다
     * (두 값이 갈라지면 강이 땅으로 읽힌다 — 그러면 마을이 강 위에 선다).
     */
    static final int WET = Integer.MIN_VALUE;

    /** 하상 아래 이만큼은 단단하다 — {@code TerrainForge.SEAL_DEPTH} 와 같은 약속 (계약 ①) */
    static final int SEAL_DEPTH = 6;

    /** 땅의 표본 — 강은 <b>지금 거기 있는 지면</b>을 보고 골짜기를 판다 */
    interface Ground {

        /** 그 열의 자연 지면 y. 물이면 {@link #WET} */
        int y(int x, int z);
    }

    /**
     * 등록부가 적어 준 한 물길 ({@code config/rivers.yml}).
     *
     * @param ux                흐름 단위벡터 x (동 = +1)
     * @param uz                흐름 단위벡터 z (남 = +1)
     * @param halfWidth         수면 반폭
     * @param depth             물길 한가운데 수심
     * @param gradient          하상이 한 칸 내려가는 데 걸리는 물길 거리(칸)
     * @param axisOffset        물길 중심선이 부지 중심에서 옆으로 비켜난 거리 (양수 = 흐름의 오른쪽)
     * @param surfaceBelowGround 수면이 조성 지면보다 이만큼 아래
     * @param valley            둔치 폭 — 물가에서 자연 지형으로 수렴하는 거리
     * @param margin            물길이 부지 반경 밖으로 더 뻗는 거리
     */
    record Spec(String placeId, String name, int ux, int uz,
                int halfWidth, int depth, int gradient, int axisOffset,
                int surfaceBelowGround, int valley, int margin,
                int meanderAmp, int meanderLen, String bankMaterial,
                int wetland) {

        /** 축만 뒤집은 사본 — 물길은 물리적으로 <b>같은 쪽</b>에 남는다 (offset 부호도 함께 뒤집는다) */
        Spec reversed() {
            return new Spec(placeId, name, -ux, -uz, halfWidth, depth, gradient, -axisOffset,
                    surfaceBelowGround, valley, margin, meanderAmp, meanderLen, bankMaterial, wetland);
        }
    }

    /** 하상을 파 내려갈 수 있는 최대 깊이 (조성 지면 기준) — 골짜기가 무한정 깊어지지 않게 */
    static final int MAX_CUT = 24;

    private final Spec spec;
    private final int cx;
    private final int cz;
    private final int radius;
    private final int groundY;

    /** 물길 전체 길이 — 부지 지름 + 양쪽 여유. s 는 0(상류 끝)에서 length(하류 끝)까지 */
    private final int length;

    /**
     * <b>수면의 세로 단면</b> — {@code surface[s]} 가 그 지점의 수면 y 다.
     *
     * <p>★ 구판은 이것이 <b>상수 하나</b>였다 ({@code groundY - 2} 에서 gradient 만큼 계단으로 내려감).
     * 평지에서는 맞았다. 그런데 인게임의 실제 부지는 <b>기복 32칸</b>이었고,
     * 상수 수면은 어떤 데서는 땅속 30칸에 있고 어떤 데서는 <b>언덕 위 공중</b>에 있었다.
     * <b>강은 땅을 무시하고 그을 수 있는 선이 아니다.</b>
     *
     * <p>지금은 <b>땅이 정한다</b> (아래 {@link #profile}).
     */
    private final int[] surface;

    /**
     * 산술을 짓는다. <b>{@code ground} 를 주면 땅이 수면을 정한다</b> (조성기가 부른다).
     * 주지 않으면 기하(幾何)만 쓴다 — 중심선·폭·물길 마스크는 땅과 무관하고,
     * <b>검수는 그 기하만 쓴다</b> (검수는 계획의 수면을 믿지 않는다. <b>거기 있는 물을 잰다</b>).
     */
    RiverPlan(Spec spec, int cx, int cz, int radius, int groundY, Ground ground) {
        this.spec = spec;
        this.cx = cx;
        this.cz = cz;
        this.radius = radius;
        this.groundY = groundY;
        this.length = 2 * (radius + spec.margin());
        this.surface = ground == null ? null : profile(ground);
    }

    /** 기하만 — 검수용 (땅을 다시 재지 않는다. 조성이 이미 땅을 바꿔 놓았으므로 재면 <b>딴 답</b>이 나온다) */
    RiverPlan(Spec spec, int cx, int cz, int radius, int groundY) {
        this(spec, cx, cz, radius, groundY, null);
    }

    /**
     * <b>수면을 땅에게 묻는다</b> — §19-F: <i>"강이 높은 곳에서 낮은 곳으로 흐르는가?"</i>
     *
     * <p>중심선을 따라 땅을 훑고, 그 위에 <b>단조 비증가</b>인 수면을 얹는다:
     * <pre>
     *   h[s]  = 그 자리의 땅 − 여유(freeboard)          ← 강은 땅보다 낮다
     *   ws[s] = min( ws[s-1],           ← 절대 올라가지 않는다 (그것이 '흐른다'의 정의다)
     *                h[s],              ← 땅 위로 뜨지 않는다
     *                ws[0] − s/기울기 ) ← 반드시 떨어진다 (안 떨어지면 그건 못이다)
     * </pre>
     * 셋 다 s 에 대해 비증가이므로 <b>그 최소도 비증가다</b> — 역류는 <b>산술적으로 불가능</b>하다.
     * 땅이 하류에서 솟으면 그만큼 <b>더 깊이 판다</b> (협곡이 된다). 그것이 강이 하는 일이다.
     */
    private int[] profile(Ground ground) {
        int[] ws = new int[length + 1];
        int floor = groundY - MAX_CUT;
        int head = reference(ground, 0);
        ws[0] = head;
        for (int s = 1; s <= length; s++) {
            int cap = head - Math.floorDiv(s, spec.gradient());   // 반드시 떨어진다
            int here = reference(ground, s);
            ws[s] = Math.max(floor, Math.min(Math.min(ws[s - 1], here), cap));
        }
        return ws;
    }

    /** 그 지점에서 수면이 넘볼 수 있는 <b>최고 높이</b> — 땅보다 {@code surfaceBelowGround} 만큼 낮다 */
    private int reference(Ground ground, int s) {
        int[] p = pointOnCenterline(s);
        int g = ground.y(p[0], p[1]);
        if (g == WET) {
            g = groundY;   // 자연의 물 — 그 높이는 조성기가 따로 본다 (거절 검사)
        }
        return g - spec.surfaceBelowGround();
    }

    /** 중심선 위의 세계 좌표 (하류 거리 s 에서) */
    int[] pointOnCenterline(int s) {
        int along = s - length / 2;
        int l = (int) Math.round(centerline(s));
        return new int[]{
                cx + spec.ux() * along + (-spec.uz()) * l,
                cz + spec.uz() * along + spec.ux() * l};
    }

    /**
     * <b>땅이 어느 쪽으로 낮아지는가</b> — 흐름의 방향을 여기서 정한다.
     *
     * <p>등록부는 "장강은 서에서 동으로 흐른다"고 적는다. 그것은 <b>실지리</b>다.
     * 그러나 바닐라가 그 좌표에 놓아 준 땅은 <b>동쪽이 높을 수도 있다</b>.
     * 등록부를 따라 오르막으로 강을 내면 그것은 강이 아니라 <b>수로교</b>다.
     *
     * <p>그래서 <b>땅에게 묻는다</b>: 등록된 방향의 상류 1/4 과 하류 1/4 의 평균 지면을 재서,
     * 하류가 더 높으면 <b>축을 뒤집는다</b>. 이때 {@code axisOffset} 의 부호도 함께 뒤집어
     * <b>물길이 물리적으로 같은 쪽에 남게</b> 한다 (마을이 앉을 뭍이 반대편으로 튀지 않도록).
     *
     * @return 땅이 요구하는 Spec (뒤집혔거나, 그대로거나)
     */
    static Spec faceDownhill(Spec spec, int cx, int cz, int radius, int groundY, Ground ground) {
        RiverPlan probe = new RiverPlan(spec, cx, cz, radius, groundY);
        int n = probe.length() / 4;
        long up = 0;
        long down = 0;
        int cu = 0;
        int cd = 0;
        for (int s = 0; s <= probe.length(); s += 2) {
            int[] p = probe.pointOnCenterline(s);
            int g = ground.y(p[0], p[1]);
            if (g == WET) {
                g = groundY;
            }
            if (s <= n) {
                up += g;
                cu++;
            } else if (s >= probe.length() - n) {
                down += g;
                cd++;
            }
        }
        if (cu == 0 || cd == 0) {
            return spec;
        }
        int headY = (int) (up / cu);
        int footY = (int) (down / cd);
        if (footY <= headY + 1) {
            return spec;   // 등록된 방향이 이미 내리막이다 — 지리가 옳았다
        }
        // 하류가 더 높다 — 땅이 등록부를 반박한다. 뒤집는다 (물은 지리를 모른다. 중력만 안다)
        return spec.reversed();
    }

    // ═══════════════════════════════════════════════════════════════════
    // 범람원(氾濫原) — <b>강가의 땅은 들판이 아니다</b>
    // ═══════════════════════════════════════════════════════════════════

    /**
     * <b>젖은 땅인가</b> — 물가에서 {@code wetland} 칸까지. 여기는 풀밭이 아니라 <b>진창</b>이다.
     *
     * <p>강을 파 놓고 그 옆을 잔디로 두면 <b>물길만 난 잔디밭</b>이다. 강은 제 옆의 땅을 바꾼다 —
     * 물이 드나들고, 흙이 젖고, 갈대가 선다. <b>그것까지가 강이다.</b>
     *
     * <p>등록부가 폭을 정한다 ({@code wetland: 0} 이면 범람원이 없다 — 협곡의 강은 둔치가 없다).
     */
    boolean inWetland(int x, int z) {
        if (spec.wetland() <= 0 || !inBank(x, z)) {
            return false;
        }
        int s = downstream(x, z);
        return distanceFromCenterline(x, z) <= halfWidthAt(s) + spec.wetland();
    }

    /**
     * 범람원의 <b>결</b> — 물가에 가까울수록 진창이다. 0(마른 땅) ~ 3(물가의 진흙).
     *
     * <p>결정론: 저주파 사인 둘. <b>좌표 해시(파장 1칸)는 쓰지 않는다</b> —
     * 그건 결이 아니라 노이즈이고, 산을 '점무늬'로 만든 병이 바로 그것이었다.
     */
    int wetness(int x, int z) {
        if (!inWetland(x, z)) {
            return 0;
        }
        int s = downstream(x, z);
        double d = distanceFromCenterline(x, z) - halfWidthAt(s);
        double t = 1 - Math.min(1, d / Math.max(1, spec.wetland()));   // 1 = 물가, 0 = 범람원 끝
        // 결 — 물가선이 자로 그은 듯 곧지 않다 (파장 23·37칸)
        double grain = 0.18 * Math.sin(2 * Math.PI * s / 23.0) + 0.12 * Math.sin(2 * Math.PI * s / 37.0 + 1.7);
        double v = t + grain;
        if (v > 0.72) {
            return 3;   // 진창 — 물이 드나드는 자리
        }
        if (v > 0.42) {
            return 2;   // 젖은 흙
        }
        return v > 0.15 ? 1 : 0;
    }

    Spec spec() {
        return spec;
    }

    int length() {
        return length;
    }

    int groundY() {
        return groundY;
    }

    int radius() {
        return radius;
    }

    int centerX() {
        return cx;
    }

    int centerZ() {
        return cz;
    }

    /** 물길이 손대는 반경 — 부지 밖까지 뻗는다 (조성기가 청크를 실을 범위를 여기서 안다) */
    int reachRadius() {
        return radius + spec.margin();
    }

    // ═══════════════════════════════════════════════════════════════════
    // 좌표계 — 흐름을 축으로 삼는다
    // ═══════════════════════════════════════════════════════════════════
    //
    //   s (downstream) = 상류 끝에서 하류로 잰 거리.  0 ≤ s ≤ length
    //   d (lateral)    = 물길 중심선에서 옆으로 잰 거리 (양수 = 흐름의 오른쪽)
    //
    // 흐름의 오른쪽은 u 를 +90° 돌린 것이다: v = (-uz, ux).
    //   동(+x)으로 흐르면 오른쪽은 남(+z) — 강을 등지고 하류를 보면 오른손 쪽이다.

    /** 하류 거리 s — 상류 끝이 0 */
    int downstream(int x, int z) {
        return (x - cx) * spec.ux() + (z - cz) * spec.uz() + length / 2;
    }

    /** 축에서의 측방 거리 (부호 있음. 양수 = 흐름의 오른쪽) */
    int lateral(int x, int z) {
        return (x - cx) * (-spec.uz()) + (z - cz) * spec.ux();
    }

    /**
     * 물길 중심선의 측방 위치 — <b>강은 자로 그은 듯 곧지 않다</b>.
     *
     * <p>저주파 사인 <b>둘</b>. 파장이 길어야 굽이가 되고, 짧으면 톱니가 된다.
     */
    double centerline(int s) {
        double a = 2 * Math.PI * s / spec.meanderLen();
        double b = 2 * Math.PI * s / (spec.meanderLen() * 0.41) + 0.9;
        return spec.axisOffset() + spec.meanderAmp() * Math.sin(a) + spec.meanderAmp() * 0.45 * Math.sin(b);
    }

    /** 그 자리의 반폭 — 강은 넓어졌다 좁아졌다 한다 (역시 저주파다) */
    int halfWidthAt(int s) {
        double m = Math.sin(2 * Math.PI * s / (spec.meanderLen() * 0.6) + 2.1);
        return Math.max(3, (int) Math.round(spec.halfWidth() * (1 + 0.12 * m)));
    }

    /** 물길 중심선에서의 거리 (부호 없음) */
    double distanceFromCenterline(int x, int z) {
        return Math.abs(lateral(x, z) - centerline(downstream(x, z)));
    }

    // ═══════════════════════════════════════════════════════════════════
    // 수직 — 참 ①: 높은 데서 낮은 데로
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 그 자리의 <b>수면</b> y. — <b>s 에 대해 단조 비증가</b>다. 그것이 '흐른다'의 정의다.
     *
     * <p>계단으로 내려간다 (매 {@code gradient} 칸마다 한 켜). 마인크래프트의 물은 한 켜 안에서
     * <b>평평하다</b> — 수면을 비스듬히 놓을 수는 없다. 그러니 강은 <b>층계로</b> 흐른다.
     */
    int waterY(int s) {
        if (surface == null) {
            // 기하 전용 (검수) — 계획 수면이 없다. 검수는 이 값을 쓰지 않고 <b>거기 있는 물을 잰다</b>
            return groundY - spec.surfaceBelowGround();
        }
        return surface[Math.max(0, Math.min(length, s))];
    }

    /** 한 굽이에서 수면이 떨어지는 총 낙차 — 0 이면 그것은 강이 아니라 못이다 (검수가 잰다) */
    int totalFall() {
        return waterY(0) - waterY(length);
    }

    /**
     * 중심선에서 d 만큼 떨어진 자리의 <b>수심</b>.
     *
     * <p>반타원 단면이다 — 한가운데가 깊고 물가로 갈수록 얕아져 <b>0 으로 닿는다</b>.
     * 직사각 단면(수직 벽)은 강이 아니라 <b>수로(canal)</b>다.
     */
    int depthAt(int s, double d) {
        int hw = halfWidthAt(s);
        if (d >= hw) {
            return 0;
        }
        double t = d / hw;
        return (int) Math.round(spec.depth() * Math.sqrt(Math.max(0, 1 - t * t)));
    }

    /** 그 자리의 <b>하상</b> y (강바닥). 물이 없는 자리면 수면과 같다 */
    int bedY(int s, double d) {
        return waterY(s) - depthAt(s, d);
    }

    // ═══════════════════════════════════════════════════════════════════
    // 마스크 — 어디가 물이고 어디가 둔치인가
    // ═══════════════════════════════════════════════════════════════════

    /** 물길의 사정권 안인가 (물 + 둔치) — 이 밖은 손대지 않는다 */
    boolean inReach(int x, int z) {
        int s = downstream(x, z);
        if (s < 0 || s > length) {
            return false;
        }
        return distanceFromCenterline(x, z) <= halfWidthAt(s) + spec.valley();
    }

    /** <b>물</b>인가 — 여기는 수심이 한 칸 이상이다 */
    boolean inChannel(int x, int z) {
        int s = downstream(x, z);
        if (s < 0 || s > length) {
            return false;
        }
        return depthAt(s, distanceFromCenterline(x, z)) >= 1;
    }

    /** <b>둔치</b>인가 — 물은 아니지만 물가라서 땅을 다듬는 자리 */
    boolean inBank(int x, int z) {
        return inReach(x, z) && !inChannel(x, z);
    }

    /**
     * 둔치의 <b>목표 지면</b> y — 참 ⑤(골짜기에 있다)와 참 ③(새지 않는다)이 여기서 만난다.
     *
     * <ul>
     *   <li>물가(d = hw)에서는 <b>수면보다 한 칸 위</b> — 물이 넘지 않는 최소한의 둑</li>
     *   <li>둔치 바깥(d = hw + valley)에서는 <b>자연 지형 그대로</b> — 그래야 벼랑이 안 생긴다</li>
     *   <li>그 사이는 부드럽게 잇는다 (smoothstep — 직선으로 이으면 이음매가 각진다)</li>
     *   <li><b>수면보다 낮출 수는 없다</b> — 낮추면 강이 옆으로 샌다 (강은 스스로 둑을 쌓는다)</li>
     * </ul>
     *
     * <p><b>자연이 이미 물이면 손대지 않는다</b> ({@link #WET} 를 돌려준다) —
     * 바닐라의 호수·바다가 거기 있으면 그것이 <b>이 강의 하구</b>다. 하구를 메우면 강이 막힌다.
     *
     * @param naturalY 그 열의 현재 자연 지면 ({@link #WET} 이면 이미 물)
     * @return 목표 지면 y. {@link #WET} 이면 <b>건드리지 말라</b>는 뜻
     */
    int bankTargetY(int x, int z, int naturalY) {
        if (naturalY == WET) {
            return WET;   // 자연의 물 — 강이 여기서 바다를 만난다. 메우지 않는다
        }
        int s = downstream(x, z);
        int hw = halfWidthAt(s);
        double d = distanceFromCenterline(x, z);
        int levee = waterY(s) + 1;                       // 물이 넘지 않는 최소 높이

        double t = (d - hw) / (double) spec.valley();    // 0 = 물가, 1 = 둔치 끝
        t = Math.max(0, Math.min(1, t));
        double w = t * t * (3 - 2 * t);                  // smoothstep — 자연으로 부드럽게 수렴한다

        int target = (int) Math.round(levee * (1 - w) + naturalY * w);
        return Math.max(target, levee);                  // 절대 수면 아래로 낮추지 않는다 (참 ③)
    }

    /** 그 자리에서 <b>하늘이 열려야 하는</b> 높이 — 강 위에 천장이 있으면 그건 굴이지 강이 아니다 */
    int clearanceTop(int s) {
        return waterY(s) + 8;
    }
}
