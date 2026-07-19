"""V2-W 21차 — 만화(애니)풍 리스타일 + 부위별 입체 기하 토대 + 기(氣) 복셀 껍질 흐름.
스크래치 몽타주 3종 (팩 무접촉 — _R21/_R20 토글만 · git/서버 금지):
  v21_manga_pilot.png  — [20차 표면 스윕 | 21차 만화 셀 + 기 껍질] GUI 대형 대조
  v21_qi_frames.png    — 기 흐름 프레임 위상 (기가 자루→날끝으로 흐른다)
  v21_volume_proof.png — 부위별 단면 컷 (7단 볼록 렌즈 날 · 봉 자루 · 두꺼운 코등이)
"""
import os
import sys
import zlib
import math
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import respack.weapons as w  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "scratch", "v2w_weapons_3d")
os.makedirs(OUT, exist_ok=True)
SCALE = 5
BG = (26, 24, 30, 255)


def _font(sz):
    for p in ("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, sz)
            except Exception:
                pass
    return ImageFont.load_default()


def _salt(key):
    return zlib.crc32(key.encode()) & 0x7F


def spec(series, grade, mode, phase):
    """mode: '20'=표면 스윕(R20) · '21'=만화+기껍질(R21) · series 'hwasan'=명병."""
    if series == "hwasan":
        salt = _salt("myeong/hwasan")
        if mode == "21":
            w._R21, w._R20, w._R21_ALLOW[0], w._R21_PHASE = True, False, True, phase
        else:
            w._R21, w._R20, w._R21_ALLOW[0], w._R20_PHASE = False, True, False, phase
        box, sh, ex = w._myeong_spec("hwasan", salt)
        return box, sh, salt
    salt = _salt(f"{series}_{grade}")
    r, b, t, m = w._GRADE_FORM[grade]
    if mode == "21":
        w._R21, w._R20, w._R21_ALLOW[0], w._R21_PHASE = True, False, True, phase
    else:
        w._R21, w._R20, w._R21_ALLOW[0], w._R20_PHASE = False, True, False, phase
    fn = w._spec_sword if series == "sword" else w._spec_dao
    box, sh, ex = fn(salt, grade, r, b, t, m)
    return box, sh, salt


def front_img(series, grade, mode, phase=0.0):
    box, sh, salt = spec(series, grade, mode, phase)
    rows, k, dep, parts, qi = w._compose(sh, box, salt, grade)
    n = len(rows)
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    px = im.load()
    for y in range(w._CANVAS_Y0, n):
        for x in range(n):
            if rows[y][x][3] > 8:
                px[x, y] = tuple(rows[y][x])
    im = im.rotate(-90, expand=True)
    return im.resize((im.width * SCALE, im.height * SCALE), Image.NEAREST)


def crop(im, pad=6):
    bb = im.getbbox()
    if not bb:
        return im
    x0, y0, x1, y1 = bb
    return im.crop((max(0, x0 - pad), max(0, y0 - pad),
                    min(im.width, x1 + pad), min(im.height, y1 + pad)))


def on_bg(im):
    bg = Image.new("RGBA", im.size, BG)
    bg.alpha_composite(im)
    return bg


# ─────────────────────────────────────────────────────────────── 몽타주 1 — 만화 대조
def montage_manga():
    f_title, f_lab, f_col, f_note = _font(19), _font(13), _font(15), _font(11)
    rows_spec = [
        ("검 범철 (수수 셀 · 기 없음)", "sword", "beomcheol"),
        ("검 신병 (화려 청백 셀 · 기 2가닥+입자)", "sword", "sinbyeong"),
        ("검 마병 (검붉은 셀 · 검붉은 기)", "sword", "mabyeong"),
        ("도 신병 (넓은 배 셀 · 호 따라 기 흐름)", "dao", "sinbyeong"),
        ("명병 화산 (문파색 매화홍 셀)", "hwasan", "sinbyeong"),
    ]
    imgs = []
    for lab, s, g in rows_spec:
        a = on_bg(crop(front_img(s, g, "20")))
        b = on_bg(crop(front_img(s, g, "21", phase=0.25)))
        imgs.append((lab, a, b))
    cw = max(max(a.width, b.width) for _, a, b in imgs) + 16
    ch = max(max(a.height, b.height) for _, a, b in imgs) + 12
    W = 360 + cw * 2
    H = 96 + ch * len(imgs) + 20
    canvas = Image.new("RGBA", (W, H), (18, 16, 22, 255))
    d = ImageDraw.Draw(canvas)
    d.text((24, 16), "V2-W 21차 — 만화(애니)풍 셀 + 부위별 입체 기하 + 기(氣) 복셀 껍질 흐름",
           font=f_title, fill=(236, 232, 240))
    d.text((24, 44), "그림: 사실적 3단 사면 → 또렷한 2~3톤 셀 + 강한 외곽선 + 청백 하이라이트 스팟 · "
                     "일렁임: 표면 스윕 → 무기 감싸는 기 복셀 껍질(리본+입자)이 흐른다",
           font=f_note, fill=(150, 200, 170))
    d.text((360, 70), "20차 (전통 · 표면 스펙큘러 스윕)", font=f_col, fill=(210, 200, 170))
    d.text((360 + cw, 70), "21차 (만화 셀 + 기 껍질 흐름)", font=f_col, fill=(170, 220, 245))
    y = 96
    for lab, a, b in imgs:
        d.text((16, y + ch // 2 - 8), lab, font=f_lab, fill=(220, 210, 180))
        canvas.alpha_composite(a, (360 + (cw - a.width) // 2, y + (ch - a.height) // 2))
        canvas.alpha_composite(b, (360 + cw + (cw - b.width) // 2, y + (ch - b.height) // 2))
        y += ch
    canvas.convert("RGB").save(os.path.join(OUT, "v21_manga_pilot.png"))
    print("wrote v21_manga_pilot.png", canvas.size)


# ─────────────────────────────────────────────────────────────── 몽타주 2 — 기 흐름 프레임
def montage_qi_frames():
    f_title, f_lab, f_note = _font(19), _font(13), _font(11)
    nfr = 6
    specs = [
        ("검 신병 (기 세로 흐름 · 자루→날끝)", "sword", "sinbyeong"),
        ("도 신병 (기 호 따라 흐름)", "dao", "sinbyeong"),
        ("명병 화산 (매화홍 기 흐름)", "hwasan", "sinbyeong"),
    ]
    imgs = [[on_bg(crop(front_img(s, g, "21", phase=fr / nfr))) for fr in range(nfr)]
            for _, s, g in specs]
    cw = max(im.width for row in imgs for im in row) + 12
    ch = max(im.height for row in imgs for im in row) + 10
    W = 300 + cw * nfr
    H = 74 + ch * len(specs) + 20
    canvas = Image.new("RGBA", (W, H), (18, 16, 22, 255))
    d = ImageDraw.Draw(canvas)
    d.text((24, 16), "V2-W 21차 — 기(氣) 복셀 껍질 흐름 (프레임 위상 · 텍스처 애니)",
           font=f_title, fill=(236, 232, 240))
    d.text((24, 44), "복셀 위치는 불변(정지 기하) · 밝은 마디만 프레임마다 이동 → 기가 자루→날끝으로 "
                     "흐르는 착시 (좌→우 = 프레임 위상 · 블룸 핫코어 발광)",
           font=f_note, fill=(150, 200, 170))
    y = 74
    for (label, _, _), row in zip(specs, imgs):
        d.text((24, y + ch // 2 - 20), label, font=f_lab, fill=(220, 210, 180))
        for i, im in enumerate(row):
            x = 280 + i * cw
            canvas.alpha_composite(im, (x + (cw - 12 - im.width) // 2, y + (ch - im.height) // 2))
            d.text((x + 4, y + ch - 16), f"f{i}", font=f_note, fill=(150, 150, 160))
        y += ch
    canvas.convert("RGB").save(os.path.join(OUT, "v21_qi_frames.png"))
    print("wrote v21_qi_frames.png", canvas.size)


# ─────────────────────────────────────────────────────────────── 몽타주 3 — 단면 증명
def voxels(series, grade, mode):
    box, sh, salt = spec(series, grade, mode, 0.0)
    rows, k, dep, parts, qi = w._compose(sh, box, salt, grade)
    elems, tags, dep2 = w._voxelize(rows, dep, k, box, _return_tags=True)
    return elems


def cross_section(elems, x0, csc=26, title=""):
    """x=x0 슬랩을 지나는 원소들의 (y,z) 단면 사각을 그린다 — 부위 단면(볼록 렌즈·봉·코등이)."""
    sel = [e for e in elems if e["from"][0] - 0.01 <= x0 <= e["to"][0] + 0.01]
    ys = [c for e in sel for c in (e["from"][1], e["to"][1])] or [6, 10]
    zs = [c for e in sel for c in (e["from"][2], e["to"][2])] or [6, 10]
    y0, y1 = min(ys) - 1, max(ys) + 1
    z0, z1 = min(zs) - 1, max(zs) + 1
    W = int((y1 - y0) * csc) + 20
    H = int((z1 - z0) * csc) + 40
    im = Image.new("RGBA", (max(W, 120), H), (22, 20, 26, 255))
    d = ImageDraw.Draw(im)
    for e in sel:
        fy, ty = e["from"][1], e["to"][1]
        fz, tz = e["from"][2], e["to"][2]
        rot = "rotation" in e
        px0 = 10 + (fy - y0) * csc
        px1 = 10 + (ty - y0) * csc
        pz0 = 20 + (z1 - tz) * csc
        pz1 = 20 + (z1 - fz) * csc
        col = (110, 200, 235) if not rot else (240, 190, 120)
        d.rectangle([px0, pz0, px1 - 1, pz1 - 1], fill=col, outline=(20, 18, 24))
    d.text((10, H - 16), title, font=_font(11), fill=(200, 200, 210))
    d.text((10, 4), f"x={x0:.1f}", font=_font(10), fill=(150, 150, 160))
    return im


def montage_volume():
    f_title, f_note, f_col = _font(19), _font(11), _font(13)
    rows_spec = [
        ("검 신병", "sword", "sinbyeong", [("날 (7단 볼록 렌즈)", 15.0), ("자루 (봉)", 2.0), ("코등이 (별도·두껍다)", 5.6)]),
        ("도 신병", "dao", "sinbyeong", [("날 (볼록 렌즈)", 14.0), ("자루 (봉)", 3.0), ("코등이 (원반·두껍다)", 6.0)]),
    ]
    blocks = []
    for lab, s, g, cuts in rows_spec:
        el21 = voxels(s, g, "21")
        row = [cross_section(el21, xx, title=cl) for cl, xx in cuts]
        blocks.append((lab, row))
    pad = 24
    colw = max(im.width for _, row in blocks for im in row) + 20
    rowh = max(im.height for _, row in blocks for im in row) + 40
    ncol = max(len(row) for _, row in blocks)
    W = 40 + colw * ncol
    H = 90 + rowh * len(blocks)
    canvas = Image.new("RGBA", (W, H), (16, 14, 20, 255))
    d = ImageDraw.Draw(canvas)
    d.text((24, 16), "V2-W 21차 — 부위별 입체 기하 단면 증명 (날 길이축 수직 y-z 컷)",
           font=f_title, fill=(236, 232, 240))
    d.text((24, 44), "파랑=본체 단면 (능선 두껍고 인선 얇은 7단 볼록 렌즈 · 폭≈깊이 봉 자루 · 날보다 두꺼운 코등이) "
                     "· 주황=회전 부품(±22.5/45° 날개 코등이). '두께만 늘린 균일 슬래브'가 아님을 눈으로 증명",
           font=f_note, fill=(150, 200, 170))
    y = 84
    for lab, row in blocks:
        d.text((24, y + rowh // 2), lab, font=f_col, fill=(220, 210, 180))
        for i, im in enumerate(row):
            x = 180 + i * colw
            canvas.alpha_composite(im, (x, y))
        y += rowh
    canvas.convert("RGB").save(os.path.join(OUT, "v21_volume_proof.png"))
    print("wrote v21_volume_proof.png", canvas.size)


if __name__ == "__main__":
    montage_manga()
    montage_qi_frames()
    montage_volume()
    w._R20, w._R21, w._R21_ALLOW[0] = True, False, False
    print("OUT:", os.path.abspath(OUT))
