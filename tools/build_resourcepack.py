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
바닐라 텍스처 교체 (1.21.4 / pack_format 46 — 화면 HUD·인벤토리 수묵 재해석):
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
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / "resourcepack"
FONT_DIR = PACK / "assets" / "honcheon" / "textures" / "font"
GUI_DIR = PACK / "assets" / "minecraft" / "textures" / "gui"
HUD_DIR = GUI_DIR / "sprites" / "hud"        # 1.21.4 스프라이트 아틀라스 경로 (pack_format 46)
CONTAINER_DIR = GUI_DIR / "container"        # 컨테이너 GUI는 스프라이트 분리 대상이 아님 — 기존 경로
# ─── 아이템·블록 텍스처 레이어 디렉터리 계약 (texture_layer_design.md §5.1) ───
ITEM_DEF_DIR = PACK / "assets" / "honcheon" / "items"                     # 아이템 정의 (1.21.4)
ITEM_MODEL_DIR = PACK / "assets" / "honcheon" / "models" / "item"         # 모델
ITEM_TEX_DIR = PACK / "assets" / "honcheon" / "textures" / "item"         # 16x16 아트
BLOCK_DIR = PACK / "assets" / "minecraft" / "textures" / "block"          # 전역 치환 (징발)
PAINTING_DIR = PACK / "assets" / "minecraft" / "textures" / "painting"    # 족자

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
ORB_SOCK_LIT = (110, 102, 90, 195)  # 빈 소켓 하·우 안쪽 — 바닥에서 되비친 빛
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
ORB_CONTAINER_ART = [
    ".........",
    "..#####..",
    ".#sssss#.",
    ".#s~~~v#.",
    ".#s~~~v#.",
    ".#s~~~v#.",
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


def put_rings(grid, x0, y0, rings, slots, sx=-1, sy=1):
    """등급 고리 — 자루를 가로지르는 2톤 금속 테. slots = 자루 걸음 번호(위에서부터).
    colorblind_rule: 색 단독 금지 → 회색조에서도 '고리 몇 개'로 등급이 읽힌다."""
    for k in range(min(rings, len(slots))):
        i = slots[k]
        band(grid, x0 + sx * i, y0 + sy * i, 1, ["R", "R", "e"])


def blade_strands(mabyeong, spine):
    """날 단면 4가닥: 인(가장 밝음) → 밝은 사면 → 혈조(血槽) → 등.
    혈조 자리가 마병에서는 혈적이 된다 — 형태는 같고 '피가 밴 홈'만 다르다.
    도(刀)는 한날이라 등이 두껍고 어둡다 (spine=True) — 검과의 차이는 오직 등의 그늘로 읽힌다."""
    return ["H", "L", "m" if mabyeong else "B", "D" if spine else "S"]


def _hilt(g, rings, mabyeong, gx, gy, guard, slots, guard_steps=5):
    """자루 한 벌 — 감기 5걸음 + 고리 + 놋 물미 + 신병 수실 + 마병 혈적.
    검·도·비수가 공유한다 (자루는 계열이 아니라 등급이 말하는 부위다).
    guard_steps = 코등이 걸음 수. 검은 길게 뻗은 가로대(5), 도는 뭉툭한 원반(2) —
    이 값이 같으면 검과 도가 같은 물건이 된다 (그것이 첫 판의 실패였다)."""
    band(g, gx, gy, 5, wrap_grip, sx=-1, sy=1)
    band(g, *guard, guard_steps, ["G", "g", "f"], sx=1, sy=1)  # 코등이 — 날에 수직인 가로대
    put_rings(g, gx, gy, rings, slots)
    band(g, gx - 4, gy + 4, 1, ["G", "g", "f"])              # 물미(자루 끝 놋)
    if rings >= 3:                                            # 신병 수실 — 물미에 매단다
        g[gy + 5][gx - 4] = "T"
        g[gy + 5][gx - 3] = "t"
    if mabyeong:                                              # 마병 혈적 — 자루 끝의 낙인
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
    """검(劍) — 곧은 양날. 폭이 끝까지 균일하고 좌우 대칭이다 (등줄기가 가운데)."""
    g = blank16()
    ridge = "m" if mabyeong else "H"                          # 등줄기(鎬) — 마병은 혈조가 된다
    for i in range(7):                                        # 날 (6,9) → (12,3), 폭 3 균일
        band(g, 6 + i, 9 - i, 1, ["L", ridge, "L"], vertical=True)
    band(g, 13, 3, 1, ["H", "L"], vertical=True)              # 끝 좁힘
    band(g, 14, 3, 1, ["H"], vertical=True)                   # 칼끝
    _hilt(g, rings, mabyeong, 5, 10, (3, 8), (1, 2, 3))       # 긴 가로대 코등이
    return g


# 도의 날 — (x, y_top, 세로 가닥). 등(D)은 곧은 대각선인데 인(H) 쪽 배가 불러
# 중간에서 가장 넓고 끝에서 좁아진다. 이 '배'가 곡선을 만든다 (16px에서 곡률보다 폭 변화가 읽힌다).
DAO_BLADE = [
    (7,  9, "DLH"),      # 밑동
    (8,  8, "D*LH"),
    (9,  7, "D*LH"),     # 배 — 가장 넓다
    (10, 6, "D*LH"),
    (11, 5, "DLH"),
    (12, 4, "DLH"),
    (13, 3, "LH"),       # 끝 좁힘
    (14, 2, "H"),        # 칼끝
]


def dao_grid(rings, mabyeong):
    """도(刀) — 한날. 등이 두껍고 인 쪽 배가 부르며, 코등이는 뭉툭한 원반이다."""
    g = blank16()
    _hilt(g, rings, mabyeong, 6, 10, (5, 9), (1, 2, 3), guard_steps=2)   # 원반 호수 — 뭉툭하게
    for x, y, strands in DAO_BLADE:                           # 날은 자루 위에 얹는다 (접합부를 덮는다)
        band(g, x, y, 1, [("m" if mabyeong else "B") if c == "*" else c
                          for c in strands], vertical=True)
    return g


def dagger_grid(rings, mabyeong):
    """비수(匕首) — 짧은 날. 자루가 날보다 길다 (비율이 곧 정체다)."""
    g = blank16()
    band(g, 7, 9, 4, ["H", "L", "m" if mabyeong else "S"])    # 짧은 날 y=9..6
    band(g, 11, 5, 1, ["H"])
    # 코등이는 짧다(3). 검과 같은 긴 가로대를 달면 '작은 검'이 되어 계열이 흐려진다.
    _hilt(g, rings, mabyeong, 6, 10, (5, 8), (1, 2, 3), guard_steps=3)
    return g


def spear_grid(rings, mabyeong):
    """창(槍) — 긴 자루 + 좁은 창날 + 홍영(紅纓, 창날 밑 붉은 술).
    자루가 길어 고리를 넉넉히 벌려 꽂는다 — 등급이 멀리서도 세어진다."""
    g = blank16()
    band(g, 1, 14, 9, wrap_grip, sx=1, sy=-1)                 # 긴 자루 (1,14)→(9,6)
    put_rings(g, 1, 14, rings, (2, 4, 6), sx=1, sy=-1)
    band(g, 10, 5, 1, ["T", "t", "t"])                        # 홍영 — 붉은 술
    band(g, 11, 4, 1, ["G", "g", "f"])                        # 물미(창날 목)
    band(g, 12, 3, 1, ["H", "L", "m" if mabyeong else "S"])   # 창날
    band(g, 13, 2, 1, ["H", "L"])
    band(g, 13, 1, 1, ["H"])                                  # 창끝
    if mabyeong:
        g[15][1] = "M"
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
GAUNT_KNUCKLES = ((3, 2), (6, 1), (9, 2), (12, 3))
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
    for n, y in ((1, 10), (2, 12), (3, 14)):                  # 고리 3자리 — 한 줄 걸러 하나 (세어진다)
        for x in range(cx0, cx1 + 1):
            # 점등 = 2톤 금속 테 (오른쪽 절반이 그늘) / 미점등 = 가죽색 — 구멍이 나지 않는다
            g[y][x] = ("R" if x < 9 else "e") if rings >= n else ("x" if x < cx1 else "X")
    for x in range(cx0, cx1 + 1):
        g[15][x] = "x"                                        # 띠 아랫단
    if rings >= 3:                                            # 신병 수실 — 띠 양끝에 늘어뜨린다
        g[15][cx0 - 1], g[15][cx1 + 1] = "T", "t"
    return g


WEAPON_SERIES = {          # 계열 = model_key 앞자리 (config item_channels.무기.series)
    "sword": sword_grid, "dao": dao_grid, "spear": spear_grid,
    "gauntlet": gauntlet_grid, "dagger": dagger_grid,
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


def write_item_assets() -> int:
    """무기 20(계열 5 × 등급 4) + 마병 1 + 지물·기물·재료 16 = 37종."""
    made = 0
    for series in WEAPON_SERIES:
        for grade, rings in WEAPON_GRADES:
            write_item_asset(f"weapon/{series}_{grade}", weapon_rows(series, rings), True)
            made += 1

    # 혈음도(마병) — identification.default가 "감정 전엔 정체 불명"이므로 미감정은 평범한 도.
    # 상태 분기는 custom_model_data.strings (§1.2 보조 채널) — 정수 CMD는 쓰지 않는다.
    write_item_asset("weapon/dao_mabyeong", weapon_rows("dao", 0, mabyeong=True), True)
    made += 1
    write_json(ITEM_DEF_DIR / "weapon" / "dao_mabyeong.json", {
        "model": {
            "type": "minecraft:select",
            "property": "minecraft:custom_model_data",
            "index": 0,
            "cases": [{
                "when": "revealed",
                "model": {"type": "minecraft:model", "model": "honcheon:item/weapon/dao_mabyeong"},
            }],
            # 미감정(기본) = 평범한 도 — 기연의 탈을 쓴 저주가 첫눈에 보이면 그건 저주가 아니다
            "fallback": {"type": "minecraft:model", "model": "honcheon:item/weapon/dao_jeongryeon"},
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

WIN_VBARS = (3, 6, 9, 12)   # 세로살 1px — 세살창
WIN_HBARS = (7,)            # 중간 가로살 1px


def lattice_window_rows():
    """세살창 — 1px 세로살 4대 + 중간 가로살 1대 + 창호지.
    1px 살대는 제 몸에 명암을 담을 수 없다 — 그래서 입체는 **창호지 쪽에서** 만든다:
    살 바로 오른쪽·아래 칸에 그림자를 앉히면 살이 종이 위로 떠오른다 (빛은 좌상단)."""
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
BAMBOO_SHADES = ramp((116, 106, 88, 255), (184, 172, 150, 255), 12)   # 12단 — 한 단 ≈ 5.8
# 쪽 단면 (x % 4) — 볼록한 대쪽. 좌측이 빛을 받고 우측이 그늘, 쪽과 쪽 사이가 골이다.
#   주기 4는 16의 약수 → 좌우 랩 경계가 내부 쪽 경계와 같은 위상이라 이음매 위험이 없다.
BAMBOO_CURVE = [1.3, 0.5, -0.4, -1.4]
# 마디(節) — 가로선 2개. 마디 홈(짙은 선) 바로 아래에 융기(밝은 선)가 온다.
#   주기 16(텍스처 한 장)이라 상하 랩에서 마디 간격이 일정하게 이어진다.
BAMBOO_NODES = (3, 11)


def bamboo_rows():
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            v = 6.2 + BAMBOO_CURVE[x % 4]
            v += octave(x, y, 2, 0xC1, 0.42) + octave(x, y, 1, 0xD3, 0.26)   # 대나무 결
            if y in BAMBOO_NODES:
                v -= 1.4                                # 마디 홈 — 가로 그늘 (약하게)
            elif (y - 1) in BAMBOO_NODES:
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
SHELF_WOOD = ramp((44, 34, 24, 255), (168, 138, 100, 255), 7)
SHELF_VOID = (18, 16, 14, 255)      # 빈 칸 깊은 어둠
SHELF_VOID_HI = (44, 38, 32, 255)   # 빈 칸 안쪽 바닥 — 위에서 든 빛이 겨우 닿는 곳
BRASS_HI = (204, 184, 132, 255)     # 놋 손잡이 광
BRASS_DIM = (104, 90, 60, 255)      # 놋 손잡이 그늘
SHELF_COLS = [(0, 4), (5, 10), (11, 15)]
SHELF_ROWS = [(0, 7), (8, 15)]


def shelf_grain(x, y, vertical, base=3.6):
    """목재 결 — 결 방향으로 길게 늘인 노이즈 (결은 한 방향으로 흐른다)."""
    gx, gy = (x, y // 4) if vertical else (x // 4, y)
    return base + octave(gx, gy, 1, 0x2D, 1.5) + octave(x, y, 1, 0x41, 0.4)


def shelf_face_rows(occupied):
    grid = [[SHELF_WOOD[2]] * 16 for _ in range(16)]      # 문선(칸 사이 기둥)
    for y in range(16):
        for x in range(16):
            grid[y][x] = step(SHELF_WOOD, shelf_grain(x, y, True, 2.6))
    for x0, x1 in SHELF_COLS:
        for y0, y1 in SHELF_ROWS:
            for y in range(y0, y1 + 1):
                for x in range(x0, x1 + 1):
                    if x == x0 or y == y0:
                        grid[y][x] = SHELF_WOOD[0]        # 칸 상·좌 — 인셋 그림자
                    elif x == x1 or y == y1:
                        grid[y][x] = SHELF_WOOD[5]        # 칸 하·우 — 빛 받는 턱
                    elif occupied:
                        grid[y][x] = step(SHELF_WOOD, shelf_grain(x, y, False, 4.2))
                    elif y == y1 - 1:
                        # 서랍 바닥판 — 열린 칸으로 빛이 들어 바닥이 환히 드러난다.
                        # 이 한 줄이 '빈 칸'에 깊이를 준다 (어둠만 칠하면 서랍이 아니라 검은 구멍이다).
                        # 판정상으로도 이 줄이 없으면 empty의 강한 가로 경계가 하나뿐이라
                        # 이음매 기준선(내부 경계 90퍼센타일)이 무너져 랩이 이상치로 몰린다 (1.31의 정체).
                        grid[y][x] = step(SHELF_WOOD, shelf_grain(x, y, False, 5.0))
                    else:
                        # 빈 서랍 속 뒷판 — 열린 칸 위로 든 빛이 뒷판 윗쪽을 스치고,
                        # 뒷판과 바닥이 만나는 아래 구석은 빛이 닿지 않아 가장 깊이 잠긴다.
                        # 그 구석의 어둠이 바로 밑 바닥판의 밝음과 부딪쳐 깊이를 만든다
                        # ('무(無)'가 아니다 — 어둠에 잠긴 뒷판의 결이 어스름히 비친다).
                        t = (y - y0) / max(1, (y1 - y0))
                        base = mix(SHELF_VOID_HI, SHELF_VOID, t)
                        n = shelf_grain(x, y, False, 0.0) * 5.0
                        grid[y][x] = tuple(max(0, min(255, round(c + n)))
                                           for c in base[:3]) + (255,)
            if occupied:                                   # 가로 놋 손잡이 (2px — 광 + 그늘)
                my = (y0 + y1) // 2
                for x in range(x0 + 2, x1 - 1):
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
SCROLL_ROD = ramp((36, 28, 20, 255), (116, 92, 64, 255), 5)   # 축(軸) — 나무 봉
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
        "light_gray_terracotta": plaster_rows((130, 132, 135, 255), (186, 187, 189, 255)),
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
    for name, rows in blocks.items():
        write_png(BLOCK_DIR / f"{name}.png", rows)
    for name in SCROLL_MOTIFS:
        write_png(PAINTING_DIR / f"{name}.png", scroll_rows(name))
    return len(blocks) + len(SCROLL_MOTIFS)


def write_json(path: Path, data):
    """산출 JSON — ensure_ascii=True (F26: PUA·비ASCII 리터럴이 조용히 유실되지 않게)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")


def main():
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
    # ─── 바닐라 HUD 텍스처 교체 (폰트 아님 — 스프라이트 직접 교체, 9x9 치수 계약) ───
    for name, art, palette in HEART_SPRITES:
        write_png(HUD_DIR / "heart" / f"{name}.png", paint_rows(art, palette))
    write_png(HUD_DIR / "hotbar.png", hotbar())
    write_png(HUD_DIR / "hotbar_selection.png", hotbar_selection())
    write_png(CONTAINER_DIR / "inventory.png", inventory_container())
    write_png(CONTAINER_DIR / "generic_54.png", generic_54_container())

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

    (PACK / "pack.mcmeta").write_text(json.dumps({
        "pack": {"pack_format": 46,
                 "description": "혼천 — 글리프 + HUD + 아이템·블록 텍스처 (M3)"}
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    total = 1 + 9 + len(REALM_CRESTS) + 1
    vanilla = len(HEART_SPRITES) + 2 + 2   # 하트 6 + 핫바 2 + 컨테이너 2
    print(f"팩 컴파일 완료: {PACK.relative_to(ROOT)} "
          f"(글리프 {total}종 + 음수공백 6폭 + 바닐라 교체 {vanilla}장 + 폰트 주입 + pack.mcmeta)")
    print(f"  아이템 채널 {items}종 (PNG {items} + 모델 {items} + 아이템 정의 {items}) — item_model, 전역 오염 0")
    print(f"  블록 징발 {blocks}장 (전역 치환 — block_channels.징발 등록분만)")


if __name__ == "__main__":
    main()
