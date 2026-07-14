package com.honcheon.bot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** PG-004 포트 계약을 빈 PostgreSQL 16 데이터베이스에서 실행한다. */
public final class PostgresqlContractSelfTest {
    private static int eyes;

    private PostgresqlContractSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("사용법: <jdbc-url> <user> <password>");
        }
        Path home = Files.createTempDirectory("honcheon-postgresql-contract");
        Map<String, String> environment = Map.of(
                "HONCHEON_DB_BACKEND", "postgresql",
                "HONCHEON_DATABASE_URL", args[0],
                "HONCHEON_DATABASE_USER", args[1],
                "HONCHEON_DATABASE_PASSWORD", args[2],
                "HONCHEON_SCHEMA", "db/postgresql/schema.sql");
        try (Db db = Db.open(environment)) {
            eye("통합 스키마 20개 표가 섰다", countTables(db) == 20);
            eye("세계일 기본값이 섰다", db.worldDay() == 1);

            long character = db.createCharacter("pg-user", "설화",
                    Map.of("근력", 3, "집안", "무가의_자식"), 12);
            eye("BIGSERIAL 캐릭터 키를 돌려준다", character > 0);
            eye("캐릭터 JSON과 전낭을 읽는다",
                    db.findCharacter("pg-user").map(row -> ((Number) row.get("wallet")).intValue() == 12)
                            .orElse(false));

            long house = db.createHouse("무가의_자식", "설가", "cheongha_hyeon", "흥", 1);
            db.setHouse(character, house);
            eye("가문 생성 키와 캐릭터 FK가 이어진다",
                    house > 0 && Long.valueOf(house).equals(db.houseOfCharacter(character)));

            db.inTransaction(() -> {
                db.setMeta("pg:커밋", "남음");
                return null;
            });
            eye("성공한 업무가 커밋된다", db.getMeta("pg:커밋").isPresent());

            try {
                db.inTransaction(() -> {
                    db.setMeta("pg:롤백", "사라짐");
                    throw new IllegalStateException("일부러 실패");
                });
            } catch (IllegalStateException expected) {
                eye("실패한 업무가 롤백된다", db.getMeta("pg:롤백").isEmpty());
            }

            boolean first = db.applyBridgeEvent("pg-event", "contract", "pg:cursor", "1:1", () ->
                    db.setMeta("pg:world", "한번"));
            boolean duplicate = db.applyBridgeEvent("pg-event", "contract", "pg:cursor", "1:2", () ->
                    db.setMeta("pg:world", "두번"));
            eye("브리지 멱등 insert가 첫 사건만 적용한다",
                    first && !duplicate && "한번".equals(db.getMeta("pg:world").orElse(null)));
            eye("중복 사건도 커서는 전진한다", "1:2".equals(db.getMeta("pg:cursor").orElse(null)));

            Path snapshot = home.resolve(db.snapshotFileName());
            db.snapshotTo(snapshot);
            eye("반복 읽기 논리 스냅숏을 만든다",
                    Files.isRegularFile(snapshot) && Files.size(snapshot) > 0);
        }
        System.out.println("✔ PostgreSQL 계약 눈 " + eyes + "개 — 전부 통과");
    }

    private static int countTables(Db db) throws Exception {
        String[] names = {"houses", "characters", "character_bank", "world_meta", "regions",
                "npcs", "rumors", "faction_standing", "myeongbun", "sect_state",
                "authority_mandate", "price_events", "registry", "events", "scenes",
                "mvt_link", "bridge_inbox", "mvt_link_code", "mvt_link_request", "blood_debt"};
        int count = 0;
        for (String name : names) {
            if (db.hasTable(name)) {
                count++;
            }
        }
        return count;
    }

    private static void eye(String name, boolean passed) {
        if (!passed) {
            throw new AssertionError(name);
        }
        eyes++;
        System.out.println("  ✔ " + name);
    }
}
