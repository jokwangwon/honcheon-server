package com.honcheon.bot;

import java.util.ArrayList;
import java.util.List;

/**
 * 청하현 의뢰 등록부 (봇 알파 풀) — quest_generation.yml grade_ladder·sources 정합.
 * 원칙: 의뢰 = 세계 상태의 증상. 알파 풀 5종은 등록된 사건·게시판 간판(북쪽 산길 정찰·
 * 상단 호위·약재 급구)과 맞물린다. 보수 = economy.yml 의뢰_보수 (신규 표 없음).
 * 게시는 세계일 기반 결정론 회전 — 매일 3건 (slots_per_region 하한의 알파 축소판).
 *
 * A2·B3 — 정적 풀은 그대로 두고 **동적 의뢰를 병합**한다 (Injections):
 *   루트 상태(favor·눈여겨봄) → 진운 지명 의뢰 / NPC 사망 → 사망 파생 의뢰.
 *   병합도 결정론이다 (같은 날 · 같은 세계 상태 = 같은 게시판).
 * 발주자(issuer)가 죽으면 그 의뢰는 게시판에서 사라진다 — 죽은 자는 사람을 부르지 못한다.
 */
final class Quests {

    record Approach(String label, String stat, int bonus) {
    }

    /**
     * issuer = 발주 NPC 등록 키 (null = 관·유족 등 개인이 아닌 발주처).
     * noseon = ★B-190 ④ — 이 의뢰의 완수가 원장에 적는 개인 노선 (덮다|도려내다|이용하다 · null = 무관).
     *   노선은 고르지 않는다, 드러난다 (사용자 확정 2026-07-31) — 선택지가 아니라 **한 일**이 적힌다.
     */
    record Quest(String key, String name, String grade, String realmReq, int resist,
                 String rewardKey, boolean combatMark, String brief, List<Approach> approaches,
                 String issuer, String noseon) {
        Quest(String key, String name, String grade, String realmReq, int resist,
              String rewardKey, boolean combatMark, String brief, List<Approach> approaches,
              String issuer) {
            this(key, name, grade, realmReq, resist, rewardKey, combatMark, brief, approaches,
                    issuer, null);
        }
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
                            new Approach("장터에서 파는 이를 수소문한다", "화술", 2)), "yumun"),
            new Quest("객잔_일손", "일손: 청하객잔", "잔심부름", "범인", 9, "잔심부름", false,
                    "한백이 장날 일손을 구한다 — 물지게든 심부름이든 몸이 재산이다.",
                    List.of(new Approach("물지게를 진다", "체력", 2),
                            new Approach("눈치껏 심부름을 돈다", "민첩", 2)), "hanbaek"),
            new Quest("북쪽_산길_정찰", "의뢰: 북쪽 산길 정찰", "조사_채집", "삼류", 11, "조사", false,
                    "산길 도적이 늘었다는 소문 — 소연이 흔적 확인을 의뢰했다. 싸우지 말고 보고 오라.",
                    List.of(new Approach("발자국과 모닥불 흔적을 쫓는다", "감각", 2),
                            new Approach("능선에 올라 길목을 살핀다", "민첩", 2)), "soyeon"),
            new Quest("유언비어_추적", "의뢰: 유언비어 추적", "조사_채집", "삼류", 11, "조사", false,
                    "객잔에 도는 흉흉한 소문의 출처를 밝혀라 — 민심은 소문보다 빨리 상한다.",
                    List.of(new Approach("객잔 구석에서 귀를 기울인다", "감각", 2),
                            new Approach("술을 사며 말을 붙인다", "화술", 2)), "soyeon"),
            new Quest("상단_호위", "구함: 상단 호위", "호위_소탕", "이류", 12, "단거리_호위", true,
                    "곽진의 상단이 남쪽 관도 단거리 호위를 구한다 — 표국 경력 우대, 담력 필수.",
                    List.of(new Approach("정면에서 습격을 막아선다", "근력", 2),
                            new Approach("먼저 알아채고 자리를 잡는다", "감각", 2)), "gwakjin"));

    /**
     * ★B-190 ④ — 제3막의 노선 의뢰 (자릿세 셋). 세계막이 제3막(paedocheon_daeripp)일 때만 오른다.
     * 썩은 뿌리의 형태는 자릿세다 (world_clock.yml sseogeun_ppuri_chujeok) — 그 부패를 두고
     * 무엇을 했는가가 곧 노선이다. 이름·문안 전부 【제안】 (★지어냄 — 빨간펜 대상).
     * 발주자 null = 이름을 밝히지 않는 발주처 (덮는 쪽과 이용하는 쪽은 원래 이름이 없다).
     */
    static final List<Quest> ACT3_NOSEON = List.of(
            new Quest("jarissae_deopgi", "부탁: 조용한 뒤처리", "조사_채집", "삼류", 11, "조사", false,
                    "맹의 이름이 적힌 자릿세 장부가 저잣거리에 돌기 전에 거둬 달라는 은밀한 부탁 — 출처는 묻지 마라.",
                    List.of(new Approach("장부가 도는 길목을 미리 막는다", "감각", 2),
                            new Approach("가진 자를 구슬려 조용히 넘겨받는다", "화술", 2)),
                    null, "덮다"),
            new Quest("jarissae_gobal", "의뢰: 자릿세 장부 고발", "조사_채집", "삼류", 11, "조사", false,
                    "맹의 이름으로 저잣거리를 뜯는 자들의 증좌를 모아 맹에 들이밀어라 — 맹이 제 살을 도려내게.",
                    List.of(new Approach("걷는 현장을 붙잡아 증좌를 챙긴다", "감각", 2),
                            new Approach("뜯긴 상인들의 증언을 모은다", "화술", 2)),
                    null, "도려내다"),
            new Quest("jarissae_heungjeong", "구함: 길목을 아는 자", "조사_채집", "삼류", 11, "조사", false,
                    "자릿세가 걷히는 길목과 시각을 아는 자를 찾는 이들이 있다 — 값은 후하고, 이름은 안 묻는다.",
                    List.of(new Approach("걷는 자들의 동선을 알아내 판다", "지혜", 2),
                            new Approach("양쪽을 다 만나 값을 올린다", "화술", 2)),
                    null, "이용하다"));

    /** 세계막에 딸린 의뢰 — 막이 지나가면 게시판에서 내려간다 (세계가 곧 발주처다) */
    static List<Quest> actQuests(String worldAct) {
        return "paedocheon_daeripp".equals(worldAct) ? ACT3_NOSEON : List.of();
    }

    /**
     * 오늘의 게시판 — 정적 3건(세계일 결정론 회전) + 동적 주입분.
     * 죽은 발주자의 의뢰는 뺀다 (dead), 대행 창구의 등급 상한(gradeCap)도 여기서 집행한다.
     */
    static List<Quest> board(int worldDay, List<Quest> injected, java.util.Set<String> dead,
                             java.util.function.Predicate<String> gradeAllowed) {
        int n = POOL.size();
        List<Quest> out = new ArrayList<>();
        for (int offset : new int[]{0, 1, 3}) {   // mod 5 에서 서로 다른 세 칸
            Quest q = POOL.get((worldDay + offset) % n);
            if (!out.contains(q)) {
                out.add(q);
            }
        }
        for (Quest q : injected) {
            if (out.stream().noneMatch(x -> x.key().equals(q.key()))) {
                out.add(q);
            }
        }
        return out.stream()
                .filter(q -> q.issuer() == null || !dead.contains(q.issuer()))
                .filter(q -> gradeAllowed.test(q.grade()))
                .toList();
    }

    /** 정적 풀 + 오늘의 동적 주입분에서 찾는다 (수주·수행의 조회 지점) */
    static Quest find(String key, List<Quest> injected) {
        Quest q = POOL.stream().filter(x -> x.key().equals(key)).findFirst().orElse(null);
        return q != null ? q : injected.stream().filter(x -> x.key().equals(key)).findFirst().orElse(null);
    }

    private Quests() {
    }
}
