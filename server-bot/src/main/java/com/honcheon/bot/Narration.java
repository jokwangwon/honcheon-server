package com.honcheon.bot;

import java.util.Map;

/**
 * 서장 서사 — 폴백 템플릿 (llm.yml failure_handling: LLM 없이도 게임은 돈다).
 * 7계 준수: 수치 은닉(마진·굴림값 언급 금지 — 판정 embed가 따로 공개한다),
 * 등록제 명사(청하현·발단 키만 사용), 길이 예산(장면 3~5문장).
 * LLM 결선 시 이 클래스는 폴백으로 강등된다 — 시그니처는 유지.
 */
final class Narration {

    private Narration() {
    }

    // ─── 발단별 첫 장면 도입 — player_creation.yml inciting_incidents의 cause를 산문화 ───
    private static final Map<String, String> INCIDENT_OPENING = Map.of(
            "가문의_몰락", "장부가 문제였다고 했다. 어른들의 언성이 담을 넘던 그 밤, 아버지는 너를 흔들어 깨웠다.",
            "습격", "개 짖는 소리가 뚝 끊겼다. 그다음에 들린 것은 말발굽과, 마을 어귀에서 번지는 불빛이었다.",
            "역병", "이레 전부터 마을에 곡소리가 끊이지 않았다. 오늘은 옆집 차례였고, 어머니는 짐을 쌌다.",
            "목격", "봐서는 안 될 것을 봤다. 달빛 아래 그 사람이 고개를 돌렸을 때, 너는 이미 뛰고 있었다.",
            "맡겨짐", "아버지는 네 손을 낯선 어른의 손에 쥐여 주고 눈을 마주치지 않았다. 그날 밤 너는 그 집을 나왔다.",
            "핏줄의_비밀", "벽장 깊은 곳의 그것이 누구의 것이었는지 안 날 밤, 문밖에서 낯선 발소리가 멎었다.",
            "아이_거두기", "\"재목이다.\" 그 한마디를 남기고 간 자들이 다시 온다는 소문이 돌던 밤이었다.",
            "수행_파견", "\"강호를 눈으로 보고 오너라. 이름은 밝히지 말고.\" 아버지의 명이 떨어진 밤, 짐은 이미 꾸려져 있었다.");

    // ─── 집안별 정착 장면 색 — 청하현에서 아이가 기댈 첫 감각 ───
    private static final Map<String, String> FAMILY_COLOR = Map.of(
            "상가의_자식", "장터의 셈 소리는 고향의 그것과 닮아 있었다.",
            "농가의_자식", "흙냄새만은 여기도 같았다.",
            "객잔집_자식", "국밥 김 오르는 냄새에 코끝이 시큰했다.",
            "의원집_자식", "약재 달이는 냄새가 골목에 배어 있었다.",
            "표국집_자식", "짐수레와 호위꾼들 — 눈에 익은 풍경이었다.",
            "무가의_자식", "허리에 와야 할 검의 무게가 없는 것이 오히려 어색했다.",
            "몰락_무가의_자식", "품 안의 가보가 심장처럼 무거웠다.");

    /** 장면 서사 — prevTier(직전 판정 등급명)에 따라 이음새가 달라진다 */
    static String scene(GameListener.Character ch, int idx, String prevTier) {
        if ("수행_파견".equals(ch.incident())) {
            return dispatchScene(idx, bridge(prevTier));
        }
        String bridge = bridge(prevTier);
        return switch (idx) {
            case 0 -> INCIDENT_OPENING.getOrDefault(ch.incident(),
                    "그날 밤, 모든 것이 달라졌다.")
                    + "\n\n숨이 목까지 차올랐다. 지금, 무엇을 하는가.";
            case 1 -> bridge + "동트기 전의 관도는 텅 비어 있었다. 뒤는 돌아볼 수 없다 — "
                    + "돌아볼 곳이 남아 있지 않으니까. 발바닥이 부르트도록 걸어도, "
                    + "쫓아오는 것이 사람인지 기억인지 알 수 없었다.\n\n길은 세 갈래로 이어진다.";
            case 2 -> bridge + "성문도 없는 작은 고을 — 청하현이라 했다. "
                    + FAMILY_COLOR.getOrDefault(ch.family(), "모든 것이 낯설었다.")
                    + " 하지만 주머니는 가볍고 해는 저문다. 오늘 밤 잠잘 곳과 한 끼가 먼저다.";
            default -> "이야기는 계속된다.";
        };
    }

    /**
     * 무가의 자식 전용 서장 「수행 파견」 — 재난이 아니라 명령. 도주극이 아닌 첫 출문.
     * 판돈이 다르다: 목숨이 아니라 이름과 체면, 그리고 가문의 기대.
     */
    private static String dispatchScene(int idx, String bridge) {
        return switch (idx) {
            case 0 -> "\"강호를 눈으로 보고 오너라. 이름은 밝히지 말고, 검은 뽑지 말고.\"\n"
                    + "사랑채의 아버지는 그 말뿐이었다. 문책인지 시험인지 물을 틈도 없었다. "
                    + "떠나는 날 새벽, 무기고 문이 열려 있었다 — 가주의 무언의 허락이다. "
                    + "수행원은 없다. 처음으로, 혼자다.";
            case 1 -> bridge + "관도에 오르자 세상이 달라졌다. 집안의 담장 안에서 이름이 하던 일 — "
                    + "길을 비키게 하고, 값을 깎아 주고, 시비를 걸러 주던 그 모든 일을 이제 아무것도 해 주지 않는다. "
                    + "허리의 검이 문득 무거웠다. 이것이 아버지가 보라던 강호인가.\n\n길은 세 갈래로 이어진다.";
            case 2 -> bridge + "성문도 없는 작은 고을 — 청하현. 가문의 연고가 닿는 전장에 월례 전표가 와 있을 것이다. "
                    + "굶을 걱정은 없다. 다만 여기서 나는 아무개다 — 이름을 대면 편해지고, 대는 순간 수행은 끝난다. "
                    + "어디서부터 시작할 것인가.";
            default -> "이야기는 계속된다.";
        };
    }

    /** 서장 에필로그 — 마지막 판정 등급이 첫 정착의 온도를 정한다 */
    static String epilogue(GameListener.Character ch, String tierName) {
        if ("수행_파견".equals(ch.incident())) {
            // 판돈이 다르므로 착지도 다르다 — 잠자리가 아니라 이름을 지켰는가
            String landing = switch (grade(tierName)) {
                case GOOD -> "첫날의 일들이 매끄럽게 풀렸다. 전표는 수중에, 이름은 아직 아무도 모른다 — 좋은 시작이다.";
                case MIXED -> "일은 됐지만 매끄럽지는 않았다. 객잔 주인이 힐끗거린다 — 말씨인지 예법인지, 어딘가 태가 났나 보다.";
                case BAD -> "첫날부터 꼬였다. 저잣거리에서 시비가 붙을 뻔했고, 하마터면 이름이 나올 뻔했다. "
                        + "검자루를 쥐었다 놓은 손바닥에 땀이 배어 있었다.";
            };
            return landing + "\n\n" + ch.name() + "의 수행은 이렇게 시작되었다. "
                    + "돌아갈 집이 있는 자의 강호 — 그것이 짐인지 힘인지는, 이제부터 알게 된다.";
        }
        String landing = switch (grade(tierName)) {
            case GOOD -> "다행히 첫날 밤부터 지붕 아래서 잘 수 있었다. 주인은 무뚝뚝했지만 밥그릇은 넉넉했다.";
            case MIXED -> "처마 밑이나마 자리를 얻었다. 내일도 써 주겠다는 말은 없었지만, 내쫓지도 않았다.";
            case BAD -> "문전박대였다. 결국 다리 밑에서 첫 밤을 났다 — 하지만 살아 있다. 그거면 됐다.";
        };
        return landing + "\n\n" + ch.name() + "의 강호는 이렇게 시작되었다. "
                + "유년의 기억 다섯 조각과 " + ch.incident().replace('_', ' ')
                + "의 흔적을 품은 채 — 청하현의 아침이 밝는다.";
    }

    private static String bridge(String prevTier) {
        if (prevTier == null) {
            return "";
        }
        return switch (grade(prevTier)) {
            case GOOD -> "고비는 넘겼다. ";
            case MIXED -> "가까스로였다. 심장은 아직 뛰고 있다. ";
            case BAD -> "일이 틀어졌다. 그래도 발은 멈추지 않았다. ";
        };
    }

    // ─── 출도·청하현 (봇 베타) ───

    /** 서장 종료 → 출도 안내 — 지역 채널이 열린다 */
    static String debut(GameListener.Character ch) {
        return "몇 계절이 흘렀다. " + ch.name() + "은(는) 청하현의 골목과 장터, 뒷산 능선까지 발로 익혔다. "
                + "이제 이 고을이 낯설지 않다 — 하지만 강호는 이제부터다.\n\n"
                + "**청하현 지역 채널에서 `/혼천 사냥` `/혼천 비무` 를 쓸 수 있다.**";
    }

    /** 사냥 결과 폴백 — tierName 3분류로 온도만 정한다 (수치는 embed 몫) */
    static String hunt(String beast, String tierName, boolean pelt) {
        return switch (grade(tierName)) {
            case GOOD -> pelt
                    ? "한 호흡에 끝났다. " + beast + "은(는) 미처 방향을 틀기도 전에 무너졌고, 가죽은 흠집 하나 없이 벗겨졌다."
                    : "한 호흡에 끝났다. " + beast + "은(는) 미처 방향을 틀기도 전에 무너졌다.";
            case MIXED -> "엎치락뒤치락 끝에 겨우 숨통을 끊었다. 몸 여기저기 생채기가 남았고, "
                    + (pelt ? "가죽도 성한 곳이 많지 않다." : "가죽은 못 쓰게 됐다 — 그래도 손에 익은 것이 남았다.");
            case BAD -> beast + "이(가) 더 빨랐다. 덤불 사이로 놓치고 나서야 옆구리가 쓰라린 것을 알았다 — 오늘은 여기까지다.";
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

    private enum Grade { GOOD, MIXED, BAD }

    /** judgment.yml result_tiers의 name 기준 3분류 — 수치가 아니라 결의 문제 */
    private static Grade grade(String tierName) {
        return switch (tierName) {
            case "대성공", "성공" -> Grade.GOOD;
            case "아슬아슬한 성공", "부분 성공" -> Grade.MIXED;
            default -> Grade.BAD;
        };
    }
}
