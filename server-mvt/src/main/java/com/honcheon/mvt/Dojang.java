package com.honcheon.mvt;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 연무장(演武場) — <b>따로 두들겨 보는 자리</b>.
 *
 * <p>사용자 판정: "서버 스킬 테스트나 몹 테스트 서버를 만드는 게 좋을까요? 이것만 따로 써 보고 평가하고 싶다.
 * 명령어로 멀티 월드처럼 이동하고, 임의로 단계를 움직이거나 능력치를 조정하고, 스킬도 쓰고,
 * <b>허수아비를 설치해 데미지 테스트</b>도 해 보고."
 *
 * <p>맞는 판단이다. 밸런스와 모션은 <b>세계 안에서는 못 읽는다</b> — 청하현에서 무공을 시험하면
 * 마을이 부서지고, 도적이 죽고, 소문이 돌고, 관이 움직인다. 시험은 세계에 자국을 남기면 안 된다.
 *
 * <p>그래서 연무장은 <b>별도 월드</b>다 (world: {@code honcheon_dojang}):
 * <ul>
 *   <li>평평한 허공 — 지형이 시험을 방해하지 않는다</li>
 *   <li>몹 자연 스폰 없음 · 항상 낮 · 날씨 없음 — 변수를 없앤다</li>
 *   <li><b>세계와 이어지지 않는다</b> — 여기서 벤 것은 소문이 되지 않는다 (WorldBridge 는 이 월드를 무시한다)</li>
 * </ul>
 *
 * <h2>★ 금고(金庫) — 맡긴 것은 잃지 않는다 (2026-07)</h2>
 *
 * <p><b>이것은 위치 버그가 아니라 데이터 손실이었다.</b> 연무장은 들어오는 사람에게서 진짜 것을
 * <b>떼어 내</b> 시험용 빈 몸을 끼운다 (아래 「두 세계의 장부」). 그런데 떼어 낸 것을 <b>메모리에만</b>
 * 두었다 — {@code HashMap} 넷. 그 상태로 서버가 리붓되면:
 *
 * <ol>
 *   <li>{@code origins} 소멸 → 돌아갈 자리를 잃는다</li>
 *   <li>{@code realInventory} 소멸 → ★ <b>진짜 짐이 영영 사라진다</b></li>
 *   <li>{@code realLedger}·{@code realState} 소멸 → ★ 시험용 빈 장부가 <b>진짜 장부의 자리를 차지한다</b>
 *       (더 나쁜 것: {@code HoncheonMvt.saveLedgers} 는 5분마다 {@code ledgers} 맵을 굽는데,
 *       연무장 안에서는 그 맵에 <b>시험용 장부</b>가 들어 있다 — 리붓을 안 해도 디스크가 오염됐다)</li>
 *   <li>사람은 평평한 시험 월드에 빈손으로 남는다 — 사용자가 본 <b>"이상한 공간"</b></li>
 * </ol>
 *
 * <p>그래서 <b>맡는 순간 디스크에 적는다</b> ({@code dojang.yml} — 임시 파일에 쓰고 원자적으로 옮긴다.
 * 반쯤 쓰다 죽으면 그것도 손실이다). 그리고 <b>되돌리는 손</b>을 단다: 접속했는데 금고에 맡긴 것이
 * 있으면 <b>말없이 두지 않고 돌려주고 내보낸다</b> ({@link #onJoin}).
 *
 * <p><b>왜 자동으로 내보내는가</b> — 사람이 일부러 연무장에서 로그아웃했을 수도 있다. 그러나
 * <b>짐을 잃는 것보다 연무장에서 쫓겨나는 것이 훨씬 가볍다</b>: 되돌리는 비용은 명령 한 번
 * ({@code /혼천 연무장})이고, 안 되돌리는 비용은 세션 경계를 넘길 때마다 진짜 장부·짐이 걸린 판돈이다.
 * 시험의 장부·짐은 <b>금고에 그대로 남으므로</b> 다시 들어오면 어제 자리에서 이어진다 — 잃는 것이 없다.
 *
 * <p><b>허수아비</b>는 맞아 주는 몸이다. 죽지 않고, 반격하지 않고, <b>맞은 것을 말한다</b>:
 * 누적 피해 · 최근 한 합 · 합수 · 합당 평균. 밸런스는 느낌이 아니라 숫자다.
 */
final class Dojang implements Listener {

    static final String WORLD = "honcheon_dojang";
    /** 금고 — 맡긴 것이 사는 곳. 문법은 저장소의 것 (anchors.yml·ledgers.yml 과 같은 YamlConfiguration) */
    static final String VAULT_FILE = "dojang.yml";

    private static final NamespacedKey KEY_DUMMY = new NamespacedKey("honcheon", "dummy");

    private final HoncheonMvt plugin;
    /** 허수아비 장부 — 누적 피해·합수. 세션의 것이다 (허수아비는 리붓을 넘지 않는다) */
    private final Map<UUID, double[]> tally = new HashMap<>();   // [누적, 합수, 최근]

    // ─── 두 세계의 장부는 섞이지 않는다 (사용자 규정) ───
    //
    // "여기서 얻은 수련이나 모든 것들은 기존 월드와 분리."
    // 시험장에서 얻은 30일 수련과 신병(神兵)이 강호의 것이 되면, 그건 시험이 아니라 치트다.
    // 들어갈 때 현실의 장부·무공 상태·짐을 **떼어 두고**, 시험용 벌을 끼운다. 나올 때 되돌린다.
    // 연무장의 장부는 그것대로 남는다 — 다시 들어오면 어제 시험하던 자리에서 이어진다.
    //
    // ★ 떼어 낸 것은 **금고에 넣는다**. 맵 넷이 아니라 한 사람당 한 뭉치({@link Deposit})다 —
    //   넷이 따로 놀면 하나만 살아남는 반쪽 복구가 난다 (짐은 돌아왔는데 돌아갈 자리가 없는 식).

    private final Map<UUID, Deposit> vault = new HashMap<>();

    Dojang(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  금고 — 맡긴 것 한 뭉치
    // ══════════════════════════════════════════════════════════════════════

    /** 돌아갈 자리 — <b>Location 이 아니라 숫자로</b> 적는다 (월드가 안 실린 채로도 읽힌다 · 서버 없이 시험된다) */
    record Spot(String world, double x, double y, double z, float yaw, float pitch) {

        static Spot of(Location at) {
            return at == null || at.getWorld() == null ? null
                    : new Spot(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                            at.getYaw(), at.getPitch());
        }

        /** 진짜 자리로 — 월드가 사라졌으면 {@code null} (부르는 쪽이 대안을 찾는다) */
        Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
    }

    /**
     * 한 사람이 <b>맡긴 것</b> + 시험장에 <b>두고 간 것</b>.
     *
     * <p>{@code inside} 가 참인 동안 {@code real*} 셋은 <b>이 사람의 진짜 삶 전부</b>다 — 세계 어디에도
     * 사본이 없다. 그래서 이 뭉치는 맡는 순간 디스크로 간다.
     *
     * <p>{@code test*} 는 {@code inside} 가 거짓이어도 남는다 (다시 들어오면 이어진다). 짐은 Base64
     * 한 줄로 적는다 ({@code ItemStack.serializeItemsAsBytes} — 버전을 넘어 살아남는 공식 직렬화).
     */
    static final class Deposit {
        boolean inside;              // 지금 맡긴 것이 있는가 (= 연무장 안에 있어야 할 사람인가)
        /**
         * ★ <b>이 세션에서 실제로 갈아 끼웠는가</b> — 디스크에 적지 않는다 (리붓하면 거짓이 된다).
         *
         * <p>이것이 없으면 리붓 뒤 되돌림에서 <b>시험의 장부가 진짜 장부로 덮인다</b>: 리붓 뒤의
         * {@code ledgers} 맵에는 (금고 덕분에) <b>진짜 장부</b>가 들어 있는데, {@code swapLedger} 가
         * 돌려주는 그 "이전 값"을 시험 장부인 줄 알고 금고에 넣으면 두 세계의 장부가 섞인다.
         * <b>갈아 끼운 적이 없으면 되돌릴 것도 없다</b> — 금고의 시험 장부를 그대로 둔다.
         */
        transient boolean swapped;
        Spot origin;                 // 돌아갈 자리
        PlayerLedger realLedger;     // ★ 진짜 장부
        SkillEngine.State realState; // ★ 진짜 무공 상태
        String realItems;            // ★ 진짜 짐 (Base64)
        PlayerLedger testLedger;     // 시험의 장부 — 시험장에 두고 간다
        SkillEngine.State testState;
        String testItems;
        int invSize = 41;            // 되돌릴 때 빈 배열의 크기 (짐이 없던 사람)

        boolean holdsAnything() {
            return inside && (realLedger != null || realState != null || realItems != null);
        }
    }

    /** 지금 이 사람의 것을 맡고 있는가 — 되돌릴 것이 있는가 */
    boolean holding(UUID id) {
        Deposit d = vault.get(id);
        return d != null && d.inside;
    }

    /**
     * <b>초기화 — 이 사람의 금고를 비운다</b> ({@link Reset} 만 부른다).
     *
     * <p>★ 금고를 안 비우면 초기화가 <b>되돌려진다.</b> {@code ledgers.yml} 에서 그 사람을 지워도,
     * 연무장에 맡겨 둔 <b>진짜 장부</b>가 여기 남아 있으면 {@link HoncheonMvt#worldLedger} 가 그것을
     * 꺼내 5분 뒤 다시 굽는다 (그리고 {@code saveVault} 가 파일도 되살린다).
     * {@code scripts/fresh_start.sh} 가 놓친 구멍이 정확히 이것이다.
     *
     * <p>맵에서 지우고 <b>즉시</b> 파일을 다시 굽는다 — 여기서 죽어도 되살아나지 않게.
     *
     * @return 맡고 있던 것이 있었는가
     */
    boolean forget(UUID id) {
        boolean had = vault.remove(id) != null;
        if (had) {
            saveVault();   // ★ 지운 것을 **그 자리에서** 굽는다 (5분 타이머를 믿지 않는다)
        }
        return had;
    }

    /** 맡고 있는 사람들 — 기동 로그·감사({@code /혼천 금고})가 읽는다 */
    Map<UUID, Deposit> held() {
        Map<UUID, Deposit> out = new LinkedHashMap<>();
        vault.forEach((id, d) -> {
            if (d.inside) {
                out.put(id, d);
            }
        });
        return out;
    }

    /**
     * ★ <b>이 사람의 진짜 세계 장부</b> — {@code HoncheonMvt.saveLedgers} 가 부른다.
     *
     * <p>연무장 안이면 {@code ledgers} 맵에 든 것은 <b>시험용 장부</b>다. 그것을 {@code ledgers.yml} 에
     * 구우면 <b>디스크의 진짜 장부가 시험용 빈 장부로 덮인다</b> — 리붓 없이도 손실이다. 여기서 가른다.
     */
    PlayerLedger worldLedger(UUID id, PlayerLedger live) {
        return worldLedger(vault.get(id), live);
    }

    /** 같은 판단, 서버 없이 시험할 수 있는 몸 ({@code tools/DojangVaultSelfTest.java}) */
    static PlayerLedger worldLedger(Deposit d, PlayerLedger live) {
        return d != null && d.inside && d.realLedger != null ? d.realLedger : live;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  금고의 입출 — 디스크 (원자적으로 쓴다)
    // ══════════════════════════════════════════════════════════════════════

    /** 짐 → 한 줄 (Base64). 빈 손이면 {@code null} */
    private static String encodeItems(ItemStack[] items) {
        return items == null ? null
                : Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items));
    }

    /** 한 줄 → 짐. 못 읽으면 {@code null} 을 돌려주고 <b>짖는다</b> (조용히 빈손으로 만들지 않는다) */
    private ItemStack[] decodeItems(String blob) {
        if (blob == null || blob.isBlank()) {
            return null;
        }
        try {
            return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(blob));
        } catch (RuntimeException broken) {
            plugin.getLogger().severe("[연무장/금고] ★ 짐을 못 읽는다 — " + broken
                    + " · 원본은 " + VAULT_FILE + " 에 그대로 있다 (덮어쓰지 않는다)");
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ★ 금고 왕복 실측 (B-011) — **진짜 ItemStack 으로** 잰다
    // ══════════════════════════════════════════════════════════════════════
    //
    // 왜 새로 짓는가: `tools/DojangVaultSelfTest.java` 는 **문자열**을 왕복시킨다
    //   (`before.realItems = "REAL:칠성검,비급,은자7971"`). 그것이 증명하는 것은
    //   "YAML 이 불투명한 줄을 잃지 않는다" 뿐이고, **증명하지 않는 것**이
    //   "재기동을 건너 사람의 짐이 살아 돌아온다" 이다.
    //   그 둘 사이에 `ItemStack.serializeItemsAsBytes` 가 통째로 빠져 있었다.
    //
    // ★ 이 시험은 **금고 파일을 건드리지 않는다.** 메모리에서 encode→decode 만 왕복시킨다.
    //   (시험이 진짜 장부를 덮으면 그것 자체가 데이터 손실이다.)

    /** 한 판의 결과 — 무엇을 걸었고, 살아 돌아왔는가. */
    record VaultCase(String name, boolean pass, String detail) { }

    /**
     * <b>진짜 짐을 금고 경로에 넣었다 뺀다.</b> 사람이 실제로 들고 다니는 것들로 건다 —
     * 빈 칸(null), 인챈트, 이름·설명, <b>우리 신병의 PDC</b>, 상자 속의 상자.
     *
     * <p>비교는 {@code ItemStack.equals} 로 한다 — 종류·개수·메타(=PDC 포함)를 다 본다.
     */
    List<VaultCase> vaultRoundTripTest() {
        List<VaultCase> out = new ArrayList<>();

        out.add(runCase("빈 손 (null)", null));
        out.add(runCase("빈 배열", new ItemStack[0]));
        out.add(runCase("평범한 것 (돌 64)", new ItemStack[]{ new ItemStack(Material.STONE, 64) }));

        // ★ 인벤토리에는 **빈 칸이 있다.** 배열 한가운데의 null 을 못 넘기면 짐이 밀려 어긋난다.
        out.add(runCase("빈 칸(null) 구멍", new ItemStack[]{
                new ItemStack(Material.STONE), null, new ItemStack(Material.DIRT), null }));

        ItemStack air = new ItemStack(Material.AIR);
        out.add(runCase("AIR 섞임", new ItemStack[]{ air, new ItemStack(Material.STONE) }));

        ItemStack fancy = new ItemStack(Material.IRON_SWORD);
        org.bukkit.inventory.meta.ItemMeta fm = fancy.getItemMeta();
        fm.setDisplayName(ChatColor.AQUA + "시험용 이름");
        fm.setLore(List.of("첫 줄", "둘째 줄"));
        fm.addEnchant(org.bukkit.enchantments.Enchantment.SHARPNESS, 3, true);
        fm.setCustomModelData(12345);
        fancy.setItemMeta(fm);
        out.add(runCase("이름·설명·인챈트·모델데이터", new ItemStack[]{ fancy }));

        // ★★ 여기가 진짜 물음이다 — **우리 신병의 PDC 가 왕복을 견디는가.**
        //    이것이 깨지면 검이 「그냥 철검」이 되어 돌아온다. 사람은 그것을 손실이라 부른다.
        try {
            ItemStack weapon = Weapons.makeRolled(Weapons.Series.values()[0], Weapons.Grade.values()[0]);
            out.add(runCase("★ 신병 (PDC 붙은 무기)", new ItemStack[]{ weapon }));
        } catch (RuntimeException | Error e) {
            out.add(new VaultCase("★ 신병 (PDC 붙은 무기)", false, "무기를 못 만들었다 — " + e));
        }

        // 상자 속의 상자 — 중첩 인벤토리가 통째로 살아야 한다
        try {
            ItemStack box = new ItemStack(Material.SHULKER_BOX);
            org.bukkit.inventory.meta.BlockStateMeta bm =
                    (org.bukkit.inventory.meta.BlockStateMeta) box.getItemMeta();
            org.bukkit.block.ShulkerBox state = (org.bukkit.block.ShulkerBox) bm.getBlockState();
            state.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 7));
            bm.setBlockState(state);
            box.setItemMeta(bm);
            out.add(runCase("상자 속의 상자 (셜커)", new ItemStack[]{ box }));
        } catch (RuntimeException | Error e) {
            out.add(new VaultCase("상자 속의 상자 (셜커)", false, "못 지었다 — " + e));
        }

        // 실제 인벤토리 크기 그대로 — 41칸(주 36 + 방어구 4 + 보조 1)
        ItemStack[] full = new ItemStack[41];
        for (int i = 0; i < full.length; i++) {
            full[i] = (i % 3 == 0) ? null : new ItemStack(Material.BREAD, 1 + (i % 16));
        }
        out.add(runCase("인벤토리 한 벌 (41칸 · 빈 칸 섞임)", full));

        // ══════════════════════════════════════════════════════════════
        //  ★ 눈을 시험하는 눈 — 이 시험이 **실패를 잡아낼 수 있는가**
        // ══════════════════════════════════════════════════════════════
        //  "전부 통과"는 그 자체로는 아무 뜻이 없다. 아무것도 안 보는 눈도 통과를 낸다.
        //  그러니 **틀린 것을 일부러 먹여** 시험이 짖는지 본다. 여기서 안 짖으면
        //  위의 아홉 판은 전부 무의미하다 — 그때는 이 줄이 그렇다고 말한다.
        out.addAll(eyeTests());

        return out;
    }

    /**
     * <b>짐의 지문</b> — 같은 짐이면 같은 값이 나온다. <b>재기동 전후를 견주기 위한 것</b>이다.
     *
     * <p>왜 필요했나: `data get entity … Inventory` 는 서버가 <b>174바이트에서 잘라</b> 준다.
     * 잘린 글로는 "같은 것이 돌아왔다"를 말할 수 없다. 그래서 <b>금고가 실제로 적는 그 바이트</b>
     * (= {@link #encodeItems})를 그대로 sha1 로 접는다 — 한 칸이라도 다르면 지문이 달라진다.
     */
    static String inventoryFingerprint(ItemStack[] items) {
        String blob = encodeItems(items);
        if (blob == null) {
            return "빈손";
        }
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(blob.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            return "sha1없음";
        }
    }

    /** 사람이 눈으로도 대조할 수 있게 — 칸마다 무엇이 몇 개, PDC 는 몇 줄인가. */
    static List<String> inventoryLines(ItemStack[] items) {
        List<String> out = new ArrayList<>();
        if (items == null) {
            out.add("  (빈손)");
            return out;
        }
        int n = 0;
        for (int i = 0; i < items.length; i++) {
            ItemStack s = items[i];
            if (s == null || s.getType() == Material.AIR) {
                continue;
            }
            n++;
            out.add("  " + i + "번  " + describe(s));
        }
        out.add("  — 물건이 든 칸: " + n + "개 / 전체 " + items.length + "칸");
        return out;
    }

    /** 일부러 어긋난 것을 먹인다 — <b>잡아내면</b> 통과다. */
    private List<VaultCase> eyeTests() {
        List<VaultCase> out = new ArrayList<>();

        // ① 다른 물건을 같다고 하지는 않는가
        VaultCase mismatch = compareArrays("눈① 다른 물건 판별",
                new ItemStack[]{ new ItemStack(Material.STONE, 64) },
                new ItemStack[]{ new ItemStack(Material.STONE, 63) });
        out.add(new VaultCase("눈① 개수 1개 차이를 잡는가", !mismatch.pass(),
                mismatch.pass() ? "★ 못 잡았다 — 이 시험은 눈이 멀었다" : "잡았다: " + mismatch.detail()));

        // ② 빈 칸이 밀린 것을 잡는가 (짐이 어긋나는 전형적인 손실)
        VaultCase shifted = compareArrays("눈② 밀림 판별",
                new ItemStack[]{ new ItemStack(Material.STONE), null },
                new ItemStack[]{ null, new ItemStack(Material.STONE) });
        out.add(new VaultCase("눈② 빈 칸 밀림을 잡는가", !shifted.pass(),
                shifted.pass() ? "★ 못 잡았다 — 밀려도 통과가 난다" : "잡았다: " + shifted.detail()));

        // ③ 깨진 줄을 먹였을 때 **조용히 빈손**을 만들지 않는가
        //    decodeItems 는 못 읽으면 null 을 주고 로그에 짖는 것이 계약이다.
        //    ★ null 은 「짐 없음」이 아니라 「모르겠다」다 — 부르는 쪽이 이걸 빈손으로 쓰면 그게 손실이다.
        ItemStack[] fromGarbage;
        try {
            fromGarbage = decodeItems("이건-Base64-가-아니다!!");
        } catch (RuntimeException e) {
            fromGarbage = null;
        }
        out.add(new VaultCase("눈③ 깨진 줄에 빈손을 내놓지 않는가", fromGarbage == null,
                fromGarbage == null
                        ? "null 을 돌려주고 로그에 짖는다 (빈손으로 덮지 않는다)"
                        : "★ " + fromGarbage.length + "칸을 내놨다 — 깨진 줄을 짐으로 읽는다"));

        return out;
    }

    /** {@link #runCase} 의 비교부만 떼어 쓴다 — 왕복 없이 두 배열을 견준다. */
    private VaultCase compareArrays(String name, ItemStack[] a, ItemStack[] b) {
        if (a.length != b.length) {
            return new VaultCase(name, false, "칸 수가 다르다");
        }
        for (int i = 0; i < a.length; i++) {
            ItemStack x = a[i], y = b[i];
            boolean xe = x == null || x.getType() == Material.AIR;
            boolean ye = y == null || y.getType() == Material.AIR;
            if (xe && ye) {
                continue;
            }
            if (xe != ye || !x.equals(y)) {
                return new VaultCase(name, false,
                        i + "번 칸 " + describe(x) + " ≠ " + describe(y));
            }
        }
        return new VaultCase(name, true, "같다");
    }

    /** 한 판 — encode→decode 를 **진짜 경로로** 돌리고 같은 것이 왔는지 본다. */
    private VaultCase runCase(String name, ItemStack[] items) {
        try {
            ItemStack[] back = decodeItems(encodeItems(items));
            if (items == null || items.length == 0) {
                // 빈 손은 null 로 돌아오는 것이 계약이다 (encodeItems 가 null 을 준다)
                boolean ok = back == null || back.length == 0;
                return new VaultCase(name, ok, ok ? "빈 채로 돌아왔다" : "빈 손인데 " + back.length + "칸이 왔다");
            }
            if (back == null) {
                return new VaultCase(name, false, "★ null 이 돌아왔다 — 짐이 통째로 사라진다");
            }
            if (back.length != items.length) {
                return new VaultCase(name, false,
                        "칸 수가 다르다: " + items.length + " → " + back.length);
            }
            for (int i = 0; i < items.length; i++) {
                ItemStack a = items[i], b = back[i];
                boolean aEmpty = a == null || a.getType() == Material.AIR;
                boolean bEmpty = b == null || b.getType() == Material.AIR;
                if (aEmpty && bEmpty) {
                    continue;
                }
                if (aEmpty != bEmpty) {
                    return new VaultCase(name, false,
                            i + "번 칸: " + (aEmpty ? "빈 칸이 채워져 왔다" : "★ 물건이 빈 칸이 돼 왔다"));
                }
                if (!a.equals(b)) {
                    return new VaultCase(name, false,
                            i + "번 칸이 달라졌다: " + describe(a) + "  →  " + describe(b));
                }
            }
            return new VaultCase(name, true, items.length + "칸 전부 같은 것으로 돌아왔다");
        } catch (RuntimeException | Error e) {
            return new VaultCase(name, false, "★ 왕복 중 터졌다 — " + e);
        }
    }

    /** 어긋났을 때 **무엇이** 어긋났는지 사람이 읽을 수 있게. */
    private static String describe(ItemStack s) {
        if (s == null) {
            return "(빈 칸)";
        }
        StringBuilder sb = new StringBuilder(s.getType().name()).append(" x").append(s.getAmount());
        if (s.hasItemMeta()) {
            org.bukkit.inventory.meta.ItemMeta m = s.getItemMeta();
            if (m.hasDisplayName()) {
                sb.append(" 「").append(ChatColor.stripColor(m.getDisplayName())).append("」");
            }
            int pdc = m.getPersistentDataContainer().getKeys().size();
            if (pdc > 0) {
                sb.append(" PDC:").append(pdc).append("개");
            }
            if (!m.getEnchants().isEmpty()) {
                sb.append(" 인챈트:").append(m.getEnchants().size()).append("종");
            }
        }
        return sb.toString();
    }

    /** 무공 상태 → 금고. <b>틱 값(쿨다운·경직·창)은 적지 않는다</b> — 리붓을 넘으면 뜻이 없는 숫자다 */
    static void writeState(ConfigurationSection to, SkillEngine.State s) {
        if (s == null) {
            return;
        }
        to.set("realm", s.realm);
        to.set("naegong", s.naegong);
        to.set("energy", s.energy);
        to.set("armed", s.armed);
        to.set("ultimate", s.ultimateId);
    }

    static SkillEngine.State readState(ConfigurationSection from) {
        if (from == null) {
            return null;
        }
        SkillEngine.State s = new SkillEngine.State();
        s.realm = from.getString("realm");
        s.naegong = from.getDouble("naegong");
        s.energy = from.getInt("energy");
        s.armed = from.getString("armed");
        s.ultimateId = from.getString("ultimate");
        return s;
    }

    private static void writeSpot(ConfigurationSection to, String path, Spot spot) {
        if (spot == null) {
            return;
        }
        to.set(path + ".world", spot.world());
        to.set(path + ".x", spot.x());
        to.set(path + ".y", spot.y());
        to.set(path + ".z", spot.z());
        to.set(path + ".yaw", spot.yaw());
        to.set(path + ".pitch", spot.pitch());
    }

    private static Spot readSpot(ConfigurationSection from, String path) {
        ConfigurationSection sec = from.getConfigurationSection(path);
        if (sec == null || sec.getString("world") == null) {
            return null;
        }
        return new Spot(sec.getString("world"), sec.getDouble("x"), sec.getDouble("y"),
                sec.getDouble("z"), (float) sec.getDouble("yaw"), (float) sec.getDouble("pitch"));
    }

    /** 한 뭉치를 적는다 — <b>서버 없이도 도는 순수 코드</b> (그래서 오프라인으로 시험할 수 있다) */
    static void writeDeposit(ConfigurationSection to, Deposit d) {
        to.set("inside", d.inside);
        to.set("inv_size", d.invSize);
        writeSpot(to, "origin", d.origin);
        if (d.realLedger != null) {
            d.realLedger.save(to.createSection("real.ledger"));
        }
        writeState(to.createSection("real.state"), d.realState);
        to.set("real.items", d.realItems);
        if (d.testLedger != null) {
            d.testLedger.save(to.createSection("test.ledger"));
        }
        writeState(to.createSection("test.state"), d.testState);
        to.set("test.items", d.testItems);
    }

    static Deposit readDeposit(ConfigurationSection from) {
        Deposit d = new Deposit();
        d.inside = from.getBoolean("inside");
        d.invSize = from.getInt("inv_size", 41);
        d.origin = readSpot(from, "origin");
        ConfigurationSection rl = from.getConfigurationSection("real.ledger");
        d.realLedger = rl == null ? null : PlayerLedger.load(rl);
        d.realState = readState(from.getConfigurationSection("real.state"));
        d.realItems = from.getString("real.items");
        ConfigurationSection tl = from.getConfigurationSection("test.ledger");
        d.testLedger = tl == null ? null : PlayerLedger.load(tl);
        d.testState = readState(from.getConfigurationSection("test.state"));
        d.testItems = from.getString("test.items");
        return d;
    }

    /**
     * ★ <b>금고를 굽는다 — 임시 파일에 쓰고 옮긴다.</b>
     *
     * <p>{@code yml.save(file)} 는 <b>파일을 먼저 비우고</b> 쓴다. 그 사이에 죽으면 금고가 반쪽이 되고,
     * 반쪽 금고는 <b>없는 금고보다 나쁘다</b> (읽히는데 틀린다). 그래서 옆에 다 쓰고 <b>이름만 바꾼다</b> —
     * 리네임은 원자적이다. 죽는 순간이 어디든 디스크에는 <b>온전한 옛 금고나 온전한 새 금고</b>만 있다.
     */
    static void writeVault(File file, Map<UUID, Deposit> deposits) throws IOException {
        YamlConfiguration yml = new YamlConfiguration();
        deposits.forEach((id, d) -> writeDeposit(yml.createSection(id.toString()), d));
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        yml.save(tmp);
        try {
            Files.move(tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException noAtomic) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 금고를 읽는다. <b>깨졌으면 던진다</b> — {@code YamlConfiguration.loadConfiguration} 은 깨진 파일에
     * <b>빈 설정</b>을 돌려준다. 그것을 그대로 받으면 금고가 빈 줄 알고 <b>다음 저장이 증거까지 덮는다</b>
     * (맡긴 것이 있는데 없다고 믿는 것 — 가장 나쁜 실패). 그래서 직접 파싱하고 <b>터뜨린다</b>.
     */
    static Map<UUID, Deposit> readVault(File file)
            throws IOException, org.bukkit.configuration.InvalidConfigurationException {
        Map<UUID, Deposit> out = new LinkedHashMap<>();
        if (file == null || !file.isFile()) {
            return out;
        }
        YamlConfiguration yml = new YamlConfiguration();
        yml.load(file);
        for (String key : yml.getKeys(false)) {
            ConfigurationSection sec = yml.getConfigurationSection(key);
            if (sec == null) {
                continue;
            }
            try {
                out.put(UUID.fromString(key), readDeposit(sec));
            } catch (IllegalArgumentException notUuid) {
                // 키가 uuid 가 아니다 — 버리지 않고 넘긴다 (부르는 쪽이 짖는다)
            }
        }
        return out;
    }

    private File vaultFile() {
        return new File(plugin.getDataFolder(), VAULT_FILE);
    }

    /**
     * 금고를 굽는다 — <b>맡는 순간 · 돌려주는 순간 · 5분마다 · 종료 때</b>.
     *
     * <p>안에 있는 사람의 <b>시험 짐</b>을 다시 뜬다 (장부·상태는 살아 있는 객체를 그대로 들고 있으므로
     * 뜰 것이 없다 — 시험 중의 변화가 그대로 적힌다).
     */
    void saveVault() {
        for (Map.Entry<UUID, Deposit> e : vault.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && e.getValue().inside && isDojang(p.getWorld())) {
                e.getValue().testItems = encodeItems(p.getInventory().getContents());
                e.getValue().invSize = p.getInventory().getSize();
            }
        }
        if (vault.isEmpty() && !vaultFile().isFile()) {
            return;
        }
        try {
            writeVault(vaultFile(), vault);
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().severe("[연무장/금고] ★★ 금고를 못 구웠다 — 연무장 안의 사람이 "
                    + "지금 죽으면 진짜 짐을 잃는다: " + e);
        }
    }

    /**
     * 기동 — 금고를 연다. <b>맡긴 것이 남아 있으면 짖는다</b> (관리자가 알아야 한다).
     * 사람은 여기서 안 꺼낸다 — 접속할 때 꺼낸다 ({@link #onJoin}). 오프라인인 몸은 만질 수 없다.
     */
    void loadVault() {
        vault.clear();
        try {
            vault.putAll(readVault(vaultFile()));
        } catch (Exception broken) {
            // ★ 깨진 금고를 **덮지 않는다** — 증거를 옆으로 치우고 짖는다. 사람의 짐이 걸려 있다.
            File keep = new File(plugin.getDataFolder(),
                    VAULT_FILE + ".broken-" + System.currentTimeMillis());
            boolean moved = vaultFile().renameTo(keep);
            plugin.getLogger().severe("[연무장/금고] ★★ 금고를 읽을 수 없다: " + broken);
            plugin.getLogger().severe("[연무장/금고] " + (moved
                    ? "깨진 금고를 " + keep.getName() + " 로 옮겼다 — 손으로 살릴 수 있다. "
                        + "연무장 안에 있던 사람은 짐을 못 돌려받는다 (짐에 손대지도 않는다)."
                    : "★ 옮기지도 못했다 — 파일을 건드리지 마라. 관리자가 직접 봐야 한다."));
            return;
        }
        Map<UUID, Deposit> stillHeld = held();
        if (stillHeld.isEmpty()) {
            if (!vault.isEmpty()) {
                plugin.getLogger().info("[연무장] 시험 기록 " + vault.size() + "인 — 맡긴 것은 없다");
            }
            return;
        }
        plugin.getLogger().warning("[연무장] ★ 금고가 " + stillHeld.size()
                + "인의 것을 맡고 있다 (연무장 안에서 서버가 내려갔다). 접속하면 돌려준다:");
        stillHeld.forEach((id, d) -> {
            org.bukkit.OfflinePlayer who = Bukkit.getOfflinePlayer(id);
            plugin.getLogger().warning("  · " + (who.getName() == null ? id : who.getName())
                    + " — 장부 " + (d.realLedger != null ? "○" : "×")
                    + " · 무공 " + (d.realState != null ? "○" : "×")
                    + " · 짐 " + (d.realItems != null ? "○" : "×")
                    + " · 돌아갈 자리 " + (d.origin != null ? d.origin.world() + " ("
                        + (int) d.origin.x() + ", " + (int) d.origin.y() + ", "
                        + (int) d.origin.z() + ")" : "없음"));
        });
    }

    /** 연무장 월드 — 없으면 만든다 (평평한 허공 · 몹 없음 · 항상 낮) */
    World world() {
        World w = Bukkit.getWorld(WORLD);
        if (w != null) {
            return w;
        }
        w = new WorldCreator(WORLD)
                .type(WorldType.FLAT)
                .generateStructures(false)
                .generatorSettings("{\"layers\":[{\"block\":\"minecraft:stone\",\"height\":1},"
                        + "{\"block\":\"minecraft:dirt\",\"height\":2},"
                        + "{\"block\":\"minecraft:grass_block\",\"height\":1}],\"biome\":\"minecraft:plains\"}")
                .createWorld();
        if (w == null) {
            return null;
        }
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);      // 변수를 없앤다 — 시험은 시험만 남아야 한다
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setGameRule(GameRule.KEEP_INVENTORY, true);
        w.setGameRule(GameRule.MOB_GRIEFING, false);
        w.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        w.setTime(6000);
        w.setStorm(false);
        return w;
    }

    /** 여기가 연무장인가 — 세계 다리·사냥·소문이 이 월드를 무시하는 근거 */
    static boolean isDojang(World world) {
        return world != null && WORLD.equals(world.getName());
    }

    // ─── 이동 ───

    void enter(Player player) {
        World w = world();
        if (w == null) {
            player.sendMessage(ChatColor.RED + "연무장을 열 수 없다.");
            return;
        }
        UUID id = player.getUniqueId();
        if (!isDojang(player.getWorld())) {
            Deposit d = vault.computeIfAbsent(id, k -> new Deposit());
            if (d.inside) {
                // 맡긴 것이 있는데 밖에 있다 — 앞뒤가 안 맞는다. 두 번 맡으면 첫 번째가 지워진다.
                player.sendMessage(ChatColor.RED + "금고에 이미 맡긴 것이 있다 — 먼저 돌려받는다.");
                giveBack(player, d, "앞선 시험이 덜 끝났다");
                return;
            }
            d.origin = Spot.of(player.getLocation());   // 돌아갈 자리를 기억한다
            d.invSize = player.getInventory().getSize();
            // 현실의 장부·무공·짐을 떼어 둔다 — 시험은 세계에 자국을 남기지 않는다
            PlayerLedger mineLedger = plugin.ledger(id);   // 없으면 여기서 태어난다 (null 을 맡지 않는다)
            if (d.testLedger == null) {
                d.testLedger = new PlayerLedger();
            }
            d.realLedger = plugin.swapLedger(id, d.testLedger);
            if (d.realLedger == null) {
                d.realLedger = mineLedger;
            }
            SkillEngine.State mine = plugin.skills().state(player);   // 세계의 나 (경지는 봇의 시트)
            if (d.testState == null) {
                d.testState = new SkillEngine.State();
            }
            if (d.testState.realm == null) {
                // 시험대에는 **제 경지로** 들어선다 — 그 뒤에 /혼천 시험 경지 로 갈아 낀다.
                // (경지 기본값이 코드에 없어졌다: 그 자리에 "이류"가 박혀 있었다)
                d.testState.realm = mine.realm;
                d.testState.naegong = mine.naegong;
                d.testState.energy = mine.energy;
            }
            d.realState = plugin.skills().swapState(id, d.testState);
            if (d.realState == null) {
                d.realState = mine;
            }
            d.realItems = encodeItems(player.getInventory().getContents());
            ItemStack[] test = decodeItems(d.testItems);
            player.getInventory().setContents(test != null ? test
                    : new ItemStack[player.getInventory().getSize()]);
            d.inside = true;
            d.swapped = true;   // 이 세션에서 갈아 끼웠다 — 되돌릴 때 살아 있는 것이 시험의 것이다
            saveVault();   // ★ **맡는 순간** 적는다. 여기서 죽어도 진짜 짐은 디스크에 있다
        }
        // 평면 월드의 지면은 y5 가 아니다 — 층이 월드 최저(-64)부터 쌓이므로 지표는 y-61 언저리다.
        // 구판은 y5 에 떨어뜨렸고 사람이 **낙사**했다. 지면은 월드에게 묻는다.
        w.getChunkAt(0, 0).load(true);   // ★ 청크가 안 실린 채로 물으면 바닥이 없다고 답한다 (허공 낙하)
        int groundY = w.getHighestBlockYAt(0, 0);
        Location at = new Location(w, 0.5, groundY + 1.0, 0.5, 0f, 0f);
        pad(w, groundY);   // 딛는 자리 — 첫 걸음이 허공이면 시험이고 뭐고 없다
        Standing.Verdict v = Standing.measure(at);   // 눈을 만들었으면 쓰라 — 바닥을 깔았다고 믿지 않는다
        if (!v.ok()) {
            plugin.getLogger().severe("[연무장/입장] 바닥을 깔았는데 설 수 없다 "
                    + Standing.describe(at) + " — " + v.why());
        }
        player.teleport(at);
        player.setFallDistance(0f);
        player.setGameMode(org.bukkit.GameMode.CREATIVE);   // 시험은 죽음의 자리가 아니다
        player.sendMessage(ChatColor.GOLD + "── 연무장 ──");
        player.sendMessage(ChatColor.GRAY + "여기서 벤 것은 소문이 되지 않는다. 마음껏 두들겨라.");
        player.sendMessage(ChatColor.DARK_GRAY + "진짜 장부·무공·짐은 금고에 맡겼다 (디스크에 적혔다 — "
                + "리붓이 나도 돌려받는다).");
        player.sendMessage(ChatColor.GRAY + "/혼천 시험 경지 <삼류|이류|일류|절정|초절정|화경> · "
                + "/혼천 시험 내력 <값> · /혼천 시험 무공 <id>");
        player.sendMessage(ChatColor.GRAY + "/혼천 허수아비 [내구] · /혼천 시험 몹 <id> · /혼천 귀환");
    }

    /** 연무장 바닥 — 32×32 돌바닥과 표식. 허공에 떨구지 않는다 */
    private void pad(World w, int groundY) {
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) {
                w.getBlockAt(x, groundY, z).setType(Math.floorMod(x + z, 8) == 0
                        ? Material.ANDESITE : Material.POLISHED_ANDESITE);
                for (int y = groundY + 1; y <= groundY + 4; y++) {
                    w.getBlockAt(x, y, z).setType(Material.AIR);
                }
            }
        }
        for (int i = -16; i <= 16; i += 8) {   // 열 자(尺) — 거리를 눈으로 잰다 (사거리 시험)
            w.getBlockAt(i, groundY, 0).setType(Material.DARK_OAK_PLANKS);
            w.getBlockAt(0, groundY, i).setType(Material.DARK_OAK_PLANKS);
        }
        // 시험대 넷 — **클릭으로 고른다** (명령을 외워 치는 시험은 시험 준비다)
        stand(w, -5, groundY, -5, Material.SMITHING_TABLE, "병기대", "9계열 × 5등급 — 집으면 손에 온다");
        stand(w, 5, groundY, -5, Material.LECTERN, "무공대", "등록부의 무공 — 고르면 수련 30일");
        stand(w, -5, groundY, 5, Material.ENCHANTING_TABLE, "경지대", "삼류~화경 — 내력 풀이 그 경지의 것으로");
        stand(w, 5, groundY, 5, Material.TARGET, "적수대", "허수아비 · 등록부의 적");
    }

    /** 시험대 한 자리 — 블록 + 그 위 명패 */
    private void stand(World w, int x, int y, int z, Material block, String title, String note) {
        w.getBlockAt(x, y + 1, z).setType(block);
        org.bukkit.block.Block sign = w.getBlockAt(x, y + 2, z);
        sign.setType(Material.OAK_SIGN);
        if (sign.getState() instanceof org.bukkit.block.Sign s) {
            s.getSide(org.bukkit.block.sign.Side.FRONT).setLine(0, ChatColor.GOLD + title);
            s.getSide(org.bukkit.block.sign.Side.FRONT).setLine(1, ChatColor.GRAY + "우클릭");
            s.getSide(org.bukkit.block.sign.Side.FRONT).setLine(2, ChatColor.DARK_GRAY + note.substring(0,
                    Math.min(15, note.length())));
            s.update();
        }
    }

    /** 시험대를 우클릭하면 그 자리의 목록이 열린다 */
    @EventHandler
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || !isDojang(event.getPlayer().getWorld())
                || event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Material m = event.getClickedBlock().getType();
        DojangGui gui = plugin.dojangGui();
        Player p = event.getPlayer();
        switch (m) {
            case SMITHING_TABLE -> {
                event.setCancelled(true);
                gui.openWeapons(p, 0);
            }
            case LECTERN -> {
                event.setCancelled(true);
                gui.openArts(p, 0);
            }
            case ENCHANTING_TABLE -> {
                event.setCancelled(true);
                gui.openRealms(p);
            }
            case TARGET -> {
                event.setCancelled(true);
                gui.openFoes(p);
            }
            default -> { }
        }
    }

    void leave(Player player) {
        Deposit d = vault.get(player.getUniqueId());
        if (d != null && d.inside) {
            giveBack(player, d, null);
            return;
        }
        if (!isDojang(player.getWorld())) {
            player.sendMessage(ChatColor.GRAY + "연무장에 있지 않다.");
            return;
        }
        // 연무장에 있는데 맡긴 것이 없다 — 금고를 잃었거나 애초에 안 맡겼다.
        // ★ 짐에 손대지 않는다 (지금 든 것이 이 사람이 가진 전부일 수 있다). 자리만 옮긴다.
        plugin.getLogger().severe("[연무장/귀환] ★ " + player.getName()
                + " 이(가) 연무장에 있는데 금고에 맡긴 것이 없다 — 짐은 그대로 두고 내보낸다");
        player.sendMessage(ChatColor.RED + "금고에 맡긴 것이 없다 — 짐은 건드리지 않고 내보낸다. "
                + "잃은 것이 있으면 관리자에게 말하라.");
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        player.teleport(wayOut(null));
    }

    /**
     * ★ <b>맡은 것을 돌려준다</b> — 장부·무공·짐, 그리고 돌아갈 자리.
     *
     * <p>귀환({@code /혼천 귀환})과 리붓 뒤 자동 되돌림이 <b>같은 손</b>을 쓴다. 두 갈래로 갈라 놓으면
     * 한쪽만 고쳐지고 다른 쪽에서 사람이 짐을 잃는다.
     */
    private void giveBack(Player player, Deposit d, String why) {
        UUID id = player.getUniqueId();
        // 시험의 장부는 시험장에 두고 간다 (다시 들어오면 그 자리에서 이어진다)
        if (isDojang(player.getWorld())) {
            d.testItems = encodeItems(player.getInventory().getContents());
        }
        // ★ 살아 있는 장부·상태가 **시험의 것인가**는 이 세션에서 갈아 끼웠을 때만 참이다.
        //   리붓 뒤에는 그 자리에 (금고 덕분에) **진짜 장부**가 서 있다 — 그것을 시험 장부로 착각해
        //   금고에 넣으면 두 세계의 장부가 섞인다. 안 끼웠으면 금고의 시험 장부를 그대로 둔다.
        PlayerLedger wasLive = plugin.swapLedger(id, d.realLedger);
        SkillEngine.State wasState = plugin.skills().swapState(id, d.realState);
        if (d.swapped) {
            d.testLedger = wasLive;
            d.testState = wasState;
        }
        d.swapped = false;
        ItemStack[] back = decodeItems(d.realItems);
        if (back == null && d.realItems != null) {
            // 못 읽었다 — **비우지 않는다**. 지금 든 것을 그대로 두고 짖는다 (빈손이 더 나쁘다)
            player.sendMessage(ChatColor.RED + "★ 맡긴 짐을 읽지 못했다 — 짐은 그대로 둔다. "
                    + "원본은 " + VAULT_FILE + " 에 남아 있다. 관리자에게 말하라.");
        } else {
            player.getInventory().setContents(back != null ? back : new ItemStack[d.invSize]);
        }
        d.inside = false;
        d.realLedger = null;
        d.realState = null;
        d.realItems = null;
        Spot origin = d.origin;
        d.origin = null;
        saveVault();   // ★ 돌려준 것은 금고에서 지운다 (두 번 돌려주면 짐이 복제된다)

        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        player.teleport(wayOut(origin));
        player.setFallDistance(0f);
        if (why == null) {
            player.sendMessage(ChatColor.GRAY + "연무장을 나선다 — 시험의 수련·병기는 시험장에 두고 간다.");
        } else {
            player.sendMessage(ChatColor.GOLD + "── 연무장에서 꺼냈다 ── " + ChatColor.GRAY + why);
            player.sendMessage(ChatColor.WHITE + "진짜 장부·무공·짐을 돌려줬다."
                    + ChatColor.GRAY + " 시험의 것은 연무장에 그대로 있다 — /혼천 연무장 으로 이어서 하라.");
        }
    }

    /**
     * <b>내리는 자리</b> — 돌아갈 자리를 <b>재고</b> 내린다.
     *
     * <p>★ 앵커는 표식이지 발판이 아니다. 「장터」 앵커는 마을 원점 = <b>광장 우물 한복판</b>이다 —
     * 재지 않고 내리면 사람이 우물에 갇힌다 (실제로 갇혔다). 내리기 전에 잰다 ({@link Standing}).
     *
     * <p>돌아갈 자리도 못 믿는다: 리붓 사이에 그 자리가 물에 잠겼을 수도, 누가 벽을 쌓았을 수도 있다.
     * 그래서 <b>원래 자리도 잰다</b> — 못 서면 둘레에서 설 자리를 찾는다.
     */
    private Location wayOut(Spot origin) {
        Location home = origin == null ? null : origin.location();
        if (home != null && !isDojang(home.getWorld())) {
            Location spot = Standing.landing(home);
            if (spot != null) {
                return spot;
            }
            plugin.getLogger().warning("[연무장/귀환] 돌아갈 자리에 설 수 없다 "
                    + Standing.describe(home) + " — 장터로 내린다");
        }
        Location market = plugin.anchor("장터");
        Location spot = market == null ? null : Standing.landing(market);
        if (spot == null) {
            spot = Standing.landing(Bukkit.getWorlds().get(0).getSpawnLocation(), 32);
        }
        if (spot == null) {
            spot = Bukkit.getWorlds().get(0).getSpawnLocation();
            plugin.getLogger().severe("[연무장/귀환] 설 자리를 못 찾았다 — 세계 스폰에 떨군다 "
                    + Standing.describe(spot) + " · /혼천 앵커검사");
        }
        return spot;
    }

    /**
     * ★ <b>되돌리는 손</b> — 리붓 뒤에 사람을 구한다.
     *
     * <p>여기 <b>아무것도 없었다</b>. 연무장에 남은 채 서버가 내려가면 <b>아무도 안 꺼냈다</b> —
     * 사람은 빈손·시험 장부로 평평한 시험 월드에 남았고, 진짜 짐은 메모리와 함께 사라졌다.
     *
     * <p>{@code LOWEST} 로 가장 먼저 돈다. {@code SkillListener.onJoin} 이 시트를 싣고
     * {@code Antechamber.onJoin} 이 미접합자를 나루로 보내기 <b>전에</b> 진짜 장부를 제자리에 돌려놔야
     * 한다 — 안 그러면 저들이 <b>시험용 빈 장부</b>를 보고 판단한다 (일류 무인이 나루로 끌려간다).
     *
     * <p>순간이동만 다음 틱으로 미룬다 (접속 이벤트 안의 이동은 클라이언트를 흔든다).
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Deposit d = vault.get(id);
        if (d == null || !d.inside) {
            if (isDojang(player.getWorld())) {
                // 연무장에 있는데 맡긴 것이 없다 — 금고가 없던 시절의 유령이거나 금고를 잃었다.
                // ★ 짐에 손대지 않는다. 자리만 옮기고 짖는다.
                plugin.getLogger().severe("[연무장] ★ " + player.getName()
                        + " 이(가) 연무장에서 접속했는데 금고가 비었다 — 짐은 그대로 두고 내보낸다");
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    player.setGameMode(org.bukkit.GameMode.SURVIVAL);
                    player.teleport(wayOut(null));
                    player.sendMessage(ChatColor.RED + "연무장에서 깨어났다 — 맡긴 기록이 없어 "
                            + "짐은 건드리지 않고 세계로 내보낸다.");
                });
            }
            return;
        }
        // ★ 데이터는 **지금** 돌려놓는다 (뒤이어 도는 손들이 진짜 장부를 봐야 한다)
        String why = isDojang(player.getWorld())
                ? "연무장 안에서 서버가 내려갔다 (또는 연무장에서 접속을 끊었다)"
                : "연무장에 맡긴 것이 남아 있었다";
        plugin.getLogger().warning("[연무장] " + player.getName()
                + " — 금고에서 진짜 장부·무공·짐을 돌려준다 (" + why + ")");
        final Spot origin = d.origin;   // ★ giveBack 이 비운다 — 먼저 챙긴다
        giveBack(player, d, why);
        // 순간이동은 다음 틱에 한 번 더 (접속 직후의 이동은 씹힐 수 있다 — 확실히 내린다)
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && isDojang(player.getWorld())) {
                player.teleport(wayOut(origin));
                player.setFallDistance(0f);
            }
        });
    }

    // ─── 능력치 조정 ───

    void setRealm(Player player, String realm) {
        SkillEngine.State state = plugin.skills().state(player);
        state.realm = realm;
        state.energy = plugin.skillEngine().poolOf(realm);
        player.sendMessage(ChatColor.GOLD + "경지 " + realm
                + ChatColor.GRAY + " · 내력 풀 " + state.energy);
    }

    void setEnergy(Player player, int energy) {
        SkillEngine.State state = plugin.skills().state(player);
        state.energy = Math.max(0, energy);
        player.sendMessage(ChatColor.GOLD + "내력 " + state.energy);
    }

    void grantSkill(Player player, String skillId, double days) {
        plugin.ledger(player.getUniqueId()).grant(skillId, days);
        player.sendMessage(ChatColor.GOLD + skillId + ChatColor.GRAY + " 수련 +"
                + String.format("%.1f", days) + "일 (누적 "
                + String.format("%.1f", plugin.ledger(player.getUniqueId()).allSkills()
                        .getOrDefault(skillId, 0.0)) + "일)");
    }

    // ─── 허수아비 ───

    /**
     * <b>설 자리</b> — 앞 4칸에 몸이 들어갈 틈이 있는가.
     *
     * <p>옛 코드는 앞 4칸의 <b>x·z 만</b> 쓰고 높이는 <b>내 발 높이를 그대로</b> 썼다. 앞의 땅이 한 켜만
     * 높아도 허수아비는 <b>흙에 파묻혔다</b> — 사용자가 본 것이 그것이다. 아무도 "거기 몸이 설 수
     * 있는가"를 묻지 않았다.
     *
     * <p>그래서 <b>묻는다</b>: 발밑이 단단하고 머리까지 두 칸이 비었는가. 내 발 높이에서 위아래로
     * 훑되 <b>가까운 쪽부터</b> 고른다 (계단 한 칸을 오르내리는 것이 절벽을 찾는 것보다 낫다).
     * 못 찾으면 <b>지어내지 않고 null 을 돌려준다</b> — 조용한 기본값이 오늘 사람을 우물에 가뒀다.
     */
    private static Location footing(Player player) {
        Location eye = player.getLocation();
        Location ahead = eye.clone().add(eye.getDirection().setY(0).normalize().multiply(4));
        int baseY = eye.getBlockY();
        for (int step = 0; step <= 4; step++) {
            for (int dir : (step == 0 ? new int[]{0} : new int[]{step, -step})) {
                int y = baseY + dir;
                Location foot = new Location(ahead.getWorld(), ahead.getBlockX() + 0.5, y, ahead.getBlockZ() + 0.5);
                boolean floor = foot.clone().add(0, -1, 0).getBlock().getType().isSolid();
                boolean room = foot.getBlock().isPassable()
                        && foot.clone().add(0, 1, 0).getBlock().isPassable();
                if (floor && room) {
                    foot.setYaw(eye.getYaw() + 180.0f);   // 나를 마주 본다
                    return foot;
                }
            }
        }
        return null;
    }

    /**
     * 허수아비 — 맞아 주는 몸. 죽지 않고, 반격하지 않고, <b>맞은 것을 말한다</b>.
     *
     * <p>몸은 좀비다 (인간형이라 격·무기 판정이 사람 상대와 같게 돈다 — 짚단을 때리면
     * 사람을 때린 것과 다른 숫자가 나온다). AI 를 끄고, 죽지 않게 하고, 명패로 장부를 보여 준다.
     */
    void dummy(Player player, int durability) {
        World w = player.getWorld();
        Location at = footing(player);
        if (at == null) {
            player.sendMessage(ChatColor.RED + "앞에 설 자리가 없다 — 트인 곳에서 다시 부르라");
            return;
        }
        Zombie z = w.spawn(at, Zombie.class, e -> {
            e.setAI(false);
            e.setSilent(true);
            e.setCollidable(true);
            e.setRemoveWhenFarAway(false);
            e.setShouldBurnInDay(false);
            e.setAdult();
            e.getPersistentDataContainer().set(KEY_DUMMY, PersistentDataType.INTEGER, durability);
            // ★ 체력은 **특성에게 물어서** 넣는다. 2048 을 손으로 넣으면 터진다 —
            //   MAX_HEALTH 특성의 범위는 …1024 라, 기준값 2048 은 실효값 1024 로 깎이고
            //   setHealth(2048) 이 "0..1024 여야 한다"며 예외를 던진다. 그러면 좀비는 **아예 안 태어난다.**
            //   (로그 2026-07-12 21:24 — 사용자가 적수대를 세 번 눌렀고 세 번 다 허수아비가 안 나왔다.)
            //   getValue() 는 이미 깎인 값이다 — 상한이 몇이든 안 터진다.
            org.bukkit.attribute.AttributeInstance attr = e.getAttribute(Attribute.MAX_HEALTH);
            if (attr != null) {
                attr.setBaseValue(2048);   // 죽지 않는다 (장부를 위해 산다 — 실효값은 상한까지)
                e.setHealth(attr.getValue());
            }
            e.setCustomNameVisible(true);
            e.setCustomName(ChatColor.GRAY + "허수아비 · 내구 " + durability);
        });
        tally.put(z.getUniqueId(), new double[]{0, 0, 0});
        player.sendMessage(ChatColor.GOLD + "허수아비 (내구 " + durability + ") — 때려라. "
                + ChatColor.GRAY + "명패가 누적·합수·평균을 말한다");
    }

    /** 몹 시험 — 등록부의 적을 그대로 부른다 (짐승·산적·무인) */
    void mob(Player player, String foeId) {
        mob(player, foeId, false);
    }

    /**
     * 몹 시험 — 등록부의 적을 그대로 부른다 (짐승·산적·무인).
     *
     * <p><b>{@code walking} 이 왜 있는가:</b> 기본은 {@code setAI(false)} 다 — 시험대의 몸은
     * 계기라서, 걸어 다니면 사거리·피해를 못 잰다. 그런데 <b>형체(MobDisplay)의 다리 관절은
     * 「실제로 움직인 거리」로 위상이 돈다</b>({@code rig.phase += moved * walkBobRate}) —
     * 즉 <b>AI 를 끄면 다리가 영원히 안 흔들린다.</b> 걷는 모습을 봐야 하는 시험
     * (하네스의 보행 애니메이션 검증)에서는 이 축이 필요하다.
     *
     * <p>{@code walking} 이면 AI 를 켜고 <b>부른 자를 표적으로</b> 준다 — 그래야 제자리에서
     * 배회하지 않고 카메라 쪽으로 곧장 걸어온다 (보행 위상이 확실히 돈다).
     */
    void mob(Player player, String foeId, boolean walking) {
        Location at = footing(player);   // 허수아비와 같은 손 — 적도 땅에 파묻히면 안 된다
        if (at == null) {
            player.sendMessage(ChatColor.RED + "앞에 설 자리가 없다 — 트인 곳에서 다시 부르라");
            return;
        }
        LivingEntity spawned = plugin.hunting().spawnById(foeId, at);
        if (spawned == null) {
            player.sendMessage(ChatColor.RED + "등록부에 없는 적: " + foeId);
            return;
        }
        // ★ 다리 관절은 **이동거리**로 돈다 — AI 를 끄면 걷기 애니메이션이 영원히 멈춘다
        spawned.setAI(walking);
        if (walking && spawned instanceof org.bukkit.entity.Mob beast) {
            beast.setTarget(player);   // 제자리 배회 말고 이쪽으로 걸어오게 (보행 위상을 돌린다)
        }
        player.sendMessage(ChatColor.GOLD + spawned.getCustomName() + ChatColor.GRAY + " 을(를) 불렀다"
                + (walking ? ChatColor.YELLOW + " (걷는다 — AI 켬 · 표적 " + player.getName() + ")"
                           : ChatColor.DARK_GRAY + " (AI 끔 — 계기용)"));
    }

    void clear(Player player) {
        int removed = 0;
        for (org.bukkit.entity.Entity e : player.getWorld().getEntities()) {
            if (e instanceof LivingEntity && !(e instanceof Player)) {
                e.remove();
                removed++;
            }
        }
        tally.clear();
        player.sendMessage(ChatColor.GRAY + "연무장을 치웠다 — " + removed + "체");
    }

    /** {@code /혼천 금고} — <b>맡긴 것이 있는가</b>. 눈을 만들었으면 사람도 볼 수 있어야 한다 */
    List<String> auditLines() {
        List<String> out = new ArrayList<>();
        Map<UUID, Deposit> stillHeld = held();
        out.add(ChatColor.GOLD + "── 연무장 금고 ── " + ChatColor.GRAY + VAULT_FILE
                + (vaultFile().isFile() ? "" : ChatColor.RED + " (파일 없음)"));
        if (stillHeld.isEmpty()) {
            out.add(ChatColor.GRAY + "맡긴 것 없음 — 연무장 안에 있어야 할 사람이 없다.");
        }
        stillHeld.forEach((id, d) -> {
            org.bukkit.OfflinePlayer who = Bukkit.getOfflinePlayer(id);
            out.add(ChatColor.YELLOW + "★ " + (who.getName() == null ? id.toString() : who.getName())
                    + ChatColor.GRAY + " — 장부 " + (d.realLedger != null ? "○" : "×")
                    + " · 무공 " + (d.realState != null ? "○" : "×")
                    + " · 짐 " + (d.realItems != null ? "○" : "×")
                    + " · 돌아갈 자리 " + (d.origin == null ? "없음"
                        : d.origin.world() + " (" + (int) d.origin.x() + ", "
                            + (int) d.origin.y() + ", " + (int) d.origin.z() + ")")
                    + (who.isOnline() ? "" : ChatColor.DARK_GRAY + " · 접속하면 돌려준다"));
        });
        int records = vault.size() - stillHeld.size();
        if (records > 0) {
            out.add(ChatColor.DARK_GRAY + "시험 기록 " + records + "인 (맡긴 것 없음 — 다시 들어오면 이어진다)");
        }
        return out;
    }

    // ─── 계측 — 허수아비는 맞은 것을 말한다 ───

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        double[] t = tally.get(target.getUniqueId());
        if (t == null) {
            return;
        }
        // 다음 틱에 읽는다 — 우리 무공 리스너가 피해를 고쳐 쓴 **뒤**의 값이 진실이다
        Bukkit.getScheduler().runTask(plugin, () -> {
            double dealt = event.getFinalDamage();
            t[0] += dealt;
            t[1] += 1;
            t[2] = dealt;
            target.setHealth(Math.min(2048,
                    target.getAttribute(Attribute.MAX_HEALTH).getValue()));   // 죽지 않는다
            int durability = target.getPersistentDataContainer()
                    .getOrDefault(KEY_DUMMY, PersistentDataType.INTEGER, 20);
            double avg = t[1] == 0 ? 0 : t[0] / t[1];
            int ttk = avg <= 0 ? 0 : (int) Math.ceil(durability / avg);
            target.setCustomName(ChatColor.GRAY + "허수아비 · "
                    + ChatColor.WHITE + String.format("최근 %.1f", t[2])
                    + ChatColor.GRAY + " · 누적 " + String.format("%.0f", t[0])
                    + " · " + (int) t[1] + "합 · 평균 " + String.format("%.2f", avg)
                    + ChatColor.YELLOW + " → 내구 " + durability + " 상대 TTK " + ttk + "합");
        });
    }

    /**
     * 연무장의 허수아비·몹은 세계의 장부에 오르지 않는다 (소문·명분·혈채 없음).
     *
     * <p><b>입도진(나루)도 같다</b> — 강호에 들지 않은 자가 나루의 허수아비를 백 번 베어도
     * 그것은 강호의 일이 아니다. 세 곳({@code SkillCast}·{@code SkillListener}·{@code HuntingGrounds})이
     * 이미 이 한 문장을 부르고 있으므로, <b>여기 한 줄이 세 곳을 다 막는다</b>.
     */
    static boolean suppressWorldEvents(World world) {
        return isDojang(world) || Antechamber.isAntechamber(world);
    }
}
