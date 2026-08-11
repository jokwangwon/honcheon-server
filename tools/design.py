#!/usr/bin/env python3
"""요청 → 도면. <b>기법 사전</b>(config/techniques.yml)이 설계한다.

    python3 tools/design.py --rank 본전 --height 12 --bays 7 --side 5
    python3 tools/design.py --rank 강당 --height 9 --bays 5 --side 3 --out hwasan_test
    python3 tools/design.py --selftest

왜 있나
-------
지금까지 기둥 처방(`columns:`)을 <b>손으로</b> 적었다. 그래서
  · 높이를 12 로 맞추려고 사람이 켜를 세어 더했고 (여러 번 틀렸다)
  · 「창방은 모든 칸이 같은 높이」 같은 규칙이 <b>주석에만</b> 있었고
  · 산문·강당에 같은 기법을 쓰려면 처방을 통째로 베껴야 했다

여기서는 부재(노드)와 쌓임(간선)만 적고 <b>켜 수는 푼다</b>.
「기둥 높이 12」를 주면 몸통 켜가 스스로 정해진다.

무엇이 <b>구조</b>가 되었나
--------------------------
* 「창방·도리·긴보는 모든 칸의 <b>끝 세 켜</b>」 — 사전이 그렇게 적혀 있으므로
  <b>어길 수가 없다</b>. 전에는 주석이었고, 실제로 여러 번 어긋났다.
* 「높이는 모든 칸이 같다」 — 채움 부재가 남는 켜를 먹으므로 <b>언제나</b> 맞는다.
* 「격이 낮으면 기법을 덜어 쓴다」 — 새 기법을 만들지 않는다 (위계 사다리).

정직한 한계
----------
* 지금 푸는 것은 <b>기둥 처방과 평면</b>이다. 지붕·공포·소품 수치는 아직 요청이 아니라
  도면에 손으로 적는다 — 다음 회차의 몫이다.
* 손으로 쓴 본전과 <b>한 글자까지</b> 같지는 않다. 본전에는 이 사전에 아직 없는
  손질(되받이 `Q`·고주 `G`·중앙 겹기둥)이 있다. 자기 시험은 <b>겹치는 부분</b>만 견준다.
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TECH = ROOT / "config" / "techniques.yml"
BP = ROOT / "config" / "blueprints"

# 쌓임 이름 → 평면 글자 (도면 문법과 맞춘다)
GLYPH = {"적주": "P", "모서리": "C", "입구옆": "A", "문설주": "J",
         "창호": "D", "회벽칸": "W", "개구": "O"}


def load():
    import yaml

    return yaml.safe_load(TECH.read_text())


def solve_stack(tech, name: str, height: int, palette: dict):
    """쌓임 하나를 <b>켜 목록</b>으로 푼다 — 채움 부재가 남는 켜를 먹는다.

    ★높이가 모자라면 <b>거기서 죽는다</b>. 조용히 줄이면 칸마다 높이가 달라지고,
    그러면 수평 부재가 한 높이에서 안 이어진다 (도면이 거짓말을 한다).
    """
    seq = tech["stacks"][name]
    members = tech["members"]
    fixed = 0
    fills = []
    for i, mname in enumerate(seq):
        m = members[mname]
        if m.get("fill"):
            fills.append(i)
        else:
            fixed += int(m.get("courses", 1))
    if len(fills) != 1:
        raise SystemExit(f"쌓임 «{name}» 에 채움 부재가 {len(fills)}개 — 하나여야 한다")
    rest = height - fixed
    if rest < 1:
        raise SystemExit(f"쌓임 «{name}» 은 높이 {height} 에 안 들어간다 (고정 켜만 {fixed})")

    out = []
    for i, mname in enumerate(seq):
        m = members[mname]
        n = rest if i in fills else int(m.get("courses", 1))
        mat = m["material"]
        if isinstance(mat, str) and mat.startswith("$"):
            key = mat[1:]
            if key not in palette:
                raise SystemExit(f"팔레트에 «{key}» 가 없다 (부재 {mname})")
            mat = palette[key]
        out.append((mat, n))
    # 이어진 같은 재료는 <b>한 켜열</b>로 합친다 — 도면 문법이 그렇게 읽는다
    merged = []
    for mat, n in out:
        if merged and merged[-1][0] == mat:
            merged[-1][1] += n
        else:
            merged.append([mat, n])
    return [f"{m}*{n}" if n > 1 else m for m, n in merged]


def bay_columns(bays: int, period: int, center_wide: int = 0):
    """칸 배치 → 기둥이 설 x 자리. 가운데 칸만 넓힐 수 있다 (어칸)."""
    xs = [0]
    for i in range(bays):
        w = period + (center_wide if i == bays // 2 else 0)
        xs.append(xs[-1] + w)
    return xs


def design(rank: str, height: int, bays: int, side: int, period: int = 4,
           center_wide: int = 2, palette=None):
    tech = load()
    if rank not in tech["ranks"]:
        raise SystemExit(f"모르는 격: {rank} (있는 것: {list(tech['ranks'])})")
    used = tech["ranks"][rank]
    palette = palette or {"post": "red_terracotta"}

    stacks = {}
    for sname in tech["stacks"]:
        stacks[GLYPH[sname]] = solve_stack(tech, sname, height, palette)

    # 깊이 — 쓰는 기법이 정한다
    depth = {}
    for tname in used:
        t = tech["techniques"].get(tname, {})
        for sname, d in (t.get("depth") or {}).items():
            depth[GLYPH[sname]] = d

    xs = bay_columns(bays, period, center_wide)
    zs = bay_columns(side, period, 0)
    front = []
    for i in range(xs[-1] + 1):
        if i in (0, xs[-1]):
            front.append("C")
        elif i in xs:
            front.append("P")
        else:
            front.append("D")

    flags = {}
    for tname in used:
        t = tech["techniques"].get(tname, {})
        for k in ("intercolumnar", "two_tier_eave"):
            if k in t:
                flags[k] = t[k]

    return {"rank": rank, "height": height, "columns": stacks, "depth": depth,
            "front": "".join(front), "post_x": xs, "post_z": zs,
            "size": (xs[-1] + 1, zs[-1] + 1), "techniques": used, "flags": flags}


def to_yaml(d, name: str) -> str:
    lines = [f"# 자동 설계 — tools/design.py (격 {d['rank']} · 기법 {', '.join(d['techniques'])})",
             "meta:", f"  name: {name}", f"  size: [{d['size'][0]}, {d['size'][1]}]",
             f"  rank: {d['rank']}"]
    for k, v in d["flags"].items():
        lines.append(f"  {k}: {str(v).lower()}")
    lines.append("columns:")
    for g, stack in d["columns"].items():
        lines.append(f'  "{g}": [' + ", ".join(f'"{s}"' for s in stack) + "]")
    lines.append("depth:")
    for g, v in sorted(d["depth"].items()):
        lines.append(f'  "{g}": {v}')
    return "\n".join(lines) + "\n"


# ───────────────────────── 눈을 시험하는 눈 ─────────────────────────

def _selftest() -> int:
    fails = []

    def check(name, cond, got=""):
        print(("  ✓ " if cond else "  ✗ ") + name + (f" — {got}" if not cond else ""))
        if not cond:
            fails.append(name)

    tech = load()
    d = design("본전", 12, 7, 5)

    # ① ★모든 칸이 <b>같은 높이</b>다 — 채움 부재가 남는 켜를 먹으므로 언제나 참이어야 한다
    import re

    def total(stack):
        n = 0
        for e in stack:
            m = re.match(r"(.+?)\*(\d+)$", e)
            n += int(m.group(2)) if m else 1
        return n

    hs = {g: total(s) for g, s in d["columns"].items()}
    check("★모든 칸이 <b>같은 높이</b>다 (채움이 남는 켜를 먹는다)",
          set(hs.values()) == {12}, hs)

    # ② ★수평 부재가 <b>한 높이</b>에서 이어진다 — 맨 위 세 <b>켜</b>가 창방·도리·긴보
    #   ★자를 고쳤다: 처음엔 <b>항목 문자열</b>의 끝 셋을 봤다. 그런데 같은 재료가
    #     이웃하면 한 항목으로 합쳐지므로(`mangrove_planks*2`) 항목 수가 칸마다 달라진다.
    #     조성은 멀쩡한데 자가 짖었다 — 또 <b>대용품</b>을 잰 것이다. 켜로 편다.
    def courses(stack):
        out = []
        for e in stack:
            m = re.match(r"(.+?)\*(\d+)$", e)
            out += [m.group(1)] * int(m.group(2)) if m else [e]
        return out

    tails = {g: tuple(courses(s)[-3:]) for g, s in d["columns"].items()}
    check("★수평 부재가 한 높이에서 이어진다 (맨 위 세 <b>켜</b>가 같다)",
          len(set(tails.values())) == 1, tails)

    # ③ 팔레트가 채워진다 ($post → 실제 재료)
    check("팔레트가 $ 를 채운다", not any("$" in e for s in d["columns"].values() for e in s),
          d["columns"]["P"])

    # ④ 격이 낮으면 기법이 <b>준다</b> (새 기법을 만들지 않는다)
    low = design("창고", 9, 3, 2)
    check("★격이 낮으면 기법이 준다 (본전 3 · 창고 2)",
          len(low["techniques"]) < len(d["techniques"]),
          f"{d['techniques']} vs {low['techniques']}")
    check("창고는 툇간이 없다 (깊이가 전부 0)",
          set(low["depth"].values()) == {0}, low["depth"])

    # ⑤ ★높이가 모자라면 <b>죽는다</b> — 조용히 줄이면 칸마다 높이가 달라진다
    died = False
    try:
        design("본전", 5, 3, 2)
    except SystemExit:
        died = True
    check("★높이가 모자라면 <b>거기서 죽는다</b> (조용히 안 줄인다)", died)

    # ⑥ 칸 배치 — 어칸이 협칸보다 넓다
    xs = d["post_x"]
    gaps = [b - a for a, b in zip(xs, xs[1:])]
    check("★어칸이 협칸보다 넓다", max(gaps) > min(gaps), gaps)

    # ⑦ ★손으로 쓴 본전과 <b>겹치는 부분</b>이 맞는가 (사전이 실물을 설명하는가)
    import yaml
    hj = yaml.safe_load((BP / "hwasan_honjeon.yml").read_text())
    hand = hj["columns"]
    same_tail = all(tuple(hand[g][-2:]) == ("dark_oak_slab", "dark_oak_log")
                    or tuple(hand[g][-2:]) == ("warped_slab", "dark_oak_log")
                    for g in ("W", "D", "O") if g in hand)
    check("★사전의 끝 두 켜가 손으로 쓴 본전과 같다 (도리·긴보)", same_tail,
          {g: hand[g][-2:] for g in ("W", "D", "O") if g in hand})
    check("사전이 본전의 글자를 다 안다",
          set(GLYPH.values()) <= set(hand) | {"P", "C", "A", "J", "D", "W", "O"},
          sorted(GLYPH.values()))

    print(f"\n설계기의 눈 — {'통과' if not fails else f'실패 {len(fails)}: {fails}'}")
    return 1 if fails else 0


def main(argv):
    if "--selftest" in argv:
        return _selftest()
    opt = {"rank": "본전", "height": 12, "bays": 7, "side": 5, "out": None}
    for i, a in enumerate(argv):
        if a.startswith("--") and i + 1 < len(argv):
            k = a[2:]
            if k in opt:
                opt[k] = argv[i + 1] if k in ("rank", "out") else int(argv[i + 1])
    d = design(opt["rank"], opt["height"], opt["bays"], opt["side"])
    print(f"[설계] 격 {d['rank']} · 기법 {', '.join(d['techniques'])}")
    print(f"  크기 {d['size'][0]} × {d['size'][1]} · 기둥 x {d['post_x']} · z {d['post_z']}")
    print(f"  정면 {d['front']}")
    for g, s in d["columns"].items():
        print(f'  "{g}": {s}')
    if opt["out"]:
        p = BP / f"{opt['out']}.yml"
        p.write_text(to_yaml(d, opt["out"]))
        print(f"→ {p.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
