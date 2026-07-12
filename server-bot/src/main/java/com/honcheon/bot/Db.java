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
        ensureRegion();
        schemaVersionGate(schemaPath);
    }

    /** 청하현 지역 행 — 소문·NPC 의 외래키 대상 (region_state 기본값 50/50/50). 스키마 변경 아님 */
    public static final String REGION = "cheongha_hyeon";

    private void ensureRegion() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT OR IGNORE INTO regions(id, security, economy, sentiment, updated_day) "
                    + "VALUES('" + REGION + "', 50, 50, 50, 1)");
        }
    }

    /**
     * 스키마 버전 게이트 (db_migration.md 7절) — 신규 DB는 최신 번호로 스탬프(소급 불필요),
     * 구 DB가 최신 미만이면 경고만 (기동은 허용 — 적용은 사람이 백업 확인 후 tools/migrate_db.py).
     */
    private void schemaVersionGate(Path schemaPath) throws SQLException {
        Path migrationsDir = schemaPath.toAbsolutePath().getParent().resolve("migrations");
        int latest = 0;
        if (Files.isDirectory(migrationsDir)) {
            try (var files = Files.list(migrationsDir)) {
                latest = files.map(p -> p.getFileName().toString())
                        .filter(n -> n.length() > 3 && n.substring(0, 3).chars().allMatch(Character::isDigit))
                        .mapToInt(n -> Integer.parseInt(n.substring(0, 3))).max().orElse(0);
            } catch (java.io.IOException e) {
                return;   // 등록부를 못 읽으면 게이트 생략
            }
        }
        if (latest == 0) {
            return;
        }
        try (Statement st = conn.createStatement()) {
            var rs = st.executeQuery("SELECT value FROM world_meta WHERE key='스키마_버전'");
            Integer version = rs.next() ? Integer.parseInt(rs.getString(1)) : null;
            if (version == null) {
                var count = st.executeQuery("SELECT COUNT(*) FROM characters");
                count.next();
                if (count.getInt(1) == 0) {
                    // 신규 DB — 최신으로 스탬프 (스키마가 이미 최신이므로 소급 불필요)
                    st.execute("INSERT OR REPLACE INTO world_meta(key, value) VALUES('스키마_버전', '"
                            + latest + "')");
                    return;
                }
                version = 0;   // 구 DB(버전 표기 이전) — 소급 대상
            }
            if (version < latest) {
                System.err.println("경고: DB 스키마 버전 " + version + " < 최신 " + latest
                        + " — 봇을 멈추고 백업 후 `python3 tools/migrate_db.py <db경로>` 를 실행하라"
                        + " (docs/design/db_migration.md)");
            }
        }
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

    /** 강호에 나와 있는 모든 캐릭터 — 세계일 정산(빈사 마감·감쇠)의 순회 대상 */
    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> activeCharacters() throws Exception {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, discord_id, name, status, realm, location, sheet_json, wallet "
                             + "FROM characters WHERE status = '강호' ORDER BY id")) {
            while (rs.next()) {
                out.add(Map.of("id", rs.getLong("id"), "discord_id", rs.getString("discord_id"),
                        "name", rs.getString("name"), "status", rs.getString("status"),
                        "realm", rs.getString("realm"),
                        "location", String.valueOf(rs.getString("location")),
                        "sheet", JSON.readValue(rs.getString("sheet_json"), Map.class),
                        "wallet", rs.getInt("wallet")));
            }
        }
        return out;
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

    /**
     * append-only 이벤트 로그 — 진실의 원장 (판정·생성·선택 전부).
     * 규약 (F32·F39): 대상이 있는 이벤트는 반드시 target_type/target_id 를 채운다 —
     * npc(대화)·quest(의뢰)·character(비무)·fortune(기연)·simbeop(개화·운기)·place(탐방).
     * 새 이벤트 타입을 추가할 때 대상이 있으면 6-인자 오버로드를 쓰라.
     */
    public synchronized void logEvent(String type, String actorType, String actorId, Map<String, Object> data)
            throws Exception {
        logEvent(type, actorType, actorId, null, data);
    }

    /** F32 — 대상 있는 이벤트: target_type/target_id 를 채워 추적 가능하게 (8차 보완: type 동반) */
    public synchronized void logEvent(String type, String actorType, String actorId, String targetId,
                                      Map<String, Object> data) throws Exception {
        logEvent(type, actorType, actorId, null, targetId, data);
    }

    public synchronized void logEvent(String type, String actorType, String actorId, String targetType,
                                      String targetId, Map<String, Object> data) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO events(day, type, actor_type, actor_id, target_type, target_id, data_json) "
                        + "VALUES(?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, worldDay());
            ps.setString(2, type);
            ps.setString(3, actorType);
            ps.setString(4, actorId);
            ps.setString(5, targetType == null && targetId != null ? "npc" : targetType);
            ps.setString(6, targetId);
            ps.setString(7, JSON.writeValueAsString(data));
            ps.executeUpdate();
        }
    }

    // ─── 세력 반응 (faction_standing) — 주목 × 우호 2축 (faction_reaction.yml 통화) ───
    //
    // 감쇠는 배치 잡이 아니라 **읽는 순간 정산**한다 (decayedAttention/decayedFavor).
    // 같은 세계일에 몇 번을 읽어도 같은 값 — 결정론. 잡 중복 실행으로 두 번 깎이는 사고가 없다.
    // 쓰는 쪽(addAttention/addFavor)은 먼저 정산해 앉히고, 그 위에 더한 뒤 오늘로 스탬프한다.

    /** 세력 × 캐릭터 관계 한 칸 — attention/favor 는 오늘 기준 정산값 (저장값이 아니다) */
    record Standing(String faction, int attention, int favor, int peakStage, int peakFavor) {
    }

    /** 저장된 원시 행 (정산 전) — 내부용 */
    private record RawStanding(int attention, int favor, int attentionDay, int favorDay,
                               int peakStage, int peakFavor) {
    }

    private RawStanding rawStanding(String factionId, long characterId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT attention, favor, attention_day, favor_day, peak_stage, peak_favor "
                        + "FROM faction_standing WHERE faction_id = ? AND character_id = ?")) {
            ps.setString(1, factionId);
            ps.setLong(2, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new RawStanding(0, 0, 0, 0, 0, 0);
                }
                return new RawStanding(rs.getInt(1), rs.getInt(2), rs.getInt(3),
                        rs.getInt(4), rs.getInt(5), rs.getInt(6));
            }
        }
    }

    /** 오늘 기준 세력 관계 — 감쇠 정산 후의 값 (세계는 잊는다, 단 이력의 하한은 남는다) */
    synchronized Standing standing(String factionId, long characterId, int today, Factions f)
            throws SQLException {
        RawStanding raw = rawStanding(factionId, characterId);
        int favor = f.decayedFavor(raw.favor(), raw.peakFavor(), raw.favorDay(), today);
        int attention = f.decayedAttention(raw.attention(), raw.peakStage(), favor,
                raw.attentionDay(), today);
        return new Standing(factionId, attention, favor, raw.peakStage(), raw.peakFavor());
    }

    /** 등록된 모든 세력 관계 (0인 것은 행이 없다 — 무관심은 기록되지 않는다) */
    synchronized List<Standing> standings(long characterId, int today, Factions f) throws SQLException {
        List<String> factions = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT faction_id FROM faction_standing WHERE character_id = ? ORDER BY faction_id")) {
            ps.setLong(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    factions.add(rs.getString(1));
                }
            }
        }
        List<Standing> out = new java.util.ArrayList<>();
        for (String faction : factions) {
            out.add(standing(faction, characterId, today, f));
        }
        return out;
    }

    /** 주목 가산 — 정산 후 더하고 [0, cap] 클램프. peak_stage(감쇠 하한의 근거)를 갱신한다 */
    synchronized int addAttention(String factionId, long characterId, int delta, int today, Factions f)
            throws SQLException {
        Standing now = standing(factionId, characterId, today, f);
        int next = Math.max(0, Math.min(f.scoreMax(), now.attention() + delta));
        int peakStage = Math.max(now.peakStage(), f.stageOf(next).stage());
        upsert(factionId, characterId, next, now.favor(), today, today, peakStage,
                Math.max(now.peakFavor(), now.favor()));
        return next;
    }

    /** 우호 가산 — 정산 후 더하고 [0, cap] 클램프. peak_favor(공신 이력)를 갱신한다 */
    synchronized int addFavor(String factionId, long characterId, int delta, int cap, int today,
                              Factions f) throws SQLException {
        Standing now = standing(factionId, characterId, today, f);
        int next = Math.max(0, Math.min(Math.min(cap, f.favorMax()), now.favor() + delta));
        upsert(factionId, characterId, now.attention(), next, today, today,
                Math.max(now.peakStage(), f.stageOf(now.attention()).stage()),
                Math.max(now.peakFavor(), next));
        return next;
    }

    /** 우호 조회 (오늘 기준 정산값) — 직행 루트·게시판 게이트의 입력 */
    synchronized int favor(String factionId, long characterId, int today, Factions f)
            throws SQLException {
        return standing(factionId, characterId, today, f).favor();
    }

    private void upsert(String factionId, long characterId, int attention, int favor,
                        int attentionDay, int favorDay, int peakStage, int peakFavor)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO faction_standing(faction_id, character_id, attention, favor, "
                        + "attention_day, favor_day, peak_stage, peak_favor) VALUES(?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(faction_id, character_id) DO UPDATE SET "
                        + "attention = excluded.attention, favor = excluded.favor, "
                        + "attention_day = excluded.attention_day, favor_day = excluded.favor_day, "
                        + "peak_stage = excluded.peak_stage, peak_favor = excluded.peak_favor")) {
            ps.setString(1, factionId);
            ps.setLong(2, characterId);
            ps.setInt(3, attention);
            ps.setInt(4, favor);
            ps.setInt(5, attentionDay);
            ps.setInt(6, favorDay);
            ps.setInt(7, peakStage);
            ps.setInt(8, peakFavor);
            ps.executeUpdate();
        }
    }

    // ─── NPC 생사 (npcs.status) — 죽은 자와는 말할 수 없다 ───

    /** NPC 사망 기록 — state_json 에 사망일·사인·목격·시신 (연쇄의 전체 입력) */
    public synchronized void killNpc(String npcKey, int tier, Map<String, Object> state) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO npcs(id, region, tier, status, state_json, updated_day) "
                        + "VALUES(?, '" + REGION + "', ?, '사망', ?, ?) "
                        + "ON CONFLICT(id) DO UPDATE SET status = '사망', state_json = excluded.state_json, "
                        + "updated_day = excluded.updated_day")) {
            ps.setString(1, npcKey);
            ps.setInt(2, tier);
            ps.setString(3, JSON.writeValueAsString(state));
            ps.setInt(4, worldDay());
            ps.executeUpdate();
        }
    }

    /** 사망 NPC 등록부 — 키 → state_json (사망일·사인·목격·시신). 서비스 공백·게시판 주입의 입력 */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Map<String, Object>> deadNpcs() throws Exception {
        Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, state_json FROM npcs WHERE status = '사망' ORDER BY id")) {
            while (rs.next()) {
                out.put(rs.getString(1), JSON.readValue(rs.getString(2), Map.class));
            }
        }
        return out;
    }

    // ─── 소문망 (rumors) — 한 소문 = 망별 '도달' 행들의 묶음 (단계 4 B) ───
    //
    // rumors 의 한 행은 이제 소문 하나가 아니라 **소문 하나가 특정 망에 도달한 사건**이다:
    //   network  = 도달한 망      born_day = 그 망에 닿는 날 (speed_days 반영)
    //   accuracy = 그 망에서의 정확도 (발원 정확도 − 그 망의 distortion)
    // 같은 소문(content_json.군 이 같다)이 망마다 다른 날, 다른 정확도로 존재한다 — 전언 게임.
    //
    // 감쇠(propagation.decay: 3일마다 -1, 0이면 소멸)는 저장값을 깎지 않고 **읽을 때 계산**한다:
    //   유효강도 = strength − (오늘 − born_day) / every_days
    // → 배치 잡 없음. 같은 날 = 같은 소문판 (결정론). 잡 중복으로 두 번 깎일 여지가 없다.

    /** 도달 한 건 — 어느 망에, 며칠에, 얼마나 정확하게 (RumorEngine 이 계산한 스케줄의 한 칸) */
    record Arrival(String network, int day, int accuracy) {
    }

    /**
     * 소문 전파 — 도달 스케줄 하나하나를 한 행으로 심는다.
     * 강도 0 이면 아무것도 심지 않는다 (소문 없음 = 은밀형의 보상). 반환: 심어진 도달 수.
     */
    synchronized int spreadRumor(String group, String truth, String subject, Long subjectId,
                                 List<String> tags, int strength, List<Arrival> arrivals,
                                 String region) throws Exception {
        if (strength <= 0 || arrivals.isEmpty()) {
            return 0;
        }
        Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("군", group);          // 같은 소문의 망별 도달을 묶는 키 (세력 중복 가산 금지의 기준)
        content.put("내용", truth);        // 사실 내용 — 왜곡은 읽는 쪽(Rumors.tell)이 정확도로 만든다
        content.put("주체", subject == null ? "" : subject);
        if (subjectId != null) {
            content.put("주체_id", subjectId);   // 플레이어가 소문의 주체일 때 — 세력 반응의 대상
        }
        content.put("태그", tags);
        String json = JSON.writeValueAsString(content);
        int planted = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO rumors(content_json, strength, accuracy, network, region, born_day, state) "
                        + "VALUES(?, ?, ?, ?, ?, ?, '전파중')")) {
            for (Arrival a : arrivals) {
                ps.setString(1, json);
                ps.setInt(2, strength);
                ps.setInt(3, Math.max(0, a.accuracy()));
                ps.setString(4, a.network());
                ps.setString(5, region);
                ps.setInt(6, a.day());
                ps.addBatch();
                planted++;
            }
            ps.executeBatch();
        }
        return planted;
    }

    /**
     * 오늘 살아 있는 도달들 — 이미 닿았고(born_day <= 오늘), 아직 안 죽은(유효강도 > 0) 것만.
     * network 가 null 이면 전 망. 강한 것·최근 것부터.
     */
    @SuppressWarnings("unchecked")
    synchronized List<Rumors.Heard> heard(int day, String network, int decayEveryDays)
            throws Exception {
        int every = Math.max(1, decayEveryDays);
        String sql = "SELECT id, content_json, strength, accuracy, network, born_day, "
                + "(strength - (? - born_day) / ?) AS live FROM rumors "
                + "WHERE state = '전파중' AND born_day <= ? "
                + (network == null ? "" : "AND network = ? ")
                + "AND (strength - (? - born_day) / ?) > 0 "
                + "ORDER BY live DESC, born_day DESC, id DESC LIMIT 50";
        List<Rumors.Heard> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            ps.setInt(i++, day);
            ps.setInt(i++, every);
            ps.setInt(i++, day);
            if (network != null) {
                ps.setString(i++, network);
            }
            ps.setInt(i++, day);
            ps.setInt(i, every);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> content = JSON.readValue(rs.getString(2), Map.class);
                    List<String> tags = content.get("태그") instanceof List<?> l
                            ? l.stream().map(String::valueOf).toList() : List.of();
                    out.add(new Rumors.Heard(rs.getLong(1), String.valueOf(content.get("군")),
                            String.valueOf(content.get("내용")), String.valueOf(content.get("주체")),
                            rs.getString(5), rs.getInt(7), rs.getInt(4), rs.getInt(6), tags));
                }
            }
        }
        return out;
    }

    /** 오늘 막 도달한 것들 (born_day == 오늘) — 아침 방송의 "몇 건이 새로 닿았는가" */
    synchronized int arrivalCountOn(int day) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM rumors WHERE state = '전파중' AND born_day = ?")) {
            ps.setInt(1, day);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * 이미 도달한 것들 (born_day <= 오늘, lookback 창 안) — 세력의 '조직적 인지'가 발화하는 지점.
     *
     * '오늘 도달한 것'만 보지 않는 이유: 소문은 세계일 정산 사이에도 심어진다 (심사 대성공이
     * 정파망에 곧장 꽂히는 경우 — born_day = 오늘). 그것을 다음 정산이 놓치면 영영 안 세어진다.
     * 중복은 이벤트 원장이 막는다 (세력_인지 = 세력 × 소문군 1회) — 그래서 몇 번을 돌려도 멱등이다.
     */
    @SuppressWarnings("unchecked")
    synchronized List<Map<String, Object>> arrivalsThrough(int day, int lookbackDays)
            throws Exception {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT content_json, accuracy, network FROM rumors "
                        + "WHERE state = '전파중' AND born_day <= ? AND born_day > ? ORDER BY id")) {
            ps.setInt(1, day);
            ps.setInt(2, day - Math.max(1, lookbackDays));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> content = JSON.readValue(rs.getString(1), Map.class);
                    out.add(Map.of("내용", content, "정확도", rs.getInt(2),
                            "망", String.valueOf(rs.getString(3))));
                }
            }
        }
        return out;
    }

    /** 특정 소문군이 이미 심어져 있는가 — 세계 개막 소문의 1회성 보장 */
    synchronized boolean rumorGroupExists(String group) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM rumors WHERE content_json LIKE ? LIMIT 1")) {
            ps.setString(1, "%\"군\":\"" + group + "\"%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ─── 플레이어의 죽음 (단계 4 A) — 비가역. 계정당 한 삶 ───

    /** 사망 확정 — status '사망' + died_day. 이 행은 findCharacter 에서 영영 사라진다 */
    public synchronized void killCharacter(long id, int day) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE characters SET status = '사망', died_day = ? WHERE id = ?")) {
            ps.setInt(1, day);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** 이 계정의 마지막 죽은 캐릭터 — 새 삶의 '혈연 시작'이 무엇을 물려받는지의 원천 */
    @SuppressWarnings("unchecked")
    public synchronized Optional<Map<String, Object>> lastDeadCharacter(String discordId)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, realm, sheet_json, died_day FROM characters "
                        + "WHERE discord_id = ? AND status = '사망' ORDER BY died_day DESC, id DESC LIMIT 1")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(Map.of("id", rs.getLong(1), "name", rs.getString(2),
                        "realm", rs.getString(3),
                        "sheet", JSON.readValue(rs.getString(4), Map.class),
                        "died_day", rs.getInt(5)));
            }
        }
    }

    /** 혈연 시작 — 새 캐릭터를 전생에 잇는다 (characters.lineage_of) */
    public synchronized void setLineage(long id, long ancestorId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE characters SET lineage_of = ? WHERE id = ?")) {
            ps.setLong(1, ancestorId);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    // ─── 전장 예치 (character_bank) — 죽음이 건드리는 유일한 재산 (현장의 것은 흩어진다) ───

    public synchronized int bankBalance(long characterId, String branch) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT balance FROM character_bank WHERE character_id = ? AND branch = ?")) {
            ps.setLong(1, characterId);
            ps.setString(2, branch);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 예치·인출 (음수 = 인출). 새 잔액을 돌려준다 */
    public synchronized int bankMove(long characterId, String branch, int delta) throws SQLException {
        int next = Math.max(0, bankBalance(characterId, branch) + delta);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO character_bank(character_id, branch, balance) VALUES(?, ?, ?) "
                        + "ON CONFLICT(character_id, branch) DO UPDATE SET balance = excluded.balance")) {
            ps.setLong(1, characterId);
            ps.setString(2, branch);
            ps.setInt(3, next);
            ps.executeUpdate();
        }
        return next;
    }

    /** 생전 지정 상속인 (legacy.예치.order 1순위) — 선언은 이벤트로도 남는다 */
    public synchronized void setHeir(long characterId, String branch, String heir) throws SQLException {
        bankMove(characterId, branch, 0);   // 행 보장
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE character_bank SET heir_hint = ? WHERE character_id = ? AND branch = ?")) {
            ps.setString(1, heir);
            ps.setLong(2, characterId);
            ps.setString(3, branch);
            ps.executeUpdate();
        }
    }

    public synchronized Optional<String> heirHint(long characterId, String branch) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT heir_hint FROM character_bank WHERE character_id = ? AND branch = ?")) {
            ps.setLong(1, characterId);
            ps.setString(2, branch);
            try (ResultSet rs = ps.executeQuery()) {
                String hint = rs.next() ? rs.getString(1) : null;
                return Optional.ofNullable(hint);
            }
        }
    }

    /** 이벤트 존재 여부 — 중복 가산 금지(세력당 소문 1회)·1회성 사건의 게이트 */
    public synchronized boolean eventExists(String type, String actorId, String targetId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM events WHERE type = ? AND actor_id = ? AND target_id = ? LIMIT 1")) {
            ps.setString(1, type);
            ps.setString(2, actorId);
            ps.setString(3, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 한 행위자의 특정 유형 이벤트 — 피의 장부 상속(전생의 원한 → 이 아이의 짐)의 조회 지점 */
    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> eventsOf(String type, String actorId)
            throws Exception {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT day, target_type, target_id, data_json FROM events "
                        + "WHERE type = ? AND actor_id = ? ORDER BY id")) {
            ps.setString(1, type);
            ps.setString(2, actorId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(Map.of("day", rs.getInt(1),
                            "target_type", String.valueOf(rs.getString(2)),
                            "target_id", String.valueOf(rs.getString(3)),
                            "data", JSON.readValue(rs.getString(4), Map.class)));
                }
            }
        }
        return out;
    }

    /** 한 캐릭터의 이벤트 유형별 건수 — 명성 동결(세계 연표)의 재료 */
    public synchronized Map<String, Integer> eventTally(String actorType, String actorId)
            throws SQLException {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT type, COUNT(*) FROM events WHERE actor_type = ? AND actor_id = ? "
                        + "GROUP BY type ORDER BY COUNT(*) DESC")) {
            ps.setString(1, actorType);
            ps.setString(2, actorId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getInt(2));
                }
            }
        }
        return out;
    }

    /** 특정 행위자의 이벤트 유형 합계 — 기연 트리거(선행 기억 등)의 조회 지점 */
    public synchronized int countEvents(String actorType, String actorId, List<String> types)
            throws SQLException {
        String in = String.join(",", types.stream().map(t -> "?").toList());
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM events WHERE actor_type = ? AND actor_id = ? AND type IN (" + in + ")")) {
            ps.setString(1, actorType);
            ps.setString(2, actorId);
            for (int i = 0; i < types.size(); i++) {
                ps.setString(3 + i, types.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
