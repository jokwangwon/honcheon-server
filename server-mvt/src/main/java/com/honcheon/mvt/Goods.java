package com.honcheon.mvt;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * 지물·재료·기물 — 병기와 같은 문법(item_model + PDC + 툴팁)으로 나머지 등록 채널을 세운다.
 *
 * <p>정본: {@code config/resourcepack_design.yml item_channels} — 여기 등록된 것만 만든다
 * (canon_rule: 신규 아이템 발명 금지). 모델 키는 {@code resourcepack/assets/honcheon/items/**} 의
 * 파일 경로와 1:1 이다.
 *
 * <p>보류 채널(청낭·호신부·천잠사_요대)은 만들지 않는다 — item_channels 에 {@code status: 보류} 로
 * 등록되어 있고 blocker(바닐라 수납 GUI·개체 명명·매기 동작 차단)가 아직 접합되지 않았다.
 * 지금 지급하면 플레이어가 번들에 물건을 넣고 이름표로 소를 이름 짓는다.
 *
 * <p>표시 이름은 기존 매각 로직(TradeListener·MvtCommand: "늑대 가죽"·"여우 가죽" 문자열 비교)과
 * <b>반드시 일치</b>해야 한다 — 이름을 바꾸면 가죽이 팔리지 않는다.
 */
public final class Goods {

    /** 지물 종류 태그 — 판정층이 읽는다 (예: 비급을 읽는 행위) */
    public static final NamespacedKey KEY_GOODS = new NamespacedKey("honcheon", "goods");
    /** 전표 액면 (문) — 텍스처 1장이 모든 액면을 감당한다 (액면은 lore) */
    public static final NamespacedKey KEY_FACE_VALUE = new NamespacedKey("honcheon", "face_value");

    // ══════════ 재료 — 사냥 부산물 (economy.yml price_table.사냥_부산물) ══════════

    /**
     * 가죽 — 늑대·여우·호랑이. 셋 다 베이스는 leather 이고 실루엣(모델 키)이 구별한다.
     * 컴포넌트 없는 일반 가죽은 바닐라 그대로 (전역 오염 0).
     *
     * @param beast 늑대 | 여우 | 호랑이
     */
    public static ItemStack pelt(String beast) {
        String model = switch (beast) {
            case "늑대" -> "wolf";
            case "여우" -> "fox";
            case "호랑이" -> "tiger";
            default -> throw new IllegalArgumentException("등록되지 않은 가죽: " + beast);
        };
        String flavor = switch (beast) {
            case "늑대" -> "들짐승의 가죽 — 장터에서 값이 매겨진다.";
            case "여우" -> "붉은 기가 도는 가죽 — 늑대 가죽 서너 장 값은 한다.";
            default -> "맹수의 가죽 — 이것은 색이 아니라 이름이 판다.";
        };
        return build(Material.LEATHER, "pelt/" + model, ChatColor.WHITE + beast + " 가죽",
                beast + "_가죽", List.of(
                        ChatColor.GRAY + "사냥 부산물",
                        ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + flavor,
                        ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "장터의 장쇠가 시세의 절반에 사들인다."));
    }

    /** 웅담 — 사냥 부산물 (약재상·의원의 물건) */
    public static ItemStack ungdam() {
        return build(Material.SLIME_BALL, "spoil/ungdam", ChatColor.WHITE + "웅담", "웅담",
                List.of(ChatColor.GRAY + "약재 — 사냥 부산물",
                        ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "곰의 쓸개. 의원이 탐낸다."));
    }

    /** 약재 — 묶은 약초 다발 (컴포넌트 없는 일반 밀은 바닐라 밀 그대로) */
    public static ItemStack yakjae() {
        return build(Material.WHEAT, "herb/yakjae", ChatColor.WHITE + "약재", "약재",
                List.of(ChatColor.GRAY + "약재 — 의원의 재료",
                        ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "묶은 약초 다발. 이것만으로는 약이 되지 않는다."));
    }

    // ══════════ 지물 — 소모품·서물·증서·화물 ══════════

    /** 영약 — 단약. 효과는 판정층이 준다 (바닐라 효과 없음) */
    public static ItemStack yeongyak() {
        return build(Material.MAGMA_CREAM, "pill/yeongyak", ChatColor.RED + "영약(靈藥)", "영약",
                List.of(ChatColor.GRAY + "단약 — 삼키면 기혈이 도는다",
                        ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "값비싼 한 알. 상처가 아니라 근본을 채운다.",
                        ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "(효과는 판정층의 몫 — 배선 대기)"));
    }

    /** 비급 진본 — 인장이 진본을 만든다 */
    public static ItemStack manualOriginal(String skillName) {
        return build(Material.WRITTEN_BOOK, "tome/manual_original",
                ChatColor.GOLD + "비급 진본 — " + skillName, "비급_진본",
                List.of(ChatColor.GRAY + "무공서 — 읽으면 익힌다",
                        ChatColor.RED + "" + ChatColor.ITALIC + "스승 없이 익히면 부작용을 각오해야 한다 (주화입마).",
                        ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "봉인 끈과 주사 인장 — 이것이 진본의 표식."));
    }

    /** 비급 필사본 — 진본과의 차이가 인장이다 */
    public static ItemStack manualCopy(String skillName) {
        return build(Material.BOOK, "tome/manual_copy",
                ChatColor.WHITE + "비급 필사본 — " + skillName, "비급_필사본",
                List.of(ChatColor.GRAY + "무공서 — 베껴 쓴 것",
                        ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "인장이 없다. 빠진 구결이 있을지도 모른다."));
    }

    /** 심법 구결 — 낱장 */
    public static ItemStack gugyeol(String simbeopName) {
        return build(Material.WRITABLE_BOOK, "tome/gugyeol",
                ChatColor.AQUA + "구결 — " + simbeopName, "심법_구결",
                List.of(ChatColor.GRAY + "심법 구결 — 외워야 하는 것",
                        ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "붓 자국 세로 세 획. 소리 내어 읽는 물건이 아니다."));
    }

    /** 전표 — 액면은 lore (텍스처 1장이 모든 액면) */
    public static ItemStack jeonpyo(int faceValue) {
        ItemStack item = build(Material.PAPER, "coin/jeonpyo",
                ChatColor.YELLOW + "전표 — " + faceValue + "냥", "전표",
                List.of(ChatColor.GRAY + "전장 발행 증서",
                        ChatColor.WHITE + "액면 " + ChatColor.YELLOW + faceValue + "냥",
                        ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "장부는 거짓말을 하지 않는다 — 인출 시 수수료가 붙는다."));
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(KEY_FACE_VALUE, PersistentDataType.INTEGER, faceValue);
        item.setItemMeta(meta);
        return item;
    }

    /** 표물 — 표국의 화물 (레일 없는 세계에선 설치 불가: 바닐라 규칙이 곧 방어) */
    public static ItemStack pyomul() {
        return build(Material.CHEST_MINECART, "cargo/pyomul", ChatColor.GOLD + "표물(鏢物)", "표물",
                List.of(ChatColor.GRAY + "표국 화물 — 목적지까지 지켜야 한다",
                        ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "봉인 끈과 표국 인패. 뜯으면 표국이 안다."));
    }

    // ══════════ 기물 — 징발 완료분만 (보류 3종은 만들지 않는다) ══════════

    /** 피독주 — 독 저항 판정 +2 (equipment.yml trinkets) */
    public static ItemStack pidokju() {
        return build(Material.HEART_OF_THE_SEA, "trinket/pidokju",
                ChatColor.DARK_AQUA + "피독주(避毒珠)", "피독주",
                List.of(ChatColor.GRAY + "기물 — 슬롯 1칸 차지",
                        ChatColor.WHITE + "독 저항 판정 " + ChatColor.GREEN + "+2",
                        ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "표면의 균열이 독을 마신다고들 한다."));
    }

    /** 야명주 — 야간 시야 페널티 무효 */
    public static ItemStack yamyeongju() {
        return build(Material.NETHER_STAR, "trinket/yamyeongju",
                ChatColor.YELLOW + "야명주(夜明珠)", "야명주",
                List.of(ChatColor.GRAY + "기물 — 슬롯 1칸 차지",
                        ChatColor.WHITE + "야간 시야 페널티 " + ChatColor.GREEN + "무효",
                        ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "어둠 속에서 스스로 빛난다."));
    }

    // ══════════ 공통 ══════════

    @SuppressWarnings("deprecation")
    private static ItemStack build(Material base, String modelPath, String displayName,
                                   String goodsTag, List<String> lore) {
        ItemStack item = new ItemStack(base);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("honcheon", modelPath));
        meta.setDisplayName(displayName);
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(KEY_GOODS, PersistentDataType.STRING, goodsTag);
        item.setItemMeta(meta);
        return item;
    }

    /** 지물 종류 태그 판독 — 없으면 null (바닐라 물건) */
    public static String goodsOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(KEY_GOODS, PersistentDataType.STRING);
    }

    private Goods() {
    }
}
