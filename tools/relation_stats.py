#!/usr/bin/env python3
"""관계 통계 — 쌓임(세로) 다음은 관계(가로)다 (SYSTEM_REVIEW ⑥ · E-3).

  python3 tools/relation_stats.py run/corpus/tsv/hanok_palace.tsv --auto
  python3 tools/relation_stats.py run/mvt-test/dump/hwasan_honjeon.tsv
  python3 tools/relation_stats.py --selftest

재는 것 셋 — 전부 읽힘 자·도면 회차의 미해결에 답하는 자다:
  ① 기둥 간격   몸통에 긴 세로 런(≥4)이 있는 기둥 = 「기둥 후보」.
               같은 축 위 이웃 기둥까지의 거리 분포 → 격자 주기 (칸 리듬의 수)
  ② 이웃 쌍    수평 이웃(±x·±z)의 재료 쌍 빈도 + 재료별 같은-이웃 비율(뭉침도)
               → 「무엇이 무엇과 붙는가」가 수가 된다
  ③ 지붕 곡선   지붕 기둥의 꼭대기 높이를 「처마 가장자리에서의 거리」로 묶는다
               → 물매·안허리곡의 실측 곡선 (거리 0 = 처마 끝)

★쌓임 캐기(stack_mine)와 같은 경계를 쓴다 — 사전 모드 · --auto 모드.
"""

from __future__ import annotations

import argparse
import collections
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from stack_mine import (  # noqa: E402
    ROOF_MIN, body_of, body_of_auto, detect_boundaries, load_boundaries, read_dump,
)

POST_MIN = 4  # 이만큼 이어진 단일 재료 세로 런이 있으면 기둥 후보다


# ── ① 기둥 간격 ────────────────────────────────────────────────────────────

def find_posts(cols: dict, bodies: dict) -> set[tuple[int, int]]:
    posts = set()
    for key, runs in bodies.items():
        if runs is None:
            continue
        for r in runs:
            name, _, n = r.partition("*")
            if name != "air" and n and int(n) >= POST_MIN:
                posts.add(key)
                break
    return posts


def spacing_hist(posts: set[tuple[int, int]]) -> collections.Counter:
    """같은 축 위에서 다음 기둥까지의 거리 — 양 축을 함께 센다."""
    hist: collections.Counter = collections.Counter()
    by_z: dict[int, list[int]] = collections.defaultdict(list)
    by_x: dict[int, list[int]] = collections.defaultdict(list)
    for x, z in posts:
        by_z[z].append(x)
        by_x[x].append(z)
    for seq in list(by_z.values()) + list(by_x.values()):
        seq.sort()
        for a, b in zip(seq, seq[1:]):
            if b - a > 1:  # 붙은 것은 겹기둥·굵은 기둥이다 — 간격이 아니다
                hist[b - a] += 1
    return hist


# ── ② 이웃 쌍 ──────────────────────────────────────────────────────────────

def adjacency(cells: dict) -> tuple[collections.Counter, dict]:
    pairs: collections.Counter = collections.Counter()
    same: collections.Counter = collections.Counter()
    total: collections.Counter = collections.Counter()
    for (x, y, z), b in cells.items():
        if b == "air":
            continue
        for nb in ((x + 1, y, z), (x, y, z + 1)):  # 한 방향만 — 쌍을 두 번 안 센다
            o = cells.get(nb)
            if o is None or o == "air":
                continue
            total[b] += 1
            total[o] += 1
            if b == o:
                same[b] += 2
            else:
                pairs[tuple(sorted((b, o)))] += 1
    clump = {m: same[m] / total[m] for m in total if total[m] >= 40}
    return pairs, clump


# ── ③ 지붕 곡선 ────────────────────────────────────────────────────────────

def roof_profile(cols: dict, yg: int) -> list[tuple[int, float, int]]:
    """처마 가장자리 거리 d → 꼭대기 높이(지면 기준)의 중앙값. (d, 높이, 표본수)"""
    tops = {}
    for key, col in cols.items():
        for y in sorted(col, reverse=True):
            if col[y] != "air":
                tops[key] = y
                break
    roofed = {k for k, y in tops.items() if y >= yg + ROOF_MIN}
    if not roofed:
        return []
    # 다중 시작 BFS — 지붕이 아닌 이웃까지의 거리 (4방)
    dist = {}
    frontier = []
    for (x, z) in roofed:
        for nb in ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)):
            if nb not in roofed:
                dist[(x, z)] = 0
                frontier.append((x, z))
                break
    while frontier:
        nxt = []
        for (x, z) in frontier:
            for nb in ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)):
                if nb in roofed and nb not in dist:
                    dist[nb] = dist[(x, z)] + 1
                    nxt.append(nb)
        frontier = nxt
    by_d: dict[int, list[int]] = collections.defaultdict(list)
    for k, d in dist.items():
        by_d[d].append(tops[k] - yg)
    return [(d, statistics.median(hs), len(hs)) for d, hs in sorted(by_d.items())]


# ── 실행 ───────────────────────────────────────────────────────────────────

def run(paths: list[Path], auto: bool) -> None:
    if not auto:
        dbase, dplate, _ = load_boundaries()
    for p in paths:
        cols = read_dump(p)
        cells = {}
        for (x, z), col in cols.items():
            for y, b in col.items():
                cells[(x, y, z)] = b
        if auto:
            yg, base, roof = detect_boundaries(cols)
            bodies = {k: body_of_auto(c, base, roof) for k, c in cols.items()}
        else:
            yg = 0
            bodies = {k: body_of(c, dbase, dplate) for k, c in cols.items()}

        print(f"\n═══ {p.stem} ═══")
        posts = find_posts(cols, bodies)
        hist = spacing_hist(posts)
        n = sum(hist.values())
        print(f"① 기둥 {len(posts)}개 · 간격 표본 {n}")
        for gap, c in sorted(hist.items(), key=lambda kv: -kv[1])[:8]:
            print(f"    간격 {gap:3d}  {c:5d}  {'█' * min(40, int(40 * c / max(hist.values())))}")

        pairs, clump = adjacency(cells)
        print("② 이웃 쌍 상위 8 (다른 재료끼리):")
        for (a, b), c in pairs.most_common(8):
            print(f"    {c:6d}  {a} ↔ {b}")
        top_clump = sorted(clump.items(), key=lambda kv: -kv[1])[:5]
        print("   뭉침도 상위 (1.0 = 언제나 저희끼리):"
              + " · ".join(f"{m} {v:.2f}" for m, v in top_clump))

        prof = roof_profile(cols, yg)
        if prof:
            print("③ 지붕 곡선 — 처마 끝(d0)에서 안쪽으로 · 중앙값 높이(지면 기준):")
            row = " · ".join(f"d{d}:{h:.0f}({n})" for d, h, n in prof[:10])
            print(f"    {row}")
        else:
            print("③ 지붕 곡선 — 지붕 기둥이 없다 (사전 모드는 yg=0 이라 못 잰다 — --auto 로)")


# ── 눈 (selftest) ──────────────────────────────────────────────────────────

def selftest() -> int:
    fails, ran = [], [0]

    def eye(name, cond):
        ran[0] += 1
        print(("  ✓ " if cond else "  ✗ ") + name)
        if not cond:
            fails.append(name)

    # ① 간격 — x 0·4·8·12 에 기둥, 붙은 겹기둥(13)은 간격에서 빠진다
    posts = {(0, 0), (4, 0), (8, 0), (12, 0), (13, 0)}
    h = spacing_hist(posts)
    eye("간격 4 가 셋 · 붙은 겹기둥은 안 센다", h == {4: 3})

    # ① 기둥 후보 — 세로 런 4 이상만
    bodies = {(0, 0): ("wood*4",), (1, 0): ("wood*3", "cap"), (2, 0): ("air*5", "cap")}
    eye("기둥 후보 = 런 ≥4 · air 런은 아니다",
        find_posts({}, bodies) == {(0, 0)})

    # ② 이웃 — 두 재료 바둑판은 뭉침도 0, 단일 판은 1
    board = {(x, 0, z): ("a" if (x + z) % 2 == 0 else "b") for x in range(6) for z in range(6)}
    pairs, clump = adjacency(board)
    eye("바둑판: a↔b 쌍만 · 뭉침도 0",
        list(pairs) == [("a", "b")] and clump.get("a") == 0.0 and clump.get("b") == 0.0)
    slab = {(x, 0, z): "c" for x in range(8) for z in range(8)}
    _, clump2 = adjacency(slab)
    eye("단일 판: 뭉침도 1", clump2.get("c") == 1.0)

    # ③ 지붕 곡선 — 피라미드 지붕: 가장자리에서 안으로 갈수록 높다
    cols = {}
    for x in range(9):
        for z in range(9):
            d = min(x, z, 8 - x, 8 - z)
            h = 6 + d  # 처마 6, 안쪽으로 1 씩 오른다
            cols[(x, z)] = {0: "stone", h: "tile"}
    for x in range(-3, 12):  # 지붕 아닌 둘레 땅
        for z in (-1, 9):
            cols[(x, z)] = {0: "stone"}
            cols[(z, x)] = {0: "stone"}
    prof = roof_profile(cols, 0)
    heights = [h for _, h, _ in prof]
    eye("피라미드: d 가 커질수록 높이가 는다 (단조)",
        len(heights) >= 4 and all(a < b for a, b in zip(heights, heights[1:])))
    eye("피라미드: 처마 끝 d0 높이 = 6", prof and prof[0][1] == 6)

    print(f"\n눈 {ran[0]}종 · 실패 {len(fails)}")
    return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("dumps", nargs="*")
    ap.add_argument("--auto", action="store_true", help="경계 자동 탐지 (외부 코퍼스용)")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()
    if not args.dumps:
        ap.error("덤프를 다오 (또는 --selftest)")
    run([Path(p) for p in args.dumps], args.auto)
    return 0


if __name__ == "__main__":
    sys.exit(main())
