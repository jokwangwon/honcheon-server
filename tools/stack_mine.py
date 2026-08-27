#!/usr/bin/env python3
"""쌓임 캐기 — 덤프(TSV)에서 수직 부재열 문법을 캔다 (SYSTEM_REVIEW ⑥ · E-2).

  python3 tools/stack_mine.py run/mvt-test/dump/hwasan_honjeon.tsv
  python3 tools/stack_mine.py run/mvt-test/dump/*.tsv --top 30
  python3 tools/stack_mine.py --selftest

무엇을 하나:
  (x,z) 기둥마다 y 오름차순 블록열을 뽑되, **층을 갈라** 몸통 문법만 남긴다 —
  날것 그대로 세면 지형·지붕 기둥이 상위를 덮는다 (2026-08-27 첫 실증의 교훈).

  아래 자름   기단·포장 재료(base)의 연속 구간을 바닥에서 걷는다
  위 자름     긴보(plate) — 사전(techniques.yml)이 「몸통과 지붕을 가르는 정본 경계」로
             정한 그 켜에서 자른다 (긴보 포함 — 사전의 쌓임이 긴보로 끝나므로)
  plate 없음  몸통 없는 기둥(지형·지붕뿐·소품)으로 따로 센다 — 조용히 안 버린다

★경계 재료는 손으로 안 적는다 — techniques.yml 의 layer 가 정본이다 (base·plate).
  단 사전의 재료가 자리표시자($post)거나 추상명(lattice·plaster)일 수 있어,
  실블록이 아닌 것은 세어서 보고한다. 포장 재료(stone_bricks 계열)만 여기 더한다.
★몸통 안의 air 는 버리지 않는다 — 그것이 개구(빈칸) 부재다.
"""

from __future__ import annotations

import argparse
import collections
import csv
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TECHNIQUES = ROOT / "config" / "techniques.yml"

# 포장·지반은 사전 밖의 캠퍼스 계약(패드)이라 여기서만 더한다.
PAVING = {"stone_bricks", "stone_brick_slab", "stone_brick_stairs", "stone_brick_wall"}


def load_boundaries(path: Path = TECHNIQUES) -> tuple[set[str], set[str], list[str]]:
    """사전에서 (base 재료, plate 재료, 실블록이 아닌 재료 이름들)을 뽑는다."""
    import yaml

    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    base: set[str] = set()
    plate: set[str] = set()
    abstract: list[str] = []
    for name, m in doc["members"].items():
        mat = str(m["material"])
        if mat.startswith("$") or mat in ("lattice", "plaster", "air"):
            if mat != "air":
                abstract.append(f"{name}={mat}")
            continue
        if m.get("layer") == "base":
            base.add(mat)
        if m.get("layer") == "plate":
            plate.add(mat)
    return base | PAVING, plate, abstract


def read_dump(path: Path) -> dict[tuple[int, int], dict[int, str]]:
    cols: dict[tuple[int, int], dict[int, str]] = collections.defaultdict(dict)
    with open(path, encoding="utf-8") as f:
        rd = csv.reader(f, delimiter="\t")
        header = next(rd)
        if header[:4] != ["x", "y", "z", "data"]:
            sys.exit(f"덤프 머리가 다르다: {header} — 상태 없는 옛 .csv 인가?")
        for x, y, z, data in rd:
            blk = data.split("[")[0]
            blk = blk.removeprefix("minecraft:")
            cols[(int(x), int(z))][int(y)] = blk
    return cols


def body_of(col: dict[int, str], base: set[str], plate: set[str]) -> tuple[str, ...] | None:
    """한 기둥의 몸통 구간(기단 위 ~ 긴보 포함)을 런 압축해 돌려준다. 없으면 None."""
    ys = sorted(col)
    blocks = [col[y] for y in ys]

    # 위 자름 — 가장 높은 plate. 없으면 몸통 없는 기둥이다.
    plate_i = max((i for i, b in enumerate(blocks) if b in plate), default=-1)
    if plate_i < 0:
        return None

    # 아래 자름 — 바닥의 base 연속 구간만 걷는다. air 는 걷지 않는다:
    # air 는 개구(빈칸) 부재라, 걷으면 개구 쌓임이 파괴된다.
    lo = 0
    while lo < plate_i and blocks[lo] in base:
        lo += 1

    seg = blocks[lo : plate_i + 1]
    if not seg:
        return None
    return _runs(seg)


def _runs(seg: list[str]) -> tuple[str, ...]:
    runs: list[list] = []
    for b in seg:
        if runs and runs[-1][0] == b:
            runs[-1][1] += 1
        else:
            runs.append([b, 1])
    return tuple(f"{b}*{n}" if n > 1 else b for b, n in runs)


# ── 경계 자동 탐지 — 이름을 묻지 않고 구조를 묻는다 (E-2 확장 · 2026-08-28) ──
#   외부 코퍼스는 우리 사전의 재료를 안 쓴다 (한옥마을에서 몸통 0 실측).
#   그래서 경계를 코퍼스 스스로에게 묻는다:
#     지면  = 기둥 꼭대기 높이의 최빈값 (탁 트인 땅이 가장 넓다)
#     기단  = 지면 이하 층에서 넓게 깔린 재료 (한 층의 30% 이상)
#     지붕  = 지면보다 ROOF_MIN 이상 솟은 기둥들의 꼭대기 재료 (누적 90%)
#   ★한계 (정직하게): 상자가 건물로 꽉 차면 최빈 높이가 지붕이 된다 —
#     상자는 건물 둘레의 땅을 여유 있게 포함해야 한다. 탐지값을 항상 인쇄한다.

ROOF_MIN = 4        # 지면보다 이만큼 솟아야 「지붕 있는 기둥」이다
BASE_COVER = 0.30   # 한 층의 이 비율 이상 깔리면 기단·지반 재료다
ROOF_CUM = 0.90     # 지붕 재료는 꼭대기 재료의 누적 이 비율까지
ROOF_SHARE = 0.02   # 그리고 낱개로 이 비율은 넘어야 한다 (나무 한 그루가 안 들어오게)
GROUND_SHARE = 0.05  # 지붕 없는 기둥의 꼭대기에서 이 비율을 넘으면 땅 표면 재료다
#   (거리 포장이 andesite·stone·cobble 로 갈려 층 점유 30% 에 못 미쳐도,
#    「트인 기둥의 꼭대기」로는 흔하다 — 한옥마을 실측에서 잡은 소음)


def detect_boundaries(cols: dict) -> tuple[int, set[str], set[str]]:
    tops = {}
    for key, col in cols.items():
        for y in sorted(col, reverse=True):
            if col[y] != "air":
                tops[key] = (y, col[y])
                break
    if not tops:
        sys.exit("빈 덤프다 — 기둥 꼭대기가 하나도 없다")
    n = len(tops)

    yg = collections.Counter(t[0] for t in tops.values()).most_common(1)[0][0]

    layer: dict[int, collections.Counter] = collections.defaultdict(collections.Counter)
    for col in cols.values():
        for y, b in col.items():
            if y <= yg and b != "air":
                layer[y][b] += 1
    base = {b for cnt in layer.values() for b, c in cnt.items() if c >= BASE_COVER * n}

    roofed = collections.Counter(m for y, m in tops.values() if y >= yg + ROOF_MIN)
    roof: set[str] = set()
    if roofed:
        total = sum(roofed.values())
        cum = 0
        for m, c in roofed.most_common():
            if cum >= ROOF_CUM * total or c < ROOF_SHARE * total:
                break
            roof.add(m)
            cum += c

    # 트인 기둥(지붕 없음)의 꼭대기 재료 = 땅 표면 — 기단에 더한다.
    # 포장이 여러 갈래(안산암·돌·자갈)로 갈려 층 점유 30% 에 못 미쳐도 여기서 잡힌다.
    open_tops = collections.Counter(m for y, m in tops.values() if y < yg + ROOF_MIN)
    if open_tops:
        total = sum(open_tops.values())
        base |= {m for m, c in open_tops.items() if c >= GROUND_SHARE * total}
    return yg, base, roof


def body_of_auto(col: dict[int, str], base: set[str], roof: set[str]) -> tuple[str, ...] | None:
    """지붕을 위에서 걷고, 기단을 아래에서 걷고, 남은 몸통을 돌려준다."""
    ys = sorted(col)
    blocks = [col[y] for y in ys]
    hi = len(blocks) - 1
    while hi >= 0 and (blocks[hi] == "air" or blocks[hi] in roof):
        hi -= 1
    lo = 0
    while lo <= hi and blocks[lo] in base:
        lo += 1
    if lo > hi:
        return None
    return _runs(blocks[lo : hi + 1])


def mine(paths: list[Path], auto: bool = False) -> tuple[collections.Counter, int, int]:
    counter: collections.Counter = collections.Counter()
    bodies = terrain = 0
    if not auto:
        base, plate, abstract = load_boundaries()
        if abstract:
            print(f"[고지] 사전의 실블록 아닌 재료 (경계에 못 쓴다): {', '.join(abstract)}")
    for p in paths:
        cols = read_dump(p)
        if auto:
            yg, base, roof = detect_boundaries(cols)
            print(f"[{p.stem} 탐지] 지면 y{yg} · 기단 {len(base)}종 "
                  f"{sorted(base)[:6]}{'…' if len(base) > 6 else ''} · "
                  f"지붕 {len(roof)}종 {sorted(roof)[:6]}{'…' if len(roof) > 6 else ''}")
        for _key, col in cols.items():
            s = body_of_auto(col, base, roof) if auto else body_of(col, base, plate)
            if s is None:
                terrain += 1
            else:
                bodies += 1
                counter[s] += 1
    return counter, bodies, terrain


# ── 눈 (selftest) ──────────────────────────────────────────────────────────

def _col(*blocks: str) -> dict[int, str]:
    return {y: b for y, b in enumerate(blocks)}


def selftest() -> int:
    base, plate, abstract = load_boundaries()
    fails = []
    ran = [0]

    def eye(name, cond):
        ran[0] += 1
        print(("  ✓ " if cond else "  ✗ ") + name)
        if not cond:
            fails.append(name)

    # ① 사전에서 경계가 나온다 — 긴보가 plate 다
    eye("사전의 plate 가 비어 있지 않다", bool(plate))
    eye("사전의 base 가 포장을 포함한다", "stone_bricks" in base)

    # ② 몸통 절단 — 기단은 걷히고 긴보에서 끝나며 지붕은 없다
    c = _col("stone_bricks", "smooth_stone", "smooth_stone",
             "red_terracotta", "red_terracotta", "red_terracotta",
             "mangrove_planks", "dark_oak_slab", "dark_oak_log",
             "deepslate_tiles", "deepslate_tiles")
    got = body_of(c, base, plate)
    eye("몸통 = 몸통…긴보 (기단·지붕 없음)",
        got == ("red_terracotta*3", "mangrove_planks", "dark_oak_slab", "dark_oak_log"))

    # ③ plate 없는 기둥(지형)은 None — 조용히 스택으로 세지 않는다
    eye("지형 기둥은 몸통이 아니다",
        body_of(_col("stone", "stone", "grass_block"), base, plate) is None)

    # ④ 몸통 안의 air 는 남는다 — 개구(빈칸) 부재다
    c = _col("stone_bricks", "air", "air", "air", "mangrove_planks", "dark_oak_slab", "dark_oak_log")
    eye("개구의 air 가 몸통에 남는다",
        body_of(c, base, plate) == ("air*3", "mangrove_planks", "dark_oak_slab", "dark_oak_log"))

    # ⑤ 몸통 앞머리의 air 도 부재다 — 걷는 것은 base 뿐이다
    c = _col("stone_bricks", "air", "plaster_block", "air", "dark_oak_log")
    eye("걷는 것은 base 뿐이다 (앞머리 air 보존)",
        body_of(c, base, plate) == ("air", "plaster_block", "air", "dark_oak_log"))

    # ⑧~⑪ 경계 자동 탐지 — 합성 세계: 트인 땅 6 · 건물 4
    ground = [_col("stone") for _ in range(6)]
    bldg = [_col("stone", "plank", "plank", "plank", "tile") for _ in range(4)]
    world = {(i, 0): c for i, c in enumerate(ground + bldg)}
    yg, abase, aroof = detect_boundaries(world)
    eye("자동: 지면=최빈 꼭대기 · 기단=깔린 재료 · 지붕=솟은 꼭대기",
        yg == 0 and "stone" in abase and aroof == {"tile"})
    eye("자동: 몸통 = 지붕·기단을 걷은 것",
        body_of_auto(bldg[0], abase, aroof) == ("plank*3",))
    eye("자동: 트인 땅은 몸통이 아니다",
        body_of_auto(ground[0], abase, aroof) is None)
    eye("자동: 처마 밑 공기는 지붕과 함께 걷힌다",
        body_of_auto(_col("stone", "plank", "air", "air", "tile"), abase, aroof)
        == ("plank",))

    # ⑫ 포장이 여러 갈래라 층 점유에 못 미쳐도 트인 땅은 몸통이 아니다
    pavs = [_col("stone", p) for p in ("a1", "a2", "a3", "a4") for _ in range(3)]
    tall = [_col("stone", "plank", "plank", "plank", "plank", "tile") for _ in range(4)]
    world2 = {(i, 1): c for i, c in enumerate(pavs + tall)}
    yg2, base2, roof2 = detect_boundaries(world2)
    eye("자동: 갈린 포장도 땅 표면으로 걷힌다",
        body_of_auto(pavs[0], base2, roof2) is None
        and body_of_auto(tall[0], base2, roof2) == ("plank*4",))

    # ⑥ 상태 문자열은 본체 이름으로 접힌다
    import io
    tsv = "x\ty\tz\tdata\n0\t0\t0\tminecraft:dark_oak_log[axis=y]\n"
    rd = csv.reader(io.StringIO(tsv), delimiter="\t")
    next(rd)
    row = next(rd)
    eye("상태 접힘", row[3].split("[")[0].removeprefix("minecraft:") == "dark_oak_log")

    print(f"\n눈 {ran[0]}종 · 실패 {len(fails)}")
    return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("dumps", nargs="*", help="덤프 .tsv (여러 개 = 코퍼스로 합산)")
    ap.add_argument("--top", type=int, default=25)
    ap.add_argument("--auto", action="store_true",
                    help="경계를 사전이 아니라 코퍼스 스스로에게 묻는다 (외부 코퍼스용)")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if not args.dumps:
        ap.error("덤프를 다오 (또는 --selftest)")

    counter, bodies, terrain = mine([Path(p) for p in args.dumps], auto=args.auto)
    total = bodies + terrain
    print(f"\n기둥 {total}개 = 몸통 {bodies} + 몸통 없음(지형·지붕뿐·소품) {terrain}")
    print(f"고유 쌓임 {len(counter)}종 — 빈도 상위 {args.top}:\n")
    for s, n in counter.most_common(args.top):
        print(f"{n:5d}  {' | '.join(s)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
