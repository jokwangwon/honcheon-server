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
import blockshape                            # noqa: E402
DUMP = ROOT / "run" / "mvt-test" / "dump"
TABLE = ROOT / "config" / "block_colors.json"

# ★한 블록을 <b>16 등분</b>으로 잡는다 — 마크 모델과 같은 단위라 반블록·계단이 딱 떨어진다.
#   화소가 커야 3px 트랩도어가 보인다: 블록당 16 화소면 1 단위 = 1 화소.
U = 1                       # 한 단위(1/16 블록)의 화소 수
TW, TH, VH = 8 * U, 4 * U, 8 * U   # 반너비 · 반깊이 · 켜 높이
SHADE = {"top": 1.00, "right": 0.78, "left": 0.58}
BG = (250, 248, 243)
UNKNOWN_COLOR = (255, 0, 255)

# 네 귀 — 어느 모서리에서 보는가. (x 뒤집기, z 뒤집기)
VIEWS = {"se": (False, False), "sw": (True, False), "ne": (False, True), "nw": (True, True)}


def load_dump(name: str):
    """덤프를 읽는다 — <b>블록 상태까지</b> (탭으로 갈린다)."""
    f = DUMP / f"{name}.tsv"
    if not f.exists():
        old = DUMP / f"{name}.csv"
        if old.exists():
            raise SystemExit(f"옛 덤프({old.name})다 — 상태가 없어 형태를 못 그린다.\n"
                             f"  다시 뽑아라: 혼천 도면시험 {name} hwasan")
        raise SystemExit(f"덤프가 없다: {f}\n  먼저 인게임에서: 혼천 도면시험 {name} hwasan")
    out = []
    for line in f.read_text().splitlines()[1:]:
        if not line:
            continue
        x, y, z, b = line.split("\t")
        # ★air 는 칸이 아니다 — 도면시험 덤프에는 air 행이 없지만 코퍼스 TSV 에는 있다.
        #   air 를 남기면 컬링의 occupied 에 들어가 「위가 막혔다」가 되어,
        #   위에 하늘이 있는 모든 풀블록(바닥 전체·통벽)이 지워진다 (2026-08-28 실증 —
        #   한옥마을 도해가 붕 떠 보인 원인. 지붕은 계단·반블록이라 살아남아 더 감쪽같았다).
        if b.split("[")[0] in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air"):
            continue
        out.append((int(x), int(y), int(z), b))
    return out


def shade(rgb, k):
    return tuple(max(0, min(255, round(c * k))) for c in rgb)


def _prism(dr, ox, oy, box, rgb, bx, by, bz):
    """상자 하나 → 등각 여섯 모서리. 윗면·좌면·우면 셋만 보인다."""
    x0, y0, z0, x1, y1, z1 = box
    # 블록 안 좌표(0..16) 를 세계 단위로 옮긴다
    wx0, wx1 = bx * 16 + x0, bx * 16 + x1
    wy0, wy1 = by * 16 + y0, by * 16 + y1
    wz0, wz1 = bz * 16 + z0, bz * 16 + z1

    def pt(wx, wy, wz):
        return ((wx - wz) * TW / 16 + ox, (wx + wz) * TH / 16 - wy * VH / 16 + oy)

    top = [pt(wx0, wy1, wz0), pt(wx1, wy1, wz0), pt(wx1, wy1, wz1), pt(wx0, wy1, wz1)]
    left = [pt(wx0, wy1, wz1), pt(wx1, wy1, wz1), pt(wx1, wy0, wz1), pt(wx0, wy0, wz1)]
    right = [pt(wx1, wy1, wz0), pt(wx1, wy1, wz1), pt(wx1, wy0, wz1), pt(wx1, wy0, wz0)]
    dr.polygon(top, fill=shade(rgb, SHADE["top"]))
    dr.polygon(left, fill=shade(rgb, SHADE["left"]))
    dr.polygon(right, fill=shade(rgb, SHADE["right"]))


def render(voxels, table, flip_x=False, flip_z=False, cut=None):
    """실물 덤프 → 등각 도해. <b>블록의 실제 형태</b>로 그린다."""
    from PIL import Image, ImageDraw

    unknown = {}
    blockshape.UNKNOWN.clear()
    if cut:
        axis, val = cut[0], int(cut[1:])
        voxels = [v for v in voxels
                  if (v[0] if axis == "x" else v[2] if axis == "z" else v[1]) <= val]
    # ★가리는 것은 풀블록뿐이다 — 계단·반블록·트랩도어를 「막힘」으로 세면
    #   그 밑·옆의 풀블록이 지워져 지붕 속이 뚫린 것처럼 보인다 (2026-08-28 실증 —
    #   한옥 지붕 「붕 뜬 공간」 오인의 한 축. air 사건과 같은 병의 다른 얼굴이다)
    occupied = {(x, y, z) for x, y, z, b in voxels
                if blockshape.boxes(b) == blockshape.FULL}

    mx = max(v[0] for v in voxels)
    mz = max(v[2] for v in voxels)
    put = []
    for x, y, z, b in voxels:
        bs = blockshape.boxes(b)
        if not bs:
            continue
        # ★가림 컬링은 <b>풀블록에만</b> 쓴다 — 반블록·계단은 이웃이 있어도 제 모양이 보인다
        if bs == blockshape.FULL and (x + 1, y, z) in occupied \
                and (x, y, z + 1) in occupied and (x, y + 1, z) in occupied:
            continue
        vx = (mx - x) if flip_x else x
        vz = (mz - z) if flip_z else z
        # 뒤집으면 블록 안 좌표도 뒤집어야 한다 — 안 그러면 계단이 <b>반대로</b> 선다
        if flip_x or flip_z:
            bs = [((16 - x1 if flip_x else x0), y0, (16 - z1 if flip_z else z0),
                   (16 - x0 if flip_x else x1), y1, (16 - z0 if flip_z else z1))
                  for x0, y0, z0, x1, y1, z1 in bs]
        put.append((vx, y, vz, b, bs))

    sxs = [(vx - vz) * TW for vx, _, vz, _, _ in put]
    sys_ = [(vx + vz) * TH - y * VH for vx, y, vz, _, _ in put]
    ox = -min(sxs) + TW + 6
    oy = -min(sys_) + 6
    w = int(max(sxs) - min(sxs) + 2 * TW + 12)
    h = int(max(sys_) - min(sys_) + 2 * TH + VH + 12)

    im = Image.new("RGB", (w, h), BG)
    dr = ImageDraw.Draw(im)
    for vx, y, vz, b, bs in sorted(put, key=lambda v: (v[0] + v[2] + v[1])):
        ent = color_of(table, blockshape.parse(b)[0])
        if ent is None:
            unknown[blockshape.parse(b)[0]] = unknown.get(blockshape.parse(b)[0], 0) + 1
            rgb = UNKNOWN_COLOR
        else:
            rgb = tuple(ent["rgb"])
        # 한 블록 안의 상자도 뒤에서 앞으로
        for box in sorted(bs, key=lambda q: q[0] + q[2] + q[1]):
            _prism(dr, ox, oy, box, rgb, vx, y, vz)
    for k, v in blockshape.UNKNOWN.items():
        unknown["형태모름:" + k] = v
    return im, unknown


# ───────────────────────── 눈을 시험하는 눈 ─────────────────────────

def _selftest() -> int:
    fails = []

    def check(name, cond, got=""):
        print(("  ✓ " if cond else "  ✗ ") + name + (f" — {got}" if not cond else ""))
        if not cond:
            fails.append(name)

    tbl = {"stone": {"rgb": [200, 100, 50]}, "dark_oak_planks": {"rgb": [40, 40, 40]}}

    # ① 한 칸은 세 면을 그린다 — 명도 셋이 나와야 입체다
    im, unk = render([(0, 0, 0, "minecraft:stone")], tbl)
    cols = {c for c in im.getdata() if c != BG}
    check("한 칸이 <b>세 면</b>으로 그려진다 (윗면·좌·우)", len(cols) == 3, sorted(cols))
    check("모르는 블록이 없다", not unk, unk)

    # ② 모르는 블록은 <b>자홍색 + 이름</b> — 조용히 안 넘어간다
    im, unk = render([(0, 0, 0, "minecraft:없는블록")], tbl)
    check("모르는 블록을 자홍색으로 칠하고 <b>이름을 센다</b>",
          unk.get("없는블록") == 1 and UNKNOWN_COLOR in set(im.getdata()), unk)

    # ③ ★가려진 칸은 안 그린다 — 안 그러면 1만 칸이 전부 겹쳐 그려진다
    solid = [(x, y, z, "minecraft:stone") for x in range(3) for y in range(3) for z in range(3)]
    im, _ = render(solid, tbl)
    inner_hidden = all(((1, 1, 1) != (x, y, z)) for x, y, z, _ in solid if False)
    # 3×3×3 에서 (0,0,0) 은 세 이웃이 다 있으므로 안 그려져야 한다
    from PIL import Image as _I
    im2, _ = render([v for v in solid if v[:3] != (0, 0, 0)], tbl)
    check("★세 면이 다 막힌 칸은 <b>안 그린다</b> (그림이 같다)",
          list(im.getdata()) == list(im2.getdata()), "")

    # ④ 자른 면 — 경계 밖이 사라진다
    im, _ = render([(0, 0, 0, "minecraft:stone"), (0, 0, 5, "minecraft:stone")], tbl, cut="z0")
    im_one, _ = render([(0, 0, 0, "minecraft:stone")], tbl)
    check("자르면 그 너머가 <b>사라진다</b>", im.size == im_one.size, f"{im.size} vs {im_one.size}")

    # ⑤ 네 귀가 서로 다른 그림이다 (뒤집기가 실제로 듣는다)
    two = [(0, 0, 0, "minecraft:stone"), (4, 0, 0, "minecraft:dark_oak_planks")]
    a, _ = render(two, tbl, *VIEWS["se"])
    b, _ = render(two, tbl, *VIEWS["sw"])
    check("네 귀가 <b>서로 다른 그림</b>이다", list(a.getdata()) != list(b.getdata()), "")

    # ⑥ 위가 밝고 옆이 어둡다 — 입체감의 방향이 뒤집히면 안 된다
    check("윗면이 가장 밝다", SHADE["top"] > SHADE["right"] > SHADE["left"], SHADE)

    # ⑦ ★air 위의 바닥은 그려진다 — 코퍼스 TSV 의 air 행이 컬링을 오염시켰던 회귀
    import tempfile as _tf
    from pathlib import Path as _P
    with _tf.TemporaryDirectory() as td:
        p = _P(td) / "eye.tsv"
        p.write_text("x\ty\tz\tdata\n"
                     "0\t0\t0\tminecraft:stone\n"
                     "0\t1\t0\tminecraft:air\n")
        global DUMP
        old_dump, DUMP = DUMP, _P(td)
        try:
            vox = load_dump("eye")
        finally:
            DUMP = old_dump
    im_air, _ = render(vox, tbl)
    im_bare, _ = render([(0, 0, 0, "minecraft:stone")], tbl)
    check("★air 아래 바닥이 <b>그려진다</b> (air 는 칸이 아니다)",
          list(im_air.getdata()) == list(im_bare.getdata()), "")

    # ⑧ ★계단·반블록은 가리지 못한다 — 세 이웃이 계단이어도 풀블록은 그려진다
    tbl2 = {"stone": {"rgb": [200, 100, 50]}, "oak_stairs": {"rgb": [40, 40, 40]},
            "oak": {"rgb": [40, 40, 40]}}
    core = [(0, 0, 0, "minecraft:stone"),
            (1, 0, 0, "minecraft:oak_stairs[facing=east,half=bottom]"),
            (0, 1, 0, "minecraft:oak_stairs[facing=east,half=bottom]"),
            (0, 0, 1, "minecraft:oak_stairs[facing=east,half=bottom]")]
    im_st, _ = render(core, tbl2)
    im_no, _ = render(core[1:], tbl2)
    check("★계단 이웃은 가리지 못한다 (돌이 보인다)",
          list(im_st.getdata()) != list(im_no.getdata()), "")

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
