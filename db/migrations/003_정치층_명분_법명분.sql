-- 003 — 정치층 (config/faction_politics.yml 배선: 명분·연합·관무불가침)
--
-- 배경: faction_standing 은 **세력 대 개인**(주목·우호)만 들고 있었다.
--       세력 대 세력의 층 — 명분(myeongbun) · 법명분(authority_mandate) — 이 없었다.
--       그래서 "현령을 죽이면 무슨 일이 벌어지는가"에 세계가 대답하지 못했다.
--
-- 설계:
--   myeongbun          사안(issue)마다 하나. 사건이 쌓고(inputs) 관이 깎는다(drains).
--                      raw_gauge 는 '장부에 적힌 사실'이고, 세계가 그것을 얼마나 믿는가는
--                      **소문의 정확도**가 정한다 (origin_accuracy → accuracy_gate 배수).
--                      각 세력은 자기 조직 채널(rumor.yml network_access)에 닿은 정확도로
--                      제 몫의 명분을 계산한다 — 망별 속도가 곧 세력별 참전 시차다.
--   authority_mandate  관 측 게이지. 대상은 개인. 포두 +8 · 현령 +14.
--                      10 이상 + 무림 명분 < 8 → murim_disavowal (강호의 절연).
--
-- 연합(coalition)에는 테이블이 없다 — **상태가 아니라 함수다.**
--   참여 세력 = f(명분 게이지, 태그, 관계표, 조직 채널 도달)  ← 전부 위 두 테이블 + config + rumors
--   같은 세계일이면 같은 연합. 저장할 것이 없으므로 어긋날 것도 없다 (결정론).
--
-- 감쇠: 배치 잡 금지. 읽는 순간 정산한다 (002 의 관행 그대로).

CREATE TABLE IF NOT EXISTS myeongbun (
    issue           TEXT PRIMARY KEY,
    target          TEXT NOT NULL,
    tags_json       TEXT NOT NULL DEFAULT '[]',
    raw_gauge       INTEGER NOT NULL DEFAULT 0,
    origin_accuracy INTEGER NOT NULL DEFAULT 90,
    origin_rumor    TEXT,
    true_target     TEXT,
    created_day     INTEGER NOT NULL,
    updated_day     INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS authority_mandate (
    character_id INTEGER PRIMARY KEY REFERENCES characters(id),
    gauge        INTEGER NOT NULL DEFAULT 0,
    peak         INTEGER NOT NULL DEFAULT 0,
    updated_day  INTEGER NOT NULL
);

-- 소급 없음: 지금까지 관을 죽인 자도, 쌓인 명분도 없다 (이 층이 존재하지 않았으므로).
-- 빈 표에서 시작하는 것이 정확하다 — 과거를 발명하지 않는다.
