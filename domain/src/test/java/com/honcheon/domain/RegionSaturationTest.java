package com.honcheon.domain;

import com.honcheon.core.rules.RegionStateEngine;
import com.honcheon.core.rules.RulesConfig;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ★ <b>포화 검사</b> — 세계가 며칠 만에 치안 100 / 민심 100 으로 굳는가.
 *
 * <p>등록부(region_state.yml)가 지역 사건의 정본이 되면서 도적 처치의 값이 커졌다
 * (두목 치안 +2 → <b>+5</b>, 민심 +0 → <b>+3</b>). 그것이 설계 의도다. 그러나 설계 의도는
 * <b>사건이 사건일 때</b> 성립한다 — 5분마다 되살아나는 두목은 사건이 아니라 <b>농장</b>이다.
 *
 * <p>이 테스트는 그 사실을 <b>숫자로 못박는다</b>. 값을 바꾸면 이 숫자가 바뀌고, 바뀌었다는 것이
 * 눈에 보인다. (검사이지 <b>승인이 아니다</b> — 포화는 지금 실재하고, 보고서에 청구서로 올렸다.)
 *
 * <p>눈금: hunting_grounds.yml {@code boss_respawn_seconds: 300} · {@code cycle_seconds: 10}
 * (졸개는 구역당 10초에 한 마리씩 다시 선다) · 세계일 1일 = <b>실제 1일</b> (봇의 자정 스케줄러).
 */
class RegionSaturationTest {

    static Path cfg(String name) {
        return Path.of(System.getProperty("honcheon.config.dir", "../config")).resolve(name);
    }

    private static final RegionStateEngine RULES =
            new RegionStateEngine(RulesConfig.load(cfg("region_state.yml")));

    /** 청하현 초기값 (regions 표) */
    private static Map<String, Integer> cheongha() {
        return new LinkedHashMap<>(Map.of("치안", 50, "경제", 48, "민심", 55));
    }

    /** 같은 사건을 반복해 눈금이 상한(100)에 닿을 때까지 몇 번 걸리는가 */
    private static int killsToCap(String eventKey, String stat) {
        Map<String, Integer> state = cheongha();
        for (int kills = 1; kills <= 10_000; kills++) {
            RULES.applyDeltas(state, RULES.deltas(eventKey));
            if (state.get(stat) >= 100) {
                return kills;
            }
        }
        return -1;   // 닿지 않는다
    }

    @Test
    void 도적_두목을_반복_처치하면_치안이_열_번_만에_포화한다() {
        assertEquals(10, killsToCap("도적_두목_제거", "치안"),
                "치안 50 → 100 (+5씩)");
        assertEquals(15, killsToCap("도적_두목_제거", "민심"),
                "민심 55 → 100 (+3씩)");

        // 두목 리스폰 300초 → 10회 = 50분. **세계는 한 시간이면 치안이 굳는다.**
        int bossRespawnSeconds = 300;
        int minutesToCap = killsToCap("도적_두목_제거", "치안") * bossRespawnSeconds / 60;
        assertEquals(50, minutesToCap, "실제 시간으로 몇 분이면 치안이 100 이 되는가");
    }

    @Test
    void 졸개_소탕은_더_빠르다_리스폰이_빠르기_때문이다() {
        assertEquals(25, killsToCap("도적_부분_소탕", "치안"), "치안 50 → 100 (+2씩)");

        // 졸개는 구역당 10초에 한 마리 — 25마리면 250초, **4분 남짓**이다.
        int cycleSeconds = 10;
        assertTrue(killsToCap("도적_부분_소탕", "치안") * cycleSeconds <= 300,
                "졸개 농장은 5분 안에 치안을 포화시킨다");
    }

    /**
     * ★ <b>회복은 이것을 되돌리지 못한다.</b> recovery: 10일마다 기준값(50)을 향해 1.
     * 세계일 1일 = 실제 1일이므로, 한 시간 농장으로 올린 +50 을 세계가 잊는 데 <b>500일</b> 걸린다.
     */
    @Test
    void 회복력은_농장을_따라잡지_못한다() {
        Map<String, Integer> state = cheongha();
        state.put("치안", 100);   // 농장이 끝난 직후

        int days = 0;
        while (state.get("치안") > 50 && days < 10_000) {
            Map<String, Integer> step = RULES.recoveryDeltas(state, Map.of());
            RULES.applyDeltas(state, step);
            days += RULES.recoveryEveryDays();   // 회복은 10일에 한 걸음
        }

        assertEquals(500, days, "치안 100 이 기준값 50 으로 돌아오는 데 걸리는 **실제 일수**");
        assertEquals(50, state.get("치안"));
    }

    /**
     * 등록부의 값 자체는 <b>서사 규모의 사건</b>으로서 온당하다 —
     * "그 산길의 두목이 사라졌다"는 치안 +5 를 받을 만하다. 문제는 값이 아니라
     * <b>그 사건이 5분마다 다시 일어난다는 사실</b>이다 (다리가 나르는 사실이 틀렸다).
     */
    @Test
    void 등록부의_값은_한_번의_사건으로는_온당하다() {
        Map<String, Integer> state = cheongha();
        RULES.applyDeltas(state, RULES.deltas("도적_두목_제거"));
        assertEquals(55, state.get("치안"), "한 번의 두목 제거 — 치안 50 → 55 (극적이되 포화는 아니다)");
        assertEquals(58, state.get("민심"), "민심 55 → 58");
    }
}
