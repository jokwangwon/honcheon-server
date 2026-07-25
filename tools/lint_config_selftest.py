#!/usr/bin/env python3
"""lint_config의 세력 ID 눈을 일부러 깨뜨려 시험한다."""

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent


def run(root: Path):
    return subprocess.run(
        [sys.executable, str(root / "tools" / "lint_config.py")],
        cwd=root,
        capture_output=True,
        text=True,
        check=False,
    )


def main():
    cases = []
    with tempfile.TemporaryDirectory(prefix="honcheon-lint-") as td:
        work = Path(td)
        shutil.copytree(ROOT / "config", work / "config")
        (work / "tools").mkdir()
        shutil.copy2(ROOT / "tools" / "lint_config.py", work / "tools" / "lint_config.py")

        baseline = run(work)
        cases.append(("정상 faction id 21개를 받는다", baseline.returncode == 0, baseline.stdout))

        ultimate_path = work / "config" / "ultimate_arts.yml"
        ultimate = yaml.safe_load(ultimate_path.read_text(encoding="utf-8"))
        first = next(iter(ultimate["legacy_arts"].values()))
        original = first["faction"]
        first["faction"] = "없는_문파"
        ultimate_path.write_text(yaml.safe_dump(ultimate, allow_unicode=True, sort_keys=False), encoding="utf-8")
        missing = run(work)
        cases.append(("미등록 faction id를 거부한다", missing.returncode == 1 and "없는_문파" in missing.stdout,
                      missing.stdout))

        first["faction"] = "화산파"
        ultimate_path.write_text(yaml.safe_dump(ultimate, allow_unicode=True, sort_keys=False), encoding="utf-8")
        display = run(work)
        cases.append(("표시명은 참조 id가 아니다", display.returncode == 1 and "화산파" in display.stdout,
                      display.stdout))

        first["faction"] = original
        ultimate_path.write_text(yaml.safe_dump(ultimate, allow_unicode=True, sort_keys=False), encoding="utf-8")

        # xp_grade 눈 (5-d) — 등록부(grade_coefficient) 밖의 등급을 심으면 잡아야 한다
        npcs_path = work / "config" / "npcs" / "cheongha_npcs.yml"
        npcs_doc = yaml.safe_load(npcs_path.read_text(encoding="utf-8"))
        first_npc = next(iter(npcs_doc["npcs"].values()))
        first_npc["xp_grade"] = "없는_등급"
        npcs_path.write_text(yaml.safe_dump(npcs_doc, allow_unicode=True, sort_keys=False), encoding="utf-8")
        bad_grade = run(work)
        cases.append(("미등록 xp_grade 를 거부한다", bad_grade.returncode == 1 and "없는_등급" in bad_grade.stdout,
                      bad_grade.stdout))
        del first_npc["xp_grade"]
        npcs_path.write_text(yaml.safe_dump(npcs_doc, allow_unicode=True, sort_keys=False), encoding="utf-8")

        # 게시판 의뢰 XP 눈 (5-e) — 사다리 등급에서 XP 표 항목을 빼면 잡아야 한다
        cult_path = work / "config" / "cultivation.yml"
        cult = yaml.safe_load(cult_path.read_text(encoding="utf-8"))
        removed = cult["levels"]["xp_sources"]["board_quests"].pop("잔심부름")
        cult_path.write_text(yaml.safe_dump(cult, allow_unicode=True, sort_keys=False), encoding="utf-8")
        missing_xp = run(work)
        cases.append(("XP 표에서 빠진 의뢰 등급을 잡는다", missing_xp.returncode == 1 and "잔심부름" in missing_xp.stdout,
                      missing_xp.stdout))
        cult["levels"]["xp_sources"]["board_quests"]["잔심부름"] = removed
        cult_path.write_text(yaml.safe_dump(cult, allow_unicode=True, sort_keys=False), encoding="utf-8")

        # ★가전 무공 입장권 눈 (5-f) — 카탈로그 밖 무공을 입장권에 적으면 잡아야 한다 (C안)
        pc_path = work / "config" / "player_creation.yml"
        pc_text = pc_path.read_text(encoding="utf-8")
        pc_path.write_text(pc_text.replace("하북팽가: wangja_sado", "하북팽가: eopneun_mugong"),
                           encoding="utf-8")
        bad_art = run(work)
        cases.append(("카탈로그 밖 가전 무공 입장권을 잡는다",
                      bad_art.returncode == 1 and "eopneun_mugong" in bad_art.stdout,
                      bad_art.stdout))
        pc_path.write_text(pc_text, encoding="utf-8")

    ok = True
    print("══ lint_config 눈을 시험한다 ══")
    for name, caught, output in cases:
        print(("✓ " if caught else "✗ ") + name)
        if not caught:
            print(output[-1200:])
        ok &= caught
    print("── " + ("✓ 세력 ID 눈이 전부 잡았다" if ok else "✗ 눈이 놓쳤다"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

