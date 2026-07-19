"""지물·기물·재료 — 가죽·구슬·재화 (T2 소유)."""
# 기계 분할 산출 — 원본: tools/build_resourcepack.py (pack_upgrade_v1.md §2 0단계).
# 로직 무수정: 함수 본문·상수 값은 원본 그대로다 (이동만 했다).
import json
import math
import struct
import sys
import zlib
from pathlib import Path
from .core import (
    ITEM_DEF_DIR, ITEM_MODEL_DIR, ITEM_TEX_DIR, SEAL, octave, paint_rows, ramp, smooth_octave,
    step, write_json, write_png,
)
from .weapons import (WEAPON_SERIES, _GRADE_FORM, _shift, blank16, outline, write_weapon_asset)

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


# ═══ 마감(老) — 플레이스홀더 → 본 아트 승격 (T2 · pack_upgrade_v1.md 1단계) ═══
# 평면 채우기는 재질이 아니다: 종이에는 결이, 궤 널빤지에는 나뭇결이, 마른 약재에는 잔결이 있다.
# 문법은 pack_art_direction §6-④ 「종이의 결」 — **인지 문턱 언저리**(±1.5~2루마)의 잔결을 깐다.
# 먹(#·i)·주사(r·R)·놋(g·G·f)·끈(h·H·k)은 안 만진다: 획·도장·묶음은 정보이고, 마감은 재질이다.
# 난수 없음 — octave/smooth_octave(결정론 해시)뿐. 구슬 3종·가죽 3종은 이미 절차(램버트·털결)다.
_PAPER_CH = frozenset("qpPd")     # 화선지 — 섬유의 잔결 + 위가 밝다 (위에서 든 빛)
_CRATE_CH = frozenset("wWx")      # 궤 널빤지 — 결은 가로로 긴 획 (행 단위 농담)
_HERB_CH = frozenset("eEc")       # 마른 약초 — 잎맥의 잔결
_GALL_CH = frozenset("aAb")       # 웅담 — 물컹한 것의 옅은 얼룩
_HIDE_CH = frozenset("Lln")       # 가죽 주머니(청낭) — 무두질 잔결


def age_rows(grid, rows):
    """지물 격자 위 마감 — 순수 f(x, y, 문자). 같은 소스는 언제나 같은 바이트 (결정론)."""
    for y in range(16):
        for x in range(16):
            ch = grid[y][x]
            if ch in _PAPER_CH:
                dv = octave(x, y, 1, 0x3A, 1.6) + (6.0 - y) * 0.30
            elif ch in _CRATE_CH:
                dv = smooth_octave(x, y, 4, 0x77, 2.4) + octave(0, y, 1, 0x5B, 1.4)
            elif ch in _HERB_CH:
                dv = octave(x, y, 1, 0x44, 2.2)
            elif ch in _GALL_CH:
                dv = smooth_octave(x, y, 4, 0x6D, 1.8)
            elif ch in _HIDE_CH:
                dv = octave(x, y, 1, 0x58, 1.8)
            else:
                continue
            rows[y][x] = _shift(rows[y][x], dv)
    return rows


def goods_rows(key):
    """지물 1종 → RGBA 행. 구슬 3종만 절차적, 나머지는 아트 + 자동 외곽선 + 마감."""
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
    g = outline([list(r) for r in GOODS_ART[key]], "#")
    return age_rows(g, paint_rows(g, GOODS_PALETTE))


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
