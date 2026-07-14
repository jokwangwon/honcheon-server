package com.honcheon.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.honcheon.domain.FactionLedger;
import com.honcheon.domain.RegionLedger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
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
 *
 * <p>★ <b>장부이지 규칙이 아니다.</b> 세력({@link FactionLedger})·지역({@link RegionLedger}) 두 축은
 * 도메인이 정의한 <b>포트</b>를 구현한다 — 이 클래스는 행을 읽고 쓸 뿐, 클램프도 감쇠도 하지 않는다.
 * 그 산수는 {@code domain.FactionService}/{@code domain.RegionService} 가
 * {@code core} 룰 엔진에게 시킨다. <b>정본은 하나다.</b>
 */
public final class Db implements AutoCloseable, FactionLedger, RegionLedger,
        ResetStore, WorldMetaReader, BridgeStore, GameStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ConnectionSource source;   // 연결의 샘 — SQLite 한 손 · PostgreSQL 풀 (PG-006)
    private final Connection conn;           // 샘 위의 가면(RoutingConnection) — 87곳의 conn. 이 그대로 산다
    private final SqlDialect dialect;

    public static Db open(Map<String, String> environment) throws Exception {
        String backend = environment.getOrDefault("HONCHEON_DB_BACKEND", "sqlite").strip().toLowerCase();
        if ("postgresql".equals(backend) || "postgres".equals(backend)) {
            String url = environment.get("HONCHEON_DATABASE_URL");
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException(
                        "PostgreSQL에는 HONCHEON_DATABASE_URL 환경 변수가 필요하다");
            }
            String user = environment.getOrDefault("HONCHEON_DATABASE_USER", "honcheon");
            String password = environment.getOrDefault("HONCHEON_DATABASE_PASSWORD", "");
            Path schema = Path.of(environment.getOrDefault(
                    "HONCHEON_SCHEMA", "db/postgresql/schema.sql"));
            int poolSize = Integer.parseInt(environment.getOrDefault("HONCHEON_DB_POOL", "4"));
            return new Db(Path.of("."), schema, new PostgresqlDialect(url, user, password), poolSize);
        }
        if (!"sqlite".equals(backend)) {
            throw new IllegalArgumentException("모르는 HONCHEON_DB_BACKEND: " + backend);
        }
        Path database = Path.of(environment.getOrDefault(
                "HONCHEON_DB", "run/bot/honcheon.db"));
        Path schema = Path.of(environment.getOrDefault("HONCHEON_SCHEMA", "db/schema.sql"));
        return new Db(database, schema);
    }

    public Db(Path dbPath, Path schemaPath) throws Exception {
        this(dbPath, schemaPath, new SqliteDialect());
    }

    Db(Path dbPath, Path schemaPath, SqlDialect dialect) throws Exception {
        this(dbPath, schemaPath, dialect, 4);
    }

    Db(Path dbPath, Path schemaPath, SqlDialect dialect, int poolSize) throws Exception {
        Files.createDirectories(dbPath.toAbsolutePath().getParent());
        this.dialect = dialect;
        // PG-006 — 직렬화가 메서드의 synchronized 에서 연결의 샘으로 내려왔다:
        // SQLite 는 한 손(오늘의 직렬화 그대로), PostgreSQL 은 풀(전역 직렬화 없음).
        this.source = dialect.pooled()
                ? new PooledConnectionSource(() -> dialect.open(dbPath), poolSize,
                        dialect.connectionIsolation())
                : new SingleConnectionSource(dialect.open(dbPath));
        this.conn = RoutingConnection.wrap(source);
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
    public static final String REGION = WorldStore.PRIMARY_REGION;   // 한 몸 — 다시 갈라지면 컴파일이 막는다... 는 못 하지만 적어도 원천이 하나다 (B-103)

    private void ensureRegion() throws Exception {
        dialect.ensureRegion(conn, REGION);
    }

    /**
     * 스키마 버전 게이트 (db_migration.md 7절) — 신규 DB는 최신 번호로 스탬프(소급 불필요),
     * 구 DB가 최신 미만이면 경고만 (기동은 허용 — 적용은 사람이 백업 확인 후 tools/migrate_db.py).
     */
    private void schemaVersionGate(Path schemaPath) throws Exception {
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
                    dialect.writeSchemaVersion(conn, latest);
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

    private void ensureWorldDay() throws Exception {
        dialect.ensureWorldDay(conn);
    }

    public int worldDay() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT value FROM world_meta WHERE key='현재일'")) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 1;
        }
    }

    /** 세계일 +1 — 자정 스케줄러·관리자 정산의 공용 지점. 새 날을 돌려준다 */
    public int advanceDay() throws SQLException {
        // 읽고(오늘) 계산하고(내일) 쓴다 — 두 손이 겹치면 하루가 증발한다. 그래서 원자다.
        return atomicallySql(() -> {
            int next = worldDay() + 1;
            setMeta("현재일", String.valueOf(next));
            return next;
        });
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

    /** 강호에 나와 있는 모든 캐릭터 — 세계일 정산(빈사 마감·감쇠)의 순회 대상 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> activeCharacters() throws Exception {
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

    public Optional<Map<String, Object>> findCharacterById(long id) throws Exception {
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
    public void updateCharacter(long id, Map<String, Object> sheet, int wallet,
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

    public void openScene(String channel, String thread, long characterId) throws Exception {
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

    public void closeScene(String thread) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE scenes SET state = '종결', closed_day = ? WHERE thread = ? AND state = '진행'")) {
            ps.setInt(1, worldDay());
            ps.setString(2, thread);
            ps.executeUpdate();
        }
    }

    /** 진행 중 장면의 첫 참가자 캐릭터 ID — 서장은 1인 장면 */
    @SuppressWarnings("unchecked")
    public Optional<Long> sceneCharacter(String thread) throws Exception {
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

    public void setMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO world_meta(key, value) VALUES(?, ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public Optional<String> getMeta(String key) throws SQLException {
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
    public void logEvent(String type, String actorType, String actorId, Map<String, Object> data)
            throws Exception {
        logEvent(type, actorType, actorId, null, data);
    }

    /** F32 — 대상 있는 이벤트: target_type/target_id 를 채워 추적 가능하게 (8차 보완: type 동반) */
    public void logEvent(String type, String actorType, String actorId, String targetId,
                                      Map<String, Object> data) throws Exception {
        logEvent(type, actorType, actorId, null, targetId, data);
    }

    public void logEvent(String type, String actorType, String actorId, String targetType,
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

    // ─── 세력 반응 (faction_standing) — 주목 × 우호 2축 · **장부 포트 구현** ───
    //
    // ★ 여기에 규칙이 있었다. 이제 없다.
    //   전에는 이 자리에서 Db 가 클램프하고(min(cap, favorMax)), 감쇠를 정산하고(Factions.decayedX),
    //   단계를 판정했다 — 그리고 core.FactionReactionEngine 이 **같은 산수를 제 메모리 맵에 대고
    //   다시** 하고 있었다 (테스트만 그것을 불렀다). 정본이 둘이었다.
    //
    //   지금 이 클래스가 아는 것은 **행을 읽고 쓴다**는 것뿐이다 (FactionLedger 포트).
    //   산수는 domain.FactionService → core.FactionReactionEngine 하나뿐이다.

    @Override
    public FactionLedger.Row standingRow(String factionId, long characterId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT attention, favor, attention_day, favor_day, peak_stage, peak_favor "
                        + "FROM faction_standing WHERE faction_id = ? AND character_id = ?")) {
            ps.setString(1, factionId);
            ps.setLong(2, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return FactionLedger.Row.NONE;
                }
                return new FactionLedger.Row(rs.getInt(1), rs.getInt(2), rs.getInt(3),
                        rs.getInt(4), rs.getInt(5), rs.getInt(6));
            }
        }
    }

    /** 이 캐릭터를 아는 세력들 (0 인 것은 행이 없다 — 무관심은 기록되지 않는다) */
    @Override
    public List<String> standingFactions(long characterId) throws SQLException {
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
        return factions;
    }

    @Override
    public void writeStanding(String factionId, long characterId, FactionLedger.Row row)
            throws SQLException {
        upsert(factionId, characterId, row.attention(), row.favor(), row.attentionDay(),
                row.favorDay(), row.peakStage(), row.peakFavor());
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

    // ─── 정치층 (myeongbun · authority_mandate) — 세력 대 세력 (단계 5) ───
    //
    // faction_standing 이 '세력 대 개인'이라면 이 둘은 '세력 대 세력'이다. 두 층은 곱해진다.
    // 감쇠는 여기서도 **읽는 순간 정산**한다 (배치 잡 금지 — 같은 세계일이면 같은 상태).
    //
    // raw_gauge 는 '장부에 적힌 사실'이고, 세계가 그것을 얼마나 믿는지는 **소문의 정확도**가 정한다.
    // 그래서 연합(참여 세력)은 저장하지 않는다 — 그것은 상태가 아니라 계산의 결과다 (Politics.form).

    /**
     * 사안 한 칸 — 무엇 때문에, **누가 했고 누가 당했는가** (raw_gauge = 배수 적용 전 사건 점수).
     * victims 가 명분의 두 번째 축이다: 이것이 있어야 당사자가 먼저 붙고(-8), 동맹이 따라 붙고(-6),
     * **원수는 빠진다**(+5). 없으면 남는 것은 태그뿐이고, 태그만 남으면 무림은 언제나 뭉친다.
     */
    @SuppressWarnings("unchecked")
    private MyeongbunIssue readIssue(ResultSet rs) throws Exception {
        List<String> tags = JSON.readValue(rs.getString("tags_json"), List.class);
        List<String> victims = JSON.readValue(rs.getString("victims_json"), List.class);
        return new MyeongbunIssue(rs.getString("issue"), rs.getString("target"), victims, tags,
                rs.getInt("raw_gauge"), rs.getInt("origin_accuracy"),
                rs.getString("origin_rumor"), rs.getString("true_target"),
                rs.getInt("created_day"), rs.getInt("updated_day"));
    }

    /** 지금 세계에 걸린 모든 사안 (저장값 — 감쇠 정산은 Politics.decayed 가 읽는 순간 한다) */
    public List<MyeongbunIssue> issues() throws Exception {
        List<MyeongbunIssue> out = new java.util.ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM myeongbun ORDER BY created_day, issue")) {
            while (rs.next()) {
                out.add(readIssue(rs));
            }
        }
        return out;
    }

    public Optional<MyeongbunIssue> issue(String key) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM myeongbun WHERE issue = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readIssue(rs)) : Optional.empty();
            }
        }
    }

    /**
     * 명분 가산 — 정산 후 더한다 (사건이 쌓고 시간이 깎는다).
     * 사안이 없으면 만든다: 그때의 target·tags·발원 소문(정확도)이 이 명분의 정체가 된다.
     */
    public MyeongbunIssue addMyeongbun(String key, String target, List<String> victims,
                                    List<String> tags, int delta, int accuracy, String rumorGroup,
                                    String trueTarget, int day, int max, Politics politics)
            throws Exception {
        return atomically(() -> addMyeongbunInTx(key, target, victims, tags, delta, accuracy,
                rumorGroup, trueTarget, day, max, politics));
    }

    private MyeongbunIssue addMyeongbunInTx(String key, String target, List<String> victims,
                                    List<String> tags, int delta, int accuracy, String rumorGroup,
                                    String trueTarget, int day, int max, Politics politics)
            throws Exception {
        Optional<MyeongbunIssue> found = issue(key);
        int base = found.map(i -> politics.decayed(i.rawGauge(), i.tags(), i.updatedDay(), day))
                .orElse(0);
        int next = Math.max(0, Math.min(max, base + delta));
        // 태그도 **누적된다** — 한 사안에 두 태그가 겹치면 감응 세력이 늘어난다.
        // ★ 이것이 혈채 루트의 발화 지점이다: 무고(-3)만으로는 안 뭉친 무림이,
        //   같은 사안에 금기(-4)가 붙는 순간 뭉친다 (faction_politics blood_debt_ignition.①_금기).
        java.util.LinkedHashSet<String> mergedTags = new java.util.LinkedHashSet<>(
                found.map(MyeongbunIssue::tags).orElse(List.of()));
        if (tags != null) {
            mergedTags.addAll(tags);
        }
        // 피해 세력은 **누적된다** — 관이 두 번째 문파를 치면 피해자가 둘이 된다 (그래서 연합이 커진다)
        java.util.LinkedHashSet<String> mergedVictims = new java.util.LinkedHashSet<>(
                found.map(MyeongbunIssue::victims).orElse(List.of()));
        if (victims != null) {
            mergedVictims.addAll(victims);
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO myeongbun(issue, target, victims_json, tags_json, raw_gauge, "
                        + "origin_accuracy, origin_rumor, true_target, created_day, updated_day) "
                        + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(issue) DO UPDATE SET raw_gauge = excluded.raw_gauge, "
                        + "updated_day = excluded.updated_day, target = excluded.target, "
                        + "victims_json = excluded.victims_json, "
                        // ★ 태그도 갱신한다 — 이 한 줄이 없어서 지금까지 **태그가 겹치지 않았다**:
                        //   같은 사안에 금기가 붙어도 tags 는 무고인 채였고, 그래서 소림·무당이 오지 않았다
                        + "tags_json = excluded.tags_json, "
                        + "origin_accuracy = excluded.origin_accuracy, "
                        + "origin_rumor = COALESCE(excluded.origin_rumor, myeongbun.origin_rumor), "
                        + "true_target = COALESCE(excluded.true_target, myeongbun.true_target)")) {
            ps.setString(1, key);
            ps.setString(2, target);
            ps.setString(3, JSON.writeValueAsString(List.copyOf(mergedVictims)));
            ps.setString(4, JSON.writeValueAsString(List.copyOf(mergedTags)));
            ps.setInt(5, next);
            ps.setInt(6, accuracy);
            ps.setString(7, rumorGroup);
            ps.setString(8, trueTarget);
            ps.setInt(9, found.map(MyeongbunIssue::createdDay).orElse(day));
            ps.setInt(10, day);
            ps.executeUpdate();
        }
        return issue(key).orElseThrow();
    }

    // ─── 문파 상태 (sect_state) — ★ 문파에게도 사정이 있다 (004) ───
    //
    // 제 코가 석 자면 남의 싸움에 못 낀다. 이 표가 연합의 브레이크다.
    // 기준선은 config (roster.<세력>.internal_burden). 여기 든 것은 **사건이 얹은 것**뿐이다.
    // '다른 전쟁 중(+4)' 은 저장하지 않는다 — 오늘의 연합에서 읽으면 되는 값이므로 (파생 상태 금지).

    /** 사건이 얹은 부담 (감쇠 정산 후) — 30일마다 -1. 사정은 느리게 풀린다 */
    int sectBurden(String faction, int today, int decayEveryDays) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT burden, updated_day FROM sect_state WHERE faction = ?")) {
            ps.setString(1, faction);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0;
                }
                int burden = rs.getInt(1);
                int ticks = decayEveryDays <= 0 ? 0
                        : Math.max(0, (today - rs.getInt(2)) / decayEveryDays);
                return Math.max(0, burden - ticks);
            }
        }
    }

    /** 사정을 얹거나(장문 사망 +3) 푼다(후계가 섰다 -3) — 0~6 clamp */
    public int addSectBurden(String faction, int delta, String source, int today,
                                   int decayEveryDays, int max) throws Exception {
        return atomically(() -> addSectBurdenInTx(faction, delta, source, today, decayEveryDays, max));
    }

    private int addSectBurdenInTx(String faction, int delta, String source, int today,
                                  int decayEveryDays, int max) throws Exception {
        int now = sectBurden(faction, today, decayEveryDays);
        int next = Math.max(0, Math.min(max, now + delta));
        java.util.LinkedHashSet<String> sources = new java.util.LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT sources_json FROM sect_state WHERE faction = ?")) {
            ps.setString(1, faction);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    @SuppressWarnings("unchecked")
                    List<String> prior = JSON.readValue(rs.getString(1), List.class);
                    sources.addAll(prior);
                }
            }
        }
        if (source != null && delta > 0) {
            sources.add(source);
        }
        if (next == 0) {
            sources.clear();   // 사정이 풀렸다
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sect_state(faction, burden, sources_json, updated_day) "
                        + "VALUES(?, ?, ?, ?) ON CONFLICT(faction) DO UPDATE SET "
                        + "burden = excluded.burden, sources_json = excluded.sources_json, "
                        + "updated_day = excluded.updated_day")) {
            ps.setString(1, faction);
            ps.setInt(2, next);
            ps.setString(3, JSON.writeValueAsString(List.copyOf(sources)));
            ps.setInt(4, today);
            ps.executeUpdate();
        }
        return next;
    }

    /** 지금 사정이 있는 세력들 (진단·관측용) */
    public Map<String, Integer> sectBurdens(int today, int decayEveryDays) throws SQLException {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT faction, burden, updated_day FROM sect_state")) {
            while (rs.next()) {
                int ticks = decayEveryDays <= 0 ? 0
                        : Math.max(0, (today - rs.getInt(3)) / decayEveryDays);
                int burden = Math.max(0, rs.getInt(2) - ticks);
                if (burden > 0) {
                    out.put(rs.getString(1), burden);
                }
            }
        }
        return out;
    }

    /**
     * 진범 규명 — 명분은 **소멸하지 않고 이전된다** (myeongbun.drains.진범_규명 = transfer).
     * 이간(오해 밴드)을 푸는 유일한 문: 엉뚱한 세력에게 붙었던 명분이 진짜 가해자에게 옮겨 간다.
     */
    public void transferMyeongbun(String key, String newTarget, int accuracy, int day)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE myeongbun SET target = ?, true_target = NULL, origin_accuracy = ?, "
                        + "updated_day = ? WHERE issue = ?")) {
            ps.setString(1, newTarget);
            ps.setInt(2, accuracy);
            ps.setInt(3, day);
            ps.setString(4, key);
            ps.executeUpdate();
        }
    }

    /** 법명분 한 칸 (관 측 게이지 — 대상은 개인이다) */
    record Mandate(int gauge, int peak, int updatedDay) {
    }

    private Mandate rawMandate(long characterId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT gauge, peak, updated_day FROM authority_mandate WHERE character_id = ?")) {
            ps.setLong(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Mandate(rs.getInt(1), rs.getInt(2), rs.getInt(3))
                        : new Mandate(0, 0, 0);
            }
        }
    }

    /** 오늘 기준 법명분 — 정산값 (7일마다 -1. 단 15 이력이면 8에서 멈춘다) */
    public int mandate(long characterId, int today, Politics politics) throws SQLException {
        Mandate raw = rawMandate(characterId);
        return politics.decayedMandate(raw.gauge(), raw.peak(), raw.updatedDay(), today);
    }

    /** 법명분 가산 — 포두 +8 · 현령 +14 / 자수 -10 · 배상 -6. peak 는 감쇠 하한의 근거로 남는다 */
    public int addMandate(long characterId, int delta, int today, Politics politics)
            throws SQLException {
        return atomicallySql(() -> addMandateInTx(characterId, delta, today, politics));
    }

    private int addMandateInTx(long characterId, int delta, int today, Politics politics)
            throws SQLException {
        int now = mandate(characterId, today, politics);
        int next = Math.max(0, Math.min(politics.mandateMax(), now + delta));
        int peak = Math.max(rawMandate(characterId).peak(), next);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO authority_mandate(character_id, gauge, peak, updated_day) "
                        + "VALUES(?, ?, ?, ?) ON CONFLICT(character_id) DO UPDATE SET "
                        + "gauge = excluded.gauge, peak = excluded.peak, "
                        + "updated_day = excluded.updated_day")) {
            ps.setLong(1, characterId);
            ps.setInt(2, next);
            ps.setInt(3, peak);
            ps.setInt(4, today);
            ps.executeUpdate();
        }
        return next;
    }

    /**
     * 그 소문이 이 망에 얼마나 정확하게 닿았는가 — **세력이 자기 조직 채널로 읽는 정확도**.
     * -1 = 아직 닿지 않았다 (formation.channel_gate: 소식이 없으면 평가 자체가 없다).
     * ★ 같은 사건이 망마다 다른 정확도로 도착한다 = 세력마다 다른 크기의 명분이 된다.
     */
    public int rumorAccuracyIn(String group, String network, int day) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT accuracy FROM rumors WHERE content_json LIKE ? AND network = ? "
                        + "AND born_day <= ? ORDER BY accuracy DESC LIMIT 1")) {
            ps.setString(1, "%\"군\":\"" + group + "\"%");
            ps.setString(2, network);
            ps.setInt(3, day);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    // ★ 절연의 집행(breakFavor)은 domain.FactionService 로 갔다 — 그것은 SQL 이 아니라 규칙이다.

    // ─── NPC 생사 (npcs.status) — 죽은 자와는 말할 수 없다 ───

    /** NPC 사망 기록 — state_json 에 사망일·사인·목격·시신 (연쇄의 전체 입력) */
    public void killNpc(String npcKey, int tier, Map<String, Object> state) throws Exception {
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
    public Map<String, Map<String, Object>> deadNpcs() throws Exception {
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

    /**
     * 소문 전파 — 도달 스케줄 하나하나를 한 행으로 심는다.
     * 강도 0 이면 아무것도 심지 않는다 (소문 없음 = 은밀형의 보상). 반환: 심어진 도달 수.
     */
    public int spreadRumor(String group, String truth, String subject, Long subjectId,
                                 List<String> tags, int strength, List<RumorArrival> arrivals,
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
            for (RumorArrival a : arrivals) {
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
    public List<Rumors.Heard> heard(int day, String network, int decayEveryDays)
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
    public int arrivalCountOn(int day) throws SQLException {
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
    public List<Map<String, Object>> arrivalsThrough(int day, int lookbackDays)
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
    public boolean rumorGroupExists(String group) throws SQLException {
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
    public void killCharacter(long id, int day) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE characters SET status = '사망', died_day = ? WHERE id = ?")) {
            ps.setInt(1, day);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    /** 이 계정의 마지막 죽은 캐릭터 — 새 삶의 '혈연 시작'이 무엇을 물려받는지의 원천 */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> lastDeadCharacter(String discordId)
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
    public void setLineage(long id, long ancestorId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE characters SET lineage_of = ? WHERE id = ?")) {
            ps.setLong(1, ancestorId);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    // ─── 전장 예치 (character_bank) — 죽음이 건드리는 유일한 재산 (현장의 것은 흩어진다) ───

    public int bankBalance(long characterId, String branch) throws SQLException {
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
    public int bankMove(long characterId, String branch, int delta) throws SQLException {
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
    public void setHeir(long characterId, String branch, String heir) throws SQLException {
        bankMove(characterId, branch, 0);   // 행 보장
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE character_bank SET heir_hint = ? WHERE character_id = ? AND branch = ?")) {
            ps.setString(1, heir);
            ps.setLong(2, characterId);
            ps.setString(3, branch);
            ps.executeUpdate();
        }
    }

    public Optional<String> heirHint(long characterId, String branch) throws SQLException {
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
    public boolean eventExists(String type, String actorId, String targetId)
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
    public List<Map<String, Object>> eventsOf(String type, String actorId)
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
    public Map<String, Integer> eventTally(String actorType, String actorId)
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
    public int countEvents(String actorType, String actorId, List<String> types)
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

    // ═══ 세계 다리 (마이그레이션 005) — 마크(MVT)의 사건이 이 장부로 들어오는 문 ═══
    //
    // 다리는 세계 상태를 새로 만들지 않는다. 무명이 죽으면 rumors 가 자라고 regions.민심이 깎인다 —
    // 이미 있는 표들이다. 여기 새로 드는 것은 부속 둘뿐이다:
    //   mvt_link     신원 — 마크의 몸(uuid)과 봇의 장부(character_id)를 잇는다.
    //                이 표가 비면 소문에 **주체가 없다** → 세력이 아무도 주목하지 않는다 (배경음).
    //   bridge_inbox 멱등 — 같은 줄을 두 번 읽어도 사람은 한 번만 죽는다.

    /**
     * 브리지 사건 한 건을 원자적으로 적용한다.
     * inbox 선점, 세계 상태 변경, JSONL 커서 기록은 반드시 함께 성공하거나 함께 되돌아간다.
     */
    public boolean applyBridgeEvent(String eventId, String kind, String cursorKey,
                                                 String checkpoint, BridgeStore.Work work) throws Exception {
        return inTransaction(() -> {
            boolean first = claimBridgeEvent(eventId, kind);
            if (first) {
                work.run();
            }
            setMeta(cursorKey, checkpoint);
            return first;
        });
    }

    /**
     * 이 사건을 지금 처음 보는가 — 처음이면 못을 박고 true. 이미 박혀 있으면 false (건너뛴다).
     * 커서를 못 쓰고 죽어도, 파일을 통째로 재생해도 세계는 같은 자리에 선다.
     */
    public boolean claimBridgeEvent(String eventId, String kind) throws Exception {
        return dialect.claimBridgeEvent(conn, eventId, kind, worldDay());
    }

    /** 마크 플레이어 ↔ 캐릭터 접합 (character_id 가 null 이면 '아직 안 이어진 몸'으로만 등록된다) */
    public void linkMvt(String mcUuid, String mcName, Long characterId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO mvt_link(mc_uuid, mc_name, character_id, linked_day) VALUES(?, ?, ?, ?) "
                        + "ON CONFLICT(mc_uuid) DO UPDATE SET mc_name = excluded.mc_name, "
                        + "character_id = COALESCE(excluded.character_id, mvt_link.character_id)")) {
            ps.setString(1, mcUuid);
            ps.setString(2, mcName);
            if (characterId == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setLong(3, characterId);
            }
            ps.setInt(4, worldDay());
            ps.executeUpdate();
        }
    }

    // ═══ 신원 접합 (마이그레이션 007) — **디스코드가 청하고, 그 몸이 게임에서 수락한다** ═══
    //
    // ★ 옛 길(006: 마크가 코드 발급 → 디스코드가 확정)은 폐기됐다. 사용자의 판정이었다 —
    //   "코드 복사는 없어져도 되고, 닉네임 입력하여 대조."
    //
    // ★★ 그러나 **닉네임은 열쇠가 아니다.** 아무나 남의 닉을 댈 수 있고, 그래도 된다:
    //   청은 mvt_link_request 에 '대기'로 앉을 뿐이고, 물음은 **그 몸의 게임 화면**으로 간다.
    //   이어지는 유일한 손은 **그 몸에 로그인한 사람의 [잇는다] 클릭**이다 (mc_uuid 대조).
    //   그러므로 훔치려면 그 사람의 **디스코드 계정**이거나 **마크 계정**이어야 한다 — 코드 시절과 같다.
    //   (config/world_bridge.yml identity.direction: discord_asks_body_accepts)

    /**
     * 청을 대기열에 앉힌다 — <b>여기서는 아무것도 이어지지 않는다.</b>
     *
     * <p>같은 몸에게 살아 있던 옛 청과, <b>같은 캐릭터가 낸 옛 청</b>은 함께 폐기된다
     * (one_pending_per_body — 한 화면에 물음이 쌓이지 않게. 그리고 한 사람이 여러 몸에 동시에 청하지 못하게).
     */
    public void pendLinkRequest(String token, String mcUuid, String mcName,
                                             long characterId, String discordId, String discordName,
                                             long issuedAt, long expiresAt) throws SQLException {
        atomicallySql(() -> {
            pendLinkRequestInTx(token, mcUuid, mcName, characterId, discordId, discordName,
                    issuedAt, expiresAt);
            return null;
        });
    }

    private void pendLinkRequestInTx(String token, String mcUuid, String mcName,
                                     long characterId, String discordId, String discordName,
                                     long issuedAt, long expiresAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE mvt_link_request SET state = '폐기' "
                        + "WHERE state = '대기' AND (mc_uuid = ? OR character_id = ?)")) {
            ps.setString(1, mcUuid);
            ps.setLong(2, characterId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO mvt_link_request(token, mc_uuid, mc_name, character_id, discord_id, "
                        + "discord_name, issued_at, expires_at, state) "
                        + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, '대기') ON CONFLICT(token) DO NOTHING")) {
            ps.setString(1, token);
            ps.setString(2, mcUuid);
            ps.setString(3, mcName);
            ps.setLong(4, characterId);
            ps.setString(5, discordId);
            ps.setString(6, discordName);
            ps.setLong(7, issuedAt);
            ps.setLong(8, expiresAt);
            ps.executeUpdate();
        }
    }

    public Optional<LinkRequest> linkRequest(String token) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT token, mc_uuid, mc_name, character_id, discord_id, discord_name, "
                        + "issued_at, expires_at, state FROM mvt_link_request WHERE token = ?")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readRequest(rs)) : Optional.empty();
            }
        }
    }

    /**
     * 지금 살아 있는 청 전부 — <b>마크로 내려보낼 목록</b> (link_requests.json).
     * 만료된 것은 먼저 죽이고(상태 '만료') 남은 것만 돌려준다 — <b>죽은 청은 화면에 뜨지 않는다.</b>
     */
    public List<LinkRequest> livingLinkRequests(long now) throws SQLException {
        return atomicallySql(() -> livingLinkRequestsInTx(now));
    }

    private List<LinkRequest> livingLinkRequestsInTx(long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE mvt_link_request SET state = '만료' WHERE state = '대기' AND expires_at < ?")) {
            ps.setLong(1, now);
            ps.executeUpdate();
        }
        List<LinkRequest> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT token, mc_uuid, mc_name, character_id, discord_id, discord_name, "
                        + "issued_at, expires_at, state FROM mvt_link_request "
                        + "WHERE state = '대기' ORDER BY issued_at")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readRequest(rs));
                }
            }
        }
        return out;
    }

    /** 이 캐릭터가 마지막으로 낸 청 (연타 판정 — 상태 불문. 거절당하고 또 조르는 것도 연타다) */
    public Optional<LinkRequest> lastLinkRequestOf(long characterId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT token, mc_uuid, mc_name, character_id, discord_id, discord_name, "
                        + "issued_at, expires_at, state FROM mvt_link_request "
                        + "WHERE character_id = ? ORDER BY issued_at DESC LIMIT 1")) {
            ps.setLong(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readRequest(rs)) : Optional.empty();
            }
        }
    }

    /** 이 몸이 마지막으로 받은 청 (한 화면에 물음을 도배할 수 없다 — 받는 쪽의 연타 방지) */
    public Optional<LinkRequest> lastLinkRequestTo(String mcUuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT token, mc_uuid, mc_name, character_id, discord_id, discord_name, "
                        + "issued_at, expires_at, state FROM mvt_link_request "
                        + "WHERE mc_uuid = ? ORDER BY issued_at DESC LIMIT 1")) {
            ps.setString(1, mcUuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readRequest(rs)) : Optional.empty();
            }
        }
    }

    /**
     * 청을 태운다 (single_use) — <b>한 번 답하면 그 청은 죽는다.</b>
     *
     * <p>★ {@code WHERE state = '대기'} 가 이 표의 자물쇠다: 같은 토큰이 두 번 와도(다리의 재생·연타 클릭)
     * 두 번째는 <b>0행</b>이 바뀐다. 그래서 부르는 쪽은 반환값으로 <b>이번이 처음인가</b>를 안다.
     */
    public boolean burnLinkRequest(String token, String state, long now, int day)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE mvt_link_request SET state = ?, decided_at = ?, decided_day = ? "
                        + "WHERE token = ? AND state = '대기'")) {
            ps.setString(1, state);
            ps.setLong(2, now);
            ps.setInt(3, day);
            ps.setString(4, token);
            return ps.executeUpdate() > 0;
        }
    }

    private static LinkRequest readRequest(ResultSet rs) throws SQLException {
        return new LinkRequest(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                rs.getString(5), rs.getString(6), rs.getLong(7), rs.getLong(8), rs.getString(9));
    }

    /** 이 캐릭터에게 이미 몸이 있는가 (one_body_one_character — 양쪽 다 1:1) */
    public Optional<String> mcOfCharacter(long characterId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT mc_uuid FROM mvt_link WHERE character_id = ?")) {
            ps.setLong(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /**
     * 그 몸이 마크에서 쓰는 <b>이름</b> — 사람에게 uuid 를 보여 주지 않기 위한 것.
     *
     * <p>안내판이 "몸은 이어졌다 (`Lindydone`)" 라고 말할 때 쓴다. 이름이 없으면 {@code empty}
     * (그러면 부르는 쪽이 uuid 로 떨어진다 — <b>이름을 지어내지 않는다</b>).
     */
    public Optional<String> mcName(String mcUuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT mc_name FROM mvt_link WHERE mc_uuid = ?")) {
            ps.setString(1, mcUuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /** 이 몸에 붙어 있는 캐릭터 (죽은 캐릭터도 본다 — 재접합 판정은 산 자만 막는다) */
    public Optional<Long> rawCharacterOfMc(String mcUuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT character_id FROM mvt_link WHERE mc_uuid = ? AND character_id IS NOT NULL")) {
            ps.setString(1, mcUuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    /** 접합 해제 — 몸은 남고 이름만 떨어진다 (혈채는 캐릭터 원장에 그대로 남는다. 빚은 안 없어진다) */
    public void unlinkMc(String mcUuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE mvt_link SET character_id = NULL, linked_day = ? WHERE mc_uuid = ?")) {
            ps.setInt(1, worldDay());
            ps.setString(2, mcUuid);
            ps.executeUpdate();
        }
    }

    /** 이 몸은 누구인가 — 살아 있는 캐릭터만 (죽은 자의 이름으로는 소문이 붙지 않는다) */
    public Optional<Long> characterOfMc(String mcUuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT l.character_id FROM mvt_link l JOIN characters c ON c.id = l.character_id "
                        + "WHERE l.mc_uuid = ? AND c.status != '사망'")) {
            ps.setString(1, mcUuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    /** 이어진 몸들 — 되먹임 스냅숏(수배·우호)의 순회 대상 (mc_uuid → character_id) */
    // ═══════════ 가문(家門) — 「한 채의 집」 (008_가문.sql. ★ 아직 안 돌렸을 수 있다) ═══════════
    //
    // ★★ 옛 `houseKin(family)` 는 **죽었다.** 그것은 **집안 유형**으로 남매를 묶었다 —
    //   그래서 **농가의 아이 둘이 남매였다** (서로 다른 농가인데). 담당자가 만든 병이었다.
    //   이제 남매는 **같은 house_id** 로만 잡는다. 유형이 아니라 **한 채의 집**이다.
    //
    // ★ 표가 아직 없을 수 있다 (마이그레이션은 **사람이** 돌린다 — 봇은 구 DB 를 만나도 안 죽는다).
    //   그래서 두 함수 다 **표가 없으면 조용히 빈손**으로 돌아온다. 세계는 계속 돈다.

    /** 이 표가 세계에 있는가 (마이그레이션 008 을 돌렸는가) */
    private boolean hasHouses() {
        try {
            return dialect.tableExists(conn, "houses");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * ★★ <b>이 집안의 집들 — 아이가 몇이나 태어났는지와 함께.</b>
     *
     * <p><b>★ 아이 수는 「태어난 수」다 (산 자가 아니다).</b> 형이 죽어도 <b>그는 형이었다</b> —
     * 죽음이 자리를 비우면 '죽은 형이 있는 집' 이 성립하지 않는다 (자리가 다시 채워지니까).
     * 그래서 상한은 <b>태어난 수</b>로 센다 (형제 목록은 산 자만 보이지만).
     */
    public List<HouseEntry> housesOf(String family) throws Exception {
        List<HouseEntry> out = new java.util.ArrayList<>();
        if (!hasHouses()) {
            return out;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT h.id, h.family, h.name, h.region, h.state, "
                        + "(SELECT COUNT(*) FROM characters c WHERE c.house_id = h.id) AS born "
                        + "FROM houses h WHERE h.family = ? ORDER BY h.id")) {
            ps.setString(1, family);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new HouseEntry(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getInt(6)));
                }
            }
        }
        return out;
    }

    /** 새 집이 선다 — 세계에 없던 가문 하나가 생긴다 */
    public long createHouse(String family, String name, String region,
                                         String state, int day) throws Exception {
        if (!hasHouses()) {
            return 0L;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO houses(family, name, region, state, created_day) VALUES(?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, family);
            ps.setString(2, name);
            ps.setString(3, region);
            ps.setString(4, state);
            ps.setInt(5, day);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** 아이를 그 집에 앉힌다 */
    public void setHouse(long characterId, long houseId) throws Exception {
        if (!hasHouses()) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE characters SET house_id = ? WHERE id = ?")) {
            ps.setLong(1, houseId);
            ps.setLong(2, characterId);
            ps.executeUpdate();
        }
    }

    public Optional<HouseEntry> house(long houseId) throws Exception {
        if (!hasHouses()) {
            return Optional.empty();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT h.id, h.family, h.name, h.region, h.state, "
                        + "(SELECT COUNT(*) FROM characters c WHERE c.house_id = h.id) "
                        + "FROM houses h WHERE h.id = ?")) {
            ps.setLong(1, houseId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new HouseEntry(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6)))
                        : Optional.empty();
            }
        }
    }

    /** 이 사람이 태어난 집 — null = 아직 집이 없다 (마이그레이션 전에 태어났거나 배정 규칙이 없다) */
    public Long houseOfCharacter(long characterId) throws Exception {
        if (!hasHouses()) {
            return null;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT house_id FROM characters WHERE id = ?")) {
            ps.setLong(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    return rs.wasNull() ? null : v;
                }
            }
        }
        return null;
    }

    /**
     * ★★ <b>그 집에 태어난 아이들 — 태어난 순서대로.</b>
     *
     * <p><b>같은 집</b>이다 (같은 유형이 아니라). 사용자의 말 그대로:
     * <i>"같은 <b>세가에</b> 같이 태어나게 되었다면 순서대로 형, 누나가 되어야 함."</i>
     *
     * <p><b>서열은 표로 두지 않는다</b> — {@code characters.id} 순이 곧 태어난 순서다.
     * 파생값은 <b>낡을 수가 없다</b> (별도 표를 두면 그 표가 진실과 갈라진다).
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> houseMembers(long houseId) throws Exception {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (!hasHouses()) {
            return out;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, name, sheet_json FROM characters "
                        + "WHERE house_id = ? AND status != '사망' ORDER BY id")) {
            ps.setLong(1, houseId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> sheet = JSON.readValue(rs.getString(3), Map.class);
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", rs.getLong(1));
                    row.put("name", rs.getString(2));
                    row.put("성별", sheet.get("성별"));
                    out.add(row);
                }
            }
        }
        return out;
    }

    public Map<String, Long> linkedBodies() throws SQLException {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT l.mc_uuid, l.character_id FROM mvt_link l "
                             + "JOIN characters c ON c.id = l.character_id "
                             + "WHERE c.status != '사망' ORDER BY l.mc_uuid")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getLong(2));
            }
        }
        return out;
    }

    /**
     * 지역 장부에 적는다 — <b>바뀐 눈금만</b> (이미 클램프된 값). {@link RegionLedger} 포트 구현.
     *
     * <p>★ 여기에 {@code Math.max(0, Math.min(100, …))} 가 있었다 — <b>코드가 눈금을 지어냈다.</b>
     * region_state.yml 의 {@code scale} 을 아무도 안 읽었으므로 config 로 상한을 고쳐도 세계는
     * 꿈쩍하지 않았다. 이제 클램프는 {@code core.RegionStateEngine.clamp} 하나다 (등록제).
     */
    @Override
    public void writeRegion(Map<String, Integer> values) throws SQLException {
        Map<String, String> column = Map.of("치안", "security", "경제", "economy", "민심", "sentiment");
        for (Map.Entry<String, Integer> e : values.entrySet()) {
            String col = column.get(e.getKey());
            if (col == null) {
                continue;   // 세계에 없는 눈금은 장부에 없다
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE regions SET " + col + " = ?, updated_day = ? WHERE id = ?")) {
                ps.setInt(1, e.getValue());
                ps.setInt(2, worldDay());
                ps.setString(3, REGION);
                ps.executeUpdate();
            }
        }
    }

    /** 청하현의 오늘 — 치안·경제·민심 ({@link RegionLedger} 포트 구현) */
    @Override
    public Map<String, Integer> region() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT security, economy, sentiment FROM regions WHERE id = ?")) {
            ps.setString(1, REGION);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, Integer> out = new java.util.LinkedHashMap<>();
                if (rs.next()) {
                    out.put("치안", rs.getInt(1));
                    out.put("경제", rs.getInt(2));
                    out.put("민심", rs.getInt(3));
                } else {
                    out.put("치안", 50);
                    out.put("경제", 50);
                    out.put("민심", 50);
                }
                return out;
            }
        }
    }

    /**
     * 지금 살아 있는 소문의 태그들 — 되먹임의 심장.
     * 도적 소문이 아직 안 죽었으면 마크의 나무꾼은 오늘도 산길을 피한다 (Populace.rumor).
     * 감쇠는 여기서도 읽는 순간 정산한다 (heard 와 같은 공식 — 같은 날이면 같은 소문판).
     */
    @SuppressWarnings("unchecked")
    public java.util.Set<String> liveRumorTags(int day, int decayEveryDays) throws Exception {
        int every = Math.max(1, decayEveryDays);
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT content_json FROM rumors WHERE state = '전파중' AND born_day <= ? "
                        + "AND (strength - (? - born_day) / ?) > 0")) {
            ps.setInt(1, day);
            ps.setInt(2, day);
            ps.setInt(3, every);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> content = JSON.readValue(rs.getString(1), Map.class);
                    if (content.get("태그") instanceof List<?> tags) {
                        tags.forEach(t -> out.add(String.valueOf(t)));
                    }
                }
            }
        }
        return out;
    }

    // ═══ 혈채 (마이그레이션 006) — ★ 이 세계에서 감쇠하지 않는 유일한 값 ═══
    //
    // subject: character:<id> (이어진 자) · mc:<uuid> (아직 안 이어진 몸) · 미상의_살인마 (몸도 모를 때).
    // ★ 접합의 순간 mc:<uuid> 가 character:<id> 로 병합된다 —
    //   그 전까지 세계는 열 개의 사고를 보았고, 그 후로 세계는 한 마리의 짐승을 본다.


    public BloodDebtEntry bloodDebt(String subject) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT subject, character_id, hidden, known_raw, known_day, public_count, kills, "
                        + "exposure_floor FROM blood_debt WHERE subject = ?")) {
            ps.setString(1, subject);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return BloodDebtEntry.empty(subject);
                }
                Long chId = rs.getObject(2) == null ? null : rs.getLong(2);
                return new BloodDebtEntry(rs.getString(1), chId, rs.getDouble(3), rs.getDouble(4),
                        rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getDouble(8));
            }
        }
    }

    /** 캐릭터의 장부 (subject = character:<id>) */
    public BloodDebtEntry bloodDebtOf(long characterId) throws SQLException {
        return bloodDebt("character:" + characterId);
    }

    /**
     * 한 건을 적는다. <b>암혈채는 노출과 무관하게 자란다</b> (몸이 장부다) —
     * 현혈채만 노출·정확도 배수를 먹는다. 공개(witness 2) 건은 감쇠 하한(×2)의 근거로 따로 센다.
     */
    public BloodDebtEntry addBloodDebt(String subject, Long characterId, double hidden,
                                                    double known, boolean publicKill, int day)
            throws SQLException {
        return atomicallySql(() -> addBloodDebtInTx(subject, characterId, hidden, known, publicKill, day));
    }

    private BloodDebtEntry addBloodDebtInTx(String subject, Long characterId, double hidden,
                                            double known, boolean publicKill, int day)
            throws SQLException {
        BloodDebtEntry now = bloodDebt(subject);
        double nextHidden = now.hidden() + hidden;
        double nextKnown = now.knownRaw() + known;
        int publicCount = now.publicCount() + (publicKill ? 1 : 0);
        int kills = now.kills() + (hidden > 0 ? 1 : 0);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO blood_debt(subject, character_id, hidden, known_raw, known_day, "
                        + "public_count, kills, exposure_floor, updated_day) "
                        + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(subject) DO UPDATE SET "
                        + "character_id = COALESCE(excluded.character_id, blood_debt.character_id), "
                        + "hidden = excluded.hidden, known_raw = excluded.known_raw, "
                        + "known_day = excluded.known_day, public_count = excluded.public_count, "
                        + "kills = excluded.kills, updated_day = excluded.updated_day")) {
            ps.setString(1, subject);
            if (characterId == null) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setLong(2, characterId);
            }
            ps.setDouble(3, nextHidden);
            ps.setDouble(4, nextKnown);
            ps.setInt(5, day);
            ps.setInt(6, publicCount);
            ps.setInt(7, kills);
            ps.setDouble(8, now.exposureFloor());
            ps.setInt(9, day);
            ps.executeUpdate();
        }
        return bloodDebt(subject);
    }

    /**
     * ★ 병합 — 접합의 순간, 이름 없이 쌓인 장부가 한 사람의 이름으로 합산된다.
     * 그 전까지 세계는 열 개의 사고를 보았다. 그 후로 세계는 한 마리의 짐승을 본다.
     */
    public BloodDebtEntry mergeBloodDebt(String from, long characterId, int day) throws SQLException {
        return atomicallySql(() -> mergeBloodDebtInTx(from, characterId, day));
    }

    private BloodDebtEntry mergeBloodDebtInTx(String from, long characterId, int day) throws SQLException {
        BloodDebtEntry src = bloodDebt(from);
        if (src.hidden() <= 0 && src.knownRaw() <= 0 && src.exposureFloor() <= 0) {
            return bloodDebtOf(characterId);
        }
        BloodDebtEntry before = bloodDebtOf(characterId);   // 합치기 전의 그의 장부 (건수 이중 계상 금지)
        addBloodDebt("character:" + characterId, characterId, src.hidden(), src.knownRaw(),
                false, day);
        // 공개 건수·살인 건수·노출 하한은 **원장 그대로** 옮긴다 (addBloodDebt 은 '한 건'만 셀 줄 안다)
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE blood_debt SET public_count = ?, kills = ?, exposure_floor = ? "
                        + "WHERE subject = ?")) {
            ps.setInt(1, before.publicCount() + src.publicCount());
            ps.setInt(2, before.kills() + src.kills());
            ps.setDouble(3, Math.max(before.exposureFloor(), src.exposureFloor()));
            ps.setString(4, "character:" + characterId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM blood_debt WHERE subject = ?")) {
            ps.setString(1, from);
            ps.executeUpdate();
        }
        return bloodDebtOf(characterId);
    }

    /** ★ B6 — 마공 운기를 목격당했다. 이 몸에 '은밀'은 이제 없다 (노출 배수 하한 1.0) */
    public void setExposureFloor(String subject, Long characterId, double floor, int day)
            throws SQLException {
        BloodDebtEntry now = bloodDebt(subject);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO blood_debt(subject, character_id, exposure_floor, updated_day) "
                        + "VALUES(?, ?, ?, ?) ON CONFLICT(subject) DO UPDATE SET "
                        + "exposure_floor = MAX(blood_debt.exposure_floor, excluded.exposure_floor), "
                        + "updated_day = excluded.updated_day")) {
            ps.setString(1, subject);
            if (characterId == null) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setLong(2, characterId);
            }
            ps.setDouble(3, Math.max(floor, now.exposureFloor()));
            ps.setInt(4, day);
            ps.executeUpdate();
        }
    }

    /** 모든 장부 (검산·되먹임 순회용) */
    public List<BloodDebtEntry> bloodDebts() throws SQLException {
        List<BloodDebtEntry> out = new java.util.ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT subject FROM blood_debt ORDER BY subject")) {
            while (rs.next()) {
                out.add(bloodDebt(rs.getString(1)));
            }
        }
        return out;
    }

    // ═══════════ 초기화(初期化)의 손 — 되돌리는 명령이 쓰는 최소 도구 ═══════════
    //
    // ★ 여기에 **정책은 없다.** 어떤 표를 지울 수 있는지는 config/reset.yml 이 정하고,
    //   그것을 검사하는 것은 {@link Reset} 이다. 이 아래의 메서드들은 표 이름을 **묻지 않고** 받는다 —
    //   그러므로 **아무나 부르면 안 된다.** 패키지 밖으로 내보내지 않는 이유다 (public 이 아니다).
    //
    // ★★ 봇은 단일 작성자다. MVT 는 이 파일을 열지 않는다 — 마크 쪽 몫은 다리로 **청한다**.

    /** 그 표가 이 DB 에 실재하는가 — {@code mvt_link_code} 는 곧 사라지고, {@code mvt_link_request} 는 아직 없을 수 있다 */
    public boolean hasTable(String table) throws Exception {
        return dialect.tableExists(conn, table);
    }

    /**
     * 지우기 <b>전에</b> 읽는다 — <b>무엇을 지웠는지 소리내어 말하기 위해서다</b> (조용한 삭제 금지).
     * 읽은 것은 백업 폴더의 {@code deleted.json} 에 그대로 적힌다.
     */
    public List<Map<String, Object>> rowsOf(String table, String column, Object value,
            String extraWhere) throws SQLException {
        String sql = "SELECT * FROM \"" + table + "\" WHERE \"" + column + "\" = ?"
                + (extraWhere == null || extraWhere.isBlank() ? "" : " AND (" + extraWhere + ")");
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                var meta = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    out.add(row);
                }
            }
        }
        return out;
    }

    /** 이 사람의 행만 지운다 — 표·축은 등록부(reset.yml)가 고른 것이고, 값은 이 사람의 것이다 */
    public int deleteRows(String table, String column, Object value, String extraWhere)
            throws SQLException {
        String sql = "DELETE FROM \"" + table + "\" WHERE \"" + column + "\" = ?"
                + (extraWhere == null || extraWhere.isBlank() ? "" : " AND (" + extraWhere + ")");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, value);
            return ps.executeUpdate();
        }
    }

    /** 이 계정의 캐릭터 전부 — <b>죽은 것도</b> (남겨 두면 다음 삶이 혈연으로 이어져 버린다) */
    public List<Long> characterIdsOf(String discordId) throws SQLException {
        List<Long> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM characters WHERE discord_id = ? ORDER BY id")) {
            ps.setString(1, discordId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
            }
        }
        return out;
    }

    /** 이 캐릭터에 매달린 몸들 — 접합을 끊으려면 uuid 를 알아야 한다 (끊고 나면 못 찾는다) */
    public List<String> mcUuidsOf(long characterId) throws SQLException {
        List<String> out = new java.util.ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT mc_uuid FROM mvt_link WHERE character_id = ?")) {
            ps.setLong(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    /** 이 몸의 계정 — 마크에서 친 초기화가 봇의 캐릭터를 찾는 유일한 길 (죽은 캐릭터도 본다) */
    public Optional<String> discordOfMc(String mcUuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT c.discord_id FROM mvt_link l JOIN characters c ON c.id = l.character_id "
                        + "WHERE l.mc_uuid = ?")) {
            ps.setString(1, mcUuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /**
     * <b>백업 — 살아 있는 DB 의 온전한 사본.</b> 파일을 그냥 복사하면 WAL 이 따로 놀아 <b>반쪽이 뜬다</b>
     * (이 DB 는 WAL 모드다: {@code honcheon.db-wal} 이 1.5MB 다). {@code VACUUM INTO} 는 SQLite 가
     * 트랜잭션 경계에서 <b>일관된 한 벌</b>을 새 파일로 굽는 유일한 방법이다 — 봇을 멈추지 않아도 된다.
     *
     * <p>★ 되돌리기: 봇을 끄고 이 파일을 {@code run/bot/honcheon.db} 로 덮은 뒤
     * {@code honcheon.db-wal}·{@code honcheon.db-shm} 을 <b>지운다</b> (옛 WAL 이 새 본체를 덮어쓴다).
     */
    public String snapshotFileName() {
        return dialect.snapshotFileName();
    }

    public String restoreInstructions(Path snapshot) {
        return dialect.restoreInstructions(snapshot);
    }

    public void snapshotTo(Path target) throws Exception {
        // ★ 스냅숏은 연결 상태(격리·읽기 전용·unwrap)를 직접 만진다 — 가면(RoutingConnection)
        //   너머로는 못 한다. 실제 연결을 빌려 주고, 무슨 일이 있어도 돌려받는다.
        Connection raw = source.borrow();
        try {
            dialect.snapshot(raw, target);
        } finally {
            source.release(raw);
        }
    }

    /** 샘의 지표 — 풀이 어떻게 살았는지 사람이 묻는 자리 (관측: PG-006). */
    public String storageStats() {
        return source.describe();
    }

    /**
     * 같은 뭉치(aggregate)를 놓고 겨루는 읽기-계산-쓰기 — 트랜잭션에 <b>합류</b>하거나, 새로 열고
     * <b>충돌하면 물러나 다시 잰다</b>.
     *
     * <p>PG-005 까지는 메서드 전체가 {@code synchronized} 라 이런 겨룸이 아예 없었다. PG-006 이
     * 그 자물쇠를 걷었으므로, 읽고-계산하고-쓰는 메서드는 이제 스스로 원자여야 한다:
     * PostgreSQL 은 SERIALIZABLE 이 충돌(40001)로 순서를 판정하고, 진 쪽이 여기서 다시 잰다.
     * SQLite 는 한 손(SingleConnectionSource)이라 트랜잭션이 곧 직렬이다.
     *
     * <p>이미 업무 트랜잭션 안이면 <b>합류</b>한다 — 중첩을 만들지 않고, 재시도는 바깥 주인의 몫이다.
     * (그래서 이 안의 일은 DB 밖 부수효과가 없어야 한다 — 재시도가 그것을 두 번 하게 된다.)
     */
    private <T> T atomically(TransactionRunner.Work<T> work) throws Exception {
        if (source.inTransaction()) {
            return work.run();
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return inTransaction(work);
            } catch (SQLException failure) {
                if (!dialect.isRetryableConflict(failure) || attempt >= 8) {
                    throw failure;
                }
                // 충돌은 순서의 판정이다 — 지수로 물러나되, 지터로 발걸음을 흩는다
                // (같은 박자로 물러나면 같은 박자로 다시 부딪힌다)
                long base = 5L << Math.min(attempt - 1, 6);
                Thread.sleep(base + java.util.concurrent.ThreadLocalRandom.current().nextLong(base));
            }
        }
    }

    /** {@link #atomically} 의 SQLException 낯 — 계약이 SQLException 인 포트 구현용. */
    private <T> T atomicallySql(TransactionRunner.Work<T> work) throws SQLException {
        try {
            return atomically(work);
        } catch (SQLException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SQLException(failure);
        }
    }

    /** 한 벌로 지운다 — 중간에 죽으면 되돌린다 (반쯤 지워진 사람이 세계에 남지 않는다) */
    @Override
    public <T> T inTransaction(TransactionRunner.Work<T> work) throws Exception {
        if (!conn.getAutoCommit()) {
            throw new SQLException("중첩 트랜잭션은 지원하지 않는다");
        }
        conn.setAutoCommit(false);
        try {
            T result = work.run();
            conn.commit();
            return result;
        } catch (Exception e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
