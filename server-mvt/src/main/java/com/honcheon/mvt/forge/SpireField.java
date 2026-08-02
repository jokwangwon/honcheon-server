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
    public static final int FIELD_R = 620;

    /** 본산권 안쪽 한계 — 이 안은 골격(캠퍼스의 산)의 것, 산군은 손대지 않는다 */
    public static final int INNER_R = 130;

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
     * 원뿔 아님 — pow 0.55 절벽 프로파일 (기존 봉 몸체 위에 max 로 앉는다).
     *
     * @param topH 목표 마루 높이 (baseY 위)
     */
    public record Cone(String id, int cx, int cz, int topH, int r) {
    }

    /** 배후봉 넷 — 골격 Peak 자리 그대로, 높이만 실측 비로 (실측표 §4) */
    public static List<Cone> backPeaks() {
        return List.of(
                new Cone("Pm", -24, -54, 228, 56),
                new Cone("Em", 62, -46, 200, 42),
                new Cone("Wm", -104, -16, 195, 46),
                new Cone("Es", 98, -18, 170, 32));
    }

    private final List<int[]> exclusions;

    /** @param exclusions 침범 금지 사각들 [x0,x1,z0,z1] — 캠퍼스 패드·계단·다리 발자국 + 여유 */
    public SpireField(List<int[]> exclusions) {
        this.exclusions = List.copyOf(exclusions);
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
        int best = 0;
        for (Cone c : backPeaks()) {                    // ① 배후봉 증고
            double d = Math.hypot(x - c.cx(), z - c.cz());
            if (d < c.r()) {
                best = Math.max(best, (int) (c.topH() * Math.pow(1.0 - d / c.r(), 0.55)));
            }
        }
        int cellX = Math.floorDiv(x, CELL);             // ② 스파이어 켜 3 — 이웃 셀 아홉을 본다
        int cellZ = Math.floorDiv(z, CELL);
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                best = Math.max(best, spireAt(cellX + i, cellZ + j, x, z));
            }
        }
        return best;
    }

    /** 셀 하나의 침봉 — 실측: 반경 6~12 · 정상고(켜별) 70~170 · 세장비 1:4~1:8 */
    private int spireAt(int cellX, int cellZ, int x, int z) {
        long h = mix(SALT, cellX, 0, cellZ);
        if (Math.floorMod(h, 100) >= 62) {
            return 0;                                   // 이 셀엔 침봉이 없다 (밀도 62%)
        }
        int cx = cellX * CELL + 5 + (int) Math.floorMod(h >> 8, 16);
        int cz = cellZ * CELL + 5 + (int) Math.floorMod(h >> 16, 16);
        double centerDist = Math.hypot(cx, cz);
        int top;
        if (centerDist < INNER_R) {
            return 0;                                   // 본산권 — 골격의 것
        } else if (centerDist < 260) {
            top = 110 + (int) Math.floorMod(h >> 24, 61);   // 근경 110~170
        } else if (centerDist < 430) {
            top = 90 + (int) Math.floorMod(h >> 24, 51);    // 중경 90~140
        } else if (centerDist < FIELD_R) {
            top = 70 + (int) Math.floorMod(h >> 24, 41);    // 원경 70~110
        } else {
            return 0;
        }
        int r = 6 + (int) Math.floorMod(h >> 32, 7);        // 6~12
        double d = Math.hypot(x - cx, z - cz) / r;
        if (d >= 1.0) {
            return 0;
        }
        // 세로로 긴 침봉 — (1−d²)^1.5: 반높이 반경 ≈ 0.55r → 폭:높이 ≈ 1:4~1:8 (실측)
        return (int) (top * Math.pow(1.0 - d * d, 1.5));
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
