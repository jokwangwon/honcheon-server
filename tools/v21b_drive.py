"""V2-W 21차-b — 기 포인트 3D 감쌈 + 팔각 원통화 + 범철 fp 크기. 스크래치 몽타주 (팩 무접촉).
  v21b_pilot.png    — [20차 | 21b] GUI 대형 대조 (만화 셀 + 기 포인트)
  v21b_qi_wrap.png  — 축 둘레 회전 시점 — 기 포인트가 3D 로 무기를 두르는 증명 (연속 리본 아님)
  v21b_octagon.png  — 자루·코등이 팔각 단면 컷 (사각기둥 아님) + 날 볼록 렌즈
  v21b_fp.png       — 범철 fp 크기 전(24)/후(26)/바닐라 기준
"""
import os, sys, zlib, math
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


def _salt(k):
    return zlib.crc32(k.encode()) & 0x7F


def spec(series, grade, mode, phase):
    if series == "hwasan":
        salt = _salt("myeong/hwasan")
        if mode == "21b":
            w._R21, w._R20, w._R21_ALLOW[0], w._R21_PHASE = True, False, True, phase
        else:
            w._R21, w._R20, w._R21_ALLOW[0], w._R20_PHASE = False, True, False, phase
        box, sh, ex = w._myeong_spec("hwasan", salt)
        return box, sh, salt
    salt = _salt(f"{series}_{grade}")
    r, b, t, m = w._GRADE_FORM[grade]
    if mode == "21b":
        w._R21, w._R20, w._R21_ALLOW[0], w._R21_PHASE = True, False, True, phase
    else:
        w._R21, w._R20, w._R21_ALLOW[0], w._R20_PHASE = False, True, False, phase
    fn = w._spec_sword if series == "sword" else w._spec_dao
    box, sh, ex = fn(salt, grade, r, b, t, m)
    return box, sh, salt


def front_img(series, grade, mode, phase=0.25):
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
    return im.crop((max(0, x0 - pad), max(0, y0 - pad), min(im.width, x1 + pad), min(im.height, y1 + pad)))


def on_bg(im, bg=BG):
    b = Image.new("RGBA", im.size, bg)
    b.alpha_composite(im)
    return b


def voxels(series, grade, mode):
    box, sh, salt = spec(series, grade, mode, 0.25)
    rows, k, dep, parts, qi = w._compose(sh, box, salt, grade)
    elems, tags, dep2 = w._voxelize(rows, dep, k, box, _return_tags=True)
    return elems


# ───────────────────────────── 1. 프론트 대조 [20차 | 21b]
def montage_pilot():
    f_t, f_l, f_c, f_n = _font(19), _font(13), _font(15), _font(11)
    specs = [("검 범철", "sword", "beomcheol"), ("검 신병", "sword", "sinbyeong"),
             ("검 마병", "sword", "mabyeong"), ("도 신병", "dao", "sinbyeong"),
             ("명병 화산", "hwasan", "sinbyeong")]
    imgs = [(l, on_bg(crop(front_img(s, g, "20"))), on_bg(crop(front_img(s, g, "21b")))) for l, s, g in specs]
    cw = max(max(a.width, b.width) for _, a, b in imgs) + 16
    ch = max(max(a.height, b.height) for _, a, b in imgs) + 12
    W, H = 340 + cw * 2, 96 + ch * len(imgs) + 16
    cv = Image.new("RGBA", (W, H), (18, 16, 22, 255))
    d = ImageDraw.Draw(cv)
    d.text((24, 16), "V2-W 21차-b — 만화 셀 + 기(氣) 복셀 포인트 (검을 3D로 두른다) + 팔각 원통화",
           font=f_t, fill=(236, 232, 240))
    d.text((24, 44), "기: 연속 리본(옆에 붙은 판) → 축 둘레 대각 순환 + 다중 z 로 띄엄띄엄 뜬 발광 포인트 "
                     "(자루→날끝 차례로 점등) · 자루·코등이·물미관 팔각 모깎기", font=f_n, fill=(150, 200, 170))
    d.text((340, 70), "20차 (전통 · 표면 스윕)", font=f_c, fill=(210, 200, 170))
    d.text((340 + cw, 70), "21차-b (만화 셀 + 기 포인트)", font=f_c, fill=(170, 220, 245))
    y = 96
    for l, a, b in imgs:
        d.text((16, y + ch // 2 - 8), l, font=f_l, fill=(220, 210, 180))
        cv.alpha_composite(a, (340 + (cw - a.width) // 2, y + (ch - a.height) // 2))
        cv.alpha_composite(b, (340 + cw + (cw - b.width) // 2, y + (ch - b.height) // 2))
        y += ch
    cv.convert("RGB").save(os.path.join(OUT, "v21b_pilot.png"))
    print("wrote v21b_pilot.png", cv.size)


# ───────────────────────────── 2. 기 3D 감쌈 — 축 둘레 회전 시점
def render3d(elems, alpha, S=13):
    """길이축(x) 둘레로 (y,z) 를 alpha 만큼 돌려 아이소 투영 — 기 포인트가 무기를 두르는 게
    회전에 따라 앞/뒤로 도는 걸 보인다. 파랑=본체 · 밝은 청록/적=기 float 포인트."""
    ca, sa = math.cos(alpha), math.sin(alpha)

    def qi(e):                       # 기 포인트 판별 — 정지 z 중심이 8 에서 크게 벗어남 (float)
        zc = (e["from"][2] + e["to"][2]) / 2
        return abs(zc - 8) > 1.3 and (e["to"][0] - e["from"][0]) < 2.2

    prims = []
    for e in elems:
        fx, fy, fz = e["from"]; tx, ty, tz = e["to"]
        is_qi = qi(e)
        for (x0, x1) in [(fx, tx)]:
            corners = []
            for xx in (x0, x1):
                for yy in (fy, ty):
                    for zz in (fz, tz):
                        yr = 8 + (yy - 8) * ca - (zz - 8) * sa
                        zr = 8 + (yy - 8) * sa + (zz - 8) * ca
                        sx = (yr - 8) * S + (zr - 8) * 0.45 * S
                        sy = -xx * S + (zr - 8) * 0.32 * S
                        corners.append((sx, sy, zr))
            xs = [c[0] for c in corners]; ys = [c[1] for c in corners]
            zc = sum(c[2] for c in corners) / len(corners)
            prims.append((zc, min(xs), min(ys), max(xs), max(ys), is_qi))
    prims.sort(key=lambda p: p[0])           # painter — 먼 것 먼저 (뒤가 뒤에)
    minx = min(p[1] for p in prims); maxx = max(p[3] for p in prims)
    miny = min(p[2] for p in prims); maxy = max(p[4] for p in prims)
    Wd, Ht = int(maxx - minx) + 20, int(maxy - miny) + 20
    im = Image.new("RGBA", (max(Wd, 60), max(Ht, 60)), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for zc, x0, y0, x1, y1, is_qi in prims:
        px0, py0 = x0 - minx + 10, y0 - miny + 10
        px1, py1 = x1 - minx + 10, y1 - miny + 10
        if is_qi:
            d.ellipse([px0 - 1, py0 - 1, px1 + 1, py1 + 1], fill=(120, 235, 245, 255),
                      outline=(240, 255, 255, 255))
        else:
            sh = max(60, min(150, int(110 + (zc - 8) * 12)))
            d.rectangle([px0, py0, px1, py1], fill=(sh, sh + 8, sh + 20, 255), outline=(30, 30, 40))
    return im


def montage_qi_wrap():
    f_t, f_l, f_n = _font(19), _font(13), _font(11)
    rows_spec = [("검 신병", "sword", "sinbyeong"), ("도 신병", "dao", "sinbyeong")]
    angles = [0, 45, 90, 135, 180]
    grid = []
    for l, s, g in rows_spec:
        el = voxels(s, g, "21b")
        grid.append((l, [on_bg(crop(render3d(el, math.radians(a)), 4)) for a in angles]))
    cw = max(im.width for _, r in grid for im in r) + 14
    ch = max(im.height for _, r in grid for im in r) + 24
    W, H = 200 + cw * len(angles), 84 + ch * len(grid)
    cv = Image.new("RGBA", (W, H), (16, 14, 20, 255))
    d = ImageDraw.Draw(cv)
    d.text((24, 16), "V2-W 21차-b — 기(氣) 포인트가 무기를 3D로 두른다 (길이축 둘레 회전 시점)",
           font=f_t, fill=(236, 232, 240))
    d.text((24, 44), "청록 점=기 float 포인트 · 파랑=본체. 회전에 따라 포인트가 앞→옆→뒤로 돌아 무기를 "
                     "감싼다 (옆에 붙은 코플래너 판 아님 · 대각 순환+다중 z)", font=f_n, fill=(150, 200, 170))
    y = 74
    for l, r in grid:
        d.text((20, y + ch // 2), l, font=f_l, fill=(220, 210, 180))
        for i, im in enumerate(r):
            x = 170 + i * cw
            cv.alpha_composite(im, (x + (cw - 14 - im.width) // 2, y + (ch - 20 - im.height) // 2))
            d.text((x + 4, y + ch - 18), f"{angles[i]}°", font=f_n, fill=(150, 150, 160))
        y += ch
    cv.convert("RGB").save(os.path.join(OUT, "v21b_qi_wrap.png"))
    print("wrote v21b_qi_wrap.png", cv.size)


# ───────────────────────────── 3. 팔각 단면 컷
def cross_section(elems, x0, csc=30, title=""):
    sel = [e for e in elems if e["from"][0] - 0.01 <= x0 <= e["to"][0] + 0.01 and "rotation" not in e]
    ys = [c for e in sel for c in (e["from"][1], e["to"][1])] or [6, 10]
    zs = [c for e in sel for c in (e["from"][2], e["to"][2])] or [6, 10]
    y0, y1 = min(ys) - 1, max(ys) + 1
    z0, z1 = min(zs) - 1, max(zs) + 1
    W, H = int((y1 - y0) * csc) + 20, int((z1 - z0) * csc) + 40
    im = Image.new("RGBA", (max(W, 130), H), (22, 20, 26, 255))
    d = ImageDraw.Draw(im)
    # 깊은(두꺼운) 것 먼저 → 얕은(모깎인) 가장자리 나중 = 팔각 계단이 보인다
    for e in sorted(sel, key=lambda e: -(e["to"][2] - e["from"][2])):
        fy, ty, fz, tz = e["from"][1], e["to"][1], e["from"][2], e["to"][2]
        px0, px1 = 10 + (fy - y0) * csc, 10 + (ty - y0) * csc
        pz0, pz1 = 20 + (z1 - tz) * csc, 20 + (z1 - fz) * csc
        d.rectangle([px0, pz0, px1 - 1, pz1 - 1], fill=(110, 200, 235), outline=(28, 40, 55))
    d.text((10, H - 16), title, font=_font(11), fill=(200, 200, 210))
    d.text((10, 4), f"x={x0:.1f}", font=_font(10), fill=(150, 150, 160))
    return im


def montage_octagon():
    f_t, f_n, f_c = _font(19), _font(11), _font(13)
    rows_spec = [("검 신병", "sword", "sinbyeong",
                  [("자루 (팔각봉)", -0.1), ("코등이 (팔각)", 5.6), ("물미관 (팔각)", -3.9), ("날 (볼록 렌즈)", 15.0)])]
    blocks = []
    for l, s, g, cuts in rows_spec:
        el = voxels(s, g, "21b")
        blocks.append((l, [cross_section(el, xx, title=cl) for cl, xx in cuts]))
    colw = max(im.width for _, r in blocks for im in r) + 18
    rowh = max(im.height for _, r in blocks for im in r) + 40
    W, H = 40 + colw * max(len(r) for _, r in blocks), 92 + rowh * len(blocks)
    cv = Image.new("RGBA", (W, H), (16, 14, 20, 255))
    d = ImageDraw.Draw(cv)
    d.text((24, 16), "V2-W 21차-b — 팔각 모깎기 단면 (사각기둥 → 원통 · 사용자: '이미지 늘려 입체 같다' 수리)",
           font=f_t, fill=(236, 232, 240))
    d.text((24, 44), "자루·코등이·물미관: 가장자리 z 모를 단계로 죽여 팔각 단면(원통처럼 읽힘). 날은 볼록 렌즈 유지. "
                     "네 귀가 계단으로 잘린 게 보인다 (균일 사각기둥 아님)", font=f_n, fill=(150, 200, 170))
    y = 84
    for l, r in blocks:
        d.text((24, y + rowh // 2), l, font=f_c, fill=(220, 210, 180))
        for i, im in enumerate(r):
            cv.alpha_composite(im, (150 + i * colw, y))
        y += rowh
    cv.convert("RGB").save(os.path.join(OUT, "v21b_octagon.png"))
    print("wrote v21b_octagon.png", cv.size)


# ───────────────────────────── 4. 범철 fp 크기
def montage_fp():
    f_t, f_n, f_l = _font(19), _font(11), _font(13)
    base = crop(front_img("sword", "beomcheol", "21b"))
    # fp: 종전 = 상한 24·계열 공유 분모(b) · 21b = 상한 26·**범철 제 본체**(own) · 바닐라 0.68
    b = w._series_body_extent("sword")
    salt = zlib.crc32(b"sword_beomcheol") & 0x7f
    r, bl, t, m = w._GRADE_FORM["beomcheol"]
    box, sh, _ = w._spec_sword(salt, "beomcheol", r, bl, t, m)
    rows, k, dep, parts, q = w._compose(sh, w._series_frame("sword"), salt, "beomcheol")
    own = w._body_extent(rows, dep, k)
    old_s = 0.68 * min(1.0, 24.0 / b)
    new_s = 0.68 * min(1.0, 26.0 / own)
    van = 0.68
    cols = [("종전 (상한24·공유분모)", old_s), ("21b (상한26·제본체)", new_s), ("바닐라 검(0.68)", van)]
    ref = max(new_s, van)
    imgs = [(l, on_bg(base.resize((max(1, int(base.width * s / ref)), max(1, int(base.height * s / ref))), Image.NEAREST))) for l, s in cols]
    cw = max(im.width for _, im in imgs) + 30
    ch = max(im.height for _, im in imgs) + 20
    W, H = cw * len(cols) + 40, ch + 110
    cv = Image.new("RGBA", (W, H), (18, 16, 22, 255))
    d = ImageDraw.Draw(cv)
    d.text((24, 16), "V2-W 21차-b — 범철 1인칭 크기 (사용자: '범철 fp에서 검이 작아')",
           font=f_t, fill=(236, 232, 240))
    d.text((24, 44), f"fp 상한 24→26 (전 등급 +8%) · 본체 extent(검) b≈{b:.1f} · 상대 크기 비교 (바닐라 검 존재감 근접)",
           font=f_n, fill=(150, 200, 170))
    for i, ((l, s), (_, im)) in enumerate(zip(cols, imgs)):
        x = 20 + i * cw
        cv.alpha_composite(im, (x + (cw - im.width) // 2, 80 + (ch - im.height) // 2))
        d.text((x + 8, 80 + ch + 4), f"{l}  (×{s:.3f})", font=f_l, fill=(210, 205, 185))
    cv.convert("RGB").save(os.path.join(OUT, "v21b_fp.png"))
    print("wrote v21b_fp.png", cv.size)


if __name__ == "__main__":
    montage_pilot()
    montage_qi_wrap()
    montage_octagon()
    montage_fp()
    w._R20, w._R21, w._R21_ALLOW[0] = True, False, False
    print("OUT:", os.path.abspath(OUT))
