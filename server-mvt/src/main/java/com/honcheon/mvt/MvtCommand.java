package com.honcheon.mvt;

import com.honcheon.core.rules.JudgmentEngine;
import com.honcheon.mvt.forge.FloraForge;
import com.honcheon.mvt.forge.MountainRangeForge;
import com.honcheon.mvt.forge.RangeField;
import com.honcheon.mvt.forge.RangeSpec;
import com.honcheon.mvt.forge.HwasanCampusBuilder;
import com.honcheon.mvt.forge.RangeZone;
import com.honcheon.mvt.forge.SpireField;
import com.honcheon.mvt.forge.TerraceForge;
import com.honcheon.mvt.forge.TrailBuilder;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * /혼천 — 관리자 검증 명령. 엔진을 직접 두드려 수치를 눈으로 확인한다.
 * 서브커맨드: 원장 · 판정 · 팔기 · 물가 · 정산 · 협공 · 도움말
 */
public final class MvtCommand implements CommandExecutor {

    private final HoncheonMvt plugin;

    public MvtCommand(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    /**
     * ★B-190 ② — 세계 사건(레이드)의 결말 선고: /혼천 레이드해소 <막id> <박key> <격퇴|패배>.
     * 무대(보스 스폰·페이즈)가 서기 전까지의 발신자다 — 대장들의 싸움(사용자 확정 2026-07-31:
     * 제5막 승패는 인게임 레이드가 정한다)을 사람이 심판하고, 다리(raid_resolved)가 봇의 해소
     * 그릇(막해소:&lt;막&gt;.&lt;박&gt;)으로 나른다. ★무대가 서면 같은 emit 을 무대가 부른다 —
     * 이 명령은 그때도 남는다 (심판 불능 판의 폴백). 검증은 봇이 다시 한다
     * (Bridge.raidResolved — 등록 값 밖은 버리고, 첫 보고가 정본이다).
     */
    private boolean raidResolve(CommandSender sender, String[] args) {
        if (sender instanceof Player p && !p.isOp()) {
            sender.sendMessage("§c레이드해소는 관리자의 손이다.");
            return true;
        }
        if (args.length < 4 || !(args[3].equals("격퇴") || args[3].equals("패배"))) {
            sender.sendMessage("§7쓰임: /혼천 레이드해소 <막id> <박key> <격퇴|패배>");
            return true;
        }
        WorldBridge.emit("raid_resolved", Map.of("act", args[1], "beat", args[2], "result", args[3]));
        sender.sendMessage("§6레이드 결말을 다리에 실었다 — " + args[1] + "." + args[2] + " = " + args[3]);
        return true;
    }

    /**
     * ★B-194 — 기억의 마을 「그날 밤」을 서장 월드에 찍고 걸어 본다 (관리자 검수).
     * 도면이 정본(config/stages/geunal_bam.stage.yml — 렌더 검수를 통과한 것)이고,
     * 로더는 도면 그대로 찍는다 (멱등 — 다시 부르면 손댄 것이 되돌아간다).
     */
    private boolean seojangStage(CommandSender sender, String[] args) {
        // ★공간덤프 — /혼천 서장무대 덤프 : 실제 블록·엔티티를 파일로 (★콘솔 가능 — AI 원격 검수.
        //   플레이어 검사보다 먼저 — 콘솔함이 이 문으로 들어온다)
        if (args.length >= 2 && args[1].equals("덤프")) {
            return stageDump(sender);
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("몸이 있어야 걷는다.");
            return true;
        }
        if (!p.isOp()) {
            p.sendMessage("§c무대는 관리자가 세운다.");
            return true;
        }
        // ★생명 체험 — /혼천 서장무대 체험 [발단] (기본: 습격). 판정·시트 무접촉 시험이다
        if (args.length >= 2 && args[1].equals("체험")) {
            String incident = args.length >= 3 ? args[2] : "습격";
            plugin.stagePlay().begin(p, incident, null);
            return true;
        }
        Voyage voyage = plugin.antechamber().voyage();
        World w = voyage.sea();
        if (w == null) {
            p.sendMessage("§c서장 월드를 못 열었다 — 콘솔 로그를 보라.");
            return true;
        }
        try {
            StageLoader.Stage s = StageLoader.load(plugin.configPath(), "geunal_bam");
            int oy = StageLoader.originY(s, w, voyage);
            StageLoader.build(s, w, oy);
            p.teleport(StageLoader.spot(s, w, oy, "깨어남"));
            p.sendMessage("§6무대가 섰다 — " + s.name() + " (" + s.width() + "×" + s.depth()
                    + "×" + s.layers().size() + "층 · 자리 " + s.spots().size() + ")");
            p.sendMessage("§7깨어나는 자리에 내렸다. 이부자리 뒤 · 담장 틈 · 식구 머리맡을 걸어 보라.");
        } catch (Exception e) {
            p.sendMessage("§c무대 조성 실패: " + e.getMessage());
        }
        return true;
    }

    /**
     * ★B-194 공간덤프 — 무대 상자의 실제 블록·엔티티를 JSON 으로 뜬다 (콘솔 가능).
     * 「사진 찍고 좌표 찍는」 왕복의 반대편 눈: AI 가 접속 없이 실세계를 본다.
     * 대조는 tools/stage_dump_diff.py — 도면과 실세계가 어긋난 곳을 센다.
     */
    private boolean stageDump(CommandSender sender) {
        if (sender instanceof Player p && !p.isOp()) {
            p.sendMessage("§c덤프는 관리자의 손이다.");
            return true;
        }
        try {
            Voyage voyage = plugin.antechamber().voyage();
            World w = voyage.sea();
            if (w == null) {
                sender.sendMessage("서장 월드를 못 열었다");
                return true;
            }
            StageLoader.Stage s = StageLoader.load(plugin.configPath(), "geunal_bam");
            int oy = StageLoader.originY(s, w, voyage);
            StringBuilder out = new StringBuilder();
            out.append("{\"origin\":[").append(s.ox()).append(',').append(oy).append(',')
                    .append(s.oz()).append("],\"size\":[").append(s.width()).append(',')
                    .append(s.layers().size()).append(',').append(s.depth()).append("],\"blocks\":[");
            boolean first = true;
            for (int y = -1; y <= s.layers().size(); y++) {
                for (int r = 0; r < s.depth(); r++) {
                    for (int c = 0; c < s.width(); c++) {
                        var b = w.getBlockAt(s.ox() + c, oy + y, s.oz() + r);
                        if (b.getType() == org.bukkit.Material.AIR) {
                            continue;
                        }
                        if (!first) {
                            out.append(',');
                        }
                        first = false;
                        out.append("[").append(c).append(',').append(y).append(',').append(r)
                                .append(",\"").append(b.getBlockData().getAsString()).append("\"]");
                    }
                }
            }
            out.append("],\"entities\":[");
            first = true;
            for (org.bukkit.entity.Entity e : w.getEntities()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append("{\"type\":\"").append(e.getType()).append("\",\"x\":")
                        .append(String.format("%.1f", e.getLocation().getX())).append(",\"y\":")
                        .append(String.format("%.1f", e.getLocation().getY())).append(",\"z\":")
                        .append(String.format("%.1f", e.getLocation().getZ()))
                        .append(",\"name\":\"").append(e.getCustomName() == null ? "" :
                                org.bukkit.ChatColor.stripColor(e.getCustomName()))
                        .append("\",\"visible_default\":").append(e.isVisibleByDefault()).append('}');
            }
            out.append("]}");
            java.nio.file.Path f = plugin.getDataFolder().toPath().resolve("stage_dump.json");
            java.nio.file.Files.writeString(f, out.toString());
            sender.sendMessage("덤프 완료 — " + f);
            plugin.getLogger().info("[서장무대] 공간덤프 — " + f);
        } catch (Exception e) {
            sender.sendMessage("덤프 실패: " + e.getMessage());
        }
        return true;
    }

    /** ★B-190 ① — 신교의 시험 돌을 세운다 (관리자) · 정본: factions.yml cheonma.플레이어_루트_기계.시험 */
    private boolean trialStone(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("몸이 있어야 세운다.");
            return true;
        }
        if (!p.isOp()) {
            p.sendMessage("§c시험의 돌은 관리자가 세운다.");
            return true;
        }
        plugin.dojang().trialStone(p);
        return true;
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
                case "경지" -> realm(sender, args);
                case "병기" -> giveWeapon(sender, args);   // 관리자 지급 — 검증용
                case "병기상" -> weaponShop(sender);        // 장쇠 좌판
                case "병기전시" -> weaponStand(sender);      // 든 병기를 땅에 크게 세운다 / 빈손이면 회수
                case "명명" -> enshrine(sender, args);       // 애병을 문파의 명병으로 (매화검은 매화검처럼 생긴다)
                case "재련" -> reforgeWeapon(sender);        // 야철수 — 품 1단 상승
                case "각인" -> inscribeWeapon(sender, args); // 각인 — 슬롯 안에서만
                case "격돌" -> recordClash(sender);          // 애병 카운터 (검증용)
                case "사냥터" -> census(sender);              // 구역 개체군
                case "비무" -> spar(sender, args);            // 죽지 않는 싸움
                case "소환" -> summon(sender, args);          // 영물은 등록제 (관리자)
                case "지도" -> showMap(sender);               // 세계 지도 — 등록 좌표·여정 일수
                case "시드검사" -> seedCheck(sender, args);    // 지형 적합성 점수 (관리자)
                case "세계조성" -> buildWorld(sender);         // 등록 지역을 제 좌표에 (관리자)   // 무공 검증용 — MVT엔 캐릭터 시트가 없다
                case "지역조성" -> buildRegion(sender, args);   // 원거리 등록지 하나를 짓는다 (관리자·콘솔 가능)
                case "지형조성" -> forgeLand(sender, args);     // ★ 땅만 빚는다 — 집은 안 짓는다 (지도 → 지형 → 건축)
                case "지역검수" -> auditRegion(sender, args);   // 지역 자동 검산 — 도달성·계약·허공·광원·수묵
                case "원형대조" -> compareArchetypes(sender, args);   // ★ 집들이 서로 구별되는가 (오늘의 병) · `시험` = 눈을 시험한다
                case "레이드해소" -> raidResolve(sender, args);   // ★B-190 — 세계 사건(레이드) 결말 선고 (관리자)
                case "시험돌" -> trialStone(sender);              // ★B-190 ① — 신교의 시험 돌 (관리자 세움)
                case "서장무대" -> seojangStage(sender, args);    // ★B-194 — 조성·방문 · 체험 (관리자)
                case "땅갈아엎기" -> forgetLand(sender, args);   // 땅을 다시 빚겠다는 **명시적 선언**
                case "산세시험" -> sanseTest(sender, args);      // ★ 버리는 FLAT 월드에 광역 산세를 세워 도보로 본다 (프로덕션 무접촉)
                case "식생시험" -> floraTest(sender, args);      // ★ 산세시험 월드에 구역별 식생을 심는다 (매화림→벚꽃 등 · 프로덕션 무접촉)
                case "도보길" -> trailBuild(sender, args);        // ★ 완성된 험산 위에 걸을 수 있는 계단길(천계단·잔도)을 짓는다 — 산기슭→정상 (프로덕션 무접촉)
                case "캠퍼스시험" -> campusTest(sender, args);    // ★ 산세시험 월드에 마스터플랜 캠퍼스 패드·계단을 앉힌다 — B-146 처방 시험 (프로덕션 무접촉)
                case "산군시험" -> spireTest(sender, args);       // ★ 산세 위에 산군(배후봉 증고+침봉 켜 3)을 얹는다 — 실측표 §4 (프로덕션 무접촉)
                case "지도검수" -> auditMap(sender);         // ★ 등록된 곳이 그 지형답게 서 있는가 (안 지은 곳도 말한다)
                case "환경검수" -> auditTerrain(sender, args);   // 조성물과 자연의 이음매 — 공동·수역·경계·연결성
                case "지하정리" -> sweepUnderground(sender, args);   // ★ 묻힌 나무를 걷는다 — 지면 밑 공기·잎·통나무 채움 (관리자·콘솔 가능)
                case "경계다듬기" -> featherEdge(sender, args);   // ★ 끊긴 경계 띠를 자연으로 다시 잇는다 — 부지 안은 안 건드린다 (B-127)
                case "운기" -> meditate(sender);
                case "태세" -> stance(sender, args);         // 맞는 쪽의 선택 — 회피·막기·흘리기 (기본은 자동)
                case "조성" -> buildTown(sender, args);
                case "검수" -> auditTown(sender);   // 규칙 린트 — 콘솔 가능 (앵커 기준)
                case "앵커검사" -> anchorCheck(sender);   // ★ 앵커에 사람이 설 수 있는가 (우물을 잡는 눈)
                case "앵커재측" -> anchorRemeasure(sender);   // ★ 못 서는 앵커를 설 수 있는 자리로 다시 박는다
                case "조감" -> renderTown(sender, args);   // 조감도 PNG — 콘솔 가능 (인자 = 지역id)
                case "출행" -> travel(sender, args);          // 지역으로 간다 (조성된 곳만)
                case "입도" -> antechamber(sender, args);    // 나루로 — 몸을 다시 익힌다 (인자 재조성 = 다시 세운다)
                case "도강" -> crossRiver(sender);           // 강을 건넌다 (= 부두의 종)
                case "연무장" -> dojangEnter(sender);        // 시험 월드로 (스킬·몹·허수아비)
                case "귀환" -> dojangLeave(sender);          // 세계로 돌아온다
                case "금고" -> dojangVault(sender);          // ★ 연무장이 누구의 무엇을 맡고 있는가 (콘솔 가능)
                case "금고시험" -> dojangVaultTest(sender);   // ★ 진짜 ItemStack 이 왕복을 견디는가 (B-011)
                case "짐지문" -> inventoryFingerprint(sender, args);   // ★ 재기동 전후로 같은 짐인가 (B-011 끝단)
                case "시험" -> dojangTune(sender, args);     // 경지·내력·무공 조정
                case "허수아비" -> dojangDummy(sender, args); // 맞아 주는 몸 (피해 계측)
                case "계측" -> metrics(sender, args);        // MSPT·티커별 예산 (performance.yml 대조)
                case "수련" -> training(sender, args);       // 하루 5구간을 무엇에 쓸 것인가 (성장 축)
                case "시트" -> sheetInfo(sender);            // 나는 누구인가 — 강호의 장부가 답한다
                case "접속" -> link(sender);                 // 초대 링크 + 안내 (★ 낡은 청을 초기화한다)
                // ★★ 접합의 결속 — 사람이 치지 않는다. 화면의 [잇는다]/[아니다] 클릭이 대신 친다.
                //   이 두 줄이 **도용을 막는 자리**다 (linkDecision 이 몸을 대조한다)
                case "수락" -> linkDecide(sender, args, true);
                case "거절" -> linkDecide(sender, args, false);
                // ★★ 서장 — 사람이 치지 않는다. **책 안의 글자를 누르면** 이것이 대신 쳐진다
                //   (/혼천 서장 <토큰> <n> — SeojangBook 의 ClickEvent.runCommand)
                case "서장" -> seojangPick(sender, args);
                case "판정보기" -> judgeEye(sender);         // 【디버그】 판정의 눈 — 히트박스·2d6·피해의 층
                case "타격보기" -> hitEye(sender);           // 【디버그】 타격의 눈 — 시간 구조·히트스톱·넉백·흔들림
                case "모션진단" -> motionDiag(sender, args);   // 3D 층이 실제로 떴는가 (팩 유무 포함)
                case "획시험" -> strokeTest(sender);          // 획 15종 + 대조군을 한 줄로 — 보라 큐브를 이름으로 지목한다
                case "사다리" -> ladder(sender);              // ★ 여섯 격을 나란히 — 한눈에 사다리를 본다 (화려함의 눈)
                case "획위치" -> strokeOrigin(sender, args);   // ★ 획이 서는 자리 — 인게임에서 밀고 당긴다 (즉시 그어 본다)
                case "스윙" -> swing(sender, args);           // ★★ 스윙의 크기·각도·활·전진 — 밀면 즉시 한 획 (찌르기 → 베기)
                case "검기" -> kigi(sender, args);           // ★★ 검기의 각도·크기 — 재기동 없이 즉석 조율
                case "모션" -> motion(sender, args);         // ★★ 재적재/상태 — 서버를 안 내리고 등록부를 다시 읽는다
                case "대행" -> proxy(sender, args);         // ★★ 콘솔/RCON 이 **플레이어의 손을 빌린다** — 하네스 전용
                case "문장" -> crests(sender);
                case "초기화" -> wipe(sender, args);   // ★ 되돌린다 — 시험용 (두 번 쳐야 지운다)
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

    /**
     * /혼천 협공 &lt;인원&gt; — 협공 보정.
     *
     * <p>이 명령은 오랫동안 <b>"캡 +3"</b> 이라고 화면에 찍었다. config 는 <b>2</b> 다.
     * 설계 감사(gap_audit)가 잡았다 — 문구가 손으로 적혀 있었고, 아무도 등록부와 대조하지 않았다.
     * <b>화면에 찍는 숫자도 등록부에서 나와야 한다.</b> 안 그러면 화면이 세계에 대해 거짓말을 한다.
     */
    private boolean coop(CommandSender sender, String[] args) {
        int attackers = Integer.parseInt(args[1]);
        int cap = plugin.skillEngine().gangUpCap();     // combat.yml attack.gang_up.max
        int slots = plugin.skillEngine().engageSlots(); // 한 사람을 동시에 벨 수 있는 자리
        sender.sendMessage(ChatColor.GOLD + "협공 " + attackers + "인 → 보정 +"
                + plugin.party().coopAttackBonus(attackers)
                + ChatColor.GRAY + " (캡 +" + cap + " · 동시 교전 " + slots + "인 — 그 위는 대기다)");
        return true;
    }

    /** /혼천 경지 <경지> [내공] — MVT엔 캐릭터 시트가 없다. 무공 검증용으로 몸을 세운다 */
    private boolean realm(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(ChatColor.GRAY + "/혼천 경지 <삼류|이류|일류|절정|초절정|화경> [내공]");
            return true;
        }
        plugin.skills().setRealm(player, args[1],
                args.length > 2 ? Double.parseDouble(args[2]) : 1.0);
        return true;
    }

    /** /혼천 병기 <계열> <등급> [속성...] — 관리자 지급 (검증용) */
    private boolean giveWeapon(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            // ★ 콘솔 경로 (2026-07-23 · 계율 "플레이어 전용 명령은 RCON execute-as 로 안 된다 —
            //   콘솔 경로를 설계하라"): /혼천 병기 <플레이어> <계열> <등급> — 촬영 봇 지급용
            if (args.length >= 4) {
                Player target = plugin.getServer().getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("[혼천] 병기 지급 실패 — 접속 중이 아니다: " + args[1]);
                    return true;
                }
                try {
                    target.getInventory().addItem(Weapons.make(
                            Weapons.Series.of(args[2]), Weapons.Grade.of(args[3]),
                            Weapons.Bias.균, Weapons.Craft.보통, java.util.List.of()));
                    sender.sendMessage("[혼천] 병기 지급 — " + args[1] + " ← " + args[3] + " " + args[2]);
                } catch (IllegalArgumentException rejected) {
                    sender.sendMessage("[혼천] 병기 지급 실패 — " + rejected.getMessage());
                }
            } else {
                sender.sendMessage("[혼천] 콘솔 용법: /혼천 병기 <플레이어> <계열> <등급>");
            }
            return true;
        }
        if (!player.isOp()) {
            player.sendMessage(ChatColor.RED + "병기 지급은 관리자의 몫이다.");
            return true;
        }
        if (args.length < 3) {
            player.sendMessage(ChatColor.GRAY
                    + "/혼천 병기 <검|도|창|권갑|단검|부|겸|봉|구|활> <범철|정련|보병|신병|마병> [속성…]");
            return true;
        }
        int i = 3;
        Weapons.Bias bias = Weapons.Bias.균;
        Weapons.Craft craft = Weapons.Craft.보통;
        try {   // 위치 인자 — 파싱되면 소비, 아니면 속성으로 넘긴다 (하위 호환)
            if (i < args.length) {
                bias = Weapons.Bias.of(args[i]);
                i++;
            }
        } catch (IllegalArgumentException notABias) {
            // 속성이다 — 그대로 둔다
        }
        try {
            if (i < args.length) {
                craft = Weapons.Craft.of(args[i]);
                i++;
            }
        } catch (IllegalArgumentException notACraft) {
            // 속성이다
        }
        java.util.List<String> properties = i < args.length
                ? java.util.List.of(java.util.Arrays.copyOfRange(args, i, args.length))
                : java.util.List.of();
        try {
            player.getInventory().addItem(Weapons.make(
                    Weapons.Series.of(args[1]), Weapons.Grade.of(args[2]), bias, craft, properties));
        } catch (IllegalArgumentException rejected) {   // 명공 범철·슬롯 초과 등
            player.sendMessage(ChatColor.RED + rejected.getMessage());
            return true;
        }
        player.sendMessage(ChatColor.GOLD + "병기를 손에 쥐었다 — " + args[2] + " " + args[1]
                + " · " + bias.name() + " · " + craft.name());
        return true;
    }

    /**
     * /혼천 지도 — 세계 지도 (등록 좌표·거리·여정 일수). 청하현이 원점이다.
     *
     * <p><b>★ B-151 — {@code hidden} 의 독자.</b> 등록부(world_map.yml §5.8)가 {@code hidden: true} /
     * {@code player_map: false} 라 적은 곳은 <b>플레이어 지도에 없다</b> — 마교 전초가 4일 거리라는
     * 사실 자체가 스포일러다 ("지도에 없는 것이 지도에서 가장 가깝다"가 그 좌표의 설계 의도다).
     * 관리자(op)·콘솔에는 「숨김」 표기와 함께 보인다 — 검수의 눈은 남긴다.
     * ★ 여기서 막는 것은 <b>표시</b>뿐이다 — 해금(access)은 다른 축이다 (세 축 분리).
     */
    private boolean showMap(CommandSender sender) {
        WorldMap map = plugin.worldMap();
        if (map == null) {
            sender.sendMessage(ChatColor.RED + "world_map.yml 이 없다.");
            return true;
        }
        boolean adminEye = !(sender instanceof Player viewer) || viewer.isOp();
        sender.sendMessage(ChatColor.GOLD + "── 세계 지도 (청하현 = 원점, 1블록 = 1m) ──");
        for (WorldMap.Place p : map.all()) {
            if (p.hidden() && !adminEye) {
                continue;   // ★ B-151 표시 축 — 숨긴 곳은 플레이어 지도에 렌더되지 않는다
            }
            int days = map.travelDays(p);
            // ★★ B-151 해금 축 — **출행과 같은 판정기(AccessJudge).** 지도는 표시 축이므로 장소를 **지우지 않고**
            //   접근이 막힌 곳에 주석을 더한다 (좌표는 알아도 못 가는 곳이 있다 — 새외가 그렇다).
            //   관문형(공개 아님·미지·미등록)일 때만 표기: 비op 는 「잠김」, op·콘솔은 검수용으로 토큰을 본다.
            AccessJudge.Access ac = AccessJudge.classify(p.access());
            boolean gated = ac == null || !ac.open;
            String lock = !gated ? "" : (adminEye
                    ? " · access:" + (p.access() == null ? "미등록" : p.access())
                    : " · 잠김");
            sender.sendMessage(String.format("%s%-14s %s(%+d, %+d) %s· %s · %s%s%s",
                    ChatColor.WHITE, p.name(), ChatColor.GRAY, map.worldX(p), map.worldZ(p),
                    ChatColor.DARK_GRAY, p.terrain(),
                    days <= 0 ? "여기" : days + "일 여정",
                    p.hidden() ? " · 숨김(hidden)" : "", lock));
        }
        return true;
    }

    /** /혼천 시드검사 <시드...> — 등록 좌표의 지형이 시드와 맞는가 (관리자) */
    private boolean seedCheck(CommandSender sender, String[] args) {
        if (sender instanceof Player p && !p.isOp()) {
            return true;
        }
        WorldMap map = plugin.worldMap();
        if (map == null || args.length < 2) {
            sender.sendMessage(ChatColor.GRAY + "/혼천 시드검사 <시드> [시드...]");
            return true;
        }
        java.util.List<Long> seeds = new java.util.ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            seeds.add(Long.parseLong(args[i]));
        }
        sender.sendMessage(ChatColor.GOLD + "시드 검사 " + seeds.size() + "개 — 지형을 생성하며 훑는다 "
                + ChatColor.GRAY + "(틱을 나눠 먹는다 · 서버는 계속 돈다)");
        new SeedProbe(plugin, map, seeds, sender).runTaskTimer(plugin, 1L, 1L);
        return true;
    }

    /**
     * 시드 검사 — <b>틱을 나눠 먹는 채점기</b>.
     *
     * <p>구판은 후보 수백 곳을 한 틱 안에서 훑었다. 후보마다 지형을 표본하며 청크를 동기 생성하니,
     * 시드 5개에 4,380청크였고 워치독이 "메인 스레드가 60초째 응답 없음"이라며 서버를 죽였다.
     * 지형을 보려면 지형을 만들어야 한다 — 그 값은 못 깎는다. 깎을 수 있는 건 <b>한 틱에 얼마나 하냐</b>다.
     *
     * <p>그래서 후보를 하나씩 꺼내 쓰고, 틱마다 예산(20ms)이 다하면 손을 뗀다. 서버는 계속 돈다.
     */
    private static final class SeedProbe extends org.bukkit.scheduler.BukkitRunnable {
        private static final long TICK_BUDGET_NANOS = 20_000_000L;   // 20ms — 한 틱(50ms)의 절반 이하

        private final HoncheonMvt plugin;
        private final WorldMap map;
        private final java.util.List<Long> seeds;
        private final CommandSender sender;

        private int seedIndex = -1;
        private World probe;
        private java.util.List<WorldMap.Place> places;
        private int placeIndex;
        private java.util.List<int[]> candidates;
        private int candIndex;
        private WorldMap.Fit best;
        private int bestShift;
        private java.util.List<String> lines = new java.util.ArrayList<>();
        private int sum;
        private int count;

        SeedProbe(HoncheonMvt plugin, WorldMap map, java.util.List<Long> seeds, CommandSender sender) {
            this.plugin = plugin;
            this.map = map;
            this.seeds = seeds;
            this.sender = sender;
        }

        @Override
        public void run() {
            long t0 = System.nanoTime();
            while (System.nanoTime() - t0 < TICK_BUDGET_NANOS) {
                if (!step()) {
                    cancel();
                    return;
                }
            }
        }

        /** 한 걸음 = 후보 한 곳 채점. false = 검사 끝 */
        private boolean step() {
            if (probe == null) {                      // 다음 시드로
                seedIndex++;
                if (seedIndex >= seeds.size()) {
                    return false;
                }
                probe = map.createProbeWorld(seeds.get(seedIndex));
                places = map.seedProbeTargets(false);
                placeIndex = 0;
                candidates = null;
                lines = new java.util.ArrayList<>();
                sum = 0;
                count = 0;
                return true;
            }
            if (candidates == null) {                 // 다음 지역으로
                if (placeIndex >= places.size()) {
                    finishSeed();
                    return true;
                }
                candidates = map.probeCandidates(places.get(placeIndex));
                candIndex = 0;
                best = null;
                bestShift = 0;
                return true;
            }
            WorldMap.Place place = places.get(placeIndex);
            if (candIndex >= candidates.size()) {     // 후보 소진 — 최고점 자리에 앉힌다
                recordPlace(place, best, bestShift);
                return true;
            }
            int[] c = candidates.get(candIndex++);
            WorldMap.Fit f = map.fitAt(probe, place, c[0], c[1]);
            if (f.pass()) {                           // 합격 — 더 볼 것 없다
                recordPlace(place, f, c[2]);
                return true;
            }
            if (best == null || f.score() > best.score()) {
                best = f;
                bestShift = c[2];
            }
            return true;
        }

        private void recordPlace(WorldMap.Place place, WorldMap.Fit fit, int shift) {
            sum += fit.score();
            count++;
            lines.add(String.format("  %-14s %3d점 %s%s", place.name(), fit.score(),
                    fit.pass() ? "적합" : "부적합 — " + fit.verdict(),
                    shift == 0 ? "" : " (인근 " + shift + "칸 이동)"));
            placeIndex++;
            candidates = null;
        }

        private void finishSeed() {
            long seed = seeds.get(seedIndex);
            int score = count == 0 ? 0 : sum / count;
            sender.sendMessage(ChatColor.GOLD + "시드 " + seed + " — 점수 " + score);
            lines.forEach(line -> sender.sendMessage(ChatColor.GRAY + line));
            // 콘솔(RCON)로 부르면 명령이 끝난 뒤엔 sender 로 보낸 말이 사라진다 — 검사는 몇 분이 걸린다.
            // 결과는 로그에도 남긴다: 루프의 눈이 결과를 못 보면 검사한 적 없는 것과 같다.
            plugin.getLogger().info("[시드검사] 시드 " + seed + " — 점수 " + score);
            lines.forEach(line -> plugin.getLogger().info("[시드검사] " + line));
            map.disposeProbeWorld(probe);
            probe = null;
        }

        @Override
        public synchronized void cancel() {
            map.disposeProbeWorld(probe);   // 중도 취소·플러그인 종료에도 임시 월드는 남기지 않는다
            probe = null;
            super.cancel();
        }
    }

    /**
     * /혼천 지역조성 &lt;id&gt; — <b>등록부의 좌표에 실제로 집을 세운다</b> (관리자·콘솔 가능).
     *
     * <p>세계 지도에 33곳이 좌표를 갖고 있는데 선 것은 청하현과 산길 도적뿐이었다. 나머지는 build: later —
     * 등록만 되고 서지 않은 곳은 여행의 목적지가 아니라 문서의 줄이다. 이 명령이 그 줄을 집으로 바꾼다.
     *
     * <p>부지는 <b>지도가 고른다</b>: 등록 좌표에서 지형 적합성을 재고, 안 맞으면 링(64~1024칸)을 돌며
     * 첫 합격지를 잡는다 (청하현과 같은 규칙 — 좌표는 흔들려도 여정 일수는 안 흔들린다).
     */
    private boolean buildRegion(CommandSender sender, String[] args) {
        return region(sender, args, false);
    }

    /**
     * /혼천 지형조성 &lt;id&gt; — <b>땅만 빚는다. 집은 안 짓는다.</b>
     *
     * <p><b>왜 이 문이 따로 있는가.</b> 사용자의 순서는 <b>지도 → 지형 → 건축</b>이고, 2계층 계약은
     * <b>"땅에 맞게 건물이 올라가는 것이지, 건축에 맞게 지형이 생기는 게 아니다"</b> 이다.
     * 그런데 {@code /혼천 지역조성} 은 {@link RemoteBuilder#unbuildableReasons} 로 <b>먼저 거절하고 돌아선다</b> —
     * 그래서 <b>집이 미결이면 땅도 못 선다</b>. 그것은 계층이 거꾸로 선 것이다:
     * 땅은 {@code build_radius} 를 <b>쓰지도 않는다</b> (지형 반경은 §1-b {@code land.forge_radius} 하나 —
     * <b>"땅은 세력을 모른다"</b>). 강남 상로가 바로 그 인질이다: 물길(rivers.yml)도 지형(수향)도
     * 등록부에 <b>이미 적혀 있는데</b>, {@code commercial_class} 가 미결이라 <b>땅조차 못 빚는다</b>.
     *
     * <p>그러므로 이 문은 <b>건축의 미결을 지형의 발목에서 푼다</b>. 집이 왜 못 서는지는 <b>그대로 말한다</b> —
     * 조용히 넘어가지 않는다. 땅이 선 뒤 등급이 정해지면 {@code /혼천 지역조성} 이 <b>그 땅 위에</b> 집을 올린다
     * ({@code Terraform} 이 원장을 보고 <b>땅을 한 블록도 안 건드린다</b>).
     */
    private boolean forgeLand(CommandSender sender, String[] args) {
        return region(sender, args, true);
    }

    /**
     * @param terrainOnly 참이면 <b>땅까지만</b> — 건축 게이트({@code unbuildableReasons})를 <b>묻지 않는다</b>
     */
    private boolean region(CommandSender sender, String[] args, boolean terrainOnly) {
        if (sender instanceof Player p && !p.isOp()) {
            return true;
        }
        String cmd = terrainOnly ? "지형조성" : "지역조성";
        if (forgeWorldBarred(sender, cmd)) {
            return true;   // ★ 원거리 조성도 세계는 발밑에서 얻는다 — 나루에서 치면 나루에 선다 (B-126)
        }
        WorldMap map = plugin.worldMap();
        if (map == null || args.length < 2) {
            sender.sendMessage(ChatColor.GRAY + "/혼천 " + cmd + " <지역id>  (예: nokrim_sochae)");
            return true;
        }
        WorldMap.Place place = map.place(args[1]);
        if (place == null) {
            sender.sendMessage(ChatColor.RED + "지도에 없는 지역: " + args[1]);
            return true;
        }
        // ══════════ ① 지형 게이트 — **땅의 일만 묻는다** (docs/design/gate_and_watertown.md) ══════════
        //
        // ★★ 여기가 뒤집힌 자리다. 예전엔 **건축 게이트가 먼저** 서서, 집의 등급이 미결이면
        //    `return` 하고 돌아섰다 — **집이 미결이면 땅조차 안 빚어졌다.**
        //    사용자: *"건축의 미결은 건축만 막을 수 있다. 건축의 미결이 땅을 막아서는 안 된다."*
        TerrainGate.Verdict gate = TerrainGate.judge(place, map.forgeRadius());
        // ★★ 관문의 판정은 **언제나 로그에 남는다.** 조용히 막으면 그것은 관문이 아니라 함정이다
        //   (RCON 의 sender 는 명령이 반환되는 순간 죽는다 — 그리로만 말하면 아무도 못 듣는다).
        Announce.say(plugin, sender, ChatColor.GRAY + "[지형 관문] " + place.id()
                + " — terrain_state: " + gate.state()
                + (gate.reason() == null ? "" : " · pending_reason: " + gate.reason())
                + " · forge_mode: " + gate.mode()
                + " · 판 v" + TerrainGate.forgeVersion()
                + (TerrainGate.approved() ? " (승인됨)" : " (★ 미승인)"));
        for (String note : gate.notes()) {
            Announce.say(plugin, sender, ChatColor.GRAY + "[지형 관문] " + note);
        }
        if ("pending".equals(gate.state()) && gate.mode() == TerrainGate.Mode.preview) {
            // ★ preview — **월드에 한 블록도 안 쓴다.** 그리고 **그렇다고 소리내어 말한다**
            Announce.warn(plugin, sender, place.name() + " — 땅을 빚지 않았다 (forge_mode: preview · "
                    + "pending_reason: " + gate.reason() + "). 예정: 프로파일 "
                    + TerrainForge.requestedProfile(place)
                    + " · 표층 " + TerrainForge.surface(place)
                    + " · 반경 " + map.forgeRadius()
                    + " · 좌표 (" + place.x() + ", " + place.z() + ")");
            return true;
        }
        // ══════════ ④ 건축 게이트 — **집만 막는다** (땅은 이미 제 길을 간다) ══════════
        java.util.List<String> blockers = RemoteBuilder.unbuildableReasons(place);
        boolean architectureBlocked = !blockers.isEmpty();
        if (architectureBlocked) {
            // ★ 실패가 아니다: terrain_state: forged · architecture_state: blocked 는 **정상 상태**다
            Announce.say(plugin, sender, ChatColor.YELLOW + place.name()
                    + " — 땅은 빚는다. 집은 아직 못 선다 (architecture_state: blocked): "
                    + String.join(" · ", blockers));
        }
        // ★ 집이 못 서면 **땅만** 짓는다 (⑥ 땅만 남기고 사유 출력). 땅은 이미 제 길을 갔다
        boolean landOnly = terrainOnly || architectureBlocked;
        World world = sender instanceof Player p2 ? p2.getWorld() : org.bukkit.Bukkit.getWorlds().get(0);
        // 부지 탐색이 먼저 서버를 멈춘다 — 후보마다 지형을 표본하며 **청크를 동기 생성**하기 때문이다
        // (20km 밖의 땅은 아직 존재하지 않는다). 시드검사와 같은 병이고 같은 처방이다: 틱을 나눠 먹는다.
        // ★ 기억된 자리가 있으면 **다시 찾지 않는다.**
        //   부지 탐색은 지형을 표본한다 — 그런데 **우리가 지난번에 만든 것이 그 지형이다.**
        //   강을 파면 물이 늘고, 산을 세우면 기복이 는다. 그러면 탐색이 **다른 답**을 낸다:
        //   장강수로채가 재조성 한 번에 128칸을 옮겨 앉았고, 하지(河誌)는 옛 자리를 가리켰다.
        //   **우리가 만든 것이 다음 탐색을 흔든다.** 그러므로 첫 답을 원장에 굽고, 그 뒤로는 그것을 쓴다.
        int[] at = plugin.regionSite(place.id());
        if (at != null) {
            Announce.say(plugin, sender, ChatColor.GRAY + place.name()
                    + " 부지 (원장 · 다시 찾지 않는다) — (" + at[0] + ", " + at[2] + ") · 지면 y" + at[1]);
            preloadThenBuild(sender, world, place,
                    new WorldMap.Site(at[0], at[2], at[1], 0,
                            map.fit(world, at[0], at[2], place.terrain(), place.biomes())), landOnly);
            return true;
        }
        Announce.say(plugin, sender, ChatColor.GRAY + place.name() + " 부지를 찾는다 — 지형을 표본한다 "
                + "(틱을 나눠 먹는다 · 서버는 계속 돈다)");
        // ★ 얼마나 걸리는지 **미리** 말한다. 화산파는 729초 걸렸고, 그 침묵이 곧 "죽었다"는 오해였다
        Announce.say(plugin, sender, ChatColor.DARK_GRAY
                + "※ 봉우리를 세우는 땅은 10분을 넘길 수 있다 (화산파 실측 729초). "
                + "진행은 [조성·진행] 으로 로그에 남는다 — 조용하면 그때가 사고다");
        new SiteProbe(plugin, map, place, world,
                site -> preloadThenBuild(sender, world, place, site, landOnly)).runTaskTimer(plugin, 1L, 1L);
        return true;
    }

    /** 부지가 정해진 뒤 — 땅을 비동기로 싣고, 실리면 짓는다 */
    private void preloadThenBuild(CommandSender sender, World world, WorldMap.Place place,
                                  WorldMap.Site site, boolean terrainOnly) {
        Announce.say(plugin, sender, ChatColor.GRAY + place.name() + " 부지 (" + site.x() + ", "
                + site.z() + ") · 지면 y" + site.groundY() + " · 지형 점수 " + site.fit().score()
                + (site.shift() == 0 ? "" : " (등록 좌표에서 " + site.shift() + "칸 이동)"));

        // 청크를 **비동기로** 실어 온다. 원거리 조성은 20km 밖의 땅을 처음 만드는 일이라,
        // 메인 스레드에서 동기 로드하면 서버가 15초 멈춘다(워치독이 스레드 덤프를 뜬다 — 시드검사와 같은 병).
        // 땅이 다 실린 뒤에 짓는다: 짓는 것 자체는 메인 스레드의 일이다 (블록 API 는 그것만 허락한다).
        java.util.List<java.util.concurrent.CompletableFuture<org.bukkit.Chunk>> loading =
                new java.util.ArrayList<>();
        // ★★ 부지보다 넓게 — 동기 청크 생성이 서버를 멈춘다. **그러나 세력을 묻지 않는다** —
        //   예전엔 `noklim ? 64 : 150` 이었다. **땅은 세력을 모른다** (world_map.yml §1-b land).
        int pre = plugin.worldMap().forgeRadius() + plugin.worldMap().probeMargin();
        for (int chunkX = (site.x() - pre) >> 4; chunkX <= (site.x() + pre) >> 4; chunkX++) {
            for (int chunkZ = (site.z() - pre) >> 4; chunkZ <= (site.z() + pre) >> 4; chunkZ++) {
                loading.add(world.getChunkAtAsync(chunkX, chunkZ, true));
            }
        }
        Announce.say(plugin, sender, ChatColor.GRAY + "땅을 싣는다 — 청크 " + loading.size() + "개 (비동기)");
        java.util.concurrent.CompletableFuture
                .allOf(loading.toArray(new java.util.concurrent.CompletableFuture[0]))
                .thenRun(() -> org.bukkit.Bukkit.getScheduler().runTask(plugin,
                        () -> finishRegion(sender, world, place, site, terrainOnly)))
                // ★ 청크 적재가 터지면 **여기서 끝난다** — 예전엔 그 뒤로 아무 일도, 아무 말도 없었다.
                //   조용한 실패는 없다: 로그에 남긴다 (RCON 은 이미 끊겼을 수 있으므로 로그가 정본이다).
                .exceptionally(err -> {
                    Announce.fail(plugin, sender, "★ 땅을 싣지 못했다 — " + place.id() + ": " + err);
                    return null;
                });
    }

    /**
     * 부지 탐색기 — <b>틱을 나눠 먹는다</b>.
     *
     * <p>지형 적합성은 후보 좌표마다 96×96 창을 표본한다. 그 땅이 아직 생성되지 않았으면(원거리는 늘 그렇다)
     * 표본이 곧 <b>청크 생성</b>이다. 한 틱에 다 하면 서버가 20초 멈춘다 — 워치독이 스레드 덤프를 뜬다.
     * 후보를 하나씩 꺼내 쓰고 틱 예산(20ms)이 다하면 손을 뗀다.
     */
    private static final class SiteProbe extends org.bukkit.scheduler.BukkitRunnable {
        private static final long TICK_BUDGET_NANOS = 20_000_000L;

        private final HoncheonMvt plugin;
        private final WorldMap map;
        private final WorldMap.Place place;
        private final World world;
        private final java.util.function.Consumer<WorldMap.Site> then;
        private final java.util.List<int[]> candidates;

        private int index;
        private WorldMap.Fit best;
        private int bestX;
        private int bestZ;
        private int bestShift;

        SiteProbe(HoncheonMvt plugin, WorldMap map, WorldMap.Place place, World world,
                  java.util.function.Consumer<WorldMap.Site> then) {
            this.plugin = plugin;
            this.map = map;
            this.place = place;
            this.world = world;
            this.then = then;
            this.candidates = map.probeCandidates(place);
        }

        /** 지금 후보의 땅을 싣는 중인가 — 실리기 전에는 표본하지 않는다 */
        private java.util.concurrent.CompletableFuture<Void> loading;

        /**
         * 틱 예산만으로는 부족하다 — <b>후보 하나의 표본이 이미 청크 36개를 만든다</b>(96×96 창).
         * 예산은 후보 <i>사이</i>에서만 물리므로, 후보 하나가 통째로 10초를 먹으면 서버는 그동안 멈춘다.
         * 그래서 표본하기 전에 그 후보의 땅을 <b>비동기로 싣는다</b>. 생성은 워커 스레드가 하고,
         * 메인 스레드는 이미 실린 블록을 읽기만 한다 — 그건 빠르다.
         */
        @Override
        public void run() {
            if (loading != null && !loading.isDone()) {
                return;   // 땅이 실리는 중 — 기다린다 (서버는 그동안 정상으로 돈다)
            }
            long t0 = System.nanoTime();
            while (System.nanoTime() - t0 < TICK_BUDGET_NANOS) {
                if (index >= candidates.size()) {          // 후보 소진 — 최고점 자리에 앉힌다
                    finish(bestX, bestZ, bestShift, best);
                    return;
                }
                int[] c = candidates.get(index);
                if (loading == null) {                     // 이 후보의 땅을 아직 안 실었다
                    loading = loadWindow(c[0], c[1]);
                    return;                                // 다음 틱에 다시 본다
                }
                index++;
                loading = null;
                WorldMap.Fit fit = map.fitAt(world, place, c[0], c[1]);
                if (fit.pass()) {
                    finish(c[0], c[1], c[2], fit);
                    return;
                }
                if (best == null || fit.score() > best.score()) {
                    best = fit;
                    bestX = c[0];
                    bestZ = c[1];
                    bestShift = c[2];
                }
            }
        }

        /** 후보의 표본 창(96×96 = 반경 48) 청크를 비동기로 싣는다 */
        private java.util.concurrent.CompletableFuture<Void> loadWindow(int x, int z) {
            java.util.List<java.util.concurrent.CompletableFuture<org.bukkit.Chunk>> futures =
                    new java.util.ArrayList<>();
            for (int chunkX = (x - 48) >> 4; chunkX <= (x + 48) >> 4; chunkX++) {
                for (int chunkZ = (z - 48) >> 4; chunkZ <= (z + 48) >> 4; chunkZ++) {
                    futures.add(world.getChunkAtAsync(chunkX, chunkZ, true));
                }
            }
            return java.util.concurrent.CompletableFuture
                    .allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0]));
        }

        private void finish(int x, int z, int shift, WorldMap.Fit fit) {
            cancel();
            if (fit == null) {
                plugin.getLogger().warning("[지역조성] " + place.id() + " — 후보가 없다");
                return;
            }
            then.accept(new WorldMap.Site(x, z, WorldMap.groundY(world, x, z), shift, fit));
        }
    }

    /** 땅이 실린 뒤 — 짓고 구역을 등록한다 (메인 스레드) */
    private void finishRegion(CommandSender sender, World world, WorldMap.Place place,
                              WorldMap.Site site, boolean terrainOnly) {
        // 지면은 **첫 조성이 잰 값**을 쓴다 (원장). 다시 재면 지난번에 세운 산을 지면으로 읽는다.
        Integer remembered = plugin.regionBase(place.id());
        int baseY = remembered == null ? site.groundY() : remembered;
        if (remembered == null) {
            plugin.setRegionBase(place.id(), site.x(), baseY, site.z());   // 자리를 굽는다 — 좌표까지
        }
        // ★★★ **땅의 반경 — 어디나 같은 값** (world_map.yml §1-b land.forge_radius).
        //
        //   예전엔 이 줄이 `noklim ? 24 : 110` 이었다 — **땅이 세력을 알고 있었다.**
        //   ★ 사용자 (2026-07-13): *"**땅은 세력을 모른다.** 산은 문파가 흥하든 망하든 그 자리에 그만큼 있다."*
        //     ⇒ 도적 24 의 예외를 **폐지**했다. 도적의 산이라고 작게 서지 않는다 — 산은 산이다.
        //
        //   ★ 110 인 이유: 산이 72켜로 서면 계단이 그만큼 길어야 오른다 (반경 44 는 17켜에서 멎는다).
        //     ★★ 그리고 **이미 이 반경으로 빚어진 땅이 있다** (TerrainLedger) — 그래서 값은 안 건드렸다.
        //   ★ 2계층: 집(build_radius ≤ 80)이 이 안에 있어야 한다 — map_lint.two_layers 가 매번 잰다.
        int forgeRadius = plugin.worldMap().forgeRadius();

        if (TickBudget.busy()) {
            Announce.warn(plugin, sender, "이미 조성이 돌고 있다 — 끝난 뒤에 다시 쳐라 "
                    + "(한 번에 하나만 돈다).");
            return;
        }
        Announce.say(plugin, sender, ChatColor.GRAY + "[조성] " + place.id() + " 시작 — 반경 "
                + forgeRadius + " · 프로파일 " + TerrainForge.requestedProfile(place)
                + " · 좌표 (" + site.x() + ", " + baseY + ", " + site.z() + ")");
        // ★ 청크를 **먼저 실어야 한다.** 안 그러면 드레인 도중 메인이 청크를 동기 생성한다 —
        //   땅을 빚느라 느린 게 아니라 **청크를 기다리느라** 느려진다 (화산파가 300초 안전핀에 걸린 진범).
        //   실어 둘 반경은 지형이 빚을 반경이 정한다 — 등록부가 아니라 **이 조성이 손댈 범위**가.
        int preloadRadius = forgeRadius + plugin.worldMap().preloadMargin();
        // 지형·굴·건축 셋을 **한 세션**으로 묶어 틱을 나눠 먹인다 (셋 다 world 를 받으므로 대역 하나로 덮인다).
        // 봉우리 하나가 반경 64 × 높이 72 다 — 청하현보다 큰 폭탄이었다.
        TickBudget.preload(plugin, world, site.x(), site.z(), preloadRadius).thenRun(() ->
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () ->
        TickBudget.build(plugin, "조성:" + place.id(), world,
                w -> {   // ★ 워커 스레드 — Bukkit API 직접 호출 금지 (대역 월드를 통하는 것만 안전하다)
                    // ★★ 3계층 헌법 (2026-07-15 개정, HANDOFF §2.4): **지도(건축 포함 정본) →
                    //   땅 → 건축.** 집행 안전핀은 존속한다 — 땅은 **한 번만** 선다 (`Terraform` 이 그 문):
                    //     땅이 없다 → 빚는다 (prepare → 강 → 요청 → 굴) → **원장에 적는다**
                    //     땅이 있다 → **측량만 한다. 블록을 하나도 안 건드린다.**
                    //   그러므로 `/혼천 지역조성` 을 두 번 치면 **두 번째는 건물만 다시 선다.**
                    //   (구판 표어 "땅에 맞게 건물이…"는 폐지 — 지도가 바뀌면 갈아엎어 다시 빚는다)
                    Terraform.Land land =
                            Terraform.land(w, place, site.x(), baseY, site.z(), forgeRadius);
                    // ★ 봉인 — **건축이 땅을 바꿨는가**를 기계가 증명한다 (자재가 아니라 **형상**을 잰다)
                    TerrainSeal.Probe probe = TerrainSeal.of(w);
                    TerrainSeal.Seal before = TerrainSeal.seal(probe, land.spec());
                    // ★ 지형조성이면 **집을 안 짓는다.** 땅의 집행은 건축을 못질하지 않는다 —
                    //   3계층에서 땅의 치수는 이미 지도(건축 footprint)가 정했고, 여기서는 집행만 가른다
                    java.util.List<Zone> built = terrainOnly
                            ? java.util.List.of()
                            : RemoteBuilder.build(w, place, land.spec(), land.cave());
                    TerrainSeal.Seal after = TerrainSeal.seal(probe, land.spec());
                    return new RegionResult(land, before, after, built);
                },
                r -> {   // ★ 메인 스레드 — 여기서 말한다
                    plugin.getLogger().info("[지형] " + (r.land().forged()
                            ? "새로 빚었다" : "★ 원장에서 읽었다 (땅을 안 건드렸다)")
                            + " · 요청 " + r.land().requests() + "건 — " + r.spec().summary());
                    if (r.land().forged()) {
                        // ③ 조성 영수증 — **땅은 한 번만 선다.** 어느 판(版)으로 섰는지 남긴다
                        //   (다음 사람이 "이 땅은 점묘판으로 빚어졌는가"를 물을 수 있어야 한다)
                        TerrainGate.receipt(place, site.x(), site.z(), forgeRadius, world.getSeed());
                        Announce.say(plugin, sender, ChatColor.GRAY + "[영수증] " + place.id()
                                + " — terrain_state: forged · forge_version: "
                                + TerrainGate.forgeVersion() + " · state: committed");
                    }
                    if (!r.land().forged() && r.land().requests() > 0) {
                        Announce.say(plugin, sender, ChatColor.GOLD + "땅에 " + r.land().requests()
                                + "가지 일을 더했다 (나머지는 그대로다)");
                    }
                    for (String no : Terraform.refusals) {
                        Announce.warn(plugin, sender, "거절: " + no);
                    }
                    for (String no : LandRequest.refusals) {
                        // 거절은 소리내어 말한다 — 조용히 넘어가면 그것이 곧 거짓말이다
                        Announce.warn(plugin, sender, "요청 거절: " + no);
                    }
                    for (String line : TerrainSeal.compare(r.before(), r.after())) {
                        Announce.say(plugin, sender, line);   // ★ 봉인 판정도 로그에 남는다
                    }
                    if (RiverForge.lastRefusal != null) {
                        // 조용히 넘어가지 않는다 — **짓지 않으면 위반이 없다**는 침묵이 이 사고의 정체였다
                        Announce.warn(plugin, sender,
                                "[지형/강] 강을 파지 못했다 — " + RiverForge.lastRefusal);
                    }
                    if (r.cave() != null) {
                        Announce.say(plugin, sender, ChatColor.GRAY + "[지형/동굴] " + place.id()
                                + " — 굴 입구: /tp " + r.cave().mouthX() + " " + r.cave().mouthY()
                                + " " + r.cave().mouthZ() + " · 파낸 칸 " + r.cave().carved());
                    }
                    if (terrainOnly) {
                        // ★ 땅이 섰다. **집은 일부러 안 지었다** — 조용한 성공이 아니라 **말하는 성공**이다
                        Announce.say(plugin, sender, ChatColor.GOLD + "[지형조성] " + place.name()
                                + " 의 땅이 섰다 — (" + site.x() + ", " + baseY + ", " + site.z()
                                + ") · 반경 " + forgeRadius + " · 지형 " + place.terrain()
                                + " · 건축 없음 (땅만)");
                        Announce.say(plugin, sender, ChatColor.GRAY
                                + "집은 안 지었다. 등급이 정해지면 /혼천 지역조성 이 이 땅 위에 올린다 "
                                + "— 땅은 원장에 굳었으므로 다시 안 빚는다");
                        return;
                    }
                    if (r.built().isEmpty()) {
                        Announce.warn(plugin, sender, "원형이 없어 아무것도 서지 않았다 — "
                                + place.id() + " (땅은 섰다)");
                        return;
                    }
                    java.util.List<Zone> all = new java.util.ArrayList<>(plugin.zones());
                    all.removeIf(z -> z.name().equals(place.name()));   // 재조성 = 덮어쓰기
                    all.addAll(r.built());
                    plugin.setZones(all);
                    // ★ 사람은 **조성 순간에만** 설 수 있다. 지역 앵커는 SiteSpec/CaveSpec 에만 있고
                    //   Zone 상자는 중심 대칭이라 방위를 모른다 — 나중에 재측량하면 장문이 허공에 선다.
                    plugin.populace().bindRegion(place, r.spec(), r.cave());
                    Announce.say(plugin, sender, ChatColor.GOLD + place.name() + " 이(가) 섰다 — ("
                            + site.x() + ", " + baseY + ", " + site.z() + ") · 구역 "
                            + r.built().size() + "곳");
                    plugin.getLogger().info("[지역조성] " + place.id() + " (" + place.name() + ") — ("
                            + site.x() + ", " + baseY + ", " + site.z() + ") · 구역 "
                            + r.built().size() + "곳");
                },
                // ★★ 여기가 진범이었다: 실패와 진행이 **죽은 RCON sender 로만** 갔다.
                //    RCON 은 명령이 반환되는 순간 소켓을 닫는다 — 12분간의 진행도, 실패도 허공이었다.
                err -> Announce.fail(plugin, sender, "★ 조성 실패 — " + place.id() + ": " + err),
                line -> Announce.progress(plugin, sender, line))));
    }

    /** 지형·굴·건축을 한 세션으로 묶은 결과 (봉인 전/후 포함) */
    private record RegionResult(Terraform.Land land, TerrainSeal.Seal before, TerrainSeal.Seal after,
                                java.util.List<Zone> built) {
        TerrainForge.SiteSpec spec() {
            return land.spec();
        }

        TerrainForge.CaveSpec cave() {
            return land.cave();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  산세 시험 조성 — 버리는 FLAT 월드에 광역 산세를 세운다 (기계 ① 배선)
    //  ★ 프로덕션 월드·D-12 무접촉. 전용 테스트 월드(sanse_test_*)에서만.
    // ═══════════════════════════════════════════════════════════════════

    /** 한 산세 조성이 도는 동안 참 — 중복 실행을 막는다 (재실행은 결정론이라 무해하나 겹치면 헛일) */
    private static final AtomicBoolean SANSE_FORGING = new AtomicBoolean(false);

    /** 산세 타일 한 변 (칸). 작을수록 틱당 스파이크가 작다 — 16 = 정상 타일 최악 ~0.5초 */
    private static final int SANSE_TILE = 16;

    /**
     * /혼천 산세시험 [hwasan] — <b>버리는 FLAT 테스트 월드에 화산 산세를 세운다</b> (OP·콘솔 전용).
     *
     * <p>기계 ①(MountainRangeForge)의 인게임 면(面). 조성은 <b>전용 테스트 월드</b>
     * {@code sanse_test_<변종>} 에서만 돈다 — 프로덕션 월드·D-12 갈아엎기는 절대 무접촉이다
     * (B-126 정신: 조성 대상 월드가 {@code sanse_test_} 접두가 아니면 거부).
     *
     * <p>절차: ① 버리는 FLAT 월드 생성/로드 → ② <b>그 월드의 실제 표면 y 실측</b>({@code
     * getHighestBlockYAt(0,0)} — 코드가 baseY 를 지어내지 않는다) → ③ {@link RangeSpec#hwasan}
     * (lift 160 내장)로 경제권(444) 도메인을 {@value #SANSE_TILE}칸 타일로 쪼개 {@link
     * MountainRangeForge#forgeTile} 을 {@link TickBudget#slice} 아래 굴린다 (틱을 나눠 먹는다 ·
     * 서버는 계속 돈다) → ④ 15초 안쪽 진행 로그(silence_audit) → ⑤ census + 입구(남 r≈280)
     * 텔레포트. 재실행은 결정론이라 같은 산이 선다 (덮어써도 무해 — 백업 불요).
     */
    private boolean sanseTest(CommandSender sender, String[] args) {
        if (sender instanceof Player p && !p.isOp()) {
            p.sendMessage(ChatColor.RED + "산세 시험 조성은 관리자의 몫이다.");
            return true;
        }
        String variant = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "hwasan";
        if (!variant.equals("hwasan")) {
            sender.sendMessage(ChatColor.GRAY + "/혼천 산세시험 [hwasan]  (지금은 hwasan 하나)");
            return true;
        }
        // ★ 한 번에 하나만 — 두 조성이 같은 월드를 함께 쓰면 헛일이다 (재실행 자체는 무해)
        if (!SANSE_FORGING.compareAndSet(false, true)) {
            Announce.warn(plugin, sender, "[산세시험] 이미 산세를 빚는 중이다 — 끝난 뒤에 다시 쳐라.");
            return true;
        }
        boolean started = false;
        try {
            // ★★ 가드 (B-126) — 대상 월드는 반드시 버리는 sanse_test_ 접두다. 프로덕션 보호.
            String worldName = "sanse_test_" + variant;
            if (!worldName.startsWith("sanse_test_")) {
                Announce.fail(plugin, sender,
                        "[산세시험] 대상 월드가 sanse_test_ 접두가 아니다 — 거부 (프로덕션 보호).");
                return true;
            }
            World world = loadOrCreateSanseWorld(worldName);
            if (world == null) {
                Announce.fail(plugin, sender, "[산세시험] 버리는 FLAT 월드를 만들 수 없다 — " + worldName);
                return true;
            }
            // ★★ 실물 가드 — 빚기 직전, 대상 월드의 **실제 이름**이 sanse_test_ 접두인지 다시 잰다.
            //   (프로덕션·D-12 를 산으로 덮는 사고를 여기서 최종 차단한다 — B-126 정신)
            if (!world.getName().startsWith("sanse_test_")) {
                Announce.fail(plugin, sender, "[산세시험] 조성 대상이 sanse_test_ 월드가 아니다 ("
                        + world.getName() + ") — 거부 (프로덕션 보호).");
                return true;
            }
            // ★ baseY 실측 — 코드가 지어내지 않는다. FLAT 프리셋이 정한 실제 표면 y 를 읽는다
            //   (생성기와 안 싸운다 — Q2). 봉우리·곁구역은 RangeSpec.hwasan 유도값이 정한다.
            //   ★★ 주봉 (0,0) 이 아니라 **생활권 밖 평지**(x=600 > economyR 444)에서 잰다 —
            //   (0,0) 은 재조성 때 이미 세운 산 꼭대기라 평지가 아니다. 거기서 재면 재실행마다
            //   산 위에 산을 쌓는다 (B-159). 밖은 산이 안 닿아 언제 재도 FLAT 표면 → 멱등.
            int baseY = world.getHighestBlockYAt(SpireField.PROBE_X, SpireField.PROBE_Z);
            SANSE_BASEY.put(world.getName(), baseY);   // ★기록 — 뒤 명령들의 대조 기준
            RangeSpec spec = RangeSpec.hwasan(0, 0, baseY);
            Announce.say(plugin, sender, ChatColor.GRAY + "[산세시험] " + worldName
                    + " — 기준면 실측 y" + baseY + " (FLAT 표면) · 주봉 (0,0) · 상승 " + spec.lift()
                    + " · 영향권 r" + spec.domainR() + " · 생활권 r" + spec.economyR());
            Announce.say(plugin, sender, ChatColor.DARK_GRAY + "  타일 조성을 시작한다 (한 변 "
                    + SANSE_TILE + "칸 · 틱을 나눠 먹는다 · 서버는 계속 돈다) — 진행은 [산세시험·진행] 으로 남는다");
            SanseForge forge = new SanseForge(plugin, sender, world, spec, SANSE_TILE);
            TickBudget.slice(plugin, "산세시험:" + variant, forge, () -> {
                try {
                    forge.finish();
                } finally {
                    SANSE_FORGING.set(false);   // ★ 끝나면 잠금을 푼다 (다음 시험을 위해)
                }
            });
            started = true;
            return true;
        } finally {
            if (!started) {
                SANSE_FORGING.set(false);   // 시작 전에 빠져나온 길(거부·오류)은 여기서 푼다
            }
        }
    }

    /**
     * 버리는 FLAT 산세 시험 월드 — 없으면 만든다, 있으면 그대로 쓴다 (재조성은 결정론이라 덮어써도 무해).
     * 기본 FLAT 프리셋에 맡긴다 — 표면 y 는 밖에서 <b>실측</b>한다 (코드가 정하지 않는다 · Q2).
     * 몹·날씨·시간을 잠근다 (도보 관찰만 남긴다).
     */
    private World loadOrCreateSanseWorld(String name) {
        World existing = org.bukkit.Bukkit.getWorld(name);
        if (existing != null) {
            return existing;
        }
        World w = new org.bukkit.WorldCreator(name)
                .type(org.bukkit.WorldType.FLAT)
                .generateStructures(false)
                .createWorld();
        if (w == null) {
            return null;
        }
        w.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        w.setGameRule(org.bukkit.GameRule.DO_IMMEDIATE_RESPAWN, true);
        w.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, true);
        w.setDifficulty(org.bukkit.Difficulty.PEACEFUL);
        w.setTime(6000);
        w.setStorm(false);
        return w;
    }

    /**
     * 산세 타일 순회기 — <b>내가 짠 루프</b>이므로 {@link TickBudget#slice} 로 나눠 먹인다
     * (조성기 프록시 월드가 아니라 <b>진짜 월드에 직접</b> 쓴다 — 타일이 작아 스파이크가 짧고,
     * 블록 읽기가 장벽 왕복을 안 탄다). 경제권(444) 정사각을 {@code tile}칸 격자로 훑으며 타일마다
     * {@link MountainRangeForge#forgeTile}(제 안에서 청크를 선로드한다)을 부른다. 예산은 타일
     * <i>사이</i>에서 물린다 — 한 타일은 {@code tile}²열로 유계라 워치독을 깨우지 않는다.
     */
    private static final class SanseForge implements TickBudget.Step {
        private final HoncheonMvt plugin;
        private final CommandSender sender;
        private final World world;
        private final RangeSpec spec;
        private final RangeField field;
        private final int minX;
        private final int minZ;
        private final int maxX;
        private final int maxZ;
        private final int tile;
        private final int tilesX;
        private final int totalTiles;
        private final long startNanos;

        private int index;
        private long filledBlocks;   // census — 채운 열의 높이 합 (순수 계산, 블록 I/O 아님)
        private long lastProgressMs;

        SanseForge(HoncheonMvt plugin, CommandSender sender, World world, RangeSpec spec, int tile) {
            this.plugin = plugin;
            this.sender = sender;
            this.world = world;
            this.spec = spec;
            this.field = new RangeField(spec);
            this.tile = tile;
            int r = spec.economyR();
            this.minX = spec.peakX() - r;
            this.maxX = spec.peakX() + r;
            this.minZ = spec.peakZ() - r;
            this.maxZ = spec.peakZ() + r;
            this.tilesX = (maxX - minX + tile) / tile;   // ceil((span)/tile)
            int tilesZ = (maxZ - minZ + tile) / tile;
            this.totalTiles = tilesX * tilesZ;
            this.startNanos = System.nanoTime();
            this.lastProgressMs = System.currentTimeMillis();
        }

        @Override
        public boolean step() {
            if (index >= totalTiles) {
                return false;
            }
            int ti = index % tilesX;
            int tj = index / tilesX;
            int x0 = minX + ti * tile;
            int z0 = minZ + tj * tile;
            int x1 = Math.min(maxX, x0 + tile - 1);
            int z1 = Math.min(maxZ, z0 + tile - 1);
            // census 집계 — 채운 블록 수(융기 열 높이 합). RangeField 는 결정론·Bukkit 무의존이라 값싸다
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    int h = field.surfaceY(x, z) - spec.baseY();
                    if (h > 0) {
                        filledBlocks += h;
                    }
                }
            }
            MountainRangeForge.forgeTile(world, spec, field, x0, z0, x1, z1);
            index++;
            // 진행 — 3초마다 사람에게, 15초마다 로그(Announce 가 로그를 rate-limit). silence_audit 이 지킨다
            long now = System.currentTimeMillis();
            if (now - lastProgressMs >= 3000L || index == totalTiles) {
                lastProgressMs = now;
                int pct = (int) (100L * index / totalTiles);
                Announce.progress(plugin, sender, ChatColor.GRAY + "[산세시험·진행] " + index + "/"
                        + totalTiles + " 타일 (" + pct + "%) · 융기블록 " + filledBlocks + " · "
                        + (System.nanoTime() - startNanos) / 1_000_000_000L + "초");
            }
            return index < totalTiles;
        }

        /** 조성 완료 — census(로그 먼저·사람 뒤) + 입구(남 r≈280) 텔레포트. 콘솔이면 좌표만 안내 */
        void finish() {
            int px = spec.peakX();
            int pz = spec.peakZ();
            int summitY = world.getHighestBlockYAt(px, pz);
            int rise = summitY - spec.baseY();
            long secs = (System.nanoTime() - startNanos) / 1_000_000_000L;
            Announce.say(plugin, sender, ChatColor.GOLD + "[산세시험] 화산 산세가 섰다 — " + world.getName());
            Announce.say(plugin, sender, ChatColor.GRAY + "  정상 (" + px + ", " + summitY + ", " + pz
                    + ") · 상승고 " + rise + " (기준면 y" + spec.baseY() + " · 설계 lift " + spec.lift()
                    + ") · 융기블록 " + filledBlocks + " · 타일 " + totalTiles + " · " + secs + "초");
            Announce.say(plugin, sender, ChatColor.GRAY + "  지표: 정상 +" + spec.lift()
                    + " · 곁구역 넷(매화림·계곡·절벽·연무 계곡) · 곁능선 " + spec.sideRidges().size()
                    + "가닥 · 협곡 " + spec.gorges().size() + "줄 · 등산로 남면 진입 · 영향권 r"
                    + spec.domainR() + " · 생활권 r" + spec.economyR());
            // 입구 = 남측(+z) r≈280 지면 (입구 구역: honsanR+midDepth < r ≤ domainR = 264~294)
            int ex = px;
            int ez = pz + 280;
            int ey = world.getHighestBlockYAt(ex, ez) + 1;
            if (sender instanceof Player player) {
                // 북(-z)을 바라보게 = 산을 마주본다 (yaw 180 = 북향)
                Location entrance = new Location(world, ex + 0.5, ey, ez + 0.5, 180f, 0f);
                player.teleport(entrance);
                Announce.say(plugin, sender, ChatColor.GREEN + "  입구(남 r280)에 세운다 — 북으로 산을 마주본다 ("
                        + ex + ", " + ey + ", " + ez + ")");
            } else {
                Announce.say(plugin, sender, ChatColor.GRAY + "  입구(남 r280): /tp " + ex + " " + ey + " "
                        + ez + " (콘솔 — 좌표만 안내 · 월드 " + world.getName() + ")");
            }
        }
    }


    // ═══════════════════════════════════════════════════════════════════
    //  식생 시험 조성 — 산세시험 월드에 구역별 식생을 심는다 (기계 ⑨ 배선)
    //  ★ 프로덕션 월드·D-12 무접촉. sanse_test_ 접두 월드에서만. 산세 위에 얹는다.
    // ═══════════════════════════════════════════════════════════════════

    /** 한 식생 조성이 도는 동안 참 — 중복 실행을 막는다 (재실행은 결정론이라 무해하나 겹치면 헛일) */
    private static final AtomicBoolean FLORA_FORGING = new AtomicBoolean(false);

    /** 식생 타일 한 변 (칸) — 산세 타일과 같은 결. 나무 하나가 청크 왕복을 안 타 스파이크가 짧다 */
    private static final int FLORA_TILE = 16;

    /**
     * /혼천 식생시험 [hwasan] — <b>산세시험 월드에 구역별 식생을 심는다</b> (OP·콘솔 전용).
     *
     * <p>식생층 생성기(기계 ⑨ — {@link FloraForge})의 인게임 면(面). 이미 선 산세
     * ({@code sanse_test_hwasan}) 위에 구역별 식생(매화림→벚꽃 · 외곽→참나무 숲 · 계곡→대나무 ·
     * 후산→오래된 소나무 등 — hwasan_domain_design.md §식생 표)을 얹는다.
     *
     * <p>절차: ① 산세시험 월드를 로드 (없거나 산세가 안 섰으면 「먼저 /혼천 산세시험」 안내) →
     * ② 기준면 y 를 <b>산 밖 평지에서 실측</b> (getHighestBlockYAt · 코드가 baseY 를 지어내지 않는다) →
     * ③ 경제권(444) 정사각을 {@value #FLORA_TILE}칸 타일로 {@link FloraForge#plantTile} 아래 굴린다
     * (틱을 나눠 먹는다 · 서버는 계속 돈다) → ④ 15초 안쪽 진행 로그(silence_audit) → ⑤ census
     * (심은 나무·대나무·지표 수 · 구역별) + 매화림(남 산기슭) 텔레포트. 재실행은 결정론이라 같은
     * 식생이 선다 (덮어써도 무해). ★가드: sanse_test_ 접두만 (프로덕션 보호 — B-126 정신).
     */
    private boolean floraTest(CommandSender sender, String[] args) {
        if (sender instanceof Player p && !p.isOp()) {
            p.sendMessage(ChatColor.RED + "식생 시험 조성은 관리자의 몫이다.");
            return true;
        }
        String variant = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "hwasan";
        if (!variant.equals("hwasan")) {
            sender.sendMessage(ChatColor.GRAY + "/혼천 식생시험 [hwasan]  (지금은 hwasan 하나)");
            return true;
        }
        if (!FLORA_FORGING.compareAndSet(false, true)) {
            Announce.warn(plugin, sender, "[식생시험] 이미 식생을 심는 중이다 — 끝난 뒤에 다시 쳐라.");
            return true;
        }
        boolean started = false;
        try {
            String worldName = "sanse_test_" + variant;
            // ★★ 가드 (B-126) — 대상은 반드시 버리는 sanse_test_ 접두다. 프로덕션 보호.
            if (!worldName.startsWith("sanse_test_")) {
                Announce.fail(plugin, sender,
                        "[식생시험] 대상 월드가 sanse_test_ 접두가 아니다 — 거부 (프로덕션 보호).");
                return true;
            }
            World world = org.bukkit.Bukkit.getWorld(worldName);
            if (world == null || !world.getName().startsWith("sanse_test_")) {
                // ★ 식생은 산세 위에 얹는다 — 월드를 새로 만들지 않는다. 산세부터 세우라 안내한다
                Announce.warn(plugin, sender, "[식생시험] " + worldName
                        + " 이(가) 없다 — 먼저 /혼천 산세시험 hwasan 으로 산세를 세워라.");
                return true;
            }
            // ★ 기준면 실측 — 산 밖 평지(경제권 밖)의 표면 y. 코드가 지어내지 않는다.
            //   주봉은 산세시험과 같은 (0,0). economyR 은 baseY 무관 상수라 임시 spec 으로 읽는다.
            int peakX = 0;
            int peakZ = 0;
            int economyR = RangeSpec.hwasan(peakX, peakZ, 0).economyR();
            int baseY = probeBaseY(world, sender, plugin, "식생시험");   // ★정본 실측점 (6.5 통일 — 옛 484 는 침봉 필드 안)
            if (baseY == -9999) {
                return true;
            }
            RangeSpec spec = RangeSpec.hwasan(peakX, peakZ, baseY);
            // ★ 산세가 섰는가 — 정상 열이 기준면보다 한참 높아야 한다 (산이 없으면 식생 심을 자리가 없다)
            int summitTop = world.getHighestBlockYAt(peakX, peakZ);
            if (summitTop - baseY < 20) {
                Announce.warn(plugin, sender, "[식생시험] " + worldName + " 에 산세가 안 섰다 (정상고 "
                        + (summitTop - baseY) + " < 20) — 먼저 /혼천 산세시험 hwasan.");
                return true;
            }
            Announce.say(plugin, sender, ChatColor.GRAY + "[식생시험] " + worldName
                    + " — 기준면 실측 y" + baseY + " · 정상고 " + (summitTop - baseY)
                    + " · 주봉 (" + peakX + "," + peakZ + ") · 생활권 r" + spec.economyR());
            Announce.say(plugin, sender, ChatColor.DARK_GRAY + "  구역별 식생을 심는다 (한 변 "
                    + FLORA_TILE + "칸 · 틱을 나눠 먹는다 · 서버는 계속 돈다) — 진행은 [식생시험·진행] 으로 남는다");
            SanseFlora flora = new SanseFlora(plugin, sender, world, spec, FLORA_TILE);
            TickBudget.slice(plugin, "식생시험:" + variant, flora, () -> {
                try {
                    flora.finish();
                } finally {
                    FLORA_FORGING.set(false);   // ★ 끝나면 잠금을 푼다
                }
            });
            started = true;
            return true;
        } finally {
            if (!started) {
                FLORA_FORGING.set(false);
            }
        }
    }

    /**
     * 식생 타일 순회기 — {@link SanseForge} 의 짝. 경제권 정사각을 {@code tile}칸 격자로 훑으며
     * 타일마다 {@link FloraForge#plantTile}(제 안에서 청크를 선로드한다)을 부른다. 산세와 달리
     * <b>산을 다시 빚지 않는다</b> — 이미 선 표면 위(surfaceY+1)에만 식생을 얹는다.
     */
    private static final class SanseFlora implements TickBudget.Step {
        private final HoncheonMvt plugin;
        private final CommandSender sender;
        private final World world;
        private final RangeSpec spec;
        private final RangeField field;
        private final FloraForge.Tally tally = new FloraForge.Tally();
        private final int minX;
        private final int minZ;
        private final int maxX;
        private final int maxZ;
        private final int tile;
        private final int tilesX;
        private final int totalTiles;
        private final long startNanos;

        private int index;
        private long lastProgressMs;

        SanseFlora(HoncheonMvt plugin, CommandSender sender, World world, RangeSpec spec, int tile) {
            this.plugin = plugin;
            this.sender = sender;
            this.world = world;
            this.spec = spec;
            this.field = new RangeField(spec);
            this.tile = tile;
            int r = spec.economyR();
            this.minX = spec.peakX() - r;
            this.maxX = spec.peakX() + r;
            this.minZ = spec.peakZ() - r;
            this.maxZ = spec.peakZ() + r;
            this.tilesX = (maxX - minX + tile) / tile;
            int tilesZ = (maxZ - minZ + tile) / tile;
            this.totalTiles = tilesX * tilesZ;
            this.startNanos = System.nanoTime();
            this.lastProgressMs = System.currentTimeMillis();
        }

        @Override
        public boolean step() {
            if (index >= totalTiles) {
                return false;
            }
            int ti = index % tilesX;
            int tj = index / tilesX;
            int x0 = minX + ti * tile;
            int z0 = minZ + tj * tile;
            int x1 = Math.min(maxX, x0 + tile - 1);
            int z1 = Math.min(maxZ, z0 + tile - 1);
            FloraForge.plantTile(world, spec, field, x0, z0, x1, z1, tally);
            index++;
            long now = System.currentTimeMillis();
            if (now - lastProgressMs >= 3000L || index == totalTiles) {
                lastProgressMs = now;
                int pct = (int) (100L * index / totalTiles);
                Announce.progress(plugin, sender, ChatColor.GRAY + "[식생시험·진행] " + index + "/"
                        + totalTiles + " 타일 (" + pct + "%) · 나무 " + tally.trees() + " · 대나무 "
                        + tally.bamboo() + " · 지표 " + tally.ground() + " · "
                        + (System.nanoTime() - startNanos) / 1_000_000_000L + "초");
            }
            return index < totalTiles;
        }

        /** 조성 완료 — census + 매화림(남 산기슭) 텔레포트. 콘솔이면 좌표만 안내 */
        void finish() {
            long secs = (System.nanoTime() - startNanos) / 1_000_000_000L;
            Announce.say(plugin, sender, ChatColor.GOLD + "[식생시험] 화산 식생이 섰다 — " + world.getName());
            Announce.say(plugin, sender, ChatColor.GRAY + "  나무 " + tally.trees() + "그루 · 대나무 "
                    + tally.bamboo() + "줄 · 지표(풀·꽃·이끼) " + tally.ground() + "칸 · 타일 "
                    + totalTiles + " · " + secs + "초");
            Announce.say(plugin, sender, ChatColor.GRAY + "  구역별 나무·대나무: " + tally.byZone());
            Announce.say(plugin, sender, ChatColor.DARK_GRAY
                    + "  수종: 외곽=참나무·자작 · 매화림=벚꽃 · 계곡=대나무 · 후산=오래된 소나무·고목 · "
                    + "입구=소나무(잠정) · 정상=풀만 · 절벽/연무/본산=성기게(풀·관목)");
            // 매화림 = 남면(+z) 중간 고리 산기슭 쪽 — 곁구역 재단(등산로 호장)이 정한 자리를 찾는다
            int[] pg = findPlumGrove();
            if (pg == null) {
                Announce.warn(plugin, sender, "  매화림 열을 못 찾았다 — 텔레포트 생략 (곁구역 재단 확인).");
                return;
            }
            int ey = world.getHighestBlockYAt(pg[0], pg[1]) + 1;
            if (sender instanceof Player player) {
                Location spot = new Location(world, pg[0] + 0.5, ey, pg[1] + 0.5, 180f, 0f);   // 북향 = 산을 마주본다
                player.teleport(spot);
                Announce.say(plugin, sender, ChatColor.GREEN + "  매화림(남 산기슭)에 세운다 — 벚꽃을 본다 ("
                        + pg[0] + ", " + ey + ", " + pg[1] + ")");
            } else {
                Announce.say(plugin, sender, ChatColor.GRAY + "  매화림(남 산기슭): /tp " + pg[0] + " " + ey
                        + " " + pg[1] + " (콘솔 — 좌표만 안내 · 월드 " + world.getName() + ")");
            }
        }

        /** 매화림 한 열 — 남면(+z) 중간 고리를 산기슭(바깥)에서 안쪽으로 훑어 첫 PLUM_GROVE 를 잡는다 */
        private int[] findPlumGrove() {
            int px = spec.peakX();
            int pz = spec.peakZ();
            int outer = spec.honsanR() + spec.midDepth();   // 산기슭 쪽 (u≈0 = 매화림)
            int inner = spec.honsanR();
            for (int z = pz + outer; z >= pz + inner; z--) {
                for (int dx = 0; dx <= 80; dx++) {
                    for (int sign : new int[]{1, -1}) {
                        int x = px + sign * dx;
                        if (field.zoneAt(x, z) == RangeZone.PLUM_GROVE) {
                            return new int[]{x, z};
                        }
                        if (dx == 0) {
                            break;
                        }
                    }
                }
            }
            return null;
        }
    }


    // ═══════════════════════════════════════════════════════════════════
    //  도보길 시험 조성 — 완성된 험산 위에 걸을 수 있는 계단길을 짓는다 (기계 ③ 조성분 배선)
    //  ★ 프로덕션 월드·D-12 무접촉. sanse_test_ 접두 월드에서만. 산세 위에 블록만 얹는다.
    // ═══════════════════════════════════════════════════════════════════

    /** 한 도보길 조성이 도는 동안 참 — 중복 실행을 막는다 (재실행은 결정론이라 무해하나 겹치면 헛일) */
    private static final AtomicBoolean TRAIL_BUILDING = new AtomicBoolean(false);

    /**
     * /혼천 도보길 [hwasan] — <b>완성된 험산 위에 걸을 수 있는 계단길(천계단·잔도)을 짓는다</b>
     * (OP·콘솔 전용).
     *
     * <p>도보길 조성기(기계 ③ 조성분 — {@link TrailBuilder})의 인게임 면(面). 이미 선 산세
     * ({@code sanse_test_hwasan}) 위에 {@code TrailForge} 노선을 딛는 <b>석계단길</b>을 놓아
     * <b>산기슭→정상(최고봉 Pm)</b> 을 끊김 없이 잇는다. 산세 높이장은 안 건드린다 — 블록만 얹는다.
     *
     * <p>절차: ① 산세시험 월드 로드 (없거나 산세 미조성이면 「먼저 /혼천 산세시험」 안내) →
     * ② 산 밖 평지에서 baseY 실측 → ③ 노선 계획({@link TrailBuilder#plan} — 순수) →
     * ④ 노드마다 {@link TrailBuilder#paveNode} 를 {@link TickBudget#slice} 아래 굴린다 (틱을 나눠
     * 먹는다 · 15초 안쪽 진행 로그) → ⑤ census(계단·평탄·기단·등롱 수 · 시작/정상 좌표 · 소요) +
     * 트레일헤드(산기슭)에 텔레포트(정상 바라보게). ★가드: {@code sanse_test_} 접두만 (B-126).
     * 재실행은 결정론·멱등이라 덮어써도 무해.
     */
    private boolean trailBuild(CommandSender sender, String[] args) {
        if (sender instanceof Player p && !p.isOp()) {
            p.sendMessage(ChatColor.RED + "도보길 조성은 관리자의 몫이다.");
            return true;
        }
        String variant = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "hwasan";
        if (!variant.equals("hwasan")) {
            sender.sendMessage(ChatColor.GRAY + "/혼천 도보길 [hwasan]  (지금은 hwasan 하나)");
            return true;
        }
        if (!TRAIL_BUILDING.compareAndSet(false, true)) {
            Announce.warn(plugin, sender, "[도보길] 이미 계단길을 놓는 중이다 — 끝난 뒤에 다시 쳐라.");
            return true;
        }
        boolean started = false;
        try {
            String worldName = "sanse_test_" + variant;
            // ★★ 가드 (B-126) — 대상은 반드시 버리는 sanse_test_ 접두다. 프로덕션 보호.
            if (!worldName.startsWith("sanse_test_")) {
                Announce.fail(plugin, sender,
                        "[도보길] 대상 월드가 sanse_test_ 접두가 아니다 — 거부 (프로덕션 보호).");
                return true;
            }
            World world = org.bukkit.Bukkit.getWorld(worldName);
            if (world == null || !world.getName().startsWith("sanse_test_")) {
                Announce.warn(plugin, sender, "[도보길] " + worldName
                        + " 이(가) 없다 — 먼저 /혼천 산세시험 hwasan 으로 산세를 세워라.");
                return true;
            }
            // ★ 기준면 실측 — 정본 실측점 (산군 필드 밖 · 6.5 통일 — 옛 484 는 침봉 필드 안이었다).
            int peakX = 0;
            int peakZ = 0;
            int baseY = probeBaseY(world, sender, plugin, "도보길");
            if (baseY == -9999) {
                return true;
            }
            RangeSpec spec = RangeSpec.hwasan(peakX, peakZ, baseY);
            // ★ 산세가 섰는가 — 정상 열이 기준면보다 한참 높아야 한다 (산이 없으면 길을 낼 자리가 없다)
            int summitTop = world.getHighestBlockYAt(peakX, peakZ);
            if (summitTop - baseY < 20) {
                Announce.warn(plugin, sender, "[도보길] " + worldName + " 에 산세가 안 섰다 (정상고 "
                        + (summitTop - baseY) + " < 20) — 먼저 /혼천 산세시험 hwasan.");
                return true;
            }
            // ★ 노선 계획은 순수(Bukkit 무의존) — 여기서 미리 세워 census 좌표를 확보한다
            RangeField field = new RangeField(spec);
            TrailBuilder.Plan plan = TrailBuilder.plan(spec, field);
            Announce.say(plugin, sender, ChatColor.GRAY + "[도보길] " + worldName
                    + " — 기준면 실측 y" + baseY + " · 노드 " + plan.nodes().size()
                    + " · 트레일헤드 (" + plan.footX() + "," + plan.footY() + "," + plan.footZ()
                    + ") → 정상 (" + plan.summitX() + "," + plan.summitY() + "," + plan.summitZ()
                    + ") · 오름 " + (plan.summitY() - plan.footY()) + "칸");
            Announce.say(plugin, sender, ChatColor.DARK_GRAY + "  석계단길을 놓는다 (노드마다 폭 "
                    + (2 * TrailBuilder.HALF + 1) + " · 한 칸 계단 · 틱을 나눠 먹는다 · 서버는 계속 돈다)"
                    + " — 진행은 [도보길·진행] 으로 남는다");
            TrailPaver paver = new TrailPaver(plugin, sender, world, spec, plan);
            TickBudget.slice(plugin, "도보길:" + variant, paver, () -> {
                try {
                    paver.finish();
                } finally {
                    TRAIL_BUILDING.set(false);
                }
            });
            started = true;
            return true;
        } finally {
            if (!started) {
                TRAIL_BUILDING.set(false);
            }
        }
    }

    /**
     * 도보길 노드 순회기 — {@link SanseForge}·{@link SanseFlora} 의 짝. 계획된 노드 사슬을 하나씩
     * 꺼내 {@link TrailBuilder#paveNode}(제 안에서 청크를 선로드한다)로 땅에 놓는다. 산을 다시
     * 빚지 않는다 — 이미 선 험산 위에 노반·계단·기단만 얹는다.
     */
    private static final class TrailPaver implements TickBudget.Step {
        private final HoncheonMvt plugin;
        private final CommandSender sender;
        private final World world;
        private final RangeSpec spec;
        private final TrailBuilder.Plan plan;
        private final TrailBuilder.Tally tally = new TrailBuilder.Tally();
        private final int total;
        private final long startNanos;

        private int index;
        private long lastProgressMs;

        TrailPaver(HoncheonMvt plugin, CommandSender sender, World world,
                   RangeSpec spec, TrailBuilder.Plan plan) {
            this.plugin = plugin;
            this.sender = sender;
            this.world = world;
            this.spec = spec;
            this.plan = plan;
            this.total = plan.nodes().size();
            this.startNanos = System.nanoTime();
            this.lastProgressMs = System.currentTimeMillis();
        }

        @Override
        public boolean step() {
            if (index >= total) {
                return false;
            }
            TrailBuilder.paveNode(world, spec, plan.nodes().get(index), tally);
            index++;
            long now = System.currentTimeMillis();
            if (now - lastProgressMs >= 3000L || index == total) {
                lastProgressMs = now;
                int pct = (int) (100L * index / total);
                Announce.progress(plugin, sender, ChatColor.GRAY + "[도보길·진행] " + index + "/"
                        + total + " 노드 (" + pct + "%) · 계단 " + tally.stairs() + " · 평탄 "
                        + tally.flats() + " · 기단 " + tally.filled() + " · 다리 " + tally.bridges() + " · "
                        + (System.nanoTime() - startNanos) / 1_000_000_000L + "초");
            }
            return index < total;
        }

        /** 조성 완료 — census + 트레일헤드(산기슭) 텔레포트(정상 바라보게). 콘솔이면 좌표만 안내 */
        void finish() {
            long secs = (System.nanoTime() - startNanos) / 1_000_000_000L;
            Announce.say(plugin, sender, ChatColor.GOLD + "[도보길] 화산 천계단이 섰다 — " + world.getName());
            Announce.say(plugin, sender, ChatColor.GRAY + "  석계단 " + tally.stairs() + " · 평탄 노반 "
                    + tally.flats() + " · 기단 받침 " + tally.filled() + " · 깎아낸 바위 " + tally.carved()
                    + " · 등롱 " + tally.lanterns() + " · 노드 " + total + " · " + secs + "초");
            Announce.say(plugin, sender, ChatColor.GRAY + "  다리(잔도·현공교) 상판 " + tally.bridges()
                    + "노드 · 지지 기둥 " + tally.piers() + "다리 · 난간 " + tally.rails()
                    + " · 기둥(울타리 2단) " + tally.posts()
                    + " (뜬 구간은 다리 — 밑 허공 · 목책풍 난간: 다리 양옆·낭떠러지 쪽·굽이 바깥)");
            Announce.say(plugin, sender, ChatColor.GRAY + "  트레일헤드(산기슭) (" + plan.footX() + ","
                    + plan.footY() + "," + plan.footZ() + ") → 정상 Pm (" + plan.summitX() + ","
                    + plan.summitY() + "," + plan.summitZ() + ") · 오름 " + (plan.summitY() - plan.footY())
                    + "칸 · 한 칸 계단 원칙(연속 노반 단차 ≤" + TrailBuilder.MAX_STEP + ")");
            Announce.say(plugin, sender, ChatColor.DARK_GRAY + "  참고 최고 열(본산권): (" + plan.highestColX()
                    + "," + plan.highestColY() + "," + plan.highestColZ() + ") — 잔도 절벽 종주는 미결");
            // 트레일헤드 = 노선 시작(산기슭). 노반 위(standY+1)에 세우고 정상을 바라보게 한다
            int hy = world.getHighestBlockYAt(plan.footX(), plan.footZ());
            int fy = Math.max(plan.footY() + 1, hy + 1);
            float yaw = (float) Math.toDegrees(Math.atan2(
                    -(plan.summitX() - plan.footX()), plan.summitZ() - plan.footZ()));
            if (sender instanceof Player player) {
                Location head = new Location(world, plan.footX() + 0.5, fy, plan.footZ() + 0.5, yaw, 0f);
                player.teleport(head);
                Announce.say(plugin, sender, ChatColor.GREEN + "  트레일헤드에 세운다 — 계단을 밟고 오른다 ("
                        + plan.footX() + ", " + fy + ", " + plan.footZ() + " · 정상 바라봄)");
            } else {
                Announce.say(plugin, sender, ChatColor.GRAY + "  트레일헤드: /tp " + plan.footX() + " " + fy
                        + " " + plan.footZ() + " (콘솔 — 좌표만 안내 · 월드 " + world.getName() + ")");
            }
        }
    }


    // ═══════════════════════════════════════════════════════════════════
    //  캠퍼스 시험 조성 — 산세시험 월드에 석축 테라스 단(段)을 앉힌다 (B-146 처방 시험)
    //  ★ 프로덕션 월드 무접촉. sanse_test_ 접두 월드에서만. 산세 위에 얹는다 (높이장 무접촉).
    // ═══════════════════════════════════════════════════════════════════

    /** 한 캠퍼스 조성이 도는 동안 참 — 중복 실행을 막는다 (재실행은 결정론·멱등이라 무해) */
    private static final AtomicBoolean CAMPUS_FORGING = new AtomicBoolean(false);

    /**
     * /혼천 캠퍼스시험 [hwasan] — <b>완성된 험산 위에 화산 캠퍼스(마스터플랜 20구역 중 슬라이스 1 몫)의 석축 패드·계단을 앉힌다</b>
     * (OP·콘솔 전용).
     *
     * <p>석축 테라스 기계({@link TerraceForge})의 인게임 면. 설계 정본:
     * {@code docs/design/hwasan_build_enhancement_v1.md} §2·§3. 이미 선 산세
     * ({@code sanse_test_hwasan}) 위에 척추 단 6(산문→외원→종문→본전→장로회→정상)+계단참 2와
     * 좌우 로브 단 7(연무장·강당·생활·훈련장·창고)을 앉힌다 — 패드 위는 평탄, 가장자리는
     * 실지형까지 내려가 닿는 옹벽, 패드 사이는 폭 7 대계단(몸체 전 열 접지). <b>건물이 뜰 자리가 구조적으로 없다</b> (B-146).
     *
     * <p>절차는 도보길과 같은 결: ① 월드·산세 확인 → ② baseY 실측 → ③ 계획
     * ({@link TerraceForge#plan} — 명세 순수 검증+지형 판독·어긋남 경고) → ④ 패드·계단을
     * {@link TickBudget#slice} 아래 하나씩 → ⑤ <b>검수</b>({@link TerraceForge#audit} — 평탄·접지·보행)와 census.
     * 검수 위반은 <b>소리친다</b> (조용한 성공 금지). ★가드: {@code sanse_test_} 접두만 (B-126).
     */
    private boolean campusTest(CommandSender sender, String[] args) {
        if (sender instanceof Player p && !p.isOp()) {
            p.sendMessage(ChatColor.RED + "캠퍼스 조성은 관리자의 몫이다.");
            return true;
        }
        String variant = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "hwasan";
        if (!variant.equals("hwasan")) {
            sender.sendMessage(ChatColor.GRAY + "/혼천 캠퍼스시험 [hwasan]  (지금은 hwasan 하나)");
            return true;
        }
        if (!CAMPUS_FORGING.compareAndSet(false, true)) {
            Announce.warn(plugin, sender, "[캠퍼스시험] 이미 테라스를 앉히는 중이다 — 끝난 뒤에 다시 쳐라.");
            return true;
        }
        boolean started = false;
        try {
            String worldName = "sanse_test_" + variant;
            // ★★ 가드 (B-126) — 대상은 반드시 버리는 sanse_test_ 접두다. 프로덕션 보호.
            if (!worldName.startsWith("sanse_test_")) {
                Announce.fail(plugin, sender,
                        "[캠퍼스시험] 대상 월드가 sanse_test_ 접두가 아니다 — 거부 (프로덕션 보호).");
                return true;
            }
            World world = org.bukkit.Bukkit.getWorld(worldName);
            if (world == null || !world.getName().startsWith("sanse_test_")) {
                Announce.warn(plugin, sender, "[캠퍼스시험] " + worldName
                        + " 이(가) 없다 — 먼저 /혼천 산세시험 hwasan 으로 산세를 세워라.");
                return true;
            }
            // ★ 기준면 실측 — 정본 실측점 (산군 필드 밖 · 6.5 통일). 표류면 거부.
            int peakX = 0;
            int peakZ = 0;
            int baseY = probeBaseY(world, sender, plugin, "캠퍼스시험");
            if (baseY == -9999) {
                return true;
            }
            RangeSpec spec = RangeSpec.hwasan(peakX, peakZ, baseY);
            // ★ 산세가 섰는가 — 산이 없으면 단을 앉힐 비탈이 없다
            int summitTop = world.getHighestBlockYAt(peakX, peakZ);
            if (summitTop - baseY < 20) {
                Announce.warn(plugin, sender, "[캠퍼스시험] " + worldName + " 에 산세가 안 섰다 (정상고 "
                        + (summitTop - baseY) + " < 20) — 먼저 /혼천 산세시험 hwasan.");
                return true;
            }
            // 계획 — 명세 검증(순수) + 발자국 지형 판독 (~1.2만 열 · 청크 동기 로드 한 번의 스파이크)
            TerraceForge.Plan plan = TerraceForge.plan(world, spec);
            HwasanCampusBuilder.validateBuildings(plan.pads(), plan.lanes(), plan.bridges());   // 발자국 ⊂ 패드 + 통로·다리 어귀 무접촉 (계율 #4)
            Announce.say(plugin, sender, ChatColor.GRAY + "[캠퍼스시험] " + worldName
                    + " — 기준면 실측 y" + baseY + " · 패드 " + plan.pads().size()
                    + " (통단 7대 17칸 + 곁봉 3 — 실측 재구성) · 계단 " + plan.lanes().size()
                    + " · 다리 " + plan.bridges().size() + " — 마스터플랜 20구역 · 실측표 hwasan_block_measurements.md");
            StringBuilder ys = new StringBuilder();
            for (TerraceForge.Pad p : plan.pads()) {
                if (ys.length() > 0) {
                    ys.append(" · ");
                }
                ys.append(p.spec().zone()).append(' ').append(p.spec().name()).append(" y").append(p.y());
            }
            Announce.say(plugin, sender, ChatColor.DARK_GRAY + "  " + ys);
            // ★잠정 높이 vs 실지형 어긋남 — 계획이 잰 것을 그대로 소리 낸다 (조용히 지어내지 않는다)
            for (String n : plan.terrainNotes()) {
                Announce.warn(plugin, sender, "[캠퍼스시험] 지형 어긋남 — " + n);
            }
            Announce.say(plugin, sender, ChatColor.DARK_GRAY + "  패드·계단을 하나씩 앉힌다"
                    + " (틱을 나눠 먹는다 · 서버는 계속 돈다) — 진행은 [캠퍼스시험·진행] 으로 남는다");
            CampusPaver paver = new CampusPaver(plugin, sender, world, plan);
            TickBudget.slice(plugin, "캠퍼스시험:" + variant, paver, () -> {
                try {
                    paver.finish();
                } finally {
                    CAMPUS_FORGING.set(false);
                }
            });
            started = true;
            return true;
        } finally {
            if (!started) {
                CAMPUS_FORGING.set(false);
            }
        }
    }

    /** 한 산군 조성이 도는 동안 참 — 중복 실행을 막는다 (재실행은 결정론·멱등이라 무해) */
    private static final AtomicBoolean SPIRE_FORGING = new AtomicBoolean(false);

    /** 산세시험이 기록한 기준면 — 뒤 명령들이 실측값과 대조한다 (표류 = 조용히 뜨는 캠퍼스) */
    private static final java.util.Map<String, Integer> SANSE_BASEY = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 기준면 실측 — ★정본 실측점은 {@link SpireField#PROBE_X} 하나다 (6.0 병: 실측점이 침봉
     * 필드 안이라 캠퍼스가 54칸 떴다). 산세시험 기록이 있으면 대조해서 어긋나면 -9999 로 거부.
     */
    private static int probeBaseY(World world, CommandSender sender, HoncheonMvt plugin, String who) {
        int baseY = world.getHighestBlockYAt(SpireField.PROBE_X, SpireField.PROBE_Z);
        Integer recorded = SANSE_BASEY.get(world.getName());
        if (recorded != null && recorded != baseY) {
            Announce.fail(plugin, sender, "[" + who + "] ★기준면 표류 — 실측 y" + baseY
                    + " ≠ 산세시험 기록 y" + recorded + " (프로브 열이 오염됐다) — 거부. 산세를 다시 세워라.");
            return -9999;
        }
        return baseY;
    }

    /**
     * /혼천 산군시험 [hwasan] — <b>완성된 산세 위에 산군(배후봉 증고 + 침봉 켜 3)을 얹는다</b>
     * (OP·콘솔 전용 · 프로덕션 무접촉).
     *
     * <p>★근거: 사용자 확정 (2026-08-03) — 「화산파 내외의 산들이 전부 구성되어야 한다」.
     * 수치는 실측표 §4 ({@link SpireField} 주석). 캠퍼스·계단·다리 발자국(+여유)은 제외
     * 목록으로 산이 비켜 간다. 권장 순서: 산세시험 → <b>산군시험</b> → 캠퍼스시험
     * (캠퍼스가 마지막 — 걷기·스커트가 잔재도 정리한다). max 합성이라 협곡·골은 남는다.
     */
    private boolean spireTest(CommandSender sender, String[] args) {
        if (sender instanceof Player p && !p.isOp()) {
            p.sendMessage(ChatColor.RED + "산군 조성은 관리자의 몫이다.");
            return true;
        }
        String variant = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "hwasan";
        if (!variant.equals("hwasan")) {
            sender.sendMessage(ChatColor.GRAY + "/혼천 산군시험 [hwasan]  (지금은 hwasan 하나)");
            return true;
        }
        if (!SPIRE_FORGING.compareAndSet(false, true)) {
            Announce.warn(plugin, sender, "[산군시험] 이미 산군을 세우는 중이다 — 끝난 뒤에 다시 쳐라.");
            return true;
        }
        boolean started = false;
        try {
            String worldName = "sanse_test_" + variant;
            if (!worldName.startsWith("sanse_test_")) {
                Announce.fail(plugin, sender, "[산군시험] 대상이 sanse_test_ 접두가 아니다 — 거부.");
                return true;
            }
            World world = org.bukkit.Bukkit.getWorld(worldName);
            if (world == null || !world.getName().startsWith("sanse_test_")) {
                Announce.warn(plugin, sender, "[산군시험] " + worldName
                        + " 이(가) 없다 — 먼저 /혼천 산세시험 hwasan.");
                return true;
            }
            if (!SpireField.probeUntouched()) {
                Announce.fail(plugin, sender, "[산군시험] ★프로브 열이 산군에 덮인다 — SpireField 상수를 고쳐라.");
                return true;
            }
            int baseY = probeBaseY(world, sender, plugin, "산군시험");
            if (baseY == -9999) {
                return true;
            }
            // 제외 목록 — 캠퍼스 패드(+8)·계단(+4)·다리(+6): 산이 사람의 것을 침범하지 않는다
            TerraceForge.Campus campus = TerraceForge.hwasanCampus();
            java.util.List<TerraceForge.Pad> pads = TerraceForge.resolvePads(campus, 0, 0, baseY);
            java.util.List<TerraceForge.StairLane> lanes = TerraceForge.resolveLanes(campus, pads);
            java.util.List<TerraceForge.Bridge> bridges =
                    TerraceForge.resolveBridges(campus, pads, lanes, 0, 0, baseY);
            java.util.List<int[]> ex = new java.util.ArrayList<>();
            for (TerraceForge.Pad pd : pads) {
                ex.add(new int[]{pd.x0() - 8, pd.x1() + 8, pd.zN() - 8, pd.zS() + 8});
            }
            for (TerraceForge.StairLane lane : lanes) {
                int x2 = lane.startX() + lane.dirX() * (lane.length() - 1);
                int z2 = lane.startZ() + lane.dirZ() * (lane.length() - 1);
                ex.add(new int[]{Math.min(lane.startX(), x2) - 8, Math.max(lane.startX(), x2) + 8,
                        Math.min(lane.startZ(), z2) - 8, Math.max(lane.startZ(), z2) + 8});
            }
            for (TerraceForge.Bridge b : bridges) {
                if (b.alongX()) {
                    ex.add(new int[]{b.a0() - 6, b.a1() + 6, b.c() - 6, b.c() + 6});
                } else {
                    ex.add(new int[]{b.c() - 6, b.c() + 6, b.a0() - 6, b.a1() + 6});
                }
            }
            SpireField field = new SpireField(ex);
            Announce.say(plugin, sender, ChatColor.GRAY + "[산군시험] " + worldName + " — 기준면 y"
                    + baseY + " · 배후봉 4 (Pm h250) · 침봉 켜 3 (r200~1000) · 제외 사각 " + ex.size());
            Announce.say(plugin, sender, ChatColor.DARK_GRAY + "  타일을 나눠 먹는다 — 진행은 [산군시험·진행] 으로 남는다");
            SpirePaver paver = new SpirePaver(plugin, sender, world, field, baseY);
            TickBudget.slice(plugin, "산군시험:" + variant, paver, () -> {
                try {
                    paver.finish();
                } finally {
                    SPIRE_FORGING.set(false);
                }
            });
            started = true;
            return true;
        } finally {
            if (!started) {
                SPIRE_FORGING.set(false);
            }
        }
    }

    /** 산군 타일 순회기 — {@link SanseForge} 의 짝. max 합성이라 멱등 (재실행 무해). */
    private static final class SpirePaver implements TickBudget.Step {
        private final HoncheonMvt plugin;
        private final CommandSender sender;
        private final World world;
        private final SpireField field;
        private final int baseY;
        private final int tilesX;
        private final int totalTiles;
        private final long startNanos;

        private int index;
        private long raised;
        private long lastProgressMs;

        private long pines;
        private long shrubs;
        private long mossPatches;

        SpirePaver(HoncheonMvt plugin, CommandSender sender, World world, SpireField field, int baseY) {
            this.plugin = plugin;
            this.sender = sender;
            this.world = world;
            this.field = field;
            this.baseY = baseY;
            int span = 2 * SpireField.FIELD_R;
            this.tilesX = (span + SANSE_TILE) / SANSE_TILE;
            this.totalTiles = tilesX * tilesX;
            this.startNanos = System.nanoTime();
            this.lastProgressMs = System.currentTimeMillis();
        }

        @Override
        public boolean step() {
            // ★슬라이스 11 — 2상: ①지형 융기 → ②식생 (같은 타일 격자를 두 번 돈다)
            int grand = totalTiles * 2;
            if (index >= grand) {
                return false;
            }
            boolean vegPhase = index >= totalTiles;
            int tile = vegPhase ? index - totalTiles : index;
            int x0 = -SpireField.FIELD_R + (tile % tilesX) * SANSE_TILE;
            int z0 = -SpireField.FIELD_R + (tile / tilesX) * SANSE_TILE;
            if (!vegPhase) {
                for (int x = x0; x < x0 + SANSE_TILE; x++) {
                    for (int z = z0; z < z0 + SANSE_TILE; z++) {
                        int h = field.targetH(x, z);
                        if (h < 4) {
                            continue;   // 4 미만은 소음
                        }
                        int targetY = baseY + h;
                        int curY = world.getHighestBlockYAt(x, z);
                        for (int y = curY + 1; y <= targetY; y++) {
                            world.getBlockAt(x, y, z).setType(spireStone(x, y, z, y == targetY), false);
                            raised++;
                        }
                    }
                }
            } else {
                // ★11.5-② 시간 고삐: 식생은 r≤700 (보이는 켜 + 본산)만 — 밖 타일은 청크를
                //   건드리지 않고 통째로 건너뛴다 (1087초의 주범 = 원경 타일 재방문 청크 부하)
                int fx = Math.min(Math.abs(x0), Math.abs(x0 + SANSE_TILE - 1));
                int fz = Math.min(Math.abs(z0), Math.abs(z0 + SANSE_TILE - 1));
                if (fx * fx + fz * fz <= 700 * 700) {
                    vegetateTile(x0, z0);
                }
            }
            index++;
            long now = System.currentTimeMillis();
            if (now - lastProgressMs >= 3000L || index == grand) {
                lastProgressMs = now;
                Announce.progress(plugin, sender, ChatColor.GRAY + "[산군시험·진행] " + index + "/"
                        + grand + (vegPhase ? " 식생" : " 타일") + " (" + (100L * index / grand)
                        + "%) · 융기 " + raised + " · 소나무 " + pines
                        + " · " + (System.nanoTime() - startNanos) / 1_000_000_000L + "초");
            }
            return index < grand;
        }

        /** 식생이 앉을 수 있는 자연 상면 — 조성 석재(석전·박석 등 보행면)는 안 된다 */
        private static final java.util.Set<Material> PLANTABLE = java.util.EnumSet.of(
                Material.STONE, Material.ANDESITE, Material.TUFF, Material.CALCITE,
                Material.DRIPSTONE_BLOCK, Material.SANDSTONE, Material.MOSS_BLOCK,
                Material.COBBLESTONE, Material.MOSSY_COBBLESTONE,
                Material.GRASS_BLOCK, Material.DIRT);

        /**
         * ★슬라이스 11-①·③ 식생 상 — 실측 문법 (실측표 §13 · 1·8·9·12호): 완사면은 짙은
         * 소나무 군락 · 어깨 턱은 점식 · 절벽은 이끼 띠와 턱 관목 · 침봉 소평두 마루 소나무.
         * 본산권(캠퍼스 밑 산세)도 같은 문법 — 산군과 본산의 결이 갈라지지 않는다.
         * 결정론(해시 표집 1/29) · 제외 사각(캠퍼스·다리)·조성 석재 상면 무침범.
         */
        private void vegetateTile(int x0, int z0) {
            for (int x = x0; x < x0 + SANSE_TILE; x++) {
                for (int z = z0; z < z0 + SANSE_TILE; z++) {
                    long h = ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) z * 0x165667B19E3779F9L);
                    h ^= h >>> 31;
                    if (Math.floorMod(h, 37) != 0 || field.excluded(x, z)) {
                        continue;   // ★11.5-② 표집 1/29→1/37 (시간 고삐 1안)
                    }
                    int top = world.getHighestBlockYAt(x, z);
                    Material ground = world.getBlockAt(x, top, z).getType();
                    if (!PLANTABLE.contains(ground)) {
                        continue;   // 보행면·건축 위 금지 — 재료가 곧 계약
                    }
                    // ★11.5-② 산몸 판정 경량화 — targetH(무거움) 대신 실표고 (같은 뜻: 산 위인가)
                    boolean onBody = top > baseY + 20;
                    if (!onBody && Math.floorMod(h >> 8, 100) < 85) {
                        continue;   // 들판은 드문드문
                    }
                    int slope = 0;
                    for (int[] n : new int[][]{{3, 0}, {-3, 0}, {0, 3}, {0, -3}}) {
                        slope = Math.max(slope,
                                Math.abs(world.getHighestBlockYAt(x + n[0], z + n[1]) - top));
                    }
                    if (slope <= 2) {                        // 완사면·마루 — 군락 (짙게)
                        pine(x, top, z, h);
                        if (Math.floorMod(h >> 16, 100) < 55) {
                            pine(x + 2 + (int) Math.floorMod(h >> 20, 3), top, z + 1, h >> 24);
                        }
                        mossGround(x, top, z, h);
                    } else if (slope <= 5) {                 // 어깨 턱 — 점식
                        if (Math.floorMod(h >> 16, 100) < 55) {
                            pine(x, top, z, h);
                        } else {
                            shrub(x, top, z, h);
                        }
                    } else {                                 // 절벽 — 이끼 띠 · 턱 관목 점점이
                        world.getBlockAt(x, top, z).setType(Material.MOSS_BLOCK, false);
                        mossPatches++;
                        if (Math.floorMod(h >> 16, 100) < 30) {
                            shrub(x, top, z, h);
                        }
                    }
                }
            }
        }

        /** 소나무 — 껍질 침엽(SPRUCE_WOOD — 건물 재료와 층위 분리) + 잎 (몸통 곁이라 안 삭는다) */
        private void pine(int x, int top, int z, long h) {
            int hgt = 3 + (int) Math.floorMod(h >> 32, 5);   // 3~7
            int g = world.getHighestBlockYAt(x, z);
            if (g <= world.getMinHeight()
                    || !PLANTABLE.contains(world.getBlockAt(x, g, z).getType())) {
                return;
            }
            for (int dy = 1; dy <= hgt; dy++) {
                world.getBlockAt(x, g + dy, z).setType(Material.SPRUCE_WOOD, false);
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) <= 1) {
                        world.getBlockAt(x + dx, g + hgt, z + dz).setType(Material.SPRUCE_LEAVES, false);
                        if (hgt >= 5) {
                            world.getBlockAt(x + dx, g + hgt - 2, z + dz)
                                    .setType(Material.SPRUCE_LEAVES, false);
                        }
                    }
                }
            }
            world.getBlockAt(x, g + hgt + 1, z).setType(Material.SPRUCE_LEAVES, false);
            pines++;
        }

        /** 턱 관목 — 이끼 위 진달래/양치 (잎 삭음 없는 식물 블록) */
        private void shrub(int x, int top, int z, long h) {
            world.getBlockAt(x, top, z).setType(Material.MOSS_BLOCK, false);
            Material m = Math.floorMod(h >> 36, 100) < 55 ? Material.AZALEA : Material.FERN;
            world.getBlockAt(x, top + 1, z).setType(m, false);
            shrubs++;
        }

        /** 완사면 바닥 결 — 이끼 자리 + 풀 (군락의 발치) */
        private void mossGround(int x, int top, int z, long h) {
            for (int[] o : new int[][]{{1, -1}, {-1, 1}, {2, 1}}) {
                int gx = x + o[0];
                int gz = z + o[1];
                int gy = world.getHighestBlockYAt(gx, gz);
                if (PLANTABLE.contains(world.getBlockAt(gx, gy, gz).getType())) {
                    world.getBlockAt(gx, gy, gz).setType(Material.MOSS_BLOCK, false);
                    if (Math.floorMod(h >> 40, 2) == 0) {
                        world.getBlockAt(gx, gy + 1, gz).setType(Material.SHORT_GRASS, false);
                    }
                    mossPatches++;
                }
            }
        }

        /** 침봉·산체 결 — ★슬라이스 10-② 웜톤 이관: 정본은 {@link SpireField#stone} (눈이 잰다) */
        private Material spireStone(int x, int y, int z, boolean cap) {
            return SpireField.stone(x, y, z, cap);
        }

        /** 완료 — 검수(배후봉 마루·켜 표본·다리 회랑) + census */
        void finish() {
            long secs = (System.nanoTime() - startNanos) / 1_000_000_000L;
            int bad = 0;
            StringBuilder peaks = new StringBuilder();
            for (SpireField.Ridge c : SpireField.backPeaks()) {
                int top = world.getHighestBlockYAt(c.cx(), c.cz());
                if (top < baseY + c.topH() - 3) {
                    bad++;
                    Announce.fail(plugin, sender, "[산군시험] ★배후봉 " + c.id() + " 마루 y" + top
                            + " < 목표 y" + (baseY + c.topH()) + " — 실측 비가 안 섰다");
                }
                if (peaks.length() > 0) {
                    peaks.append(" · ");
                }
                peaks.append(c.id()).append(" y").append(top);
            }
            if (bad == 0) {
                Announce.say(plugin, sender, ChatColor.GOLD + "[산군시험] 산군이 섰다 — " + world.getName()
                        + " · 융기 " + raised + " 블록 · " + secs + "초");
            }
            Announce.say(plugin, sender, ChatColor.GRAY + "  배후봉: " + peaks
                    + " (캠퍼스 정상단 y" + (baseY + 148) + " — 실측 §4: 주봉이 +80 위)");
            Announce.say(plugin, sender, ChatColor.GRAY + "  식생: 소나무 " + pines + " · 관목 " + shrubs
                    + " · 이끼 " + mossPatches + " (슬라이스 11 — 완사면 군락·턱 점식·절벽 이끼)");
            Announce.say(plugin, sender, ChatColor.GRAY + "  다음: /혼천 캠퍼스시험 hwasan — 캠퍼스가 산군 잔재를 걷고 앉는다");
        }
    }

    /**
     * 캠퍼스 순회기 — {@link TrailPaver} 의 짝. 계획된 패드를 하나씩 앉히고, 패드가 다 서면
     * 계단을 하나씩 놓고, 끝나면 <b>검수부터</b> 소리 내어 읽는다.
     */
    private static final class CampusPaver implements TickBudget.Step {
        private final HoncheonMvt plugin;
        private final CommandSender sender;
        private final World world;
        private final TerraceForge.Plan plan;
        private final TerraceForge.Tally tally = new TerraceForge.Tally();
        private final HwasanCampusBuilder.Tally buildTally = new HwasanCampusBuilder.Tally();
        private final int padCount;
        private final int laneCount;
        private final int bridgeCount;
        private final int total;
        private final long startNanos;

        private int index;

        CampusPaver(HoncheonMvt plugin, CommandSender sender, World world, TerraceForge.Plan plan) {
            this.plugin = plugin;
            this.sender = sender;
            this.world = world;
            this.plan = plan;
            this.padCount = plan.pads().size();
            this.laneCount = plan.lanes().size();
            this.bridgeCount = plan.bridges().size();
            this.total = padCount + laneCount + bridgeCount + 1 + padCount + padCount;   // 패드 → 계단 → 다리 → 접근로 → 건물 → 조경 (6상 · 9b)
            this.startNanos = System.nanoTime();
        }

        @Override
        public boolean step() {
            if (index >= total) {
                return false;
            }
            String what;
            if (index < padCount) {
                TerraceForge.Pad p = plan.pads().get(index);
                TerraceForge.pavePad(world, plan, p, tally);
                what = "패드 " + p.spec().zone() + " " + p.spec().name() + " (y" + p.y()
                        + " · " + p.spec().width() + "×" + p.spec().depth() + ")";
            } else if (index < padCount + laneCount) {
                TerraceForge.StairLane lane = plan.lanes().get(index - padCount);
                TerraceForge.paveStair(world, lane, tally);
                what = "계단 " + lane.link().upperZone() + "→" + lane.link().lowerZone()
                        + " (낙차 " + (lane.topY() - lane.lowY()) + " · 디딤 " + lane.treads()
                        + (lane.walk() > 0 ? " · 보도 " + lane.walk() : "") + ")";
            } else if (index < padCount + laneCount + bridgeCount) {
                TerraceForge.Bridge b = plan.bridges().get(index - padCount - laneCount);
                TerraceForge.paveBridge(world, b, tally);
                what = "다리 " + b.spec().name() + " (스팬 " + b.span() + " · y" + b.y()
                        + " · 교각 " + b.pierOffsets().size() + ")";
            } else if (index < padCount + laneCount + bridgeCount + 1) {
                TerraceForge.paveApproach(world, plan, tally);   // ★9b — 도착하는 과정 (대계단·참·소문·비석)
                what = "접근로 (남쪽 시퀀스 " + (plan.approach() != null
                        ? TerraceForge.APPROACH_LEN + "칸" : "없음") + ")";
            } else if (index < padCount + laneCount + bridgeCount + 1 + padCount) {
                TerraceForge.Pad p = plan.pads().get(index - padCount - laneCount - bridgeCount - 1);
                HwasanCampusBuilder.buildZone(world, plan, p, buildTally);
                what = "건물 " + p.spec().zone() + " " + p.spec().name();
            } else {
                TerraceForge.Pad p = plan.pads().get(index - padCount - laneCount - bridgeCount - 1 - padCount);
                HwasanCampusBuilder.decorate(world, plan, p, buildTally);
                what = "조경 " + p.spec().zone() + " " + p.spec().name();
            }
            index++;
            Announce.progress(plugin, sender, ChatColor.GRAY + "[캠퍼스시험·진행] " + index + "/" + total
                    + " " + what + " · " + (System.nanoTime() - startNanos) / 1_000_000_000L + "초");
            return index < total;
        }

        /** 조성 완료 — ★검수 먼저 (위반이면 소리친다) → census → 산문(1구역) 남단 텔레포트 */
        void finish() {
            TerraceForge.Audit audit = TerraceForge.audit(world, plan, HwasanCampusBuilder::auditSkipBoxes);
            java.util.List<String> leaks = HwasanCampusBuilder.auditBuildings(world, plan);
            long secs = (System.nanoTime() - startNanos) / 1_000_000_000L;
            if (audit.clean() && leaks.isEmpty()) {
                Announce.say(plugin, sender, ChatColor.GOLD + "[캠퍼스시험] 화산 캠퍼스 패드 " + padCount
                        + " · 계단 " + laneCount + " · 다리 " + bridgeCount + " · 구역 건물이 앉았다 — "
                        + world.getName() + " · 검수 깨끗 (열 " + audit.checkedCols() + " · 유출 0)");
            } else {
                Announce.fail(plugin, sender, "[캠퍼스시험] ★검수 위반 — 평탄 " + audit.flatViolations()
                        + " · 접지(허공) " + audit.floatViolations() + " · 보행 단차 " + audit.walkBreaks()
                        + " · 패드 밖 유출 " + leaks.size()
                        + " (열 " + audit.checkedCols() + ") — 아래 표본을 보라");
                for (String n : audit.notes()) {
                    Announce.fail(plugin, sender, "  · " + n);
                }
                for (String n : leaks) {
                    Announce.fail(plugin, sender, "  · " + n);
                }
            }
            Announce.say(plugin, sender, ChatColor.GRAY + "  포장 " + tally.pavement + " · 속채움 "
                    + tally.core + " · 옹벽 결 " + tally.wallFace + " · 여장 " + tally.parapet
                    + " · 계단 " + tally.stairTreads + " · 깎음 " + tally.cut + " · 등롱 " + tally.lanterns
                    + " · " + secs + "초");
            Announce.say(plugin, sender, ChatColor.GRAY + "  건물: 홀 " + buildTally.halls + " · 문루 "
                    + buildTally.gates + " · 정자 " + buildTally.pavilions + " · 목인 " + buildTally.dummies
                    + " · 시렁 " + buildTally.racks + " · 탑 " + buildTally.towers
                    + " · 블록 " + buildTally.blocks);
            Announce.say(plugin, sender, ChatColor.GRAY + "  조경: 매화 " + buildTally.plums + " · 소나무 "
                    + buildTally.pines + " · 덩굴·지의 " + buildTally.vines + " · 소품 " + buildTally.props);
            TerraceForge.Pad gate = plan.pads().get(0);   // 명세 첫 줄 = 1 산문
            TerraceForge.Pad top = plan.pads().stream()
                    .max(java.util.Comparator.comparingInt(TerraceForge.Pad::y)).orElse(gate);
            Announce.say(plugin, sender, ChatColor.GRAY + "  " + gate.spec().name() + " y" + gate.y()
                    + " → " + top.spec().name() + " y" + top.y() + " · 총 오름 " + (top.y() - gate.y())
                    + "칸 · 다음 슬라이스: 구역 배치기(조닝 3색)·곁봉·운무교");
            int sx = gate.x0() + gate.spec().width() / 2;
            int sz = gate.zS() - 2;
            int sy = gate.y() + 1;
            if (sender instanceof Player player) {
                // 북(-z)을 바라보게 = 척추를 올려다본다 (yaw 180 = 북향)
                player.teleport(new Location(world, sx + 0.5, sy, sz + 0.5, 180f, 0f));
                Announce.say(plugin, sender, ChatColor.GREEN + "  산문 남단에 세운다 — 북으로 척추를 올려다본다 ("
                        + sx + ", " + sy + ", " + sz + ")");
            } else {
                Announce.say(plugin, sender, ChatColor.GRAY + "  산문 남단: /tp " + sx + " " + sy + " "
                        + sz + " (콘솔 — 좌표만 안내 · 월드 " + world.getName() + ")");
            }
        }
    }


    /** /혼천 세계조성 — 등록된 지역을 제 좌표에 짓는다 (관리자). 지금은 청하현 일대 */
    private boolean buildWorld(CommandSender sender) {
        if (sender instanceof Player p && !p.isOp()) {
            return true;
        }
        if (forgeWorldBarred(sender, "세계조성")) {
            return true;
        }
        WorldMap map = plugin.worldMap();
        if (map == null) {
            sender.sendMessage(ChatColor.RED + "world_map.yml 이 없다.");
            return true;
        }
        WorldMap.Place home = map.place("cheongha_hyeon");
        if (home == null) {
            sender.sendMessage(ChatColor.RED + "청하현이 지도에 없다.");
            return true;
        }
        // 콘솔 가능 — 지면 높이는 지도가 계산한다 (플레이어 발밑이 아니라 지형이 정한다).
        // 부지 탐색은 틱을 나눠 먹는다 (새 월드의 원점도 아직 생성되지 않은 땅이다 — 표본이 곧 청크 생성이다).
        org.bukkit.World world = sender instanceof Player p2 ? p2.getWorld()
                : org.bukkit.Bukkit.getWorlds().get(0);
        sender.sendMessage(ChatColor.GRAY + "청하현 부지를 찾는다 — 지형을 표본한다");
        new SiteProbe(plugin, map, home, world, site -> {
            sender.sendMessage(ChatColor.GRAY + "청하현 부지 (" + site.x() + ", " + site.z()
                    + ") · 지면 y" + site.groundY()
                    + " · 지형 점수 " + site.fit().score() + " (" + site.fit().verdict() + ")");
            // 조성은 **틱을 나눠 먹는다** — 계측이 잡았다: 한 틱 평균 MSPT **625ms** (목표 40ms 의 15배).
            //   총량은 못 깎는다(마을을 지으려면 블록을 놓아야 한다). 깎을 수 있는 건 **한 틱에 얼마나 하냐**다.
            //   조성기는 **한 줄도 안 고친다** — 대역 월드를 주고 쓰기를 큐에 적어 메인이 20ms 씩 집행한다.
            java.util.List<Zone> zones = new java.util.ArrayList<>();
            if (TickBudget.busy()) {
                Announce.warn(plugin, sender, "이미 조성이 돌고 있다 — 끝난 뒤에 다시 쳐라.");
                return;
            }
            TickBudget.preload(plugin, world, site.x(), site.z(), 96).thenRun(() ->
                    org.bukkit.Bukkit.getScheduler().runTask(plugin, () ->
                            TickBudget.build(plugin, "조성:청하현", world,
                                    w -> CheonghaBuilder.build(w, site.x(), site.groundY(), site.z(), zones),
                                    built -> {
                                        TickBudget.rebind(built, world);   // 앵커가 대역 월드를 물고 있다
                                        plugin.setAnchors(built);
                                        plugin.setZones(zones);
                                        Announce.say(plugin, sender, ChatColor.GOLD
                                                + "[세계조성] 세계가 섰다 — 청하현 (" + site.x() + ", "
                                                + site.groundY() + ", " + site.z() + ") · 장소 "
                                                + built.size() + "곳 · 구역 " + zones.size() + "곳");
                                        Announce.say(plugin, sender, ChatColor.GRAY
                                                + "원거리 지역: /혼천 지역조성 <id> (예: nokrim_sochae)");
                                    },
                                    // ★ 같은 병이 여기에도 있었다 — 청하현이 터져도 콘솔은 조용했을 것이다
                                    err -> Announce.fail(plugin, sender, "★ 조성 실패 — 청하현: " + err),
                                    line -> Announce.progress(plugin, sender, line))));
        }).runTaskTimer(plugin, 1L, 1L);
        return true;
    }

    /** /혼천 사냥터 — 구역별 개체군 (정원 대비 현재) */
    private boolean census(CommandSender sender) {
        plugin.hunting().census().forEach(sender::sendMessage);
        return true;
    }

    /** /혼천 비무 <플레이어|수락|항복> — 죽지 않는 싸움 (표국 마당의 곽진은 우클릭) */
    private boolean spar(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        Sparring sparring = plugin.hunting().sparring();
        if (args.length < 2) {
            player.sendMessage(ChatColor.GRAY
                    + "/혼천 비무 <플레이어> | 수락 | 항복   (표국 마당의 곽진은 우클릭)");
        } else if ("수락".equals(args[1])) {
            sparring.accept(player);
        } else if ("항복".equals(args[1])) {
            sparring.yield(player);
        } else {
            Player target = plugin.getServer().getPlayer(args[1]);
            if (target == null) {
                player.sendMessage(ChatColor.GRAY + "그런 사람이 없다.");
            } else {
                sparring.challenge(player, target);
            }
        }
        return true;
    }

    /** /혼천 소환 <id> — 영물은 등록제다 (관리자) */
    private boolean summon(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp()) {
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(ChatColor.GRAY + "/혼천 소환 <"
                    + String.join("|", HuntingGrounds.registry()) + ">");
            return true;
        }
        var spawned = plugin.hunting().summon(args[1], player.getLocation());
        player.sendMessage(spawned == null ? ChatColor.RED + "등록되지 않은 개체: " + args[1]
                : ChatColor.GOLD + spawned.getCustomName() + ChatColor.GRAY + " 이(가) 섰다.");
        return true;
    }

    /** /혼천 재련 — 손에 든 병기의 품을 한 단계 올린다 (야철수 대역, 검증용) */
    private boolean reforgeWeapon(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        Weapons.ReforgeResult result = Weapons.reforge(player.getInventory().getItemInMainHand(), 2,
                java.util.concurrent.ThreadLocalRandom.current());   // config 등록 대기: 야철수 솜씨 2
        switch (result.outcome()) {
            case 성공 -> {
                player.getInventory().setItemInMainHand(result.item());
                player.sendMessage(ChatColor.GOLD + result.message());
            }
            case 손상 -> {
                player.getInventory().setItemInMainHand(result.item());
                player.sendMessage(ChatColor.YELLOW + result.message());
            }
            case 파손 -> {
                player.getInventory().setItemInMainHand(null);   // 쇠가 갈라졌다
                player.sendMessage(ChatColor.RED + result.message());
            }
            default -> player.sendMessage(ChatColor.GRAY + result.message());
        }
        return true;
    }

    /** /혼천 각인 <파사|한철|경명|음양쌍인|탈혼> — 슬롯 안에서만 */
    private boolean inscribeWeapon(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(ChatColor.GRAY + "/혼천 각인 <파사|한철|경명|음양쌍인|탈혼>");
            return true;
        }
        Weapons.ReforgeResult result =
                Weapons.inscribe(player.getInventory().getItemInMainHand(), args[1]);
        if (result.outcome() == Weapons.Reforge.성공) {
            player.getInventory().setItemInMainHand(result.item());
            player.sendMessage(ChatColor.LIGHT_PURPLE + result.message());
        } else {
            player.sendMessage(ChatColor.GRAY + result.message());
        }
        return true;
    }

    /** /혼천 격돌 — 애병 카운터 +1 (전투 훅이 설 때까지의 검증 수단) */
    private boolean recordClash(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        org.bukkit.inventory.ItemStack hand = player.getInventory().getItemInMainHand();
        if (!Weapons.isWeapon(hand)) {
            player.sendMessage(ChatColor.GRAY + "손에 병기가 없다.");
            return true;
        }
        player.getInventory().setItemInMainHand(Weapons.recordClash(hand, player.getUniqueId()));
        player.sendMessage(ChatColor.GRAY + "격돌 "
                + Weapons.clashesOf(player.getInventory().getItemInMainHand()) + "회");
        return true;
    }

    /** /혼천 병기상 — 장쇠의 병기 좌판 */
    private boolean weaponShop(CommandSender sender) {
        if (sender instanceof Player player) {
            WeaponShop.open(plugin, player);
        }
        return true;
    }

    /** 든 병기를 땅에 크게 세운다 (전시) — 빈손이면 가까운 제 전시대를 회수한다 (SkillListener 가 판다) */
    private boolean weaponStand(CommandSender sender) {
        if (sender instanceof Player player) {
            plugin.skills().weaponStandCommand(player);
        } else {
            sender.sendMessage(ChatColor.GRAY + "병기전시는 플레이어만 쓴다");
        }
        return true;
    }

    /** /혼천 운기 — 운기조식 1구간 (내력 회복) */
    private boolean meditate(CommandSender sender) {
        if (sender instanceof Player player) {
            plugin.skills().meditate(player);
            // 뿌리내림 (B-178) — 시도 자체를 센다: 개화 전의 거절("단전이 비어 있다")도 가르침이다
            plugin.tutorial().bump(player, "운기");
        }
        return true;
    }

    /**
     * ★ 조성의 세계 가드 (B-126) — <b>서 있는 자리가 곧 표적이 되는 명령은, 자리부터 묻는다.</b>
     *
     * <p>실사고 (2026-07-14 밤): 나루 세계에서 {@code /혼천 조성} → 마을이 나루 심부(y≈−56)에
     * 재조성됐고 <b>앵커 9종·구역·지형 영수증까지 덮였다.</b> 조성·갈아엎기는 플레이어의 세계를
     * 표적으로 삼는다 — 나루·연무장·그 밖의 부속 세계에서는 거부하고 <b>이유를 말한다.</b>
     * 콘솔은 제 세계가 없으므로 각 명령의 기본(본세계·앵커의 세계)이 정한다 — 여기서 안 막는다.
     *
     * @return 막았으면 true (명령은 그대로 돌아선다)
     */
    private static boolean forgeWorldBarred(CommandSender sender, String cmd) {
        if (!(sender instanceof Player p)) {
            return false;
        }
        org.bukkit.World w = p.getWorld();
        String why;
        if (Antechamber.isAntechamber(w)) {
            why = "여기는 나루다";
        } else if (Dojang.isDojang(w)) {
            why = "여기는 연무장이다";
        } else if (!w.equals(org.bukkit.Bukkit.getWorlds().get(0))) {
            why = "여기는 강호의 본세계가 아니다 (" + w.getName() + ")";
        } else {
            return false;
        }
        p.sendMessage(ChatColor.RED + "[" + cmd + "] " + why + " — 조성은 강호의 땅에서 쳐라.");
        p.sendMessage(ChatColor.GRAY + "  (나루에서 친 조성이 나루 심부에 마을을 세운 적 있다 — 그래서 이 문이 섰다)");
        return true;
    }

    /** 청하현 조성 (M2b) — 관리자 전용, 재조성 = 같은 마을 (결정론 생성) */
    private boolean buildTown(CommandSender sender, String[] args) {
        if (forgeWorldBarred(sender, "조성")) {
            return true;
        }
        java.util.List<Zone> zones = new java.util.ArrayList<>();
        Map<String, Location> anchors;
        // 좌표 지정: /혼천 조성 <x> <y> <z> — 마을을 특정 자리에 못박는다.
        // (플레이어가 엉뚱한 데 서서 조성하면 마을이 통째로 이사한다 — 물가·저지대면 부지가 망가진다)
        if (args.length >= 4) {
            org.bukkit.World world = sender instanceof Player p ? p.getWorld()
                    : org.bukkit.Bukkit.getWorlds().get(0);
            int bx = Integer.parseInt(args[1]);
            int by = Integer.parseInt(args[2]);
            int bz = Integer.parseInt(args[3]);
            // ★ 기준면 안전핀 (B-126 · sweepTargetSane 결) — 조성 표면은 심부가 아니다.
            //   앵커를 덮어쓰기 전의 마지막 문이다 (실사고의 y 는 −56 이었다).
            if (by < 0) {
                sender.sendMessage(ChatColor.RED + "[조성] 기준면이 심부다 (y" + by
                        + " < 0) — 조성 표면이 아니다. 표적을 다시 재라.");
                return true;
            }
            anchors = CheonghaBuilder.build(world, bx, by, bz, zones);
            plugin.setAnchors(anchors);
            plugin.setZones(zones);
            sender.sendMessage(ChatColor.GOLD + "청하현이 섰다 (" + bx + ", " + by + ", " + bz + ") — 장소 "
                    + anchors.size() + "곳 · 구역 " + zones.size() + "곳");
            return true;
        }
        if (sender instanceof Player player) {
            if (!player.isOp()) {
                player.sendMessage(ChatColor.RED + "조성은 관리자의 몫이다.");
                return true;
            }
            if (player.getLocation().getBlockY() < 0) {
                // ★ 기준면 안전핀 — 강호의 동굴 심부에 서서 쳐도 마을이 묻힌다 (B-126)
                player.sendMessage(ChatColor.RED + "[조성] 발밑이 심부다 (y"
                        + player.getLocation().getBlockY() + " < 0) — 지면에 서서 쳐라.");
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
        for (String line : TownAudit.audit(center.getWorld(), plugin.anchors(), plugin.zones(),
                center.getBlockX(), center.getBlockY() - 1, center.getBlockZ())) {
            sender.sendMessage(line);
        }
        return true;
    }

    /**
     * ★ <b>/혼천 앵커검사</b> — 앵커마다 <b>사람이 설 수 있는가</b>를 잰다 (콘솔 가능).
     *
     * <p>발밑이 단단한가 · 몸이 들어가는가 · <b>걸어 나갈 수 있는가</b>. 셋째가 <b>우물을 거르는 조건</b>이다:
     * 우물은 앞의 둘을 만족할 수 있으나 사방이 담이라 <b>못 나온다</b> ({@link Standing}).
     */
    private boolean anchorCheck(CommandSender sender) {
        Map<String, Location> anchors = plugin.anchors();
        if (anchors.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "앵커가 없다 — 먼저 /혼천 조성");
            return true;
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.List<String> violations = new java.util.ArrayList<>();
        TownAudit.standable(out, violations, anchors);
        // ★로그 먼저 (침묵 금지 — RCON 의 sender 는 응답을 삼킬 수 있다: 2026-07-25 실증.
        //   로그는 아무도 안 죽는다 — §4 조성의 같은 계율)
        out.forEach(line -> plugin.getLogger().info("[앵커검사] " + ChatColor.stripColor(line)));
        out.forEach(sender::sendMessage);
        if (violations.isEmpty()) {
            plugin.getLogger().info("[앵커검사] 앵커 " + anchors.size() + "곳 전부 설 수 있다.");
            sender.sendMessage(ChatColor.GREEN + "앵커 " + anchors.size() + "곳 전부 설 수 있다.");
        } else {
            plugin.getLogger().warning("[앵커검사] ★ 착지 불가 " + violations.size() + "건 — "
                    + String.join(", ", violations));
            sender.sendMessage(ChatColor.RED + "★ 착지 불가 " + violations.size() + "건 — "
                    + ChatColor.WHITE + "/혼천 앵커재측" + ChatColor.GRAY + " 로 고친다.");
        }
        return true;
    }

    /**
     * ★ <b>/혼천 앵커재측</b> — 못 서는 앵커를 <b>설 수 있는 자리로 다시 박는다</b> (관리자 · anchors.yml 저장).
     *
     * <p><b>「장터」 는 옮기지 않는다.</b> 그 앵커는 장소 표식이 아니라 <b>마을 원점</b>이다 — 콘솔 재조성
     * ({@code /혼천 조성})·검수·조감이 {@code anchor("장터").getBlockY() - 1} 을 원점으로 삼는다.
     * 옮기면 다음 재조성 때 <b>마을이 통째로 이사한다</b>. 그래서 표식은 그대로 두고, 대신
     * <b>내릴 때마다 잰다</b> ({@link Standing#landing}) — 도강·귀환·출행 세 곳 모두. 사람은 우물이 아니라
     * 우물 <b>곁</b>에 내린다. 이 사실을 여기서 <b>소리내어 말한다</b> (조용한 기본값 금지).
     */
    private boolean anchorRemeasure(CommandSender sender) {
        if (sender instanceof Player p && !p.isOp()) {
            p.sendMessage(ChatColor.RED + "앵커는 관리자의 몫이다.");
            return true;
        }
        Map<String, Location> anchors = plugin.anchors();
        if (anchors.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "앵커가 없다 — 먼저 /혼천 조성");
            return true;
        }
        Map<String, Location> fixed = new java.util.LinkedHashMap<>(anchors);
        int moved = 0, kept = 0, failed = 0;
        for (Map.Entry<String, Location> e : anchors.entrySet()) {
            String k = e.getKey();
            Location at = e.getValue();
            if (at == null || at.getWorld() == null) {
                continue;
            }
            Standing.Verdict v = Standing.measure(at);
            if (v.ok()) {
                continue;
            }
            Location spot = Standing.landing(at);
            if (spot == null) {
                sender.sendMessage(ChatColor.RED + "✘ " + k + " " + Standing.describe(at) + " — "
                        + v.why() + " · 둘레 " + Standing.SEARCH_R + "칸에도 설 자리가 없다");
                failed++;
                continue;
            }
            if ("장터".equals(k)) {
                // ★ 원점은 못 옮긴다 — 옮기면 다음 콘솔 재조성 때 마을이 이사한다.
                sender.sendMessage(ChatColor.YELLOW + "◆ 장터 " + Standing.describe(at) + " — " + v.why());
                sender.sendMessage(ChatColor.GRAY + "   장터 앵커는 **마을 원점 표식**이라 옮기지 않는다 "
                        + "(옮기면 재조성 때 마을이 이사한다).");
                sender.sendMessage(ChatColor.GRAY + "   대신 도강·귀환·출행이 내릴 때마다 잰다 → 착지 "
                        + ChatColor.WHITE + Standing.describe(spot));
                kept++;
                continue;
            }
            fixed.put(k, spot);
            sender.sendMessage(ChatColor.GREEN + "✔ " + k + " " + Standing.describe(at) + " → "
                    + Standing.describe(spot) + ChatColor.GRAY + " (" + v.why() + ")");
            moved++;
        }
        if (moved > 0) {
            plugin.setAnchors(fixed);   // anchors.yml 에 적는다 — 재기동을 넘어 산다
        }
        // ★로그 먼저 (침묵 금지 — RCON 응답 유실 실증 2026-07-25. 로그는 아무도 안 죽는다)
        plugin.getLogger().info("[앵커재측] 다시 박음 " + moved + " · 원점 유지 " + kept
                + " · 실패 " + failed + " · 멀쩡 " + (anchors.size() - moved - kept - failed));
        sender.sendMessage(ChatColor.GOLD + "앵커 재측 — 다시 박음 " + moved + " · 원점 유지 " + kept
                + " · 실패 " + failed + " · 멀쩡 " + (anchors.size() - moved - kept - failed));
        return true;
    }

    /** 조감 — 탑다운·아이소메트릭·건물별 PNG 렌더 (plugins/HoncheonMVT/render/). 콘솔 가능 */
    /**
     * /혼천 조감 [지역id] — 조감도 PNG (콘솔 가능).
     *
     * <p>인자가 있으면 <b>그 지역</b>을 그린다. 원거리 조성기가 지은 것을 볼 눈이 없으면
     * "섰다"는 로그만 믿게 된다 — 루프의 눈이 없는 조성은 검산이 아니다.
     */
    private boolean renderTown(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            return renderRegion(sender, args[1]);
        }
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

    /**
     * /혼천 지역검수 &lt;id&gt; — 지역의 자동 검산 (콘솔 가능).
     *
     * <p>청하현에는 검수 12종이 있는데 산채·문파에는 아무 눈도 없었다. 조감은 "보기에 이상하다"까지만
     * 말하고 <b>"오를 수 없다"는 말은 못 한다</b>. 지역 검수의 첫 질문이 그것이다 — 정말 걸어 올라가지는가.
     */
    private boolean auditRegion(CommandSender sender, String[] args) {
        WorldMap map = plugin.worldMap();
        if (map == null || args.length < 2) {
            sender.sendMessage(ChatColor.GRAY + "/혼천 지역검수 <지역id>  (예: hwasan · nokrim_sochae)");
            return true;
        }
        WorldMap.Place place = map.place(args[1]);
        if (place == null) {
            sender.sendMessage(ChatColor.RED + "지도에 없는 지역: " + args[1]);
            return true;
        }
        Zone zone = plugin.zones().stream().filter(z -> z.name().equals(place.name())).findFirst().orElse(null);
        if (zone == null) {
            sender.sendMessage(ChatColor.RED + place.name() + " 은 아직 서지 않았다 — /혼천 지역조성 " + args[1]);
            return true;
        }
        World world = org.bukkit.Bukkit.getWorld(zone.world());
        for (String line : RegionAudit.audit(world, place, zone)) {
            sender.sendMessage(line);
            plugin.getLogger().info("[지역검수] " + org.bukkit.ChatColor.stripColor(line));
        }
        return true;
    }

    /**
     * /혼천 원형대조 [지역id | 시험] — <b>집들이 서로 구별되는가</b>를 잰다 (콘솔 가능).
     *
     * <p>★ <b>이 명령이 오늘의 병을 겨눈다.</b> 기존 검수({@code /혼천 지역검수})는 <b>한 집씩</b> 보므로
     * "소림에 매화 20장"을 통과시켰다 — 계약이 「도관」이었고, 계약대로 서 있었기 때문이다.
     * 병은 한 집 안이 아니라 <b>집들 사이</b>에 있었다.
     *
     * <ul>
     *   <li><b>인자 없음</b> — 원형 21종의 계약을 통째로 견준다 (월드가 없어도 돈다).
     *       두 원형의 계약이 80% 이상 겹치면 <b>그 둘은 한 집</b>이라고 짖는다</li>
     *   <li><b>{@code 시험}</b> — ★ <b>눈을 시험한다.</b> 거짓말 셋을 <b>일부러 지어내</b> 눈에 먹이고
     *       (소림을 도관으로 · 곤륜에 회벽 · 산채에 석축) 짖는지 본다. 그리고 <b>참말에는 안 짖는지</b>도</li>
     *   <li><b>{@code <지역id>}</b> — 실제로 선 집을 본다: <b>금지 자재가 섞였는가</b></li>
     * </ul>
     */
    private boolean compareArchetypes(CommandSender sender, String[] args) {
        java.util.List<String> lines;
        if (args.length >= 2 && "시험".equals(args[1])) {
            lines = ArchetypeAudit.selfTest();
        } else if (args.length >= 2) {
            WorldMap map = plugin.worldMap();
            WorldMap.Place place = map == null ? null : map.place(args[1]);
            if (place == null) {
                sender.sendMessage(ChatColor.RED + "지도에 없는 지역: " + args[1]);
                return true;
            }
            Zone zone = plugin.zones().stream().filter(z -> z.name().equals(place.name()))
                    .findFirst().orElse(null);
            if (zone == null) {
                sender.sendMessage(ChatColor.RED + place.name() + " 은 아직 서지 않았다 — /혼천 지역조성 "
                        + args[1]);
                return true;
            }
            lines = ArchetypeAudit.audit(org.bukkit.Bukkit.getWorld(zone.world()), place, zone);
        } else {
            lines = ArchetypeAudit.distinctness();
        }
        for (String line : lines) {
            sender.sendMessage(line);
            plugin.getLogger().info("[원형대조] " + org.bukkit.ChatColor.stripColor(line));
        }
        return true;
    }

    /**
     * /혼천 환경검수 [지역id] — <b>조성물과 자연의 이음매</b>를 잰다 (콘솔 가능).
     *
     * <p>지금까지의 검수는 전부 <b>안</b>을 봤다. 그런데 인게임에서 드러난 파탄은 <b>바깥</b>이었다 —
     * 바닥 밑에서 잘린 동굴, 산 위에 남은 웅덩이, 뚝 끊긴 부지 경계. 조성기는 자연 위에 상자를
     * 찍어 넣었고, <b>그것을 볼 눈이 없었다.</b>
     */
    /**
     * 환경검수·지하정리의 <b>표적</b> — 세계·중심·기준면·반경.
     * {@code place} 는 지역 경로에서만 있다 (청하현 무인자 경로는 null — 앵커가 곧 등록부다).
     */
    private record TerrainTarget(World world, WorldMap.Place place, String name,
                                 int cx, int cy, int cz, int r) {
    }

    /**
     * 표적 해석기 — <b>눈(환경검수)과 손(지하정리)이 글자 그대로 같은 해석기를 쓴다.</b>
     *
     * <p>★ 검토(2026-07-14)가 잡은 미발: 지하정리가 해석을 <b>복제</b>했더니 눈이 재는 곳과 손이 걷는 곳이
     * 어긋났다 — 무인자는 표적을 못 잡았고, {@code cheongha_hyeon} 은 원점 심부(-2,-56,-1 · y −96..−16)로
     * 풀려 85,052칸을 헛채웠다. <b>해석기가 둘이면 하나가 낡는다.</b> 그래서 함수로 추출해
     * {@link #auditTerrain} 과 {@link #sweepUnderground} 가 <b>이 하나</b>를 부른다.
     *
     * <ul>
     *   <li><b>무인자</b> — 청하현: 장터 앵커 중심 · cy = 앵커 y−1 · 반경 61 (auditTown 의 자 그대로)</li>
     *   <li><b>지역id</b> — 구역 중심 · 반경 = 구역 절반+8 · 기준면 = 원장(regionBase), 없으면 구역 y1+6</li>
     * </ul>
     *
     * @return null 이면 해석 실패 — 사유는 sender 에게 이미 말했다
     */
    private TerrainTarget resolveTerrainTarget(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            WorldMap map = plugin.worldMap();
            WorldMap.Place place = map == null ? null : map.place(args[1]);
            Zone zone = place == null ? null : plugin.zones().stream()
                    .filter(z -> z.name().equals(place.name())).findFirst().orElse(null);
            if (zone == null) {
                sender.sendMessage(ChatColor.RED + "서지 않은 지역이다: " + args[1]);
                return null;
            }
            World world = org.bukkit.Bukkit.getWorld(zone.world());
            if (world == null) {
                sender.sendMessage(ChatColor.RED + "구역의 세계가 실려 있지 않다: " + zone.world());
                return null;
            }
            int cx = (zone.x1() + zone.x2()) / 2;
            int cz = (zone.z1() + zone.z2()) / 2;
            int radius = Math.max(zone.x2() - zone.x1(), zone.z2() - zone.z1()) / 2 + 8;
            // 조성 지면은 **원장**이 안다. 구역의 y1 을 쓰면 은신처처럼 굴까지 덮는 구역에서
            // 기준면이 30켜 내려가고, 그러면 검수 ②가 **강물 전체를 "산 위의 웅덩이"** 로 센다.
            Integer base = plugin.regionBase(place.id());
            int auditY = base != null ? base : zone.y1() + 6;
            return new TerrainTarget(world, place, place.name(), cx, auditY, cz, radius);
        }
        Location center = plugin.anchor("장터");
        if (center == null) {
            sender.sendMessage(ChatColor.RED + "조성된 마을이 없다 — 먼저 /혼천 세계조성");
            return null;
        }
        // auditTown 의 자 그대로: 중심 = 장터 앵커 · cy = 앵커 y−1 · 반경 61
        return new TerrainTarget(center.getWorld(), null, "청하현",
                center.getBlockX(), center.getBlockY() - 1, center.getBlockZ(), 61);
    }

    private boolean auditTerrain(CommandSender sender, String[] args) {
        TerrainTarget at = resolveTerrainTarget(sender, args);
        if (at == null) {
            return true;
        }
        java.util.List<String> lines;
        if (at.place() != null) {
            java.util.List<String> t = new java.util.ArrayList<>(
                    TerrainAudit.audit(at.world(), at.name(), at.cx(), at.cy(), at.cz(), at.r(),
                            at.place().terrain() == null ? "평지" : at.place().terrain(),
                            TerrainForge.caveKind(at.place()) != null));   // 우리가 판 굴이 있는 지역인가
            // ★ 강의 눈 — 강 없는 곳이면 빈 목록이다. 있는 곳이면 역류·단절·누수·수심을 잰다.
            t.addAll(RiverAudit.audit(at.world(), at.place(), at.cx(), at.cz(), at.r(), at.cy()));
            lines = t;
        } else {
            lines = TerrainAudit.audit(at.world(), at.name(), at.cx(), at.cy(), at.cz(), at.r());
        }
        for (String line : lines) {
            sender.sendMessage(line);
            plugin.getLogger().info("[환경검수] " + ChatColor.stripColor(line));
        }
        return true;
    }

    /**
     * /혼천 지하정리 [지역id] — <b>산 채로 묻힌 나무를 걷는다</b> (B-114 7차의 치유).
     *
     * <p>조성기가 서 있던 나무 위로 땅을 올려, 지면 밑에 「공기 + 잎 + 통나무」가 묻혔다
     * (실측 단면 -61,81~89,359: 옛 지면 y79 → 공기 81-82 → 잎 83-86 → 새 흙·잔디 87-89).
     * 원인은 TerrainForge 계약 ①-b(fill_below_raised)로 막았고, <b>이미 묻힌 것</b>은 이 손이 걷는다:
     * 기둥마다 <b>그 기둥의 지면</b>({@link TerrainAudit#surfaceY} — 환경검수 ⑥과 <b>같은 자</b>) 아래
     * 공기·잎·통나무·버섯·초목을 채움 재질(깊이의 순수 함수 — sealBelow 관례)로 치환한다.
     * ★ 원장(terrain_built.yml)의 <b>판 굴 상자 안은 건드리지 않는다</b> ({@link TerrainAudit#dugCaveBoxes}).
     *
     * <p><b>왜 콘솔이 되는가</b> — 조성 명령들의 "마크에서 쳐라"(플레이어 전용)는 부지가
     * <b>플레이어의 자리</b>에서 오기 때문이다. 치유는 좌표가 <b>등록부</b>(앵커·원장·구역)에서 오므로
     * 지역조성·환경검수와 같은 관례를 따른다: {@code Player 면 op 요구 · 콘솔 허용} ({@link #region} 과 동일).
     *
     * <p>★ <b>표적 해석은 환경검수와 한 함수다</b> ({@link #resolveTerrainTarget}) — 1차 구현이 해석을
     * <b>복제</b>했더니 실사격에서 두 경로 다 미발했다 (무인자 표적 상실 · cheongha_hyeon 원점 심부 오폭).
     * 그리고 <b>안전핀</b>: 채우기 전에 해석된 표적을 한 줄로 말하고, 기준면이 상식 밖이면
     * ({@link TerrainForge#sweepTargetSane} — cy&lt;0 · 실지면 40칸 이상 괴리) <b>채우지 않고 거부</b>한다.
     */
    /**
     * /혼천 경계다듬기 [지역id] — <b>경계 띠만 다시 잇는다</b> (B-127).
     *
     * <p>영수증을 잃은 재조성은 이음 띠를 잃는다 — 마을 단(y94~96)과 자연면(y89~90) 사이가
     * 벽이 됐다 (환경검수 ③ 8.2% · 46곳 · ④ 0/4, 2026-07-15 실측). 재조성은 같은 땅을 또
     * 깎으므로 금지다 — 이 명령은 <b>부지 안을 한 칸도 건드리지 않고</b>
     * {@link TerrainForge#feather} 계약(땅에게 묻는 결정론 이음 · 물 불가침 · 올린 밑 채움)을
     * 경계 띠(r .. r+{@link TerrainForge#FEATHER_WIDTH})에 재적용한다.
     *
     * <p>관례는 지하정리와 같다: 표적 해석은 {@link #resolveTerrainTarget} <b>한 함수</b> ·
     * 표적을 먼저 소리내어 말한다 · 상식 밖이면 거부({@link TerrainForge#sweepTargetSane}) ·
     * 좌표가 등록부(앵커·구역)에서 오므로 콘솔 가능.
     */
    private boolean featherEdge(CommandSender sender, String[] args) {
        if (sender instanceof Player p && !p.isOp()) {
            return true;
        }
        TerrainTarget at = resolveTerrainTarget(sender, args);
        if (at == null) {
            return true;
        }
        World world = at.world();
        int cx = at.cx();
        int cy = at.cy();
        int cz = at.cz();
        int rOuter = at.r() + TerrainForge.FEATHER_WIDTH;
        world.getChunkAt(cx >> 4, cz >> 4).load(true);
        int centerStand = TerrainAudit.surfaceY(world, cx, cz, cy);
        Announce.say(plugin, sender, ChatColor.GRAY + "[경계다듬기] 표적 — " + at.name()
                + " · 세계 " + world.getName()
                + " · 중심 (" + cx + "," + cy + "," + cz + ") · 띠 r" + at.r() + "~" + rOuter
                + " · 중심 기둥 실지면 "
                + (centerStand == Integer.MIN_VALUE ? "못 찾음(±40 밖)" : "y" + (centerStand - 1)));
        if (!TerrainForge.sweepTargetSane(cy, centerStand)) {
            Announce.fail(plugin, sender, "★ 표적이 이상하다: " + world.getName()
                    + " (" + cx + "," + cy + "," + cz + ") — 기준면이 상식 밖이다. **다듬지 않는다** "
                    + "(지하정리와 같은 핀 — 등록부를 확인하라)");
            return true;
        }
        TerrainForge.preload(world, cx, cz, rOuter + 4);
        TerrainForge.feather(world, cx, cz, at.r(), rOuter, cy);
        Announce.say(plugin, sender, ChatColor.GOLD + "[경계다듬기] 이었다 — " + at.name()
                + " 경계 띠 r" + at.r() + "~" + rOuter
                + ". 눈으로 재라: /혼천 환경검수" + (at.place() == null ? "" : " " + at.place().id()));
        return true;
    }

    private boolean sweepUnderground(CommandSender sender, String[] args) {
        if (sender instanceof Player p && !p.isOp()) {
            return true;
        }
        // ★ 표적 해석은 환경검수와 **한 함수**다 ({@link #resolveTerrainTarget}) — 복제했다가 미발했다
        TerrainTarget at = resolveTerrainTarget(sender, args);
        if (at == null) {
            return true;
        }
        World world = at.world();
        String name = at.name();
        int cx = at.cx();
        int cy = at.cy();
        int cz = at.cz();
        int r = at.r();
        // ── 안전핀 — **채우기 전에** 해석된 표적을 소리내어 말하고, 상식 밖이면 거부한다 ──
        //   검토 실사격: cheongha_hyeon 이 낡은 구역으로 원점 심부(-2,-56,-1 · y −96..−16)에 풀려
        //   85,052칸을 헛채웠다. 오탈자·낡은 원장이 세계를 파게 두지 않는다 (갇힘 금지와 같은 결).
        world.getChunkAt(cx >> 4, cz >> 4).load(true);   // 중심 기둥만 먼저 — 실지면을 재기 위해
        int centerStand = TerrainAudit.surfaceY(world, cx, cz, cy);
        Announce.say(plugin, sender, ChatColor.GRAY + "[지하정리] 표적 — " + name
                + " · 세계 " + world.getName()
                + " · 중심 (" + cx + "," + cy + "," + cz + ") · 반경 " + r
                + " · 중심 기둥 실지면 "
                + (centerStand == Integer.MIN_VALUE ? "못 찾음(±40 밖)" : "y" + (centerStand - 1)));
        if (!TerrainForge.sweepTargetSane(cy, centerStand)) {
            Announce.fail(plugin, sender, "★ 표적이 이상하다: " + world.getName()
                    + " (" + cx + "," + cy + "," + cz + ") — 기준면이 상식 밖이다 (cy<0 이거나 "
                    + "중심 기둥의 실지면과 40칸 이상 괴리). **채우지 않는다** — "
                    + "등록부(앵커·원장 terrain_built·구역)를 확인하라");
            return true;
        }
        java.util.List<Zone> dug = TerrainAudit.dugCaveBoxes(world.getName(), cx, cz, r);
        TerrainForge.preload(world, cx, cz, r);
        Announce.say(plugin, sender, ChatColor.GOLD + "── 지하정리 — " + name
                + " (세계 " + world.getName() + " · 중심 " + cx + "," + cy + "," + cz + " · 반경 " + r
                + " · 판 굴 상자 " + dug.size() + "곳 보존) ──");
        long air = 0;
        long leaf = 0;
        long log = 0;
        long mush = 0;
        long plant = 0;
        long kept = 0;
        int columns = 0;
        int filledLo = Integer.MAX_VALUE;   // ★ 실제로 채운 y 대역 — 다음 미발이 즉시 보이게 (검토 지시 ③)
        int filledHi = Integer.MIN_VALUE;
        int bottom = Math.max(world.getMinHeight() + 5, cy - 45);   // 환경검수 ⑥과 같은 대역 바닥
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                int stand = TerrainAudit.surfaceY(world, x, z, cy);   // ⑥과 같은 지면 판정
                if (stand == Integer.MIN_VALUE) {
                    continue;   // 지면을 못 찾은 기둥 — ⑥도 세지 않는다. 같은 자다
                }
                columns++;
                int ground = stand - 1;   // 지면 블록 y — 그 **아래**만 걷는다 (표면은 손대지 않는다)
                for (int y = ground - 1; y >= bottom; y--) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (!TerrainForge.sweepFillable(m)) {
                        continue;   // 돌·흙·광석·물·용암·사람 것 — 남긴다
                    }
                    if (!TerrainForge.sweepShouldFill(m, dug, x, y, z)) {
                        kept++;     // ★ 원장의 판 굴 — 설계다. 건드리지 않는다
                        continue;
                    }
                    String n = m.name();
                    if (m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR) {
                        air++;
                    } else if (n.endsWith("_LEAVES")) {
                        leaf++;
                    } else if (n.endsWith("_LOG") || n.endsWith("_WOOD")) {
                        log++;
                    } else if (n.contains("MUSHROOM") || n.equals("BAMBOO")) {
                        mush++;
                    } else {
                        plant++;
                    }
                    world.getBlockAt(x, y, z).setType(TerrainForge.sweepFill(ground - y), false);
                    filledLo = Math.min(filledLo, y);
                    filledHi = Math.max(filledHi, y);
                }
            }
        }
        long total = air + leaf + log + mush + plant;
        Announce.say(plugin, sender, ChatColor.WHITE + "기둥 " + columns + " · 채움 " + total + "칸 — "
                + "공기 " + air + " · 잎 " + leaf + " · 통나무 " + log
                + " · 버섯·대나무 " + mush + " · 초목 " + plant);
        // ★ 어느 세계 어느 대역을 채웠는가 — 표적이 어긋났으면 이 줄이 즉시 말한다
        Announce.say(plugin, sender, ChatColor.GRAY + "채운 자리: 세계 " + world.getName()
                + (total == 0 ? " · 채운 칸 없음" : " · 대역 y" + filledLo + "~y" + filledHi)
                + " (표본 대역 y" + bottom + "~지면−1)");
        Announce.say(plugin, sender, ChatColor.GRAY + "판 굴 보존 " + kept + "칸 (원장 "
                + dug.size() + "곳 — 우리가 판 굴은 설계다)");
        Announce.say(plugin, sender, ChatColor.GRAY
                + "다음: /혼천 환경검수 — ⑥ 지하 공동이 2% 아래로 내려가야 닫힌다 (B-114)");
        return true;
    }

    /** 지역 조감 — 등록부의 좌표(지도가 고른 부지)를 중심으로 그린다 */
    private boolean renderRegion(CommandSender sender, String id) {
        WorldMap map = plugin.worldMap();
        WorldMap.Place place = map == null ? null : map.place(id);
        if (place == null) {
            sender.sendMessage(ChatColor.RED + "지도에 없는 지역: " + id);
            return true;
        }
        Zone zone = plugin.zones().stream().filter(z -> z.name().equals(place.name())).findFirst().orElse(null);
        if (zone == null) {
            sender.sendMessage(ChatColor.RED + place.name() + " 은 아직 서지 않았다 — /혼천 지역조성 " + id);
            return true;
        }
        World world = org.bukkit.Bukkit.getWorld(zone.world());
        int cx = (zone.x1() + zone.x2()) / 2;
        int cz = (zone.z1() + zone.z2()) / 2;
        // 창은 **구역이 정한다** — 마을의 창(반경 65·높이 25)으로 문파를 보면 산허리만 잘려 나온다.
        int cy = zone.y1() + 6;
        int radius = Math.min(120, Math.max(48,
                Math.max(zone.x2() - zone.x1(), zone.z2() - zone.z1()) / 2 + 12));
        int yUp = Math.min(90, Math.max(25, zone.y2() - cy));
        // 렌더 전에 땅을 싣는다 — 안 실린 청크는 공기로 읽혀 조감에 **검은 구멍**이 뚫린다.
        // 루프의 눈이 못 본 것을 "없다"고 그리면, 있는 결함도 없는 것이 된다.
        java.util.List<java.util.concurrent.CompletableFuture<org.bukkit.Chunk>> loading =
                new java.util.ArrayList<>();
        int pre = radius + 16;
        for (int chunkX = (cx - pre) >> 4; chunkX <= (cx + pre) >> 4; chunkX++) {
            for (int chunkZ = (cz - pre) >> 4; chunkZ <= (cz + pre) >> 4; chunkZ++) {
                loading.add(world.getChunkAtAsync(chunkX, chunkZ, true));
            }
        }
        java.io.File dir = new java.io.File(plugin.getDataFolder(), "render/" + id);
        java.util.concurrent.CompletableFuture
                .allOf(loading.toArray(new java.util.concurrent.CompletableFuture[0]))
                .thenRun(() -> org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    for (String line : TownRender.render(world, cx, cy, cz, dir, radius, 6, yUp)) {
                        sender.sendMessage(ChatColor.GRAY + line);
                    }
                    plugin.getLogger().info("[조감] " + id + " → " + dir.getAbsolutePath());
                }));
        return true;
    }

    /**
     * /혼천 출행 [지역id] — <b>지역으로 간다</b>.
     *
     * <p>실지리 1:1 세계라 화산까지 146km 다 — 걸으면 닷새다(등록부의 여정 일수 그대로).
     * 지금은 시험 중이니 관리자가 곧장 간다. 인자가 없으면 <b>갈 수 있는 곳의 목록</b>을 편다:
     * 조성된 지역만 뜬다 (등록만 되고 서지 않은 곳은 목적지가 아니라 문서의 줄이다).
     */
    private boolean travel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        WorldMap map = plugin.worldMap();
        if (map == null) {
            sender.sendMessage(ChatColor.RED + "world_map.yml 이 없다.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.GOLD + "── 갈 수 있는 곳 (조성된 지역) ──");
            Location market = plugin.anchor("장터");
            if (market != null) {
                sender.sendMessage(ChatColor.WHITE + "  청하현" + ChatColor.GRAY
                        + " — /혼천 출행 cheongha_hyeon");
            }
            for (Zone zone : plugin.zones()) {
                WorldMap.Place place = map.places().values().stream()
                        .filter(pl -> pl.name().equals(zone.name())).findFirst().orElse(null);
                if (place == null) {
                    continue;   // 마을 안 구역(객잔·의방…)은 지역이 아니다
                }
                if (place.hidden() && !player.isOp()) {
                    // ★ B-151 표시 축 — 숨긴 곳은 목록에 없다 (해금 축과 별개다: 세 축 분리)
                    continue;
                }
                // ★★ B-151 해금 축 — **id 직행과 같은 판정기.** "갈 수 있는 곳" 목록이니
                //   접근이 막힌 곳은 여기서도 뺀다 (표시 축을 부수지 않고 접근 축을 더한다).
                if (!AccessJudge.judge(place.access(), player.isOp()).allowed()) {
                    continue;
                }
                int days = place.days();
                sender.sendMessage(ChatColor.WHITE + "  " + place.name() + ChatColor.GRAY
                        + " — /혼천 출행 " + place.id()
                        + (days > 0 ? ChatColor.DARK_GRAY + " (걸어서 " + days + "일)" : ""));
            }
            sender.sendMessage(ChatColor.DARK_GRAY + "  /혼천 연무장 — 시험장 (별도 월드)");
            return true;
        }
        String id = args[1];
        if ("cheongha_hyeon".equals(id) || "청하현".equals(id)) {
            Location market = plugin.anchor("장터");
            if (market == null) {
                sender.sendMessage(ChatColor.RED + "청하현이 아직 서지 않았다.");
                return true;
            }
            // ★ 옛 코드: market.clone().add(0, 1, 0) — 앵커(= 마을 원점 = **광장 우물**) 위 한 칸.
            //   그 한 칸은 우물 두레박 사슬 자리다. 재지 않고 내리면 사람이 우물에 갇힌다.
            Location spot = Standing.landing(market);
            if (spot == null) {
                sender.sendMessage(ChatColor.RED + "장터 앵커 " + Standing.describe(market)
                        + " 둘레에 설 자리가 없다 — /혼천 앵커검사");
                return true;
            }
            player.teleport(spot);
            player.sendMessage(ChatColor.GOLD + "청하현 장터 " + ChatColor.GRAY + Standing.describe(spot));
            return true;
        }
        WorldMap.Place place = map.place(id);
        if (place == null) {
            sender.sendMessage(ChatColor.RED + "지도에 없는 지역: " + id);
            return true;
        }
        // ★★ B-151 — 해금 축. **id 직행이 관문을 우회하던 구멍을 여기서 막는다** (Codex §8).
        //   목록·지도와 **같은 판정기**를 부른다 (단일 창구 — 두 벌 금지). 관문의 판정은 로그에 남긴다.
        AccessJudge.Verdict access = AccessJudge.judge(place.access(), player.isOp());
        if (!access.allowed()) {
            player.sendMessage(ChatColor.RED + place.name() + " — 들어갈 수 없다: " + access.reason());
            plugin.getLogger().info("[혼천/출행] 접근 거부 — " + player.getName()
                    + " → " + place.id() + " (" + access.reason() + ")");
            return true;
        }
        Zone zone = plugin.zones().stream().filter(z -> z.name().equals(place.name()))
                .findFirst().orElse(null);
        if (zone == null) {
            sender.sendMessage(ChatColor.RED + place.name() + " 은 아직 서지 않았다 — /혼천 지역조성 " + id);
            return true;
        }
        World world = org.bukkit.Bukkit.getWorld(zone.world());
        // 문 앞에 내린다 — 구역 남쪽 가장자리의 **땅**(지붕이 아니라). 걸어 들어가는 맛을 남긴다.
        int tx = (zone.x1() + zone.x2()) / 2;
        int tz = zone.z2() - 4;
        int ty = world.getHighestBlockYAt(tx, tz);
        // ★ 지표(getHighestBlockYAt)는 **설 수 있는 자리가 아니다** — 물 위·지붕 위·담 안일 수 있다.
        //   재고 내린다 (앵커 우물 사건의 같은 병).
        Location door = new Location(world, tx + 0.5, ty + 1.0, tz + 0.5, 180f, 0f);
        Location spot = Standing.landing(door, 16);
        if (spot == null) {
            sender.sendMessage(ChatColor.RED + place.name() + " 문 앞 " + Standing.describe(door)
                    + " 둘레에 사람이 설 자리가 없다 — 내리지 않는다 (지역을 다시 조성하라).");
            return true;
        }
        player.teleport(spot);
        player.sendMessage(ChatColor.GOLD + place.name() + ChatColor.GRAY + " — 문 앞이다"
                + (place.days() > 0 ? " (걸어서라면 " + place.days() + "일)" : ""));
        return true;
    }

    // ═══ 연무장 — 따로 두들겨 보는 자리 (별도 월드) ═══

    private boolean dojangEnter(CommandSender sender) {
        if (sender instanceof Player p) {
            plugin.dojang().enter(p);
        }
        return true;
    }

    private boolean dojangLeave(CommandSender sender) {
        if (sender instanceof Player p) {
            plugin.dojang().leave(p);
        }
        return true;
    }

    /**
     * ★ <b>/혼천 금고</b> — 연무장이 <b>누구의 무엇을 맡고 있는가</b> (콘솔 가능).
     *
     * <p>연무장은 들어온 사람에게서 진짜 장부·무공·짐을 <b>떼어 낸다</b>. 그것이 어디 있는지 볼 수 있어야
     * 한다 — 안 보이면 사라져도 아무도 모른다 (그렇게 사라졌다).
     */
    private boolean dojangVault(CommandSender sender) {
        plugin.dojang().auditLines().forEach(sender::sendMessage);
        return true;
    }

    /**
     * <b>/혼천 짐지문 [플레이어]</b> — 지금 든 짐의 <b>지문</b>을 찍는다.
     *
     * <p>재기동을 <b>실제로 건너</b> 같은 짐이 돌아왔는지 보려면 전후를 견줘야 하는데,
     * `data get entity … Inventory` 는 서버가 <b>174바이트에서 잘라</b> 준다 — 잘린 글로는 못 견준다.
     * 그래서 금고가 실제로 적는 바이트를 sha1 로 접어 한 줄로 만든다. 콘솔에서도 부를 수 있다.
     */
    private boolean inventoryFingerprint(CommandSender sender, String[] args) {
        Player who;
        if (args.length >= 2) {
            who = Bukkit.getPlayerExact(args[1]);
            if (who == null) {
                sender.sendMessage(ChatColor.RED + "그런 사람이 접속해 있지 않다: " + args[1]);
                return true;
            }
        } else if (sender instanceof Player p) {
            who = p;
        } else {
            sender.sendMessage(ChatColor.GRAY + "/혼천 짐지문 <플레이어>  (콘솔은 이름을 줘야 한다)");
            return true;
        }
        ItemStack[] items = who.getInventory().getContents();
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━ " + who.getName() + " 의 짐 ━━━");
        Dojang.inventoryLines(items).forEach(sender::sendMessage);
        sender.sendMessage(ChatColor.AQUA + "지문: " + ChatColor.WHITE
                + Dojang.inventoryFingerprint(items));
        return true;
    }

    /**
     * <b>/혼천 금고시험</b> — 금고가 <b>진짜 짐</b>을 잃지 않는지 잰다 (B-011).
     *
     * <p>왜 필요했나: 옛 자기시험은 <b>문자열</b>을 왕복시켰다. 그것은
     * "YAML 이 줄을 잃지 않는다"를 증명할 뿐, <b>"재기동을 건너 사람의 짐이 살아 돌아온다"</b>는
     * 한 번도 증명한 적이 없다 — 그 사이의 {@code ItemStack.serializeItemsAsBytes} 가 통째로 빠져 있었다.
     *
     * <p>금고 파일은 <b>건드리지 않는다</b>. 메모리에서만 왕복시킨다.
     */
    private boolean dojangVaultTest(CommandSender sender) {
        if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "금고시험은 관리자의 몫이다.");
            return true;
        }
        List<Dojang.VaultCase> rs = plugin.dojang().vaultRoundTripTest();
        long bad = rs.stream().filter(r -> !r.pass()).count();
        sender.sendMessage(ChatColor.DARK_GRAY + "━━━ 금고 왕복 실측 (진짜 ItemStack) ━━━");
        for (Dojang.VaultCase r : rs) {
            sender.sendMessage((r.pass() ? ChatColor.GREEN + "  통과  " : ChatColor.RED + "  ★실패 ")
                    + ChatColor.WHITE + r.name() + ChatColor.GRAY + " — " + r.detail());
        }
        sender.sendMessage(bad == 0
                ? ChatColor.GREEN + "전부 통과 — 짐은 재기동을 건너 살아 돌아온다"
                : ChatColor.RED + "★ " + bad + "건 실패 — 이 상태로는 사람의 물건을 잃는다");
        return true;
    }

    /** /혼천 시험 경지|내력|무공|몹|치움 … */
    private boolean dojangTune(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            return true;
        }
        if (args.length < 2) {
            p.sendMessage(ChatColor.GRAY + "/혼천 시험 경지 <경지> · 내력 <값> · 무공 <id> [일수] · "
                    + "몹 <id> [걷기] · 치움");
            return true;
        }
        switch (args[1]) {
            case "경지" -> {
                if (args.length >= 3) {
                    plugin.dojang().setRealm(p, args[2]);
                }
            }
            case "내력" -> {
                if (args.length >= 3) {
                    plugin.dojang().setEnergy(p, Integer.parseInt(args[2]));
                }
            }
            case "무공" -> {
                if (args.length >= 3) {
                    double days = args.length >= 4 ? Double.parseDouble(args[3]) : 30.0;
                    plugin.dojang().grantSkill(p, args[2], days);
                }
            }
            case "몹" -> {
                if (args.length >= 3) {
                    // 넷째 인자 「걷기」 = AI 를 켜고 표적을 준다 (다리 관절은 이동거리로 돈다)
                    boolean walking = args.length >= 4 && args[3].equals("걷기");
                    plugin.dojang().mob(p, args[2], walking);
                }
            }
            case "치움" -> plugin.dojang().clear(p);
            default -> p.sendMessage(ChatColor.GRAY + "경지 · 내력 · 무공 · 몹 · 치움");
        }
        return true;
    }

    private boolean dojangDummy(CommandSender sender, String[] args) {
        if (sender instanceof Player p) {
            int durability = args.length >= 2 ? Integer.parseInt(args[1]) : 20;
            plugin.dojang().dummy(p, durability);
        }
        return true;
    }

    /**
     * /혼천 계측 [초기화|켜기|끄기] — <b>예산을 지키는가</b>.
     *
     * <p>이 서버는 <b>한 번도 계측된 적이 없었다.</b> 그런데 spark(Paper 번들 프로파일러)는
     * <b>이미 배경에서 돌고 있었다</b> — 지난 모든 랙의 프로파일이 쌓여 있었고 아무도 안 봤다.
     * spark 는 "왜 느린가"(스택 트리)에 답하고, 이 계기는 <b>"규약을 어겼는가"</b>(performance.yml 예산 대조)에
     * 답한다. spark 는 우리 예산을 모른다 — "MobDisplay 가 7.2ms 를 먹었다"고 짖어 줄 자는 우리가 만들어야 한다.
     */
    private boolean metrics(CommandSender sender, String[] args) {
        if (sender instanceof Player p && !p.isOp()) {
            return true;
        }
        if (args.length >= 2 && args[1].equals("초기화")) {
            Metrics.reset();
            sender.sendMessage(ChatColor.GRAY + "계기를 0으로 놓았다.");
            return true;
        }
        if (args.length >= 2 && (args[1].equals("켜기") || args[1].equals("끄기"))) {
            Metrics.enabled(args[1].equals("켜기"));
            sender.sendMessage(ChatColor.GRAY + "계기 " + args[1]);
            return true;
        }
        for (String line : Metrics.report()) {
            sender.sendMessage(ChatColor.GRAY + line);
        }
        return true;
    }

    /**
     * /혼천 모션진단 [초] — <b>3D 층이 실제로 떴는가</b>.
     *
     * <p>"공격 모션이 안 바뀐 것 같다"는 보고를 받고서야 알았다 — 3D 획이 <b>무공 시전 경로에만</b> 살아 있어서,
     * 무공을 안 배운 채 칼을 휘두르면 아무것도 안 떴다. <b>가장 흔한 손이 빈 경로였다.</b>
     * 검수는 등록부만 봤고 빈 경로는 못 봤다. 이제 눈으로도 물을 수 있다:
     * 안 뜬 것(등록부가 획을 안 준 계열)과 못 뜬 것(예산 강등)이 <b>다른 사건으로</b> 보인다.
     */
    private boolean motionDiag(CommandSender sender, String[] args) {
        int seconds = args.length >= 2 ? Integer.parseInt(args[1]) : 30;
        for (String line : plugin.skills().motionDiagnostics(seconds)) {
            sender.sendMessage(line);
        }
        return true;
    }

    /**
     * <b>/혼천 획시험</b> — 획의 눈을 게임 안에 세운다.
     *
     * <p>정적 검산은 전부 통과했다 (배급 zip = 최신 · 키 15개 전부 팩에 있음 · items→models→textures 사슬
     * 안 끊김 · 배치본 = 저장소). 그런데 사용자는 보라 큐브를 본다. <b>검산이 볼 수 없는 곳에서 어긋나 있다.</b>
     *
     * <p>그래서 획을 <b>한 줄로 세우고 이름을 단다</b> — 사용자가 걸어가며 <b>어느 것이 보라인지 이름으로
     * 지목</b>할 수 있게. 대조군(맨 종이 · 없는 키 · 병기 키)이 같은 줄에 선다: <b>없는 키가 보라가
     * 아니면 원인은 팩이 아니라 코드다.</b>
     */
    private boolean strokeTest(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "획시험은 몸이 있어야 한다 — 게임 안에서 쳐라 (콘솔 불가)");
            return true;
        }
        for (String line : plugin.skills().strokeTest(player)) {
            player.sendMessage(line);
        }
        return true;
    }

    /**
     * <b>/혼천 사다리</b> — 여섯 격의 생김새를 <b>나란히</b> 세운다 (화려함의 눈).
     *
     * <p>사용자의 요구: <i>"격별 획을 나란히 보여 주는 길을 만들어라 — 한눈에 사다리를 보고 판단할 수
     * 있어야 한다."</i> 말뚝 여섯이 앞에 서고, 각 말뚝에서 그 격의 파티클(잔상·먹번짐·먹점·강조·<b>폭발</b>)이
     * 반복해 터진다. 동시에 손에서는 3D 획이 격을 갈아 가며 그어진다 (굵기·밝기의 사다리).
     */
    private boolean ladder(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED
                    + "사다리는 몸이 있어야 한다 — 눈으로 봐야 판단할 수 있는 값이다 (콘솔 불가)");
            return true;
        }
        for (String line : plugin.skills().ladder(player)) {
            player.sendMessage(line);
        }
        return true;
    }

    /**
     * <b>/혼천 획위치</b> — 획이 서는 자리를 <b>인게임에서</b> 맞춘다.
     *
     * <p>사용자가 본 것: "<b>획은 몸 안에서 나오는 느낌입니다. 1인칭 시점에선 보이지도 않아요.</b>"
     * 원인은 코드가 획을 <b>시전자의 눈</b>에 세운 것이었다 (앞으로 미는 값이 없었다). 자리는 이제
     * 등록부({@code display.stroke_origin})가 쥐지만 — 그 값은 <b>눈으로 봐야 정해진다</b>.
     * 서버를 세우고 등록부를 고치고 다시 세우는 왕복으로는 한 값도 못 맞춘다.
     *
     * <p>그래서 이 명령이 있다: <b>밀고 → 즉시 그어 보고 → 맞으면 뽑아서 등록부에 적는다.</b>
     * <pre>
     *   /혼천 획위치                     지금 값 · 실효값 · 몸 안 검사
     *   /혼천 획위치 호 앞 1.2           밀고 **즉시 획 한 번** (호·선·원 × 앞·높이·옆)
     *   /혼천 획위치 그려 선             지금 값으로 한 번 더
     *   /혼천 획위치 되돌려              등록부의 값으로
     *   /혼천 획위치 적기                config/skill_motion.yml 에 붙일 줄을 뽑는다
     * </pre>
     */
    private boolean strokeOrigin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED
                    + "획위치는 몸이 있어야 한다 — 눈으로 봐야 정해지는 값이다 (콘솔 불가)");
            return true;
        }
        for (String line : plugin.skills().strokeOrigin(player, args)) {
            player.sendMessage(line);
        }
        return true;
    }

    /**
     * <b>/혼천 스윙</b> — 스윙의 <b>크기·각도</b>를 인게임에서 밀고 당긴다.
     *
     * <p>사용자가 본 것: "<b>지금은 앞으로 툭 치는 공격 같다. 원하는 건 검을 크게 휘둘러 시원하게 베는
     * 공격이다.</b>" 원인은 획이 <b>돌지 않은 것</b>이었다 (각을 세우고 길이만 키웠다 — 각이동 0도).
     * 이제 획은 시작 각에서 끝 각으로 <b>쓸고 지나간다</b>. 그러나 <b>그 각은 눈으로 봐야 정해진다</b>.
     *
     * <pre>
     *   /혼천 스윙                    지금 값 + 눈 (계열마다 참격인가 찌르기인가)
     *   /혼천 스윙 호 1.4             호 각도를 1.4배 → **즉시 획 한 번**
     *   /혼천 스윙 활 1.8             파티클 궤적의 활(弧)을 부풀린다
     *   /혼천 스윙 전진 0             전진을 죽인다 (그래도 참격인가 — 눈의 대조군)
     *   /혼천 스윙 그려 내려베기      넷을 눈으로 비교한다
     *   /혼천 스윙 되돌려 · 적기
     * </pre>
     */
    private boolean swing(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED
                    + "스윙은 몸이 있어야 한다 — 눈으로 봐야 정해지는 값이다 (콘솔 불가)");
            return true;
        }
        for (String line : plugin.skills().swing(player, args)) {
            player.sendMessage(line);
        }
        return true;
    }

    /**
     * <b>/혼천 검기</b> — 초승달 검기(kigi_slash)의 <b>각도·크기</b>를 인게임에서 즉석에 돌려 본다.
     *
     * <p>재기동이 없다: 민 값은 {@link SkillEngine#setKigiSlash} 로 <b>메모리에만</b> 살고 다음 스윙부터
     * 보인다. config 파일은 안 쓴다 (주석이 정본의 절반이다) — 확정값은 {@code 보기} 가 뱉는
     * 붙여넣기용 줄로 사람이 못 박는다.
     *
     * <pre>
     *   /혼천 검기 [보기]        지금 값 전부 + config 에 붙일 줄
     *   /혼천 검기 &lt;키&gt; &lt;값&gt;     roll·tilt·sweep·radius·scale·height·forward·draw·fade·frame·sparks
     *   /혼천 검기 시험          휘두르지 않고 지금 값으로 한 번 소환
     *   /혼천 검기 초기화        등록부(config) 값으로 되돌린다
     * </pre>
     */
    private boolean kigi(CommandSender sender, String[] args) {
        Player player = asBody(sender);
        if (player == null) {
            // ★ 콘솔이면 **몸을 이름으로 지목**할 수 있다 (맨 끝 인자)
            Player named = args.length >= 2
                    ? plugin.getServer().getPlayerExact(args[args.length - 1]) : null;
            if (named != null) {
                player = named;
                args = java.util.Arrays.copyOf(args, args.length - 1);   // 이름은 떼어낸다
            }
        }
        if (player == null) {
            sender.sendMessage(ChatColor.RED
                    + "검기는 몸이 있어야 한다 — 검기를 세울 자리와 방향이 필요하다");
            sender.sendMessage(ChatColor.GRAY
                    + "  콘솔에서는 몸을 지목하라:  혼천 검기 "
                    + (args.length >= 2 ? args[1] : "시험") + " <플레이어>");
            return true;
        }
        if (!player.isOp()) {
            player.sendMessage(ChatColor.RED + "검기 조율은 관리자의 몫이다.");
            return true;
        }
        for (String line : plugin.skills().kigi(player, args)) {
            player.sendMessage(line);
        }
        return true;
    }

    /**
     * 명령을 친 자의 <b>몸</b>을 찾는다 — 없으면 null.
     *
     * <p>★ 왜 이 명령이 몸을 요구하나: <b>검기를 세울 자리와 방향</b>이 필요해서지
     * 「사람이 직접 쳤는가」가 아니다. 그러니 자리와 방향만 있으면 콘솔이 쳐도 된다 —
     * 그 길이 열려야 하네스가 RCON 한 줄로 스킬을 발동한다 (사람도 xdotool 도 없이).
     *
     * <p>★ 실측으로 배운 것 (2026-07-20 · 자동 루프가 막힌 자리):
     * {@code /execute as kigibot run 혼천 검기 시험} 은 <b>안 통한다.</b> 페이퍼는 레거시 Bukkit
     * 명령에 <b>원래 sender 를 그대로</b> 넘긴다 — 실측한 껍데기는
     * {@code CraftRemoteConsoleCommandSender} 였다 ({@code ProxiedCommandSender} 조차 아니다).
     * {@code execute as} 는 바닐라 소스스택의 엔티티만 바꿀 뿐 Bukkit sender 는 안 바꾼다.
     * ⇒ 그래서 콘솔은 <b>몸을 이름으로 지목</b>한다: {@code 혼천 검기 시험 kigibot}.
     * 아래 반사(reflection)는 그래도 남겨 둔다 — 다른 경로(플러그인 간 호출·판올림)에서
     * 진짜 프록시가 올 수 있고, 그때 이름을 안 대고도 풀리면 그편이 낫다.
     */
    private static Player asBody(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        if (sender instanceof org.bukkit.command.ProxiedCommandSender proxied
                && proxied.getCallee() instanceof Player callee) {
            return callee;      // execute as <플레이어> run … — 몸은 callee 다
        }
        // ★ 껍데기의 이름은 서버 구현·판올림마다 다르다 (ProxiedNativeCommandSender ·
        //   CommandSourceStack · …). 이름을 박아 두면 다음 판올림에 조용히 깨진다.
        //   ⇒ **몸을 내놓는 메서드가 있으면 쓴다** — 이름이 아니라 능력으로 본다.
        for (String getter : new String[]{"getCallee", "getExecutor", "getEntity"}) {
            try {
                Object body = sender.getClass().getMethod(getter).invoke(sender);
                if (body instanceof Player callee) {
                    return callee;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 그 메서드가 없거나 못 부른다 — 다음 것을 본다
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  /혼천 대행 — 콘솔이 플레이어의 손을 빌린다
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * <b>/혼천 대행 &lt;플레이어&gt; &lt;명령…&gt;</b> — 지목한 <b>몸</b>이 그 명령을 친 것처럼 실행한다.
     *
     * <p><b>★ 이것은 시험 도구다 (하네스 전용).</b> 라이브에서 남용되면 <b>사칭</b>이 된다 —
     * 남의 몸으로 돈을 쓰고 문파를 옮길 수 있다. 그래서 <b>OP 또는 콘솔만</b> 부를 수 있고,
     * 부른 자·빌린 몸·친 명령을 <b>서버 로그에 남긴다</b> (조용히 지나가지 않는다).
     *
     * <p><b>★ 왜 필요한가 — 실측으로 부딪힌 벽 (2026-07-20):</b> 하네스는 사람 없이
     * 시각효과를 검증한다. 그런데 {@code /혼천} 의 대부분 하위명령이 <b>몸</b>을 요구해
     * ({@code sender instanceof Player}) 콘솔·RCON 에서 거절당한다. 우회로가 전부 막혔다:
     * <ul>
     *   <li>{@code execute as <봇> run 혼천 …} — 안 된다. 페이퍼는 레거시 Bukkit 명령에
     *       <b>원래 sender 를 그대로</b> 넘긴다 ({@code CraftRemoteConsoleCommandSender}).
     *       자세한 실측은 {@link #asBody(CommandSender)} 의 주석을 보라.</li>
     *   <li>{@code xdotool} 로 채팅창에 치기 — <b>한글이 뭉개진다</b>(「혼천」→「천」).</li>
     * </ul>
     * ⇒ 열쇠는 이미 있었다: {@code Antechamber} 의 발판이 {@code player.performCommand(cmd)} 로
     * 플레이어를 대신해 명령을 친다. 그 통로를 RCON 에서 부를 수 있게 낸 문이 이것이다.
     *
     * <p><b>★ {@code performCommand} 는 {@code PlayerCommandPreprocessEvent} 를 안 태운다.</b>
     * (Antechamber 도 같은 이유로 과제 적립을 직접 닫는다.) 여기서는 <b>일부러 그대로 둔다</b> —
     * 이 문은 <b>계측용</b>이고, 하네스가 친 명령이 사람의 과제·통계로 집계되면 오히려 장부가
     * 더러워진다. 대신 그 사실을 <b>출력에 적어</b> 부른 자가 모르고 지나가지 않게 한다.
     *
     * <pre>
     *   혼천 대행 kigibot 혼천 시험 몹 horangi     # 봇이 연무장 몹을 부른다
     *   혼천 대행 kigibot 혼천 검기 시험           # 몸이 필요한 명령을 콘솔에서
     * </pre>
     */
    private boolean proxy(CommandSender sender, String[] args) {
        // 문지기 — OP 또는 콘솔만. (콘솔 sender 는 isOp() 가 true 다)
        if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "대행은 관리자의 몫이다.");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.GRAY + "/혼천 대행 <플레이어> <명령…> — "
                    + "그 몸이 친 것처럼 실행한다 (시험용)");
            sender.sendMessage(ChatColor.DARK_GRAY + "  예: 혼천 대행 kigibot 혼천 시험 몹 horangi");
            return true;
        }

        String who = args[1];
        Player body = plugin.getServer().getPlayerExact(who);
        if (body == null) {
            // 조용히 실패하지 않는다 — 누가 접속해 있는지까지 말해 준다
            String online = plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName).reduce((a, b) -> a + ", " + b).orElse("(아무도 없다)");
            sender.sendMessage(ChatColor.RED + "그런 몸이 접속해 있지 않다: " + who);
            sender.sendMessage(ChatColor.GRAY + "  접속 중: " + online);
            return true;
        }

        String cmd = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim();
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1);   // 있어도 되고 없어도 된다 — performCommand 는 / 를 안 받는다
        }
        if (cmd.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "칠 명령이 비었다.");
            return true;
        }

        String verdict = refuse(cmd);
        if (verdict != null) {
            sender.sendMessage(ChatColor.RED + "거부: " + verdict);
            return true;
        }

        // 남는다 — 사칭은 흔적 없이 지나가면 안 된다
        plugin.getLogger().warning("[대행] " + sender.getName() + " 이(가) "
                + body.getName() + " 의 손을 빌렸다: /" + cmd);

        boolean ok;
        try {
            ok = body.performCommand(cmd);
        } catch (Exception e) {
            // 조용히 실패하지 마라 — 오늘 `ms cast` 가 조용히 실패해 한참 헤맸다
            sender.sendMessage(ChatColor.RED + "대행 중 터졌다: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            plugin.getLogger().warning("[대행] 실패 — " + e);
            return true;
        }
        sender.sendMessage((ok ? ChatColor.GREEN + "대행 성공" : ChatColor.YELLOW + "대행 반환 false")
                + ChatColor.GRAY + " — " + body.getName() + " (으)로 /" + cmd);
        if (!ok) {
            sender.sendMessage(ChatColor.GRAY + "  false = 그런 명령이 없거나 실행기가 false 를 냈다. "
                    + "몸에게 간 메시지는 그 몸의 화면에 있다");
        }
        sender.sendMessage(ChatColor.DARK_GRAY
                + "  (performCommand 는 PlayerCommandPreprocessEvent 를 안 태운다 — "
                + "과제·통계에 안 잡힌다. 계측용으로는 이편이 깨끗하다)");
        return true;
    }

    /**
     * 대행으로 <b>치면 안 되는</b> 명령인가 — 막을 이유가 있는 것만 막는다 (과하게 막지 않는다).
     *
     * <p>고른 기준은 <b>「op 인 사람이 자기 손으로 못 하는 일을 대행으로만 할 수 있는가」</b>다.
     * 부른 자는 이미 op 라 {@code stop}·{@code op} 를 직접 칠 수 있다 — 그러니 그것을 막는 것은
     * 보안이 아니라 <b>사고 방지</b>다. 실제로 막을 값어치가 있는 것은 두 부류다:
     * <ul>
     *   <li><b>되돌릴 수 없는 것</b> — {@code stop}·{@code op}·{@code ban}·{@code whitelist} …
     *       대행은 자동 루프가 부른다. 루프가 실수로 서버를 내리면 시험이 통째로 날아간다.</li>
     *   <li><b>재귀</b> — {@code 혼천 대행 …} 을 대행시키면 A→B→A 로 무한히 돈다.</li>
     * </ul>
     * 나머지(월드 편집·give·tp 등)는 <b>막지 않는다</b> — 그것이 이 도구의 쓰임이다.
     */
    private static String refuse(String cmd) {
        String head = cmd.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        if (head.startsWith("minecraft:") || head.startsWith("bukkit:")) {
            head = head.substring(head.indexOf(':') + 1);
        }
        switch (head) {
            case "stop", "restart", "reload", "op", "deop", "ban", "ban-ip", "pardon",
                 "pardon-ip", "whitelist", "save-off" ->
                    { return "되돌릴 수 없는 명령은 대행하지 않는다 (" + head + ") — 직접 쳐라"; }
            default -> { }
        }
        // 재귀 방지 — 대행이 대행을 부르면 끝이 없다
        String[] parts = cmd.split("\\s+");
        if (parts.length >= 2 && (head.equals("혼천") || head.equals("honcheon"))
                && parts[1].equals("대행")) {
            return "대행이 대행을 부를 수 없다 (무한 재귀)";
        }
        return null;
    }

    /**
     * <b>/혼천 모션 &lt;재적재|상태&gt;</b> — 모션 등록부를 <b>서버를 안 내리고</b> 다시 읽는다.
     *
     * <p>이것이 스킬 모션 디자인의 병목을 푸는 손이다. 값 하나를 보려고 서버를 내리면
     * 클라 접속이 끊기고 소프트렌더 재접속에 1~3분이 간다 — 값 하나에 3~5분.
     * 여기서는 <b>고친다 → 재적재 → 발동 → 본다</b> 가 10초다.
     *
     * <pre>
     *   /혼천 모션 재적재    config/skill_motion.yml 을 다시 읽고 무엇이 달라졌는지 요약
     *   /혼천 모션 상태      지금 메모리에 실린 값 + 마지막 재적재 시각 (파일이 더 새로운지 알려 준다)
     * </pre>
     *
     * <p><b>콘솔에서도 된다</b> — 몸이 필요 없다. RCON 으로 부르는 것이 이 도구의 본래 쓰임이다
     * ({@code /혼천 검기} 는 눈으로 봐야 하는 값이라 몸을 요구하지만, 재적재는 그렇지 않다).
     */
    private boolean motion(CommandSender sender, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage(ChatColor.RED + "모션 재적재는 관리자의 몫이다.");
            return true;
        }
        String verb = args.length >= 2 ? args[1] : "상태";
        switch (verb) {
            case "재적재", "리로드" -> {
                for (String line : plugin.reloadSkillMotion()) {
                    sender.sendMessage(line);
                }
            }
            case "상태" -> {
                for (String line : motionStatus()) {
                    sender.sendMessage(line);
                }
            }
            default -> sender.sendMessage(ChatColor.GRAY
                    + "/혼천 모션 <재적재|상태> — 재적재는 서버를 안 내리고 skill_motion.yml 을 다시 읽는다");
        }
        return true;
    }

    /**
     * 지금 <b>메모리에 실린</b> 값을 보여 준다 — 파일이 아니라 메모리다.
     *
     * <p>핵심은 마지막 줄이다: <b>파일이 메모리보다 새로우면 재적재를 안 한 것</b>이다.
     * 그 어긋남을 모르고 재면 <b>파일에 적은 값을 본다고 믿으며 옛 값을 재게 된다</b>.
     */
    private java.util.List<String> motionStatus() {
        java.util.List<String> out = new java.util.ArrayList<>();
        SkillEngine engine = plugin.skillEngine();
        java.time.format.DateTimeFormatter clock =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        long reloaded = plugin.motionReloadedAt();
        java.nio.file.Path file = plugin.configPath() == null
                ? null : plugin.configPath().resolve("skill_motion.yml");
        long mtime = file == null ? 0L : file.toFile().lastModified();

        out.add(ChatColor.GOLD + "── 모션 등록부 — 지금 메모리에 실린 것 ──");
        StringBuilder counts = new StringBuilder(ChatColor.GRAY.toString());
        engine.motionCensus().forEach((k, v) ->
                counts.append(ChatColor.WHITE).append(k).append(' ').append(v)
                        .append(ChatColor.GRAY).append("종  "));
        out.add(counts.toString().stripTrailing());

        SkillEngine.KigiSlash kigi = engine.kigiSlash();
        if (kigi == null) {
            out.add(ChatColor.GRAY + "검기(kigi_slash): 등록부에 없다");
        } else {
            out.add(ChatColor.GRAY + "검기: " + ChatColor.WHITE
                    + "scale " + kigi.scale() + "  sweep " + kigi.sweepDeg()
                    + "  radius " + kigi.orbitRadius() + "  roll " + kigi.rollDeg()
                    + "  tilt " + kigi.tiltDeg() + "  height " + kigi.centerHeight()
                    + "  forward " + kigi.forward()
                    + ChatColor.GRAY + (engine.kigiSlashOverridden() ? "  ★ 인게임 오버라이드 중" : "  (등록부 원본)"));
        }

        if (reloaded == 0L) {
            out.add(ChatColor.GRAY + "재적재: " + ChatColor.WHITE + "없음"
                    + ChatColor.GRAY + " — 기동 때 읽은 것 그대로다");
        } else {
            out.add(ChatColor.GRAY + "마지막 재적재: " + ChatColor.WHITE
                    + java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(reloaded),
                            java.time.ZoneId.systemDefault()).format(clock));
        }
        if (file != null && mtime > 0) {
            out.add(ChatColor.GRAY + "파일 mtime: " + ChatColor.WHITE
                    + java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(mtime),
                            java.time.ZoneId.systemDefault()).format(clock));
            // 기동으로 읽은 경우엔 비교 기준이 없다 (프로세스 시작 시각을 안 들고 있다) — 그때는 말을 아낀다
            if (reloaded > 0 && mtime > reloaded) {
                out.add(ChatColor.RED + "★ 파일이 메모리보다 새롭다 — 재적재를 안 했다. "
                        + ChatColor.WHITE + "/혼천 모션 재적재");
            } else if (reloaded > 0) {
                out.add(ChatColor.GREEN + "파일 ↔ 메모리 동기 — 지금 도는 값이 파일이 말하는 값이다");
            }
        }
        return out;
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
        sender.sendMessage(ChatColor.GRAY + "앵커의 눈: " + ChatColor.WHITE + "/혼천 앵커검사"
                + ChatColor.GRAY + " (사람이 설 수 있는 자리인가 — 우물·독방을 잡는다) · "
                + ChatColor.WHITE + "/혼천 앵커재측" + ChatColor.GRAY + " (고친다)");
        sender.sendMessage(ChatColor.GRAY + "연무장: " + ChatColor.WHITE + "/혼천 연무장"
                + ChatColor.GRAY + " (시험장 — 진짜 장부·짐은 금고에 맡긴다) · "
                + ChatColor.WHITE + "/혼천 귀환" + ChatColor.GRAY + " · "
                + ChatColor.WHITE + "/혼천 금고" + ChatColor.GRAY + " (맡긴 것을 본다)");
        sender.sendMessage(ChatColor.GRAY + "사냥 루프: 늑대·여우(격상) vs 가축(회색) — 기세·적립·감쇠·돌파를 몸으로 확인");
        sender.sendMessage(ChatColor.GRAY + "모션의 눈: " + ChatColor.WHITE
                + "/혼천 사다리" + ChatColor.GRAY + " (여섯 격을 나란히) · "
                + ChatColor.WHITE + "/혼천 획시험" + ChatColor.GRAY + " · "
                + ChatColor.WHITE + "/혼천 획위치" + ChatColor.GRAY + " · "
                + ChatColor.WHITE + "/혼천 모션진단");
        sender.sendMessage(ChatColor.GRAY + "모션 재적재: " + ChatColor.WHITE + "/혼천 모션 재적재"
                + ChatColor.GRAY + " (서버를 안 내리고 skill_motion.yml 을 다시 읽는다 — 클라가 안 끊긴다) · "
                + ChatColor.WHITE + "/혼천 모션 상태" + ChatColor.GRAY + " (파일이 메모리보다 새로운가)");
        sender.sendMessage(ChatColor.GRAY + "검기의 눈: " + ChatColor.WHITE + "/혼천 검기"
                + ChatColor.GRAY + " (각도·크기를 재기동 없이 즉석 조율 · "
                + ChatColor.WHITE + "검기 시험" + ChatColor.GRAY + " 이 그 자리에서 한 번 소환한다)");
        sender.sendMessage(ChatColor.GRAY + "되돌리기: " + ChatColor.WHITE
                + "/혼천 초기화 <접합|캐릭터|전부>" + ChatColor.GRAY
                + " (시험용 — 두 번 쳐야 지운다. 백업은 항상 뜬다. 세계는 안 건드린다)");
        return true;
    }

    /**
     * <b>/혼천 수련 [과목] [구간]</b> — 하루 다섯 구간을 무엇에 쓸 것인가.
     *
     * <p>이것이 성장의 축이다. 경지는 <b>천장</b>을 정하고, 배분은 <b>어떤 사람이 되는가</b>를 정한다.
     * 그전까지 같은 경지의 두 사람은 코드 수준에서 완전히 같은 사람이었다.
     *
     * <p>인자 없이 부르면 지금의 배분과 원장을 보여준다. 과목·구간은 <b>등록부가 댄다</b>
     * ({@code training.yml curriculum}) — 코드가 이름을 지어내지 않는다.
     */
    private boolean training(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "마크에서 쳐라.");
            return true;
        }
        Growth growth = Growth.get();
        if (growth == null) {
            sender.sendMessage(ChatColor.RED + "성장 축이 서지 않았다 (training.yml).");
            return true;
        }
        PlayerLedger ledger = plugin.ledger(player.getUniqueId());
        int perDay = growth.segmentsPerDay();
        if (args.length < 3) {
            player.sendMessage(ChatColor.GOLD + "수련 배분 — 하루 " + perDay + "구간");
            int used = 0;
            for (Map.Entry<String, Growth.Subject> e : growth.subjects().entrySet()) {
                int seg = ledger.curriculum().getOrDefault(e.getKey(), 0);
                used += seg;
                player.sendMessage(ChatColor.GRAY + "  " + e.getKey() + " "
                        + ChatColor.WHITE + "▮".repeat(seg) + ChatColor.DARK_GRAY + "▯".repeat(perDay - seg)
                        + ChatColor.GRAY + "  " + e.getValue().name());
            }
            player.sendMessage(ChatColor.DARK_GRAY + "  남은 구간 " + (perDay - used)
                    + " · /혼천 수련 <과목> <구간>");
            return true;
        }
        String subject = args[1];
        if (!growth.subjects().containsKey(subject)) {
            player.sendMessage(ChatColor.RED + "그런 과목은 없다: " + subject
                    + ChatColor.GRAY + " (" + String.join(", ", growth.subjects().keySet()) + ")");
            return true;
        }
        int want;
        try {
            want = Integer.parseInt(args[2]);
        } catch (NumberFormatException notNumber) {
            player.sendMessage(ChatColor.RED + "구간은 숫자다 (0~" + perDay + ").");
            return true;
        }
        int others = 0;
        for (String key : growth.subjects().keySet()) {
            if (!key.equals(subject)) {
                others += ledger.curriculum().getOrDefault(key, 0);
            }
        }
        if (want < 0 || others + want > perDay) {
            player.sendMessage(ChatColor.RED + "하루는 " + perDay + "구간뿐이다 — 남은 것은 "
                    + (perDay - others) + "구간.");
            return true;
        }
        ledger.setSegments(subject, want);
        player.sendMessage(ChatColor.GOLD + subject + " → " + want + "구간 "
                + ChatColor.GRAY + "(내일부터 그렇게 쌓인다)");
        plugin.tutorial().bump(player, "수련");   // 뿌리내림 (B-178) — 배분이 실제로 섰을 때만
        return true;
    }

    /**
     * <b>/혼천 접속</b> — <b>코드를 내지 않는다.</b> 초대를 주고, 어디서 무엇을 칠지 말해 주는 손이다.
     *
     * <p><b>★ 그리고 초기화한다.</b> 사용자의 말: <i>"발판 밟을 때마다 코드 초기화를 시켜야 할 듯."</i>
     * 코드는 없어졌지만 원리는 남는다 — <b>다시 부르면 낡은 청은 죽는다</b>
     * ({@link WorldBridge#linkReset}). 사람이 발판을 다시 밟는 것은 "처음부터 다시 하겠다"는 뜻이고,
     * 그때 낡은 청이 살아 있으면 ① 죽은 줄 알았던 창을 나중에 실수로 수락하거나 ② 두 청이 경쟁한다.
     * <b>한 몸에 살아 있는 청은 언제나 하나뿐</b>이다.
     *
     * <p>★ 발판({@code Antechamber.stepPlate})은 {@code performCommand("혼천 접속")} 로 <b>이 함수를
     * 대신 부를 뿐</b>이다 — 발판에 따로 넣은 로직은 없다. 손으로 친 것과 발판으로 밟은 것이 <b>같은 함수</b>를
     * 지나므로 둘이 어긋날 자리가 없다.
     */
    /**
     * <b>/혼천 초기화 &lt;접합|캐릭터|전부&gt;</b> — 시험을 위해 되돌린다.
     *
     * <p><b>자기 자신만</b> 지운다 — 남을 지우는 길은 <b>없다</b> (인자에 남의 이름을 댈 칸이 아예 없다).
     * 콘솔에서도 못 친다: 지울 <b>몸</b>이 없기 때문이다. 남을 지워야 하면 디스코드에서
     * {@code /초기화 대상:@아무개} 를 쳐라 (거기서 서버 관리자를 검사한다).
     *
     * <p><b>두 번 쳐야 지운다.</b> 첫 번째는 무엇이 사라지는지 말하고 멈춘다 ({@link Reset#command}).
     */
    private boolean wipe(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            // ★ 콘솔에는 몸이 없다. "전원 초기화" 같은 문을 열어 두지 않는다
            sender.sendMessage(ChatColor.RED + "마크에서 쳐라 — 초기화는 **자기 몸**에만 듣는다. "
                    + "남을 되돌리려면 디스코드에서 /초기화 대상:@아무개");
            return true;
        }
        Reset reset = plugin.reset();
        if (reset == null || reset.locked()) {
            player.sendMessage(ChatColor.RED + "초기화가 잠겨 있다 — 등록부(config/reset.yml)를 못 읽었다"
                    + (reset == null ? "." : ": " + reset.fault()));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(ChatColor.GOLD + "/혼천 초기화 <" + String.join("|", reset.scopes()) + ">");
            player.sendMessage(ChatColor.GRAY + "  접합 — 마크의 몸과 디스코드의 이름을 끊는다 (캐릭터는 남는다)");
            player.sendMessage(ChatColor.GRAY + "  캐릭터 — 유년의 기억부터 다시 (마크의 몸·짐은 그대로)");
            player.sendMessage(ChatColor.GRAY + "  전부 — 나루(입도진)부터 다시 (몸·원장·금고까지)");
            player.sendMessage(ChatColor.DARK_GRAY + "  백업은 항상 뜬다. 세계(청하현·사람·소문)는 안 건드린다.");
            return true;
        }
        reset.command(player, args[1]);
        return true;
    }

    private boolean link(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "마크에서 쳐라.");
            return true;
        }
        String linked = WorldBridge.linkedName(player.getUniqueId());
        if (linked != null) {
            player.sendMessage(LinkGate.already(linked));
            return true;   // 이미 이어져 있다 — 청을 낼 일이 없다 (끊는 것은 디스코드에서)
        }
        // ★ 초기화 — 이 몸에게 살아 있던 낡은 청은 여기서 죽는다 (봇의 장부에서도 폐기된다)
        WorldBridge.linkReset(player.getUniqueId(), player.getName());
        player.sendMessage(LinkGate.invite(player.getName()));
        return true;
    }

    /**
     * ★★ <b>/혼천 수락 &lt;토큰&gt;</b> · <b>/혼천 거절 &lt;토큰&gt;</b> — <b>접합의 결속 순간.</b>
     *
     * <p>사람이 치는 명령이 아니다 — 화면에 뜬 <b>[잇는다] / [아니다]</b> 클릭이 대신 친다 (RUN_COMMAND).
     *
     * <p><b>★ 여기가 도용을 막는 자리다.</b> {@link WorldBridge#linkDecision} 이
     * {@code player.getUniqueId()} 를 <b>청에 적힌 몸</b>과 대조한다. 토큰을 어깨너머로 본 자가 제 화면에서
     * 같은 명령을 쳐도 — 몸이 다르므로 {@code NOT_YOURS} 다. <b>토큰은 열쇠가 아니라 지목이다.</b>
     * (그리고 봇이 다리 건너에서 <b>같은 대조를 한 번 더</b> 한다 — jsonl 은 파일이므로 믿지 않는다.)
     */
    private boolean linkDecide(CommandSender sender, String[] args, boolean accept) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "몸이 있어야 답한다.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(ChatColor.GRAY + "청이 없다 — 디스코드에서 먼저 청하라 (/혼천 접속).");
            return true;
        }
        WorldBridge.Decision d = WorldBridge.linkDecision(player.getUniqueId(), args[1], accept,
                player.getName());
        switch (d) {
            case ACCEPTED -> {
                // ★ 봇의 장부가 정본이다 — 여기서 "이어졌다"고 단정하지 않는다.
                //   다리를 건너 확정되면 다음 스냅숏의 links 가 이 몸의 이름을 데려온다 (그때가 진짜다).
                player.sendMessage(ChatColor.GREEN + WorldBridge.gateText("accepted",
                        "이었다 — 강호가 그대의 이름을 받아 적는다.").replace("{name}", player.getName()));
                player.sendMessage(ChatColor.DARK_GRAY + "(장부가 확정하면 사이드바의 이름이 바뀐다 — 몇 초)");
            }
            case REJECTED -> player.sendMessage(ChatColor.GRAY
                    + WorldBridge.gateText("rejected", "청을 물렸다."));
            // ★★ 남의 청 — 여기서 죽는다. 토큰을 알아도 남의 몸은 이을 수 없다
            case NOT_YOURS -> player.sendMessage(ChatColor.RED
                    + WorldBridge.gateText("not_yours", "그 청은 그대에게 온 것이 아니다."));
            case EXPIRED -> player.sendMessage(ChatColor.RED
                    + WorldBridge.gateText("expired", "그 청은 이미 죽었다. 디스코드에서 다시 청하라."));
            case GONE -> player.sendMessage(ChatColor.RED
                    + WorldBridge.gateText("gone", "그런 청은 없다."));
            default -> { }
        }
        return true;
    }

    /**
     * ★★ <b>/혼천 서장 &lt;토큰&gt; &lt;n&gt;</b> — <b>책의 글자를 눌렀다.</b>
     *
     * <p><b>사람이 이것을 칠 일은 없다.</b> {@link SeojangBook} 의 책장 안 클릭
     * ({@code ClickEvent.runCommand})이 대신 친다 — 접합의 [잇는다] 와 같은 문법이다.
     *
     * <p><b>마크는 아무것도 판정하지 않는다.</b> 번호 하나를 다리에 얹을 뿐이고, 나머지는 전부 봇이
     * 한다 (주사위·경지·성별 보정·시트). <b>토큰은 열쇠가 아니라 지목</b>이다: 낡은 책을 눌러도,
     * 남의 토큰을 주워 눌러도 <b>봇이 거른다</b> (지금 그 장면의 것이 아니면 버린다).
     * 그래서 여기서는 <b>몸이 있는가</b>만 본다 — 자물쇠는 다리 건너에 있다.
     */
    private boolean seojangPick(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "몸이 있어야 책을 편다.");
            return true;
        }
        if (args.length < 3) {
            player.sendMessage(ChatColor.GRAY + "서책이 없다 — 강호에 이름을 올려라 (/혼천 접속).");
            return true;
        }
        int n;
        try {
            n = Integer.parseInt(args[args.length - 1]);
        } catch (NumberFormatException e) {
            return true;   // 책이 내는 값은 언제나 수다 — 아니면 사람이 손으로 친 것이다
        }
        // ★ 토큰은 args[1..끝-1] 을 도로 잇는다 (B-142) — 판정어의 공백('부분 성공' ·
        //   '아슬아슬한 성공')이 명령 인자를 갈라, 책이 낸 클릭이 위의 침묵 반환으로 죽었다.
        //   책은 토큰을 통짜로 내지만 명령 인자는 공백을 모른다 — 잇는 것은 받는 쪽의 몫이다.
        String token = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length - 1));
        // ★ 낡은 책의 클릭 — 그 자리에서 말해 준다 (봇도 거르지만, 사람은 **지금** 알아야 한다)
        String live = SeojangBook.get() == null ? null : SeojangBook.get().tokenOf(player.getUniqueId());
        if (live != null && !live.equals(token)) {
            player.sendMessage(SeojangBook.legacy(
                    SeojangBook.get().stale()));
            return true;
        }
        WorldBridge.seojangChoice(player.getUniqueId(), player.getName(), token, n);
        player.sendMessage(SeojangBook.legacy(SeojangBook.get().waiting()));
        player.closeInventory();   // 책을 덮는다 — 다음 장이 오면 저절로 펼쳐진다
        return true;
    }

    /**
     * <b>/혼천 태세 [회피|막기|흘리기|자동]</b> — <b>맞는 쪽의 선택.</b>
     *
     * <p>세 방어는 세 능력치의 뒷면이다 — 신법(민첩)→회피 · 외공(근력)→막기 · 심안(감각)→흘리기.
     * 그전까진 이 셋이 <b>config 에만 있고 엔진에 없었다</b>. 수련의 절반이 살 곳이 없었다.
     *
     * <p><b>없어도 굴러간다.</b> 기본값은 <b>자동</b>이고, 자동은 그 사람의 수련을 보고 가장 좋은 태세를 고른다 —
     * 명령을 모르는 자가 제 빌드에 안 맞는 태세로 죽으면 안 된다. 그리고 <b>몸짓이 언제나 덮어쓴다</b>
     * (방패=막기 · 웅크림=흘리기 · 달림=회피). 바닐라가 이미 가진 세 자세다 —
     * 그래서 <b>남의 눈에도 보인다.</b> "보이는 것 = 맞는 것"이 방어에도 선다.
     */
    private boolean stance(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "몸이 있어야 태세가 있다.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(ChatColor.GRAY + "/혼천 태세 <회피|막기|흘리기|자동>");
            player.sendMessage(ChatColor.DARK_GRAY
                    + "  몸짓이 덮어쓴다 — 방패=막기 · 웅크림=흘리기 · 달림=회피");
            return true;
        }
        plugin.skills().setStance(player, args[1]);
        return true;
    }

    /**
     * <b>/혼천 시트</b> — 나는 누구인가.
     *
     * <p><b>장부의 정본은 봇이다.</b> 경지·화후·승급은 <b>강호가 인정하는 것</b>이고, 그 장부는 재기동을 넘어
     * 살아남는 유일한 곳이다. 마크는 <b>몸</b>이다 — 수련하고, 싸우고, 그 증분을 다리로 올려 보낸다.
     * 내려오는 것은 봇의 확정값(절대값)이므로 <b>두 장부가 영구히 갈라질 수 없다.</b>
     *
     * <p>접합 전이면 시트가 없다 — 그리고 그 사실을 <b>소리내어 말한다.</b>
     * 조용한 게이트가 가장 나쁘다: 반쪽 세계에서 노는 줄도 모른 채 놀게 된다.
     */
    private boolean sheetInfo(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "몸이 있어야 시트가 있다.");
            return true;
        }
        PlayerLedger led = plugin.ledger(player.getUniqueId());
        if (!led.linked()) {
            player.sendMessage(ChatColor.RED + "너는 아직 강호에 없다 — " + ChatColor.WHITE + "/혼천 접속");
            return true;
        }
        String realm = led.realm(plugin.skillEngine().baseRealm());
        player.sendMessage(ChatColor.GOLD + "── " + WorldBridge.linkedName(player.getUniqueId())
                + " · " + realm + " ──");
        Growth growth = Growth.get();
        if (growth != null) {
            growth.sheet(led, realm).forEach(player::sendMessage);
        }
        player.sendMessage(ChatColor.GRAY + "심법 " + ChatColor.WHITE
                + (led.simbeop() == null ? "없음 (개화 전)" : led.simbeop())
                + ChatColor.GRAY + " · 내공 " + ChatColor.WHITE + String.format("%.2f", led.naegong()));
        player.sendMessage(ChatColor.GRAY + "주무공 " + ChatColor.WHITE
                + (led.primaryArt() == null ? "없음 (무공 백지)" : led.primaryArt())
                + ChatColor.GRAY + " · 실전 마크 " + ChatColor.WHITE + led.marks실전());
        return true;
    }

    /** <b>/혼천 입도</b> — 나루로 돌아간다 (몸을 다시 익히고 싶은 자를 위해) */
    private boolean antechamber(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "마크에서 쳐라.");
            return true;
        }
        // /혼천 입도 재조성 — 나루를 **다시 세운다** (재접속 없이 허수아비·글판을 다시 세운다)
        if (args.length >= 2 && "재조성".equals(args[1])) {
            plugin.antechamber().rebuild(player);
            return true;
        }
        plugin.antechamber().enter(player);
        return true;
    }

    /**
     * <b>/혼천 도강</b> — 강을 건넌다.
     *
     * <p>문은 <b>배</b>다. 그리고 사공은 <b>이름 없는 자를 태우지 않는다</b> — 나룻배는 장부에 적고 건넨다.
     * 그것이 접합(接合)이 무엇인지에 대한 이 세계의 은유다.
     *
     * <p>다만 <b>봇이 꺼져 있으면 그냥 건넨다.</b> 장부가 없어서 못 적는 것을 사람 탓으로 돌릴 수 없다 —
     * 갇히지 않는다. 그 대신 사공이 말해 준다: "네가 벤 것은 아무 데도 적히지 않는다."
     */
    private boolean crossRiver(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "마크에서 쳐라.");
            return true;
        }
        plugin.antechamber().cross(player);
        return true;
    }

    /**
     * <b>/혼천 지도검수</b> — 등록부의 모든 장소가 그 지형답게 서 있는가.
     *
     * <p>★ 이 눈이 없어서 **60곳이 조용히 통과하고 있었다.** 안 지어진 땅은 `Zone` 이 없고,
     * `Zone` 이 없으면 지역 검수가 **아예 안 불린다** → 위반 0건. <b>짓지 않으면 위반이 없다.</b>
     * 침묵이 성공으로 읽혔다.
     *
     * <p>그래서 이 눈은 <b>안 지은 곳을 말한다</b>. 그리고 <b>대조군이 0건이 아니다</b> —
     * 섬(해남파·동영도)이 아직 반도에 앉을 수 있고, 그것은 실재하는 문제다.
     * <b>0건이면 그게 거짓말이다.</b> 이 눈은 조용해지기를 거부한다.
     */
    private boolean auditMap(CommandSender sender) {
        java.util.Set<String> built = new java.util.HashSet<>(plugin.regionBaseIds());
        built.add("cheongha_hyeon");   // 청하현은 원장이 아니라 앵커가 안다
        for (String line : MapAudit.audit(plugin.worldMap(), built)) {
            sender.sendMessage(line);
        }
        return true;
    }

    /**
     * <b>/혼천 땅갈아엎기 &lt;id&gt;</b> — 그 땅의 기억을 지운다. <b>다음 조성이 땅을 다시 빚는다.</b>
     *
     * <p>★ 이것이 <b>명시적 선언</b>이어야 하는 이유: 땅을 다시 빚는 것은
     * <b>지난번에 우리가 만든 것 위에 또 만드는 것</b>이다 (재조성이 산 위에 산을 쌓던 그 병).
     * 그리고 부지 탐색이 <b>우리가 바꾼 지형을 표본해</b> 다른 답을 낸다 —
     * 장강수로채가 재조성 한 번에 128칸을 옮겨 앉았다.
     *
     * <p>그러므로 <b>조용히 일어나면 안 된다.</b> 땅을 갈아엎겠다면 그렇게 말해야 한다.
     */
    private boolean forgetLand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.GRAY + "/혼천 땅갈아엎기 <지역id>");
            return true;
        }
        if (forgeWorldBarred(sender, "땅갈아엎기")) {
            return true;   // ★ 되돌리기도 세계를 발밑에서 얻는다 — 엉뚱한 세계를 갈면 그것도 사고다 (B-126)
        }
        World world = sender instanceof Player p ? p.getWorld() : org.bukkit.Bukkit.getWorlds().get(0);
        // ★ **원지(原地)로 되돌린다.** 장부만 지우는 것은 **반쪽**이고, 반쪽 명령은 사용자를 속인다 —
        //   그러면 다음 조성이 **이미 만든 산 위에 또 산을 쌓는다.**
        int n = Terraform.razeLand(world, args[1]);
        if (n < 0) {
            sender.sendMessage(ChatColor.RED + "되돌릴 수 없다 — 이 땅의 원지를 적어 두지 않았다.");
            sender.sendMessage(ChatColor.GRAY + "  원지 기록 이전에 조성된 땅이다. 장부를 지우면 "
                    + "이미 만든 산 위에 또 산을 쌓는다.");
            sender.sendMessage(ChatColor.GRAY + "  처음부터 원하면 세계 재조성이다 "
                    + ChatColor.WHITE + "(scripts/fresh_start.sh)");
            return true;   // ★ 장부를 지우지 않는다 — 반쪽으로 두느니 거절한다
        }
        sender.sendMessage(ChatColor.GOLD + args[1] + " 의 땅을 원지로 되돌렸다 (" + n
                + "열). 다음 조성이 처음부터 빚는다.");
        sender.sendMessage(ChatColor.DARK_GRAY
                + "  (높이는 되돌아오나 광석·초목의 결은 못 되살린다 — 우리는 그것을 안 적었다)");
        return true;
    }

    /**
     * <b>/혼천 명명 &lt;문파&gt;</b> — 애병에 문파의 얼굴을 준다.
     *
     * <p>사용자 요구: <i>"매화검인 경우 매화검처럼 생겨야 함. 손잡이에 매화 무늬가 있다던가."</i>
     *
     * <p>★ <b>계열이 문파와 맞을 때만</b> 실루엣이 바뀐다. 매화검은 <b>검</b>이다 —
     * 도(刀)에 매화를 물리면 <b>도가 검으로 보인다.</b> 어긋나면 단계만 오르고 실루엣은 계열 그대로다.
     */
    private boolean enshrine(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "마크에서 쳐라.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "/혼천 명명 <hwasan|jeomchang|jongnam|namgung"
                    + "|mudang|paengga|dangga|sorimsa>");
            return true;
        }
        String sect = args[1].toLowerCase(java.util.Locale.ROOT);
        if (!Weapons.isMyeongSect(sect)) {
            player.sendMessage(ChatColor.RED + "등록되지 않은 문파: " + sect);
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!Weapons.isWeapon(held)) {
            player.sendMessage(ChatColor.RED + "손에 병기가 없다.");
            return true;
        }
        ItemStack named = Weapons.enshrine(held, sect);
        player.getInventory().setItemInMainHand(named);
        player.sendMessage(ChatColor.GOLD + "명병(名兵) — " + sect + " 의 이름을 얻었다."
                + (sect.equals(Weapons.sectOf(named))
                ? ChatColor.GRAY + " 병기가 문파의 얼굴을 띤다."
                : ChatColor.DARK_GRAY + " (계열이 달라 실루엣은 그대로다)"));
        return true;
    }

    /**
     * <b>/혼천 판정보기</b> — 판정의 눈. <b>켠 사람에게만 보인다.</b>
     *
     * <p>성능은 {@code Metrics} 가, 등록부는 검산이, 3D 는 {@code /혼천 모션진단} 이 재는데 —
     * <b>판정만은 아무도 못 봤다.</b> 히트박스가 어디까지 닿는지, 2d6 이 무엇과 겨뤘는지,
     * 피해가 어느 층에서 깎였는지를 눈으로 본다.
     *
     * <p>★ <b>이 눈이 거짓말할 수 없는 이유</b>: 눈은 히트박스를 <b>다시 그리지 않는다.</b>
     * <b>판정이 쓰는 그 함수에 점을 물어본다</b> — "이 자리는 맞는 자리인가?"
     * 그리는 코드와 맞히는 코드가 <b>하나</b>이므로 <b>그림은 판정에 대해 거짓말할 수 없다.</b>
     * (제 원뿔을 따로 그리는 눈은 <b>없느니만 못하다</b> — 틀린 그림을 믿고 판정을 고치게 되므로.)
     */
    private boolean judgeEye(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "몸이 있어야 본다.");
            return true;
        }
        boolean on = plugin.skills().toggleEye(player);
        player.sendMessage(on
                ? ChatColor.DARK_AQUA + "판정의 눈 — 켰다 (히트박스 · 2d6 · 피해의 층이 보인다)"
                : ChatColor.GRAY + "판정의 눈 — 껐다");
        return true;
    }

    /**
     * <b>/혼천 타격보기</b> — <b>타격의 눈</b>. 한 대가 들어갈 때마다 <b>시간 구조와 히트스톱이
     * 실제로 도는가</b>를 손에 찍는다. <b>켠 사람에게만 보인다.</b>
     *
     * <p>사용자 보고("공격해도 전혀 바뀌는 게 없다")를 <b>게임 안에서</b> 반증하거나 확증하는 눈이다:
     * 선딜·지속·후딜이 몇 틱인지, 이 격이 몇 틱을 얼리는지, 넉백과 흔들림이 몇인지가 <b>매 타격</b> 찍힌다.
     * 숫자가 0 으로 찍히면 <b>등록부가 꺼져 있는 것</b>이고 (combat.yml {@code impact}),
     * 아무것도 안 찍히면 <b>이 손이 타격의 문을 안 지나는 것</b>이다 — 두 사건이 다르게 보인다.
     */
    private boolean hitEye(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "몸이 있어야 친다.");
            return true;
        }
        boolean on = plugin.skills().toggleHitEye(player);
        player.sendMessage(on
                ? ChatColor.DARK_AQUA + "타격의 눈 — 켰다 "
                        + ChatColor.GRAY + "(선딜·지속·후딜 · 히트스톱 · 넉백 · 흔들림이 매 타격 찍힌다)"
                : ChatColor.GRAY + "타격의 눈 — 껐다");
        return true;
    }
}
