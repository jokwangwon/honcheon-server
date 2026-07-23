#!/usr/bin/env python3
"""설치기 그림 굽기 — 수묵 산수 배경 · 붓 획 진행선 · 낙관 인장 · exe 아이콘

산출 (run/modpack/art/):
  honcheon_bg.png     520×300 @2x — 달밤 먹산 한 폭 (창 배경)
  honcheon_stroke.png 400×14 @2x — 청록 붓 획 (진행선 · 왼쪽부터 드러난다)
  honcheon_seal.png   96×96 @2x — 낙관 「入門」 (완료 도장)
  honcheon.ico        16~256 — 낙관을 아이콘으로

결정론: 고정 시드 — 같은 코드는 같은 그림을 낳는다 (다듬은 판이 재현된다).
"""
import numpy as np
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "run" / "modpack" / "art"
W, H = 1040, 600          # 2x — 내릴 때 붓이 부드러워진다
rng = np.random.default_rng(20260723)
FONT = "/usr/share/fonts/opentype/noto/NotoSerifCJK-Bold.ttc"

INK_SKY_TOP = np.array([13, 14, 20])
INK_SKY_LOW = np.array([26, 27, 36])
PAPER = np.array([232, 228, 218])


def ridge(n, roughness, octaves=7):
    """1D 프랙탈 능선 [0,1] — 사인 합성 + 봉우리 접기 (시드 고정).

    |사인|을 접으면(1-|y|) 골이 둥글고 봉우리가 뾰족해진다 — 구름이 아니라 산이 된다.
    """
    x = np.linspace(0, 1, n)
    y = np.zeros(n)
    for o in range(octaves):
        f = 2 ** o
        y += (roughness ** o) * np.abs(np.sin(np.pi * (f * x * rng.uniform(0.8, 1.5)
                                                       + rng.uniform(0, 1))))
    y = 1 - y / y.max()
    y -= y.min()
    return (y / max(1e-9, y.max())) ** 1.25


def paint_bg():
    # 하늘 — 위가 더 깊다
    t = np.linspace(0, 1, H)[:, None, None]
    img = (INK_SKY_TOP * (1 - t) + INK_SKY_LOW * t) * np.ones((H, W, 3))

    # 은은한 구름 결 (가로로 긴 저주파 노이즈)
    cloud = rng.normal(0, 1, (H // 8, W // 8))
    cloud = np.kron(cloud, np.ones((8, 8)))[:H, :W]
    cloud = np.array(Image.fromarray(((cloud - cloud.min()) / np.ptp(cloud) * 255)
                                     .astype(np.uint8)).filter(ImageFilter.GaussianBlur(24)))
    img += (cloud[:, :, None] / 255.0 - 0.5) * 10

    base = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))

    # 달 — 우상단. 무리(halo)를 세 겹으로
    moon = Image.new("L", (W, H), 0)
    md = ImageDraw.Draw(moon)
    cx, cy, r = int(W * 0.78), int(H * 0.24), 74
    for rr, a in ((r * 3.0, 22), (r * 1.9, 40), (r * 1.25, 70)):
        md.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=a)
    halo = moon.filter(ImageFilter.GaussianBlur(46))
    disc = Image.new("L", (W, H), 0)
    dd = ImageDraw.Draw(disc)
    dd.ellipse([cx - r, cy - r, cx + r, cy + r], fill=235)
    disc = disc.filter(ImageFilter.GaussianBlur(2.5))
    moonpaper = Image.new("RGB", (W, H), tuple(PAPER.astype(int)))
    base = Image.composite(moonpaper, base, halo)
    base = Image.composite(Image.new("RGB", (W, H), (238, 234, 222)), base, disc)

    # 산 네 겹 — 멀수록 옅고 흐리다 (대기원근). 봉우리는 뾰족하게, 안개는 얇게
    layers = [
        (0.46, 0.52, 0.30, (104, 108, 124), 4.5, 26),   # 원산 — 높고 옅다
        (0.60, 0.55, 0.24, (66, 70, 84), 2.6, 20),
        (0.74, 0.58, 0.18, (36, 38, 47), 1.4, 14),
        (0.90, 0.60, 0.11, (13, 13, 17), 0.8, 0),       # 근산 — 낮고 먹
    ]
    for top, rough, amp, color, blur, mist_a in layers:
        line = ridge(W, rough)
        ytop = (H * top - line * H * amp).astype(int)
        arr = np.zeros((H, W), dtype=np.uint8)
        for x in range(W):
            arr[max(0, ytop[x]):, x] = 255
        m = Image.fromarray(arr).filter(ImageFilter.GaussianBlur(blur))
        base = Image.composite(Image.new("RGB", (W, H), color), base, m)
        if mist_a > 0:
            # 능선 밑 안개 띠 — 산이 안개에 발을 담근다 (얇게 — 두꺼우면 구름이 된다)
            mist = Image.new("L", (W, H), 0)
            mistd = ImageDraw.Draw(mist)
            band_y = int(H * top + H * 0.035)
            mistd.rectangle([0, band_y, W, band_y + int(H * 0.028)], fill=mist_a)
            mist = mist.filter(ImageFilter.GaussianBlur(16))
            base = Image.composite(Image.new("RGB", (W, H), tuple((PAPER * 0.72).astype(int))),
                                   base, mist)

    # 아래 먹 안개 — 진행부의 자리 (글씨가 설 어둠)
    fade = Image.new("L", (W, H), 0)
    fd = ImageDraw.Draw(fade)
    for i in range(int(H * 0.34)):
        y = H - 1 - i
        fd.line([(0, y), (W, y)], fill=int(215 * (1 - i / (H * 0.34)) ** 1.6))
    base = Image.composite(Image.new("RGB", (W, H), (10, 10, 13)), base, fade)

    # 종이 결 — 아주 옅은 알갱이
    grain = rng.normal(0, 5.5, (H, W, 1))
    out = np.clip(np.asarray(base, dtype=float) + grain, 0, 255).astype(np.uint8)
    Image.fromarray(out).resize((520, 300), Image.LANCZOS).save(OUT / "honcheon_bg.png")


def paint_stroke():
    """청록 붓 획 — 머리 굵고 꼬리로 갈수록 얇게 스러진다 + 갈필(마른 붓) 결.

    설치기는 이 그림을 StretchImage 로 진행 폭만큼 늘린다 — 어느 길이에서도
    머리·꼬리가 산다 (자르면 꼬리가 죽는다).
    """
    w, h = 800, 28
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    xs = np.linspace(0, 1, w)
    half = (h * 0.5) * (0.92 - 0.74 * xs ** 1.35) \
        * (1 + 0.10 * np.sin(xs * 13 + 0.7))          # 흔들리는 손
    mid = h / 2 + np.sin(xs * 5.5) * 1.6              # 획의 허리
    for x in range(w):
        a = 255 if xs[x] < 0.86 else int(255 * (1 - (xs[x] - 0.86) / 0.14) ** 1.5)
        d.line([(x, mid[x] - half[x]), (x, mid[x] + half[x])], fill=(63, 167, 160, a))
    # 갈필 — 획 속을 긁는 마른 결 (세로 위치 고정의 옅은 줄)
    dry = ImageDraw.Draw(img)
    for _ in range(9):
        yy = float(rng.uniform(h * 0.2, h * 0.8))
        x0 = int(rng.uniform(w * 0.25, w * 0.6))
        dry.line([(x0, yy), (w - 1, yy + rng.uniform(-2, 2))],
                 fill=(22, 22, 26, int(rng.integers(60, 130))), width=1)
    img = img.filter(ImageFilter.GaussianBlur(0.6))
    img.resize((400, 14), Image.LANCZOS).save(OUT / "honcheon_stroke.png")


def paint_seal():
    """낙관 「入門」 — 주사 인주, 종이빛 획, 세로 두 자."""
    s = 384
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([8, 8, s - 8, s - 8], radius=42, fill=(176, 58, 46, 255))
    d.rounded_rectangle([26, 26, s - 26, s - 26], radius=30, outline=(242, 230, 216, 235),
                        width=10)
    font = ImageFont.truetype(FONT, 132, index=0)
    for ch, y in (("入", 52), ("門", 196)):
        bb = d.textbbox((0, 0), ch, font=font)
        d.text(((s - (bb[2] - bb[0])) / 2 - bb[0], y), ch, font=font,
               fill=(242, 230, 216, 255))
    # 도장의 숨 — 가장자리 뜯김 (알갱이 구멍)
    holes = Image.new("L", (s, s), 0)
    hd = ImageDraw.Draw(holes)
    for _ in range(240):
        x, y = rng.integers(8, s - 8, 2)
        edge = min(x, y, s - x, s - y)
        if edge < 34 and rng.random() < 0.8:
            r = int(rng.integers(2, 6))
            hd.ellipse([x - r, y - r, x + r, y + r], fill=255)
    img.putalpha(Image.composite(Image.new("L", (s, s), 0), img.getchannel("A"), holes))
    img = img.rotate(-3, expand=False, resample=Image.BICUBIC)
    img.resize((192, 192), Image.LANCZOS).save(OUT / "honcheon_seal.png")
    # 아이콘 — 같은 도장
    ico = img.resize((256, 256), Image.LANCZOS)
    ico.save(OUT / "honcheon.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48),
                                          (64, 64), (128, 128), (256, 256)])


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    paint_bg()
    paint_stroke()
    paint_seal()
    print("그렸다 —", *[p.name for p in sorted(OUT.iterdir())])
