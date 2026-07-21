#!/usr/bin/env python3
"""검기 그라데이션·형태 변주 — 「느낌이 빡 오게」 라운드 (2026-07-21 사용자 지시).

버스트 문법(제옹 레퍼런스 실측) 이후의 선호 학습 2막:
  · 색감: 등록부 먹빛만 쓰되 **램프(먹→청회→청백)** 로 기세의 흐름을 넣는다 (금지색 무접촉)
  · 형태: 굵은 살집 + 거친 톱니 + 파편 — 레퍼런스의 「꽝」 을 수묵으로 소화한다
변주는 여기서 실험하고, 채택되면 kigi_forge 로 승격한다 (대장간은 단일 진실 원천 유지).
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import kigi_forge as F
from PIL import Image, ImageDraw, ImageFilter

WHITE = (255, 255, 255)


def ramp(t: float, stops: list) -> tuple:
    """다단 색 램프 — stops = [(위치, 색), ...] 오름차순."""
    if t <= stops[0][0]:
        return stops[0][1]
    for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
        if t <= t1:
            k = (t - t0) / max(1e-6, t1 - t0)
            return F._mix(c0, c1, k)
    return stops[-1][1]


def stroke_grad(d, pts, ws, cols, alpha=255):
    """색이 흐르는 획 — 점마다 색이 다르다."""
    for (x, y), w, c in zip(pts, ws, cols):
        if w > 0:
            d.ellipse([x - w, y - w, x + w, y + w], fill=(*c, alpha))


def jag_widths(base, ts, jag: float, seed: int = 11):
    """폭 흔들림 — 약하게만 (뭉툭한 혹 방지. 이빨은 spikes 가 세운다)."""
    out = []
    for w, t in zip(base, ts):
        n1 = F.noise(t * 26.0, seed)
        bite = 1.0 + jag * 0.35 * (n1 - 0.5) * 2.0
        out.append(max(0.0, w * bite))
    return out


def spikes(d, pts, ws, ts, jag, col, seed=17):
    """바깥 날의 가시 이빨 — 레퍼런스의 찢긴 가장자리를 뾰족하게."""
    n = len(pts)
    step = max(6, n // 22)
    for i in range(step, n - step, step):
        t = ts[i]
        if t < 0.12:
            continue
        L = ws[i] * (0.7 + 1.5 * F.noise(t * 13.0, seed))      # 이빨 길이
        if L <= 0:
            continue
        bx, by = F._normal(pts, i, -ws[i] * 0.85)               # 볼록한 바깥 뿌리
        tx, ty = F._normal(pts, i, -(ws[i] * 0.85 + L * 2.2))   # 이빨 끝
        j = min(n - 1, i + max(2, step // 3))
        cx2, cy2 = F._normal(pts, j, -ws[j] * 0.8)
        k = max(0, i - max(2, step // 3))
        ax, ay = F._normal(pts, k, -ws[k] * 0.8)
        d.polygon([(ax, ay), (tx, ty), (cx2, cy2)], fill=(*col, 250))


def fragments(d, pts, base_w, col, seed=23, count=13):
    """파편 — 획 바깥쪽으로 튀는 조각들 (레퍼런스의 부서짐)."""
    n = len(pts)
    for i in range(count):
        t = 0.15 + 0.80 * F.noise(i * 3.7, seed)
        idx = min(n - 1, int(t * n))
        off = base_w * (1.3 + 1.6 * F.noise(i * 7.1, seed + 2))
        x, y = F._normal(pts, idx, -off)          # 볼록한 바깥쪽
        r = base_w * (0.10 + 0.14 * F.noise(i * 5.3, seed + 4))
        ang = F.noise(i * 9.9, seed + 6) * math.pi
        dx, dy = math.cos(ang) * r * 1.8, math.sin(ang) * r * 1.8
        d.polygon([(x - dx, y - dy), (x + dy * 0.6, y - dx * 0.6), (x + dx, y + dy)],
                  fill=(*col, 235))


def brush(pts, ink="청회", *, grad="none", jag=0.0, frag=False, bright=False,
          base_w=None, white_scale=0.45) -> Image.Image:
    """변주 붓 — 삼합의 층 순서를 지키되 색·폭·질감을 실험한다.

    grad  : none=현행 | along=꼬리 먹→몸 청회→머리 청백 (기세의 흐름)
    bright: 몸을 밝게 뒤집는다 — 먹은 얇은 테두리로 물러나고 속이 빛난다 (레퍼런스 문법)
    jag   : 0..1 톱니 강도 | frag: 파편 조각
    """
    pal = F.palette(ink)
    W = F.W
    base_w = base_w or W * F.BAND_W_SCALE
    bu = base_w / (W * 0.088)
    n = len(pts)
    ts = [i / (n - 1) for i in range(n)]
    press = [F.press(t) for t in ts]
    im = F.canvas()

    hot = F._mix(F.INKS["청백"], WHITE, 0.45)
    if grad == "along":
        gcols = [ramp(t, [(0.0, pal["glow_deep"]), (0.45, pal["glow_mid"]),
                          (0.85, F.INKS["청백"]), (1.0, hot)]) for t in ts]
    else:
        gcols = None

    # ① 담묵 번짐
    lay = F.canvas(); d = ImageDraw.Draw(lay)
    F.stroke(d, pts, [base_w * p * 1.6 for p in press], pal["glow_deep"], 55)
    lay = lay.filter(ImageFilter.GaussianBlur(W / 64 * 2.4 * bu))
    im.alpha_composite(lay)

    # ③ 겹겹 광채
    for scale, alpha, blur in ((2.0, 115, 3.0), (1.45, 165, 1.7)):
        lay = F.canvas(); d = ImageDraw.Draw(lay)
        ws = [base_w * p * scale for p in press]
        if gcols:
            stroke_grad(d, pts, ws, gcols, alpha)
        else:
            F.stroke(d, pts, ws, pal["glow_mid" if scale < 1.8 else "glow_deep"], alpha)
        lay = lay.filter(ImageFilter.GaussianBlur(W / 64 * blur * bu))
        im.alpha_composite(lay)

    # ② 몸 — bright 면 속이 빛나고 먹은 테두리로 물러난다
    body_ws = [base_w * p * 0.78 for p in press]
    if jag > 0:
        body_ws = jag_widths(body_ws, ts, jag)
    lay = F.canvas(); d = ImageDraw.Draw(lay)
    if jag > 0:
        spikes(d, pts, body_ws, ts, jag, pal["body"])
    if bright:
        F.stroke(d, pts, [w * 1.12 for w in body_ws], pal["body"], 245)       # 먹 테
        inner = [ramp(t, [(0.0, pal["glow_mid"]), (0.55, F.INKS["청백"]), (1.0, hot)])
                 if grad == "along" else
                 ramp(t, [(0.0, pal["glow_mid"]), (1.0, F.INKS["청백"])]) for t in ts]
        stroke_grad(d, pts, [w * 0.82 for w in body_ws], inner, 252)
    else:
        F.stroke(d, pts, body_ws, pal["body"], 250)
    im.alpha_composite(lay)

    # 갈필
    lay = F.canvas(); d = ImageDraw.Draw(lay)
    for off in (-0.5, -0.15, 0.3, 0.62):
        seg = [i for i in range(n) if ts[i] < 0.45]
        F.stroke(d, [F._normal(pts, i, base_w * off) for i in seg],
                 [base_w * press[i] * 0.15 * 0.78 for i in seg], pal["body"], 190)
    im.alpha_composite(lay)

    # ④ 서슬 줄 + 광점
    lay = F.canvas(); d = ImageDraw.Draw(lay)
    for off, (t0, t1), wmul, colkey in ((0.45, (0.30, 0.98), 0.16, "core"),
                                         (0.0, (0.05, 0.72), 0.12, "edge"),
                                         (-0.42, (0.42, 0.88), 0.09, "core")):
        seg = [i for i in range(n) if t0 <= ts[i] <= t1]
        F.stroke(d, [F._normal(pts, i, base_w * off) for i in seg],
                 [base_w * F.taper(ts[i]) * wmul * white_scale for i in seg],
                 pal[colkey], 255)
    x, y = pts[int(n * 0.97)]
    r = base_w * 0.205 * white_scale * (1.6 if grad == "along" else 1.0)
    d.ellipse([x - r, y - r, x + r, y + r], fill=(*hot, 255))
    im.alpha_composite(lay)

    # ⑤ 흰 심
    lay = F.canvas(); d = ImageDraw.Draw(lay)
    core_cols = ([ramp(t, [(0.0, pal["core"]), (0.8, WHITE)]) for t in ts]
                 if grad == "along" else [pal["core"]] * n)
    stroke_grad(d, pts, [base_w * p * 0.35 * white_scale for p in press], core_cols, 255)
    im.alpha_composite(lay)

    # 파편
    if frag:
        lay = F.canvas(); d = ImageDraw.Draw(lay)
        fragments(d, pts, base_w, F._mix(pal["glow_mid"], WHITE, 0.3))
        im.alpha_composite(lay)

    return im.resize((F.OUT, F.OUT), Image.LANCZOS)


VARIANTS = [
    ("가 현행 살집",        dict()),
    ("나 기세 흐름",        dict(grad="along")),
    ("다 발광 몸(먹 테)",   dict(bright=True)),
    ("라 톱니+파편",        dict(jag=0.55, frag=True)),
    ("마 흐름+톱니+파편",   dict(grad="along", jag=0.55, frag=True)),
    ("바 발광+흐름+파편",   dict(bright=True, grad="along", frag=True)),
]


def main():
    out = Path("작업물/검기/변주")
    out.mkdir(parents=True, exist_ok=True)
    F.set_res(256)
    try:
        pts = F.sil_arc_band()
        band_h = 112
        cw, ch, pad = 256 * 2, band_h * 2, 10
        sheet = Image.new("RGB", (cw + pad * 2, (ch + pad + 26) * len(VARIANTS) + pad), (13, 15, 18))
        dr = ImageDraw.Draw(sheet)
        for i, (name, kw) in enumerate(VARIANTS):
            im = brush(pts, "청회", **kw)
            im.save(out / f"{i}_{name.split()[0]}.png")
            band = im.crop((0, 0, 256, band_h)).resize((cw, ch), Image.NEAREST)
            bg = Image.new("RGBA", (cw, ch), (24, 27, 31, 255))
            bg.alpha_composite(band)
            y = pad + i * (ch + pad + 26)
            dr.text((pad + 2, y + 2), name, fill=(225, 228, 230))
            sheet.paste(bg.convert("RGB"), (pad, y + 22))
        p = out / "변주_6종.png"
        sheet.save(p)
        print(f"그렸다: {p}")
    finally:
        F.set_res(64)


if __name__ == "__main__":
    main()
