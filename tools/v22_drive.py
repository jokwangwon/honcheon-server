"""V2-W 22차 — 레퍼런스식 번개 검 (작은 평면 스프라이트 + 가늘고 우아한 검신). 스크래치 몽타주.
  v22_ref_lightning.png — 검 신병(옥/청록 뇌)·도 신병(진홍 뇌) ×
    [ 레퍼런스 나란히 | 22차 GUI 정면 | 애니 프레임(치지직) | 회전 시점 ]

21e 는 검 전체를 3D 뇌전 케이지로 감싸 과함/난잡. 레퍼런스는 정반대 —
**작은 평면 번개 스프라이트 2~3개를 키 포인트(칼끝·코등이)에만** 얇게 (조율자 "적은 게 이긴다").
+ 검신 슬림(레이피어 톤): 반폭 1.5→1.4 · 길이 21.4→22.4 · 볼록 렌즈 유지. 팩 무접촉 (모듈 복원).
"""
import os, sys, zlib, math
from PIL import Image, ImageDraw, ImageFont
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import respack.weapons as w  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "scratch", "v2w_weapons_3d")
REF = os.path.join(HERE, "..", "scratch", "ref_lightning")
os.makedirs(OUT, exist_ok=True)
SCALE = 5
BG = (18, 16, 24, 255)


def _font(sz):
    for p in ("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, sz)
            except Exception:
                pass
    return ImageFont.load_default()


def _salt(series, grade):
    return zlib.crc32(f"{series}_{grade}".encode()) & 0x7F


def _spec(series, grade, phase):
    w._R21, w._R20, w._R21_ALLOW[0], w._R21_FX, w._R21_PHASE = True, False, True, "bolt", phase
    salt = _salt(series, grade)
    r, b, t, m = w._GRADE_FORM[grade]
    fn = w._spec_sword if series == "sword" else w._spec_dao
    box, sh, ex = fn(salt, grade, r, b, t, m)
    return box, sh, salt


def front_img(series, grade, phase):
    box, sh, salt = _spec(series, grade, phase)
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


def voxels(series, grade):
    box, sh, salt = _spec(series, grade, None)          # 위상 None = 스프라이트 전부 (기하)
    rows, k, dep, parts, qi = w._compose(sh, box, salt, grade)
    elems, tags, dep2 = w._voxelize(rows, dep, k, box, _return_tags=True)
    return elems


def crop(im, pad=6):
    bb = im.getbbox()
    if not bb:
        return im
    x0, y0, x1, y1 = bb
    return im.crop((max(0, x0 - pad), max(0, y0 - pad),
                    min(im.width, x1 + pad), min(im.height, y1 + pad)))


def on_bg(im, bg=BG):
    b = Image.new("RGBA", im.size, bg)
    b.alpha_composite(im)
    return b


# ── 길이축(x) 둘레 회전 아이소 투영 — 슬림 검신 + 칼끝·코등이 작은 번개 스프라이트를 보인다 ──
def render3d(elems, alpha, boltcol, S=13):
    ca, sa = math.cos(alpha), math.sin(alpha)

    def is_bolt(e):                                     # float 뇌전 — z 중심이 8 에서 벗어남 (±0.9)
        zc = (e["from"][2] + e["to"][2]) / 2
        return abs(zc - 8) > 0.5

    prims = []
    for e in elems:
        fx, fy, fz = e["from"]; tx, ty, tz = e["to"]
        b = is_bolt(e)
        corners = []
        for xx in (fx, tx):
            for yy in (fy, ty):
                for zz in (fz, tz):
                    yr = 8 + (yy - 8) * ca - (zz - 8) * sa
                    zr = 8 + (yy - 8) * sa + (zz - 8) * ca
                    sx = (yr - 8) * S + (zr - 8) * 0.45 * S
                    sy = -xx * S + (zr - 8) * 0.32 * S
                    corners.append((sx, sy, zr))
        xs = [c[0] for c in corners]; ys = [c[1] for c in corners]
        zc = sum(c[2] for c in corners) / len(corners)
        prims.append((zc, min(xs), min(ys), max(xs), max(ys), b))
    prims.sort(key=lambda p: p[0])
    minx = min(p[1] for p in prims); maxx = max(p[3] for p in prims)
    miny = min(p[2] for p in prims); maxy = max(p[4] for p in prims)
    im = Image.new("RGBA", (int(maxx - minx) + 20, int(maxy - miny) + 20), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for zc, x0, y0, x1, y1, b in prims:
        px0, py0 = x0 - minx + 10, y0 - miny + 10
        px1, py1 = x1 - minx + 10, y1 - miny + 10
        if b:                                           # 뇌전 스프라이트 — 밝은 계열색 + 흰 코어 테
            d.rectangle([px0 - 1, py0 - 1, px1 + 1, py1 + 1], fill=boltcol,
                        outline=(245, 250, 255, 255))
        else:                                           # 본체 — 청회색 (깊이 음영)
            sh = max(58, min(150, int(108 + (zc - 8) * 12)))
            d.rectangle([px0, py0, px1, py1], fill=(sh, sh + 8, sh + 22, 255),
                        outline=(28, 30, 42))
    return im


def ref_thumb(fname, target_h):
    p = os.path.join(REF, fname)
    if not os.path.exists(p):
        return None
    im = Image.open(p).convert("RGBA")
    scale = target_h / im.height
    return im.resize((max(1, int(im.width * scale)), target_h), Image.LANCZOS)


def montage():
    f_t, f_l, f_c, f_n = _font(19), _font(13), _font(13), _font(11)
    rows_spec = [("검 신병 (옥/청록 뇌)", "sword", "sinbyeong", (120, 235, 190, 255)),
                 ("도 신병 (진홍 뇌)", "dao", "sinbyeong", (240, 95, 72, 255))]
    angles = [0, 45, 90]
    nfr = 6
    phs = [i / nfr for i in range(nfr)]
    data = []
    for lab, s, g, bc in rows_spec:
        el = voxels(s, g)
        rots = [on_bg(crop(render3d(el, math.radians(a), bc), 4)) for a in angles]
        frames = [on_bg(crop(front_img(s, g, p))) for p in phs]
        gui = on_bg(crop(front_img(s, g, 0.0)))         # 대표 GUI 프레임
        data.append((lab, gui, frames, rots))
    ch = max(max(im.height for _, g, fr, r in data for im in ([g] + fr + r)) + 26,
             220)
    # 레퍼런스 썸네일 (검 레퍼런스 3프레임 — 도는 진홍 계열색 주석)
    refs = [ref_thumb(f, ch - 30) for f in ("ref1.png", "ref2.png", "ref3.png")]
    refs = [r for r in refs if r]
    cwref = (sum(r.width for r in refs) + 8 * len(refs) + 12) if refs else 120
    cwg = max(g.width for _, g, _, _ in data) + 14
    cwf = max(im.width for _, _, fr, _ in data for im in fr) + 10
    cwr = max(im.width for _, _, _, r in data for im in r) + 12
    x_ref = 150
    x_gui = x_ref + cwref + 24
    x_frm = x_gui + cwg + 24
    x_rot = x_frm + cwf * nfr + 28
    W = x_rot + cwr * len(angles) + 24
    H = 112 + ch * len(rows_spec) + 16
    cv = Image.new("RGBA", (W, H), (13, 11, 17, 255))
    d = ImageDraw.Draw(cv)
    d.text((22, 12), "V2-W 22차 — 레퍼런스식 번개 검  (작은 평면 스프라이트 · 칼끝·코등이 · 가늘고 우아한 검신)",
           font=f_t, fill=(236, 232, 245))
    d.text((22, 40), "21e 검 전체 3D 뇌전 케이지(과함) → 22차: 칼끝·코등이 두 키포인트에만 작은 얇은 청록 지그재그 "
                     "아크(적은 게 이긴다) · 검신 반폭 1.5→1.4·길이 21.4→22.4(레이피어 톤·볼록 렌즈 유지) · frametime 2",
           font=f_n, fill=(150, 205, 225))
    d.text((x_ref, 86), "레퍼런스 (사용자 목표)", font=f_c, fill=(210, 205, 160))
    d.text((x_gui, 86), "22차 GUI 정면", font=f_c, fill=(180, 225, 235))
    d.text((x_frm, 86), "애니 프레임 — 치지직 (번개 옮겨 번쩍)", font=f_c, fill=(200, 225, 250))
    d.text((x_rot, 86), "회전 시점", font=f_c, fill=(180, 225, 235))
    y = 112
    for ri, (lab, gui, frames, rots) in enumerate(data):
        d.text((12, y + ch // 2 - 8), lab, font=f_l, fill=(222, 214, 190))
        if ri == 0 and refs:
            xx = x_ref
            for r in refs:
                cv.alpha_composite(on_bg(r), (xx, y + (ch - r.height) // 2))
                xx += r.width + 8
        elif ri == 0:
            d.text((x_ref, y + ch // 2), "(레퍼런스 없음)", font=f_n, fill=(150, 150, 160))
        else:
            d.text((x_ref, y + ch // 2 - 8), "레퍼런스=검 기준\n도는 진홍 계열색\n(대담 곡선 유지)",
                   font=f_n, fill=(170, 150, 150))
        cv.alpha_composite(gui, (x_gui + (cwg - gui.width) // 2, y + (ch - gui.height) // 2))
        for i, im in enumerate(frames):
            x = x_frm + i * cwf
            cv.alpha_composite(im, (x + (cwf - im.width) // 2, y + (ch - im.height) // 2))
            d.text((x + 4, y + ch - 16), f"f{i}", font=f_n, fill=(140, 140, 155))
        for i, im in enumerate(rots):
            x = x_rot + i * cwr
            cv.alpha_composite(im, (x + (cwr - im.width) // 2, y + (ch - im.height) // 2))
            d.text((x + 4, y + ch - 16), f"{angles[i]}°", font=f_n, fill=(140, 140, 155))
        y += ch
    cv.convert("RGB").save(os.path.join(OUT, "v22_ref_lightning.png"))
    print("wrote v22_ref_lightning.png", cv.size)


if __name__ == "__main__":
    montage()
    # ★모듈 상태 복원 — 팩 무접촉 (배선 현행: _R21 파일럿 켜짐 · FX bolt · 위상 None)
    w._R20, w._R21, w._R21_ALLOW[0], w._R21_FX, w._R21_PHASE = False, True, False, "bolt", None
    print("OUT:", os.path.abspath(OUT))
