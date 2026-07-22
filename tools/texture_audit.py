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

WEAPON_SERIES = ["sword", "dao", "spear", "gauntlet", "dagger", "bu", "gyeom", "bong", "gu"]
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


# ═══════════════════════════════════════════════════════════════════════════
# 틴트 인지(認知) — 【2026-07 신설】 **재는 자가 파일을 재면, 파일은 맞고 세계는 틀린다.**
#
# 【이 축이 왜 생겼나 — 형광 초록은 왜 아무 축도 울리지 않았나】
#   검수는 열한 축을 재면서도 잔디가 형광인 것을 **한 번도 잡지 못했다.** 잡을 수가 없었다:
#   바닐라 grass_block_top.png 은 **회색조**다. 초록은 파일에 없다 — 엔진이 **곱한다**.
#     화면색 = 텍스처 × 틴트 ÷ 255
#   즉 파일만 재는 눈에게 잔디는 영원히 무채색이다. 검수는 통과를 외치고 세계는 형광이었다.
#   ★ 재지 않는 축은 조용히 무너진다 — 그런데 이 축은 **재는 척하면서** 무너져 있었다. 더 나쁘다.
#
# 【그래서 잰다】 블록을 읽을 때 **엔진이 곱할 것을 우리도 곱한다.** 그 다음에 모든 축을 들이댄다.
#   ⇒ 물의 PNG 는 금빛이지만(역틴트) 검수가 보는 것은 **비취빛**이다. 파일이 아니라 **눈에 닿는 색**.
#
# 【틴트의 출처 — jar 로 확인한 세 갈래】
#   ① 컬러맵 (팩이 쥔다)  : grass.png / foliage.png  → 잔디·풀·고사리·덩굴·참나무잎·수수깡
#      ★ 우리가 **직접 구운 컬러맵을 읽어서** 곱한다 — 팩이 실제로 배포하는 그 값이다 (닫힌 고리).
#   ② 코드 상수 (못 쥔다) : 가문비 0x619961 · 자작 0x80A755 (FoliageColor)
#   ③ 바이옴 데이터(못 쥔다): 물 water_color 0x3F76E4
#   ②③ 은 **역틴트**로 되돌린 자리다 — 그 되돌림이 맞았는지 여기서 검산된다.
# ═══════════════════════════════════════════════════════════════════════════
# 기준 바이옴 — **어디서 재는지 못 박지 않으면 숫자가 뜻을 잃는다** (컬러맵은 바이옴마다 다른 값을 준다).
#   청하현이 선 자리 = 평원 (기온 0.8 · 강수 0.4).
#   검산: 바닐라 grass.png 를 이 색인으로 읽으면 #91BD59 가 나온다 — 우리가 아는 평원의 그 초록이다.
REF_BIOME = (0.8, 0.4)
WATER_BIOME_TINT = (63, 118, 228)      # 0x3F76E4 — 바닐라 바이옴 water_color (커스텀 바이옴 0개 확인)
TINT_CONST = {                          # 코드에 박힌 상수 — 팩이 못 바꾼다 ⇒ 역틴트의 대상
    "spruce_leaves": (97, 153, 97),     # FoliageColor 0x619961
    "birch_leaves": (128, 167, 85),     # FoliageColor 0x80A755
    "lily_pad": (32, 128, 48),          # 0x208030 — ★ R 의 천장이 32 다 (저채도가 **불가능**한 자리)
    "water_still": WATER_BIOME_TINT,
    "water_flow": WATER_BIOME_TINT,
}
TINT_GRASS = ["grass_block_top", "grass_block_side_overlay", "short_grass", "fern",
              "tall_grass_bottom", "tall_grass_top", "large_fern_bottom", "large_fern_top",
              "sugar_cane"]
TINT_FOLIAGE = ["oak_leaves", "vine"]
# 프레임 시트 — 애니메이션. **첫 프레임만 잰다** (16x512 를 통째로 재면 32장을 한 장으로 뭉갠다).
ANIMATED = {"water_still": 32, "water_flow": 32, "lava_still": 20, "lava_flow": 16,
            "campfire_fire": 8, "soul_campfire_fire": 8,
            "campfire_log_lit": 4, "soul_campfire_log_lit": 4,
            "kelp": 20, "kelp_plant": 20, "seagrass": 18,
            "tall_seagrass_top": 19, "tall_seagrass_bottom": 19}
_CMAP_CACHE = {}


def colormap_tint(kind):
    """우리가 **실제로 구운** 컬러맵에서 기준 바이옴의 틴트를 뽑는다 (닫힌 고리 — 팩이 곧 진실)."""
    if kind not in _CMAP_CACHE:
        f = PACK / "minecraft" / "textures" / "colormap" / f"{kind}.png"
        if not f.exists():
            _CMAP_CACHE[kind] = None                    # 컬러맵이 없으면 바닐라가 산다 = 형광
        else:
            t, d = REF_BIOME
            w, h, rows = read_png(f)
            x = int((1.0 - t) * 255)
            y = int((1.0 - d * t) * 255)
            _CMAP_CACHE[kind] = tuple(px(rows, x, y)[:3])   # px() 는 바이트열을 준다 — 튜플로 (보고용)
    return _CMAP_CACHE[kind]


def tint_of(name):
    """이 텍스처에 엔진이 곱할 틴트 (없으면 None)."""
    if name in TINT_CONST:
        return TINT_CONST[name]
    if name in TINT_GRASS:
        return colormap_tint("grass")
    if name in TINT_FOLIAGE:
        return colormap_tint("foliage")
    return None


def read_block(path, name):
    """블록을 **눈에 닿는 모습**으로 읽는다 — ① 프레임 시트는 첫 장만 ② 틴트를 곱한다."""
    w, h, rows = read_png(path)
    if name in ANIMATED:
        rows = rows[:w]                       # 첫 프레임 (프레임은 세로로 쌓인다)
        h = w
    tint = tint_of(name)
    if tint:
        # 행은 **평면 바이트열**이다 (px() 가 4바이트씩 끊어 읽는다) — 그 표현을 지켜서 곱한다.
        # 알파(4번째)는 곱하지 않는다: 틴트는 색만 물들인다 (물의 알파 180 이 여기서 살아남는다).
        out = []
        for row in rows:
            b = bytearray(row)
            for i in range(0, len(b), 4):
                for c in range(3):
                    b[i + c] = min(255, b[i + c] * tint[c] // 255)
            out.append(bytes(b))
        rows = out
    return w, h, rows


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
      "smoker_*", "brewing_stand*", "tripwire*", "campfire_log",
      "smithing_table_front", "smithing_table_top"],
     "쇠와 아궁이는 어둡다. 다만 순먹은 구멍으로 보인다 (하한 55). "
     "대장간 상판·앞면은 metal_rows(IRON) 이다 — 목재가 아니다 (축 ⑫가 우리 분류 오류를 잡아냈다)"),
    ("그릇 속(내부·구멍)", 40, 112,
     ["barrel_top_open", "cauldron_inner", "hopper_inside"],
     "속을 들여다보는 면 — **어두운 것이 정체다**. 밝히면 그릇에 바닥이 없다 (면이 아니라 깊이다)"),
    ("젖은 땅·삭은 거름", 78, 138,
     ["mud", "farmland_moist", "composter_compost", "composter_ready"],
     "물과 삭음은 **빛을 먹는다** — 마른 흙보다 어두운 것이 정체다 (진흙·젖은 밭·거름). "
     "다만 65는 과했다: 어둠에도 바닥이 있어야 한다"),
    ("매화_꽃", 150, 215, ["cherry_leaves", "cherry_sapling"],
     "★ 채색이 허락된 유일한 자리. 수관 전체를 도배하는 면이므로 **나무 한 그루가 분홍으로 서야** 한다"),
    ("매화_가지", 95, 165, ["cherry_log", "cherry_log_top", "cherry_shelf"],
     "먹빛 가지 — **꽃의 짝**이다. 어두워야 분홍이 산다 (대비가 곧 수묵)"),
    ("등불·불", 105, 235, ["lantern", "soul_lantern", "candle", "candle_lit", "torch",
                          "campfire_log_lit", "campfire_fire", "lava_still", "lava_flow",
                          "soul_campfire_fire", "soul_campfire_log_lit"],
     "불은 **빛나는 것**이다. 어두운 등롱은 등롱이 아니다 (용암·불꽃도 같은 자리 — 불은 의미다)"),
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
    ("자작(白樺)", 150, 215, ["birch_log", "birch_log_top"],
     "자작나무 껍질은 **희다** — 그것이 이름이다. 목재 대역에 가두면 자작이 아니라 그냥 나무다"),
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
      "red_sand", "packed_mud", "mud", "grass_block_side"],   # 잔디 옆면 '바탕'은 흙이다 (틴트 없음)
     "밟혀 **마른** 흙은 밝다. 젖은 먹빛 흙이 마을을 그을음으로 만들었다"),
    ("삭은땅_부엽토·이끼", 72, 130, ["podzol_*", "mycelium_*", "moss_block"],
     "부엽토는 **어두운 것이 정체다** (삭은 잎이 덮은 땅) — 밝히면 그것도 거짓말. 다만 60은 과했다"),
    ("초목_작물·꽃", 85, 200,
     ["wheat_*", "carrots_*", "potatoes_*", "beetroots_*", "sweet_berry_*", "*mushroom",
      "poppy", "dandelion", "cornflower", "azure_bluet", "oxeye_daisy", "white_tulip"],
     "작물과 꽃 — 폭이 넓다 (익은 밀은 밝고 어린 싹은 어둡다)"),
    # ── 틴트층 (2026-07) — **곱한 뒤의 색**으로 잰다 (파일이 아니라 눈에 닿는 색) ──
    ("풀_잔디·포기", 85, 150,
     ["grass_block_top", "grass_block_side_overlay", "short_grass", "fern",
      "tall_grass_*", "large_fern_*", "sugar_cane"],
     "★ 세계를 덮는 **가장 큰 면**이다 (시야의 60~80%). 형광이어도 안 되고 그을려도 안 된다 — "
     "밝기는 살리고 **채도만 죽인다** (⑫-d 가 그 채도를 잰다)"),
    ("잎_수관", 78, 145, ["oak_leaves", "spruce_leaves", "birch_leaves", "vine"],
     "수관은 **그늘을 문다** — 풀보다 짙은 것이 정체다. 다만 상록수의 밝기는 우리가 아니라 "
     "**틴트의 천장**이 정한다 (가문비 R ≤ 97 — 역틴트로도 그 위로는 못 간다)"),
    ("물", 58, 115, ["water_still", "water_flow"],
     "★ 물의 밝기는 **엔진의 산술**이다: 틴트 R 이 63 이므로 화면의 물은 R 63 을 넘지 못한다. "
     "먹이 풀린 비취빛 — 형광 파랑을 죽이되 시커먼 웅덩이로 만들지 않는다"),
    ("수초", 80, 155, ["kelp", "kelp_plant", "seagrass", "tall_seagrass_*"],
     "물속 식생 — **틴트가 없다** (모델에 tintindex 가 없다) ⇒ 우리가 칠한 값이 그대로 선다"),
    ("연잎", 42, 95, ["lily_pad"],
     "★ **저채도가 불가능한 유일한 자리**: 틴트 0x208030 의 R 이 32 라 화면의 연잎은 R 32 를 못 넘는다. "
     "무채색으로 만들려면 새까맣게 만드는 수밖에 없다 — 그래서 짙은 연녹으로 둔다 (못물의 연잎은 원래 짙다)"),
]
# 대역 제외 — **획(劃)·투명 요소**. 세계를 덮는 '면'이 아니라 배경 위에 긋는 '선'이라
# 어두운 것이 정답이다. 제외도 등록제다 (여기 없는 텍스처는 반드시 대역을 갖는다).
LUMA_EXEMPT = ["glass", "glass_pane_top", "cobweb", "dead_bush", "ladder", "glow_lichen",
               "sea_pickle", "torch"]
BLOCK_LUMA_MEAN = (128, 172)   # 전역 평균 밝기 대역 — 세계의 인상은 평균이 만든다
# ── ⑫-d 자연물 채도 **밴드** — 【2026-07 재설계】 형광도 실패지만 **죽음도 실패다** ──
#
# 【이 축의 역사 — 두 번 틀렸다】
#   1차: 채도를 아무도 안 쟀다 ⇒ 형광 초록이 통과했다.
#   2차: **상한만** 달았다 (풀 ≤ 40) ⇒ 이번엔 반대로 넘어갔다. 잔디 58 → 32, 나뭇잎 → 25,
#        다시마 → 22. 세계에서 **피가 빠졌다.** 상한은 형광을 잡았지만 **죽음은 아무도 안 잡았다.**
#   ⇒ 한쪽만 재는 자는 반드시 반대쪽으로 넘어간다. 그래서 이제 **양쪽을 잰다** (밴드).
#
# 【기준선 — 바닐라 잔디의 화면 채도 58】 (사용자 판정: *"일반적인 세상을 표현하기에 잔디블럭은 적합"*)
#   ★ 아래 「바닐라」 수치는 전부 **client jar 에서 우리 잣대로 실측한 값**이다 (짐작이 아니다).
#     그 과정에서 앞선 증분이 적어 둔 두 수가 **틀렸음**이 드러났다:
#       · 물   — 「바닐라 139」라 적혀 있었다. 실측 **115**.
#       · 연잎 — 「바닐라 75」라 적혀 있었다. 실측 **30** (외려 우리가 더 진했다).
#     **틀린 근거 위에 세운 상한은 상한이 아니라 미신이다.** 그래서 다시 쟀다.
#
# 【밴드의 뜻】  하한 = "죽었다" · 상한 = "형광이다".  기준선 58 을 감싼다.
#   자연물은 **서로 나란해야** 한다 — 잔디가 58 인 세상에서 나뭇잎이 25 면 나뭇잎이 시체로 보인다.
# ★ 이 밴드는 **곱한 뒤의 색**에 건다 (파일이 아니라 눈에 닿는 색 — 「틴트 인지」 절).
# ★ 자재(기와·나무·흙벽·석재)는 이 밴드의 **대상이 아니다** — 수묵 건축의 저채도는 이미 통과한
#   계약이다 (⑫-a·⑫-b 자재 모집단). 두 모집단은 갈라져 있다: **자연은 살고 자재는 먹이다.**
VANILLA_GRASS_CHROMA = 58     # ★ 기준선 (client jar 실측 · 평원). 이 수가 이 팩의 채도 원점이다
# ⑫-e 자재 모집단 — **등록된 자재 등급만** 센다 (「나머지 전부」가 아니다).
#   꽃·작물·차양·등롱은 자재가 아니다 (채색이 허락된 자리 — ⑫-c 가 따로 잰다). 그것들을 자재에
#   섞으면 자재 평균이 부풀어 **자재가 채도를 올려도 안 잡힌다** (모집단을 게으르게 잡으면 눈이 먼다).
MATERIAL_CLASSES = {"먹_기와·심층암", "무쇠·화로", "그릇 속(내부·구멍)", "젖은 땅·삭은 거름",
                    "여백_회벽·눈·얼음", "짚·죽", "자작(白樺)", "목재", "돌", "땅",
                    "삭은땅_부엽토·이끼"}
MAT_CHROMA_MEAN_MAX = 25      # 자재 평균 채도 상한 — 수묵의 정체 (자연을 올려도 여기는 안 움직인다)
# ★ 실측 23.1 (이 증분에서 **한 톨도 안 움직였다** — 자재 PNG 는 바이트 동일. md5 로 확인).
#   상한은 그 위에 **드리프트 감지선**으로 둔다: 훗날 누가 자연을 올리며 자재까지 끌어올리면 여기서 걸린다.
#   ⇒ 실측값 아래로 상한을 잡는 것은 검수가 아니라 **자해**다 (못 고칠 위반을 스스로 만드는 짓).
NAT_CHROMA_MEAN_MIN = 46      # 자연 평균 채도 하한 — 세계가 다시 빛바래면 여기서 걸린다
POP_GAP_MIN = 25              # 두 모집단의 **격차** — 자연과 자재가 붙으면 이 팩은 정체를 잃는다
CHROMA_BAND = [
    (["grass_block_top", "grass_block_side_overlay", "short_grass", "fern", "tall_grass_*",
      "large_fern_*", "sugar_cane"], 46, 72,
     "풀 — ★ **기준선**. 바닐라 잔디 58 (실측). 세계를 덮는 가장 큰 면이라 이것이 세계의 채도다"),
    (["oak_leaves", "spruce_leaves", "birch_leaves", "vine"], 44, 72,
     "잎 — 바닐라 참나무 70 · 가문비 17 · 자작 41 (실측). 풀과 **나란해야** 한다 (잎만 죽으면 나무가 시체다)"),
    (["water_still", "water_flow"], 50, 80,
     "물 — 바닐라 115 (형광 파랑의 정체 · 실측). 틴트 R 이 63 이라 위로도 아래로도 갇혀 있다"),
    (["kelp", "kelp_plant", "seagrass", "tall_seagrass_*"], 44, 74,
     "수초 — 바닐라 다시마 97 · 잘피 118 (실측). **틴트가 없으니 변명도 없다** (칠한 값이 그대로 선다)"),
    (["lily_pad"], 46, 82,
     "연잎 — **저채도가 원천 불가능한 자리**: 틴트 R 천장이 32 라 무채색이 되려면 새까매지는 수밖에 없다. "
     "그런데 우리는 이제 저채도를 **원하지 않는다** ⇒ 천장은 우리를 가려던 곳으로 떠민다 (바닐라 30)"),
]
# ⑫-b 를 **두 모집단**으로 가른다 — 자재와 자연을 한 통에 넣는 순간 평균이 거짓말을 한다.
#   자재(목재·돌·땅·회벽)의 밝기는 **우리가 고른다** → 기존 대역 [128,172] 그대로 (계약 불변).
#   자연(물·풀·잎)의 밝기는 **틴트의 천장이 정한다** → 물의 R 은 63 을 넘을 수 없다 (엔진의 산술).
#   그 둘을 한 평균에 넣으면, 물이 어둡다는 이유로 마을을 밝히게 된다 — **또 다른 일괄 보정**이다.
NATURAL = ["water_*", "grass_block_top", "grass_block_side_overlay", "short_grass", "fern",
           "tall_grass_*", "large_fern_*", "sugar_cane", "*_leaves", "vine",
           "kelp*", "seagrass", "tall_seagrass_*", "lily_pad"]
NATURAL_LUMA_MEAN = (82, 150)   # 자연층 평균 — 물이 바닥을 끌고 잔디가 천장을 든다
FIRE = ["lava_*", "*campfire_fire"]   # 빛나는 것 — 면이 아니다 (평균에서 빼되 ⑫-a 는 잰다)
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
    w, h, rows = read_block(f, f.stem)      # 밝기도 **곱한 뒤의 색**으로 잰다 (틴트 인지)
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

    nat_lumas = []
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
                # ⑫-b 는 **두 모집단**이다 (자재 / 자연) — 불은 면이 아니라 빛이라 양쪽에서 뺀다
                if any(fnmatch.fnmatch(f.stem, p) for p in NATURAL):
                    nat_lumas.append(L)
                elif not any(fnmatch.fnmatch(f.stem, p) for p in FIRE):
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
        total = sum(len(v[3]) for v in by_class.values())
        print(f"  ✅ 분류 커버리지 {total}/{total} = 100% (제외 등록 {len(LUMA_EXEMPT)}종 — 획·투명 요소)")

    # ⑫-b 전역 평균 — **두 모집단**. 자재의 밝기는 우리가 고르고, 자연의 밝기는 틴트의 천장이 정한다
    for label, vals, (lo, hi), why in (
            ("자재(목재·돌·땅·회벽)", lumas, BLOCK_LUMA_MEAN, "세계가 통째로 어둡다 (사용자가 본 것이 이것이다)"),
            ("자연(물·풀·잎)", nat_lumas, NATURAL_LUMA_MEAN, "땅과 물이 그을음이다 — 틴트를 잘못 되돌렸다")):
        if not vals:
            continue
        gmean = sum(vals) / len(vals)
        ok = lo <= gmean <= hi
        violations += 0 if ok else 1
        print(f"  {'✅' if ok else '❌'} 평균 밝기 {gmean:5.0f} · {label:16s} n={len(vals):3d} — 대역 [{lo}–{hi}]"
              + ("" if ok else f"  ← {why}"))

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

    # ⑫-d 자연물 채도 **밴드** — 형광도 죽음도 둘 다 실패다 (한쪽만 재면 반대쪽으로 넘어간다)
    print(f"  ── ⑫-d 자연물 채도 밴드 (풀·잎·물·수초) — 기준선 **바닐라 잔디 {VANILLA_GRASS_CHROMA}** ──")
    print("     **곱한 뒤의 색**을 잰다 (파일이 아니라 눈에 닿는 색). 자재는 이 밴드 밖이다 (수묵 = 자재의 몫)")
    nat_chromas = []
    for pats, floor, ceil, why in CHROMA_BAND:
        hits = [f for f in files if any(fnmatch.fnmatch(f.stem, p) for p in pats)]
        for f in sorted(hits):
            st = _tex_stats(f)
            if st is None:
                continue
            L, chroma, _ = st
            tint = tint_of(f.stem)
            nat_chromas.append(chroma)
            ok = floor <= chroma <= ceil
            violations += 0 if ok else 1
            src = f"틴트 {tint}" if tint else "무틴트"
            mark = "✅" if ok else "❌"
            print(f"     {mark} {f.stem:22s} 채도 {chroma:5.0f} ∈ [{floor:3d},{ceil:3d}] · 밝기 {L:5.0f} · {src}"
                  + ("" if ok else
                     f"  ← **{'죽었다' if chroma < floor else '형광이다'}** · {why}"))

    # ⑫-e 자연 ↔ 자재의 **갈라짐** — 두 모집단이 실제로 갈라져 있는지 잰다.
    #   이것이 이 팩의 정체다: **자연은 살아 있고(≈58) 자재는 먹이다(≤ 25).**
    #   자연을 올린다고 자재까지 끌려 올라가면 수묵이 무너진다 (그 반대도 마찬가지다).
    if nat_chromas:
        mat_chromas = []
        for cls, lo, hi, pats, why in LUMA_BANDS:
            if cls not in MATERIAL_CLASSES:
                continue
            for f in files:
                if any(fnmatch.fnmatch(f.stem, p) for p in pats):
                    st = _tex_stats(f)
                    if st:
                        mat_chromas.append(st[1])
        nat_mean = sum(nat_chromas) / len(nat_chromas)
        mat_mean = sum(mat_chromas) / len(mat_chromas) if mat_chromas else 0.0
        gap = nat_mean - mat_mean
        ok = (nat_mean >= NAT_CHROMA_MEAN_MIN and mat_mean <= MAT_CHROMA_MEAN_MAX
              and gap >= POP_GAP_MIN)
        violations += 0 if ok else 1
        print(f"  {'✅' if ok else '❌'} 모집단 갈라짐 — 자연 평균 채도 {nat_mean:5.1f} (n={len(nat_chromas)}, "
              f"≥{NAT_CHROMA_MEAN_MIN}) ↔ 자재 평균 {mat_mean:5.1f} (n={len(mat_chromas)}, "
              f"≤{MAT_CHROMA_MEAN_MAX}) · 격차 {gap:5.1f} ≥ {POP_GAP_MIN}"
              + ("" if ok else "  ← **두 모집단이 붙었다** (자연을 올리며 자재를 함께 올렸거나 그 반대다)"))
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


# ═══════════════════════════════════════════════════════════════════════════
# 축 ⑬. 획(劃)과 여백(餘白) — 「그림이 바닐라인가」  【2026-07 신설】
#
# ── 왜 이 축이 필요한가 ──
# 사용자: *"일본/동양풍 리소스팩 기준으로 블럭 및 물 등 **디자인**을 수정."*
# 앞 증분이 **색**(채도·색상)을 고쳤고 축 ⑫가 그것을 지킨다. 그런데 색이 맞아도 **그림이 바닐라면**
# 눈은 마인크래프트를 본다 — 그리고 **그것을 재는 자가 없었다.**
#
# 실측이 그 공백을 폭로했다 (눈에 가장 많이 닿는 16장, 화면색 기준):
#     지표                  바닐라   우리(이전)
#     σ (소리 크기)          18.2     13.2  ← 그나마 나았으나
#     여백비                  41%      35%  ← **바닐라보다 좁았다**
#     획 응집도 (난수=1.0)    1.13     1.13  ← **같다.** 획이 없다 = 점을 흩뿌리고 있었다
#   grass_block_top 은 응집도 0.97 (바닐라 0.98) — **바닐라와 같은 그림**이었다.
#
# ── 무엇을 재는가 (남의 팩 실측에서 나온 세 잣대) ──
# 조사: Mizuno's 16 Craft · Ōkami · 중국신화 팩의 PNG 를 직접 재서 얻은 문법 (docs 참조).
#   ① σ (화면 루마 표준편차) — **소리의 크기**. 오리엔탈 팩은 바닐라보다 조용하다
#      (고임물: 바닐라 8.3 · 미즈노 4.6 · 오오카미 0.3). ★ 그리고 셋 다 바닐라의 한 가지를 뒤집었다:
#      **고요(고임물)가 움직임(흐름)보다 조용하다.** 바닐라는 거꾸로다.
#   ② 여백비 — 잔잔한 바탕이 차지하는 몫. 수묵은 **쉬는 면**이 그림의 절반이다.
#   ③ 획 응집도 — **획인가 점인가.** 획(mark)은 눈에 보이는 것만 센다(절대 문턱 8루마 —
#      종이결 ±1.5루마는 획이 아니다). 그 획들이 서로 **이어져** 있는가를 밀도로 정규화해 잰다:
#        응집도 = (획의 평균 이웃 획 수) / (8 × 획 밀도).   난수로 흩뿌리면 **정확히 1.0** 이다.
#        이어진 곡선이면 1보다 크다. ⇒ **1.0 = 바닐라의 잡티. 그 위가 그림이다.**
#      ★ 이 잣대는 σ 를 대신하지 못한다: 매끈한 플라스마 얼룩은 조용하지만(σ 낮음) 그림이 아니다.
#        둘을 함께 재야 '조용한 무구조'와 '조용한 획'이 갈린다.
#
# ── 재는 범위는 등록제다 ──
# 아직 문법을 갈아입지 못한 장(판자·자갈·조약돌…)은 **STROKE_BACKLOG 에 사유와 함께** 적는다.
# 조용히 빠지는 것이 아니라 **이름과 함께 청구서에 오른다** (등록되지 않은 미완은 다음 사람이 못 본다).
MARK_LUMA = 8.0            # 획의 절대 문턱 — "눈에 보이는 자국" (종이결 ±1.5 는 획이 아니다)
CALM_MIN = 0.35            # 여백비 하한 — 쉬는 면이 이보다 좁으면 수묵이 아니다
COHESION_MIN = 1.15        # 획 응집도 하한 (난수 = 1.00). 이 아래 = 획이 아니라 잡티
COHESION_SKIP_DENSITY = 0.03   # 획이 이보다 드물면 응집도는 뜻이 없다 (거의 순수 여백 — 그것은 정답이다)
# ★ 래칫 — 축 ⑬ 이 **강제하는 화면 면적**의 하한. 2026-07: 12.1% → 28.3%(판자) → **41.6%**(석재).
#   이 수는 **오직 올라가야 한다.** 내려가면 축이 화면을 놓친 것이고, 그것은 조용한 후퇴다.
STROKE_COVERAGE_MIN = 0.43

STROKE_CONVERTED = {       # **문법을 갈아입은 '면'** — 여기는 강제한다
    "water_still", "water_flow", "grass_block_top", "grass_block_side",
    "dirt", "dirt_path_top", "stone", "deepslate_tiles",
    # 2026-07 판자 셋 (화면의 **16.2%**. 이 팩에서 가장 넓은 면이다)
    "oak_planks", "spruce_planks", "dark_oak_planks",
    # 2026-07 전돌 다섯 — 담·바닥·계단이 이 몇 장이다 (화면의 **8.4%**)
    #   (막돌은 여기가 아니라 **⑬-d 세포면**이 다스린다 — 응집도가 구조적으로 못 재는 면이다)
    "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "bricks", "mud_bricks",
}
# ★★ 【이 눈이 두 번째로 거짓말했다 — 잎에 '면'의 자를 들이댔다】
#   나뭇잎을 STROKE_CONVERTED 에 넣었더니 여백 32% · 응집도 0.84 로 위반이 떴다. 그런데 **범주 착오**다:
#   축 ⑬ 은 '칠해진 면(surface)'의 문법을 묻는 자다 (물·땅·돌·기와 — 꽉 찬 면).
#   수관(樹冠)은 면이 아니라 **컷아웃**이고, 그 여백은 **투명한 구멍(하늘)** 이다.
#   그런데 stroke_grammar 는 (옳게) 투명을 제외한다 ⇒ 잎에게는 **잴 여백이 애초에 남지 않는다.**
#   면의 자로 구멍 뚫린 것을 재면 언제나 "여백이 없다"가 나온다 — 자가 틀린 것이지 그림이 틀린 게 아니다.
#   ⇒ 잎은 **제 자**로 잰다: **구멍 비율**. 그것이 수관을 수관으로 만드는 값이다.
#   근거(실측): 바닐라 참나무 잎 구멍 33% · 조사한 오리엔탈 팩들 20~34%.
#   구멍이 없으면 수관이 **속이 꽉 찬 덩어리**(이끼)이고, 너무 많으면 앙상한 가지다.
LEAF_HOLE_BAND = (0.18, 0.36)      # 수관의 성김 — [하한, 상한]
LEAF_TEXTURES = ["oak_leaves", "spruce_leaves", "birch_leaves", "cherry_leaves"]

# ═══ ⑬-d 세포면(細胞面) — 【이 자가 세 번째로 거짓말했다. 그리고 이번엔 **그림에 상을 잘못 줬다**】
#
#   막돌(cobblestone)에 응집도를 들이대면 **영원히 통과할 수 없다.** 기하가 금지한다:
#     16px 장에 돌이 N 개면 줄눈의 총 길이는 L ≈ 2√(N·256) 이다 (N=16 ⇒ **128px**).
#     응집도가 요구하는 **2px 두께**를 주면 줄눈만으로 L×2 = **256px = 장의 100%** 다.
#     ⇒ **막돌의 줄눈은 두꺼워질 수가 없다.** 얇은 그물은 응집도가 정의상 ≈1.0 이다
#       (밀도로 정규화하므로 — 얇고 촘촘한 그물은 난수와 같은 이웃 수를 갖는다).
#     바닐라 막돌 0.99 · 바닐라 자갈 0.94 — **바닐라도 같은 이유로 못 넘는다.**
#
#   ★★★ 그리고 이것이 증거다: 나는 응집도를 만족시키려고 **줄눈을 문턱 아래로 지웠다.**
#     응집도 **1.02 → 1.83**, 여백 70%. **검수는 만족했다.** 확대해 보니 돌의 윤곽이 사라진
#     잿빛 콘크리트였고, 이끼 낀 막돌은 맨 막돌과 **구별조차 되지 않았다.**
#     **자가 나쁜 그림에 1.83 을, 좋은 그림에 1.05 를 주었다.** 자가 순위를 뒤집은 것이다.
#     ⇒ 응집도는 **획이 성긴 면**의 자다 (판자의 틈 넷 · 전돌의 켜 셋). **세포면의 자가 아니다.**
#
#   ⇒ 세포면은 **제 자**로 잰다 — 「줄눈이 줄눈인가」:
#     ① 여백 ≥ 35%              — 돌의 몸이 쉰다      (바닐라 막돌 29% · 자갈 25% 가 여기서 걸린다)
#     ② 어두운 획이 **얇다** — 2회 침식 생존 ≤ 3%     (줄눈은 **그물**이지 **덩이**가 아니다)
#     ③ 줄눈의 깊이 ≥ 12루마    — **윤곽이 눈에 보인다** (문턱 아래로 숨기면 여기서 걸린다)
#
#   ★★★★ 【그리고 **이 자도 처음엔 틀렸다.** 시험하지 않았으면 그대로 실었다】
#     첫 판의 ②는 "여백이 돌 [6,28]개로 갈라지는가" 였다. 아는 답 넷으로 시험했더니:
#       · **좋은 막돌을 떨어뜨렸다** (돌 4개 — 줄눈에 뚫린 자리가 있어 돌이 붙는다. 눈엔 멀쩡하다)
#       · **나쁜 시안을 통과시켰다** (돌 7개 · 깊이 15.9 — '씻긴 돌'이 어두워서 **줄눈 행세를 했다**)
#     ⇒ 내 '줄눈 깊이'는 줄눈이 아니라 **검은 얼룩**을 재고 있었다. 자가 또 거짓말한 것이다.
#     ⇒ 진짜 차이는 **두께**였다: 줄눈은 1~2px 그물이라 침식하면 사라지고(생존 **0.0%**),
#       씻긴 돌은 4×4 덩이라 **살아남는다(8.8%)**. 실측이 다섯 시안을 전부 갈랐다.
#     **자를 만들면 아는 답으로 시험하라. 나는 이 프로젝트에서 자에 두 번 속았다.**
CELLULAR = ["cobblestone", "mossy_cobblestone", "cobbled_deepslate"]   # 세포면 — 돌 하나하나가 세포다
CELL_THICK_MAX = 0.03              # 2회 침식 생존률 상한 — 이 위는 그물이 아니라 덩이다
CELL_JOINT_DEPTH = 12.0            # 줄눈의 평균 깊이 (루마) — 이보다 얕으면 윤곽이 안 보인다
STROKE_BACKLOG = {         # 아직 못 갈아입은 장 — **사유와 함께** 등록한다 (청구서)
    "barrel_side": "독의 테가 골함석이다 (여백 16% · 응집 1.00) — 판자 처방(잔 램프 + 획 한 줄 + 여백)을 "
                   "그대로 옮기면 된다. **다음 순번** · 화면 3.85%",
    "oak_log": "수피(bark_rows)는 아직 골함석이다 — 판자와 **같은 병**(골+어깨가 면을 덮는다). "
               "처방도 같다. 원목 3종 화면 3.4% (수피 램프는 이번에 갈라 뒀다: OAK/SPRUCE/DARK_BARK)",
    "gravel": "자갈은 본디 점의 집합이라 '획'의 문법이 그대로 안 맞는다 — 별도 판정 필요. "
              "화면 1.56% · 여백 25% (바닐라 53% — **바닐라가 우리보다 조용하다**). "
              "세포면(⑬-d)도 아니다: 줄눈이 없다. **제 자를 새로 지어야 한다**",
    "andesite": "★ 2026-07: **고치려다 망가뜨려서 되돌렸다.** amp 가 바탕을 획의 문턱 위로 밀어 "
                "바탕 전체가 획이다 (여백 36% · 응집 1.09) — 진단은 옳다. 그러나 바탕을 여백에 가두자 "
                "**돌이 사라졌다** (σ 15.9 → 6.9. 확대 시트에서 백지였다). "
                "얼룩을 줄이는 것으로는 안 된다 — **준법(皴法)의 획을 새로 지어야 한다.** "
                "tuff·packed_mud·deepslate 가 같은 병 (stone_rows 공유) · 넷 합쳐 화면 1.6%",
    "chiseled_stone_bricks": "회(回)자 무늬가 장의 93% 를 덮는다 (여백 7%). 무늬 자체를 다시 물어야 한다 — "
                             "파낸 홈을 가늘게 해 여백을 내라. 화면 0.96%",
    "sand": "모래알은 점이 정체다 — 획으로 옮길 대상인지 자체가 미결 (판정 보류)",
}


def stroke_grammar(path, name):
    """(σ, 여백비, 획 응집도, 획 밀도) — 축 ⑬ 의 세 잣대. 화면색(틴트 곱셈)으로 잰다.

    ★★ 【이 눈이 처음에 거짓말했다 — 투명을 '시끄럽다'고 셌다】
      첫 판은 컷아웃(잎)의 **투명 픽셀**(RGB 0,0,0)을 그대로 통계에 넣었다.
      ⇒ 나뭇잎의 여백이 20~27% 로 찍혀 '쉬는 면이 없다'는 위반이 떴다.
      그러나 **수관의 구멍은 하늘이다** — 그것이야말로 가장 순수한 여백이다.
      면(surface)을 재는 축이 면이 아닌 것을 재고 있었다. 투명은 **제외한다** (이 축은 '칠해진 면'의 문법을 묻는다).
      ⇒ 잎의 구멍은 축 ⑬ 의 관심사가 아니다. 그것은 leaf_rows 의 구멍 비율(20~34%)이 따로 진다."""
    w, h, rows = read_block(path, name)
    h = min(h, 32 if name in ("water_flow", "lava_flow") else 16)   # 프레임 시트는 첫 장만
    op = [(x, y) for y in range(h) for x in range(w) if px(rows, x, y)[3] > 8]   # 불투명만
    if not op:
        return 0.0, 1.0, 0.0, 0.0
    Lm = {(x, y): luma(px(rows, x, y)) for x, y in op}
    flat = list(Lm.values())
    n = len(flat)
    mu = sum(flat) / n
    sigma = (sum((v - mu) ** 2 for v in flat) / n) ** 0.5
    med = sorted(flat)[n // 2]
    calm = sum(1 for v in flat if abs(v - med) <= 6) / n
    mark = {p for p in op if abs(Lm[p] - med) > MARK_LUMA}
    dens = len(mark) / n
    if not mark or dens >= 0.999:
        return sigma, calm, 0.0, dens
    nb = sum(sum(1 for j in (-1, 0, 1) for i in (-1, 0, 1)
                 if (i, j) != (0, 0) and ((x + i) % w, (y + j) % h) in mark) for x, y in mark)
    cohesion = (nb / len(mark)) / (8 * dens)
    return sigma, calm, cohesion, dens


def cellular_grammar(path, name):
    """(여백비, 어두운획 비율, 2회 침식 생존률, 줄눈 깊이) — 축 ⑬-d 의 자. 세포면 전용.

    **침식(erosion)이 이 자의 핵심이다.** 줄눈은 1~2px 그물이라 두 번 깎으면 **사라진다**.
    검은 얼룩(씻긴 돌·이끼 덩이)은 4×4 덩이라 **살아남는다.** 그 차이가 '줄눈인가 얼룩인가'다.
    랩(모듈러)으로 잇는다 — 이어 붙여도 같은 그물이다."""
    w, h, rows = read_block(path, name)
    h = min(h, 16)
    L = {(x, y): luma(px(rows, x, y)) for y in range(h) for x in range(w)}
    med = sorted(L.values())[len(L) // 2]
    calm = sum(1 for v in L.values() if abs(v - med) <= 6) / (w * h)
    dark = {p for p, v in L.items() if v < med - MARK_LUMA}       # 줄눈 후보 = 어두운 획
    if not dark:
        return calm, 0.0, 0.0, 0.0
    depth = sum(med - L[p] for p in dark) / len(dark)

    def erode(s):
        return {(x, y) for (x, y) in s
                if all(((x + dx) % w, (y + dy) % h) in s
                       for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))}

    thick = len(erode(erode(dark))) / len(dark)                   # 두 번 깎고도 남는 몫 = 덩이의 증거
    return calm, len(dark) / (w * h), thick, depth


def cellular_axis():
    """축 ⑬-d — 세포면(막돌). 「줄눈이 줄눈인가」. 위반 수를 돌려준다."""
    print("\n  ── ⑬-d 세포면(細胞面) — 응집도가 **구조적으로 못 재는** 면 (위 주석의 증명) ──")
    bad = 0
    bdir = PACK / "minecraft" / "textures" / "block"
    for name in CELLULAR:
        f = bdir / f"{name}.png"
        if not f.exists():
            print(f"     ❌ {name}: 등록됐으나 팩에 없다")
            bad += 1
            continue
        calm, dens, thick, depth = cellular_grammar(f, name)
        notes = []
        if calm < CALM_MIN:
            notes.append(f"여백 {calm:.0%} < {CALM_MIN:.0%} (돌의 몸이 안 쉰다)")
        if thick > CELL_THICK_MAX:
            notes.append(f"침식 생존 {thick:.1%} > {CELL_THICK_MAX:.0%} "
                         f"(**줄눈이 아니라 검은 덩이다** — 얼룩을 줄눈이라 우기고 있다)")
        if depth < CELL_JOINT_DEPTH:
            notes.append(f"줄눈 깊이 {depth:.1f} < {CELL_JOINT_DEPTH} 루마 (**윤곽이 눈에 안 보인다**)")
        if notes:
            bad += 1
            print(f"     ❌ {name}: {', '.join(notes)}")
        else:
            print(f"     ✅ {name}: 여백 {calm:4.0%} · 줄눈 {dens:4.0%} (침식 생존 {thick:.1%} — 그물이다) "
                  f"· 깊이 {depth:4.1f}루마")
    return bad


_SCREEN_AREA = {}


def screen_area():
    """텍스처별 **화면 면적 점유율** — 조성 팔레트의 빈도 × 모델 면적.
    「무엇이 눈에 가장 많이 닿는가」. 축 ⑬-c 가 이것으로 제 사각지대를 잰다."""
    if _SCREEN_AREA:
        return _SCREEN_AREA
    try:
        mc = Vanilla()
    except Exception:
        return _SCREEN_AREA                              # jar 없음 — 축 ⑪ 가 이미 위반으로 외친다
    for mat, freq in builder_materials().items():
        name = mat.lower()
        if name == "air":
            continue
        if name in FLUIDS:
            areas = {FLUIDS[name]: 6 * 256.0}
        elif name in BLOCK_ENTITIES:
            areas = dict(BLOCK_ENTITIES[name])
        else:
            areas = mc.block_area(name)
            if not areas:
                continue
        for t, a in areas.items():
            _SCREEN_AREA[t] = _SCREEN_AREA.get(t, 0.0) + freq * a
    tot = sum(_SCREEN_AREA.values()) or 1.0
    for t in _SCREEN_AREA:
        _SCREEN_AREA[t] /= tot
    return _SCREEN_AREA


def stroke_coverage():
    """축 ⑬-c — **이 축이 화면의 몇 %를 실제로 다스리는가.**

    ★★★ 【이 축이 스스로에 대해 거짓말하고 있었다 — 2026-07 의 발견】
      축 ⑬ 은 STROKE_CONVERTED **8장만** 재고 "위반 0건"을 외쳤다. 그런데 그 8장은
      **화면의 12.1%** 였다. 나머지 87.9% 중 축이 **묻지도 않은** 면이 64.4% 였고,
      그 사각지대에 계약을 들이대 보니 **168장 · 화면의 44.4% 가 즉시 깨졌다.**
      **검수는 계약을 지켰지만, 계약이 걸린 곳이 화면의 8분의 1이었다.**

      ⇒ 이제 이 축은 **제가 다스리는 면적을 스스로 보고한다.** 커버리지가 떨어지면 검수가 운다.
        "위반 0건"이 "화면의 12%에서 위반 0건"이라는 뜻이었다는 것을, 다음 사람은 **한 줄로 안다.**
    ⇒ 청구서는 이름이 아니라 **면적**으로 갚는다. 넓은 면부터."""
    area = screen_area()
    if not area:
        return 0
    bdir = PACK / "minecraft" / "textures" / "block"
    # 강제하는 면 = ⑬(획) 이 다스리는 면 **+ ⑬-d(세포면) 이 다스리는 면.**
    #   자가 다르다고 다스리지 않는 것이 아니다 — 막돌에는 **제 계약**이 걸려 있다.
    governed = set(STROKE_CONVERTED) | set(CELLULAR)
    gov = sum(a for t, a in area.items() if t.split("/")[-1] in governed)
    bill = sum(a for t, a in area.items() if t.split("/")[-1] in STROKE_BACKLOG)
    blind = []
    for t, a in area.items():
        name = t.split("/")[-1]
        if name in governed or name in STROKE_BACKLOG or name in LEAF_TEXTURES:
            continue
        f = bdir / f"{name}.png"
        if not f.exists():
            continue
        sigma, calm, coh, dens = stroke_grammar(f, name)
        if calm < CALM_MIN or (dens >= COHESION_SKIP_DENSITY and coh < COHESION_MIN):
            blind.append((a, name, calm, coh))
    lost = sum(a for a, _, _, _ in blind)
    print(f"\n  ── ⑬-c 이 축이 다스리는 화면 (면적 가중) ──")
    print(f"     강제하는 면      {gov:6.1%}  ({len(governed)}장 — 계약이 걸려 있다: 획 {len(STROKE_CONVERTED)} + 세포면 {len(CELLULAR)})")
    print(f"     청구서           {bill:6.1%}  ({len(STROKE_BACKLOG)}장 — 이름과 사유가 등록돼 있다)")
    print(f"     ★ 사각지대       {lost:6.1%}  ({len(blind)}장 — **계약을 걸면 즉시 깨질 면.** 축이 묻지도 않는다)")
    if blind:
        print(f"     사각지대 상위 (면적) — **다음 증분은 여기서 고른다**:")
        for a, n, calm, coh in sorted(blind, reverse=True)[:8]:
            print(f"       {a:6.2%}  {n:26s} 여백 {calm:4.0%} · 응집 {coh:.2f}")
    if gov < STROKE_COVERAGE_MIN:
        print(f"     ❌ 강제 면적 {gov:.1%} < {STROKE_COVERAGE_MIN:.0%} — **축이 화면을 놓치고 있다**")
        return 1
    print(f"     ✅ 강제 면적 {gov:.1%} ≥ {STROKE_COVERAGE_MIN:.0%} (래칫 — 내려가면 검수가 운다)")
    return 0


def stroke_axis():
    """축 ⑬ — 획과 여백. 위반 수를 돌려준다."""
    print("\n── 축 ⑬ 획(劃)과 여백(餘白) — 「그림이 바닐라인가」 ──")
    print(f"  획 응집도: 난수로 흩뿌리면 1.00 (= 바닐라의 잡티). 그 **위**가 그림이다 (하한 {COHESION_MIN})")
    bad = 0
    bdir = PACK / "minecraft" / "textures" / "block"
    for name in sorted(STROKE_CONVERTED):
        f = bdir / f"{name}.png"
        if not f.exists():
            print(f"  ❌ {name}: 등록됐으나 팩에 없다 (등록부가 팩을 앞지른다)")
            bad += 1
            continue
        sigma, calm, coh, dens = stroke_grammar(f, name)
        notes = []
        if calm < CALM_MIN:
            notes.append(f"여백 {calm:.0%} < {CALM_MIN:.0%} (쉬는 면이 없다)")
        if dens >= COHESION_SKIP_DENSITY and coh < COHESION_MIN:
            notes.append(f"응집도 {coh:.2f} < {COHESION_MIN} (획이 아니라 잡티 — 난수와 구별되지 않는다)")
        if notes:
            bad += 1
            print(f"  ❌ {name}: {', '.join(notes)}")
        else:
            tail = "여백뿐" if dens < COHESION_SKIP_DENSITY else f"응집 {coh:.2f}"
            print(f"  ✅ {name}: σ {sigma:5.1f} · 여백 {calm:4.0%} · {tail}")
    # 고요는 움직임보다 조용해야 한다 — 바닐라가 거꾸로 가진 것 (남의 팩 셋이 모두 뒤집었다)
    ws, wf = bdir / "water_still.png", bdir / "water_flow.png"
    if ws.exists() and wf.exists():
        s_still = stroke_grammar(ws, "water_still")[0]
        s_flow = stroke_grammar(wf, "water_flow")[0]
        if s_still >= s_flow:
            bad += 1
            print(f"  ❌ 물: 고임 σ {s_still:.1f} ≥ 흐름 σ {s_flow:.1f} — "
                  f"**고요가 움직임보다 시끄럽다** (바닐라의 거꾸로를 그대로 물려받았다)")
        else:
            print(f"  ✅ 물: 고임 σ {s_still:.1f} < 흐름 σ {s_flow:.1f} (고요가 더 조용하다 — 바닐라는 거꾸로다)")
    # ⑬-b 수관의 성김 — 잎은 '면'이 아니라 컷아웃이다 (제 자로 잰다)
    lo, hi = LEAF_HOLE_BAND
    for name in LEAF_TEXTURES:
        f = bdir / f"{name}.png"
        if not f.exists():
            continue
        w, h, rows = read_png(f)
        holes = sum(1 for y in range(h) for x in range(w) if px(rows, x, y)[3] <= 8) / (w * h)
        if not lo <= holes <= hi:
            bad += 1
            why = "속이 꽉 찼다 (수관이 아니라 이끼 덩이)" if holes < lo else "앙상하다 (가지만 남았다)"
            print(f"  ❌ {name}: 구멍 {holes:.0%} — 대역 [{lo:.0%}–{hi:.0%}] 밖 · {why}")
        else:
            print(f"  ✅ {name}: 구멍 {holes:4.0%} (수관이 성글다 — 잎덩이 사이로 하늘이 비친다)")
    bad += cellular_axis()                               # ⑬-d — 세포면은 제 자로 잰다 (막돌)
    print(f"\n  ── 청구서: 아직 문법을 못 갈아입은 장 {len(STROKE_BACKLOG)}종 (사유와 함께 등록됨)")
    for k, why in sorted(STROKE_BACKLOG.items()):
        print(f"     · {k} — {why}")
    bad += stroke_coverage()                             # ⑬-c — 축이 제 사각지대를 스스로 잰다
    return bad


# ═══════════════════════════════════════════════════════════════════════════
# 축 ⑭. 명병(名兵) 문파 변별 — 「흑백으로 보아도 문파가 갈리는가」 【2026-07 신설】
#
# 가져온 기획 프롬프트의 제1원칙을 계량화한다:
#   *"아이템 아이콘을 **흑백으로 보더라도** 문파가 구분되어야 한다."*
#   그리고 그 프롬프트가 스스로 금지한 것: *"색만 바꾸고 같은 모델을 재사용하는 방식은 피하라."*
#
# ★★ 【첫 판의 잣대가 틀렸다 — 자카드는 이 물음의 자가 아니다】
#   처음엔 축 ⑨(계열 실루엣)를 그대로 베껴 **마스크 자카드 ≤ 0.65** 를 걸었다. 아홉 쌍이 걸렸다
#   (화산↔남궁 0.929). 그런데 그 잣대를 통과하려면 **검이 검처럼 생기기를 그만두어야 한다**:
#   화산·남궁·점창·종남·무당·청성·해남·곤륜은 **전부 검(劍)이다.** 계열이 같다.
#   축 ⑨ 는 "도(刀)와 비수를 가르는가"를 묻는 자다 — **서로 다른 계열**의 실루엣을 견주라고 만든 것이다.
#   같은 계열 안에서 자카드 0.65 를 요구하는 것은 "검 여덟 자루가 서로 안 닮게 생겨라"는 말이고,
#   그 요구를 따르면 문파색은 생기되 **병기 체계가 무너진다** (등록부: 화산도 남궁도 무기는 검이다).
#
#   프롬프트가 실제로 요구한 것은 **"구분된다"** 이지 **"윤곽이 다르다"** 가 아니다.
#   그리고 흑백에서 구분을 만드는 것은 윤곽만이 아니다 — **명암(문양·코등이·물미)** 도 흑백에 남는다.
#   ⇒ 그래서 축 ⑩(등급 회색조 변별)의 문법을 쓴다: 두 장을 겹쳐
#     **(ㄱ) 실루엣이 다르거나 (ㄴ) 휘도가 16 이상 다른** 픽셀 수를 센다. 색상(色相)은 세지 않는다.
#     그 수가 곧 "색맹 플레이어와 회색조 스크린샷이 보는 차이"다 — 프롬프트가 물은 바로 그것.
#   자카드는 **버리지 않고 참고로 출력한다** (계열이 다른 쌍 — 팽가의 도·당가의 비수·소림의 권갑 —
#   은 여전히 윤곽으로 갈려야 하고, 그것은 눈으로 확인할 값이다).
MYEONG_DELTA_MIN = 16     # 흑백에서 달라야 하는 픽셀 수 하한 (아이템은 16x16에서 60~90px 이다)


def myeong_pairs():
    """(회색조 변별 픽셀 수, 자카드, a, b) — 모든 명병 쌍."""
    from build_resourcepack import MYEONGBYEONG          # 등록부는 하나다 (빌더가 원천)
    grids, out = {}, []
    for sect in MYEONGBYEONG:
        f = ITEM / "weapon" / "myeong" / f"{sect}.png"
        w, h, rows = read_png(f)
        grids[sect] = rows
    names = list(MYEONGBYEONG)
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            ra, rb = grids[a], grids[b]
            delta = inter = uni = 0
            for y in range(16):
                for x in range(16):
                    pa, pb = px(ra, x, y), px(rb, x, y)
                    va, vb = pa[3] > 8, pb[3] > 8
                    if va != vb or (va and abs(luma(pa) - luma(pb)) >= 16):
                        delta += 1
                    if va and vb:
                        inter += 1
                    if va or vb:
                        uni += 1
            out.append((delta, inter / uni if uni else 0.0, a, b))
    out.sort()
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


# ═══════════════════════════════════════════════════════════════════════════
# 축 ⑮ 아틀라스 · 축 ⑯ 비율 — 「그린 것이 **화면에 서는가**」  【2026-07 신설】
#
# ── 이 눈이 왜 생겼나 (검수가 거짓말한 기록) ──
# 사용자가 게임에서 무공을 썼다. 획 15종이 **전부 보라 큐브**였다. 짐승도 같았다.
# 그동안 이 검수는 **위반 0건**을 외치고 있었다. 열네 축이 전부 통과였다.
#
# 잡을 수가 없었다 — 열네 축은 전부 **PNG 한 장 안의 그림**을 묻는 자들이다:
#   색이 몇인가 · 명암이 있는가 · 이음매가 뜨는가 · 외곽선이 폐합인가 · 획과 여백이 있는가.
# 그림은 **완벽했다.** 완벽한 그림이 **화면에 서지 못하는 자리**에 있었을 뿐이다.
#   ⇒ 검수는 「그림이 좋은가」만 물었고 「그림이 **보이는가**」는 **아무도 안 물었다.**
#   ★ 재지 않는 축은 조용히 무너진다 — 그리고 이 축은 **아예 없었다.** 그래서 못을 박는다.
#
# ── 무엇을 묻는가 (둘) ──
#   ⑮ 아틀라스: 모델이 부르는 텍스처가 **클라이언트가 훑는 자리**에 있는가.
#      클라이언트는 아무 데나 훑지 않는다. 1.21.11 jar 의 atlases/*.json 이 훑을 자리를 못 박는다:
#          blocks.json → directory "block" (prefix "block/")
#          items.json  → directory "item"  (prefix "item/")
#      directory 원천은 **모든 네임스페이스**의 assets/<ns>/textures/<source>/** 를 훑어
#      <ns>:<prefix><상대경로> 라는 스프라이트를 만든다. 그 바깥의 PNG 는 **스프라이트가 없다** —
#      모델은 뜨는데 그릴 그림이 없으니 클라이언트가 결번 텍스처(보라-검정)를 대신 그린다.
#      ★ 짐작하지 않는다: 원천 목록을 **jar 에서 읽는다** (팩이 atlases 를 선언했으면 그것도 더한다).
#
#   ⑯ 비율: 아틀라스 스프라이트는 **정사각** 또는 **세로 스트립(h = n·w, .mcmeta 동반)** 이어야 한다.
#      가로로 긴 64x16 은 둘 다 아니다 — 아틀라스에 넣어도 그림이 서지 않는다.
#      (font/* 는 예외다: 비트맵 폰트는 아틀라스가 아니라 **폰트 프로바이더**가 읽는다.
#       gui/·painting/·entity/ 도 이 축의 밖이다 — 모델이 안 쓰고, 아틀라스 계약도 다르다.
#       ⑯ 의 사정거리는 **⑮ 가 훑는 그 자리**와 정확히 같다: block/ 과 item/.)
#
# ── 눈을 세웠으면 눈을 시험하라 ──
#   --selftest 가 일부러 어긴다: ① 텍스처를 아틀라스 밖으로 옮기고 ② 64x16 을 굽는다.
#   눈이 그 둘을 **실제로 잡는지** 확인하고, 못 잡으면 1을 돌려준다 (눈이 또 거짓말한 것이다).
# ═══════════════════════════════════════════════════════════════════════════
MODEL_ATLASES = ("blocks", "items")     # 모델 렌더러가 쓰는 아틀라스는 이 둘뿐이다
                                        # (gui·painting·particle·entity 는 모델이 안 쓴다)

# ═══ 아이콘 축의 사정거리 — 「textures/item/ 아래가 전부 아이콘은 아니다」 【2026-07】 ═══
# 축 ①(16x16 치수)·축 ⑥(외곽선 폐합)은 **핫바에 뜨는 아이콘**의 규율이다:
#   "손에 든 물건이 어떤 배경 위에서도 어두운 테로 떠올라야 한다."
# 그런데 item/ 아래에는 아이콘이 **아닌** 것도 산다 — 아틀라스에 꿰매지는 자리가 거기뿐이기 때문이다:
#   · item/qi/·item/ult/ — 무공의 **획**. ItemDisplay 가 3D 모델에 입혀 공중에 띄운다.
#     **반투명이 본체**이고 꼬리로 갈수록 옅어지는 것이 그림의 뜻이다 — 어두운 테를 두르면 획이 아니다.
#     32x32·64x64 로 굽는 것도 형체를 위해서다. 핫바에 뜨는 일이 **없다**.
#   · item/mob/ — 짐승의 **가죽**. MobDisplay 가 3D 형체에 입힌다. 면(面)이지 아이콘이 아니다.
#
# ★★ 이 등록이 **왜** 여기 있는가 (보라 큐브의 진짜 뿌리) ★★
#   이것들은 원래 textures/qi|ult|mob/ 에 있었다. 그렇게 둔 **이유가 주석에 적혀 있었다**:
#       "item/ 아래가 아니다 — 축 ⑥ 외곽선 폐합은 아이콘의 규율이지 반투명 획의 규율이 아니다"
#   즉 **축을 피하려고 파일을 축의 사정거리 밖으로 옮긴 것**이다. 그 자리는 하필
#   **클라이언트도 안 훑는 자리**였고, 그래서 게임에서 획 15종과 짐승 8종이 전부 **보라 큐브**가 됐다.
#   ⇒ 교훈: **축이 시끄럽다고 파일을 숨기면, 축은 조용해지고 세계가 깨진다.**
#      옳은 길은 파일을 제자리(아틀라스 안)에 두고 **면제를 등록제로 적는 것**이다 —
#      이 팩이 이미 쓰는 관행 그대로 (SEAM_FACES · 밝기 대역 제외 등록).
#   면제된 것은 **숨은 것이 아니다**: 축 ⑮·⑯ 이 이것들을 계속 잰다 (자리와 비율).
# kigi/ — 녹색 검기(劍氣) 참격. qi/ 와 **같은 부류**(ItemDisplay 가 3D 판에 입혀 공중에 띄우는
#   반투명 획)이므로 아이콘 규율(축 ①·⑥)의 관할이 아니다. 색만 다르다 (qi/* 무색 판을 물들이지
#   않으려고 별도 경로에 굽는다 — 축 ⑱ QI_ACHROMATIC 참조). 면제는 은닉이 아니다: 축 ⑮·⑯(자리·비율)
#   ·⑰(중심)·⑱(색)이 kigi/ 를 계속 잰다 (아래 QI_TEX_DIRS · stroke_models 참조).
NON_ICON = ("/textures/item/qi/", "/textures/item/ult/", "/textures/item/mob/", "/textures/item/kigi/")

# ═══ 고해상 등록부 — 【2026-07-16 신설 · V2-W 2차 청구】 축 ①·⑥ 의 **등록형** 면제 ═══
# NON_ICON 은 하드코딩 3종으로 굳었다 — 네 번째부터는 **등록부가 주도한다**
# (config/resourcepack_design.yml hi_res_channels: 경로 글롭 · 허용 해상도 · 사유).
# 등재된 채널은 아이콘 규율(① 16x16 · ⑥ 외곽선)의 관할이 아니다 — 3D 모델의 면이지
# 핫바 아이콘이 아니기 때문이다. 그러나 면제는 무법지대가 아니다: **축 ㉓ 이 다른 자로 잰다**
# (치수·팔레트·적색·알파 실루엣·아틀라스 실존). 미등록 고해상은 여전히 축 ① 이 잡는다.
_HI_RES_CACHE = []


def hi_res_registry():
    """hi_res_channels.entries — [(글롭, [허용 변길이…], 사유)…]. 절이 없으면 None (축 ㉓ 이 운다)."""
    if _HI_RES_CACHE:
        return _HI_RES_CACHE[0]
    src = (ROOT / "config" / "resourcepack_design.yml").read_text(encoding="utf-8")
    body = src.split("\nhi_res_channels:", 1)
    if len(body) < 2:
        _HI_RES_CACHE.append(None)
        return None
    out = []
    for line in body[1].splitlines():
        if line and not line[0].isspace():
            break                                  # 다음 최상위 절 — 등록부는 끝났다
        m = re.match(r'\s*-\s*\{\s*path:\s*"([^"]+)"\s*,\s*sizes:\s*\[([\d,\s]+)\]\s*,'
                     r'\s*reason:\s*"([^"]+)"', line)
        if m:
            # 【V2-W 6차 개정 — 팔레트 해방은 등록부 주도】 채널별 선택 필드 (없으면 전역 기본):
            #   chroma_mean_max / chroma_pixel_max / chroma_pixel_frac / hue_free
            # 사유: 레퍼런스(TWC 실측 2장)의 설계 언어 — 흑자주·심녹 기조 + 선명한 악센트 —
            # 는 전역 채도·색상 상한(묵청 자재의 잣대)으로 성립 불가. 무기 paint 채널만
            # 제 잣대를 등록하고, 미등록 채널·아이콘·블록 축은 옛 법 그대로다. 【잠정】
            pal = {}
            for key in ("chroma_mean_max", "chroma_pixel_max", "chroma_pixel_frac"):
                km = re.search(key + r':\s*([\d.]+)', line)
                if km:
                    pal[key] = float(km.group(1))
            if re.search(r'hue_free:\s*true', line):
                pal["hue_free"] = True
            if re.search(r'animation:\s*true', line):
                pal["animation"] = True             # 20차 — 세로 프레임 스트립(h=n·w)+.mcmeta 허용
            out.append((m.group(1), [int(v) for v in m.group(2).split(",")], m.group(3), pal))
    _HI_RES_CACHE.append(out)
    return out


def _hi_res_entry(path):
    """이 파일이 걸리는 hi_res 등재 행 — (글롭, sizes, 사유, 팔레트) 또는 None. 첫 일치가 이긴다."""
    import fnmatch
    reg = hi_res_registry() or []
    p = str(path).replace("\\", "/")
    return next((row for row in reg if fnmatch.fnmatch(p, "*/" + row[0])), None)


def is_icon(path):
    """핫바에 뜨는 **아이콘**인가 — 축 ①·⑥ 의 사정거리. (획·가죽은 3D 모델의 면이지 아이콘이 아니다.
    hi_res_channels 등재분도 아이콘이 아니다 — 등록형 면제, 축 ㉓ 이 대신 잰다)"""
    p = str(path).replace("\\", "/")
    return ("/textures/item/" in p and not any(k in p for k in NON_ICON)
            and _hi_res_entry(path) is None)


def png_size(path):
    """PNG 의 (w, h) — IHDR 만 읽는다. read_png 은 RGBA8 만 받으므로 축 ⑯ 은 따로 잰다."""
    d = path.read_bytes()
    return struct.unpack(">II", d[16:24])


def atlas_sources():
    """클라이언트가 **실제로 훑는 자리** — jar 의 atlases/{blocks,items}.json 이 진실이다.
    팩이 같은 이름의 아틀라스를 선언했다면 그 원천도 더한다 (나중에 그 길을 택해도 눈이 산다).
    돌려주는 것: (directory 원천 [(source, prefix)…], single 원천 {"ns:path"…})."""
    dirs, singles, z = [], set(), client_jar()
    names = set(z.namelist())
    for atlas in MODEL_ATLASES:
        docs = []
        jar_path = f"assets/minecraft/atlases/{atlas}.json"
        if jar_path in names:
            docs.append(json.loads(z.read(jar_path)))
        for ns_dir in sorted(p for p in PACK.iterdir() if p.is_dir()):
            f = ns_dir / "atlases" / f"{atlas}.json"
            if f.exists():
                docs.append(json.loads(f.read_text(encoding="utf-8")))
        for doc in docs:
            for s in doc.get("sources", []):
                t = str(s.get("type", "")).split(":")[-1]
                if t == "directory":
                    dirs.append((s["source"], s.get("prefix", "")))
                elif t == "single":
                    r = s["resource"]
                    singles.add(r if ":" in r else f"minecraft:{r}")
                # paletted_permutations 등은 이 축의 대상이 아니다 (우리 모델이 안 쓴다)
    return dirs, singles


def model_texture_refs():
    """팩의 모든 모델 JSON 이 부르는 텍스처 — [(모델파일, 슬롯, 참조)…]. #참조는 뺀다(다른 슬롯을 가리킨다)."""
    out = []
    for ns_dir in sorted(p for p in PACK.iterdir() if p.is_dir()):
        mdir = ns_dir / "models"
        if not mdir.is_dir():
            continue
        for f in sorted(mdir.rglob("*.json")):
            doc = json.loads(f.read_text(encoding="utf-8"))
            for slot, ref in (doc.get("textures") or {}).items():
                if isinstance(ref, str) and not ref.startswith("#"):
                    out.append((f, slot, ref))
    return out


def _stitched(ref, dirs, singles):
    """이 텍스처 참조가 아틀라스에 꿰매지는가 → 꿰매지면 그 PNG 경로, 아니면 None."""
    ns, path = ref.split(":", 1) if ":" in ref else ("minecraft", ref)
    if f"{ns}:{path}" in singles:
        return PACK / ns / "textures" / f"{path}.png"
    for src, prefix in dirs:
        if not path.startswith(prefix):
            continue
        cand = PACK / ns / "textures" / src / f"{path[len(prefix):]}.png"
        if cand.exists():
            return cand
    return None


def atlas_axis():
    """축 ⑮ — 모델이 부르는 텍스처가 전부 아틀라스 안에 있는가. 위반 수를 돌려준다."""
    dirs, singles = atlas_sources()
    print("\n── 축 ⑮ 아틀라스 — 「모델이 부르는 그림이 클라이언트가 훑는 자리에 있는가」 ──")
    print(f"  원천(jar+팩): " + " · ".join(f"textures/{s}/** → {p}*" for s, p in dirs))

    violations, bad_refs = 0, {}
    for f, slot, ref in model_texture_refs():
        if _stitched(ref, dirs, singles):
            continue
        bad_refs.setdefault(ref, []).append(f"{f.parent.name}/{f.stem}")
    for ref in sorted(bad_refs):
        ns, path = ref.split(":", 1) if ":" in ref else ("minecraft", ref)
        tdir = PACK / ns / "textures"
        # 파일이 **어디에** 있는지 찾아서 말한다 — "없다"와 "엉뚱한 데 있다"는 다른 병이고
        # 고치는 손도 다르다 (전자는 굽고, 후자는 옮긴다). 눈이 병명을 틀리면 손이 헤맨다.
        stray = next((p for p in sorted(tdir.rglob(f"{path.rsplit('/', 1)[-1]}.png"))
                      if p.parts[-2] == path.rsplit("/", 2)[-2:][0] or "/" not in path), None) \
            if tdir.is_dir() else None
        if (tdir / f"{path}.png").exists():
            stray = tdir / f"{path}.png"
        why = (f"아틀라스 **밖**에 있다 → {stray.relative_to(PACK)} "
               f"(스프라이트가 안 만들어진다 ⇒ 보라 큐브)"
               if stray else "텍스처 파일 자체가 없다 (아무도 굽지 않았다)")
        violations += 1
        users = bad_refs[ref]
        print(f"  ❌ {ref}: {why}"
              f"\n       ← 모델 {len(users)}개 ({', '.join(users[:3])}{' …' if len(users) > 3 else ''})")
    total = len({r for _, _, r in model_texture_refs()})
    print(f"  모델 텍스처 참조 {total}종 · 아틀라스 밖 {violations}종")
    return violations


def aspect_axis():
    """축 ⑯ — 아틀라스에 꿰매지는 모든 텍스처가 정사각 또는 (세로 스트립 + .mcmeta) 인가.

    .mcmeta 는 **팩에 없어도 된다**: 팩이 PNG 만 얹으면 바닐라 jar 의 .mcmeta 가 그대로 산다
    (리소스팩은 파일 단위로 겹친다). 그래서 jar 도 함께 본다 — 안 그러면 물(16x512)이 거짓 위반이 된다."""
    dirs, _ = atlas_sources()
    z, names = client_jar(), None
    names = set(z.namelist())
    print("\n── 축 ⑯ 비율 — 「정사각인가 · 세로 스트립(+mcmeta)인가」 ──")

    violations, checked = 0, 0
    for ns_dir in sorted(p for p in PACK.iterdir() if p.is_dir()):
        for src, _prefix in dirs:
            root = ns_dir / "textures" / src
            if not root.is_dir():
                continue
            for f in sorted(root.rglob("*.png")):
                checked += 1
                w, h = png_size(f)
                if w == h:
                    continue
                rel = f.relative_to(ns_dir / "textures")
                has_meta = (f.with_name(f.name + ".mcmeta").exists()
                            or f"assets/{ns_dir.name}/textures/{rel}.mcmeta" in names)
                if h > w and h % w == 0 and has_meta:
                    continue                      # 세로 스트림 + 프레임 선언 = 애니메이션 (합법)
                violations += 1
                if h > w and h % w == 0:
                    why = f"세로 스트립이나 .mcmeta 가 없다 (프레임 수를 아무도 안 말했다)"
                else:
                    why = ("**가로로 길다** — 마인크래프트가 안 받는다 (정사각도 세로 스트립도 아니다)"
                           if w > h else "정사각도 세로 스트립(h=n·w)도 아니다")
                print(f"  ❌ {ns_dir.name}:{rel}: {w}x{h} — {why}")
    print(f"  아틀라스 텍스처 {checked}장 검사 · 비율 위반 {violations}장 "
          f"(font/·gui/·entity/ 는 아틀라스 밖이라 이 축의 대상이 아니다)")
    return violations


# ═══════════════════════════════════════════════════════════════════════════
# 축 ⑰ 중심 — 「획이 **시전자의 정면**에 서는가」  【2026-07 신설】
#
# ── 이 눈이 왜 생겼나 (축 ⑮ 가 획을 보이게 하자마자 드러난 병) ──
# 획이 보이기 시작하니 사용자가 바로 말했다: *"주먹 휘두를 때 모션이 캐릭터의 **정면이 아닌 왼쪽**에서 나온다."*
# 재어 보니 참격선 두 모델의 기하 중심이 원점에서 **x −12** 만큼 치우쳐 있었다 (x −16..8).
#
# 【왜 치우침이 곧 병인가】 ItemDisplay 는 모델을 **엔티티 자리에 중심을 두고** 그린다.
#   그리고 모델의 +X 는 (SkillDisplay.pose 좌표계) **앞이 아니라 좌우 축**이다.
#   ⇒ 모델의 중심이 x 로 치우치면 그 획은 시전자의 **옆구리**에 걸린다. 정면에 안 선다.
#   ⇒ **모델에 박힌 오프셋은 모든 모션이 물려받는다.** 그래서 모델은 중심에 서야 하고,
#     위치·성장은 코드가 정해야 한다 (머리 고정은 등록부의 anchor + 코드의 translation 이 한다).
#
# 【무엇을 재는가】 획 모델(models/item/qi|ult/**)의 elements 바운딩박스 중심이 (8,8,8) 인가.
#   · x(좌우) — **가장 엄격히**. 좌우 치우침은 곧 "옆구리에서 나온다"다. 허용 오차 0.01px.
#   · y·z — 같은 잣대. 다만 **등록부가 뜻을 밝힌 비대칭은 면제**한다 (아래 CENTER_EXEMPT).
#     지금은 면제가 **하나도 없다** — 15종 전부 중심에 서야 한다. 앞으로 뻗는 창(z 비대칭)처럼
#     의도된 비대칭이 생기면 **이유와 함께 여기에 등록**한다 (조용히 눈감는 자리를 만들지 않는다).
# ═══════════════════════════════════════════════════════════════════════════
CENTER_TOL = 0.01          # px — 기하 중심이 원점(8,8,8)에서 벗어나도 되는 한계
CENTER_EXEMPT = {}         # 키 → 이유. **등록제** — 의도된 비대칭만, 반드시 이유와 함께.
                           # (비어 있다 = 획 15종 전부 중심에 서야 한다)


def stroke_models():
    """획 모델 — models/item/qi/** · models/item/ult/** (참격선·오의·투사체의 형체)."""
    root = PACK / "honcheon" / "models" / "item"
    return [f for f in sorted(root.rglob("*.json"))
            if str(f.relative_to(root)).replace("\\", "/").split("/")[0] in ("qi", "ult", "kigi")]


def model_center(f):
    """모델의 기하 중심 — elements 바운딩박스의 한가운데. (없으면 None)"""
    els = json.loads(f.read_text(encoding="utf-8")).get("elements") or []
    if not els:
        return None
    lo = [min(min(e["from"][i], e["to"][i]) for e in els) for i in range(3)]
    hi = [max(max(e["from"][i], e["to"][i]) for e in els) for i in range(3)]
    return [(lo[i] + hi[i]) / 2.0 for i in range(3)]


def center_axis():
    """축 ⑰ — 획 모델의 기하 중심이 원점(8,8,8) 에 서는가. 위반 수를 돌려준다."""
    root = PACK / "honcheon" / "models" / "item"
    print("\n── 축 ⑰ 중심 — 「획이 시전자의 정면에 서는가 (기하 중심 = 원점)」 ──")
    violations = 0
    for f in stroke_models():
        key = str(f.relative_to(root)).replace("\\", "/")[:-5]
        c = model_center(f)
        if c is None:
            continue
        if key in CENTER_EXEMPT:
            print(f"  ⊖ {key}: 면제 — {CENTER_EXEMPT[key]}")
            continue
        dev = [c[i] - 8.0 for i in range(3)]
        off = [ax for i, ax in enumerate("xyz") if abs(dev[i]) > CENTER_TOL]
        if off:
            violations += 1
            side = ("시전자의 **옆구리**에 걸린다 (정면에 안 선다)"
                    if "x" in off else "원점에서 치우쳤다")
            print(f"  ❌ {key}: 중심 ({c[0]:.1f}, {c[1]:.1f}, {c[2]:.1f}) — "
                  f"원점 편차 ({dev[0]:+.1f}, {dev[1]:+.1f}, {dev[2]:+.1f}) · {'·'.join(off)} 축 — {side}")
    print(f"  획 모델 {len(stroke_models())}종 · 중심 이탈 {violations}종 "
          f"(면제 {len(CENTER_EXEMPT)}종 — 등록된 비대칭만)")
    return violations


# ═══════════════════════════════════════════════════════════════════════════
# 축 ⑱ — 【획의 색】 (2026-07 · 사용자 지시)
#
# ── 축 ⑫ 의 면제를 **소리내어 청구하는 자리** ────────────────────────────────
#   축 ⑫(밝기 대역 · 자재 채도 ≤25)는 `minecraft/textures/block/*.png` 만 훑는다.
#   획은 `honcheon/textures/item/qi|ult/` 에 산다 ⇒ **애초에 ⑫ 의 사정거리 밖**이었다.
#   그런데 그 사실이 **어디에도 적혀 있지 않았다.** 사정거리 밖이라 조용히 통과하던 것은
#   면제가 아니라 **구멍**이다 — NON_ICON 이 가르쳐 준 그 교훈 그대로다:
#       "축이 시끄럽다고 파일을 숨기면 축은 조용해지고 세계가 깨진다."
#   ⇒ 그래서 **면제를 등록하고, 다른 자로 잰다.** 획은 자재가 아니라 기(氣)다:
#      세계를 덮는 '면'이 아니라 공중에 뜨는 '빛'이므로 저채도 밴드가 잘못된 잣대다.
#      대신 이 축이 **금지색**과 **등록부와의 일치**를 잰다.
#      면제는 "재지 않는다"가 아니라 **"다른 자로 잰다"** 는 뜻이다.
#
# ── 무엇을 재는가 ────────────────────────────────────────────────────────────
#   ① qi/* 7종 — **무색**이어야 한다. 여섯 격이 이 판 한 장을 나눠 쓰므로 (참격선은 격마다
#      다른 모델이 아니다) 판에 색을 넣으면 **외공기의 주먹이 심검처럼 빛난다** — 사다리가 무너진다.
#   ② ult/* — 색이 있어야 한다 (사용자: "최상위는 가장 화려하게"). 그러나 **등록부의 색**이어야 한다:
#      config/skill_motion.yml 의 inks.옥 / inks.청백 과 구워진 PNG 가 어긋나면 위반이다.
#      (빌더가 색을 지어내면 등록제가 무너진다 — 그 어긋남을 잡는 것이 이 축의 본업이다.)
#   ③ 금지색 — 형광 핑크 · 네온 보라 · 과도한 노랑. **어느 획에도 없어야 한다.**
#      예외: ult/blood_tide (혈해만리) — 【채색 예외】. 소리내어 등록한다.
# ═══════════════════════════════════════════════════════════════════════════
QI_TEX_DIRS = ("qi", "ult", "kigi")   # kigi = 녹색 검기 (아래 KIGI 밴드가 잰다 — 등록형 색 예외)
QI_ACHROMATIC_MAX = 12        # qi/* 의 평균 채도 상한 — 이보다 크면 '무색'이 아니다
QI_HUE_TOLERANCE = 20         # 구워진 색상 ↔ 등록부 색상(옥·청백)의 허용 오차(도). 넘으면 빌더가 색을 지어낸 것이다
QI_CHROMA_FLOOR = 8           # ult/* 의 채도 하한 — 색이 죽으면 "최상위는 화려하다"가 거짓말이 된다
# ── kigi/* 검기(劍氣) — 【2026-07-20 사용자 결정: 등록부의 격 사다리를 따른다】 ──
#   ★ **연두 밴드 예외를 걷었다.** 그 예외는 레퍼런스 영상의 연두색을 통과시키려고 있던 것인데,
#     등록부(config/skill_motion.yml inks)의 사다리는 이미 색을 정해 놨다:
#     외공기 회백 → **검기 청회** → 강기 청록 → 어검 옥 → 심검 청백.
#     검기의 등록된 색은 **청회(124,143,152)** 이고 초록이 아니다.
#   ⇒ 이제 kigi/ 는 **다른 자**가 아니라 **등록부 자체**로 잰다 — 구운 색이 `청회` 에서 얼마나
#     떨어졌는가(QI_HUE_TOLERANCE). qi/* 무색판과 경로만 다를 뿐, **색의 출처는 하나**가 됐다.
#   왜 이게 나은가: 예외는 은닉이 아니어도 **자를 하나 더 만든다.** 자가 둘이면 둘 다 믿기 어렵다.
KIGI_INK = "청회"             # 등록부의 그 색 — inks 에서 읽는다 (여기 숫자를 적지 않는다)
# 【면제 — 소리내어 청구한다】 둘 다 "빛나지 않는 것이 곧 정보"인 자리다. 은닉이 아니라 등록이다.
QI_COLOR_EXEMPT = {
    "ult/blood_tide": ("혈", "【채색 예외】 마공의 혈조 — '저것은 사람의 기가 아니다'"),
    "ult/toxin_veil": ("무색", "【무형(無形)】 무형지독은 **빛나지 않는다** — "
                               "등록부가 이미 그렇게 적었다 (독무_범람 brightness 4)"),
}


def _ink_registry():
    """config/skill_motion.yml 의 inks — **색의 단일 진실 원천**. 파서 없이 필요한 줄만 읽는다."""
    src = (ROOT / "config" / "skill_motion.yml").read_text(encoding="utf-8")
    body = src.split("\ninks:", 1)
    if len(body) < 2:
        return {}
    out = {}
    for line in body[1].splitlines():
        if line and not line[0].isspace():
            break                                  # 다음 최상위 절 — inks 는 끝났다
        m = re.match(r"\s+(\S+):\s*\{\s*rgb:\s*\[\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\]", line)
        if m:
            out[m.group(1)] = (int(m.group(2)), int(m.group(3)), int(m.group(4)))
    return out


def _forbidden_hue(h, c):
    """사용자의 금지 규칙 — motion_audit 축 ⑪ 과 **같은 잣대**여야 한다 (두 자가 다르면 둘 다 거짓말이다)."""
    if c <= 20:
        return None                                # 회색에 색상은 없다
    if 265 <= h <= 345:
        return "네온 보라 · 형광 핑크"
    if 38 <= h <= 68:
        return "과도한 노랑"
    if c > 130:
        return f"형광 (채도 {c})"
    return None


def _hue(r, g, b):
    mx, mn = max(r, g, b), min(r, g, b)
    c = mx - mn
    if c == 0:
        return 0.0, 0
    if mx == r:
        h = 60 * (((g - b) / c) % 6)
    elif mx == g:
        h = 60 * (((b - r) / c) + 2)
    else:
        h = 60 * (((r - g) / c) + 4)
    return h % 360, c


def stroke_color_axis():
    """축 ⑱ — 획의 색. (위반 수)"""
    print("\n── 축 ⑱ 획의 색 — 【축 ⑫ 면제를 소리내어 청구하는 자리】 ──")
    print("     획은 자재가 아니라 **기(氣)** 다 (세계를 덮는 면이 아니라 공중에 뜨는 빛).")
    print("     ⇒ ⑫(저채도 밴드 · 자재 채도 ≤25 · 밝기 [128,172])는 **면제**한다 —")
    print("       그러나 **면제는 재지 않는다는 뜻이 아니다**: 아래 자로 잰다 (금지색 · 등록부 일치).")

    inks = _ink_registry()
    if not inks:
        print("  ❌ config/skill_motion.yml 의 inks 를 못 읽었다 — **색의 원천이 없으면 대조가 불가능**하다")
        return 1
    violations = 0
    base = PACK / "honcheon" / "textures" / "item"
    for sub in QI_TEX_DIRS:
        d = base / sub
        if not d.is_dir():
            print(f"  ⚠ {d} 없음 — 팩이 아직 안 구워졌다 (축 미측정)")
            continue
        for f in sorted(d.glob("*.png")):
            key = f"{sub}/{f.stem}"
            w, h, rows = read_png(f)
            ps = [px(rows, x, y) for y in range(h) for x in range(w) if px(rows, x, y)[3] > 8]
            if not ps:
                continue
            n = len(ps)
            avg = tuple(int(sum(p[i] for p in ps) / n) for i in range(3))
            hue, chroma = _hue(*avg)

            bad = _forbidden_hue(hue, chroma)
            if bad and key not in QI_COLOR_EXEMPT:
                violations += 1
                print(f"  ❌ {key:20s} rgb{avg} hue {hue:5.0f}° 채도 {chroma:3d} — **금지색**: {bad}")
                continue

            if key in QI_COLOR_EXEMPT:
                ink, why = QI_COLOR_EXEMPT[key]
                # 면제도 **잰다** — 무색을 청구했으면 정말 무색인지 본다 (면제는 백지수표가 아니다)
                ok = chroma <= QI_ACHROMATIC_MAX if ink == "무색" else True
                violations += 0 if ok else 1
                print(f"  {'✅' if ok else '❌'} {key:20s} rgb{avg} hue {hue:5.0f}° 채도 {chroma:3d}"
                      f" — {why}"
                      + ("" if ok else f"  ← **무색을 청구해 놓고 색을 칠했다** (채도 {chroma} > "
                                       f"{QI_ACHROMATIC_MAX})"))
                continue

            if sub == "qi":
                # 무색이어야 한다 — 여섯 격이 한 장을 나눠 쓴다 (색을 넣으면 외공기가 심검처럼 빛난다)
                ok = chroma <= QI_ACHROMATIC_MAX
                violations += 0 if ok else 1
                print(f"  {'✅' if ok else '❌'} {key:20s} rgb{avg} 채도 {chroma:3d} ≤ {QI_ACHROMATIC_MAX}"
                      f" — 무색 (격의 사다리가 이 판 한 장을 나눠 쓴다)"
                      + ("" if ok else "  ← **색이 들었다**: 외공기의 주먹이 심검처럼 빛난다"))
                continue

            if sub == "kigi":
                # 검기 — **등록부의 `청회` 에서 얼마나 떨어졌는가**로 잰다 (밴드 예외를 걷었다).
                #   자를 새로 만들지 않는다: ult/* 가 옥·청백을 재는 그 자(QI_HUE_TOLERANCE)를 그대로 쓴다.
                want = inks.get(KIGI_INK)
                if want is None:
                    print(f"  ❌ {key:20s} — 등록부에 먹빛 '{KIGI_INK}' 이(가) 없다 "
                          f"(config/skill_motion.yml inks)")
                    violations += 1
                    continue
                want_hue, _want_chroma = _hue(*want)   # _hue 는 (색상, 채도) 쌍을 준다
                d = abs(hue - want_hue)
                d = min(d, 360 - d)                       # 색상환은 둥글다
                ok = d <= QI_HUE_TOLERANCE
                violations += 0 if ok else 1
                print(f"  {'✅' if ok else '❌'} {key:20s} rgb{avg} hue {hue:5.0f}° 채도 {chroma:3d}"
                      f" — 등록부 {KIGI_INK} hue {want_hue:.0f}° 와 {d:.0f}° 차 (허용 {QI_HUE_TOLERANCE}°)"
                      + ("" if ok else f"  ← **{KIGI_INK} 에서 벗어났다** — 검기의 색은 등록부가 정한다"))
                continue

            # ult/* — 등록부의 옥·청백 **색상(hue)** 이어야 한다.
            #
            # 【잣대를 왜 hue 로 잡는가 — 첫 판이 틀렸다】 처음엔 평균 RGB 가 옥~청백 사이에 있는지를
            #   쟀다. 그런데 그 자는 **축 ⑬(명암차 ≥ 24)과 싸운다**: 획은 스미는 끝(어둡다)과 속(밝다)이
            #   있어야 하므로 평균은 필연적으로 그 둘 사이에서 **내려간다**. 두 축이 서로를 위반하게
            #   만드는 자는 자가 아니라 함정이다.
            #   ⇒ **밝기는 ⑬ 이 재고, 이 축은 색상만 잰다.** 획의 색이 옥·청록의 자리에 있는가,
            #     그리고 색이 죽지 않았는가(채도 하한). 창(窓)은 **등록부에서 유도한다** —
            #     여기 숫자를 박으면 등록부가 색을 바꿔도 눈이 옛 색을 고집한다.
            jade, pale = inks.get("옥"), inks.get("청백")
            if not jade or not pale:
                violations += 1
                print(f"  ❌ {key:20s} — 등록부에 옥·청백이 없다 (inks 를 지웠는가?)")
                continue
            hj, _ = _hue(*jade)
            hp, _ = _hue(*pale)
            lo, hi = min(hj, hp) - QI_HUE_TOLERANCE, max(hj, hp) + QI_HUE_TOLERANCE
            ok = lo <= hue <= hi and chroma >= QI_CHROMA_FLOOR
            violations += 0 if ok else 1
            why = ("" if ok
                   else ("  ← **색이 죽었다** (채도 %d < %d — 물들이지 않았거나 틴트가 씻겼다)"
                         % (chroma, QI_CHROMA_FLOOR) if chroma < QI_CHROMA_FLOOR
                         else "  ← **등록부의 색이 아니다** (빌더가 색을 지어냈다 —"
                              " config/skill_motion.yml inks 가 정본이다)"))
            print(f"  {'✅' if ok else '❌'} {key:20s} rgb{avg} hue {hue:5.0f}° 채도 {chroma:3d}"
                  f" — 색상 ∈ [{lo:.0f}°,{hi:.0f}°] (옥 {hj:.0f}° · 청백 {hp:.0f}°) · 채도 ≥ {QI_CHROMA_FLOOR}"
                  + why)
    print(f"  ⇒ 획의 색: 위반 {violations}건 · 채색 예외 {len(QI_COLOR_EXEMPT)}종 (등록됨)")
    return violations


def _paint(path, rgb):
    """PNG 의 **불투명 픽셀만** 한 색으로 덮는다 (알파는 보존 — 형체는 그대로 두고 색만 어긴다)."""
    w, h, rows = read_png(path)
    out = []
    for y in range(h):
        line = bytearray(rows[y])
        for x in range(w):
            if line[x * 4 + 3] > 8:
                line[x * 4], line[x * 4 + 1], line[x * 4 + 2] = rgb
        out.append(bytes(line))
    write_png(path, w, h, out)


def stroke_color_selftest():
    """★ 눈을 시험한다 — 축 ⑱ 에 **금지색을 먹여** 잡는지 본다. 반드시 되돌린다."""
    print("\n══ 축 ⑱ 눈 시험 (일부러 어긴다) ══")
    base = stroke_color_axis()
    if base:
        print(f"  ⚠ 기준선이 이미 위반 {base}건 — 시험의 뜻이 흐려진다")
    target = PACK / "honcheon" / "textures" / "item" / "ult" / "plum_bloom.png"
    if not target.exists():
        print("  ⚠ ult/plum_bloom.png 없음 — 시험 못 함")
        return 1
    keep = target.read_bytes()
    ok = True
    try:
        # ① 네온 보라를 굽는다 (사용자가 이름을 대어 금지한 바로 그 색)
        _paint(target, (190, 40, 230))
        n = stroke_color_axis()
        caught = n > base
        ok = ok and caught
        print(f"  {'✅' if caught else '❌'} 네온 보라를 구웠다 — {'잡았다' if caught else '**못 잡았다**'}")

        # ② 참격선(qi/*)에 색을 넣는다 — 사다리가 무너지는 자리
        arc = PACK / "honcheon" / "textures" / "item" / "qi" / "slash_arc.png"
        keep_arc = arc.read_bytes()
        try:
            _paint(arc, (60, 200, 170))
            n2 = stroke_color_axis()
            caught2 = n2 > n
            ok = ok and caught2
            print(f"  {'✅' if caught2 else '❌'} 참격선에 청록을 칠했다 (외공기가 심검처럼 빛난다)"
                  f" — {'잡았다' if caught2 else '**못 잡았다**'}")
        finally:
            arc.write_bytes(keep_arc)
    finally:
        target.write_bytes(keep)
    after = stroke_color_axis()
    restored = after == base
    print(f"  {'✅' if restored else '❌'} 팩을 되돌렸다 (위반 {after} == 기준선 {base})")
    print(f"  ⇒ 눈의 시험: {'통과' if ok and restored else '**실패**'}")
    return 0 if (ok and restored) else 1


# ═══════════════════════════════════════════════════════════════════════════
# 축 ⑲·⑳·㉑·㉒ — 【2026-07-14 신설 · RP 합의】 (docs/collaboration/RP_REVIEW_BOARD.md §합의)
#
# 넷의 공통 뿌리: 앞선 축들은 텍스처를 **파일로** 봤다. 그런데 화면에서 그림은 혼자 서지 않는다 —
#   획은 배경 **위에** 합성되고(⑲), 주사는 다른 색 **사이에서** 신호가 되고(⑳),
#   문장은 옆 단계와 **나란히** 읽히고(㉑), 게이지는 틴트가 **곱해진 채** 읽힌다(㉒).
# 네 축 모두 관계를 잰다. 그리고 예외는 전부 등록제다 (조용한 예외 금지 — NON_ICON 의 교훈).
# ═══════════════════════════════════════════════════════════════════════════
FONT = PACK / "honcheon" / "textures" / "font"

# ── 축 ⑲ 획 배경 합성 대비 — 「참격이 화선지에서도 먹에서도 보이는가」 ──
# 실측의 기록 (RP_REVIEW_CODEX §RP-3 · 보정 전): 참격 3종은 화선지 합성 가시 15~31% 였다 —
# 옛 4단의 232 가 화선지(Y≈231)와 ΔY 1 이라 **알파 255 여도 안 보였다.** 어두운 번짐 테(RP-3)가
# 그것을 고쳤고, 이 축이 회귀를 막는다. 형태를 죽이고 테만 칠하는 편법은 기존 기하·알파 감사
# (⑮ 아틀라스 · ⑯ 비율 · ⑰ 중심 · ⑱ 무색)와의 **병행 통과**가 막는다.
SLASH_BG = (("화선지", (238, 231, 214)), ("먹", (26, 24, 22)))   # #EEE7D6 · #1A1816
SLASH_ALPHA_MIN = 64          # 표본 — 이보다 옅은 픽셀은 '형태'가 아니라 여운이다
SLASH_P25_MIN = 16.0          # 합성 ΔY 의 1사분위 하한
SLASH_COVER_MIN = 0.70        # 가시(ΔY≥16) 픽셀 비율 하한
# 강제 대상 — 합의가 못 박은 참격 3종 (등록제: 넓히려면 여기 더하라. 나머지 qi/* 는 측정만 —
# blade_sheath 는 보정 전 실측 60.9% 로 미달이나 합의 범위 밖이다: 관측으로 소리내어 남긴다).
SLASH_CONTRAST_TARGETS = ("qi/slash_arc", "qi/slash_line", "qi/slash_ring")


def _luma(r, g, b):
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def _bg_deltas(path, bg):
    """배경 위 알파 합성 후 |ΔY| 목록 (알파 ≥ SLASH_ALPHA_MIN 표본만) — 오름차순."""
    w, h, rows = read_png(path)
    bg_y = _luma(*bg)
    out = []
    for y in range(h):
        for x in range(w):
            p = px(rows, x, y)
            if p[3] < SLASH_ALPHA_MIN:
                continue
            f = p[3] / 255.0
            out.append(abs(_luma(*(p[i] * f + bg[i] * (1 - f) for i in range(3))) - bg_y))
    return sorted(out)


def slash_contrast_axis():
    """축 ⑲ — 획 배경 합성 대비. 위반 수를 돌려준다."""
    print("\n── 축 ⑲ 획 배경 합성 대비 — 「참격이 화선지에서도 먹에서도 보이는가」 (RP-3) ──")
    d = PACK / "honcheon" / "textures" / "item" / "qi"
    if not d.is_dir():
        print("  ❌ item/qi/ 없음 — 팩이 안 구워졌다 (미측정은 통과가 아니다)")
        return 1
    violations = 0
    for f in sorted(d.glob("*.png")):
        key = f"qi/{f.stem}"
        enforced = key in SLASH_CONTRAST_TARGETS
        fails, cells = [], []
        for bg_name, bg in SLASH_BG:
            ds = _bg_deltas(f, bg)
            if not ds:
                fails.append(f"{bg_name} 표본 0")
                cells.append(f"{bg_name} 표본 0")
                continue
            p25 = ds[len(ds) // 4]
            cover = sum(1 for v in ds if v >= SLASH_P25_MIN) / len(ds)
            cells.append(f"{bg_name} p25 {p25:5.1f} 가시 {cover:5.1%}")
            if p25 < SLASH_P25_MIN or cover < SLASH_COVER_MIN:
                fails.append(f"{bg_name}: p25 {p25:.1f} (≥{SLASH_P25_MIN:.0f}) · 가시 {cover:.1%} (≥{SLASH_COVER_MIN:.0%})")
        mark = ("❌" if fails else "✅") if enforced else "⊖"
        tail = "" if enforced else "  (관측 — 합의 범위 밖: 강제하려면 SLASH_CONTRAST_TARGETS 에 등록)"
        if enforced and fails:
            violations += 1
            print(f"  {mark} {key:16s} " + " | ".join(cells) + " — **밝은 배경에서 획이 죽는다**: " + " · ".join(fails))
        else:
            print(f"  {mark} {key:16s} " + " | ".join(cells) + tail)
    print(f"  ⇒ 참격 3종 강제 · 위반 {violations}건 (표본 α≥{SLASH_ALPHA_MIN} · 양배경 각각 p25≥{SLASH_P25_MIN:.0f}·가시≥{SLASH_COVER_MIN:.0%})")
    return violations


# ── 축 ⑳ 의미 적색 면적 — 「주사(朱砂)가 신호로 남는가」 ──
# 등록부: config/resourcepack_design.yml semantic_red_registry — **표가 예외의 전부다.**
# 기본 GUI·아이템은 보이는 면적 ≤1%. 그보다 큰 적색은 등록된 경로만, 등록된 상한까지만.
# 미등록 초과·등록 상한 초과만 위반 (예외를 면제로 숨기지 않고 예외 자체를 잰다 — RP-5).
RED_DEFAULT_MAX = 0.01
RED_ALPHA_MIN, RED_SAT_MIN = 16, 0.35
RED_HUE = (335.0, 20.0)       # hue 335°~360° ∪ 0°~20°


def _red_registry():
    """semantic_red_registry.entries — [(글롭, max_ratio, 이유)…] 등록 순서대로 (첫 일치가 이긴다)."""
    src = (ROOT / "config" / "resourcepack_design.yml").read_text(encoding="utf-8")
    body = src.split("\nsemantic_red_registry:", 1)
    if len(body) < 2:
        return None
    out = []
    for line in body[1].splitlines():
        if line and not line[0].isspace():
            break                                  # 다음 최상위 절
        m = re.match(r'\s*-\s*\{\s*path:\s*"([^"]+)"\s*,\s*max_ratio:\s*([\d.]+)\s*,\s*reason:\s*"([^"]+)"', line)
        if m:
            out.append((m.group(1), float(m.group(2)), m.group(3)))
    return out


def _red_ratio(path):
    """(보이는 픽셀, 적색 픽셀) — 알파 ≥16 · HSV hue 335°~20° · 채도 ≥0.35."""
    w, h, rows = read_png(path)
    vis = red = 0
    for y in range(h):
        for x in range(w):
            r, g, b, a = px(rows, x, y)
            if a < RED_ALPHA_MIN:
                continue
            vis += 1
            mx = max(r, g, b)
            if mx == 0 or (mx - min(r, g, b)) / mx < RED_SAT_MIN:
                continue
            hue, _c = _hue(r, g, b)
            if hue >= RED_HUE[0] or hue <= RED_HUE[1]:
                red += 1
    return vis, red


def red_axis():
    """축 ⑳ — 의미 적색 면적. 위반 수를 돌려준다."""
    import fnmatch
    print("\n── 축 ⑳ 의미 적색 면적 — 「주사가 신호로 남는가」 (RP-5 · 등록부: semantic_red_registry) ──")
    reg = _red_registry()
    if reg is None:
        print("  ❌ config/resourcepack_design.yml 에 semantic_red_registry 가 없다 — 등록부 없는 예외는 잴 수 없다")
        return 1
    files = sorted((PACK / "honcheon" / "textures").rglob("*.png")) \
        + sorted((PACK / "minecraft" / "textures" / "gui").rglob("*.png"))
    violations = shown = 0
    for f in files:
        rel = str(f.relative_to(PACK)).replace("\\", "/")
        vis, red = _red_ratio(f)
        if not vis:
            continue
        ratio = red / vis
        hit = next(((pat, cap, why) for pat, cap, why in reg if fnmatch.fnmatch(rel, pat)), None)
        cap = hit[1] if hit else RED_DEFAULT_MAX
        if ratio > cap:
            violations += 1
            why = (f"등록 상한 초과 ({hit[2]})" if hit
                   else f"**미등록 적색** — 의미 없는 주사다 (기본 ≤{RED_DEFAULT_MAX:.0%}: 넘으려면 등록부에 이유와 함께)")
            print(f"  ❌ {rel}: 적색 {red}/{vis} = {ratio:.2%} > {cap:.0%} — {why}")
        elif hit and ratio > RED_DEFAULT_MAX:
            shown += 1
            print(f"  ✅ {rel}: {ratio:6.2%} ≤ {hit[1]:.0%} — {hit[2]}")
    print(f"  검사 {len(files)}장 (honcheon/** + minecraft gui/**) · 등록 {len(reg)}행 · "
          f"등록 예외 가동 {shown}건 · 위반 {violations}건")
    return violations


# ── 축 ㉑ 경지 인접 XOR — 「옆 단계와 나란히 놓아도 갈리는가」 ──
# 실측의 기록 (보정 전): 초절정↔화경이 **XOR 2px** — 윗줄 광점 둘뿐이라 8x8 실크기에서 같은
# 문장이었다 (RP-1). 하한 8px. 8단 문장의 인접쌍 7 을 전부 잰다 (grade_delta 와 같은 문법 —
# 문장은 2값이라 회색조 대신 알파 실루엣 XOR 로 충분하다).
CREST_XOR_MIN = 8
CREST_NAMES = ("삼류", "이류", "일류", "절정", "초절정", "화경", "현경", "생사경")


def crest_axis():
    """축 ㉑ — 경지 문장 인접 변별. 위반 수를 돌려준다."""
    print("\n── 축 ㉑ 경지 인접 XOR — 「옆 단계와 나란히 놓아도 갈리는가」 (RP-1 · 하한 8px) ──")
    masks = []
    for i in range(len(CREST_NAMES)):
        f = FONT / f"crest_{i}.png"
        if not f.exists():
            print(f"  ❌ {f.name} 없음 — 문장 8단이 다 구워져야 잰다")
            return 1
        w, h, rows = read_png(f)
        masks.append({(x, y) for y in range(h) for x in range(w) if px(rows, x, y)[3] >= 128})
    violations = 0
    for i in range(len(CREST_NAMES) - 1):
        xor = len(masks[i] ^ masks[i + 1])
        ok = xor >= CREST_XOR_MIN
        violations += 0 if ok else 1
        print(f"  {'✅' if ok else '❌'} {CREST_NAMES[i]}↔{CREST_NAMES[i + 1]}: XOR {xor:2d}px"
              + ("" if ok else f" < {CREST_XOR_MIN} — **옆에 두면 같은 문장이다** (실크기 8x8 에서 안 갈린다)"))
    print(f"  ⇒ 인접쌍 {len(CREST_NAMES) - 1} 전부 · 위반 {violations}건")
    return violations


# ── 축 ㉒ 게이지 계약 — 「붓홈은 같고 먹만 차오르는가 · 틴트가 곱해져도 읽히는가」 ──
# RP-2 의 네 못: ① 20x6 고정 ② 외곽 붓홈이 9단에서 **바이트까지 동일** (advance·실루엣 불변)
# ③ 불투명(α≥128) 픽셀 엄격 단조 증가 ④ 4틴트(SkillHud 부상 사다리 백/황/적/암적 — woundColor)
#   + 내력 옥청 을 곱해 화선지·먹 두 배경에 합성해도 만충↔공허가 갈린다 (내부 평균 ΔY ≥ 12).
#   ④가 없으면 "게이지가 있다"와 "게이지가 읽힌다"가 또 갈라선다 (실측: 옛 판은 황·옥청 틴트가
#   화선지에서 ΔY 11 — **찼는지 비었는지 안 보였다**).
GAUGE_SIZE = (20, 6)
GAUGE_TINT_MIN = 12.0
GAUGE_TINTS = (("백", (255, 255, 255)), ("황", (255, 255, 85)), ("적", (255, 85, 85)),
               ("암적", (170, 0, 0)), ("옥청", (85, 255, 255)))


def _gauge_pixels(n):
    f = FONT / f"gauge_{n}.png"
    if not f.exists():
        return None
    w, h, rows = read_png(f)
    return w, h, [[px(rows, x, y) for x in range(w)] for y in range(h)]


def _tinted_y(p, tint, bg):
    """글리프 픽셀에 틴트를 곱하고 배경에 알파 합성한 휘도 — 인게임 렌더 경로 그대로."""
    f = p[3] / 255.0
    return _luma(*(p[i] * tint[i] / 255 * f + bg[i] * (1 - f) for i in range(3)))


def gauge_axis():
    """축 ㉒ — 게이지 계약 (20x6 · 붓홈 고정 · 9단 단조 · 틴트 합성 시인성). 위반 수를 돌려준다."""
    print("\n── 축 ㉒ 게이지 계약 — 「붓홈은 같고 먹만 차오르는가」 (RP-2) ──")
    gs = []
    for n in range(9):
        g = _gauge_pixels(n)
        if g is None:
            print(f"  ❌ gauge_{n}.png 없음 — 9단이 다 구워져야 잰다")
            return 1
        gs.append(g)
    violations = 0
    # ① 치수
    bad = [n for n, (w, h, _p) in enumerate(gs) if (w, h) != GAUGE_SIZE]
    if bad:
        violations += 1
        print(f"  ❌ 치수: gauge_{bad} 가 {GAUGE_SIZE[0]}x{GAUGE_SIZE[1]} 이 아니다 — advance 가 흔들린다")
    else:
        print(f"  ✅ 치수: 9단 전부 {GAUGE_SIZE[0]}x{GAUGE_SIZE[1]}")
    w, h = GAUGE_SIZE
    # ② 붓홈(외곽 1px 고리) — 9단 동일
    rim = [(x, y) for y in range(h) for x in range(w) if y in (0, h - 1) or x in (0, w - 1)]
    diff = [n for n in range(1, 9) if any(gs[n][2][y][x] != gs[0][2][y][x] for x, y in rim)]
    if diff:
        violations += 1
        print(f"  ❌ 붓홈: gauge_{diff} 의 외곽이 gauge_0 과 다르다 — 홈틀이 흔들리면 실루엣·advance 계약이 깨진다")
    else:
        print("  ✅ 붓홈: 외곽 1px 고리가 9단에서 바이트까지 동일")
    # ③ 불투명(α≥128) 엄격 단조
    counts = [sum(1 for y in range(h) for x in range(w) if g[2][y][x][3] >= 128) for g in gs]
    mono = all(counts[i] < counts[i + 1] for i in range(8))
    violations += 0 if mono else 1
    print(f"  {'✅' if mono else '❌'} 단조: 불투명 픽셀 {counts}"
          + ("" if mono else " — **차오르지 않는 단이 있다** (엄격 증가가 아니다)"))
    # ④ 틴트 합성 시인성 — 만충(8) ↔ 공허(0) 내부 평균 ΔY
    worst, worst_key = 1e9, ""
    for t_name, tint in GAUGE_TINTS:
        for bg_name, bg in SLASH_BG:
            ds = [abs(_tinted_y(gs[8][2][y][x], tint, bg) - _tinted_y(gs[0][2][y][x], tint, bg))
                  for y in range(1, h - 1) for x in range(1, w - 1)]
            mean = sum(ds) / len(ds)
            if mean < worst:
                worst, worst_key = mean, f"{t_name}·{bg_name}"
            if mean < GAUGE_TINT_MIN:
                violations += 1
                print(f"  ❌ 틴트 {t_name} · {bg_name}: 만충↔공허 평균 ΔY {mean:.1f} < {GAUGE_TINT_MIN:.0f}"
                      f" — **찼는지 비었는지 안 보인다**")
    print(f"  ✅ 틴트 합성: {len(GAUGE_TINTS)}틴트 × 양배경 최악 {worst:.1f} ({worst_key}) ≥ {GAUGE_TINT_MIN:.0f}"
          if worst >= GAUGE_TINT_MIN else f"  ⇒ 틴트 최악 {worst:.1f} ({worst_key})")
    print(f"  ⇒ 게이지 계약: 위반 {violations}건")
    return violations


# ═══════════════════════════════════════════════════════════════════════════
# 축 ㉓ 고해상 페인트 — 「면제는 무법지대가 아니다」  【2026-07-16 신설 · V2-W 2차 청구】
#
# ── 왜 이 축이 생겼나 ──
# 사용자가 무기 디자인을 겐신 무기팩(TWC)급으로 지시했다. 그 문법 = 큰 평면 원소 +
# **64px 페인트 텍스처** (곡선 실루엣은 알파로). 그런데 축 ①(16x16)은 textures/item/**
# 전역(NON_ICON 밖)을 막아 고해상이 설 자리가 없었다 — V2-W 2차가 "16x16 2장 모자이크가
# 합법 상한"이라 적고 **등록형 면제 + 페인트 전용 축**을 정식 청구했다 (pack_upgrade_v2_3d §5).
#
# ── 법 (등록부 주도 — NON_ICON 처럼 하드코딩을 늘리지 않는다) ──
# config/resourcepack_design.yml hi_res_channels 에 등재된 글롭만 축 ①·⑥에서 벗어난다.
# 그리고 벗어난 자리를 **이 축이 다른 자로 잰다** (면제 = "재지 않는다"가 아니라 "다른 자로 잰다"):
#   ⓐ 치수 — 등록 sizes 의 **정사각 정확히** (과도기 [16,64]: 16=현행 2차 시트 · 64=3차 목표.
#      3차 뒤 등록부가 [64] 로 좁힌다 — 여기 코드는 등록부를 따라갈 뿐이다)
#   ⓑ 등록 팔레트 준수 — weapons.py 등록 색(강철 5단·놋 3단·주사·옥·백·삼줄)의 mix 는
#      계측 가능한 세 잣대로 남는다 (2026-07-16 현행 114장 실측이 근거 — 실측 + 여유):
#        · 파일 평균 채도 ≤ 60   (실측 최대 42.0 — bu_mabyeong_hilt)
#        · 픽셀 채도 > 160 비율 ≤ 1%  (등록 팔레트의 픽셀 최대 134 — mix 는 끝점을 못 넘는다)
#        · 팔레트에 없는 색상 대역 200°~330°(청·보라·마젠타) 비율 ≤ 1%  (실측 0픽셀)
#      ★ 축 ⑱의 _forbidden_hue 를 그대로 쓰면 안 된다 — 놋(hue ≈43°)이 "과도한 노랑"에
#        걸린다 (실측 43.8%). 획의 자를 페인트에 들이대면 거짓 위반이 뜬다 — 제 자를 짓는다.
#   ⓒ 적색 — semantic_red_registry **그대로** (paint 2행이 상한. 축 ⑳과 같은 자·같은 등록부 —
#      두 자가 다르면 둘 다 거짓말이다)
#   ⓓ 알파 실루엣 위생 【잠정 — 3차 실물이 나오면 실측으로 조인다】: 완전 투명(α≤8)/불투명(α≥248)
#      이분이 기본. 중간 알파는 **부드러운 가장자리**로만 허용 — 투명에서 체비쇼프 2px 이내 ·
#      전체의 ≤25%. 실루엣에서 떨어진 중간 알파(내부 안개)는 위반이다 (아틀라스에서 유령 번짐이 된다).
#   ⓔ 아틀라스 실존 (축 ⑮ 연동) — 클라이언트가 훑는 자리(atlases 원천)에 있고, 모델이
#      **실제로 부른다** (부르지 않는 등재 시트 = 죽은 짐 — 등록·실물·참조가 어긋난 것이다).
# ═══════════════════════════════════════════════════════════════════════════
HI_CHROMA_MEAN_MAX = 60        # 파일 평균 채도 상한 — 실측 최대 42.0 + 여유 (드리프트 감지선)
HI_PIXEL_CHROMA_MAX = 160      # 픽셀 채도 상한 — 등록 팔레트 픽셀 최대 134 (mix 는 끝점을 못 넘는다)
HI_PIXEL_CHROMA_FRAC = 0.01    # 상한 초과 픽셀 허용 비율
HI_FORBID_HUE = (200.0, 330.0) # 등록 팔레트에 없는 색상 대역 (실측 0픽셀 — 청·보라·마젠타)
HI_FORBID_FRAC_MAX = 0.01      # 금지 대역 픽셀 허용 비율 (채도 ≤20 회색은 색상이 없다 — 세지 않는다)
HI_MID_ALPHA = (8, 248)        # 이 사이 = 중간 알파 (완전 투명/불투명 이분의 바깥)
HI_MID_FRAC_MAX = 0.25         # 중간 알파 비율 상한 【잠정】
HI_EDGE_DIST = 2               # 중간 알파가 투명에서 떨어져도 되는 체비쇼프 거리 【잠정】


def _alpha_hygiene(w, h, rows):
    """(중간알파 비율, 내부 안개 픽셀 수) — ⓓ. 중간 알파는 투명 가장자리 2px 이내만 합법이다."""
    transp, mid = set(), set()
    for y in range(h):
        for x in range(w):
            a = px(rows, x, y)[3]
            if a <= HI_MID_ALPHA[0]:
                transp.add((x, y))
            elif a < HI_MID_ALPHA[1]:
                mid.add((x, y))
    if not mid:
        return 0.0, 0
    near = set(transp)
    for _ in range(HI_EDGE_DIST):                    # 투명을 2번 부풀린다 = 거리 ≤2 의 띠
        near |= {(x + dx, y + dy) for x, y in near
                 for dx in (-1, 0, 1) for dy in (-1, 0, 1)
                 if 0 <= x + dx < w and 0 <= y + dy < h}
    return len(mid) / (w * h), sum(1 for p in mid if p not in near)


def hi_res_axis():
    """축 ㉓ — 고해상 등록 채널 검수 (치수·팔레트·적색·알파·아틀라스). 위반 수를 돌려준다."""
    import fnmatch
    print("\n── 축 ㉓ 고해상 페인트 — 「면제는 무법지대가 아니다」 (등록부: hi_res_channels) ──")
    reg = hi_res_registry()
    if reg is None:
        print("  ❌ config/resourcepack_design.yml 에 hi_res_channels 가 없다 — "
              "등록부 없는 면제는 잴 수 없다 (is_icon 의 면제가 공중에 뜬다)")
        return 1
    try:
        dirs, _singles = atlas_sources()
    except Exception as e:
        dirs = [("block", "block/"), ("item", "item/")]
        print(f"  ⚠ 아틀라스 원천을 jar 에서 못 읽었다 ({e}) — 1.21.11 기본값(block·item)으로 잰다")
    refs = {(r if ":" in r else f"minecraft:{r}") for _f, _s, r in model_texture_refs()}
    red_reg = _red_registry() or []
    all_pngs = sorted(PACK.rglob("*.png"))
    violations = 0
    for glob_, sizes, why, pal in reg:
        mean_cap = pal.get("chroma_mean_max", HI_CHROMA_MEAN_MAX)
        px_cap = pal.get("chroma_pixel_max", HI_PIXEL_CHROMA_MAX)
        px_frac = pal.get("chroma_pixel_frac", HI_PIXEL_CHROMA_FRAC)
        hue_free = pal.get("hue_free", False)
        hits = [f for f in all_pngs
                if fnmatch.fnmatch(str(f.relative_to(PACK)).replace("\\", "/"), glob_)]
        if not hits:
            violations += 1
            print(f"  ❌ {glob_}: 등록됐으나 실물 0장 (등록부가 팩을 앞지른다)")
            continue
        worst_mean = worst_mid = 0.0
        size_seen = {}
        for f in hits:
            rel = str(f.relative_to(PACK)).replace("\\", "/")
            notes = []
            w, h = png_size(f)
            size_seen[(w, h)] = size_seen.get((w, h), 0) + 1
            if w not in sizes:                                 # ⓐ 치수 — 프레임 변길이
                notes.append(f"크기 {w}x{h} — 등록 해상도 {sizes} 의 변길이가 아니다")
            elif w != h:                                       # 세로 스트립 — 애니(등록 + .mcmeta)만
                meta = (f.with_name(f.name + ".mcmeta").exists()
                        or f"assets/{f.relative_to(PACK).parts[0]}/textures/"
                        f"{'/'.join(f.relative_to(PACK).parts[2:])}.mcmeta"
                        in set(client_jar().namelist()))
                if not (pal.get("animation") and h % w == 0 and meta):
                    notes.append(f"크기 {w}x{h} — 등록 애니 스트립(animation:true + h=n·{w} + "
                                 f".mcmeta)이 아니다 (정사각도 애니 스트립도 아니다)")
            w2, h2, rows = read_png(f)
            vis = [px(rows, x, y) for y in range(h2) for x in range(w2)
                   if px(rows, x, y)[3] > HI_MID_ALPHA[0]]
            if vis:                                            # ⓑ 등록 팔레트
                n = len(vis)
                mean_c = sum(max(p[:3]) - min(p[:3]) for p in vis) / n
                worst_mean = max(worst_mean, mean_c)
                hyper = sum(1 for p in vis if max(p[:3]) - min(p[:3]) > px_cap) / n
                forbid = 0
                if not hue_free:                   # 색상 자유 채널은 대역을 묻지 않는다 (등록제)
                    for p in vis:
                        hu, c = _hue(*p[:3])
                        if c > 20 and HI_FORBID_HUE[0] <= hu <= HI_FORBID_HUE[1]:
                            forbid += 1
                forbid /= n
                if mean_c > mean_cap:
                    notes.append(f"평균 채도 {mean_c:.0f} > {mean_cap:.0f} — 채널 등록 상한 밖이다 (형광 도배)")
                if hyper > px_frac:
                    notes.append(f"픽셀 채도 >{px_cap:.0f} 이 {hyper:.1%} > {px_frac:.0%} — 채널 상한 밖의 원색")
                if forbid > HI_FORBID_FRAC_MAX:
                    notes.append(f"금지 색상 대역({HI_FORBID_HUE[0]:.0f}°~{HI_FORBID_HUE[1]:.0f}°) {forbid:.1%} > "
                                 f"{HI_FORBID_FRAC_MAX:.0%} — 이 팩에 없는 색(청·보라·마젠타)")
            mid_frac, ghost = _alpha_hygiene(w2, h2, rows)     # ⓓ 알파 실루엣 위생
            worst_mid = max(worst_mid, mid_frac)
            if mid_frac > HI_MID_FRAC_MAX:
                notes.append(f"중간 알파 {mid_frac:.0%} > {HI_MID_FRAC_MAX:.0%} — 이분(투명/불투명)이 본체다")
            if ghost:
                notes.append(f"내부 안개 {ghost}px — 중간 알파가 실루엣 가장자리({HI_EDGE_DIST}px)에서 떨어져 있다")
            vis_n, red_n = _red_ratio(f)                       # ⓒ 적색 — 축 ⑳과 같은 등록부
            if vis_n:
                cap = next((c for pat, c, _r in red_reg if fnmatch.fnmatch(rel, pat)),
                           RED_DEFAULT_MAX)
                if red_n / vis_n > cap:
                    notes.append(f"적색 {red_n / vis_n:.1%} > {cap:.0%} — semantic_red_registry 상한 (축 ⑳과 같은 자)")
            parts = rel.split("/")                             # ⓔ 아틀라스 실존 (축 ⑮ 연동)
            ns, tex_rel = parts[0], "/".join(parts[2:])        # <ns>/textures/<...>.png
            if not any(tex_rel.startswith(src + "/") for src, _p in dirs):
                notes.append("아틀라스 밖 — 클라이언트가 훑지 않는 자리다 (스프라이트가 안 생긴다 ⇒ 보라 큐브)")
            elif f"{ns}:{tex_rel[:-4]}" not in refs:
                notes.append("모델이 부르지 않는다 — 죽은 등재 시트 (등록·실물·참조가 어긋났다)")
            if notes:
                violations += 1
                print(f"  ❌ {rel}: " + " · ".join(notes))
        sz = " · ".join(f"{w}x{h}×{n}" for (w, h), n in sorted(size_seen.items()))
        print(f"  {glob_}: {len(hits)}장 ({sz}) · 허용 {sizes} · 평균채도 최대 {worst_mean:.1f} "
              f"(≤{mean_cap:.0f}{' · 색상 자유' if hue_free else ''}) · 중간알파 최대 {worst_mid:.1%} — {why}")
    print(f"  ⇒ 고해상 등록 채널 {len(reg)}행 · 위반 {violations}건")
    return violations


def hi_res_selftest():
    """★ 축 ㉓ 의 눈 시험 — 심은 위반 네 갈래(크기·미등록 hi-res·과채도·적색) + 알파 안개.
    팩을 임시로 더럽히고 반드시 되돌린다 (try/finally)."""
    print("\n══ 축 ㉓ 눈 시험 (일부러 어긴다) ══")
    base = hi_res_axis()
    if base:
        print(f"  ⚠ 기준선이 이미 위반 {base}건 — 시험의 뜻이 흐려진다")
    ok = True
    pdir = PACK / "honcheon" / "textures" / "item" / "weapon" / "paint"

    # ① 등재 글롭 안에 32x32 를 심는다 — 등록 해상도(14차: 128) 밖의 치수
    f1 = pdir / "_selftest_mid.png"
    write_png(f1, 32, 32, [b"\x60\x60\x60\xff" * 32 for _ in range(32)])
    try:
        n = hi_res_axis()
        caught = n > base
        ok &= caught
        print(f"  {'✅' if caught else '❌'} [㉓ⓐ] 32x32 를 paint/ 에 심었다 — "
              f"{'잡았다' if caught else '**못 잡았다**'} ({base} → {n})")
    finally:
        f1.unlink()

    # ② **미등록** 고해상 — 면제가 넓어지지 않았는가 (축 ① 이 여전히 잡아야 한다)
    f2 = PACK / "honcheon" / "textures" / "item" / "weapon" / "_selftest_hires.png"
    write_png(f2, 64, 64, [bytes(v for x in range(64)
                                 for v in ((x * 3) % 200, (x * 3) % 200, (x * 3) % 200, 255))
                           for _ in range(64)])
    try:
        r = lint(f2, f2.stem)
        caught = r[2] and any("크기" in note for note in r[1])
        ok &= caught
        print(f"  {'✅' if caught else '❌'} [㉓→①] 미등록 64x64 를 weapon/ 에 심었다 — "
              f"축 ① 이 {'잡았다 (면제는 등재 글롭뿐이다)' if caught else '**못 잡았다 — 면제가 새 나갔다**'}")
    finally:
        f2.unlink()

    # ③ 과채도 — 등재 시트를 네온 보라로 (금지 대역 + 평균 채도 + 픽셀 채도 동시 위반)
    target = pdir / "bu_beomcheol.png"  # 3차 개명(시트 1장/자루) 추종 — 2026-07-16
    keep = target.read_bytes()
    try:
        _paint(target, (190, 40, 230))
        n = hi_res_axis()
        caught = n > base
        ok &= caught
        print(f"  {'✅' if caught else '❌'} [㉓ⓑ] 등재 시트를 네온 보라로 칠했다 — "
              f"{'잡았다' if caught else '**못 잡았다**'} ({base} → {n})")

        # ④ 적색 초과 — 시트를 통째로 주사로 (등록 상한 12% 를 훌쩍 넘는다)
        _paint(target, (200, 40, 44))
        n = hi_res_axis()
        caught = n > base
        ok &= caught
        print(f"  {'✅' if caught else '❌'} [㉓ⓒ] 등재 시트를 통째로 붉혔다 — "
              f"{'잡았다' if caught else '**못 잡았다**'} ({base} → {n})")

        # ⑤ 알파 안개 — 내부 한 덩이를 중간 알파로 (투명 가장자리에서 떨어진 자리)
        w, h, rows = read_png(target)
        out = []
        for y in range(h):
            line = bytearray(rows[y])
            for x in range(w):
                if 6 <= x <= 9 and 6 <= y <= 9:
                    line[x * 4 + 3] = 120
            out.append(bytes(line))
        write_png(target, w, h, out)
        n = hi_res_axis()
        caught = n > base
        ok &= caught
        print(f"  {'✅' if caught else '❌'} [㉓ⓓ] 내부에 중간 알파 안개를 심었다 — "
              f"{'잡았다' if caught else '**못 잡았다**'} ({base} → {n})")
    finally:
        target.write_bytes(keep)

    # ⑥ 세로 스트립 애니 (20차) — 모델이 부르는 등재 시트를 스트립으로: mcmeta 없으면 잡고
    #    (스트립은 등록만으론 부족), 있으면 통과 (죽은 시트 오염을 피하려 실참조 시트를 빌린다)
    strip_t = pdir / "sword_bobyeong.png"           # 실 시트(모델 참조) · 실물 mcmeta 없는 등급 고른다
    meta = strip_t.with_name(strip_t.name + ".mcmeta")
    keep_s = strip_t.read_bytes()
    keep_meta = meta.read_bytes() if meta.exists() else None   # 실물 mcmeta 가 있으면 보존
    write_png(strip_t, 128, 256, [b"\x60\x80\x70\xff" * 128 for _ in range(256)])
    try:
        n_nometa = hi_res_axis()
        caught = n_nometa > base
        ok &= caught
        print(f"  {'✅' if caught else '❌'} [㉓ⓐ-애니] mcmeta 없는 세로 스트립(128x256) — "
              f"{'잡았다 (스트립은 등록+ .mcmeta 라야 합법)' if caught else '**못 잡았다**'} ({base} → {n_nometa})")
        meta.write_text(json.dumps({"animation": {"frametime": 2}}), encoding="utf-8")
        n_meta = hi_res_axis()
        passed = n_meta == base
        ok &= passed
        print(f"  {'✅' if passed else '❌'} [㉓ⓐ-애니] .mcmeta 동반 세로 스트립 — "
              f"{'통과 (animation:true 등록 스트립은 합법)' if passed else '**거짓 위반**'} ({base} → {n_meta})")
    finally:
        strip_t.write_bytes(keep_s)
        if keep_meta is not None:
            meta.write_bytes(keep_meta)             # 실물 mcmeta 복원 (있었다면)
        elif meta.exists():
            meta.unlink()

    after = hi_res_axis()
    restored = after == base
    ok &= restored
    print(f"  {'✅' if restored else '❌'} 팩을 되돌렸다 (위반 {after} == 기준선 {base})")
    print(f"  ⇒ 눈의 시험 (㉓): {'통과' if ok else '**실패**'}")
    return 0 if ok else 1


def rp_axes_selftest():
    """★ 축 ⑲·⑳·㉑·㉒ 의 눈 시험 — 일부러 어겨서 실제로 잡는지 본다. 반드시 되돌린다."""
    print("\n══ 축 ⑲·⑳·㉑·㉒ 눈 시험 (일부러 어긴다) ══")
    ok = True

    # ⑲ — 참격의 먹을 화선지색으로 바랜다 (밝은 배경에서 죽는 값 236,231,214)
    arc = PACK / "honcheon" / "textures" / "item" / "qi" / "slash_arc.png"
    base = base19 = slash_contrast_axis()
    keep = arc.read_bytes()
    try:
        _paint(arc, (236, 231, 214))
        n = slash_contrast_axis()
        caught = n > base
        ok &= caught
        print(f"  {'✅' if caught else '❌'} [⑲] 참격을 화선지색으로 바랬다 — {'잡았다' if caught else '**못 잡았다**'} ({base} → {n})")
    finally:
        arc.write_bytes(keep)

    # ⑳ — ① 등록 자산의 상한 초과 (gui_ledger 를 통째로 주사로) ② 미등록 자산의 적색
    base = base20 = red_axis()
    ledger = FONT / "gui_ledger.png"
    crest0 = FONT / "crest_0.png"
    keep_l, keep_c = ledger.read_bytes(), crest0.read_bytes()
    try:
        _paint(ledger, (200, 40, 44))
        n = red_axis()
        caught = n > base
        ok &= caught
        print(f"  {'✅' if caught else '❌'} [⑳] 등록 자산(gui_ledger)을 상한 너머로 붉혔다 — {'잡았다' if caught else '**못 잡았다**'} ({base} → {n})")
        ledger.write_bytes(keep_l)
        _paint(crest0, (200, 40, 44))
        n = red_axis()
        caught = n > base
        ok &= caught
        print(f"  {'✅' if caught else '❌'} [⑳] 미등록 자산(crest_0)을 붉혔다 — {'잡았다' if caught else '**못 잡았다**'} ({base} → {n})")
    finally:
        ledger.write_bytes(keep_l)
        crest0.write_bytes(keep_c)

    # ㉑ — 화경을 초절정으로 덮는다 (XOR 0 — 옆에 두면 같은 문장)
    base = base21 = crest_axis()
    c4, c5 = FONT / "crest_4.png", FONT / "crest_5.png"
    keep5 = c5.read_bytes()
    try:
        c5.write_bytes(c4.read_bytes())
        n = crest_axis()
        caught = n > base
        ok &= caught
        print(f"  {'✅' if caught else '❌'} [㉑] 화경을 초절정 사본으로 덮었다 — {'잡았다' if caught else '**못 잡았다**'} ({base} → {n})")
    finally:
        c5.write_bytes(keep5)

    # ㉒ — ① 만충을 공허 사본으로 (단조·틴트 동시 위반) ② 5단을 4단 사본으로 (단조만)
    base = base22 = gauge_axis()
    g8, g0, g5, g4 = FONT / "gauge_8.png", FONT / "gauge_0.png", FONT / "gauge_5.png", FONT / "gauge_4.png"
    keep8, keep5g = g8.read_bytes(), g5.read_bytes()
    try:
        g8.write_bytes(g0.read_bytes())
        n = gauge_axis()
        caught = n > base
        ok &= caught
        print(f"  {'✅' if caught else '❌'} [㉒] 만충을 공허 사본으로 덮었다 — {'잡았다' if caught else '**못 잡았다**'} ({base} → {n})")
        g8.write_bytes(keep8)
        g5.write_bytes(g4.read_bytes())
        n = gauge_axis()
        caught = n > base
        ok &= caught
        print(f"  {'✅' if caught else '❌'} [㉒] 5단을 4단 사본으로 덮었다 (단조 위반) — {'잡았다' if caught else '**못 잡았다**'} ({base} → {n})")
    finally:
        g8.write_bytes(keep8)
        g5.write_bytes(keep5g)

    expect = base19 + base20 + base21 + base22
    after = slash_contrast_axis() + red_axis() + crest_axis() + gauge_axis()
    restored = after == expect
    ok &= restored
    print(f"  {'✅' if restored else '❌'} 팩을 되돌렸다 (위반 {after} == 기준선 {expect})")
    print(f"  ⇒ 눈의 시험 (⑲·⑳·㉑·㉒): {'통과' if ok else '**실패**'}")
    return 0 if ok else 1


def selftest():
    """★ 눈을 만들면 눈을 시험하라 — 일부러 어겨서 축 ⑮·⑯·⑰ 이 **실제로 잡는지** 본다.
    팩을 임시로 더럽히고 반드시 되돌린다 (try/finally)."""
    print("══ 축 ⑮·⑯·⑰ 눈 시험 (일부러 어긴다) ══")
    base_15, base_16, base_17 = atlas_axis(), aspect_axis(), center_axis()
    print(f"\n[기준] 고친 팩: ⑮ {base_15}건 · ⑯ {base_16}건 · ⑰ {base_17}건")
    if base_15 or base_16 or base_17:
        print("  ⚠ 기준이 0이 아니다 — 시험 이전에 팩이 이미 위반이다")

    ok = True
    # ── 시험 ③ 획 모델을 **옆으로 민다** (옛 '머리=원점' 계약을 되살린다: x −12 편향) ──
    mf = PACK / "honcheon" / "models" / "item" / "qi" / "slash_arc.json"
    orig = mf.read_text(encoding="utf-8")
    print("\n[시험 ③] qi/slash_arc 의 기하를 x −12 만큼 민다 (옛 '머리=원점' 편향을 되살린다)")
    doc = json.loads(orig)
    for e in doc["elements"]:
        e["from"][0] -= 12.0
        e["to"][0] -= 12.0
    mf.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    try:
        n = center_axis()
        caught = n > base_17
        ok &= caught
        print(f"  {'✅ 잡았다' if caught else '❌ 못 잡았다 — 눈이 거짓말했다'}"
              f" (⑰ {base_17} → {n})")
    finally:
        mf.write_text(orig, encoding="utf-8")

    # ── 시험 ① 텍스처를 아틀라스 밖으로 옮긴다 (item/qi/slash_arc → qi/slash_arc) ──
    src = PACK / "honcheon" / "textures" / "item" / "qi" / "slash_arc.png"
    out = PACK / "honcheon" / "textures" / "qi" / "slash_arc.png"
    print("\n[시험 ①] textures/item/qi/slash_arc.png → textures/qi/slash_arc.png (아틀라스 밖으로)")
    out.parent.mkdir(parents=True, exist_ok=True)
    src.rename(out)
    try:
        n = atlas_axis()
        caught = n > base_15
        ok &= caught
        print(f"  {'✅ 잡았다' if caught else '❌ 못 잡았다 — 눈이 거짓말했다'}"
              f" (⑮ {base_15} → {n})")
    finally:
        out.rename(src)
        try:
            out.parent.rmdir()
        except OSError:
            pass

    # ── 시험 ② 64x16 짜리 가로로 긴 텍스처를 아틀라스 안에 심는다 ──
    wide = PACK / "honcheon" / "textures" / "item" / "qi" / "_selftest_wide.png"
    print("\n[시험 ②] textures/item/qi/_selftest_wide.png 를 64x16 으로 굽는다 (비율 위반)")
    write_png(wide, 64, 16, [bytes(64 * 4) for _ in range(16)])
    try:
        n = aspect_axis()
        caught = n > base_16
        ok &= caught
        print(f"  {'✅ 잡았다' if caught else '❌ 못 잡았다 — 눈이 거짓말했다'}"
              f" (⑯ {base_16} → {n})")
    finally:
        wide.unlink()

    print(f"\n눈 시험: {'통과 — 눈이 실제로 잡는다' if ok else '실패 — 눈이 거짓말한다'}")
    return 0 if ok else 1


def _axis_15_16_17():
    return atlas_axis() + aspect_axis() + center_axis()


def lint(path, name):
    is_block = "/textures/block/" in str(path).replace("\\", "/")
    # 블록은 **눈에 닿는 모습**으로 읽는다 (프레임 첫 장 + 틴트 곱셈) — 위 「틴트 인지」 절
    w, h, rows = read_block(path, name) if is_block else read_png(path)
    notes, bad = [], False
    # 흐르는 유체는 바닐라가 **32x32** 다 (물길·용암길). 치수는 바닐라 계약이라 우리가 못 고른다
    want = (32, 32) if name in ("water_flow", "lava_flow") else (16, 16)
    # 16x16 은 **아이콘**의 계약이다 — 획(item/qi·ult)·가죽(item/mob)은 3D 모델의 면이라 열외고,
    # hi_res_channels 등재분(페인트 시트)도 열외다 — 그 자리는 축 ㉓ 이 치수·팔레트·적색·알파를 잰다
    # (is_icon 의 등록 주석 참조: 축을 피해 파일을 숨긴 것이 보라 큐브의 뿌리였다)
    if (w, h) != want and "block" in str(path) or (w, h) != (16, 16) and is_icon(path):
        notes.append(f"크기 {w}x{h} ({want[0]}x{want[1]} 아님)")
        bad = True
    pxs = opaque(rows, w, h)
    if not pxs:
        return name, ["전부 투명"], True
    colors = len({bytes(p[:3]) for p in pxs})
    lum = [0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2] for p in pxs]
    contrast = max(lum) - min(lum)
    avg = [sum(p[i] for p in pxs) / len(pxs) for i in range(3)]
    chroma = max(avg) - min(avg)
    # 이음매는 **블록의 면**에만 묻는다. 몹 가죽·글리프는 이어 붙는 면이 아니다
    # (w==h 로만 열었더니 32x32 몹 파츠·문장이 줄줄이 거짓 위반을 냈다 — 축의 범위를 좁힌다).
    seam = seam_score(rows, w, h) if (w == h == 16 or (is_block and w == h)) else 0

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
    if is_icon(path):                                # 축 6 — 외곽선 폐합 (**아이콘**의 규율)
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


def sheet(files, out_path, scale=8, cols=4, label_h=0, block=False):
    """확대 시트 — 사람의 눈이 보는 자리.

    ★ 블록 시트는 **read_block** 으로 읽는다: ① 프레임 시트는 첫 장만 (16x512 를 통째로 깔면
      칸 크기가 그것에 맞춰져 시트가 4억 9천만 픽셀이 된다 — 실제로 그렇게 터졌다)
      ② 틴트를 곱한다 (역틴트한 물은 파일로는 금빛이다. 사람이 검수하는 것은 **화면에 설 색**이다)."""
    tiles = []
    for f in files:
        try:
            w, h, rows = read_block(f, f.stem) if block else read_png(f)
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
    ap.add_argument("--selftest", action="store_true",
                    help="축 ⑮·⑯ 의 눈 시험 — 일부러 어겨서 눈이 실제로 잡는지 본다")
    ap.add_argument("--atlas-only", action="store_true",
                    help="축 ⑮·⑯ 만 — 낡은 팩에 새 눈을 대 볼 때 쓴다")
    args = ap.parse_args()

    if args.selftest:
        # 축 ⑮·⑯·⑰ + ⑱(획의 색) + **⑲·⑳·㉑·㉒ (RP 합의 축)** + ㉓(고해상 등록부)
        return selftest() + stroke_color_selftest() + rp_axes_selftest() + hi_res_selftest()
    if args.atlas_only:
        v = _axis_15_16_17()
        print(f"\n총평: 위반 {v}건 (축 ⑮·⑯·⑰)")
        return 1 if v else 0

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
    violations += stroke_axis()               # 축 ⑬ — 색이 맞아도 **그림**이 바닐라면 마인크래프트다

    print("\n── 축 ⑭ 명병 문파 변별 (흑백으로 보아도 문파가 갈리는가) ──")
    mp = myeong_pairs()
    for d, j, a, b in mp:
        if d < MYEONG_DELTA_MIN:
            violations += 1
            print(f"  ❌ {a} ↔ {b}: 회색조 변별 {d}px < {MYEONG_DELTA_MIN} "
                  f"(색을 빼면 같은 검이다 — 프롬프트가 금한 '색만 바꾼 재탕')")
    print(f"  회색조 변별 — 최소 {mp[0][0]}px ({mp[0][2]} ↔ {mp[0][3]}) · "
          f"중앙 {mp[len(mp) // 2][0]}px · 최대 {mp[-1][0]}px  (하한 {MYEONG_DELTA_MIN})")
    worst_j = max(mp, key=lambda t: t[1])
    print(f"  (참고) 실루엣 자카드 최대 {worst_j[1]:.3f} — {worst_j[2]} ↔ {worst_j[3]}. "
          f"검 계열끼리는 윤곽을 공유하는 것이 **옳다** (등록부: 화산·남궁·점창·종남·무당 모두 무기가 검이다).")
    _, cov_violations = palette_coverage()
    violations += cov_violations

    # ★ 축 ⑮·⑯ — 「그림이 좋은가」를 묻는 열네 축 뒤에 「그림이 **보이는가**」를 묻는다.
    #   이 둘이 없어서 검수가 위반 0건을 외치는 동안 사용자는 보라 큐브를 보고 있었다.
    violations += atlas_axis()
    violations += aspect_axis()
    violations += center_axis()      # 축 ⑰ — 보이기 시작하니 **엉뚱한 자리**에 서 있었다
    violations += stroke_color_axis()  # 축 ⑱ — 획의 **색** (⑫ 면제를 소리내어 청구하는 자리)

    # ★ RP 합의 축 (2026-07-14 · RP_REVIEW_BOARD §합의) — 관계를 잰다:
    #   획 대 배경(⑲) · 주사 대 등록부(⑳) · 문장 대 옆 단계(㉑) · 게이지 대 틴트(㉒)
    violations += slash_contrast_axis()   # 축 ⑲ — RP-3 (참격이 화선지에서 사라지던 자리)
    violations += red_axis()              # 축 ⑳ — RP-5 (예외를 숨기지 않고 예외 자체를 잰다)
    violations += crest_axis()            # 축 ㉑ — RP-1 (초절정↔화경 XOR 2px 이던 자리)
    violations += gauge_axis()            # 축 ㉒ — RP-2 (붓홈 고정·9단 단조·틴트 시인성)
    violations += hi_res_axis()           # 축 ㉓ — 고해상 등록부 (V2-W 2차 청구 — 면제는 무법지대가 아니다)

    if not args.lint_only:
        roof = PACK / "minecraft" / "textures" / "block" / "deepslate_tiles.png"
        if roof.exists():
            roof_mock(roof, OUT / "roof_mock.png")
            print(f"\n── 계단 지붕 목업 ──\n  {OUT / 'roof_mock.png'}")
        print("\n── 확대 시트 (8배) ──")
        for group, files in groups.items():
            if not files:
                continue
            res = sheet(files, OUT / f"{group}.png", block=(group == "blocks"))
            if res:
                print(f"  {res[0]}  ({len(res[1])}장)")

    print(f"\n총평: 위반 {violations}건")
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main())
