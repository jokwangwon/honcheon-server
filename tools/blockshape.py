#!/usr/bin/env python3
"""블록 <b>형태</b> — 계단·반블록·트랩도어를 정육면체로 안 그리기 위한 것.

    python3 tools/blockshape.py --selftest
    python3 tools/blockshape.py "minecraft:dark_oak_stairs[facing=east,half=bottom,shape=straight]"

왜 있나
-------
3D 미리보기 첫 판은 모든 블록을 <b>정육면체</b>로 그렸다. 그래서
· 반블록으로 만든 도리가 <b>통짜 보</b>로 보였고
· 계단으로 짠 공포·처마가 <b>네모 덩어리</b>가 됐고
· 트랩도어 살창이 <b>꽉 찬 벽</b>이 됐다
— 즉 <b>우리가 형태로 만든 것을 형태 없이</b> 그렸다. 그러면 3D 로 볼 이유가 없다.

여기서는 블록 하나를 <b>상자 목록</b>으로 준다. 좌표는 마크와 같은 0..16 이다.
(마크의 모델도 이 단위를 쓴다 — 사람이 견주기 쉽다.)

정직한 한계
----------
* 마크의 <b>모든</b> 블록 모양을 담지 않는다. 우리 건물이 쓰는 것을 담고,
  <b>모르는 블록은 정육면체로 그리되 이름을 세어 보고한다</b> — 조용히 안 넘어간다.
* 담장(wall)·울타리(fence)의 이음은 상태에 적힌 대로 낸다. 마크의 실제 판정과
  다를 수 있으나, 우리가 보는 것은 <b>도해</b>이므로 이음의 유무만 맞으면 된다.
"""
from __future__ import annotations

import sys

# 못 그린 블록 이름 — 부르는 쪽이 보고한다
UNKNOWN: dict[str, int] = {}

FULL = [(0, 0, 0, 16, 16, 16)]


def parse(data: str):
    """`minecraft:이름[a=b,c=d]` → (이름, {a: b})"""
    s = data.strip()
    if s.startswith("minecraft:"):
        s = s[len("minecraft:"):]
    props = {}
    if "[" in s:
        s, rest = s.split("[", 1)
        for kv in rest.rstrip("]").split(","):
            if "=" in kv:
                k, v = kv.split("=", 1)
                props[k] = v
    return s, props


def _stairs(p):
    """계단 — 밑판(반블록) + 윗단. `shape` 가 안·바깥 모서리를 만든다."""
    half = p.get("half", "bottom")
    facing = p.get("facing", "north")
    shape = p.get("shape", "straight")
    base = (0, 0, 0, 16, 8, 16) if half == "bottom" else (0, 8, 0, 16, 16, 16)
    ty0, ty1 = (8, 16) if half == "bottom" else (0, 8)

    # ★윗단은 <b>facing 쪽</b>에 얹힌다 — 바닐라 모델이 그렇다.
    #   (`block/stairs.json` 의 둘째 상자가 [8,8,0]~[16,16,16] = +x 이고,
    #    blockstate 는 `facing=east` 를 회전 0 으로 둔다. 즉 east → +x.)
    #   처음엔 반대로 적었고, 그러자 지붕이 <b>빗살처럼 틈이 벌어져</b> 보였다 —
    #   계단 층이 서로 안 맞물렸기 때문이다. 실물은 막혀 있는데 도해가 거짓말을 했다.
    def slab_for(dirn):
        return {"north": (0, ty0, 0, 16, ty1, 8),
                "south": (0, ty0, 8, 16, ty1, 16),
                "east":  (8, ty0, 0, 16, ty1, 16),
                "west":  (0, ty0, 0, 8, ty1, 16)}[dirn]

    def quad(dx, dz):
        x0 = 8 if dx > 0 else 0
        z0 = 8 if dz > 0 else 0
        return (x0, ty0, z0, x0 + 8, ty1, z0 + 8)

    left = {"north": "east", "east": "south", "south": "west", "west": "north"}[facing]
    right = {"north": "west", "west": "south", "south": "east", "east": "north"}[facing]
    step = [slab_for(facing)]
    if shape.startswith("outer"):
        # 바깥 모서리 — 윗단이 <b>4분의 1</b>만 남는다
        d = left if shape.endswith("left") else right
        vec = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
        fx, fz = vec[facing]
        gx, gz = vec[d]
        step = [quad(-(fx + gx) or -1 if (fx + gx) == 0 else -(fx + gx),
                     -(fz + gz) or -1 if (fz + gz) == 0 else -(fz + gz))]
        step = [quad(-(fx or gx), -(fz or gz))]
    elif shape.startswith("inner"):
        d = left if shape.endswith("left") else right
        step = [slab_for(facing), slab_for(d)]
    return [base] + step


def _slab(p):
    t = p.get("type", "bottom")
    if t == "double":
        return FULL
    return [(0, 0, 0, 16, 8, 16)] if t == "bottom" else [(0, 8, 0, 16, 16, 16)]


def _trapdoor(p):
    """트랩도어 — 두께 3. 열려 있으면 <b>세운다</b> (우리 살창이 그렇다)."""
    if p.get("open") == "true":
        f = p.get("facing", "north")
        return {"north": [(0, 0, 13, 16, 16, 16)], "south": [(0, 0, 0, 16, 16, 3)],
                "east":  [(0, 0, 0, 3, 16, 16)],   "west":  [(13, 0, 0, 16, 16, 16)]}[f]
    return [(0, 13, 0, 16, 16, 16)] if p.get("half") == "top" else [(0, 0, 0, 16, 3, 16)]


def _wall(p):
    """담장 — 가운데 기둥 + 이어진 쪽으로 팔. `up=false` 면 기둥이 낮다."""
    hi = 16 if p.get("up", "true") == "true" else 14
    out = [(4, 0, 4, 12, hi, 12)]
    arm = {"north": (5, 0, 0, 11, 14, 4), "south": (5, 0, 12, 11, 14, 16),
           "west": (0, 0, 5, 4, 14, 11), "east": (12, 0, 5, 16, 14, 11)}
    for d, box in arm.items():
        if p.get(d, "none") != "none":
            out.append(box)
    return out


def _fence(p):
    out = [(6, 0, 6, 10, 16, 10)]
    arm = {"north": (7, 0, 0, 9, 15, 6), "south": (7, 0, 10, 9, 15, 16),
           "west": (0, 0, 7, 6, 15, 9), "east": (10, 0, 7, 16, 15, 9)}
    for d, box in arm.items():
        if p.get(d) == "true":
            out.append(box)
    return out


def _lantern(p):
    y0 = 1 if p.get("hanging") == "true" else 0
    return [(5, y0, 5, 11, y0 + 7, 11), (6, y0 + 7, 6, 10, y0 + 9, 10)]


def _banner(p):
    f = p.get("facing", "north")
    return {"north": [(0, 0, 14, 16, 12, 16)], "south": [(0, 0, 0, 16, 12, 2)],
            "east":  [(0, 0, 0, 2, 12, 16)],   "west":  [(14, 0, 0, 16, 12, 16)]}[f]


def boxes(data: str):
    """블록 하나 → <b>상자 목록</b> (0..16 단위)."""
    name, p = parse(data)
    if name == "air":
        return []
    if name.endswith("_stairs"):
        return _stairs(p)
    if name.endswith("_slab"):
        return _slab(p)
    if name.endswith("_trapdoor"):
        return _trapdoor(p)
    if name.endswith("_wall") and not name.endswith("_wall_banner"):
        return _wall(p)
    if name.endswith("_fence"):
        return _fence(p)
    if name in ("lantern", "soul_lantern"):
        return _lantern(p)
    if name.endswith("_banner"):
        return _banner(p)
    if name.endswith("_carpet") or name == "snow":
        return [(0, 0, 0, 16, 1, 16)]
    if name.endswith("_pane") or name == "iron_bars":
        return [(7, 0, 0, 9, 16, 16), (0, 0, 7, 16, 16, 9)]
    if name.endswith("_sign") or name.endswith("_hanging_sign"):
        return [(0, 0, 7, 16, 16, 9)]
    if name.endswith("_door"):
        return [(0, 0, 0, 16, 16, 3)]
    # ★모르는 블록 — 정육면체로 그리되 <b>이름을 센다</b>
    if name not in _KNOWN_FULL:
        UNKNOWN[name] = UNKNOWN.get(name, 0) + 1
    return FULL


# 「정육면체가 맞다」고 <b>확인한</b> 이름들 — 여기 없으면 보고에 오른다
_KNOWN_FULL = {
    "smooth_stone", "stone", "stone_bricks", "deepslate_tiles", "deepslate_bricks",
    "cracked_deepslate_tiles", "polished_andesite", "andesite", "red_terracotta",
    "bone_block", "dark_oak_planks", "dark_oak_log", "mangrove_planks", "mangrove_log",
    "stripped_mangrove_log", "jungle_planks", "spruce_planks", "warped_planks",
    "red_nether_bricks", "dirt_path", "grass_block", "dirt", "cobblestone",
    "gravel", "sand", "water", "lava", "cherry_leaves", "cherry_log", "moss_block",
}


def _selftest() -> int:
    fails = []

    def check(name, cond, got=""):
        print(("  ✓ " if cond else "  ✗ ") + name + (f" — {got}" if not cond else ""))
        if not cond:
            fails.append(name)

    def vol(bs):
        return sum((x1 - x0) * (y1 - y0) * (z1 - z0) for x0, y0, z0, x1, y1, z1 in bs)

    check("이름·상태를 가른다", parse("minecraft:oak_stairs[facing=east,half=top]")
          == ("oak_stairs", {"facing": "east", "half": "top"}))
    check("공기는 <b>아무것도</b> 아니다", boxes("minecraft:air") == [])
    check("풀블록은 4096", vol(boxes("minecraft:stone")) == 4096)

    # ★반블록은 <b>절반</b>이다 — 도리가 통짜 보로 보이던 병
    b = boxes("minecraft:dark_oak_slab[type=bottom]")
    check("★반블록(아래)은 절반이고 <b>아래</b>에 있다",
          vol(b) == 2048 and b[0][1] == 0 and b[0][4] == 8, b)
    bt = boxes("minecraft:dark_oak_slab[type=top]")
    check("★반블록(위)은 <b>위</b>에 있다", bt[0][1] == 8 and bt[0][4] == 16, bt)
    check("겹반블록은 풀블록이다", vol(boxes("minecraft:dark_oak_slab[type=double]")) == 4096)

    # ★계단은 <b>두 덩이</b> — 밑판 + 윗단. 부피는 4분의 3
    st = boxes("minecraft:dark_oak_stairs[facing=north,half=bottom,shape=straight]")
    check("★계단은 두 상자다 (밑판 + 윗단)", len(st) == 2, st)
    check("★계단 부피는 <b>4분의 3</b>", vol(st) == 3072, vol(st))
    check("★계단 윗단은 <b>facing 쪽</b>에 있다 (북향이면 북쪽 절반 — 바닐라 모델대로)",
          st[1][2] == 0 and st[1][5] == 8, st[1])
    se = boxes("minecraft:dark_oak_stairs[facing=east,half=bottom,shape=straight]")
    check("동향이면 윗단이 <b>동쪽</b> 절반", se[1][0] == 8 and se[1][3] == 16, se[1])
    si = boxes("minecraft:dark_oak_stairs[facing=north,half=bottom,shape=inner_left]")
    check("안 모서리는 윗단이 <b>둘</b> (부피가 더 크다)", vol(si) > vol(st), (vol(si), vol(st)))
    so = boxes("minecraft:dark_oak_stairs[facing=north,half=bottom,shape=outer_left]")
    check("바깥 모서리는 윗단이 <b>4분의 1</b> (부피가 더 작다)", vol(so) < vol(st),
          (vol(so), vol(st)))

    # ★열린 트랩도어는 <b>세운 판</b> — 살창이 꽉 찬 벽으로 보이던 병
    tr = boxes("minecraft:dark_oak_trapdoor[open=true,facing=north,half=bottom]")
    check("★열린 트랩도어는 <b>얇고 세워져</b> 있다",
          tr[0][4] - tr[0][1] == 16 and tr[0][5] - tr[0][2] == 3, tr)
    tc = boxes("minecraft:dark_oak_trapdoor[open=false,half=bottom]")
    check("닫힌 트랩도어는 <b>눕는다</b>", tc[0][4] - tc[0][1] == 3, tc)

    # 담장·울타리 — 이음이 상태를 따른다
    w0 = boxes("minecraft:stone_brick_wall[up=true,north=none,south=none,east=none,west=none]")
    w2 = boxes("minecraft:stone_brick_wall[up=true,north=low,south=low,east=none,west=none]")
    check("담장은 이음이 없으면 <b>기둥 하나</b>", len(w0) == 1, w0)
    check("★담장은 이어진 쪽으로 <b>팔을 낸다</b>", len(w2) == 3, len(w2))
    f2 = boxes("minecraft:dark_oak_fence[north=true,south=false,east=false,west=false]")
    check("울타리도 이음을 따른다", len(f2) == 2, len(f2))

    # 등롱·배너
    check("등롱은 <b>작다</b> (풀블록의 4분의 1 미만)", vol(boxes("minecraft:lantern")) < 1024)
    check("배너는 얇은 판이다",
          vol(boxes("minecraft:blue_wall_banner[facing=south]")) < 800)

    # ★모르는 블록은 <b>이름을 센다</b>
    UNKNOWN.clear()
    boxes("minecraft:some_unknown_block")
    check("★모르는 블록은 정육면체로 그리되 <b>이름을 센다</b>",
          UNKNOWN == {"some_unknown_block": 1}, UNKNOWN)
    UNKNOWN.clear()
    boxes("minecraft:smooth_stone")
    check("확인된 풀블록은 보고에 <b>안</b> 오른다", not UNKNOWN, UNKNOWN)

    print(f"\n블록 형태의 눈 — {'통과' if not fails else f'실패 {len(fails)}: {fails}'}")
    return 1 if fails else 0


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(_selftest())
    for a in sys.argv[1:]:
        print(a, "→", boxes(a))
