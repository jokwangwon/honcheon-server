package com.honcheon.mvt;

import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * <b>설 수 있는 자리인가 — 재는 손.</b>
 *
 * <p>오늘의 병: 아무도 <b>"여기 사람이 설 수 있는가"</b>를 재지 않았다. 앵커를 박을 때도, 사람을 내릴 때도.
 * 그래서 {@code 장터} 앵커가 <b>광장 우물 한복판</b>(= 마을 원점)에 박혔고, 세 곳의 순간이동
 * ({@link Antechamber} 도강 · {@link Dojang} 귀환 · {@code /혼천 출행})이 모두 사람을 우물에 떨어뜨렸다.
 *
 * <p><b>앵커는 표식이지 발판이 아니다.</b> 그 둘을 한 좌표로 쓴 것이 병이었다. 이 클래스는 그 둘을 가른다 —
 * 표식은 그대로 두고, <b>내릴 때마다 잰다</b>.
 *
 * <h2>세 가지를 잰다</h2>
 * <ol>
 *   <li><b>발밑이 단단한가</b> — (x, y-1, z) 가 충돌면을 가진 고체다. 물·용암·공기가 아니다.</li>
 *   <li><b>몸이 들어가는가</b> — (x, y), (x, y+1) 두 칸이 뚫려 있다. <b>물도 막힌 것으로 친다</b>
 *       (물속은 서 있는 것이 아니다).</li>
 *   <li>★ <b>걸어 나갈 수 있는가</b> — <b>이것이 우물을 거르는 조건이다.</b> 우물은 위 두 조건을
 *       <b>만족할 수 있다</b>: 물을 빼면 발밑이 단단하고 머리 위가 뚫린다. 그런데 못 나온다.
 *       그래서 <b>발로 뻗어 본다</b> — 설 수 있는 칸을 따라 BFS 로 걸어(한 칸 오르고 세 칸까지 내리며,
 *       문·쪽문·울짱문은 <b>사람이 여는 것</b>이므로 통과한다) <b>하늘이 보이는 칸</b>에 닿거나
 *       <b>{@value #ESCAPE_R} 칸 밖</b>으로 나가면 통과. 사방이 막혀 발이 못 뻗으면 <b>갇힌 것</b>이다.</li>
 * </ol>
 *
 * <p><b>왜 하늘인가.</b> 우물은 뚜껑(흑와 지붕)이 덮여 있어 하늘이 안 보인다. 집 안은 하늘이 안 보이지만
 * <b>문이 있다</b> — 문을 열고 나가면 길이고, 길에는 하늘이 있다. 그래서 "하늘"은 시작 칸이 아니라
 * <b>걸어서 닿을 수 있는 칸</b>에 대해 묻는다. 그 물음이 집과 우물을 가른다.
 *
 * <h2>시험 가능하게 만든 이유</h2>
 * 판정의 알맹이는 {@link Probe} 하나만 본다 — Bukkit World 가 아니다. 그래서 <b>서버를 켜지 않고도</b>
 * 가짜 세계(우물·독방·물·평지·문 달린 집)를 지어 눈을 시험할 수 있다. 눈을 만들었으면 시험해야 한다.
 */
public final class Standing {

    /** 걸어 나갔다고 인정하는 수평 거리 (하늘을 못 봐도 이만큼 걸으면 갇힌 것이 아니다) */
    public static final int ESCAPE_R = 16;
    /** BFS 상한 — 검사는 검사지 탐험이 아니다 */
    private static final int ESCAPE_CELLS = 4000;
    /** 착지 자리를 찾는 수평 반경 */
    public static final int SEARCH_R = 12;
    /** 착지 자리를 찾는 수직 폭 (앵커 y 기준 ±) */
    private static final int[] DY = {0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5, 6, -6};

    private Standing() {
    }

    // ══════════════════════════════════════════════════════════════════════
    //  세계를 보는 구멍 — 이것만 있으면 잴 수 있다 (Bukkit 이 아니어도 된다)
    // ══════════════════════════════════════════════════════════════════════

    /** 세계에 묻는 네 가지. 가짜 세계도 이 넷만 답하면 눈을 시험할 수 있다. */
    public interface Probe {
        /** 발이 딛는가 — 충돌면 있는 고체. 물·용암·공기·풀은 아니다. */
        boolean solid(int x, int y, int z);

        /** 몸이 들어가는가 — 뚫린 칸. <b>물·용암은 아니다</b> (물속은 서 있는 것이 아니다). */
        boolean body(int x, int y, int z);

        /** 사람이 여는가 — 문·쪽문·울짱문. 막혀 보여도 <b>걸어 나갈 수 있다</b>. */
        boolean openable(int x, int y, int z);

        /** 이 칸에서 하늘이 보이는가 — 머리(y+1) 위로 막은 것이 없다. */
        boolean sky(int x, int y, int z);

        int minY();

        int maxY();
    }

    /** 판정 결과 — <b>안 되면 이유를 말한다</b> (조용한 실패를 금한다). */
    public record Verdict(boolean ok, String why) {
        public static final Verdict OK = new Verdict(true, "설 수 있다");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ① 재는 손 — 순수 기하 (Probe 만 본다)
    // ══════════════════════════════════════════════════════════════════════

    /** ★ <b>여기 사람이 설 수 있는가.</b> 발밑 · 몸 · <b>걸어 나갈 길</b> 셋을 다 잰다. */
    public static Verdict measure(Probe p, int x, int y, int z) {
        if (y - 1 < p.minY() || y + 1 > p.maxY()) {
            return new Verdict(false, "세계 밖 (y=" + y + ")");
        }
        if (!p.solid(x, y - 1, z)) {
            return new Verdict(false, "발밑이 단단하지 않다 (물·용암·허공)");
        }
        if (!p.body(x, y, z) || !p.body(x, y + 1, z)) {
            return new Verdict(false, "몸이 들어갈 두 칸이 없다 (블록·물이 머리를 막는다)");
        }
        if (!escapes(p, x, y, z)) {
            return new Verdict(false, "★ 갇혔다 — 걸어 나갈 길이 없다 (우물·독방·구덩이)");
        }
        return Verdict.OK;
    }

    /** 발밑·몸만 — <b>이것만으로는 우물을 못 거른다</b> (그것이 오늘의 병이었다). */
    public static boolean footAndBody(Probe p, int x, int y, int z) {
        return y - 1 >= p.minY() && y + 1 <= p.maxY()
                && p.solid(x, y - 1, z) && p.body(x, y, z) && p.body(x, y + 1, z);
    }

    /** 걸어서 닿을 수 있는 칸 — 몸이 들어가거나(공기) <b>사람이 열 수 있으면</b>(문) 지난다. */
    private static boolean walkable(Probe p, int x, int y, int z) {
        return (p.body(x, y, z) || p.openable(x, y, z))
                && (p.body(x, y + 1, z) || p.openable(x, y + 1, z));
    }

    private static boolean footing(Probe p, int x, int y, int z) {
        return y - 1 >= p.minY() && y + 1 <= p.maxY()
                && p.solid(x, y - 1, z) && walkable(p, x, y, z);
    }

    /**
     * ★ <b>걸어 나갈 수 있는가</b> — 우물을 거르는 유일한 조건.
     *
     * <p>설 수 있는 칸을 따라 BFS. 한 칸 오르고 세 칸까지 내린다 (사람의 다리). 문은 연다.
     * <b>하늘이 보이는 칸</b>에 닿거나 <b>{@value #ESCAPE_R} 칸 밖</b>으로 나가면 나간 것이다.
     * 우물은 사방이 조약돌 담(COBBLESTONE_WALL)이라 <b>첫 걸음에서 발이 안 뻗는다</b> — 그래서 잡힌다.
     */
    public static boolean escapes(Probe p, int sx, int sy, int sz) {
        if (p.sky(sx, sy, sz)) {
            return true;   // 이미 하늘 아래다
        }
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{sx, sy, sz});
        seen.add(key(sx, sy, sz));
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty() && seen.size() < ESCAPE_CELLS) {
            int[] c = q.poll();
            for (int[] d : dirs) {
                int nx = c[0] + d[0];
                int nz = c[2] + d[1];
                for (int dy = 1; dy >= -3; dy--) {          // 한 칸 오르고, 세 칸까지 내린다
                    int ny = c[1] + dy;
                    if (!footing(p, nx, ny, nz)) {
                        continue;
                    }
                    long k = key(nx, ny, nz);
                    if (!seen.add(k)) {
                        break;
                    }
                    if (p.sky(nx, ny, nz)) {
                        return true;                        // 하늘을 봤다
                    }
                    if (Math.max(Math.abs(nx - sx), Math.abs(nz - sz)) >= ESCAPE_R) {
                        return true;                        // 충분히 멀리 걸었다 (덮인 회랑·처마 밑)
                    }
                    q.add(new int[]{nx, ny, nz});
                    break;                                  // 그 방향은 한 높이만 (가장 높은 발판)
                }
            }
        }
        return false;   // ★ 발이 못 뻗었다 — 우물이다
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | ((long) z & 0x3FFFFFF);
    }

    /**
     * ★ <b>설 수 있는 자리를 실제로 찾는다</b> — 앵커 근처를 뒤져서.
     *
     * <p>가까울수록·높이 차가 적을수록 좋다 ({@code 수평*2 + |높이차|*3}). 높이에 벌점을 더 주는 이유:
     * 우물 한복판에서 위로 뒤지면 <b>우물 지붕 위</b>가 먼저 걸린다 (거기서도 걸어 내려올 수는 있다 —
     * 그러나 사람을 지붕에 얹는 것은 답이 아니다). 옆으로 두 칸이면 광장 돌바닥이다.
     *
     * <p>결정론이다 — 같은 세계·같은 앵커면 늘 같은 자리. 난수 없음.
     *
     * @return 설 수 있는 자리의 <b>발 좌표</b>, 못 찾으면 {@code null}
     */
    public static int[] search(Probe p, int ax, int ay, int az, int radius) {
        int[] best = null;
        int bestCost = Integer.MAX_VALUE;
        for (int r = 0; r <= radius; r++) {
            if (r * 2 > bestCost) {
                break;   // 더 멀리 가 봐야 진다
            }
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;   // 껍질만
                    }
                    for (int dy : DY) {
                        int cost = r * 2 + Math.abs(dy) * 3;
                        if (cost >= bestCost) {
                            continue;
                        }
                        int x = ax + dx, y = ay + dy, z = az + dz;
                        if (!footAndBody(p, x, y, z) || !escapes(p, x, y, z)) {
                            continue;
                        }
                        best = new int[]{x, y, z};
                        bestCost = cost;
                    }
                }
            }
        }
        return best;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ② Bukkit 의 눈 — 위 손을 진짜 세계에 붙인다
    // ══════════════════════════════════════════════════════════════════════

    /** 진짜 세계를 보는 구멍. */
    public static Probe of(World world) {
        return new Probe() {
            @Override
            public boolean solid(int x, int y, int z) {
                Block b = world.getBlockAt(x, y, z);
                return !b.isPassable() && !b.isLiquid();
            }

            @Override
            public boolean body(int x, int y, int z) {
                Block b = world.getBlockAt(x, y, z);
                return b.isPassable() && !b.isLiquid();
            }

            @Override
            public boolean openable(int x, int y, int z) {
                org.bukkit.Material m = world.getBlockAt(x, y, z).getType();
                return Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m);
            }

            @Override
            public boolean sky(int x, int y, int z) {
                return world.getHighestBlockYAt(x, z) <= y + 1;
            }

            @Override
            public int minY() {
                return world.getMinHeight();
            }

            @Override
            public int maxY() {
                return world.getMaxHeight() - 1;
            }
        };
    }

    /** 이 자리에 사람이 설 수 있는가 — 이유까지. */
    public static Verdict measure(Location at) {
        if (at == null || at.getWorld() == null) {
            return new Verdict(false, "세계가 없다");
        }
        return measure(of(at.getWorld()), at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }

    /**
     * ★ <b>내리는 자리</b> — 앵커를 <b>재고</b>, 서지 못하면 <b>근처에서 실제로 찾는다</b>.
     *
     * <p>앵커 자체가 멀쩡하면 앵커를 그대로(칸 중앙으로 정렬해) 돌려준다. 못 서면 {@link #search} 가
     * 찾은 자리를 돌려준다. <b>못 찾으면 {@code null}</b> — 조용한 기본값(월드 스폰)을 여기서 만들지 않는다.
     * 부르는 쪽이 짖어야 한다.
     */
    public static Location landing(Location anchor) {
        return landing(anchor, SEARCH_R);
    }

    public static Location landing(Location anchor, int radius) {
        if (anchor == null || anchor.getWorld() == null) {
            return null;
        }
        World w = anchor.getWorld();
        Probe p = of(w);
        int ax = anchor.getBlockX(), ay = anchor.getBlockY(), az = anchor.getBlockZ();
        if (measure(p, ax, ay, az).ok()) {
            return center(w, ax, ay, az, anchor);
        }
        int[] spot = search(p, ax, ay, az, radius);
        return spot == null ? null : center(w, spot[0], spot[1], spot[2], anchor);
    }

    private static Location center(World w, int x, int y, int z, Location like) {
        return new Location(w, x + 0.5, y, z + 0.5, like.getYaw(), like.getPitch());
    }

    /** 앵커가 원래 자리와 다른 자리로 밀렸는가 (사람에게 말해 줄 값) */
    public static boolean moved(Location anchor, Location landing) {
        return anchor != null && landing != null
                && (anchor.getBlockX() != landing.getBlockX()
                || anchor.getBlockY() != landing.getBlockY()
                || anchor.getBlockZ() != landing.getBlockZ());
    }

    /** 사람·로그에 찍는 한 줄 */
    public static String describe(Location at) {
        return at == null ? "(없음)"
                : "(" + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ() + ")";
    }
}
