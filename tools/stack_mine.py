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
    runs: list[list] = []
    for b in seg:
        if runs and runs[-1][0] == b:
            runs[-1][1] += 1
        else:
            runs.append([b, 1])
    return tuple(f"{b}*{n}" if n > 1 else b for b, n in runs)


def mine(paths: list[Path]) -> tuple[collections.Counter, int, int]:
    base, plate, abstract = load_boundaries()
    if abstract:
        print(f"[고지] 사전의 실블록 아닌 재료 (경계에 못 쓴다): {', '.join(abstract)}")
    counter: collections.Counter = collections.Counter()
    bodies = terrain = 0
    for p in paths:
        for _key, col in read_dump(p).items():
            s = body_of(col, base, plate)
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
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if not args.dumps:
        ap.error("덤프를 다오 (또는 --selftest)")

    counter, bodies, terrain = mine([Path(p) for p in args.dumps])
    total = bodies + terrain
    print(f"\n기둥 {total}개 = 몸통 {bodies} + 몸통 없음(지형·지붕뿐·소품) {terrain}")
    print(f"고유 쌓임 {len(counter)}종 — 빈도 상위 {args.top}:\n")
    for s, n in counter.most_common(args.top):
        print(f"{n:5d}  {' | '.join(s)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
