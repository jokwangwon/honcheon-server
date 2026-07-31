#!/usr/bin/env python3
"""무대 도면 렌더 — 층별 도면(config/stages/*.stage.yml)을 그림으로 (B-194).

「사진 찍고 좌표 찍는 방식으론 원하는 느낌이 안 나온다」(사용자 2026-07-31) —
AI 가 공간을 **설계 단계에서 보게** 하는 눈이다. 평면도 + 아이소메트릭을 PNG 로 그린다.
조성 전에 본다 · 조성 후에는 공간덤프(후속)와 대조한다.

사용법:  python3 tools/stage_render.py <무대이름>     # config/stages/<이름>.stage.yml
출력:    run/stage_render/<이름>_plan.png · <이름>_iso.png
검증도 겸한다: 줄 길이·범례 밖 문자·spots 가 air 위인지 — 어긋나면 종료 1.
"""
import sys
from pathlib import Path

import yaml
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "run" / "stage_render"

# 재질 → 색 (수묵 밤 무대의 결 — 렌더 전용, 게임과 무관)
COLORS = {
    "air": None,
    "podzol": (74, 60, 42),
    "dirt_path": (120, 100, 66),
    "spruce_planks": (114, 84, 48),
    "dark_oak_log": (56, 38, 20),
    "dark_oak_slab": (66, 46, 26),
    "mud_bricks": (140, 118, 94),
    "mossy_cobblestone": (100, 110, 90),
    "water": (52, 76, 120),
    "white_carpet": (225, 220, 210),
    "red_carpet": (150, 60, 50),
    "soul_lantern": (120, 200, 210),
    "spruce_fence": (100, 74, 42),
    "decorated_pot": (112, 78, 58),
    "barrel": (96, 68, 38),
}
# 블록데이터 문자열은 어미로 색을 고른다
DATA_COLOR = {"door": (90, 66, 36), "fence_gate": (76, 52, 28), "trapdoor": (66, 46, 26)}


def color_of(mat):
    if "light[" in mat:
        return None                 # 보이지 않는 광원 — 그리지 않는다
    if mat in COLORS:
        return COLORS[mat]
    for key, c in DATA_COLOR.items():
        if key in mat:
            return c
    return (200, 60, 200)   # 모르는 재질 — 보라로 소리낸다


def load(name):
    path = ROOT / "config" / "stages" / f"{name}.stage.yml"
    cfg = yaml.safe_load(path.read_text(encoding="utf-8"))
    legend = cfg["legend"]
    w, d = cfg["meta"]["size"]
    layers = []
    bad = []
    for lname, art in sorted(cfg["layers"].items()):
        rows = art.rstrip("\n").split("\n")
        if len(rows) != d:
            bad.append(f"{lname}: 행 {len(rows)} ≠ {d}")
        grid = []
        for r, line in enumerate(rows):
            if len(line) != w:
                bad.append(f"{lname} r{r}: 폭 {len(line)} ≠ {w}")
            cells = []
            for ch in line:
                if ch not in legend:
                    bad.append(f"{lname} r{r}: 범례 밖 문자 {ch!r}")
                    cells.append("air")
                else:
                    cells.append(str(legend[ch]))
            grid.append(cells)
        layers.append((lname, grid))
    for sname, (c, r) in (cfg.get("spots") or {}).items():
        if not (0 <= c < w and 0 <= r < d):
            bad.append(f"spot {sname}: 도면 밖 [{c},{r}]")
        elif layers and layers[1][1][r][c] != "air" and "carpet" not in layers[1][1][r][c]:
            bad.append(f"spot {sname}: y1 에 설 수 없다 (벽·세간) [{c},{r}]")
    return cfg, layers, bad


def render_plan(cfg, layers, out):
    w, d = cfg["meta"]["size"]
    s = 22
    img = Image.new("RGB", (w * s, d * s), (18, 18, 22))
    dr = ImageDraw.Draw(img)
    for r in range(d):
        for c in range(w):
            top = None
            height = 0
            for i, (_, grid) in enumerate(layers):
                if grid[r][c] != "air":
                    top, height = grid[r][c], i
            if top is None:
                continue
            col = color_of(top)
            if col is None:
                continue
            shade = 0.55 + 0.11 * height           # 높을수록 밝다 — 평면도에서 층이 읽힌다
            dr.rectangle([c * s, r * s, c * s + s - 1, r * s + s - 1],
                         fill=tuple(min(255, int(v * shade)) for v in col))
    for sname, (c, r) in (cfg.get("spots") or {}).items():
        dr.ellipse([c * s + 5, r * s + 5, c * s + s - 5, r * s + s - 5],
                   outline=(140, 230, 240), width=2)
        dr.text((c * s + 2, r * s - 10), sname[:6], fill=(140, 230, 240))
    img.save(out)


def render_iso(cfg, layers, out):
    w, d = cfg["meta"]["size"]
    hw, hh, vz = 12, 6, 12                          # 다이아 반폭·반높이·층 높이
    W = (w + d) * hw + 40
    H = (w + d) * hh + len(layers) * vz + 80
    img = Image.new("RGB", (W, H), (14, 14, 18))
    dr = ImageDraw.Draw(img)
    ox, oy = d * hw + 20, 40

    def px(c, r, y):
        return ox + (c - r) * hw, oy + (c + r) * hh + (len(layers) - y) * vz

    for r in range(d):                              # 뒤(북)→앞(남) · 아래층→위층
        for c in range(w):
            for y, (_, grid) in enumerate(layers):
                mat = grid[r][c]
                if mat == "air":
                    continue
                col = color_of(mat)
                if col is None:
                    continue
                x0, y0 = px(c, r, y)
                top = [(x0, y0 - hh), (x0 + hw, y0), (x0, y0 + hh), (x0 - hw, y0)]
                left = [(x0 - hw, y0), (x0, y0 + hh), (x0, y0 + hh + vz), (x0 - hw, y0 + vz)]
                right = [(x0 + hw, y0), (x0, y0 + hh), (x0, y0 + hh + vz), (x0 + hw, y0 + vz)]
                dr.polygon(left, fill=tuple(int(v * 0.55) for v in col))
                dr.polygon(right, fill=tuple(int(v * 0.75) for v in col))
                dr.polygon(top, fill=col)
    for sname, (c, r) in (cfg.get("spots") or {}).items():
        x0, y0 = px(c, r, 1)
        dr.ellipse([x0 - 4, y0 - hh - 14, x0 + 4, y0 - hh - 6], fill=(140, 230, 240))
        dr.text((x0 + 6, y0 - hh - 16), sname[:8], fill=(140, 230, 240))
    img.save(out)


def render_mood(cfg, layers, out):
    """분위기 렌더 — 밤 워시 + 광원 글로우 (쉐이더의 근사 · 접속 없이 빛의 유도를 검수한다).
    광원: 도면의 soul_lantern 자동 감지 + meta.lights([[col,row,색이름], …] — 화광 등 연출 광원)."""
    w, d = cfg["meta"]["size"]
    hw, hh, vz = 12, 6, 12
    W = (w + d) * hw + 40
    H = (w + d) * hh + len(layers) * vz + 80
    base = Image.open(OUT / f"{cfg['_name']}_iso.png").convert("RGB")
    # ① 밤 워시 — 푸르게 가라앉힌다 (달밤)
    night = Image.new("RGB", base.size, (24, 30, 52))
    img = Image.blend(base, night, 0.45)
    # ② 광원 수집 — 등잔(자동) + 연출 광원(meta.lights)
    ox, oy = d * hw + 20, 40
    glow_colors = {"warm": (255, 190, 110), "fire": (255, 130, 50), "moon": (180, 200, 235),
                   "soul": (130, 210, 220)}
    lights = []
    for y, (_, grid) in enumerate(layers):
        for r in range(d):
            for c in range(w):
                if grid[r][c] == "soul_lantern":
                    lights.append((c, r, y, "soul", 60))
    for spec in (cfg["meta"].get("lights") or []):
        c, r = int(spec[0]), int(spec[1])
        lights.append((c, r, 2, str(spec[2]) if len(spec) > 2 else "warm", 80))
    # ③ 글로우 — 가산 방사 (PIL: 작은 원들을 겹쳐 근사)
    from PIL import ImageChops
    overlay = Image.new("RGB", base.size, (0, 0, 0))
    dr = ImageDraw.Draw(overlay)
    for c, r, y, kind, radius in lights:
        x0 = ox + (c - r) * hw
        y0 = oy + (c + r) * hh + (len(layers) - y) * vz
        col = glow_colors.get(kind, glow_colors["warm"])
        for i in range(radius, 4, -6):
            a = max(6, int(70 * (1 - i / radius)))
            dr.ellipse([x0 - i, y0 - i // 2 - 6, x0 + i, y0 + i // 2 - 6],
                       fill=tuple(v * a // 255 for v in col))
    img = ImageChops.add(img, overlay)
    img.save(out)


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    name = sys.argv[1]
    cfg, layers, bad = load(name)
    if bad:
        print(f"❌ 도면이 어긋났다 ({len(bad)}건):")
        for b in bad:
            print("  ·", b)
        return 1
    OUT.mkdir(parents=True, exist_ok=True)
    cfg["_name"] = name
    render_plan(cfg, layers, OUT / f"{name}_plan.png")
    render_iso(cfg, layers, OUT / f"{name}_iso.png")
    render_mood(cfg, layers, OUT / f"{name}_mood.png")
    w, d = cfg["meta"]["size"]
    print(f"✅ {cfg['meta']['name']} — {w}×{d} · 층 {len(layers)} · spots {len(cfg.get('spots') or {})}")
    print(f"   {OUT / (name + '_plan.png')}")
    print(f"   {OUT / (name + '_iso.png')}")
    print(f"   {OUT / (name + '_mood.png')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
