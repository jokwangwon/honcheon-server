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
 *   지형 = 각 열의 <b>기준 지면 아래 여덟 칸의 채움새</b> (단단한가 · 물인가 · 비었는가)
 * </pre>
 * <ul>
 *   <li><b>잡는다</b>: 건축이 기초를 파고 들어간 것 · 땅을 돋운 것 · 마당을 깎아 내린 것 ·
 *       조성기가 <b>땅을 다시 빚어</b> 산 위에 산을 쌓은 것</li>
 *   <li><b>안 잡는다</b>: 잔디를 포석으로 간 것 (채움새가 같다 — <b>포석은 지형이 아니다</b>) ·
 *       지면 위에 세운 벽·지붕 (기준면 위는 <b>건축의 하늘</b>이다)</li>
 * </ul>
 * <b>자재가 아니라 형상을 잰다.</b> 그것이 "땅"의 정직한 정의다.
 *
 * <h2>진짜 시험 — 멱등(冪等)</h2>
 * 계약의 참뜻은 이것이다: <b>건물을 다시 지어도 땅이 그대로다.</b>
 * <pre>
 *   1차 조성 (땅 + 집) → 봉인 H0
 *   ★ 건물만 다시 짓기 (땅은 원장이 막는다) → 봉인 H1
 *   <b>H0 == H1 이어야 한다.</b>
 * </pre>
 * 예전 구조에서는 <b>이것이 불가능했다</b> — 건물을 다시 지으려면 {@code prepare()} 를 다시 불러야 했고,
 * 그러면 땅이 매번 달라졌다. {@link Terraform} 이 그 고리를 끊었고, <b>이 눈이 그것을 증명한다.</b>
 */
final class TerrainSeal {

    private TerrainSeal() {
    }

    private static final String OK = ChatColor.GREEN + "✓ ";
    private static final String BAD = ChatColor.RED + "✗ ";
    private static final String INFO = ChatColor.GRAY + "  ";
    private static final String HEAD = ChatColor.AQUA + "";

    /** 기준면 아래 이만큼을 잰다 — 건축의 기초가 파고들면 여기가 바뀐다 */
    static final int DEPTH = 8;

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

    /** 한 부지의 봉인 — 열마다 (기준면 아래 8칸의 채움새). <b>자재는 안 본다</b> */
    record Seal(String placeId, int cx, int cz, int radius, int groundY, int[] columns) {

        int columnCount() {
            return columns.length;
        }
    }

    /**
     * 땅의 골격을 뜬다.
     *
     * <p>기준은 <b>조성 기준면({@code groundY})</b>이지 '그 열의 최상단'이 아니다 —
     * 최상단은 건축이 지붕을 얹는 순간 바뀌고, 그러면 이 눈은 <b>집을 지었다고 땅이 바뀌었다</b>고
     * 거짓말하게 된다. <b>기준면은 원장이 고정한다. 그래서 안 흔들린다.</b>
     */
    static Seal seal(Probe probe, TerrainForge.SiteSpec spec) {
        int r = spec.radius();
        int w = 2 * r + 1;
        int[] cols = new int[w * w];
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int x = spec.cx() + dx;
                int z = spec.cz() + dz;
                int bits = 0;
                for (int i = 0; i < DEPTH; i++) {
                    int y = spec.groundY() - i;
                    int v = probe.water(x, y, z) ? 2 : probe.solid(x, y, z) ? 1 : 0;
                    bits |= v << (i * 2);
                }
                cols[(dz + r) * w + (dx + r)] = bits;
            }
        }
        return new Seal(spec.placeId(), spec.cx(), spec.cz(), r, spec.groundY(), cols);
    }

    /** 두 봉인이 같은가 — 다르면 <b>땅이 바뀐 것이다</b> */
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
        int r = before.radius();
        int w = 2 * r + 1;
        for (int i = 0; i < before.columns().length; i++) {
            int a = before.columns()[i];
            int b = after.columns()[i];
            if (a == b) {
                continue;
            }
            if (changed == 0) {
                firstX = before.cx() + (i % w) - r;
                firstZ = before.cz() + (i / w) - r;
            }
            changed++;
            if (Integer.bitCount(b) < Integer.bitCount(a)) {
                dug++;      // 채움이 줄었다 — 팠다
            } else {
                filled++;   // 채움이 늘었다 — 돋웠다
            }
        }
        out.add(INFO + "  잰 열 " + before.columnCount() + " · 바뀐 열 " + changed
                + " (판 것 " + dug + " · 돋운 것 " + filled + ")");
        if (changed > 0) {
            out.add(BAD + "  ★ 건축이 땅을 바꿨다 — " + changed + "열 (처음: " + firstX + "," + firstZ + ")");
            out.add(INFO + "    땅에 맞게 건물이 올라가야지, 건축에 맞게 지형이 생기면 안 된다.");
            out.add(INFO + "    땅을 바꿔야 한다면 **요청(land_requests.yml)** 으로 적어라 — "
                    + "그러면 땅이 집행하고, 원장에 적히고, 다시 지어도 안 바뀐다");
            violations.add("건축이 땅을 바꿨다(" + changed + "열)");
        } else {
            out.add(OK + "  땅이 한 열도 안 바뀌었다 — 건축은 땅 위에 올라앉았을 뿐이다");
        }

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
