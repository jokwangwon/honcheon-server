#!/usr/bin/env python3
"""뿌리내림 과정 감사 (B-178의 눈) — 등록부와 훅의 표류를 잰다.

무엇이 거짓말이 되나:
  ① 훅이 부르는 정거장 id 가 등록부에 없다 → 그 행위는 영영 안 세어진다 (조용한 유실)
  ② 등록부의 정거장을 어느 훅도 부르지 않는다 → 트래커가 그 정거장에서 영영 멈춘다
     (튜토리얼이 끝나지 않는다 — 이것이 이 눈의 존재 이유다)
자기 시험: --selftest 가 가짜 표류를 심어 눈이 짖는지 확인한다 (시험 없는 눈은 눈이 아니다).
"""
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "config" / "tutorial.yml"
SRC = ROOT / "server-mvt" / "src" / "main" / "java"

# 훅 문법: plugin.tutorial().bump(<변수>, "<정거장>")  ·  TutorialGuide 내부 소급: closeQuiet(ledger, "<정거장>")
#          + TutorialGuide **자기 파일 안**의 맨 bump() (몸짓 감지가 부른다 — 남의 파일의 맨 bump 는
#            딴 시스템(Antechamber)의 것이라 안 센다)
BUMP = re.compile(r'tutorial\(\)\s*\.\s*bump\(\s*\w+\s*,\s*"([^"]+)"')
BUMP_INNER = re.compile(r'(?<!\.)\bbump\(\s*\w+\s*,\s*"([^"]+)"')
QUIET = re.compile(r'closeQuiet\(\s*\w+\s*,\s*"([^"]+)"')


def registry_stations(path=CONFIG):
    with open(path, encoding="utf-8") as f:
        root = yaml.safe_load(f) or {}
    stations = (root.get("tutorial") or {}).get("stations") or []
    return [str(s.get("id")) for s in stations if isinstance(s, dict) and s.get("id")]


def hook_stations(src=SRC):
    found = set()
    for java in src.rglob("*.java"):
        text = java.read_text(encoding="utf-8")
        for pat in (BUMP, QUIET):
            found.update(pat.findall(text))
        if java.name == "TutorialGuide.java":
            found.update(BUMP_INNER.findall(text))
    return found


def audit(reg, hooks):
    """위반 목록 — (종류, 정거장). reg 는 순서 보존 목록, hooks 는 집합."""
    bad = []
    for st in hooks:
        if st not in reg:
            bad.append(("등록부에 없는 정거장을 훅이 부른다", st))
    for st in reg:
        if st not in hooks:
            bad.append(("어느 훅도 부르지 않는 정거장 (트래커가 여기서 영영 멈춘다)", st))
    return bad


def selftest():
    """눈을 시험하는 눈 — 가짜 표류 둘을 심어 둘 다 짖는지."""
    reg = ["마중", "채비"]
    hooks = {"마중", "유령_정거장"}          # ① 등록부 밖 훅 ② 채비를 부르는 훅 없음
    bad = audit(reg, hooks)
    kinds = {b[1] for b in bad}
    ok = "유령_정거장" in kinds and "채비" in kinds and len(bad) == 2
    print(f"  {'✅' if ok else '❌'} 자기 시험 — 심은 표류 2건을 눈이 {len(bad)}건 잡았다")
    # 깨끗한 판은 조용해야 한다 (거짓 짖음 검사)
    clean = audit(["마중"], {"마중"})
    ok2 = not clean
    print(f"  {'✅' if ok2 else '❌'} 자기 시험 — 깨끗한 판에 거짓 짖음 {len(clean)}건")
    return 0 if ok and ok2 else 1


def main():
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    reg = registry_stations()
    hooks = hook_stations()
    bad = audit(reg, hooks)
    print(f"뿌리내림 감사 — 등록부 {len(reg)}정거장 · 훅 참조 {len(hooks)}종")
    for kind, st in bad:
        print(f"  ✗ {kind}: {st}")
    print(f"  총평: {'✅ 위반 0건' if not bad else f'❌ 위반 {len(bad)}건'}")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
