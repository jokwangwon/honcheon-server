package com.honcheon.bot;

import java.util.Map;

/** 세계 시계, 메타데이터, NPC 생사를 다루는 저장소 포트. */
interface WorldStore extends WorldMetaReader {
    /**
     * 첫 고을의 <b>id</b> — regions.id 의 값이다 (「청하현」은 표시명이고, 참조는 id 로 한다:
     * factions.yml id_policy 가 헌법이다).
     *
     * <p>★ 【B-103 · 2026-07-14】 여기 "청하현"(표시명)이 박혀 있었다 — SQLite 는 FK 를 안 지켜서
     * 고아 소문을 조용히 만들었을 자리인데, 컷오버 직후 PostgreSQL 이 첫 탄생 소문에서
     * FK 위반으로 소리내어 잡았다. 참조값이 갈라지면 소문망 전체가 죽는다.
     */
    String PRIMARY_REGION = "cheongha_hyeon";

    int worldDay() throws Exception;

    int advanceDay() throws Exception;

    void setMeta(String key, String value) throws Exception;

    Map<String, Map<String, Object>> deadNpcs() throws Exception;

    void killNpc(String npcKey, int tier, Map<String, Object> state) throws Exception;
}
