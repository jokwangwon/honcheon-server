-- 002 — 세력 반응 감쇠 축 (단계 4 C: faction_reaction.yml decay 배선)
--
-- 배경: faction_standing 은 attention·favor 두 축의 '현재값'만 들고 있었다.
-- faction_reaction.yml 의 decay 규칙은 값만으로는 집행할 수 없다:
--   주목: every_days 7, amount -1, floor_after_stage_3 = 4   (3단계 이력이 있으면 4 밑으로 안 잊힌다)
--   우호: every_days 30, amount -1, floor_after_공신 = 8      (공신13 이력이 있으면 8 밑으로 안 잊힌다)
-- → 기산점(마지막 갱신일)과 이력(도달 최고치) 두 가지가 더 필요하다.
--
-- 설계: 감쇠는 배치 잡이 아니라 **읽는 순간 정산**한다 (lazy). 같은 세계일에 몇 번을 읽어도
--       같은 값이 나온다 (결정론) — 정산 잡 중복 실행으로 값이 두 번 깎이는 사고가 원천 봉쇄된다.
--
-- 소급: 기존 행의 *_day 는 0 (세계 1일 이전) — 첫 조회 때 경과일만큼 정산된다.
--       그 값을 그대로 두면 알파 시절 쌓인 favor 가 즉시 증발하므로, 현재 세계일로 스탬프한다
--       (지금까지는 감쇠가 아예 없었다 = 그 값은 '오늘 갱신된 값'과 같다).
--       peak_* 는 현재값으로 스탬프 — 이력의 하한은 지금 값 이상일 수 없다.

ALTER TABLE faction_standing ADD COLUMN attention_day INTEGER NOT NULL DEFAULT 0;
ALTER TABLE faction_standing ADD COLUMN favor_day     INTEGER NOT NULL DEFAULT 0;
ALTER TABLE faction_standing ADD COLUMN peak_stage    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE faction_standing ADD COLUMN peak_favor    INTEGER NOT NULL DEFAULT 0;

UPDATE faction_standing
   SET attention_day = COALESCE((SELECT CAST(value AS INTEGER) FROM world_meta WHERE key = '현재일'), 1),
       favor_day     = COALESCE((SELECT CAST(value AS INTEGER) FROM world_meta WHERE key = '현재일'), 1),
       peak_favor    = favor;

-- peak_stage: faction_reaction.yml stage_thresholds (0/1/4/8/13/19/26) 를 SQL 로 옮긴 것.
-- 수치 원천은 config 이며 여기는 시점 고정 소급 스크립트다 (마이그레이션은 '그때의 규칙'을 안다).
UPDATE faction_standing
   SET peak_stage = CASE
        WHEN attention >= 26 THEN 6
        WHEN attention >= 19 THEN 5
        WHEN attention >= 13 THEN 4
        WHEN attention >= 8  THEN 3
        WHEN attention >= 4  THEN 2
        WHEN attention >= 1  THEN 1
        ELSE 0 END;
