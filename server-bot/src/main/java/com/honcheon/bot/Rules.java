package com.honcheon.bot;

import com.honcheon.core.rules.EconomyEngine;
import com.honcheon.core.rules.InternalEnergyEngine;
import com.honcheon.core.rules.GenderEngine;
import com.honcheon.core.rules.JudgmentEngine;
import com.honcheon.core.rules.ProgressionEngine;
import com.honcheon.core.rules.RulesConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * config/*.yml 로더 + 룰 엔진 묶음 — 단일 진실 원천의 봇 쪽 손잡이.
 */
public final class Rules {

    public final JudgmentEngine judgment;
    public final ProgressionEngine progression;
    // 성장 v3 레벨 (cultivation.yml levels — B-135 단계 4)
    public final boolean levelsEnabled;
    public final double xpBase;
    public final double xpGrowth;
    public final int pointsPerLevel;
    /** 경지 → 원장 캡 c² ({@code cultivation.yml levels.raw_attribute_cap_by_realm}) */
    public final Map<String, Integer> rawCapByRealm;
    /** 경지 → 자격 레벨 N_k ({@code cultivation.yml levels.qualifying_level}) — 승급 이중 관문 */
    public final Map<String, Integer> qualifyingLevel;
    /** ★A안 내공 통일 ({@code cultivation.yml levels.naegong_unified}) — 내력 풀 = √원장[내공] */
    public final boolean naegongUnified;
    /** 처치 XP 등급 계수 ({@code levels.xp_sources.combat.grade_coefficient} — 잡졸/정예/두목) */
    public final Map<String, Double> xpGradeCoef;
    public final Map<String, Integer> questXp;
    public final EconomyEngine economy;
    public final InternalEnergyEngine energy;
    public final Map<String, Object> dispositionTest;
    public final Map<String, Object> playerCreation;
    /** 세력 입문 루트 — 직행(direct_approach)·게이트·의뢰 주입의 단일 원천 */
    public final Routes routes;
    /** NPC 사망 연쇄 — 서비스 공백·후계·소문·의뢰 주입의 단일 원천 */
    public final Deaths deaths;
    /** 소문망 (단계 4 B) — 전파(망별 속도·왜곡)·감쇠·세력 인지의 단일 원천 */
    public final Rumors rumors;
    /**
     * 세력 반응 <b>규칙</b> (단계 4 C) — 주목·우호 2축, 반응 사다리·감쇠의 단일 원천.
     *
     * <p><b>장부가 아니다.</b> 장부는 {@code faction_standing} 표 하나뿐이고, 그 문은
     * {@code domain.FactionService} 다. 전에는 여기에 봇의 {@code Factions} 클래스가 있었고
     * core 엔진과 <b>같은 산수를 두 번</b> 구현했다 — 그 쌍둥이는 죽었다.
     */
    public final com.honcheon.core.rules.FactionReactionEngine factions;
    /** 세력 정치 (단계 5) — 명분·연합·관무불가침의 단일 원천 (세력 대 세력) */
    public final Politics politics;
    /** 혈채 — ★ 감쇠하지 않는 유일한 축 (faction_reaction.yml blood_debt) */
    public final BloodDebt bloodDebt;
    /** 죽음과 유산 (단계 4 A) — 부상 사다리·사망 위기·상속·피의 장부의 단일 원천 */
    public final Legacy legacy;
    /** 기연 등록부 (fortune_encounters.yml) — ★ 관문 수치를 코드가 짓지 않는다 (방문 30·의뢰 15·사흘) */
    public final Fortunes fortunes;
    /**
     * 서장 등록부 (seojang.yml) — ★ <b>글·선택지·저항값이 사는 곳.</b>
     *
     * <p>서장은 이제 <b>마크의 책</b>으로 흐른다 (디스코드 스레드는 죽었다). 그러나 <b>그리는 것은
     * 봇이다</b> — LLM 도 판정도 시트도 여기에만 있다. 마크는 서책이고 봇이 저자다.
     */
    public final Seojang seojang;
    /**
     * 지역 상태 <b>규칙</b> (region_state.yml) — 사건 델타 + ★ 자연 회복.
     *
     * <p><b>장부가 아니다.</b> 장부는 {@code regions} 표 하나뿐이다 ({@code Db.region()}/{@code nudgeRegion}).
     * 이 엔진은 상태를 들고 있지 않으므로 두 세계로 갈라질 수가 없다 — 그것이 이 축의 요점이다.
     */
    public final com.honcheon.core.rules.RegionStateEngine regions;
    private final Map<String, Object> judgmentCfg;
    private final Map<String, Object> economyCfg;
    private final Map<String, Object> llmCfg;
    private final Map<String, Object> npcsCfg;
    private final Map<String, Object> rumorCfg;
    private final Map<String, Object> timeCfg;
    private final Map<String, Object> questCfg;
    private final Map<String, Object> regionCfg;
    private final Map<String, Object> innateQiCfg;
    private final Map<String, Object> factionsCfg;
    /** 문파 생활 — 계급 사다리·공적·문규, 그리고 ★ 문파 상태(sect_state.internal_burden) */
    private final Map<String, Object> sectLifeCfg;
    /** 심법 — ★ 은폐 가능 여부가 두 어둠의 운명을 가른다 (simbeop.yml simbeop.&lt;id&gt;.stealth_option) */
    private final Map<String, Object> simbeopCfg;
    /** 무명(無名) 등록부 (npcs/populace.yml) — 행인의 이름·관계, 그리고 무명 의뢰의 결말표 */
    private final Map<String, Object> populaceCfg;
    /** 접합의 문 (world_bridge.yml identity.gate) — 버튼·모달의 문장. 코드는 문장을 지어내지 않는다 */
    private final Map<String, Object> gateCfg;
    /** 신원 접합 본문 (world_bridge.yml identity) — ★ 자물쇠가 사는 곳: TTL·쿨다운·1:1·토큰 */
    private final Map<String, Object> identityCfg;
    /**
     * 안내판 (discord_panel.yml panel) — <b>디스코드에서 명령을 치지 않게 하는 판.</b>
     *
     * <p>판의 글·버튼의 이름·<b>못 누르는 이유</b>가 전부 여기 있다. 코드는 <b>상태를 고를 뿐</b>
     * 문장을 짓지 않는다 ({@code /접합문} 의 gate.discord 와 같은 문법).
     */
    private final Map<String, Object> panelCfg;

    @SuppressWarnings("unchecked")
    public Rules(Path configDir) {
        this.judgmentCfg = RulesConfig.load(configDir.resolve("judgment.yml"));
        this.judgment = new JudgmentEngine(judgmentCfg);
        Map<String, Object> cultivationCfg = RulesConfig.load(configDir.resolve("cultivation.yml"));
        this.progression = new ProgressionEngine(
                cultivationCfg,
                RulesConfig.load(configDir.resolve("training.yml")));
        // ─── 성장 v3 레벨 (cultivation.yml levels — B-135 단계 4 · 사용자 확정 2026-07-24) ───
        Map<String, Object> levels = RulesConfig.section(cultivationCfg, "levels");
        this.levelsEnabled = Boolean.TRUE.equals(levels.get("enabled"));
        Map<String, Object> curve = RulesConfig.section(levels, "xp_curve");
        this.xpBase = curve.get("base") instanceof Number nb ? nb.doubleValue() : 0.0;
        this.xpGrowth = curve.get("growth") instanceof Number ng ? ng.doubleValue() : 1.0;
        this.pointsPerLevel = levels.get("points_per_level") instanceof Number np ? np.intValue() : 0;
        Map<String, Integer> qxp = new java.util.LinkedHashMap<>();
        RulesConfig.section(RulesConfig.section(levels, "xp_sources"), "quests")
                .forEach((k, v) -> qxp.put(k, v instanceof Number nq ? nq.intValue() : 0));
        this.questXp = java.util.Collections.unmodifiableMap(qxp);
        // 원장 캡 c² (경지별) — 배분 손이 지키는 천장 (§8.5 · 캡 표는 등록부가 정본)
        Map<String, Integer> caps = new java.util.LinkedHashMap<>();
        RulesConfig.section(levels, "raw_attribute_cap_by_realm")
                .forEach((k, v) -> caps.put(k, v instanceof Number nc ? nc.intValue() : 0));
        this.rawCapByRealm = java.util.Collections.unmodifiableMap(caps);
        // 처치 XP 등급 계수 (levels.xp_sources.combat.grade_coefficient) — 비면 처치 XP 미배선 (마크와 동일 규약)
        Map<String, Double> gcoef = new java.util.LinkedHashMap<>();
        RulesConfig.section(RulesConfig.section(
                RulesConfig.section(levels, "xp_sources"), "combat"), "grade_coefficient")
                .forEach((k, v) -> gcoef.put(k, v instanceof Number ngc ? ngc.doubleValue() : 0.0));
        this.xpGradeCoef = java.util.Collections.unmodifiableMap(gcoef);
        // ★A안 내공 통일 (사용자 확정 2026-07-24) — 내력 풀의 내공 실수치 = √원장[내공]
        this.naegongUnified = Boolean.TRUE.equals(levels.get("naegong_unified"));
        // 자격 레벨 N_k (경지별) — 승급 이중 관문의 '자격' 축 (사건 마크가 '문' — cultivation_v3_levels.md §5)
        Map<String, Integer> quals = new java.util.LinkedHashMap<>();
        RulesConfig.section(levels, "qualifying_level")
                .forEach((k, v) -> quals.put(k, v instanceof Number nq ? nq.intValue() : 0));
        this.qualifyingLevel = java.util.Collections.unmodifiableMap(quals);
        this.economyCfg = RulesConfig.load(configDir.resolve("economy.yml"));
        this.economy = new EconomyEngine(economyCfg);
        this.energy = new InternalEnergyEngine(RulesConfig.load(configDir.resolve("internal_energy.yml")));
        this.dispositionTest = RulesConfig.load(configDir.resolve("disposition_test.yml"));
        this.playerCreation = RulesConfig.load(configDir.resolve("player_creation.yml"));
        this.genderEngine = GenderEngine.of(playerCreation);   // 성별 규칙의 단일 원천 (core)
        this.llmCfg = RulesConfig.load(configDir.resolve("llm.yml"));
        this.npcsCfg = RulesConfig.load(configDir.resolve("npcs/cheongha_npcs.yml"));
        this.rumorCfg = RulesConfig.load(configDir.resolve("rumor.yml"));
        this.timeCfg = RulesConfig.load(configDir.resolve("time.yml"));
        this.questCfg = RulesConfig.load(configDir.resolve("quest_generation.yml"));
        this.routes = new Routes(RulesConfig.load(configDir.resolve("faction_entry_routes.yml")));
        this.deaths = new Deaths(RulesConfig.load(configDir.resolve("npc_death.yml")));
        this.rumors = new Rumors(rumorCfg);
        Map<String, Object> factionReactionCfg = RulesConfig.load(
                configDir.resolve("faction_reaction.yml"));
        this.factions = new com.honcheon.core.rules.FactionReactionEngine(factionReactionCfg);
        this.bloodDebt = new BloodDebt(factionReactionCfg);
        this.simbeopCfg = RulesConfig.load(configDir.resolve("simbeop.yml"));
        this.fortunes = new Fortunes(
                RulesConfig.load(configDir.resolve("fortune_encounters.yml")),
                cultivationCfg, simbeopCfg);
        this.politics = new Politics(RulesConfig.load(configDir.resolve("faction_politics.yml")));
        this.legacy = new Legacy(RulesConfig.load(configDir.resolve("death_and_legacy.yml")));
        this.seojang = new Seojang(configDir);   // ★ 서장의 글 — 코드가 아니라 등록부가 진다
        this.regionCfg = RulesConfig.load(configDir.resolve("regions/cheongha_hyeon.yml"));
        this.regions = new com.honcheon.core.rules.RegionStateEngine(
                RulesConfig.load(configDir.resolve("region_state.yml")));
        this.populaceCfg = RulesConfig.load(configDir.resolve("npcs/populace.yml"));
        this.innateQiCfg = RulesConfig.load(configDir.resolve("internal_energy.yml"));
        this.factionsCfg = RulesConfig.load(configDir.resolve("factions.yml"));
        this.sectLifeCfg = RulesConfig.load(configDir.resolve("sect_life.yml"));
        this.identityCfg = RulesConfig.section(
                RulesConfig.load(configDir.resolve("world_bridge.yml")), "identity");
        this.gateCfg = RulesConfig.section(identityCfg, "gate");
        // ★ 안내판의 등록부가 깨져도 **봇은 죽지 않는다** (초기화가 그렇듯이 — Reset.locked).
        //   판만 못 선다. 「빌드 통과 ≠ 기동 성공」 — 서식 한 줄이 플러그인 전체를 안 켜지게 한 날이 있었다.
        Map<String, Object> panel;
        try {
            panel = sub(RulesConfig.load(configDir.resolve("discord_panel.yml")), "panel");
        } catch (RuntimeException brokenRegistry) {
            System.err.println("안내판 등록부를 못 읽었다 (판만 잠긴다): " + brokenRegistry.getMessage());
            panel = Map.of();
        }
        this.panelCfg = panel;
    }

    // ─── 안내판 (discord_panel.yml panel) — ★ 코드는 문장을 지어내지 않는다 ───
    //
    // ★ {@link RulesConfig#section} 을 안 쓰는 이유: 그것은 **없으면 던진다.** 안내판은 사람이 보는 판이고,
    //   등록부의 한 칸이 빈 것과 봇이 죽는 것은 **같은 무게가 아니다.** 여기서는 조용히 fallback 으로 간다
    //   (그리고 그 빈 칸은 `tools/panel_audit.py` 가 짖는다 — 침묵하는 것은 눈이 아니라 코드다).

    /** 안내판이 등록부를 못 읽었는가 — 그러면 판을 세울 수 없다 (그리고 그렇게 말한다) */
    public boolean panelLocked() {
        return panelCfg.isEmpty();
    }

    /** 판의 문장 하나 — {@code panel.board.<key>}. 없으면 fallback (gateText 와 같은 문법) */
    public String panelBoard(String key, String fallback) {
        return text(sub(panelCfg, "board"), key, fallback);
    }

    /** 「내 자리」의 문장 — {@code panel.me.<key>} */
    public String panelMe(String key, String fallback) {
        return text(sub(panelCfg, "me"), key, fallback);
    }

    /** 상태별 첫 문장 — {@code panel.me.states.<state>.say}. 없으면 빈 문자열 */
    public String panelStateSay(String state) {
        return text(panelState(state), "say", "");
    }

    /**
     * 상태별 버튼 — {@code panel.me.states.<state>.buttons}.
     *
     * <p><b>어떤 버튼이 뜨는가는 등록부가 정한다.</b> 코드는 그중 <b>지금 못 누르는 것</b>을 빼고
     * (그리고 <b>왜 뺐는지 말하고</b>) 나머지를 그린다.
     */
    public java.util.List<String> panelStateButtons(String state) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (panelState(state).get("buttons") instanceof java.util.List<?> list) {
            list.forEach(b -> out.add(String.valueOf(b)));
        }
        return out;
    }

    /** 등록된 상태 이름 전부 (눈이 쓴다 — 코드가 만드는 상태가 전부 등록돼 있는가) */
    public java.util.Set<String> panelStates() {
        return sub(sub(panelCfg, "me"), "states").keySet();
    }

    /** 버튼의 이름 — {@code panel.buttons.<key>.label}. 없으면 키 그대로 (지어내지 않는다) */
    public String panelButtonLabel(String key) {
        return text(sub(sub(panelCfg, "buttons"), key), "label", key);
    }

    /** 버튼의 결 — primary · secondary · danger (등록부에 없으면 secondary) */
    public String panelButtonStyle(String key) {
        return text(sub(sub(panelCfg, "buttons"), key), "style", "secondary");
    }

    /** 등록된 버튼 키 전부 (눈이 쓴다 — 상태가 부르는 버튼이 전부 등록돼 있는가) */
    public java.util.Set<String> panelButtonKeys() {
        return sub(panelCfg, "buttons").keySet();
    }

    /** ★★ 못 누르는 이유 — {@code panel.locks.<key>}. <b>침묵 금지</b>의 유일한 원천 */
    public String panelLock(String key, String fallback) {
        return text(sub(panelCfg, "locks"), key, fallback);
    }

    /** 되돌리기 판의 문장 — {@code panel.reset.<key>} */
    public String panelReset(String key, String fallback) {
        return text(sub(panelCfg, "reset"), key, fallback);
    }

    /** world_meta 키 — 안내판이 선 채널 */
    public String panelChannelMeta() {
        return text(panelCfg, "channel_meta", "안내판:채널");
    }

    /** 관리자에게만 보이는 꼬리말 — 관리자의 손은 <b>버튼이 아니라 명령</b>이다 (한 번 치고 만다) */
    public String panelAdminNote() {
        return text(panelCfg, "admin_note", "");
    }

    private Map<String, Object> panelState(String state) {
        return sub(sub(sub(panelCfg, "me"), "states"), state);
    }

    /** 없으면 빈 맵 — <b>던지지 않는다</b> (RulesConfig.section 과의 유일한 차이) */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> root, String key) {
        Object v = root == null ? null : root.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    private static String text(Map<String, Object> root, String key, String fallback) {
        Object v = root.get(key);
        return v == null ? fallback : String.valueOf(v).strip();
    }

    // ─── 신원 접합의 자물쇠 (world_bridge.yml identity) — ★ 값은 여기서 발명하지 않는다 ───

    /** 청의 수명 — 지금 화면 앞에 있는 사람에게 묻는 것이다. 오래 살면 안 된다 (기본 120초) */
    public int linkTtlSeconds() {
        Object v = identityCfg.get("ttl_seconds");
        return v instanceof Number n ? Math.max(15, n.intValue()) : 120;
    }

    /** ★ 연타 방지 — 한 사람이(그리고 한 몸에게) 이 초 안에 두 번 청할 수 없다 (기본 60초) */
    public int linkCooldownSeconds() {
        Object v = identityCfg.get("cooldown_seconds");
        return v instanceof Number n ? Math.max(0, n.intValue()) : 60;
    }

    /** 토큰의 알파벳 — <b>열쇠가 아니다</b> (지목일 뿐. 수락은 mc_uuid 가 판정한다) */
    public String linkTokenAlphabet() {
        Object v = identityCfg.get("token_alphabet");
        return v == null || String.valueOf(v).isBlank()
                ? "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" : String.valueOf(v);
    }

    public int linkTokenLength() {
        Object v = identityCfg.get("token_length");
        return v instanceof Number n ? Math.max(6, n.intValue()) : 10;
    }

    // ─── 접합의 문 (world_bridge.yml identity.gate) ───

    /**
     * 문의 문장 하나 — {@code gate.discord.<key>}. 등록부에 없으면 fallback (코드가 지어내지 않는다).
     * 여기 오는 값은 전부 사람이 읽는 문장이다. 자물쇠는 이 표에 없다 (그것은 identity 본문이다).
     */
    public String gateText(String key, String fallback) {
        Object v = RulesConfig.section(gateCfg, "discord").get(key);
        return v == null ? fallback : String.valueOf(v).strip();
    }

    /** world_meta 키 — 접속의 문이 선 채널·길드 (등록부가 이름을 정한다) */
    public String gateMetaKey(String which, String fallback) {
        Object v = gateCfg.get(which);
        return v == null ? fallback : String.valueOf(v);
    }

    /**
     * <b>초대 링크</b> — 서버에 <b>아직 안 들어온 사람</b>의 유일한 길
     * ({@code world_bridge.yml identity.gate.invite_url}).
     *
     * <p>채널 URL 은 이미 서버 안에 있는 사람에게만 쓸모가 있다. 밖에 있는 사람에게는
     * <b>초대</b>가 있어야 한다 — 이 서버는 공개가 아니다.
     *
     * <p><b>★ 값은 코드에 없다.</b> 등록부에서만 온다. 그리고 등록부의 기본값은 <b>빈 칸</b>이다 —
     * 실제 링크는 저장소에 커밋되지 않는다 (관리자가 제 손으로 넣는다). 비어 있으면 {@code null} 을
     * 돌려주고, 그때 마크는 초대 버튼을 <b>띄우지 않는다</b> (없는 문을 걸지 않는다).
     *
     * <p>스킴이 없으면 {@code https://} 를 붙인다 — 마크 클라이언트는 http/https 만 열기 때문에
     * {@code discord.gg/XXXX} 를 그대로 걸면 열리지 않는다 (1.21.5+ 는 파싱조차 거부한다).
     *
     * @return 정규화된 https URL, 또는 등록부가 비었으면 {@code null}
     */
    public String gateInviteUrl() {
        Object v = gateCfg.get("invite_url");
        if (v == null) {
            return null;
        }
        String url = String.valueOf(v).strip();
        if (url.isEmpty()) {
            return null;
        }
        // 사람이 스킴을 빼고 적는 일이 잦다 — 붙여 준다 (discord:// 는 마크가 거부하므로 https 로 강제)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        return url;
    }

    // ─── 무명(無名) 등록부 (npcs/populace.yml) — 마크의 마을이 봇의 장부에 닿는 곳 ───
    //
    // ★ 왜 봇이 이 파일을 읽는가: MVT 는 무명 의뢰가 **어떻게 끝났는지**(rule · outcome)만 다리에 싣는다.
    //   그 결말이 지역에 얼마를 얹는지(민심 ±1 · 치안 ±1 · 경제 -1)는 등록부가 정하고, 그것을 제 표에
    //   적는 것은 장부의 주인인 봇이다. 같은 표를 양쪽이 계산해 각자 더하면 — 세계가 둘이 된다.

    /** 무명의 이름 — 등록부에 있는 사람만. 없으면 null (코드는 이름을 지어내지 않는다) */
    @SuppressWarnings("unchecked")
    public String populaceName(String id) {
        Map<String, Object> people = RulesConfig.section(populaceCfg, "people");
        Object person = people.get(id);
        return person instanceof Map<?, ?> p
                ? String.valueOf(((Map<String, Object>) p).getOrDefault("name", id)) : null;
    }

    /**
     * 무명 의뢰의 결말이 지역에 얹는 값 — {@code quests.rules.<rule>.<outcome>.region}.
     * outcome ∈ {@code success · fail_body · on_expire}. 등록부에 없으면 빈 맵 (아무 일도 없다).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Integer> populaceQuestRegion(String rule, String outcome) {
        Map<String, Object> block = populaceQuestBlock(rule, outcome);
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        if (block.get("region") instanceof Map<?, ?> region) {
            ((Map<String, Object>) region).forEach((stat, value) -> {
                if (value instanceof Number n) {
                    out.put(stat, n.intValue());
                }
            });
        }
        return out;
    }

    /**
     * ★ 살해자가 유족의 의뢰를 완수했을 때 혈교가 얹는 우호 —
     * {@code quests.rules.<rule>.killer_irony.blood_favor}.
     *
     * <p>"어미가 당신이 가리킨 자리를 본다. 당신의 손을 본다. 그리고 다시 시신을 본다 —
     * 아무것도 묻지 않는다. 삯을 쥐여 준다." <b>혈교는 그것을 자격으로 읽는다.</b>
     */
    public int populaceQuestIrony(String rule) {
        Object v = populaceQuestBlock(rule, "killer_irony").get("blood_favor");
        return v instanceof Number n ? n.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> populaceQuestBlock(String rule, String outcome) {
        Map<String, Object> quests = RulesConfig.section(populaceCfg, "quests");
        Object rules = quests.get("rules");
        Object one = rules instanceof Map<?, ?> m ? ((Map<String, Object>) m).get(rule) : null;
        Object block = one instanceof Map<?, ?> r ? ((Map<String, Object>) r).get(outcome) : null;
        return block instanceof Map<?, ?> b ? (Map<String, Object>) b : Map.of();
    }

    // ─── 문파 상태 (sect_life.yml sect_state.internal_burden) — 연합의 브레이크 ───
    //
    // "문파가 제 코가 석 자면 남의 싸움에 못 낀다."
    // 이 축이 없어서 연합이 너무 쉽게 뭉쳤다 — 양(+) 보정이 통째로 빠져 있었다.

    @SuppressWarnings("unchecked")
    private Map<String, Object> internalBurdenCfg() {
        Map<String, Object> state = RulesConfig.section(sectLifeCfg, "sect_state");
        return (Map<String, Object>) state.get("internal_burden");
    }

    /** sect_state.internal_burden.scale = [0, 6] — 상한 */
    public int burdenMax() {
        @SuppressWarnings("unchecked")
        List<Object> scale = (List<Object>) internalBurdenCfg().get("scale");
        return scale == null ? 6 : RulesConfig.intValue(scale.get(1));
    }

    /** sect_state.internal_burden.decay.every_days = 30 — 사정은 느리게 풀린다 (favor 와 같은 주기) */
    @SuppressWarnings("unchecked")
    public int burdenDecayEveryDays() {
        Map<String, Object> decay = (Map<String, Object>) internalBurdenCfg().get("decay");
        return decay == null ? 30 : RulesConfig.intValue(decay.get("every_days"));
    }

    /**
     * sect_state.internal_burden.sources.&lt;키&gt;.burden — 사건이 얹는 부담.
     * 장문_교체기 3 · 내분_알력 2 · 사상자_누적 2 · 재정_궁핍 1 · 폐관_은둔 2 · **다른_전쟁_중 4**
     */
    @SuppressWarnings("unchecked")
    public int burdenSource(String key) {
        Map<String, Object> sources = (Map<String, Object>) internalBurdenCfg().get("sources");
        Object e = sources == null ? null : sources.get(key);
        if (!(e instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("등록되지 않은 문파 사정: " + key);
        }
        return RulesConfig.intValue(((Map<String, Object>) m).get("burden"));
    }

    /** 등록된 사정의 이름들 (관리자 명령의 선택지 원천 — 신규 사정 발명 금지) */
    @SuppressWarnings("unchecked")
    public java.util.Set<String> burdenSourceKeys() {
        Map<String, Object> sources = (Map<String, Object>) internalBurdenCfg().get("sources");
        return sources == null ? java.util.Set.of() : sources.keySet();
    }

    // ─── 심법 (simbeop.yml) — ★ 은폐 가능 여부 한 줄이 두 어둠의 운명을 가른다 ───
    //
    // 마교(천마무극공)는 숨을 수 있다 → 잠식한다. 혈교(혈기심공)는 못 숨는다 → 즉시 토벌.
    // 그래서 **운기조식을 목격당하는 순간** 혈교도의 은밀함은 끝난다 (blood_debt B6).

    /** 표시 이름(혈기심공)으로 심법 항목을 찾는다 — 시트에 적히는 것은 이름이다 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> simbeopByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Map<String, Object> catalog = RulesConfig.section(simbeopCfg, "simbeop");
        for (Object value : catalog.values()) {
            if (value instanceof Map<?, ?> s && name.equals(s.get("name"))) {
                return (Map<String, Object>) s;
            }
        }
        return null;
    }

    /** 마공인가 (demonic: true) — 흡성(혈교) 또는 연수(마교) */
    public boolean isMagong(String simbeopName) {
        Map<String, Object> s = simbeopByName(simbeopName);
        return s != null && Boolean.TRUE.equals(s.get("demonic"));
    }

    /**
     * ★ 운기 색을 숨길 수 있는가 (stealth_option). 마공인데 숨길 수 없으면 —
     * <b>운기조식이 곧 자백이다.</b> 혈기심공은 false 다 (그리고 그것이 혈교의 수명을 정한다).
     */
    public boolean canHideCirculation(String simbeopName) {
        Map<String, Object> s = simbeopByName(simbeopName);
        return s != null && Boolean.TRUE.equals(s.get("stealth_option"));
    }

    /** judgment.yml formula.npc_fixed_bonus — NPC는 주사위 대신 고정값 (+7) */
    public int npcFixedBonus() {
        return RulesConfig.intValue(RulesConfig.section(judgmentCfg, "formula").get("npc_fixed_bonus"));
    }

    /** judgment.yml formula.situation_modifier_cap — 상황 보정 합계 절대값 상한 (±5) */
    public int situationCap() {
        return RulesConfig.intValue(
                RulesConfig.section(judgmentCfg, "formula").get("situation_modifier_cap"));
    }

    /** judgment.yml situation_modifiers.condition — 경상 -1 · 중상 -2 · 빈사 -3 (전투 밖 판정에도 지속) */
    @SuppressWarnings("unchecked")
    public int conditionModifier(String wound) {
        if (wound == null || wound.isBlank()) {
            return 0;
        }
        Map<String, Object> mods = RulesConfig.section(judgmentCfg, "situation_modifiers");
        Map<String, Object> condition = (Map<String, Object>) mods.get("condition");
        Object value = condition.get(wound);
        return value instanceof Number n ? n.intValue() : 0;
    }

    /** internal_energy.yml innate_qi.burn_uses.회생.cost_years — 빈사 사망 위기 자동 통과 1회의 값 */
    @SuppressWarnings("unchecked")
    public int revivalCostYears() {
        Map<String, Object> innate = RulesConfig.section(innateQiCfg, "innate_qi");
        Map<String, Object> burns = (Map<String, Object>) innate.get("burn_uses");
        Map<String, Object> revival = (Map<String, Object>) burns.get("회생");
        return RulesConfig.intValue(revival.get("cost_years"));
    }

    /** internal_energy.yml innate_qi.total_at_birth — 수명 100년 (전원 균등, 재능과 무관) */
    public int innateQiTotal() {
        return RulesConfig.intValue(RulesConfig.section(innateQiCfg, "innate_qi").get("total_at_birth"));
    }

    /**
     * 청하현 등록 사건 (regions/cheongha_hyeon.yml incidents) — 세계 개막 소문의 원천.
     * 신규 사건 발명 없음: 이미 등록된 3건(사파 연락책·북로 도적·열병)이 첫날부터 돌고 있어야 한다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> incidentsRegistry() {
        Object incidents = regionCfg.get("incidents");
        return incidents instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** 등록 NPC 의 장소 키 (cheongha_inn · market · request_office …) — 그가 어느 소문망에 사는가 */
    public String npcLocation(String key) {
        Map<String, Object> npc = npcByKey(key);
        return npc == null ? null : String.valueOf(npc.get("location"));
    }

    /** 등록 NPC 의 소속 세력 (mingan · haomun · orthodox_heroes · sangdan · gwan_gun …) */
    public String npcFaction(String key) {
        Map<String, Object> npc = npcByKey(key);
        return npc == null ? null : String.valueOf(npc.get("faction"));
    }

    /**
     * 세력 id → 표시 이름 — factions.yml aliases 를 뒤집어 읽는다 (haomun → 하오문).
     * 별칭표가 곧 등록부다: 여기에 없는 id 는 그대로 보여 준다 (신규 세력 발명 금지).
     */
    @SuppressWarnings("unchecked")
    public String factionName(String id) {
        Object aliases = factionsCfg.get("aliases");
        if (aliases instanceof Map<?, ?> m) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                if (id.equals(String.valueOf(e.getValue()))) {
                    return e.getKey();
                }
            }
        }
        return id;
    }

    /**
     * 표시 이름·별칭 → 세력 id (factions.yml aliases 그대로. 화산파 → hwasan).
     * 이미 id 인 것은 그대로 돌려준다. 등록부에 없으면 null — **세력을 발명하지 않는다.**
     */
    @SuppressWarnings("unchecked")
    public String factionId(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return null;
        }
        String key = nameOrId.strip();
        Object aliases = factionsCfg.get("aliases");
        if (aliases instanceof Map<?, ?> m && ((Map<String, Object>) m).get(key) != null) {
            return String.valueOf(((Map<String, Object>) m).get(key));
        }
        return key;   // id 로 온 것 — 등록 여부는 부르는 쪽이 coalitionOf 로 확인한다
    }

    /** quest_generation.yml grade_ladder — 등급 사다리 (낮은 것부터). 등급 상한 집행의 원천 */
    @SuppressWarnings("unchecked")
    public List<String> gradeLadder() {
        Map<String, Object> ladder = RulesConfig.section(questCfg, "grade_ladder");
        List<Object> rungs = (List<Object>) ladder.get("rungs");
        return rungs.stream().map(r -> String.valueOf(((Map<String, Object>) r).get("grade"))).toList();
    }

    /** rumor.yml generation.initial_accuracy — 직접_목격 90 · 간접_전문 70 · 흔적_추론 50 */
    @SuppressWarnings("unchecked")
    public int initialAccuracy(String kind) {
        Map<String, Object> gen = RulesConfig.section(rumorCfg, "generation");
        Map<String, Object> table = (Map<String, Object>) gen.get("initial_accuracy");
        return RulesConfig.intValue(table.get(kind));
    }

    /** judgment.yml static_difficulty — 난이도 기준치 (쉬움 10 · 보통 12 · 어려움 14 …) */
    public int difficulty(String band) {
        Map<String, Object> table = RulesConfig.section(judgmentCfg, "static_difficulty");
        return RulesConfig.intValue(table.get(band));
    }

    /** economy.yml trading.black_market.rate — 장물 매입가 (장쇠 사후 마삼의 좌판) */
    @SuppressWarnings("unchecked")
    public double blackMarketRate() {
        Map<String, Object> trading = RulesConfig.section(economyCfg, "trading");
        Map<String, Object> black = (Map<String, Object>) trading.get("black_market");
        return ((Number) black.get("rate")).doubleValue();
    }

    /** economy.yml price_table 하위 표의 값 (범위면 하한) — 노자 산출의 원천 */
    @SuppressWarnings("unchecked")
    public int price(String category, String item) {
        Map<String, Object> table = RulesConfig.section(economyCfg, "price_table");
        Object value = RulesConfig.section(table, category).get(item);
        if (value instanceof List<?> range) {
            return ((Number) range.get(0)).intValue();
        }
        return ((Number) value).intValue();
    }

    /**
     * 오프스크린 여정의 하루치 노자 — 봉놋방 1박 + 국밥 2끼 (economy.yml 생활 표에서 유도).
     * 신규 수치 발명 없음: 기존 생활 물가의 합이 곧 '길 위의 하루'다.
     */
    public int dailyTravelCost() {
        return price("생활", "봉놋방_1박") + price("생활", "국밥") * 2;
    }

    /** time.yml action_costs.지역권_이동 = "3~7일 (multi_day)" → [3, 7] */
    public List<Integer> regionTravelDays() {
        Map<String, Object> costs = RulesConfig.section(timeCfg, "action_costs");
        String raw = String.valueOf(costs.get("지역권_이동"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*~\\s*(\\d+)").matcher(raw);
        if (!m.find()) {
            throw new IllegalStateException("time.yml 지역권_이동 형식을 읽을 수 없다: " + raw);
        }
        return List.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
    }

    /** rumor.yml propagation.origin_network_by_location — 장소별 발원 소문망 */
    @SuppressWarnings("unchecked")
    public String originNetwork(String location) {
        Map<String, Object> prop = RulesConfig.section(rumorCfg, "propagation");
        Map<String, Object> byLoc = (Map<String, Object>) prop.get("origin_network_by_location");
        Object net = byLoc.get(location);
        return net == null ? "mingan_market" : String.valueOf(net);
    }

    /** 등록 NPC 키 → 표시 이름 (등록제 명사 — 발명 금지) */
    public String npcName(String key) {
        Map<String, Object> npc = npcByKey(key);
        return npc == null ? key : String.valueOf(npc.get("name"));
    }

    public String npcRole(String key) {
        Map<String, Object> npc = npcByKey(key);
        return npc == null ? "" : String.valueOf(npc.get("role"));
    }

    public int npcTier(String key) {
        Map<String, Object> npc = npcByKey(key);
        return npc == null ? 1 : RulesConfig.intValue(npc.get("tier"));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> npcByKey(String key) {
        Object npc = RulesConfig.section(npcsCfg, "npcs").get(key);
        return npc instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** 표시 이름 → 등록 키 (대화 명령의 상대 옵션은 이름으로 온다) */
    public String npcKeyByName(String name) {
        for (Map.Entry<String, Object> e : RulesConfig.section(npcsCfg, "npcs").entrySet()) {
            if (e.getValue() instanceof Map<?, ?> npc && name.equals(npc.get("name"))) {
                return e.getKey();
            }
        }
        return null;
    }

    /** 기 운용 게이트 — realm_gates에 없는 경지(범인)는 게이트 없음 = 불가 */
    public boolean canUseQi(String realm, String costBand) {
        try {
            return energy.canUse(realm, costBand);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 등록 NPC 항목을 표시 이름으로 찾는다 (등록제 명사 — 대화 페르소나의 원천) */
    @SuppressWarnings("unchecked")
    public Map<String, Object> npcByName(String name) {
        Map<String, Object> registry = RulesConfig.section(npcsCfg, "npcs");
        for (Object value : registry.values()) {
            if (value instanceof Map<?, ?> npc && name.equals(npc.get("name"))) {
                return (Map<String, Object>) npc;
            }
        }
        return null;
    }

    /** llm.yml roles.turn_renderer.model — 세대 교체는 config 만 갱신하면 된다 */
    @SuppressWarnings("unchecked")
    public String turnRendererModel() {
        Map<String, Object> roles = RulesConfig.section(llmCfg, "roles");
        Map<String, Object> renderer = (Map<String, Object>) roles.get("turn_renderer");
        return (String) renderer.get("model");
    }

    /**
     * ★ LLM 런타임 (llm.yml runtime) — <b>시간·길이·줄이 코드에서 등록부로 나온 자리.</b>
     *
     * <p>옛 코드는 로컬 타임아웃 25초를 <b>박아 두고</b> 있었다. 2026-07-13 실측: 실제 서장 한 건이
     * <b>22.4초</b> — 정상 응답이 <b>2.6초 차이로 겨우</b> 통과하고 있었고, 넷이 동시에 들면 89.5초라
     * <b>전원이 폴백</b>이었다. 그래서 여기로 뺐다 (그리고 {@link Scribe} 가 동시성을 1로 조인다).
     */
    public int llmRuntime(String key, int fallback) {
        Object v = RulesConfig.section(llmCfg, "runtime").get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    /** 폴백으로 나온 글임을 사람에게도 보이는가 (llm.yml runtime.fallback_visible_to_player) */
    public boolean fallbackVisible() {
        Object v = RulesConfig.section(llmCfg, "runtime").get("fallback_visible_to_player");
        return !(v instanceof Boolean b) || b;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> questions() {
        return (List<Map<String, Object>>) dispositionTest.get("questions");
    }

    // ─── 성별 (player_creation.yml gender) — 생성의 첫 물음 ───
    //
    // ★ 2026-07-13: gender.gates 가 **채워졌다** (사용자 지시 — 능력치·문파·호칭).
    //   그 규칙을 아는 것은 **코어의 GenderEngine 하나뿐**이다 (봇은 그것을 통해서만 묻는다).
    //   봇이 성별 규칙을 직접 알면 등록제가 깨진다 — 여기는 손잡이일 뿐이다.
    //
    // ★★ 히든: 성별 보정은 **판정에만** 들고 **시트에는 안 뜬다** (genderStat 을 지나야 든다).
    //   시트 출력은 genderStat 을 지나면 안 된다 — 지나는 순간 플레이어가 보정을 눈으로 본다.

    /** 성별 규칙 엔진 — 능력치 보정·문파 입문·호칭의 <b>단일 원천</b> (core) */
    public final GenderEngine genderEngine;

    /** 성별 등록부 통째 (player_creation.yml gender) */
    public Map<String, Object> gender() {
        return RulesConfig.section(playerCreation, "gender");
    }

    /**
     * <b>판정이 쓰는 능력치</b> = 시트값 + 성별 보정 (히든).
     *
     * <p>★ 모든 판정 호출부는 시트를 직접 읽지 말고 <b>이 문을 지나라.</b> 그래야 보정이 실제로 든다.
     * <p>★ 시트 출력({@code /혼천 정보})은 <b>이 문을 지나지 마라.</b> 지나면 히든이 깨진다.
     *
     * @param sheet 캐릭터 시트 (성별과 능력치를 여기서 읽는다)
     */
    @SuppressWarnings("unchecked")
    public int genderStat(Map<String, Object> sheet, String attribute, int fallback) {
        int base = fallback;
        // ★ v3 저울 (B-135 단계 2 · attribute_scale_v3 §8.1): 판정치 = floor(√원장).
        //   성별 ±1 은 이 base **뒤**에 얹힌다 (judgmentStat 후치 — §8.4). 판정 엔진은 무변경.
        Object rawLedger = sheet == null ? null : sheet.get("원장");
        if (rawLedger instanceof Map<?, ?> rm && rm.get(attribute) instanceof Number rn) {
            base = GrowthV3.judgmentValue(rn.doubleValue());
        } else {
            // 원장이 아직 없는 시트 — 옛 능력치로 물러선다.
            //   동등: floor(능력치) = floor(√능력치²) (backfill 이 원장=능력치² 라 결과 불변 — 하네스 증명)
            Object attrs = sheet == null ? null : sheet.get("능력치");
            if (attrs instanceof Map<?, ?> m && m.get(attribute) instanceof Number n) {
                base = n.intValue();
            }
        }
        Object g = sheet == null ? null : sheet.get(genderSheetKey());
        return genderEngine.judgmentStat(g == null ? null : String.valueOf(g), attribute, base);
    }

    /** 성별을 묻는가 — {@code gender.ask}. 등록부가 끄면 안 묻는다 (성별 없는 캐릭터로 돌아간다) */
    public boolean genderAsk() {
        Object v = gender().get("ask");
        return v == null || Boolean.parseBoolean(String.valueOf(v));
    }

    /** sheet_json 의 칸 이름 — {@code gender.sheet_key} (코드가 이름을 정하지 않는다) */
    public String genderSheetKey() {
        Object v = gender().get("sheet_key");
        return v == null ? "성별" : String.valueOf(v).strip();
    }

    /** 사람이 읽는 문장 하나 — {@code gender.<key>} */
    public String genderText(String key, String fallback) {
        Object v = gender().get(key);
        return v == null ? fallback : String.valueOf(v).strip();
    }

    /**
     * 고를 수 있는 성별 — {@code gender.options}. <b>등록제: 여기 없는 성별은 세계에 존재하지 않는다.</b>
     * 키가 시트에 적히는 값(남·여)이고, {@code label} 이 버튼에 뜨는 말(사내·계집)이다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> genderOptions() {
        Object v = gender().get("options");
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** 버튼에 뜰 말 — {@code gender.options.<key>.label}. 없으면 키 그대로 */
    public String genderLabel(String key) {
        Object opt = genderOptions().get(key);
        if (opt instanceof Map<?, ?> m && m.get("label") != null) {
            return String.valueOf(m.get("label")).strip();
        }
        return key;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> families() {
        Map<String, Object> lifepath = RulesConfig.section(playerCreation, "age_and_lifepath");
        return (Map<String, Object>) lifepath.get("families");
    }

    private Map<String, Object> lifepath(String key) {
        return RulesConfig.section(
                RulesConfig.section(playerCreation, "age_and_lifepath"), key);
    }

    /**
     * ★★ <b>결(結) — 유년의 기억이 집안을 정한다</b> (player_creation.yml family_affinity).
     *
     * <p>옛 {@code birth()} 는 집안을 <b>전체에서 통째로 주사위</b>로 뽑았다 — 그래서 아홉 문항이
     * 집안에 <b>아무 자국도 남기지 않았다.</b> 이제 테스트가 <b>갈래</b>를 좁히고,
     * 주사위는 <b>그 갈래 안에서만</b> 구른다 (무늬).
     *
     * @return 성향 → 후보 집안들. 등록부에 없는 성향이면 빈 목록 (코드가 짝을 지어내지 않는다)
     */
    @SuppressWarnings("unchecked")
    public List<String> familyCandidates(String disposition) {
        Map<String, Object> row = (Map<String, Object>) lifepath("family_affinity").get(disposition);
        if (row == null || !(row.get("candidates") instanceof List<?> l)) {
            return List.of();
        }
        List<String> out = new java.util.ArrayList<>();
        l.forEach(v -> out.add(String.valueOf(v)));
        return out;
    }

    /** 세가를 거절하는 문 (player_creation.yml refuse_house) — 비어 있으면 문이 없다 */
    public Map<String, Object> refuseHouse() {
        return lifepath("refuse_house");
    }

    /**
     * ★★ <b>가문이 세계에 섰는가</b> (player_creation.yml house_system.enabled).
     *
     * <p><b>false 면 형제가 비어 있다.</b> 그전까지 코드는 <b>집안 유형</b>으로 남매를 묶었고 —
     * 그래서 <b>농가의 아이 둘이 남매였다</b> (서로 다른 농가인데). <b>거짓 형제보다 없는 형제가 낫다.</b>
     *
     * <p>켜려면 세 가지가 필요하다: ① {@code db/migrations/008_가문.sql} 을 <b>사람이</b> 돌리고
     * ② <b>배정 규칙</b>(누가 어느 집에 태어나는가)이 정해지고 ③ 이 칸을 true 로.
     * ②는 <b>세계관 결정</b>이다 (house_system.open_questions ①) — 코드가 지어내지 않는다.
     */
    public boolean houseSystemEnabled() {
        return Boolean.TRUE.equals(lifepath("house_system").get("enabled"));
    }

    private Map<String, Object> houseCfg(String key) {
        return RulesConfig.section(lifepath("house_system"), key);
    }

    /** 배정 — 기존 집에 태어날 확률(%) · 자식 수 상한 (house_system.assignment) */
    public int houseAssign(String key, int fallback) {
        Object v = houseCfg("assignment").get(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    /**
     * ★★ <b>사람이 실제로 설 수 있는 고을</b> (mvt_start.playable).
     *
     * <p>사용자: <i>"각 지역 분지마다 시작 위치가 정해져 <b>실제 여러 세상에서 시작하는 것처럼</b>
     * 느껴져야 합니다."</i>
     *
     * <p><b>★ 그러나 지역이 실제로 서야 한다</b> — 블록도 앵커도 없는 고을에 가문을 두면
     * <b>갈 수 없는 집</b>이 되고 사람은 허공에 떨어진다. 그래서 가문의 지역은 <b>이 목록 안에서만</b>
     * 뽑는다. <b>고을이 늘면 등록부만 고친다</b> — 코드는 고을 이름을 지어내지 않는다.
     */
    public List<String> playableRegions() {
        // ★ section() 은 Map 으로 캐스팅한다 — playable 은 **목록**이다. get() 으로 읽어야 한다
        //   (이 한 줄이 틀려서 **탄생 때마다 ClassCastException** 이 났다. 눈이 잡았다)
        Object v = lifepath("mvt_start").get("playable");
        if (v instanceof List<?> l && !l.isEmpty()) {
            List<String> out = new java.util.ArrayList<>();
            l.forEach(r -> out.add(String.valueOf(r)));
            return out;
        }
        return List.of(defaultRegion());
    }

    /** 고을의 사람 이름 (mvt_start.by_region.<id>.name) — 「cheongha_hyeon」 이 아니라 「청하현」 */
    public String regionName(String region) {
        Map<String, Object> r = RulesConfig.section(
                RulesConfig.section(lifepath("mvt_start"), "by_region"),
                region == null ? defaultRegion() : region);
        Object n = r.get("name");
        return n == null ? String.valueOf(region) : String.valueOf(n);
    }

    public String defaultRegion() {
        // ★ 【B-102】 여기가 section() 이었다 — section 은 Map 전용이라 스칼라("cheongha_hyeon")를
        //   만나면 ClassCastException 을 던졌고, 세계 상태 발행이 통째로 죽었다 (되먹임 단절).
        //   스칼라는 get 으로 읽는다. (section 은 null 이면 던지므로 아래 null 분기도 그때는 죽은 코드였다.)
        Object v = lifepath("mvt_start").get("default_region");
        return v == null ? "cheongha_hyeon" : String.valueOf(v);
    }

    /** ★ 성씨 — <b>무가 계열에만</b> 붙는다 (이 세계에서 성은 「가문이 있다」는 표시 그 자체다) */
    public String rollSurname(java.util.Random dice) {
        Object v = houseCfg("surnames").get("pool");
        if (!(v instanceof List<?> l) || l.isEmpty()) {
            return null;   // 등록부가 비었다 — 코드가 성을 지어내지 않는다
        }
        return String.valueOf(l.get(dice.nextInt(l.size())));
    }

    public String houseNameFormat(boolean martial) {
        Object v = houseCfg("surnames").get(martial ? "format_martial" : "format_common");
        return v == null ? "{region} {family}" : String.valueOf(v);
    }

    /** 이 집안이 무가 계열인가 (families.<집안>.lineage: 무가) — 성씨·흥망은 여기에만 붙는다 */
    public boolean isMartialHouse(String family) {
        Object f = families().get(family);
        return f instanceof Map<?, ?> m && "무가".equals(m.get("lineage"));
    }

    /**
     * ★★ <b>가문의 형태 — 탄생에 정해지고 바뀌지 않는다</b> (사용자 확정).
     *
     * <p><b>★ 「몰락무가」와 겹치는 문제는 파생으로 풀었다</b>: 집안이 <b>이미 상태를 말하고 있으면</b>
     * (몰락무가 = 멸) <b>굴리지 않고 받는다.</b> 두 번 말하면 어긋날 수 있다 (몰락무가인데 흥?).
     * 주사위는 <b>집안이 말하지 않은 것에만</b> 든다 — 살아 있는 집이 흥한가 기우는가.
     */
    public String houseState(String family, java.util.Random dice) {
        if (!isMartialHouse(family)) {
            return null;   // 농가의 '흥망'은 이 세계의 어휘가 아니다
        }
        Object fixed = RulesConfig.section(houseCfg("state"), "by_family").get(family);
        if (fixed != null) {
            return String.valueOf(fixed);   // ★ 집안이 이미 말했다 (몰락무가 → 멸)
        }
        Map<String, Object> w = RulesConfig.section(houseCfg("state"), "living_weights");
        int total = 0;
        for (Object v : w.values()) {
            total += v instanceof Number n ? n.intValue() : 0;
        }
        if (total <= 0) {
            return null;
        }
        int roll = dice.nextInt(total);
        for (Map.Entry<String, Object> e : w.entrySet()) {
            roll -= e.getValue() instanceof Number n ? n.intValue() : 0;
            if (roll < 0) {
                return e.getKey();
            }
        }
        return null;
    }

    /** ★ 아우가 났다 — 형에게 가는 소식 (birth_rumor.sibling_news). ★ 소문 범위와 무관하다 */
    public Map<String, Object> siblingNews() {
        return RulesConfig.section(birthRumor(), "sibling_news");
    }

    /** ★ 탄생 소문 (player_creation.yml birth_rumor) — "세상이 누가 태어났는지 알아야 한다" */
    public Map<String, Object> birthRumor() {
        return lifepath("birth_rumor");
    }

    /**
     * ★★ <b>그 집의 아이가 얼마나 멀리 알려지는가.</b>
     *
     * <p>사용자 (2026-07-14): <i>"<b>세가를 제외하곤 지역까지만 퍼짐 (해당 마을)</b>"</i>
     *
     * <p><b>★ 눈금을 지어내지 않았다</b> — {@code rumor.yml propagation.reach_by_intensity} 가
     * 이미 갖고 있었다:
     * <pre>
     *   1 = 발생 장소의 기본 망 1개
     *   2 = **현 내 관심 일치 망 전체**    ← ★ 사용자의 "해당 마을" 이 정확히 이것이다 (기본값)
     *   3 = 현 전체 + 인접 현              ← 세가 (★ 담당자의 **제안값** — 승인 대기)
     *   4 = 지역권 전체 · 5 = 천하
     * </pre>
     */
    public int birthRumorIntensity(String family, String rank) {
        // ★ 적서가 있는 집이면 **적서가 무게를 정한다** (사용자 확정: 적자 5 · 서자 3)
        Map<String, Object> opt = birthRankOption(rank);
        if (hasBirthRank(family) && opt.get("rumor_intensity") instanceof Number n) {
            return n.intValue();
        }
        Object d = birthRumor().get("default_intensity");
        return d instanceof Number n ? n.intValue() : 2;   // 보통의 집 → 2 (현 내 = 해당 마을)
    }

    // ═══ ★★ 적서(嫡庶) — 같은 집에 태어났는데 세상이 아는 무게가 다르다 ═══

    /** player_creation.yml birth_rank — 꺼져 있으면 세계에 적서가 없다 */
    public Map<String, Object> birthRank() {
        return lifepath("birth_rank");
    }

    /**
     * ★ <b>이 집에 적서가 있는가.</b>
     *
     * <p>등록부에 <b>「세가 표」가 없었다</b> — factions.yml 의 오대세가는 <b>NPC 세력</b>이고,
     * 플레이어의 집안 중 「이름이 있는 <b>살아 있는</b> 집」은 {@code 무가의_자식} 하나뿐이다.
     * 그래서 {@code birth_rank.houses} 가 그 목록이고, <b>코드가 짐작하지 않는다.</b>
     */
    public boolean hasBirthRank(String family) {
        Map<String, Object> cfg = birthRank();
        return Boolean.TRUE.equals(cfg.get("enabled"))
                && cfg.get("houses") instanceof List<?> l && l.contains(family);
    }

    /** 적서를 굴린다 — <b>무늬</b>다 (결은 심리 테스트가 정했다). 가중치는 등록부가 정한다 */
    public String rollBirthRank(java.util.Random dice) {
        Map<String, Object> weights = RulesConfig.section(birthRank(), "weights");
        int total = 0;
        for (Object v : weights.values()) {
            total += v instanceof Number n ? n.intValue() : 0;
        }
        if (total <= 0) {
            return null;   // 등록부가 비었다 — 코드가 비율을 지어내지 않는다
        }
        int roll = dice.nextInt(total);
        for (Map.Entry<String, Object> e : weights.entrySet()) {
            roll -= e.getValue() instanceof Number n ? n.intValue() : 0;
            if (roll < 0) {
                return e.getKey();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> birthRankOption(String rank) {
        if (rank == null) {
            return Map.of();
        }
        Object row = RulesConfig.section(birthRank(), "options").get(rank);
        return row instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    public String birthRankSheetKey() {
        return String.valueOf(birthRank().getOrDefault("sheet_key", "적서"));
    }

    /**
     * ★★ <b>이 아이가 「태어난」 집안</b> — 집을 찾을 때만 쓴다.
     *
     * <p>세가를 거절한 아이의 집안은 {@code 가출한_무가의_자식} 이다. 그러나 그 아이는
     * <b>무가의_자식의 집에서 태어났다</b> — 나온 것은 그 <b>뒤</b>의 일이다.
     * 그 키로 집을 찾으면 「가출한 무가」라는 <b>새 집</b>이 서고 <b>제 형과 남남이 된다.</b>
     * 사용자가 확정한 것과 정반대다: <i>"호적에서 지워도 <b>형은 형이다.</b>"</i>
     *
     * <p>어느 집을 나왔는지는 <b>등록부가 안다</b> ({@code families.<집안>.from}) —
     * 코드가 짐작하지 않는다.
     *
     * <p>★ 옛 {@code kinGroup()} 은 <b>죽었다</b>: 형제를 <b>집안 유형</b>으로 묶던 시절의 손잡이였고,
     * 이제 형제는 <b>{@code house_id}</b> 로만 잡힌다. 그 결정(「형은 형이다」)은 사라지지 않았다 —
     * <b>여기로 옮겨왔을 뿐이다</b> (같은 집에 앉히면 같은 house_id 가 된다).
     */
    public String birthFamilyOf(String family) {
        Object f = families().get(family);
        if (f instanceof Map<?, ?> m && m.get("from") != null) {
            return String.valueOf(m.get("from"));
        }
        return family;
    }

    /**
     * ★ <b>혈연의 호칭</b> — 형·누나·동생 (player_creation.yml gender.gates.honorifics.kin).
     *
     * <p><b>문파의 것과 다르다:</b> 문파는 <b>입문 순</b>의 사형·사저·사제·사매고, 혈연은
     * <b>태어난 순</b>의 형·누나·동생이다. <b>어휘를 섞지 않는다</b> — 세가의 형을 '사형'이라 부르지 않는다.
     *
     * @param elder        그가 나보다 <b>먼저</b> 태어났는가
     * @param theirGender  ★ <b>불리는 자</b>의 성별 (부르는 자가 아니다 — resolve_by: 대상의_성별)
     */
    @SuppressWarnings("unchecked")
    public String kinTitle(boolean elder, String theirGender) {
        Map<String, Object> gates = RulesConfig.section(
                RulesConfig.section(playerCreation, "gender"), "gates");
        Map<String, Object> kin = RulesConfig.section(
                RulesConfig.section(gates, "honorifics"), "kin");
        Object row = kin.get(elder ? "손위" : "손아래");
        if (!(row instanceof Map<?, ?> m) || theirGender == null) {
            return genderUnknownTitle();
        }
        Object title = ((Map<String, Object>) m).get(theirGender);
        return title == null ? genderUnknownTitle() : String.valueOf(title);
    }

    /** 성별을 모르는 옛 캐릭터 — 코드가 추측하지 않는다 (honorifics.unknown) */
    private String genderUnknownTitle() {
        Map<String, Object> gates = RulesConfig.section(
                RulesConfig.section(playerCreation, "gender"), "gates");
        Object v = RulesConfig.section(gates, "honorifics").get("unknown");
        return v == null ? "무인" : String.valueOf(v);
    }

    /** 이 집안이 거절할 수 있는 집인가 (건재한 세가만 — 몰락무가는 버릴 집이 없다) */
    public boolean canRefuseHouse(String family) {
        Map<String, Object> cfg = refuseHouse();
        return Boolean.TRUE.equals(cfg.get("enabled"))
                && cfg.get("applies_to") instanceof List<?> l && l.contains(family);
    }

    /** 거절하면 무엇이 되는가 — 근거: 무가의_자식.support.단계 "절연 = 사실상 몰락_무가 루트 합류" */
    public String refusedFamily() {
        return String.valueOf(refuseHouse().getOrDefault("becomes", "몰락_무가의_자식"));
    }

    public String refuseText(String key, String fallback) {
        Object v = refuseHouse().get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    /**
     * ★ <b>마크의 첫 자리 — 집안마다 다르다</b> (player_creation.yml mvt_start).
     *
     * <p>전에는 <b>전원이 같은 자리</b>에 내렸다 (Antechamber.depart → 첫 유효 앵커).
     * 그리고 {@code start_location} 은 <b>읽는 코드가 0줄인 죽은 등록부</b>였다.
     *
     * @return 이 집안이 설 <b>마크의 앵커 이름</b> (한글 — 좌표의 정본). 없으면 default_id 의 앵커
     */
    public String startAnchor(String region, String family) {
        Map<String, Object> byRegion = RulesConfig.section(
                RulesConfig.section(lifepath("mvt_start"), "by_region"),
                region == null ? defaultRegion() : region);
        if (byRegion.isEmpty()) {
            byRegion = RulesConfig.section(
                    RulesConfig.section(lifepath("mvt_start"), "by_region"), defaultRegion());
        }
        Object id = RulesConfig.section(byRegion, "by_family").get(family);
        if (id == null) {
            id = byRegion.get("default_family");
        }
        Object anchor = RulesConfig.section(byRegion, "anchor_map").get(String.valueOf(id));
        return anchor == null ? null : String.valueOf(anchor);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> incidents() {
        Map<String, Object> lifepath = RulesConfig.section(playerCreation, "age_and_lifepath");
        return (Map<String, Object>) lifepath.get("inciting_incidents");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> ageBrackets() {
        Map<String, Object> lifepath = RulesConfig.section(playerCreation, "age_and_lifepath");
        return (Map<String, Object>) lifepath.get("age_brackets");
    }

    /**
     * 시작 자금 — economy.yml starting_money: 연령대 범위 × 집안 배율.
     * 몰락_무가의_자식은 배율 항목이 없어 1.0 — 가보는 서사 자산이지 전낭이 아니다.
     */
    @SuppressWarnings("unchecked")
    public int startingMoney(String bracket, String family, Random dice) {
        Map<String, Object> sm = (Map<String, Object>) economyCfg.get("starting_money");
        List<Number> range = (List<Number>) sm.get(bracket);
        int min = range.get(0).intValue();
        int max = range.get(1).intValue();
        int base = min + dice.nextInt(max - min + 1);
        Map<String, Object> multipliers = (Map<String, Object>) sm.get("family_multiplier");
        double mult = multipliers.containsKey(family)
                ? ((Number) multipliers.get(family)).doubleValue() : 1.0;
        return Math.max(1, (int) Math.round(base * mult));
    }

    /** 의뢰 보수 — economy.yml price_table.의뢰_보수: 고정값 또는 [min, max] 범위 */
    @SuppressWarnings("unchecked")
    public int questReward(String key, Random dice) {
        Map<String, Object> table = RulesConfig.section(economyCfg, "price_table");
        Map<String, Object> rewards = (Map<String, Object>) table.get("의뢰_보수");
        Object value = rewards.get(key);
        if (value instanceof List<?> range) {
            int min = ((Number) range.get(0)).intValue();
            int max = ((Number) range.get(1)).intValue();
            return min + dice.nextInt(max - min + 1);
        }
        return ((Number) value).intValue();
    }

    @SuppressWarnings("unchecked")
    public List<Integer> presetStats(String disposition) {
        Map<String, Object> presets = RulesConfig.section(playerCreation, "disposition_presets");
        Map<String, Object> preset = (Map<String, Object>) presets.get(disposition);
        if (preset == null) {
            preset = (Map<String, Object>) presets.get("협의형");
        }
        return (List<Integer>) preset.get("stats");
    }
}
