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
    faction_id    TEXT NOT NULL,
    character_id  INTEGER NOT NULL REFERENCES characters(id),
    attention     INTEGER NOT NULL DEFAULT 0,    -- 주목 0~30
    favor         INTEGER NOT NULL DEFAULT 0,    -- 우호 (안면4/신용8/공신13/은인19)
    -- 감쇠 축 (faction_reaction.yml decay) — 읽는 순간 정산한다 (세계일 결정론: 같은 날 = 같은 값)
    attention_day INTEGER NOT NULL DEFAULT 0,    -- 주목 마지막 갱신일 (7일마다 -1)
    favor_day     INTEGER NOT NULL DEFAULT 0,    -- 우호 마지막 갱신일 (30일마다 -1 — 은혜는 천천히 잊힌다)
    peak_stage    INTEGER NOT NULL DEFAULT 0,    -- 도달 최고 주목 단계 (3단계 이력 = 감쇠 하한 4)
    peak_favor    INTEGER NOT NULL DEFAULT 0,    -- 도달 최고 우호 (공신13 이력 = 감쇠 하한 8)
    PRIMARY KEY (faction_id, character_id)
);

-- ─── 정치층 (config/faction_politics.yml) — 세력 대 세력 ───
-- faction_standing 이 '세력 대 개인'이라면, 이 둘은 '세력 대 세력'이다. 두 층은 곱해진다.
-- 감쇠는 배치 잡이 아니라 **읽는 순간 정산**한다 (faction_standing 관행 계승 — 같은 날 = 같은 상태).

CREATE TABLE IF NOT EXISTS myeongbun (           -- 명분(名分) — 연합의 방아쇠. 사안(issue)마다 하나
    issue           TEXT PRIMARY KEY,            -- 사안 id ("<사건키>:<대상>")
    target          TEXT NOT NULL,               -- 누가 했는가 — 명분이 겨누는 세력 id (factions.yml 등록부)
    victims_json    TEXT NOT NULL DEFAULT '[]',  -- ★ 누가 당했는가 — 피해 세력 id 목록 (004)
                                                 --   이 칸이 없으면 연합 계산의 절반이 죽는다:
                                                 --   피해_당사자(-8) · 동맹_피해(-6) · 원한_세력_참여(+5)
    tags_json       TEXT NOT NULL DEFAULT '[]',  -- 무고/무림침해/존망/금기/법의배신/규약/생계
    raw_gauge       INTEGER NOT NULL DEFAULT 0,  -- 사건 점수 누적 (배수 적용 전 — '장부에 적힌 사실')
    origin_accuracy INTEGER NOT NULL DEFAULT 90, -- 발원 소문의 정확도 = 명분 배수의 원천
    origin_rumor    TEXT,                        -- 이 명분을 실어 나른 소문군 (rumors.content_json.군)
    true_target     TEXT,                        -- 오해 밴드의 진범 (진범_규명 시 명분이 이쪽으로 이전)
    created_day     INTEGER NOT NULL,
    updated_day     INTEGER NOT NULL             -- 감쇠 기산점 (7일마다 -1, 존망 태그는 4에서 멈춘다)
);

-- 문파 상태 (004) — ★ 문파에게도 '사정'이 있다. 제 코가 석 자면 남의 싸움에 못 낀다.
-- 0~6 의 단일 축. 기준선은 config (faction_politics roster.<세력>.internal_burden),
-- 이 표는 **사건이 얹은 것**만 든다. '다른 전쟁 중(+4)' 은 저장하지 않는다 —
-- 오늘의 연합에서 읽으면 되는 값이다 (파생 상태 금지 — 연합이 표가 없는 것과 같은 이유).
-- 규칙: config/sect_life.yml sect_state.internal_burden
CREATE TABLE IF NOT EXISTS sect_state (
    faction      TEXT PRIMARY KEY,               -- 세력 id (문파·세가·사파 조직 — 조직은 다 사정이 있다)
    burden       INTEGER NOT NULL DEFAULT 0,     -- 사건이 얹은 부담 (장문 교체기 3 · 내분 2 · 사상자 2 …)
    sources_json TEXT NOT NULL DEFAULT '[]',     -- 무엇 때문인가
    updated_day  INTEGER NOT NULL                -- 감쇠 기산점 (30일마다 -1 — 사정은 느리게 풀린다)
);

CREATE TABLE IF NOT EXISTS authority_mandate (   -- 법명분(法名分) — 관 측의 게이지. 대상 = 개인
    character_id INTEGER PRIMARY KEY REFERENCES characters(id),
    gauge        INTEGER NOT NULL DEFAULT 0,     -- 0~30 (포두 8 · 현령 14 · 10 이상 = 강호의 절연)
    peak         INTEGER NOT NULL DEFAULT 0,     -- 도달 최고치 (15 이력 = 감쇠 하한 8)
    updated_day  INTEGER NOT NULL                -- 감쇠 기산점 (7일마다 -1)
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

-- ─── 세계 다리 (마이그레이션 005) — 마크(MVT)의 사건이 이 장부로 흘러 들어오는 통로 ───
-- 규칙: config/world_bridge.yml (등록제) · 설계: docs/design/world_bridge.md
-- 세계 상태를 새로 만들지 않는다: 사건은 events·rumors·regions 로 흘러간다.
-- 이 둘은 다리의 부속이다 — 신원(누가 죽였는가)과 멱등(두 번 죽이지 않는다).

CREATE TABLE IF NOT EXISTS mvt_link (            -- 마크의 몸(uuid) ↔ 봇의 장부(character_id)
    mc_uuid      TEXT PRIMARY KEY,
    mc_name      TEXT NOT NULL,                  -- 마지막으로 본 이름 (키가 아니다 — 닉은 바뀐다)
    character_id INTEGER REFERENCES characters(id),   -- NULL = 아직 안 이어졌다 (이름만 남는 소문)
    linked_day   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_mvt_link_char ON mvt_link(character_id);
CREATE INDEX IF NOT EXISTS idx_mvt_link_name ON mvt_link(mc_name);

CREATE TABLE IF NOT EXISTS bridge_inbox (        -- 멱등의 못 — 재생은 무해하다 (append-only 로그의 값)
    event_id    TEXT PRIMARY KEY,                -- MVT 봉투의 id (uuid)
    kind        TEXT NOT NULL,
    world_day   INTEGER NOT NULL,
    applied_at  TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_bridge_inbox_kind ON bridge_inbox(kind, world_day);
