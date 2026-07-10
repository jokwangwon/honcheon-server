#!/usr/bin/env python3
"""청하현 성내 배치 컴파일러 — 맵 파이프라인 M2 (L3 배치 + L4 바인딩).

map_spec의 스케일·구역·표준표와 건물 생성기들로 현성 전체를 조립한다.
지형(L1)은 평지 가정 — WorldPainter 연동은 M2b.

출력: 배치 통계(건물 수·블록 수), 축소 평면도(1문자=8블록), L4 바인딩 좌표.
사용법: python3 tools/mapgen/town_compiler.py
"""

import os
import sys
import random
from collections import Counter

import yaml

sys.path.insert(0, os.path.dirname(__file__))
import generators as g                       # noqa: E402
from inn_generator import generate_inn, BLOCKS as INN_BLOCKS  # noqa: E402

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
SPEC_PATH = os.path.join(ROOT, "config", "map_spec", "cheongha_hyeon_map.yml")


def compile_town(spec, seed):
    rng = random.Random(seed)
    size = spec["scale"]["town_diameter"]          # 520 — 정방 성곽
    wall_h = spec["scale"]["town_wall"]["height"]
    world = {}
    occupied = set()
    road_cells = set()
    footprints = []                                # (x0,z0,w,d,label)
    bindings = {"npc_anchors": {}, "trigger_zones": {}, "broadcast_zones": {}}

    def stamp(blocks, ox, oz, label=None, meta=None):
        w = max(p[0] for p in blocks) + 1
        d = max(p[2] for p in blocks) + 1
        for (x, y, z), m in blocks.items():
            world[(ox + x, y, oz + z)] = m
        for x in range(ox - 1, ox + w + 1):
            for z in range(oz - 1, oz + d + 1):
                occupied.add((x, z))
        footprints.append((ox, oz, w, d, label))
        if meta:
            for name, pos in meta.get("anchor", {}).items():
                bindings["npc_anchors"][name] = [ox + pos[0], pos[1], oz + pos[2]]
            for name, pos in meta.get("trigger", {}).items():
                bindings["trigger_zones"][name] = [ox + pos[0], pos[1], oz + pos[2]]

    def area_free(x0, z0, w, d):
        if x0 < 6 or z0 < 6 or x0 + w > size - 6 or z0 + d > size - 6:
            return False
        return all((x, z) not in occupied and (x, z) not in road_cells
                   for x in range(x0 - 1, x0 + w + 1)
                   for z in range(z0 - 1, z0 + d + 1))

    # ── 성벽 (정방, 두께 2, 성문 3) ──
    for t in range(2):
        for i in range(size):
            for edge in [(i, t), (i, size - 1 - t), (t, i), (size - 1 - t, i)]:
                for y in range(wall_h):
                    world[(edge[0], y, edge[1])] = "stone_bricks"
                occupied.add(edge)
    gates = {"남문": (size // 2, 0), "북문": (size // 2, size - 1), "동문": (size - 1, size // 2)}
    for name, (gx, gz) in gates.items():
        for off in range(-4, 4):
            for t in range(2):
                x, z = (gx + off, gz + (t if gz == 0 else -t)) if gz in (0, size - 1) \
                    else (gx - t, gz + off)
                for y in range(wall_h):
                    world.pop((x, y, z), None)
                road_cells.add((x, z))
        bindings["trigger_zones"][name] = [gx, 0, gz]

    # ── 도로망 (지면 1층) ──
    def road(x0, z0, x1, z1, width):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for z in range(min(z0, z1), max(z0, z1) + 1):
                for wx in range(width):
                    for wz in range(width if x0 == x1 else 1):
                        cell = (x + (wx if z0 != z1 else 0) - width // 2,
                                z + (wx if z0 == z1 else 0) - width // 2)
                        world[(cell[0], 0, cell[1])] = "dirt_path"
                        road_cells.add(cell)

    mid = size // 2
    road(mid, 2, mid, size - 3, 8)               # 남북 대로 (남문↔북문)
    road(mid, mid, size - 3, mid, 8)             # 동서 대로 (중심↔동문)
    for sx in (100, 180, 340, 420):              # 거리 격자 (폭 5)
        road(sx, 60, sx, size - 60, 5)
    for sz in (100, 180, 340, 420):
        road(60, sz, size - 60, sz, 5)

    # ── 주요 건물 (구역 지정 배치) ──
    blocks, meta = g.gwana(random.Random(rng.random()))
    stamp(blocks, mid - 20, mid + 20, "관아", meta)             # town_center 북측
    blocks, meta = g.office(random.Random(rng.random()))
    stamp(blocks, mid + 30, mid - 20, "의뢰소", meta)           # 광장 동측
    blocks, meta = g.clinic(random.Random(rng.random()))
    stamp(blocks, 150, 250, "의원", meta)                       # market 서측
    inn_blocks, inn_bind = generate_inn(seed, w=25, d=20, floors=2,
                                        courtyard=True, back_door=True, worn=0.4)
    inn_o = (350, 350)                                          # inn_quarter 동남
    stamp({p: INN_BLOCKS[k] for p, k in inn_blocks.items()}, *inn_o, "객잔")
    for group in ("npc_anchors", "trigger_zones", "broadcast_zones"):
        for name, v in inn_bind[group].items():
            pos = v["pos"]
            bindings[group][name] = [inn_o[0] + pos[0], pos[1], inn_o[1] + pos[2]]
    blocks, meta = g.warehouse(random.Random(rng.random()))
    stamp(blocks, 352, 375, "골목창고", meta)                    # 객잔 북쪽 골목 건너

    # ── 민가·상점 채우기 (도로변 필지) ──
    counts = Counter()
    for sx in (100, 180, 340, 420, mid):
        for z in range(70, size - 70, 13):
            for side in (-14, 6):
                x0 = sx + side
                kind = "상점" if rng.random() < 0.2 else "민가"
                w, d = (11, 9) if kind == "상점" else (9, 8)
                if area_free(x0, z, w, d):
                    gen = g.shop if kind == "상점" else g.house
                    blocks, meta = gen(random.Random(rng.random()))
                    stamp(blocks, x0, z, kind, None)
                    counts[kind] += 1
    return world, footprints, bindings, counts


def render(world, footprints, size, scale=8):
    """축소 평면도 — 1문자 = scale×scale 블록."""
    n = size // scale
    grid = [["." for _ in range(n)] for _ in range(n)]
    for (x, y, z), m in world.items():
        if not (0 <= x < size and 0 <= z < size):
            continue
        gx, gz = x // scale, z // scale
        if m == "stone_bricks":
            grid[gz][gx] = "W"
        elif m == "dirt_path" and grid[gz][gx] == ".":
            grid[gz][gx] = "="
    letters = {"관아": "O", "의뢰소": "R", "의원": "M", "객잔": "I", "골목창고": "S"}
    for x0, z0, w, d, label in footprints:
        ch = letters.get(label, "#")
        for x in range(x0, x0 + w, scale):
            for z in range(z0, z0 + d, scale):
                grid[z // scale][x // scale] = ch
    return "\n".join("".join(row) for row in grid)


def main():
    with open(SPEC_PATH, encoding="utf-8") as f:
        spec = yaml.safe_load(f)["map_spec"]
    size = spec["scale"]["town_diameter"]
    seed = spec["seed"]

    world, footprints, bindings, counts = compile_town(spec, seed)
    world2 = compile_town(spec, seed)[0]

    print("=" * 66)
    print(f"청하현 성내 컴파일 (M2 배치기) — seed {seed}, 성곽 {size}×{size}")
    print(f"결정론 검증: {world == world2}")
    print("=" * 66)
    total = len(world)
    mat = Counter(world.values())
    building_total = counts["민가"] + counts["상점"] + 5
    print(f"\n건물 수: 총 {building_total}채 — 민가 {counts['민가']}, 상점 {counts['상점']}, "
          f"주요 건물 5 (관아·의뢰소·의원·객잔·골목창고)")
    print(f"블록 수: 총 {total:,}개")
    print("  상위 재질: " + ", ".join(f"{m} {n:,}" for m, n in mat.most_common(6)))
    print(f"\n[성내 평면도 — 1문자=8블록] W성벽 =도로 #민가·상점 O관아 R의뢰소 M의원 I객잔 S골목창고")
    print(render(world, footprints, size))
    print(f"\n[L4 바인딩 — 세계 좌표 (총 {sum(len(v) for v in bindings.values())}건)]")
    for name, pos in list(bindings["npc_anchors"].items()):
        print(f"  앵커 {name}: {pos}")
    for name, pos in list(bindings["trigger_zones"].items())[:6]:
        print(f"  존   {name}: {pos if isinstance(pos, list) else pos}")


if __name__ == "__main__":
    main()
