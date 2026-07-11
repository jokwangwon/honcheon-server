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
# 무기 팔레트 — 등급 색은 바닐라 재질이 이미 말한다. 텍스처는 형태(실루엣·고리)에 집중.
BLADE = (176, 178, 182, 255)      # 날 — 저채도 강철
BLADE_EDGE = (128, 132, 138, 255) # 날 중심선/인(刃) 음영
FITTING = (150, 140, 116, 255)    # 호수(護手)·장식 쇠붙이 — 놋
GRIP = (74, 58, 44, 255)          # 자루 — 어두운 나무·가죽
RING = (200, 192, 168, 255)       # 자루 고리(등급 표식) — 화선지 톤 금속 테
TASSEL = (150, 56, 44, 255)       # 신병 수실 — 주사
BLOOD = (140, 30, 26, 255)        # 마병 혈적 — 다른 계보임을 형태로 선언 (위가 아니라 밖)

# 지물·기물·재료 공용 팔레트 (먹·화선지·주사 3계열 + 재질 최소값)
PAPER = (216, 208, 190, 255)      # 화선지
PAPER_DIM = (176, 166, 146, 255)  # 화선지 음영(접힘)
CORD = (74, 58, 44, 255)          # 끈·자루
BRASS = (150, 140, 116, 255)      # 놋·인패
JADE = (108, 142, 136, 255)       # 피독주 청록
GLOW = (240, 232, 214, 255)       # 야명주 — 유일하게 밝은 값 허용
GLOW_HI = (255, 252, 240, 255)
HIDE = (140, 116, 88, 255)        # 가죽
GALL = (104, 102, 66, 255)        # 웅담 — 녹갈
HERB = (108, 124, 84, 255)        # 약재 — 마른 풀

# ─── 무기 실루엣 16x16 (외곽 1px 여백 준수) ───
# 문자: # 먹 외곽 / b 날 / v 날 중심선(마병=혈적) / g 호수 / h 자루 / r 홍영(창 전용)
#       1·2·3 자루 고리 슬롯(등급별 점등, 미점등은 자루색) / t 수실(신병) / m 혈적(마병)
SWORD_ART = [   # 검 — 곧은 양날 + 대각 호수 + 자루
    "................",
    ".............##.",
    "............#bv.",
    "...........#bv#.",
    "..........#bv#..",
    ".........#bv#...",
    "...#....#bv#....",
    "....g..#bv#.....",
    ".....g#bv#......",
    ".....#gv#.......",
    "....#3#g........",
    "...#23#.#.......",
    "..#12#..........",
    ".t#1#...........",
    ".t#m............",
    "................",
]
DAO_ART = [     # 도 — 한날(등 = 먹 척, 인 = v) + 원반 호수
    "................",
    "............##..",
    "...........#bb#.",
    "..........#bbv#.",
    ".........#bbv#..",
    "........#bbv#...",
    ".......#bbv#....",
    "......#bbv#.....",
    ".....#bbv#......",
    "....#gg#........",
    "....#3#.........",
    "...#23#.........",
    "..#12#..........",
    ".t#1#...........",
    ".t#m............",
    "................",
]
SPEAR_ART = [   # 창 — 좁은 삼각 창날 + 홍영 + 긴 자루
    "................",
    ".............#..",
    "............#b#.",
    "...........#bv#.",
    "..........#bv#..",
    ".........#bv#...",
    "........#rr#....",
    ".......#hh#.....",
    "......#hh#......",
    ".....#hh#.......",
    "....#hh#........",
    "...#33#.........",
    "..#22#..........",
    ".t11#...........",
    ".t##m...........",
    "................",
]
GAUNTLET_ART = [  # 권갑 — 손등 판 + 손목 띠 (날 없음: 고리는 띠의 밝은 줄로 읽힌다)
    "................",
    "....#####.......",
    "...#ggggg#......",
    "...#g###g#......",
    "...#ggggg#......",
    "...#g###g#......",
    "...#ggggg#......",
    "...#######......",
    "....#hhh#.......",
    "....#333#.......",
    "....#hhh#.......",
    "....#222#.......",
    "....#hhh#.......",
    "....#111#.......",
    "...t#h#m........",
    "................",
]
DAGGER_ART = [  # 단검 — 짧은 날 + 손잡이 절반 비율
    "................",
    "................",
    "................",
    "...........#....",
    "..........#b#...",
    ".........#bv#...",
    "........#bv#....",
    ".......#bv#.....",
    ".....#gg#.......",
    ".....#hh#.......",
    "....#hh#........",
    "...#33#.........",
    "..#22#..........",
    ".t11#...........",
    ".t##m...........",
    "................",
]
WEAPON_SERIES = {          # 계열 = model_key 앞자리 (config item_channels.무기.series)
    "sword": SWORD_ART, "dao": DAO_ART, "spear": SPEAR_ART,
    "gauntlet": GAUNTLET_ART, "dagger": DAGGER_ART,
}
# 등급 = 베이스 바닐라 아이템(팩 게이트). 여기서는 고리 수만 쥔다 — 색은 바닐라 재질의 몫.
WEAPON_GRADES = [("beomcheol", 0), ("jeongryeon", 1), ("bobyeong", 2), ("sinbyeong", 3)]


def weapon_palette(rings: int, mabyeong: bool = False):
    """등급 표식 팔레트 — 고리 수(0~3) + 신병 수실 + 마병 혈적.
    colorblind_rule: 색 단독 금지 → 회색조에서도 고리 수로 등급이 읽힌다."""
    pal = {
        "#": INK_SOLID, "b": BLADE, "g": FITTING, "h": GRIP, "r": SEAL,
        "v": BLOOD if mabyeong else BLADE_EDGE,      # 마병: 날 중심 혈적 세로선
        "t": TASSEL if rings >= 3 else T,            # 신병만 수실
        "m": BLOOD if mabyeong else T,               # 마병만 자루 끝 혈적 1점
    }
    for n in (1, 2, 3):
        pal[str(n)] = RING if rings >= n else GRIP   # 미점등 고리는 자루색 — 구멍 나지 않는다
    return pal


# ─── 지물·기물·재료 16x16 ───
# 문자: # 먹 / p 화선지 / P 화선지 음영 / r 주사 / h 끈·나무 / g 놋 / j 청록 / w 광 / W 강광
#       L 가죽 / G 녹갈·초록 / o 단약 채움 / O 단약 광점
MANUAL_ORIGINAL_ART = [   # 비급 진본 — 죽간 말이 + 봉인 끈 + 주사 인장 (진본의 차이 = 인장)
    "................", "................", "................",
    "...##########...",
    "..#pppppppppp#..",
    "..#p#p#p#p#pp#..",
    "..#p#p#p#p#pp#..",
    "..hhhhhhhhhhhh..",
    "..hhhhhhhhhhhh..",
    "..#p#p#p#p#pp#..",
    "..#prr#p#p#pp#..",
    "..############..",
    "................", "................", "................", "................",
]
MANUAL_COPY_ART = [       # 비급 필사본 — 얇은 철(綴), 인장 없음
    "................", "................", "................", "................",
    "...##########...",
    "..#pppppppppp#..",
    "..#p#p#p#p#pp#..",
    "..#p#p#p#p#pp#..",
    "..#p#p#p#p#pp#..",
    "..#p#p#p#p#pp#..",
    "..#pppppppppp#..",
    "...##########...",
    "................", "................", "................", "................",
]
GUGYEOL_ART = [           # 심법 구결 — 접힌 낱장 + 붓 자국 세로 3획 (베껴 적는 것)
    "................", "................", "................",
    "...##########...",
    "..#pppppppppp#..",
    "..#p##p##p##p#..",
    "..#p##p##p##p#..",
    "..#p##p##p##p#..",
    "..#p##p##p##p#..",
    "..#pppppppppp#..",
    "..#ppppppppPP#..",
    "...#########P#..",
    "..........###...",
    "................", "................", "................",
]
JEONPYO_ART = [           # 전표 — 가로 종이 + 전장 인장(주사) + 액면 획 (액면은 lore, 텍스처 1장)
    "................", "................", "................", "................",
    "..############..",
    "..#pppppppppp#..",
    "..#rrpppppppp#..",
    "..#rrpppppppp#..",
    "..#pppppppppp#..",
    "..#p########p#..",
    "..#pppppppppp#..",
    "..#p######ppp#..",
    "..############..",
    "................", "................", "................",
]
YEONGYAK_ART = [          # 단약(영약) — 구형 + 좌상 광점 (HUD 기혈 구슬과 같은 문법)
    "................", "................", "................",
    "......####......",
    "....##oooo##....",
    "...#OOoooooo#...",
    "..#Ooooooooo#...",
    "..#ooooooooo#...",
    "..#ooooooooo#...",
    "..#ooooooooo#...",
    "...#ooooooo#....",
    "....#######.....",
    "................", "................", "................", "................",
]
PYOMUL_ART = [            # 표물 — 궤 + 봉인 끈 십자 + 표국 인패 (chest_minecart: 레일 없는 세계에선 설치 불가)
    "................", "................", "................",
    "..############..",
    "..#LLLLhhLLLL#..",
    "..#LLLLhhLLLL#..",
    "..############..",
    "..#LLLLhhLLLL#..",
    "..#hhhhhhhhhh#..",
    "..#LLLLhhLLLL#..",
    "..#LLLLrrLLLL#..",
    "..#LLLLhhLLLL#..",
    "..############..",
    "................", "................", "................",
]
PIDOKJU_ART = [           # 피독주 — 구슬 + 표면 균열 2획 (독을 머금은 흠)
    "................", "................", "................",
    "......####......",
    "....##jjjj##....",
    "...#jjj#jjjj#...",
    "..#jjj#jjjjj#...",
    "..#jjjjj#jjj#...",
    "..#jjjj#jjjj#...",
    "..#jjjjjjjjj#...",
    "...#jjjjjjj#....",
    "....#######.....",
    "................", "................", "................", "................",
]
YAMYEONGJU_ART = [        # 야명주 — 구슬 + 방사 광선 4방 1px (유일하게 밝은 값 허용)
    "................",
    ".......w........",
    "................",
    "......####......",
    "....##WWWW##....",
    "...#WWwwwwww#...",
    "..#Wwwwwwwww#...",
    ".w#wwwwwwwww#w..",
    "..#wwwwwwwww#...",
    "..#wwwwwwwww#...",
    "...#wwwwwww#....",
    "....#######.....",
    "................",
    ".......w........",
    "................", "................",
]
CHEONGNANG_ART = [        # 청낭 — 아가리 묶은 주머니 + 침 3개 (의술의 표식)
    "................",
    "....#..#..#.....",
    "....#..#..#.....",
    "...hhhhhhhhhh...",
    "..#LLLLLLLLLL#..",
    "..#LLLLLLLLLL#..",
    ".#LLLLLLLLLLLL#.",
    ".#LLLLLLLLLLLL#.",
    ".#LLLLLLLLLLLL#.",
    ".#LLLLLLLLLLLL#.",
    "..#LLLLLLLLLL#..",
    "...##########...",
    "................", "................", "................", "................",
]
HOSINBU_ART = [           # 호신부 — 세로 부적 + 주사 부문 3획 + 끈 고리
    "................",
    "......hhhh......",
    "....########....",
    "....#pppppp#....",
    "....#prrrrp#....",
    "....#pppppp#....",
    "....#prrrrp#....",
    "....#pppppp#....",
    "....#prrrrp#....",
    "....#pppppp#....",
    "....#pppppp#....",
    "....#pppppp#....",
    "....########....",
    "................", "................", "................",
]
YODAE_ART = [             # 천잠사 요대 — 감긴 띠 + 매듭 + 은사 광택 1px
    "................", "................", "................",
    "...##########...",
    "..#hhhhhhhhhh#..",
    "..#whhhhhhhhh#..",
    "..############..",
    "..#hhhhhhhhhh#..",
    "..#hhhhhhhhwh#..",
    "..############..",
    "..#hhhh##hhhh#..",
    "..#hhh#gg#hhh#..",
    "..############..",
    "................", "................", "................",
]
PELT_WOLF_ART = [         # 늑대 가죽 — 펼친 가죽 + 네 발 외곽 (가죽 3종은 윤곽으로 구별)
    "................", "................",
    "..##........##..",
    "..#LL#....#LL#..",
    "..#LLL####LLL#..",
    "...#LLLLLLLL#...",
    "...#LLLLLLLL#...",
    "...#LLLLLLLL#...",
    "...#LLLLLLLL#...",
    "...#LLLLLLLL#...",
    "..#LLL####LLL#..",
    "..#LL#....#LL#..",
    "..##........##..",
    "................", "................", "................",
]
PELT_FOX_ART = [          # 여우 가죽 — 좁고 긴 형 + 꼬리 술 (붉은 기 한 점 = 시세 3배의 표식)
    "................",
    "......##........",
    ".....#LL#.......",
    ".....#LL#.......",
    "....#LLLL#......",
    "....#LLLL#......",
    "....#LLLL#......",
    "....#LLLL#......",
    "....#LLLL#......",
    "....#LLLL#......",
    ".....#LL##......",
    ".....#LLL#......",
    "......#LLL#.....",
    ".......#Lr#.....",
    ".......###......",
    "................",
]
PELT_TIGER_ART = [        # 호랑이 가죽 — 넓은 형 + 줄무늬 3획 (150배 가격은 색이 아니라 이름이 판다)
    "................", "................",
    "..############..",
    ".#LLLLLLLLLLLL#.",
    ".#L##LL##LL##L#.",
    ".#LLLLLLLLLLLL#.",
    ".#L##LL##LL##L#.",
    ".#LLLLLLLLLLLL#.",
    ".#L##LL##LL##L#.",
    ".#LLLLLLLLLLLL#.",
    ".#LLLLLLLLLLLL#.",
    "..############..",
    "................", "................", "................", "................",
]
UNGDAM_ART = [            # 웅담 — 쓸개 주머니 + 매단 끈 1
    "................",
    ".......h........",
    ".......h........",
    "......####......",
    ".....#GGGG#.....",
    "....#GGGGGG#....",
    "...#GGGGGGGG#...",
    "...#GGGGGGGG#...",
    "...#GGGGGGGG#...",
    "...#GGGGGGGG#...",
    "....#GGGGGG#....",
    ".....######.....",
    "................", "................", "................", "................",
]
YAKJAE_ART = [            # 약재 — 묶은 약초 다발 + 끈 2줄 (널어 둔 약초와 동형)
    "................",
    "...G.G.G.G.G....",
    "...G.G.G.G.G....",
    "...GGGGGGGGG....",
    "....GGGGGGG.....",
    "....hhhhhhh.....",
    "....GGGGGGG.....",
    "....GGGGGGG.....",
    "....hhhhhhh.....",
    "....GGGGGGG.....",
    ".....GGGGG......",
    ".....G.G.G......",
    ".....G.G.G......",
    "................", "................", "................",
]
GOODS_PALETTE = {
    "#": INK_SOLID, "p": PAPER, "P": PAPER_DIM, "r": SEAL, "h": CORD, "g": BRASS,
    "j": JADE, "w": GLOW, "W": GLOW_HI, "L": HIDE, "G": HERB,
    "o": ORB_FILL, "O": ORB_LIGHT,
}
# 지물/기물/재료 등록표 — key = model_key 경로 (config item_channels 등록분 그대로. 발명 0건)
GOODS_ART = {
    "tome/manual_original": MANUAL_ORIGINAL_ART,
    "tome/manual_copy": MANUAL_COPY_ART,
    "tome/gugyeol": GUGYEOL_ART,
    "coin/jeonpyo": JEONPYO_ART,
    "pill/yeongyak": YEONGYAK_ART,
    "cargo/pyomul": PYOMUL_ART,
    "trinket/pidokju": PIDOKJU_ART,
    "trinket/yamyeongju": YAMYEONGJU_ART,
    "trinket/cheongnang": CHEONGNANG_ART,      # status: 보류 — 텍스처만 준비, 지급은 접합 후
    "trinket/hosinbu": HOSINBU_ART,            # status: 보류
    "trinket/yodae": YODAE_ART,                # status: 보류
    "pelt/wolf": PELT_WOLF_ART,
    "pelt/fox": PELT_FOX_ART,
    "pelt/tiger": PELT_TIGER_ART,
    "spoil/ungdam": UNGDAM_ART,
    "herb/yakjae": YAKJAE_ART,
}
# 웅담 팔레트 보정 — G를 녹갈로 (약재의 G와 의미가 다르다)
UNGDAM_PALETTE = dict(GOODS_PALETTE, G=GALL)


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
    for series, art in WEAPON_SERIES.items():
        for grade, rings in WEAPON_GRADES:
            write_item_asset(f"weapon/{series}_{grade}", paint_rows(art, weapon_palette(rings)), True)
            made += 1

    # 혈음도(마병) — identification.default가 "감정 전엔 정체 불명"이므로 미감정은 평범한 도.
    # 상태 분기는 custom_model_data.strings (§1.2 보조 채널) — 정수 CMD는 쓰지 않는다.
    write_item_asset("weapon/dao_mabyeong", paint_rows(DAO_ART, weapon_palette(0, mabyeong=True)), True)
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

    for key, art in GOODS_ART.items():
        palette = UNGDAM_PALETTE if key == "spoil/ungdam" else GOODS_PALETTE
        write_item_asset(key, paint_rows(art, palette), False)
        made += 1
    return made


# ═══════════════════════════════════════════════════════════════════════════
# 블록 채널 — 전역 치환 (징발). config block_channels.징발 등록분만.
# 금지 원칙: 자연이 만든 블록은 건드리지 않는다 (stone·dirt·*_log·stone_bricks…) — 팩에 1장도 없다.
# ═══════════════════════════════════════════════════════════════════════════
# 흑와(黑瓦) — 기와골이 보이는 결. deepslate_tiles PNG 1장이 계단·반블록·담장 전체를 덮는다.
ROOF_PALETTE = {
    "E": (26, 26, 30, 255),   # 기와 이음 그늘 — 골(course) 경계
    "R": (78, 78, 86, 255),   # 수키와 마루 — 빛 받는 등
    "r": (56, 56, 63, 255),   # 수키와 옆면
    "c": (43, 43, 49, 255),   # 암키와 골
    "C": (34, 34, 39, 255),   # 골 바닥 그늘
}
ROOF_COURSE = "rRrccCccrRrccCcc"   # 수키와(3px) + 암키와 골(5px) 2주기 — 세로 결
ROOF_ART = ["E" * 16 if y % 8 == 0 else ROOF_COURSE for y in range(16)]
# 깨진 기와(폐사당 잔해) — 금 간 자리만 덮어쓴다 (같은 결 위의 균열이라야 같은 지붕으로 읽힌다)
ROOF_CRACKS = [(3, 2), (4, 3), (4, 4), (5, 5), (5, 6), (6, 7), (9, 2), (10, 3),
               (11, 10), (12, 11), (12, 12), (13, 13), (2, 12), (3, 13), (7, 14)]


def cracked_roof_art():
    grid = [list(row) for row in ROOF_ART]
    for x, y in ROOF_CRACKS:
        grid[y][x] = "K"
    return ["".join(row) for row in grid]


CRACK_PALETTE = dict(ROOF_PALETTE, K=(16, 16, 19, 255))


def plaster_rows(base, light, dark):
    """회벽(灰壁) — 거친 회칠. 무늬·이음선 금지(block_channels 조건):
    배들랜드 지층에 대량 자연 생성되므로 무늬가 있으면 지층이 벽으로 튄다.
    저대비 3값 결정론 노이즈만 — 텍스처 디자인으로 세계 오염을 무력화한다.
    노이즈원은 crc32: 산술식(x*a+y*b)은 규칙적인 사선 격자를 만들고, 사선 격자는 곧 무늬다."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            n = zlib.crc32(bytes((x, y))) % 12
            row.append(light if n < 2 else dark if n < 4 else base)
        rows.append(row)
    return rows


LATTICE_FRAME = (58, 46, 36, 255)    # 창틀 — 어두운 목재
LATTICE_BAR = (92, 74, 54, 255)      # 창살(muntin)


def lattice_window_rows():
    """격자창 — glass.png(면). glass_pane와 유리 블록이 면 텍스처를 공유하므로
    유리도 격자창이 된다: 전근대 강호에 판유리는 없다 — 오염이 아니라 정합."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            if x in (0, 15) or y in (0, 15):
                row.append(LATTICE_FRAME)
            elif x in (5, 10) or y in (5, 10):
                row.append(LATTICE_BAR)
            else:
                row.append(T)          # 창호 — 투명 (팩 없음 폴백 = 유리창)
        rows.append(row)
    return rows


def pane_top_rows():
    """glass_pane_top.png — 창살 마구리. 통짜 목재 색 (모델이 어느 UV를 잡아도 나무로 읽힌다)."""
    return [[LATTICE_FRAME] * 16 for _ in range(16)]


BAMBOO_PALETTE = {
    "l": (204, 190, 140, 255),   # 대나무 겉대 — 빛
    "b": (182, 166, 114, 255),   # 대나무 몸
    "d": (128, 112, 72, 255),    # 쪽 사이 골
    "n": (104, 90, 58, 255),     # 마디(節) — 가로 결
}
BAMBOO_ART = ["n" * 16 if y in (3, 11) else "lbbd" * 4 for y in range(16)]

LANTERN_PALETTE = {   # 등롱 — 종이에 스민 불빛
    "#": (58, 46, 36, 255), "P": (198, 168, 106, 255),
    "p": (236, 214, 152, 255), "w": (252, 240, 200, 255),
}
SOUL_LANTERN_PALETTE = {   # 백등롱 — 폐사당 냉광 (냉색 = 금기의 의미 유지)
    "#": (40, 48, 56, 255), "P": (142, 186, 200, 255),
    "p": (196, 230, 240, 255), "w": (236, 250, 255, 255),
}


def lantern_art():
    """등롱 — 위아래 테 + 세로 살대 + 중심 심지 광.
    lantern.png는 모델이 여러 UV 조각으로 잡으므로 어느 조각을 잡아도 '종이 등'으로 읽히게 균질 구성."""
    rows = []
    for y in range(16):
        if y in (0, 1, 14, 15):
            rows.append("#" * 16)
            continue
        row = []
        for x in range(16):
            if x in (0, 15):
                row.append("#")
            elif x % 5 == 0:
                row.append("P")                     # 살대
            elif 6 <= x <= 9 and 6 <= y <= 9:
                row.append("w")                     # 심지 광
            else:
                row.append("p")
        rows.append("".join(row))
    return rows


SHELF_PALETTE = {
    "d": (58, 46, 36, 255),    # 서랍 테두리·문선
    "w": (128, 102, 72, 255),  # 서랍 앞판
    "W": (150, 122, 88, 255),  # 결 밝은 획
    "k": (22, 20, 18, 255),    # 빈 칸 — 열린 서랍 속 어둠
    "g": BRASS,                # 놋 손잡이
}
SHELF_COLS = [(0, 4), (5, 10), (11, 15)]   # 3열 × 2단 = 6칸 (바닐라 chiseled_bookshelf 슬롯 배치)
SHELF_ROWS = [(0, 7), (8, 15)]


def shelf_face_art(occupied: bool):
    """한약장 서랍 — '꽂힌 책 = 채운 서랍' 재해석.
    occupied = 앞판 + 놋 손잡이 / empty = 열린 칸의 어둠. 6칸을 텍스처 안에서 그대로 배치."""
    grid = [["d"] * 16 for _ in range(16)]
    for x0, x1 in SHELF_COLS:
        for y0, y1 in SHELF_ROWS:
            for y in range(y0, y1 + 1):
                for x in range(x0, x1 + 1):
                    edge = x in (x0, x1) or y in (y0, y1)
                    grid[y][x] = "d" if edge else ("w" if occupied else "k")
            if occupied:
                for x in range(x0 + 1, x1):
                    grid[(y0 + y1) // 2][x] = "g"      # 가로 놋 손잡이
    return ["".join(row) for row in grid]


def shelf_grain_art(vertical: bool):
    """약장 몸통(top·side) — 결만 있는 목재. 무늬 금지 대상이 아니라 자유롭다."""
    rows = []
    for y in range(16):
        row = []
        for x in range(16):
            t = x if vertical else y
            row.append("W" if t % 5 == 0 else "d" if t % 5 == 4 else "w")
        rows.append("".join(row))
    return rows


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
        "deepslate_tiles": paint_rows(ROOF_ART, ROOF_PALETTE),
        "cracked_deepslate_tiles": paint_rows(cracked_roof_art(), CRACK_PALETTE),
        "white_terracotta": plaster_rows((214, 208, 196, 255), (226, 221, 210, 255), (198, 191, 178, 255)),
        "light_gray_terracotta": plaster_rows((168, 164, 156, 255), (180, 176, 168, 255), (152, 148, 141, 255)),
        "glass": lattice_window_rows(),
        "glass_pane_top": pane_top_rows(),
        "bamboo_planks": paint_rows(BAMBOO_ART, BAMBOO_PALETTE),
        "lantern": paint_rows(lantern_art(), LANTERN_PALETTE),
        "soul_lantern": paint_rows(lantern_art(), SOUL_LANTERN_PALETTE),
        "chiseled_bookshelf_top": paint_rows(shelf_grain_art(False), SHELF_PALETTE),
        "chiseled_bookshelf_side": paint_rows(shelf_grain_art(True), SHELF_PALETTE),
        "chiseled_bookshelf_empty": paint_rows(shelf_face_art(False), SHELF_PALETTE),
        "chiseled_bookshelf_occupied": paint_rows(shelf_face_art(True), SHELF_PALETTE),
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
