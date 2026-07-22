package com.honcheon.mvt;

import com.honcheon.core.rules.RulesConfig;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 무공의 모션 — 조작을 실제 시전으로 바꾸는 층. 규칙은 SkillEngine, 연출은 여기.
 *
 * <p>조작 매핑 (docs/design/mc_action_mapping.md 1·3-B장 + skill_motion.md):
 * <table>
 *   <tr><td>좌클릭 (검·도)</td><td>기본 무공 콤보 — 육합검 1·2타(외공기 0) → 3타(발경 1)</td></tr>
 *   <tr><td>우클릭 (Shift 없이)</td><td>방어 선언 — 방어_전념(+2)의 MC 환산. 앞머리는 패링 창 (B-015 · active_guard)</td></tr>
 *   <tr><td>Shift + 우클릭</td><td>격 태세 순환 — 외공 → 발경 → 검기 → 강기 → 외공 (경지 게이트 통과분만)</td></tr>
 *   <tr><td>Shift + 좌클릭 (검기+ 태세)</td><td>기 발출(쏨) — 검기 참격 3 / 강기 포 6</td></tr>
 * </table>
 *
 * <p>불변식:
 * <ul>
 *   <li><b>연출도 config 다</b> — 이 파일에는 파티클 이름도 사운드 키도 없다. 전부
 *       {@code config/skill_motion.yml} 의 등록부를 이름으로 부른다 (등록제 규약).
 *       {@code tools/motion_audit.py} ⑦이 하드코딩을 잡는다.</li>
 *   <li>중앙 티커 1개 (performance.yml F-P2) — 예약 타격·텔레그래프·유지비·HUD 가 한 태스크를 공유한다</li>
 *   <li>파티클은 SkillHud.emit() 을 통해서만 — 예산 초과 시 연출만 강등, 판정은 불변</li>
 *   <li><b>보이는 것 = 맞는 것</b> — 궤적(호·선·원·시·돌·진)은 히트박스와 같은 모양으로 그린다.
 *       상대가 본 것으로 물러설 수 있어야 규칙이 정직하다.</li>
 * </ul>
 */
public final class SkillListener implements Listener {

    private static final String CD_SHOT = "발출";
    /** 기본 초식(무공 없는 손)의 연출 간격 — 바닐라 연타에 획이 겹쳐 쌓이지 않게 */
    private static final String CD_BASIC = "기본초식";
    /** NPC 격 시전 간격 — 응집과 응집 사이 (라운드 = 두름 과금 주기와 같은 눈금) */
    private static final String CD_QI = "npc_격";
    /** NPC 근접 사거리 — 이 안에 들어와야 응집을 시작한다 (config 등록 대기: npc_combat.yml reach) */
    private static final double NPC_REACH = 3.5;
    /** 응집이 끝난 뒤 격이 실려 있는 창 — 바닐라 근접 AI 의 스윙 타이밍을 우리가 못 정한다 (근사) */
    private static final int NPC_HOT_TICKS = 20;
    /**
     * NPC 의 고정 판정치 — {@code combat.yml attack.defender_bonus}: <i>"+2d6(플레이어) 또는 +7(NPC)"</i>.
     * <b>한쪽만 굴린다</b> — 플레이어가 2d6 을 굴리고 NPC 는 기댓값(7)으로 선다.
     * ({@code tools/combat_audit.py margin_dist} 가 쓰는 것과 같은 규약이다 — 도구와 엔진은 같은 셈을 한다)
     */
    private static final int NPC_JUDGMENT = 7;
    // 태세의 글자가 액션바에 머무는 시간(옛 STANCE_READ_TICKS 15)은 등록부로 갔다 —
    // skill_motion.yml hud.flash_read_ticks (B-116: 하드코딩 금지 · engine.hudFlashTicks()).
    // NPC 내력 회복의 하드코딩(라운드당 1)은 제거됐다 — 이제 조식(internal_energy.yml
    // recovery.in_combat.조식)을 플레이어와 **같은 함수**로 탄다 (regulateBreath).

    private final HoncheonMvt plugin;
    /** 등록부. {@code final} 이 아닌 이유는 {@link #rebind} — 핫 리로드가 이 참조를 갈아끼운다 */
    private SkillEngine engine;
    private final SkillHud hud;

    /** 파티클의 유일한 창구 — 예산·관람자·LOD 가 여기서 걸린다 ({@link QiGeometry} 가 이 손을 빌린다) */
    SkillHud hud() {
        return hud;
    }

    /**
     * 내공/내력 보스바 — XP바에서 이사 (★사용자 확정 2026-07-15: XP바 = v3 경험/레벨).
     * 옛 {@code hud.energyBar} 가 서 있던 자리마다 {@link EnergyBossBar#update} 가 선다.
     * 문구·색·구간은 등록부(skill_motion.yml hud.energy_bossbar)의 것 — 코드가 지어내지 않는다.
     */
    private final EnergyBossBar energyBossBar;
    /** 3D 모션 층 — 파티클 위에 얹는다. 이것이 통째로 실패해도 무공은 보인다 (불변식 ㅁ) */
    private final SkillDisplay display;
    private final Map<UUID, SkillEngine.State> states = new HashMap<>();
    /** NPC 의 격 — 대칭 원칙. 같은 State 를 쓴다 (내력·두름·다운캐스트가 같은 규칙이라는 뜻이다) */
    private final Map<UUID, SkillEngine.State> npcStates = new HashMap<>();
    /** 무기 격돌 누적 — 몸(플레이어·NPC)당. breaks_at 회째에 병기가 부러진다 (weapon_break) */
    private final Map<UUID, Integer> clashCounts = new HashMap<>();
    /**
     * <b>몸에 밴 태세</b> — {@code /혼천 태세 <회피|막기|흘리기|자동>}. 없으면 등록부의 기본값(자동).
     * 몸짓(방패·웅크림·질주)이 그 순간 이것을 <b>덮어쓴다</b> (combat.yml defender_stance_mc.precedence).
     */
    private final Map<UUID, String> stancePin = new HashMap<>();
    /** 지금 이 몸이 서 있는 태세 — HUD 가 그린다 (화면이 판정에 대해 거짓말하지 않게) */
    private final Map<UUID, String> stanceNow = new HashMap<>();
    /**
     * <b>능동 태세 선언</b> — 맨 우클릭 (B-015). {@code [선언 틱, 만료 틱]}.
     * 재선언은 만료만 늘린다 — <b>패링의 시계([0])는 첫 선언의 것</b>이다: 손이 이미 올라가 있으면
     * 다시 잴 수 없다. 그래서 연타가 정답이 되지 못한다 (등록부 active_guard 의 왜 참조).
     */
    private final Map<UUID, long[]> guardDeclare = new HashMap<>();
    /** 虛 의 기록 — 이 몸의 방어가 마지막으로 연 허 (SkillCast 의 관문이 {@link #opening} 으로 읽는다) */
    private final Map<UUID, Long> lastParry = new HashMap<>();
    private final Map<UUID, Long> lastDodge = new HashMap<>();
    private final Map<UUID, Long> lastStanceWin = new HashMap<>();
    /** 몸이 지금 원점에서 얼마나 벗어나 있는가 — 회전은 <b>정확히 이만큼만</b> 되돌린다 (순증 금지) */
    private final Map<UUID, Posture> postures = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    /**
     * <b>얼어붙은 몸</b> — 히트스톱 (combat.yml impact.hitstop).
     *
     * <p>맞는 순간 몇 틱이 멎는다. <b>클라이언트의 프레임은 서버가 못 멈춘다</b> — 대신 매 틱
     * <b>속도를 0 으로 못질</b>한다 (달리던 몸이 공중에서 멎는다). 그리고 <b>풀리는 순간 넉백이 터진다</b>:
     * 멈췄다 → 날아간다. <b>그 순서가 무게를 만든다</b> (동시에 하면 그냥 밀리는 것이고,
     * 멈췄다 날아가면 <i>맞은</i> 것이다).
     */
    private final Map<UUID, Frozen> frozen = new HashMap<>();
    /** 【디버그】 타격의 눈 — {@code /혼천 타격보기}. 켠 사람의 손에만 산수가 뜬다 */
    private final Set<UUID> hitEyes = new HashSet<>();

    /** @param release 정지가 풀리는 순간 터질 넉백 (없으면 null) */
    private record Frozen(long until, Vector release) {
    }

    private long tick;
    /** 자기 피해 재진입 가드 — 엔진이 준 피해를 넣을 때 이 리스너가 다시 잡지 않도록 */
    private boolean applying;

    private record Pending(long due, Runnable action) {
    }

    /**
     * 능동 태세 등록부 — {@code combat.yml attack.defender_stance_mc.active_guard} (B-015).
     * 지속시간은 여기 없다: '이번 라운드' = {@link SkillEngine#roundTicks} — realtime 절이 정본이다
     * (같은 값을 두 등록부에 적으면 언젠가 갈라진다 — B-106 의 병).
     */
    private record ActiveGuard(boolean enabled, String stance, int commitBonus,
                               int parryTicks, int openingTicks, boolean breakOnAttack) {
    }

    private final ActiveGuard activeGuard;

    /** 안전 지역 등록부 (B-006) — training.yml {@code location_safety}. 적힌 순서 = 판정 우선순위 */
    private final List<SafetyRule> safetyRules;

    /** 인구층의 표식 (Populace 스폰 규약과 같은 키) — 행인·지역 사람의 신원. 읽기 전용 */
    private final org.bukkit.NamespacedKey keyPopulace;
    private final org.bukkit.NamespacedKey keyRegionNpc;

    public SkillListener(HoncheonMvt plugin, SkillEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
        this.hud = new SkillHud(engine);
        this.energyBossBar = loadEnergyBossBar(plugin, engine);
        this.display = new SkillDisplay(plugin, engine);
        this.activeGuard = loadActiveGuard(plugin);
        this.safetyRules = loadSafetyRules(plugin);
        this.keyPopulace = new org.bukkit.NamespacedKey(plugin, "populace");
        this.keyRegionNpc = new org.bukkit.NamespacedKey(plugin, "region_npc");
    }

    /**
     * 핫 리로드 — 새 등록부를 <b>제 아래 층까지</b> 내려보낸다 ({@code /혼천 모션 재적재}).
     *
     * <p>이 층이 {@link SkillHud}·{@link SkillDisplay}·{@link EnergyBossBar} 를 쥐고 있으므로,
     * 갈아끼우는 손도 여기서 한 번에 내려간다 — <b>한 곳만 갈아끼우면 나머지가 옛 등록부를 계속 읽는다</b>
     * (그것이 "재적재했는데 안 먹었다"의 정체다).
     *
     * <p><b>플레이어 상태({@link #states}·태세·경직·동결)는 안 건드린다.</b> 그것은 config 가 아니라
     * 지금 살아 있는 몸이다 — 재적재가 사람의 내력을 되돌리면 안 된다.
     */
    void rebind(SkillEngine engine) {
        this.engine = engine;
        hud.rebind(engine);
        display.rebind(engine);
        if (energyBossBar != null) {
            energyBossBar.rebind(engine);
        }
    }

    /**
     * 등록부 판독 — SkillEngine 은 이번 라운드 동결이라 이 층이 직접 읽는다
     * (SkillCast 가 skill_mechanics 를 직접 읽는 것과 같은 전례). 섹션이 없으면 <b>조용히 꺼진다</b> —
     * 등록부가 앞서고 코드가 따른다: 등록되지 않은 능동 태세는 존재하지 않는 것이다.
     */
    private static ActiveGuard loadActiveGuard(HoncheonMvt plugin) {
        try {
            Map<String, Object> ag = RulesConfig.section(RulesConfig.section(RulesConfig.section(
                    RulesConfig.load(plugin.getDataFolder().toPath()
                            .resolve("config").resolve("combat.yml")),
                    "attack"), "defender_stance_mc"), "active_guard");
            return new ActiveGuard(true,
                    String.valueOf(ag.getOrDefault("stance", "막기")),
                    RulesConfig.intValue(ag.getOrDefault("commit_bonus", 2)),
                    RulesConfig.intValue(ag.getOrDefault("parry_window_ticks", 6)),
                    RulesConfig.intValue(ag.getOrDefault("opening_window_ticks", 12)),
                    !Boolean.FALSE.equals(ag.get("break_on_attack")));
        } catch (RuntimeException missing) {
            return new ActiveGuard(false, "막기", 0, 0, 0, true);
        }
    }

    /**
     * 내공/내력 보스바 등록부 판독 — {@code skill_motion.yml hud.energy_bossbar} (loadActiveGuard 와
     * 같은 전례: SkillEngine 은 동결이라 이 층이 직접 읽는다). <b>침묵하는 실패 금지</b>: 절이 없거나
     * 이름이 틀리면 {@link EnergyBossBar} 가 소리내고 채널을 열지 않는다 — 조용한 폴백 색은 없다.
     */
    private static EnergyBossBar loadEnergyBossBar(HoncheonMvt plugin, SkillEngine engine) {
        Map<String, Object> bar;
        try {
            bar = RulesConfig.section(RulesConfig.section(
                    RulesConfig.load(plugin.getDataFolder().toPath()
                            .resolve("config").resolve("skill_motion.yml")), "hud"), "energy_bossbar");
        } catch (RuntimeException missing) {
            org.bukkit.Bukkit.getLogger().warning(
                    "[혼천] skill_motion.yml hud.energy_bossbar 절이 없다 — 내력 보스바를 열지 않는다");
            bar = java.util.Collections.emptyMap();
        }
        return new EnergyBossBar(engine,
                strOrNull(bar.get("title")), strOrNull(bar.get("color")),
                strOrNull(bar.get("depleted_color")), strOrNull(bar.get("overlay")),
                strOrNull(bar.get("depleted_suffix")));
    }

    private static String strOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  안전 지역 (B-006) — 【관아 앞마당에서는 사람을 벨 수 없다】
    //
    //  training.yml location_safety 는 오래 적혀 있었는데 읽는 자바가 0줄이었다 — 등록부는
    //  "관아는 안전"이라 말하는데 세계에선 관아 앞마당에서 사람이 베였다. 설계는 명시적이다:
    //  "PvP는 상시 가능(공유 세계) — 단 안전 지역(관아·문파 내부)은 예외 (location_safety)"
    //  (docs/design/party_and_cooperation.md §6).
    //
    //  장소 판정은 **존/앵커 체계 그대로**다 (조성이 만들고 zones.yml 이 기억하고 zoneAt 이 답한다 —
    //  새 좌표 체계를 발명하지 않았다). 어느 구역이 어느 분류인지는 등록부(location_safety 의
    //  zone_keywords · archetypes)가 말한다 — 이 코드에는 지명이 없다 (등록제).
    //
    //  ★ 비무(합의)는 **별개 층**이다 — 게이트는 서로 선언한 두 사람의 칼을 막지 않는다.
    //    문파 내부(안전)의 비무 서열전·장문 비무 도전이 설계에 있다 (sect_life.md) — 안전 지역이
    //    합의된 겨룸까지 막으면 그 설계가 죽는다. 합의의 규칙(중상 상한·제3자의 칼)은 Sparring 의 것.
    // ══════════════════════════════════════════════════════════════════════════

    /** 분류 하나 — {@code level} 은 training.yml 의 어휘 그대로 (안전 · 보통 · 위험) */
    private record SafetyRule(String category, String level,
                              List<String> zoneKeywords, Set<String> archetypes) {
    }

    /** 사람에게 칼이 서지 않는 등급 — training.yml location_safety 의 level 어휘 (정본) */
    private static final String SAFE_LEVEL = "안전";

    /** 장소 이름 → 원형 — world_map.yml §16. 지도는 onEnable 뒤에 서므로 첫 물음에서 굳힌다 */
    private Map<String, String> archetypeByPlaceName;

    /** 등록부 판독 — 섹션이 없으면 조용히 꺼진다 (등록부가 앞서고 코드가 따른다. safety_audit 이 짖는다) */
    private static List<SafetyRule> loadSafetyRules(HoncheonMvt plugin) {
        try {
            Map<String, Object> table = RulesConfig.section(RulesConfig.load(
                            plugin.getDataFolder().toPath().resolve("config").resolve("training.yml")),
                    "location_safety");
            List<SafetyRule> out = new ArrayList<>();
            for (Map.Entry<String, Object> e : table.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> m)) {
                    continue;
                }
                out.add(new SafetyRule(e.getKey(), String.valueOf(m.get("level")),
                        strings(m.get("zone_keywords")), Set.copyOf(strings(m.get("archetypes")))));
            }
            return List.copyOf(out);
        } catch (RuntimeException missing) {
            return List.of();
        }
    }

    private static List<String> strings(Object v) {
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    /**
     * 이 자리의 안전도 — training.yml {@code location_safety} 의 level (안전·보통·위험).
     * 구역 밖(들판·산야)이면 {@code null} — 강호의 자유 지대다.
     *
     * <p>장소 판정은 존 체계 그대로다: {@link HoncheonMvt#zoneAt} (중첩 시 부피가 작은 쪽 —
     * 건물 > 마을). 분류는 등록부에 <b>적힌 순서대로</b> 먼저 맞는 것이 이긴다 (안전이 맨 위 —
     * "청하현 관아"는 「관아」로 안전이지, 「청하현」으로 보통이 아니다).
     */
    String safetyLevel(Location at) {
        if (safetyRules.isEmpty()) {
            return null;
        }
        Zone zone = plugin.zoneAt(at);
        if (zone == null) {
            return null;
        }
        String archetype = placeArchetype(zone.name());
        for (SafetyRule rule : safetyRules) {
            for (String kw : rule.zoneKeywords()) {
                if (!kw.isBlank() && zone.name().contains(kw)) {
                    return rule.level();
                }
            }
            if (archetype != null && rule.archetypes().contains(archetype)) {
                return rule.level();
            }
        }
        return null;
    }

    /** 구역 이름 = 원거리 등록지의 장소 이름 (RemoteBuilder 가 place.name() 으로 존을 판다) — 그 원형 */
    private String placeArchetype(String zoneName) {
        if (archetypeByPlaceName == null) {
            WorldMap map = plugin.worldMap();
            if (map == null) {
                return null;
            }
            Map<String, String> out = new HashMap<>();
            for (WorldMap.Place p : map.all()) {
                if (p.archetype() != null && !p.archetypePending()) {
                    out.put(p.name(), p.archetype());
                }
            }
            archetypeByPlaceName = out;
        }
        return archetypeByPlaceName.get(zoneName);
    }

    /**
     * <b>안전 지역 게이트 (B-006)</b> — 안전(安全) 지역에서는 사람에게 칼이 서지 않는다.
     * 세 판정길이 전부 이 문을 지난다: {@code onMelee}(길목의 맨 앞 — 바닐라·화살 포함) ·
     * {@link #basicJudged}(벼른 뒤 베는 순간) · {@link #admit}(초식의 히트박스가 가려낼 때).
     *
     * <p>어느 <b>한쪽이라도</b> 안전 지역에 서 있으면 막는다 — 담 밖에서 담 안을 쏘는 것도,
     * 담 안에서 담 밖을 쏘는 것도 (안전 지역이 저격 진지가 되면 그것은 안전이 아니라 무기다).
     *
     * <p>비무 선언 중의 두 사람은 예외 (합의는 {@link Sparring} 의 별개 층 — 위 절의 사연).
     */
    private boolean safetyBlocks(Player attacker, LivingEntity target) {
        if (safetyRules.isEmpty() || !(target instanceof Player victim) || attacker.equals(victim)) {
            return false;
        }
        Sparring bouts = plugin.hunting() == null ? null : plugin.hunting().sparring();
        if (bouts != null && bouts.isSparring(attacker) && bouts.isSparring(victim)) {
            return false;   // 서로 선언했다 — 비무는 안전 지역의 예외다 (죽지는 않는다. Sparring 이 막는다)
        }
        return SAFE_LEVEL.equals(safetyLevel(victim.getLocation()))
                || SAFE_LEVEL.equals(safetyLevel(attacker.getLocation()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  타격 허용 (B-119) — 【누가 맞을 수 있는가】 combat.yml strike_admission
    //
    //  사용자 실측: "npc와 동물등 안때려짐" + 정정: "마을 npc도 때려져야합니다. 몰래 죽일수도".
    //  → 기본은 전부 허용 — 문이 닫는 것은 등록부의 예외 표식뿐 (기본: 빈 목록).
    //  판정은 순수 함수다 (StrikeAdmission — Bukkit 을 모른다. tools/StrikeAdmissionSelfTest 가 시험).
    //  여기는 몸에서 표식(PDC)을 읽어 그 함수에 건네는 배선일 뿐이다.
    //
    //  ★ 사람 대 사람은 이 문 밖이다 — 안전 지역(B-006)·비무(Sparring)의 기존 계약이 그대로 선다.
    //  ★ 이 문은 "누가 맞나"만 정한다 — 격 지불·태세 마진·피해 산술은 한 줄도 안 바뀌었다.
    // ══════════════════════════════════════════════════════════════════════════

    /** 이 몸의 표식 — 신원(PDC)을 낱말로. 판정 자체는 {@link StrikeAdmission#mark} (순수) */
    private String strikeMark(LivingEntity target) {
        org.bukkit.persistence.PersistentDataContainer pdc = target.getPersistentDataContainer();
        return StrikeAdmission.mark(
                target instanceof Player,
                pdc.has(CheonghaBuilder.KEY_NPC, org.bukkit.persistence.PersistentDataType.STRING),
                HuntingGrounds.tag(target, HuntingGrounds.KEY_KIND),
                HuntingGrounds.tag(target, HuntingGrounds.KEY_RANK),
                HuntingGrounds.tag(target, HuntingGrounds.KEY_ROLE),
                pdc.has(keyPopulace, org.bukkit.persistence.PersistentDataType.STRING),
                pdc.has(keyRegionNpc, org.bukkit.persistence.PersistentDataType.STRING));
    }

    /** 타격 허용의 문 — 비플레이어의 몸에만 선다 (사람 대 사람은 안전 지역·비무 계약이 정본) */
    private boolean admissionBars(LivingEntity target) {
        return !(target instanceof Player) && !engine.admission().allowed(strikeMark(target));
    }

    /** 문의 말 — 왜 칼이 서지 않았는지 (침묵하는 게이트는 버그로 보인다 — safetyDenied 와 같은 원칙) */
    private void admissionDenied(Player attacker, LivingEntity target) {
        String why = engine.admission().refusal(strikeMark(target));
        flash(attacker, ChatColor.GRAY + (why == null ? "칼이 서지 않는다" : why));
    }

    /** 게이트의 말 — 왜 칼이 서지 않았는지 화면이 말한다 (침묵하는 게이트는 버그로 보인다) */
    private void safetyDenied(Player attacker, LivingEntity target) {
        Location at = SAFE_LEVEL.equals(safetyLevel(target.getLocation()))
                ? target.getLocation() : attacker.getLocation();
        Zone zone = plugin.zoneAt(at);
        String where = zone == null ? "안전 지역" : zone.name();
        flash(attacker, ChatColor.GRAY + where
                + " — 여기서는 법이 이긴다 (사람에게 칼이 서지 않는다)");
    }

    /** 중앙 티커 기동 — HoncheonMvt.onEnable 에서 1회 (효과별 개별 태스크 생성 금지, F-P2) */
    public void start() {
        display.start();   // 지난 생의 유령(공중에 얼어붙은 획)을 걷는다
        plugin.getServer().getScheduler().runTaskTimer(plugin, Metrics.wrap("skill_execution", this::tick), 1L, 1L);
    }

    /**
     * 정지 — 세계에 형체를 남기지 않는다.
     * <p><b>배선 필요</b>: {@code HoncheonMvt.onDisable()} 에서 {@code skillListener.shutdown()} 을 부른다.
     * 안 불러도 다음 기동의 {@link SkillDisplay#start()} 가 표식(honcheon:vfx)을 보고 걷어낸다 —
     * 이중 방벽이다 (크래시엔 onDisable 이 돌지 않으므로).
     */
    public void shutdown() {
        display.clearAll();
        energyBossBar.clearAll();   // 리로드 뒤 유령 보스바가 화면에 살아남지 않게
    }

    /**
     * 모션 진단 — <b>인게임에 못 들어가는 눈</b>(RCON·콘솔)이 "3D 획이 정말 떴는가"를 확인하는 창구.
     *
     * <p><b>배선</b>: {@code MvtCommand} 에 {@code /혼천 모션진단 [초]} 를 붙이고 이 줄들을 그대로 뿌린다.
     * 안 뜬 것(등록부가 획을 안 준 계열)과 못 뜬 것(예산 강등)이 <b>다른 사건</b>으로 보인다.
     */
    public List<String> motionDiagnostics(int seconds) {
        return display.diagnostics(seconds);
    }

    /**
     * 획시험 — <b>획 15종 + 대조군 3</b> 을 눈앞에 한 줄로 세운다 ({@code /혼천 획시험}).
     *
     * <p>정적 검산(등록부 ↔ 팩 ↔ 배치본)이 전부 통과했는데 사용자는 보라 큐브를 본다.
     * 검산이 못 보는 곳에서 어긋난 것이므로, <b>눈을 게임 안에 세운다</b>.
     */
    public List<String> strokeTest(org.bukkit.entity.Player player) {
        return display.strokeTest(player);
    }

    /**
     * <b>/혼천 사다리</b> — 여섯 격의 생김새를 <b>나란히</b> 세운다.
     *
     * <p><b>왜 있는가</b>: 사용자가 요구한 것은 "격이 오를수록 화려해진다"이고, 그것은
     * <b>한눈에 사다리를 보아야</b> 판단할 수 있다. 무공을 여섯 번 배워 여섯 번 치는 것으로는
     * 비교가 안 된다 (기억은 색을 못 세운다). 그래서 <b>여섯을 같은 화면에 동시에</b> 세운다.
     *
     * <p><b>말뚝 여섯 개</b>가 앞에 서고, 각 말뚝에서 그 격의 파티클이 <b>반복해서</b> 터진다:
     * 잔상(echo) · 먼지·먹번짐(haze) · 먹점(impact) · 강조(accent) · <b>폭발(burst)</b>.
     * 동시에 시전자의 손에서는 <b>3D 획</b>이 격을 하나씩 갈아 가며 그어진다 (굵기·밝기의 사다리).
     *
     * <p><b>이 눈은 실전과 같은 함수를 부른다</b> — 시험용 파티클을 따로 만들지 않는다.
     * 따로 만들면 시험이 실전을 대변하지 못한다 (획시험이 같은 이유로 {@code item()} 을 그대로 쓴다).
     */
    public List<String> ladder(Player player) {
        List<String> out = new ArrayList<>();
        List<SkillEngine.GradeMotion> grades = engine.gradeLadder();
        SkillEngine.Budget b = engine.motionBudget();
        Vector flat = player.getLocation().getDirection().setY(0);
        if (flat.lengthSquared() < 1e-6) {
            flat = new Vector(0, 0, 1);
        }
        flat.normalize();
        Vector right = new Vector(-flat.getZ(), 0, flat.getX());
        Location origin = player.getLocation().add(flat.clone().multiply(LADDER_FRONT));

        out.add(ChatColor.GOLD + "── 격의 사다리 (" + LADDER_SECONDS + "초) — 왼쪽이 아래, 오른쪽이 위 ──");
        out.add(ChatColor.GRAY + "격이 오르면 " + ChatColor.WHITE + "먹 → 회백 → 청회 → 청록 → 옥 → 청백"
                + ChatColor.GRAY + " 으로 색이 오른다 (config/skill_motion.yml inks)");
        out.add(ChatColor.GRAY + "타격점 = 먹점 + 강조 + 폭발 + 대성공 예약 " + b.critReserve()
                + " ≤ " + ChatColor.WHITE + b.perPointTickMax() + ChatColor.GRAY + " (한 지점·한 틱 상한)");

        double start = -(grades.size() - 1) / 2.0;
        for (int i = 0; i < grades.size(); i++) {
            SkillEngine.GradeMotion g = grades.get(i);
            Location post = origin.clone().add(right.clone().multiply((start + i) * LADDER_SPAN));
            int point = g.impact().count() + g.accent().count() + g.burst().count() + b.critReserve();
            display.post(post.clone().add(0, 2.2, 0),
                    g.rank() + ". " + g.grade() + "  타격점 " + point + "/" + b.perPointTickMax(),
                    LADDER_SECONDS * 20);
            out.add(String.format("%s%d. %-4s %s먹점 %2d · 강조 %d · %s폭발 %2d(%s)%s · 잔상 %d(%s) · 안개 %d%s ⇒ 타격점 %2d",
                    ChatColor.WHITE, g.rank(), g.grade(), ChatColor.GRAY,
                    g.impact().count(), g.accent().count(), ChatColor.AQUA,
                    g.burst().count(), String.valueOf(g.burst().ink()), ChatColor.GRAY,
                    g.echo().count(), String.valueOf(g.echo().ink()), g.haze().count(),
                    ChatColor.GRAY, point));

            // 말뚝에서 그 격이 **반복해서** 터진다 — 여섯을 동시에 보고 비교할 수 있어야 사다리다
            for (int t = 0; t < LADDER_SECONDS * 20; t += LADDER_BEAT) {
                pending.add(new Pending(tick + t, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    Location at = post.clone().add(0, 1.0, 0);
                    hud.emit(at, g.echo(), false);
                    hud.emit(at, g.haze(), false);
                    hud.emit(at, g.impact(), false);
                    hud.emit(at, g.accent(), false);
                    hud.emit(at, g.burst(), false);
                }));
            }
            // 손에서는 3D 획이 격을 갈아 가며 그어진다 (굵기 0.55 → 2.10 · 밝기 0 → 15)
            pending.add(new Pending(tick + (long) i * LADDER_STROKE_GAP, () -> {
                if (!player.isOnline()) {
                    return;
                }
                String weaponClass = engine.weaponClassOf(
                        player.getInventory().getItemInMainHand(), materialName(player));
                SkillEngine.Basic basic = engine.basicStrike(weaponClass);
                if (basic == null) {
                    return;   // 활·무관·짐승 — 그을 획이 없다 (등록부대로다)
                }
                // 사다리의 격 이름도 flash 로 — 맨 sendActionBar 는 다음 statusBar 틱에 덮인다 (B-116)
                flash(player, "획 — " + g.rank() + ". " + g.grade());
                display.slash(player, basic.trail(), g.grade(), weaponClass,
                        basic.range(), basic.angle(), basic.frames().total());
            }));
        }
        out.add(ChatColor.GRAY + "3D 획은 손에서 격을 갈아 가며 그어진다 — "
                + ChatColor.WHITE + "병기를 들고 있어야 보인다" + ChatColor.GRAY
                + " (팩이 없으면 획은 안 뜨고 파티클만 — 설계대로다)");
        return out;
    }

    private static final int LADDER_SECONDS = 12;     // 사다리가 서 있는 시간
    private static final int LADDER_BEAT = 12;        // 말뚝이 다시 터지는 간격 (틱)
    private static final int LADDER_STROKE_GAP = 24;  // 손의 획이 다음 격으로 넘어가는 간격 (틱)
    private static final double LADDER_SPAN = 2.4;    // 말뚝 간격 (m)
    private static final double LADDER_FRONT = 5.0;   // 말뚝이 서는 거리 (m)

    /** 사람이 부르는 이름 → 모션 id (등록부의 이름을 다 쳐야 하면 아무도 안 쓴다) */
    private static final Map<String, String> STROKE_ALIAS = Map.of(
            "호", "참격_호", "선", "참격_선", "원", "참격_원",
            "참격_호", "참격_호", "참격_선", "참격_선", "참격_원", "참격_원");

    /**
     * <b>/혼천 획위치</b> — 획이 서는 자리를 <b>인게임에서 밀고 당긴다</b>.
     *
     * <p><b>왜 명령인가</b>: 이 자리(앞·높이·옆)는 <b>눈으로 봐야 정해진다</b>. 팔이 지나가는 자리는
     * 계산으로 못 맞춘다 — 서버를 세우고, 등록부를 고치고, 다시 세우는 왕복으로는 한 값도 못 맞춘다.
     * 그래서 <b>살아 있는 서버에서 밀고, 민 즉시 획을 한 번 긋는다</b> (한 번 접속으로 맞춘다).
     *
     * <p>민 값은 <b>임시</b>다 (재기동하면 사라진다). {@code /혼천 획위치 적기} 가 등록부에 붙일 줄을
     * 뽑아 주고, <b>사람이</b> {@code config/skill_motion.yml} 에 적는다 —
     * <b>코드가 등록부를 고치면 등록제가 무너진다</b>.
     *
     * <p>몸 안을 청구한 값은 <b>붉게 잡힌다</b> ({@link SkillDisplay#originFault}) — 그 눈은 정적 감사
     * ({@code motion_audit.py} ⑧) 와 <b>같은 규칙</b>을 본다.
     */
    public List<String> strokeOrigin(Player player, String[] args) {
        String weaponClass = engine.weaponClassOf(
                player.getInventory().getItemInMainHand(), materialName(player));
        SkillEngine.Swing sw = engine.swing(weaponClass);
        SkillEngine.Basic basic = engine.basicStrike(weaponClass);
        double range = basic == null ? 3.0 : basic.range();
        double length = sw == null ? 0.0 : range * sw.reach();

        // args[0] = "획위치"
        String verb = args.length >= 2 ? args[1] : "";
        if (verb.isEmpty()) {
            return display.originReport(length, weaponClass);
        }
        if ("적기".equals(verb)) {
            return display.originYaml();
        }
        if ("되돌려".equals(verb)) {
            display.resetOrigins();
            List<String> out = new ArrayList<>();
            out.add(ChatColor.GREEN + "등록부의 값으로 되돌렸다 (인게임에서 민 것은 전부 버렸다)");
            out.addAll(display.originReport(length, weaponClass));
            return out;
        }
        if ("그려".equals(verb)) {
            String hit = args.length >= 3 ? hitTypeOf(args[2]) : "호";
            return List.of(probe(player, hit, grade(player), weaponClass, range, basic));
        }

        // /혼천 획위치 <호|선|원> <앞|높이|옆> <값>
        if (args.length < 4) {
            return List.of(ChatColor.RED + "쓰는 법: /혼천 획위치 <호|선|원> <앞|높이|옆> <값>"
                    + "  ·  /혼천 획위치 그려 [호|선|원]  ·  /혼천 획위치 적기  ·  /혼천 획위치 되돌려");
        }
        String motion = STROKE_ALIAS.get(args[1]);
        if (motion == null) {
            return List.of(ChatColor.RED + "모르는 획: " + args[1] + " (호 · 선 · 원)");
        }
        double value;
        try {
            value = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            return List.of(ChatColor.RED + "숫자가 아니다: " + args[3]);
        }
        if (!display.setOrigin(motion, args[2], value)) {
            return List.of(ChatColor.RED + "모르는 칸: " + args[2] + " (앞 · 높이 · 옆)");
        }

        List<String> out = new ArrayList<>();
        SkillEngine.StrokeOrigin o = display.originOf(motion);
        out.add(String.format(ChatColor.GREEN + "%s · %s = %.2f  " + ChatColor.GRAY
                        + "(앞 %.2f · 높이 %.2f · 옆 %.2f)",
                motion, args[2], value, o.forward(), o.height(), o.lateral()));
        double eff = display.forwardOf(o, length);
        if (!o.centered() && Math.abs(eff - o.forward()) > 1.0e-6) {
            out.add(String.format(ChatColor.YELLOW + "  못에 걸렸다 — 실효 앞 %.2fm"
                    + " (몸 밖 최소 %.2f · 획 길이 %.2f 의 상한 %.2f)",
                    eff, engine.strokeLimits().minForward(), length,
                    length * engine.strokeLimits().forwardMaxRatio()));
        }
        String fault = display.originFault(o);
        if (fault != null) {
            out.add(ChatColor.RED + "  ✖ 위반: " + fault);
        }
        // ★ 민 즉시 그어 본다 — 안 그으면 이 명령은 숫자놀음이다
        out.add(probe(player, hitTypeOf(args[1]), grade(player), weaponClass, range, basic));
        return out;
    }

    /**
     * <b>/혼천 스윙</b> — 스윙의 <b>크기·각도·활·전진</b>을 인게임에서 밀고 당긴다.
     *
     * <p>사용자의 못: <i>"이런 값은 눈으로 봐야 정해진다."</i> {@code /혼천 획위치} 와 <b>같은 문법</b>이다 —
     * 밀면 <b>즉시 한 획을 긋는다</b> (한 번 접속으로 맞춘다). 민 값은 임시고, {@code 적기} 가 등록부에
     * 붙일 줄을 뽑는다 — <b>코드가 등록부를 고치면 등록제가 무너진다</b>.
     *
     * <pre>
     *   /혼천 스윙                    지금 값 + 눈(호각·전진·참격비) + 계열마다 참격인가 찌르기인가
     *   /혼천 스윙 호 1.4             호 각도를 1.4배 → **즉시 획 한 번**
     *   /혼천 스윙 전진 0             전진을 죽인다 (그래도 참격인가? — 눈의 대조군)
     *   /혼천 스윙 그려 내려베기      그 획을 한 번 (넷을 눈으로 비교한다)
     *   /혼천 스윙 되돌려 · 적기
     * </pre>
     */
    public List<String> swing(Player player, String[] args) {
        String weaponClass = engine.weaponClassOf(
                player.getInventory().getItemInMainHand(), materialName(player));
        SkillEngine.Basic basic = engine.basicStrike(weaponClass);
        double range = basic == null ? 3.0 : basic.range();
        double angle = basic == null ? 90.0 : basic.angle();
        String hit = basic == null ? "호" : basic.trail();

        String verb = args.length >= 2 ? args[1] : "";
        if (verb.isEmpty()) {
            return display.swingReport(weaponClass);
        }
        if ("적기".equals(verb)) {
            return display.swingYaml();
        }
        if ("되돌려".equals(verb)) {
            display.resetSwings();
            List<String> out = new ArrayList<>();
            out.add(ChatColor.GREEN + "등록부의 값으로 되돌렸다 (인게임에서 민 것은 전부 버렸다)");
            out.addAll(display.swingReport(weaponClass));
            return out;
        }
        if ("그려".equals(verb)) {
            String id = args.length >= 3 ? args[2]
                    : nextStroke(player, weaponClass);   // 안 적으면 다음 순번의 획
            if (engine.swingArcs().stroke(id) == null) {
                return List.of(ChatColor.RED + "모르는 획: " + id + " ("
                        + String.join(" · ", engine.swingArcs().strokes().keySet()) + ")");
            }
            // ★ 실전과 **같은 함수**를 부른다 — 시험용으로 따로 그리면 시험이 실전을 대변하지 못한다
            boolean drawn = display.slash(player, hit, grade(player), weaponClass,
                    range, angle, 12, id);
            if (basic != null) {
                basicTrail(player, basic, grade(player), weaponClass, id, 12, drawn);
            }
            SkillEngine.SwingArc a = engine.swingArcs().stroke(id);
            return List.of(String.format(ChatColor.GRAY + "  → %s (%s) — 호각 %.0f도%s",
                    id, weaponClass, a.arcDeg(display.tuning().arc()),
                    drawn ? " · 3D 획도 떴다" : " · 3D 획은 안 떴다 (팩 없음 — 파티클이 지킨다)"));
        }

        // /혼천 스윙 <호|높이|활|전진> <값>
        if (args.length < 3) {
            return List.of(ChatColor.RED + "쓰는 법: /혼천 스윙 <호|높이|활|전진> <값>"
                    + "  ·  /혼천 스윙 그려 [획]  ·  /혼천 스윙 적기  ·  /혼천 스윙 되돌려");
        }
        double value;
        try {
            value = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            return List.of(ChatColor.RED + "숫자가 아니다: " + args[2]);
        }
        if (!display.setSwing(args[1], value)) {
            return List.of(ChatColor.RED + "모르는 칸: " + args[1] + " (호 · 높이 · 활 · 전진)");
        }
        List<String> out = new ArrayList<>(display.swingReport(weaponClass));
        // ★ 민 즉시 그어 본다 — 안 그으면 이 명령은 숫자놀음이다
        String id = nextStroke(player, weaponClass);
        boolean drawn = display.slash(player, hit, grade(player), weaponClass, range, angle, 12, id);
        if (basic != null) {
            basicTrail(player, basic, grade(player), weaponClass, id, 12, drawn);
        }
        out.add(ChatColor.GRAY + "  → 획을 그었다 (" + id + " · " + weaponClass + ")");
        return out;
    }

    // ══════════ ★ /혼천 검기 — 검기 값을 인게임에서 즉석에 돌려 본다 (재기동 없이) ══════════

    /**
     * <b>/혼천 검기</b> — 초승달 검기의 <b>각도·크기</b>를 눈으로 보며 찾는다.
     *
     * <p>사용자의 못: <i>"이런 값은 인게임에서 눈으로 봐야 정해진다."</i> 그런데 config 를 고쳐 재기동하면
     * 왕복이 1분이다 — 값 하나에 1분이면 값을 못 찾는다. 그래서 여기서 민 값은
     * {@link SkillEngine#setKigiSlash} 로 <b>메모리에만</b> 산다 (다음 스윙부터 반영).
     *
     * <p><b>config 파일은 안 쓴다.</b> {@code skill_motion.yml} 은 주석이 정본의 절반이라 프로그램이
     * 쓰면 그 절반이 죽는다. 확정값은 {@code 보기} 가 뱉는 <b>붙여넣기용 줄</b>로 사람이 못 박는다 —
     * 재기동하면 오버라이드는 사라지고 등록부 값이 돈다.
     *
     * <pre>
     *   /혼천 검기 [보기]        지금 값 전부 + config 에 붙일 줄
     *   /혼천 검기 tilt 25       그 값을 즉시 (다음 스윙부터)
     *   /혼천 검기 시험          휘두르지 않고 지금 값으로 한 번 소환 (눈의 대조)
     *   /혼천 검기 초기화        등록부(config) 값으로 되돌린다
     * </pre>
     *
     * @return 화면에 찍을 줄들 (MvtCommand 가 그대로 보낸다)
     */
    public List<String> kigi(Player player, String[] args) {
        SkillEngine.KigiSlash cfg = engine.kigiSlash();
        if (cfg == null) {
            return List.of(ChatColor.RED
                    + "검기 등록부가 없다 — config/skill_motion.yml 의 kigi_slash 절이 비었다.");
        }
        String verb = args.length >= 2 ? args[1] : "보기";

        if ("보기".equals(verb)) {
            return kigiReport(cfg);
        }
        if ("초기화".equals(verb) || "되돌려".equals(verb)) {
            engine.resetKigiSlash();
            List<String> out = new ArrayList<>();
            out.add(ChatColor.GREEN + "등록부(config)의 값으로 되돌렸다 — 인게임에서 민 것은 전부 버렸다");
            out.addAll(kigiReport(engine.kigiSlash()));
            return out;
        }
        if ("횡참시험".equals(verb) || "임팩트시험".equals(verb)) {
            // 부 횡참 검증 훅 — 실전과 같은 함수 (spawnHeavySlash). 클라 입력 없이 판정·연출을 잰다
            SkillEngine.HeavySlash imp = engine.heavySlash();
            if (imp == null || !imp.enabled()) {
                return List.of(ChatColor.RED + "heavy_slash 가 없다/꺼져 있다 (config/skill_motion.yml)");
            }
            spawnHeavySlash(player, imp, 1);
            return List.of(ChatColor.GOLD + "횡참을 한 번 소환했다 (반경 " + imp.radius()
                    + "m · 호 " + Math.round(imp.sweepDeg()) + "도 · 폭 " + imp.bandWidth() + "m)");
        }
        if ("시험".equals(verb)) {
            // ★ 실전과 **같은 함수**를 부른다 (SkillDisplay.kigiSlash + 흰 별) — 시험용으로 따로 그리면
            //   시험이 실전을 대변하지 못한다. 휘두르지 않아도 눈앞에 같은 검기가 선다.
            boolean drawn = spawnKigiSlash(player);
            return List.of(ChatColor.GOLD + "검기를 한 번 소환했다" + ChatColor.GRAY
                    + (drawn ? " (3D 검기가 떴다)" : " (3D 는 안 떴다 — 팩 없음/enabled: false. 흰 별만 뿌렸다)"));
        }

        // /혼천 검기 <키> <값>
        if (args.length < 3) {
            return List.of(ChatColor.RED + "쓰는 법: /혼천 검기 <키> <값>",
                    ChatColor.GRAY + "  키: " + KIGI_KEYS,
                    ChatColor.GRAY + "  또는: /혼천 검기 보기 · /혼천 검기 시험 · /혼천 검기 초기화");
        }
        double value;
        try {
            value = Double.parseDouble(args[2]);
        } catch (NumberFormatException notANumber) {
            return List.of(ChatColor.RED + "숫자가 아니다: " + args[2],
                    ChatColor.GRAY + "  예: /혼천 검기 " + args[1] + " 25");
        }
        SkillEngine.KigiSlash next = withKigi(cfg, args[1], value);
        if (next == null) {
            return List.of(ChatColor.RED + "모르는 칸: " + args[1],
                    ChatColor.GRAY + "  키: " + KIGI_KEYS);
        }
        engine.setKigiSlash(next);
        List<String> out = new ArrayList<>();
        out.add(String.format(ChatColor.GOLD + "%s = %s" + ChatColor.GRAY
                + " (메모리에만 — 다음 스윙부터 보인다)", args[1], trim(value)));
        out.addAll(kigiReport(next));
        return out;
    }

    /** {@code /혼천 검기} 가 받는 칸들 — 오류 안내가 여기 하나만 본다 (문구가 갈라지지 않게) */
    private static final String KIGI_KEYS =
            "roll·pitch · tilt · sweep · radius · scale · height · forward · draw · fade · frame · sparks";

    /**
     * 값 하나만 갈아끼운 <b>사본</b> — {@code KigiSlash} 는 record(불변)라 새 인스턴스를 만든다.
     * 모르는 키면 null (부르는 쪽이 안내한다).
     */
    private static SkillEngine.KigiSlash withKigi(SkillEngine.KigiSlash c, String key, double v) {
        double scale = c.scale();
        double height = c.centerHeight();
        double forward = c.forward();
        double radius = c.orbitRadius();
        double sweep = c.sweepDeg();
        double tilt = c.tiltDeg();
        double roll = c.rollDeg();
        double pitch = c.bladePitchDeg();
        int draw = c.drawTicks();
        int fade = c.fadeTicks();
        int frame = c.frameTicks();
        SkillEngine.KigiSpark spark = c.spark();
        switch (key) {
            case "roll" -> roll = v;
            case "tilt" -> tilt = v;
            case "sweep" -> sweep = v;
            case "radius" -> radius = v;
            case "scale" -> scale = v;
            case "height" -> height = v;
            case "forward" -> forward = v;
            case "pitch" -> pitch = v;   // 날의 눕힘 — 90 이면 볼록한 바깥이 정면
            // 틱은 등록부 로더와 **같은 못**을 쓴다 (draw/frame ≥ 1 · fade ≥ 0) — 0틱은 그림이 없다
            case "draw" -> draw = Math.max(1, (int) Math.round(v));
            case "fade" -> fade = Math.max(0, (int) Math.round(v));
            case "frame" -> frame = Math.max(1, (int) Math.round(v));
            case "sparks" -> spark = spark == null ? null : new SkillEngine.KigiSpark(
                    spark.particle(), Math.max(0, (int) Math.round(v)),
                    spark.spread(), spark.speed(), spark.alongArc());
            default -> {
                return null;
            }
        }
        return new SkillEngine.KigiSlash(c.enabled(), c.medium(), c.model(), c.applyToTrails(),
                c.applyToClasses(),
                c.frameModels(), c.frameModelsB(), frame,
                c.bandWidth(), c.bandRows(), c.bandJitter(), c.bandSweepTicks(), c.accentCount(),
                c.widthSelfMul(), c.widthOthersMul(), c.heightOthersMul(),
                c.bandHit(), c.bandHitReach(),
                c.replaceStroke(), scale, height, forward,
                radius, sweep, tilt, roll, pitch, c.yawDeg(), draw, fade, c.billboard(), c.alternate(),
                c.brightness(), spark, c.calmHeldAura(),
                c.geomParticle(), c.geomInk(), c.geomInkAlt(),
                c.geomTemplate(), c.geomTemplateFps(), c.geomSweepDeg(), c.geomStepDeg(), c.plate(),
                c.model3d(), c.model3dAnim(),
                c.model3dUp(), c.model3dYaw(), c.model3dPitch());
    }

    /** 지금 값 전부 + <b>config 에 그대로 붙일 줄</b> (확정은 사람이 config 에 못 박는다) */
    private List<String> kigiReport(SkillEngine.KigiSlash c) {
        List<String> out = new ArrayList<>();
        out.add(ChatColor.GOLD + "── 검기(kigi_slash) 지금 값 ──" + ChatColor.GRAY
                + (engine.kigiSlashOverridden() ? "  ★ 인게임 오버라이드 중" : "  (등록부 원본)"));
        out.add(String.format(ChatColor.WHITE + "각: roll %s · tilt %s · sweep %s"
                + ChatColor.GRAY + "   (롤 · 공전면 기울기 · 공전 호각)",
                trim(c.rollDeg()), trim(c.tiltDeg()), trim(c.sweepDeg())));
        out.add(String.format(ChatColor.WHITE + "몸: radius %s · scale %s · height %s · forward %s"
                + ChatColor.GRAY + "   (공전 반경 · 초승달 크기 · 중심 높이 · 앞으로)",
                trim(c.orbitRadius()), trim(c.scale()), trim(c.centerHeight()), trim(c.forward())));
        out.add(String.format(ChatColor.WHITE + "때: draw %d · fade %d · frame %d · sparks %d"
                + ChatColor.GRAY + "   (그리는 틱 · 지우는 틱 · 단계당 틱 · 흰 별 수)",
                c.drawTicks(), c.fadeTicks(), c.frameTicks(),
                c.spark() == null ? 0 : c.spark().count()));
        out.add(ChatColor.DARK_GRAY + "enabled " + c.enabled() + " · billboard " + c.billboard()
                + " · alternate " + c.alternate() + " · brightness " + c.brightness());
        out.add(ChatColor.GOLD + "── config/skill_motion.yml · kigi_slash 에 붙일 줄 ──");
        out.add(String.format("§f  scale: %s   center_height: %s   forward: %s   orbit_radius: %s",
                trim(c.scale()), trim(c.centerHeight()), trim(c.forward()), trim(c.orbitRadius())));
        out.add(String.format("§f  sweep_deg: %s   tilt_deg: %s   roll_deg: %s"
                + "   draw_ticks: %d   fade_ticks: %d   frame_ticks: %d",
                trim(c.sweepDeg()), trim(c.tiltDeg()), trim(c.rollDeg()),
                c.drawTicks(), c.fadeTicks(), c.frameTicks()));
        // ★ 한 줄로도 뽑는다 — 조율자가 복사해서 확정값을 config 에 못 박는다 (YAML flow 로도 유효)
        out.add(String.format("§f{scale: %s, center_height: %s, forward: %s, orbit_radius: %s, "
                + "sweep_deg: %s, tilt_deg: %s, roll_deg: %s, draw_ticks: %d, fade_ticks: %d, "
                + "frame_ticks: %d, spark: {count: %d}}",
                trim(c.scale()), trim(c.centerHeight()), trim(c.forward()), trim(c.orbitRadius()),
                trim(c.sweepDeg()), trim(c.tiltDeg()), trim(c.rollDeg()),
                c.drawTicks(), c.fadeTicks(), c.frameTicks(),
                c.spark() == null ? 0 : c.spark().count()));
        out.add(ChatColor.GRAY + "※ 여기서 민 값은 §f메모리에만§7 산다 — 재기동하면 config 값으로 돌아간다. "
                + "확정했으면 위 줄을 config 에 적어라 (프로그램은 config 를 안 쓴다 — 주석을 지키기 위해서다)");
        return out;
    }

    /** 2.50 → 2.5, 25.00 → 25 (붙여넣을 줄에 0 이 늘어지면 읽기 싫어진다) */
    private static String trim(double v) {
        String s = String.format("%.3f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static String hitTypeOf(String alias) {
        String motion = STROKE_ALIAS.getOrDefault(alias, "참격_호");
        return motion.substring(motion.indexOf('_') + 1);
    }

    private String grade(Player player) {
        String g = offense(state(player));
        return g == null ? SkillEngine.BARE : g;
    }

    /**
     * <b>지금 값으로 한 획</b> — 실전과 <b>같은 함수</b>({@link SkillDisplay#slash})를 부른다.
     *
     * <p>시험용으로 따로 그리면 시험이 실전을 대변하지 못한다 (그 그림은 실전에 대해 거짓말할 수 있다).
     * 그래서 판정이 부르는 그 손을 그대로 부른다 — <b>안 뜨면 왜 안 떴는지</b>를 말한다.
     */
    private String probe(Player player, String hitType, String grade, String weaponClass,
                         double range, SkillEngine.Basic basic) {
        double angle = basic == null ? 90.0 : basic.angle();
        boolean drawn = display.slash(player, hitType, grade, weaponClass, range, angle, 10);
        if (drawn) {
            return ChatColor.GRAY + "  → 획을 그었다 (" + hitType + " · " + weaponClass
                    + " · " + grade + ")";
        }
        if (engine.swing(weaponClass) == null) {
            return ChatColor.RED + "  → 안 떴다: '" + weaponClass
                    + "' 계열은 그을 획이 없다 (등록부 swings: null) — 병기를 들어라";
        }
        if (!display.packed(player.getUniqueId())) {
            return ChatColor.RED + "  → 안 떴다: 이 몸은 팩을 못 받은 눈이다."
                    + " 참격선 모델은 폴백이 null 이라 팩 없이는 안 뜬다 (파티클이 그 자리를 지킨다)";
        }
        return ChatColor.RED + "  → 안 떴다: 예산 강등이거나 등록부가 이 궤적에 획을 안 줬다"
                + " (/혼천 모션진단 이 이유를 말한다)";
    }

    /**
     * 팩을 받았는가 — 3D 층의 유일한 분기점 ({@code require-resource-pack=false} 이므로 <b>물어볼 수밖에 없다</b>).
     *
     * <p>수락한 눈에는 팩이 구운 3D 획(item_model)을, 거절한 눈에는 <b>바닐라 아이템</b>을 실은 형체를 보인다
     * (철검이 날아가고 · 흰 막대가 몸을 돈다). 어느 쪽이든 무엇이 일어났는지 읽힌다.
     */
    @EventHandler
    public void onPackStatus(org.bukkit.event.player.PlayerResourcePackStatusEvent event) {
        display.packStatus(event.getPlayer().getUniqueId(),
                event.getStatus() == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
    }

    /** 무공 상태를 갈아 끼운다 — 연무장의 경지·내력은 세계의 것이 아니다 */
    public SkillEngine.State swapState(java.util.UUID playerId, SkillEngine.State replacement) {
        SkillEngine.State previous = states.get(playerId);
        if (replacement == null) {
            states.remove(playerId);
        } else {
            states.put(playerId, replacement);
        }
        return previous;
    }

    /**
     * 이 몸의 무공 상태 — <b>경지는 원장에서 온다</b> (원장의 경지는 봇의 시트에서 왔다).
     *
     * <p>여기가 {@code "이류"} 하드코딩이 살아 있던 자리다. 이제 태어나는 상태는 몸에 실린 원장을 보고,
     * 원장이 비었으면(접합 전) 등록부의 첫 단으로 선다 — <b>코드가 경지를 지어내지 않는다.</b>
     */
    /**
     * 순간 사건의 한 줄 (B-116) — 읽을 시간({@code skill_motion.yml hud.flash_read_ticks})만큼
     * 액션바 줄을 갖고, 지나면 statusBar(지속 상태 합성)가 돌아온다. 맨 {@code SkillHud.actionBar} 는
     * 다음 statusBar 틱(≤0.2초)에 덮인다 — 사람이 읽어야 하는 문구는 <b>반드시 이 길로 온다</b>
     * (경공 등 바깥 손의 창구이기도 하다).
     */
    void flash(Player player, String text) {
        hud.flash(player, text, tick + engine.hudFlashTicks());
    }

    /**
     * 바깥 지속 표시의 한 조각 (B-116 전역 소유권) — 비무 카운트다운·서장 집필 대기·시신 은닉
     * 진행처럼 <b>반복 송신되는 상태</b>는 flash(줄 독점)가 아니라 이 길로 온다: statusBar 합성의
     * 채널 조각이 되어 생명·격 두름·경공과 <b>나란히</b> 읽힌다 (겹치지 않는다).
     *
     * @param readTicks 조각의 수명 — 보내는 손의 재송신 주기 + statusBar 주기(4틱)보다 길게.
     *                  재송신이 끊기면 이 수명 뒤에 조각이 스스로 빠진다.
     */
    void notice(Player player, String channel, String text, int readTicks) {
        // 무공 상태가 없는 몸은 statusBar 틱이 안 돈다 — 그 몸에게는 여기서 직접 그린다
        boolean stateless = states.get(player.getUniqueId()) == null;
        hud.notice(player, channel, text, tick, tick + readTicks, stateless);
    }

    /** 채널을 비운다 — 끝난 판의 카운트다운을 TTL 만료까지 끌고 다니지 않는다 */
    void dropNotice(java.util.UUID playerId, String channel) {
        hud.dropNotice(playerId, channel);
    }

    public SkillEngine.State state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), id -> {
            SkillEngine.State fresh = new SkillEngine.State();
            PlayerLedger ledger = plugin.ledger(id);
            fresh.realm = ledger.realm(engine.baseRealm());
            fresh.naegong = ledger.naegong();
            fresh.energy = engine.pool(fresh.naegong);
            return fresh;
        });
    }

    // ══════════ 시트 접합 — 봇의 장부가 마크의 몸에 실린다 ══════════

    /**
     * <b>봇의 시트를 이 몸에 싣는다.</b> 스냅숏이 올 때마다(20초) · 접속할 때마다 부른다.
     *
     * <p><b>정본은 봇이다.</b> 내려오는 값은 <b>절대값</b>(덮어쓰기)이지 증분이 아니다 — 그래서
     * 마크가 수련으로 먼저 더해 둔 값이 있어도 <b>영구히 갈라질 수 없다</b>: 다음 스냅숏이 진실로 되돌린다.
     * 마크가 쌓은 것은 {@code cultivation_logged} 로 올라가 봇의 시트를 바꾸고, 바뀐 시트가 여기로 온다.
     *
     * <p>접합되지 않은 몸에는 시트가 없다. 그 몸은 <b>강호에 없는 사람</b>이다 — 등록부의 첫 단(범인)으로
     * 서고, 화후는 쌓이지 않는다 (쌓을 장부가 없다). {@link #nag} 가 그 사실을 본인에게 말한다.
     *
     * @return 시트가 실렸는가 (false = 접합 전이거나 봇이 꺼져 있다)
     */
    public boolean syncSheet(Player player) {
        PlayerLedger ledger = plugin.ledger(player.getUniqueId());
        WorldBridge.Sheet sheet = WorldBridge.state().sheet(player.getUniqueId());
        if (sheet == null) {
            ledger.setLinked(false);
            return false;
        }
        ledger.applySheet(sheet);
        SkillEngine.State state = state(player);
        String before = state.realm;
        state.realm = ledger.realm(engine.baseRealm());
        state.naegong = ledger.naegong();
        state.energy = Math.min(state.energy < 0 ? 0 : state.energy, engine.pool(state.naegong));
        if (!java.util.Objects.equals(before, state.realm)) {
            // 승급은 강호가 인정하는 것이다 — 마크는 그것을 전해 듣는다
            state.armed = null;
            state.energy = engine.pool(state.naegong);
            player.sendTitle(ChatColor.GOLD + Glyphs.realmCrest(state.realm) + " " + state.realm,
                    ChatColor.GRAY + "강호가 너를 그렇게 부른다", 10, 60, 20);
        }
        return true;
    }

    /** 접속한 모든 몸에 시트를 다시 싣는다 — 스냅숏이 바뀔 때마다 (메인 스레드에서 부를 것) */
    public void syncAllSheets() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            syncSheet(player);
        }
    }

    // ══════════ 중앙 티커 ══════════

    private void tick() {
        tick++;
        hud.newTick();
        display.tick(tick);   // 3D 층 — 투사 전진 · 수축 · 회수 (중앙 티커 하나를 같이 쓴다, F-P2)

        if (!pending.isEmpty()) {
            List<Pending> due = new ArrayList<>();
            pending.removeIf(p -> {
                if (p.due() <= tick) {
                    due.add(p);
                    return true;
                }
                return false;
            });
            for (Pending p : due) {
                try {
                    p.action().run();
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("무공 예약 처리 실패: " + e.getMessage());
                }
            }
        }

        freezeTick();   // 히트스톱 — 얼어붙은 몸을 붙들고, 풀리는 순간 놓아 준다

        if (tick % engine.npcThinkTicks() == 0) {
            npcSweep();   // NPC 격 — 매 틱 사고 금지 (npc_combat think_interval_ticks)
        }
        // 무기 오라 — 병기 둘레를 도는 기운 (순수 VFX · 판정 불변). 격 두름(state.armed)과 별개 층이라
        // 여기서 (state 유무와 무관하게) 자체 주기로 돈다. 발행은 SkillHud 예산 게이트를 그대로 탄다.
        //   ① ★영상 정합 — 월드에 떨어진/세워진 병기 아이템 둘레 (검을 땅에 두면 기운이 돈다)
        //   ② 부차 — 든 무기 곁 (우하단 손 자리)
        SkillEngine.WeaponAura wa = engine.weaponAura();
        if (wa != null && wa.enabled()) {
            // dropped/전시(정지)는 성긴 주기로 (interval). held(움직임)는 촘촘히 (held_interval) —
            // 파티클이 검에서 벗어났다 붙는 끊김을 없애려 매 틱 손 자리에 뿌린다 (발행당 수는 아래서 줄인다).
            if (wa.dropped() && wa.intervalTicks() > 0 && tick % wa.intervalTicks() == 0) {
                weaponAuraDropped(wa);
            }
            if (wa.held() && wa.heldIntervalTicks() > 0 && tick % wa.heldIntervalTicks() == 0) {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    weaponAuraHeld(player, wa);
                }
            }
        }
        if (tick % 4 != 0) {
            return;   // HUD·유지비는 4틱(0.2초)마다 — 액션바 갱신 비용 절감
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            SkillEngine.State state = states.get(player.getUniqueId());
            if (state == null) {
                continue;
            }
            if (state.combatUntil > 0 && tick > state.combatUntil) {
                endCombat(player, state);   // 전투가 끝났다 — 흐름은 흩어지고 오의 횟수는 돌아온다
            }
            sustain(player, state);
            regulateBreath(state, engine.pool(state.naegong));   // 조식 — 격을 싣지 않은 합에 단전이 돈다
            settleTraining(player, state);   // 날이 바뀌면 하루치 수련이 배분대로 갈린다
            // 생명 — 경지·장비가 바뀌면 다음 틱에 몸이 따라온다 (훅은 빠뜨리면 조용히 틀리고, 대조는 못 빠뜨린다)
            hud.vitalityTick(player, state, plugin.ledger(player.getUniqueId()));
            energyBossBar.update(player, state);   // 보스바 — 매 틱 대조 (값이 바뀔 때만 패킷이 나간다)
            // 내구·부상·태세는 격이 없어도 보인다 — 게이트를 두면 **삼류가 제 목숨을 영영 못 본다**
            // 경공 유지는 지속 상태다 — 순간 문구가 아니라 합성 한 줄의 상시 조각으로 병기한다 (B-116)
            GyeonggongListener gg = plugin.gyeonggong();
            hud.statusBar(player, state, tick, stanceOf(player),
                    gg == null ? null : gg.hudStatus(player));
        }
    }

    /**
     * 조식(調息) — 전투 중의 숨. {@code internal_energy.yml recovery.in_combat.조식} 의 배선.
     *
     * <p><b>운기조식(앉는 것)이 아니다.</b> 격을 싣지 않은 합에는 단전이 돈다 — 초식은 외공이니까
     * (cost_bands 외공기). 태우기와 고르기는 같은 합에 못 한다({@code only_if_unspent}).
     *
     * <p>이것이 없던 시절 개화 직후(내공 0.33 → 내력 풀 1)의 발경은 <b>전투당 한 번</b>이었다 —
     * 자원 관리가 아니라 형벌이었다. 이제 '한 합 태우고 한 합 고른다'(7합 전투에 발경 4회).
     * 축기가 사는 것은 총량이 아니라 <b>몰아 쓸 수 있는 합</b>이다 (내력 풀 3 = 3합 연발).
     *
     * <p>플레이어와 NPC 가 같은 함수를 탄다 — npc_combat.yml symmetry("NPC 자원 = 동일")의 이행이다.
     * 그전엔 NPC 만 하드코딩된 라운드당 1을 받고 있었고, 그 대칭은 거짓이었다.
     */
    private void regulateBreath(SkillEngine.State state, int pool) {
        int regen = engine.combatRegen(state.naegong);
        if (regen <= 0 || pool <= 0) {
            return;   // config 가 조식을 등록하지 않았다 — 코드가 수치를 지어내지 않는다
        }
        if (state.energyAtRoundStart < 0 || state.nextRegenTick < 0) {
            state.energyAtRoundStart = state.energy;
            state.upkeepThisRound = 0;
            state.nextRegenTick = tick + engine.roundTicks();
            return;
        }
        if (tick < state.nextRegenTick) {
            return;
        }
        // 【태운 것 vs 서리게 한 것】 이 합에 빠져나간 내력에서 **두름 유지비를 뺀 나머지**가 '태운 것'이다.
        //   두름(병기에 실은 격)의 유지비는 숨을 막지 않는다 — 날에 기를 서리게 한 채로도 호흡은 돈다.
        //   그 밖의 전부(발경·발출·경신[경공]·오의·호신강기 전개)는 태운 것이고, 태운 합엔 단전이 멎는다.
        int drained = state.energyAtRoundStart - state.energy;
        int burned = engine.regenUpkeepExempt() ? drained - state.upkeepThisRound : drained;
        boolean spent = burned > 0;
        // 【무한 방어의 못】 호신강기를 두른 자는 숨을 못 고른다 — 몸을 통째로 기로 감쌌기 때문이다.
        boolean guarding = engine.regenBlockedByGuard() && SkillEngine.GUARD.equals(state.armed);
        if (!guarding && !(engine.regenOnlyIfUnspent() && spent) && state.energy < pool) {
            state.energy = Math.min(pool, state.energy + regen);
        }
        state.energyAtRoundStart = state.energy;
        state.upkeepThisRound = 0;
        state.nextRegenTick = tick + engine.roundTicks();
    }

    /** 전투의 끝 — 흐름(발동권)은 그 전투의 것이다. 다음 싸움은 처음부터 읽어내야 한다 */
    private void endCombat(Player player, SkillEngine.State state) {
        state.combatUntil = -1;
        state.ultimateUses = 0;
        if (state.flow > 0) {
            state.flow = 0;
            flash(player, ChatColor.DARK_GRAY + "숨을 고른다 — 흐름이 흩어졌다");
        }
    }

    /** 공방이 오갈 때마다 전투 창을 민다 (오의 횟수·흐름의 수명) */
    private void touchCombat(SkillEngine.State state) {
        state.combatUntil = tick + engine.combatWindowTicks();
    }

    // ══════════ NPC 격 — 대칭 원칙 (npc_combat.yml symmetry) ══════════

    /**
     * 격을 쓰는 NPC 는 <b>플레이어와 같은 규칙</b>으로 쓴다: 경지 게이트 · 내력 · 두름 유지비 ·
     * 다운캐스트 · 텔레그래프. 사람이 가까이 있을 때만 돈다 (performance.yml npc_logic 6ms).
     */
    private void npcSweep() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            for (org.bukkit.entity.Entity entity : player.getNearbyEntities(
                    engine.cullBeyond(), 12, engine.cullBeyond())) {
                if (!(entity instanceof Mob mob) || !mob.isValid()) {
                    continue;
                }
                SkillEngine.Npc npc = npcOf(mob);
                if (npc == null || !npc.manifests()) {
                    continue;   // 외공의 몸 — 갈호(이류)도, 졸개도, 들짐승·맹수도 여기서 끝난다
                }
                npcThink(mob, npc, npcState(mob, npc));
            }
        }
        npcStates.keySet().removeIf(id -> {
            org.bukkit.entity.Entity e = plugin.getServer().getEntity(id);
            return e == null || e.isDead();   // 죽은 몸의 내력은 남지 않는다 (F-P2 cleanup_on death)
        });
    }

    private SkillEngine.Npc npcOf(org.bukkit.entity.Entity entity) {
        String id = HuntingGrounds.tag(entity, HuntingGrounds.KEY_ID);   // 등록부의 몸인가 (읽기 전용)
        return id == null ? null : engine.npc(id);
    }

    /** NPC 의 내력 장부 — 처음 보는 몸이면 등록부대로 세운다 (경지·내력 풀·두를 격) */
    private SkillEngine.State npcState(Mob mob, SkillEngine.Npc npc) {
        return npcStates.computeIfAbsent(mob.getUniqueId(), id -> {
            SkillEngine.State state = new SkillEngine.State();
            state.realm = npc.realm();
            // 풀 → 내공 (역함수) — 등록부가 내력을 직접 적었어도 조식은 내공을 읽는다 (대칭 원칙).
            // ★ 여기 `/ 3.0` 이 박혀 있었다. 풀 곡선이 세월(x(x+1)/2)로 바뀐 지금 그 나눗셈은 거짓말이다
            state.naegong = engine.naegongOf(npc.pool());
            state.energy = npc.pool();
            if (engine.sustainCost(npc.grade()) > 0) {
                state.armed = npc.grade();      // 두름형(검기·강기) — 켠 채로 선다. 유지비는 아래에서 낸다
                state.nextSustainTick = tick;
            }
            return state;
        });
    }

    /**
     * 한 번의 사고 — 유지비 · 회복 · 응집(텔레그래프).
     *
     * <p><b>두름</b>(검기·강기): 라운드마다 유지비. 못 내면 기가 흩어진다 — 플레이어가 그것을 본다.
     * <p><b>발경</b>: 유지비가 없다. 사거리에 들어오면 <b>응집을 시작</b>하고(선딜 = 텔레그래프),
     * 응집이 끝난 창 안에 들어간 타격에만 격이 실린다. 그 창을 보고 물러서면 맨 주먹이 온다.
     */
    private void npcThink(Mob mob, SkillEngine.Npc npc, SkillEngine.State state) {
        Location hand = mob.getEyeLocation();
        boolean fighting = mob.getTarget() instanceof Player;

        // 조식 — 플레이어와 같은 함수, 같은 config (npc_combat.yml symmetry).
        //   전투 중이든 밖이든 같은 규칙이다: 내력을 쓴 합에는 돌지 않고, 쉰 합에는 돈다.
        regulateBreath(state, npc.pool());

        if (!fighting) {
            if (state.armed == null && state.energy >= engine.sustainCost(npc.grade())
                    && engine.sustainCost(npc.grade()) > 0) {
                state.armed = npc.grade();   // 숨을 고르고 다시 두른다 (다운캐스트는 영구형이 아니다)
            }
            return;
        }

        int sustain = engine.sustainCost(state.armed);
        if (state.armed != null && sustain > 0) {
            if (tick >= state.nextSustainTick) {
                if (state.energy < sustain) {
                    state.armed = null;   // 다운캐스트 — 규칙이 대칭이어야 세계가 정직하다
                    event(hand, "격_소산");
                    return;
                }
                state.energy -= sustain;
                state.upkeepThisRound += sustain;   // 대칭 — NPC 의 두름도 숨을 막지 않는다
                state.nextSustainTick = tick + engine.roundTicks();
            }
            aura(hand, mob, state.armed);   // 두름 잔광 — 플레이어와 같은 등록부를 탄다
            return;
        }

        // 발경 — 두름이 없는 격. 사거리 + 쿨다운이 맞으면 응집한다
        if (state.energy < engine.npcStrikeCost(npc.grade())
                || mob.getLocation().distance(mob.getTarget().getLocation()) > NPC_REACH
                || state.onCooldown(CD_QI, tick)) {
            return;
        }
        SkillEngine.Frames f = engine.comboFrames("yukhap_geom", 2);   // 발경이 실리는 칸 (3타)
        state.cooldownUntil.put(CD_QI, tick + engine.roundTicks());
        state.qiHotUntil = tick + f.startup() + NPC_HOT_TICKS;
        // 응집 — 플레이어와 **같은 함수**를 탄다 (npc_combat.yml symmetry: "응집은 빛으로 보인다").
        // 그 창을 보고 물러서면 맨 주먹이 온다 — NPC 의 텔레그래프는 규칙의 일부다
        scheduleTelegraph(mob::getEyeLocation, npc.grade(), f.startup(), 0, 1.0f);
    }

    /** 지금 이 몸의 타격에 실리는 격 — 두름(상시) 또는 응집이 끝난 창(발경). 그 밖엔 외공기 */
    private String npcActiveGrade(SkillEngine.Npc npc, SkillEngine.State state) {
        if (state.armed != null) {
            return state.armed;
        }
        return tick <= state.qiHotUntil && tick >= state.qiHotUntil - NPC_HOT_TICKS
                ? npc.grade() : SkillEngine.BARE;
    }

    /** 두름 유지비 — 라운드마다 과금. 못 내면 기가 흩어진다 (상대가 읽을 수 있는 고갈 신호) */
    private void sustain(Player player, SkillEngine.State state) {
        if (state.armed == null) {
            return;
        }
        int cost = engine.sustainCost(state.armed);
        if (cost <= 0) {
            // 발경은 유지비가 없다 — 타격 순간에만 실린다. 그래도 <b>켜져 있다는 사실</b>은 보여야 한다
            // (등록부의 발경 잔광은 1개 — 검기 3, 강기 5 보다 흐리다. 격의 사다리는 잔광에서도 단조 증가한다)
            aura(handLocation(player), player, state.armed);
            return;
        }
        if (tick < state.nextSustainTick) {
            // 두름은 잔광으로만 알린다 — 켜져 있다는 사실 자체가 상대에게 정보다 (소리는 응집의 몫)
            aura(handLocation(player), player, state.armed);
            return;
        }
        if (state.energy < cost) {
            dispel(player, state, "기가 흩어진다 — 내력이 다했다");
            return;
        }
        state.energy -= cost;
        state.upkeepThisRound += cost;   // 두름 유지비 — 태운 것이 아니라 서리게 한 것이다 (조식을 막지 않는다)
        state.nextSustainTick = tick + engine.roundTicks();
        aura(handLocation(player), player, state.armed);
    }

    // ══════════ 입력 → 시전 ══════════

    /**
     * 한 대가 오가는 자리. 세 갈래다.
     * <ul>
     *   <li><b>플레이어의 칼</b> — 바닐라 피해를 취소하고 무공 판정으로 대체한다 (좌클릭 = 공격)</li>
     *   <li><b>NPC 의 칼</b> — 격이 실려 있으면 격 위력을 얹고, 방어자의 기 방어·무기를 판정한다</li>
     *   <li><b>그 밖</b> — 격 없는 타격(외공기). 그래도 호신강기·반격 오의는 반응한다</li>
     * </ul>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMelee(EntityDamageByEntityEvent event) {
        if (applying || !(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        // ★ 【안전 지역 · B-006】 길목의 맨 앞 — 판정도 내력 지불도 이 문을 지나야 시작된다.
        //   화살(발사체)의 손도 여기서 잡는다: 아래 분기들은 발사체를 사람의 일로 치지 않아서,
        //   이 문이 없으면 바닐라 화살이 안전 지역의 사람에게 그대로 실린다.
        Player assailant = event.getDamager() instanceof Player p ? p
                : event.getDamager() instanceof Projectile pr
                        && pr.getShooter() instanceof Player shooter ? shooter : null;
        if (assailant != null && safetyBlocks(assailant, target)) {
            event.setCancelled(true);
            safetyDenied(assailant, target);
            return;
        }
        // ★ 【타격 허용 · B-119】 비플레이어의 몸 — 등록부의 예외 표식만 문을 닫는다 (기본: 전부 허용)
        if (assailant != null && admissionBars(target)) {
            event.setCancelled(true);
            admissionDenied(assailant, target);
            return;
        }
        if (event.getDamager() instanceof Player player) {
            breakGuard(player);   // 행동 소모 — 때리는 손은 방어 전념을 버린 것이다 (active_guard)
            String skillId = skillInHand(player);
            // ★★ 【고침 — 사용자의 "공격해도 전혀 바뀌는 게 없다"의 가장 큰 몫이 여기 있었다】
            //   옛 코드는 무공이 손에 있으면 **먼저 바닐라 피해를 취소하고** swing() 을 불렀다.
            //   그런데 swing() 은 쿨다운·후딜이면 **조용히 돌아간다** (획만 뜨고 판정은 없다).
            //   → 매화검법(쿨다운 90틱 = 4.5초) 흑살도법(180틱 = 9초) 의 마무리를 낸 뒤,
            //     그 몇 초 동안 **검이 피해를 한 점도 안 냈다.** 획은 뜨고 팔은 휘둘러지는데
            //     상대는 아무 일도 없었다 — 무공을 배운 자가 배우지 않은 자보다 약해지는 구간이다.
            //   → 이제 무공이 **쉬고 있으면 기본 초식이 대신 나간다** (손은 언제나 나간다).
            //     그것이 등록부의 뜻이기도 하다: "무공이 쉬는 동안에도 손은 움직인다".
            if (skillId == null || !artReady(player, skillId)) {
                basicMelee(event, player, target);
                return;
            }
            event.setCancelled(true);
            swing(player, skillId, target);
            return;
        }
        if (!(event.getDamager() instanceof LivingEntity attacker)) {
            return;
        }
        npcStrike(event, attacker, target);
    }

    /**
     * NPC 의 한 대 — 대칭 원칙의 마지막 칸.
     *
     * <p>격이 실려 있으면 <b>격 위력</b>(combat.yml qi_power)이 피해에 더해지고, 발경은 그 자리에서
     * 내력을 낸다 (못 내면 다운캐스트 — 맨 주먹이 온다). 그리고 방어자의 <b>기 방어</b>(호신강기)와
     * <b>무기</b>(격돌 — 범철은 검기를 세 합 못 견딘다)가 같은 규칙으로 판정된다.
     */
    private void npcStrike(EntityDamageByEntityEvent event, LivingEntity attacker, LivingEntity target) {
        SkillEngine.Npc npc = npcOf(attacker);
        SkillEngine.State state = npc == null || !npc.manifests()
                ? null : npcStates.get(attacker.getUniqueId());
        String grade = state == null ? SkillEngine.BARE : npcActiveGrade(npc, state);

        if (state != null && !SkillEngine.BARE.equals(grade)) {
            int cost = engine.npcStrikeCost(grade);
            if (state.energy < cost) {
                grade = SkillEngine.BARE;                     // 다운캐스트 — 빈약함이 곧 정보다
                state.armed = null;
                event(attacker.getEyeLocation(), "다운캐스트");
            } else {
                state.energy -= cost;
                state.qiHotUntil = -1;                        // 응집을 태웠다 — 다시 모아야 한다
                event.setDamage(event.getDamage() + engine.qiPower(grade));
                impact(target.getLocation().add(0, 1, 0), grade,
                        new SkillEngine.Strike(0, 0, "success", "성공", true, 0), 1, 1.0f);
            }
        }

        // ★ 【맞는 쪽의 선택】 방어자가 태세를 세운다 — 회피(민첩+경공) · 막기(근력) · 흘리기(감각)
        //   여기가 수련의 절반이 사는 자리다. 그전엔 근력·감각·민첩을 아무리 키워도
        //   **맞을 때 아무 일도 일어나지 않았다.**
        int attackers = attackersOn(target);
        boolean surrounded = engine.surrounded(attackers);
        String stance = chooseStance(target, surrounded);
        Guardline line = stance == null ? null : guardline(target, stance, surrounded);
        String note = stanceNote(target, stance, surrounded);

        double incoming = event.getDamage();
        if (line != null) {
            // 대립 판정 — 공격 총합(+7) vs 태세 판정치 + 2d6(플레이어) / +7(NPC)
            int atk = foeAttackScore(attacker, target, attackers);
            int roll = target instanceof Player ? roll2d6() : NPC_JUDGMENT;
            int margin = atk - (line.score() + roll);
            if (target instanceof Player prey && eyes.contains(prey.getUniqueId())) {
                // 【판정의 눈 · 맞는 쪽】 우클릭 선언(B-015)을 시험하는 자리 — NPC 의 손을 받는 판정 (B-105)
                eyeStance(prey, attacker, line, atk, roll, margin);
            }
            if (margin < 0) {
                // 태세가 이겼다 — 안 맞는다. 회피면 GyeonggongListener(MONITOR)가 몸을 뒤로 뺀다
                event.setCancelled(true);
                stanceSucceeded(target, line, margin, note);
                return;
            }
            // 맞았다 — 그러나 태세는 값을 한다: 피해 = 무기 + 무공 + 격 + floor(마진/2) − 경감
            //   ★ floor(마진/2) 항이 지금까지 NPC 공격에 통째로 빠져 있었다 (combat.yml damage.formula).
            //     그 항이 없으면 방어 판정치가 명중/빗나감만 흔들고 **피해의 크기를 못 흔든다** —
            //     그러면 흘리기의 −2 는 순손해가 되고, 판정을 키우는 수련이 절반만 산다.
            incoming += foeTechniquePower(attacker) + Math.floorDiv(margin, 2);
            incoming -= line.soak();
            stanceFailed(target, line, margin, note);
            if (line.clashes()) {
                clashWeapon(target, grade);   // 막기는 무기를 태워 목숨을 산다 (회피·흘리기는 접촉이 없다)
            }
        }
        incoming -= armorSoak(target, grade);   // 갑옷 — 태세와 무관하게 언제나. 단 강기 앞에서는 0

        Defense defense = defend(target, attacker, grade, Math.max(0.0, incoming));
        if (defense.blocked() || defense.damage() <= 0.0) {
            event.setCancelled(true);
            return;
        }
        event.setDamage(defense.damage());
        if (line == null) {
            clashWeapon(target, grade);   // 태세 층이 없던 시절의 경로 (Growth 미배선) — 옛 동작 그대로
        }

        // ★ 【맞는 것이 플레이어일 때】 여기가 체감의 절반이다 — 지금까지 플레이어는 맞아도
        //   **밀리지도 않고 화면이 흔들리지도 않았다** (넉백은 몹 전용이었다).
        //   한 틱 뒤에 건다: 바닐라가 이 이벤트를 마저 처리하며 제 넉백을 먹이는데,
        //   그 위에 덮어야 우리 것이 산다 (같은 틱에 걸면 바닐라가 우리를 덮는다).
        final double dealt = defense.damage();
        final String landed = grade;
        pending.add(new Pending(tick + 1, () -> strikeLanded(attacker, target, landed, dealt, null)));
    }

    /**
     * <b>빌드의 대가를 규칙이 아니라 화면이 가르친다.</b>
     *
     * <p>둘 있다: ① 회피를 고르고 서 있었는데 <b>둘에게 잡혔다</b> — "몸을 뺄 자리가 없다"
     * (신법 빌드가 다구리에서 벌거벗는 순간. 그것이 규칙이고, 화면이 그 말을 한다).
     * ② 막기를 골랐는데 <b>손이 비었다</b> — "받을 것이 없다" (경감은 든다. 부러질 물건이 없을 뿐).
     */
    private String stanceNote(LivingEntity body, String stance, boolean surrounded) {
        Growth growth = Growth.get();
        if (growth == null || stance == null || !(body instanceof Player player)) {
            return "";
        }
        String wanted = stancePin.get(player.getUniqueId());
        if (wanted == null && player.isSprinting()) {
            wanted = engine.stanceOfGesture("isSprinting");
        }
        if (surrounded && wanted != null && growth.lostWhenSurrounded(wanted)) {
            return ChatColor.DARK_RED + " │ " + stanceLabel("회피_봉쇄");
        }
        EntityEquipment gear = player.getEquipment();
        if (growth.clashes(stance) && (gear == null || gear.getItemInMainHand().getType().isAir())) {
            return ChatColor.GRAY + " │ " + stanceLabel("맨손_막기");
        }
        return "";
    }

    /**
     * 방어자가 지금 세우는 태세 한 줄 — <b>플레이어든 NPC든 같은 규칙</b> (대칭 원칙).
     * {@code null} 이면 성장 축이 미배선 — 호출자는 조용히 옛 동작(고정 난이도)으로 돌아간다.
     */
    private Guardline defenderStance(LivingEntity target) {
        if (Growth.get() == null) {
            return null;
        }
        boolean surrounded = engine.surrounded(attackersOn(target));
        String stance = chooseStance(target, surrounded);
        return stance == null ? null : guardline(target, stance, surrounded);
    }

    /** 2d6 — 전투는 주사위를 쓴다 (판정 밖의 조성기는 난수를 안 쓴다. 전투만 예외다) */
    private static int roll2d6() {
        return ThreadLocalRandom.current().nextInt(6) + 1 + ThreadLocalRandom.current().nextInt(6) + 1;
    }

    /**
     * <b>태세가 이겼다</b> — 화면이 그 사실을 말한다 (파티클·소리·글자. 팩이 없어도 셋 다 보인다).
     * 회피면 {@code event.setCancelled(true)} 위에서 {@link GyeonggongListener#onDodged} 가
     * 몸을 <b>실제로 뒤로 뺀다</b> — 경공 담당이 깔아 둔 이음매다.
     */
    private void stanceSucceeded(LivingEntity body, Guardline line, int margin, String note) {
        boolean parried = stanceWon(body, line.stance());   // 虛 의 기록 — 절기의 관문이 읽는다 (B-015)
        stanceFx(body, line.stance());
        if (!(body instanceof Player player)) {
            return;
        }
        stanceNow.put(player.getUniqueId(), line.stance());
        hud.flash(player, ChatColor.AQUA + stanceLabel(line.stance())
                + (parried ? ChatColor.LIGHT_PURPLE + " · " + stanceLabel("패링") : "")
                + ChatColor.DARK_GRAY + " │ 방어 " + line.score() + " (마진 " + margin + ")" + note,
                tick + engine.hudFlashTicks());
    }

    /** <b>태세가 무너졌다</b> — 그래도 경감은 든다 (막는 것은 판정이 아니라 몸이다) */
    private void stanceFailed(LivingEntity body, Guardline line, int margin, String note) {
        stanceFx(body, line.soak() > 0 ? line.stance() : "실패");
        if (!(body instanceof Player player)) {
            return;
        }
        stanceNow.put(player.getUniqueId(), line.stance());
        String head = line.soak() > 0
                ? ChatColor.YELLOW + stanceLabel(line.stance())
                        + ChatColor.DARK_GRAY + " (경감 −" + line.soak() + ")"
                : ChatColor.RED + stanceLabel("실패");
        hud.flash(player, head + ChatColor.DARK_GRAY + " │ 마진 " + margin + note,
                tick + engine.hudFlashTicks());
    }

    /**
     * <b>채굴의 그림자</b> — 이 몸이 마지막으로 블록을 깬 틱 ({@link AttackRhythm#digShadow}).
     * 좌클릭 홀드로 캐는 동안 클라이언트는 매 틱 스윙 패킷을 보내고, 블록이 깨진 틱의 스윙은
     * 갓 뚫린 구멍을 지나 빗나가 Paper 가 LEFT_CLICK_AIR 로 합성한다 — 그것은 공격 입력이 아니다.
     * 봉(옛 월아산 — 유일한 삽 베이스 병기)이 땅을 2~4틱에 깨서 이 합성이 자동 연속 공격으로 나타났다 (2026-07-17).
     */
    private final Map<UUID, Long> lastDig = new HashMap<>();

    /** 깬 틱을 적는다 — 취소 여부와 무관 (깨졌을 때만 구멍이 나지만, 적어 둬서 해로울 일이 없다) */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDig(org.bukkit.event.block.BlockBreakEvent event) {
        lastDig.put(event.getPlayer().getUniqueId(), tick);
    }

    /** 허공 좌클릭 = 헛손질(콤보는 진행된다) / Shift+좌클릭 = 발출 / Shift+우클릭 = 격 태세 */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != null && event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        Action action = event.getAction();

        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            if (!player.isSneaking()) {
                // ★ 【B-015】 맨 우클릭 = **방어 선언** (방어_전념의 MC 환산 — active_guard).
                //   취소하지 않는다: 문·상자·음식은 그대로 세계의 몫이다 (선언은 그 위에 얹힐 뿐)
                declareGuard(player, event);
                return;
            }
            event.setCancelled(true);
            cycleArmed(player);
            return;
        }
        if (action != Action.LEFT_CLICK_AIR) {
            return;   // LEFT_CLICK_BLOCK 은 건드리지 않는다 — 채굴을 무공이 잡아먹으면 안 된다
        }
        // ★ 【봉(옛 월아산) 자동 연발 · 2026-07-17】 채굴의 그림자 — 블록을 깬 직후의 허공 스윙 패킷은
        //   캐는 손이지 공격의 손이 아니다 (기전·근거는 AttackRhythm 의 문서에 있다).
        //   breakGuard 보다 먼저 선다: 캐는 손은 공격이 아니므로 방어 전념도 깨지 않는다.
        if (AttackRhythm.digShadow(tick,
                lastDig.getOrDefault(player.getUniqueId(), Long.MIN_VALUE / 2))) {
            return;
        }
        breakGuard(player);   // 행동 소모 — 공격하는 손은 방어 전념을 버린 것이다 (active_guard.break_on_attack)
        SkillEngine.State state = state(player);
        if (player.isSneaking() && engine.gradeRank(offense(state)) >= 2) {
            shoot(player, state);
            return;
        }
        String skillId = skillInHand(player);
        if (skillId != null) {
            swing(player, skillId, null);
            return;
        }
        basicSwing(player);   // 무공이 없어도 병기는 궤적을 그린다 (허공을 갈라도 획은 남는다)
    }

    // ─── 콤보 ───

    /**
     * <b>【무공 없는 손】</b> 병기를 들고 좌클릭한다 — 그것만으로 궤적이 뜬다.
     *
     * <p>사용자 보고("공격 모션은 아직 안 바뀐 거 같고")의 원인이 여기 있었다: 3D 층이 <b>무공 시전 경로
     * 안에만</b> 살아 있었고, 무공을 안 배운 손(= 가장 흔한 손)은 그 경로에 들어오지도 못했다.
     *
     * <p><b>무공은 궤적을 바꾸는 것이지, 무공이 없다고 궤적이 없는 것이 아니다.</b>
     * 히트박스·프레임은 등록부({@code basic_strike})가 준다 — 코드가 지어내지 않는다.
     * 판정은 건드리지 않는다 (바닐라 피해 그대로 = 외공기). 이것은 <b>연출 층</b>이다.
     */
    /**
     * 이 무공이 지금 <b>나갈 수 있는가</b> — {@link #swing} 의 관문과 <b>같은 셈</b>이다.
     *
     * <p>{@code onMelee} 가 <b>바닐라 피해를 취소하기 전에</b> 이것을 물어야 한다. 안 물으면
     * 취소는 됐는데 무공은 안 나가서 <b>한 대가 통째로 증발한다</b> (그것이 옛 동작이었다).
     * 같은 답을 두 곳에서 계산하지 않도록, {@code swing} 도 제 관문을 그대로 둔다 (허공 좌클릭 경로).
     */
    private boolean artReady(Player player, String skillId) {
        SkillEngine.State state = state(player);
        return tick >= state.busyUntil && !state.onCooldown(skillId, tick);
    }

    /**
     * <b>【무공 없는 손이 한 대를 넣는다】 — 사용자가 "전혀 바뀌는 게 없다"고 말한 바로 그 자리.</b>
     *
     * <p><b>진단(사실)</b>: 이 경로는 <b>바닐라 피해를 그대로 통과시키고 획만 얹었다.</b>
     * 타격음도(격의 impact 사운드는 무공 경로에만 있었다) · 히트스톱도 · 넉백도 · 화면 흔들림도 없었다.
     * 그리고 등록부의 {@code basic_strike.frames [선딜, 지속, 후딜]} 은 <b>연출 길이로만</b> 쓰였다 —
     * <b>시간 구조가 등록부에 적혀 있는데 판정이 그것을 안 읽고 있었다.</b>
     *
     * <p><b>이제 두 가지를 한다</b> (combat.yml {@code impact}):
     * <ol>
     *   <li><b>시간 구조</b> — 바닐라 피해를 취소하고 <b>선딜만큼 벼렸다가</b> 우리 손으로 넣는다
     *       (검 2틱 · 부 6틱 · 중병기 7틱). <b>선딜의 값은 '피할 수 있다'는 것이다</b>:
     *       그 사이 상대가 {@code range + reach_grace} 밖으로 몸을 빼면 <b>헛친다</b>.</li>
     *   <li><b>타격 층</b> — 격의 타격음·타격 파티클 + 멈춤·밀림·흔들림({@link #strikeLanded}).</li>
     * </ol>
     *
     * <p><b>판정 (B-005 — 그 청구서를 여기서 닫았다)</b>: 이제 평타도 <b>대립 판정층</b>을 탄다
     * ({@link #basicJudged}) — 그전엔 바닐라 피해가 그대로 흘러서, 하필 <b>무공 없는 손</b>(강호의
     * 가장 흔한 손)에 맞을 때만 방어자의 태세·마진·격이 통째로 무시됐다 (docs/design/defense.md §8-5).
     * 단 '무기 위력' 항만은 여전히 <b>바닐라가 정한 그대로</b>({@code event.getDamage()}) 쓴다 —
     * 바닐라의 공격 쿨다운(연타 감쇠)이 이미 그 값에 반영돼 있고, 그것을 우리가 다시
     * 지어내면 밸런스를 조용히 뒤엎게 된다 (combat.yml {@code basic_strike_judgment}).
     */
    private void basicMelee(EntityDamageByEntityEvent event, Player player, LivingEntity target) {
        // ★ 허수아비는 계기다 — **계기와는 대립하지 않는다** (등록부: "안 죽고, 안 움직이고,
        //   맞은 것을 말한다"). B-005 의 대립 판정이 여기까지 번져 배우는 손의 타격 대부분이
        //   **조용히** 기각됐다 (실측 2026-07-15: 여러 타 중 누적 1 — 나루의 손 과제가 안 늘었다).
        //   획은 그리고, 취소하지 않는다 — 바닐라 피해가 그대로 실리고 나루의 눈(onDamage)이 센다 (B-132)
        if (Antechamber.dummy(target)) {
            basicSwing(player);
            return;
        }
        basicSwing(player);   // 획 · 몸의 자세 (있던 그대로 — CD_BASIC 이 겹침만 막는다)

        SkillEngine.Impact im = engine.impact();
        String weaponClass = engine.weaponClassOf(
                player.getInventory().getItemInMainHand(), materialName(player));
        SkillEngine.Basic basic = engine.basicStrike(weaponClass);
        SkillEngine.State state = state(player);
        double raw = event.getDamage();
        // 시간 구조(선딜)는 연출 등록부(impact)의 것이지만 **판정은 아니다** — impact 를 꺼도
        // 판정층은 산다 (연출 토글이 전투 규칙을 껐다 켰다 하면 그것이 곧 밸런스 스위치가 된다)
        int startup = basic == null || !im.enabled() || !im.basicStartup()
                ? 0 : basic.frames().startup();

        if (startup <= 0) {
            // 시간 구조가 없는 손(활·미등록 계열, 또는 impact 꺼짐) — 즉발. 판정만 태우고
            // 바닐라 흐름 위에 얹는다 (취소하지 않는다 = 가장 안전한 길. FX 는 스스로 게이트한다)
            SkillEngine.Frames f = basic == null ? null : basic.frames();
            BasicHit hit = basicJudged(player, target, raw);
            if (!hit.landed()) {
                event.setCancelled(true);   // 태세·기 방어가 이겼다 — 회피면 onDodged 가 몸을 뺀다
                return;
            }
            event.setDamage(hit.damage());
            basicFx(player, target, hit.grade(), hit.damage());
            strikeLanded(player, target, hit.grade(), hit.damage(), f);
            return;
        }

        // 【시간 구조】 벼른다 → 벤다. 바닐라의 즉발 피해를 취소하고 선딜 뒤에 우리가 넣는다
        event.setCancelled(true);
        touchCombat(state);
        windup(player, weaponClass, basic.trail(), startup);
        final double reach = basic.range() + im.reachGrace();
        final SkillEngine.Frames frames = basic.frames();
        pending.add(new Pending(tick + startup, () -> {
            if (!player.isOnline() || !target.isValid() || target.isDead()) {
                return;
            }
            Location me = player.getEyeLocation();
            if (me.getWorld() != target.getWorld()
                    || me.distanceSquared(target.getLocation()) > reach * reach) {
                // 【선딜의 값】 그 0.1초에 몸을 뺐다. 무게에는 대가가 있다 — 그것이 정직한 대가다
                event(me, "헛손질");
                flash(player, ChatColor.GRAY + "헛손질 " + ChatColor.DARK_GRAY
                        + "│ 몸을 뺐다");
                return;
            }
            // 판정은 **베는 순간**에 선다 (resolve 와 같은 자리) — 격 지불·태세·주사위 전부.
            // 클릭 순간에 굴려 두면 선딜 사이에 바뀐 태세(몸짓)가 판정에 못 들어온다
            BasicHit hit = basicJudged(player, target, raw);
            if (!hit.landed()) {
                return;
            }
            applying = true;   // 재진입 가드 — 우리가 넣는 피해를 우리가 다시 잡지 않는다
            try {
                target.damage(hit.damage(), player);
            } finally {
                applying = false;
            }
            basicFx(player, target, hit.grade(), hit.damage());
            strikeLanded(player, target, hit.grade(), hit.damage(), frames);
        }));
    }

    /** 평타 한 대의 판정 결과 — 실렸는가 · 실제로 실린 격(다운캐스트 반영) · 들어갈 피해 */
    private record BasicHit(boolean landed, String grade, double damage) {
    }

    /**
     * <b>평타의 대립 판정 (B-005)</b> — {@link #npcStrike} 의 판정층을 <b>공격 방향만 뒤집어</b>
     * 그대로 태웠다. 새 산술이 아니다: 마진·경감의 두 줄은 combat.yml {@code check_formula} ·
     * {@code soak_rule} 그대로이고, defense_audit 의 {@code expected()} 가 재는 것과 같은 셈이다.
     *
     * <p>층의 순서도 세 길이 같다 ({@code npcStrike} · {@link #resolve} · 여기):
     * <b>격 지불 → 태세(마진) → 갑옷 → 기 방어</b>. 다른 것은 둘뿐이다 —
     * ① '무공 위력' 항이 0 이다 (<b>평타에는 초식이 없다</b> — 짐승의 이빨과 같은 이유,
     * {@link #foeTechniquePower}). ② 무기 위력 항이 등록부 위력표가 아니라 <b>바닐라 피해</b>다
     * (연타 감쇠가 이미 실려 있다 — combat.yml {@code basic_strike_judgment}).
     * 방어자가 굴리지 않고 +7 로 서는 것은 {@link #resolve} 의 "PvP 근사" 규약 그대로다 —
     * 도구({@code defense_audit})와 엔진이 같은 셈을 해야 도구가 안 거짓말한다.
     */
    private BasicHit basicJudged(Player player, LivingEntity target, double raw) {
        // 【안전 지역 · B-006】 벼른 뒤 **베는 순간**의 문 — 선딜 사이에 상대가 관아 문턱을
        //   넘었을 수 있다 (onMelee 의 문은 클릭 순간의 자리만 본다). 내력 지불보다 먼저 선다.
        if (safetyBlocks(player, target)) {
            safetyDenied(player, target);
            return new BasicHit(false, SkillEngine.BARE, 0.0);
        }
        // 【타격 허용 · B-119】 베는 순간의 문 — 선딜 사이에 표식이 바뀌지는 않지만, 세 판정길이
        //   같은 문을 지나야 문이 하나다 (onMelee · 여기 · admit — 안전 게이트와 같은 세 길목)
        if (admissionBars(target)) {
            admissionDenied(player, target);
            return new BasicHit(false, SkillEngine.BARE, 0.0);
        }
        SkillEngine.State state = state(player);
        touchCombat(state);
        // 격은 두른 것을 그대로 싣는다 — 검기를 두르고 그냥 휘둘러도 **기의 타격**이 난다.
        // 값도 NPC 와 같은 셈이다: 두름(검기·강기)은 유지비가 이미 냈고, 발경은 타격마다 낸다
        String grade = offense(state) == null ? SkillEngine.BARE : offense(state);
        if (!SkillEngine.BARE.equals(grade)) {
            int cost = engine.npcStrikeCost(grade);
            if (state.energy < cost) {
                grade = SkillEngine.BARE;   // 다운캐스트 — 빈약함이 곧 정보다
                event(handLocation(player), "다운캐스트");
            } else {
                state.energy -= cost;
                raw += engine.qiPower(grade);   // 격 위력 — 지불한 타격에만 실린다 (qi_power_note)
            }
        }

        // 방어자의 태세 — npcStrike 와 같은 층. 맞는 쪽에서 보면 오는 손이 평타인지 초식인지는 상관없다
        int attackers = attackersOn(target);
        boolean surrounded = engine.surrounded(attackers);
        String stance = chooseStance(target, surrounded);
        Guardline line = stance == null ? null : guardline(target, stance, surrounded);
        String note = stanceNote(target, stance, surrounded);
        // 【판정의 눈 · B-105】 평타도 무공과 같은 가시성 — 꺼져 있으면 비용은 if 한 줄이다
        boolean eye = eyes.contains(player.getUniqueId());
        boolean preyEye = target instanceof Player && eyes.contains(target.getUniqueId());
        if (line != null) {
            // 대립 판정 — 공격 총합 + 2d6(공격자가 굴린다) vs 태세 판정치 + 7
            int atkScore = basicAttackScore(player, target);
            int roll = roll2d6();
            int resist = line.score() + NPC_JUDGMENT;
            int margin = atkScore + roll - resist;
            if (eye) {
                // 눈은 판정이 쓴 값을 그대로 본다 — 평타의 눈이 따로 셈하면 그 눈은 거짓말할 수 있다.
                //   문법은 resolve 와 한 벌(eyeRoll): 격/등급(tier)이 없는 손이라 명중/빗나감 두 칸이다
                Growth growth = Growth.get();
                int attrBonus = growth == null ? 0 : growth.attackBonus(
                        plugin.ledger(player.getUniqueId()),
                        engine.weaponClassOf(player.getInventory().getItemInMainHand(), null),
                        engine.realmAttr(state.realm));
                eyeRoll(player, target, attrBonus, weaponSkill(player), atkScore, roll, resist,
                        line, new SkillEngine.Strike(roll, margin,
                                margin < 0 ? "miss" : "basic", margin < 0 ? "빗나감" : "명중",
                                margin >= 0, (int) Math.round(raw)));
            }
            if (preyEye) {
                // 【맞는 쪽】 우클릭 선언(B-015)의 판정이 시험대에 선다 — 태세·선언·패링이 숫자로 보인다
                eyeStance((Player) target, player, line, atkScore + roll, NPC_JUDGMENT, margin);
            }
            if (margin < 0) {
                stanceSucceeded(target, line, margin, note);
                flash(player, ChatColor.GRAY + "헛손질 " + ChatColor.DARK_GRAY
                        + "│ " + stanceLabel(line.stance()));
                return new BasicHit(false, grade, 0.0);
            }
            // 피해 = 무기(바닐라) + 무공(0) + 격 + floor(마진/2) − 경감 (combat.yml soak_rule)
            raw += Math.floorDiv(margin, 2);
            stanceFailed(target, line, margin, note);
            if (line.clashes()) {
                clashWeapon(target, grade);   // 막기는 무기를 태워 목숨을 산다 (회피·흘리기는 접촉이 없다)
            }
        }
        // 경감 두 층을 기준선(base)에서 갈라 둔다 — 눈이 "어느 층에서 깎였는가"를 셈과 같은 값으로 본다
        double base = raw;
        int stanceSoak = line == null ? 0 : line.soak();
        int armor = armorSoak(target, grade);   // 갑옷 — 태세와 무관하게 언제나. 단 강기 앞에서는 0

        // 상대의 기 방어(호신강기)·반격 오의가 같은 규칙으로 판정된다 (대칭)
        Defense defense = defend(target, player, grade, Math.max(0.0, base - stanceSoak - armor));
        if (eye) {
            // 【판정의 눈】 피해가 어느 층에서 깎였는가 — resolve 와 같은 눈, 같은 문법 (B-105)
            eyeDamage(player, (int) Math.round(base), stanceSoak, armor, defense);
        }
        if (defense.blocked() || defense.damage() <= 0.0) {
            return new BasicHit(false, grade, 0.0);
        }
        if (line == null) {
            clashWeapon(target, grade);   // 태세 층이 없던 시절의 경로 (Growth 미배선) — 옛 동작 그대로
        }
        return new BasicHit(true, grade, defense.damage());
    }

    /**
     * 평타의 판정 총합 — {@link #resolve} 의 {@code execBase} 와 같은 항이다.
     * '무공 숙련' 자리에는 <b>병기 기술</b>({@link #weaponSkill} — 막기·흘리기의 기술 항과 같은 값)이
     * 선다: 매화검법을 익힌 손은 초식이 쉬는 사이의 한 획도 다르다. 그리고 NPC 의 평타가 이미
     * 그 경지의 표준 기술을 싣고 온다 ({@link #foeAttackScore} 의 {@code realmSkill}) —
     * 플레이어만 0 이면 대칭 원칙이 평타에서 깨진다.
     */
    private int basicAttackScore(Player player, LivingEntity target) {
        SkillEngine.State state = state(player);
        Growth growth = Growth.get();
        // 시트가 없는 몸은 그 경지의 표준 무인이다 (resolve 와 같은 폴백 — realmAttr)
        int attrBonus = growth == null ? 0 : growth.attackBonus(plugin.ledger(player.getUniqueId()),
                engine.weaponClassOf(player.getInventory().getItemInMainHand(), null),
                engine.realmAttr(state.realm));
        return attrBonus + weaponSkill(player)
                + engine.weaponJudgmentBonus(weaponGrade(player))
                + engine.realmGapBonus(state.realm, foeRealm(target, target instanceof Monster))
                + (engine.isDepleted(state.energy) ? -2 : 0);   // 내공 고갈 = 판정 -2
    }

    /**
     * 기본 초식의 타격 연출 — <b>격의 타격음·타격 파티클</b> (combat.yml {@code impact.basic_startup.fx}).
     *
     * <p>그전엔 이 손에 <b>소리가 없었다</b>: 격의 {@code impact} 사운드는 무공 경로({@link #resolve})
     * 에만 걸려 있었고, 무공 없는 손은 바닐라 주먹 소리로 끝났다. <b>소리가 없으면 때린 것 같지 않다.</b>
     */
    private void basicFx(Player player, LivingEntity target, String grade, double damage) {
        if (!engine.impact().basicFx()) {
            return;
        }
        impact(target.getLocation().add(0, 1, 0), grade,
                new SkillEngine.Strike(0, 0, "success", "성공", true, (int) Math.round(damage)),
                1, 1.0f);
    }

    private void basicSwing(Player player) {
        SkillEngine.State state = state(player);
        if (state.onCooldown(CD_BASIC, tick)) {
            return;   // 바닐라 연타에 획이 겹쳐 쌓이지 않게 (등록부 basic_strike.cooldown_ticks)
        }
        String weaponClass = engine.weaponClassOf(
                player.getInventory().getItemInMainHand(), materialName(player));
        SkillEngine.Basic basic = engine.basicStrike(weaponClass);
        if (basic == null) {
            return;   // 활·무관·짐승 — 우리가 얹을 것이 없다 (바닐라가 제 일을 한다)
        }
        // ★ 【병기의 박자 · 2026-07-17】 등록부 연출 간격(4틱)과 계열 공속(봉 20틱) 중 긴 쪽 —
        //   홀드·연타가 병기의 박자를 넘어 획·전진을 연발하지 못한다. 무공 경로의
        //   busyUntil = max(frames, swingInterval) ("공속이 거짓말하지 않게")과 같은 못이다.
        //   판정은 불변: 몹 타격의 피해는 이 문과 무관하게 basicMelee 가 그대로 잰다.
        state.cooldownUntil.put(CD_BASIC, AttackRhythm.basicCooldownUntil(
                tick, engine.basicCooldownTicks(), swingInterval(player)));

        // 격은 두른 것을 그대로 쓴다 — 검기를 두르고 그냥 휘둘러도 **기의 획**이 나간다 (무공과 무관하게)
        String grade = offense(state) == null ? SkillEngine.BARE : offense(state);
        int swingTicks = (int) Math.max(basic.frames().total(), swingInterval(player));

        String stroke = nextStroke(player, weaponClass);   // 그림의 순번 (입력 문법이 아니다)

        // ★ 부(斧)의 횡참 — 짧고 굵고 둔중한 궤적 (heavy_slash · 사용자 확정: 충격은 빼고 횡참)
        SkillEngine.HeavySlash imp = engine.heavySlash();
        if (imp != null && imp.enabled() && imp.appliesTo(weaponClass)) {
            int heavyDir = -kigiDir.getOrDefault(player.getUniqueId(), -1);
            kigiDir.put(player.getUniqueId(), heavyDir);          // 검기와 같은 교대 토글 공유
            spawnHeavySlash(player, imp, heavyDir);
            if (imp.replaceStroke()) {
                SkillEngine.Style style0 = engine.weaponStyle(weaponClass);
                if (style0 != null) {
                    sfx(player.getLocation(), style0.swing());
                }
                posture(player, weaponClass, basic.trail(), swingTicks);
                return;
            }
        }

        // ★ 전용 검기(劍氣) 평타 — 호 계열의 작은 무협 참격을 크고 선명한 초록 초승달로 대체 (kigi_slash)
        SkillEngine.KigiSlash kigi = engine.kigiSlash();
        if (kigi != null && kigi.enabled() && kigi.appliesTo(weaponClass, basic.trail())) {
            spawnKigiSlash(player);                         // 초록 초승달 검기(3D) + 흰 별 반짝이
            if (kigi.replaceStroke()) {
                // 기존 무협 참격 아크·궤적 파티클을 이 무기에 대해 그리지 않는다 (레퍼런스는 검기+흰별만).
                // 몸의 자세(전진·포즈)는 유지하고, 스윙음만 남긴다 — 판정(basicMelee)은 어차피 별개 경로다.
                SkillEngine.Style style = engine.weaponStyle(weaponClass);
                if (style != null) {
                    sfx(player.getLocation(), style.swing());
                }
                posture(player, weaponClass, basic.trail(), swingTicks);
                return;
            }
            // replace_stroke=false — 검기에 **더해** 기존 참격·궤적도 그린다 (additive)
        }

        boolean solid = strike(player, basic.trail(), grade, weaponClass,
                basic.range(), basic.angle(), swingTicks, stroke);
        // ★ 무공 없는 손도 궤적을 남긴다 — 여기에 **아무것도 없었다** (그것이 찌르기로 보인 절반이다)
        basicTrail(player, basic, grade, weaponClass, stroke, swingTicks, solid);
    }

    // ══════════ ★ 전용 검기(劍氣) 평타 — 발행 층 (3D 소환 + 흰 별) ══════════

    /** 스윙마다 좌↔우 방향 — alternate 면 토글, 아니면 +1 고정 (레퍼런스 "한 세트 더") */
    private final Map<UUID, Integer> kigiDir = new HashMap<>();

    /**
     * 전용 검기 평타 발행 — 초록 초승달 검기(3D · {@link SkillDisplay#kigiSlash})를 소환하고
     * 흰 별을 아크를 따라 뿌린다. dirSign 은 alternate 면 스윙마다 번갈아 바뀐다.
     *
     * @return 3D 검기가 실제로 떴는가 (흰 별은 팩 유무와 무관하게 늘 뿌린다)
     */
    private boolean spawnKigiSlash(Player player) {
        SkillEngine.KigiSlash cfg = engine.kigiSlash();
        if (cfg == null || !cfg.enabled()) {
            return false;
        }
        int dirSign;
        if (cfg.alternate()) {
            dirSign = -kigiDir.getOrDefault(player.getUniqueId(), -1);
            kigiDir.put(player.getUniqueId(), dirSign);
        } else {
            dirSign = 1;
        }
        boolean drawn = display.kigiSlash(player, cfg, dirSign);
        kigiSparks(player, cfg, dirSign);   // 흰 별 — 파티클은 팩이 없어도 늘 보인다
        if (cfg.bandHit()) {
            kigiBandStrike(player, cfg, dirSign);   // ★ 띠가 곧 판정 — 화면과 같은 거울로
        }
        return drawn;
    }

    /**
     * 부의 횡참 소환 — 검기 띠와 같은 밴드를 <b>짧고 굵게</b> 긋는다 (허리 높이 · 얕은 대각).
     * 판정도 같은 문법: 스윕 동안 궤적 좌표를 표본해 닿은 생명체에 딜 (재진입 일원화).
     */
    private void spawnHeavySlash(Player player, SkillEngine.HeavySlash cfg, int dirSign) {
        QiGeometry geom = plugin.qiGeometry();
        if (geom == null) {
            return;
        }
        Location feet = player.getLocation();
        Location center = feet.clone();
        center.setY(feet.getY() + cfg.centerHeight());
        double f = Math.toRadians(player.getLocation().getYaw());
        double full = Math.toRadians(cfg.sweepDeg());
        double tilt = Math.toRadians(cfg.tiltDeg());
        double r = cfg.radius();
        double reach = Math.max(0.3, cfg.hitReach());
        int span = Math.max(1, cfg.sweepTicks());
        double fwdX = -Math.sin(f), fwdZ = Math.cos(f);
        // ★ 부호 수정 (2026-07-22): 옛 rgtZ=+sin(f) 는 yaw 45°에서 fwd 와 평행 — 화면과 같은 거울 유지
        double rgtX = -Math.cos(f), rgtZ = -Math.sin(f);
        Set<UUID> struck = new HashSet<>();
        float yaw = player.getLocation().getYaw();
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= span + 5 || !player.isOnline()) {
                    cancel();
                    return;
                }
                java.util.UUID me = player.getUniqueId();
                if (t < span) {
                    geom.slashBand(center, yaw, r, cfg.sweepDeg(),
                            (double) t / span, (double) (t + 1) / span,
                            cfg.tiltDeg(), cfg.stepDeg(),
                            cfg.bandWidth() * cfg.widthSelfMul(), cfg.bandRows(), cfg.bandJitter(),
                            "dust", cfg.ink(), dirSign, v -> v.getUniqueId().equals(me),
                            1.0, player.getEyeLocation(), false, false, cfg.inkAlt());
                    geom.slashBand(center, yaw, r, cfg.sweepDeg(),
                            (double) t / span, (double) (t + 1) / span,
                            cfg.tiltDeg(), cfg.stepDeg(),
                            cfg.bandWidth() * cfg.widthOthersMul(), cfg.bandRows(), cfg.bandJitter(),
                            "dust", cfg.ink(), dirSign, v -> !v.getUniqueId().equals(me),
                            cfg.heightOthersMul(), null, true, false, cfg.inkAlt());
                } else if (t == span + 1 || t == span + 3) {
                    geom.slashBand(center, yaw, r, cfg.sweepDeg(), 0.0, 1.0,
                            cfg.tiltDeg(), cfg.stepDeg() * 2.0,
                            cfg.bandWidth(), cfg.bandRows(), cfg.bandJitter(),
                            "dust", cfg.ink(), dirSign, null, 1.4, null, false, true, cfg.inkAlt());
                }
                if (cfg.hit()) {
                    for (int i = 0; i <= 6; i++) {
                        double phase = (t + i / 6.0) / span;
                        double phi = -full / 2.0 + full * phase;
                        double side = Math.sin(phi) * r * Math.cos(tilt);
                        double up = -Math.sin(phi) * r * Math.sin(tilt) * (dirSign < 0 ? -1.0 : 1.0);
                        double fwd = Math.cos(phi) * r;
                        Location p = center.clone().add(
                                fwdX * fwd + rgtX * side, up, fwdZ * fwd + rgtZ * side);
                        for (org.bukkit.entity.Entity e
                                : p.getWorld().getNearbyEntities(p, reach, 1.4, reach)) {
                            if (!(e instanceof LivingEntity le) || e == player
                                    || struck.contains(e.getUniqueId())) {
                                continue;
                            }
                            struck.add(e.getUniqueId());
                            var atk = player.getAttribute(
                                    org.bukkit.attribute.Attribute.ATTACK_DAMAGE);
                            double dmg = Math.max(1.0, atk == null ? 1.0 : atk.getValue());
                            le.damage(dmg, player);   // → basicMelee 재진입 (판정 일원화)
                        }
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, cfg.trailDelayTicks(), 1L);
        // ★ 몸이 먼저, 궤적이 나중 (2026-07-22 사용자) — 선딜(trail_delay_ticks) 뒤에
        //   띠와 판정이 **함께** 나간다. 화면 = 판정 원칙은 지연 속에서도 유지된다.
    }

    /**
     * ★ <b>띠 = 판정</b> (2026-07-21 사용자 확정: "빗나감·명중을 없애고 파티클에 부딪치면 딜").
     *
     * <p>스윕 동안 띠의 실제 좌표를 표본해, 닿은 생명체에 {@code damage(원피해, player)} 를 넣는다.
     * 그 호출이 {@code EntityDamageByEntityEvent} → {@link #basicMelee} 로 <b>재진입</b>하므로
     * 태세·기 방어·입도 문지방·허수아비 계기 판정이 전부 기존 한 경로로 잰다 — 별도 판정 분기가 없다.
     * 바닐라 직접 클릭과의 이중타는 MC 무적 프레임(10틱)이 막고, 한 스윙 안의 중복은 struck 이 막는다.
     * 좌표 수식은 {@code QiGeometry.slashBand} 와 같은 시선 기준 베기면 — 화면과 판정이 같은 자리다.
     */
    private void kigiBandStrike(Player player, SkillEngine.KigiSlash cfg, int dirSign) {
        Location feet = player.getLocation();
        Vector flat0 = flatOf(player);
        Location center = feet.clone().add(flat0.clone().multiply(cfg.forward()));
        center.setY(feet.getY() + cfg.centerHeight());
        double f = Math.toRadians(player.getLocation().getYaw());
        double full = Math.toRadians(cfg.geomSweepDeg());
        double tilt = Math.toRadians(cfg.tiltDeg());
        double r = cfg.orbitRadius();
        double reach = Math.max(0.3, cfg.bandHitReach());
        int span = Math.max(1, cfg.bandSweepTicks());
        Set<UUID> struck = new HashSet<>();
        // ★ QiGeometry.slashBand 와 같은 기저 (화면 = 판정) — pitch 추종·부호 수정 포함 (2026-07-22)
        double pt = Math.toRadians(player.getLocation().getPitch());
        double cp = Math.cos(pt), sp = Math.sin(pt);
        double fwdX = -Math.sin(f) * cp, fwdY = -sp, fwdZ = Math.cos(f) * cp;
        double rgtX = -Math.cos(f), rgtZ = -Math.sin(f);
        double upX = -rgtZ * fwdY, upY = rgtZ * fwdX - rgtX * fwdZ, upZ = rgtX * fwdY;
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= span || !player.isOnline()) {
                    cancel();
                    return;
                }
                List<Location> pts = new ArrayList<>();
                for (int i = 0; i <= 6; i++) {
                    double phase = (t + i / 6.0) / span;
                    double phi = -full / 2.0 + full * phase;
                    double side = Math.sin(phi) * r * Math.cos(tilt);
                    double up = -Math.sin(phi) * r * Math.sin(tilt) * (dirSign < 0 ? -1.0 : 1.0);
                    double fwd = Math.cos(phi) * r;
                    pts.add(center.clone().add(
                            fwdX * fwd + rgtX * side + upX * up,
                            fwdY * fwd + upY * up,
                            fwdZ * fwd + rgtZ * side + upZ * up));
                }
                for (org.bukkit.entity.Entity e
                        : center.getWorld().getNearbyEntities(center, r + 1.5,
                        Math.max(2.5, r + 1.5), r + 1.5)) {   // 세로도 반경만큼 — 위아래 베기가 닿는다
                    if (!(e instanceof LivingEntity le) || e == player
                            || struck.contains(e.getUniqueId())) {
                        continue;
                    }
                    Location chest = le.getLocation().add(0, le.getHeight() * 0.5, 0);
                    for (Location p : pts) {
                        if (chest.distanceSquared(p) <= reach * reach) {
                            struck.add(e.getUniqueId());
                            var atk = player.getAttribute(org.bukkit.attribute.Attribute.ATTACK_DAMAGE);
                            double dmg = Math.max(1.0, atk == null ? 1.0 : atk.getValue());
                            le.damage(dmg, player);   // → basicMelee 로 재진입 (판정 일원화)
                            break;
                        }
                    }
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /**
     * 흰 별 반짝이 — 검기 아크를 표본해 {@code spark.particle}(end_rod)을 성기게 뿌린다.
     * {@code along_arc} 면 그리는 시간(draw_ticks)에 갈라 뿌려 별이 검기를 따라 흐르게 한다.
     */
    private void kigiSparks(Player player, SkillEngine.KigiSlash cfg, int dirSign) {
        SkillEngine.KigiSpark sp = cfg.spark();
        if (sp == null || sp.count() <= 0 || sp.particle() == null) {
            return;
        }
        Vector flat = flatOf(player);
        // 국소축을 세계축으로 편다 — 디스플레이의 국소 +X 는 시전자의 **왼쪽**이다 (yaw 0 = 남쪽 = +Z)
        Vector left = new Vector(flat.getZ(), 0, -flat.getX());
        Vector up = new Vector(0, 1, 0);
        Location feet = player.getLocation();
        Location base = feet.clone().add(flat.clone().multiply(cfg.forward()));
        base.setY(feet.getY() + cfg.centerHeight());
        // ★ 별도 **같은 공전 궤도**를 돈다 (SkillDisplay.kigiOrbit 과 같은 수식) — 3D 초승달이 몸 둘레를
        //   도는데 별만 정면 평면에 남으면 둘이 갈라진다. 별의 모습(입자·개수·퍼짐)은 그대로다
        double radius = cfg.orbitRadius();
        double half = Math.toRadians(cfg.sweepDeg() * 0.5);
        double tilt = Math.toRadians(cfg.tiltDeg());
        double sign = dirSign < 0 ? -1.0 : 1.0;
        int n = sp.count();
        for (int i = 0; i < n; i++) {
            double phase = n <= 1 ? 0.0 : (i / (double) (n - 1)) * 2.0 - 1.0;   // −1..+1
            double theta = half * phase * sign;                   // 공전각
            // (r sinθ, 0, r cosθ) 을 앞축 둘레로 tilt 만큼 눕힌 것 = Rz(tilt) 를 편 꼴
            double lx = radius * Math.sin(theta) * Math.cos(tilt);
            double ly = radius * Math.sin(theta) * Math.sin(tilt);
            double lz = radius * Math.cos(theta);
            Vector off = left.clone().multiply(lx)
                    .add(up.clone().multiply(ly))
                    .add(flat.clone().multiply(lz));
            Location at = base.clone().add(off);
            long due = tick + (sp.alongArc()
                    ? Math.round(cfg.drawTicks() * (n <= 1 ? 0.0 : i / (double) (n - 1)))
                    : 0L);
            if (due <= tick) {
                hud.emit(at, sp.particle(), 1, sp.spread(), sp.speed());
            } else {
                final Location fat = at;
                pending.add(new Pending(due,
                        () -> hud.emit(fat, sp.particle(), 1, sp.spread(), sp.speed())));
            }
        }
    }

    /**
     * 한 번의 손 — <b>3D 층 + 몸의 자세</b>. 무공이 있든 없든 여기를 지난다 (경로가 하나여야 거짓말이 없다).
     *
     * @return 3D 형체가 실제로 떴는가 (떴으면 궤적 파티클은 물러선다)
     */
    private boolean strike(Player player, String hitType, String grade, String weaponClass,
                           double range, double angle, int swingTicks) {
        return strike(player, hitType, grade, weaponClass, range, angle, swingTicks,
                nextStroke(player, weaponClass));
    }

    private boolean strike(Player player, String hitType, String grade, String weaponClass,
                           double range, double angle, int swingTicks, String strokeId) {
        boolean solid = display.slash(player, hitType, grade, weaponClass, range, angle,
                swingTicks, strokeId);
        if ("시".equals(hitType)) {
            // 던진 물건은 **실제로 날아간다** (암기) — 복제가 아니다. 손을 떠났으므로
            solid |= display.thrown(player, weaponClass, range);
        }
        posture(player, weaponClass, hitType, swingTicks);
        return solid;
    }

    // ══════════ ★ 획의 순번 — **그림의 리듬이지 입력의 문법이 아니다** ══════════

    /**
     * <b>【함정 ②를 지킨다】</b> 이것은 <b>콤보가 아니다.</b>
     *
     * <p>사용자가 청구한 것은 "1타 좌→우, 2타 우→좌"라는 <b>그림</b>이지 입력 문법이 아니다.
     * 우리는 외부 설계서의 <b>콤보 창(combo window)·입력 버퍼를 일부러 거부했다</b> — 우리 전투의 문법은
     * <b>"몸짓이 곧 선택"</b>(방패=막기 · 웅크림=흘리기 · 달림=회피)이고, 콤보 창은 그 삼문을 잡아먹는다.
     *
     * <p>그래서 여기 있는 것은 <b>숫자 하나</b>다: 연타하면 획의 <b>방향만</b> 번갈아 바뀐다.
     * 입력을 버퍼링하지 않고, 창을 열지 않고, 우클릭·웅크림·달림의 뜻을 <b>하나도 바꾸지 않는다</b>.
     * {@code reset_ticks} 동안 안 치면 1타로 돌아온다 — 그것이 전부다.
     */
    private final Map<UUID, long[]> strokeCycle = new HashMap<>();   // [순번, 마지막 틱]

    private String nextStroke(Player player, String weaponClass) {
        SkillEngine.SwingArcs arcs = engine.swingArcs();
        if (!arcs.enabled()) {
            return null;
        }
        long[] c = strokeCycle.computeIfAbsent(player.getUniqueId(),
                k -> new long[]{0, Long.MIN_VALUE / 2});
        if (tick - c[1] > arcs.resetTicks()) {
            c[0] = 0;   // 쉬었다 — 다음 손은 다시 1타다 (창이 아니라 **쉼**이 순번을 되돌린다)
        }
        c[1] = tick;
        String id = arcs.strokeAt(weaponClass, (int) c[0]);
        c[0]++;
        return id;
    }

    /** 획을 그리는 틱 — {@link SkillDisplay#slash} 와 <b>같은 산수</b> (두 층이 어긋나면 그림이 갈라진다) */
    private int drawTicks(String weaponClass, int swingTicks) {
        SkillEngine.Swing sw = engine.swing(weaponClass);
        SkillEngine.DisplayBudget b = engine.displayBudget();
        if (sw == null) {
            return Math.max(1, swingTicks);
        }
        return (int) Math.max(b.slashMinTicks(), Math.min(b.slashMaxTicks(),
                Math.round(swingTicks * sw.spanRatio())));
    }

    /** 시선의 수평 성분 — 베는 것은 땅 위의 일이다 */
    private static Vector flatOf(Player player) {
        Vector v = player.getLocation().getDirection().setY(0);
        return v.lengthSquared() < 1.0e-6 ? new Vector(1, 0, 0) : v.normalize();
    }

    /**
     * ★ <b>궤적이 시간을 두고 훑는다</b> — 한 틱에 다 뿌리면 그것은 스윙이 아니라 <b>펑 하고 뜬 부채</b>다.
     *
     * <p><b>【이것이 옛 병의 절반이었다】</b> 예전 호(弧) 궤적은 일곱 점을 <b>같은 틱에</b> 뿌렸다 —
     * 눈은 "쓸고 지나간 것"이 아니라 "앞에 나타난 것"을 본다. 이제 점들이 <b>획과 같은 길을, 획과 같은
     * 시간에</b> 지나간다 ({@link SkillDisplay#sweepPath} — 두 층이 <b>같은 등록부</b>를 읽는다).
     *
     * <p><b>【예산】 늘지 않는다.</b> 점 수도 점당 개수도 그대로다 — <b>시간에 갈라 뿌릴 뿐</b>이다.
     * 오히려 한 지점·한 틱 부하는 <b>줄어든다</b> (7점 동시 → 1점씩 차례로).
     */
    private void sweepArc(Player player, String strokeId, String particle, int per, int points,
                          double radius, double angle, int drawTicks) {
        List<Vector> path = display.sweepPath(strokeId, flatOf(player), radius, angle, points);
        Location feet = player.getLocation();
        for (int i = 0; i < path.size(); i++) {
            Vector v = path.get(i);
            Location at = feet.clone().add(v.getX(), 0.0, v.getZ());
            at.setY(feet.getY() + v.getY());
            long due = tick + Math.round(drawTicks
                    * (points <= 1 ? 0.0 : i / (double) (points - 1)));
            if (due <= tick) {
                hud.emit(at, particle, per, 0.05, 0.0);
            } else {
                pending.add(new Pending(due, () -> hud.emit(at, particle, per, 0.05, 0.0)));
            }
        }
    }

    /**
     * ★ <b>무공 없는 손의 궤적</b> — <b>여기에 아무것도 없었다.</b>
     *
     * <p><b>【진단 · 사용자가 본 것】</b> {@link #basicSwing} 은 {@link #strike} 만 불렀다 —
     * 3D 획과 몸뿐이다. <b>궤적 파티클도, 스윙음도 없었다</b> (그 둘은 무공 시전 경로 {@link #trail} 에만
     * 있었다). 그런데 참격선 모델은 {@code fallback: null} — <b>팩을 못 받은 눈에는 3D 도 안 뜬다.</b>
     * ⇒ 팩 없는 사람의 일반 공격에 서버가 얹은 것은 <b>전진(lunge)과 팔 휘두름뿐</b>이었다.
     * 앞으로 미는 힘이 모션의 전부인 것 — 사람은 그것을 <b>찌르기</b>라고 부른다. 사용자가 옳았다.
     */
    private void basicTrail(Player player, SkillEngine.Basic basic, String grade,
                            String weaponClass, String strokeId, int swingTicks, boolean solid) {
        SkillEngine.Traj traj = engine.trajectory(basic.trail());
        SkillEngine.Style style = engine.weaponStyle(weaponClass);
        SkillEngine.GradeMotion g = engine.motionGrade(grade);
        if (traj == null || style == null || g == null) {
            return;
        }
        // 스윙음 — **기본 타격에는 소리가 없었다** (계열의 소리는 등록부에 있는데 아무도 안 불렀다)
        sfx(player.getLocation(), style.swing());

        boolean manifested = !SkillEngine.BARE.equals(grade);
        boolean thrust = "선".equals(basic.trail()) || "시".equals(basic.trail());
        String particle = manifested ? g.trailParticle() : thrust ? style.thrust() : style.arc();
        int per = solid ? engine.displayBlend().damp(1) : 1;
        SkillEngine.Budget b = engine.motionBudget();

        // 잔상(echo)·먹번짐(haze) — 획이 **떠난 자리**. 궤적 풀에서 뽑는다 (밖에서 뽑으면 예산이 아니다)
        int spent = 0;
        Vector flat = flatOf(player);
        if (!"aura".equals(traj.shape())) {
            Location wake = strokeWake(player, flat);
            hud.emit(wake, g.echo(), false);
            hud.emit(wake, g.haze(), false);
            spent = g.echo().count() + g.haze().count();
        }
        int points = Math.min(traj.points(),
                Math.max(1, Math.max(0, b.trailPool() - spent) / Math.max(1, per)));
        int draw = drawTicks(weaponClass, swingTicks);

        if ("arc".equals(traj.shape())) {
            sweepArc(player, strokeId, particle, per, points,
                    basic.range() * traj.radiusRatio(), basic.angle(), draw);
        } else if (thrust) {
            Vector dir = player.getLocation().getDirection().normalize();
            Location eye = player.getEyeLocation();
            for (int i = 1; i <= points; i++) {
                hud.emit(eye.clone().add(dir.clone().multiply(
                        Math.min(basic.range(), i * traj.step()))), particle, per, 0.05, 0.0);
            }
        }
    }

    // ══════════ 몸의 자세(體勢) — 획을 그리는 몸 ══════════

    /**
     * <b>못 하는 것</b>: 바닐라 클라이언트에서 플레이어의 <b>팔다리 각도는 서버가 줄 수 없다</b>
     * (애니메이션은 클라이언트가 돌리고, 팩으로 플레이어 지오메트리를 바꿀 수 없다). 무릎·팔꿈치를 꺾는
     * 골격 애니메이션은 바닐라 프로토콜에 자리가 없다.
     *
     * <p><b>하는 것 — 다섯 축</b>: 전진(lunge — <b>창의 5m 는 몸이 나가야 5m 다</b>) · 자세(pose —
     * SWIMMING 은 눕히고 · SNEAKING 은 낮추고 · SPIN_ATTACK 은 돈다) · <b>몸의 회전</b>(yaw·pitch —
     * 허리가 돌고 상체가 젖혀진다) · <b>팔의 휘두름</b>(swingMainHand/OffHand — 서버가 줄 수 있는 유일한
     * 사지 애니메이션) · 정지(프레임이 이미 발을 묶는다).
     *
     * <p><b>한 칸이 아니라 한 줄기다</b>: 획이 그려지는 동안 몸이 {@code script} 의 beat 를 따라 흐른다
     * (어깨가 뒤에 있다 → 허리가 돌며 벤다 → 흘린다 → 돌아온다). 예전엔 자세가 한 번 찍히고 끝나서
     * <b>획만 춤추고 몸은 서 있었다.</b>
     */
    private static final class Posture {
        int epoch;      // 새 수가 들어오면 옛 beat 는 죽는다 (겹쳐도 몸이 꼬이지 않게)
        int poseSeq;    // 자세 강제의 세대 — max_pose_ticks 를 넘기면 스스로 풀린다
        float yaw;      // 원점 대비 **실제로 먹인** 각 — 되돌릴 때 이만큼만 뺀다 (순증 금지)
        float pitch;
    }

    /**
     * 자세의 줄기를 태운다 — {@code swingTicks} 동안 몸이 beat 를 지난다.
     * 회전은 <b>반드시 원점으로 돌아온다</b> (마지막 beat 가 (0,0) 이고, 그 뒤 못질(restore)이 한 번 더 있다).
     */
    private void posture(Player player, String weaponClass, String hitType, int swingTicks) {
        SkillEngine.Body body = engine.body(weaponClass, hitType);
        if (body == null || skipBody(player)) {
            return;
        }
        Posture st = postures.computeIfAbsent(player.getUniqueId(), k -> new Posture());
        final int epoch = ++st.epoch;
        if (!body.scripted()) {
            // 옛 한 칸 (script 없는 계열) — 있던 것을 잃지 않는다
            applyBeat(player, st, epoch, new SkillEngine.Beat(0.0, body.lunge(),
                    body.hasPose() ? body.pose() : null, body.yawKick(), body.pitchKick(), null));
            pending.add(new Pending(tick + Math.max(1, swingTicks), () -> restore(player, st, epoch)));
            return;
        }
        int span = Math.max(1, swingTicks);
        for (SkillEngine.Beat b : body.script()) {
            long due = tick + Math.round(b.at() * span);
            if (due <= tick) {
                applyBeat(player, st, epoch, b);   // 사출/베기의 순간 — 판정과 **같은 틱**이다
            } else {
                pending.add(new Pending(due, () -> applyBeat(player, st, epoch, b)));
            }
        }
        // 못 — 어떤 경우에도 몸은 돌아온다 (beat 가 잘렸든 겹쳤든 · 회전은 순증하지 않는다)
        pending.add(new Pending(tick + span + 1, () -> restore(player, st, epoch)));
    }

    /**
     * 예비 동작(蓄勢) — <b>선딜이 있는 초식에만</b>. 몸을 빼고, 어깨를 뒤로 틀고, 팔을 젖힌다.
     *
     * <p>기본 타격(basic_strike)은 클릭과 같은 틱에 판정이 끝나므로 <b>예비가 없다</b>.
     * 있는 척하면 "암기가 날아간 뒤에 몸이 뒤로 젖혀지는" 거짓말이 된다 — 그래서 안 넣었다.
     * (대신 암기의 끝자세가 다음 던짐의 예비가 된다 — {@code script} 의 0.60 칸.)
     */
    private void windup(Player player, String weaponClass, String hitType, int startupTicks) {
        if (startupTicks < 2) {
            return;
        }
        SkillEngine.Body body = engine.body(weaponClass, hitType);
        if (body == null || body.windup() == null || skipBody(player)) {
            return;
        }
        Posture st = postures.computeIfAbsent(player.getUniqueId(), k -> new Posture());
        final int epoch = ++st.epoch;
        applyBeat(player, st, epoch, body.windup());
        // 판정이 오지 않아도(취소·사망) 몸은 돌아온다. 판정이 오면 epoch 가 바뀌어 이 못은 스스로 물러선다
        pending.add(new Pending(tick + startupTicks + 2L, () -> restore(player, st, epoch)));
    }

    /** 물속·활강·탈것 — 바닐라 자세가 이긴다 (싸우면 몸이 굳고, 굳으면 그것은 버그로 읽힌다) */
    private static boolean skipBody(Player player) {
        return player.isSwimming() || player.isGliding() || player.isInsideVehicle();
    }

    private void applyBeat(Player player, Posture st, int epoch, SkillEngine.Beat b) {
        if (!player.isOnline() || st.epoch != epoch) {
            return;   // 다음 수가 이미 들어왔다 — 이 몸은 그 수의 것이다
        }
        if (skipBody(player)) {
            restore(player, st, epoch);   // 물에 들어갔다 — 돌려주고 손을 뗀다
            return;
        }
        SkillEngine.BodyLimits limits = engine.bodyLimits();
        // ★ 전진 배율 — 인게임에서 민다 (/혼천 스윙 전진 0.5). 0 이면 전진이 통째로 죽는다 (눈의 시험용)
        double lunge = b.lunge() * display.tuning().lunge();
        if (lunge != 0.0 && (!limits.requireGround() || player.isOnGround())) {
            Vector push = player.getLocation().getDirection().setY(0);
            if (push.lengthSquared() > 1.0e-6) {
                // 체중이 실린다 — 창은 나가고(0.42), 검은 **되돌아온다**(−0.03). 상한은 등록부가 물렸다
                player.setVelocity(player.getVelocity().add(push.normalize().multiply(lunge)));
            }
        }
        if (b.setsPose()) {
            pose(player, st, epoch, b);
        }
        turn(player, st, b.yaw(), b.pitch());
        if (b.hand() != null) {
            // **서버가 줄 수 있는 유일한 사지 애니메이션** — 권갑의 원-투, 암기의 사출, 단검의 되받아치기
            if ("부".equals(b.hand())) {
                player.swingOffHand();
            } else {
                player.swingMainHand();
            }
        }
    }

    private void pose(Player player, Posture st, int epoch, SkillEngine.Beat b) {
        if (b.clearsPose()) {
            clearPose(player);
            st.poseSeq++;
            return;
        }
        try {
            player.setPose(org.bukkit.entity.Pose.valueOf(b.pose()), true);
        } catch (IllegalArgumentException e) {
            return;   // 등록부가 모르는 자세를 적었다 — 조용히 지나간다 (연출이 판정을 멈추지 않는다)
        }
        final int seq = ++st.poseSeq;
        // 【못】 자세 강제는 max_pose_ticks 를 넘지 못한다 — 스윙이 그보다 길어도(부 22틱) 몸은 먼저 풀린다
        pending.add(new Pending(tick + engine.bodyLimits().maxPoseTicks(), () -> {
            if (st.poseSeq == seq && st.epoch == epoch) {
                clearPose(player);
            }
        }));
    }

    /**
     * 몸을 튼다 — <b>원점 기준 목표각</b>을 받아 <b>차분만</b> 더한다.
     * 그 사이 플레이어가 마우스로 돌린 것은 그대로 보존되고, 마지막 칸(0,0)에서 <b>원점으로</b> 돌아온다
     * (반동이지 조준 훼손이 아니다).
     *
     * <p><b>【눈이 거짓말한 자리】</b> 처음엔 "차분만 더하면 언제나 정확히 되돌아온다"고 적었다.
     * <b>거짓이었다.</b> pitch 는 ±90 에서 잘린다(clamp). 하늘/발밑을 보고 있으면 우리가 먹인 각이
     * 벽에 잘리고, 되돌릴 때는 안 잘려서 <b>조준이 그만큼 어긋난 채로 남는다</b> (시뮬레이션에서 최대 4도).
     * 벽이 있으면 덧셈은 교환법칙을 잃는다.
     *
     * <p>그래서 <b>벽에서 손을 뗀다</b>: 제 시선이 안전대(±{@value #PITCH_SAFE}도) 밖이면 pitch 를
     * <b>키우지 않는다</b> (줄이는 것 — 되돌리는 것 — 은 언제나 허용된다). 안전대 안에서는
     * 65 + 12 = 77 &lt; 90 이라 <b>벽에 닿지 않으므로</b> 먹인 만큼 정확히 돌아온다.
     * yaw 는 벽이 없다 (360도로 감긴다) — 언제나 정확하다.
     */
    private static final float PITCH_SAFE = 65.0f;

    private void turn(Player player, Posture st, float yaw, float pitch) {
        if (!player.isOnline() || !engine.bodyLimits().yawKickEnabled()) {
            return;   // 회전 축만 통째로 꺼졌다 — 전진·자세·팔은 그대로 돈다
        }
        Location at = player.getLocation();
        if (Math.abs(at.getPitch()) >= 89.9f) {
            st.pitch = 0.0f;   // 벽이 먹었다 — **돌려주지 않는다**. 돌려주면 제가 겨눈 발밑에서 끌어내리는 셈이다
        }
        float mine = at.getPitch() - st.pitch;   // 우리 것을 뺀, **플레이어 제 시선**
        if (Math.abs(mine) > PITCH_SAFE) {
            pitch = 0.0f;   // 하늘/발밑으로 가고 있다 — **손을 뗀다** (벽에 잘리면 못 돌려주므로)
        }
        float dYaw = yaw - st.yaw;
        float dPitch = pitch - st.pitch;
        if (Math.abs(dYaw) < 0.01f && Math.abs(dPitch) < 0.01f) {
            return;
        }
        float wanted = Math.max(-90.0f, Math.min(90.0f, at.getPitch() + dPitch));
        player.setRotation(at.getYaw() + dYaw, wanted);
        st.yaw = yaw;
        st.pitch += wanted - at.getPitch();   // **실제로 먹은 만큼만** 기록 (그래도 벽에 잘렸으면 그만큼만)
    }

    private void clearPose(Player player) {
        if (player.isOnline() && player.hasFixedPose()) {
            player.setPose(org.bukkit.entity.Pose.STANDING, false);   // 몸을 돌려준다 (굳지 않게)
        }
    }

    /** 몸을 원점으로 — 자세를 풀고 회전을 되돌린다. 이것이 마지막 못이다 */
    private void restore(Player player, Posture st, int epoch) {
        if (st.epoch != epoch) {
            return;
        }
        clearPose(player);
        turn(player, st, 0.0f, 0.0f);
    }

    /**
     * 절기 한 타를 <b>정본 파이프라인</b>으로 흘려보낸다 (commit → resolve).
     *
     * <p>무공 카탈로그 담당의 청구서: 지금 절기는 출처 없는 {@code damage(double)} 로 근사되어
     * <b>기 방어·무기 격돌·사냥 적립을 못 탄다</b>. 이 문을 통과하면 전부 탄다 (한 대는 한 대다).
     *
     * @param step    콤보 칸 (0-기반). 단발형은 0
     * @param primary 주 대상 (없으면 히트박스가 찾는다)
     * @return 시전됐는가 (자세가 안 돌아왔거나 쿨다운이면 false)
     */
    public boolean castArt(Player player, String skillId, int step, LivingEntity primary) {
        if (skillId == null || !engine.hasActionData(skillId)) {
            return false;
        }
        SkillEngine.State state = state(player);
        if (tick < state.busyUntil || state.onCooldown(skillId, tick)
                || tick - state.lastCastTick < engine.duplicateWindowTicks()) {
            return false;
        }
        String weaponClass = engine.weaponClassOf(
                player.getInventory().getItemInMainHand(), materialName(player));
        SkillEngine.Cast cast = engine.planCombo(
                skillId, step, state.realm, state.energy, offense(state), weaponClass);
        state.busyUntil = tick + Math.max(cast.frames().total(), swingInterval(player));
        state.lastCastTick = tick;
        state.energy -= cast.paid();
        applyCooldown(player, state, skillId, cast);
        commit(player, state, cast, primary, engine.skillName(skillId), step);
        return true;
    }

    /**
     * 쿨다운 — <b>【고침】 한 번도 적용된 적이 없던 규칙</b>.
     * {@code planCombo} 가 {@code cooldown_ticks} 를 읽지 않아 {@code Cast.cooldownTicks()} 가 늘 0 이었다.
     * 태조장권 60 · 매화검법 90 · 흑살도법 180 이 등록부에만 있었다.
     */
    private void applyCooldown(Player player, SkillEngine.State state, String skillId,
                               SkillEngine.Cast cast) {
        int cd = cast.cooldownTicks();
        if (cd <= 0) {
            return;   // 콤보의 1·2타는 쿨다운이 없다 — 연타가 권법의 값이다 (마무리 타에만 붙는다)
        }
        state.cooldownUntil.put(skillId, tick + cd);
        state.comboIndex = 0;             // 마무리를 냈다 — 콤보는 처음으로 돌아간다
        itemCooldown(player, cd);         // 바닐라 스와이프 = 쿨다운 (mc_action_mapping 2장)
    }

    private void swing(Player player, String skillId, LivingEntity primary) {
        SkillEngine.State state = state(player);
        if (tick - state.lastCastTick < engine.duplicateWindowTicks()) {
            return;   // F-R1 — 같은 틱 중복 시전 폐기
        }
        if (tick < state.busyUntil) {
            flash(player, ChatColor.DARK_GRAY + "아직 자세가 돌아오지 않았다");
            return;   // 경직·후딜 — 연타 방지
        }
        if (state.onCooldown(skillId, tick)) {
            // 무공이 쉬는 동안에도 손은 움직인다 — 기본 초식으로 친다 (획은 뜬다. 무공만 안 나갈 뿐)
            basicSwing(player);
            return;
        }
        if (tick > state.comboDeadline) {
            state.comboIndex = 0;   // 입력 유예창을 놓쳤다 — 처음부터
        }

        String weaponClass = engine.weaponClassOf(player.getInventory().getItemInMainHand(), materialName(player));
        SkillEngine.Cast cast = engine.planCombo(
                skillId, state.comboIndex, state.realm, state.energy, offense(state), weaponClass);

        int shown = state.comboIndex + 1;
        int size = engine.comboSize(skillId);
        state.comboIndex = (state.comboIndex + 1) % size;
        state.comboDeadline = tick + cast.frames().total() + engine.comboWindow(skillId);
        applyCooldown(player, state, skillId, cast);
        // 공속이 거짓말하지 않게 — 무공이 실리면 바닐라 피해가 취소되고 프레임이 스윙 간격을 정한다.
        // 계열 공속(부 0.9/s = 22틱)이 프레임(9틱)보다 느리면 "가장 느린 병기"가 연출로만 남는다.
        state.busyUntil = tick + Math.max(cast.frames().total(), swingInterval(player));
        state.lastCastTick = tick;
        state.energy -= cast.paid();

        commit(player, state, cast, primary, shown + "타", shown - 1);
    }

    // ─── 발출 (쏨) ───

    private void shoot(Player player, SkillEngine.State state) {
        if (tick < state.busyUntil) {
            flash(player, ChatColor.DARK_GRAY + "아직 자세가 돌아오지 않았다");
            return;
        }
        if (state.onCooldown(CD_SHOT, tick)) {
            return;
        }
        String weaponClass = engine.weaponClassOf(player.getInventory().getItemInMainHand(), materialName(player));
        SkillEngine.Cast cast = engine.planShot(offense(state), state.realm, state.energy, weaponClass);
        if (cast.downcast() || engine.gradeRank(cast.grade()) < 2) {
            // 발출만은 다운캐스트가 없다 — 프레임이 아니라 기 그 자체가 본체다 (skill_motion.md 4장)
            flash(player, ChatColor.RED + "기가 흩어진다 — 쏠 것이 없다");
            event(handLocation(player), "발출_불발");
            state.cooldownUntil.put(CD_SHOT, tick + 10L);
            return;
        }
        // 공속이 거짓말하지 않게 — 무공이 실리면 바닐라 피해가 취소되고 프레임이 스윙 간격을 정한다.
        // 계열 공속(부 0.9/s = 22틱)이 프레임(9틱)보다 느리면 "가장 느린 병기"가 연출로만 남는다.
        state.busyUntil = tick + Math.max(cast.frames().total(), swingInterval(player));
        state.lastCastTick = tick;
        state.energy -= cast.paid();
        state.cooldownUntil.put(CD_SHOT, tick + cast.cooldownTicks());
        itemCooldown(player, cast.cooldownTicks());

        commit(player, state, cast, null, "발출", 0);
    }

    /** 선딜 텔레그래프 → 지속 프레임에 판정 → 후딜. 프레임은 다운캐스트해도 남는다 */
    private void commit(Player player, SkillEngine.State state, SkillEngine.Cast cast,
                        LivingEntity primary, String label, int stepIndex) {
        if (cast.gated()) {
            flash(player, ChatColor.GRAY + "그 격은 아직 이 몸의 것이 아니다 ("
                    + engine.gradeGate(offense(state) == null ? "발경" : offense(state)) + "부터)");
        }
        if (cast.downcast()) {
            // 【빈약함이 곧 정보다】 격이 외공기로 떨어지면 연출도 회색으로 떨어진다 — 상대가 그것을 읽는다
            flash(player, ChatColor.DARK_GRAY + "기가 실리지 않는다 — 맨 기술");
            event(handLocation(player), "다운캐스트");
        }

        SkillEngine.Frames f = cast.frames();
        // 시전 중 이동 제약 — 무거운 수일수록 발이 묶인다 (프레임 총량이 곧 자세의 무게다)
        if (f.total() >= 8) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                    Math.max(1, f.total()), f.total() >= 16 ? 2 : 1, false, false, false));
        }
        // 텔레그래프 — 응집은 빛으로 보인다. 등록부가 개수·주기·소리·예산을 전부 정한다.
        // 텔레그래프 가중(telegraph_boost)은 초식이 정한다: 제왕검형·만천화우는 선딜이 곧 예고다
        SkillEngine.SkillMotion motion = engine.motionSkill(cast.skillId());
        SkillEngine.Step step = motion == null ? null : motion.step(stepIndex);
        int boost = step == null ? 0 : step.telegraphBoost();
        if (cast.manifested() || boost > 0) {
            // 응집음도 초식의 배율을 탄다 — '조용한 초식'은 예고부터 조용하다
            scheduleTelegraph(() -> handLocation(player), cast.grade(), f.startup(), boost,
                    soundScale(step));
        }
        player.swingMainHand();
        // 축세(蓄勢) — 선딜이 있는 초식은 **몸이 먼저 벼른다** (몸을 빼고, 어깨를 뒤로 틀고, 팔을 젖힌다).
        // 암기의 "뒤로 젖혔다 던진다"가 온전히 서는 자리다 (기본 타격은 즉발이라 예비가 없다)
        windup(player, engine.weaponClassOf(player.getInventory().getItemInMainHand(),
                materialName(player)), cast.hitType(), f.startup());
        pending.add(new Pending(tick + f.startup(),
                () -> resolve(player, state, cast, primary, label, stepIndex)));
    }

    // ══════════ 판정 · 적용 ══════════

    private void resolve(Player player, SkillEngine.State state, SkillEngine.Cast cast,
                         LivingEntity primary, String label, int stepIndex) {
        if (!player.isOnline()) {
            return;
        }
        SkillEngine.Ultimate art = cast.ultimate();
        if (art != null && art.isCounter()) {
            // 반격 오의 — 벨 것을 찾지 않는다. 상대가 오기를 기다린다 (지속 창 = window_ticks)
            state.counterUntil = tick + cast.frames().active() + art.counterWindow();
            flash(player, ChatColor.LIGHT_PURPLE + art.name()
                    + ChatColor.WHITE + " — 기다린다 (" + art.counterWindow() + "틱)");
            return;
        }
        // ① 기하 — 히트박스 안에 든 몸 (아직 '표적'이 아니다. 그저 **자리에 있었을 뿐**이다)
        List<LivingEntity> caught = switch (cast.hitType()) {
            case "선" -> lineTargets(player, cast);
            case "원" -> circleTargets(player, cast);
            default -> arcTargets(player, cast, primary);
        };
        // ② 손이 가려낸다 — 아군인가 · 벽이 막았는가 · 손이 모자라는가. **왜 안 맞았는지가 남는다**
        List<String[]> vetoes = new ArrayList<>();
        List<LivingEntity> targets = admit(player, cast, primary, caught, vetoes);

        boolean eye = eyes.contains(player.getUniqueId());   // 【디버그】 꺼져 있으면 여기서 끝 (비용 0)
        if (eye) {
            eyeHitbox(player, cast, targets, vetoes);
        }
        int swings = art == null ? 1 : Math.max(1, art.multiHit());

        Location origin = swingLocation(player, cast);
        // 궤적 — 판정과 같은 틱에 같은 모양을 그린다 (보이는 것 = 맞는 것).
        // 헛쳐도 그린다: 상대가 '지나간 자리'를 보고 다음 수를 읽을 수 있어야 공방이 성립한다
        String weaponClass = engine.weaponClassOf(player.getInventory().getItemInMainHand(),
                materialName(player));
        if (art == null) {
            trail(player, cast, weaponClass, stepIndex);
        }
        int shown = Math.max(1, targets.size()) * swings;   // 타격 풀을 나눌 몫 (광역이 예산을 터뜨리지 않게)
        int hits = 0;
        for (LivingEntity target : targets) {
            for (int swing = 0; swing < swings; swing++) {
                if (!target.isValid()) {
                    break;
                }
                // 실행력 = 능력치 + 무공 숙련 + 무기 보정 + 경지 격차(gm_modifiers realm_gap) + 상태 보정
                // ★ 능력치 항이 오래 빠져 있었다 — 그래서 **같은 경지의 두 사람이 완전히 같은 사람**이었다.
                //   무엇이 실리는지는 **병기가 정한다** (도=근력·검=민첩·암기=감각, combat.yml attacker_attribute).
                //   한 과목이 공격과 방어를 함께 사면 그 과목이 지배 전략이 된다 — 그것이 우리가 버린 MMO 문법이다.
                boolean hostile = target instanceof Monster;
                PlayerLedger led = plugin.ledger(player.getUniqueId());
                int mastery = led.levelOf(engine.skillName(cast.skillId()), plugin.progression());
                Growth growth = Growth.get();
                // ★ 시트가 없는 몸은 **그 경지의 표준 무인**이다 — 갓 접속한 자가 능력치 0.0 이라
                //   +0 으로 싸우는데 같은 경지의 산적은 realmAttr 로 표준치를 받고 있었다.
                //   (Vitality.cheOf 가 이미 같은 답을 갖고 있다 — 원장이 비면 경지 대체값)
                int attrBonus = growth == null ? 0 : growth.attackBonus(led,
                        engine.weaponClassOf(player.getInventory().getItemInMainHand(), null),
                        engine.realmAttr(state.realm));
                int execBase = attrBonus + mastery + engine.weaponJudgmentBonus(weaponGrade(player))
                        + engine.realmGapBonus(state.realm, foeRealm(target, hostile))
                        + (engine.isDepleted(state.energy) ? -2 : 0);   // 내공 고갈 = 판정 -2

                // ★ 【대칭】 상대도 태세를 세운다 — 저항치가 고정 난이도(보통 12)가 아니라
                //   **그 몸이 고른 방어**다. 그전엔 상대가 무엇을 하든 언제나 12 였다
                //   (등록부는 회피·막기·흘리기를 적어 뒀는데, 엔진의 NPC 는 서 있기만 했다).
                //   저항 = 태세 판정치 + 7 (NPC 는 굴리지 않는다 — combat.yml defender_bonus).
                Guardline foeLine = defenderStance(target);
                int resist = foeLine != null ? foeLine.score() + NPC_JUDGMENT
                        : engine.difficulty(hostile ? "보통" : "쉬움");
                int roll = roll2d6();   // 전투는 주사위를 쓴다

                SkillEngine.Strike strike = engine.strike(cast, execBase, roll, resist);
                if (eye) {
                    // 【판정의 눈】 2d6 이 무엇을 굴렸고, 실행력이 무엇으로 이루어졌고, 저항이 어디서 왔는가
                    eyeRoll(player, target, attrBonus, mastery, execBase, roll, resist,
                            foeLine, strike);
                }
                if (foeLine != null && target instanceof Player prey
                        && eyes.contains(prey.getUniqueId())) {
                    // 【판정의 눈 · 맞는 쪽】 내 태세(선언 포함)가 초식을 무엇으로 받았는가 (B-105)
                    eyeStance(prey, player, foeLine, execBase + roll, NPC_JUDGMENT, strike.margin());
                }
                touchCombat(state);
                if (engine.isFlowTier(strike.tierId())) {
                    gainFlow(player, state);   // 발동권 — 읽어낸 순간이 쌓인다 (오의 충전의 실체)
                }
                if (!strike.hit()) {
                    if (foeLine != null) {
                        stanceFx(target, foeLine.stance());   // 상대가 무엇으로 살아났는지 보인다
                        stanceWon(target, foeLine.stance());  // 초식을 받아 낸 것도 방어다 — 虛 가 열린다
                    }
                    continue;
                }
                // 상대의 경감 — 태세는 맞아도 값을 한다 (막기 −3 · 흘리기 −1). 갑옷은 그 뒤에 든다
                double raw = strike.damage();
                int stanceSoak = foeLine == null ? 0 : foeLine.soak();
                if (foeLine != null) {
                    raw -= foeLine.soak();
                    if (foeLine.clashes()) {
                        clashWeapon(target, cast.grade());   // 막기 — 그 몸의 무기가 내 격을 먹는다
                    }
                }
                int armor = armorSoak(target, cast.grade());
                raw -= armor;
                // 상대의 기 방어·무기가 같은 규칙으로 판정된다 (대칭)
                Defense defense = defend(target, player, cast.grade(), Math.max(0.0, raw));
                if (eye) {
                    // 【판정의 눈】 피해가 **어느 층에서** 깎였는가 (태세 → 갑옷 → 기 방어)
                    eyeDamage(player, strike.damage(), stanceSoak, armor, defense);
                }
                if (defense.blocked() || defense.damage() <= 0.0) {
                    continue;   // 기 방어·태세·갑옷이 먹었다 — 검이 닿지 않았으니 격돌도 없다
                }
                if (foeLine == null) {
                    clashWeapon(target, cast.grade());   // 태세 층이 없던 시절의 경로 (원칙 3)
                }
                hits++;
                applying = true;
                try {
                    target.damage(defense.damage(), player);
                } finally {
                    applying = false;
                }
                stagger(target, player, cast);
                // 【타격의 순간】 멈춤 · 밀림 · 흔들림 — 맞는 쪽의 몸이 말한다 (combat.yml impact)
                strikeLanded(player, target, cast.grade(), defense.damage(), cast.frames());
                // 타격음은 **격**의 것이다 — 초식이 그것을 줄이려면 등록부의 sound_scale 한 칸이 필요하다
                impact(target.getLocation().add(0, 1, 0), cast.grade(), strike, shown,
                        soundScale(stepOf(cast, stepIndex)));
                if (art != null) {
                    ultimateEffect(player, state, art, target, defense.damage());
                }
            }
        }

        // 세계 다리 — **격은 목격될 때 비로소 강호의 일이 된다** ("그자가 검기를 뿜었다더라").
        // 아무도 없는 산속에서 강기를 터뜨린 것은 소문이 아니다. 본 사람이 있어야 이야기가 된다.
        if (hits > 0 && cast.manifested() && !Dojang.suppressWorldEvents(player.getWorld())) {
            Location me = player.getLocation();
            int seen = (int) me.getWorld().getNearbyEntities(me, 24, 12, 24).stream()
                    .filter(e -> (e instanceof Player p && !p.equals(player))
                            || e instanceof org.bukkit.entity.Villager).count();
            Location market = plugin.anchor("장터");
            String where = market != null && market.getWorld() == me.getWorld()
                    && market.distance(me) <= 60 ? "장터_광장" : "산길_어귀";
            WorldBridge.qiManifested(cast.grade(), where, seen, player.getUniqueId(), player.getName());
        }

        if (hits == 0) {
            event(origin, "헛손질");   // 마른 붓 자국 — 빗나갔다는 것도 정보다
        }
        strain(player, state, cast);

        flash(player, hud.gradeColor(cast.grade()) + label + " "
                + ChatColor.DARK_GRAY + "│ " + SkillHud.gradeLabel(cast.grade())
                + (hits > 0 ? ChatColor.WHITE + " · " + hits + "타" : ChatColor.GRAY + " · 헛손질"));
        energyBossBar.update(player, state);
    }

    /** 상대의 경지 — 등록부의 몸이면 그 경지로 격차를 잰다 (몹 = 삼류 취급은 미등록 몸의 폴백) */
    private String foeRealm(LivingEntity target, boolean hostile) {
        SkillEngine.Npc npc = npcOf(target);
        if (npc != null) {
            return npc.realm();
        }
        if (target instanceof Player other) {
            return state(other).realm;
        }
        return hostile ? "삼류" : "범인";
    }

    /** 흐름 — 아슬아슬한 성공 이상 공방 n회 누적. 차는 순간을 연출로 고지한다 (mc_action_mapping) */
    private void gainFlow(Player player, SkillEngine.State state) {
        if (engine.ultimateStage(state.realm) == null || state.flow >= engine.flowRequired()) {
            return;
        }
        state.flow++;
        if (state.flow >= engine.flowRequired()) {
            event(player.getLocation().add(0, 1, 0), "흐름_충전");
            flash(player, ChatColor.LIGHT_PURPLE + "흐름을 읽었다 — 오의 (F)");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  방어 태세 삼문(三門) — 【맞는 쪽의 선택】
    //
    //  combat.yml 은 방어 셋을 **완전히 정의해 두고** 있었다 (회피·막기·흘리기). 엔진에는 하나도 없었다 —
    //  있는 것은 호신강기(내력으로 막는 것)뿐이었고, 그래서 **수련의 절반이 살 곳이 없었다**:
    //  근력·감각·민첩에 구간을 부어도 **맞을 때 아무 일도 일어나지 않았다.**
    //
    //  ★ 누가 고르는가 — **둘 다.** 마인크래프트는 턴제가 아니라 맞는 순간에 메뉴를 못 띄운다.
    //    ① 몸짓이 먼저다 — 바닐라가 **이미 가진 세 자세**를 읽는다:
    //         손을 세우면(isBlocking) 받아 내는 것 · 몸을 낮추면(isSneaking) 흘리는 것 ·
    //         발이 이미 움직이면(isSprinting) 빼는 것.
    //       새 입력 채널을 열지 않았다. 그래서 **남의 눈에도 보인다** — 상대가 내 자세를 읽고 수를 고른다.
    //    ② 몸에 밴 태세 — `/혼천 태세`. 무인은 제 자세가 있다.
    //    ③ 자동 — 몸이 아는 대로 (Growth.bestStance). **기본값이 이것이다.**
    //       그래서 아무것도 안 배운 삼류도 **제 목숨을 본다** (게이트 없음 — 이 프로젝트가 한 번 데인 죄).
    //
    //  ★ 쿨다운이 없다. 태세를 막는 것은 **내력·자세·상황**이다:
    //    갑옷이 회피를 팔고 · 막기가 무기를 태우고 · 포위가 회피를 지운다.
    // ══════════════════════════════════════════════════════════════════════════

    /** 이 몸이 이 합에 서는 태세 — 이름 · 판정치 · 경감 · 무기가 상하는가 */
    private record Guardline(String stance, int score, int soak, boolean clashes) {
    }

    /** {@code /혼천 태세 <회피|막기|흘리기|자동>} — MvtCommand 가 부른다 */
    public void setStance(Player player, String stance) {
        Growth growth = Growth.get();
        String auto = engine.stanceDefault();
        if (growth == null) {
            flash(player, ChatColor.GRAY + "성장 축이 배선되지 않았다");
            return;
        }
        if (auto.equals(stance)) {
            stancePin.remove(player.getUniqueId());
            player.sendMessage(ChatColor.AQUA + "태세 — " + auto
                    + ChatColor.GRAY + " (몸이 아는 대로 선다. 판정치 + 경감이 가장 높은 태세)");
            return;
        }
        if (!growth.stanceNames().contains(stance)) {
            player.sendMessage(ChatColor.GRAY + "그런 태세는 없다 — "
                    + String.join(" · ", growth.stanceNames()) + " · " + auto);
            return;
        }
        stancePin.put(player.getUniqueId(), stance);
        Growth.Stance st = growth.stance(stance);
        player.sendMessage(ChatColor.AQUA + "태세 — " + stance
                + ChatColor.GRAY + " (" + st.attribute() + " + " + st.skill()
                + (st.soak() > 0 ? " · 경감 −" + st.soak() : "")
                + (st.penalty() != 0 ? " · 판정 " + st.penalty() : "")
                + (st.weaponSafe() ? " · 무기 안전" : " · 무기가 격을 먹는다") + ")");
    }

    /** 지금 서 있는 태세 — HUD 가 그린다 (아직 한 대도 안 맞았으면 지금 몸짓으로 계산해 보인다) */
    public String stanceOf(Player player) {
        String now = stanceNow.get(player.getUniqueId());
        return now != null ? now : chooseStance(player, false);
    }

    // ══════════ 능동 태세 — 우클릭이 방어를 선언한다 (B-015 · active_guard) ══════════

    /**
     * <b>방어 선언</b> — 맨 우클릭. 넷째 태세가 아니라 <b>방어_전념(actions)의 MC 환산</b>이다:
     * 1합({@link SkillEngine#roundTicks}) 동안 태세 판정 +2, 태세는 막기로 선다. 대가는 행동이다 —
     * 공격하면 깨진다 ({@link #breakGuard}).
     *
     * <p><b>재선언은 지속만 늘린다</b> — 패링의 시계는 첫 선언의 것이다. 손이 이미 올라가 있으면
     * 다시 잴 수 없다 (연타 = 그냥 가드, 읽고 세운 한 번 = 패링 기회). 쿨다운이 아니다:
     * 선언을 막는 것은 타이머가 아니라 <b>손</b>이다 ({@code no_cooldown} 은 그대로 참이다).
     */
    private void declareGuard(Player player, PlayerInteractEvent event) {
        if (!activeGuard.enabled() || Growth.get() == null) {
            return;   // 등록부가 없거나 태세 층이 미배선 — 옛 동작 그대로 (우클릭은 세계의 몫)
        }
        if (event.useItemInHand() == Event.Result.DENY) {
            return;   // SkillCast 가 이미 이 우클릭으로 절기를 냈다 (겨눔이 선언보다 앞선다)
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isEdible()) {
            return;   // 먹는 손 — 세계의 몫. 밥을 먹으며 방어에 전념할 수는 없다
        }
        // basic_strike 등록 계열만 — 활·미등록 계열은 우클릭에 제 일이 있다 (활은 시위를 당긴다)
        if (engine.basicStrike(engine.weaponClassOf(held, materialName(player))) == null) {
            return;
        }
        long until = tick + engine.roundTicks();   // '이번 라운드' — realtime.round_ticks 가 정본
        long[] g = guardDeclare.get(player.getUniqueId());
        if (g != null && tick < g[1]) {
            g[1] = until;   // 손은 이미 올라가 있다 — 지속만 는다. 패링의 시계(g[0])는 그대로
            return;
        }
        guardDeclare.put(player.getUniqueId(), new long[]{tick, until});
        stanceFx(player, "선언");   // 선언은 남의 눈에도 보인다 — 보이는 것 = 맞는 것 (상대가 읽고 수를 고른다)
        hud.flash(player, ChatColor.AQUA + stanceLabel("선언") + ChatColor.DARK_GRAY
                + " │ 태세 +" + activeGuard.commitBonus() + " (" + activeGuard.stance() + ")",
                tick + engine.hudFlashTicks());
    }

    /** 선언이 지금 서 있는가 — 판정층({@link #chooseStance}·{@link #guardline})이 읽는다 */
    private boolean guardDeclared(Player player) {
        long[] g = guardDeclare.get(player.getUniqueId());
        return g != null && tick < g[1];
    }

    /** 이 순간이 선언의 <b>앞머리</b>(패링 창) 안인가 — 읽고 세운 손만 패링이다 */
    private boolean parryTiming(LivingEntity body) {
        if (!(body instanceof Player player)) {
            return false;
        }
        long[] g = guardDeclare.get(player.getUniqueId());
        return g != null && tick < g[1] && tick - g[0] <= activeGuard.parryTicks();
    }

    /**
     * 행동 소모 — 공격하는 순간 선언이 깨진다 ({@code active_guard.break_on_attack}).
     * <b>SkillCast 도 부른다</b>: 절기도 공격이다 (+2 와 살초를 동시에 가질 수 없다).
     */
    public void breakGuard(Player player) {
        if (activeGuard.breakOnAttack()) {
            guardDeclare.remove(player.getUniqueId());
        }
    }

    /**
     * <b>방어가 이겼다 — 虛 의 기록.</b> 세 판정길(npcStrike · basicJudged · resolve)이 전부 지나는
     * 한 자리다. 여기서 남긴 기록을 {@link #opening} 이 절기의 관문에 판다.
     *
     * @return 패링이었는가 — 선언의 앞머리({@code parry_window_ticks}) 안에서 받아 냈다
     */
    private boolean stanceWon(LivingEntity body, String stance) {
        if (!(body instanceof Player player)) {
            return false;   // NPC 는 절기의 虛 를 사지 않는다 — 기록할 것이 없다
        }
        UUID id = player.getUniqueId();
        lastStanceWin.put(id, tick);
        Growth growth = Growth.get();
        if (growth != null && growth.lostWhenSurrounded(stance)) {
            lastDodge.put(id, tick);   // 몸을 뺀 태세(forced_guard.loses) = 회피 — 등록부가 이름을 댄다
        }
        boolean parried = parryTiming(player);
        if (parried) {
            lastParry.put(id, tick);
            event(player.getLocation().add(0, 1.2, 0), "패링_성공");   // defend 의 방패 패링과 같은 이음매
        }
        return parried;
    }

    /**
     * <b>절기 虛 관문의 눈</b> — SkillCast 가 부른다 (B-015 의 닫는 조건 후반부).
     *
     * <p>그전까지 "패링·회피·반격"은 전부 <b>피격 후 창</b>(lastHurt + COUNTER_WINDOW)으로 근사됐다 —
     * 막는 행위가 아니라 <b>맞은 뒤의 보상</b>이었다. 이제 <b>방어 성공의 기록</b>을 읽는다:
     * 패링(선언 앞머리에서 받아 냄) · 회피(몸을 뺌) · 반격(어느 태세든 이긴 직후).
     * 창은 등록부의 것이다 ({@code active_guard.opening_window_ticks}).
     */
    public boolean opening(Player player, String heo) {
        if (!activeGuard.enabled()) {
            return false;   // 등록부가 없으면 허는 닫혀 있다 — 근사로 되돌아가지 않는다
        }
        UUID id = player.getUniqueId();
        Long at = switch (heo) {
            case "패링" -> lastParry.get(id);
            case "회피" -> lastDodge.get(id);
            case "반격" -> lastStanceWin.get(id);
            default -> null;
        };
        return at != null && tick - at <= activeGuard.openingTicks();
    }

    /**
     * <b>태세를 고른다</b> — 몸짓 → 지정 → 자동 (combat.yml defender_stance_mc.precedence).
     *
     * <p>포위되면 회피가 사라진다 ({@code forced_guard.loses}) — 몸을 뺄 자리가 없으면 못 고른다.
     * 회피를 고른 채 포위당한 자는 <b>화면이 그 사실을 가르친다</b>: "몸을 뺄 자리가 없다".
     */
    private String chooseStance(LivingEntity body, boolean surrounded) {
        Growth growth = Growth.get();
        if (growth == null) {
            return null;
        }
        String picked = null;
        if (body instanceof Player player) {
            // ⓪ 선언 — 행동으로 세운 태세가 자세보다 앞선다 (precedence: [선언, 몸짓, …] · active_guard)
            if (guardDeclared(player)) {
                picked = activeGuard.stance();
            }
            // ① 몸짓 — 손과 발이 이미 말하고 있다 (등록부가 술어 이름을 준다. 코드가 몸짓을 짓지 않는다)
            if (picked == null) {
                if (player.isBlocking()) {
                    picked = engine.stanceOfGesture("isBlocking");
                } else if (player.isSneaking()) {
                    picked = engine.stanceOfGesture("isSneaking");
                } else if (player.isSprinting()) {
                    picked = engine.stanceOfGesture("isSprinting");
                }
            }
            // ② 몸에 밴 태세
            if (picked == null) {
                picked = stancePin.get(player.getUniqueId());
            }
        }
        if (picked != null && surrounded && growth.lostWhenSurrounded(picked)) {
            picked = null;   // 회피를 골랐으나 뺄 자리가 없다 — 몸이 남은 것 중에서 고른다
        }
        if (picked != null) {
            return picked;
        }
        // ③ 자동 — 몸이 아는 대로. 삼류도 여기서 제 목숨을 본다 (게이트 없음)
        if (body instanceof Player player) {
            PlayerLedger led = plugin.ledger(player.getUniqueId());
            return growth.bestStance(led, weaponSkill(player), gyeonggongSkill(player),
                    armorDodge(player), surrounded);
        }
        return npcBestStance(body, surrounded);
    }

    /** 병기 기술 — 막기·흘리기의 '기술' 항 (지금 손에 든 병기로 나가는 주무공의 숙련) */
    private int weaponSkill(Player player) {
        String skillId = skillInHand(player);
        return skillId == null ? 0
                : plugin.ledger(player.getUniqueId())
                        .levelOf(engine.skillName(skillId), plugin.progression());
    }

    /**
     * 경공 — <b>회피의 '기술' 항</b> ({@code combat.yml defender_choice.회피.check = "민첩 + 경공"}).
     * 경공 담당이 깔아 둔 이음매({@link Gyeonggong#gyeonggongMastery})가 여기서 처음으로 쓰인다.
     *
     * <p><b>철갑은 경공을 못 쓴다</b> ({@code gyeonggong.yml armor_gate} — 경공_불가).
     * 그러면 이 항은 <b>0</b> 이다. 갑옷이 회피를 파는 두 번째 방식이다 (판정 −2 위에 기술 항 상실).
     */
    private int gyeonggongSkill(Player player) {
        Gyeonggong gg = Gyeonggong.get();
        if (gg == null) {
            return 0;
        }
        if (gg.blocksGyeonggong(armorOf(player))) {
            return 0;   // 철갑이 몸을 땅에 붙든다 — 발의 기술이 통째로 사라진다
        }
        return gg.gyeonggongMastery(plugin.ledger(player.getUniqueId()), plugin.progression());
    }

    /** 이 몸이 입은 갑옷 계열 (equipment.yml armor) — 바닐라 흉갑을 등록부로 읽는다 */
    private String armorOf(LivingEntity body) {
        Gyeonggong gg = Gyeonggong.get();
        EntityEquipment gear = body.getEquipment();
        ItemStack chest = gear == null ? null : gear.getChestplate();
        String material = chest == null || chest.getType().isAir() ? null : chest.getType().name();
        return gg == null ? "무복" : gg.armorOf(material);
    }

    /** 갑옷이 파는 것 — 회피 판정 (무복 0 · 피갑 −1 · 철갑 −2). 막기·흘리기는 갑옷을 신경 쓰지 않는다 */
    private int armorDodge(LivingEntity body) {
        return engine.armorDodgePenalty(armorOf(body));
    }

    /**
     * 갑옷이 사는 것 — <b>경감</b>. 이것이 없어서 지금까지 <b>갑옷은 손해만 봤다</b>
     * (회피를 팔고 아무것도 못 받았다).
     *
     * <p><b>단, 상위 격 앞에서는 0 이다</b> ({@code equipment.yml mitigation_pierced_from: 강기} —
     * 등록부의 프로즈가 이미 그어 둔 선: <i>"격 상성은 못 이긴다 — 검강 앞 피갑은 종이"</i>).
     * 그래서 갑옷은 <b>졸개에게 강하고 고수에게 무력하다</b> — 그것이 갑옷의 지배 전략을 스스로 막는다.
     */
    private int armorSoak(LivingEntity body, String grade) {
        return engine.armorPierced(grade) ? 0 : engine.armorMitigation(armorOf(body));
    }

    /** 이 몸을 지금 붙잡고 있는 손의 수 — 포위 판정 (둘 이상이면 회피가 사라진다) */
    private int attackersOn(LivingEntity defender) {
        int n = 0;
        for (org.bukkit.entity.Entity e : defender.getNearbyEntities(NPC_REACH + 1.0, 2.5, NPC_REACH + 1.0)) {
            if (e instanceof Mob mob && mob.isValid() && defender.equals(mob.getTarget())) {
                n++;
            }
        }
        return Math.max(1, n);
    }

    /**
     * 이 합의 태세 한 줄 — 판정치 · 경감 · 무기가 상하는가.
     *
     * <p>판정치 = 능력치 + 기술 + 결 − 판정 비용 + 갑옷 ({@link Growth#defenseScore}).
     * NPC 는 등록부의 능력치 시트를, 없으면 <b>그 경지의 표준 무인</b>을 쓴다
     * ({@code combat_audit realm_axis} 와 같은 셈 — 도구와 엔진이 같은 사람을 세워야 도구가 안 거짓말한다).
     */
    private Guardline guardline(LivingEntity body, String stance, boolean surrounded) {
        Growth growth = Growth.get();
        Growth.Stance st = growth == null ? null : growth.stance(stance);
        if (st == null) {
            return new Guardline(stance, 0, 0, false);
        }
        int score;
        if (body instanceof Player player) {
            score = growth.defenseScore(plugin.ledger(player.getUniqueId()), stance,
                    weaponSkill(player), gyeonggongSkill(player), armorDodge(player), surrounded)
                    // 선언(방어_전념)의 +2 — 행동을 판 값이다 (active_guard.commit_bonus, 정본은 action_notes)
                    + (guardDeclared(player) ? activeGuard.commitBonus() : 0);
        } else {
            score = npcDefenseScore(body, st, surrounded);
        }
        // 맨손은 태울 것이 없다 — 경감은 그대로 들지만 격돌 판정이 없다 (부러질 물건이 없으니까)
        EntityEquipment gear = body.getEquipment();
        ItemStack held = gear == null ? null : gear.getItemInMainHand();
        boolean armed = held != null && !held.getType().isAir();
        return new Guardline(stance, score, st.soak(), !st.weaponSafe() && armed);
    }

    /** NPC 의 방어 판정치 — 등록된 능력치, 없으면 경지의 표준 무인 (코드가 수치를 짓지 않는다) */
    private int npcDefenseScore(LivingEntity body, Growth.Stance st, boolean surrounded) {
        SkillEngine.Npc npc = npcOf(body);
        String realm = foeRealm(body, true);
        int attr = npc == null ? engine.realmAttr(realm)
                : npc.attr(st.attribute(), engine.realmAttr(npc.realm()));
        int skill = engine.realmSkill(realm);
        Growth growth = Growth.get();
        int pen = (surrounded && growth != null && growth.forcedFloor().equals(st.name()))
                ? 0 : st.penalty();
        return attr + skill + pen + (st.usesGyeonggong() ? armorDodge(body) : 0);
    }

    /** NPC 가 고르는 태세 — 기대 피해가 가장 작은 것 (플레이어와 같은 규칙. 대칭 원칙) */
    private String npcBestStance(LivingEntity body, boolean surrounded) {
        Growth growth = Growth.get();
        String best = growth.forcedFloor();
        int bestScore = Integer.MIN_VALUE;
        for (String name : growth.stanceNames()) {
            if (surrounded && growth.lostWhenSurrounded(name)) {
                continue;
            }
            Growth.Stance st = growth.stance(name);
            int score = npcDefenseScore(body, st, surrounded) + st.soak();
            if (score > bestScore) {
                bestScore = score;
                best = name;
            }
        }
        return best;
    }

    /**
     * <b>공격자의 판정 총합</b> — {@code combat.yml attack.attacker}:
     * 능력치 + 무공 숙련 + 보정(경지 격차·협공) + <b>7</b> (NPC 는 굴리지 않는다).
     *
     * <p>능력치가 <b>어느 능력치인가</b>는 <b>병기가 정한다</b> ({@code attacker_attribute} —
     * 도=근력 · 검=민첩 · 활/암기=감각). 등록되지 않은 몸(야생 좀비·짐승)은 그 경지의 표준 무인이다.
     */
    private int foeAttackScore(LivingEntity attacker, LivingEntity defender, int attackers) {
        SkillEngine.Npc npc = npcOf(attacker);
        Growth growth = Growth.get();
        String realm = foeRealm(attacker, true);
        String weaponClass = npc == null ? "맨손" : npc.weaponClass();
        String attr = growth == null ? "근력" : growth.attackAttribute(weaponClass);
        int base = npc == null ? engine.realmAttr(realm) : npc.attr(attr, engine.realmAttr(npc.realm()));
        // 협공 − 피포위 방어 = 0 (같은 눈금이다 — combat.yml 이 그렇게 못 박아 뒀다).
        // 머릿수는 '더 잘 맞히는 것'이 아니라 '더 많이 치는 것'이다 (engage_slots).
        return base + engine.realmSkill(realm)
                + engine.realmGapBonus(realm, defenderRealm(defender))
                + engine.gangNetModifier(attackers)
                + NPC_JUDGMENT;
    }

    /** 방어자의 경지 — 플레이어면 제 경지, NPC면 등록부의 경지 */
    private String defenderRealm(LivingEntity defender) {
        if (defender instanceof Player player) {
            return state(player).realm;
        }
        return foeRealm(defender, true);
    }

    /**
     * <b>공격자의 무공 위력</b> — {@code damage.formula} 의 둘째 항. 짐승·야생 몸은 0 이다
     * (이빨에는 초식이 없다). 등록부의 사람만 제 경지의 무공 위력을 싣는다.
     */
    private int foeTechniquePower(LivingEntity attacker) {
        SkillEngine.Npc npc = npcOf(attacker);
        if (npc == null || npc.isBeast()) {
            return 0;
        }
        return engine.techniquePower(npc.realm());
    }

    /**
     * <b>태세 연출</b> — 화면이 판정에 대해 거짓말하면 안 된다.
     * 막았으면 막았다고, 흘렸으면 흘렸다고, 피했으면 피했다고. <b>팩이 없어도 보인다</b>
     * (파티클·소리·글자 셋 다 바닐라 — 등록부는 이름만 준다: combat.yml defender_stance_mc.vfx).
     */
    private void stanceFx(LivingEntity body, String key) {
        SkillEngine.StanceFx fx = engine.stanceFx(key);
        if (fx == null) {
            return;   // 등록되지 않은 연출 — 조용히 지나간다 (연출이 없다고 판정이 멈추지 않는다)
        }
        Location at = body.getLocation().add(0, 1.2, 0);
        if (fx.hasParticle()) {
            hud.emit(at, fx.particle(), fx.count(), fx.spread(), 0.0);
        }
        if (fx.sound() != null && at.getWorld() != null) {
            at.getWorld().playSound(at, fx.sound(), 1.0f, fx.pitch());
        }
    }

    /** 태세의 글자 — 액션바에 잠깐 뜬다 (statusBar 가 다시 덮기 전까지 read_ticks 동안) */
    private String stanceLabel(String key) {
        SkillEngine.StanceFx fx = engine.stanceFx(key);
        return fx == null ? key : fx.label();
    }

    // ══════════ 방어 — 태세 → 기 방어(호신강기) → 갑옷 ══════════

    /** 한 대를 받아 낸 결과 — 무효(blocked) 이거나, 깎여서 들어온다 (관통 = 격 위력 차) */
    private record Defense(boolean blocked, double damage) {
    }

    /** 방어자의 장부 — 플레이어는 없으면 만든다 (무공을 한 번도 안 쓴 몸도 맞기는 한다) */
    private SkillEngine.State stateOf(LivingEntity entity) {
        return entity instanceof Player player
                ? state(player) : npcStates.get(entity.getUniqueId());
    }

    /**
     * 방어의 두 층 — 반격 오의(무효 + 반사) · 기 방어(호신강기).
     *
     * <p><b>절대 방어는 없다</b> (qi_manifestation forms.두름_몸.호신강기.on_hit): 하위 격을 무효화하는
     * 것은 공짜가 아니라 유지 내력을 깎아 낸다(상쇄 소모 1). 동격은 2. 상위 격은 <b>관통</b>한다 —
     * 격 위력 차만큼 들어오고, 방어자는 그 위에 2를 더 잃는다. 지불할 내력이 없으면 강기는 그 자리에서
     * 흩어진다 (collapse) — 다음 타격은 맨몸으로 받는다.
     */
    private Defense defend(LivingEntity target, LivingEntity attacker, String grade, double incoming) {
        SkillEngine.State state = stateOf(target);
        if (state == null) {
            return new Defense(false, incoming);
        }
        touchCombat(state);

        // 무기 접촉 격돌 — 방패로 받아 낸 순간 (defense_states.패링 / weapon_break.trigger "가드·패링·합").
        // 판정을 바꾸지 않는다 (바닐라가 이미 피해를 깎는다) — 그 순간이 **보이고 들리게** 할 뿐이다.
        if (target instanceof Player guarding && guarding.isBlocking()) {
            event(target.getLocation().add(0, 1.2, 0), "패링_성공");
        }

        // 태극혜검 — 패링의 극의. 지속 중 받은 공격 1회를 무효화하고 위력 그대로 반사한다
        return defendInner(target, attacker, grade, incoming, state);
    }

    /**
     * <b>기(氣)의 층</b> — 반격 오의 · 호신강기. 태세(몸)가 못 막은 것을 <b>기</b>가 받는다.
     * 태세는 이 앞에 선다 — 회피가 성공하면 여기까지 오지 않고, 호신강기의 내력도 <b>깎이지 않는다</b>
     * (몸을 뺀 자는 기를 아낀다 — 회피의 숨은 값).
     */
    private Defense defendInner(LivingEntity target, LivingEntity attacker, String grade,
                                double incoming, SkillEngine.State state) {
        if (tick <= state.counterUntil) {
            state.counterUntil = -1;
            Location at = target.getLocation().add(0, 1, 0);
            event(at, "반격_오의");
            applying = true;
            try {
                attacker.damage(incoming, target);   // 위력 그대로 되돌아간다
            } finally {
                applying = false;
            }
            if (target instanceof Player player) {
                flash(player, ChatColor.LIGHT_PURPLE + "태극 — 그대의 힘이 그대에게 돌아간다");
            }
            return new Defense(true, 0);
        }

        if (!SkillEngine.GUARD.equals(state.armed)) {
            return new Defense(false, incoming);
        }
        SkillEngine.Guard guard = engine.guard(grade, state.armed);
        if (state.energy < guard.drain()) {
            state.armed = null;                      // 상쇄 소모를 못 냈다 — 강기가 흩어진다
            state.nextSustainTick = -1;
            event(target.getLocation().add(0, 1, 0), "호신강기_붕괴");
            if (target instanceof Player player) {
                flash(player, ChatColor.RED + "호신강기가 깨진다 — 상쇄할 내력이 없다");
            }
            return new Defense(false, incoming);
        }
        state.energy -= guard.drain();
        Location at = target.getLocation().add(0, 1, 0);
        event(at, guard.blocked() ? "호신강기_무효" : "호신강기_관통");
        if (target instanceof Player player) {
            flash(player, guard.blocked()
                    ? ChatColor.YELLOW + "호신강기 — 튕겨 낸다 " + ChatColor.DARK_GRAY + "(상쇄 −" + guard.drain() + ")"
                    : ChatColor.RED + "관통 — " + grade + "가 강기를 갈랐다 " + ChatColor.DARK_GRAY
                            + "(피해 " + guard.pierce() + " · 상쇄 −" + guard.drain() + ")");
            energyBossBar.update(player, states.get(player.getUniqueId()));
        }
        return guard.blocked()
                ? new Defense(true, 0)
                : new Defense(false, guard.pierce());   // 관통 피해 = 격 위력 차 (원칙 1)
    }

    /**
     * 무기 격돌 — 원칙 3: 무기가 격을 견뎌야 한다 (보검이 비싼 이유).
     * 범철이 검기를 받으면 <b>3합째 부러진다</b>(CRACK 누적), 범철이 검강을 받으면 <b>한 합에 잘린다</b>(SEVER).
     * 발경은 예외 — 기가 몸·무기 안에 머문다.
     */
    private void clashWeapon(LivingEntity defender, String grade) {
        if (engine.gradeRank(grade) < 2) {
            return;
        }
        EntityEquipment gear = defender.getEquipment();
        ItemStack held = gear == null ? null : gear.getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            return;   // 맨손은 부러지지 않는다 (짐승도 여기서 빠진다 — 무기를 들지 않으니 격돌이 없다)
        }
        String weaponGrade = engine.weaponGradeOf(held, held.getType().name());
        var clash = engine.clash(weaponGrade, grade, 0);
        if (clash == com.honcheon.core.rules.QiManifestationEngine.Clash.NONE) {
            return;
        }
        boolean sever = clash == com.honcheon.core.rules.QiManifestationEngine.Clash.SEVER;
        int count = clashCounts.merge(defender.getUniqueId(), 1, Integer::sum);
        Location at = defender.getLocation().add(0, 1.2, 0);

        if (sever || count % engine.breaksAt() == 0) {
            breakWeapon(defender, gear, held, sever, weaponGrade, grade);
            clashCounts.remove(defender.getUniqueId());
            return;
        }
        wear(held, gear);   // 금이 간다 — 1회째부터 예고 (weapon_break telegraph)
        event(at, "무기_균열");
        if (defender instanceof Player player) {
            player.sendMessage(ChatColor.YELLOW + "날에 금이 간다 — " + weaponGrade + "의 몸으로 " + grade
                    + "를 받았다 (" + count + "/" + engine.breaksAt() + "합)");
        }
    }

    /** 병기의 끝 — 절단(2격 초과)은 한 합, 누적 파괴는 3합째. 그 뒤는 맨손이다 (after_break) */
    private void breakWeapon(LivingEntity holder, EntityEquipment gear, ItemStack held,
                             boolean sever, String weaponGrade, String grade) {
        Location at = holder.getLocation().add(0, 1.2, 0);
        gear.setItemInMainHand(null);
        // 절단(2격 초과, 한 합)과 파괴(누적 3합)는 다른 사건이다 — 소리와 파편 수가 다르다
        event(at, sever ? "무기_절단" : "무기_파괴", held);
        // 무기가 사라졌으니 손이 가벼워진다 — NPC 의 공격력을 맨손으로 되돌린다 (combat.yml weapon_power)
        if (!(holder instanceof Player)) {
            var attribute = holder.getAttribute(org.bukkit.attribute.Attribute.ATTACK_DAMAGE);
            if (attribute != null) {
                double drop = engine.weaponPower(engine.weaponClassOf(held, held.getType().name()))
                        - engine.weaponPower("맨손");
                attribute.setBaseValue(Math.max(1, attribute.getBaseValue() - drop));
            }
        }
        if (holder instanceof Player player) {
            player.sendMessage(ChatColor.RED + (sever
                    ? "검이 잘렸다 — " + weaponGrade + "이(가) " + grade + "를 한 합도 못 견딘다."
                    : "검이 부러졌다 — " + engine.breaksAt() + "합을 버텼다. 남은 것은 맨손이다."));
        }
    }

    /** 내구 1 — 등급별 최대 내구를 넘기면 그 자리에서 부러진다 (바닐라는 '사용'해야 부러진다) */
    private static boolean wear(ItemStack item, EntityEquipment gear) {
        if (!(item.getItemMeta() instanceof Damageable meta)) {
            return false;
        }
        int max = item.getType().getMaxDurability();
        int damage = meta.getDamage() + 1;
        if (max > 0 && damage >= max) {
            gear.setItemInMainHand(null);
            return true;
        }
        meta.setDamage(damage);
        item.setItemMeta(meta);
        return false;
    }

    /** 호(arc) 히트박스 — 정면 부채꼴. max_targets 상한 (F-P3) */
    private List<LivingEntity> arcTargets(Player player, SkillEngine.Cast cast, LivingEntity primary) {
        double range = cast.range();
        Vector facing = player.getLocation().getDirection().setY(0).normalize();
        Location eye = player.getEyeLocation();
        List<LivingEntity> out = new ArrayList<>();
        if (primary != null && primary.isValid()) {
            out.add(primary);
        }
        for (org.bukkit.entity.Entity e : player.getNearbyEntities(range, range, range)) {
            if (!(e instanceof LivingEntity le) || le.equals(player) || out.contains(le) || !le.isValid()) {
                continue;
            }
            if (inArc(eye, facing, range, cast.angle(), le.getLocation())) {
                out.add(le);
            }
        }
        return out;
    }

    // ══════════ 히트박스의 기하 — 【판정의 눈이 물어보는 그 함수】 ══════════
    //
    // ★ 이 셋이 **맞는 자리를 아는 유일한 코드**다. 판정도 이것을 부르고, 판정의 눈(eye)도 이것을 부른다.
    //   눈이 제 기하학을 따로 가지면 **그림이 판정에 대해 거짓말할 수 있다** — 그래서 하나로 묶었다.
    //   (감사 ⑩이 이 사실을 지킨다: 눈과 판정이 같은 함수를 부르지 않으면 위반이다.)

    /** 호(弧) — 시선의 수평 성분에서 잰 각. 【MC 규약】 높이는 안 본다 (베는 것은 땅 위의 일이다) */
    static boolean inArc(Location eye, Vector facing, double range, double angle, Location at) {
        Vector to = at.toVector().subtract(eye.toVector()).setY(0);
        if (to.lengthSquared() < 1e-6 || to.length() > range) {
            return false;
        }
        return Math.toDegrees(facing.angle(to.normalize())) <= angle / 2.0;
    }

    /** 선(線) — 시선 축을 따라 길이만큼, 축에서 halfWidth 안. 【3D】 발출은 하늘도 쏜다 */
    static boolean inLine(Location eye, Vector dir, double length, double halfWidth, Location at) {
        Vector to = at.toVector().subtract(eye.toVector());
        double along = to.dot(dir);
        if (along < 0 || along > length) {
            return false;
        }
        return to.clone().subtract(dir.clone().multiply(along)).length() <= halfWidth;
    }

    /** 원(圓) — 제자리에서 몸을 도는 것. 방향이 없다 (뒤도 벤다) */
    static boolean inCircle(Location origin, double range, Location at) {
        return at.getWorld() == origin.getWorld() && at.distance(origin) <= range;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  손이 가려낸다(擇) — 히트박스에 들었다고 다 베는 것이 아니다
    //
    //  ★ 그전엔 <b>히트박스에 든 모든 LivingEntity 를 베었다</b> — 옆에 선 동료도, 마당의 닭도,
    //    지나가던 무명도. 화이트리스트 넷이 나란히 서서 도적을 치면 **서로를 벴다.**
    //    등록부(party.yml)는 답을 갖고 있었다: {@code friendly_fire: { 스킬: 면제, 오의_광역: 예외 }}.
    //    **코드가 그것을 읽지 않았을 뿐이다.**
    //
    //  【무엇으로 아군을 가르는가 — 파티 시스템이 없다】 동행(party)은 아직 코드에 없다. 그래서
    //  '아군 명단'으로 가를 수 없다. 대신 **의도(意)로 가른다** — 그리고 그것이 더 옳다:
    //
    //      ★ 담는 것은 둘뿐이다: <b>내가 겨눈 것</b>(primary) 과 <b>나를 노리는 것</b>(getTarget).
    //        나머지는 <b>휩쓸리지 않는다.</b>
    //
    //  이 한 줄이 청구서 전부를 갚는다:
    //    · 옆의 동료 — 나를 노리지 않고 내가 겨누지도 않았다 → <b>안 벤다</b>
    //    · 마당의 닭 — 광역기에 쓸려도 표적이 아니다 → <b>안 벤다 → 혈채가 안 쌓인다</b>
    //    · 일부러 벤 무명 — <b>내가 클릭했다</b>(primary) → 벤다 → <b>혈채는 그대로 쌓인다</b>
    //      (혈채의 문은 '죽었느냐'가 아니라 <b>'겨눴느냐'</b>다. 실수와 살의를 가르는 것은 겨눔이다.)
    //    · 도적·성난 늑대 — 나를 노린다(getTarget) → 휩쓸어도 좋다
    //    · 비무(Sparring) 중의 상대 — 서로 동의했다 → 담는다 (죽지는 않는다 — Sparring 이 막는다)
    //    · <b>오의의 광역</b> — 등록부의 예외다: <b>"매화만개 앞에서는 아군도 물러선다"</b>
    //
    //  【벽】 등록부 어디에도 "이 격은 벽을 뚫는다"고 적힌 곳이 없다 (qi_manifestation 의 '관통'은
    //  전부 <b>기 방어의 관통</b>이지 지형이 아니다). 그러므로 <b>벽은 전부를 막는다</b> —
    //  어검도 심검도. 코드가 없는 예외를 지어내지 않는다 (등록제). 문 뒤에 숨는 것이 값을 한다.
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 히트박스가 잡은 몸들을 <b>가려낸다</b> — 가까운 것부터, 손이 닿는 만큼.
     * 물리친 몸은 <b>이유와 함께</b> {@code vetoes} 에 남는다 ("안 맞았다"만 말하는 눈은 반쪽 눈이다).
     */
    List<LivingEntity> admit(Player player, SkillEngine.Cast cast, LivingEntity primary,
                                     List<LivingEntity> caught, List<String[]> vetoes) {
        // ★ 【가장 가까운 N】 — 그전엔 청크 순회 순서대로 잘랐다 ("아무 8"). 손은 가까운 것부터 닿는다
        Location me = player.getLocation();
        caught.sort(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(me)));

        List<LivingEntity> out = new ArrayList<>();
        long t0 = System.nanoTime();
        for (LivingEntity t : caught) {
            if (t instanceof Player && safetyBlocks(player, t)) {
                // 【안전 지역 · B-006】 벨 수 없는 몸은 손의 몫(max_targets)도 먹지 않는다
                vetoes.add(new String[]{name(t), "안전 지역 — 사람에게 칼이 서지 않는다 (training.yml location_safety)"});
                continue;
            }
            if (admissionBars(t)) {
                // 【타격 허용 · B-119】 등록부의 예외 표식 — 이 몸도 손의 몫을 먹지 않는다
                vetoes.add(new String[]{name(t), "칼이 서지 않는다 (combat.yml strike_admission)"});
                continue;
            }
            if (out.size() >= cast.maxTargets()) {
                vetoes.add(new String[]{name(t), "손이 모자라다 (max_targets " + cast.maxTargets() + ")"});
                continue;
            }
            if (!aimedAt(player, t, cast, primary)) {
                vetoes.add(new String[]{name(t), "표적이 아니다 — 휩쓸리지 않는다 (party.yml friendly_fire)"});
                continue;
            }
            if (!player.hasLineOfSight(t)) {
                vetoes.add(new String[]{name(t), "벽이 막았다 (시선 없음)"});
                continue;
            }
            out.add(t);
        }
        // 【성능】 문서가 경고한 그 비용이다 ("잦은 Raycast"). 재지 않으면 예산을 넘는지 알 수 없다.
        //   레이캐스트는 **기하를 통과한 몸에만** 쏜다 (군중 전체가 아니라 ≤ max_targets 개)
        Metrics.record("판정_가려내기", System.nanoTime() - t0);
        return out;
    }

    /**
     * <b>겨눴는가, 아니면 나를 노리는가</b> — 이 둘만이 표적이다 (나머지는 휩쓸리지 않는다).
     *
     * <p>등록부가 {@code friendly_fire.스킬 = 면제} 를 적어 두지 않았다면 옛 행동(전부 벤다)으로 돌아간다 —
     * <b>규칙은 등록부의 것이지 이 코드의 것이 아니다.</b>
     */
    private boolean aimedAt(Player player, LivingEntity t, SkillEngine.Cast cast, LivingEntity primary) {
        if (!engine.spareAllies()) {
            return true;   // 등록부가 면제를 안 적었다 — 코드가 대신 정하지 않는다
        }
        if (t.equals(primary)) {
            return true;   // ★ **내가 겨눴다** — 일부러 벤 무명은 혈채다 (실수와 살의를 가르는 것은 겨눔이다)
        }
        if (t instanceof Monster) {
            return true;   // 적대 — 휩쓸어도 좋다
        }
        if (t instanceof Mob mob && player.equals(mob.getTarget())) {
            return true;   // ★ **나를 노린다** — 성난 늑대·달려드는 도적 (몹이 아니어도 적이다)
        }
        if (cast.ultimate() != null && engine.ultimateSweepsAllies() && isSweepingArt(cast)) {
            return true;   // 등록부의 예외 — **"매화만개 앞에서는 아군도 물러선다"**
        }
        Sparring bouts = plugin == null || plugin.hunting() == null ? null : plugin.hunting().sparring();
        return bouts != null && t instanceof Player other
                && bouts.isSparring(other) && bouts.isSparring(player);   // 비무 — 서로 동의했다 (죽지는 않는다)
    }

    /** 오의의 <b>광역</b> — 몸을 도는 것(원)과 진(陣). 한 사람만 겨눈 오의는 광역이 아니다 */
    private static boolean isSweepingArt(SkillEngine.Cast cast) {
        return "원".equals(cast.hitType()) || "진".equals(cast.hitType());
    }

    /** 눈에 띄는 이름 — 무명은 종(種)으로 부른다 (닭은 닭이다) */
    private static String name(LivingEntity t) {
        return t instanceof Player p ? p.getName()
                : t.customName() != null ? t.getName() : t.getType().name();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  판정의 눈(判定의 眼) — 【디버그】 <b>보이는 것이 정말 맞는 것인가</b>
    //
    //  이 프로젝트의 규칙은 "보이는 것 = 맞는 것"이다. 그런데 그것을 **확인할 방법이 없었다**:
    //  성능은 Metrics 가 재고 등록부는 감사가 재는데, **판정만은 아무도 못 봤다.**
    //  히트박스가 정말 그 각도인지 · 2d6 이 무엇을 굴렸는지 · 피해가 어느 층에서 깎였는지 —
    //  전부 코드를 읽어서 상상해야 했다. **오늘 이 프로젝트에서 눈이 스무 번 넘게 거짓말했다.**
    //
    //  ★ 【정직의 못】 이 눈은 히트박스를 **다시 그리지 않는다.** 판정이 쓰는 그 함수(inArc·inLine·
    //    inCircle)에 점을 하나씩 물어본다 — "이 자리는 맞는 자리인가?" 그렇다고 답한 점만 찍는다.
    //    **그리는 코드와 맞히는 코드가 하나이므로, 그림은 판정에 대해 거짓말할 수 없다.**
    //    (다른 길 — 눈이 제 원뿔을 따로 그리는 것 — 은 거짓말할 수 있는 눈이다. 그것은 눈이 아니다.)
    // ══════════════════════════════════════════════════════════════════════════

    /** 판정의 눈을 켠 몸들 — 비어 있으면 판정 경로의 비용은 {@code if} 한 줄이다 */
    private final Set<UUID> eyes = new HashSet<>();

    /** {@code /혼천 판정보기} — MvtCommand 가 부른다. @return 켜졌는가 */
    boolean toggleEye(Player player) {
        UUID id = player.getUniqueId();
        if (eyes.remove(id)) {
            return false;
        }
        eyes.add(id);
        return true;
    }

    /**
     * 히트박스를 <b>판정에게 물어서</b> 그린다 — 표본 점을 하나씩 판정 함수에 넣고, "맞는 자리"라고
     * 답한 점만 찍는다. 상한(max_points)은 등록부의 것이다 — <b>눈이 서버를 죽이면 안 된다</b>.
     */
    private void eyeHitbox(Player player, SkillEngine.Cast cast, List<LivingEntity> targets,
                           List<String[]> vetoes) {
        SkillEngine.Eye eye = engine.eye();
        if (eye == null || eye.hitboxParticle() == null) {
            return;
        }
        Location origin = player.getLocation();
        Location at = player.getEyeLocation();
        Vector dir = origin.getDirection().normalize();
        Vector flat = dir.clone().setY(0);
        if (flat.lengthSquared() < 1e-6) {
            flat = new Vector(0, 0, 1);
        }
        flat.normalize();
        double range = cast.range();
        double half = Math.max(0.5, cast.angle() / 2.0);   // 선의 angle 칸은 폭이다 (lineTargets 와 같은 규약)
        double step = Math.max(0.2, eye.step());
        int drawn = 0;

        for (double x = -range; x <= range && drawn < eye.maxPoints(); x += step) {
            for (double z = -range; z <= range && drawn < eye.maxPoints(); z += step) {
                Location p = origin.clone().add(x, eye.height(), z);
                boolean inside = switch (cast.hitType()) {
                    case "선" -> inLine(at, dir, range, half, p.clone().add(0, 1.0, 0));
                    case "원" -> inCircle(origin, range, p);
                    default -> inArc(at, flat, range, cast.angle(), p);
                };
                if (inside) {
                    hud.emit(p, eye.hitboxParticle(), 1, 0.0, 0.0);
                    drawn++;
                }
            }
        }
        // 실제로 목록에 오른 몸 — 히트박스가 **고른 자**의 머리 위에 찍는다 (그림과 목록이 어긋나면 그것이 버그다)
        if (eye.targetParticle() != null) {
            for (LivingEntity t : targets) {
                hud.emit(t.getEyeLocation().add(0, 0.6, 0), eye.targetParticle(), 4, 0.15, 0.0);
            }
        }
        if (eye.log()) {
            player.sendMessage(ChatColor.DARK_AQUA + "▍판정 " + ChatColor.WHITE + cast.hitType()
                    + ChatColor.GRAY + " 사거리 " + String.format("%.1f", range) + "m · "
                    + ("선".equals(cast.hitType()) ? "폭 " + String.format("%.1f", half * 2) : "각 " + (int) cast.angle() + "°")
                    + " · 표본 " + drawn + "점" + (drawn >= eye.maxPoints() ? " (상한)" : "")
                    + ChatColor.DARK_GRAY + " │ " + ChatColor.WHITE + "고른 몸 " + targets.size()
                    + ChatColor.DARK_GRAY + "/" + cast.maxTargets());
            // ★ **왜 안 맞았는지** — "안 맞았다"만 말하는 눈은 반쪽 눈이다.
            //   히트박스에는 들었는데 손이 물린 몸들. 이유가 없으면 버그와 규칙을 구별할 수 없다.
            for (String[] v : vetoes) {
                player.sendMessage(ChatColor.DARK_GRAY + "  ✗ " + ChatColor.GRAY + v[0]
                        + ChatColor.DARK_GRAY + " — " + v[1]);
            }
        }
    }

    /**
     * 2d6 이 무엇을 굴렸는가 — 실행력의 내역과 저항의 출처 (숫자가 어디서 왔는지 못 대면 그것은 마법이다).
     * <b>Cast 를 받지 않는다</b> (B-105) — 무공도 평타도 같은 눈에 뜬다. 문법은 한 벌뿐이다.
     */
    private void eyeRoll(Player player, LivingEntity target,
                         int attrBonus, int mastery, int execBase, int roll, int resist,
                         Guardline foeLine, SkillEngine.Strike strike) {
        if (!engine.eye().log()) {
            return;
        }
        String who = target.getName();
        player.sendMessage(ChatColor.DARK_AQUA + "  ├ " + ChatColor.WHITE + who
                + ChatColor.GRAY + " · 실행력 " + ChatColor.WHITE + execBase
                + ChatColor.DARK_GRAY + "(능력치 " + attrBonus + " + 숙련 " + mastery
                + " + 병기 " + engine.weaponJudgmentBonus(weaponGrade(player))
                + " + 경지차 " + engine.realmGapBonus(state(player).realm,
                        foeRealm(target, target instanceof Monster)) + ")"
                + ChatColor.GRAY + " + 2d6 " + ChatColor.WHITE + roll
                + ChatColor.GRAY + " vs 저항 " + ChatColor.WHITE + resist
                + ChatColor.DARK_GRAY + (foeLine == null ? "(고정 난이도)"
                        : "(" + foeLine.stance() + " " + foeLine.score() + " + NPC " + NPC_JUDGMENT + ")")
                + ChatColor.GRAY + " → " + (strike.hit() ? ChatColor.GREEN : ChatColor.RED)
                + strike.tierName() + ChatColor.GRAY + " (여유 " + strike.margin() + ")");
    }

    /** 피해가 <b>어느 층에서</b> 깎였는가 — 태세 → 갑옷 → 기 방어. 0 이 됐으면 어디서 0 이 됐는지 보인다 */
    private void eyeDamage(Player player, int base, int stanceSoak, int armor, Defense defense) {
        if (!engine.eye().log()) {
            return;
        }
        player.sendMessage(ChatColor.DARK_AQUA + "  └ " + ChatColor.GRAY + "피해 "
                + ChatColor.WHITE + base
                + ChatColor.GRAY + " − 태세 " + stanceSoak + " − 갑옷 " + armor
                + " − 기방어 " + String.format("%.1f", Math.max(0.0, base - stanceSoak - armor)
                        - defense.damage())
                + ChatColor.GRAY + " = " + (defense.blocked() || defense.damage() <= 0
                        ? ChatColor.RED + "0 (막혔다)"
                        : ChatColor.YELLOW + String.format("%.1f", defense.damage())));
    }

    /**
     * 【판정의 눈 · 맞는 쪽】 <b>내 태세가 무엇을 굴렸는가</b> — {@link #eyeRoll} 의 거울상 (B-105).
     * 우클릭 선언(B-015)의 +{@code commit_bonus} 는 판정치(score)에 <b>이미 들어 있다</b> —
     * 태그는 그 사실만 밝힌다 (같은 값을 두 번 더해 보이면 눈이 거짓말한다).
     * 세 판정길(npcStrike · basicJudged · resolve)이 전부 이 한 줄을 쓴다 — 문법은 한 벌뿐이다.
     */
    private void eyeStance(Player viewer, LivingEntity attacker, Guardline line,
                           int atkTotal, int defRoll, int margin) {
        if (!engine.eye().log()) {
            return;
        }
        viewer.sendMessage(ChatColor.DARK_AQUA + "▍태세 " + ChatColor.WHITE + line.stance()
                + " " + line.score()
                + (guardDeclared(viewer)
                        ? ChatColor.LIGHT_PURPLE + " · 선언(+" + activeGuard.commitBonus() + " 포함)" : "")
                + ChatColor.GRAY + " + " + defRoll
                + " vs " + attacker.getName() + " 공격 " + ChatColor.WHITE + atkTotal
                + ChatColor.GRAY + " → " + (margin < 0
                        ? ChatColor.GREEN + "받아냈다" + (parryTiming(viewer)
                                ? ChatColor.LIGHT_PURPLE + " · 패링" : "")
                        : ChatColor.RED + "뚫렸다")
                + ChatColor.GRAY + " (마진 " + margin + ")");
    }

    /** 시(矢) — 날아가는 것. 거리에 따라 벌어지는 원뿔 (선과 달리 멀수록 넓다) */
    static boolean inCone(Location eye, Vector dir, double range, double angle, Location at) {
        Vector to = at.toVector().subtract(eye.toVector());
        double along = to.dot(dir);
        if (along < 0 || along > range) {
            return false;
        }
        double half = Math.max(0.8, along * Math.tan(Math.toRadians(angle / 2.0)));
        return to.clone().subtract(dir.clone().multiply(along)).length() <= half;
    }

    /** 선(線) 히트박스 — 참격·포. 시선 방향으로 길이만큼, 폭 안의 것을 벤다 */
    private List<LivingEntity> lineTargets(Player player, SkillEngine.Cast cast) {
        double length = cast.range();
        double halfWidth = Math.max(0.5, cast.angle() / 2.0);   // 발출의 angle 칸은 폭(width)이다
        Vector dir = player.getLocation().getDirection().normalize();
        Location eye = player.getEyeLocation();
        List<LivingEntity> out = new ArrayList<>();
        for (org.bukkit.entity.Entity e : player.getNearbyEntities(length, length, length)) {
            if (!(e instanceof LivingEntity le) || le.equals(player) || !le.isValid()) {
                continue;
            }
            if (inLine(eye, dir, length, halfWidth, le.getEyeLocation())) {
                out.add(le);
            }
        }
        return out;
    }

    /**
     * 경직 — 등급별 틱 (약2·중5·강10·다운20). MC 근사: 이동 봉쇄 + 넉백.
     *
     * <p><b>【2026-07 타격감 패스】</b> 옛 넉백(몹 한정 · 10틱 이상 한정)은 <b>그대로 두었다</b> —
     * {@code impact.enabled: false} 로 되돌렸을 때 이 세계가 정확히 옛 동작으로 돌아가야 하므로.
     * 등록부가 켜져 있으면 {@link #strikeLanded} 의 넉백이 <b>이 위에 덮인다</b> (히트스톱이 풀리는
     * 순간 속도를 새로 쓴다). 경직(SLOWNESS)은 다른 축이므로 두 경우 모두 그대로 든다.
     */
    private void stagger(LivingEntity target, Player from, SkillEngine.Cast cast) {
        int ticks = cast.staggerTicks();
        if (ticks <= 0) {
            return;
        }
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 4, false, false, true));
        if (ticks >= 10 && target instanceof Mob) {
            Vector push = target.getLocation().toVector()
                    .subtract(from.getLocation().toVector()).setY(0);
            if (push.lengthSquared() > 1e-6) {
                target.setVelocity(push.normalize().multiply(ticks >= 20 ? 0.8 : 0.45).setY(0.25));
            }
        }
    }

    // ══════════ 타격의 순간(打擊) — 맞는 쪽의 몸이 말한다 ══════════

    /**
     * <b>한 대가 들어간 순간.</b> 세 가지가 동시에 일어난다: <b>멈춤 · 밀림 · 흔들림</b>
     * (combat.yml {@code impact}).
     *
     * <p><b>【진단이 찾은 것】</b> 이 문은 <b>없었다</b>. 있던 것은 ① 파티클·소리({@code impact()}) ②
     * 경직 SLOWNESS ③ <b>몹에게만</b> 가는 넉백뿐이었다. <b>플레이어는 이 세계에서 한 번도 밀린 적이
     * 없고, 맞아도 화면이 아무 말을 하지 않았다.</b> 사용자가 "공격해도 전혀 바뀌는 게 없다"고 한 것의
     * 절반이 여기 있다 — 나머지 절반은 {@link #basicMelee} 다.
     *
     * <p><b>세 갈래가 모두 이 문을 지난다</b> — 무공의 손({@link #resolve}) · NPC 의 손
     * ({@link #npcStrike}) · 무공 없는 손({@link #basicMelee}). 경로가 하나여야 화면이 거짓말을 안 한다.
     *
     * @param frames 이 수의 시간 구조 (없으면 null — 타격의 눈이 "즉발"로 읽는다)
     */
    private void strikeLanded(LivingEntity from, LivingEntity target, String grade, double damage,
                              SkillEngine.Frames frames) {
        SkillEngine.Impact im = engine.impact();
        if (!im.enabled() || !target.isValid()) {
            return;
        }
        int qi = engine.qiPower(grade);
        int stop = im.hitstopTicks(grade);
        double power = im.knockback(qi, damage);

        // ① 밀림 — 때린 자에게서 멀어지는 방향. 발이 뜬다 (뜨지 않으면 밀린 것이 안 보인다)
        //
        // ★ 【사용자 판정 · 2026-07-13】 "허수아비가 움직인다 (연무장에선 위치 고정)."
        //   맞다. 허수아비는 **적이 아니라 계기(計器)** 다. 밀리면 사거리가 매 대마다 달라져
        //   TTK·합당 평균이 **재는 것이 아니라 흔들리는 것**이 된다. AI 를 껐어도 소용없다 —
        //   넉백은 AI 를 거치지 않고 속도를 직접 밀기 때문이다. 그래서 **여기서** 막는다.
        Vector kick = null;
        boolean canKnock = power > 0.0 && (im.knockPlayers() || !(target instanceof Player))
                && !isDummy(target);
        if (canKnock) {
            Vector away = target.getLocation().toVector().subtract(from.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1.0e-6) {
                away = from.getLocation().getDirection().setY(0);   // 겹쳐 섰다 — 때린 자가 보는 쪽으로
            }
            if (away.lengthSquared() > 1.0e-6) {
                kick = away.normalize().multiply(power).setY(im.knockLift());
            }
        }

        // ② 멈춤 — 히트스톱. 풀리는 순간 ①이 터진다 (멈췄다 → 날아간다)
        if (isDummy(target)) {
            target.setVelocity(new Vector());   // 계기는 제자리를 지킨다 (바닐라 넉백까지 지운다)
        }
        if (stop > 0 && im.freezeTarget()) {
            frozen.put(target.getUniqueId(), new Frozen(tick + stop, kick));
            target.setVelocity(new Vector());
            if (im.attackerHold() && from instanceof Player striker) {
                // 검이 살에 박힌다 — 때린 쪽의 다음 수도 그만큼 늦는다. 그것이 무게다
                SkillEngine.State st = states.get(striker.getUniqueId());
                if (st != null) {
                    st.busyUntil = Math.max(st.busyUntil, tick + stop);
                }
            }
        } else if (kick != null) {
            target.setVelocity(target.getVelocity().add(kick));   // 히트스톱이 꺼졌다 — 바로 민다
        }

        // ③ 흔들림 — **맞은 쪽의 시야**가 흔들린다 (때린 쪽이 아니라)
        //   ★ 등록부가 껐다 (combat.yml impact.shake.enabled: false — 사용자: "멀미가 발생한다").
        //     여기 손은 남겨 둔다. 되살리려면 등록부 한 줄이면 된다.
        shake(target, im.shakeDegrees(qi, damage));

        hitEye(from, target, grade, damage, frames, stop, power, im.shakeDegrees(qi, damage));
    }

    /**
     * 히트스톱의 심장 — 매 틱 <b>속도를 0 으로 못질</b>하고, 시간이 되면 <b>놓아 준다</b>.
     *
     * <p>서버는 클라이언트의 애니메이션 프레임을 멈출 수 없다. 그러나 <b>몸의 속도는 서버의 것</b>이다 —
     * 달리던 몸이 공중에서 멎는 것만으로 "맞았다"가 읽힌다. 그리고 못이 빠지는 순간 넉백이 터진다.
     *
     * <p>죽었거나 나갔으면 조용히 지운다 (유령이 남지 않게).
     */
    private void freezeTick() {
        if (frozen.isEmpty()) {
            return;   // 아무도 안 맞았다 — 비용 0
        }
        frozen.entrySet().removeIf(e -> {
            org.bukkit.entity.Entity body = plugin.getServer().getEntity(e.getKey());
            if (!(body instanceof LivingEntity live) || !live.isValid()) {
                return true;   // 죽었거나 사라졌다
            }
            if (tick < e.getValue().until()) {
                live.setVelocity(new Vector());   // 아직 얼어 있다 — 매 틱 다시 못질한다
                return false;
            }
            Vector release = e.getValue().release();
            if (release != null) {
                live.setVelocity(release);   // 못이 빠졌다 — **이제 날아간다**
            }
            return true;
        });
    }

    /**
     * <b>맞은 쪽의 시야가 흔들린다.</b> {@code setRotation} 진동 — 오프셋의 합이 <b>정확히 0</b> 이라
     * 조준을 훔치지 않는다 (반동이지 조준 훼손이 아니다 — {@link #turn} 이 같은 규약을 쓴다).
     *
     * <p><b>★ pitch 의 벽</b>: pitch 는 ±90 에서 잘린다(clamp). 잘리면 되돌릴 때 그만큼 어긋난 채로
     * 남는다 — {@link #turn} 이 이미 그 벽에 부딪혀 배운 것이다. 그래서 두 가지를 지킨다:
     * ① 제 시선이 안전대(±{@value #PITCH_SAFE}도) 밖이면 <b>pitch 를 아예 안 건드린다</b>.
     * ② 안전대 안에서는 {@code max_degrees}(7) 를 물려 65 + 7 = 72 &lt; 90 — <b>벽에 닿지 않는다</b>.
     * yaw 는 벽이 없다 (360도로 감긴다).
     *
     * <p>차분(delta)만 먹이므로 그 사이 플레이어가 마우스로 돌린 것은 <b>그대로 보존된다</b>.
     */
    /**
     * 허수아비인가 — <b>계기(計器)는 밀리지 않는다</b>.
     *
     * <p>연무장의 허수아비({@code honcheon:dummy})와 입도진의 허수아비({@code honcheon:ipdo_dummy}) 둘 다.
     * 표식은 <b>몸에 박혀 있다</b>(PDC) — 월드나 이름으로 짐작하지 않는다.
     */
    private static boolean isDummy(LivingEntity target) {
        // ★ 연무장의 몸은 **전부** 계기다 (사용자: "몹은 여전히 움직임 · 넉백도 있음").
        //   시험대에서 표적이 밀리면 사거리가 매 대마다 달라져 TTK·평균이 **재는 것이 아니라
        //   흔들리는 것**이 된다. 사람은 예외 — 비무는 밀려야 비무다.
        if (Dojang.isDojang(target.getWorld()) && !(target instanceof Player)) {
            return true;
        }
        var pdc = target.getPersistentDataContainer();
        return pdc.has(new org.bukkit.NamespacedKey("honcheon", "dummy"))
                || pdc.has(new org.bukkit.NamespacedKey("honcheon", "ipdo_dummy"));
    }

    private void shake(LivingEntity target, double degrees) {
        if (degrees <= 0.0 || !(target instanceof Player player) || !player.isOnline()) {
            return;
        }
        int ticks = Math.max(1, engine.impact().shakeTicks());
        // 원점 대비 목표 오프셋 — 좌 → 우 → … → **마지막 칸은 반드시 0** (몸은 돌아온다).
        // 차분(delta)의 합은 망원경처럼 접혀 offset[ticks] − 0 = **정확히 0** 이 된다 (감쇠 비율과 무관하게).
        double[] offset = new double[ticks + 1];
        for (int i = 0; i < ticks; i++) {
            offset[i] = degrees * Math.pow(SHAKE_DECAY, i) * (i % 2 == 0 ? 1.0 : -1.0);
        }
        offset[ticks] = 0.0;
        // pitch 는 벽(±90)이 있으므로 **먹인 만큼을 장부에 적고** 마지막 칸이 그만큼만 되돌린다
        final float[] applied = {0.0f};
        for (int i = 0; i <= ticks; i++) {
            final double dYaw = offset[i] - (i == 0 ? 0.0 : offset[i - 1]);
            final boolean last = i == ticks;
            if (i == 0) {
                nudge(player, dYaw, dYaw * SHAKE_PITCH_RATIO, false, applied);
            } else {
                pending.add(new Pending(tick + i,
                        () -> nudge(player, dYaw, dYaw * SHAKE_PITCH_RATIO, last, applied)));
            }
        }
    }

    /** 감쇠 — 흔들림은 잦아든다 (부호가 뒤집히며 줄어든다: +1.0 → −0.55 → +0.30 → 0) */
    private static final double SHAKE_DECAY = 0.55;
    /** 상하 성분 — 목은 <b>옆으로</b> 꺾인다 (위아래로 크게 꺾으면 하늘/발밑의 벽에 가까워진다) */
    private static final double SHAKE_PITCH_RATIO = 0.45;

    /**
     * 한 칸 흔든다 — yaw 는 벽이 없으므로 <b>언제나 정확</b>하고, pitch 는 벽(±90)이 있으므로
     * <b>실제로 먹인 만큼만 장부에 적는다</b>.
     *
     * <p><b>【눈이 거짓말할 뻔한 자리】</b> 처음엔 흔들기 <b>시작할 때 한 번</b> 안전대를 재고, 그 판단을
     * 3틱 내내 썼다. <b>거짓이었다</b>: 그 사이 플레이어가 시선을 발밑으로 홱 돌리면 나중 칸이 벽에
     * 잘리고, 잘린 만큼 <b>조준이 영구히 어긋난 채로 남는다</b>. {@link #turn} 이 이미 이 벽에 부딪혀
     * 배운 것을 내가 다시 밟았다.
     *
     * <p>그래서 <b>매 칸마다</b> 두 가지를 한다: ① 제 시선(먹인 것을 뺀 값)이 안전대 밖이면 pitch 에서
     * <b>손을 뗀다</b> ② 마지막 칸은 목표각이 아니라 <b>장부에 적힌 만큼</b>을 되돌린다 —
     * {@code cur − applied = 플레이어 제 시선} 이므로 이 되돌림은 <b>절대 벽에 닿지 않는다</b>.
     *
     * @param last 마지막 칸인가 (먹인 만큼을 정확히 되돌린다)
     */
    private void nudge(Player player, double dYaw, double dPitch, boolean last, float[] applied) {
        if (!player.isOnline()) {
            return;
        }
        Location at = player.getLocation();
        float cur = at.getPitch();
        float want;
        if (Math.abs(cur) >= 89.9f) {
            // 【벽이 먹었다】 플레이어가 흔들리는 중에 시선을 하늘/발밑 끝까지 밀어붙였다.
            //   벽은 우리 것과 제 것을 **구별하지 않고** 함께 삼킨다 — 그러니 **돌려주지 않는다**.
            //   돌려주면 제가 겨눈 발밑에서 그만큼 끌어내리는 셈이다 ({@link #turn} 이 배운 것과 같은 규칙).
            applied[0] = 0.0f;
        }
        if (last) {
            want = cur - applied[0];   // **먹인 만큼만** 되돌린다 — 그 사이 제가 돌린 것은 그대로 남는다
            applied[0] = 0.0f;
        } else if (Math.abs(cur - applied[0]) > PITCH_SAFE) {
            want = cur;                // 하늘/발밑을 보고 있다 — pitch 에서 손을 뗀다 (yaw 는 그대로 흔든다)
        } else {
            want = Math.max(-90.0f, Math.min(90.0f, cur + (float) dPitch));
            applied[0] += want - cur;  // 벽에 잘렸으면 **잘린 만큼만** 적는다 (그래야 되돌림이 정확하다)
        }
        player.setRotation(at.getYaw() + (float) dYaw, want);
    }

    /**
     * <b>【타격의 눈】</b> {@code /혼천 타격보기} — 시간 구조와 히트스톱이 <b>실제로 도는가</b>를
     * 사용자가 게임 안에서 직접 본다. 켠 사람의 손에만 뜬다 (꺼져 있으면 비용 0).
     */
    private void hitEye(LivingEntity from, LivingEntity target, String grade, double damage,
                        SkillEngine.Frames f, int stop, double knock, double shakeDeg) {
        if (hitEyes.isEmpty() || !(from instanceof Player player)
                || !hitEyes.contains(player.getUniqueId())) {
            return;
        }
        String time = f == null ? ChatColor.DARK_GRAY + "즉발"
                : ChatColor.WHITE + "선딜 " + f.startup() + ChatColor.DARK_GRAY + "·"
                        + ChatColor.WHITE + "지속 " + f.active() + ChatColor.DARK_GRAY + "·"
                        + ChatColor.WHITE + "후딜 " + f.recovery();
        player.sendMessage(ChatColor.DARK_AQUA + "[타격] " + ChatColor.GRAY + name(target)
                + ChatColor.DARK_GRAY + " │ " + hud.gradeColor(grade) + grade
                + ChatColor.DARK_GRAY + " │ " + time
                + ChatColor.DARK_GRAY + " │ " + ChatColor.AQUA + "히트스톱 " + stop + "틱"
                + ChatColor.DARK_GRAY + " · " + ChatColor.YELLOW
                + String.format("넉백 %.2f", knock)
                + ChatColor.DARK_GRAY + " · " + ChatColor.LIGHT_PURPLE
                + String.format("흔들림 %.1f°", shakeDeg)
                + ChatColor.DARK_GRAY + " · " + ChatColor.RED + String.format("피해 %.1f", damage));
    }

    /** {@code /혼천 타격보기} — MvtCommand 가 부른다. @return 켜졌는가 */
    boolean toggleHitEye(Player player) {
        UUID id = player.getUniqueId();
        if (hitEyes.remove(id)) {
            return false;
        }
        hitEyes.add(id);
        return true;
    }

    /** 자기 무기가 자기 격을 못 견딘다 — 범철에 검기를 두르면 3회마다 손상 1 (weapon_break self_damage) */
    private void strain(Player player, SkillEngine.State state, SkillEngine.Cast cast) {
        if (!cast.manifested()) {
            return;
        }
        String grade = weaponGrade(player);
        if (!engine.selfDamages(grade, cast.grade(), 0)) {
            state.selfStrainCount = 0;
            return;
        }
        state.selfStrainCount++;
        int every = engine.selfDamageEvery();
        if (state.selfStrainCount % every != 0) {
            return;   // 아직 견딘다 — 1회째부터 '금이 간다'는 예고는 아래 손상 시점에만
        }
        EntityEquipment gear = player.getEquipment();
        ItemStack item = gear.getItemInMainHand();
        if (item.getType().isAir()) {
            return;
        }
        // 【고침】 바닐라는 '사용'해야 부러진다 — 내구를 넘긴 손상은 그대로 부러뜨린다 (after_break: 맨손)
        if (wear(item, gear)) {
            event(player.getLocation().add(0, 1.2, 0), "무기_파괴", item);
            player.sendMessage(ChatColor.RED + "검이 부러졌다 — 제 격을 못 견딘 쇠는 결국 제 주인을 버린다.");
            state.selfStrainCount = 0;
            return;
        }
        event(handLocation(player), "자기_손상");
        player.sendMessage(ChatColor.RED + "검이 운다 — " + grade + "의 몸으로 "
                + cast.grade() + "를 감당하지 못한다 (" + every + "합마다 금이 간다)");
    }

    // ══════════ 격 태세 ══════════

    private void cycleArmed(Player player) {
        SkillEngine.State state = state(player);
        List<String> armable = engine.armableGrades(state.realm);
        if (armable.isEmpty()) {
            flash(player, ChatColor.GRAY + "단전이 열리지 않았다 — 몸과 무기가 전부다");
            return;
        }
        String next = engine.cycleArmed(state.realm, state.armed);
        if (next == null) {
            dispel(player, state, "기를 거둔다");
            return;
        }
        int deploy = engine.deployCost(next);   // 호신강기는 전개비 4 를 따로 낸다 (두름은 유지비 선납)
        if (deploy > 0 && state.energy < deploy) {
            flash(player, ChatColor.RED + "기를 두를 내력이 없다 (" + next + " 전개 " + deploy + ")");
            return;
        }
        state.energy -= deploy;
        state.armed = next;
        state.nextSustainTick = tick + engine.roundTicks();

        // 태세 진입 — 두름은 손끝에, 호신강기는 몸에. 형태가 다르면 실루엣도 소리도 달라야 한다
        if (SkillEngine.GUARD.equals(next)) {
            SkillEngine.FormMotion form = engine.motionForm(SkillEngine.GUARD);
            guardRing(player);
            hud.emit(player.getLocation().add(0, 1, 0), form.aura(), false);
            sfx(player.getLocation(), form.deploySounds());
        } else {
            SkillEngine.GradeMotion m = engine.motionGrade(next);
            hud.emit(handLocation(player), m.charge(),
                    Math.min(m.charge().count() * 2, engine.motionBudget().perPointTickMax()), false);
            sfx(player.getLocation(), m.armSounds());
        }
        // 태세 전환의 문구는 flash 로 — 맨 actionBar 는 다음 statusBar 틱(≤0.2초)에 덮여 겹쳐 읽힌다 (B-116)
        flash(player, hud.gradeColor(next) + next + " — " + gradeFlavor(next));
        energyBossBar.update(player, state);
    }

    private void dispel(Player player, SkillEngine.State state, String why) {
        state.armed = null;
        state.nextSustainTick = -1;
        event(handLocation(player), "격_소산");
        flash(player, ChatColor.GRAY + why);
    }

    private static String gradeFlavor(String grade) {
        return switch (grade) {
            case "발경" -> "타격에 기를 싣는다";
            case "검기" -> "기가 날에 서린다";
            case "강기" -> "기가 응집한다";
            case SkillEngine.GUARD -> "기를 몸에 두른다 — 하위 격은 닿지 않는다";
            case "어검" -> "검이 손을 떠난다";
            case "심검" -> "형(形)이 사라진다";
            default -> grade;
        };
    }

    /** 공격에 실리는 격 — 호신강기는 <b>몸</b>에 두른 것이지 검에 두른 것이 아니다 (형태가 다르다) */
    private static String offense(SkillEngine.State state) {
        return SkillEngine.GUARD.equals(state.armed) ? null : state.armed;
    }

    // ══════════ 오의(奧義) — 격이 아니다. 별개의 사다리다 ══════════

    /**
     * F (스왑) = 오의 · Shift+F = 오의 선택 (mc_action_mapping 1장 "F = 오의, 발동권 획득 시에만 활성").
     * 오의를 느끼지 못하는 몸(초절정 미만)에게 F 는 그냥 손 바꾸기다 — 세계를 건드리지 않는다.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        SkillEngine.State state = state(player);
        if (engine.ultimateStage(state.realm) == null) {
            return;   // 벽 너머를 아직 훔쳐보지 못한 몸 — 바닐라 스왑 그대로
        }
        event.setCancelled(true);
        if (player.isSneaking()) {
            cycleUltimate(player, state);
            return;
        }
        castUltimate(player, state);
    }

    /** 전승 오의 순환 — MVT 엔 전승(사문·비급)이 없다. 손으로 고른다 (검증 수단) */
    private void cycleUltimate(Player player, SkillEngine.State state) {
        List<String> ids = engine.ultimateIds();
        int idx = state.ultimateId == null ? -1 : ids.indexOf(state.ultimateId);
        state.ultimateId = ids.get((idx + 1) % ids.size());
        SkillEngine.Ultimate art = engine.ultimate(state.ultimateId);
        int cost = engine.ultimateCost(art, engine.pool(state.naegong));
        player.sendMessage(ChatColor.LIGHT_PURPLE + "「" + art.name() + "」"
                + ChatColor.GRAY + " · " + art.faction() + " · 내력 " + cost
                + " (" + Math.round(art.costRatio() * 100) + "%)"
                + (art.bloodline() ? ChatColor.DARK_RED + " · 혈통 제한" : "")
                + (art.demonic() ? ChatColor.DARK_RED + " · 마공" : ""));
        flash(player, ChatColor.LIGHT_PURPLE + art.name() + ChatColor.DARK_GRAY
                + " — " + engine.ultimateStage(state.realm) + "  (F: 시전 · Shift+F: 다음 오의)");
    }

    /**
     * 오의 시전 — 발동권(흐름) · 횟수 · 내력. 셋을 다 채워야 나간다.
     *
     * <p>초절정(개안)은 <b>불완전 시전</b>이다: 위력 절반, 직후 내력 전소 + 내상.
     * 화경(완성)은 전투당 1회. 현경(자재)부터 횟수 제한이 풀린다.
     */
    private void castUltimate(Player player, SkillEngine.State state) {
        if (state.ultimateId == null) {
            flash(player, ChatColor.GRAY + "펼칠 오의가 없다 — Shift+F 로 고른다");
            return;
        }
        if (tick < state.busyUntil) {
            flash(player, ChatColor.DARK_GRAY + "아직 자세가 돌아오지 않았다");
            return;
        }
        if (state.flow < engine.flowRequired()) {
            flash(player, ChatColor.GRAY + "흐름이 없다 — 오의는 버튼이 아니라 읽어낸 순간이다 ("
                    + state.flow + "/" + engine.flowRequired() + ")");
            return;
        }
        if (state.ultimateUses >= engine.ultimateLimit(state.realm)) {
            flash(player, ChatColor.GRAY + "이 전투에서 이미 한 번 펼쳤다 (자재는 현경부터다)");
            return;
        }
        SkillEngine.Ultimate art = engine.ultimate(state.ultimateId);
        int pool = engine.pool(state.naegong);
        SkillEngine.Cast cast = engine.planUltimate(art, state.realm, state.energy, pool,
                state.armed, engine.weaponClassOf(player.getInventory().getItemInMainHand(), materialName(player)));
        if (cast == null) {
            flash(player, ChatColor.RED + "내력이 반도 남지 않았다 — 태울 것이 없다 ("
                    + state.energy + "/" + engine.ultimateCost(art, pool) + ")");
            return;
        }
        state.energy -= cast.paid();
        state.flow = 0;
        state.ultimateUses++;
        state.busyUntil = tick + cast.frames().total();
        state.lastCastTick = tick;
        touchCombat(state);

        // 세계가 알게 된다 — 시전 목격 = 강도 3 소문 (world_weight.rumor). MVT: 목격자에게 그대로 고지
        String stage = engine.ultimateStage(state.realm);
        for (Player viewer : player.getWorld().getPlayers()) {
            if (viewer.getLocation().distance(player.getLocation()) <= engine.cullBeyond()) {
                viewer.sendTitle(ChatColor.LIGHT_PURPLE + "「" + art.name() + "」",
                        ChatColor.GRAY + player.getName() + " — " + stage
                                + (cast.halved() ? " (불완전)" : ""), 5, 30, 15);
            }
        }
        ultimateTelegraph(player, cast, art);

        // 시전 중 무적(상한 20틱) 또는 슈퍼아머 — 오의별 명시 (skill_mechanics iframe_caps.오의)
        if (art.iframeTicks() > 0) {
            state.invulnerableUntil = tick + art.iframeTicks();
            player.setInvulnerable(true);
            pending.add(new Pending(tick + art.iframeTicks(), () -> {
                if (tick >= state.invulnerableUntil) {
                    player.setInvulnerable(false);
                }
            }));
        }
        if (art.superArmor()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,
                    cast.frames().total(), 2, false, false, false));
        }
        pending.add(new Pending(tick + cast.frames().startup(),
                () -> resolve(player, state, cast, null, art.name(), 0)));
        if (cast.halved()) {
            // 개안 — 벽 너머를 훔쳐본 대가: 내력 전소 + 내상 (원기 계통은 MVT 미배선 — 몸이 무거워진다)
            pending.add(new Pending(tick + cast.frames().total(), () -> {
                state.energy = 0;
                player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 600, 0, true, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 600, 0, true, true));
                player.sendMessage(ChatColor.RED + "내상 — 벽 너머의 것을 억지로 끌어왔다. 내력이 전소했다.");
                energyBossBar.update(player, state);
            }));
        }
    }

    /**
     * 긴 선딜 = 긴 텔레그래프 — "세계가 멈춘 듯한 연출". 파티클 예산 내 <b>최우선권</b>.
     *
     * <p>【오의는 어떤 격과도 헷갈리지 않는다】 구분 수단이 셋이다:
     * ① <b>응집 고리</b> — 사방에서 몸으로 빨려드는 고리 (다른 어떤 모션도 고리를 쓰지 않는다)
     * ② <b>개시 섬광</b> — flash 1개 + 뇌격음 (팔레트에서 flash 를 쓰는 것은 오의와 심검뿐이다)
     * ③ <b>이름</b> — 32m 안의 모든 눈에 오의명 (world_weight: 시전 목격 = 강도 3 소문)
     * 파티클·소리·개수는 전부 등록부(skill_motion.yml ultimates)가 정한다.
     */
    private void ultimateTelegraph(Player player, SkillEngine.Cast cast, SkillEngine.Ultimate art) {
        SkillEngine.UltimateMotion m = engine.motionUltimate(art.id());
        if (m == null) {
            return;   // 등록되지 않은 오의는 연출이 없다 (판정은 그대로 나간다)
        }
        SkillEngine.Budget b = engine.motionBudget();
        sfx(player.getLocation(), m.chargeSounds());
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                cast.frames().startup(), 3, false, false, false));
        // 3D — 오의의 형체. 응집 내내 오므린 채 자라고, 개시의 순간 활짝 편다 (예약분을 쓴다: 깎이지 않는다)
        display.bloom(player, art.id(), cast.frames().startup(), art.range());

        int points = Math.min(m.ringPoints(), b.ultimateRingPoints());
        for (int t = 0; t < cast.frames().startup(); t += b.telegraphStepTicks()) {
            double phase = t / (double) Math.max(1, cast.frames().startup());
            pending.add(new Pending(tick + t, () -> {
                if (!player.isOnline()) {
                    return;
                }
                Location at = player.getLocation().add(0, 1, 0);
                double radius = art.range() * (1.0 - phase);   // 고리가 좁아지며 몸으로 빨려든다
                for (int i = 0; i < points; i++) {
                    double angle = Math.PI * 2 * i / points + phase * Math.PI;
                    hud.emitPriority(at.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius),
                            m.chargeParticle(), m.ringPerPoint(), 0.02, 0.0);
                }
                hud.emitPriority(at, m.coreParticle(), m.coreCount(), 0.3, 0.02);
            }));
        }
        SkillEngine.UltFlourish f = engine.ultimateFlourish(art.id());
        pending.add(new Pending(tick + cast.frames().startup(), () -> {
            if (player.isOnline()) {
                Location at = player.getLocation().add(0, 1, 0);
                sfx(at, m.releaseSounds());
                hud.emitPriority(at, m.accent().particle(), m.accent().count(), 0.0, 0.0);
                hud.emitPriority(at, m.burst().particle(), m.burst().count(),
                        art.range() * m.burstSpreadRatio(), m.burst().extra());
                // ★ 개시의 **청백색 폭발감** — burst 위로 겹친다 (burst 40 + 강조 1 + bloom 7 = 48 = 상한)
                hud.emit(at, f.bloom().particle(), f.bloom().ink(), f.bloom().count(),
                        f.bloom().spread(), f.bloom().extra(), true);
            }
        }));
        ultimateFlourish(player, art, f, cast.frames().startup());
    }

    /**
     * 오의의 화려함 — <b>다층 궤적 + 운무</b> (사용자가 이름을 댄 것을 그대로 편다).
     *
     * <p>개시 <b>다음 틱부터</b> 겹이 하나씩 벌어진다: 한 틱에 한 겹(호 8점) + 운무 4 = <b>12 ≤ 48</b>
     * ({@code budget.ultimate_per_tick_max}). 개시 틱에 다 뿌리면 그 한 틱이 예산을 통째로 먹고,
     * 그러면 다른 사람의 오의가 <b>조용히 사라진다</b> — 그래서 <b>시간으로 편다</b> (겹이 벌어지는 것이
     * 곧 '다층'이기도 하다: 한 순간에 겹쳐 뜨면 그것은 층이 아니라 덩어리다).
     *
     * <p>{@code emitPriority} 를 쓴다 — 오의는 생략되지 않고 <b>깎인다</b>
     * (ultimate_arts world_weight.particle_priority).
     */
    private void ultimateFlourish(Player player, SkillEngine.Ultimate art, SkillEngine.UltFlourish f,
                                  int startup) {
        SkillEngine.Layers ly = f.layers();
        SkillEngine.Mist mist = f.mist();
        if (ly.present()) {
            for (int layer = 0; layer < ly.arcs(); layer++) {
                final int k = layer;
                pending.add(new Pending(tick + startup + 1L + layer, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    Location at = player.getLocation().add(0, 1, 0);
                    // 겹마다 반경이 자란다 — 안에서 밖으로 벌어지는 반월의 층
                    double radius = art.range() * ly.radiusRatio() * (k + 1) / (double) ly.arcs();
                    for (int i = 0; i < ly.points(); i++) {
                        double a = Math.PI * 2 * i / ly.points() + k * Math.PI / ly.points();
                        hud.emit(at.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius),
                                ly.particle(), ly.ink(), ly.perPoint(), ly.spread(), ly.extra(), true);
                    }
                }));
            }
        }
        if (mist.present()) {
            for (int t = 0; t < mist.ticks(); t++) {
                pending.add(new Pending(tick + startup + 1L + t, () -> {
                    if (player.isOnline()) {
                        // 운무는 **낮게 깔린다** — 폭발은 위로, 구름은 아래로 (둘이 갈려야 층이 읽힌다)
                        hud.emit(player.getLocation().add(0, mist.height(), 0), mist.particle(), null,
                                mist.count(), mist.spread(), mist.extra(), true);
                    }
                }));
            }
        }
    }

    /** 오의별 부가 효과 — ultimate_arts.yml legacy_arts effect (수치는 config 가 정한다) */
    private void ultimateEffect(Player player, SkillEngine.State state, SkillEngine.Ultimate art,
                                LivingEntity target, double damage) {
        String effect = art.effect();
        SkillEngine.UltimateMotion m = engine.motionUltimate(art.id());
        if (m != null) {
            sfx(target.getLocation(), m.hitSounds());   // 오의의 적중음 — 오의마다 다르다 (혈해만리는 마시는 소리)
            // 오의의 타격 — 맞은 몸에서 옥·청백이 터진다 (격의 burst 와 같은 문법, 더 크다). 우선권을 쓴다
            SkillEngine.Fx hit = engine.ultimateFlourish(art.id()).hit();
            hud.emit(target.getLocation().add(0, 1, 0), hit.particle(), hit.ink(), hit.count(),
                    hit.spread(), hit.extra(), true);
        }
        if (effect.contains("내구")) {
            // 제왕검형·군림 — 적중 시 다운 + 내구 50% 피해 (내구 = 이 세계의 체력. combat.yml durability)
            var max = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            double half = (max == null ? 20 : max.getValue()) * 0.5;
            applying = true;
            try {
                target.damage(half, player);
            } finally {
                applying = false;
            }
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                    engine.staggerTicks("다운"), 6, false, false, true));
        }
        if (effect.contains("약탈") || effect.contains("흡수")) {
            // 혈해만리 — 원기·내력 약탈 (마공 ①). 흡수량으로 자기를 채운다
            var max = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            player.setHealth(Math.min(max == null ? 20 : max.getValue(),
                    player.getHealth() + damage * 0.5));
            SkillEngine.State prey = stateOf(target);
            if (prey != null && prey.energy > 0) {
                int stolen = Math.min(prey.energy, 2);
                prey.energy -= stolen;
                state.energy = Math.min(engine.pool(state.naegong), state.energy + stolen);
            }
            event(target.getLocation().add(0, 1, 0), "흡성");   // 【채색 예외】 붉은 점 — 예외 자체가 경고다
        }
    }

    /** 원(圓) 히트박스 — 사방. 매화만개·혈해만리의 자리 (skill_mechanics hitbox_types 원) */
    private List<LivingEntity> circleTargets(Player player, SkillEngine.Cast cast) {
        double range = cast.range();
        List<LivingEntity> out = new ArrayList<>();
        for (org.bukkit.entity.Entity e : player.getNearbyEntities(range, range, range)) {
            if (e instanceof LivingEntity le && !le.equals(player) && le.isValid()
                    && inCircle(player.getLocation(), range, le.getLocation())) {
                out.add(le);
            }
        }
        return out;
    }

    // ══════════ 연출 — 전부 등록부(config/skill_motion.yml) 판독 ══════════
    // 【등록제 규약】 이 아래에는 파티클 이름도 사운드 키도 없다. 이름을 등록부에서 받아 옮길 뿐이다.
    // 【팩 없이도 읽힌다】 격의 사다리(밝기·개수) · 응집(제자리, 2틱마다) · 발출(날아간다) ·
    //   두름(머문다) · 호신강기(몸을 두르는 고리) · 오의(고리 + 섬광 + 이름) — 여섯이 서로 다르다.

    /** 등록부의 소리 한 줄 — 1.21 의 Sound 는 열거형이 아니다. 바닐라 키를 문자열로 재생한다 */
    private void sfx(Location at, SkillEngine.Sfx sfx) {
        sfx(at, sfx, 1.0f);
    }

    /**
     * 소리 한 줄 — <b>초식의 배율</b>({@code steps[i].sound_scale})이 걸린다.
     *
     * <p>타격음은 <b>격</b>의 것이고(grades[].sounds.impact) 스윙음은 <b>초식·계열</b>의 것이다.
     * 초식이 제 소리만 줄이면 <b>격의 타격음이 그대로 울려</b> "조용한 검"이 성립하지 않았다 —
     * 무성무색(곤륜)은 <b>전 무공 유일의 무음 초식</b>인데, 그 정체성이 등록부만으로 완결되지 않았다.
     * 이 배율이 그 자리다: <b>초식이 제가 내는 모든 소리를 함께 낮춘다</b>.
     *
     * <p><b>0 이면 아예 발행하지 않는다</b> — 다만 <b>타격 파티클은 깎지 않는다</b>: 조용한 것은 정보지만,
     * <i>맞았다는 사실</i>까지 지우면 그것은 화면이 판정에 대해 거짓말하는 것이다.
     */
    private void sfx(Location at, SkillEngine.Sfx sfx, float scale) {
        if (sfx == null || at.getWorld() == null || scale <= 0.0f) {
            return;
        }
        float volume = sfx.volume() * scale;
        if (volume <= 0.0f) {
            return;   // 무음 — 소리를 0 으로 트는 것과 아예 안 트는 것은 다르다 (후자가 정직하다)
        }
        at.getWorld().playSound(at, sfx.key(), volume, sfx.pitch());
    }

    private void sfx(Location at, List<SkillEngine.Sfx> sounds) {
        sfx(at, sounds, 1.0f);
    }

    private void sfx(Location at, List<SkillEngine.Sfx> sounds, float scale) {
        for (SkillEngine.Sfx s : sounds) {
            sfx(at, s, scale);
        }
    }

    /** 이번 초식이 소리를 얼마나 내는가 — 등록되지 않았으면 1.0 (그대로 운다) */
    private static float soundScale(SkillEngine.Step step) {
        return step == null ? 1.0f : step.soundScale();
    }

    /** 이번 수의 초식 한 칸 — 등록부 {@code skills[].steps[i]} (없으면 null: 오의·발출·기본 초식) */
    private SkillEngine.Step stepOf(SkillEngine.Cast cast, int stepIndex) {
        SkillEngine.SkillMotion motion = engine.motionSkill(cast.skillId());
        return motion == null ? null : motion.step(stepIndex);
    }

    /**
     * 사건의 모션 — 무기 균열·파괴·절단 · 다운캐스트 · 호신강기 무효/관통/붕괴 · 패링 · 흐름 …
     * 등록되지 않은 이름은 조용히 아무것도 하지 않는다 (연출이 없다고 판정이 멈추면 안 된다).
     */
    private void event(Location at, String name) {
        event(at, name, null);
    }

    private void event(Location at, String name, Object data) {
        SkillEngine.EventMotion e = engine.motionEvent(name);
        if (e == null) {
            return;
        }
        SkillEngine.Fx f = e.fx();
        if (f.present()) {
            hud.emit(at, f.particle(), f.count(), f.spread(), f.extra(), data);
        }
        sfx(at, e.sounds());
    }

    /**
     * 두름의 잔광 — 켜져 있다는 사실 자체가 정보다 (상대가 보고 판단한다).
     *
     * <p>두 층으로 알린다: <b>손끝 잔광 파티클</b>(언제나) 위에 <b>날의 기</b>(3D — 팩이 있으면).
     * 후자가 사용자가 요구한 것이다: "검기 강기 등 <b>무기에</b> 효과가 발현되어야지, 눈 앞에 발현된다고
     * 다가 아님." 격은 <b>날에 서리는 것</b>이고, 그래야 태세만 잡아도 남이 안다 (전의 규칙의 전제).
     * 호신강기만은 몸에 두른 것이므로 고리다 (실루엣이 달라야 형태가 갈린다).
     */
    private void aura(Location hand, LivingEntity body, String stance) {
        if (SkillEngine.GUARD.equals(stance)) {
            guardRing(body);
            return;
        }
        hud.emit(hand, engine.motionGrade(stance).aura(), false);
        display.sheath(body, stance);   // 날에 기가 흐른다 (지속 — 심장박동으로 산다)
    }

    /**
     * 호신강기 — <b>몸을 두르는 고리</b> (forms.두름_몸.ring). 손끝 잔광(두름)과 실루엣이 다르다.
     * 그 차이가 곧 정보다: "저 자는 검에 기를 실은 것이 아니라 몸에 둘렀다 — 하위 격은 닿지 않는다."
     *
     * <p>두 층으로 돈다: <b>파티클 고리</b>(언제나) 위에 <b>3D 판 4장</b>(디스플레이 — 예산이 허락하면).
     * 3D 가 강등돼도 파티클 고리는 그대로 돈다 — 켜져 있다는 사실은 어떤 경우에도 보인다.
     */
    private void guardRing(LivingEntity body) {
        SkillEngine.FormMotion form = engine.motionForm(SkillEngine.GUARD);
        if (form == null || form.ringPoints() <= 0) {
            return;
        }
        Location at = body.getLocation().add(0, form.ringHeight(), 0);
        for (int i = 0; i < form.ringPoints(); i++) {
            double angle = Math.PI * 2 * i / form.ringPoints() + tick * 0.05;   // 천천히 돈다
            hud.emit(at.clone().add(Math.cos(angle) * form.ringRadius(), 0,
                            Math.sin(angle) * form.ringRadius()),
                    form.ringParticle(), form.ringPerPoint(), 0.02, 0.0);
        }
        display.ring(body, SkillEngine.GUARD);   // 그 위에 3D 판이 돈다 (심장박동 — 갱신이 끊기면 사라진다)
    }

    /** 병기 전시대 표식 — 이 표가 붙은 ItemDisplay 는 회수 대상이고 오라 반경이 커진다 (VFX 표식과 별개) */
    static final NamespacedKey KEY_WEAPON_STAND = new NamespacedKey("honcheon", "weapon_stand");
    /** 전시대 주인 UUID — 회수는 주인만 (강탈해도 남의 검을 못 걷는다) */
    static final NamespacedKey KEY_WEAPON_STAND_OWNER = new NamespacedKey("honcheon", "weapon_stand_owner");
    /** 자동 드롭 전시 표식 — 떨어뜨린 병기를 대신하는 큰 디스플레이 (다가가면 줍힌다 · 수명 있음) */
    static final NamespacedKey KEY_DROP_DISPLAY = new NamespacedKey("honcheon", "weapon_drop_display");
    /** 드롭 전시가 태어난 벽시계 시각(ms) — 줍기 지연·수명(바닐라 despawn 정합)을 잰다 (재기동 넘어 유효) */
    static final NamespacedKey KEY_DROP_BORN = new NamespacedKey("honcheon", "weapon_drop_born");
    /** 줍기 지연 — 버린 직후 바로 도로 줍히지 않게 (바닐라 2초 소유자 지연에 준한다) */
    private static final long PICKUP_DELAY_MS = 1500L;

    /**
     * 병기를 품은 <b>큰 ItemDisplay</b> 를 세운다 — 전시대와 자동 드롭이 함께 쓰는 문.
     * {@code setPersistent(true)} 라 재기동에도 병기를 잃지 않는다. VFX 표식이 아니라 유령 청소에 안 걸린다.
     */
    private void spawnWeaponDisplay(Location at, ItemStack weapon, double scale,
                                    double rotX, double rotY, double rotZ,
                                    NamespacedKey marker, java.util.UUID owner, boolean born) {
        ItemStack one = weapon.clone();
        one.setAmount(1);
        float s = (float) scale;
        Quaternionf rot = new Quaternionf().rotationXYZ(
                (float) Math.toRadians(rotX), (float) Math.toRadians(rotY), (float) Math.toRadians(rotZ));
        at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(one);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);   // 모델 그대로 (손 변형 없이)
            e.setBillboard(Display.Billboard.FIXED);                            // 세운 채 고정 (카메라 안 따라감)
            e.setPersistent(true);                                              // ★재기동해도 검을 잃지 않는다
            e.setViewRange(engine.displayBudget().viewRange());
            e.setTransformation(new Transformation(
                    new Vector3f(), rot, new Vector3f(s, s, s), new Quaternionf()));
            var pdc = e.getPersistentDataContainer();
            pdc.set(marker, PersistentDataType.BYTE, (byte) 1);
            if (owner != null) {
                pdc.set(KEY_WEAPON_STAND_OWNER, PersistentDataType.STRING, owner.toString());
            }
            if (born) {
                pdc.set(KEY_DROP_BORN, PersistentDataType.LONG, System.currentTimeMillis());
            }
        });
    }

    /**
     * <b>/혼천 병기전시</b> — 든 혼천 병기를 앞 지면에 <b>크게 세운다</b>(전시), 빈손이면 가까운 제
     * 전시대를 <b>회수</b>한다. ★영상 정합: 땅에 박힌 큰 검 둘레를 오라가 돈다 (전시대는 실물 병기의
     * ItemDisplay라 {@link #weaponAuraDropped} 순회가 자동으로 오라를 두른다).
     *
     * <p><b>병기를 잃지 않는다</b>: 전시 엔티티는 {@code setPersistent(true)} — 재기동해도 검이 사라지지
     * 않고 그 자리에 서 있다 (회수하면 인벤토리로 돌아온다). 우리 VFX 표식(honcheon:vfx)이 아니므로
     * {@link SkillDisplay#start} 의 유령 청소에 걸리지 않는다.
     */
    public void weaponStandCommand(Player player) {
        SkillEngine.WeaponStand ws = engine.weaponStand();
        if (ws == null || !ws.enabled()) {
            player.sendMessage(ChatColor.GRAY + "병기 전시대가 꺼져 있다 (config/skill_motion.yml weapon_stand.enabled)");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (Weapons.isWeapon(hand)) {
            plantWeaponStand(player, ws, hand);
        } else {
            retrieveWeaponStand(player, ws);
        }
    }

    /** 든 병기를 앞 지면에 크게 세운다 — ItemDisplay(스케일·직립 회전) + 손에서 회수 */
    private void plantWeaponStand(Player player, SkillEngine.WeaponStand ws, ItemStack hand) {
        Vector flat = player.getLocation().getDirection().setY(0);
        if (flat.lengthSquared() < 1.0e-6) {
            flat = new Vector(0, 0, 1);
        }
        flat.normalize();
        // 앞 1.5m 지면 — 발 높이 기준 (박힌 검처럼 보이게 rise 로 띄운다)
        Location base = player.getLocation().add(flat.clone().multiply(1.5));
        base.setY(player.getLocation().getY() + ws.rise());
        base.setYaw(player.getLocation().getYaw());
        base.setPitch(0);

        spawnWeaponDisplay(base, hand, ws.scale(), ws.rotX(), ws.rotY(), ws.rotZ(),
                KEY_WEAPON_STAND, player.getUniqueId(), false);   // 전시대 — 수명 없음(영구), 회수는 주인만
        // 손에서 거둔다 — 전시된 그 한 자루가 세계에 하나 (복제 아님)
        player.getInventory().setItemInMainHand(null);
        player.sendMessage(ChatColor.AQUA + "병기를 세웠다 " + ChatColor.GRAY
                + "— 빈손으로 " + ChatColor.WHITE + "/혼천 병기전시" + ChatColor.GRAY + " 하면 도로 거둔다");
    }

    /** 빈손 호출 — 반경 안의 제 전시대를 회수 (병기가 인벤토리로 돌아온다) */
    private void retrieveWeaponStand(Player player, SkillEngine.WeaponStand ws) {
        double r = ws.retrieveRadius();
        ItemDisplay found = null;
        double best = Double.MAX_VALUE;
        for (org.bukkit.entity.Entity e
                : player.getWorld().getNearbyEntities(player.getLocation(), r, r, r)) {
            if (!(e instanceof ItemDisplay disp)) {
                continue;
            }
            var pdc = disp.getPersistentDataContainer();
            if (!pdc.has(KEY_WEAPON_STAND)) {
                continue;
            }
            String owner = pdc.get(KEY_WEAPON_STAND_OWNER, PersistentDataType.STRING);
            if (owner != null && !owner.equals(player.getUniqueId().toString())) {
                continue;   // 남의 전시대 — 회수는 주인만
            }
            double d = disp.getLocation().distanceSquared(player.getLocation());
            if (d < best) {
                best = d;
                found = disp;
            }
        }
        if (found == null) {
            player.sendMessage(ChatColor.GRAY + "가까이에 거둘 내 전시대가 없다 (반경 "
                    + String.format("%.0f", r) + "m · 든 병기가 있으면 세운다)");
            return;
        }
        ItemStack stored = found.getItemStack();
        found.remove();
        if (stored != null && !stored.getType().isAir()) {
            player.getInventory().addItem(stored).values()
                    .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
        }
        player.sendMessage(ChatColor.AQUA + "병기를 도로 거뒀다");
    }

    /**
     * <b>무기 오라 — ★영상 정합 (떨어진/세워진 아이템).</b> 월드에 <b>떨어져 있거나 세워진</b> 혼천
     * 병기 둘레를 <b>기운</b>이 소용돌이친다 (레퍼런스: 땅에 박힌 검 둘레를 파티클이 돈다).
     *
     * <p>청크 로드된 엔티티만 순회한다 ({@code getEntitiesByClass} — 언로드된 세계는 나오지 않는다).
     * 볼 눈이 없는 세계·거리 밖 아이템은 건너뛴다 (거리 컬링). 우리 VFX 엔티티(honcheon:vfx)는
     * <b>제외</b>한다 — 이기어검·던진 암기의 ItemDisplay 가 제 위에 또 오라를 두르지 않게.
     */
    private void weaponAuraDropped(SkillEngine.WeaponAura wa) {
        // 지면에 낮게 뜬 아이템 — 수직 기둥으로 돈다 (세운 검 둘레를 감는 소용돌이)
        Vector u = new Vector(1, 0, 0);
        Vector v = new Vector(0, 0, 1);
        Vector w = new Vector(0, 1, 0);
        double cull = engine.cullBeyond();
        SkillEngine.WeaponStand ws = engine.weaponStand();
        SkillEngine.DroppedDisplay dd = engine.droppedDisplay();
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            if (world.getPlayers().isEmpty()) {
                continue;   // 볼 눈이 없는 세계 — 발행해도 아무도 못 본다
            }
            int interval = Math.max(1, wa.intervalTicks());
            // ─── ① 떨어뜨린 병기 — dropped_display 가 켜져 있으면 작은 바닐라 아이템을 큰 디스플레이로 교체 ───
            for (org.bukkit.entity.Item item : world.getEntitiesByClass(org.bukkit.entity.Item.class)) {
                ItemStack stack = item.getItemStack();
                if (!Weapons.isWeapon(stack) || !anyPlayerWithin(item.getLocation(), cull)) {
                    continue;
                }
                if (dd != null && dd.enabled()) {
                    // 작은 아이템을 치우고 같은 자리에 큰 디스플레이를 세운다 (병기는 디스플레이가 품는다 — 안 잃음)
                    spawnWeaponDisplay(item.getLocation().add(0, dd.rise(), 0), stack, dd.scale(),
                            dd.rotX(), dd.rotY(), dd.rotZ(), KEY_DROP_DISPLAY, item.getThrower(), true);
                    item.remove();
                } else {
                    // dropped_display 꺼짐 — 옛 동작: 작은 아이템에 오라만 두른다
                    spawnWeaponAura(item.getLocation().add(0, wa.droppedRise(), 0), u, v, w, stack, wa,
                            1.0, interval, 1.0);
                }
            }
            if (!wa.includeDisplays()) {
                continue;
            }
            // ─── ② 세워진 디스플레이 (전시대 · 자동 드롭) — 오라 + 드롭은 줍기/수명 처리 ───
            for (ItemDisplay disp : world.getEntitiesByClass(ItemDisplay.class)) {
                var pdc = disp.getPersistentDataContainer();
                if (pdc.has(SkillDisplay.KEY_VFX)) {
                    continue;   // 우리 VFX 엔티티 — 그 위에 오라를 겹치지 않는다
                }
                ItemStack stack = disp.getItemStack();
                if (stack == null || !Weapons.isWeapon(stack)) {
                    continue;
                }
                boolean stand = ws != null && pdc.has(KEY_WEAPON_STAND);
                boolean drop = pdc.has(KEY_DROP_DISPLAY);
                if (drop && handleDropDisplay(disp, stack, pdc, dd)) {
                    continue;   // 줍혔거나 수명이 다해 사라졌다 — 오라를 그릴 대상이 없다
                }
                if (!anyPlayerWithin(disp.getLocation(), cull)) {
                    continue;
                }
                // 큰 검(전시대·드롭)이면 오라 반경·중심을 키운다 — 작은 아이템과 달리 검 몸통을 감싼다
                double rise;
                double radiusScale;
                if (stand) {
                    rise = ws.auraCenterRise();
                    radiusScale = ws.auraScale();
                } else if (drop && dd != null) {
                    rise = dd.auraCenterRise();
                    radiusScale = dd.auraScale();
                } else {
                    rise = wa.droppedRise();
                    radiusScale = 1.0;
                }
                spawnWeaponAura(disp.getLocation().add(0, rise, 0), u, v, w, stack, wa,
                        1.0, interval, radiusScale);
            }
        }
    }

    /**
     * 자동 드롭 디스플레이의 줍기·수명 — 사라지게 했으면 true.
     * <ul>
     *   <li><b>수명</b> — 바닐라 드롭 despawn(5분)과 정합. 벽시계로 재므로 재기동을 넘어 유효하다.
     *       {@code lifetime_seconds: 0} 이면 안 사라진다 (귀한 병기 보호용 설정).</li>
     *   <li><b>줍기</b> — 줍기 지연 뒤, 반경 안 가장 가까운 플레이어에게 병기가 돌아가고 디스플레이는 사라진다.</li>
     * </ul>
     */
    private boolean handleDropDisplay(ItemDisplay disp, ItemStack stack,
                                      org.bukkit.persistence.PersistentDataContainer pdc,
                                      SkillEngine.DroppedDisplay dd) {
        if (dd == null) {
            return false;
        }
        long born = pdc.getOrDefault(KEY_DROP_BORN, PersistentDataType.LONG, 0L);
        long age = System.currentTimeMillis() - born;
        if (dd.lifetimeSeconds() > 0 && age > dd.lifetimeSeconds() * 1000L) {
            disp.remove();   // 병기가 사라진다 — 바닐라 드롭 despawn 과 같다 (0 이면 이 길을 안 탄다)
            return true;
        }
        if (age < PICKUP_DELAY_MS) {
            return false;   // 버린 직후 — 아직 도로 줍히지 않는다
        }
        Player taker = nearestPlayer(disp.getLocation(), dd.pickupRadius());
        if (taker == null) {
            return false;
        }
        disp.remove();
        // 병기가 인벤토리로 — 넘치면 발 밑에 (다시 작은 아이템으로 떨어지면 다음 순회가 또 크게 세운다)
        taker.getInventory().addItem(stack).values()
                .forEach(rest -> taker.getWorld().dropItem(taker.getLocation(), rest));
        return true;
    }

    /** 반경 안 가장 가까운 플레이어 — 없으면 null (드롭 디스플레이 줍기용) */
    private static Player nearestPlayer(Location at, double range) {
        if (at.getWorld() == null) {
            return null;
        }
        double best = range * range;
        Player found = null;
        for (Player p : at.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(at);
            if (d <= best) {
                best = d;
                found = p;
            }
        }
        return found;
    }

    /**
     * <b>무기 오라 — 부차 (든 무기 곁).</b> 혼천 병기를 주 손에 들면 <b>실제 렌더되는 손 자리</b>
     * (우하단)에서 병기 곁으로 기운이 돈다. 1인칭 정면을 가리지 않는다 (눈앞이 아니라 손 곁이다).
     */
    private void weaponAuraHeld(Player player, SkillEngine.WeaponAura wa) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!Weapons.isWeapon(hand)) {
            return;   // 바닐라 아이템·빈손 — 서릴 기운이 없다
        }
        // ★ calm_held_aura — 검기 평타 무기(호 계열)를 들었으면 held 오라를 억제해 장면을 깨끗하게 (가역).
        //   레퍼런스처럼 검기+흰별만 남기고 연두 사각 클러터(weapon_aura held)를 끈다.
        SkillEngine.KigiSlash kigi = engine.kigiSlash();
        if (kigi != null && kigi.enabled() && kigi.calmHeldAura()) {
            SkillEngine.Basic basic = engine.basicStrike(
                    engine.weaponClassOf(hand, materialName(player)));
            if (basic != null && kigi.appliesToTrail(basic.trail())) {
                return;
            }
        }
        Vector dir = player.getEyeLocation().getDirection().normalize();
        Vector flat = dir.clone().setY(0);
        if (flat.lengthSquared() < 1.0e-6) {
            flat = new Vector(1, 0, 0);
        }
        flat.normalize();
        Vector right = new Vector(-flat.getZ(), 0, flat.getX());   // 시선의 오른쪽 (오른손 자리)
        // 손 자리 — 눈에서 앞·오른쪽·아래로 (우하단 손). 정면(눈앞)이 아니다
        Location center = player.getEyeLocation()
                .add(dir.clone().multiply(wa.heldForward()))
                .add(right.clone().multiply(wa.heldRight()))
                .subtract(0, wa.heldDown(), 0);
        // 궤도 평면 — 병기축(시선)에 수직. 시선이 수직에 가까우면 기준 up 을 바꾼다
        Vector up0 = Math.abs(dir.getY()) > 0.99 ? new Vector(1, 0, 0) : new Vector(0, 1, 0);
        Vector u = dir.clone().crossProduct(up0).normalize();
        Vector v = u.clone().crossProduct(dir).normalize();
        // 촘촘한 주기라 발행당 수를 held/interval 비율로 줄인다 (초당 총량 균형). 스파크 박자도 held 주기 기준
        double density = (double) wa.heldIntervalTicks() / Math.max(1, wa.intervalTicks());
        spawnWeaponAura(center, u, v, dir, hand, wa, density, wa.heldIntervalTicks(), 1.0);
    }

    /** 이 자리에서 {@code range} 안에 눈이 하나라도 있는가 — 거리 컬링 (없으면 발행을 아낀다) */
    private static boolean anyPlayerWithin(Location at, double range) {
        if (at.getWorld() == null) {
            return false;
        }
        double r2 = range * range;
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= r2) {
                return true;
            }
        }
        return false;
    }

    /**
     * 한 병기의 오라를 한 자리에 뿌린다 — 궤도 평면은 {@code u × v}, 나선축은 {@code w}.
     * held 는 (시선수직평면, 시선축), dropped 는 (수평평면, 수직축)을 준다.
     *
     * <p><b>순수 VFX다 — 판정을 한 톨도 바꾸지 않는다</b>: 등급 미등록·계열색 없음·예산 초과 어디서
     * 실패해도 조용히 물러선다. 등급 사다리(범철 없음 → 정련 희미 → 신병 또렷 → 마병 격렬)와 계열/명병색은
     * <b>등록부가 정한다</b> (skill_motion.yml weapon_aura). 코드는 파티클·색을 고르지 않는다.
     * 발행은 {@link SkillHud#emit} 예산 게이트를 그대로 타므로 시야당/전역 예산·LOD·컬링이 자동 적용된다.
     */
    private void spawnWeaponAura(Location center, Vector u, Vector v, Vector w,
                                 ItemStack item, SkillEngine.WeaponAura wa,
                                 double densityMul, int sparkBeatInterval, double radiusScale) {
        Weapons.Series series = Weapons.seriesOf(item);
        Weapons.Grade grade = Weapons.gradeOf(item);
        if (series == null || grade == null) {
            return;
        }
        SkillEngine.WeaponAuraGrade g = wa.grade(grade.name());
        if (g == null || (g.shards() <= 0 && g.sparks() <= 0)) {
            return;   // 범철(오라 없음) 또는 미등록 등급 — 조용히 물러선다
        }
        // 색: 등급이 덮어썼으면 그것(마병 혈), 아니면 계열/명병색. 파일럿 밖 계열은 계열색이 없어 물러선다
        String ink = g.inkOverride() != null ? g.inkOverride()
                : wa.inkFor(series.name(), Weapons.sectOf(item));
        if (ink == null) {
            return;   // 계열 악센트색이 등록 안 됨 (검·도 밖 계열 — 2차의 몫). 조용히 꺼진다
        }

        // 밀도 — held 는 촘촘히 뿌리므로 발행당 수를 줄여 초당 총량을 맞춘다 (예산 균형). 최소 1개는 남긴다
        int shards = g.shards() <= 0 ? 0
                : Math.max(1, (int) Math.round(g.shards() * densityMul));
        double radius = wa.radius() * radiusScale;
        double helix = wa.helix() * radiusScale;
        double phase = tick * wa.orbitSpeed();
        for (int i = 0; i < shards; i++) {
            double a = phase + Math.PI * 2 * i / shards;
            double along = Math.sin(a * 1.5) * helix;   // 나선 — 축을 따라 오르내린다
            Location at = center.clone()
                    .add(u.clone().multiply(Math.cos(a) * radius))
                    .add(v.clone().multiply(Math.sin(a) * radius))
                    .add(w.clone().multiply(along));
            // 작은 결정 — dust size 를 실어 뭉치를 없앤다 (색은 등록 먹빛, 크기만 등록부 shard_size)
            hud.emitSized(at, wa.shardParticle(), ink, wa.shardSize(), 1, wa.shardSpread(), 0.0);
        }
        // 흰 반짝이 — 떠도는 별. spark_every 번의 발행 주기마다 한 번만 (성기게)
        long beats = tick / Math.max(1, sparkBeatInterval);
        if (g.sparks() > 0 && g.sparkEvery() > 0 && beats % g.sparkEvery() == 0) {
            double a = phase * 0.7;   // 결정과 다른 속도로 돌아 서로 붙지 않는다
            double r = radius * wa.sparkRadiusMul();
            for (int s = 0; s < g.sparks(); s++) {
                double aa = a + Math.PI * 2 * s / g.sparks();
                Location at = center.clone()
                        .add(u.clone().multiply(Math.cos(aa) * r))
                        .add(v.clone().multiply(Math.sin(aa) * r));
                hud.emitSized(at, wa.sparkParticle(), ink, wa.sparkSize(), 1, wa.sparkSpread(), 0.0);
            }
        }
    }

    /**
     * 텔레그래프(응집) — 상대가 읽을 수 있어야 한다 ("응집은 빛으로 보인다", npc_combat 대칭 원칙).
     *
     * <p>선딜 2틱마다(budget.telegraph_step_ticks) 손끝에 격의 응집이 맺힌다. 예산은 <b>풀</b>이다:
     * 선딜이 길다고 파티클이 비례해 늘지 않는다 (budget.telegraph_pool). 응집을 보고 물러서면 맨 주먹이 온다.
     */
    private void telegraph(Location at, String grade, int boost) {
        SkillEngine.GradeMotion m = engine.motionGrade(grade);
        int n = m.charge().count() + boost;
        if (n <= 0) {
            return;
        }
        hud.emit(at, m.charge(), Math.min(n, engine.motionBudget().perPointTickMax()), false);
    }

    /** 응집 예약 — 선딜 동안 몇 번 발행할지는 예산 풀이 정한다 (프레임이 길어도 풀이 마르면 멈춘다) */
    private void scheduleTelegraph(java.util.function.Supplier<Location> at, String grade, int startup,
                                   int boost, float soundScale) {
        SkillEngine.GradeMotion m = engine.motionGrade(grade);
        SkillEngine.Budget b = engine.motionBudget();
        int per = m.charge().count() + boost;
        if (per <= 0 || startup < b.telegraphStepTicks()) {
            return;
        }
        int max = Math.max(1, b.telegraphPool() / per);          // 응집 풀 — 이만큼만 발행한다
        int emits = Math.min(max, startup / b.telegraphStepTicks());
        for (int i = 0; i < emits; i++) {
            pending.add(new Pending(tick + (long) i * b.telegraphStepTicks(),
                    () -> telegraph(at.get(), grade, boost)));
        }
        sfx(at.get(), m.chargeSounds(), soundScale);              // 귀로도 예고한다 (감각이 낮아도 읽힌다)
    }

    /**
     * 타격 순간 — 격을 눈에 보이게.
     *
     * <p><b>타격 풀</b>(budget.impact_pool)을 대상 수로 나눈다: 광역 8인을 쳐도 예산이 터지지 않는다.
     * 아무리 나눠도 대상당 최소치(min_impact_per_target)는 남긴다 —
     * <i>맞았다는 사실</i>은 언제나 보여야 한다 (performance.yml over_budget: "핵심 타격 표시만 유지").
     */
    private void impact(Location at, String grade, SkillEngine.Strike strike, int targets,
                        float soundScale) {
        SkillEngine.GradeMotion m = engine.motionGrade(grade);
        SkillEngine.Budget b = engine.motionBudget();
        HuntingGrounds.witnessQi(at, grade);   // 격을 본 자는 전의가 꺾인다 (npc_combat morale 상대_위세)

        int share = Math.max(b.minImpactPerTarget(), b.impactPool() / Math.max(1, targets));
        // 【불가침】 파티클은 배율을 타지 않는다 — 초식이 조용할 수는 있어도, 맞았다는 사실을 지울 수는 없다
        int plain = Math.min(m.impact().count(), share);
        hud.emit(at, m.impact(), plain, false);
        hud.emit(at, m.accent(), false);
        // ★ 타격 순간의 작은 폭발형 입자 (사용자 지시). **한 지점 예산을 넘지 않는다** —
        //   먹점 + 강조 + 폭발 + 대성공 예약분 ≤ per_point_tick_max (등록부 budget 절의 표가 그 산수다).
        //   넘치면 **소리내어 강등**한다 (조용한 절단 금지 — 안 뜬 것과 못 뜬 것은 다른 사건이다).
        int room = b.perPointTickMax() - plain - m.accent().count() - b.critReserve();
        int burst = Math.min(m.burst().count(), Math.max(0, room));
        if (burst < m.burst().count()) {
            degrade("타격 폭발", grade, m.burst().count(), burst, b.perPointTickMax());
        }
        hud.emit(at, m.burst(), burst, false);
        sfx(at, m.impactSounds(), soundScale);   // 타격음만 초식의 배율을 탄다 (무성무색이 조용한 이유)
        if ("critical_success".equals(strike.tierId())) {
            event(at, "대성공");   // 급소 — 격과 무관하게 타격 위에 겹친다 (자리는 critReserve 가 비워 뒀다)
        }
    }

    /**
     * <b>획이 떠난 자리</b> — 잔상·먹번짐이 남는 곳. 참격선의 원점과 <b>같은 등록부</b>를 읽는다
     * ({@code display.stroke_origin.default}) — 두 층이 서로 다른 자리에 그리면 그림이 갈라진다.
     *
     * <p>파티클 층은 팩이 없어도 도는 층이므로 3D 가 강등돼도 이 자리는 남는다.
     */
    private Location strokeWake(Player player, Vector flat) {
        SkillEngine.StrokeOrigin o = engine.strokeOrigin("참격_호");
        Vector right = new Vector(-flat.getZ(), 0, flat.getX());
        Location at = player.getLocation().clone()
                .add(flat.clone().multiply(o.forward()))
                .add(right.multiply(o.lateral()));
        at.setY(player.getLocation().getY() + o.height());
        return at;
    }

    /** 이미 짖은 강등 (같은 자리·같은 격) — 파티클은 초당 여러 번 난다. 로그가 진실을 덮으면 안 된다 */
    private final java.util.Set<String> degraded = new java.util.HashSet<>();

    /**
     * <b>소리내어 강등</b> — 등록부가 청구한 만큼 못 뿌렸으면 말한다.
     *
     * <p>사용자의 못: <i>"넘으면 소리내어 강등하라 (조용한 절단 금지)."</i> 예산이 모자라 깎는 것은
     * 설계지만, <b>말하지 않고 깎는 것</b>은 거짓말이다 — 등록부는 11 을 적었는데 화면엔 3 이 뜨고
     * 아무도 그 사실을 모르는 상태가 그것이다.
     */
    private void degrade(String what, String grade, int want, int got, int cap) {
        if (degraded.add(what + "|" + grade)) {
            plugin.getLogger().warning(String.format(
                    "[모션·강등] %s (%s) — 등록부가 %d 을 청구했으나 %d 만 뿌린다 (한 지점 상한 %d)."
                            + " config/skill_motion.yml 의 grades.%s 를 줄이거나 budget 을 다시 도출하라",
                    what, grade, want, got, cap, grade));
        }
    }

    /**
     * 궤적(軌跡) — <b>보이는 모양 = 맞는 모양</b>. 히트박스 type 을 그대로 그린다 (motion_audit ②).
     *
     * <p>검은 벤다(호 — 부채꼴에 획이 남는다) · 창은 찌른다(선 — 점이 앞으로 뻗는다) ·
     * 매화검법 4타는 원을 그린다 · 보법은 지나온 자리에 잔상을 남긴다(돌).
     * 계열의 지문(weapon_styles)이 같은 '호'라도 검과 봉을 가른다.
     */
    private void trail(Player player, SkillEngine.Cast cast, String weaponClass, int stepIndex) {
        SkillEngine.Traj traj = engine.trajectory(cast.hitType());
        if (traj == null) {
            return;
        }
        SkillEngine.Style style = engine.weaponStyle(weaponClass);
        SkillEngine.SkillMotion motion = engine.motionSkill(cast.skillId());
        SkillEngine.Step step = motion == null ? null : motion.step(stepIndex);
        SkillEngine.GradeMotion grade = engine.motionGrade(cast.grade());
        // 발출(쏨)은 무공이 아니다 — 형태(forms.쏨)가 제 광선과 발사음을 갖는다.
        // 【발출이 두름·응집과 헷갈리지 않는 이유】 기가 **날아간다**: 8m 직선 광선 + 큰 발사음 + 끝의 작렬
        SkillEngine.FormMotion shot = SkillEngine.SHOT.equals(cast.skillId()) ? engine.shotForm(cast.grade()) : null;

        // ─── 3D 층 (파티클 위에 얹는다) ───
        //   참격선 — 지나간 자리가 남는다 (검을 복제하지 않는다) · 던진 암기는 정말로 날아간다 ·
        //   발출은 기가 날아간다. 전부 실패해도(예산·팩·미등록) 아래 파티클 층이 그대로 돈다
        boolean solid;
        // ★ 획의 순번은 **한 손에 한 번만** 뽑는다 — 3D 획과 파티클이 **같은 스윙**을 그려야 한다
        //   (두 번 뽑으면 검은 좌→우로 가는데 파티클은 우→좌로 간다 — 그것이 그림이 갈라지는 것이다)
        int swingTicks = (int) Math.max(cast.frames().total(), swingInterval(player));
        String stroke = nextStroke(player, weaponClass);
        if (shot != null) {
            solid = display.bolt(player, shot.name(), cast.range());
        } else {
            solid = strike(player, cast.hitType(), cast.grade(), weaponClass,
                    cast.range(), cast.angle(), swingTicks, stroke);
        }

        // 점당 파티클: 초식이 정한 개수. 격이 실렸으면 격의 궤적 파티클이 무기의 것을 덮는다
        //   (검기를 두른 검은 '검의 획'이 아니라 '기의 획'을 남긴다 — 그것이 격이 보인다는 말이다)
        String particle = shot != null ? shot.beamParticle()
                : cast.manifested() ? grade.trailParticle()
                : step != null && step.particle() != null ? step.particle()
                : "선".equals(cast.hitType()) || "시".equals(cast.hitType()) ? style.thrust() : style.arc();
        int per = shot != null ? shot.beamPerPoint() : step == null ? 1 : step.count();
        // 3D 획이 실제로 떴으면 궤적 파티클은 물러선다 (display.blend) — 둘 다 뿌리면 지저분하고 비싸다.
        // 0 으로는 만들지 않는다: 획이 지나간 자리는 어떤 경우에도 남아야 한다 (강등 ≠ 실종)
        if (solid) {
            per = engine.displayBlend().damp(per);
        }
        SkillEngine.Budget b = engine.motionBudget();

        Location eye = player.getEyeLocation();
        Vector dir = player.getLocation().getDirection().normalize();
        Vector flat = dir.clone().setY(0);
        if (flat.lengthSquared() < 1e-6) {
            flat = new Vector(1, 0, 0);
        }
        flat.normalize();

        // ★ 잔상(echo) · 먼지·먹번짐(haze) — 획이 **떠난 자리**에 남는다 (사용자 지시: 수묵 강화).
        //   초급은 회백 잔상 + 약한 먼지 (발광 거의 없음) · 상급으로 갈수록 청회→청록→옥→청백으로 오른다.
        //   【예산】 궤적 풀에서 뽑는다 (budget.trail_pool = 궤적 24 + 잔상·먹번짐 12). 밖에서 뽑으면
        //   그것은 예산이 아니다 — 그래서 아래 points 는 **남은 풀**로 계산한다 (궤적이 스스로 물러선다).
        //   ※ 예산에서 빼는 것은 **등록부가 청구한 수**이지 실제 발행량(관람자 수 × 개수)이 아니다 —
        //     풀은 설계의 눈금이고, 관람자별 상한은 SkillHud 가 따로 지킨다 (두 예산은 다른 층이다).
        int spent = 0;
        if (!"aura".equals(traj.shape())) {   // 태세·가드태세·시전은 몸에 머무는 것이다 — 떠난 자리가 없다
            Location wake = strokeWake(player, flat);
            hud.emit(wake, grade.echo(), false);
            hud.emit(wake, grade.haze(), false);
            spent = grade.echo().count() + grade.haze().count();
        }
        int points = Math.min(traj.points(),
                Math.max(1, Math.max(0, b.trailPool() - spent) / Math.max(1, per)));

        switch (traj.shape()) {
            case "arc" -> {          // ★ 호 — 부채꼴을 **시간을 두고 훑는다** (획과 같은 길·같은 시간)
                sweepArc(player, stroke, particle, per, points,
                        cast.range() * traj.radiusRatio(), cast.angle(),
                        drawTicks(weaponClass, swingTicks));
            }
            case "line", "shot" -> { // 선·시 — 앞으로 뻗는다 (찌르기·참격·투척)
                for (int i = 1; i <= points; i++) {
                    Location p = eye.clone().add(dir.clone().multiply(
                            Math.min(cast.range(), i * traj.step())));
                    hud.emit(p, particle, per, 0.05, 0.0);
                }
            }
            case "circle", "ring" -> {   // 원·진 — 사방
                double radius = cast.range() * traj.radiusRatio();
                Location center = player.getLocation().add(0, 1, 0);
                for (int i = 0; i < points; i++) {
                    double a = Math.PI * 2 * i / points;
                    hud.emit(center.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius),
                            particle, per, 0.05, 0.0);
                }
            }
            case "dash" -> {         // 돌 — 지나온 자리에 잔상 (뒤로 그린다)
                for (int i = 1; i <= points; i++) {
                    hud.emit(player.getLocation().add(0, 0.8, 0)
                                    .subtract(dir.clone().multiply(i * traj.step())),
                            particle, per, 0.08, 0.0);
                }
            }
            default -> {             // aura — 태세·가드태세·시전: 몸에 머문다
                hud.emit(player.getLocation().add(0, 1, 0), particle,
                        Math.min(per * points, b.trailPool()), 0.35, 0.0);
            }
        }
        if (shot != null) {
            // 광선의 끝에서 작렬한다 — 어디까지 갔는지 눈에 남는다 (빗나가면 그 자리에 코스트만 흩어진다)
            hud.emit(eye.clone().add(dir.clone().multiply(cast.range())), shot.burst(), false);
            sfx(player.getLocation(), shot.releaseSounds());
            return;   // 발출은 초식이 아니다 (형태의 것) — 초식의 배율이 걸릴 자리가 없다
        }
        // 스윙음 — 초식이 제 소리를 가졌으면 그것, 아니면 계열의 소리. 둘 다 초식의 배율을 탄다
        sfx(player.getLocation(), step != null && step.sound() != null ? step.sound() : style.swing(),
                soundScale(step));
    }

    // ══════════ 관리 명령 접합 (MvtCommand 가 부른다) ══════════

    /** /혼천 경지 — MVT 는 캐릭터 시트가 없다. 경지·내공을 손으로 세운다 (검증 도구) */
    public void setRealm(Player player, String realm, double naegong) {
        SkillEngine.State state = state(player);
        state.realm = realm;
        state.naegong = naegong;
        state.energy = engine.pool(naegong);
        state.armed = null;
        state.flow = 0;
        state.ultimateUses = 0;
        state.combatUntil = -1;
        energyBossBar.update(player, state);
        player.sendMessage(ChatColor.GOLD + Glyphs.realmCrest(realm) + " " + realm
                + ChatColor.WHITE + " — 내공 " + String.format("%.2f", naegong)
                + " / 내력 " + state.energy
                + ChatColor.GRAY + " · 열린 태세: "
                + (engine.armableStances(realm).isEmpty() ? "없음 (외공기뿐)"
                        : String.join(" → ", engine.armableStances(realm))));
        String stage = engine.ultimateStage(realm);
        if (stage != null) {
            player.sendMessage(ChatColor.LIGHT_PURPLE + "오의 — " + stage
                    + ChatColor.GRAY + " (Shift+F: 오의 선택 · F: 시전 · 발동권 = 아슬아슬한 성공 이상 공방 "
                    + engine.flowRequired() + "회)"
                    + (engine.isAwakening(realm) ? ChatColor.DARK_GRAY
                            + "  — 개안: 불완전 시전 (위력 절반 · 직후 내력 전소 + 내상)" : ""));
        }
    }

    /** /혼천 운기 — 운기조식 1구간 (전투 밖). 하한 1 + 순도 배율 (internal_energy recovery) */
    public void meditate(Player player) {
        SkillEngine.State state = state(player);
        int pool = engine.pool(state.naegong);
        if (pool <= 0) {
            player.sendMessage(ChatColor.GRAY + "단전이 비어 있다 — 돌릴 기가 없다.");
            return;
        }
        int before = state.energy;
        state.energy = Math.min(pool, state.energy + engine.meditationRecover(state.naegong, 1.0));
        energyBossBar.update(player, state);
        event(player.getLocation().add(0, 1, 0), "운기조식");
        player.sendMessage(ChatColor.AQUA + "한 구간을 앉았다 — 내력 " + before + " → " + state.energy
                + "/" + pool);
    }

    // ══════════ 정리 (performance.yml effects.cleanup_on) ══════════

    /**
     * <b>첫 접속의 목소리.</b> 여기 아무것도 없었다 — {@code PlayerJoinEvent} 처리기는 팩과 형체 둘뿐이었고,
     * 아무것도 모르는 사람이 들어와 <b>아무 말도 듣지 못하고</b> 빈 세계에 섰다.
     *
     * <p>세 가지를 한다:
     * <ol>
     *   <li><b>시트를 싣는다</b> — 봇의 장부가 이 몸의 경지·능력치·심법·내공이 된다.</li>
     *   <li><b>접합 안 됐으면 말한다</b> — "너는 아직 강호에 없다". 조용히 반쪽 세계에서 놀게 두지 않는다.</li>
     *   <li><b>첫 배분을 깔고 목표를 준다</b> — 빈 {@code curriculum} 은 {@code Growth.train} 이
     *       즉시 0 을 돌려준다. 그 침묵이 첫 함정이었다 ({@code player_creation.yml mvt_onboarding}).</li>
     * </ol>
     */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerLedger ledger = plugin.ledger(player.getUniqueId());
        boolean carried = syncSheet(player);   // 스냅숏에 이 몸이 있으면 시트가 실린다
        state(player);                         // 상태를 세운다 (경지는 원장에서 — 하드코딩 없음)
        Onboarding on = Onboarding.get();
        if (on == null) {
            return;
        }
        // 첫 배분 — 아직 한 번도 수련을 고르지 않은 몸에만 (플레이어의 선택을 덮지 않는다)
        boolean empty = ledger.curriculum().isEmpty();
        if (empty && !on.defaultCurriculum().isEmpty()) {
            Growth growth = Growth.get();
            on.defaultCurriculum().forEach((subject, segments) -> {
                if (growth != null && growth.subjects().containsKey(subject)) {
                    ledger.setSegments(subject, segments);
                }
            });
        }
        final boolean seeded = empty && !ledger.curriculum().isEmpty();
        final boolean linkedNow = carried || ledger.linked();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (!linkedNow) {
                nag(player, on);
                return;
            }
            String goal = on.goalFor(player.getUniqueId());
            if (goal != null) {
                player.sendMessage(on.goalPrefix() + ChatColor.WHITE + " — " + goal);
            }
            on.linkedLines().forEach(player::sendMessage);
            if (!ledger.curriculum().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                ledger.curriculum().forEach((subject, segments) ->
                        sb.append(sb.isEmpty() ? "" : " · ").append(subject).append(' ')
                                .append(segments).append("구간"));
                player.sendMessage((seeded ? ChatColor.DARK_GRAY + "  기본 배분 — " : ChatColor.DARK_GRAY
                        + "  오늘의 배분 — ") + ChatColor.GRAY + sb);
            }
        }, 40L);   // 접속 직후의 폭포(팩·타이틀) 뒤에 말한다 — 안 그러면 아무도 못 읽는다
    }

    /**
     * <b>"너는 아직 강호에 없다."</b> — 접합되지 않은 몸에게.
     *
     * <p>지금까지 미접합자는 <b>조용히 반쪽 세계에서 놀았다.</b> 그것이 가장 나쁘다: 아무리 베고 익혀도
     * 어디에도 적히지 않는데 본인은 그것을 모른다. 이제는 말한다 — 그리고 화후도 쌓이지 않는다
     * (쌓을 장부가 없다. {@link #settleTraining} 이 접합을 요구한다).
     */
    private void nag(Player player, Onboarding on) {
        if (!on.shouldNag(player.getUniqueId(), System.currentTimeMillis())) {
            return;
        }
        player.sendTitle(ChatColor.RED + on.unlinkedTitle(),
                ChatColor.YELLOW + on.unlinkedSubtitle(), 10, 70, 20);
        on.unlinkedLines().forEach(player::sendMessage);
        player.sendMessage(LinkGate.callToAction());   // 클릭 = /혼천 접속 (칠 것이 없다)
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        // ★ 떠나기 전에 원장을 굽는다 — 재기동이 아니라 로그아웃 하나로도 소멸했다
        plugin.saveLedger(id);
        states.remove(id);
        clashCounts.remove(id);
        stanceNow.remove(id);   // 지정 태세(stancePin)는 남긴다 — 몸에 밴 것은 로그아웃으로 안 풀린다
        guardDeclare.remove(id);   // 선언·허의 기록은 순간의 것 — 접속을 건너 살아남지 않는다
        lastParry.remove(id);
        lastDodge.remove(id);
        lastStanceWin.remove(id);
        lastDig.remove(id);        // 채굴의 그림자도 접속과 함께 걷힌다
        hud.forget(id);
        energyBossBar.forget(id);   // 끊긴 몸의 보스바 기록 — 연결이 죽었으니 바는 이미 없다
        display.clear(id);      // 떠난 몸의 형체는 남지 않는다
        if (Onboarding.get() != null) {
            Onboarding.get().forget(id);
        }
    }

    /** 죽은 몸의 내력은 남지 않는다 (F-P2 cleanup_on death) */
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        npcStates.remove(event.getEntity().getUniqueId());
        clashCounts.remove(event.getEntity().getUniqueId());
        display.clear(event.getEntity().getUniqueId());   // 죽은 몸의 고리도 (심장박동이 멎기 전에)
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        SkillEngine.State state = states.get(event.getPlayer().getUniqueId());
        if (state != null) {
            state.armed = null;
            state.comboIndex = 0;
        }
        display.clear(event.getPlayer().getUniqueId());   // 세계를 건너간 몸의 형체는 저쪽에 남기지 않는다
    }

    // ══════════ 도우미 ══════════

    /**
     * 이 손에 실리는 무공 — <b>원장이 고른다</b> (하드코딩된 무공표를 지운 자리).
     *
     * <p>손에 든 병기의 계열로 나갈 수 있는 무공들({@code skills.yml weapon_class}) 중,
     * <b>이 사람이 실제로 익힌</b>(원장에 일수가 쌓인) 것 하나를 고른다 — 가장 오래 판 것이 주무공이다.
     * 아무것도 안 익혔으면 {@code null} → 그 손은 <b>기본 초식</b>(basic_strike)으로 친다.
     * 예전엔 검을 들면 누구나 육합검이 나갔고(배우지 않아도), 매화검법·나한권은 <b>시전 경로가 아예 없었다</b>.
     */
    private String skillInHand(Player player) {
        String weaponClass = engine.weaponClassOf(
                player.getInventory().getItemInMainHand(), materialName(player));
        PlayerLedger ledger = plugin.ledger(player.getUniqueId());
        String best = null;
        double bestDays = 0.0;
        for (String id : engine.artsFor(weaponClass)) {
            double days = ledger.daysOf(engine.skillName(id));
            if (days > bestDays) {
                bestDays = days;
                best = id;
            }
        }
        return best;
    }

    /** 계열 공속 → 스윙 간격(틱). 혼천 병기가 아니면 0 (프레임이 전부다) */
    private long swingInterval(Player player) {
        Weapons.Series series = Weapons.seriesOf(player.getInventory().getItemInMainHand());
        return series == null ? 0L : Math.round(20.0 / series.attackSpeed());
    }

    private static String materialName(Player player) {
        Material m = player.getInventory().getItemInMainHand().getType();
        return m.isAir() ? "AIR" : m.name();
    }

    private String weaponGrade(Player player) {
        return engine.weaponGradeOf(player.getInventory().getItemInMainHand(), materialName(player));
    }

    private static Location handLocation(Player player) {
        return player.getEyeLocation().add(player.getLocation().getDirection().multiply(0.8)).subtract(0, 0.2, 0);
    }

    private Location swingLocation(Player player, SkillEngine.Cast cast) {
        return player.getEyeLocation()
                .add(player.getLocation().getDirection().multiply(Math.min(2.0, cast.range() / 2)));
    }

    private void itemCooldown(Player player, int ticks) {
        Material m = player.getInventory().getItemInMainHand().getType();
        if (!m.isAir() && ticks > 0) {
            player.setCooldown(m, ticks);   // 바닐라 아이템 쿨다운 스와이프 = 스킬 쿨다운 (mc_action_mapping 2장)
        }
    }

    /**
     * <b>날이 바뀌면 수련이 갈린다.</b>
     *
     * <p>여기 있는 이유: 원래 {@code rollDay} 는 <b>몹을 죽일 때만</b> 불렸다 ({@code HuntListener}).
     * 수련이 사냥에 묶여 있으면 <b>"앉아서 쌓는 몸"이 성립하지 않는다</b> — 외공·내공·심안은
     * 베어서 느는 것이 아니라 <b>앉아서</b> 느는 것이고, 실전 화후만이 손(초식)에 쌓인다.
     * 그래서 정산은 모두에게 매일 온다. 사냥은 그 위에 얹힌다.
     *
     * <p>넘치는 몫은 버린다 — <b>천장이 몰빵을 꺾는 자리</b>다. 신법을 끝까지 민 자는 어느 날부터
     * 하루치의 일부가 허공에 흩어지는 것을 본다. 배분을 바꾸라는 세계의 말이다.
     */
    private void settleTraining(Player player, SkillEngine.State state) {
        Growth growth = Growth.get();
        if (growth == null) {
            return;
        }
        PlayerLedger ledger = plugin.ledger(player.getUniqueId());

        // ★ 시계는 하나다 — 봇의 세계일. 예전에는 getFullTime()/24000 (마크의 20분 하루)이었고,
        //   그래서 `Growth.attrCostDays`(능력치 +1 = 360일)가 봇의 360일과 **72배 다른 시계** 위에서 돌았다.
        //   마크에 닷새 앉아 있으면 봇에서 1년 걸릴 능력치가 올랐다. 이제 달력은 봇이 굴리고 마크가 읽는다.
        long day = WorldBridge.worldDay();
        if (day <= 0) {
            return;   // 스냅숏이 없다 = 봇이 꺼져 있다. 장부는 봇의 것이므로 움직이지 않는다
        }
        if (!ledger.linked()) {
            return;   // 강호에 없는 몸에는 쌓을 장부가 없다 (onJoin 의 nag 가 그 이유를 말했다)
        }
        if (!ledger.isNewDay(day)) {
            return;
        }
        double wasted = growth.train(ledger, state.realm, 1.0);
        ledger.addWasted(wasted);
        // 몸에 실제로 들어간 일치 — 봇의 `화후_원장` 으로 간다 (범인 → 삼류의 관문: 기초 단련 3개월)
        ledger.pendTrain(Math.max(0.0, 1.0 - wasted));
        ledger.rollDay(day);
        if (wasted > 0.0) {
            player.sendMessage(org.bukkit.ChatColor.DARK_GRAY + "수련 "
                    + String.format("%.1f", wasted) + "일치가 흩어졌다 — 천장에 닿았다 (/혼천 수련)");
        }
        pushLedger(player, ledger, state);
    }

    /**
     * <b>몸에서 쌓인 것을 장부로 올린다.</b> 시트를 적는 손은 봇 하나뿐이다 — 마크는 규칙의 계산기다.
     *
     * <p>마크는 낙관적으로 제 거울에 먼저 더해 두었다(그래야 같은 틱에 몸이 반응한다). 그 값은 곧
     * 스냅숏이 <b>절대값으로 덮어쓴다</b> — 더하는 것이 아니라 덮는 것이므로 <b>이중 계상이 원리적으로 불가능</b>하다.
     */
    void pushLedger(Player player, PlayerLedger ledger, SkillEngine.State state) {
        if (!ledger.hasPending() || !ledger.linked()) {
            return;
        }
        WorldBridge.cultivationLogged(player.getUniqueId(), player.getName(),
                state == null ? ledger.realm(engine.baseRealm()) : state.realm, ledger.takePending());
    }
}
