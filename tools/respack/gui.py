"""HUD·컨테이너·글리프·메뉴 스프라이트 (T3 소유)."""
# 기계 분할 산출 — 원본: tools/build_resourcepack.py (pack_upgrade_v1.md §2 0단계).
# 로직 무수정: 함수 본문·상수 값은 원본 그대로다 (이동만 했다).
import json
import math
import struct
import sys
import zlib
from pathlib import Path
from .core import (
    BLINK_DARK, BLINK_EMPTY, BLINK_FILL, BLINK_LIGHT, BLINK_OUT, BLINK_SOCK_DARK,
    BLINK_SOCK_LIT, GUI_DIR, HOT_DIV, HOT_EDGE_HI, HOT_EDGE_LO, HOT_INK_BOT, HOT_INK_TOP,
    HOT_WEAR, INK_GUIDE, INK_LINE, INK_SOLID, ORB_DARK, ORB_EMPTY, ORB_FILL, ORB_LIGHT,
    ORB_OUT, ORB_SOCK_DARK, ORB_SOCK_LIT, PAPER_BODY, PAPER_FIBER, PAPER_LIGHT, PAPER_SHADOW,
    SEAL, SEL_DARK, SEL_FRAME, SEL_HI, SEL_SEAL, SLOT_BASE, SLOT_DARK, SLOT_LIGHT, T,
    WINDOW_DEEP, WINDOW_HAZE, WINDOW_INK, WINDOW_LIT, art_rows, h32, mix, octave, paint_rows,
    ramp, step, write_png,
)

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

# ═══════════════════════════════════════════════════════════════════════════
# 허기 — 만두(饅頭). 【2026-07】 닭다리를 강호에서 몰아낸다.
#
# 【왜 지우지 않고 바꾸는가 — 전임의 판단을 뒤집는다】
#   전임은 "코드에 setFoodLevel 이 한 줄도 없다 = 아무도 안 쓴다 = 게이지가 거짓말"이라 했다.
#   그건 **덮어쓰기가 없다**는 뜻이지 **기능이 없다**는 뜻이 아니다. 바닐라 허기는 **살아 있다**:
#   줄어들고, 6 이하에서 달리기를 막고, 0 에서 굶겨 죽인다. 아무도 그걸 끄지 않았다.
#   ⇒ 그러므로 아이콘을 **투명하게 지우면** 플레이어는 **보이지 않는 굶주림**으로 죽는다.
#     (그리고 서버 배선은 내 소유가 아니다 — 남의 손을 전제한 팩 변경은 덫이다.)
#   ⇒ 만두는 **거짓말이 아니다.** 허기는 실재하는 규칙이고, 만두는 그 규칙의 강호식 얼굴이다.
#   ★ '허기를 강호의 축으로 삼을 것인가, 아니면 FoodLevelChangeEvent 로 20 에 고정해
#     무의미하게 만들 것인가' 는 **게임 설계 결정**이다 — 청구서로 넘긴다 (사람이 정한다).
#     고정하기로 정하면 이 만두는 '항상 가득'이 되어 조용해진다. 그때도 거짓말은 아니다.
BUN_OUT = (26, 24, 22, 255)        # 만두 먹 테두리
BUN_BODY = (222, 214, 196, 255)    # 반죽 — 화선지 계열 (저채도)
BUN_LIGHT = (246, 240, 226, 255)   # 김 오른 윗면 광
BUN_DARK = (168, 158, 140, 255)    # 아랫면 그늘 · 반 칸 절단면
BUN_PLEAT = (140, 128, 110, 255)   # 주름 꼭지 — 만두를 만두로 만드는 한 점
# 허기(Hunger) 효과 변형 — 쉰 만두. 색이 아니라 **때깔**이 상한다 (저채도 유지)
BUN_H_BODY = (176, 184, 158, 255)
BUN_H_LIGHT = (208, 214, 190, 255)
BUN_H_DARK = (126, 134, 112, 255)
BUN_H_PLEAT = (102, 110, 90, 255)

FOOD_EMPTY_ART = [       # 빈 자리 — 소켓 (하트와 같은 문법: 파인 그늘 + 되비친 립)
    "....#....",
    "...#s#...",
    "..#ss~#..",
    ".#ss~~v#.",
    "#ss~~~vv#",
    "#s~~~vvv#",
    "#~~~vvvv#",
    ".#vvvvv#.",
    "..#####..",
]
FOOD_FULL_ART = [        # 만두 — 꼭지(주름) + 부푼 몸 + 앉은 바닥
    "....#....",
    "...#p#...",
    "..#LBB#..",
    ".#LBBBB#.",
    "#LBBBBBB#",
    "#BBBBBBD#",
    "#BBBBBDD#",
    ".#DDDDD#.",
    "..#####..",
]
FOOD_HALF_ART = [        # 좌반만 — 절단면(D 열)으로 반 칸이 1초에 읽힌다 (빈 자리가 비쳐 나온다)
    "....#....",
    "...#D....",
    "..#LD....",
    ".#LBD....",
    "#LBBD....",
    "#BBBD....",
    "#BBBD....",
    ".#DDD....",
    "..###....",
]
BUN_PALETTE = {"#": BUN_OUT, "B": BUN_BODY, "L": BUN_LIGHT, "D": BUN_DARK, "p": BUN_PLEAT,
               "s": ORB_SOCK_DARK, "~": ORB_EMPTY, "v": ORB_SOCK_LIT}
BUN_H_PALETTE = {"#": BUN_OUT, "B": BUN_H_BODY, "L": BUN_H_LIGHT, "D": BUN_H_DARK,
                 "p": BUN_H_PLEAT, "s": ORB_SOCK_DARK, "~": ORB_EMPTY, "v": ORB_SOCK_LIT}

FOOD_SPRITES = [
    ("food_empty", FOOD_EMPTY_ART, BUN_PALETTE),
    ("food_full", FOOD_FULL_ART, BUN_PALETTE),
    ("food_half", FOOD_HALF_ART, BUN_PALETTE),
    ("food_empty_hunger", FOOD_EMPTY_ART, BUN_H_PALETTE),
    ("food_full_hunger", FOOD_FULL_ART, BUN_H_PALETTE),
    ("food_half_hunger", FOOD_HALF_ART, BUN_H_PALETTE),
]

# ─── 내공(內功) 막대 — 초록 XP 바를 옥(玉)으로 갈아 끼운다 ───
#   알맹이는 이미 내공이다 (SkillHud:197-198 이 setLevel(내공)·setExp(원기/최대) 를 한다).
#   껍데기만 마인크래프트 초록이었다 — **화면이 세계에 대해 거짓말하던 자리**다.
#   182x5 (client-1.21.11.jar 실측 — 이름·치수를 짐작하지 않았다).
XP_RIM = (24, 26, 28, 255)          # 먹 테두리
XP_HOLLOW_DK = (38, 42, 44, 255)    # 빈 홈 윗속 — 가장 깊은 그늘 (파인 것으로 읽히는 근거)
XP_HOLLOW_MID = (52, 57, 58, 255)   # 빈 홈 바닥
XP_HOLLOW_LT = (72, 78, 78, 255)    # 빈 홈 아랫속 — 바닥에서 되비친 빛 (립)
XP_FILL = (122, 176, 168, 255)      # 옥(玉) — 청량 포인트. 명병의 JADE 와 같은 색
XP_FILL_HI = (170, 214, 206, 255)   # 윗줄 광
XP_FILL_DK = (78, 124, 118, 255)    # 아랫줄 그늘


def xp_bar(fill: bool):
    """182x5 — 위아래 먹 테두리 사이에 3px 홈(또는 옥)이 흐른다.
    【2026-07-16 승격】 채움 몸통(가운데 행)에 옥의 결을 깐다 — 균일 채움은 옥이 아니라 플라스틱이다
    (팩 아트 디렉션 §5: "옥 = 반투명감 — 밝은 심 + 어두운 테"). 결은 5px 단위 저주파 결정론
    (h32 — 픽셀마다 튀면 노이즈지 결이 아니다) + 드문 밝은 심 한 점. 홈(빈 바)은 조용히 둔다."""
    if fill:
        rows = [[XP_RIM] * 182, [XP_FILL_HI] * 182]
        body = []
        for x in range(182):
            j = h32(x // 5, 0x33) % 9 - 4                   # 옥의 결 — 5px 단위 농담
            c = tuple(max(0, min(255, XP_FILL[i] + j)) for i in range(3)) + (255,)
            if h32(x, 0x9D) % 23 == 0:
                c = XP_FILL_HI                              # 심(心) — 옥 속에 드문 밝은 맥
            body.append(c)
        rows += [body, [XP_FILL_DK] * 182, [XP_RIM] * 182]
        return rows
    band_rows = [XP_HOLLOW_DK, XP_HOLLOW_MID, XP_HOLLOW_LT]
    return [[XP_RIM] * 182] + [[c] * 182 for c in band_rows] + [[XP_RIM] * 182]


# 글리프 공용 농담 4단 — 백색 계열 (인게임 §색 틴트가 곱해도 계단이 남는다. bimu 와 같은 문법:
# 젖은 획 머리 W → 마른 끝 D. 순백 2값 플레이스홀더의 '스티커 느낌'이 이 계단에서 벗겨진다).
GLYPH_SHADES = {"W": (255, 255, 255, 255), "L": (224, 220, 212, 255),
                "M": (182, 176, 166, 255), "D": (134, 128, 118, 255)}


def gise_icon():
    """8x8 기세 아이콘 — 솟는 기운 (불꽃형) 【2026-07-16 승격 — 계약 불변: E000·8x8·실루엣 언어】.
    아래로 갈수록 넓어지는 화염 실루엣 + 우상단 분리 불티 1px = 상승 운동감 (1초 가독).
    승격: 2값 덩어리 → 농담 4단 — 심(W)이 밝고 가장자리가 마른다(M·D). 혀끝이 왼쪽으로 눕고
    (불은 흔들린다) 바닥이 좁아진다 (땅에서 떠오른 기운 — 붙은 불이 아니다)."""
    return paint_rows([
        "...L..W.",
        "...W....",
        "..LW....",
        "..WWL...",
        ".LWWWL..",
        ".LWWWM..",
        ".MLWLM..",
        "..MDM...",
    ], GLYPH_SHADES)


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


# ─── 화후 게이지 (E010~E018) — 고정 폭 붓홈 + 좌→우 먹 채움 【2026-07-14 RP-2 재도트】 ───
# RP_REVIEW_BOARD §합의 2: 시안 A(docs/collaboration/rp_review/A_gauge_ink_proposal.png)는
# **언어로만** 채택 — 단계마다 외형 길이가 자라는 자유 붓획을 그대로 자산화하면
# 20x6 고정 폭 · 동일 advance · 틴트 재사용(SkillHud 부상 사다리 백/황/적/암적 + 내력 옥청)
# 계약을 잃는다. 그래서 붓홈(홈틀)은 아홉 단이 **한 획으로 같고**, 차오르는 것은 내부 먹뿐이다:
#   ① 외곽 = 가는 붓홈 테. 결정론 지터(h32)로 붓이 지나간 홈이 되, filled 와 무관 —
#      아홉 단에서 **바이트까지 동일**하다 (texture_audit 축 ㉒가 대조한다: advance·실루엣 불변의 증거)
#   ② 빈 홈 바닥 = 옅은 먹 (α 120 < 128 — '불투명 단조' 계수 밖). 이것이 없으면 암적 틴트가
#      먹 배경에서, 황·옥청 틴트가 화선지에서 **채움과 빈 홈을 못 가른다** (합성 실측 ΔY 11)
#   ③ 채움 = 젖은 먹, 좌→우. 위가 밝고 아래가 가라앉는 농담 4단 — 이미 찬 몸에는 구멍을 내지 않는다
#   ④ 마른 붓 = 채움 **선두 2열만** 갈라진다. 열마다 갈라짐 최대 2점 (h32 결정론) —
#      그래서 9단 불투명 픽셀의 엄격 단조 증가가 산수로 보장된다 (한 단의 몸 증가 ≥8 > 갈라짐 ≤4).
#      만충(8)은 갈라짐 없이 오른쪽 붓홈에 밀착 — 완충은 젖은 먹이다.
GAUGE_W, GAUGE_H = 20, 6
GAUGE_RIM_LIT, GAUGE_RIM_DIM = 188, 150      # 붓홈 테 — 위·왼쪽 빛, 아래·오른쪽 그늘
GAUGE_RIM_A = 235
GAUGE_FLOOR = (40, 40, 40, 120)              # 빈 홈 바닥 — 옅은 먹 (α<128: 불투명 계수 밖)
GAUGE_BODY_V = (255, 246, 238, 224)          # 채움 농담 — y=1(빛) … y=4(그늘)
GAUGE_DRY_V, GAUGE_DRY_A = 232, 208          # 마른 붓 선두 열 — 몸보다 옅다


def _gauge_rim(x, y):
    """붓홈 테 한 점 — filled 와 무관 (아홉 단이 같은 홈이다). 지터는 결정론 해시뿐."""
    v = GAUGE_RIM_LIT if (y == 0 or x == 0) else GAUGE_RIM_DIM
    v += h32(x, y, 0xA7) % 17 - 8            # 붓의 홈 — 자 대고 그은 금이 아니다
    return (v, v, v, GAUGE_RIM_A)


def gauge(filled: int):
    """20x6 화후 게이지 (filled/8) — 위 머리말의 네 계약을 그린다."""
    inner = GAUGE_W - 2                          # 18
    fill_px = (inner * filled + 4) // 8          # 반올림(half-up) — 0·2·5·7·9·11·14·16·18
    dry = () if filled in (0, 8) else tuple(x for x in (fill_px - 1, fill_px) if x >= 1)
    rows = []
    for y in range(GAUGE_H):
        row = []
        for x in range(GAUGE_W):
            if y in (0, GAUGE_H - 1) or x in (0, GAUGE_W - 1):
                corner = x in (0, GAUGE_W - 1) and y in (0, GAUGE_H - 1)
                row.append(T if corner else _gauge_rim(x, y))   # 네 귀는 붓이 돌며 뜬 자리
            elif 1 <= x <= fill_px and x not in dry:
                v = GAUGE_BODY_V[y - 1]                          # 젖은 먹 몸 — 농담 4단
                row.append((v, v, v, 255))
            elif x in dry:
                cracks = {h32(x, 0x11) % 4, h32(x, 0x37) % 4}    # 열마다 최대 2점
                if (y - 1) in cracks:
                    row.append(GAUGE_FLOOR)                      # 갈라진 자리로 홈 바닥이 비친다
                else:
                    row.append((GAUGE_DRY_V, GAUGE_DRY_V, GAUGE_DRY_V, GAUGE_DRY_A))
            else:
                row.append(GAUGE_FLOOR)
        rows.append(row)
    return rows


# ─── 경지 문장 8단 (E020~E027) — 8x8 농담 4단 【2026-07-16 승격 — 실루엣 불변】 ───
# 디자인 언어 (1초 상호 구별 — 불변):
#   하위 3단(삼류~일류) = 획 수 一二三 (윗획 짧게·아랫획 길게 — 서예 리듬)
#   중위 3단: 절정=봉우리(산), 초절정=검(劍), 화경=검+칼끝 검기 광점
#   상위 2단: 현경=8행 원환(空), 생사경=원환+만월 중심핵(滿)
# 승격의 규율: **α≥128 마스크(실루엣)는 2값 판과 픽셀까지 동일** — 축 ㉑(인접 XOR)의 실측
# 합격값(삼류→생사경 인접쌍 7)이 그대로 산다. 바뀐 것은 획 안의 농담뿐이다 (GLYPH_SHADES):
#   가로획 = 起筆 젖고(W) 收筆 마른다(D) — 서예의 호흡 · 봉우리 = 능선이 밝고 기슭이 가라앉는다
#   검 = 날이 밝고(W) 자루가 어둡다(D) · 원환 = 좌상이 젖고 우하로 마른다 (일필의 농담)
REALM_CRESTS = {
    "삼류": [
        "........",
        "........",
        "........",
        ".WWWLMD.",
        "........",
        "........",
        "........",
        "........",
    ],
    "이류": [
        "........",
        "........",
        "..WWLD..",
        "........",
        ".WWWLMD.",
        "........",
        "........",
        "........",
    ],
    "일류": [
        "........",
        "..WWLD..",
        "........",
        "..WWLD..",
        "........",
        ".WWWLMD.",
        "........",
        "........",
    ],
    "절정": [
        "........",
        "...W....",
        "..LWL...",
        "..LWM...",
        ".LLWMM..",
        "DLLWMMD.",
        "........",
        "........",
    ],
    "초절정": [
        "...W....",
        "..LWL...",
        "..LWM...",
        "..LWM...",
        ".MLDLM..",
        "...D....",
        "...D....",
        "..MDM...",
    ],
    # 화경 【2026-07-14 RP-1 재도트】 — 옛 판은 초절정과 윗줄 광점 2px 만 달랐다 (XOR 2:
    # 8x8 실크기에서 안 갈린다 — RP_REVIEW_BOARD §합의 1). 검 계열은 지키고 **외곽 실루엣**으로
    # 벌린다: 검기가 칼끝 밖으로 비산(윗줄 세 점이 몸에서 떨어져 뜬다) + 코등이가 활짝 벌고(6행
    # 전폭) + 자루받침이 넓어진다. 초절정↔화경 XOR 10 · 화경↔현경 XOR 32 (축 ㉑ 하한 8).
    "화경": [
        "L..W..L.",
        ".M.W.M..",
        "..LWL...",
        "..LWM...",
        "MLLDLLM.",
        "...D....",
        "...D....",
        ".MMDMM..",
    ],
    "현경": [
        "..LWWL..",
        ".L....M.",
        "L......M",
        "L......M",
        "M......D",
        "M......D",
        ".M....D.",
        "..MDDD..",
    ],
    "생사경": [
        "..LWWL..",
        ".L....M.",
        "L..WW..M",
        "L.WWWW.M",
        "M.LWWL.D",
        "M..LL..D",
        ".M....D.",
        "..MDDD..",
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


# ═══════════════════════════════════════════════════════════════════════════
# B-036 — HUD 잔여 신작: crosshair · air(숨) · boss_bar (2026-07-16)
# 치수·경로는 client-1.21.11.jar 실측이다 (짐작 금지):
#   gui/sprites/hud/crosshair.png 15x15 · hud/air{,_bursting,_empty}.png 9x9
#   gui/sprites/boss_bar/<색>_{background,progress}.png 182x5
# 등록: resourcepack_design.yml vanilla_texture_channels (hud_조준·hud_숨·boss_bar_내공내력)
# ═══════════════════════════════════════════════════════════════════════════
HUD_SPRITE_DIR = GUI_DIR / "sprites" / "hud"
BOSS_DIR = GUI_DIR / "sprites" / "boss_bar"

# 십자선 — ★ 바닐라는 크로스헤어를 **반전 블렌드**로 그린다 (배경색을 뒤집는 특수 합성).
#   그 합성에서 검은 픽셀은 무연산(안 보인다)이고 밝은 픽셀이 배경을 뒤집는다. 그래서
#   몸은 화선지 밝기로 (어디서나 보인다 — 바닐라 흰 십자와 같은 물리), 먹 외곽은
#   인게임에선 조용하되 시트·검수(축 8 양배경)에서 획을 세운다. 손해 보는 자리가 없다.
CROSS_PALETTE = {"#": INK_SOLID, "W": (238, 231, 214, 255),
                 "L": (216, 208, 190, 255), "T": (178, 170, 152, 255)}
CROSSHAIR_ART = [       # 붓 네 획이 안쪽을 겨눈다 — 가운데 3x3 은 여백 (조준의 눈은 비워 둔다)
    "......###......",
    "......#T#......",
    "......#L#......",
    "......#W#......",
    "......#W#......",
    "......###......",
    "######...######",
    "#TLWW#...#WWLT#",
    "######...######",
    "......###......",
    "......#W#......",
    "......#W#......",
    "......#L#......",
    "......#T#......",
    "......###......",
]

# 숨(息) 구슬 — 물속 거품. 몸은 화선지 계열에 물의 서늘함 한 김 (저채도 — 청량 면적 규율 준수),
# 빛은 좌상단 (바닐라 관례). empty 는 기혈 구슬 소켓과 같은 문법 (파인 그늘 s + 바닥 ~ + 되비침 립 v).
AIR_PALETTE = {"#": INK_SOLID, "L": (240, 246, 242, 255), "b": (198, 210, 206, 235),
               "d": (140, 152, 148, 235),
               "s": ORB_SOCK_DARK, "~": ORB_EMPTY, "v": ORB_SOCK_LIT}
AIR_ART = [             # 숨이 차 있는 구슬 — 좌상 광, 우하 그늘
    "..#####..",
    ".#LLbbb#.",
    "#LLbbbbd#",
    "#Lbbbbbd#",
    "#Lbbbbdd#",
    "#bbbbddd#",
    "#bbbdddd#",
    ".#bbddd#.",
    "..#####..",
]
AIR_BURSTING_ART = [    # 터지는 순간 — 위가 열리고 물방울이 튄다 (형태로 갈린다: 색맹 규약)
    "L...#...L",
    "...L.L...",
    ".#.....#.",
    "#L.....d#",
    "#Lb...bd#",
    "#bb...dd#",
    ".#bb.dd#.",
    "..#ddd#..",
    "..#####..",
]
AIR_EMPTY_ART = [       # 숨이 빠진 자리 — 소켓 (구슬이 아니라 홈: 상·좌 그늘, 하·우 립)
    "..#####..",
    ".#sss~~#.",
    "#ss~~~~v#",
    "#s~~~~vv#",
    "#s~~~vvv#",
    "#~~~vvvv#",
    "#~~vvvvv#",
    ".#~vvvv#.",
    "..#####..",
]

# 보스바 — 내공/내력 채널의 실사용 색만 굽는다 (skill_motion.yml hud.energy_bossbar:
# 평시 BLUE · 고갈 RED · overlay PROGRESS — ★사용자 확정 2026-07-15). 그 밖의 색과
# notched_* 는 미등록 = 바닐라 유지 (쓰지 않는 채널을 미리 칠하지 않는다).
#   파랑 = 쪽빛(藍) — 옥(XP 내공바)과 갈리는 남기. 빨강 = 주사 계열 (기혈 구슬과 같은 핏줄).
#   문법은 xp_bar 와 한 몸: 먹 테 + 3행 몸(위 광 / 몸 / 아래 그늘) + 몸통 저주파 결.
BOSS_RIM = (24, 26, 28, 255)
BOSS_BARS = {
    "blue": {"hi": (150, 178, 205, 255), "body": (96, 130, 164, 255), "dk": (58, 86, 116, 255),
             "bg": ((34, 40, 48, 255), (44, 54, 66, 255), (60, 72, 86, 255))},
    "red": {"hi": (208, 112, 88, 255), "body": (150, 56, 44, 255), "dk": (96, 34, 26, 255),
            "bg": ((44, 30, 27, 255), (56, 36, 32, 255), (76, 50, 44, 255))},
}


def boss_bar(kind: str, fill: bool):
    """182x5 보스바 — background 는 파인 홈(어두운 같은 색 계열 — 빈 바에서도 채널色이 산다),
    progress 는 차오르는 몸. 몸통 가운데 행에만 5px 결 (xp_bar 와 같은 규율: 균일 채움 금지)."""
    pal = BOSS_BARS[kind]
    bands = [pal["hi"], pal["body"], pal["dk"]] if fill else list(pal["bg"])
    rows = [[BOSS_RIM] * 182]
    for i, c in enumerate(bands):
        row = [BOSS_RIM]
        for x in range(1, 181):
            v = c
            if fill and i == 1:
                j = h32(x // 5, 0x5A) % 9 - 4
                v = tuple(max(0, min(255, c[k] + j)) for k in range(3)) + (255,)
            row.append(v)
        row.append(BOSS_RIM)
        rows.append(row)
    rows.append([BOSS_RIM] * 182)
    return rows


def write_hud_sprites() -> int:
    """B-036 신작 8장 — 십자선 1 + 숨 3 + 보스바 4 (경로·치수 = 바닐라 계약)."""
    write_png(HUD_SPRITE_DIR / "crosshair.png", paint_rows(CROSSHAIR_ART, CROSS_PALETTE))
    write_png(HUD_SPRITE_DIR / "air.png", paint_rows(AIR_ART, AIR_PALETTE))
    write_png(HUD_SPRITE_DIR / "air_bursting.png", paint_rows(AIR_BURSTING_ART, AIR_PALETTE))
    write_png(HUD_SPRITE_DIR / "air_empty.png", paint_rows(AIR_EMPTY_ART, AIR_PALETTE))
    n = 4
    for kind in BOSS_BARS:
        write_png(BOSS_DIR / f"{kind}_background.png", boss_bar(kind, False))
        write_png(BOSS_DIR / f"{kind}_progress.png", boss_bar(kind, True))
        n += 2
    return n


# ═══════════════════════════════════════════════════════════════════════════
# 서장 기억첩 (E0B0~E0B3) — seojang_presentation.md §3.2·§4.1 이 이름 댄 4종**만** (2026-07-16)
#   "리소스팩 1차 범위는 장 구분선, 붓점, 인장, 선택 표식 같은 소형 비트맵 글리프다" (§3.2)
#   선택 표식은 "빈 인장 → 클릭 접수 시 찍힌 인장 — 색 없이도 형태가 달라야" (§4.1):
#   빈 틀(먹 테)과 찍힌 인장(주사 몸+파인 각)은 채움 자체가 다르다. 주사는 §4.1 규율대로
#   확정·출도 인장에만 (semantic_red_registry 등재). 그 밖의 장식은 발명하지 않는다.
#   Java 배선(SeojangBook 조판)은 다음 회차 — SJ-002 의 "등록부·빌드 대조 후 코드" 순서.
# ═══════════════════════════════════════════════════════════════════════════
SEAL_HI = (188, 82, 64, 255)     # 인주가 두껍게 앉은 마루 (LEDGER_SEAL_HI 동일 계열)
SEAL_DK = (96, 34, 26, 255)      # 인주 그늘 — 도장은 파인 것이라 두께에 명암이 있다

SEOJANG_DOT_ART = [     # 붓점 — 머리가 눌리고 꼬리가 뺀다 (낙관 앞의 숨)
    "........",
    "...WW...",
    "..WWWW..",
    "..WWWL..",
    "...WLM..",
    "....M...",
    "........",
    "........",
]
SEOJANG_SEAL_ART = [    # 찍힌 인장 — 주사 몸에 각(刻)이 파여 종이가 비친다 (투명 = 화선지)
    "HHHHHHD.",
    "H.RR.RD.",
    "HRR.RRD.",
    "HR.RR.D.",
    "HRRR.RD.",
    "H.RR.RD.",
    "DDDDDDD.",
    "........",
]
SEOJANG_SEAL_EMPTY_ART = [   # 빈 인장 — 아직 안 찍힌 먹 틀 (열린 속 = 기다림)
    "KKKKKKK.",
    "K.....K.",
    "K.....K.",
    "K.....K.",
    "K.....K.",
    "K.....K.",
    "KKKKKKK.",
    "........",
]
SEOJANG_SEAL_PALETTE = {"H": SEAL_HI, "R": SEAL, "D": SEAL_DK, "K": (26, 24, 22, 225)}


def seojang_divider():
    """44x5 장 구분선 — 한 획. 起筆(머리 눌림) → 몸 → 마른 收筆 (갈라짐은 h32 결정론).
    백색+알파 — 먹빛은 본문 글자색(책 기본 검정)이 입힌다 (틴트 문법: 글리프는 색을 안 고른다)."""
    rows = [[T] * 44 for _ in range(5)]
    for x in range(1, 43):
        t = x / 42.0
        a = 255
        if t > 0.72:                          # 收筆 — 마른 붓이 갈라진다
            if h32(x, 0x53) % 3 == 0:
                continue
            a = max(150, 235 - int((t - 0.72) * 300))
        rows[2][x] = (255, 255, 255, a)
    for x in range(1, 4):                     # 起筆 — 머리가 눌려 두꺼워진다
        rows[1][x] = (255, 255, 255, 220)
        rows[3][x] = (255, 255, 255, 200)
    rows[1][4] = (255, 255, 255, 150)
    return rows


def seojang_glyphs():
    """(codepoint, 파일명, 픽셀, height, ascent) — 프로바이더 등재는 __init__ 이 한다 (한 몸 규율).
    ascent 는 잠정 (E080 과 같은 인게임 튜닝 대상): 8x8 은 글줄 관례(7), 구분선은 줄 가운데(3)."""
    return [
        (0xE0B0, "seojang_divider", seojang_divider(), 5, 3),
        (0xE0B1, "seojang_dot", paint_rows(SEOJANG_DOT_ART, GLYPH_SHADES), 8, 7),
        (0xE0B2, "seojang_seal", paint_rows(SEOJANG_SEAL_ART, SEOJANG_SEAL_PALETTE), 8, 7),
        (0xE0B3, "seojang_seal_empty", paint_rows(SEOJANG_SEAL_EMPTY_ART, SEOJANG_SEAL_PALETTE), 8, 7),
    ]


