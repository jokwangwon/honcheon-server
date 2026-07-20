#!/usr/bin/env python3
"""검기 파라미터 탐색 — 카메라 봇으로 **네 각도**를 재서 균형점을 찾는다.

왜: 뒤(실제 플레이 시점)에서 검기가 몸에 가려 작게 보인다(1300px vs 앞 4460px).
    크기·전방 오프셋·높이를 조합해 **뒤에서도 보이되 앞·옆이 과하지 않은** 값을 고른다.
    사람의 녹화 없이 숫자로 고른다 — 그것이 이 하네스의 존재 이유다.

★ run/mvt-test 만 고친다. 라이브(25565 · config/) 는 건드리지 않는다.
"""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CFG = ROOT / "run" / "mvt-test" / "plugins" / "HoncheonMVT" / "config" / "skill_motion.yml"
CAM = ROOT / "scripts" / "kigi_cam_test.sh"
# ★ 실제 출력의 「각도별 요약」 줄에서 뽑는다 (형식을 눈으로 확인하고 맞췄다):
#     back         110     31      2        1262   (708,380)     /경로…
#   = 각도 · 프레임 · 검출 · 횟수 · **최대면적px** · 위치
#   (추측한 정규식이 안 맞으면 전부 0 으로 읽혀 거짓 결론이 난다 — 이 프로젝트에서 이미 겪었다)
PEAK_RE = re.compile(r"^\s*(back|front|side|high)\s+\d+\s+\d+\s+\d+\s+(\d+)\s", re.I | re.M)


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


def probe(swings, timeout=900):
    """네 각도를 한 번에 재고 {angle: peak_px} 로 돌려준다."""
    p = subprocess.run([str(CAM), "--angle", "all", "--swings", str(swings)],
                       capture_output=True, text=True, timeout=timeout)
    out = p.stdout + p.stderr
    peaks = {}
    for angle, px in PEAK_RE.findall(out):
        a = angle.lower()
        peaks[a] = max(peaks.get(a, 0), int(px))
    return peaks, out


def main():
    ap = argparse.ArgumentParser(description="검기 파라미터 탐색 (카메라 봇)")
    ap.add_argument("--swings", type=int, default=6)
    ap.add_argument("--out", default=str(ROOT / "scratch" / "camtest" / "sweep.json"))
    args = ap.parse_args()

    # (scale, forward, center_height) — 뒤에서 몸 밖으로 나오게 하는 세 축
    combos = [
        (2.0, 0.1, 1.10),   # 지금 값 (기준선)
        (3.0, 0.1, 1.10),   # 크게만
        (2.0, 1.2, 1.10),   # 앞으로만
        (3.0, 1.2, 1.10),   # 크게 + 앞으로
        (3.0, 1.2, 1.60),   # 크게 + 앞으로 + 높게
        (4.0, 2.0, 1.40),   # 더 크게 + 더 앞으로
    ]

    print(f"{'scale':>6}{'fwd':>6}{'hgt':>6} | {'back':>7}{'front':>7}{'side':>7}{'high':>7}", flush=True)
    print("-" * 56, flush=True)
    rows = []
    for scale, fwd, hgt in combos:
        stop_test_server()
        set_kigi({"scale": scale, "forward": fwd, "center_height": hgt})
        try:
            peaks, _ = probe(args.swings)
        except subprocess.TimeoutExpired:
            print(f"{scale:>6}{fwd:>6}{hgt:>6} | 시간초과", flush=True)
            continue
        rows.append({"scale": scale, "forward": fwd, "center_height": hgt, "peaks": peaks})
        print(f"{scale:>6}{fwd:>6}{hgt:>6} | "
              f"{peaks.get('back', 0):>7}{peaks.get('front', 0):>7}"
              f"{peaks.get('side', 0):>7}{peaks.get('high', 0):>7}", flush=True)
        Path(args.out).write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")

    print("-" * 56, flush=True)
    if rows:
        best = max(rows, key=lambda r: r["peaks"].get("back", 0))
        print(f"★ 뒤 시점 최대: scale {best['scale']} · forward {best['forward']} "
              f"· height {best['center_height']} → {best['peaks'].get('back', 0)}px", flush=True)
        print(f"   그때 앞 {best['peaks'].get('front', 0)}px · 옆 {best['peaks'].get('side', 0)}px", flush=True)
    print(f"※ 기록: {args.out} · 라이브 config 는 안 건드렸다.", flush=True)


if __name__ == "__main__":
    main()
