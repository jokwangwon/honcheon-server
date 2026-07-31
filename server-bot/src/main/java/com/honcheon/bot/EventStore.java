package com.honcheon.bot;

import java.util.List;
import java.util.Map;

/** append-only 사건 원장과 멱등 조회 포트. */
interface EventStore {
    void logEvent(String type, String actorType, String actorId, Map<String, Object> data)
            throws Exception;

    void logEvent(String type, String actorType, String actorId, String targetId,
                  Map<String, Object> data) throws Exception;

    void logEvent(String type, String actorType, String actorId, String targetType,
                  String targetId, Map<String, Object> data) throws Exception;

    boolean eventExists(String type, String actorId, String targetId) throws Exception;

    List<Map<String, Object>> eventsOf(String type, String actorId) throws Exception;

    /** 한 유형의 이벤트 전부 (id 순) — 세계 시계의 노선 집계(B-190 ④)가 분포를 접는 조회 지점 */
    List<Map<String, Object>> eventsByType(String type) throws Exception;

    Map<String, Integer> eventTally(String actorType, String actorId) throws Exception;

    int countEvents(String actorType, String actorId, List<String> types) throws Exception;
}
