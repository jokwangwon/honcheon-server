#!/usr/bin/env python3
"""실물 덤프 → <b>등각 3D 미리보기</b>. 짓기 전에 형태를 본다.

    python3 tools/blueprint_3d.py hwasan_honjeon            # 네 귀에서 본 넉 장
    python3 tools/blueprint_3d.py hwasan_honjeon --view se  # 한 방향만
    python3 tools/blueprint_3d.py hwasan_honjeon --cut z18  # 잘라 보기 (단면)
    python3 tools/blueprint_3d.py --selftest

왜 이것인가 — <b>도면을 다시 그리지 않는다</b>
------------------------------------------------
`blueprint_draw.py` 는 `plan`+`columns` 만 그린다. 그런데 <b>지붕·공포·간포·처마·소품은
조성기가 만든다</b> — 도면에 안 보인다. 실루엣의 절반이 지붕인데.
그래서 2026-08-11 의 형태 문제(벽돌 기둥·와플 문짝·안 보이는 공포)를
그 도면으로는 <b>못 잡았을 것이다</b>.

여기서는 조성기가 <b>실제로 놓은 블록</b>(`혼천 도면시험` 이 뱉는 덤프)을 읽어 그린다.
도면과 실물이 갈라질 여지가 <b>구조적으로 없다</b> — 그리는 것이 곧 실물이기 때문이다.

색은 어디서 오나
---------------
`config/block_colors.json` — 클라이언트 jar 의 실제 텍스처에서 뽑은 1110종.
리소스팩이 덮은 블록은 <b>팩 것</b>을 쓴다 (게임에서 보이는 것이 그것이다).
모르는 블록은 <b>자홍색</b>으로 칠하고 이름을 세어 보고한다 — 조용히 안 넘어간다.

정직한 한계
----------
* 이것은 <b>렌더가 아니라 도해</b>다. 그림자·주변광·기울어진 블록(계단·반블록·트랩도어)의
  실제 모양은 안 그린다 — 모두 <b>정육면체</b>로 그린다.
  그러므로 「형태·덩어리·비례」를 보는 데 쓰고, <b>「무엇으로 읽히는가」는 여전히 사진</b>이다.
* 대신 사진이 못 하는 것을 한다: <b>짓기 전에</b>, <b>어느 방향에서든</b>, <b>잘라서</b> 본다.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from block_palette import color_of          # noqa: E402
DUMP = ROOT / "run" / "mvt-test" / "dump"
TABLE = ROOT / "config" / "block_colors.json"

TW, TH, VH = 6, 3, 6        # 반너비 · 반깊이 · 켜 높이 (화소)
SHADE = {"top": 1.00, "right": 0.78, "left": 0.58}
BG = (250, 248, 243)
UNKNOWN_COLOR = (255, 0, 255)

# 네 귀 — 어느 모서리에서 보는가. (x 뒤집기, z 뒤집기)
VIEWS = {"se": (False, False), "sw": (True, False), "ne": (False, True), "nw": (True, True)}


def load_dump(name: str):
    f = DUMP / f"{name}.csv"
    if not f.exists():
        raise SystemExit(f"덤프가 없다: {f}\n  먼저 인게임에서: 혼천 도면시험 {name} hwasan")
    out = []
    for line in f.read_text().splitlines()[1:]:
        if not line:
            continue
        x, y, z, b = line.split(",")
        out.append((int(x), int(y), int(z), b))
    return out


def shade(rgb, k):
    return tuple(max(0, min(255, round(c * k))) for c in rgb)


def render(voxels, table, flip_x=False, flip_z=False, cut=None):
    from PIL import Image, ImageDraw

    unknown = {}
    occupied = {(x, y, z) for x, y, z, _ in voxels}
    if cut:
        axis, val = cut[0], int(cut[1:])
        voxels = [v for v in voxels
                  if (v[0] if axis == "x" else v[2] if axis == "z" else v[1]) <= val]
        occupied = {(x, y, z) for x, y, z, _ in voxels}

    mx = max(v[0] for v in voxels)
    mz = max(v[2] for v in voxels)
    put = []
    for x, y, z, b in voxels:
        # ★가려진 칸은 안 그린다 — 보이는 세 면이 모두 막혔으면 화면에 한 화소도 못 낸다
        if ((x + 1, y, z) in occupied and (x, y, z + 1) in occupied
                and (x, y + 1, z) in occupied):
            continue
        vx = (mx - x) if flip_x else x
        vz = (mz - z) if flip_z else z
        put.append((vx, y, vz, b))

    sxs = [(vx - vz) * TW for vx, _, vz, _ in put]
    sys_ = [(vx + vz) * TH - y * VH for vx, y, vz, _ in put]
    ox = -min(sxs) + TW + 4
    oy = -min(sys_) + 4
    w = max(sxs) - min(sxs) + 2 * TW + 8
    h = max(sys_) - min(sys_) + 2 * TH + VH + 8

    im = Image.new("RGB", (w, h), BG)
    dr = ImageDraw.Draw(im)
    # 화가 알고리즘 — 뒤에서 앞으로. 등각에서는 (x+z+y) 오름차순이 곧 원근이다
    for vx, y, vz, b in sorted(put, key=lambda v: (v[0] + v[2] + v[1])):
        ent = color_of(table, b)
        if ent is None:
            unknown[b] = unknown.get(b, 0) + 1
            rgb = UNKNOWN_COLOR
        else:
            rgb = tuple(ent["rgb"])
        sx = (vx - vz) * TW + ox
        sy = (vx + vz) * TH - y * VH + oy
        dr.polygon([(sx, sy), (sx + TW, sy + TH), (sx, sy + 2 * TH), (sx - TW, sy + TH)],
                   fill=shade(rgb, SHADE["top"]))
        dr.polygon([(sx - TW, sy + TH), (sx, sy + 2 * TH),
                    (sx, sy + 2 * TH + VH), (sx - TW, sy + TH + VH)],
                   fill=shade(rgb, SHADE["left"]))
        dr.polygon([(sx, sy + 2 * TH), (sx + TW, sy + TH),
                    (sx + TW, sy + TH + VH), (sx, sy + 2 * TH + VH)],
                   fill=shade(rgb, SHADE["right"]))
    return im, unknown


# ───────────────────────── 눈을 시험하는 눈 ─────────────────────────

def _selftest() -> int:
    fails = []

    def check(name, cond, got=""):
        print(("  ✓ " if cond else "  ✗ ") + name + (f" — {got}" if not cond else ""))
        if not cond:
            fails.append(name)

    tbl = {"a": {"rgb": [200, 100, 50]}, "b": {"rgb": [40, 40, 40]}}

    # ① 한 칸은 세 면을 그린다 — 명도 셋이 나와야 입체다
    im, unk = render([(0, 0, 0, "a")], tbl)
    cols = {c for c in im.getdata() if c != BG}
    check("한 칸이 <b>세 면</b>으로 그려진다 (윗면·좌·우)", len(cols) == 3, sorted(cols))
    check("모르는 블록이 없다", not unk, unk)

    # ② 모르는 블록은 <b>자홍색 + 이름</b> — 조용히 안 넘어간다
    im, unk = render([(0, 0, 0, "없는블록")], tbl)
    check("모르는 블록을 자홍색으로 칠하고 <b>이름을 센다</b>",
          unk == {"없는블록": 1} and UNKNOWN_COLOR in set(im.getdata()), unk)

    # ③ ★가려진 칸은 안 그린다 — 안 그러면 1만 칸이 전부 겹쳐 그려진다
    solid = [(x, y, z, "a") for x in range(3) for y in range(3) for z in range(3)]
    im, _ = render(solid, tbl)
    inner_hidden = all(((1, 1, 1) != (x, y, z)) for x, y, z, _ in solid if False)
    # 3×3×3 에서 (0,0,0) 은 세 이웃이 다 있으므로 안 그려져야 한다
    from PIL import Image as _I
    im2, _ = render([v for v in solid if v[:3] != (0, 0, 0)], tbl)
    check("★세 면이 다 막힌 칸은 <b>안 그린다</b> (그림이 같다)",
          list(im.getdata()) == list(im2.getdata()), "")

    # ④ 자른 면 — 경계 밖이 사라진다
    im, _ = render([(0, 0, 0, "a"), (0, 0, 5, "a")], tbl, cut="z0")
    im_one, _ = render([(0, 0, 0, "a")], tbl)
    check("자르면 그 너머가 <b>사라진다</b>", im.size == im_one.size, f"{im.size} vs {im_one.size}")

    # ⑤ 네 귀가 서로 다른 그림이다 (뒤집기가 실제로 듣는다)
    two = [(0, 0, 0, "a"), (4, 0, 0, "b")]
    a, _ = render(two, tbl, *VIEWS["se"])
    b, _ = render(two, tbl, *VIEWS["sw"])
    check("네 귀가 <b>서로 다른 그림</b>이다", list(a.getdata()) != list(b.getdata()), "")

    # ⑥ 위가 밝고 옆이 어둡다 — 입체감의 방향이 뒤집히면 안 된다
    check("윗면이 가장 밝다", SHADE["top"] > SHADE["right"] > SHADE["left"], SHADE)

    print(f"\n3D 미리보기의 눈 — {'통과' if not fails else f'실패 {len(fails)}: {fails}'}")
    return 1 if fails else 0


def main(argv):
    if "--selftest" in argv:
        return _selftest()
    args = [a for a in argv if not a.startswith("--")]
    if not args:
        print(__doc__)
        return 2
    name = args[0]
    view = None
    cut = None
    for a in argv:
        if a.startswith("--view"):
            view = a.split("=", 1)[1] if "=" in a else argv[argv.index(a) + 1]
        if a.startswith("--cut"):
            cut = a.split("=", 1)[1] if "=" in a else argv[argv.index(a) + 1]
    if not TABLE.exists():
        raise SystemExit("색표가 없다 — python3 tools/block_palette.py")
    table = json.loads(TABLE.read_text())
    voxels = load_dump(name)
    out_dir = ROOT / "run" / "preview"
    out_dir.mkdir(parents=True, exist_ok=True)
    views = {view: VIEWS[view]} if view else VIEWS
    allunk = {}
    for vname, (fx, fz) in views.items():
        im, unk = render(voxels, table, fx, fz, cut)
        for k, v in unk.items():
            allunk[k] = allunk.get(k, 0) + v
        path = out_dir / f"{name}_{vname}{'_' + cut if cut else ''}.png"
        im.save(path)
        print(f"  {vname}  {im.size[0]}×{im.size[1]}  → {path.relative_to(ROOT)}")
    print(f"덤프 {len(voxels):,}칸")
    if allunk:
        print("  ★모르는 블록 " + str(len(allunk)) + "종 (자홍색): " + ", ".join(sorted(allunk)))
    else:
        print("  색표가 블록을 <b>전부</b> 안다")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
