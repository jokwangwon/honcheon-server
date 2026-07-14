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

