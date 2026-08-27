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


CLUSTER_R = 8   # 기둥 사이가 이보다 멀면 딴 건물이다 (코퍼스 최빈 주기 4~6 의 위)
ROW_MIN = 3     # 행이라 부르려면 한 줄에 기둥이 이만큼은 서야 한다 (둘은 우연이다)


def cluster_points(points: set, radius: int = CLUSTER_R) -> list[set]:
    """가까운 점끼리 무리 짓는다 (체비셰프 거리 ≤ radius) — 건물 단위 분리."""
    buckets: dict = collections.defaultdict(set)
    for p in points:
        buckets[(p[0] // radius, p[1] // radius)].add(p)
    seen: set = set()
    out = []
    for p in points:
        if p in seen:
            continue
        comp = {p}
        seen.add(p)
        todo = [p]
        while todo:
            x, z = todo.pop()
            for dbx in (-1, 0, 1):
                for dbz in (-1, 0, 1):
                    for q in buckets.get((x // radius + dbx, z // radius + dbz), ()):
                        if q not in seen and abs(q[0] - x) <= radius and abs(q[1] - z) <= radius:
                            seen.add(q)
                            comp.add(q)
                            todo.append(q)
        out.append(comp)
    return out


def spacing_hist(posts: set[tuple[int, int]]) -> collections.Counter:
    """기둥 간격 — ★건물 무리 안에서, 기둥 셋 이상 선 행(같은 축)만 잰다.

    1차의 병 둘을 고친 자다: 마당 건너 건물 사이 거리(112·106)가 끼었고,
    둘만 선 우연한 줄이 행으로 세어졌다.
    """
    hist: collections.Counter = collections.Counter()
    for comp in cluster_points(posts):
        by_z: dict[int, list[int]] = collections.defaultdict(list)
        by_x: dict[int, list[int]] = collections.defaultdict(list)
        for x, z in comp:
            by_z[z].append(x)
            by_x[x].append(z)
        for seq in list(by_z.values()) + list(by_x.values()):
            if len(seq) < ROW_MIN:
                continue
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

MIN_ROOF_AREA = 40  # 이보다 작은 지붕 조각(정자 꼭대기·나무)은 건물이 아니다 — 세어서 버린다


def _components4(cells: set) -> list[set]:
    seen: set = set()
    out = []
    for p in cells:
        if p in seen:
            continue
        comp = {p}
        seen.add(p)
        todo = [p]
        while todo:
            x, z = todo.pop()
            for nb in ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)):
                if nb in cells and nb not in seen:
                    seen.add(nb)
                    comp.add(nb)
                    todo.append(nb)
        out.append(comp)
    return out


def _profile_of(comp: set, tops: dict, yg: int) -> list[tuple[int, float, int]]:
    """한 건물의 처마 가장자리 거리 d → 꼭대기 높이 중앙값. (d, 높이, 표본수)"""
    dist = {}
    frontier = []
    for (x, z) in comp:
        for nb in ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)):
            if nb not in comp:
                dist[(x, z)] = 0
                frontier.append((x, z))
                break
    while frontier:
        nxt = []
        for (x, z) in frontier:
            for nb in ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)):
                if nb in comp and nb not in dist:
                    dist[nb] = dist[(x, z)] + 1
                    nxt.append(nb)
        frontier = nxt
    by_d: dict[int, list[int]] = collections.defaultdict(list)
    for k, d in dist.items():
        by_d[d].append(tops[k] - yg)
    return [(d, statistics.median(hs), len(hs)) for d, hs in sorted(by_d.items())]


def _pitch(curve: list[tuple[int, float, int]]) -> float | None:
    """물매 = d 에 대한 높이의 기울기 (표본 3 이상인 d 만 · 최소제곱)."""
    pts = [(d, h) for d, h, n in curve if n >= 3]
    if len(pts) < 3:
        return None
    md = statistics.mean(d for d, _ in pts)
    mh = statistics.mean(h for _, h in pts)
    den = sum((d - md) ** 2 for d, _ in pts)
    return sum((d - md) * (h - mh) for d, h in pts) / den if den else None


def roof_buildings(cols: dict, yg: int) -> tuple[list[dict], int]:
    """지붕을 건물별(4방 연결 성분)로 갈라 곡선·물매를 잰다. (건물 목록, 버린 조각 수)

    1차의 병을 고친 자다: 한 상자의 지붕을 통째로 섞어 회랑(6~7)과 정전(15~21)이
    한 곡선에 겹쳤다.
    """
    tops = {}
    for key, col in cols.items():
        for y in sorted(col, reverse=True):
            if col[y] != "air":
                tops[key] = y
                break
    roofed = {k for k, y in tops.items() if y >= yg + ROOF_MIN}
    buildings = []
    dropped = 0
    for comp in _components4(roofed):
        if len(comp) < MIN_ROOF_AREA:
            dropped += 1
            continue
        curve = _profile_of(comp, tops, yg)
        buildings.append({"area": len(comp), "curve": curve, "pitch": _pitch(curve)})
    buildings.sort(key=lambda b: -b["area"])
    return buildings, dropped


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

        if auto:
            builds, dropped = roof_buildings(cols, yg)
            print(f"③ 지붕 — 건물 {len(builds)}채 · 작은 조각 {dropped} 버림 (건물별 곡선·물매):")
            for b in builds[:6]:
                row = " ".join(f"d{d}:{h:.0f}" for d, h, _n in b["curve"][:9])
                pit = f"물매 {b['pitch']:.2f}/칸" if b["pitch"] is not None else "물매 — "
                print(f"    {b['area']:5d}칸 · {pit} · {row}")
        else:
            print("③ 지붕 — 사전 모드는 지면을 모른다 · --auto 가 서는 상자로 재라")


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

    # ① 두 건물이 마당을 사이에 두면 그 거리는 간격이 아니다
    two = {(0, 0), (4, 0), (8, 0), (40, 0), (44, 0), (48, 0)}
    eye("무리 밖 거리(32)는 안 센다", spacing_hist(two) == {4: 4})
    eye("무리 짓기: 두 건물", len(cluster_points(two)) == 2)

    # ③ 지붕 곡선 — 피라미드 지붕 두 채 + 작은 조각 하나
    cols = {}
    def pyramid(ox):
        for x in range(9):
            for z in range(9):
                d = min(x, z, 8 - x, 8 - z)
                cols[(ox + x, z)] = {0: "stone", 6 + d: "tile"}  # 처마 6 · 물매 1/칸
    pyramid(0)
    pyramid(30)
    for i in range(4):
        cols[(60 + i, 0)] = {0: "stone", 7: "tile"}  # 4칸짜리 조각 — 건물이 아니다
    for x in range(-3, 70):
        for z in (-1, 9):
            cols[(x, z)] = {0: "stone"}
    builds, dropped = roof_buildings(cols, 0)
    eye("지붕이 건물별로 갈린다 (2채 · 조각 1 버림)",
        len(builds) == 2 and dropped == 1)
    heights = [h for _, h, _ in builds[0]["curve"]]
    eye("피라미드: d 가 커질수록 높이가 는다 · 처마 끝 6",
        all(a < b for a, b in zip(heights, heights[1:])) and builds[0]["curve"][0][1] == 6)
    eye("피라미드: 물매 ≈ 1.0/칸",
        builds[0]["pitch"] is not None and abs(builds[0]["pitch"] - 1.0) < 0.05)

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
