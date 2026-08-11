#!/usr/bin/env python3
"""사진 → <b>치수</b>. 레퍼런스에서 칸과 부재를 뽑아 도면과 대질시킨다.

    python3 tools/ref_measure.py 09_honjeon --box 450,415,985,515
    python3 tools/ref_measure.py 09_honjeon --box 450,415,985,515 --compare hwasan_honjeon
    python3 tools/ref_measure.py --selftest

왜 있나
-------
지금까지 레퍼런스에서 뽑아 쓴 것은 <b>색</b>뿐이었다 (평균 명도·붉은 면 비율).
치수는 내가 <b>눈으로 세어</b> 「정면 7칸쯤」 하고 말했다. 그래서 「칸이 몇이고 어칸이
협칸의 몇 배인가」 같은 것을 <b>도면과 나란히 놓고 따질 수가 없었다.</b>

여기서는 <b>선</b>을 찾는다. 목조 건축의 정면은 기둥과 수평 부재가 만드는 <b>격자</b>다.
그 격자를 찾으면 칸 수·칸 폭의 비·부재 높이가 <b>수로</b> 나온다.

어떻게 찾나
----------
* 세로선(기둥) — 각 x 에서 <b>세로 방향으로 이어지는 밝기 변화</b>를 세로로 합한다.
  기둥의 좌우 모서리는 위아래로 길게 이어지므로 그 x 에서 합이 솟는다.
* 가로선(인방·창방·도리·처마) — 같은 것을 축만 바꿔서.
* 봉우리는 <b>이웃보다 크고</b> <b>최소 간격</b>을 지키는 것만 센다 —
  안 그러면 한 기둥의 좌·우 모서리가 두 기둥으로 세어진다.

정직한 한계
----------
* <b>상자를 사람이 준다.</b> 자동으로 정면만 오려내는 것은 이 사진들(안개·절벽·나무)에서
  믿을 수 없다. 상자는 명령줄에 남으므로 <b>재현은 된다</b>.
* 원근이 있는 사진에서는 먼 쪽 칸이 좁게 잡힌다. 그래서 <b>칸 폭의 비</b>는
  가운데 몇 칸만 믿고, 양 끝은 참고로만 본다.
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REF = ROOT / "docs" / "design" / "hwasan" / "ref"
BP = ROOT / "config" / "blueprints"


def profile(img, axis: int):
    """축을 따라 <b>이어지는 변화</b>를 모은다. axis=0 → 세로선(x별) · 1 → 가로선(y별)."""
    import numpy as np

    a = np.asarray(img.convert("RGB"), dtype=np.float64)
    g = 0.299 * a[..., 0] + 0.587 * a[..., 1] + 0.114 * a[..., 2]
    if axis == 0:
        d = np.abs(np.diff(g, axis=1))          # 가로로 갈 때의 변화 = 세로선
        return d.sum(axis=0)                    # 세로로 합한다 (길게 이어질수록 커진다)
    d = np.abs(np.diff(g, axis=0))              # 세로로 갈 때의 변화 = 가로선
    return d.sum(axis=1)


def smooth(v, k=3):
    import numpy as np

    if k <= 1:
        return v
    ker = np.ones(k) / k
    return np.convolve(v, ker, mode="same")


# 선이라 부를 최소 세기 — 이보다 약하면 <b>무늬가 아니라 잡티</b>다
#   (한 선을 따라 쌓인 밝기 변화를 그 선의 길이로 나눈 값. 2.0 = 화소당 평균 2단계)
LINE_FLOOR = 2.0
# 부재의 두 모서리를 <b>하나</b>로 묶는 문턱 — 가장 넓은 칸의 이 비율보다 좁으면 한 부재다
MEMBER_FRAC = 0.4


def peaks(v, min_gap: int, span: int, keep: float = 0.55):
    """봉우리 — <b>이웃보다 크고</b> 최소 간격을 지키는 것만.

    ★<b>민판에서는 아무것도 안 만든다</b>: 평평한 그림은 평균도 표준편차도 0 이라
    「평균+0.55σ 이상」을 <b>모든 화소가</b> 통과한다. 그래서 절대 바닥을 둔다 —
    선을 따라 쌓인 변화가 길이당 {@link #LINE_FLOOR} 에 못 미치면 <b>선이 아니다</b>.

    ★<b>부재 하나를 둘로 안 센다</b>: 굵은 기둥은 좌·우 <b>두 모서리</b>를 낸다.
    최소 간격만으로는 부재가 그 간격보다 굵을 때 못 막는다. 그래서 뒤에 한 번 더 묶는다 —
    가장 넓은 칸의 {@link #MEMBER_FRAC} 보다 좁게 붙은 봉우리들은 <b>한 부재</b>다.
    (부재 폭 &lt; 칸 폭 은 목조 건축에서 언제나 참이다.)
    ※단 <b>홀로 선 부재 하나</b>는 원리적으로 못 가른다 — 견줄 칸이 없기 때문이다.
    """
    import numpy as np

    v = np.asarray(v, dtype=np.float64)
    if span <= 0 or v.size == 0 or v.max() / span < LINE_FLOOR:
        return []                                    # 민판 — 선이 없다
    thr = v.mean() + keep * v.std()
    cand = [i for i in range(1, len(v) - 1)
            if v[i] >= v[i - 1] and v[i] >= v[i + 1] and v[i] >= thr]
    cand.sort(key=lambda i: -v[i])
    out = []
    for i in cand:
        if all(abs(i - j) >= min_gap for j in out):
            out.append(i)
    out.sort()
    if len(out) < 3:
        return out
    gaps = [b - a for a, b in zip(out, out[1:])]
    wide = max(gaps)
    merged = [[out[0]]]
    for g, i in zip(gaps, out[1:]):
        if g < MEMBER_FRAC * wide:
            merged[-1].append(i)                     # 같은 부재의 다른 모서리
        else:
            merged.append([i])
    return [round(sum(c) / len(c)) for c in merged]


def measure(path: Path, box, min_gap_x=None, min_gap_y=None):
    from PIL import Image

    im = Image.open(path).convert("RGB").crop(box)
    w, h = im.size
    px = profile(im, 0)
    py = profile(im, 1)
    gx = min_gap_x or max(4, w // 24)
    gy = min_gap_y or max(3, h // 14)
    cols = peaks(smooth(px, 3), gx, h)
    rows = peaks(smooth(py, 3), gy, w)
    return {"size": (w, h), "cols": cols, "rows": rows,
            "gaps_x": [b - a for a, b in zip(cols, cols[1:])],
            "gaps_y": [b - a for a, b in zip(rows, rows[1:])]}


def bp_structure(name: str):
    """도면 쪽의 같은 것 — 정면 기둥열과 그 틈."""
    import re

    import yaml

    d = yaml.safe_load((BP / f"{name}.yml").read_text())
    cols = d["columns"]
    plan = d["plan"].split("\n")

    def is_post(ch):
        for e in cols.get(ch, []):
            m = re.match(r"(.+?)\*(\d+)$", e)
            mat, n = (m.group(1), int(m.group(2))) if m else (e, 1)
            if (n >= 3 and "stone" not in mat and "andesite" not in mat
                    and mat not in ("air", "plaster", "lattice")):
                return True
        return False

    rf = list(d["roof"].values())[0]
    bx0, _, bx1, bz1 = rf["box"]
    posts = [c for c in range(bx0, bx1 + 1) if is_post(plan[bz1][c])]
    # 겹기둥(붙어 선 둘)은 <b>한 열</b>로 센다 — 사진에서도 한 선으로 보인다
    lines = []
    for c in posts:
        if lines and c - lines[-1][-1] == 1:
            lines[-1].append(c)
        else:
            lines.append([c])
    centers = [sum(g) / len(g) for g in lines]
    return {"posts": posts, "lines": len(lines), "bays": len(lines) - 1,
            "gaps": [round(b - a, 1) for a, b in zip(centers, centers[1:])]}


def report(name, box, compare=None):
    src = REF / f"{name}.png"
    if not src.exists():
        raise SystemExit(f"레퍼런스가 없다: {src}")
    m = measure(src, box)
    print(f"[사진] {src.name}  상자 {box}  ({m['size'][0]}×{m['size'][1]})")
    print(f"  세로선(기둥) {len(m['cols'])}개 → <b>{max(0, len(m['cols']) - 1)}칸</b>")
    print(f"    자리 {m['cols']}")
    print(f"    틈   {m['gaps_x']}")
    if m["gaps_x"]:
        mid = sorted(m["gaps_x"])[len(m["gaps_x"]) // 2]
        big = max(m["gaps_x"])
        print(f"    가운데 값 {mid} · 가장 넓은 칸 {big} → <b>어칸 : 협칸 = {big / mid:.2f}</b>")
    print(f"  가로선(부재) {len(m['rows'])}개 · 자리 {m['rows']}")
    print(f"    틈 {m['gaps_y']}")
    if compare:
        b = bp_structure(compare)
        print(f"\n[도면] {compare}")
        print(f"  기둥열 {b['lines']}개 → <b>{b['bays']}칸</b> · 틈 {b['gaps']}")
        if b["gaps"]:
            mid = sorted(b["gaps"])[len(b["gaps"]) // 2]
            big = max(b["gaps"])
            print(f"  가운데 값 {mid} · 가장 넓은 칸 {big} → <b>어칸 : 협칸 = {big / mid:.2f}</b>")
        print("\n[대질]")
        pb = max(0, len(m["cols"]) - 1)
        print(f"  칸 수      사진 {pb} · 도면 {b['bays']}"
              + ("   ✓" if pb == b["bays"] else "   ← 다르다"))


# ───────────────────────── 눈을 시험하는 눈 ─────────────────────────

def _selftest() -> int:
    from PIL import Image, ImageDraw

    fails = []

    def check(name, cond, got=""):
        print(("  ✓ " if cond else "  ✗ ") + name + (f" — {got}" if not cond else ""))
        if not cond:
            fails.append(name)

    # ① 아는 답을 만들어 되찾는다 — 40화소마다 기둥, 7개
    im = Image.new("RGB", (280, 120), (220, 215, 200))
    dr = ImageDraw.Draw(im)
    for i in range(7):
        dr.rectangle([i * 40 + 16, 0, i * 40 + 23, 119], fill=(90, 30, 25))
    m = measure_img(im)
    check("★아는 그림에서 기둥 <b>7개</b>를 되찾는다", len(m["cols"]) == 7, m["cols"])
    check("★틈이 <b>40</b> 으로 고르게 나온다",
          m["gaps_x"] and all(abs(g - 40) <= 1 for g in m["gaps_x"]), m["gaps_x"])

    # ② ★<b>굵은</b> 기둥의 좌·우 모서리를 둘로 세지 않는다
    #    (최소 간격보다 굵은 부재 — 이것이 첫 판에서 무너진 자리다)
    im2 = Image.new("RGB", (260, 120), (220, 215, 200))
    d2 = ImageDraw.Draw(im2)
    for x in (20, 100, 180):
        d2.rectangle([x, 0, x + 15, 119], fill=(90, 30, 25))   # 폭 16 · 간격 80
    m2 = measure_img(im2)
    check("★굵은 기둥 셋을 <b>셋</b>으로 센다 (모서리 여섯이 아니라)",
          len(m2["cols"]) == 3, m2["cols"])

    # ③ 가로 부재도 같은 방식으로 잡힌다
    im3 = Image.new("RGB", (200, 160), (220, 215, 200))
    d3 = ImageDraw.Draw(im3)
    for y in (30, 80, 130):
        d3.rectangle([0, y, 199, y + 6], fill=(60, 40, 25))
    m3 = measure_img(im3)
    check("가로 부재 <b>3개</b>를 잡는다", len(m3["rows"]) == 3, m3["rows"])

    # ④ 민판에서는 <b>아무것도</b> 안 잡는다 (헛것을 만들지 않는다)
    flat = Image.new("RGB", (200, 120), (180, 175, 165))
    m4 = measure_img(flat)
    check("민판에서는 선을 <b>안</b> 만든다", not m4["cols"] and not m4["rows"],
          f"{m4['cols']} / {m4['rows']}")

    # ⑤ 도면 쪽 구조를 읽는다 — 겹기둥은 한 열로
    b = bp_structure("hwasan_honjeon")
    check("도면에서 정면 칸 수를 읽는다 (겹기둥은 한 열)", b["bays"] >= 5,
          f"{b['lines']}열 {b['bays']}칸")

    print(f"\n사진 자의 눈 — {'통과' if not fails else f'실패 {len(fails)}: {fails}'}")
    return 1 if fails else 0


def measure_img(im):
    """시험용 — 이미 열린 그림에서 잰다."""
    w, h = im.size
    px = profile(im, 0)
    py = profile(im, 1)
    cols = peaks(smooth(px, 3), max(4, w // 24), h)
    rows = peaks(smooth(py, 3), max(3, h // 14), w)
    return {"size": (w, h), "cols": cols, "rows": rows,
            "gaps_x": [b - a for a, b in zip(cols, cols[1:])],
            "gaps_y": [b - a for a, b in zip(rows, rows[1:])]}


def main(argv):
    if "--selftest" in argv:
        return _selftest()
    args = [a for a in argv if not a.startswith("--")]
    if not args:
        print(__doc__)
        return 2
    box = None
    compare = None
    for i, a in enumerate(argv):
        if a == "--box":
            box = tuple(int(v) for v in argv[i + 1].split(","))
        if a == "--compare":
            compare = argv[i + 1]
    if box is None:
        raise SystemExit("--box x0,y0,x1,y1 이 필요하다 (상자는 사람이 준다 — 머리말 참조)")
    report(args[0], box, compare)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
