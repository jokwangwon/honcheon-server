#!/usr/bin/env python3
"""건물 파라메트릭 생성기 모음 — building_standards 표준 크기 구현.

모든 생성기는 (blocks, meta)를 반환한다. blocks: (x,y,z) → 재질명.
결정론: 같은 rng 시드 = 같은 건물. (map_generation.md 원칙)
"""

FLOOR_H = 4


def box_building(rng, w, d, floors=1, worn=0.2, door_width=1,
                 wall="spruce_planks", floor="oak_planks", roof="dark_oak_planks"):
    """기본 상자형 건물 — 민가·상점·창고의 뼈대."""
    blocks = {}

    def put(x, y, z, m):
        blocks[(x, y, z)] = m

    for f in range(floors):
        base = f * FLOOR_H
        for x in range(w):
            for z in range(d):
                put(x, base, z, floor)
        for y in range(base + 1, base + FLOOR_H):
            for x in range(w):
                for z in range(d):
                    if x in (0, w - 1) or z in (0, d - 1):
                        if x in (0, w - 1) and z in (0, d - 1):
                            put(x, y, z, "spruce_log")
                        elif y == base + 2 and (x % 3 == 1 or z % 3 == 1):
                            put(x, y, z, "glass_pane")
                        else:
                            put(x, y, z, wall)
    # 정문 (남쪽)
    for dx in range(door_width):
        put(w // 2 + dx, 1, 0, "spruce_door")
        put(w // 2 + dx, 2, 0, "air")
    # 낡음
    walls = [p for p, m in blocks.items() if m == wall]
    for pos in rng.sample(walls, int(len(walls) * worn * 0.1)):
        blocks[pos] = "mossy_cobblestone"
    # 지붕
    for x in range(w):
        for z in range(d):
            put(x, floors * FLOOR_H, z, roof)
    return blocks


def house(rng, worn=0.3):
    """민가 9×8 단층."""
    return box_building(rng, 9, 8, worn=worn), {"kind": "민가", "w": 9, "d": 8}


def shop(rng, worn=0.25):
    """상점 11×9 — 진열대."""
    blocks = box_building(rng, 11, 9, worn=worn)
    for dx in range(3):
        blocks[(3 + dx, 1, 2)] = "barrel"
    return blocks, {"kind": "상점", "w": 11, "d": 9}


def clinic(rng, worn=0.2):
    """의원 15×12 — 진료방·병상 (유문의 자리)."""
    blocks = box_building(rng, 15, 12, worn=worn)
    for z in range(7, 10):          # 병상 열 (열병 환자들 — 사건 무대)
        blocks[(2, 1, z)] = "white_bed"
        blocks[(5, 1, z)] = "white_bed"
    blocks[(11, 1, 8)] = "barrel"   # 약장
    return blocks, {"kind": "의원", "w": 15, "d": 12,
                    "anchor": {"yumun": [11, 1, 7]}}


def warehouse(rng, worn=0.5):
    """창고 12×9 — 뒷문 (북쪽 골목 창고: 흑랑 접선 무대)."""
    blocks = box_building(rng, 12, 9, worn=worn, wall="oak_planks")
    blocks[(9, 1, 8)] = "spruce_door"   # 뒷문 — 조사/엿듣기 상호작용 지점
    blocks[(9, 2, 8)] = "air"
    return blocks, {"kind": "창고", "w": 12, "d": 9,
                    "trigger": {"warehouse_backdoor": [9, 1, 9]}}


def gwana(rng):
    """관아 40×35 — 담장 구획 + 정청."""
    blocks = {}
    w, d = 40, 35
    for x in range(w):              # 담장 (높이 3)
        for z in range(d):
            if x in (0, w - 1) or z in (0, d - 1):
                for y in range(3):
                    blocks[(x, y, z)] = "mud_bricks"
    for dx in range(3):             # 정문 (남쪽, 폭 3)
        for y in range(3):
            blocks[(w // 2 + dx - 1, y, 0)] = "air" if y else "spruce_door"
    inner = box_building(rng, 20, 15, worn=0.0, door_width=2,
                         wall="white_terracotta", roof="deepslate_tiles")
    for (x, y, z), m in inner.items():
        blocks[(x + 10, y, z + 10)] = m
    return blocks, {"kind": "관아", "w": w, "d": d,
                    "anchor": {"현령": [20, 1, 17]}}


def office(rng, worn=0.2):
    """의뢰소 11×9 — 게시판 (소연의 자리)."""
    blocks = box_building(rng, 11, 9, worn=worn)
    for dx in range(4):
        blocks[(3 + dx, 2, 1)] = "oak_sign"    # 의뢰 게시판
    blocks[(8, 1, 2)] = "barrel"               # 접수대
    return blocks, {"kind": "의뢰소", "w": 11, "d": 9,
                    "anchor": {"soyeon": [8, 1, 3]}}
