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
ORB_OUT = (26, 24, 22, 255)         # 구슬 먹 테두리
ORB_EMPTY = (214, 205, 186, 80)     # 빈 소켓 내부 — 화선지 톤 희미하게
ORB_FILL = (150, 56, 44, 255)       # 주사 채움 (SEAL 동일 계열)
ORB_LIGHT = (208, 112, 88, 255)     # 좌상단 광택
ORB_DARK = (96, 34, 26, 255)        # 우하단 음영
BLINK_OUT = (240, 232, 214, 255)    # 피격 점멸 — 화선지 백 테두리 (바닐라 점멸=백화 관례)
BLINK_EMPTY = (240, 232, 214, 120)  # 점멸 빈 소켓 내부
BLINK_FILL = (208, 112, 88, 255)    # 점멸 채움 — 한 단계 밝은 주사
BLINK_LIGHT = (244, 202, 184, 255)  # 점멸 광택
BLINK_DARK = (150, 56, 44, 255)     # 점멸 음영
# 핫바 — 먹색 반투명 패널 + 화선지 테두리
HOT_PANEL = (26, 24, 22, 160)       # 반투명 먹 패널 (바닐라도 반투명 — 월드가 비친다)
HOT_EDGE = (214, 205, 186, 200)     # 화선지 테두리
HOT_DIV = (214, 205, 186, 60)       # 슬롯 구분 세로선 — 희미하게
SEL_FRAME = (150, 56, 44, 255)      # 선택 프레임 — 주사
# 인벤토리 컨테이너 — 화선지 몸체 + 먹 외곽 (명암 3톤은 바닐라 입체 문법 유지)
PAPER_BODY = (216, 208, 190, 255)   # 패널 몸체 — 화선지
PAPER_LIGHT = (238, 231, 214, 255)  # 상·좌 내부 프레임 밝은 획
PAPER_SHADOW = (172, 162, 140, 255) # 하·우 내부 프레임 음영
SLOT_DARK = (96, 86, 72, 255)       # 슬롯 상·좌 음영 (먹 계열)
SLOT_BASE = (176, 166, 146, 255)    # 슬롯 내부 (몸체보다 어둡게 — 바닐라 인셋 문법)
SLOT_LIGHT = (240, 234, 220, 255)   # 슬롯 하·우 광
WINDOW_INK = (18, 16, 14, 255)      # 인물 창 내부 — 짙은 먹


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
# 문자: # 테두리, ~ 빈 소켓 내부, o 채움, L 광택, D 음영
ORB_CONTAINER_ART = [
    ".........",
    "..#####..",
    ".#~~~~~#.",
    ".#~~~~~#.",
    ".#~~~~~#.",
    ".#~~~~~#.",
    ".#~~~~~#.",
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
ORB_PALETTE = {"#": ORB_OUT, "~": ORB_EMPTY, "o": ORB_FILL, "L": ORB_LIGHT, "D": ORB_DARK}
BLINK_PALETTE = {"#": BLINK_OUT, "~": BLINK_EMPTY, "o": BLINK_FILL, "L": BLINK_LIGHT, "D": BLINK_DARK}

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
    """182x22 핫바 (gui/sprites/hud/hotbar) — 먹색 반투명 패널 + 화선지 테두리.
    182 = 테두리 2 + 슬롯 9칸 x 20px. 아이템은 x=3+20i 오프셋에 얹힌다 (바닐라 렌더 계약).
    슬롯 경계 x=20·40·…·160 에 희미한 화선지 세로 구분선 — 배경은 조용하게."""
    width, height = 182, 22
    rows = []
    for y in range(height):
        row = []
        for x in range(width):
            if y in (0, height - 1) or x in (0, width - 1):
                row.append(HOT_EDGE)
            elif x % 20 == 0:   # x=20..160 (양끝 테두리 인접 제외 — 20*9=180 은 x=181 테두리 안쪽 몸체)
                row.append(HOT_DIV if x < 180 else HOT_PANEL)
            else:
                row.append(HOT_PANEL)
        rows.append(row)
    return rows


def hotbar_selection():
    """24x23 선택 프레임 (gui/sprites/hud/hotbar_selection) — 주사색 2px 테 + 투명 내부.
    치수 주의: 1.21.4 바닐라 스프라이트는 24x24가 아니라 24x23 (Gui가 24x23으로 blit,
    하단 1px이 핫바 아래로 걸치는 바닐라 관례) — 치수·걸침 인게임 확인 필요."""
    width, height = 24, 23
    rows = []
    for y in range(height):
        row = []
        for x in range(width):
            frame = x < 2 or x >= width - 2 or y < 2 or y >= height - 2
            row.append(SEL_FRAME if frame else T)
        rows.append(row)
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
                grid[y][x] = PAPER_BODY


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
    """인물 미리보기 창 — 먹 테두리 + 짙은 먹 내부 (장식 요소, 슬롯 아님)."""
    for dy in range(wh):
        for dx in range(ww):
            edge = dy in (0, wh - 1) or dx in (0, ww - 1)
            grid[wy + dy][wx + dx] = INK_SOLID if edge else WINDOW_INK


def inventory_container():
    """256x256 (유효 176x166) — 생존 인벤토리 (gui/container/inventory.png).
    슬롯 박스(아이템 좌표-1, InventoryMenu 계약): 방어구 (7,7+18r) r=0..3 /
    보조손 (76,61) / 제작 2x2 (97+18c,17+18r) / 제작 결과 (153,27) /
    본가방 (7+18c,83+18r) r=0..2 / 핫바열 (7+18c,141). 좌표 불변 — 색만 교체.
    인물 창(25,7,54x72)·제작 화살표는 장식 — 정확 위치 인게임 확인 필요."""
    grid = blank_canvas()
    draw_panel(grid, 176, 166)
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
    for r in range(6):
        for c in range(9):
            draw_slot(grid, 7 + 18 * c, 17 + 18 * r)    # 궤짝 9x6
    for r in range(3):
        for c in range(9):
            draw_slot(grid, 7 + 18 * c, 139 + 18 * r)   # 플레이어 인벤 9x3
    for c in range(9):
        draw_slot(grid, 7 + 18 * c, 197)                # 핫바열
    return grid


def gui_background():
    """176x110 경락도 GUI 배경 패널 — 먹색 + 화선지 테두리 + 수묵 표구 장식.
    - 모서리: 전각 도장풍 주사색 ㄱ자 쌍획 4귀 (인장 테두리 모티프, 4px 인셋·팔 6px·2px 두께)
    - 제목 구분선: y=17 가로선 — 상단 16px 제목 영역과 본문 분리
    - 여백 가이드: 5px 인셋 점선 사각 — 내용 배치 기준선 (희미한 화선지 톤, 배경은 조용하게)
    인벤토리 제목에 음수 공백으로 얹는 기법용. 정확한 오프셋은 인게임 튜닝 (플레이스홀더)."""
    width, height = 176, 110
    grid = []
    for y in range(height):
        row = []
        for x in range(width):
            edge = y in (0, 1, height - 2, height - 1) or x in (0, 1, width - 2, width - 1)
            row.append(INK_EDGE if edge else INK)
        grid.append(row)

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
                    grid[oy + sy * t][ox + sx * a] = SEAL   # 가로 팔
                    grid[oy + sy * a][ox + sx * t] = SEAL   # 세로 팔
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


def _hilt(g, rings, mabyeong, gx, gy, guard, slots):
    """자루 한 벌 — 감기 5걸음 + 고리 + 놋 물미 + 신병 수실 + 마병 혈적.
    검·도·비수가 공유한다 (자루는 계열이 아니라 등급이 말하는 부위다)."""
    band(g, gx, gy, 5, wrap_grip, sx=-1, sy=1)
    band(g, *guard, 5, ["G", "g", "f"], sx=1, sy=1)          # 코등이 — 날에 수직인 가로대
    put_rings(g, gx, gy, rings, slots)
    band(g, gx - 4, gy + 4, 1, ["G", "g", "f"])              # 물미(자루 끝 놋)
    if rings >= 3:                                            # 신병 수실 — 물미에 매단다
        g[gy + 5][gx - 4] = "T"
        g[gy + 5][gx - 3] = "t"
    if mabyeong:                                              # 마병 혈적 — 자루 끝의 낙인
        g[gy + 4][gx - 2] = "M"


def sword_grid(rings, mabyeong):
    """검(劍) — 곧은 양날 + 날에 수직으로 가로지르는 긴 코등이."""
    g = blank16()
    band(g, 6, 8, 5, blade_strands(mabyeong, False))          # 날 y=8..4
    band(g, 11, 3, 1, ["H", "L"])                             # 칼끝 2px
    band(g, 12, 2, 1, ["H"])                                  # 칼끝 1px
    _hilt(g, rings, mabyeong, 5, 9, (4, 6), (1, 2, 3))
    return g


def dao_grid(rings, mabyeong):
    """도(刀) — 한날. 코등이는 짧은 원반(圓盤)이라 검과 실루엣이 갈린다."""
    g = blank16()
    band(g, 6, 8, 5, blade_strands(mabyeong, True))
    band(g, 11, 3, 1, ["H", "L"])
    band(g, 12, 2, 1, ["H"])
    _hilt(g, rings, mabyeong, 5, 9, (5, 7), (1, 2, 3))        # 원반 호수 — 짧게
    return g


def dagger_grid(rings, mabyeong):
    """비수(匕首) — 짧은 날. 자루가 날보다 길다 (비율이 곧 정체다)."""
    g = blank16()
    band(g, 7, 9, 4, ["H", "L", "m" if mabyeong else "S"])    # 짧은 날 y=9..6
    band(g, 11, 5, 1, ["H"])
    _hilt(g, rings, mabyeong, 6, 10, (5, 8), (1, 2, 3))
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


# 권갑(拳甲) — 날이 없다. 손등 판의 못머리와 손목 띠로 읽힌다.
# 고리(등급)는 손목 띠를 감는 금속 테 개수 — 다른 무기와 같은 문법이다.
GAUNTLET_ART = [
    "................",
    "....GGGGG.......",
    "...GgggggG......",
    "...GgfffgG......",
    "...GgfGfgG......",
    "...GgfffgG......",
    "...GgggggG......",
    "...ffffffG......",
    "....WwwwX.......",
    "....11111.......",
    "....WwwwX.......",
    "....22222.......",
    "....WwwwX.......",
    "....33333.......",
    "....XxwwX.......",
    "................",
]


def gauntlet_grid(rings, mabyeong):
    g = [list(r) for r in GAUNTLET_ART]
    for n in (1, 2, 3):
        for y in range(16):
            for x in range(16):
                if g[y][x] == str(n):
                    # 점등 = 2톤 금속 테 (아래 절반이 그늘) / 미점등 = 자루색 — 구멍이 나지 않는다
                    g[y][x] = ("R" if x < 7 else "e") if rings >= n else "x"
    if mabyeong:
        g[4][6] = "M"                                         # 손등 못머리에 밴 혈적
    if rings >= 3:
        g[14][3] = g[14][9] = "t"                             # 수실 — 손목 띠 양끝
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
PELT_WOLF_ART = [         # 늑대 가죽 — 펼쳐 못 박은 네 다리
    "................",
    "................",
    "...lL......Ln...",
    "...lL......Ln...",
    "..llllllllllln..",
    "..lLLLLLLLLLLn..",
    "..lLLLLLLLLLLn..",
    "..lLLLLLLLLLLn..",
    "..lLLLLLLLLLLn..",
    "..lLLLLLLLLLLn..",
    "..nnnnnnnnnnnn..",
    "...nL......Ln...",
    "...nL......Ln...",
    "................",
    "................",
    "................",
]
PELT_FOX_ART = [          # 여우 가죽 — 같은 못질 윤곽이되 확연히 작다 + 붉은 털 한 점 (시세 3배의 표식)
    "................",
    "................",
    "....lL...Ln.....",
    "....lL...Ln.....",
    "...lllllllln....",
    "...lLLLLLLLn....",
    "...lLLLLLLLn....",
    "...lLrLLLLLn....",
    "...lLLLLLLLn....",
    "...nnnnnnnnn....",
    "....nL...Ln.....",
    "....nL...Ln.....",
    "................",
    "................",
    "................",
    "................",
]
PELT_TIGER_ART = [        # 호랑이 가죽 — 늑대와 같은 못질 윤곽 + 끊기지 않는 세로 줄무늬.
    "................",   # 줄무늬를 행마다 끊으면 격자(와플)가 되고, 윤곽이 네모나면 널빤지가 된다 —
    "................",   # 다리를 달아야 비로소 '가죽'으로 읽힌다 (150배 값은 색이 아니라 이름이 판다)
    "...lL......Ln...",
    "...lL......Ln...",
    "..llllllllllln..",
    "..lLNLLNNLLNLn..",
    "..lLNLLNNLLNLn..",
    "..lLNLLNNLLNLn..",
    "..lLNLLNNLLNLn..",
    "..lLNLLNNLLNLn..",
    "..nnnnnnnnnnnn..",
    "...nL......Ln...",
    "...nL......Ln...",
    "................",
    "................",
    "................",
]
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
# 가로(x, 주기 8): 수키와(丸瓦 — 볼록한 반원통) 5px + 암키와(平瓦 — 오목한 골) 3px 교대.
#   → 넓고 둥근 마루 / 좁고 깊은 골. 지붕이 '결'을 갖는 건 이 대비 덕이다.
# 세로(y, 주기 16 = 텍스처 한 장): 한 장에 기와 한 단(course).
#   지붕은 인게임에서 계단 블록으로 쌓인다 — 블록 하나가 곧 한 단이다. 한 장에 단을 둘 넣으면
#   가로 리듬이 세로 리듬과 같은 세기로 부딪혀 격자무늬(체크)가 된다. 이게 첫 시도의 실패 모드였다.
# 랩 안전: x는 8(16의 약수), y는 16 그 자체 — 위아래로 이으면 아랫단 처마 그늘이 윗단 겹침
#   그늘로 그대로 이어진다.
ROOF_SHADES = ramp((23, 23, 28, 255), (103, 104, 113, 255), 9)   # 9단 — 먹빛 청회색 기와
# 곡면 명암 (x % 8) — 빛은 좌상단. 볼록한 마루는 왼쪽 어깨가 가장 밝고 오른쪽으로 떨어진다.
#   0 마루 왼쪽 이음 그늘 / 1~2 마루 하이라이트·등 / 3~4 우측 falloff / 5~7 골 (마루 그림자가 진다)
ROOF_CURVE = [-1.0, 2.6, 2.0, 0.6, -0.9, -2.2, -1.7, -2.0]
# 단(course) 명암 (y) — 윗단 기와가 덮은 자리(y0~1)가 가장 어둡고, 노출된 기와 코(y2)가 빛을 받는다.
# 아래로 완만히 어두워지다가 기와 끝 젖힌 턱(y13~14)이 다시 빛을 받고, 그 아래(y15)가 그늘 —
# 그 그늘이 곧 아랫단의 y0 겹침 그늘로 이어진다 (랩이 곧 구조).
ROOF_COURSE = [-2.9, -1.4, 1.2, 0.8, 0.5, 0.3, 0.2, 0.0,
               0.0, -0.1, -0.2, -0.2, -0.1, 0.3, 0.6, -1.5]
# 마루(수키와)는 둥근 코가 다음 장을 타고 넘으므로 이음 턱이 얕다 — 단 그림자를 절반만 받는다.
# 이 감쇠가 없으면 가로 검은 띠가 마루를 관통해 다시 격자가 된다.
ROOF_CAP_DAMP = 0.45
ROOF_MID = 4.8          # 계단 중앙값 — 얼룩·곡면이 여기서 위아래로 흔들린다


def roof_stain(x, y):
    """오래된 기와의 얼룩 — 저주파(4px) 큰 얼룩 + 중주파(2px) + 픽셀 결.
    구조(곡면·단)를 덮지 않을 만큼만. 셀 크기 4·2 는 16의 약수라 랩에서 끊기지 않는다."""
    return (smooth_octave(x, y, 8, 0x11, 0.80)
            + smooth_octave(x, y, 4, 0x23, 0.50)
            + octave(x, y, 1, 0x37, 0.28))


def roof_rows(cracked=False):
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            damp = ROOF_CAP_DAMP if x % 8 < 5 else 1.0      # 마루는 단 그림자를 덜 받는다
            v = ROOF_MID + ROOF_CURVE[x % 8] + ROOF_COURSE[y] * damp + roof_stain(x, y)
            row.append(step(ROOF_SHADES, v))
        rows.append(row)
    if cracked:
        for (x, y), d in ROOF_CRACKS:
            row = rows[y]
            row[x] = ROOF_SHADES[0] if d < 0 else step(ROOF_SHADES, 7.2)   # 깨진 틈 / 파단면 光
    return rows


# 깨진 기와(폐사당 잔해) — 같은 결 위의 균열이라야 같은 지붕으로 읽힌다.
# d=-1 어둠(갈라진 틈) / d=+1 파단면 하이라이트 — 틈의 좌상단에 붙여 깊이를 만든다.
ROOF_CRACKS = [((4, 1), -1), ((4, 2), -1), ((3, 2), 1), ((5, 3), -1), ((5, 4), -1),
               ((4, 4), 1), ((6, 5), -1), ((6, 6), -1), ((7, 7), -1), ((6, 7), 1),
               ((11, 2), -1), ((12, 3), -1), ((11, 3), 1), ((12, 4), -1),
               ((13, 10), -1), ((13, 11), -1), ((12, 11), 1), ((14, 12), -1),
               ((2, 11), -1), ((2, 12), -1), ((1, 12), 1), ((3, 13), -1), ((3, 14), -1),
               ((9, 13), -1), ((9, 14), -1), ((8, 14), 1)]


# ─── 회벽(灰壁) — 거친 회칠. 무늬·이음선 금지(block_channels 조건): 배들랜드 지층에
#     대량 자연 생성되므로 무늬가 있으면 지층이 벽으로 튄다.
#     '균열'을 직선으로 그으면 그 선이 16px마다 되풀이돼 벽 전체에 사선 격자가 뜬다 (실측 확인).
#     그래서 형상(선·점)은 하나도 두지 않는다 — 4옥타브 노이즈장만으로 얼룩과 팬 자국을 만든다.
#     저주파는 보간 노이즈(smooth_octave)라야 한다. 계단형이면 4px 네모 얼룩이 그대로 보인다.
def plaster_rows(dark, light):
    """회벽 — 6단 저대비 계단. 명도 폭이 좁아 바닐라 배들랜드/석재 옆에서 튀지 않는다."""
    shades = ramp(dark, light, 6)
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            v = (2.6
                 + smooth_octave(x, y, 8, 0x5B, 0.95)   # 큰 얼룩 — 미장의 물결
                 + smooth_octave(x, y, 4, 0x6D, 0.70)   # 흙손 자국
                 + octave(x, y, 2, 0x7F, 0.55)          # 거친 결
                 + octave(x, y, 1, 0x91, 0.60))         # 모래 입자
            if h32(x, y, 0xA7) % 23 == 0:
                v -= 1.6                                # 회칠이 패인 곰보 자국 (선이 아니라 점 — 격자가 안 생긴다)
            row.append(step(shades, v))
        rows.append(row)
    return rows


# ─── 격자창 — glass.png(면). glass_pane와 유리 블록이 면 텍스처를 공유하므로
#     유리도 격자창이 된다: 전근대 강호에 판유리는 없다 — 오염이 아니라 정합.
#     창살은 목재(4단 명암), 창호(窓戶)는 창호지 — 따뜻한 반투명 백지.
#     알파 주의: 바닐라 glass 는 cutout 렌더(알파 이진 판정)라 부분 알파는 사실상 불투명으로
#     보인다. 창호지는 원래 들여다보이지 않는 종이다 — 이 렌더 특성이 곧 의도한 그림이다.
WOOD_HI = (138, 110, 78, 255)     # 창살 — 빛 받는 위/왼쪽 모
WOOD_MID = (104, 82, 58, 255)     # 창살 몸
WOOD_DIM = (74, 58, 40, 255)      # 창살 그늘
WOOD_OUT = (44, 34, 24, 255)      # 창틀 외곽 — 가장 어두운 목재
WIN_PAPER_HI = (240, 232, 206, 190)   # 창호지 — 살에 닿는 밝은 결
WIN_PAPER_MID = (226, 216, 188, 175)  # 창호지 몸
WIN_PAPER_DIM = (206, 195, 166, 165)  # 창호지 그늘 (살 그림자가 지는 아래·오른쪽)


def lattice_window_rows():
    """井자 창살(x·y = 5, 10) + 창호지 4×4 칸. 살마다 좌상 하이라이트·우하 그림자."""
    bars = (5, 10)
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            on_frame = x in (0, 15) or y in (0, 15)
            on_bar = x in bars or y in bars
            if on_frame:
                # 창틀 — 좌·상은 한 단 밝게, 우·하는 최암 (테두리도 입체다)
                row.append(WOOD_MID if (x == 0 or y == 0) else WOOD_OUT)
            elif on_bar:
                # 살의 좌상 모서리(살 바로 앞 칸)는 빛, 우하는 그늘
                lit = (x in bars and y not in bars and x == 5) or (y in bars and y == 5)
                row.append(WOOD_HI if lit else WOOD_MID if (x == 10 or y == 10) else WOOD_DIM)
            else:
                # 창호지 — 살에 인접한 위/왼쪽은 밝고, 아래/오른쪽엔 살 그림자가 앉는다
                near_lit = (x - 1) in bars or (y - 1) in bars or x == 1 or y == 1
                near_dim = (x + 1) in bars or (y + 1) in bars or x == 14 or y == 14
                base = WIN_PAPER_DIM if near_dim else WIN_PAPER_HI if near_lit else WIN_PAPER_MID
                # 종이 섬유 — 1px 결정론 결. 알파는 건드리지 않는다 (컷아웃 안정)
                f = h32(x, y, 0x9C) % 3
                row.append(base if f else mix(base, WIN_PAPER_HI, 0.35)[:3] + (base[3],))
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
#     쪽 폭 4px (16의 약수) → 좌우 랩 매끄러움. 마디(節)는 8행 주기이되 쪽마다 어긋난다
#     (결정론 오프셋) — 일직선 마디는 격자로 보이지만, 어긋난 마디는 결로 보인다.
BAMBOO_SHADES = ramp((92, 80, 50, 255), (222, 210, 164, 255), 7)
BAMBOO_CURVE = [-2.4, 1.9, 0.6, -0.9]   # 쪽 단면(볼록): 골 / 좌측 광 / 몸 / 우측 falloff


def bamboo_rows():
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            strip = x // 4                              # 쪽 번호 0~3
            v = 4.0 + BAMBOO_CURVE[x % 4] + octave(x, y, 2, 0xC1, 0.45)
            v += octave(x, y, 1, 0xD3, 0.25)
            node = (y + (h32(strip, 0xE7) % 5)) % 8     # 쪽마다 어긋난 마디 위상
            if node == 0:
                v -= 2.6                                # 마디 홈 — 가로 그늘
            elif node == 1:
                v += 1.1                                # 마디 아래 융기 — 빛 받는 턱
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
                    else:
                        # 빈 서랍 속 — 위는 칠흑, 아래로 갈수록 바닥이 희미하게 보인다
                        t = (y - y0) / max(1, (y1 - y0))
                        grid[y][x] = mix(SHELF_VOID, SHELF_VOID_HI, t)
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
PAINTING_PALETTE = {
    ".": PAPER, "#": INK_SOLID, ",": (118, 110, 100, 255), "r": SEAL, "h": (52, 42, 32, 255),
}
SCROLL_MOTIFS = {          # 12행 × 16열 — 위아래 축(軸) 2행씩을 더해 16x16
    "kebab": [             # 산수(山水)
        "................", "................",
        ".......##.......",
        "......####......",
        ".....##..##.....",
        "....###..###....",
        "..####....####..",
        ".##############.",
        "................",
        "..,,,,,,,,,,,...",
        "...,,,,,,,,,....",
        ".r..............",
    ],
    "aztec": [             # 죽(竹)
        "................",
        "....#....#......",
        "...#,#..#,#.....",
        "...#,#..#,#.....",
        "..##,##.#,#.....",
        "...#,#..#,#.....",
        "...#,#.##,##....",
        "...#,#..#,#.....",
        "...#,#..#,#.....",
        "...###..###.....",
        "................",
        ".r..............",
    ],
    "alban": [             # 서(書) — 획만 남은 글씨
        "................",
        "...####...##....",
        "....#......#....",
        "....#....####...",
        "..#####....#....",
        "....#......#....",
        "...###...####...",
        "..##.##.........",
        ".##...##...##...",
        "..........####..",
        "................",
        ".r..............",
    ],
    "wasteland": [         # 난(蘭)
        "................",
        "..........#.....",
        ".......#..#.....",
        "......#,#.#.....",
        ".....#,,,##.....",
        "......#,#.#.....",
        ".......#..#.....",
        "........#.#.....",
        ".........##.....",
        "..........#.....",
        "........###.....",
        ".r..............",
    ],
}


def scroll_art(motif):
    return ["h" * 16, "h" * 16] + list(motif) + ["h" * 16, "h" * 16]


def write_block_textures() -> int:
    """징발 등록부 순회 — 바닐라 경로에 16x16 덮어쓰기 (blockstate/model JSON 불요)."""
    blocks = {
        "deepslate_tiles": roof_rows(),
        "cracked_deepslate_tiles": roof_rows(cracked=True),
        "white_terracotta": plaster_rows((186, 179, 165, 255), (236, 231, 219, 255)),
        "light_gray_terracotta": plaster_rows((142, 138, 131, 255), (190, 186, 179, 255)),
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
    for name, motif in SCROLL_MOTIFS.items():
        write_png(PAINTING_DIR / f"{name}.png", paint_rows(scroll_art(motif), PAINTING_PALETTE))
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
