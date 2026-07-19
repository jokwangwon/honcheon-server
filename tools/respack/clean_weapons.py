#!/usr/bin/env python3
"""V2-W 25차 — **깨끗한 재건축**(clean hand-placed) 무기 생성기.

★배경: 무기 대장정 내내 사용자가 "무기가 뭉툭·이상하다"를 반복했고, 인게임에서 우리
  복셀 무기(_voxelize — 두꺼운 볼록 날·팔각·수실·기 껍질 누적 복잡함)를 크게 키우면
  어수선함이 객관 확인됐다. 반면 손으로 깨끗이 지은 ref_blade(청뢰검 · tools/refblade_forge.py)
  는 우아하다. 사용자 결정: **청뢰검의 깨끗한 손 배치 방식을 우리 무기 로스터에 녹인다.**

이 모듈은 refblade_forge 의 문법을 계열(검·도)·등급 파라미터로 일반화한다:
  · 부위별 소수 cuboid 를 **손으로 배치**한다 (_voxelize 안 씀 · 픽셀 압출 없음).
  · 계열 형태 개성: 검=곧고 가는 롱소드 + 금 십자 코등이(보석) · 도=한날 곡도 + 원반 코등이.
  · 계열 악센트색: 검=옥/청록 · 도=진홍. 등급 사다리(범철 수수 → 신병 선명 → 마병 검붉음).
  · 모델은 **정지 깨끗한 병기** — 날 위 번개 애니 제거. 흐르는 기운은 파티클 트랙(플러그인) 소관.

★파일럿 배선 (weapons._R25 · _R25_PILOTS = {검 신병, 도 신병}):
  weapon_model_3d 가 이 두 자루에서만 clean 경로로 갈아탄다. 나머지 45+12 무기는 무접촉.
  · 아이콘(GUI 2D · weapon/{key}.png)은 write_weapon_asset 이 그대로 굽는다 — 불변.
  · 3D 페인트 시트만 clean 스와치(정사각 128²)로 덮어쓴다 (파일럿 2자루 = 변경 허용 자산).
    옛 R21 애니 스트립(.mcmeta)은 제거한다 (정지 모델 — 애니는 파티클 트랙).

결정론: 난수 없음. 두 번 굽으면 바이트가 같다.
"""
from pathlib import Path

from .core import (
    ITEM_DEF_DIR, ITEM_MODEL_DIR, ITEM_TEX_DIR, PAINT_DIR, _display, write_json, write_png,
)

T = (0, 0, 0, 0)

# ═══ 계열·등급 색 램프 ════════════════════════════════════════════════════════
# 각 region = (hi, mid, dim). broad face(북/남 = 몸색 = 계열을 말하는 면)는 mid 를 문다.
# 등급 사다리: 범철=수수한 강철(무채) → 신병=선명한 악센트+밝은 능선 → 마병=검붉음.
# ★파일럿은 검 신병·도 신병만. 범철/마병 램프는 전파 대비 골격만 둔다 (미배선).

# 검(劍) — 옥/청록 롱소드
_SWORD = {
    "sinbyeong": {
        "blade": ((86, 210, 202, 255), (44, 168, 168, 255), (24, 118, 128, 255)),   # 청록 검신
        "ridge": ((238, 252, 254, 255), (198, 240, 244, 255), (120, 206, 214, 255)),  # 흰-청록 능선
        "gold":  ((250, 218, 128, 255), (214, 172, 78, 255), (150, 112, 44, 255)),   # 금 코등이/물미
        "navy":  ((58, 76, 118, 255), (36, 48, 88, 255), (18, 26, 54, 255)),         # 남색 자루
        "gem":   ((152, 188, 255, 255), (74, 116, 236, 255), (42, 70, 176, 255)),    # 파란 보석
    },
    # ── 전파 골격 (미배선) ──
    "beomcheol": {
        "blade": ((150, 158, 166, 255), (108, 116, 126, 255), (64, 70, 80, 255)),    # 수수한 강철
        "ridge": ((196, 202, 208, 255), (158, 164, 172, 255), (110, 116, 124, 255)),
        "gold":  ((150, 138, 108, 255), (116, 106, 82, 255), (78, 72, 54, 255)),      # 무광 놋
        "navy":  ((70, 74, 82, 255), (48, 52, 60, 255), (28, 32, 40, 255)),
        "gem":   ((120, 126, 134, 255), (86, 92, 100, 255), (54, 60, 68, 255)),
    },
    "mabyeong": {
        "blade": ((150, 60, 54, 255), (104, 34, 32, 255), (60, 18, 18, 255)),         # 검붉은 날
        "ridge": ((214, 120, 108, 255), (170, 80, 72, 255), (110, 46, 42, 255)),
        "gold":  ((160, 120, 60, 255), (120, 88, 44, 255), (78, 56, 28, 255)),
        "navy":  ((46, 30, 34, 255), (30, 20, 24, 255), (16, 12, 16, 255)),
        "gem":   ((196, 70, 60, 255), (140, 38, 34, 255), (86, 22, 20, 255)),
    },
}

# 도(刀) — 진홍 한날 곡도. 강철 몸 + 밝은 인선 + 어두운 등(척) + 진홍 악센트(감김·혈조·수실).
_DAO = {
    "sinbyeong": {
        "steel":   ((196, 206, 216, 255), (150, 160, 172, 255), (78, 86, 98, 255)),   # 도신 몸
        "edge":    ((246, 248, 250, 255), (208, 214, 222, 255), (150, 162, 174, 255)),  # 밝은 인선
        "spine":   ((96, 104, 118, 255), (60, 66, 78, 255), (30, 34, 42, 255)),        # 어두운 등(척)
        "crimson": ((232, 120, 110, 255), (196, 58, 52, 255), (120, 28, 26, 255)),     # 진홍 악센트
        "gold":    ((250, 218, 128, 255), (214, 172, 78, 255), (150, 112, 44, 255)),   # 금 원반/물미
    },
    "beomcheol": {
        "steel":   ((150, 158, 166, 255), (108, 116, 126, 255), (64, 70, 80, 255)),
        "edge":    ((198, 204, 210, 255), (160, 166, 174, 255), (112, 118, 126, 255)),
        "spine":   ((92, 98, 106, 255), (58, 64, 72, 255), (32, 36, 44, 255)),
        "crimson": ((120, 96, 96, 255), (90, 70, 70, 255), (56, 44, 44, 255)),
        "gold":    ((150, 138, 108, 255), (116, 106, 82, 255), (78, 72, 54, 255)),
    },
    "mabyeong": {
        "steel":   ((150, 70, 62, 255), (104, 42, 38, 255), (60, 22, 20, 255)),
        "edge":    ((224, 150, 140, 255), (184, 100, 92, 255), (128, 56, 50, 255)),
        "spine":   ((70, 30, 30, 255), (46, 20, 20, 255), (26, 12, 12, 255)),
        "crimson": ((214, 60, 50, 255), (156, 34, 30, 255), (92, 20, 18, 255)),
        "gold":    ((160, 120, 60, 255), (120, 88, 44, 255), (78, 56, 28, 255)),
    },
}

_PALETTES = {"sword": _SWORD, "dao": _DAO}


def clean_palette(series, grade):
    return _PALETTES[series][grade]


# ═══ 텍스처 스와치 아틀라스 (128×128 정사각 · 정지) ═══════════════════════════
# 넉넉한 무채 불투명 바탕(채도≈10) 위에 region×shade 블록을 얹는다. 모델 UV 는 블록만 문다.
# 바탕이 opaque 면적을 지배 → 축 ㉓ 평균채도(가시 픽셀만 셈) ≤ 85 를 여유로 통과.
# 알파는 이분(전부 255) — 중간 알파 0% (알파 위생 통과). 픽셀 아트 플랫 규율 유지 (그라데이션 없음).
TW = 128
FILL = (44, 48, 54, 255)          # 무채 바탕 (채도 10 — 모델이 안 무는 여백)
_CW = 12                          # region 열 폭(px)
_X0 = 4                           # 블록 시작 x
_BANDS = [(4, 16), (18, 30), (32, 44)]   # hi / mid / dim 세로 구간(px)
_UVF = 16.0 / TW                  # px → uv (0..16) 환산 (128px → 1/8)


def _region_order(pal):
    return list(pal.keys())


def clean_swatch(series, grade):
    """region×shade 색 스와치 아틀라스 (128×128 · 무채 바탕 + 블록)."""
    pal = clean_palette(series, grade)
    rows = [[FILL] * TW for _ in range(TW)]
    for ci, region in enumerate(_region_order(pal)):
        hi, mid, dim = pal[region]
        x0 = _X0 + ci * _CW
        for shade, (y0, y1) in zip((hi, mid, dim), _BANDS):
            for y in range(y0, y1):
                for x in range(x0, min(x0 + _CW, TW)):
                    rows[y][x] = shade
    return rows


def _shade_uv(series, grade):
    """region → {hi/mid/dim: (u0,v0,u1,v1)} — 블록 안쪽 작은 uv 사각(가장자리 새어듦 방지)."""
    pal = clean_palette(series, grade)
    out = {}
    for ci, region in enumerate(_region_order(pal)):
        x0 = _X0 + ci * _CW
        for band, (y0, y1) in zip(("hi", "mid", "dim"), _BANDS):
            out[f"{region}_{band}"] = ((x0 + 3) * _UVF, (y0 + 3) * _UVF,
                                       (x0 + _CW - 3) * _UVF, (y1 - 3) * _UVF)
    return out


def _faces(region, uvmap, tex="#0"):
    """윗면 밝게·아랫면 어둡게·옆면 몸 (좌상 광원). broad face(N/S) = region mid = 계열을 말한다."""
    hi = list(uvmap[f"{region}_hi"])
    mid = list(uvmap[f"{region}_mid"])
    dim = list(uvmap[f"{region}_dim"])
    return {"up": {"texture": tex, "uv": hi}, "down": {"texture": tex, "uv": dim},
            "north": {"texture": tex, "uv": mid}, "south": {"texture": tex, "uv": mid},
            "east": {"texture": tex, "uv": hi}, "west": {"texture": tex, "uv": dim}}


def _cuboid(box, region, uvmap):
    return {"from": [round(v, 3) for v in box[:3]],
            "to": [round(v, 3) for v in box[3:]],
            "faces": _faces(region, uvmap)}


# ═══ 기하 (모델 공간: X=길이·칼끝 +X, Y=폭·중심 8, Z=두께·중심 8) ══════════════
# refblade_forge 의 손 배치 문법. 부위 근거는 weapon_anatomy_canon.md 유지
# (가는 볼록 날·봉 자루·코등이·물미). 계열 형태 개성만 갈린다.

def _sword_spec():
    """검 신병 — 곧고 가는 롱소드 + 금 십자 코등이(중앙 파란 보석) + 남색 자루/금 물미.
    청뢰검 문법: 청록 몸 슬래브 + 가운데 흰-청록 능선(Z 돌출=볼록) · 3단 테이퍼로 칼끝."""
    return [
        # ── 검신 (롱소드 폭) : 몸(청록) + 가운데 능선(흰-청록, Z 볼록 돌출) — 3단 테이퍼 ──
        ((2.5, 6.3, 7.55, 18.0, 9.7, 8.45), "blade"),    # 본체 몸 (폭 3.4·두께 0.9)
        ((2.5, 7.55, 7.35, 18.0, 8.45, 8.65), "ridge"),  # 본체 능선 (볼록)
        ((18.0, 6.9, 7.65, 22.0, 9.1, 8.35), "blade"),   # 중간 몸 (폭 2.2)
        ((18.0, 7.6, 7.5, 22.0, 8.4, 8.5), "ridge"),     # 중간 능선
        ((22.0, 7.35, 7.75, 25.5, 8.65, 8.25), "blade"),  # 칼끝 (폭 1.3, 점)
        # ── 코등이: 화려한 금 십자 — 가로대 + 양끝 상향 곡선/뾰족 돌기 + 중앙 파란 보석 + 목 collar ──
        ((0.3, 4.3, 7.15, 2.3, 11.7, 8.85), "gold"),     # 가로대 본체 (Y 폭 넓게 — 십자)
        ((0.7, 11.7, 7.4, 2.5, 12.7, 8.6), "gold"),      # 위 팔 (상향)
        ((1.1, 12.7, 7.6, 2.4, 13.4, 8.4), "gold"),      # 위 끝 뾰족 돌기
        ((0.7, 3.3, 7.4, 2.5, 4.3, 8.6), "gold"),        # 아래 팔 (대칭)
        ((1.1, 2.6, 7.6, 2.4, 3.3, 8.4), "gold"),        # 아래 끝 뾰족 돌기
        ((2.3, 6.9, 7.45, 4.0, 9.1, 8.55), "gold"),      # 목 collar (langet)
        ((0.5, 7.35, 8.55, 2.1, 8.65, 9.35), "gem"),     # 파란 보석 앞면(+Z)
        ((0.5, 7.35, 6.65, 2.1, 8.65, 7.45), "gem"),     # 파란 보석 뒷면(-Z)
        # ── 자루: 남색 감김 + 금 띠 2 + 금 스터드 + 금 물미 ──
        ((-3.6, 7.2, 7.2, 0.3, 8.8, 8.8), "navy"),       # 남색 자루 (정사각 봉)
        ((-0.3, 7.1, 7.1, 0.3, 8.9, 8.9), "gold"),       # 금 띠 (자루 목)
        ((-2.7, 7.1, 7.1, -2.2, 8.9, 8.9), "gold"),      # 금 띠 (자루 중간)
        ((-1.4, 7.65, 8.8, -0.9, 8.35, 9.1), "gold"),    # 금 스터드 앞 (위)
        ((-1.4, 7.65, 6.9, -0.9, 8.35, 7.2), "gold"),    # 금 스터드 뒤 (위)
        ((-2.0, 7.65, 8.8, -1.5, 8.35, 9.1), "gold"),    # 금 스터드 앞 (아래)
        ((-2.0, 7.65, 6.9, -1.5, 8.35, 7.2), "gold"),    # 금 스터드 뒤 (아래)
        ((-4.6, 7.0, 7.0, -3.6, 9.0, 9.0), "gold"),      # 물미 금 캡
        ((-4.95, 7.4, 7.4, -4.6, 8.6, 8.6), "gold"),     # 물미 끝 마감
    ]


def _dao_spec():
    """도 신병 — 한날 곡도 + 원반 코등이 + 진홍 악센트(감김·혈조·수실).
    한날의 문법: 어두운 등(척)이 등을 지고 밝은 인선이 배를 부른다. 완만한 휨(세그먼트 Y 드리프트).
    진홍은 계열 악센트 — 감김·혈조 홈·수실·보석에 얹는다 (날 전체를 붉히지 않는다)."""
    spec = []
    # ── 도신 4세그먼트 (곡도 휨 = 중심 Y 가 칼끝으로 갈수록 상향) ──
    # (x0, x1, cy=중심 Y, hw=반폭)
    segs = [(2.5, 10.0, 8.0, 1.7), (10.0, 16.0, 8.3, 1.5),
            (16.0, 21.0, 8.8, 1.15), (21.0, 25.0, 9.4, 0.6)]
    for i, (x0, x1, cy, hw) in enumerate(segs):
        spec.append(((x0, cy - hw, 7.55, x1, cy + hw, 8.45), "steel"))          # 몸 슬래브
        spec.append(((x0, cy + hw - 0.55, 7.3, x1, cy + hw, 8.7), "spine"))     # 등(척) — 두꺼운 Z
        if i < 3:
            spec.append(((x0, cy - hw, 7.75, x1, cy - hw + 0.45, 8.25), "edge"))  # 인선 — 얇은 Z
            spec.append(((x0, cy - 0.1, 8.45, x1, cy + 0.5, 8.62), "crimson"))    # 혈조 홈 (진홍 · 앞면 돌출)
    # ── 원반 코등이 (도 고유 · 금) — 통형 호수를 Y·Z 넓은 두 판 겹침으로 둥글게 ──
    spec += [
        ((0.6, 5.6, 7.2, 2.4, 10.4, 8.8), "gold"),       # 원반 (Y 넓게)
        ((0.8, 6.8, 6.6, 2.2, 9.2, 9.4), "gold"),        # 원반 (Z 넓게 — 둥글게 읽힘)
        ((1.0, 7.4, 8.8, 2.0, 8.6, 9.35), "crimson"),    # 코등이 진홍 보석 앞
        ((1.0, 7.4, 6.65, 2.0, 8.6, 7.2), "crimson"),    # 코등이 진홍 보석 뒤
        ((2.4, 7.3, 7.4, 3.4, 8.7, 8.6), "gold"),        # 목띠 (하바키)
    ]
    # ── 자루: 진홍 감김 + 금 띠 2 + 금 물미 + 진홍 수실 ──
    spec += [
        ((-4.0, 7.2, 7.2, 0.6, 8.8, 8.8), "crimson"),    # 진홍 감김 (정사각 봉)
        ((-0.1, 7.1, 7.1, 0.4, 8.9, 8.9), "gold"),       # 금 띠 (자루 목)
        ((-2.6, 7.1, 7.1, -2.1, 8.9, 8.9), "gold"),      # 금 띠 (자루 중간)
        ((-5.0, 7.0, 7.0, -4.0, 9.0, 9.0), "gold"),      # 물미 금 캡
        ((-5.35, 7.4, 7.4, -5.0, 8.6, 8.6), "gold"),     # 물미 끝 마감
        ((-4.8, 5.5, 7.6, -4.2, 7.0, 8.4), "crimson"),   # 수실 (신병 — 물미 아래 드리개)
        ((-4.6, 4.2, 7.65, -4.2, 5.5, 8.35), "crimson"),  # 수실 끝
    ]
    return spec


def clean_body_spec(series, grade):
    """계열·등급 → [(box, region)…]. build() 와 몽타주가 같은 진실을 읽는다.
    (등급은 색 램프만 가른다 — 형태는 계열이 쥔다 · weapon_anatomy_canon §0 소유권)."""
    return _sword_spec() if series == "sword" else _dao_spec()


def _span(elems):
    xs = [c for e in elems for c in (e["from"][0], e["to"][0])]
    ys = [c for e in elems for c in (e["from"][1], e["to"][1])]
    zs = [c for e in elems for c in (e["from"][2], e["to"][2])]
    return max(max(xs) - min(xs), max(ys) - min(ys), max(zs) - min(zs))


def clean_weapon_model(series, grade):
    """clean 3D 모델 JSON dict — 손 배치 cuboid + clean 스와치 참조.
    텍스처 0 = clean 스와치(paint 시트) · particle = 아이콘(불변)."""
    uvmap = _shade_uv(series, grade)
    elems = [_cuboid(box, region, uvmap) for box, region in clean_body_spec(series, grade)]
    span = _span(elems)
    key = f"{series}_{grade}"
    return {
        "textures": {"0": f"honcheon:item/{PAINT_DIR}/{key}",
                     "particle": f"honcheon:item/weapon/{key}"},
        "elements": elems,
        "display": _display(span, span),
        "gui_light": "front",
    }


def write_clean_pilot(series, grade):
    """파일럿 한 자루 — clean 스와치 페인트 시트를 굽고(정사각 128²) 옛 애니 .mcmeta 를 제거,
    clean 모델 dict 를 돌려준다. 아이콘 PNG·아이템 정의·모델 JSON 은 호출부(write_weapon_asset)가
    그대로 쓴다 (아이콘 불변 · 3D 만 교체). 결정론 — 난수 없음."""
    key = f"{series}_{grade}"
    sheet = ITEM_TEX_DIR / f"{PAINT_DIR}/{key}.png"
    write_png(sheet, clean_swatch(series, grade))
    # 정지 깨끗한 병기 — 옛 R21 번개 애니 스트립 .mcmeta 제거 (있으면). 애니는 파티클 트랙 소관.
    meta = sheet.with_name(sheet.name + ".mcmeta")
    if meta.exists():
        meta.unlink()
    return clean_weapon_model(series, grade)


if __name__ == "__main__":
    for s in ("sword", "dao"):
        m = clean_weapon_model(s, "sinbyeong")
        print(f"{s}_sinbyeong: elements {len(m['elements'])} · span {_span(m['elements']):.1f}")
