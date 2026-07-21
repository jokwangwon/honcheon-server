#!/usr/bin/env python3
"""검압(劍壓)형 검기 — 「압축된 절단파」 (2026-07-21 사용자·자문 합의, 2차 정련).

【확정 문법】 (자문 2차 검토 반영)
  · A 기본: 오목면 서슬 (내려베기·횡베기) / B = 같은 체계의 방향 변주 (올려베기 — 서슬이 볼록면)
  · C 는 독립안이 아니라 **A의 소멸 프레임 투명도**다 — A와 C는 시간축의 앞뒤
  · 구조: 검압 몸체 면 하나 + 서슬 근처 좁은 압축대 + 흐름 결 한 줄(희미) —
    내부 평행 띠 금지. 밀도는 서슬 쪽이 짙고 먹 테 쪽으로 빠르게 흐려진다
  · 머리: 최대 폭 지점 t≈0.75, 끝 10~15% 급수축. 서슬 쪽 끝이 더 길고 먹 테 쪽은 짧게 끊김 (비대칭)
  · 서슬: 꼬리 15% 없음 → 중간 가늘게 → 머리 직전 25% 최대 밝기 → 끝 급수축. 미세 단절 1~2개
  · 먹 테: 얇게(기존 65%), 몸체에 밀착(안쪽 부드러운 전이대), 꼬리에서 서서히 끊김
  · 잔상: **피크 프레임엔 없다** — 절단 통과 프레임에만, 본체 가까이 짧게(길이 25~40%)

【3프레임 시간 구조 — 평타】
  1 압축 피크: 몸 80% · 잔상 없음 · 머리 국소 번짐만
  2 절단 통과: 몸 62%(C) · 전방 이동 + 폭 +7% · 잔상 출현 · 서슬 가늘지만 밝음
  3 붕괴: 몸 거의 소멸 · 서슬 끊어진 토막 · 먹 테는 머리 부근만 · 절단선 3개 → 다음 틱 삭제
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import kigi_forge as F
from PIL import Image, ImageDraw, ImageFilter

WHITE = (255, 255, 255)
WMAX_SCALE = 0.145               # 몸체 최대 반폭 (자문: 기존 0.165 에서 12% 감량)


def _lerp(a, b, t):
    return a + (b - a) * t


def _between(p, q, f):
    return (p[0] + (q[0] - p[0]) * f, p[1] + (q[1] - p[1]) * f)


def width_profile(t: float) -> float:
    """꼬리 가늘게 → 급팽창 → 최대(t≈0.75) → 끝 12% 급수축(가장자리별 비대칭은 edge_cut)."""
    if t < 0.20:
        return _lerp(0.10, 0.30, (t / 0.20) ** 0.8)
    if t < 0.75:
        return _lerp(0.30, 1.00, ((t - 0.20) / 0.55) ** 0.85)
    if t < 0.88:
        return 1.00
    return max(0.10, 1.00 - ((t - 0.88) / 0.12) ** 1.1 * 0.92)


def edge_cut(t: float, side: str) -> float:
    """비대칭 붓칼 끝 — 먹 테(바깥) 쪽은 짧게 끊기고, 서슬 쪽은 길고 날카롭게 뻗는다."""
    if side == "outer":
        return 1.0 if t < 0.90 else max(0.0, (0.955 - t) / 0.055)
    return 1.0 if t < 0.93 else max(0.0, (1.0 - t) / 0.07)


def _edges(pts, wmax, edge_side):
    n = len(pts)
    outer, inner = [], []
    for i in range(n):
        t = i / (n - 1)
        w = wmax * width_profile(t)
        o_side = "outer" if edge_side == "inner" else "inner"
        i_side = "inner" if edge_side == "inner" else "outer"
        outer.append(F._normal(pts, i, -w * 0.42 * edge_cut(t, o_side)))
        inner.append(F._normal(pts, i, w * 0.58 * edge_cut(t, i_side)))
    return outer, inner


def _seosul_w(t: float) -> float:
    """서슬의 구간별 굵기·존재 — 꼬리 없음 → 가늘게 → 머리 직전 최대 → 끝 급수축."""
    if t < 0.15:
        return 0.0
    if t < 0.60:
        return 0.62
    if t < 0.85:
        return _lerp(0.9, 1.25, (t - 0.60) / 0.25)
    return max(0.0, 1.25 * (1.0 - ((t - 0.85) / 0.15) ** 1.3))


SEOSUL_BREAKS = ((0.515, 0.530), (0.775, 0.786))    # 미세 단절 — 속도감


def press_brush(pts, ink: str = "청회", *, edge_side: str = "inner",
                mode: str = "peak", wmax: float = None) -> Image.Image:
    """검압형 붓. mode: peak(압축 피크) | pass(절단 통과) | collapse(붕괴)."""
    pal = F.palette(ink)
    W = F.W
    wmax = (wmax or W * WMAX_SCALE) * (1.07 if mode == "pass" else 1.0)
    body_alpha = {"peak": 0.82, "pass": 0.62, "collapse": 0.10}[mode]
    # ★ 절단 통과 = **후방 30% 를 비운다** (검토 2차: 옛 pts 절단+끝점 중복은 이동이 아니었다 —
    #   같은 그림이 다시 그려졌다). 본체가 앞으로 빠져나가고 뒤가 비는 것이 2틱에서 읽히는 차이다.
    rear_cut = 0.30 if mode == "pass" else 0.0
    n = len(pts)
    ts = [i / (n - 1) for i in range(n)]
    outer, inner = _edges(pts, wmax, edge_side)
    im = F.canvas()

    def alive(t):
        """후방 비움 게이트 — rear_cut 이전은 없고, 경계 6% 구간은 알파 램프."""
        if rear_cut <= 0:
            return 1.0
        if t < rear_cut:
            return 0.0
        return min(1.0, (t - rear_cut) / 0.06)

    # ── ① 검압 몸체 — 면 하나. 서슬 쪽이 짙고 먹 테 쪽으로 빠르게 흐려진다
    if body_alpha > 0.12:
        lay = F.canvas(); d = ImageDraw.Draw(lay)
        SLICES = 5
        for i in range(n - 1):
            t = ts[i]
            col = F._mix(F._mix(pal["glow_deep"], pal["glow_mid"], min(1.0, t * 1.6)),
                         F.INKS["청백"], max(0.0, (t - 0.74) * 1.5))
            base_a = _lerp(55, 150, min(1.0, t * 1.35)) * body_alpha * alive(t)
            for k in range(SLICES):
                f0, f1 = k / SLICES, (k + 1) / SLICES
                fm = (k + 0.5) / SLICES
                dens = 0.22 + 0.78 * (fm if edge_side == "inner" else 1.0 - fm) ** 1.6   # 밝은 배경 보강 (검토 3.7)
                p00 = _between(outer[i], inner[i], f0); p01 = _between(outer[i], inner[i], f1)
                p10 = _between(outer[i + 1], inner[i + 1], f0); p11 = _between(outer[i + 1], inner[i + 1], f1)
                d.polygon([p00, p10, p11, p01], fill=(*col, int(base_a * dens)))
        im.alpha_composite(lay)

        # 서슬 근처 좁은 압축대 — 몸과 서슬 사이의 밀도 응축
        lay = F.canvas(); d = ImageDraw.Draw(lay)
        for i in range(n - 1):
            t = ts[i]
            if t < 0.18:
                continue
            f0, f1 = (0.86, 0.985) if edge_side == "inner" else (0.015, 0.14)
            col = F._mix(pal["glow_mid"], F.INKS["청백"], min(1.0, 0.35 + max(0.0, t - 0.6)))
            p00 = _between(outer[i], inner[i], f0); p01 = _between(outer[i], inner[i], f1)
            p10 = _between(outer[i + 1], inner[i + 1], f0); p11 = _between(outer[i + 1], inner[i + 1], f1)
            d.polygon([p00, p10, p11, p01],
                      fill=(*col, int(95 * body_alpha / 0.82 * min(1.0, t * 1.5) * alive(t))))
        im.alpha_composite(lay)

        # 흐름 결 — 딱 한 줄, 아주 희미하게 (부분적)
        lay = F.canvas(); d = ImageDraw.Draw(lay)
        for i in range(n):
            t = ts[i]
            if not (0.30 <= t <= 0.72):
                continue
            x, y = _between(outer[i], inner[i], 0.45)
            ww = wmax * 0.022 * (0.5 + t)
            fade = min(1.0, 7.0 * min(t - 0.30, 0.72 - t) / 0.42)
            d.ellipse([x - ww, y - ww, x + ww, y + ww],
                      fill=(*F._mix(pal["glow_mid"], F.INKS["청백"], 0.4), int(48 * fade)))
        im.alpha_composite(lay)

    # ── 머리 국소 번짐
    if mode != "collapse":
        lay = F.canvas(); d = ImageDraw.Draw(lay)
        hx, hy = pts[int(n * 0.86)]
        r = wmax * 0.80
        d.ellipse([hx - r, hy - r, hx + r, hy + r], fill=(*F.INKS["청백"], 60))
        lay = lay.filter(ImageFilter.GaussianBlur(W / 64 * 1.8))
        im.alpha_composite(lay)

    # ── ④ 잔상 — 절단 통과 프레임에만. 본체 가까이, 짧게(≈본체의 35%), 얇아지다 끊긴다
    if mode == "pass":
        lay = F.canvas(); d = ImageDraw.Draw(lay)
        echo_col = F._mix(pal["body"], pal["glow_mid"], 0.45)
        for i in range(n):
            t = ts[i]
            if not (0.28 <= t <= 0.63):
                continue
            u = (t - 0.28) / 0.35
            if u > 0.92:                                 # 흐려지는 게 아니라 — 끊긴다
                continue
            gap = wmax * (0.34 - 0.10 * u)               # 본체 가까이 · 곡률 살짝 평평
            ex, ey = F._normal(pts, i, wmax * 0.58 + gap)
            ww = wmax * 0.038 * (1.0 - u * 0.75)         # 얇아지다
            d.ellipse([ex - ww, ey - ww, ex + ww, ey + ww], fill=(*echo_col, 145))
        im.alpha_composite(lay)

    # ── ③ 먹 테 — 얇고 몸체에 밀착 (안쪽 전이대), 꼬리에서 서서히 끊김
    lay = F.canvas(); d = ImageDraw.Draw(lay)
    dark_is_outer = (edge_side == "inner")
    dark_edge = outer if dark_is_outer else inner
    for i in range(n):
        t = ts[i]
        cut = edge_cut(t, "outer" if dark_is_outer else "inner")
        if cut <= 0.4:
            continue
        if mode == "collapse" and t < 0.62:              # 붕괴: 머리 부근만 잔류
            continue
        cutf = min(1.0, (cut - 0.4) / 0.35)
        tail_in = min(1.0, max(0.0, (t - 0.04) / 0.10))  # 꼬리에서 서서히
        head_thick = 1.0 + max(0.0, (t - 0.80)) * 1.6    # 머리 쪽은 몸과 합쳐지며 두꺼워도 된다
        ww = wmax * 0.052 * (0.45 + 0.55 * width_profile(t)) * cutf * tail_in * head_thick
        if ww <= 0:
            continue
        x, y = dark_edge[i]
        # 안쪽 전이대(부드럽게 몸과 붙인다) → 겉의 선명한 먹 선
        tx, ty = _between((x, y), _between(outer[i], inner[i], 0.5), 0.22)
        d.ellipse([tx - ww * 1.7, ty - ww * 1.7, tx + ww * 1.7, ty + ww * 1.7],
                  fill=(*F._mix(pal["body"], pal["glow_deep"], 0.5), int(110 * tail_in)))
        d.ellipse([x - ww, y - ww, x + ww, y + ww], fill=(*pal["body"], 250))
    im.alpha_composite(lay)

    # ── ② 서슬 — 구간별 굵기·밝기 + 미세 단절. 붕괴 프레임은 끊어진 토막만
    lay = F.canvas(); d = ImageDraw.Draw(lay)
    lit_edge = inner if edge_side == "inner" else outer
    for i in range(n):
        t = ts[i]
        sw = _seosul_w(t)
        if sw <= 0:
            continue
        if any(a <= t <= b for a, b in SEOSUL_BREAKS):
            continue
        if mode == "collapse" and not (0.35 <= t <= 0.46 or 0.67 <= t <= 0.94):
            continue                                     # 끊어진 토막 둘 — 진행 방향으로 밀린 자리 (검토 3.5)
        cut = edge_cut(t, "inner" if edge_side == "inner" else "outer")
        if cut <= 0.45:
            continue
        a_live = alive(t)
        if a_live <= 0:
            continue
        col = F._mix(F.INKS["청백"], WHITE, max(0.0, (t - 0.55) * 1.6))
        ww = wmax * 0.048 * sw * min(1.0, (cut - 0.45) / 0.35)
        if mode == "pass":
            ww *= 0.78
        x, y = lit_edge[i]
        # 접촉 그림자 — 서슬 바로 안쪽의 얇은 먹 (흰 구름 배경에서 서슬을 지킨다 · 검토 3.7).
        #   별도의 세 번째 선이 아니라 서슬에 붙은 그늘이다 (몸 쪽으로 반쯤 겹침)
        sx, sy = _between((x, y), _between(outer[i], inner[i], 0.5), 0.30)
        d.ellipse([sx - ww * 0.9, sy - ww * 0.9, sx + ww * 0.9, sy + ww * 0.9],
                  fill=(*F._mix(F.MEOK, F.INKS["청회"], 0.25), int(175 * a_live)))
        d.ellipse([x - ww, y - ww, x + ww, y + ww], fill=(*col, int(255 * a_live)))
    im.alpha_composite(lay)

    return im.resize((F.OUT, F.OUT), Image.LANCZOS)


def press_frames(ink: str = "청회", edge_side: str = "inner", res: int = 256) -> list:
    """★ 평타 3장 — 압축 피크 → 절단 통과 → 붕괴. (팩이 굽는 실물이 될 후보)"""
    old = F.OUT
    F.set_res(res)
    try:
        pts = F.sil_arc_band()
        return [press_brush(pts, ink, edge_side=edge_side, mode=m)
                for m in ("peak", "pass", "collapse")]
    finally:
        F.set_res(old)


def main():
    out = Path("작업물/검기/검압")
    out.mkdir(parents=True, exist_ok=True)
    band_h = 112
    cw, ch, pad = 256 * 2, band_h * 2, 10
    labels = ["1 압축 피크", "2 절단 통과", "3 붕괴"]
    for bgc, name in (((24, 27, 31), "어두운"), ((156, 190, 222), "밝은")):
        rows = []
        for side, sname in (("inner", "A 내려·횡베기"), ("outer", "B 올려베기")):
            rows.append((sname, press_frames("청회", side)))
        sheet = Image.new("RGB", ((cw + pad) * 3 + pad, (ch + pad + 26) * len(rows) + pad), (13, 15, 18))
        dr = ImageDraw.Draw(sheet)
        for r, (sname, frames) in enumerate(rows):
            y = pad + r * (ch + pad + 26)
            for c, im in enumerate(frames):
                band = im.crop((0, 0, 256, band_h)).resize((cw, ch), Image.NEAREST)
                bg = Image.new("RGBA", (cw, ch), (*bgc, 255))
                bg.alpha_composite(band)
                x = pad + c * (cw + pad)
                dr.text((x + 2, y + 2), f"{sname} · {labels[c]}", fill=(225, 228, 230))
                sheet.paste(bg.convert("RGB"), (x, y + 22))
        p = out / f"검압_정련_3프레임_{name}.png"
        sheet.save(p)
        print(f"그렸다: {p}")
    # GIF (A · 인게임 리듬 근사 2틱/장)
    frames = press_frames("청회", "inner")
    gif = []
    for im in frames:
        bg = Image.new("RGBA", (cw, ch), (24, 27, 31, 255))
        bg.alpha_composite(im.crop((0, 0, 256, band_h)).resize((cw, ch), Image.LANCZOS))
        gif.append(bg.convert("RGB"))
    gif.append(Image.new("RGB", (cw, ch), (24, 27, 31)))
    gif[0].save(out / "검압_A.gif", save_all=True, append_images=gif[1:],
                duration=[100, 100, 100, 420], loop=0)
    print(f"그렸다: {out}/검압_A.gif")


if __name__ == "__main__":
    main()
