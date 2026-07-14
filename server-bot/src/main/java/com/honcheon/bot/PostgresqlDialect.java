package com.honcheon.bot;

import org.postgresql.core.BaseConnection;
import org.postgresql.copy.CopyManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** PostgreSQL 연결, 멱등 SQL, 메타데이터 조회와 일관된 논리 스냅숏. */
final class PostgresqlDialect implements SqlDialect {
    private final String url;
    private final Properties properties;

    PostgresqlDialect(String url, String user, String password) {
        if (url == null || !url.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("HONCHEON_DATABASE_URL은 jdbc:postgresql: URL이어야 한다");
        }
        this.url = url;
        this.properties = new Properties();
        if (user != null && !user.isBlank()) {
            properties.setProperty("user", user);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
    }

    @Override
    public Connection open(Path ignored) throws Exception {
        Class.forName("org.postgresql.Driver");
        return DriverManager.getConnection(url, properties);
    }

    @Override
    public boolean pooled() {
        return true;   // PG-006 — 여기서부터 전역 직렬화가 없다
    }

    @Override
    public int connectionIsolation() {
        // 같은 뭉치를 두 손이 고치면 버전 충돌(40001)이 난다 — 그것이 순서의 판정이다.
        return Connection.TRANSACTION_SERIALIZABLE;
    }

    @Override
    public boolean isRetryableConflict(SQLException failure) {
        // 40001 = 직렬화 실패 · 40P01 = 교착 — 둘 다 "잠시 물러나 다시 재라"는 뜻이다
        String state = failure.getSQLState();
        return "40001".equals(state) || "40P01".equals(state);
    }

    @Override
    public void ensureRegion(Connection connection, String region) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO regions(id, security, economy, sentiment, updated_day) "
                        + "VALUES(?, 50, 50, 50, 1) ON CONFLICT(id) DO NOTHING")) {
            ps.setString(1, region);
            ps.executeUpdate();
        }
    }

    @Override
    public void writeSchemaVersion(Connection connection, int version) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO world_meta(key, value) VALUES('스키마_버전', ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, String.valueOf(version));
            ps.executeUpdate();
        }
    }

    @Override
    public void ensureWorldDay(Connection connection) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO world_meta(key, value) VALUES('현재일', '1') "
                        + "ON CONFLICT(key) DO NOTHING")) {
            ps.executeUpdate();
        }
    }

    @Override
    public boolean claimBridgeEvent(Connection connection, String eventId, String kind, int day)
            throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO bridge_inbox(event_id, kind, world_day) VALUES(?, ?, ?) "
                        + "ON CONFLICT(event_id) DO NOTHING")) {
            ps.setString(1, eventId);
            ps.setString(2, kind);
            ps.setInt(3, day);
            return ps.executeUpdate() > 0;
        }
    }

    @Override
    public boolean tableExists(Connection connection, String table) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Override
    public String snapshotFileName() {
        return "honcheon-postgresql.zip";
    }

    @Override
    public String restoreInstructions(Path snapshot) {
        return "이 PostgreSQL 논리 스냅숏을 보존하고 PG-005 import 도구로 복원한다: " + snapshot;
    }

    @Override
    public void snapshot(Connection connection, Path target) throws Exception {
        if (!connection.getAutoCommit()) {
            throw new SQLException("기존 트랜잭션 안에서는 PostgreSQL 스냅숏을 만들 수 없다");
        }
        int isolation = connection.getTransactionIsolation();
        boolean readOnly = connection.isReadOnly();
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        try {
            List<String> tables = tables(connection);
            CopyManager copy = new CopyManager(connection.unwrap(BaseConnection.class));
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
                for (String table : tables) {
                    zip.putNextEntry(new ZipEntry(table + ".csv"));
                    copy.copyOut("COPY " + quote(table) + " TO STDOUT WITH (FORMAT CSV, HEADER TRUE)", zip);
                    zip.closeEntry();
                }
            }
            connection.commit();
        } catch (Exception e) {
            rollback(connection, e);
            Files.deleteIfExists(target);
            throw e;
        } finally {
            connection.setAutoCommit(true);
            connection.setReadOnly(readOnly);
            connection.setTransactionIsolation(isolation);
        }
    }

    private static List<String> tables(Connection connection) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE' "
                        + "ORDER BY table_name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException failure) {
            original.addSuppressed(failure);
        }
    }
}
