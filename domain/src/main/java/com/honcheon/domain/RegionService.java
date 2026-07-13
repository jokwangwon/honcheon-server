package com.honcheon.domain;

import com.honcheon.core.rules.RegionStateEngine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 지역 상태 <b>도메인 서비스</b> — 청하현의 치안·경제·민심을 흔드는 유일한 문.
 *
 * <p>규칙은 {@link RegionStateEngine} (region_state.yml), 장부는 {@link RegionLedger} (봇의 regions 표).
 * 이 서비스가 그 둘을 잇는다 — <b>그리고 눈금(0~100)은 이제 코드가 아니라 config 가 정한다.</b>
 *
 * <p><b>★ 남은 청구서 — 사건 등록부가 둘이다.</b>
 * {@link #applyEvent} 는 {@code region_state.yml event_deltas} 라는 <b>등록된 사건표</b>를 쓴다
 * (도적_두목_제거: 치안 +5 · 민심 +3 …). 그런데 프로덕션이 실제로 부르는 것은 {@link #nudge} 이고,
 * 그 값은 {@code world_bridge.yml} 의 {@code effects.&lt;event&gt;.region} 에 <b>인라인 숫자로</b>
 * 따로 적혀 있다 (bandit_slain: 치안 +1, 두목이면 ×2 = +2). <b>두 등록부의 수치가 서로 다르다.</b>
 *
 * <p>어느 쪽이 정본인지는 <b>코드가 정할 일이 아니다</b> (config 는 사람의 것이다).
 * 그래서 {@link #applyEvent} 는 길만 내 두고 부르지 않는다 — 등록부가 하나로 합쳐지는 날
 * 어댑터가 사건 <b>이름</b>을 넘기면 그날로 살아난다.
 */
public final class RegionService {

    private final RegionStateEngine rules;
    private final RegionLedger ledger;

    public RegionService(RegionStateEngine rules, RegionLedger ledger) {
        this.rules = rules;
        this.ledger = ledger;
    }

    /** 청하현의 오늘 */
    public Map<String, Integer> region() throws Exception {
        return ledger.region();
    }

    /**
     * 지역을 흔든다 — 이미 값이 정해진 델타로. 클램프는 규칙이 한다 (scale.min~max).
     *
     * <p>등록되지 않은 눈금과 헛걸음(델타 0)은 장부에 닿지 않는다.
     *
     * @return 흔든 뒤의 지역 상태 (치안·경제·민심 전부)
     */
    public Map<String, Integer> nudge(Map<String, Integer> deltas) throws Exception {
        Map<String, Integer> now = new LinkedHashMap<>(ledger.region());
        Map<String, Integer> changed = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : deltas.entrySet()) {
            Integer current = now.get(e.getKey());
            if (current == null || e.getValue() == 0) {
                continue;   // 세계에 없는 눈금이거나, 아무것도 안 한 것이다
            }
            int next = rules.clamp(current + e.getValue());
            now.put(e.getKey(), next);
            changed.put(e.getKey(), next);
        }
        if (!changed.isEmpty()) {
            ledger.writeRegion(changed);
        }
        return now;
    }

    /**
     * 등록된 사건 하나로 지역을 흔든다 — {@code region_state.yml event_deltas} 의 이름을 넘긴다.
     * (등록되지 않은 사건은 세계에 없다 — 규칙이 던진다.)
     *
     * <p>★ 위 클래스 주석의 청구서를 보라: 프로덕션은 아직 이 문으로 들어오지 않는다.
     * 다리(world_bridge.yml)가 제 인라인 숫자를 쓰기 때문이다.
     */
    public Map<String, Integer> applyEvent(String eventKey) throws Exception {
        return nudge(rules.deltas(eventKey));
    }

    /**
     * ★ 자연 회복 한 걸음 — 사건이 없으면 세계는 기준값(50)으로 되돌아간다.
     *
     * <p><b>민심 부채</b>는 회복의 천장을 낮춘다 (npc_death.yml populace_layer.civil_debt):
     * 무명을 죽인 값은 세월이 지워 주지 않는다.
     *
     * @param debts 회복에서 제외된 몫 (예: {@code {민심: 3}})
     * @return 회복 뒤의 지역 상태, 회복할 것이 없으면 <b>빈 맵</b> (장부를 건드리지 않았다는 뜻)
     */
    public Map<String, Integer> recover(Map<String, Integer> debts) throws Exception {
        Map<String, Integer> step = rules.recoveryDeltas(ledger.region(), debts);
        return step.isEmpty() ? Map.of() : nudge(step);
    }
}
