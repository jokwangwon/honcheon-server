#!/usr/bin/env python3
"""V2-W 24차 — 레퍼런스 검 **영상 프레임 정합**(ref_blade) 몽타주. 스크래치(커밋 대상 아님).

  scratch/v24_refblade_match.png
    [ 영상 프레임(vid_zoom1·vid_grid 셀) | 수정 모델 GUI 3/4 | 회전 시점 3 (두께·능선·보석) ]

렌더러는 refblade_forge 의 기하(BODY_SPEC)를 읽어 자체 판정용 그림을 만든다 (인게임 클라이언트가
아니라 파이썬 근사 — 색·비례·부위 형태 대조가 목적). 번개 스트립은 24차에 제거됐다.
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import refblade_forge as rb  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
REF = os.path.join(HERE, "..", "scratch", "ref_lightning")
OUT = os.path.join(HERE, "..", "scratch", "v24_refblade_match.png")
S = 15
BG = (16, 20, 26, 255)

REGION_MID = {"ridge": rb.RIDGE_MID, "blade": rb.BLADE_MID, "gold": rb.GOLD_MID,
              "navy": rb.NAVY_MID, "gem": rb.GEM_MID}
REGION_HI = {"ridge": rb.RIDGE_HI, "blade": rb.BLADE_HI, "gold": rb.GOLD_HI,
             "navy": rb.NAVY_HI, "gem": rb.GEM_HI}


def _font(sz):
    for p in ("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        if os.path.exists(p):
            return ImageFont.truetype(p, sz)
    return ImageFont.load_default()


def _project(box, ca, sa):
    """8 코너 → (minx, miny, maxx, maxy, depth). 길이축(X)=화면 세로, 폭/두께 roll about X."""
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


def render(roll_deg):
    ca, sa = math.cos(math.radians(roll_deg)), math.sin(math.radians(roll_deg))
    prims = [(region, _project(box, ca, sa)) for box, region in rb.BODY_SPEC]
    allp = [p[1] for p in prims]
    minx = min(p[0] for p in allp) - 6
    miny = min(p[1] for p in allp) - 6
    maxx = max(p[2] for p in allp) + 6
    maxy = max(p[3] for p in allp) + 6
    im = Image.new("RGBA", (int(maxx - minx), int(maxy - miny)), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    prims.sort(key=lambda p: p[1][4])                # 뒤에서 앞으로 (depth) — 돌출 능선·보석이 위로
    for region, (x0, y0, x1, y1, zc) in prims:
        px0, py0, px1, py1 = x0 - minx, y0 - miny, x1 - minx, y1 - miny
        base, hi = REGION_MID[region], REGION_HI[region]
        t = max(0.0, min(1.0, (zc + 1.6) / 3.2))     # 앞면(가까운 z) 밝게
        col = tuple(round(base[i] + (hi[i] - base[i]) * t) for i in range(3)) + (255,)
        d.rectangle([px0, py0, px1, py1], fill=col, outline=(18, 22, 28, 255))
    return im


def _fit(im, target_h):
    r = target_h / im.height
    return im.resize((max(1, int(im.width * r)), target_h), Image.NEAREST)


def _bg(im, bg=BG):
    b = Image.new("RGBA", im.size, bg)
    b.alpha_composite(im)
    return b


def montage():
    H = 720
    ref_h = H - 44
    # 영상 프레임: vid_zoom1(확대 검) + vid_grid 상단 좌 셀(전체 검)
    z1 = _fit(Image.open(os.path.join(REF, "vid_zoom1.png")).convert("RGBA"), ref_h)
    grid = Image.open(os.path.join(REF, "vid_grid.png")).convert("RGBA")
    cell = grid.crop((0, 0, grid.width // 4, int(grid.height * 0.42)))   # 상단 좌 검 셀
    cell = _fit(cell, ref_h)

    gui = _fit(render(-20), ref_h)                   # 수정 모델 GUI 3/4
    roll_h = (ref_h - 24) // 3
    rolls = [_fit(render(a), roll_h) for a in (-45, 0, 55)]   # 회전 — 두께·능선·보석 노출

    f_c = _font(21)
    gap = 22
    x = gap
    cols = [("ref", z1, x)]
    x += z1.width + gap
    cols.append(("ref", cell, x)); x += cell.width + gap + 26
    gui_x = x
    cols.append(("gui", gui, x)); x += gui.width + gap + 30
    roll_x = x
    x += max(r.width for r in rolls) + gap
    W = x

    cv = Image.new("RGBA", (W, H + 96), (12, 15, 20, 255))
    d = ImageDraw.Draw(cv)
    d.text((gap, 12), "영상 프레임 (vid_zoom1 · vid_grid)", font=f_c, fill=(150, 210, 225))
    for _, im, xx in cols[:2]:
        cv.alpha_composite(_bg(im), (xx, 46))
    d.text((gui_x, 12), "수정 모델 GUI 3/4", font=f_c, fill=(150, 225, 210))
    cv.alpha_composite(_bg(gui), (gui_x, 46))
    d.text((roll_x, 12), "회전 시점 (두께·능선·보석)", font=f_c, fill=(150, 225, 210))
    for i, r in enumerate(rolls):
        cv.alpha_composite(_bg(r), (roll_x, 46 + i * (roll_h + 8)))
    d.text((gap, H + 54),
           "수정: 코등이=금 장식 십자(양끝 뾰족 돌기+상향 곡선 팔)+중앙 파란 보석 큐브 · "
           "검신=청록 몸+가운데 흰-청록 능선(볼록)·롱소드 폭 · 자루=남색+금 띠2+금 스터드+금 물미 · "
           "번개 스프라이트 제거(정지 모델만)",
           font=f_c, fill=(180, 190, 200))
    cv.convert("RGB").save(OUT)
    print("wrote", os.path.relpath(OUT, os.path.join(HERE, "..")), cv.size)


if __name__ == "__main__":
    montage()
