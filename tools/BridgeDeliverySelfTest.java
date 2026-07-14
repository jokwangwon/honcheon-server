package com.honcheon.bot;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 실제 JSONL과 SQLite로 브리지 실패 재시도, 원자성, 중복 억제를 검증한다. */
public final class BridgeDeliverySelfTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static int passed;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("honcheon-bridge-delivery");
        Path dbPath = tmp.resolve("honcheon.db");
        Path segment = tmp.resolve("2026-07-14.jsonl");
        String line = "{\"id\":\"event-1\",\"kind\":\"test\",\"data\":{}}\n";
        Files.writeString(segment, line, StandardCharsets.UTF_8);

        try (Db db = new Db(dbPath, Path.of("db/schema.sql"))) {
            AtomicBoolean failFirst = new AtomicBoolean(true);
            AtomicInteger workCalls = new AtomicInteger();

            boolean failed = false;
            try {
                deliver(db, segment, failFirst, workCalls);
            } catch (IOException expected) {
                failed = true;
            }
            check("처리기 실패가 수신 루프 밖으로 전달된다", failed);
            check("실패하면 커서가 전진하지 않는다", db.getMeta("test:cursor").isEmpty());
            check("실패하면 inbox 선점이 롤백된다", count(dbPath, "bridge_inbox") == 0);
            check("실패하면 부분 세계 변경이 롤백된다", countType(dbPath, "bridge_test") == 0);

            check("다음 전달이 같은 줄을 다시 적용한다", deliver(db, segment, failFirst, workCalls));
            check("성공하면 inbox와 세계 변경이 함께 남는다",
                    count(dbPath, "bridge_inbox") == 1 && countType(dbPath, "bridge_test") == 1);
            check("성공하면 커서가 줄 끝에 선다",
                    db.getMeta("test:cursor").orElse("").equals(checkpoint(segment)));

            Files.writeString(segment, line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
            check("같은 id의 재전달은 세계를 다시 바꾸지 않는다",
                    !deliver(db, segment, failFirst, workCalls));
            check("중복 줄도 커서는 전진한다",
                    db.getMeta("test:cursor").orElse("").equals(checkpoint(segment)));
            check("중복 처리기는 실행되지 않는다",
                    workCalls.get() == 2 && countType(dbPath, "bridge_test") == 1);
        }

        System.out.println("✔ 브리지 전달 눈 " + passed + "개 — 전부 통과");
    }

    @SuppressWarnings("unchecked")
    private static boolean deliver(Db db, Path segment, AtomicBoolean failFirst,
                                   AtomicInteger workCalls) throws Exception {
        String json = Files.readString(segment, StandardCharsets.UTF_8).lines()
                .reduce((first, second) -> second).orElseThrow();
        Map<String, Object> event = JSON.readValue(json, Map.class);
        return db.applyBridgeEvent(String.valueOf(event.get("id")), String.valueOf(event.get("kind")),
                "test:cursor", checkpoint(segment), () -> {
                    workCalls.incrementAndGet();
                    db.logEvent("bridge_test", "world", "selftest", Map.of("id", event.get("id")));
                    if (failFirst.getAndSet(false)) {
                        throw new IOException("의도한 처리 실패");
                    }
                });
    }

    private static String checkpoint(Path segment) throws Exception {
        return segment.getFileName() + ":" + Files.size(segment);
    }

    private static int count(Path dbPath, String table) throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int countType(Path dbPath, String type) throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM events WHERE type = ?")) {
            ps.setString(1, type);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void check(String name, boolean ok) {
        if (!ok) {
            throw new AssertionError(name);
        }
        passed++;
        System.out.println("  ✔ " + name);
    }
}
