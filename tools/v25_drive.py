#!/usr/bin/env python3
"""V2-W 25차 — 깨끗한 재건축 파일럿 몽타주. 스크래치(커밋 대상 아님).

  scratch/v25_clean_pilot.png
    [ 청뢰검(ref_blade) | 검 신병: 현행 복셀판 → 깨끗한 재건축 | 도 신병: 복셀판 → 깨끗한 ]
    각 칸 = GUI 3/4 (-20°) + 회전 시점(-48°, 두께·능선 노출).

렌더러는 파이썬 근사(인게임 클라이언트가 아님) — 색·비례·부위 형태·복잡도(원소 수) 대조가 목적:
  · 깨끗한 = clean_weapons.clean_body_spec + 계열 색 램프 (손 배치 cuboid).
  · 복셀판 = scratch/v2w_weapons_3d/voxel_before/*.model.json (내 변경 前 현행) + 페인트 시트 색 표집.
  · 청뢰검 = refblade_forge.BODY_SPEC (템플릿).
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
VOXEL_DIR = os.path.join(ROOT, "scratch", "v2w_weapons_3d", "voxel_before")
PACK = os.path.join(ROOT, "resourcepack", "assets", "honcheon", "textures", "item")
OUT = os.path.join(ROOT, "scratch", "v25_clean_pilot.png")
S = 14
BG = (16, 20, 26, 255)


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


def _shade(base, hi, zc):
    t = max(0.0, min(1.0, (zc + 1.6) / 3.2))
    return tuple(round(base[i] + (hi[i] - base[i]) * t) for i in range(3)) + (255,)


def _draw(prims, pad=6):
    """prims = [(fill_mid, fill_hi, projbox)…] → RGBA 이미지 (뒤→앞 depth 정렬)."""
    allp = [p[2] for p in prims]
    minx = min(p[0] for p in allp) - pad
    miny = min(p[1] for p in allp) - pad
    maxx = max(p[2] for p in allp) + pad
    maxy = max(p[3] for p in allp) + pad
    im = Image.new("RGBA", (max(1, int(maxx - minx)), max(1, int(maxy - miny))), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for mid, hi, (x0, y0, x1, y1, zc) in sorted(prims, key=lambda p: p[2][4]):
        col = _shade(mid, hi, zc)
        d.rectangle([x0 - minx, y0 - miny, x1 - minx, y1 - miny],
                    fill=col, outline=(18, 22, 28, 255))
    return im


def render_clean(series, grade, roll_deg):
    pal = cw.clean_palette(series, grade)
    ca, sa = math.cos(math.radians(roll_deg)), math.sin(math.radians(roll_deg))
    prims = []
    for box, region in cw.clean_body_spec(series, grade):
        hi, mid, _dim = pal[region]
        prims.append((mid, hi, _project(box, ca, sa)))
    return _draw(prims)


def render_ref(roll_deg):
    ca, sa = math.cos(math.radians(roll_deg)), math.sin(math.radians(roll_deg))
    reg_mid = {"ridge": rb.RIDGE_MID, "blade": rb.BLADE_MID, "gold": rb.GOLD_MID,
               "navy": rb.NAVY_MID, "gem": rb.GEM_MID}
    reg_hi = {"ridge": rb.RIDGE_HI, "blade": rb.BLADE_HI, "gold": rb.GOLD_HI,
              "navy": rb.NAVY_HI, "gem": rb.GEM_HI}
    prims = [(reg_mid[r], reg_hi[r], _project(box, ca, sa)) for box, r in rb.BODY_SPEC]
    return _draw(prims)


def _sample(img, uv):
    """페인트 시트 uv(0..16) 중심 픽셀 색. 프레임 0(상단 128행)만 표집 (애니 스트립 무시)."""
    w = img.width
    u = (uv[0] + uv[2]) * 0.5 / 16.0 * w
    v = (uv[1] + uv[3]) * 0.5 / 16.0 * w        # 정사각 프레임 — v 도 w 로 (프레임 0)
    px = img.getpixel((min(w - 1, max(0, int(u))), min(w - 1, max(0, int(v)))))
    if len(px) == 4 and px[3] < 8:
        return None
    return px[:3]


def render_voxel(series, grade, roll_deg):
    model = json.load(open(os.path.join(VOXEL_DIR, f"{series}_{grade}.model.json")))
    sheet = Image.open(os.path.join(PACK, "weapon", "paint", f"{series}_{grade}.png")).convert("RGBA")
    ca, sa = math.cos(math.radians(roll_deg)), math.sin(math.radians(roll_deg))
    prims = []
    for el in model["elements"]:
        box = el["from"] + el["to"]
        faces = el.get("faces", {})
        col = None
        for fn in ("north", "south", "up", "east"):     # broad face 우선 (계열색)
            f = faces.get(fn)
            if f and "uv" in f:
                col = _sample(sheet, f["uv"])
                if col:
                    break
        if col is None:
            col = (120, 128, 138)
        hi = tuple(min(255, int(c * 1.28)) for c in col)
        prims.append((col, hi, _project(box, ca, sa)))
    return _draw(prims), len(model["elements"])


def _fit(im, target_h):
    r = target_h / im.height
    return im.resize((max(1, int(im.width * r)), target_h), Image.NEAREST)


def _bg(im, bg=BG):
    b = Image.new("RGBA", im.size, bg)
    b.alpha_composite(im)
    return b


def montage():
    big_h, small_h = 430, 150
    f_hdr = _font(23)
    f_sub = _font(19)
    f_note = _font(19)

    # 각 칸: 큰 GUI 3/4 + 아래 작은 회전 시점
    def panel(big, small):
        big = _fit(big, big_h)
        small = _fit(small, small_h)
        w = max(big.width, small.width)
        cv = Image.new("RGBA", (w, big_h + small_h + 10), (0, 0, 0, 0))
        cv.alpha_composite(_bg(big), ((w - big.width) // 2, 0))
        cv.alpha_composite(_bg(small), ((w - small.width) // 2, big_h + 10))
        return cv

    ref = panel(render_ref(-20), render_ref(-48))
    sw_vox_im, sw_n = render_voxel("sword", "sinbyeong", -20)
    sw_vox_s, _ = render_voxel("sword", "sinbyeong", -48)
    sw_vox = panel(sw_vox_im, sw_vox_s)
    sw_cln = panel(render_clean("sword", "sinbyeong", -20), render_clean("sword", "sinbyeong", -48))
    dao_vox_im, dao_n = render_voxel("dao", "sinbyeong", -20)
    dao_vox_s, _ = render_voxel("dao", "sinbyeong", -48)
    dao_vox = panel(dao_vox_im, dao_vox_s)
    dao_cln = panel(render_clean("dao", "sinbyeong", -20), render_clean("dao", "sinbyeong", -48))
    sw_c = len(cw.clean_body_spec("sword", "sinbyeong"))
    dao_c = len(cw.clean_body_spec("dao", "sinbyeong"))

    cols = [("청뢰검 (ref_blade · 템플릿)", ref, (150, 210, 225)),
            (f"검 신병 — 현행 복셀판 ({sw_n} 원소)", sw_vox, (210, 180, 170)),
            (f"검 신병 — 깨끗한 재건축 ({sw_c} 원소)", sw_cln, (150, 235, 220)),
            (f"도 신병 — 현행 복셀판 ({dao_n} 원소)", dao_vox, (210, 180, 170)),
            (f"도 신병 — 깨끗한 재건축 ({dao_c} 원소)", dao_cln, (240, 160, 150))]

    gap = 26
    top = 52
    xs = []
    x = gap
    for _t, im, _c in cols:
        xs.append(x)
        x += im.width + gap
    W = x
    H = top + max(im.height for _t, im, _c in cols) + 118

    cv = Image.new("RGBA", (W, H), (12, 15, 20, 255))
    d = ImageDraw.Draw(cv)
    d.text((gap, 14), "V2-W 25차 — 깨끗한 재건축 파일럿 (청뢰검 손 배치 방식을 검·도 신병에 녹임)",
           font=f_hdr, fill=(180, 220, 230))
    for (title, im, c), xx in zip(cols, xs):
        d.text((xx, top - 24), title, font=f_sub, fill=c)
        cv.alpha_composite(im, (xx, top))
    d.text((gap, H - 92),
           f"깨끗한 방식: 부위별 소수 cuboid 손 배치(_voxelize 미경유) · 검=곧은 롱소드+금 십자 코등이(파란 보석)·옥/청록 능선 · "
           f"도=한날 곡도+원반 코등이·진홍 악센트(감김·혈조·수실) · 정지 병기(번개 제거—파티클 트랙 소관).",
           font=f_note, fill=(180, 190, 200))
    d.text((gap, H - 60),
           f"복잡도 급감: 검 {sw_n}→{sw_c} 원소 · 도 {dao_n}→{dao_c} 원소. 위 큰 그림=GUI 3/4 · 아래 작은 그림=회전(두께·능선·볼록 노출). "
           f"파일럿 배선=스위치(weapons._R25) · 나머지 45+12 무기·아이콘 무접촉.",
           font=f_note, fill=(160, 200, 190))
    cv.convert("RGB").save(OUT)
    print("wrote", os.path.relpath(OUT, ROOT), cv.size,
          f"| 검 {sw_n}→{sw_c} · 도 {dao_n}→{dao_c} 원소")


if __name__ == "__main__":
    montage()
