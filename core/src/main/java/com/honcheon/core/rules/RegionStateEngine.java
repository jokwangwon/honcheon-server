package com.honcheon.core.rules;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 지역 상태 규칙 — config/region_state.yml 의 자바 판독기. 치안·경제·민심(0~100, 기준 50).
 *
 * <p><b>★ 이 클래스는 장부가 아니다. 규칙이다.</b>
 *
 * <p>예전에는 여기에 {@code state} 맵이 있었다 — 그리고 봇의 {@code regions} 표에도 같은 값이 있었다.
 * 같은 것을 세는 장부가 둘이면 언젠가 <b>두 세계가 갈라진다</b>. 그리고 실제로 갈라져 있었다:
 * 이 맵은 아무도 안 읽었고(프로덕션 호출자 0), 그 사이 {@code recoveryStep()} 이 약속한 자연 회복은
 * <b>한 번도 집행되지 않았다</b> — 청하현의 치안은 한 번 내려가면 영원히 내려가 있었다.
 *
 * <p>그래서 정본을 하나로 잘랐다:
 * <ul>
 *   <li><b>장부는 봇의 SQLite</b> ({@code regions} 표 — {@code Db.region()} / {@code Db.nudgeRegion()}).
 *       재기동을 넘어 살아남는 유일한 곳이다.</li>
 *   <li><b>규칙은 여기 하나</b> — 사건별 델타(event_deltas)와 자연 회복(recovery)의 산수.
 *       상태를 들고 있지 않으므로 <b>갈라질 수가 없다.</b></li>
 * </ul>
 *
 * <p>부르는 쪽이 제 장부를 넘기고, 이 클래스는 <b>델타만 돌려준다</b>. 적는 것은 장부 주인의 일이다.
 */
public final class RegionStateEngine {

    private final Map<String, Map<String, Integer>> eventDeltas;
    private final int recoveryEveryDays;
    private final int recoveryToward;
    private final int recoveryAmount;
    private final int min;
    private final int max;

    @SuppressWarnings("unchecked")
    public RegionStateEngine(Map<String, Object> config) {
        Map<String, Object> rawDeltas = RulesConfig.section(config, "event_deltas");
        Map<String, Map<String, Integer>> parsed = new HashMap<>();
        rawDeltas.forEach((event, deltas) -> {
            Map<String, Integer> statDeltas = new HashMap<>();
            ((Map<String, Object>) deltas).forEach((stat, value) ->
                    statDeltas.put(stat, RulesConfig.intValue(value)));
            parsed.put(event, Map.copyOf(statDeltas));
        });
        this.eventDeltas = Map.copyOf(parsed);

        Map<String, Object> recovery = RulesConfig.section(config, "recovery");
        this.recoveryEveryDays = RulesConfig.intValue(recovery.get("every_days"));
        this.recoveryToward = RulesConfig.intValue(recovery.get("toward"));
        this.recoveryAmount = RulesConfig.intValue(recovery.get("amount"));

        Map<String, Object> scale = RulesConfig.section(config, "scale");
        this.min = RulesConfig.intValue(scale.get("min"));
        this.max = RulesConfig.intValue(scale.get("max"));
    }

    /** 사건 하나가 지역에 얹는 값 (region_state.yml event_deltas) — 등록되지 않은 사건은 세계에 없다 */
    public Map<String, Integer> deltas(String eventKey) {
        Map<String, Integer> deltas = eventDeltas.get(eventKey);
        if (deltas == null) {
            throw new IllegalArgumentException("정의되지 않은 지역 사건: " + eventKey);
        }
        return deltas;
    }

    /** 사건을 <b>부르는 쪽의 장부에</b> 적용한다 (0~100 clamp). 이 클래스는 그 장부를 기억하지 않는다 */
    public Map<String, Integer> applyTo(Map<String, Integer> ledger, String eventKey) {
        deltas(eventKey).forEach((stat, delta) -> ledger.merge(stat, delta,
                (now, d) -> Math.max(min, Math.min(max, now + d))));
        return ledger;
    }

    /** region_state.yml recovery.every_days — 이 날수마다 한 걸음 (10일) */
    public int recoveryEveryDays() {
        return recoveryEveryDays;
    }

    /** 기준값 — 사건이 없으면 세계가 되돌아가는 자리 (50) */
    public int recoveryToward() {
        return recoveryToward;
    }

    /**
     * ★ 자연 회복 한 걸음 — <b>10년 만에 처음 불리는 규칙</b>.
     *
     * <p>사건이 없으면 세계는 기준값(50)으로 되돌아간다. 이것이 없으면 <b>마을을 구해도 치안이
     * 제자리로 오지 않는다</b> — 눈금은 한 번 내려가면 영원히 내려가 있고, 플레이어의 선행은
     * 세계에 흔적을 남기지 못한다. 회복이 있어야 하락이 뜻을 가진다.
     *
     * <p><b>민심 부채(civil_debt)</b> — npc_death.yml populace_layer.civil_debt:
     * <i>"이 민심 델타는 region_state.recovery 의 자연 회복 대상에서 제외된다"</i>.
     * 무명을 죽인 값은 <b>세월이 지워 주지 않는다</b>. 그래서 부채만큼 회복의 천장이 내려간다:
     * 민심 부채가 3이면 민심은 47 까지만 돌아온다 — <i>"가장 약한 자를 죽인 대가는 복수가 아니라
     * 지역의 냉기다."</i>
     *
     * @param now   지금의 장부 (봇의 regions 표)
     * @param debts 회복에서 제외된 몫 (예: {@code {민심: 3}}) — 없으면 빈 맵
     * @return 적용할 델타 (없으면 빈 맵 — 부르는 쪽이 제 장부에 적는다)
     */
    public Map<String, Integer> recoveryDeltas(Map<String, Integer> now, Map<String, Integer> debts) {
        Map<String, Integer> out = new LinkedHashMap<>();
        now.forEach((stat, value) -> {
            int debt = Math.max(0, debts == null ? 0 : debts.getOrDefault(stat, 0));
            int ceiling = recoveryToward - debt;      // 부채만큼 낮아진 회복의 천장
            if (value < ceiling) {
                out.put(stat, Math.min(recoveryAmount, ceiling - value));
            } else if (value > recoveryToward) {
                out.put(stat, -Math.min(recoveryAmount, value - recoveryToward));   // 과잉도 되돌아온다
            }
        });
        return out;
    }
}
