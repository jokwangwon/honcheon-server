package com.honcheon.core.rules;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 세계 반응 루프 패리티 테스트 — tools/simulate_world_reaction.py 의
 * '경로 B(폭력 루트)' 재생 결과와 D+7 종료 상태가 일치해야 한다.
 * 파이썬 시뮬레이터가 자바 엔진의 스펙이다.
 *
 * <p>★ <b>장부는 부르는 쪽이 든다.</b> {@link RegionStateEngine} 도 {@link FactionReactionEngine} 도
 * 이제 상태를 들고 있지 않다 (규칙일 뿐이다) — 프로덕션의 장부는 봇의 {@code regions}·
 * {@code faction_standing} 표이고, 여기서는 <b>이 테스트의 맵이 그 장부다</b>.
 * 엔진이 제 메모리에 점수를 쌓던 시절, 이 테스트는 초록이었지만 <b>플레이어는 그 코드를 겪지 않았다.</b>
 * 이제 테스트와 프로덕션이 <b>같은 산수</b>({@code nextAttention}/{@code nextFavor})를 부른다.
 */
class WorldReactionParityTest {

    static Path cfg(String name) {
        return Path.of(System.getProperty("honcheon.config.dir", "../config")).resolve(name);
    }

    /** 시뮬레이터의 장부 — 프로덕션에서는 봇의 faction_standing 표가 이 자리에 있다 */
    private static final class Ledger {
        private final Map<String, Integer> values = new HashMap<>();

        int get(String faction, String target) {
            return values.getOrDefault(faction + "|" + target, 0);
        }

        void put(String faction, String target, int value) {
            values.put(faction + "|" + target, value);
        }
    }

    @Test
    void 경로B_폭력루트_D7_상태가_파이썬_시뮬레이터와_일치한다() {
        RumorEngine rumorEngine = new RumorEngine(RulesConfig.load(cfg("rumor.yml")));
        FactionReactionEngine rules = new FactionReactionEngine(RulesConfig.load(cfg("faction_reaction.yml")));
        RegionStateEngine region = new RegionStateEngine(RulesConfig.load(cfg("region_state.yml")));

        Map<String, Integer> state = new java.util.LinkedHashMap<>(
                Map.of("치안", 50, "경제", 48, "민심", 55));   // 청하현 초기값
        Ledger attention = new Ledger();

        List<RumorEngine.Rumor> rumors = new ArrayList<>();
        Set<String> scored = new HashSet<>();
        RumorEngine.Rumor r1 = null;
        RumorEngine.Rumor r2 = null;

        for (int day = 0; day <= 7; day++) {
            // ── 시나리오 고정 사건 (파이썬 시뮬레이터와 동일 입력) ──
            if (day == 0) {
                // 밤: 객잔에서 묵삼 기습 제압 (판정: 성공 — 흔적 남음)
                r1 = rumorEngine.create("R1", 0, "inn_net", 2, 70,
                        Set.of("폭력", "사파", "무인", "치안", "조직원"), null);
                rumors.add(r1);
                region.applyDeltas(state, region.deltas("증거_없는_폭행"));
                apply(rules, attention, "haomun", "미상의 낭인", rules.attentionInput("연락책_연락두절"));
            }
            if (day == 2) {
                // 흑랑 보고 — 연락두절(+3) 기가산, 사망/중상 확인 차액 +1
                apply(rules, attention, "haomun", "미상의 낭인", 1);
            }
            if (day == 3) {
                // 해질녘: 상단 습격 발생
                region.applyDeltas(state, region.deltas("상단_습격_성공"));
                r2 = rumorEngine.create("R2", 3, "sangdan_net", 3, 90,
                        Set.of("도적", "물류", "금전", "치안", "폭력"), "sangdan");
                rumors.add(r2);
                apply(rules, attention, "sangdan", "습격 사건", rules.attentionInput("금전_손실_대규모"));
                apply(rules, attention, "gwan_gun", "습격 사건", rules.attentionInput("관할_치안_중대사건"));
            }

            // ── 이하 자동: 소문 도달 → 세력 인지, 감쇠 ──
            for (RumorEngine.AwarenessEvent event : rumorEngine.awarenessOn(day, rumors, scored)) {
                scored.add(event.rumorId() + "|" + event.faction());
                String target = event.faction().equals("sangdan") ? "습격 사건" : "미상의 낭인";
                // ★ 프로덕션(GameListener.factionAwareness)이 부르는 그 규칙 그대로다
                apply(rules, attention, event.faction(), target, rules.rumorInput(event.accuracy()));
            }
            for (RumorEngine.Rumor rumor : rumors) {
                rumorEngine.decayOn(day, rumor);
            }
        }

        // ── D+7 종료 상태 — 파이썬 시뮬레이터 출력과 대조 ──
        // 지역: 치안 43 / 경제 45 / 민심 52
        assertEquals(43, state.get("치안"));
        assertEquals(45, state.get("경제"));
        assertEquals(52, state.get("민심"));

        // 세력 반응: 하오문 5 [2단계 정보 수집] / 상단 6 [2단계] / 정파 1 [1단계] /
        //           관군 (미상 3, 습격 3) / 녹림 1
        assertEquals(5, attention.get("haomun", "미상의 낭인"));
        assertEquals(2, rules.stageOf(attention.get("haomun", "미상의 낭인")).stage());
        assertEquals(6, attention.get("sangdan", "습격 사건"));
        assertEquals(2, rules.stageOf(attention.get("sangdan", "습격 사건")).stage());
        assertEquals(1, attention.get("orthodox", "미상의 낭인"));
        assertEquals(1, rules.stageOf(attention.get("orthodox", "미상의 낭인")).stage());
        assertEquals(3, attention.get("gwan_gun", "미상의 낭인"));
        assertEquals(3, attention.get("gwan_gun", "습격 사건"));
        assertEquals(1, attention.get("noklim", "미상의 낭인"));

        // 소문: R1 소멸(기억 태그 잔존), R2 강도 2
        assertTrue(r1.isDead());
        assertEquals(2, r2.intensity());

        // 도달 스케줄: 정파 연락망 D+3 정확도 65 (과장) / 관군 망 R2 D+5 정확도 80 (사실적)
        assertEquals(3, r1.arrivals().get("orthodox_net").day());
        assertEquals(65, r1.arrivals().get("orthodox_net").accuracy());
        assertEquals("과장", rumorEngine.accuracyBand(65));
        assertEquals(5, r2.arrivals().get("gwan_net").day());
        assertEquals(80, r2.arrivals().get("gwan_net").accuracy());
        assertEquals("사실적", rumorEngine.accuracyBand(80));
    }

    /** 주목 한 걸음 — 규칙에게 다음 값을 묻고, <b>장부에 적는 것은 부르는 쪽</b>이다 */
    private static void apply(FactionReactionEngine rules, Ledger ledger,
                              String faction, String target, int points) {
        ledger.put(faction, target, rules.nextAttention(ledger.get(faction, target), points));
    }

    @Test
    void 우호_축이_작동한다_F3() {
        FactionReactionEngine rules = new FactionReactionEngine(RulesConfig.load(cfg("faction_reaction.yml")));
        Ledger favor = new Ledger();
        int cap = rules.favorMax();

        // PT-001 소급: 습격 저지 = 상단에 공적 대 (+4) → '안면'
        assertEquals("낯섦", rules.favorLevelOf(favor.get("sangdan", "미상의 낭인")).name());
        gain(rules, favor, "sangdan", "미상의 낭인", "공적_대", cap);
        assertEquals(4, favor.get("sangdan", "미상의 낭인"));
        assertEquals("안면", rules.favorLevelOf(favor.get("sangdan", "미상의 낭인")).name());

        // 의뢰 완수 2건 + 공적 대 1건 → 공신 (추천장 자격)
        gain(rules, favor, "sangdan", "미상의 낭인", "공적_소", cap);
        gain(rules, favor, "sangdan", "미상의 낭인", "공적_소", cap);
        gain(rules, favor, "sangdan", "미상의 낭인", "공적_대", cap);
        assertEquals(12, favor.get("sangdan", "미상의 낭인"));
        assertEquals("신용", rules.favorLevelOf(favor.get("sangdan", "미상의 낭인")).name());

        // 정체 확인 → 병합: 두 축 모두 실제 대상으로 이전
        // ★ 규칙은 다음 값을 돌려줄 뿐이고, **옮겨 적는 것(원본을 0 으로)은 장부의 일**이다.
        FactionReactionEngine.Merged merged = rules.mergeIdentity(
                0, favor.get("sangdan", "미상의 낭인"), 0,
                0, favor.get("sangdan", "player_1"));
        favor.put("sangdan", "player_1", merged.favor());
        favor.put("sangdan", "미상의 낭인", 0);

        assertEquals(12, favor.get("sangdan", "player_1"));
        assertEquals(0, favor.get("sangdan", "미상의 낭인"));
    }

    /** 우호 한 걸음 — 등록된 입력 키로 (등록되지 않은 입력은 세계에 없다) */
    private static void gain(FactionReactionEngine rules, Ledger ledger,
                             String faction, String target, String inputKey, int cap) {
        ledger.put(faction, target,
                rules.nextFavor(ledger.get(faction, target), rules.favorInput(inputKey), cap));
    }
}
