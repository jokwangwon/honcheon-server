#!/usr/bin/env python3
"""검압(劍壓)형 검기 — 「압축된 절단파」 재설계 (2026-07-21 사용자·자문 합의).

【합의】 톱니·삼각 파편·병렬 3선 폐기. 수묵은 **팔레트와 소멸 잔광**에서만 유지하고,
형태는 세련된 절단파로 간다:
  ① 넓은 반투명 청회 검압 몸체 (검이 지나가며 공기가 압축된 영역)
  ② 절단면 쪽에만 얇은 청백 서슬 한 줄
  ③ 반대쪽 외곽에 좁고 진한 먹 테 (밝은 하늘 가독)
  ④ 본체와 평행하지 않은 불완전한 잔상 한 줄 (중간에 끊긴다)
  실루엣: 꼬리 가늘게 → 중앙 급팽창 → 머리 최대 → 끝은 비스듬히 잘린 붓칼 (한 점에서 안 만남)
채택되면 kigi_forge 로 승격한다.
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import kigi_forge as F
from PIL import Image, ImageDraw, ImageFilter

WHITE = (255, 255, 255)


def _lerp(a, b, t):
    return a + (b - a) * t


def _between(p, q, f):
    return (p[0] + (q[0] - p[0]) * f, p[1] + (q[1] - p[1]) * f)


def width_profile(t: float) -> float:
    """꼬리 20% 가늘게 → 중앙 50% 급팽창 → 머리 20% 최대 → 끝 10% 급수축(비스듬)."""
    if t < 0.20:
        return _lerp(0.10, 0.34, (t / 0.20) ** 0.8)
    if t < 0.70:
        return _lerp(0.34, 1.00, ((t - 0.20) / 0.50) ** 0.85)
    if t < 0.90:
        return 1.00
    return 1.00  # 끝의 수축은 가장자리별(비스듬)로 처리한다 — edge_cut 참조


def edge_cut(t: float, side: str) -> float:
    """비스듬한 붓칼 끝 — 바깥(먹 테) 선은 빨리 잘리고 절단면 선은 조금 더 뻗는다."""
    if side == "outer":
        return 1.0 if t < 0.925 else max(0.0, (0.975 - t) / 0.05)
    if t < 0.90:
        return 1.0
    return max(0.0, 1.0 - ((t - 0.90) / 0.10) ** 1.4 * 0.98)


def _edges(pts, wmax, edge_side):
    """중심선 → 바깥(볼록)·안(오목) 가장자리. edge_side='inner' 면 서슬이 오목쪽."""
    n = len(pts)
    outer, inner = [], []
    for i, (x, y) in enumerate(pts):
        t = i / (n - 1)
        w = wmax * width_profile(t)
        o = w * 0.42 * edge_cut(t, "outer")
        v = w * 0.58 * edge_cut(t, "inner")
        outer.append(F._normal(pts, i, -o))
        inner.append(F._normal(pts, i, v))
    return outer, inner


def press_brush(pts, ink: str = "청회", *, edge_side: str = "inner",
                body_alpha: float = 1.0, wmax: float = None) -> Image.Image:
    """검압형 붓 — 몸체(면) + 서슬(한 줄) + 먹 테(반대쪽) + 불완전 잔상."""
    pal = F.palette(ink)
    W = F.W
    wmax = wmax or W * 0.165
    n = len(pts)
    ts = [i / (n - 1) for i in range(n)]
    outer, inner = _edges(pts, wmax, edge_side)
    im = F.canvas()

    # ── ① 검압 몸체 — 반투명 청회, 꼬리 어둡고 머리로 갈수록 차오른다
    lay = F.canvas(); d = ImageDraw.Draw(lay)
    SLICES = 4                                  # 폭을 4켜로 갈라 서슬 쪽으로 짙어지게
    for i in range(n - 1):
        t = ts[i]
        col = F._mix(F._mix(pal["glow_deep"], pal["glow_mid"], min(1.0, t * 1.6)),
                     F.INKS["청백"], max(0.0, (t - 0.72) * 1.6))
        base_a = _lerp(60, 160, min(1.0, t * 1.35)) * body_alpha
        for k in range(SLICES):
            f0, f1 = k / SLICES, (k + 1) / SLICES
            if edge_side == "inner":            # 서슬이 오목(아래) — 아래 켜가 짙다
                dens = 0.35 + 0.65 * ((k + 0.5) / SLICES)
            else:
                dens = 0.35 + 0.65 * (1.0 - (k + 0.5) / SLICES)
            p00 = _between(outer[i], inner[i], f0); p01 = _between(outer[i], inner[i], f1)
            p10 = _between(outer[i + 1], inner[i + 1], f0); p11 = _between(outer[i + 1], inner[i + 1], f1)
            d.polygon([p00, p10, p11, p01], fill=(*col, int(base_a * dens)))
    im.alpha_composite(lay)

    # 몸 속 흐름 결 — 희미한 유선 2개 (병렬 3선 부활 금지: 옅고, 부분적이고, 폭이 다르다)
    lay = F.canvas(); d = ImageDraw.Draw(lay)
    for frac, (t0, t1), ww_m, al in ((0.30, (0.18, 0.66), 0.020, 60),
                                      (0.62, (0.34, 0.86), 0.030, 75)):
        for i in range(n):
            t = ts[i]
            if not (t0 <= t <= t1):
                continue
            x, y = _between(outer[i], inner[i], frac)
            ww = wmax * ww_m * (0.5 + t)
            fade = min(1.0, 6.0 * min(t - t0, t1 - t) / (t1 - t0))
            d.ellipse([x - ww, y - ww, x + ww, y + ww],
                      fill=(*F._mix(pal["glow_mid"], F.INKS["청백"], 0.4), int(al * fade)))
    im.alpha_composite(lay)

    # ── 머리 국소 번짐 (전체 후광 금지 — 머리·충돌점만)
    lay = F.canvas(); d = ImageDraw.Draw(lay)
    hx, hy = pts[int(n * 0.88)]
    r = wmax * 0.85
    d.ellipse([hx - r, hy - r, hx + r, hy + r], fill=(*F.INKS["청백"], 70))
    lay = lay.filter(ImageFilter.GaussianBlur(W / 64 * 2.0))
    im.alpha_composite(lay)

    # ── ④ 불완전한 잔상 한 줄 — 평행하지 않고, 중간에 끊긴다
    lay = F.canvas(); d = ImageDraw.Draw(lay)
    echo_col = F._mix(pal["body"], pal["glow_mid"], 0.45)
    for i in range(n - 1):
        t = ts[i]
        if not (0.05 <= t <= 0.60):
            continue
        gap = wmax * (0.55 - 0.28 * t)                         # 시작은 멀고 점점 가까워진다
        ex, ey = F._normal(pts, i, wmax * 0.58 + gap)
        ww = wmax * 0.040 * (0.5 + t)
        fade = min(1.0, (0.60 - t) / 0.14) * min(1.0, (t - 0.05) / 0.06)
        d.ellipse([ex - ww, ey - ww, ex + ww, ey + ww], fill=(*echo_col, int(135 * fade)))
    im.alpha_composite(lay)

    # ── ③ 먹 테 — 서슬 반대쪽 가장자리, 좁고 선명하게
    lay = F.canvas(); d = ImageDraw.Draw(lay)
    dark_edge = outer if edge_side == "inner" else inner
    for i in range(n):
        t = ts[i]
        cut = edge_cut(t, "outer" if edge_side == "inner" else "inner")
        if cut <= 0.4:
            continue
        cut = min(1.0, (cut - 0.4) / 0.35)
        ww = max(W / 256 * 1.3 * cut, wmax * 0.075 * (0.45 + 0.55 * width_profile(t)) * cut)
        x, y = dark_edge[i]
        d.ellipse([x - ww, y - ww, x + ww, y + ww], fill=(*pal["body"], 250))
    im.alpha_composite(lay)

    # ── ② 서슬 — 절단면 한 줄, 머리로 갈수록 희게
    lay = F.canvas(); d = ImageDraw.Draw(lay)
    lit_edge = inner if edge_side == "inner" else outer
    for i in range(n):
        t = ts[i]
        if t < 0.10:
            continue
        cut = edge_cut(t, "inner" if edge_side == "inner" else "outer")
        if cut <= 0.55:                       # 수렴 구간에 들어가면 긋지 않는다 — 갈고리 방지
            continue
        cut = min(1.0, (cut - 0.55) / 0.30)   # 끝을 뾰족하게
        col = F._mix(F.INKS["청백"], WHITE, max(0.0, (t - 0.6) * 1.8))
        ww = wmax * 0.055 * (0.5 + 0.7 * t) * cut
        ww = max(W / 256 * 1.1 * cut, ww)
        x, y = lit_edge[i]
        d.ellipse([x - ww, y - ww, x + ww, y + ww], fill=(*col, 255))
    im.alpha_composite(lay)

    return im.resize((F.OUT, F.OUT), Image.LANCZOS)


VARIANTS = [
    ("검압A 서슬 아래(오목)",  dict(edge_side="inner")),
    ("검압B 서슬 위(볼록)",    dict(edge_side="outer")),
    ("검압C A+몸 투명하게",    dict(edge_side="inner", body_alpha=0.62)),
]


def main():
    out = Path("작업물/검기/검압")
    out.mkdir(parents=True, exist_ok=True)
    F.set_res(256)
    try:
        pts = F.sil_arc_band()
        band_h = 112
        cw, ch, pad = 256 * 2, band_h * 2, 10
        for bgc, name in (((24, 27, 31), "어두운"), ((156, 190, 222), "밝은")):
            sheet = Image.new("RGB", (cw + pad * 2, (ch + pad + 26) * len(VARIANTS) + pad), (13, 15, 18))
            dr = ImageDraw.Draw(sheet)
            for i, (label, kw) in enumerate(VARIANTS):
                im = press_brush(pts, "청회", **kw)
                if name == "어두운":
                    im.save(out / f"{i}_{label.split()[0]}.png")
                band = im.crop((0, 0, 256, band_h)).resize((cw, ch), Image.NEAREST)
                bg = Image.new("RGBA", (cw, ch), (*bgc, 255))
                bg.alpha_composite(band)
                y = pad + i * (ch + pad + 26)
                dr.text((pad + 2, y + 2), label, fill=(225, 228, 230))
                sheet.paste(bg.convert("RGB"), (pad, y + 22))
            p = out / f"검압_3종_{name}.png"
            sheet.save(p)
            print(f"그렸다: {p}")
    finally:
        F.set_res(64)


if __name__ == "__main__":
    main()
