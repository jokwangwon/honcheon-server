"""V2-W 20차 — 전통 병기 + 표면 광택 일렁임 (스펙큘러 스윕) 몽타주 (스크래치 · 팩 무접촉).
계열/등급별 스윕 서명 + 프레임 위상 흐름 (일렁임)을 나란히 굽는다."""
import os
import sys
import zlib
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import respack.weapons as w  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "scratch", "v2w_weapons_3d")
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


def compose(series, grade, phase):
    w._R20 = True
    w._R20_PHASE = phase
    r, b, t, m = w._GRADE_FORM[grade]
    box, sh, ex = w._spec_sword(zlib.crc32(f"{series}_{grade}".encode()) & 0x7f, grade, r, b, t, m) \
        if series == "sword" else \
        w._spec_dao(zlib.crc32(f"{series}_{grade}".encode()) & 0x7f, grade, r, b, t, m)
    rows, k, dep, parts, qi = w._compose(sh, box, zlib.crc32(f"{series}_{grade}".encode()) & 0x7f, grade)
    return rows


def sheet_img(rows):
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


def montage():
    f_title = _font(19)
    f_lab = _font(13)
    f_note = _font(11)
    nfr = 6
    # 행 = (라벨, series, grade) · 열 = 프레임 위상 (일렁임 흐름)
    rows_spec = [
        ("검 범철 (무광 — 일렁임 미미)", "sword", "beomcheol"),
        ("검 신병 (옥빛 광택 · 인선 세로 흐름)", "sword", "sinbyeong"),
        ("검 마병 (검붉은 위압 광택)", "sword", "mabyeong"),
        ("도 신병 (넓은 배 사행 스윕)", "dao", "sinbyeong"),
    ]
    imgs = [[on_bg(crop(sheet_img(compose(s, g, fr / nfr)))) for fr in range(nfr)]
            for _, s, g in rows_spec]
    cw = max(im.width for row in imgs for im in row) + 12
    ch = max(im.height for row in imgs for im in row) + 10
    W = 320 + cw * nfr
    H = 78 + ch * len(rows_spec) + 30
    canvas = Image.new("RGBA", (W, H), (18, 16, 22, 255))
    d = ImageDraw.Draw(canvas)
    d.text((24, 18), "V2-W 20차 — 전통 병기 + 표면 광택 일렁임 (스펙큘러 스윕 · 애니 프레임)",
           font=f_title, fill=(236, 232, 240))
    d.text((24, 46), "마법 오라(헤일로·기환 고리·기맥 그물·부유 광점) 제거 · 광택 마루가 표면을 "
                     "흐른다 (등급=세기 · 계열=방향) — 좌→우 = 프레임 위상 진행 (몸 정지·광택만 이동)",
           font=f_note, fill=(150, 200, 170))
    y = 78
    for (label, _, _), row in zip(rows_spec, imgs):
        d.text((24, y + ch // 2 - 20), label, font=f_lab, fill=(220, 210, 180))
        for i, im in enumerate(row):
            x = 300 + i * cw
            canvas.alpha_composite(im, (x + (cw - 12 - im.width) // 2, y + (ch - im.height) // 2))
            d.text((x + 4, y + ch - 16), f"f{i}", font=f_note, fill=(150, 150, 160))
        y += ch
    canvas.convert("RGB").save(os.path.join(OUT, "v20_tradition_shimmer.png"))
    print("wrote v20_tradition_shimmer.png", canvas.size)


if __name__ == "__main__":
    montage()
    w._R20 = False
    print("OUT:", os.path.abspath(OUT))
