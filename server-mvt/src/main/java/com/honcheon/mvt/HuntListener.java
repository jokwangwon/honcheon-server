package com.honcheon.mvt;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;
import java.util.Map;

/**
 * 사냥 = 실전 화후 적립 (1막 루프의 인게임 배선).
 * 기세 색(첫 타격 시 표시) → 처치 즉시 같은 틱 피드백(+N일 액션바·적립음) → 돌파 타이틀.
 * 수치는 전부 ProgressionEngine(=config/cultivation.yml) — 여기는 배선만 한다.
 */
public final class HuntListener implements Listener {

    /** 짐승의 격 (beast_ranks) — MVT 플레이어는 '범인' 고정이므로 격차로 환산해 둔다 */
    private static final Map<EntityType, String> GAP_BY_MOB = Map.of(
            EntityType.WOLF, "상수",          // 들짐승 = 삼류 상당 — 범인에게는 격상
            EntityType.FOX, "상수",
            EntityType.POLAR_BEAR, "상수",    // 맹수 = 일류 상당
            EntityType.PIG, "압도적_하수",     // 가축 — 배울 것이 없다
            EntityType.COW, "압도적_하수",
            EntityType.SHEEP, "압도적_하수",
            EntityType.CHICKEN, "압도적_하수");

    private static final String HUNT_SKILL = "사냥";

    /** 짐승의 상당 경지 (npc_combat beast_ranks) — 몹 레벨 유도용 (성장 v3 XP · 표에 없으면 Lv1) */
    private static final Map<EntityType, String> REALM_BY_MOB = Map.of(
            EntityType.WOLF, "삼류",          // 들짐승 = 삼류 상당
            EntityType.FOX, "삼류",
            EntityType.POLAR_BEAR, "일류");   // 맹수 = 일류 상당

    private final HoncheonMvt plugin;

    public HuntListener(HoncheonMvt plugin) {
        this.plugin = plugin;
    }

    /** 첫 접촉의 기세 읽기 — 명패 색 대신 MVT는 액션바 색으로 (몹 레벨 색 문법) */
    @EventHandler
    public void onFirstContact(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        String gap = GAP_BY_MOB.get(event.getEntityType());
        if (gap == null) {
            return;
        }
        ChatColor color = switch (gap) {
            case "상수" -> ChatColor.RED;
            case "동수" -> ChatColor.YELLOW;
            case "하수" -> ChatColor.WHITE;
            default -> ChatColor.GRAY;
        };
        // 기세 글리프(백색 비트맵)는 색 코드로 틴트된다 — 리소스팩 미설치 시 □
        // 순간 문구는 flash 로 (B-116) — 맨 sendActionBar 는 다음 statusBar 틱(≤0.2초)에 덮인다
        plugin.skills().flash(player, color + Glyphs.GISE + " 기세: " + gap.replace('_', ' '));
    }

    @EventHandler
    @SuppressWarnings("deprecation")
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        String gap = GAP_BY_MOB.get(event.getEntityType());
        SkillEngine.Npc npc = npcOf(event);
        if (gap == null && npc == null) {
            return;   // 등록부 밖의 죽음 — 배울 것도 경험도 없다
        }
        // 부산물은 접합과 무관하다 — 가죽은 몸이 벗기는 것이지 장부가 주는 것이 아니다
        if (event.getEntityType() == EntityType.WOLF || event.getEntityType() == EntityType.FOX) {
            event.getDrops().add(pelt(event.getEntityType() == EntityType.WOLF ? "늑대 가죽" : "여우 가죽"));
        }
        if (gap != null) {
            accrue(killer, event, gap);
        }
        grantXp(killer, event, npc);
    }

    /** 등록부의 몸인가 — 산적·행인·짐승 개체 (HuntingGrounds 가 심는 PDC 표식을 읽는다) */
    private SkillEngine.Npc npcOf(EntityDeathEvent event) {
        String id = HuntingGrounds.tag(event.getEntity(), HuntingGrounds.KEY_ID);
        return id == null ? null : plugin.skillEngine().npc(id);
    }

    /**
     * 성장 v3 XP (B-135 단계 4) — 처치 = 몹 레벨 × 등급 계수 (클래식 고정값 · 사용자 확정 2026-07-24).
     * 몹 레벨 = 등록값(npcs/*.yml level) 우선, 없으면 상당 경지의 자격 레벨. 레벨업·포인트는 봇이 굴린다.
     * ★정예·두목 등급 지정 필드는 아직 등록부에 없다 — 전부 잡졸 계수 (등록부 확장 때 함께 배선).
     */
    private void grantXp(Player killer, EntityDeathEvent event, SkillEngine.Npc npc) {
        SkillEngine engine = plugin.skillEngine();
        PlayerLedger ledger = plugin.ledger(killer.getUniqueId());
        if (!engine.xpEnabled() || !ledger.linked()) {
            return;
        }
        int xp = engine.killXp(engine.mobLevel(npc, REALM_BY_MOB.get(event.getEntityType())), "잡졸");
        if (xp <= 0) {
            return;
        }
        ledger.pendXp(xp);
        plugin.skills().flash(killer, ChatColor.GOLD + "+" + xp + " 경험");
    }

    /**
     * 벤 것이 손에 남는가 — <b>실전 화후.</b> 다섯 과목 중 실전으로 자라는 것은 초식뿐이다.
     *
     * <p><b>시계는 봇의 세계일이다</b> (현실 하루 = 세계 1일). 예전에는 {@code getFullTime()/24000} —
     * <b>마크의 하루는 현실 20분</b>이었다. 일일 상한(cappedGrant)과 반복 감쇠가 20분마다 풀렸다는 뜻이고,
     * 그것은 상한이 아니다. 달력은 하나여야 한다.
     *
     * <p><b>접합되지 않은 몸에는 쌓이지 않는다.</b> 장부는 봇의 것이다 — 강호에 이름이 없는 자의 화후를
     * 마크가 혼자 적으면 그것은 장부가 아니라 위조다. (그 사실은 {@code SkillListener.nag} 가 말해 준다.)
     */
    private void accrue(Player killer, EntityDeathEvent event, String gap) {
        PlayerLedger ledger = plugin.ledger(killer.getUniqueId());
        long worldDay = WorldBridge.worldDay();
        if (worldDay <= 0 || !ledger.linked()) {
            return;
        }
        ledger.rollDay(worldDay);

        // 목숨 무게: 결과가 아니라 상황 — MVT 근사: 처치 시점 체력 절반 미만 = 생사
        boolean deadly = killer.getHealth() < killer.getMaxHealth() * 0.5;
        String stakes = deadly ? "생사" : "실전_사냥";

        String mobType = event.getEntityType().name().toLowerCase(Locale.ROOT);
        double accrual = plugin.progression().combatAccrualDays(gap, stakes, ledger.repetitionOf(mobType));
        double granted = plugin.progression().cappedGrant(ledger.grantedToday(), accrual);
        ledger.countRepetition(mobType);

        if (granted <= 0) {
            plugin.skills().flash(killer,
                    ChatColor.GRAY + (accrual <= 0 ? "배울 것이 없다" : "오늘은 몸이 벅차다"));
            return;
        }

        int levelBefore = ledger.levelOf(HUNT_SKILL, plugin.progression());
        ledger.grant(HUNT_SKILL, granted);
        ledger.pendSkill(HUNT_SKILL, granted);   // 다리로 — 시트를 적는 것은 봇이다
        int levelAfter = ledger.levelOf(HUNT_SKILL, plugin.progression());

        // 마크 — 격상 상대 + 생사 = 사선. ★ 이것이 봇의 승급 요건('실전 마크 N')을 채우는 값이다
        if (deadly && "상수".equals(gap)) {
            ledger.mark사선();
            ledger.pend사선();
        } else {
            ledger.mark실전();
            ledger.pend실전();
        }
        plugin.skills().pushLedger(killer, ledger, plugin.skills().state(killer));

        // 처치 즉시 같은 틱 피드백 — 수련 일수가 경험치 숫자의 자리를 맡는다
        plugin.skills().flash(killer,
                ChatColor.AQUA + String.format("사냥이 손에 익는다 (+%.2f일)", granted));
        killer.playSound(killer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);

        if (levelAfter > levelBefore) {
            killer.sendTitle(ChatColor.GOLD + "오늘따라 손이 가볍다",
                    ChatColor.YELLOW + "사냥 숙련 " + levelBefore + " → " + levelAfter, 10, 50, 20);
            killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        }
    }

    @SuppressWarnings("deprecation")
    private static ItemStack pelt(String name) {
        ItemStack item = new ItemStack(Material.LEATHER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.WHITE + name);
        item.setItemMeta(meta);
        return item;
    }

    // ★ 묘비 — private actionBar(맨 sendActionBar)는 여기 살았다 (2026-07-16 철거, B-116).
    //   순간 문구(기세·적립)는 전부 SkillListener.flash — HudLine 이 한 줄을 중재한다.
}
