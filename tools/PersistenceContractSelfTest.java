package com.honcheon.bot;

import java.nio.file.Files;
import java.nio.file.Path;

/** PG-003 저장소 트랜잭션 계약을 실제 SQLite 구현에 대고 검증한다. */
public final class PersistenceContractSelfTest {
    private static int eyes;

    private PersistenceContractSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("honcheon-persistence-contract");
        try (Db db = new Db(home.resolve("contract.db"), Path.of("db/schema.sql"))) {
            db.inTransaction(() -> {
                db.setMeta("계약:커밋", "남음");
                return null;
            });
            eye("성공한 업무는 커밋된다", "남음".equals(db.getMeta("계약:커밋").orElse(null)));

            try {
                db.inTransaction(() -> {
                    db.setMeta("계약:롤백", "사라짐");
                    throw new IllegalStateException("일부러 실패");
                });
                throw new AssertionError("실패한 업무가 예외를 내지 않았다");
            } catch (IllegalStateException expected) {
                eye("실패한 업무는 롤백된다", db.getMeta("계약:롤백").isEmpty());
            }

            try {
                db.inTransaction(() -> db.inTransaction(() -> null));
                throw new AssertionError("중첩 트랜잭션이 열렸다");
            } catch (Exception expected) {
                eye("암묵 중첩 트랜잭션을 거부한다", true);
            }

            db.inTransaction(() -> {
                db.setMeta("계약:회복", "정상");
                return null;
            });
            eye("롤백 뒤 연결이 자동 커밋 상태로 회복된다",
                    "정상".equals(db.getMeta("계약:회복").orElse(null)));

            eye("방언 경계가 SQLite 표를 조회한다", db.hasTable("world_meta"));
            eye("없는 표는 없다고 판정한다", !db.hasTable("없는_표"));

            Path snapshot = home.resolve("snapshot.db");
            db.snapshotTo(snapshot);
            eye("방언 경계가 일관된 스냅숏을 만든다",
                    Files.isRegularFile(snapshot) && Files.size(snapshot) > 0);
        }
        System.out.println("✔ 영속화 계약 눈 " + eyes + "개 — 전부 통과");
    }

    private static void eye(String name, boolean passed) {
        if (!passed) {
            throw new AssertionError(name);
        }
        eyes++;
        System.out.println("  ✔ " + name);
    }
}
