package com.honcheon.mvt;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 땅의 봉인(封印) — <b>"건축이 땅을 안 바꾼다"를 재는 눈.</b>
 *
 * <p>사용자 판정: <i>"건축이 수정되어도 <b>지형이 변화하면 안 됩니다.</b>"</i>
 * <b>말로 정한 계약은 계약이 아니다. 재야 계약이다.</b>
 *
 * <h2>무엇이 '지형'인가 — 이 정의가 이 눈의 전부다</h2>
 * 순진한 답("블록이 하나도 안 바뀐다")은 <b>틀렸다</b>. 건축은 당연히 블록을 놓는다 —
 * 집을 짓고, 길에 포석을 깐다. 그건 <b>땅을 바꾼 게 아니라 땅 위에 올린 것</b>이다.
 *
 * <p>그래서 <b>골격(骨格)</b>만 잰다:
 * <pre>
 *   지형 = 각 열의 <b>실지면(그 열의 지면 y) 아래 여덟 칸의 채움새</b> (단단한가 · 물인가 · 비었는가)
 * </pre>
 * <ul>
 *   <li><b>잡는다</b>: 건축이 기초를 파고 들어간 것 · 지면 밑을 돋운 것 · 마당을 깎아 내린 것 ·
 *       조성기가 <b>땅을 다시 빚어</b> 산 위에 산을 쌓은 것 — <b>정상 단·중턱 단의 흙일도</b> (B-150)</li>
 *   <li><b>안 잡는다</b>: 잔디를 포석으로 간 것 (채움새가 같다 — <b>포석은 지형이 아니다</b>) ·
 *       지면 위에 세운 벽·지붕 (실지면 위는 <b>건축의 하늘</b>이다)</li>
 * </ul>
 * <b>자재가 아니라 형상을 잰다.</b> 그것이 "땅"의 정직한 정의다.
 *
 * <h2>★ B-150 — 기준은 평면이 아니라 열이다</h2>
 * 구판은 모든 열에서 {@code spec.groundY()} 아래 8칸만 쟀다. 그래서 두 번 속았다:
 * <ul>
 *   <li><b>고지대 사각지대</b> — 본전·석조도관·여섯 단은 {@code peakY}·실지면 기준으로 기준면보다
 *       수십 칸 높이 선다. 그 단의 깎기·채움을 구판은 산 속(무변)만 재며 <b>못 봤다</b>
 *       (Codex 헌장 §3 검토 축 5).</li>
 *   <li><b>물리 잡음</b> (B-139) — 실지면이 기준면보다 낮은 외곽 열에선 기준면 창이 <b>허공·수면</b>을
 *       재서, 두 계측 사이에 물이 흐르고 모래가 앉으면 "건축 없는 조성"까지 물었다.</li>
 * </ul>
 * 새 눈은 <b>열마다 그 열의 실지면</b>({@link TerrainForge.SiteSpec#groundAt})을 기준으로 잰다 —
 * 실지면과 그 아래 일곱 칸은 봉인 시점에 단단한 뼈다. 뼈는 물리로 안 흔들리고, 높은 단 위에서도
 * 뼈는 뼈다. 젖은 열(물)은 기준면 창을 쓰되 <b>뼈(고체)만</b> 잰다 — 물의 몸(물↔공기)은 물의 것이다.
 *
 * <h2>★ 단(壇)은 위반이 아니다 — 허용 마스크는 지도에서 나온다 (3계층 헌법)</h2>
 * 건축 계층은 땅을 직접 만지지 않는다. 평평한 바닥이 필요하면 {@link TerrainForge#terrace}·
 * {@link TerrainForge#terraceRound}에 <b>요청</b>하고, 그 손이 유일하게
 * {@code spec.surface()}·{@code spec.buildable()}을 함께 갱신한다 (이 저장소에서 그 두 배열을
 * 만지는 손은 terrace 둘뿐이다 — 그래서 배열이 곧 <b>요청 원장</b>이다). 봉인은 전/후로
 * {@code spec.surface()}를 <b>스냅샷</b>해 두 장을 가른다:
 * <ul>
 *   <li><b>실지면이 spec 과 함께 움직인 열</b> = terrace 채널 = 지도(원형 footprint)가 승인한 단 —
 *       위반이 아니다. 대신 <b>계약 ①</b>(단 밑 {@value TerrainForge#SEAL_DEPTH}칸은 단단하다)을 잰다.</li>
 *   <li><b>spec 은 그대로인데 세계만 바뀐 열</b> = 임의 지형 변경 = <b>위반</b>.</li>
 * </ul>
 * 지도가 정본이고, 봉인은 지도를 섬기는 기계다 — 지도가 모르는 흙일만 문다.
 *
 * <h2>진짜 시험 — 멱등(冪等)</h2>
 * 계약의 참뜻은 이것이다: <b>건물을 다시 지어도 땅이 그대로다.</b>
 * <pre>
 *   1차 조성 (땅 + 집) → 봉인 H0
 *   ★ 건물만 다시 짓기 (땅은 원장이 막는다) → 봉인 H1
 *   <b>H0 == H1 이어야 한다.</b>
 * </pre>
 * 그래서 봉인은 <b>지문</b>({@link #fingerprint})을 말한다 — 같은 원장 위 재건축은 매회 같은
 * 지문을 찍어야 한다. 조율자는 두 조성의 로그에서 지문 두 줄을 diff 하면 된다.
 *
 * <p>눈을 시험하는 눈: {@code tools/TerrainSealSelfTest.java} — 일부러 고지대 흙일을 흉내 내
 * 이 눈이 잡는지, 구판이 못 잡던 것을 잡는지 잰다.
 */
final class TerrainSeal {

    private TerrainSeal() {
    }

    private static final String OK = ChatColor.GREEN + "✓ ";
    private static final String BAD = ChatColor.RED + "✗ ";
    private static final String INFO = ChatColor.GRAY + "  ";
    private static final String HEAD = ChatColor.AQUA + "";

    /** 실지면 포함, 아래로 이만큼을 잰다 — 건축의 기초가 파고들면 여기가 바뀐다 */
    static final int DEPTH = 8;

    /** 젖은 열 표식 — 이 열의 실지면은 물이다 (기준면 창으로 뼈만 잰다) */
    static final int WET = Integer.MIN_VALUE;

    /** 세계를 읽는 창 (가짜 세계로 시험할 수 있게) */
    interface Probe {

        boolean solid(int x, int y, int z);

        boolean water(int x, int y, int z);
    }

    static Probe of(World world) {
        return new Probe() {
            @Override
            public boolean solid(int x, int y, int z) {
                Material m = world.getBlockAt(x, y, z).getType();
                return !m.isAir() && m != Material.WATER;
            }

            @Override
            public boolean water(int x, int y, int z) {
                return world.getBlockAt(x, y, z).getType() == Material.WATER;
            }
        };
    }

    /**
     * 한 부지의 봉인 — 열마다 (실지면 y · 그 아래 8칸의 채움새 · 건축 가능 여부). <b>자재는 안 본다</b>
     *
     * @param ground    열별 실지면 y — 봉인 시점 {@code spec.surface()} 의 <b>스냅샷</b>
     *                  (terrace 가 원본을 제자리에서 고치므로 베껴 둬야 전/후가 갈린다).
     *                  젖은 열은 {@link #WET}
     * @param buildable 열별 건축 가능 스냅샷 — 같은 높이의 단 요청(false→true)을 알아보는 곁눈
     * @param columns   열별 채움새 — 마른 열은 실지면부터 아래로, 젖은 열은 기준면부터 아래로
     */
    record Seal(String placeId, int cx, int cz, int radius, int groundY,
                int[] ground, boolean[] buildable, int[] columns) {

        int columnCount() {
            return columns.length;
        }
    }

    /**
     * 땅의 골격을 뜬다.
     *
     * <p>기준은 <b>그 열의 실지면({@code spec.groundAt})</b>이지 '그 열의 최상단'이 아니다 —
     * 최상단은 건축이 지붕을 얹는 순간 바뀌고, 그러면 이 눈은 <b>집을 지었다고 땅이 바뀌었다</b>고
     * 거짓말하게 된다. <b>실지면은 지형 계층(측량·terrace)만 고친다. 그래서 안 흔들린다.</b>
     */
    static Seal seal(Probe probe, TerrainForge.SiteSpec spec) {
        int r = spec.radius();
        int w = 2 * r + 1;
        int[] ground = new int[w * w];
        boolean[] canBuild = new boolean[w * w];
        int[] cols = new int[w * w];
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int x = spec.cx() + dx;
                int z = spec.cz() + dz;
                int i = (dz + r) * w + (dx + r);
                boolean wet = spec.wet(x, z);
                ground[i] = wet ? WET : spec.groundAt(x, z);
                canBuild[i] = spec.canBuild(x, z);
                cols[i] = bits(probe, x, wet ? spec.groundY() : ground[i], z, wet);
            }
        }
        return new Seal(spec.placeId(), spec.cx(), spec.cz(), r, spec.groundY(),
                ground, canBuild, cols);
    }

    /**
     * 한 열의 채움새 — 기준 y 포함 아래 {@value #DEPTH}칸.
     *
     * <p>젖은 열은 <b>뼈(고체)만</b> 잰다 — 물↔공기의 출렁임은 물리의 것이지 건축의 손이 아니다
     * (B-139: 그 출렁임이 "건축 없는 조성"을 물게 했다). 판 것(고체→물/공기)·돋운 것(물/공기→고체)은
     * 여전히 잡힌다.
     */
    private static int bits(Probe probe, int x, int refY, int z, boolean wet) {
        int bits = 0;
        for (int i = 0; i < DEPTH; i++) {
            int y = refY - i;
            int v = wet
                    ? (probe.solid(x, y, z) ? 1 : 0)
                    : (probe.water(x, y, z) ? 2 : probe.solid(x, y, z) ? 1 : 0);
            bits |= v << (i * 2);
        }
        return bits;
    }

    /** 채움새에서 단단한 칸의 수 — 판 것/돋운 것의 방향을 가른다 */
    private static int solidCells(int bits) {
        int n = 0;
        for (int i = 0; i < DEPTH; i++) {
            if (((bits >> (i * 2)) & 3) == 1) {
                n++;
            }
        }
        return n;
    }

    /** 계약 ① — 단의 바닥과 그 밑 {@value TerrainForge#SEAL_DEPTH}칸이 단단한가 (terrace 가 약속한 것) */
    private static boolean underpinned(int bits) {
        for (int i = 0; i <= TerrainForge.SEAL_DEPTH && i < DEPTH; i++) {
            if (((bits >> (i * 2)) & 3) != 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * 봉인의 지문 — <b>같은 원장 위 재건축은 매회 같은 지문을 찍어야 한다</b> (멱등 실측의 자).
     *
     * <p>실지면과 채움새만 섞는다 — 그것이 땅의 형상이다. (buildable 은 배치 장부지 형상이 아니라 뺐다.)
     */
    static long fingerprint(Seal s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.placeId().length(); i++) {
            h = fnv(h, s.placeId().charAt(i));
        }
        h = fnv(h, s.cx());
        h = fnv(h, s.cz());
        h = fnv(h, s.radius());
        h = fnv(h, s.groundY());
        for (int g : s.ground()) {
            h = fnv(h, g);
        }
        for (int c : s.columns()) {
            h = fnv(h, c);
        }
        return h;
    }

    private static long fnv(long h, int v) {
        for (int b = 0; b < 4; b++) {
            h ^= (v >> (b * 8)) & 0xff;
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** 두 봉인이 같은가 — spec 이 모르게 다르면 <b>땅이 바뀐 것이다</b> */
    static List<String> compare(Seal before, Seal after) {
        List<String> out = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        out.add(HEAD + "══ 땅의 봉인 — 건축이 땅을 바꾸었는가 (" + before.placeId() + ") ══");

        if (before.cx() != after.cx() || before.cz() != after.cz()
                || before.groundY() != after.groundY() || before.radius() != after.radius()) {
            out.add(BAD + "  ★ 부지가 옮겨 앉았다 — (" + before.cx() + "," + before.cz() + ") 지면 y"
                    + before.groundY() + " → (" + after.cx() + "," + after.cz() + ") 지면 y"
                    + after.groundY() + ". 땅이 다시 빚어졌다 (원장이 안 막았다)");
            out.add(INFO + "    이것이 **산 위에 산을 쌓던 병**이다. TerrainLedger 를 보라");
            violations.add("부지 이동");
            out.add(HEAD + "── 총평 ──");
            out.add(BAD + "위반 " + violations.size() + "건: " + String.join(" / ", violations));
            return out;
        }

        int changed = 0;
        int dug = 0;
        int filled = 0;
        int firstX = 0;
        int firstZ = 0;
        int terraced = 0;
        int hollow = 0;          // 단인데 밑이 빈 열 (계약 ① 위반)
        int hollowX = 0;
        int hollowZ = 0;
        int loGround = Integer.MAX_VALUE;   // 잰 실지면의 폭 — 고지대가 창 안에 있다는 증거
        int hiGround = Integer.MIN_VALUE;
        int r = before.radius();
        int w = 2 * r + 1;
        for (int i = 0; i < before.columns().length; i++) {
            int gb = before.ground()[i];
            int ga = after.ground()[i];
            int a = before.columns()[i];
            int b = after.columns()[i];
            if (gb != WET) {
                loGround = Math.min(loGround, gb);
                hiGround = Math.max(hiGround, gb);
            }
            // ★ 허용 마스크 — 실지면이 spec 과 **함께** 움직였다 = terrace 채널 (지도가 승인한 단).
            //   같은 높이의 단 재요청은 buildable(false→true)이 곁눈으로 알아본다.
            boolean viaTerrace = gb != ga
                    || (a != b && !before.buildable()[i] && after.buildable()[i]);
            if (viaTerrace) {
                terraced++;
                if (ga != WET && !underpinned(b)) {
                    if (hollow == 0) {
                        hollowX = before.cx() + (i % w) - r;
                        hollowZ = before.cz() + (i / w) - r;
                    }
                    hollow++;   // 단은 승인됐지만 **계약 ①을 안 지켰다** — 밑이 빈 단은 껍데기다
                }
                continue;
            }
            if (a == b) {
                continue;
            }
            if (changed == 0) {
                firstX = before.cx() + (i % w) - r;
                firstZ = before.cz() + (i / w) - r;
            }
            changed++;
            if (solidCells(b) < solidCells(a)) {
                dug++;      // 뼈가 줄었다 — 팠다
            } else {
                filled++;   // 뼈가 늘었다 — 돋웠다
            }
        }
        out.add(INFO + "  잰 열 " + before.columnCount() + " · 열별 실지면 "
                + (loGround > hiGround ? "전열이 물이다"
                        : "y" + loGround + "~y" + hiGround)
                + " (기준면 y" + before.groundY() + " — 고지대 단도 창 안이다)");
        if (terraced > 0) {
            out.add(OK + "  단(壇) " + terraced + "열 — 지도가 승인한 terrace 요청 "
                    + "(spec.surface 가 함께 움직였다. 위반이 아니다)");
        }
        if (hollow > 0) {
            out.add(BAD + "  ★ 단 밑이 비었다 — " + hollow + "열 (처음: " + hollowX + "," + hollowZ
                    + "). 단은 승인됐지만 계약 ①(바닥 밑 " + TerrainForge.SEAL_DEPTH
                    + "칸은 단단하다)을 안 지켰다");
            violations.add("밑 빈 단(" + hollow + "열)");
        }
        if (changed > 0) {
            out.add(BAD + "  ★ 건축이 spec 몰래 땅을 바꿨다 — " + changed + "열 (처음: " + firstX
                    + "," + firstZ + " · 판 것 " + dug + " · 돋운 것 " + filled + ")");
            out.add(INFO + "    땅에 맞게 건물이 올라가야지, 건축이 제 손으로 흙을 만지면 안 된다.");
            out.add(INFO + "    땅을 바꿔야 한다면 **terrace 요청**으로 말하라 — 그 손만이 "
                    + "spec 을 함께 고치고, 그래야 봉인이 단으로 읽는다");
            out.add(INFO + "    (높이가 같은 단의 속 채움이었다면 위양성일 수 있다 — "
                    + "그 열의 원형 단 목록·land_requests 와 대조하라)");
            violations.add("임의 지형 변경(" + changed + "열)");
        } else if (hollow == 0) {
            out.add(OK + "  땅이 한 열도 spec 몰래 안 바뀌었다 — 건축은 땅 위에 올라앉았을 뿐이다");
        }
        out.add(INFO + "  봉인 지문 " + String.format("%016x", fingerprint(after))
                + " — 같은 원장 재건축이면 이 지문이 매회 같아야 한다 (멱등)");

        out.add(HEAD + "── 총평 ──");
        out.add(violations.isEmpty()
                ? OK + "위반 0건 — 건축이 수정되어도 지형은 그대로다"
                : BAD + "위반 " + violations.size() + "건: " + String.join(" / ", violations));
        return out;
    }

    /** 위반만 (시험이 읽는다) */
    static List<String> violations(Seal before, Seal after) {
        List<String> out = new ArrayList<>();
        for (String line : compare(before, after)) {
            String plain = ChatColor.stripColor(line);
            if (plain.startsWith("✗ ")) {
                out.add(plain.substring(2).trim());
            }
        }
        return out;
    }
}
