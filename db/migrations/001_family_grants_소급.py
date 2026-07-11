# 001 — 집안 기술 grants 소급 (bot_alpha_guide.md '마이그레이션 선례' 해소)
#
# 배경: 집안 grants의 기술 축 배선(GameListener 운명 생성)은 생성 시점에만
# sheet_json["기술"]을 심는다. 배선 이전에 생성된 캐릭터는 "기술" 키 자체가 없어
# 승급 요건("아무 무공 입문" 등)을 영구히 못 채운다.
#
# 소급 규칙:
#   - 대상: sheet_json에 "기술" 키가 없는 캐릭터 (있으면 배선 이후 생성이거나
#     이미 다른 경로로 기술을 얻은 것 — 건드리지 않는다. 이중 부여 방지)
#   - sheet_json["집안"]을 config/player_creation.yml age_and_lifepath.families의
#     grants.기술과 대조해 주입. 예: 몰락 무가의 자식 → {검법: 0}, 무가의 자식 → {검법: 1},
#     상가의 자식 → {흥정: 1}, 객잔집 자식 → {고객_파악: 1} …
#   - 집안 표기 주의: 시트에는 밑줄→공백 변환돼 저장된다 ("몰락_무가의_자식" →
#     "몰락 무가의 자식"). 대조표를 공백 표기로 변환해 맞춘다.
#   - grants에 기술 축이 없는 집안(농가의 자식 — 생활_기술_선택_1종은 플레이어
#     선택형이라 소급 불가)은 건너뛴다.
#   - 기술 외 grants(소양·흔적·armory)는 시트 키가 아니라 서사·판정 재료 — 소급 제외.

import json
import re
from pathlib import Path

CONFIG_PATH = Path(__file__).resolve().parents[2] / "config" / "player_creation.yml"


def _family_skill_grants(text: str) -> dict:
    """player_creation.yml에서 families.*.grants.기술만 뽑는 초소형 파서.

    표준 라이브러리 제약(YAML 파서 없음)에 따른 대상 한정 파싱:
    - 'families:' 블록(들여쓰기 2) 안의 집안 항목(들여쓰기 4)을 순회
    - 각 집안의 'grants:' 줄(flow-style)에서 '기술: { ... }' 내부 쌍을 정규식으로 추출
    grants가 블록 스타일로 바뀌면 이 파서는 못 읽는다 — 그때는 이 파일을 함께 고칠 것
    (마이그레이션은 시점 고정 스크립트라 미래 config 개편과 무관하게 당시 규칙만 알면 된다).
    """
    grants = {}
    in_families = False
    family = None
    for line in text.splitlines():
        if re.match(r"^  families:\s*(#.*)?$", line):
            in_families = True
            continue
        if in_families:
            # families 블록 종료: 들여쓰기 2 이하의 새 키
            if re.match(r"^ {0,2}\S", line) and line.strip():
                break
            m = re.match(r"^    ([^\s:]+):\s*(#.*)?$", line)
            if m:
                family = m.group(1)
                continue
            if family and re.match(r"^      grants:", line):
                skill_m = re.search(r"기술:\s*\{([^{}]*)\}", line)
                if skill_m:
                    skills = {}
                    for pair in skill_m.group(1).split(","):
                        if ":" not in pair:
                            continue
                        k, v = pair.split(":", 1)
                        v = v.strip()
                        skills[k.strip()] = int(v) if re.fullmatch(r"-?\d+", v) \
                            else float(v)
                    if skills:
                        grants[family] = skills
    return grants


def migrate(conn):
    text = CONFIG_PATH.read_text(encoding="utf-8")
    raw = _family_skill_grants(text)
    if not raw:
        raise RuntimeError(f"{CONFIG_PATH}에서 families grants.기술을 하나도 못 읽었다 — "
                           "config 구조 변경 여부 확인 필요 (안전 중단)")
    # 시트의 집안은 공백 표기 — 대조표를 공백 표기로 변환
    by_display = {fam.replace("_", " "): skills for fam, skills in raw.items()}

    patched = 0
    skipped_has_skills = 0
    rows = conn.execute("SELECT id, name, sheet_json FROM characters").fetchall()
    for cid, name, sheet_json in rows:
        sheet = json.loads(sheet_json)
        family = str(sheet.get("집안", ""))
        # 방어: 혹시 밑줄 표기로 저장된 행이 있어도 맞춘다
        skills = by_display.get(family) or by_display.get(family.replace("_", " "))
        if not skills:
            continue  # 집안 없음 / 기술 grants 없는 집안(농가 등)
        if "기술" in sheet:
            skipped_has_skills += 1
            continue  # 배선 이후 생성 or 이미 기술 보유 — 이중 부여 방지
        sheet["기술"] = dict(skills)
        conn.execute(
            "UPDATE characters SET sheet_json = ? WHERE id = ?",
            (json.dumps(sheet, ensure_ascii=False, separators=(",", ":")), cid),
        )
        patched += 1
    return (f"캐릭터 {len(rows)}명 검사 — {patched}명 소급, "
            f"{skipped_has_skills}명은 기술 보유로 제외")
