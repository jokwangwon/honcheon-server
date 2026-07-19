"""V2-H 한옥 건축 어휘 3D — 형태(모델)의 층 (pack_upgrade_v2_3d.md §2 V2-H).

【무엇을 굽나】 assets/minecraft/models/block/*.json 20장:
  · 기와 프로파일 14장 — deepslate_tiles·bricks(+cracked) 풀블록 4 / 계단 straight·inner·outer
    × 두 결 6 / 반블록 bottom·top × 두 결 4
  · 담장 기와 갓 2장 — deepslate_tile_wall post·side (side_tall 은 제외 — 아래 한계)
  · 세살 격자창 3장 — glass_pane post·side·side_alt (noside 2종은 바닐라 유지 — 아래 참조)
  · 죽렴 1장 — bamboo_planks (대쪽 돋움)

【왜 blockstates 를 안 건드리나 — 이 트랙의 안전 장치】
  계단은 facing 4 × half 2 × shape 5 = 40 variant 다. blockstate 를 다시 쓰면 하나만 빠져도
  그 방향·그 모양의 지붕이 깨진다. 바닐라 blockstate 는 모델 셋(straight/inner/outer)을
  y·x 회전 + uvlock 으로 돌려 40 을 만든다 — 그러므로 **모델 경로만 재정의하면 40 variant
  전부가 바닐라 회전표를 그대로 탄다.** 반블록 3 variant(bottom/top/double), 담장 multipart,
  판유리 연결 multipart 도 같은 원리다: 빠질 variant 가 구조적으로 없다.
  (double 반블록은 바닐라가 block/deepslate_tiles 를 가리키므로 풀블록 재정의가 곧 그 답이다.)

【불가침 — 시각과 물리의 경계】
  ㄱ. 히트박스·충돌·점유 판정은 블록 타입이 정한다 (서버·클라이언트 공통) — 여기서 바꾸는 것은
      **눈에 보이는 것뿐**이다. 보행·화살·설치 판정 영향 0.
  ㄴ. 컬링 계약: 바닐라가 cullface 를 붙이던 '가득 찬 면'(계단 밑판·뒷판, 반블록 밑판, 풀블록
      여섯 면)은 재정의 후에도 같은 자리를 가득 채운다. 이웃 블록이 그 면을 믿고 제 면을
      지우므로(occlusion), 안 채우면 지붕에 엑스레이 구멍이 뚫린다.
  ㄷ. 원소 회전은 한 축 · {±22.5, ±45} · 좌표 -16..32 — _el() 이 컴파일 시 검증한다
      (weapons.py 의 _bx 와 같은 눈).
  ㄹ. 이웃 칸 침범은 처마·수키와 끝 ≤ 2px (경사판 처마 끝 x=-0.3 · 수키와 끝 x≈-2.05).
      지붕에서 그 자리는 한 단 아래·앞 — 거의 언제나 공기다. 막힌 자리면 이웃 불투명면이 가린다.

【기와 프로파일 — 굴곡을 어떻게 만들었나】
  · 계단(지붕의 몸): 바닐라 두 상자(밑판+뒷단)는 실루엣·컬링째 그대로 두고, 층계 모서리
    (0,8)→(8,16) 을 45° 경사판(빗변 8√2)으로 덮는다. 처마 쪽으로 1.4 연장해 회전 후
    (−0.3, 6.3) 까지 내려온다 — 계단이 아니라 **물매**로 읽힌다. 그 위에 수키와 골 3줄
    (주기 8 — 텍스처 너울(ROOF_SWELL)의 마루 x=0·8 과 같은 자리·같은 주기라 랩이 맞는다)을
    얹는다. inner 는 두 경사판이 만나 골(합각 골), outer 는 추녀마루가 된다.
  · 반블록(처마·용마루 받침·담 갓): 윗면에 수키와 3줄 (y 8→8.8). top 반블록은 **아랫면에**
    3줄 (y 7.2→8) — 처마 밑에서 올려다보는 기와 끝이다.
  · 풀블록(용마루·적새·벽 박이): 윗면 y 16→16.8 수키와 3줄. 위에 블록이 얹히면 그 불투명
    부피 속에 숨는다 (보이지 않을 뿐 무해 — 시선이 닿기 전에 이웃 면이 먼저 그려진다).
  · tiles ↔ bricks: 텍스처가 서로의 90도 회전판이듯 수키와도 직각이다 — tiles 는 골이 z 로,
    bricks 는 x 로 흐른다 (CheonghaBuilder.crossGrain 의 자재 선택 축과 동일. 조성기가
    남북 경사면=TILE(facing N/S → y회전 → 골이 물매 방향), 동서 경사면=BRICK 을 고르므로
    수키와는 어느 면에서나 물매를 따라 흘러내린다).

【세살 격자창 — 살이 실제로 돋는다】
  판유리의 유리판(2px)은 바닐라 자리 그대로 두고(그림 정렬 불변 — glass.png 의 그린 살과
  같은 u 자리), 목재 살대를 **관통 상자**로 박는다: 세로살 2대 + 가로살(띠장) 2대가 유리
  양쪽으로 0.75 씩 돋는다. 중앙 기둥(post)은 2.8각 문설주 목재 상자가 된다. 살대 텍스처는
  glass_pane_top(창살 마구리 목재) — 새 PNG 0장.
  · noside/noside_alt 2종은 재정의하지 않는다: 그 2px 스텁 면은 굵어진 문설주 상자 **속**에
    들어가 보이지 않고, 연결 안 된 끝은 문설주 목재로 읽힌다 (더 옳은 그림).

【한계 — 기술이 막은 것 (지어내지 않는다)】
  · dark_oak_hanging_sign(현판)·chest·decorated_pot 은 **블록엔티티** — 모델 JSON 이 아니라
    하드코딩 렌더러가 그린다. 팩으로는 entity/ 텍스처 교체까지가 전부다 (이미 징발 등록분).
    여기서 형태를 얹을 수 없다.
  · deepslate_tile_wall_side_tall 은 재정의하지 않는다: tall 은 위가 막힐 때 나오는 변종이라
    갓을 얹어도 위 블록 속에 숨는다 — 죽은 원소만 는다.
  · MC 원소는 직육면체뿐 — 추녀 곡선(앙곡·안허리곡)은 45° 평면 근사다. outer 계단의 낮은
    사분면(합각 아래 평활면)은 평면으로 남는다 (잠정 — 인게임 인상으로 재판정).

【잠정 표기】 수키와 주기 8(=텍스처 너울 주기)·폭 1.5·돋움 0.8, 처마 내림 1.3, 경사 45° 는
  전부 오프라인 등축 렌더로 고른 값이다 — 실존 한옥의 수키와 곡률·처마 앙곡을 정밀 재현한
  치수가 아니다 (세부는 인게임 확정 대상).
"""
from .core import PACK, write_json

BLOCK_MODEL_DIR = PACK / "assets" / "minecraft" / "models" / "block"

TILE = "minecraft:block/deepslate_tiles"
TILE_CRACKED = "minecraft:block/cracked_deepslate_tiles"
BRICK = "minecraft:block/deepslate_bricks"
BRICK_CRACKED = "minecraft:block/cracked_deepslate_bricks"
PANE = "minecraft:block/glass"
PANE_EDGE = "minecraft:block/glass_pane_top"
BAMBOO = "minecraft:block/bamboo_planks"

HYP = 11.314        # 8·√2 — 45° 경사판 빗변 (층계 모서리 (0,8)→(8,16) 를 덮는다)
EAVE = -1.4         # 경사판의 처마 연장 (회전 후 끝점 (−0.3, 6.3))
CREST_HALF = 0.75   # 수키와 반폭 — 마루 중심 ±0.75 = 폭 1.5
CREST_RISE = 0.8    # 수키와 돋움
CRESTS = (0.0, 8.0, 16.0)   # 마루 자리 — 텍스처 너울 ROOF_SWELL 의 마루(x%8==0)와 같은 격자.
                            # 0·16 은 반쪽씩이라 이웃 장과 합쳐 온전한 한 줄이 된다 (랩 안전).

# 바닐라 block/stairs 의 display 절 (1.21.11 client jar 원문) — 손·GUI 에서 계단 아이템이
# 바닐라와 같은 자리에 놓인다. 짐작하지 않고 옮겨 적었다.
_STAIR_DISPLAY = {
    "gui": {"rotation": [30, 135, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
    "head": {"rotation": [0, -90, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
    "thirdperson_lefthand": {"rotation": [75, -135, 0], "translation": [0, 2.5, 0],
                             "scale": [0.375, 0.375, 0.375]},
}


def _n(v):
    """좌표 정규화 — 3자리 반올림, 정수는 정수로 (두 번 구워도 같은 바이트: 결정론)."""
    r = round(float(v), 3)
    return int(r) if r == int(r) else r


def _f(uv, tex="#0", cull=None):
    face = {"uv": [_n(v) for v in uv], "texture": tex}
    if cull:
        face["cullface"] = cull
    return face


def _el(frm, to, faces, rot=None):
    """상자 하나 — MC 한계를 컴파일 시 검증 (넘으면 클라이언트가 모델을 조용히 버린다)."""
    for i in range(3):
        if frm[i] > to[i]:
            raise ValueError(f"from > to: {frm} → {to}")
    for v in (*frm, *to):
        if not -16 <= v <= 32:
            raise ValueError(f"원소가 [-16,32] 를 벗어났다: {frm} → {to}")
    e = {"from": [_n(v) for v in frm], "to": [_n(v) for v in to]}
    if rot:
        axis, ang, org = rot
        if ang not in (-45, -22.5, 22.5, 45):
            raise ValueError(f"허용되지 않는 원소 회전각 {ang} (MC 는 0·±22.5·±45 만 받는다)")
        e["rotation"] = {"origin": [_n(v) for v in org], "axis": axis, "angle": ang}
    e["faces"] = faces
    return e


# ─── 수키와 — 평평한 면(풀블록 윗면·반블록) 위의 골 3줄 ─────────────────────────
def _crests(y0, y1, along):
    """along='z': 골이 z 로 흐른다 (tiles — 마루가 x=0·8·16). along='x': 직각 (bricks)."""
    out = []
    for c in CRESTS:
        a, b = max(0.0, c - CREST_HALF), min(16.0, c + CREST_HALF)
        uv_side = [0, 15.2, 16, 16]          # 옆면 — 텍스처 아랫단의 얇은 띠 (색 연속용)
        if along == "z":
            out.append(_el([a, y0, 0], [b, y1, 16], {
                "up": _f([a, 0, b, 16]),      # 마루 자리의 세로 띠 — 너울 마루 위에 앉는다
                "down": _f([a, 0, b, 16]),
                "west": _f(uv_side), "east": _f(uv_side),
                "north": _f([a, 15.2, b, 16]), "south": _f([a, 15.2, b, 16]),
            }))
        else:
            out.append(_el([0, y0, a], [16, y1, b], {
                "up": _f([0, a, 16, b]),      # bricks 는 마루가 가로 띠 (90도 회전판)
                "down": _f([0, a, 16, b]),
                "north": _f(uv_side), "south": _f(uv_side),
                "west": _f([a, 15.2, b, 16]), "east": _f([a, 15.2, b, 16]),
            }))
    return out


# ─── 45° 기와면 — 계단의 층계 모서리를 물매로 덮는다 ────────────────────────────
def _slope_x(z0, z1):
    """−X 로 흘러내리는 기와면 (폭 z0..z1). 원점 [0,8,0] z축 +45° — X 방향이 (cos45,sin45)
    로 서서 빗변이 (0,8)→(8,16) 층계 모서리에 정확히 얹힌다 (처마 쪽 1.4 연장)."""
    rot = ("z", 45, [0, 8, 0])
    out = [_el([EAVE, 7, z0], [HYP, 8, z1], {
        "up": _f([0, z0, 16, z1]),           # 기와면 — uvlock 변형에서 세계 방향으로 잠긴다
        "down": _f([0, z0, 16, z1]),         # 처마 밑 — 올려다보면 보인다
        "north": _f([0, 7, 16, 9]), "south": _f([0, 7, 16, 9]),
        "west": _f([z0, 6, z1, 8]),          # 처마 끝단 (두께 1)
    }, rot)]
    for c in CRESTS:
        a, b = max(z0, c - CREST_HALF), min(z1, c + CREST_HALF)
        if b - a < 0.4:
            continue
        out.append(_el([-1.7, 8, a], [10.6, 8.9, b], {   # 수키와 — 물매를 따라 흘러내린다
            "up": _f([0, a, 16, b]),
            "north": _f([0, 15.1, 16, 16]), "south": _f([0, 15.1, 16, 16]),
            "west": _f([a, 15.1, b, 16]), "east": _f([a, 15.1, b, 16]),
        }, rot))
    return out


def _slope_z(x0, x1):
    """−Z 로 흘러내리는 기와면 (폭 x0..x1) — _slope_x 의 직각판. x축 −45° 가 +Z 를
    (0, sin45, cos45) 로 세운다 (검산: (0,0,1)→(0,+0.707,+0.707))."""
    rot = ("x", -45, [0, 8, 0])
    out = [_el([x0, 7, EAVE], [x1, 8, HYP], {
        "up": _f([x0, 0, x1, 16]),
        "down": _f([x0, 0, x1, 16]),
        "west": _f([0, 7, 16, 9]), "east": _f([0, 7, 16, 9]),
        "north": _f([x0, 6, x1, 8]),         # 처마 끝단
    }, rot)]
    for c in CRESTS:
        a, b = max(x0, c - CREST_HALF), min(x1, c + CREST_HALF)
        if b - a < 0.4:
            continue
        out.append(_el([a, 8, -1.7], [b, 8.9, 10.6], {
            "up": _f([a, 0, b, 16]),
            "west": _f([0, 15.1, 16, 16]), "east": _f([0, 15.1, 16, 16]),
            "north": _f([a, 15.1, b, 16]), "south": _f([a, 15.1, b, 16]),
        }, rot))
    return out


# ─── 모델 조립 ────────────────────────────────────────────────────────────────
def _model(tex, elems, display=None, ao=None):
    m = {"parent": "minecraft:block/block", "textures": {"particle": tex, "0": tex},
         "elements": elems}
    if display:
        m["display"] = display
    if ao is not None:
        m["ambientocclusion"] = ao
    return m


def _kiwa_cube(tex, along):
    """풀블록 — 바닐라 큐브(여섯 면 cullface 그대로 = 컬링 계약 불변) + 윗면 수키와."""
    cube = _el([0, 0, 0], [16, 16, 16], {
        f: _f([0, 0, 16, 16], cull=f) for f in ("down", "up", "north", "south", "west", "east")
    })
    return _model(tex, [cube] + _crests(16, 16 + CREST_RISE, along))


def _kiwa_slab(tex, along, top=False):
    if not top:
        base = _el([0, 0, 0], [16, 8, 16], {
            "down": _f([0, 0, 16, 16], cull="down"), "up": _f([0, 0, 16, 16]),
            "north": _f([0, 8, 16, 16], cull="north"), "south": _f([0, 8, 16, 16], cull="south"),
            "west": _f([0, 8, 16, 16], cull="west"), "east": _f([0, 8, 16, 16], cull="east"),
        })
        return _model(tex, [base] + _crests(8, 8 + CREST_RISE, along))
    base = _el([0, 8, 0], [16, 16, 16], {
        "down": _f([0, 0, 16, 16]), "up": _f([0, 0, 16, 16], cull="up"),
        "north": _f([0, 0, 16, 8], cull="north"), "south": _f([0, 0, 16, 8], cull="south"),
        "west": _f([0, 0, 16, 8], cull="west"), "east": _f([0, 0, 16, 8], cull="east"),
    })
    # top 반블록 = 매달린 처마 — 수키와가 **아랫면에** 돋는다 (올려다보는 기와 끝)
    return _model(tex, [base] + _crests(8 - CREST_RISE, 8, along))


def _kiwa_stairs(tex, shape):
    """계단 — 바닐라 실루엣(밑판+뒷단, uv·cullface 를 1.21.11 원문 그대로) 위에 기와면."""
    elems = [_el([0, 0, 0], [16, 8, 16], {                       # 밑판 (바닐라 원문)
        "down": _f([0, 0, 16, 16], "#0", "down"), "up": _f([0, 0, 16, 16]),
        "north": _f([0, 8, 16, 16], "#0", "north"), "south": _f([0, 8, 16, 16], "#0", "south"),
        "west": _f([0, 8, 16, 16], "#0", "west"), "east": _f([0, 8, 16, 16], "#0", "east"),
    })]
    if shape in ("straight", "inner"):
        elems.append(_el([8, 8, 0], [16, 16, 16], {              # 뒷단 (바닐라 원문)
            "up": _f([8, 0, 16, 16], "#0", "up"),
            "north": _f([0, 0, 8, 8], "#0", "north"), "south": _f([8, 0, 16, 8], "#0", "south"),
            "west": _f([0, 0, 16, 8]), "east": _f([0, 0, 16, 8], "#0", "east"),
        }))
    if shape == "straight":
        elems += _slope_x(0, 16)
    elif shape == "inner":
        elems.append(_el([0, 8, 8], [8, 16, 16], {               # 안쪽 날개 (바닐라 원문)
            "up": _f([0, 8, 8, 16], "#0", "up"),
            "north": _f([8, 0, 16, 8]), "south": _f([0, 0, 8, 8], "#0", "south"),
            "west": _f([8, 0, 16, 8], "#0", "west"),
        }))
        elems += _slope_x(0, 8) + _slope_z(0, 8)                 # 두 물매가 만나 골이 된다
    else:                                                        # outer — 추녀 모서리
        elems.append(_el([8, 8, 8], [16, 16, 16], {              # 모퉁이 단 (바닐라 원문)
            "up": _f([8, 8, 16, 16], "#0", "up"),
            "north": _f([0, 0, 8, 8]), "south": _f([8, 0, 16, 8], "#0", "south"),
            "west": _f([8, 0, 16, 8]), "east": _f([0, 0, 8, 8], "#0", "east"),
        }))
        elems += _slope_x(8, 16) + _slope_z(8, 16)               # 두 물매가 만나 추녀마루가 된다
    return _model(tex, elems, display=_STAIR_DISPLAY if shape == "straight" else None)


# ─── 담장 기와 갓 — 바닐라 기둥·팔 위에 갓돌(개판+마루) ──────────────────────────
def _wall_post():
    elems = [
        _el([4, 0, 4], [12, 16, 12], {
            "down": _f([4, 4, 12, 12], cull="down"), "up": _f([4, 4, 12, 12], cull="up"),
            "north": _f([4, 0, 12, 16]), "south": _f([4, 0, 12, 16]),
            "west": _f([4, 0, 12, 16]), "east": _f([4, 0, 12, 16]),
        }),
        _el([3, 16, 3], [13, 17, 13], {      # 갓 개판 — 기둥보다 1px 내밀어 그늘을 만든다
            "down": _f([3, 3, 13, 13]), "up": _f([3, 3, 13, 13]),
            "north": _f([3, 0, 13, 1]), "south": _f([3, 0, 13, 1]),
            "west": _f([3, 0, 13, 1]), "east": _f([3, 0, 13, 1]),
        }),
        _el([5.5, 17, 5.5], [10.5, 17.8, 10.5], {   # 갓 마루
            "up": _f([5.5, 5.5, 10.5, 10.5]),
            "north": _f([5.5, 15.2, 10.5, 16]), "south": _f([5.5, 15.2, 10.5, 16]),
            "west": _f([5.5, 15.2, 10.5, 16]), "east": _f([5.5, 15.2, 10.5, 16]),
        }),
    ]
    return _model(TILE, elems)


def _wall_side():
    elems = [
        _el([5, 0, 0], [11, 14, 8], {        # 바닐라 낮은 팔 (h14)
            "down": _f([5, 0, 11, 8], cull="down"), "up": _f([5, 0, 11, 8]),
            "north": _f([5, 2, 11, 16], cull="north"),
            "west": _f([0, 2, 8, 16]), "east": _f([0, 2, 8, 16]),
        }),
        _el([4, 14, 0], [12, 15, 8], {       # 갓 개판
            "down": _f([4, 0, 12, 8]), "up": _f([4, 0, 12, 8]),
            "north": _f([4, 0, 12, 1], cull="north"),
            "west": _f([0, 0, 8, 1]), "east": _f([0, 0, 8, 1]),
        }),
        _el([6.5, 15, 0], [9.5, 15.8, 8], {  # 갓 마루 — 담 위를 한 줄로 달린다
            "up": _f([6.5, 0, 9.5, 8]),
            "north": _f([6.5, 15.2, 9.5, 16], cull="north"),
            "west": _f([0, 15.2, 8, 16]), "east": _f([0, 15.2, 8, 16]),
        }),
    ]
    return _model(TILE, elems)


# ─── 세살 격자창 — 살대는 관통 상자 (유리판·그림 정렬은 바닐라 자리 그대로) ─────────
def _mullion(z0, z1):
    """세로살 — 유리(x 7..9)를 관통해 양쪽 0.75 씩 돋는다."""
    return _el([6.5, 0, z0], [9.5, 16, z1], {
        "west": _f([7, 0, 8, 16], "#edge"), "east": _f([7, 0, 8, 16], "#edge"),
        "north": _f([6, 0, 9, 16], "#edge"), "south": _f([6, 0, 9, 16], "#edge"),
        "up": _f([6.5, 7, 9.5, 8], "#edge"), "down": _f([6.5, 7, 9.5, 8], "#edge"),
    })


def _rail(y0, y1, z0, z1):
    """가로살(띠장) — 판 길이를 가로지른다."""
    return _el([6.5, y0, z0], [9.5, y1, z1], {
        "west": _f([z0, 7, z1, 8], "#edge"), "east": _f([z0, 7, z1, 8], "#edge"),
        "up": _f([6.5, z0, 9.5, z1], "#edge"), "down": _f([6.5, z0, 9.5, z1], "#edge"),
        "north": _f([6.5, y0, 9.5, y1], "#edge"), "south": _f([6.5, y0, 9.5, y1], "#edge"),
    })


def _pane_model(elems):
    return {"ambientocclusion": False,
            "textures": {"particle": PANE, "pane": PANE, "edge": PANE_EDGE},
            "elements": elems}


def _pane_post():
    """중앙 기둥 → 문설주 — 2.8각 목재 상자 (noside 스텁 면이 이 속에 숨는다)."""
    return _pane_model([_el([6.6, 0, 6.6], [9.4, 16, 9.4], {
        "down": _f([7, 7, 9, 9], "#edge"), "up": _f([7, 7, 9, 9], "#edge"),
        "north": _f([7, 0, 9, 16], "#edge"), "south": _f([7, 0, 9, 16], "#edge"),
        "west": _f([7, 0, 9, 16], "#edge"), "east": _f([7, 0, 9, 16], "#edge"),
    })])


def _pane_side(alt=False):
    """연결 판 — 유리판(바닐라 원문 uv: 그린 세살과 u 정렬 유지) + 세로살 2 + 띠장 2.
    살대 자리는 glass.png 의 그린 세로살이 이 판에 비치는 u(9·12 / 3·6)의 z 사영이다."""
    if not alt:
        glass = _el([7, 0, 0], [9, 16, 7], {
            "down": _f([7, 0, 9, 7], "#edge"), "up": _f([7, 0, 9, 7], "#edge"),
            "north": _f([7, 0, 9, 16], "#edge", "north"),
            "west": _f([16, 0, 9, 16], "#pane"), "east": _f([9, 0, 16, 16], "#pane"),
        })
        bars = [_mullion(0, 1), _mullion(3, 4)]          # u 9→z 0 · u 12→z 3
        rails = [_rail(5, 6, 0, 7), _rail(11, 12, 0, 7)]  # 그린 가로살 y 4·10 의 세계 y
    else:
        glass = _el([7, 0, 9], [9, 16, 16], {
            "down": _f([7, 0, 9, 7], "#edge"), "up": _f([7, 0, 9, 7], "#edge"),
            "south": _f([7, 0, 9, 16], "#edge", "south"),
            "west": _f([7, 0, 0, 16], "#pane"), "east": _f([0, 0, 7, 16], "#pane"),
        })
        bars = [_mullion(12, 13), _mullion(15, 16)]      # u 3→z 12 · u 6→z 15
        rails = [_rail(5, 6, 9, 16), _rail(11, 12, 9, 16)]
    return _pane_model([glass] + bars + rails)


# ─── 죽렴 — 대쪽이 실제로 돋는다 (텍스처 쪽 폭 4·마루 x%4∈{0,1} 과 같은 격자) ───
def _bamboo_planks():
    cube = _el([0, 0, 0], [16, 16, 16], {
        f: _f([0, 0, 16, 16], cull=f) for f in ("down", "up", "north", "south", "west", "east")
    })
    slats = [_el([x, 16, 0], [x + 1.5, 16.6, 16], {
        "up": _f([x, 0, x + 1.5, 16]),
        "west": _f([0, 15.4, 16, 16]), "east": _f([0, 15.4, 16, 16]),
        "north": _f([x, 15.4, x + 1.5, 16]), "south": _f([x, 15.4, x + 1.5, 16]),
    }) for x in (0, 4, 8, 12)]
    return _model(BAMBOO, [cube] + slats)


# ─── 등재표 — 파일명 = 바닐라 모델 경로 (blockstate 가 이 이름으로 부른다) ─────────
def _catalog():
    return {
        # 기와 — 풀블록 (double 반블록·용마루·적새·벽 박이)
        "deepslate_tiles": _kiwa_cube(TILE, "z"),
        "cracked_deepslate_tiles": _kiwa_cube(TILE_CRACKED, "z"),
        "deepslate_bricks": _kiwa_cube(BRICK, "x"),
        "cracked_deepslate_bricks": _kiwa_cube(BRICK_CRACKED, "x"),
        # 기와 — 계단 (지붕의 몸. 남북 경사면=TILE · 동서 경사면=BRICK — crossGrain)
        "deepslate_tile_stairs": _kiwa_stairs(TILE, "straight"),
        "deepslate_tile_stairs_inner": _kiwa_stairs(TILE, "inner"),
        "deepslate_tile_stairs_outer": _kiwa_stairs(TILE, "outer"),
        "deepslate_brick_stairs": _kiwa_stairs(BRICK, "straight"),
        "deepslate_brick_stairs_inner": _kiwa_stairs(BRICK, "inner"),
        "deepslate_brick_stairs_outer": _kiwa_stairs(BRICK, "outer"),
        # 기와 — 반블록 (처마·용마루 받침·담 갓)
        "deepslate_tile_slab": _kiwa_slab(TILE, "z"),
        "deepslate_tile_slab_top": _kiwa_slab(TILE, "z", top=True),
        "deepslate_brick_slab": _kiwa_slab(BRICK, "x"),
        "deepslate_brick_slab_top": _kiwa_slab(BRICK, "x", top=True),
        # 담장 기와 갓 (side_tall 제외 — 위가 막힌 변종이라 갓이 죽은 원소가 된다)
        "deepslate_tile_wall_post": _wall_post(),
        "deepslate_tile_wall_side": _wall_side(),
        # 세살 격자창 (noside 2종은 바닐라 유지 — 문설주 상자 속에 숨는다)
        "glass_pane_post": _pane_post(),
        "glass_pane_side": _pane_side(),
        "glass_pane_side_alt": _pane_side(alt=True),
        # 죽렴
        "bamboo_planks": _bamboo_planks(),
    }


def write_hanok_assets():
    """한옥 건축 어휘 형태층을 굽는다 — 반환: 재정의한 모델 수."""
    catalog = _catalog()
    for name in sorted(catalog):
        write_json(BLOCK_MODEL_DIR / f"{name}.json", catalog[name])
    print(f"  한옥 형태층 {len(catalog)}모델 (기와 14 + 담 갓 2 + 세살창 3 + 죽렴 1) — "
          f"blockstates 불변 = 전 variant 승계 · 히트박스·컬링 계약 불변 · 새 PNG 0장")
    return len(catalog)
