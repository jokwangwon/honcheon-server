#!/usr/bin/env python3
"""리소스팩 검수 — 팩의 '눈'과 '자'.

조성기에 /혼천 검수·조감 이 있듯, 팩에도 측정과 확대가 있어야 반복이 굴러간다.

  측정(린트): 색 수·채도·색조 중립성·명암 대비·타일링 이음매·규약(16x16, RGBA8)
  확대(시트): 8배 확대 대조 시트 — blocks / items / ui 세 장 (사람·모델이 눈으로 본다)

사용:
  python3 tools/texture_audit.py                 # 린트 + 시트 (run/texture-review/)
  python3 tools/texture_audit.py --lint-only

외부 라이브러리 금지 (순수 파이썬) — build_resourcepack.py 와 같은 관행.
"""
import argparse
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / "resourcepack" / "assets"
OUT = ROOT / "run" / "texture-review"

# ─── 판정 기준 (팩의 가이드라인) ───
MIN_COLORS = 4            # 4색 미만 = 평면 채우기 (명암이 없다)
MAX_CHROMA_INK = 10       # 수묵 무채색 계열(회벽·흑와)의 채도 상한 (max-min RGB)
MAX_BLUE_TINT = 8         # 흑와의 푸른 기 (B - R)
MIN_CONTRAST = 24         # 최명/최암 차 — 이보다 작으면 밋밋하다
SEAM_MAX = 1.25           # 타일링 이음매 = 랩 경계 차이 / 내부 경계 중앙값


def read_png(path):
    d = path.read_bytes()
    pos, w, h, idat = 8, 0, 0, b""
    while pos < len(d):
        ln = struct.unpack(">I", d[pos:pos + 4])[0]
        typ = d[pos + 4:pos + 8]
        data = d[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            w, h, bd, ct = struct.unpack(">IIBB", data[:10])
            if bd != 8 or ct != 6:
                raise ValueError(f"{path.name}: RGBA8 아님 (bd={bd} ct={ct})")
        elif typ == b"IDAT":
            idat += data
        pos += 12 + ln
    raw = zlib.decompress(idat)
    stride, rows, prev, i = w * 4, [], bytearray(w * 4), 0
    for _ in range(h):
        f = raw[i]; i += 1
        line = bytearray(raw[i:i + stride]); i += stride
        for x in range(stride):
            a = line[x - 4] if x >= 4 else 0
            b = prev[x]
            c = prev[x - 4] if x >= 4 else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[x] = (line[x] + pr) & 255
        rows.append(bytes(line))
        prev = line
    return w, h, rows


def write_png(path, w, h, rows):
    raw = b"".join(b"\x00" + bytes(r) for r in rows)

    def chunk(tag, data):
        body = struct.pack(">I", len(data)) + tag + data
        return body + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n"
                     + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(raw))
                     + chunk(b"IEND", b""))


def px(rows, x, y):
    return rows[y][x * 4:x * 4 + 4]


def opaque(rows, w, h):
    return [px(rows, x, y) for y in range(h) for x in range(w) if px(rows, x, y)[3] > 8]


def edge_diff(a, b):
    return sum(abs(a[i] - b[i]) for i in range(3))


def seam_score(rows, w, h):
    """타일링 이음매 — 랩 경계의 차이가 내부 경계들 사이에서 이상치인가."""
    def col_diff(x0, x1):
        return sum(edge_diff(px(rows, x0, y), px(rows, x1, y)) for y in range(h)) / h

    def row_diff(y0, y1):
        return sum(edge_diff(px(rows, x, y0), px(rows, x, y1)) for x in range(w)) / w

    # 분모는 중앙값이 아니라 상위 경계(90퍼센타일)다 — 기와 골·판자 결처럼 '의도된 강한 경계'가
    # 있는 텍스처를 중앙값으로 재면, 매끈한 내부만 보고 정상 랩을 이음매로 오판한다.
    # 판정 질문은 "랩 경계가 이 텍스처의 강한 경계들 사이에서 이상치인가"이다.
    inner_x = sorted(col_diff(x, x + 1) for x in range(w - 1))
    inner_y = sorted(row_diff(y, y + 1) for y in range(h - 1))
    ref_x = inner_x[int(len(inner_x) * 0.9)] or 1
    ref_y = inner_y[int(len(inner_y) * 0.9)] or 1
    return max(col_diff(w - 1, 0) / ref_x, row_diff(h - 1, 0) / ref_y)


def lint(path, name):
    w, h, rows = read_png(path)
    notes, bad = [], False
    if (w, h) != (16, 16) and "block" in str(path) or (w, h) != (16, 16) and "/item/" in str(path):
        notes.append(f"크기 {w}x{h} (16x16 아님)")
        bad = True
    pxs = opaque(rows, w, h)
    if not pxs:
        return name, ["전부 투명"], True
    colors = len({bytes(p[:3]) for p in pxs})
    lum = [0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2] for p in pxs]
    contrast = max(lum) - min(lum)
    avg = [sum(p[i] for p in pxs) / len(pxs) for i in range(3)]
    chroma = max(avg) - min(avg)
    seam = seam_score(rows, w, h) if w == h == 16 else 0

    # 폰트 글리프(font/)는 **단색이 정답**이다 — 인게임에서 §색으로 물들여 쓰는 문자다.
    #   여기에 "4색·명암차 24" 를 들이대면 린트가 18건을 거짓으로 외친다 (검수의 눈이 거짓말하면
    #   루프가 헛돈다). 글리프는 모양(불투명 픽셀 비율)으로 본다: 다 채우면 뭉개지고, 너무 비면 안 보인다.
    is_glyph = "/font/" in str(path).replace("\\", "/")
    if is_glyph:
        # 게이지(gauge_*)는 차오르는 그림이라 100% 채움이 정답이고, 장부 배경(gui_*)도 면이다.
        # 획으로 보는 것은 문장·글자 글리프뿐이다.
        fill = len(pxs) / (w * h)
        if not name.startswith(("gauge_", "gui_")) and not 0.03 <= fill <= 0.75:
            notes.append(f"획 채움 {fill:.0%} (권장 3~75% — 뭉개짐/실종)")
            bad = True
    elif colors < MIN_COLORS:
        notes.append(f"색 {colors} < {MIN_COLORS} (평면)")
        bad = True
    if not is_glyph and contrast < MIN_CONTRAST:
        notes.append(f"명암차 {contrast:.0f} < {MIN_CONTRAST} (밋밋)")
        bad = True
    if seam > SEAM_MAX:
        notes.append(f"이음매 {seam:.2f} > {SEAM_MAX}")
        bad = True
    if name in ("deepslate_tiles", "cracked_deepslate_tiles"):
        blue = avg[2] - avg[0]
        if blue > MAX_BLUE_TINT:
            notes.append(f"푸른 기 B-R={blue:.0f} > {MAX_BLUE_TINT} (기와는 검다)")
            bad = True
    if name in ("white_terracotta", "light_gray_terracotta"):
        if chroma > MAX_CHROMA_INK:
            notes.append(f"채도 {chroma:.0f} > {MAX_CHROMA_INK} (회벽은 무채색)")
            bad = True
    return name, notes, bad, dict(colors=colors, contrast=contrast, chroma=chroma, seam=seam,
                                  avg=tuple(round(v) for v in avg))


def sheet(files, out_path, scale=8, cols=4, label_h=0):
    tiles = []
    for f in files:
        try:
            w, h, rows = read_png(f)
        except Exception:
            continue
        tiles.append((f.stem, w, h, rows))
    if not tiles:
        return None
    cw = max(t[1] for t in tiles) * scale
    ch = max(t[2] for t in tiles) * scale
    rows_n = (len(tiles) + cols - 1) // cols
    W, H = cols * cw, rows_n * ch
    canvas = [bytearray(b"\x20\x20\x28\xff" * W) for _ in range(H)]
    for i, (name, w, h, rows) in enumerate(tiles):
        ox, oy = (i % cols) * cw, (i // cols) * ch
        for y in range(h * scale):
            for x in range(w * scale):
                p = px(rows, x // scale, y // scale)
                if p[3] == 0:
                    p = b"\x40\x30\x30\xff"          # 투명 = 팥색 배경 (알파 확인용)
                o = (ox + x) * 4
                canvas[oy + y][o:o + 4] = p
    write_png(out_path, W, H, canvas)
    return out_path, [t[0] for t in tiles]


def roof_mock(tex_path, out_path, scale=6):
    """계단 지붕 목업 — 텍스처를 실제 지붕(계단 블록 층계)에 얹어 아이소메트릭으로 그린다.

    16x16 확대만 보면 예뻐 보여도 계단에 깔리면 골함석처럼 보이는 일이 있다.
    지붕은 '한 장'이 아니라 '층계에 반복된 면'으로 보이는 것이 진실이다 — 그걸 먼저 본다.
    """
    w, h, rows = read_png(tex_path)
    steps, run = 6, 3                      # 6단 계단, 단마다 3칸 전진
    tiles_w = 10
    TW = 16 * scale
    W = (tiles_w + steps) * TW
    H = (steps + 4) * TW
    canvas = [bytearray(b"\x1a\x1a\x22\xff" * W) for _ in range(H)]

    def blit(ox, oy, shade):
        for y in range(TW):
            for x in range(TW):
                p = px(rows, (x // scale) % w, (y // scale) % h)
                if p[3] == 0:
                    continue
                r = int(p[0] * shade); g = int(p[1] * shade); b = int(p[2] * shade)
                X, Y = ox + x, oy + y
                if 0 <= X < W and 0 <= Y < H:
                    o = X * 4
                    canvas[Y][o:o + 4] = bytes((min(r, 255), min(g, 255), min(b, 255), 255))

    # 층계: 위 면(밝게) + 앞 면(어둡게) — 계단 블록의 두 얼굴
    for s in range(steps):
        top_y = (s + 1) * TW
        for t in range(tiles_w - s):
            ox = (s + t) * TW
            blit(ox, top_y - TW, 1.0)      # 윗면 (기와가 깔린 면)
            blit(ox, top_y, 0.62)          # 앞면 (단의 수직면 — 그림자)
    write_png(out_path, W, H, canvas)
    return out_path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lint-only", action="store_true")
    args = ap.parse_args()

    groups = {
        "blocks": sorted((PACK / "minecraft" / "textures" / "block").glob("*.png")),
        # 16x16 아이템만 — 큰 GUI 텍스처가 섞이면 타일 크기가 그것에 맞춰져
        # 아이템이 셀 구석의 점이 된다 (시트가 못 쓰게 된다). 크기로 갈라 담는다.
        "items": [f for f in sorted((PACK / "honcheon" / "textures").rglob("*.png"))
                  if read_png(f)[0] <= 32],
        "gui": [f for f in sorted((PACK / "honcheon" / "textures").rglob("*.png"))
                if read_png(f)[0] > 32],
        "ui": sorted((PACK / "minecraft" / "textures" / "gui").rglob("*.png"))
             + sorted((PACK / "minecraft" / "textures" / "font").rglob("*.png")),
    }

    print("══ 리소스팩 검수 ══")
    violations = 0
    for group, files in groups.items():
        if not files:
            continue
        print(f"\n── {group} ({len(files)}장) ──")
        for f in files:
            r = lint(f, f.stem)
            name, notes, bad = r[0], r[1], r[2]
            m = r[3] if len(r) > 3 else {}
            if bad:
                violations += 1
                print(f"  ❌ {name}: {', '.join(notes)}")
            elif m:
                print(f"  ✅ {name}: 색 {m['colors']} · 명암 {m['contrast']:.0f}"
                      f" · 채도 {m['chroma']:.0f} · 이음매 {m['seam']:.2f} · 평균 {m['avg']}")

    if not args.lint_only:
        roof = PACK / "minecraft" / "textures" / "block" / "deepslate_tiles.png"
        if roof.exists():
            roof_mock(roof, OUT / "roof_mock.png")
            print(f"\n── 계단 지붕 목업 ──\n  {OUT / 'roof_mock.png'}")
        print("\n── 확대 시트 (8배) ──")
        for group, files in groups.items():
            if not files:
                continue
            res = sheet(files, OUT / f"{group}.png")
            if res:
                print(f"  {res[0]}  ({len(res[1])}장)")

    print(f"\n총평: 위반 {violations}건")
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main())
