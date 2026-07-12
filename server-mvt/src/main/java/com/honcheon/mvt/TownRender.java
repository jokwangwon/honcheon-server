package com.honcheon.mvt;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 청하현 조감도 렌더 — 육안 검수를 파일로 바꾼다.
 *
 * <p>외부 의존성 0. javax.imageio + java.awt.image 만 쓴다 (Paper 서버 JVM에 이미 있다).
 * 난수 없음(결정론) — 같은 마을이면 같은 PNG.
 *
 * <p>렌더 3종:
 * <ul>
 *   <li>town_top.png — 탑다운 지도 (4px/블록, 고도 음영 + 북서 릴리프)</li>
 *   <li>town_iso.png — 45° 등각 조감 (상단면 밝게·측면 어둡게 — '어색함'을 보는 주 뷰)</li>
 *   <li>bld_&lt;이름&gt;.png — 앵커별 25×25 클로즈업 등각 확대</li>
 * </ul>
 *
 * <pre>
 *   MvtCommand 호출 예:
 *     File dir = new File(plugin.getDataFolder(), "render");
 *     for (String line : TownRender.render(player.getWorld(), cx, cy, cz, dir)) {
 *         player.sendMessage(ChatColor.GRAY + line);
 *     }
 * </pre>
 */
public final class TownRender {

    private TownRender() {
    }

    private static final int R = 65;          // 마을 스캔 반경
    private static final int Y_DOWN = 5;      // 지면 아래
    private static final int Y_UP = 25;       // 지면 위
    private static final int TOP_SCALE = 4;   // 탑다운 px/블록

    // 등각 투영 — 타일 반폭/반높이, 블록 1칸 높이의 픽셀
    private static final int ISO_HW = 4;
    private static final int ISO_HH = 2;
    private static final int ISO_HZ = 4;
    private static final int CLOSE_HW = 12;
    private static final int CLOSE_HH = 6;
    private static final int CLOSE_HZ = 12;
    private static final int CLOSE_R = 12;    // 25x25 = ±12
    private static final int CLOSE_UP = 20;

    private static final int FALLBACK = 0x808080;

    /**
     * 앵커가 없는 클로즈업 대상 — {dx, dz, 지면 탐침 dz}.
     * 탐침 dz=0 이면 마을 지면(cy)을 그대로 쓴다(평탄화 안). 0이 아니면 그 지점의 지표를 읽는다
     * (폐사당은 평탄화 밖 자연 지형 위 — 구조물 밖 한 점에서 지면을 뽑아야 결정론이 산다).
     */
    private static final Map<String, int[]> EXTRA_SPOTS = new LinkedHashMap<>();

    static {
        EXTRA_SPOTS.put("잡화점", new int[]{8, -6, 0});        // 장터 붉은 차양 노점·점포 (마을 안 = 평탄)
        EXTRA_SPOTS.put("폐사당", new int[]{-75, -75, -15});   // 담장 밖 북서 외곽 (담 흔적 밖에서 지면 탐침)
    }

    // ─── 블록 팔레트 (대표 RGB — 1.21.4 실재 Material 만) ───

    private static final Map<Material, Integer> PALETTE = new EnumMap<>(Material.class);

    private static void p(Material m, int rgb) {
        PALETTE.put(m, rgb);
    }

    static {
        // 지면·길
        p(Material.GRASS_BLOCK, 0x6A9A3C);
        p(Material.DIRT, 0x8B6A46);
        p(Material.COARSE_DIRT, 0x7A5C3E);
        p(Material.DIRT_PATH, 0xA98E58);
        p(Material.GRAVEL, 0x958E88);
        p(Material.SAND, 0xDBD3A0);
        p(Material.PODZOL, 0x5C3E1E);
        p(Material.SHORT_GRASS, 0x5E9A3C);
        p(Material.FERN, 0x5E9A3C);
        p(Material.TALL_GRASS, 0x5E9A3C);
        p(Material.WATER, 0x3F5FBF);

        // 석재
        p(Material.STONE, 0x8E8E8E);
        p(Material.SMOOTH_STONE, 0xA2A2A2);
        p(Material.STONE_BRICKS, 0x7C7C7C);
        p(Material.STONE_BRICK_STAIRS, 0x7C7C7C);
        p(Material.STONE_BRICK_SLAB, 0x7C7C7C);
        p(Material.STONE_BRICK_WALL, 0x7C7C7C);
        p(Material.CRACKED_STONE_BRICKS, 0x767570);
        p(Material.MOSSY_STONE_BRICKS, 0x6E7A64);
        p(Material.CHISELED_STONE_BRICKS, 0x787878);
        p(Material.COBBLESTONE, 0x848484);
        p(Material.COBBLESTONE_WALL, 0x848484);
        p(Material.COBBLESTONE_STAIRS, 0x848484);
        p(Material.COBBLESTONE_SLAB, 0x848484);
        p(Material.MOSSY_COBBLESTONE, 0x6F7A5E);
        p(Material.STONE_PRESSURE_PLATE, 0x8E8E8E);

        // 흑와 (심층암 계열)
        p(Material.DEEPSLATE_TILES, 0x36363A);
        p(Material.DEEPSLATE_TILE_STAIRS, 0x36363A);
        p(Material.DEEPSLATE_TILE_SLAB, 0x3A3A3E);
        p(Material.DEEPSLATE_TILE_WALL, 0x36363A);
        p(Material.DEEPSLATE_BRICKS, 0x3C3C40);
        p(Material.DEEPSLATE_BRICK_STAIRS, 0x3C3C40);
        p(Material.DEEPSLATE_BRICK_SLAB, 0x3C3C40);
        p(Material.COBBLED_DEEPSLATE, 0x4A4A4E);

        // 벽 (수묵 3색)
        p(Material.WHITE_TERRACOTTA, 0xD1B1A1);
        p(Material.LIGHT_GRAY_TERRACOTTA, 0x876B62);
        p(Material.GRAY_TERRACOTTA, 0x3A2B25);
        p(Material.BRICKS, 0x96614F);
        p(Material.MUD_BRICKS, 0x8C6D5C);
        p(Material.MUD_BRICK_STAIRS, 0x8C6D5C);
        p(Material.MUD_BRICK_SLAB, 0x8C6D5C);
        p(Material.PACKED_MUD, 0x8F6B51);

        // 목재
        p(Material.DARK_OAK_LOG, 0x4A3319);
        p(Material.STRIPPED_DARK_OAK_LOG, 0x5C4326);
        p(Material.DARK_OAK_PLANKS, 0x422A16);
        p(Material.DARK_OAK_STAIRS, 0x422A16);
        p(Material.DARK_OAK_SLAB, 0x422A16);
        p(Material.DARK_OAK_FENCE, 0x422A16);
        p(Material.DARK_OAK_TRAPDOOR, 0x4A3319);
        p(Material.DARK_OAK_DOOR, 0x4A3319);
        p(Material.DARK_OAK_HANGING_SIGN, 0x4A3319);
        p(Material.DARK_OAK_WALL_HANGING_SIGN, 0x4A3319);
        p(Material.SPRUCE_LOG, 0x3B2B14);
        p(Material.STRIPPED_SPRUCE_LOG, 0x6F5734);
        p(Material.SPRUCE_PLANKS, 0x725430);
        p(Material.SPRUCE_STAIRS, 0x725430);
        p(Material.SPRUCE_SLAB, 0x725430);
        p(Material.SPRUCE_FENCE, 0x725430);
        p(Material.SPRUCE_TRAPDOOR, 0x725430);
        p(Material.SPRUCE_DOOR, 0x725430);
        p(Material.SPRUCE_PRESSURE_PLATE, 0x725430);
        p(Material.OAK_LOG, 0x6B5432);
        p(Material.OAK_PLANKS, 0xA2824E);
        p(Material.STRIPPED_OAK_LOG, 0xB39158);
        p(Material.OAK_FENCE, 0xA2824E);
        p(Material.OAK_SIGN, 0xA2824E);
        p(Material.OAK_WALL_SIGN, 0xA2824E);
        p(Material.BAMBOO_PLANKS, 0xC3B172);
        p(Material.BAMBOO_FENCE, 0xC3B172);
        p(Material.SCAFFOLDING, 0xC2A85C);
        p(Material.LADDER, 0x8A6A3E);

        // 지붕 변종
        p(Material.OXIDIZED_CUT_COPPER, 0x4F9880);
        p(Material.OXIDIZED_CUT_COPPER_STAIRS, 0x4F9880);
        p(Material.OXIDIZED_CUT_COPPER_SLAB, 0x4F9880);

        // 창·조명
        p(Material.GLASS_PANE, 0xC6E5E8);
        p(Material.GLASS, 0xC6E5E8);
        p(Material.IRON_BARS, 0xA8A8A8);
        p(Material.LANTERN, 0xF0B45A);
        p(Material.SOUL_LANTERN, 0x4FC3C7);
        p(Material.CAMPFIRE, 0xE07B3A);
        p(Material.SOUL_CAMPFIRE, 0x2E9EA6);
        p(Material.CANDLE, 0xE8DFC8);
        p(Material.IRON_CHAIN, 0x6A6A72);
        p(Material.TORCH, 0xF0B45A);
        p(Material.WALL_TORCH, 0xF0B45A);

        // 색 (수묵 검수의 대상 — 렌더에서 눈에 띄어야 한다)
        p(Material.RED_WOOL, 0xA02722);
        p(Material.WHITE_WOOL, 0xE9ECEC);
        p(Material.RED_CARPET, 0xA02722);
        p(Material.WHITE_CARPET, 0xE9ECEC);
        p(Material.LIGHT_GRAY_CARPET, 0x9D9D97);
        p(Material.BROWN_CARPET, 0x6B4B2A);
        p(Material.CHERRY_LEAVES, 0xE9A7C3);
        p(Material.CHERRY_LOG, 0x8A5A62);
        p(Material.CHERRY_PLANKS, 0xE2B4A6);
        p(Material.OAK_LEAVES, 0x4C7A2E);
        p(Material.SPRUCE_LEAVES, 0x3E5E33);
        p(Material.PINK_PETALS, 0xE9A7C3);
        p(Material.POPPY, 0xC1372B);

        // 소품
        p(Material.DECORATED_POT, 0xB5573F);
        p(Material.CHISELED_BOOKSHELF, 0x70502E);
        p(Material.BOOKSHELF, 0x70502E);
        p(Material.BARREL, 0x8A6A3E);
        p(Material.CHEST, 0xA0813F);
        p(Material.LECTERN, 0x9A7B4F);
        p(Material.HAY_BLOCK, 0xA68C1F);
        p(Material.CAULDRON, 0x4A4A4A);
        p(Material.BREWING_STAND, 0x6A6A6A);
        p(Material.SMOKER, 0x6A6A6A);
        p(Material.BLAST_FURNACE, 0x6A6A6A);
        p(Material.FURNACE, 0x7A7A7A);
        p(Material.ANVIL, 0x4A4A4A);
        p(Material.SMITHING_TABLE, 0x3E3A3E);
        p(Material.LOOM, 0x9A7B4F);
        p(Material.COMPOSTER, 0x6B4B2A);
        p(Material.NOTE_BLOCK, 0x5A3E22);
        p(Material.FLOWER_POT, 0x9B4B32);
        p(Material.COBWEB, 0xDCDCDC);
        p(Material.HOPPER, 0x4A4A4A);
    }

    // ─── 팩 인식 팔레트 (리소스팩 텍스처 PNG → 대표색) ───
    //
    // 조감도가 "팩을 켠 클라이언트가 보는 화면"과 어긋나면 검산이 거짓말이 된다.
    // 그래서 렌더 시작 시 리소스팩의 블록 텍스처를 실제로 읽어 대표색(알파 가중 평균 RGB)을
    // 계산하고, 하드코딩 팔레트 위에 덮어쓴다. 텍스처가 없으면 하드코딩 값으로 폴백한다
    // (팩 게이트 불가침 — 팩이 없어도 렌더는 돌아야 한다).

    /** 렌더 1회 동안 유효한 팩 대표색 캐시 — Material → RGB. render() 진입 시 재적재. */
    private static final Map<Material, Integer> PACK = new EnumMap<>(Material.class);

    /** 텍스처를 못 찾은 Material — 재탐색(디스크 stat) 반복을 막는 음성 캐시. */
    private static final Set<Material> PACK_MISS = new LinkedHashSet<>();

    private static int packHits;
    private static int packMisses;

    /** 베이스 블록 텍스처를 공유하는 변종 접미사 — 계단·반블록·담장·울타리·문틀 등. */
    private static final String[] SUFFIXES = {
            "_stairs", "_slab", "_wall", "_fence_gate", "_fence",
            "_pressure_plate", "_button", "_carpet", "_wall_sign", "_sign",
            "_wall_hanging_sign", "_hanging_sign",
    };

    /** 접두사 변종 — 벽면 부착형은 본체 텍스처를 쓴다 (WALL_TORCH → torch.png). */
    private static final String[] PREFIXES = {"wall_"};

    /** 파일명이 규칙에서 벗어나는 예외 — 규칙보다 먼저 시도한다. */
    private static final Map<Material, String> SPECIAL = new EnumMap<>(Material.class);

    /**
     * 바이옴 틴트 블록 — 텍스처가 회색조라 평균색만 쓰면 잔디가 잿빛이 된다.
     * 클라이언트가 곱하는 틴트를 결정론 상수로 재현한다 (평원 기준 — 난수 없음).
     */
    private static final Map<Material, Integer> TINT = new EnumMap<>(Material.class);

    static {
        SPECIAL.put(Material.WATER, "water_still");
        SPECIAL.put(Material.LAVA, "lava_still");
        SPECIAL.put(Material.GRASS_BLOCK, "grass_block_top");
        SPECIAL.put(Material.DIRT_PATH, "dirt_path_top");
        SPECIAL.put(Material.HAY_BLOCK, "hay_block_side");
        SPECIAL.put(Material.PODZOL, "podzol_top");
        SPECIAL.put(Material.CAMPFIRE, "campfire_log");
        SPECIAL.put(Material.SOUL_CAMPFIRE, "soul_campfire_log_lit");
        SPECIAL.put(Material.LADDER, "ladder");
        SPECIAL.put(Material.SCAFFOLDING, "scaffolding_top");
        SPECIAL.put(Material.BOOKSHELF, "bookshelf");
        SPECIAL.put(Material.CHISELED_BOOKSHELF, "chiseled_bookshelf_empty");
        SPECIAL.put(Material.BARREL, "barrel_top");
        SPECIAL.put(Material.COMPOSTER, "composter_side");
        SPECIAL.put(Material.LECTERN, "lectern_top");
        SPECIAL.put(Material.LOOM, "loom_top");
        SPECIAL.put(Material.SMITHING_TABLE, "smithing_table_top");
        SPECIAL.put(Material.NOTE_BLOCK, "note_block");
        SPECIAL.put(Material.FLOWER_POT, "flower_pot");
        SPECIAL.put(Material.CAULDRON, "cauldron_side");
        SPECIAL.put(Material.HOPPER, "hopper_outside");
        SPECIAL.put(Material.ANVIL, "anvil_top");
        SPECIAL.put(Material.FURNACE, "furnace_front");
        SPECIAL.put(Material.SMOKER, "smoker_front");
        SPECIAL.put(Material.BLAST_FURNACE, "blast_furnace_front");
        SPECIAL.put(Material.BREWING_STAND, "brewing_stand_base");
        SPECIAL.put(Material.DECORATED_POT, "decorated_pot_side");
        SPECIAL.put(Material.CHEST, "oak_planks");     // 상자는 엔티티 모델 — 나무 톤으로 근사
        SPECIAL.put(Material.IRON_BARS, "iron_bars");
        SPECIAL.put(Material.GLASS_PANE, "glass");
        SPECIAL.put(Material.PINK_PETALS, "pink_petals");

        // 평원(plains) 기준 틴트 — 클라이언트가 회색조 텍스처에 곱하는 값
        int grass = 0x91BD59;
        int foliage = 0x77AB2F;
        TINT.put(Material.GRASS_BLOCK, grass);
        TINT.put(Material.SHORT_GRASS, grass);
        TINT.put(Material.TALL_GRASS, grass);
        TINT.put(Material.FERN, grass);
        TINT.put(Material.OAK_LEAVES, foliage);
        TINT.put(Material.SPRUCE_LEAVES, 0x619961);   // 가문비는 바이옴 무관 고정 틴트
        TINT.put(Material.WATER, 0x3F76E4);
    }

    /**
     * 리소스팩의 블록 텍스처 디렉터리를 찾는다.
     * 우선순위: 시스템 프로퍼티/환경변수 {@code HONCHEON_PACK_DIR} → 플러그인 데이터폴더·작업 디렉터리에서
     * 위로 거슬러 올라가며 {@code resourcepack/assets/minecraft/textures/block} 탐색.
     * 절대경로 하드코딩 없음 — 레포를 어디에 두든 붙는다.
     */
    private static File findPackBlockDir(File outDir) {
        String tail = "assets/minecraft/textures/block".replace('/', File.separatorChar);
        String hint = System.getProperty("HONCHEON_PACK_DIR", System.getenv("HONCHEON_PACK_DIR"));
        if (hint != null && !hint.isBlank()) {
            // 힌트는 팩 루트(resourcepack/)일 수도, 블록 텍스처 폴더 자체일 수도 있다 — 둘 다 받는다.
            File h = new File(hint);
            File asBlocks = new File(h, tail);
            if (asBlocks.isDirectory()) {
                return asBlocks;
            }
            if (h.isDirectory() && h.getName().equals("block")) {
                return h;
            }
            log("HONCHEON_PACK_DIR 이 가리키는 곳에 블록 텍스처가 없다 — 상대경로 후보로 넘어간다: " + hint);
        }
        // 후보 기점: 렌더 출력 폴더(= 플러그인 데이터폴더 하위) 와 서버 작업 디렉터리.
        // 각 기점에서 최대 8단계 위로 올라가며 <base>/resourcepack/... 을 찾는다.
        // (run/mvt 에서 기동하면 run/mvt → run → <레포루트>/resourcepack 에서 걸린다)
        List<File> roots = new ArrayList<>();
        roots.add(outDir);
        roots.add(new File(System.getProperty("user.dir", ".")));
        for (File root : roots) {
            File cur = root.getAbsoluteFile();
            for (int i = 0; i < 8 && cur != null; i++, cur = cur.getParentFile()) {
                File blocks = new File(new File(cur, "resourcepack"), tail);
                if (blocks.isDirectory()) {
                    return blocks;
                }
            }
        }
        return null;
    }

    /** Material → 텍스처 파일명 후보 (우선순위 순). 첫 번째로 실재하는 PNG 를 쓴다. */
    private static List<String> textureCandidates(Material m) {
        String n = m.name().toLowerCase(Locale.ROOT);
        Set<String> c = new LinkedHashSet<>();
        String special = SPECIAL.get(m);
        if (special != null) {
            c.add(special);
        }
        addForms(c, n);
        for (String suf : SUFFIXES) {
            if (n.endsWith(suf) && n.length() > suf.length()) {
                String base = n.substring(0, n.length() - suf.length());
                addForms(c, base);
                addForms(c, base + "s");        // 복수형 보정: deepslate_tile→deepslate_tiles, stone_brick→stone_bricks
                addForms(c, base + "_planks");  // 목재 변종: dark_oak_stairs → dark_oak_planks
                addForms(c, base + "_wool");    // 카펫: red_carpet → red_wool
                addForms(c, base + "_block");
                break;                          // 접미사는 하나만 (가장 긴 것부터 배열 순)
            }
        }
        for (String pre : PREFIXES) {
            if (n.startsWith(pre) && n.length() > pre.length()) {
                addForms(c, n.substring(pre.length()));
            }
        }
        return new ArrayList<>(c);
    }

    /** 한 이름의 파생형 — 본체·윗면·옆면·아랫면 (문·풀 등 조각 텍스처 대응). */
    private static void addForms(Set<String> c, String base) {
        c.add(base);
        c.add(base + "_top");
        c.add(base + "_side");
        c.add(base + "_bottom");
    }

    /**
     * 텍스처 PNG 의 대표색 — 알파 가중 평균 RGB.
     * 애니메이션 텍스처(세로로 이어붙인 프레임)는 첫 프레임만 쓴다(결정론).
     * 거의 전부 투명하면 null (대표색을 뽑을 근거가 없다 → 폴백).
     */
    private static Integer averageColor(File png) {
        BufferedImage img;
        try {
            img = ImageIO.read(png);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (img == null) {
            return null;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        if (h > w && h % w == 0) {
            h = w;   // 애니메이션 시트 — 첫 프레임(정사각)만
        }
        double sr = 0, sg = 0, sb = 0, sa = 0;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int argb = img.getRGB(x, y);
                double a = ((argb >>> 24) & 0xFF) / 255.0;
                if (a <= 0) {
                    continue;
                }
                sr += ((argb >> 16) & 0xFF) * a;
                sg += ((argb >> 8) & 0xFF) * a;
                sb += (argb & 0xFF) * a;
                sa += a;
            }
        }
        if (sa < 0.02 * w * h) {
            return null;   // 사실상 투명 — 대표색 없음
        }
        int r = clamp((int) Math.round(sr / sa), 0, 255);
        int g = clamp((int) Math.round(sg / sa), 0, 255);
        int b = clamp((int) Math.round(sb / sa), 0, 255);
        return (r << 16) | (g << 8) | b;
    }

    /** 바이옴 틴트 곱 (결정론 상수). */
    private static int tint(int rgb, int t) {
        int r = (((rgb >> 16) & 0xFF) * ((t >> 16) & 0xFF)) / 255;
        int g = (((rgb >> 8) & 0xFF) * ((t >> 8) & 0xFF)) / 255;
        int b = ((rgb & 0xFF) * (t & 0xFF)) / 255;
        return (r << 16) | (g << 8) | b;
    }

    /** 렌더 시작 시 1회 — 팩 텍스처를 읽어 팔레트를 덮어쓴다. 팩이 없으면 조용히 폴백. */
    private static void loadPack(File outDir) {
        PACK.clear();
        PACK_MISS.clear();
        packHits = 0;
        packMisses = 0;
        File blocks = findPackBlockDir(outDir);
        packBlocksDir = blocks;
        if (blocks == null) {
            log("리소스팩 블록 텍스처 폴더를 못 찾았다 — 하드코딩 팔레트로 렌더한다 (팩 없이도 렌더는 돈다)");
            return;
        }
        // 하드코딩 팔레트에 등재된 블록(= 조성이 실제로 쓰는 블록)을 전부 시도한다.
        // 그 밖의 블록은 스캔 중 만나면 그때 지연 적재된다(color()).
        for (Material m : PALETTE.keySet()) {
            resolvePack(blocks, m);
        }
        log("팩 텍스처 " + packHits + "종 반영 (폴백 " + packMisses + "종) — " + blocks.getAbsolutePath());
    }

    /** 한 Material 의 팩 대표색을 확정한다. 성공하면 PACK 에, 실패하면 PACK_MISS 에 넣는다. */
    private static Integer resolvePack(File blocks, Material m) {
        Integer cached = PACK.get(m);
        if (cached != null || PACK_MISS.contains(m)) {
            return cached;
        }
        for (String name : textureCandidates(m)) {
            File png = new File(blocks, name + ".png");
            if (!png.isFile()) {
                continue;
            }
            Integer rgb = averageColor(png);
            if (rgb == null) {
                continue;
            }
            Integer t = TINT.get(m);
            if (t != null) {
                rgb = tint(rgb, t);
            }
            PACK.put(m, rgb);
            packHits++;
            return rgb;
        }
        PACK_MISS.add(m);
        packMisses++;
        return null;
    }

    /** 렌더 1회 동안 고정된 팩 블록 디렉터리 (null = 팩 없음 → 전부 폴백). */
    private static File packBlocksDir;

    /**
     * 블록 대표색 — 팩 텍스처 우선, 없으면 하드코딩 팔레트, 그것도 없으면 회색.
     * (팩 게이트 불가침: 팩이 없어도 색이 나온다)
     */
    private static int color(Material m) {
        Integer packed = PACK.get(m);
        if (packed == null && packBlocksDir != null && !PACK_MISS.contains(m)) {
            packed = resolvePack(packBlocksDir, m);   // 팔레트 밖 블록 — 지연 적재
        }
        if (packed != null) {
            return packed;
        }
        Integer c = PALETTE.get(m);
        return c == null ? FALLBACK : c;
    }

    // ─── 진입점 ───

    public static List<String> render(World world, int cx, int cy, int cz, File outDir) {
        List<String> out = new ArrayList<>();
        if (!outDir.exists() && !outDir.mkdirs()) {
            out.add("렌더 실패 — 출력 폴더를 만들 수 없다: " + outDir.getAbsolutePath());
            return out;
        }
        long t0 = System.currentTimeMillis();
        log("렌더 시작 — 중심 (" + cx + "," + cy + "," + cz + ") 반경 " + R);

        // 팩 인식 — 클라이언트가 실제로 보는 텍스처 색으로 팔레트를 덮어쓴다 (없으면 폴백)
        loadPack(outDir);
        out.add(packBlocksDir == null
                ? "팩 텍스처 없음 — 하드코딩 팔레트 폴백"
                : "팩 텍스처 " + packHits + "종 반영 (폴백 " + packMisses + "종)");

        // 마을 하이트맵 1회 스캔 → 탑다운·아이소 공용 (블록 읽기 1회로 두 뷰를 만든다)
        int n = 2 * R + 1;
        int[][] h = new int[n][n];
        int[][] col = new int[n][n];
        long reads = scanHeightmap(world, cx, cy, cz, cy - Y_DOWN, cy + Y_UP, h, col);
        log("하이트맵 스캔 완료 — 블록 읽기 " + reads + "회 (" + (System.currentTimeMillis() - t0) + "ms)");

        File top = new File(outDir, "town_top.png");
        if (writeTop(h, col, top)) {
            out.add("탑다운 " + top.getAbsolutePath() + " (" + (n * TOP_SCALE) + "×" + (n * TOP_SCALE) + ")");
        }
        log("town_top.png 완료");

        File iso = new File(outDir, "town_iso.png");
        if (writeIso(h, col, iso, ISO_HW, ISO_HH, ISO_HZ)) {
            out.add("아이소 " + iso.getAbsolutePath());
        }
        log("town_iso.png 완료");

        // 클로즈업 — 앵커가 없는 고정 스팟(잡화점·폐사당). 앵커 건물은 render(…, anchors, …) 오버로드가 맡는다.
        for (Map.Entry<String, int[]> e : EXTRA_SPOTS.entrySet()) {
            String name = e.getKey();
            int[] s = e.getValue();
            int bx = cx + s[0], bz = cz + s[1];
            int groundY = s[2] == 0 ? cy : world.getHighestBlockYAt(bx, bz + s[2]);
            File f = closeup(world, name, bx, bz, groundY, outDir);
            if (f != null) {
                out.add("클로즈업 " + f.getAbsolutePath());
            }
            log("bld_" + name + ".png 완료");
        }
        out.add("렌더 완료 — " + (System.currentTimeMillis() - t0) + "ms, 블록 읽기 약 " + reads + "회");
        log("렌더 종료 (" + (System.currentTimeMillis() - t0) + "ms)");
        return out;
    }

    /**
     * 앵커 맵을 그대로 받는 편의 오버로드 — 앵커 건물별 클로즈업까지 한 번에.
     * (MvtCommand 에서 plugin.anchors() 를 넘겨 쓰는 형태)
     */
    public static List<String> render(World world, Map<String, Location> anchors,
                                      int cx, int cy, int cz, File outDir) {
        List<String> out = render(world, cx, cy, cz, outDir);
        if (anchors == null) {
            return out;
        }
        for (Map.Entry<String, Location> e : anchors.entrySet()) {
            Location a = e.getValue();
            if (a == null || "북쪽_산길".equals(e.getKey())) {
                continue;
            }
            File f = closeup(world, e.getKey(), a.getBlockX(), a.getBlockZ(), a.getBlockY() - 1, outDir);
            if (f != null) {
                out.add("클로즈업 " + f.getAbsolutePath());
            }
            log("bld_" + e.getKey() + ".png 완료");
        }
        return out;
    }

    // ─── 하이트맵 스캔 ───

    /** 각 (x,z) 열의 최상단 비공기 블록의 y와 색을 채운다. 블록 읽기 횟수를 돌려준다. */
    /**
     * 반 칸 높이 블록 — 반블록만이다.
     * 계단은 '반쪽'처럼 보이지만 뒤쪽이 통 칸이라 지붕에서는 통 칸으로 읽는 것이 실물에 가깝다
     * (계단을 반 칸으로 그렸더니 지붕이 실제보다 납작해져 판단이 반대로 흘렀다).
     */
    private static boolean isHalfHeight(Material m) {
        return m.name().endsWith("_SLAB");
    }

    private static long scanHeightmap(World world, int cx, int cy, int cz, int yMin, int yMax,
                                      int[][] h, int[][] col) {
        long reads = 0;
        int n = h.length;
        int r = (n - 1) / 2;
        for (int ix = 0; ix < n; ix++) {
            for (int iz = 0; iz < n; iz++) {
                int x = cx - r + ix, z = cz - r + iz;
                h[ix][iz] = Integer.MIN_VALUE;
                col[ix][iz] = FALLBACK;
                for (int y = yMax; y >= yMin; y--) {
                    reads++;
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (m.isAir()) {
                        continue;
                    }
                    // 반 칸 단위 — 계단·반블록은 실제로 반 칸이다. 통 블록으로 그리면 지붕이
                    // 실물보다 훨씬 계단져 보여 판단을 그르친다 (루프의 눈이 거짓말을 한다).
                    h[ix][iz] = 2 * y + (isHalfHeight(m) ? 1 : 2);
                    col[ix][iz] = color(m);
                    break;
                }
            }
            if (ix % 40 == 0 && ix > 0) {
                log("  스캔 " + (100 * ix / n) + "%");
            }
        }
        return reads;
    }

    // ─── ① 탑다운 ───

    private static boolean writeTop(int[][] h, int[][] col, File f) {
        int n = h.length;
        int w = n * TOP_SCALE;
        BufferedImage img = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int[] row : h) {
            for (int v : row) {
                if (v == Integer.MIN_VALUE) {
                    continue;
                }
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        int span = Math.max(1, hi - lo);
        for (int ix = 0; ix < n; ix++) {
            for (int iz = 0; iz < n; iz++) {
                int y = h[ix][iz];
                int rgb = col[ix][iz];
                double f2;
                if (y == Integer.MIN_VALUE) {
                    rgb = 0x101010;
                    f2 = 1.0;
                } else {
                    // 고도 음영 + 북서 릴리프 (이웃보다 높으면 밝게, 낮으면 어둡게 — 지붕 능선이 읽힌다)
                    f2 = 0.62 + 0.38 * (y - lo) / span;
                    int nx = ix > 0 ? h[ix - 1][iz] : y;
                    int nz = iz > 0 ? h[ix][iz - 1] : y;
                    int rel = (y - nx) + (y - nz);
                    f2 *= rel > 0 ? 1.18 : rel < 0 ? 0.80 : 1.0;
                }
                int shaded = shade(rgb, f2);
                // 이미지 x = 월드 x, 이미지 y = 월드 z (북이 위)
                for (int px = 0; px < TOP_SCALE; px++) {
                    for (int py = 0; py < TOP_SCALE; py++) {
                        img.setRGB(ix * TOP_SCALE + px, iz * TOP_SCALE + py, shaded);
                    }
                }
            }
        }
        return write(img, f);
    }

    // ─── ② 아이소메트릭 (하이트맵 큐브 — 상단면 밝게·측면 어둡게) ───

    private static boolean writeIso(int[][] h, int[][] col, File f, int hw, int hh, int hz) {
        int n = h.length;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int[] row : h) {
            for (int v : row) {
                if (v == Integer.MIN_VALUE) {
                    continue;
                }
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        if (lo == Integer.MAX_VALUE) {
            return false;
        }
        int span = Math.max(1, hi - lo);
        // 측면 최대 깊이 = 고도 범위 전체. 이보다 얕게 잡으면 지붕→지면처럼 낙차가 큰 곳에서
        // 측면이 못 미쳐 배경이 새어 나온다(용마루 끝의 검은 홈).
        int skirt = clamp(span, 6, 32);
        int w = (2 * n) * hw + 4 * hw;
        int ht = (2 * n) * hh + (span + skirt) * hz + 8 * hh;
        int ox = n * hw + 2 * hw;
        int oy = 4 * hh + span * hz;
        BufferedImage img = new BufferedImage(w, ht, BufferedImage.TYPE_INT_RGB);
        fill(img, 0x14161A);

        // 페인터 알고리즘 — (ix+iz) 오름차순 = 뒤에서 앞으로
        for (int s = 0; s <= 2 * (n - 1); s++) {
            for (int ix = Math.max(0, s - n + 1); ix <= Math.min(n - 1, s); ix++) {
                int iz = s - ix;
                int y = h[ix][iz];
                if (y == Integer.MIN_VALUE) {
                    continue;
                }
                int sx = ox + (ix - iz) * hw;
                int sy = oy + (ix + iz) * hh - ((y - lo) * hz) / 2;
                int rgb = col[ix][iz];
                // 측면 깊이 = 앞쪽 이웃과의 고도차 (없으면 skirt)
                int nxh = ix + 1 < n ? h[ix + 1][iz] : y - skirt;
                int nzh = iz + 1 < n ? h[ix][iz + 1] : y - skirt;
                int dxDepth = clamp(y - (nxh == Integer.MIN_VALUE ? y - skirt : nxh), 0, skirt);
                int dzDepth = clamp(y - (nzh == Integer.MIN_VALUE ? y - skirt : nzh), 0, skirt);
                // 화면 좌반부 = z+1 이웃 쪽, 우반부 = x+1 이웃 쪽 (sx = ox + (ix-iz)*hw 이므로)
                drawCube(img, sx, sy, hw, hh, rgb, (dzDepth * hz) / 2, (dxDepth * hz) / 2);
            }
        }
        return write(img, f);
    }

    /**
     * 등각 큐브 1개 — 상단면(마름모, 밝게) + 왼쪽 면(어둡게) + 오른쪽 면(가장 어둡게).
     * (sx,sy) = 상단면 마름모의 중심.
     */
    private static void drawCube(BufferedImage img, int sx, int sy, int hw, int hh,
                                 int rgb, int leftDepth, int rightDepth) {
        int top = shade(rgb, 1.0);
        int left = shade(rgb, 0.72);
        int right = shade(rgb, 0.52);
        for (int dx = -hw; dx < hw; dx++) {
            // 마름모: |dx|/hw + |dy|/hh <= 1
            int lim = hh - Math.abs(dx) * hh / hw;
            for (int dy = -lim; dy <= lim; dy++) {
                put(img, sx + dx, sy + dy, top);
            }
            // 측면 — 마름모 아래 가장자리에서 수직으로 내린다
            int edge = sy + lim;
            int depth = dx < 0 ? leftDepth : rightDepth;
            int side = dx < 0 ? left : right;
            for (int dy = 1; dy <= depth; dy++) {
                put(img, sx + dx, edge + dy, side);
            }
        }
    }

    // ─── ③ 건물 클로즈업 ───

    private static File closeup(World world, String name, int bx, int bz, int groundY, File outDir) {
        int n = 2 * CLOSE_R + 1;
        int[][] h = new int[n][n];
        int[][] col = new int[n][n];
        scanHeightmap(world, bx, groundY, bz, groundY - Y_DOWN, groundY + CLOSE_UP, h, col);
        File f = new File(outDir, "bld_" + safe(name) + ".png");
        return writeIso(h, col, f, CLOSE_HW, CLOSE_HH, CLOSE_HZ) ? f : null;
    }

    private static String safe(String s) {
        return s.replaceAll("[^\\p{L}\\p{N}_-]", "_");
    }

    // ─── 픽셀 유틸 ───

    private static void put(BufferedImage img, int x, int y, int rgb) {
        if (x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight()) {
            img.setRGB(x, y, rgb);
        }
    }

    private static void fill(BufferedImage img, int rgb) {
        for (int x = 0; x < img.getWidth(); x++) {
            for (int y = 0; y < img.getHeight(); y++) {
                img.setRGB(x, y, rgb);
            }
        }
    }

    private static int shade(int rgb, double f) {
        int r = clamp((int) (((rgb >> 16) & 0xFF) * f), 0, 255);
        int g = clamp((int) (((rgb >> 8) & 0xFF) * f), 0, 255);
        int b = clamp((int) ((rgb & 0xFF) * f), 0, 255);
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static boolean write(BufferedImage img, File f) {
        try {
            return ImageIO.write(img, "png", f);
        } catch (IOException e) {
            log("PNG 저장 실패 " + f.getName() + " — " + e.getMessage());
            return false;
        }
    }

    private static void log(String msg) {
        Bukkit.getLogger().info("[혼천/렌더] " + msg);
    }
}
