#!/usr/bin/env python3
"""분포 눈 — 코퍼스 분포를 정본으로 굽고, 우리 값을 그 분포에 대본다 (E-4).

  python3 tools/corpus_priors.py build run/corpus/tsv/hanok_*.tsv   # → config/corpus_priors.json
  python3 tools/corpus_priors.py check --spacing 6 --pitch 0.75
  python3 tools/corpus_priors.py --selftest

「오늘의 값을 외운 자」(눈 실패 유형 ①)의 해독제다: 문턱을 처방에서 뽑지 않고
**코퍼스 분포의 p10~p90** 에서 뽑는다. 코퍼스가 갱신되면 문턱이 따라온다 —
표가 조용히 낡지 않는다.

굽는 것 셋:
  spacing  기둥 간격 표본 (relation_stats ① — 건물 무리 안 · 행 ≥3)
  pitch    단층 지붕 물매 (relation_stats ③ — ★단층만: 곡선에 3칸 넘는 계단이
           없는 건물. 중층·회랑 고리·석탑은 물매가 다른 뜻이라 섞으면 오염이다)
  clump    면 재료의 뭉침도 (상자마다 상위 5 — 「면은 통판」의 수)

★표본의 정직한 고지: 지금 코퍼스는 **한 마을**(hanok_village 상자들)이다.
  파일에 sources 를 적는다 — 코퍼스가 자라기 전까지 이 분포는 잠정이다.
"""

from __future__ import annotations

import argparse
import collections
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from stack_mine import body_of_auto, detect_boundaries, read_dump  # noqa: E402
from relation_stats import (  # noqa: E402
    adjacency, find_posts, roof_buildings, spacing_hist,
)

ROOT = Path(__file__).resolve().parent.parent
PRIORS = ROOT / "config" / "corpus_priors.json"

TIER_JUMP = 3     # 이웃 d 사이 높이 차가 이보다 크면 층 계단이다 → 단층이 아니다
PITCH_AREA = 200  # 물매 표본이 되려면 지붕이 이만큼은 커야 한다


def quantiles(samples: list[float], qs=(0.10, 0.50, 0.90)) -> dict:
    s = sorted(samples)
    out = {}
    for q in qs:
        i = min(len(s) - 1, max(0, round(q * (len(s) - 1))))
        out[f"p{int(q * 100)}"] = s[i]
    return out


def single_tier(curve) -> bool:
    hs = [h for _, h, _ in curve]
    return all(abs(b - a) <= TIER_JUMP for a, b in zip(hs, hs[1:]))


def build(paths: list[Path]) -> dict:
    spacing_samples: list[int] = []
    pitch_samples: list[float] = []
    clump_samples: list[float] = []
    for p in paths:
        cols = read_dump(p)
        yg, base, roof = detect_boundaries(cols)
        bodies = {k: body_of_auto(c, base, roof) for k, c in cols.items()}
        for gap, n in spacing_hist(find_posts(cols, bodies)).items():
            spacing_samples += [gap] * n
        builds, _ = roof_buildings(cols, yg)
        for b in builds:
            if b["area"] >= PITCH_AREA and b["pitch"] is not None and single_tier(b["curve"]):
                pitch_samples.append(round(b["pitch"], 3))
        cells = {}
        for (x, z), col in cols.items():
            for y, blk in col.items():
                cells[(x, y, z)] = blk
        _, clump = adjacency(cells)
        clump_samples += sorted(clump.values(), reverse=True)[:5]

    if not spacing_samples or not pitch_samples:
        sys.exit("표본이 모자란다 — 상자가 너무 작거나 단층 건물이 없다")
    doc = {
        "고지": "잠정 — 코퍼스가 한 마을이다. 상자가 늘면 다시 구워라",
        "sources": [p.name for p in paths],
        "spacing": {
            "n": len(spacing_samples),
            "hist": dict(sorted(collections.Counter(spacing_samples).items())),
            **quantiles(spacing_samples),
        },
        "pitch": {"n": len(pitch_samples), "samples": sorted(pitch_samples),
                  **quantiles(pitch_samples)},
        "clump_face": {"n": len(clump_samples),
                       **quantiles([round(v, 3) for v in clump_samples])},
    }
    return doc


def check(doc: dict, spacing=None, pitch=None, clump=None) -> int:
    bad = 0

    def judge(name, v, pr):
        nonlocal bad
        lo, hi = pr["p10"], pr["p90"]
        ok = lo <= v <= hi
        mark = "✓ 분포 안" if ok else "★분포 밖"
        print(f"  {name} {v} — 코퍼스 p10 {lo} · p50 {pr['p50']} · p90 {hi} → {mark}")
        if not ok:
            bad += 1

    if spacing is not None:
        judge("기둥 간격", spacing, doc["spacing"])
    if pitch is not None:
        judge("물매", pitch, doc["pitch"])
    if clump is not None:
        judge("면 뭉침도", clump, doc["clump_face"])
    print(f"  (근거: {', '.join(doc['sources'])} — {doc['고지']})")
    return 1 if bad else 0


# ── 눈 (selftest) ──────────────────────────────────────────────────────────

def selftest() -> int:
    fails, ran = [], [0]

    def eye(name, cond):
        ran[0] += 1
        print(("  ✓ " if cond else "  ✗ ") + name)
        if not cond:
            fails.append(name)

    q = quantiles(list(range(1, 101)))
    eye("분위수 p10·p50·p90 (최근접 순위)", (q["p10"], q["p50"], q["p90"]) == (11, 51, 90))

    eye("단층: 완만한 곡선", single_tier([(0, 6, 9), (1, 7, 9), (2, 8, 9)]))
    eye("중층: 계단(6→18)은 단층이 아니다",
        not single_tier([(0, 6, 9), (1, 7, 9), (2, 18, 9)]))

    doc = {"sources": ["눈"], "고지": "시험",
           "spacing": {"p10": 3, "p50": 4, "p90": 6},
           "pitch": {"p10": 0.5, "p50": 0.8, "p90": 1.0},
           "clump_face": {"p10": 0.7, "p50": 0.85, "p90": 0.95}}
    import io
    cap = io.StringIO()
    old, sys.stdout = sys.stdout, cap
    try:
        r_in = check(doc, spacing=4, pitch=0.8)
        r_out = check(doc, spacing=9)
    finally:
        sys.stdout = old
    eye("분포 안이면 0 · 밖이면 1 로 짖는다", r_in == 0 and r_out == 1)
    eye("판정문에 근거(sources)가 적힌다", "눈" in cap.getvalue())

    print(f"\n눈 {ran[0]}종 · 실패 {len(fails)}")
    return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("mode", nargs="?", choices=["build", "check"])
    ap.add_argument("dumps", nargs="*")
    ap.add_argument("--spacing", type=float)
    ap.add_argument("--pitch", type=float)
    ap.add_argument("--clump", type=float)
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()

    if a.selftest:
        return selftest()
    if a.mode == "build":
        if not a.dumps:
            ap.error("build <덤프.tsv ...>")
        doc = build([Path(p) for p in a.dumps])
        PRIORS.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"구웠다 → {PRIORS}")
        print(f"  spacing n{doc['spacing']['n']} p10~p90 "
              f"{doc['spacing']['p10']}~{doc['spacing']['p90']} (p50 {doc['spacing']['p50']})")
        print(f"  pitch   n{doc['pitch']['n']} p10~p90 "
              f"{doc['pitch']['p10']}~{doc['pitch']['p90']} (p50 {doc['pitch']['p50']})")
        print(f"  clump   n{doc['clump_face']['n']} p10~p90 "
              f"{doc['clump_face']['p10']}~{doc['clump_face']['p90']}")
        return 0
    if a.mode == "check":
        if not PRIORS.exists():
            sys.exit("먼저 build 로 구워라 — config/corpus_priors.json 이 없다")
        if a.spacing is None and a.pitch is None and a.clump is None:
            ap.error("check 에는 --spacing/--pitch/--clump 중 하나는 다오")
        return check(json.loads(PRIORS.read_text(encoding="utf-8")),
                     a.spacing, a.pitch, a.clump)
    ap.error("build 또는 check (또는 --selftest)")


if __name__ == "__main__":
    sys.exit(main())
