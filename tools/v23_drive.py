#!/usr/bin/env python3
"""V2-W 23차 — 레퍼런스 번개 검 **정밀 복제**(ref_blade) 몽타주. 스크래치(커밋 대상 아님).

  scratch/v23_ref_replica.png — [ 레퍼런스 3장 | 복제 GUI 3/4 | 회전 시점 3 | 번개 애니 8프레임 ]

렌더러는 refblade_forge 의 기하(BODY_SPEC·BOLTS_SPEC)와 실제 팩 텍스처(ref_bolt.png)를 읽어
자체 판정용 그림을 만든다 (인게임 클라이언트가 아니라 파이썬 근사 — 색·비례 대조가 목적).
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import refblade_forge as rb  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
REF = os.path.join(HERE, "..", "scratch", "ref_lightning")
PACK_BOLT = os.path.join(HERE, "..", "resourcepack", "assets", "honcheon",
                         "textures", "item", "weapon", "ref_bolt.png")
OUT = os.path.join(HERE, "..", "scratch", "v23_ref_replica.png")
S = 14
BG = (16, 20, 26, 255)

REGION_COL = {"core": rb.CORE_MID, "edge": rb.EDGE_MID, "gold": rb.GOLD_MID, "grip": rb.GRIP_MID}
REGION_HI = {"core": rb.CORE_HI, "edge": rb.EDGE_HI, "gold": rb.GOLD_HI, "grip": rb.GRIP_HI}


def _font(sz):
    for p in ("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        if os.path.exists(p):
            return ImageFont.truetype(p, sz)
    return ImageFont.load_default()


def _project(box, ca, sa):
    """8 코너 → (sx, sy, depth). 길이축(X)=화면 세로, 폭/두께 회전(roll about X)."""
    fx, fy, fz, tx, ty, tz = box
    pts = []
    for xx in (fx, tx):
        for yy in (fy, ty):
            for zz in (fz, tz):
                yr = (yy - 8) * ca - (zz - 8) * sa
                zr = (yy - 8) * sa + (zz - 8) * ca
                sx = yr * S + zr * 0.42 * S
                sy = -xx * S + zr * 0.30 * S
                pts.append((sx, sy, zr))
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    return min(xs), min(ys), max(xs), max(ys), sum(p[2] for p in pts) / 8


def render(roll_deg, bolt_frame):
    ca, sa = math.cos(math.radians(roll_deg)), math.sin(math.radians(roll_deg))
    prims = []
    for box, region in rb.BODY_SPEC:
        prims.append(("body", region, _project(box, ca, sa)))
    for box in rb.BOLTS_SPEC:
        prims.append(("bolt", None, _project(box, ca, sa)))
    allp = [p[2] for p in prims]
    minx = min(p[0] for p in allp) - 8
    miny = min(p[1] for p in allp) - 8
    maxx = max(p[2] for p in allp) + 8
    maxy = max(p[3] for p in allp) + 8
    im = Image.new("RGBA", (int(maxx - minx), int(maxy - miny)), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    strip = Image.open(PACK_BOLT).convert("RGBA")
    prims.sort(key=lambda p: p[2][4])                # 뒤에서 앞으로 (depth)
    for kind, region, (x0, y0, x1, y1, zc) in prims:
        px0, py0, px1, py1 = x0 - minx, y0 - miny, x1 - minx, y1 - miny
        if kind == "bolt":
            frame = strip.crop((0, bolt_frame * 32, 32, bolt_frame * 32 + 32))
            w, h = max(4, int(px1 - px0)), max(4, int(py1 - py0))
            im.alpha_composite(frame.resize((w, h), Image.NEAREST), (int(px0), int(py0)))
        else:
            base = REGION_COL[region]
            hi = REGION_HI[region]
            t = max(0.0, min(1.0, (zc + 1.4) / 2.8))  # 앞면(가까운 z) 밝게
            col = tuple(round(base[i] + (hi[i] - base[i]) * t) for i in range(3)) + (255,)
            d.rectangle([px0, py0, px1, py1], fill=col, outline=(20, 24, 30, 255))
    return im


def _fit(im, target_h, pad=(0, 0, 0, 0)):
    r = target_h / im.height
    return im.resize((max(1, int(im.width * r)), target_h), Image.NEAREST)


def montage():
    H = 560
    ref_h = H - 40
    refs = [_fit(Image.open(os.path.join(REF, f)).convert("RGBA"), ref_h)
            for f in ("ref1.png", "ref2.png", "ref3.png")]
    gui = _fit(render(-18, 0), ref_h)                # 복제 GUI 3/4
    roll_h = (ref_h - 24) // 3
    rolls = [_fit(render(a, 0), roll_h) for a in (-45, 0, 45)]   # 회전 시점 (두께·양날이 드러난다)
    # 번개 애니 8프레임 (스트립 확대)
    strip = Image.open(PACK_BOLT).convert("RGBA")
    fr = [strip.crop((0, i * 32, 32, i * 32 + 32)).resize((72, 72), Image.NEAREST)
          for i in range(8)]

    f_t = _font(26)
    f_c = _font(19)
    gap = 20
    x = gap
    cols = []
    for r in refs:
        cols.append(("ref", r, x)); x += r.width + gap
    x += 20
    cols.append(("gui", gui, x)); x += gui.width + gap + 24
    roll_x = x
    x += max(r.width for r in rolls) + gap + 28
    anim_x = x
    W = anim_x + 4 * 74 + gap

    cv = Image.new("RGBA", (W, H + 90), (12, 15, 20, 255))
    d = ImageDraw.Draw(cv)
    d.text((gap, 12), "레퍼런스 (3인칭 인게임)", font=f_c, fill=(150, 210, 225))
    for _, im, xx in cols:
        cv.alpha_composite(_bg(im), (xx, 46))
    d.text((cols[3][2], 12), "복제 GUI 3/4", font=f_c, fill=(150, 225, 210))
    d.text((roll_x, 12), "회전 시점", font=f_c, fill=(150, 225, 210))
    for i, r in enumerate(rolls):
        cv.alpha_composite(_bg(r), (roll_x, 46 + i * (roll_h + 8)))
    d.text((anim_x, 12), "번개 애니 8프레임", font=f_c, fill=(150, 225, 210))
    for i, im in enumerate(fr):
        col, row = i % 4, i // 4
        cv.alpha_composite(_bg(im, (8, 11, 16, 255)),
                           (anim_x + col * 74, 46 + row * 84))
        d.text((anim_x + col * 74 + 2, 46 + row * 84 + 74), f"f{i}", font=_font(14),
               fill=(120, 160, 175))
    d.text((gap, H + 52),
           "복제 구성: 검신 7 cuboid(청백 심+청록 결·볼록 테이퍼) · 금 십자 코등이 3 · 자루 감김+금 물미 2 · 번개 평면 3(칼끝2·코등이1, 8프레임 애니 frametime 2)",
           font=f_c, fill=(180, 190, 200))
    cv.convert("RGB").save(OUT)
    print("wrote", os.path.relpath(OUT, os.path.join(HERE, "..")), cv.size)


def _bg(im, bg=BG):
    b = Image.new("RGBA", im.size, bg)
    b.alpha_composite(im)
    return b


if __name__ == "__main__":
    montage()
