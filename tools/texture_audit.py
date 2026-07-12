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
ITEM = PACK / "honcheon" / "textures" / "item"
BLOCK = PACK / "minecraft" / "textures" / "block"

# ─── 판정 기준 (팩의 가이드라인) ───
MIN_COLORS = 4            # 4색 미만 = 평면 채우기 (명암이 없다)
MAX_CHROMA_INK = 10       # 수묵 무채색 계열(회벽·흑와)의 채도 상한 (max-min RGB)
MAX_BLUE_TINT = 8         # 흑와의 푸른 기 (B - R)
MIN_CONTRAST = 24         # 최명/최암 차 — 이보다 작으면 밋밋하다
SEAM_MAX = 1.25           # 타일링 이음매 = 랩 경계 차이 / 내부 경계 중앙값

# ─── 2차 검수 축 (2026-07 추가) — 아래 다섯은 "한 장을 확대해서" 보면 절대 안 걸리는 결함들이다.
#     기존 축은 전부 **텍스처 한 장 안의 통계**였다 (색 수·명암·채도·이음매·획). 그래서 팩은
#     위반 0건이면서도 (ㄱ) 아이콘끼리 서로 닮았고 (ㄴ) 등급이 안 갈리고 (ㄷ) 벽이 격자로 반복되고
#     (ㄹ) HUD가 어두운 배경에서 사라질 수 있었다. 새 축은 **관계**를 잰다: 아이콘 대 아이콘,
#     등급 대 등급, 타일 대 자기 자신(시프트), 스프라이트 대 배경.
JACCARD_MAX = 0.65        # 계열 실루엣 자카드 상한 — 두 아이콘이 실루엣의 2/3를 공유하면 16px에서 못 가른다
GRADE_DELTA_MIN = 8       # 인접 등급 간 '색 없이도 다른' 픽셀 수 하한 (colorblind_rule의 계량화)
OUTLINE_LUMA_MAX = 70     # 실루엣 경계 픽셀의 밝기 상한 — 이보다 밝으면 외곽선이 끊긴 것이다
REPLICATION_MAX = 0.85    # 면 반복재의 자기 복제 상관 상한 — 넘으면 블록이 제 안에서 스스로를 복사한다
UI_LEGIBLE_MIN = 8        # HUD 스프라이트가 흑/백 두 배경 각각에서 확보해야 할 '읽히는 픽셀' 수
UI_LEGIBLE_DELTA = 55     # '읽히는 픽셀'의 정의 — 합성 후 배경과의 휘도차

WEAPON_SERIES = ["sword", "dao", "spear", "gauntlet", "dagger", "bu", "gyeom", "wolasan", "gu"]
WEAPON_GRADES = ["beomcheol", "jeongryeon", "bobyeong", "sinbyeong"]

# 면 반복재 — '벽·바닥·지붕처럼 여러 장이 이어 붙어 하나의 면이 되는' 블록만.
# 제외 대상과 그 이유 (축을 아무 데나 들이대면 거짓 위반이 뜨고, 거짓 위반은 루프를 헛돌린다):
#   · 등롱(lantern·soul_lantern) — 낱개 모델로 선다. 제 안에서 반복돼도 벽이 되지 않는다.
#   · 약장 앞판(chiseled_bookshelf_empty·occupied) — **가구**다. 3×2 여섯 칸 격자는 바닐라의
#     슬롯 UV 계약이라 옮길 수 없고(칸마다 제 영역을 UV로 잡아 그린다), 그 반복은 모아레가 아니라
#     물건의 생김새 자체다. 대신 '여섯 칸이 한 판의 도장 여섯 번'인 것은 별개의 결함이므로
#     칸마다 결·톤·손잡이를 달리해 고쳤다 (occupied 0.922 → 0.814).
#     약장 몸통(side·top)은 결만 있는 면이므로 축의 대상이다.
TILING_BLOCKS = {
    "deepslate_tiles", "cracked_deepslate_tiles", "deepslate_bricks", "cracked_deepslate_bricks",
    "white_terracotta", "light_gray_terracotta", "bamboo_planks", "glass", "glass_pane_top",
    "chiseled_bookshelf_side", "chiseled_bookshelf_top",
}


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


def luma(p):
    return 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]


def mask_of(rows, w, h):
    """실루엣 마스크 — 알파 8 초과가 '있는 것'. (바닐라 아이템 렌더도 사실상 이 문턱이다)"""
    return [[px(rows, x, y)[3] > 8 for x in range(w)] for y in range(h)]


# ─── 축 6. 외곽선 폐합 (실루엣 가독성) ──────────────────────────────────────
# 바닐라 아이템이 어떤 배경 위에서도 떠오르는 이유는 예외 없이 두른 **어두운 외곽선** 때문이다.
# 팩의 outline()이 그 테를 자동으로 둘러 주지만, 두 곳에서 새어 나간다:
#   ① 아트가 캔버스 가장자리에 닿으면 그 변(邊)에는 테를 두를 자리가 없다 (밝은 픽셀이 노출된다)
#   ② outline() 뒤에 밝은 픽셀을 덧찍으면(수실·하이라이트) 테가 그 자리에서 끊긴다
# 지표: 실루엣 경계 픽셀(투명 또는 캔버스 밖과 4방으로 맞닿은 불투명 픽셀) 중 휘도 > 70 인 개수.
def outline_break(rows, w, h):
    m = mask_of(rows, w, h)
    total = bad = 0
    for y in range(h):
        for x in range(w):
            if not m[y][x]:
                continue
            if not any(not (0 <= x + dx < w and 0 <= y + dy < h and m[y + dy][x + dx])
                       for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                continue
            total += 1
            if luma(px(rows, x, y)) > OUTLINE_LUMA_MAX:
                bad += 1
    return bad, total


# ─── 축 7. 자기 복제 상관 (타일링 반복성·방향성) ─────────────────────────────
# 사용자가 두 번 지적한 고질 — "기와가 한 방향으로 반복돼 이질감이 든다".
# 이음매 축(SEAM)은 **장과 장의 경계**만 본다. 그러나 이질감의 진짜 원인은 경계가 아니라
# **한 장 안의 주기성**이다: 블록이 제 안에서 스스로를 복사하고 있으면, 벽에 이어 붙였을 때
# 블록 크기보다 잘은 격자가 눈에 잡히고 그것이 모아레·타탄·골함석으로 읽힌다.
# 지표: 16x16 원환(토러스) 위 휘도의 **정규화 자기상관** r(dx,dy) 의 원점 제외 최대값.
#   r = 1 이면 그 시프트만큼 밀었을 때 자기 자신과 정확히 겹친다 = 순수한 스탬프 복제.
#   난수 얼룩만 있는 회벽은 0.3 아래로 떨어진다 (실측). 0.85 초과 = 복제.
# 부수 지표로 가로축(dy=0)·세로축(dx=0) 최대 상관을 따로 뽑는다 — 어느 방향으로 반복하는지가
# 보여야 고칠 자리를 안다 (사용자가 말한 '한 방향'이 이것이다).
def replication(rows, w, h):
    L = [[luma(px(rows, x, y)) for x in range(w)] for y in range(h)]
    mu = sum(sum(r) for r in L) / (w * h)
    var = sum((v - mu) ** 2 for r in L for v in r) / (w * h)
    if var < 1e-6:
        return 0.0, (0, 0), 0.0, 0.0
    best, hmax, vmax = (-2.0, (0, 0)), -2.0, -2.0
    for dy in range(h):
        for dx in range(w):
            if dx == 0 and dy == 0:
                continue
            c = sum((L[y][x] - mu) * (L[(y + dy) % h][(x + dx) % w] - mu)
                    for y in range(h) for x in range(w)) / (w * h) / var
            if c > best[0]:
                best = (c, (dx, dy))
            if dy == 0:
                hmax = max(hmax, c)
            if dx == 0:
                vmax = max(vmax, c)
    return best[0], best[1], hmax, vmax


# ─── 축 8. 양배경 가독 (UI 대비) ─────────────────────────────────────────────
# HUD는 하늘 위에도, 동굴 어둠 위에도 얹힌다. 한쪽 배경에서만 읽히는 스프라이트는 반쪽이다.
# 지표: 스프라이트를 흰 배경(255)과 검은 배경(0)에 각각 알파 합성하고, 배경과의 휘도차가
#       55 이상인 픽셀 수를 센다. 두 배경 모두에서 8px 이상이어야 한다
#       (= 스프라이트 안에 '어두운 요소'와 '밝은 요소'가 둘 다 있어야 한다는 말의 계량화).
def bg_legibility(rows, w, h):
    out = {}
    for bg in (255, 0):
        n = 0
        for y in range(h):
            for x in range(w):
                p = px(rows, x, y)
                if p[3] < 8:
                    continue
                a = p[3] / 255
                comp = [p[i] * a + bg * (1 - a) for i in range(3)]
                if abs(luma(comp) - bg) >= UI_LEGIBLE_DELTA:
                    n += 1
        out[bg] = n
    return out[255], out[0]


# ─── 축 9. 계열 실루엣 자카드 (아이콘만 보고 계열이 갈리는가) ────────────────
# 핫바의 아이콘은 대개 **틴트된 실루엣**으로 먼저 인지된다 (색과 세부는 그 다음이다).
# 두 계열의 실루엣이 크게 겹치면 플레이어는 손에 든 것이 도인지 비수인지 모른다.
# 지표: 마스크의 자카드 유사도 J = |A∩B| / |A∪B|. 0.65 초과 = 실루엣의 2/3 공유 = 못 가른다.
def series_jaccard():
    masks = {}
    for s in WEAPON_SERIES:
        f = ITEM / "weapon" / f"{s}_beomcheol.png"
        w, h, rows = read_png(f)
        masks[s] = mask_of(rows, w, h)
    out = []
    for i, a in enumerate(WEAPON_SERIES):
        for b in WEAPON_SERIES[i + 1:]:
            inter = sum(1 for y in range(16) for x in range(16) if masks[a][y][x] and masks[b][y][x])
            uni = sum(1 for y in range(16) for x in range(16) if masks[a][y][x] or masks[b][y][x])
            out.append((inter / uni if uni else 0.0, a, b))
    out.sort(reverse=True)
    return out


# ─── 축 10. 등급 회색조 변별 (colorblind_rule 의 계량화) ─────────────────────
# 설계 규약은 "등급은 색 단독으로 말하지 않는다"이다. 그런데 그 규약을 **재는 자**가 없었다.
# 지표: 같은 계열의 인접 등급 두 장을 겹쳐, (ㄱ) 실루엣이 다르거나 (ㄴ) 휘도가 16 이상 다른
#       픽셀 수. 색상(色相)은 세지 않는다 — 색맹 플레이어와 회색조 스크린샷이 보는 것이 이 값이다.
#       8px 미만이면 '등급 표식이 사실상 없다'로 본다 (16x16에서 아이템은 60~90px이다).
def grade_delta():
    out = []
    for s in WEAPON_SERIES:
        for i in range(len(WEAPON_GRADES) - 1):
            wa, ha, ra = read_png(ITEM / "weapon" / f"{s}_{WEAPON_GRADES[i]}.png")
            wb, hb, rb = read_png(ITEM / "weapon" / f"{s}_{WEAPON_GRADES[i + 1]}.png")
            d = 0
            for y in range(16):
                for x in range(16):
                    pa, pb = px(ra, x, y), px(rb, x, y)
                    va, vb = pa[3] > 8, pb[3] > 8
                    if va != vb or (va and abs(luma(pa) - luma(pb)) >= 16):
                        d += 1
            out.append((d, s, WEAPON_GRADES[i], WEAPON_GRADES[i + 1]))
    return out


def cross_checks():
    """관계 축 3종 (계열 실루엣 · 등급 변별) — 파일 하나로는 볼 수 없는 결함."""
    violations = 0
    print("\n── 계열 실루엣 자카드 (병기 9계열 × 36쌍) ──")
    js = series_jaccard()
    for v, a, b in js:
        if v > JACCARD_MAX:
            violations += 1
            print(f"  ❌ {a} ↔ {b}: J {v:.3f} > {JACCARD_MAX} (실루엣이 겹쳐 계열이 안 갈린다)")
    print(f"  최대 {js[0][0]:.3f} ({js[0][1]}↔{js[0][2]}) · 중앙 {js[len(js) // 2][0]:.3f}"
          f" · 최소 {js[-1][0]:.3f} ({js[-1][1]}↔{js[-1][2]})")

    print("\n── 등급 회색조 변별 (인접 등급 27쌍) ──")
    gd = grade_delta()
    for d, s, g0, g1 in gd:
        if d < GRADE_DELTA_MIN:
            violations += 1
            print(f"  ❌ {s} {g0}→{g1}: {d}px < {GRADE_DELTA_MIN} (색을 빼면 같은 물건)")
    vals = sorted(d for d, *_ in gd)
    print(f"  최소 {vals[0]}px · 중앙 {vals[len(vals) // 2]}px · 최대 {vals[-1]}px")
    return violations


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

    sp = str(path).replace("\\", "/")
    extra = {}
    if "/textures/item/" in sp:                       # 축 6 — 외곽선 폐합
        brk, tot = outline_break(rows, w, h)
        extra["외곽"] = f"{brk}/{tot}"
        if brk:
            notes.append(f"외곽선 끊김 {brk}px (경계 {tot}px 중 — 밝은 픽셀이 배경에 그대로 노출)")
            bad = True
    if name in TILING_BLOCKS and (w, h) == (16, 16):  # 축 7 — 자기 복제 상관
        rep, (dx, dy), hm, vm = replication(rows, w, h)
        extra["복제"] = f"{rep:.2f}@({dx},{dy}) 가로 {hm:.2f} 세로 {vm:.2f}"
        if rep > REPLICATION_MAX:
            notes.append(f"자기 복제 r={rep:.3f} > {REPLICATION_MAX} @시프트({dx},{dy}) "
                         f"— 블록이 제 안에서 스스로를 복사한다 (벽에 격자가 뜬다)")
            bad = True
    if "/gui/sprites/hud/" in sp:                     # 축 8 — 양배경 가독
        lo, hi = bg_legibility(rows, w, h)
        extra["가독"] = f"백 {lo}px / 흑 {hi}px"
        if min(lo, hi) < UI_LEGIBLE_MIN:
            weak = "밝은 배경" if lo < hi else "어두운 배경"
            notes.append(f"양배경 가독 백 {lo}px / 흑 {hi}px — {weak}에서 읽히는 픽셀이 "
                         f"{min(lo, hi)} < {UI_LEGIBLE_MIN} (스프라이트가 배경에 잠긴다)")
            bad = True

    return name, notes, bad, dict(colors=colors, contrast=contrast, chroma=chroma, seam=seam,
                                  avg=tuple(round(v) for v in avg), extra=extra)


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
                tail = "".join(f" · {k} {v}" for k, v in m.get("extra", {}).items())
                print(f"  ✅ {name}: 색 {m['colors']} · 명암 {m['contrast']:.0f}"
                      f" · 채도 {m['chroma']:.0f} · 이음매 {m['seam']:.2f} · 평균 {m['avg']}{tail}")

    violations += cross_checks()

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
