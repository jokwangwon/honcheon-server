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
import java.util.List;
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

    public synchronized int worldDay() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT value FROM world_meta WHERE key='현재일'")) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 1;
        }
    }

    /** 세계일 +1 — 자정 스케줄러·관리자 정산의 공용 지점. 새 날을 돌려준다 */
    public synchronized int advanceDay() throws SQLException {
        int next = worldDay() + 1;
        setMeta("현재일", String.valueOf(next));
        return next;
    }

    /** 활성/서장 캐릭터 조회 — 계정당 1 (죽음 규칙 정합) */
    public synchronized Optional<Map<String, Object>> findCharacter(String discordId) throws Exception {
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

    public synchronized long createCharacter(String discordId, String name, Map<String, Object> sheet, int wallet)
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

    public synchronized Optional<Map<String, Object>> findCharacterById(long id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, status, realm, location, sheet_json, wallet FROM characters WHERE id = ?")) {
            ps.setLong(1, id);
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

    /** 시트·전낭·경지·신분·위치 갱신 — 서장 진행·출도·사냥·비무·수련·승급의 영속화 지점 */
    public synchronized void updateCharacter(long id, Map<String, Object> sheet, int wallet,
                                String realm, String status, String location) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE characters SET sheet_json = ?, wallet = ?, realm = ?, status = ?, location = ? "
                        + "WHERE id = ?")) {
            ps.setString(1, JSON.writeValueAsString(sheet));
            ps.setInt(2, wallet);
            ps.setString(3, realm);
            ps.setString(4, status);
            ps.setString(5, location);
            ps.setLong(6, id);
            ps.executeUpdate();
        }
    }

    // ─── 장면 영속화 (scenes) — 봇 재시작 생존의 핵심 (알파 한계 1 해소) ───

    public synchronized void openScene(String channel, String thread, long characterId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO scenes(channel, thread, state, participants, opened_day) "
                        + "VALUES(?, ?, '진행', ?, ?)")) {
            ps.setString(1, channel);
            ps.setString(2, thread);
            ps.setString(3, JSON.writeValueAsString(List.of(characterId)));
            ps.setInt(4, worldDay());
            ps.executeUpdate();
        }
    }

    public synchronized void closeScene(String thread) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE scenes SET state = '종결', closed_day = ? WHERE thread = ? AND state = '진행'")) {
            ps.setInt(1, worldDay());
            ps.setString(2, thread);
            ps.executeUpdate();
        }
    }

    /** 진행 중 장면의 첫 참가자 캐릭터 ID — 서장은 1인 장면 */
    @SuppressWarnings("unchecked")
    public synchronized Optional<Long> sceneCharacter(String thread) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT participants FROM scenes WHERE thread = ? AND state = '진행' LIMIT 1")) {
            ps.setString(1, thread);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                List<Number> ids = JSON.readValue(rs.getString(1), List.class);
                return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0).longValue());
            }
        }
    }

    // ─── world_meta 범용 키 — 지역 채널 바인딩 등 ───

    public synchronized void setMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO world_meta(key, value) VALUES(?, ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public synchronized Optional<String> getMeta(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT value FROM world_meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /** append-only 이벤트 로그 — 진실의 원장 (판정·생성·선택 전부) */
    public synchronized void logEvent(String type, String actorType, String actorId, Map<String, Object> data)
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
