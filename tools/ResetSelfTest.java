package com.honcheon.bot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>초기화의 눈</b> — 되돌리는 명령이 <b>세계를 지우지 않는가</b>. 그리고 <b>지우면 안 될 때 멈추는가</b>.
 *
 * <p><b>눈을 만들면 눈을 시험하라.</b> 그래서 이 시험은 착한 길만 걷지 않는다 — <b>일부러 규칙을 어긴다</b>:
 * 등록부에 세계의 표(events)를 적어 넣어 보고, 백업이 실패하도록 만들어 보고, 있어야 할 표를 없애 본다.
 * 그러고도 세계가 살아 있고 한 행도 안 지워졌으면, 그때 눈이 있는 것이다.
 *
 * <p><b>봇을 켜지 않고 돈다.</b> {@link Reset} 은 JDA 를 모른다 — SQLite 와 yml 뿐이다.
 *
 * <h2>어떻게 돌리나 (저장소 루트에서)</h2>
 * <pre>
 *   export JAVA_HOME=$PWD/run/jdk-21
 *   ./gradlew :server-bot:compileJava
 *   CP="server-bot/build/classes/java/main:core/build/classes/java/main:domain/build/classes/java/main"
 *   CP="$CP:$(find ~/.gradle -name 'sqlite-jdbc-*.jar'      | head -1)"
 *   CP="$CP:$(find ~/.gradle -name 'jackson-databind-*.jar' | head -1)"
 *   CP="$CP:$(find ~/.gradle -name 'jackson-core-*.jar'     | head -1)"
 *   CP="$CP:$(find ~/.gradle -name 'jackson-annotations-*.jar' | head -1)"
 *   CP="$CP:$(find ~/.gradle -name 'snakeyaml-2.2.jar'      | head -1)"
 *   $JAVA_HOME/bin/java -cp "$CP" tools/ResetSelfTest.java
 * </pre>
 */
public final class ResetSelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("honcheon-reset-eye");
        System.out.println("초기화의 눈 — " + tmp + "\n");

        eyeWorldSurvives(tmp);
        eyeProtectedTableRefused(tmp);
        eyeBackupFailureAborts(tmp);
        eyeMissingTableTolerated(tmp);
        eyeLinkScopeKeepsCharacter(tmp);
        eyeBridgeFromMvt(tmp);

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("✔ 눈 " + passed + "개 — 전부 통과");
        } else {
            System.out.println("✖ 실패 " + failures.size() + " / 통과 " + passed);
            failures.forEach(f -> System.out.println("  ✖ " + f));
            System.exit(1);
        }
    }

    // ══════════ 눈 ① 세계는 살아남는가 · 캐릭터는 사라지는가 ══════════

    private static void eyeWorldSurvives(Path tmp) throws Exception {
        Path home = fresh(tmp, "world-survives");
        Db db = openSeeded(home);
        Reset reset = new Reset(db, home.resolve("config"));
        check("등록부가 성하다", !reset.locked(), String.valueOf(reset.fault()));

        Reset.Report r = reset.reset("캐릭터", "111", null, "눈", null);

        // 캐릭터의 것 — 전부 사라졌는가 (죽은 캐릭터도)
        check("내 캐릭터가 지워졌다 (산 것·죽은 것 둘 다)",
                !exists(db, "SELECT 1 FROM characters WHERE discord_id='111'"), rows(db, "characters"));
        // ★★ 남의 것은 못 지운다 — 이것이 규약 3이다
        check("★★ 남의 캐릭터는 그대로다",
                exists(db, "SELECT 1 FROM characters WHERE discord_id='999'"), "남의 것을 지웠다");
        check("character_bank 가 지워졌다", count(db, "character_bank") == 0, "");
        check("blood_debt 가 지워졌다", count(db, "blood_debt") == 0, "");
        check("faction_standing 이 지워졌다", count(db, "faction_standing") == 0, "");
        check("mvt_link 이 지워졌다", count(db, "mvt_link") == 0, "");

        // ★★ 세계 — 한 행도 안 줄었는가
        check("★ regions 가 그대로다", count(db, "regions") == 1, "");
        check("★ npcs 가 그대로다", count(db, "npcs") == 2, "");
        check("★ rumors 가 그대로다", count(db, "rumors") == 3, "");
        check("★ world_meta 가 그대로다", count(db, "world_meta") >= 1, "");
        // events 는 append-only — 초기화 **사건 자체**가 한 줄 늘어난다 (줄지 않는다)
        check("★ events 가 안 줄었다 (초기화 기록이 늘 뿐)", count(db, "events") >= 2, "");

        // 등록부 — 이 캐릭터가 잡아 둔 기연만 풀리고, 세계의 것은 그대로
        check("registry — 내 것은 풀렸다", !exists(db, "SELECT 1 FROM registry WHERE owner_id='1'"), "");
        check("★ registry — 세계의 것은 그대로다",
                exists(db, "SELECT 1 FROM registry WHERE owner_type='world'"), "");

        // 백업 — 실제로 떠졌는가
        Path snap = Path.of(r.backup()).resolve("honcheon.db");
        check("백업 파일이 떠졌다", Files.isRegularFile(snap) && Files.size(snap) > 0, r.backup());
        check("무엇을 지웠는지 적혔다",
                Files.isRegularFile(Path.of(r.backup()).resolve("deleted.json")), "");
        // 백업이 **초기화 전**의 세계인가 — 캐릭터가 그 안에 살아 있어야 되돌릴 수 있다
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + snap);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM characters")) {
            rs.next();
            check("백업 안에 지운 캐릭터가 살아 있다 (되돌릴 수 있다)", rs.getInt(1) == 3,
                    "백업 characters = " + rs.getInt(1));
        }
        db.close();
    }

    // ══════════ 눈 ② 【일부러 어긴다】 등록부가 세계를 지우라고 하면 ══════════

    private static void eyeProtectedTableRefused(Path tmp) throws Exception {
        Path home = fresh(tmp, "protected");
        // ★ 규약을 **일부러 어긴다** — 캐릭터 범위에 세계의 표(events·rumors)를 끼워 넣는다
        Path cfg = home.resolve("config/reset.yml");
        String doctored = Files.readString(cfg)
                .replace("blood_debt, registry, characters]", "blood_debt, registry, characters, events, rumors]");
        Files.writeString(cfg, doctored);

        Db db = openSeeded(home);
        int rumorsBefore = count(db, "rumors");
        int eventsBefore = count(db, "events");

        Reset reset = new Reset(db, home.resolve("config"));
        check("★ 세계의 표를 적으면 초기화가 잠긴다", reset.locked(),
                "잠기지 않았다 — 세계를 지울 수 있는 문이 열려 있다");
        check("★ 왜 잠겼는지 말한다", reset.locked() && reset.fault().contains("events"),
                String.valueOf(reset.fault()));

        // 그래도 불러 본다 — 지워지면 안 된다
        boolean threw = false;
        try {
            reset.reset("캐릭터", "111", null, "눈", null);
        } catch (Exception expected) {
            threw = true;
        }
        check("★ 잠긴 채로 부르면 던진다", threw, "조용히 지나갔다");
        check("★★ rumors 가 한 행도 안 지워졌다", count(db, "rumors") == rumorsBefore, "");
        check("★★ events 가 한 행도 안 지워졌다", count(db, "events") == eventsBefore, "");
        check("★★ characters 도 안 지워졌다 (잠겼으므로 아무것도 안 한다)",
                count(db, "characters") == 3, "");
        db.close();
    }

    // ══════════ 눈 ③ 【일부러 어긴다】 백업이 실패하면 ══════════

    private static void eyeBackupFailureAborts(Path tmp) throws Exception {
        Path home = fresh(tmp, "backup-fails");
        // ★ 백업을 **일부러 못 뜨게** 한다 — 쓸 수 없는 곳을 가리킨다
        Path cfg = home.resolve("config/reset.yml");
        Files.writeString(cfg, Files.readString(cfg)
                .replace("dir: " + home.resolve("backup"), "dir: /proc/못쓰는곳/backup"));

        Db db = openSeeded(home);
        Reset reset = new Reset(db, home.resolve("config"));
        check("등록부는 성하다 (백업 경로만 나쁘다)", !reset.locked(), String.valueOf(reset.fault()));

        boolean threw = false;
        try {
            reset.reset("캐릭터", "111", null, "눈", null);
        } catch (Exception expected) {
            threw = true;
        }
        check("★ 백업이 실패하면 던진다", threw, "백업 없이 지나갔다");
        // ★★ 이것이 이 시험의 심장이다 — 백업이 없으면 **한 행도** 안 지워져야 한다
        check("★★ 백업 실패 — characters 가 그대로다", count(db, "characters") == 3,
                "백업도 없이 캐릭터를 지웠다");
        check("★★ 백업 실패 — mvt_link 가 그대로다", count(db, "mvt_link") == 1, "");
        check("★★ 백업 실패 — blood_debt 가 그대로다", count(db, "blood_debt") == 1, "");
        db.close();
    }

    // ══════════ 눈 ④ 없는 표는 없는 대로 (mvt_link_code 는 곧 사라진다) ══════════

    private static void eyeMissingTableTolerated(Path tmp) throws Exception {
        Path home = fresh(tmp, "missing-table");
        Db db = openSeeded(home);
        // ★ 접합 담당이 코드 방식을 폐기하는 중이다 — 표를 **미리 없애 본다**
        exec(db, "DROP TABLE mvt_link_code");
        exec(db, "DROP TABLE IF EXISTS mvt_link_request");

        Reset reset = new Reset(db, home.resolve("config"));
        Reset.Report r = reset.reset("접합", "111", null, "눈", null);
        check("★ 없는 표가 있어도 돈다", true, "");
        check("★ 없다고 말해 준다 (조용히 넘어가지 않는다)",
                r.notes().stream().anyMatch(n -> n.contains("mvt_link_code")), r.notes().toString());
        check("접합은 끊겼다", count(db, "mvt_link") == 0, "");
        db.close();
    }

    // ══════════ 눈 ⑤ 접합만 끊으면 캐릭터·혈채는 남는가 ══════════

    private static void eyeLinkScopeKeepsCharacter(Path tmp) throws Exception {
        Path home = fresh(tmp, "link-only");
        Db db = openSeeded(home);
        Reset reset = new Reset(db, home.resolve("config"));
        reset.reset("접합", "111", null, "눈", null);

        check("접합이 끊겼다", count(db, "mvt_link") == 0, "");
        check("★ 캐릭터는 남았다", count(db, "characters") == 3, "");
        check("★ 혈채는 남았다 (빚은 몸을 바꾼다고 없어지지 않는다)", count(db, "blood_debt") == 1, "");
        check("★ 전낭(전장)도 남았다", count(db, "character_bank") == 1, "");
        db.close();
    }

    // ══════════ 눈 ⑥ 다리 — 마크에서 친 초기화가 봇의 장부에 닿는가 ══════════
    //
    // ★ 여기 쓰는 JSON 은 **MVT 가 실제로 쓰는 그 문자열**이다 (Reset.json() 의 출력 형식).
    //   두 몸이 같은 말을 쓰는지 시험하는 자리다 — 형식이 어긋나면 마크의 초기화가 장부에 안 닿는다.

    private static void eyeBridgeFromMvt(Path tmp) throws Exception {
        Path home = fresh(tmp, "bridge");
        Db db = openSeeded(home);
        Reset reset = new Reset(db, home.resolve("config"));
        Path lane = home.resolve("bridge");
        Files.createDirectories(lane);

        String uuid = "05909c69-0126-490a-9448-649a19702637";
        String req = "{\"id\":\"e1\",\"at\":" + System.currentTimeMillis()
                + ",\"from\":\"mvt\",\"scope\":\"전부\",\"mc_uuid\":\"" + uuid
                + "\",\"mc_name\":\"Lindydone\",\"actor\":\"mc:Lindydone\",\"backup\":\"20260713-000000\"}";
        Files.writeString(lane.resolve("1-e1.json"), req, StandardCharsets.UTF_8);

        // 봇이 쓴 청은 **건드리면 안 된다** (마크가 가져갈 것이다)
        Files.writeString(lane.resolve("2-mine.json"),
                "{\"id\":\"m1\",\"at\":" + System.currentTimeMillis() + ",\"from\":\"bot\","
                        + "\"scope\":\"전부\",\"mc_uuid\":\"" + uuid + "\"}", StandardCharsets.UTF_8);

        reset.start();
        for (int i = 0; i < 40 && Files.exists(lane.resolve("1-e1.json")); i++) {
            Thread.sleep(250);
        }
        reset.stop();

        check("★ 마크의 청이 봇의 장부에 닿았다 (캐릭터가 지워졌다)",
                !exists(db, "SELECT 1 FROM characters WHERE discord_id='111'"),
                "마크에서 친 초기화가 장부에 안 닿았다 — 형식이 어긋났다");
        check("★ 처리한 청은 done/ 으로 치웠다 (두 번 안 지운다)",
                Files.exists(lane.resolve("done/1-e1.json")), "");
        check("★★ 봇이 쓴 청은 그대로 뒀다 (마크가 가져갈 것이다)",
                Files.exists(lane.resolve("2-mine.json")), "제 청을 제가 먹었다");
        // ★★ 메아리 금지 — 마크가 청한 것을 봇이 **되받아 청하면** 그 사람이 두 번 쫓겨나고
        //    백업이 두 번 뜬다. 남아 있어야 할 파일은 처음에 넣어 둔 '2-mine.json' 하나뿐이다.
        try (var s = Files.list(lane)) {
            long pending = s.filter(p -> p.getFileName().toString().endsWith(".json")).count();
            check("★★ 마크의 청에 되받아 청하지 않았다 (메아리 없음)", pending == 1,
                    "다리에 " + pending + "장이 남았다 — 봇이 마크에게 되물었다");
        }
        check("★★ 세계는 여전히 그대로다", count(db, "rumors") == 3 && count(db, "npcs") == 2, "");
        check("★★ 남의 캐릭터는 그대로다",
                exists(db, "SELECT 1 FROM characters WHERE discord_id='999'"), "");
        db.close();
    }

    // ══════════ 자리 깔기 ══════════

    /** 저장소의 진짜 스키마·진짜 등록부로 시험한다 — 시험용 사본을 지어내지 않는다 */
    private static Path fresh(Path tmp, String name) throws Exception {
        Path home = tmp.resolve(name);
        Files.createDirectories(home.resolve("config"));
        Files.createDirectories(home.resolve("db"));
        Files.copy(Path.of("db/schema.sql"), home.resolve("db/schema.sql"));
        // 등록부는 **저장소의 것 그대로** — 다만 백업·다리는 임시 폴더로 돌린다
        String cfg = Files.readString(Path.of("config/reset.yml"), StandardCharsets.UTF_8)
                .replace("dir: run/backup", "dir: " + home.resolve("backup"))
                .replace("dir: run/bridge/reset", "dir: " + home.resolve("bridge"));
        Files.writeString(home.resolve("config/reset.yml"), cfg, StandardCharsets.UTF_8);
        return home;
    }

    private static Db openSeeded(Path home) throws Exception {
        Db db = new Db(home.resolve("honcheon.db"), home.resolve("db/schema.sql"));
        // 사람 — 산 캐릭터 하나, 죽은 캐릭터 하나 (죽은 것도 지워져야 혈연이 안 이어진다)
        exec(db, "INSERT INTO characters(id, discord_id, name, status, realm, sheet_json, created_day) "
                + "VALUES(1, '111', '무명', '활성', '범인', '{}', 1)");
        exec(db, "INSERT INTO characters(id, discord_id, name, status, realm, sheet_json, created_day, died_day) "
                + "VALUES(2, '111', '옛몸', '사망', '범인', '{}', 1, 2)");
        // ★ 남의 캐릭터 — 절대 안 지워져야 한다 (지워지면 눈 ①의 count 가 0 이 아니다)
        exec(db, "INSERT INTO characters(id, discord_id, name, status, realm, sheet_json, created_day) "
                + "VALUES(9, '999', '남', '활성', '범인', '{}', 1)");
        exec(db, "INSERT INTO character_bank(character_id, branch, balance) VALUES(1, '청하', 500)");
        exec(db, "INSERT INTO blood_debt(subject, character_id, hidden, updated_day) "
                + "VALUES('character:1', 1, 3.0, 1)");
        exec(db, "INSERT INTO faction_standing(faction_id, character_id, attention, favor) "
                + "VALUES('hwasan', 1, 5, 2)");
        exec(db, "INSERT INTO mvt_link(mc_uuid, mc_name, character_id, linked_day) "
                + "VALUES('05909c69-0126-490a-9448-649a19702637', 'Lindydone', 1, 1)");
        exec(db, "INSERT INTO mvt_link_code(code, mc_uuid, mc_name, issued_at, expires_at) "
                + "VALUES('ABC123', '05909c69-0126-490a-9448-649a19702637', 'Lindydone', 1, 2)");
        // 등록부 — 내 기연 하나, 세계의 것 하나
        exec(db, "INSERT INTO registry(kind, key, owner_type, owner_id, claimed_day) "
                + "VALUES('기연', '취걸개', 'character', '1', 1)");
        exec(db, "INSERT INTO registry(kind, key, owner_type, owner_id, claimed_day) "
                + "VALUES('세계급_사건', '개막', 'world', 'world', 1)");
        // ★★ 세계 — 이것들이 살아남아야 한다
        exec(db, "INSERT INTO npcs(id, region, tier, updated_day) VALUES('한백', 'cheongha_hyeon', 1, 1)");
        exec(db, "INSERT INTO npcs(id, region, tier, updated_day) VALUES('곽진', 'cheongha_hyeon', 2, 1)");
        for (int i = 1; i <= 3; i++) {
            exec(db, "INSERT INTO rumors(content_json, strength, accuracy, network, region, born_day) "
                    + "VALUES('{}', 2, 80, '저잣거리', 'cheongha_hyeon', 1)");
        }
        exec(db, "INSERT INTO events(day, type, actor_type, actor_id) VALUES(1, '등록', 'world', 'seed')");
        exec(db, "INSERT INTO events(day, type, actor_type, actor_id) VALUES(1, '판정', 'character', '1')");
        return db;
    }

    // ══════════ 잡동사니 ══════════

    private static void exec(Db db, String sql) throws Exception {
        java.lang.reflect.Field f = Db.class.getDeclaredField("conn");
        f.setAccessible(true);
        try (Statement st = ((Connection) f.get(db)).createStatement()) {
            st.execute(sql);
        }
    }

    private static int count(Db db, String table) throws Exception {
        java.lang.reflect.Field f = Db.class.getDeclaredField("conn");
        f.setAccessible(true);
        try (Statement st = ((Connection) f.get(db)).createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM \"" + table + "\"")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static boolean exists(Db db, String sql) throws Exception {
        java.lang.reflect.Field f = Db.class.getDeclaredField("conn");
        f.setAccessible(true);
        try (Statement st = ((Connection) f.get(db)).createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            return rs.next();
        }
    }

    private static String rows(Db db, String table) throws Exception {
        return table + " = " + count(db, table) + "행";
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ✔ " + what);
        } else {
            failures.add(what + (detail.isBlank() ? "" : " — " + detail));
            System.out.println("  ✖ " + what + (detail.isBlank() ? "" : " — " + detail));
        }
    }
}
