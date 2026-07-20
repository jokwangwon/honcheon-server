#!/usr/bin/env python3
"""검기 각도 자동 탐색 — 테스트 서버 config 를 바꿔가며 '뒤 시점 가시성'을 잰다.

왜: 검기는 납작한 판이라 각도에 따라 뒤(=실제 플레이 시점)에서 사라진다.
    사람이 매번 녹화해 주지 않아도 되도록, 조합을 자동으로 훑어 **면적으로** 고른다.

★ 라이브(25565 · run/mvt)는 건드리지 않는다. 테스트(run/mvt-test)만 고친다.
"""
import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TEST_CFG = ROOT / "run" / "mvt-test" / "plugins" / "HoncheonMVT" / "config" / "skill_motion.yml"
LIVE_CFG = ROOT / "config" / "skill_motion.yml"


def set_kigi(cfg_path: Path, values: dict):
    """kigi_slash 블록 안의 키만 바꾼다 (주석 보존)."""
    s = cfg_path.read_text(encoding="utf-8")
    i = s.index("kigi_slash:")
    end = s.index("\n# ═", i)
    head, block, tail = s[:i], s[i:end], s[end:]
    for k, v in values.items():
        block, n = re.subn(
            r"(?m)^(\s*" + re.escape(k) + r":\s*)([^#\n]*)(\s*#.*)?$",
            lambda m: m.group(1) + str(v) + "  " + (m.group(3).strip() if m.group(3) else ""),
            block, count=1)
        if n == 0:
            raise SystemExit(f"키를 못 찾았다: {k}")
    cfg_path.write_text(head + block + tail, encoding="utf-8")


def stop_test_server():
    subprocess.run(["pkill", "-f", "run/mvt-test.*paper.jar"], capture_output=True)
    subprocess.run(["pkill", "-f", "mvt-test"], capture_output=True)
    for _ in range(30):
        r = subprocess.run(["pgrep", "-f", "mvt-test.*paper"], capture_output=True)
        if r.returncode != 0:
            return
        time.sleep(1)


AREA_RE = re.compile(r"(\d+)회차:\s*(\d+)프레임 · 최대면적 (\d+)px @ \((\d+),(\d+)\)")


def run_probe(view: str, swings: int, timeout: int = 420):
    cmd = [str(ROOT / "scripts" / "kigi_autotest.sh"),
           "--swings", str(swings), "--view", view, "--restart-client"]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    out = p.stdout + p.stderr
    hits = AREA_RE.findall(out)
    if not hits:
        return {"bursts": 0, "max": 0, "mean": 0, "pos": None, "raw": out[-500:]}
    areas = [int(h[2]) for h in hits]
    best = max(hits, key=lambda h: int(h[2]))
    return {"bursts": len(hits), "max": max(areas),
            "mean": sum(areas) // len(areas), "pos": (int(best[3]), int(best[4]))}


def main():
    ap = argparse.ArgumentParser(description="검기 각도 자동 탐색")
    ap.add_argument("--view", default="back", choices=["back", "front", "first"])
    ap.add_argument("--swings", type=int, default=6)
    args = ap.parse_args()

    # (tilt_deg, roll_deg) 조합 — 납작한 판이 뒤에서 보이려면 어느 각인가
    combos = [
        (25, 180), (0, 180), (45, 180),
        (25, 90), (0, 90), (60, 90),
        (25, 0), (0, 0),
    ]

    print(f"{'tilt':>5} {'roll':>5} | {'발생':>4} {'최대px':>7} {'평균px':>7}  위치")
    print("-" * 56)
    results = []
    for tilt, roll in combos:
        stop_test_server()
        set_kigi(TEST_CFG, {"tilt_deg": tilt, "roll_deg": roll})
        try:
            r = run_probe(args.view, args.swings)
        except subprocess.TimeoutExpired:
            print(f"{tilt:>5} {roll:>5} | 시간초과")
            continue
        results.append((tilt, roll, r))
        pos = f"({r['pos'][0]},{r['pos'][1]})" if r["pos"] else "-"
        print(f"{tilt:>5} {roll:>5} | {r['bursts']:>4} {r['max']:>7} {r['mean']:>7}  {pos}", flush=True)

    print("-" * 56)
    if results:
        best = max(results, key=lambda x: x[2]["max"])
        print(f"★ 최대 가시성: tilt {best[0]} · roll {best[1]} → {best[2]['max']}px")
    print("※ 라이브 config 는 건드리지 않았다. 확정값은 사람이 config 에 적는다.")


if __name__ == "__main__":
    main()
