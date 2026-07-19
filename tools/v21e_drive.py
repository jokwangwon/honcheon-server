"""V2-W 21차-e — 검을 휘감는 3D 번개(뇌전) 껍질. 스크래치 몽타주 (팩 무접촉·모듈 복원).
  v21e_lightning.png — 검 신병(옥빛 뇌)·도 마병(검붉은 뇌전) ×
     [ 회전 시점 5컷 (번개가 3D 로 검을 감싸는 증명 — 길이축 둘레 회전)
     | 애니 프레임 6컷 (감속 치지직 · 볼트가 프레임마다 옮겨 튐 · 지그재그 형태) ]

사용자 인게임 실측 3건 수정: ① frametime 1→3틱(감속) ② 표면 잡음 → 진짜 번개 가지(지그재그
척추+분기·흰-핫 코어) ③★ 날 표면 오버레이 폐지 → 검 외부에서 기(氣)를 대신해 번개가 날을 휘감음
(21b 기 포인트 껍질 구조를 뇌전 볼트로 대체 · float · 다중 z · helix).
"""
import os, sys, zlib, math
from PIL import Image, ImageDraw, ImageFont
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import respack.weapons as w  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "scratch", "v2w_weapons_3d")
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
    box, sh, salt = _spec(series, grade, None)          # 위상 None = 뇌전 케이지 전부 (기하)
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


# ── 길이축(x) 둘레 회전 아이소 투영 — 번개가 앞→옆→뒤로 돌아 검을 감싸는 걸 보인다 ──
def render3d(elems, alpha, boltcol, S=13):
    ca, sa = math.cos(alpha), math.sin(alpha)

    def is_bolt(e):                                     # float 뇌전 — z 중심이 8 에서 크게 벗어남
        zc = (e["from"][2] + e["to"][2]) / 2
        return abs(zc - 8) > 1.0

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
    prims.sort(key=lambda p: p[0])                      # painter — 먼 것(뒤) 먼저
    minx = min(p[1] for p in prims); maxx = max(p[3] for p in prims)
    miny = min(p[2] for p in prims); maxy = max(p[4] for p in prims)
    im = Image.new("RGBA", (int(maxx - minx) + 20, int(maxy - miny) + 20), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    for zc, x0, y0, x1, y1, b in prims:
        px0, py0 = x0 - minx + 10, y0 - miny + 10
        px1, py1 = x1 - minx + 10, y1 - miny + 10
        if b:                                           # 뇌전 볼트 — 밝은 계열색 + 흰 코어 테
            d.rectangle([px0 - 1, py0 - 1, px1 + 1, py1 + 1], fill=boltcol,
                        outline=(245, 250, 255, 255))
        else:                                           # 본체 — 청회색 (깊이 음영)
            sh = max(58, min(150, int(108 + (zc - 8) * 12)))
            d.rectangle([px0, py0, px1, py1], fill=(sh, sh + 8, sh + 22, 255),
                        outline=(28, 30, 42))
    return im


def montage():
    f_t, f_l, f_c, f_n = _font(19), _font(13), _font(13), _font(11)
    rows_spec = [("검 신병 (옥빛 뇌)", "sword", "sinbyeong", (120, 235, 190, 255)),
                 ("도 마병 (검붉은 뇌전)", "dao", "mabyeong", (240, 95, 72, 255))]
    angles = [0, 45, 90, 135, 180]
    nfr = 6
    phs = [i / nfr for i in range(nfr)]
    data = []
    for lab, s, g, bc in rows_spec:
        el = voxels(s, g)
        rots = [on_bg(crop(render3d(el, math.radians(a), bc), 4)) for a in angles]
        frames = [on_bg(crop(front_img(s, g, p))) for p in phs]
        data.append((lab, rots, frames))
    cwr = max(im.width for _, r, _ in data for im in r) + 12
    chr_ = max(im.height for _, r, _ in data for im in r) + 26
    cwf = max(im.width for _, _, fr in data for im in fr) + 10
    chf = max(im.height for _, _, fr in data for im in fr) + 16
    ch = max(chr_, chf)
    xrot = 190
    xfr = xrot + cwr * len(angles) + 34
    W = xfr + cwf * nfr + 24
    H = 104 + ch * len(rows_spec) + 16
    cv = Image.new("RGBA", (W, H), (13, 11, 17, 255))
    d = ImageDraw.Draw(cv)
    d.text((22, 14), "V2-W 21차-e — 검을 휘감는 3D 번개(뇌전) 껍질  (기 포인트 → 뇌전 볼트로 대체)",
           font=f_t, fill=(236, 232, 245))
    d.text((22, 42), "21e — 진짜 번개 문법: 검 외부 float 아크가 검을 휘감되(helix·다중 z), 굵은 균일 Z → "
                     "가는 흰-핫 코어 + 불규칙 꺾임(결정론 해시) + 분기 fork(짧게 taper·점점 사라짐) · frametime 2틱",
           font=f_n, fill=(150, 205, 225))
    d.text((xrot, 78), "회전 시점 — 번개가 3D로 검을 감싼다 (앞→옆→뒤)", font=f_c, fill=(180, 225, 235))
    d.text((xfr, 78), "애니 프레임 — 감속 치지직 (볼트가 옮겨 튐)", font=f_c, fill=(200, 225, 250))
    y = 104
    for lab, rots, frames in data:
        d.text((12, y + ch // 2 - 8), lab, font=f_l, fill=(222, 214, 190))
        for i, im in enumerate(rots):
            x = xrot + i * cwr
            cv.alpha_composite(im, (x + (cwr - im.width) // 2, y + (ch - im.height) // 2))
            d.text((x + 4, y + ch - 16), f"{angles[i]}°", font=f_n, fill=(140, 140, 155))
        for i, im in enumerate(frames):
            x = xfr + i * cwf
            cv.alpha_composite(im, (x + (cwf - im.width) // 2, y + (ch - im.height) // 2))
            d.text((x + 4, y + ch - 16), f"f{i}", font=f_n, fill=(140, 140, 155))
        y += ch
    cv.convert("RGB").save(os.path.join(OUT, "v21e_lightning.png"))
    print("wrote v21e_lightning.png", cv.size)


if __name__ == "__main__":
    montage()
    # ★모듈 상태 복원 — 팩 무접촉 (배선 현행: _R21 파일럿 켜짐 · FX bolt · 위상 None)
    w._R20, w._R21, w._R21_ALLOW[0], w._R21_FX, w._R21_PHASE = False, True, False, "bolt", None
    print("OUT:", os.path.abspath(OUT))
