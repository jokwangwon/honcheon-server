package com.honcheon.bot;

import java.util.List;

/**
 * 청하현 의뢰 등록부 (봇 알파 풀) — quest_generation.yml grade_ladder·sources 정합.
 * 원칙: 의뢰 = 세계 상태의 증상. 알파 풀 5종은 등록된 사건·게시판 간판(북쪽 산길 정찰·
 * 상단 호위·약재 급구)과 맞물린다. 보수 = economy.yml 의뢰_보수 (신규 표 없음).
 * 게시는 세계일 기반 결정론 회전 — 매일 3건 (slots_per_region 하한의 알파 축소판).
 */
final class Quests {

    record Approach(String label, String stat, int bonus) {
    }

    record Quest(String key, String name, String grade, String realmReq, int resist,
                 String rewardKey, boolean combatMark, String brief, List<Approach> approaches) {
    }

    /** 경지 사다리 서열 — 격상 도전 판별용 (gate 느슨: 금지가 아니라 사선) */
    static int realmRank(String realm) {
        return switch (realm) {
            case "삼류" -> 1;
            case "이류" -> 2;
            case "일류" -> 3;
            default -> "범인".equals(realm) ? 0 : 4;
        };
    }

    static final List<Quest> POOL = List.of(
            new Quest("약재_급구", "급구: 약재", "잔심부름", "범인", 9, "잔심부름", false,
                    "의방 유문이 열병 대비 약재를 급히 구한다 — 산기슭의 흔한 약초라도 좋다.",
                    List.of(new Approach("산기슭에서 약초를 찾는다", "지혜", 2),
                            new Approach("장터에서 파는 이를 수소문한다", "화술", 2))),
            new Quest("객잔_일손", "일손: 청하객잔", "잔심부름", "범인", 9, "잔심부름", false,
                    "한백이 장날 일손을 구한다 — 물지게든 심부름이든 몸이 재산이다.",
                    List.of(new Approach("물지게를 진다", "체력", 2),
                            new Approach("눈치껏 심부름을 돈다", "민첩", 2))),
            new Quest("북쪽_산길_정찰", "의뢰: 북쪽 산길 정찰", "조사_채집", "삼류", 11, "조사", false,
                    "산길 도적이 늘었다는 소문 — 소연이 흔적 확인을 의뢰했다. 싸우지 말고 보고 오라.",
                    List.of(new Approach("발자국과 모닥불 흔적을 쫓는다", "감각", 2),
                            new Approach("능선에 올라 길목을 살핀다", "민첩", 2))),
            new Quest("유언비어_추적", "의뢰: 유언비어 추적", "조사_채집", "삼류", 11, "조사", false,
                    "객잔에 도는 흉흉한 소문의 출처를 밝혀라 — 민심은 소문보다 빨리 상한다.",
                    List.of(new Approach("객잔 구석에서 귀를 기울인다", "감각", 2),
                            new Approach("술을 사며 말을 붙인다", "화술", 2))),
            new Quest("상단_호위", "구함: 상단 호위", "호위_소탕", "이류", 12, "단거리_호위", true,
                    "곽진의 상단이 남쪽 관도 단거리 호위를 구한다 — 표국 경력 우대, 담력 필수.",
                    List.of(new Approach("정면에서 습격을 막아선다", "근력", 2),
                            new Approach("먼저 알아채고 자리를 잡는다", "감각", 2))));

    /** 오늘의 게시판 — 세계일 기반 결정론 3건 (오프셋 0·1·3은 mod 5 에서 항상 서로 다르다) */
    static List<Quest> board(int worldDay) {
        int n = POOL.size();
        return List.of(POOL.get(worldDay % n), POOL.get((worldDay + 1) % n), POOL.get((worldDay + 3) % n));
    }

    static Quest byKey(String key) {
        return POOL.stream().filter(q -> q.key().equals(key)).findFirst().orElse(null);
    }

    private Quests() {
    }
}
