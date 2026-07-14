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

    Map<String, Integer> eventTally(String actorType, String actorId) throws Exception;

    int countEvents(String actorType, String actorId, List<String> types) throws Exception;
}
