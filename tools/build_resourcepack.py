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

    (PACK / "pack.mcmeta").write_text(json.dumps({
        "pack": {"pack_format": 46, "description": "혼천 — 글리프 + HUD·인벤토리 수묵 텍스처 (M2)"}
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    total = 1 + 9 + len(REALM_CRESTS) + 1
    vanilla = len(HEART_SPRITES) + 2 + 2   # 하트 6 + 핫바 2 + 컨테이너 2
    print(f"팩 컴파일 완료: {PACK.relative_to(ROOT)} "
          f"(글리프 {total}종 + 음수공백 6폭 + 바닐라 교체 {vanilla}장 + 폰트 주입 + pack.mcmeta)")


if __name__ == "__main__":
    main()
