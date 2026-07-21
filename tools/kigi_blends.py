#!/usr/bin/env python3
"""검기 그림체 — 2(글로우)·3(수묵)·5(금속) **혼합 대조군** (사용자 요청 · 2026-07-21).

  굵기를 키워(BASE_W 0.058 → 0.088) 비교하기 쉽게 하고,
  순수 2·3·5 세 기준 + 혼합 네 가지를 나란히 놓는다:
    2      발광 글로우 (기준)
    3      수묵 붓     (기준)
    5      금속 날     (기준)
    2+3    빛나는 붓   — 붓 눌림·갈필 위에 광채
    2+5    빛나는 서슬 — 글로우 몸 + 금속 하이라이트
    3+5    먹의 서슬   — 먹 붓 몸 + 금속 하이라이트 (발광 없음)
    2+3+5  삼합        — 붓 몸 + 광채 + 서슬
"""
from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

MEOK = (38, 46, 54)
CHEONGHOE = (124, 143, 152)
CHEONGHOE_DEEP = (86, 128, 148)
CHEONGBAEK = (226, 240, 238)
WHITE = (246, 251, 251)

SS = 4
OUT = 64
W = OUT * SS

CX, CY, R = W * 0.5, W * 1.02, W * 0.64
A0, A1 = math.radians(-146), math.radians(-33)
N = 260
TS = [i / (N - 1) for i in range(N)]
BASE_W = W * 0.088          # ★굵힘 (전 0.058) — 비교하기 쉽게


def pt(t: float, dr: float = 0.0):
    a = A0 + (A1 - A0) * t
    return CX + (R + dr) * math.cos(a), CY + (R + dr) * math.sin(a)


def taper(t: float, head: float = 0.10, tail_pow: float = 1.2) -> float:
    if t > 1.0 - head:
        return max(0.0, (1.0 - t) / head) ** 0.65 * ((1.0 - head) ** tail_pow)
    return t ** tail_pow


def noise(x: float, seed: int = 0) -> float:
    n = math.sin(x * 127.1 + seed * 74.7) * 43758.5453
    return n - math.floor(n)


def canvas():
    return Image.new("RGBA", (W, W), (0, 0, 0, 0))


def stroke(d, pts, ws, color, alpha=255):
    for (x, y), w in zip(pts, ws):
        if w > 0:
            d.ellipse([x - w, y - w, x + w, y + w], fill=(*color, alpha))


def press(t: float) -> float:
    """붓 눌림 (수묵 요소) — 폭이 손힘 따라 흔들린다."""
    return taper(t, head=0.13, tail_pow=0.9) * (0.7 + 0.4 * noise(t * 8.0, 3))


def width_fn(inky: bool):
    return press if inky else (lambda t: taper(t))


# ── 요소 층들 (조립식) ───────────────────────────────────────────────────
def layer_glow(im, wf, strength=1.0):
    """2 글로우 — 겹겹의 빛."""
    for scale, col, alpha, blur in ((2.0, CHEONGHOE_DEEP, int(110 * strength), 3.0),
                                     (1.45, CHEONGHOE, int(150 * strength), 1.7),
                                     (0.92, CHEONGBAEK, int(215 * strength), 0.8)):
        lay = canvas(); d = ImageDraw.Draw(lay)
        stroke(d, [pt(t) for t in TS], [BASE_W * wf(t) * scale for t in TS], col, alpha)
        lay = lay.filter(ImageFilter.GaussianBlur(SS * blur))
        im.alpha_composite(lay)


def layer_ink_body(im, wf, dark=True):
    """3 수묵 — 먹 본획 + 갈필."""
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, [pt(t) for t in TS], [BASE_W * wf(t) for t in TS],
           MEOK if dark else CHEONGHOE_DEEP, 250)
    im.alpha_composite(lay)
    lay = canvas(); d = ImageDraw.Draw(lay)
    for off in (-0.5, -0.15, 0.3, 0.62):
        seg = [t for t in TS if t < 0.45]
        stroke(d, [pt(t, BASE_W * off) for t in seg],
               [BASE_W * wf(t) * 0.15 for t in seg], MEOK, 190)
    im.alpha_composite(lay)


def layer_ink_bleed(im, wf):
    """3 수묵 — 종이에 스민 담묵."""
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, [pt(t) for t in TS], [BASE_W * wf(t) * 1.6 for t in TS], CHEONGHOE, 60)
    lay = lay.filter(ImageFilter.GaussianBlur(SS * 2.4))
    im.alpha_composite(lay)


def layer_core(im, wf, scale=0.55):
    """흰 심."""
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, [pt(t) for t in TS], [BASE_W * wf(t) * scale for t in TS], WHITE, 255)
    im.alpha_composite(lay)


def layer_metal(im, wf):
    """5 금속 — 얇고 강한 하이라이트 줄 셋 + 끝 점광."""
    lay = canvas(); d = ImageDraw.Draw(lay)
    for off, (t0, t1), wmul, col in ((0.45, (0.30, 0.98), 0.16, WHITE),
                                      (0.0, (0.05, 0.72), 0.12, CHEONGBAEK),
                                      (-0.42, (0.42, 0.88), 0.09, WHITE)):
        seg = [t for t in TS if t0 <= t <= t1]
        stroke(d, [pt(t, BASE_W * off) for t in seg],
               [BASE_W * wf(t) * wmul for t in seg], col, 255)
    x, y = pt(0.97)
    r = W * 0.018
    d.ellipse([x - r, y - r, x + r, y + r], fill=(*WHITE, 255))
    im.alpha_composite(lay)


def layer_dark_body(im, wf):
    """5 금속의 어두운 몸 (블러 없음)."""
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, [pt(t) for t in TS], [BASE_W * wf(t) * 1.05 for t in TS], MEOK, 250)
    im.alpha_composite(lay)
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, [pt(t) for t in TS], [BASE_W * wf(t) * 0.85 for t in TS], CHEONGHOE_DEEP, 255)
    im.alpha_composite(lay)


# ── 일곱 판 ──────────────────────────────────────────────────────────────
def v_2() -> Image.Image:      # 발광 글로우 (기준)
    im = canvas(); wf = width_fn(False)
    layer_glow(im, wf)
    layer_core(im, wf, 0.50)
    return im.resize((OUT, OUT), Image.LANCZOS)


def v_3() -> Image.Image:      # 수묵 붓 (기준)
    im = canvas(); wf = width_fn(True)
    layer_ink_bleed(im, wf)
    layer_ink_body(im, wf)
    # 인선 — 날 끝 1/4 만 청백
    lay = canvas(); d = ImageDraw.Draw(lay)
    seg = [t for t in TS if t > 0.72]
    stroke(d, [pt(t, BASE_W * 0.35) for t in seg],
           [BASE_W * taper(t, 0.10) * 0.24 for t in seg], CHEONGBAEK, 225)
    im.alpha_composite(lay)
    return im.resize((OUT, OUT), Image.LANCZOS)


def v_5() -> Image.Image:      # 금속 날 (기준)
    im = canvas(); wf = width_fn(False)
    layer_dark_body(im, wf)
    layer_metal(im, wf)
    return im.resize((OUT, OUT), Image.LANCZOS)


def v_23() -> Image.Image:     # 빛나는 붓
    im = canvas(); wf = width_fn(True)
    layer_glow(im, wf, strength=0.85)
    layer_ink_body(im, wf, dark=False)     # 붓 몸은 청회 (먹이면 빛을 먹는다)
    layer_core(im, wf, 0.45)
    return im.resize((OUT, OUT), Image.LANCZOS)


def v_25() -> Image.Image:     # 빛나는 서슬
    im = canvas(); wf = width_fn(False)
    layer_glow(im, wf, strength=0.9)
    layer_dark_body(im, wf)
    layer_metal(im, wf)
    return im.resize((OUT, OUT), Image.LANCZOS)


def v_35() -> Image.Image:     # 먹의 서슬 (발광 없음)
    im = canvas(); wf = width_fn(True)
    layer_ink_bleed(im, wf)
    layer_ink_body(im, wf)
    layer_metal(im, wf)
    return im.resize((OUT, OUT), Image.LANCZOS)


def v_235() -> Image.Image:    # 삼합
    im = canvas(); wf = width_fn(True)
    layer_glow(im, wf, strength=0.7)
    layer_ink_body(im, wf, dark=True)
    layer_metal(im, wf)
    layer_core(im, wf, 0.35)
    return im.resize((OUT, OUT), Image.LANCZOS)


BLENDS = [
    ("2", "발광 글로우 (기준)", v_2, "겹겹이 빛나는 에너지"),
    ("3", "수묵 붓 (기준)", v_3, "먹 본획 + 갈필 + 번짐"),
    ("5", "금속 날 (기준)", v_5, "어두운 몸 + 얇은 하이라이트"),
    ("2+3", "빛나는 붓", v_23, "붓 눌림 위에 광채 — 부드럽게 빛나는 획"),
    ("2+5", "빛나는 서슬", v_25, "글로우 몸 + 금속 하이라이트 — 빛나되 날카롭다"),
    ("3+5", "먹의 서슬", v_35, "먹 붓 몸 + 금속 줄 — 발광 없이 서슬만"),
    ("2+3+5", "삼합", v_235, "붓 몸 + 광채 + 서슬 — 전부 조금씩"),
]


def main():
    out = Path("작업물/검기/혼합")
    out.mkdir(parents=True, exist_ok=True)
    cell = OUT * 5
    pad = 8
    cols = len(BLENDS)
    sheet = Image.new("RGB", ((cell + pad) * cols + pad, (cell + pad) * 2 + pad), (16, 18, 21))
    for j, bgc in enumerate([(24, 27, 31), (196, 203, 208)]):
        for i, (key, name, fn, desc) in enumerate(BLENDS):
            img = fn()
            img.save(out / f"{key.replace('+', '')}.png")
            big = img.resize((cell, cell), Image.NEAREST)
            bg = Image.new("RGBA", (cell, cell), (*bgc, 255))
            bg.alpha_composite(big)
            sheet.paste(bg.convert("RGB"), (pad + i * (cell + pad), pad + j * (cell + pad)))
    p = out / "혼합_대조.png"
    sheet.save(p)
    print(f"  그렸다: {p}")
    for key, name, _, desc in BLENDS:
        print(f"    {key:6s} {name} — {desc}")


if __name__ == "__main__":
    main()
