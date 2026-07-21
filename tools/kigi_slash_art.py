#!/usr/bin/env python3
"""애니풍 검기 슬래시를 **그림으로** 그린다 — 수식 호가 아니라 슬래시 아트.

【왜 이 길인가 · 2026-07-21】
  공책 RPG(메르헨전기 계열)의 검기는 **손으로 그린 2D 슬래시 프레임**을 스윙마다
  순서대로 재생하는 것이다. 우리의 arc1/2/3 프레임 구조가 맞았고, 모자랐던 것은
  **그림 자체** — 수식으로 만든 균일한 호는 슬래시 아트가 아니다.
  레퍼런스 캡처가 없으므로 애니 슬래시의 해부학으로 직접 그린다:
    · 굵고 밝은 심(core)이 호를 따라 흐른다 — 균일하지 않고 배가 부르다
    · 가장자리로 색(청회 계열)이 번지고 밖은 어둡게 닫힌다
    · 꼬리는 얇은 채찍(whip)으로 찢어진다 — 속도감
    · 3프레임: ①베는 찰나(가는 은빛 낫) ②최대(배부른 슬래시+채찍) ③흩어짐(끊긴 줄기)

  4배 슈퍼샘플로 그리고 내려 축소한다 — 픽셀 계단 없이 붓맛을 남기기 위해.
  색은 등록부의 격 사다리(청백/청회/먹)를 쓰되, 애니 슬래시의 명암 대비로 배치한다.
"""
from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

# 등록부의 색 (config/skill_motion.yml inks)
MEOK = (38, 46, 54)
CHEONGHOE = (124, 143, 152)
CHEONGHOE_DEEP = (86, 128, 148)
CHEONGBAEK = (226, 240, 238)
WHITE = (245, 250, 250)

SS = 4               # 슈퍼샘플 배율
OUT = 64             # 최종 한 변
W = OUT * SS         # 작업 캔버스


def arc_pt(t: float, cx: float, cy: float, r: float, a0: float, a1: float):
    """t∈[0,1] 을 호 위의 점으로. 각은 라디안."""
    a = a0 + (a1 - a0) * t
    return cx + r * math.cos(a), cy + r * math.sin(a)


def stroke(draw: ImageDraw.ImageDraw, pts, widths, color, alpha=255):
    """폭이 변하는 붓 — 원을 겹쳐 찍는다 (PIL 에 가변폭 선이 없다)."""
    for (x, y), w in zip(pts, widths):
        if w <= 0:
            continue
        draw.ellipse([x - w, y - w, x + w, y + w], fill=(*color, alpha))


def belly(t: float, peak: float = 0.30, width_pow: float = 1.35) -> float:
    """슬래시의 배 — 머리(t=1) 근처가 굵고 꼬리(t=0)로 길게 얇아진다.
    ★ t=1 이 「지금 베는 날 끝」이다 — 끝은 뾰족하게 닫는다."""
    # 머리 쪽 뾰족: t>0.94 에서 급감
    tip = 1.0 if t < 0.94 else max(0.0, 1.0 - (t - 0.94) / 0.06) ** 0.7
    if t > 1.0 - peak:                       # 머리 근처 — 배가 부르다
        u = (1.0 - t) / peak
        body = 0.55 + 0.45 * (u ** 0.5) if u < 1 else 1.0
        body = max(body, 0.9)
    else:                                    # 꼬리로 얇아진다
        u = t / (1.0 - peak)
        body = u ** width_pow
    return body * tip


def draw_slash(frac: float, whips: bool, breakup: float, seed: int = 0) -> Image.Image:
    """슬래시 한 장. frac=호를 얼마나 그었나(0~1), whips=꼬리 채찍, breakup=흩어짐 정도(0~1)."""
    im = Image.new("RGBA", (W, W), (0, 0, 0, 0))

    # 호의 기하 — 캔버스 위쪽을 크게 훑는 호 (원 중심은 아래)
    cx, cy = W * 0.5, W * 1.06
    r = W * 0.68
    a_full0, a_full1 = math.radians(-146), math.radians(-32)   # 왼→오로 벤다 (캔버스 안에 다 들어온다)
    a1 = a_full0 + (a_full1 - a_full0) * frac                  # 그은 데까지

    n = 260
    ts = [i / (n - 1) for i in range(n)]
    pts = [arc_pt(t, cx, cy, r, a_full0, a1) for t in ts]

    base_w = W * 0.075 * (0.55 + 0.45 * frac)   # 획이 자랄수록 굵어진다

    # 흩어짐 — 프레임3: 폭이 물결치며 스러진다 (조각이 아니라 사그라드는 줄기)
    def decay(t: float) -> float:
        if breakup <= 0:
            return 1.0
        g = math.sin(t * 31.0 + seed * 3.1) * 0.5 + 0.5      # 물결
        d = 1.0 - breakup * (0.45 + 0.55 * g)                # 군데군데 얇아진다
        return max(0.12, d)

    # ── 층 1: 바깥 어두운 테 (먹) — 슬래시의 윤곽을 닫는다 ──
    lay = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    ws = [base_w * belly(t) * decay(t) * 1.28 for t in ts]
    stroke(d, pts, ws, MEOK, 235)
    im.alpha_composite(lay)

    # ── 층 2: 청회 몸 — 심 둘레의 기운 ──
    lay = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    ws = [base_w * belly(t) * decay(t) * 0.92 for t in ts]
    stroke(d, pts, ws, CHEONGHOE_DEEP, 245)
    lay = lay.filter(ImageFilter.GaussianBlur(SS * 0.6))
    im.alpha_composite(lay)

    # ── 층 3: 흰 심 — 애니 슬래시의 핵. 배가 부르고 끝은 실처럼 ──
    lay = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    ws = [base_w * belly(t) * decay(t) * 0.52 for t in ts]
    stroke(d, pts, ws, WHITE, 255)
    im.alpha_composite(lay)

    # 심 위 은빛 하이라이트 (청백) — 바깥쪽 절반에만 얹어 날의 방향을 준다
    lay = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    off = base_w * 0.22
    hi = []
    for t in ts:
        x, y = arc_pt(t, cx, cy, r + off, a_full0, a1)
        hi.append((x, y))
    ws = [base_w * belly(t) * decay(t) * 0.30 for t in ts]
    stroke(d, hi, ws, CHEONGBAEK, 230)
    im.alpha_composite(lay)

    # ── 층 4: 꼬리 채찍 — 끝에서 찢어지는 가는 줄기들 (속도감) ──
    if whips:
        lay = Image.new("RGBA", (W, W), (0, 0, 0, 0))
        d = ImageDraw.Draw(lay)
        for k, (t0, dr, ln) in enumerate([(0.00, -0.045, 0.20), (0.05, -0.09, 0.15), (0.00, 0.035, 0.26)]):
            t0 = min(t0, frac * 0.9)
            wpts, wws = [], []
            m = 70
            for i in range(m):
                u = i / (m - 1)
                t = t0 + u * ln * frac
                rr = r * (1.0 + dr * u)
                x, y = arc_pt(t, cx, cy, rr, a_full0, a1)
                wpts.append((x, y))
                wws.append(base_w * 0.20 * (1.0 - u) ** 1.4)
            stroke(d, wpts, wws, CHEONGHOE, 220)
        im.alpha_composite(lay)

    return im.resize((OUT, OUT), Image.LANCZOS)


def build_frames() -> list[Image.Image]:
    """3프레임 — ①베는 찰나 ②최대 ③흩어짐."""
    return [
        draw_slash(frac=0.46, whips=False, breakup=0.0),
        draw_slash(frac=1.00, whips=True, breakup=0.0),
        draw_slash(frac=1.00, whips=True, breakup=0.72, seed=1),
    ]


def main():
    out = Path("작업물/검기/아트")
    out.mkdir(parents=True, exist_ok=True)
    frames = build_frames()
    # 검토용 시트 — 어두운 배경 + 밝은 배경 둘 다 (인게임 배경은 다양하다)
    cell = OUT * 5
    sheet = Image.new("RGB", (cell * 3, cell * 2), (24, 27, 31))
    for j, bgc in enumerate([(24, 27, 31), (196, 203, 208)]):
        for i, f in enumerate(frames):
            big = f.resize((cell, cell), Image.NEAREST)
            bg = Image.new("RGBA", (cell, cell), (*bgc, 255))
            bg.alpha_composite(big)
            sheet.paste(bg.convert("RGB"), (i * cell, j * cell))
    p = out / "슬래시_3프레임.png"
    sheet.save(p)
    for i, f in enumerate(frames, 1):
        f.save(out / f"frame{i}.png")
    print(f"  그렸다: {p} (위=어두운 배경 · 아래=밝은 배경)")


if __name__ == "__main__":
    main()
