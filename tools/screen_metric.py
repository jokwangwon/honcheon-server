#!/usr/bin/env python3
"""화면의 눈 — 렌더 컷에서 <b>밝기·덩이·명도</b>를 잰다.

왜 있나
-------
J-5 회차(2026-08-10)에 지난 회차의 지표 스크립트가 남지 않아 자를 다시 만들어야 했다.
숫자만 남은 자는 다음 회차에 못 쓴다 — 절대 눈금이 달라져 전·후 비교가 깨진다.
그래서 <b>자를 스크립트째</b> 여기 박는다. 눈금을 바꾸려면 이 파일을 고치고,
고친 회차에 전·후를 <b>둘 다 다시</b> 재라.

정의 (이걸 바꾸면 지난 수치와 못 견준다)
--------------------------------------
* 하늘 화소 = ``B > R+20`` 이고 ``B > 120``  — 잰 값에서 뺀다.
  하늘·구름은 건물보다 훨씬 밝아, 안 빼면 평균 명도가 하늘 면적을 재게 된다.
* 밝은 화소 = 하늘이 아니면서 명도 > ``THRESHOLD``
* 최대 밝은 덩이 = 밝은 화소의 <b>4-이웃</b> 최대 연결 성분 (화소 수)
  — 「밝은 띠」는 총량이 아니라 <b>이어짐</b>이 문제라서 면적이 아니라 덩이를 잰다.
* 명도 = 0.299R + 0.587G + 0.114B

판정 시점 (Codex 확정 · 2026-08-10 · 45도 중경 은퇴)
---------------------------------------------------
``CAMERAS`` 를 보라. 45도 중경은 화면 대부분이 절벽·마당이라 은퇴했다 —
그 시점의 옛 이상치는 건물이 아니라 <b>지형의 회색 암반</b>을 잰 것이었다.

쓰기
----
    python3 tools/screen_metric.py run/stage_render/J5_*.png
    python3 tools/screen_metric.py --cameras          # 판정 시점을 찍어라
    python3 tools/screen_metric.py --selftest         # 눈을 시험하는 눈
"""
from __future__ import annotations

import sys
from collections import deque

import numpy as np

THRESHOLD = 150.0
SKY_BLUE_OVER_RED = 20.0
SKY_MIN_BLUE = 120.0

# 화산파 본전 판정 시점 — 이름=x,y,z,yaw,pitch (scripts/stage_shot.py 인자 형식)
CAMERAS = [
    "원경=1,74,95,180,3",
    "중경=1,63,80,180,2",
    "근접=-6,60,66,172,-1",
    "귀=-32,65,70,-133,2",  # 45도 중경을 대신한다 — 측면 판정은 이 한 장으로 갈음
]


def luma(rgb: np.ndarray) -> np.ndarray:
    return 0.299 * rgb[..., 0] + 0.587 * rgb[..., 1] + 0.114 * rgb[..., 2]


def sky_mask(rgb: np.ndarray) -> np.ndarray:
    return (rgb[..., 2] > rgb[..., 0] + SKY_BLUE_OVER_RED) & (rgb[..., 2] > SKY_MIN_BLUE)


def largest_blob(mask: np.ndarray) -> int:
    """4-이웃 최대 연결 성분의 화소 수."""
    seen = np.zeros(mask.shape, bool)
    best = 0
    h, w = mask.shape
    for y in range(h):
        for x in range(w):
            if not mask[y, x] or seen[y, x]:
                continue
            q = deque([(y, x)])
            seen[y, x] = True
            n = 0
            while q:
                cy, cx = q.popleft()
                n += 1
                for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    ny, nx = cy + dy, cx + dx
                    if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        q.append((ny, nx))
            best = max(best, n)
    return best


def measure(rgb: np.ndarray) -> dict:
    g = luma(rgb)
    land = ~sky_mask(rgb)
    bright = (g > THRESHOLD) & land
    return {
        "bright_pct": float(bright.sum() / max(1, land.sum()) * 100.0),
        "largest": largest_blob(bright),
        "mean_luma": float(g[land].mean()) if land.any() else 0.0,
        "land_pct": float(land.mean() * 100.0),
    }


def measure_file(path: str) -> dict:
    from PIL import Image

    return measure(np.asarray(Image.open(path).convert("RGB"), dtype=np.float64))


# ───────────────────────── 눈을 시험하는 눈 ─────────────────────────

def _selftest() -> int:
    fails = []

    def check(name, cond):
        print(("  ✓ " if cond else "  ✗ ") + name)
        if not cond:
            fails.append(name)

    def solid(h, w, rgb):
        a = np.zeros((h, w, 3), dtype=np.float64)
        a[:] = rgb
        return a

    sky = (120.0, 170.0, 230.0)   # B > R+20 이고 B > 120
    stone = (128.0, 128.0, 128.0)  # 명도 128 — 어두운 쪽
    white = (230.0, 230.0, 230.0)  # 명도 230 — 밝은 쪽

    # ① 하늘은 잰 값에서 빠진다 — 하늘만 있으면 땅이 0%
    a = solid(20, 20, sky)
    check("하늘만 있는 화면은 땅 0%", abs(measure(a)["land_pct"]) < 1e-9)

    # ② 하늘이 평균 명도를 못 올린다
    a = solid(20, 20, stone)
    a[:10] = sky
    m = measure(a)
    check("하늘은 평균 명도에 안 섞인다 (128)", abs(m["mean_luma"] - 128.0) < 0.5)

    # ③ 문턱 위/아래를 가른다
    check("명도 128 은 밝은 화소가 아니다", measure(solid(8, 8, stone))["bright_pct"] == 0.0)
    check("명도 230 은 밝은 화소다", measure(solid(8, 8, white))["bright_pct"] == 100.0)

    # ④ ★덩이는 <b>면적이 아니라 이어짐</b>을 잰다 —
    #    같은 밝은 화소 수라도 흩어지면 덩이가 작아야 한다
    joined = solid(10, 10, stone)
    joined[0, 0:6] = white                      # 한 줄로 이어진 6
    split = solid(10, 10, stone)
    split[0, 0:3] = white                       # 3 + 3, 사이가 끊김
    split[0, 5:8] = white
    mj, ms = measure(joined), measure(split)
    check("이어진 6 과 흩어진 6 은 밝은 화소%가 같다",
          abs(mj["bright_pct"] - ms["bright_pct"]) < 1e-9)
    check("★이어진 쪽 덩이가 더 크다 (6 > 3)", mj["largest"] == 6 and ms["largest"] == 3)

    # ⑤ 대각선은 안 잇는다 (4-이웃이지 8-이웃이 아니다)
    diag = solid(10, 10, stone)
    diag[0, 0] = white
    diag[1, 1] = white
    check("대각선은 안 이어진다 (4-이웃)", measure(diag)["largest"] == 1)

    # ⑥ ★변이 — 문턱을 낮추면 돌이 밝은 화소가 되어야 한다.
    #    이 시험이 통과하면 「밝은 화소 0」이 문턱 덕이 아니라 화면 덕임을 안다
    global THRESHOLD
    keep = THRESHOLD
    try:
        THRESHOLD = 100.0
        check("★변이: 문턱 100 이면 명도 128 도 밝다", measure(solid(8, 8, stone))["bright_pct"] == 100.0)
    finally:
        THRESHOLD = keep
    check("문턱은 되돌아왔다", THRESHOLD == 150.0)

    # ⑦ 땅이 없으면 0 으로 나누지 않는다
    check("하늘뿐이어도 안 터진다", measure(solid(4, 4, sky))["bright_pct"] == 0.0)

    # ⑧ 판정 시점 넷이 살아 있다 · 45도는 은퇴했다
    names = [c.split("=")[0] for c in CAMERAS]
    check("판정 시점은 원경·중경·근접·귀 넷", names == ["원경", "중경", "근접", "귀"])
    check("45도 중경은 은퇴했다", not any("45" in n for n in names))

    print(f"\n화면의 눈 — {12 - len(fails)}/12 통과" if not fails
          else f"\n화면의 눈 — 실패 {len(fails)}: {fails}")
    return 1 if fails else 0


def main(argv: list[str]) -> int:
    if "--selftest" in argv:
        return _selftest()
    if "--cameras" in argv:
        print("python3 scripts/stage_shot.py sanse_test_hwasan overworld \\")
        print("  " + " ".join(f'"{c.split("=")[0]}={c.split("=")[1]}"' for c in CAMERAS))
        return 0
    paths = [a for a in argv if not a.startswith("--")]
    if not paths:
        print(__doc__)
        return 2
    print(f"{'화면':14s} {'밝은 화소%':>10s} {'최대 밝은 덩이':>13s} {'평균 명도':>9s}")
    for p in paths:
        m = measure_file(p)
        name = p.split("/")[-1].rsplit(".", 1)[0]
        print(f"{name:14s} {m['bright_pct']:9.1f}% {m['largest']:13,d} {m['mean_luma']:9.1f}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
