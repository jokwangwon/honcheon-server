-- ════════════════════════════════════════════════════════════════════════════
-- 008 · 가문(家門) — 집안이 「유형」에서 「한 채의 집」이 된다
--
-- 【왜】 B-077. 지금까지 집안은 **분류표의 한 칸**이었다 (`sheet_json.집안 = "농가의 자식"`).
--   그래서 코드가 **같은 유형의 아이를 전부 남매로 묶었다** — 농가의 아이 둘은 남매가 아니다.
--   서로 다른 농가다. 사용자의 말은 *"**같은 세가에** 같이 태어나게 되었다면"* — **같은 집**이다.
--   그런데 세계에 **집의 실체가 없었다.**
--
-- 【★★ 이 파일은 아직 돌리지 마라 — 그릇만 만들었다】
--   담당자는 **아무도 이 표에 앉히지 않았다.** 왜냐하면 **누가 어느 집에 태어나는가**(배정 규칙)가
--   **세계관 결정**이고 사용자가 아직 안 정했기 때문이다 (player_creation.yml house_system.open_questions).
--   그릇은 다섯 답 **어느 쪽이 와도 담을 수 있게** 넉넉히 비워 두었다 (전부 NULL 허용).
--
-- 【규약】 DB 는 봇만 쓴다. 마이그레이션은 **사람이 백업 확인 후** 돌린다:
--     python3 tools/migrate_db.py            (봇이 꺼져 있을 때. ★ 담당자는 봇을 죽이지 않는다)
--   봇은 구 DB 를 만나도 **죽지 않는다** — 경고만 하고 뜬다 (Db.schemaVersionGate).
--
-- 【안전】 이 마이그레이션은 **더하기만 한다** (표 하나 + 열 하나). 지우거나 고치는 것이 없다.
--   돌리기 전의 캐릭터는 house_id 가 NULL 이고, 코드는 NULL 을 **"가문이 아직 없다"** 로 읽는다
--   (그때 형제는 **비어 있다** — 거짓 형제를 만들지 않는다).
-- ════════════════════════════════════════════════════════════════════════════

-- ─── 가문 — 세계에 실재하는 「한 채의 집」 ───
CREATE TABLE IF NOT EXISTS houses (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,

    -- 어느 유형의 집인가 — player_creation.yml age_and_lifepath.families 의 키
    -- (무가의_자식 · 농가의_자식 … ★ 유형은 죽지 않았다. 그 위에 **실체**가 선 것이다)
    family      TEXT NOT NULL,

    -- ★ 이름 — NULL 허용. 성씨 규칙이 **아직 없다** (house_system.open_questions ②)
    --   등록 NPC 는 성이 없다 (한백·묵삼·곽진 — 두 자 이름뿐). 성을 쓰는 것은 **세가뿐**이다
    --   (남궁세가·팽가·당가…). 그러므로 무가에는 성이 필요하고, **그 성을 사용자가 정해야 한다.**
    name        TEXT,

    -- ★ 어디에 있는가 — NULL 허용. 지금 **완비된 지역은 청하현 하나뿐**이다
    --   (사천·강남·하북은 스텁 — 블록도 앵커도 없다). 스텁에 가문을 두면 **갈 수 없는 집**이 된다
    region      TEXT,

    -- ★ 상태 — NULL 허용. 흥/쇠/멸의 어휘가 **등록부에 아직 없다** (house_system.open_questions ⑤)
    --   지금은 「몰락_무가의_자식」이라는 **집안 유형**이 '이미 멸한 집'을 대신 표현하고 있다
    state       TEXT,

    created_day INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_houses_family ON houses(family);

-- ─── 사람이 어느 집에 태어났는가 ───
-- ★ NULL = 가문이 아직 없다 (이 마이그레이션 전에 태어난 사람 · 배정 규칙이 서기 전)
--   코드는 NULL 을 만나면 **형제를 비운다** — 거짓 형제보다 없는 형제가 낫다
ALTER TABLE characters ADD COLUMN house_id INTEGER REFERENCES houses(id);

CREATE INDEX IF NOT EXISTS idx_characters_house ON characters(house_id, status);

-- ★ 서열은 **표로 두지 않는다** — 같은 house_id 를 id 순으로 세우면 그것이 곧 태어난 순서다.
--   파생값은 낡을 수가 없다 (별도 표를 두면 그 표가 진실과 갈라진다).

UPDATE world_meta SET value = '8' WHERE key = '스키마_버전';
INSERT OR IGNORE INTO world_meta(key, value) VALUES('스키마_버전', '8');
