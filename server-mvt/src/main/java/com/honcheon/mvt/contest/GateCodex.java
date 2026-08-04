package com.honcheon.mvt.contest;

import org.bukkit.Material;
import org.bukkit.World;

public final class GateCodex {
    private GateCodex() {
    }

    public static void build(World w, int ox, int oy, int oz) {
        if (w == null) {
            return;
        }

        Builder b = new Builder(w, ox, oy, oz);

        b.fill(-38, 38, 1, 44, -29, 9, Material.AIR);

        buildFoundation(b);
        buildApproach(b);
        buildLowerGatehouse(b);
        buildWingWalls(b);
        buildLowerRoof(b);
        buildUpperPavilion(b);
        buildUpperRoof(b);
        buildRailings(b);
        buildLanterns(b);
        buildCourtyardDetails(b);
        finishPassage(b);
    }

    private static void buildFoundation(Builder b) {
        b.fill(-31, 31, -3, -2, -13, 4, Material.COBBLESTONE);
        b.fill(-31, 31, -1, -1, -13, 4, Material.STONE_BRICKS);
        b.fill(-30, 30, 0, 0, -12, 3, Material.POLISHED_ANDESITE);

        b.fill(-29, 29, 0, 0, -11, 2, Material.STONE_BRICKS);
        b.fill(-27, 27, 0, 0, -10, 2, Material.SMOOTH_STONE);

        for (int x = -27; x <= 27; x++) {
            if ((x & 3) == 0) {
                b.set(x, 0, 2, Material.CHISELED_STONE_BRICKS);
                b.set(x, 0, -10, Material.CHISELED_STONE_BRICKS);
            }
        }

        for (int z = -10; z <= 2; z++) {
            if ((z & 3) == 0) {
                b.set(-29, 0, z, Material.CHISELED_STONE_BRICKS);
                b.set(29, 0, z, Material.CHISELED_STONE_BRICKS);
            }
        }

        b.fill(-5, 5, 0, 0, -24, 8, Material.POLISHED_ANDESITE);
        b.fill(-2, 2, 0, 0, -24, 8, Material.SMOOTH_STONE);

        for (int z = -24; z <= 8; z += 4) {
            b.fill(-5, 5, 0, 0, z, z, Material.STONE_BRICKS);
            b.fill(-2, 2, 0, 0, z, z, Material.CHISELED_STONE_BRICKS);
        }

        for (int x : new int[]{-27, -21, -15, -9, -7, 7, 9, 15, 21, 27}) {
            stoneBase(b, x, 1);
            stoneBase(b, x, -8);
        }
    }

    private static void buildApproach(Builder b) {
        b.fill(-12, 12, -1, -1, 4, 10, Material.STONE_BRICKS);
        b.fill(-11, 11, 0, 0, 4, 9, Material.POLISHED_ANDESITE);

        b.fill(-10, 10, -1, -1, 10, 10, Material.COBBLESTONE);
        b.fill(-9, 9, 0, 0, 9, 10, Material.SMOOTH_STONE);

        for (int x = -9; x <= 9; x++) {
            b.data(x, 0, 10,
                    "minecraft:polished_andesite_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]");
        }

        b.fill(-13, -11, 0, 0, 4, 8, Material.STONE_BRICKS);
        b.fill(11, 13, 0, 0, 4, 8, Material.STONE_BRICKS);

        for (int z = 4; z <= 8; z += 2) {
            b.set(-12, 1, z, Material.CHISELED_STONE_BRICKS);
            b.set(12, 1, z, Material.CHISELED_STONE_BRICKS);
            b.set(-12, 2, z, Material.STONE_BRICK_WALL);
            b.set(12, 2, z, Material.STONE_BRICK_WALL);
        }
    }

    private static void buildLowerGatehouse(Builder b) {
        b.fill(-28, -6, 1, 11, -7, 1, Material.CALCITE);
        b.fill(6, 28, 1, 11, -7, 1, Material.CALCITE);

        b.fill(-28, -6, 2, 3, -7, 1, Material.WHITE_TERRACOTTA);
        b.fill(6, 28, 2, 3, -7, 1, Material.WHITE_TERRACOTTA);
        b.fill(-28, -6, 9, 10, -7, 1, Material.WHITE_TERRACOTTA);
        b.fill(6, 28, 9, 10, -7, 1, Material.WHITE_TERRACOTTA);

        for (int x : new int[]{-27, -21, -15, -9, -7, 7, 9, 15, 21, 27}) {
            lowerColumn(b, x, 1);
            lowerColumn(b, x, -8);
        }

        b.fill(-28, -6, 3, 3, 1, 2, Material.MANGROVE_PLANKS);
        b.fill(6, 28, 3, 3, 1, 2, Material.MANGROVE_PLANKS);
        b.fill(-28, -6, 10, 10, 1, 2, Material.MANGROVE_PLANKS);
        b.fill(6, 28, 10, 10, 1, 2, Material.MANGROVE_PLANKS);

        b.fill(-28, -6, 3, 3, -8, -7, Material.MANGROVE_PLANKS);
        b.fill(6, 28, 3, 3, -8, -7, Material.MANGROVE_PLANKS);
        b.fill(-28, -6, 10, 10, -8, -7, Material.MANGROVE_PLANKS);
        b.fill(6, 28, 10, 10, -8, -7, Material.MANGROVE_PLANKS);

        for (int cx : new int[]{-24, -18, -12, 12, 18, 24}) {
            lowerWindow(b, cx);
        }

        buildGateArch(b);
        buildLowerBrackets(b);

        b.fill(-28, 28, 11, 11, -8, 2, Material.MANGROVE_PLANKS);
        b.fill(-29, 29, 12, 12, -9, 3, Material.RED_NETHER_BRICKS);

        for (int x = -28; x <= 28; x += 2) {
            b.set(x, 12, 3, Material.CHISELED_POLISHED_BLACKSTONE);
            b.set(x, 12, -9, Material.CHISELED_POLISHED_BLACKSTONE);
        }
    }

    private static void lowerColumn(Builder b, int x, int z) {
        b.fill(x - 1, x + 1, 1, 1, z, z, Material.POLISHED_ANDESITE);
        b.set(x, 2, z, Material.CHISELED_STONE_BRICKS);
        b.data(x, 3, z, "minecraft:stripped_mangrove_log[axis=y]");
        b.fillData(x, x, 4, 12, z, z, "minecraft:stripped_mangrove_log[axis=y]");
        b.set(x, 13, z, Material.MANGROVE_PLANKS);
    }

    private static void lowerWindow(Builder b, int cx) {
        b.fill(cx - 2, cx + 2, 4, 8, 1, 1, Material.GRAY_STAINED_GLASS_PANE);

        for (int y = 4; y <= 8; y++) {
            b.set(cx - 2, y, 2, Material.MANGROVE_FENCE);
            b.set(cx, y, 2, Material.MANGROVE_FENCE);
            b.set(cx + 2, y, 2, Material.MANGROVE_FENCE);
        }

        for (int x = cx - 2; x <= cx + 2; x++) {
            b.set(x, 4, 2, Material.MANGROVE_FENCE);
            b.set(x, 6, 2, Material.MANGROVE_FENCE);
            b.set(x, 8, 2, Material.MANGROVE_FENCE);
        }

        b.data(cx - 3, 4, 2,
                "minecraft:mangrove_trapdoor[facing=south,half=bottom,open=true,powered=false,waterlogged=false]");
        b.data(cx - 3, 5, 2,
                "minecraft:mangrove_trapdoor[facing=south,half=bottom,open=true,powered=false,waterlogged=false]");
        b.data(cx - 3, 6, 2,
                "minecraft:mangrove_trapdoor[facing=south,half=bottom,open=true,powered=false,waterlogged=false]");
        b.data(cx - 3, 7, 2,
                "minecraft:mangrove_trapdoor[facing=south,half=bottom,open=true,powered=false,waterlogged=false]");
        b.data(cx - 3, 8, 2,
                "minecraft:mangrove_trapdoor[facing=south,half=bottom,open=true,powered=false,waterlogged=false]");

        b.data(cx + 3, 4, 2,
                "minecraft:mangrove_trapdoor[facing=south,half=bottom,open=true,powered=false,waterlogged=false]");
        b.data(cx + 3, 5, 2,
                "minecraft:mangrove_trapdoor[facing=south,half=bottom,open=true,powered=false,waterlogged=false]");
        b.data(cx + 3, 6, 2,
                "minecraft:mangrove_trapdoor[facing=south,half=bottom,open=true,powered=false,waterlogged=false]");
        b.data(cx + 3, 7, 2,
                "minecraft:mangrove_trapdoor[facing=south,half=bottom,open=true,powered=false,waterlogged=false]");
        b.data(cx + 3, 8, 2,
                "minecraft:mangrove_trapdoor[facing=south,half=bottom,open=true,powered=false,waterlogged=false]");
    }

    private static void buildGateArch(Builder b) {
        b.fillData(-7, -7, 2, 12, 1, 1, "minecraft:stripped_mangrove_log[axis=y]");
        b.fillData(7, 7, 2, 12, 1, 1, "minecraft:stripped_mangrove_log[axis=y]");
        b.fillData(-7, -7, 2, 12, -8, -8, "minecraft:stripped_mangrove_log[axis=y]");
        b.fillData(7, 7, 2, 12, -8, -8, "minecraft:stripped_mangrove_log[axis=y]");

        b.fill(-6, 6, 13, 13, -8, 2, Material.MANGROVE_PLANKS);
        b.fill(-5, 5, 14, 14, -7, 1, Material.RED_NETHER_BRICKS);

        b.set(-6, 13, 2, Material.CHISELED_RED_SANDSTONE);
        b.set(6, 13, 2, Material.CHISELED_RED_SANDSTONE);
        b.fill(-4, 4, 14, 14, 2, 2, Material.CHISELED_POLISHED_BLACKSTONE);

        for (int x = -6; x <= 6; x += 2) {
            b.data(x, 13, 3,
                    "minecraft:mangrove_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]");
        }

        b.fill(-8, -6, 11, 12, 2, 3, Material.MANGROVE_PLANKS);
        b.fill(6, 8, 11, 12, 2, 3, Material.MANGROVE_PLANKS);
    }

    private static void buildLowerBrackets(Builder b) {
        for (int x : new int[]{-27, -21, -15, -9, -7, 7, 9, 15, 21, 27}) {
            b.fill(x - 2, x + 2, 11, 11, 2, 2, Material.MANGROVE_PLANKS);
            b.fill(x - 1, x + 1, 12, 12, 2, 3, Material.RED_NETHER_BRICK_SLAB);

            b.data(x - 2, 12, 3,
                    "minecraft:mangrove_stairs[facing=east,half=top,shape=straight,waterlogged=false]");
            b.data(x + 2, 12, 3,
                    "minecraft:mangrove_stairs[facing=west,half=top,shape=straight,waterlogged=false]");
            b.data(x, 13, 3,
                    "minecraft:red_nether_brick_stairs[facing=south,half=top,shape=straight,waterlogged=false]");

            b.fill(x - 2, x + 2, 11, 11, -9, -9, Material.MANGROVE_PLANKS);
            b.fill(x - 1, x + 1, 12, 12, -10, -9, Material.RED_NETHER_BRICK_SLAB);
        }
    }

    private static void buildWingWalls(Builder b) {
        b.fill(-36, -29, 1, 6, -8, 2, Material.STONE_BRICKS);
        b.fill(29, 36, 1, 6, -8, 2, Material.STONE_BRICKS);

        b.fill(-36, -29, 3, 5, -7, 1, Material.CALCITE);
        b.fill(29, 36, 3, 5, -7, 1, Material.CALCITE);

        for (int x : new int[]{-36, -32, 32, 36}) {
            b.fill(x, x, 1, 7, -8, 2, Material.STONE_BRICKS);
            b.set(x, 3, 2, Material.CHISELED_STONE_BRICKS);
            b.set(x, 6, 2, Material.CHISELED_STONE_BRICKS);
        }

        b.fill(-37, -28, 7, 7, -9, 3, Material.MANGROVE_PLANKS);
        b.fill(28, 37, 7, 7, -9, 3, Material.MANGROVE_PLANKS);

        for (int x = -37; x <= -28; x++) {
            b.data(x, 8, 3,
                    "minecraft:deepslate_tile_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]");
            b.data(x, 8, -9,
                    "minecraft:deepslate_tile_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
        }

        for (int x = 28; x <= 37; x++) {
            b.data(x, 8, 3,
                    "minecraft:deepslate_tile_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]");
            b.data(x, 8, -9,
                    "minecraft:deepslate_tile_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
        }

        b.fill(-36, -29, 8, 8, -8, 2, Material.DEEPSLATE_TILES);
        b.fill(29, 36, 8, 8, -8, 2, Material.DEEPSLATE_TILES);

        for (int x : new int[]{-35, -31, 31, 35}) {
            b.set(x, 2, 3, Material.STONE_BRICK_WALL);
            b.set(x, 3, 3, Material.MANGROVE_FENCE);
            b.set(x, 4, 3, Material.LANTERN);
        }
    }

    private static void buildLowerRoof(Builder b) {
        buildHipRoof(b, 32, 9, -3, 12, 4);

        b.fill(-32, 32, 11, 11, -12, -12, Material.RED_NETHER_BRICK_SLAB);
        b.fill(-32, 32, 11, 11, 6, 6, Material.RED_NETHER_BRICK_SLAB);

        for (int x = -32; x <= 32; x += 2) {
            b.data(x, 12, 6,
                    "minecraft:deepslate_tile_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]");
            b.data(x, 12, -12,
                    "minecraft:deepslate_tile_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
        }

        for (int z = -11; z <= 5; z += 2) {
            b.data(-32, 12, z,
                    "minecraft:deepslate_tile_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]");
            b.data(32, 12, z,
                    "minecraft:deepslate_tile_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        }

        roofCorner(b, -32, 6, 13, "west", "south");
        roofCorner(b, 32, 6, 13, "east", "south");
        roofCorner(b, -32, -12, 13, "west", "north");
        roofCorner(b, 32, -12, 13, "east", "north");

        b.fill(-25, 25, 16, 16, -7, 1, Material.MANGROVE_PLANKS);
        b.fill(-23, 23, 17, 17, -6, 0, Material.DARK_OAK_PLANKS);
    }

    private static void buildUpperPavilion(Builder b) {
        b.fill(-24, 24, 16, 16, -8, 1, Material.MANGROVE_PLANKS);
        b.fill(-23, 23, 17, 25, -7, 0, Material.WHITE_TERRACOTTA);

        b.fill(-23, 23, 17, 18, -7, 0, Material.MANGROVE_PLANKS);
        b.fill(-23, 23, 24, 25, -7, 0, Material.MANGROVE_PLANKS);

        for (int x : new int[]{-23, -17, -11, -5, 5, 11, 17, 23}) {
            upperColumn(b, x, 1);
            upperColumn(b, x, -8);
        }

        for (int cx : new int[]{-20, -14, -8, 0, 8, 14, 20}) {
            upperWindow(b, cx);
        }

        b.fill(-25, 25, 16, 16, 1, 4, Material.MANGROVE_PLANKS);
        b.fill(-25, 25, 15, 15, 3, 4, Material.RED_NETHER_BRICKS);

        for (int x = -24; x <= 24; x += 2) {
            b.set(x, 17, 4, Material.MANGROVE_FENCE);
        }

        b.fill(-25, -25, 17, 18, 2, 4, Material.MANGROVE_FENCE);
        b.fill(25, 25, 17, 18, 2, 4, Material.MANGROVE_FENCE);

        buildBlankPlaque(b);

        for (int x : new int[]{-23, -17, -11, -5, 5, 11, 17, 23}) {
            b.fill(x - 2, x + 2, 24, 24, 1, 2, Material.MANGROVE_PLANKS);
            b.fill(x - 1, x + 1, 25, 25, 2, 3, Material.RED_NETHER_BRICK_SLAB);
            b.data(x - 2, 25, 3,
                    "minecraft:mangrove_stairs[facing=east,half=top,shape=straight,waterlogged=false]");
            b.data(x + 2, 25, 3,
                    "minecraft:mangrove_stairs[facing=west,half=top,shape=straight,waterlogged=false]");
            b.data(x, 26, 3,
                    "minecraft:red_nether_brick_stairs[facing=south,half=top,shape=straight,waterlogged=false]");
        }
    }

    private static void upperColumn(Builder b, int x, int z) {
        b.set(x, 16, z, Material.CHISELED_STONE_BRICKS);
        b.fillData(x, x, 17, 25, z, z, "minecraft:stripped_mangrove_log[axis=y]");
        b.fill(x - 1, x + 1, 25, 25, z, z, Material.MANGROVE_PLANKS);
    }

    private static void upperWindow(Builder b, int cx) {
        int half = cx == 0 ? 3 : 2;

        b.fill(cx - half, cx + half, 19, 23, 1, 1, Material.BLACK_STAINED_GLASS_PANE);

        for (int x = cx - half; x <= cx + half; x += 2) {
            b.fill(x, x, 19, 23, 2, 2, Material.MANGROVE_FENCE);
        }

        for (int y = 19; y <= 23; y += 2) {
            b.fill(cx - half, cx + half, y, y, 2, 2, Material.MANGROVE_FENCE);
        }

        b.fill(cx - half - 1, cx - half - 1, 19, 23, 2, 2, Material.MANGROVE_PLANKS);
        b.fill(cx + half + 1, cx + half + 1, 19, 23, 2, 2, Material.MANGROVE_PLANKS);
        b.fill(cx - half - 1, cx + half + 1, 18, 18, 2, 2, Material.MANGROVE_PLANKS);
        b.fill(cx - half - 1, cx + half + 1, 24, 24, 2, 2, Material.MANGROVE_PLANKS);
    }

    private static void buildBlankPlaque(Builder b) {
        b.fill(-5, 5, 20, 23, 3, 3, Material.STRIPPED_DARK_OAK_WOOD);
        b.fill(-6, 6, 19, 19, 3, 3, Material.MANGROVE_PLANKS);
        b.fill(-6, 6, 24, 24, 3, 3, Material.MANGROVE_PLANKS);
        b.fill(-6, -6, 20, 23, 3, 3, Material.MANGROVE_PLANKS);
        b.fill(6, 6, 20, 23, 3, 3, Material.MANGROVE_PLANKS);

        b.set(-6, 19, 3, Material.CHISELED_RED_SANDSTONE);
        b.set(6, 19, 3, Material.CHISELED_RED_SANDSTONE);
        b.set(-6, 24, 3, Material.CHISELED_RED_SANDSTONE);
        b.set(6, 24, 3, Material.CHISELED_RED_SANDSTONE);

        b.data(-7, 21, 3,
                "minecraft:mangrove_stairs[facing=east,half=top,shape=straight,waterlogged=false]");
        b.data(7, 21, 3,
                "minecraft:mangrove_stairs[facing=west,half=top,shape=straight,waterlogged=false]");
    }

    private static void buildUpperRoof(Builder b) {
        buildHipRoof(b, 29, 11, -3, 27, 6);

        b.fill(-29, 29, 26, 26, -14, -14, Material.RED_NETHER_BRICK_SLAB);
        b.fill(-29, 29, 26, 26, 8, 8, Material.RED_NETHER_BRICK_SLAB);

        for (int x = -29; x <= 29; x += 2) {
            b.data(x, 27, 8,
                    "minecraft:deepslate_tile_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]");
            b.data(x, 27, -14,
                    "minecraft:deepslate_tile_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
        }

        for (int z = -13; z <= 7; z += 2) {
            b.data(-29, 27, z,
                    "minecraft:deepslate_tile_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]");
            b.data(29, 27, z,
                    "minecraft:deepslate_tile_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        }

        roofCorner(b, -29, 8, 28, "west", "south");
        roofCorner(b, 29, 8, 28, "east", "south");
        roofCorner(b, -29, -14, 28, "west", "north");
        roofCorner(b, 29, -14, 28, "east", "north");

        b.fill(-16, 16, 34, 34, -4, -2, Material.DEEPSLATE_TILES);
        b.fill(-15, 15, 35, 35, -3, -3, Material.POLISHED_DEEPSLATE);

        for (int x = -15; x <= 15; x++) {
            b.data(x, 36, -3,
                    "minecraft:deepslate_tile_slab[type=top,waterlogged=false]");
        }

        b.data(-17, 35, -3,
                "minecraft:deepslate_tile_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]");
        b.data(17, 35, -3,
                "minecraft:deepslate_tile_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");

        b.set(-18, 36, -3, Material.CHISELED_POLISHED_BLACKSTONE);
        b.set(18, 36, -3, Material.CHISELED_POLISHED_BLACKSTONE);
    }

    private static void buildHipRoof(Builder b, int halfX, int halfZ, int centerZ,
                                     int baseY, int maxLevel) {
        for (int x = -halfX; x <= halfX; x++) {
            for (int z = centerZ - halfZ; z <= centerZ + halfZ; z++) {
                int edgeX = halfX - Math.abs(x);
                int edgeZ = halfZ - Math.abs(z - centerZ);
                int level = Math.min(maxLevel, Math.min(edgeX / 3, edgeZ));
                int y = baseY + level;

                boolean southEdge = z == centerZ + halfZ - level;
                boolean northEdge = z == centerZ - halfZ + level;
                boolean westEdge = x == -halfX + level * 3;
                boolean eastEdge = x == halfX - level * 3;

                if (southEdge) {
                    b.data(x, y, z,
                            "minecraft:deepslate_tile_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]");
                } else if (northEdge) {
                    b.data(x, y, z,
                            "minecraft:deepslate_tile_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
                } else if (westEdge) {
                    b.data(x, y, z,
                            "minecraft:deepslate_tile_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]");
                } else if (eastEdge) {
                    b.data(x, y, z,
                            "minecraft:deepslate_tile_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
                } else if (((x + z) & 3) == 0) {
                    b.set(x, y, z, Material.POLISHED_DEEPSLATE);
                } else {
                    b.set(x, y, z, Material.DEEPSLATE_TILES);
                }
            }
        }
    }

    private static void roofCorner(Builder b, int x, int z, int y,
                                   String xFacing, String zFacing) {
        b.data(x, y, z,
                "minecraft:deepslate_tile_stairs[facing=" + zFacing
                        + ",half=bottom,shape=straight,waterlogged=false]");

        int ix = x < 0 ? 1 : -1;
        int iz = z < -3 ? 1 : -1;

        b.data(x + ix, y + 1, z,
                "minecraft:deepslate_tile_stairs[facing=" + xFacing
                        + ",half=bottom,shape=straight,waterlogged=false]");
        b.data(x, y + 1, z + iz,
                "minecraft:deepslate_tile_stairs[facing=" + zFacing
                        + ",half=bottom,shape=straight,waterlogged=false]");
        b.set(x, y + 2, z, Material.CHISELED_POLISHED_BLACKSTONE);
    }

    private static void buildRailings(Builder b) {
        for (int x = -30; x <= 30; x += 3) {
            if (Math.abs(x) > 6) {
                b.set(x, 1, 4, Material.STONE_BRICK_WALL);
                b.set(x, 2, 4, Material.POLISHED_BLACKSTONE_WALL);
            }
        }

        b.fill(-30, -7, 1, 1, 4, 4, Material.STONE_BRICK_WALL);
        b.fill(7, 30, 1, 1, 4, 4, Material.STONE_BRICK_WALL);

        for (int z = -20; z <= -11; z += 3) {
            b.set(-8, 1, z, Material.STONE_BRICK_WALL);
            b.set(8, 1, z, Material.STONE_BRICK_WALL);
        }

        b.fill(-8, -8, 1, 1, -20, -11, Material.STONE_BRICK_WALL);
        b.fill(8, 8, 1, 1, -20, -11, Material.STONE_BRICK_WALL);

        for (int x : new int[]{-30, -24, -18, -12, 12, 18, 24, 30}) {
            b.set(x, 2, 4, Material.CHISELED_STONE_BRICKS);
            b.set(x, 3, 4, Material.STONE_BRICK_WALL);
            b.data(x, 4, 4,
                    "minecraft:polished_andesite_slab[type=top,waterlogged=false]");
        }
    }

    private static void buildLanterns(Builder b) {
        hangingLantern(b, -7, 11, 4);
        hangingLantern(b, 7, 11, 4);
        hangingLantern(b, -18, 11, 4);
        hangingLantern(b, 18, 11, 4);
        hangingLantern(b, -27, 11, 4);
        hangingLantern(b, 27, 11, 4);

        hangingLantern(b, -12, 25, 4);
        hangingLantern(b, 12, 25, 4);
        hangingLantern(b, -23, 25, 4);
        hangingLantern(b, 23, 25, 4);

        for (int x : new int[]{-9, 9}) {
            for (int z : new int[]{7, 1, -11, -17}) {
                stoneLantern(b, x, z);
            }
        }

        for (int x : new int[]{-34, 34}) {
            stoneLantern(b, x, 4);
        }
    }

    private static void hangingLantern(Builder b, int x, int y, int z) {
        b.set(x, y, z, Material.MANGROVE_FENCE);
        b.set(x, y - 1, z, Material.IRON_BARS);
        b.data(x, y - 2, z, "minecraft:lantern[hanging=true,waterlogged=false]");
        b.data(x - 1, y, z,
                "minecraft:mangrove_stairs[facing=east,half=top,shape=straight,waterlogged=false]");
        b.data(x + 1, y, z,
                "minecraft:mangrove_stairs[facing=west,half=top,shape=straight,waterlogged=false]");
    }

    private static void stoneLantern(Builder b, int x, int z) {
        b.set(x, 0, z, Material.CHISELED_STONE_BRICKS);
        b.set(x, 1, z, Material.STONE_BRICK_WALL);
        b.set(x, 2, z, Material.CHISELED_STONE_BRICKS);
        b.set(x, 3, z, Material.LANTERN);
        b.data(x, 4, z,
                "minecraft:polished_andesite_slab[type=top,waterlogged=false]");
    }

    private static void buildCourtyardDetails(Builder b) {
        b.fill(-36, -34, 0, 0, -25, -11, Material.STONE_BRICKS);
        b.fill(34, 36, 0, 0, -25, -11, Material.STONE_BRICKS);

        for (int z = -25; z <= -11; z += 4) {
            b.set(-35, 1, z, Material.CHISELED_STONE_BRICKS);
            b.set(-35, 2, z, Material.MANGROVE_FENCE);
            b.set(35, 1, z, Material.CHISELED_STONE_BRICKS);
            b.set(35, 2, z, Material.MANGROVE_FENCE);
        }

        for (int x = -32; x <= 32; x += 4) {
            if (Math.abs(x) > 7) {
                b.set(x, 0, -16, Material.MOSSY_STONE_BRICKS);
                b.set(x, 0, -20, Material.CRACKED_STONE_BRICKS);
            }
        }

        for (int x : new int[]{-31, -25, 25, 31}) {
            b.set(x, 1, -14, Material.MOSS_BLOCK);
            b.set(x, 2, -14, Material.AZALEA_LEAVES);
            b.set(x, 1, -20, Material.MOSS_BLOCK);
            b.set(x, 2, -20, Material.FLOWERING_AZALEA_LEAVES);
        }

        for (int x : new int[]{-32, -20, 20, 32}) {
            b.set(x, 0, 7, Material.MOSSY_STONE_BRICKS);
            b.set(x, 1, 7, Material.MANGROVE_LEAVES);
            b.set(x, 2, 7, Material.FLOWERING_AZALEA_LEAVES);
        }
    }

    private static void finishPassage(Builder b) {
        b.fill(-5, 5, 1, 12, -24, 8, Material.AIR);
        b.fill(-5, 5, 0, 0, -24, 8, Material.POLISHED_ANDESITE);
        b.fill(-2, 2, 0, 0, -24, 8, Material.SMOOTH_STONE);

        for (int z = -24; z <= 8; z += 4) {
            b.fill(-5, 5, 0, 0, z, z, Material.STONE_BRICKS);
            b.fill(-2, 2, 0, 0, z, z, Material.CHISELED_STONE_BRICKS);
        }

        for (int z : new int[]{-8, -4, 0}) {
            b.fillData(-6, -6, 2, 11, z, z, "minecraft:stripped_mangrove_log[axis=y]");
            b.fillData(6, 6, 2, 11, z, z, "minecraft:stripped_mangrove_log[axis=y]");
            b.fill(-6, 6, 12, 12, z, z, Material.MANGROVE_PLANKS);

            b.data(-6, 11, z + 1,
                    "minecraft:mangrove_stairs[facing=east,half=top,shape=straight,waterlogged=false]");
            b.data(6, 11, z + 1,
                    "minecraft:mangrove_stairs[facing=west,half=top,shape=straight,waterlogged=false]");
        }

        b.fill(-5, 5, 1, 11, -24, 8, Material.AIR);
    }

    private static void stoneBase(Builder b, int x, int z) {
        b.fill(x - 1, x + 1, 0, 0, z - 1, z + 1, Material.POLISHED_ANDESITE);
        b.set(x, 1, z, Material.CHISELED_STONE_BRICKS);
    }

    private static final class Builder {
        private final World world;
        private final int ox;
        private final int oy;
        private final int oz;

        private Builder(World world, int ox, int oy, int oz) {
            this.world = world;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
        }

        private boolean valid(int x, int y, int z) {
            return x >= -40 && x <= 40
                    && y >= -4 && y <= 56
                    && z >= -30 && z <= 10;
        }

        private void set(int x, int y, int z, Material material) {
            if (valid(x, y, z)) {
                world.getBlockAt(ox + x, oy + y, oz + z).setType(material, false);
            }
        }

        private void data(int x, int y, int z, String blockData) {
            if (valid(x, y, z)) {
                world.getBlockAt(ox + x, oy + y, oz + z)
                        .setBlockData(org.bukkit.Bukkit.createBlockData(blockData), false);
            }
        }

        private void fill(int x1, int x2, int y1, int y2, int z1, int z2,
                          Material material) {
            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            int minY = Math.min(y1, y2);
            int maxY = Math.max(y1, y2);
            int minZ = Math.min(z1, z2);
            int maxZ = Math.max(z1, z2);

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        set(x, y, z, material);
                    }
                }
            }
        }

        private void fillData(int x1, int x2, int y1, int y2, int z1, int z2,
                              String blockData) {
            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            int minY = Math.min(y1, y2);
            int maxY = Math.max(y1, y2);
            int minZ = Math.min(z1, z2);
            int maxZ = Math.max(z1, z2);

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        data(x, y, z, blockData);
                    }
                }
            }
        }
    }
}
