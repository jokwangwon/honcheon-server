package com.honcheon.core.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 성별 엔진 — {@code config/player_creation.yml} 의 {@code gender} 절만 읽는다.
 *
 * <p><b>이 클래스는 아무것도 지어내지 않는다.</b> 능력치 보정도, 입문 가능 문파도, 호칭도
 * 전부 등록부에서 온다. 등록부가 비어 있으면 <b>아무 일도 일어나지 않는다</b> — 보정 0,
 * 문은 열려 있고, 호칭은 {@code unknown} 이다. 코드가 빈 칸을 메우면 그것이 은닉이다.
 *
 * <p><b>【히든의 뜻】</b> 숨기는 것은 <b>플레이어에게</b>지 <b>개발자에게</b>가 아니다.
 * 보정표는 등록부에 명시돼 있고(누구나 읽는다), 다만 <b>시트에 수치로 뜨지 않는다.</b>
 * 보이면 최적해가 생기고 사람들이 '유리한 성별'을 고른다. 안 보이면 역할로 고른다.
 * 이것은 새 원칙이 아니다 — {@code gm_modifiers.yml} 이 이미 못을 박아 뒀다:
 * <i>"보정 내역은 내부 로그에 기록, 플레이어 출력에 수치 노출 금지"</i>.
 *
 * <p><b>【그래서 보정은 저장되지 않는다】</b> 시트의 {@code 능력치} 맵은 {@code /혼천 정보} 가
 * 통째로 훑어 출력한다. 거기에 더하는 순간 히든이 아니다. 그래서 보정은 <b>판정이 능력치를
 * 읽는 그 순간에만</b> 얹힌다 ({@link #judgmentStat}). 시트에는 흔적이 없다.
 */
public final class GenderEngine {

    /** 성별을 모르는 캐릭터 — 옛 캐릭터에는 성별이 없다. 코드는 <b>추측하지 않는다</b> */
    public static final String UNKNOWN = null;

    private final Map<String, Object> cfg;

    /** @param genderSection {@code player_creation.yml} 의 {@code gender} 절 (없으면 빈 맵) */
    public GenderEngine(Map<String, Object> genderSection) {
        this.cfg = genderSection == null ? Map.of() : genderSection;
    }

    /** {@code player_creation.yml} 전체를 받아 {@code gender} 절을 스스로 찾는다 */
    public static GenderEngine of(Map<String, Object> playerCreation) {
        return new GenderEngine(sub(playerCreation, "gender"));
    }

    /**
     * <b>없어도 되는 절</b>을 읽는다 — 없으면 빈 맵.
     *
     * <p>★ {@link RulesConfig#section} 은 절이 없으면 <b>예외를 던진다</b> (필수 절 문법).
     * 그러나 성별의 관문은 <b>전부 선택</b>이다 — 사용자가 아직 안 정한 칸은 비어 있어야 하고,
     * 빈 칸은 "아무것도 하지 않는다" 를 뜻해야지 <b>봇을 터뜨리면 안 된다.</b>
     * (이 구멍은 눈이 잡았다 — {@code 등록부가_비면_성별은_아무것도_하지_않는다})
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> root, String key) {
        Object v = root == null ? null : root.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    // ─────────────────────────── 등록제 ───────────────────────────

    /** 고를 수 있는 성별 — <b>여기 없는 성별은 세계에 존재하지 않는다</b> */
    public Map<String, Object> options() {
        return sub(cfg, "options");
    }

    /** 등록부에 있는 성별인가. {@code null}(모름)은 성별이 아니다 */
    public boolean isRegistered(String gender) {
        return gender != null && options().containsKey(gender);
    }

    private Map<String, Object> gates() {
        return sub(cfg, "gates");
    }

    // ───────────────────── ① 능력치 — 히든 보정 ─────────────────────

    /**
     * 성별 보정이 <b>시트에 노출되면 안 되는가</b> — {@code gates.attributes.hidden}.
     * 눈(GenderGateTest)이 이 깃발을 근거로 시트를 검사한다.
     */
    public boolean attributesHidden() {
        Object v = sub(gates(), "attributes").get("hidden");
        return !Boolean.FALSE.equals(v);   // 등록부가 명시적으로 끄지 않는 한 히든이다
    }

    /** 한 축당 보정 절대값 상한 — {@code gates.attributes.cap}. 성별이 강약을 정하지 못하게 하는 못 */
    public int cap() {
        Object v = sub(gates(), "attributes").get("cap");
        return v == null ? 1 : RulesConfig.intValue(v);
    }

    /**
     * 성별의 능력치 보정 — {@code gates.attributes.<성별>.<능력치>}.
     *
     * <p>등록부에 없으면 <b>0</b>. 코드는 값을 지어내지 않는다.
     * 등록부가 {@link #cap()} 을 넘는 값을 적어도 <b>깎아서</b> 돌려준다 — 상한은 등록부 자신의 못이다.
     *
     * @param gender 성별 키 (남/여). {@code null}(모름)이면 0 — <b>추측하지 않는다</b>
     */
    public int attrModifier(String gender, String attribute) {
        if (!isRegistered(gender)) {
            return 0;   // 성별을 모르는 캐릭터는 보정이 없다. 추론 금지
        }
        // 등록부가 이 성별에 아무 보정도 안 적었을 수 있다 — 그러면 보정은 없다 (터지지 않는다)
        Object v = sub(sub(gates(), "attributes"), gender).get(attribute);
        if (v == null) {
            return 0;
        }
        int raw = RulesConfig.intValue(v);
        int cap = Math.abs(cap());
        return Math.max(-cap, Math.min(cap, raw));
    }

    /**
     * <b>판정이 쓰는 능력치</b> = 시트값 + 성별 보정.
     *
     * <p>★ 판정 호출부는 시트를 직접 읽지 말고 <b>이 문을 지나야 한다.</b> 그래야 보정이
     * 실제로 든다. 시트 출력({@code /혼천 정보})은 <b>이 문을 지나면 안 된다</b> — 지나면 히든이 깨진다.
     *
     * @param sheetValue 시트의 {@code 능력치.<attribute>} 값 (보정 안 된 원본)
     */
    public int judgmentStat(String gender, String attribute, int sheetValue) {
        return sheetValue + attrModifier(gender, attribute);
    }

    // ───────────────────── ② 문파 입문 ─────────────────────

    /**
     * 이 문파가 성별을 가리는가 — {@code gates.faction_entry.<문파>: [허용 성별…]}.
     *
     * <p><b>등록부에 그 문파가 없으면 아무나 들어간다</b> (빈 칸 = 안 막는다).
     * 지금 등록부에 있는 것은 <b>아미</b> 하나뿐이다 — 근거: {@code factions.yml} 이 아미를
     * "사천의 <b>여승</b> 문파" 라 적고, {@code docs/design/ami_architecture.md} 가
     * "<b>여성 중심 문파</b>" 라 적는다. 소림·불가가 남성 전용인지는 <b>등록부가 말한 적이 없어</b>
     * 적지 않았다 (open_questions ②).
     *
     * <p><b>★★ 이 관문은 「문파에 들어갈 때」 선다 — 「캐릭터를 만들 때」가 아니다.</b>
     * 사용자의 최초 불만이 "성별 선택이 없어 강제로 루트가 제한됨" 이었다. 성별을 넣고서
     * 그 성별 때문에 <b>캐릭터를 못 만들게</b> 하면 같은 병을 반대편으로 옮긴 것이다.
     *
     * @param gender {@code null}(모름)이면 <b>막지 않는다</b> — 옛 캐릭터를 가두지 않는다
     */
    public boolean factionAllowed(String gender, String factionId) {
        List<String> allowed = allowedGenders(factionId);
        if (allowed.isEmpty()) {
            return true;   // 등록부가 안 가린 문파 — 아무나 들어간다
        }
        if (gender == null) {
            return true;   // 성별을 모른다. 추측해서 막지 않는다
        }
        return allowed.contains(gender);
    }

    /** 이 문파가 받는 성별 목록. 등록부에 없으면 빈 목록 = <b>안 가린다</b> */
    public List<String> allowedGenders(String factionId) {
        Object v = sub(gates(), "faction_entry").get(factionId);
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        list.forEach(x -> out.add(String.valueOf(x)));
        return Collections.unmodifiableList(out);
    }

    /** 성별을 가리는 문파 전부 (등록부가 적은 것만) */
    public Map<String, List<String>> genderedFactions() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        sub(gates(), "faction_entry").forEach((k, v) -> {
            if (v instanceof List<?>) {
                out.put(k, allowedGenders(k));
            }
        });
        return Collections.unmodifiableMap(out);
    }

    /** 문 앞에서 돌아설 때의 말 — {@code gates.faction_entry.refusal} */
    public String refusal() {
        Object v = sub(gates(), "faction_entry").get("refusal");
        return v == null ? "이 문은 너를 받지 않는다." : String.valueOf(v);
    }

    // ───────────────────── ③ 호칭 ─────────────────────

    private Map<String, Object> honorifics() {
        return sub(gates(), "honorifics");
    }

    /** 성별을 모를 때의 말 — {@code gates.honorifics.unknown}. <b>코드가 성별을 추측하지 않는다</b> */
    public String unknownHonorific() {
        Object v = honorifics().get("unknown");
        return v == null ? "무인" : String.valueOf(v);
    }

    /**
     * 문파 안의 호칭 — {@code gates.honorifics.sect.<관계>.<성별>}.
     *
     * <p><b>★ 방향 주의: 호칭은 「부르는 자」가 아니라 「불리는 자」의 성별로 정해진다.</b>
     * 내가 사내든 계집이든, 나보다 먼저 입문한 사내는 <b>사형</b>이다.
     * 등록부도 그렇게 못을 박았다 ({@code sect.resolve_by: 대상의_성별}).
     *
     * <p>관계의 축은 <b>입문 순서</b>다 — {@code sect_life.yml} {@code brotherhood.order: 입문_순}
     * (나이가 아니다. 등록부가 이미 그렇게 정해 뒀다).
     *
     * @param targetGender <b>불리는 자</b>의 성별
     * @param relation 선배 / 후배 / 스승 / 문파장 (등록부의 키)
     */
    public String sectHonorific(String targetGender, String relation) {
        // 등록부에 없는 관계·성별이면 추측하지 않고 'unknown' 으로 물러선다
        Object v = sub(sub(honorifics(), "sect"), relation).get(targetGender);
        return v == null ? unknownHonorific() : String.valueOf(v);
    }

    /**
     * 입문 순서로 관계를 정한다 — <b>코드가 서열을 지어내지 않는다.</b>
     *
     * @param myOrder 나의 입문 순번, {@code theirOrder} 상대의 입문 순번 (작을수록 먼저 들었다)
     * @return 선배 / 후배 (동기는 후배로 보지 않는다 — 같으면 '선배' 예우)
     */
    public String sectRelation(int myOrder, int theirOrder) {
        return theirOrder <= myOrder ? "선배" : "후배";
    }

    /** 강호의 호칭 (문파 밖) — {@code gates.honorifics.jianghu.<성별>} */
    public String jianghuHonorific(String gender) {
        Object v = sub(honorifics(), "jianghu").get(gender);
        return v == null ? unknownHonorific() : String.valueOf(v);
    }

    /** 불문의 호칭 (아미·소림) — {@code gates.honorifics.buddhist.<성별>} */
    public String buddhistHonorific(String gender) {
        Object v = sub(honorifics(), "buddhist").get(gender);
        return v == null ? unknownHonorific() : String.valueOf(v);
    }

    // ───────────────────── 생성의 중립성 ─────────────────────

    /**
     * 성별이 <b>생성의 다른 선택지를 줄이면 안 되는가</b> — {@code creation_neutrality.restricts_creation: false}.
     * 눈이 이 깃발을 근거로 생성 문답을 검사한다.
     */
    public boolean mayRestrictCreation() {
        Object v = sub(cfg, "creation_neutrality").get("restricts_creation");
        return Boolean.TRUE.equals(v);
    }

    /** 성별은 <b>고르는 것이다</b> — 무작위도, 다른 문항에서 추론되는 것도 아니다 */
    public boolean chosenNeverInferred() {
        Object v = sub(cfg, "creation_neutrality").get("chosen_never_inferred");
        return !Boolean.FALSE.equals(v);
    }
}
