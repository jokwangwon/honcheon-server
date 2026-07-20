#!/usr/bin/env python3
"""검기 3프레임을 **한 궤도의 자라나는 구간**으로 그린다 — 모양을 눈으로 고르기 위한 미리보기.

왜 다시 짓는가 (2026-07-20 실측):
  지금 굽히는 arc1/arc2/arc3 은 레퍼런스 스크린샷 **세 장을 각각 따로** 떠서 만들었다.
  그래서 셋의 **자리도 크기도 서로 관계가 없다** (불투명 픽셀 252 → 139 → 360 —
  가운데가 첫 장보다 작다). 사용자 평가가 정확했다: *"따로 노는 프레임들 같아보입니다."*

  「한 번의 베기가 자라난다」면 셋은 **같은 호 위에서 각도만 늘어나야** 한다.
  그래서 호를 하나 정의하고 f=0.40 / 0.72 / 1.00 만큼 잘라 쓴다 —
  1번은 문자 그대로 3번의 앞 40% 다.

이 파일은 **미리보기 전용**이다. 모양이 정해지면 tools/respack/qi.py 로 옮긴다.
"""
from __future__ import annotations

import math
from pathlib import Path

from PIL import Image

W, H = 64, 22                      # 그림이 사는 영역 (캔버스는 64×64, 위 22행만 쓴다)
CANVAS = 64
FRACS = (0.40, 0.72, 1.00)         # 세 단계가 무는 호의 비율

# 색 — qi.py 의 검기 팔레트와 같은 값 (인선이 밝고 배가 짙다)
RIM = (14, 22, 16)
DARK = (26, 120, 60)
MID = (78, 200, 120)
BRIGHT = (150, 245, 170)
HOT = (222, 255, 226)


def lerp(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


# ── 호의 기하 ───────────────────────────────────────────────────────────
#   원의 일부를 쓴다. 중심을 캔버스 **아래**에 두어 위로 봉긋한 ∩ 이 되게 한다.
#   양 끝이 (2, 20) · (62, 20) 께에 닿고 꼭대기가 y≈1.5 가 되도록 풀면 아래 값이 나온다.
CX, CY, R = 32.0, 35.1, 33.6
TH = math.radians(63.3)            # ±63.3° — 양 끝이 캔버스 아래 모서리에 닿는 각


def arc_point(s: float):
    """s∈[0,1] — 꼬리(왼쪽)에서 머리(오른쪽)까지. 호 위의 좌표를 준다."""
    th = -TH + (2 * TH) * s
    return CX + R * math.sin(th), CY - R * math.cos(th)


def half_width(s: float, wmax: float) -> float:
    """두께 — 양 끝이 뾰족하고 **머리 쪽이 더 실하다** (벤 자리는 두껍고 끝은 날카롭다)."""
    base = math.sin(math.pi * max(0.0, min(1.0, s))) ** 0.62
    return wmax * base * (0.55 + 0.45 * s)


def _noise(x: float, y: float, seed: int) -> float:
    """씨앗이 고정된 값잡음 — 굽는 결과가 매번 같아야 한다 (팩 sha1 이 흔들리면 안 된다)."""
    n = math.sin(x * 12.9898 + y * 78.233 + seed * 37.719) * 43758.5453
    return n - math.floor(n)


def draw(frac: float, style: str = "레퍼런스", wmax: float = 3.1, samples: int = 1400):
    """호의 앞 `frac` 만큼을 그린다. 반환: (H×W) RGBA 격자.

    style — 세 갈래를 같은 기하 위에 얹는다 (모양이 아니라 **필치**가 다르다):
      · `레퍼런스`  깨끗하고 고른 아크 (마크에이지 영상에 가깝다)
      · `수묵`      붓질 — 가장자리가 거칠고 갈필(마른 붓)이 끊긴다. 혼천의 수묵 HUD 와 같은 결
      · `검기`      속심이 하얗게 타고 밖으로 번진다 (기가 실린 날)
    """
    g = [[(0, 0, 0, 0)] * W for _ in range(H)]
    for i in range(samples + 1):
        s_draw = frac * i / samples          # 지금 찍는 자리 (전체 호 기준)
        px, py = arc_point(s_draw)
        hw = half_width(s_draw, wmax)
        head = s_draw / max(frac, 1e-6)      # 이 프레임 안에서의 진행도 0..1
        # ★ 꼬리는 옅다 — 먼저 지나간 자리가 흩어지는 것이다 (머리가 지금 베는 자리)
        alpha = 0.30 + 0.70 * (head ** 0.55)

        if style == "수묵":
            # 붓이 마르며 굵기가 흔들린다 — 획 하나가 살아 있게
            hw *= 1.18 * (0.78 + 0.42 * _noise(s_draw * 41.0, 0.0, 3))
            alpha *= 0.72 + 0.38 * _noise(s_draw * 17.0, 9.0, 5)
        elif style == "검기":
            hw *= 1.30

        th = -TH + (2 * TH) * s_draw
        nx, ny = math.sin(th), -math.cos(th)   # 바깥(위) 법선
        steps = max(3, int(hw * 4))
        for k in range(-steps, steps + 1):
            t = k / steps                     # -1(안쪽) … +1(바깥=인선)
            x = px + nx * hw * t
            y = py + ny * hw * t
            xi, yi = int(round(x)), int(round(y))
            if not (0 <= xi < W and 0 <= yi < H):
                continue
            u = (1.0 - t) / 2.0               # 0 = 바깥 인선 … 1 = 안쪽 배
            a_mul = 1.0

            if style == "혼천필치":
                # ★ 팩에 이미 있는 qi/* 의 결 — **렌즈형**이다: 속이 밝고 양 가장자리로 사라진다.
                #   (blade_sheath·bolt_edge·slash_arc 를 보면 전부 이 단면이다. 단단한 테가 없다.)
                #   검기가 「혼천의 것」으로 읽히려면 색만 초록일 게 아니라 **이 단면**이어야 한다.
                d = abs(t)
                c = lerp(HOT, MID, d ** 0.85)
                a_mul = (1.0 - d ** 1.7) ** 0.75      # 가장자리에서 0 으로 — 테가 생기지 않는다
            elif style == "검기":
                # 속심이 타고 밖으로 번진다 — 가운데가 희고 양쪽이 옅어진다
                d = abs(t)
                if d < 0.34:
                    c = lerp(HOT, BRIGHT, d / 0.34)
                elif d < 0.72:
                    c = lerp(BRIGHT, MID, (d - 0.34) / 0.38)
                else:
                    c = lerp(MID, DARK, (d - 0.72) / 0.28)
                a_mul = 1.0 - max(0.0, (d - 0.62) / 0.38) * 0.85   # 바깥으로 번짐
            elif style == "수묵":
                # 먹이 번진 획 — 바깥 테가 짙고(먹), 안쪽이 밝다. 갈필로 군데군데 비운다
                if u < 0.14:
                    c = lerp(RIM, DARK, u / 0.14)
                elif u < 0.40:
                    c = lerp(DARK, MID, (u - 0.14) / 0.26)
                elif u < 0.74:
                    c = lerp(MID, BRIGHT, (u - 0.40) / 0.34)
                else:
                    c = lerp(BRIGHT, MID, (u - 0.74) / 0.26)
                if _noise(x * 3.1, y * 3.1, 11) < 0.16:
                    a_mul = 0.0                                     # 갈필 — 붓이 닿지 않은 자리
                a_mul *= 1.0 - max(0.0, (abs(t) - 0.86) / 0.14) * 0.6
            else:   # 레퍼런스
                if u < 0.20:
                    c = lerp(HOT, BRIGHT, u / 0.20)
                elif u < 0.62:
                    c = lerp(BRIGHT, MID, (u - 0.20) / 0.42)
                else:
                    c = lerp(MID, DARK, (u - 0.62) / 0.38)
                a_mul = 1.0 - max(0.0, (abs(t) - 0.82) / 0.18) * 0.75

            a = int(255 * alpha * a_mul)
            if a <= 0:
                continue
            old = g[yi][xi]
            if a > old[3]:
                g[yi][xi] = (c[0], c[1], c[2], min(252, a))
    return g


def to_image(g):
    im = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    px = im.load()
    for y in range(H):
        for x in range(W):
            px[x, y] = g[y][x]
    return im


STYLES = ("혼천필치", "수묵", "검기", "레퍼런스")


def main():
    out = Path(__file__).resolve().parent.parent / "작업물" / "검기"
    out.mkdir(parents=True, exist_ok=True)
    # 가로 = 자라나는 3단계 · 세로 = 갈래 3종. 한 장에 놓고 견준다.
    scale, pad = 6, 6
    cell = CANVAS * scale
    sheet = Image.new("RGBA",
                      (pad + (cell + pad) * 3, pad + (cell + pad) * len(STYLES)),
                      (30, 30, 34, 255))
    for r, style in enumerate(STYLES):
        counts = []
        for c, f in enumerate(FRACS):
            im = to_image(draw(f, style))
            counts.append(sum(1 for p in im.getdata() if p[3] > 0))
            sheet.alpha_composite(im.resize((cell, cell), Image.NEAREST),
                                  (pad + c * (cell + pad), pad + r * (cell + pad)))
        print(f"  {style:5s} 불투명 {counts[0]:>4} → {counts[1]:>4} → {counts[2]:>4} px"
              + ("  ✔ 자란다" if counts[0] < counts[1] < counts[2] else "  ★ 단조 증가가 아니다"))
    p = out / "검기_갈래_3종.png"
    sheet.convert("RGB").save(p)
    print("  →", p)


if __name__ == "__main__":
    main()
