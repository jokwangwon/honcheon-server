"""공용 기반 — PNG 작성기·팔레트·좌표해시·획 + 3D 모델 공용 규약 (경로 상수 포함)."""
# 기계 분할 산출 — 원본: tools/build_resourcepack.py (pack_upgrade_v1.md §2 0단계).
# 로직 무수정: 함수 본문·상수 값은 원본 그대로다 (이동만 했다).
import json
import math
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent   # tools/respack/core.py — 원본(tools/*.py)보다 한 단계 깊다. 값은 동일 (저장소 뿌리)
PACK = ROOT / "resourcepack"
FONT_DIR = PACK / "assets" / "honcheon" / "textures" / "font"
GUI_DIR = PACK / "assets" / "minecraft" / "textures" / "gui"
HUD_DIR = GUI_DIR / "sprites" / "hud"        # 스프라이트 아틀라스 경로 (1.21.4~1.21.11 동일)
PACK_FORMAT = 75                            # 1.21.11 (client version.json · pack_version.resource_major)
CONTAINER_DIR = GUI_DIR / "container"        # 컨테이너 GUI는 스프라이트 분리 대상이 아님 — 기존 경로
# ─── 아이템·블록 텍스처 레이어 디렉터리 계약 (texture_layer_design.md §5.1) ───
ITEM_DEF_DIR = PACK / "assets" / "honcheon" / "items"                     # 아이템 정의 (1.21.4)
ITEM_MODEL_DIR = PACK / "assets" / "honcheon" / "models" / "item"         # 모델
ITEM_TEX_DIR = PACK / "assets" / "honcheon" / "textures" / "item"         # 16x16 아트
BLOCK_DIR = PACK / "assets" / "minecraft" / "textures" / "block"          # 전역 치환 (징발)
PAINTING_DIR = PACK / "assets" / "minecraft" / "textures" / "painting"    # 족자
PARTICLE_DIR = PACK / "assets" / "minecraft" / "textures" / "particle"    # 무공의 획 (스킬 모션)

W = (255, 255, 255, 255)   # 백색 — 인게임 색 코드가 틴트한다
T = (0, 0, 0, 0)           # 투명
INK = (26, 24, 22, 235)    # 먹색 — GUI 배경 전용 (틴트 불가 채널이라 직접 채색 허용)
INK_EDGE = (214, 205, 186, 255)   # 화선지 테두리
INK_LINE = (214, 205, 186, 150)   # 제목 구분선 — 화선지 톤 절반 농도
INK_GUIDE = (214, 205, 186, 70)   # 여백 가이드 점선 — 희미하게 (배경은 조용하게)
SEAL = (150, 56, 44, 255)         # 주사(朱砂) — 전각 도장풍 모서리 장식 전용 (저채도 진사홍)

# ─── 바닐라 HUD·컨테이너 교체 채널 팔레트 (직접 채색 허용 채널 —
#     resourcepack_design.yml vanilla_texture_channels 등록) ───
INK_SOLID = (26, 24, 22, 255)       # 불투명 먹 — HUD 테두리·패널 외곽선
# 체력 = 기혈 구슬(단약): 주사 채움 + 먹 테두리
# 빈 소켓(container)은 '구슬이 빠진 자리'다 — 평면 채움이면 구멍이 아니라 얼룩으로 보인다.
# 파인 자리의 문법(바닐라 인셋): 상·좌 안쪽이 그늘, 하·우 안쪽이 되비침 (빛은 좌상단).
ORB_OUT = (26, 24, 22, 255)         # 구슬 먹 테두리
ORB_SOCK_DARK = (44, 40, 36, 215)   # 빈 소켓 상·좌 안쪽 — 파인 그늘
ORB_EMPTY = (74, 68, 60, 195)       # 빈 소켓 바닥
ORB_SOCK_LIT = (152, 142, 124, 215)  # 빈 소켓 하·우 안쪽 — 바닥에서 되비친 빛 (립)
ORB_FILL = (150, 56, 44, 255)       # 주사 채움 (SEAL 동일 계열)
ORB_LIGHT = (208, 112, 88, 255)     # 좌상단 광택
ORB_DARK = (96, 34, 26, 255)        # 우하단 음영
BLINK_OUT = (240, 232, 214, 255)    # 피격 점멸 — 화선지 백 테두리 (바닐라 점멸=백화 관례)
BLINK_SOCK_DARK = (178, 170, 154, 225)
BLINK_EMPTY = (212, 204, 188, 205)
BLINK_SOCK_LIT = (248, 242, 228, 205)
BLINK_FILL = (208, 112, 88, 255)    # 점멸 채움 — 한 단계 밝은 주사
BLINK_LIGHT = (244, 202, 184, 255)  # 점멸 광택
BLINK_DARK = (150, 56, 44, 255)     # 점멸 음영
# 핫바 — 먹 반투명 패널 + 화선지 테두리. 이전 판은 테두리와 구분선이 **같은 RGB**(알파만 달랐다)라
# 실질 2색 = 완전 평면 회색 막대였다 (린트·육안 동시 확인). 색이 아니라 알파로만 명암을 주면
# 배경이 밝을 때 두 획이 한 색으로 붙어버린다 — HUD의 명암은 RGB로 새겨야 한다.
HOT_EDGE_HI = (222, 214, 196, 210)  # 상단·좌 화선지 테두리 — 빛 받는 모
HOT_EDGE_LO = (146, 138, 122, 210)  # 하단·우 테두리 — 그늘진 모 (테두리도 입체다)
HOT_INK_TOP = (56, 52, 46, 172)     # 패널 상단 — 옅은 먹 (세로 그라데이션 시작)
HOT_INK_BOT = (24, 22, 20, 188)     # 패널 하단 — 짙은 먹 (먹이 아래로 가라앉는다)
HOT_DIV = (12, 11, 10, 205)         # 슬롯 구분 — 먹선 1px (패널보다 짙어야 '그은 선'이다)
HOT_WEAR = (198, 190, 172, 130)     # 모서리 마모 — 화선지가 닳아 비친 자국
SEL_DARK = (92, 32, 24, 255)        # 선택 프레임 — 주사 그늘(바깥 테)
SEL_FRAME = (150, 56, 44, 255)      # 선택 프레임 — 주사 몸
SEL_HI = (212, 116, 92, 255)        # 선택 프레임 — 좌상 광 (이중 테의 안쪽 밝은 선)
SEL_SEAL = (72, 24, 18, 255)        # 네 귀 전각 점 — 가장 짙은 주사
# 인벤토리 컨테이너 — 화선지 몸체 + 먹 외곽 (명암 3톤은 바닐라 입체 문법 유지)
PAPER_BODY = (216, 208, 190, 255)   # 패널 몸체 — 화선지
PAPER_LIGHT = (238, 231, 214, 255)  # 상·좌 내부 프레임 밝은 획
PAPER_SHADOW = (172, 162, 140, 255) # 하·우 내부 프레임 음영
SLOT_DARK = (96, 86, 72, 255)       # 슬롯 상·좌 음영 (먹 계열)
SLOT_BASE = (176, 166, 146, 255)    # 슬롯 내부 (몸체보다 어둡게 — 바닐라 인셋 문법)
SLOT_LIGHT = (240, 234, 220, 255)   # 슬롯 하·우 광
WINDOW_DEEP = (22, 20, 18, 255)     # 인물 창 인셋 — 상·좌 파인 최암
WINDOW_INK = (44, 40, 35, 255)      # 인물 창 내부 — 짙은 먹 (순검정 금지: 검정은 구멍으로 보인다)
WINDOW_HAZE = (78, 71, 62, 255)     # 인물 창 아래 — 옅어지는 먹 안개 (인물이 설 바닥)
WINDOW_LIT = (96, 88, 76, 255)      # 인물 창 인셋 — 하·우 되비침
PAPER_FIBER = (228, 221, 204, 255)  # 화선지 결 — 몸체 위에 성기게 뜨는 밝은 섬유


def write_png(path: Path, rows):
    """최소 PNG 작성기 (RGBA, 무압축 필터 0)."""
    height, width = len(rows), len(rows[0])
    bad = [i for i, row in enumerate(rows) if len(row) != width]
    if bad:                                   # 아트 오타 방호 — 어긋난 행 하나가 PNG 전체를 깨뜨린다
        raise ValueError(f"{path.name}: 행 길이 불일치 (기대 {width}, 어긋난 행 {bad})")
    raw = b"".join(b"\x00" + b"".join(struct.pack("4B", *px) for px in row) for row in rows)

    def chunk(tag, data):
        payload = tag + data
        return struct.pack(">I", len(data)) + payload + struct.pack(">I", zlib.crc32(payload))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
                     + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def art_rows(art):
    return [[W if c == "#" else T for c in row] for row in art]


def paint_rows(art, palette):
    """다색 아트 — 문자→RGBA 팔레트 매핑 (미등록 문자는 투명)."""
    return [[palette.get(c, T) for c in row] for row in art]


# ═══════════════════════════════════════════════════════════════════════════
# 명암 엔진 — 픽셀 아트의 입체감은 '색'이 아니라 '계단'에서 온다.
# 빛은 좌상단에서 온다 (바닐라 관례). 위·왼쪽 밝게, 아래·오른쪽 어둡게.
# 난수 금지 — 결정론 해시(crc32)만. 같은 좌표는 언제나 같은 값.
# ═══════════════════════════════════════════════════════════════════════════
def mix(c1, c2, t):
    """RGBA 선형 보간 (t=0 → c1, t=1 → c2)."""
    return tuple(round(a + (b - a) * t) for a, b in zip(c1, c2))


def ramp(dark, light, n):
    """어두운색 → 밝은색 n단 명암 계단. 인덱스 0이 가장 어둡다.
    한 텍스처의 모든 픽셀이 이 계단 위에만 앉으면 색이 흩어지지 않는다 (팔레트 규율)."""
    return [mix(dark, light, i / (n - 1)) for i in range(n)]


def step(shades, v):
    """실수 명암값 v를 계단 인덱스로 스냅 — 계단 밖은 양 끝으로 클램프."""
    return shades[max(0, min(len(shades) - 1, int(round(v))))]


def luma(c):
    """화면 밝기 (Rec.709) — 눈이 실제로 세는 값. 검수(축 ⑬·⑮)와 **같은 자**를 쓴다."""
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


# ★★★ 【법칙 — 면(面)을 그리는 램프는 한 단이 획의 문턱보다 잘아야 한다 (단당 루마 ≤ 4)】
#   판자 증분이 찾은 구조적 병이다. 획의 문턱은 8루마인데 9~11단 램프의 한 단이 9~11루마다
#   ⇒ `step()` 의 **반올림이 단을 하나만 건너뛰어도 그것이 '획'으로 세어진다.**
#   양자화기가 잡티를 제조한다. 종이결(±1.5루마)조차 ±7루마의 단 넘김으로 증폭됐다.
#
#   그런데 그 증분은 판자에만 25단 램프를 손으로 박아 넣었다 (PLANK_STEPS). **법칙이 아니라 예외였다.**
#   석재는 여전히 COBBLE 11.2 · BRICK 8.9 루마/단이었다 — 전돌은 **단 하나가 문턱보다 굵다.**
#
#   ⇒ 이제 명암을 **단(step)이 아니라 루마(luma)로 적는다.** 램프는 팔레트에서 **파생**된다.
#     "줄눈을 -20루마" 라고 쓰면 팔레트가 무엇이든 그것은 20루마다 — 램프가 굵어서 획이
#     제멋대로 굵어지는 일이 구조적으로 불가능해진다. **축 ⑮ 가 이것을 검수한다.**
INK_STEP_LUMA = 3.6        # 면을 그리는 램프의 단당 루마 — 문턱(8.0)의 절반 아래


def ink_ramp(shades, target=INK_STEP_LUMA):
    """면(面)의 먹 — **잔 램프**와 「루마 → 단」 변환기를 함께 낸다.

    쓰는 법:  fine, L = ink_ramp(COBBLE_SHADES)  →  step(fine, L(밝기루마))
    팔레트의 양 끝(가장 어두운 색 · 가장 밝은 색)은 그대로 두고 **사이만 잘게 나눈다** —
    색은 하나도 바뀌지 않고(축 ⑫ 불변), 계조만 촘촘해진다."""
    lo, hi = luma(shades[0]), luma(shades[-1])
    span = abs(hi - lo)
    n = max(2, int(math.ceil(span / target)) + 1)        # 단당 루마가 target 을 넘지 않는 최소 단수
    fine = ramp(shades[0], shades[-1], n)
    per = span / (n - 1)
    return fine, (lambda L: (L - lo) / per)


def h32(*args):
    """결정론 해시 — crc32. 산술식(x*a+y*b)은 규칙적 사선 격자를 낳고, 사선 격자는 곧 무늬다."""
    return zlib.crc32(bytes(v & 0xFF for v in args))


def octave(x, y, cell, salt, amp):
    """셀 격자 결정론 노이즈 (계단형) — 진폭 ±amp. 입자·결 같은 고주파용.
    타일링 안전 조건: 16 % cell == 0 (셀 경계가 텍스처 경계와 맞아야 랩이 매끄럽다)."""
    return (h32(x // cell, y // cell, salt) % 1001 / 1000.0 * 2 - 1) * amp


def smooth_octave(x, y, cell, salt, amp):
    """격자 보간 결정론 노이즈 — 셀 모서리 4점을 smoothstep 이중선형 보간.
    계단형 octave를 저주파로 쓰면 셀이 '네모 얼룩'으로 보인다 (회칠이 아니라 모자이크가 된다).
    격자 인덱스를 n = 16 // cell 로 모듈러 → 텍스처를 이어 붙여도 노이즈장이 연속이다 (랩 안전)."""
    n = 16 // cell

    def corner(i, j):
        return h32((x // cell + i) % n, (y // cell + j) % n, salt) % 1001 / 1000.0 * 2 - 1

    tx, ty = (x % cell) / cell, (y % cell) / cell
    sx, sy = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)      # smoothstep — 셀 경계를 무디게
    a = corner(0, 0) + (corner(1, 0) - corner(0, 0)) * sx
    b = corner(0, 1) + (corner(1, 1) - corner(0, 1)) * sx
    return (a + (b - a) * sy) * amp


# ═══════════════════════════════════════════════════════════════════════════
# 획(劃)과 여백(餘白) — 동양화의 문법. 【2026-07 신설】
#
# 【진단 — 색은 맞췄는데 그림이 마인크래프트였다】
#   앞 증분이 채도·색상을 고쳤다 (청록산수: 자연 56.6 ↔ 자재 23.1 — 그 성과는 건드리지 않는다).
#   그런데 사용자는 여전히 *"일본/동양풍 리소스팩 기준으로 블럭 및 물 등 디자인을 수정"* 을 청했다.
#   재어 보니 그 말이 옳았다 — **색이 아니라 그림이 바닐라였다** (축 ⑬ 실측, 눈에 가장 많이 닿는 11장):
#
#       지표                     바닐라    우리(이전)
#       인접상관 (획이 있는가)     0.16      0.34      ← 획이 없다. 점을 흩뿌리고 있었다
#       고주파비 (잡티가 그리는가) 0.72      0.59      ← 그림을 **노이즈가** 그린다
#       여백비   (쉬는 면이 있는가) 32%       35%      ← 쉬는 자리가 없다
#     grass_block_top 은 상관 0.04 · 고주파 0.76 — **바닐라(-0.10 · 0.79)와 같은 그림**이었다.
#     물은 외려 바닐라보다 **더 시끄러웠다** (여백 24% · 바닐라 39%).
#
#   원인은 팔레트가 아니라 **연산**이다. 이 팩의 거의 모든 텍스처가 한 줄로 그려져 있었다:
#       step(SHADES, 기준값 + octave(...) + smooth_octave(...))
#   = **노이즈장을 램프에 양자화한 것**. 그런데 그것이 바로 **바닐라의 문법**이다 (픽셀마다 흩뿌린 잡티).
#   색을 갈아도 문법이 같으면 눈은 마인크래프트를 본다. 이 절이 그 문법을 바꾼다.
#
# 【동양화의 문법 — 세 마디】
#   ① 선(線)이 그린다 — 디테일은 **이어진 획**이 나른다. 픽셀마다 튀는 점이 아니다.
#   ② 여백(餘白)이 받친다 — 화면의 대부분은 **쉬는 면**이다. 획은 적고 **의도적**이다.
#   ③ 농담(濃淡)이 깊이를 준다 — 계조는 **부드럽고 방향이 있다** (난수가 아니다).
#
# 【팔레트는 한 톨도 안 건드린다】
#   램프(WATER_SHADES·DIRT_SHADES·TURF_SHADES…)는 **색의 계약**이다 (축 ⑫ — 위반 0건).
#   그것을 그대로 두고 **픽셀이 앉는 자리**만 바꾼다. 색은 앞 담당의 것이고, 그림이 내 것이다.
# ═══════════════════════════════════════════════════════════════════════════
def wash(x, y, salt, amp=1.0, cell=8):
    """농담(濃淡) — **저주파 계조만**. 고주파 잡티를 섞지 않는다 (여백을 지키는 바탕).

    earth_base 와의 차이가 이 증분의 핵심이다: 저 함수는 cell=1 옥타브(픽셀마다 튀는 점)를
    섞었고 **그것이 곧 바닐라의 그림이었다**. 여기엔 그 항이 없다 — 바탕은 쉬어야 한다."""
    return (smooth_octave(x, y, cell, salt, amp)
            + smooth_octave(x, y, max(2, cell // 2), salt ^ 0x27, amp * 0.48))


def level_set(field, w, h, k):
    """등고선(等高線) — 높이장의 **레벨 경계**를 1px 선으로 뽑는다. ★ 이것이 '획'을 만드는 기계다.

    난수를 흩뿌리면 **점**이 되지만, 매끄러운 장의 레벨 경계는 **반드시 이어진 곡선**이다
    (등고선은 끊기지 않는다 — 위상이 보증한다). 파문(波紋)·바위의 결(石理)이 전부 여기서 나온다.

    ★★ 【첫 시안의 버그 — 획이 소금·후추가 됐다】 처음엔 경계에서 **장의 부호**로 획의 밝기를 갈랐다.
      그런데 등고선 **위**에서 장의 값은 정의상 경계값 언저리다 — 부호가 이웃끼리 제멋대로 뒤집힌다.
      ⇒ 같은 곡선 위의 픽셀이 밝음/어두움을 오갔다 (실측: 물 고주파 0.29 → 0.63. **더 나빠졌다**).
      획을 그으려다 잡티를 뿌린 것이다. 교훈: **경계에서 결정하지 말라. 경계는 흔들리는 자리다.**
    ⇒ 이제 **레벨 번호**를 함께 돌려준다. 레벨은 면(band)의 성질이라 공간적으로 매끄럽다 —
      획의 밝기를 레벨로 정하면 한 곡선은 처음부터 끝까지 **한 밝기**다.

    반환: (bound, lv) — bound[y][x] = 경계인가 · lv[y][x] = 그 픽셀이 속한 레벨 번호.
    랩 안전: 이웃을 모듈러로 본다 (장이 정수 파수면 경계에서도 곡선이 이어진다)."""
    lv = [[math.floor(field[y][x] * k) for x in range(w)] for y in range(h)]
    bound = [[False] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            if lv[y][x] != lv[y][(x + 1) % w] or lv[y][x] != lv[(y + 1) % h][x]:
                bound[y][x] = True
    return bound, lv


def strokes(g_apply, salt, count, length, w=16, h=16, lean=0, vertical=True):
    """획을 심는다 — 짧고 이어진 선 몇 개 (잎날·결·긁힌 자국).

    자리는 해시로 흩는다 (격자 금지). 길이는 획마다 다르다 (같은 길이 = 도장 복제).
    g_apply(x, y, t) 를 획 위의 각 픽셀에 부른다 — t 는 0(밑동)~1(끝) 진행도.
    랩 안전: 좌표를 모듈러로 접는다 ⇒ 획이 경계를 넘어가도 이어진다."""
    for s in range(count):
        x0 = h32(s, salt, 0x1D) % w
        y0 = h32(s, salt, 0x2B) % h
        ln = length + h32(s, salt, 0x3F) % 2                  # 획마다 길이가 다르다
        tilt = lean * (1 if h32(s, salt, 0x4D) % 2 else -1)
        for k in range(ln):
            t = k / max(1, ln - 1)
            if vertical:
                x = int(round(x0 + tilt * k)) % w
                y = (y0 - k) % h                              # 위로 자란다
            else:
                x = (x0 + k) % w
                y = int(round(y0 + tilt * k)) % h
            g_apply(x, y, t)


ENTITY_DIR = PACK / "assets" / "minecraft" / "textures" / "entity"
def write_json(path: Path, data):
    """산출 JSON — ensure_ascii=True (F26: PUA·비ASCII 리터럴이 조용히 유실되지 않게)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")


# ═══════════════════════════════════════════════════════════════════════════
# 3D 모델층 — 병기 45자루 · 무공 획 9종 · 짐승 형체 8종
#
# 【왜 이 층이 생겼나】 지금까지 병기는 **평면 스프라이트를 두께 1px 로 밀어낸 것**이었다
# (minecraft:item/handheld 의 기본 동작). 칼날도 자루도 코등이도 같은 판때기의 일부라
# 손에 들면 종잇장이 서 있고, SkillDisplay 의 궤적에 실리면 종잇장이 날아다녔다.
#
# 【세 층이 같은 규약을 쓴다】
#   · 병기 — SkillDisplay 가 손에 든 ItemStack 을 그대로 실어 돌린다 (use_held).
#   · 획   — item_model 키를 얹은 형체를 SkillDisplay 가 띄운다.
#   · 짐승 — MobDisplay 가 본체를 감추고 조각을 태운다.
#   셋 다 ItemDisplayTransform.NONE 으로 그린다 ⇒ **display 절이 적용되지 않는다.**
#   그리는 것은 날것의 지오메트리다. 그러므로:
#
# 【모델 규약 — 불가침】
#   ㄱ. 길이축 = +X.  SkillDisplay.roll() 이 X 축으로 자전하고(§3-D "획은 +X 로 눕힌다"),
#      yaw·pitch 가 +X 를 진행 방향에 맞춘다. 병기도 같은 계약을 탄다 — 칼끝이 +X 다.
#   ㄴ. 두께축 = +Z.  날의 넓은 면이 ±Z 를 본다 (획과 같다).
#   ㄷ. 원점 = 모델의 (8,8,8).  ItemDisplay 는 모델을 **엔티티 자리에 중심을 두고** 그린다.
#      그래서 병기는 제 bbox 중심을 (8,8,8) 에 맞춘다 (_center 가 강제한다).
#   ㄹ. 짐승은 **코가 +Z** (MobDisplay 가 본체의 yaw 를 그대로 먹인다 — 이 계약이 깨지면
#      짐승이 옆으로 걷는다) · 등이 +Y · 원점(8,8,8) 이 **발이 딛는 바닥의 정중앙**.
#
# 【텍스처 — 새 PNG 를 굽지 않는다 (병기)】
#   병기의 여섯 면은 **이미 있는 16x16 아이콘 PNG 를 UV 로 찍어** 색을 얻는다.
#   아이콘은 검수 축 ⑥(외곽선)·⑨(계열 실루엣)·⑩(등급 변별)이 재는 진실이므로 손대지 않는다.
#   대신 그 격자(char grid)에서 **부위별 대표 픽셀 한 칸**을 뽑아 면마다 물린다 —
#   격자는 빌더가 만든 것이라 어느 칸이 무슨 재질인지 우리가 안다 (구멍이 원천적으로 없다).
#   면마다 다른 코드를 물리면(위=인, 아래=척) 마인크래프트의 면 음영과 겹쳐 입체가 산다.
# ═══════════════════════════════════════════════════════════════════════════

MODEL_DIR = PACK / "assets" / "honcheon" / "models"      # models/item/** (병기·획) · models/mob/** (짐승)

# ═══ 텍스처는 **아틀라스 안에** 살아야 한다 (2026-07 — 보라 큐브의 진짜 원인) ═══
# 【무엇이 틀렸었나】 획·오의·짐승 가죽을 textures/qi|ult|mob/ 아래 두었다. 이유는
#   "축 ⑥ 외곽선 폐합은 아이콘의 규율이지 반투명 획의 규율이 아니다" — 즉 **검수를 피하려고**
#   텍스처를 item/ 밖으로 옮긴 것이다. 그런데 그 자리는 **클라이언트가 안 훑는 자리**였다.
#
# 【클라이언트가 실제로 훑는 곳 — 1.21.11 jar 의 atlases/ 가 답이다 (짐작 아님)】
#   assets/minecraft/atlases/blocks.json → directory source "block" (prefix "block/")
#   assets/minecraft/atlases/items.json  → directory source "item"  (prefix "item/")
#   그 둘뿐이다. directory source 는 **모든 네임스페이스**의 assets/<ns>/textures/<source>/** 를
#   훑어 <ns>:<prefix><상대경로> 라는 스프라이트 이름을 만든다. 그래서 병기
#   (textures/item/weapon/*.png → honcheon:item/weapon/*) 는 꿰매지고 **보였다**.
#   반면 textures/qi/** 는 어느 아틀라스의 원천도 아니다 ⇒ 스프라이트가 없다 ⇒ **보라 큐브**.
#
# 【그래서 item/ 아래로 되돌린다 (방법 A)】 아틀라스 선언(atlases/*.json)을 새로 굽는 길(방법 B)도
#   있으나 택하지 않았다: 그 길은 "리소스팩의 atlases 는 덮어쓰기가 아니라 **누적**된다"는
#   런타임 가정에 기대는데, 이 프로젝트는 서버를 못 띄우므로 그 가정을 **시험할 수 없다.**
#   item/ 아래는 다르다 — 사용자의 게임에서 병기 45자루가 이미 그것으로 **보이고 있다**(대조군).
#   검증된 길을 두고 미검증 길을 가지 않는다.
#
# 【검수를 피해 도망가지 않는다】 item/ 아래로 오면 아이콘 축(⑥ 외곽선 등)의 사정거리에 든다.
#   그것은 **텍스처를 옮겨서** 풀 문제가 아니라 **축의 적용 범위를 등록제로 적어서** 풀 문제다
#   (texture_audit 이 이미 그 관행을 쓴다: SEAM_FACES · 대역 제외 등록).
QI_TEX_DIR = PACK / "assets" / "honcheon" / "textures" / "item" / "qi"     # 기의 획
ULT_TEX_DIR = PACK / "assets" / "honcheon" / "textures" / "item" / "ult"   # 오의
MOB_TEX_DIR = PACK / "assets" / "honcheon" / "textures" / "item" / "mob"   # 짐승 가죽


# ─── 회전 합성 — 손에 든 모습은 **바닐라에서 유도한다** (눈대중 금지) ───────────
# 바닐라 item/handheld 의 display 는 **대각선으로 누운 스프라이트**를 전제로 맞춰진 값이다
# (자루가 좌하, 칼끝이 우상 — 즉 병기의 길이축이 XY 평면의 +45°).
# 우리 모델의 길이축은 +X (0°) 다. 그러므로 모델을 먼저 Z축으로 +45° 돌려 스프라이트와 같은
# 자세로 만든 뒤 바닐라의 변환을 그대로 먹이면, **손·GUI·바닥에서 바닐라와 똑같은 자리에 놓인다.**
#     R = R_바닐라 · Rz(45°)
# 이 곱을 XYZ 오일러로 역산해 JSON 에 적는다. 검산(_check)이 재합성해 대조한다 —
# 각도를 손으로 짐작하면 병기가 손등을 뚫고 나온다. 짐작하지 않는다.
def _rot(axis, deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    if axis == "x":
        return [[1, 0, 0], [0, c, -s], [0, s, c]]
    if axis == "y":
        return [[c, 0, s], [0, 1, 0], [-s, 0, c]]
    return [[c, -s, 0], [s, c, 0], [0, 0, 1]]


def _mm(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def _legacy_euler_to_mat(r):
    """★사용 금지 (17차 봉인 — Codex 무기 3D 감사 §3). 옛 Rz·Ry·Rx 합성 — **MC 규약이
    아니다**. MC/JOML rotationXYZ 는 R = Rx·Ry·Rz (벡터에는 Rz 가 먼저 닿는다 —
    jar 바이트코드 실측, `_mc_euler_mat` 참조). 이 헬퍼로 display 회전을 합성·역산하면
    정면이 45° 눕는 v11 병리가 재발한다. 기록으로만 남긴다 — 새 코드에서 부르지 마라."""
    return _mm(_rot("z", r[2]), _mm(_rot("y", r[1]), _rot("x", r[0])))


def _mc_euler_mat(r):
    """MC 1.21.11 display 회전 — JOML Quaternionf.rotationXYZ(x,y,z) ≡ R = Rx·Ry·Rz
    (열벡터에는 Rz → Ry → Rx 순으로 닿는다. client jar 바이트코드 실측 — 17차)."""
    return _mm(_rot("x", r[0]), _mm(_rot("y", r[1]), _rot("z", r[2])))


def _mat_to_euler(m):
    """★사용 금지 (17차 봉인) — `_legacy_euler_to_mat` 의 짝 (Rz·Ry·Rx 역산). 기록용.
    R = Rz(γ)·Ry(β)·Rx(α) 역산 → [α, β, γ] (도). 짐벌 락(β=±90°)도 유효한 한 쌍을 돌려준다."""
    if abs(m[2][0]) > 0.999999:                       # β = ∓90° — α 와 γ 가 한 축으로 겹친다
        beta = -90.0 if m[2][0] > 0 else 90.0
        return [0.0, beta, math.degrees(math.atan2(-m[0][1], m[1][1]))]
    return [math.degrees(math.atan2(m[2][1], m[2][2])),
            math.degrees(math.asin(-m[2][0])),
            math.degrees(math.atan2(m[1][0], m[0][0]))]


def _check(r, m):
    """★사용 금지 (17차 봉인) — 옛 규약(`_legacy_euler_to_mat`) 기준 검산. 기록용."""
    back = _legacy_euler_to_mat(r)
    err = max(abs(back[i][j] - m[i][j]) for i in range(3) for j in range(3))
    if err > 1e-6:
        raise ValueError(f"오일러 역산 불일치 (오차 {err:.2e}) — 회전 합성이 깨졌다")
    return [round(v, 3) + 0.0 for v in r]


# 바닐라 item/handheld + item/generated 의 display (1.21.11 client jar 에서 그대로 옮겼다).
# gui 만 우리 값이다: 바닐라는 평면 스프라이트라 정면(회전 0)이 정답이지만, 3D 병기는
# 정면에서 보면 두께가 사라져 **예전과 똑같은 아이콘**이 된다. 살짝 틀어 두께를 보인다.
# ★ V2-W: 1인칭(fp)과 3인칭(hand)의 축소 상한을 갈랐다 — 리그 정교화로 병기가 길어지며
#   (물미·다단 곡률 날) 1인칭 화면에서 긴 병기가 시야를 가로지른다. 3인칭은 팔 비례가
#   기준이라 26px 상한을 유지하고, 1인칭만 22px 로 한 단 더 줄인다 (잠정값 —
#   resourcepack_design.yml model_channels.병기.display_변환 등재. 그림만 줄고 판정은 불변).
_VANILLA_DISPLAY = {
    "thirdperson_righthand": ([0, -90, 55], [0, 4.0, 0.5], 0.85, "hand"),
    "thirdperson_lefthand": ([0, 90, -55], [0, 4.0, 0.5], 0.85, "hand"),
    "firstperson_righthand": ([0, -90, 25], [1.13, 3.2, 1.13], 0.68, "fp"),
    "firstperson_lefthand": ([0, 90, -25], [1.13, 3.2, 1.13], 0.68, "fp"),
    "ground": ([0, 0, 0], [0, 2, 0], 0.5, "gui"),
    "head": ([0, 180, 0], [0, 13, 7], 1.0, "gui"),
    "fixed": ([0, 180, 0], [0, 0, 0], 1.0, "gui"),
    "gui": ([12, -28, 0], [0, 0, 0], 1.0, "gui"),   # 3/4 부감 — 날의 두께와 코등이가 보인다
}
_PRE_Z = 45.0        # 모델(+X) → 바닐라 스프라이트(+45°) 로 맞추는 선회전
_FP_PUSH_Z = -1.0    # ★17차 — 1인칭 translation z (바닐라 1.13 → -1.0, 카메라에서 0.13m 멀리).
                     # 사용자 실측(도 정련 1인칭 "화면 오른쪽이 무기 덩어리") 재현으로 확정:
                     # 정지 rest 포즈 자체가 병리였다 — 바닐라 배치(z 1.13)에서 날 끝이 눈앞
                     # 0.5m 에 오는데, 바닐라 검은 두께 1px 라 빗면 실루엣이 가늘게 남지만
                     # 혼천 병기는 날 폭 6~7px·단면 0.7~3.4px 볼륨이라 근접 복셀이 검은 벽 +
                     # 반투명 인선 슬래브 계단으로 화면을 채운다 (1080p 점유 8.2% ≈ 바닐라와
                     # 같은 면적인데 읽히지 않는 덩어리). z -1.0 = 점유 6.0% (바닐라의 0.70 —
                     # Codex §10-C 존재감 70~90% 계약 하한)이고 코등이·날·인선이 병기로 읽힌다.
                     # 회전·스케일 불변 (§11 행렬 계약은 회전만 잰다).【잠정 — 사용자 재실측 대기】


def _display(extent, body=None, fp_body=None):
    """병기 한 자루의 display 절. extent = 프레임 최대 변(px — 파편·수실 포함 합집합) —
    GUI 슬롯은 그림 전부가 들어가야 하므로 이걸로 줄인다. body = **본체** 최대 변
    (파편·드리개 제외 — weapons._body_extent) — 손에 드는 크기(hand/fp)의 분모다.
    창(2.1m)을 손 크기 그대로 GUI 칸에 넣으면 칸을 뚫고 나간다. 줄이는 것은 **그림뿐**이다 —
    궤적에 실리는 것은 NONE 변환의 날것이므로 창은 여전히 2.1m 다 (거짓말이 아니다).

    ★수리(2026-07-17, 인게임 시뮬 진단): 클라이언트의 display rotationXYZ 는 **Rx·Ry·Rz**
    (jar 바이트코드 실측 — 벡터에는 Rz 가 먼저 닿는다). 옛 코드는 Rz·Ry·Rx 규약의
    _euler_to_mat/_mat_to_euler 로 합성·역산해 **규약이 어긋났고**, 배포 회전이 칼끝을 화면
    안쪽으로 눕혔다 (그림 면 가시율 19~23% vs 바닐라 80% — 사용자 스크린샷 재현으로 검증).
    선회전 Rz(_PRE_Z) 는 게임 규약에선 **z 슬롯 가산**이 정답이다 (오른손 +45 · 왼손 거울 −45).
    행렬 기계는 쓰지 않는다 — 직접 기입.

    ★12차(2026-07-17, 사용자 3인칭 재실측 2건):
    ① "3인칭 검이 작다" — 종전 분모는 파편·수실까지 합친 프레임(검 36.8)이라 본체가 과축소됐다.
       hand/fp 분모를 본체(검 29.3)로 갈고 상한을 hand 26→30 · fp 22→24 로 올린다【잠정】 —
       단병기는 바닐라 검(0.85) 등가 존재감, 장병기(창 42.4·봉 39.5)는 팔 비례 선에서 감쇄.
    ② "도의 날이 앞뒤 반대" — 3인칭은 길이축(모델 +X) 180° 롤이 정답이다:
       R·Rx(180) ≡ 오일러 [rx+180, −ry, −rz] (Rx·Ry·Rz 규약 — 시뮬 항등 검증 오차 1e-16).
       인선(-Y)이 위-뒤 → **아래-앞**으로 서고(도의 휨·부의 수염이 바로 놓인다), F5 뒤 시점이
       북면(거울상) 대신 남면(정화 — 아이콘과 같은 상)을 본다. 1인칭·GUI 는 종전이 옳아 불변."""
    b = body or extent
    # ★21차-b (사용자 인게임: "범철 1인칭에서 검이 작아 보여 크기를 더 키워야") — 두 처방:
    #   ① fp 상한 24→26. ② fp 분모를 **자루별 본체 extent**(fp_body)로 — 12차 공유 분모(계열 최대)는
    #      날 짧은 범철을 계열 최장(신병)에 맞춰 과축소했다. fp 는 손에 든 그 자루 크기가 옳으니
    #      제 본체로 잰다 (범철 fp 0.584→0.641 ≈ 바닐라 0.68 근접 · 신병 등 최장은 불변). hand(3인칭)·
    #      GUI 는 공유 분모 유지 (호수 한 자리 계약). 잠정 — 인게임 확정 몫.
    bf = fp_body or b
    k = {"gui": min(1.0, 15.0 / extent), "hand": min(1.0, 30.0 / b),
         "fp": min(1.0, 26.0 / bf)}
    out = {}
    for mode, (rv, tv, sv, kind) in _VANILLA_DISPLAY.items():
        pre = -_PRE_Z if "lefthand" in mode else _PRE_Z
        s = round(sv * k[kind], 4)
        rot = [rv[0], rv[1], round(rv[2] + pre, 3)]
        if mode.startswith("thirdperson"):            # 12차 ② — 길이축 180° 롤 (위 주석)
            rot = [rot[0] + 180.0, -rot[1], -rot[2]]
        if kind == "fp":                              # 17차 — 1인칭 근접 병리 (_FP_PUSH_Z 주석)
            tv = [tv[0], tv[1], _FP_PUSH_Z]
        out[mode] = {"rotation": rot,
                     "translation": [round(v, 3) for v in tv],
                     "scale": [s, s, s]}
    return out


def _display_selftest():
    """★17차 (Codex 무기 3D 감사 §11) — display 회전의 행렬 자기시험. JSON 각도 문자열이
    아니라 **행렬**로 잰다 (동치 오일러 표현을 놓치지 않는다). 계약:
      R(fp_r) = R(바닐라 fp_r)·Rz(+45) · R(fp_l) = R(바닐라 fp_l)·Rz(-45)
      R(tp_r) = R(바닐라 tp_r)·Rz(+45)·Rx(180) · R(tp_l) = R(바닐라 tp_l)·Rz(-45)·Rx(180)
    (tp 의 Rx(180) = 12차 길이축 롤 — Codex §9-3 승인). 원소 최대 오차 ≤ 1e-6.
    임포트 시점에 돈다 — 계약이 깨지면 팩이 구워지기 전에 죽는다."""
    d = _display(16.0, 16.0)                          # k=1 — 회전만 본다 (스케일 무관)
    for mode, (post_z, roll) in {"firstperson_righthand": (45.0, False),
                                 "firstperson_lefthand": (-45.0, False),
                                 "thirdperson_righthand": (45.0, True),
                                 "thirdperson_lefthand": (-45.0, True)}.items():
        want = _mm(_mc_euler_mat(_VANILLA_DISPLAY[mode][0]), _rot("z", post_z))
        if roll:
            want = _mm(want, _rot("x", 180.0))
        got = _mc_euler_mat(d[mode]["rotation"])
        err = max(abs(got[i][j] - want[i][j]) for i in range(3) for j in range(3))
        if err > 1e-6:
            raise ValueError(f"display 행렬 계약 위반 [{mode}] — 오차 {err:.2e} "
                             f"(회전 {d[mode]['rotation']}) — §11 자기시험")


_display_selftest()


# ─── UV — 아이콘 격자에서 부위별 대표 픽셀을 뽑는다 ─────────────────────────
# 대체 사슬: 그 등급의 아이콘에 없는 코드(범철엔 고리도 수실도 없다)는 이웃 재질로 떨어진다.
# 마지막 보루는 K(먹 외곽선) — outline() 이 반드시 두르므로 **없을 수가 없다**.
_UV_CHAIN = {
    "H": "HLBSD", "L": "LHBSD", "B": "BLHSD", "S": "SBLDH", "D": "DSBLH",
    "G": "Ggf", "g": "gGf", "f": "fgG",
    "W": "WwxX", "w": "wWxX", "x": "xwWX", "X": "XxwW",
    "R": "ReGg", "e": "eRgG", "t": "tTGg", "T": "TtGg",
    "m": "mMHL", "M": "MmHL",
    # ─ 명병 문파 표식 (V2-W — 명병 전용 리그가 문양 부위를 갖게 되며 필요해졌다) ─
    "p": "pPt", "P": "Pp", "j": "jGg", "d": "dgG", "u": "uHL",
    "r": "rWw", "z": "zxX",
}


def _uv_index(grid):
    pos = {}
    for y in range(16):
        for x in range(16):
            c = grid[y][x]
            if c != "." and c not in pos:
                pos[c] = (x, y)
    return pos


def _uv(pos, code):
    """한 칸의 안쪽 절반을 문다 — 면 가장자리에서 이웃 픽셀 색이 새어들지 않는다."""
    for c in _UV_CHAIN.get(code, code) + "K":
        if c in pos:
            x, y = pos[c]
            return [x + 0.25, y + 0.25, x + 0.75, y + 0.75]
    raise ValueError(f"UV 없음: {code} (외곽선 K 조차 없는 격자다 — outline() 이 안 돌았다)")


# 부위 → 여섯 면의 코드. 위/아래를 다르게 물리는 것이 요점이다:
#   마인크래프트는 면 방향으로 음영을 준다(위 100% · 옆 80/60% · 아래 50%).
#   그 위에 우리가 인(刃)=밝음 / 척(脊)=어둠을 얹으면 **한날과 양날이 3D 에서 갈린다.**
_MAT = {
    "edge2": {"up": "H", "down": "H", "north": "L", "south": "L", "east": "H", "west": "B"},
    "edge1": {"up": "H", "down": "D", "north": "L", "south": "L", "east": "H", "west": "B"},
    "core": {"up": "L", "down": "S", "north": "B", "south": "B", "east": "H", "west": "B"},
    "spine": {"up": "D", "down": "D", "north": "D", "south": "S", "east": "D", "west": "D"},
    "fit": {"up": "G", "down": "f", "north": "g", "south": "g", "east": "G", "west": "f"},
    "grip": {"up": "W", "down": "X", "north": "w", "south": "w", "east": "x", "west": "x"},
    "ring": {"up": "R", "down": "e", "north": "R", "south": "R", "east": "e", "west": "e"},
    "tassel": {"up": "T", "down": "t", "north": "t", "south": "t", "east": "t", "west": "t"},
    "blood": {"up": "M", "down": "m", "north": "m", "south": "m", "east": "M", "west": "m"},
    # ─ 명병 문파 표식 부위 (V2-W 추가 — 기존 항은 무수정) ─
    "plum": {"up": "P", "down": "p", "north": "p", "south": "p", "east": "P", "west": "p"},
    "jade": {"up": "j", "down": "j", "north": "j", "south": "j", "east": "j", "west": "j"},
    "poison": {"up": "d", "down": "d", "north": "d", "south": "d", "east": "d", "west": "d"},
    "mist": {"up": "u", "down": "u", "north": "u", "south": "u", "east": "u", "west": "u"},
    "rope": {"up": "r", "down": "z", "north": "r", "south": "z", "east": "r", "west": "z"},
}


# ═══ 3D 전용 페인트 — 「평판 + 64px 그림」 【V2-W 3차 · 2026-07-16】 ═══════════════════
# 【사용자 지시】 겐신 무기팩(TWC)급 — 그 팩류의 실제 문법은 상자 쌓기가 아니라:
#   큰 평면 원소 몇 장 + **곡선 실루엣은 텍스처 알파로 깎기** + 음영·장식은 64px 페인팅.
# 【해상도의 법 — 2차의 "16x16 상한"은 폐지됐다 (감사 개정 2026-07-16)】
#   V2-W 2차가 청구한 등록형 면제가 섰다: config/resourcepack_design.yml `hi_res_channels` 에
#   등재된 글롭(weapon/paint/*.png)만 축 ①(16x16 아이콘 계약)·⑥(외곽선)에서 벗어나고,
#   그 자리를 **축 ㉓(고해상 페인트)** 이 다른 자로 잰다: 등록 해상도 정사각 · 등록 팔레트
#   (평균 채도 ≤60 · 픽셀 채도>160 ≤1% · 청보라 대역 ≤1%) · 적색(semantic_red_registry) ·
#   알파 이분(중간 알파는 투명 가장자리 2px 이내 · ≤25%) · 아틀라스 실존 + 모델 실참조.
#   3차부터 시트는 **64x64 1장/자루**다 (과도기 [16,64]는 3차 완료로 닫는다 — sizes [64]).
# 【역할 분담】 아이콘 16x16 = GUI·드롭·검수 축 ⑥⑨⑩⑭ 의 진실 (바이트 불변).
#   페인트 시트 = 3D 모델 전용 (#1): 본판(앞뒤 큰 면)이 시트의 일러스트 구역을 통째로 물고,
#   실루엣(도신의 휨·창날의 잎꼴·겸의 호)은 SDF 페인터의 알파가 깎는다.
#   구현: weapons.py V2-W 3차 절 (_stroke·_compose·_plate — 씨앗은 병기 키의 crc32).
PAINT_DIR = "weapon/paint"     # honcheon:item/weapon/paint/<이름>.png (128x128 · 자루당 1장 — 14차)


def _bake(elems, pos, mab=False):
    """부위 코드를 UV 로 굳힌다 — 상자 리그의 유산 규약 (V2-W 3차의 병기는 안 쓴다:
    평판 모델이 faces 를 직접 짓는다). 마병은 날의 속(core)이 혈조가 된다."""
    out = []
    for e in elems:
        mat = dict(_MAT[e.pop("_mat")])
        if mab and mat is not None and mat.get("north") == "B":
            mat["north"] = mat["south"] = "m"
        e["faces"] = {f: {"texture": "#0", "uv": _uv(pos, c)} for f, c in mat.items()}
        out.append(e)
    return out


def _center(elems):
    """bbox 중심을 (8,8,8) 로 옮긴다 — ItemDisplay 는 모델의 중심을 엔티티 자리에 두고 그리고,
    display 의 회전도 중심을 돈다. 중심이 어긋난 병기는 손에서 **궤도를 돈다**."""
    def span(i):
        lo = min(min(e["from"][i], e["to"][i]) for e in elems)
        hi = max(max(e["from"][i], e["to"][i]) for e in elems)
        return lo, hi

    d, ext = [], 0.0
    for i in range(3):
        lo, hi = span(i)
        d.append(8.0 - (lo + hi) / 2.0)
        ext = max(ext, hi - lo)
    for e in elems:
        for k in ("from", "to"):
            e[k] = [round(e[k][i] + d[i], 3) for i in range(3)]
        if "rotation" in e:
            e["rotation"]["origin"] = [round(e["rotation"]["origin"][i] + d[i], 3) for i in range(3)]
        for i in range(3):                       # MC 하한/상한 (-16 … 32) — 넘으면 모델이 잘린다
            if not (-16 <= e["from"][i] <= 32 and -16 <= e["to"][i] <= 32):
                raise ValueError(f"원소가 모델 상자를 벗어났다: {e['from']} → {e['to']}")
    return elems, ext


