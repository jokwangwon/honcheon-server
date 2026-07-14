package com.honcheon.bot;

import java.nio.file.Path;
import java.sql.Connection;

/** 저장소 구현이 사용하는 DB별 연결·DDL 보조·원자 연산 경계. */
interface SqlDialect {
    Connection open(Path database) throws Exception;

    /** 이 방언이 동시 연결을 감당하는가 — SQLite 는 한 손(직렬), PostgreSQL 은 풀. (PG-006) */
    boolean pooled();

    /**
     * 연결에 걸 격리 수준 (음수 = 드라이버 기본). PostgreSQL 은 SERIALIZABLE —
     * 같은 뭉치를 두 손이 고치면 <b>버전 충돌</b>이 나고, 그것이 순서의 판정이다.
     */
    int connectionIsolation();

    /** 이 예외가 「충돌 — 잠시 물러나 다시 재면 된다」인가 (PG 40001 직렬화 실패 · 40P01 교착). */
    boolean isRetryableConflict(java.sql.SQLException failure);

    void ensureRegion(Connection connection, String region) throws Exception;

    void writeSchemaVersion(Connection connection, int version) throws Exception;

    void ensureWorldDay(Connection connection) throws Exception;

    boolean claimBridgeEvent(Connection connection, String eventId, String kind, int day)
            throws Exception;

    boolean tableExists(Connection connection, String table) throws Exception;

    String snapshotFileName();

    String restoreInstructions(Path snapshot);

    void snapshot(Connection connection, Path target) throws Exception;
}
