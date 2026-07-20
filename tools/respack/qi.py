"""무공의 획 — 참격선·오의 판 (T3)."""
# 기계 분할 산출 — 원본: tools/build_resourcepack.py (pack_upgrade_v1.md §2 0단계).
# 로직 무수정: 함수 본문·상수 값은 원본 그대로다 (이동만 했다).
import json
import math
import struct
import sys
import zlib
from pathlib import Path
from .core import (ITEM_DEF_DIR, MODEL_DIR, PACK, T, write_json, write_png)

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

# ═══ 오의의 색 【2026-07 — 사용자 지시】 ══════════════════════════════════════
# 사용자: *"최상위(어검/심검)는 가장 화려하게. 단, 형광 네온·보라/핑크 금지 —
#          반드시 먹선 + 청록 + 옥색 + 백색 범위 안에서만."*
#
# 【왜 오의의 판만 물들이는가】 참격선 판(qi/*)은 **여섯 격이 한 장을 나눠 쓴다** —
#   거기 색을 넣으면 외공기의 주먹이 심검처럼 빛난다 (사다리가 무너진다).
#   ult/* 8종은 **오의 전용**이므로 판이 색을 가져도 사다리가 안 흔들린다.
#   ⇒ "기본은 수묵, 최상위만 화려" 라는 1차 MVP 의 못이 판에서도 그대로 선다.
#
# 【색의 출처】 아래 두 값은 **config/skill_motion.yml 의 inks 와 같은 수**다 (옥 · 청백).
#   빌더가 색을 지어낸 것이 아니라 등록부의 색을 굽는 것이다 —
#   그리고 **texture_audit 축 ⑰ 이 등록부와 구워진 PNG 를 대조한다** (어긋나면 위반).
ULT_JADE = (166, 214, 199)       # 옥 — 획의 몸 (skill_motion.yml inks.옥)
ULT_PALE = (226, 240, 238)       # 청백 — 획의 속 (skill_motion.yml inks.청백)


def _tint(px, dim, core):
    """먹의 농담(濃淡)은 **그대로 두고** 색만 입힌다.

    획의 4단(속이 밝고 끝이 스민다)이 이 팩의 골격이다. 색을 칠하며 그 골격을 뭉개면
    그것은 수묵이 아니라 그냥 색칠이다.

    【첫 판이 틀렸고, 눈이 그것을 잡았다】 처음엔 밝기를 **색상 보간의 축**으로만 썼다
    (어두운 끝 → 옥 · 밝은 속 → 청백). 그랬더니 획의 명암이 옥(밝기 200)~청백(236) 사이로
    **압착됐다** — 명암차 158 → 22. 축 ⑬(명암차 ≥ 24)이 여섯 장 전부를 잡았다.
    "농담을 그대로 둔다"고 주석에 적어 놓고 정확히 그 반대를 한 것이다.

    ⇒ 그래서 **곱한다**: 원래 회색이 밝기를 쥐고, 틴트는 **색상만** 얹는다.
        새 값 = 원래회색 × (틴트 ÷ 255)
      스미는 끝(92)은 어두운 옥이 되고, 획의 속(250)은 밝은 청백이 된다 — 명암차가 그대로 산다.
    알파는 손대지 않는다 (사라지는 인상의 절반이 알파다).
    """
    r, g, b, a = px
    if a == 0:
        return px
    v = max(r, g, b)
    t = v / 255.0                                  # 0(스미는 끝) … 1(획의 속)
    tint = [dim[i] + (core[i] - dim[i]) * t for i in range(3)]
    return (min(255, int(v * tint[0] / 255)),
            min(255, int(v * tint[1] / 255)),
            min(255, int(v * tint[2] / 255)),
            a)


def tint_rows(rows, dim, core):
    return [[_tint(p, dim, core) for p in row] for row in rows]

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


# ─── 참격선(斬擊線) — 검을 복제하지 않는다. **지나간 자리**를 그린다 (만화의 검격) ──────
#
# 【두 갈래의 scale 규약 — 이것을 틀리면 획의 길이가 절반이 된다】
#   · 투사·지속·고리 (SkillDisplay.sizeUnit)  → scale = size          ⇒ 모델은 **단위 상자**를 채운다
#   · 참격선          (SkillDisplay.slash)    → scale = {length/size[0], thick, thick}
#     ← **size 를 곱하지 않는다.** 그러므로 참격선 모델은 **제 실제 미터 치수**로 구워야 하고,
#       그 치수가 곧 size 여야 한다 (최종 길이 = 모델X × length/size[0] = length ⟺ 모델X == size[0]).
#
# 【원점 = 머리(칼끝)】 획은 거기서 **−X 로 늘어진다**. Transformation.scale 은 모델 (8,8,8) 을
#   중심으로 먹으므로, 머리를 그 점에 두어야 scale.x 를 0→1 로 키울 때 **칼끝 뒤로 꼬리가 자라고**,
#   1→0 으로 줄일 때 **꼬리부터 지워진다.** 머리가 먼저 지워지면 거꾸로 보인다 — 그것이 사용자 지적이었다.
#
# 【그래서 1.5m 다 — 3.0m 는 구울 수 없다】 머리가 x=8 이고 몸이 −X 로 3.0m(48px) 뻗으면 꼬리는 x=−40.
#   MC 모델 원소 좌표의 한계는 **[-16, 32]** 다 (원점에서 −X 로 24px = **1.5m** 가 최대).
#   size[0] 은 **상쇄되는 정규화 상수**이므로(모델X == size[0] 이기만 하면 결과가 같다) 1.5 로 굽고
#   등록부에 1.5 를 청구한다 — 인게임 길이는 한 치도 안 변한다 (길이는 판정의 range 가 정한다).
#
# 【무색·반투명】 색을 넣지 않는다. 격의 사다리는 **코드가 밝기(setBrightness)·굵기(thick)로** 올린다
#   (외공기 0.55 → 심검 2.10). 텍스처가 하는 일은 **형태와 알파**뿐이다 — 꼬리로 갈수록 옅어져
#   "굽어 사라지는" 인상을 만든다. 사라지는 인상의 절반은 그 그라데이션이 만든다.
SLASH_W, SLASH_H = 64, 16          # 가로로 긴 획 — 꼬리의 테이퍼에 해상도를 준다 (좌=꼬리 · 우=머리)

# ═══ **가로로 긴 텍스처는 구울 수 없다** (2026-07 — 보라 큐브의 두 번째 원인) ═══
# 마인크래프트의 스프라이트 계약은 **정사각 또는 세로 스트립(h = n·w, .mcmeta 동반)** 이다.
# 64x16 은 둘 다 아니다 — 아틀라스에 넣어도 그림이 서지 않는다 (프레임 계산이 64x16 을
# 16x16 짜리 네 프레임으로 읽어 **꼬리 16px 만** 남긴다 · 최악의 경우 스프라이트가 통째로 버려진다).
#
# 【고치는 법 — 그림은 한 픽셀도 안 바뀐다】 캔버스를 64x64 로 키우고 그림을 **맨 위 16행**에 둔다.
#   모델 UV 는 언제나 0~16 정규화이므로, 캔버스가 4배 높아졌으면 v 를 1/4 로 줄여야 같은 그림이다:
#       v_max = 16 × (SLASH_H / SLASH_CANVAS) = 16 × 16/64 = 4
#   ⇒ uv [0, 0, 16, 4] 가 64x64 의 위쪽 64x16 영역, 곧 **예전 그림 그대로**를 가리킨다.
#   이것은 버그 수정이지 디자인 변경이 아니다 — 획의 모양·굵기·알파가 전부 보존된다.
SLASH_CANVAS = 64                       # 정사각 캔버스 (아래 48행은 투명 여백)
SLASH_UV_V = 16.0 * SLASH_H / SLASH_CANVAS      # = 4.0 — 캔버스를 키운 만큼 UV 를 줄인다


def _square_pad(g, canvas=SLASH_CANVAS):
    """가로로 긴 그림을 **정사각 캔버스의 맨 위**에 얹는다 (아래는 투명). 그림은 그대로다."""
    w = len(g[0])
    return [row[:] for row in g] + [[T] * w for _ in range(canvas - len(g))]


def _slash_blank(w, h):
    return [[T] * w for _ in range(h)]


def _qi_px(v, a):
    """무색(無色) 4단 + 알파 — 채도 0. 격은 코드가 올린다 (수묵 규약)."""
    s = (170, 206, 232, 255)
    c = s[max(0, min(3, int(v * 4)))]
    return (c, c, c, max(0, min(255, int(a))))


# ─── 참격 3종의 먹 — 어두운 번짐 테 【2026-07-14 RP-3 보강】 ────────────────────
# 옛 4단(170·206·232·255)은 화선지(#EEE7D6 · Y≈231) 위에서 **죽는 값**이었다: 232 는 배경과
# ΔY 1 이라 알파가 255 여도 안 보이고, 실측 가시율이 참격 3종에서 15~31% 였다 (RP_REVIEW_CODEX §RP-3).
# 새 4단은 축 ⑲(양배경 합성 대비: 화선지·먹 p25≥16 · 가시≥70%)의 산수에서 역산했다:
#   합성 ΔY = (α/255)·|V − Y_bg| 이므로, 표본 하한 α=64 인 픽셀이 **양쪽** 배경에서 보이려면
#   V ∈ [92, 167] (중회색 — 화선지보다 어둡고 먹보다 밝다) 이어야 하고,
#   백(255)은 α ≥ 176 에서만 화선지 ΔY ≥ 16 이 선다 (255−231.3 = 23.7 · 23.7×176/255 = 16.4).
# ⇒ 가장자리 0단이 곧 **어두운 번짐 테**(92)가 되고, 획의 속(255)은 알파가 진한 머리쪽에만 선다.
#   무색 계약(resourcepack_design.yml qi_무색 — 채도 0)은 그대로다: 만진 것은 **명도**뿐이다.
SLASH_STEPS = (92, 124, 160, 255)   # 번짐 테(어두운 먹) … 획의 속 (흰빛)
SLASH_CORE_MIN_A = 176              # 속의 백(255)을 허락하는 알파 하한 — 위 산수의 못


def _slash_px(v, a):
    """참격선의 먹 한 점 — 무색 4단 + 알파. α<176 인 자리의 속은 한 단 가라앉는다(160)."""
    a = max(0, min(255, int(a)))
    i = max(0, min(3, int(v * 4)))
    if i == 3 and a < SLASH_CORE_MIN_A:
        i = 2                                    # 옅은 백은 화선지에서 죽는다 — 중회색으로
    c = SLASH_STEPS[i]
    return (c, c, c, a)


# ═══ 반월(弧) 리디자인 + 애니 【2026-07-17 — 사용자 확정: 반월 실루엣·날카로운 앞날·핫코어·애니】 ═══
# 옛 획은 **쉼표/올챙이 얼룩**이었다 (한쪽 뭉툭 + 가는 꼬리 → 붓 얼룩). 새 획은 셋을 얹는다:
#   ① 반월(弧) 실루엣 — 가운데가 굵고 **양 끝이 날카롭게 0 으로 수렴**(cusp). 초승달의 배.
#      band 반두께 Wc(t) = wmax · sin(πt)^taper — sin 이 양 끝을 물고, taper<1 이 끝을 더 뾰족하게.
#   ② 날카로운 앞날 + 부드러운 꼬리 — 바깥(볼록) 가장자리 = **인선(刃線)**: 흰-핫 1.6px.
#      거기서 안쪽(오목)으로 갈수록 **스미며 페이드**(잔상) = "빠르게 베고 지나간" 방향성.
#   ③ 핫코어 발광 — 인선의 심이 흰빛(255)이고 falloff 로 옅어진다 (블룸). ★**무색**이다 (축 ⑱):
#      여섯 격이 이 판 한 장을 나눠 쓰므로 색을 넣으면 외공기가 심검처럼 빛난다. 발광은 **명도**로만.
#      계열/등급 색은 코드가(밝기·굵기) · 파티클(inks)이 올린다 — 판은 형태·알파·명도뿐.
#   ④ 애니 — 밝은 기운 한 줄기가 길이를 따라 흐른다 (phase). 세로 스트립 + .mcmeta 로 굽는다 (축 ⑯).
# 모든 점은 여전히 **_slash_px 를 지난다** → 축 ⑲(양배경 가독)의 중회색/흰-핫 규약이 그대로 산다.
SLASH_FRAMES = 8                # 애니 프레임 수 ("번쩍" — 기운이 스치는 속도)
SLASH_FRAMETIME = 1             # 프레임당 틱 (interpolate 로 부드럽게 흐른다)
SLASH_LEAD_PX = 1.6             # 인선(刃線)의 흰-핫 두께 (px)


def _anim_strip(frame_fn, n, canvas):
    """n 프레임(phase 0…1)을 **세로로 쌓는다** (h = n·canvas · .mcmeta 동반 = 축 ⑯ 합법).
    각 프레임의 그림은 **맨 위**에 얹히고 아래는 투명 (모델 uv v 가 프레임의 위쪽을 문다 — SLASH_UV_V)."""
    strip = []
    for i in range(n):
        g = frame_fn(i / n)                       # 위상 — 감김(순환)이라 이음매가 없다
        w = len(g[0])
        strip += [row[:] for row in g] + [[T] * w for _ in range(canvas - len(g))]
    return strip


def slash_rows(w, h, wmax, bow, taper=0.62, centered=False, head_bias=0.0, phase=None):
    """참격선 한 프레임 — **반월(弧)** 또는 **찌르기 광선(centered)**.
    wmax: band 최대 반두께 · bow: 중심선이 위로 휘는 깊이 (호는 굽고 선은 곧다) ·
    taper: 끝의 뾰족함(작을수록 날카롭다) · centered: True 면 심이 **가운데**(찌르기 광선),
    False 면 심이 **바깥 인선**(반월) · head_bias: 머리(+X, 오른쪽)로 갈수록 알파를 살짝 더 준다
    ('베고 지나간' 방향) · phase: 애니 위상 — 밝은 기운이 길이를 따라 흐른다 (None=정지)."""
    g = _slash_blank(w, h)
    ybase = h * 0.62
    for x in range(w):
        t = x / (w - 1)
        env = math.sin(math.pi * t)                # 양 끝 0 · 가운데 1 — 초승달의 배
        if env <= 0.03:
            continue
        Wc = wmax * env ** taper                   # 반두께 — 양 끝에서 날카롭게 수렴(cusp)
        if Wc < 0.5:
            continue
        yc = ybase - bow * env                     # 중심선이 위로 휜다 (호)
        y_lead = yc - Wc                            # 바깥(볼록) = 인선
        span = 2 * Wc
        # 애니: 밝은 기운 한 줄기 (감김 순환 — 이음매 없음)
        pulse = 0.0 if phase is None else math.exp(-((abs((t - phase + 0.5) % 1.0 - 0.5)) / 0.17) ** 2)
        abias = 1.0 + head_bias * (t - 0.5)        # 머리로 갈수록 살짝 짙다
        for y in range(h):
            dlead = (y + 0.5) - y_lead              # 인선(0)에서 안으로
            if dlead < -1.4 or dlead > span + (1.4 if centered else 0.0):
                continue
            if dlead < 0 or (centered and dlead > span):    # 바깥/안쪽 번짐 테 — 밝은 배경 방호(RP-3)
                off = -dlead if dlead < 0 else dlead - span
                fall = max(0.0, 1.0 - off / 1.4) ** 1.3
                a = 150 * (0.35 + 0.65 * env) * fall * abias
                if a >= 10:
                    g[y][x] = _slash_px(0.0, a)     # 어두운 먹(0단, value 92)
                continue
            if centered:                            # 찌르기 광선 — 심이 가운데
                s = abs((y + 0.5) - yc) / Wc        # 0 심 … 1 가장자리
                core = (1.0 - s) ** 1.15
            else:                                   # 반월 — 심이 바깥 인선, 안으로 스민다
                core = (1.0 - max(0.0, dlead - SLASH_LEAD_PX) / max(0.6, span - SLASH_LEAD_PX)) ** 1.3
            core = min(1.0, core + 0.55 * pulse)    # 기운이 흐르며 심이 밝아진다 (핫코어 맥동)
            a = min(255.0, 236 * (0.32 + 0.68 * env) * (0.28 + 0.72 * core) * abias + 72 * pulse)
            g[y][x] = _slash_px(core, a)
    return g


def sheath_rows():
    """날의 기 — 병기의 날을 따라 흐르는 빛. 가운데가 굵고 양 끝으로 스민다 (날을 감싼다).
    **복제가 아니다**: 손에 든 검 위에 겹쳐 흐르는 기다."""
    w, h = SLASH_W, SLASH_H
    g = _slash_blank(w, h)
    for x in range(w):
        t = x / (w - 1)
        th = (h / 2 - 1.2) * math.sin(math.pi * t) ** 0.45      # 날의 허리가 가장 굵다
        for y in range(h):
            d = abs(y + 0.5 - h / 2) / max(0.6, th)
            if d > 1.0:
                continue
            core = 1.0 - d ** 1.8
            g[y][x] = _qi_px(core, 214 * (0.35 + 0.65 * core))
    return g


def slash_ring_rows(phase=None):
    """참격선(원) — 몸을 훑고 **퍼져 나간** 고리. XZ 평면에 눕는다 (위·아래 면이 그림을 문다).
    ★리디자인: 바깥 rim = **인선**(흰-핫 1.4px) · 안으로 스민다(잔상). 벤 자리(시작)가 짙고
    돌아가며 옅어진다 (고리도 지나간 자리다). phase: 밝은 기운이 고리를 **돈다** (애니).
    바깥 테는 d ≤ 15.4 로 물린다 — 캔버스 여백 위생 (고리가 모서리를 물면 아틀라스에서 번진다)."""
    n = 32
    g = [[T] * n for _ in range(n)]
    R, bw = 11.2, 2.9                                          # 반지름 · 반두께 (바깥 rim 14.1 + 테 → 15.4)
    for y in range(n):
        for x in range(n):
            dx, dy = x + 0.5 - n / 2, y + 0.5 - n / 2
            d = math.hypot(dx, dy)
            ang = (math.atan2(dy, dx) + math.pi) / (2 * math.pi)   # 0(벤 자리) … 1
            angw = 0.30 + 0.70 * ang                           # 한쪽이 짙고 돌아가며 옅다
            dlead = (R + bw) - d                               # 바깥 rim(0)에서 안으로
            if dlead < -1.3 or dlead > 2 * bw:
                continue
            pulse = 0.0 if phase is None else \
                math.exp(-((abs((ang - phase + 0.5) % 1.0 - 0.5)) / 0.15) ** 2)
            if dlead < 0:                                      # 바깥 번짐 테 — 밝은 배경 방호
                fall = max(0.0, 1.0 + dlead / 1.3) ** 1.3
                a = 140 * angw * fall
                if a >= 10:
                    g[y][x] = _slash_px(0.0, a)
                continue
            core = (1.0 - max(0.0, dlead - 1.4) / max(0.6, 2 * bw - 1.4)) ** 1.3
            core = min(1.0, core + 0.55 * pulse)               # 기운이 rim 을 타고 돈다
            a = min(255.0, 236 * angw * (0.28 + 0.72 * core) + 72 * pulse)
            g[y][x] = _slash_px(core, a)
    return g


def qi_textures():
    """획 11장 — 그림은 알파가 판다 (형체는 판때기, 모양은 이 PNG)."""
    tex = {}

    # 참격선 3종 — **반월(弧)·찌르기 광선·원참** (2026-07-17 리디자인). 각자 애니 스트립이다:
    #   _anim_strip 이 SLASH_FRAMES(8) 장을 세로로 쌓는다 (h = n·canvas). 각 프레임의 그림은
    #   맨 위(arc/line=64칸 중 16 · ring=32칸 중 32)에 얹히고, 모델 uv v 는 프레임의 위쪽을 문다.
    #   .mcmeta 는 write_qi_assets 가 굽는다 (아래) — 없으면 축 ⑯ 이 세로 스트립을 거부한다.
    tex["qi/slash_arc"] = _anim_strip(                          # 큰 반월 — 굽는다
        lambda ph: slash_rows(SLASH_W, SLASH_H, wmax=5.2, bow=3.2, taper=0.60,
                              head_bias=0.28, phase=ph), SLASH_FRAMES, SLASH_CANVAS)
    tex["qi/slash_line"] = _anim_strip(                         # 얇은 찌르기 광선 — 곧다·심이 가운데
        lambda ph: slash_rows(SLASH_W, SLASH_H, wmax=3.2, bow=0.0, taper=0.80,
                              centered=True, head_bias=0.34, phase=ph), SLASH_FRAMES, SLASH_CANVAS)
    tex["qi/slash_ring"] = _anim_strip(                         # 원참 — 퍼져 나간 고리
        lambda ph: slash_ring_rows(phase=ph), SLASH_FRAMES, 32)
    tex["qi/blade_sheath"] = _square_pad(sheath_rows())

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

    # ── 15문파 전승 오의 4장 (skill_motion.yml display.models 의 신규 청구) ──
    # 히트박스가 형체를 정한다: **원**은 펴지고(고리·손바닥), **선**은 앞에 선다(빗살).
    # 문파 17종이 이 넷 + 기존 넷(매화·태극·군림·혈해)을 나눠 쓴다 — 파티클·소리가 문파를 가른다.
    g = _qi_blank()                                   # 검풍(劍風) — 여덟 획이 사방으로 (원 오의)
    c = QI_TEX / 2
    # 【깊이 버퍼】 붓은 겹쳐도 **진한 쪽이 남는다.** 그냥 덮어쓰면 뒤에 지나간 획의 옅은 끝이
    #   앞 획의 속을 지운다 — 그러면 4단 먹이 3단으로 무너진다 (texture_audit 이 그것을 잡는다).
    depth = [[0.0] * QI_TEX for _ in range(QI_TEX)]
    for k in range(8):
        a = k * math.pi / 4 + math.pi / 16
        for step in range(28):                        # 안(r=5)에서 밖(r=15)으로 — 밖으로 갈수록 가늘고 옅다
            r = 5.0 + step * (10.0 / 27)
            t = step / 27.0
            th = 2.6 * (1.0 - t) ** 0.7               # 획의 반두께 (뿌리가 굵다)
            bend = 0.30 * t                           # 살짝 휜다 — 곧은 살이 아니라 **베고 지나간 자리**다
            px_, py_ = c + r * math.cos(a + bend), c + r * math.sin(a + bend)
            for dy in range(-4, 5):
                for dx in range(-4, 5):
                    d = math.hypot(dx, dy)
                    edge = th + 1.5                   # 획의 가장자리는 **스민다** (속이 진하고 끝이 번진다)
                    if d > edge:
                        continue
                    x, y = int(px_) + dx, int(py_) + dy
                    if 0 <= x < QI_TEX and 0 <= y < QI_TEX:
                        depth[y][x] = max(depth[y][x],
                                          (1.0 - d / edge) ** 1.3 * (1.0 - 0.30 * t))
    for y in range(QI_TEX):
        for x in range(QI_TEX):
            if depth[y][x] > 0.02:
                _ink(g, x, y, depth[y][x])
    tex["ult/sword_gale"] = g

    g = _qi_blank()                                   # 뇌격의 빗살 — 앞에 선 여러 세로 획 (선 오의)
    for k in range(13):                               # 열셋 (섬전십삼검뢰의 수 — 다른 선 오의도 이 형체를 쓴다)
        x0 = 3 + k * 2                                # 2px 간격 — 가운데가 높고 양옆이 낮다 (한 줄로 간다)
        h = 26.0 * (0.35 + 0.65 * math.sin(math.pi * (k + 0.5) / 13) ** 0.8)
        for y in range(int(QI_TEX - 2 - h), QI_TEX - 2):
            t = min(1.0, (QI_TEX - 2 - y) / max(1.0, h))   # 아래(뿌리)가 진하고 위(끝)가 스민다
            _ink(g, x0, y, 0.35 + 0.65 * (1.0 - t) ** 0.6)
            _ink(g, x0 + 1, y, (0.25 + 0.55 * (1.0 - t) ** 0.6) * 0.8)
    tex["ult/bolt_comb"] = g

    g = _qi_blank()                                   # 장심(掌心) — 손바닥 하나 (소림의 원 오의)
    _disc(g, c, c + 4.0, 7.4)                         # 손바닥
    for k, (ax, ay, ln, w) in enumerate((                 # 다섯 손가락 — 엄지가 짧고 굵다
            (-9.5, -2.0, 6.5, 2.2), (-4.0, -8.5, 8.0, 1.9), (0.0, -10.0, 9.0, 2.0),
            (4.2, -8.8, 8.0, 1.9), (8.6, -5.0, 6.8, 1.8))):
        ang = math.atan2(ay, ax)
        for step in range(16):
            t = step / 15.0
            r = t * ln
            px_ = c + ax * 0.55 + r * math.cos(ang)
            py_ = c + 2.0 + ay * 0.55 + r * math.sin(ang)
            th = w * (1.0 - 0.45 * t)
            for dy in range(-3, 4):
                for dx in range(-3, 4):
                    d = math.hypot(dx, dy)
                    if d <= th:
                        _ink(g, int(px_) + dx, int(py_) + dy, 1.0 - d / max(0.8, th))
    tex["ult/palm_bloom"] = g

    g = _qi_blank()                                   # 무형의 독 — 바닥을 기는 재의 겹고리 (터지지 않는다)
    for r_out, r_in in ((15.5, 12.6), (11.2, 8.6), (6.8, 4.6)):
        for y in range(QI_TEX):
            for x in range(QI_TEX):
                d = math.hypot(x + 0.5 - c, y + 0.5 - c)
                if not (r_in <= d <= r_out):
                    continue
                # 고리의 속이 진하고 테가 스민다 (4단 먹). **어둡게 만드는 것은 코드다** —
                # 텍스처를 뭉개면 형체가 사라진다 (motions.독무_범람 brightness 4 가 그 일을 한다)
                _ink(g, x, y, min(1.0, min(r_out - d, d - r_in) / 1.6))
    tex["ult/toxin_veil"] = g
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


def _slash(length_m, width_m, thick_m=0.04):
    """참격선 한 자루 — **기하 중심이 원점(8,8,8)** 이고 몸이 ±X 로 반씩 뻗는다.
       (머리는 +X 끝 · 꼬리는 −X 끝. 그림·굵기·테이퍼는 예전 그대로다 — 자리만 옮겼다)

    ═══ 왜 '머리=원점' 을 버렸나 (2026-07 — 획이 몸 왼쪽에서 나오던 병) ═══
    옛 계약은 **머리를 원점에 두고 몸을 전부 −X 로** 늘어뜨렸다 (x −16..8). 이유는 있었다:
    scale 이 원점을 중심으로 먹으므로 머리를 그 점에 두어야 **꼬리부터 지워진다**.
    그런데 그 계약에는 대가가 있었고, 아무도 그 대가를 재지 않았다 —
      **모델의 기하 중심이 원점에서 x −12 만큼 치우친다.**
    ItemDisplay 는 모델을 **엔티티 자리에 중심을 두고** 그린다. 그리고 모델의 +X 는
    (SkillDisplay.pose 의 좌표계 주석대로) **앞이 아니라 좌우 축**이다. 그래서 획은
    시전자의 **정면이 아니라 옆구리 한쪽**에 통째로 걸렸다 — 사용자가 본 그것이다.

    ═══ 그러면 '꼬리부터 지워진다' 는 어쩌나 — **코드가 진다** ═══
    옳은 계약은 이것이다: **모델은 중심에 서고, 위치·방향·성장은 코드가 정한다.**
    모델에 오프셋을 박아 두면 그 편향을 **모든 모션이 물려받는다** (실제로 그랬다).
    그래서 머리 고정은 모델이 아니라 **코드의 translation** 이 한다:
        translation.x = halfLen × (1 − scale.x)        (SkillDisplay.transform · anchor: head)
      · scale.x = 1 → translation 0  ⇒ 획이 **시전자의 정면에 좌우 대칭으로** 걸린다 (고친 것)
      · scale.x → 0 → translation → halfLen ⇒ 획이 **머리 쪽으로 수축한다** = 꼬리부터 지워진다
    ⇒ 옛 그림(만화의 검격)은 **한 틱도 안 잃고** 살아남고, 편향만 사라진다.
    (등록부 models.<키>.anchor: head 가 이 계약을 청구한다 — 코드가 지어내지 않는다)"""
    lx = length_m * 16.0
    # 중심 정렬이라 한계는 **양쪽**으로 절반씩이다: 8 ± lx/2 가 [-16, 32] 안에 들어야 한다.
    if 8.0 - lx / 2.0 < -16.0 or 8.0 + lx / 2.0 > 32.0:
        raise ValueError(f"참격선 {length_m}m: x {8 - lx / 2:.0f}..{8 + lx / 2:.0f} — "
                         f"MC 원소 좌표 한계 [-16, 32] 를 넘는다. 등록부의 size[0] 을 낮춰야 한다")
    hy, hz = width_m * 8.0, thick_m * 8.0
    # ★ v 는 0..SLASH_UV_V(=4) — 텍스처가 64x64 정사각이고 그림은 **맨 위 64x16** 에 있다.
    #   (예전 64x16 시절의 uv [0,0,16,16] 과 화면상 **똑같은 그림**이다 — SLASH_CANVAS 주석 참조)
    faces = {f: {"texture": "#0",
                 "uv": [0, 0, 16, SLASH_UV_V] if f in ("north", "south") else _HIDE}
             for f in ("north", "south", "east", "west", "up", "down")}
    return [{"from": [8.0 - lx / 2.0, 8.0 - hy, 8.0 - hz],   # 꼬리 (−X 끝)
             "to": [8.0 + lx / 2.0, 8.0 + hy, 8.0 + hz],     # … 머리 (+X 끝) — 중심이 원점이다
             "faces": faces}]


def _slash_ring(dia_m, width_m):
    """참격선(원) — 몸을 훑고 지나간 고리. **원점 = 고리의 중심** (몸이 그 자리에 선다).
    XZ 평면에 눕고 위·아래 면이 그림을 문다. scale = {lenScale×2, thick, lenScale×2}."""
    r, hy = dia_m * 8.0, width_m * 8.0
    faces = {f: {"texture": "#0", "uv": [0, 0, 16, 16] if f in ("up", "down") else _HIDE}
             for f in ("north", "south", "east", "west", "up", "down")}
    return [{"from": [8.0 - r, 8.0 - hy, 8.0 - r],
             "to": [8.0 + r, 8.0 + hy, 8.0 + r], "faces": faces}]


def _sheath():
    """날의 기 — 손에 든 병기의 날에 **겹쳐** 흐른다 (복제가 아니다).
    sizeUnit 경로다 (scale = size × 격의 배율) ⇒ **단위 상자를 가득 채운다**.
    판 두 장을 교차시켜 어느 각도에서 보아도 날이 빛나 보이게 한다 (바닐라 화초의 문법)."""
    out = []
    for frm, to, show in (([0, 0, 7.4], [16, 16, 8.6], ("north", "south")),
                          ([0, 7.4, 0], [16, 8.6, 16], ("up", "down"))):
        # ★ blade_sheath 도 64x64 정사각 · 그림은 맨 위 16행 ⇒ v 는 0..SLASH_UV_V
        out.append({"from": frm, "to": to,
                    "faces": {f: {"texture": "#0",
                                  "uv": [0, 0, 16, SLASH_UV_V] if f in show else _HIDE}
                              for f in ("north", "south", "east", "west", "up", "down")}})
    return out


# 획 11종 = (키, 기하).
#   · 참격선 3종 — 원점이 **머리**다 (−X 로 늘어진다). 모델이 제 미터 치수로 구워진다.
#   · 그 밖 8종 — 원점이 중심이고 **단위 상자**를 채운다 (scale = size).
#   · blade_arc · blade_heavy 는 **버렸다** — 등록부(display.models)에서 사라졌다.
#     "검을 복제해 날리는" 방식을 폐기하고 참격선으로 갔기 때문이다. 안 쓰는 키는 굽지 않는다.
QI_MODELS = {
    "qi/slash_arc": _slash(1.5, 0.55),      # ★ size[0] 청구: 3.0 → 1.5 (아래 주석 참조)
    "qi/slash_line": _slash(1.5, 0.35),     # ★ 동일
    "qi/slash_ring": _slash_ring(2.0, 0.35),
    "qi/blade_sheath": _sheath(),
    "qi/bolt_edge": _plate("z"), "qi/bolt_lance": _lance(), "qi/guard_shard": _plate("z"),
    "ult/plum_bloom": _cross(), "ult/taegeuk_disc": _plate("z"),
    "ult/emperor_edge": _plate("z"),      # 길이축이 Y 다 (4.0m 짜리 칼이 떨어진다)
    "ult/blood_tide": _plate("y"),        # 바닥을 훑는 파문 — 위/아래 면이 그림을 문다
    # ── 15문파 전승 오의 4장 (2026-07) ──
    #   bloom() 은 **회전을 주지 않는다** (yaw 로 서고 scale 만 자란다) ⇒ 앞으로 뻗는 축을 모델에 못 새긴다.
    #   그래서 원 오의는 **눕고**(y-plate) 선 오의는 **앞에 선다**(z-plate). 못 하는 것을 굽지 않는다.
    "ult/sword_gale": _plate("y"),        # 원 오의 — 사방으로 뻗은 검풍 (바닥에 눕는다)
    "ult/toxin_veil": _plate("y"),        # 당가의 독 — 바닥을 기는 재의 겹고리
    "ult/bolt_comb": _plate("z"),         # 선 오의 — 몸 앞에 선 세로 획 열셋
    "ult/palm_bloom": _plate("z"),        # 소림 — 앞을 향해 펴지는 손바닥
}


def write_qi_assets() -> int:
    """획 9종 — PNG + 모델 + 아이템 정의. motion_audit ⑧ 의 '팩 미구움' 경고가 여기서 꺼진다.

    ★ 텍스처는 textures/**item**/qi|ult/ 에 굽고 모델은 honcheon:**item**/<키> 를 문다 —
      그 자리만이 아틀라스(items)에 꿰매진다 (위 QI_TEX_DIR 주석 참조).
      **모델·아이템정의의 키(honcheon:qi/slash_arc)는 한 글자도 안 바뀐다** — 등록부
      (skill_motion.yml display.models)가 그 이름을 부르므로 여기서 이름을 바꾸면 배선이 끊긴다.
      바뀌는 것은 **텍스처가 사는 자리**뿐이다."""
    tex = qi_textures()
    # ★ 오의의 판만 물든다 (옥 → 청백). qi/* 는 무색 그대로 — 여섯 격이 그 한 장을 나눠 쓰기 때문이다.
    # 【물들이지 않는 둘 — 둘 다 "빛나지 않는 것이 정보"인 자리다】
    #   · ult/blood_tide  (혈해만리) — 이미 혈조(BT_STEPS)로 구워졌다. 【채색 예외】를 청백으로 덮으면
    #     "저것은 사람의 기가 아니다" 라는 정보가 사라진다.
    #   · ult/toxin_veil  (무형지독·만천화우) — **무형(無形)은 빛나지 않는다.**
    #     등록부가 이미 그렇게 적어 두었다: 독무_범람 brightness [4, 15] — "다른 오의는 빛나고,
    #     이것은 빛나지 않는다 — 그것이 무형이다" (skill_motion.yml display.motions).
    #     여기에 옥빛을 칠하면 **등록부와 팩이 서로 다른 말을 한다.**
    ULT_UNTINTED = ("ult/blood_tide", "ult/toxin_veil")
    for key in list(tex):
        if key.startswith("ult/") and key not in ULT_UNTINTED:
            tex[key] = tint_rows(tex[key], ULT_JADE, ULT_PALE)
    for key, rows in tex.items():
        write_png(PACK / "assets" / "honcheon" / "textures" / "item" / f"{key}.png", rows)
    # ★ 참격선 3종은 애니 스트립(h = n·canvas) — .mcmeta 로 프레임 수를 선언한다 (없으면 축 ⑯ 거부 ·
    #   클라이언트가 스트립을 한 프레임으로 오독한다). interpolate 로 기운이 부드럽게 흐른다.
    for key in ("qi/slash_arc", "qi/slash_line", "qi/slash_ring"):
        write_json(PACK / "assets" / "honcheon" / "textures" / "item" / f"{key}.png.mcmeta",
                   {"animation": {"frametime": SLASH_FRAMETIME, "interpolate": True,
                                  "frames": list(range(SLASH_FRAMES))}})
    for key, elems in QI_MODELS.items():
        write_json(MODEL_DIR / "item" / f"{key}.json", {
            "textures": {"0": f"honcheon:item/{key}", "particle": f"honcheon:item/{key}"},
            "elements": elems,
            "gui_light": "front",
        })
        write_json(ITEM_DEF_DIR / f"{key}.json",
                   {"model": {"type": "minecraft:model", "model": f"honcheon:item/{key}"}})
    return len(QI_MODELS)


# ═══ 녹색 검기(劍氣) — 평타 스윕의 초승달 참격 【2026-07 · 레퍼런스: 마크에이지】 ═══
# 【왜 새 경로 kigi/ 인가 — qi/* 무색 판을 물들이지 않는다】
#   참격선 판(qi/slash_arc)은 **여섯 격이 한 장을 나눠 쓴다** — 거기 색을 넣으면 외공기의 주먹이
#   심검처럼 빛난다 (texture_audit 축 ⑱ QI_ACHROMATIC_MAX=12 가 그것을 강제한다). 그래서 색 검기는
#   **별도 경로 honcheon:item/kigi/arc** 에 굽는다. qi/slash_arc/line/ring 은 무색 그대로 둔다.
# 【색·모양의 출처】 scratch/ref_attack/kigi_proto.py 확정본 — 두 톤 깊은 초승달: 양쪽 먹 외곽선
#   (RIM) + 고른 연두 몸(DARK→MID→BRIGHT→HOT) + 자라는 앞머리 하이라이트, 6프레임 좌→우 스윕.
#   이 파일은 그 로직을 **무수정 이식**한다 (색·형·프레임 동일). 난수 0 — 결정론.
KIGI_W, KIGI_H = 64, 22         # 가로로 긴 초승달 — 레퍼런스는 우리보다 깊은 배 (semi-circle 쪽)
KIGI_CANVAS = 64                # ★정사각 **단일 프레임** (아래 【스윙 동기화】 참조 — 스트립을 버렸다)
KIGI_FRAMES = 3                 # ★레퍼런스처럼 3단계 뚜렷한 스와이프 (사용자 확정 2026-07-19)
KIGI_UV_V = 16.0 * KIGI_H / KIGI_CANVAS   # = 5.5 — 그림은 캔버스의 **맨 위 22행**에 있다

# ═══ 【스윙 동기화 · 2026-07-19】 왜 .mcmeta 애니를 버리고 **모델 3벌**로 굽는가 ═══
# 옛 판은 arc.png 를 3프레임 세로 스트립 + .mcmeta(frametime 3)로 구웠다. 그런데 마인크래프트의
#   텍스처 애니는 **전역 시계**로 돈다 — 월드 틱을 나눈 값이 프레임 번호다. 모든 인스턴스가
#   동시에 같은 프레임을 본다. 검기 디스플레이의 수명은 draw(9)+fade(5)=14틱인데 애니 한 바퀴는
#   9틱이므로, **소환 순간의 프레임이 매번 제각각**이었다: 어떤 스윙은 3단계부터 시작하고
#   어떤 스윙은 2단계에서 끝났다. 사용자 실측: "검기 프레임이 전혀 반영이 안 된다".
# ⇒ 프레임을 **텍스처가 아니라 모델**로 가른다: arc1/arc2/arc3 (각각 정사각 64×64 · .mcmeta 없음).
#   플러그인이 스윙 중 frame_ticks 마다 ItemDisplay 의 아이템을 갈아끼워 1→2→3 을 **스윙마다
#   정확히 순서대로** 보여 준다 (SkillDisplay.advanceFrames). 그림은 한 픽셀도 안 바뀐다 —
#   옛 스트립의 세 프레임(ph 0 · 0.5 · 1.0)이 그대로 세 장이 된다.
# ── 검기의 색 — 【2026-07-20 · 사용자 결정: 등록부의 격 사다리를 따른다】 ──
#   옛 판은 레퍼런스 영상의 **연두색**이었다. 그런데 등록부(config/skill_motion.yml)의 사다리는
#   이미 색을 정해 놨다: 외공기 회백 → **검기 청회** → 강기 청록 → 어검 옥 → 심검 청백.
#   ★ 검기의 등록된 색은 **청회(124,143,152) 푸른 잿빛이고 초록이 아니다.**
#   그리고 우리가 만든 연두는 chroma 122 로 이 세계의 어느 색보다 튀었다 (청록 76 · 옥 48).
#   금지색 규약("서양 판타지의 발광을 금한다" · 상한 130)에 걸리진 않았지만 **아슬아슬했다.**
#   사용자 평가 *"혼천의 것 같지 않다"* 의 원인이 모양이 아니라 **색**이었던 셈이다.
#   ⇒ 이제 램프의 네 칸이 **전부 등록부에 있는 색**이다: 먹 → 청회 → 회백 → 청백.
#     그래서 kigi/ 를 위해 두었던 **초록 밴드 예외가 필요 없어진다** (texture_audit 축 ⑱ 참조).
KIGI_RIM = (46, 49, 54)         # 먹 — 농묵. 쇠가 지나간 자리, 빛나지 않는다
KIGI_DARK = (85, 96, 103)       # 먹과 청회 사이 (배)
KIGI_MID = (124, 143, 152)      # ★청회 — 중급(검기)의 색. 등록부가 정한 그 색이다
KIGI_BRIGHT = (178, 181, 178)   # 회백 — 잔상은 색이 아니라 재다
KIGI_HOT = (226, 240, 238)      # 청백 — 인선. 흰빛에 푸른 기가 남는다
KIGI_RIM_PX = 1.7               # 먹 외곽선 두께 (양쪽)


def _kigi_lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def _kigi_body(s, hi):
    """s: 밴드 내 위치 0(바깥)…1(안쪽). 몸은 고르게 민트, 가운데가 가장 밝다. hi: 앞머리 하이라이트."""
    center = 1.0 - abs(s - 0.42) * 1.4
    center = max(0.0, min(1.0, center))
    base = _kigi_lerp(KIGI_DARK, KIGI_MID, 0.4 + 0.6 * center)
    col = _kigi_lerp(base, KIGI_BRIGHT, min(1.0, 0.35 * center + hi))
    if hi > 0.6:
        col = _kigi_lerp(col, KIGI_HOT, (hi - 0.6) / 0.4)
    return col


KIGI_BASE = (16, 52, 34)        # 아직 채워지지 않은 밑그림 (어두운 초록 — 레퍼런스 17.2s 밑칠)


def _kigi_frame(ph):
    """한 프레임 (ph 0…1) — 22×64 RGBA. ★레퍼런스 그대로 3단계 '채워지는' 애니:
    넓고 얕은 대칭 ∩ 아크(무지개형). **위(볼록) 가장자리 = 밝은 흰빛-민트 인선**, 아래로 초록 몸.
    초록은 위 가장자리에서 아래로 **채워진다** — ph 0(윗변만) → 0.5(위쪽 반) → 1.0(가득).
    (검은 외곽선 없음: 레퍼런스는 어두운 밑그림을 초록으로 덮는다.)"""
    g = [[T] * KIGI_W for _ in range(KIGI_H)]
    ybase = KIGI_H * 0.88                      # 아크가 위로 봉긋 (배는 아래·인선은 위)
    wmax = 5.2                                 # 밴드 반두께 — ★얇은 리본(레퍼런스 초승달). 두꺼우면 바닥이 평평한 돔이 된다
    bow = 9.5                                  # 넓고 얕은 아크 (레퍼런스 무지개형)
    taper = 0.50                               # 양 끝 더 날카롭게(첨점) — 레퍼런스는 끝이 뾰족하다
    # ★레퍼런스 실물 프레임(사용자 제공 image1/2/3): 아크가 **길이 방향으로 자란다**
    #   ① 짧은 획 → ② 왼쪽에서 휘어 뻗은 초승달 → ③ 넓은 ∩ 전체 아크.
    #   (채워지는 것이 아니라 **검이 지나간 궤적이 그려지는** 애니다.)
    grow = 0.30 + 0.70 * ph                    # 그려진 아크의 길이 비율 (좌 → 우)
    for x in range(KIGI_W):
        t = x / (KIGI_W - 1)
        if t > grow:
            continue                           # 아직 검이 지나가지 않은 자리
        env = math.sin(math.pi * t)
        if env <= 0.04:
            continue
        Wc = wmax * env ** taper
        if Wc < 0.6:
            continue
        yc = ybase - bow * env
        y_out = yc - Wc                        # 위(볼록) 가장자리 = 인선
        span = 2 * Wc
        for y in range(KIGI_H):
            d = (y + 0.5) - y_out              # 0 = 위 가장자리 … span = 아래 가장자리
            if d < -1.0 or d > span:
                continue
            if d < 0:                          # 인선 바로 위 — 밝은 빛 번짐 (얇게)
                a = 210 * env * max(0.0, 1.0 + d)
                if a >= 14:
                    g[y][x] = (KIGI_HOT[0], KIGI_HOT[1], KIGI_HOT[2], min(255, int(a)))
                continue
            u = d / max(0.8, span)             # 0 = 위 인선 … 1 = 아래 가장자리
            # 몸 색: 위 인선이 가장 밝고(흰빛 민트) 아래로 갈수록 초록이 짙어진다
            if u < 0.22:
                col = _kigi_lerp(KIGI_HOT, KIGI_BRIGHT, u / 0.22)
            elif u < 0.66:
                col = _kigi_lerp(KIGI_BRIGHT, KIGI_MID, (u - 0.22) / 0.44)
            else:
                col = _kigi_lerp(KIGI_MID, KIGI_DARK, (u - 0.66) / 0.34)
            # 자라는 앞머리(방금 검이 지나간 자리)는 더 희게 — 궤적이 그려지는 인상
            head = max(0.0, 1.0 - (grow - t) / 0.16)
            if head > 0.0:
                col = _kigi_lerp(col, KIGI_HOT, min(1.0, head))
            a = min(255.0, 252 * (0.6 + 0.4 * env))
            g[y][x] = (col[0], col[1], col[2], int(a))
    return g


_KIGI_SHAPES = None


def _kigi_traced(idx):
    """★ 레퍼런스 실물 프레임을 **픽셀 그대로** 쓴다 (2026-07-20 · 사용자 지정).

    왜: 실루엣을 수식(sin 아크)으로 만들던 것을 버린다. 레퍼런스의 세 프레임은
      ① 짧고 굵은 대각 획 → ② 왼쪽으로 휘어 뻗은 발톱 → ③ 넓은 ∩ 아치 로,
      대칭 아크 수식으로는 흉내 낼 수 없는 손그림이다. 사용자가 영상에서 뽑아 준
      image2/image1/image3 에서 그려진 픽셀을 읽어(tools/kigi_trace.py) 마스크로 쓴다.
      순서도 사용자가 지정했다: image2=1번 · image1=2번 · image3=3번.
    색: 마스크 안에서 **위(바깥) 가장자리가 밝은 인선**, 아래로 갈수록 짙은 초록.
    """
    global _KIGI_SHAPES
    if _KIGI_SHAPES is None:
        path = Path(__file__).resolve().parent.parent / "kigi_shapes.json"
        _KIGI_SHAPES = json.loads(path.read_text(encoding="utf-8"))
    sh = _KIGI_SHAPES
    grid = sh["frames"][idx]
    gw, gh = sh["w"], sh["h"]
    g = [[T] * KIGI_W for _ in range(KIGI_H)]
    # 각 세로줄에서 '그림의 위 끝' 을 찾아 거기서부터의 깊이로 색을 정한다 (인선이 위)
    for x in range(min(gw, KIGI_W)):
        col = [y for y in range(gh) if grid[y][x]]
        if not col:
            continue
        top, bot = col[0], col[-1]
        span = max(1, bot - top)
        for y in col:
            if y >= KIGI_H:
                continue
            u = (y - top) / span              # 0 = 위 인선 … 1 = 아래 끝
            if u < 0.22:
                c = _kigi_lerp(KIGI_HOT, KIGI_BRIGHT, u / 0.22)
            elif u < 0.66:
                c = _kigi_lerp(KIGI_BRIGHT, KIGI_MID, (u - 0.22) / 0.44)
            else:
                c = _kigi_lerp(KIGI_MID, KIGI_DARK, (u - 0.66) / 0.34)
            g[y][x] = (c[0], c[1], c[2], 250)
    return g


def kigi_canvas(ph):
    """단계 하나 — **정사각 64×64 단일 프레임**. 그림은 맨 위 22행 · 아래 42행은 투명 여백.
    모델 uv v(0..KIGI_UV_V) 가 그 위쪽 22행을 문다 (참격선의 SLASH_UV_V 규약과 같은 문법).
    .mcmeta 를 동반하지 않는다 — 정사각이므로 축 ⑯(정사각 or 스트립+mcmeta)이 그대로 통과한다.
    ph 0.0/0.5/1.0 → 레퍼런스 1/2/3 번 프레임."""
    idx = 0 if ph < 0.25 else (1 if ph < 0.75 else 2)
    g = _kigi_traced(idx)
    return [row[:] for row in g] + [[T] * KIGI_W for _ in range(KIGI_CANVAS - KIGI_H)]


def _kigi_model(length_m=1.5, width_m=0.55, thick_m=0.04):
    """검기 참격의 판 — 참격_호(qi/slash_arc) 기하를 본뜬다: 중심이 원점(8,8,8)이고 ±X 로 반씩 뻗는다
    (축 ⑰ 중심 계약). 초승달이 더 깊고 두꺼우니 UV v 를 콘텐츠(22행)에 맞춘다 (참격_호는 16행=4.0).
    size 정규화상수 length=1.5 는 참격_호와 호환 (등록부 size[0]=1.5 · MC 좌표 한계 [-16,32])."""
    lx, hy, hz = length_m * 16.0, width_m * 8.0, thick_m * 8.0
    faces = {f: {"texture": "#0",
                 "uv": [0, 0, 16, KIGI_UV_V] if f in ("north", "south") else _HIDE}
             for f in ("north", "south", "east", "west", "up", "down")}
    return [{"from": [8.0 - lx / 2.0, 8.0 - hy, 8.0 - hz],   # 꼬리 (−X 끝)
             "to": [8.0 + lx / 2.0, 8.0 + hy, 8.0 + hz],     # … 머리 (+X 끝) — 중심이 원점이다
             "faces": faces}]


def write_kigi_assets() -> int:
    """녹색 검기 3단계 — kigi/arc1·arc2·arc3 (각각 정사각 PNG + 모델 + 아이템 정의 · .mcmeta 없음).

    등록부(config/skill_motion.yml display.models 참격_호_검기1/2/3)가 honcheon:kigi/arc1|2|3 을
    청구하고, kigi_slash.frame_models 가 스윙 중 갈아끼울 순서를 적는다 (위 【스윙 동기화】 참조).
    옛 kigi/arc(스트립+mcmeta)는 **더 이상 굽지 않고 자리에 남은 것도 지운다** — 등록부가 안 부르는
    자산은 model_key_audit 의 '죽은 자산'이다."""
    base = PACK / "assets" / "honcheon" / "textures" / "item"
    # 옛 스트립의 잔해를 걷는다 (빌더는 덮어쓸 뿐 지우지 않는다 — 남으면 죽은 자산 위반)
    for stale in (base / "kigi" / "arc.png", base / "kigi" / "arc.png.mcmeta",
                  MODEL_DIR / "item" / "kigi" / "arc.json", ITEM_DEF_DIR / "kigi" / "arc.json"):
        if stale.exists():
            stale.unlink()
    for i in range(KIGI_FRAMES):
        key = f"kigi/arc{i + 1}"
        ph = i / max(1, KIGI_FRAMES - 1)          # 0 · 0.5 · 1.0 — 옛 스트립의 세 프레임 그대로
        write_png(base / f"{key}.png", kigi_canvas(ph))
        write_json(MODEL_DIR / "item" / f"{key}.json", {
            "textures": {"0": f"honcheon:item/{key}", "particle": f"honcheon:item/{key}"},
            "elements": _kigi_model(),
            "gui_light": "front",
        })
        write_json(ITEM_DEF_DIR / f"{key}.json",
                   {"model": {"type": "minecraft:model", "model": f"honcheon:item/{key}"}})
    return KIGI_FRAMES


