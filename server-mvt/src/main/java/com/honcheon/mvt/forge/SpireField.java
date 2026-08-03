package com.honcheon.mvt.forge;

import java.util.List;

/**
 * 산군(山群) 필드 — <b>화산파 내외의 산을 전부 세우는 순수 높이장</b> (슬라이스 6).
 *
 * <p>★근거: 사용자 확정 (2026-08-03) — 「아무리 봐도 산이 없다. 화산파 내외의 산들이 전부
 * 구성되어야 그 느낌이 산다.」 수치는 전부 실측표
 * ({@code docs/design/hwasan_block_measurements.md} §4 — 임의 금지)에서 온다:
 * 배후봉 = 정상단 위 +80 · 근경 스파이어 = 가장자리 8~30칸 · 폭:높이 1:4~1:8 · 켜 3.
 *
 * <p>★RangeSpec(lift 160)·C 골격은 <b>무변경</b> — 캠퍼스 표고 계약(p85 사슬 46~148)을
 * 보존하기 위해 산군은 기존 지형 위에 <b>max 합성 오버레이</b>로 얹는다. H-확정값과의
 * 충돌은 사용자 지시가 이긴다 (개정이 아니라 오버레이로 화해 — 골격 밑은 그대로다).
 *
 * <p>순수·결정론(난수 0 — 해시 섞음)·Bukkit 무의존 — 눈({@code TerraceForgeSelfTest})이
 * 그대로 잰다. 캠퍼스 패드·계단·다리 발자국(+여유)은 제외 목록으로 받는다 —
 * <b>산이 사람의 것을 침범하면 그것은 산이 아니라 사고다.</b>
 */
public final class SpireField {

    /** 스파이어 셀 한 변 — 침봉 하나가 사는 격자 */
    public static final int CELL = 26;

    /** 산군 바깥 한계 (방사) — 원경 켜의 끝 */
    public static final int FIELD_R = 1000;   // ★8.5 — 산체 재척도 동행 (켜 3: 200~430~700~1000)

    /** 본산권 안쪽 한계 — 이 안은 골격(캠퍼스의 산)의 것, 산군은 손대지 않는다 */
    public static final int INNER_R = 200;

    private static final long SALT = 0x5A9_F1E1DL;

    /**
     * ★기준면(baseY) 실측점 — <b>산군 필드(±{@value #FIELD_R}) 밖 + 여유</b>.
     * 6.0 실기동의 병: 실측점 (600,0)이 필드 안이라 침봉 마루 위에서 기준을 재
     * 캠퍼스가 54칸 떠서 앉았다. 산세·산군·캠퍼스·도보길이 <b>전부 이 점 하나</b>를 쓴다 —
     * 각자 좌표를 들면 다음 반경 확장 때 또 갈라진다.
     */
    public static final int PROBE_X = FIELD_R + 180;   // 800 — FLAT 표면 보장 구역
    public static final int PROBE_Z = 0;

    /** 실측점이 산군 어느 성분(배후봉·침봉)에도 안 덮이는가 — 계획 단계 순수 가드 */
    public static boolean probeUntouched() {
        SpireField bare = new SpireField(List.of());
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (bare.targetH(PROBE_X + dx, PROBE_Z + dz) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 배후봉 증고 — 실측표 §4: 정상단(148) 위 +80 이 주봉. 곁봉도 캠퍼스 위로.
     *
     * <p>★6.7 형태 재실측 (§4-b — s7 촬영 「매끈한 원뿔」 판정의 처방): 원뿔이 아니라
     * <b>병풍꼴 능선봉</b>이다 — 마루가 점이 아니라 장축 방향 짧은 능선(선분)이고,
     * 한쪽은 급벽(반경 ×0.80)·반대쪽은 완사(×1.15) 비대칭이며, 마루선은 요철(파임 0~5)로
     * 울퉁불퉁하다. 세로 홈(방위 로브)이 몸을 가른다. ★계약: <b>선분 중심(cx,cz)의 높이는
     * 정확히 topH</b> — 눈(selftest ④-d)과 산군시험 finish 검수가 이 점을 잰다.
     *
     * @param topH    목표 마루 높이 (baseY 위) — 선분 중심에서 정확히 이 값
     * @param r       횡 기준 반경 (급벽 쪽 ×0.80 · 완사 쪽 ×1.15)
     * @param axisDeg 능선 장축 방위 — 남축(+z) 기준 시계 (C 골격 Peak 과 같은 결)
     * @param len     마루 능선 길이 (실측 §4-b: 2~10)
     */
    public record Ridge(String id, int cx, int cz, int topH, int r, double axisDeg, int len) {
    }

    /**
     * 배후봉 넷 — 골격 Peak 자리·장축 방위 그대로, 높이는 실측 비로 (실측표 §4·§4-b).
     * ★슬라이스 8: 캠퍼스 표고 사슬이 정상단 148→170 으로 커져 (사용자 기준자 — 계단 20)
     * 산의 우위(+60~100)를 지키려 배후봉을 함께 올렸다 — Pm 250 = 정상단 170 위 +80.
     */
    public static List<Ridge> backPeaks() {
        // ★8.5 — 골격 재척도 동행: 자리 = 새 skelPeaks · 높이 = 골격 마루 위 +8 (크랙 결 몫 ·
        //   Pm 265 = 정상단 170 + 95 — 산의 우위 실측 창 +60~100 안)
        return List.of(
                new Ridge("Pm", -50, -113, 265, 90, 90, 16),
                new Ridge("Em", 130, -97, 255, 70, 116, 12),
                new Ridge("Wm", -218, -34, 250, 76, 60, 12),
                new Ridge("Es", 206, -38, 215, 52, 120, 6));
    }

    private final List<int[]> exclusions;

    /** @param exclusions 침범 금지 사각들 [x0,x1,z0,z1] — 캠퍼스 패드·계단·다리 발자국 + 여유 */
    public SpireField(List<int[]> exclusions) {
        this.exclusions = List.copyOf(exclusions);
    }

    /** 그 열이 침범 금지 사각 안인가 — ★11 식생 상이 같은 계약을 쓴다 */
    public boolean excluded(int x, int z) {
        for (int[] e : exclusions) {
            if (x >= e[0] && x <= e[1] && z >= e[2] && z <= e[3]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 그 열의 산군 목표 높이 (baseY 위 · 0 = 손대지 않음). 조성은
     * {@code max(기존 지형, baseY + targetH)} 로 얹는다 — 감산 없음 (협곡·골 보존).
     */
    public int targetH(int x, int z) {
        for (int[] e : exclusions) {
            if (x >= e[0] && x <= e[1] && z >= e[2] && z <= e[3]) {
                return 0;
            }
        }
        int best = baseRelief(x, z);                    // ⓪ 저지 릴리프 — 광봉 사이를 채운다
        for (Ridge c : backPeaks()) {                   // ① 배후봉 — 병풍꼴 능선봉 (§4-b)
            best = Math.max(best, ridgeH(c, x, z));
        }
        int gX = Math.floorDiv(x, GCELL);               // ② ★광봉(廣峰) 켜 — 구도의 주인 (슬라이스 10.5)
        int gZ = Math.floorDiv(z, GCELL);
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                best = Math.max(best, broadAt(gX + i, gZ + j, x, z));
            }
        }
        int cellX = Math.floorDiv(x, CELL);             // ③ 침봉 — 장식으로 강등 (광봉 위 소수)
        int cellZ = Math.floorDiv(z, CELL);
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                best = Math.max(best, spireAt(cellX + i, cellZ + j, x, z));
            }
        }
        return best;
    }

    // ═══════════════════════════════════════════════════════════════════
    // ★광봉(廣峰) 켜 — 슬라이스 10.5 구도 반전 (사용자 재판정 「아직 기둥 밭」의 처방).
    //   재실측 (실측표 §12 — 8·12호): 산의 주인공은 **넓은 몸통의 봉우리**다 —
    //   밑변:높이 ≈ 1:0.8~1.5 · 계단진 절벽면 · 능선·안부로 이어진 매시프. 원작 12호는
    //   독립 침봉이 사실상 0. 세장 침봉은 그 위 소수의 장식일 뿐이다 (개수비 ≥4:1).
    //   문법: 배후봉의 병풍 능선({@link #ridgeH})을 절차 생성판으로 재사용 — 셀 해시가
    //   자리·밑변(60~120)·높이(90~165)·장축·능선 길이를 정한다.
    // ═══════════════════════════════════════════════════════════════════

    /** 광봉 격자 한 변 — 능선 도달 반경(≤~92)이 이웃 ±1 스캔 안에 들도록 */
    public static final int GCELL = 128;

    /** 광봉이 미치는 끝 — 원경(650+)은 운해 위 침봉 실루엣의 몫 */
    public static final int BROAD_END = 650;

    private int broadAt(int gX, int gZ, int x, int z) {
        long h = mix(SALT ^ 0xB40ADL, gX, 0, gZ);
        if (Math.floorMod(h, 100) >= 85) {
            return 0;                                    // 이 격자엔 광봉이 없다 (밀도 85%)
        }
        int cx = gX * GCELL + 20 + (int) Math.floorMod(h >> 8, GCELL - 40);
        int cz = gZ * GCELL + 20 + (int) Math.floorMod(h >> 16, GCELL - 40);
        int r = 30 + (int) Math.floorMod(h >> 32, 31);       // 밑변 60~120 (실측 §12 — 1:0.8~1.5)
        int len = 12 + (int) Math.floorMod(h >> 48, 29);     // 능선 길이 12~40 — 매시프로 이어진다
        if (Math.abs(x - cx) > (len / 2 + r) + r / 3 || Math.abs(z - cz) > (len / 2 + r) + r / 3) {
            return 0;                                        // 값싼 상자 탈출 — ridgeH 전에
        }
        double dist = Math.hypot(cx, cz);
        if (dist < INNER_R || dist >= BROAD_END || inCorridor(cx, cz)) {
            return 0;
        }
        int lo = dist < 430 ? 105 : 90;
        int hi = dist < 430 ? 165 : 140;
        int topH = lo + (int) Math.floorMod(h >> 24, hi - lo + 1);
        double axis = Math.floorMod(h >> 40, 180);
        int hh = ridgeH(new Ridge("광봉", cx, cz, topH, r, axis, len), x, z);
        return corridorFaded(hh, x, z);
    }

    /**
     * 회랑 소멸 — 시야 회랑(8.8)을 지나는 광봉 몸은 45~85 띠에서 매끄럽게 죽는다
     * (수직 절단이 아니라 골짜기 벽 — 시선을 축선으로 이끈다).
     */
    private static int corridorFaded(int h, int x, int z) {
        if (h <= 0 || z < 340) {
            return h;
        }
        double a = Math.abs(x + 4.0);
        if (a >= 85) {
            return h;
        }
        if (a <= 45) {
            return 0;
        }
        return (int) (h * (a - 45) / 40.0);
    }

    // ═══════════════════════════════════════════════════════════════════
    // ★산몸 (基底 릴리프) — 슬라이스 10-① (사용자 재지시: 「그냥 기둥이지 산이 아니다.
    //   산부터 완성시켜라」). 레퍼런스 8호(능선으로 이어진 봉군)·12호(하나의 산체) 재실측
    //   (실측표 §11): 산체 마루 ≈ 침봉 마루의 35~45% · 안부 ≈ 산체 마루의 ~60% ·
    //   운해 골 ≈ 자리의 1/4. 침봉·배후봉은 이 산체 위에서 솟는다 (max 합성) —
    //   「평지+기둥」이 「산+암봉」이 된다. 본산(산세)과는 max 합성 + 안쪽 소멸 띠로 잇는다.
    // ═══════════════════════════════════════════════════════════════════

    /** 산체가 미치는 끝 — 원경 침봉은 운해 위 실루엣로 남긴다 (조성량·시간의 고삐이기도 하다) */
    public static final int RELIEF_FADE_END = 760;

    /** 산체 높이 기여 (0 = 골/범위 밖). 능선 결(ridged) 노이즈 — 마루선이 이어지고 안부가 생긴다. */
    private int baseRelief(int x, int z) {
        double dist = Math.hypot(x, z);
        if (dist >= RELIEF_FADE_END) {
            return 0;
        }
        double n1 = 1.0 - 2.0 * Math.abs(valueNoise(x / 96.0, z / 96.0, SALT ^ 0x51DCEL) - 0.5);
        double n2 = valueNoise(x / 37.0, z / 37.0, SALT ^ 0x52DCEL);
        double n = 0.62 * n1 + 0.38 * n2;
        if (n < 0.32) {
            return 0;   // 운해 골 — 협곡 자리 보존 (~1/4)
        }
        double t = (n - 0.32) / 0.68;
        double amp = dist < 430 ? 85 : dist < 700 ? 85 - 25 * (dist - 430) / 270.0 : 60;
        double edge = Math.min(1.0, (RELIEF_FADE_END - dist) / 120.0);
        double inner = dist >= INNER_R ? 1.0 : Math.max(0.0, (dist - 120) / 80.0);   // 본산권 — 산세에 양보
        int hgt = (int) (t * t * amp * edge * inner);   // t² — 마루는 서고 발치는 완만
        if (inCorridor(x, z)) {
            hgt = Math.min(hgt, 20);   // ★시야 회랑 — 지형 금지가 아니라 낮은 구릉까지 (8.8 의 결)
        }
        return hgt;
    }

    /** 격자 값 노이즈 [0,1) — 결정론 (난수 0) · 스무스스텝 보간 */
    private static double valueNoise(double u, double v, long salt) {
        int iu = (int) Math.floor(u);
        int iv = (int) Math.floor(v);
        double fu = u - iu;
        double fv = v - iv;
        double su = fu * fu * (3 - 2 * fu);
        double sv = fv * fv * (3 - 2 * fv);
        double a = lattice(iu, iv, salt);
        double b = lattice(iu + 1, iv, salt);
        double c = lattice(iu, iv + 1, salt);
        double d = lattice(iu + 1, iv + 1, salt);
        return a + (b - a) * su + (c - a) * sv + (a - b - c + d) * su * sv;
    }

    private static double lattice(int iu, int iv, long salt) {
        return Math.floorMod(mix(salt, iu, 0, iv), 10_000) / 10_000.0;
    }

    /** 남쪽 접근 시야 회랑인가 (8.8) — 침봉은 금지, 산체는 구릉 상한 */
    private static boolean inCorridor(int x, int z) {
        return z >= 340 && Math.abs(x + 4) <= 45;
    }

    /**
     * 배후봉 하나의 높이 기여 — 병풍꼴 능선봉 (§4-b).
     * 마루 = 장축 선분(길이 len) · 선분 중심에서 정확히 topH (계약) · 밖으로 요철 파임 0~5 ·
     * 급벽/완사 비대칭 (0.80/1.15) · 세로 홈(방위 로브 7·11 가닥) · 하부 사면 4칸 턱 양자화.
     */
    private static int ridgeH(Ridge c, int x, int z) {
        double rad = Math.toRadians(c.axisDeg());
        double ax = Math.sin(rad), az = Math.cos(rad);          // 남축(+z) 기준 시계
        double ux = x - c.cx(), uz = z - c.cz();
        double u = ux * ax + uz * az;                            // 능선 방향 성분
        double v = -ux * az + uz * ax;                           // 횡 성분
        double half = c.len() / 2.0;
        double uc = Math.abs(u) <= half ? 0.0 : Math.abs(u) - half;   // 마루 선분까지의 축상 거리
        double rv = v >= 0 ? c.r() * 0.80 : c.r() * 1.15;        // 급벽(+) · 완사(−)
        double d = Math.hypot(uc / c.r(), v / rv);
        if (d >= 1.15) {
            return 0;
        }
        // 세로 홈 — 방위 로브가 유효 거리를 흔든다 (몸 전 높이 관통 · 결정론).
        // ★12-② 급벽 쪽(v>2)은 진폭 상향 — 매끈한 비탈이 아니라 갈라진 절벽면으로 읽히게.
        boolean steep = v > 2;
        double a1 = steep ? 0.08 : 0.05;
        double a2 = steep ? 0.05 : 0.03;
        double th = Math.atan2(uz, ux);
        double flute = 1.0 + a1 * Math.sin(7 * th) + a2 * Math.sin(11 * th + 2.1);
        double de = d / flute;
        if (de >= 1.0) {
            return 0;
        }
        // 마루 요철 — 능선을 따라 3칸 단위 파임 0~7 (★11-②: 탁상 꼭대기 손질 — 원경에서도
        //   마루가 평평히 읽히지 않게 진폭 상향). ★선분 중심 ±1 은 파임 0 (topH 계약)
        double crest = c.topH();
        if (Math.abs(u) > 1.0) {
            long ph = mix(SALT ^ 0xB1DF, c.cx(), (int) Math.floor(u / 3.0), c.cz());
            crest -= Math.floorMod(ph, 8);
        }
        int hh = (int) (crest * Math.pow(1.0 - de, 0.55));
        if (steep && hh < crest * 0.85) {
            // ★12-② 급벽면 계단짐 — 턱 간격 4~7 · 깊이 1~2 (배후봉 하부 문법의 강화판 ·
            //   마루·계약은 무변경 — v>2 밖·상부 15% 는 손대지 않는다)
            int stp = 4 + (int) Math.floorMod(mix(SALT ^ 0xC11FL, c.cx(), hh / 9, c.cz()), 4);
            int notch = 1 + (int) Math.floorMod(mix(SALT ^ 0xC11FL, c.cx(), hh / 5, c.cz()) >> 3, 2);
            hh = Math.max(0, (hh / stp) * stp - notch);
        } else if (hh < crest * 0.55) {
            hh = (hh / 4) * 4;                                   // 완사면 하부 — 종전 결 (§4-b)
        }
        return hh;
    }

    /**
     * 셀 하나의 침봉 — 실측: 반경 6~12 · 정상고(켜별) 70~170 · 세장비 1:4~1:8.
     * ★6.7 형태 (§4-b — 「종유석 바늘」 판정의 처방): (1−d²)^1.5 폐기 →
     * <b>몸통 유지(0.78 까지 마루의 88~100% — 둥근 소평두) + 치마(t^0.4 급락 · 발치 애추)</b>.
     * 세로 홈(방위 로브 5~9 · 반경 ±8%)이 전 높이를 관통하고, 치마 높이는 3~5칸 턱으로
     * 양자화된다. ★침봉 중심 높이 = top 정확히 (근경 마루 실측 창 110~170 눈이 잰다).
     */
    private int spireAt(int cellX, int cellZ, int x, int z) {
        long h = mix(SALT, cellX, 0, cellZ);
        int cx = cellX * CELL + 5 + (int) Math.floorMod(h >> 8, 16);
        int cz = cellZ * CELL + 5 + (int) Math.floorMod(h >> 16, 16);
        // ★8.8 남쪽 접근 시야 회랑 — 침봉 금지 (몸통 가장자리가 살짝 무는 건 자연의 결)
        if (inCorridor(cx, cz)) {
            return 0;
        }
        double centerDist = Math.hypot(cx, cz);
        int lo;
        int hi;
        // ★10.5 침봉 강등 (구도 반전 — 실측 §12: 광봉:침봉 ≥4:1): 근·중경 밀도 62%→4% —
        //   광봉 마루·가장자리 위의 장식만 남는다. 원경(650+)은 운해 실루엣 몫으로 10%.
        int density;
        if (centerDist < INNER_R) {
            return 0;                                   // 본산권 — 골격의 것
        } else if (centerDist < 430) {
            lo = 140;
            hi = 220;                                   // 근경 (★8.5 산체 배율 동행)
            density = 4;
        } else if (centerDist < BROAD_END) {
            lo = 110;
            hi = 180;                                   // 중경
            density = 4;
        } else if (centerDist < FIELD_R) {
            lo = 90;
            hi = 140;                                   // 원경 — 운해 위 실루엣
            density = 10;
        } else {
            return 0;
        }
        if (Math.floorMod(h, 100) >= density) {
            return 0;
        }
        // 실루엣 변주 (슬라이스 10) — 굵은 놈 소수(18%) + 가는 놈 다수
        boolean thick = Math.floorMod(h >> 52, 100) < 18;
        int r = thick ? 13 + (int) Math.floorMod(h >> 32, 5)
                : 5 + (int) Math.floorMod(h >> 32, 5);
        int top = thick ? hi - (int) Math.floorMod(h >> 24, (hi - lo) / 3 + 1)
                : lo + (int) Math.floorMod(h >> 24, hi - lo + 1);
        // ★10.5 발치 가드 — 평지에서 곧장 솟는 침봉 금지: 광봉·저지 릴리프 위(지지고 ≥55)에서만,
        //   그리고 지지면 위로 ≥20 은 솟아야 장식이 된다 (원경 실루엣 켜는 예외 — 운해가 발치를 가린다)
        if (centerDist < BROAD_END) {
            int support = baseRelief(cx, cz);
            int sgX = Math.floorDiv(cx, GCELL);
            int sgZ = Math.floorDiv(cz, GCELL);
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    support = Math.max(support, broadAt(sgX + i, sgZ + j, cx, cz));
                }
            }
            if (support < 55 || top < support + 20) {
                return 0;
            }
        }
        double dx = x - cx, dz = z - cz;
        if (Math.hypot(dx, dz) >= r * 1.13) {
            return 0;                                        // 로브 최대 확장 밖 — 빠른 탈출
        }
        // 세로 홈 — 방위 로브 5~9 가닥이 유효 반경을 ±8% 흔든다 (주상절리 · §4-b)
        double th = Math.atan2(dz, dx);
        int lobes = 5 + (int) Math.floorMod(h >> 40, 5);     // 5~9
        double ph = Math.floorMod(h >> 44, 628) / 100.0;     // 위상
        double rEff = r * (1.0 + 0.08 * Math.sin(lobes * th + ph)
                + 0.04 * Math.sin((lobes + 3) * th + 1.7 * ph));
        double d = Math.hypot(dx, dz) / rEff;
        if (d >= 1.0) {
            return 0;
        }
        // 몸통 유지 → 급락 캡 (§4-b): 0.78 까지 마루의 88~100% (둥근 소평두 — 중심 = top 정확),
        // 밖은 t^0.4 치마 — 위 절반 수직벽 · 발치 애추. 치마는 3~5칸 턱으로 양자화.
        final double body = 0.78;
        if (d < body) {
            double dome = Math.sqrt(1.0 - (d / body) * (d / body));
            int hh = (int) (top * (0.88 + 0.12 * dome));
            if (d > 0.3) {   // ★11-② 소평두 거칠기 — 넓은 돔이 탁상으로 읽히지 않게 (중심 계약 보존)
                hh -= (int) Math.floorMod(mix(SALT ^ 0xD03EL,
                        Math.floorDiv(x, 3), 0, Math.floorDiv(z, 3)), 3);
            }
            return hh;
        }
        double t = (d - body) / (1.0 - body);
        int step = 3 + (int) Math.floorMod(h >> 48, 3);      // 3~5 — 수평 바위 턱
        int skirt = (int) (top * 0.88 * (1.0 - Math.pow(t, 0.4)));
        return (skirt / step) * step;
    }

    /**
     * ★암질 — 슬라이스 10-② 색 온도: 차가운 회색 일색 → 웜톤 (레퍼런스 1·8·12호의 베이지 끼
     * 석재). 점적석(따뜻한 갈빛)·사암을 섞고 응회암 비중을 올렸다 — y-띠(6칸)·해시 결정론.
     * 마루(cap)엔 이끼가 앉는다 (기존 결 유지).
     */
    public static org.bukkit.Material stone(int x, int y, int z, boolean cap) {
        long h = (x * 0x9E3779B97F4A7C15L) ^ ((y / 6) * 0xC2B2AE3D27D4EB4FL)
                ^ (z * 0x165667B19E3779F9L);
        h ^= h >>> 31;
        int r = (int) Math.floorMod(h, 100);
        if (cap) {
            return r < 45 ? org.bukkit.Material.MOSS_BLOCK : org.bukkit.Material.STONE;
        }
        if (r < 26) {
            return org.bukkit.Material.STONE;
        }
        if (r < 40) {
            return org.bukkit.Material.ANDESITE;
        }
        if (r < 58) {
            return org.bukkit.Material.TUFF;
        }
        if (r < 70) {
            return org.bukkit.Material.CALCITE;
        }
        if (r < 88) {
            return org.bukkit.Material.DRIPSTONE_BLOCK;   // 웜톤의 몸통
        }
        if (r < 96) {
            return org.bukkit.Material.SANDSTONE;
        }
        return org.bukkit.Material.MOSSY_COBBLESTONE;     // 벽면 이끼 낌 (10-① 예비)
    }

    /** 셀의 침봉 반경 (0 = 없음 — 밀도·회랑·본산권 반영 · 발치 가드는 targetH 의 몫) — 변주 눈용 */
    public static int spireRadius(int cellX, int cellZ) {
        int[] c = spireCenter(cellX, cellZ);
        if (c == null) {
            return 0;
        }
        long h = mix(SALT, cellX, 0, cellZ);
        boolean thick = Math.floorMod(h >> 52, 100) < 18;
        return thick ? 13 + (int) Math.floorMod(h >> 32, 5)
                : 5 + (int) Math.floorMod(h >> 32, 5);
    }

    /** 셀 침봉의 중심 (밀도·회랑·켜 통과 시 · 아니면 null) — 마루 창·강등 비율의 눈용 */
    public static int[] spireCenter(int cellX, int cellZ) {
        long h = mix(SALT, cellX, 0, cellZ);
        int cx = cellX * CELL + 5 + (int) Math.floorMod(h >> 8, 16);
        int cz = cellZ * CELL + 5 + (int) Math.floorMod(h >> 16, 16);
        if (inCorridor(cx, cz)) {
            return null;
        }
        double d = Math.hypot(cx, cz);
        int density = d < INNER_R || d >= FIELD_R ? 0 : d < BROAD_END ? 4 : 10;
        if (density == 0 || Math.floorMod(h, 100) >= density) {
            return null;
        }
        return new int[]{cx, cz};
    }

    /** 격자의 광봉 밑변 반경 (0 = 없음) — 구도 반전 비율의 눈용 (10.5) */
    public static int broadRadius(int gX, int gZ) {
        long h = mix(SALT ^ 0xB40ADL, gX, 0, gZ);
        if (Math.floorMod(h, 100) >= 85) {
            return 0;
        }
        int cx = gX * GCELL + 20 + (int) Math.floorMod(h >> 8, GCELL - 40);
        int cz = gZ * GCELL + 20 + (int) Math.floorMod(h >> 16, GCELL - 40);
        double d = Math.hypot(cx, cz);
        if (d < INNER_R || d >= BROAD_END || inCorridor(cx, cz)) {
            return 0;
        }
        return 30 + (int) Math.floorMod(h >> 32, 31);
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
