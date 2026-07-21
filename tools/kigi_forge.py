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


def samhap(pts: list, pal: dict, base_w: float = None, body_scale: float = 0.78,
           white_scale: float = 1.0) -> Image.Image:
    """★ 삼합 골격 — 어떤 경로든 이 붓으로 긋는다. (실루엣, 팔레트) 만 주문하라.
    white_scale: 흰 층(심·서슬·광점)의 상대 굵기. 확정 64룩에서 흰 층은 서브픽셀로 뭉개져
    먹에 스몄다 — 고해상도로 구울 때 1.0 이면 흰 층이 또렷이 살아나 획을 덮으므로,
    0.45 쯤으로 줄여 그 뭉개짐(획 대비 ~0.2 비율)을 재현한다. 기본 1.0 = 기존 그림 불변."""
    base_w = base_w or W * 0.088
    bu = base_w / (W * 0.088)      # 블러도 붓 굵기에 비례 — 가는 붓에서 광채가 획을 씻지 않게
    n = len(pts)
    ts = [i / (n - 1) for i in range(n)]
    im = canvas()
    # ① 담묵 번짐
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, pts, [base_w * press(t) * 1.6 for t in ts], pal["glow_deep"], 55)
    lay = lay.filter(ImageFilter.GaussianBlur(W / 64 * 2.4 * bu))   # 블러는 W 에 비례 — 어느 해상도든 같은 그림
    im.alpha_composite(lay)
    # ③ 겹겹 광채 (몸 아래에 깔린다)
    for scale, col, alpha, blur in ((2.0, pal["glow_deep"], 115, 3.0),
                                     (1.45, pal["glow_mid"], 155, 1.7)):
        lay = canvas(); d = ImageDraw.Draw(lay)
        stroke(d, pts, [base_w * press(t) * scale for t in ts], col, alpha)
        lay = lay.filter(ImageFilter.GaussianBlur(W / 64 * blur * bu))
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
               [base_w * taper(ts[i]) * wmul * white_scale for i in seg], pal[colkey], 255)
    x, y = pts[int(n * 0.97)]
    r = base_w * 0.205 * white_scale   # 광점은 붓 굵기에 비례 (0.088W 붓에서 옛 값 0.018W 와 동일)
    d.ellipse([x - r, y - r, x + r, y + r], fill=(*pal["core"], 255))
    im.alpha_composite(lay)
    # ⑤ 흰 심 (얇게 — 삼합의 마무리)
    lay = canvas(); d = ImageDraw.Draw(lay)
    stroke(d, pts, [base_w * press(t) * 0.35 * white_scale for t in ts], pal["core"], 255)
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


# ═══ 확정 산출 — 팩이 굽는 실물 (2026-07-21 사용자 확정: 5프레임 통일) ═══════
# 한 번의 베기 = 시작(28%) → 흐름(62%) → 전체 → 잔상 → 스러짐. 2틱/장 × 5 = 10틱 = draw_ticks.
FRAMES_5 = [(0.28, 0.0), (0.62, 0.0), (1.00, 0.0), (1.00, 0.35), (1.00, 0.75)]

# 판 띠 굵기 배율 — 정사각 붓(0.088)을 띠 높이(22/64)에 맞춰 줄인 값. 키우면 광채가 띠를 벗어난다.
BAND_W_SCALE = 0.055


def set_res(out: int):
    """산출 해상도를 바꾼다 — 팩 실물은 256, 견본·비교는 기본 64.
    붓 굵기·블러·실루엣 전부 W 에 비례하므로 어느 해상도든 같은 그림이 나온다."""
    global OUT, W
    OUT = out
    W = OUT * SS


def sil_arc_band(n=260):
    """판 띠(64×22)용 표준 참격 호 — 넓고 얕은 ∩. 인게임 판(1.5×0.55m)과 같은 비율의
    캔버스 맨 위 22행 안에 광채까지 들어가도록 반지름을 크게(얕게) 잡았다."""
    cx, cy, r = W * 0.5, W * 1.33, W * 1.20
    a0, a1 = math.radians(-112), math.radians(-68)
    return [(cx + r * math.cos(a0 + (a1 - a0) * i / (n - 1)),
             cy + r * math.sin(a0 + (a1 - a0) * i / (n - 1))) for i in range(n)]


def frame(pts: list, ink: str, frac: float, decay: float, base_w: float = None,
          white_scale: float = 1.0) -> Image.Image:
    """베기의 한 장 — frac 만큼 그어졌고, decay 만큼 스러졌다 (프레임 세트의 원자).
    frac 절단은 머리 쪽을 남긴다: 부분 획도 제 머리(서슬·광점)를 가진 짧은 완성 획이 된다."""
    n = max(12, int(len(pts) * frac))
    im = samhap(pts[:n], palette(ink), base_w=base_w, white_scale=white_scale)
    if decay > 0:
        a = im.split()[3].point(lambda v: int(v * (1.0 - decay * 0.72)))
        im.putalpha(a)
    return im


def band_frames(ink: str = "청회", res: int = 256) -> list:
    """★ 팩이 굽는 확정 5장 — 띠 실루엣 × 삼합 붓 × FRAMES_5.
    tools/respack/qi.py write_kigi_assets 가 이것을 불러 kigi/arc1..5 로 만든다.
    격 사다리 승급(강기·어검…)은 ink 인자만 바꾸면 된다.
    res=256: 64 였을 땐 띠 높이 22px 에 획이 3px 이 되어 삼합의 층이 흰 덩어리로 뭉개졌다."""
    old = OUT
    set_res(res)
    try:
        pts = sil_arc_band()
        return [frame(pts, ink, fr, dc, base_w=W * BAND_W_SCALE, white_scale=0.45)
                for fr, dc in FRAMES_5]
    finally:
        set_res(old)


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
