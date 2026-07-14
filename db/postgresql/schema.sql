-- 혼천 PostgreSQL 통합 스키마 v8.
-- SQLite schema.sql과 002~008 마이그레이션의 최종 상태를 새 설치용으로 합친다.

CREATE TABLE IF NOT EXISTS houses (
    id BIGSERIAL PRIMARY KEY,
    family TEXT NOT NULL,
    name TEXT,
    region TEXT,
    state TEXT,
    created_day INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_houses_family ON houses(family);

CREATE TABLE IF NOT EXISTS characters (
    id BIGSERIAL PRIMARY KEY,
    discord_id TEXT NOT NULL,
    name TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT '서장',
    realm TEXT NOT NULL DEFAULT '범인',
    location TEXT,
    sheet_json TEXT NOT NULL,
    ledger_json TEXT NOT NULL DEFAULT '{}',
    wallet INTEGER NOT NULL DEFAULT 0,
    marks_json TEXT NOT NULL DEFAULT '{}',
    created_day INTEGER NOT NULL,
    died_day INTEGER,
    lineage_of BIGINT REFERENCES characters(id),
    house_id BIGINT REFERENCES houses(id)
);
CREATE INDEX IF NOT EXISTS idx_characters_account ON characters(discord_id, status);
CREATE INDEX IF NOT EXISTS idx_characters_location ON characters(location) WHERE status = '활성';
CREATE INDEX IF NOT EXISTS idx_characters_house ON characters(house_id, status);

CREATE TABLE IF NOT EXISTS character_bank (
    character_id BIGINT NOT NULL REFERENCES characters(id),
    branch TEXT NOT NULL,
    balance INTEGER NOT NULL DEFAULT 0,
    heir_hint TEXT,
    PRIMARY KEY (character_id, branch)
);

CREATE TABLE IF NOT EXISTS world_meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS regions (
    id TEXT PRIMARY KEY,
    security INTEGER NOT NULL DEFAULT 50,
    economy INTEGER NOT NULL DEFAULT 50,
    sentiment INTEGER NOT NULL DEFAULT 50,
    state_json TEXT NOT NULL DEFAULT '{}',
    updated_day INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS npcs (
    id TEXT PRIMARY KEY,
    region TEXT REFERENCES regions(id),
    tier INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT '활성',
    state_json TEXT NOT NULL DEFAULT '{}',
    updated_day INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS rumors (
    id BIGSERIAL PRIMARY KEY,
    content_json TEXT NOT NULL,
    strength INTEGER NOT NULL,
    accuracy INTEGER NOT NULL,
    network TEXT NOT NULL,
    region TEXT REFERENCES regions(id),
    born_day INTEGER NOT NULL,
    state TEXT NOT NULL DEFAULT '전파중'
);
CREATE INDEX IF NOT EXISTS idx_rumors_live ON rumors(region, state);

CREATE TABLE IF NOT EXISTS faction_standing (
    faction_id TEXT NOT NULL,
    character_id BIGINT NOT NULL REFERENCES characters(id),
    attention INTEGER NOT NULL DEFAULT 0,
    favor INTEGER NOT NULL DEFAULT 0,
    attention_day INTEGER NOT NULL DEFAULT 0,
    favor_day INTEGER NOT NULL DEFAULT 0,
    peak_stage INTEGER NOT NULL DEFAULT 0,
    peak_favor INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (faction_id, character_id)
);

CREATE TABLE IF NOT EXISTS myeongbun (
    issue TEXT PRIMARY KEY,
    target TEXT NOT NULL,
    victims_json TEXT NOT NULL DEFAULT '[]',
    tags_json TEXT NOT NULL DEFAULT '[]',
    raw_gauge INTEGER NOT NULL DEFAULT 0,
    origin_accuracy INTEGER NOT NULL DEFAULT 90,
    origin_rumor TEXT,
    true_target TEXT,
    created_day INTEGER NOT NULL,
    updated_day INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sect_state (
    faction TEXT PRIMARY KEY,
    burden INTEGER NOT NULL DEFAULT 0,
    sources_json TEXT NOT NULL DEFAULT '[]',
    updated_day INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS authority_mandate (
    character_id BIGINT PRIMARY KEY REFERENCES characters(id),
    gauge INTEGER NOT NULL DEFAULT 0,
    peak INTEGER NOT NULL DEFAULT 0,
    updated_day INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS price_events (
    id BIGSERIAL PRIMARY KEY,
    item TEXT NOT NULL,
    delta DOUBLE PRECISION NOT NULL,
    born_day INTEGER NOT NULL,
    decay_days INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS registry (
    id BIGSERIAL PRIMARY KEY,
    kind TEXT NOT NULL,
    key TEXT NOT NULL,
    owner_type TEXT,
    owner_id TEXT,
    data_json TEXT NOT NULL DEFAULT '{}',
    claimed_day INTEGER NOT NULL,
    UNIQUE (kind, key)
);

CREATE TABLE IF NOT EXISTS events (
    id BIGSERIAL PRIMARY KEY,
    day INTEGER NOT NULL,
    type TEXT NOT NULL,
    actor_type TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    target_type TEXT,
    target_id TEXT,
    data_json TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_events_actor ON events(actor_type, actor_id, day);
CREATE INDEX IF NOT EXISTS idx_events_type ON events(type, day);

CREATE TABLE IF NOT EXISTS scenes (
    id BIGSERIAL PRIMARY KEY,
    channel TEXT NOT NULL,
    thread TEXT,
    state TEXT NOT NULL DEFAULT '진행',
    participants TEXT NOT NULL DEFAULT '[]',
    opened_day INTEGER NOT NULL,
    closed_day INTEGER
);
CREATE INDEX IF NOT EXISTS idx_scenes_open ON scenes(channel, state);

CREATE TABLE IF NOT EXISTS mvt_link (
    mc_uuid TEXT PRIMARY KEY,
    mc_name TEXT NOT NULL,
    character_id BIGINT REFERENCES characters(id),
    linked_day INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_mvt_link_char ON mvt_link(character_id);
CREATE INDEX IF NOT EXISTS idx_mvt_link_name ON mvt_link(mc_name);

CREATE TABLE IF NOT EXISTS bridge_inbox (
    event_id TEXT PRIMARY KEY,
    kind TEXT NOT NULL,
    world_day INTEGER NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_bridge_inbox_kind ON bridge_inbox(kind, world_day);

CREATE TABLE IF NOT EXISTS mvt_link_code (
    code TEXT PRIMARY KEY,
    mc_uuid TEXT NOT NULL,
    mc_name TEXT NOT NULL,
    issued_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    state TEXT NOT NULL DEFAULT '대기',
    used_by BIGINT REFERENCES characters(id),
    used_day INTEGER
);
CREATE INDEX IF NOT EXISTS idx_mvt_link_code_body ON mvt_link_code(mc_uuid, state);

CREATE TABLE IF NOT EXISTS mvt_link_request (
    token TEXT PRIMARY KEY,
    mc_uuid TEXT NOT NULL,
    mc_name TEXT NOT NULL,
    character_id BIGINT NOT NULL REFERENCES characters(id),
    discord_id TEXT NOT NULL,
    discord_name TEXT NOT NULL,
    issued_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    state TEXT NOT NULL DEFAULT '대기',
    decided_at BIGINT,
    decided_day INTEGER
);
CREATE INDEX IF NOT EXISTS idx_mvt_link_request_body ON mvt_link_request(mc_uuid, state);
CREATE INDEX IF NOT EXISTS idx_mvt_link_request_char ON mvt_link_request(character_id, state);
CREATE INDEX IF NOT EXISTS idx_mvt_link_request_live ON mvt_link_request(state, expires_at);

CREATE TABLE IF NOT EXISTS blood_debt (
    subject TEXT PRIMARY KEY,
    character_id BIGINT REFERENCES characters(id),
    hidden DOUBLE PRECISION NOT NULL DEFAULT 0,
    known_raw DOUBLE PRECISION NOT NULL DEFAULT 0,
    known_day INTEGER NOT NULL DEFAULT 0,
    public_count INTEGER NOT NULL DEFAULT 0,
    kills INTEGER NOT NULL DEFAULT 0,
    exposure_floor DOUBLE PRECISION NOT NULL DEFAULT 0,
    updated_day INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_blood_debt_char ON blood_debt(character_id);

INSERT INTO world_meta(key, value) VALUES('스키마_버전', '8')
ON CONFLICT(key) DO NOTHING;
