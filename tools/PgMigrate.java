package com.honcheon.bot;

import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.sqlite.SQLiteConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * PG-005 — SQLite 원본을 PostgreSQL 로 <b>일회성</b> 이관하고, 양쪽에서 검산한다.
 *
 * <p><b>원칙</b> (docs/design/postgresql_migration.md):
 * <ul>
 *   <li>원본 SQLite 는 <b>읽기 전용</b>으로만 연다 — 이 도구는 원본에 한 바이트도 쓰지 않는다.</li>
 *   <li>import 전체가 <b>PostgreSQL 트랜잭션 하나</b>다. 어디서 실패하든 대상은 통째로 되돌아간다.</li>
 *   <li>dual-write 는 없다. 정지 → 이관 → 검산 → 전환이 계약이다.</li>
 *   <li>대상이 비어 있지 않으면 <b>손대기 전에 거절한다</b> — 재실행이 데이터를 겹쳐 쓰는 일은 없다.</li>
 * </ul>
 *
 * <p><b>검산 다섯 축</b> — 커밋 <b>전에</b> 같은 트랜잭션에서 재고, 하나라도 어긋나면 롤백한다:
 * ① 표별 행 수 ② 세계 메타(world_meta 전체 키=값) ③ 캐릭터 전낭 합계(SUM wallet)
 * ④ 은행 합계(SUM balance) ⑤ bridge_inbox 멱등 키(정렬된 event_id 의 SHA-256).
 * FK 는 두 겹이다: 원본에서 {@code PRAGMA foreign_key_check} 로 미리 재고,
 * 적재 중에는 PostgreSQL 제약이 행마다 강제한다.
 *
 * <p><b>BIGSERIAL 보정</b>: 명시적 id 로 적재하면 시퀀스는 그대로 1 에 서 있다 —
 * 다음 INSERT 가 기존 id 와 충돌한다. 그래서 표마다 setval(max id) 로 밀어 둔다.
 *
 * <p><b>스냅숏 복원</b>: PG-004 의 {@code honcheon-postgresql.zip}(표별 CSV) 도
 * {@code from-snapshot} 모드로 같은 계약(빈 대상 · 한 트랜잭션 · 시퀀스 보정) 아래 복원한다.
 *
 * <p>사용법:
 * <pre>
 *   PgMigrate from-sqlite   &lt;honcheon.db&gt;              &lt;jdbc-url&gt; &lt;user&gt; &lt;password&gt; [보고서.md]
 *   PgMigrate from-snapshot &lt;honcheon-postgresql.zip&gt;  &lt;jdbc-url&gt; &lt;user&gt; &lt;password&gt; [보고서.md]
 * </pre>
 * 종료 코드: 성공 0 · 거절/실패 1 (보고서는 성공이든 실패든 남는다).
 */
public final class PgMigrate {

    /**
     * 부모 → 자식 순서. PostgreSQL 은 FK 를 행마다 즉시 강제하므로 <b>순서가 곧 정합성</b>이다.
     * characters 는 자기 자신(lineage_of)을 참조한다 — 조상이 항상 먼저 태어났으므로 id 오름차순이면 안전하다.
     * 표가 늘면 이 목록도 늘어야 한다: 모르는 표를 만나면 이 도구는 <b>멈춘다</b> (조용히 빼놓지 않는다).
     */
    static final List<String> TABLE_ORDER = List.of(
            "world_meta", "regions", "houses", "characters",
            "character_bank", "npcs", "rumors", "faction_standing", "myeongbun",
            "sect_state", "authority_mandate", "price_events", "registry",
            "events", "scenes", "mvt_link", "bridge_inbox", "mvt_link_code",
            "mvt_link_request", "blood_debt");

    /** SQLite 의 datetime('now') 이 남긴 형식 — UTC 다. TIMESTAMPTZ 로 갈 때 그 사실을 명시한다. */
    private static final DateTimeFormatter SQLITE_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StringBuilder report = new StringBuilder();
    private final List<String> failures = new ArrayList<>();

    private PgMigrate() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** main 과 분리 — 자기 시험이 프로세스를 죽이지 않고 종료 코드를 관찰한다. */
    public static int run(String[] args) {
        if (args.length < 5 || args.length > 6
                || !("from-sqlite".equals(args[0]) || "from-snapshot".equals(args[0]))) {
            System.err.println("사용법: PgMigrate from-sqlite|from-snapshot <원본> <jdbc-url> <user> <password> [보고서.md]");
            return 1;
        }
        String mode = args[0];
        Path source = Path.of(args[1]);
        String url = args[2];
        String user = args[3];
        String password = args[4];
        Path reportPath = Path.of(args.length == 6 ? args[5] : "run/bot/pg005_migration_report.md");

        PgMigrate m = new PgMigrate();
        m.say("# PG-005 이관 보고서");
        m.say("");
        m.say("- 모드: `" + mode + "`");
        m.say("- 원본: `" + source.toAbsolutePath() + "`");
        m.say("- 대상: `" + url + "`");
        m.say("- 시각: " + java.time.OffsetDateTime.now());
        m.say("");
        int code;
        try {
            if ("from-sqlite".equals(mode)) {
                m.fromSqlite(source, url, user, password);
            } else {
                m.fromSnapshot(source, url, user, password);
            }
            m.say("");
            m.say("## 총평: ✅ 이관 완료 — 검산 전 축 일치, 커밋됨");
            code = 0;
        } catch (Exception failure) {
            m.say("");
            m.say("## 총평: ❌ 이관 실패 — PostgreSQL 변경은 롤백됐다 (원본은 읽기 전용이라 무사하다)");
            m.say("");
            m.say("```");
            m.say(String.valueOf(failure.getMessage()));
            m.say("```");
            System.err.println("[이관] 실패 — " + failure.getMessage());
            code = 1;
        }
        try {
            if (reportPath.toAbsolutePath().getParent() != null) {
                Files.createDirectories(reportPath.toAbsolutePath().getParent());
            }
            Files.writeString(reportPath, m.report.toString());
            System.out.println("[이관] 보고서 — " + reportPath.toAbsolutePath());
        } catch (IOException io) {
            System.err.println("[이관] 보고서를 못 썼다 — " + io.getMessage());
            code = 1;
        }
        return code;
    }

    // ── SQLite → PostgreSQL ─────────────────────────────────────────────────

    private void fromSqlite(Path sqlite, String url, String user, String password) throws Exception {
        if (!Files.isRegularFile(sqlite)) {
            throw new IllegalArgumentException("원본 SQLite 가 없다: " + sqlite.toAbsolutePath());
        }
        try (Connection src = openSqliteReadOnly(sqlite);
             Connection dst = openPostgresql(url, user, password)) {

            // 원본의 표 목록이 이 도구가 아는 세계와 같은가 — 모르는 표는 조용히 빼놓지 않는다
            List<String> sourceTables = sqliteTables(src);
            requireKnownTables("SQLite 원본", sourceTables);

            // FK 는 SQLite 에선 꺼져 있었을 수 있다 — 고아 행은 여기서 미리 잡는다 (PG 는 어차피 거절한다)
            List<String> orphans = foreignKeyCheck(src);
            if (!orphans.isEmpty()) {
                throw new IllegalStateException("원본 FK 위반 " + orphans.size() + "건 — 먼저 고치라: "
                        + String.join(" · ", orphans.subList(0, Math.min(5, orphans.size()))));
            }
            say("- 원본 FK: ✅ `PRAGMA foreign_key_check` 위반 0건");

            ensureSchema(dst);
            requireEmptyTarget(dst);

            dst.setAutoCommit(false);   // ★ 여기서부터 커밋까지가 한 덩어리다
            try {
                // 스키마가 심은 씨앗(스키마_버전)을 걷어낸다 — 세계 메타의 정본은 원본이다
                try (Statement st = dst.createStatement()) {
                    st.executeUpdate("DELETE FROM world_meta");
                }
                Map<String, Long> loaded = new LinkedHashMap<>();
                for (String table : TABLE_ORDER) {
                    loaded.put(table, copyTable(src, dst, table));
                }
                fixSequences(dst, loaded);
                verify(src, dst, loaded);
                if (!failures.isEmpty()) {
                    throw new IllegalStateException("검산 불일치 " + failures.size() + "건 — "
                            + String.join(" · ", failures));
                }
                dst.commit();
                say("");
                say("커밋했다 — 검산이 전부 일치한 뒤에만 커밋한다.");
            } catch (Exception failure) {
                rollback(dst, failure);
                throw failure;
            } finally {
                dst.setAutoCommit(true);
            }
        }
    }

    /** 원본은 읽기 전용으로 연다 — 이관 도구가 원본을 오염시키는 길 자체를 없앤다. */
    private static Connection openSqliteReadOnly(Path sqlite) throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        return DriverManager.getConnection("jdbc:sqlite:" + sqlite, config.toProperties());
    }

    private static Connection openPostgresql(String url, String user, String password) throws Exception {
        if (url == null || !url.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("대상은 jdbc:postgresql: URL 이어야 한다: " + url);
        }
        Class.forName("org.postgresql.Driver");
        Properties props = new Properties();
        props.setProperty("user", user);
        if (password != null) {
            props.setProperty("password", password);
        }
        return DriverManager.getConnection(url, props);
    }

    /** 대상에 표가 없으면 통합 스키마를 세운다 (Db 생성자와 같은 단순 분할 — 스키마에 문자열 내 세미콜론 없음). */
    private void ensureSchema(Connection dst) throws Exception {
        if (tableExists(dst, "world_meta")) {
            say("- 대상 스키마: 이미 있다 (새로 세우지 않음)");
            return;
        }
        Path schema = Path.of("db/postgresql/schema.sql");
        if (!Files.isRegularFile(schema)) {
            throw new IllegalStateException("통합 스키마가 없다: " + schema.toAbsolutePath()
                    + " — 저장소 루트에서 실행하라");
        }
        try (Statement st = dst.createStatement()) {
            for (String sql : Files.readString(schema).split(";")) {
                String body = sql.strip().lines().filter(l -> !l.strip().startsWith("--"))
                        .reduce("", (a, b) -> a + "\n" + b).strip();
                if (!body.isEmpty()) {
                    st.execute(body);
                }
            }
        }
        say("- 대상 스키마: `db/postgresql/schema.sql` 로 세웠다");
    }

    /**
     * <b>비어 있지 않으면 거절한다</b> — 이관은 일회성이고, 겹쳐 쓰기는 없다.
     * 예외 하나: world_meta 의 스키마 씨앗(스키마_버전)만은 빈 것으로 친다 (스키마가 심은 것이다).
     */
    private void requireEmptyTarget(Connection dst) throws Exception {
        List<String> targetTables = postgresqlTables(dst);
        requireKnownTables("PostgreSQL 대상", targetTables);
        for (String table : targetTables) {
            long count = count(dst, table);
            if (count == 0) {
                continue;
            }
            if ("world_meta".equals(table)) {
                try (Statement st = dst.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT COUNT(*) FROM world_meta WHERE key <> '스키마_버전'")) {
                    rs.next();
                    if (rs.getLong(1) == 0) {
                        continue;   // 씨앗뿐 — 빈 것으로 친다
                    }
                }
            }
            throw new IllegalStateException("대상이 비어 있지 않다 — " + table + " 에 " + count
                    + "행. 이관은 빈 PostgreSQL 에만 한다 (재실행 보호). 지우려면 사람이 직접 지우라.");
        }
        say("- 대상 상태: ✅ 비어 있다 (이관 가능)");
    }

    /** 표 하나를 옮긴다 — 열 집합이 양쪽에서 같아야 하고(스키마 표류 거부), TIMESTAMPTZ 는 UTC 로 밝혀 적는다. */
    private long copyTable(Connection src, Connection dst, String table) throws Exception {
        Map<String, String> pgColumns = postgresqlColumns(dst, table);
        List<String> sqliteColumns = sqliteColumns(src, table);
        if (!pgColumns.keySet().equals(new java.util.HashSet<>(sqliteColumns))) {
            throw new IllegalStateException("표 " + table + " 의 열이 양쪽에서 다르다 — SQLite "
                    + sqliteColumns + " vs PostgreSQL " + pgColumns.keySet()
                    + " (마이그레이션 002~008 이 원본에 다 적용됐는지 보라)");
        }
        List<String> columns = new ArrayList<>(pgColumns.keySet());
        String quoted = String.join(", ", columns.stream().map(PgMigrate::quote).toList());
        String params = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
        // characters 의 lineage_of 는 자기참조 FK — 조상은 항상 먼저 태어났으므로 id 순이면 부모가 먼저 선다
        String order = columns.contains("id") ? " ORDER BY id" : "";
        long copied = 0;
        try (Statement read = src.createStatement();
             ResultSet rs = read.executeQuery("SELECT " + quoted + " FROM " + quote(table) + order);
             PreparedStatement write = dst.prepareStatement(
                     "INSERT INTO " + quote(table) + " (" + quoted + ") VALUES (" + params + ")")) {
            int batched = 0;
            while (rs.next()) {
                for (int i = 0; i < columns.size(); i++) {
                    bind(write, i + 1, pgColumns.get(columns.get(i)), rs.getObject(i + 1), table, columns.get(i));
                }
                write.addBatch();
                copied++;
                if (++batched == 500) {
                    write.executeBatch();
                    batched = 0;
                }
            }
            if (batched > 0) {
                write.executeBatch();
            }
        }
        return copied;
    }

    /** 값 하나를 PostgreSQL 형에 맞춰 적는다 — 어물쩍 넘기지 않고, 모르는 조합이면 소리내어 죽는다. */
    private static void bind(PreparedStatement ps, int index, String pgType, Object value,
                             String table, String column) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NULL);
            return;
        }
        switch (pgType) {
            case "integer", "bigint", "smallint" -> ps.setLong(index, ((Number) value).longValue());
            case "double precision", "real", "numeric" -> ps.setDouble(index, ((Number) value).doubleValue());
            case "timestamp with time zone" -> {
                // SQLite datetime('now') 는 UTC 텍스트다. 시간대를 밝히지 않고 넘기면
                // PostgreSQL 이 세션 시간대로 읽는다 — 그 순간 모든 시각이 조용히 어긋난다.
                String text = String.valueOf(value).replace('T', ' ');
                ps.setObject(index, LocalDateTime.parse(text, SQLITE_TS).atOffset(ZoneOffset.UTC));
            }
            case "text", "character varying" -> ps.setString(index, String.valueOf(value));
            default -> throw new SQLException("모르는 형 변환: " + table + "." + column
                    + " (" + pgType + " ← " + value.getClass().getSimpleName() + ") — 이 도구에 가르쳐라");
        }
    }

    // ── PG-004 CSV ZIP 스냅숏 → PostgreSQL ──────────────────────────────────

    /**
     * PG-004 의 논리 스냅숏({@code honcheon-postgresql.zip} · 표별 CSV, HEADER 포함)을 복원한다.
     * 같은 계약이다: 빈 대상 · 한 트랜잭션 · FK 순서 적재 · 시퀀스 보정. 원본 DB 가 없으므로
     * 검산은 COPY 가 보고한 적재 행 수와 표의 실제 행 수 대조, 그리고 FK·시퀀스로 한다.
     */
    private void fromSnapshot(Path zip, String url, String user, String password) throws Exception {
        if (!Files.isRegularFile(zip)) {
            throw new IllegalArgumentException("스냅숏이 없다: " + zip.toAbsolutePath());
        }
        Map<String, byte[]> entries = readZip(zip);
        requireKnownTables("스냅숏", new ArrayList<>(entries.keySet()));
        try (Connection dst = openPostgresql(url, user, password)) {
            ensureSchema(dst);
            requireEmptyTarget(dst);
            dst.setAutoCommit(false);
            try {
                try (Statement st = dst.createStatement()) {
                    st.executeUpdate("DELETE FROM world_meta");
                }
                CopyManager copy = new CopyManager(dst.unwrap(BaseConnection.class));
                Map<String, Long> loaded = new LinkedHashMap<>();
                for (String table : TABLE_ORDER) {          // zip 의 알파벳 순서가 아니라 FK 순서로
                    byte[] csv = entries.get(table);
                    if (csv == null) {
                        loaded.put(table, 0L);              // 스냅숏에 없는 표 = 그때 비어 있었음이 아니라
                        failures.add("스냅숏에 표 " + table + " 이 없다");   // — 없는 건 없는 거다. 소리낸다
                        continue;
                    }
                    long rows = copy.copyIn("COPY " + quote(table)
                                    + " FROM STDIN WITH (FORMAT CSV, HEADER TRUE)",
                            new ByteArrayInputStream(csv));
                    loaded.put(table, rows);
                }
                fixSequences(dst, loaded);
                say("");
                say("## 검산 (스냅숏 복원)");
                say("");
                say("| 표 | COPY 적재 | 실제 행 수 | 일치 |");
                say("|---|---|---|---|");
                for (String table : TABLE_ORDER) {
                    long actual = count(dst, table);
                    boolean ok = actual == loaded.get(table);
                    if (!ok) {
                        failures.add("표 " + table + " 행 수 불일치 (COPY " + loaded.get(table)
                                + " vs 실제 " + actual + ")");
                    }
                    say("| " + table + " | " + loaded.get(table) + " | " + actual + " | " + (ok ? "✅" : "❌") + " |");
                }
                if (!failures.isEmpty()) {
                    throw new IllegalStateException("스냅숏 복원 검산 실패 — " + String.join(" · ", failures));
                }
                dst.commit();
                say("");
                say("커밋했다 — FK 는 COPY 중에 PostgreSQL 제약이 행마다 강제했다.");
            } catch (Exception failure) {
                rollback(dst, failure);
                throw failure;
            } finally {
                dst.setAutoCommit(true);
            }
        }
    }

    private static Map<String, byte[]> readZip(Path zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                String name = entry.getName();
                if (!name.endsWith(".csv")) {
                    throw new IOException("스냅숏에 모르는 항목: " + name);
                }
                out.put(name.substring(0, name.length() - 4), in.readAllBytes());
            }
        }
        return out;
    }

    // ── 시퀀스 보정 ──────────────────────────────────────────────────────────

    /**
     * BIGSERIAL 은 명시적 id 적재를 <b>모른다</b> — 시퀀스는 1 에 서 있고, 다음 INSERT 가
     * 기존 행과 충돌한다. 표마다 시퀀스를 max(id) 로 밀고, 밀린 값을 다시 읽어 확인한다.
     */
    private void fixSequences(Connection dst, Map<String, Long> loaded) throws Exception {
        say("");
        say("## 시퀀스 보정 (BIGSERIAL → max id)");
        say("");
        say("| 표 | 시퀀스 | max(id) | setval 후 last_value |");
        say("|---|---|---|---|");
        for (String table : TABLE_ORDER) {
            String sequence = serialSequence(dst, table);
            if (sequence == null) {
                continue;   // id 시퀀스가 없는 표 (TEXT PK 등)
            }
            Long max = maxId(dst, table);
            long lastValue;
            try (PreparedStatement ps = dst.prepareStatement(
                    max == null ? "SELECT setval(?, 1, false)" : "SELECT setval(?, ?)")) {
                ps.setString(1, sequence);
                if (max != null) {
                    ps.setLong(2, max);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    lastValue = rs.getLong(1);
                }
            }
            long expected = max == null ? 1 : max;
            if (lastValue != expected) {
                failures.add("시퀀스 " + sequence + " 보정 실패 (기대 " + expected + " vs " + lastValue + ")");
            }
            say("| " + table + " | " + sequence + " | " + (max == null ? "—" : max) + " | " + lastValue + " |");
        }
    }

    private static String serialSequence(Connection dst, String table) throws SQLException {
        // ★ id 열이 없는 표에 pg_get_serial_sequence 를 물으면 예외가 난다 — 그리고 PostgreSQL 은
        //   예외가 난 트랜잭션 전체를 오염시킨다 (이후 모든 문장이 거부된다). 우리는 지금
        //   이관 트랜잭션 **안**이다. 그러므로 묻기 전에 정보 스키마로 열의 존재부터 확인한다.
        try (PreparedStatement ps = dst.prepareStatement(
                "SELECT 1 FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = 'id'")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;   // id 열 자체가 없는 표 (world_meta 등)
                }
            }
        }
        try (PreparedStatement ps = dst.prepareStatement("SELECT pg_get_serial_sequence(?, 'id')")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;   // id 는 있지만 시퀀스가 아니면 NULL (npcs 등)
            }
        }
    }

    private static Long maxId(Connection dst, String table) throws SQLException {
        try (Statement st = dst.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(id) FROM " + quote(table))) {
            rs.next();
            long max = rs.getLong(1);
            return rs.wasNull() ? null : max;
        }
    }

    // ── 검산 — 양쪽에서 같은 것을 재서 대조한다 ─────────────────────────────────

    private void verify(Connection src, Connection dst, Map<String, Long> loaded) throws Exception {
        say("");
        say("## 검산 — 다섯 축, 양쪽에서 잰다");
        say("");
        say("### ① 표별 행 수");
        say("");
        say("| 표 | SQLite | PostgreSQL | 일치 |");
        say("|---|---|---|---|");
        for (String table : TABLE_ORDER) {
            long a = count(src, table);
            long b = count(dst, table);
            boolean ok = a == b && b == loaded.get(table);
            if (!ok) {
                failures.add("표 " + table + " 행 수 (SQLite " + a + " vs PG " + b + ")");
            }
            say("| " + table + " | " + a + " | " + b + " | " + (ok ? "✅" : "❌") + " |");
        }

        say("");
        say("### ② 세계 메타 (world_meta 전체)");
        Map<String, String> metaSrc = worldMeta(src);
        Map<String, String> metaDst = worldMeta(dst);
        boolean metaOk = metaSrc.equals(metaDst);
        if (!metaOk) {
            failures.add("world_meta 불일치 (SQLite " + metaSrc + " vs PG " + metaDst + ")");
        }
        say("");
        for (Map.Entry<String, String> e : metaSrc.entrySet()) {
            say("- `" + e.getKey() + "` = `" + e.getValue() + "` "
                    + (e.getValue().equals(metaDst.get(e.getKey())) ? "✅" : "❌"));
        }
        say("- 전체 일치: " + (metaOk ? "✅" : "❌"));

        say("");
        say("### ③·④ 원장 합계");
        long walletSrc = sum(src, "SELECT COALESCE(SUM(wallet), 0) FROM characters");
        long walletDst = sum(dst, "SELECT COALESCE(SUM(wallet), 0) FROM characters");
        long bankSrc = sum(src, "SELECT COALESCE(SUM(balance), 0) FROM character_bank");
        long bankDst = sum(dst, "SELECT COALESCE(SUM(balance), 0) FROM character_bank");
        if (walletSrc != walletDst) {
            failures.add("전낭 합계 (SQLite " + walletSrc + " vs PG " + walletDst + ")");
        }
        if (bankSrc != bankDst) {
            failures.add("은행 합계 (SQLite " + bankSrc + " vs PG " + bankDst + ")");
        }
        say("");
        say("- 전낭 SUM(wallet): " + walletSrc + " vs " + walletDst + " " + (walletSrc == walletDst ? "✅" : "❌"));
        say("- 은행 SUM(balance): " + bankSrc + " vs " + bankDst + " " + (bankSrc == bankDst ? "✅" : "❌"));

        say("");
        say("### ⑤ 브리지 멱등 키 (정렬된 event_id 의 SHA-256)");
        String keysSrc = bridgeKeyDigest(src);
        String keysDst = bridgeKeyDigest(dst);
        if (!keysSrc.equals(keysDst)) {
            failures.add("bridge_inbox 멱등 키 지문 불일치");
        }
        say("");
        say("- SQLite: `" + keysSrc + "`");
        say("- PostgreSQL: `" + keysDst + "`");
        say("- 일치: " + (keysSrc.equals(keysDst) ? "✅" : "❌"));

        say("");
        say("### FK");
        say("");
        say("- 원본: `PRAGMA foreign_key_check` 위반 0건 (적재 전 확인)");
        say("- 대상: PostgreSQL 제약이 적재 중 행마다 강제 — 위반이 있었다면 여기까지 오지 못했다");
    }

    private static long count(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + quote(table))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long sum(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Map<String, String> worldMeta(Connection conn) throws SQLException {
        Map<String, String> out = new TreeMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT key, value FROM world_meta")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getString(2));
            }
        }
        return out;
    }

    /** 멱등 키 전체의 지문 — 행 수가 같아도 <b>내용이 다른</b> 경우를 잡는다. */
    private static String bridgeKeyDigest(Connection conn) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT event_id FROM bridge_inbox ORDER BY event_id")) {
            while (rs.next()) {
                digest.update(rs.getString(1).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    // ── 표·열 메타 ─────────────────────────────────────────────────────────

    private static List<String> sqliteTables(Connection src) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = src.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' "
                     + "AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static List<String> postgresqlTables(Connection dst) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = dst.prepareStatement(
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

    /** 열 이름 → PostgreSQL data_type (선언 순서 유지). */
    private static Map<String, String> postgresqlColumns(Connection dst, String table) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (PreparedStatement ps = dst.prepareStatement(
                "SELECT column_name, data_type FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? ORDER BY ordinal_position")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return out;
    }

    private static List<String> sqliteColumns(Connection src, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = src.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + quote(table) + ")")) {
            while (rs.next()) {
                out.add(rs.getString("name"));
            }
        }
        return out;
    }

    private static List<String> foreignKeyCheck(Connection src) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = src.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA foreign_key_check")) {
            while (rs.next()) {
                out.add(rs.getString(1) + " rowid=" + rs.getLong(2) + " → " + rs.getString(3));
            }
        }
        return out;
    }

    /** 모르는 표를 만나면 멈춘다 — 조용히 빼놓은 표는 조용히 사라진 데이터다. */
    private void requireKnownTables(String side, List<String> tables) {
        List<String> unknown = tables.stream().filter(t -> !TABLE_ORDER.contains(t)).toList();
        List<String> missing = TABLE_ORDER.stream().filter(t -> !tables.contains(t)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(side + " 에 이 도구가 모르는 표가 있다: " + unknown
                    + " — TABLE_ORDER 에 가르치고 다시 오라 (조용히 빼놓지 않는다)");
        }
        if (!missing.isEmpty() && !"스냅숏".equals(side)) {
            throw new IllegalStateException(side + " 에 있어야 할 표가 없다: " + missing);
        }
        say("- " + side + " 표: " + tables.size() + "개, 전부 아는 표다");
    }

    private static boolean tableExists(Connection dst, String table) throws SQLException {
        try (PreparedStatement ps = dst.prepareStatement(
                "SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static void rollback(Connection conn, Exception original) {
        try {
            conn.rollback();
        } catch (SQLException failure) {
            original.addSuppressed(failure);
        }
    }

    private void say(String line) {
        report.append(line).append('\n');
    }
}
