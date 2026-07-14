-- 007 접합의 두 손 — **디스코드가 청하고, 그 몸이 수락한다** (2026-07-13)
--
-- 006 은 코드 방식이었다: 마크가 6자를 내고, 사람이 그것을 날라 디스코드에 붙여넣었다.
-- 자물쇠는 튼튼했으나 **사람이 코드를 날랐다** — 외우고, 창을 바꾸고, 붙여넣고, 만료되고.
-- 사용자의 판정: "코드 복사는 없어져도 되고 초대 링크만 있으면 되고, 닉네임 입력하여 대조."
--
-- ★ 그런데 닉네임만으로 이으면 **아무나 남의 닉을 대고 그 캐릭터를 가져간다.**
--   그래서 손을 둘로 나눈다 — 청은 디스코드에서(신원을 디스코드가 서명한다),
--   **수락은 그 몸의 게임 화면에서**(그 몸에 로그인한 사람만 누를 수 있다).
--   이 표는 그 **사이의 2분**을 담는다. 둘 다 없으면 아무것도 이어지지 않는다.
--
-- 규칙: config/world_bridge.yml identity (ttl_seconds · cooldown_seconds · one_pending_per_body)
--
-- ☠ mvt_link_code (006) 는 **더 이상 쓰이지 않는다.** 지우지 않는다 — 옛 접합의 감사 기록이다.

CREATE TABLE IF NOT EXISTS mvt_link_request (
    token        TEXT PRIMARY KEY,                    -- 지목일 뿐 열쇠가 아니다 (새어도 남의 몸은 못 받는다)
    mc_uuid      TEXT NOT NULL,                       -- ★ 청을 받은 몸 — 이 몸만 수락할 수 있다
    mc_name      TEXT NOT NULL,                       -- 청할 때의 이름 (명부에서 읽은 것)
    character_id INTEGER NOT NULL REFERENCES characters(id),   -- 청한 캐릭터 (디스코드가 서명한 신원)
    discord_id   TEXT NOT NULL,
    discord_name TEXT NOT NULL,                       -- 게임 화면에 뜰 이름 ("디스코드의 「아무개」가…")
    issued_at    INTEGER NOT NULL,                    -- epoch millis
    expires_at   INTEGER NOT NULL,                    -- issued_at + ttl_seconds (기본 120초)
    state        TEXT NOT NULL DEFAULT '대기',        -- 대기 | 수락 | 거절 | 만료 | 폐기
    decided_at   INTEGER,                             -- 그 몸이 답한 시각
    decided_day  INTEGER                              -- 세계일 (감사)
);
CREATE INDEX IF NOT EXISTS idx_mvt_link_request_body ON mvt_link_request(mc_uuid, state);
CREATE INDEX IF NOT EXISTS idx_mvt_link_request_char ON mvt_link_request(character_id, state);
CREATE INDEX IF NOT EXISTS idx_mvt_link_request_live ON mvt_link_request(state, expires_at);
