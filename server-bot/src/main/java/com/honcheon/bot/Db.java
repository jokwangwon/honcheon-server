package com.honcheon.bot;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;

/**
 * SQLite 영속화 — persistence.md: 단일 파일·단일 작성자·WAL.
 * 스키마는 db/schema.sql이 원천 (여기서 실행만 한다).
 */
public final class Db implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Connection conn;

    public Db(Path dbPath, Path schemaPath) throws Exception {
        Files.createDirectories(dbPath.toAbsolutePath().getParent());
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = conn.createStatement()) {
            for (String sql : Files.readString(schemaPath).split(";")) {
                String trimmed = sql.strip();
                // 주석·공백 조각 제거 (단순 분할 — 스키마에 문자열 내 세미콜론 없음)
                String body = trimmed.lines().filter(l -> !l.strip().startsWith("--"))
                        .reduce("", (a, b) -> a + "\n" + b).strip();
                if (!body.isEmpty()) {
                    st.execute(body);
                }
            }
        }
        ensureWorldDay();
    }

    private void ensureWorldDay() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT OR IGNORE INTO world_meta(key, value) VALUES('현재일', '1')");
        }
    }

    public int worldDay() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT value FROM world_meta WHERE key='현재일'")) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 1;
        }
    }

    /** 활성/서장 캐릭터 조회 — 계정당 1 (죽음 규칙 정합) */
    public Optional<Map<String, Object>> findCharacter(String discordId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, status, realm, location, sheet_json, wallet FROM characters "
                        + "WHERE discord_id = ? AND status != '사망' ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Map<String, Object> sheet = JSON.readValue(rs.getString("sheet_json"), Map.class);
                return Optional.of(Map.of(
                        "id", rs.getLong("id"), "name", rs.getString("name"),
                        "status", rs.getString("status"), "realm", rs.getString("realm"),
                        "location", String.valueOf(rs.getString("location")),
                        "sheet", sheet, "wallet", rs.getInt("wallet")));
            }
        }
    }

    public long createCharacter(String discordId, String name, Map<String, Object> sheet, int wallet)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO characters(discord_id, name, status, realm, location, sheet_json, wallet, created_day) "
                        + "VALUES(?, ?, '서장', '범인', '서장', ?, ?, ?) RETURNING id")) {
            ps.setString(1, discordId);
            ps.setString(2, name);
            ps.setString(3, JSON.writeValueAsString(sheet));
            ps.setInt(4, wallet);
            ps.setInt(5, worldDay());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** append-only 이벤트 로그 — 진실의 원장 (판정·생성·선택 전부) */
    public void logEvent(String type, String actorType, String actorId, Map<String, Object> data)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO events(day, type, actor_type, actor_id, data_json) VALUES(?, ?, ?, ?, ?)")) {
            ps.setInt(1, worldDay());
            ps.setString(2, type);
            ps.setString(3, actorType);
            ps.setString(4, actorId);
            ps.setString(5, JSON.writeValueAsString(data));
            ps.executeUpdate();
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
