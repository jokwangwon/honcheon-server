#!/usr/bin/env python3
"""설계도 → 도면 5종 (평면도 · 정면도 · 측면도 · 지붕 층별 · 블록 팔레트).

  python3 tools/blueprint_draw.py hwasan_gate [출력폴더]

★왜 이것이 필요한가 (사용자 확정 2026-08-06):
  「사진 한 장이 아니라 최소한 정면도·측면도·평면도·지붕 분해도·블록 팔레트가 있어야
  AI 가 사진을 감상하는 것이 아니라 규칙에 따라 건축할 수 있다.」

★그런데 <b>도면을 손으로 그리지 않는다</b>. config/blueprints/*.yml 이 이미 좌표의
  정본이고, 조성기가 그것을 읽어 짓는다. 도면을 따로 그리면 도면과 실물이 갈라진다 —
  이 저장소가 여러 번 데인 병이다 (신고표가 실물보다 넓어 눈이 헛것을 지킨 일).
  그래서 <b>도면은 정본에서 뽑는다.</b> 여기서 나온 그림은 언제나 실물과 같다.

시선은 고정한다 (사용자 확정):
  정면도 = 남에서 북을 봄 · 측면도 = 동에서 서를 봄 · 평면도 = 위에서 아래를 봄
"""
import sys
from pathlib import Path

import yaml
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
BP = ROOT / "config" / "blueprints"

# ── 재료 색 — <b>텍스처에서 자동으로 뽑는다</b> (tools/block_palette.py) ──────────
#   ★2026-08-11: 여기 <b>손으로 적은 17종짜리 표</b>가 있었다. 그 사이 팔레트가 여러 번
#     바뀌었고, 재 보니 본전이 쓰는 재료 14종 중 <b>9종(64%)을 몰라</b> 정면도가 통째로
#     `?` 였다. <b>도면 도구가 고장난 채 몇 회차를 지났다.</b>
#     표가 틀린 게 아니라 <b>손으로 유지하는 표</b>가 문제였다 — 안 따라 적어도 아무도
#     안 죽으니 조용히 낡는다. 이제 클라이언트 jar 의 실제 텍스처에서 뽑고,
#     리소스팩이 덮은 블록은 <b>팩 것을 쓴다</b> (게임에서 보이는 것이 그것이다).
#   ★모르는 재료가 나오면 <b>자홍색으로 칠하고 세어 보고한다</b> — 조용히 넘어가지 않는다.
import json as _json

_TABLE_PATH = ROOT / "config" / "block_colors.json"
if not _TABLE_PATH.exists():
    raise SystemExit("색표가 없다 — 먼저 굽는다: python3 tools/block_palette.py")
_TABLE = _json.loads(_TABLE_PATH.read_text())

sys.path.insert(0, str(ROOT / "tools"))
from block_palette import color_of as _color_of      # noqa: E402

UNKNOWN = {}


def mat_color(m):
    """재료 → 색. 모르면 자홍색을 주고 <b>이름을 적어 둔다</b>."""
    if not m or m == "air":
        return None
    got = _color_of(_TABLE, m)
    if got is None:
        UNKNOWN[m] = UNKNOWN.get(m, 0) + 1
        return (255, 0, 255)
    return tuple(got["rgb"])


# 도면에 쓰는 한 글자 — ASCII 도면용 (1문자 = 1칸)
GLYPH = {
    "air": ".", "smooth_stone": "=", "stone": "#", "stone_bricks": "#",
    "stone_brick_wall": "n", "tuff": "-", "plaster": "o", "bone_block": "o",
    "cut_sandstone": "^", "stripped_mangrove_log": "|", "dark_oak_planks": "H",
    "dark_oak_trapdoor": "D", "dark_oak_fence": "I", "lantern": "*",
    "glass_pane": "'", "cobbled_deepslate": "%", "deepslate_bricks": "%",
}


def expand(stack):
    """["a*3","b"] -> ["a","a","a","b"] — 도면 문법의 «재료*n»을 편다."""
    out = []
    for item in stack:
        if "*" in item:
            mat, n = item.rsplit("*", 1)
            out.extend([mat] * int(n))
        else:
            out.append(item)
    return out


class Bp:
    def __init__(self, name):
        self.name = name
        d = yaml.safe_load((BP / f"{name}.yml").read_text())
        self.raw = d
        self.meta = d["meta"]
        self.cols = {k: expand(v) for k, v in d["columns"].items()}
        self.plan = [ln for ln in d["plan"].splitlines() if ln.strip()]
        # 평면의 앞 여백을 걷는다 (yml 블록 스칼라의 들여쓰기)
        pad = min(len(ln) - len(ln.lstrip()) for ln in self.plan)
        self.plan = [ln[pad:] for ln in self.plan]
        self.roof = d.get("roof", {})
        self.spots = d.get("spots", {})
        self.w = max(len(ln) for ln in self.plan)
        self.d = len(self.plan)
        self.h = max((len(v) for v in self.cols.values()), default=0)

    def at(self, col, row):
        ln = self.plan[row]
        return ln[col] if col < len(ln) else "."

    def stack(self, col, row):
        return self.cols.get(self.at(col, row), [])

    def block(self, col, row, y):
        s = self.stack(col, row)
        if y < len(s):
            m = s[y]
            return None if m == "air" else m
        return None

    # ── 정면도 — 남(+z, row 큰 쪽)에서 북을 본다 ──────────────────────
    def elevation_south(self):
        grid = []
        for y in range(self.h - 1, -1, -1):
            line = []
            for col in range(self.w):
                mat = None
                for row in range(self.d - 1, -1, -1):     # 남 → 북, 처음 만나는 것
                    m = self.block(col, row, y)
                    if m:
                        mat = m
                        break
                line.append(mat)
            grid.append(line)
        return grid

    # ── 측면도 — 동(+x, col 큰 쪽)에서 서를 본다 ─────────────────────
    def elevation_east(self):
        grid = []
        for y in range(self.h - 1, -1, -1):
            line = []
            for row in range(self.d):
                mat = None
                for col in range(self.w - 1, -1, -1):
                    m = self.block(col, row, y)
                    if m:
                        mat = m
                        break
                line.append(mat)
            grid.append(line)
        return grid

    # ── 평면도 — 위에서 아래. 그 열의 가장 높은 것 ────────────────────
    def plan_view(self):
        grid = []
        for row in range(self.d):
            line = []
            for col in range(self.w):
                s = self.stack(col, row)
                mat = None
                for m in reversed(s):
                    if m != "air":
                        mat = m
                        break
                line.append(mat)
            grid.append(line)
        return grid


def to_ascii(grid):
    return "\n".join("".join(GLYPH.get(m, "?") if m else "." for m in ln) for ln in grid)


def to_png(grid, path, cell=8, grid_every=5):
    h, w = len(grid), max(len(ln) for ln in grid)
    im = Image.new("RGB", (w * cell, h * cell), (250, 248, 243))
    dr = ImageDraw.Draw(im)
    for y, ln in enumerate(grid):
        for x, m in enumerate(ln):
            if not m:
                continue
            c = mat_color(m)
            if c is None:
                continue
            dr.rectangle([x * cell, y * cell, x * cell + cell - 1, y * cell + cell - 1], fill=c)
    # 5칸 눈금 — 치수를 눈으로 세게 (도면의 자)
    for x in range(0, w + 1, grid_every):
        dr.line([x * cell, 0, x * cell, h * cell], fill=(0, 0, 0, 40), width=1)
    for y in range(0, h + 1, grid_every):
        dr.line([0, y * cell, w * cell, y * cell], fill=(0, 0, 0, 40), width=1)
    im.save(path)
    return im.size


# ── 화면에 실제로 덮이는 두께 (블록 한 칸 = 1.0) ─────────────────────────
#   ★2026-08-06 D-39 의 진범: 도면 칸수와 화면 면적이 다르다. 트랩도어는 3/16 이라
#     한 칸을 차지하고도 화면의 5분의 1만 덮고, 나머지로 뒤가 비친다. 세 회차를
#     「문짝이 적다」로 오진한 원인이다. lint 는 <b>화면 면적</b>으로 잰다.
THICK = {
    "dark_oak_trapdoor": 3 / 16,
    "dark_oak_fence": 2 / 16,
    "stone_brick_wall": 8 / 16,
    "glass_pane": 2 / 16,
    "lantern": 6 / 16,
}
TIMBER = {"stripped_mangrove_log", "dark_oak_trapdoor", "dark_oak_fence",
          "dark_oak_planks", "spruce_log", "spruce_planks"}
PLASTER = {"plaster", "bone_block"}


def thickness(m):
    if m is None:
        return 0.0
    return THICK.get(m, 1.0)


def lint(bp, grid):
    """입면 lint — 「대형 백면이 다시 생기는가」를 탐지한다.

    ★건축 정답이 아니다. 되돌아가는 것을 잡는 자다 (사용자 확정 2026-08-06).
    """
    spec = (bp.raw.get("lint") or {}).get("facade")
    if not spec:
        return []
    c0, c1 = spec.get("box", [0, bp.w - 1])
    rows = [[ln[c] for c in range(c0, c1 + 1)] for ln in grid]
    tot = sum(len(r) for r in rows)
    op = sum(1 for r in rows for m in r if m is None)
    tim = sum(thickness(m) for r in rows for m in r if m in TIMBER)
    # 이어진 회벽 — 가로/세로 최장
    runw = runh = 0
    for r in rows:
        run = 0
        for m in r:
            run = run + 1 if m in PLASTER else 0
            runw = max(runw, run)
    for c in range(len(rows[0])):
        run = 0
        for r in rows:
            run = run + 1 if r[c] in PLASTER else 0
            runh = max(runh, run)
    out = []

    def judge(name, got, ok, want):
        out.append((ok, f"{name}: {got} ({want})"))

    judge("가로로 이어진 회벽", runw,
          runw <= spec["max_contiguous_plaster_width"],
          f"상한 {spec['max_contiguous_plaster_width']}")
    judge("세로로 이어진 회벽", runh,
          runh <= spec["max_contiguous_plaster_height"],
          f"상한 {spec['max_contiguous_plaster_height']}")
    judge("개구 비율", f"{op / tot * 100:.1f}%",
          op / tot >= spec["min_opening_ratio"],
          f"하한 {spec['min_opening_ratio'] * 100:.0f}%")
    judge("목재 입면 (★화면 면적)", f"{tim / tot * 100:.1f}%",
          tim / tot >= spec["min_timber_facade_ratio"],
          f"하한 {spec['min_timber_facade_ratio'] * 100:.0f}%")
    return out


def palette(bp):
    """실제로 쓰는 재료와 칸수 — 신고가 아니라 <b>센 것</b>이다."""
    cnt = {}
    for row in range(bp.d):
        for col in range(bp.w):
            for m in bp.stack(col, row):
                if m == "air":
                    continue
                cnt[m] = cnt.get(m, 0) + 1
    return sorted(cnt.items(), key=lambda kv: -kv[1])


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    name = sys.argv[1]
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "docs" / "design" / "hwasan" / name
    out.mkdir(parents=True, exist_ok=True)
    bp = Bp(name)
    print(f"[도면] {bp.meta.get('name', name)} — 폭 {bp.w} · 깊이 {bp.d} · 높이 {bp.h}")
    views = {
        "front_view": ("정면도 (남 → 북)", bp.elevation_south()),
        "side_view": ("측면도 (동 → 서)", bp.elevation_east()),
        "top_view": ("평면도 (위 → 아래)", bp.plan_view()),
    }
    for key, (label, grid) in views.items():
        size = to_png(grid, out / f"{key}.png")
        (out / f"{key}.txt").write_text(f"# {label}\n" + to_ascii(grid) + "\n")
        print(f"  {label:20s} {len(grid)}행 x {len(grid[0])}열  → {key}.png {size}")
    lines = ["| 재료 | 칸 | 비율 |", "|---|---:|---:|"]
    pal = palette(bp)
    tot = sum(c for _, c in pal)
    for m, c in pal:
        lines.append(f"| `{m}` | {c} | {c / tot * 100:.1f}% |")
    (out / "palette_counted.md").write_text(
        f"# {bp.meta.get('name', name)} — 블록 팔레트 (도면에서 **센** 것)\n\n"
        f"총 {tot}칸 · {len(pal)}종\n\n" + "\n".join(lines) + "\n")
    print(f"  블록 팔레트           {len(pal)}종 · {tot}칸  → palette_counted.md")
    rep = lint(bp, views["front_view"][1])
    if rep:
        print("  ── 입면 lint (대형 백면 탐지) ──")
        bad = 0
        for ok, line in rep:
            print(f"    {'OK ' if ok else '★위반'} {line}")
            bad += 0 if ok else 1
        (out / "facade_lint.md").write_text(
            "# 입면 lint — 「대형 백면이 다시 생기는가」\n\n"
            "★건축 정답이 아니라 되돌아가는 것을 잡는 자다.\n"
            "★★비율은 도면 칸이 아니라 **화면에 덮이는 면적**으로 잰다 (얇은 블록은 두께로 깎인다).\n\n"
            + "\n".join(f"- {'OK' if ok else '**위반**'} {l}" for ok, l in rep) + "\n")
        if bad:
            print(f"    → 위반 {bad}건")
    if bp.roof:
        rl = ["# 지붕 층별 (도면 roof 절 — 코드 문법 호출)", ""]
        for rname, spec in bp.roof.items():
            rl.append(f"## {rname}")
            box = spec.get("box")
            if box:
                rl.append(f"- 발자국: col {box[0]}~{box[2]} · row {box[1]}~{box[3]} "
                          f"(폭 {box[2] - box[0] + 1} × 깊이 {box[3] - box[1] + 1})")
            for k, v in spec.items():
                if k != "box":
                    rl.append(f"- {k}: {v}")
            rl.append("")
        (out / "roof_layers.md").write_text("\n".join(rl))
        print(f"  지붕 층별             {len(bp.roof)}채  → roof_layers.md")
    if UNKNOWN:
        print("  ★모르는 재료 " + str(len(UNKNOWN)) + "종 (자홍색으로 칠했다): "
              + ", ".join(sorted(UNKNOWN)))
    else:
        print("  색표가 재료를 <b>전부</b> 안다")
    print(f"→ {out}")


if __name__ == "__main__":
    main()
