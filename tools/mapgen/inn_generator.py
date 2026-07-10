#!/usr/bin/env python3
"""객잔 파라메트릭 생성기 — 맵 생성 파이프라인 M1 (실측 스케일 v1).

config/map_spec/cheongha_hyeon_map.yml 의 cheongha_inn 파라미터
(size / floors / courtyard / back_door / worn)를 읽어
① 블록 버퍼, ② 층별 ASCII 평면도, ③ L4 게임 데이터 바인딩을 출력한다.

스케일: building_standards 기준 — 객잔 25×20 ㅁ자 마당집 2층.
원칙: AI는 블록을 놓지 않는다 — 같은 스펙+시드 = 같은 건물.

사용법: python3 tools/mapgen/inn_generator.py
"""

import os
import json
import random
from collections import Counter

import yaml

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
SPEC_PATH = os.path.join(ROOT, "config", "map_spec", "cheongha_hyeon_map.yml")

FLOOR_H = 4   # 층고 (바닥 포함)

BLOCKS = {
    "floor": "oak_planks", "wall": "spruce_planks", "pillar": "spruce_log",
    "window": "glass_pane", "roof": "dark_oak_planks", "stairs": "oak_stairs",
    "counter": "barrel", "table": "oak_slab", "door": "spruce_door",
    "worn_wall": "mossy_cobblestone", "worn_hole": "air",
    "courtyard": "coarse_dirt", "railing": "spruce_fence",
}


def generate_inn(seed, w, d, floors=2, courtyard=True, back_door=True, worn=0.0):
    """결정론 생성: 같은 인자 = 같은 건물."""
    rng = random.Random(seed)
    blocks = {}

    # ㅁ자 마당 영역 (중앙 개방 — 하늘이 뚫려 있다)
    cy = None
    if courtyard:
        cx0, cx1 = w // 3, w - w // 3          # 25 → 8..16
        cz0, cz1 = d // 3, d - d // 3          # 20 → 6..13
        cy = (cx0, cx1, cz0, cz1)

    def in_courtyard(x, z):
        return cy and cy[0] <= x <= cy[1] and cy[2] <= z <= cy[3]

    def put(x, y, z, key):
        blocks[(x, y, z)] = key

    for f in range(floors):
        base = f * FLOOR_H
        # 바닥 — 마당 위 2층 바닥은 없다 (개방)
        for x in range(w):
            for z in range(d):
                if in_courtyard(x, z):
                    if f == 0:
                        put(x, base, z, "courtyard")
                    continue
                put(x, base, z, "floor")
        # 외벽·기둥·창
        for y in range(base + 1, base + FLOOR_H):
            for x in range(w):
                for z in range(d):
                    if x in (0, w - 1) or z in (0, d - 1):
                        if x in (0, w - 1) and z in (0, d - 1):
                            put(x, y, z, "pillar")
                        elif y == base + 2 and (x % 4 == 2 or z % 4 == 2):
                            put(x, y, z, "window")
                        else:
                            put(x, y, z, "wall")
        # 마당면 회랑 — 1층은 기둥열, 2층은 난간
        if cy:
            cx0, cx1, cz0, cz1 = cy
            for x in range(cx0 - 1, cx1 + 2):
                for z in (cz0 - 1, cz1 + 1):
                    if f == 0 and x % 3 == 0:
                        for y in range(base + 1, base + FLOOR_H):
                            put(x, y, z, "pillar")
                    elif f > 0:
                        put(x, base + 1, z, "railing")
            for z in range(cz0 - 1, cz1 + 2):
                for x in (cx0 - 1, cx1 + 1):
                    if f == 0 and z % 3 == 0:
                        for y in range(base + 1, base + FLOOR_H):
                            put(x, y, z, "pillar")
                    elif f > 0:
                        put(x, base + 1, z, "railing")
        # 낡음
        wall_positions = [p for p, k in blocks.items()
                          if k == "wall" and base < p[1] < base + FLOOR_H]
        for pos in rng.sample(wall_positions, int(len(wall_positions) * worn * 0.12)):
            blocks[pos] = "worn_wall" if rng.random() > 0.3 else "worn_hole"

    # 정문 (남쪽 중앙 — 대문: 폭 2)
    for dx in (0, 1):
        put(w // 2 + dx, 1, 0, "door")
        put(w // 2 + dx, 2, 0, "worn_hole")
    # 뒷문 (북동쪽 — 골목 방향: 잠복·미행 동선)
    back = (w - 5, 1, d - 1)
    if back_door:
        put(back[0], 1, d - 1, "door")
        put(back[0], 2, d - 1, "worn_hole")
    # 계산대 (남쪽 홀 — 정문 우측, 한백의 자리)
    counter = (w // 2 + 4, 2)
    for dx in range(4):
        put(counter[0] + dx, 1, counter[1] + 1, "counter")
    # 계단 (동쪽 날개 — 뒷문 최근접: 묵삼의 밤 동선)
    stairs_x = w - 3
    for i, z in enumerate(range(d - 7, d - 4)):
        for y in range(1, 2 + i):
            put(stairs_x, y, z, "stairs")
    # 1층 남쪽 홀 탁자들
    for tx in range(3, w - 3, 4):
        if not in_courtyard(tx, 3):
            put(tx, 1, 3, "table")
    # 2층 북쪽 날개 객실 (마당 북측 복도 + 방들) — 동북쪽 끝 = 묵삼의 방
    base2 = FLOOR_H
    if floors >= 2 and cy:
        room_z0 = cy[3] + 2                     # 복도 다음부터 방
        for x in range(3, w - 1, 4):            # 4칸 간격 방벽
            for z in range(room_z0, d - 1):
                for y in range(base2 + 1, base2 + FLOOR_H - 1):
                    put(x, y, z, "wall")
    # 지붕 — 마당 위는 뚫려 있다
    roof_y = floors * FLOOR_H
    for x in range(-1, w + 1):
        for z in range(-1, d + 1):
            if not in_courtyard(x, z):
                put(x, roof_y, z, "roof")

    bindings = {
        "npc_anchors": {
            "hanbaek": {"pos": [counter[0] + 1, 1, counter[1]],
                        "note": "1층 남쪽 홀 계산대 — 2층 계단 쪽을 흘끗거림 (routine)"},
            "muksam": {"pos": [w - 4, base2 + 1, d - 3],
                       "note": "2층 동북쪽 끝 객실 — 계단·뒷문 최근접 (밤 동선)"},
        },
        "trigger_zones": {
            "back_alley_exit": {"pos": [back[0], 1, d], "radius": 4,
                                "note": "뒷문 골목 — 잠복(턴4)·미행(턴5) 판정 존"},
            "courtyard": {"pos": [w // 2, 1, d // 2], "radius": 5,
                          "note": "마당 — 낮 손님 동선 / 밤 은신 판정 유리(엄폐)"},
        },
        "broadcast_zones": {
            "inn_hall": {"pos": [w // 2, 1, 3], "radius": 7,
                         "network": "inn_net", "note": "남쪽 홀 — 소문 대사 풀 재생 구역"},
        },
    }
    return blocks, bindings


GLYPH = {"wall": "#", "pillar": "P", "window": "o", "door": "D", "stairs": "S",
         "counter": "C", "table": "t", "floor": ".", "worn_wall": "%", "worn_hole": " ",
         "roof": "^", "courtyard": ",", "railing": "="}


def render_floor(blocks, floor, w, d):
    base = floor * FLOOR_H
    lines = []
    for z in range(d):
        row = ""
        for x in range(w):
            key = blocks.get((x, base + 1, z)) or blocks.get((x, base, z))
            row += GLYPH.get(key, "?") if key else " "
        lines.append(row)
    return "\n".join(lines)


def main():
    with open(SPEC_PATH, encoding="utf-8") as f:
        spec = yaml.safe_load(f)["map_spec"]
    params = spec["locations"]["cheongha_inn"]["params"]
    seed = spec["seed"]
    w, d = params["size"]["w"], params["size"]["d"]
    args = dict(w=w, d=d, floors=params["floors"],
                courtyard=params["courtyard"], back_door=params["back_door"],
                worn=params["worn"])

    blocks, bindings = generate_inn(seed, **args)

    print("=" * 66)
    print(f"청하객잔 생성 (실측 스케일 v1) — seed {seed}")
    print(f"규모: {w}×{d} ㅁ자 마당집 {params['floors']}층 │ "
          f"결정론: {generate_inn(seed, **args)[0] == blocks}")
    print("=" * 66)
    for f_idx in range(params["floors"]):
        label = ("1층 — 남쪽 홀(계산대C·탁자t)·마당(,)·회랑 기둥P·계단S·뒷문D북측"
                 if f_idx == 0 else "2층 — 마당 상부 개방·난간(=)·북쪽 날개 객실 (동북 끝 = 묵삼)")
        print(f"\n[{label}]")
        print(render_floor(blocks, f_idx, w, d))

    counts = Counter(BLOCKS[k] for k in blocks.values())
    print(f"\n블록 통계: 총 {len(blocks):,}개 — "
          + ", ".join(f"{b} {n}" for b, n in counts.most_common(6)) + " …")
    print("\n[L4 게임 데이터 바인딩]")
    print(json.dumps(bindings, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
