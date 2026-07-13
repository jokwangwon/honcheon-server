package com.honcheon.mvt;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 원지(原地) — <b>우리가 손대기 전의 땅</b>.
 *
 * <h2>왜 이것이 있어야 하는가</h2>
 * 사용자: <i>"이 산 마음에 안 드니 없애줘"</i> — <b>지금 구조로는 못 한다.</b>
 * 그리고 {@code /혼천 땅갈아엎기} 는 <b>원장의 기억만 지웠다</b>. 그러면 다음 조성이
 * <b>이미 만든 산 위에 또 산을 쌓는다</b> — 이 프로젝트가 이미 앓은 병이다.
 * <b>반쪽 명령은 사용자를 속인다.</b>
 *
 * <h2>왜 청크를 지우지 않는가 (그 길을 검토하고 버렸다)</h2>
 * "시드에서 다시 생성한다"가 이론상 가장 깨끗하다. 그러나 <b>서버가 켜진 채로는 안전하지 않다</b>:
 * <ul>
 *   <li>Paper 는 region 파일({@code .mca})을 <b>열어 두고 캐시한다</b>. 켜진 채로 지우면
 *       메모리의 청크가 다시 그 파일로 저장되어 <b>되살아나거나 파일이 깨진다</b></li>
 *   <li>{@code World.regenerateChunk()} 는 <b>폐기(deprecated)됐고 실제로 재생성하지 않는다</b></li>
 *   <li>주 월드는 <b>언로드할 수 없다</b> (플레이어가 거기 있을 수 있다)</li>
 * </ul>
 * <b>안 되는 것을 된다고 하지 않는다.</b> 대신 <b>우리가 바꾸기 전의 높이를 적어 둔다</b> —
 * 되돌릴 때 그 높이로 깎고 채운다.
 *
 * <h2>정직한 한계 — 이것은 <b>완전한 복원이 아니다</b></h2>
 * 높이는 되돌아온다. 그러나 <b>광석·초목·자재의 결은 못 되살린다</b> (적지 않았으므로).
 * 되돌린 땅은 <b>흙과 돌과 잔디</b>다. <b>그것으로 충분한 이유</b>: 우리가 고치려는 병은
 * <b>형상의 누적</b>(산 위에 산)이지 자갈 한 알의 자리가 아니다.
 * <b>완전한 복원을 원하면 세계 재조성이다</b> ({@code scripts/fresh_start.sh}).
 */
final class TerrainBaseline {

    private TerrainBaseline() {
    }

    private static Path dir;

    static void load(Path dataDir) {
        dir = dataDir == null ? null : dataDir.resolve("baseline");
    }

    private static Path file(String placeId) {
        return dir == null ? null : dir.resolve(placeId + ".bin");
    }

    static boolean has(String placeId) {
        Path f = file(placeId);
        return f != null && Files.isRegularFile(f);
    }

    /**
     * <b>손대기 전에 적는다.</b> {@code prepare()} 보다 먼저 불려야 한다 — 한 번이라도 빚고 나면
     * 원지는 <b>영영 사라진다</b>.
     *
     * <p>열마다 자연 지면 y 하나 (물이면 음수로 표시). 반경 130이면 약 68,000열 = 270KB.
     * <b>싸다.</b> 되돌릴 수 없는 것에 비하면.
     */
    static void capture(World world, String placeId, int cx, int cz, int radius, int yHint) {
        Path f = file(placeId);
        if (f == null || Files.isRegularFile(f)) {
            return;   // 이미 적혀 있다 — ★ 덮어쓰지 않는다. 두 번째 조성의 땅은 원지가 아니다
        }
        try {
            Files.createDirectories(f.getParent());
            try (DataOutputStream out = new DataOutputStream(
                    new java.io.BufferedOutputStream(Files.newOutputStream(f)))) {
                out.writeInt(cx);
                out.writeInt(cz);
                out.writeInt(radius);
                for (int x = cx - radius; x <= cx + radius; x++) {
                    for (int z = cz - radius; z <= cz + radius; z++) {
                        out.writeShort(top(world, x, z, yHint + 90));
                    }
                }
            }
        } catch (IOException ignored) {
            // 못 적었으면 되돌릴 수 없다 — 그러나 조성은 진행된다 (되돌리기 명령이 그때 **거절한다**)
        }
    }

    /** 그 열의 최상단 (자연이든 아니든 — 원지를 뜰 때는 아직 우리가 아무것도 안 놓았다) */
    private static short top(World world, int x, int z, int from) {
        int hi = Math.min(from, world.getMaxHeight() - 1);
        for (int y = hi; y > world.getMinHeight(); y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (m == Material.WATER) {
                return (short) -y;   // 물은 음수로 — 되돌릴 때 물을 되돌린다
            }
            if (!m.isAir() && m != Material.SNOW && !isPlant(m)) {
                return (short) y;
            }
        }
        return (short) world.getSeaLevel();
    }

    private static boolean isPlant(Material m) {
        String n = m.name();
        return n.contains("LEAVES") || n.contains("LOG") || n.contains("GRASS")
                || n.contains("FLOWER") || n.contains("SAPLING") || n.contains("FERN")
                || n.contains("VINE") || n.contains("MUSHROOM") || n.contains("BAMBOO")
                || n.contains("SUGAR_CANE") || n.contains("CACTUS");
    }

    /**
     * <b>되돌린다</b> — 적어 둔 높이로 깎고 채운다.
     *
     * @return 되돌린 열 수. 원지가 없으면 -1 (<b>되돌릴 수 없다</b>)
     */
    static int restore(World world, String placeId) {
        Path f = file(placeId);
        if (f == null || !Files.isRegularFile(f)) {
            return -1;   // 원지를 안 적었다 — 되돌릴 수 없다. **그렇다고 말해야 한다**
        }
        try (DataInputStream in = new DataInputStream(
                new java.io.BufferedInputStream(Files.newInputStream(f)))) {
            int cx = in.readInt();
            int cz = in.readInt();
            int radius = in.readInt();
            int n = 0;
            for (int x = cx - radius; x <= cx + radius; x++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    short v = in.readShort();
                    boolean wet = v < 0;
                    int y = Math.abs(v);
                    column(world, x, z, y, wet);
                    n++;
                }
            }
            return n;
        } catch (IOException e) {
            return -1;
        }
    }

    /** 한 열을 원래 높이로 — 위는 걷어내고, 아래는 채운다 */
    private static void column(World world, int x, int z, int y, boolean wet) {
        for (int i = y + 1; i <= y + 90 && i < world.getMaxHeight(); i++) {
            Block b = world.getBlockAt(x, i, z);
            if (!b.getType().isAir()) {
                b.setType(Material.AIR);
            }
        }
        Block topBlock = world.getBlockAt(x, y, z);
        if (wet) {
            topBlock.setType(Material.WATER);
        } else if (topBlock.getType().isAir() || topBlock.isLiquid()) {
            topBlock.setType(Material.GRASS_BLOCK);
        }
        for (int i = y - 1; i > y - 10; i--) {
            Block b = world.getBlockAt(x, i, z);
            if (b.getType().isAir() || (b.isLiquid() && !wet)) {
                b.setType(i > y - 4 ? Material.DIRT : Material.STONE);
            }
        }
    }

    /** 원지를 잊는다 (되돌린 뒤에 부른다 — 다음 조성이 새 원지를 뜬다) */
    static void forget(String placeId) {
        Path f = file(placeId);
        try {
            if (f != null) {
                Files.deleteIfExists(f);
            }
        } catch (IOException ignored) {
            // 못 지웠다 — 다음 capture 가 덮어쓰지 않으므로 옛 원지가 남는다. 검수가 잡을 일은 아니다
        }
    }
}
