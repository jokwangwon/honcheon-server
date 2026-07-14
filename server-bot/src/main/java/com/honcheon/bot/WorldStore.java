package com.honcheon.bot;

import java.util.Map;

/** 세계 시계, 메타데이터, NPC 생사를 다루는 저장소 포트. */
interface WorldStore extends WorldMetaReader {
    String PRIMARY_REGION = "청하현";

    int worldDay() throws Exception;

    int advanceDay() throws Exception;

    void setMeta(String key, String value) throws Exception;

    Map<String, Map<String, Object>> deadNpcs() throws Exception;

    void killNpc(String npcKey, int tier, Map<String, Object> state) throws Exception;
}
