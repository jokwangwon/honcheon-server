package com.honcheon.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.honcheon.domain.FactionLedger;
import com.honcheon.domain.RegionLedger;

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
 *
 * <p>★ <b>장부이지 규칙이 아니다.</b> 세력({@link FactionLedger})·지역({@link RegionLedger}) 두 축은
 * 도메인이 정의한 <b>포트</b>를 구현한다 — 이 클래스는 행을 읽고 쓸 뿐, 클램프도 감쇠도 하지 않는다.
 * 그 산수는 {@code domain.FactionService}/{@code domain.RegionService} 가
 * {@code core} 룰 엔진에게 시킨다. <b>정본은 하나다.</b>
 */
public final class Db implements AutoCloseable, FactionLedger, RegionLedger {

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
    public synchronized FactionLedger.Row standingRow(String factionId, long characterId)
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
    public synchronized List<String> standingFactions(long characterId) throws SQLException {
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
    public synchronized void writeStanding(String factionId, long characterId, FactionLedger.Row row)
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
    record Issue(String issue, String target, List<String> victims, List<String> tags, int rawGauge,
                 int originAccuracy, String originRumor, String trueTarget, int createdDay,
                 int updatedDay) {
    }

    @SuppressWarnings("unchecked")
    private Issue readIssue(ResultSet rs) throws Exception {
        List<String> tags = JSON.readValue(rs.getString("tags_json"), List.class);
        List<String> victims = JSON.readValue(rs.getString("victims_json"), List.class);
        return new Issue(rs.getString("issue"), rs.getString("target"), victims, tags,
                rs.getInt("raw_gauge"), rs.getInt("origin_accuracy"),
                rs.getString("origin_rumor"), rs.getString("true_target"),
                rs.getInt("created_day"), rs.getInt("updated_day"));
    }

    /** 지금 세계에 걸린 모든 사안 (저장값 — 감쇠 정산은 Politics.decayed 가 읽는 순간 한다) */
    public synchronized List<Issue> issues() throws Exception {
        List<Issue> out = new java.util.ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM myeongbun ORDER BY created_day, issue")) {
            while (rs.next()) {
                out.add(readIssue(rs));
            }
        }
        return out;
    }

    public synchronized Optional<Issue> issue(String key) throws Exception {
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
    synchronized Issue addMyeongbun(String key, String target, List<String> victims,
                                    List<String> tags, int delta, int accuracy, String rumorGroup,
                                    String trueTarget, int day, int max, Politics politics)
            throws Exception {
        Optional<Issue> found = issue(key);
        int base = found.map(i -> politics.decayed(i.rawGauge(), i.tags(), i.updatedDay(), day))
                .orElse(0);
        int next = Math.max(0, Math.min(max, base + delta));
        // 태그도 **누적된다** — 한 사안에 두 태그가 겹치면 감응 세력이 늘어난다.
        // ★ 이것이 혈채 루트의 발화 지점이다: 무고(-3)만으로는 안 뭉친 무림이,
        //   같은 사안에 금기(-4)가 붙는 순간 뭉친다 (faction_politics blood_debt_ignition.①_금기).
        java.util.LinkedHashSet<String> mergedTags = new java.util.LinkedHashSet<>(
                found.map(Issue::tags).orElse(List.of()));
        if (tags != null) {
            mergedTags.addAll(tags);
        }
        // 피해 세력은 **누적된다** — 관이 두 번째 문파를 치면 피해자가 둘이 된다 (그래서 연합이 커진다)
        java.util.LinkedHashSet<String> mergedVictims = new java.util.LinkedHashSet<>(
                found.map(Issue::victims).orElse(List.of()));
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
            ps.setInt(9, found.map(Issue::createdDay).orElse(day));
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
    synchronized int sectBurden(String faction, int today, int decayEveryDays) throws SQLException {
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
    synchronized int addSectBurden(String faction, int delta, String source, int today,
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
    synchronized Map<String, Integer> sectBurdens(int today, int decayEveryDays) throws SQLException {
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
    synchronized void transferMyeongbun(String key, String newTarget, int accuracy, int day)
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
    synchronized int mandate(long characterId, int today, Politics politics) throws SQLException {
        Mandate raw = rawMandate(characterId);
        return politics.decayedMandate(raw.gauge(), raw.peak(), raw.updatedDay(), today);
    }

    /** 법명분 가산 — 포두 +8 · 현령 +14 / 자수 -10 · 배상 -6. peak 는 감쇠 하한의 근거로 남는다 */
    synchronized int addMandate(long characterId, int delta, int today, Politics politics)
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
    synchronized int rumorAccuracyIn(String group, String network, int day) throws SQLException {
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

    // ═══ 세계 다리 (마이그레이션 005) — 마크(MVT)의 사건이 이 장부로 들어오는 문 ═══
    //
    // 다리는 세계 상태를 새로 만들지 않는다. 무명이 죽으면 rumors 가 자라고 regions.민심이 깎인다 —
    // 이미 있는 표들이다. 여기 새로 드는 것은 부속 둘뿐이다:
    //   mvt_link     신원 — 마크의 몸(uuid)과 봇의 장부(character_id)를 잇는다.
    //                이 표가 비면 소문에 **주체가 없다** → 세력이 아무도 주목하지 않는다 (배경음).
    //   bridge_inbox 멱등 — 같은 줄을 두 번 읽어도 사람은 한 번만 죽는다.

    /**
     * 이 사건을 지금 처음 보는가 — 처음이면 못을 박고 true. 이미 박혀 있으면 false (건너뛴다).
     * 커서를 못 쓰고 죽어도, 파일을 통째로 재생해도 세계는 같은 자리에 선다.
     */
    public synchronized boolean claimBridgeEvent(String eventId, String kind) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO bridge_inbox(event_id, kind, world_day) VALUES(?, ?, ?)")) {
            ps.setString(1, eventId);
            ps.setString(2, kind);
            ps.setInt(3, worldDay());
            return ps.executeUpdate() > 0;
        }
    }

    /** 마크 플레이어 ↔ 캐릭터 접합 (character_id 가 null 이면 '아직 안 이어진 몸'으로만 등록된다) */
    public synchronized void linkMvt(String mcUuid, String mcName, Long characterId) throws SQLException {
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

    // ═══ 신원 접합 (마이그레이션 006) — 마크가 코드를 내고, 디스코드가 확정한다 ═══
    //
    // ★ 최종 결속이 디스코드에 있는 이유: 캐릭터를 훔치려면 그 사람의 **디스코드 계정**이 필요해진다.
    //   코드가 새어 나가도 도둑이 할 수 있는 최악은 '제 캐릭터에 남의 몸을 붙이는 것' — 자해다.
    //   (config/world_bridge.yml identity.direction: mvt_issues_discord_confirms)

    /** 마크가 낸 코드를 대기열에 앉힌다 — 그 몸의 이전 대기 코드는 폐기된다 (one_pending_per_body) */
    public synchronized void pendLinkCode(String code, String mcUuid, String mcName,
                                          long issuedAt, long expiresAt) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE mvt_link_code SET state = '폐기' WHERE mc_uuid = ? AND state = '대기'")) {
            ps.setString(1, mcUuid);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO mvt_link_code(code, mc_uuid, mc_name, issued_at, expires_at, state) "
                        + "VALUES(?, ?, ?, ?, ?, '대기') ON CONFLICT(code) DO NOTHING")) {
            ps.setString(1, code);
            ps.setString(2, mcUuid);
            ps.setString(3, mcName);
            ps.setLong(4, issuedAt);
            ps.setLong(5, expiresAt);
            ps.executeUpdate();
        }
    }

    /** 대기 중인 코드 한 장 (만료 여부는 부르는 쪽이 판정한다 — 이유를 사람에게 말해 줘야 하므로) */
    public record LinkCode(String code, String mcUuid, String mcName, long expiresAt, String state) {

        public boolean expired(long now) {
            return now > expiresAt;
        }
    }

    public synchronized Optional<LinkCode> linkCode(String code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT code, mc_uuid, mc_name, expires_at, state FROM mvt_link_code WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new LinkCode(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getLong(4), rs.getString(5))) : Optional.empty();
            }
        }
    }

    /** 코드를 태운다 (single_use) — 한 번 쓰면 그 문자열은 죽는다 */
    public synchronized void burnLinkCode(String code, long characterId, int day) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE mvt_link_code SET state = '사용됨', used_by = ?, used_day = ? WHERE code = ?")) {
            ps.setLong(1, characterId);
            ps.setInt(2, day);
            ps.setString(3, code);
            ps.executeUpdate();
        }
    }

    /** 이 캐릭터에게 이미 몸이 있는가 (one_body_one_character — 양쪽 다 1:1) */
    public synchronized Optional<String> mcOfCharacter(long characterId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT mc_uuid FROM mvt_link WHERE character_id = ?")) {
            ps.setLong(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /** 이 몸에 붙어 있는 캐릭터 (죽은 캐릭터도 본다 — 재접합 판정은 산 자만 막는다) */
    public synchronized Optional<Long> rawCharacterOfMc(String mcUuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT character_id FROM mvt_link WHERE mc_uuid = ? AND character_id IS NOT NULL")) {
            ps.setString(1, mcUuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        }
    }

    /** 접합 해제 — 몸은 남고 이름만 떨어진다 (혈채는 캐릭터 원장에 그대로 남는다. 빚은 안 없어진다) */
    public synchronized void unlinkMc(String mcUuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE mvt_link SET character_id = NULL, linked_day = ? WHERE mc_uuid = ?")) {
            ps.setInt(1, worldDay());
            ps.setString(2, mcUuid);
            ps.executeUpdate();
        }
    }

    /** 이 몸은 누구인가 — 살아 있는 캐릭터만 (죽은 자의 이름으로는 소문이 붙지 않는다) */
    public synchronized Optional<Long> characterOfMc(String mcUuid) throws SQLException {
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
    public synchronized Map<String, Long> linkedBodies() throws SQLException {
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
    public synchronized void writeRegion(Map<String, Integer> values) throws SQLException {
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
    public synchronized Map<String, Integer> region() throws SQLException {
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
    public synchronized java.util.Set<String> liveRumorTags(int day, int decayEveryDays) throws Exception {
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

    /** 혈채 원장 한 줄 (known 은 저장값 — 오늘의 값은 BloodDebt.decayedKnown 이 읽는 순간 정산한다) */
    public record Debt(String subject, Long characterId, double hidden, double knownRaw,
                       int knownDay, int publicCount, int kills, double exposureFloor) {

        public static Debt empty(String subject) {
            return new Debt(subject, null, 0, 0, 0, 0, 0, 0);
        }
    }

    public synchronized Debt bloodDebt(String subject) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT subject, character_id, hidden, known_raw, known_day, public_count, kills, "
                        + "exposure_floor FROM blood_debt WHERE subject = ?")) {
            ps.setString(1, subject);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Debt.empty(subject);
                }
                Long chId = rs.getObject(2) == null ? null : rs.getLong(2);
                return new Debt(rs.getString(1), chId, rs.getDouble(3), rs.getDouble(4),
                        rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getDouble(8));
            }
        }
    }

    /** 캐릭터의 장부 (subject = character:<id>) */
    public synchronized Debt bloodDebtOf(long characterId) throws SQLException {
        return bloodDebt("character:" + characterId);
    }

    /**
     * 한 건을 적는다. <b>암혈채는 노출과 무관하게 자란다</b> (몸이 장부다) —
     * 현혈채만 노출·정확도 배수를 먹는다. 공개(witness 2) 건은 감쇠 하한(×2)의 근거로 따로 센다.
     */
    public synchronized Debt addBloodDebt(String subject, Long characterId, double hidden,
                                          double known, boolean publicKill, int day)
            throws SQLException {
        Debt now = bloodDebt(subject);
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
    public synchronized Debt mergeBloodDebt(String from, long characterId, int day) throws SQLException {
        Debt src = bloodDebt(from);
        if (src.hidden() <= 0 && src.knownRaw() <= 0 && src.exposureFloor() <= 0) {
            return bloodDebtOf(characterId);
        }
        Debt before = bloodDebtOf(characterId);   // 합치기 전의 그의 장부 (건수 이중 계상 금지)
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
    public synchronized void setExposureFloor(String subject, Long characterId, double floor, int day)
            throws SQLException {
        Debt now = bloodDebt(subject);
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
    public synchronized List<Debt> bloodDebts() throws SQLException {
        List<Debt> out = new java.util.ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT subject FROM blood_debt ORDER BY subject")) {
            while (rs.next()) {
                out.add(bloodDebt(rs.getString(1)));
            }
        }
        return out;
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
