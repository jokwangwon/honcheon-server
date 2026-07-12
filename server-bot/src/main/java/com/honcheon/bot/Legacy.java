package com.honcheon.bot;

import com.honcheon.core.rules.RulesConfig;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 죽음과 유산 — config/death_and_legacy.yml 의 봇 쪽 손잡이 (단계 4 A).
 *
 * 이 게임의 뼈대: **죽음은 비가역 — 계정당 한 삶.**
 * 그러나 죽음은 갑자기 오지 않는다. 파이프는 길다:
 *
 *   부상 사다리(경상 → 중상 → 빈사)  ← 사선(살의)에서의 치명적 실패만이 여기를 태운다
 *     빈사   매 라운드 체력 판정 (비대립 12) — 버티는 것 자체가 판정이다
 *     실패 → 사망 위기 (개입 창구 1회):
 *            ① 회생   선천진기 10년 — 사전 선언제 자동 (internal_energy.yml)
 *            ② 구조   의술 지혈 (비대립 어려움 14) — 동행 또는 NPC
 *            ③ 없음 → 사망 확정 (비가역)
 *
 * 구조의 NPC 축은 **npc_death.yml 의 서비스 공백과 접합**한다 —
 * 유문이 죽으면 의방은 곰보영감의 대행이 되고, 그 대행의 불가_서비스에 '중상_치료'가 있다.
 * 즉 **유문이 죽으면 아무도 너를 못 살린다.** 그것이 그 죽음의 값이다.
 *
 * 수치는 전부 config: 부상 페널티·난이도(judgment.yml), 회생 비용(internal_energy.yml),
 * 상속 순서·소문 강도(death_and_legacy.yml), 대행 불가 서비스(npc_death.yml).
 */
final class Legacy {

    /** 부상 사다리 — judgment.yml combat.wound_stages 의 키와 같다 (경상 -1 · 중상 -2 · 빈사 -3) */
    static final List<String> WOUNDS = List.of("경상", "중상", "빈사");

    /** 의방을 지키는 자 = 유문 (npc_death.yml service_gap.facilities.medicine_shop) */
    static final String PHYSICIAN_NPC = "yumun";
    static final String MEDICINE_SHOP = "medicine_shop";
    /** 대행자가 못 하는 일 — 이 서비스가 막히면 빈사에서 아무도 못 살린다 */
    static final String CRITICAL_CARE = "중상_치료";

    private final Map<String, Object> cfg;

    Legacy(Map<String, Object> cfg) {
        this.cfg = cfg;
    }

    // ─── 사망 파이프 (death_pipeline) ───

    @SuppressWarnings("unchecked")
    private Map<String, Object> crisis() {
        Map<String, Object> pipeline = RulesConfig.section(cfg, "death_pipeline");
        return (Map<String, Object>) pipeline.get("사망_위기");
    }

    /** death_pipeline.빈사 — "매 라운드 체력 판정 (비대립 12 …)" 에서 12 를 읽는다 */
    int dyingCheckDifficulty(int fallback) {
        Map<String, Object> pipeline = RulesConfig.section(cfg, "death_pipeline");
        Matcher m = Pattern.compile("(\\d+)").matcher(String.valueOf(pipeline.get("빈사")));
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    /** 사망_위기.window_rounds — 마지막 개입 창구 (1). 텍스트 봇에서는 '빈사인 채 맞는 다음 세계일' */
    int crisisWindowRounds() {
        Object rounds = crisis().get("window_rounds");
        return rounds instanceof Number n ? n.intValue() : 1;
    }

    /** 사망_위기.interventions.구조.check — "의술 지혈 — 비대립 어려움 14" 의 난이도 이름 */
    @SuppressWarnings("unchecked")
    String rescueDifficultyBand() {
        Map<String, Object> interventions = (Map<String, Object>) crisis().get("interventions");
        Map<String, Object> rescue = (Map<String, Object>) interventions.get("구조");
        String check = String.valueOf(rescue.get("check"));
        for (String band : List.of("극한", "매우_어려움", "어려움", "보통", "쉬움")) {
            if (check.contains(band.replace('_', ' ')) || check.contains(band)) {
                return band;
            }
        }
        return "어려움";
    }

    /** default_defeat — 패배의 기본값 [제압, 강탈, 중상]. 살인은 비싸다: 세계가 청구한다 */
    @SuppressWarnings("unchecked")
    List<String> defaultDefeat() {
        Map<String, Object> pipeline = RulesConfig.section(cfg, "death_pipeline");
        return ((List<Object>) pipeline.get("default_defeat")).stream().map(String::valueOf).toList();
    }

    /**
     * 패배의 기본값이 죽음이 아닌 이상, **살의 없는 패배는 중상에서 멈춘다** (비무·대련).
     * 살의(사선 — 야수의 이빨·사파의 칼·격상 의뢰)만이 빈사 칸을 연다.
     */
    String woundCap(boolean lethal) {
        return lethal ? "빈사" : defaultDefeat().contains("중상") ? "중상" : "경상";
    }

    /** 부상 한 칸 악화 — 상한(cap) 을 넘지 않는다. 반환: 새 부상 (없으면 null) */
    String worsen(String current, int steps, String cap) {
        int at = current == null ? -1 : WOUNDS.indexOf(current);
        int capAt = WOUNDS.indexOf(cap);
        int next = Math.min(capAt, at + Math.max(1, steps));
        return next < 0 ? null : WOUNDS.get(next);
    }

    /** 부상 한 칸 회복 — 의방의 일 (반환: 새 부상, 다 나으면 null) */
    static String heal(String current) {
        int at = current == null ? -1 : WOUNDS.indexOf(current);
        return at <= 0 ? null : WOUNDS.get(at - 1);
    }

    // ─── 유산 (legacy) ───

    /** legacy.예치.order — [생전_지정_상속인, 혈연_NPC, 세력_귀속_무주공산] */
    @SuppressWarnings("unchecked")
    List<String> inheritanceOrder() {
        Map<String, Object> legacy = RulesConfig.section(cfg, "legacy");
        Map<String, Object> deposit = (Map<String, Object>) legacy.get("예치");
        return ((List<Object>) deposit.get("order")).stream().map(String::valueOf).toList();
    }

    /** legacy.현장.drops — [전낭_전액, 착용_장비]. 줍는 자의 것 (텍스트 봇: 주울 자가 없으면 흩어진다) */
    @SuppressWarnings("unchecked")
    List<String> siteDrops() {
        Map<String, Object> legacy = RulesConfig.section(cfg, "legacy");
        Map<String, Object> site = (Map<String, Object>) legacy.get("현장");
        return ((List<Object>) site.get("drops")).stream().map(String::valueOf).toList();
    }

    /** legacy.비이전 — [화후, 숙련, 경지, 마크, favor]. 몸에 새긴 것은 무덤까지 간다 */
    @SuppressWarnings("unchecked")
    List<String> nonTransferable() {
        Map<String, Object> legacy = RulesConfig.section(cfg, "legacy");
        return ((List<Object>) legacy.get("비이전")).stream().map(String::valueOf).toList();
    }

    // ─── 세계의 기억 (world_memory) ───

    /** world_memory.소문.strength — 사망은 강도 2 소문이다 (사인·장소 포함) */
    @SuppressWarnings("unchecked")
    int deathRumorStrength() {
        Map<String, Object> memory = RulesConfig.section(cfg, "world_memory");
        Map<String, Object> rumor = (Map<String, Object>) memory.get("소문");
        return RulesConfig.intValue(rumor.get("strength"));
    }

    /** world_memory.명성 — "동결 — 별호는 세계 연표에 역사로 (NPC 회고 가능)" */
    String fameRule() {
        return String.valueOf(RulesConfig.section(cfg, "world_memory").get("명성"));
    }

    /** world_memory.피의_장부 — 피살자의 원한은 살해자에게 이관된다 */
    String bloodLedgerRule() {
        return String.valueOf(RulesConfig.section(cfg, "world_memory").get("피의_장부"));
    }

    // ─── 새 캐릭터 (new_character) ───

    /** new_character.lineage_choice.<혈연_시작|무관_시작> 존재 여부 — 생성 로비의 2택 */
    @SuppressWarnings("unchecked")
    Map<String, Object> lineageChoice() {
        Map<String, Object> newChar = RulesConfig.section(cfg, "new_character");
        return (Map<String, Object>) newChar.get("lineage_choice");
    }

    /** 혈연 시작이 물려주는 것 — [상속분_접근, 피의_장부_상속] */
    @SuppressWarnings("unchecked")
    List<String> lineageGrants() {
        Object kin = lineageChoice().get("혈연_시작");
        if (kin instanceof Map<?, ?> m && ((Map<String, Object>) m).get("grants") instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /** 혈연 시작의 집안 문법 — 몰락_무가의_자식 (player_creation.yml 등록 집안 재사용) */
    @SuppressWarnings("unchecked")
    String lineageFamilyTemplate() {
        Object kin = lineageChoice().get("혈연_시작");
        if (kin instanceof Map<?, ?> m) {
            Object template = ((Map<String, Object>) m).get("family_template");
            if (template != null) {
                return String.valueOf(template).replace("_문법", "");
            }
        }
        return "몰락_무가의_자식";
    }
}
