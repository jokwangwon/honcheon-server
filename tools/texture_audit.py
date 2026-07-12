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
import json
import re
import struct
import sys
import urllib.request
import zipfile
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / "resourcepack" / "assets"
OUT = ROOT / "run" / "texture-review"
ITEM = PACK / "honcheon" / "textures" / "item"
BLOCK = PACK / "minecraft" / "textures" / "block"

# ─── 축 ⑪ 의 재료 — 조성기(읽기 전용)와 바닐라 클라이언트 jar ───
BUILDERS = [ROOT / "server-mvt" / "src" / "main" / "java" / "com" / "honcheon" / "mvt" / f
            for f in ("CheonghaBuilder.java", "RemoteBuilder.java")]
MC_VERSION = "1.21.11"                      # build_resourcepack.PACK_FORMAT 75 와 같은 클라이언트
JAR_CACHE = ROOT / "run" / "client" / f"client-{MC_VERSION}.jar"   # run/ 은 gitignore 대상
MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

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

# ─── 3차 검수 축 (2026-07) — 축 ⑪ 조성 팔레트 커버리지 ───────────────────────
# 앞선 열 축은 전부 **팩 안**만 본다: 팩이 그린 장을 잰다. 그래서 팩이 **그리지 않은 것**은
# 영원히 안 보인다 — 위반 0건인 채로 세계의 절반이 바닐라일 수 있다 (실제로 그랬다: 11.4%).
# 이 축만이 팩 밖을 본다: 조성기가 세우는 마을에서 **플레이어의 눈에 닿는 픽셀 중 몇 %가
# 팩이 그린 것인가**. 검수가 못 재는 축은 다음 사이클에 조용히 무너지므로, 손계산으로
# 굴러다니던 이 수치를 자로 만든다.
COVERAGE_MIN = 0.80       # 가중 커버리지 하한 — 이보다 낮으면 '기와만 얹은 마인크래프트 마을'이다

# ─── 모델 JSON 이 답하지 않는 두 부류 — 명시적으로 적는다 (조용히 분모에서 빠지면 자가 눈감는다) ───
# ① 유체: blockstate → model 에 elements 가 없다 (특수 렌더러가 그린다). 그대로 두면 물(19회)이
#    면적 0으로 사라진다. 큐브 1개(6면)로 친다.
FLUIDS = {"water": "block/water_still", "lava": "block/lava_still",
          "bubble_column": "block/water_still"}

# ② 블록 엔티티: 궤·항아리·현판은 블록이되 **BlockEntityRenderer** 가 그린다 — 모델 JSON 에
#    elements 가 없고 텍스처는 entity/ 아래 산다. 면적은 모델이 아니라 **바운딩 박스**에서 낸다
#    (렌더러의 박스 치수는 자바 코드에 있어 JSON 으로 못 읽는다 — 아래 수치는 그 박스의 기하다).
BLOCK_ENTITIES = {
    "chest":         {"entity/chest/normal": 6 * 14 * 14},          # 14³ 궤 — 6면
    "decorated_pot": {"entity/decorated_pot/decorated_pot_base": 2 * 14 * 14,   # 목·굽 (상·하면)
                      "entity/decorated_pot/decorated_pot_side": 4 * 14 * 16},  # 배 4면 (무늬 없는 항아리도 이 텍스처)
    "oak_sign":      {"entity/signs/oak": 2 * 16 * 8},              # 판 앞뒤 (2/3 축소 렌더 반영)
    "oak_wall_sign": {"entity/signs/oak": 2 * 16 * 8},
    "dark_oak_hanging_sign": {"entity/signs/hanging/dark_oak": 2 * 16 * 10},
}

# 면 → 그 면의 넓이를 재는 두 축 (모델 element 는 from/to 의 직육면체다)
FACE_AXES = {"up": ("x", "z"), "down": ("x", "z"), "north": ("x", "y"),
             "south": ("x", "y"), "east": ("z", "y"), "west": ("z", "y")}

WEAPON_SERIES = ["sword", "dao", "spear", "gauntlet", "dagger", "bu", "gyeom", "wolasan", "gu"]
# 마병(魔兵)이 이 목록에 들어왔다 (2026-07). 그전까지 축 ⑩은 **네 등급만** 재고 있었고,
# 그 사이 팩은 마병을 도(刀) 한 자루만 굽고 있었다 — 즉 나머지 여덟 계열의 마병은
# **재는 자도 굽는 자도 없었다**. Weapons.java 는 45개의 item_model 키를 전부 박는데도.
# 이제 45자루를 다 굽고, 축 ⑩도 45자루를 다 잰다 (인접 쌍 27 → 36).
WEAPON_GRADES = ["beomcheol", "jeongryeon", "bobyeong", "sinbyeong", "mabyeong"]

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

# ─── 이음매(SEAM) 축의 적용 범위 — '면'인가 '스프라이트'인가 (2026-07) ───
# 이음매는 "이어 붙였을 때 랩 경계가 배신하는가"를 묻는다. 그 물음은 **면 텍스처**에만 뜻이 있다.
# 십자 모델에 매달려 홀로 서는 스프라이트(꽃·횃불·초·작물·버섯)는 **제 복사본과 이어 붙지 않는다.**
#
# 그리고 여기 함정이 있다 — 바닐라 UV 계약이 스프라이트를 **텍스처 가장자리에 붙여 놓는다**:
#   candle.png 의 촛대는 x0..1, 즉 **왼쪽 변에 딱 붙어** 있다 (바닐라 실측).
#   그 계약을 지키면 랩 경계 차이가 필연적으로 커진다 → **UV를 지키면 이음매 축이 반드시 운다.**
#   축을 달래려고 촛대를 가운데로 옮기면? 모델이 x0..1 을 읽으므로 **초가 사라진다.**
#   계약이 지표를 이긴다. 그러므로 우는 쪽은 축이다 — 축의 적용 범위를 좁힌다.
# (이 팩의 규율: "축을 아무 데나 들이대면 거짓 위반이 뜨고, 거짓 위반은 루프를 헛돌린다".)
#
# 판정: 불투명 ≥ 50% = 면 / 그 아래 = 스프라이트.
#       불투명은 낮으나 **실제로 이어 붙는 격자면**(창살·잎·사다리)은 명시 등록으로 되돌린다.
SEAM_FACE_MIN_OPAQUE = 0.5
SEAM_FACES = {"iron_bars", "cherry_leaves", "ladder"}


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


# ═══════════════════════════════════════════════════════════════════════════
# 축 ⑪. 조성 팔레트 커버리지 — 「팩이 세계를 얼마나 덮는가」
#
# ── 무엇을 재는가 ──
# 물음은 "팩이 몇 장을 그렸나"가 아니다 (그건 자화자찬이다). 물음은:
#   **청하현에 선 플레이어의 눈에 닿는 픽셀 중 몇 %가 팩이 그린 것인가.**
# 그래서 세 가지를 곱한다 — 빈도 × 면적 × 불투명도.
#
# ① 빈도(頻度): 조성기(CheonghaBuilder·RemoteBuilder)의 코드에서 `Material.XXX` 를 센다.
#    주석·문자열을 걷어낸 뒤 세므로 죽은 코드가 표를 흔들지 않는다. 이것은 '설치 횟수'가
#    아니라 '코드가 그 블록을 부르는 횟수'다 — 대리 지표다. 루프 안의 한 줄이 벽 하나를
#    통째로 세우므로 절대량은 틀리지만, **블록끼리의 상대 비중**은 남는다 (자재층은 루프로,
#    기물은 낱개로 놓이는 경향이 서로를 상쇄하지 않고 같은 방향으로 어긋나는 일이 없다).
#
# ② 면적(面積): **여기가 이 축의 핵심이다.** 벽·바닥·지붕은 크고 항아리는 작다 — 그 차이를
#    손으로 등급 매기지 않는다 (손 등급은 다음 사람이 못 재는 또 하나의 손계산이다).
#    1.21.11 클라이언트 jar 의 **모델 JSON 이 이미 그 답을 갖고 있다**: element 의 from/to 가
#    직육면체를 주고, 각 face 가 어느 텍스처를 어느 넓이로 쓰는지 말한다.
#      · 온전한 큐브 = 6면 × 256 = 1536 (16단위 공간)
#      · 울타리 기둥 = 4×16×4 → 288  (벽의 1/5)
#      · 화분 = 더 작다. 손으로 "항아리는 0.3" 이라 적을 필요가 없다 — 기하가 말한다.
#    이 방식은 덤으로 **블록 하나가 텍스처 여러 장을 쓰는 문제**를 정확히 푼다: 온전한 큐브의
#    옆면 텍스처(*_side)는 4면 = 1024 를 먹고 윗면(*_top)은 1면 = 256 이다. 즉 *_side 를
#    덮는 것이 *_top 을 덮는 것보다 4배 값진 일이고, 자가 그렇게 센다.
#
# ③ 불투명도: 컷아웃 텍스처(풀·유리·창살·거미줄)는 제 사각형을 다 안 채운다. 바닐라 PNG 의
#    불투명 픽셀 비율을 곱해, 뚫린 자리를 '덮어야 할 화면'으로 세지 않는다.
#
# ── 판정(무엇을 '덮었다'고 하는가) ──
# blockstate → (variants | multipart) → model → parent 사슬 → textures 사전 → #참조 해소.
# 짐작 금지: 1.21.11 jar 를 실제로 풀어 읽는다 (piston-meta 로 받아 run/client/ 에 캐시).
#   · variants: 변종들의 **평균** (반블록 top/bottom/double 처럼 면적이 다른 변종이 있다)
#   · multipart: 무조건부 부분(울타리 기둥)은 1.0, 조건부 부분(연결 팔)은 0.5
#     — 담장 한 칸은 평균 두 방향으로 붙지 네 방향이 아니다
# 덮었다 = resourcepack/…/textures/block/<이름>.png 가 실재한다.
#
# ── 이 축의 정직성 ──
# 손계산 84.7% 가 맞았는지를 이 자가 심판한다. 아래 세 부류는 분모에서 **명시적으로** 뺀다
# (조용히 빠지는 것이 아니라 이름과 함께 보고된다 — 눈감는 자리를 만들지 않는다):
#   · AIR — 그림이 없다 (모델에 element 가 없어 면적 0. 자연히 빠진다)
#   · 아이템(HONEY_BOTTLE 등) — blockstate 가 없다. 블록 축이 아니다
#   · 모델 미해석 — 있으면 소리친다 (자의 고장을 침묵으로 넘기지 않는다)
# ═══════════════════════════════════════════════════════════════════════════
def builder_materials():
    """조성기가 부르는 Material 과 그 빈도 — 주석·문자열을 걷어낸 뒤 센다."""
    counts = {}
    for f in BUILDERS:
        if not f.exists():
            continue
        src = f.read_text(encoding="utf-8")
        src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)     # 블록 주석
        src = re.sub(r"//[^\n]*", "", src)                  # 줄 주석
        src = re.sub(r'"(?:[^"\\]|\\.)*"', '""', src)       # 문자열 리터럴
        for m in re.findall(r"Material\.([A-Z0-9_]+)", src):
            counts[m] = counts.get(m, 0) + 1
    return counts


def client_jar():
    """1.21.11 클라이언트 jar — run/client/ 에 캐시. 없으면 piston-meta 로 받는다."""
    if JAR_CACHE.exists():
        return zipfile.ZipFile(JAR_CACHE)
    JAR_CACHE.parent.mkdir(parents=True, exist_ok=True)
    man = json.load(urllib.request.urlopen(MANIFEST, timeout=30))
    entry = next(v for v in man["versions"] if v["id"] == MC_VERSION)
    meta = json.load(urllib.request.urlopen(entry["url"], timeout=30))
    url = meta["downloads"]["client"]["url"]
    print(f"  클라이언트 jar 내려받는 중 ({MC_VERSION}) … {url}")
    urllib.request.urlretrieve(url, JAR_CACHE)
    return zipfile.ZipFile(JAR_CACHE)


def png_opacity(data):
    """PNG 의 불투명 픽셀 비율. 바닐라는 팔레트(ct3)·회색조(ct0)·RGB(ct2)가 섞여 있다 —
    팩의 read_png(RGBA8 전용)로는 못 읽는다. 알파만 필요하므로 최소 해석기를 따로 둔다."""
    pos, w, h, idat, ct, bd, trns = 8, 0, 0, b"", 6, 8, None
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        d = data[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            w, h, bd, ct = struct.unpack(">IIBB", d[:10])
        elif typ == b"tRNS":
            trns = d
        elif typ == b"IDAT":
            idat += d
        pos += 12 + ln
    if bd != 8 or ct in (0, 2) or (ct == 3 and not trns):
        return 1.0                                   # 알파 채널이 없다 = 전면 불투명
    ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ct]
    raw = zlib.decompress(idat)
    stride = w * ch
    prev, i, op, tot = bytearray(stride), 0, 0, 0
    for _ in range(h):
        f = raw[i]; i += 1
        line = bytearray(raw[i:i + stride]); i += stride
        for x in range(stride):
            a = line[x - ch] if x >= ch else 0
            b = prev[x]
            c = prev[x - ch] if x >= ch else 0
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
        for x in range(w):
            tot += 1
            al = trns[line[x]] if ct == 3 and line[x] < len(trns) else (
                255 if ct == 3 else line[x * ch + ch - 1])
            if al > 8:
                op += 1
        prev = line
    return op / tot if tot else 1.0


class Vanilla:
    """클라이언트 jar 조회 — 모델·blockstate·텍스처 불투명도 (전부 캐시)."""

    def __init__(self):
        self.z = client_jar()
        self.names = set(self.z.namelist())
        self._model, self._opac = {}, {}

    def load(self, path):
        return json.loads(self.z.read(path)) if path in self.names else None

    def model(self, ref):
        ref = ref.split(":")[-1]
        if ref not in self._model:
            self._model[ref] = self.load(f"assets/minecraft/models/{ref}.json") or {}
        return self._model[ref]

    def opacity(self, tex):
        tex = tex.split(":")[-1]
        if tex not in self._opac:
            path = f"assets/minecraft/textures/{tex}.png"
            self._opac[tex] = png_opacity(self.z.read(path)) if path in self.names else 1.0
        return self._opac[tex]

    def textures(self, ref):
        """부모 사슬을 타고 textures 사전을 합친다 (자식이 이긴다)."""
        m = self.model(ref)
        out = {}
        if "parent" in m:
            out.update(self.textures(m["parent"]))
        out.update(m.get("textures", {}))
        return out

    def elements(self, ref):
        m = self.model(ref)
        if "elements" in m:
            return m["elements"]
        return self.elements(m["parent"]) if "parent" in m else []

    def model_area(self, ref):
        """모델 하나가 텍스처마다 그리는 면적 — 큐브 한 면 = 256 (16단위 공간)."""
        tex, out = self.textures(ref), {}
        for el in self.elements(ref):
            f = el.get("from", [0, 0, 0])
            t = el.get("to", [16, 16, 16])
            dim = {"x": abs(t[0] - f[0]), "y": abs(t[1] - f[1]), "z": abs(t[2] - f[2])}
            for face, fd in (el.get("faces") or {}).items():
                ax = FACE_AXES.get(face)
                if not ax:
                    continue
                area = dim[ax[0]] * dim[ax[1]]
                if area <= 0:
                    continue
                name = fd.get("texture", "")
                for _ in range(10):                      # #참조 해소 (#all → #texture → block/x)
                    if not (isinstance(name, str) and name.startswith("#")):
                        break
                    name = tex.get(name[1:], "")
                if not isinstance(name, str) or not name:
                    continue
                name = name.split(":")[-1]
                out[name] = out.get(name, 0.0) + area * self.opacity(name)
        return out

    def block_area(self, block):
        """blockstate → 텍스처별 면적. None = blockstate 없음(= 블록이 아니다)."""
        bs = self.load(f"assets/minecraft/blockstates/{block}.json")
        if bs is None:
            return None
        parts = []
        if "variants" in bs:
            vs = bs["variants"]
            for v in vs.values():
                if isinstance(v, list):
                    v = v[0]                             # 랜덤 회전 목록 — 면적은 같다
                if isinstance(v, dict) and v.get("model"):
                    parts.append((v["model"], 1.0 / max(1, len(vs))))   # 변종 평균
        for part in bs.get("multipart", []):
            ap = part["apply"]
            if isinstance(ap, list):
                ap = ap[0]
            if ap.get("model"):
                # 무조건부(기둥)는 늘 서고, 조건부(연결 팔)는 평균 2/4 방향만 붙는다
                parts.append((ap["model"], 1.0 if "when" not in part else 0.5))
        out = {}
        for ref, w in parts:
            for tex, area in self.model_area(ref).items():
                out[tex] = out.get(tex, 0.0) + area * w
        return out


# ═══════════════════════════════════════════════════════════════════════════
# 축 ⑫. 밝기 대역 — 「수묵은 어두운 것이 아니라 **농담(濃淡)** 이다」 (2026-07)
#
# ── 왜 이 축이 생겼나 (실패의 기록) ──
# 사용자 보고: *"전체적으로 어둡고 칙칙한 분위기, 심지어 벚꽃나무도 분홍빛에서 검은 빛이 더 많이 드는 느낌"*
# 실측이 그를 뒷받침했다: 블록 209장 평균 밝기 **113** · 매화 잎 **73**(먹빛 갈색).
#
# 우리는 "채색 금지"를 **"전부 어둡게"** 로 번역했다. 그것은 오역이다.
#   수묵의 본질은 **여백(밝은 종이)과 먹(짙은 획)의 대비**다. 종이까지 잿빛으로 칠하면
#   먹은 더 이상 먹이 아니다 — 대비가 없으면 농담도 없고, 남는 것은 그을음뿐이다.
#
# 그리고 이 실패가 **왜 여기까지 왔는가**: 아무도 밝기를 재지 않았다.
#   색 수·명암차·채도·이음매·자기복제·외곽선·양배경·자카드·등급변별·커버리지는 재면서
#   **"전체가 너무 어두운가"** 는 아무도 묻지 않았다. 등롱 때와 똑같은 실패다
#   (그때도 검수가 어둠을 못 잡아 조성기가 등을 계속 꽂았다).
#   **재지 않는 축은 조용히 무너진다.** 그래서 잰다.
#
# ── 무엇을 재는가 (셋) ──
#   ⑫-a 자재별 밝기 계약 — 자재마다 **다른 대역**이다. 일괄 보정은 또 다른 거짓말이다:
#        기와까지 밝히면 검은 기와가 아니고, 회벽을 어둡히면 회벽이 아니다.
#        **분류되지 않은 텍스처 = 위반** (구멍이 있으면 그 구멍으로 어둠이 새어든다).
#   ⑫-b 전역 밝기 — 블록 텍스처 평균이 대역 안인가 (세계의 인상은 평균이 만든다).
#   ⑫-c 허락된 채색의 **채도 하한** — 규약이 채색을 허락한 자리(매화·등롱·차양·불)에서
#        색이 죽으면 **규약이 자기모순**이다. 매화가 먹빛 갈색이면 "채색은 매화뿐"이 무슨 말인가.
#
# ── 대역의 근거 ──
# 마을을 걷는 눈에 가장 크게 닿는 면은 **목재 52장 · 돌 24 · 땅 12** 다. 그것들이 **종이** 노릇을
# 해야 지붕의 먹(54)과 처마 그림자가 산다. 그래서 그 셋의 대역이 가장 높고, 기와·심층암은 낮다.
# 밝기 계약은 **면(面)의 규율**이다 — 세계를 덮는 것은 면이다. 유리·거미줄·철창 같은
# **획(劃)·투명 요소**는 어두운 선이 정답이므로 대역에서 제외하되, **제외도 등록제**로 적는다.
LUMA_BANDS = [
    # (자재, 하한, 상한, 패턴들, 근거)
    ("먹_기와·심층암", 42, 88, ["deepslate*", "cracked_deepslate*", "cobbled_deepslate"],
     "검은 기와는 **검어야 한다**. 이것이 이 세계의 먹이다 — 밝히면 그것이 거짓말이다"),
    ("무쇠·화로", 55, 130,
     ["anvil*", "iron_bars", "iron_chain", "cauldron_*", "hopper_*", "blast_furnace_*",
      "smoker_*", "brewing_stand*", "tripwire*", "campfire_log"],
     "쇠와 아궁이는 어둡다. 다만 순먹은 구멍으로 보인다 (하한 55)"),
    ("매화_꽃", 150, 215, ["cherry_leaves", "cherry_sapling"],
     "★ 채색이 허락된 유일한 자리. 수관 전체를 도배하는 면이므로 **나무 한 그루가 분홍으로 서야** 한다"),
    ("매화_가지", 95, 165, ["cherry_log", "cherry_log_top", "cherry_shelf"],
     "먹빛 가지 — **꽃의 짝**이다. 어두워야 분홍이 산다 (대비가 곧 수묵)"),
    ("등불·불", 105, 235, ["lantern", "soul_lantern", "candle", "candle_lit", "torch",
                          "campfire_log_lit"],
     "불은 **빛나는 것**이다. 어두운 등롱은 등롱이 아니다"),
    ("천_깃발·차양", 85, 200,
     ["red_wool", "orange_wool", "yellow_wool", "green_wool", "lime_wool", "cyan_wool",
      "light_blue_wool", "brown_wool"],
     "차양·깃발 — 채색 허용. 밝기보다 **채도**가 본체다 (⑫-c 가 그것을 잰다)"),
    ("여백_회벽·눈·얼음", 160, 240,
     ["white_terracotta", "light_gray_terracotta", "calcite", "snow", "powder_snow",
      "grass_block_snow", "*ice*", "white_wool", "light_gray_wool", "bone_block_*"],
     "★ 이것이 **종이**다. 여백이 밝지 않으면 먹이 먹으로 읽히지 않는다"),
    ("짚·죽", 125, 195,
     ["hay_block_*", "bamboo_planks", "bamboo_shelf", "bamboo_singleleaf", "bamboo_stalk",
      "bamboo_fence", "scaffolding_*"],
     "마른 짚은 햇빛에 바랜다. 초가지붕이 밝아야 **기와(54)와 갈린다** — 두 지붕은 다른 계층이다"),
    ("목재", 105, 175,
     ["*_planks", "*_log", "*_log_top", "stripped_*", "*_trapdoor", "*_shelf", "barrel_*",
      "lectern_*", "crafting_table_*", "loom_*", "composter_*", "smithing_table_*",
      "chiseled_bookshelf_*", "flower_pot"],
     "★ 조성 팔레트 **최대 면적(52장)** — 벽·바닥·계단·울타리가 전부 이 몇 장을 쓴다. "
     "여기가 어두우면 마을 전체가 그을음이다"),
    ("돌", 115, 195,
     ["stone", "stone_bricks", "cracked_stone_bricks", "chiseled_stone_bricks",
      "mossy_stone_bricks", "cobblestone", "mossy_cobblestone", "andesite", "polished_andesite",
      "diorite", "granite", "smooth_stone*", "bricks", "mud_bricks", "tuff", "sandstone*",
      "red_sandstone*", "terracotta", "clay"],
     "사람이 깎은 돌 — 계단·단·담장. 무채색이되 **밝은 회색**이다 (돌은 먹이 아니다)"),
    ("땅", 100, 170,
     ["dirt", "coarse_dirt", "rooted_dirt", "dirt_path_*", "farmland*", "gravel", "sand",
      "red_sand", "packed_mud", "mud"],
     "밟혀 **마른** 흙은 밝다. 젖은 먹빛 흙이 마을을 그을음으로 만들었다"),
    ("삭은땅_부엽토·이끼", 72, 130, ["podzol_*", "mycelium_*", "moss_block"],
     "부엽토는 **어두운 것이 정체다** (삭은 잎이 덮은 땅) — 밝히면 그것도 거짓말. 다만 60은 과했다"),
    ("초목_작물·꽃", 85, 200,
     ["wheat_*", "carrots_*", "potatoes_*", "beetroots_*", "sweet_berry_*", "*mushroom",
      "poppy", "dandelion", "cornflower", "azure_bluet", "oxeye_daisy", "white_tulip"],
     "작물과 꽃 — 폭이 넓다 (익은 밀은 밝고 어린 싹은 어둡다)"),
]
# 대역 제외 — **획(劃)·투명 요소**. 세계를 덮는 '면'이 아니라 배경 위에 긋는 '선'이라
# 어두운 것이 정답이다. 제외도 등록제다 (여기 없는 텍스처는 반드시 대역을 갖는다).
LUMA_EXEMPT = ["glass", "glass_pane_top", "cobweb", "dead_bush", "ladder", "glow_lichen",
               "sea_pickle", "torch"]
BLOCK_LUMA_MEAN = (128, 172)   # 전역 평균 밝기 대역 — 세계의 인상은 평균이 만든다
# ⑫-c 허락된 채색의 **채도 하한** — 색이 죽으면 규약이 자기모순이다
CHROMA_FLOOR = {
    "cherry_leaves": ("R-G", 42, "매화 — 채색이 허락된 유일한 자리. 붉은 기가 이 아래면 **색이 죽었다**"),
    "cherry_sapling": ("R-G", 30, "매화 묘목 — 꽃이 핀 어린 나무 (먹빛 삭정이가 아니다)"),
    "cherry_log": ("R-G", 12, "매화 가지 — 먹빛이되 붉은 기가 남는다"),
    "red_wool": ("chroma", 60, "차양(붉은 천) — 붉어야 차양이다"),
    "lantern": ("chroma", 32, "등롱 — 유등의 난색"),
    "campfire_log_lit": ("chroma", 30, "타는 장작 — 불은 의미다"),
}


def _tex_stats(f):
    w, h, rows = read_png(f)
    ps = [px(rows, x, y) for y in range(h) for x in range(w) if px(rows, x, y)[3] > 8]
    if not ps:
        return None
    n = len(ps)
    avg = [sum(p[i] for p in ps) / n for i in range(3)]
    return (sum(luma(p) for p in ps) / n, max(avg) - min(avg), avg[0] - avg[1])


def brightness_bands():
    """축 ⑫ — 밝기 대역 · 자재별 계약 · 허락된 채색의 채도 하한. 위반 수를 돌려준다."""
    import fnmatch
    print("\n── 축 ⑫ 밝기 대역 (수묵 = 여백과 먹의 대비. '전부 어둡게'가 아니다) ──")
    B = PACK / "minecraft" / "textures" / "block"
    files = sorted(B.glob("*.png"))
    violations = 0
    by_class, unclassified, lumas = {}, [], []

    for f in files:
        st = _tex_stats(f)
        if st is None:
            continue
        L, chroma, rg = st
        if any(fnmatch.fnmatch(f.stem, p) for p in LUMA_EXEMPT):
            continue                                     # 획·투명 요소 — 등록된 제외
        for cls, lo, hi, pats, why in LUMA_BANDS:
            if any(fnmatch.fnmatch(f.stem, p) for p in pats):
                by_class.setdefault(cls, (lo, hi, why, []))[3].append((f.stem, L))
                lumas.append(L)
                break
        else:
            unclassified.append(f.stem)

    # ⑫-a 자재별 밝기 계약
    for cls, lo, hi, pats, why in LUMA_BANDS:
        if cls not in by_class:
            continue
        _, _, _, items = by_class[cls]
        out = [(n, L) for n, L in items if not (lo <= L <= hi)]
        mean = sum(L for _, L in items) / len(items)
        mark = "❌" if out else "✅"
        print(f"  {mark} {cls:18s} [{lo:3d}–{hi:3d}]  n={len(items):3d} 평균 {mean:5.0f}")
        if out:
            violations += len(out)
            for n, L in sorted(out, key=lambda t: t[1])[:6]:
                side = "어둡다" if L < lo else "밝다"
                print(f"       ❌ {n:28s} 밝기 {L:5.0f} — 대역 밖({side}) · {why}")

    # 미분류 = 위반 (구멍이 있으면 그 구멍으로 어둠이 새어든다)
    if unclassified:
        violations += len(unclassified)
        print(f"  ❌ 미분류 {len(unclassified)}장 — **밝기 계약이 없는 텍스처** (등록되지 않은 것은 "
              f"재어지지 않고, 재어지지 않는 것은 조용히 무너진다): {unclassified[:8]}")
    else:
        print(f"  ✅ 분류 커버리지 {len(lumas)}/{len(lumas)} = 100% (제외 등록 {len(LUMA_EXEMPT)}종 — 획·투명 요소)")

    # ⑫-b 전역 평균
    gmean = sum(lumas) / len(lumas)
    lo, hi = BLOCK_LUMA_MEAN
    ok = lo <= gmean <= hi
    violations += 0 if ok else 1
    print(f"  {'✅' if ok else '❌'} 전역 평균 밝기 {gmean:.0f} — 대역 [{lo}–{hi}]"
          + ("" if ok else "  ← 세계가 통째로 어둡다 (사용자가 본 것이 이것이다)"))

    # ⑫-c 허락된 채색의 채도 하한
    print("  ── ⑫-c 허락된 채색 (매화·차양·등롱·불) — 색이 죽으면 규약이 자기모순이다 ──")
    for name, (kind, floor, why) in CHROMA_FLOOR.items():
        f = B / f"{name}.png"
        if not f.exists():
            continue
        L, chroma, rg = _tex_stats(f)
        v = rg if kind == "R-G" else chroma
        ok = v >= floor
        violations += 0 if ok else 1
        print(f"     {'✅' if ok else '❌'} {name:18s} {kind} {v:+5.0f} ≥ {floor:3d} · 밝기 {L:5.0f}"
              + ("" if ok else f"  ← **색이 죽었다** · {why}"))
    return violations


def palette_coverage():
    """축 ⑪ — 조성 팔레트 커버리지. (커버리지, 위반수) 반환."""
    print("\n── 축 ⑪ 조성 팔레트 커버리지 (빈도 × 면적 × 불투명도) ──")
    try:
        mc = Vanilla()
    except Exception as e:                               # 망 없음·jar 못 받음
        print(f"  ⚠ 클라이언트 jar 를 열 수 없다 ({e}) — 축 ⑪ **미측정**.")
        print(f"    이 축은 팩 밖(세계)을 보는 유일한 자다. 미측정은 통과가 아니다:")
        print(f"    {JAR_CACHE} 를 놓거나 망을 열고 다시 돌려라.")
        return None, 1                                   # 못 잰 것도 위반이다 (조용한 구멍 금지)

    # 팩이 덮은 것 = assets/minecraft/textures/ 아래 실재하는 PNG 의 상대 경로
    #   (block/ 만 보면 안 된다 — 궤·항아리·현판의 텍스처는 entity/ 아래 산다)
    root = PACK / "minecraft" / "textures"
    have = {str(p.relative_to(root).with_suffix("")).replace("\\", "/")
            for p in root.rglob("*.png")}
    mats = builder_materials()
    tex_area, blocks, nonblock, unresolved = {}, [], [], []

    for mat, freq in mats.items():
        name = mat.lower()
        if name == "air":
            continue                                     # 그림이 없다
        if name in FLUIDS:
            areas = {FLUIDS[name]: 6 * 256.0}            # 특수 렌더러 — 큐브 1개로 친다
        elif name in BLOCK_ENTITIES:
            areas = dict(BLOCK_ENTITIES[name])           # BlockEntityRenderer — 바운딩 박스 기하
        else:
            areas = mc.block_area(name)
            if areas is None:
                nonblock.append(mat)                     # blockstate 없음 = 아이템
                continue
            if not areas:
                unresolved.append(mat)
                continue
        tot = sum(areas.values())
        cov = sum(a for t, a in areas.items() if t in have)
        miss = sorted(t for t in areas if t not in have)
        blocks.append((freq * (tot - cov), mat, freq, cov / tot if tot else 1.0, miss))
        for t, a in areas.items():
            tex_area[t] = tex_area.get(t, 0.0) + freq * a

    total = sum(tex_area.values())
    covered = sum(a for t, a in tex_area.items() if t in have)
    ratio = covered / total if total else 0.0
    full = sum(1 for _, _, _, r, _ in blocks if r >= 0.999)

    print(f"  조성 팔레트: 블록 {len(blocks)}종 · 참조 텍스처 {len(tex_area)}장"
          f" · 팩 보유 PNG {len(have)}장")
    print(f"  가중 커버리지 = {ratio:.1%}  (덮은 면적 {covered:,.0f} / 총 {total:,.0f})")
    print(f"  (참고) 블록 단위 완전커버 = {full}/{len(blocks)} = {full / len(blocks):.1%}"
          f" — 가중치 없는 옛 셈법. 큰 면(벽·바닥)이 항아리와 같은 표를 갖는 셈이라 쓰지 않는다")

    holes = sorted(blocks, reverse=True)[:12]
    if holes and holes[0][0] > 0:
        print("  ── 남은 구멍 (미커버 면적 상위) ──")
        for gap, mat, freq, r, miss in holes:
            if gap <= 0:
                break
            print(f"    {gap:9,.0f}  {mat:24s} ×{freq:<3d} 커버 {r:4.0%}  결손: {', '.join(miss[:4])}")
    if nonblock:
        print(f"  분모 제외 — 아이템(blockstate 없음) {len(nonblock)}종: {', '.join(sorted(nonblock))}")
    if unresolved:
        print(f"  ❌ 모델 미해석 {len(unresolved)}종: {', '.join(sorted(unresolved))} (자의 고장 — 고쳐라)")

    if ratio < COVERAGE_MIN:
        print(f"  ❌ 가중 커버리지 {ratio:.1%} < {COVERAGE_MIN:.0%} — 세계가 아직 바닐라다")
        return ratio, 1 + len(unresolved)
    print(f"  ✅ 가중 커버리지 {ratio:.1%} ≥ {COVERAGE_MIN:.0%}")
    return ratio, len(unresolved)


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

    # 쌍 수는 세어서 적는다 — 등급이 늘었는데 라벨이 27에 머물면 **검수가 거짓말을 한다**
    print(f"\n── 등급 회색조 변별 (인접 등급 "
          f"{len(WEAPON_SERIES) * (len(WEAPON_GRADES) - 1)}쌍) ──")
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
    # 이음매는 **면**에만 묻는다 (SEAM_FACES 주석 — 스프라이트는 제 복사본과 이어 붙지 않는다)
    is_face = (name in TILING_BLOCKS or name in SEAM_FACES
               or len(pxs) / (w * h) >= SEAM_FACE_MIN_OPAQUE)
    if seam > SEAM_MAX and is_face:
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
    violations += brightness_bands()          # 축 ⑫ — 재지 않는 축은 조용히 무너진다
    _, cov_violations = palette_coverage()
    violations += cov_violations

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
