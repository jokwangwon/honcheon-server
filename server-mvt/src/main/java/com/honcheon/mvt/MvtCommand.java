package com.honcheon.mvt;

import com.honcheon.core.rules.JudgmentEngine;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * /혼천 — 관리자 검증 명령. 엔진을 직접 두드려 수치를 눈으로 확인한다.
 * 서브커맨드: 원장 · 판정 · 팔기 · 물가 · 정산 · 협공 · 도움말
 */
public final class MvtCommand implements CommandExecutor {

    private final HoncheonMvt plugin;

    public MvtCommand(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return help(sender);
        }
        try {
            return switch (args[0]) {
                case "원장" -> ledger(sender);
                case "판정" -> judge(sender, args);
                case "팔기" -> sell(sender);
                case "물가" -> prices(sender, args);
                case "정산" -> settle(sender, args);
                case "협공" -> coop(sender, args);
                default -> help(sender);
            };
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "오류: " + e.getMessage());
            return true;
        }
    }

    private boolean ledger(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        PlayerLedger ledger = plugin.ledger(player.getUniqueId());
        sender.sendMessage(ChatColor.GOLD + "── 화후 원장 ──");
        ledger.allSkills().forEach((skill, days) -> sender.sendMessage(String.format(
                ChatColor.AQUA + "%s %s" + ChatColor.WHITE + " 누적 %.2f일 (숙련 %d → 다음 %.0f%%)",
                skill, Glyphs.gauge(ledger.progressToNext(skill, plugin.progression())),
                days, ledger.levelOf(skill, plugin.progression()),
                ledger.progressToNext(skill, plugin.progression()) * 100)));
        sender.sendMessage(ChatColor.WHITE + "오늘 적립 " + String.format("%.2f", ledger.grantedToday())
                + "일 / 전낭 " + ChatColor.YELLOW + ledger.money() + "문"
                + ChatColor.WHITE + " / 마크: 실전 " + ledger.marks실전() + " · 사선 " + ledger.marks사선());
        return true;
    }

    private boolean judge(CommandSender sender, String[] args) {
        int exec = Integer.parseInt(args[1]);
        int resist = Integer.parseInt(args[2]);
        Random random = ThreadLocalRandom.current();
        int roll = random.nextInt(6) + 1 + random.nextInt(6) + 1;
        JudgmentEngine.Tier tier = plugin.judgment().resolve(exec, roll, resist);
        int margin = exec + roll - resist;
        sender.sendMessage(String.format("%s2d6=%d │ 실행력 %d vs 저항 %d │ 마진 %+d → %s",
                ChatColor.GOLD, roll, exec + roll, resist, margin, tier.name()));
        if (plugin.judgment().isAutoSuccess(exec, resist)) {
            sender.sendMessage(ChatColor.GRAY + "(기대 마진 +8 이상 — 실전이라면 판정 생략 자동 성공)");
        }
        return true;
    }

    private boolean sell(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            String name = item == null || !item.hasItemMeta() ? null : item.getItemMeta().getDisplayName();
            if (name == null) {
                continue;
            }
            String plain = ChatColor.stripColor(name);
            Integer base = switch (plain) {
                case "늑대 가죽" -> plugin.economy().basePrice("사냥_부산물", "늑대_가죽");
                case "여우 가죽" -> plugin.economy().basePrice("사냥_부산물", "여우_가죽");
                default -> null;
            };
            if (base != null) {
                total += plugin.economy().npcBuyPrice(base, false) * item.getAmount();
                player.getInventory().remove(item);
            }
        }
        if (total == 0) {
            sender.sendMessage(ChatColor.GRAY + "팔 가죽이 없다.");
        } else {
            plugin.ledger(player.getUniqueId()).earn(total);
            sender.sendMessage(ChatColor.YELLOW + "매입가(시세 50%)로 " + total + "문을 받았다. (흥정은 상술 판정의 몫)");
        }
        return true;
    }

    private boolean prices(CommandSender sender, String[] args) {
        int economyIndex = args.length > 1 ? Integer.parseInt(args[1]) : 50;
        sender.sendMessage(ChatColor.GOLD + "── 물가 (지역 경제 지표 " + economyIndex + ") ──");
        for (Map.Entry<String, String> entry : Map.of(
                "만두_한_끼", "생활", "봉놋방_1박", "생활", "검_범철", "장비", "늑대_가죽", "사냥_부산물").entrySet()) {
            int base = plugin.economy().basePrice(entry.getValue(), entry.getKey());
            sender.sendMessage(String.format("%s%s: %d문 (기준 %d × %.1f)", ChatColor.WHITE,
                    entry.getKey(), plugin.economy().adjustedPrice(base, economyIndex),
                    base, plugin.economy().priceMultiplier(economyIndex)));
        }
        return true;
    }

    private boolean settle(CommandSender sender, String[] args) {
        int help = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int clamped = plugin.lifecycle().clampPlayerHand(help);
        int power = plugin.lifecycle().lifeCheckPower(3, 4, clamped);   // 한백: 화술3 + 고객_파악4
        int difficulty = plugin.lifecycle().rungDifficulty("상인의_길", "상단_지점주");
        int margin = plugin.lifecycle().seasonMargin(power, difficulty);
        sender.sendMessage(String.format("%s한백 계절 정산: 판정력 %d vs 다음 칸(상단_지점주) %d → 마진 %+d %s",
                ChatColor.GOLD, power, difficulty, margin,
                margin >= 0 ? ChatColor.GREEN + "상승 궤적 적립" : ChatColor.GRAY + "정체"));
        if (clamped != help) {
            sender.sendMessage(ChatColor.GRAY + "(개입 보정은 ±3 클램프 — 입력 " + help + " → " + clamped + ")");
        }
        return true;
    }

    private boolean coop(CommandSender sender, String[] args) {
        int attackers = Integer.parseInt(args[1]);
        sender.sendMessage(ChatColor.GOLD + "협공 " + attackers + "인 → 보정 +"
                + plugin.party().coopAttackBonus(attackers)
                + ChatColor.GRAY + " (캡 +3 — 그 위는 합격진의 영역)");
        return true;
    }

    private boolean help(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "── 혼천 MVT ──");
        sender.sendMessage("/혼천 원장 — 화후·전낭·마크 / /혼천 판정 <실행력> <저항>");
        sender.sendMessage("/혼천 팔기 — 가죽 매각(50%) / /혼천 물가 [경제지수 0~100]");
        sender.sendMessage("/혼천 정산 [개입 -3~3] — 한백 계절 정산 / /혼천 협공 <인원>");
        sender.sendMessage(ChatColor.GRAY + "사냥 루프: 늑대·여우(격상) vs 가축(회색) — 기세·적립·감쇠·돌파를 몸으로 확인");
        return true;
    }
}
