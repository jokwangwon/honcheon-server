#!/usr/bin/env python3
"""레퍼런스 번개 검 정밀 복제 — 손으로 짓는 독립 무기 (ref_blade).

레퍼런스(scratch/ref_lightning/ref1~3.png · 3인칭 인게임)의 관찰을 그대로 손으로 옮긴다:
  · 가늘고 긴 청록 검 — 밝은 청백 심 + 청록 가장자리 (얇은 레이피어/롱소드)
  · 금색 십자 코등이 (단순 가로대 — 날개 아님) + 자루(청록 감김) + 금 물미
  · 작은 청록 번개 — 칼끝 근처 2가닥 + 코등이 근처 1가닥 (프레임마다 번쩍이는 애니)

★이 파일은 tools/respack/weapons.py 의 복셀 스펙(_voxelize·WEAPON_SPEC)을 **거치지 않는다.**
  cuboid 를 손으로 배치한다. 기존 45+8 무기 자산은 무접촉 (신규 키 ref_blade 만 굽는다).
  결정론: 난수 없음. 두 번 돌리면 바이트가 같다.

산출 (전부 신규):
  resourcepack/assets/honcheon/items/weapon/ref_blade.json          아이템 정의
  resourcepack/assets/honcheon/models/item/weapon/ref_blade.json    모델 (손 배치 cuboid)
  resourcepack/assets/honcheon/textures/item/weapon/ref_blade.png   32x32 검신/코등이/자루 아틀라스
  resourcepack/assets/honcheon/textures/item/weapon/ref_bolt.png    32x256 번개 애니 스트립(8프레임)
  resourcepack/assets/honcheon/textures/item/weapon/ref_bolt.png.mcmeta  frametime 2

인게임 조회 (팩만으로 — jar 변경·기존 자산 무접촉):
  /give Lindydone golden_sword[minecraft:item_model="honcheon:weapon/ref_blade"]
  (1.21.4+ item_model 컴포넌트가 이 아이템 정의를 가리킨다 → 클라이언트가 ref_blade 를 그린다)
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from respack.core import (  # noqa: E402
    ITEM_DEF_DIR, ITEM_MODEL_DIR, ITEM_TEX_DIR, _display, write_json, write_png,
)

T = (0, 0, 0, 0)

# ─── 색 램프 (레퍼런스 청록 톤) ─────────────────────────────────────────────
CORE_HI = (224, 248, 252, 255)   # 검신 심 — 밝은 청백 (인이 빛을 되쏘는 선)
CORE_MID = (150, 224, 236, 255)  # 검신 심 몸 — 청 (broad face 가 읽는 색)
CORE_DIM = (86, 176, 200, 255)   # 검신 심 그늘
EDGE_HI = (72, 206, 194, 255)    # 날 가장자리 — 청록 결 밝음
EDGE_MID = (36, 158, 158, 255)   # 날 가장자리 몸 (심보다 어둡다 — 볼록 단면이 읽힌다)
EDGE_DIM = (20, 110, 122, 255)   # 날 가장자리 그늘
GOLD_HI = (248, 214, 120, 255)   # 코등이/물미 금 — 광
GOLD_MID = (208, 168, 74, 255)   # 금 몸
GOLD_DIM = (150, 112, 44, 255)   # 금 그늘
GRIP_HI = (46, 100, 108, 255)    # 자루 감김 — 청록 어두움 마루
GRIP_MID = (28, 66, 74, 255)     # 감김 몸
GRIP_DIM = (15, 38, 46, 255)     # 감김 골 (감은 결)

# 번개 스트립 팔레트 (4색 — 린트 MIN_COLORS 충족 + 청록 심/후광)
BOLT_HI = (232, 252, 255, 255)   # 심 — 흰-청
BOLT_MID = (120, 234, 236, 255)  # 청록 몸
BOLT_DIM = (48, 176, 200, 255)   # 후광
BOLT_DEEP = (26, 118, 150, 255)  # 바깥 후광

# ─── 텍스처 32x32: 4열(심·날·금·자루) × 3단(hi/mid/dim) ────────────────────
TW = 32
COLS = [("core", CORE_HI, CORE_MID, CORE_DIM),
        ("edge", EDGE_HI, EDGE_MID, EDGE_DIM),
        ("gold", GOLD_HI, GOLD_MID, GOLD_DIM),
        ("grip", GRIP_HI, GRIP_MID, GRIP_DIM)]
BANDS = [(0, 10), (10, 21), (21, 32)]   # hi / mid / dim 세로 구간(px)


def blade_texture():
    rows = [[T] * TW for _ in range(TW)]
    for ci, (region, hi, mid, dim) in enumerate(COLS):
        x0, x1 = ci * 8, ci * 8 + 8
        for shade, (y0, y1) in zip((hi, mid, dim), BANDS):
            for y in range(y0, y1):
                for x in range(x0, x1):
                    rows[y][x] = shade
        # 자루는 감은 결 — mid 밴드에 골(dim) 가로줄 2px 간격 (감김이 읽힌다)
        if region == "grip":
            for y in range(BANDS[1][0], BANDS[1][1]):
                if (y - BANDS[1][0]) % 3 == 2:
                    for x in range(x0, x1):
                        rows[y][x] = dim
    return rows


# uv(0..16) = px * 0.5. 각 면이 샘플하는 작은 uv 사각(그 단색 블록 안).
SHADE_UV = {}
for _ci, (_region, _hi, _mid, _dim) in enumerate(COLS):
    _ux = _ci * 4.0            # 8px = 4uv
    SHADE_UV[f"{_region}_hi"] = (_ux + 0.5, 0.5, _ux + 3.5, 4.0)
    SHADE_UV[f"{_region}_mid"] = (_ux + 0.5, 5.5, _ux + 3.5, 9.5)
    SHADE_UV[f"{_region}_dim"] = (_ux + 0.5, 11.0, _ux + 3.5, 15.5)


def _faces(region, tex="#0"):
    """윗면 밝게·아랫면 어둡게·옆면 몸 (좌상 광원). broad face(N/S) = region 몸색 = 계열을 말한다."""
    hi, mid, dim = SHADE_UV[f"{region}_hi"], SHADE_UV[f"{region}_mid"], SHADE_UV[f"{region}_dim"]
    return {
        "up": {"texture": tex, "uv": list(hi)},
        "down": {"texture": tex, "uv": list(dim)},
        "north": {"texture": tex, "uv": list(mid)},
        "south": {"texture": tex, "uv": list(mid)},
        "east": {"texture": tex, "uv": list(mid)},
        "west": {"texture": tex, "uv": list(mid)},
    }


def cuboid(box, region):
    return {"from": [round(v, 3) for v in box[:3]],
            "to": [round(v, 3) for v in box[3:]],
            "faces": _faces(region)}


def bolt_quad(box):
    """번개 평면 스프라이트 — 얇은 판(Z 0.1). broad face(N/S)만 스트립(#1) 전 프레임 표시."""
    full = [0.0, 0.0, 16.0, 16.0]
    return {"from": [round(v, 3) for v in box[:3]],
            "to": [round(v, 3) for v in box[3:]],
            "faces": {
                "north": {"texture": "#1", "uv": full},
                "south": {"texture": "#1", "uv": [16.0, 0.0, 0.0, 16.0]},
            }}


# ═══ 기하 (모델 공간: X=길이·칼끝 +X, Y=폭·중심 8, Z=두께·중심 8) ══════════════
# 레퍼런스 비례: 가늘고 길다. 칼끝 X=25, 코등이 X~1.2, 자루 -3.4..0.5, 물미 -4.4.
# BODY_SPEC = [(box, region)…] — build() 와 몽타주(v23_drive) 가 같은 진실을 읽는다.
BODY_SPEC = [
    # ── 검신: 3단 (본체 → 중간 → 칼끝) — 단마다 단면을 줄여 볼록 테이퍼(점) 흉내
    ((2, 7.35, 7.5, 19, 8.65, 8.5), "core"),        # 본체 심 (청백)
    ((2, 8.65, 7.75, 19, 9.0, 8.25), "edge"),        # 본체 위 날 결 (청록)
    ((2, 7.0, 7.75, 19, 7.35, 8.25), "edge"),        # 본체 아래 날 결
    ((19, 7.55, 7.6, 22.5, 8.45, 8.4), "core"),      # 중간 심
    ((19, 8.45, 7.8, 22.5, 8.75, 8.2), "edge"),
    ((19, 7.25, 7.8, 22.5, 7.55, 8.2), "edge"),
    ((22.5, 7.7, 7.7, 25, 8.3, 8.3), "core"),        # 칼끝 (심만, 좁게 — 점)
    # ── 코등이: 금색 십자 가로대 (단순 — 날개 아님). Y 로 검신 폭 밖까지 뻗는다 (얇게 — 레퍼런스 슬림)
    ((0.6, 4.8, 7.25, 1.9, 11.2, 8.75), "gold"),     # 가로대 본체
    ((0.8, 4.4, 7.45, 1.7, 4.8, 8.55), "gold"),      # 아래 끝 마무리
    ((0.8, 11.2, 7.45, 1.7, 11.6, 8.55), "gold"),    # 위 끝 마무리
    # ── 자루: 청록 감김 + 금 물미
    ((-3.4, 7.25, 7.25, 0.5, 8.75, 8.75), "grip"),   # 감김
    ((-4.4, 7.0, 7.0, -3.4, 9.0, 9.0), "gold"),      # 물미(금)
]
# 작은 번개 (평면 스프라이트 + 애니): 칼끝 2가닥 + 코등이 1가닥 (레퍼런스처럼 작고 얇게)
BOLTS_SPEC = [
    (19.5, 9.2, 7.95, 22.5, 12.8, 8.05),             # 칼끝 위
    (21.5, 5.3, 7.95, 24.8, 8.8, 8.05),              # 칼끝 아래(끝단)
    (2.2, 9.0, 7.95, 5.0, 12.4, 8.05),               # 코등이 근처
]

BODY = [cuboid(box, region) for box, region in BODY_SPEC]
BOLTS = [bolt_quad(box) for box in BOLTS_SPEC]
ELEMENTS = BODY + BOLTS


def _span(elems):
    xs = [c for e in elems for c in (e["from"][0], e["to"][0])]
    ys = [c for e in elems for c in (e["from"][1], e["to"][1])]
    zs = [c for e in elems for c in (e["from"][2], e["to"][2])]
    return max(max(xs) - min(xs), max(ys) - min(ys), max(zs) - min(zs))


def build():
    ITEM_TEX_DIR.mkdir(parents=True, exist_ok=True)
    # 텍스처
    write_png(ITEM_TEX_DIR / "weapon" / "ref_blade.png", blade_texture())
    write_png(ITEM_TEX_DIR / "weapon" / "ref_bolt.png", bolt_strip())
    (ITEM_TEX_DIR / "weapon" / "ref_bolt.png.mcmeta").write_text(
        json.dumps({"animation": {"frametime": 2, "frames": list(range(8))}},
                   ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    # 모델
    frame = _span(ELEMENTS)
    body = _span(BODY)
    model = {
        "textures": {
            "0": "honcheon:item/weapon/ref_blade",
            "1": "honcheon:item/weapon/ref_bolt",
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
    print(f"ref_blade: elements {len(ELEMENTS)} (body {len(BODY)} + bolt {len(BOLTS)}) · "
          f"frame span {frame:.1f} · body span {body:.1f}")


# ─── 번개 애니 스트립 32x256 (8프레임 × 32) ──────────────────────────────────
def _seg(frame, p0, p1):
    """두 점을 잇는 얇은 심(core) 지그재그 선 — 1px. 후광은 스트립 조립부가 팽창으로 두른다."""
    x0, y0 = p0
    x1, y1 = p1
    steps = max(abs(x1 - x0), abs(y1 - y0), 1)
    for i in range(steps + 1):
        t = i / steps
        x = round(x0 + (x1 - x0) * t)
        y = round(y0 + (y1 - y0) * t)
        if 0 <= x < TW and 0 <= y < 32:
            frame[y][x] = BOLT_HI


def _dilate(frame, src, dst):
    """src 색에 4방 인접한 빈 픽셀을 dst 로 두른다 (후광 한 겹)."""
    add = []
    for y in range(32):
        for x in range(TW):
            if frame[y][x] is not T:
                continue
            if any(0 <= x + dx < TW and 0 <= y + dy < 32 and frame[y + dy][x + dx] == src
                   for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                add.append((x, y))
    for x, y in add:
        frame[y][x] = dst


# 프레임별 지그재그 경로 (칼끝/코등이 어디에 붙든 같은 스트립을 쓴다 — 작고 얇다).
# 결정론: 손으로 박은 좌표. 몇 프레임은 성글게(번쩍임), 포크 1가닥.
_FRAMES = [
    [[(16, 2), (12, 10), (18, 17), (13, 26)], [(18, 17), (24, 21)]],
    [[(15, 3), (19, 11), (12, 18), (17, 27)], [(12, 18), (7, 22)]],
    [[(17, 2), (11, 9), (19, 16), (12, 25)]],
    [[(14, 4), (20, 12), (13, 20), (18, 28)], [(20, 12), (26, 15)]],
    [[(16, 3), (10, 11), (17, 18), (11, 26)]],
    [[(18, 2), (13, 10), (20, 18), (14, 27)], [(13, 10), (8, 13)]],
    [[(15, 4), (18, 12), (12, 19), (16, 28)]],
    [[(16, 3), (12, 11), (19, 19), (13, 27)], [(19, 19), (24, 23)]],
]


def bolt_strip():
    rows = [[T] * TW for _ in range(32 * 8)]
    for fi, polylines in enumerate(_FRAMES):
        frame = [[T] * TW for _ in range(32)]
        for poly in polylines:
            for a, b in zip(poly, poly[1:]):
                _seg(frame, a, b)
        _dilate(frame, BOLT_HI, BOLT_MID)     # 심 둘레 청록 후광
        _dilate(frame, BOLT_MID, BOLT_DIM)    # 그 밖 한 겹
        if fi % 2 == 0:                       # 짝수 프레임만 바깥 번짐 한 겹 → 밝기 번쩍임(애니)
            _dilate(frame, BOLT_DIM, BOLT_DEEP)
        for y in range(32):
            rows[fi * 32 + y] = frame[y]
    return rows


if __name__ == "__main__":
    build()
