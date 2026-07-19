"""V2-W 19차-c 오라 근거 정본 — 주석 렌더 드라이브 (스크래치 전용 · 팩 무접촉).
[현행 v19-b 부산물 | 정본+블룸 빛] 대조 + 요소 이름표·지시선 + 근거 한 줄 · 일렁임 프레임.
_R19 게이트를 스크래치에서만 True 로 걸어 2자루(검 신병·도 마병)를 굽는다."""
import os
import sys
import zlib
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import respack.weapons as w  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "scratch", "v2w_weapons_3d")
os.makedirs(OUT, exist_ok=True)
SCALE = 6
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


def compose(series, grade, salt, phase=None, bloom=True, meridian=True):
    w._R19 = True
    w._R19_BLOOM = bloom
    w._R19_MOTE_MERIDIAN = meridian
    w._R19_PHASE = phase
    spec = {"sword": w._spec_sword, "dao": w._spec_dao}[series]
    rings, blen, tassel, mab = w._GRADE_FORM[grade]
    box, sh, ex = spec(salt, grade, rings, blen, tassel, mab)
    rows, k, dep, parts, qi = w._compose(sh, box, salt, grade)
    return rows, k, box, sh


def sheet_img(rows):
    n = len(rows)
    # 시트 위쪽 예약 밴드(y<_CANVAS_Y0)는 복셀 측면 UV 스트립 — GUI 정면이 아니라 제외한다
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    px = im.load()
    for y in range(w._CANVAS_Y0, n):
        for x in range(n):
            if rows[y][x][3] > 8:
                px[x, y] = tuple(rows[y][x])
    # 무기는 +x 로 뻗는다 — GUI 정면처럼 세우려 -90° 회전
    im = im.rotate(-90, expand=True)
    return im.resize((im.width * SCALE, im.height * SCALE), Image.NEAREST)


def crop_content(im, pad=8):
    bbox = im.getbbox()
    if not bbox:
        return im
    x0, y0, x1, y1 = bbox
    x0 = max(0, x0 - pad); y0 = max(0, y0 - pad)
    x1 = min(im.width, x1 + pad); y1 = min(im.height, y1 + pad)
    return im.crop((x0, y0, x1, y1))


def on_bg(im):
    bg = Image.new("RGBA", im.size, BG)
    bg.alpha_composite(im)
    return bg


# ── 대조판: [현행 v19-b 부산물 | 정본+블룸 빛] × 검 신병 · 도 마병 ──────────────
def compare_panel():
    f_title = _font(20)
    f_lab = _font(13)
    f_note = _font(11)
    pilots = [("sword", "sinbyeong", 21, "검 신병 (옥)"),
              ("dao", "mabyeong", zlib.crc32(b"dao_mabyeong") & 0x7f, "도 마병 (진홍)")]
    cells = []
    for series, grade, salt, label in pilots:
        before = on_bg(crop_content(sheet_img(compose(series, grade, salt,
                                                       bloom=False, meridian=False)[0])))
        after = on_bg(crop_content(sheet_img(compose(series, grade, salt,
                                                     bloom=True, meridian=True)[0])))
        cells.append((label, before, after))
    cw = max(max(b.width, a.width) for _, b, a in cells) + 24
    ch = max(max(b.height, a.height) for _, b, a in cells) + 24
    W = 24 + cw * 2 + 24
    H = 90 + (ch + 46) * len(cells) + 30
    canvas = Image.new("RGBA", (W, H), (18, 16, 22, 255))
    d = ImageDraw.Draw(canvas)
    d.text((24, 20), "V2-W 19차-c — 오라 근거 정본화: 부산물 → 빛 (기의 발광)", font=f_title,
           fill=(236, 232, 240))
    d.text((24, 50), "왼쪽 = 현행 v19-b (플랫 램프·기환 축 산포 광점 = 부산물)   |   "
                     "오른쪽 = 정본+블룸 (핫 코어·falloff·기맥 경혈 광점 = 빛)", font=f_note,
           fill=(150, 200, 170))
    y = 90
    for label, before, after in cells:
        d.text((24, y), label, font=f_lab, fill=(220, 210, 180))
        for i, (im, cap) in enumerate(((before, "현행 v19-b (부산물)"),
                                       (after, "정본+블룸 (빛)"))):
            x = 24 + i * cw
            canvas.alpha_composite(im, (x + (cw - 24 - im.width) // 2, y + 22))
            d.text((x, y + 24 + ch), cap, font=f_note,
                   fill=(190, 120, 110) if i == 0 else (140, 210, 180))
        y += ch + 46
    canvas.convert("RGB").save(os.path.join(OUT, "v19c_aura_canon.png"))
    print("wrote v19c_aura_canon.png", canvas.size)


# ── 주석판: 검 신병 정본 오라에 요소 이름표·지시선 + 근거 한 줄 ─────────────────
def annotate_panel():
    f_title = _font(20)
    f_lab = _font(13)
    f_note = _font(11)
    rows, k, box, sh = compose("sword", "sinbyeong", 21, bloom=True, meridian=True)
    im = on_bg(crop_content(sheet_img(rows), pad=30))
    ox, oy = 20, 44
    W, H = im.width + 500, max(im.height + 70, 400)
    canvas = Image.new("RGBA", (W, H), (18, 16, 22, 255))
    canvas.alpha_composite(im, (ox, oy))
    d = ImageDraw.Draw(canvas)
    d.text((20, 12), "오라 요소별 근거 — 검 신병 (정본 §4)", font=f_title, fill=(236, 232, 240))
    # (이름, 근거 한 줄, 그림 안 지시 대상 im 상대비율 fx, fy)
    notes = [
        ("헤일로 (발광 윤곽)", "벼려진 기가 인선에 맺힌다 — 인선 쪽만·틈 1.4u 밖 (등엔 없다)", 0.14, 0.55),
        ("기환 고리 + 내단", "손→날 관문에서 기가 응축 회전 (코등이의 기 번역)", 0.5, 0.36),
        ("부유 광점 (경혈 잔광)", "기맥 경로 위 마디(氣穴)에서 떠오른다 — 방사 상수 아님", 0.54, 0.5),
        ("기맥 (내공 길)", "단전(자루)→관문→날끝 한 획 (R3) — 끊길 수 없다", 0.48, 0.64),
        ("블룸 (핫 코어·빛)", "흰-핫 심 → 악센트 후광 → 어둠 falloff — 광원이지 부산물 아님", 0.5, 0.9),
    ]
    lx, ly = ox + im.width + 26, 64
    for name, note, fx, fy in notes:
        tx, ty = ox + int(fx * im.width), oy + int(fy * im.height)
        d.line((lx - 6, ly + 7, tx, ty), fill=(120, 180, 150), width=1)
        d.ellipse((tx - 4, ty - 4, tx + 4, ty + 4), outline=(190, 235, 205), width=2)
        d.ellipse((lx - 9, ly + 4, lx - 3, ly + 10), fill=(150, 210, 180))
        d.text((lx + 4, ly), name, font=f_lab, fill=(150, 210, 180))
        d.text((lx + 16, ly + 18), note, font=f_note, fill=(200, 196, 205))
        ly += 58
    canvas.convert("RGB").save(os.path.join(OUT, "v19c_aura_annotated.png"))
    print("wrote v19c_aura_annotated.png", canvas.size)


# ── 일렁임 프레임: 위상 이동 8프레임 나란히 (정지 PNG지만 밝은 마디가 흐른다) ────
def frames_panel(nframes=8):
    f_title = _font(20)
    f_note = _font(11)
    cells = []
    for fr in range(nframes):
        phase = fr / nframes
        im = on_bg(crop_content(sheet_img(compose("sword", "sinbyeong", 21,
                                                  phase=phase, bloom=True, meridian=True)[0])))
        cells.append(im)
    cw = max(c.width for c in cells) + 10
    ch = max(c.height for c in cells) + 10
    W = 24 + cw * nframes + 24
    H = 80 + ch + 40
    canvas = Image.new("RGBA", (W, H), (18, 16, 22, 255))
    d = ImageDraw.Draw(canvas)
    d.text((24, 20), f"V2-W 19차-c — 오로라 일렁임 (위상 {nframes}프레임 · 결정론 위상 이동)",
           font=f_title, fill=(236, 232, 240))
    d.text((24, 50), "프레임 인덱스 = 사인 위상 — 헤일로의 밝은 마디가 인선을 따라 흐르고 "
                     "경혈 광점이 맥동한다 (애니 텍스처 시 재생 · §4-일렁임)", font=f_note,
           fill=(150, 200, 170))
    for i, im in enumerate(cells):
        x = 24 + i * cw
        canvas.alpha_composite(im, (x + (cw - 10 - im.width) // 2, 80))
        d.text((x + 4, 80 + ch), f"f{i}", font=f_note, fill=(180, 180, 190))
    canvas.convert("RGB").save(os.path.join(OUT, "v19c_aura_frames.png"))
    print("wrote v19c_aura_frames.png", canvas.size)


if __name__ == "__main__":
    compare_panel()
    annotate_panel()
    frames_panel()
    print("OUT dir:", os.path.abspath(OUT))
