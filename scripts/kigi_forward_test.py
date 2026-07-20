#!/usr/bin/env python3
"""검기를 앞으로 얼마나 밀어야 '뒤 시점'에서 몸에 안 가리는가 — 자동 측정.

발견: billboard CENTER 로 각도 문제를 없애도 뒤에서 80px 뿐이었다.
      원인은 각도가 아니라 **가림** — 검기가 몸 바로 앞에 있어 플레이어 몸이 막는다.
      앞으로 밀면 (1인칭에선 멀어져 작아지고) 뒤에서는 몸 밖으로 나온다.
★ run/mvt-test 만 고친다. 라이브는 안 건드린다.
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
    # (forward, scale) — 앞으로 밀면서 크기도 같이 본다
    combos = [(0.1, 2.0), (1.2, 2.0), (2.0, 2.5), (2.8, 3.0)]
    views = sys.argv[1:] or ["back", "first"]
    print(f"{'fwd':>5} {'scale':>6} {'view':>6} | {'발생':>4} {'최대px':>8}  위치", flush=True)
    print("-" * 54, flush=True)
    rows = []
    for fwd, sc in combos:
        for view in views:
            stop_test_server()
            set_kigi({"forward": fwd, "scale": sc, "billboard": "CENTER"})
            try:
                n, mx, pos = probe(view)
            except subprocess.TimeoutExpired:
                print(f"{fwd:>5} {sc:>6} {view:>6} | 시간초과", flush=True)
                continue
            rows.append((fwd, sc, view, n, mx, pos))
            print(f"{fwd:>5} {sc:>6} {view:>6} | {n:>4} {mx:>8}  {pos}", flush=True)
    print("-" * 54, flush=True)
    back = [r for r in rows if r[2] == "back"]
    if back:
        b = max(back, key=lambda r: r[4])
        print(f"★ 뒤 시점 최대: forward {b[0]} · scale {b[1]} → {b[4]}px", flush=True)
    print("※ 1인칭이 과하게 크면(화면 덮음) forward 를 더 밀거나 scale 을 줄인다.", flush=True)


if __name__ == "__main__":
    main()
