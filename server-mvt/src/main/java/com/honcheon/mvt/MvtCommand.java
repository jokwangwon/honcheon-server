package com.honcheon.mvt;

import com.honcheon.core.rules.JudgmentEngine;
import org.bukkit.ChatColor;
import org.bukkit.Location;
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
                case "원장", "정보" -> ledger(sender);   // 원장 = 하위호환 (terminology)
                case "판정" -> judge(sender, args);
                case "팔기" -> sell(sender);
                case "물가" -> prices(sender, args);
                case "정산" -> settle(sender, args);
                case "협공" -> coop(sender, args);
                case "조성" -> buildTown(sender);
                case "검수" -> auditTown(sender);   // 규칙 린트 — 콘솔 가능 (앵커 기준)
                case "조감" -> renderTown(sender);   // 조감도 PNG — 콘솔 가능
                case "문장" -> crests(sender);
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
        // 화면 UI — 채팅 대신 경락도 GUI (조작 가능한 인벤토리 창). 채팅판은 chatLedger 폴백
        LedgerGui.open(plugin, player);
        return true;
    }

    @SuppressWarnings("unused")   // 팩 미적용 환경 폴백 — /혼천 정보 채팅판 (필요 시 재배선)
    private boolean chatLedger(Player player) {
        PlayerLedger ledger = plugin.ledger(player.getUniqueId());
        player.sendMessage(ChatColor.GOLD + "── 수련 기록 ──");
        ledger.allSkills().forEach((skill, days) -> player.sendMessage(String.format(
                ChatColor.AQUA + "%s %s" + ChatColor.WHITE + " 누적 %.2f일 (숙련 %d → 다음 %.0f%%)",
                skill, Glyphs.gauge(ledger.progressToNext(skill, plugin.progression())),
                days, ledger.levelOf(skill, plugin.progression()),
                ledger.progressToNext(skill, plugin.progression()) * 100)));
        player.sendMessage(ChatColor.WHITE + "오늘 적립 " + String.format("%.2f", ledger.grantedToday())
                + "일 / 소지금 " + ChatColor.YELLOW + ledger.money() + "문"
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
        // 조성된 마을이 있으면 매각은 장터에서만 — 맵이 의미를 갖기 시작한다 (M2b)
        Location market = plugin.anchor("장터");
        if (market != null && (player.getWorld() != market.getWorld()
                || player.getLocation().distanceSquared(market) > 15 * 15)) {
            player.sendMessage(ChatColor.GRAY + "여기서는 아무도 가죽을 사지 않는다 — 장터로 가라. ("
                    + market.getBlockX() + ", " + market.getBlockZ() + " 부근, 붉은 차양 노점)");
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

    /** 청하현 조성 (M2b) — 관리자 전용, 재조성 = 같은 마을 (결정론 생성) */
    private boolean buildTown(CommandSender sender) {
        java.util.List<Zone> zones = new java.util.ArrayList<>();
        Map<String, Location> anchors;
        if (sender instanceof Player player) {
            if (!player.isOp()) {
                player.sendMessage(ChatColor.RED + "조성은 관리자의 몫이다.");
                return true;
            }
            anchors = CheonghaBuilder.build(player, zones);
        } else {
            // 콘솔·RCON — 직전 마을 중심(장터 앵커)에 재조성. 결정론이므로 같은 마을이 선다
            Location center = plugin.anchor("장터");
            if (center == null) {
                sender.sendMessage(ChatColor.RED
                        + "콘솔 조성은 기존 마을이 있어야 한다 (중심을 모른다) — 최초 1회는 인게임에서.");
                return true;
            }
            anchors = CheonghaBuilder.build(center.getWorld(), center.getBlockX(),
                    center.getBlockY() - 1, center.getBlockZ(), zones);
        }
        plugin.setAnchors(anchors);
        plugin.setZones(zones);
        sender.sendMessage(ChatColor.GOLD + "청하현이 섰다 — 장소 " + anchors.size()
                + "곳 · 구역 " + zones.size() + "곳 (입장 타이틀)");
        return true;
    }

    /**
     * 검수 — 조성된 마을을 가이드 규칙으로 린트 (building_style_guide.md).
     * 콘솔 가능: 좌표를 앵커에서 얻으므로 플레이어가 필요 없다 (자동 반복 루프의 눈).
     */
    private boolean auditTown(CommandSender sender) {
        Location center = plugin.anchor("장터");
        if (center == null) {
            sender.sendMessage(ChatColor.RED + "조성된 마을이 없다 — 먼저 /혼천 조성");
            return true;
        }
        for (String line : TownAudit.audit(center.getWorld(), plugin.anchors(),
                center.getBlockX(), center.getBlockY() - 1, center.getBlockZ())) {
            sender.sendMessage(line);
        }
        return true;
    }

    /** 조감 — 탑다운·아이소메트릭·건물별 PNG 렌더 (plugins/HoncheonMVT/render/). 콘솔 가능 */
    private boolean renderTown(CommandSender sender) {
        Location center = plugin.anchor("장터");
        if (center == null) {
            sender.sendMessage(ChatColor.RED + "조성된 마을이 없다 — 먼저 /혼천 조성");
            return true;
        }
        java.io.File dir = new java.io.File(plugin.getDataFolder(), "render");
        for (String line : TownRender.render(center.getWorld(), plugin.anchors(),
                center.getBlockX(), center.getBlockY() - 1, center.getBlockZ(), dir)) {
            sender.sendMessage(ChatColor.GRAY + line);
        }
        return true;
    }

    /** 경지 문장 글리프 시연 — 리소스팩 검증용 (E020~E027) */
    private boolean crests(CommandSender sender) {
        StringBuilder line = new StringBuilder(ChatColor.GOLD + "경지 문장: ");
        for (String realm : Glyphs.crestRealms()) {
            line.append(ChatColor.WHITE).append(Glyphs.realmCrest(realm))
                    .append(ChatColor.GRAY).append(realm).append("  ");
        }
        sender.sendMessage(line.toString());
        sender.sendMessage(ChatColor.DARK_GRAY + "□로 보이면 리소스팩 미적용 — 팩 재컴파일·재적용 필요");
        return true;
    }

    private boolean help(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "── 혼천 MVT ──");
        sender.sendMessage("/혼천 정보 — 수련·소지금·마크 / /혼천 판정 <실행력> <저항>");
        sender.sendMessage("/혼천 팔기 — 가죽 매각(50%) / /혼천 물가 [경제지수 0~100]");
        sender.sendMessage("/혼천 정산 [개입 -3~3] — 한백 계절 정산 / /혼천 협공 <인원>");
        sender.sendMessage("/혼천 조성 — 청하현 마을 생성 (관리자) / /혼천 문장 — 경지 문장 글리프 확인");
        sender.sendMessage(ChatColor.GRAY + "사냥 루프: 늑대·여우(격상) vs 가축(회색) — 기세·적립·감쇠·돌파를 몸으로 확인");
        return true;
    }
}
