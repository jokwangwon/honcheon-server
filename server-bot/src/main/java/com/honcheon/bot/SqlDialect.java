package com.honcheon.bot;

import java.nio.file.Path;
import java.sql.Connection;

/** 저장소 구현이 사용하는 DB별 연결·DDL 보조·원자 연산 경계. */
interface SqlDialect {
    Connection open(Path database) throws Exception;

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
