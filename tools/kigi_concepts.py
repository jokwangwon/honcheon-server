#!/usr/bin/env python3
"""검기 슬래시 **컨셉 5종** — 계열이 다른 다섯 스타일을 그려 사용자가 고른다.

  A 은섬(銀閃) — 가늘고 예리한 검광. 단단한 날, 발광 최소. 잘 벼린 칼의 섬광.
  B 월아(月牙) — 두툼한 에너지 파도. 속이 꽉 찬 초승달 덩어리 (기가 뭉친 투사체 느낌).
  C 수묵일획(水墨一劃) — 서예 붓획. 붓 눌림·갈필·먹 번짐. 무협 수묵.
  D 열풍(裂風) — 찢어진 참격. 들쭉한 가장자리 + 속도선. 사납다.
  E 쌍호(雙弧) — 겹친 두 호. 본획 + 따라오는 잔호 (이도류/잔상 느낌).

  전부 등록부의 색(먹·청회·청백)만 쓴다 — 스타일이 다른 것이지 세계가 다른 게 아니다.
  각 컨셉은 「최대 프레임」한 장씩 — 스타일을 고른 뒤에 3프레임 세트를 만든다.
"""
from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

MEOK = (38, 46, 54)
CHEONGHOE = (124, 143, 152)
CHEONGHOE_DEEP = (86, 128, 148)
CHEONGBAEK = (226, 240, 238)
WHITE = (246, 251, 251)

SS = 4
OUT = 64
W = OUT * SS

# 공통 호 — 캔버스 위쪽을 왼→오로 훑는다
CX, CY, R = W * 0.5, W * 1.04, W * 0.66
A0, A1 = math.radians(-146), math.radians(-32)


def noise(x: float, seed: int = 0) -> float:
    n = math.sin(x * 127.1 + seed * 74.7) * 43758.5453
    return n - math.floor(n)


def pt(t: float, dr: float = 0.0):
    a = A0 + (A1 - A0) * t
    r = R + dr
    return CX + r * math.cos(a), CY + r * math.sin(a)


def stroke(d: ImageDraw.ImageDraw, pts, ws, color, alpha=255):
    for (x, y), w in zip(pts, ws):
        if w > 0:
            d.ellipse([x - w, y - w, x + w, y + w], fill=(*color, alpha))


def canvas():
    return Image.new("RGBA", (W, W), (0, 0, 0, 0))


def taper(t: float, head: float = 0.10, tail_pow: float = 1.3) -> float:
    """양끝 다듬기 — 머리(t=1)는 짧고 뾰족, 꼬리(t=0)는 길게."""
    tip = max(0.0, 1.0 - max(0.0, t - (1.0 - head)) / head) ** 0.7
    return (t ** tail_pow) * tip if t < 1.0 - head else tip * ((1.0 - head) ** tail_pow)


TS = [i / 259 for i in range(260)]


# ── A 은섬 — 가늘고 예리한 검광 ─────────────────────────────────────────
def concept_A() -> Image.Image:
    im = canvas()
    base = W * 0.040
    # 먹 윤곽 (아주 얇게)
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, [pt(t) for t in TS], [base * taper(t) * 1.18 for t in TS], MEOK, 240)
    im.alpha_composite(lay)
    # 흰 날 — 가늘고 단단하게, 블러 없음
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, [pt(t) for t in TS], [base * taper(t) * 0.62 for t in TS], WHITE, 255)
    im.alpha_composite(lay)
    # 바깥날에 청백 실선 하나
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, [pt(t, base * 0.8) for t in TS], [base * taper(t) * 0.20 for t in TS], CHEONGBAEK, 235)
    im.alpha_composite(lay)
    return im.resize((OUT, OUT), Image.LANCZOS)


# ── B 월아 — 두툼한 에너지 파도 (속이 꽉 찬 초승달) ─────────────────────
def concept_B() -> Image.Image:
    im = canvas()
    # 초승달 폴리곤: 바깥 호 + 안쪽 호(반경 작게)를 이어 채운다
    depth = W * 0.16
    outer = [pt(t, 0) for t in TS]
    inner = [pt(t, -depth * math.sin(math.pi * t) ** 0.8) for t in reversed(TS)]
    poly = outer + inner
    # 먹 테 (살짝 크게)
    lay = canvas(); d = ImageDraw.Draw(lay)
    grow = [pt(t, base_r) for t in TS for base_r in ()]  # noop
    d.polygon(outer + [pt(t, -depth * math.sin(math.pi * t) ** 0.8 - W * 0.012) for t in reversed(TS)],
              fill=(*MEOK, 235))
    im.alpha_composite(lay)
    # 청회 몸 (채움)
    lay = canvas(); d = ImageDraw.Draw(lay)
    d.polygon(poly, fill=(*CHEONGHOE_DEEP, 250))
    lay = lay.filter(ImageFilter.GaussianBlur(SS * 0.4))
    im.alpha_composite(lay)
    # 흰 심 — 바깥 날을 따라 굵게
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, [pt(t, -W * 0.012) for t in TS],
           [W * 0.045 * math.sin(math.pi * t) ** 0.6 for t in TS], WHITE, 255)
    im.alpha_composite(lay)
    # 안쪽으로 청백 잔광
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, [pt(t, -depth * 0.55 * math.sin(math.pi * t) ** 0.8) for t in TS],
           [W * 0.02 * math.sin(math.pi * t) for t in TS], CHEONGBAEK, 160)
    lay = lay.filter(ImageFilter.GaussianBlur(SS * 1.2))
    im.alpha_composite(lay)
    return im.resize((OUT, OUT), Image.LANCZOS)


# ── C 수묵일획 — 서예 붓획 ──────────────────────────────────────────────
def concept_C() -> Image.Image:
    im = canvas()
    base = W * 0.062
    # 붓 눌림 — 폭이 울퉁불퉁 (손의 힘)
    def press(t):
        return taper(t, head=0.14, tail_pow=0.9) * (0.75 + 0.35 * noise(t * 9.0, 3))
    # 먹 본획
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, [pt(t) for t in TS], [base * press(t) for t in TS], MEOK, 250)
    im.alpha_composite(lay)
    # 갈필 — 꼬리 쪽에 얇은 평행 줄기 (마른 붓)
    lay = canvas(); d = ImageDraw.Draw(lay)
    for k, off in enumerate((-0.55, -0.2, 0.25, 0.6)):
        seg = [t for t in TS if t < 0.42]
        stroke(d, [pt(t, base * off) for t in seg],
               [base * press(t) * 0.16 for t in seg], MEOK, 200)
    im.alpha_composite(lay)
    # 담묵 번짐 (블러 겹)
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, [pt(t) for t in TS], [base * press(t) * 1.5 for t in TS], CHEONGHOE, 70)
    lay = lay.filter(ImageFilter.GaussianBlur(SS * 2.2))
    im.alpha_composite(lay)
    # 날 끝 청백 한 점 — 획의 눈
    lay = canvas(); d = ImageDraw.Draw(lay)
    seg = [t for t in TS if t > 0.80]
    stroke(d, [pt(t, base * 0.3) for t in seg],
           [base * taper(t, head=0.10) * 0.22 for t in seg], CHEONGBAEK, 235)
    im.alpha_composite(lay)
    return im.resize((OUT, OUT), Image.LANCZOS)


# ── D 열풍 — 찢어진 참격 ────────────────────────────────────────────────
def concept_D() -> Image.Image:
    im = canvas()
    base = W * 0.058
    # 들쭉한 폭 — 사나운 가장자리
    def jag(t, seed):
        return taper(t, head=0.08, tail_pow=1.1) * (0.55 + 0.75 * noise(t * 23.0, seed))
    # 먹 윤곽
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, [pt(t, base * (noise(t * 31.0, 7) - 0.5) * 0.9) for t in TS],
           [base * jag(t, 5) * 1.2 for t in TS], MEOK, 240)
    im.alpha_composite(lay)
    # 흰 심 (들쭉)
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, [pt(t, base * (noise(t * 31.0, 7) - 0.5) * 0.9) for t in TS],
           [base * jag(t, 5) * 0.6 for t in TS], WHITE, 255)
    im.alpha_composite(lay)
    # 속도선 — 획 위아래 찢긴 실선들
    lay = canvas(); d = ImageDraw.Draw(lay)
    for k, (t0, t1, off) in enumerate([(0.06, 0.34, 1.5), (0.14, 0.52, -1.4),
                                        (0.30, 0.62, 2.1), (0.52, 0.86, -1.9)]):
        seg = [t for t in TS if t0 <= t <= t1]
        stroke(d, [pt(t, base * off) for t in seg],
               [base * 0.10 * (1.0 - abs((t - (t0 + t1) / 2) / ((t1 - t0) / 2))) for t in seg],
               CHEONGHOE, 230)
    im.alpha_composite(lay)
    return im.resize((OUT, OUT), Image.LANCZOS)


# ── E 쌍호 — 겹친 두 호 (본획 + 잔호) ──────────────────────────────────
def concept_E() -> Image.Image:
    im = canvas()
    base = W * 0.048
    # 잔호 (아래·뒤에 옅게)
    lay = canvas(); d = ImageDraw.Draw(lay)
    lag = [t for t in TS if t < 0.80]
    stroke(d, [pt(t, -W * 0.075) for t in lag],
           [base * taper(t / 0.8 if t < 0.8 else 1.0) * 0.8 for t in lag], CHEONGHOE_DEEP, 190)
    lay = lay.filter(ImageFilter.GaussianBlur(SS * 0.8))
    im.alpha_composite(lay)
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, [pt(t, -W * 0.075) for t in lag],
           [base * taper(t / 0.8 if t < 0.8 else 1.0) * 0.35 for t in lag], CHEONGBAEK, 210)
    im.alpha_composite(lay)
    # 본획 — 먹 테 + 흰 심
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, [pt(t) for t in TS], [base * taper(t) * 1.15 for t in TS], MEOK, 240)
    im.alpha_composite(lay)
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, [pt(t) for t in TS], [base * taper(t) * 0.58 for t in TS], WHITE, 255)
    im.alpha_composite(lay)
    return im.resize((OUT, OUT), Image.LANCZOS)


CONCEPTS = [
    ("A", "은섬 銀閃", concept_A, "가늘고 예리한 검광 — 잘 벼린 칼의 섬광"),
    ("B", "월아 月牙", concept_B, "두툼한 에너지 파도 — 기가 뭉친 덩어리"),
    ("C", "수묵일획", concept_C, "서예 붓획 — 붓 눌림·갈필·먹 번짐"),
    ("D", "열풍 裂風", concept_D, "찢어진 참격 — 들쭉한 날 + 속도선"),
    ("E", "쌍호 雙弧", concept_E, "겹친 두 호 — 본획 + 따라오는 잔호"),
]


def main():
    out = Path("작업물/검기/컨셉")
    out.mkdir(parents=True, exist_ok=True)
    cell = OUT * 5
    pad = 8
    label_h = 40
    sheet = Image.new("RGB", ((cell + pad) * 5 + pad, (cell + label_h + pad) * 2 + pad), (16, 18, 21))
    dr = ImageDraw.Draw(sheet)
    for j, bgc in enumerate([(24, 27, 31), (196, 203, 208)]):
        for i, (key, name, fn, desc) in enumerate(CONCEPTS):
            img = fn()
            img.save(out / f"{key}.png")
            big = img.resize((cell, cell), Image.NEAREST)
            bg = Image.new("RGBA", (cell, cell), (*bgc, 255))
            bg.alpha_composite(big)
            x = pad + i * (cell + pad)
            y = pad + j * (cell + label_h + pad)
            sheet.paste(bg.convert("RGB"), (x, y))
            if j == 0:
                dr.text((x + 4, y + cell + 6), f"{key}. {name}", fill=(220, 226, 228))
    p = out / "컨셉_5종.png"
    sheet.save(p)
    print(f"  그렸다: {p}")
    for key, name, _, desc in CONCEPTS:
        print(f"    {key}. {name} — {desc}")


if __name__ == "__main__":
    main()
