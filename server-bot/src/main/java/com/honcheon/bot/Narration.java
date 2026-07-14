package com.honcheon.bot;

/**
 * 서사 폴백 템플릿 — <b>사냥과 비무</b>의 결과를 산문으로 (llm.yml failure_handling: LLM 없이도 게임은 돈다).
 *
 * <p>7계 준수: 수치 은닉(마진·굴림값 언급 금지 — 판정 embed 가 따로 공개한다),
 * 등록제 명사, 길이 예산.
 *
 * <p><b>★ 서장(序章)의 글은 여기서 나갔다 (2026-07-13).</b> 이 파일에는
 * {@code INCIDENT_OPENING} · {@code FAMILY_COLOR} · {@code scene()} · {@code epilogue()} ·
 * {@code debut()} 가 있었다 — <b>발단 8종의 첫 문장과 집안 7종의 감각과 에필로그 10종이
 * 자바 코드 안에 박혀 있었다.</b> 등록제 위반이다: 코드가 이야기를 지고 있었다.
 *
 * <p>전부 <b>{@code config/seojang.yml}</b> 로 옮겼고, 읽는 손은 {@link Seojang} 이다.
 * <b>여기에 서장의 문장을 다시 적지 마라</b> — 두 벌이 되면 하나가 낡는다.
 *
 * <p><b>★ 남은 청구서:</b> 아래 {@link #hunt}·{@link #duel} 의 문장은 <b>아직 자바에 있다.</b>
 * 같은 병이다. 이번 바퀴의 범위가 「서장」이라 손대지 않았다 — 다음 바퀴에 등록부로 보내야 한다.
 */
final class Narration {

    private Narration() {
    }

    /** 사냥 결과 폴백 — 등급 5분류로 온도를 정한다 (수치는 embed 몫) */
    static String hunt(String beast, String tierName, boolean pelt) {
        return switch (grade(tierName)) {
            case CRIT_GOOD -> "몸이 먼저 움직였다 — 스스로도 놀랄 만큼 깨끗한 일수. " + beast
                    + "은(는) 소리도 없이 무너졌다. 오늘의 감각은 오래 기억날 것이다.";
            case GOOD -> pelt
                    ? "한 호흡에 끝났다. " + beast + "은(는) 미처 방향을 틀기도 전에 무너졌고, 가죽은 흠집 하나 없이 벗겨졌다."
                    : "한 호흡에 끝났다. " + beast + "은(는) 미처 방향을 틀기도 전에 무너졌다.";
            case MIXED -> "엎치락뒤치락 끝에 겨우 숨통을 끊었다. 몸 여기저기 생채기가 남았고, "
                    + (pelt ? "가죽도 성한 곳이 많지 않다." : "가죽은 못 쓰게 됐다 — 그래도 손에 익은 것이 남았다.");
            case BAD -> beast + "이(가) 더 빨랐다. 덤불 사이로 놓치고 나서야 옆구리가 쓰라린 것을 알았다 — 오늘은 여기까지다.";
            case CRIT_BAD -> "발을 헛디딘 순간 " + beast + "의 이빨이 코앞까지 왔다. 구른 끝에 간신히 빠져나왔다 — "
                    + "찢긴 옷자락이 산비탈에 남았다. 산이 오늘은 나를 거부한다.";
        };
    }

    /** 비무 결과 폴백 — 승/무/패는 엔진이 정했고, 여기는 예의만 */
    static String duel(String winner, String loser, boolean draw) {
        if (draw) {
            return "수십 합이 오갔지만 승부는 갈리지 않았다. 두 사람은 동시에 물러나 포권했다 — 오늘의 합은 여기까지.";
        }
        return "합이 갈렸다. " + winner + "의 마지막 일수가 반 박자 빨랐고, " + loser
                + "은(는) 깨끗이 패배를 인정하며 포권했다. 구경꾼들 사이에서 낮은 탄성이 새어 나왔다.";
    }

    private enum Grade { CRIT_GOOD, GOOD, MIXED, BAD, CRIT_BAD }

    /** judgment.yml result_tiers의 name 기준 5분류 — 수치가 아니라 결의 문제 */
    private static Grade grade(String tierName) {
        return switch (tierName) {
            case "대성공" -> Grade.CRIT_GOOD;
            case "성공" -> Grade.GOOD;
            case "아슬아슬한 성공", "부분 성공" -> Grade.MIXED;
            case "실패" -> Grade.BAD;
            default -> Grade.CRIT_BAD;
        };
    }
}
