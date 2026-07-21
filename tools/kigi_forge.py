#!/usr/bin/env python3
"""검기 대장간 — **삼합(2+3+5) 골격**을 레시피 함수로 만든다 (사용자 확정 · 2026-07-21).

【사용자의 걱정】 "저마다 색상 부여·컨셉 부여 시에 부여하기 어렵다고 판단되는데"
【답】 골격은 **함수**, 색과 실루엣은 **인자**다.
    · 색 부여   = 등록부 먹빛 이름 하나 (`청회`·`청록`·`옥`·`혈`…) → 팔레트가 자동 파생된다
    · 실루엣 부여 = 경로 하나 (호·직선·S자·X자…) → 같은 붓이 그 길을 따라간다
  즉 무기·문파·기술마다 그림을 새로 그리는 게 아니라 **(실루엣, 먹빛) 한 쌍을 주문**한다.
  격 사다리(검기 청회 → 강기 청록 → 어검 옥)도 먹빛 인자만 갈아끼우면 자동으로 오른다.

【확정값】 먹 몸 굵기 body_scale = 0.78 (2026-07-21 사용자 확정 — 「나」)

【삼합의 층 (고정 골격)】
  ① 담묵 번짐 (수묵) ② 붓 눌림 먹 몸 (수묵) ③ 겹겹 광채 (글로우)
  ④ 금속 서슬 줄 (금속) ⑤ 흰 심 — 순서와 비율이 골격이고, 색만 팔레트를 따른다.
"""
from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

# ── 등록부의 먹빛 (config/skill_motion.yml inks — 단일 진실 원천) ─────────
INKS = {
    "청회": (124, 143, 152),   # 검기
    "청록": (82, 158, 146),    # 강기
    "옥":   (166, 214, 199),   # 어검
    "청백": (226, 240, 238),   # 심검
    "혈":   (138, 38, 42),     # 마공 (채색 예외)
}
MEOK = (38, 46, 54)

SS = 4
OUT = 64
W = OUT * SS


def _mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def palette(ink_name: str) -> dict:
    """★ 색 부여의 전부 — 등록부 먹빛 하나에서 팔레트를 파생한다."""
    accent = INKS[ink_name]
    return {
        "body": MEOK,                                  # 붓 몸은 언제나 먹 (수묵 세계)
        "glow_deep": _mix(accent, MEOK, 0.35),         # 어두운 광채
        "glow_mid": accent,                            # 광채 본색
        "edge": _mix(accent, (255, 255, 255), 0.35),   # 서슬 줄 (기색이 진한 밝음)
        "core": _mix(accent, (255, 255, 255), 0.58),   # 심 (기색이 뚜렷한 밝음 — 씻기면 격이 안 보인다)
    }


def noise(x: float, seed: int = 0) -> float:
    n = math.sin(x * 127.1 + seed * 74.7) * 43758.5453
    return n - math.floor(n)


def canvas():
    return Image.new("RGBA", (W, W), (0, 0, 0, 0))


def stroke(d, pts, ws, color, alpha=255):
    for (x, y), w in zip(pts, ws):
        if w > 0:
            d.ellipse([x - w, y - w, x + w, y + w], fill=(*color, alpha))


def taper(t: float, head: float = 0.10, tail_pow: float = 1.1) -> float:
    if t > 1.0 - head:
        return max(0.0, (1.0 - t) / head) ** 0.65 * ((1.0 - head) ** tail_pow)
    return t ** tail_pow


def press(t: float) -> float:
    return taper(t, head=0.13, tail_pow=0.9) * (0.7 + 0.4 * noise(t * 8.0, 3))


def _normal(pts, i, off):
    """경로의 법선 방향으로 off 만큼 비낀 점 — 서슬 줄을 경로 곁에 긋기 위해."""
    j = min(len(pts) - 1, i + 1)
    dx, dy = pts[j][0] - pts[i][0], pts[j][1] - pts[i][1]
    L = math.hypot(dx, dy) or 1.0
    return pts[i][0] - dy / L * off, pts[i][1] + dx / L * off


def samhap(pts: list, pal: dict, base_w: float = None, body_scale: float = 0.78) -> Image.Image:
    """★ 삼합 골격 — 어떤 경로든 이 붓으로 긋는다. (실루엣, 팔레트) 만 주문하라."""
    base_w = base_w or W * 0.088
    n = len(pts)
    ts = [i / (n - 1) for i in range(n)]
    im = canvas()
    # ① 담묵 번짐
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, pts, [base_w * press(t) * 1.6 for t in ts], pal["glow_deep"], 55)
    lay = lay.filter(ImageFilter.GaussianBlur(SS * 2.4))
    im.alpha_composite(lay)
    # ③ 겹겹 광채 (몸 아래에 깔린다)
    for scale, col, alpha, blur in ((2.0, pal["glow_deep"], 115, 3.0),
                                     (1.45, pal["glow_mid"], 155, 1.7)):
        lay = canvas(); d = ImageDraw.Draw(lay)
        stroke(d, pts, [base_w * press(t) * scale for t in ts], col, alpha)
        lay = lay.filter(ImageFilter.GaussianBlur(SS * blur))
        im.alpha_composite(lay)
    # ② 붓 눌림 먹 몸
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, pts, [base_w * press(t) * body_scale for t in ts], pal["body"], 250)
    im.alpha_composite(lay)
    # 갈필 — 꼬리 절반의 마른 줄기
    lay = canvas(); d = ImageDraw.Draw(lay)
    for off in (-0.5, -0.15, 0.3, 0.62):
        seg = [i for i in range(n) if ts[i] < 0.45]
        stroke(d, [_normal(pts, i, base_w * off) for i in seg],
               [base_w * press(ts[i]) * 0.15 * body_scale for i in seg], pal["body"], 190)
    im.alpha_composite(lay)
    # ④ 금속 서슬 줄
    lay = canvas(); d = ImageDraw.Draw(lay)
    for off, (t0, t1), wmul, colkey in ((0.45, (0.30, 0.98), 0.16, "core"),
                                         (0.0, (0.05, 0.72), 0.12, "edge"),
                                         (-0.42, (0.42, 0.88), 0.09, "core")):
        seg = [i for i in range(n) if t0 <= ts[i] <= t1]
        stroke(d, [_normal(pts, i, base_w * off) for i in seg],
               [base_w * taper(ts[i]) * wmul for i in seg], pal[colkey], 255)
    x, y = pts[int(n * 0.97)]
    r = W * 0.018
    d.ellipse([x - r, y - r, x + r, y + r], fill=(*pal["core"], 255))
    im.alpha_composite(lay)
    # ⑤ 흰 심 (얇게 — 삼합의 마무리)
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, pts, [base_w * press(t) * 0.35 for t in ts], pal["core"], 255)
    im.alpha_composite(lay)
    return im.resize((OUT, OUT), Image.LANCZOS)


# ── 실루엣 경로들 (부여 인자 — 여기 무기·문파·기술별 경로가 쌓인다) ──────
def sil_arc(n=260):
    """표준 참격 호."""
    cx, cy, r = W * 0.5, W * 1.02, W * 0.64
    a0, a1 = math.radians(-146), math.radians(-33)
    return [(cx + r * math.cos(a0 + (a1 - a0) * i / (n - 1)),
             cy + r * math.sin(a0 + (a1 - a0) * i / (n - 1))) for i in range(n)]


def sil_line(n=260):
    """일섬 — 대각 직선 (거합)."""
    p0, p1 = (W * 0.08, W * 0.86), (W * 0.92, W * 0.18)
    return [(p0[0] + (p1[0] - p0[0]) * i / (n - 1),
             p0[1] + (p1[1] - p0[1]) * i / (n - 1)) for i in range(n)]


def sil_scurve(n=260):
    """용틀임 — S 자 굽이."""
    p0, p1 = (W * 0.06, W * 0.80), (W * 0.94, W * 0.24)
    dx, dy = p1[0] - p0[0], p1[1] - p0[1]
    L = math.hypot(dx, dy)
    nx, ny = -dy / L, dx / L
    out = []
    for i in range(n):
        t = i / (n - 1)
        off = W * 0.135 * math.sin(t * math.pi * 2.0) * math.sin(t * math.pi) ** 0.5
        out.append((p0[0] + dx * t + nx * off, p0[1] + dy * t + ny * off))
    return out


SILS = [("호 (참격)", sil_arc), ("일섬 (직선)", sil_line), ("용틀임 (S자)", sil_scurve)]
PALS = ["청회", "청록", "옥", "혈"]


def main():
    out = Path("작업물/검기/대장간")
    out.mkdir(parents=True, exist_ok=True)
    cell = OUT * 4
    pad = 8
    rows, cols = len(PALS), len(SILS)
    # 어두운 배경 한 판 (격자) — 색·실루엣 부여가 자동임을 한눈에
    sheet = Image.new("RGB", ((cell + pad) * cols + pad + 90, (cell + pad) * rows + pad), (16, 18, 21))
    from PIL import ImageDraw as _ID
    dr = _ID.Draw(sheet)
    for r_i, ink in enumerate(PALS):
        pal = palette(ink)
        for c_i, (sname, sfn) in enumerate(SILS):
            img = samhap(sfn(), pal)
            img.save(out / f"{ink}_{c_i}.png")
            big = img.resize((cell, cell), Image.NEAREST)
            bg = Image.new("RGBA", (cell, cell), (24, 27, 31, 255))
            bg.alpha_composite(big)
            sheet.paste(bg.convert("RGB"), (90 + pad + c_i * (cell + pad), pad + r_i * (cell + pad)))
        dr.text((10, pad + r_i * (cell + pad) + cell // 2), ink, fill=(210, 216, 218))
    p = out / "부여_실연.png"
    sheet.save(p)
    print(f"  그렸다: {p}")
    print(f"  = 삼합 골격 하나 × 먹빛 {len(PALS)}종 × 실루엣 {len(SILS)}종 — 전부 자동 파생")


if __name__ == "__main__":
    main()
