package com.honcheon.mvt;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>타격 허용(打擊 許容) — 누가 맞을 수 있는가의 문 (B-119).</b>
 *
 * <p>사용자 실측 (2026-07-14): <i>"npc와 동물등 안때려짐 (이거에 대한 조건이 필요할듯)"</i> —
 * 그리고 설계 정정: <i>"마을 npc도 때려져야합니다. 몰래 죽일수도 있어야 해요."</i>
 *
 * <p>그래서 이 문은 <b>기본이 열려 있다</b>. 살아 있는 것은 다 맞는다 — 문이 닫는 것은
 * 등록부의 예외 표식({@code combat.yml strike_admission.immune_marks})뿐이고, 그 목록은
 * <b>기본이 빈 목록</b>이다. 보호 대상이 필요해지면 등록부에 표식 하나를 적는다 (코드는 안 바뀐다).
 *
 * <p><b>이 클래스는 순수하다</b> — Bukkit 을 모른다. 표식(문자열)을 받아 참/거짓을 돌려줄 뿐이다.
 * 그래서 서버 없이 시험된다 ({@code tools/StrikeAdmissionSelfTest.java} — 눈을 시험하는 눈).
 * 몸에서 표식을 읽는 일(PDC 판독)은 {@code SkillListener.strikeMark} 의 몫이다.
 *
 * <p><b>이 문이 아닌 것</b>: "맞았는가"(태세 마진 · check_formula)와 "얼마나 아픈가"(damage) —
 * 그 판정층은 불변이다. 사람 대 사람의 계약(안전 지역 B-006 · 비무 Sparring)도 이 문 밖이다.
 */
public final class StrikeAdmission {

    // ─── 표식 어휘 — combat.yml strike_admission.marks 와 1:1 (코드가 낱말을 지어내지 않는다) ───
    public static final String MARK_PLAYER = "플레이어";
    public static final String MARK_TOWN_NPC = "마을NPC";
    public static final String MARK_SPAR = "비무상대";
    public static final String MARK_BANDIT = "도적";
    public static final String MARK_LIVESTOCK = "가축";
    public static final String MARK_PREY = "사냥감";
    public static final String MARK_POPULACE = "행인";
    public static final String MARK_REGION = "지역사람";

    private final boolean defaultAllow;
    private final Set<String> immune;

    private StrikeAdmission(boolean defaultAllow, Set<String> immune) {
        this.defaultAllow = defaultAllow;
        this.immune = immune;
    }

    /**
     * 등록부 절({@code strike_admission}) → 문. 절이 없으면 <b>전부 허용</b>으로 선다 —
     * 서버는 떠야 하고, 이 문의 기본 자세가 원래 "열림"이다 (등록부가 그렇게 적었다).
     */
    public static StrikeAdmission load(Map<String, Object> section) {
        if (section == null || section.isEmpty()) {
            return new StrikeAdmission(true, Set.of());
        }
        boolean allow = "허용".equals(String.valueOf(section.getOrDefault("default", "허용")));
        Set<String> immune = section.get("immune_marks") instanceof List<?> list
                ? Set.copyOf(list.stream().map(String::valueOf).toList())
                : Set.of();
        return new StrikeAdmission(allow, immune);
    }

    /**
     * 이 표식의 몸에 칼이 서는가 — <b>순수 판정</b>.
     * 표식 없는 몸({@code null} = 무명)은 기본값을 따른다.
     */
    public boolean allowed(String mark) {
        if (!defaultAllow) {
            return false;   // 등록부가 문을 통째로 닫았다 — 코드가 예외를 지어내지 않는다
        }
        return mark == null || !immune.contains(mark);
    }

    /** 닫힌 이유 — 화면이 말할 수 있게 (침묵하는 게이트는 버그로 보인다) */
    public String refusal(String mark) {
        if (!defaultAllow) {
            return "칼이 서지 않는다 (strike_admission.default)";
        }
        return mark != null && immune.contains(mark)
                ? mark + " — 칼이 서지 않는다 (strike_admission.immune_marks)" : null;
    }

    /**
     * 몸의 신원 → 표식 — <b>순수 환산</b> (PDC 판독 결과를 받는다. Bukkit 은 부르는 쪽의 몫).
     * 우선순위: 사람(플레이어) &gt; 마을 NPC &gt; 사냥터 등록부(비무상대·사람·가축·짐승) &gt;
     * 인구층(행인·지역사람). 아무 표식도 없으면 {@code null}(무명) — 기본값을 따른다.
     *
     * @param player       사람인가 (이 문 밖 — 안전 지역·비무 계약이 다룬다. 표식만 준다)
     * @param townNpc      마을 계약 NPC 인가 (PDC honcheon:town_npc)
     * @param foeKind      사냥터 등록부의 몸이면 그 종별 (foe_kind: 짐승|사람), 아니면 null
     * @param foeRank      짐승의 격 (beast_rank: 들짐승|맹수|영물|가축), 없으면 null
     * @param foeRole      배역 (foe_role: 졸개|두목|짐승|비무상대), 없으면 null
     * @param populace     무명 행인인가 (PDC honcheonmvt:populace)
     * @param regionPerson 지역 사람인가 (PDC honcheonmvt:region_npc)
     */
    public static String mark(boolean player, boolean townNpc, String foeKind, String foeRank,
                              String foeRole, boolean populace, boolean regionPerson) {
        if (player) {
            return MARK_PLAYER;
        }
        if (townNpc) {
            return MARK_TOWN_NPC;
        }
        if (foeKind != null) {
            if (MARK_SPAR.equals(foeRole)) {
                return MARK_SPAR;
            }
            if ("사람".equals(foeKind)) {
                return MARK_BANDIT;
            }
            return MARK_LIVESTOCK.equals(foeRank) ? MARK_LIVESTOCK : MARK_PREY;
        }
        if (populace) {
            return MARK_POPULACE;
        }
        return regionPerson ? MARK_REGION : null;
    }
}
