package com.honcheon.bot;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

/** SQLite 전용 JDBC 연결과 SQL 방언. 업무 저장소에는 이 문법을 노출하지 않는다. */
final class SqliteDialect implements SqlDialect {
    @Override
    public Connection open(Path database) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    @Override
    public void ensureRegion(Connection connection, String region) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO regions(id, security, economy, sentiment, updated_day) "
                        + "VALUES(?, 50, 50, 50, 1)")) {
            ps.setString(1, region);
            ps.executeUpdate();
        }
    }

    @Override
    public void writeSchemaVersion(Connection connection, int version) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO world_meta(key, value) VALUES('스키마_버전', ?)")) {
            ps.setString(1, String.valueOf(version));
            ps.executeUpdate();
        }
    }

    @Override
    public void ensureWorldDay(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO world_meta(key, value) VALUES('현재일', '1')")) {
            ps.executeUpdate();
        }
    }

    @Override
    public boolean claimBridgeEvent(Connection connection, String eventId, String kind, int day)
            throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO bridge_inbox(event_id, kind, world_day) VALUES(?, ?, ?)")) {
            ps.setString(1, eventId);
            ps.setString(2, kind);
            ps.setInt(3, day);
            return ps.executeUpdate() > 0;
        }
    }

    @Override
    public boolean tableExists(Connection connection, String table) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public String snapshotFileName() {
        return "honcheon.db";
    }

    @Override
    public String restoreInstructions(Path snapshot) {
        return "봇을 끄고 " + snapshot + "을 run/bot/honcheon.db로 덮은 뒤 "
                + "honcheon.db-wal과 honcheon.db-shm을 지우고 봇을 켠다";
    }

    @Override
    public void snapshot(Connection connection, Path target) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("VACUUM INTO ?")) {
            ps.setString(1, target.toAbsolutePath().toString());
            ps.execute();
        }
    }
}
