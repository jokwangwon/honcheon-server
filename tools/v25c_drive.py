#!/usr/bin/env python3
"""V2-W 25c — 코등이 대칭·sweep 방향 수리 몽타주 (★세운 자세 tip-down · MC 면 음영). 스크래치.

  scratch/v25c_clean_pilot.png
    상단: [ 레퍼런스 vid_zoom1(코등이) | 검 코등이 확대(세운 자세) | 검 전신(세운) | 도 전신(세운) ]
    하단: 코등이 확대 대조 [ 25b 옛(처진 비대칭) 재현 | 25c 수리(수평 대칭) ] — 세운 자세.

★사용자(25c): "코등이는 왜 길이가 다르죠?" — 두 버그: ① 팔이 칼끝(세운 자세=아래)으로 처짐
  (sweep 방향 반대) ② 좌우 비대칭(길이 다름). 수리: 수평 팔 + finial 만 자루 쪽(-X=위) 살짝 ·
  _mirror_y 로 완벽 대칭. **세운 자세(tip 아래)** 로 렌더해 인게임 전시대와 같은 상하를 본다.
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import refblade_forge as rb  # noqa: E402
from respack import clean_weapons as cw  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
REF = os.path.join(ROOT, "scratch", "ref_lightning")
OUT = os.path.join(ROOT, "scratch", "v25c_clean_pilot.png")
S = 16
BG = (18, 22, 30, 255)
FACE = {"up": (0, 1.0), "down": (2, 0.5), "north": (1, 0.8),
        "south": (1, 0.8), "east": (0, 0.6), "west": (2, 0.6)}
NORMAL = {"up": (0, 1, 0), "down": (0, -1, 0), "north": (0, 0, -1),
          "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0)}


def _font(sz):
    for p in ("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        if os.path.exists(p):
            return ImageFont.truetype(p, sz)
    return ImageFont.load_default()


def _rot_vec(v, rot):
    if not rot:
        return v
    axis, angle, _o = rot
    a = math.radians(angle)
    c, s = math.cos(a), math.sin(a)
    x, y, z = v
    if axis == "z":
        return (x * c - y * s, x * s + y * c, z)
    if axis == "y":
        return (x * c + z * s, y, -x * s + z * c)
    return (x, y * c - z * s, y * s + z * c)


def _rot_pt(p, rot):
    if not rot:
        return p
    axis, angle, o = rot
    a = math.radians(angle)
    c, s = math.cos(a), math.sin(a)
    x, y, z = p[0] - o[0], p[1] - o[1], p[2] - o[2]
    if axis == "z":
        x, y = x * c - y * s, x * s + y * c
    elif axis == "y":
        x, z = x * c + z * s, -x * s + z * c
    else:
        y, z = y * c - z * s, y * s + z * c
    return (x + o[0], y + o[1], z + o[2])


def _proj(p, ca, sa, flipx):
    """flipx=-1 → 세운 자세(칼끝 아래 · 인게임 전시대). flipx=+1 → 칼끝 위."""
    x, y, z = p
    yr = (y - 8) * ca - (z - 8) * sa
    zr = (y - 8) * sa + (z - 8) * ca
    return (yr * S + zr * 0.42 * S, flipx * (-x * S) + zr * 0.30 * S, zr)


def _face_corners(f, name):
    fx, fy, fz, tx, ty, tz = f
    return {
        "up": [(fx, ty, fz), (tx, ty, fz), (tx, ty, tz), (fx, ty, tz)],
        "down": [(fx, fy, fz), (tx, fy, fz), (tx, fy, tz), (fx, fy, tz)],
        "north": [(fx, fy, fz), (tx, fy, fz), (tx, ty, fz), (fx, ty, fz)],
        "south": [(fx, fy, tz), (tx, fy, tz), (tx, ty, tz), (fx, ty, tz)],
        "east": [(tx, fy, fz), (tx, ty, fz), (tx, ty, tz), (tx, fy, tz)],
        "west": [(fx, fy, fz), (fx, ty, fz), (fx, ty, tz), (fx, fy, tz)],
    }[name]


def _shaded(rgb, mult):
    return tuple(min(255, max(0, round(rgb[i] * mult))) for i in range(3)) + (255,)


def render(elems, palette, roll_deg, flipx=-1, pad=6):
    ca, sa = math.cos(math.radians(roll_deg)), math.sin(math.radians(roll_deg))
    faces = []
    for box, region, rot in elems:
        shades = palette[region]
        for name, (si, mult) in FACE.items():
            nx, ny, nz = _rot_vec(NORMAL[name], rot)
            if ny * sa + nz * ca < -0.12:
                continue
            pts = [_proj(_rot_pt(c, rot), ca, sa, flipx) for c in _face_corners(box, name)]
            faces.append((sum(p[2] for p in pts) / 4, [(p[0], p[1]) for p in pts],
                          _shaded(shades[si], mult)))
    allp = [pt for _d, poly, _c in faces for pt in poly]
    minx = min(p[0] for p in allp) - pad
    miny = min(p[1] for p in allp) - pad
    maxx = max(p[0] for p in allp) + pad
    maxy = max(p[1] for p in allp) + pad
    im = Image.new("RGBA", (max(1, int(maxx - minx)), max(1, int(maxy - miny))), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for _depth, poly, col in sorted(faces, key=lambda f: f[0]):
        d.polygon([(x - minx, y - miny) for x, y in poly], fill=col, outline=(14, 17, 22, 255))
    return im


def _clean_elems(series, grade, xf=None):
    out = []
    for e in cw.clean_body_spec(series, grade):
        box, rot = e[0], (e[2] if len(e) > 2 else None)
        if xf and not (xf[0] <= (box[0] + box[3]) / 2 <= xf[1]):
            continue
        out.append((box, e[1], rot))
    return out


# 옛 25b 코등이(처진 비대칭) 재현 — 대조용 (아래 팔이 위 팔과 다른 자리 = 비대칭 + ±22.5 처짐)
def _old_guard_elems():
    UP, LO = ("z", -22.5), ("z", 22.5)
    o_up, o_lo = (1.5, 9.4, 8.0), (1.5, 6.6, 8.0)
    base = [((0.4, 6.6, 7.15, 2.6, 9.4, 8.85), "gold", None),
            ((0.7, 7.0, 8.6, 2.3, 9.0, 8.95), "gold", None),
            ((0.9, 9.4, 7.35, 2.1, 12.4, 8.65), "gold", (*UP, o_up)),
            ((0.95, 12.4, 7.55, 2.05, 13.5, 8.45), "gold", (*UP, o_up)),
            ((0.9, 5.6, 7.35, 2.1, 8.6, 8.65), "gold", (*LO, o_lo)),        # 옛 아래 팔 — 자리 어긋남
            ((0.95, 4.5, 7.55, 2.05, 5.6, 8.45), "gold", (*LO, o_lo)),
            ((0.7, 7.1, 8.6, 2.3, 8.9, 9.25), "gem", None),
            ((1.15, 7.5, 9.15, 1.85, 8.5, 9.55), "gem", None),
            ((2.5, 6.95, 7.45, 4.2, 9.05, 8.55), "gold", None)]
    return base


def _fit(im, h):
    return im.resize((max(1, int(im.width * h / im.height)), h), Image.NEAREST)


def _bg(im):
    b = Image.new("RGBA", im.size, BG)
    b.alpha_composite(im)
    return b


def montage():
    full_h, guard_h = 470, 300
    f_hdr, f_sub, f_note = _font(23), _font(19), _font(18)
    gx = (-1.0, 6.0)
    pal_sw = cw.clean_palette("sword", "sinbyeong")
    pal_dao = cw.clean_palette("dao", "sinbyeong")

    vid = _fit(Image.open(os.path.join(REF, "vid_zoom1.png")).convert("RGBA").crop((150, 250, 560, 430)), guard_h)
    g_new = _fit(render(_clean_elems("sword", "sinbyeong", gx), pal_sw, -16, flipx=-1), guard_h)
    sw_full = _fit(render(_clean_elems("sword", "sinbyeong"), pal_sw, -16, flipx=-1), full_h)
    dao_full = _fit(render(_clean_elems("dao", "sinbyeong"), pal_dao, -16, flipx=-1), full_h)
    g_old = _fit(render(_old_guard_elems(), pal_sw, -16, flipx=-1), guard_h)

    top = [("레퍼런스 vid_zoom1 (코등이)", vid, (150, 210, 225)),
           ("검 코등이 25c 수리 (세운 자세)", g_new, (150, 235, 220)),
           ("검 전신 (세운 자세·MC음영)", sw_full, (150, 235, 220)),
           ("도 전신 (세운 자세)", dao_full, (240, 170, 155))]
    bot = [("옛 25b — 처진 비대칭 (버그 재현)", g_old, (235, 150, 140)),
           ("25c 수리 — 수평·완벽 대칭", g_new, (150, 235, 210))]

    gap = 26
    x = gap
    txs = []
    for _t, im, _c in top:
        txs.append(x)
        x += im.width + gap
    topW = x
    x = gap
    bxs = []
    for _t, im, _c in bot:
        bxs.append(x)
        x += im.width + gap
    W = max(topW, x)
    ty, by = 54, 54 + full_h + 60
    H = by + guard_h + 96

    cv = Image.new("RGBA", (W, H), (12, 15, 20, 255))
    d = ImageDraw.Draw(cv)
    d.text((gap, 14), "V2-W 25c — 코등이 대칭·sweep 방향 수리 (★세운 자세 tip-down · MC 면 음영)",
           font=f_hdr, fill=(185, 222, 232))
    for (title, im, c), xx in zip(top, txs):
        d.text((xx, ty - 24), title, font=f_sub, fill=c)
        cv.alpha_composite(_bg(im), (xx, ty))
    d.text((gap, by - 28), "── 코등이 대조 (세운 자세) — 옛 처진 비대칭 vs 수리 수평 대칭 ──",
           font=f_sub, fill=(200, 210, 220))
    for (title, im, c), xx in zip(bot, bxs):
        d.text((xx, by - 4), title, font=f_note, fill=c)
        cv.alpha_composite(_bg(im), (xx, by + 18))
    d.text((gap, H - 62),
           "수리: 팔 수평(옛 ±22.5° 처짐 제거) · finial 만 자루 쪽(-X=세운 자세 위)으로 살짝 상향 flare · "
           "_mirror_y 로 두 팔 완벽 대칭(길이·자리 동일) · _guard_symmetry_selftest 임포트마다 검산. 색·보석·검신 색층은 25b 유지.",
           font=f_note, fill=(175, 190, 200))
    cv.convert("RGB").save(OUT)
    print("wrote", os.path.relpath(OUT, ROOT), cv.size)


if __name__ == "__main__":
    montage()
