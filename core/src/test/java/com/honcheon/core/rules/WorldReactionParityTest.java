package com.honcheon.core.rules;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
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
 */
class WorldReactionParityTest {

    static Path cfg(String name) {
        return Path.of(System.getProperty("honcheon.config.dir", "../config")).resolve(name);
    }

    @Test
    void 경로B_폭력루트_D7_상태가_파이썬_시뮬레이터와_일치한다() {
        RumorEngine rumorEngine = new RumorEngine(RulesConfig.load(cfg("rumor.yml")));
        FactionReactionEngine factions = new FactionReactionEngine(RulesConfig.load(cfg("faction_reaction.yml")));
        RegionStateEngine region = new RegionStateEngine(RulesConfig.load(cfg("region_state.yml")));
        // ★ 장부는 부르는 쪽이 든다 (RegionStateEngine 은 규칙일 뿐 — 프로덕션의 장부는 봇의 regions 표다).
        //   여기서는 시뮬레이터가 그 장부다.
        Map<String, Integer> state = new java.util.LinkedHashMap<>(
                Map.of("치안", 50, "경제", 48, "민심", 55));   // 청하현 초기값

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
                region.applyTo(state, "증거_없는_폭행");
                factions.apply("haomun", "미상의 낭인", "연락책_연락두절");
            }
            if (day == 2) {
                // 흑랑 보고 — 연락두절(+3) 기가산, 사망/중상 확인 차액 +1
                factions.applyPoints("haomun", "미상의 낭인", 1);
            }
            if (day == 3) {
                // 해질녘: 상단 습격 발생
                region.applyTo(state, "상단_습격_성공");
                r2 = rumorEngine.create("R2", 3, "sangdan_net", 3, 90,
                        Set.of("도적", "물류", "금전", "치안", "폭력"), "sangdan");
                rumors.add(r2);
                factions.apply("sangdan", "습격 사건", "금전_손실_대규모");
                factions.apply("gwan_gun", "습격 사건", "관할_치안_중대사건");
            }

            // ── 이하 자동: 소문 도달 → 세력 인지, 감쇠 ──
            for (RumorEngine.AwarenessEvent event : rumorEngine.awarenessOn(day, rumors, scored)) {
                scored.add(event.rumorId() + "|" + event.faction());
                String target = event.faction().equals("sangdan") ? "습격 사건" : "미상의 낭인";
                String input = event.accuracy() >= 70 ? "소문_도달_고정확도" : "소문_도달_관심일치";
                factions.apply(event.faction(), target, input);
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
        assertEquals(5, factions.score("haomun", "미상의 낭인"));
        assertEquals(2, factions.stage("haomun", "미상의 낭인"));
        assertEquals(6, factions.score("sangdan", "습격 사건"));
        assertEquals(2, factions.stage("sangdan", "습격 사건"));
        assertEquals(1, factions.score("orthodox", "미상의 낭인"));
        assertEquals(1, factions.stage("orthodox", "미상의 낭인"));
        assertEquals(3, factions.score("gwan_gun", "미상의 낭인"));
        assertEquals(3, factions.score("gwan_gun", "습격 사건"));
        assertEquals(1, factions.score("noklim", "미상의 낭인"));

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

    @Test
    void 우호_축이_작동한다_F3() {
        FactionReactionEngine factions = new FactionReactionEngine(RulesConfig.load(cfg("faction_reaction.yml")));

        // PT-001 소급: 습격 저지 = 상단에 공적 대 (+4) → '안면'
        assertEquals("낯섦", factions.favorLevelName("sangdan", "미상의 낭인"));
        factions.applyFavor("sangdan", "미상의 낭인", "공적_대");
        assertEquals(4, factions.favor("sangdan", "미상의 낭인"));
        assertEquals("안면", factions.favorLevelName("sangdan", "미상의 낭인"));

        // 의뢰 완수 2건 + 공적 대 1건 → 공신 (추천장 자격)
        factions.applyFavor("sangdan", "미상의 낭인", "공적_소");
        factions.applyFavor("sangdan", "미상의 낭인", "공적_소");
        factions.applyFavor("sangdan", "미상의 낭인", "공적_대");
        assertEquals(12, factions.favor("sangdan", "미상의 낭인"));
        assertEquals("신용", factions.favorLevelName("sangdan", "미상의 낭인"));

        // 정체 확인 → 병합: 두 축 모두 실제 대상으로 이전
        factions.mergeTarget("sangdan", "미상의 낭인", "player_1");
        assertEquals(12, factions.favor("sangdan", "player_1"));
        assertEquals(0, factions.favor("sangdan", "미상의 낭인"));
    }
}
