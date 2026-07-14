package com.honcheon.bot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

/**
 * PG-005 이관 도구의 자기 시험 — <b>실제</b> SQLite fixture 와 <b>실제</b> 빈 PostgreSQL 16 에서
 * 성공·실패·재실행을 전부 겪어 본다.
 *
 * <p>시험하는 계약:
 * <ol>
 *   <li>원본 FK 위반(고아 행)은 PostgreSQL 을 <b>건드리기 전에</b> 거절된다</li>
 *   <li>적재 도중 실패(썩은 타임스탬프)는 <b>전체 롤백</b>된다 — 반쯤 이관된 세계는 없다</li>
 *   <li>원본 SQLite 는 어떤 실행 뒤에도 <b>한 바이트도 변하지 않는다</b></li>
 *   <li>성공 시 행 수·세계 메타·전낭/은행 합계·멱등 키가 양쪽에서 일치한다</li>
 *   <li>이관된 DB 는 실제 Db 로 열어 <b>새 행을 만들 수 있다</b> — BIGSERIAL 보정의 실증
 *       (보정이 없으면 첫 INSERT 가 기존 id 와 충돌한다)</li>
 *   <li>재실행은 거절된다 — 이관은 일회성이고 겹쳐 쓰기는 없다</li>
 *   <li>PG-004 CSV ZIP 스냅숏도 같은 계약으로 복원된다</li>
 * </ol>
 *
 * <p>사용법: {@code PgMigrateSelfTest <jdbc-url> <user> <password>}
 * (주어진 서버에 pgmigrate_selftest_a / _b 데이터베이스를 만들고 지운다)
 */
public final class PgMigrateSelfTest {
    private static int eyes;

    private PgMigrateSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("사용법: <jdbc-url> <user> <password>");
        }
        String adminUrl = args[0];
        String user = args[1];
        String password = args[2];
        int slash = adminUrl.lastIndexOf('/');
        String base = adminUrl.substring(0, slash + 1);
        String urlA = base + "pgmigrate_selftest_a";
        String urlB = base + "pgmigrate_selftest_b";

        Path home = Files.createTempDirectory("honcheon-pgmigrate");
        Path sqlite = home.resolve("fixture.db");
        buildFixture(sqlite);

        recreate(adminUrl, user, password, "pgmigrate_selftest_a", "pgmigrate_selftest_b");
        try {
            // ── ① 원본 FK 위반은 PostgreSQL 을 건드리기 전에 거절된다 ──────────
            // ★ 원본 불변의 눈은 **도구 실행 전후**를 잰다 — 독을 넣고 빼는 것은 이 시험 자신이므로
            //   시험 전체 전후를 재면 자기 손을 도구의 죄로 뒤집어씌우게 된다.
            poison(sqlite, "INSERT INTO faction_standing(faction_id, character_id) VALUES('유령문', 424242)");
            byte[] beforeRun = sha256(sqlite);
            int rc = PgMigrate.run(new String[]{"from-sqlite", sqlite.toString(),
                    urlA, user, password, home.resolve("report_fk.md").toString()});
            eye("고아 FK 원본은 거절된다 (exit 1)", rc != 0);
            eye("거절 실행이 원본을 건드리지 않았다 (SHA-256 일치)",
                    java.util.Arrays.equals(beforeRun, sha256(sqlite)));
            try (Connection a = connect(urlA, user, password)) {
                eye("거절은 대상을 건드리기 전이다 (표가 서지 않았다)", !hasTable(a, "world_meta"));
            }
            poison(sqlite, "DELETE FROM faction_standing WHERE character_id = 424242");

            // ── ② 적재 도중 실패는 전체 롤백된다 ─────────────────────────────
            poison(sqlite, "INSERT INTO events(day, type, actor_type, actor_id, data_json, created_at) "
                    + "VALUES(1, '독', '세계', 'poison', '{}', '어제쯤')");
            rc = PgMigrate.run(new String[]{"from-sqlite", sqlite.toString(),
                    urlA, user, password, home.resolve("report_rollback.md").toString()});
            eye("썩은 타임스탬프는 실패한다 (exit 1)", rc != 0);
            try (Connection a = connect(urlA, user, password)) {
                // events 는 적재 순서상 뒤쪽이다 — 실패 시점에 characters 등은 이미 적재돼 있었다.
                // 그런데도 전부 비어 있어야 한다. 그것이 "한 트랜잭션"의 뜻이다.
                long stray = 0;
                for (String table : PgMigrate.TABLE_ORDER) {
                    stray += "world_meta".equals(table) ? 0 : count(a, table);
                }
                eye("실패 뒤 대상은 통째로 비어 있다 (반쯤 이관된 세계는 없다)", stray == 0);
                eye("world_meta 에는 스키마 씨앗만 남아 있다 (DELETE 도 롤백됐다)",
                        count(a, "world_meta") == 1);
            }
            poison(sqlite, "DELETE FROM events WHERE actor_id = 'poison'");

            // ── ③ 성공 — 검산 다섯 축이 양쪽에서 일치한다 ─────────────────────
            byte[] cleanFixture = sha256(sqlite);   // 여기부터 원본에 손대는 자는 도구뿐이다
            rc = PgMigrate.run(new String[]{"from-sqlite", sqlite.toString(),
                    urlA, user, password, home.resolve("report_ok.md").toString()});
            eye("깨끗한 원본은 이관된다 (exit 0)", rc == 0);
            String report = Files.readString(home.resolve("report_ok.md"));
            eye("보고서가 성공을 말한다", report.contains("총평: ✅"));
            eye("보고서에 다섯 축이 있다", report.contains("표별 행 수") && report.contains("세계 메타")
                    && report.contains("원장 합계") && report.contains("멱등 키") && report.contains("시퀀스 보정"));
            try (Connection src = roSqlite(sqlite);   // 대조하는 눈도 원본에 쓰지 않는다
                 Connection a = connect(urlA, user, password)) {
                boolean countsMatch = true;
                for (String table : PgMigrate.TABLE_ORDER) {
                    countsMatch &= count(src, table) == count(a, table);
                }
                eye("표 20개 행 수가 전부 일치한다", countsMatch);
                eye("전낭 합계가 일치한다", scalar(src, "SELECT COALESCE(SUM(wallet),0) FROM characters")
                        == scalar(a, "SELECT COALESCE(SUM(wallet),0) FROM characters"));
                eye("은행 합계가 일치한다", scalar(src, "SELECT COALESCE(SUM(balance),0) FROM character_bank")
                        == scalar(a, "SELECT COALESCE(SUM(balance),0) FROM character_bank"));
                eye("세계 메타가 일치한다 (시즌=봄 포함)",
                        "봄".equals(meta(a, "시즌")) && meta(a, "현재일") != null);
                eye("타임스탬프가 UTC 로 살아남았다 (2026-07-10T17:30:39Z)",
                        scalar(a, "SELECT COUNT(*) FROM events WHERE created_at = TIMESTAMPTZ '2026-07-10 17:30:39+00'") == 1);
            }

            // ── ④ 이관된 DB 는 실제 Db 가 그대로 쓴다 — BIGSERIAL 보정의 실증 ──
            long before;
            long created;
            try (Connection a = connect(urlA, user, password)) {
                before = scalar(a, "SELECT MAX(id) FROM characters");
            }
            try (Db db = Db.open(env(urlA, user, password))) {
                created = db.createCharacter("pg005-user", "이관후생", Map.of("근력", 3), 12);
            }
            eye("이관 후 첫 INSERT 가 id 충돌 없이 선다 (시퀀스 보정 실증: "
                    + created + " > " + before + ")", created > before);

            // ── ⑤ 재실행은 거절된다 ──────────────────────────────────────────
            rc = PgMigrate.run(new String[]{"from-sqlite", sqlite.toString(),
                    urlA, user, password, home.resolve("report_rerun.md").toString()});
            eye("재실행은 거절된다 (이관은 일회성이다)", rc != 0);
            try (Connection a = connect(urlA, user, password)) {
                eye("거절된 재실행은 아무것도 바꾸지 않았다 (fixture 4 + 실증 1 = 5 그대로)",
                        scalar(a, "SELECT COUNT(*) FROM characters") == 5);
            }

            // ── ⑥ 원본은 한 바이트도 변하지 않았다 (성공·재실행 실행을 통틀어) ──
            eye("원본 SQLite 가 이관·재실행 뒤에도 그대로다 (SHA-256 일치)",
                    java.util.Arrays.equals(cleanFixture, sha256(sqlite)));

            // ── ⑦ PG-004 CSV ZIP 스냅숏 복원 — 같은 계약이다 ─────────────────
            Path zip = home.resolve("honcheon-postgresql.zip");
            try (Connection a = connect(urlA, user, password)) {
                new PostgresqlDialect(urlA, user, password).snapshot(a, zip);
            }
            rc = PgMigrate.run(new String[]{"from-snapshot", zip.toString(),
                    urlB, user, password, home.resolve("report_snapshot.md").toString()});
            eye("스냅숏이 빈 PostgreSQL 로 복원된다 (exit 0)", rc == 0);
            try (Connection a = connect(urlA, user, password);
                 Connection b = connect(urlB, user, password)) {
                boolean parity = true;
                for (String table : PgMigrate.TABLE_ORDER) {
                    parity &= count(a, table) == count(b, table);
                }
                eye("복원본의 표 20개 행 수가 원본 PostgreSQL 과 일치한다", parity);
            }
            try (Db db = Db.open(env(urlB, user, password))) {
                long id = db.createCharacter("pg005-user-b", "복원후생", Map.of("근력", 2), 13);
                eye("복원본도 첫 INSERT 가 충돌 없이 선다 (스냅숏 길의 시퀀스 보정)", id > 0);
            }
        } finally {
            drop(adminUrl, user, password, "pgmigrate_selftest_a", "pgmigrate_selftest_b");
        }
        System.out.println();
        System.out.println("✔ 이관 눈 " + eyes + "개 — 전부 통과");
    }

    // ── fixture — 실제 스키마(db/schema.sql)로 만든 진짜 SQLite 세계 ─────────

    private static void buildFixture(Path sqlite) throws Exception {
        // Db 생성자가 스키마를 세우고 현재일·지역·스키마_버전을 심는다 — 운영 DB 와 같은 출생이다
        try (Db db = new Db(sqlite, Path.of("db/schema.sql"))) {
            // 태어나기만 하면 된다
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sqlite);
             Statement st = c.createStatement()) {
            // 【B-101 닫힘 후】 schema.sql 이 이제 008(가문)까지 품은 20표 완결 상태다 —
            // fixture 가 손수 얹던 houses/house_id DDL 은 중복이 되어 걷었다 (2026-07-14).
            st.executeUpdate("INSERT INTO world_meta(key, value) VALUES('시즌', '봄')");
            st.executeUpdate("INSERT INTO houses(family, name, region, state, created_day) "
                    + "VALUES('남궁', '남궁세가', 'cheongha_hyeon', '활성', 1)");
            st.executeUpdate("INSERT INTO characters(discord_id, name, status, realm, sheet_json, wallet, created_day, house_id) "
                    + "VALUES('u1', '조상', '사망', '일류', '{}', 100, 1, 1)");
            st.executeUpdate("INSERT INTO characters(discord_id, name, status, realm, sheet_json, wallet, created_day, lineage_of) "
                    + "VALUES('u1', '후손', '활성', '이류', '{}', 250, 5, 1)");
            st.executeUpdate("INSERT INTO characters(discord_id, name, status, realm, sheet_json, wallet, created_day) "
                    + "VALUES('u2', '무명객', '활성', '범인', '{}', 3, 7)");
            st.executeUpdate("INSERT INTO characters(discord_id, name, status, realm, sheet_json, wallet, created_day) "
                    + "VALUES('u3', '점소이', '활성', '범인', '{}', 0, 9)");
            st.executeUpdate("INSERT INTO character_bank(character_id, branch, balance) VALUES(2, '중앙전장', 1200)");
            st.executeUpdate("INSERT INTO character_bank(character_id, branch, balance) VALUES(3, '중앙전장', 45)");
            st.executeUpdate("INSERT INTO bridge_inbox(event_id, kind, world_day) VALUES('evt-001', 'first_blood', 3)");
            st.executeUpdate("INSERT INTO bridge_inbox(event_id, kind, world_day) VALUES('evt-002', 'qi_manifested', 4)");
            st.executeUpdate("INSERT INTO bridge_inbox(event_id, kind, world_day) VALUES('evt-003', 'surrender', 5)");
            // 고정 시각 하나 — TIMESTAMPTZ 변환이 UTC 를 지키는지 대상에서 되물을 수 있다
            st.executeUpdate("INSERT INTO events(day, type, actor_type, actor_id, data_json, created_at) "
                    + "VALUES(3, '초혈', '캐릭터', '2', '{}', '2026-07-10 17:30:39')");
            st.executeUpdate("INSERT INTO events(day, type, actor_type, actor_id, data_json) "
                    + "VALUES(4, '기현', '캐릭터', '2', '{}')");
            st.executeUpdate("INSERT INTO rumors(content_json, strength, accuracy, network, region, born_day) "
                    + "VALUES('{\"말\":\"후손이 강하다더라\"}', 60, 80, '저잣거리', 'cheongha_hyeon', 4)");
            st.executeUpdate("INSERT INTO npcs(id, region, tier, updated_day) VALUES('객잔주인', 'cheongha_hyeon', 1, 1)");
            st.executeUpdate("INSERT INTO faction_standing(faction_id, character_id, favor) VALUES('hwasan', 2, 7)");
            st.executeUpdate("INSERT INTO mvt_link(mc_uuid, mc_name, character_id, linked_day) "
                    + "VALUES('00000000-0000-0000-0000-000000000001', 'Lindydone', 2, 6)");
            st.executeUpdate("INSERT INTO scenes(channel, opened_day) VALUES('객잔', 5)");
            st.executeUpdate("INSERT INTO blood_debt(subject, character_id, hidden, kills, updated_day) "
                    + "VALUES('캐릭터:2', 2, 1.5, 1, 6)");
        }
    }

    private static void poison(Path sqlite, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sqlite);
             Statement st = c.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    // ── 보조 ─────────────────────────────────────────────────────────────────

    private static Map<String, String> env(String url, String user, String password) {
        return Map.of("HONCHEON_DB_BACKEND", "postgresql",
                "HONCHEON_DATABASE_URL", url,
                "HONCHEON_DATABASE_USER", user,
                "HONCHEON_DATABASE_PASSWORD", password,
                "HONCHEON_SCHEMA", "db/postgresql/schema.sql");
    }

    private static void recreate(String adminUrl, String user, String password, String... names) throws Exception {
        try (Connection admin = connect(adminUrl, user, password);
             Statement st = admin.createStatement()) {
            for (String name : names) {
                st.executeUpdate("DROP DATABASE IF EXISTS " + name);
                st.executeUpdate("CREATE DATABASE " + name);
            }
        }
    }

    private static void drop(String adminUrl, String user, String password, String... names) {
        try (Connection admin = connect(adminUrl, user, password);
             Statement st = admin.createStatement()) {
            for (String name : names) {
                st.executeUpdate("DROP DATABASE IF EXISTS " + name);
            }
        } catch (Exception cleanup) {
            System.err.println("정리 실패 (수동으로 지우라): " + cleanup.getMessage());
        }
    }

    private static Connection roSqlite(Path sqlite) throws Exception {
        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
        config.setReadOnly(true);
        return DriverManager.getConnection("jdbc:sqlite:" + sqlite, config.toProperties());
    }

    private static Connection connect(String url, String user, String password) throws Exception {
        Class.forName("org.postgresql.Driver");
        java.util.Properties props = new java.util.Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        return DriverManager.getConnection(url, props);
    }

    private static boolean hasTable(Connection c, String table) throws Exception {
        try (var ps = c.prepareStatement("SELECT 1 FROM information_schema.tables "
                + "WHERE table_schema = current_schema() AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static long count(Connection c, String table) throws Exception {
        return scalar(c, "SELECT COUNT(*) FROM \"" + table + "\"");
    }

    private static long scalar(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String meta(Connection c, String key) throws Exception {
        try (var ps = c.prepareStatement("SELECT value FROM world_meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static byte[] sha256(Path file) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
    }

    private static void eye(String name, boolean ok) {
        if (!ok) {
            throw new AssertionError("✘ " + name);
        }
        eyes++;
        System.out.println("  ✔ " + name);
    }
}
