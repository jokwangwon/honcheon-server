"""V2-W 21차-c — 날 위 전격(번개) 크래클 반복 애니. 스크래치 몽타주 (팩 무접촉).
  v21c_lightning.png — 검 신병·도 마병 × [21b 기 포인트 | 21c 전격 크래클 6프레임]
(사용자: 참조 영상 = 무기에 번개가 치지직 반복 재생 — 우리 애니 텍스처 시스템으로 구현)
"""
import os, sys, zlib
from PIL import Image, ImageDraw, ImageFont
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import respack.weapons as w  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "scratch", "v2w_weapons_3d")
os.makedirs(OUT, exist_ok=True)
SCALE = 5
BG = (20, 18, 26, 255)


def _font(sz):
    for p in ("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, sz)
            except Exception:
                pass
    return ImageFont.load_default()


def front_img(series, grade, fx, phase):
    """만화 셀 + (fx) 전격/기 · 특정 프레임 위상의 앞면 GUI 렌더."""
    salt = zlib.crc32(f"{series}_{grade}".encode()) & 0x7f
    w._R21, w._R20, w._R21_ALLOW[0], w._R21_FX, w._R21_PHASE = True, False, True, fx, phase
    r, b, t, m = w._GRADE_FORM[grade]
    fn = w._spec_sword if series == "sword" else w._spec_dao
    box, sh, ex = fn(salt, grade, r, b, t, m)
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
    return im.crop((max(0, x0 - pad), max(0, y0 - pad), min(im.width, x1 + pad), min(im.height, y1 + pad)))


def on_bg(im):
    b = Image.new("RGBA", im.size, BG)
    b.alpha_composite(im)
    return b


def montage():
    f_t, f_l, f_c, f_n = _font(19), _font(13), _font(14), _font(11)
    rows_spec = [("검 신병 (옥빛 뇌)", "sword", "sinbyeong"), ("도 마병 (검붉은 뇌전)", "dao", "mabyeong")]
    nfr = 6
    # 21c 프레임 위상 — 치지직이 프레임마다 튀는 걸 보이게 (F=20 중 다양한 위상 추출)
    phs = [i / nfr for i in range(nfr)]
    data = []
    for lab, s, g in rows_spec:
        qi = on_bg(crop(front_img(s, g, "qi", 0.25)))                       # 21b 기 포인트
        bolts = [on_bg(crop(front_img(s, g, "both", p))) for p in phs]      # 21c 전격 프레임
        data.append((lab, qi, bolts))
    cw = max(max(im.width for im in [qi] + bolts) for _, qi, bolts in data) + 12
    ch = max(max(im.height for im in [qi] + bolts) for _, qi, bolts in data) + 12
    W = 210 + cw * (1 + nfr) + 30
    H = 96 + ch * len(rows_spec) + 20
    cv = Image.new("RGBA", (W, H), (14, 12, 18, 255))
    d = ImageDraw.Draw(cv)
    d.text((22, 16), "V2-W 21차-c — 날 위 전격(번개) 크래클 반복 애니 (참조 영상: 무기에 번개 치지직)",
           font=f_t, fill=(236, 232, 245))
    d.text((22, 44), "전격 = 솔리드 날 표면의 색 오버레이(기하 불변) · 프레임마다 번개 가지가 튄다(점멸+경로 이동) "
                     "· 핫화이트 코어+악센트 글로우 · frametime 1틱=치지직 · 결정론(난수0)",
           font=f_n, fill=(150, 210, 235))
    d.text((210, 72), "21b 기 포인트", font=f_c, fill=(170, 220, 200))
    d.text((210 + cw + 30, 72), "21c 전격 크래클 (좌→우 = 애니 프레임 · 번개가 튄다)", font=f_c, fill=(190, 225, 250))
    y = 96
    for lab, qi, bolts in data:
        d.text((14, y + ch // 2 - 8), lab, font=f_l, fill=(220, 214, 190))
        cv.alpha_composite(qi, (210 + (cw - qi.width) // 2, y + (ch - qi.height) // 2))
        for i, im in enumerate(bolts):
            x = 210 + cw + 30 + i * cw
            cv.alpha_composite(im, (x + (cw - im.width) // 2, y + (ch - im.height) // 2))
            d.text((x + 4, y + ch - 14), f"f{i}", font=f_n, fill=(140, 140, 155))
        y += ch
    cv.convert("RGB").save(os.path.join(OUT, "v21c_lightning.png"))
    print("wrote v21c_lightning.png", cv.size)


if __name__ == "__main__":
    montage()
    w._R20, w._R21, w._R21_ALLOW[0], w._R21_FX, w._R21_PHASE = True, False, False, "both", None
    print("OUT:", os.path.abspath(OUT))
