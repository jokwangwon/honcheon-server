"""엔티티 텍스처 징발 + 짐승의 형체(3D) (T1/T2)."""
# 기계 분할 산출 — 원본: tools/build_resourcepack.py (pack_upgrade_v1.md §2 0단계).
# 로직 무수정: 함수 본문·상수 값은 원본 그대로다 (이동만 했다).
import json
import math
import struct
import sys
import zlib
from pathlib import Path
from .core import (
    ENTITY_DIR, ITEM_DEF_DIR, MODEL_DIR, PACK, ROOT, T, h32, mix, octave, ramp, smooth_octave,
    step, write_json, write_png,
)

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


# ── 호랑이 v2 (RP-4 파일럿) — 몸통에서 다리 4개가 떨어져 나왔다 (관절 파트) ──
#   torso·leg 는 **중심 원점 파트**다 (y 0..16 — 등록부 offset 이 중심 높이를 잡는다).
#   다리의 모델 중심(y 8)이 엉덩관절이다: MobDisplay 가 rotateX 로 관절을 돌리면 발끝이 호를 그린다.
#   다리 위 절반(y 8..16)은 관골·근육 — 몸통 옆구리에 파묻혀서, 회전해도 이음매가 벌어지지 않는다.
#   v1 병합 몸통(horangi_body)은 지우지 않는다 — lod_parts(원거리·예산 압박의 저파트 폴백)가 그것을 입는다.

def horangi_torso():
    """v2 몸통 — 다리가 없다 (관절 파트가 됐다). 어깨가 엉덩이보다 높은 포식자의 등선은 유지.
    가슴이 깊고(y 0 까지) 허리로 갈수록 낮아진다. 어깨 블록이 x 0..16 — 몸통이 가장 넓은 곳이다."""
    return [_fb(0, 6, 8, 16, 16, 14, "main", down="light"),       # 어깨 — 가장 높고(y 16) 가장 넓다(x 0..16)
            _fb(2.5, 0, 8, 13.5, 8, 15, "main", down="light"),    # 가슴 — 깊다 (y 0, 다리 사이로 내려온다)
            _fb(4, 7, 14, 12, 15, 16, "main"),                    # 목 (코 쪽 +Z 끝 — z 16)
            _fb(1.5, 4, 2, 14.5, 14, 8, "main", down="light"),    # 허리·엉덩이 — 낮게 흐른다
            _fb(5, 5, 0, 11, 11, 2, "dark")]                      # 꼬리 밑동 (z 0)


def horangi_leg_front():
    """v2 앞다리 — 관절(모델 중심 y 8)을 축으로 흔들린다. 위 절반은 어깨 근육(몸통에 묻힘)."""
    return [_fb(0, 8, 0, 16, 16, 16, "main"),                     # 관골·어깨 근육 — 피벗 위의 살
            _fb(3, 4, 2.5, 13, 9, 13.5, "main"),                  # 윗다리
            _fb(4.5, 1.6, 4, 11.5, 5, 12, "main"),                # 정강이
            _fb(2.5, 0, 1.5, 13.5, 1.8, 14.5, "dark", down="accent")]  # 발 — 발톱 (y 0)


def horangi_leg_hind():
    """v2 뒷다리 — 앞다리보다 허벅지가 두껍다 (도약하는 짐승의 뒷심)."""
    return [_fb(0, 8, 0, 16, 16, 16, "main"),                     # 관골·엉덩이 근육 — 피벗 위의 살
            _fb(2.5, 3.5, 2, 13.5, 9.5, 14.5, "main"),            # 허벅지 — 두껍다
            _fb(4.5, 1.6, 5, 11.5, 4.5, 12.5, "main"),            # 정강이
            _fb(2.5, 0, 2, 13.5, 1.8, 15, "dark", down="accent")]  # 발 — 발톱 (y 0)


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


# 짐승 13파트 — (경로, 기하, 가죽). 산늑대는 **일부러 굽지 않는다**:
#   mob_models.yml 이 shape: vanilla 로 못 박았다 ("이미 늑대다 — 조각으로 만들면 더 나빠진다").
#   구워 두면 언젠가 누군가 켤 것이고, 그러면 여섯 마리가 뻣뻣하게 미끄러진다. 등록부의 판단을 따른다.
#   호랑이는 v2 관절 6파트(torso + leg×4 는 새 키)에 v1 병합 몸통(body)을 **함께** 굽는다 —
#   body 는 죽은 자산이 아니라 lod_parts(원거리·예산 압박의 저파트 폴백)의 몸이다.
MOB_PARTS = {
    "mob/horangi/body": (horangi_body, (128, 124, 116), (44, 42, 40), (206, 202, 192), (240, 238, 232), 3, 1),
    "mob/horangi/head": (horangi_head, (128, 124, 116), (44, 42, 40), (206, 202, 192), (240, 238, 232), 3, 2),
    "mob/horangi/tail": (horangi_tail, (128, 124, 116), (44, 42, 40), (206, 202, 192), (240, 238, 232), 3, 3),
    "mob/horangi/torso": (horangi_torso, (128, 124, 116), (44, 42, 40), (206, 202, 192), (240, 238, 232), 3, 9),
    "mob/horangi/leg_fl": (horangi_leg_front, (128, 124, 116), (44, 42, 40), (206, 202, 192), (240, 238, 232), 3, 10),
    "mob/horangi/leg_fr": (horangi_leg_front, (128, 124, 116), (44, 42, 40), (206, 202, 192), (240, 238, 232), 3, 11),
    "mob/horangi/leg_hl": (horangi_leg_hind, (128, 124, 116), (44, 42, 40), (206, 202, 192), (240, 238, 232), 3, 12),
    "mob/horangi/leg_hr": (horangi_leg_hind, (128, 124, 116), (44, 42, 40), (206, 202, 192), (240, 238, 232), 3, 13),
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
        # ★ 가죽도 아틀라스 안(textures/item/mob/**)에 굽는다 — 모델 키(honcheon:mob/…)는 불변.
        #   mob_models.yml 이 그 이름을 부르므로 키를 바꾸면 짐승이 통째로 끊긴다.
        write_png(PACK / "assets" / "honcheon" / "textures" / "item" / f"{path}.png",
                  fur_rows(main, dark, light, accent, stripe, salt))
        elems = rig()
        _fill_check(elems, path)
        write_json(MODEL_DIR / f"{path}.json", {
            "textures": {"0": f"honcheon:item/{path}", "particle": f"honcheon:item/{path}"},
            "elements": elems,
            "gui_light": "front",
        })
        write_json(ITEM_DEF_DIR / f"{path}.json",
                   {"model": {"type": "minecraft:model", "model": f"honcheon:{path}"}})
    return len(MOB_PARTS)


