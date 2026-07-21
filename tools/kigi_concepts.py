#!/usr/bin/env python3
"""검기 컨셉 5종 · 2판 — **실루엣부터 다른** 다섯 갈래 (사용자: 전부 한 결에서 조금 변형한 느낌).

  1판의 실패: 다섯 전부 같은 호(같은 중심·반지름·각)에 붓 처리만 바꿨다 → 한 결로 보였다.
  2판은 **구도 자체**가 다르다:
    A 십자섬(十字閃) — X 로 교차하는 두 획. 애니 십자베기.
    B 용틀임        — S 자로 굽이치는 획 + 머리 섬광. 뱀처럼 휘돈다.
    C 삼연격(三連擊) — 발톱처럼 나란한 세 획. 긁어 찢는 삼선.
    D 만월참(滿月斬) — 고리에 가깝게 도는 두꺼운 원호. 큰 마무리 기술.
    E 일섬(一閃)     — 대각 직선 한 줄 + 발도 섬광점. 거합.

  공통 「멋」 장치: 머리 섬광(별빛 스파이크) · 비말 방울 · 3층 날붓(먹 테→청회 기운→흰 심).
  색은 등록부(먹·청회·청백)만.
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


def noise(x: float, seed: int = 0) -> float:
    n = math.sin(x * 127.1 + seed * 74.7) * 43758.5453
    return n - math.floor(n)


def canvas():
    return Image.new("RGBA", (W, W), (0, 0, 0, 0))


def stroke(d: ImageDraw.ImageDraw, pts, ws, color, alpha=255):
    for (x, y), w in zip(pts, ws):
        if w > 0:
            d.ellipse([x - w, y - w, x + w, y + w], fill=(*color, alpha))


def taper(t: float, head: float = 0.10, tail_pow: float = 1.25) -> float:
    """양끝 다듬기 — t=1 이 날 끝(짧고 뾰족), t=0 이 꼬리(길게 얇아짐)."""
    if t > 1.0 - head:
        return max(0.0, (1.0 - t) / head) ** 0.65 * ((1.0 - head) ** tail_pow)
    return t ** tail_pow


# ── 경로 생성기 ──────────────────────────────────────────────────────────
def arc_path(cx, cy, r, a0, a1, n=240):
    out = []
    for i in range(n):
        t = i / (n - 1)
        a = a0 + (a1 - a0) * t
        out.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return out


def line_path(p0, p1, n=240):
    return [(p0[0] + (p1[0] - p0[0]) * i / (n - 1),
             p0[1] + (p1[1] - p0[1]) * i / (n - 1)) for i in range(n)]


def s_path(p0, p1, amp, n=240):
    """p0→p1 직선에 사인 굽이를 얹는다 — 용틀임."""
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    L = math.hypot(dx, dy)
    nx, ny = -dy / L, dx / L
    out = []
    for i in range(n):
        t = i / (n - 1)
        off = amp * math.sin(t * math.pi * 2.0) * math.sin(t * math.pi) ** 0.5
        out.append((p0[0] + dx * t + nx * off, p0[1] + dy * t + ny * off))
    return out


# ── 날붓 — 3층 (먹 테 → 청회 기운 → 흰 심 → 청백 인선) ─────────────────
def blade(im, pts, base_w, head=0.10, tail_pow=1.25, glow=True):
    n = len(pts)
    ts = [i / (n - 1) for i in range(n)]
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, pts, [base_w * taper(t, head, tail_pow) * 1.30 for t in ts], MEOK, 235)
    im.alpha_composite(lay)
    if glow:
        lay = canvas(); d = ImageDraw.Draw(lay)
        stroke(d, pts, [base_w * taper(t, head, tail_pow) * 1.05 for t in ts], CHEONGHOE_DEEP, 235)
        lay = lay.filter(ImageFilter.GaussianBlur(SS * 0.7))
        im.alpha_composite(lay)
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, pts, [base_w * taper(t, head, tail_pow) * 0.58 for t in ts], WHITE, 255)
    im.alpha_composite(lay)
    # 인선 — 심 위 한쪽에 청백 가는 줄 (방향의 빛)
    lay = canvas(); d = ImageDraw.Draw(lay)
    off = base_w * 0.34
    hipts = [(x, y - off) for x, y in pts]
    stroke(d, hipts, [base_w * taper(t, head, tail_pow) * 0.22 for t in ts], CHEONGBAEK, 210)
    im.alpha_composite(lay)


def flare(im, xy, size, spikes=4, rot=0.0):
    """머리 섬광 — 별빛 스파이크 + 둥근 빛."""
    x, y = xy
    lay = canvas(); d = ImageDraw.Draw(lay)
    d.ellipse([x - size * 0.9, y - size * 0.9, x + size * 0.9, y + size * 0.9], fill=(*CHEONGBAEK, 120))
    lay = lay.filter(ImageFilter.GaussianBlur(SS * 1.6))
    im.alpha_composite(lay)
    lay = canvas(); d = ImageDraw.Draw(lay)
    for k in range(spikes):
        a = rot + k * math.pi / (spikes / 2)
        lx, ly = math.cos(a), math.sin(a)
        ln = size * (1.9 if k % 2 == 0 else 1.1)
        pts = line_path((x - lx * ln, y - ly * ln), (x + lx * ln, y + ly * ln), 60)
        ws = [size * 0.16 * math.sin(math.pi * i / 59) for i in range(60)]
        stroke(d, pts, ws, WHITE, 255)
    im.alpha_composite(lay)


def spatter(im, pts, seed, count=7, size=1.0):
    """비말 — 획 바깥으로 튄 방울."""
    lay = canvas(); d = ImageDraw.Draw(lay)
    n = len(pts)
    for k in range(count):
        t = 0.25 + 0.7 * noise(k * 1.7, seed)
        i = int(t * (n - 1))
        x, y = pts[i]
        ang = (noise(k * 3.1, seed + 1) - 0.3) * math.pi
        dist = W * (0.03 + 0.05 * noise(k * 5.3, seed + 2))
        px, py = x + math.cos(ang) * dist, y - abs(math.sin(ang)) * dist
        r = W * 0.008 * (0.5 + noise(k * 7.7, seed + 3)) * size
        col = WHITE if k % 3 else CHEONGHOE
        d.ellipse([px - r, py - r, px + r, py + r], fill=(*col, 235))
    im.alpha_composite(lay)


# ── A 십자섬 — X 교차 두 획 ─────────────────────────────────────────────
def concept_A() -> Image.Image:
    im = canvas()
    # ★ X 는 **대각 두 직선(살짝 굽은)**이다 — 1판에서 세로 두 호가 마주 붙어 눈(렌즈)이 됐다.
    # 뒤 획: 오른아래→왼위 (먼저 그은 것 — 가늘고 옅게)
    p_back = s_path((W * 0.88, W * 0.82), (W * 0.10, W * 0.16), amp=W * 0.03)
    blade(im, p_back, W * 0.038, glow=False)
    # 앞 획: 왼아래→오른위 (지금 그은 것 — 굵고 밝게)
    p_main = s_path((W * 0.08, W * 0.84), (W * 0.92, W * 0.14), amp=W * 0.045)
    blade(im, p_main, W * 0.056)
    # 교차점 섬광
    flare(im, (W * 0.50, W * 0.49), W * 0.06, rot=math.radians(15))
    spatter(im, p_main, seed=2, count=5)
    return im.resize((OUT, OUT), Image.LANCZOS)


# ── B 용틀임 — S 자 굽이 + 머리 섬광 ────────────────────────────────────
def concept_B() -> Image.Image:
    im = canvas()
    pts = s_path((W * 0.06, W * 0.80), (W * 0.94, W * 0.24), amp=W * 0.135)
    blade(im, pts, W * 0.062, head=0.08, tail_pow=1.1)
    flare(im, pts[-6], W * 0.05, rot=math.radians(-35))
    spatter(im, pts, seed=5, count=6)
    return im.resize((OUT, OUT), Image.LANCZOS)


# ── C 삼연격 — 발톱 세 획 ───────────────────────────────────────────────
def concept_C() -> Image.Image:
    im = canvas()
    specs = [  # (반경 오프셋, 세로 오프셋, 굵기, 길이 잘라내기)
        (-W * 0.13, -W * 0.10, W * 0.034, (0.10, 0.92)),
        (0.0, 0.0, W * 0.052, (0.0, 1.0)),
        (W * 0.13, W * 0.11, W * 0.038, (0.16, 0.98)),
    ]
    for dr, dy, bw, (t0, t1) in specs:
        full = arc_path(W * 0.5, W * 1.02 + dy, W * 0.62 + dr, math.radians(-141), math.radians(-37))
        seg = full[int(t0 * len(full)):int(t1 * len(full))]
        blade(im, seg, bw, head=0.09)
    spatter(im, arc_path(W * 0.5, W * 1.02, W * 0.62, math.radians(-141), math.radians(-37)),
            seed=8, count=6)
    return im.resize((OUT, OUT), Image.LANCZOS)


# ── D 만월참 — 고리에 가까운 두꺼운 원호 ────────────────────────────────
def concept_D() -> Image.Image:
    im = canvas()
    cx, cy, r = W * 0.5, W * 0.52, W * 0.335
    pts = arc_path(cx, cy, r, math.radians(-205), math.radians(63), n=300)
    # 속 채운 몸 — 도넛 조각 (폴리곤)
    depth = W * 0.085
    outer = pts
    inner = [ (cx + (r - depth * math.sin(math.pi * i / 299) ** 0.7) * math.cos(math.radians(-205) + math.radians(268) * i / 299),
               cy + (r - depth * math.sin(math.pi * i / 299) ** 0.7) * math.sin(math.radians(-205) + math.radians(268) * i / 299))
              for i in reversed(range(300)) ]
    lay = canvas(); d = ImageDraw.Draw(lay)
    d.polygon(outer + inner, fill=(*MEOK, 230))
    im.alpha_composite(lay)
    lay = canvas(); d = ImageDraw.Draw(lay)
    d.polygon(outer + inner, fill=(*CHEONGHOE_DEEP, 200))
    lay = lay.filter(ImageFilter.GaussianBlur(SS * 0.8))
    im.alpha_composite(lay)
    # 바깥 날 흰 심 (머리 쪽 굵게)
    n = len(pts)
    ts = [i / (n - 1) for i in range(n)]
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, pts, [W * 0.030 * (0.35 + 0.65 * t) * taper(t, 0.06, 0.5) for t in ts], WHITE, 255)
    im.alpha_composite(lay)
    flare(im, pts[-8], W * 0.055, rot=math.radians(60))
    return im.resize((OUT, OUT), Image.LANCZOS)


# ── E 일섬 — 대각 직선 + 발도점 ─────────────────────────────────────────
def concept_E() -> Image.Image:
    im = canvas()
    p0, p1 = (W * 0.08, W * 0.86), (W * 0.92, W * 0.18)
    pts = line_path(p0, p1, 260)
    blade(im, pts, W * 0.048, head=0.06, tail_pow=0.85)
    # 평행 속도선 두 줄 (아래쪽, 가늘게)
    lay = canvas(); d = ImageDraw.Draw(lay)
    for off, ln in ((W * 0.06, (0.15, 0.55)), (W * 0.10, (0.30, 0.62))):
        seg = [(x + off * 0.7, y + off) for x, y in pts[int(ln[0] * 260):int(ln[1] * 260)]]
        m = len(seg)
        stroke(d, seg, [W * 0.008 * math.sin(math.pi * i / (m - 1)) * 2 for i in range(m)], CHEONGHOE, 220)
    im.alpha_composite(lay)
    flare(im, pts[-4], W * 0.065, rot=math.radians(-39))
    return im.resize((OUT, OUT), Image.LANCZOS)


CONCEPTS = [
    ("A", "십자섬 十字閃", concept_A, "X 로 교차하는 두 획 — 십자베기"),
    ("B", "용틀임", concept_B, "S 자로 굽이치는 획 — 뱀처럼 휘돈다"),
    ("C", "삼연격 三連擊", concept_C, "발톱처럼 나란한 세 획"),
    ("D", "만월참 滿月斬", concept_D, "고리에 가깝게 도는 두꺼운 원호"),
    ("E", "일섬 一閃", concept_E, "대각 직선 한 줄 — 거합 발도"),
]


def main():
    out = Path("작업물/검기/컨셉")
    out.mkdir(parents=True, exist_ok=True)
    cell = OUT * 5
    pad = 8
    sheet = Image.new("RGB", ((cell + pad) * 5 + pad, (cell + pad) * 2 + pad), (16, 18, 21))
    for j, bgc in enumerate([(24, 27, 31), (196, 203, 208)]):
        for i, (key, name, fn, desc) in enumerate(CONCEPTS):
            img = fn()
            img.save(out / f"{key}.png")
            big = img.resize((cell, cell), Image.NEAREST)
            bg = Image.new("RGBA", (cell, cell), (*bgc, 255))
            bg.alpha_composite(big)
            sheet.paste(bg.convert("RGB"), (pad + i * (cell + pad), pad + j * (cell + pad)))
    p = out / "컨셉_5종.png"
    sheet.save(p)
    print(f"  그렸다: {p}")
    for key, name, _, desc in CONCEPTS:
        print(f"    {key}. {name} — {desc}")


if __name__ == "__main__":
    main()
