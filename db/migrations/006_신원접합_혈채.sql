-- 006 신원 접합(身元接合) + 혈채(血債) — 다리를 건너는 것은 사건이 아니라 **사람**이다.
--
-- 005 가 다리를 놨다. 그러나 mvt_link.character_id 를 채우는 명령이 없었다 —
-- 그래서 마크에서 사람을 죽여도 소문에 주체가 붙지 않았고, 어느 세력도 그를 보지 않았다.
-- 세계의 절반이 남의 일이었다.
--
-- 규칙: config/world_bridge.yml identity · config/faction_reaction.yml blood_debt
-- 설계: docs/design/world_bridge.md 5장 · docs/design/blood_debt.md

-- ① 접합 코드 — 마크가 내고, 디스코드가 확정한다 (mvt_issues_discord_confirms).
--    ★ 최종 결속을 디스코드에서 하는 이유: 캐릭터를 훔치려면 그 사람의 디스코드 계정이 필요해진다.
--    코드가 새어 나가도 도둑이 할 수 있는 최악은 '제 캐릭터에 남의 몸을 붙이는 것' — 자해다.
CREATE TABLE IF NOT EXISTS mvt_link_code (
    code         TEXT PRIMARY KEY,                    -- 6자 (0·O·1·I 없는 알파벳 — 옮겨 적는 값이므로)
    mc_uuid      TEXT NOT NULL,                       -- 코드를 낸 몸
    mc_name      TEXT NOT NULL,
    issued_at    INTEGER NOT NULL,                    -- epoch millis (MVT 봉투의 at)
    expires_at   INTEGER NOT NULL,                    -- issued_at + ttl_seconds (기본 10분)
    state        TEXT NOT NULL DEFAULT '대기',        -- 대기 | 사용됨 | 폐기
    used_by      INTEGER REFERENCES characters(id),   -- 확정한 캐릭터
    used_day     INTEGER
);
CREATE INDEX IF NOT EXISTS idx_mvt_link_code_body ON mvt_link_code(mc_uuid, state);

-- ② 혈채 — ★ 이 세계에서 감쇠하지 않는 유일한 값 (암혈채).
--    subject 는 'character:<id>' (이어진 자) · 'mc:<uuid>' (아직 안 이어진 몸) · '미상의_살인마' (몸도 모를 때).
--    ★ 접합의 순간 mc:<uuid> 원장이 character:<id> 로 **병합된다** —
--      그 전까지 세계는 열 개의 사고를 보았고, 그 후로 세계는 한 마리의 짐승을 본다
--      (blood_debt.target_tracking.merge).
CREATE TABLE IF NOT EXISTS blood_debt (
    subject       TEXT PRIMARY KEY,
    character_id  INTEGER REFERENCES characters(id),  -- NULL = 아직 이름이 없는 장부
    hidden        REAL NOT NULL DEFAULT 0,            -- 암혈채 — ★ 감쇠 없음. 몸이 안다
    known_raw     REAL NOT NULL DEFAULT 0,            -- 현혈채 (감쇠 전 원값 — 정산은 읽는 순간)
    known_day     INTEGER NOT NULL DEFAULT 0,         -- 현혈채 마지막 갱신일 (30일 -1 의 기준)
    public_count  INTEGER NOT NULL DEFAULT 0,         -- 공개(witness 2) 건수 → 감쇠 하한 = ×2
    kills         INTEGER NOT NULL DEFAULT 0,         -- 무고 살해 건수 (감사용)
    exposure_floor REAL NOT NULL DEFAULT 0,           -- ★ B6 — 마공 운기를 목격당한 몸에는 '은밀'이 없다 (1.0)
    updated_day   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_blood_debt_char ON blood_debt(character_id);
