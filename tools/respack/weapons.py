"""병기 9계열 × 5등급 + 명병 8문파 — 아이콘 격자·3D 리그·MYEONGBYEONG 등록부 (T2 소유)."""
# 기계 분할 산출 — 원본: tools/build_resourcepack.py (pack_upgrade_v1.md §2 0단계).
# 로직 무수정: 함수 본문·상수 값은 원본 그대로다 (이동만 했다).
import json
import math
import struct
import sys
import zlib
from pathlib import Path
from .core import (
    ITEM_DEF_DIR, ITEM_MODEL_DIR, ITEM_TEX_DIR, PAINT_DIR, T, _center,
    _display, h32, mix, octave, paint_rows, smooth_octave, write_json, write_png,
)

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

# ─── 명병(名兵)의 포인트 색 — 「묵청(墨靑)」의 청량(淸涼) 축 ───
# 묵풍(墨風)이 세계의 기반이고, 청량은 **희귀함과 신비의 강조**다 (주조색이 아니라 포인트).
# 그래서 이 세 색은 **한두 픽셀**만 쓴다 — 코등이·물미·날끝. 면을 칠하면 그건 포인트가 아니라 도배다.
PLUM = (176, 92, 108, 255)        # 매화(梅花) — 화산. 주사에 분홍기 (저채도 — 형광 금지)
PLUM_HI = (214, 138, 152, 255)    # 꽃술
JADE = (122, 176, 168, 255)       # 옥(玉) — 청량 포인트 (무당 태극 · 소림 반야)
POISON = (126, 158, 118, 255)     # 독(毒) — 당가. 옥과 갈려야 한다 (푸른 옥 ↔ 누런 독)
MIST = (212, 218, 224, 255)       # 백(白) — 곤륜의 구름 · 청성의 달 · 해남의 파도.
                                  # ★ 청량(옥·매화·독)이 아니다 — 수묵의 흰 획이다. 세 문파가 색을
                                  #   나눠 쓰고 **모양과 자리**로 갈린다 (축 ⑭ 는 회색조만 잰다)
ROPE = (192, 168, 122, 255)       # 삼줄 — 개방의 매듭(結). 어두운 노끈은 먹 외곽선에 먹혀
ROPE_DIM = (144, 122, 84, 255)    #   안 세어졌다 (몽타주 육안) — 결은 볏짚빛이라야 아이도 센다

WPN_PALETTE = {
    "H": BLADE_HI, "L": BLADE_LIT, "B": BLADE_MID, "S": BLADE_DIM, "D": BLADE_SPINE,
    "G": FIT_HI, "g": FIT_MID, "f": FIT_DIM,
    "W": GRIP_HI, "w": GRIP_MID, "x": GRIP_DIM, "X": GRIP_DARK,
    "R": RING_HI, "e": RING_MID,
    "t": TASSEL, "T": TASSEL_HI, "m": BLOOD, "M": BLOOD_HI, "K": WPN_OUT,
    "p": PLUM, "P": PLUM_HI, "j": JADE, "d": POISON,      # 명병 — 문파의 포인트 색
    "u": MIST,                                            # 명병 — 흰 획 (구름·달·파도)
    "r": ROPE, "z": ROPE_DIM,                             # 명병 — 개방 매듭 (삼줄 2톤)
}


# ═══ 마감(鍛) — 플레이스홀더 → 본 아트 승격 (T2 · pack_upgrade_v1.md 1단계) ═══
# 평면 띠는 재질이 아니다. 강철에는 담금결이, 가죽 감기에는 손때가, 놋에는 마모가 있다.
# ★ 규율 — 마감은 **순수 f(x, y, 문자)** 다. 등급·문파·계열이 식에 들어가지 않는다:
#   비교되는 두 장(축 ⑩ 인접 등급 · 축 ⑭ 명병 쌍)이 같은 자리에 같은 문자를 가지면
#   마감 후에도 **같은 색**이다 — 검수가 재던 회색조 변별이 한 픽셀도 흔들리지 않는다.
#   문자가 다른 자리는 팔레트 계단(인접 문자 간 루마 격차 ≥ 23)이 지배하고, 마감의 진폭은
#   ±4.5 루마 이하라 16루마 문턱을 넘나들지 못한다. 난수 없음 — octave/smooth_octave 뿐.
_BLADE_CH = frozenset("HLBSD")    # 강철 — 담금결 (길이 방향으로 흐르는 결 + 칼끝이 빛을 되쏜다)
_GRIP_CH = frozenset("WwxX")      # 가죽 감기 — 손때 (잔결)
_FIT_CH = frozenset("Ggf")        # 놋 — 마모 (넓고 느린 얼룩)


def _shift(c, dv):
    """RGBA 한 픽셀의 루마 이동 — 색상은 안 바뀐다 (세 채널 동일 이동)."""
    return (min(255, max(0, round(c[0] + dv))), min(255, max(0, round(c[1] + dv))),
            min(255, max(0, round(c[2] + dv))), c[3])


def forge_rows(grid):
    """병기 격자 → RGBA. 표식 문자(고리 R/e · 수실 · 혈적 · 매화·옥·독·백)는 건드리지 않는다 —
    표식은 정보이고, 마감은 재질이다 (정보를 만지면 등급·문파가 흐려진다)."""
    rows = paint_rows(grid, WPN_PALETTE)
    for y in range(16):
        for x in range(16):
            ch = grid[y][x]
            if ch in _BLADE_CH:
                # 담금결 — 날의 길이 좌표(자루 0 → 칼끝 1)를 따라 칼끝이 밝다 + 낮은 물결
                s = (x - y + 15) / 30.0
                dv = (s - 0.5) * 5.0 + smooth_octave(x, y, 4, 0x51, 2.0)
            elif ch in _GRIP_CH:
                dv = octave(x, y, 1, 0x66, 2.5)
            elif ch in _FIT_CH:
                dv = smooth_octave(x, y, 8, 0x29, 2.0)
            else:
                continue
            rows[y][x] = _shift(rows[y][x], dv)
    return rows

# ═══ V2-W 3차 — 겐신(TWC) 문법: 평판 원소 + 64px SDF 페인팅 【2026-07-16】 ═══════════
# 조율자 판정: 2차까지도 상자 덩어리로 읽힌다. 레퍼런스 팩류의 실제 비결은 상자 쌓기가 아니라
#   ① 날/몸통 = **얇은 평판 1~2장** (곡선 실루엣은 텍스처 알파가 깎는다 — 획(qi)의 문법)
#   ② 음영·그라데이션·문양은 **고해상 페인팅**에 굽는다 (감사 개정: hi_res_channels 등재 + 축 ㉓ — 3차 64px → 14차 128px)
#   ③ 입체 악센트만 소형 원소 (코등이 보스 · 고리 · 물미 구슬 · 감김 십자판)
# 페인팅은 SDF(부호 거리장)로 그린다 — 폴리라인/베지어/호까지의 거리로 실루엣·음영·인선을
# 전부 수식이 결정한다 (난수 0 — 씨앗은 병기 키의 crc32).
# 축 ㉓ 준수: _SHEET 정사각 (등록 해상도 — 14차 128) · 평균 채도 ≤60 · 픽셀 채도>160 ≤1% · 청보라 대역 ≤1% ·
# 알파 이분(가장자리 1~2px 램프만) · 적색 paint 0.12/myeong 0.13 · 모델이 실제 참조.
# ═══ V2-W 14차 — 복셀 정밀화: 시트 64→128 【2026-07-17】 ═══════════════════════
# SDF 절차 페인팅은 해상도 무관하게 스케일된다 (셰이프·스타일은 전부 모델 좌표의 수식) —
# 같은 그림을 128x128 로 굽고, 복셀 피치가 절반이 되어 같은 무기 크기에서 계단이 곱다.
# 아래 상수는 전부 _SHEET 에서 유도한다 (64 로 되돌리면 13차 판이 비트 단위로 재현된다).
_SHEET = 128
_PXS = _SHEET // 64                # 13차(64px) 대비 픽셀 배율 — 패치·밴딩 물리 치수 보존용
_UVPX = 16.0 / _SHEET              # 시트 1px 의 UV 폭 (모델 UV 공간은 언제나 0..16)
_CANVAS_Y0 = 6 * _PXS              # 0..(_CANVAS_Y0-1)행 = 보조 스트립 (감김 윗면 · 패치 · 투명 구역)
_PATCH_GRIP = (0, 0, 48 * _PXS, _CANVAS_Y0)               # 감김 십자판 윗면
_PATCH_BRASS = (48 * _PXS, 0, 52 * _PXS, _CANVAS_Y0)      # 놋 — 보스·물미 구슬 악센트
_PATCH_RING = (52 * _PXS, 0, 56 * _PXS, _CANVAS_Y0)       # 고리 악센트
_PATCH_MARK = (56 * _PXS, 0, 60 * _PXS, _CANVAS_Y0)       # 문파 문양 악센트 (없으면 놋빛)
_PATCH_CLEAR = (60 * _PXS, 0, 64 * _PXS, _CANVAS_Y0)      # 투명 — 테(옆면)가 이 자리를 문다

# ═══ V2-W 15차 — 128px 디테일 페인팅 강화 【2026-07-17 · 사용자 지시 "그림도 조금 변경 —
# 정밀화 판에 맞게 디자인적 강화"】 ═══════════════════════════════════════════════
# 14차가 복셀 알을 절반으로 곱게 했지만 그림은 64px 설계 그대로였다 — 고운 알에 걸맞은
# 세부가 없다. 그래서 128px 에서 1~2px 가 되는 **잔선 층**을 각 재질 스타일 **안에** 그린다:
#   날 = 인선 이중선 · 풀러 홈선 · 하몬 물결(주+잔) · 담금결 2~3가닥 · 신병 결정면/광맥 ·
#        마병 균열 실금 / 자루 = 감김 실낱 세분 + 틈 그늘 / 의장 = 필리그리 음각 · 금 광택
#        점·모서리 여묾 · 보주 광점 코어+림 / 명병 문양 = 매화 잎맥·꽃술 낱알 · 태극 경계
#        이중선 · 파도 물거품 · 구름 소용돌이 · 새끼줄 꼬임 / 파편 = 결정면 선 + 밝은 심.
# ★ 계약: 잔선은 **색만** 바꾼다 — 알파(실루엣)·셰이프 순서·깊이 태그는 무수정이므로
#   복셀 원소·display·아이콘이 14차와 비트 단위로 같다 (기하 무수정이 코드 구조로 보장).
#   큰 형태(플랫 램프 존)는 유지 — 잔선은 그 위의 양념 (kmc/TWC 픽셀아트 규율 · 과밀 금지).
#   전부 결정론: sin 위상 = salt(crc32) — 난수 0. 진폭·주기는 전부 【잠정】.
_D15 = True   # 15차 잔선 층 스위치 — False 면 14차 그림이 비트 단위로 재현된다


def _uvr(p):
    """패치 px → UV (시트 _SHEET px = UV 16 이므로 ×_UVPX)."""
    return [p[0] * _UVPX, p[1] * _UVPX + 0.02, p[2] * _UVPX, p[3] * _UVPX - 0.02]


def _plen(pts):
    return sum(math.hypot(pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1])
               for i in range(len(pts) - 1)) or 1e-9


def _pl_dt(mx, my, pts, lens, total):
    """폴리라인까지 (거리, 전장 진행도 t, 좌우 부호 — 진행 방향의 왼쪽이 +1)."""
    best = (1e9, 0.0, 1.0)
    acc = 0.0
    for i in range(len(pts) - 1):
        ax, ay = pts[i]
        vx, vy = pts[i + 1][0] - ax, pts[i + 1][1] - ay
        L2 = vx * vx + vy * vy or 1e-9
        t = max(0.0, min(1.0, ((mx - ax) * vx + (my - ay) * vy) / L2))
        dx, dy = mx - (ax + vx * t), my - (ay + vy * t)
        d = math.hypot(dx, dy)
        if d < best[0]:
            best = (d, (acc + lens[i] * t) / total,
                    1.0 if (vx * dy - vy * dx) >= 0 else -1.0)
        acc += lens[i]
    return best


def _bez(p0, p1, p2, n=10):
    """이차 베지어 → 점 사슬 (곡선은 표집으로 온다 — SDF 는 폴리라인 하나면 족하다)."""
    out = []
    for i in range(n + 1):
        t = i / n
        a, b = 1 - t, t
        out.append((a * a * p0[0] + 2 * a * b * p1[0] + b * b * p2[0],
                    a * a * p0[1] + 2 * a * b * p1[1] + b * b * p2[1]))
    return out


def _arcpts(cx, cy, r, a0, a1, n=14):
    """호 → 점 사슬 (도 단위 · a0→a1 선형)."""
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
             cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]


def _stroke(pts, wfn, style, wmod=None):
    """굵기 있는 획 하나 — SDF 셰이프. (mx, my) → (경계까지 거리, 색 또는 None)."""
    lens = [math.hypot(pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1])
            for i in range(len(pts) - 1)] or [1e-9]
    total = sum(lens) or 1e-9
    def f(mx, my):
        d0, t, side = _pl_dt(mx, my, pts, lens, total)
        w = wfn(t) if callable(wfn) else wfn
        if wmod is not None:
            w += wmod(t, side)
        d = d0 - w
        if d >= 1.2:
            return d, None
        return d, style(t, d0, max(w, 1e-6), side, mx, my)
    return f


def _taperw(half, tip=0.86, slim=0.2):
    """날 굵기 곡선 — 원위 테이퍼 뒤 끝에서 뾰족하게 마감."""
    def w(t):
        if t < tip:
            return half * (1.0 - slim * t)
        return max(0.05, half * (1.0 - slim * tip) * (1.0 - (t - tip) / (1.0 - tip)))
    return w


def _jag(L, edge_side, t0=0.0, t1=1.0):
    """마병의 톱니 — 인선 쪽 실루엣을 물어뜯는다 (결정론 이빨: '정파의 쇠가 아니다').
    t0..t1 — 겸처럼 칼끝의 방향선을 남겨야 하는 계열은 구간을 좁힌다 (Codex §3)."""
    def wm(t, side):
        if side != edge_side or not (t0 <= t <= t1):
            return 0.0
        return -0.5 if int(t * L / 2.1) % 2 else 0.0
    return wm


def _crystal(L, edge_side, t0=0.5, t1=0.96):
    """9차 신병 — 원소 날의 결정 면 (2차 레퍼런스 kmc: 불규칙 윤곽·결정 단).
    인선 쪽 원위 실루엣이 낮은 결정 단으로 꺾인다 — 톱니(_jag)와 달리 물어뜯지 않고
    **밖으로도 자란다** (수정이 자라난 날). 결정론 계단 4주기 · 마병 톱니와 실루엣이 갈린다."""
    steps = (0.5, -0.2, 0.1, -0.4)
    def wm(t, side):
        if side != edge_side or not (t0 <= t <= t1):
            return 0.0
        return steps[int((t - t0) * L / 2.6) % 4]
    return wm


# ─── 스타일 (색 결정 — 등록 팔레트의 mix + 해방 악센트. 축 ㉓ ⓑ의 사정거리) ───────
# 9차 (A안 확정 + 2차 레퍼런스 kmc): 전 스타일이 **플랫 존**이다 — 그라데이션(연속 mix)·
# 노이즈(octave) 금지. 존 경계는 rel(폭 진행)·t(길이 진행) 문턱뿐. 신병·마병의 발광은
# 3~5단 **발광 램프**(어두운 테 → 악센트 → 백광 심)가 연기한다 — 단수 램프이지 그라데이션이 아니다.
def _steel_style(salt, grade, single=False, edge_side=-1.0, poison=False, dark=False,
                 hamon_w=0.32, groove="center", series=None, elem_t0=0.46, vein=True):
    """날 — 9차 A안+원소 사다리 (series 지정 시 · 범철은 언제나 실용 무채):
      범철 = 무딘 무채 강철 (사다리 하단이 소박해야 상단이 산다)
      정련 = A 문법: 어두운 계열 심 + 뼈백 사면 + 은은한 인선
      보병 = A 문법 + **계열 악센트빛 인선**(파일럿 A) + 온백 담금선
      신병 = 근위는 A + 룬 각인 2획, **원위(t>elem_t0)는 원소 재질** — 악센트 수정의
             4단 발광 램프 (kmc 문법: 테→악센트→백광 심) + 백광 끝
      마병 = **날 전체가 검붉은 수정** (kmc 진홍 수정날) + 발광 혈조 — 적색이 곧 계보 신호.
    series=None 이면 옛 무채 강철 (아이콘 격자·보조 부위). groove: 혈조 자리 (Codex §4).
    hamon_w: 담금선 띠 폭 — 짧은 계열 0.32 · 장병기 0.45. elem_t0: 원소 존 시작(갈고리·
    겸 끝 휨처럼 통째로 원위인 조각은 0.0 을 준다)."""
    if _R16 and not (_R16 == "calm" and grade == "beomcheol"):
        # 16차 선협 — 플랫 램프 대신 기환 문법. ★18차: calm 범철은 **현행 무채 경로** —
        # "범철은 언제나 실용 무채"(위 규약)와 사다리 하단("범철~보병 현행 유사" · 16차-b ⑥)이
        # 기운 0의 날에 계열 심을 칠할 근거를 주지 않는다 (기운이 안 열린 쇠는 그냥 쇠다).
        return _r16_steel(salt, grade, series=series, single=single,
                          edge_side=edge_side, dark=dark, calm=(_R16 == "calm"),
                          vein=vein,               # 17차 — 기맥은 오버레이 한 획 (스타일 무늬 금지)
                          poison=poison)           # 18차 — 당가 독 끝 배선 (문파 표식 유지)
    sin_, mab = grade == "sinbyeong", grade == "mabyeong"
    dull = grade == "beomcheol"
    hamon = grade == "bobyeong"
    onbaek = mix(BLADE_HI, FIT_HI, 0.24)           # 따뜻한 백색 담금선 — 금이 아니다 (§4)
    b8 = _ink8(series) if (series and not dull) else None
    if b8:
        core_c = mix(b8[0], b8[1], 0.3)            # 어두운 심 — 계열 기조색 (A 문법)
        facet = mix(BONE_MID, BLADE_LIT, 0.55)     # 뼈백 사면 (파일럿 A body)
        a_lo, a_hi = b8[4], b8[5]
        edge_full = mix(a_hi, MOON_V, 0.45)        # 악센트빛 인선 (파일럿 A edge)
        edge_soft = mix(BLADE_HI, a_hi, 0.28)      # 정련 — 강철에 악센트 기만 돈다
        rune = mix(a_hi, MOON_V, 0.55)             # 신병 룬 각인 — 발광 획 (kmc)
    def style(t, d0, w, side, mx, my):
        rel = d0 / w
        if b8 and not dark:
            if sin_ and t > elem_t0:               # ── 신병 원위 — 원소 수정 (4단 발광 램프)
                if t > 0.93:
                    return mix(a_hi, MOON_V, 0.82)             # 백광 끝
                if rel > 0.78:
                    c = mix(a_lo, b8[0], 0.5)                  # 어두운 테 (기조색 그늘)
                elif rel > 0.5:
                    c = a_lo
                elif rel > 0.24:
                    c = a_hi
                else:
                    c = mix(a_hi, MOON_V, 0.68)                # 백광 심 — 발광 착시
                if _D15:                           # 15차 — 수정의 잔선 (시트 1~2px 층:
                    fu = (t - elem_t0) * 11.0 + side * rel * 1.1 + salt * 0.05   # 1px≈0.3u)
                    if abs(fu - round(fu)) < 0.09 and rel > 0.2:
                        c = mix(c, mix(a_lo, b8[0], 0.6), 0.6)     # 결정면 경계선 (facet)
                    if t < 0.9 and abs(d0 - w * (0.3 + 0.14 * math.sin(t * 31.0
                                                                       + salt * 0.53))) < 0.14:
                        c = mix(a_hi, MOON_V, 0.8)                 # 내부 광맥 — 흐르는 실빛
                return c
            if mab:                                # ── 마병 — 날 전체 검붉은 수정 (kmc)
                if single and side == -edge_side and rel > 0.55:
                    c = mix(BLOOD, WPN_OUT, 0.62)              # 어두운 척 — 한날의 등
                elif rel > 0.78:
                    c = mix(BLOOD, WPN_OUT, 0.5)               # 어두운 테
                elif rel > 0.52:
                    c = mix(BLOOD, CRIM_V, 0.45)
                elif rel > 0.26:
                    c = CRIM_V
                else:
                    c = mix(CRIM_VHI, MOON_V, 0.3)             # 발광 심
                if _D15 and rel < 0.72 and 0.1 < t < 0.9:  # 15차 — 균열 실금: sin 영집합은
                    cr = math.sin(mx * 1.3 + math.sin(my * 2.1 + salt * 0.19) * 1.6   # 굽이치는
                                  + salt * 0.11)                                       # 긴 곡선이다
                    if abs(cr) < 0.2:
                        c = mix(c, WPN_OUT, 0.45)
                hit = (rel < 0.2 if groove == "center"
                       else (side == -edge_side and 0.5 < rel < 0.78) if groove == "spine"
                       else (t < 0.5 and rel < 0.3))
                if hit and 0.1 < t < 0.85:
                    c = mix(CRIM_VHI, MOON_V, 0.55)            # 발광 혈조 — 빛이 밴 홈
                if t < 0.08:
                    c = mix(c, WPN_OUT, 0.3)
                return c
            # ── A 문법 (정련·보병 전장 + 신병 근위)
            edge = rel > 0.62
            if single and side == -edge_side:      # 척 — 어두운 계열 심이 등을 진다
                c = mix(core_c, WPN_OUT, 0.3) if edge else core_c
            elif edge:
                ec = edge_soft if grade == "jeongryeon" else edge_full
                c = ec if (side > 0 or single) else mix(ec, facet, 0.45)   # 빛 받는 쪽만 (§7)
                if _D15 and (side > 0 or single):  # 15차 — 인선 이중선 (128px 층)
                    if d0 > w - 0.3:
                        c = mix(ec, MOON_V, 0.45)  # 예리한 흰 선 — 바깥 1px
                    elif d0 < w - 0.62:
                        c = mix(ec, facet, 0.55)   # 옅은 속선 (넓은 날만 — 좁으면 접힌다)
            elif rel < 0.34:
                c = core_c                         # 어두운 심 (파일럿 A ridge)
                if _D15 and 0.14 < t < 0.8 and abs(d0 - min(0.42, w * 0.2)) < 0.13:
                    c = mix(core_c, WPN_OUT, 0.32)     # 15차 — 혈조/풀러 홈선 한 쌍
            else:
                c = facet                          # 뼈백 사면
                if _D15 and 0.1 < t < 0.88 and (abs(d0 - w * 0.44) < 0.12
                                                or abs(d0 - w * 0.58) < 0.1):
                    c = mix(facet, core_c, 0.24)   # 15차 — 강철 결 (길이 방향 담금선 2가닥)
            if hamon and not edge and 0.12 < t < 0.86:
                hb = 0.4                           # 15차 — 하몬 물결: 주 물결 + 잔 물결
                if _D15:
                    hb += (0.09 * math.sin(t * 23.0 + salt * 0.37)
                           + 0.035 * math.sin(t * 67.0 + salt * 0.81))
                if hb < rel < hb + hamon_w:
                    c = onbaek                     # 보병 왕관 — 온백 담금선 (물결치는 띠)
                elif _D15 and hb - 0.22 < rel < hb - 0.1:
                    c = mix(c, onbaek, 0.45)       # 잔 물결 — 옅은 겹선
            if sin_ and rel < 0.3 and (0.15 < t < 0.2 or 0.26 < t < 0.3):
                c = rune                           # 룬 각인 2획 — 심에 새겨 빛난다 (kmc)
            if poison and t > 0.82:
                c = POISON
            if t < 0.1:
                c = mix(c, WPN_OUT, 0.3)           # AO — 날 뿌리 그림자 앵커
            return c
        # ── 무채 강철 (범철 · series 미지정 부위 · 곤륜 음 갈래)
        edge = rel > 0.62
        if dark:                                   # 곤륜 음(陰) 갈래
            c = BLADE_SPINE if rel < 0.5 else BLADE_DIM
        elif single and side == -edge_side:        # 척(脊) — 한날의 어두운 등 (투톤 §7)
            c = BLADE_SPINE if rel > 0.62 else (BLADE_DIM if rel > 0.3 else BLADE_MID)
        elif edge:
            c = BLADE_HI if (side > 0 or single) else BLADE_LIT   # 림은 빛 받는 쪽만 (§7)
        elif rel < 0.22:
            c = BLADE_LIT                          # 등줄기(鎬)
        else:
            c = BLADE_MID
        if dull:                                   # 범철 — 계단을 통째로 한 단 내린다
            c = {BLADE_HI: BLADE_LIT, BLADE_LIT: BLADE_MID,
                 BLADE_MID: BLADE_DIM, BLADE_DIM: BLADE_SPINE}.get(c, c)
        if _D15 and not (dark or mab or edge):     # 15차 — 무채 강철도 결은 지녔다 (은은하게)
            if 0.14 < t < 0.8 and abs(d0 - min(0.42, w * 0.2)) < 0.12:
                c = mix(c, BLADE_SPINE, 0.28)      # 풀러 홈선 한 쌍
            elif rel >= 0.22 and abs(d0 - w * 0.48) < 0.11 and 0.1 < t < 0.88:
                c = mix(c, BLADE_DIM, 0.25)        # 강철 결 한 가닥 (범철은 소박하게 — 사다리)
        if hamon and not edge and 0.12 < t < 0.86:
            hb = 0.34                              # 15차 — 하몬 물결 (A 문법과 같은 결)
            if _D15:
                hb += (0.09 * math.sin(t * 23.0 + salt * 0.37)
                       + 0.035 * math.sin(t * 67.0 + salt * 0.81))
            if hb < rel < hb + hamon_w:
                c = onbaek
            elif _D15 and hb - 0.22 < rel < hb - 0.1:
                c = mix(c, onbaek, 0.45)           # 잔 물결
        if sin_:
            if edge:
                c = mix(c, MIST, 0.55)
            if t > 0.8:
                c = mix(c, MIST, 0.5)
        if mab:
            if not edge:
                c = mix(c, WPN_OUT, 0.42)
            hit = (rel < 0.2 if groove == "center"
                   else (side == -edge_side and 0.5 < rel < 0.78) if groove == "spine"
                   else (t < 0.5 and rel < 0.3))
            if hit and 0.1 < t < 0.85:
                c = mix(BLOOD, WPN_OUT, 0.35)
        if poison and t > 0.82:
            c = POISON
        if t < 0.1 and not dark:
            c = mix(c, WPN_OUT, 0.3)
        return c
    return style


def _grip_style(salt, L, wood=False, dense=False, ink=None, grade=None, acc=None, vein=True,
                plain=False):
    """감김 — 8차: **조용한 플랫 원통** (6차 사선 랩은 체커 잡음으로 읽혔다 — 레퍼런스의
    자루는 조용한 유색 원통이고 띠가 따로 두른다). wood=맨나무 (개방 봉·범철 장병).
    ink=(기조lo, 기조hi) — 묵청 먹. 무지정 = 가죽 (범철의 실용 자루).
    grade·acc — 16차 선협 훅 전용 (플랫 경로는 안 읽는다 · _hilt_kit 이 배선)."""
    if _R16 and not plain and not (_R16 == "calm" and (wood or grade == "beomcheol")):
        # 18차 — calm 범철·맨나무 자루는 현행 경로 (가죽 손때·나무결 잔선까지 그대로:
        # 기운 0의 자루가 잔선을 잃을 근거가 없다 — 사다리 하단 "현행 유사")
        # 20차 — plain=전통 병기(_R20 파일럿)는 마법 기운 실을 걷는다 (조용한 유색 원통 · 플랫)
        return _r16_grip(salt, L, wood=wood, ink=ink, grade=grade, acc=acc,
                         calm=(_R16 == "calm"), vein=vein)
    def style(t, d0, w, side, mx, my):
        rel = d0 / w
        if wood:
            c = GRIP_MID if (side > 0 and rel < 0.5) else GRIP_DIM
            if _D15 and abs(d0 - w * 0.42) < 0.09:
                c = mix(c, GRIP_DARK, 0.3)         # 15차 — 맨나무 결 한 가닥
            return mix(c, GRIP_DARK, 0.7) if rel > 0.76 else c
        base = ink if ink else (GRIP_DIM, GRIP_MID)
        c = base[1] if (side > 0 and rel < 0.5) else base[0]
        if _D15 and rel <= 0.76:                   # 15차 — 감김 실낱 세분 (조용한 원통 위의
            u = (t * L + side * d0 * 1.4) / 1.55   #   잔선: 교차 띠 — 좌우가 반대로 슬린다)
            fu = u - math.floor(u)
            if fu < 0.2:
                c = mix(c, WPN_OUT, 0.5)           # 감김 틈 — 깊은 그늘 1px
            elif abs(fu - 0.58) < 0.1:
                c = mix(c, WPN_OUT, 0.2)           # 띠 안의 실 가닥 경계 (틈과 함께 2~3가닥)
        if rel > 0.76:
            c = mix(c, WPN_OUT, 0.55)
        return c
    return style


def _brass_style(salt, grade):
    """금 부속 — 플랫 3존 (광/몸/여문 테). 9차 A안: 놋(FIT)을 금(GOLD_FIT_*) 쪽으로
    끌어올렸다 — 물미 관·목띠·코등이·소켓이 파일럿 A 의 금 의장으로 읽힌다.
    범철은 금이 아니라 무딘 쇠다 (등급의 색 — 사다리 하단은 실용)."""
    if _R16 == "full":       # 16차-b(calm): 기능 부위 금속 질감 회생 — 현행 금 부속 그대로
        return _r16_metal(salt, grade)
    iron = grade == "beomcheol"
    def style(t, d0, w, side, mx, my):
        rel = d0 / w
        c = GOLD_FIT_HI if rel < 0.34 else (GOLD_FIT_MID if rel < 0.72 else GOLD_FIT_DIM)
        if _D15 and not iron:                      # 15차 — 금 부속의 잔선 (범철 쇠는 무광)
            if d0 > w - 0.22:
                c = mix(c, WPN_OUT, 0.3)           # 모서리 여묾 — 테가 한 번 더 잠긴다 (~1px)
            elif rel < 0.24 and 0.12 < t < 0.32 and side > 0:
                c = mix(GOLD_FIT_HI, MOON_V, 0.4)  # 광택 하이라이트 점 (빛 받는 쪽 한 점)
        if iron:
            c = mix(c, BLADE_DIM, 0.6)
        return c
    return style


def _ring_style():
    if _R16 == "full":       # calm — 등급 고리는 현행 금속 테 (기능 부위)
        return _r16_ring()
    def style(t, d0, w, side, mx, my):
        return RING_HI if d0 / w < 0.55 else RING_MID
    return style


def _tassel_style(salt, quiet=False, acc=None, plain=False):
    """수실·홍영 — 주사 플랫 2존 (밑동 밝음/끝 몸) + 여문 테. quiet=낮은 등급의 상시
    홍영은 잿빛으로 잠재운다 (Codex §3 창). acc — 16차 선협 훅 전용 (기운 자락 발광색).
    ★18차 — calm 의 quiet(범철·정련 홍영)는 현행 잿빛 그대로: 기운이 안 열린 등급의 술이
    기운 자락으로 빛날 근거가 없다 (사다리 하단 "현행 유사")."""
    if _R16 and not plain and not (_R16 == "calm" and quiet):  # 16차 선협 — 천 수실 대신 기운 자락
        a = acc or (JADE_V, JADE_VHI)              # 20차 — plain=전통 병기는 천 수실 그대로 (마법 걷음)
        if _R16 == "calm":                         # 절제 — 백광 없이 스러진다
            return _r16_wisp_style(a[0], a[1], 0.8)
        return _r16_wisp_style(a[0], a[1], 0.5 if quiet else 1.0)
    def style(t, d0, w, side, mx, my):
        c = TASSEL_HI if t < 0.3 else TASSEL
        if d0 / w > 0.62:
            c = mix(c, BLOOD, 0.35)
        if quiet:
            c = mix(c, GRIP_DIM, 0.3)
        return c
    return style


def _flat_style(lo, hi):
    """민무늬 플랫 2단 — 몸 밝고 테가 잠긴다 (문양·구름·달·파도)."""
    if _R16 == "full":                             # 16차 선협 — 심이 빛나고 테가 잠기는 기환 2톤
        def style(t, d0, w, side, mx, my):         # (calm 은 현행 그대로 — 기물 질감 회생)
            return mix(hi, MOON_V, 0.4) if d0 / w < 0.45 else mix(lo, WPN_OUT, 0.35)
        return style
    def style(t, d0, w, side, mx, my):
        return hi if d0 / w < 0.55 else lo
    return style


def _rope_style(salt):
    """삼줄 — 볏짚빛 꼬임 2톤 (개방의 매듭: 지나가는 아이도 센다). 플랫.
    15차: 꼬임 골 1px 그늘 + 마루의 실낱 빛 — 두 톤 줄무늬가 '꼰 줄'로 읽힌다."""
    def style(t, d0, w, side, mx, my):
        u = (t * 10 + side * d0 * 1.2) / 0.9
        c = ROPE if int(u) % 2 else ROPE_DIM
        if _D15:
            fu = u - math.floor(u)
            if fu < 0.14:
                c = mix(ROPE_DIM, WPN_OUT, 0.4)    # 꼬임 골 — 깊은 그늘
            elif 0.42 < fu < 0.6:
                c = mix(c, BONE, 0.3)              # 꼬임 마루 — 실낱 하이라이트
        return c
    return style


def _wisps(salt, tx, ty, grade, scale=1.0, tangent=(1.0, 0.0), series=None):
    """8차 — 기운 자락 폐지: 신물의 기운은 **떠 있는 파편**이 말한다 (B안 언어 — _head_kit 의
    방사 파편 4/5). 호출부(9계열)의 접선 배선은 남겨 둔다 — 기운 연출을 되살릴 회차의 자산이다.
    ★16차 선협 — 그 회차가 왔다: _R16 이면 칼끝 접선을 따라 흐르는 기운 자락으로 되살아난다
    (신병 2 · 마병 2+역류 1 — 부유 z 태그라 본체 extent 에 안 세인다).
    ★18차 — 유보 해소: series 배선 완료 (계열 악센트가 곧 자락 발광색 · 마병은 검붉음 유지 ·
    명병은 _ACC_OVR 상속이 _qi_accent→_ink8 경로로 문파색을 준다)."""
    if not _R16 or grade not in ("sinbyeong", "mabyeong"):
        return []
    a_lo, a_hi = _qi_accent(series, grade)
    tl = math.hypot(*tangent) or 1.0
    ux, uy = tangent[0] / tl, tangent[1] / tl
    nx, ny = -uy, ux
    calm = _R16 == "calm"
    if calm:                                       # 16차-b — 칼끝 자락도 하나만, 짧고 어둡게
        runs = ((0.0, 1.0, 2.9),) if grade == "sinbyeong" else ((0.0, 1.0, 2.6),)
    else:
        runs = ((0.0, 1.0, 3.6), (0.55, -1.0, 2.6)) if grade == "sinbyeong" \
            else ((0.0, 1.0, 3.2), (0.5, -1.0, 2.4), (-0.6, 0.7, 1.8))
    w0, wk = (0.34, 0.7) if calm else (0.42, 1.0)
    out = []
    for i, (off, sgn, ln) in enumerate(runs):
        p0 = (tx + ux * off * scale, ty + uy * off * scale + ny * sgn * 0.2)
        p1 = (p0[0] + (ux * 0.55 + nx * sgn * 0.4) * ln * scale,
              p0[1] + (uy * 0.55 + ny * sgn * 0.4) * ln * scale)
        p2 = (p0[0] + (ux + nx * sgn * 0.85) * ln * scale,
              p0[1] + (uy + ny * sgn * 0.85) * ln * scale)
        out.append(_pn(_vd(_stroke(_bez(p0, p1, p2, 8),
                                   lambda t: max(0.13, w0 * (1.0 - 0.75 * t)),
                                   _r16_wisp_style(a_lo, a_hi, wk)),
                           _vshard(i, p0[0], p0[1])), "기운자락"))
    return out


# ═══ V2-W 6차 — 의장(儀裝) 신물 팔레트 (레퍼런스 스크린샷 2장 실측 · 팔레트 해방 등록) ═══
# 설계 언어: [아주 어두운 유색 기조(자루)] + [뼈백/금 장식 구조] + [선명한 악센트 1색].
# 플랫 2~3단 램프 (그라데이션 아님) · 장식이 주인공, 날은 조연 · 파편은 본체와 떨어져 떠 있다.
BONE = (234, 222, 198, 255)
BONE_MID = (198, 180, 150, 255)
BONE_DK = (142, 122, 96, 255)
DK_PLUM = (44, 32, 54, 255)          # 흑자주 — 해방 대역 (hue_free 등록)
DK_PLUM_HI = (110, 80, 126, 255)
DK_GREEN = (26, 50, 40, 255)         # 심녹
DK_GREEN_HI = (64, 116, 86, 255)
DK_SLATE = (30, 30, 38, 255)         # 먹흑
DK_SLATE_HI = (78, 76, 92, 255)
GOLD_V = (204, 166, 96, 255)
GOLD_VHI = (238, 208, 132, 255)
JADE_V = (112, 198, 122, 255)        # 선명 옥 — 파편의 초록
JADE_VHI = (178, 232, 168, 255)
CRIM_V = (198, 46, 42, 255)          # 진홍 — 호마의 화염
CRIM_VHI = (240, 96, 72, 255)
AMBER_V = (226, 152, 62, 255)
AMBER_VHI = (246, 200, 122, 255)
PGRN_V = (150, 212, 92, 255)         # 독록
CYANJ_V = (92, 196, 188, 255)        # 청옥
CYANJ_VHI = (162, 230, 222, 255)
MOON_V = (222, 232, 240, 255)
COPPER_V = (206, 116, 66, 255)       # 적동 — 부(斧)의 악센트
COPPER_VHI = (238, 166, 112, 255)
VIOL_V = (138, 96, 186, 255)         # 남자주 — 구(鉤)의 악센트 (해방 대역)
VIOL_VHI = (184, 150, 222, 255)
MHWA_V = mix(CRIM_VHI, PLUM_HI, 0.5)          # 매화홍 — 화산의 악센트 (B안 문양)
MHWA_VHI = mix(MHWA_V, MOON_V, 0.35)
_ORN_STEEL = (mix(BONE_DK, BLADE_DIM, 0.28), mix(BONE_MID, BONE, 0.3))  # 골강 — 8차(B안) 기록.
                                            # 9차부터 의장은 순 뼈백(BONE_DK·BONE)이다 (A안 문법)
_ORN_BONE = (BONE_DK, BONE)                 # 9차 A안 — 뼈백 필리그리 (레퍼런스 옥장의 흰 의장)
GOLD_FIT_HI = mix(FIT_HI, GOLD_VHI, 0.65)   # 9차 A안 — 금 부속(물미 관·목띠·코등이·소켓 띠):
GOLD_FIT_MID = mix(FIT_MID, GOLD_V, 0.65)   #   놋(FIT)을 금(GOLD_V) 쪽으로 끌어올린 플랫 3존.
GOLD_FIT_DIM = mix(FIT_DIM, mix(GOLD_V, WPN_OUT, 0.35), 0.65)   # 파일럿 A 의 금 비중 상향
# 9차 (A안 확정 — 사용자 결정 번복: 8차 B 전파 뒤 렌더 실사에서 "A가 더 좋아 보입니다"):
# 기조 = **계열별 어두운 유색 자루** (6차 색표 부활·조정 — 범철만 실용 가죽) + 의장 = 순 뼈백
# 필리그리 + 금 부속, 계열 변별 = 악센트 1색(8차 유지 — 보주 광점·파편·물미 구슬·인선이 문다).
# 【잠정 — yml 색표】 계열 → (자루lo, 자루hi · 의장lo, 의장hi · 악센트lo, 악센트hi)
SERIES_INK = {
    "sword": (DK_PLUM, DK_PLUM_HI) + _ORN_BONE + (JADE_V, JADE_VHI),             # 검 — 흑자주·옥
    "dao": (DK_SLATE, DK_SLATE_HI) + _ORN_BONE + (CRIM_V, CRIM_VHI),             # 도 — 먹흑·진홍
    "spear": (DK_GREEN, DK_GREEN_HI) + _ORN_BONE + (AMBER_V, AMBER_VHI),         # 창 — 심녹·호박
    "gauntlet": (DK_SLATE, DK_SLATE_HI) + _ORN_BONE + (GOLD_V, GOLD_VHI),        # 권갑 — 먹흑·금
    "dagger": (DK_PLUM, DK_PLUM_HI) + _ORN_BONE + (PGRN_V, mix(PGRN_V, MOON_V, 0.4)),  # 비수 — 흑자주·독록
    "bu": (DK_SLATE, DK_SLATE_HI) + _ORN_BONE + (COPPER_V, COPPER_VHI),          # 부 — 먹흑·적동
    "gyeom": (DK_GREEN, DK_GREEN_HI) + _ORN_BONE + (CYANJ_V, CYANJ_VHI),         # 겸 — 심녹·청옥
    "wolasan": (DK_PLUM, DK_PLUM_HI) + _ORN_BONE + (MOON_V, MOON_V),             # 월아산 — 흑자주·백월
    "gu": (DK_PLUM, DK_PLUM_HI) + _ORN_BONE + (VIOL_V, VIOL_VHI),                # 구 — 흑자주·남자주
}
_ORN = {"beomcheol": 0, "jeongryeon": 1, "bobyeong": 2, "sinbyeong": 3, "mabyeong": 4}
# 명병 — 문파 문양이 의장 테마: 악센트를 문파 색으로 상속 (화산 매화홍 · 무당 청옥 · 소림 금 ·
# 당가 독록 · 팽가 진홍 · 남궁 금 · 점창 은월 · 종남 호박 · 곤륜 백운 · 청성 송옥 · 해남 파도 ·
# 개방 볏짚). 【잠정 — yml 색표】
_SECT_ACCENT = {
    "hwasan": (MHWA_V, MHWA_VHI), "mudang": (CYANJ_V, CYANJ_VHI),
    "sorimsa": (GOLD_V, GOLD_VHI), "dangga": (PGRN_V, mix(PGRN_V, MOON_V, 0.4)),
    "paengga": (CRIM_V, CRIM_VHI), "namgung": (GOLD_V, GOLD_VHI),
    "jeomchang": (MOON_V, MOON_V), "jongnam": (AMBER_V, AMBER_VHI),
    "gonryun": (MOON_V, MOON_V), "cheongseong": (mix(JADE_V, MOON_V, 0.45), MOON_V),
    "haenam": (CYANJ_V, mix(CYANJ_VHI, MOON_V, 0.4)), "gaebang": (ROPE, ROPE),
}
_ACC_OVR = [None]        # 명병 악센트 상속 컨텍스트 — _myeong_spec 가 걸고 반드시 푼다 (결정론)
_PETAL_OVR = [False]     # 화산 — 파편이 매화 꽃잎이 된다
_MOTIF_OVR = [None]      # 18차 — 명병 문양의 기맥 번역 컨텍스트 (_qi_vein 이 읽는다 · 반드시 푼다)
# 명병 문양 → 기맥 파형 번역 (18차 · 정본 §4 — 색·빛만: 문양이 기맥 **무늬** 속에 녹는다.
# 실루엣 문양(매화잎·태극 stud·파도·답운·매듭)은 부위로 그대로 남고, 기맥 오버레이의
# 사행 파형·기혈 매듭만 문파의 획을 탄다. 전부 한 획(R3) 안이다):
#   매화(화산) = 기혈이 꽃술 낱알 매듭으로 맺힌다 (_stamen15 낱알 문법의 기맥판)
#   태극(무당) = 느린 S 두 굽이 — 받아 되감는 원 / 파도(해남) = 마루가 앞으로 쏠린 물결
#   구름(곤륜) = 진폭이 뭉게뭉게 부풀고 잦아드는 감김 / 새끼줄(개방) = 두 가닥 맞꼬임
_MYEONG_MOTIF = {"hwasan": "매화", "mudang": "태극", "haenam": "파도",
                 "gonryun": "구름", "gaebang": "새끼줄"}

# ═══ V2-W 16차 — 선협(仙俠) 기환풍 파일럿 【2026-07-17 · 사용자: 플랫 램프 음영 시안
# (A~D 문법 교체안)을 접고 "아예 다른 컨셉" — 선협 기환풍 선택】 ═══════════════════════
# 문법 5조: ① 몸 = 어두운 옥/먹 심 (기물은 먹쇠) ② 발광 윤곽선 — 실루엣 가장자리의
# **불투명 발광 램프** (축 ㉓ 알파 규율: 알파는 현행 _compose 경로 그대로 — 발광은 4차의
# "밝은 그라데이션 착시" 문법, 단수 램프) ③ 흐르는 기맥 — 날 몸을 사행하는 발광 실선 +
# 마디 광점 (위상 = salt · 결정론, 마병은 꺾인 균열 기맥) ④ 물리 장식(뼈백 필리그리·금
# 부속·주사 수실·수정 파편) 제거 — 기환 고리·내단(內丹)·기운 자락·부유 광점으로 대체
# (코등이·물미 관 등 기능 부위는 제거가 아니라 먹쇠+발광 심선으로 기환 번역) ⑤ 사다리 =
# **발광 강도·기맥 밀도** (_QI_GRADE — 범철 무광 먹병 → 신병 최광 · 마병 = 검붉은 발광).
# 계열/문파 악센트 1색이 곧 발광색이다 (색 정체성의 번역 — 검 옥 · 도 진홍 · 화산 매화홍).
# ★ 팩 배선 이력: 파일럿은 스크래치 렌더로만 봤다 (False = 훅 전부 잠듦 · 산출물 비트 불변).
#   15차 잔선 층(_D15)은 이 문법이 통째로 대체한다 (공존 아님 — 잔선은 플랫 램프 위의
#   양념이었고, 선협 경로는 조기 반환으로 자연히 지나친다. 기물 회생 부위(calm ⑦)에선
#   현행 경로가 살아 있으므로 잔선도 그 부위에선 산다).
# ★16차-b 【사용자 판정: "조정 후 재시안으로 갑시다 — 너무 과합니다"】 — 선협 방향 유지 +
# 강도 대폭 절제: _R16 은 3값이다. False=현행 / "full"=16차 원판(과함 — 대조 기록용) /
# "calm"=16차-b 절제판. 절제 7조: ① 무기가 먼저 — 몸체는 현행 A안 재질감(어두운 계열 심 +
# 뼈백 사면), 발광이 몸을 삼키지 않는다 ② 발광 윤곽 = 인선 쪽 위주 가늘게 (반대편 0.12배) ·
# 백광 심 억제 (칼끝 한 점만) ③ 기맥 1~2가닥 · 저대비 (몸색에 절반 혼합) · 마디 광점 절반
# (문턱 0.86→0.95) ④ 마병 균열 = 느린 긴 실선 2가닥 · 몸은 조용한 먹 ⑤ 기환 고리 r×0.78 ·
# 폭/밝기 축소 · 자락 1 · 광점 2 ⑥ 사다리: 범철~보병 사실상 현행 유사 (calm g 0/0.15/0.3)
# ⑦ 기능 부위 금속 질감 회생 — 코등이·물미·등급 고리·상감·물미 구슬은 **현행 경로 그대로**
# (calm 은 brass/ring/flat/gem/orb 훅이 잠긴다 — 기물 전부 발광 번역은 과했다).
# ★18차 【2026-07-17 사용자 채택: 17차 정렬판(calm+ALIGN) 파일럿 실사 — "정렬판이 훨씬 좋은
#   느낌"】 — "calm"+정렬을 **기본 팩 배선**으로 전파한다 (9계열×5등급 45 + 명병 12 = 57).
#   16차 유보 배선 해소: ① _wisps·수실/홍영 acc 의 계열 악센트 배선 (옥 폴백 폐지 —
#   SERIES_INK 8차 색표 승계: 검=옥·도=진홍·창=호박·권갑=금·비수=독록·부=적동·겸=청옥·
#   월아산=백월·구=남자주 · 명병은 _SECT_ACCENT 상속) ② 명병 문양의 기맥 번역
#   (_MYEONG_MOTIF — 매화·태극·파도·구름·새끼줄이 _qi_vein 파형·기혈 매듭에 녹는다 ·
#   한 획 규칙 R3 준수) ③ 전 계열 기맥 오버레이 배선 (파일럿 검·도 → 9계열+명병 —
#   스타일 속 무늬(vein=True)는 전 호출부에서 잠근다). "full" 은 대조 기록으로만 남는다.
_R16 = "calm"
# ═══ V2-W 17차(정본화) — 설계 근거 정본 + 연속성 재구현 【2026-07-17 · 사용자:
# "각 무기별 명확한 디자인 이유를 정리해야 — 이유가 없는 디자인을 한 것 같습니다.
#  손잡이부터 날까지 이 부분이 무엇을 표현하며 입체 블록이 왜 이렇게 박히는지 명확히,
#  연속성 있는 형태로"】 ═══════════════════════════════════════════════════════
# 정본 문서: docs/design/weapon_anatomy_canon.md — 부위 이름·복셀 배치 근거·연속성 규칙이
# 전부 거기 있고, 코드는 셰이프마다 정본 부위명 태그(_pn)를 달아 1:1 로 대응한다.
# 기계 집행: _continuity_check (R1 본체 연결 · R2 단면 단차 · R3 기맥 연속 · R4 부위 순서)
# — _paint_model 이 매 자루 부른다. 위반이면 빌드가 죽는다 (자기시험 _continuity_selftest).
# _R16_ALIGN: True = 17차 정렬판 (calm 파일럿 재구현 — 기맥 오버레이 한 획 · 광점 대칭 짝 ·
# 결정 실루엣 돌기 제거) / False = 16차-b 원판 재현 (대조 기록용 — 스크래치 렌더 전용).
_R16_ALIGN = True
# ═══ V2-W 19차 — 종합 리디자인 + 오라 분리 파일럿 (검 신병 · 도 마병) 【2026-07-17】 ═══
# 사용자 지시(18차 배포 후, 원문): ① "무기 디자인의 기본 베이스를 수정하여 디자인적 개선을
# 더 취하고 싶다" → 종합 리디자인 (기하·부위·재질을 통째로 새 기준). ② "오라(기운)가 날과
# 붙어있어 느낌이 덜하다 — 조금 떨어뜨리면 좋겠다."
# ★_R19 = False → 훅 전부 잠듦 (팩 배선은 _R16="calm" 그대로 · 산출물 비트 불변).
#   스크래치 드라이브(v19_drive.py)만 True 로 걸어 2자루를 스크래치에 굽는다 (전파는 20차).
# 오라 분리 = 병기(본체) + 감도는 기운(오라) 두 층의 **시각 분리** (정본 §4 float 계약 준수):
#   ① 발광 윤곽 = 날 실루엣에 붙은 림 → 어두운 틈(_R19_GAP)을 두고 바깥에 뜬 헤일로.
#      양날은 ±z(_R19_HALO_Z)로 갈라 떠서 각도가 바뀌면 오라가 날에서 분리돼 보인다.
#   ② 기맥 = 날 위 흐름은 유지(_qi_vein 오버레이 · 정본 R3), 그 바깥에 뜬 기운 층을 더한다.
#   ③ 기환·자락·광점 = 이미 float 이나 더 멀리·앞으로 띄운다 (날에서 떨어진 고리·잔광).
# 종합 베이스 = 선협 유지 + 해부 정본 준수: 실루엣 더 우아하게(검=곧고 기품·도=대담한 휨) ·
#   부위 형태 세련화(물미관·자루·목띠·코등이/기환·날 3층 유지) · 재질 정교화(어두운 옥/먹 심
#   + 뼈백 사면 3단 + 벼린 인선 한 줄 — 발광 bloom 은 헤일로로 떼어냈다). 연속성 R1~R4 준수.
_R19 = False
_R19_GAP = 1.4          # 날 실루엣 ↔ 오라 헤일로 사이 어두운 틈 (모델 u 【잠정】)
_R19_HALO_Z = 0.9       # 헤일로 z 부양 (±로 갈라 각도서 분리 · 정본 §4 float — extent 불침)
# ★19차-b 【사용자 판정: "1겹으로 줄이고, 너무 오라가 많아서 난잡해 보임"】 — 오라 감량.
# 아래 둘은 스크래치 대조 토글이다 (기본값 = 19b 정갈 · 드라이브가 True 로 걸어 19 난잡 재현):
_R19_OUTER = False      # 날 바깥 "뜬 기운" 둘째 겹 (True=19 난잡 / False=19b 헤일로 1겹만)
_R19_WISP = False       # 기환/칼끝 자락 줄기 (True=19 / False=19b 잔광은 광점만)
# ═══ V2-W 19차-c — 오라 근거 정본화 + 발광(빛) 재현 【2026-07-17 · 사용자】 ═══════════
# 사용자 지시(19b 배포 후, 원문 3): ① "오라를 이렇게 표현한 이유에 대해 분석 후 자체 검토 —
#   이유 없이 오라 양을 늘린 것처럼 보임." ② "오라를 빛이 발광하는 느낌으로 — 현재는 부산물처럼
#   느껴짐." ③ "오로라처럼 빛이 일렁거리는 느낌으로."
# 근거 정본: docs/design/weapon_anatomy_canon.md §4 (오라 근거 정본 — 요소별 세계관 근거·위치
#   도출·양 비례) + §4-발광(빛의 문법: 블룸 falloff·핫 코어) + §4-일렁임(오로라 위상 설계).
# 세 수선 (전부 _R19 게이트 · 팩 비트 불변):
#   ① 부유 광점 = **기맥 경혈(氣穴) 잔광** — 기환 축 임의 오프셋(r+2.7)에서 → 기맥 경로 위의
#      마디(node>0.93 = _vein_style 기혈)로 이전 (_meridian_motes). 위치가 해부에서 도출된다.
#   ② 오라 = **빛(기의 발광)** — 단수 플랫 램프(_qi_glow · 부산물처럼 읽힘)를 오라 한정
#      그라데이션 예외로: 핫 코어(흰-핫 과노출 심) → 채도 악센트 → 어둠 falloff (_bloom_color).
#      ★무기 몸/날은 픽셀아트 플랫 불변 — 그라데이션은 오라 층에만. 축 ㉓ 는 그라데이션을
#      안 잰다 (평균채도·알파만) — 오라도 채도·알파 예산 안이라 20차 전파 가능 (실측은 §5).
#   ③ 일렁임(오로라) = 정지 텍스처론 불가 — 애니 텍스처(세로 스트립+.mcmeta)가 유일한 길.
#      실현 판정 = **조건부 가능** (§5·정본 §4-일렁임): 오라를 별도 텍스처층으로 분리 + mcmeta
#      배선 + 축 ㉓/⑯ 스트립 등록 확장이 선행 (20차 소관). 이 회차는 위상 설계만 심어(_R19_PHASE)
#      프레임을 스크래치에 굽는다 (일렁임이 결정론·가독임을 증명 — 팩엔 정지 블룸만 후보).
_R19_BLOOM = True       # 오라 발광 블룸 (True=정본 빛/핫코어 그라데이션 · False=19b 플랫 부산물 대조)
_R19_MOTE_MERIDIAN = True  # 광점 자리 (True=기맥 경혈 정본 · False=19b 기환 축 r+2.7 산포 대조)
_R19_PHASE = None       # 애니 위상 (None=정지 · 드라이브가 0..1 프레임 위상으로 걸어 일렁임 렌더)
_QI_GRADE = {   # 등급 → (발광 강도 g 0..1, 기맥 수, 기운 자락 수, 부유 광점 수) 【잠정】
    "beomcheol": (0.0, 0, 0, 0),
    "jeongryeon": (0.35, 1, 0, 0),
    "bobyeong": (0.6, 1, 1, 2),
    "sinbyeong": (1.0, 2, 3, 4),
    "mabyeong": (1.0, 3, 2, 5),
}
_QI_GRADE_CALM = {   # 16차-b — 사다리 절제 (범철~보병 사실상 현행 유사) 【잠정】
    "beomcheol": (0.0, 0, 0, 0),
    "jeongryeon": (0.15, 0, 0, 0),
    "bobyeong": (0.3, 1, 0, 0),
    "sinbyeong": (0.75, 2, 1, 2),
    "mabyeong": (0.8, 2, 1, 2),
}
_QI_INK = (13, 19, 18, 255)          # 먹 심 — 어두운 옥먹의 골
_QI_BODY = (31, 48, 43, 255)         # 어두운 옥 사면
_QI_METAL = ((19, 21, 25, 255), (48, 54, 60, 255))   # 기물(관·띠·코등이) — 먹쇠 2톤

# ═══ V2-W 20차 — 전통 무협 병기 + 표면 광택 일렁임 (스펙큘러 스윕) 【2026-07-17 · 사용자】 ═══
# 사용자 방향 전환(19c 위에서 재조준): ① "판타지쪽을 좀 빼고 전통 무협 무기로 구현하되 무기의
# 특색을 부여" ② "등급 및 무기마다 일렁임을 추가하여 디자인적 측면 강화."
# → 선협 마법 오라(붙은 발광 헤일로·기환 고리·기맥 발광 그물·부유 광점)는 걷어내고, 일렁임을
#   **"잘 벼린 강철·옥 표면에 빛이 흐르는 광택 일렁임"**(스펙큘러 스윕)으로 재해석 — 그것이
#   곧 무기의 특색. 판타지 오라 → 전통 병기의 살아있는 광택. 담금선·하몬 같은 진짜 강철 문양은
#   유지(그건 전통이다). 고등급 광택이 살짝 색(신병 옥빛·마병 검붉음)을 띠는 정도까지 전통+α.
# ★애니 실현(19c "조건부 가능"의 선행 3건을 이번에 착수):
#   ⓐ **원소 0 추가로 실현** — 전체 페인트 시트("#1")를 세로 프레임 스트립(128×128·N)으로 굽는다.
#      각 프레임 = 같은 무기 + 스윕만 다른 위치. 몸 픽셀은 프레임마다 동일 → 몸은 정지, 광택만
#      흐른다. 모델 UV 는 0~16(프레임 0)을 물고 MC 가 프레임을 순환 — **UV/원소 재배선 불요**
#      (19c 가 예상한 "별도 텍스처층 분리"는 불필요: 몸을 매 프레임 같은 자리에 다시 그리면 된다).
#   ⓑ **`.mcmeta` 를 굽는다** (세로 스트립 · campfire/kelp 전례로 신규 텍스처 mcmeta 는 계약 안).
#   ⓒ 감사 축 ㉓/⑯ 을 스윕 글롭의 세로 스트립(N×128) 허용으로 확장 (자기시험 갱신).
# ★_R20 = False → 훅 전부 잠듦 (팩 배선 현행 · 산출물 비트 불변). 파일럿 배선은 _R20_PILOTS.
_R20 = True
_R20_FRAMES = 16        # 스트립 프레임 수 (mcmeta interpolate 로 부드럽게 · 잠정)
_R20_FRAMETIME = 2      # 프레임당 틱 (mcmeta frametime · 잠정)
_R20_PHASE = None       # 렌더 위상 (None=정지 대표 프레임 · 스트립 굽기가 0..1 로 건다)
_R20_PILOTS = frozenset({("sword", "beomcheol"), ("sword", "sinbyeong"),
                         ("sword", "mabyeong"), ("dao", "sinbyeong")})
_R20_MYEONG_PILOTS = frozenset({"hwasan"})
# 스윕 서명 — 무기마다 광택이 흐르는 방향 (계열 특색) · 등급마다 세기 (사다리):
_R20_SWEEP = {   # 등급 → (스윕 세기 0..1, 색 tint 키) — 범철 무광 → 신병 또렷 → 마병 검붉게 위압
    "beomcheol": (0.12, "steel"), "jeongryeon": (0.32, "steel"),
    "bobyeong": (0.55, "steel"), "sinbyeong": (1.0, "jade"), "mabyeong": (0.8, "blood"),
}
_R20_SWEEP_TINT = {"steel": None, "jade": (170, 235, 205, 255), "blood": (235, 90, 70, 255)}

# ═══ V2-W 21차 — 만화(애니)풍 리스타일 파일럿 (구조·일렁임 유지 · 렌더 스타일만 교체) ═══
# 사용자 지시(20차 배포 후): "너무 중국쪽 무기로 되어버린 거 같아. 조금은 만화 계열로 변경해도
# 좋지 않을까?" → 20차 전통 병기가 정통 중국풍으로 너무 갔다. **그림 문법만** 만화(애니)풍으로:
#   ① 셀 셰이딩 — 사실적 3단 사면 → 또렷한 2~3톤 셀(명/암 경계 딱 떨어짐) + 강한 외곽선(어두운
#      테 1px). ② 대담·아이콘화 실루엣 — 코등이를 대담하게 (볼록 단면·해부 부위는 유지).
#   ③ 채도·생동 — 살짝 채도 올린 생생한 색 (강철 청백 하이라이트·악센트 선명 · 평균채도 ≤85 안).
#   ④ 하이라이트 스팟 — 만화 금속의 별표 반짝 (날에 또렷한 흰 반짝 점/띠, 일렁임 스윕과 어울리게).
# ★유지: 볼록 단면 날·해부 정본·부위 근거·표면 광택 일렁임(_R20 스윕·애니 스트립·mcmeta) 전부.
#   일렁임은 21에서도 유지 — 만화 셀 위에 스펙큘러 스윕이 그대로 흐른다 (_sweep_mix 계속 호출).
# ★_R21 = False → 팩 배선 현행(20차) 유지 · 산출물 비트 불변. 파일럿만 _R21_PILOTS.
#   등급 사다리 유지: 범철=수수 → 신병=화려 청백 셀 → 마병=검붉은 셀 · 명병=문파색 셀.
_R21 = True
_R21_PHASE = None       # 기 흐름 애니 위상 (None=정지 대표 프레임 · 스트립 굽기가 0..1 로 건다)
_R21_PILOTS = frozenset({("sword", "beomcheol"), ("sword", "sinbyeong"),
                         ("sword", "mabyeong"), ("dao", "sinbyeong")})
_R21_MYEONG_PILOTS = frozenset({"hwasan"})

# ═══ V2-W 25차 — 깨끗한 재건축 파일럿 (clean hand-placed · 검·도 신병) 【2026-07-19】 ═══
# 배경: 무기 대장정 내내 사용자 "무기가 뭉툭·이상하다" 반복 · 인게임에서 우리 복셀 무기
#   (_voxelize — 두꺼운 볼록 날·팔각·수실·기 껍질 누적 복잡함)를 크게 키우면 어수선함 객관 확인.
#   반면 손으로 지은 ref_blade(청뢰검)는 우아. ★사용자 결정: **청뢰검의 깨끗한 손 배치 방식을
#   우리 무기 로스터에 녹인다.** clean_weapons.py 가 refblade_forge 문법을 계열·등급으로 일반화.
# 배선: weapon_model_3d 가 _R25_PILOTS 두 자루에서만 clean 경로로 갈아탄다 (부위별 소수 cuboid
#   손 배치 · _voxelize 미경유). 나머지 45+12 무기 무접촉 · 아이콘(GUI 2D) 불변 · 3D 만 교체.
#   모델은 정지 깨끗한 병기 — 날 위 번개 애니 제거(파티클 트랙 소관). 옛 R21 스트립 .mcmeta 제거.
# ★_R25 = False → clean 경로 잠듦 (복셀 현행 유지). 파일럿만 _R25_PILOTS.
_R25 = True
_R25_PILOTS = frozenset({("sword", "sinbyeong"), ("dao", "sinbyeong")})
# ★기 복셀 껍질 (사용자: "날이 일렁이는 게 아닌, 검 주변의 복셀을 생성하여 애니메이션으로
#   기(氣)가 흐르듯이" · 21b 정밀: "기가 검을 감쌀 때 연속적인 나선형이 아닌 **포인트만 조금씩
#   주는 형태로**") — 무기를 두르는 **띄엄띄엄 떠 있는 기 포인트**(짧은 발광 입자)로 재해석.
#   연속 리본/줄기(선) 폐지 — 점점이. 날 길이축을 따라 드문드문, 각 포인트를 **축 둘레 다른
#   각도**(앞·뒤·좌·우 대각 순환)+**서로 다른 z**(코플래너 아님)에 둬 3D 로 감싼다. 텍스처
#   애니로 자루→날끝 **차례로 켜지며**(순차 점등) 흐르는 착시 (연속 흐름 아님). 블룸 핫코어 발광.
#   float 역할 — 본체 extent 불침(display 계약) · 본체(볼록 날·봉 자루)와 분리된 껍질. 양 절제.
# 등급 사다리 = 기 포인트 수 — 범철 0(순수 병기) → 신병 5 → 마병 검붉은 5 (난잡 금지).
_R21_QI_SHELL = {
    "beomcheol":  0,   # 순수 병기 — 기 포인트 없음
    "jeongryeon": 2,
    "bobyeong":   3,
    "sinbyeong":  5,   # 또렷 5점 (3~5 · 절제 — 19b 난잡 교훈)
    "mabyeong":   5,   # 검붉은 5점
}
_R21_SHELL_GAP = 1.9    # 날 실루엣 ↔ 기 포인트 사이 틈 (본체와 분리된 껍질 · 모델 u 【잠정】)
_R21_SHELL_Z = 2.6      # 기 포인트 z 부양 반경 (±로 갈라 떠 3D 로 무기를 두른다 · 코플래너 아님)

# ★21차-d — 검을 휘감는 3D 번개 껍질 (사용자 인게임 실측 3건 수정):
#   ① 속도 — frametime 21c 1틱→21d 3틱→**21e 2틱** (1 빠름·3 느림 사이 · 사용자 실측).
#   ② 형태 (21e 재수정 — 사용자: "굵은 균일 Z 는 아이콘 같다 · 가늘고 불규칙하고 갈라지게") →
#   **진짜 번개 문법**: 가는 주 채널(흰-핫 1px 코어 + 색 글로우 · `_R21_BOLT_W` · 두꺼운 리본 아님) ·
#   세그 각도·길이 **불규칙**(결정론 해시 `_lcg` · 균일 45° Z 금지 · 대체로 한 방향 흐르되 잔 꺾임 랜덤)
#   · **분기 fork 1~3개**가 갈라져 짧게 taper(점점 사라짐) — 전기 아크 = 얇고 강렬.
#   ③★핵심 — 번개가 **날 표면**에 흐르는 게 아니라 **검 외부에서 기(氣)를 대신해 날을 휘감아야**
#   한다. 그래서 21c 의 `_blade_lightning` 날 표면 오버레이는 **폐지**하고, 번개를 **기 껍질
#   float**(21b `_qi_shell` 구조)에 실어 **뇌전 볼트 조각**으로 검을 감싼다: 축 둘레 helix(각도가
#   길이축 따라 돈다)+다중 z(앞·뒤·좌·우 · 코플래너 아님) → 어느 각도서 봐도 번개가 감싸는 게
#   보인다. 프레임마다 볼트가 점멸(strobe) → 감은 번개가 살아 튀는 착시 (`_bake_r21_strip` 재사용).
#   ★기하 제약: float 볼트 케이지는 **위상 None 프레임에 전부 불투명**하게 구워져(복셀 케이지)
#   그 위에서 프레임 애니가 점멸한다 — 꺼진 프레임의 투명은 텍스처 컷아웃이 지운다(기하 불변 ·
#   UV/원소 재배선 0). 결정론 — 위상·salt 사인 (난수 0).
# ★22차 — 레퍼런스식 번개 (사용자 레퍼런스 3프레임 · 조율자 "적은 게 이긴다"):
#   21e 는 검 전체를 3D 뇌전 케이지로 감싸 **과함·난잡**이었다. 레퍼런스는 정반대 —
#   **작은 평면 번개 스프라이트 2~3개를 키 포인트(칼끝·코등이)에만** 얇게. 검 전체 감싸기 아님.
#   그래서 `_R21_FX="bolt"` 를 **작은 평면 스프라이트**로 재정의하고, 옛 21e 3D 케이지는
#   `_R21_FX="cage"` 로 보존한다 (`_qi_cage`). 프레임마다 다른 작은 볼트로 번쩍(치지직 · frametime 2).
# _R21_FX 스위치: "bolt"=레퍼런스식 작은 평면 스프라이트(22차 · 칼끝·코등이 · 기본) ·
#   "cage"=옛 21e 3D 뇌전 케이지(검 전체 휘감음 · 보존) · "qi"=옛 기 포인트(21b) ·
#   "both"=기 포인트 + 22차 스프라이트.
_R21_FX = "bolt"      # 22차 — 레퍼런스식 작은 번개 스프라이트(칼끝·코등이)
_R21_FRAMES = 20        # 애니 프레임 수 (넉넉히 — 치지직 다양성 · mcmeta interpolate 없이 딱딱 튄다)
_R21_FRAMETIME = 2      # 프레임당 2틱 (21e — 1틱 빠름·3틱 느림 사이 · 사용자 실측)
_R21_BOLT = {           # 등급 → (뇌전 세기 0..1, 스프라이트 수) — 22차는 **작게**: 케이지 6~7 → 스프라이트 2~4
    "beomcheol":  (0.0, 0),   # 순수 병기 — 뇌전 없음
    "jeongryeon": (0.45, 2),  # 약한 스파크 (칼끝 1 · 코등이 1)
    "bobyeong":   (0.6, 2),
    "sinbyeong":  (1.0, 3),   # 또렷 — 칼끝 2 + 코등이 1 (레퍼런스 톤)
    "mabyeong":   (1.0, 4),   # 검붉은 격렬 (칼끝 2 + 코등이 2)
}
_R21_CAGE = {           # (보존 · "cage") 21e 3D 케이지 조각 수 — 검 전체 휘감음 (원본 재현용)
    "beomcheol":  (0.0, 0), "jeongryeon": (0.45, 3), "bobyeong": (0.7, 4),
    "sinbyeong":  (1.0, 6), "mabyeong": (1.0, 7),
}
_R21_BOLT_LEN = 3.8     # (cage) 주 채널 길이 (휘감기 방향 흐름 · 모델 u 【잠정】)
_R21_BOLT_W = 0.26      # 주 채널 반폭 (가는 전기 아크 · 흰-핫 1px 코어 + 색 글로우 · 두꺼운 리본 금지)
# ★22차 작은 평면 스프라이트 파라미터 (레퍼런스 — 칼끝·코등이에만 얇게):
_R21_SPARK_ANCH = (0.95, 0.16)   # 두 키포인트 — 칼끝(0.95) · 코등이 곁 날밑(0.16 · 대담 날개 코등이
#   를 지난 날 밑) · blade-length 분수. 코등이 쪽은 넓은 날개에 가려지지 않게 날 밖 빈 공간으로 뜬다.
_R21_SPARK_LEN  = 2.1            # 작은 스프라이트 채널 길이 (케이지 3.8보다 짧다 · 모델 u 【잠정】)
_R21_SPARK_GAP  = 0.7            # 날 반폭 밖 기본 틈 (칼끝 — 좁은 날 끝 곁 빈 공간에 바짝)
_R21_SPARK_GAP_GUARD = 1.9       # 코등이 쪽 추가 밀어냄 — 대담 날개 밖 빈 공간으로 (본체 우선 회피)
_R21_SPARK_Z    = 0.9            # 얕은 ±z (평면 스프라이트 — 케이지 2.6 아님 · 거의 검 평면에 눕는다)
# ★22차 뇌전 계열색 (레퍼런스 톤 · 스프라이트 전용) — 검=옥/청록(레퍼런스 teal, 만화 셀 청백 검신과
#   한 가족). 도=진홍·명병=문파색·마병=검붉음은 종전 경로(`_ink8`/`_ACC_OVR`/BLOOD)가 그대로 쥔다.
#   ★스프라이트(`_qi_bolts`)에만 적용 — 옛 21e 케이지(`_qi_cage`)는 원본 재현 위해 손대지 않는다.
_R21_BOLT_ACCENT = {
    "sword": ((64, 186, 200, 255), (152, 234, 242, 255)),   # 옥/청록 (레퍼런스 teal 지그재그)
}


def _r21_anim(grade):
    """이 등급이 애니 스트립(전격·기)을 가지는가 (범철=정지 시트)."""
    qi = _R21_FX in ("qi", "both") and _R21_QI_SHELL.get(grade, 0) > 0
    bolt = _R21_FX in ("bolt", "cage", "both") and _R21_BOLT.get(grade, (0, 0))[0] > 0
    return qi or bolt


def _bolt_fire(bi, seed):
    """이 볼트 조각이 지금 프레임(_R21_PHASE)에 켜지는가 (딱딱 점멸 = 치지직). ★위상 None(기하
    굽는 프레임)엔 항상 켜 둔다 — float 볼트 케이지 전부가 복셀로 만들어져야 한다 (기하 불변)."""
    ph = _R21_PHASE
    if ph is None:
        return True
    fi = ph * _R21_FRAMES
    # 볼트마다 다른 위상(bi·seed) → 프레임마다 켜진 조각이 축 둘레를 옮겨 다닌다 (감은 번개가 산다).
    # 늘 일부(2~4)만 켜져 전역 명멸이 아닌 이동 치지직 — 잠정 계수.
    return math.sin(fi * 0.75 + bi * 2.4 + seed * 0.4) > 0.0


_R21_ALLOW = [False]    # 파일럿 컨텍스트 — weapon/myeong_model_3d 가 이 자루가 R21 파일럿인지
#   걸고 반드시 푼다 (결정론). _spec_sword/_dao 는 _R21 and _R21_ALLOW[0] 일 때만 만화 경로.
#   ("sword","sinbyeong") 파일럿이 명병 비-화산 sinbyeong 검까지 만화로 물들지 않게 하는 관문.
# 등급 → (셀 채도 sat 0..1, 청백 악센트 색) — 범철 수수(낮은 채도) → 신병 화려 청백(높은 채도).
# 마병은 검붉은 계보색(아래 mab 분기), 명병은 _ACC_OVR 문파색이 이 표를 이긴다.
_R21_CEL = {
    "beomcheol":  (0.10, (150, 165, 185, 255)),   # 수수 — 무딘 강청 (사다리 하단)
    "jeongryeon": (0.22, (150, 178, 200, 255)),
    "bobyeong":   (0.36, (148, 198, 224, 255)),
    "sinbyeong":  (0.58, (150, 216, 246, 255)),   # 화려 청백 셀 (칼끝 청백 하이라이트)
    "mabyeong":   (0.62, (150, 40, 34, 255)),     # 검붉은 (mab 분기가 실제 색을 쥔다)
}


def _r21_brass(salt, grade):
    """만화 셀 금 부속 — 2톤 금 + 어두운 외곽 테 (대담한 코등이의 만화 렌더 · 강한 외곽선)."""
    lo, mid, hi = GOLD_FIT_DIM, GOLD_FIT_MID, GOLD_FIT_HI
    out = mix(GOLD_FIT_DIM, WPN_OUT, 0.62)         # 만화 외곽선 — 어두운 테 1px
    iron = grade == "beomcheol"
    def style(t, d0, w, side, mx, my):
        rel = d0 / max(w, 1e-6)
        if rel > 0.82:
            c = out                                # 외곽 테
        elif side > 0 and rel < 0.42:
            c = mix(hi, MOON_V, 0.28)              # 빛 받는 쪽 셀 하이라이트 (별표 반짝)
        else:
            c = hi if rel < 0.5 else (mid if rel < 0.72 else lo)   # 2~3톤 셀
        if iron:
            c = mix(c, BLADE_DIM, 0.6)             # 범철은 금이 아니라 무딘 쇠 (사다리 하단)
        return c
    return style


def _sweep_coord(series, t, rel):
    """계열 스윕 서명 — 광택 하이라이트가 표면 위를 흐르는 축 (0..1, 위상과 비교)."""
    if series == "dao":                            # 도 — 넓은 배를 가로지르는 사행
        return (0.62 * t + 0.5 * rel) % 1.0
    if series in ("gyeom", "gu", "wolasan"):       # 곡선 병기 — 호를 따라 흐른다
        return t
    if series == "spear":                          # 창 — 날에서 자루로 흘러내린다
        return (1.0 - t) % 1.0
    if series == "gauntlet":                        # 권갑 — 마디 위로 (가로)
        return rel
    return t                                        # 검·비수 — 인선 따라 세로로 흐른다


def _sweep_mix(c, series, grade, t, rel, side, phase, single=False, edge_side=-1.0):
    """표면 스펙큘러 스윕 — 벼린 금속이 빛을 받아 반짝이는 광택 마루가 흐른다 (일렁임의 핵심).
    phase(프레임 위상 0..1)에 가까운 스윕 축 자리를 흰-스펙큘러로 밝힌다 (등급=세기·계열=방향).
    ★무기 몸/날의 픽셀아트 플랫 규율 안 — 스윕은 광택 하이라이트지 발광 오라가 아니다
    (마법처럼 뜨는 헤일로가 아니라 표면 위 반사)."""
    if phase is None:
        return c
    strength, tintk = _R20_SWEEP[grade]
    if strength <= 0.01:
        return c
    if single and side == -edge_side:              # 한날 척(등) — 광택이 잦아든다
        strength *= 0.4
    sc = _sweep_coord(series, t, rel)
    band = (sc - phase) % 1.0
    dist = min(band, 1.0 - band)                   # 순환 거리 (스윕이 감돈다)
    bw = 0.11                                       # 스윕 폭 (좁은 마루 · 잠정)
    s = math.exp(-(dist / bw) ** 2) * strength
    # 광택은 인선·능선 마루에서 가장 밝다 (벼린 면이 빛을 되쏜다) · 사면 중턱은 덜
    s *= 0.5 + 0.5 * (rel if rel > 0.5 else 1.0 - rel * 0.6)
    if s <= 0.03:
        return c
    tint = _R20_SWEEP_TINT[tintk]
    hi = mix(BLADE_HI, tint, 0.5) if tint else BLADE_HI    # 스펙큘러 마루 색 (고등급 살짝 색)
    return mix(c, hi, min(0.9, s))


def _qi_accent(series, grade):
    """발광색 = 계열/문파 악센트의 번역 (마병은 검붉은 발광 — 계보 신호 유지).
    ★18차 — 계열 배선 완료 (_wisps·수실/홍영 acc 전 호출부가 series/ink 악센트를 준다).
    series=None 폴백(옥)은 "full" 대조 기록 경로(_r16_ring 등)에만 남는다 — calm 팩 배선은
    None 으로 부르는 곳이 없다 (명병은 _ACC_OVR 이 _ink8 을 거쳐 문파색으로 갈아탄다)."""
    if grade == "mabyeong":
        return CRIM_V, CRIM_VHI
    b8 = _ink8(series) if series else SERIES_INK["sword"]
    return b8[4], b8[5]


def _qi_glow(a_lo, a_hi, lvl):
    """발광 램프 — 단수 램프 4단 (그라데이션 아님 · 9차 발광 문법 승계):
    그늘 껍질 → 반광 → 악센트 → 백광 심. lvl 이 셀수록 안쪽 단이 나온다."""
    if lvl >= 3.0:
        return mix(a_hi, MOON_V, 0.72)
    if lvl >= 2.0:
        return a_hi
    if lvl >= 1.0:
        return mix(a_lo, a_hi, 0.5)
    return mix(a_lo, WPN_OUT, 0.35)


def _r16_steel(salt, grade, series=None, single=False, edge_side=-1.0, dark=False,
               calm=False, vein=True, poison=False):
    """선협 날 — 어두운 옥/먹 심 + 발광 윤곽선 + 흐르는 기맥.
    윤곽 발광: 가장자리 진행 q 에 강도 g 를 곱해 단(lvl)이 오른다 — 강할수록 백광 심까지.
    기맥: rel-공간을 사행하는 실선 (sin 위상 = salt) + 마디 광점. 마병 = 잦은 굽이 + 꺾임.
    calm=16차-b 절제판: 몸 = 현행 A안 재질감 · 인선 쪽만 가는 발광 · 기맥 1~2 저대비 ·
    마병 = 조용한 먹 + 긴 균열 실선 2가닥 (사용자: "너무 과합니다")."""
    g, nvein, _w_, _m_ = (_QI_GRADE_CALM if calm else _QI_GRADE)[grade]
    if not vein:                                   # 17차 정렬 — 기맥은 _qi_vein 오버레이가 긋는다:
        nvein = 0                                  # rel-공간 사행 무늬는 부위마다 뚝 끊겼다 (정본 R3)
    mab = grade == "mabyeong"
    a_lo, a_hi = _qi_accent(series, grade)
    body = mix(_QI_BODY, BLOOD, 0.28) if mab else _QI_BODY
    core = mix(_QI_INK, BLOOD, 0.22) if mab else _QI_INK
    if dark:                                       # 곤륜 음 갈래 — 기운을 죽인 먹
        g *= 0.3
    if calm:
        b8 = _ink8(series) if series else SERIES_INK["sword"]
        core_c = mix(b8[0], b8[1], 0.3)            # 현행 A안 몸체 — 무기가 먼저다
        facet = mix(BONE_MID, BLADE_LIT, 0.55)
        q_core = mix(mix(BLADE_SPINE, WPN_OUT, 0.5), BLOOD, 0.12)   # 마병 — 조용한 먹
        q_body = mix(mix(BLADE_SPINE, WPN_OUT, 0.2), BLOOD, 0.1)
        def style(t, d0, w, side, mx, my):
            rel = min(1.0, d0 / max(w, 1e-6))
            if dark:                               # 곤륜 음 갈래 — 기운을 죽인 무채 먹 (현행
                c = BLADE_SPINE if rel < 0.5 else BLADE_DIM   # 유지 · 무성무색은 빛나지 않는다)
                return c
            edge_ok = (not single) or side == edge_side
            gg = g * (1.0 if edge_ok else 0.12)    # 발광은 인선 쪽 위주 — 등은 거의 침묵
            if gg > 0.04 and rel > 0.84:           # 가는 인선 발광 (백광 심 억제)
                lvl = (rel - 0.84) / 0.16 * (0.7 + 1.7 * gg)
                if t > 0.955:
                    lvl += 0.9                     # 칼끝 한 점만 맺힌다
                if t < 0.07:
                    lvl -= 1.0
                if lvl >= 1.0:
                    return _qi_glow(a_lo, a_hi, min(lvl, 3.1 if t > 0.955 else 2.5))
            if mab:
                c = q_core if rel < 0.32 else q_body
            elif single and side == -edge_side:    # 척 — 어두운 심이 등을 진다 (A안 그대로)
                c = mix(core_c, WPN_OUT, 0.3) if rel > 0.62 else core_c
            else:
                c = core_c if rel < 0.34 else facet
            if nvein and 0.1 < t < 0.93 and rel < 0.7:   # 기맥 — 은은히 흐른다 (저대비)
                for i in range(min(nvein, 2)):
                    ph = salt * 0.23 + i * 2.13
                    fq = 5.3 if mab else 6.2       # 마병 균열 = 느린 긴 실선
                    path = 0.2 + 0.22 * i + 0.13 * math.sin(t * fq + ph)
                    if abs(rel - path) < (0.035 if mab else 0.045):
                        node = math.sin(t * 19.0 + ph * 1.7)
                        gl = _qi_glow(a_lo, a_hi, 2.4 if node > 0.95 else 1.8)
                        return mix(c, gl, 0.6 if node > 0.95 else 0.42)
            if grade == "sinbyeong" and rel < 0.3 and (0.15 < t < 0.2 or 0.26 < t < 0.3):
                return mix(c, _qi_glow(a_lo, a_hi, 2.2), 0.5)   # 룬 — 은은한 각인
            if poison and t > 0.82:
                c = POISON                         # 당가 — 독 오른 끝 (A 문법 그대로 · 18차)
            if t < 0.1:
                c = mix(c, WPN_OUT, 0.3)
            return c
        return style
    def style(t, d0, w, side, mx, my):
        rel = min(1.0, d0 / max(w, 1e-6))
        spine = single and side == -edge_side
        gg = g * (0.4 if spine else 1.0)           # 척(등)은 윤곽 발광이 잦아든다
        if gg > 0.05:
            lvl = max(0.0, (rel - 0.68) / 0.32) * (1.2 + 2.6 * gg)
            if t > 1.0 - 0.1 * gg:
                lvl += 1.6                         # 칼끝 — 기운이 맺힌다
            if t < 0.07:
                lvl -= 1.0                         # 뿌리 — 어둠에 잠긴다 (AO 앵커)
            if lvl >= 1.0:
                return _qi_glow(a_lo, a_hi, lvl)   # 발광 윤곽선
        c = core if rel < 0.32 else body
        if nvein and 0.07 < t < 0.95 and rel < 0.74:   # ── 흐르는 기맥
            for i in range(nvein):
                ph = salt * 0.23 + i * 2.13
                fq = 11.4 if mab else 7.1
                path = 0.16 + 0.19 * i + 0.15 * math.sin(t * fq + ph)
                if mab:                            # 균열 기맥 — 굽이가 꺾인다
                    path += 0.05 * (1.0 if math.sin(t * 31.0 + ph) > 0 else -1.0)
                tol = (0.04 + 0.015 * gg) if mab else (0.05 + 0.02 * gg)
                if abs(rel - path) < tol:          # 마병은 가늘고 어둡게 — 균열은 과밀 금지
                    node = math.sin(t * 19.0 + ph * 1.7)
                    return _qi_glow(a_lo, a_hi, (3.0 if node > 0.9 else 2.0) if mab
                                    else (3.2 if node > 0.86 else 2.2))   # 마디 광점
        if grade == "sinbyeong" and rel < 0.3 and (0.15 < t < 0.2 or 0.26 < t < 0.3):
            return _qi_glow(a_lo, a_hi, 3.2)       # 룬 각인 — 자리 유지 · 발광색 번역
        return c
    return style


def _r16_grip(salt, L, wood=False, ink=None, grade=None, acc=None, calm=False, vein=True):
    """선협 자루 — 먹 원통(계열 기조 기미) + 감아 도는 기맥 실 (가죽 감김의 기환 번역).
    calm(16차-b): 현행 유색 원통 그대로 두고 실 하나만 은은히 감긴다 (몸색에 0.3 혼합).
    vein=False (17차 정렬) — 감김 실도 _qi_vein 오버레이 한 획이 대신한다 (정본 R3)."""
    g = (_QI_GRADE_CALM if calm else _QI_GRADE)[grade][0] if grade else (0.3 if calm else 0.6)
    if not vein:
        g = 0.0
    a = acc or _qi_accent(None, grade or "bobyeong")
    if calm:
        base = ink if ink else (GRIP_DIM, GRIP_MID)          # 현행 원통 기조 — 무기가 먼저
        def style(t, d0, w, side, mx, my):
            rel = d0 / max(w, 1e-6)
            if wood:
                c = GRIP_MID if (side > 0 and rel < 0.5) else GRIP_DIM
                return mix(c, GRIP_DARK, 0.7) if rel > 0.76 else c
            c = base[1] if (side > 0 and rel < 0.5) else base[0]
            if g > 0.25 and rel <= 0.76:
                u = (t * L + side * d0 * 1.4) / 2.3
                if u - math.floor(u) < 0.1:
                    c = mix(c, a[1], 0.3)                    # 은은한 기맥 실 한 가닥
            return mix(c, WPN_OUT, 0.55) if rel > 0.76 else c
        return style
    base_lo = mix(_QI_INK, ink[0], 0.35) if ink else _QI_INK
    base_hi = mix(base_lo, ink[1] if ink else _QI_BODY, 0.3)
    def style(t, d0, w, side, mx, my):
        rel = d0 / max(w, 1e-6)
        if g > 0.05:
            u = (t * L + side * d0 * 1.4) / 2.3
            if u - math.floor(u) < 0.12 and rel <= 0.78:
                return _qi_glow(a[0], a[1], 1.0 + 1.2 * g)   # 감김 기맥 실
        c = base_hi if (side > 0 and rel < 0.5) else base_lo
        return mix(c, WPN_OUT, 0.5) if rel > 0.78 else c
    return style


def _r16_metal(salt, grade):
    """선협 기물 (물미 관·목띠·코등이·소켓) — 먹쇠 2톤 + 발광 심선 (금 부속의 기환 번역)."""
    g = _QI_GRADE[grade][0]
    seam = CRIM_VHI if grade == "mabyeong" else mix(MOON_V, JADE_V, 0.35)
    def style(t, d0, w, side, mx, my):
        rel = d0 / max(w, 1e-6)
        c = _QI_METAL[1] if rel < 0.4 else _QI_METAL[0]
        if g > 0.3 and abs(d0 - w * 0.52) < 0.16:
            c = mix(c, seam, 0.4 + 0.4 * g)        # 심선 — 형태를 따라 흐르는 빛
        return mix(c, WPN_OUT, 0.4) if rel > 0.8 else c
    return style


def _r16_ring():
    """등급 고리 — 발광 테 (금속 테의 기환 번역: 등급이 빛의 수로 읽힌다). 【전파 유보】 옥 폴백."""
    def style(t, d0, w, side, mx, my):
        return mix(MOON_V, JADE_V, 0.3) if d0 / max(w, 1e-6) < 0.45 \
            else mix(JADE_V, WPN_OUT, 0.45)
    return style


def _r16_wisp_style(a_lo, a_hi, k=1.0):
    """기운 자락 — 뿌리 백광 → 악센트 → 끝이 어둠에 스러진다 (불투명 발광 램프 · 수실 대체)."""
    def style(t, d0, w, side, mx, my):
        return _qi_glow(a_lo, a_hi, max((3.2 - 2.9 * t) * k, 0.0))
    return style


def _r16_orb_style(rim, core):
    """내단(內丹)/부유 광점 — 백광 심 + 악센트 몸 + 그늘 껍질 (보주·파편의 기환 번역)."""
    def style(t, d0, w, side, mx, my):
        rel = d0 / max(w, 1e-6)
        if rel < 0.34:
            return mix(core, MOON_V, 0.75)
        return core if rel < 0.7 else mix(rim, WPN_OUT, 0.35)
    return style


def _r16_head_kit(salt, series, grade, hx, hy, wing=(-0.9, 0.9), calm=False):
    """선협 머리부 — 물리 의장(필리그리·금 소켓·깃·보주·파편)을 지우고 기환으로 대체:
    기환 고리(흐르는 발광 halo) + 내단 + 기운 자락 + 부유 광점.
    사다리 = 형태의 있고 없음이 아니라 **빛의 세기와 수** (_QI_GRADE).
    calm(16차-b): 고리 r×0.78 · 폭/밝기 축소 · 자락 1 · 광점 2 · 백광 억제."""
    g, _nv, nwisp, nmote = (_QI_GRADE_CALM if calm else _QI_GRADE)[grade]
    if _R19:                                       # 19차-b/c — 잔광은 소수의 절제된 점만
        if not _R19_WISP:
            nwisp = 0                              # 기환 자락(줄기) 제거 — 난잡의 주범
        if _R19_MOTE_MERIDIAN:
            nmote = 0                              # 19c — 부유 광점은 기맥 경혈로 이전
            #   (_meridian_motes 가 기맥 경로 위 마디에 놓는다 — 기환 축 임의 오프셋이 아니라
            #    해부에서 도출된 자리 · 정본 §4. 옛 r+2.7 산포가 사용자가 본 "무근거 오라 양".)
        else:
            nmote = min(nmote, 2)                  # 19b 대조 — 기환 축 r+2.7 산포 재현
    if g <= 0.05:
        return []                                  # 범철 — 기운이 아직 안 열렸다
    a_lo, a_hi = _qi_accent(series, grade)
    r, _brk, _wings, fw = _HEAD_FORM.get(series, (2.5, None, True, 1.0))
    if calm:
        r *= 0.78
    sh = []
    def ringstyle(t, d0, w, side, mx, my):         # 기환 고리 — 밝기가 고리를 따라 흐른다
        if calm:                                   # 절제 — 악센트 반광 언저리서만 오르내린다
            return _qi_glow(a_lo, a_hi,
                            0.9 + 0.7 * g + 0.5 * math.sin(t * math.tau * 2.0 + salt * 0.31))
        return _qi_glow(a_lo, a_hi,
                        1.2 + 1.6 * g + 1.1 * math.sin(t * math.tau * 2.0 + salt * 0.31))
    sh.append(_pn(_vd(_stroke(_arcpts(hx, hy, r, 0, 360, 18),
                              (0.3 + 0.06 * g) if calm else (0.42 + 0.1 * g), ringstyle),
                      _vrot((1.0, 0.0, None), 22.5, hx, hy)), "기환고리"))
    if g >= 0.55:                                  # 내단 — 보주 자리의 기운 구슬
        rr = max(0.6 if calm else 0.7, r - 1.35)
        if calm:
            def orb_dim(t, d0, w, side, mx, my):   # 백광 억제 — 심은 반백까지만
                rel = d0 / max(w, 1e-6)
                if rel < 0.3:
                    return mix(a_hi, MOON_V, 0.4)
                return mix(a_lo, a_hi, 0.6) if rel < 0.7 else mix(a_lo, WPN_OUT, 0.4)
            sh.append(_pn(_vd(_stroke([(hx, hy), (hx + 0.01, hy)], rr, orb_dim),
                              _vorb(rr)), "내단"))
        else:
            sh.append(_pn(_vd(_stroke([(hx, hy), (hx + 0.01, hy)], rr,
                                      _r16_orb_style(a_lo, a_hi)), _vorb(rr)), "내단"))
    align = calm and _R16_ALIGN                    # 17차 정렬 — 자락은 기환에서 흘러나온다
    wl = (4.8 if _R19 else 3.5) if calm else 4.4   # calm — 자락이 짧고 가늘고 어둡다
    #                                                (19차 오라 분리 — 자락을 더 멀리 흘린다)
    ww = (0.4 if calm else 0.5) * fw
    wk = 0.65 if calm else 0.9 + 0.3 * g
    for j in range(nwisp):                         # 기운 자락 — 옛 깃 자리에서 뒤로 흐른다
        wx = wing[0]
        oy = (r - 0.2, r + 0.3, -r + 0.2)[j % 3]
        sy_ = (1.0, 1.35, -1.0)[j % 3]
        # 17차 정렬: 뿌리를 기환 고리 접점(윗마루)에 앉힌다 — 옛 자리(hx-1.2)는 허공에서
        # 시작하는 자락이었다 (기운은 기환에서 넘쳐 흐른다 — 정본 §4).
        p0 = (hx - 0.2 + j * 0.9, hy + oy) if align else (hx - 1.2 + j * 0.9, hy + oy)
        p1 = (p0[0] + wx * wl * 0.5, p0[1] + sy_ * 1.3)
        p2 = (p0[0] + wx * wl, p0[1] + sy_ * 2.1)
        sh.append(_pn(_vd(_stroke(_bez(p0, p1, p2, 8),
                                  lambda t: max(0.16, ww * (1.0 - 0.7 * t)),
                                  _r16_wisp_style(a_lo, a_hi, wk)),
                          _vrot((0.8, 0.0, None), (22.5, -22.5, 45.0)[j % 3],
                                p0[0], p0[1])), "기운자락"))
    k_ = max(0.85, r / 2.8)
    if calm:
        def mote_style(t, d0, w, side, mx, my):    # calm 광점 — 백광 없이 악센트 한 톤
            return a_hi if d0 / max(w, 1e-6) < 0.55 else mix(a_lo, WPN_OUT, 0.4)
    else:
        mote_style = None
    for i in range(nmote):                         # 부유 광점
        if align:                                  # 17차 정렬 — 기환 축 위아래 대칭 짝:
            #   광점 = 기환에서 새어나온 잔광 (정본 §4). _P7_BURST 방사 상수 재사용은
            #   "균일하지 않은 산포"가 이유였는데 광점 2개에선 그저 무근거 배치로 읽혔다.
            far = (r + 2.7, -(r + 2.6)) if _R19 else (r + 1.5, -(r + 1.4))   # 19차 — 더 멀리
            px_, py_ = hx, hy + far[i % 2]
            rr = (0.28, 0.24)[i % 2]
        else:
            ang, dist, _ln, _curl = _P7_BURST[i % len(_P7_BURST)]
            a = math.radians(ang)
            px_, py_ = hx + dist * k_ * math.cos(a), hy + dist * k_ * math.sin(a)
            rr = ((0.26 + 0.04 * ((i * 7 + salt) % 3)) if calm
                  else 0.34 + 0.05 * ((i * 7 + salt) % 3))
        vt = _vshard(i, px_, py_)
        if _R19:                                   # 19차 오라 분리 — 광점을 더 앞으로 띄운다
            vt = _vz(vt, vt[1] + (1.4 if i % 2 == 0 else -1.4))
        sh.append(_pn(_vd(_stroke([(px_, py_), (px_ + 0.01, py_)], rr,
                                  mote_style or _r16_orb_style(a_lo, a_hi)), vt), "부유광점"))
    return sh


def _ink8(series):
    """계열 색표 + 명병 악센트 상속 — 6튜플 (자루 2 · 의장 2 · 악센트 2)."""
    b = SERIES_INK[series]
    return b[:4] + tuple(_ACC_OVR[0]) if _ACC_OVR[0] else b


def _flat3(lo, hi):
    """플랫 3단 — 픽셀아트 램프 (그라데이션 금지: 레퍼런스의 청키한 명암).
    15차: 몸 한가운데 음각 새김선 1획 — 필리그리·소켓·깃의 내부가 128px 에서 비로소 산다."""
    md = mix(lo, hi, 0.5)
    eng = mix(lo, WPN_OUT, 0.3)
    def style(t, d0, w, side, mx, my):
        rel = d0 / w
        if _D15 and abs(d0 - w * 0.52) < 0.17 and 0.06 < t < 0.94:
            return eng                             # 음각 라인 — 형태를 따라 흐르는 잔선 (~1px)
        return hi if rel < 0.32 else (md if rel < 0.74 else lo)
    return style


def _ring_orn(cx, cy, r, w, lo, hi):
    """필리그리 고리 — 도넛 (가운데 구멍 = 네거티브 스페이스, 레퍼런스의 숭숭 뚫린 장식)."""
    return _stroke(_arcpts(cx, cy, r, 0, 360, 18), w, _flat3(lo, hi))


_HEAD_FORM = {   # 계열 → (고리 r · 앞 브래킷 이격(None=없음) · 깃 유무 · 깃 배율) — 변주 【잠정】
    # 13차 (비율 재균형): 고리 r 을 한 단 줄여 의장 머리가 **길이 방향으로 압축**된다 —
    # "장식이 크다"가 아니라 "장식이 밀도 있다". 고리·보주·깃 구성은 그대로 (완성도 사다리 불변).
    # 권갑만 유지 — 손등 정면 의장이라 길이 축을 먹지 않는다.
    "sword": (2.2, 1.15, True, 1.0),     # 검 — 겹고리 + 브래킷 + 깃 (파일럿 B 골격)
    "dao": (2.2, 1.15, True, 1.0),       # 도 — 원반 호수를 두르는 고리
    "spear": (2.35, None, True, 1.3),    # 창 — 소켓 고리 + 큰 깃 (날개가 주인공 · 장병 배율)
    "gauntlet": (2.3, None, True, 1.0),  # 권갑 — 손등 정면 의장 (고리 = 방패 보스)
    "dagger": (1.6, 0.45, False, 1.0),   # 비수 — 축소 의장 (작고 응축 — 브래킷이 고리를 문다)
    "bu": (2.1, None, True, 1.05),       # 부 — 도끼 목 고리 + 깃
    "gyeom": (2.0, None, True, 1.0),     # 겸 — 슴베 목 고리
    "wolasan": (1.85, None, False, 1.3), # 월아산 — 쌍월 (뒤 소형 고리는 스펙이 단다)
    "gu": (1.95, 1.15, True, 1.0),       # 구 — 갈고리 관
}


def _head_kit(salt, series, grade, hx, hy, wing=(-0.9, 0.9)):
    """머리부 의장 — 9차 A안 (사용자 결정 번복: B 전파 뒤 "A가 더 좋아 보입니다"):
    구조·사다리는 8차 그대로 (**큰 형태 3~4개 + 방사 파편** · 각 요소 6px+ · 네거티브
    시원하게), 재질·색 층만 A — 의장 = **순 뼈백 필리그리**(SERIES_INK 개정) + 소켓 띠는
    **금**, 보주는 **흑자주**(파일럿 A 의 눈). 사다리 = 장식 완성도 + 파편 수:
    범철 0(실용 병기) / 정련 금 소켓 이중 띠 / 보병 대형 고리 / 신병 완전 의장(고리+브래킷
    +깃+보주)+파편 4 / 마병 흑골 의장+진홍 파편 5. 계열 변주 = _HEAD_FORM + 악센트 1색."""
    if _R16:                                       # 16차 선협 — 물리 의장을 기환으로 대체
        return _r16_head_kit(salt, series, grade, hx, hy, wing, calm=(_R16 == "calm"))
    b = _ink8(series)
    o_lo, o_hi, a_lo, a_hi = b[2], b[3], b[4], b[5]
    g_lo, g_hi = GOLD_FIT_MID, GOLD_FIT_HI         # 금 소켓 띠 (A안 — 금 부속 비중 상향)
    orb_lo, orb_hi = mix(DK_PLUM, WPN_OUT, 0.3), DK_PLUM_HI   # 흑자주 보주
    lv = _ORN[grade]
    dark = grade == "mabyeong"
    if dark:
        o_lo, o_hi = mix(o_lo, WPN_OUT, 0.5), mix(o_hi, WPN_OUT, 0.45)   # 흑골
        g_lo, g_hi = o_lo, o_hi                    # 마병은 금도 뼈도 삭는다
        orb_lo, orb_hi = mix(orb_lo, WPN_OUT, 0.5), mix(orb_hi, WPN_OUT, 0.45)
        a_lo, a_hi = CRIM_V, CRIM_VHI
    if lv < 1:
        return []
    r, brk, wings, fw = _HEAD_FORM.get(series, (2.5, None, True, 1.0))
    sh = []
    for bx_ in (hx - 0.55, hx + 0.65):             # 정련 — 금 소켓 이중 띠 (돌출)
        sh.append(_pn(_vd(_stroke([(bx_, hy - 1.9), (bx_, hy + 1.9)], 0.36,
                                  _flat3(g_lo, g_hi)), _VD_ORN), "소켓띠"))
    if lv >= 2:                                    # 보병 — 대형 필리그리 고리 (구멍이 시원하다)
        sh.append(_pn(_vd(_ring_orn(hx, hy, r, 0.6, o_lo, o_hi),
                          _vrot((1.1, 0.0, None), 22.5, hx, hy)), "의장고리"))   # y축 22.5° (11차)
    if lv >= 3:                                    # 신병/마병 — 완전 의장
        if brk:
            sh.append(_pn(_vd(_stroke(_arcpts(hx + r + brk + 0.95, hy, 1.25, 95, 265, 12), 0.6,
                                      _flat3(o_lo, o_hi)), _VD_ORN), "의장브래킷"))   # (13차 r 1.25)
        if wings:                                  # 깃 — 위 2 + 아래 1, 뒤로 쓸린다.
            wx = wing[0]                           # 각 깃이 다른 y축 기울기로 부챗살처럼 벌어진다
            # 13차: 깃의 길이 축 쓸림을 ×0.72 압축 (2.2/4.6 → 1.6/3.3 등) — 폭 방향 벌어짐은
            # 유지한다 (의장의 존재감은 폭이, 자루 축 점유는 길이가 정한다).
            sh.append(_pn(_vd(_p7_feather((hx - 1.3, hy + r - 0.2), (hx - 1.3 + wx * 1.6, hy + r + 1.2),
                                          (hx - 1.3 + wx * 3.3, hy + r + 1.9), 0.62 * fw,
                                          o_lo, o_hi),
                              _vrot((0.9, 0.0, None), 22.5, hx - 1.3, hy + r - 0.2)), "의장깃"))
            sh.append(_pn(_vd(_p7_feather((hx + 0.7, hy + r), (hx + 0.7 + wx * 1.4, hy + r + 1.4),
                                          (hx + 0.7 + wx * 2.8, hy + r + 2.1), 0.5 * fw,
                                          o_lo, o_hi),
                              _vrot((0.9, 0.0, None), -22.5, hx + 0.7, hy + r)), "의장깃"))
            sh.append(_pn(_vd(_p7_feather((hx - 1.1, hy - r + 0.2), (hx - 1.1 + wx * 1.45, hy - r - 1.0),
                                          (hx - 1.1 + wx * 3.0, hy - r - 1.7), 0.56 * fw,
                                          o_lo, o_hi),
                              _vrot((0.9, 0.0, None), 45.0, hx - 1.1, hy - r + 0.2)), "의장깃"))
        sh.append(_pn(_vd(_p7_orb(hx, hy, max(0.65, r - 1.35), orb_lo, orb_hi, a_hi),
                          _vorb(max(0.65, r - 1.35))), "보주"))   # 보주 — 깊이=지름 (z 로 튄다)
        sh += _p7_burst(hx, hy, a_lo, a_hi, n=5 if dark else 4,        # 보주 — 악센트 광점 ↑
                        k=max(0.85, r / 2.8), petal=_PETAL_OVR[0])
    return sh


# ═══ V2-W 7차 파일럿 A/B/C → 8차 전파 (2026-07-16) ═══════════════════════════════
# 조율자 실사 판정(6차 잔여 격차): ① 의장 머리부가 1~2px 조각들의 노이즈 덩어리 (레퍼런스는
# 큰 형태 3~4개 + 시원한 네거티브 스페이스) ② 회색 날이 전장 대부분 ③ 프레임 내 본체 왜소.
# 파일럿 1자루(검 신병) A/B/C 시안 → ★사용자 결정: **B안** (의장 구조는 레퍼런스 문법 그대로,
# 색은 묵청 뿌리(먹·강철·뼈백) + 선명한 악센트 1색, 문양은 무협) → 같은 회차에 57자루 전파.
# 공통 수리: 큰 형태 3~4개(각 6px+ · 서로 분리 · 구멍이 시원) · 날 35~45% 조연 ·
# 본체 프레임 대각 70%+ · 파편 4~5개 방사 호(균일 뿌림 금지) · 플랫 2~3단 램프(그라데이션·노이즈 금지).
# 아래 _flat2.._p7_* 는 8차 공용 부품이고, _spec_sword_pilot7 은 A/C 시안 렌더 기록이다.
def _flat2(lo, hi, edge=0.58):
    """플랫 2단 — 가장자리 lo · 몸 hi (파편·꽃잎의 청키한 조각)."""
    def style(t, d0, w, side, mx, my):
        return lo if d0 / w > edge else hi
    return style


def _p7_orb(cx, cy, r, rim, core, gleam):
    """보주 — 플랫 3존: 테 · 몸 · 좌상 광점 (호마의 눈 — 그라데이션 없음).
    15차: 광점을 코어+림 2단으로 정밀화 — 한 덩이 점이 '맺힌 빛'으로 읽힌다."""
    def style(t, d0, w, side, mx, my):
        gd = math.hypot(mx - (cx - r * 0.34), my - (cy + r * 0.34))
        if gd < r * 0.4:
            if not _D15:
                return gleam
            return mix(gleam, MOON_V, 0.5) if gd < r * 0.18 else mix(gleam, core, 0.3)
        return rim if d0 / w > 0.66 else core
    return _stroke([(cx, cy), (cx + 0.01, cy)], r,
                   _r16_orb_style(rim, core) if _R16 == "full" else style)   # 16차 — 내단
                   # 번역은 full 만 — calm 은 물미 구슬 등 기능 부위 질감 회생 (현행 3존)


def _p7_feather(p0, p1, p2, w0, lo, hi):
    """관/깃 하나 — 뿌리 굵고 끝이 가늘어지는 한 획 (플랫 3단)."""
    return _stroke(_bez(p0, p1, p2, 8), lambda t: max(0.22, w0 * (1.0 - 0.72 * t)),
                   _flat3(lo, hi))


_P7_BURST = (      # (각도°, 거리, 길이, 굽이) — 머리 주변 폭발 방사형 호. 명시 결정론 상수 —
    (36, 5.0, 1.5, 0.55),    # h32 산포(6차)는 각도가 고르게 흩어져 '균일 뿌림'으로 읽혔다.
    (104, 4.4, 1.0, -0.45),  # 폭발은 고르지 않다 — 성긴 곳과 몰린 곳이 있어야 방사다.
    (150, 5.6, 1.7, 0.5),    # 거리는 의장 고리 바깥 1u+ — 붙으면 파편이 아니라 혹이다.
    (238, 4.7, 1.1, -0.5),
    (317, 5.3, 1.4, 0.45),
)


def _shard15(lo, hi, petal=False):
    """15차 — 파편의 잔선: 결정면 가로선 1~2 + 밝은 심 (꽃잎이면 잎맥 한 획).
    큰 형태(플랫 2단 조각)는 그대로 — 잔선이 '수정 조각/꽃잎'을 한 켜 더 말한다."""
    def style(t, d0, w, side, mx, my):
        rel = d0 / w
        c = lo if rel > 0.58 else hi
        if not _D15:
            return c
        if petal:
            if rel < 0.34 and 0.1 < t < 0.85:
                return mix(lo, WPN_OUT, 0.25)      # 꽃잎 맥 — 중심 실선
            return c
        if rel < 0.85 and (abs(t - 0.52) < 0.09 or abs(t - 0.82) < 0.07):
            return mix(lo, WPN_OUT, 0.22)          # 결정면 1~2선
        if rel < 0.36 and 0.12 < t < 0.45:
            return mix(hi, MOON_V, 0.55)           # 밝은 심
        return c
    return style


def _p7_burst(cx, cy, lo, hi, n=5, k=1.0, petal=False):
    """떠 있는 파편 4~5 — 각 2~4px · 본체 비접촉 · 바깥으로 굽는 호 (옥 조각/불티/꽃잎).
    11차: 파편마다 서로 다른 z 평면(±2~4) + 개별 y축 기울기 (_vshard) — 옆에서도 떠 있다."""
    out = []
    for i, (ang, dist, ln, curl) in enumerate(_P7_BURST[:n]):
        a = math.radians(ang)
        dx, dy = math.cos(a), math.sin(a)
        px_, py_ = cx + dist * k * dx, cy + dist * k * dy
        nx, ny = -dy, dx
        pts = [(px_, py_),
               (px_ + dx * ln * 0.55 + nx * curl * 0.3, py_ + dy * ln * 0.55 + ny * curl * 0.3),
               (px_ + dx * ln + nx * curl, py_ + dy * ln + ny * curl)]
        w0, tp = (0.55, 0.35) if petal else (0.46, 0.58)
        out.append(_pn(_vd(_stroke(pts, lambda t, w=w0, s=tp: max(0.18, w * (1.0 - s * t)),
                                   _shard15(lo, hi, petal=petal)),
                           _vshard(i, px_, py_)), "매화잎" if petal else "파편"))
    return out


def _p7_blade_style(edge, body, ridge, tipc, tip_t=0.85, edge_rel=0.6, ridge_rel=0.24):
    """날 — 플랫 3단 (능선/몸/인선) + 칼끝 존. 신병이니 인선에 악센트빛 허용."""
    def style(t, d0, w, side, mx, my):
        rel = d0 / w
        if t > tip_t:
            return tipc
        if rel > edge_rel:
            return edge
        if rel < ridge_rel:
            return ridge
        return body
    return style


def _p7_grip_style(ink):
    """자루 — 플랫 원통 2단(위가 밝다) + 깊은 테. 랩 무늬 없음 — 레퍼런스의 자루는
    조용한 유색 원통이고, 띠 두어 개가 따로 두른다 (6차 4보 랩은 체커 잡음으로 읽혔다)."""
    def style(t, d0, w, side, mx, my):
        c = ink[1] if (side > 0 and d0 / w < 0.5) else ink[0]
        if d0 / w > 0.76:
            c = mix(c, WPN_OUT, 0.55)
        return c
    return style


def _spec_sword_pilot7(salt, variant):
    """검 신병 파일럿 — A(레퍼런스 최대 근접) / B(의장·무협 절충) / C(발광 신검).
    골격 공통: 물미 구슬 + 유색 자루(뼈백 사선 랩) + 금/놋 목띠 + 의장 머리부(큰 형태 3~4)
    + 조연 날(전장 36~41%) + 방사 파편. → (box, shapes, extras, ink)."""
    minimal = variant == "C"
    hc = (2.4, 8.0) if minimal else (2.6, 8.0)    # 머리부 중심
    if variant == "A":       # 심녹 자루 · 금/뼈백 의장 · 흑자주 보주 · 옥 파편 (옥장 문법)
        ink = (DK_GREEN, DK_GREEN_HI)
        orn = (BONE_DK, BONE)
        gold = (GOLD_V, GOLD_VHI)
        orb = (DK_PLUM, DK_PLUM_HI, JADE_VHI)
        acc = (JADE_V, JADE_VHI)
        blade = _p7_blade_style(edge=mix(JADE_VHI, MOON_V, 0.45),          # 레퍼런스 날 문법:
                                body=mix(BONE_MID, BLADE_LIT, 0.55),       # 어두운 심 + 뼈백 사면
                                ridge=mix(DK_PLUM, DK_PLUM_HI, 0.3),       # + 옥백 인선
                                tipc=MOON_V, ridge_rel=0.42, edge_rel=0.66)
    elif variant == "B":     # 묵청 뿌리(먹·강철) + 선명 악센트 1색(매화) — 문양 매화
        ink = (DK_SLATE, DK_SLATE_HI)
        orn = (mix(BONE_DK, BLADE_DIM, 0.5), mix(BONE_MID, BLADE_LIT, 0.45))
        gold = (BLADE_SPINE, BLADE_MID)
        mhwa = mix(CRIM_VHI, PLUM_HI, 0.5)        # 매화홍 — 유일한 악센트
        orb = None
        acc = (mix(mhwa, CRIM_V, 0.45), mhwa)
        blade = _p7_blade_style(edge=BLADE_HI, body=BLADE_MID, ridge=BLADE_LIT,
                                tipc=BLADE_HI, edge_rel=0.68)
    else:                    # C — 발광 신검: 장식 최소 · 옥백 검신 + 발광 테 · 기운이 주인공
        ink = (DK_SLATE, DK_SLATE_HI)
        orn = (mix(JADE_V, MOON_V, 0.45), MOON_V)
        gold = (BLADE_SPINE, BLADE_MID)
        orb = (JADE_V, MOON_V, BLADE_HI)
        acc = (mix(JADE_V, MOON_V, 0.3), mix(JADE_VHI, MOON_V, 0.55))
        blade = _p7_blade_style(edge=mix(JADE_VHI, MOON_V, 0.5), body=MOON_V,
                                ridge=BLADE_HI, tipc=BLADE_HI, edge_rel=0.55)
    tipx = 17.4 if minimal else 17.8
    broot = 4.9 if minimal else 7.0
    half = 1.5 if minimal else 1.35
    bw = _taperw(half, tip=0.7, slim=0.08)
    sh = []
    if minimal:              # 발광 테 — 어두운 옥 외피가 검신을 감싸고 칼끝 밖으로 잦아든다
        sh.append(_stroke([(broot - 0.4, 8.0), (tipx + 1.4, 8.0)],
                          lambda t: max(0.4, (half + 0.55) * (1.0 - 0.5 * t)),
                          _flat2(mix(JADE_V, DK_GREEN, 0.25), JADE_V, edge=0.99)))
    sh.append(_stroke([(broot, 8.0), (tipx, 8.0)], bw, blade))     # 날 — 조연 (전장 35~41%)
    # ─ 자루 — 조용한 유색 원통 + 띠 둘 + 물미 + 목띠 (길게 — 레퍼런스의 늘씬한 몸)
    sh.append(_stroke([(-10.4, 8.0), (-0.6, 8.0)], 0.82, _p7_grip_style(ink)))
    for bx_ in (-8.2, -2.9):                                               # 자루 띠 — 살짝 돌출
        sh.append(_stroke([(bx_, 7.0), (bx_, 9.0)], 0.34, _flat2(gold[0], gold[1], edge=0.62)))
    sh.append(_stroke([(-11.35, 8.0), (-10.55, 8.0)], 1.0, _flat3(gold[0], gold[1])))  # 물미 관
    sh.append(_p7_orb(-12.25, 8.0, 0.72, acc[0], acc[1], MOON_V))                      # 물미 구슬
    sh.append(_stroke([(-0.45, 8.0), (0.75, 8.0)], 1.05, _flat3(gold[0], gold[1])))    # 목띠
    # ─ 머리부 의장 — 큰 형태 3~4개, 각각 분리 · 구멍이 시원하다
    ring_r, ring_w = (2.4, 0.6) if minimal else (2.85, 0.62)
    sh.append(_ring_orn(hc[0], hc[1], ring_r, ring_w, orn[0], orn[1]))     # ① 대형 필리그리 고리
    if not minimal:
        sh.append(_stroke(_arcpts(8.85, 8.0, 1.6, 95, 265, 12), 0.5,
                          _flat3(orn[0], orn[1])))                         # ② 앞 반고리 — 날 뿌리
        sh.append(_p7_feather((1.4, 10.9), (-0.6, 12.2), (-3.0, 12.9), 0.6, orn[0], orn[1]))
        sh.append(_p7_feather((2.8, 11.1), (1.4, 12.4), (-0.4, 13.1), 0.48, orn[0], orn[1]))
        sh.append(_p7_feather((1.5, 5.1), (-0.3, 4.0), (-2.4, 3.3), 0.54, orn[0], orn[1]))  # ③ 관/깃 3
    if orb is not None:
        sh.append(_p7_orb(hc[0], hc[1], 1.45 if not minimal else 1.35,
                          orb[0], orb[1], orb[2]))                         # ④ 보주 — 광점
    else:                    # B — 보주 자리에 매화 (다섯 잎 + 금 꽃술: 문양이 곧 보주)
        for i in range(5):
            a = math.radians(90 + i * 72)
            px_, py_ = hc[0] + 0.95 * math.cos(a), hc[1] + 0.95 * math.sin(a)
            sh.append(_stroke([(px_, py_), (px_ + 0.01, py_)], 0.62, _flat2(acc[0], acc[1])))
        sh.append(_stroke([hc, (hc[0] + 0.01, hc[1])], 0.44, _flat2(GOLD_V, GOLD_VHI)))
    # ─ 떠 있는 파편 — 머리 주변 폭발 방사 (본체 비접촉)
    sh += _p7_burst(hc[0], hc[1], acc[0], acc[1], n=5 if minimal else 4,
                    k=1.1 if minimal else 1.0, petal=(variant == "B"))
    extras = [_cross(-10.4, -0.6, 0.78),
              _acc(-10.95, 8.0, 0.5, _PATCH_BRASS, zhalf=0.6),
              _acc(0.15, 8.0, 0.55, _PATCH_BRASS, zhalf=0.7),
              _acc(hc[0], hc[1], 0.7, _PATCH_BRASS, zhalf=0.8)]
    box = (-13.8, 2.6, (tipx + 1.9) if minimal else (tipx + 1.1), 13.8)
    return box, sh, extras, ink


_PILOT7 = {}     # 7차 파일럿 배선은 닫혔다. ★9차 (사용자 결정 번복): 8차가 B 를 전파했으나
                 # 렌더(pilot_ABC.png) 실사 후 "A가 더 좋아 보입니다" — **A안 확정**, 9차가
                 # A 언어(유색 자루·뼈백+금 의장·어두운 심+뼈백 사면+악센트 인선 날)를 57자루에
                 # 재전파했다 (+2차 레퍼런스 kmc: 신병 원위 원소 재질·마병 진홍 수정날 —
                 # _steel_style 9차 절). _spec_sword_pilot7 은 시안 렌더 기록용으로 남는다.


_DECOR = ("bobyeong", "sinbyeong", "mabyeong")   # 보병 왕관 — 누적 (Codex §5 구조 장식)


def _knot(cx, cy, r):
    """온백 매듭 — 보병 왕관의 점 장식 (놋과 다른 재질로 읽히는 따뜻한 백색 — Codex §4)."""
    onbaek = mix(BLADE_HI, FIT_HI, 0.24)
    return _pn(_vd(_stroke([(cx, cy), (cx + 0.01, cy)], r,
                           _flat_style(mix(onbaek, FIT_MID, 0.45), onbaek)),
                   _vstud(4.0)), "매듭")


def _gem(cx, cy, r, ink):
    """보석/옥 상감 — 플랫 3존 구슬 (심 백광 / 몸 / 테). 좋아질수록 하나씩 는다."""
    if _R16 == "full":                             # 16차 선협 — 발광 징 (상감의 기환 번역)
        return _stroke([(cx, cy), (cx + 0.01, cy)], r,   # calm 은 현행 상감 그대로
                       _r16_orb_style(ink, mix(ink, MIST, 0.55)))
    core = mix(ink, MIST, 0.55)
    def style(t, d0, w, side, mx, my):
        rel = d0 / w
        if rel < 0.38:
            return mix(core, MIST, 0.4)
        return core if rel < 0.72 else ink
    return _stroke([(cx, cy), (cx + 0.01, cy)], r, style)


# ─── 시트 합성 — 균일 배율로 _SHEET 캔버스(보조 스트립 아래)에 앉힌다 (알파는 SDF 가장자리 램프) ─
# 10차: 픽셀마다 **이긴 셰이프의 부위 깊이**(_vd 태그)를 함께 기록한다.
# 11차 (볼륨 조립): 사용자 재실측 "두께만 늘어났는데 — 실제 블럭으로 구성된 입체 형태여야
# 합니다" — 10차의 균일 깊이 부조(浮彫)를 **부위별 단면 태그** (깊이, z오프셋, 회전) 로
# 확장한다. 부위마다 단면·z위치·두께가 다르다: 자루=폭≈깊이 정사각 봉 · 날=인선 얇고
# 능선 두꺼운 3층(단면 마름모) · 의장=날보다 앞뒤로 불룩 · 보주=깊이=지름 · 수실=앞/뒤
# z 평면 · 파편=서로 다른 z(±2~4)+개별 기울기 (MC 회전 제약: 한 축 {0, ±22.5, ±45}).
_VD_BLADE = None              # 날 — 무태그: 복셀러가 실루엣 가장자리 거리로 3층을 깎는다 (11차)
# 17차 (Codex 무기 3D 감사 §6) — 태그 4번째 자리 = **역할** (body | attached | float).
# 본체 extent(= hand/fp 스케일 분모)는 깊이 문턱이 아니라 역할로 가른다: 얇은 필리그리·깃
# (단면 0.7~0.9)도 본체에 붙은 구조(attached)다 — 깊이는 역할이 아니다. float(수실·파편)만
# 분모에서 뺀다. 깊이 튜닝이 display 크기를 우연히 바꾸지 않는 계약.
_VD_ORN = (3.4, 0.0, None, "body")    # 의장·코등이·물미 관·목띠 — 날 평면보다 ±z 로 불룩
_VD_STUD = (2.6, 0.0, None, "body")   # 등급 고리 — 봉(자루)을 감고 z 로도 튀는 테
_VD_DRAPE = (0.7, 0.9, None, "float") # 수실·홍영·끈 — 앞 z 평면의 얇은 천 (뒤는 _vz(-0.9))
_VD_SHARD = (0.8, 2.4, None, "float") # 떠 있는 파편 — 본체 앞 z 평면 (_vshard 가 개별 포즈)
_BLADE_D = (0.7, 1.25, 1.7)   # 날 3층 — 인선(가장자리 1px)/사면/능선 두께 【잠정 · 팩 현행】
# ═══ V2-W 19차 — 볼록 단면(렌즈) 다단 프로파일 【사용자 정밀 스펙 · _R19 게이트】 ═══
# 사용자: "Do NOT simply increase thickness. Rebuild ... volumetric convex cross-section
#   instead of a flat slab ... multiple cuboids with gradually changing depth. Center ridge
#   thickest. Cutting edges taper smoothly to thin. Silhouette must remain the same while the
#   volume becomes rounded. Forged steel, not a flat metal plate."
# 3층(0.7/1.25/1.7)은 너무 거칠다 — **7단 렌즈**로 세분: 실루엣 가장자리 거리를 blade 전역
# 최대로 정규화(0 인선 → 1 능선)해 단조 증가 깊이를 준다. **정면 폭(xy 실루엣) 불변** — z
# 두께만 볼록해진다 (능선 최두께 · 인선 최소 · 계단이 매끄럽게 볼록). 팩(_R19=False)은 3층 유지.
# 21차 (조율자 토대 수리 — 사용자: "그냥 두께만 늘린 느낌"): 3층 볼록은 128px 날 폭에서도
# 계단이 성겨 "판(板)"으로 읽혔다. 7단 렌즈를 **기본으로 승격**(_BLADE_CONVEX_ON — _R19 게이트
# 에서 풂: R20/R21/calm base 전부에 적용)하고, 능선↔인선 **깊이 대비를 키운다** (실루엣 xy 불변,
# z 두께만 — 능선 1.7→1.9 두껍게 · 인선 0.4→0.26 얇게 · 계단이 능선 쪽에서 촘촘해 볼록이 굳다).
_BLADE_CONVEX = (0.26, 0.48, 0.74, 1.04, 1.36, 1.64, 1.9)   # 인선→능선 단조 (벼려 부푼 렌즈)
_BLADE_CONVEX_ON = True        # ★기본 승격 — 날은 언제나 7단 볼록 렌즈 (두께만 늘린 슬래브 금지)


def _blade_convex_depth(frac):
    """정규화 가장자리 거리(0 인선 → 1 능선)를 7단 볼록 렌즈 깊이로 (단조 증가).
    frac^0.72 로 능선 쪽을 완만하게 — 마름모(선형)가 아니라 **둥근 볼록** (단조된 강철)."""
    n = len(_BLADE_CONVEX)
    return _BLADE_CONVEX[max(0, min(n - 1, int(round((frac ** 0.72) * (n - 1)))))]


def _vrole(tag, default="body"):
    """태그의 역할 (17차) — 4자리가 없는 옛/익명 3튜플은 default 로 읽는다."""
    return tag[3] if len(tag) > 3 else default


def _oct(tag):
    """모깎기(팔각) 표식 — 태그에 "oct" 5번째 자리를 단다. 복셀러가 그 부위의 z 깊이를
    가장자리 링에서 단계로 줄여 **정사각 기둥의 네 모를 죽인다**(팔각 단면 → 원통처럼 읽힘).
    21차-b (사용자: "칼날 빼곤 단면이 다 사각기둥 → 원통/입체감을"). V2-F 술독 팔각화의
    복셀 번역 — 45° 겹큐보이드 대신 거리-단계 깊이로 모를 깎는다 (원소 폭증 없이)."""
    return tuple(tag[:4]) + ("oct",)


def _vrod(hw):
    """봉/자루 — 폭≈깊이 **팔각(모깎기)** 막대 (옆에서도 봉·원통으로 읽힌다). hw = 획 반폭.
    21차: 깊이 2.0·hw→2.2·hw (획 램프 물어도 깊이 ≥ 정면 폭). 21차-b: "oct" — 가장자리 z
    모를 죽여 사각기둥 → 팔각 단면 (사용자: "이미지 늘려 입체로 만든 것 같다" 수리)."""
    return _oct((round(max(2.2 * hw, 1.4), 3), 0.0, None, "body"))


def _vorb(r):
    """보주·구슬·꽃잎 — 깊이=지름 볼륨 (z 로 튀는 공)."""
    return (round(max(2.0 * r, 1.9), 3), 0.0, None, "body")


def _vstud(d):
    """징·상감·문양 메달 — 명시 깊이 (숙주 볼륨보다 깊어 z 로 튄다)."""
    return (round(d, 3), 0.0, None, "body")


def _vz(tag, z):
    """태그의 z 평면 이동 — 겹수실의 앞/뒤, 파편의 서로 다른 z. 역할은 승계한다 (17차)."""
    return (tag[0], round(z, 3), tag[2], _vrole(tag))


def _vrot(tag, ang, ox, oy):
    """볼륨을 y축으로 기울인다 (origin=(ox, oy, 8+z오프셋)) — 필리그리 고리·깃이
    날 평면에서 **벌어진다**. MC 제약: 원소 회전은 한 축 {0, ±22.5, ±45} 뿐.
    17차: 익명 3튜플(필리그리 고리 1.1 · 깃 0.9)의 기본 역할은 attached — 본체에 붙은
    구조라 extent 분모에 든다 (Codex §6: 깊이 ≤0.9 문턱이 이들을 부유물로 오분류했다)."""
    return (tag[0], tag[1], ("y", ang, round(ox, 3), round(oy, 3)),
            _vrole(tag, default="attached"))


_SHARD_POSE = ((2.4, 22.5), (-2.8, -22.5), (3.4, 45.0), (-3.8, -45.0), (2.9, 22.5))


def _vshard(i, px_, py_):
    """방사 파편 i — 서로 다른 z 평면(±2~4) + 개별 y축 기울기 (떠 있음이 옆에서도 읽힌다)."""
    z, ang = _SHARD_POSE[i % len(_SHARD_POSE)]
    return (0.8, z, ("y", ang, round(px_, 3), round(py_, 3)), "float")


def _vd(obj, d):
    """복셀 단면 태그 (10차 신설 · 11차 확장) — shape 하나/리스트에 (깊이, z, 회전)을 단다."""
    for f in (obj if isinstance(obj, list) else [obj]):
        f._vdepth = d
    return obj


# ═══ V2-W 17차(정본화) — 부위 이름 태그 + 연속성 정본 (weapon_anatomy_canon.md 와 1:1) ═══
def _pn(obj, name):
    """정본 부위명 태그 — 모든 본체 셰이프는 이름을 가진다 (이름 없는 픽셀 = 빌드 실패).
    이름은 PART_CANON 등록제: 문서(weapon_anatomy_canon.md §1~§2)와 기계가 같은 표를 본다."""
    for f in (obj if isinstance(obj, list) else [obj]):
        f._part = name
    return obj


# 부위 정본 등록부 — docs/design/weapon_anatomy_canon.md §1(공용)·§2(계열)·§4(기운 층) 그대로.
# 여기 없는 이름을 _pn 에 쓰면 _continuity_check 가 죽인다 (문서·코드 표류 방지).
PART_CANON = frozenset((
    # 공용 — 자루 벌 (§1)
    "물미구슬", "물미관", "자루", "등급고리", "드리개", "수실", "수실구슬", "혈적",
    "코등이", "상감", "목띠",
    # 의장 (9차 A안 물리 의장 · §2)
    "소켓띠", "의장고리", "의장브래킷", "의장깃", "보주", "파편",
    # 선협 기운 층 (§4 — calm/full 번역)
    "기환고리", "내단", "기운자락", "부유광점", "기맥",
    "뇌전",                                        # 21d — 검을 휘감는 float 뇌전 볼트 (기 대신 · §4-뇌전)
    # 계열 고유 (§2)
    "검신", "도신", "비수날",                      # 검·도·비수
    "창날", "소켓", "홍영", "준",                  # 창
    "부채날", "수염", "도끼목", "폴",              # 부
    "낫날", "끝휨", "슴베목", "매듭",              # 겸 (+개방 매듭)
    "월아", "삽날", "삽목", "달목",                # 월아산
    "구신", "갈고리", "미늘",                      # 구
    "손등판", "마디", "엄지", "손목띠", "띠끝", "손등능선",   # 권갑
    # 명병 문양·전용 (§2 명병)
    "매화잎", "꽃술", "태극", "답운", "청월", "파도", "봉", "봉마디", "끈꼬리",
))

# R4 부위 순서 — 계열별 (a, b) 쌍: 두 부위가 다 있으면 무게중심 x(a) < x(b) 여야 한다.
# (물미 < 자루 < 코등이 < 날 — 병기의 해부학적 순서. 정본 §3-R4)
_ORDER_SWORDLIKE = (("물미구슬", "물미관"), ("물미관", "자루"), ("자루", "코등이"),
                    ("자루", "의장고리"), ("자루", "기환고리"), ("코등이", "검신"),
                    ("의장고리", "검신"), ("기환고리", "검신"), ("자루", "검신"))
_PART_ORDER = {
    "sword": _ORDER_SWORDLIKE,
    "dao": (("물미관", "자루"), ("자루", "코등이"), ("코등이", "도신"),
            ("의장고리", "도신"), ("기환고리", "도신")),
    "dagger": (("물미관", "자루"), ("자루", "코등이"), ("코등이", "비수날"),
               ("의장고리", "비수날")),
    "spear": (("준", "자루"), ("자루", "소켓"), ("소켓", "창날")),
    "bu": (("물미관", "자루"), ("자루", "도끼목"), ("도끼목", "부채날")),
    "gyeom": (("물미관", "자루"), ("자루", "슴베목"), ("슴베목", "낫날")),
    "wolasan": (("삽날", "삽목"), ("삽목", "자루"), ("자루", "달목"), ("달목", "월아")),
    "gu": (("물미관", "자루"), ("자루", "코등이"), ("코등이", "구신"), ("구신", "갈고리")),
    "gauntlet": (("손목띠", "손등판"), ("손등판", "마디")),
    "gaebang": (),                                 # 봉 — 축 순서 부위가 없다 (매듭은 봉 위)
}


def _compose(shapes, box, salt, grade, wood=False, mark_pair=None, ink=None):
    """box = (mx0, my0, mx1, my1) 모델 좌표. → (rows _SHEET², k 배율, dep 깊이장,
    parts 부위명장, qi 기맥 오버레이 픽셀 집합).
    ink — 7차 파일럿: 감김 보조 스트립도 유색 랩을 따른다 (None 이면 종전 그대로).
    17차 — 오버레이 셰이프 (f._overlay=True · 기운 층 전용): 알파·깊이 **소유권이 없다** —
    이미 불투명한 픽셀의 색만 섞는다 (스타일이 돌려준 색의 α = 혼합 강도). 그래서
    "기운 층은 색·빛만, 구조는 계열이 소유"가 코드 구조로 보장된다 (정본 §4)."""
    mx0, my0, mx1, my1 = box
    k = min(_SHEET / (mx1 - mx0), (_SHEET - _CANVAS_Y0) / (my1 - my0))
    rows = [[T] * _SHEET for _ in range(_SHEET)]
    dep = [[None] * _SHEET for _ in range(_SHEET)]
    parts = [[None] * _SHEET for _ in range(_SHEET)]
    qi = {}
    solid = [f for f in shapes if not getattr(f, "_overlay", False)]
    over = [f for f in shapes if getattr(f, "_overlay", False)]
    # 17차 — 본체 우선 규칙: float(수실·파편·광점 — 역할 태그)은 **본체를 덮지 못한다**.
    # 8차 정본 "파편은 본체와 떨어져 떠 있다"의 집행이다 — 13차 깃 압축 뒤 파편이 깃 위를
    # 지나며 본체 픽셀을 훔쳤고(복셀에서 깃이 두 동강 — R1 실측), 겹치면 본체가 이긴다.
    # parts(해부 귀속)는 **가장 깊은** 본체 셰이프의 이름이다 — 이웃 부위의 램프가 색을
    # 이겨도 속살의 주인은 바뀌지 않는다 (R2 가 참 경계를 보는 눈).
    roles = [(f, "float" if (getattr(f, "_vdepth", None) is not None
                             and _vrole(f._vdepth) == "float") else "body") for f in solid]
    for sy in range(_CANVAS_Y0, _SHEET):
        for sx in range(_SHEET):
            mx = mx0 + (sx + 0.5) / k
            my = my1 - (sy - _CANVAS_Y0 + 0.5) / k
            dmin = 1e9
            bcol = fcol = None                     # (색, 깊이 태그) — 본체/부유 마지막 승자
            anat_d, anat_pn = 1e9, None            # 해부 귀속 — 가장 깊은 본체 셰이프
            for f, role in roles:
                d, c = f(mx, my)
                if d < dmin:
                    dmin = d
                if c is not None and d < 1.0 / k:
                    pick = (c, getattr(f, "_vdepth", _VD_BLADE), getattr(f, "_part", None))
                    if role == "float":
                        fcol = pick                # 부유물끼리는 뒤가 이긴다
                    else:
                        bcol = pick                # 본체끼리도 뒤가 이긴다 (그리는 순서 = 층)
                        if d < anat_d:
                            anat_d, anat_pn = d, pick[2]
            win = bcol or fcol                     # 본체 > 부유 (본체 우선)
            if win is None:
                continue
            col, vd, pn = win
            ds = dmin * k                          # 시트 px 단위 경계 거리
            a = 255 if ds < -0.75 else int(max(0.0, min(255.0, (0.45 - ds) / 1.2 * 255.0)))
            if a <= 8:
                continue                           # 축 ㉓ ⓓ — 이분이 본체, 램프는 가장자리 1px대
            rows[sy][sx] = (col[0], col[1], col[2], 255 if a >= 248 else a)
            dep[sy][sx] = vd
            parts[sy][sx] = anat_pn if bcol else pn
            if over and bcol:                      # ─ 기운 오버레이 — 본체 픽셀만 물들인다
                for f in over:
                    d, c = f(mx, my)
                    if c is not None and d < 1.0 / k:
                        base = rows[sy][sx]
                        m = mix(base, (c[0], c[1], c[2], base[3]), c[3] / 255.0)
                        rows[sy][sx] = (m[0], m[1], m[2], base[3])
                        qi[(sx, sy)] = getattr(f, "_part", "기맥")
    _fill_patches(rows, salt, grade, wood, mark_pair, ink=ink)
    _alpha_snap(rows)          # 패치가 선 뒤에 잰다 — 스냅의 눈과 감사의 눈이 같은 판을 봐야 한다
    return rows, k, dep, parts, qi


def _alpha_snap(rows):
    """알파 위생 (축 ㉓ⓓ) — 투명(α≤8)에서 체비쇼프 2px 밖의 중간 알파는 **내부 안개**다:
    셰이프 사이 좁은 틈의 램프가 그렇게 남는다. 실질 내부이므로 불투명으로 승격한다 (결정론)."""
    transp = {(x, y) for y in range(_SHEET) for x in range(_SHEET) if rows[y][x][3] <= 8}
    for y in range(_SHEET):
        for x in range(_SHEET):
            a = rows[y][x][3]
            if 8 < a < 248 and not any((x + dx, y + dy) in transp
                                       for dx in (-2, -1, 0, 1, 2) for dy in (-2, -1, 0, 1, 2)):
                c = rows[y][x]
                rows[y][x] = (c[0], c[1], c[2], 255)
    return rows


def _fill_patches(rows, salt, grade, wood, mark_pair, ink=None):
    """보조 스트립 (0..5행) — 감김 윗면 + 놋/고리/문양 패치. 60..63열은 투명(평판의 테).
    ink — 7차 파일럿: 유색 랩 4보 문법(_grip_style ink 분기와 같은 결) · 플랫(노이즈 없음)."""
    mid = (_CANVAS_Y0 - 1) / 2.0                   # 스트립 세로 중앙 (64px: 2.5 · 128px: 5.5)
    for sy in range(0, _CANVAS_Y0):
        band = sy // _PXS                          # 13차(64px)의 행 번호 — 톤 문턱은 그 판 그대로
        for sx in range(*_PATCH_GRIP[::2]):
            rel = abs(sy - mid) / mid
            fu = (sx / (3.0 * _PXS)) % 1.0             # 15차 — 감김 실낱 (윗면도 같은 결)
            if ink:
                c = ink[1] if band <= 2 else ink[0]        # 조용한 원통 — 위가 밝다 (플랫)
                if _D15 and fu < 0.2:
                    c = mix(c, WPN_OUT, 0.4)               # 감김 틈 그늘
                elif _D15 and abs(fu - 0.58) < 0.1:
                    c = mix(c, WPN_OUT, 0.16)              # 실 가닥 경계
                if rel > 0.6:
                    c = mix(c, WPN_OUT, (rel - 0.6) * 1.6)
                rows[sy][sx] = c
                continue
            if wood:
                c = GRIP_MID if band <= 2 else GRIP_DIM
            else:
                c = GRIP_MID if band <= 2 else GRIP_DIM        # 가죽 — 플랫 원통 (8차)
            if _D15 and fu < 0.2:
                c = mix(c, GRIP_DARK, 0.45)                # 감김 틈 그늘
            elif _D15 and abs(fu - 0.58) < 0.1:
                c = mix(c, GRIP_DARK, 0.18)                # 실 가닥 경계
            if rel > 0.6:
                c = mix(c, GRIP_DARK, (rel - 0.6) * 1.6)
            rows[sy][sx] = c
        for sx in range(*_PATCH_BRASS[::2]):
            c = GOLD_FIT_HI if band <= 1 else (GOLD_FIT_MID if band <= 3 else GOLD_FIT_DIM)
            if grade == "beomcheol":               # 금 패치 (9차 A안 — 악센트 상자가 문다)
                c = mix(c, BLADE_DIM, 0.6)
            rows[sy][sx] = c
        for sx in range(*_PATCH_RING[::2]):
            rows[sy][sx] = RING_HI if band <= 2 else RING_MID
        for sx in range(*_PATCH_MARK[::2]):
            if mark_pair:
                lo, hi = mark_pair
                cx = 57.5 * _PXS + (_PXS - 1) * 0.5    # 64px 의 (57.5, 2.5) 원판 중심을 배율 이동
                rows[sy][sx] = hi if math.hypot(sx - cx, sy - mid) < 2.0 * _PXS else lo
            else:
                rows[sy][sx] = mix(FIT_MID if grade != "beomcheol" else BLADE_DIM, WPN_OUT, 0.3)
    if _R16 == "full":                             # 16차 선협 — 보조 스트립(윗면 UV)도 기환 번역
        g = _QI_GRADE[grade][0]                    # (calm 은 현행 스트립 그대로)
        seam = CRIM_VHI if grade == "mabyeong" else mix(MOON_V, JADE_V, 0.35)
        base_lo = mix(_QI_INK, ink[0], 0.35) if ink else _QI_INK
        base_hi = mix(base_lo, ink[1] if ink else _QI_BODY, 0.3)
        for sy in range(0, _CANVAS_Y0):
            band = sy // _PXS
            for sx in range(*_PATCH_GRIP[::2]):
                c = base_hi if band <= 2 else base_lo
                if g > 0.05 and (sx // _PXS) % 5 == 0:
                    c = mix(c, seam, 0.35)             # 감김 기맥 실 — 윗면도 같은 결
                rows[sy][sx] = c
            for sx in range(*_PATCH_BRASS[::2]):
                c = _QI_METAL[1] if band <= 3 else _QI_METAL[0]
                if g > 0.3 and band in (2, 3):
                    c = mix(c, seam, 0.35)             # 기물 심선
                rows[sy][sx] = c
            for sx in range(*_PATCH_RING[::2]):
                rows[sy][sx] = mix(MOON_V, JADE_V, 0.3) if band <= 2 \
                    else mix(JADE_V, WPN_OUT, 0.45)


# ═══ V2-W 10차 — 복셀 압출 (사용자 인게임 실측: "3인칭·1인칭에서는 3D 모델이 아닙니다")
# 3차의 평판+알파 문법은 정면(GUI)에선 그림이 서지만 손에 들면 옆면 없는 종잇장이다.
# 레퍼런스 팩(TWC·kmc)의 실체는 **텍스처 불투명 픽셀을 픽셀 단위 두께로 압출한 복셀 3D**
# (바닐라 item/generated 가 손에서 보이는 그 픽셀 계단의 고해상판). 그래서 시트의 불투명
# 영역을 행/열 그리디 병합 직육면체로 굽는다 — 페인트·색·사다리·의장 구도는 9차 그대로,
# **기하만 평판 → 복셀**. 옆면(두께면)은 해당 가장자리 픽셀 열/행의 UV 를 물어 가장자리
# 색이 깊이로 이어진다 — 옆면이 밝은 배색이면 스티커고, 가장자리 색이면 조각이다.
def _vox_elem(px0, py0, px1, py1, tag, k, box):
    """시트 픽셀 사각 [px0,px1)×[py0,py1) → 단면 태그 (깊이 d, z오프셋 zo, 회전 rot) 의
    직육면체. 남/북 = 제 UV 영역 · 상/하/동/서 = 가장자리 1px 행/열을 깊이로 늘인 UV
    (가장자리 색이 깊이로 이어진다 — 10차 문법 유지). rot 는 MC 원소 회전 한 축.
    17차: 태그 4자리(역할)는 extent 전용 — 기하에는 앞 3자리만 쓴다."""
    d, zo, rot = tag[0], tag[1], tag[2]
    mx0, my0, mx1, my1 = box
    fx0, fx1 = round(mx0 + px0 / k, 3), round(mx0 + px1 / k, 3)
    fy0 = round(my1 - (py1 - _CANVAS_Y0) / k, 3)
    fy1 = round(my1 - (py0 - _CANVAS_Y0) / k, 3)
    z0, z1 = round(8 + zo - d / 2, 3), round(8 + zo + d / 2, 3)
    u0, v0, u1, v1 = px0 * _UVPX, py0 * _UVPX, px1 * _UVPX, py1 * _UVPX
    e = 0.02                                       # 픽셀 경계 블리딩 방지 (패치 UV 와 같은 여유)
    el = {"from": [fx0, fy0, z0], "to": [fx1, fy1, z1],
          "faces": {"south": {"texture": "#1", "uv": [u0, v0, u1, v1]},
                    "north": {"texture": "#1", "uv": [u1, v0, u0, v1]},
                    "up": {"texture": "#1", "uv": [u0, v0 + e, u1, v0 + _UVPX - e]},
                    "down": {"texture": "#1", "uv": [u0, v1 - _UVPX + e, u1, v1 - e]},
                    "west": {"texture": "#1", "uv": [u0 + e, v0, u0 + _UVPX - e, v1]},
                    "east": {"texture": "#1", "uv": [u1 - _UVPX + e, v0, u1 - e, v1]}}}
    if rot:
        el["rotation"] = {"origin": [rot[2], rot[3], round(8 + zo, 3)],
                          "axis": rot[0], "angle": rot[1]}
    return el


def _voxelize(rows, dep, k, box, alpha_min=8, _return_tags=False):
    """볼륨 조립기 (11차) — 캔버스(6..63행)의 불투명 픽셀(α>alpha_min)을 **단면 태그별**
    행 우선 그리디 병합 직육면체로. 결정론 — 스캔 순서 고정, 난수 없음.
    태그 없는 픽셀(날)은 실루엣 가장자리(투명)까지의 체비쇼프 거리로 3층을 받는다 —
    인선 1px 얇게 / 사면 / 능선 두껍게 (단면 마름모 근사: "벼린 날"이 옆에서 읽힌다).
    같은 (깊이, z, 회전) 이웃만 한 상자로 합친다 (태그 경계가 곧 부위의 z 계단이다)."""
    solid = [[y >= _CANVAS_Y0 and rows[y][x][3] > alpha_min for x in range(_SHEET)]
             for y in range(_SHEET)]

    def _d(g, y, x):                               # 캔버스 밖 = 투명 (거리 0)
        return g[y][x] if 0 <= y < _SHEET and 0 <= x < _SHEET else 0
    dist = [[255 if solid[y][x] else 0 for x in range(_SHEET)] for y in range(_SHEET)]
    for y in range(_SHEET):                        # 체비쇼프 거리 변환 — 두 패스
        for x in range(_SHEET):
            if solid[y][x]:
                dist[y][x] = min(dist[y][x], 1 + min(_d(dist, y, x - 1), _d(dist, y - 1, x - 1),
                                                     _d(dist, y - 1, x), _d(dist, y - 1, x + 1)))
    for y in range(_SHEET - 1, -1, -1):
        for x in range(_SHEET - 1, -1, -1):
            if solid[y][x]:
                dist[y][x] = min(dist[y][x], 1 + min(_d(dist, y, x + 1), _d(dist, y + 1, x + 1),
                                                     _d(dist, y + 1, x), _d(dist, y + 1, x - 1)))
    # 19차 볼록 프로파일(_R19): 날(무태그) 픽셀 깊이를 blade 전역 최대 거리로 정규화해
    # 7단 렌즈로 준다 (능선 최두께 · 인선 얇음 · 정면 폭 불변 · z만 볼록). 팩은 3층 유지.
    dmax = 0
    convex = _BLADE_CONVEX_ON or _R19             # 21차 — 7단 렌즈를 기본 승격 (R20/R21/calm base)
    if convex:
        for y in range(_CANVAS_Y0, _SHEET):
            for x in range(_SHEET):
                if solid[y][x] and dep[y][x] is None:
                    dmax = max(dmax, dist[y][x])
    # 21b 팔각(모깎기) — oct 부위마다 **연결 성분별 국소 최대 거리로 정규화**해 중심=full·가장자리
    #   모를 3단으로 죽인다 (봉·코등이·물미관 두께가 달라도 각자 팔각 단면. 고정 px 문턱은 얇은
    #   봉에서 안 먹혔다). frac=거리/국소최대 → {≥0.6 full · ≥0.3 0.72 · else 0.45}.
    oct_depth = {}
    oct_pix = [(x, y) for y in range(_CANVAS_Y0, _SHEET) for x in range(_SHEET)
               if solid[y][x] and dep[y][x] is not None
               and len(dep[y][x]) > 4 and dep[y][x][4] == "oct"]
    for comp in _components(set(oct_pix)):
        lm = max(dist[y][x] for x, y in comp) or 1
        for x, y in comp:
            frac = dist[y][x] / lm
            oct_depth[(x, y)] = 1.0 if frac >= 0.6 else (0.72 if frac >= 0.3 else 0.45)
    tags = [[None] * _SHEET for _ in range(_SHEET)]
    for y in range(_CANVAS_Y0, _SHEET):
        for x in range(_SHEET):
            if not solid[y][x]:
                continue
            t = dep[y][x]
            if t is not None:
                if (x, y) in oct_depth:            # 팔각 — 중심 full → 가장자리 모 죽임 (정규화)
                    tags[y][x] = (round(t[0] * oct_depth[(x, y)], 3), t[1], t[2], t[3])
                else:
                    tags[y][x] = t
            elif convex and dmax > 0:
                tags[y][x] = (_blade_convex_depth(dist[y][x] / dmax), 0.0, None)
            else:
                # 날 3층 밴딩 — 층 폭은 13차(64px)의 물리 치수를 지킨다: 128px 에선
                # 가장자리 2px 가 인선 (한 알이 절반이 됐으니 두 알이 같은 폭이다)
                band = (dist[y][x] + _PXS - 1) // _PXS
                tags[y][x] = (_BLADE_D[min(band, len(_BLADE_D)) - 1], 0.0, None)
    used = [[False] * _SHEET for _ in range(_SHEET)]
    elems = []
    for y in range(_CANVAS_Y0, _SHEET):
        for x in range(_SHEET):
            if not solid[y][x] or used[y][x]:
                continue
            t = tags[y][x]
            x1 = x
            while (x1 + 1 < _SHEET and solid[y][x1 + 1] and not used[y][x1 + 1]
                   and tags[y][x1 + 1] == t):
                x1 += 1
            y1 = y
            while (y1 + 1 < _SHEET
                   and all(solid[y1 + 1][xx] and not used[y1 + 1][xx]
                           and tags[y1 + 1][xx] == t
                           for xx in range(x, x1 + 1))):
                y1 += 1
            for yy in range(y, y1 + 1):
                for xx in range(x, x1 + 1):
                    used[yy][xx] = True
            elems.append(_vox_elem(x, y, x1 + 1, y1 + 1, t, k, box))
    if _return_tags:
        return elems, tags, dep
    return elems


# ─── 평판·악센트 원소 — 【10차 폐지 · 유산 기록】 복셀 압출이 본판·감김 십자판·악센트
#     상자를 전부 대체했다 (깊이 태그 _VD_* 가 돌출을 잇는다). 함수는 3~9차의 기록으로 남는다.
def _plate(box, k, z0=7.65, z1=8.35):
    """본판 — 앞뒤 큰 면만 그림을 문다. 테(상하좌우)는 투명 패치: 실루엣은 알파가 깎는다."""
    mx0, my0, mx1, my1 = box
    uv = [0.0, _CANVAS_Y0 / 4.0,
          round((mx1 - mx0) * k / 4.0, 3), round((_CANVAS_Y0 + (my1 - my0) * k) / 4.0, 3)]
    clear = _uvr(_PATCH_CLEAR)
    return {"from": [mx0, my0, z0], "to": [mx1, my1, z1],
            "faces": {"south": {"texture": "#1", "uv": uv},
                      "north": {"texture": "#1", "uv": [uv[2], uv[1], uv[0], uv[3]]},
                      "up": {"texture": "#1", "uv": clear},
                      "down": {"texture": "#1", "uv": clear},
                      "east": {"texture": "#1", "uv": clear},
                      "west": {"texture": "#1", "uv": clear}}}


def _cross(gx0, gx1, gw):
    """감김 십자판 — 본판과 직교하는 얇은 수평판: 자루가 어느 각도에서도 둥글게 읽힌다."""
    strip = [0.05, 0.1, 11.9, 1.4]
    clear = _uvr(_PATCH_CLEAR)
    return {"from": [gx0, 7.72, 8 - gw], "to": [gx1, 8.28, 8 + gw],
            "faces": {"up": {"texture": "#1", "uv": strip},
                      "down": {"texture": "#1", "uv": strip},
                      "north": {"texture": "#1", "uv": clear},
                      "south": {"texture": "#1", "uv": clear},
                      "east": {"texture": "#1", "uv": clear},
                      "west": {"texture": "#1", "uv": clear}}}


def _acc(cx, cy, half, patch, zhalf=None):
    """입체 악센트 — 패치를 문 작은 상자 (보스·고리·물미 구슬·매듭·징)."""
    zh = zhalf if zhalf is not None else half
    u = _uvr(patch)
    return {"from": [round(cx - half, 3), round(cy - half, 3), round(8 - zh, 3)],
            "to": [round(cx + half, 3), round(cy + half, 3), round(8 + zh, 3)],
            "faces": {f: {"texture": "#1", "uv": u}
                      for f in ("up", "down", "north", "south", "east", "west")}}


# ─── 자루·코등이 키트 (계열이 공유하는 문법 — 등급은 여기서 자란다) ───────────────
def _hilt_kit(salt, grade, rings, tassel, mab, gx0, gx1, gw, wood=False, tassel_k=1.0, ink=None,
              vein=True, traditional=False):
    """자루 한 벌 — 8차 B안: 조용한 묵청 원통(범철은 가죽) + 돌출 등급 고리 + 물미 관·구슬
    (신병은 악센트 구슬) + 신병 수실 + 마병 혈적. ink = _ink8 6튜플.
    vein=False — 17차 calm 정렬: 감김 기맥 실을 끄고 _qi_vein 오버레이가 잇는다.
    traditional=True (20차) — 마법 기운 실·발광 수실을 걷고 전통 원통·천 수실로 (파일럿 한정).
    → (shapes, extras, 좌/하 여백)."""
    L = gx1 - gx0
    acc = (ink[4], ink[5]) if ink else (TASSEL, TASSEL_HI)
    tas = lambda: _tassel_style(salt, acc=acc if (ink and not traditional) else None,
                                plain=traditional)
    sh = [_pn(_vd(_stroke([(gx0, 8.0), (gx1, 8.0)], gw,
                          _grip_style(salt, L, wood=wood,
                                      ink=ink[:2] if (ink and grade != "beomcheol") else None,
                                      grade=grade, acc=acc if ink else None, vein=vein,
                                      plain=traditional)),
                  _vrod(gw)), "자루")]             # 자루 — 폭≈깊이 정사각 봉 (11차)
    extras = []
    sh.append(_pn(_vd(_stroke([(gx0 - 0.9, 8.0), (gx0 - 0.15, 8.0)], gw + 0.2,
                              _brass_style(salt, grade)), _oct(_VD_ORN)), "물미관"))  # 팔각 (21b)
    if rings:
        gap = (L - 1.2) / (rings + 1)
        for i in range(rings):
            rx = gx0 + 0.6 + gap * (i + 1)
            sh.append(_pn(_vd(_stroke([(rx, 8 - gw - 0.45), (rx, 8 + gw + 0.45)], 0.34,
                                      _ring_style()), _VD_STUD), "등급고리"))   # z 로도 튄다
    lo_x, lo_y = gx0 - 1.4, 8 - gw - 1.0
    if grade == "bobyeong":                        # 보병 — 소형 드리개 (사다리: 장식이 자란다)
        pts = _bez((gx0 - 0.6, 8 - gw - 0.2), (gx0 - 1.4, 8 - gw - 1.5), (gx0 - 2.1, 8 - gw - 2.4), 6)
        sh.append(_pn(_vd(_stroke(pts, lambda t: (0.5 - 0.24 * t) * tassel_k,
                                  tas()),
                          _VD_DRAPE), "드리개"))
        lo_x, lo_y = gx0 - 3.0, 8 - gw - 3.2
    if tassel:                                     # 신병 — 겹수실 (앞/뒤 z 평면 · 11차) + 구슬
        pts = _bez((gx0 - 0.7, 8 - gw - 0.2), (gx0 - 2.0, 8 - gw - 2.2), (gx0 - 3.3, 8 - gw - 3.4), 8)
        sh.append(_pn(_vd(_stroke(pts, lambda t: (0.72 - 0.32 * t) * tassel_k,
                                  tas()),
                          _VD_DRAPE), "수실"))
        pts2 = _bez((gx0 + 0.25, 8 - gw - 0.15), (gx0 - 0.5, 8 - gw - 2.5), (gx0 - 1.3, 8 - gw - 4.1), 8)
        sh.append(_pn(_vd(_stroke(pts2, lambda t: (0.48 - 0.26 * t) * tassel_k,
                                  tas()),
                          _vz(_VD_DRAPE, -0.9)), "수실"))
        sh.append(_pn(_vd(_p7_orb(gx0 - 1.55, 8.0, 0.62, acc[0], acc[1], MOON_V),
                          _vorb(0.62)), "수실구슬"))
        lo_x, lo_y = gx0 - 4.4, 8 - gw - 5.2
    if mab:
        sh.append(_pn(_vd(_stroke([(gx0 - 0.55, 8.0), (gx0 - 0.56, 8.0)], 0.55,
                                  _flat_style(BLOOD, BLOOD_HI)), _VD_ORN), "혈적"))
    return sh, extras, lo_x, lo_y


def _guard_cross(salt, grade, bx, hlen, w, flare=0.9, manga=False):
    """가로대 코등이 — 검의 십자. 끝이 날 쪽으로 벌어진다 (flare). → (shapes, extras).
    21차 manga=True — 만화 셀 금(_r21_brass) + **날개 판 각도 배치**(±22.5/45° _vrot): 대담한
    아이콘화 코등이 (회전 부품 0 이던 것을 부활 — 조율자: 장식판은 각도로 벌어진다)."""
    bstyle = _r21_brass(salt, grade) if manga else _brass_style(salt, grade)
    sh = [_stroke([(bx, 8 - hlen), (bx, 8 + hlen)], w * 0.88, bstyle)]
    if flare:
        sh += [_stroke([(bx, 8 + hlen - 0.3), (bx + flare, 8 + hlen + 1.1)], w * 0.82, bstyle),
               _stroke([(bx, 8 - hlen + 0.3), (bx + flare, 8 - hlen - 1.1)], w * 0.82, bstyle)]
    if grade in _DECOR:                            # 보병 왕관 — 코등이 안쪽 제2익 (§5 검·비수)
        sh += [_stroke([(bx + 0.7, 8 + hlen * 0.34), (bx + 1.7, 8 + hlen * 0.5)], 0.28, bstyle),
               _stroke([(bx + 0.7, 8 - hlen * 0.34), (bx + 1.7, 8 - hlen * 0.5)], 0.28, bstyle)]
    _pn(_vd(sh, _oct(_VD_ORN)), "코등이")          # 코등이 몸 — 팔각 의장 (21b 모깎기 · ★상감 먼저)
    extra = []
    if manga:                                       # 날개 판 — y축 ±45° 로 벌어진 대담한 의장깃
        for s, ang in ((1, 45.0), (-1, -45.0)):
            wing = _stroke([(bx - 0.2, 8 + s * (hlen * 0.5)),
                            (bx + 1.4, 8 + s * (hlen + 0.9))], 0.5, bstyle)
            extra.append(_pn(_vd(wing, _vrot(_VD_ORN, ang, bx, 8.0)), "의장깃"))
    if grade in _DECOR:
        sh.append(_pn(_vd(_gem(bx, 8.0, 0.52, JADE if grade != "mabyeong" else BLOOD),
                          _vstud(4.2)), "상감"))   # 상감 — 코등이(3.4)보다 z 로 튄다 (11차)
    return sh + extra, []


def _guard_disc(salt, grade, bx, r, manga=False):
    """원반 코등이 — 도·무당의 원. → (shapes, extras).
    21차 manga=True — 만화 셀 금 + 바깥 날개 호 각도 배치(_vrot · 대담한 아이콘화 원반)."""
    bstyle = _r21_brass(salt, grade) if manga else _brass_style(salt, grade)
    sh = [_stroke([(bx, 8.0), (bx + 0.01, 8.0)], r, bstyle)]
    if grade in _DECOR:                            # 보병 왕관 — 원반 바깥 끊긴 동심호 (§5 도)
        for a0 in (100, 170, 240):
            sh.append(_stroke(_arcpts(bx, 8.0, r + 0.34, a0, a0 + 42, 5), 0.22, bstyle))
    _pn(_vd(sh, _VD_ORN), "코등이")                # 원반 몸 — 의장 깊이 (★상감보다 먼저 태그)
    extra = []
    if manga:                                       # 날개 판 — y축 ±22.5° 로 벌어진 대담한 의장깃
        for s, ang in ((1, 22.5), (-1, -22.5)):
            wing = _stroke([(bx, 8 + s * (r - 0.2)), (bx + 1.1, 8 + s * (r + 1.0))], 0.46, bstyle)
            extra.append(_pn(_vd(wing, _vrot(_VD_ORN, ang, bx, 8.0)), "의장깃"))
    if grade in _DECOR:
        sh.append(_pn(_vd(_gem(bx, 8.0, 0.52, JADE if grade != "mabyeong" else BLOOD),
                          _vstud(4.2)), "상감"))   # 상감 — 원반(3.4)보다 z 로 튄다 (11차)
    return sh + extra, []


# ═══ 17차 — 기맥 오버레이 (정본 §4-②) ═══════════════════════════════════════════
def _overlay(obj, name="기맥"):
    """기운 오버레이 표식 — 알파·깊이 소유권이 없다: _compose 가 이미 불투명한 픽셀의
    색만 섞는다 (스타일이 돌려준 색의 α = 혼합 강도). 기운 층은 색·빛만 — 구조는 계열 소유."""
    for f in (obj if isinstance(obj, list) else [obj]):
        f._overlay = True
        f._part = name
    return obj


def _vein_style(a_lo, a_hi, salt, mab, terminal=False, motif=None):
    """기맥의 색 — 저대비 발광 실선 + 기혈(마디 광점: 기가 맺히는 혈자리).
    terminal=지류 — 끝이 기혈에 맺힌다 (뚝 끊긴 무늬 금지: 끝은 언제나 맺음이다).
    motif="매화" (18차) — 기혈이 다섯 매듭 자리에 꽃술 낱알로 **모여** 맺힌다
    (느린 창(sin 8.5) 안에서만 잦은 낱알(sin 47) — _stamen15 낱알 문법의 기맥 번역)."""
    def style(t, d0, w, side, mx, my):
        if motif == "매화":
            gate = math.sin(t * 8.5 + salt * 0.29)
            node = math.sin(t * 47.0 + salt * 0.7) if gate > 0.55 else -1.0
        else:
            node = math.sin(t * 17.0 + salt * 0.29)
        if node > 0.93 or (terminal and t > 0.88):
            c = _qi_glow(a_lo, a_hi, 2.6)
            return (c[0], c[1], c[2], 170)         # 기혈 — 맺힌 광점 (혼합 0.67)
        c = _qi_glow(a_lo, a_hi, 1.7 if mab else 1.9)
        return (c[0], c[1], c[2], 92 if mab else 110)   # 저대비 (16차-b 절제 ③ 승계)
    return style


def _qi_vein(salt, grade, series, spine, amp, nvein=1):
    """기맥 — **한 획** (17차 정본 §4-② · R3 가 기계로 잰다): 내공은 손(자루)에서 들어
    관문(코등이/기환)을 지나 날끝에서 맺는다. 16차-b 는 자루 실·날 무늬가 부위마다 따로
    끊겨 있었다 — 그것이 "이유 없는 무늬"였다. spine = 자루→날끝 중심선 (모델 좌표),
    amp(mx) = 사행 진폭 (부위 폭 안에 머문다 — 실루엣 무접촉은 오버레이 계약이 보장).
    nvein≥2 — 지류 하나가 본류에서 갈라져 기혈에 맺힌다 (본류와 접점 공유 = 연결 보장).
    18차 — 명병 문양의 기맥 번역 (_MOTIF_OVR 컨텍스트 · 정본 §4): 파형·기혈 매듭만 문파의
    획을 탄다 — 태극=느린 S 두 굽이 · 파도=쏠린 물결 · 구름=뭉게 감김 · 매화=꽃술 낱알 기혈
    (_vein_style) · 새끼줄=지류가 본류와 거울 맞꼬임 (교차마다 닿는다 — 한 획 R3 유지)."""
    lens = [math.hypot(spine[i + 1][0] - spine[i][0], spine[i + 1][1] - spine[i][1])
            for i in range(len(spine) - 1)]
    total = sum(lens) or 1e-9
    mab = grade == "mabyeong"
    motif = _MOTIF_OVR[0]
    freq = 2.6 if motif == "태극" else (3.4 if mab else 4.6)   # 마병 = 느린 긴 실선 (16차-b ④)
    M = 72
    pts, base = [], []
    for i in range(M + 1):
        s = i / M * total
        acc, seg = 0.0, 0
        while seg < len(lens) - 1 and acc + lens[seg] < s:
            acc += lens[seg]
            seg += 1
        u = (s - acc) / (lens[seg] or 1e-9)
        ax, ay = spine[seg]
        bx_, by_ = spine[seg + 1]
        x, y = ax + (bx_ - ax) * u, ay + (by_ - ay) * u
        tx_, ty_ = (bx_ - ax) / (lens[seg] or 1e-9), (by_ - ay) / (lens[seg] or 1e-9)
        th = i / M * freq * math.tau * 0.5 + salt * 0.23
        if motif == "파도":                        # 마루가 앞으로 쏠린 물결 — 2배음 겹침
            off = amp(x) * (0.72 * math.sin(th) + 0.34 * math.sin(2.0 * th + 1.1))
        elif motif == "구름":                      # 뭉게 — 진폭이 부풀고 잦아드는 감김
            off = amp(x) * math.sin(th) * (0.55 + 0.45 * math.sin(th * 0.5 + salt * 0.11))
        else:                                      # 기본 사행 (태극은 freq 가 이미 굽혔다)
            off = amp(x) * math.sin(th)
        base.append((x, y, tx_, ty_, off))
        pts.append((x - ty_ * off, y + tx_ * off))
    a_lo, a_hi = _qi_accent(series, grade)
    out = [_overlay(_stroke(pts, 0.24, _vein_style(a_lo, a_hi, salt, mab, motif=motif)))]
    if nvein >= 2 and motif == "새끼줄":           # 맞꼬임 — 거울 가닥 (시작 공유 + 교차 = 연결)
        pts2 = [pts[0]] + [(x + ty_ * off, y - tx_ * off)
                           for (x, y, tx_, ty_, off) in base[1:]]
        out.append(_overlay(_stroke(pts2, 0.2,
                                    _vein_style(a_lo, a_hi, salt, mab, motif=motif))))
    elif nvein >= 2:                               # 지류 — 관문 뒤에서 갈라진다
        i0 = int(0.58 * M)
        side = 1.0 if salt % 2 else -1.0
        bpts = [pts[i0]]
        for j in range(1, 13):
            tt = j / 12.0
            bi = min(M, i0 + int(tt * (M - i0) * 0.6))
            x, y = pts[bi]
            bpts.append((x, y + amp(x) * (0.7 + 0.5 * tt) * side))
        out.append(_overlay(_stroke(bpts, 0.2,
                                    _vein_style(a_lo, a_hi, salt, mab, terminal=True,
                                                motif=motif))))
    return out


# ─── 계열 스펙 9벌 — (box, shapes, extras) ────────────────────────────────────────
def _spec_sword(salt, grade, rings, blen, tassel, mab, *, half=1.6, tipx=None, gx0=-3.4,
                gx1=5.0, gw=0.72, hlen=3.6, gword=0.78, flare=1.25, poison=False,
                qi_spine=None, qi_amp=None):
    """검 — 9차 A안 골격 (자루·의장·날 색층은 _steel_style/_head_kit 의 A 문법).
    13차 (비율 재균형 — 사용자 인게임: "손잡이와 날이 크기가 비슷해 이질적"): 7차의
    "날은 조연 ≤45%" 규칙 폐기 — **날이 전장의 55~62%**【잠정】. 날 연장(tipx 19.6→21.2 ·
    의장 등급 16.6→21.4) + 의장 자루 단축(-7.4→-4.8) + 머리 압축(3.7→3.2 · r 2.8→2.2) +
    자루 슬림(gw 0.85→0.72). 등급 사다리(blen·의장 완성도·파편)는 그대로다."""
    if _R21 and _R21_ALLOW[0]:           # 21차 — 만화 셀 + 기 복셀 껍질 흐름 (파일럿만)
        return _spec_sword_r21(salt, grade, rings, blen, tassel, mab)
    if _R20:                             # 20차 — 전통 병기 + 광택 일렁임 (마법 오라 제거)
        return _spec_sword_r20(salt, grade, rings, blen, tassel, mab)
    if _R19:                             # 19차 리디자인 파일럿 — 오라 분리 (팩은 _R19=False)
        return _spec_sword_r19(salt, grade, rings, blen, tassel, mab)
    orn_full = _ORN[grade] >= 3          # 신병/마병 — 의장이 코등이를 대신한다 (파일럿 골격)
    calm17 = _R16 == "calm" and _R16_ALIGN         # 17차 정본 정렬판 (파일럿: 검·도)
    if tipx is None:
        tipx = (21.2 + blen) if not orn_full else (23.2 + blen)   # 13차 — 날이 주연 (55~62%)
    wm = (_jag(tipx - 6.1, 1.0) if mab
          else _crystal(tipx - 6.1, 1.0)
          if grade == "sinbyeong" and half > 0.3 and not calm17 else None)
    # (17차 — calm 은 결정 실루엣 돌기를 접는다: 원소 수정 재질(kmc)이 없는 선협 날에서
    #  결정 단차는 근거를 잃은 돌기다. 신성은 기운 층이 말한다 — 정본 §4)
    sh = [_pn(_stroke([(6.1, 8.0), (tipx, 8.0)], _taperw(half, tip=0.74, slim=0.09),
                      _steel_style(salt, grade, poison=poison, series="sword",
                                   vein=not calm17), wmod=wm), "검신")]
    sh += _wisps(salt, tipx - 0.6, 8.0, grade, series="sword")
    if orn_full:                         # 자루가 물러나 고리 속이 **빈다** — 네거티브 스페이스
        g_sh, g_ex = [], []              # + 유색 자루 (13차: 늘씬하되 짧게 — 날에 주연을 준다)
        gx0, gx1 = min(gx0, -4.0), min(gx1, 1.4)
        if calm17:                       # 17차 — calm 의 관문: 물리 의장이 빠진 자리를 기환이
            gx1 = 5.2                    # 대신하는데, 16차-b 는 자루(≤1.4)와 날(6.1) 사이가
            #   허공이었다 (R1 — 날이 고립되고 내단이 떠 있었다). 정본 §2-검: 자루가 기환
            #   관문 밑을 지나 **목띠**(날밑쇠 — 실검의 하바키·血止め)까지 잇는다.
            g_sh = [_pn(_vd(_stroke([(5.65, 6.55), (5.65, 9.45)], 0.5,
                                    _brass_style(salt, grade)), _VD_ORN), "목띠")]
    else:
        g_sh, g_ex = _guard_cross(salt, grade, 5.6, hlen, gword, flare)
    h_sh, h_ex, lox, loy = _hilt_kit(salt, grade, rings, tassel, mab, gx0, gx1, gw,
                                     ink=_ink8("sword"), vein=not calm17)
    if calm17 and _QI_GRADE_CALM[grade][1]:        # 기맥 — 자루→날끝 한 획 (정본 §4-② · R3)
        def _amp(mx, _tip=tipx, _gw=gw):
            if mx < 6.1:
                return min(0.28, _gw * 0.42)
            return min(0.55, max(0.1, (_tip - 0.6 - mx) * 0.18))
        # qi_spine/qi_amp — 명병 변주(곤륜 두 갈래 등)가 제 날몸을 따라 긋는 훅 (18차)
        sh += _qi_vein(salt, grade, "sword",
                       qi_spine or [(gx0 - 0.5, 8.0), (tipx - 0.35, 8.0)],
                       qi_amp or _amp, nvein=_QI_GRADE_CALM[grade][1])
    orn = _head_kit(salt, "sword", grade, 3.2, 8.0, wing=(-1.0, 0.8))
    box = (min(lox, gx0 - 2.0, -0.9), min(loy, 8 - hlen - 3.4, 2.1),
           tipx + 3.4, max(8 + hlen + 3.4, 14.0))
    return box, sh + g_sh + h_sh + orn, g_ex + h_ex


def _spec_dao(salt, grade, rings, blen, tassel, mab, *, half=2.3, r_disc=2.05, tip_y=11.3):
    """도 — 계열 대표. 9차: half 2.0→2.3 (kmc 대담 실루엣 — 사용자 지시가 Codex 유지를
    이긴다) · 끝 수축 완화(tip 0.76) · 혈조는 척 아래(§4) · 신병 원위는 진홍 수정."""
    if _R21 and _R21_ALLOW[0]:           # 21차 — 만화 셀 + 기 복셀 껍질 흐름 (파일럿만)
        return _spec_dao_r21(salt, grade, rings, blen, tassel, mab)
    if _R20:                             # 20차 — 전통 병기 + 광택 일렁임 (마법 오라 제거)
        return _spec_dao_r20(salt, grade, rings, blen, tassel, mab)
    if _R19:                             # 19차 리디자인 파일럿 — 오라 분리 (팩은 _R19=False)
        return _spec_dao_r19(salt, grade, rings, blen, tassel, mab)
    tipx = 19.3 + blen
    calm17 = _R16 == "calm" and _R16_ALIGN         # 17차 정본 정렬판 (파일럿: 검·도)
    bpts = _bez((6.6, 7.75), (13.2, 7.8), (tipx, tip_y), 12)
    wm = (_jag(14.0, -1.0) if mab
          else _crystal(14.0, -1.0) if grade == "sinbyeong" and not calm17 else None)
    sh = [_pn(_stroke(bpts, _taperw(half, tip=0.76, slim=0.12),
                      _steel_style(salt, grade, single=True, edge_side=-1.0, groove="spine",
                                   series="dao", vein=not calm17), wmod=wm), "도신")]
    tang = (bpts[-1][0] - bpts[-2][0], bpts[-1][1] - bpts[-2][1])
    sh += _wisps(salt, tipx - 0.4, tip_y - 0.4, grade, scale=0.95, tangent=tang,
                 series="dao")
    g_sh, g_ex = _guard_disc(salt, grade, 6.0, r_disc)
    h_sh, h_ex, lox, loy = _hilt_kit(salt, grade, rings, tassel, mab, 1.4, 5.4, 0.76,
                                     ink=_ink8("dao"),              # 13차 — 자루 슬림 0.9→0.76
                                     vein=not calm17)
    if calm17 and _QI_GRADE_CALM[grade][1]:        # 기맥 — 자루→도끝 한 획 (정본 §4-② · R3)
        spine = [(1.0, 8.0), (6.0, 7.9)] + _bez((6.6, 7.75), (13.2, 7.8),
                                                (tipx - 0.5, tip_y - 0.25), 8)
        def _amp(mx, _tip=tipx):
            if mx < 6.4:
                return 0.26
            return min(0.6, max(0.1, (_tip - 0.8 - mx) * 0.2))
        sh += _qi_vein(salt, grade, "dao", spine, _amp, nvein=_QI_GRADE_CALM[grade][1])
    sh += _head_kit(salt, "dao", grade, 5.8, 8.0, wing=(-0.9, 0.9))
    box = (min(lox, -0.9), min(loy, 8 - half - 2.4, 2.1), tipx + 3.4, tip_y + half + 2.2)
    return box, sh + g_sh + h_sh, g_ex + h_ex


# ═══ V2-W 19차 — 오라 분리 + 종합 리디자인 (파일럿: 검·도 · _R19 게이트) ═══════════════
def _aura_offset(spine, wfn, sign, gap):
    """날 중심선(spine)의 sign 쪽 법선으로 (반폭 w(t)+gap) 밀어낸 오프셋 폴리라인 —
    오라 헤일로가 날 실루엣 바깥에 **어두운 틈**을 두고 뜬다 (오라 분리의 xy 축)."""
    n = len(spine)
    lens = [math.hypot(spine[i + 1][0] - spine[i][0], spine[i + 1][1] - spine[i][1])
            for i in range(n - 1)] or [1e-9]
    total = sum(lens) or 1e-9
    pre = [0.0]
    for L in lens:
        pre.append(pre[-1] + L)
    out = []
    for i in range(n):
        x, y = spine[i]
        t = pre[i] / total
        if i < n - 1:
            tx, ty = spine[i + 1][0] - x, spine[i + 1][1] - y
        else:
            tx, ty = x - spine[i - 1][0], y - spine[i - 1][1]
        tl = math.hypot(tx, ty) or 1e-9
        nx, ny = -ty / tl, tx / tl                 # 진행 방향 왼쪽 법선
        w = wfn(t) if callable(wfn) else wfn
        out.append((x + nx * sign * (w + gap), y + ny * sign * (w + gap)))
    return out


def _aura_rim(spine, wfn, sign, series, grade, z, *, gap=None, thick=0.34, k=1.0):
    """오라 헤일로 한 줄 — 날 바깥으로 gap 만큼 뜬 발광 윤곽 (float · z 부양 · 정본 §4).
    벼려진 기운은 인선·칼끝에 맺힌다 — 뿌리는 흐리고 칼끝으로 갈수록 밝다 (16차-b 절제 승계).
    float 태그라 본체 extent 분모에 안 든다 (12차 display 계약) · R1 float 예외."""
    gap = _R19_GAP if gap is None else gap
    g = _QI_GRADE_CALM[grade][0]
    a_lo, a_hi = _qi_accent(series, grade)
    pts = _aura_offset(spine, wfn, sign, gap)
    cap = (2.6 if grade == "mabyeong" else 3.0) * k
    thick2 = thick * (1.35 if _R19_BLOOM else 1.0)     # 블룸엔 단면 falloff 여유 폭 (번짐)

    def style(t, d0, w, side, mx, my):
        # 길이 밝기 — 뿌리 흐림 → 칼끝 맺힘 (기운은 인선·칼끝에 맺힌다 · §4)
        lvl = (0.9 + 1.3 * g) * (0.4 + 0.6 * t) * k
        if t > 0.9:
            lvl += 0.7
        if _R19_PHASE is not None:                     # 일렁임 — 밝은 마디가 인선을 따라 흐른다
            wave = 0.5 + 0.5 * math.sin((t * 2.4 - _R19_PHASE) * math.tau + g * 3.1)
            lvl *= 0.55 + 0.75 * wave                  # (오로라 위상 이동 · 프레임 인덱스=위상)
        if not _R19_BLOOM:
            return _qi_glow(a_lo, a_hi, min(lvl, cap))   # 19b 플랫 램프 (부산물 대조)
        # 빛 — 블룸: 획 단면 중앙이 핫(흰), 가장자리로 옅게 falloff (광원 후광)
        rel = min(1.0, d0 / max(w, 1e-6))
        center = min(lvl, cap) * (1.0 - 0.82 * rel)    # 단면 falloff (중앙 밝고 가장자리 옅다)
        c = _bloom_color(a_lo, a_hi, center)
        a = 255 if rel < 0.72 else max(60, int(255 * (1.0 - rel) / 0.28))   # 가장자리 2px 페이드
        return (c[0], c[1], c[2], a)
    return _pn(_vd(_stroke(pts, thick2, style),
                   (round(thick2 * 2, 3), round(z, 3), None, "float")), "기운자락")


def _aura_layers(spine, wfn, series, grade, signs):
    """오라 분리 — signs(±1) 각 쪽마다 **헤일로 1겹**만 (19차-b · 사용자: "1겹으로 줄이고,
    너무 오라가 많아서 난잡해 보임"). 19차의 외곽 "뜬 기운" 둘째 겹은 난잡의 주범이라 제거 —
    윤곽 1겹 + 날 위 기맥 한 획 + 최소 광점만 남긴다. 분리 효과(gap·±z)는 유지한다."""
    if _QI_GRADE_CALM[grade][0] < 0.4:             # 범철·정련 — 기운 미개방 (헤일로 없음)
        return []
    out = [_aura_rim(spine, wfn, s, series, grade, _R19_HALO_Z * s) for s in signs]
    if _R19_OUTER:                                 # 19 난잡 재현 — 외곽 뜬 기운 둘째 겹
        for s in signs:
            out.append(_aura_rim(spine, wfn, s, series, grade, _R19_HALO_Z * s + 0.7 * s,
                                 gap=_R19_GAP + 1.2, thick=0.24, k=0.55))
    return out


def _bloom_color(a_lo, a_hi, b):
    """중심 밝기 b(0..1.3+) → 오라 블룸 색 (정본 §4-발광): 광원은 심이 **희게 뜬다**.
       b>1.0 핫 코어(흰-핫 과노출) → 채도 악센트(a_hi) → a_lo → 어둠 falloff(몸으로 스민다).
    ★오라 한정 그라데이션 예외 — 무기 몸/날의 픽셀아트 플랫 규율(축 자체 규율)은 불변이고,
    감사 축 ㉓ 은 그라데이션을 안 잰다 (평균채도·알파만). 단수 플랫 램프(_qi_glow)가 발광을
    부산물처럼 딱딱하게 계단 지운 것이 사용자가 지적한 병이다 — 빛은 부드럽게 falloff 한다."""
    b = max(0.0, min(1.3, b))
    if b > 1.0:
        return mix(a_hi, MOON_V, min(1.0, (b - 1.0) / 0.3))   # 핫 코어 — 광원의 흰 심
    if b > 0.55:
        return mix(a_lo, a_hi, (b - 0.55) / 0.45)             # 채도 있는 악센트 후광
    return mix(mix(a_lo, WPN_OUT, 0.62), a_lo, b / 0.55)      # 어둡게 falloff (몸으로 스민다)


def _vein_centerline(spine, amp, salt, M=72, motif=None):
    """기맥 경로 중심선 — _qi_vein 과 **같은 샘플링** (경혈 자리를 그 위에서 고르려면
    본류와 같은 곡선이어야 한다). (x, y, t) 리스트를 돌려준다."""
    lens = [math.hypot(spine[i + 1][0] - spine[i][0], spine[i + 1][1] - spine[i][1])
            for i in range(len(spine) - 1)]
    total = sum(lens) or 1e-9
    out = []
    for i in range(M + 1):
        s = i / M * total
        acc, seg = 0.0, 0
        while seg < len(lens) - 1 and acc + lens[seg] < s:
            acc += lens[seg]
            seg += 1
        u = (s - acc) / (lens[seg] or 1e-9)
        ax, ay = spine[seg]
        bx_, by_ = spine[seg + 1]
        x, y = ax + (bx_ - ax) * u, ay + (by_ - ay) * u
        tx_, ty_ = (bx_ - ax) / (lens[seg] or 1e-9), (by_ - ay) / (lens[seg] or 1e-9)
        off = amp(x) * math.sin(i / M * (2.6 if motif == "태극" else 4.6) * math.pi
                                + salt * 0.23)
        out.append((x - ty_ * off, y + tx_ * off, i / M))
    return out


def _meridian_node_ts(salt, lo=0.42, hi=0.96):
    """기혈(경혈) 자리 — 기맥 위에서 마디 광점이 맺히는 t (정본 §4: 부유 광점 = 경혈 잔광).
    _vein_style 의 node = sin(t·17 + salt·0.29): 광점 조건 node>0.93 인 **마루**의 t 를
    원위(칼끝 쪽) 창 [lo, hi] 에서 골라 돌려준다 (임의 방사 상수가 아니라 해부에서 도출)."""
    ts = []
    m = 0
    while True:
        # sin 마루: t·17 + salt·0.29 = π/2 + 2πm
        t = ((math.pi / 2 + 2 * math.pi * m) - salt * 0.29) / 17.0
        if t > hi:
            break
        if t >= lo:
            ts.append(round(t, 5))
        m += 1
    return ts


def _meridian_motes(spine, amp, salt, series, grade, n=None, phase=None, motif=None):
    """부유 광점 — **기맥 경혈 잔광** (정본 §4 재정본화): 기환 축 임의 오프셋(19b: r+2.7)이
    아니라 기맥 경로 위의 마디(경혈)에서 떠오른다. 위치가 해부(기맥·경혈)에서 도출된다 —
    사용자 "이유 없이 오라를 늘렸다"의 실체(무근거 산포)를 근거 있는 배치로 갈아탄다.
    양(n) = 등급 광점 수 (_QI_GRADE_CALM · 절제 유지) · 발광은 블룸(핫 코어) · phase 면 맥동."""
    if n is None:
        n = _QI_GRADE_CALM[grade][3]
    if not n:
        return []
    a_lo, a_hi = _qi_accent(series, grade)
    cl = _vein_centerline(spine, amp, salt, motif=motif)
    node_ts = _meridian_node_ts(salt)
    if not node_ts:                                # 이 씨앗에 원위 경혈이 없으면 중·원위 균등
        node_ts = [0.55, 0.82]
    # 가장 원위(칼끝 쪽) n 개 경혈 — 기운은 인선·칼끝으로 갈수록 맺힌다 (§4)
    node_ts = sorted(node_ts)[-n:]
    out = []
    for i, nt in enumerate(node_ts):
        # 경로 위에서 그 t 에 가장 가까운 마디 좌표
        px_, py_, _ = min(cl, key=lambda e: abs(e[2] - nt))
        rr = (0.30, 0.26)[i % 2]
        z = 1.4 if i % 2 == 0 else -1.4            # float — ±z 로 갈라 떠 각도서 분리 (§4)
        nodephase = nt                             # 맥동 위상 = 경혈 자리 (경혈마다 다른 박동)

        def mstyle(t, d0, w, side, mx, my, _np=nodephase):
            rel = min(1.0, d0 / max(w, 1e-6))
            pulse = 1.0
            if phase is not None:                  # 맥동 — 경혈이 숨쉰다 (오로라 · 결정론)
                pulse = 0.7 + 0.55 * math.sin((_np * 3.0 - phase) * math.tau + salt * 0.3)
            b = (1.18 * pulse) * (1.0 - rel) ** 1.25 + 0.12
            if not _R19_BLOOM:
                return (a_hi if rel < 0.55 else mix(a_lo, WPN_OUT, 0.4))   # 19b 플랫 대조
            c = _bloom_color(a_lo, a_hi, b)        # 빛 — 핫 코어 흰 심 + 악센트 후광
            a = 255 if rel < 0.7 else max(50, int(255 * (1.0 - rel) / 0.3))
            return (c[0], c[1], c[2], a)
        f = _pn(_vd(_stroke([(px_, py_), (px_ + 0.01, py_)], rr, mstyle),
                    (round(rr * 2, 3), round(z, 3), None, "float")), "부유광점")
        f._center = (px_, py_)                      # 자기시험용 — 광점 중심 (경혈 좌표)
        out.append(f)
    return out


def _r19_steel(salt, grade, series, single=False, edge_side=-1.0):
    """19차 리디자인 날 — **오라 분리**: 발광 bloom 을 본체에서 떼어 헤일로로 보냈다.
    본체는 조용하고 정교한 재질만 — 어두운 옥/먹 심 + 뼈백 사면 3단 + 벼린 인선 한 줄
    (발광 halo 아님 · 빛 받는 쪽만) + 담금선 한 가닥. 기맥은 _qi_vein 오버레이가 날 위에 긋는다."""
    mab = grade == "mabyeong"
    b8 = _ink8(series)
    a_lo, a_hi = _qi_accent(series, grade)
    core_c = mix(b8[0], b8[1], 0.3)
    core_dk = mix(core_c, WPN_OUT, 0.4)
    facet_lo = mix(BONE_DK, core_c, 0.42)
    facet_hi = mix(BONE_MID, BLADE_LIT, 0.5)
    edge_c = mix(BLADE_HI, a_hi, 0.35)             # 벼린 인선 — 악센트 기 도는 흰 선 (bloom 아님)
    if mab:
        core_c = mix(_QI_INK, BLOOD, 0.28)
        core_dk = mix(core_c, WPN_OUT, 0.35)
        facet_lo = mix(core_c, BLOOD, 0.3)
        facet_hi = mix(mix(_QI_BODY, BLOOD, 0.32), BLADE_DIM, 0.25)
        edge_c = mix(BLADE_HI, CRIM_VHI, 0.32)

    def style(t, d0, w, side, mx, my):
        rel = min(1.0, d0 / max(w, 1e-6))
        lit = side > 0 or single
        if single and side == -edge_side:          # 척 — 어두운 등 (한날의 등)
            return mix(core_c, WPN_OUT, 0.32) if rel > 0.5 else core_c
        if rel > 0.9:                              # 인선 — 얇은 벼림 선 (빛 받는 쪽만)
            return edge_c if lit else mix(edge_c, core_dk, 0.6)
        if rel < 0.22:
            c = core_dk                            # 능선 어두운 심
        elif rel < 0.5:
            c = core_c
        elif rel < 0.76:
            c = facet_lo if lit else mix(facet_lo, core_c, 0.45)
        else:
            c = facet_hi if lit else facet_lo      # 뼈백 사면 (정교화 명암)
        if 0.12 < t < 0.9 and abs(d0 - w * 0.52) < 0.11 and 0.42 < rel < 0.86:
            c = mix(c, core_dk, 0.3)               # 담금선 한 가닥 (길이 방향 결)
        if t < 0.08:
            c = mix(c, WPN_OUT, 0.3)               # 날 뿌리 AO 앵커
        return c
    return style


def _r19_hilt(salt, grade, series, rings, tassel, mab, gx0, gx1, gw):
    """19차 자루 — 세련화: 조용한 유색 원통(기품) + 물미관 + 등급 고리 + 신병 수실.
    calm 경로(_hilt_kit)를 재사용하되 vein=False (감김 실은 _qi_vein 오버레이가 잇는다)."""
    return _hilt_kit(salt, grade, rings, tassel, mab, gx0, gx1, gw,
                     ink=_ink8(series), vein=False)


def _spec_sword_r19(salt, grade, rings, blen, tassel, mab):
    """검 — 19차 리디자인: **곧고 기품 있는** 실루엣 (더 갸름한 원위 수렴 · 슬림 자루) +
    오라 분리 (발광 윤곽을 양날 헤일로로 · 기맥은 날 위 유지 · 기환/광점 더 멀리)."""
    orn_full = _ORN[grade] >= 3
    gw = 0.62                                      # 자루 한 단 더 슬림 (검의 기품)
    half = 1.46
    tipx = (23.4 + blen) if orn_full else (21.4 + blen)
    wfn = _taperw(half, tip=0.7, slim=0.08)        # 우아한 원위 수렴 (곧게 뻗어 끝만 여민다)
    sh = [_pn(_stroke([(6.0, 8.0), (tipx, 8.0)], wfn,
                      _r19_steel(salt, grade, "sword")), "검신")]
    sh += _aura_layers([(6.0, 8.0), (tipx - 0.15, 8.0)], wfn, "sword", grade, (1.0, -1.0))
    if _R19_WISP:                                  # 19 재현 — 칼끝 자락 (19b 는 헤일로가 대신)
        sh += _wisps(salt, tipx - 0.55, 8.0, grade, series="sword")
    if orn_full:                                   # 신병/마병 — 기환 관문 + 목띠 (허공 없이 잇는다)
        gx0, gx1 = min(-4.0, -4.0), 5.25
        g_sh = [_pn(_vd(_stroke([(5.6, 6.55), (5.6, 9.45)], 0.48,
                                _brass_style(salt, grade)), _VD_ORN), "목띠")]
    else:
        gx0, gx1 = -3.4, 5.0
        g_sh, _gx = _guard_cross(salt, grade, 5.6, 3.4, 0.72, 1.2)
    h_sh, h_ex, lox, loy = _r19_hilt(salt, grade, "sword", rings, tassel, mab, gx0, gx1, gw)
    veins = []
    if _QI_GRADE_CALM[grade][1]:                   # 기맥 — 자루→날끝 한 획 (날 위 유지 · 정본 R3)
        def _amp(mx, _tip=tipx, _gw=gw):
            if mx < 6.0:
                return min(0.24, _gw * 0.42)
            return min(0.5, max(0.1, (_tip - 0.5 - mx) * 0.17))
        sword_spine = [(gx0 - 0.5, 8.0), (tipx - 0.3, 8.0)]
        veins = _qi_vein(salt, grade, "sword", sword_spine, _amp,
                         nvein=_QI_GRADE_CALM[grade][1])
        if _R19_MOTE_MERIDIAN:
            veins += _meridian_motes(sword_spine, _amp, salt, "sword", grade,   # 광점=기맥 경혈 잔광
                                     phase=_R19_PHASE)
    orn = _head_kit(salt, "sword", grade, 3.0, 8.0, wing=(-1.0, 0.8))
    box = (min(lox, gx0 - 2.0, -1.2), min(loy, 8 - 3.4 - 3.4, 1.6),
           tipx + 4.6, max(14.8, 8 + 3.4 + 3.4))
    return box, sh + g_sh + h_sh + orn + veins, h_ex


def _spec_dao_r19(salt, grade, rings, blen, tassel, mab, *, half=2.45, r_disc=2.1, tip_y=11.7):
    """도 — 19차 리디자인: **대담한 휨** (배가 더 부르고 끝이 시원하게 쓸린다) +
    오라 분리 (인선 쪽 헤일로가 도신 배에서 떨어져 뜬다 · 척은 침묵) · 원반 코등이 세련화."""
    tipx = 19.6 + blen
    bpts = _bez((6.5, 7.7), (13.6, 7.55), (tipx, tip_y), 14)   # 더 부른 배 (대담한 휨)
    sh = [_pn(_stroke(bpts, _taperw(half, tip=0.78, slim=0.12),
                      _r19_steel(salt, grade, "dao", single=True, edge_side=-1.0)), "도신")]
    # 오라 분리 — 인선 쪽(+1: 진행 왼쪽 = 배)만 헤일로 1겹 (한날은 인선에 기운이 맺힌다 · §4)
    sh += _aura_layers(bpts, _taperw(half, tip=0.78, slim=0.12), "dao", grade, (1.0,))
    if _R19_WISP:                                  # 19 재현 — 도끝 자락 (19b 는 광점만)
        tang = (bpts[-1][0] - bpts[-2][0], bpts[-1][1] - bpts[-2][1])
        sh += _wisps(salt, tipx - 0.4, tip_y - 0.4, grade, scale=0.98, tangent=tang, series="dao")
    g_sh, g_ex = _guard_disc(salt, grade, 6.0, r_disc)
    h_sh, h_ex, lox, loy = _r19_hilt(salt, grade, "dao", rings, tassel, mab, 1.3, 5.4, 0.72)
    veins = []
    if _QI_GRADE_CALM[grade][1]:                   # 기맥 — 자루→도끝 한 획 (날 위 유지 · 정본 R3)
        spine = [(1.0, 8.0), (6.0, 7.85)] + _bez((6.5, 7.7), (13.6, 7.55),
                                                 (tipx - 0.5, tip_y - 0.25), 8)

        def _amp(mx, _tip=tipx):
            if mx < 6.4:
                return 0.26
            return min(0.58, max(0.1, (_tip - 0.8 - mx) * 0.2))
        veins = _qi_vein(salt, grade, "dao", spine, _amp, nvein=_QI_GRADE_CALM[grade][1])
        if _R19_MOTE_MERIDIAN:
            veins += _meridian_motes(spine, _amp, salt, "dao", grade,   # 광점=기맥 경혈 잔광 (§4)
                                     phase=_R19_PHASE)
    orn = _head_kit(salt, "dao", grade, 5.8, 8.0, wing=(-0.9, 0.9))
    box = (min(lox, -1.2), min(loy, 8 - half - 2.6, 1.6), tipx + 4.6, tip_y + half + 3.0)
    return box, sh + g_sh + h_sh + orn + veins, g_ex + h_ex


# ═══ V2-W 20차 — 전통 병기 날 + 표면 광택 일렁임 (마법 오라 제거 · _R20 게이트) ═══════════
def _r20_steel(salt, grade, series, single=False, edge_side=-1.0, phase=None):
    """전통 병기 날 — 벼린 강철/옥의 정교한 재질 + **표면 스펙큘러 스윕**(광택 일렁임).
    선협 발광 오라(헤일로·기맥 그물)는 없다 — 진짜 강철 문양만: 어두운 심 → 사면 3단 →
    벼린 인선 + 담금선/하몬 한 결. 그 위로 광택 마루가 phase 따라 흐른다 (일렁임의 특색).
    마병은 검붉게 위압 · 신병은 옥빛 광택 (전통+α — 고등급이 살짝 색을 띤다)."""
    mab = grade == "mabyeong"
    b8 = _ink8(series)
    # 전통 재질색 — 마법 옥먹 심 대신 강철/유색 자루 기조 (담금선·하몬은 강철 문양이라 유지)
    core_c = mix(BLADE_MID, mix(b8[0], b8[1], 0.3), 0.3)
    core_dk = mix(core_c, WPN_OUT, 0.42)
    facet_lo = mix(BLADE_DIM, core_c, 0.5)
    facet_hi = mix(BLADE_LIT, BONE_MID, 0.28)
    edge_c = BLADE_HI                              # 벼린 인선 — 빛을 되쏘는 강철 흰 선
    if mab:                                         # 마병 — 검붉은 강철 (혈조·위압)
        core_c = mix(BLADE_DIM, BLOOD, 0.4)
        core_dk = mix(core_c, WPN_OUT, 0.4)
        facet_lo = mix(core_c, BLOOD, 0.3)
        facet_hi = mix(mix(BLADE_MID, BLOOD, 0.35), BLADE_DIM, 0.2)
        edge_c = mix(BLADE_HI, CRIM_VHI, 0.28)

    def style(t, d0, w, side, mx, my):
        rel = min(1.0, d0 / max(w, 1e-6))
        lit = side > 0 or single
        if single and side == -edge_side:          # 척 — 어두운 등 (한날의 등)
            c = mix(core_c, WPN_OUT, 0.34) if rel > 0.5 else core_c
        elif rel > 0.9:                            # 인선 — 벼림 선 (빛 받는 쪽만)
            c = edge_c if lit else mix(edge_c, core_dk, 0.55)
        elif rel < 0.22:
            c = core_dk                            # 능선 심
        elif rel < 0.5:
            c = core_c
        elif rel < 0.76:
            c = facet_lo if lit else mix(facet_lo, core_c, 0.45)
        else:
            c = facet_hi if lit else facet_lo      # 사면 명암
        if 0.12 < t < 0.9 and abs(d0 - w * 0.5) < 0.12 and 0.42 < rel < 0.86:
            c = mix(c, core_dk, 0.28)              # 담금선 한 결 (진짜 강철 문양 — 전통 유지)
        if not mab and lit and 0.1 < t < 0.94:     # 하몬 물결 (담금질 경계 — 강철 문양)
            hw = 0.62 + 0.06 * math.sin(t * 23.0 + salt * 0.3)
            if abs(rel - hw) < 0.05:
                c = mix(c, BLADE_LIT, 0.3)
        c = _sweep_mix(c, series, grade, t, rel, side, phase, single, edge_side)  # ★광택 일렁임
        if t < 0.08:
            c = mix(c, WPN_OUT, 0.3)               # 날 뿌리 AO
        return c
    return style


def _spec_sword_r20(salt, grade, rings, blen, tassel, mab):
    """검 — 전통 병기(20차): 곧고 기품 있는 강철 검 + 표면 광택 일렁임. 마법 오라 없음 —
    십자 코등이·물미·자루 고리(전통 부위)만. 광택 마루가 인선 따라 세로로 흐른다."""
    gw = 0.66
    half = 1.5
    tipx = 21.4 + blen
    wfn = _taperw(half, tip=0.72, slim=0.09)
    sh = [_pn(_stroke([(6.0, 8.0), (tipx, 8.0)], wfn,
                      _r20_steel(salt, grade, "sword", phase=_R20_PHASE)), "검신")]
    g_sh, _gx = _guard_cross(salt, grade, 5.6, 3.6, 0.74, 1.2)     # 전통 십자 코등이
    h_sh, h_ex, lox, loy = _hilt_kit(salt, grade, rings, tassel, mab, -3.4, 5.0, gw,
                                     ink=_ink8("sword"), vein=False, traditional=True)
    box = (min(lox, -3.4 - 2.0, -1.2), min(loy, 2.1), tipx + 2.4, 14.0)
    return box, sh + g_sh + h_sh, h_ex


def _spec_dao_r20(salt, grade, rings, blen, tassel, mab, *, half=2.4, r_disc=2.05, tip_y=11.5):
    """도 — 전통 병기(20차): 대담한 휨의 한날 도 + 표면 광택 일렁임 (넓은 배를 사행). 마법 없음."""
    tipx = 19.6 + blen
    bpts = _bez((6.5, 7.7), (13.5, 7.6), (tipx, tip_y), 14)
    sh = [_pn(_stroke(bpts, _taperw(half, tip=0.78, slim=0.12),
                      _r20_steel(salt, grade, "dao", single=True, edge_side=-1.0,
                                 phase=_R20_PHASE)), "도신")]
    g_sh, g_ex = _guard_disc(salt, grade, 6.0, r_disc)            # 전통 원반 호수
    h_sh, h_ex, lox, loy = _hilt_kit(salt, grade, rings, tassel, mab, 1.3, 5.4, 0.72,
                                     ink=_ink8("dao"), vein=False, traditional=True)
    box = (min(lox, -1.2), min(loy, 8 - half - 2.6, 1.6), tipx + 2.6, tip_y + half + 2.4)
    return box, sh + g_sh + h_sh, g_ex + h_ex


# ═══ V2-W 21차 — 만화(애니)풍 셀 셰이딩 날 (구조·볼록 단면 유지 · 그림 문법만 교체) ═══════════
def _r21_steel(salt, grade, series, single=False, edge_side=-1.0):
    """만화(애니)풍 셀 날 — 20차 사실적 3단 사면을 **또렷한 2~3톤 셀**로 교체:
    명/암 경계가 딱 떨어지고(그라데이션 없음) · **강한 외곽선**(어두운 테, 실루엣 바깥 1px 픽셀)
    이 실루엣을 두른다(복셀 옆면까지 어두워 3D 만화 윤곽) · 살짝 채도 올린 생생한 색(청백 하이라이트)
    · 날에 **또렷한 흰 반짝 점/띠**(만화 금속 하이라이트 스팟). 담금선·하몬(강철 문양)은 유지.
    ★볼록 단면(7단 렌즈)·해부 부위는 기하 층 소유 — 이 함수는 색(셀)만 만진다. 표면 스윕 없음
    (일렁임은 기 껍질 `_qi_shell` 이 맡는다 — 사용자 "검 주변 복셀로 기가 흐르듯이")."""
    mab = grade == "mabyeong"
    sat, cel_acc = _R21_CEL[grade]
    sect = _ACC_OVR[0]                             # 명병 문파색 (없으면 None) — 표를 이긴다
    if mab:
        acc_lo, acc_hi = BLOOD, BLOOD_HI
    elif sect:
        acc_lo, acc_hi = sect[0], sect[1]          # 명병 = 문파색 셀
    else:
        acc_lo = cel_acc
        acc_hi = tuple(min(255, int(v * 1.12)) for v in cel_acc[:3]) + (255,)
    # 만화 셀 톤 — 2~3톤 (명/암 경계 딱 떨어짐 · 채도 살짝 상향)
    light = mix(BLADE_LIT, acc_hi, min(0.72, sat * 1.25))    # 빛 받는 셀 (생생)
    mid = mix(BLADE_MID, acc_lo, sat * 0.7)                  # 중간 셀
    shadow = mix(mix(BLADE_DIM, acc_lo, sat * 0.5), WPN_OUT, 0.30)   # 그늘 셀 (어둡게)
    edge_hi = mix(BLADE_HI, acc_hi, sat * 0.4)              # 벼린 인선 반짝 (청백)
    outline = mix(mix(BLADE_SPINE, acc_lo, sat * 0.4), WPN_OUT, 0.62)  # 만화 외곽 어두운 테
    if mab:
        light = mix(BLADE_MID, BLOOD_HI, 0.55)
        mid = mix(BLADE_DIM, BLOOD, 0.55)
        shadow = mix(BLOOD, WPN_OUT, 0.52)
        edge_hi = mix(BLADE_HI, CRIM_VHI, 0.42)
        outline = mix(BLOOD, WPN_OUT, 0.72)

    def style(t, d0, w, side, mx, my):
        rel = min(1.0, d0 / max(w, 1e-6))          # 0=능선(중심) → 1=인선(바깥 실루엣)
        lit = side > 0 or single
        if single and side == -edge_side:          # 한날 도 — 어두운 등(척)
            if rel > 0.86:
                return outline                     # 등도 외곽 테
            return mix(shadow, WPN_OUT, 0.3) if rel > 0.5 else shadow
        if rel > 0.86:                             # ★만화 외곽선 — 실루엣 바깥 어두운 테 1px
            c = outline
        elif rel > 0.60:                           # 인선대 — 빛 받는 쪽 흰 반짝, 그늘 쪽 어둡게
            c = edge_hi if lit else mix(edge_hi, shadow, 0.6)
        else:                                       # 셀 몸 — 명/암 2톤 (경계 딱 떨어짐)
            if lit:
                c = light if rel > 0.32 else mid   # 능선 심은 한 톤 낮춰 3톤째
            else:
                c = shadow
        # 담금선·하몬 — 진짜 강철 문양(전통 유지), 단 만화답게 또렷한 1획
        if 0.12 < t < 0.9 and abs(d0 - w * 0.5) < 0.10 and 0.40 < rel < 0.82:
            c = mix(c, outline, 0.34)              # 담금선 (음각 한 결)
        if not mab and lit and 0.1 < t < 0.94:
            hw = 0.58 + 0.05 * math.sin(t * 23.0 + salt * 0.3)
            if abs(rel - hw) < 0.045:
                c = mix(c, BLADE_HI, 0.45)         # 하몬 물결 (담금질 경계)
        # ★하이라이트 스팟 — 만화 금속의 별표 반짝 (능선 근처 또렷한 흰 점/띠, 빛 받는 쪽)
        if lit:
            for tc in (0.34, 0.68):
                if abs(t - tc) < 0.028 and 0.16 < rel < 0.5:
                    c = mix(c, MOON_V, 0.8)
        if t < 0.08:
            c = mix(c, WPN_OUT, 0.3)               # 날 뿌리 AO
        # ★21d — 21c 의 날 표면 전격 오버레이는 폐지됐다: 번개는 날에 흐르지 않고 검 외부에서
        #   기를 대신해 날을 휘감는다 (뇌전 볼트 float 껍질 = `_qi_bolts`). 여기 날은 만화 셀만.
        return c
    return style


def _spine_at(spine, tt):
    """날 중심선(spine 폴리라인)의 호길이 파라미터 tt(0..1) 위치 (x, y) — 검(2점 직선)·도(14점
    베지어) 둘 다 올바로 보간한다 (인덱스 표집은 2점 spine 에서 뿌리에 몰린다 — 21b 수리)."""
    if len(spine) == 1:
        return spine[0]
    lens = [math.hypot(spine[j + 1][0] - spine[j][0], spine[j + 1][1] - spine[j][1])
            for j in range(len(spine) - 1)]
    total = sum(lens) or 1e-9
    s = max(0.0, min(1.0, tt)) * total
    acc = 0.0
    for j, L in enumerate(lens):
        if acc + L >= s or j == len(lens) - 1:
            u = (s - acc) / (L or 1e-9)
            ax, ay = spine[j]
            bx_, by_ = spine[j + 1]
            return (ax + (bx_ - ax) * u, ay + (by_ - ay) * u)
        acc += L
    return spine[-1]


# ═══ V2-W 21차-b/d — 기(氣) 껍질: 발광 포인트(21b) · 뇌전 볼트(21d) — float · 검을 3D 로 감쌈 ═══
def _qi_shell(salt, grade, series, spine, wfn, cy, *, single=False, edge_side=-1.0):
    """무기를 두르는 float 기 껍질 (본체 extent 불침).
    _R21_FX: "bolt"=22차 레퍼런스식 작은 평면 스프라이트(칼끝·코등이 · 기본) · "cage"=옛 21e 3D
    뇌전 케이지(검 전체 휘감음 · 보존) · "qi"=발광 포인트(21b) · "both"=기 포인트+22차 스프라이트.
    반환: float 셰이프 리스트."""
    out = []
    if _R21_FX in ("qi", "both"):
        out += _qi_points(salt, grade, series, spine, wfn)
    if _R21_FX in ("bolt", "both"):
        out += _qi_bolts(salt, grade, series, spine, wfn)     # 22차 — 작은 평면 스프라이트
    if _R21_FX == "cage":
        out += _qi_cage(salt, grade, series, spine, wfn)      # 21e — 옛 3D 케이지 (보존)
    return out


def _qi_points(salt, grade, series, spine, wfn):
    """21b — 무기를 두르는 기의 **띄엄띄엄 떠 있는 발광 포인트** (연속 나선 아님). 길이축 드문드문,
    축 둘레 대각 각도 순환(앞·뒤·좌·우)+다중 z. 자루→날끝 차례로 켜지는 순차 점등 (블룸 핫코어)."""
    n = _R21_QI_SHELL[grade]
    if not n:                                      # 범철 — 순수 병기 (기 없음)
        return []
    a_lo, a_hi = (BLOOD, CRIM_VHI) if grade == "mabyeong" else \
        (_ACC_OVR[0] if _ACC_OVR[0] else (_ink8(series)[4], _ink8(series)[5]))
    g = {"jeongryeon": 0.4, "bobyeong": 0.65, "sinbyeong": 1.0, "mabyeong": 0.95}[grade]
    out = []
    for i in range(n):
        tt = 0.16 + 0.72 * (i / max(1, n - 1))     # 길이축 드문드문 (자루 곁 → 날끝)
        bx0, by0 = _spine_at(spine, tt)            # ★호길이 보간 (spine 2점 검·14점 도 둘 다)
        theta = math.radians(45 + i * 90)          # 대각 각도 순환 (앞·뒤·좌·우 4분면 감쌈)
        rad = wfn(tt) + _R21_SHELL_GAP             # 날 반폭 밖 (본체에 안 먹힌다 · 렌더된다)
        px_ = bx0
        py_ = by0 + rad * math.cos(theta)          # 날 중심선 밖 y 오프셋 (대각이라 |cos|=0.707)
        z = _R21_SHELL_Z * math.sin(theta)         # z 부양 — 앞/뒤 (± 갈라 3D 감쌈)

        def pt_style(t, d0, w, side, mx, my, _tt=tt):
            rel = min(1.0, d0 / max(w, 1e-6))
            # 순차 점등 — 밝은 위상이 t=_tt 를 지날 때 이 포인트가 켜진다 (자루→날끝 차례로)
            dph = abs((_R21_PHASE or 0.0) - _tt)
            dph = min(dph, 1.0 - dph)              # 순환 거리
            lit = math.exp(-(dph / 0.17) ** 2)     # 가우시안 점등 (지날 때 확 밝고 멀면 잦아든다)
            b = (0.22 + 1.15 * g * lit) * (1.0 - rel) ** 1.2   # 블룸 falloff (핫코어 심)
            c = _bloom_color(a_lo, a_hi, b)
            a = 255 if rel < 0.55 else max(40, int(255 * (1.0 - rel) / 0.45))
            return (c[0], c[1], c[2], a)
        out.append(_pn(_vd(_stroke([(px_, py_), (px_ + 0.01, py_)], 0.46 * (0.7 + 0.3 * g),
                                   pt_style), (0.8, round(z, 3), None, "float")), "부유광점"))
    return out


def _lcg(seed):
    """결정론 해시 스트림 (선형합동) — 볼트 세그 각도·길이·분기를 '랜덤처럼' 불규칙하게. 난수 아님
    (씨앗은 자루 키·볼트 인덱스에서 도출 → 2회 빌드 동일)."""
    s = (int(abs(seed) * 104729) & 0x7FFFFFFF) or 1
    def nxt():
        nonlocal s
        s = (1103515245 * s + 12345) & 0x7FFFFFFF
        return s / 0x7FFFFFFF
    return nxt


def _bolt_channel(sx, sy, mu, length, nseg, rnd, jitter=52.0):
    """번개 주 채널 — 시작점(sx,sy)에서 대체로 mu 방향으로 흐르되 세그먼트 **각도·길이가 불규칙**
    (결정론 rnd). 균일 45° Z 반복이 아니라 진짜 전격처럼 잔 꺾임이 랜덤. (점 리스트, 세그 각도)."""
    seglen = length / nseg
    px_, py_ = sx, sy
    pts = [(px_, py_)]
    angs = []
    for _ in range(nseg):
        a = mu + math.radians(jitter) * (rnd() - 0.5)    # 방향 잔 꺾임 (mu 둘레 불규칙)
        L = seglen * (0.5 + rnd())                       # 길이 불규칙 (0.5~1.5배)
        px_ += L * math.cos(a)
        py_ += L * math.sin(a)
        pts.append((px_, py_))
        angs.append(a)
    return pts, angs


def _bolt_style(a_lo, a_hi, inten, bi, seed, tail=False):
    """뇌전 아크 한 획의 색 — **흰-핫 1px 코어**(rel→0, b>1 과노출) → 색 글로우 falloff → 어둠.
    ★불투명도는 _compose 가 기하 거리로 정한다(스타일 알파 무시) — 케이지는 늘 복셀로 서 있고
    애니는 **밝기로만** 친다(투명 명멸 불가 · 20c 스윕과 같은 계약). '치는' 프레임이면 코어가
    흰-핫으로 번쩍, 쉬는 프레임이면 어두운 계열색 잔광 → 밝은 아크가 축 둘레를 옮겨 다니는 치지직.
    tail=분기 — 살짝 어둡게(주 채널이 더 밝다)."""
    k = 0.82 if tail else 1.0
    def style(t, d0, w, side, mx, my):
        rel = min(1.0, d0 / max(w, 1e-6))
        core = 1.34 if _bolt_fire(bi, seed) else 0.32    # 치는 프레임 = 흰-핫 · 쉬는 프레임 = 어두운 잔광
        b = inten * k * (core * (1.0 - rel) ** 1.7 + 0.08 * (1.0 - rel))   # ^1.7 = 얇고 강렬한 코어
        c = _bloom_color(a_lo, a_hi, b)
        return (c[0], c[1], c[2], 255)             # 알파는 기하가 쥔다 (여기 값은 무시된다)
    return style


def _qi_bolts(salt, grade, series, spine, wfn):
    """22차 — 레퍼런스식 **작은 평면 번개 스프라이트** (사용자 레퍼런스 3프레임 · "적은 게 이긴다").
    21e 의 검 전체를 감싸는 3D 뇌전 케이지(`_qi_cage` · 과함·난잡)를 폐지하고, **칼끝·코등이
    두 키포인트에만**(`_R21_SPARK_ANCH`) 작은 얇은 청록 지그재그 아크를 얹는다. 각 스프라이트 =
    짧은 가는 채널(`_R21_SPARK_LEN` · 불규칙 꺾임) + 분기 1개(짧게 taper) · 거의 **평면**
    (얕은 ±z `_R21_SPARK_Z` — 케이지처럼 축 둘레를 감지 않는다) · float. 프레임마다 다른 볼트로
    번쩍(치지직 · `_bolt_style`/`_bolt_fire` 재사용 · frametime 2). 등급 = 세기·스프라이트 수
    (범철 0 → 신병 또렷 3 → 마병 격렬 검붉음 4). 원소 예산: 케이지(≈18) → 스프라이트(≈6)로 급감.
    반환: float 아크 셰이프 리스트."""
    inten, nb = _R21_BOLT.get(grade, (0, 0))
    if inten <= 0 or nb <= 0:                      # 범철 — 순수 병기 (뇌전 없음)
        return []
    if grade == "mabyeong":                        # 마병 — 검붉음 (계보 신호)
        a_lo, a_hi = BLOOD, CRIM_VHI
    elif _ACC_OVR[0]:                              # 명병 — 문파 악센트색
        a_lo, a_hi = _ACC_OVR[0]
    elif series in _R21_BOLT_ACCENT:               # 22차 뇌전 계열색 (검=옥/청록 레퍼런스 톤)
        a_lo, a_hi = _R21_BOLT_ACCENT[series]
    else:                                          # 나머지 계열 — SERIES_INK 악센트 (도=진홍 등)
        a_lo, a_hi = _ink8(series)[4], _ink8(series)[5]
    out = []
    # nb 스프라이트를 두 앵커(칼끝·코등이)에 배분 — 칼끝에 조금 더 (레퍼런스: 칼끝 2 · 코등이 1)
    ntip = (nb + 1) // 2
    plan = [(_R21_SPARK_ANCH[0], True)] * ntip + [(_R21_SPARK_ANCH[1], False)] * (nb - ntip)
    for si, (tt, at_tip) in enumerate(plan):
        bx0, by0 = _spine_at(spine, tt)            # ★호길이 보간 (검 2점·도 14점 둘 다)
        side = 1.0 if si % 2 == 0 else -1.0        # 앞/뒤로만 살짝 갈라 평면 유지 (감지 않는다)
        gap = _R21_SPARK_GAP + (0.0 if at_tip else _R21_SPARK_GAP_GUARD)  # 코등이 곁은 날개 밖으로 더
        rad = wfn(tt) + gap                        # 날 반폭 밖 빈 공간 (본체 불침 · 레퍼런스 톤)
        ax = bx0
        ay = by0 + side * rad
        z = round(_R21_SPARK_Z * side, 3)          # 얕은 ±z — 거의 검 평면 (평면 스프라이트)
        seed = si * 3.1 + salt * 0.17
        rnd = _lcg(si * 7.3 + salt * 0.37 + 2.0)
        # 흐름 방향 — 칼끝 스프라이트는 날축 밖으로 뻗치고, 코등이 스프라이트는 자잘하게 (잔 꺾임)
        mu = math.radians((20 if at_tip else -20) + 46 * (rnd() - 0.5))
        sx = ax - _R21_SPARK_LEN * 0.5 * math.cos(mu)   # 채널을 앵커 둘레로 중심 정렬
        sy = ay - _R21_SPARK_LEN * 0.5 * math.sin(mu)
        pts, angs = _bolt_channel(sx, sy, mu, _R21_SPARK_LEN, 4, rnd, jitter=64.0)
        main = _pn(_vd(_stroke(pts, _R21_BOLT_W, _bolt_style(a_lo, a_hi, inten, si, seed)),
                       (0.55, z, None, "float")), "뇌전")
        main._center = (round(ax, 3), round(ay, 3))     # 자기시험용 — 스프라이트 앵커 (두 키포인트 검증)
        out.append(main)
        # 분기 하나 — 짧게 taper (점점 사라짐 · 번개의 핵심)
        vj = 1 + int(rnd() * (len(pts) - 2))
        fx0, fy0 = pts[vj]
        fmu = angs[min(vj, len(angs) - 1)] + math.radians(46 + 30 * rnd()) * (1 if si % 2 else -1)
        flen = _R21_SPARK_LEN * (0.34 + 0.22 * rnd())

        fpts, _fa = _bolt_channel(fx0, fy0, fmu, flen, 2, rnd, jitter=44.0)

        def _ftap(u):
            return max(0.03, _R21_BOLT_W * 0.8 * (1.0 - 0.92 * u))
        fork = _pn(_vd(_stroke(fpts, _ftap,
                               _bolt_style(a_lo, a_hi, inten, si, seed, tail=True)),
                       (0.42, round(z * 0.85, 3), None, "float")), "뇌전")
        fork._center = (round(ax, 3), round(ay, 3))
        out.append(fork)
    return out


def _qi_cage(salt, grade, series, spine, wfn):
    """21e (보존 · `_R21_FX="cage"`) — 뇌전 아크 케이지: 날 둘레 float 껍질에 **가늘고 불규칙하고
    갈라지는 전격**을 축 둘레 helix(각도가 길이축 따라 돈다)+다중 z(앞·뒤·좌·우 · 코플래너 아님)로
    휘감아 배치 → 어느 각도서 봐도 번개가 검을 감싼다. 각 조각 = 가는 주 채널(불규칙 꺾임
    `_bolt_channel` · 균일 Z 아님) + 분기 fork 1~3개(짧게 taper — 점점 사라짐 · 번개의 핵심).
    프레임마다 점멸 → 감은 번개가 살아 튀는 착시. 기(氣)를 대신한다.
    반환: float 아크 셰이프 리스트. 등급 사다리 = 볼트 세기·조각 수 (범철 0 → 마병 격렬)."""
    inten, nb = _R21_CAGE.get(grade, (0, 0))       # 케이지는 원본(21e) 조각 수를 쓴다 (재현 보존)
    if inten <= 0 or nb <= 0:                      # 범철 — 순수 병기 (뇌전 없음)
        return []
    a_lo, a_hi = (BLOOD, CRIM_VHI) if grade == "mabyeong" else \
        (_ACC_OVR[0] if _ACC_OVR[0] else (_ink8(series)[4], _ink8(series)[5]))
    out = []
    for i in range(nb):
        tt = 0.14 + 0.74 * (i / max(1, nb - 1))    # 길이축 드문드문 (자루 곁 → 날끝)
        bx0, by0 = _spine_at(spine, tt)            # ★호길이 보간 (검 2점·도 14점 둘 다)
        theta = math.radians(40 + i * 97 + 240 * tt)   # helix — 각도가 길이축 따라 돈다 (휘감김)
        rad = wfn(tt) + _R21_SHELL_GAP             # 날 반폭 밖 (본체 불침 · 렌더된다)
        ax = bx0
        ay = by0 + rad * math.cos(theta)           # 날 중심선 밖 y (대각 순환)
        z = round(_R21_SHELL_Z * math.sin(theta), 3)   # z 부양 — 앞/뒤 (± 갈라 3D 감쌈)
        seed = i * 3.1 + salt * 0.17
        rnd = _lcg(i * 7.3 + salt * 0.37 + 1.0)
        mu = math.radians(6 + 30 * (rnd() - 0.5))      # 흐름 방향 — 대체로 날 따라(+x), 볼트마다 기움
        sx = ax - _R21_BOLT_LEN * 0.5 * math.cos(mu)   # 채널을 앵커 둘레로 중심 정렬
        sy = ay - _R21_BOLT_LEN * 0.5 * math.sin(mu)
        pts, angs = _bolt_channel(sx, sy, mu, _R21_BOLT_LEN, 6, rnd)
        out.append(_pn(_vd(_stroke(pts, _R21_BOLT_W, _bolt_style(a_lo, a_hi, inten, i, seed)),
                           (0.65, z, None, "float")), "뇌전"))
        nfork = 1 + int(rnd() * (2.99 if inten >= 1.0 else 1.99))   # 강 등급 1~3 · 약 1~2
        for f in range(nfork):
            vj = 1 + int(rnd() * (len(pts) - 2))       # 갈라지는 정점 (뿌리~중간)
            fx0, fy0 = pts[vj]
            fmu = angs[min(vj, len(angs) - 1)] + math.radians(42 + 34 * rnd()) * (1 if f % 2 else -1)
            flen = _R21_BOLT_LEN * (0.26 + 0.2 * rnd())    # 짧게
            fpts, _fa = _bolt_channel(fx0, fy0, fmu, flen, 2, rnd, jitter=40.0)

            def _ftap(u):                              # 뿌리 두께 → 끝 0 (점점 사라짐 · taper)
                return max(0.03, _R21_BOLT_W * 0.82 * (1.0 - 0.92 * u))
            out.append(_pn(_vd(_stroke(fpts, _ftap, _bolt_style(a_lo, a_hi, inten, i, seed, tail=True)),
                               (0.5, round(z * 0.8, 3), None, "float")), "뇌전"))
    return out


def _spec_sword_r21(salt, grade, rings, blen, tassel, mab):
    """검 — 만화(애니)풍(21차) + 22차 레퍼런스식 슬림 검신: 만화 셀 강철 검 + 대담한 날개 코등이
    + 작은 평면 번개 스프라이트(칼끝·코등이). 구조(볼록 단면·봉 자루·부위)는 20차 토대 그대로.
    ★22차 검신 슬림(레퍼런스 — 얇은 레이피어 톤): 반폭 1.5→1.4 (슬림) · 길이 21.4→22.4 (우아하게
    길게) · 테이퍼 tip 0.72→0.66·slim 0.09→0.10 (원위 수렴 더 매끄럽게). 볼록 단면 렌즈는 유지 —
    볼륨 자기시험 ⓐ(7단 렌즈 ≥5단)를 지키는 슬림 하한이다 (판(板) 회귀 방지 · 도는 대담 유지)."""
    gw = 0.66
    half = 1.4                                     # 22차 — 1.5→1.4 슬림 (레퍼런스 얇은 검신)
    tipx = 22.4 + blen                             # 22차 — 21.4→22.4 길게 (우아한 레이피어 톤)
    wfn = _taperw(half, tip=0.66, slim=0.10)       # 22차 — 원위 수렴 더 길고 매끄럽게
    spine = [(6.0, 8.0), (tipx, 8.0)]
    sh = [_pn(_stroke(spine, wfn, _r21_steel(salt, grade, "sword")), "검신")]
    g_sh, _gx = _guard_cross(salt, grade, 5.6, 4.2, 0.92, 1.7, manga=True)   # 대담한 날개 코등이
    h_sh, h_ex, lox, loy = _hilt_kit(salt, grade, rings, tassel, mab, -3.4, 5.0, gw,
                                     ink=_ink8("sword"), vein=False, traditional=True)
    qi = _qi_shell(salt, grade, "sword", spine, wfn, 8.0)          # 기/뇌전 껍질 (검을 감쌈)
    sr = _R21_SHELL_GAP + half + 2.1               # 껍질 반경(날 반폭+틈) — 케이지 토글도 담게 넉넉히
    box = (min(lox, -3.4 - 2.0, -1.2), min(loy, 8 - sr, 2.1), tipx + 2.6, 8 + sr)
    return box, sh + g_sh + h_sh + qi, h_ex


def _spec_dao_r21(salt, grade, rings, blen, tassel, mab, *, half=2.4, r_disc=2.4, tip_y=11.5):
    """도 — 만화(애니)풍(21차): 만화 셀 한날 도 + 대담한 원반 코등이 + 기 껍질(호 따라 흐름)."""
    tipx = 19.6 + blen
    bpts = _bez((6.5, 7.7), (13.5, 7.6), (tipx, tip_y), 14)
    wfn = _taperw(half, tip=0.78, slim=0.12)
    sh = [_pn(_stroke(bpts, wfn, _r21_steel(salt, grade, "dao", single=True, edge_side=-1.0)),
              "도신")]
    g_sh, g_ex = _guard_disc(salt, grade, 6.0, r_disc, manga=True)   # 대담한 원반 호수
    h_sh, h_ex, lox, loy = _hilt_kit(salt, grade, rings, tassel, mab, 1.3, 5.4, 0.72,
                                     ink=_ink8("dao"), vein=False, traditional=True)
    qi = _qi_shell(salt, grade, "dao", bpts, wfn, 8.0, single=True, edge_side=-1.0)
    box = (min(lox, -1.2), min(loy, 8 - half - _R21_SHELL_GAP - 1.6, 1.6),
           tipx + 2.8, tip_y + half + _R21_SHELL_GAP + 2.2)
    return box, sh + g_sh + h_sh + qi, g_ex + h_ex


def _spec_dagger(salt, grade, rings, blen, tassel, mab, *, poison=False):
    """비수 — 살상성은 '작고 응축됨'에서 (Codex §3): 기운 scale 0.58 로 본체가 주인공.
    13차: 날 연장 13.6→16.2 (날 55~62%【잠정】 — 실물 비수도 날이 자루보다 길다) ·
    자루 슬림 0.75→0.64. 전장은 검(31+)의 2/3 아래라 계열 변별은 산다."""
    tipx = 16.8 + blen
    calm17 = _R16 == "calm" and _R16_ALIGN         # 18차 — calm 전 계열 전파
    wm = (_jag(tipx - 6.0, 1.0) if mab
          else _crystal(tipx - 6.0, 1.0) if grade == "sinbyeong" and not calm17 else None)
    sh = [_pn(_stroke([(6.0, 8.0), (tipx, 8.0)], _taperw(1.5, tip=0.68),
                      _steel_style(salt, grade, poison=poison, series="dagger",
                                   vein=not calm17), wmod=wm),
              "비수날")]
    sh += _wisps(salt, tipx - 0.4, 8.0, grade, scale=0.58, series="dagger")
    g_sh, g_ex = _guard_cross(salt, grade, 5.5, 1.9, 0.6, flare=0.6)
    h_sh, h_ex, lox, loy = _hilt_kit(salt, grade, rings, tassel, mab, 1.0, 5.0, 0.64,
                                     tassel_k=0.78, ink=_ink8("dagger"), vein=not calm17)
    if calm17 and _QI_GRADE_CALM[grade][1]:        # 기맥 — 자루→비수 끝 한 획 (정본 §4-②)
        def _amp(mx, _tip=tipx):
            if mx < 6.0:
                return 0.24
            return min(0.45, max(0.1, (_tip - 0.55 - mx) * 0.18))
        sh += _qi_vein(salt, grade, "dagger", [(0.6, 8.0), (tipx - 0.3, 8.0)], _amp,
                       nvein=_QI_GRADE_CALM[grade][1])
    sh += _head_kit(salt, "dagger", grade, 4.9, 8.0, wing=(-0.8, 0.7))
    box = (min(lox, -1.5), min(loy, 2.8), tipx + 2.4, 13.4)
    return box, sh + g_sh + h_sh, g_ex + h_ex


def _spec_spear(salt, grade, rings, blen, tassel, mab):
    """창 — Codex §3 처방 그대로: 잎 반폭 3.3 · 머리 8.4+blen · 소켓 매듭 강화 ·
    홍영 등급화(범철·정련은 잿빛으로 잠재움) · 고리는 소켓 뒤에 모은다."""
    hx, tipx = 18.6, 27.0 + blen
    b8 = _ink8("spear")
    ink = b8[:2]
    calm17 = _R16 == "calm" and _R16_ALIGN         # 18차 — calm 전 계열 전파
    sh = [_pn(_vd(_stroke([(-10.5, 8.0), (hx, 8.0)], 0.62,
                          _grip_style(salt, hx + 10.5, wood=(grade == "beomcheol"),
                                      ink=ink if grade != "beomcheol" else None,
                                      grade=grade, acc=b8[4:6], vein=not calm17)),
              _vrod(0.62)), "자루")]
    # 13차 — 창은 자루가 길어야 하므로 창날 유지 · 자루 슬림 0.72→0.62 · 소켓 1.3→1.15 ·
    # 의장부만 축소 (_HEAD_FORM r 2.9→2.35 · 깃 배율 1.5→1.3)
    def leafw(t):
        return max(0.05, 3.8 * min(1.0, t * 3.0) ** 0.7 * (1.0 - t) ** 0.66)
    wm = (_jag(tipx - hx, 1.0) if mab
          else _crystal(tipx - hx, 1.0) if grade == "sinbyeong" and not calm17 else None)
    sh.append(_pn(_stroke([(hx, 8.0), (tipx, 8.0)], leafw,
                          _steel_style(salt, grade, hamon_w=0.45, series="spear",
                                       vein=not calm17), wmod=wm),
                  "창날"))
    sh.append(_pn(_vd(_stroke([(hx - 0.3, 8.0), (hx - 0.31, 8.0)], 1.15,
                              _brass_style(salt, grade)), _VD_ORN), "소켓"))
    if grade in _DECOR:                            # 보병 왕관 — 소켓 이중 띠 (§5)
        onbaek = mix(BLADE_HI, FIT_HI, 0.24)
        for bx_ in (hx - 1.15, hx - 1.85):
            sh.append(_pn(_vd(_stroke([(bx_, 6.9), (bx_, 9.1)], 0.22,
                                      _flat_style(mix(onbaek, FIT_MID, 0.5), onbaek)),
                              _VD_ORN), "소켓띠"))
    tp = _bez((hx - 1.0, 7.0), (hx - 2.4, 5.2), (hx - 3.8, 4.4), 8)      # 홍영
    sh.append(_pn(_vd(_stroke(tp, lambda t: 0.86 - 0.42 * t,
                              _tassel_style(salt, quiet=grade in ("beomcheol", "jeongryeon"),
                                            acc=b8[4:6])),          # 18차 — 호박 악센트 배선
                      _VD_DRAPE), "홍영"))
    sh.append(_pn(_vd(_stroke([(-10.9, 8.0), (-12.1, 8.0)], lambda t: 0.75 * (1 - t * 0.8),
                              _brass_style(salt, grade)), _VD_ORN), "준"))   # 준(鐏)
    sh += _wisps(salt, tipx - 0.5, 8.0, grade, scale=0.8, series="spear")
    extras = []
    for i in range(rings):                         # 고리 — 소켓 바로 뒤 (GUI 가독 §3)
        rx = hx - 3.6 - i * 1.2
        sh.append(_pn(_vd(_stroke([(rx, 8 - 1.1), (rx, 8 + 1.1)], 0.34, _ring_style()),
                          _VD_STUD), "등급고리"))
    if tassel:                                     # 물미 수실 — 홍영(앞)과 다른 뒤 z 평면 (11차)
        sp = _bez((-10.2, 7.2), (-11.2, 5.8), (-12.2, 5.0), 8)
        sh.append(_pn(_vd(_stroke(sp, lambda t: 0.8 - 0.35 * t,
                                  _tassel_style(salt, acc=b8[4:6])),
                          _vz(_VD_DRAPE, -0.9)), "수실"))
    if mab:
        sh.append(_pn(_vd(_stroke([(-10.6, 8.0), (-10.61, 8.0)], 0.55,
                                  _flat_style(BLOOD, BLOOD_HI)), _VD_ORN), "혈적"))
    if calm17 and _QI_GRADE_CALM[grade][1]:        # 기맥 — 간(자루)→창끝 한 획 (정본 §4-②)
        def _amp(mx, _tip=tipx, _hx=hx):
            if mx < _hx - 0.4:
                return 0.24                        # 긴 간 — 실이 가늘게 감긴다
            return min(0.6, max(0.1, (_tip - 0.7 - mx) * 0.2))
        sh += _qi_vein(salt, grade, "spear", [(-10.0, 8.0), (tipx - 0.45, 8.0)], _amp,
                       nvein=_QI_GRADE_CALM[grade][1])
    sh += _head_kit(salt, "spear", grade, hx - 2.4, 8.0, wing=(-1.1, 1.0))   # 자루 위 —
    return (-13.0, 2.1, tipx + 3.0, 14.0), sh, extras                        # 고리 속이 빈다


def _spec_gauntlet(salt, grade, rings, blen, tassel, mab, *, jade=False):
    """권갑 — Codex §3: 손등 판 테이퍼(3.0→2.45) · 마디 분리(반폭 0.8 · y 벌림 · 부채꼴 끝) ·
    보병 V능선 · 신병 옥 점 + 바깥 두 마디의 짧은 기운 · 마병은 바깥 두 마디가 발톱."""
    calm17 = _R16 == "calm" and _R16_ALIGN         # 18차 — calm 전 계열 전파
    sh = [_pn(_vd(_stroke([(4.2, 8.0), (9.6, 8.0)], lambda t: 3.0 - 0.55 * t,
                          _brass_style(salt, grade)), _vstud(2.6)), "손등판")]   # 손이 든 두께
    kn = ((5.4, 10.9), (7.2, 11.5), (9.0, 11.8), (10.8, 11.2))                       # (y, 끝 x)
    extras = []
    for i, (ky, kx) in enumerate(kn):
        if mab and i in (0, 3):                    # 마병 — 바깥 두 마디는 진홍 수정 발톱
            sh.append(_pn(_stroke([(10.3, ky), (kx + 1.4, ky + (0.55 if i == 3 else -0.55))],
                                  lambda t: max(0.14, 0.72 * (1 - 0.8 * t)),
                                  _steel_style(salt, grade, series="gauntlet", elem_t0=0.0,
                                               vein=not calm17)),
                          "마디"))
        else:
            sh.append(_pn(_vd(_stroke([(10.3, ky), (kx, ky)], 0.8,
                                      _brass_style(salt, grade)), _VD_ORN), "마디"))
    sh.append(_pn(_vd(_stroke(_bez((4.6, 5.0), (5.6, 4.2), (6.6, 4.0), 6), 1.0,
                              _brass_style(salt, grade)), _VD_ORN), "엄지"))
    sh.append(_pn(_vd(_stroke([(2.2, 4.9), (2.2, 11.1)], 1.1,
                              _grip_style(salt, 6.2, grade=grade, vein=not calm17)),
                      _vrod(1.1)), "손목띠"))                         # 손목을 감는 통
    sh.append(_pn(_vd(_stroke(_bez((1.8, 4.4), (1.2, 3.4), (0.6, 2.9), 6), 0.5,
                              _grip_style(salt, 3.0, grade=grade, vein=not calm17)),
                      _VD_DRAPE), "띠끝"))
    if grade in _DECOR:                            # 보병 왕관 — 손등 V자 온백 능선 (§5)
        onbaek = mix(BLADE_HI, FIT_HI, 0.24)
        for ya, yb in ((9.3, 8.05), (6.7, 7.95)):
            sh.append(_pn(_vd(_stroke([(5.0, ya), (7.2, yb)], 0.26,
                                      _flat_style(mix(onbaek, FIT_MID, 0.5), onbaek)),
                              _VD_ORN), "손등능선"))
    if grade == "sinbyeong":                       # 신병 — V자 중심의 옥 + 바깥 마디 기운
        sh.append(_pn(_vd(_gem(7.0, 8.0, 0.5, JADE), _vstud(3.4)), "상감"))
        for ky, kx in (kn[0], kn[3]):
            sh += _wisps(salt, kx + 0.1, ky, grade, scale=0.5, series="gauntlet")
    for n in range(rings):                         # 등급 — 띠 테
        ry = 9.9 - n * 1.9
        sh.append(_pn(_vd(_stroke([(1.0, ry), (3.4, ry)], 0.4, _ring_style()), _VD_STUD),
                      "등급고리"))
    if tassel:
        tp = _bez((1.4, 4.6), (0.6, 3.6), (-0.4, 3.0), 6)
        sh.append(_pn(_vd(_stroke(tp, lambda t: 0.7 - 0.3 * t,
                                  _tassel_style(salt, acc=_ink8("gauntlet")[4:6])),
                          _VD_DRAPE), "수실"))
    if mab:
        sh.append(_pn(_vd(_stroke([(6.9, 9.4), (6.91, 9.4)], 0.8,
                                  _flat_style(BLOOD, BLOOD_HI)), _vstud(3.4)), "혈적"))
    if jade:
        sh.append(_pn(_vd(_stroke([(6.9, 8.6), (6.91, 8.6)], 0.75,
                                  _flat_style(JADE, mix(JADE, MIST, 0.5))), _vstud(3.4)),
                      "상감"))
    if calm17 and _QI_GRADE_CALM[grade][1]:        # 기맥 — 손목띠→손등판→마디 한 획 (§4-②
        def _amp(mx):                              #   시작은 손목띠: 내공은 손목에서 든다 R3)
            return 0.2 if mx < 3.4 else 0.3
        sh += _qi_vein(salt, grade, "gauntlet",
                       [(2.2, 10.6), (2.2, 9.2), (3.6, 8.4), (6.0, 8.2), (9.9, 8.8),
                        (11.5, 9.0)], _amp, nvein=_QI_GRADE_CALM[grade][1])
    sh += _head_kit(salt, "gauntlet", grade, 6.4, 8.0, wing=(-0.9, 0.9))   # 손등 정면 의장
    return (-2.6, 1.2, 15.2, 13.8), sh, extras


def _spec_bu(salt, grade, rings, blen, tassel, mab):
    """부 — 성공 계열 (유지). 기운은 바깥 인선 중간(t≈0.72)의 접선을 따라 짧게 (Codex §3) ·
    보병 왕관 = 도끼 목 제2 놋띠 · 마병 혈조 = 날 뿌리→호 방향 (§4)."""
    b8 = _ink8("bu")
    ink = b8[:2]
    calm17 = _R16 == "calm" and _R16_ALIGN         # 18차 — calm 전 계열 전파
    sh = [_pn(_vd(_stroke([(-7.0, 8.0), (9.6, 8.0)], 0.68,
                          _grip_style(salt, 16.6, wood=(grade == "beomcheol"),
                                      ink=ink if grade != "beomcheol" else None,
                                      grade=grade, acc=b8[4:6], vein=not calm17)),
              _vrod(0.68)), "자루")]
    # 13차 — 타격부:자루 40:60 안팎(장병 특성 유지): 도끼 부채 유지 · 자루 슬림 0.8→0.68
    arc = _arcpts(8.6, 8.0, 3.6, -62, 62, 14)                                        # 도끼 부채
    wm = (_jag(9.0, 1.0) if mab
          else _crystal(9.0, 1.0) if grade == "sinbyeong" and not calm17 else None)
    sh.append(_pn(_stroke(arc, lambda t: 2.5 + 0.75 * math.sin(math.pi * t) + blen * 0.12,
                          _steel_style(salt, grade, single=True, edge_side=1.0,
                                       hamon_w=0.45, groove="root", series="bu",
                                       vein=not calm17), wmod=wm),
                  "부채날"))
    sh.append(_pn(_stroke(_arcpts(8.2, 8.4, 3.9, -95, -58, 6),
                          lambda t: 1.1 + 0.7 * t,
                          _steel_style(salt, grade, series="bu", vein=not calm17)),
                  "수염"))   # beard
    sh.append(_pn(_vd(_stroke([(7.0, 8.0), (7.01, 8.0)], 1.35,
                              _brass_style(salt, grade)), _VD_ORN), "도끼목"))
    if grade in _DECOR:                            # 보병 왕관 — 목 제2 놋띠 (§5)
        sh.append(_pn(_vd(_stroke([(7.85, 6.9), (7.85, 9.1)], 0.3,
                                  _brass_style(salt, grade)), _VD_ORN), "목띠"))
    sh.append(_pn(_vd(_stroke([(6.2, 8.0), (7.0, 8.0)], 1.15,
                              _flat_style(BLADE_SPINE, BLADE_DIM)), _VD_ORN), "폴"))   # 망치 등
    h_sh, h_ex, lox, loy = _hilt_kit(salt, grade, rings, tassel, mab, -7.0, 5.0, 0.68,
                                     ink=b8, vein=not calm17)
    wp = arc[10]                                   # 바깥 인선 중간 — 접선 좌표계 (§6)
    tang = (arc[11][0] - arc[9][0], arc[11][1] - arc[9][1])
    sh += h_sh + _wisps(salt, wp[0] + 1.7, wp[1], grade, scale=0.7, tangent=tang,
                        series="bu")
    if calm17 and _QI_GRADE_CALM[grade][1]:        # 기맥 — 자루→부채날 호 한가운데 (정본 §4-②)
        def _amp(mx):
            return 0.24 if mx < 6.8 else min(0.5, max(0.12, (14.6 - mx) * 0.2))
        sh += _qi_vein(salt, grade, "bu", [(-6.4, 8.0), (14.6, 8.0)], _amp,
                       nvein=_QI_GRADE_CALM[grade][1])
    sh += _head_kit(salt, "bu", grade, 6.4, 8.0, wing=(-1.0, 0.9))
    return (lox, 1.4, 16.4 + blen * 0.2, 14.4), sh, h_ex


def _spec_gyeom(salt, grade, rings, blen, tassel, mab):
    """겸 — 호를 176°로 열어(190→14) 목과 끝 사이 음영 공간 확보 + 끝 15% 바깥 휨(방향선) ·
    마병 톱니는 35~75% 구간만 (Codex §3) · 보병 왕관 = 슴베 목 온백 매듭."""
    sh = []
    calm17 = _R16 == "calm" and _R16_ALIGN         # 18차 — calm 전 계열 전파
    arc = _arcpts(11.0, 9.4, 3.7 + blen * 0.2, 190, 14, 16)                           # 낫의 호
    wm = (_jag(13.0, 1.0, 0.35, 0.75) if mab
          else _crystal(13.0, 1.0, 0.45, 0.9) if grade == "sinbyeong" and not calm17
          else None)
    sh.append(_pn(_stroke(arc, _taperw(2.0, tip=0.7, slim=0.35),
                          _steel_style(salt, grade, single=True, edge_side=1.0,
                                       hamon_w=0.4, groove="root", series="gyeom",
                                       vein=not calm17), wmod=wm),
                  "낫날"))
    ex_, ey_ = arc[-1]
    tang = (arc[-1][0] - arc[-2][0], arc[-1][1] - arc[-2][1])
    tl = math.hypot(*tang) or 1.0
    tang = (tang[0] / tl, tang[1] / tl)
    bend = (tang[0] * 0.8 - tang[1] * 0.32, tang[1] * 0.8 + tang[0] * 0.32)           # 바깥 휨
    sh.append(_pn(_stroke([(ex_, ey_), (ex_ + bend[0], ey_ + bend[1])],
                          lambda t: max(0.14, 0.5 - 0.36 * t),
                          _steel_style(salt, grade, series="gyeom", elem_t0=0.0,
                                       vein=not calm17)), "끝휨"))
    sh.append(_pn(_vd(_stroke([(7.3, 8.4), (7.31, 8.4)], 1.05,
                              _brass_style(salt, grade)), _VD_ORN), "슴베목"))
    if grade in _DECOR:
        sh.append(_knot(7.3, 9.5, 0.42))           # 보병 왕관 — 온백 매듭 (§5)
    h_sh, h_ex, lox, loy = _hilt_kit(salt, grade, rings, tassel, mab, -2.2, 7.0, 0.66,
                                     ink=_ink8("gyeom"),            # 13차 — 자루 슬림 0.78→0.66
                                     vein=not calm17)
    sh = h_sh + sh + _wisps(salt, ex_ + bend[0], ey_ + bend[1], grade, scale=0.75,
                            tangent=bend, series="gyeom")
    if calm17 and _QI_GRADE_CALM[grade][1]:        # 기맥 — 자루→슴베목→낫날 호→끝 (정본 §4-②)
        spine = [(-1.7, 8.0), (7.0, 8.35)] + [arc[i] for i in range(0, 15, 2)]
        def _amp(mx):
            return 0.24 if mx < 6.6 else 0.3       # 호 위에선 진폭만 낮게 (안쪽 인 존중)
        sh += _qi_vein(salt, grade, "gyeom", spine, _amp,
                       nvein=_QI_GRADE_CALM[grade][1])
    sh += _head_kit(salt, "gyeom", grade, 7.2, 8.0, wing=(-0.9, -0.9))
    return (min(lox, 0.6), min(loy, 2.2), 17.6 + blen * 0.3, 15.2), sh, h_ex


def _spec_wolasan(salt, grade, rings, blen, tassel, mab):
    """월아산 — Codex §3: 자루 12% 축소 · 달 r 4.2+0.18blen/폭 0.65+2.05sin · 삽날 확대 ·
    양단 명암 분리(달=밝은 인선 · 삽=어두운 앵커) · 고리는 날 목에 누적 · 짝 띠 왕관."""
    b8 = _ink8("wolasan")
    ink = b8[:2]
    calm17 = _R16 == "calm" and _R16_ALIGN         # 18차 — calm 전 계열 전파
    sh = [_pn(_vd(_stroke([(-11.0, 8.0), (13.7, 8.0)], 0.62,
                          _grip_style(salt, 24.7, ink=ink if grade != "beomcheol" else None,
                                      grade=grade, acc=b8[4:6], vein=not calm17)),
                  _vrod(0.62)), "자루")]           # 13차 — 자루 슬림 0.72→0.62 (양단 날 유지)
    # 17차 (R1 실측 — 월아 566px 고립): 달의 안쪽 인(15.9)이 자루 끝(≤14.6)에 닿지 않아
    # 초승달이 허공에 떠 있었다. 달목을 슴베처럼 초승 아가리 속(16.2)까지 뻗어 물린다 —
    # 실병 월아산의 결구(月牙는 자루 소켓의 슴베에 물린다). 정본 §2-월아산.
    sh.append(_pn(_vd(_stroke([(13.6, 8.0), (16.2, 8.0)], 1.0,
                              _brass_style(salt, grade)), _VD_ORN), "달목"))
    moon = _arcpts(14.7, 8.0, 4.2 + blen * 0.18, -78, 78, 16)                         # 초승달
    wm = (_jag(9.0, 1.0) if mab
          else _crystal(9.0, 1.0) if grade == "sinbyeong" and not calm17 else None)
    sh.append(_pn(_stroke(moon, lambda t: 0.7 + 2.3 * math.sin(math.pi * t),
                          _steel_style(salt, grade, hamon_w=0.45, series="wolasan",
                                       vein=not calm17), wmod=wm),
                  "월아"))
    st_ = _steel_style(salt, grade, hamon_w=0.45, groove="root", series="wolasan",
                       vein=not calm17)            # 18차 — 삽날도 calm 배선 (유보 해소)
    def spade_style(t, d0, w, side, mx, my):       # 삽날 — 어두운 앵커 (양단 명암 분리)
        return mix(st_(t, d0, w, side, mx, my), BLADE_DIM, 0.3)
    sh.append(_pn(_stroke([(-11.5, 8.0), (-15.5, 8.0)],
                          lambda t: 1.0 + 2.2 * min(1.0, t * 2.2), spade_style), "삽날"))
    sh.append(_pn(_vd(_stroke([(-11.3, 8.0), (-11.31, 8.0)], 0.95,
                              _brass_style(salt, grade)), _VD_ORN), "삽목"))
    extras = []
    if grade in _DECOR:                            # 보병 왕관 — 양단 목 짝 띠 (§5)
        onbaek = mix(BLADE_HI, FIT_HI, 0.24)
        for bx_ in (12.85, -10.55):
            sh.append(_pn(_vd(_stroke([(bx_, 7.0), (bx_, 9.0)], 0.24,
                                      _flat_style(mix(onbaek, FIT_MID, 0.5), onbaek)),
                              _VD_ORN), "목띠"))
    ring_xs = (12.2, -9.8, 11.3)                   # 고리 — 각 날 목에 하나씩 누적 (§3)
    for i in range(rings):
        rx = ring_xs[i]
        sh.append(_pn(_vd(_stroke([(rx, 8 - 1.1), (rx, 8 + 1.1)], 0.36, _ring_style()),
                          _VD_STUD), "등급고리"))
    if tassel:
        tp = _bez((-9.9, 7.2), (-10.7, 5.9), (-11.7, 5.2), 8)
        sh.append(_pn(_vd(_stroke(tp, lambda t: 0.75 - 0.32 * t,
                                  _tassel_style(salt, acc=b8[4:6])),
                          _VD_DRAPE), "수실"))
    if mab:
        sh.append(_pn(_vd(_stroke([(-11.1, 8.0), (-11.11, 8.0)], 0.5,
                                  _flat_style(BLOOD, BLOOD_HI)), _VD_ORN), "혈적"))
    tang = (moon[-1][0] - moon[-2][0], moon[-1][1] - moon[-2][1])
    sh += _wisps(salt, moon[-1][0], moon[-1][1], grade, scale=0.75, tangent=tang,
                 series="wolasan")
    if calm17 and _QI_GRADE_CALM[grade][1]:        # 기맥 — 자루→달목→월아 배 한 획 (정본 §4-②
        r_moon = 4.2 + blen * 0.18                 #   양끝 병기의 본류는 월아 쪽 — 이름의 끝)
        def _amp(mx):
            return 0.22 if mx < 13.5 else 0.34
        sh += _qi_vein(salt, grade, "wolasan",
                       [(-10.4, 8.0), (14.7 + r_moon + 2.1, 8.0)], _amp,
                       nvein=_QI_GRADE_CALM[grade][1])
    sh += _head_kit(salt, "wolasan", grade, 13.1, 8.0, wing=(-1.0, 0.85))
    if _ORN[grade] >= 3:                           # 쌍월 — 뒤(삽날) 목에도 소형 고리 (계열 변주)
        b8 = _ink8("wolasan")
        o2 = ((mix(b8[2], WPN_OUT, 0.5), mix(b8[3], WPN_OUT, 0.45)) if mab
              else (b8[2], b8[3]))
        sh.append(_pn(_vd(_ring_orn(-10.6, 8.0, 1.5, 0.48, o2[0], o2[1]),
                          _vrot((1.1, 0.0, None), -22.5, -10.6, 8.0)), "의장고리"))   # (11차)
    return (-16.6, 2.2, 22.4 + blen * 0.18, 14.3), sh, extras


def _spec_gu(salt, grade, rings, blen, tassel, mab):
    """구 — 개성 계열 (유지). 갈고리 접합만 겹치고(시작각 −85→−100 — 계단 끊김 제거) ·
    보병 왕관 = 미늘 뿌리 온백 매듭 · 기운은 갈고리 끝 접선으로 (Codex §3·§6)."""
    tipx = 15.2 + blen
    calm17 = _R16 == "calm" and _R16_ALIGN         # 18차 — calm 전 계열 전파
    wm = (_jag(tipx - 6.2, 1.0) if mab
          else _crystal(tipx - 6.2, 1.0) if grade == "sinbyeong" and not calm17 else None)
    sh = [_pn(_stroke([(6.2, 8.0), (tipx, 8.0)], _taperw(1.38, tip=0.9, slim=0.15),
                      _steel_style(salt, grade, series="gu", vein=not calm17), wmod=wm),
              "구신")]
    hook = _arcpts(tipx - 0.3, 9.7, 2.05, -100, 60, 11)                               # 되꺾인 끝
    sh.append(_pn(_stroke(hook, lambda t: 0.8 * (1 - 0.5 * t),
                          _steel_style(salt, grade, series="gu", elem_t0=0.0,
                                       vein=not calm17)), "갈고리"))
    sh.append(_pn(_stroke([(tipx - 1.6, 9.0), (tipx - 2.5, 9.9)], 0.4,
                          _steel_style(salt, grade, series="gu", elem_t0=0.0,
                                       vein=not calm17)), "미늘"))
    if grade in _DECOR:
        sh.append(_knot(tipx - 1.5, 8.75, 0.4))    # 보병 왕관 — 미늘 뿌리 매듭 (§5)
    guard = _arcpts(6.0, 8.0, 1.55, 95, 265, 10)                                      # 초승 호수
    sh.append(_pn(_vd(_stroke(guard, 0.5, _brass_style(salt, grade)), _VD_ORN), "코등이"))
    h_sh, h_ex, lox, loy = _hilt_kit(salt, grade, rings, tassel, mab, 1.0, 5.2, 0.68,
                                     ink=_ink8("gu"),               # 13차 — 자루 슬림 0.8→0.68
                                     vein=not calm17)
    if calm17 and _QI_GRADE_CALM[grade][1]:        # 기맥 — 자루→구신→갈고리 되꺾임 (정본 §4-②)
        spine = [(0.6, 8.0), (tipx - 1.6, 8.0), (hook[0][0], hook[0][1])] \
            + [hook[i] for i in range(2, 10, 2)]
        def _amp(mx, _tip=tipx):
            return 0.24 if mx < 6.4 else min(0.4, max(0.1, (_tip - 0.7 - mx) * 0.16))
        sh += _qi_vein(salt, grade, "gu", spine, _amp, nvein=_QI_GRADE_CALM[grade][1])
    sh += _head_kit(salt, "gu", grade, 5.9, 8.0, wing=(-0.9, 0.85))
    tang = (hook[-1][0] - hook[-2][0], hook[-1][1] - hook[-2][1])
    sh += _wisps(salt, hook[-1][0], hook[-1][1], grade, scale=0.68, tangent=tang,
                 series="gu")
    return (min(lox, -1.2), min(loy, 2.2), tipx + 3.2, 14.0), sh + h_sh, h_ex


WEAPON_SPEC = {
    "sword": _spec_sword, "dao": _spec_dao, "spear": _spec_spear,
    "gauntlet": _spec_gauntlet, "dagger": _spec_dagger,
    "bu": _spec_bu, "gyeom": _spec_gyeom, "wolasan": _spec_wolasan, "gu": _spec_gu,
}


# ─── 명병 스펙 12벌 — 확정 실루엣 유지 (곤륜 두 갈래·해남 파도·개방 매듭 등) ─────────
# 15차 — 문파 문양의 잔선 스타일 (구도·자리·색 정체성 불변 · 색만 바뀌므로 기하 무수정):
def _petal15(cx, cy, fx, fy):
    """화산 매화 꽃잎 — 플랫 2단 + 잎맥 (15차: 꽃심(fx, fy)에서 뻗는 방사 실선)."""
    vx, vy = cx - fx, cy - fy
    vl = math.hypot(vx, vy) or 1e-9
    vx, vy = vx / vl, vy / vl
    def style(t, d0, w, side, mx, my):
        c = MHWA_VHI if d0 / w < 0.55 else MHWA_V
        if _D15:
            perp = abs((mx - fx) * (-vy) + (my - fy) * vx)
            along = (mx - fx) * vx + (my - fy) * vy
            if perp < 0.14 and along > vl * 0.4 and d0 / w < 0.8:
                c = mix(MHWA_V, DK_PLUM, 0.4)      # 꽃잎 맥
        return c
    return style


def _stamen15(cx, cy):
    """매화 꽃술 — 15차: 금 낱알 (심 한 점 + 각도 5분할 낱알 — 원판이 알갱이로 읽힌다)."""
    def style(t, d0, w, side, mx, my):
        if not _D15:
            return GOLD_VHI if d0 / w < 0.55 else GOLD_V
        if math.hypot(mx - cx, my - cy) < w * 0.3:
            return GOLD_VHI                        # 꽃술 심
        a = math.atan2(my - cy, mx - cx)
        return GOLD_VHI if int((a + math.pi) / (2 * math.pi) * 5.0) % 2 == 0 else GOLD_V
    return style


def _wave15(cx, cy, r):
    """해남 파도 — 흰 획 2단 + 물거품 점 (15차: 바깥 마루를 따라 점점이 성긴 거품)."""
    lo, hi = mix(MIST, BLADE_MID, 0.3), MIST
    def style(t, d0, w, side, mx, my):
        c = hi if d0 / w < 0.55 else lo
        if _D15 and math.hypot(mx - cx, my - cy) > r + w * 0.15 and int(t * 15.0) % 3 == 0:
            c = mix(MIST, MOON_V, 0.6)             # 물거품 점
        return c
    return style


def _cloud15(cx, cy, r):
    """곤륜 답운 — 흰 획 2단 + 소용돌이 잔획 (15차: 구름이 감긴다 — 나선 위상 실선)."""
    lo, hi = mix(MIST, BLADE_MID, 0.3), MIST
    def style(t, d0, w, side, mx, my):
        c = hi if d0 / w < 0.55 else lo
        if _D15:
            dd = math.hypot(mx - cx, my - cy)
            sp = dd / r * 1.2 - math.atan2(my - cy, mx - cx) / math.pi
            if dd > r * 0.22 and abs(sp - round(sp)) < 0.22:
                c = mix(hi, BLADE_MID, 0.45)       # 소용돌이 획 — 감기는 초승 획
        return c
    return style


def _myeong_spec(sect, salt):
    """명병 — 8차: 문파 문양이 의장 테마다. 악센트를 문파 색으로 상속(_SECT_ACCENT)하고
    화산은 파편이 매화 꽃잎이 된다. 컨텍스트는 반드시 푼다 (결정론 — 다음 자루에 안 샌다)."""
    _ACC_OVR[0] = _SECT_ACCENT.get(sect)
    _PETAL_OVR[0] = sect == "hwasan"
    _MOTIF_OVR[0] = _MYEONG_MOTIF.get(sect)       # 18차 — 문양의 기맥 번역 (_qi_vein 이 읽는다)
    try:
        return _myeong_spec_inner(sect, salt)
    finally:
        _ACC_OVR[0] = None
        _PETAL_OVR[0] = False
        _MOTIF_OVR[0] = None


def _myeong_spec_inner(sect, salt):
    g = _GRADE_FORM["sinbyeong"]
    rings, blen, tassel, mab = g
    calm17 = _R16 == "calm" and _R16_ALIGN         # 18차 — calm 전파 (명병 12 동행)
    if sect == "hwasan":                          # 매화검 — 의장 한복판에 매화 왕관 (8차:
        box, sh, ex = _spec_sword(salt, "sinbyeong", *g)   # 고리 속 매화 + 꽃잎 파편)
        for i in range(5):                        # 13차 — 머리 중심 3.7→3.2 를 따라간다
            a = math.radians(90 + i * 72)
            px_, py_ = 3.2 + 1.15 * math.cos(a), 8 + 1.15 * math.sin(a)
            sh.append(_pn(_vd(_stroke([(px_, py_)] * 2, 0.72,
                                      _petal15(px_, py_, 3.2, 8.0)), _vorb(0.72)),
                          "매화잎"))               # 꽃잎+잎맥 (15차)
        sh.append(_pn(_vd(_stroke([(3.2, 8.0), (3.21, 8.0)], 0.5,
                                  _stamen15(3.2, 8.0)), _vorb(0.62)), "꽃술"))   # 잎보다 튄다
        return box, sh, ex
    # 13차 — 검 신병 기본 tipx 가 19.6→25.8 로 늘며 문파 변주도 동행 (상대 서열 불변:
    # 남궁(최장 27.8) > 점창(빠르고 얇게 27.2) > 청성(27.0) > 해남(26.6) > 종남(최중·짧고
    # 두껍다 25.4) — 자루 슬림도 동행).
    if sect == "jeomchang":                       # 쾌검 — 가장 얇다
        return _spec_sword(salt, "sinbyeong", *g, half=1.05, tipx=27.2, hlen=2.0,
                           gword=0.55, flare=0.5, gw=0.62)
    if sect == "jongnam":                         # 중검 — 가장 두껍다
        return _spec_sword(salt, "sinbyeong", *g, half=2.55, tipx=25.4, hlen=4.1,
                           gword=1.0, flare=0.0, gw=0.9)
    if sect == "namgung":                         # 장검 — 가장 길다
        return _spec_sword(salt, "sinbyeong", *g, half=1.45, tipx=27.8, hlen=3.6, flare=1.1)
    if sect == "mudang":                          # 태극검 — 원반 + 태극
        box, sh, ex = _spec_sword(salt, "sinbyeong", *g, half=1.55, hlen=0.0, flare=0.0)
        d_sh, d_ex = _guard_disc(salt, "sinbyeong", 5.6, 2.0)
        sh += d_sh
        def taegeuk(t, d0, w, side, mx, my):      # 음양 — S 경계 (옥/먹)
            sb = (my - 8.0) - 0.32 * math.cos((mx - 5.6) * 2.4)
            if _D15 and abs(sb) < 0.26:           # 15차 — 태극 경계 이중선 (먹 심 + 흰 겹선)
                return mix(BLADE_SPINE, WPN_OUT, 0.45) if abs(sb) < 0.12 \
                    else mix(MIST, JADE, 0.35)
            return JADE if sb > 0 else mix(BLADE_SPINE, MIST, 0.25)
        sh.append(_pn(_vd(_stroke([(5.6, 8.0), (5.61, 8.0)], 1.15, taegeuk), _vstud(4.2)),
                      "태극"))
        return box, sh, ex + d_ex
    if sect == "paengga":                         # 오호단문도 — 최광폭
        box, sh, ex = _spec_dao(salt, "sinbyeong", *g, half=2.8, r_disc=2.3, tip_y=11.8)
        tp = _bez((1.0, 6.8), (0.2, 5.6), (-0.8, 5.0), 6)
        sh.append(_pn(_vd(_stroke(tp, lambda t: 0.7 - 0.3 * t,
                                  _tassel_style(salt, acc=_ink8("dao")[4:6])),
                          _VD_DRAPE), "수실"))    # 18차 — 진홍 상속 배선 (옥 폴백 폐지)
        return box, sh, ex
    if sect == "dangga":                          # 비수 — 독 오른 끝
        return _spec_dagger(salt, "sinbyeong", *g, poison=True)
    if sect == "sorimsa":                         # 권갑 — 반야의 옥
        return _spec_gauntlet(salt, "sinbyeong", *g, jade=True)
    if sect == "gonryun":                         # 양의검 — 두 갈래 (양 밝고 음 어둡다)
        box, sh, ex = _spec_sword(salt, "sinbyeong", *g, half=0.0, tipx=20.6, hlen=2.6,
                                  gword=0.6, flare=0.6,
                                  # 기맥 — 양(밝은 갈래)을 타고 흘러 스러지는 끝에서 맺는다
                                  # (가운데는 갈래 사이 허공 — 한 획은 몸 위로만 지난다 · R3)
                                  qi_spine=[(-4.5, 8.0), (5.0, 8.0), (5.65, 8.6),
                                            (6.8, 8.85), (20.0, 9.0), (20.9, 8.1),
                                            (22.55, 8.0)],
                                  qi_amp=lambda mx: 0.26 if mx < 6.1
                                  else (0.28 if mx < 20.4 else 0.1))
        sh = [s for s in sh]                      # 두 갈래는 의장 고리 위를 지나 4.6 부터 세인다 —
        sh.append(_pn(_stroke([(6.1, 8.8), (20.6, 9.05)], 0.62,       # 짧은 tipx 로도 날%가 상단
                              _steel_style(salt, "sinbyeong", series="sword",
                                           vein=not calm17)), "검신"))   # 양
        sh.append(_pn(_stroke([(6.1, 7.2), (20.6, 6.95)], 0.62,
                              _steel_style(salt, "sinbyeong", dark=True,
                                           vein=not calm17)), "검신"))        # 음
        sh.append(_pn(_stroke([(20.6, 8.0), (23.0, 8.0)], _taperw(0.6, tip=0.55),
                              _steel_style(salt, "sinbyeong", dark=True,
                                           vein=not calm17)), "검신"))   # 스러지는 끝
        for i, (cx, cy, r) in enumerate(((1.5, 10.3, 0.75), (2.7, 10.8, 0.55),
                                         (0.6, 10.7, 0.45))):     # 답운 — 구름 세 점
            sh.append(_pn(_vd(_stroke([(cx, cy), (cx + 0.01, cy)], r,
                                      _cloud15(cx, cy, r)),        # 소용돌이 잔획 (15차)
                              _vz(_VD_SHARD, (2.4, -2.6, 3.0)[i])), "답운"))   # z 산개 (11차)
        return (box[0], box[1], 23.8, max(box[3], 11.4)), sh, ex
    if sect == "cheongseong":                     # 송풍검 — 3단 좁아지는 돌진 날
        def stepw(t):
            if t < 0.4:
                return 1.7
            if t < 0.72:
                return 1.2
            if t < 0.93:
                return 0.7
            return max(0.05, 0.7 * (1 - (t - 0.93) / 0.07))
        box, sh, ex = _spec_sword(salt, "sinbyeong", *g, half=1.7, tipx=27.0, hlen=2.4,
                                  gword=0.6, flare=0.5)
        sh[0] = _pn(_stroke([(6.1, 8.0), (27.0, 8.0)], stepw,
                            _steel_style(salt, "sinbyeong", series="sword",
                                         vein=not calm17)), "검신")   # 송옥 날
        moon = _arcpts(0.4, 10.4, 0.9, -60, 200, 10)                      # 청월 — 푸른 달 한 호
        sh.append(_pn(_vd(_stroke(moon, 0.3,
                                  _flat_style(mix(MIST, BLADE_MID, 0.3), MIST)),
                          _VD_SHARD), "청월"))
        return (box[0], box[1], box[2], max(box[3], 11.8)), sh, ex
    if sect == "haenam":                          # 역수검 — 뒤로 쏠린 코등이 + 남해 파도
        box, sh, ex = _spec_sword(salt, "sinbyeong", *g, half=1.15, tipx=26.6, hlen=0.0,
                                  flare=0.0, gw=0.66)
        sh += [_pn(_vd(_stroke([(5.7, 8.6), (4.6, 10.4)], 0.62, _brass_style(salt, "sinbyeong")),
                       _VD_ORN), "코등이"),
               _pn(_vd(_stroke([(5.7, 7.4), (4.6, 5.6)], 0.62, _brass_style(salt, "sinbyeong")),
                       _VD_ORN), "코등이")]
        for i, (cx, cr) in enumerate(((9.5, 1.9), (13.0, 1.5))):          # 파도 두 굽이 — 크게
            wave = _arcpts(cx, 4.4, cr, 15, 165, 8)
            sh.append(_pn(_vd(_stroke(wave, 0.42,
                                      _wave15(cx, 4.4, cr)),       # 물거품 점 (15차)
                              _vz(_VD_SHARD, (2.4, -2.6)[i])), "파도"))   # 앞/뒤 z (11차)
        return (box[0], 2.8, box[2], box[3]), sh, ex
    # gaebang — 타구봉: 날 없는 봉 + 삼줄 매듭 셋 + 끈 꼬리 (쇠 장식 0)
    sh = [_pn(_vd(_stroke([(-11.5, 8.0), (13.5, 8.0)], 0.72,
                          _grip_style(salt, 25.0, wood=True, vein=not calm17)),
                  _vrod(0.72)), "봉")]   # 13차 슬림
    extras = []
    for kx in (-5.5, 0.5, 6.5):
        sh.append(_pn(_vd(_stroke([(kx, 8 - 1.35), (kx, 8 + 1.35)], 0.6, _rope_style(salt)),
                          _vstud(2.3)), "매듭"))   # 매듭 — 봉(1.64)을 감고 z 로도 튄다 (11차)
    tail = _bez((6.6, 6.9), (7.4, 5.6), (8.4, 4.9), 8)
    sh.append(_pn(_vd(_stroke(tail, lambda t: 0.45 - 0.15 * t, _rope_style(salt)),
                      _VD_DRAPE), "끈꼬리"))
    sh.append(_pn(_vd(_stroke([(-11.9, 8.0), (-11.4, 8.0)], 0.76,
                              _flat_style(GRIP_MID, GRIP_HI)), _vrod(0.76)), "봉마디"))
    sh.append(_pn(_vd(_stroke([(13.4, 8.0), (13.9, 8.0)], 0.76,
                              _flat_style(GRIP_MID, GRIP_HI)), _vrod(0.76)), "봉마디"))
    if calm17:                                     # 기맥 — 봉을 타는 새끼줄 맞꼬임 (18차 번역:
        sh += _qi_vein(salt, "sinbyeong", "sword",  # _MOTIF_OVR="새끼줄" · 악센트는 _ACC_OVR
                       [(-11.2, 8.0), (13.6, 8.0)],  # 이 볏짚(ROPE)으로 상속 — series 인자는
                       lambda mx: 0.3, nvein=2)      # _ink8 경유용이다)
    return (-13.4, 3.6, 15.4, 11.2), sh, extras


_MYEONG_MARK_INK = {"hwasan": (mix(PLUM, MIST, 0.34), mix(PLUM_HI, MIST, 0.3)), "mudang": (JADE, MIST),
                    "sorimsa": (JADE, MIST), "dangga": (POISON, MIST),
                    "gonryun": (mix(MIST, BLADE_MID, 0.3), MIST),
                    "cheongseong": (mix(MIST, BLADE_MID, 0.3), MIST),
                    "haenam": (mix(MIST, BLADE_MID, 0.3), MIST), "gaebang": (ROPE_DIM, ROPE)}


_SERIES_FRAME = {}          # 계열 → 5등급 합집합 box (Codex §3 — 프레임을 등급이 공유해야
                            # blen 성장이 정규화에 지워지지 않는다). 결정론 캐시.


def _series_frame(series):
    """계열 공통 프레임 — 5등급 스펙의 box 합집합 (신병 수실·마병 기운 포함).
    같은 프레임 = 같은 시트 배율 + 같은 GUI extent + 같은 앵커 ⇒ 호수가 한 자리에 서고
    범철→신병의 날 성장이 화면 픽셀로 남는다 (Codex 1단계 합격 기준)."""
    if series not in _SERIES_FRAME:
        lo_x = lo_y = 1e9
        hi_x = hi_y = -1e9
        for g, (rings, blen, tassel, mab) in _GRADE_FORM.items():
            box, _sh, _ex = WEAPON_SPEC[series](0, g, rings, blen, tassel, mab)
            lo_x, lo_y = min(lo_x, box[0]), min(lo_y, box[1])
            hi_x, hi_y = max(hi_x, box[2]), max(hi_y, box[3])
        _SERIES_FRAME[series] = (lo_x, lo_y, hi_x, hi_y)
    return _SERIES_FRAME[series]


def _anchor(elems, frame):
    """프레임 중심을 (8,8)에 앵커 — 개별 bbox 가 아니라 **계열 공통 프레임**이 기준이다.
    다섯 등급이 같은 이동량을 받으므로 호수/손잡이가 GUI 에서 같은 자리에 선다."""
    dx = 8.0 - (frame[0] + frame[2]) / 2.0
    dy = 8.0 - (frame[1] + frame[3]) / 2.0
    for e in elems:
        for kk in ("from", "to"):
            e[kk] = [round(e[kk][0] + dx, 3), round(e[kk][1] + dy, 3), round(e[kk][2], 3)]
        if "rotation" in e:                        # 회전 원점도 함께 이동 (11차 — 벌어진 깃)
            o = e["rotation"]["origin"]
            e["rotation"]["origin"] = [round(o[0] + dx, 3), round(o[1] + dy, 3), o[2]]
        for i in range(3):
            if not (-16 <= e["from"][i] <= 32 and -16 <= e["to"][i] <= 32):
                raise ValueError(f"원소가 모델 상자를 벗어났다: {e['from']} → {e['to']}")
    return elems, max(frame[2] - frame[0], frame[3] - frame[1])


def _body_extent(rows, dep, k):
    """본체 extent (모델 단위, 12차 · 17차 역할 태그) — **float 역할**(수실 _VD_DRAPE ·
    파편 _VD_SHARD/_vshard)만 뺀 불투명 픽셀 bbox 의 최대 변. 사용자 3인칭 실측 "검이
    작다"의 처방: 프레임 extent 는 z 산개 파편·드리개까지 합친 합집합이라 분모가 부풀어
    본체가 과축소됐다. **그림·기하는 그대로**, hand/fp 스케일 분모만 이것으로 잰다
    (GUI 는 그림 전부가 칸에 들어야 하므로 종전 extent).
    17차 (Codex §6): 종전의 "단면 ≤0.9 = 부유물" 깊이 문턱을 폐기 — 얇은 필리그리 깃
    (0.9, y회전, 본체 접촉)이 부유물로 오분류돼 분모가 흔들렸다. 깊이는 역할이 아니다:
    body/attached 는 분모에 들고 float 만 뺀다 (_vrole — 익명 회전 태그는 attached)."""
    x0 = y0 = 10 ** 9
    x1 = y1 = -1
    for y in range(_CANVAS_Y0, _SHEET):
        for x in range(_SHEET):
            if rows[y][x][3] <= 8:
                continue
            t = dep[y][x]
            if t is not None and _vrole(t) == "float":
                continue
            x0, x1 = min(x0, x), max(x1, x)
            y0, y1 = min(y0, y), max(y1, y)
    if x1 < 0:                                     # 본체가 없다 — 있을 수 없지만 안전
        return None
    # 14차: bbox 를 13차(64px) 셀 점유로 양자화 — 해상도가 올라도 분모(=display 스케일)는
    # 12·13차와 같은 자로 잰다 (display 불변 계약 — 미세 픽셀이 아니라 굵은 셀이 스팬을
    # 정한다. 64px 로 되돌리면 _PXS=1 — 무연산). 실루엣이 굵은 셀 하나를 통째로 잃을 만큼
    # 조여진 경우만 값이 움직인다 (그때는 캡 공식이 손 크기 24px 계약을 지키는 쪽이 옳다).
    span = max(x1 // _PXS - x0 // _PXS + 1, y1 // _PXS - y0 // _PXS + 1)
    return span * _PXS / k


_SERIES_BODY = {}         # 계열 → 5등급 본체 extent 최대값 (프레임과 같은 이유로 등급 공유:
_COMPOSED = {}            # 같은 분모 = 같은 스케일 = 호수가 한 자리). 합성 결과도 캐시해
                          # 본 굽기(weapon_model_3d → _paint_model)가 같은 판을 재사용한다.


def _series_body_extent(series):
    """계열 공통 본체 extent — 5등급 본체 bbox 의 최대 변 중 최댓값 (결정론 캐시)."""
    if series not in _SERIES_BODY:
        fr = _series_frame(series)
        hi = 0.0
        for g, (rings, blen, tassel, mab) in _GRADE_FORM.items():
            salt = zlib.crc32(f"{series}_{g}".encode()) & 0x7F
            _box, shapes, _ex = WEAPON_SPEC[series](salt, g, rings, blen, tassel, mab)
            comp = _compose(shapes, fr, salt, g)
            _COMPOSED[(series, g)] = comp
            hi = max(hi, _body_extent(comp[0], comp[2], comp[1]) or 0.0)
        _SERIES_BODY[series] = round(hi, 3)
    return _SERIES_BODY[series]


# ═══ 17차 — 연속성 자기시험 (정본 §3 의 기계 집행 · 위반 = 빌드 실패) ═══════════════
_CONT_CAP_U = 1.25      # R2 — 같은 부위 구성이 이어지는 인접 열의 실루엣 상/하연 단차 상한
                        #      (모델 단위 【잠정】. 마병 톱니 0.5u·결정 단 0.9u·테이퍼는 아래,
                        #       부위 경계의 의도된 확폭은 구성 변화로 예외 — 정본 §3-R2)
_QI_TIP_REACH_U = 2.5   # R3 — 기맥이 "날끝에 닿았다"고 보는 잔여 거리 (모델 단위 【잠정】)
# R2 의 사정거리 — **곧은 축 부위**만 잰다 (x 를 따라 길게 눕는 직선 구조).
# ① 여러 조각이 한 이름을 쓰는 장식(의장깃 3장·소켓띠 2줄·고리)은 열 구성 비교가 경계를
#    못 갈라 거짓 양성 (실측: 깃·고리 접선 열) ② 곡선 부위(낫날·부채날·월아·갈고리·수염·
#    끝휨)는 안쪽 벽의 기울기가 수직에 접근해 열 단차가 **형태의 성질**이다 (실측: 겸의
#    아가리 12px). 그들의 연속성은 R1(연결)이 잰다. 전부 【잠정】 — 정본 §3-R2.
_R2_AXIAL = frozenset(("검신", "도신", "비수날", "창날", "구신", "삽날", "자루", "봉"))


def _components(pix):
    """8-이웃 연결 성분 — 결정론 (씨앗 = 정렬 최솟값 · 난수 0)."""
    left = set(pix)
    out = []
    while left:
        seed = min(left)
        left.discard(seed)
        stack, comp = [seed], [seed]
        while stack:
            x, y = stack.pop()
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    p = (x + dx, y + dy)
                    if p in left:
                        left.discard(p)
                        stack.append(p)
                        comp.append(p)
        out.append(comp)
    return out


def _continuity_check(key, rows, k, dep, parts, qi, series=None, qi_required=None):
    """연속성 정본 (weapon_anatomy_canon.md §3) — 네 눈:
      R1 본체 연결 — body/attached 불투명 픽셀은 정확히 1개의 8-연결 성분 (고립 덩어리 0).
         float(수실·파편·광점·구름 등 — 역할 태그)만 떠도 된다.
      R2 단면 연속 — 부위 구성이 같은 인접 x-열 사이 실루엣 상/하연 단차 ≤ _CONT_CAP_U
         (부위 경계 = 열의 부위 구성 변화 = 의도된 확폭·전이 — 예외).
      R3 기맥 연속 — 기맥(오버레이)은 1개 성분이고 자루 위에서 시작해 날끝까지 닿는다.
         qi_required 인데 오버레이가 없으면 그것도 위반이다 (스타일 속 무늬는 부위마다
         끊긴다 — 연속성을 증명할 수 없는 기맥은 기맥이 아니다).
      R4 부위 순서 — _PART_ORDER 의 (a, b) 쌍마다 무게중심 x(a) < x(b).
    + 태그 위생: 모든 본체 픽셀은 PART_CANON 의 이름을 가진다 (문서·코드 1:1)."""
    n = len(rows)
    if series is None:
        if key.startswith("myeong/"):
            sect = key[7:]
            series = "gaebang" if sect == "gaebang" else MYEONG_BASE[sect]
        else:
            series = key.rsplit("_", 1)[0]
    if qi_required is None:
        qi_required = False
    errs = []
    body = {}
    solid = set()          # α≥248 — R2 는 실루엣 본체만 잰다 (SDF 가장자리 램프의 모서리
    bad_name = set()       # 라운딩은 단차가 아니다 — 첫 실측: 소켓띠 끝의 5px 거짓 양성)
    unnamed = 0
    for y in range(_CANVAS_Y0, n):
        row = rows[y]
        for x in range(n):
            if row[x][3] <= 8:
                continue
            name = parts[y][x]
            if name is None:
                unnamed += 1
                continue
            if name not in PART_CANON:
                bad_name.add(name)
                continue
            t = dep[y][x]
            if (t is None) or _vrole(t) != "float":
                body[(x, y)] = name
                if row[x][3] >= 248:
                    solid.add((x, y))
    if unnamed:
        errs.append(f"이름 없는 본체 픽셀 {unnamed}개 — 모든 셰이프는 _pn 태그를 가진다")
    if bad_name:
        errs.append(f"미등록 부위 태그 {sorted(bad_name)} — PART_CANON(정본 §1~§2)에 없다")
    # ── R1 본체 연결
    comps = sorted(_components(set(body)), key=len, reverse=True)
    for c in comps[1:]:
        names = sorted({body[p] for p in c})
        xs = [p[0] for p in c]
        ys = [p[1] for p in c]
        errs.append(f"R1 고립 본체 덩어리 {len(c)}px [{'·'.join(names)}] "
                    f"x{min(xs)}..{max(xs)} y{min(ys)}..{max(ys)}")
    # ── R2 단면 연속 (실루엣 상/하연)
    cap = max(2.0, _CONT_CAP_U * k)
    cols = {}
    for (x, y) in solid:
        c = cols.setdefault(x, [set(), 10 ** 9, -1])
        c[0].add(body[(x, y)])
        c[1] = min(c[1], y)
        c[2] = max(c[2], y)
    for x in sorted(cols):
        if x + 1 not in cols:
            continue
        a, b = cols[x], cols[x + 1]
        if a[0] != b[0]:
            continue                               # 부위 경계 — 의도된 확폭/전이 (정본 §3-R2)
        if len(a[0]) != 1 or not (a[0] & _R2_AXIAL):
            continue                               # 축 부위 단독 열만 잰다 (위 _R2_AXIAL 주석)
        for i, edge in ((1, "상연"), (2, "하연")):
            d = abs(a[i] - b[i])
            if d > cap:
                errs.append(f"R2 무근거 단차 x{x}→{x + 1} [{'·'.join(sorted(a[0]))}] "
                            f"{edge} {d}px > 상한 {cap:.1f}px")
    # ── R3 기맥 연속
    if qi:
        qcomps = _components(set(qi))
        if len(qcomps) > 1:
            errs.append(f"R3 기맥 단절 — {len(qcomps)}토막 (내공이 흐르는 길은 한 획이다)")
        grip_names = {"자루", "봉", "손목띠"}      # 권갑 — 내공은 손목에서 손등으로 든다 (§3-R3)
        if not any(body.get(p) in grip_names for p in qi):
            errs.append("R3 기맥이 자루 위에서 시작하지 않는다 (내공은 손에서 든다)")
        if body:
            bx_max = max(p[0] for p in body)
            q_max = max(p[0] for p in qi)
            if q_max < bx_max - _QI_TIP_REACH_U * k:
                errs.append(f"R3 기맥이 날끝에 못 닿는다 (x{q_max} < {bx_max}"
                            f"-{_QI_TIP_REACH_U:.1f}u)")
    elif qi_required:
        errs.append("R3 기맥 부재 — 이 등급은 기맥을 가지는데 등록 오버레이(_qi_vein)가 없다 "
                    "(스타일 속 무늬는 연속성을 증명할 수 없다)")
    # ── R4 부위 순서
    cent, cnt = {}, {}
    for (x, y), name in body.items():
        cent[name] = cent.get(name, 0.0) + x
        cnt[name] = cnt.get(name, 0) + 1
    for name in cent:
        cent[name] /= cnt[name]
    for a, b in _PART_ORDER.get(series, ()):
        if a in cent and b in cent and cent[a] >= cent[b]:
            errs.append(f"R4 부위 순서 위반 — {a}(x̄{cent[a]:.1f}) ≥ {b}(x̄{cent[b]:.1f})")
    if errs:
        raise ValueError(f"연속성 정본 위반 [{key}] (weapon_anatomy_canon.md §3):\n  "
                         + "\n  ".join(errs[:14]))


def _continuity_selftest():
    """눈을 시험하는 눈 — 심은 위반 6종을 전부 잡아야 眼이 서 있다 (임포트 시 1회)."""
    def blank():
        return ([[T] * _SHEET for _ in range(_SHEET)],
                [[None] * _SHEET for _ in range(_SHEET)],
                [[None] * _SHEET for _ in range(_SHEET)])

    def put(rows, dep, parts, x0, x1, yc, h, name, tag=None):
        for x in range(x0, x1):
            for y in range(yc - h, yc + h + 1):
                rows[y][x] = (120, 120, 120, 255)
                dep[y][x] = tag
                parts[y][x] = name

    def sword(order_ok=True, cliff=False, island=False, name_ok=True):
        rows, dep, parts = blank()
        put(rows, dep, parts, 10, 14, 60, 4, "물미관", _VD_ORN)
        put(rows, dep, parts, 14, 40, 60, 3, "자루", _vrod(1.0))
        put(rows, dep, parts, 40, 44, 60, 9, "코등이", _VD_ORN)
        put(rows, dep, parts, 44, 104, 60, 5, "검신" if name_ok else "칼몸")
        if cliff:
            put(rows, dep, parts, 70, 104, 60, 11, "검신")
        if island:
            put(rows, dep, parts, 112, 116, 24, 2, "상감", _vstud(4.0))
        if not order_ok:                           # 물미관이 날 위로 넘어가 앉았다 (순서 파괴 —
            put(rows, dep, parts, 80, 84, 60, 4, "물미관", _VD_ORN)   # 실루엣은 그대로)
        return rows, dep, parts

    def vein(broken=False):
        return {(x, 60): "기맥" for x in range(16, 102) if not (broken and 60 <= x < 70)}

    k = 2.8
    rows, dep, parts = sword()
    _continuity_check("selftest_sword", rows, k, dep, parts, vein(), series="sword")
    planted = (
        ("R1", dict(island=True), None, "고립"),
        ("R2", dict(cliff=True), None, "무근거 단차"),
        ("R3 단절", {}, vein(broken=True), "단절"),
        ("R4", dict(order_ok=False), None, "순서"),
        ("태그", dict(name_ok=False), None, "미등록"),
    )
    for tag, kw, qv, needle in planted:
        rows, dep, parts = sword(**kw)
        try:
            _continuity_check("selftest_sword", rows, k, dep, parts,
                              vein() if qv is None else qv, series="sword")
        except ValueError as e:
            if needle not in str(e):
                raise AssertionError(f"연속성 자기시험 {tag}: 다른 위반을 보고했다 — {e}")
        else:
            raise AssertionError(f"연속성 자기시험 {tag}: 심은 위반을 못 잡았다")
    try:
        rows, dep, parts = sword()
        _continuity_check("selftest_sword", rows, k, dep, parts, {},
                          series="sword", qi_required=True)
    except ValueError as e:
        if "기맥 부재" not in str(e):
            raise AssertionError(f"연속성 자기시험 R3 부재: 다른 위반 — {e}")
    else:
        raise AssertionError("연속성 자기시험 R3 부재: 심은 위반을 못 잡았다")


def _paint_model(key, box, shapes, extras, salt, grade, wood=False, mark_pair=None, frame=None,
                 ink=None, composed=None, ext_body=None):
    """시트 1장 + **복셀 모델** (10차) — 시트를 굽고, 불투명 픽셀을 그리디 압출한
    직육면체들로 모델 JSON 을 짓는다. extras(구 악센트 상자·십자판)는 받되 버린다 —
    깊이 태그(_VD_*)가 보주·고리의 돌출을 z 로 잇는다 (호출부 서명은 기록으로 유지).
    frame(계열 공통 프레임)이 오면 box 대신 그것으로 굽고 앵커한다 (Codex 1단계).
    composed = _series_body_extent 가 이미 구운 (rows, k, dep) — 같은 판 재사용 (12차).
    ext_body = 계열 공통 본체 extent — 없으면(명병) 제 판에서 직접 잰다."""
    fr = frame or box
    rows, k, dep, parts, qi = composed or _compose(shapes, fr, salt, grade, wood=wood,
                                                   mark_pair=mark_pair, ink=ink)
    qi_req = bool(_R16 == "calm" and _R16_ALIGN and not _R20     # 20차 전통 병기엔 기맥 그물이
                  and _QI_GRADE_CALM.get(grade, (0, 0))[1])       # 없다 (마법 오라 제거)
    _continuity_check(key, rows, k, dep, parts, qi,    # 17차 — 연속성 정본 (위반 = 빌드 실패)
                      qi_required=qi_req)
    write_png(ITEM_TEX_DIR / f"{PAINT_DIR}/{key}.png", rows)
    elems, ext = _anchor(_voxelize(rows, dep, k, fr), fr)
    own_body = _body_extent(rows, dep, k)          # 21b — 이 자루의 제 본체 extent (fp 분모)
    if ext_body is None:
        ext_body = own_body
    icon = f"weapon/{key}" if not key.startswith("myeong/") else f"weapon/myeong/{key[7:]}"
    return {
        "textures": {"0": f"honcheon:item/{icon}",
                     "1": f"honcheon:item/{PAINT_DIR}/{key}",
                     "particle": f"honcheon:item/{icon}"},
        "elements": elems,
        "display": _display(ext, ext_body, fp_body=own_body),   # 21b — fp 는 제 본체(범철 확대)
        "gui_light": "front",
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


# ═══════════════════════════════════════════════════════════════════════════
# 명병(名兵) — 문파의 얼굴. 【2026-07 신설】
#
# 【사용자가 문장으로 직접 청한 유일한 시각 항목】
#   *"매화검인 경우 매화검처럼 생겨야함. 손잡이에 매화 무늬가 있다던가 특색이 있어야 함"*
#   그전까지 병기는 계열 9 × 등급 5 = 45장이 전부였다 — 십 년을 함께한 애병이 방금 대장간에서
#   산 정련검과 **픽셀 하나까지 똑같았다.** equipment.yml 이 스스로 쓴 *"검이 소문의 주체가 된다"* 와
#   정면으로 어긋난다. 소문의 주체가 될 검에 **얼굴이 없었다.**
#
# 【문파는 지어내지 않는다 — 등록부에서 나온다】
#   가져온 기획 프롬프트는 청운검문·철혈도문·벽옥궁 따위 **여덟 문파를 예시로 들었다. 전부 없는 것이다.**
#   우리 세계의 문파는 docs/design/sect_lineage.md 의 **15문파**이고, 그 문파들은 이미
#   **수치의 지문(fingerprint)** 을 갖고 있다 (skills.yml 이 굴리는 실제 값이다).
#   ⇒ 실루엣을 **지문에서 도출한다.** 억지로 대응시키지 않는다 — 기계가 이미 말한 것을 그림이 옮긴다:
#
#     점창파  [2,1,3] 최속 · 5다단(최다) · 입력창 6(최단)  →  **가장 얇은 날** (바늘 같은 쾌검)
#     종남파  [13,4,16] = 33틱 최중 · 슈퍼아머             →  **가장 두꺼운 날** (중검)
#     남궁세가 선 7.5 — 근접 최장 리치                     →  **가장 긴 날** (장검)
#     하북팽가 폭 1.8 최광폭 · 파격 · armor_pierce         →  **가장 넓은 도** (오호단문도)
#     소림사  리치 2.4 최단 · 4타 · 슈퍼아머               →  **권갑** (무기 = 권·장)
#     사천당가 사거리 18 · 4다단 · max_targets 3 · 독      →  **비수** (암기) + 독빛 한 점
#     화산파  8다단 오의(최다단) · 매화                    →  **매화 코등이** (사용자의 요청)
#     무당파  패링 창 5 + 반격 (원으로 되돌린다)           →  **둥근 코등이 + 태극 물미**
#
# 【기획 프롬프트에서 **살려서** 가져온 4순위 원칙 — 그대로 지킨다】
#   ① 실루엣 — *"흑백으로 보더라도 문파가 구분되어야 한다"*  ⇒ **축 ⑬ 이 그것을 잰다** (자카드).
#   ② 재질   — 금속의 밝기·마모
#   ③ 문양   — 작은 픽셀에서도 읽히게 **단순화**. **아이템 전체를 덮지 말고 시선이 모이는 한두 곳에만**
#              ⇒ 문양은 **코등이와 물미에만** 앉는다 (날에는 안 앉는다 — 날은 날이어야 한다)
#   ④ 포인트 색 — **색은 보조다.** 색만 바꾼 같은 모델의 재탕은 금지 (그래서 실루엣부터 갈랐다)
# ═══════════════════════════════════════════════════════════════════════════
def hwasan_grid():
    """화산 매화검(梅花劍) — **사용자가 직접 청한 검.** 코등이에 매화 다섯 잎.

    날은 표준 검이다 (화산의 지문은 '8다단'이지 '이상한 날'이 아니다 — 지문에 없는 것은 짓지 않는다).
    화산을 화산으로 만드는 것은 **매화**다. 그리고 매화는 **날이 아니라 코등이**에 핀다:
    문양이 날을 덮으면 그것은 검이 아니라 장식품이다 (기획 원칙 ③ — 시선이 모이는 한두 곳에만)."""
    g = blank16()
    for i in range(7):
        band(g, 6 + i, 9 - i, 1, ["L", "H", "L"], vertical=True)
    band(g, 13, 3, 1, ["H", "L"], vertical=True)
    band(g, 14, 3, 1, ["H"], vertical=True)
    _hilt(g, 3, False, 6, 9, (4, 7), (1, 2, 3))
    # ★ 첫 시안의 매화는 **너무 수줍었다** — 넉 잎이 코등이 안에 숨어 축 ⑭ 가 잡았다
    #   (화산 ↔ 남궁 회색조 변별 **15px < 16** — 색을 빼면 같은 검이었다. 사용자가 청한 바로 그 검이).
    #   매화는 **꽃**이다. 코등이 밖으로 피어야 꽃이다 — 다섯 잎이 호수를 두르고 밖으로 벌어진다.
    for px_, py_ in ((5, 5), (6, 6), (6, 8), (5, 9), (3, 7), (4, 5), (4, 9)):
        g[py_][px_] = "p"                                  # 다섯 잎 + 벌어진 곁잎 (코등이 **밖**으로)
    for px_, py_ in ((4, 6), (5, 6), (4, 8), (5, 8)):
        g[py_][px_] = "P"                                  # 꽃잎의 빛 받는 면
    g[7][4] = "P"                                          # 꽃술 — 한복판
    return g


def jeomchang_grid():
    """점창 쾌검(快劍) — **가장 얇은 날**. 지문: [2,1,3] 최속 · 입력창 6틱(최단) · 5다단(최다).
    빠른 검은 **가볍다** — 폭 1의 바늘. 코등이도 최소다 (걸리는 것이 없어야 빠르다)."""
    g = blank16()
    for i in range(8):                                     # 폭 1 — 계보에서 가장 얇다
        band(g, 6 + i, 9 - i, 1, ["H"], vertical=True)
    band(g, 14, 2, 1, ["H"], vertical=True)                # 칼끝
    # ★ 자루 기준선은 **9행**이다 (_hilt 의 계약). 10행에 두면 물미가 14행, 수실이 15행 —
    #   캔버스 테두리 — 으로 밀려 outline() 이 먹으로 두를 자리를 잃는다 (축 ⑥ 이 4px 끊김으로 잡았다).
    #   주석이 이미 경고하던 함정에 그대로 빠졌다. 등록된 계약은 읽으라고 있는 것이다.
    _hilt(g, 3, False, 6, 9, (4, 7), (1, 2, 3), guard_steps=3)   # 코등이 3걸음 (짧다 — 걸리는 것이 없다)
    return g


def jongnam_grid():
    """종남 중검(重劍) — **가장 두꺼운 날**. 지문: [13,4,16] = 33틱(최중) · 슈퍼아머.
    무거운 검은 **두껍다** — 폭 5에 등줄기가 굵다. 느린 대신 맞으면 밀리지 않는다."""
    g = blank16()
    for i in range(6):                                     # 폭 5 — 계보에서 가장 두껍다
        band(g, 7 + i, 9 - i, 1, ["L", "B", "H", "B", "L"], vertical=True)
    band(g, 13, 4, 1, ["L", "H", "L"], vertical=True)
    band(g, 14, 4, 1, ["H"], vertical=True)
    _hilt(g, 3, False, 7, 9, (5, 7), (1, 2, 3))            # 자루 기준선 9행 (_hilt 의 계약)
    return g


def namgung_grid():
    """남궁 장검(長劍) — **가장 긴 날**. 지문: 선 7.5 — 근접 최장 리치 (제왕검형).
    긴 검은 자루를 아래로 당겨 날을 벌 수 있는 데까지 번다 (16px 에서 리치는 곧 길이다)."""
    g = blank16()
    # ★ 길이는 **칼끝에서** 번다 (자루에서 벌면 물미가 캔버스 왼쪽 끝(x=0)에 닿아 외곽선이 끊긴다 —
    #   축 ⑥ 이 2px 끊김으로 잡았다). 자루는 표준 자리에 두고 날만 한 칸 더 뻗는다:
    #   표준 검의 칼끝은 y=3, 남궁의 칼끝은 **y=1** 이다 (같은 자루에서 더 멀리 닿는다 = 리치 7.5).
    for i in range(8):                                     # 날 8걸음 — 계보에서 가장 길다
        band(g, 6 + i, 9 - i, 1, ["L", "H", "L"], vertical=True)
    band(g, 14, 1, 1, ["H", "L"], vertical=True)           # 칼끝 — 가장 멀리 닿는 자리
    _hilt(g, 3, False, 6, 9, (4, 7), (1, 2, 3))            # 자루 기준선 9행 (_hilt 의 계약)
    return g


def mudang_grid():
    """무당 태극검(太極劍) — 지문: 패링 창 5 + 반격 (**원으로 되돌린다**).
    그래서 코등이가 **둥글다** (가로대가 아니라 원반 — 받아 흘리는 손). 물미에 태극 한 점."""
    g = blank16()
    for i in range(7):
        band(g, 6 + i, 9 - i, 1, ["L", "H", "L"], vertical=True)
    band(g, 13, 3, 1, ["H", "L"], vertical=True)
    band(g, 14, 3, 1, ["H"], vertical=True)
    _hilt(g, 3, False, 6, 9, (5, 7), (1, 2, 3), guard_steps=2)   # 원반 호수 (가로대가 아니다)
    for px_, py_ in ((4, 6), (5, 6), (4, 7), (5, 8), (4, 8)):    # 둥글게 두른 원반
        g[py_][px_] = "g"
    g[7][4] = "j"                                          # 태극 — 옥빛 한 점 (청량 포인트)
    return g


def paengga_grid():
    """하북팽가 오호단문도(五虎斷門刀) — **가장 넓은 도**. 지문: 폭 1.8(최광폭) · 파격 · armor_pierce.
    넓은 도는 **등이 두껍다** (무거워 보이는 것은 등 때문이지 날 때문이 아니다 — 한날 병기의 문법)."""
    g = dao_grid(3, False)
    for py_ in range(4, 8):                                # 등을 한 가닥 더 불린다 (최광폭)
        if g[py_][9] == ".":
            g[py_][9] = "D"
    g[9][5] = "t"                                          # 자루 끝 붉은 수실 (호랑이의 문파)
    return g


def dangga_grid():
    """사천당가 비수(匕首) — 지문: 사거리 18 · 4다단 · max_targets 3 (흩어진다) · **무형지독**.
    던지는 것은 짧고 가볍다. 그리고 당가의 표식은 **독**이다 — 날 끝에 푸른 기 한 점.
    (독은 **한 점**이다. 날 전체를 물들이면 그것은 독이 아니라 색칠이다.)"""
    g = dagger_grid(3, False)
    for py_ in range(16):
        for px_ in range(16):
            if g[py_][px_] == "H" and py_ <= 5:
                g[py_][px_] = "d"                          # 날 끝에 밴 독 (끝에만)
    return g


def sorimsa_grid():
    """소림 권갑(拳甲) — 지문: 리치 2.4(최단) · 4타 · 슈퍼아머. 무기는 **권·장**이다 (검이 아니다).
    ★ 등록부가 그렇게 적혀 있다 — '소림의 계도' 같은 것은 우리 세계에 없다 (짓지 않는다)."""
    g = gauntlet_grid(3, False)
    g[4][7] = "j"                                          # 손등의 옥 — 반야(般若)의 한 점
    return g


# ═══ 신작 4문파 (B-037) — 곤륜·청성·해남·개방 【2026-07-16 T2 신작 — 사용자 결정 회차 대기】 ═══
# 근거는 전부 저장소에서 나왔다 (sect_lineage.md §2 지문 · skills.yml · factions.yml).
# **모양(구름·달·파도의 획 형태)만 잠정**이다 — 이름이 준 주제를 픽셀로 옮긴 해석이라 결정 회차에 올린다.
def gonryun_grid():
    """곤륜 양의검(兩儀劍) — 지문: **무성무색 = 텔레그래프 없음(유일)** · 답운종 거리 10(최장).

    ① 날이 두 갈래다: 밝은 갈래(양) 위 · 어두운 갈래(음) 아래 — skills.yml yangui_geom(하급)의
       원문 *"음과 양, 두 갈래로 갈린다"* 를 그대로 옮겼다 (근거: 저장소).
    ② 칼끝이 **빛을 되쏘지 않는다** — 끝 두 걸음이 그늘로 스러진다. 무성무색(중급)은 전 무공 중
       유일하게 텔레그래프가 없고 목격해도 못 읽는다(public_use_rumor: false 유일) — 경고 없는
       검은 칼끝부터 조용하다 (근거: 저장소 수치).
    ③ 물미 곁의 구름 두 점 — 답운종(踏雲)·운룡대팔식(雲龍)의 '구름' (주제: 저장소 이름 ·
       **획 모양: 잠정**)."""
    g = blank16()
    for i in range(7):                                     # 두 갈래 날 — 폭 2 (경공의 문파, 가볍다)
        band(g, 6 + i, 9 - i, 1, ["H", "S"], vertical=True)
    band(g, 13, 3, 1, ["S", "D"], vertical=True)           # 끝이 스러진다 — 무성무색
    band(g, 14, 3, 1, ["D"], vertical=True)
    _hilt(g, 3, False, 6, 9, (4, 7), (1, 2, 3))            # 자루 기준선 9행 (_hilt 의 계약)
    # 답운(踏雲) — 날 **아래**에 깔린 구름 두 자락 (구름을 밟는다). 빈 여백에 띄워야 읽힌다 —
    # 물미 곁에 두었던 첫 시안은 놋의 담황과 붙어 보이지 않았다 (몽타주 육안 확인).
    for px_, py_ in ((10, 9), (11, 9), (13, 8)):
        g[py_][px_] = "u"
    return g


def cheongseong_grid():
    """청성 송풍검(松風劍) — 지문: **검 계보 유일 — 돌진(突)으로 연다**.

    ① 날이 밑동에서 끝으로 **좁아진다** (폭 3 → 2 → 1) — 찌르는 검은 끝에 무게가 없다.
       돌진이 곧 실루엣이다 (근거: 저장소 지문).
    ② 칼끝을 한 걸음 더 벼렸다 (y=2) — 돌진이 닿는 자리.
    ③ 물미 곁의 초승달 — ◆청월검법(장문 오의, 노션 원천 이름)의 '청월(靑月)' (주제: 저장소 이름 ·
       **靑月=푸른 달 해석과 획 모양: 잠정**). 바람 이름들(청풍·능풍·송풍)은 날에 앉히지 않았다 —
       날은 날이어야 한다 (문양 규율: 코등이·물미에만)."""
    g = blank16()
    for i in range(4):                                     # 밑동 — 폭 3
        band(g, 6 + i, 9 - i, 1, ["L", "H", "L"], vertical=True)
    for i in range(4, 7):                                  # 허리 — 폭 2
        band(g, 6 + i, 9 - i, 1, ["H", "L"], vertical=True)
    band(g, 13, 3, 1, ["H"], vertical=True)                # 끝 — 폭 1 (바늘이 아니라 송곳)
    band(g, 14, 2, 1, ["H"], vertical=True)                # 한 걸음 더 벼린 칼끝
    _hilt(g, 3, False, 6, 9, (4, 7), (1, 2, 3), guard_steps=4)   # 코등이 4걸음 (표준 5보다 짧다)
    # 청월(靑月) — 좌상 여백에 뜬 초승달 한 호 (곤륜의 구름은 날 아래·달은 날 위 — 자리로도 갈린다)
    for px_, py_ in ((3, 3), (2, 4), (2, 5)):
        g[py_][px_] = "u"
    return g


def haenam_grid():
    """해남 역수검(逆手劍) — 지문: 검(**쾌·좌수·역수**) · 패링 유예 -2 · 카운터 0.5 · 가드 반감.

    ★ **판 전체가 뒤집혀 있다 — 자루가 위, 날이 아래.** '역수검법'은 원천(노션)의 이름이고
      pack_art_direction §3 이 청구서에 이미 적어 두었다: *"역수 = 날이 아래로"* (근거: 저장소).
      열두 자루 중 유일하게 대각선이 거꾸로다 — 흑백으로 봐도 첫눈에 갈린다 (축 ⑭ 의 뜻 그대로).
    날은 폭 2 (쾌검 — 점창 다음으로 얇다). 물미 곁의 파도 한 획 — 해류심결·해풍검법·남해삼십육검의
    '남해' (주제: 저장소 이름 · **획 모양: 잠정**). 그리는 법: 표준 문법(_hilt)으로 그린 뒤 180° 돌리고,
    빛이 좌상단에서 오도록 표식의 좌우만 되짚는다 (문법 재사용 — 손으로 뒤집어 그리면 계약이 어긋난다)."""
    g = blank16()
    for i in range(7):                                     # 얇은 쾌검 — 폭 2
        band(g, 6 + i, 9 - i, 1, ["H", "L"], vertical=True)
    band(g, 13, 3, 1, ["H"], vertical=True)
    band(g, 14, 3, 1, ["H"], vertical=True)
    _hilt(g, 3, False, 6, 9, (4, 7), (1, 2, 3), guard_steps=3)   # 짧은 코등이 (쾌검 — 걸리면 늦다)
    g = [row[::-1] for row in g[::-1]]                     # 역수 — 자루가 위, 날이 아래
    _relight(g)                                            # 빛은 여전히 좌상단에서 온다
    for x in range(16):                                    # 회전으로 물미 **위**에 선 수실을 지우고
        if g[1][x] in ("T", "t"):
            g[1][x] = "."
    g[3][14], g[4][14] = "T", "t"                          # 물미 곁에 다시 늘어뜨린다 (수실은 아래로)
    # 남해 — 아래로 향한 칼끝 밑의 파도 한 획 (검이 바다 위에 서 있다). 여백에 띄워야 읽힌다.
    for px_, py_ in ((4, 13), (5, 13), (6, 12)):
        g[py_][px_] = "u"
    return g


def _relight(g):
    """180° 회전 뒤의 조명 수선 — 좌우가 뒤집히면 '왼쪽이 밝다'는 관례가 깨진다.
    가로로 이어진 표식 무리(고리 R/e · 놋 G/g/f · 수실 T/t)만 무리 안에서 밝은 문자를
    왼쪽으로 되짚는다. 실루엣·문자 구성은 그대로다 (자리만 무리 안에서 정렬)."""
    order = {"R": 0, "e": 1, "G": 0, "g": 1, "f": 2, "T": 0, "t": 1,
             "W": 0, "w": 1, "x": 2, "X": 3}
    for y in range(16):
        x = 0
        while x < 16:
            if g[y][x] in order:
                x0 = x
                while x < 16 and g[y][x] in order and (x == x0 or _same_family(g[y][x0], g[y][x])):
                    x += 1
                run = sorted((g[y][k] for k in range(x0, x)), key=lambda c: order[c])
                for k, ch in enumerate(run):
                    g[y][x0 + k] = ch
            else:
                x += 1
    return g


def _same_family(a, b):
    fams = ("Re", "Ggf", "Tt", "WwxX")
    return any(a in f and b in f for f in fams)


def gaebang_grid():
    """개방 타구봉(打狗棒) — 지문: 봉·권 · **호 150° 최광각** (포위를 푼다).

    ★ 열두 자루 중 유일하게 **날이 없다.** 봉은 캔버스 대각선을 끝까지 쓴다 (최광각 —
      가장 넓게 휘두르는 병기가 가장 길게 눕는다). 쇠 장식이 하나도 없다: 놋도 고리도 수실도 없다 —
      거지의 병기다 (factions.yml: "거지들의 방파. 정보와 발이 무기다").
    ② 매듭 셋 — sect_lineage §4 개방의 구결: *"개방은 계급이 몸에 보인다 (자루의 매듭 수)"* ·
       *"거지의 결은 지나가는 아이도 센다"* (근거: 저장소). 매듭이 곧 이 병기의 등급 문법이다.
       **몇 결을 그릴지는 잠정** — 정적 텍스처는 착용자의 결을 모른다 (셋 = 16px 에서 세어지는 수).
    ③ 통념 각주: 무협 통념의 타구봉은 청죽(靑竹)·녹옥이다. 저장소에 색 근거가 없어 **나무로 그렸다**
       — 초록 몸통은 포인트 면적 규율에도 어긋난다. 죽절(대나무 마디)·녹죽 시안은 결정 회차에 묻는다."""
    g = blank16()

    def staff(i):                                          # 봉 — 폭 2. 감기가 아니라 맨나무의 결
        return ["W", "w"] if i % 3 != 1 else ["w", "x"]
    band(g, 1, 13, 12, staff, sx=1, sy=-1)                 # (1,13) → (12,2) — 화면을 끝까지 긋는 봉
    band(g, 13, 1, 1, ["W", "w"], vertical=True)           # 끝 마디 (짚고 다니는 끝 — 닳아서 밝다)
    band(g, 1, 14, 1, ["w", "x"])                          # 밑동 마디
    for i in (3, 6, 9):                                    # 매듭 셋 — 봉을 감고 좌우로 삐져나온다
        x, y = 1 + i, 13 - i                               #   (돌출 = 실루엣의 사건. 지나가는 아이도 센다)
        band(g, x - 1, y, 1, ["r", "r", "z", "z"])         # 삼줄 2톤 — 왼쪽이 밝다 (빛은 좌상단)
    g[5][12], g[6][12] = "r", "z"                          # 맨 위 매듭의 끈 꼬리 — 아래로 늘어진다
    return g


MYEONGBYEONG = {           # 명병 등록부 — **등록된 문파만** (factions.yml · sect_lineage.md)
    "hwasan": hwasan_grid,        # 화산파 — 매화검 ★ 사용자 직접 요청
    "jeomchang": jeomchang_grid,  # 점창파 — 쾌검 (최속·최박)
    "jongnam": jongnam_grid,      # 종남파 — 중검 (최중·최후)
    "namgung": namgung_grid,      # 남궁세가 — 장검 (최장 리치)
    "mudang": mudang_grid,        # 무당파 — 태극검 (원으로 되돌린다)
    "paengga": paengga_grid,      # 하북팽가 — 오호단문도 (최광폭)
    "dangga": dangga_grid,        # 사천당가 — 비수 (암기·독)
    "sorimsa": sorimsa_grid,          # 소림사 — 권갑 (권·장)
    # ─ 신작 4문파 (B-037 · 2026-07-16) — 잠정 시안. Weapons.java MYEONG_SERIES 미배선 (결정 회차 뒤 배선)
    "gonryun": gonryun_grid,      # 곤륜파 — 양의검 (무성무색 · 답운종)
    "cheongseong": cheongseong_grid,  # 청성파 — 송풍검 (돌진으로 연다)
    "haenam": haenam_grid,        # 해남파 — 역수검 (날이 아래로)
    "gaebang": gaebang_grid,      # 개방 — 타구봉 (봉 — 날이 없다 · 매듭이 계급)
}

# 신작 4문파 — 아직 Weapons.java 가 키를 안 박는다 (배선은 결정 회차 뒤 별도 트랙).
# 팩에는 완제품으로 굽는다: 텍스처·모델·아이템 정의·바닐라 분기 전부 — 배선이 오면 그대로 붙는다.
MYEONG_NEW = frozenset(("gonryun", "cheongseong", "haenam", "gaebang"))




def myeong_model_3d(sect, grid=None):
    """명병의 3D — 복셀 + 전용 고해상 SDF 페인트 (V2-W 3차 문법 · 14차 128px). 확정 실루엣(곤륜 두 갈래·
    해남 파도·개방 매듭·화산 매화)은 _myeong_spec 이 알파 실루엣과 문양으로 그린다.
    grid 인자는 옛 서명 호환용이다 (아이콘 격자는 이제 3D 와 무관 — 아이콘은 GUI 의 진실)."""
    salt = zlib.crc32(f"myeong/{sect}".encode()) & 0x7F
    pilot = _R21 and sect in _R21_MYEONG_PILOTS
    _R21_ALLOW[0] = pilot                          # 명병 파일럿(화산)만 만화 셀 + 문파색 기 껍질
    box, shapes, extras = _myeong_spec(sect, salt)
    model = _paint_model(f"myeong/{sect}", box, shapes, extras, salt, "sinbyeong",
                         wood=(sect == "gaebang"), mark_pair=_MYEONG_MARK_INK.get(sect))
    if pilot:                                      # 기 복셀 껍질 흐름 애니 스트립 (문파색)
        _bake_r21_strip(MYEONG_BASE.get(sect, "sword"), "sinbyeong",
                        f"myeong/{sect}", box, salt, myeong=sect)
    _R21_ALLOW[0] = False
    return model


def write_myeongbyeong_assets() -> int:
    """명병 — 아이콘 PNG(불변) + 페인트 시트 2장 + 3D 모델 + 아이템 정의. 열두 자루 전부
    전용 리그 + 전용 그림 (곤륜 두 갈래·해남 쏠린 코등이·개방 봉 매듭 — 문양은 시트가 그린다).
    아이콘 PNG·경로·격자는 그대로다 — 축 ⑭ 가 재던 진실은 안 흔들린다."""
    for sect, fn in MYEONGBYEONG.items():
        grid = outline(fn())
        key = f"weapon/myeong/{sect}"
        write_png(ITEM_TEX_DIR / f"{key}.png", forge_rows(grid))
        write_json(ITEM_MODEL_DIR / f"{key}.json", myeong_model_3d(sect))
        write_json(ITEM_DEF_DIR / f"{key}.json",
                   {"model": {"type": "minecraft:model", "model": f"honcheon:item/{key}"}})
    return len(MYEONGBYEONG)
# 등급 = 베이스 바닐라 아이템(팩 게이트). 여기서는 고리 수만 쥔다 — 색은 바닐라 재질의 몫.
WEAPON_GRADES = [("beomcheol", 0), ("jeongryeon", 1), ("bobyeong", 2), ("sinbyeong", 3)]


def weapon_rows(series, rings, mabyeong=False):
    return forge_rows(outline(WEAPON_SERIES[series](rings, mabyeong)))


def weapon_grid(series, rings, mabyeong=False):
    """아이콘 격자 한 장 — PNG 와 3D 모델이 **같은 격자**를 쓴다.
    (모델의 UV 가 이 격자에서 부위별 대표 픽셀을 문다 — 아이콘과 3D 가 같은 쇠로 보이는 이유다.)"""
    return outline(WEAPON_SERIES[series](rings, mabyeong))


def write_weapon_asset(series: str, grade: str):
    """병기 한 자루 = 아이콘 PNG + **3D 모델** + 아이템 정의.

    모델은 더 이상 평면 스프라이트(handheld)가 아니다 — 칼날·자루·코등이·물미·고리가
    저마다 부피를 가진 elements 다 (weapon_model_3d). 아이콘 PNG 는 GUI·드롭의 진실로
    바이트 불변이고(축 ⑥⑨⑩ 계약), 3D 는 전용 페인트 시트 2장을 입는다 (V2-W 2차)."""
    rings, _, _, mab = _GRADE_FORM[grade]
    grid = weapon_grid(series, rings, mab)
    key = f"weapon/{series}_{grade}"
    write_png(ITEM_TEX_DIR / f"{key}.png", forge_rows(grid))
    write_json(ITEM_MODEL_DIR / f"{key}.json", weapon_model_3d(series, grade, grid))
    write_json(ITEM_DEF_DIR / f"{key}.json",
               {"model": {"type": "minecraft:model", "model": f"honcheon:item/{key}"}})


# 명병 → 그 문파의 병기 계열 (Weapons.java MYEONG_SERIES 와 **같은 표**여야 한다 — 눈이 대조한다)
# ★ 신작 4문파(MYEONG_NEW)는 Java 쪽에 아직 짝이 없다 — 팩이 앞서 굽고 배선이 따라온다
#   (등록·배선 청구: resourcepack_design.yml myeongbyeong.등록 4줄 + Weapons.java MYEONG_SERIES 4항).
#   gaebang 의 "spear" 는 **바닐라 분기용 잠정값**이다: 봉(棒) 계열이 없어(B-037: "신설 선행")
#   같은 긴 자루 병기의 베이스(검 재질 5종)를 빌린다. 3D 는 전용 리그(bong_rig)가 맡는다.
MYEONG_BASE = {
    "hwasan": "sword", "jeomchang": "sword", "jongnam": "sword", "namgung": "sword",
    "mudang": "sword", "paengga": "dao", "dangga": "dagger", "sorimsa": "gauntlet",
    "gonryun": "sword", "cheongseong": "sword", "haenam": "sword",   # 셋 다 검 (sect_lineage §2)
    "gaebang": "spear",                                              # ★ 잠정 — 봉 계열 신설 전
}
# 지물·기물·재료 → 바닐라 베이스 (Goods.java build(Material.X, ...) 와 **같은 표** — 눈이 대조한다)
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




def _write_mcmeta(path, frames, frametime):
    """애니 텍스처 선언 (.mcmeta) — 세로 프레임 스트립을 순환한다 (campfire/kelp 전례 format).
    interpolate: 프레임 사이를 보간 (8~16 프레임이 부드러운 광택 흐름으로 읽힌다)."""
    write_json(path, {"animation": {"frametime": frametime, "interpolate": True,
                                    "frames": list(range(frames))}})


def _bake_r20_strip(series, grade, key, fr, salt):
    """20차 — 페인트 시트를 **세로 프레임 스트립**(128×128·N)으로 굽는다: 각 프레임 = 같은
    무기 + 스윕만 다른 위치. 몸 픽셀은 프레임마다 동일 → 몸 정지·광택만 흐른다. MC 가 mcmeta
    로 프레임을 순환 (모델 UV 는 프레임 0 = 0~16 을 물어 원소·UV 재배선 0). 결정론 — 위상만."""
    global _R20_PHASE
    saved = _R20_PHASE
    strip = []
    for i in range(_R20_FRAMES):
        _R20_PHASE = i / _R20_FRAMES
        r, b, t, m = _GRADE_FORM[grade]
        box, shapes, extras = WEAPON_SPEC[series](salt, grade, r, b, t, m)
        rows, k, dep, parts, qi = _compose(shapes, fr, salt, grade)
        strip.extend(rows)                         # 세로 적층 (프레임 i 가 시트 아래로 이어진다)
    _R20_PHASE = saved
    base = ITEM_TEX_DIR / f"{PAINT_DIR}/{key}.png"
    write_png(base, strip)                          # 정지 단일 시트를 스트립으로 덮어쓴다
    _write_mcmeta(base.with_name(base.name + ".mcmeta"), _R20_FRAMES, _R20_FRAMETIME)


def _bake_r21_strip(series, grade, key, fr, salt, myeong=None):
    """21차(+d) — 페인트 시트를 **세로 프레임 스트립**(128×128·N)으로 굽는다: 각 프레임 = 같은
    무기 몸(만화 셀 · 볼록 날 · 팔각) 정지 + **검을 휘감는 뇌전 볼트(float 껍질)**만 프레임마다
    점멸(치지직). 몸/날 픽셀 불변(위상 안 읽음) · 볼트 케이지 기하는 위상 None 프레임이 만들고
    프레임 애니는 그 위에서 점멸(꺼진 프레임 투명은 컷아웃이 지운다 · 기하/UV/원소 재배선 0).
    결정론 — 위상만. myeong=sect 면 명병. frametime _R21_FRAMETIME(3틱 · 감속) · interpolate 없이 딱딱."""
    global _R21_PHASE
    saved = _R21_PHASE
    strip = []
    for i in range(_R21_FRAMES):
        _R21_PHASE = i / _R21_FRAMES
        if myeong:
            box, shapes, extras = _myeong_spec(myeong, salt)
        else:
            r, b, t, m = _GRADE_FORM[grade]
            box, shapes, extras = WEAPON_SPEC[series](salt, grade, r, b, t, m)
        rows, k, dep, parts, qi = _compose(shapes, fr, salt, grade)
        strip.extend(rows)
    _R21_PHASE = saved
    base = ITEM_TEX_DIR / f"{PAINT_DIR}/{key}.png"
    write_png(base, strip)
    # 전격은 프레임 간 보간하면 번개가 번지므로 딱딱 튀게(interpolate=False) — 치지직
    write_json(base.with_name(base.name + ".mcmeta"),
               {"animation": {"frametime": _R21_FRAMETIME, "interpolate": False,
                              "frames": list(range(_R21_FRAMES))}})


def weapon_model_3d(series, grade, grid=None):
    """병기 한 자루의 3D — 복셀 + 전용 고해상 SDF 페인트 (V2-W 3차 문법 · 14차 128px):
    본판 1장(곡선 실루엣은 알파가 깎는다) + 감김 십자판 + 입체 악센트 몇 점.
    grid 인자는 옛 서명 호환용이다 (아이콘 격자는 이제 3D 와 무관)."""
    rings, blen, tassel, mab = _GRADE_FORM[grade]
    salt = zlib.crc32(f"{series}_{grade}".encode()) & 0x7F
    if _R25 and (series, grade) in _R25_PILOTS:   # 25차 — 깨끗한 재건축 (손 배치 cuboid · _voxelize 미경유)
        from .clean_weapons import write_clean_pilot
        return write_clean_pilot(series, grade)   # clean 스와치 굽고(정지 128²) 옛 애니 mcmeta 제거 → clean 모델
    if (series, grade) in _PILOT7:      # 7차 파일럿 — 전용 프레임 (잠정: 사용자 선택 뒤 전파 시
        box, shapes, extras, ink = _spec_sword_pilot7(salt, _PILOT7[(series, grade)])  # 계열 프레임 재구성)
        return _paint_model(f"{series}_{grade}", box, shapes, extras, salt, grade, ink=ink)
    pilot = _R21 and (series, grade) in _R21_PILOTS
    # ★프레임·본체 extent 는 **base(R20) 기하로 계열 단위** 계산 (파일럿 플래그 OFF) — 비파일럿
    #   자루가 _R21 켠 빌드에서도 비트 불변이게 한다 (계열 프레임 오염 = dao_mabyeong 적색 표류).
    #   파일럿은 제 r21 박스(기 껍질 float 포함)로 앵커한다 — 껍질이 프레임에 안 잘린다.
    _R21_ALLOW[0] = False
    ext_body = _series_body_extent(series)         # 12차 — 계열 공통 본체 분모 (base — 파일럿도 공유)
    fr_series = _series_frame(series)
    _R21_ALLOW[0] = pilot                          # 실제 자루 굽기는 자루 단위 (파일럿만 만화+기)
    box, shapes, extras = WEAPON_SPEC[series](salt, grade, rings, blen, tassel, mab)
    frame = box if pilot else fr_series            # 파일럿 = 제 박스(기 껍질) · 비파일럿 = 계열 프레임
    model = _paint_model(f"{series}_{grade}", box, shapes, extras, salt, grade,
                         frame=frame, ext_body=ext_body,
                         composed=None if pilot else _COMPOSED.pop((series, grade), None))
    if pilot:                                      # 21차 파일럿 — 만화 셀 (+ 전격/기 애니 등급만 스트립)
        if _r21_anim(grade):                       # 범철=전격·기 없음 → 정지 만화 시트 (스트립 없음)
            _bake_r21_strip(series, grade, f"{series}_{grade}", box, salt)
    elif _R20 and (series, grade) in _R20_PILOTS:  # 20차 — 광택 일렁임 애니 스트립 + mcmeta
        _bake_r20_strip(series, grade, f"{series}_{grade}", fr_series, salt)
    _R21_ALLOW[0] = False
    return model


def _aura_selftest():
    """오라 자기시험 (19차-c · 임포트 시 1회) — 정본 §4 를 기계로 잰다:
      ① 부유 광점은 **기맥 경혈** 위에 있다 (기맥 경로 마디 · node>0.9) — 임의 좌표가 아니다.
      ② 헤일로는 **인선 쪽**만 — 한날(도)은 signs 1겹, 양날(검)은 2겹.
      ③ 블룸은 **핫 코어**를 가진다 — 심 색이 몸보다 밝다 (광원이지 부산물이 아니다)."""
    salt = 21
    spine = [(-3.9, 8.0), (23.0, 8.0)]

    def amp(mx):
        return min(0.5, max(0.1, (22.5 - mx) * 0.17)) if mx >= 6.0 else 0.24
    # ① 광점이 경혈 위인가 — 각 광점 중심이 기맥 중심선의 마디(node>0.9)에 얹혔나
    cl = _vein_centerline(spine, amp, salt)
    motes = _meridian_motes(spine, amp, salt, "sword", "sinbyeong")
    assert motes, "오라 자기시험 ①: 신병은 경혈 광점을 가지는데 0개가 나왔다"
    for f in motes:
        mx, my = f._center                         # 광점 중심 (경혈 좌표)
        px_, py_, nt = min(cl, key=lambda e: (e[0] - mx) ** 2 + (e[1] - my) ** 2)
        d = math.hypot(px_ - mx, py_ - my)
        assert d < 0.6, f"오라 자기시험 ①: 광점이 기맥 경로에서 {d:.2f}u 떨어졌다 (경혈이 아니다)"
        nodev = math.sin(nt * 17.0 + salt * 0.29)
        assert nodev > 0.9, (f"오라 자기시험 ①: 광점 자리 t={nt:.3f} 의 node={nodev:.2f} ≤ 0.9 "
                             f"— 경혈(마디)이 아닌 임의 마루가 아닌 곳이다")
    # ② 헤일로는 인선 쪽만 — 도(한날) 1겹 · 검(양날) 2겹
    wfn = _taperw(1.5, tip=0.7)
    assert len(_aura_layers(spine, wfn, "dao", "sinbyeong", (1.0,))) == 1, \
        "오라 자기시험 ②: 한날(도) 헤일로가 인선 1겹이 아니다"
    assert len(_aura_layers(spine, wfn, "sword", "sinbyeong", (1.0, -1.0))) == 2, \
        "오라 자기시험 ②: 양날(검) 헤일로가 2겹이 아니다"
    # ③ 블룸 핫 코어 — 심(b>1)이 흰-핫, 가장자리(b→0)가 어둡다 (광원)
    a_lo, a_hi = _qi_accent("sword", "sinbyeong")
    core = _bloom_color(a_lo, a_hi, 1.25)
    edge = _bloom_color(a_lo, a_hi, 0.1)
    assert sum(core[:3]) > sum(a_hi[:3]), "오라 자기시험 ③: 핫 코어가 악센트보다 밝지 않다 (부산물)"
    assert sum(core[:3]) > sum(edge[:3]) + 120, "오라 자기시험 ③: 블룸 falloff 이 없다 (딱딱한 계단)"


def _volume_selftest():
    """부위별 입체 기하 자기시험 (21차 · 조율자: 사용자 "그냥 두께만 늘린 느낌") — 임포트 시 1회.
    "두께만 늘림"(균일 슬래브) 재발을 기계로 막는다. 네 눈:
      ⓐ 날 단면 볼록 — 날(무태그) 픽셀이 다단 렌즈 (능선>인선 · N≥5 distinct depth).
      ⓑ 자루 봉 — 깊이 ≥ 정면 폭 × 0.7 (슬랩이 아니라 봉).
      ⓒ 코등이 두께 > 날 능선 (별도 부품이 날보다 확실히 두껍다).
      ⓓ 만화 파일럿 장식판 — 회전 부품(±22.5/45°) 존재 (회전 0 이던 것을 부활)."""
    # ⓐ+ⓒ (함수 층) — 볼록 승격 + 다단 + 능선<코등이
    assert _BLADE_CONVEX_ON, "볼륨 자기시험: 7단 볼록이 기본 승격 안 됐다 (_BLADE_CONVEX_ON)"
    assert len(set(_BLADE_CONVEX)) >= 5, "볼륨 자기시험 ⓐ: 날 렌즈 단수 N<5 (슬래브 위험)"
    assert all(_BLADE_CONVEX[i] <= _BLADE_CONVEX[i + 1] for i in range(len(_BLADE_CONVEX) - 1)), \
        "볼륨 자기시험 ⓐ: 렌즈 깊이가 단조 증가(인선→능선)가 아니다"
    assert _BLADE_CONVEX[-1] > _BLADE_CONVEX[0], "볼륨 자기시험 ⓐ: 능선이 인선보다 두껍지 않다"
    assert _VD_ORN[0] > _BLADE_CONVEX[-1], "볼륨 자기시험 ⓒ: 코등이가 날 능선보다 두껍지 않다"
    # ⓑ (함수 층) — 봉 깊이 ≥ 정면 폭 × 0.7
    for hw in (0.62, 0.66, 0.72, 0.8):
        assert _vrod(hw)[0] >= 2 * hw * 0.7, f"볼륨 자기시험 ⓑ: 자루(hw={hw})가 봉이 아니다 (슬랩)"

    def _build(series, grade, r21):
        _R21_ALLOW[0] = r21
        r, b, t, m = _GRADE_FORM[grade]
        box, sh, ex = WEAPON_SPEC[series](21, grade, r, b, t, m)
        rows, k, dep, parts, qi = _compose(sh, box, 21, grade)
        elems, tags, dep2 = _voxelize(rows, dep, k, box, _return_tags=True)
        _R21_ALLOW[0] = False
        return elems, tags, dep2

    # ⓐ+ⓒ (실측 층) — 실제 자루로 날 렌즈 다단·코등이>능선 확인 (R20 base + R21 파일럿 둘 다)
    global _R21
    for r21 in (False, True):
        saved = _R21
        _R21 = r21
        try:
            elems, tags, dep = _build("sword", "sinbyeong", r21)
        finally:
            _R21 = saved
        blade_d = {tags[y][x][0] for y in range(_CANVAS_Y0, _SHEET) for x in range(_SHEET)
                   if tags[y][x] is not None and dep[y][x] is None}
        tagged_d = {tags[y][x][0] for y in range(_CANVAS_Y0, _SHEET) for x in range(_SHEET)
                    if tags[y][x] is not None and dep[y][x] is not None}
        assert len(blade_d) >= 5, f"볼륨 자기시험 ⓐ(실측·r21={r21}): 날 단면 단수 {len(blade_d)}<5"
        assert max(blade_d) > min(blade_d), f"볼륨 자기시험 ⓐ(실측·r21={r21}): 날이 균일 슬래브"
        assert max(tagged_d) > max(blade_d), \
            f"볼륨 자기시험 ⓒ(실측·r21={r21}): 코등이(최대 {max(tagged_d)})가 날 능선보다 안 두껍다"
    # ⓓ~ⓕ — 만화 파일럿(검 신병): 회전 부품 · 팔각 모깎기 · 기 포인트 3D 감쌈 (21b)
    saved = _R21
    _R21 = True
    try:
        elems, tags, dep = _build("sword", "sinbyeong", True)
    finally:
        _R21 = saved
    assert any("rotation" in e for e in elems), \
        "볼륨 자기시험 ⓓ: 만화 파일럿에 회전 부품(±22.5/45° 날개 코등이)이 없다"
    # ⓔ 팔각 모깎기 — oct 부위(자루·코등이·물미관)가 가장자리 모를 죽여 ≥2 깊이(사각기둥 아님)
    oct_d = {tags[y][x][0] for y in range(_CANVAS_Y0, _SHEET) for x in range(_SHEET)
             if dep[y][x] is not None and len(dep[y][x]) > 4 and dep[y][x][4] == "oct"}
    assert len(oct_d) >= 2, f"볼륨 자기시험 ⓔ: 팔각 부위가 단일 깊이 {oct_d} (모깎기 없음 = 사각기둥)"
    # ⓕ 뇌전 float 껍질 — 앞/뒤로 z 갈림이 있다 (스프라이트 ±z · 케이지 다중 z · 옆에 붙은 단일 판 아님)
    if _R21_FX in ("qi", "bolt", "cage", "both"):
        qi_z = {round(dep[y][x][1], 1) for y in range(_CANVAS_Y0, _SHEET) for x in range(_SHEET)
                if dep[y][x] is not None and _vrole(dep[y][x]) == "float"}
        assert len(qi_z) >= 2, f"볼륨 자기시험 ⓕ: 뇌전 float 껍질이 단일 z {qi_z} (코플래너 — 옆에 붙은 판)"
    global _R21_PHASE
    # ⓖ 22차 레퍼런스식 작은 평면 번개 스프라이트 — 검 전체를 감싸는 케이지(과함)가 **아니고**,
    #   **칼끝·코등이 두 키포인트에만** 얹힌 작은 얇은 지그재그 아크 (레퍼런스 · "적은 게 이긴다").
    if _R21_FX in ("bolt", "both"):
        wfn = _taperw(1.4, tip=0.66, slim=0.10)
        spine = [(6.0, 8.0), (23.0, 8.0)]
        _R21_PHASE = None
        bolts = _qi_bolts(21, "sinbyeong", "sword", spine, wfn)
        assert bolts, "볼륨 자기시험 ⓖ: 신병 번개 스프라이트가 0개 (번개 없음)"
        assert all(_vrole(f._vdepth) == "float" for f in bolts), \
            "볼륨 자기시험 ⓖ: 번개 스프라이트가 float 가 아니다 (본체 extent 침범)"
        # 가는 코어 — 두께 상한 (두꺼운 리본 금지 · 전기 아크는 얇다)
        assert _R21_BOLT_W <= 0.32, f"볼륨 자기시험 ⓖ: 주 채널 반폭 {_R21_BOLT_W} > 0.32 (두꺼운 리본)"
        # 작다 — 케이지 회귀 금지 (스프라이트 수 절제 · 레퍼런스: 검 전체 감싸기 아님)
        nb = _R21_BOLT["sinbyeong"][1]
        assert nb <= 4, f"볼륨 자기시험 ⓖ: 스프라이트 수 {nb} > 4 (레퍼런스 절제 위반 — 케이지 회귀)"
        assert len(bolts) <= 2 * nb + 2, \
            f"볼륨 자기시험 ⓖ: 아크 조각 {len(bolts)} 이 많다 (스프라이트당 주+분기 절제 · 케이지 회귀)"
        # ★두 키포인트에만 — 앵커 x 가 칼끝(큰 x) 또는 코등이(작은 x) 무리, 중앙(10..18)엔 없다
        cxs = [f._center[0] for f in bolts if hasattr(f, "_center")]
        assert cxs, "볼륨 자기시험 ⓖ: 스프라이트에 앵커(_center)가 없다"
        assert all(cx > 18.0 or cx < 10.0 for cx in cxs), \
            f"볼륨 자기시험 ⓖ: 번개가 검 중앙(10..18)에 있다 {sorted(cxs)} (칼끝·코등이 두 키포인트 위반)"
        assert any(cx > 18.0 for cx in cxs) and any(cx < 10.0 for cx in cxs), \
            f"볼륨 자기시험 ⓖ: 번개가 칼끝·코등이 두 곳에 모두 있지 않다 {sorted(cxs)}"
        # 불규칙 꺾임 — 세그 각도가 균일 Z 반복이 아니다 (결정론 해시로 varied)
        _p, angs = _bolt_channel(0.0, 8.0, 0.0, _R21_SPARK_LEN, 4, _lcg(3.7), jitter=64.0)
        assert len({round(a, 3) for a in angs}) >= 3, \
            "볼륨 자기시험 ⓖ: 세그 각도가 균일 (불규칙 꺾임 아님 — 아이콘 Z)"
        # 프레임마다 점멸 — 켜진 아크 집합이 프레임에 따라 바뀐다 (치지직 · 정지 번개 아님)
        seeds = [i * 3.1 + 21 * 0.17 for i in range(4)]
        seen = set()
        for fi in range(_R21_FRAMES):
            _R21_PHASE = fi / _R21_FRAMES
            seen.add(tuple(_bolt_fire(i, seeds[i]) for i in range(4)))
        _R21_PHASE = None
        assert any(any(row) for row in seen), "볼륨 자기시험 ⓖ: 번개가 어느 프레임에도 안 켜진다"
        assert len(seen) >= 3, "볼륨 자기시험 ⓖ: 번개가 프레임마다 안 바뀐다 (치지직 아님 · 정지 번개)"
    # ⓖ-cage 21e 보존 — 검 전체를 휘감는 3D 뇌전 케이지 (다중 z · 분기 fork · 치지직)
    if _R21_FX == "cage":
        wfn = _taperw(1.5, tip=0.72, slim=0.09)
        spine = [(6.0, 8.0), (23.0, 8.0)]
        _R21_PHASE = None                          # 기하 프레임 — 케이지 전부 (다중 z 확인용)
        cage = _qi_cage(21, "sinbyeong", "sword", spine, wfn)
        assert cage, "볼륨 자기시험 ⓖ-cage: 신병 뇌전 케이지가 0개"
        zs = {round(f._vdepth[1], 1) for f in cage}
        assert len(zs) >= 2, f"볼륨 자기시험 ⓖ-cage: 케이지가 단일 z {zs} (코플래너)"
        assert len(cage) > 6, f"볼륨 자기시험 ⓖ-cage: 아크 수 {len(cage)} ≤ 6 (분기 fork 없음)"


_continuity_selftest()   # 17차 — 눈을 시험하는 눈 (임포트 시 1회 · 깨지면 팩이 구워지기 전에 죽는다)
_aura_selftest()         # 19차-c — 오라 근거를 기계로 잰다 (광점=경혈 · 헤일로=인선 · 블룸=광원)
_volume_selftest()       # 21차(+b/d) — 입체 기하 (날 렌즈·봉·두꺼운 코등이·회전·팔각·기 3D 감쌈·뇌전 볼트 껍질)


