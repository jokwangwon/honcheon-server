#!/usr/bin/env python3
"""무대 1인칭 렌더 — 게임 시점의 근사 (B-194).

「단순 도면도가 아닌 실 플레이 분위기를 보고 싶다」(사용자 2026-07-31) — 접속 없이
그 자리에 선 눈(눈높이 1.62)으로 무대를 본다. 간이 레이캐스트: 달빛 + 등잔 + 연출 광원.

사용법:  python3 tools/stage_pov.py <무대이름>
출력:    run/stage_render/<이름>_pov_<시점>.png  (시점은 무대 yml 의 pov_views)
"""
import math
import sys
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from stage_render import COLORS, DATA_COLOR, OUT, color_of, load   # noqa: E402

FOG = (16, 20, 34)          # 밤하늘·원경
AMBIENT = 0.22              # 달밤의 바닥 밝기
MOON = (-0.35, -0.8, 0.25)  # 달빛 방향 (서쪽 하늘에서)


def solid(mat):
    return mat != "air" and "carpet" not in mat   # 카펫은 밟는 것 — 시선은 통과시킨다 (얇음)


def build_grid(layers, w, d):
    grid = {}
    for y, (_, g) in enumerate(layers):
        for r in range(d):
            for c in range(w):
                if g[r][c] != "air":
                    grid[(c, y, r)] = g[r][c]
    return grid


def lights_of(cfg, layers, w, d):
    out = []
    for y, (_, g) in enumerate(layers):
        for r in range(d):
            for c in range(w):
                if g[r][c] == "soul_lantern":
                    out.append(((c + .5, y + .6, r + .5), (140, 215, 225), 2.6, False))
    for spec in (cfg["meta"].get("lights") or []):
        c, r = float(spec[0]), float(spec[1])
        kind = str(spec[2]) if len(spec) > 2 else "warm"
        col = {"fire": (255, 140, 60), "warm": (255, 195, 120), "moon": (185, 205, 240)}.get(kind, (255, 195, 120))
        out.append(((c + .5, 2.2, r + .5), col, 2.0, True))     # 발광체 — 틈 너머의 불 그 자체
        out.append(((c - .8, 2.3, r + .5), col, 0.35, False))   # 안쪽 미광 — 틈 테두리만 데운다
    return out


def ray(grid, w, d, h, pos, dirv, lights):
    x, y, z = pos
    dx, dy, dz = dirv
    step = 0.05
    for i in range(1, 1200):
        x += dx * step
        y += dy * step
        z += dz * step
        for (lx, ly, lz), lcol, lstr, emissive in lights:
            if emissive and (x - lx) ** 2 + (y - ly) ** 2 + (z - lz) ** 2 < 0.30:
                return lcol                             # 발광체 — 화광 그 자체 (틈 너머의 불)
        cell = (int(math.floor(x)), int(math.floor(y)), int(math.floor(z)))
        mat = grid.get(cell)
        if mat is not None and "carpet" in mat and (y - math.floor(y)) < 0.08:
            pass                                        # 깔개 윗면 — 아래 hit 로직으로
        elif mat is None or not solid(mat):
            mat = None
        if mat is not None:
            base = color_of(mat)
            # 면 노멀 근사 — 직전 칸과의 차이로
            px, py, pz = x - dx * step, y - dy * step, z - dz * step
            pcell = (int(math.floor(px)), int(math.floor(py)), int(math.floor(pz)))
            n = tuple(a - b for a, b in zip(pcell, cell))
            lam = max(0.0, -(n[0] * MOON[0] + n[1] * MOON[1] + n[2] * MOON[2]))
            face = {(0, 1, 0): 1.0, (0, -1, 0): 0.45}.get(n, 0.72 if n[0] else 0.6)
            lum = AMBIENT + 0.5 * lam
            r_, g_, b_ = (v * lum * face for v in base)
            # 점광 — 등잔·화광 (거리 감쇠 · 차폐는 안 잰다: 근사)
            for (lx, ly, lz), lcol, lstr, emissive in lights:
                if emissive:
                    continue                            # 발광체는 면을 비추지 않는다 (차폐 근사)
                dd = (x - lx) ** 2 + (y - ly) ** 2 + (z - lz) ** 2
                k = lstr / (1.0 + dd * 0.28)
                r_ += lcol[0] * k * 0.75
                g_ += lcol[1] * k * 0.75
                b_ += lcol[2] * k * 0.75
            # 거리 안개 — 밤이 삼킨다
            dist = i * step
            f = min(1.0, dist / 26.0)
            return tuple(int(min(255, v) * (1 - f) + FOG[j] * f)
                         for j, v in enumerate((r_, g_, b_)))
        if y < -1 or y > h + 4:
            break
    return None


def render_view(cfg, grid, lights, w, d, h, name, view, out):
    W, H = 520, 330
    pos = tuple(float(v) for v in view["pos"])
    yaw = math.radians(float(view["yaw"]))          # 0 = +x(동) · 90 = +z(남)
    pitch = math.radians(float(view.get("pitch", -6)))
    fwd = (math.cos(yaw) * math.cos(pitch), math.sin(pitch), math.sin(yaw) * math.cos(pitch))
    right = (-math.sin(yaw), 0.0, math.cos(yaw))
    up = (fwd[1] * right[2] - fwd[2] * right[1],
          fwd[2] * right[0] - fwd[0] * right[2],
          fwd[0] * right[1] - fwd[1] * right[0])
    half = math.tan(math.radians(35))
    img = Image.new("RGB", (W, H))
    px = img.load()
    for j in range(H):
        for i in range(W):
            u = (2 * i / W - 1) * half
            v = (1 - 2 * j / H) * half * H / W
            dirv = tuple(fwd[k] + u * right[k] - v * up[k] for k in range(3))
            ln = math.sqrt(sum(c * c for c in dirv))
            dirv = tuple(c / ln for c in dirv)
            hit = ray(grid, w, d, h, pos, dirv, lights)
            if hit is None:
                sky = 1 - j / H
                hit = tuple(int(c * (0.6 + 0.4 * sky)) for c in FOG)
            px[i, j] = hit
    img.save(out)


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    name = sys.argv[1]
    cfg, layers, bad = load(name)
    if bad:
        print("❌ 도면이 어긋났다 — stage_render 를 먼저 통과시켜라")
        return 1
    w, d = cfg["meta"]["size"]
    grid = build_grid(layers, w, d)
    lights = lights_of(cfg, layers, w, d)
    views = cfg["meta"].get("pov_views") or {}
    if not views:
        print("meta.pov_views 가 없다 — 시점을 도면에 등재하라")
        return 1
    OUT.mkdir(parents=True, exist_ok=True)
    for vname, view in views.items():
        out = OUT / f"{name}_pov_{vname}.png"
        render_view(cfg, grid, lights, w, d, len(layers), vname, view, out)
        print(f"   {out}")
    print(f"✅ 시점 {len(views)}컷")
    return 0


if __name__ == "__main__":
    sys.exit(main())
