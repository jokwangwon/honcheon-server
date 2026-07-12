#!/usr/bin/env python3
"""혼천 서버 리소스팩 컴파일러 — 결정론 생성 (맵과 같은 철학: 팩도 컴파일한다).

산출: resourcepack/ (팩 소스) — 기동 스크립트가 zip으로 묶는다.
글리프 (사설 영역 코드포인트, minecraft:default 폰트에 주입 — 채팅/액션바 어디서나 렌더):
  U+E000        기세 아이콘 (백색 — 채팅 색 코드로 틴트: 회/백/황/적)
  U+E010~E018   화후 게이지 0~8칸 (경락도 원장용)
                내력·원기 게이지는 같은 글리프를 색 틴트로 재사용 (슬롯 절약 — 설계 등록)
  U+E020~E027   경지 문장 8단: 삼류~생사경 (하위=획 수, 중위=봉우리/검, 상위=원환+중심)
  U+E080        경락도 GUI 배경 (먹색 패널 + 전각 도장풍 모서리 + 제목 구분선 + 여백 가이드,
                인벤토리 제목 음수 공백 기법용)
  U+E0A0~E0A5   음수 공백 (space 프로바이더: -8/-16/-32/-64/-128/+1 — E080 제목 오프셋용)
바닐라 텍스처 교체 (1.21.11 / pack_format 75 — 화면 HUD·인벤토리 수묵 재해석):
  hud/heart/    container·full·half (+_blinking) 9x9 — 하트 대신 기혈 구슬 (주사+먹)
  hud/          hotbar 182x22 (먹 반투명+화선지 테두리), hotbar_selection 24x23 (주사 프레임)
  gui/container inventory·generic_54 256x256 — 화선지 재채색 (슬롯 18x18 좌표는 바닐라 계약 불변)
  ※ XP 바는 내력 표시용 예약 — 기능·위치 불가침, 이번엔 바닐라 유지 (숨기기 금지)
아이템 채널 (docs/design/texture_layer_design.md §2 — item_model 컴포넌트, 전역 오염 0):
  honcheon/items/<key>.json         아이템 정의 (1.21.4) — item_model이 가리키는 곳
  honcheon/models/item/<key>.json   parent: handheld(무기) | generated(그 외)
  honcheon/textures/item/<key>.png  16x16 플레이스홀더 (2~3값 + 의미 강조)
  등급 = 베이스 바닐라 아이템(팩 게이트) / 계열 = model_key / 등급 표식 = 자루 고리 0~3 + 마병 혈적
블록 징발 (동 문서 §3 — 전역 치환. block_channels.징발에 등록된 것만):
  block/deepslate_tiles(+cracked)   흑와 — PNG 1장이 계단·반블록·담장 전부를 덮는다
  block/deepslate_bricks(+cracked)  흑와 직각 변형 — tiles의 90도 회전판 (동서 경사면용. ROOF_ISOTROPY)
  block/white|light_gray_terracotta 회벽 (무늬·이음선 금지 — 배들랜드 지층 무해화 조건)
  block/glass(+glass_pane_top)      격자창 / block/bamboo_planks 죽렴
  block/lantern·soul_lantern        등롱·백등롱 / block/chiseled_bookshelf_* 한약장 서랍
  painting/*                        족자 (1x1 소형 4종 — 수묵 산수·죽·서예·난)
의존성 없음 — 순수 표준 라이브러리 PNG 작성기.
"""
import json
import math
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
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


# ─── 체력바 — 하트 대신 기혈 구슬(단약) 9x9 (gui/sprites/hud/heart/) ───
# 바닐라 렌더 계약: container 먼저, 그 위에 full/half를 겹쳐 그린다 —
# half는 우측을 투명으로 남겨 아래 container 소켓이 비쳐 보이게 한다 (바닐라 동일 문법).
# 문자: # 테두리, s 소켓 상·좌 그늘, ~ 소켓 바닥, v 소켓 하·우 되비침, o 채움, L 광택, D 음영
# ★ 빈 소켓의 되비침(v)을 넓히고 밝혔다 — 양배경 가독 축(축 8)이 잡은 결함:
#   검은 배경에 합성했을 때 휘도차 55 이상인 픽셀이 **7개**뿐이었다. 즉 동굴·밤하늘 위에서
#   빈 구슬은 사실상 보이지 않았다 (남은 목숨을 못 세는 HUD는 HUD가 아니다).
#   원인은 소켓을 '검은 구멍'으로 칠했기 때문이다 — 그러나 돌에 판 구멍은 검지 않다:
#   맞은편 벽이 빛을 되받아 **밝은 립(lip)** 이 선다. 그 립을 대각으로 넓혀(4 → 10px) 밝히면
#   흰 배경에서는 먹 테두리가, 검은 배경에서는 립이 읽힌다 — 양쪽에서 사는 그림이 된다.
ORB_CONTAINER_ART = [
    ".........",
    "..#####..",
    ".#sssss#.",
    ".#ss~~v#.",
    ".#s~~vv#.",
    ".#s~vvv#.",
    ".#~vvvv#.",
    "..#####..",
    ".........",
]
ORB_FULL_ART = [
    ".........",
    "..#####..",
    ".#LLooo#.",
    ".#Loooo#.",
    ".#ooooo#.",
    ".#ooooD#.",
    ".#oDDDD#.",
    "..#####..",
    ".........",
]
ORB_HALF_ART = [   # 좌반만 — 절단면(D 열)으로 반 칸이 1초에 읽힌다
    ".........",
    "..###....",
    ".#LLD....",
    ".#LoD....",
    ".#ooD....",
    ".#ooD....",
    ".#oDD....",
    "..###....",
    ".........",
]
ORB_PALETTE = {"#": ORB_OUT, "s": ORB_SOCK_DARK, "~": ORB_EMPTY, "v": ORB_SOCK_LIT,
               "o": ORB_FILL, "L": ORB_LIGHT, "D": ORB_DARK}
BLINK_PALETTE = {"#": BLINK_OUT, "s": BLINK_SOCK_DARK, "~": BLINK_EMPTY, "v": BLINK_SOCK_LIT,
                 "o": BLINK_FILL, "L": BLINK_LIGHT, "D": BLINK_DARK}

# (이름, 아트, 팔레트) — 흡수(absorbing)·독(poisoned)·시듦(withered) 변형은 범위 밖: 바닐라 유지
HEART_SPRITES = [
    ("container", ORB_CONTAINER_ART, ORB_PALETTE),
    ("full", ORB_FULL_ART, ORB_PALETTE),
    ("half", ORB_HALF_ART, ORB_PALETTE),
    ("container_blinking", ORB_CONTAINER_ART, BLINK_PALETTE),
    ("full_blinking", ORB_FULL_ART, BLINK_PALETTE),
    ("half_blinking", ORB_HALF_ART, BLINK_PALETTE),
]


def gise_icon():
    """8x8 기세 아이콘 — 솟는 기운 (불꽃형).
    아래로 갈수록 넓어지는 화염 실루엣 + 우상단 분리 불티 1px = 상승 운동감 (1초 가독)."""
    return art_rows([
        "...#..#.",
        "...##...",
        "..###...",
        "..####..",
        ".#####..",
        ".######.",
        ".######.",
        "..####..",
    ])


BIMU_SHADES = {"L": (238, 234, 226, 255), "M": (190, 184, 172, 255),
               "D": (140, 134, 124, 255), "K": (96, 91, 84, 255)}


def bimu_icon():
    """8x8 비무 아이콘 — 교차한 목검(木劍) 두 자루 + 가운데 묶은 띠.

    비무는 **죽이지 않는 싸움**이다 — 그래서 날이 아니라 나무이고, 겨누지 않고 X로 걸린다.
    백색 계열 4단 명암: 인게임 색 코드가 틴트하되(회/백/황/적) 계단이 남아 납작하지 않다.
    (기세 아이콘 E000 은 2값이지만, 새 글리프는 린트 하한 4색을 지킨다)"""
    return paint_rows([
        "M......M",
        ".L....L.",
        "..L..L..",
        "...KK...",
        "...KK...",
        "..D..D..",
        ".D....D.",
        "M......M",
    ], BIMU_SHADES)


def gauge(filled: int):
    """20x6 화후 게이지 — 테두리 + 채움 (filled/8).
    눈금: 2단위 간격 상단 틱 3개 (x=5·9·14) — 빈 구간에만 보이고 채움에 흡수된다.
    끝단: 부분 채움(1~7)의 선두 열은 상하 1px 깎은 테이퍼 — 진행 끝이 한눈에 읽힌다.
          8칸 만충은 테이퍼 없이 우측 테두리 밀착 (완충 = 꽉 참)."""
    width, height, inner = 20, 6, 18

    def unit_px(k):
        return (inner * k + 4) // 8   # 반올림(half-up) — 단조 증가 보장

    fill_px = unit_px(filled)
    ticks = {unit_px(k) for k in (2, 4, 6)}
    rows = []
    for y in range(height):
        row = []
        for x in range(width):
            border = y in (0, height - 1) or x in (0, width - 1)
            in_fill = 1 <= y <= height - 2 and 1 <= x <= fill_px
            if in_fill and x == fill_px and 1 <= filled <= 7 and y in (1, height - 2):
                in_fill = False   # 끝단 테이퍼
            tick = y == 1 and x in ticks and x > fill_px
            row.append(W if border or in_fill or tick else T)
        rows.append(row)
    return rows


# ─── 경지 문장 8단 (E020~E027) — 8x8 2값 플레이스홀더 ───
# 디자인 언어 (1초 상호 구별):
#   하위 3단(삼류~일류) = 획 수 一二三 (윗획 짧게·아랫획 길게 — 서예 리듬)
#   중위 3단: 절정=봉우리(산), 초절정=검(劍), 화경=검+칼끝 검기 광점
#   상위 2단: 현경=8행 원환(空), 생사경=원환+만월 중심핵(滿)
# 정식 아트는 M3 전 교체형 (resourcepack_design.yml promotion_timing).
REALM_CRESTS = {
    "삼류": [
        "........",
        "........",
        "........",
        ".######.",
        "........",
        "........",
        "........",
        "........",
    ],
    "이류": [
        "........",
        "........",
        "..####..",
        "........",
        ".######.",
        "........",
        "........",
        "........",
    ],
    "일류": [
        "........",
        "..####..",
        "........",
        "..####..",
        "........",
        ".######.",
        "........",
        "........",
    ],
    "절정": [
        "........",
        "...#....",
        "..###...",
        "..###...",
        ".#####..",
        "#######.",
        "........",
        "........",
    ],
    "초절정": [
        "...#....",
        "..###...",
        "..###...",
        "..###...",
        ".#####..",
        "...#....",
        "...#....",
        "..###...",
    ],
    "화경": [
        ".#.#.#..",
        "..###...",
        "..###...",
        "..###...",
        ".#####..",
        "...#....",
        "...#....",
        "..###...",
    ],
    "현경": [
        "..####..",
        ".#....#.",
        "#......#",
        "#......#",
        "#......#",
        "#......#",
        ".#....#.",
        "..####..",
    ],
    "생사경": [
        "..####..",
        ".#....#.",
        "#..##..#",
        "#.####.#",
        "#.####.#",
        "#..##..#",
        ".#....#.",
        "..####..",
    ],
}


def hotbar():
    """182x22 핫바 (gui/sprites/hud/hotbar) — 먹 반투명 패널 + 화선지 테두리.
    182 = 테두리 2 + 슬롯 9칸 x 20px. 아이템은 x=3+20i 오프셋에 얹힌다 (바닐라 렌더 계약 — 불변).
    입체는 세 겹으로 온다: ① 테두리 상·좌 밝고 하·우 어둡게 ② 패널이 위→아래로 짙어지는
    먹 그라데이션 ③ 슬롯 경계 x=20·40·…·160 에 그은 1px 먹선.
    네 귀의 마모 자국은 결정론 해시로 — 종이가 닳아 먹이 벗겨진 자리다."""
    width, height = 182, 22
    body = height - 2
    rows = []
    for y in range(height):
        row = []
        for x in range(width):
            if y == 0 or x == 0:
                row.append(HOT_EDGE_HI)                    # 상·좌 — 빛 받는 모
            elif y == height - 1 or x == width - 1:
                row.append(HOT_EDGE_LO)                    # 하·우 — 그늘진 모
            elif x % 20 == 0 and x < 180:
                row.append(HOT_DIV)                        # 슬롯 구분 먹선
            else:
                # 패널 — 위에서 아래로 먹이 가라앉는다 (평면 채움 금지)
                row.append(mix(HOT_INK_TOP, HOT_INK_BOT, (y - 1) / (body - 1)))
        rows.append(row)
    # 네 귀 마모 — 테두리 안쪽 3x3 안에서만, 해시로 성기게 (난수 금지)
    for cy in (1, height - 2):
        for cx in (1, width - 2):
            for dy in range(3):
                for dx in range(3):
                    x = cx + (dx if cx == 1 else -dx)
                    y = cy + (dy if cy == 1 else -dy)
                    if h32(x, y, 0x4D) % 5 == 0:
                        rows[y][x] = HOT_WEAR
    return rows


def hotbar_selection():
    """24x23 선택 프레임 (gui/sprites/hud/hotbar_selection) — 주사 이중 테 + 투명 내부.
    이전 판은 2px를 한 색으로 채운 '빨간 네모' 한 겹이라 인장이 아니라 스티커로 보였다.
    두께(2px)는 그대로 두되 — 안쪽 1px을 밝은 주사로 올려 **이중 테**를 만든다:
    바깥이 그늘, 안쪽이 광. 그래야 테가 두께를 갖고 프레임으로 읽힌다.
    좌상은 빛을 받으므로 안쪽 선이 가장 밝고, 우하는 한 단 죽인다.
    네 귀에는 전각 도장풍 짙은 점 — 무협 UI의 서명.
    치수 주의: 1.21.4 바닐라 스프라이트는 24x24가 아니라 24x23 (Gui가 24x23으로 blit,
    하단 1px이 핫바 아래로 걸치는 바닐라 관례) — 치수·걸침 인게임 확인 필요."""
    width, height = 24, 23
    rows = []
    for y in range(height):
        row = []
        for x in range(width):
            ring = min(x, y, width - 1 - x, height - 1 - y)   # 0 = 바깥 테, 1 = 안쪽 테
            if ring == 0:
                row.append(SEL_DARK)                          # 바깥 — 주사 그늘 (배경과 분리)
            elif ring == 1:
                lit = x <= 1 or y <= 1                        # 좌·상 안쪽 선이 빛을 받는다
                row.append(SEL_HI if lit else SEL_FRAME)
            else:
                row.append(T)                                 # 내부 투명 — 아이템이 보여야 한다
        rows.append(row)
    for cy in (0, height - 1):                                # 네 귀 전각 점
        for cx in (0, width - 1):
            rows[cy][cx] = SEL_SEAL
    return rows


# ─── 인벤토리 컨테이너 (gui/container/) — 화선지 재채색, 슬롯 좌표는 바닐라 계약 그대로 ───
def blank_canvas(width=256, height=256):
    return [[T] * width for _ in range(height)]


def draw_panel(grid, gw, gh):
    """(0,0) 기준 gw x gh 패널 — 먹 외곽선 + 화선지 몸체.
    모서리는 바닐라 둥근 귀 관례를 2px 사선 컷으로 재현 (컷 밖 투명).
    상·좌 2px 밝은 획 / 하·우 2px 음영 — 바닐라 입체(양각) 문법 유지."""
    for y in range(gh):
        for x in range(gw):
            cx, cy = min(x, gw - 1 - x), min(y, gh - 1 - y)   # 가장 가까운 모서리까지 거리
            s = cx + cy
            if s < 2:
                continue                       # 모서리 컷 — 투명 유지
            if s == 2 or cx == 0 or cy == 0:
                grid[y][x] = INK_SOLID         # 먹 외곽선 (사선 포함)
            elif (x <= 2 or y <= 2) and x < gw - 3 and y < gh - 3:
                grid[y][x] = PAPER_LIGHT       # 상·좌 밝은 획
            elif x >= gw - 3 or y >= gh - 3:
                grid[y][x] = PAPER_SHADOW      # 하·우 음영
            else:
                # 화선지 결 — 성긴 섬유가 빛을 받는다. 균일 채움이면 종이가 아니라 플라스틱이다
                grid[y][x] = PAPER_FIBER if h32(x, y, 0xC5) % 11 == 0 else PAPER_BODY


def draw_seal_corners(grid, gw, gh):
    """네 귀 전각(篆刻) 도장풍 ㄱ자 쌍획 — 주사. 최소한으로: 팔 3px, 두께 1px, 4px 인셋.
    슬롯 좌표(최소 x=7, y=7)를 절대 침범하지 않는다 (3+3 = x 3..5 — 슬롯 앞에서 멈춘다)."""
    off, arm = 3, 3
    for oy, sy in ((off, 1), (gh - 1 - off, -1)):
        for ox, sx in ((off, 1), (gw - 1 - off, -1)):
            for a in range(arm):
                grid[oy][ox + sx * a] = SEAL   # 가로 팔
                grid[oy + sy * a][ox] = SEAL   # 세로 팔


def draw_slot(grid, sx, sy):
    """18x18 슬롯 — 바닐라 인셋 문법 그대로 (상·좌 음영, 하·우 광, 모서리 2px는 몸체 톤).
    좌표 계약: 슬롯 박스 = 아이템 좌표 - 1. 위치·크기 불변 — 색만 수묵 계열."""
    for dy in range(18):
        for dx in range(18):
            if (dx, dy) in ((17, 0), (0, 17)):
                c = SLOT_BASE                  # 바닐라 모서리 절충 픽셀
            elif dy == 0 or dx == 0:
                c = SLOT_DARK
            elif dy == 17 or dx == 17:
                c = SLOT_LIGHT
            else:
                c = SLOT_BASE
            grid[sy + dy][sx + dx] = c


def draw_window(grid, wx, wy, ww, wh):
    """인물 미리보기 창 — 먹빛 감실(龕室). 장식 요소, 슬롯 아님.
    이전 판은 내부를 순검정 한 색으로 채워 화선지 패널에 **구멍**이 뚫린 것처럼 보였다 (육안 확인).
    검정은 '없음'이지 '어두움'이 아니다 — 어두운 면도 빛을 받아야 면으로 읽힌다.
    → 먹 테두리 + 인셋 베벨(상·좌 최암 / 하·우 되비침) + 위에서 아래로 옅어지는 먹 그라데이션 +
      희미한 종이 결. 인물 모델이 그 앞에 서면 안개 낀 먹 배경으로 떠오른다."""
    for dy in range(wh):
        for dx in range(ww):
            x, y = wx + dx, wy + dy
            if dy in (0, wh - 1) or dx in (0, ww - 1):
                grid[y][x] = INK_SOLID                     # 먹 테두리
            elif dy == 1 or dx == 1:
                grid[y][x] = WINDOW_DEEP                   # 상·좌 안쪽 — 파인 최암 (인셋)
            elif dy == wh - 2 or dx == ww - 2:
                grid[y][x] = WINDOW_LIT                    # 하·우 안쪽 — 바닥에서 되비친 빛
            else:
                # 먹물이 위로 갈수록 짙다 — 인물의 발치가 밝아 서 있는 바닥이 생긴다
                t = (dy - 2) / max(1, wh - 5)
                c = mix(WINDOW_INK, WINDOW_HAZE, t)
                if h32(x, y, 0xB3) % 7 == 0:
                    c = mix(c, WINDOW_HAZE, 0.4)           # 종이 결 — 먹이 고르지 않게 스민다
                grid[y][x] = c


def inventory_container():
    """256x256 (유효 176x166) — 생존 인벤토리 (gui/container/inventory.png).
    슬롯 박스(아이템 좌표-1, InventoryMenu 계약): 방어구 (7,7+18r) r=0..3 /
    보조손 (76,61) / 제작 2x2 (97+18c,17+18r) / 제작 결과 (153,27) /
    본가방 (7+18c,83+18r) r=0..2 / 핫바열 (7+18c,141). 좌표 불변 — 색만 교체.
    인물 창(25,7,54x72)·제작 화살표는 장식 — 정확 위치 인게임 확인 필요."""
    grid = blank_canvas()
    draw_panel(grid, 176, 166)
    draw_seal_corners(grid, 176, 166)
    draw_window(grid, 25, 7, 54, 72)           # 인물 창 (장식 — 바닐라 근사)
    for r in range(4):
        draw_slot(grid, 7, 7 + 18 * r)         # 방어구 4
    draw_slot(grid, 76, 61)                    # 보조손 (창 우하단에 겹침 — 바닐라 동일)
    for r in range(2):
        for c in range(2):
            draw_slot(grid, 97 + 18 * c, 17 + 18 * r)   # 제작 2x2
    draw_slot(grid, 153, 27)                   # 제작 결과
    for x in range(135, 147):                  # 제작 화살표 — 먹 획 (장식)
        grid[31][x] = SLOT_DARK
        grid[32][x] = SLOT_DARK
    for dy, span in ((-2, 0), (-1, 1), (0, 2), (1, 1), (2, 0)):
        for dx in range(span + 1):
            grid[31 + dy][144 + dx] = SLOT_DARK   # 화살촉
    for r in range(3):
        for c in range(9):
            draw_slot(grid, 7 + 18 * c, 83 + 18 * r)    # 본가방 9x3
    for c in range(9):
        draw_slot(grid, 7 + 18 * c, 141)       # 핫바열
    return grid


def generic_54_container():
    """256x256 (유효 176x222) — 궤짝 6줄 (gui/container/generic_54.png).
    렌더 계약(ContainerScreen): 화면 = 텍스처 y0..(줄수*18+16) + y126..221 두 조각 —
    y=125 는 화면에 안 그려지는 심(seam) 행. 몸체가 균일 톤이라 심은 보이지 않는다.
    슬롯 박스(ChestMenu 계약, 아이템 좌표-1): 궤짝 (7+18c,17+18r) r=0..5 /
    플레이어 인벤 (7+18c,139+18r) r=0..2 / 핫바열 (7+18c,197) — 텍스처 기준 좌표
    (화면 좌표는 심 때문에 -1). 좌표 불변 — 색만 교체."""
    grid = blank_canvas()
    draw_panel(grid, 176, 222)
    draw_seal_corners(grid, 176, 222)
    for r in range(6):
        for c in range(9):
            draw_slot(grid, 7 + 18 * c, 17 + 18 * r)    # 궤짝 9x6
    for r in range(3):
        for c in range(9):
            draw_slot(grid, 7 + 18 * c, 139 + 18 * r)   # 플레이어 인벤 9x3
    for c in range(9):
        draw_slot(grid, 7 + 18 * c, 197)                # 핫바열
    return grid


# ─── 작업 컨테이너 GUI — 화로(火爐)·목공대. 인벤토리·궤와 같은 화선지 문법 ───
# 왜 이제서야: 인벤토리와 궤는 덮었는데 **화덕을 열면 바닐라가 튀어나왔다**. GUI는 전역이고
# 플레이어가 가장 오래 들여다보는 화면이다 — 한 장만 바닐라면 그 한 장이 팩 전체를 배신한다.
# 좌표 계약(바닐라 메뉴): 슬롯 박스 = 아이템 좌표 - 1. 위치·크기 불변, 색만 수묵으로.
#   화로(FurnaceMenu):  재료 (55,16) · 연료 (55,52) · 결과 (111,29)  [+ 불꽃 (56,36) · 화살 (79,34)]
#   훈연기·용광로: 같은 좌표 (SmokerMenu·BlastFurnaceMenu 는 화로와 같은 메뉴 배치)
#   목공대(CraftingMenu): 3x3 (29+18c, 16+18r) · 결과 (123,34) [+ 화살 (89,34)]
# 공통: 플레이어 인벤 (7+18c, 83+18r) · 핫바열 (7+18c, 141) — 인벤토리와 같다
FIRE_DARK = (58, 30, 22, 255)       # 불꽃 게이지 — 꺼진 자리 (파인 먹빛)
FIRE_LIT = (186, 96, 52, 255)       # 불꽃 — 타는 자리 (주사 계열. 불은 의미다 → 채도 허용)
ARROW_DIM = (150, 140, 122, 255)    # 진행 화살 — 빈 자리 (화선지보다 어둡게)


def draw_player_rows(grid):
    """플레이어 인벤 9x3 + 핫바열 — 모든 작업 GUI가 공유하는 아래 절반."""
    for r in range(3):
        for c in range(9):
            draw_slot(grid, 7 + 18 * c, 83 + 18 * r)
    for c in range(9):
        draw_slot(grid, 7 + 18 * c, 141)


def draw_arrow(grid, x0, y0, w=22):
    """진행 화살 — 먹 획 (바닐라의 '채워지는 화살'의 빈 상태. 채움은 코드가 blit 한다)."""
    for x in range(x0, x0 + w - 6):
        grid[y0 + 6][x] = ARROW_DIM
        grid[y0 + 7][x] = ARROW_DIM
    for dy, span in ((-3, 0), (-2, 1), (-1, 2), (0, 3), (1, 3), (2, 2), (3, 1), (4, 0)):
        for dx in range(span + 1):
            grid[y0 + 6 + dy][x0 + w - 6 + dx] = ARROW_DIM     # 화살촉


def furnace_container():
    """256x256 (유효 176x166) — 화로/훈연기/용광로 (gui/container/furnace|smoker|blast_furnace.png).
    세 화덕이 **같은 배치**를 쓴다 → 한 함수가 세 장을 굽는다 (바닐라도 같은 좌표다)."""
    grid = blank_canvas()
    draw_panel(grid, 176, 166)
    draw_seal_corners(grid, 176, 166)
    draw_slot(grid, 55, 16)                       # 재료 (약재·광석)
    draw_slot(grid, 55, 52)                       # 연료 (숯)
    draw_slot(grid, 111, 29)                      # 결과
    # 불꽃 게이지 14x14 (56,36) — 바닐라는 아래에서 위로 차오른다. 텍스처는 '꺼진 아궁이'다
    for dy in range(14):
        for dx in range(14):
            d = abs(dx - 6.5) + abs(dy - 10)      # 아궁이의 불 모양 (마름모 실루엣)
            if d < 7:
                grid[36 + dy][56 + dx] = FIRE_DARK
    draw_arrow(grid, 79, 28)                      # 제련 진행 화살 (79,34) 근방
    draw_player_rows(grid)
    return grid


def crafting_container():
    """256x256 (유효 176x166) — 목공대 (gui/container/crafting_table.png).
    슬롯 박스(CraftingMenu 계약): 3x3 (29+18c,16+18r) / 결과 (123,34)."""
    grid = blank_canvas()
    draw_panel(grid, 176, 166)
    draw_seal_corners(grid, 176, 166)
    for r in range(3):
        for c in range(3):
            draw_slot(grid, 29 + 18 * c, 16 + 18 * r)
    draw_slot(grid, 123, 34)                      # 결과
    draw_arrow(grid, 89, 28)
    draw_player_rows(grid)
    return grid


# 경락도 배경 — 먹 바탕은 '평면 검정'이 아니다. 종이에 스민 먹은 고이고 번진다.
# (린트가 색 3을 집은 이유: 테·구분선·가이드가 RGB는 같고 알파만 달랐다 — 세 획이 한 색이었다.)
LEDGER_INK = ramp((15, 14, 13, 235), (48, 45, 41, 235), 5)   # 먹 바탕 5단 — 고인 먹 → 엷게 번진 먹
LEDGER_EDGE_LIT = (238, 231, 214, 255)    # 화선지 테 — 위·왼쪽 (빛을 받는 마루)
LEDGER_EDGE = (214, 205, 186, 255)        # 화선지 테 — 몸
LEDGER_EDGE_DIM = (168, 158, 136, 255)    # 화선지 테 — 아래·오른쪽 (그늘)
LEDGER_SEAL_HI = (188, 82, 64, 255)       # 주사 — 각(刻)의 마루


def gui_background():
    """176x110 경락도 GUI 배경 패널 — 먹색 + 화선지 테두리 + 수묵 표구 장식.
    - 바탕: 먹 5단 저주파 얼룩 (먹이 고인 자리와 엷게 번진 자리)
    - 테두리: 2px 화선지 테에 빗각 — 위·왼쪽은 빛, 아래·오른쪽은 그늘 (패널이 떠오른다)
    - 모서리: 전각 도장풍 주사색 ㄱ자 쌍획 4귀 (인장 테두리 모티프, 4px 인셋·팔 6px·2px 두께)
    - 제목 구분선: y=17 가로선 — 상단 16px 제목 영역과 본문 분리
    - 여백 가이드: 5px 인셋 점선 사각 — 내용 배치 기준선 (희미한 화선지 톤, 배경은 조용하게)
    인벤토리 제목에 음수 공백으로 얹는 기법용. 정확한 오프셋은 인게임 튜닝 (플레이스홀더)."""
    width, height = 176, 110

    def wash(x, y, cell=24):
        """먹 얼룩 — 저주파 값 노이즈. GUI는 타일링하지 않으니 랩 모듈러가 없다
        (smooth_octave는 16px 랩용이라 여기 쓰면 16px마다 무늬가 되풀이된다)."""
        def corner(i, j):
            return h32(x // cell + i, y // cell + j, 0x5E) % 1001 / 1000.0

        tx, ty = (x % cell) / cell, (y % cell) / cell
        sx, sy = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
        a = corner(0, 0) + (corner(1, 0) - corner(0, 0)) * sx
        b = corner(0, 1) + (corner(1, 1) - corner(0, 1)) * sx
        return a + (b - a) * sy

    grid = [[step(LEDGER_INK, wash(x, y) * 4 + octave(x, y, 1, 0x6B, 0.45))
             for x in range(width)] for y in range(height)]

    # 테두리 — 2px 화선지 테 + 빗각. 위·왼쪽이 빛을 받고 아래·오른쪽이 그늘에 잠긴다
    for y in range(height):
        for x in range(width):
            near = min(x, y, width - 1 - x, height - 1 - y)
            if near > 1:
                continue
            lit = (y <= x and y <= width - 1 - x) or (x <= y and x <= height - 1 - y)
            if lit:                                    # 위·왼쪽 변
                grid[y][x] = LEDGER_EDGE_LIT if near == 0 else LEDGER_EDGE
            else:                                      # 아래·오른쪽 변
                grid[y][x] = LEDGER_EDGE_DIM if near == 0 else LEDGER_EDGE

    # 여백 가이드 — 5px 인셋 점선 사각
    inset = 5
    for x in range(inset, width - inset):
        if x % 2 == 0:
            grid[inset][x] = INK_GUIDE
            grid[height - 1 - inset][x] = INK_GUIDE
    for y in range(inset, height - inset):
        if y % 2 == 0:
            grid[y][inset] = INK_GUIDE
            grid[y][width - 1 - inset] = INK_GUIDE

    # 제목 구분선 — 제목 영역(상단 16px)과 본문 경계
    for x in range(10, width - 10):
        grid[17][x] = INK_LINE

    # 모서리 전각 도장풍 ㄱ자 쌍획 — 4귀, 가이드·구분선 위에 마지막으로 얹는다
    arm, thick, off = 6, 2, 4
    for oy, sy in ((off, 1), (height - 1 - off, -1)):
        for ox, sx in ((off, 1), (width - 1 - off, -1)):
            for t in range(thick):
                for a in range(arm):
                    # 안쪽 획이 마루(광), 바깥 획이 그늘 — 도장은 파인 것이라 두께에 명암이 있다
                    ink = LEDGER_SEAL_HI if t else SEAL
                    grid[oy + sy * t][ox + sx * a] = ink   # 가로 팔
                    grid[oy + sy * a][ox + sx * t] = ink   # 세로 팔
    return grid


# ═══════════════════════════════════════════════════════════════════════════
# 아이템 채널 — 개별 지정 (minecraft:item_model 컴포넌트, 세계 오염 0)
# 등록 원천: config/resourcepack_design.yml item_channels — 미등록 키 생성 금지 (등록제)
# 팩 게이트: 등급 = 베이스 바닐라 아이템(재질 색) / 계열 = model_key / 등급 = 자루 고리 수
# ═══════════════════════════════════════════════════════════════════════════
# ─── 무기 팔레트 — 등급 '색'은 바닐라 재질이 말한다. 텍스처가 말하는 건 재질감과 형태다.
#     강철은 평면 회색이 아니다: 인(刃)은 빛을 되쏘고, 몸은 중간이고, 등 쪽 사면은 그늘에 잠긴다.
#     4단 강철 계단 + 3단 놋 + 4단 자루 = 무기 한 자루에 최소 8색.
BLADE_HI = (232, 236, 242, 255)   # 인(刃) — 날이 빛을 되쏘는 선. 무기에서 가장 밝은 값
BLADE_LIT = (188, 193, 201, 255)  # 날 밝은 사면
BLADE_MID = (146, 151, 160, 255)  # 날 몸
BLADE_DIM = (104, 109, 119, 255)  # 날 그늘 사면
BLADE_SPINE = (72, 76, 85, 255)   # 척(脊) — 도의 두꺼운 등
FIT_HI = (198, 178, 128, 255)     # 호수(護手) 놋 — 광
FIT_MID = (150, 132, 92, 255)     # 놋 몸
FIT_DIM = (98, 84, 56, 255)       # 놋 그늘
GRIP_HI = (120, 96, 70, 255)      # 자루 감기 — 빛 받는 마루
GRIP_MID = (86, 68, 48, 255)      # 자루 몸
GRIP_DIM = (58, 45, 32, 255)      # 감기 사이 골
GRIP_DARK = (40, 31, 22, 255)     # 자루 깊은 골
RING_HI = (236, 230, 210, 255)    # 자루 고리(등급 표식) — 광
RING_MID = (176, 168, 144, 255)   # 고리 그늘 (2톤이라야 금속 테로 읽힌다)
TASSEL = (168, 62, 48, 255)       # 신병 수실 — 주사
TASSEL_HI = (206, 96, 78, 255)
BLOOD = (150, 32, 28, 255)        # 마병 혈적 — 다른 계보임을 형태로 선언 (위가 아니라 밖)
BLOOD_HI = (196, 56, 46, 255)
WPN_OUT = (24, 22, 20, 255)       # 먹 외곽선

WPN_PALETTE = {
    "H": BLADE_HI, "L": BLADE_LIT, "B": BLADE_MID, "S": BLADE_DIM, "D": BLADE_SPINE,
    "G": FIT_HI, "g": FIT_MID, "f": FIT_DIM,
    "W": GRIP_HI, "w": GRIP_MID, "x": GRIP_DIM, "X": GRIP_DARK,
    "R": RING_HI, "e": RING_MID,
    "t": TASSEL, "T": TASSEL_HI, "m": BLOOD, "M": BLOOD_HI, "K": WPN_OUT,
}

# 무기는 대각선으로 눕는다 (좌하 자루 → 우상 칼끝, 바닐라 아이템 관례).
# 대각선 띠를 (1,-1)·(1,1) 두 벡터로만 찍으면 격자의 절반(홀수 패리티)에 구멍이 남는다 —
# 외곽선을 두르는 순간 그 구멍이 전부 검게 메워져 체크무늬가 된다 (첫 시도의 실패, 실측 확인).
# 그래서 띠는 '한 걸음마다 한 행을 가로로 채우는' 계단식으로 찍는다 — 빈틈이 원천적으로 없다.
# 가닥(strands)은 그 행에서 왼쪽부터 오른쪽 순서다. 빛이 좌상단에서 오므로 왼쪽이 밝다:
# 첫 가닥이 인(刃)의 하이라이트, 마지막 가닥이 척(脊)의 그늘.
def band(grid, x0, y0, steps, strands, sx=1, sy=-1, vertical=False):
    """(x0, y0)에서 (sx, sy)씩 steps번 걸으며, 걸음마다 strands를 가로(기본)로 나란히 찍는다.
    strands = 문자 리스트, 또는 걸음 i를 받아 문자 리스트를 돌려주는 함수 (자루 감기용)."""
    for i in range(steps):
        use = strands(i) if callable(strands) else strands
        x, y = x0 + sx * i, y0 + sy * i
        for j, ch in enumerate(use):
            px, py = (x, y + j) if vertical else (x + j, y)
            if 0 <= px < 16 and 0 <= py < 16 and ch != ".":
                grid[py][px] = ch


def outline(grid, ch="K"):
    """실루엣 자동 외곽선 — 빈 칸이 채워진 칸과 상하좌우로 맞닿으면 먹으로 두른다.
    바닐라 아이템은 예외 없이 어두운 외곽선을 두른다 (인벤토리 배경에서 형태가 떠오르는 이유).
    손으로 외곽을 찍지 않으니 형태를 고쳐도 외곽선이 저절로 따라오고,
    행 길이가 어긋나는 아트 오타의 주된 원인 하나가 사라진다."""
    edge = [(x, y) for y in range(16) for x in range(16) if grid[y][x] == "."
            and any(0 <= x + dx < 16 and 0 <= y + dy < 16 and grid[y + dy][x + dx] != "."
                    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
    for x, y in edge:
        grid[y][x] = ch
    return grid


def blank16():
    return [["."] * 16 for _ in range(16)]


def wrap_grip(i):
    """자루 감기 — 걸음마다 밝은 마루와 어두운 골이 번갈아 온다 (가죽끈을 감은 결)."""
    return ["W", "w", "x"] if i % 2 == 0 else ["w", "x", "X"]


def thin_grip(i):
    """창(槍)의 자루 — 2가닥. 검·도의 3가닥보다 한 가닥 얇다.
    창은 '긴 막대에 촉을 단 것'이다. 자루가 날만큼 굵으면 그건 검을 길게 늘인 것에 지나지 않는다
    (실측: 3가닥 자루일 때 검과 창의 실루엣 자카드가 0.742 — 둘이 같은 대각선 띠였다)."""
    return ["W", "x"] if i % 2 == 0 else ["w", "X"]


def put_rings(grid, x0, y0, rings, slots, sx=-1, sy=1, width=3):
    """등급 고리 — 자루를 **감고 좌우로 삐져나온** 2톤 금속 테. slots = 자루 걸음 번호(위에서부터).

    colorblind_rule 의 집행자다: 색을 빼도 '고리 몇 개'로 등급이 읽혀야 한다.
    ★ 고리를 자루 폭에 딱 맞춰 찍던 첫 판은 그 규약을 못 지켰다 — 인접 등급 간 회색조 상이가
      2~3px 에 불과했다 (측정 축 10). 자루 안에만 있는 표식은 **실루엣을 바꾸지 못하고**,
      16px에서 3px 색 변화는 아무도 못 본다. 그래서 고리를 자루보다 좌우 1px씩 넓게 두른다 —
      테가 자루 밖으로 나오는 순간 그것은 **윤곽선의 사건**이 되고, 핫바에서 세어진다.
    width = 자루 폭 (검 3 · 창 2 · 부 4). 테는 언제나 width+2.
    """
    n = width + 2
    lit = (n + 1) // 2                       # 빛은 좌상단에서 온다 — 테의 왼쪽 절반이 마루
    strands = ["R"] * lit + ["e"] * (n - lit)
    for k in range(min(rings, len(slots))):
        i = slots[k]
        band(grid, x0 + sx * i - 1, y0 + sy * i, 1, strands)


def grade_butt(grid, x, y, rings, width=3, tassel=True):
    """자루 끝 — **등급이 자라는 곳**. 아홉 계열이 모두 공유하는 두 번째 표식이다.
    고리 하나(≈7px)만으로는 인접 등급 사이에 8px 계단을 못 만든다 (측정 축 10). 그래서 물미를 키운다:
      범철(0) 민자루 — 놋이 없다 (가장 싼 무기에 장식은 사치다)
      정련(1) 물미 — 자루 폭에 맞춘 놋 마구리
      보병(2) 물미가 자루 밖으로 벌어진다 (좌우 1px씩) — 실루엣이 바뀐다
      신병(3) + 수실 한 줄 — 물미 아래로 늘어진다
    (x, y) = 자루 끝 걸음의 왼쪽 위 칸. width = 자루 폭."""
    if rings == 0:
        return
    n = width if rings < 2 else width + 2
    lit = (n + 1) // 2
    band(grid, x - (0 if rings < 2 else 1), y, 1,
         ["G"] * (lit - 1) + ["g"] + ["f"] * (n - lit))
    if rings >= 3 and tassel:            # 수실 — 한 줄. 15행(캔버스 테두리)에 닿으면 안 된다:
        for k, ch in enumerate(("T", "t", "t")):   # 테두리엔 outline()이 먹을 두를 자리가 없다 (축 6)
            if 0 <= x - 1 + k < 16 and y + 1 < 16:
                grid[y + 1][x - 1 + k] = ch


def blade_strands(mabyeong, spine):
    """날 단면 4가닥: 인(가장 밝음) → 밝은 사면 → 혈조(血槽) → 등.
    혈조 자리가 마병에서는 혈적이 된다 — 형태는 같고 '피가 밴 홈'만 다르다.
    도(刀)는 한날이라 등이 두껍고 어둡다 (spine=True) — 검과의 차이는 오직 등의 그늘로 읽힌다."""
    return ["H", "L", "m" if mabyeong else "B", "D" if spine else "S"]


def _hilt(g, rings, mabyeong, gx, gy, guard, slots, guard_steps=5):
    """자루 한 벌 — 감기 5걸음 + 돌출 고리 + 놋 물미(등급 성장) + 신병 수실 + 마병 혈적.
    검·도·비수가 공유한다 (자루는 계열이 아니라 등급이 말하는 부위다).
    guard_steps = 코등이 걸음 수. 검은 길게 뻗은 가로대(5), 도는 뭉툭한 원반(2) —
    이 값이 같으면 검과 도가 같은 물건이 된다 (그것이 첫 판의 실패였다).

    ★ 자루 기준선을 한 행 올렸다 (gy 10 → 9). 물미가 14행에 있으면 수실이 15행 —
      캔버스 테두리 — 으로 밀려 outline()이 먹으로 두를 자리를 잃는다. 그러면 밝은 주사 픽셀이
      배경에 그대로 노출돼 아이템의 테가 끊긴다 (측정 축 6이 잡아낸 4건이 정확히 이것이었다).
      아이템 아트는 **사방 1px 여백**을 남긴다 — 그 여백이 외곽선의 자리다."""
    band(g, gx, gy, 5, wrap_grip, sx=-1, sy=1)
    band(g, *guard, guard_steps, ["G", "g", "f"], sx=1, sy=1)  # 코등이 — 날에 수직인 가로대
    put_rings(g, gx, gy, rings, slots)
    grade_butt(g, gx - 4, gy + 4, rings)                       # 물미 + 신병 수실 (등급이 자라는 곳)
    if mabyeong:                                               # 마병 혈적 — 자루 끝의 낙인
        g[gy + 4][gx - 2] = "M"


# ─── 검과 도는 '실루엣'으로 갈린다 ───────────────────────────────────────────
# 16px 핫바에서 명암 한두 단(척의 그늘)은 읽히지 않는다. 갈라야 하는 것은 윤곽이다:
#
#   검(劍): 얇고 · 곧고 · 좌우 대칭 (가운데 등줄기 鎬가 날 끝까지 곧게 뻗는다)
#           + 날에 수직으로 길게 뻗은 가로대 코등이 (양쪽으로 삐져나온 십자)
#   도(刀): 두껍고 · 배가 부르고 · 한쪽만 날 (등은 곧은데 인 쪽 배가 불러 끝에서 넓어진다)
#           + 뭉툭한 원반 호수 (가로대가 없다)
#
# 즉 '가로대가 있는가'와 '날 폭이 균일한가'가 실루엣 질문이다 — 둘 다 회색조에서 읽힌다.
def sword_grid(rings, mabyeong):
    """검(劍) — 곧은 양날. 폭이 끝까지 균일하고 좌우 대칭이다 (등줄기가 가운데).
    자루는 (6,9)에 앉는다 — 옛 (5,10)에서 날을 따라 한 칸 올라왔다. 물미가 13행에 서야
    수실이 14행에 걸리고, 15행(테두리)에 밝은 픽셀을 흘리지 않는다 (측정 축 6)."""
    g = blank16()
    ridge = "m" if mabyeong else "H"                          # 등줄기(鎬) — 마병은 혈조가 된다
    for i in range(7):                                        # 날 (6,9) → (12,3), 폭 3 균일
        band(g, 6 + i, 9 - i, 1, ["L", ridge, "L"], vertical=True)
    band(g, 13, 3, 1, ["H", "L"], vertical=True)              # 끝 좁힘
    band(g, 14, 3, 1, ["H"], vertical=True)                   # 칼끝
    _hilt(g, rings, mabyeong, 6, 9, (4, 7), (1, 2, 3))        # 긴 가로대 코등이
    return g


# 도의 날 — (x, y_top, 세로 가닥). 등(D)은 곧은 대각선인데 인(H) 쪽 배가 불러
# 중간에서 가장 넓고 끝에서 좁아진다. 이 '배'가 곡선을 만든다 (16px에서 곡률보다 폭 변화가 읽힌다).
# ★ 배를 한 가닥 더 불렸다 (4 → 5). 비수와의 실루엣 자카드가 0.760 이었다 (측정 축 9) —
#   비수는 도의 앞부분을 잘라 낸 것과 같은 모양이었다는 뜻이다. 둘을 가르는 길은 둘 중 하나다:
#   비수를 더 줄이거나, 도를 더 불리거나. **도를 불렸다** — 도는 원래 무겁고 두꺼운 병기고,
#   비수를 더 줄이면 아이콘이 점이 된다 (16px에는 줄일 여지가 없다).
# 배는 **등(D) 쪽으로** 불린다 — 인(H) 쪽으로 불리면 날이 자루 선 아래로 흘러내려 고리를 덮는다.
# 도가 무거워 보이는 것은 등이 두껍기 때문이지 날이 넓기 때문이 아니다 (한날 병기의 문법).
DAO_BLADE = [
    (8,  8, "DLH"),      # 밑동 (호수가 덮는다)
    (9,  6, "DD*LH"),
    (10, 5, "DD*LLH"),   # 배 — 가장 두껍다 (6가닥)
    (11, 4, "DD*LLH"),
    (12, 4, "D*LH"),
    (13, 3, "DLH"),      # 끝 좁힘
    (14, 2, "LH"),       # 칼끝
]


def dao_grid(rings, mabyeong):
    """도(刀) — 한날. 등이 두껍고 인 쪽 배가 부르며, 코등이는 뭉툭한 원반이다.

    ★ 그리는 순서를 뒤집었다 (날 → 자루). 자루를 먼저 긋고 날을 덮던 첫 판은 **등급 고리를 날이
      먹었다**: 슬롯 1의 고리 5칸 중 3칸이 날 밑동에 덮여, 범철→정련 계단이 7px 로 주저앉았다
      (다른 여덟 계열은 12px 이상). 표식끼리 자리를 다투면 이기는 쪽은 언제나 등급이다 —
      호수는 멋이고 고리는 정보다 (구鉤에서 이미 배운 교훈이었는데 도刀에서 되풀이했다).
      대신 날의 아랫배를 자루 선 위로 끌어올려(밑동 y≤10) 접합부가 벌어지지 않게 했다."""
    g = blank16()
    for x, y, strands in DAO_BLADE:
        band(g, x, y, 1, [("m" if mabyeong else "B") if c == "*" else c
                          for c in strands], vertical=True)
    _hilt(g, rings, mabyeong, 7, 9, (6, 8), (1, 2, 3), guard_steps=2)   # 원반 호수 — 뭉툭하게
    return g


def dagger_grid(rings, mabyeong):
    """비수(匕首) — 짧은 날. 자루가 날보다 길다 (비율이 곧 정체다).
    날을 한 걸음 더 줄였다 (4 → 3): 도가 배를 불린 만큼 비수는 더 짧아져야 둘이 갈린다."""
    g = blank16()
    band(g, 7, 7, 3, ["H", "L", "m" if mabyeong else "S"])    # 짧은 날 y=7..5
    band(g, 10, 4, 1, ["H"])                                  # 칼끝
    # 코등이는 짧다(3). 검과 같은 긴 가로대를 달면 '작은 검'이 되어 계열이 흐려진다.
    _hilt(g, rings, mabyeong, 6, 8, (5, 6), (1, 2, 3), guard_steps=3)
    return g


def spear_grid(rings, mabyeong):
    """창(槍) — **얇은** 긴 자루 + 좁은 창날 + 홍영(紅纓, 창날 밑 붉은 술).

    ★ 자루를 2가닥으로 깎았다. 3가닥이던 첫 판은 검과 실루엣 자카드 0.742 —
      둘 다 좌하에서 우상으로 뻗은 폭 3의 대각선 띠였고, 회색조에서 같은 물건이었다.
      창의 정체는 '길다'가 아니라 **자루가 날보다 훨씬 가늘다**이다. 그 비율만 지키면
      같은 대각선 위에 놓여도 눈이 갈라 본다 (자카드가 떨어지는 이유이기도 하다)."""
    g = blank16()
    band(g, 2, 13, 9, thin_grip, sx=1, sy=-1)                 # 얇은 긴 자루 (2,13)→(10,5)
    put_rings(g, 2, 13, rings, (2, 4, 6), sx=1, sy=-1, width=2)
    grade_butt(g, 2, 13, rings, width=2)                      # 자루 끝 — 등급이 자란다
    band(g, 10, 5, 1, ["T", "t", "t"])                        # 홍영 — 붉은 술
    band(g, 11, 4, 1, ["G", "g", "f"])                        # 물미(창날 목)
    band(g, 12, 3, 1, ["H", "L", "m" if mabyeong else "S"])   # 창날
    band(g, 13, 2, 1, ["H", "L"])
    band(g, 13, 1, 1, ["H"])                                  # 창끝
    if mabyeong:
        g[12][3] = "M"                                        # 혈적 — 자루에 밴 낙인 (테두리 금지)
    return g


# 권갑(拳甲) — 날이 없다. '손에 끼는 물건'으로 읽혀야 한다.
# 정면 판 하나로는 방패가 된다 (첫 판의 실패). 방패가 되지 않으려면 세 가지가 필요하다:
#   (1) 마디 — 윗변이 톱니여야 한다. 방패의 윗변은 매끈하다.
#   (2) 엄지 — 왼쪽으로 삐져나온 비대칭. 방패는 좌우 대칭이다.
#   (3) 손목 띠 + 늘어진 끈 — '몸에 매는 것'임을 아래로 말한다. 방패는 아래로 늘어지지 않는다.
# 그리고 판은 마디 쪽이 넓고 손목 쪽이 좁다 (사다리꼴) — 방패는 그렇게 좁아지지 않는다.
# 마디 4개 — (왼쪽 칸, 마루 높이). 높이가 들쭉날쭉해야 한다: 네 마디를 같은 높이로 나란히 세우면
# 성가퀴(battlement)가 되어 망루로 읽힌다 (둘째 판의 실패). 사람의 주먹은 가운뎃마디가 가장 높고
# 새끼 쪽으로 흘러내린다 — 그 아치가 '손'이라고 말한다.
GAUNT_KNUCKLES = ((3, 3), (6, 2), (9, 3), (12, 4))
GAUNT_PLATE = ((5, 3, 13), (6, 3, 13), (7, 4, 12))   # (y, x0, x1) — 마디 쪽이 넓은 사다리꼴
GAUNT_CUFF = (5, 11)                    # 손목 띠 좌우 — 판보다 좁다


def gauntlet_grid(rings, mabyeong):
    g = blank16()
    cx0, cx1 = GAUNT_CUFF

    # ─ 손가락 마디 — 아치를 이루는 네 마디 (사이 골은 비워 둔다: outline()이 먹으로 파 준다)
    for x, top in GAUNT_KNUCKLES:
        for y in range(top, 5):
            g[y][x] = "G" if y == top else "g"                # 마루는 빛을 받는다
            g[y][x + 1] = "G" if y == top else ("g" if y < 4 else "f")
    for x in (5, 8, 11):                                      # 마디 사이 골 — 네 개로 세어지게
        g[4][x] = "f"

    # ─ 손등 판 — 마디 쪽이 넓고 손목 쪽이 좁다
    for y, x0, x1 in GAUNT_PLATE:
        for x in range(x0, x1 + 1):
            g[y][x] = "G" if x == x0 else ("f" if x == x1 else "g")
    for x in (5, 8, 11):                                      # 못머리 — 빈 판은 방패가 된다
        g[6][x] = "G"
    if mabyeong:
        g[6][8] = "M"                                         # 가운데 못머리에 밴 혈적

    # ─ 엄지 — 왼쪽으로 크게 삐져나온 덩이 (대칭을 깬다). 작으면 실루엣에서 사라진다.
    g[5][2] = "G"
    g[6][1], g[6][2] = "G", "g"
    g[7][1], g[7][2], g[7][3] = "g", "g", "f"
    g[8][2], g[8][3], g[8][4] = "f", "f", "f"                 # 엄지 밑동 — 손목으로 흘러내린다

    # ─ 손목 — 놋 테 + 가죽 띠. 고리(등급)는 띠를 감는 금속 테 개수다.
    for x in range(cx0, cx1 + 1):
        g[8][x] = "G" if x == cx0 else ("f" if x == cx1 else "g")   # 놋 테
    for y in (9, 11, 13):                                     # 가죽 띠 — 고리 사이의 몸
        for x in range(cx0, cx1 + 1):
            g[y][x] = "W" if x == cx0 else ("X" if x == cx1 else "w")
    # 고리 3자리 — 한 줄 걸러 하나 (세어진다). 점등한 테는 띠보다 **좌우로 1px씩 넓다**:
    # 다른 여덟 계열의 돌출 고리와 같은 문법이다 — 표식은 실루엣을 바꿔야 등급이 읽힌다 (축 10).
    for n, y in ((1, 10), (2, 12), (3, 14)):
        for x in range(cx0 - 1, cx1 + 2):
            if rings >= n:
                g[y][x] = "R" if x < 9 else "e"               # 점등 = 2톤 금속 테 (오른쪽이 그늘)
            elif cx0 <= x <= cx1:
                g[y][x] = "x" if x < cx1 else "X"             # 미점등 = 가죽색 (구멍이 나지 않는다)
    for x in range(cx0, cx1 + 1):
        g[15][x] = "x"                                        # 띠 아랫단
    if rings >= 3:                                            # 신병 수실 — 띠 양옆에 늘어뜨린다
        for x in (cx0 - 2, cx1 + 2):                          # 고리 돌출(cx0-1..cx1+1) 바깥 자리
            g[13][x], g[14][x] = "T", "t"
    return g


# ═══ 장병기 4계열 (부·겸·월아산·구) — 곡선은 손으로 찍지 않는다 ═══════════════
# 검·도·창은 직선 띠(band)로 족했다. 그러나 도끼날·낫날·갈고리는 '휨'이 곧 정체다 —
# 계단식 띠로 곡선을 흉내내면 걸음마다 각이 져서 16px에서 '톱니 막대'가 된다.
# 그래서 곡선은 원(圓)의 대수로 굽는다: 원 안/밖 판정으로 칠하면 곡률이 저절로 매끈하다.
# 난수는 없다 — 같은 중심·반지름은 언제나 같은 픽셀을 준다 (결정론).
def _rad(x, y, c):
    """칸 중심에서 원 중심까지의 거리 (칸의 한가운데를 재야 곡선이 한쪽으로 밀리지 않는다)."""
    return math.hypot(x + 0.5 - c[0], y + 0.5 - c[1])


def crescent(grid, c1, r1, c2, r2, shades=("H", "L", "B", "S")):
    """초승달 — 원 A(c1, r1) 안이면서 원 B(c2, r2) 밖. 달을 깎는 것은 언제나 또 하나의 달이다.
    B가 A를 베어 문 자리가 오목한 안쪽(자루가 붙는 면), 남은 A의 테두리가 볼록한 인(刃).
    두 테두리에서 먼 안쪽일수록 어둡다 — 날은 가장자리가 얇고 가운데가 두껍기 때문."""
    for y in range(16):
        for x in range(16):
            d1, d2 = _rad(x, y, c1), _rad(x, y, c2)
            if d1 <= r1 and d2 >= r2:
                grid[y][x] = shades[min(int(min(r1 - d1, d2 - r2)), len(shades) - 1)]
    return grid


def arc_blade(grid, c, r, a0, a1, w0, w1, shades=("H", "L", "B", "S")):
    """휜 날 한 벌 — 중심 c 둘레를 반지름 r로 a0→a1(도, 반시계) 돌며 긋는다.
    폭은 밑동 w0에서 끝 w1로 좁아진다 (낫도 구도 밑동이 굵고 끝이 뾰족하다 — 등폭이면 '철사'가 된다).
    안쪽(오목한) 테가 가장 밝다: 낫과 갈고리는 바깥이 아니라 안으로 건다 — 안쪽이 인(刃)이다."""
    span = (a1 - a0) % 360
    for y in range(16):
        for x in range(16):
            d = _rad(x, y, c)
            ang = math.degrees(math.atan2(c[1] - (y + 0.5), x + 0.5 - c[0])) % 360
            t = ((ang - a0) % 360) / span
            if t > 1.0:
                continue                       # 호(弧) 바깥 — 여기가 갈고리의 '아가리'다
            half = (w0 + (w1 - w0) * t) / 2
            if abs(d - r) <= half:
                grid[y][x] = shades[min(int(d - (r - half)), len(shades) - 1)]
    return grid


def blit(grid, art, mabyeong=False):
    """각진 조각(도끼 쐐기·삽날)을 아트로 얹는다 — 원의 대수로는 각(角)이 나오지 않는다.
    마병이면 날의 몸(B)이 혈조(m)가 된다 — 다른 계열과 같은 문법."""
    for y, row in enumerate(art):
        for x, ch in enumerate(row):
            if ch != ".":
                grid[y][x] = "m" if (mabyeong and ch == "B") else ch
    return grid


def heavy_grip(i):
    """부(斧)의 자루 — 4가닥. 검·창의 3가닥보다 한 가닥 굵다.
    '무겁다'는 말은 무게로 못 하고 굵기로 한다 (16px에는 저울이 없다)."""
    return ["W", "w", "w", "x"] if i % 2 == 0 else ["w", "x", "x", "X"]


def bu_grid(rings, mabyeong):
    """부(斧) — 굵은 자루 + 한쪽에만 달린 넓은 초승달 날. 날이 화면의 절반을 먹는다.
    날을 먼저 깔고 자루를 그 위에 덧긋는다 — 자루가 날의 눈(구멍)을 꿰뚫고 지나간 것으로 읽힌다.
    (자루를 먼저 그으면 날이 자루에 '얹힌' 스티커가 된다 — 도끼는 그렇게 생기지 않았다.)"""
    g = blank16()
    blit(g, BU_HEAD, mabyeong)
    band(g, 2, 13, 9, heavy_grip, sx=1, sy=-1)        # 자루를 날 위에 덧긋는다 (눈을 꿰뚫는다)
    put_rings(g, 2, 13, rings, (1, 3, 5), sx=1, sy=-1, width=4)   # 굵은 자루 → 굵은 테
    grade_butt(g, 2, 13, rings, width=4)              # 자루 끝 — 물미(등급 2 이상은 벌어진다) + 신병 수실
    return g


def gyeom_grid(rings, mabyeong):
    """겸(鎌, 낫) — 짧은 자루 + 안으로 크게 감긴 넓은 날. 날이 아래로 열린 C를 그리고
    끝이 손 쪽으로 돌아온다 (걸어 채는 병기 — 끝이 바깥을 보면 그냥 칼이 된다).
    구(鉤)와의 갈림은 오직 비율이다: 여기는 자루가 짧고 날이 크고 두껍다. 구는 정확히 그 반대."""
    g = blank16()
    arc_blade(g, (7.4, 8.0), 4.4, 5, 205, 4.2, 1.6,   # 두꺼운 날 — '얇은 철사'는 구의 몫이다
              ("H", "L", "m" if mabyeong else "B", "S"))
    band(g, 5, 13, 6, wrap_grip, sx=1, sy=-1)         # 짧은 자루 — 날 밑동까지만
    put_rings(g, 5, 13, rings, (1, 2, 3), sx=1, sy=-1)
    grade_butt(g, 5, 13, rings)
    return g


def gu_grid(rings, mabyeong):
    """구(鉤, 갈고리) — 긴 곧은 자루 + 끝의 작은 발톱 + 미늘(逆鉤) + 손 앞의 초승달 호수.
    겸과 같은 '휜 것'이지만 비율이 뒤집혀 있다: 자루가 길고 발톱이 작다.
    그 비율만으로는 16px에서 겸과 갈리지 않아 두 표식을 더 박았다 —
    미늘(걸린 것이 빠지지 않게 하는 턱)과 호수(鉤의 손앞 초승달). 둘 다 겸에는 없다."""
    g = blank16()
    band(g, 2, 13, 9, wrap_grip, sx=1, sy=-1)
    band(g, 4, 8, 4, ["G", "g", "f"], sx=1, sy=1)        # 호수 — 자루를 가로지르는 초승달 코등이
    # 고리를 호수보다 뒤에 찍는다 — 호수가 먼저면 가운데 고리를 덮어 등급이 한 단 사라진다.
    # 표식끼리 자리를 다투면 이기는 쪽은 언제나 등급이다 (호수는 멋이고, 고리는 정보다).
    put_rings(g, 2, 13, rings, (1, 3, 5), sx=1, sy=-1)   # 발톱 밑동도 피한 자리
    arc_blade(g, (9.4, 5.0), 2.6, 300, 168, 2.2, 1.4,    # 작은 발톱 — 겸의 큰 날과 대비된다
              ("H", "L", "m" if mabyeong else "B", "S"))
    g[7][12], g[8][12], g[9][13] = "B", "L", "H"         # 미늘 — 자루 뒤로 뻗은 턱
    grade_butt(g, 2, 13, rings)
    return g


# 부(斧)의 날 — 원으로 깎으면 둥근 덩이(달·국자)가 된다. 도끼는 '쐐기'다:
# 왼쪽에 곧게 선 인(刃) + 위아래로 뻗은 두 뿔 + 자루 쪽으로 두꺼워지는 몸.
# 그 각(角)은 원의 대수로 나오지 않으므로 손으로 찍는다.
BU_HEAD = [
    "................",
    "................",
    "...HLLBBBB......",   # 곧은 윗변 — 둥근 지붕은 도끼가 아니라 종(鐘)이 된다
    "..HLLLLBBBBB....",
    "..HLLLLLLBBBB...",
    "..HLLLLLLLBBB...",
    "..HLLLLLLBBB....",
    "..HLLLLLBB......",
    "..HLLLLB........",
    "..HLLB..........",   # 수염(beard) — 자루 아래로 흘러내린 아래 뿔
    "..HLB...........",
    "................",
    "................",
    "................",
    "................",
    "................",
]

# 월아산의 삽날 — 원으로 굽기엔 너무 각지다 (삽은 곡선이 아니라 판이다). 아트로 얹는다.
# 넓고 납작한 판이어야 '삽'이다 — 작은 덩이는 그냥 자루 끝의 혹으로 읽힌다.
WOLASAN_SPADE = [
    "................", "................", "................", "................",
    "................", "................", "................", "................",
    "................", "................",
    "..SBBB..........",
    "..SBBLB.........",
    "..SBLLLB........",
    "..SLLLLB........",
    "................",
    "................",
]


def wolasan_grid(rings, mabyeong):
    """월아산(月牙鏟) — 승려의 병기. 긴 자루의 양끝이 서로 다르다:
    위는 월아(月牙, 두 뿔이 벌어진 초승달), 아래는 넓은 삽날. 이 '양끝'이 계열의 전부다 —
    다른 여덟 계열은 모두 한쪽 끝에만 쇠가 달렸다. 실루엣만으로 갈리는 유일한 축이다.
    월아는 두 뿔이 자루를 사이에 두고 벌어지도록 앉힌다 (오목한 안쪽이 자루 끝을 문다) —
    뿔이 하나로 뭉치면 갈고리가 되어 구(鉤)와 섞인다. 벌어져야 달이다."""
    g = blank16()
    blit(g, WOLASAN_SPADE, mabyeong)                  # 아래 끝 — 삽날 (자루보다 먼저: 자루가 목을 덮는다)
    band(g, 5, 11, 7, wrap_grip, sx=1, sy=-1)         # 긴 자루
    put_rings(g, 5, 11, rings, (1, 2, 4), sx=1, sy=-1)   # 월아에 먹히지 않는 자리
    # 월아 — 자루 끝에 얹힌 초승달. 앞을 보는 대칭 U도 시도했으나 12x12 안에서는
    # 두 뿔이 각각 1px로 뭉개져 '꺾쇠(⌐)'가 됐다 — 16px이 허락하지 않는 형태가 있다.
    # 그래서 달은 한쪽으로 눕히고, 구(鉤)와의 갈림은 반대쪽 끝의 삽날에 맡긴다:
    # 아홉 계열 중 쇠가 '양끝'에 달린 것은 이것뿐이다 — 그 비대칭이 곧 이름이다.
    crescent(g, (10.8, 5.2), 3.2, (8.2, 7.8), 3.4,
             ("H", "L", "m" if mabyeong else "B", "S"))
    # 등급 표식 — 월아산만 자루 끝이 비어 있지 않다 (거기 삽날이 달렸다). 그래서 물미는
    # '삽날 목의 놋 테'가 되고, 수실은 물미가 아니라 **삽날 아래**로 늘어진다 (석장의 문법).
    grade_butt(g, 5, 11, rings, tassel=False)
    if rings >= 3:
        for k, ch in enumerate(("T", "t", "t")):
            g[14][3 + k] = ch
    return g


WEAPON_SERIES = {          # 계열 = model_key 앞자리 (config item_channels.무기.series)
    "sword": sword_grid, "dao": dao_grid, "spear": spear_grid,
    "gauntlet": gauntlet_grid, "dagger": dagger_grid,
    # 18반 병기 — 바닐라 도구 4종을 병기화한 계열 (axe=부 / hoe=겸 / shovel=월아산 / pickaxe=구)
    "bu": bu_grid, "gyeom": gyeom_grid, "wolasan": wolasan_grid, "gu": gu_grid,
}
# 등급 = 베이스 바닐라 아이템(팩 게이트). 여기서는 고리 수만 쥔다 — 색은 바닐라 재질의 몫.
WEAPON_GRADES = [("beomcheol", 0), ("jeongryeon", 1), ("bobyeong", 2), ("sinbyeong", 3)]


def weapon_rows(series, rings, mabyeong=False):
    return paint_rows(outline(WEAPON_SERIES[series](rings, mabyeong)), WPN_PALETTE)


# ─── 지물·기물·재료 16x16 ───
# 재질마다 3~4단 계단을 준다 (몸 / 광 / 그늘). 외곽선은 손으로 찍지 않는다 — outline()이 두른다.
# 종이는 흰 사각형이 아니다: 위쪽 모서리가 빛을 받고, 겹친 아래쪽은 그늘에 잠기고, 먹은 종이에 스민다.
INK_OUT = (28, 25, 22, 255)       # 외곽 먹
INK_SOFT = (78, 70, 62, 255)      # 흐린 먹 — 글씨·인쇄 획 (외곽선보다 옅어야 '글씨'로 읽힌다)
PAPER_LIT = (248, 242, 226, 255)  # 화선지 — 빛 받는 윗면
PAPER = (224, 215, 194, 255)      # 화선지 몸
PAPER_SHADE = (188, 178, 154, 255)  # 접힘·아랫면 그늘
PAPER_DEEP = (150, 140, 118, 255)   # 겹친 종이 사이 깊은 그늘
SEAL_HI = (196, 88, 70, 255)      # 주사 인장 — 광
CORD = (88, 70, 50, 255)          # 끈
CORD_HI = (128, 104, 74, 255)
CORD_DIM = (54, 42, 30, 255)
BRASS = (152, 134, 94, 255)       # 놋
BRASS_LIT = (204, 184, 132, 255)
BRASS_SHADE = (98, 84, 56, 255)
HIDE = (150, 124, 94, 255)        # 가죽
HIDE_HI = (188, 160, 124, 255)
HIDE_DIM = (104, 84, 60, 255)
HIDE_DARK = (62, 48, 34, 255)     # 호피 줄무늬 — 그늘(HIDE_DIM)로는 줄무늬가 안 읽힌다 (대비 부족)
HERB = (112, 128, 86, 255)        # 약재 — 마른 풀
HERB_HI = (148, 164, 114, 255)
HERB_DIM = (74, 88, 56, 255)
GALL = (112, 110, 72, 255)        # 웅담 — 녹갈
GALL_HI = (148, 146, 100, 255)
GALL_DIM = (74, 72, 46, 255)
CRATE = (128, 102, 72, 255)       # 표물 궤 — 목재
CRATE_HI = (166, 136, 100, 255)
CRATE_DIM = (82, 64, 44, 255)

GOODS_PALETTE = {
    "#": INK_OUT, "i": INK_SOFT,
    "q": PAPER_LIT, "p": PAPER, "P": PAPER_SHADE, "d": PAPER_DEEP,
    "r": SEAL, "R": SEAL_HI,
    "h": CORD, "H": CORD_HI, "k": CORD_DIM,
    "g": BRASS, "G": BRASS_LIT, "f": BRASS_SHADE,
    "L": HIDE, "l": HIDE_HI, "n": HIDE_DIM, "N": HIDE_DARK,
    "e": HERB, "E": HERB_HI, "c": HERB_DIM,
    "a": GALL, "A": GALL_HI, "b": GALL_DIM,
    "w": CRATE, "W": CRATE_HI, "x": CRATE_DIM,
}

MANUAL_ORIGINAL_ART = [   # 비급 진본 — 선장본(線裝本): 실 꿰맨 등 + 제첨(題簽) + 주사 인장.
    "................",   # 진본과 필사본의 차이는 '인장' 하나 — 형태로 위조를 구별한다
    "................",
    "..qqqqqqqqqqqq..",
    "..qppppppppppP..",
    "..qkpqqqpppppP..",
    "..qppqiqpppppP..",
    "..qkpqqqpppppP..",
    "..qppqiqpppppP..",
    "..qkpqqqpppppP..",
    "..qppqiqpppppP..",
    "..qkpqqqppRrrP..",
    "..qppqqqpprrrP..",
    "..ppPPPPPPrrrd..",
    "..dddddddddddd..",
    "................",
    "................",
]
MANUAL_COPY_ART = [       # 비급 필사본 — 얇은 철(綴), 제첨도 인장도 없다 (베낀 것은 베낀 티가 난다)
    "................",
    "................",
    "................",
    "...qqqqqqqqqq...",
    "...qkpppppppP...",
    "...qppppppppP...",
    "...qkpppppppP...",
    "...qppppppppP...",
    "...qkpppppppP...",
    "...qppppppppP...",
    "...qkpppppppP...",
    "...qppppppppP...",
    "...pPPPPPPPPd...",
    "...dddddddddd...",
    "................",
    "................",
]
GUGYEOL_ART = [           # 심법 구결 — 접힌 낱장 + 세로쓰기 먹획 + 말린 오른아래 귀
    "................",
    "................",
    "................",
    "..qqqqqqqqqqqq..",
    "..qppppppppppP..",
    "..qpipipipippP..",
    "..qpipipipippP..",
    "..qpipipipippP..",
    "..qpipipipippP..",
    "..qpipipipippP..",
    "..qppppppppppP..",
    "..pPPPPPPPPqqd..",
    "..dddddddddq....",
    "................",
    "................",
    "................",
]
JEONPYO_ART = [           # 전표 — 가로 지폐. 먹 테두리 + 전장 인장(주사) + 액면 획 (액면은 lore)
    "................",
    "................",
    "................",
    "................",
    ".qqqqqqqqqqqqqq.",
    ".qpiiiiiiiiiipP.",
    ".qpiRrpiiiiiipP.",
    ".qpirrpiiiiiipP.",
    ".qpipppiiiiiipP.",
    ".qpippppppppppP.",
    ".qpiiiiiiiiiipP.",
    ".pPPPPPPPPPPPdd.",
    "................",
    "................",
    "................",
    "................",
]
PYOMUL_ART = [            # 표물 — 궤 + 봉인 끈 십자 + 표국 인패 (chest_minecart: 레일 없는 세계엔 설치 불가)
    "................",
    "................",
    "................",
    "..WWWWWWWWWWWW..",
    "..Wwwwwhhwwwwx..",
    "..Wwwwwhhwwwwx..",
    "..Wwwwwhhwwwwx..",
    "..HHHHHHHHHHHH..",
    "..hhhhhhhhhhhh..",
    "..wwwwwhhwwwwx..",
    "..wwwwGGGGwwwx..",
    "..wwwwgffgwwwx..",
    "..xxxxxxxxxxxx..",
    "................",
    "................",
    "................",
]
CHEONGNANG_ART = [        # 청낭 — 아가리 묶은 가죽 주머니 + 삐져나온 침 3개 (의술의 표식)
    "................",   # 침 간격은 3px — 그보다 좁으면 외곽선이 사이를 메워 통짜 검은 덩이가 된다
    "....G...G...G...",
    "....G...G...G...",
    "...HHHHHHHHHH...",
    "...hhhhhhhhhh...",
    "..lLLLLLLLLLLn..",
    ".lLLLLLLLLLLLLn.",
    ".lLLLLLLLLLLLLn.",
    ".lLLLLLLLLLLLLn.",
    ".lLLLLLLLLLLLLn.",
    ".nLLLLLLLLLLLnn.",
    "..nLLLLLLLLLLn..",
    "...nnnnnnnnnn...",
    "................",
    "................",
    "................",
]
HOSINBU_ART = [           # 호신부 — 세로 부적 + 주사 부문(符文) 3획 + 매단 끈
    "................",
    "......Hh........",
    "....qqqqqqqq....",
    "....qppppppP....",
    "....qprrrrpP....",
    "....qppppppP....",
    "....qprRRrpP....",
    "....qppppppP....",
    "....qprrrrpP....",
    "....qppppppP....",
    "....qprrrrpP....",
    "....qppppppP....",
    "....pPPPPPPd....",
    "....dddddddd....",
    "................",
    "................",
]
YODAE_ART = [             # 천잠사 요대 — 감아 둔 띠 3바퀴 + 놋 교구(鉸具)
    "................",
    "................",
    "................",
    "...HHHHHHHHHH...",
    "..hhhhhhhhhhhh..",
    "..kkkkkkkkkkkk..",
    "..HHHHHHHHHHHH..",
    "..hhhhhhhhhhhh..",
    "..kkkkkkkkkkkk..",
    "..HHHHGGGGHHHH..",
    "..hhhhGffGhhhh..",
    "..kkkkGGGGkkkk..",
    "..kkkkkkkkkkkk..",
    "................",
    "................",
    "................",
]
# 가죽 3종은 오직 윤곽으로 구별된다 (색은 셋 다 같은 무두질 가죽이다).
# 실패 사례 둘을 피한다: 몸통보다 다리가 더 벌어지면 모래시계로 보이고,
# 줄무늬를 행마다 끊으면 격자(와플)로 보인다. 몸통은 꽉 찬 판, 다리는 그 밖으로 삐져나온 토막,
# 줄무늬는 끊기지 않는 세로 획 — 그래야 '펼쳐 못 박은 짐승 가죽'으로 읽힌다.
PELT_WOLF_ART = [         # 늑대 가죽 — 펼쳐 못 박은 네 다리 ('o' = 놋 못머리)
    "................",
    "................",
    "...oL......Lo...",
    "...lL......Ln...",
    "..llllllllllln..",
    "..lLLLLLLLLLLn..",
    "..lLLLLLLLLLLn..",
    "..lLLLLLLLLLLn..",
    "..lLLLLLLLLLLn..",
    "..lLLLLLLLLLLn..",
    "..nnnnnnnnnnnn..",
    "...nL......Ln...",
    "...oL......Lo...",
    "................",
    "................",
    "................",
]
PELT_FOX_ART = [          # 여우 가죽 — 같은 못질 윤곽이되 확연히 작다 + 붉은 털 한 점 (시세 3배의 표식)
    "................",
    "................",
    "....oL...Lo.....",
    "....lL...Ln.....",
    "...lllllllln....",
    "...lLLLLLLLn....",
    "...lLLLLLLLn....",
    "...lLrLLLLLn....",
    "...lLLLLLLLn....",
    "...nnnnnnnnn....",
    "....nL...Ln.....",
    "....oL...Lo.....",
    "................",
    "................",
    "................",
    "................",
]
PELT_TIGER_ART = [        # 호랑이 가죽 — 늑대와 같은 못질 윤곽 + 끊기지 않는 세로 줄무늬.
    "................",   # 줄무늬를 행마다 끊으면 격자(와플)가 되고, 윤곽이 네모나면 널빤지가 된다 —
    "................",   # 다리를 달아야 비로소 '가죽'으로 읽힌다 (150배 값은 색이 아니라 이름이 판다)
    "...oL......Lo...",
    "...lL......Ln...",
    "..llllllllllln..",
    "..lLNLLNNLLNLn..",
    "..lLNLLNNLLNLn..",
    "..lLNLLNNLLNLn..",
    "..lLNLLNNLLNLn..",
    "..lLNLLNNLLNLn..",
    "..nnnnnnnnnnnn..",
    "...nL......Ln...",
    "...oL......Lo...",
    "................",
    "................",
    "................",
]

# ─── 가죽 — 아트는 실루엣만 잡고, 명암과 털결은 절차로 굽는다 ───────────────
# 4~5색 평면 채우기로는 '무두질한 짐승 가죽'이 아니라 '갈색 널빤지'가 된다. 셋이 필요하다:
#   (1) 위에서 든 빛 — 어깨가 밝고 아랫배가 그늘에 잠긴다 (세로 그라데이션)
#   (2) 털결 — 결정론 노이즈 한 겹. 가죽은 매끈한 플라스틱이 아니다
#   (3) 놋 못머리 — 네 다리 끝에 박은 못. '펼쳐서 못 박았다'를 형태로 말한다 (차가운 금속 = 대비)
PELT_SHADES = ramp((54, 42, 30, 255), (200, 174, 138, 255), 7)     # 무두질 가죽 7단
PELT_STRIPE_SHADES = ramp((30, 24, 18, 255), (88, 70, 50, 255), 3)  # 호피 줄무늬 — 털이니 결이 있다
PELT_BASE = {"l": 5.0, "L": 3.6, "n": 2.0}


def pelt_rows(art):
    g = outline([list(r) for r in art], "#")
    body = [(x, y) for y in range(16) for x in range(16) if g[y][x] not in (".", "#")]
    ytop = min(y for _, y in body)
    span = max(1, max(y for _, y in body) - ytop)
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            ch = g[y][x]
            if ch == ".":
                row.append((0, 0, 0, 0))
            elif ch == "#":
                row.append(INK_OUT)
            elif ch == "o":                      # 놋 못머리 — 위에 박은 못이 빛을 더 받는다
                row.append(BRASS_LIT if y < 8 else BRASS)
            elif ch == "r":                      # 여우 — 붉은 털 한 점
                row.append(SEAL)
            elif ch == "N":                      # 호피 줄무늬
                row.append(step(PELT_STRIPE_SHADES, 1 + octave(x, y, 1, 0x7A, 0.9)))
            else:
                v = PELT_BASE.get(ch, 3.0) + (1 - (y - ytop) / span) * 1.1 - 0.5
                row.append(step(PELT_SHADES, v + octave(x, y, 1, 0x5C, 0.75)))
        rows.append(row)
    return rows
UNGDAM_ART = [            # 웅담 — 쓸개 주머니 + 매단 끈 (녹갈 3단 — 물컹한 것도 빛은 받는다)
    "................",
    ".......h........",
    ".......h........",
    "......AAA.......",
    ".....Aaaab......",
    "....Aaaaaaab....",
    "...Aaaaaaaaab...",
    "...Aaaaaaaaab...",
    "...Aaaaaaaaab...",
    "...Aaaaaaaaab...",
    "...baaaaaaabb...",
    "....baaaaab.....",
    ".....bbbbb......",
    "................",
    "................",
    "................",
]
YAKJAE_ART = [            # 약재 — 묶은 약초 다발 + 끈 2줄 (널어 둔 약초와 동형)
    "................",   # 줄기 사이는 투명이 아니라 짙은 풀색 — 투명이면 외곽선이 사이를 메운다
    "...EcEcEcEcE....",
    "...EcEcEcEcE....",
    "...EEEEEEEEE....",
    "....eeeeeee.....",
    "....HHHHHHH.....",
    "....hhhhhhh.....",
    "....eeeeeee.....",
    "....eeeeeee.....",
    "....HHHHHHH.....",
    "....hhhhhhh.....",
    "....ccccccc.....",
    ".....ccccc......",
    ".....c.c.c......",
    "................",
    "................",
]

# ─── 구슬 3종 (단약·피독주·야명주) — 램버트 명암으로 굽는다 ───
# 평평한 원에 광점 하나 찍는 것과 이건 다른 그림이다. 구는 빛이 감기는 방향으로 밝기가 '휜다':
# 좌상단 정반사 → 몸 → 우하 그늘 → 그리고 우하 가장자리에 반사광(rim)이 한 줄 돈다.
# 그 반사광이 있어야 구슬이 배경에서 떨어져 나와 '떠 있는 구'로 보인다.
ORB_STEPS = 7


def orb_shades(dark, light, spec):
    """구슬 계단 — 마지막 칸만 정반사(spec)로 따로 뗀다.
    본체 계단을 그냥 밝은 값까지 늘리면 구 전체가 허옇게 뜬다 (실측: 야명주가 흰 덩어리가 됐다).
    정반사는 '한 점'이라야 광택이지, 넓게 퍼지면 그냥 밝은 색일 뿐이다."""
    s = ramp(dark, light, ORB_STEPS)
    s[-1] = spec
    return s


PILL_SHADES = orb_shades((58, 18, 14, 255), (198, 88, 68, 255), (244, 178, 158, 255))      # 단약 — 주사 계열
JADE_SHADES = orb_shades((32, 58, 54, 255), (142, 188, 176, 255), (212, 238, 230, 255))    # 피독주 — 청록
GLOW_SHADES = orb_shades((112, 104, 78, 255), (238, 232, 208, 255), (255, 255, 248, 255))  # 야명주 — 유일하게 밝은 값 허용


def orb_grid():
    """구슬 명암 — 문자 '0'(가장 어두움) ~ '5'(몸통 최명), '6'(정반사). 팔레트만 갈면 다른 구슬이 된다.
    정반사를 계단 맨 윗칸으로 두면 안 된다: 밝은 반구 전체가 그 칸에 몰려 구슬이 흰 덩어리가 된다.
    정반사는 '빛을 정면으로 마주 본 법선'(lam > 0.9)에만 따로 준다 — 그래야 광택 한 점이 된다."""
    g = blank16()
    cx, cy, r = 7.6, 8.4, 5.9
    lx, ly, lz = -0.52, -0.58, 0.63          # 좌상단·앞에서 오는 빛
    body = ORB_STEPS - 2                     # 몸통 계단 상한 (0~5) — 6은 정반사 전용
    for y in range(16):
        for x in range(16):
            nx, ny = (x + 0.5 - cx) / r, (y + 0.5 - cy) / r
            q = nx * nx + ny * ny
            if q > 1.0:
                continue
            nz = (1 - q) ** 0.5
            lam = nx * lx + ny * ly + nz * lz                    # 램버트 항 (-1..1)
            rim = max(0.0, -(nx * lx + ny * ly)) * (1 - nz) ** 2  # 우하 반사광 — 구슬을 배경에서 떼어낸다
            v = 2.4 + lam * 3.4 + rim * 1.5
            idx = ORB_STEPS - 1 if lam > 0.90 else max(0, min(body, int(round(v))))
            g[y][x] = str(idx)
    return g


def orb_palette(shades, extra=None):
    pal = {str(i): shades[i] for i in range(ORB_STEPS)}
    pal["#"] = INK_OUT
    if extra:
        pal.update(extra)
    return pal


PIDOKJU_CRACKS = [(6, 5), (7, 6), (6, 7), (7, 8), (8, 9), (10, 6), (11, 7)]   # 독을 머금은 흠


def goods_rows(key):
    """지물 1종 → RGBA 행. 구슬 3종만 절차적, 나머지는 아트 + 자동 외곽선."""
    if key == "pill/yeongyak":
        return paint_rows(outline(orb_grid(), "#"), orb_palette(PILL_SHADES))
    if key == "trinket/yamyeongju":
        return paint_rows(outline(orb_grid(), "#"), orb_palette(GLOW_SHADES))
    if key == "trinket/pidokju":
        g = orb_grid()
        for x, y in PIDOKJU_CRACKS:
            if g[y][x] != ".":
                g[y][x] = "v"                # 균열 — 구슬 표면을 가르는 어두운 실금
        return paint_rows(outline(g, "#"), orb_palette(JADE_SHADES, {"v": (30, 54, 50, 255)}))
    if key.startswith("pelt/"):
        return pelt_rows(GOODS_ART[key])         # 가죽은 절차 — 털결과 명암을 굽는다
    return paint_rows(outline([list(r) for r in GOODS_ART[key]], "#"), GOODS_PALETTE)


# 지물/기물/재료 등록표 — key = model_key 경로 (config item_channels 등록분 그대로. 발명 0건)
GOODS_ART = {
    "tome/manual_original": MANUAL_ORIGINAL_ART,
    "tome/manual_copy": MANUAL_COPY_ART,
    "tome/gugyeol": GUGYEOL_ART,
    "coin/jeonpyo": JEONPYO_ART,
    "pill/yeongyak": None,                     # 절차적 (orb_grid)
    "cargo/pyomul": PYOMUL_ART,
    "trinket/pidokju": None,                   # 절차적
    "trinket/yamyeongju": None,                # 절차적
    "trinket/cheongnang": CHEONGNANG_ART,      # status: 보류 — 텍스처만 준비, 지급은 접합 후
    "trinket/hosinbu": HOSINBU_ART,            # status: 보류
    "trinket/yodae": YODAE_ART,                # status: 보류
    "pelt/wolf": PELT_WOLF_ART,
    "pelt/fox": PELT_FOX_ART,
    "pelt/tiger": PELT_TIGER_ART,
    "spoil/ungdam": UNGDAM_ART,
    "herb/yakjae": YAKJAE_ART,
}


def write_item_asset(key: str, rows, handheld: bool):
    """아이템 1종 = PNG + 모델 JSON + 아이템 정의 JSON 3장 동시 산출.
    아이템 정의(assets/honcheon/items/<key>.json)가 곧 item_model 컴포넌트의 값이다:
      minecraft:item_model = "honcheon:<key>"  →  이 파일이 읽힌다."""
    write_png(ITEM_TEX_DIR / f"{key}.png", rows)
    model = {
        "parent": "minecraft:item/handheld" if handheld else "minecraft:item/generated",
        "textures": {"layer0": f"honcheon:item/{key}"},
    }
    write_json(ITEM_MODEL_DIR / f"{key}.json", model)
    write_json(ITEM_DEF_DIR / f"{key}.json",
               {"model": {"type": "minecraft:model", "model": f"honcheon:item/{key}"}})


def weapon_grid(series, rings, mabyeong=False):
    """아이콘 격자 한 장 — PNG 와 3D 모델이 **같은 격자**를 쓴다.
    (모델의 UV 가 이 격자에서 부위별 대표 픽셀을 문다 — 아이콘과 3D 가 같은 쇠로 보이는 이유다.)"""
    return outline(WEAPON_SERIES[series](rings, mabyeong))


def write_weapon_asset(series: str, grade: str):
    """병기 한 자루 = 아이콘 PNG + **3D 모델** + 아이템 정의.

    모델은 더 이상 평면 스프라이트(handheld)가 아니다 — 칼날·자루·코등이·물미·고리가
    저마다 부피를 가진 elements 다 (weapon_model_3d). 텍스처는 **그 아이콘 PNG 그대로**이므로
    검수 축 ⑥(외곽선)·⑨(계열 실루엣)·⑩(등급 변별)이 재던 진실은 하나도 흔들리지 않는다."""
    rings, _, _, mab = _GRADE_FORM[grade]
    grid = weapon_grid(series, rings, mab)
    key = f"weapon/{series}_{grade}"
    write_png(ITEM_TEX_DIR / f"{key}.png", paint_rows(grid, WPN_PALETTE))
    write_json(ITEM_MODEL_DIR / f"{key}.json", weapon_model_3d(series, grade, grid))
    write_json(ITEM_DEF_DIR / f"{key}.json",
               {"model": {"type": "minecraft:model", "model": f"honcheon:item/{key}"}})


def write_item_assets() -> int:
    """병기 45(계열 9 × 등급 5) + 지물·기물·재료 16 = 61종.

    ★ 마병 8자루가 새로 구워졌다. Weapons.java 는 **계열 9 × 등급 5 = 45개의 item_model 키를
      조건 없이 박는다** (Series.modelId 가 전부 non-null 이므로). 그런데 팩에는 dao_mabyeong
      하나뿐이었다 — 즉 팩을 받은 눈에 나머지 마병 8자루는 **'없는 모델'(보라·검정 큐브)** 로 떴다.
      팩 게이트는 "키를 안 붙이거나, 붙였으면 반드시 굽거나" 둘 중 하나다. 여기서 굽는다."""
    made = 0
    for series in WEAPON_SERIES:
        for grade in _GRADE_FORM:                 # 범철 · 정련 · 보병 · 신병 · 마병
            write_weapon_asset(series, grade)
            made += 1
        # 마병은 **상태 변주**다 (custom_model_data.strings — 정수 CMD 는 쓰지 않는다).
        # 미감정(기본)은 평범한 정련 병기로 보인다 — 기연의 탈을 쓴 저주가 첫눈에 보이면 저주가 아니다.
        write_json(ITEM_DEF_DIR / "weapon" / f"{series}_mabyeong.json", {
            "model": {
                "type": "minecraft:select",
                "property": "minecraft:custom_model_data",
                "index": 0,
                "cases": [{
                    "when": "revealed",
                    "model": {"type": "minecraft:model",
                              "model": f"honcheon:item/weapon/{series}_mabyeong"},
                }],
                "fallback": {"type": "minecraft:model",
                             "model": f"honcheon:item/weapon/{series}_jeongryeon"},
            },
        })

    for key in GOODS_ART:
        write_item_asset(key, goods_rows(key), False)
        made += 1
    return made


# ═══════════════════════════════════════════════════════════════════════════
# 블록 채널 — 전역 치환 (징발). config block_channels.징발 등록분만.
# 금지 원칙: 자연이 만든 블록은 건드리지 않는다 (stone·dirt·*_log·stone_bricks…) — 팩에 1장도 없다.
# ═══════════════════════════════════════════════════════════════════════════
# ─── 흑와(黑瓦) — deepslate_tiles PNG 1장이 계단·반블록·담장 전부를 덮는다. 마을의 인상. ───
# ★ 방향 전환 (계단 목업 실측) — **기와를 정밀 묘사하지 않는다.**
#   지난 판은 수키와/암키와 단면(주기 8) × 단 이음(주기 8)을 둘 다 또렷이 새겼다. 한 장만 8배로
#   확대해 보면 그럴듯했지만, 계단에 얹어 수십 블록 이어 붙이자 두 리듬의 교차점마다 밝은 점이
#   맺혀 **카본 파이버·뽁뽁이·골함석**이 되었다 (roof_mock.png 육안 확인). 원인은 셋:
#     ① 고주파 — 블록마다 마루가 둘, 단이 둘. 화면에선 그냥 촘촘한 노이즈 격자다
#     ② 고대비 — 마루 하이라이트↔골 바닥 명암차가 100+ 라 그 격자가 도드라진다
#     ③ 반복   — 모든 블록이 같은 장이라 격자가 모아레를 만든다
#   지붕은 '한 장'이 아니라 '층계에 반복된 면'으로 보인다. 그러니 한 장의 사실성이 아니라
#   **면의 인상**을 그려야 한다 — 동양풍 팩(Conquest 계열)의 문법이 정확히 이것이다.
#
# 새 원칙 — 멀리서 **매끈한 짙은 면**, 가까이서 **은은한 결**. 이 순서다.
#   저주파: 한 블록에 큰 형상 둘(부드러운 세로 너울). 뾰족한 마루·골이 아니라 완만한 물결이다
#   저대비: 미세 패턴의 명암 폭을 20~35 안에 가둔다 (지난 판은 100+)
#   선 하나: 단 이음은 **한 줄만**, 아주 약하게(≈12). 두 줄 이상이면 그 순간 격자가 된다
#   얼룩: ±6 — 죽은 평면만 겨우 면하게. 구조를 덮지 않는다
#   색: 짙은 회흑(중성). 지난 판 평균 (75,73,70)은 '검은 기와'가 아니라 중간 회색이었다 —
#       평균을 50대로 낮춰 **검게** 앉히고, 대신 아주 약한 색 온도 편차로 죽은 회색을 피한다
ROOF_SHADES = ramp((28, 28, 29, 255), (78, 77, 76, 255), 14)   # 14단 — 짙은 회흑. 한 단 ≈ 3.8
ROOF_MID = 6.6          # 계단 중앙값 — 너울·얼룩이 여기서 위아래로 흔들린다 (평균은 회흑 54)
# 세로 너울 (x % 8) — '마루'가 아니라 **완만한 결**이다. 하이라이트도 골도 없다: 한 주기에 걸쳐
#   부드럽게 밝아졌다 어두워질 뿐. 블록당 둘 — 지시된 '2~3개'의 하한이다.
#   지난 판의 ROOF_CURVE는 폭이 7단(≈70)이었고 1px 만에 3.6→0.8로 꺾였다. 그 급락이 리벳이었다.
# ★ 진폭은 두 번 깎았다 (목업 실측). 폭 4.1단(≈16)일 때도 계단에 얹으니 여전히 **골덴(코듀로이)**
#   세로 줄무늬로 읽혔다 — 어두운 면에서는 같은 명암차라도 상대적으로 훨씬 세게 보이기 때문이다.
#   폭 2.5단(≈9.5)까지 낮추자 비로소 '무늬'가 아니라 '결'이 됐다: 멀리선 매끈한 면, 가까이서 결.
# 표는 한 주기를 도는 **매끈한 물결**이다 (끝값 0.2 → 첫값 1.2, 낙차 1.0 — 내부 최대 낙차 1.1과
#   같은 급). 어디에도 1px 절벽이 없다는 뜻이고, 절벽이 곧 리벳이었다.
#   (주기 8은 16의 약수라 좌우 랩 경계는 x=7→8 경계와 같은 위상이다 — x축은 애초에 이음매
#    위험이 없다. 이음매를 만든 건 세로 표가 아니라 **가로 단 이음의 위치**였다. 아래 참조.)
# ★ 3차 감쇠 (인게임 실측 — "한 방향으로 되어 이질감") — 계수 0.85배. 폭 2.1단(≈8.1).
#   결은 남되 한 겹 더 눕혔다. 완전히 죽이지는 않는다: 결이 0이면 기와가 아니라 그냥 돌이고,
#   그러면 아래 ②(회전 변형)도 돌릴 결이 없어 무의미해진다. 방향성은 **없애는 게 아니라
#   면마다 옳게 돌려주는 것**이 답이다 — ROOF_ISOTROPY 참조.
ROOF_SWELL = [1.02, 0.935, 0.425, -0.255, -0.85, -1.105, -0.765, 0.17]

# ═══ 방향성 감쇠 (ROOF_ISOTROPY) — "지붕이 한 방향으로 보인다"의 정체와 처방 ═══
# 지붕은 네 방향 경사면(우진각/팔작)인데 블록 텍스처는 한 장이고 방향이 고정이다. 그래서
# 남북 경사면에서는 가로 단 이음선이 경사를 가로지르지만(옳다), 동서 경사면에서는 같은 선이
# 경사와 나란히 누워 **기와가 옆으로 누운 것처럼** 보인다. 처방은 둘이고, 둘 다 쓴다:
#   ① 기본 텍스처의 방향성을 줄인다 — 약할수록 어느 면에 붙어도 덜 어색하다.
#      · 단 이음선: **점선으로 끊고**(핵심) 깊이도 -3.0 → -2.4단으로 낮춘다.
#        점선이 핵심인 이유: 이어진 선은 '자로 그은 방향'이지만 끊긴 선은 '기와 낱장의 끝'이다.
#        그리고 점선은 **명암을 잃지 않으면서 방향만 줄인다** — 켜진 칸은 여전히 제 깊이까지
#        파이므로 명암차(린트 하한 24)는 유지되고, 행평균 프로파일(=결집 방향 에너지)만
#        듀티비(8/16)만큼 줄어든다. 깎기와 끊기 중 **끊기가 공짜에 가깝다**는 뜻이다.
#      · 세로 너울: 계수 0.85배
#      · 얼룩을 조금 올려 잃은 질감을 벌충 (단, 아래 '실패한 갈래' 참조 — 많이 올리면 안 된다)
#      · 실측 (전 → 후): 가로선(단 이음선. **동서 경사면에서 눕는 그 선이다**) 2.78 → 1.18 (**-58%**)
#            결집 방향 에너지 총량 4.48 → 3.62 (-19%) · 세로결 3.51 → 3.42
#            90도 회전 평균절대차 5.33 → 4.43 (-17%) · 노이즈 걷어낸 값(3x3 블러 후) 3.87 → 3.61
#            명암차 26 → 26 (유지) · 이음매 0.70 → 0.73 (유지)
#        ※ 경계 에너지 이방비는 1.01 → 1.55로 올랐다. 숨기지 않는다: 기준선의 1.01은 '등방'이
#          아니라 **세로 결과 가로 선의 세기가 우연히 맞먹던 것**(= 격자)이었다. 그 격자가 바로
#          지난 판이 '골함석'이라 부르며 없앤 것이다. 가로 선만 걷어내면 남은 세로 결이 홀로
#          도드라져 비율이 커진다 — 비율이 아니라 **총량**(4.48 → 3.62)이 옳은 지표다.
#
#      ※ 실패한 갈래 (기록 — 같은 삽질 금지). "선을 절반 이하로 깎고 그 명암을 노이즈로 벌충한다"를
#        곧이곧대로 해봤다. 결과는 **둘 다 나빴다**:
#          · 선을 깎으면 명암차가 26 → 16으로 무너진다 (린트 '밋밋'). **선이 명암을 벌고 있었다.**
#          · 그 몫을 노이즈로 메우려 진폭을 올리면, 모든 블록이 같은 16x16 한 장이므로 그 노이즈가
#            블록마다 똑같이 되풀이돼 **카본 파이버(고주파) 또는 누비이불(저주파) 격자**가 뜬다.
#            지난 판이 이미 겪고 고친 실패 모드다 (roof_mock.png 육안 확인 — 수치는 다 통과했다).
#        교훈: 노이즈는 명암의 대체재가 아니다. 방향은 **끊어서** 줄이고, 명암은 선에 맡겨 둔다.
#
#      · 정직한 한계: 결을 완전히 죽이지 않는 한 등방성은 '가까워질' 뿐 도달하지 않는다.
#        그리고 결을 죽이면 그건 기와가 아니라 그냥 돌이다. 그래서 ②가 진짜 처방이다.
#   ② 직각 면용 회전 변형 블록 (**핵심 처방**) — deepslate_bricks = 이 텍스처의 90도 회전판.
#      조성기가 동서 경사면에 DEEPSLATE_BRICK_* 를 쓰면 각 면이 제 방향의 결을 갖는다.
#      ①만으로는 결을 죽여야 하고(그러면 기와가 아니라 그냥 돌이다), ②만으로는 남은 방향성이
#      다른 데서 새어 나온다(담·용마루는 회전 변형이 없다). 둘을 같이 써야 결을 지키면서 눕지 않는다.
#
# 점선 마스크 (x=0..15, 1=선 있음). 켜진 칸 8 / 16 — 듀티 1/2이라 결집 방향 에너지가 정확히 반이 된다.
#   끊는 자리를 불규칙하게 둔 이유: 규칙적으로 끊으면 그건 점선이 아니라 **더 작은 격자**다.
#   마지막 칸이 0이라 좌우 랩에서도 선이 끊긴 채로 이어진다 (긴 선의 재발 방지).
ROOF_COURSE_DASH = "1110010110100100"
# 단(段) 이음 — 한 장에 한 줄만(주기 16), 이제 **점선**으로. 깊이 -2.4단 ≈ 명암차 9.
# 줄은 y=8(장 한가운데)에 둔다. y=0에 두면 그 줄이 곧 상하 랩 경계가 되어, 텍스처에서
#   유일하게 강한 가로 경계가 하필 이음매 자리에 앉는다 (실측: 이음매 4.65 — 린트가 옳다).
ROOF_COURSE = {8: -2.4, 9: +0.55, 10: +0.17}


def roof_course(x, y):
    """단 이음 — 점선. 이어진 한 줄이 아니라 끊긴 자국들이다 (방향감 감쇠)."""
    d = ROOF_COURSE.get(y)
    if d is None or ROOF_COURSE_DASH[x] == "0":
        return 0.0
    return d


def roof_stain(x, y):
    """오래된 기와의 얼룩 — 저주파 큰 얼룩 + 중주파 + 아주 옅은 픽셀 결. 합 진폭 ±2.1단 ≈ 명암 ±8.

    ★ 진폭은 **일부러 낮게 묶어 둔다.** 노이즈로 질감을 벌려는 유혹이 크지만, 모든 블록이 같은
      16x16 한 장이므로 노이즈도 블록마다 똑같이 되풀이된다 — 진폭을 올리는 순간 그것은 '질감'이
      아니라 **16px 주기로 반복되는 격자**가 된다 (고주파면 카본 파이버, 저주파면 누비이불).
      실측으로 두 번 확인했다. 얼룩은 죽은 평면을 겨우 면할 만큼만이 옳다 (ROOF_ISOTROPY '실패한 갈래').
    ★ 소금 0x11 → 0x61: 셀 8은 16px 안에 격자점이 2x2뿐이라 **소금 운에 따라 장이 평평해진다**
      (0x11은 ±1 중 0.91폭만 실현 — 진폭을 키워도 명암이 안 붙던 이유. 0x61은 1.67폭).
    셀 크기 8·4·2·1 은 모두 16의 약수라 랩에서 끊기지 않는다."""
    return (smooth_octave(x, y, 8, 0x61, 1.25)      # 큰 얼룩 — 그을음·이끼·비 자국
            + smooth_octave(x, y, 4, 0x23, 0.70)    # 중간 자락
            + smooth_octave(x, y, 2, 0x5F, 0.28)    # 잔 얼룩 — 결의 밀도
            + octave(x, y, 1, 0x37, 0.30))          # 픽셀 결 — 구운 흙의 입자


def roof_tint(x, y, color):
    """색 온도 미세 편차 — 죽은 회색을 피하는 법.
    저주파 한 겹으로 R과 B를 반대로 ±2 흔든다: 어떤 자락은 아주 살짝 따뜻하고(그을음·이끼)
    어떤 자락은 아주 살짝 차갑다(비에 씻긴 자리). 명도는 건드리지 않으므로 대비가 늘지 않고,
    R↔B 대칭이라 평균 색조는 중성으로 남는다 (린트: 기와의 푸른 기 B-R ≤ 8)."""
    t = smooth_octave(x, y, 8, 0x4D, 1.0)          # -1(차갑다) ~ +1(따뜻하다)
    d = round(2.0 * t)
    r, g, b, a = color
    return (max(0, min(255, r + d)), g, max(0, min(255, b - d)), a)


def roof_rows(cracked=False):
    """흑와 한 장. cracked=True 면 같은 지붕 위에 균열만 얹는다 (딴 그림이 아니라 상한 판)."""
    crack = dict(ROOF_CRACKS) if cracked else {}
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            v = ROOF_MID + ROOF_SWELL[x % 8] + roof_course(x, y) + roof_stain(x, y)
            # 균열은 '칠하는 색'이 아니라 '표면에 주는 변형'이다 — 절대색으로 덮으면
            # 아래 기와의 명암이 지워져 얼룩(S자 반점)으로 뜬다 (지지난 판의 실패 모드).
            # 델타로 얹으면 제 밝기 위에서 파이므로 어디서든 '금'으로 읽힌다.
            v += crack.get((x, y), 0.0)
            row.append(roof_tint(x, y, step(ROOF_SHADES, v)))
        rows.append(row)
    return rows


# 깨진 기와(폐사당 잔해) — 같은 지붕의 '상한 판'이다. 딴 그림이 아니다.
# 저대비 기조에 맞춰 균열도 **가늘고 얕게**: 지난 판은 델타 -4.2단(≈ -44)이라 균열이 아니라
#   검은 흠집 덩어리였고, 그 자체가 또 하나의 고주파 무늬가 됐다. 이제 -2.2단(≈ -8) —
#   멀리선 안 보이고 가까이서야 '아, 갈라졌구나' 하는 깊이. 금은 **길고 가늘게 셋**만 긋는다
#   (잡다한 실금 열보다 깨끗한 선 하나가 낫다 — 이 팩의 원칙).
# 값 = 명암 계단 델타. 음수 = 갈라진 틈 / 양수 = 파단면(빛을 되쏘는 갓 깨진 모) — 틈의 왼쪽에
#   붙어야 '깨진 두께'가 생긴다 (빛은 좌상단).
# 금은 텍스처 **가장자리에 닿지 않게** 둔다 (x·y 모두 2~13). 가장자리에 닿으면 이웃 장의 금과
#   이어 붙어 지붕을 가로지르는 긴 선이 되고, 랩 경계가 이 장에서 가장 강한 경계가 된다 (랩 사고).
ROOF_CRACKS = {
    # 금 1 — 좌상에서 비스듬히 내려오는 긴 균열 (한 줄로 이어진다. 끊긴 점들이 아니다)
    (3, 2): -2.2, (2, 2): +1.0,
    (3, 3): -2.2, (4, 4): -2.4, (3, 4): +1.0,
    (4, 5): -2.4, (4, 6): -2.2, (5, 7): -2.2, (4, 7): +0.9, (5, 8): -2.0,
    # 금 2 — 단 이음(y=8) 아래에서 갈라져 우하로 흐르는 균열
    (11, 9): -2.2, (10, 9): +1.0,
    (11, 10): -2.4, (12, 11): -2.4, (11, 11): +0.9,
    (12, 12): -2.2, (12, 13): -2.0,
    # 금 3 — 위쪽 짧은 실금 (지붕 전체가 균열투성이면 지붕이 아니라 폐허 무늬가 된다)
    (9, 2): -2.2, (8, 2): +0.9, (9, 3): -2.2, (10, 4): -2.0, (10, 5): -1.8,
}


def rotate90(rows):
    """시계 방향 90도 회전 — 결이 직각으로 눕는다 (ROOF_ISOTROPY ②).

    deepslate_bricks = deepslate_tiles의 회전판이다. 딴 그림이 아니라 **같은 기와를 직각으로
    놓은 것**이다: 같은 팔레트·같은 얼룩 통계·같은 이음매 성적(회전은 축을 바꿀 뿐 랩 경계의
    강도를 바꾸지 않는다). 그래서 두 블록이 한 지붕에 섞여도 이질감이 없고, 각 경사면은
    제 방향의 결을 갖는다.
    dst[y][x] = src[15-x][y] — 회전이 픽셀 대응이므로 대조 검증이 딱 떨어진다(불일치 0)."""
    n = len(rows)
    return [[rows[n - 1 - x][y] for x in range(n)] for y in range(n)]


# ─── 회벽(灰壁) — 거친 회칠. 무늬·이음선 금지(block_channels 조건): 배들랜드 지층에
#     대량 자연 생성되므로 무늬가 있으면 지층이 벽으로 튄다.
#     '균열'을 직선으로 길게 그으면 그 선이 16px마다 되풀이돼 벽 전체에 사선 격자가 뜬다 (실측 확인).
#     그래서 실금은 짧게(3~4px), 옅게(1단 미만), 텍스처 가장자리에 닿지 않게 둔다 —
#     가장자리에 닿으면 이웃 장의 실금과 이어져 벽을 가로지르는 긴 선이 된다 (랩 사고).
#     저주파는 보간 노이즈(smooth_octave)라야 한다. 계단형이면 4px 네모 얼룩이 그대로 보인다.
# 색: **차가운 회백색**. 이전 판은 R-B 가 +21이라 사막 흙벽(누런 베이지)으로 보였다 (육안 확인).
#     R≈G≈B (max-min ≤ 4) 로 채도를 사실상 0에 두고, 명도 폭도 좁혀 얼룩이 2~3톤만 읽히게 한다.
PLASTER_HAIRLINES = [(4, 5), (5, 6), (6, 6), (6, 7),        # 실금 1 — 짧은 사선
                     (11, 10), (11, 11), (12, 12), (12, 13)]  # 실금 2


def plaster_rows(dark, light, hairlines=True):
    """회벽 — 6단 저대비 계단. 명도 폭이 좁아 바닐라 배들랜드/석재 옆에서 튀지 않는다."""
    shades = ramp(dark, light, 6)
    fine = set(PLASTER_HAIRLINES) if hairlines else set()
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            v = (2.6
                 + smooth_octave(x, y, 8, 0x5B, 0.80)   # 큰 얼룩 — 미장의 물결
                 + smooth_octave(x, y, 4, 0x6D, 0.58)   # 흙손 자국
                 + octave(x, y, 2, 0x7F, 0.42)          # 거친 결
                 + octave(x, y, 1, 0x91, 0.45))         # 모래 입자
            if h32(x, y, 0xA7) % 23 == 0:
                v -= 1.4                                # 회칠이 패인 곰보 자국 (선이 아니라 점 — 격자가 안 생긴다)
            if (x, y) in fine:
                v -= 0.9                                # 실금 — 1단 미만. 눈에 겨우 걸릴 만큼만
            row.append(step(shades, v))
        rows.append(row)
    return rows


# ─── 격자창 — glass.png(면). glass_pane와 유리 블록이 면 텍스처를 공유하므로
#     유리도 격자창이 된다: 전근대 강호에 판유리는 없다 — 오염이 아니라 정합.
#     창살은 목재(4단 명암), 창호(窓戶)는 창호지 — 따뜻한 미색 종이.
#     이전 판은 살대가 2px에 3x3 칸이라 창살이 굵고 뭉툭해 '문짝'으로 보였다 (육안 확인).
#     → 살대를 **1px**로 깎고 세로살 위주(細箭窓)로 잘게 나눈다: 세로살 4대(x=3·6·9·12) +
#       중간 가로살 1대(y=7). 세로 우세 = 조선 세살창의 문법이고, 칸이 잘아 창으로 읽힌다.
#     알파: 바닐라 glass 는 cutout 렌더(알파 이진 판정)라 부분 알파가 사실상 불투명으로 보인다 —
#       창호지는 원래 들여다보이지 않는 종이다. **불투명(alpha 255)으로 확정**하고 그에 맞게 그린다
#       (반투명인 척하는 알파는 렌더에서 어차피 뭉개져 톤만 어지럽힌다).
WOOD_HI = (146, 118, 84, 255)     # 창살 — 빛 받는 위/왼쪽 모
WOOD_MID = (108, 86, 60, 255)     # 창살 몸 — 세로살
WOOD_DIM = (78, 62, 42, 255)      # 창살 그늘 — 가로살(세로살보다 뒤에 있다)
WOOD_OUT = (46, 36, 26, 255)      # 창틀 외곽 — 가장 어두운 목재
WIN_PAPER_HI = (242, 234, 210, 255)   # 창호지 — 살에 닿는 밝은 결 (따뜻한 미색. 순백 금지)
WIN_PAPER_MID = (230, 221, 196, 255)  # 창호지 몸
WIN_PAPER_DIM = (208, 198, 172, 255)  # 창호지 그늘 (살 그림자가 지는 아래·오른쪽)

WIN_VBARS = (3, 6, 9, 12)   # 세로살 1px — 세살창 (세로 우세는 유지)
# ★ 가로살을 하나에서 **둘**로 늘렸다 (4, 10). 하나뿐이던 첫 판은 세로 이동 자기상관이 0.902 —
#   창이 사실상 '세로 창살만 있는 격자(바코드)'였고, 창문 벽 한 면을 채우면 칸이 안 보였다.
#   간격도 일부러 어긋나게 둔다 (4·6·5): 등간격이면 그 간격이 곧 새로운 주기가 된다.
WIN_HBARS = (4, 10)


def lattice_window_rows():
    """세살창 — 1px 세로살 4대 + 중간 가로살 1대 + 창호지.
    1px 살대는 제 몸에 명암을 담을 수 없다 — 그래서 입체는 **창호지 쪽에서** 만든다:
    살 바로 오른쪽·아래 칸에 그림자를 앉히면 살이 종이 위로 떠오른다 (빛은 좌상단).

    ★ 2차 — 자기 복제 r(0,8) = 0.902 (축 7). 종이 칸이 전부 같은 톤이라 이 텍스처는 세로로
      **평행 이동해도 자기 자신**이었다: 창을 벽 한 면에 이어 붙이면 세로 줄무늬(바코드)만 남고
      '칸'이 사라진다. 창호지는 칸마다 따로 바른다 — 풀 먹인 날도, 볕에 삭은 정도도 다르다.
      그래서 **칸(pane)마다 톤을 달리** 하고, 오래된 칸에는 얼룩(누런 물때)을 앉힌다.
      이것이 세로 이동 대칭을 깨서 벽이 격자로 읽히게 한다 — 창은 원래 격자다."""
    # 칸 톤 — (세로 칸 0~4) × (가로 칸 0~1). 값은 결정론 상수: 같은 창은 언제나 같다.
    pane_tone = [[0.00, -0.30, 0.35], [-0.55, 0.22, -0.15], [0.30, -0.42, 0.50],
                 [-0.18, 0.45, -0.35], [0.40, -0.12, 0.18]]

    def pane_of(x, y):
        cx = sum(1 for b in WIN_VBARS if x > b)       # 몇 번째 세로 칸인가
        cy = sum(1 for b in WIN_HBARS if y > b)       # 위 칸인가 아래 칸인가
        return pane_tone[cx][cy]

    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            if x in (0, 15) or y in (0, 15):
                # 창틀 — 좌·상은 한 단 밝게, 우·하는 최암 (테두리도 입체다)
                row.append(WOOD_HI if (x == 0 or y == 0) else WOOD_OUT)
            elif x in WIN_VBARS:
                row.append(WOOD_MID)                  # 세로살 — 앞에 있다 (밝은 목재)
            elif y in WIN_HBARS:
                row.append(WOOD_DIM)                  # 가로살 — 세로살 뒤 (그늘진 목재)
            else:
                # 창호지 — 살에 인접한 위/왼쪽은 빛, 아래/오른쪽엔 살 그림자가 앉는다
                lit = (x - 1) in WIN_VBARS or (y - 1) in WIN_HBARS or x == 1 or y == 1
                dim = (x + 1) in WIN_VBARS or (y + 1) in WIN_HBARS or x == 14 or y == 14
                base = WIN_PAPER_DIM if dim else WIN_PAPER_HI if lit else WIN_PAPER_MID
                t = pane_of(x, y) + smooth_octave(x, y, 4, 0x3B, 0.35)   # 칸 톤 + 물때 번짐
                base = mix(base, WIN_PAPER_DIM if t < 0 else WIN_PAPER_HI, min(abs(t), 1.0) * 0.75)
                # 종이 섬유 — 1px 결정론 결 (닥섬유가 비쳐 보이는 결). 불투명 유지
                row.append(base if h32(x, y, 0x9C) % 3 else mix(base, WIN_PAPER_HI, 0.35))
        rows.append(row)
    return rows


def pane_top_rows():
    """glass_pane_top.png — 창살 마구리(끝면). 통짜 목재 + 결 3단
    (모델이 어느 UV를 잡아도 '깎아 놓은 나무 단면'으로 읽힌다)."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            if y in (0, 15) or x in (0, 15):
                row.append(WOOD_OUT)
            else:
                v = 1 + octave(x, y, 2, 0xA5, 0.9) + (0.8 if y < 8 else -0.5)
                row.append(step([WOOD_OUT, WOOD_DIM, WOOD_MID, WOOD_HI], v))
        rows.append(row)
    return rows


# ─── 죽렴(竹簾) — bamboo_planks. 대나무 쪽을 나란히 엮은 발.
#     쪽 폭 4px (16의 약수) → 좌우 랩 매끄러움.
# ★ 흑와와 같은 병(病)이었다 (계단 목업 실측) — 벽으로 이어 붙이자 **타탄·바구니 격자**로 보였다.
#   세로 쪽(주기 4)과 가로 마디(주기 16)가 둘 다 고대비라, 교차점마다 눈이 격자를 읽는다.
#   명암 폭이 96 — 대나무 발이 아니라 모눈종이였다. 흑와에 쓴 처방을 그대로 적용한다:
#     ① 계단을 잘게 (7단 → 12단): 한 단이 16 → 5.8. 같은 형상을 훨씬 곱게 새길 수 있다
#     ② 쪽 단면 진폭 반토막 (폭 4.6단≈74 → 2.7단≈16): 쪽이 '골'이 아니라 '결'이 된다
#     ③ 마디도 눌러서 (-2.4 → -1.4 ≈ 명암차 8): 가로선이 세로 쪽과 교차해도 격자가 안 뜬다
#   쪽 폭 4px 은 지킨다 — 잘게 엮인 살이 죽렴의 정체성이고, 대비만 낮추면 격자가 아니라 발이 된다.
# 색: **마른 대나무**의 연한 황갈색. R > G > B 로 갈색 쪽에 눕힌다 (초록기가 남으면 옥수수가 된다).
#     채도도 한 번 더 낮춘다 (light 의 max-min 50 → 34) — 골판지 같던 노랑을 빼고 바랜 대나무로.
# ★ 2차 — 자기 복제 축(축 7)이 잡아낸 마지막 잔재. 대비를 낮춰 격자는 지웠지만, 네 쪽이
#   **여전히 서로의 복사본**이었다: 자기상관 r(4,0) = 0.886. 벽에 이어 붙이면 블록보다 잘은
#   주기 4의 세로 줄무늬가 화면 전체를 가로지른다 — 사용자가 두 번 지적한 '한 방향 반복'의
#   같은 병이 대나무에서 살아 있었다는 뜻이다. 대비를 낮추는 것으로는 못 고친다 (연한 줄무늬도
#   줄무늬다). 고칠 것은 **복제 자체**다:
#     ① 쪽마다 밝기가 다르다 — 대는 한 그루에서 잘라도 쪽마다 볕을 달리 먹었다
#     ② 쪽마다 마디 높이가 다르다 — 마디가 한 줄로 가지런한 발은 없다 (그게 골판지다)
#   쪽 폭 4px(=16의 약수)은 지킨다: 좌우 랩의 위상이 어긋나면 이음매가 터진다.
BAMBOO_SHADES = ramp((134, 123, 102, 255), (204, 191, 167, 255), 12)   # 12단 — 한 단 ≈ 5.8
# 쪽 단면 (x % 4) — 볼록한 대쪽. 좌측이 빛을 받고 우측이 그늘, 쪽과 쪽 사이가 골이다.
BAMBOO_CURVE = [1.3, 0.5, -0.4, -1.4]
# 쪽 4개의 밝기 — 넷이 다 다르다. 값은 이음매 축과 함께 풀어 고른 것이다: 톤을 아무렇게나
# 흩으면 랩 경계(쪽3→쪽0)가 내부 쪽 경계들보다 큰 이상치가 되어 이음매가 터진다 (1.35 실측).
# 지금 값은 복제 0.765 / 이음매 0.80 — 두 축이 동시에 통과하는 자리다.
BAMBOO_STRIP_TONE = [0.0, -0.9, -0.3, 0.3]
BAMBOO_STRIP_NODES = [(3, 12), (7,), (1, 10), (5, 14)]   # 쪽마다 마디 높이가 다르다


def bamboo_rows():
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            s = x // 4                                  # 쪽 번호 0~3 — 네 쪽이 서로 다른 대나무다
            v = 6.2 + BAMBOO_STRIP_TONE[s] + BAMBOO_CURVE[x % 4]
            v += octave(x, y, 2, 0xC1, 0.42) + octave(x, y, 1, 0xD3, 0.26)   # 대나무 결
            nodes = BAMBOO_STRIP_NODES[s]
            if y in nodes:
                v -= 1.4                                # 마디 홈 — 가로 그늘 (약하게)
            elif (y - 1) % 16 in nodes:
                v += 0.7                                # 마디 아래 융기 — 빛 받는 턱
            row.append(step(BAMBOO_SHADES, v))
        rows.append(row)
    return rows

# ─── 등롱(燈籠) — 종이에 스민 불빛. 심지에서 멀어질수록 어두워지는 방사 그라데이션이
#     '안에서 타는 불'을 만든다 (평면 채움으로는 절대 안 나오는 그림).
#     lantern.png는 모델이 여러 UV 조각으로 잡으므로 어느 조각을 잡아도 종이 등으로 읽히게 균질 구성.
LANTERN_SHADES = ramp((92, 62, 28, 255), (255, 246, 214, 255), 8)      # 등롱 — 유등 난색
SOUL_SHADES = ramp((46, 62, 76, 255), (238, 250, 255, 255), 8)         # 백등롱 — 폐사당 냉광
LANTERN_CAP = (48, 38, 28, 255)      # 위아래 쇠테
LANTERN_CAP_HI = (86, 70, 50, 255)   # 쇠테 상단 광
SOUL_CAP = (34, 42, 50, 255)
SOUL_CAP_HI = (66, 80, 92, 255)


def lantern_rows(shades, cap, cap_hi):
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            if y in (0, 1, 14, 15):                       # 쇠테 — 위테 윗줄만 광
                row.append(cap_hi if y == 0 else cap)
                continue
            if x in (0, 15):
                row.append(cap)                           # 세로 모서리 살
                continue
            # 심지 방사광 — 중심(7.5, 8.5)에서의 거리로 감쇠. 심지는 살짝 아래에 있다.
            d = ((x - 7.5) ** 2 * 0.9 + (y - 8.5) ** 2) ** 0.5
            v = 7.4 - d * 0.72
            if x % 5 == 0:
                v -= 2.2                                  # 세로 살대 — 불빛을 가로막는 그림자
            v += octave(x, y, 2, 0xF3, 0.35)              # 종이 결 얼룩
            row.append(step(shades, v))
        rows.append(row)
    return rows


# ─── 한약장 — chiseled_bookshelf 재해석 ('꽂힌 책 = 채운 서랍').
#     6칸(3열 × 2단)은 바닐라 슬롯 배치 계약. occupied = 앞판 + 놋 손잡이 / empty = 열린 칸의 어둠.
SHELF_WOOD = ramp((70, 56, 41, 255), (190, 160, 120, 255), 7)
SHELF_VOID = (18, 16, 14, 255)      # 빈 칸 깊은 어둠
SHELF_VOID_HI = (44, 38, 32, 255)   # 빈 칸 안쪽 바닥 — 위에서 든 빛이 겨우 닿는 곳
BRASS_HI = (204, 184, 132, 255)     # 놋 손잡이 광
BRASS_DIM = (104, 90, 60, 255)      # 놋 손잡이 그늘
SHELF_COLS = [(0, 4), (5, 10), (11, 15)]
SHELF_ROWS = [(0, 7), (8, 15)]


def shelf_grain(x, y, vertical, base=3.6, salt=0x2D):
    """목재 결 — 결 방향으로 길게 늘인 노이즈 (결은 한 방향으로 흐른다).
    salt = 칸마다 다른 씨앗. 서랍 여섯이 같은 결을 쓰면 그 여섯은 한 판을 여섯 번 찍은 것이다."""
    gx, gy = (x, y // 4) if vertical else (x // 4, y)
    return base + octave(gx, gy, 1, salt, 1.5) + octave(x, y, 1, salt ^ 0x6C, 0.4)


# ★ 2차 — 자기 복제 r(0,8) = 0.984 (축 7). 위아래 두 단이 **완전한 복사본**이었다 (0.98은
#   거의 1이다: 8칸 밀면 자기 자신이 된다). 약장이 여섯 서랍이 아니라 '한 서랍의 도장 여섯 번'으로
#   보였다는 뜻이다. 서랍마다 결의 씨앗과 밝기, 손잡이 길이를 달리해 복제를 끊는다 —
#   목수가 나무 여섯 장을 같은 무늬로 켤 수는 없다.
SHELF_CELL_SALT = (0x2D, 0x53, 0x71, 0x97, 0xB3, 0xC9)
SHELF_CELL_TONE = (0.0, -0.45, 0.35, 0.5, -0.25, 0.15)


def shelf_face_rows(occupied):
    grid = [[SHELF_WOOD[2]] * 16 for _ in range(16)]      # 문선(칸 사이 기둥)
    for y in range(16):
        for x in range(16):
            grid[y][x] = step(SHELF_WOOD, shelf_grain(x, y, True, 2.6))
    for ci, (x0, x1) in enumerate(SHELF_COLS):
        for ri, (y0, y1) in enumerate(SHELF_ROWS):
            k = ri * 3 + ci                               # 서랍 번호 0~5
            salt, tone = SHELF_CELL_SALT[k], SHELF_CELL_TONE[k]
            for y in range(y0, y1 + 1):
                for x in range(x0, x1 + 1):
                    if x == x0 or y == y0:
                        grid[y][x] = SHELF_WOOD[0]        # 칸 상·좌 — 인셋 그림자
                    elif x == x1 or y == y1:
                        grid[y][x] = SHELF_WOOD[5]        # 칸 하·우 — 빛 받는 턱
                    elif occupied:
                        grid[y][x] = step(SHELF_WOOD,
                                          shelf_grain(x, y, False, 4.2 + tone, salt))
                    elif y == y1 - 1:
                        # 서랍 바닥판 — 열린 칸으로 빛이 들어 바닥이 환히 드러난다.
                        # 이 한 줄이 '빈 칸'에 깊이를 준다 (어둠만 칠하면 서랍이 아니라 검은 구멍이다).
                        # 판정상으로도 이 줄이 없으면 empty의 강한 가로 경계가 하나뿐이라
                        # 이음매 기준선(내부 경계 90퍼센타일)이 무너져 랩이 이상치로 몰린다 (1.31의 정체).
                        grid[y][x] = step(SHELF_WOOD,
                                          shelf_grain(x, y, False, 5.0 + tone, salt))
                    else:
                        # 빈 서랍 속 뒷판 — 열린 칸 위로 든 빛이 뒷판 윗쪽을 스치고,
                        # 뒷판과 바닥이 만나는 아래 구석은 빛이 닿지 않아 가장 깊이 잠긴다.
                        # 그 구석의 어둠이 바로 밑 바닥판의 밝음과 부딪쳐 깊이를 만든다
                        # ('무(無)'가 아니다 — 어둠에 잠긴 뒷판의 결이 어스름히 비친다).
                        # 칸마다 든 빛의 양이 다르다 (tone) — 여섯 서랍이 같은 어둠일 리 없다
                        t = (y - y0) / max(1, (y1 - y0))
                        base = mix(SHELF_VOID_HI, SHELF_VOID, t)
                        n = shelf_grain(x, y, False, 0.0, salt) * 5.0 + tone * 6.0
                        grid[y][x] = tuple(max(0, min(255, round(c + n)))
                                           for c in base[:3]) + (255,)
            if occupied:                                   # 가로 놋 손잡이 (2px — 광 + 그늘)
                # 손잡이 높이·길이도 칸마다 다르다 — 여섯을 한 자리에 못 박으면 그것이 복제다
                my = (y0 + y1) // 2 + (1 if k % 3 == 1 else 0)
                for x in range(x0 + 2, x1 - 1 - (1 if k % 2 else 0)):
                    grid[my][x] = BRASS_HI
                    grid[my + 1][x] = BRASS_DIM
    return grid


def shelf_grain_rows(vertical):
    """약장 몸통(top·side) — 결만 있는 목재. 무늬 금지 대상이 아니라 자유롭다."""
    return [[step(SHELF_WOOD, shelf_grain(x, y, vertical)) for x in range(16)] for y in range(16)]


# ─── 족자(簇子) — painting 소형 4종. 세계의 모든 액자가 강호가 된다 ───
# 4~5색 선화로는 화선지가 되지 않는다 (그건 종이에 그은 '금'이지 먹이 아니다). 수묵의 조건 넷:
#   (1) 농담(濃淡) — 엷은 먹·중간 먹·진한 먹이 한 폭에 같이 있어야 '붓이 지나갔다'로 읽힌다
#   (2) 번짐 — 진한 획 둘레로 엷은 먹이 한 겹 스민다. 화선지는 먹을 빨아들인다 (자동 헤일로)
#   (3) 여백(餘白) — 화선지는 비어 있어야 산다. 그래서 그림 자체는 단순해도 좋다
#   (4) 인장 — 주사 한 점. 화폭에서 유일한 채색이라 눈이 거기 앉아 쉰다
SCROLL_PAPER = (222, 214, 196, 255)       # 화선지 몸
SCROLL_PAPER_LIT = (238, 232, 216, 255)   # 화선지 결 — 빛을 되쏘는 섬유
SCROLL_INK = (30, 28, 26, 255)            # 진한 먹 (농담의 끝)
SCROLL_ROD = ramp((56, 45, 33, 255), (146, 118, 86, 255), 5)   # 축(軸) — 나무 봉
SCROLL_SEAL = (156, 58, 46, 255)          # 주사(朱砂) 인장
SCROLL_SEAL_HI = (194, 86, 68, 255)
# 획의 농도 — '.' 여백 / '-' 엷은 먹 / '+' 중간 / '*' 진한 / '#' 가장 진한 먹
SCROLL_DENSITY = {".": 0.0, "-": 0.20, "+": 0.46, "*": 0.72, "#": 1.0}
SCROLL_BLEED = 0.16                       # 번짐 — 진한 획이 종이에 스미는 한 겹

SCROLL_MOTIFS = {          # 12행 × 16열 — 위아래 축(軸) 2행씩을 더해 16x16
    "kebab": [             # 산수(山水) — 먼 봉우리는 엷고 주봉은 진하다. 아래는 안개와 물, 그리고 여백
        "................",
        ".........#......",
        "........#*#.....",
        "....#...#*##....",
        "...#*#..#**##...",
        "..#**##.#***##..",
        ".#*****#*****##.",
        "++++++++++++++..",     # 산자락이 안개에 잠긴다 (엷은 먹 한 겹)
        "................",     # 여백
        "..----...-----..",     # 물결 — 마른 붓 두 획
        ".rr.............",     # 주사 인장
        ".rr.............",
    ],
    "aztec": [             # 죽(竹) — 곧은 대 두 그루 + 마디 + 잎. 왼쪽은 통째로 비운다
        "......#....#....",
        "...*..#....#..*.",
        "....**#....#**..",
        "......#....#....",
        "......##..##....",     # 마디(節)
        "......#....#....",
        ".....*#*..*#*...",
        "....**#....#**..",
        "......#....#....",
        "......##..##....",
        ".rr...#....#....",
        ".rr...#....#....",
    ],
    "alban": [             # 서(書) — 획만 남은 글씨. 붓은 눌러 시작해 들어 올리며 마른다(枯筆)
        "................",
        "....########....",
        "......#*#.......",
        "......#*#.......",
        ".....#*#*#......",
        "....#*#.#*#.....",
        "...#*#...#*#....",
        "..#*#.....#*#...",
        ".#*#.......#*#..",
        "*#..........#*..",     # 갈필 — 획 끝이 마르며 흩어진다
        ".rr.............",
        ".rr.............",
    ],
    "wasteland": [         # 난(蘭) — 길게 휘는 잎 두 획 + 꽃 한 송이. 난은 잎이 그림이다
        "..#.............",
        "...#.........#..",
        "...#........#...",
        "....#......#....",
        "....#*....#*....",
        ".....#....#.....",
        ".....#*..#*.....",
        "......#..#......",
        "......#*#*......",
        "...**.#*#..**...",
        ".rr.*+.#.+*.....",
        ".rr....#........",
    ],
}


def scroll_rows(motif):
    """수묵 족자 — 먹은 종이 위에 얹히지 않고 '스민다'."""
    art = SCROLL_MOTIFS[motif]
    dens = [[SCROLL_DENSITY.get(c, 0.0) for c in row] for row in art]

    # 번짐 — 중간 먹 이상의 획 둘레로 엷은 먹이 한 겹 배어 나온다 (헤일로)
    bled = [row[:] for row in dens]
    for y in range(12):
        for x in range(16):
            if dens[y][x] >= SCROLL_DENSITY["+"]:
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < 16 and 0 <= ny < 12:
                        bled[ny][nx] = max(bled[ny][nx], SCROLL_BLEED)

    def rod(y, lit):       # 축(軸) — 둥근 봉이라 위가 밝고 아래가 그늘이다
        return [step(SCROLL_ROD, lit + octave(x, y, 2, 0x11, 0.6)) for x in range(16)]

    rows = [rod(0, 3.4), rod(1, 1.6)]                      # 위 축
    for y in range(12):
        row = []
        for x in range(16):
            if art[y][x] == "r":                           # 주사 인장 — 전각의 각(刻)이 보이게 2톤
                row.append(SCROLL_SEAL_HI if (x + y) % 2 == 0 else SCROLL_SEAL)
                continue
            paper = mix(SCROLL_PAPER, SCROLL_PAPER_LIT,
                        0.5 + smooth_octave(x, y, 4, 0x33, 0.5))   # 화선지 결
            row.append(mix(paper, SCROLL_INK, bled[y][x]))
        rows.append(row)
    return rows + [rod(14, 3.0), rod(15, 1.2)]             # 아래 축


# ═══════════════════════════════════════════════════════════════════════════
# 자재층(自材層) — 세계를 이루는 면들 (2026-07 확장)
#
# ── 왜 여기까지 왔는가 (객관 수치) ──
# 조성기(CheonghaBuilder 청하현 · RemoteBuilder 산채·문파)가 쓰는 블록은 179종인데 팩이 덮던
# 블록 텍스처는 15장, 그중 조성 팔레트와 겹치는 것은 **8종**뿐이었다. 즉 **지붕(흑와)과 회벽 말고는
# 세계가 전부 바닐라**였다 — 무협의 마을이 아니라 마인크래프트 마을 위에 기와만 얹은 꼴.
#
# ── 우선순위의 원칙: 눈에 보이는 **면적** ──
# 항아리 하나보다 흙길 한 장이 세계를 더 바꾼다. 그래서 순서는 길·바닥 → 벽·돌 → 목재(판자·기둥) →
# 초가·천 → 기물(통·솥·시렁)이다. 사용 빈도(조성기 코드 실측)와 화면 점유가 같은 방향을 가리킨다:
#   LANTERN 45 · SPRUCE_FENCE 39 · BARREL 39 · DARK_OAK_PLANKS 33 · COARSE_DIRT 25 · DIRT_PATH 24 …
#   ※ 울타리(SPRUCE_FENCE 39·OAK_FENCE 15)는 제 텍스처가 없다 — **판자 텍스처를 쓴다.**
#     판자 한 장이 판자·울타리·계단·반블록·문·다락을 한꺼번에 덮는다 (징발의 가성비가 여기 있다).
#
# ── 미학 규약 (불가침) ──
# 수묵(水墨). 채색은 **차양(붉은 천)·매화·등롱·깃발**에만. 나머지는 먹의 농담이다.
# 목재는 '갈색'이 아니라 **먹에 아주 옅은 흙기(土氣)를 섞은 값** (채도 ≤ 40). 돌은 무채색.
#
# ── 자재의 뜻이 보여야 한다 (building_style_guide 와 정합) ──
#   흙길·다진 흙 = 사람이 다닌 자리 (밟혀 다져진 면 + 발자국·긁힌 자국)
#   목책·통나무  = 도적의 집 (거칠게 쪼갠 결·터진 수피)
#   회벽        = 관청·문파 (매끈하되 얼룩)
#   기와        = 검다 (푸른 기 금지)
#   초가        = 성글게 이은 짚
#   다듬은 안산암 = 계단·단 (사람이 깎은 돌 — 자연 돌과 달라야 한다: 정 자국이 있다)
#
# ── 반복(反復)의 병 — 이 팩이 두 번 앓은 병이고, 여기서 다시 앓으면 세 번째다 ──
# 블록 텍스처는 **한 장이 벽 한 면을 도배한다.** 그래서 텍스처가 제 안에서 스스로를 복사하면
# (자기상관 r > 0.85) 벽에 블록보다 잘은 격자가 뜬다 — 골함석·타탄·모아레.
# 처방은 하나다: **단위마다 다르게 그린다.**
#   · 판자 4장은 폭도 톤도 결의 씨앗도 다르다 (목수가 같은 널 넉 장을 켤 수는 없다)
#   · 전돌의 켜는 높이가 다르다 (5·3·4·4) — 등간격 4는 곧 주기 4의 격자다
#   · 막돌·자갈은 보로노이 세포로 나눈다 — 세포는 크기·자리·톤이 다 다르고 격자를 만들지 않는다
#   · 긴 직선은 **끊는다** (점선 마스크). 이어진 선은 이웃 장의 선과 만나 벽을 가로지른다
# 모든 새 자재는 굽기 전 자기 복제 상관을 재서 0.85 아래를 확인했다 (tools/texture_audit.py 축 7).
# ═══════════════════════════════════════════════════════════════════════════

def wrapped_cells(x, y, cell, salt):
    """랩 안전 보로노이 — 막돌·자갈·전돌의 뼈대. 반환: (최근접 거리, 차근접 거리, 세포 id, 세포 중심).

    격자 노이즈(octave)는 '네모 얼룩'을 낳지만 보로노이 세포는 **돌 하나하나**가 된다:
    크기도 자리도 다 다르므로 그 자체로 주기가 없다 (자기 복제의 구조적 예방).
    랩: 세포 id 는 16//cell 로 모듈러 — 왼쪽 밖 세포는 오른쪽 끝 세포와 **같은 돌**이라
    좌우로 이어 붙여도 돌이 반으로 갈리지 않는다."""
    n = 16 // cell
    d1, d2 = 99.0, 99.0
    ident, center = (0, 0), (0.0, 0.0)
    for j in (-1, 0, 1):
        for i in (-1, 0, 1):
            gx, gy = x // cell + i, y // cell + j
            ci, cj = gx % n, gy % n
            fx = (h32(ci, cj, salt) % 1000) / 1000.0
            fy = (h32(cj, ci, salt ^ 0x5A) % 1000) / 1000.0
            px, py = (gx + 0.18 + fx * 0.64) * cell, (gy + 0.18 + fy * 0.64) * cell
            d = ((x + 0.5 - px) ** 2 + (y + 0.5 - py) ** 2) ** 0.5
            if d < d1:
                d1, d2, ident, center = d, d1, (ci, cj), (px, py)
            elif d < d2:
                d2 = d
    return d1, d2, ident, center


def cell_rand(ident, salt):
    """세포 고유값 -1~+1 — 돌마다 다른 톤·거칢을 뽑는 자리."""
    return (h32(ident[0], ident[1], salt) % 1001) / 1000.0 * 2 - 1


def dash(x, mask):
    """점선 마스크 — 긴 직선을 끊는다 (이어진 선은 이웃 장의 선과 만나 벽을 가로지른다)."""
    return mask[x % len(mask)] == "1"


# ─── 흙 계열 — 사람이 다닌 자리 ────────────────────────────────────────────
# 흙은 '갈색'이 아니다. 먹에 흙기를 아주 옅게 섞은 값이다 (채도 ≤ 34 — 수묵 규약).
DIRT_SHADES = ramp((78, 70, 60, 255), (162, 147, 128, 255), 9)      # 흙 — 습한 먹빛 흙
PATH_SHADES = ramp((100, 92, 81, 255), (188, 176, 158, 255), 10)    # 다져진 길 — 밟혀 마른 흙
COARSE_SHADES = ramp((84, 76, 66, 255), (172, 159, 140, 255), 10)  # 다진 흙 — 자갈이 섞인 마당
PODZOL_SHADES = ramp((54, 48, 42, 255), (126, 113, 98, 255), 9)      # 부엽토 — 삭은 잎이 덮인 땅
GRAVEL_SHADES = ramp((84, 82, 79, 255), (194, 192, 186, 255), 11)  # 자갈 — 무채색 조약돌


def earth_base(x, y, salt, clump=1.15, grit=0.75):
    """흙바탕 — 큰 덩이(보간) + 잔 알갱이(계단). 방향이 없다 (흙에는 결이 없다)."""
    return (smooth_octave(x, y, 8, salt, clump)
            + smooth_octave(x, y, 4, salt ^ 0x27, clump * 0.62)
            + octave(x, y, 2, salt ^ 0x4B, grit * 0.55)
            + octave(x, y, 1, salt ^ 0x6D, grit))


def pebble_specks(x, y, salt, every=17, deep=-1.5, light=1.6):
    """흙에 박힌 잔돌 — **점**이지 선이 아니다 (점은 격자를 만들지 않는다).
    빛을 받는 윗면(밝게)과 박힌 그늘(어둡게)이 같이 있어야 '박혀 있다'로 읽힌다."""
    k = h32(x, y, salt) % every
    if k == 0:
        return light
    if k == 1:
        return deep
    return 0.0


def dirt_rows():
    """흙 — 갈아엎지 않은 맨땅. 덩이지고 습하다."""
    return [[step(DIRT_SHADES, 4.2 + earth_base(x, y, 0x51) + pebble_specks(x, y, 0x63, 23))
             for x in range(16)] for y in range(16)]


def coarse_dirt_rows():
    """다진 흙 — 마당·연무장. 사람이 밟아 굳었고 잔돌이 드러났다 (흙보다 마르고 거칠다)."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            v = 4.6 + earth_base(x, y, 0x71, clump=0.9, grit=1.05)
            v += pebble_specks(x, y, 0x83, 11, -1.8, 2.0)      # 잔돌이 흙보다 두 배 많다
            row.append(step(COARSE_SHADES, v))
        rows.append(row)
    return rows


# 발자국 — 길 위에 남은 자국. **선이 아니라 자국이다**: 바퀴 자국을 한 줄로 길게 그으면
# 그 줄이 16px마다 되풀이돼 길 전체에 세로 홈이 파인다 (사용자가 두 번 지적한 '한 방향 반복').
# 그래서 자국은 **짧고 흩어져** 있다 — 발 하나, 발 하나. 값 = 명암 계단 델타.
PATH_PRINTS = [(2, 3), (3, 3), (2, 4), (3, 4), (3, 5),          # 발자국 1 (앞꿈치가 깊다)
               (9, 1), (10, 1), (9, 2), (10, 2),                # 발자국 2
               (6, 8), (7, 8), (6, 9), (7, 9), (7, 10),         # 발자국 3
               (12, 11), (13, 11), (12, 12), (13, 12),          # 발자국 4
               (4, 13), (5, 13), (5, 14)]                       # 발자국 5 (스쳐 밟은 자리)


def dirt_path_top_rows():
    """흙길 — 사람이 다닌 자리. 밟혀 다져진 면 + 흩어진 발자국 + 긁힌 자국.
    다져진 길은 맨흙보다 **밝고 매끈하다** (밟히면 알갱이가 눌려 평평해진다)."""
    prints = set(PATH_PRINTS)
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            v = 6.0 + earth_base(x, y, 0x95, clump=0.85, grit=0.42)
            if (x, y) in prints:
                v -= 2.3                                        # 파인 발자국 (그늘)
            if (x - 1, y - 1) in prints and (x, y) not in prints:
                v += 0.8                                        # 자국 둘레로 밀린 흙 (턱)
            v += pebble_specks(x, y, 0xA9, 29, -1.2, 1.0)
            row.append(step(PATH_SHADES, v))
        rows.append(row)
    return rows


def side_rows(top_rows, body_rows, band=2):
    """옆면 문법 — 위 몇 줄은 윗면 자재, 아래는 몸통 자재 (바닐라 dirt_path_side·podzol_side와 같은 문법).

    ★ 이음매의 함정: 이 텍스처는 **위와 아래가 다른 자재**라, 세로로 이어 붙이면 랩 경계
      (맨아랫줄 흙 → 맨윗줄 다진 흙)가 장 안에서 가장 강한 가로 경계가 된다 (실측 2.05 — 위반).
      그런데 그 경계는 **거짓 결함이 아니다**: 흙길 블록을 두 장 쌓으면 실제로 그렇게 보인다
      (위 블록의 다져진 켜가 아래 블록의 흙 위에 얹힌다). 즉 고칠 것은 그림이 아니라 **대비의 배분**이다.
      층 경계에 **그늘 한 줄 + 그 아래 빛 받는 한 줄**을 넣는다 — 덮개 밑의 그늘과, 그늘을 벗어나
      갓 드러난 흙이다. 물리적으로 옳고(덮인 층은 아래에 그림자를 드리운다), 동시에 장 안에
      랩 경계에 필적하는 강한 가로 경계를 **둘** 만들어 이음매 지표의 기준선을 세운다."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            if y < band:
                row.append(top_rows[y][x])
            elif y == band:
                p = body_rows[y][x]                            # 층 경계 — 덮개 아래 깊은 그늘
                row.append(tuple(max(0, round(c * 0.58)) for c in p[:3]) + (255,))
            elif y == band + 1:
                p = body_rows[y][x]                            # 그늘 아래 — 갓 드러난 흙 (빛을 받는다)
                row.append(tuple(min(255, round(c * 1.22)) for c in p[:3]) + (255,))
            else:
                row.append(body_rows[y][x])
        rows.append(row)
    return rows


def rooted_dirt_rows():
    """뿌리 흙 — 흙 사이로 잔뿌리가 비친다. 뿌리는 **짧고 굽은 실**이다 (긴 직선 금지)."""
    roots = {(3, 1): 1, (3, 2): 1, (4, 3): 1, (4, 4): 1, (5, 5): 1,      # 뿌리 1
             (11, 2): 1, (11, 3): 1, (10, 4): 1, (10, 5): 1,             # 뿌리 2
             (7, 9): 1, (8, 10): 1, (8, 11): 1, (9, 12): 1,              # 뿌리 3
             (2, 10): 1, (2, 11): 1, (3, 12): 1,                         # 뿌리 4
             (13, 8): 1, (13, 9): 1, (12, 10): 1}                        # 뿌리 5
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            v = 4.0 + earth_base(x, y, 0xB1)
            if (x, y) in roots:
                v += 3.4                                        # 잔뿌리 — 흙보다 밝다 (마른 실)
            row.append(step(DIRT_SHADES, v))
        rows.append(row)
    return rows


def podzol_top_rows():
    """부엽토 — 삭은 잎이 덮인 어두운 땅 (소나무 밑, 산길 어귀)."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            v = 4.4 + earth_base(x, y, 0xC3, clump=1.35, grit=0.9)
            k = h32(x, y, 0xD5) % 13
            if k == 0:
                v += 2.2                                        # 삭은 잎 조각 (밝은 티끌)
            elif k == 1:
                v -= 1.6                                        # 젖어 눌어붙은 자리
            row.append(step(PODZOL_SHADES, v))
        rows.append(row)
    return rows


def gravel_rows():
    """자갈 — 조약돌 하나하나. 보로노이 세포가 곧 돌이고, 돌마다 크기·톤·빛이 다르다.
    빛은 좌상단 — 돌의 좌상은 밝고 우하는 그늘이다 (이 명암이 '박힌 돌'을 만든다).

    ★ 소금 0x2F → 0x41. 보로노이는 랩 안전하지만 **랩 경계가 어느 자리에 떨어지는가는 소금의 운**이다:
      경계가 하필 돌과 돌 사이의 깊은 틈을 지나면 그 줄이 장에서 가장 강한 세로 경계가 된다
      (0x2F 실측 이음매 1.45 — 위반). 소금 열둘을 재서 경계가 돌의 **몸**을 지나는 값을 골랐다
      (0x41 → 0.76). 그림은 그대로고 돌의 배치만 다르다."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            d1, d2, ident, (cx, cy) = wrapped_cells(x, y, 4, 0x41)
            v = 6.0 + cell_rand(ident, 0x3D) * 2.0              # 돌마다 다른 톤
            v -= ((x + 0.5 - cx) + (y + 0.5 - cy)) * 0.62       # 돌의 입체 (좌상 밝고 우하 어둡다)
            if d2 - d1 < 0.85:
                v -= 3.2 * (1.0 - (d2 - d1) / 0.85)             # 돌과 돌 사이 — 그늘진 틈
            v += octave(x, y, 1, 0x4F, 0.5)                     # 돌 표면의 거칢
            row.append(step(GRAVEL_SHADES, v))
        rows.append(row)
    return rows


def farmland_rows(moist=False):
    """밭 — 갈아 놓은 이랑. 이랑은 **끊어 판다** (이어진 골은 밭이 아니라 골함석이다)."""
    shades = ramp((62, 54, 46, 255), (138, 126, 110, 255), 9) if moist else DIRT_SHADES
    furrows = {2: -1.9, 3: 0.7, 8: -2.1, 9: 0.8, 13: -1.7, 14: 0.6}   # 골 셋 (간격 6·5·5 — 등간격 금지)
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            v = (3.6 if moist else 4.4) + earth_base(x, y, 0xE7, clump=0.8, grit=0.7)
            d = furrows.get(y, 0.0)
            if d and dash(x + y, "1101101110110101"):           # 골도 점선이다
                v += d
            row.append(step(shades, v))
        rows.append(row)
    return rows


# ─── 돌 계열 — 막돌·전돌·다듬은 돌 ────────────────────────────────────────
STONE_SHADES = ramp((100, 99, 96, 255), (186, 185, 181, 255), 10)      # 자연석 — 무채색
COBBLE_SHADES = ramp((78, 77, 74, 255), (190, 189, 184, 255), 11)     # 막돌 — 명암 폭이 넓다
BRICK_SHADES = ramp((92, 90, 86, 255), (182, 179, 173, 255), 11)      # 전돌(塼) — 구운 회벽돌
DEEP_SHADES = ramp((36, 35, 36, 255), (96, 95, 96, 255), 9)           # 심층암 — 검은 돌
POLISH_SHADES = ramp((116, 115, 112, 255), (196, 195, 190, 255), 9)      # 다듬은 안산암 — 좁은 폭(매끈)
IRON_SHADES = ramp((50, 49, 47, 255), (152, 150, 145, 255), 9)        # 무쇠 — 솥·테


def rubble_rows(shades, cell=4, salt=0x31, mid=6.2, damp=False, mortar=3.4):
    """막돌 — 쌓아 올린 돌. 세포 하나가 돌 하나이고, 줄눈(틈)이 그 사이를 메운다.
    damp=True 면 이끼 낀 돌 (색을 쓰지 않는다 — **먹의 농담**으로 젖은 자리를 만든다)."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            d1, d2, ident, (cx, cy) = wrapped_cells(x, y, cell, salt)
            v = mid + cell_rand(ident, salt ^ 0x17) * 1.9
            v -= ((x + 0.5 - cx) + (y + 0.5 - cy)) * 0.30      # 돌 하나의 볼록함
            if d2 - d1 < 1.0:
                v -= mortar * (1.0 - (d2 - d1))                # 줄눈 — 돌 사이 그늘
            v += octave(x, y, 1, salt ^ 0x2B, 0.55)            # 정 자국·풍화
            if damp:
                w = smooth_octave(x, y, 8, salt ^ 0x3F, 1.0) + smooth_octave(x, y, 4, salt ^ 0x59, 0.6)
                if w > 0.35:
                    v -= 1.7 * min(1.0, (w - 0.35) * 1.6)      # 젖어 검어진 자리 (이끼)
            row.append(step(shades, v))
        rows.append(row)
    return rows


def stone_rows(shades, salt=0x77, mid=4.8, amp=1.0, speck=True):
    """자연석 — 결도 무늬도 없는 몸. 얼룩만 있다 (자연의 돌은 아무 방향도 편들지 않는다)."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            v = mid + (smooth_octave(x, y, 8, salt, 1.1 * amp)
                       + smooth_octave(x, y, 4, salt ^ 0x35, 0.75 * amp)
                       + octave(x, y, 2, salt ^ 0x4D, 0.42 * amp)
                       + octave(x, y, 1, salt ^ 0x61, 0.36 * amp))
            if speck:
                k = h32(x, y, salt ^ 0x8F) % 19
                if k == 0:
                    v -= 1.5                                    # 검은 점 — 광물 알갱이
                elif k == 1:
                    v += 1.2
            row.append(step(shades, v))
        rows.append(row)
    return rows


# 전돌(塼) 켜 — 높이 5·3·4·4. **등간격 4는 곧 주기 4의 격자다** (벽에 눈금이 뜬다).
# 켜마다 어긋나기(offset)도 다르다 — 벽돌은 반씩 어긋나 쌓지만, 그 반이 언제나 정확히 반이면
# 그것은 벽이 아니라 방안지다.
BRICK_COURSES = [(0, 4, 0), (5, 7, 5), (8, 11, 2), (12, 15, 6)]   # (y0, y1, x어긋나기)
BRICK_WIDTH = 8
BRICK_JOINT_DASH = "1111011111101101"     # 줄눈도 점선 — 완전한 직선은 이웃 장까지 이어진다


def brick_rows(shades, salt=0xA3, cracked=False, damp=False):
    """전돌 벽 — 구운 벽돌을 켜켜이 쌓았다. 벽돌마다 톤이 다르고 줄눈은 끊긴다."""
    cracks = {(4, 2): -2.4, (4, 3): -2.6, (5, 4): -2.4, (5, 5): -2.2,      # 금 1
              (11, 9): -2.3, (12, 10): -2.5, (12, 11): -2.2,               # 금 2
              (8, 13): -2.1, (9, 14): -2.0} if cracked else {}
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            course = next(c for c in BRICK_COURSES if c[0] <= y <= c[1])
            y0, y1, off = course
            bx = (x + off) % 16
            ident = (bx // BRICK_WIDTH, y0)                     # 벽돌 한 장의 이름
            v = 6.2 + cell_rand(ident, salt) * 1.7              # 벽돌마다 다른 톤 (가마의 운)
            v += smooth_octave(x, y, 4, salt ^ 0x1D, 0.55) + octave(x, y, 1, salt ^ 0x39, 0.42)
            v -= (y - y0) * 0.28                                # 벽돌 한 장의 위아래 명암 (윗모가 밝다)
            if y == y0 and dash(x, BRICK_JOINT_DASH):
                v -= 2.6                                        # 가로 줄눈 (켜 사이) — 점선
            elif y == y0 + 1:
                v += 0.9                                        # 줄눈 아래 — 빛 받는 벽돌의 윗모.
                #   이 한 줄이 켜마다 '줄눈 → 밝은 모' 라는 강한 가로 경계를 만든다. 그래서
                #   랩 경계(맨아랫줄 벽돌 → 맨윗줄 줄눈)가 더는 장에서 유일하게 강한 경계가 아니다
                #   (이음매 1.25 → 통과). 입체적으로도 옳다: 벽돌은 줄눈보다 앞으로 나와 있다.
            if bx % BRICK_WIDTH == 0 and dash(y, "1110110111011011"):
                v -= 2.4                                        # 세로 줄눈 (벽돌 사이) — 점선
            v += cracks.get((x, y), 0.0)
            if damp:
                w = smooth_octave(x, y, 8, salt ^ 0x77, 1.0)
                if w > 0.3:
                    v -= 1.8 * min(1.0, (w - 0.3) * 1.7)        # 젖은 자리 (이끼 낀 담)
            row.append(step(shades, v))
        rows.append(row)
    return rows


# 새긴 전돌 — 문(門)에 박는 회(回)자 무늬. 조성기는 CHISELED_STONE_BRICKS 를 문설주·기단에 쓴다.
CHISELED_ART = [
    "................",
    ".##############.",
    ".#............#.",
    ".#.##########.#.",
    ".#.#........#.#.",
    ".#.#.######.#.#.",
    ".#.#.#....#.#.#.",
    ".#.#.#.##.#.#.#.",
    ".#.#.#.##.#.#.#.",
    ".#.#.#....#.#.#.",
    ".#.#.######.#.#.",
    ".#.#........#.#.",
    ".#.##########.#.",
    ".#............#.",
    ".##############.",
    "................",
]


def chiseled_brick_rows():
    """새긴 전돌 — 회(回)자 문양을 **파낸** 돌. 파인 홈은 위·왼쪽이 어둡고 아래·오른쪽이 밝다
    (빛이 좌상단에서 오므로, 홈의 좌상 벽은 그늘이고 우하 벽은 빛을 받는다)."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            v = 6.0 + smooth_octave(x, y, 8, 0xB7, 0.8) + octave(x, y, 1, 0xC9, 0.45)
            if CHISELED_ART[y][x] == "#":
                v -= 2.8                                        # 파인 홈
                if y > 0 and CHISELED_ART[y - 1][x] == ".":
                    v -= 0.9                                    # 홈의 위 벽 — 가장 깊은 그늘
            else:
                if y > 0 and CHISELED_ART[y - 1][x] == "#":
                    v += 1.1                                    # 홈 아래 — 빛 받는 모
            row.append(step(BRICK_SHADES, v))
        rows.append(row)
    return rows


# 정(釘) 자국 — 다듬은 안산암을 자연 돌과 가르는 유일한 표식. 사람이 깎은 돌에는 **연장 자국**이 있다.
# 짧은 2px 획 여덟, 방향과 자리를 흩어 둔다 (한 방향으로 나란하면 그것은 빗살무늬가 된다).
POLISH_CHISEL = [((2, 2), (3, 2)), ((6, 4), (6, 5)), ((11, 3), (12, 3)),
                 ((3, 8), (4, 9)), ((9, 8), (10, 8)), ((13, 10), (13, 11)),
                 ((5, 12), (6, 12)), ((10, 13), (11, 14))]


def polished_andesite_rows():
    """다듬은 안산암 — 계단·단·기단. 자연 안산암과 **달라야 한다**: 매끈하고(얼룩 진폭 절반),
    그러나 죽은 면이 아니다 — 물갈이 자국과 정(釘) 자국이 사람의 손을 증언한다."""
    chisel = {p for pair in POLISH_CHISEL for p in pair}
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            v = 4.6 + smooth_octave(x, y, 8, 0x5D, 0.62) + smooth_octave(x, y, 4, 0x71, 0.34)
            v += octave(x, y, 1, 0x8B, 0.20)                    # 물갈이한 면의 아주 잔 결
            if (x, y) in chisel:
                v -= 1.6                                        # 정 자국 (얕게 판 획)
            row.append(step(POLISH_SHADES, v))
        rows.append(row)
    return rows


SMOOTH_SHADES = ramp((114, 113, 110, 255), (198, 197, 192, 255), 9)   # 켠 돌 — 매끈하되 죽지 않게


def smooth_stone_rows(band=False):
    """매끄러운 돌 — 켠 돌(石材). band=True 면 반블록 옆면 (위·아래에 켠 자국 띠).

    ★ 첫 판은 얼룩 진폭이 너무 작아 **색 2개·명암차 9** 였다 (린트: '평면'·'밋밋' 이중 위반).
      게다가 반블록 옆면은 가로 띠뿐이라 x 로 밀어도 제 자신이었다 — 자기 복제 0.94 (축 7 위반).
      매끈함은 '아무것도 없음'이 아니다. 켠 돌에는 **톱날이 지나간 자국**과 돌 자체의 얼룩이 있다.
      그래서 얼룩을 살리고(명암차 ≥ 30), 켠 자국은 **짧은 점획**으로 흩어 x 방향 등질성을 깬다."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            v = (4.4
                 + smooth_octave(x, y, 8, 0x93, 1.70)           # 돌의 큰 얼룩
                 + smooth_octave(x, y, 4, 0xA7, 1.00)
                 + octave(x, y, 1, 0xBB, 0.55))                 # 켠 면의 잔 결
            if h32(x, y, 0xCF) % 23 == 0:
                v += 1.6                                        # 톱날이 스친 반짝임 (점 — 선이 아니다)
            elif h32(x, y, 0xCF) % 19 == 0:
                v -= 1.4                                        # 돌에 박힌 검은 알갱이
            if band:
                # 점선의 **위상이 위아래에서 같아야** 한다 (dash(x) — dash(x+y)가 아니라).
                # 위상이 어긋나면 맨윗줄과 맨아랫줄이 서로 다른 무늬가 되어 상하 랩이 터진다.
                if y in (0, 15) and dash(x, "1110110111011010"):
                    v -= 2.0                                    # 켠 자국 — 모서리 띠 (점선으로 끊는다)
                elif y in (1, 14):
                    v += 0.8
            row.append(step(SMOOTH_SHADES, v))
        rows.append(row)
    return rows


# ─── 목재 계열 — 판자·기둥·수피 ───────────────────────────────────────────
# 목재는 조성 팔레트의 최대 면적이다 (DARK_OAK_PLANKS 33 + SPRUCE_FENCE 39 + SPRUCE_PLANKS 18 …).
# 울타리·계단·반블록·문·다락이 전부 **판자 텍스처 한 장**을 쓴다 — 여기가 세계를 가장 크게 바꾼다.
# 색: 채도 ≤ 40 (수묵 규약). '갈색 나무'가 아니라 **먹에 흙기를 옅게 섞은 나무**다.
DARK_WOOD = ramp((60, 52, 43, 255), (152, 135, 113, 255), 9)       # 짙은 목재 (다크오크) — 관아·객잔 기둥
SPRUCE_WOOD = ramp((76, 66, 54, 255), (172, 154, 129, 255), 9)   # 가문비 — 산채 목책·서민 판자
OAK_WOOD = ramp((94, 82, 67, 255), (188, 169, 142, 255), 9)      # 참나무 — 밝은 판자
STRIPPED_WOOD = ramp((102, 90, 74, 255), (200, 180, 152, 255), 9)  # 벗긴 원목 — 노출 기둥
CHERRY_WOOD = ramp((84, 66, 64, 255), (180, 148, 144, 255), 9)   # 매화나무 — 아주 옅은 붉은 기

# 판자 넉 장 — 폭이 다 다르다 (5·4·3·4). 등폭 4는 곧 주기 4의 줄무늬다.
PLANK_BOARDS = [(0, 4, 0x21, 0.55), (5, 8, 0x4D, -0.45), (9, 11, 0x6B, 0.30), (12, 15, 0x8F, -0.25)]
PLANK_BUTTS = {0: 11, 5: 4, 9: 13, 12: 6}    # 널마다 이음매(마구리)가 다른 자리에 온다


def wood_grain(x, y, salt, vertical=False, amp=1.3):
    """나뭇결 — 결은 **한 방향으로 흐른다**. 결 방향으로는 길게 늘이고 직각으로는 잘게 나눈다."""
    gx, gy = (x // 4, y) if vertical else (x, y // 4)
    return octave(gx, gy, 1, salt, amp) + octave(x, y, 1, salt ^ 0x6C, amp * 0.30)


def plank_rows(shades, salt=0x11):
    """판자 — 널 넉 장을 이어 깐 면. 널마다 폭·톤·결의 씨앗·이음매 자리가 다르다
    (목수가 같은 널 넉 장을 켤 수는 없다 — 그 '같음'이 곧 격자다)."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            y0, y1, seed, tone = next(b for b in PLANK_BOARDS if b[0] <= y <= b[1])
            v = 5.0 + tone + wood_grain(x, y, seed ^ salt, amp=1.15)
            if y == y0:
                v -= 2.5                                        # 널 사이 틈 (그늘)
            elif y == y0 + 1:
                v += 0.8                                        # 틈 아래 — 빛 받는 널의 윗모
            elif y == y1:
                v -= 0.9                                        # 널의 아랫모 — 살짝 어둡다
            if x == PLANK_BUTTS[y0]:
                v -= 1.9                                        # 마구리 이음 (널이 끝나는 자리)
            if (x, y) in ((PLANK_BUTTS[y0] - 2, y0 + 1), (PLANK_BUTTS[y0] + 2, y1 - 1)):
                v -= 1.5                                        # 못 (이음 곁에 박는다)
            row.append(step(shades, v))
        rows.append(row)
    return rows


# 수피(樹皮) — 거칠게 터진 세로 골. 골의 폭이 다 다르다 (3·2·4·2·3·2).
BARK_COLUMNS = [(0, 2, 0x31, 0.5), (3, 4, 0x57, -0.6), (5, 8, 0x79, 0.25),
                (9, 10, 0x9D, -0.35), (11, 13, 0xC1, 0.45), (14, 15, 0xE3, -0.5)]


def bark_rows(shades, salt=0x13, rough=1.0, knot=None):
    """통나무 수피 — **도적의 집**이다. 거칠게 쪼갠 결, 터진 골, 옹이.
    골은 세로로 흐르되 **끊긴다** (이어진 골은 통나무가 아니라 골함석이다)."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            x0, x1, seed, tone = next(c for c in BARK_COLUMNS if c[0] <= x <= c[1])
            v = 5.2 + tone + wood_grain(x, y, seed ^ salt, vertical=True, amp=1.35 * rough)
            if x == x0 and dash(y + x0, "1101110110111011"):
                v -= 2.6 * rough                                # 터진 골 (점선 — 끝까지 잇지 않는다)
            elif x == x0 + 1:
                v += 0.7                                        # 골 오른쪽 — 빛 받는 등성이
            v += octave(x, y, 1, seed ^ 0x2D, 0.45 * rough)     # 수피의 거스러미
            if knot:
                kx, ky = knot
                d = ((x - kx) ** 2 * 1.4 + (y - ky) ** 2) ** 0.5
                if d < 2.6:
                    v -= 2.4 - d * 0.7                          # 옹이 — 가운데가 가장 어둡다
                elif d < 3.4:
                    v += 0.8                                    # 옹이 둘레 — 결이 밀려 솟는다
            row.append(step(shades, v))
        rows.append(row)
    return rows


def log_top_rows(shades, salt=0x17, freq=1.85):
    """통나무 마구리 — 나이테. 동심원이라 어느 방향도 편들지 않는다 (반복의 병에서 자유롭다).

    ★ 중심은 **반드시 장의 한가운데(7.5, 7.5)** 다. 처음엔 '나무는 심이 치우친다'며 중심을 옮겼는데,
      그러자 이음매가 터졌다 (오크 1.44 · 벚 1.33 · 다크오크 1.38 — 린트가 옳다): 중심이 치우치면
      좌변과 우변의 테 위상이 어긋나 랩 경계가 이 장에서 가장 강한 경계가 된다.
      중심이 한가운데면 x=0 과 x=15 는 중심에서 **같은 거리**라 테의 값이 같다 → 랩이 조용해진다.
      나무마다 다른 것은 중심이 아니라 **테의 간격(freq)과 결의 씨앗(salt)** 으로 준다 —
      실제로도 나이테를 가르는 것은 심의 자리가 아니라 자란 해의 굵기다."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            d += smooth_octave(x, y, 8, salt, 0.9)              # 나이테는 정확한 원이 아니다
            v = 5.6 + math.sin(d * freq) * 1.5                  # 테 — 진한 테와 옅은 테가 번갈아
            v += octane_grain(x, y, salt)
            if d < 1.4:
                v -= 1.2                                        # 고갱이 (심재 — 짙다)
            row.append(step(shades, v))
        rows.append(row)
    return rows


def octane_grain(x, y, salt):
    """마구리의 잔 결 — 톱니 자국(켠 자리는 매끈하지 않다)."""
    return octave(x, y, 2, salt ^ 0x4E, 0.45) + octave(x, y, 1, salt ^ 0x72, 0.35)


def stripped_rows(shades, salt=0x19):
    """벗긴 원목 — 노출 기둥(관아·문파 본전). 수피를 벗겨 결이 곧게 드러났다."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            v = 5.4 + wood_grain(x, y, salt, vertical=True, amp=1.0)
            if x in (3, 10) and dash(y + x, "1011101101110110"):
                v -= 1.3                                        # 자귀 자국 (깎아 낸 결)
            row.append(step(shades, v))
        rows.append(row)
    return rows


# ─── 초가(草家) — 성글게 이은 짚 ──────────────────────────────────────────
STRAW_SHADES = ramp((106, 96, 75, 255), (206, 191, 155, 255), 11)   # 마른 짚 — 저채도 볏빛
# 짚단 — 층마다 두께가 다르다 (4·3·5·4). 이엉은 자로 재어 잇지 않는다.
STRAW_LAYERS = [(0, 3, 0x23, 0.5), (4, 6, 0x47, -0.4), (7, 11, 0x6D, 0.25), (12, 15, 0x91, -0.3)]


def hay_side_rows():
    """초가 옆면 — 성글게 이은 짚. 짚가닥이 가로로 눕고, 층 끝에서 **삐져나온다**
    (가지런한 짚은 짚이 아니라 골판지다). 새끼줄 둘이 세로로 눌러 맨다."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            y0, y1, seed, tone = next(l for l in STRAW_LAYERS if l[0] <= y <= l[1])
            v = 6.2 + tone
            v += octave(x, y, 1, seed, 1.55)                    # 짚가닥 하나하나 (가로로 눕는다)
            v += smooth_octave(x, y, 4, seed ^ 0x3B, 0.6)
            if y == y0 and dash(x + y0, "1011011101101101"):
                v -= 2.4                                        # 층 이음 — 짚단이 겹친 그늘 (점선)
            if y == y1 and h32(x, y, seed ^ 0x5F) % 3 == 0:
                v += 1.5                                        # 삐져나온 짚 끝 (성글게 이은 표식)
            if x in (3, 11) and dash(y + x, "1110110111011010"):
                v -= 2.0                                        # 새끼줄 — 짚을 눌러 맨 자리
            row.append(step(STRAW_SHADES, v))
        rows.append(row)
    return rows


def hay_top_rows():
    """초가 윗면 — 벤 짚대의 **단면**들. 동그란 대롱이 빽빽하다 (옆면과 완전히 다른 그림이라야 한다)."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            d1, d2, ident, (cx, cy) = wrapped_cells(x, y, 2, 0x8D)
            v = 6.6 + cell_rand(ident, 0xA1) * 1.8              # 짚대마다 다른 톤
            v -= ((x + 0.5 - cx) + (y + 0.5 - cy)) * 0.5        # 대롱의 볼록함
            if d2 - d1 < 0.6:
                v -= 2.8                                        # 짚대 사이 틈
            v += octave(x, y, 1, 0xB5, 0.5)
            row.append(step(STRAW_SHADES, v))
        rows.append(row)
    return rows


# ─── 천 계열 — 자리(멍석)·차양 ────────────────────────────────────────────
# 양털은 조성기에서 **깔개(카펫)** 로만 쓰인다: 흰 자리·삼베 자리·짚자리, 그리고 **붉은 차양**.
# 채색 허용은 붉은 차양뿐이다 (수묵 규약: 채색은 차양·매화·등롱·깃발에만).
# 씨실·날실 — 굵기가 다 다르다 (2·3·2·3·2·2·2). 등간격 격자는 곧 모눈종이다.
WEAVE_WARP = [(0, 1, 0.5), (2, 4, -0.4), (5, 6, 0.3), (7, 9, -0.25), (10, 11, 0.45),
              (12, 13, -0.35), (14, 15, 0.2)]


def cloth_rows(shades, salt=0x2B, mid=6.0):
    """자리(蓆) — 짜인 천. 실 굵기가 고르지 않고, 짜인 결이 위아래로 엇갈린다."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            wx = next(w for w in WEAVE_WARP if w[0] <= x <= w[1])
            wy = next(w for w in WEAVE_WARP if w[0] <= y <= w[1])
            over = (wx[0] // 2 + wy[0] // 2) % 2 == 0           # 씨실이 위로 지나가는 칸
            v = mid + wx[2] * 0.6 + wy[2] * 0.6
            v += 0.75 if over else -0.75                        # 짜임 — 위로 지난 실이 빛을 받는다
            if x == wx[0] or y == wy[0]:
                v -= 0.7                                        # 실과 실 사이 골
            v += octave(x, y, 1, salt, 0.6) + smooth_octave(x, y, 8, salt ^ 0x39, 0.7)  # 올·물때
            row.append(step(shades, v))
        rows.append(row)
    return rows


# ─── 기물 — 통(술독)·솥(약탕)·시렁 ────────────────────────────────────────
# 통 널 — 폭이 다 다르다 (3·2·4·3·4). BARREL 39회는 조성 팔레트 3위다 (객잔·표국·산채가 통으로 산다).
BARREL_STAVES = [(0, 2, 0x35, 0.45), (3, 4, 0x59, -0.5), (5, 8, 0x7D, 0.2),
                 (9, 11, 0xA3, -0.3), (12, 15, 0xC7, 0.4)]
BARREL_HOOPS = (2, 13)      # 테 두 줄 (대나무 테)


def barrel_side_rows():
    """술독·쌀독 옆면 — 세로로 세운 널과 그것을 조인 대나무 테 둘."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            x0, x1, seed, tone = next(s for s in BARREL_STAVES if s[0] <= x <= s[1])
            v = 5.2 + tone + wood_grain(x, y, seed, vertical=True, amp=1.1)
            if x == x0:
                v -= 2.2                                        # 널 사이 틈
            elif x == x0 + 1:
                v += 0.6
            if y in BARREL_HOOPS:
                v = 7.2 + octave(x, y, 1, 0xD9, 0.5)            # 테 — 빛 받는 대오리
            elif y - 1 in BARREL_HOOPS:
                v = 2.6 + octave(x, y, 1, 0xE5, 0.4)            # 테 아래 그늘 (테가 떠 보인다)
            row.append(step(SPRUCE_WOOD, v))
        rows.append(row)
    return rows


def barrel_top_rows(open_lid=False):
    """독 뚜껑 — 널을 짜 맞춘 원판 + 테. open_lid=True 면 열린 독 (안이 깊이 어둡다)."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            if open_lid and d < 5.4:
                t = min(1.0, d / 5.4)
                base = mix((16, 14, 12, 255), (52, 45, 38, 255), t)   # 독 안 — 바닥이 어렴풋
                n = octave(x, y, 1, 0xF1, 6.0)
                row.append(tuple(max(0, min(255, round(c + n))) for c in base[:3]) + (255,))
                continue
            y0, y1, seed, tone = next(b for b in PLANK_BOARDS if b[0] <= y <= b[1])
            v = 5.4 + tone + wood_grain(x, y, seed ^ 0x44, amp=1.05)
            if y == y0:
                v -= 2.0                                        # 널 사이 틈
            if 6.0 < d < 7.2:
                v = 7.0 + octave(x, y, 1, 0x2D, 0.5)            # 테 (독 아가리를 두른 대오리)
            elif 7.2 <= d < 7.9:
                v = 2.8                                         # 테 바깥 그늘
            row.append(step(SPRUCE_WOOD, v))
        rows.append(row)
    return rows


def cauldron_rows(part):
    """약탕관(藥湯罐) — 무쇠 솥. 의방·객잔 부엌·산채 취사장의 물그릇.
    무쇠는 매끈하지 않다: 두들겨 편 자국(움푹)과 그을음이 있다."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            v = 5.2 + smooth_octave(x, y, 4, 0x63, 0.85) + octave(x, y, 1, 0x87, 0.55)
            if h32(x, y, 0x9B) % 11 == 0:
                v -= 1.4                                        # 두들긴 자국 (움푹 팬 곳)
            if part == "side":
                # ★ 이음매(실측 2.86 — 위반)의 원인은 '밝은 전(y0) ↔ 그을린 밑동(y15)'이었다.
                #   솥은 세로로 쌓는 물건이 아니지만 이음매 축은 그것을 모른다 — 통과해야 한다.
                #   처방: 밑동의 그을음을 얕게 깎고(-1.6 → -0.9), 대신 **솥의 허리(y11)에 띠**를
                #   둘러 장 안에 강한 가로 경계를 하나 더 만든다 (무쇠 솥의 덧테 — 실제로 있는 것이다).
                if y in (0, 1):
                    v += 2.0                                    # 솥 전(테두리) — 빛을 받는다
                elif y == 2:
                    v -= 2.2                                    # 전 아래 깊은 그늘
                elif y == 3:
                    v += 0.9                                    # 그늘 아래 — 배가 부른 몸통이 빛을 받는다
                if y == 11:
                    v -= 2.4                                    # 허리 덧테 (무쇠를 두른 자리)
                elif y == 12:
                    v += 1.2                                    # 덧테 아래 — 되비침
                if y >= 13:
                    v -= 0.9                                    # 불에 그을린 밑동 (얕게)
            elif part == "top":
                d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
                if d < 5.8:
                    v -= 3.4 - d * 0.25                         # 솥 안 — 깊이 어둡다
                elif d < 7.0:
                    v += 1.6                                    # 솥 전
            elif part == "inner":
                v -= 2.6                                        # 솥 바닥 — 검다
                if h32(x, y, 0xAF) % 7 == 0:
                    v -= 0.8                                    # 눌어붙은 자국
            elif part == "bottom":
                v -= 1.2
                if h32(x, y, 0xC5) % 5 == 0:
                    v -= 1.0                                    # 그을음
            row.append(step(IRON_SHADES, v))
        rows.append(row)
    return rows


# 시렁(*_SHELF) — 1.21.9 신규 블록. 조성기가 표국·의방·객잔·산채 두목 막사·문파 본전에 건다
# (병장기 걸이·약장·술선반). 텍스처 한 장이 여섯 면을 다 덮는데, 모델이 잡는 UV 구역은 다음과 같다
# (client jar 의 template_shelf_body.json 에서 읽었다 — 짐작이 아니다):
#   x8~16 / y0~8   = 뒷판 (선반 뒤에 서는 판벽 — 가장 넓게 보이는 면)
#   x0~8  / y0~2   = 윗널의 앞모   ·  x0~8 / y6~8 = 아랫널의 앞모
#   x8~16 / y3.5~6 = 널의 윗면·밑면
# 그래서 **뒷판 구역은 세로 판벽**으로, **널의 앞모 구역은 널의 마구리**로 그린다 — UV를 알고 그리면
# 어느 면을 보아도 '벽에 건 널'로 읽힌다.
def shelf_rows(shades, salt=0x3D):
    """시렁 — 벽에 건 널 둘. 뒷판은 세로 판벽, 널의 앞모에는 못이 박혀 있다.

    ★ 아래 절반(y ≥ 8)은 **위 절반의 거울**이다. 두 가지 이유가 겹친다:
      ① 모델이 잡는 UV는 y 0~8 뿐이다 (client jar 의 template_shelf_body.json — 아래 절반은 안 쓰인다).
      ② 그래서 아래 절반을 아무렇게나 채우면 랩 경계(y15 → y0)가 장에서 가장 강한 가로 경계가 되어
         이음매가 터진다 (실측 1.46~1.60 — 위반). 거울로 접으면 맨아랫줄이 곧 맨윗줄이라
         랩이 0에 가까워진다. 안 보이는 자리를 **이음매를 재는 자가 보는 방식**으로 채운 것이다.
      거울은 병진(平行移動)이 아니므로 자기 복제 상관을 올리지 않는다 (축 7과 충돌하지 않는다)."""
    rows = []
    for y in range(16):
        yy = y if y < 8 else 15 - y                             # 아래 절반 = 위 절반의 거울
        row = []
        for x in range(16):
            if x >= 8:                                          # 뒷판 — 세로로 세운 판벽
                col = (x - 8) // 3                              # 판벽 널 (폭 3)
                v = 5.2 + (0.4, -0.35, 0.25)[col % 3]
                v += wood_grain(x, yy, salt ^ (0x11 * (col + 1)), vertical=True, amp=1.2)
                if (x - 8) % 3 == 0:
                    v -= 1.9                                    # 판벽 널 사이 틈
                if yy in (3, 6):
                    v += 0.9                                    # 널이 뒷판을 가로지른 자리 (반사광)
            else:                                               # 널의 앞모 (마구리) — 못이 보인다
                v = 5.8 + wood_grain(x, yy, salt ^ 0x71, amp=0.9)
                if yy in (0, 6):
                    v -= 1.8                                    # 널의 윗모 그늘
                elif yy in (1, 7):
                    v += 0.8                                    # 윗모 아래 — 빛 받는 널의 낯
                if (x, yy) in ((2, 1), (6, 1), (2, 7), (6, 7)):
                    v -= 2.6                                    # 못 (널을 벽에 박은 자리)
            row.append(step(shades, v))
        rows.append(row)
    return rows


def bone_block_rows(top=False):
    """백골(白骨) — 산채의 위협 표식. 색을 쓰지 않는다: 뼈는 **바랜 흰빛**이다."""
    shades = ramp((96, 93, 86, 255), (208, 204, 192, 255), 9)
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            if top:
                d1, d2, ident, (cx, cy) = wrapped_cells(x, y, 4, 0x4B)
                v = 6.0 + cell_rand(ident, 0x6F) * 1.4          # 뼈 단면 — 골수 구멍
                if d1 < 1.3:
                    v -= 3.0                                    # 골수 (구멍은 어둡다)
                v += octave(x, y, 1, 0x93, 0.5)
            else:
                x0, x1, seed, tone = next(c for c in BARK_COLUMNS if c[0] <= x <= c[1])
                v = 6.0 + tone * 0.7 + wood_grain(x, y, seed ^ 0x5C, vertical=True, amp=0.85)
                if x == x0:
                    v -= 2.0                                    # 뼈와 뼈 사이 그늘
                if h32(x, y, 0xB9) % 17 == 0:
                    v -= 1.3                                    # 금 간 자리
            row.append(step(shades, v))
        rows.append(row)
    return rows


# ─── 매화(梅) — 채색이 허락된 유일한 자리 ─────────────────────────────────
# 수묵의 세계에서 매화만 붉다. 그래서 매화는 **아껴 써야 하고**, 쓰는 자리에서는 확실히 붉어야 한다.
#
# ★★ 이 자리에서 팩이 규약을 배반했다 (2026-07 · 사용자 보고: "벚꽃나무도 분홍빛에서 검은 빛이
#    더 많이 드는 느낌"). 실측: cherry_leaves 평균 RGB (84,68,68) · 밝기 73 — **먹빛 갈색**이었다.
#    규약이 채색을 허락한 유일한 자리가 정작 먹빛이면 **규약이 자기모순**이다.
#
#    원인은 아트 솜씨가 아니라 **자리를 잘못 안 것**이다. 이전 판은 잎 텍스처에 "먹으로 친 가지 위에
#    꽃이 성글게 앉는" 그림을 그렸다 — 불투명 픽셀의 3/4가 먹 가지였다. 그러나 `cherry_leaves` 는
#    **나뭇잎 블록**이고, 나뭇잎 블록은 **수관(樹冠) 전체를 도배한다.** 가지는 `cherry_log` 가
#    이미 그린다. 화폭 하나에 **가지를 두 번 그리고 꽃을 한 번 찍은** 셈이고, 그 결과 매화나무는
#    **검은 구름에 분홍 점이 박힌 것**이 됐다.
#
#    【고침】 잎의 본체는 **꽃**이다. 먹은 꽃 사이의 그늘(깊이)로만 든다.
#    그리고 그것이 오히려 수묵이다 — **먹빛 가지 위의 밝은 분홍 꽃**, 그 대비가 곧 그림이다.
#    빈 자리는 여전히 투명하다: 하늘이 비쳐야 매화지, 빽빽한 분홍 덩어리는 벚꽃 사탕이다.
PLUM_BLOSSOM = (234, 158, 174, 255)      # 매화 꽃잎 — 분홍 (바랬으되 죽지 않았다)
PLUM_BLOSSOM_HI = (250, 204, 212, 255)   # 꽃잎 광 — 빛을 받은 잎
PLUM_BLOSSOM_LO = (198, 118, 140, 255)   # 꽃잎 그늘 — 겹친 잎 아래
PLUM_CORE = (164, 80, 104, 255)          # 꽃 술 — 짙은 분홍 (꽃의 중심)
PLUM_SHADE = (104, 68, 76, 255)          # 꽃 사이 그늘 — 먹에 분홍이 스민 값 (순먹 금지)
PLUM_BRANCH = (58, 48, 46, 255)          # 잔가지 — 먹 (수관 안에서 언뜻 비치는 것뿐)


def cherry_leaves_rows():
    """매화 수관 — **꽃이 본체다.** 먹은 꽃 사이의 그늘로만 든다 (가지는 cherry_log 의 몫).

    '@' 꽃 (분홍 3단) · '%' 겹친 꽃 아래 그늘 · '#' 언뜻 비치는 잔가지 (아껴 쓴다) · '.' 투명(하늘).
    불투명 픽셀의 8할이 꽃이라야 나무 **한 그루**가 분홍으로 선다 (한 장이 수관 전체를 도배하므로)."""
    art = [
        "..@@@.%@@@..@@%.",
        ".@@@@@@@@%.@@@@@",
        "@@%@@@#@@@@@@%@@",
        "@@@@%@@@@%@@@@@@",
        ".@@@@@@@%@@@@@@.",
        "@@%@@@@@@@@#@@@@",
        "@@@@@#@@%@@@@@@@",
        "%@@@@@@@@@@@%@@@",
        "@@@@%@@@@@@@@@@%",
        "@@@#@@@@%@@@@@@@",
        ".@@@@@@%@@@@#@@.",
        "@@@@%@@@@@@@@@@@",
        "@%@@@@@@#@@@@%@@",
        "@@@@@@%@@@@@@@@@",
        ".@@@%@@@@@%@@@@.",
        "..@@@.@@%@..@@@.",
    ]
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            c = art[y][x]
            if c == "@":
                # 꽃잎의 명암 — 뭉치지 않게 결정론 잡음으로 흩는다 (평면 분홍은 사탕이다)
                v = smooth_octave(x, y, 4, 0x77, 1.0) + octave(x, y, 2, 0x31, 0.35)
                if v > 0.42:
                    row.append(PLUM_BLOSSOM_HI)
                elif v < -0.45:
                    row.append(PLUM_CORE if h32(x, y, 0x8D) % 4 == 0 else PLUM_BLOSSOM_LO)
                else:
                    row.append(PLUM_BLOSSOM)
            elif c == "%":
                row.append(PLUM_SHADE)                          # 겹친 꽃 아래 — 깊이
            elif c == "#":
                row.append(PLUM_BRANCH)                         # 잔가지 — 언뜻만
            else:
                row.append(T)                                   # 빈 하늘 — 매화의 여백
        rows.append(row)
    return rows


def cherry_sapling_rows():
    """매화 묘목 — 어린 나무 한 그루. **꽃이 피어 있어야 매화 묘목이다** (먹빛 잔가지 = 죽은 가지).
    이전 판은 sprig_rows(CHERRY_WOOD) 로 구워 밝기 82의 먹빛 삭정이였다."""
    art = [
        "................",
        ".......@@.......",
        "......@@@@......",
        ".....@@%@@@.....",
        "....@@@@@@@@....",
        "...@@%@@@@%@....",
        "....@@@@#@@@@...",
        "...@@@@#@@@@....",
        "....@%@#@@%@....",
        ".....@@#@@@.....",
        "......@#@@......",
        ".......#........",
        ".......#........",
        "......##........",
        ".......#........",
        "................",
    ]
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            c = art[y][x]
            if c == "@":
                v = smooth_octave(x, y, 4, 0x53, 1.0)
                row.append(PLUM_BLOSSOM_HI if v > 0.4 else
                           (PLUM_BLOSSOM_LO if v < -0.45 else PLUM_BLOSSOM))
            elif c == "%":
                row.append(PLUM_CORE)
            elif c == "#":
                row.append(PLUM_BRANCH)                         # 어린 줄기 — 먹 (대비의 짝)
            else:
                row.append(T)
        rows.append(row)
    return rows


# ═══════════════════════════════════════════════════════════════════════════
# 획층(劃層) — 무공의 모션 (파티클 텍스처)
#
# ── 두 층으로 설계한다 (사용자 요구: "팩이 없어도 파티클로 어느 정도는 확인이 되어야 한다") ──
#   팩이 없으면 — 엔진이 쏘는 **바닐라 파티클**만으로 무엇이 일어났는지 읽힌다 (팩 게이트 불가침).
#   팩이 있으면 — 그 파티클의 **그림**이 수묵의 획으로 바뀐다. 엔진은 한 줄도 안 고친다.
# 이 층이 성립하는 이유가 바로 그 점이다: 파티클 텍스처 교체는 **아트만의 층**이다.
# SkillListener 가 Particle.END_ROD 를 쏘는 한, 팩은 그 END_ROD 가 **무엇으로 보이는지**만 정한다.
#
# ── 엔진이 지금 쓰는 파티클 → 바닐라 텍스처 (client jar 의 assets/minecraft/particles/*.json 에서
#    읽었다. 짐작이 아니다 — 없는 경로에 그리면 죽은 텍스처가 된다) ──
#   발경   SWEEP_ATTACK   → sweep_0..7 (32x32)      · CRIT → critical_hit (8x8)
#   검기   ELECTRIC_SPARK → glow (8x8)              · END_ROD → glitter_0..7 (8x8)
#   강기   END_ROD + EXPLOSION → explosion_0..15 (32x32)
#   어검·심검  END_ROD → glitter_*                  · FLASH → flash (32x32)
#   빗나감 CLOUD / 다운캐스트 SMOKE → generic_0..7 (8x8) — 둘이 **같은 텍스처를 공유한다**
#
# ── 렌더 계약 (틀리면 그림이 사라진다) ──
# 바닐라 파티클 텍스처는 전부 **회색조 + 알파**이고, 렌더러가 거기에 **파티클 색을 곱한다.**
#   · 연기(SMOKE)는 색이 어두운 회색(≈0.25)이다 → generic_* 를 먹빛으로 칠하면 곱셈의 결과가
#     **거의 검정**이 되어 연기가 사라진다. 그래서 generic_* 의 먹은 **알파에** 있고 밝기는 흰빛으로 둔다
#     (농담 = 알파의 농담. 이것이 '먹이 물에 번진다'의 정확한 구현이다).
#   · END_ROD·CRIT·SWEEP·EXPLOSION 은 흰색으로 곱해진다 → 텍스처의 명암이 그대로 산다.
#     여기서는 먹을 **밝기로** 쓸 수 있다 (발경의 점획이 검을 수 있는 이유).
#
# ── 전역성의 값 ──
# 파티클도 블록처럼 전역이다. critical_hit 은 바닐라 치명타에도, generic_* 은 모닥불 연기에도 쓰인다.
# 그래서 "무공 전용 기호"를 그리면 안 된다 (화살 치명타에 검기가 뜨면 그건 오염이다).
# 그리는 것은 **먹의 획**이다 — 획은 어느 맥락에서도 어색하지 않다. 모닥불 연기가 수묵으로 번지고,
# 치명타가 먹이 튄 자국이 되는 것은 오염이 아니라 정합이다 (세계 전체가 수묵이므로).
# ═══════════════════════════════════════════════════════════════════════════

def free_octave(x, y, cell, salt, amp):
    """랩 없는 보간 노이즈 — 파티클용. 블록의 smooth_octave 는 16px 주기로 **감기지만**(랩 안전),
    32x32 파티클에 그걸 쓰면 16px마다 무늬가 되풀이된다 (타일이 아닌 그림에 타일의 문법을 쓴 셈).
    파티클은 이어 붙지 않으므로 감을 필요가 없다 — 감지 않는 것이 옳다."""
    def corner(i, j):
        # 격자 인덱스는 **정수**라야 한다 — h32(crc32)는 바이트를 먹는다.
        # 방사(放射) 노이즈는 좌표에 cos·sin 을 넣으므로 x·y 가 실수로 들어온다 (여기서 한 번 터졌다).
        return (h32(int(x // cell) + i, int(y // cell) + j, salt) % 1001) / 1000.0 * 2 - 1

    tx, ty = (x % cell) / cell, (y % cell) / cell
    sx, sy = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
    a = corner(0, 0) + (corner(1, 0) - corner(0, 0)) * sx
    b = corner(0, 1) + (corner(1, 1) - corner(0, 1)) * sx
    return (a + (b - a) * sy) * amp


def ink(v, a):
    """획 한 점 — v = 밝기(0~255), a = 농도(알파). 채색 없음 (수묵 규약: 격은 밝기로만 가른다)."""
    c = max(0, min(255, round(v)))
    return (c, c, c, max(0, min(255, round(a))))


def blank(w, h):
    return [[(255, 255, 255, 0) for _ in range(w)] for _ in range(h)]


def stroke_field(rows, w, h, pts, width, v0, v1, a0, a1, salt, dry=0.55):
    """붓의 획 — 점들을 잇는 심(心)을 따라 먹을 얹는다.

    획이 '선'이 아니라 '붓'인 이유 셋 (이것이 없으면 그냥 그어진 금이다):
      ① 필압(筆壓) — 획은 눌러 시작해 들어 올리며 끝난다 (굵기와 농도가 길이를 따라 변한다)
      ② 갈필(渴筆) — 마른 붓은 털이 갈라져 획 안에 **흰 결**이 남는다 (dry: 그 결의 세기)
      ③ 번짐 — 획의 가장자리는 종이에 스며 흐려진다 (알파가 가장자리에서 떨어진다)
    """
    n = len(pts)
    for y in range(h):
        for x in range(w):
            best, t_at = 1e9, 0.0
            for i in range(n - 1):                      # 심에서 픽셀까지의 최단 거리
                (x0, y0), (x1, y1) = pts[i], pts[i + 1]
                dx, dy = x1 - x0, y1 - y0
                L = dx * dx + dy * dy
                t = 0.0 if L == 0 else max(0.0, min(1.0, ((x - x0) * dx + (y - y0) * dy) / L))
                px, py = x0 + dx * t, y0 + dy * t
                d = ((x - px) ** 2 + (y - py) ** 2) ** 0.5
                if d < best:
                    best, t_at = d, (i + t) / (n - 1)
            wid = width * (0.45 + 0.55 * math.sin(math.pi * min(1.0, t_at * 1.15)))   # ① 필압
            wid *= 1.0 + free_octave(x, y, 3, salt ^ 0x27, 0.22)
            if best > wid:
                continue
            edge = 1.0 - (best / wid) ** 1.7            # ③ 번짐 — 심에서 멀수록 옅다
            grit = free_octave(x, y, 2, salt, 1.0)      # ② 갈필 — 붓털이 갈라진 흰 결
            body = edge * max(0.0, 1.0 - dry * max(0.0, grit) * (best / max(wid, 0.6)))
            if body <= 0.02:
                continue
            v = v0 + (v1 - v0) * t_at
            a = (a0 + (a1 - a0) * t_at) * body
            prev = rows[y][x]
            if a > prev[3]:
                rows[y][x] = ink(v, a)


def arc_points(cx, cy, r, a0, a1, n=14):
    """호(弧) — 붓이 도는 자리. 발경의 쓸어치기는 직선이 아니라 원호다."""
    return [(cx + math.cos(a0 + (a1 - a0) * i / (n - 1)) * r,
             cy + math.sin(a0 + (a1 - a0) * i / (n - 1)) * r) for i in range(n)]


def sweep_rows(frame):
    """발경·쓸어치기 (SWEEP_ATTACK, 32x32 · 8프레임) — **붓의 한 획**.
    프레임이 갈수록 획이 커지고 옅어진다 (붓이 지나간 뒤 먹이 마른다). 별·마법 느낌 금지."""
    w = h = 32
    rows = blank(w, h)
    t = frame / 7.0
    r = 9.5 + t * 4.2                                   # 획이 퍼진다
    a = 236 - t * 196                                   # 그리고 마른다
    pts = arc_points(16, 17.5, r, math.pi * 1.14, math.pi * 1.92, 16)
    stroke_field(rows, w, h, pts, 3.6 - t * 1.1, 250, 176, a, a * 0.42, 0x31 + frame, dry=0.62)
    # 획의 꼬리 — 붓을 들어 올린 자리에서 먹이 몇 점 튄다 (마른 획의 끝은 갈라진다)
    for k, (ox, oy) in enumerate(((3.4, -2.2), (-3.0, 2.6), (2.0, 3.4))):
        if t > 0.15 + k * 0.12:
            tail = arc_points(16 + ox, 17.5 + oy, r * 0.86, math.pi * 1.30, math.pi * 1.64, 8)
            stroke_field(rows, w, h, tail, 1.25, 236, 150, a * 0.5, 0.0, 0x77 + frame * 3 + k, dry=0.8)
    return rows


def critical_hit_rows():
    """발경의 점획 (CRIT, 8x8) — **먹이 튄 자국**. 짧은 충격 하나.

    검은 먹만 찍으면 밤·동굴에서 사라진다 (HUD 의 양배경 가독과 같은 문제다). 튄 먹에는
    **젖은 테**가 있다 — 먹물이 종이에 닿는 순간 가장자리가 반짝인다. 그 테를 밝게 두면
    밝은 하늘에서는 먹이, 어두운 굴에서는 테가 읽힌다."""
    rows = blank(8, 8)
    core = {(3, 3), (4, 3), (3, 4), (4, 4), (2, 3), (4, 2), (5, 4), (3, 5)}
    rim = {(2, 2), (5, 2), (2, 5), (5, 5), (1, 3), (6, 4), (4, 6), (3, 1)}
    drop = {(0, 5), (6, 1), (7, 6), (1, 7)}             # 튀어 나간 방울 — 충격의 방향감
    for y in range(8):
        for x in range(8):
            if (x, y) in core:
                rows[y][x] = ink(34 + free_octave(x, y, 2, 0x4D, 14), 255)
            elif (x, y) in rim:
                rows[y][x] = ink(214, 232)              # 젖은 테 (먹이 종이에 닿은 자리)
            elif (x, y) in drop:
                rows[y][x] = ink(58, 176)
    return rows


def glow_rows():
    """검기 조각 (ELECTRIC_SPARK, 8x8) — 짧은 **마른 획**. 번개도 별도 아니다: 갈라진 붓끝이다."""
    rows = blank(8, 8)
    stroke_field(rows, 8, 8, [(1.2, 6.4), (3.4, 3.6), (6.6, 1.2)], 1.35,
                 252, 186, 246, 96, 0x59, dry=0.72)
    return rows


def glitter_rows(frame):
    """검기·강기·어검·심검의 실선 (END_ROD, 8x8 · 8프레임) — **가늘고 긴 흰 획**.
    어검은 보이지 않는 검이 지나간 자리다: 획은 실낱같고, 지나간 뒤 곧 사라진다."""
    rows = blank(8, 8)
    t = frame / 7.0
    a = 250 - t * 214                                   # 획이 사라진다 (검이 지나갔다)
    span = 1.0 - t * 0.42                               # 그리고 짧아진다 (꼬리부터 마른다)
    pts = [(0.9 + (1 - span) * 2.4, 6.8 - (1 - span) * 2.0), (3.6, 3.9), (7.0, 1.1)]
    stroke_field(rows, 8, 8, pts, 1.15 - t * 0.3, 255, 208, a, a * 0.30, 0x6B + frame, dry=0.5)
    return rows


def generic_rows(frame):
    """연기·구름 (SMOKE·CLOUD, 8x8 · 8프레임) — **먹이 물에 번진다**.

    ★ 렌더 계약 (위 머리말): 연기는 파티클 색이 **어두운 회색**이라 텍스처에 곱해진다.
      그래서 먹을 밝기로 칠하면 곱셈의 결과가 검정이 되어 연기가 사라진다.
      먹의 농담은 **알파**에 둔다 — 물에 번진 먹이 옅어지는 것과 정확히 같은 물리다."""
    rows = blank(8, 8)
    t = frame / 7.0
    r = 2.05 + t * 1.30                                 # 번져 나간다
    peak = 252 - t * 150                                # 그리고 옅어진다
    for y in range(8):
        for x in range(8):
            d = ((x - 3.5) ** 2 + (y - 3.5) ** 2) ** 0.5
            # 번짐의 가장자리는 고르지 않다 — 그러나 **가장자리만** 그렇다.
            #   노이즈를 심(心)에까지 먹이면 연기의 한복판이 뚫려 옅어진다 (빗나감·다운캐스트의
            #   신호가 흐려진다 — 연기는 '무엇이 일어났는가'를 말하는 파티클이다).
            #   그래서 노이즈의 진폭을 거리에 비례시킨다: 심은 단단하고 가장자리만 번진다.
            # 0 으로 물리는 것도 잊지 말 것 — 거리가 음수로 내려가면 (d/r)**1.45 가 **복소수**가 된다.
            d = max(0.0, d + free_octave(x, y, 2, 0x83 + frame, 0.80) * min(1.0, d / 2.0))
            # 감쇠는 **가우스**여야 하고, **모서리에 닿기 전에 죽어야** 한다.
            #   첫 판은 계단형(1-(d/r)^k)에 반경도 넓어, 8x8 안이 낮은 알파로 가득 차 **네모난 연기**가
            #   됐다 (확대 시트로 확인 — 먹이 아니라 주사위였다). 물에 번진 먹은 둥글게 죽는다.
            f = math.exp(-((d / r) ** 2) * 2.30)
            a = peak * f
            if a < 8:
                continue
            v = 252 - free_octave(x, y, 2, 0x97 + frame, 26) * 0.5   # 흰빛 유지 (틴트가 먹을 입힌다)
            rows[y][x] = ink(v, a)
    return rows


# 강기(剛氣)의 획 — 터진 먹이 뻗는 다섯 갈래. 각도는 고르지 않다 (등각으로 벌리면 그것은 별이다).
EXPLOSION_RAYS = ((0.35, 1.00), (1.42, 0.78), (2.71, 0.94), (3.86, 0.68), (5.16, 0.86))


def explosion_rows(frame):
    """강기 (EXPLOSION, 32x32 · 16프레임) — **터져 나가는 먹**.

    ★ 첫 판은 각도 노이즈로 외곽을 삐죽하게 만들었다 — 확대해 보니 **별(★)** 이었다.
      별은 마법이다. 강기는 마법이 아니라 **내력이 실린 획**이다 (규약: 별·마법 느낌 금지).
      그래서 다시 그렸다: 가운데 먹이 뭉치고(墨團), 거기서 **붓의 획 다섯**이 고르지 않은 각도로
      뻗는다. 프레임이 갈수록 획이 길어지고 먹이 옅어진다 — 터진 먹이 종이를 달리는 그림."""
    w = h = 32
    rows = blank(w, h)
    t = frame / 15.0
    r = 2.6 + t * 6.4                                   # 가운데 먹뭉치 — 번지며 커진다
    a0 = 246 - t * 226
    for y in range(h):
        for x in range(w):
            d = ((x - 15.5) ** 2 + (y - 15.5) ** 2) ** 0.5
            d = max(0.0, d + free_octave(x, y, 4, 0x2B + frame, 1.5) * min(1.0, d / 3.0))
            if d > r * 1.5:
                continue
            f = math.exp(-((d / r) ** 2) * 1.5)         # 먹뭉치도 가우스로 죽는다 (네모 방지)
            a = a0 * f
            if a < 8:
                continue
            v = 96 + 150 * f                            # 심은 희고 가장자리로 갈수록 먹이 진다
            rows[y][x] = ink(v, a)
    # 뻗는 획 다섯 — 먹뭉치에서 밖으로. 프레임이 갈수록 길고 가늘고 옅어진다
    reach = 4.0 + t * 11.5
    for i, (ang, scale) in enumerate(EXPLOSION_RAYS):
        L = reach * scale
        bend = 0.22 * math.sin(ang * 2.0 + 1.3)         # 획은 곧지 않다 (붓은 휜다)
        pts = [(15.5 + math.cos(ang + bend * k / 3) * (L * k / 3),
                15.5 + math.sin(ang + bend * k / 3) * (L * k / 3)) for k in range(4)]
        stroke_field(rows, w, h, pts, 3.0 - t * 1.5, 250, 130,
                     a0 * 0.92, a0 * 0.18, 0x91 + frame * 5 + i, dry=0.66)
    return rows


def flash_rows():
    """어검·심검의 발현 (FLASH, 32x32) — 획이 아니라 **빛무리**.
    보이지 않는 검이 서는 순간의 흰 기운. 아주 옅게 — 화면을 때리면 그것은 마법이다."""
    w = h = 32
    rows = blank(w, h)
    for y in range(h):
        for x in range(w):
            d = ((x - 15.5) ** 2 + (y - 15.5) ** 2) ** 0.5
            if d > 15.5:
                continue
            f = max(0.0, 1.0 - d / 15.5)
            a = 96 * f ** 2.3                            # 가운데만 겨우 밝다
            if 8.0 < d < 10.4:
                a += 30 * (1.0 - abs(d - 9.2) / 1.2)     # 붓이 한 바퀴 돈 자국 (옅은 테)
            a *= 1.0 + free_octave(x, y, 4, 0xC1, 0.22)
            if a < 4:
                continue
            rows[y][x] = ink(252, a)
    return rows


# ─── 쇠붙이·풀·불 — 남은 자재들 ─────────────────────────────────────────────
# 알파가 있는 블록(철창·사슬)은 **바닐라의 실루엣을 지켜야 한다.** 모델이 그 알파를 전제로 UV를 잡기
# 때문이다 (살대 자리를 옮기면 창살이 허공에 뜬다). 그래서 실루엣은 1.21.11 client jar 에서 읽어
# 그대로 두고, 그 안의 **픽셀만** 다시 칠한다 — 형(形)은 바닐라의 계약, 색(色)은 우리 것.
IRON_BARS_MASK = [
    "..##...##...##..", "..##...##...##..", "..#######...##..", "..#######...##..",
    "..##...##...##..", "..##...##...##..", "..##...##...##..", "####...##...####",
    "####...##...####", "..##...##...##..", "..##...##...##..", "..##...##...##..",
    "..##...#######..", "..##...#######..", "..##...##...##..", "..##...##...##..",
]
IRON_CHAIN_MASK = [
    "...#.#..........", "######..........", "#.#.............", "######..........",
    "...#.#..........", "...#.#..........", "######..........", "#.#.............",
    "#.#.............", "######..........", "...#.#..........", "...#.#..........",
    "######..........", "#.#.............", "######..........", "...#.#..........",
]


def iron_rows(mask, salt=0x2D):
    """철창(鐵窓)·사슬 — 두들겨 편 쇠. 빛은 좌상단: 살대의 왼쪽 모가 밝고 오른쪽이 그늘이다.
    (관아 옥사의 창살, 등롱을 매단 사슬 — 둘 다 무협의 쇠다)"""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            if mask[y][x] != "#":
                row.append(T)
                continue
            left = x == 0 or mask[y][x - 1] != "#"          # 살대의 왼쪽 모 — 빛을 받는다
            right = x == 15 or mask[y][x + 1] != "#"        # 오른쪽 모 — 그늘
            v = 4.4 + (1.9 if left else 0.0) - (1.5 if right else 0.0)
            v += octave(x, y, 1, salt, 0.8)                 # 망치 자국
            if h32(x, y, salt ^ 0x51) % 9 == 0:
                v -= 1.1                                    # 녹슬어 패인 자리
            row.append(step(IRON_SHADES, v))
        rows.append(row)
    return rows


def moss_rows():
    """이끼 — 담 밑·물가에 앉은 것. 색이 아니라 **농담**이다 (수묵 규약: 초록을 쓰지 않는다).
    이끼는 덩이져 자란다 — 보로노이 덩이에 잔털을 얹는다."""
    shades = ramp((42, 44, 41, 255), (104, 107, 100, 255), 9)     # 채도 ≤ 7 — 거의 무채색
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            d1, d2, ident, _ = wrapped_cells(x, y, 4, 0x6B)
            v = 5.0 + cell_rand(ident, 0x8F) * 1.5          # 덩이마다 다른 깊이
            v += octave(x, y, 1, 0xA3, 1.35)                # 잔털 (이끼의 결)
            if d2 - d1 < 0.6:
                v -= 1.6                                    # 덩이 사이 그늘
            row.append(step(shades, v))
        rows.append(row)
    return rows


def lectern_rows(part):
    """서안(書案) — 장부·비급을 펴 놓는 경상. 관아·표국·문파 서고에 선다."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            if part == "top":                               # 상판 — 널을 짜 맞춘 면
                y0, y1, seed, tone = next(b for b in PLANK_BOARDS if b[0] <= y <= b[1])
                v = 5.6 + tone + wood_grain(x, y, seed ^ 0x2A, amp=1.0)
                if y == y0:
                    v -= 2.0
            elif part == "front":                           # 앞면 — 책을 받치는 턱
                v = 5.2 + wood_grain(x, y, 0x4C, amp=1.1)
                if y in (2, 12) and dash(x, "1111011110111101"):
                    v -= 2.2                                # 가로 살 (책턱)
                elif y in (3, 13):
                    v += 0.8
            elif part == "sides":                           # 옆면 — 세로 결
                v = 5.0 + wood_grain(x, y, 0x6E, vertical=True, amp=1.15)
                if x in (2, 13):
                    v -= 1.6                                # 다리의 모
            else:                                           # base — 받침 (무겁고 어둡다)
                v = 4.4 + wood_grain(x, y, 0x8A, amp=1.5)
                if y in (0, 15):
                    v -= 1.8                                # 받침의 모 (바닥에 닿는 자리)
                elif y in (1, 14):
                    v += 1.2                                # 모 안쪽 — 빛을 받는다
            row.append(step(DARK_WOOD, v))
        rows.append(row)
    return rows


def crafting_table_rows(part):
    """목공대(木工臺) — 대장간·공방의 작업대. 상판은 연장에 파이고, 옆면엔 연장이 걸린다."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            if part == "top":
                # 상판도 **널을 짜 맞춘 면**이다. 첫 판은 결만 있어 장 안에 강한 가로 경계가 없었고,
                # 그래서 랩 경계가 유일한 강한 경계가 되어 이음매가 터졌다 (1.45 — 위반).
                y0, y1, seed, tone = next(b for b in PLANK_BOARDS if b[0] <= y <= b[1])
                v = 5.4 + tone + wood_grain(x, y, seed ^ 0x35, amp=1.1)
                if y == y0:
                    v -= 2.2                                # 널 사이 틈
                elif y == y0 + 1:
                    v += 0.8
                if h32(x, y, 0x59) % 13 == 0:
                    v -= 1.8                                # 끌·칼이 파고든 자국 (점 — 선이 아니다)
            elif part == "front":
                v = 5.0 + wood_grain(x, y, 0x71, vertical=True, amp=1.0)
                if 3 <= x <= 12 and y in (4, 10) and dash(x, "1101111011110110"):
                    v -= 2.3                                # 걸어 둔 연장 (가로로 건 자귀·끌)
                elif 3 <= x <= 12 and y in (5, 11):
                    v += 0.9
            else:                                           # side
                v = 5.0 + wood_grain(x, y, 0x93, vertical=True, amp=1.0)
                if x in (4, 11) and dash(y, "1110110111011010"):
                    v -= 1.9                                # 세로 버팀목
            row.append(step(SPRUCE_WOOD, v))
        rows.append(row)
    return rows


EMBER = (168, 92, 44, 255)          # 잉걸 — 등롱과 같은 계열의 난색 (불은 채색이 허락된다)
EMBER_HI = (208, 138, 72, 255)


def campfire_log_rows(lit=False):
    """모닥불 장작 — 산채·객잔 마당의 불자리. lit=True 면 밑동에 잉걸이 산다.
    불빛은 등롱과 같은 계열의 난색이다 (수묵 규약의 예외: 등롱·불)."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            v = 4.0 + wood_grain(x, y, 0x4D, amp=1.2)
            char = smooth_octave(x, y, 4, 0x77, 1.0)
            if char > 0.15:
                v -= 2.2 * min(1.0, (char - 0.15) * 1.8)    # 그을려 숯이 된 자리
            px = step(DARK_WOOD, v)
            if lit and h32(x, y, 0x95) % 11 == 0 and char > 0.0:
                px = EMBER_HI if h32(x, y, 0xB1) % 3 == 0 else EMBER   # 잉걸 (숯 사이로 붉게)
            row.append(px)
        rows.append(row)
    return rows


def snow_rows():
    """눈 덮인 옆면 — 겨울 산길. 흰빛이되 죽은 흰색은 아니다 (그늘이 있어야 눈이다)."""
    shades = ramp((168, 173, 180, 255), (250, 252, 254, 255), 9)
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            # 눈은 흰색 한 장이 아니다 — 바람에 밀린 두둑과 그늘진 골이 있다 (진폭을 죽이면 평면이 된다)
            v = (4.8 + smooth_octave(x, y, 8, 0xD3, 1.85) + smooth_octave(x, y, 4, 0xE7, 1.05)
                 + octave(x, y, 1, 0xF9, 0.55))
            if h32(x, y, 0x2B) % 17 == 0:
                v -= 1.5                                    # 눈이 패여 그늘이 앉은 점
            row.append(step(shades, v))
        rows.append(row)
    return rows


# ─── 기물 두 점 — 블록이되 **블록 엔티티**라 텍스처가 entity/ 아래 산다 ───
# DECORATED_POT 21회 · CHEST 14회 — 조성 팔레트의 상위권인데 block/ 아래에 텍스처가 없다
# (bakedmodel 이 아니라 블록 엔티티 렌더러가 그린다). 경로는 client jar 로 확인했다:
#   entity/decorated_pot/decorated_pot_base.png (32x32) · decorated_pot_side.png (16x16)
#   entity/chest/normal.png (64x64) · normal_left.png · normal_right.png (쌍궤)
# ★ UV 를 모르는 면에는 **무늬를 그리지 않는다.** 모델이 어느 조각을 잡을지 모르는 채로 문양을 얹으면
#   문양이 뚜껑 뒤나 바닥에 가서 앉는다. 그래서 이 둘은 '어느 조각을 잘라 내도 옳은 면'으로 그린다:
#   도기는 **물레 자국이 도는 유약면**, 궤는 **결이 흐르는 낡은 나무**. 어디를 잘라도 도기이고 나무다.
POT_GLAZE = ramp((58, 54, 51, 255), (158, 149, 141, 255), 10)   # 먹빛 유약 (청화 아님 — 수묵 규약)
CHEST_WOOD = ramp((68, 59, 48, 255), (158, 138, 113, 255), 9)    # 궤 — 오래 쓴 나무


def pottery_rows(w, h):
    """도기(陶器) — 술단지·약단지·쌀독. 물레에 돌린 자국이 가로로 감기고, 유약이 흘러 고인다."""
    rows = []
    for y in range(h):
        row = []
        for x in range(w):
            v = 5.6 + free_octave(x, y, 6, 0x2D, 1.15) + free_octave(x, y, 3, 0x51, 0.55)
            v += math.sin(y * 1.15 + free_octave(x, y, 8, 0x73, 1.4)) * 0.55   # 물레 자국 (가로로 감긴다)
            v += octave(x % 16, y % 16, 1, 0x9B, 0.30)                          # 흙의 입자
            if h32(x, y, 0xB7) % 29 == 0:
                v += 1.4                                                        # 유약이 뭉친 방울
            row.append(step(POT_GLAZE, v))
        rows.append(row)
    return rows


def chest_rows(w, h):
    """궤(櫃) — 나무 궤짝. 널의 결이 가로로 흐르고, 오래 쓴 자리가 닳아 밝다."""
    rows = []
    for y in range(h):
        row = []
        for x in range(w):
            v = 5.0 + wood_grain(x % 16, y % 16, 0x3B, amp=1.2)
            v += free_octave(x, y, 8, 0x67, 0.85)          # 널마다 다른 볕 (큰 얼룩)
            if y % 8 == 0:
                v -= 1.7                                   # 널과 널 사이 (가로 이음)
            elif y % 8 == 1:
                v += 0.6                                   # 이음 아래 — 빛 받는 모
            if h32(x, y, 0xC3) % 41 == 0:
                v -= 2.0                                   # 못 (드문드문)
            row.append(step(CHEST_WOOD, v))
        rows.append(row)
    return rows


def write_prop_textures() -> int:
    """블록 엔티티 기물 — 항아리·궤 (entity/ 아래 살지만 세계에 서는 것은 블록이다)."""
    out = {
        "decorated_pot/decorated_pot_base": pottery_rows(32, 32),
        "decorated_pot/decorated_pot_side": pottery_rows(16, 16),
        "chest/normal": chest_rows(64, 64),
        "chest/normal_left": chest_rows(64, 64),
        "chest/normal_right": chest_rows(64, 64),
    }
    for name, rows in out.items():
        write_png(ENTITY_DIR / f"{name}.png", rows)
    return len(out)


def soul_rows(frame):
    """은신·암살의 그림자 (SOUL, 16x16 · 11프레임) — 귀식술·무영비수.

    다른 획은 전부 **기(氣)** 다 — 밝다. 이것만 **없음**이다: 그림자는 빛이 아니라 빛의 부재다.
    (config/skill_motion.yml 이 이 파티클에 붙인 뜻이 그러하다: "기가 아니라 없음")
    그래서 홀로 먹빛으로 그린다 — 소울 파티클은 흰색으로 곱해지므로 텍스처의 어둠이 그대로 산다.
    다만 밤에 아주 사라지지는 않게 **옅은 테**를 남긴다 (밤의 암살자도 형(形)은 있다)."""
    rows = blank(16, 16)
    t = frame / 10.0
    a = 232 - t * 200                                   # 스미듯 사라진다
    lift = t * 3.2                                      # 그림자가 위로 풀린다
    pts = [(8.4, 14.2 - lift), (7.4, 10.6 - lift), (8.6, 7.2 - lift), (7.8, 4.4 - lift * 1.3)]
    stroke_field(rows, 16, 16, pts, 2.9 - t * 1.4, 40, 96, a, a * 0.25, 0xA7 + frame, dry=0.58)
    # 옅은 테 — 그림자의 가장자리는 아주 조금 밝다 (어둠 위에서도 형이 남는다).
    #   HUD 의 양배경 가독과 같은 문제다: 먹만 찍으면 밤·동굴에서 **아무것도 일어나지 않은 것**이 된다.
    #   은신은 '보이지 않음'이 아니라 '겨우 보임'이라야 한다 — 안 보이면 그건 신호가 아니다.
    for y in range(16):
        for x in range(16):
            if rows[y][x][3] > 8:
                continue
            near = any(0 <= x + dx < 16 and 0 <= y + dy < 16 and rows[y + dy][x + dx][3] > 70
                       for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1),
                                      (1, 1), (-1, -1), (1, -1), (-1, 1)))
            if near:
                rows[y][x] = ink(208, a * 0.55)
    return rows


def write_particle_textures() -> int:
    """획층 — 바닐라 파티클 텍스처 전역 치환 (엔진 불변: SkillListener 는 한 줄도 안 고친다).

    ※ ash(독·취기)는 바닐라가 generic_0 를 쓴다 (particles/ash.json) — 따로 그릴 것이 없다.
      item(무기 파편)은 아이템 텍스처를 쓰므로 이미 우리 것이다. crimson_spore 도 generic_0 다."""
    out = {"critical_hit": critical_hit_rows(), "glow": glow_rows(), "flash": flash_rows()}
    for i in range(8):
        out[f"sweep_{i}"] = sweep_rows(i)
        out[f"glitter_{i}"] = glitter_rows(i)
        out[f"generic_{i}"] = generic_rows(i)
    for i in range(16):
        out[f"explosion_{i}"] = explosion_rows(i)
    for i in range(11):
        out[f"soul_{i}"] = soul_rows(i)
    for name, rows in out.items():
        write_png(PARTICLE_DIR / f"{name}.png", rows)
    return len(out)


# ═══════════════════════════════════════════════════════════════════════════
# 자재층 2차 — **축 ⑪ 이 짚어낸 구멍**을 메운다 (2026-07)
#
# 1차 자재층은 '조성기가 많이 쓰는 블록'을 손으로 골라 덮었다 (11.4% → 84.7%).
# 그 다음 자리를 고르는 데는 자가 필요했다: texture_audit 축 ⑪ 이 빈도 × 면적 × 불투명도로
# **남은 구멍을 면적 순으로 세워 준다**. 아래 순서가 그 순서다 — 손이 아니라 자가 골랐다.
#
# 그리지 않는 것과 그 이유 (등록제: 안 그리는 것도 등록한다):
#   · 풀·잎·덩굴·수수깡 (short_grass·fern·*_leaves·vine·lily_pad·sugar_cane·tall_grass)
#     → **바이옴 컬러맵이 초록을 곱한다**. 회색조를 칠해도 초록이 된다. 앞선 작업자의 보류 판정 유지.
#   · 물·용암 (water_still·lava_still) → 유체는 특수 렌더러 + **애니메이션 프레임 시트**
#     (16x512·16x320) + 물은 바이옴 틴트까지 곱한다. 별도 증분.
#   · 불 (campfire_fire 등) → 애니메이션 프레임 시트 (16x128). 별도 증분.
#   ※ 꽃은 컬러맵 대상이 **아니다** (틴트되는 것은 풀·잎·물뿐) → 그린다.
# ═══════════════════════════════════════════════════════════════════════════
def grain_rows(shades, salt, clump=1.1, grit=0.85, dune=0.0):
    """고운 알갱이 면 — 모래·마른 흙·눈. 저주파 얼룩(뭉침) + 고주파 알갱이.

    ※ 눈처럼 **원래 매끈한 면**도 명암은 있어야 한다: 진폭을 낮추면 색 3·명암차 22 로
      '평면·밋밋' 이중 위반이 난다 (검수가 잡았다). 매끈함은 **좁은 팔레트**로 내는 것이지
      진폭을 죽여서 내는 것이 아니다 — 눈의 램프는 168~246 이라 계단을 다 밟아도 여전히 희다.
      dune = 저주파 물결 한 겹 (바람이 쓸어 놓은 결. 모래·눈이 평평한 적은 없다)."""
    def v(x, y):
        # 결의 물결은 **랩 안전**해야 한다: 파장이 16을 정수로 나눠야 이어 붙였을 때 이음매가 없다
        # (sin((x + 0.6y)*0.5) 처럼 무리한 주기를 쓰면 이음매 2.25 로 튄다 — 검수가 잡았다).
        # 위상만 smooth_octave 로 흔든다 (그것도 랩 안전이라 결이 규칙적 줄무늬가 되지 않는다).
        phase = 2 * math.pi * (x + 2 * y) / 16 + smooth_octave(x, y, 8, salt + 2, 1.4)
        return (4.0 + dune * math.sin(phase) + smooth_octave(x, y, 4, salt, clump)
                + octave(x, y, 1, salt + 1, grit))
    return [[step(shades, v(x, y)) for x in range(16)] for y in range(16)]


def ice_rows(shades, salt, cracks=3):
    """얼음 — 매끈한 면. 얼음의 정체는 얼룩이 아니라 **금**이다 (실금 몇 줄이 없으면 그냥 회색 판)."""
    g = [[step(shades, 5.0 + smooth_octave(x, y, 8, salt, 1.3)) for x in range(16)]
         for y in range(16)]
    for i in range(cracks):                      # 실금 — 대각으로 흐르되 점선(직선 금지)
        x, y = h32(i, salt, 0x1F) % 16, h32(i, salt, 0x2F) % 16
        dx, dy = (1, 1) if i % 2 else (1, -1)
        for t in range(10):
            if h32(t, i, salt) % 5 == 0:
                continue                         # 끊긴 금 (이어진 직선은 무늬가 된다)
            g[(y + dy * t) % 16][(x + dx * t) % 16] = step(shades, 1.4 + octave(t, i, 1, salt, 0.6))
    return g


def flower_rows(petal, cy=5.0, r=2.7, heart=None):
    """들꽃 — 줄기 한 획 + 잎 + 꽃. 수묵의 꽃은 **획**이지 색면이 아니다 (채도는 아주 옅게).
    꽃은 컬러맵 틴트 대상이 아니므로 우리가 칠한 값이 그대로 선다 (풀·잎과 갈리는 지점)."""
    g = [[T] * 16 for _ in range(16)]
    stem = ramp((42, 50, 40, 255), (98, 110, 88, 255), 4)
    for y in range(int(cy) + 1, 15):
        x = 8 + (1 if y in (10, 13) else 0)      # 줄기의 흔들림 — 곧은 선은 사람이 그은 선이다
        g[y][x] = step(stem, 2.6 - (y - cy) * 0.07)
        g[y][x - 1] = step(stem, 0.7)            # 줄기 그늘 1px (한 획도 입체다)
    for ly, sx in ((10, -1), (12, 1)):           # 잎 두 장 — 좌우로 뻗는다
        for t in range(1, 4):
            g[ly + (t // 3)][8 + sx * t] = step(stem, 2.2 - t * 0.35)
    for y in range(16):                          # 꽃송이
        for x in range(16):
            d = (((x - 7.5) ** 2) + ((y - cy) ** 2) * 1.25) ** 0.5
            if d <= r:
                g[y][x] = step(petal, 3.3 - d * 0.75 + octave(x, y, 1, 0x5B, 0.4))
    if heart:                                    # 꽃심 한 점
        g[int(cy)][8] = heart
        g[int(cy)][7] = heart
    return g


def cobweb_rows():
    """거미줄 — 방사 실 + 나선. 폐사당·산채 구석. 대부분 투명(컷아웃)이라 실이 곧 그림이다."""
    thread = ramp((92, 90, 86, 255), (228, 226, 220, 255), 5)
    g = [[T] * 16 for _ in range(16)]
    for a in range(8):                           # 방사 실 8줄 — 모서리와 변 중앙으로
        dx, dy = ((1, 0), (1, 1), (0, 1), (-1, 1), (-1, 0), (-1, -1), (0, -1), (1, -1))[a]
        for t in range(1, 9):
            x, y = 7 + dx * t, 7 + dy * t
            if 0 <= x < 16 and 0 <= y < 16:
                g[y][x] = step(thread, 3.4 - t * 0.18 + octave(x, y, 1, 0x2B, 0.5))
    for r in (3, 6):                             # 나선 두 겹 — 방사 실을 잇는다
        for a in range(0, 360, 12):
            x = 7 + round(r * math.cos(math.radians(a)))
            y = 7 + round(r * math.sin(math.radians(a)))
            if 0 <= x < 16 and 0 <= y < 16 and h32(x, y, 0x77) % 7:
                g[y][x] = step(thread, 2.6 + octave(x, y, 1, 0x3D, 0.6))
    g[7][7] = step(thread, 4.0)
    return g


def lichen_rows():
    """야광이끼 — 바위에 번진 냉광 얼룩 (컷아웃). 빛나되 초록이 아니라 **엷은 백록**이다.
    번짐의 가장자리는 어둡고 속은 빛난다 — 그 기울기가 없으면 색 3짜리 평면 얼룩이 된다."""
    shades = ramp((70, 88, 80, 255), (208, 228, 216, 255), 6)
    g = [[T] * 16 for _ in range(16)]
    for y in range(16):
        for x in range(16):
            v = smooth_octave(x, y, 4, 0x8F, 1.7) + octave(x, y, 1, 0x91, 0.55)
            if v > 0.3:
                g[y][x] = step(shades, (v - 0.3) * 3.4)     # 가장자리 0 → 속 5 (계단을 다 밟는다)
    return g


def ladder_rows():
    """사다리 — 두 기둥 + 가로장 (컷아웃). 실루엣이 곧 계약이다 (바닐라와 같은 자리에 발을 딛는다)."""
    wood = ramp((58, 48, 38, 255), (140, 118, 92, 255), 5)
    g = [[T] * 16 for _ in range(16)]
    for y in range(16):
        for x in (2, 3, 12, 13):                 # 두 기둥
            g[y][x] = step(wood, 2.8 + octave(x, y, 1, 0x21, 0.7) - (x in (3, 13)) * 0.9)
    for y in (2, 7, 12):                         # 가로장 셋
        for x in range(3, 13):
            g[y][x] = step(wood, 3.2 + octave(x, y, 1, 0x23, 0.6))
            g[y + 1][x] = step(wood, 1.2)        # 가로장 그늘
    return g


def trapdoor_rows(shades, salt):
    """널문 — 널 세 쪽 + 정첩 두 점 (컷아웃 아님: 꽉 찬 판)."""
    g = plank_rows(shades, salt)
    iron = ramp((44, 42, 40, 255), (128, 124, 118, 255), 4)
    for y in range(16):                          # 널을 가르는 골 두 줄
        for x in (5, 10):
            g[y][x] = step(shades, 0.6)
    for cy in (2, 12):                           # 정첩 — 쇠 띠
        for x in range(1, 6):
            g[cy][x] = step(iron, 2.6 + octave(x, cy, 1, salt, 0.5))
            g[cy + 1][x] = step(iron, 1.0)
    return g


def fence_rows(shades, salt):
    """대울타리 — 세로 쪽 (bamboo_fence 는 제 텍스처를 갖는 드문 울타리다)."""
    g = [[step(shades, 3.0 + octave(x, y, 1, salt, 0.6)) for x in range(16)] for y in range(16)]
    for x in range(16):
        if x % 4 == 3:                           # 쪽과 쪽 사이 골
            for y in range(16):
                g[y][x] = step(shades, 0.8 + octave(x, y, 1, salt + 1, 0.4))
    for y in (4, 11):                            # 가로로 엮은 끈
        for x in range(16):
            g[y][x] = step(shades, 1.6 + octave(x, y, 1, salt + 2, 0.5))
    return g


def metal_rows(shades, salt, rivets=()):
    """쇠 면 — 모루·깔때기·화덕 앞판. 두들긴 쇠는 평면이 아니다 (망치 자국)."""
    g = [[step(shades, 3.4 + smooth_octave(x, y, 4, salt, 1.1) + octave(x, y, 1, salt + 1, 0.5))
          for x in range(16)] for y in range(16)]
    for (rx, ry) in rivets:                      # 못머리 — 좌상 광, 우하 그늘
        g[ry][rx] = step(shades, 5.6)
        if ry + 1 < 16:
            g[ry + 1][rx] = step(shades, 0.8)
    return g


def funnel_rows(shades, salt):
    """깔때기 속 — 가장자리는 빛을 받고 가운데 아가리로 갈수록 어둠에 잠긴다.
    '어두운 면'을 한 톤으로 채우면 그것은 어둠이 아니라 **구멍**이다 (경락도 창에서 배운 것)."""
    def v(x, y):
        rim = max(abs(x - 7.5), abs(y - 7.5))         # 0.5(가운데) ~ 7.5(가장자리)
        return 5.6 - (7.5 - rim) * 0.72 + octave(x, y, 1, salt, 0.6)
    return [[step(shades, v(x, y)) for x in range(16)] for y in range(16)]


def hearth_front_rows(lit=False):
    """화덕 앞판 — 아궁이 아가리. 불 든 아궁이만 붉다 (채도는 의미에만: 불은 의미다)."""
    stone = ramp((60, 58, 55, 255), (146, 143, 137, 255), 8)
    g = stone_rows(stone, 0x3B, amp=1.1)
    mouth = ramp((18, 16, 15, 255), (58, 54, 50, 255), 4) if not lit else \
        ramp((92, 36, 22, 255), (232, 152, 74, 255), 5)
    for y in range(6, 13):                       # 아궁이 — 아치 아가리
        half = 5 - abs(y - 9) // 2
        for x in range(8 - half, 8 + half):
            d = abs(x - 7.5) / max(1, half)
            g[y][x] = step(mouth, (3.6 if lit else 2.2) - d * 1.4 - (y - 9) * 0.25
                           + octave(x, y, 1, 0x5D, 0.6))
    for x in range(3, 13):                       # 인방(引枋) — 아궁이 위 돌 한 줄
        g[5][x] = step(stone, 1.4)
    return g


def composter_rows(part):
    """퇴비통 — 널 세운 통 + 삭는 거름. 마을의 뒤꼍 (조성기 3회, 면적은 큰 편)."""
    wood = ramp((84, 72, 57, 255), (176, 155, 122, 255), 7)
    if part == "side":
        g = [[step(wood, 3.4 + octave(x, y, 1, 0x71, 0.8)) for x in range(16)] for y in range(16)]
        for x in range(16):
            if x % 5 == 4:                       # 세운 널의 이음
                for y in range(16):
                    g[y][x] = step(wood, 0.9 + octave(x, y, 1, 0x73, 0.4))
        for y in (1, 14):                        # 테 두 줄 (통을 조인다)
            for x in range(16):
                g[y][x] = step(wood, 1.6 + octave(x, y, 1, 0x75, 0.5))
        return g
    if part == "top":                            # 아가리 테 — 안은 비었다
        # 테는 **두께가 있는 물건**이다: 바깥 모는 빛을 받고 안쪽 모는 아가리로 떨어진다.
        # (잡티만 뿌리면 색 3·명암 22 로 '평면·밋밋' 이중 위반 — 검수가 잡아냈다.)
        g = [[T] * 16 for _ in range(16)]
        for y in range(16):
            for x in range(16):
                d = min(x, y, 15 - x, 15 - y)
                if d >= 2:
                    continue
                lit = (x <= y and x <= 15 - y) or (y <= x and y <= 15 - x)   # 좌·상 = 빛
                v = (5.2 if lit else 2.0) - d * 1.6 + octave(x, y, 1, 0x77, 0.8)
                g[y][x] = step(wood, v)
        return g
    if part == "bottom":
        return plank_rows(wood, 0x79)
    # compost / ready — 삭는 거름. ready 는 다 삭아 검고 기름지다
    dark = ramp((36, 32, 26, 255), (92, 82, 66, 255), 7) if part == "ready" else \
        ramp((52, 46, 34, 255), (124, 110, 82, 255), 7)
    g = [[step(dark, 3.6 + smooth_octave(x, y, 4, 0x7B, 1.4) + octave(x, y, 1, 0x7D, 0.9))
          for x in range(16)] for y in range(16)]
    if part == "ready":
        for i in range(6):                       # 다 삭은 표식 — 하얗게 뜬 곰팡이 몇 점
            x, y = h32(i, 0x7F) % 16, h32(i, 0x81) % 16
            g[y][x] = (150, 148, 138, 255)
    return g


def loom_rows(part):
    """베틀 — 나무 틀 + 걸린 날실. 저잣거리의 옷감집."""
    wood = ramp((90, 76, 59, 255), (182, 158, 124, 255), 7)
    if part == "top":
        return plank_rows(wood, 0x83)
    if part == "bottom":
        return plank_rows(wood, 0x85)
    g = [[step(wood, 3.2 + octave(x, y, 1, 0x87, 0.7)) for x in range(16)] for y in range(16)]
    if part == "front":                          # 앞면 — 날실이 걸렸다
        thread = ramp((150, 146, 136, 255), (226, 222, 210, 255), 4)
        for x in range(3, 13):
            if x % 2:
                continue
            for y in range(3, 13):
                g[y][x] = step(thread, 2.4 + octave(x, y, 1, 0x89, 0.7))
        for y in (2, 13):                        # 위·아래 도투마리
            for x in range(16):
                g[y][x] = step(wood, 1.2 + octave(x, y, 1, 0x8B, 0.5))
    else:                                        # side — 틀의 옆면
        for y in (3, 12):
            for x in range(16):
                g[y][x] = step(wood, 1.3 + octave(x, y, 1, 0x8D, 0.5))
    return g


def sign_rows(shades, salt, hanging=False):
    """현판(懸板)·주기(酒旗) — 64x32 (entity/signs/). 판은 나무, 글씨 자리는 비워 둔다
    (플러그인·플레이어가 글을 쓴다 — 팩이 글자를 그리면 그 위에 겹쳐 읽힌다).
    바닐라 UV: 판 = (0,0) 24x12, 기둥/막대 = (0,14) 2x14 근처. 판 밖은 투명."""
    g = [[T] * 64 for _ in range(32)]
    for y in range(12):                          # 판 — 24x12
        for x in range(24):
            v = 3.2 + octave(x, y, 1, salt, 0.7) - (y - 6) * 0.05
            if y in (0, 11) or x in (0, 23):
                v -= 1.6                         # 판의 테 — 그늘 (물건에는 모서리가 있다)
            g[y][x] = step(shades, v)
    for i in range(3):                           # 옻칠 얼룩 몇 점 (현판은 칠한 판이다)
        x, y = 3 + h32(i, salt) % 18, 2 + h32(i, salt + 1) % 8
        g[y][x] = step(shades, 1.0)
    if hanging:                                  # 매다는 사슬 두 줄 (0,14) 근방
        iron = ramp((40, 38, 36, 255), (122, 118, 112, 255), 4)
        for y in range(14, 28):
            for x in (0, 1, 12, 13):
                g[y][x] = step(iron, 2.6 - (x in (1, 13)) * 1.2 + octave(x, y, 1, salt + 2, 0.5))
    else:                                        # 세우는 기둥
        for y in range(14, 28):
            for x in range(2):
                g[y][x] = step(shades, 2.4 + octave(x, y, 1, salt + 3, 0.6))
    return g


def crop_rows(shades, stage, stages):
    """밭작물 — 자랄수록 키가 크고 짙어진다 (컷아웃).

    ※ 여기서 **빈도 대리 지표의 한계**가 보인다: 밭은 코드 한 줄이 수십 칸을 깐다.
      축 ⑪ 의 면적 순위에서 작물은 작게 잡히지만(코드 출현 1회) 눈에는 밭 한 뙈기로 보인다.
      그래서 순위와 무관하게 그린다 — 자는 순서를 정해 주는 것이지 눈을 대신하지 않는다."""
    g = [[T] * 16 for _ in range(16)]
    t = (stage + 1) / stages
    tall = int(3 + 11 * t)
    for col in (2, 7, 12):                        # 세 포기 — 이랑
        for k in range(tall):
            y, x = 15 - k, col + (1 if k % 5 == 3 else 0)
            if not (0 <= x < 16):
                continue
            # 어린 싹도 평면이 아니다: 뿌리는 그늘에 잠기고 끝은 볕을 받는다 (세로 명암)
            # + 오른쪽에 그늘 한 줄 (한 획도 입체다). 이것이 없으면 색 2·명암차 17 로 이중 위반.
            v = 1.3 + 3.6 * (k / max(1, tall - 1)) + 0.8 * t + octave(x, y, 1, 0x11, 0.7)
            g[y][x] = step(shades, v)
            if x + 1 < 16:
                g[y][x + 1] = step(shades, v - 2.4)                  # 줄기 그늘
            if k >= tall - 2 and t > 0.6 and x - 1 >= 0:
                g[y][x - 1] = step(shades, 5.8 - (tall - k) * 0.5)   # 여문 이삭 (고개를 숙인다)
    return g


def sprig_rows(shades, salt, fronds=3, lean=0.35, top_bias=0.0):
    """줄기 초목 — 마른 덤불·묘목·대나무·수초 (컷아웃). 몇 획으로 서는 것들."""
    g = [[T] * 16 for _ in range(16)]
    for f in range(fronds):
        x = 3 + f * (10 // max(1, fronds - 1) if fronds > 1 else 0)
        sway = -1 if f % 2 else 1
        for k in range(14):
            y = 15 - k
            xx = int(x + sway * lean * k)
            if not (0 <= xx < 16):
                continue
            v = 1.2 + 2.8 * (k / 13) * (1 + top_bias) + octave(xx, y, 1, salt, 0.7)
            g[y][xx] = step(shades, v)
            if k in (5, 9, 12) and 0 <= xx + sway < 16:       # 곁가지
                g[y][xx + sway] = step(shades, v - 1.2)
    return g


def mushroom_rows(cap, stem):
    """버섯 — 갓 + 대 (컷아웃). 폐사당 그늘의 살림."""
    g = [[T] * 16 for _ in range(16)]
    for y in range(6, 11):                        # 대
        for x in (7, 8):
            g[y][x] = step(stem, 2.6 - (x == 8) * 1.0 + octave(x, y, 1, 0x2B, 0.4))
    for y in range(2, 7):                         # 갓 — 반원
        half = 4 - abs(y - 4)
        for x in range(7 - half, 9 + half):
            d = abs(x - 7.5) / max(1, half + 1)
            g[y][x] = step(cap, 3.6 - d * 1.6 - (y - 4) * 0.35 + octave(x, y, 1, 0x2D, 0.5))
    return g


def pot_rows():
    """화분 — 구운 흙.

    ★ 화분은 **재질 텍스처**이지 그림이 아니다. flower_pot.json 을 열어 보면 모델이 읽는 uv는
      x5..11 · y5..16 뿐이고 나머지는 안 쓴다 — 아가리 테를 y0..2 에 가로로 그으면 그 획은
      **모델이 읽지도 않는 죽은 픽셀**이면서 랩 경계만 어긋나게 한다 (이음매 1.45 로 울었다).
      화분의 생김새는 **모델**이 만든다. 텍스처가 할 일은 '구운 흙'으로 보이는 것뿐이다.
      (팩의 규율 그대로: "UV를 모르는 면에는 무늬를 그리지 않는다".)
    물레 자국(가로 결)만 옅게 — 도기는 돌려 빚은 것이라 결이 가로로 돈다."""
    clay = ramp((82, 64, 52, 255), (172, 144, 120, 255), 8)

    def v(x, y):
        wheel = 0.55 * math.sin(2 * math.pi * y / 4)        # 랩 안전 (파장 4 가 16 을 나눈다)
        return (4.0 + wheel + smooth_octave(x, y, 4, 0x31, 1.2)
                + octave(x, y, 1, 0x33, 0.7))

    return [[step(clay, v(x, y)) for x in range(16)] for y in range(16)]


def scaffold_rows(part):
    """비계(飛階) — 대나무를 엮어 세운 발판. 목수·미장이 쓰는 임시 구조."""
    bam = ramp((122, 112, 90, 255), (208, 195, 165, 255), 8)
    if part == "top":                             # 위에서 본 발 — 격자
        g = [[step(bam, 3.4 + octave(x, y, 1, 0x35, 0.7)) for x in range(16)] for y in range(16)]
        for i in range(16):
            for j in (0, 5, 10, 15):
                g[i][j] = step(bam, 1.2 + octave(j, i, 1, 0x37, 0.5))
                g[j][i] = step(bam, 1.6 + octave(i, j, 1, 0x39, 0.5))
        return g
    g = [[T] * 16 for _ in range(16)]             # 옆·밑 — 기둥과 가로대 (컷아웃)
    for x in (1, 2, 13, 14):
        for y in range(16):
            g[y][x] = step(bam, 3.0 - (x in (2, 14)) * 1.2 + octave(x, y, 1, 0x3B, 0.6))
    for y in ((3, 11) if part == "side" else (7, 8)):
        for x in range(16):
            g[y][x] = step(bam, 3.4 + octave(x, y, 1, 0x3D, 0.6))
    return g


def brewing_rows(part):
    """약탕기 — 약재를 달이는 자리 (의방). base = 받침 · stand = 세운 쇠대."""
    iron = ramp((44, 42, 40, 255), (150, 146, 140, 255), 7)
    if part == "base":
        return stone_rows(ramp((66, 62, 58, 255), (142, 137, 130, 255), 8), 0x3F, amp=1.0)
    g = [[T] * 16 for _ in range(16)]
    for x in (7, 8):                              # 세운 대 — 위는 볕, 아래는 그늘 (세로 명암)
        for y in range(2, 15):
            v = 5.4 - (x == 8) * 2.2 - (y - 2) * 0.22 + octave(x, y, 1, 0x41, 0.6)
            g[y][x] = step(iron, v)
    for k, y in enumerate((2, 3)):                # 걸이 팔 — 마루(광)와 밑(그늘) 두 줄
        for x in range(3, 13):
            g[y][x] = step(iron, (5.0 if k == 0 else 1.6) + octave(x, y, 1, 0x43, 0.7))
    for x in (3, 12):                             # 매단 고리 — 아래로 갈수록 어둡다
        for y in range(4, 8):
            g[y][x] = step(iron, 4.2 - (y - 4) * 0.9 + octave(x, y, 1, 0x45, 0.6))
    return g


def torch_rows(lit=True, candle=False):
    """횃불·초 — 자루 + 불씨 (컷아웃). 불은 의미다 → 채도 허용 (자재_규약).

    ★ UV 계약 (바닐라 실측 — 짐작 금지. 여기를 틀리면 모델이 빈 자리를 읽어 **물건이 사라진다**):
        torch.png  : x7..8,  y6..15  (가운데 세로 두 칸)
        candle.png : x0..1,  y5..15  (**왼쪽 변에 딱 붙어 있다** — 가운데가 아니다)
      촛대가 변에 붙어 있으므로 랩 경계 차이가 필연적으로 커진다 → texture_audit 의 이음매 축은
      이 계약을 지키는 한 반드시 운다. 그래서 우는 쪽을 고쳤다 (SEAM_FACES 주석): 계약이 지표를 이긴다."""
    wood = ramp((54, 44, 35, 255), (150, 128, 98, 255), 6)
    wax = ramp((150, 143, 126, 255), (242, 238, 224, 255), 6)
    fire = ramp((104, 42, 22, 255), (246, 200, 116, 255), 5)
    body, x0 = (wax, 0) if candle else (wood, 7)
    top = 5 if candle else 6
    g = [[T] * 16 for _ in range(16)]
    for y in range(top, 16):
        for x in (x0, x0 + 1):
            # 자루도 입체다: 왼쪽이 빛, 오른쪽이 그늘. 아래로 갈수록 그늘에 잠긴다
            v = 4.4 - (x == x0 + 1) * 2.0 - (y - top) * 0.12 + octave(x, y, 1, 0x47, 0.6)
            g[y][x] = step(body, v)
    if lit:                                       # 심지의 불씨 — 위로 갈수록 밝다
        for k, y in enumerate(range(top, top + 2)):
            for x in (x0, x0 + 1):
                g[y][x] = step(fire, 3.8 - k * 1.4 - (x == x0 + 1) * 0.8
                               + octave(x, y, 1, 0x49, 0.5))
        g[top][x0] = step(fire, 4.6)
    else:
        g[top][x0] = step(body, 0.5)              # 그을린 심지 (안 켠 초)
        g[top][x0 + 1] = step(body, 1.4)
    return g


def hook_rows(wire=False):
    """덫줄 갈고리·덫줄 — 산채의 함정 (컷아웃). 한 획이라도 명암은 있어야 한다."""
    iron = ramp((40, 38, 36, 255), (156, 152, 146, 255), 5)
    g = [[T] * 16 for _ in range(16)]
    if wire:
        for x in range(16):                       # 팽팽한 줄 한 가닥
            g[7][x] = step(iron, 3.6 + octave(x, 7, 1, 0x4B, 0.7))
            g[8][x] = step(iron, 1.2 + octave(x, 8, 1, 0x4D, 0.5))
        return g
    for y in range(4, 12):                        # 갈고리 몸
        for x in (7, 8):
            g[y][x] = step(iron, 3.4 - (x == 8) * 1.4 + octave(x, y, 1, 0x4F, 0.5))
    for x in range(5, 11):                        # 걸이 판
        g[4][x] = step(iron, 4.0 + octave(x, 4, 1, 0x51, 0.6))
        g[5][x] = step(iron, 1.4)
    return g


def pickle_rows():
    """바다 나물 — 물가의 돌기 (컷아웃)."""
    shades = ramp((78, 92, 74, 255), (176, 192, 152, 255), 6)
    g = [[T] * 16 for _ in range(16)]
    for (cx, cy, h) in ((4, 12, 4), (8, 13, 3), (11, 11, 5)):
        for k in range(h):
            for x in (cx, cx + 1):
                g[cy - k][x] = step(shades, 1.4 + k * 0.9 - (x == cx + 1) * 0.8
                                    + octave(x, cy - k, 1, 0x53, 0.5))
    return g


def write_block_textures() -> int:
    """징발 등록부 순회 — 바닐라 경로에 16x16 덮어쓰기 (blockstate/model JSON 불요)."""
    blocks = {
        "deepslate_tiles": roof_rows(),
        "cracked_deepslate_tiles": roof_rows(cracked=True),
        # 직각 경사면용 회전 변형 (ROOF_ISOTROPY ②) — deepslate_bricks 계열 = tiles의 90도 회전판.
        # PNG 1장이 deepslate_brick_stairs/_slab/_wall 을 전부 덮는다 (tiles와 같은 문법).
        # 조성기는 동서 경사면에 DEEPSLATE_BRICK_* 를 깐다 — 그러면 네 면이 다 제 방향의 결을 갖는다.
        "deepslate_bricks": rotate90(roof_rows()),
        "cracked_deepslate_bricks": rotate90(roof_rows(cracked=True)),
        # 회벽 — 차가운 회백(R≈G≈B). 서민 벽(light_gray)은 같은 계열 한 톤 아래.
        "white_terracotta": plaster_rows((172, 174, 177, 255), (232, 233, 235, 255)),
        "light_gray_terracotta": plaster_rows((146, 148, 151, 255), (204, 205, 207, 255)),
        "glass": lattice_window_rows(),
        "glass_pane_top": pane_top_rows(),
        "bamboo_planks": bamboo_rows(),
        "lantern": lantern_rows(LANTERN_SHADES, LANTERN_CAP, LANTERN_CAP_HI),
        "soul_lantern": lantern_rows(SOUL_SHADES, SOUL_CAP, SOUL_CAP_HI),
        "chiseled_bookshelf_top": shelf_grain_rows(False),
        "chiseled_bookshelf_side": shelf_grain_rows(True),
        "chiseled_bookshelf_empty": shelf_face_rows(False),
        "chiseled_bookshelf_occupied": shelf_face_rows(True),
    }

    # ── 자재층 (2026-07) — 조성 팔레트의 상위 블록을 무협의 자재로 다시 그린다 ──
    # 순서는 **면적** 순이다 (조성기 실측 빈도 = 화면 점유). 옆에 적은 수는 조성기 사용 횟수.
    dirt = dirt_rows()
    path_top = dirt_path_top_rows()
    podzol_top = podzol_top_rows()
    blocks.update({
        # 흙·길 (COARSE_DIRT 25 · DIRT_PATH 24 · DIRT 21 · ROOTED_DIRT 10 · GRAVEL 11 · PODZOL 7)
        "dirt": dirt,
        "coarse_dirt": coarse_dirt_rows(),
        "dirt_path_top": path_top,
        "dirt_path_side": side_rows(path_top, dirt, band=1),      # 길은 15/16 높이 — 윗 한 줄만 길이다
        "rooted_dirt": rooted_dirt_rows(),
        "podzol_top": podzol_top,
        "podzol_side": side_rows(podzol_top, dirt, band=3),
        "gravel": gravel_rows(),
        "farmland": farmland_rows(),
        "farmland_moist": farmland_rows(moist=True),
        # 돌 (COBBLESTONE 17 · COBBLESTONE_WALL 15 · STONE_BRICKS 14 · POLISHED_ANDESITE 10 …)
        #   ※ 담장(WALL)·계단·반블록은 제 텍스처가 없다 — 몸 블록의 텍스처를 그대로 쓴다.
        "cobblestone": rubble_rows(COBBLE_SHADES, 4, 0x31),
        "mossy_cobblestone": rubble_rows(COBBLE_SHADES, 4, 0x31, damp=True),
        "stone_bricks": brick_rows(BRICK_SHADES),
        "cracked_stone_bricks": brick_rows(BRICK_SHADES, cracked=True),
        "mossy_stone_bricks": brick_rows(BRICK_SHADES, damp=True),
        "chiseled_stone_bricks": chiseled_brick_rows(),
        "stone": stone_rows(STONE_SHADES),
        "smooth_stone": smooth_stone_rows(),
        "smooth_stone_slab_side": smooth_stone_rows(band=True),
        "andesite": stone_rows(STONE_SHADES, 0x4F, amp=1.25),     # 자연 안산암 — 얼룩이 굵다
        "polished_andesite": polished_andesite_rows(),            # 다듬은 안산암 — 정 자국이 있다
        "tuff": stone_rows(ramp((88, 87, 83, 255), (172, 170, 163, 255), 9), 0x8D, amp=1.35),
        "deepslate": stone_rows(DEEP_SHADES, 0xB3, amp=0.9),
        # 심층암 마구리 — amp 0.75·speck 없음으로는 색 3개·명암차 15 였다 (평면·밋밋 이중 위반).
        # 검은 돌이라고 해서 얼룩이 없는 것은 아니다 — 검은 것은 밝기이지 균질함이 아니다.
        "deepslate_top": stone_rows(DEEP_SHADES, 0xC7, amp=1.3),
        "cobbled_deepslate": rubble_rows(DEEP_SHADES, 4, 0x75, mid=5.4),   # 소금은 이음매로 골랐다 (gravel 주석)
        "bricks": brick_rows(ramp((98, 90, 82, 255), (182, 172, 159, 255), 11), 0x6D),
        "mud_bricks": brick_rows(ramp((96, 88, 76, 255), (178, 165, 145, 255), 10), 0x9F),
        "packed_mud": stone_rows(ramp((66, 60, 52, 255), (136, 126, 110, 255), 9), 0xD1, amp=1.2),
        "clay": stone_rows(ramp((88, 86, 84, 255), (156, 154, 150, 255), 8), 0xE9, amp=0.8),
        "terracotta": plaster_rows((124, 118, 112, 255), (176, 169, 161, 255), hairlines=False),
        # 목재 — 판자 한 장이 판자·울타리·계단·반블록·문을 전부 덮는다
        #   (DARK_OAK_PLANKS 33 · SPRUCE_FENCE 39 · SPRUCE_PLANKS 18 · OAK_FENCE 15 · OAK_PLANKS 9)
        "dark_oak_planks": plank_rows(DARK_WOOD, 0x11),
        "spruce_planks": plank_rows(SPRUCE_WOOD, 0x33),
        "oak_planks": plank_rows(OAK_WOOD, 0x55),
        # 통나무 — 목책(산채)·귀틀·기둥 (SPRUCE_LOG 15 · OAK_LOG 11 · DARK_OAK_LOG 10)
        "spruce_log": bark_rows(SPRUCE_WOOD, 0x13, rough=1.15, knot=(11, 5)),   # 산채 목책 — 가장 거칠다
        "spruce_log_top": log_top_rows(SPRUCE_WOOD, 0x17, freq=1.78),
        "oak_log": bark_rows(OAK_WOOD, 0x2B, rough=0.9, knot=(4, 10)),
        "oak_log_top": log_top_rows(OAK_WOOD, 0x2F, freq=1.62),
        "dark_oak_log": bark_rows(DARK_WOOD, 0x43, rough=1.0, knot=(12, 12)),
        "dark_oak_log_top": log_top_rows(DARK_WOOD, 0x47, freq=2.05),
        "stripped_oak_log": stripped_rows(STRIPPED_WOOD, 0x19),
        "stripped_oak_log_top": log_top_rows(STRIPPED_WOOD, 0x1D, freq=1.55),
        "stripped_dark_oak_log": stripped_rows(ramp((66, 57, 47, 255), (140, 124, 104, 255), 9), 0x61),
        "stripped_dark_oak_log_top": log_top_rows(DARK_WOOD, 0x65, freq=1.94),
        "stripped_spruce_log": stripped_rows(SPRUCE_WOOD, 0x7F),
        "stripped_spruce_log_top": log_top_rows(SPRUCE_WOOD, 0x83, freq=1.71),
        # 매화 — 채색이 허락된 유일한 자리 (CHERRY_LOG 4 · CHERRY_LEAVES 3)
        "cherry_log": bark_rows(CHERRY_WOOD, 0x95, rough=0.8, knot=(5, 4)),
        "cherry_log_top": log_top_rows(CHERRY_WOOD, 0x99, freq=2.18),
        "cherry_leaves": cherry_leaves_rows(),
        # 초가 (HAY_BLOCK 20) — 녹림 산채의 지붕
        "hay_block_side": hay_side_rows(),
        "hay_block_top": hay_top_rows(),
        # 자리·차양 (WHITE_CARPET 11 · BROWN_CARPET 9 · LIGHT_GRAY_CARPET 8 · RED_CARPET 4)
        #   깔개는 제 텍스처가 없다 — **양털 텍스처**를 쓴다. 그래서 양털을 자리로 그린다.
        "white_wool": cloth_rows(ramp((176, 172, 164, 255), (238, 235, 228, 255), 9), 0x2B),
        "light_gray_wool": cloth_rows(ramp((136, 134, 130, 255), (202, 200, 195, 255), 9), 0x4D),
        "brown_wool": cloth_rows(ramp((78, 68, 54, 255), (146, 130, 106, 255), 9), 0x6F),
        "red_wool": cloth_rows(ramp((96, 40, 36, 255), (176, 86, 72, 255), 9), 0x91),   # 붉은 차양 (채색 허용)
        # 기물 (BARREL 39 · DECORATED_POT 21 · CAULDRON 12 · *_SHELF 5)
        "barrel_side": barrel_side_rows(),
        "barrel_top": barrel_top_rows(),
        "barrel_top_open": barrel_top_rows(open_lid=True),
        "barrel_bottom": barrel_top_rows(),
        "cauldron_side": cauldron_rows("side"),
        "cauldron_top": cauldron_rows("top"),
        "cauldron_inner": cauldron_rows("inner"),
        "cauldron_bottom": cauldron_rows("bottom"),
        # 시렁 — 1.21.9 신규 블록. 병장기 걸이·약장·술선반 (UV 구역은 shelf_rows 주석 참조)
        "spruce_shelf": shelf_rows(SPRUCE_WOOD, 0x3D),
        "dark_oak_shelf": shelf_rows(DARK_WOOD, 0x5B),
        "oak_shelf": shelf_rows(OAK_WOOD, 0x79),
        "bamboo_shelf": shelf_rows(ramp((104, 96, 78, 255), (176, 164, 142, 255), 9), 0x97),
        "cherry_shelf": shelf_rows(CHERRY_WOOD, 0xB5),
        # 백골 (BONE_BLOCK 4) — 산채의 위협 표식
        "bone_block_side": bone_block_rows(),
        "bone_block_top": bone_block_rows(top=True),
        # 쇠붙이 (IRON_CHAIN 6 · IRON_BARS 6) — 실루엣은 바닐라 계약, 픽셀만 우리 것 (iron_rows 주석)
        "iron_bars": iron_rows(IRON_BARS_MASK, 0x2D),
        "iron_chain": iron_rows(IRON_CHAIN_MASK, 0x4F),      # ※ 1.21.9+ 에서 chain → iron_chain 개명
        # 이끼·눈·불 (MOSS_CARPET 6 · CAMPFIRE 7 · 눈 덮인 면)
        "moss_block": moss_rows(),
        "grass_block_snow": snow_rows(),
        "campfire_log": campfire_log_rows(),
        "campfire_log_lit": campfire_log_rows(lit=True),
        # 세간 (LECTERN 5 · CRAFTING_TABLE 4) — 서안·목공대
        "lectern_top": lectern_rows("top"),
        "lectern_front": lectern_rows("front"),
        "lectern_sides": lectern_rows("sides"),
        "lectern_base": lectern_rows("base"),
        "crafting_table_top": crafting_table_rows("top"),
        "crafting_table_front": crafting_table_rows("front"),
        "crafting_table_side": crafting_table_rows("side"),
    })

    # ── 자재층 2차 — 축 ⑪ 이 면적 순으로 세워 준 구멍 (옆 수는 미커버 면적) ──
    SAND = ramp((122, 112, 92, 255), (206, 196, 172, 255), 9)
    # 적사(赤沙) — '붉은 모래'라고 붉게 칠하면 수묵이 깨진다. 자재_규약: 채도는 흙기(≤40)까지만
    RSAND = ramp((108, 88, 74, 255), (186, 158, 136, 255), 9)
    SNOW = ramp((168, 170, 174, 255), (246, 247, 250, 255), 8)
    ICE = ramp((132, 142, 148, 255), (212, 222, 228, 255), 8)      # 얼음 — 푸른 기 최소 (수묵)
    MUD = ramp((60, 55, 47, 255), (124, 114, 99, 255), 8)
    GRANITE = ramp((92, 78, 72, 255), (168, 148, 138, 255), 9)     # 화강 — 붉은 기 옅게
    DIORITE = ramp((140, 138, 136, 255), (226, 225, 223, 255), 9)  # 섬록 — 흰 돌
    CALCITE = ramp((160, 158, 152, 255), (232, 230, 224, 255), 8)
    SANDSTONE = ramp((116, 106, 86, 255), (198, 187, 160, 255), 8)
    RSANDSTONE = ramp((104, 84, 68, 255), (180, 152, 128, 255), 8)   # 적사암 — 채도 흙기까지만
    BIRCH = ramp((146, 142, 132, 255), (226, 223, 214, 255), 9)    # 자작 — 흰 수피
    IRON = ramp((48, 46, 44, 255), (152, 149, 144, 255), 8)
    STONE_H = ramp((60, 58, 55, 255), (146, 143, 137, 255), 8)
    blocks.update({
        "cobweb": cobweb_rows(),                                   # 7,373 — 폐사당·산채의 구석
        # 퇴비통 9,344 (조성기 3회 × 다섯 장)
        "composter_side": composter_rows("side"),
        "composter_top": composter_rows("top"),
        "composter_bottom": composter_rows("bottom"),
        "composter_compost": composter_rows("compost"),
        "composter_ready": composter_rows("ready"),
        # 모래·흙 6,144 + 1,536
        "sand": grain_rows(SAND, 0x11),
        "red_sand": grain_rows(RSAND, 0x13),
        "mud": grain_rows(MUD, 0x15, clump=1.5, grit=0.6),         # 진흙 — 뭉치고 알갱이는 적다
        # 눈·얼음 10,304 (POWDER_SNOW·SNOW·ICE·PACKED_ICE·BLUE_ICE·FROSTED_ICE)
        #   눈은 매끈하되 평면이 아니다 — 좁은 흰 램프 위에서 결을 다 밟는다 (grain_rows 주석)
        "powder_snow": grain_rows(SNOW, 0x17, clump=1.4, grit=0.7, dune=1.0),
        "snow": grain_rows(SNOW, 0x19, clump=1.3, grit=0.6, dune=1.2),
        "ice": ice_rows(ICE, 0x1B),
        "packed_ice": ice_rows(ICE, 0x1D, cracks=2),
        "blue_ice": ice_rows(ramp((112, 126, 138, 255), (196, 210, 220, 255), 8), 0x1F, cracks=1),
        # 돌 4,608 (GRANITE·DIORITE·CALCITE — 자재_규약: 돌은 무채색, 화강만 흙기 한 점)
        "granite": stone_rows(GRANITE, 0x21, amp=1.2),
        "diorite": stone_rows(DIORITE, 0x23, amp=1.1),
        "calcite": stone_rows(CALCITE, 0x25, amp=0.85),
        # 사암 — 켜(層)가 보이는 돌. 마구리(top/bottom)는 켜가 없으니 결로 산다 (dune)
        "sandstone": grain_rows(SANDSTONE, 0x27, clump=1.2, dune=1.1),
        "sandstone_top": grain_rows(SANDSTONE, 0x29, clump=1.3, grit=0.9, dune=0.8),
        "sandstone_bottom": grain_rows(SANDSTONE, 0x2B, clump=1.3, grit=0.9, dune=0.8),
        "red_sandstone": grain_rows(RSANDSTONE, 0x2D, clump=1.2, dune=1.1),
        "red_sandstone_top": grain_rows(RSANDSTONE, 0x2F, clump=1.3, grit=0.9, dune=0.8),
        "red_sandstone_bottom": grain_rows(RSANDSTONE, 0x31, clump=1.3, grit=0.9, dune=0.8),
        # 염색 천 9,216 — 저잣거리의 차양·깃발. 채도는 '의미'까지만 (수묵 규율: 흐린 초벌 염색)
        "yellow_wool": cloth_rows(ramp((132, 116, 66, 255), (206, 190, 130, 255), 9), 0x33),
        "orange_wool": cloth_rows(ramp((136, 92, 54, 255), (206, 156, 106, 255), 9), 0x35),
        "lime_wool": cloth_rows(ramp((100, 118, 74, 255), (172, 190, 136, 255), 9), 0x37),
        "light_blue_wool": cloth_rows(ramp((94, 116, 130, 255), (168, 190, 202, 255), 9), 0x39),
        "green_wool": cloth_rows(ramp((72, 92, 62, 255), (136, 156, 120, 255), 9), 0x3B),
        "cyan_wool": cloth_rows(ramp((72, 106, 108, 255), (140, 174, 176, 255), 9), 0x3D),
        # 자작나무 1,536 (BIRCH_LOG — 흰 수피는 산길의 표식)
        "birch_log": bark_rows(BIRCH, 0x3F, rough=0.6, knot=(6, 8)),
        "birch_log_top": log_top_rows(BIRCH, 0x41, freq=1.68),
        # 베틀 3,072 · 깔때기 1,580 · 모루 1,348
        "loom_front": loom_rows("front"),
        "loom_side": loom_rows("side"),
        "loom_top": loom_rows("top"),
        "loom_bottom": loom_rows("bottom"),
        "hopper_outside": metal_rows(IRON, 0x43, rivets=((2, 2), (13, 2), (2, 13), (13, 13))),
        # 깔때기 속 — 어두운 것이지 '없는' 것이 아니다. 아가리로 빨려드는 깔때기꼴 기울기를 준다
        "hopper_inside": funnel_rows(ramp((26, 25, 24, 255), (114, 110, 104, 255), 7), 0x45),
        "hopper_top": metal_rows(IRON, 0x47, rivets=((3, 3), (12, 12))),
        "anvil": metal_rows(IRON, 0x49, rivets=((4, 5), (11, 5), (4, 11), (11, 11))),
        "anvil_top": metal_rows(IRON, 0x4B, rivets=((7, 4), (8, 11))),
        # 화덕 2,816 (SMOKER·BLAST_FURNACE — 아궁이. GUI 와 짝을 이룬다)
        "smoker_front": hearth_front_rows(),
        "smoker_front_on": hearth_front_rows(lit=True),
        "smoker_side": stone_rows(STONE_H, 0x4D, amp=1.0),
        "smoker_top": stone_rows(STONE_H, 0x4F, amp=0.9),
        "smoker_bottom": stone_rows(STONE_H, 0x51, amp=0.9),
        "blast_furnace_front": hearth_front_rows(),
        "blast_furnace_front_on": hearth_front_rows(lit=True),
        "blast_furnace_side": stone_rows(STONE_H, 0x53, amp=1.05),
        "blast_furnace_top": stone_rows(STONE_H, 0x55, amp=0.95),
        # 대장간 1,792 (SMITHING_TABLE)
        "smithing_table_front": metal_rows(IRON, 0x57, rivets=((3, 4), (12, 4))),
        "smithing_table_side": plank_rows(DARK_WOOD, 0x59),
        "smithing_table_top": metal_rows(IRON, 0x5B, rivets=((5, 6), (10, 9))),
        "smithing_table_bottom": plank_rows(DARK_WOOD, 0x5D),
        # 널문·대울타리 3,360
        "spruce_trapdoor": trapdoor_rows(SPRUCE_WOOD, 0x5F),
        "oak_trapdoor": trapdoor_rows(OAK_WOOD, 0x61),
        "bamboo_fence": fence_rows(ramp((128, 118, 96, 255), (198, 185, 160, 255), 9), 0x63),
        # 사다리·야광이끼 1,836
        "ladder": ladder_rows(),
        "glow_lichen": lichen_rows(),
        # 들꽃 5,405 — 꽃은 **컬러맵 틴트 대상이 아니다** (틴트되는 것은 풀·잎·물).
        #   그래서 회색조가 초록으로 물들지 않는다 → 보류 대상이 아니고, 그릴 수 있다.
        "poppy": flower_rows(ramp((126, 52, 42, 255), (208, 102, 84, 255), 5), heart=(52, 46, 40, 255)),
        "cornflower": flower_rows(ramp((66, 78, 112, 255), (132, 148, 186, 255), 5), r=2.4),
        "oxeye_daisy": flower_rows(ramp((176, 172, 158, 255), (240, 238, 228, 255), 5),
                                   heart=(168, 146, 74, 255)),
        "white_tulip": flower_rows(ramp((182, 178, 166, 255), (242, 240, 232, 255), 5), cy=4.0, r=2.2),
        "azure_bluet": flower_rows(ramp((156, 166, 176, 255), (226, 232, 238, 255), 4), r=2.2,
                                   heart=(178, 156, 82, 255)),
        "dandelion": flower_rows(ramp((140, 122, 62, 255), (218, 200, 134, 255), 5), r=2.3),
    })

    # ── 자재층 2차-② — 축 ⑪ 이 남긴 잔여 구멍 (1.4%). 작지만 **미등록 구멍은 남기지 않는다** ──
    # 등록제의 뜻: 안 그린 것은 사유와 함께 「보류」에 있어야 한다. 사유 없이 비어 있는 칸이
    # 하나라도 남으면 등록부가 거짓말을 하는 것이다 (현판이 그랬다 — 축 ⑪ 이 잡았다).
    CROP = ramp((66, 76, 52, 255), (186, 178, 112, 255), 7)      # 풋것 → 여문 것 (밭은 익는다)
    BAMBOO = ramp((92, 100, 76, 255), (176, 182, 146, 255), 7)
    DRY = ramp((72, 62, 48, 255), (152, 136, 108, 255), 6)
    blocks.update({
        "flower_pot": pot_rows(),
        "scaffolding_top": scaffold_rows("top"),
        "scaffolding_side": scaffold_rows("side"),
        "scaffolding_bottom": scaffold_rows("bottom"),
        "dead_bush": sprig_rows(DRY, 0x55, fronds=3, lean=0.5),
        # 매화 묘목 — 꽃이 핀 어린 나무 (sprig_rows(CHERRY_WOOD) 는 밝기 82의 먹빛 삭정이였다)
        "cherry_sapling": cherry_sapling_rows(),
        "bamboo_stalk": sprig_rows(BAMBOO, 0x59, fronds=1, lean=0.0),
        "bamboo_singleleaf": sprig_rows(BAMBOO, 0x5B, fronds=2, lean=0.45),
        "mycelium_side": side_rows(grain_rows(ramp((58, 50, 56, 255), (118, 106, 116, 255), 8),
                                              0x5D, clump=1.3), dirt, band=3),
        "mycelium_top": grain_rows(ramp((58, 50, 56, 255), (124, 112, 122, 255), 8), 0x5F, clump=1.4),
        "brewing_stand": brewing_rows("stand"),
        "brewing_stand_base": brewing_rows("base"),
        "brown_mushroom": mushroom_rows(ramp((72, 58, 44, 255), (152, 126, 96, 255), 6),
                                        ramp((140, 132, 118, 255), (216, 210, 196, 255), 4)),
        "red_mushroom": mushroom_rows(ramp((104, 46, 38, 255), (186, 96, 80, 255), 6),
                                      ramp((146, 138, 124, 255), (222, 216, 202, 255), 4)),
        "sea_pickle": pickle_rows(),
        "torch": torch_rows(),
        "candle": torch_rows(lit=False, candle=True),
        "candle_lit": torch_rows(lit=True, candle=True),
        "tripwire_hook": hook_rows(),
        "tripwire": hook_rows(wire=True),
        # 살얼음 4단 (FROSTED_ICE) — 얼었다 녹는 중. 금이 늘수록 깨지기 직전이다
        **{f"frosted_ice_{i}": ice_rows(ICE, 0x61 + i, cracks=1 + i * 2) for i in range(4)},
        # 밭작물 — wheat 8단 · carrots/potatoes/beetroots 4단 (crop_rows 주석: 빈도 지표의 한계)
        **{f"wheat_stage{i}": crop_rows(CROP, i, 8) for i in range(8)},
        **{f"carrots_stage{i}": crop_rows(CROP, i, 4) for i in range(4)},
        **{f"potatoes_stage{i}": crop_rows(CROP, i, 4) for i in range(4)},
        **{f"beetroots_stage{i}": crop_rows(CROP, i, 4) for i in range(4)},
        **{f"sweet_berry_bush_stage{i}": sprig_rows(
            ramp((58, 62, 46, 255), (150, 124, 96, 255), 6), 0x6B + i,
            fronds=2 + i, lean=0.3, top_bias=i * 0.2) for i in range(4)},
    })

    for name, rows in blocks.items():
        write_png(BLOCK_DIR / f"{name}.png", rows)
    for name in SCROLL_MOTIFS:
        write_png(PAINTING_DIR / f"{name}.png", scroll_rows(name))
    # 현판·주기 — 블록이되 텍스처는 entity/signs/ 아래 산다 (궤·항아리와 같은 문법).
    #   resourcepack_design.yml 이 dark_oak_hanging_sign 을 「징발」로 등록해 두고도 팩에는
    #   그 PNG 가 없었다 — 축 ⑪ 이 잡아냈다 (등록부가 팩을 앞질러 있었다).
    signs = {"signs/oak": sign_rows(OAK_WOOD, 0x65),
             "signs/hanging/dark_oak": sign_rows(DARK_WOOD, 0x67, hanging=True)}
    for name, rows in signs.items():
        write_png(ENTITY_DIR / f"{name}.png", rows)
    return len(blocks) + len(SCROLL_MOTIFS) + len(signs)


# ═══════════════════════════════════════════════════════════════════════════
# 엔티티 텍스처 — 몹 징발 등록부
#
# 블록의 '희생 등록부' 문법을 몹에 그대로 적용한다: 몹 텍스처도 **전역 치환**이다
# (좀비 텍스처를 갈면 세계의 모든 좀비가 갈린다). 그래서 아이템처럼 '더하기'가 아니라
# 블록처럼 '맞바꾸기'로 설계한다 — 무엇을 얻고 **무엇을 잃어도 되는가**.
#
# ── 오염 판정의 근거 (HuntingGrounds.java onSpawn / ALLOWED_REASONS) ──
# 플러그인이 **Enemy 자연 스폰을 전 세계에서 취소**한다 (허용 이유: CUSTOM·COMMAND·
# SPAWNER_EGG·BREEDING·EGG·DISPENSE_EGG·골렘·눈사람·CURED). 따라서:
#   ZOMBIE·HUSK·HOGLIN·RAVAGER (전부 Enemy) → 자연 개체가 **0마리**다.
#   동굴 좀비도, 네더 호글린도, 습격 약탈수도 서지 않는다 → 전역 치환의 오염이 0이 된다.
#   이것이 좀비를 산적으로 갈아도 되는 유일한 근거다. 이 전제가 깨지면(자연 스폰 재허용)
#   동굴의 모든 좀비가 산적 옷을 입는다 — 그때는 이 등록부를 다시 심판해야 한다.
#   WOLF·POLAR_BEAR·CAT·OCELOT 은 Enemy 가 아니라 **자연 스폰이 산다**. 다만 이들의 오염은
#   '오염'이 아니라 **세계관 정합**이다: 혼천의 산에 서는 늑대는 산늑대여야 하고, 북극곰은
#   애초에 이 세계에 없다(반달곰이 맞다). 잃는 것이 없다 → 수용.
#
# ── UV 규약 (1.21.4 ModelPart 언랩 — 바이트코드에서 복원해 바닐라 마스크로 대조 검증) ──
#   박스(texOffs u,v / 치수 X,Y,Z)의 전개도는 폭 2Z+2X · 높이 Z+Y:
#       top (u+Z, v)  bottom (u+Z+X, v)                     ← 높이 Z 띠
#       right (u, v+Z)  front (u+Z, v+Z)  left (u+Z+X, v+Z)  back (u+2Z+X, v+Z)
#   면 이름은 **박스의 면**이지 짐승의 부위가 아니다. 네발짐승의 몸통은 대개 90도 눕혀
#   붙으므로 (WolfModel·CatModel·PolarBearModel·RavagerModel) 다음이 실제 부위다:
#       front = 배(아래)   back = 등(위)   top = 앞가슴   bottom = 궁둥이   right/left = 옆구리
#   HOGLIN 만 몸통이 눕지 않는다 (Z가 긴 축) → top = 등(강모), back = 궁둥이(꼬리).
#   근거: 바닐라 아트가 증언한다 — 늑대·얼룩고양이의 **크림색 배가 front 면**에 있고,
#   약탈수의 **안장이 back 면**에, 호글린의 **강모가 top 면**에, 꼬리가 back 면에 있다.
#   ※ 이 표를 틀리면 얼굴이 배에 붙는다. 박스 지문(불투명 마스크)으로 검증했다.
# ═══════════════════════════════════════════════════════════════════════════
ENTITY_DIR = PACK / "assets" / "minecraft" / "textures" / "entity"

# 팔·다리는 **좌우가 같은 UV를 거울로 공유한다** (HumanoidModel·WolfModel 등) —
# 좌우 비대칭 무늬는 구조적으로 불가능하다. 한쪽만 그리면 반대쪽에 뒤집혀 나온다.
FACES = ("top", "bottom", "right", "front", "left", "back")


def ebox(u, v, X, Y, Z):
    """박스 UV 전개 — 면이름 → (x0, y0, w, h). 좌표를 손으로 세지 않는다 (사고의 근원)."""
    return {
        "top":    (u + Z,         v,     X, Z),
        "bottom": (u + Z + X,     v,     X, Z),
        "right":  (u,             v + Z, Z, Y),
        "front":  (u + Z,         v + Z, X, Y),
        "left":   (u + Z + X,     v + Z, Z, Y),
        "back":   (u + 2 * Z + X, v + Z, X, Y),
    }


def eblank(w, h, fill=T):
    """빈 캔버스. 짐승은 **바탕색으로 채워서** 시작한다 — 박스 표를 한 칸 잘못 적어도
    '투명 = 모델의 구멍(면이 사라진다)'만은 일어나지 않게 하는 구조적 보험이다.
    대신 알파가 **필요한** 자리(사람의 모자 층, 멧돼지 강모 능선, 호랑이의 아가리)는
    칠하는 쪽에서 T를 되돌려 명시적으로 판다. 바닐라가 박스 안을 비워 둔 곳을 메우면
    모델이 막히기 때문이다 (약탈수 head.bottom·mouth.top = 아가리 속)."""
    return [[fill] * w for _ in range(h)]


def paint(grid, rect, fn):
    """면 하나를 로컬 좌표로 칠한다 — fn(lx, ly, w, h) → RGBA 또는 None(건너뜀).
    텍스처 좌표를 직접 만지지 않는 것이 요점이다: 면 안에서만 생각한다."""
    x0, y0, w, h = rect
    for ly in range(h):
        for lx in range(w):
            c = fn(lx, ly, w, h)
            if c is not None:
                grid[y0 + ly][x0 + lx] = c


def paint_box(grid, box, fn, only=None):
    """박스의 여러 면을 한 번에 — fn(면이름, lx, ly, w, h). 빠뜨린 면 = 투명 = 모델의 구멍."""
    for name in (only or FACES):
        paint(grid, box[name], lambda lx, ly, w, h, n=name: fn(n, lx, ly, w, h))


def pelt(shades, salt, base, amp=1.0, grad=0.0):
    """털가죽 — 계단 팔레트 + 결정론 잡티 + 세로 명암(빛은 위에서). 난수 금지."""
    def fn(lx, ly, w, h):
        v = base + octave(lx, ly, 1, salt, amp)
        if grad:
            v -= grad * (ly / max(1, h - 1) - 0.5) * 2
        return step(shades, v)
    return fn


# ─── ① 사람 — 산적(zombie) · 낭인(husk). 같은 인간형 UV, 다른 팔레트 ───
# 좀비 텍스처의 **모자 층(32,0)은 바닐라에서 완전히 비어 있다** → 두건을 얹을 빈 땅이다
# (+0.5 팽창 → 천이 머리를 감싼 두께로 읽힌다).
# 다만 플러그인이 전원에게 LEATHER_HELMET 을 씌운다 (HuntingGrounds.arm) — 가죽 투구는
# 그 바깥(팽창 1.0)에 렌더되어 **정수리를 덮는다**. 그래서 정체는 모자 층이 아니라
# **얼굴**이 진다: 눈·코·입·수염을 기본 머리 면에 새긴다 (투구를 써도 얼굴은 보인다 —
# 바닐라 가죽 투구 텍스처의 얼굴 부분이 비어 있음을 확인했다).
HUMAN_BOXES = {
    "head": ebox(0, 0, 8, 8, 8),
    "hat":  ebox(32, 0, 8, 8, 8),
    "body": ebox(16, 16, 8, 12, 4),
    "arm":  ebox(40, 16, 4, 12, 4),      # 좌우 거울 공유
    "leg":  ebox(0, 16, 4, 12, 4),       # 좌우 거울 공유
}

BANDIT = {                                     # 산적 — 볕에 그은 무명옷 사내
    "skin": ramp((104, 76, 56, 255), (208, 172, 140, 255), 5),
    "hair": ramp((20, 18, 17, 255), (66, 58, 52, 255), 5),
    "cloth": ramp((84, 77, 64, 255), (182, 172, 150, 255), 5),   # 무명(생목) — 화선지 계열
    "trouser": ramp((44, 40, 35, 255), (104, 96, 84, 255), 5),
    "sash": (128, 54, 42, 255),                # 허리띠 — 주사 (산적의 붉은 띠)
    "sash_hi": (168, 78, 62, 255),
    "band": ramp((70, 64, 54, 255), (150, 140, 118, 255), 5),    # 두건 — 무명
    "eye": (26, 24, 22, 255),
    "eye_w": (222, 214, 196, 255),
    "collar_lit": (226, 218, 200, 255),        # 동정 — 화선지 백 (계단 밖 전용 획)
    "collar_dim": (62, 57, 48, 255),           # 섶이 겹친 골
    "beard": True,
}
RONIN = {                                      # 낭인 — 흑의(黑衣) 무인. 옷이 어둡고 살결이 희다
    "skin": ramp((118, 92, 72, 255), (222, 192, 164, 255), 5),
    "hair": ramp((18, 17, 16, 255), (58, 54, 50, 255), 5),
    "cloth": ramp((26, 25, 24, 255), (86, 82, 78, 255), 5),      # 흑의
    "trouser": ramp((20, 19, 18, 255), (66, 63, 60, 255), 5),
    "sash": (58, 54, 50, 255),
    "sash_hi": (92, 86, 80, 255),
    "band": ramp((22, 21, 20, 255), (72, 68, 64, 255), 5),       # 망건 — 검은 띠
    "eye": (26, 24, 22, 255),
    "eye_w": (226, 220, 206, 255),
    "collar_lit": (206, 200, 188, 255),        # 동정 — 흑의 위의 흰 깃 (대비가 가장 세다)
    "collar_dim": (18, 17, 16, 255),
    "beard": False,
    "collar": (140, 52, 42, 255),              # 동정 안감 주사 1px — 무인의 격 (도적과 가르는 선)
}


def human_rows(p, salt):
    """인간형 64x64 — 산적·낭인 공용. 얼굴이 사람으로 읽히는 것이 유일한 합격 조건이다."""
    g = eblank(64, 64)
    B = HUMAN_BOXES
    skin, hair, cloth, trou, band = p["skin"], p["hair"], p["cloth"], p["trouser"], p["band"]

    # ── 머리: 앞면 = 얼굴 (눈 y=4 — 바닐라 인간형 관례), 윗면·뒷면 = 머리카락(상투)
    def head(n, lx, ly, w, h):
        if n == "top":                                   # 정수리 — 틀어올린 상투
            cx, cy = 3.5, 3.5
            d = max(abs(lx - cx), abs(ly - cy))
            return step(hair, 3.4 - d * 0.9 + octave(lx, ly, 1, salt, 0.5))
        if n == "back":
            return step(hair, 2.2 + octave(lx, ly, 1, salt + 1, 0.9) - (ly - 4) * 0.12)
        if n == "bottom":                                # 턱 밑
            return step(skin, 1.0 + octave(lx, ly, 1, salt + 2, 0.5))
        if n in ("right", "left"):                       # 귀밑머리 → 뺨
            if ly <= 2:
                return step(hair, 2.4 + octave(lx, ly, 1, salt + 3, 0.8))
            return step(skin, 2.4 + octave(lx, ly, 1, salt + 4, 0.7) - (ly - 4) * 0.15)
        # front — 얼굴
        if ly <= 1:                                      # 이마 위 머리칼
            return step(hair, 2.6 + octave(lx, ly, 1, salt + 5, 0.8))
        if ly == 2 and lx in (0, 7):                     # 귀밑으로 흘러내린 앞머리
            return step(hair, 2.2)
        if ly == 3 and lx in (1, 2, 5, 6):               # 눈썹 — 짙은 먹 두 획
            return step(hair, 1.2)
        if ly == 4 and lx in (1, 6):
            return p["eye_w"]                            # 흰자
        if ly == 4 and lx in (2, 5):
            return p["eye"]                              # 눈동자 (안쪽 — 노려보는 눈)
        if ly == 5 and lx in (3, 4):                     # 콧대
            return step(skin, 3.6)
        if ly == 6 and lx in (3, 4):                     # 입
            return step(skin, 0.4)
        if p["beard"] and ly >= 6 and (lx <= 1 or lx >= 6):
            return step(hair, 2.0 + octave(lx, ly, 1, salt + 6, 0.6))   # 구레나룻
        if p["beard"] and ly == 7 and 2 <= lx <= 5:
            return step(hair, 1.8 + octave(lx, ly, 1, salt + 7, 0.6))   # 턱수염
        return step(skin, 3.0 + octave(lx, ly, 1, salt + 8, 0.6) - (ly - 4) * 0.1)

    paint_box(g, B["head"], head)

    # ── 모자 층: 두건 — 이마를 감고 뒤에서 묶는다. 정수리는 천으로 덮되 나머지는 투명
    #    (투명을 남기지 않으면 머리에 정육면체가 하나 더 붙는다 — 상자 머리 사고)
    def hat(n, lx, ly, w, h):
        if n == "top":
            return step(band, 2.8 + octave(lx, ly, 1, salt + 9, 0.7))
        if n == "bottom":
            return None
        if n == "back":
            if ly <= 2:
                return step(band, 2.6 + octave(lx, ly, 1, salt + 10, 0.6))
            if ly <= 5 and lx in (2, 5):                 # 뒤로 늘어뜨린 두 가닥 매듭 끈
                return step(band, 1.8)
            if ly == 3 and 3 <= lx <= 4:                 # 매듭
                return step(band, 3.4)
            return None
        if ly <= 2:                                      # 앞·옆 — 이마를 감는 띠
            return step(band, 3.0 + octave(lx, ly, 1, salt + 11, 0.6) - ly * 0.3)
        return None

    paint_box(g, B["hat"], hat)

    # ── 몸통: 교임(交衽) 저고리 — 앞섶이 X로 겹치고 허리띠가 지른다
    def body(n, lx, ly, w, h):
        if n == "front":
            if ly <= 4:
                # 교임(交衽) 깃 — 어깨에서 명치로 모이는 두 사선. 무협의 옷은 여기서 읽힌다.
                # 깃은 **계단 밖의 전용 색**으로 긋는다: 천의 잡티(±0.7)와 같은 계단을 쓰면
                # 노이즈에 묻혀 사라진다 (실제로 1차에서 안 보였다 — 획은 획으로 그어야 한다)
                if lx == ly or lx == 7 - ly:
                    return p["collar_lit"]               # 동정 — 흰 깃
                if lx == ly + 1 or lx == 6 - ly:
                    c = p.get("collar")
                    if c is not None and ly >= 1:
                        return c                         # 안감 주사 (낭인)
                    return p["collar_dim"]               # 깃 그늘 — 섶이 겹친 골
            if ly == 8:
                return p["sash_hi"] if lx in (2, 5) else p["sash"]   # 허리띠
            if ly == 9:
                return p["sash"]
            return step(cloth, 3.0 + octave(lx, ly, 1, salt + 12, 0.7) - (ly - 5) * 0.08)
        if n in ("right", "left"):
            if ly in (8, 9):
                return p["sash"]
            return step(cloth, 2.4 + octave(lx, ly, 1, salt + 13, 0.8) - (ly - 5) * 0.08)
        if n == "back":
            if ly in (8, 9):
                return p["sash"]
            return step(cloth, 2.6 + octave(lx, ly, 1, salt + 14, 0.8) - (ly - 5) * 0.08)
        if n == "top":                                   # 어깨
            return step(cloth, 3.6 + octave(lx, ly, 1, salt + 15, 0.6))
        return step(cloth, 1.6 + octave(lx, ly, 1, salt + 16, 0.5))   # bottom — 옷자락 밑

    paint_box(g, B["body"], body)

    # ── 팔: 소매를 걷었다 — 위는 천, 아래는 맨살, 끝은 손 (좌우 거울이므로 대칭만 허용)
    def arm(n, lx, ly, w, h):
        if n == "top":
            return step(cloth, 3.4 + octave(lx, ly, 1, salt + 17, 0.6))
        if n == "bottom":
            return step(skin, 2.0 + octave(lx, ly, 1, salt + 18, 0.6))   # 손바닥
        if ly <= 5:                                      # 소매
            return step(cloth, 2.8 + octave(lx, ly, 1, salt + 19, 0.7) - ly * 0.06)
        if ly == 6:
            return step(cloth, 1.0)                      # 걷어올린 소매 끝단 — 그늘 한 줄
        if ly >= 10:
            return step(skin, 2.2 + octave(lx, ly, 1, salt + 20, 0.5))   # 손
        return step(skin, 3.0 + octave(lx, ly, 1, salt + 21, 0.6))       # 팔뚝

    paint_box(g, B["arm"], arm)

    # ── 다리: 바지 → 행전(정강이 감개) → 짚신
    def leg(n, lx, ly, w, h):
        if n == "top":
            return step(trou, 2.6 + octave(lx, ly, 1, salt + 22, 0.5))
        if n == "bottom":
            return step(trou, 0.6)                       # 신바닥
        if ly >= 11:
            return step(trou, 0.8 + octave(lx, ly, 1, salt + 23, 0.4))   # 짚신
        if ly >= 7:                                      # 행전 — 감아 올린 천 (가로 결)
            v = 3.2 if (ly % 2 == 0) else 2.2
            return step(cloth, v + octave(lx, ly, 1, salt + 24, 0.4))
        return step(trou, 2.4 + octave(lx, ly, 1, salt + 25, 0.7) - ly * 0.05)

    paint_box(g, B["leg"], leg)
    return g


# ─── ② 산늑대 — WOLF (변종 9종 × 야생/길들임/성남 = 27장) ───
# 늑대 변종은 1.20.5+ 데이터팩 레지스트리다. 어느 변종이 뽑히는지는 **생물군계**가 정한다
# (플러그인은 setVariant 를 쓰지 않는다 — HuntingGrounds 는 EntityType.WOLF 로만 부른다).
# 그래서 9종을 **모두** 산늑대로 갈아 놓는다: 어느 산에서 뽑히든 산늑대로 선다.
# 변종의 다양성은 버리지 않는다 — 9종을 9가지 '털색의 산늑대'로 남긴다.
WOLF_BOXES = {
    "head":  ebox(0, 0, 6, 6, 4),
    "ear":   ebox(16, 14, 2, 2, 1),      # 좌우 공유
    "snout": ebox(0, 10, 3, 3, 4),
    "body":  ebox(18, 14, 6, 9, 6),      # 눕힘: front=배, back=등
    "mane":  ebox(21, 0, 8, 6, 7),       # upper_body — 목덜미 갈기 (눕힘)
    "leg":   ebox(0, 18, 2, 8, 2),       # 네 다리 공유
    "tail":  ebox(9, 18, 2, 8, 2),
}
WOLF_COATS = {
    "": ((40, 38, 35), (150, 142, 128), (208, 200, 184), None),          # 회 — 기본(PALE)
    "_ashen": ((38, 37, 36), (126, 122, 116), (190, 184, 172), None),    # 잿빛
    "_black": ((16, 16, 15), (74, 70, 66), (128, 122, 114), None),       # 흑
    "_chestnut": ((44, 33, 25), (136, 106, 78), (200, 184, 160), None),  # 밤색
    "_rusty": ((50, 35, 24), (150, 108, 68), (208, 188, 158), None),     # 적갈
    "_snowy": ((122, 118, 110), (222, 218, 208), (242, 238, 230), None), # 설
    "_spotted": ((46, 42, 36), (144, 132, 114), (206, 198, 180), "spot"),
    "_striped": ((36, 32, 28), (132, 120, 102), (198, 190, 172), "stripe"),
    "_woods": ((34, 29, 24), (116, 100, 80), (186, 176, 154), None),     # 숲 — 짙은 갈
}
WOLF_EYES = {                                  # 야생은 노려보고, 성나면 핏발이 선다
    "": ((196, 156, 72, 255), (24, 22, 20, 255)),
    "_tame": ((208, 180, 116, 255), (30, 27, 24, 255)),
    "_angry": ((176, 52, 40, 255), (28, 20, 18, 255)),
}


def wolf_rows(coat, state):
    """산늑대 64x32 — 여윈 산짐승. 등은 짙고 배는 희고, 갈기가 서 있다."""
    dark, light, belly_c, mark = WOLF_COATS[coat]
    eye_c, pupil = WOLF_EYES[state]
    fur = ramp(dark + (255,), light + (255,), 5)
    belly = ramp(mix(dark + (255,), belly_c + (255,), 0.45), belly_c + (255,), 5)
    salt = zlib.crc32(coat.encode()) & 0xFF
    angry = state == "_angry"
    g = eblank(64, 32, step(fur, 2.0))
    B = WOLF_BOXES

    def marked(lx, ly, v):
        """변종 무늬 — 반점/줄무늬는 명암 계단 위에서만 논다 (팔레트 규율)."""
        if mark == "spot" and h32(lx // 2, ly // 2, salt) % 5 == 0:
            return v - 1.3
        if mark == "stripe" and (ly + lx // 3) % 4 == 0:
            return v - 1.2
        return v

    def head(n, lx, ly, w, h):
        if n == "front":                                  # 얼굴 — 눈 y=2 (바닐라 위치 그대로)
            if ly == 2 and lx in (0, 5):
                return eye_c
            if ly == 2 and lx in (1, 4):
                return pupil
            if ly >= 3 and 2 <= lx <= 3:                  # 주둥이로 이어지는 밝은 골
                return step(belly, 3.2)
            return step(fur, marked(lx, ly, 2.6 + octave(lx, ly, 1, salt, 0.8)))
        if n == "top":
            return step(fur, marked(lx, ly, 1.6 + octave(lx, ly, 1, salt + 1, 0.9)))
        if n == "bottom":
            return step(belly, 2.6 + octave(lx, ly, 1, salt + 2, 0.6))
        return step(fur, marked(lx, ly, 2.2 + octave(lx, ly, 1, salt + 3, 0.9) - (ly - 3) * 0.1))

    paint_box(g, B["head"], head)
    paint_box(g, B["ear"], lambda n, lx, ly, w, h:
              step(fur, 0.8 if n in ("front", "top") else 2.6))      # 귀 — 앞이 어둡다(구멍)

    def snout(n, lx, ly, w, h):
        if n == "front":
            return step(fur, 0.3) if ly <= 1 else step(belly, 2.4)   # 코 — 젖은 먹빛
        if n == "top":
            return step(fur, 1.4 + octave(lx, ly, 1, salt + 4, 0.7))
        if n == "bottom":
            return step(belly, 3.4)
        return step(fur, 2.0 + octave(lx, ly, 1, salt + 5, 0.7))

    paint_box(g, B["snout"], snout)

    def body(n, lx, ly, w, h):
        if n == "front":                                  # 배 — 크림색
            return step(belly, 3.0 + octave(lx, ly, 1, salt + 6, 0.7))
        if n == "back":                                   # 등 — 가장 짙다 (등줄기)
            v = 1.0 + octave(lx, ly, 1, salt + 7, 0.8)
            if 2 <= lx <= 3:
                v -= 0.7                                  # 등줄기 먹선
            return step(fur, marked(lx, ly, v))
        if n == "top":
            return step(fur, 2.2 + octave(lx, ly, 1, salt + 8, 0.7))
        if n == "bottom":
            return step(fur, 1.8 + octave(lx, ly, 1, salt + 9, 0.7))
        # 옆구리 — 위(등쪽)가 짙고 아래(배쪽)로 밝아진다
        return step(fur, marked(lx, ly, 3.2 + octave(lx, ly, 1, salt + 10, 0.8) - (h - 1 - ly) * 0.28))

    paint_box(g, B["body"], body)

    def mane(n, lx, ly, w, h):
        if n == "front":                                  # 앞가슴 아래 — 흰 목털
            return step(belly, 3.4 + octave(lx, ly, 1, salt + 11, 0.6))
        if n == "back":                                   # 목덜미 위 — 곤두선 갈기
            v = 0.9 + octave(lx, ly, 1, salt + 12, 1.1)
            if angry and lx % 2 == 0:
                v += 1.6                                  # 성나면 갈기가 선다 (밝은 결이 곤두선다)
            return step(fur, v)
        if n == "top":
            return step(fur, 2.6 + octave(lx, ly, 1, salt + 13, 0.8))
        if n == "bottom":
            return step(belly, 2.2 + octave(lx, ly, 1, salt + 14, 0.6))
        return step(fur, marked(lx, ly, 2.8 + octave(lx, ly, 1, salt + 15, 0.9) - (h - 1 - ly) * 0.22))

    paint_box(g, B["mane"], mane)
    paint_box(g, B["leg"], lambda n, lx, ly, w, h:
              step(fur, 0.4) if ly >= 7 else                              # 발 — 먹빛
              step(fur, 2.4 + octave(lx, ly, 1, salt + 16, 0.7) - ly * 0.18))
    paint_box(g, B["tail"], lambda n, lx, ly, w, h:
              step(fur, 0.3) if ly >= 6 else                              # 꼬리 끝 — 검다
              step(fur, 2.6 + octave(lx, ly, 1, salt + 17, 0.9) - ly * 0.2))
    return g


# ─── ③ 백영묘 — CAT(WHITE) 변종 채널. 오염 0의 정석 ───
# 고양이는 **변종이 11종**이다. 플러그인은 Cat.Type.WHITE 만 쓴다 → white.png 한 장만 간다.
# 나머지 10종(얼룩·검정·삼색…)은 바닐라 그대로 → 마을 고양이는 그냥 고양이다.
# 이것이 블록의 '희생 등록부'에 대응하는 **몹의 변종 채널**이다: 종을 통째로 바치지 않고
# 변종 한 칸만 바친다.
# 같은 UV(FelineModel)를 OCELOT 이 공유한다 → 호랑이의 '고양잇과 실루엣' 대안도 여기서 나온다.
CAT_BOXES = {
    "head":  ebox(0, 0, 5, 4, 5),
    "ear1":  ebox(0, 10, 1, 1, 2),
    "ear2":  ebox(6, 10, 1, 1, 2),
    "nose":  ebox(0, 24, 3, 2, 2),
    "body":  ebox(20, 0, 4, 16, 6),      # 눕힘: front=배, back=등
    "tail1": ebox(0, 15, 1, 8, 1),
    "tail2": ebox(4, 15, 1, 8, 1),
    "leg_b": ebox(8, 13, 2, 6, 2),
    "leg_f": ebox(40, 0, 2, 10, 2),
}


def feline_rows(p):
    """고양잇과 64x32 — 백영묘(영물)와 호랑이(오셀롯 대안)가 같은 UV를 쓴다."""
    fur, belly = p["fur"], p["belly"]
    eye_c, pupil, salt = p["eye"], p["pupil"], p["salt"]
    stripe = p.get("stripe")                   # 호랑이 — 먹 줄무늬
    wash = p.get("wash")                       # 백영묘 — 수묵 번짐
    g = eblank(64, 32, step(fur, 2.0))
    B = CAT_BOXES

    def coat(lx, ly, v, salt_off=0):
        v += octave(lx, ly, 1, salt + salt_off, 0.35)     # 미세한 털결
        if stripe:
            # 고양잇과 몸통도 눕는다 → 세로 줄무늬는 ly를 가로지르는 띠 (호랑이 대안용)
            if tiger_bar(ly, lx // 2, salt + 30, period=5):
                return -0.9                    # 줄무늬는 계단 밖 — 먹으로 내려앉는다
        if wash:
            # 수묵 번짐 — 점잡티가 아니라 **고이는 먹**이다. 부드러운 저주파라야 '번짐'으로 읽힌다
            # (1차의 픽셀 난반사는 그냥 노이즈로 보였다)
            v += smooth_octave(lx, ly, 4, salt + 40, 1.7)
        return v

    def head(n, lx, ly, w, h):
        if n == "front":                       # 얼굴 — 눈 y=1 (바닐라 위치)
            if ly == 1 and lx in (1, 3):
                return eye_c
            if ly == 2 and lx in (1, 3):
                return pupil
            return step(fur, coat(lx, ly, 3.2, 1))
        if n == "top":
            v = coat(lx, ly, 2.4, 2)
            if p.get("brow") and ly <= 1 and lx in (1, 3):
                return p["brow"]               # 이마 인장 — 범상치 않은 표식
            return step(fur, v)
        if n == "bottom":
            return step(belly, 3.4)
        return step(fur, coat(lx, ly, 2.8, 3))

    paint_box(g, B["head"], head)
    for e in ("ear1", "ear2"):
        paint_box(g, B[e], lambda n, lx, ly, w, h:
                  step(fur, 0.5) if n in ("front", "top") else step(fur, 2.8))
    paint_box(g, B["nose"], lambda n, lx, ly, w, h:
              p["nose"] if n == "front" and ly == 0 else step(belly, 3.2))

    def body(n, lx, ly, w, h):
        if n == "front":                       # 배
            return step(belly, 3.4 + octave(lx, ly, 1, salt + 4, 0.5))
        if n == "back":                        # 등 — 짙다
            return step(fur, coat(lx, ly, 1.6, 5))
        if n == "top":
            return step(belly, 2.8)            # 앞가슴
        if n == "bottom":
            return step(fur, coat(lx, ly, 2.0, 6))
        return step(fur, coat(lx, ly, 3.0, 7) - (h - 1 - ly) * 0.04)

    paint_box(g, B["body"], body)
    for t in ("tail1", "tail2"):
        paint_box(g, B[t], lambda n, lx, ly, w, h:
                  step(fur, 0.4) if ly >= 6 else step(fur, coat(lx, ly, 2.6, 8)))
    for lg in ("leg_b", "leg_f"):
        paint_box(g, B[lg], lambda n, lx, ly, w, h:
                  step(belly, 3.6) if ly >= h - 2 else                 # 흰 발
                  step(fur, coat(lx, ly, 2.8, 9) - ly * 0.1))
    return g


BAEK = {                                        # 백영묘(白影猫) — 눈처럼 희고 눈빛이 붉다
    # 어두운 끝을 연회색까지 내린다: 먹이 고일 자리가 있어야 '수묵'이 된다
    # (백을 백으로만 칠하면 종이지 짐승이 아니다 — 흰 짐승도 그림자로 빚는다)
    "fur": ramp((112, 110, 108, 255), (246, 244, 238, 255), 5),
    "belly": ramp((198, 196, 190, 255), (252, 251, 248, 255), 5),
    "eye": (168, 62, 48, 255),                  # 주사 — 영물의 눈
    "pupil": (58, 22, 18, 255),
    "nose": (150, 56, 44, 255),
    "brow": (44, 41, 38, 255),                  # 이마의 먹 인장
    "wash": True,                               # 수묵 번짐 — 그림자가 흐르는 짐승
    "salt": 71,
}
TIGER_CAT = {                                   # 호랑이 (OCELOT 대안 — 고양잇과 실루엣)
    "fur": ramp((92, 66, 34, 255), (192, 148, 84, 255), 5),
    "belly": ramp((186, 176, 154, 255), (238, 232, 216, 255), 5),
    "eye": (208, 172, 84, 255),
    "pupil": (24, 22, 20, 255),
    "nose": (140, 82, 70, 255),
    "stripe": True,
    "salt": 113,
}


# ─── ④ 반달곰 — POLAR_BEAR ───
# 곰은 거의 균일한 흑모다 → 면을 헷갈려도 티가 나지 않는다 (설계로 위험을 지운다).
# 유일한 표식인 **반달(가슴의 흰 초승달)** 은 body2 의 front 면에 놓는다:
#   몸통이 눕든(front=배) 눕지 않든(front=가슴) **front 는 언제나 가슴 쪽**이다 —
#   두 가정 어디서도 옳은 자리다. (곰이 일어서면 이 면이 정면으로 온다)
BEAR_BOXES = {
    "head":  ebox(0, 0, 7, 7, 7),
    "mouth": ebox(0, 44, 5, 3, 3),
    "ear":   ebox(26, 0, 2, 2, 1),
    "body":  ebox(0, 19, 14, 14, 11),
    "body2": ebox(39, 0, 12, 12, 10),    # 앞쪽 — 어깨·가슴
    "leg_f": ebox(50, 22, 4, 10, 8),
    "leg_b": ebox(50, 40, 4, 10, 6),
}
BEAR_FUR = ramp((18, 17, 16, 255), (86, 79, 72, 255), 5)     # 흑모 — 갈색 기가 도는 검정
BEAR_MUZZLE = ramp((92, 76, 60, 255), (166, 144, 118, 255), 5)
MOON = (232, 226, 210, 255)                                   # 반달 — 화선지 백
MOON_DIM = (176, 170, 156, 255)


def bear_rows():
    """반달곰 128x64 — 흑모에 가슴의 반달 하나. 그 한 획이 이름을 말한다."""
    g = eblank(128, 64, step(BEAR_FUR, 2.0))
    B, salt = BEAR_BOXES, 29

    def head(n, lx, ly, w, h):
        if n == "front":                                   # 얼굴 — 눈 (1,3),(5,3) [바닐라 위치]
            if ly == 3 and lx in (1, 5):
                return (232, 224, 206, 255)
            if ly >= 4 and 2 <= lx <= 4:
                return step(BEAR_MUZZLE, 2.6 + octave(lx, ly, 1, salt, 0.6))   # 주둥이
            return step(BEAR_FUR, 2.4 + octave(lx, ly, 1, salt + 1, 0.8))
        if n == "top":
            return step(BEAR_FUR, 2.8 + octave(lx, ly, 1, salt + 2, 0.8))
        return step(BEAR_FUR, 1.8 + octave(lx, ly, 1, salt + 3, 0.9))

    paint_box(g, B["head"], head)
    paint_box(g, B["mouth"], lambda n, lx, ly, w, h:
              step(BEAR_FUR, 0.3) if n == "front" and ly == 0 else            # 코
              step(BEAR_MUZZLE, 3.0 + octave(lx, ly, 1, salt + 4, 0.7)))
    paint_box(g, B["ear"], lambda n, lx, ly, w, h:
              step(BEAR_MUZZLE, 1.4) if n == "front" else step(BEAR_FUR, 2.0))

    def body(n, lx, ly, w, h):
        v = 2.2 + octave(lx, ly, 1, salt + 5, 0.9)
        if n == "back":
            v -= 0.8                                       # 등 — 짙게
        if n == "front":
            v += 0.3
        return step(BEAR_FUR, v)

    paint_box(g, B["body"], body)

    def body2(n, lx, ly, w, h):
        if n == "front":                                   # 가슴 — 반달을 새긴다
            cx, cy = (w - 1) / 2, h * 0.42
            dx, dy = (lx - cx) / (w * 0.40), (ly - cy) / (h * 0.34)
            r = dx * dx + dy * dy
            # 초승달 = 큰 원에서 위로 살짝 옮긴 원을 뺀다 (V자로 벌어진 반달)
            dx2, dy2 = (lx - cx) / (w * 0.40), (ly - cy + h * 0.30) / (h * 0.34)
            r2 = dx2 * dx2 + dy2 * dy2
            if r <= 1.0 and r2 > 1.0:
                return MOON if ly < h * 0.62 else MOON_DIM
            return step(BEAR_FUR, 2.4 + octave(lx, ly, 1, salt + 6, 0.8))
        v = 2.2 + octave(lx, ly, 1, salt + 7, 0.9)
        if n == "back":
            v -= 0.8
        return step(BEAR_FUR, v)

    paint_box(g, B["body2"], body2)
    for lg in ("leg_f", "leg_b"):
        paint_box(g, B[lg], lambda n, lx, ly, w, h:
                  step(BEAR_FUR, 0.2) if ly >= h - 2 else                    # 발톱·발바닥
                  step(BEAR_FUR, 2.2 + octave(lx, ly, 1, salt + 8, 0.8) - ly * 0.08))
    return g


# ─── ⑤ 멧돼지 — HOGLIN ───
# 호글린만 몸통이 눕지 않는다: top = 등(강모), back = 궁둥이(꼬리), front = 앞가슴.
# 네더의 붉은 살빛을 걷어내고 **흙빛 강모**로 덮는다. 엄니는 상아색으로 남긴다 (무기니까).
HOGLIN_BOXES = {
    "body":   ebox(1, 1, 16, 14, 26),
    "head":   ebox(61, 1, 14, 6, 19),    # top 면에 눈이 있다 (x 0~1 / 12~13, y 7~8)
    "ear_r":  ebox(1, 1, 6, 1, 4),       # 몸통 전개도의 빈 귀퉁이를 재활용 (바닐라 관행)
    "ear_l":  ebox(1, 6, 6, 1, 4),
    "tusk_r": ebox(10, 13, 2, 11, 2),
    "tusk_l": ebox(1, 13, 2, 11, 2),
    "leg_fr": ebox(66, 42, 6, 14, 6),
    "leg_fl": ebox(41, 42, 6, 14, 6),
    "leg_br": ebox(21, 45, 5, 11, 5),
    "leg_bl": ebox(0, 45, 5, 11, 5),
    "mane":   ebox(90, 33, 0, 10, 19),   # 폭 0 = 평면 두 장 (등의 강모 능선). 알파로 실루엣을 판다
}
BOAR_FUR = ramp((30, 26, 22, 255), (124, 106, 84, 255), 5)      # 흙빛 강모
BOAR_SNOUT = ramp((72, 58, 50, 255), (146, 122, 106, 255), 5)
IVORY = ramp((136, 126, 100, 255), (232, 224, 196, 255), 5)     # 엄니 — 상아


def hoglin_rows():
    """멧돼지 128x64 — 등의 강모 능선과 상아 엄니. 돌진하는 짐승의 앞머리가 무겁다."""
    g = eblank(128, 64, step(BOAR_FUR, 2.0))
    B, salt = HOGLIN_BOXES, 47

    def body(n, lx, ly, w, h):
        if n == "top":                                     # 등 — 강모가 능선을 이룬다
            v = 1.6 + octave(lx, ly, 1, salt, 1.0)
            if 6 <= lx <= 9:
                v -= 0.9                                   # 등줄기 먹선
            return step(BOAR_FUR, v)
        if n == "bottom":                                  # 배
            return step(BOAR_FUR, 2.6 + octave(lx, ly, 1, salt + 1, 0.7))
        if n == "back":                                    # 궁둥이 — 꼬리 한 점
            if abs(lx - 8) <= 0 and 2 <= ly <= 5:
                return step(BOAR_FUR, 0.4)
            return step(BOAR_FUR, 2.0 + octave(lx, ly, 1, salt + 2, 0.8))
        if n == "front":                                   # 앞가슴 — 두껍고 짙다
            return step(BOAR_FUR, 1.4 + octave(lx, ly, 1, salt + 3, 0.8))
        # 옆구리 — 어깨(앞)가 짙고 뒤로 갈수록 성기다
        return step(BOAR_FUR, 2.6 + octave(lx, ly, 1, salt + 4, 1.0) - (1.0 - lx / max(1, w - 1)) * 1.1)

    paint_box(g, B["body"], body)

    def head(n, lx, ly, w, h):
        if n == "top":                                     # 이마·콧등 + 눈 (바닐라 자리 그대로)
            if 7 <= ly <= 8 and (lx <= 1 or lx >= 12):
                return (222, 212, 190, 255) if ly == 7 else (30, 27, 24, 255)
            return step(BOAR_FUR, 2.0 + octave(lx, ly, 1, salt + 5, 0.9))
        if n == "front":                                   # 코끝 — 콧구멍 둘
            if ly in (2, 3) and lx in (5, 8):
                return (28, 24, 22, 255)
            return step(BOAR_SNOUT, 3.0 + octave(lx, ly, 1, salt + 6, 0.6))
        if n == "bottom":
            return step(BOAR_SNOUT, 2.0 + octave(lx, ly, 1, salt + 7, 0.6))
        return step(BOAR_FUR, 2.4 + octave(lx, ly, 1, salt + 8, 0.9))

    paint_box(g, B["head"], head)
    for e in ("ear_r", "ear_l"):
        paint_box(g, B[e], lambda n, lx, ly, w, h:
                  step(BOAR_FUR, 1.2 + octave(lx, ly, 1, salt + 9, 0.6)))
    for t in ("tusk_r", "tusk_l"):
        paint_box(g, B[t], lambda n, lx, ly, w, h:
                  step(IVORY, 3.6 - ly * 0.22 + octave(lx, ly, 1, salt + 10, 0.4)))
    for lg in ("leg_fr", "leg_fl", "leg_br", "leg_bl"):
        paint_box(g, B[lg], lambda n, lx, ly, w, h:
                  step(BOAR_FUR, 0.2) if ly >= h - 3 else                     # 굽 — 검다
                  step(BOAR_FUR, 2.4 + octave(lx, ly, 1, salt + 11, 0.8) - ly * 0.12))

    def mane(n, lx, ly, w, h):
        """강모 능선 — 폭 0 평면(면이 둘뿐이다). 알파가 곧 실루엣이다: 어깨에서 높고
        뒤로 낮아지며, 끝을 톱니로 뜯어 '털'로 읽히게 한다 (직사각형이면 판자가 된다).
        T를 되돌려 **명시적으로 판다** — 바탕색으로 미리 채운 캔버스이기 때문이다."""
        if n not in ("right", "left"):
            return T                                       # 폭 0 면 — 존재하지 않는다
        t = lx / max(1, w - 1)
        if n == "left":
            t = 1.0 - t                                    # 반대쪽 면은 좌우가 뒤집힌다
        ridge = h * (0.55 + 0.45 * t)                      # 어깨 쪽이 높다
        ridge -= (h32(lx, salt + 12) % 3) * 0.8            # 톱니 — 털 끝이 고르지 않다
        if ly < h - ridge:
            return T                                       # 투명 = 털이 없는 하늘
        v = 1.4 + octave(lx, ly, 1, salt + 13, 0.9) + (ly - (h - ridge)) * 0.22
        return step(BOAR_FUR, v)

    paint_box(g, B["mane"], mane)
    return g


# ─── ⑥ 호랑이 — RAVAGER (플러그인의 현재 선택) ───
# 바닐라에 호랑이가 없다. 약탈수는 **실루엣이 호랑이가 아니다** (뿔·늘어진 귀·구부정한 등).
# 텍스처가 할 수 있는 것: 색과 무늬로 종을 바꾼다 — 황갈 바탕 · 먹 줄무늬 · 흰 주둥이 ·
# 이마의 王 (호랑이의 관례적 표식이자 무협의 기호). 뿔은 귀로, 늘어진 귀는 먹빛으로 눌러
# 실루엣의 소음을 줄인다. 한계는 정직하게 남는다 (보고서 §한계).
RAV_BOXES = {
    "head":  ebox(0, 0, 16, 20, 16),     # front = 얼굴 (눈 y=13, x 2~3 / 12~13)
    "horn":  ebox(0, 0, 4, 8, 4),        # 뿔 → 귀로 읽힌다
    "mouth": ebox(0, 36, 16, 3, 16),
    "ear":   ebox(74, 55, 2, 14, 4),     # 늘어진 귀 — 먹으로 눌러 죽인다
    "neck":  ebox(68, 73, 10, 10, 18),
    "body":  ebox(0, 55, 14, 16, 20),    # 눕힘: back = 등(안장 자리), front = 배
    "body2": ebox(0, 91, 12, 13, 18),
    "leg_a": ebox(96, 0, 8, 37, 8),
    "leg_b": ebox(64, 0, 8, 37, 8),
}
TIG_FUR = ramp((96, 68, 34, 255), (198, 154, 88, 255), 5)     # 황갈 — 저채도 황토
TIG_BELLY = ramp((178, 168, 146, 255), (240, 234, 218, 255), 5)
TIG_INK = (26, 23, 20, 255)                                   # 먹 줄무늬
TIG_EYE = (214, 178, 88, 255)


def tiger_bar(across, along, salt, period=6, width=1):
    """먹 줄무늬 — across 축을 가로지르는 띠. along 으로 결정론 요동을 준다
    (자로 그은 줄은 호랑이가 아니다).

    ★ 축을 고르는 것이 이 함수의 전부다. 눕힌 몸통(약탈수·늑대·고양이)의 옆구리 면에서
      UV의 lx는 **세로(위아래)** 이고 ly는 **몸의 앞뒤** 다. 호랑이의 줄무늬는 세로로 선다 →
      UV에서는 **ly를 가로지르는 띠**(ly 고정, lx 방향으로 길게)로 그어야 한다.
      lx를 가로질러 그으면 몸을 따라 흐르는 가로줄이 되어 오소리가 된다."""
    wob = (h32(along, salt) % 3) - 1
    return ((across + wob) % period) < width


def ravager_rows():
    """호랑이 128x128 (약탈수 징발) — 색과 무늬로 종을 바꾼다."""
    g = eblank(128, 128, step(TIG_FUR, 2.0))
    B, salt = RAV_BOXES, 83

    def coat(lx, ly, v, off=0, period=6, axis="y"):
        """axis='y' → ly를 가로지르는 띠 (눕힌 몸통·다리: 세계에서 세로줄로 선다)
           axis='x' → lx를 가로지르는 띠 (머리: 뺨을 타고 내리는 세로줄)"""
        across, along = (ly, lx // 3) if axis == "y" else (lx, ly // 3)
        if tiger_bar(across, along, salt + off, period):
            return TIG_INK
        return step(TIG_FUR, v + octave(lx, ly, 1, salt + off, 0.5))

    def fang(lx):
        """송곳니 넷 — 바닐라 약탈수의 이빨 배열 그대로 (lx%4 ∈ {1,2} 가 이, 나머지는 틈).
        틈은 **T로 판다**: 메우면 이빨이 사라지고 턱이 판자가 된다."""
        return (lx % 4) in (1, 2)

    def head(n, lx, ly, w, h):
        if n == "bottom":
            return T                                                      # 아가리 속 — 바닐라도 비운다
        if n == "front":
            if ly >= 18:                                                  # 아랫니 두 줄
                return (238, 232, 214, 255) if fang(lx) else T
            # 눈 — 바닐라 자리(ly=13, lx 2·3 / 12·13). 눈두덩을 먹으로 파야 눈이 산다
            if 12 <= ly <= 14 and (1 <= lx <= 4 or 11 <= lx <= 14):
                if ly == 13 and lx in (2, 3, 12, 13):
                    return TIG_EYE
                return TIG_INK
            if ly >= 15 and 4 <= lx <= 11:
                return step(TIG_BELLY, 3.4)                               # 흰 주둥이
            if 1 <= ly <= 8 and 5 <= lx <= 10:
                # 이마의 王 — 세 가로획 + 한 세로획. 호랑이는 이름을 이마에 지고 다닌다.
                # 이 영역은 줄무늬를 **끄고** 민 털로 둔다 (줄이 겹치면 글자가 죽는다)
                if ly in (1, 4, 7) or lx in (7, 8):
                    return TIG_INK
                return step(TIG_FUR, 3.6)
            return coat(lx, ly, 2.8, 1, period=5, axis="x")
        if n == "top":
            return coat(lx, ly, 2.2, 2, period=5, axis="x")
        if n in ("right", "left") and ly >= 18:                           # 옆에서 본 이빨 틈
            return (238, 232, 214, 255) if fang(lx) else T
        return coat(lx, ly, 2.6, 3, period=5, axis="x")

    paint_box(g, B["head"], head)
    paint_box(g, B["horn"], lambda n, lx, ly, w, h:                        # 뿔 → 귀
              step(TIG_BELLY, 3.0) if n == "front" and ly >= 3 else
              step(TIG_FUR, 0.8 + octave(lx, ly, 1, salt + 4, 0.6)))

    def mouth(n, lx, ly, w, h):
        if n == "top":
            return T                                                       # 아가리 속 — 바닐라도 비운다
        if n == "bottom":
            return (120, 70, 62, 255) if 4 <= lx <= 11 else step(TIG_BELLY, 3.2)
        if n == "front":                                                   # 윗니
            return (238, 232, 214, 255) if fang(lx) else T
        return step(TIG_BELLY, 3.2)

    paint_box(g, B["mouth"], mouth)
    paint_box(g, B["ear"], lambda n, lx, ly, w, h:
              step(TIG_FUR, 0.3 + octave(lx, ly, 1, salt + 5, 0.5)))       # 먹빛으로 눌러 죽인다
    paint_box(g, B["neck"], lambda n, lx, ly, w, h:
              step(TIG_BELLY, 3.2) if n == "front" else coat(lx, ly, 2.6, 6, period=6))

    def body(n, lx, ly, w, h):
        # 몸통은 눕는다 → UV의 ly가 '몸의 앞뒤'다. 세로 줄무늬 = ly를 가로지르는 띠 (axis='y')
        if n == "front":                                                   # 배 — 희다 (줄이 옅다)
            return step(TIG_BELLY, 3.2 + octave(lx, ly, 1, salt + 7, 0.4))
        if n == "back":                                                    # 등 — 줄무늬가 가장 짙고 굵다
            return coat(lx, ly, 1.8, 8, period=5, axis="y")
        if n == "top":
            return step(TIG_BELLY, 2.6)                                    # 앞가슴 — 흰 목털
        if n == "bottom":
            return coat(lx, ly, 2.2, 9, period=6, axis="y")
        return coat(lx, ly, 3.0, 10, period=6, axis="y")                   # 옆구리 — 세로줄

    paint_box(g, B["body"], body)
    paint_box(g, B["body2"], body)
    for lg in ("leg_a", "leg_b"):
        paint_box(g, B[lg], lambda n, lx, ly, w, h:
                  step(TIG_BELLY, 3.4) if ly >= h - 3 else                 # 흰 발
                  coat(lx, ly, 2.8, 11, period=8, axis="y"))               # 다리는 고리 무늬
    return g


# ═══════════════════════════════════════════════════════════════════════════
# ─── ⑦ 마을 사람 — VILLAGER. 청하현의 얼굴 (가장 많이 보이는 몹) ───
#
# ── 왜 지금인가 ──
# Populace.java 가 **무명(無名) 28인**을 VILLAGER 로 세운다 (config/npcs/populace.yml).
# 계약 NPC 9인도 VILLAGER 다. 즉 플레이어가 마을에서 마주치는 사람은 **전부 주민 몹**이고,
# 그 몸이 바닐라면 청하현은 무협 마을이 아니라 **바닐라 마을에 기와만 얹은 곳**이다.
#
# ── 렌더 계약 (VillagerRenderer — 네 층이 겹친다) ──
#   ① entity/villager/villager.png                (바탕 — 살결·머리·속옷)
#   ② entity/villager/type/<생물군계>.png          (겉옷 — 늘 그려진다)
#   ③ entity/villager/profession/<생업>.png        (생업 표식 — profession != NONE 일 때만)
#   ④ entity/villager/profession_level/<등급>.png  (가슴 패 — 생업이 있을 때만)
# 네 장 **전부 우리 파일**이다 → 픽셀 하나까지 우리가 정한다. 특히 ①이 유일한 층인 경우가
# 없다는 것이 중요하다: populace.yml 의 생업 분포는 NONE 34 / FARMER 8 / 나머지 2씩이므로
# **대다수 주민은 ①+②만으로 선다**. ①+②가 그 자체로 완성된 사람이어야 한다.
#
# ── 코(鼻) — "바닐라 주민 코 금지" 를 실제로 이행하는 법 ──
# 리소스팩은 엔티티 **모델(기하)** 을 못 바꾼다. 코 박스(2x4x2)는 모델에 박혀 있다.
# 그러나 주민은 **RenderType.entityCutoutNoCull** 로 그려진다 — 알파가 낮은 텍셀은 **버려진다**.
#   근거(짐작 아님): 바닐라 profession/*.png·type/*.png 는 대부분이 투명한 오버레이인데
#   그 투명부가 검은 상자로 나오지 않는다. 같은 모델·같은 렌더타입이다 → 투명 = 안 그려짐.
# 따라서 **코 박스의 UV를 전부 투명으로 두면 코가 통째로 컬링된다.** 네 층 전부 우리 것이므로
# 어느 층도 그 자리를 다시 칠하지 않는다 → 코는 돌아오지 않는다. 얼굴의 콧대는 **머리 앞면에**
# 명암으로 새긴다 (사람의 코는 튀어나온 상자가 아니라 빛과 그늘이다).
#
# ── UV 규약 (검증 방법) ──
# 몹 모델 UV는 JSON이 아니라 자바에 박혀 있다. 그래서 **바닐라 오버레이의 불투명 마스크로 역산**했다:
# type/plains.png 가 칠하는 자리 = 몸통·다리·팔·팔짱·겉옷 박스이고, profession/farmer.png 가
# 칠하는 자리 = 모자 박스다. 아래 표는 그 마스크와 **정확히** 일치한다 (대조 검증 완료).
VILLAGER_BOXES = {
    "head":       ebox(0, 0, 8, 10, 8),      # x0..31  y0..17
    "nose":       ebox(24, 0, 2, 4, 2),      # x24..31 y0..5   ← 전부 투명 (컬링)
    "hat":        ebox(32, 0, 8, 10, 8),     # x32..63 y0..17  (머리 오버레이 — 두건)
    "hat_rim":    ebox(30, 47, 16, 16, 1),   # x30..63 y47..63 (삿갓 챙 — 갓 쓰는 생업만 보인다)
    "body":       ebox(16, 20, 8, 12, 6),    # x16..43 y20..37
    "leg":        ebox(0, 22, 4, 12, 4),     # x0..15  y22..37 (좌우 거울 공유)
    "arm":        ebox(44, 22, 4, 8, 4),     # x44..59 y22..33 (좌우 거울 공유)
    "arms_cross": ebox(40, 38, 8, 4, 4),     # x40..63 y38..45 (앞으로 모은 두 손)
    "jacket":     ebox(0, 38, 8, 18, 6),     # x0..27  y38..61 (겉옷 — 12가 아니라 **18** 이 길다: 두루마기)
}

VILLAGER_SKIN = {                            # 바탕 — 살결·머리칼·속옷 (모든 주민 공통)
    "skin": ramp((110, 82, 60, 255), (212, 176, 144, 255), 5),
    "hair": ramp((19, 18, 17, 255), (62, 56, 50, 255), 5),
    "inner": ramp((88, 82, 71, 255), (186, 178, 158, 255), 5),    # 속저고리 — 무명(생목)
    "trouser": ramp((46, 42, 37, 255), (106, 98, 86, 255), 5),
    "band": ramp((56, 52, 46, 255), (130, 122, 106, 255), 5),     # 두건
    "straw": ramp((104, 92, 64, 255), (202, 186, 142, 255), 5),   # 삿갓 — 짚
    "eye": (28, 25, 22, 255),
    "eye_w": (226, 219, 202, 255),
    "lip": (122, 84, 70, 255),
}

# 겉옷 팔레트 — 생물군계 8종. 어느 군계에서 뽑혀도 무협의 옷이 되도록 **전부** 채운다
# (주민 type 은 스폰 지점의 생물군계가 정한다. 하나라도 비우면 그 군계에서 갈옷이 튀어나온다).
# 수묵 규율: 채도는 의미에만 — 옷은 먹에 흙기를 옅게 섞은 값이다. 붉은 고름 1획만 허락한다.
VILLAGER_ROBES = {                           # 바닐라 생물군계 7종 (jar 확인 — forest 는 없다)
    "plains":  ((78, 74, 66), (176, 170, 152), (150, 58, 46)),    # 무명(생목) — 청하 기본
    "taiga":   ((62, 58, 52), (140, 132, 118), (128, 52, 42)),    # 갈옷 — 산골
    "snow":    ((104, 102, 98), (214, 210, 202), (140, 56, 44)),  # 솜옷 — 두껍고 희다
    "desert":  ((96, 88, 74), (196, 184, 158), (146, 60, 46)),    # 마의(麻衣) — 얇은 삼베
    "savanna": ((84, 76, 62), (172, 158, 130), (138, 54, 42)),    # 흙빛 무명
    "jungle":  ((66, 72, 68), (146, 156, 146), (132, 54, 44)),    # 죽청(竹靑) — 대숲 기운
    "swamp":   ((60, 60, 56), (132, 132, 124), (124, 50, 40)),    # 잿빛 무명
}

# 생업 표식 — populace.yml 이 쓰는 8종 + 바닐라 잔여 6종. **전부** 덮는다 (전역 치환:
# 하나라도 비우면 그 생업의 주민만 바닐라 로브를 입고 서 있다).
#   hat: 갓(삿갓 챙까지) | 건(두건만) | None(맨머리)
#   apron: 앞치마 색 (겉옷 앞자락 위에 덧입는다) | None
#   accent: 앞치마 위의 표식 한 점 (그을음·핏자국·먹물 — 생업의 자국)
VILLAGER_JOBS = {
    "farmer":       {"hat": "갓", "apron": (118, 106, 74), "accent": (86, 78, 56)},   # 농부 — 삿갓·짚 앞치마
    "fisherman":    {"hat": "갓", "apron": (92, 96, 94), "accent": (64, 70, 70)},     # 어부 — 도롱이
    "shepherd":     {"hat": "건", "apron": (168, 162, 148), "accent": (198, 194, 184)},  # 목자 — 흰 털천
    "butcher":      {"hat": "건", "apron": (176, 168, 152), "accent": (140, 52, 42)},  # 백정 — 흰 앞치마 + 핏자국
    "leatherworker": {"hat": "건", "apron": (108, 84, 62), "accent": (74, 58, 44)},   # 갖바치 — 가죽 앞치마
    "toolsmith":    {"hat": "건", "apron": (86, 74, 64), "accent": (40, 38, 36)},     # 야장 — 가죽 앞치마 + 그을음
    "weaponsmith":  {"hat": "건", "apron": (80, 70, 62), "accent": (36, 34, 33)},     # 병장 — 더 짙은 그을음
    "armorer":      {"hat": "건", "apron": (84, 80, 76), "accent": (44, 44, 44)},     # 갑장
    "mason":        {"hat": "건", "apron": (128, 124, 116), "accent": (168, 166, 160)},  # 석공 — 돌가루
    "librarian":    {"hat": "건", "apron": (96, 92, 84), "accent": (34, 32, 30)},     # 서생 — 유건 + 먹물
    "cartographer": {"hat": "건", "apron": (172, 164, 144), "accent": (70, 66, 60)},  # 여도(輿圖) — 종이빛
    "cleric":       {"hat": None, "apron": (110, 104, 96), "accent": (150, 58, 46)},  # 승(僧) — 맨머리 + 붉은 가사
    "fletcher":     {"hat": "건", "apron": (104, 94, 74), "accent": (60, 54, 44)},    # 궁장 — 화살대
    "nitwit":       {"hat": None, "apron": None, "accent": None},                     # 반편이 — 겉옷뿐
}

# 가슴 패(牌) 5단 — 바닐라의 돌·철·금·에메랄드·다이아 배지가 앉는 바로 그 자리(x10..13, y54..57).
# 무협의 격은 보석이 아니라 **패**로 말한다: 목·죽·동·은·옥.
VILLAGER_BADGES = {
    "stone":   ((74, 62, 48), (132, 112, 86)),      # 목패(木)
    "iron":    ((78, 84, 72), (140, 150, 132)),     # 죽패(竹)
    "gold":    ((112, 86, 46), (196, 158, 92)),     # 동패(銅)
    "emerald": ((118, 118, 112), (206, 206, 198)),  # 은패(銀)
    "diamond": ((96, 122, 112), (176, 208, 196)),   # 옥패(玉)
}


def villager_base():
    """바탕 64x64 — 살결·상투·두건·속옷. 코 박스는 **투명**(컬링)."""
    p, g, B = VILLAGER_SKIN, eblank(64, 64), VILLAGER_BOXES
    skin, hair, inner, trou, band = p["skin"], p["hair"], p["inner"], p["trouser"], p["band"]
    salt = 41

    def head(n, lx, ly, w, h):
        if n == "top":                                       # 정수리 — 틀어올린 상투
            d = max(abs(lx - 3.5), abs(ly - 3.5))
            return step(hair, 3.4 - d * 0.9 + octave(lx, ly, 1, salt, 0.5))
        if n == "back":
            return step(hair, 2.2 + octave(lx, ly, 1, salt + 1, 0.9) - (ly - 5) * 0.10)
        if n == "bottom":
            return step(skin, 1.0 + octave(lx, ly, 1, salt + 2, 0.5))        # 턱 밑
        if n in ("right", "left"):
            if ly <= 2:
                return step(hair, 2.4 + octave(lx, ly, 1, salt + 3, 0.8))    # 귀밑머리
            return step(skin, 2.4 + octave(lx, ly, 1, salt + 4, 0.7) - (ly - 5) * 0.12)
        # front — 얼굴 (8 x 10). 코는 상자가 아니라 **빛과 그늘**이다
        if ly <= 1:
            return step(hair, 2.6 + octave(lx, ly, 1, salt + 5, 0.8))        # 이마 위 머리칼
        if ly == 3 and lx in (1, 2, 5, 6):
            return step(hair, 1.2)                                           # 눈썹 — 먹 두 획
        if ly == 4 and lx in (1, 6):
            return p["eye_w"]
        if ly == 4 and lx in (2, 5):
            return p["eye"]
        if 5 <= ly <= 7 and lx in (3, 4):
            return step(skin, 4.2 - (ly - 5) * 0.3)                          # 콧대 — 융기(밝다)
        if 6 <= ly <= 7 and lx in (2, 5):
            return step(skin, 1.6)                                           # 콧방울 그늘
        if ly == 8 and 3 <= lx <= 4:
            return p["lip"]                                                  # 입
        return step(skin, 3.0 + octave(lx, ly, 1, salt + 6, 0.6) - (ly - 5) * 0.08)

    paint_box(g, B["head"], head)
    paint_box(g, B["nose"], lambda n, lx, ly, w, h: T)       # ★ 코 컬링 — 바닐라의 그 코를 버린다

    def hat(n, lx, ly, w, h):                                # 두건 — 이마를 감고 정수리를 덮는다
        if n == "top":
            return step(band, 2.8 + octave(lx, ly, 1, salt + 7, 0.7))
        if n == "bottom":
            return None
        if n == "back" and ly <= 4 and lx in (3, 4) and ly >= 3:
            return step(band, 1.8)                           # 뒤로 늘어뜨린 매듭 끈
        if ly <= 3:
            return step(band, 3.0 + octave(lx, ly, 1, salt + 8, 0.6) - ly * 0.3)
        return None                                          # 아래는 투명 (상자 머리 방지)

    paint_box(g, B["hat"], hat)

    def rim(n, lx, ly, w, h):                                # 삿갓 챙 — 둥근 짚 판 (갓 쓰는 생업만 보인다)
        if n not in ("front", "back"):
            return None
        d = ((lx - 7.5) ** 2 + (ly - 7.5) ** 2) ** 0.5
        if d > 7.6:
            return None                                      # 원 밖 — 투명 (네모 판이 아니라 갓이다)
        return step(p["straw"], 3.4 - d * 0.22 + octave(lx, ly, 1, salt + 9, 0.5))

    paint_box(g, B["hat_rim"], rim)

    def body(n, lx, ly, w, h):                               # 속저고리
        if n == "top":
            return step(inner, 3.6 + octave(lx, ly, 1, salt + 10, 0.6))
        if n == "bottom":
            return step(inner, 1.4 + octave(lx, ly, 1, salt + 11, 0.5))
        return step(inner, 2.8 + octave(lx, ly, 1, salt + 12, 0.7) - (ly - 6) * 0.07)

    paint_box(g, B["body"], body)
    paint_box(g, B["arm"], lambda n, lx, ly, w, h:
              step(skin, 2.2 + octave(lx, ly, 1, salt + 13, 0.5)) if ly >= 6 or n == "bottom"
              else step(inner, 2.9 + octave(lx, ly, 1, salt + 14, 0.6)))     # 소매 → 손
    paint_box(g, B["arms_cross"], lambda n, lx, ly, w, h:
              step(skin, 2.4 + octave(lx, ly, 1, salt + 15, 0.5)))           # 맞잡은 두 손
    paint_box(g, B["leg"], lambda n, lx, ly, w, h:
              step(trou, 0.8) if n == "bottom" else
              step(trou, 1.0 + octave(lx, ly, 1, salt + 16, 0.4)) if ly >= 11 else   # 짚신
              step(trou, 2.4 + octave(lx, ly, 1, salt + 17, 0.7) - ly * 0.04))
    paint_box(g, B["jacket"], lambda n, lx, ly, w, h: None)  # 겉옷은 type 층의 몫 — 여기선 비운다
    return g


def villager_type(key):
    """겉옷 64x64 — 두루마기. **늘 그려지는 층**이라 이 한 장이 주민의 인상을 정한다.
    머리(head·hat)는 칠하지 않는다 (바탕이 이미 사람이다) — 바닐라 type 층과 같은 규약."""
    dark, light, gomu = VILLAGER_ROBES[key]
    cloth = ramp(dark + (255,), light + (255,), 5)
    collar = mix(light + (255,), (255, 255, 255, 255), 0.55)   # 동정 — 흰 깃 (계단 밖 전용 획)
    shadow = mix(dark + (255,), (0, 0, 0, 255), 0.35)          # 섶이 겹친 골
    sash = gomu + (255,)                                       # 고름 — 수묵에 허락된 유일한 붉은 획
    g, B = eblank(64, 64), VILLAGER_BOXES
    salt = (zlib.crc32(key.encode()) & 0x3F) + 7

    # ── 몸통(body) 은 **보이지 않는다** ──
    # VillagerModel: jacket 은 body 의 자식이고 12 가 아니라 **18** 길이에 CubeDeformation(0.5)
    # 로 부풀려 있다 → 몸통 상자(8x12x6)를 겉옷 상자(8x18x6 +0.5)가 통째로 감싼다.
    # 즉 몸통에 그린 획은 겉옷 안에 갇혀 영원히 안 보인다 (종이인형으로 확인: 교임 깃이 사라졌다).
    # 그래서 **가슴의 모든 획(깃·고름)은 겉옷 앞면에 새긴다.** 몸통은 민 옷감으로만 채운다
    # (모델이 바뀌어 몸통이 드러나도 구멍이 나지 않게 하는 보험).
    paint_box(g, B["body"], lambda n, lx, ly, w, h:
              step(cloth, 2.8 + octave(lx, ly, 1, salt, 0.7)))

    def jacket(n, lx, ly, w, h):                              # 두루마기 — 18 길이의 긴 자락
        if n == "front":
            if ly <= 5:                                       # 가슴 — 교임(交衽) 깃이 여기서 읽힌다
                if lx == ly or lx == 7 - ly:
                    return collar                             # 동정 — 흰 깃 두 사선 (계단 밖 전용 획)
                if lx == ly + 1 or lx == 6 - ly:
                    return shadow                             # 섶이 겹친 골
            if ly in (6, 7) and 2 <= lx <= 5:
                return sash if lx in (3, 4) else shadow       # 고름 매듭 — 허락된 유일한 붉은 획
            if 8 <= ly <= 11 and lx == 4 and ly % 2 == 0:
                return sash                                   # 늘어뜨린 고름 끈 (점선 — 직선 금지)
            if 9 <= ly <= 16 and lx == 3 and ly % 3 != 0:
                return shadow                                 # 앞자락이 갈라진 선
        if n == "top":
            return step(cloth, 3.6 + octave(lx, ly, 1, salt + 4, 0.5))       # 어깨
        if n == "bottom":
            return step(cloth, 1.2 + octave(lx, ly, 1, salt + 5, 0.5))       # 자락 밑단
        v = 2.9 + octave(lx, ly, 1, salt + 6, 0.75) - (ly - 8) * 0.05
        if ly >= 15:
            v -= 0.7                                          # 밑단은 그늘에 잠긴다 (땅에 가깝다)
        return step(cloth, v)

    paint_box(g, B["jacket"], jacket)
    paint_box(g, B["arm"], lambda n, lx, ly, w, h:
              None if (ly >= 6 or n == "bottom") else         # 손은 바탕(살결)이 맡는다
              step(cloth, 2.9 + octave(lx, ly, 1, salt + 7, 0.7)))           # 소매
    paint_box(g, B["leg"], lambda n, lx, ly, w, h:
              None if ly >= 9 else                            # 정강이 아래는 바탕(바지·짚신)
              step(cloth, 2.5 + octave(lx, ly, 1, salt + 8, 0.6)))           # 두루마기가 덮은 넓적다리
    return g


def villager_job(key):
    """생업 표식 64x64 — 앞치마·갓·자국. profession != NONE 일 때만 겹친다."""
    j = VILLAGER_JOBS[key]
    g, B = eblank(64, 64), VILLAGER_BOXES
    p = VILLAGER_SKIN
    salt = (zlib.crc32(key.encode()) & 0x3F) + 71

    if j["hat"]:                                              # 갓·건 — 모자 박스 위쪽 띠
        cloth = p["straw"] if j["hat"] == "갓" else ramp((40, 38, 34, 255), (104, 98, 88, 255), 5)

        def hat(n, lx, ly, w, h):
            if n == "top":
                return step(cloth, 3.2 + octave(lx, ly, 1, salt, 0.6))
            if n == "bottom":
                return None
            if ly <= 3:
                return step(cloth, 3.0 + octave(lx, ly, 1, salt + 1, 0.6) - ly * 0.25)
            return None

        paint_box(g, B["hat"], hat)
        if j["hat"] == "갓":                                  # 삿갓 — 챙까지 (hat_rim 이 보인다)
            def rim(n, lx, ly, w, h):
                if n not in ("front", "back"):
                    return None
                d = ((lx - 7.5) ** 2 + (ly - 7.5) ** 2) ** 0.5
                if d > 7.6:
                    return None
                return step(p["straw"], 3.4 - d * 0.22 + octave(lx, ly, 1, salt + 2, 0.5))

            paint_box(g, B["hat_rim"], rim)

    if j["apron"]:                                            # 앞치마 — 겉옷 앞자락 위
        ap = ramp(mix(j["apron"] + (255,), (0, 0, 0, 255), 0.42), j["apron"] + (255,), 5)
        acc = j["accent"] + (255,)

        # 앞치마도 **겉옷 앞면**에만 새긴다 (몸통은 겉옷에 갇혀 안 보인다 — villager_type 주석 참조).
        # 가슴 위쪽(ly<=5)은 비운다: 교임 깃이 살아 있어야 무협의 옷으로 읽힌다 — 앞치마는 깃 아래로.
        def jacket(n, lx, ly, w, h):
            if n != "front" or not (6 <= ly <= 14):
                return None
            if lx in (0, 7):
                return None                                   # 옆구리는 겉옷이 보인다 (앞치마는 좁다)
            if h32(lx, ly, salt + 4) % 13 == 0:
                return acc                                    # 자국 — 그을음·핏자국·먹물·돌가루
            return step(ap, 3.0 + octave(lx, ly, 1, salt + 3, 0.7) - (ly - 10) * 0.05)

        paint_box(g, B["jacket"], jacket)
    return g


def villager_badge(key):
    """가슴 패 64x64 — 겉옷 앞면 x10..13 · y54..57 (바닐라 배지 좌표. 4x4 한 점)."""
    dark, light = VILLAGER_BADGES[key]
    shades = ramp(dark + (255,), light + (255,), 4)
    edge = mix(dark + (255,), (0, 0, 0, 255), 0.5)            # 패의 테 — 먹 (외곽이 있어야 물건이다)
    g = eblank(64, 64)
    for dy in range(4):
        for dx in range(4):
            x, y = 10 + dx, 54 + dy
            if dx in (0, 3) and dy in (0, 3):
                continue                                      # 네 귀 깎음 — 둥근 패
            if dx == 0 or dy == 0 or dx == 3 or dy == 3:
                g[y][x] = edge
            else:
                g[y][x] = step(shades, 2.6 - dy * 0.5 + dx * 0.3)   # 빛은 좌상단
    return g


# ─── 엔티티 등록부 — 무엇을 무엇으로 바쳤는가 (한 곳에서 읽힌다) ───
def write_entity_textures(sheet=False) -> int:
    """몹 징발 — 전역 치환. 오염 판정은 이 모듈 머리말에 기록했다."""
    out = {}
    out["zombie/zombie"] = human_rows(BANDIT, 11)        # 산적·두목·비무상대 (플러그인: ZOMBIE)
    out["zombie/husk"] = human_rows(RONIN, 23)           # 낭인 — 빈 인간형 채널 (플러그인 채택 대기)
    for coat in WOLF_COATS:
        for state in ("", "_tame", "_angry"):
            out[f"wolf/wolf{coat}{state}"] = wolf_rows(coat, state)
    out["cat/white"] = feline_rows(BAEK)                 # 백영묘 — 변종 1칸만 (오염 0)
    out["cat/ocelot"] = feline_rows(TIGER_CAT)           # 호랑이 대안 — 고양잇과 실루엣
    out["bear/polarbear"] = bear_rows()                  # 반달곰
    out["hoglin/hoglin"] = hoglin_rows()                 # 멧돼지
    out["illager/ravager"] = ravager_rows()              # 호랑이 (현재 채택)
    # ─── 마을 사람 — 네 층 전부 (하나라도 비우면 그 칸만 바닐라 로브가 튀어나온다) ───
    out["villager/villager"] = villager_base()
    for biome in VILLAGER_ROBES:
        out[f"villager/type/{biome}"] = villager_type(biome)
    for job in VILLAGER_JOBS:
        out[f"villager/profession/{job}"] = villager_job(job)
    for lv in VILLAGER_BADGES:
        out[f"villager/profession_level/{lv}"] = villager_badge(lv)
    for name, rows in out.items():
        write_png(ENTITY_DIR / f"{name}.png", rows)
    if sheet:
        write_entity_sheets(out)
    return len(out)


def write_entity_sheets(tex):
    """엔티티 확대 검수 시트 — run/texture-review/ (gitignore 대상. 레포에 남기지 않는다).

    texture_audit.py 는 블록·아이템·UI만 훑는다 (엔티티는 16x16이 아니라 훑지 않는다).
    엔티티는 **눈으로** 봐야 한다: UV를 틀리면 린트는 통과하고 몹만 기괴해진다.
    8텍셀 격자를 얹어 박스 경계를 함께 본다."""
    out_dir = ROOT / "run" / "texture-review"
    scale = 6
    for name, rows in tex.items():
        h, w = len(rows), len(rows[0])
        grid = []
        for y in range(h * scale):
            row = []
            for x in range(w * scale):
                px = rows[y // scale][x // scale]
                if px[3] == 0:
                    px = (64, 32, 40, 255)                       # 투명 = 팥색 (알파 확인용)
                if (x // scale) % 8 == 0 and x % scale == 0:
                    px = (200, 40, 40, 255)                      # UV 눈금 8텍셀
                if (y // scale) % 8 == 0 and y % scale == 0:
                    px = (200, 40, 40, 255)
                row.append(px)
            grid.append(row)
        write_png(out_dir / f"entity_{name.replace('/', '_')}.png", grid)
    print(f"  엔티티 검수 시트 {len(tex)}장 → {out_dir.relative_to(ROOT)}/entity_*.png ({scale}배 + UV 눈금)")


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
QI_TEX_DIR = PACK / "assets" / "honcheon" / "textures" / "qi"     # 기의 획 (item/ 아래가 아니다 —
ULT_TEX_DIR = PACK / "assets" / "honcheon" / "textures" / "ult"   #  축 ⑥ 외곽선 폐합은 아이콘의 규율이지
MOB_TEX_DIR = PACK / "assets" / "honcheon" / "textures" / "mob"   #  반투명 획·짐승 가죽의 규율이 아니다)


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


def _euler_to_mat(r):
    """MC 규약 — JOML rotationXYZ: 점은 X → Y → Z 순으로 돈다 ⇒ R = Rz·Ry·Rx."""
    return _mm(_rot("z", r[2]), _mm(_rot("y", r[1]), _rot("x", r[0])))


def _mat_to_euler(m):
    """R = Rz(γ)·Ry(β)·Rx(α) 역산 → [α, β, γ] (도). 짐벌 락(β=±90°)도 유효한 한 쌍을 돌려준다."""
    if abs(m[2][0]) > 0.999999:                       # β = ∓90° — α 와 γ 가 한 축으로 겹친다
        beta = -90.0 if m[2][0] > 0 else 90.0
        return [0.0, beta, math.degrees(math.atan2(-m[0][1], m[1][1]))]
    return [math.degrees(math.atan2(m[2][1], m[2][2])),
            math.degrees(math.asin(-m[2][0])),
            math.degrees(math.atan2(m[1][0], m[0][0]))]


def _check(r, m):
    """역산 검산 — 오일러로 되돌린 행렬이 원본과 같은가 (다르면 병기가 엉뚱한 데를 본다)."""
    back = _euler_to_mat(r)
    err = max(abs(back[i][j] - m[i][j]) for i in range(3) for j in range(3))
    if err > 1e-6:
        raise ValueError(f"오일러 역산 불일치 (오차 {err:.2e}) — 회전 합성이 깨졌다")
    return [round(v, 3) + 0.0 for v in r]


# 바닐라 item/handheld + item/generated 의 display (1.21.11 client jar 에서 그대로 옮겼다).
# gui 만 우리 값이다: 바닐라는 평면 스프라이트라 정면(회전 0)이 정답이지만, 3D 병기는
# 정면에서 보면 두께가 사라져 **예전과 똑같은 아이콘**이 된다. 살짝 틀어 두께를 보인다.
_VANILLA_DISPLAY = {
    "thirdperson_righthand": ([0, -90, 55], [0, 4.0, 0.5], 0.85, "hand"),
    "thirdperson_lefthand": ([0, 90, -55], [0, 4.0, 0.5], 0.85, "hand"),
    "firstperson_righthand": ([0, -90, 25], [1.13, 3.2, 1.13], 0.68, "hand"),
    "firstperson_lefthand": ([0, 90, -25], [1.13, 3.2, 1.13], 0.68, "hand"),
    "ground": ([0, 0, 0], [0, 2, 0], 0.5, "gui"),
    "head": ([0, 180, 0], [0, 13, 7], 1.0, "gui"),
    "fixed": ([0, 180, 0], [0, 0, 0], 1.0, "gui"),
    "gui": ([12, -28, 0], [0, 0, 0], 1.0, "gui"),   # 3/4 부감 — 날의 두께와 코등이가 보인다
}
_PRE_Z = 45.0        # 모델(+X) → 바닐라 스프라이트(+45°) 로 맞추는 선회전


def _display(extent):
    """병기 한 자루의 display 절. extent = 모델의 최대 변(px) — 긴 병기는 줄여서 담는다.
    창(2.1m)을 손 크기 그대로 GUI 칸에 넣으면 칸을 뚫고 나간다. 줄이는 것은 **그림뿐**이다 —
    궤적에 실리는 것은 NONE 변환의 날것이므로 창은 여전히 2.1m 다 (거짓말이 아니다)."""
    k = {"gui": min(1.0, 15.0 / extent), "hand": min(1.0, 26.0 / extent)}
    out = {}
    for mode, (rv, tv, sv, kind) in _VANILLA_DISPLAY.items():
        m = _mm(_euler_to_mat(rv), _rot("z", _PRE_Z))
        s = round(sv * k[kind], 4)
        out[mode] = {"rotation": _check(_mat_to_euler(m), m),
                     "translation": [round(v, 3) for v in tv],
                     "scale": [s, s, s]}
    return out


# ─── UV — 아이콘 격자에서 부위별 대표 픽셀을 뽑는다 ─────────────────────────
# 대체 사슬: 그 등급의 아이콘에 없는 코드(범철엔 고리도 수실도 없다)는 이웃 재질로 떨어진다.
# 마지막 보루는 K(먹 외곽선) — outline() 이 반드시 두르므로 **없을 수가 없다**.
_UV_CHAIN = {
    "H": "HLBSD", "L": "LHBSD", "B": "BLHSD", "S": "SBLDH", "D": "DSBLH",
    "G": "Ggf", "g": "gGf", "f": "fgG",
    "W": "WwxX", "w": "wWxX", "x": "xwWX", "X": "XxwW",
    "R": "ReGg", "e": "eRgG", "t": "tTGg", "T": "TtGg",
    "m": "mMHL", "M": "MmHL",
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
}


def _bx(x0, y0, z0, x1, y1, z1, mat, rot=None):
    """상자 하나. rot = (축, 각, 원점) — MC 는 원소당 한 축 · {0, ±22.5, ±45} 만 받는다."""
    e = {"from": [x0, y0, z0], "to": [x1, y1, z1], "_mat": mat}
    if rot:
        axis, ang, org = rot
        if ang not in (-45, -22.5, 0, 22.5, 45):
            raise ValueError(f"허용되지 않는 원소 회전각 {ang} (MC 는 0·±22.5·±45 만 받는다)")
        e["rotation"] = {"origin": list(org), "axis": axis, "angle": ang}
    return e


def _bake(elems, pos, mab=False):
    """부위 코드를 UV 로 굳힌다. 마병은 날의 속(core)이 **혈조**가 된다 — 형태는 같고 피가 밴다."""
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


# ═══ 등급은 **형체로도** 오른다 ═══════════════════════════════════════════════
# 검수 축 ⑩(등급 회색조 변별)은 아이콘을 잰다. 그러나 3D 는 아이콘이 아니다 —
# 손에 든 병기·궤적에 실린 병기는 **실루엣으로만** 읽힌다. 색은 거기서 아무 말도 못 한다.
# 그래서 등급은 세 가지 형체로 갈린다 (아이콘의 문법을 3D 로 옮긴 것이다):
#   ① 고리(鐶) — 자루에 감긴 금속 테. 0 / 1 / 2 / 3 개가 **튀어나온 덩이**로 보인다.
#   ② 날의 길이·마름 — 범철은 뭉툭하고 짧다. 오를수록 길어지고 끝이 날카로워진다.
#   ③ 물미·수실 — 신병만 자루 끝에 수실이 늘어진다 (움직이면 흔들리는 유일한 부위).
# 마병은 **위가 아니라 밖**이다: 고리가 없고(계보가 다르다) · 날에 톱니가 돋고 ·
#   혈조가 피로 찬다. 형체만 보고도 "저것은 정파의 쇠가 아니다"가 읽혀야 한다.
_GRADE_FORM = {          # 등급 → (고리 수, 날 길이 가산 px, 수실, 마병)
    "beomcheol": (0, 0.0, False, False),
    "jeongryeon": (1, 1.0, False, False),
    "bobyeong": (2, 2.0, False, False),
    "sinbyeong": (3, 3.0, True, False),
    "mabyeong": (0, 2.0, False, True),
}


def _grip(x0, x1, rings, tassel, mab, r=1.0):
    """자루 한 벌 — 감기 + 등급 고리 + 물미 + (신병) 수실 + (마병) 혈적 낙인.
    자루는 계열이 아니라 **등급이 말하는 부위**다 (아이콘의 _hilt 와 같은 판단)."""
    e = [_bx(x0 + 1.0, 8 - r, 8 - r, x1, 8 + r, 8 + r, "grip"),
         _bx(x0 - 0.2, 8 - r - 0.7, 8 - r - 0.7, x0 + 1.0, 8 + r + 0.7, 8 + r + 0.7, "fit")]  # 물미
    if rings:                                   # 고리 — 자루를 감은 테가 도드라진다 (등급의 눈금)
        gap = (x1 - x0 - 1.6) / (rings + 1)
        for i in range(rings):
            gx = x0 + 1.4 + gap * (i + 1)
            e.append(_bx(gx, 8 - r - 0.45, 8 - r - 0.45, gx + 0.7, 8 + r + 0.45, 8 + r + 0.45, "ring"))
    if tassel:                                  # 수실 — 신병만. 자루 끝에서 아래로 늘어진다
        e += [_bx(x0 - 0.9, 8 - r - 1.6, 8 - 0.5, x0 + 0.3, 8 - r - 0.2, 8 + 0.5, "tassel"),
              _bx(x0 - 0.9, 8 - r - 2.8, 8 - 0.3, x0 - 0.1, 8 - r - 1.4, 8 + 0.3, "tassel")]
    if mab:                                     # 혈적 — 자루 끝에 밴 낙인
        e.append(_bx(x0 - 0.35, 8 - 0.6, 8 - 0.6, x0 + 0.15, 8 + 0.6, 8 + 0.6, "blood"))
    return e


def _teeth(x0, x1, ylo, n=3):
    """마병의 톱니 — 날 아래로 돋은 이빨. **실루엣이 달라진다**: 형체만으로 마병이 읽힌다."""
    step = (x1 - x0) / (n + 1)
    return [_bx(x0 + step * (i + 1), ylo - 1.0, 7.7, x0 + step * (i + 1) + 1.1, ylo + 0.2, 8.3, "blood")
            for i in range(n)]


def _blade(x0, x1, half, mab, single=False, thin=0.55, core=0.85, taper=2.0):
    """날 — 세 겹으로 눕힌 단면(인 / 속 / 인). 속이 두껍고 인이 얇다 ⇒ 마름모 단면이 선다.
    single=True 는 한날(도): 아래가 인이 아니라 **척(脊)** 이다 — 두껍고 어둡다.
    끝(taper)은 좁혀서 마감한다. 뭉툭한 끝은 몽둥이지 칼이 아니다."""
    xt = x1 - taper
    e = [_bx(x0, 8 - 0.35, 8 - core, xt, 8 + 0.35, 8 + core, "core"),                 # 속(혈조 자리)
         _bx(x0, 8 + 0.35, 8 - thin, xt, 8 + half, 8 + thin, "edge2" if not single else "edge1")]
    if single:                                                                        # 척 — 도의 등
        e.append(_bx(x0, 8 - half, 8 - core - 0.2, xt, 8 - 0.35, 8 + core + 0.2, "spine"))
    else:
        e.append(_bx(x0, 8 - half, 8 - thin, xt, 8 - 0.35, 8 + thin, "edge2"))
    e.append(_bx(xt, 8 - half * 0.5, 8 - thin, x1, 8 + half * 0.55, 8 + thin, "edge2"))  # 칼끝
    if mab:
        e += _teeth(x0 + 1.5, xt - 1.0, 8 - half)
    return e


def sword_rig(rings, blen, tassel, mab):
    """검(劍) — 곧은 양날. 코등이가 **날에 수직으로 길게 뻗은 십자**다 (도와 갈리는 지점)."""
    e = _blade(6.5, 15.5 + blen, 1.6, mab)
    e.append(_bx(5.4, 4.0, 6.7, 6.5, 12.0, 9.3, "fit"))            # 긴 가로대 코등이
    return e + _grip(0.6, 5.4, rings, tassel, mab)


def dao_rig(rings, blen, tassel, mab):
    """도(刀) — 한날. 등이 두껍고 배가 부르며, 코등이는 **뭉툭한 원반**이다.
    끝 두 마디를 22.5° 들어 올려 휨(反)을 만든다 — 곡률은 회전으로 얻는다."""
    e = _blade(6.8, 13.2 + blen, 1.9, mab, single=True, core=1.05, taper=1.6)
    e.append(_bx(13.2 + blen, 8 - 1.2, 8 - 0.55, 16.4 + blen, 8 + 1.5, 8 + 0.55, "edge1",
                 rot=("z", 22.5, (13.2 + blen, 8, 8))))            # 휘어 오른 끝
    e.append(_bx(5.8, 5.9, 6.5, 6.8, 10.1, 9.5, "fit"))            # 원반 호수
    return e + _grip(0.9, 5.8, rings, tassel, mab, r=1.05)


def dagger_rig(rings, blen, tassel, mab):
    """비수(匕首) — **자루가 날보다 길다**. 그 비율이 곧 정체다 (작은 검이 아니다)."""
    e = _blade(7.0, 12.6 + blen, 1.3, mab, thin=0.45, core=0.7, taper=1.8)
    e.append(_bx(6.3, 6.1, 6.9, 7.0, 9.9, 9.1, "fit"))             # 짧은 코등이
    return e + _grip(0.9, 6.3, rings, tassel, mab, r=0.9)


def spear_rig(rings, blen, tassel, mab):
    """창(槍) — 긴 자루 + 좁은 창날 + **홍영**(紅纓, 창날 밑 붉은 술 — 등급이 아니라 계열의 표식).
    자루가 날보다 훨씬 가늘다: 그 비율이 검과 갈리는 지점이다 (아이콘 축 ⑨의 판단 그대로)."""
    e = _blade(20.0, 26.0 + blen, 1.1, mab, thin=0.4, core=0.6, taper=2.2)
    e += [_bx(-9.0, 8 - 0.75, 8 - 0.75, 19.2, 8 + 0.75, 8 + 0.75, "grip"),   # 얇고 긴 자루
          _bx(18.6, 8 - 1.0, 8 - 1.0, 20.4, 8 + 1.0, 8 + 1.0, "fit"),        # 물미 (창날 목)
          _bx(16.6, 8 - 1.7, 8 - 0.9, 18.6, 8 + 1.7, 8 + 0.9, "tassel")]     # 홍영
    if rings:
        for i in range(rings):
            gx = -7.0 + i * 2.2
            e.append(_bx(gx, 8 - 1.2, 8 - 1.2, gx + 0.7, 8 + 1.2, 8 + 1.2, "ring"))
    e.append(_bx(-9.9, 8 - 1.15, 8 - 1.15, -9.0, 8 + 1.15, 8 + 1.15, "fit"))  # 자루 끝 물미
    if tassel:
        e.append(_bx(-11.4, 8 - 1.4, 8 - 0.5, -9.9, 8 + 0.2, 8 + 0.5, "tassel"))
    if mab:
        e.append(_bx(-9.6, 8 - 0.7, 8 - 0.7, -9.1, 8 + 0.7, 8 + 0.7, "blood"))
    return e


def gauntlet_rig(rings, blen, tassel, mab):
    """권갑(拳甲) — 날이 없다. **손에 끼는 물건**으로 읽혀야 한다.
    마디(관절)가 +X 로 돋는다 — 궤적에 실리면 그 마디가 앞장선다 (주먹이 나가는 방향).
    마디 높이가 들쭉날쭉해야 손이다: 나란하면 성가퀴(망루)가 된다."""
    e = [_bx(2.0, 5.6, 4.6, 10.5, 10.4, 11.4, "fit"),               # 손등 판
         _bx(1.2, 6.2, 5.2, 2.0, 9.8, 10.8, "grip")]                # 손목 띠
    for i, h in enumerate((1.3, 2.1, 1.7, 1.0)):                    # 마디 넷 — 가운데가 가장 높다
        z = 5.1 + i * 1.6
        e.append(_bx(10.5, 6.6, z, 10.5 + 1.4 + h, 9.6, z + 1.35, "fit" if not mab or i != 1 else "blood"))
    e += [_bx(2.6, 10.4, 5.4, 9.6, 11.2, 10.6, "ring") for _ in range(1)] if rings else []
    if rings >= 2:
        e.append(_bx(3.0, 4.8, 5.6, 9.2, 5.6, 10.4, "ring"))
    if rings >= 3:
        e.append(_bx(2.4, 6.4, 3.6, 8.4, 9.6, 4.6, "ring"))         # 엄지 쪽 덧댐
    if tassel:
        e.append(_bx(0.4, 4.4, 7.0, 1.6, 6.2, 9.0, "tassel"))
    if mab:
        e.append(_bx(4.8, 10.4, 7.2, 7.2, 11.4, 8.8, "blood"))
    return e


def bu_rig(rings, blen, tassel, mab):
    """부(斧) — 가장 느리고 한 방이 가장 무겁다. 자루 끝에 **넓은 날(bit)** 이 얹힌다.
    날을 45° 로 눕힌 두 겹으로 세워 도끼의 부채꼴을 만든다."""
    e = [_bx(-6.0, 8 - 0.85, 8 - 0.85, 9.0, 8 + 0.85, 8 + 0.85, "grip")]     # 두꺼운 자루
    e += [_bx(8.4, 8 - 1.5, 8 - 1.2, 11.0, 8 + 3.4, 8 + 1.2, "fit"),         # 날 목
          _bx(10.6, 8 + 0.4, 8 - 1.0, 13.2, 8 + 6.4, 8 + 1.0, "edge1"),      # 날 몸
          _bx(12.4, 8 + 2.2, 8 - 0.75, 15.6 + blen, 8 + 6.2, 8 + 0.75, "edge1",
              rot=("z", -22.5, (12.4, 8 + 4.0, 8))),                          # 부채꼴 위
          _bx(10.6, 8 - 3.0, 8 - 1.0, 13.6, 8 + 0.6, 8 + 1.0, "edge1",
              rot=("z", 22.5, (10.6, 8, 8)))]                                 # 부채꼴 아래
    if mab:
        e += _teeth(11.0, 14.2, 8 + 6.2, n=2)
    for i in range(rings):
        gx = -4.4 + i * 2.4
        e.append(_bx(gx, 8 - 1.3, 8 - 1.3, gx + 0.75, 8 + 1.3, 8 + 1.3, "ring"))
    e.append(_bx(-6.9, 8 - 1.2, 8 - 1.2, -6.0, 8 + 1.2, 8 + 1.2, "fit"))
    if tassel:
        e.append(_bx(-8.3, 8 - 1.5, 8 - 0.5, -6.9, 8 + 0.1, 8 + 0.5, "tassel"))
    if mab:
        e.append(_bx(-6.6, 8 - 0.7, 8 - 0.7, -6.1, 8 + 0.7, 8 + 0.7, "blood"))
    return e


def gyeom_rig(rings, blen, tassel, mab):
    """겸(鎌) — 걸어 채는 날. 날이 자루에서 **직각으로 꺾여** 갈고리를 이룬다.
    (SkillDisplay 는 겸의 roll 을 음수로 준다 — 걸어 당기는 병기이므로 거꾸로 감긴다.)"""
    e = [_bx(-1.0, 8 - 0.8, 8 - 0.8, 7.0, 8 + 0.8, 8 + 0.8, "grip")]
    e += [_bx(6.4, 8 - 1.0, 8 - 1.0, 8.2, 8 + 1.8, 8 + 1.0, "fit"),
          _bx(7.4, 8 + 1.2, 8 - 0.5, 11.4 + blen, 8 + 2.9, 8 + 0.5, "edge1",
              rot=("z", 22.5, (7.4, 8 + 1.2, 8))),                            # 꺾여 오르는 날
          _bx(10.4 + blen, 8 + 2.2, 8 - 0.45, 14.0 + blen, 8 + 3.6, 8 + 0.45, "edge1",
              rot=("z", -45, (10.4 + blen, 8 + 2.8, 8)))]                     # 걸어 채는 갈고리 끝
    if mab:
        e += _teeth(8.0, 11.0 + blen, 8 + 1.3, n=2)
    for i in range(rings):
        gx = 0.4 + i * 1.9
        e.append(_bx(gx, 8 - 1.25, 8 - 1.25, gx + 0.7, 8 + 1.25, 8 + 1.25, "ring"))
    e.append(_bx(-1.9, 8 - 1.15, 8 - 1.15, -1.0, 8 + 1.15, 8 + 1.15, "fit"))
    if tassel:
        e.append(_bx(-3.2, 8 - 1.4, 8 - 0.5, -1.9, 8 + 0.1, 8 + 0.5, "tassel"))
    if mab:
        e.append(_bx(-1.6, 8 - 0.7, 8 - 0.7, -1.1, 8 + 0.7, 8 + 0.7, "blood"))
    return e


def wolasan_rig(rings, blen, tassel, mab):
    """월아산(月牙鏟) — 승려의 장병기. 한쪽 끝에 **초승달**(月牙), 반대 끝에 삽날.
    양 끝이 다르다는 것이 이 병기의 전부다 (봉도 창도 아니다)."""
    e = [_bx(-13.0, 8 - 0.8, 8 - 0.8, 15.0, 8 + 0.8, 8 + 0.8, "grip")]         # 긴 봉
    e += [_bx(14.4, 8 - 1.2, 8 - 1.1, 16.4, 8 + 1.2, 8 + 1.1, "fit"),
          _bx(16.0, 8 - 1.0, 8 - 0.5, 19.4 + blen, 8 + 1.0, 8 + 0.5, "edge2"),  # 달의 몸
          _bx(18.2, 8 + 0.6, 8 - 0.45, 21.6 + blen, 8 + 4.6, 8 + 0.45, "edge2",
              rot=("z", -45, (18.2, 8 + 0.6, 8))),                              # 초승달 위 뿔
          _bx(18.2, 8 - 4.6, 8 - 0.45, 21.6 + blen, 8 - 0.6, 8 + 0.45, "edge2",
              rot=("z", 45, (18.2, 8 - 0.6, 8)))]                               # 초승달 아래 뿔
    e += [_bx(-15.4, 8 - 1.1, 8 - 1.05, -13.0, 8 + 1.1, 8 + 1.05, "fit"),
          _bx(-15.6 - blen * 0.4, 8 - 2.6, 8 - 0.5, -15.4, 8 + 2.6, 8 + 0.5, "edge2")]  # 반대편 삽날
    if mab:
        e += _teeth(16.6, 19.0, 8 - 1.0, n=2)
    for i in range(rings):
        gx = -10.5 + i * 2.6
        e.append(_bx(gx, 8 - 1.25, 8 - 1.25, gx + 0.75, 8 + 1.25, 8 + 1.25, "ring"))
    if tassel:
        e.append(_bx(-12.4, 8 - 2.4, 8 - 0.5, -11.0, 8 - 0.9, 8 + 0.5, "tassel"))
    if mab:
        e.append(_bx(-13.6, 8 - 0.7, 8 - 0.7, -13.1, 8 + 0.7, 8 + 0.7, "blood"))
    return e


def gu_rig(rings, blen, tassel, mab):
    """구(鉤) — 걸고 당긴다. 곧은 날 **끝이 갈고리로 되꺾인다**.
    코등이가 초승달(호수)이라 손을 지키면서 상대의 날을 건다 — 구의 정체."""
    e = _blade(6.6, 14.4 + blen, 1.35, mab, thin=0.5, core=0.75, taper=1.4)
    e += [_bx(13.6 + blen, 8 + 0.9, 8 - 0.5, 16.8 + blen, 8 + 2.3, 8 + 0.5, "edge2",
              rot=("z", -45, (13.6 + blen, 8 + 0.9, 8)))]                        # 되꺾인 갈고리
    e.append(_bx(5.4, 5.4, 6.6, 6.6, 10.6, 9.4, "fit",
                 rot=("z", 22.5, (6.0, 8, 8))))                                  # 초승달 호수
    return e + _grip(0.8, 5.4, rings, tassel, mab, r=0.95)


WEAPON_RIG = {
    "sword": sword_rig, "dao": dao_rig, "spear": spear_rig,
    "gauntlet": gauntlet_rig, "dagger": dagger_rig,
    "bu": bu_rig, "gyeom": gyeom_rig, "wolasan": wolasan_rig, "gu": gu_rig,
}


def weapon_model_3d(series, grade, grid):
    """병기 한 자루의 3D 모델. 텍스처는 **그 자루의 아이콘 PNG 그대로** (새 PNG 를 굽지 않는다)."""
    rings, blen, tassel, mab = _GRADE_FORM[grade]
    elems, ext = _center(_bake(WEAPON_RIG[series](rings, blen, tassel, mab), _uv_index(grid), mab))
    return {
        "textures": {"0": f"honcheon:item/weapon/{series}_{grade}",
                     "particle": f"honcheon:item/weapon/{series}_{grade}"},
        "elements": elems,
        "display": _display(ext),
        "gui_light": "front",
    }


# ═══════════════════════════════════════════════════════════════════════════
# 무공의 획 9종 — config/skill_motion.yml display.models 의 【청구서】
#
# 【치수 계약】 엔진이 size 를 Transformation.scale 로 **그대로** 곱한다 (SkillDisplay).
#   ⇒ 모델은 1×1×1 단위 상자를 **가득 채워야** 한다. 원소가 z 를 1px 만 차지하면
#     최종 두께는 0.04m × 1/16 = 0.0025m — 보이지 않는다. 그래서 획의 원소는 0..16 을 채우고,
#     **모양은 텍스처의 알파가 판다** (두께 0.04m 짜리 판에 기하를 새기는 것은 낭비다).
# 【채색】 수묵 — 흰 획의 4단(속이 밝고 끝이 스민다). 예외는 혈해만리의 혈조(血潮) 하나뿐이다.
# ═══════════════════════════════════════════════════════════════════════════
QI_CORE = (250, 250, 248, 235)   # 획의 속 — 가장 밝다
QI_MID = (214, 214, 210, 205)
QI_DIM = (150, 152, 158, 155)
QI_EDGE = (92, 94, 102, 110)     # 스미는 끝 — 종이에 번진 먹
BT_CORE = (216, 68, 56, 232)     # 혈조 — **유일한 채색 예외** (마공의 혈점: 예외 자체가 정보다)
BT_MID = (170, 42, 34, 202)
BT_DIM = (114, 27, 24, 158)
BT_EDGE = (62, 16, 16, 112)
QI_STEPS = (QI_EDGE, QI_DIM, QI_MID, QI_CORE)
BT_STEPS = (BT_EDGE, BT_DIM, BT_MID, BT_CORE)

QI_TEX = 32                      # 32x32 — 16x16 이 아니므로 검수의 이음매(축 ⑦) 대상이 아니다
                                 # (획은 타일이 아니다. 제 복사본과 이어 붙을 일이 없다)


def _qi_blank():
    return [[T] * QI_TEX for _ in range(QI_TEX)]


def _ink(g, x, y, depth, steps=QI_STEPS):
    """depth 0(가장자리) … 1(속) → 4단 먹. 붓은 속이 진하고 끝이 스민다."""
    if 0 <= x < QI_TEX and 0 <= y < QI_TEX:
        g[y][x] = steps[max(0, min(3, int(depth * 4)))]


def _stroke(g, thick, bow, ybase=None, x0=1, x1=QI_TEX - 2, steps=QI_STEPS):
    """한 획 — 중심선을 따라 두께가 차오르고 끝에서 마른다.
    thick(t) 은 걸음 t(0..1) 의 두께, bow 는 위로 휘는 깊이 (초승달의 배)."""
    ybase = QI_TEX * 0.62 if ybase is None else ybase
    for x in range(x0, x1 + 1):
        t = (x - x0) / max(1, x1 - x0)
        th = thick(t)
        if th <= 0.4:
            continue
        yc = ybase - bow * math.sin(math.pi * t)
        for y in range(int(yc - th), int(yc + th) + 1):
            d = abs(y + 0.5 - yc) / th
            if d <= 1.0:
                _ink(g, x, y, 1.0 - d, steps)


def _disc(g, cx, cy, r, steps=QI_STEPS, r_in=0.0):
    for y in range(QI_TEX):
        for x in range(QI_TEX):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if r_in <= d <= r:
                edge = min(r - d, d - r_in) if r_in else (r - d)
                _ink(g, x, y, min(1.0, edge / 3.0), steps)


def qi_textures():
    """획 9장 — 그림은 알파가 판다 (형체는 판때기, 모양은 이 PNG)."""
    tex = {}

    g = _qi_blank()                                   # 검기 — 초승달 한 획. 안쪽이 두껍고 끝이 가늘다
    _stroke(g, lambda t: 5.5 * math.sin(math.pi * t) ** 0.7, bow=6.0)
    tex["qi/blade_arc"] = g

    g = _qi_blank()                                   # 강기 — 같은 형태를 **눌러** 두껍게 (뭉개지지 않는다)
    _stroke(g, lambda t: 9.5 * math.sin(math.pi * t) ** 0.55, bow=5.0)
    tex["qi/blade_heavy"] = g

    g = _qi_blank()                                   # 검기 비(飛) — 앞이 뾰족하고 뒤로 꼬리가 늘어진다
    _stroke(g, lambda t: 7.0 * (t ** 1.3) * ((1 - t) ** 0.55) / 0.30, bow=0.0, ybase=QI_TEX / 2)
    tex["qi/bolt_edge"] = g

    g = _qi_blank()                                   # 강기 포 — 말뚝의 속(기하가 형체를 만든다)
    _disc(g, QI_TEX / 2, QI_TEX / 2, 15.0)
    tex["qi/bolt_lance"] = g

    g = _qi_blank()                                   # 호신 조각 — 세로로 선 얇은 방패 한 장
    for y in range(2, QI_TEX - 2):
        t = (y - 2) / (QI_TEX - 5)
        w = 9.0 * math.sin(math.pi * t) ** 0.45
        for x in range(int(QI_TEX / 2 - w), int(QI_TEX / 2 + w) + 1):
            _ink(g, x, y, 1.0 - abs(x + 0.5 - QI_TEX / 2) / max(1.0, w))
    tex["qi/guard_shard"] = g

    g = _qi_blank()                                   # 매화 — 다섯 잎 (오의: 잊지 못할 형체)
    for k in range(5):
        a = -math.pi / 2 + k * 2 * math.pi / 5
        px_, py_ = QI_TEX / 2 + 8.2 * math.cos(a), QI_TEX / 2 + 8.2 * math.sin(a)
        _disc(g, px_, py_, 6.2)
    _disc(g, QI_TEX / 2, QI_TEX / 2, 4.0)             # 꽃술
    tex["ult/plum_bloom"] = g

    g = _qi_blank()                                   # 태극 — 원반. 음양이 **밝기**로 갈린다 (색이 아니다)
    _disc(g, QI_TEX / 2, QI_TEX / 2, 15.0, r_in=11.5)          # 바깥 테
    for y in range(QI_TEX):
        for x in range(QI_TEX):
            dx, dy = x + 0.5 - QI_TEX / 2, y + 0.5 - QI_TEX / 2
            if math.hypot(dx, dy) > 11.0:
                continue
            up = math.hypot(dx, dy + 5.5) < 5.5      # 위 물고기
            dn = math.hypot(dx, dy - 5.5) < 5.5      # 아래 물고기
            yin = (dy < 0) if not (up or dn) else up
            _ink(g, x, y, 0.95 if yin else 0.28)
    tex["ult/taegeuk_disc"] = g

    g = _qi_blank()                                   # 제왕의 칼 — 하늘에서 **떨어진다** (길이축 Y)
    for y in range(1, QI_TEX - 1):
        t = (y - 1) / (QI_TEX - 3)
        w = 6.5 * (1 - t) ** 0.65 if t > 0.18 else 3.0 + 20 * (0.18 - t)   # 위=자루 / 아래=칼끝
        for x in range(int(QI_TEX / 2 - w), int(QI_TEX / 2 + w) + 1):
            _ink(g, x, y, 1.0 - abs(x + 0.5 - QI_TEX / 2) / max(1.0, w))
    tex["ult/emperor_edge"] = g

    g = _qi_blank()                                   # 혈해만리 — 바닥을 훑는 붉은 파문 (채색 예외)
    _disc(g, QI_TEX / 2, QI_TEX / 2, 15.5, steps=BT_STEPS, r_in=9.0)
    _disc(g, QI_TEX / 2, QI_TEX / 2, 6.5, steps=BT_STEPS)
    tex["ult/blood_tide"] = g
    return tex


_HIDE = [0.0, 0.0, 0.25, 0.25]     # 완전 투명한 구석 한 칸 — 안 보여야 할 면이 무는 자리


def _plate(axis):
    """단위 상자를 가득 채운 **판** 하나. 그림이 실리는 두 면만 텍스처를 물고 나머지는 투명을 문다.
    (두께 0.04m 짜리 옆면에 그림을 넣는 것은 낭비다 — 어차피 종잇장의 옆구리다.)"""
    show = {"z": ("north", "south"), "y": ("up", "down"), "x": ("east", "west")}[axis]
    faces = {}
    for f in ("north", "south", "east", "west", "up", "down"):
        faces[f] = {"texture": "#0", "uv": [0, 0, 16, 16] if f in show else _HIDE}
    return [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": faces}]


def _cross():
    """교차한 판 두 장 — 바닐라 화초의 문법. 어느 각도에서 보아도 꽃이 핀 것으로 보인다."""
    out = []
    for frm, to, show in (([0, 0, 7.4], [16, 16, 8.6], ("north", "south")),
                          ([7.4, 0, 0], [8.6, 16, 16], ("east", "west"))):
        faces = {f: {"texture": "#0", "uv": [0, 0, 16, 16] if f in show else _HIDE}
                 for f in ("north", "south", "east", "west", "up", "down")}
        out.append({"from": frm, "to": to, "faces": faces})
    return out


def _lance():
    """강기 포 — **말뚝**이다. 판이 아니라 덩이라서 기하가 형체를 만든다 (2.2 × 0.7 × 0.7m).
    뒤가 뭉툭하고 앞이 뾰족하다: 베는 것이 아니라 **뚫는 것**이라고 형체가 말한다."""
    f = lambda: {c: {"texture": "#0", "uv": [0, 0, 16, 16]}
                 for c in ("north", "south", "east", "west", "up", "down")}
    return [{"from": [0, 3.5, 3.5], "to": [7, 12.5, 12.5], "faces": f()},       # 뒤 — 뭉툭한 밑동
            {"from": [7, 2.0, 2.0], "to": [12, 14.0, 14.0], "faces": f()},      # 몸 — 가장 굵다
            {"from": [12, 5.0, 5.0], "to": [16, 11.0, 11.0], "faces": f()}]     # 앞 — 뚫는 끝


# 획 9종 = (키, 텍스처, 기하). '길이축 +X' 는 판의 가로(u)가 이미 X 다 (텍스처가 그렇게 그려졌다).
QI_MODELS = {
    "qi/blade_arc": _plate("z"), "qi/blade_heavy": _plate("z"), "qi/bolt_edge": _plate("z"),
    "qi/bolt_lance": _lance(), "qi/guard_shard": _plate("z"),
    "ult/plum_bloom": _cross(), "ult/taegeuk_disc": _plate("z"),
    "ult/emperor_edge": _plate("z"),      # 길이축이 Y 다 (4.0m 짜리 칼이 떨어진다)
    "ult/blood_tide": _plate("y"),        # 바닥을 훑는 파문 — 위/아래 면이 그림을 문다
}


def write_qi_assets() -> int:
    """획 9종 — PNG + 모델 + 아이템 정의. motion_audit ⑧ 의 '팩 미구움' 경고가 여기서 꺼진다."""
    tex = qi_textures()
    for key, rows in tex.items():
        write_png(PACK / "assets" / "honcheon" / "textures" / f"{key}.png", rows)
    for key, elems in QI_MODELS.items():
        write_json(MODEL_DIR / "item" / f"{key}.json", {
            "textures": {"0": f"honcheon:{key}", "particle": f"honcheon:{key}"},
            "elements": elems,
            "gui_light": "front",
        })
        write_json(ITEM_DEF_DIR / f"{key}.json",
                   {"model": {"type": "minecraft:model", "model": f"honcheon:item/{key}"}})
    return len(QI_MODELS)


# ═══════════════════════════════════════════════════════════════════════════
# 짐승의 형체 8종 — config/mob_models.yml 의 【청구서】 (MobDisplay 가 태운다)
#
# 【방향 계약】 코가 +Z · 등이 +Y · 오른쪽이 +X.  ★ 이 계약은 등록부의 주석만이 아니라
#   **엔진의 움직임이 증명한다**: MobDisplay.follow() 는 돌진을 rotateX(lean) 으로 그린다
#   (X 축으로 기울면 ±Z 로 숙인다 ⇒ 앞 = Z) · 걷기 흔들림은 rotateZ(roll) 로 어깨를 번갈아
#   내리고(⇒ 좌우 = X) · 죽음은 rotateZ(90°) 으로 옆으로 눕힌다. 셋이 모두 +Z 정면을 말한다.
# 【치수 계약】 파츠는 1×1×1 단위 상자를 **가득 채운다** (bbox 가 세 축 모두 16px).
#   엔진이 size 를 scale 로 곱하므로, 채우지 않으면 그만큼 작아진다. 얇은 꼬리조차
#   **채운 뒤 size 가 눌러 만든다** — 모양은 여기서, 치수는 등록부에서.
# 【원점】 몸통은 (8,8,8) 이 **발이 딛는 바닥의 정중앙** ⇒ 기하는 y 8..24 에 선다.
#   머리·꼬리는 offset 이 그 **중심**을 잡아 주므로 (0.82m 높이 · 0.95m 앞) 중심 원점이다 (y 0..16).
# ═══════════════════════════════════════════════════════════════════════════
FUR_TEX = 32                     # 16x16 swatch 넷 (주·암·명·강조) — uv 8단위 격자
SW = {"main": [0, 0, 8, 8], "dark": [8, 0, 16, 8], "light": [0, 8, 8, 16], "accent": [8, 8, 16, 16]}


def _fur_swatch(g, ox, oy, base, lo, hi, stripe=0, salt=0):
    """가죽 한 조각 — 털결(잡음)과 무늬(줄). 평면 채우기는 짐승이 아니라 판때기다."""
    for y in range(16):
        for x in range(16):
            n = (octave(ox + x, oy + y, 3, salt, 26) + octave(ox + x, oy + y, 7, salt + 5, 16)) / 2
            v = [max(0, min(255, base[i] + int(n))) for i in range(3)]
            if stripe and (h32(salt, (x + int(3 * math.sin(y / 2.6))) // stripe) % 7) < 2:
                v = [max(0, min(255, lo[i] + int(n * 0.5))) for i in range(3)]
            elif (x + y) % 9 == 0:
                v = [max(0, min(255, hi[i])) for i in range(3)]
            g[oy + y][ox + x] = (v[0], v[1], v[2], 255)


def fur_rows(main, dark, light, accent, stripe=0, salt=0):
    g = [[T] * FUR_TEX for _ in range(FUR_TEX)]
    _fur_swatch(g, 0, 0, main, dark, light, stripe, salt)          # 주 — 몸의 털
    _fur_swatch(g, 16, 0, dark, dark, main, 0, salt + 11)          # 암 — 등선·발·주둥이
    _fur_swatch(g, 0, 16, light, main, accent, 0, salt + 23)       # 명 — 배
    _fur_swatch(g, 16, 16, accent, light, accent, 0, salt + 37)    # 강조 — 발톱·송곳니·반달
    return g


def _fb(x0, y0, z0, x1, y1, z1, sw, up=None, down=None):
    """짐승의 상자 — 면마다 swatch 를 문다 (위는 등, 아래는 배)."""
    f = {}
    for c in ("north", "south", "east", "west", "up", "down"):
        s = up if (c == "up" and up) else (down if (c == "down" and down) else sw)
        f[c] = {"texture": "#0", "uv": list(SW[s])}
    return {"from": [x0, y0, z0], "to": [x1, y1, z1], "faces": f}


def horangi_body():
    """호랑이 몸통 — **어깨가 엉덩이보다 높다** (포식자의 등선). 앞다리가 굵고 발톱이 보인다."""
    e = [_fb(3, 15, 7, 13, 24, 15, "main", down="light"),        # 어깨·등 — 가장 높다 (y 24)
         _fb(3.5, 15, 2, 12.5, 21.5, 7, "main", down="light"),   # 허리·엉덩이 — 낮게 흐른다
         _fb(5, 17, 14, 11, 23, 16, "main"),                     # 목 (코 쪽 +Z 끝 — z 16)
         _fb(6, 16, 0, 10, 20, 2, "dark")]                       # 꼬리 밑동 (z 0)
    for x0, x1 in ((0, 4.5), (11.5, 16)):                        # 좌·우 (x 0 과 16 을 짚는다)
        e += [_fb(x0 + 0.5, 9, 10, x1 - 0.5, 16, 13.5, "main"),  # 앞다리 — 굵다
              _fb(x0 + 1.0, 9, 3.5, x1 - 1.0, 15.5, 6.8, "main"),  # 뒷다리
              _fb(x0, 8, 9.5, x1, 9.5, 14, "dark", down="accent"),  # 앞발 — 발톱
              _fb(x0 + 0.5, 8, 3, x1 - 0.5, 9.5, 7.3, "dark", down="accent")]
    return e


def horangi_head():
    """정면을 노려보는 머리. 입이 반쯤 벌어져 송곳니가 보이고, 귀는 **뒤로 눕는다**(공격 직전)."""
    return [_fb(3, 4, 0, 13, 13, 12, "main", down="light"),       # 두개골 (뒤통수 = z 0)
            _fb(5, 4, 12, 11, 9.5, 16, "dark", down="light"),     # 주둥이 (코 = z 16)
            _fb(5.5, 0, 11, 10.5, 4, 15, "dark", down="dark"),    # 아래턱 (y 0)
            _fb(6, 4, 13.5, 6.9, 6, 15.5, "accent"),              # 송곳니
            _fb(9.1, 4, 13.5, 10, 6, 15.5, "accent"),
            _fb(0, 12, 4, 4, 16, 8, "dark"),                      # 귀 — 뒤로 누웠다 (x 0 · y 16)
            _fb(12, 12, 4, 16, 16, 8, "dark")]


def horangi_tail():
    """굵고 긴 꼬리 — 검은 고리 무늬. 끝이 살짝 위로 감긴다 (등록부의 size 가 눌러 가늘게 만든다)."""
    return [_fb(0, 3, 0, 16, 13, 5, "main"),                      # 밑동 — 가장 굵다 (x 0..16 · z 0)
            _fb(2, 4, 5, 14, 12, 11, "main"),                     # 몸 — 가늘어진다
            _fb(3, 5, 11, 13, 16, 16, "dark"),                    # 위로 감긴 끝 (y 16 · z 16)
            _fb(4, 0, 0, 12, 3, 6, "dark")]                       # 아래 그늘 (y 0)


def gom_body():
    """반달곰 — **등이 둥글게 솟는다** (호랑이와 반대다: 곰은 어깨가 아니라 허리가 높다).
    가슴에 흰 반달 — 이 짐승의 이름이 거기 있다."""
    e = [_fb(2, 15, 4, 14, 24, 13, "main", down="main"),          # 둥근 등 (y 24 · 가운데가 높다)
         _fb(3.5, 15, 0, 12.5, 21.5, 4, "main"),                  # 엉덩이 (z 0 — 곰은 꼬리가 없다)
         _fb(4, 14, 13, 12, 22, 16, "main"),                      # 가슴·목 (z 16)
         _fb(6, 16, 13.6, 10, 21, 16, "accent")]                  # 흰 반달 (가슴)
    for x0, x1 in ((0, 5), (11, 16)):
        e += [_fb(x0 + 0.5, 9, 9.5, x1 - 0.5, 16, 13, "main"),
              _fb(x0 + 0.5, 9, 2.5, x1 - 0.5, 16, 6, "main"),
              _fb(x0, 8, 9, x1, 9.5, 13.5, "dark", down="accent"),
              _fb(x0, 8, 2, x1, 9.5, 6.5, "dark", down="accent")]
    return e


def gom_head():
    """짧은 주둥이 · **둥근 귀 두 개가 크게 솟는다** · 눈이 작다 (호랑이의 누운 귀와 정반대)."""
    return [_fb(3, 3, 0, 13, 12, 12, "main", down="light"),       # 두개골 (뒤통수 = z 0)
            _fb(5, 3, 12, 11, 8.5, 16, "dark"),                   # 짧은 주둥이
            _fb(5.5, 0, 11.5, 10.5, 3, 15, "dark"),
            _fb(0, 12, 5, 5, 16, 10, "main"),                     # 크고 둥근 귀 (x 0 · y 16)
            _fb(11, 12, 5, 16, 16, 10, "main")]


def dwaeji_body():
    """멧돼지 — 머리·어깨·몸통이 **한 덩어리**다 (파츠를 나누지 않는다).
    어깨혹이 솟고 엉덩이로 갈수록 가늘어지는 **쐐기꼴** — 그것이 멧돼지의 정체다.
    아래로 굽은 엄니 · 뻣뻣한 등털."""
    e = [_fb(2, 14, 5, 14, 22, 12, "main", down="light"),         # 어깨혹 — 가장 굵고 높다
         _fb(4.5, 14, 0, 11.5, 19, 5, "main", down="light"),      # 엉덩이 — 가늘다 (쐐기 · z 0)
         _fb(4, 12, 12, 12, 20, 15, "main"),                      # 목 없는 머리 — 통째로 이어진다
         _fb(5.5, 11, 15, 10.5, 16, 16, "dark"),                  # 주둥이 (z 16)
         _fb(5.2, 10.5, 14.5, 6.4, 13, 16, "accent"),             # 엄니 — 아래로 굽었다
         _fb(9.6, 10.5, 14.5, 10.8, 13, 16, "accent"),
         _fb(6, 22, 3, 10, 24, 11, "dark")]                       # 등털 (y 24)
    for x0, x1 in ((1.5, 5), (11, 14.5)):
        e += [_fb(x0, 8.8, 9, x1, 15, 12, "dark"),
              _fb(x0 + 0.4, 8.8, 2.5, x1 - 0.4, 15, 5.5, "dark"),
              _fb(x0 - 1.5, 8, 8.5, x1 + 1.5, 8.8, 12.5, "dark")]  # 발굽 (x 0·16)
    return e


def myo_body():
    """백영묘(白影猫) — 고양잇과의 유연한 몸. 호랑이보다 **낮고 길다**.
    새하얗고 털끝이 은빛. **발톱은 늘 나와 있다** — 이 짐승은 무기를 감추지 않는다."""
    e = [_fb(4, 16, 6, 12, 23, 14, "light", down="main"),         # 어깨뼈가 솟는다 (y 23)
         _fb(4.5, 16, 2, 11.5, 22, 6, "light", down="main"),
         _fb(5, 18, 14, 11, 24, 16, "light"),                     # 긴 목 (y 24 · z 16)
         _fb(6, 17, 0, 10, 21, 2, "light")]                       # 꼬리 밑동
    for x0, x1 in ((0, 4.5), (11.5, 16)):
        e += [_fb(x0 + 0.8, 9.5, 9.5, x1 - 0.8, 17, 12.8, "light"),
              _fb(x0 + 0.8, 9.5, 3.2, x1 - 0.8, 17, 6.5, "light"),
              _fb(x0, 8, 9, x1, 9.5, 13.4, "main", down="accent"),   # 늘 나와 있는 발톱
              _fb(x0, 8, 2.8, x1, 9.5, 7, "main", down="accent")]
    return e


def myo_tail():
    """몸길이의 2/3 에 이르는 긴 꼬리. **끝이 위로 곧게 선다** — 영물의 표시다."""
    return [_fb(0, 4, 0, 16, 12, 4, "light"),                     # 밑동 (x 0..16 · z 0)
            _fb(3, 5, 4, 13, 11, 10, "light"),
            _fb(5, 6, 10, 11, 16, 16, "accent"),                  # 곧게 선 끝 (y 16 · z 16 · 은빛)
            _fb(4, 0, 0, 12, 4, 5, "main")]                       # 아래 (y 0)


# 짐승 8종 — (경로, 기하, 가죽). 산늑대는 **일부러 굽지 않는다**:
#   mob_models.yml 이 shape: vanilla 로 못 박았다 ("이미 늑대다 — 조각으로 만들면 더 나빠진다").
#   구워 두면 언젠가 누군가 켤 것이고, 그러면 여섯 마리가 뻣뻣하게 미끄러진다. 등록부의 판단을 따른다.
MOB_PARTS = {
    "mob/horangi/body": (horangi_body, (128, 124, 116), (44, 42, 40), (206, 202, 192), (240, 238, 232), 3, 1),
    "mob/horangi/head": (horangi_head, (128, 124, 116), (44, 42, 40), (206, 202, 192), (240, 238, 232), 3, 2),
    "mob/horangi/tail": (horangi_tail, (128, 124, 116), (44, 42, 40), (206, 202, 192), (240, 238, 232), 3, 3),
    "mob/bandal_gom/body": (gom_body, (62, 58, 54), (30, 28, 26), (96, 92, 86), (238, 236, 230), 0, 4),
    "mob/bandal_gom/head": (gom_head, (62, 58, 54), (30, 28, 26), (96, 92, 86), (238, 236, 230), 0, 5),
    "mob/metdwaeji/body": (dwaeji_body, (86, 80, 72), (40, 37, 34), (132, 126, 116), (226, 222, 212), 0, 6),
    "mob/baegyeongmyo/body": (myo_body, (222, 222, 226), (150, 152, 158), (246, 246, 248), (252, 252, 254), 0, 7),
    "mob/baegyeongmyo/tail": (myo_tail, (222, 222, 226), (150, 152, 158), (246, 246, 248), (252, 252, 254), 0, 8),
}


def _fill_check(elems, path):
    """단위 상자를 **가득 채웠는가** — 채우지 않으면 등록부의 size 가 거짓이 된다.
    몸통은 y 8..24 (발이 바닥) · 머리·꼬리는 y 0..16 (중심 원점). x·z 는 언제나 0..16."""
    lo = [min(min(e["from"][i], e["to"][i]) for e in elems) for i in range(3)]
    hi = [max(max(e["from"][i], e["to"][i]) for e in elems) for i in range(3)]
    want_y = (8, 24) if path.endswith("/body") else (0, 16)
    want = [(0, 16), want_y, (0, 16)]
    for i, ax in enumerate("xyz"):
        if abs(lo[i] - want[i][0]) > 0.01 or abs(hi[i] - want[i][1]) > 0.01:
            raise ValueError(f"{path}: {ax} 축이 단위 상자를 못 채웠다 "
                             f"({lo[i]}..{hi[i]}, 기대 {want[i][0]}..{want[i][1]})")


def write_mob_assets() -> int:
    """짐승 8종 — 가죽 PNG + 모델(models/mob/**) + 아이템 정의(items/mob/**).
    mob_model_audit ③ 의 '팩에 없는 모델 키' 가 여기서 0 이 된다."""
    for path, (rig, main, dark, light, accent, stripe, salt) in MOB_PARTS.items():
        write_png(PACK / "assets" / "honcheon" / "textures" / f"{path}.png",
                  fur_rows(main, dark, light, accent, stripe, salt))
        elems = rig()
        _fill_check(elems, path)
        write_json(MODEL_DIR / f"{path}.json", {
            "textures": {"0": f"honcheon:{path}", "particle": f"honcheon:{path}"},
            "elements": elems,
            "gui_light": "front",
        })
        write_json(ITEM_DEF_DIR / f"{path}.json",
                   {"model": {"type": "minecraft:model", "model": f"honcheon:{path}"}})
    return len(MOB_PARTS)


# ═══════════════════════════════════════════════════════════════════════════
# 메뉴·버튼 — 1.21 GUI 스프라이트 계약 (assets/minecraft/textures/gui/sprites/**)
#
# 【좌표 계약을 지키는 법 — .mcmeta 를 굽지 않는다】
#   1.21 의 위젯은 **나인슬라이스**다: <스프라이트>.png.mcmeta 가 테두리 폭을 정하고,
#   클라이언트는 그 테두리만 남기고 가운데를 늘리거나 깐다. 우리가 mcmeta 를 덮어쓰면
#   그 순간 버튼의 늘어나는 법이 바뀐다 — **클릭 판정은 그대로인데 그림만 어긋난다.**
#   리소스팩은 파일 단위로 겹친다: PNG 만 넣고 mcmeta 를 넣지 않으면 **바닐라의 mcmeta 가 그대로 산다.**
#   그래서 우리는 (ㄱ) 바닐라와 **똑같은 치수**로 굽고 (ㄴ) mcmeta 를 굽지 않는다.
#   ⇒ 테두리 폭·늘어나는 법·클릭 좌표가 한 픽셀도 움직이지 않는다. (치수는 1.21.11 jar 에서 실측했다.)
#
# 【늘어나는 자리는 균질해야 한다】 나인슬라이스가 가운데를 가로로 깔므로(버튼 폭은 98·150·200…),
#   가운데는 **x 로 균질**해야 한다 (행마다 한 색). 좌·우 테두리 띠는 세로로 깔리므로 **y 로 균질**.
#   이 규율을 어기면 좁은 버튼에서 무늬가 씹힌다. 그래서 결(fiber)은 가로줄로만 넣는다.
# ═══════════════════════════════════════════════════════════════════════════
SPRITE_DIR = GUI_DIR / "sprites"
UI_INK = (28, 26, 23, 255)          # 먹 — 바깥 선
UI_INK_SOFT = (58, 54, 49, 255)     # 먹 옅은 선 (안쪽 그늘)
UI_HI = (238, 231, 214, 255)        # 화선지 — 빛 받는 마루 (위·왼쪽)
UI_PAPER = (212, 203, 184, 255)     # 화선지 몸
UI_DIM = (172, 163, 144, 255)       # 화선지 그늘 (아래·오른쪽)
UI_DEEP = (138, 130, 113, 255)      # 눌린 자리
UI_MUTE = (128, 123, 114, 255)      # 비활성 — 빛이 죽은 종이
UI_MUTE_HI = (158, 152, 142, 255)
UI_MUTE_DIM = (98, 94, 87, 255)
UI_TRACK = (74, 69, 62, 255)        # 스크롤 홈
UI_PANEL = (26, 24, 22, 238)        # 툴팁 바탕 — 먹 패널 (반투명)


def _rows(w, h, c):
    return [[c] * w for _ in range(h)]


def _frame(g, w, h, line, hi, dim, deep=None):
    """테를 두른다 — 바깥 1px 먹선 + 안쪽 1px 되비침(위·왼) / 그늘(아래·오른).
    바깥 1px 만 쓰므로 **테두리 폭 1 짜리 스프라이트에도 그대로 통한다** (비활성·입력칸·스크롤).
    좌·우 띠는 x 에만, 위·아래 띠는 y 에만 기대므로 나인슬라이스가 깔아도 균질하다."""
    for x in range(w):
        g[0][x] = line
        g[h - 1][x] = line
    for y in range(h):
        g[y][0] = line
        g[y][w - 1] = line
    for x in range(1, w - 1):
        g[1][x] = hi
        g[h - 2][x] = deep or dim
    for y in range(1, h - 1):
        g[y][1] = hi if y < h - 1 else dim
        g[y][w - 2] = deep or dim
    g[1][1] = hi
    g[h - 2][w - 2] = deep or dim


def _grain(g, w, h, pad, base, salt):
    """화선지 결 — **가로줄로만** 넣는다 (행마다 한 색). 세로 결은 나인슬라이스가 씹는다."""
    for y in range(pad, h - pad):
        n = (h32(salt, y) % 7) - 3
        c = tuple(max(0, min(255, base[i] + n)) for i in range(3)) + (base[3],)
        for x in range(pad, w - pad):
            g[y][x] = c


def _button(state):
    """버튼 200x20 — 바닐라 치수. 테두리 폭은 바닐라 mcmeta 가 정한다 (기본·눌림 3 · 비활성 1).
    격(格)은 색이 아니라 **밝기**로 오른다 (색맹 규약): 기본 → 눌림은 종이가 밝아지고,
    비활성은 빛이 죽는다. 주사(朱砂)는 눌린 버튼의 밑줄 한 줄뿐 — 있고 없음이 곧 정보다."""
    w, h = 200, 20
    if state == "disabled":
        g = _rows(w, h, UI_MUTE)
        _grain(g, w, h, 1, UI_MUTE, 91)
        _frame(g, w, h, UI_INK, UI_MUTE_HI, UI_MUTE_DIM)
        return g
    hot = state == "highlighted"
    body = UI_HI if hot else UI_PAPER
    g = _rows(w, h, body)
    _grain(g, w, h, 2, body, 7 if hot else 3)
    _frame(g, w, h, UI_INK, UI_HI if not hot else (248, 244, 232, 255), UI_DIM, UI_DEEP if hot else None)
    for x in range(2, w - 2):                       # 안쪽 옅은 먹선 — 종이가 눌린 자국
        g[2][x] = UI_INK_SOFT if hot else mix(body, UI_INK_SOFT, 0.35)
        g[h - 3][x] = mix(body, UI_INK_SOFT, 0.55 if hot else 0.3)
    if hot:                                         # 주사 밑줄 — 손이 얹힌 칸 (오직 여기에만)
        for x in range(3, w - 3):
            g[h - 4][x] = SEAL
    return g


def _tab(selected, hot):
    """탭 130x24 — 아래가 열린 테(mcmeta border bottom=0). 고른 탭은 **종이가 앞으로 나온다**."""
    w, h = 130, 24
    body = UI_HI if selected else (UI_PAPER if hot else UI_DIM)
    g = _rows(w, h, body)
    _grain(g, w, h, 2, body, 13 if selected else 17)
    for x in range(w):                              # 위·좌·우만 두른다 (아래는 창과 이어진다)
        g[0][x] = UI_INK
    for y in range(h):
        g[y][0] = UI_INK
        g[y][w - 1] = UI_INK
    for x in range(1, w - 1):
        g[1][x] = UI_HI if selected else UI_PAPER
    for y in range(1, h):
        g[y][1] = UI_HI if selected else UI_PAPER
        g[y][w - 2] = UI_DEEP if selected else UI_DIM
    if selected:                                    # 고른 탭에만 주사 인끈 — 색이 아니라 유무가 말한다
        for x in range(4, w - 4):
            g[2][x] = SEAL
    else:
        for x in range(1, w - 1):                   # 안 고른 탭은 아래로 그늘 (뒤에 물러나 있다)
            g[h - 1][x] = UI_INK_SOFT
    return g


def _tooltip_bg():
    """툴팁 바탕 100x100 (border 9) — 먹 패널. 가운데는 **완전 균질**이라야 큰 툴팁에서 안 씹힌다.

    ★ 바깥 테만 먹으로 짙게 두면 명암차가 8밖에 안 난다 (검수 축: 밋밋). 종이 위에 얹힌 먹판이
      배경에서 떠오르려면 **되비침 한 줄**이 있어야 한다 — 안쪽 8번째 고리에 화선지 선을 둔다."""
    w = h = 100
    g = _rows(w, h, UI_PANEL)
    for i in range(9):                              # 모서리로 갈수록 옅어지는 테 (전각 도장의 여백)
        a = 238 - i * 6
        c = (UI_PANEL[0] + i * 2, UI_PANEL[1] + i * 2, UI_PANEL[2] + i * 2, a)
        if i == 7:                                  # 되비침 — 먹판의 안쪽 립 (배경에서 떠오르는 이유)
            c = (150, 142, 126, 210)
        elif i == 8:
            c = (92, 86, 76, 224)
        for x in range(i, w - i):
            g[i][x] = c
            g[h - 1 - i][x] = c
        for y in range(i, h - i):
            g[y][i] = c
            g[y][w - 1 - i] = c
    return g


def _tooltip_frame():
    """툴팁 테 100x100 (border 10 · stretch_inner) — 화선지 선 + 네 귀의 주사 도장.
    안쪽은 투명해야 바탕이 비친다. 선은 제 방향으로 균질하다 (늘어나도 굵기가 안 변한다).

    ★ 선을 '같은 RGB 에 알파만 다르게' 두 줄 그으면 **색이 둘뿐인 텍스처**가 된다 (검수: 평면).
      알파는 색이 아니다 — 종이의 두께를 알파로 흉내 내지 말고 **밝기 계단**으로 그어라."""
    w = h = 100
    g = _rows(w, h, T)
    for x in range(2, w - 2):
        g[1][x] = UI_HI                             # 바깥 — 빛 받는 마루
        g[2][x] = UI_PAPER
        g[h - 3][x] = UI_DIM
        g[h - 2][x] = UI_DEEP                       # 아래 — 그늘 (테가 두께를 갖는다)
    for y in range(2, h - 2):
        g[y][1] = UI_HI
        g[y][2] = UI_PAPER
        g[y][w - 3] = UI_DIM
        g[y][w - 2] = UI_DEEP
    for cx, cy in ((3, 3), (w - 7, 3), (3, h - 7), (w - 7, h - 7)):    # 주사 도장 네 귀
        for dy in range(4):
            for dx in range(4):
                if dx in (0, 3) or dy in (0, 3):
                    g[cy + dy][cx + dx] = SEAL
    return g


def _scroller(track):
    """스크롤 6x32 (border 1) — 손잡이는 종이, 홈은 먹. 테두리 1px 이라 안쪽은 균질해야 한다."""
    w, h = 6, 32
    g = _rows(w, h, UI_TRACK if track else UI_PAPER)
    if track:
        for y in range(h):
            g[y][0] = UI_INK
            g[y][w - 1] = UI_INK_SOFT
        for x in range(w):
            g[0][x] = UI_INK
            g[h - 1][x] = UI_INK
        for y in range(1, h - 1):
            g[y][1] = (52, 48, 43, 255)
        return g
    _frame(g, w, h, UI_INK, UI_HI, UI_DIM)
    for y in range(2, h - 2):                       # 손잡이 결 — 세로로 균질 (세로로 깔린다)
        g[y][2] = UI_PAPER
        g[y][3] = UI_DIM
    return g


def _bar(w, h, body, border=1):
    """입력칸·슬라이더 몸통 (border 1) — 1px 먹 테 + 균질한 속."""
    g = _rows(w, h, body)
    _grain(g, w, h, border, body, 29)
    for x in range(w):
        g[0][x] = UI_INK
        g[h - 1][x] = UI_INK
    for y in range(h):
        g[y][0] = UI_INK
        g[y][w - 1] = UI_INK
    return g


def _slider_handle(hot):
    """슬라이더 손잡이 8x20 (border l2 t2 r2 b3) — 잡는 물건이므로 **도드라져야** 한다."""
    w, h = 8, 20
    body = UI_HI if hot else UI_PAPER
    g = _rows(w, h, body)
    _frame(g, w, h, UI_INK, (248, 244, 232, 255) if hot else UI_HI, UI_DIM, UI_DEEP)
    for y in range(4, h - 5):                       # 손가락이 걸리는 홈 — 세로로 균질
        g[y][3] = UI_DEEP
        g[y][4] = UI_HI
    return g


# 스프라이트 등록부 — **치수는 1.21.11 client jar 실측치다** (짐작 금지: 어긋나면 mcmeta 와 안 맞는다)
UI_SPRITES = {
    "widget/button": lambda: _button("normal"),                    # 200x20 · border 3
    "widget/button_highlighted": lambda: _button("highlighted"),   # 200x20 · border 3
    "widget/button_disabled": lambda: _button("disabled"),         # 200x20 · border 1
    "widget/tab": lambda: _tab(False, False),                      # 130x24 · border l2 t2 r2 b0
    "widget/tab_highlighted": lambda: _tab(False, True),
    "widget/tab_selected": lambda: _tab(True, False),
    "widget/tab_selected_highlighted": lambda: _tab(True, True),
    "widget/scroller": lambda: _scroller(False),                   # 6x32 · border 1
    "widget/scroller_background": lambda: _scroller(True),         # 6x32 · border 1
    "widget/text_field": lambda: _bar(200, 20, UI_DEEP),           # 200x20 · border 1 (파인 자리)
    "widget/text_field_highlighted": lambda: _bar(200, 20, UI_PAPER),
    "widget/slider": lambda: _bar(200, 20, UI_TRACK),              # 200x20 · border 1
    "widget/slider_highlighted": lambda: _bar(200, 20, (92, 86, 77, 255)),
    "widget/slider_handle": lambda: _slider_handle(False),         # 8x20
    "widget/slider_handle_highlighted": lambda: _slider_handle(True),
    "tooltip/background": _tooltip_bg,                             # 100x100 · border 9
    "tooltip/frame": _tooltip_frame,                               # 100x100 · border 10
}

# 바닐라 실측 치수 — 이 표와 산출 PNG 가 어긋나면 굽지 않는다 (mcmeta 는 바닐라 것을 그대로 쓰므로
# 치수가 어긋나는 순간 나인슬라이스가 엉뚱한 데를 자른다 = 버튼이 찢어진다).
UI_SIZE = {
    "widget/button": (200, 20), "widget/button_highlighted": (200, 20),
    "widget/button_disabled": (200, 20),
    "widget/tab": (130, 24), "widget/tab_highlighted": (130, 24),
    "widget/tab_selected": (130, 24), "widget/tab_selected_highlighted": (130, 24),
    "widget/scroller": (6, 32), "widget/scroller_background": (6, 32),
    "widget/text_field": (200, 20), "widget/text_field_highlighted": (200, 20),
    "widget/slider": (200, 20), "widget/slider_highlighted": (200, 20),
    "widget/slider_handle": (8, 20), "widget/slider_handle_highlighted": (8, 20),
    "tooltip/background": (100, 100), "tooltip/frame": (100, 100),
}


def write_ui_sprites() -> int:
    """메뉴·버튼 — PNG 만 굽는다 (mcmeta 는 바닐라 것이 그대로 산다 = 좌표 계약 불변)."""
    for key, make in UI_SPRITES.items():
        rows = make()
        want = UI_SIZE[key]
        got = (len(rows[0]), len(rows))
        if got != want:
            raise ValueError(f"{key}: 치수 {got} ≠ 바닐라 {want} — mcmeta 와 어긋난다 (버튼이 찢어진다)")
        write_png(SPRITE_DIR / f"{key}.png", rows)
    return len(UI_SPRITES)


def main():
    # --sheet: 엔티티 확대 검수 시트도 함께 굽는다 (run/texture-review/ — 커밋 대상 아님)
    sheet = "--sheet" in sys.argv
    write_png(FONT_DIR / "gise.png", gise_icon())
    providers = [{
        "type": "bitmap", "file": "honcheon:font/gise.png",
        # PUA 리터럴 금지 — 편집기가 사설 영역 문자를 조용히 지울 수 있다 (M2b에서 실제 유실
        # → 빈 chars 하나가 팩 전체를 무효화). 다른 프로바이더처럼 chr()로 만든다.
        "height": 8, "ascent": 7, "chars": [chr(0xE000)],
    }]
    for n in range(9):
        write_png(FONT_DIR / f"gauge_{n}.png", gauge(n))
        providers.append({
            "type": "bitmap", "file": f"honcheon:font/gauge_{n}.png",
            "height": 7, "ascent": 6, "chars": [chr(0xE010 + n)],
        })
    for i, (realm, art) in enumerate(REALM_CRESTS.items()):
        write_png(FONT_DIR / f"crest_{i}.png", art_rows(art))
        providers.append({
            "type": "bitmap", "file": f"honcheon:font/crest_{i}.png",
            "height": 8, "ascent": 7, "chars": [chr(0xE020 + i)],
        })
    # 비무 표식 (E030) — 액션바·명패 앞에 붙일 글리프. 플러그인이 안 붙이면 그냥 안 보인다
    # (팩 게이트: 글리프는 강화지 필수가 아니다. Sparring 의 액션바 문구만으로도 읽힌다).
    write_png(FONT_DIR / "bimu.png", bimu_icon())
    providers.append({
        "type": "bitmap", "file": "honcheon:font/bimu.png",
        "height": 8, "ascent": 7, "chars": [chr(0xE030)],
    })
    # ─── 바닐라 HUD 텍스처 교체 (폰트 아님 — 스프라이트 직접 교체, 9x9 치수 계약) ───
    for name, art, palette in HEART_SPRITES:
        write_png(HUD_DIR / "heart" / f"{name}.png", paint_rows(art, palette))
    write_png(HUD_DIR / "hotbar.png", hotbar())
    write_png(HUD_DIR / "hotbar_selection.png", hotbar_selection())
    write_png(CONTAINER_DIR / "inventory.png", inventory_container())
    write_png(CONTAINER_DIR / "generic_54.png", generic_54_container())
    # 화덕 3종 — 배치가 같으니 같은 그림이다 (바닐라도 furnace/smoker/blast_furnace 가 동일 배치)
    furnace = furnace_container()
    for name in ("furnace", "smoker", "blast_furnace"):
        write_png(CONTAINER_DIR / f"{name}.png", furnace)
    write_png(CONTAINER_DIR / "crafting_table.png", crafting_container())

    write_png(FONT_DIR / "gui_ledger.png", gui_background())
    providers.append({
        "type": "bitmap", "file": "honcheon:font/gui_ledger.png",
        "height": 110, "ascent": 13, "chars": [chr(0xE080)],   # ascent는 인게임 튜닝 대상
    })

    # 음수 공백 프로바이더 (E0A0~E0A5) — 경락도 GUI 배경(E080) 제목 음수 공백 기법용.
    # 2의 거듭제곱 음수 폭 + 미세조정 +1 폭 조합으로 임의 오프셋 구성. F26: 키도 chr()로만.
    providers.append({
        "type": "space",
        "advances": {
            chr(0xE0A0): -8, chr(0xE0A1): -16, chr(0xE0A2): -32,
            chr(0xE0A3): -64, chr(0xE0A4): -128, chr(0xE0A5): 1,
        },
    })

    font = PACK / "assets" / "minecraft" / "font" / "default.json"
    font.parent.mkdir(parents=True, exist_ok=True)
    # ensure_ascii=True — 산출 JSON에서도 PUA가 \uXXXX 이스케이프로 남는다 (F26: 리터럴 유실 방지)
    font.write_text(json.dumps({"providers": providers}, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")

    # ─── 아이템·블록 텍스처 레이어 (texture_layer_design.md — 1차) ───
    items = write_item_assets()
    blocks = write_block_textures()
    parts = write_particle_textures()
    props = write_prop_textures()
    ents = write_entity_textures(sheet)

    # ─── 3D 모델층 (§texture_layer_design.md §6) — 획·형체·메뉴 ───
    qi = write_qi_assets()          # 무공의 획 9종 (skill_motion.yml display.models 의 청구서)
    mobs = write_mob_assets()       # 짐승의 형체 8종 (mob_models.yml 의 청구서)
    ui = write_ui_sprites()         # 메뉴·버튼 (바닐라 mcmeta 를 건드리지 않는다 = 좌표 계약 불변)

    # pack_format 은 클라이언트 버전이 정한다 — 서버 jar 의 version.json(pack_version.resource_major)이 진실.
    #   1.21.4 = 46 · 1.21.11 = 75. 숫자가 어긋나면 클라이언트가 "낡은 팩" 경고를 띄운다(적용은 되지만
    #   경고가 뜨는 팩은 사용자가 끈다). supported_formats 로 46~75 를 함께 받아 구 클라이언트도 살린다.
    (PACK / "pack.mcmeta").write_text(json.dumps({
        "pack": {"pack_format": PACK_FORMAT,
                 # 신·구 스키마를 **함께** 선언한다 — 1.21.9+ 클라이언트는 min_format/max_format 를 요구하고
                 # (없으면 "지원 버전 누락" 경고), 구 클라이언트는 supported_formats 를 읽는다.
                 # 데이터팩에서 이 필드가 없어 팩이 통째로 로드되지 않은 적이 있다 (조용히 꺼졌다).
                 "supported_formats": {"min_inclusive": 46, "max_inclusive": PACK_FORMAT},
                 "min_format": 46,
                 "max_format": PACK_FORMAT,
                 "description": "혼천(渾天) — 수묵 무협 팩 · 3D 병기 45 · 무공 획 9 · 짐승 형체 8 · 블록 215 · 메뉴 (1.21.11)"}
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    total = 1 + 9 + len(REALM_CRESTS) + 1 + 1
    vanilla = len(HEART_SPRITES) + 2 + 6   # 하트 6 + 핫바 2 + 컨테이너 6 (인벤·궤·화덕3·목공대)
    print(f"팩 컴파일 완료: {PACK.relative_to(ROOT)} "
          f"(글리프 {total}종 + 음수공백 6폭 + 바닐라 교체 {vanilla}장 + 폰트 주입 + pack.mcmeta)")
    print(f"  아이템 채널 {items}종 (PNG {items} + 모델 {items} + 아이템 정의 {items}) — item_model, 전역 오염 0")
    print(f"  ├ 병기 45자루 = **3D 모델** (elements — 평면 스프라이트가 아니다)"
          f" · 등급이 형체로 갈린다 (고리 0~3 · 수실 · 마병 톱니)")
    print(f"  무공의 획 {qi}종 (3D 모션 — SkillDisplay 가 item_model 로 태운다. 길이축 +X)")
    print(f"  짐승의 형체 {mobs}종 (MobDisplay 가 본체를 감추고 태운다. 코가 +Z · 발이 원점)")
    print(f"  메뉴·버튼 {ui}장 (GUI 스프라이트 — mcmeta 미포함 = 바닐라 나인슬라이스·좌표 계약 그대로)")
    print(f"  블록 징발 {blocks}장 (전역 치환 — block_channels.징발 등록분만)")
    print(f"  획층(파티클) {parts}장 (무공 모션 — 엔진 불변. 팩 없으면 바닐라 파티클로 폴백)")
    print(f"  기물(블록 엔티티) {props}장 (항아리·궤 — 블록이되 텍스처는 entity/ 아래 산다)")
    print(f"  엔티티 징발 {ents}장 (전역 치환 — 사람 2 + 늑대 변종 27 + 고양잇과 2 + 곰·멧돼지·호랑이 3"
          f" + 마을 사람 27: 바탕 1 + 겉옷 7 + 생업 14 + 가슴패 5)")


if __name__ == "__main__":
    main()
