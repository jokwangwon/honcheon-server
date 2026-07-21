#!/usr/bin/env python3
"""검기 **그림체 5종** — 실루엣은 하나로 고정하고, 칠하는 방식만 가른다.

【핵심 · 2026-07-21 사용자 정정】
  실루엣(X자·S자·삼선…)은 나중에 무기별·문파별·기술별로 분류될 것이다.
  지금 정하는 것은 **그림체의 골격** — 같은 획을 무엇으로 어떻게 그리느냐.
  그래서 표준 호 실루엣 하나에 다섯 가지 그림체를 입힌다:

    1 셀 애니   — 경계가 딱 떨어지는 색 띠 (흰 심·색 띠·먹 윤곽). 그러데이션 없음.
    2 발광 글로우 — 부드럽게 빛나는 에너지. 겹겹의 블러.
    3 수묵 붓   — 붓 눌림·갈필·먹 번짐. 종이에 그은 획.
    4 픽셀 도트 — 굵은 픽셀·제한 팔레트·디더링. 마크 본연의 도트.
    5 금속 날   — 강한 명암 대비의 날카로운 금속 반사. 얇은 하이라이트 줄.

  색은 전부 등록부(먹·청회·청백) — 그림체가 다른 것이지 세계가 다른 게 아니다.
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

# ── 고정 실루엣 — 표준 참격 호 (왼꼬리 → 오른머리) ──────────────────────
CX, CY, R = W * 0.5, W * 1.02, W * 0.64
A0, A1 = math.radians(-146), math.radians(-33)
N = 260
TS = [i / (N - 1) for i in range(N)]


def pt(t: float, dr: float = 0.0):
    a = A0 + (A1 - A0) * t
    return CX + (R + dr) * math.cos(a), CY + (R + dr) * math.sin(a)


def taper(t: float, head: float = 0.10, tail_pow: float = 1.2) -> float:
    if t > 1.0 - head:
        return max(0.0, (1.0 - t) / head) ** 0.65 * ((1.0 - head) ** tail_pow)
    return t ** tail_pow


BASE_W = W * 0.058
PTS = [pt(t) for t in TS]
WS = [BASE_W * taper(t) for t in TS]


def noise(x: float, seed: int = 0) -> float:
    n = math.sin(x * 127.1 + seed * 74.7) * 43758.5453
    return n - math.floor(n)


def canvas():
    return Image.new("RGBA", (W, W), (0, 0, 0, 0))


def stroke(d, pts, ws, color, alpha=255):
    for (x, y), w in zip(pts, ws):
        if w > 0:
            d.ellipse([x - w, y - w, x + w, y + w], fill=(*color, alpha))


# ── 1 셀 애니 — 딱 떨어지는 색 띠 ───────────────────────────────────────
def style_cel() -> Image.Image:
    im = canvas()
    for scale, col in ((1.30, MEOK), (1.00, CHEONGHOE_DEEP), (0.62, WHITE), (0.30, CHEONGBAEK)):
        lay = canvas(); d = ImageDraw.Draw(lay)
        stroke(d, PTS, [w * scale for w in WS], col, 255)
        im.alpha_composite(lay)          # 블러 없음 — 경계가 칼같이 떨어진다
    return im.resize((OUT, OUT), Image.LANCZOS)


# ── 2 발광 글로우 — 겹겹의 빛 ───────────────────────────────────────────
def style_glow() -> Image.Image:
    im = canvas()
    for scale, col, alpha, blur in ((2.1, CHEONGHOE_DEEP, 110, 3.2), (1.5, CHEONGHOE, 150, 1.8),
                                     (0.95, CHEONGBAEK, 220, 0.9), (0.50, WHITE, 255, 0.0)):
        lay = canvas(); d = ImageDraw.Draw(lay)
        stroke(d, PTS, [w * scale for w in WS], col, alpha)
        if blur > 0:
            lay = lay.filter(ImageFilter.GaussianBlur(SS * blur))
        im.alpha_composite(lay)
    return im.resize((OUT, OUT), Image.LANCZOS)


# ── 3 수묵 붓 — 종이에 그은 획 ──────────────────────────────────────────
def style_ink() -> Image.Image:
    im = canvas()
    def press(t):
        return taper(t, head=0.13, tail_pow=0.9) * (0.7 + 0.4 * noise(t * 8.0, 3))
    # 담묵 번짐 (종이에 스민 물기)
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, PTS, [BASE_W * press(t) * 1.6 for t in TS], CHEONGHOE, 60)
    lay = lay.filter(ImageFilter.GaussianBlur(SS * 2.4))
    im.alpha_composite(lay)
    # 본획 (농묵)
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, PTS, [BASE_W * press(t) for t in TS], MEOK, 250)
    im.alpha_composite(lay)
    # 갈필 — 꼬리 절반에 마른 평행 줄기
    lay = canvas(); d = ImageDraw.Draw(lay)
    for off in (-0.5, -0.15, 0.3, 0.62):
        seg = [t for t in TS if t < 0.45]
        stroke(d, [pt(t, BASE_W * off) for t in seg],
               [BASE_W * press(t) * 0.15 for t in seg], MEOK, 190)
    im.alpha_composite(lay)
    # 인선 — 날 끝 1/4 에만 청백 물빛
    lay = canvas(); d = ImageDraw.Draw(lay)
    seg = [t for t in TS if t > 0.72]
    stroke(d, [pt(t, BASE_W * 0.35) for t in seg],
           [BASE_W * taper(t, 0.10) * 0.24 for t in seg], CHEONGBAEK, 225)
    im.alpha_composite(lay)
    return im.resize((OUT, OUT), Image.LANCZOS)


# ── 4 픽셀 도트 — 마크 본연의 도트 ──────────────────────────────────────
def style_pixel() -> Image.Image:
    # 32×32 로 그려 최근접 확대 — 굵은 픽셀이 살아 있다
    small = 32
    im = Image.new("RGBA", (small, small), (0, 0, 0, 0))
    px = im.load()
    # 호를 픽셀 단위로 훑는다
    for i in range(400):
        t = i / 399
        x, y = pt(t)
        x, y = x / W * small, y / W * small
        w = max(0.65, BASE_W * taper(t) / W * small)   # 꼬리도 최소 1px 는 찍힌다
        pal_r = int(max(1, round(w * 1.3)))
        for dx in range(-pal_r, pal_r + 1):
            for dy in range(-pal_r, pal_r + 1):
                dd = math.hypot(dx, dy)
                if dd > w * 1.3:
                    continue
                xi, yi = int(x + dx), int(y + dy)
                if not (0 <= xi < small and 0 <= yi < small):
                    continue
                # 팔레트 4단 — 안(흰) → 청백 → 청회 → 먹 (디더링: 체커보드)
                if dd <= w * 0.45:
                    c = WHITE
                elif dd <= w * 0.8:
                    c = CHEONGBAEK if (xi + yi) % 2 == 0 else WHITE
                elif dd <= w * 1.05:
                    c = CHEONGHOE_DEEP
                else:
                    c = MEOK if (xi + yi) % 2 == 0 else CHEONGHOE_DEEP
                cur = px[xi, yi]
                if cur[3] == 0 or sum(c[:3]) > sum(cur[:3]):
                    px[xi, yi] = (*c, 255)
    return im.resize((OUT, OUT), Image.NEAREST)


# ── 5 금속 날 — 날카로운 반사 ───────────────────────────────────────────
def style_metal() -> Image.Image:
    im = canvas()
    # 어두운 몸 (블러 없음, 좁게)
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, PTS, [w * 1.05 for w in WS], MEOK, 250)
    im.alpha_composite(lay)
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, PTS, [w * 0.85 for w in WS], CHEONGHOE_DEEP, 255)
    im.alpha_composite(lay)
    # 금속 반사 — 얇고 강한 하이라이트 줄 셋 (위·중앙·아래, 서로 어긋난 구간)
    lay = canvas(); d = ImageDraw.Draw(lay)
    for off, (t0, t1), wmul, col in ((0.45, (0.30, 0.98), 0.16, WHITE),
                                      (0.0, (0.05, 0.72), 0.12, CHEONGBAEK),
                                      (-0.42, (0.42, 0.88), 0.09, WHITE)):
        seg = [t for t in TS if t0 <= t <= t1]
        stroke(d, [pt(t, BASE_W * off) for t in seg],
               [BASE_W * taper(t) * wmul for t in seg], col, 255)
    im.alpha_composite(lay)
    # 날 끝 반짝 (십자 아님 — 금속의 점광)
    x, y = pt(0.97)
    lay = canvas(); d = ImageDraw.Draw(lay)
    r = W * 0.018
    d.ellipse([x - r, y - r, x + r, y + r], fill=(*WHITE, 255))
    im.alpha_composite(lay)
    return im.resize((OUT, OUT), Image.LANCZOS)


STYLES = [
    ("1", "셀 애니", style_cel, "경계가 딱 떨어지는 색 띠 — 그러데이션 없음"),
    ("2", "발광 글로우", style_glow, "겹겹이 빛나는 에너지 — 부드러운 광채"),
    ("3", "수묵 붓", style_ink, "붓 눌림·갈필·먹 번짐 — 종이의 획"),
    ("4", "픽셀 도트", style_pixel, "굵은 픽셀·디더링 — 마크 본연의 도트"),
    ("5", "금속 날", style_metal, "강한 명암의 금속 반사 — 얇은 하이라이트"),
]


def main():
    out = Path("작업물/검기/그림체")
    out.mkdir(parents=True, exist_ok=True)
    cell = OUT * 5
    pad = 8
    sheet = Image.new("RGB", ((cell + pad) * 5 + pad, (cell + pad) * 2 + pad), (16, 18, 21))
    for j, bgc in enumerate([(24, 27, 31), (196, 203, 208)]):
        for i, (key, name, fn, desc) in enumerate(STYLES):
            img = fn()
            img.save(out / f"{key}.png")
            big = img.resize((cell, cell), Image.NEAREST)
            bg = Image.new("RGBA", (cell, cell), (*bgc, 255))
            bg.alpha_composite(big)
            sheet.paste(bg.convert("RGB"), (pad + i * (cell + pad), pad + j * (cell + pad)))
    p = out / "그림체_5종.png"
    sheet.save(p)
    print(f"  그렸다: {p}")
    for key, name, _, desc in STYLES:
        print(f"    {key}. {name} — {desc}")


if __name__ == "__main__":
    main()
