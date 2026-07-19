#!/usr/bin/env python3
"""V2-W 25b — 깨끗한 재건축 파일럿 **디테일 정교화** 몽타주 (★MC 면 음영 적용). 스크래치.

  scratch/v25b_clean_pilot.png
    상단: [ 레퍼런스 vid_zoom1 | 청뢰검 ref_blade | 검 신병(우리 수정) | 도 신병(우리 수정) ]
    하단: 코등이 확대 컷 [ ref_blade | 검 신병 | 도 신병 ] — 곡선 팔·finial·보석·층진 금 대조.

★사용자 지시(25b): "깨끗한데 디테일이 없다 → 깨끗하면서 디테일이 살아있다"(레퍼런스처럼).
  색+정교함을 함께. 몽타주만 보고 색 정하면 안 된다 — **MC 면 음영(위 1.0·아래 0.5·남북 0.8·
  동서 0.6)** 을 면마다 적용해 "인게임에서 색+디테일이 함께 읽히는지" 확인한다.
"""
import json
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
OUT = os.path.join(ROOT, "scratch", "v25b_clean_pilot.png")
S = 15
BG = (18, 22, 30, 255)

# MC 면 음영 (블록/모델 규약) + _faces 의 텍스처 단(0=hi·1=mid·2=dim) 매핑.
#   up=+Y(1.0·hi) · down=-Y(0.5·dim) · north=-Z(0.8·mid) · south=+Z(0.8·mid) · east=+X(0.6·hi) · west=-X(0.6·dim)
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
    """방향 벡터를 element 회전(축·각·원점 무관)만큼 돌린다."""
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
    return (x, y * c - z * s, y * s + z * c)      # x


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


def _proj(p, ca, sa):
    x, y, z = p
    yr = (y - 8) * ca - (z - 8) * sa
    zr = (y - 8) * sa + (z - 8) * ca
    return (yr * S + zr * 0.42 * S, -x * S + zr * 0.30 * S, zr)


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


def render(elems, palette, roll_deg, pad=6):
    """elems = [(box, region, rot)…] · palette: region→(hi,mid,dim). MC 면 음영 · painter's."""
    ca, sa = math.cos(math.radians(roll_deg)), math.sin(math.radians(roll_deg))
    faces = []                                    # (depth, poly2d, color)
    for box, region, rot in elems:
        shades = palette[region]
        for name, (si, mult) in FACE.items():
            nx, ny, nz = _rot_vec(NORMAL[name], rot)
            nzr = ny * sa + nz * ca               # 롤 뒤 시선(+zr) 성분 — 뒷면 컬링
            if nzr < -0.12:
                continue
            pts = [_proj(_rot_pt(c, rot), ca, sa) for c in _face_corners(box, name)]
            depth = sum(p[2] for p in pts) / 4
            faces.append((depth, [(p[0], p[1]) for p in pts], _shaded(shades[si], mult)))
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


def _clean_elems(series, grade, xfilter=None):
    out = []
    for e in cw.clean_body_spec(series, grade):
        rot = e[2] if len(e) > 2 else None
        box = e[0]
        if xfilter and not (xfilter[0] <= (box[0] + box[3]) / 2 <= xfilter[1]):
            continue
        out.append((box, e[1], rot))
    return out


def _ref_elems(xfilter=None):
    pal = _ref_pal()
    out = []
    for box, region in rb.BODY_SPEC:
        if xfilter and not (xfilter[0] <= (box[0] + box[3]) / 2 <= xfilter[1]):
            continue
        out.append((box, region, None))
    return out, pal


def _ref_pal():
    return {"ridge": (rb.RIDGE_HI, rb.RIDGE_MID, rb.RIDGE_DIM),
            "blade": (rb.BLADE_HI, rb.BLADE_MID, rb.BLADE_DIM),
            "gold": (rb.GOLD_HI, rb.GOLD_MID, rb.GOLD_DIM),
            "navy": (rb.NAVY_HI, rb.NAVY_MID, rb.NAVY_DIM),
            "gem": (rb.GEM_HI, rb.GEM_MID, rb.GEM_DIM)}


def _fit(im, target_h):
    r = target_h / im.height
    return im.resize((max(1, int(im.width * r)), target_h), Image.NEAREST)


def _bg(im, bg=BG):
    b = Image.new("RGBA", im.size, bg)
    b.alpha_composite(im)
    return b


def montage():
    top_h, guard_h = 460, 300
    f_hdr, f_sub, f_note = _font(23), _font(19), _font(18)

    # ── 상단: 레퍼런스 | ref_blade | 검 | 도 (전신 GUI 3/4, -20°, MC 음영) ──
    vid = _fit(Image.open(os.path.join(REF, "vid_zoom1.png")).convert("RGBA"), top_h)
    ref_full = _fit(render(*_ref_elems(), -20), top_h) if False else _fit(
        render(_ref_elems()[0], _ref_pal(), -20), top_h)
    sw_full = _fit(render(_clean_elems("sword", "sinbyeong"), cw.clean_palette("sword", "sinbyeong"), -20), top_h)
    dao_full = _fit(render(_clean_elems("dao", "sinbyeong"), cw.clean_palette("dao", "sinbyeong"), -20), top_h)

    # ── 하단: 코등이 확대 (ref_blade | 검 | 도) — x∈[-1,6] 잘라 크게 ──
    gx = (-1.5, 6.0)
    gref = _fit(render(_ref_elems(gx)[0], _ref_pal(), -18), guard_h)
    gsw = _fit(render(_clean_elems("sword", "sinbyeong", gx), cw.clean_palette("sword", "sinbyeong"), -18), guard_h)
    gdao = _fit(render(_clean_elems("dao", "sinbyeong", gx), cw.clean_palette("dao", "sinbyeong"), -18), guard_h)

    sw_n = len(cw.clean_body_spec("sword", "sinbyeong"))
    dao_n = len(cw.clean_body_spec("dao", "sinbyeong"))

    top_cols = [("레퍼런스 (vid_zoom1)", vid, (150, 210, 225)),
                ("청뢰검 ref_blade (MC음영)", ref_full, (150, 225, 210)),
                (f"검 신병 — 우리 수정 ({sw_n}원소)", sw_full, (150, 235, 220)),
                (f"도 신병 — 우리 수정 ({dao_n}원소)", dao_full, (240, 170, 155))]
    guard_cols = [("코등이 — ref_blade", gref, (150, 225, 210)),
                  ("코등이 — 검 신병", gsw, (150, 235, 220)),
                  ("코등이 — 도 원반", gdao, (240, 170, 155))]

    gap = 26
    x = gap
    txs = []
    for _t, im, _c in top_cols:
        txs.append(x)
        x += im.width + gap
    topW = x
    x = gap
    gxs = []
    for _t, im, _c in guard_cols:
        gxs.append(x)
        x += im.width + gap
    W = max(topW, x)
    top_y, guard_y = 54, 54 + top_h + 58
    H = guard_y + guard_h + 96

    cv = Image.new("RGBA", (W, H), (12, 15, 20, 255))
    d = ImageDraw.Draw(cv)
    d.text((gap, 14), "V2-W 25b — 깨끗한 재건축 디테일 정교화 (색+정교함 함께 · ★MC 면 음영 적용 렌더)",
           font=f_hdr, fill=(185, 222, 232))
    for (title, im, c), xx in zip(top_cols, txs):
        d.text((xx, top_y - 24), title, font=f_sub, fill=c)
        cv.alpha_composite(_bg(im), (xx, top_y))
    d.text((gap, guard_y - 28), "── 코등이 확대 (곡선 팔 · finial · 중앙 보석 · 층진 금 명암) ──",
           font=f_sub, fill=(200, 210, 220))
    for (title, im, c), xx in zip(guard_cols, gxs):
        d.text((xx, guard_y - 4), title, font=f_note, fill=c)
        cv.alpha_composite(_bg(im), (xx, guard_y + 18))
    d.text((gap, H - 66),
           "검 코등이: 상·하 곡선 팔(±22.5° 회전) + 뾰족 finial + 중앙 파란 보석(밝은 심 facet) + 층진 금(어두운 몸/밝은 앞 하이라이트) · "
           "검신: 몸(mid)|fuller 홈(dim)|능선(bright) 색층 · 도: 층진 금 원반 + 진홍 혈조/보석. MC 음영이 층을 읽어낸다.",
           font=f_note, fill=(175, 190, 200))
    cv.convert("RGB").save(OUT)
    print("wrote", os.path.relpath(OUT, ROOT), cv.size, f"| 검 {sw_n} · 도 {dao_n} 원소")


if __name__ == "__main__":
    montage()
