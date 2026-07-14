package com.honcheon.bot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PG-006 충돌 시험 — <b>전역 직렬화 없이</b> 충돌이 판정되는가.
 *
 * <p>닫는 조건(설계 문서): "전역 직렬화 없이 충돌 시험 통과". 세 가지를 실제로 겪는다:
 * <ol>
 *   <li><b>전역 직렬화의 부재</b> — 서로 다른 뭉치의 두 트랜잭션이 <b>동시에 열려 있는다</b>.
 *       PG-005 까지의 {@code synchronized} 아래서는 이 시험이 교착으로 죽는다 —
 *       한쪽이 트랜잭션을 쥔 채 다른 쪽을 기다리는데, 다른 쪽은 자물쇠 앞에 서 있으니까.</li>
 *   <li><b>잃어버린 갱신 없음</b> — 여러 손이 같은 뭉치(sect_state 한 행)를 동시에 읽고-계산하고-쓴다.
 *       SERIALIZABLE 이 충돌(40001)로 순서를 판정하고, 진 손이 물러나 다시 잰다.
 *       끝에 합계가 정확히 맞아야 한다 — 하나라도 증발하면 직렬화가 거짓말한 것이다.</li>
 *   <li><b>동시 선점은 하나만</b> — 같은 사건을 두 손이 동시에 선점하면 처리기는 정확히 한 번 돈다.</li>
 * </ol>
 * SQLite 로도 같은 망치질을 한다 — 한 손(직렬)이므로 당연히 맞아야 하고, 맞는지 본다.
 *
 * <p>사용법: {@code PgConcurrencySelfTest <jdbc-url> <user> <password>}
 * (주어진 서버에 pgconcurrency_selftest 데이터베이스를 만들고 지운다)
 */
public final class PgConcurrencySelfTest {
    private static int eyes;

    private PgConcurrencySelfTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("사용법: <jdbc-url> <user> <password>");
        }
        String adminUrl = args[0];
        String user = args[1];
        String password = args[2];
        String url = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + "pgconcurrency_selftest";

        try (Connection admin = connect(adminUrl, user, password);
             Statement st = admin.createStatement()) {
            st.executeUpdate("DROP DATABASE IF EXISTS pgconcurrency_selftest");
            st.executeUpdate("CREATE DATABASE pgconcurrency_selftest");
        }
        try {
            try (Db db = Db.open(Map.of(
                    "HONCHEON_DB_BACKEND", "postgresql",
                    "HONCHEON_DATABASE_URL", url,
                    "HONCHEON_DATABASE_USER", user,
                    "HONCHEON_DATABASE_PASSWORD", password,
                    "HONCHEON_SCHEMA", "db/postgresql/schema.sql"))) {
                overlappingTransactions(db);
                hammerSameAggregate(db, "postgresql");
                concurrentClaim(db);
                System.out.println("  지표: " + db.storageStats());
                eye("풀이 실제로 여럿을 동시에 태웠다 (최고 동시 ≥ 2)",
                        db.storageStats().contains("풀")
                                && !db.storageStats().contains("최고 동시 0")
                                && !db.storageStats().contains("최고 동시 1 "));
            }

            // SQLite — 한 손(직렬)이므로 같은 망치질이 당연히 맞아야 한다. 당연한지 본다.
            Path home = Files.createTempDirectory("honcheon-concurrency");
            try (Db db = new Db(home.resolve("serial.db"), Path.of("db/schema.sql"))) {
                hammerSameAggregate(db, "sqlite");
            }
        } finally {
            try (Connection admin = connect(adminUrl, user, password);
                 Statement st = admin.createStatement()) {
                st.executeUpdate("DROP DATABASE IF EXISTS pgconcurrency_selftest");
            } catch (Exception cleanup) {
                System.err.println("정리 실패 (수동으로 지우라): " + cleanup.getMessage());
            }
        }
        System.out.println();
        System.out.println("✔ 충돌 눈 " + eyes + "개 — 전부 통과 (전역 직렬화 없이)");
    }

    /** ① 서로 다른 뭉치의 두 트랜잭션이 동시에 열려 있는다 — 전역 직렬화면 여기서 굳는다. */
    private static void overlappingTransactions(Db db) throws Exception {
        CyclicBarrier inside = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String key : List.of("동시시험:갑", "동시시험:을")) {
                futures.add(pool.submit(() -> {
                    db.inTransaction(() -> {
                        db.setMeta(key, "안");
                        // ★ 트랜잭션 **안**에서 서로를 기다린다. 상대도 트랜잭션 안이어야 풀린다 —
                        //   전역 자물쇠가 남아 있으면 상대는 문 앞에 서 있고, 이 기다림은 영원하다.
                        inside.await(20, TimeUnit.SECONDS);
                        return null;
                    });
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            eye("서로 다른 뭉치의 두 트랜잭션이 **동시에** 열려 있었다 (전역 직렬화 없음)", true);
            eye("둘 다 커밋됐다", "안".equals(db.getMeta("동시시험:갑").orElse(null))
                    && "안".equals(db.getMeta("동시시험:을").orElse(null)));
        } finally {
            pool.shutdownNow();
        }
    }

    /** ② 같은 뭉치를 여덟 손이 두드린다 — 끝에 셈이 정확해야 한다 (잃어버린 갱신 없음). */
    private static void hammerSameAggregate(Db db, String backend) throws Exception {
        int threads = 8;
        int perThread = 25;
        String faction = "충돌시험_" + backend;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger conflicts = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // addSectBurden = 읽고(지금) 계산하고(다음) 쓴다 — 겨룸의 표본.
                        // 도구 안의 재시도가 다 삼키는 게 정상이지만, 극단 경합이 넘치면
                        // 여기서 한 번 더 물러난다 (횟수를 세어 보고한다 — 침묵 금지).
                        for (int retry = 0; ; retry++) {
                            try {
                                db.addSectBurden(faction, 1, null, 1, 36500, 100_000);
                                break;
                            } catch (SQLException conflict) {
                                if (!isConflict(conflict) || retry >= 3) {
                                    throw conflict;
                                }
                                conflicts.incrementAndGet();
                                Thread.sleep(20L * (retry + 1));
                            }
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
            int expected = threads * perThread;
            int actual = db.sectBurden(faction, 1, 36500);
            eye(backend + ": " + threads + "손 × " + perThread + "회 증가 → 정확히 " + expected
                            + " (실측 " + actual + ", 시험 쪽 추가 재시도 " + conflicts.get() + "회)",
                    actual == expected);
        } finally {
            pool.shutdownNow();
        }
    }

    /** ③ 같은 사건을 두 손이 동시에 선점한다 — 처리기는 정확히 한 번 돈다. */
    private static void concurrentClaim(Db db) throws Exception {
        AtomicInteger applied = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int t = 0; t < 2; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    // applyBridgeEvent 는 스스로 재시도하지 않는다 (밖의 poll 루프가 그 몫이다) —
                    // 충돌이면 여기서 한 번 다시 부른다. 그것이 poll 의 재현이다.
                    for (int retry = 0; ; retry++) {
                        try {
                            return db.applyBridgeEvent("동시-사건-001", "충돌시험", "동시시험:커서",
                                    "줄:1", applied::incrementAndGet);
                        } catch (Exception conflict) {
                            if (!(conflict instanceof SQLException sql && isConflict(sql)) || retry >= 5) {
                                throw conflict;
                            }
                            Thread.sleep(20L * (retry + 1));
                        }
                    }
                }));
            }
            start.countDown();
            int claimed = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(30, TimeUnit.SECONDS)) {
                    claimed++;
                }
            }
            eye("같은 사건의 동시 선점 — 처리기는 정확히 한 번 돌았다 (선점 " + claimed + "번 · 실행 "
                    + applied.get() + "번)", applied.get() == 1 && claimed == 1);
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean isConflict(SQLException failure) {
        String state = failure.getSQLState();
        return "40001".equals(state) || "40P01".equals(state);
    }

    private static Connection connect(String url, String user, String password) throws Exception {
        Class.forName("org.postgresql.Driver");
        java.util.Properties props = new java.util.Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        return DriverManager.getConnection(url, props);
    }

    private static void eye(String name, boolean ok) {
        if (!ok) {
            throw new AssertionError("✘ " + name);
        }
        eyes++;
        System.out.println("  ✔ " + name);
    }
}
