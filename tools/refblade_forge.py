#!/usr/bin/env python3
"""레퍼런스 검 **영상 프레임 정합** — 손으로 짓는 독립 무기 (ref_blade).

★ 24차 정정 (실제 영상 프레임 scratch/ref_lightning/vid_zoom1~2.png · vid_grid.png 정밀 관찰):
  앞선 복제(23차)는 정지 스크린샷을 근사했고 세 부위가 틀렸다. 영상은 이렇게 말한다.
    · 코등이 = **화려한 금색 장식 십자** — 곡선·양끝 뾰족 돌기 + **중앙 파란 보석 큐브**.
      (23차의 "단순 가로대"는 틀렸다 — 영상은 장식적 금 십자다.)
    · 검신 = **청록 몸 + 가운데 흰-청록 밝은 세로 능선**(하이라이트 줄). 폭은 **롱소드급**(초슬림 아님).
    · 자루 = **남색/검정** + 금 띠·금 스터드. 물미 = **금 캡**.
    · 정통 롱소드 비례 — 길고 우아하나 레이피어처럼 얇지 않다.

  ★번개 평면 스프라이트(23차)는 **제거**한다. 흐르는 기운은 별도 트랙(플러그인 파티클)이 맡는다.
   이 트랙은 **정지 모델만** 영상에 맞춘다 (Java 무접촉).

★이 파일은 tools/respack/weapons.py 의 복셀 스펙(WEAPON_SPEC)을 **거치지 않는다.**
  cuboid 를 손으로 배치한다. 기존 45+8 무기 자산은 무접촉 (신규 키 ref_blade 만 굽는다).
  결정론: 난수 없음. 두 번 돌리면 바이트가 같다.

산출 (전부 신규):
  resourcepack/assets/honcheon/items/weapon/ref_blade.json          아이템 정의
  resourcepack/assets/honcheon/models/item/weapon/ref_blade.json    모델 (손 배치 cuboid)
  resourcepack/assets/honcheon/textures/item/weapon/ref_blade.png   32x32 색 스와치 아틀라스
    (ref_bolt.png / .mcmeta 는 24차에 제거 — 번개는 파티클 트랙 소관)

인게임 조회 (팩만으로 — jar 변경·기존 자산 무접촉):
  /give Lindydone golden_sword[minecraft:item_model="honcheon:weapon/ref_blade"]
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from respack.core import (  # noqa: E402
    ITEM_DEF_DIR, ITEM_MODEL_DIR, ITEM_TEX_DIR, _display, write_json, write_png,
)

T = (0, 0, 0, 0)

# ─── 색 램프 (영상 톤) ───────────────────────────────────────────────────────
# 검신: 청록 몸 + 가운데 흰-청록 능선(밝은 세로 줄)
RIDGE_HI = (238, 252, 254, 255)   # 능선 마루 — 거의 흰-청 (빛을 되쏘는 선)
RIDGE_MID = (198, 240, 244, 255)  # 능선 broad face — 흰-청록 (가운데 밝은 줄)
RIDGE_DIM = (120, 206, 214, 255)  # 능선 그늘
BLADE_HI = (86, 210, 202, 255)    # 검신 몸 밝음 — 청록
BLADE_MID = (44, 168, 168, 255)   # 검신 몸 broad face — 청록 (능선 양옆이 이 색)
BLADE_DIM = (24, 118, 128, 255)   # 검신 몸 그늘
# 금 (코등이·물미·띠·스터드·목collar)
GOLD_HI = (250, 218, 128, 255)    # 금 광
GOLD_MID = (214, 172, 78, 255)    # 금 몸
GOLD_DIM = (150, 112, 44, 255)    # 금 그늘
# 남색 자루
NAVY_HI = (58, 76, 118, 255)      # 자루 밝음 (마루)
NAVY_MID = (36, 48, 88, 255)      # 자루 몸
NAVY_DIM = (18, 26, 54, 255)      # 자루 그늘 (거의 검정)
# 파란 보석 (코등이 중앙)
GEM_HI = (152, 188, 255, 255)     # 보석 광 — 밝은 파랑
GEM_MID = (74, 116, 236, 255)     # 보석 몸 — 파랑
GEM_DIM = (42, 70, 176, 255)      # 보석 그늘 — 짙은 파랑

# ─── 텍스처 32x32: 5열(능선·검신·금·자루·보석) × 3단(hi/mid/dim) ──────────────
TW = 32
COLW = 6                          # 열 폭(px) — 5열 = 30px (나머지 2px 투명, 무해)
COLS = [("ridge", RIDGE_HI, RIDGE_MID, RIDGE_DIM),
        ("blade", BLADE_HI, BLADE_MID, BLADE_DIM),
        ("gold", GOLD_HI, GOLD_MID, GOLD_DIM),
        ("navy", NAVY_HI, NAVY_MID, NAVY_DIM),
        ("gem", GEM_HI, GEM_MID, GEM_DIM)]
BANDS = [(0, 10), (10, 21), (21, 32)]   # hi / mid / dim 세로 구간(px)


def blade_texture():
    rows = [[T] * TW for _ in range(TW)]
    for ci, (_region, hi, mid, dim) in enumerate(COLS):
        x0, x1 = ci * COLW, ci * COLW + COLW
        for shade, (y0, y1) in zip((hi, mid, dim), BANDS):
            for y in range(y0, y1):
                for x in range(x0, x1):
                    rows[y][x] = shade
    return rows


# uv(0..16) = px * 0.5. 각 면이 샘플하는 작은 uv 사각(그 단색 블록 안, 가장자리 새어듦 방지).
SHADE_UV = {}
for _ci, (_region, _hi, _mid, _dim) in enumerate(COLS):
    _ux = _ci * (COLW * 0.5)                 # 6px = 3.0uv
    SHADE_UV[f"{_region}_hi"] = (_ux + 0.4, 0.6, _ux + 2.6, 4.4)
    SHADE_UV[f"{_region}_mid"] = (_ux + 0.4, 5.6, _ux + 2.6, 9.9)
    SHADE_UV[f"{_region}_dim"] = (_ux + 0.4, 11.2, _ux + 2.6, 15.4)


def _faces(region, tex="#0"):
    """윗면 밝게·아랫면 어둡게·옆면 몸 (좌상 광원). broad face(N/S) = region 몸색 = 계열을 말한다."""
    hi, mid, dim = SHADE_UV[f"{region}_hi"], SHADE_UV[f"{region}_mid"], SHADE_UV[f"{region}_dim"]
    return {
        "up": {"texture": tex, "uv": list(hi)},
        "down": {"texture": tex, "uv": list(dim)},
        "north": {"texture": tex, "uv": list(mid)},
        "south": {"texture": tex, "uv": list(mid)},
        "east": {"texture": tex, "uv": list(hi)},
        "west": {"texture": tex, "uv": list(dim)},
    }


def cuboid(box, region):
    return {"from": [round(v, 3) for v in box[:3]],
            "to": [round(v, 3) for v in box[3:]],
            "faces": _faces(region)}


# ═══ 기하 (모델 공간: X=길이·칼끝 +X, Y=폭·중심 8, Z=두께·중심 8) ══════════════
# 영상 비례: 정통 롱소드 — 폭이 있고 볼록. 가운데 밝은 능선. 화려한 금 십자 코등이.
# BODY_SPEC = [(box, region)…] — build() 와 몽타주(v24_drive) 가 같은 진실을 읽는다.
BODY_SPEC = [
    # ── 검신 (롱소드 폭 ≈3.4) : 몸(청록) 슬래브 + 가운데 능선(흰-청록, Z 로 볼록 돌출)
    #    broad face 로 보면: 청록 | 밝은 능선 | 청록  (가운데 밝은 세로 줄이 읽힌다)
    ((2.5, 6.3, 7.55, 18.0, 9.7, 8.45), "blade"),    # 본체 몸 (청록, 폭 3.4·두께 0.9)
    ((2.5, 7.55, 7.35, 18.0, 8.45, 8.65), "ridge"),  # 본체 능선 (흰-청록, Z 돌출=볼록)
    ((18.0, 6.9, 7.65, 22.0, 9.1, 8.35), "blade"),   # 중간 몸 (폭 2.2 — 테이퍼 1단)
    ((18.0, 7.6, 7.5, 22.0, 8.4, 8.5), "ridge"),     # 중간 능선
    ((22.0, 7.35, 7.75, 25.5, 8.65, 8.25), "blade"),  # 칼끝 (폭 1.3, 점)
    # ── 코등이: 화려한 금 십자 — 가로대 + 양끝 곡선/뾰족 돌기 + 중앙 파란 보석 + 목 collar
    ((0.3, 4.3, 7.15, 2.3, 11.7, 8.85), "gold"),     # 가로대 본체 (Y 폭 넓게 — 십자)
    ((0.7, 11.7, 7.4, 2.5, 12.7, 8.6), "gold"),      # 위 팔 — 칼끝 쪽으로 밀어 상향 곡선
    ((1.1, 12.7, 7.6, 2.4, 13.4, 8.4), "gold"),      # 위 끝 뾰족 돌기
    ((0.7, 3.3, 7.4, 2.5, 4.3, 8.6), "gold"),        # 아래 팔 — 상향 곡선 (대칭)
    ((1.1, 2.6, 7.6, 2.4, 3.3, 8.4), "gold"),        # 아래 끝 뾰족 돌기
    ((2.3, 6.9, 7.45, 4.0, 9.1, 8.55), "gold"),      # 목 collar — 검신 위로 흘러내린 금 (langet)
    ((0.5, 7.35, 8.55, 2.1, 8.65, 9.35), "gem"),     # 파란 보석 — 앞면(+Z) 돌출 큐브
    ((0.5, 7.35, 6.65, 2.1, 8.65, 7.45), "gem"),     # 파란 보석 — 뒷면(-Z) 대칭 (F5 양면)
    # ── 자루: 남색 감김 + 금 띠 2 + 금 스터드 + 금 물미
    ((-3.6, 7.2, 7.2, 0.3, 8.8, 8.8), "navy"),       # 남색 자루 (거의 정사각)
    ((-0.3, 7.1, 7.1, 0.3, 8.9, 8.9), "gold"),       # 금 띠 — 코등이 아래(자루 목)
    ((-2.7, 7.1, 7.1, -2.2, 8.9, 8.9), "gold"),      # 금 띠 — 자루 중간
    ((-1.4, 7.65, 8.8, -0.9, 8.35, 9.1), "gold"),    # 금 스터드 앞면 (위)
    ((-1.4, 7.65, 6.9, -0.9, 8.35, 7.2), "gold"),    # 금 스터드 뒷면 (위)
    ((-2.0, 7.65, 8.8, -1.5, 8.35, 9.1), "gold"),    # 금 스터드 앞면 (아래)
    ((-2.0, 7.65, 6.9, -1.5, 8.35, 7.2), "gold"),    # 금 스터드 뒷면 (아래)
    ((-4.6, 7.0, 7.0, -3.6, 9.0, 9.0), "gold"),      # 물미 — 금 캡 (자루보다 살짝 넓게)
    ((-4.95, 7.4, 7.4, -4.6, 8.6, 8.6), "gold"),     # 물미 끝 마감 (둥근 느낌)
]

BODY = [cuboid(box, region) for box, region in BODY_SPEC]
ELEMENTS = BODY


def _span(elems):
    xs = [c for e in elems for c in (e["from"][0], e["to"][0])]
    ys = [c for e in elems for c in (e["from"][1], e["to"][1])]
    zs = [c for e in elems for c in (e["from"][2], e["to"][2])]
    return max(max(xs) - min(xs), max(ys) - min(ys), max(zs) - min(zs))


def build():
    ITEM_TEX_DIR.mkdir(parents=True, exist_ok=True)
    # 텍스처 (단일 스와치 아틀라스 — 번개 스트립 없음)
    write_png(ITEM_TEX_DIR / "weapon" / "ref_blade.png", blade_texture())
    # 24차 — 옛 번개 스트립 제거 (있으면 지운다; 번개는 파티클 트랙 소관)
    for old in ("ref_bolt.png", "ref_bolt.png.mcmeta"):
        p = ITEM_TEX_DIR / "weapon" / old
        if p.exists():
            p.unlink()
    # 모델
    frame = _span(ELEMENTS)
    body = _span(BODY)
    model = {
        "textures": {
            "0": "honcheon:item/weapon/ref_blade",
            "particle": "honcheon:item/weapon/ref_blade",
        },
        "elements": ELEMENTS,
        "display": _display(frame, body),
        "gui_light": "front",
    }
    write_json(ITEM_MODEL_DIR / "weapon" / "ref_blade.json", model)
    # 아이템 정의 (1.21.4)
    write_json(ITEM_DEF_DIR / "weapon" / "ref_blade.json",
               {"model": {"type": "minecraft:model", "model": "honcheon:item/weapon/ref_blade"}})
    print(f"ref_blade: elements {len(ELEMENTS)} (전부 정지 몸통 — 번개 제거) · "
          f"frame span {frame:.1f} · body span {body:.1f}")


if __name__ == "__main__":
    build()
