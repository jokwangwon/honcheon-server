package com.honcheon.domain;

import java.util.Map;

/**
 * 지역 장부 <b>포트</b> — 청하현의 치안·경제·민심이 사는 곳 (봇의 {@code regions} 표).
 *
 * <p>★ <b>여기에 눈금을 넣지 말라.</b> 0~100 은 {@code region_state.yml} 의 {@code scale} 이고
 * 그것을 읽는 것은 {@code core.RegionStateEngine} 이다. 예전에는 {@code Db.nudgeRegion} 이
 * {@code Math.max(0, Math.min(100, …))} 라고 <b>숫자를 코드에 박아</b> 두었다 — config 를 고쳐도
 * 세계가 꿈쩍하지 않던 이유가 그것이다.
 */
public interface RegionLedger {

    /** 청하현의 오늘 — 치안·경제·민심 */
    Map<String, Integer> region() throws Exception;

    /**
     * 지역 장부에 적는다 — <b>바뀐 눈금만</b> 넘어온다 (이미 클램프된 값).
     * 헛걸음(델타 0)은 여기까지 오지 않는다 — 장부에 발자국을 남기지 않기 위해서다.
     */
    void writeRegion(Map<String, Integer> values) throws Exception;
}
