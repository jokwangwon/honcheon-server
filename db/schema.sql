-- 혼천 영속화 스키마 v1 — SQLite (WAL, 단일 작성자 = 봇 프로세스)
-- 기준 문서: docs/design/persistence.md
-- 원칙: 상태 테이블(조회) + 이벤트 로그(진실) 이중 기록. JSON 컬럼은 v1 단순화.

PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

-- ─── 캐릭터군 ───
CREATE TABLE IF NOT EXISTS characters (
    id            INTEGER PRIMARY KEY,
    discord_id    TEXT NOT NULL,              -- 계정 (캐릭터 귀속 원칙 — 계정당 활성 1)
    name          TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT '서장',  -- 서장/활성/사망
    realm         TEXT NOT NULL DEFAULT '범인',
    location      TEXT,                        -- 지역·장소 키 (조회 축)
    sheet_json    TEXT NOT NULL,               -- 능력치·기술·경지·성향 (화후 실수 포함)
    ledger_json   TEXT NOT NULL DEFAULT '{}',  -- 화후 원장 (기술별 일치·일일 적립·반복)
    wallet        INTEGER NOT NULL DEFAULT 0,  -- 전낭 (문)
    marks_json    TEXT NOT NULL DEFAULT '{}',  -- 실전·사선 마크
    created_day   INTEGER NOT NULL,            -- 세계 달력일
    died_day      INTEGER,
    lineage_of    INTEGER REFERENCES characters(id)  -- 혈연 시작 (죽음 규칙)
);
CREATE INDEX IF NOT EXISTS idx_characters_account ON characters(discord_id, status);
CREATE INDEX IF NOT EXISTS idx_characters_location ON characters(location) WHERE status = '활성';

CREATE TABLE IF NOT EXISTS character_bank (      -- 전장 예치 (상속 대상)
    character_id  INTEGER NOT NULL REFERENCES characters(id),
    branch        TEXT NOT NULL,                 -- 지점 (현급 도시 키)
    balance       INTEGER NOT NULL DEFAULT 0,
    heir_hint     TEXT,                          -- 생전 지정 상속인 (죽음 규칙)
    PRIMARY KEY (character_id, branch)
);

-- ─── 세계군 ───
CREATE TABLE IF NOT EXISTS world_meta (          -- 세계 달력·시즌 (단일 행 키밸류)
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS regions (
    id          TEXT PRIMARY KEY,                -- cheongha_hyeon …
    security    INTEGER NOT NULL DEFAULT 50,     -- 치안 (region_state 0~100)
    economy     INTEGER NOT NULL DEFAULT 50,
    sentiment   INTEGER NOT NULL DEFAULT 50,     -- 민심
    state_json  TEXT NOT NULL DEFAULT '{}',
    updated_day INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS npcs (
    id          TEXT PRIMARY KEY,
    region      TEXT REFERENCES regions(id),
    tier        INTEGER NOT NULL,
    status      TEXT NOT NULL DEFAULT '활성',    -- 활성/동면(배경화)/사망
    state_json  TEXT NOT NULL DEFAULT '{}',      -- 스케줄 상태·lifepath 궤적·knowledge
    updated_day INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS rumors (
    id           INTEGER PRIMARY KEY,
    content_json TEXT NOT NULL,                  -- 내용·주체·사건 링크
    strength     INTEGER NOT NULL,               -- 강도 1~3
    accuracy     INTEGER NOT NULL,               -- 정확도 (왜곡 반영)
    network      TEXT NOT NULL,                  -- 6망 채널 키
    region       TEXT REFERENCES regions(id),
    born_day     INTEGER NOT NULL,
    state        TEXT NOT NULL DEFAULT '전파중'  -- 전파중/감쇠/소멸
);
CREATE INDEX IF NOT EXISTS idx_rumors_live ON rumors(region, state);

CREATE TABLE IF NOT EXISTS faction_standing (    -- 세력 × 캐릭터 2축
    faction_id   TEXT NOT NULL,
    character_id INTEGER NOT NULL REFERENCES characters(id),
    attention    INTEGER NOT NULL DEFAULT 0,     -- 주목 0~30
    favor        INTEGER NOT NULL DEFAULT 0,     -- 우호 (안면4/신용8/공신13/은인19)
    PRIMARY KEY (faction_id, character_id)
);

CREATE TABLE IF NOT EXISTS price_events (
    id         INTEGER PRIMARY KEY,
    item       TEXT NOT NULL,
    delta      REAL NOT NULL,
    born_day   INTEGER NOT NULL,
    decay_days INTEGER NOT NULL
);

-- 세계 유일 등록제의 실체 — UNIQUE 제약이 '세계에 하나' 규칙의 집행자 (등록 경합 = 선착순)
CREATE TABLE IF NOT EXISTS registry (
    id         INTEGER PRIMARY KEY,
    kind       TEXT NOT NULL,                    -- 기연/신병/명명_오의/명명_명병/별호/세계급_사건/마병
    key        TEXT NOT NULL,                    -- 고유 명칭·기연 ID
    owner_type TEXT,                             -- character/npc/world
    owner_id   TEXT,
    data_json  TEXT NOT NULL DEFAULT '{}',
    claimed_day INTEGER NOT NULL,
    UNIQUE (kind, key)
);

-- ─── 기록군 ───
CREATE TABLE IF NOT EXISTS events (              -- append-only 진실의 원장
    id          INTEGER PRIMARY KEY,
    day         INTEGER NOT NULL,                -- 세계 달력일
    type        TEXT NOT NULL,                   -- 판정/거래/마크/개입/사망/승급/등록 …
    actor_type  TEXT NOT NULL,                   -- character/npc/world
    actor_id    TEXT NOT NULL,
    target_type TEXT,
    target_id   TEXT,
    data_json   TEXT NOT NULL DEFAULT '{}',
    created_at  TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_events_actor ON events(actor_type, actor_id, day);
CREATE INDEX IF NOT EXISTS idx_events_type ON events(type, day);

CREATE TABLE IF NOT EXISTS scenes (              -- 장면 = 동시성 잠금 단위 (G8 저장소)
    id           INTEGER PRIMARY KEY,
    channel      TEXT NOT NULL,                  -- 디스코드 채널 (지역·장소)
    thread       TEXT,                           -- 스레드 ID
    state        TEXT NOT NULL DEFAULT '진행',   -- 진행/종결
    participants TEXT NOT NULL DEFAULT '[]',     -- 캐릭터 ID 배열 (JSON)
    opened_day   INTEGER NOT NULL,
    closed_day   INTEGER
);
CREATE INDEX IF NOT EXISTS idx_scenes_open ON scenes(channel, state);
