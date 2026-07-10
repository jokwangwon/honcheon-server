#!/usr/bin/env python3
"""객잔 파라메트릭 생성기 — 맵 생성 파이프라인 M1 프로토타입.

config/map_spec/cheongha_hyeon_map.yml 의 cheongha_inn 파라미터를 읽어
① 블록 버퍼 (좌표→블록), ② 층별 ASCII 평면도, ③ 게임 데이터 바인딩(L4)을 출력한다.

원칙 (map_generation.md): AI는 블록을 놓지 않는다 — 같은 스펙+시드 = 같은 건물.
MC 월드 기록(amulet/GDPC)은 별도 어댑터의 몫 — 이 단계는 생성 로직의 검증.

사용법: python3 tools/mapgen/inn_generator.py
"""

import os
import json
import random
import yaml

ROOT = os.path.join(os.path.dirname(__file__), "..", "..")
SPEC_PATH = os.path.join(ROOT, "config", "map_spec", "cheongha_hyeon_map.yml")

W, D = 13, 9          # 바닥 크기 (블록)
FLOOR_H = 4           # 층고 (바닥 포함)

BLOCKS = {
    "floor": "oak_planks", "wall": "spruce_planks", "pillar": "spruce_log",
    "window": "glass_pane", "roof": "dark_oak_planks", "stairs": "oak_stairs",
    "counter": "barrel", "table": "oak_slab", "door": "spruce_door",
    "worn_wall": "mossy_cobblestone", "worn_hole": "air",
}


def generate_inn(seed, floors=2, back_door=True, worn=0.0):
    """블록 버퍼와 바인딩을 생성한다. 결정론: 같은 인자 = 같은 결과."""
    rng = random.Random(seed)
    blocks = {}   # (x, y, z) -> block key

    def put(x, y, z, key):
        blocks[(x, y, z)] = key

    for f in range(floors):
        base = f * FLOOR_H
        # 바닥
        for x in range(W):
            for z in range(D):
                put(x, base, z, "floor")
        # 벽·기둥·창 (높이 3)
        for y in range(base + 1, base + FLOOR_H):
            for x in range(W):
                for z in range(D):
                    if x in (0, W - 1) or z in (0, D - 1):
                        if x in (0, W - 1) and z in (0, D - 1):
                            put(x, y, z, "pillar")
                        elif y == base + 2 and (x % 3 == 1 or z % 3 == 1):
                            put(x, y, z, "window")
                        else:
                            put(x, y, z, "wall")
        # 낡음 — 벽 일부가 이끼 끼거나 구멍 (파라미터 worn)
        wall_keys = [p for p, k in blocks.items()
                     if k == "wall" and base < p[1] < base + FLOOR_H]
        for pos in rng.sample(wall_keys, int(len(wall_keys) * worn * 0.15)):
            blocks[pos] = "worn_wall" if rng.random() > 0.3 else "worn_hole"

    # 정문 (남쪽 중앙, 1층)
    front = (W // 2, 1, 0)
    put(front[0], 1, 0, "door")
    put(front[0], 2, 0, "worn_hole")   # 문 상단 개방
    # 뒷문 (북쪽 — 골목 방향, 스펙 placement 요구: 턴 4~5 잠복·미행 동선)
    back = (W - 3, 1, D - 1)
    if back_door:
        put(back[0], 1, D - 1, "door")
        put(back[0], 2, D - 1, "worn_hole")
    # 계산대 (1층 입구 우측 — 한백의 자리)
    counter = (W // 2 + 2, 1, 2)
    for dx in range(3):
        put(counter[0] + dx, 1, counter[1] + 1, "counter")
    # 계단 (동쪽 벽면 — 뒷문과 가까움: 묵삼의 밤 동선)
    stairs_x = W - 2
    for i, z in enumerate(range(2, 2 + 3)):
        for y in range(1, 2 + i):
            put(stairs_x, y, z, "stairs")
    # 1층 탁자들 (객잔 홀)
    for tx, tz in [(2, 3), (2, 6), (5, 5), (8, 6)]:
        put(tx, 1, tz, "table")
    # 2층 객실 4칸 (복도 남측, 방은 북측) — 묵삼의 방 = 동북쪽 끝 (계단·뒷문 최근접)
    room_walls_z = 4
    base2 = FLOOR_H
    for x in range(1, W - 1):
        for y in range(base2 + 1, base2 + FLOOR_H - 1):
            if x % 3 != 0:
                put(x, y, room_walls_z, "wall")
    for divider_x in (3, 6, 9):
        for z in range(room_walls_z + 1, D - 1):
            for y in range(base2 + 1, base2 + FLOOR_H - 1):
                put(divider_x, y, z, "wall")
    # 지붕
    roof_y = floors * FLOOR_H
    for x in range(-1, W + 1):
        for z in range(-1, D + 1):
            put(x, roof_y, z, "roof")

    bindings = {
        "npc_anchors": {
            "hanbaek": {"pos": [counter[0] + 1, 1, counter[1]],
                        "note": "1층 계산대 앞 — 2층 계단 쪽을 흘끗거림 (routine)"},
            "muksam": {"pos": [W - 3, base2 + 1, D - 3],
                       "note": "2층 동북쪽 객실 — 계단·뒷문 최근접 (밤 동선)"},
        },
        "trigger_zones": {
            "back_alley_exit": {"pos": [back[0], 1, D], "radius": 3,
                                "note": "뒷문 골목 — 잠복(턴4)·미행(턴5) 판정 존"},
        },
        "broadcast_zones": {
            "inn_hall": {"pos": [W // 2, 1, D // 2], "radius": 5,
                         "network": "inn_net", "note": "소문 대사 풀 재생 구역"},
        },
    }
    return blocks, bindings


GLYPH = {"wall": "#", "pillar": "P", "window": "o", "door": "D", "stairs": "S",
         "counter": "C", "table": "t", "floor": ".", "worn_wall": "%", "worn_hole": " ",
         "roof": "^"}


def render_floor(blocks, floor):
    """층별 평면도 — 눈높이(y=바닥+1) 우선, 없으면 바닥."""
    base = floor * FLOOR_H
    lines = []
    for z in range(D):
        row = ""
        for x in range(W):
            key = blocks.get((x, base + 1, z)) or blocks.get((x, base, z)) or " "
            row += GLYPH.get(key, "?")
        lines.append(row)
    return "\n".join(lines)


def main():
    with open(SPEC_PATH, encoding="utf-8") as f:
        spec = yaml.safe_load(f)["map_spec"]
    params = spec["locations"]["cheongha_inn"]["params"]
    seed = spec["seed"]

    blocks, bindings = generate_inn(
        seed, floors=params["floors"], back_door=params["back_door"], worn=params["worn"])

    print("=" * 60)
    print(f"청하객잔 생성 — seed {seed}, params {params}")
    print(f"결정론 검증: 동일 시드 재생성 일치 = "
          f"{generate_inn(seed, params['floors'], params['back_door'], params['worn'])[0] == blocks}")
    print("=" * 60)
    for f_idx in range(params["floors"]):
        label = "1층 (홀·계산대C·계단S·정문D남측·뒷문D북측)" if f_idx == 0 \
            else "2층 (객실 4칸 — 동북쪽 끝이 묵삼의 방)"
        print(f"\n[{label}]  ※ #벽 P기둥 o창 %낡음 t탁자")
        print(render_floor(blocks, f_idx))

    from collections import Counter
    counts = Counter(BLOCKS[k] for k in blocks.values())
    print(f"\n블록 통계: 총 {len(blocks)}개 — "
          + ", ".join(f"{b} {n}" for b, n in counts.most_common(5)) + " …")
    print("\n[L4 게임 데이터 바인딩]")
    print(json.dumps(bindings, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
