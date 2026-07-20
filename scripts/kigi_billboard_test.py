#!/usr/bin/env python3
"""검기 빌보드 모드 비교 — 뒤 시점(실제 플레이 시점)에서 얼마나 보이는가.

왜: 검기는 납작한 판이라 FIXED(고정)로는 각도에 따라 옆면이 되어 사라진다.
    빌보드를 켜면 디스플레이가 카메라를 향해 돌아 어느 시점에서도 보인다.
    그 대가로 3D '감싸는' 느낌이 줄 수 있으므로 **면적으로 재서** 판단한다.

★ 라이브(25565 · run/mvt · config/) 는 건드리지 않는다. run/mvt-test 만 고친다.
"""
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CFG = ROOT / "run" / "mvt-test" / "plugins" / "HoncheonMVT" / "config" / "skill_motion.yml"
AREA_RE = re.compile(r"(\d+)회차:\s*(\d+)프레임 · 최대면적 (\d+)px @ \((\d+),(\d+)\)")


def set_kigi(values):
    s = CFG.read_text(encoding="utf-8")
    i = s.index("kigi_slash:")
    end = s.index("\n# ═", i)
    head, block, tail = s[:i], s[i:end], s[end:]
    for k, v in values.items():
        block, n = re.subn(
            r"(?m)^(\s*" + re.escape(k) + r":\s*)([^#\n]*)(\s*#.*)?$",
            lambda m: m.group(1) + str(v) + "  " + (m.group(3).strip() if m.group(3) else ""),
            block, count=1)
        if n == 0:
            raise SystemExit(f"키 없음: {k}")
    CFG.write_text(head + block + tail, encoding="utf-8")


def stop_test_server():
    subprocess.run(["pkill", "-f", "mvt-test.*paper"], capture_output=True)
    for _ in range(40):
        if subprocess.run(["pgrep", "-f", "mvt-test.*paper"],
                          capture_output=True).returncode != 0:
            return
        time.sleep(1)


def probe(view, swings=6):
    p = subprocess.run(
        [str(ROOT / "scripts" / "kigi_autotest.sh"),
         "--swings", str(swings), "--view", view, "--restart-client"],
        capture_output=True, text=True, timeout=480)
    hits = AREA_RE.findall(p.stdout + p.stderr)
    if not hits:
        return 0, 0, None
    areas = [int(h[2]) for h in hits]
    best = max(hits, key=lambda h: int(h[2]))
    return len(hits), max(areas), (int(best[3]), int(best[4]))


def main():
    views = sys.argv[1:] or ["back"]
    print(f"{'billboard':>10} {'view':>6} | {'발생':>4} {'최대px':>8}  위치", flush=True)
    print("-" * 50, flush=True)
    results = []
    for bb in ["FIXED", "VERTICAL", "CENTER"]:
        for view in views:
            stop_test_server()
            set_kigi({"billboard": bb})
            try:
                n, mx, pos = probe(view)
            except subprocess.TimeoutExpired:
                print(f"{bb:>10} {view:>6} | 시간초과", flush=True)
                continue
            results.append((bb, view, n, mx, pos))
            print(f"{bb:>10} {view:>6} | {n:>4} {mx:>8}  {pos}", flush=True)
    print("-" * 50, flush=True)
    if results:
        best = max(results, key=lambda r: r[3])
        print(f"★ 최대 가시성: {best[0]} ({best[1]}) → {best[3]}px", flush=True)
    print("※ 라이브 config 는 안 건드렸다. 확정값은 사람이 적는다.", flush=True)


if __name__ == "__main__":
    main()
