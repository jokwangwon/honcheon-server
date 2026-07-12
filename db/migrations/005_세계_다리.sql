-- 005 세계 다리(世界橋) — 마크(MVT)에서 벌어진 일이 봇의 장부로 흐르는 통로.
-- 규칙: config/world_bridge.yml (등록제) · 설계: docs/design/world_bridge.md
--
-- 표는 둘뿐이다. 세계 상태를 새로 만들지 않는다 —
-- 사건은 이미 있는 events·rumors·regions·myeongbun 으로 흘러 들어간다.
-- 여기 있는 것은 다리의 **부속**일 뿐이다: 신원(누가 그를 죽였는가)과 멱등(두 번 죽이지 않는다).

-- ① 신원 접합 — 마크의 몸(uuid) ↔ 봇의 장부(character_id).
--    이 표가 없으면 "누가 죽였는가"가 세계에 남지 않는다: 소문에 주체가 없으면 세력이 아무도 주목하지 않는다.
--    링크가 없어도 다리는 돈다 (이름만 적힌 소문 = 세계의 배경음). 다만 그의 이름은 장부에 오르지 않는다.
CREATE TABLE IF NOT EXISTS mvt_link (
    mc_uuid      TEXT PRIMARY KEY,                    -- 마인크래프트 플레이어 UUID
    mc_name      TEXT NOT NULL,                       -- 마지막으로 본 이름 (닉 변경 대비 — 키가 아니다)
    character_id INTEGER REFERENCES characters(id),   -- 봇의 캐릭터 (NULL = 아직 안 이어졌다)
    linked_day   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_mvt_link_char ON mvt_link(character_id);
CREATE INDEX IF NOT EXISTS idx_mvt_link_name ON mvt_link(mc_name);

-- ② 수신함 — 멱등의 못. event_id 는 MVT 가 발신 때 박은 uuid 다.
--    커서(world_meta '다리:커서')를 쓰지 못하고 죽어도, 재기동 후 같은 줄을 다시 읽어도
--    이 PK 가 두 번째 적용을 막는다. 재생은 무해하다 — 그것이 append-only 로그의 값이다.
CREATE TABLE IF NOT EXISTS bridge_inbox (
    event_id    TEXT PRIMARY KEY,                     -- MVT 봉투의 id (uuid)
    kind        TEXT NOT NULL,                        -- npc_death · bandit_slain · qi_manifested …
    world_day   INTEGER NOT NULL,                     -- 봇이 받은 세계일 (마크의 시각이 아니라 장부의 날)
    applied_at  TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_bridge_inbox_kind ON bridge_inbox(kind, world_day);
