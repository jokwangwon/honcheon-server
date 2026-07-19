"""V2-F 한옥 가구 3D — blockstates + 블록 모델 신설 (pack_upgrade_v2_3d.md §2 V2-F).

【이 팩의 첫 블록 모델층】 지금까지 팩은 블록의 **낯(텍스처)** 만 바꿨다 — 형태는 전부 바닐라
큐브였다 (blockstates/ 도 models/block/ 도 없었다). 사용자 지시(2026-07-16): *"가구들도 전부
모델링을 통해 서랍장이라던지 책장의 옛 한옥 가구처럼 변경."* 이 모듈이 그 층을 신설한다:
  assets/minecraft/blockstates/*.json           — 바닐라 blockstate 를 전부 재정의 (아래 ㄴ)
  assets/honcheon/models/block/furniture/*.json — 가구의 형체 (model_key_audit 사슬 검사 대상)

【불가침 네 가지】
 ㄱ. 히트박스는 바닐라 그대로다 — 모델은 그림이지 판정이 아니다 (VoxelShape 는 서버 소관.
     배부른 술독도 부딪히는 상자는 바닐라 술통이다 — 보행·조준 영향 0).
 ㄴ. blockstate 는 바닐라의 **모든 variant 를 재정의한다** — 하나라도 빠지면 그 상태가
     보라/검정 깨짐으로 선다. 각 표(variant 키·회전·multipart 조건)는
     run/client/client-1.21.11.jar 의 blockstates JSON **실측 사본**이다 (짐작 금지 —
     barrel 12 variants · chiseled_bookshelf 52 multipart · *_shelf 24 · composter 9).
     끝에서 개수를 검산한다 (_check_counts) — 표가 줄면 굽지 않는다.
 ㄷ. 텍스처는 **기존 249장 계약 그대로 — 새 PNG 0장.** 모델이 부르는 것은 blocks.py 가 이미
     구운 징발분(barrel_*·lectern_*·loom_*·composter_*·*_shelf·chiseled_bookshelf_*·cauldron_*·
     water_still·lava_still·powder_snow)뿐이다. texture_audit 축 ⑮(아틀라스)가 이를 잰다 —
     팩에 없는 그림을 부르면 그 축이 운다.
 ㄹ. 내용물 UV·좌표 계약 — **기능이 그림을 이긴다**:
     · chiseled_bookshelf 슬롯 6칸의 앞면 UV 구역은 바닐라 슬롯 계약 그대로다
       (texture_layer_design.md §8.5 — "3×2 여섯 칸 격자는 바닐라 슬롯 UV 계약").
       서랍의 입체(찬 서랍 돌출 / 빈 서랍 후퇴)만 얹는다 — 슬롯 표시 기능은 그대로 산다.
     · composter 내용물 7+1단 · cauldron 물/용암/눈 판의 높이·범위([2..14])는 바닐라 그대로 —
       그릇의 **안벽을 x=2 / x=14 (z 동일)에 고정**해 내용물 판과 맞물린다.
       안벽을 옮기면 내용물이 벽을 뚫는다 (그래서 형태는 전부 **바깥쪽**에서 만든다).

【블록 엔티티는 못 바꾼다 — 기술 한계 (침묵하지 않고 적는다)】
 · chest·decorated_pot·sign 은 bakedmodel 이 아니라 블록 엔티티 렌더러가 그린다 —
   리소스팩 모델로 형태를 바꿀 수 없다. entity 텍스처 재작화(blocks.py 기물_블록엔티티 —
   entity/chest/normal*.png · entity/decorated_pot/*)까지가 기술의 끝이다.
 · lectern 의 책 · shelf 의 진열 아이템 · chiseled_bookshelf 상호작용은 블록 엔티티/렌더러가
   **바닐라 좌표에** 그린다 — 모델을 낮춰도 책·아이템의 자리는 못 옮긴다.
   서안(낮은 책상) 위의 책이 떠 보일 수 있다 — 잠정 수용, 인게임 육안 확정 대상.

【형태 어휘 — 지어내지 않는다 (실존 한옥 가구에서)】
  barrel→옹기 술독(배부른 독) · chiseled_bookshelf→한약장(서랍 격자) · lectern→서안(書案) ·
  *_shelf→시렁(널+까치발) · composter→절구(방아확) · loom→베틀 · cauldron→무쇠 가마솥(전·발).
  세부 문양은 잠정 — 매핑 확정은 사용자 결정 (pack_upgrade_v2_3d.md §3 · 매핑표 시안).
"""
import math

from .core import PACK, write_json

BS_DIR = PACK / "assets" / "minecraft" / "blockstates"
FURN_DIR = PACK / "assets" / "honcheon" / "models" / "block" / "furniture"
REF = "honcheon:block/furniture/"          # blockstate → 모델 참조 접두
TEX = "minecraft:block/"                    # 징발 텍스처 접두 (전부 blocks.py 기소유분)

_ANGLES = (-45.0, -22.5, 22.5, 45.0)        # MC 원소 회전 — 한 축 · 이 각만 (core._bx 와 같은 법)


def _f(tex, uv=None, cull=None, rot=None, tint=None):
    """면 하나 — texture 참조(#슬롯) + 선택 uv/cullface/rotation/tintindex."""
    d = {"texture": tex}
    if uv is not None:
        d["uv"] = list(uv)
    if cull:
        d["cullface"] = cull
    if rot is not None:
        d["rotation"] = rot
    if tint is not None:
        d["tintindex"] = tint
    return d


def _el(fr, to, faces, rot=None):
    """원소 하나 — 좌표 [-16,32]·from≤to·회전각 검산을 굽기 전에 한다 (클라이언트는 조용히 버린다)."""
    for i in range(3):
        lo, hi = fr[i], to[i]
        if lo > hi:
            raise ValueError(f"from > to ({fr} → {to})")
        for v in (lo, hi):
            if not -16 <= v <= 32:
                raise ValueError(f"원소가 모델 상자 [-16,32] 를 벗어났다: {fr} → {to}")
    e = {"from": list(fr), "to": list(to)}
    if rot:
        axis, ang, org = rot
        if ang not in _ANGLES:
            raise ValueError(f"허용되지 않는 원소 회전각 {ang} (MC 는 0·±22.5·±45 만 받는다)")
        e["rotation"] = {"origin": list(org), "axis": axis, "angle": ang}
    e["faces"] = faces
    return e


# ═══════════════════════════════════════════════════════════════════════════
# ① barrel → 옹기 술독 (suldok / suldok_open) — **팔각 층** (2차 다듬기, 사용자 승인)
#
# 형태 근거: 옹기 독 — 좁은 굽 → 배가 최대로 부풀고 → 어깨가 좁아지며 → 짧은 목 위에 전(입술).
# 닫힘 = 옹기 뚜껑(꼭지 달림) / 열림 = 전 위가 뚫린 아가리 (barrel_top_open 의 어두운 속).
#
# 【팔각 층 기법 — 렌더 세 판이 가르친 것】 1차(직육면체 층층)는 **계단식 케이크**로 읽혔다
# (사용자 판정 — 다듬기 승인). 처방은 [정방 상자 + 45° y회전 상자] 겹침으로 단면을 팔각으로
# 만드는 것인데, 렌더(눈)로 두 번 더 고쳤다:
#   ① 같은 크기 회전 쌍은 과하다 — 모가 (√2−1)·반폭 (배에서 2.9px) 튀어 교차 널더미가 됐다.
#      회전 상자 반폭 w₂=(w+1.2)/√2 로 **모 돌출 1.2px 균일** — 잔모(리브)가 되어 둥글게 읽힌다.
#   ② 층마다 쌍을 주면 리브가 층층이 끊겨 **널을 쌓은 더미**로 읽혔다 — 리브는 몸 전체를
#      한 번에 관통해야 '팔각 기둥'이다. 그래서 쌍은 **키 큰 둘뿐**(몸통 y1..12 · 배띠 y4..9.5)
#      이고, 프로파일(굽→배→어깨→목)은 홑겹 정방 띠가 깎는다.
#   · 회전 상자의 위/아래 낯이 정방 상자와 동일 평면이면 z-fight — 상하 0.05 인셋으로 피한다.
# 겉면 UV: 모든 상자가 제 세로 구간을 텍스처의 같은 세로 구간에 문다 — 회전 낯도 제 높이의
# 유약 줄을 집는다 (엉뚱한 픽셀을 집지 않는다 — 렌더로 확인). 링은 barrel_bottom(민무늬).
# 회전: blockstate 가 바닐라와 같은 x/y 회전을 먹인다 — 눕힌 술독도 바닐라 눕힌 술통과 같은 문법.
# ═══════════════════════════════════════════════════════════════════════════
def _suldok(open_):
    def band(x0, y0, x1, y1, up="#ring", down="#ring", twin=False):
        """정방 띠 하나 — twin=True 면 45° 회전 상자(모 돌출 1.2px)를 짝으로 겹친다."""
        side = {c: _f("#side", uv=[0, 16 - y1, 16, 16 - y0])
                for c in ("north", "east", "south", "west")}
        f = dict(side)
        f["up"] = _f(up)
        f["down"] = _f(down, cull="down" if y0 == 0 else None)
        out = [_el([x0, y0, x0], [x1, y1, x1], f)]
        if twin:
            g = dict(side)
            g["up"] = _f("#ring")
            g["down"] = _f("#ring")
            w2 = round((((x1 - x0) / 2.0) + 1.2) / math.sqrt(2) * 20) / 20
            out.append(_el([8 - w2, y0 + 0.05, 8 - w2], [8 + w2, y1 - 0.05, 8 + w2], g,
                           rot=("y", 45, [8, 8, 8])))
        return out

    els = []
    els += band(4.5, 0, 11.5, 1, down="#bottom")            # 굽 — 좁은 발
    els += band(1.5, 1, 14.5, 12, twin=True)                # 몸통 — 리브가 관통하는 팔각 기둥
    els += band(0.75, 4, 15.25, 9.5, twin=True)             # 배띠 — 최대 불룩 (몸통 위에 겹)
    els += band(3, 12, 13, 13)      # 어깨 — 홑겹. 짧은 띠에 쌍을 주면 도는 널(핀휠)로 읽힌다
    els += band(5, 13, 11, 14)      # 목      (렌더 4판 실측 — 리브는 키 큰 몸통 둘에만)
    if open_:
        els += band(4, 14, 12, 15.5, up="#mouth")           # 전 — 뚫린 아가리
    else:
        els += band(4, 14, 12, 15)                          # 전(입술)
        els += band(3.5, 15, 12.5, 15.75, up="#lid")        # 옹기 뚜껑
        els.append(_el([7, 15.75, 7], [9, 16, 9], {         # 뚜껑 꼭지 (낱개)
            "north": _f("#side", uv=[0, 0, 16, 0.25]), "south": _f("#side", uv=[0, 0, 16, 0.25]),
            "east": _f("#side", uv=[0, 0, 16, 0.25]), "west": _f("#side", uv=[0, 0, 16, 0.25]),
            "up": _f("#lid")}))
    return {
        "parent": "block/block",
        "textures": {"particle": TEX + "barrel_side", "side": TEX + "barrel_side",
                     "lid": TEX + "barrel_top", "mouth": TEX + "barrel_top_open",
                     "ring": TEX + "barrel_bottom", "bottom": TEX + "barrel_bottom"},
        "elements": els,
    }


# ═══════════════════════════════════════════════════════════════════════════
# ② chiseled_bookshelf → 한약장 (yakjang + 슬롯 12)
#
# 몸은 z2..16 (앞이 2px 파인 서랍장) — 앞면은 슬롯 6칸(서랍 낯)이 빈틈없이 덮는다.
# 슬롯 좌표·앞면 UV 는 client jar 의 template_chiseled_bookshelf_slot_* 실측 그대로다
# (§8.5 슬롯 UV 계약 — 칸마다 제 구역을 그대로 문다 = 채움 표시 기능 불변).
# 입체는 깊이로 준다: 찬 서랍(occupied)은 z-0.75 로 **돌출**, 빈 서랍(empty)은 z0.75 로
# **후퇴** — 약장을 여닫은 손자국이 낯의 요철로 남는다. 옆 낯(0.5px 립)은 제 구역의
# 가장자리 반 칸을 물어 색이 이어진다.
# ═══════════════════════════════════════════════════════════════════════════
_SLOTS = [
    # (이름, from-x, from-y, to-x, to-y, 앞면 uv) — client jar 실측 (slot_0..5 순서)
    ("top_left", 10, 8, 16, 16, [0, 0, 6, 8]),
    ("top_mid", 5, 8, 10, 16, [6, 0, 11, 8]),
    ("top_right", 0, 8, 5, 16, [11, 0, 16, 8]),
    ("bottom_left", 10, 0, 16, 8, [0, 8, 6, 16]),
    ("bottom_mid", 5, 0, 10, 8, [6, 8, 11, 16]),
    ("bottom_right", 0, 0, 5, 8, [11, 8, 16, 16]),
]


def _yakjang_body():
    faces = {
        "north": _f("#side", uv=[0, 0, 16, 16]),                     # 서랍 뒤벽 (틈에서만 보인다)
        "east": _f("#side", uv=[0, 0, 16, 16], cull="east"),
        "south": _f("#side", uv=[0, 0, 16, 16], cull="south"),
        "west": _f("#side", uv=[0, 0, 16, 16], cull="west"),
        "up": _f("#top", uv=[0, 0, 16, 16], cull="up"),
        "down": _f("#top", uv=[0, 0, 16, 16], cull="down"),
    }
    return {
        "parent": "block/block",
        "textures": {"particle": TEX + "chiseled_bookshelf_top",
                     "top": TEX + "chiseled_bookshelf_top",
                     "side": TEX + "chiseled_bookshelf_side"},
        "elements": [_el([0, 0, 2], [16, 16, 16], faces)],
    }


def _yakjang_slot(pos, occupied):
    name, x0, y0, x1, y1, uv = next(s for s in _SLOTS if s[0] == pos)
    u0, v0, u1, v1 = uv
    z0 = -0.75 if occupied else 0.75            # 찬 서랍은 나오고, 빈 서랍은 들어간다
    tex = TEX + ("chiseled_bookshelf_occupied" if occupied else "chiseled_bookshelf_empty")
    faces = {
        "north": _f("#front", uv=uv, cull="north"),                  # ★ 슬롯 UV 계약 — 바닐라 그대로
        "up": _f("#front", uv=[u0, v0, u1, v0 + 0.5]),
        "down": _f("#front", uv=[u0, v1 - 0.5, u1, v1]),
        "west": _f("#front", uv=[u0, v0, u0 + 0.5, v1]),
        "east": _f("#front", uv=[u1 - 0.5, v0, u1, v1]),
    }
    return {"textures": {"particle": tex, "front": tex},
            "elements": [_el([x0, y0, z0], [x1, y1, 2], faces)]}


# ═══════════════════════════════════════════════════════════════════════════
# ③ lectern → 서안 (seoan)
#
# 형태 근거: 서안 — 낮은 책상. 판각(板脚) 두 장이 상판을 받치고, 다리 사이 가름대,
# 앞에 서랍 낯, 위에 22.5° 경사 상판 (lectern 의 "경사 판" 문법을 낮춰 잇는다 —
# 바닐라 lectern 도 -22.5° 경사판이다: 같은 각, 다른 높이).
# 한계: 책은 블록 엔티티 렌더러가 바닐라 높이(y≈15)에 그린다 — 서안 위 책은 떠 보인다 (잠정).
# ═══════════════════════════════════════════════════════════════════════════
def _seoan():
    els = [
        # 판각 두 장 (앞뒤로 긴 널다리)
        _el([1, 0, 2.5], [3, 8, 13.5], {
            "north": _f("#sides", uv=[7, 8, 9, 16]), "south": _f("#sides", uv=[7, 8, 9, 16]),
            "east": _f("#sides", uv=[2, 8, 13, 16]), "west": _f("#sides", uv=[2, 8, 13, 16]),
            "up": _f("#base", uv=[1, 2.5, 3, 13.5]),
            "down": _f("#base", uv=[1, 2.5, 3, 13.5], cull="down")}),
        _el([13, 0, 2.5], [15, 8, 13.5], {
            "north": _f("#sides", uv=[7, 8, 9, 16]), "south": _f("#sides", uv=[7, 8, 9, 16]),
            "east": _f("#sides", uv=[2, 8, 13, 16]), "west": _f("#sides", uv=[2, 8, 13, 16]),
            "up": _f("#base", uv=[13, 2.5, 15, 13.5]),
            "down": _f("#base", uv=[13, 2.5, 15, 13.5], cull="down")}),
        # 가름대 (다리 사이 — 동서 낯은 판각에 맞물려 안 보인다)
        _el([3, 1.5, 7], [13, 3.5, 9], {
            "north": _f("#base", uv=[3, 12.5, 13, 14.5]), "south": _f("#base", uv=[3, 12.5, 13, 14.5]),
            "up": _f("#base", uv=[3, 7, 13, 9]), "down": _f("#base", uv=[3, 7, 13, 9])}),
        # 서랍 낯 (앞판)
        _el([3, 4.5, 4], [13, 7.5, 12], {
            "north": _f("#sides", uv=[3, 9, 13, 12]), "south": _f("#sides", uv=[3, 9, 13, 12]),
            "up": _f("#base", uv=[3, 4, 13, 12]), "down": _f("#base", uv=[3, 4, 13, 12])}),
        # 경사 상판 — lectern_top(서책·문서 아트)을 바닐라와 같은 uv·rotation 180 으로 얹는다
        _el([0, 8.2, 1.75], [16, 10.2, 14.25], {
            "up": _f("#top", uv=[0, 1, 16, 14], rot=180),
            "down": _f("#base", uv=[0, 1, 16, 14]),
            "north": _f("#sides", uv=[0, 0, 16, 2]), "south": _f("#sides", uv=[0, 0, 16, 2]),
            "east": _f("#sides", uv=[0, 4, 12.5, 6]), "west": _f("#sides", uv=[0, 4, 12.5, 6])},
            rot=("x", -22.5, [8, 9.2, 8])),
    ]
    return {
        "parent": "block/block",
        "textures": {"particle": TEX + "lectern_sides", "top": TEX + "lectern_top",
                     "sides": TEX + "lectern_sides", "base": TEX + "lectern_base"},
        "elements": els,
    }


# ═══════════════════════════════════════════════════════════════════════════
# ④ *_shelf 5종 → 시렁 (template_sireong_* + 목재별 자식)
#
# 몸의 세 원소(뒷판·아랫널·윗널)와 UV 는 client jar template_shelf_body.json **실측 그대로**다 —
# 등록부 시렁 절의 UV 계약(x8~16/y0~8=뒷판 · x0~8/y0~2·y6~8=널의 앞모)이 그 그림을 이미
# 그 자리에 그려 놓았기 때문이다 (좌표를 옮기면 그림이 어긋난다). 시렁의 어휘는 **까치발**로
# 얹는다: 윗널 아래 양 끝, 45° 빗대 두 개 (벽널에서 널 앞전으로 오르는 받침).
# 진열 아이템은 렌더러가 바닐라 좌표에 그린다 — 까치발을 양 끝(x0~1.5 / x14.5~16)에 붙여
# 아이템 자리(가운데 세 자리)와 겹치지 않게 했다 (잠정 — 인게임 육안 확정 대상).
# powered/side_chain 겹판(unpowered·unconnected·left·center·right)은 바닐라 원소 그대로 —
# 그 판이 앞면 가운데 띠를 **항상** 제공한다 (multipart 가 늘 하나를 고른다).
# ═══════════════════════════════════════════════════════════════════════════
_SHELF_WOODS = ("spruce", "dark_oak", "oak", "bamboo", "cherry")
# 겹판 uv — client jar template_shelf_{unpowered,left,center,right}.json + unconnected 실측
_SHELF_OVERLAYS = {
    "unpowered": [0, 2, 8, 6],
    "unconnected": [8, 12, 16, 16],
    "left": [0, 8, 8, 12],
    "center": [0, 12, 8, 16],
    "right": [8, 8, 16, 12],
}


def _sireong_body():
    els = [
        # 뒷판 (client jar template_shelf_body 원소 1 — UV 실측 그대로)
        _el([0, 0, 13], [16, 16, 16], {
            "east": _f("#all", uv=[8, 0, 9.5, 8], cull="east"),
            "south": _f("#all", uv=[8, 0, 16, 8], cull="south"),
            "west": _f("#all", uv=[14.5, 0, 16, 8], cull="west"),
            "up": _f("#all", uv=[16, 5, 8, 3.5], cull="up"),
            "down": _f("#all", uv=[16, 6, 8, 4.5], cull="down")}),
        # 아랫널 (원소 2)
        _el([0, 0, 11], [16, 4, 13], {
            "north": _f("#all", uv=[0, 6, 8, 8]),
            "east": _f("#all", uv=[1.5, 6, 2.5, 8], cull="east"),
            "west": _f("#all", uv=[5.5, 6, 6.5, 8], cull="west"),
            "up": _f("#all", uv=[8, 3.5, 16, 4.5]),
            "down": _f("#all", uv=[16, 4.5, 8, 3.5], cull="down")}),
        # 윗널 (원소 3)
        _el([0, 12, 11], [16, 16, 13], {
            "north": _f("#all", uv=[0, 0, 8, 2]),
            "east": _f("#all", uv=[1.5, 0, 2.5, 2], cull="east"),
            "west": _f("#all", uv=[5.5, 0, 6.5, 2], cull="west"),
            "up": _f("#all", uv=[16, 6, 8, 5], cull="up"),
            "down": _f("#all", uv=[8, 5, 16, 6])}),
    ]
    # 까치발 두 개 — 윗널 아래 양 끝 45° 빗대 (앞이 오르고 뒤가 내려 벽널에 박힌다)
    for x0 in (0.0, 14.5):
        els.append(_el([x0, 9.0, 9.7], [x0 + 1.5, 10.5, 13.7], {
            "north": _f("#all", uv=[0.5, 6, 2, 7.5]), "south": _f("#all", uv=[0.5, 6, 2, 7.5]),
            "east": _f("#all", uv=[1.5, 6, 2.5, 7.5]), "west": _f("#all", uv=[5.5, 6, 6.5, 7.5]),
            "up": _f("#all", uv=[0.5, 6, 2, 7]), "down": _f("#all", uv=[0.5, 6.5, 2, 7.5])},
            rot=("x", 45, [x0 + 0.75, 9.75, 11.7])))
    return {
        "parent": "block/block",
        # display 는 client jar template_shelf_body 실측 — 손·GUI 에서 바닐라 시렁과 같은 자리
        "display": {
            "gui": {"rotation": [30, 225, 0], "translation": [2.5, -1.5, 0], "scale": [0.625, 0.625, 0.625]},
            "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, -4], "scale": [0.5, 0.5, 0.5]},
        },
        "textures": {"particle": "#all"},
        "elements": els,
    }


def _sireong_overlay(kind):
    """겹판 — [0,4,13]..[16,12,13] 북면 한 장. 좌표·uv 바닐라 실측 그대로 (multipart 가 늘 하나를 문다)."""
    return {"parent": "block/block", "textures": {"particle": "#all"},
            "elements": [_el([0, 4, 13], [16, 12, 13],
                             {"north": _f("#all", uv=_SHELF_OVERLAYS[kind])})]}


# ═══════════════════════════════════════════════════════════════════════════
# ⑤ composter → 절구 (jeolgu + 내용물 8)
#
# 형태 근거: 절구(방아확) — 좁은 굽 위에 몸이 서고 위 전이 벌어진다.
# 안벽은 x/z 2..14 고정 (내용물 판 계약 — 머리말 ㄹ). 내용물 8단은 바닐라 좌표 그대로.
# ═══════════════════════════════════════════════════════════════════════════
def _jeolgu():
    els = [
        # 굽 + 안바닥 (위 낯이 절구 속 바닥)
        _el([1, 0, 1], [15, 2, 15], {
            "up": _f("#inside"), "down": _f("#bottom", cull="down"),
            "north": _f("#side", uv=[1, 14, 15, 16]), "south": _f("#side", uv=[1, 14, 15, 16]),
            "east": _f("#side", uv=[1, 14, 15, 16]), "west": _f("#side", uv=[1, 14, 15, 16])}),
    ]
    # 네 벽 — 안벽 2/14 계약. 아랫단(y2..8)은 겉을 0.75 들이고 윗단(y8..13)은 다 채워
    # 절구의 허리 굴곡을 만든다 (안벽은 두 단 모두 2/14 — 내용물 판이 맞물린다).
    for lo, hi, inset in ((2, 8, 0.75), (8, 13, 0.0)):
        for fr, to, outer in (
                ([0 + inset, lo, 0 + inset], [2, hi, 16 - inset], "west"),
                ([14, lo, 0 + inset], [16 - inset, hi, 16 - inset], "east"),
                ([2, lo, 0 + inset], [14, hi, 2], "north"),
                ([2, lo, 14], [14, hi, 16 - inset], "south")):
            f = {c: _f("#side", cull=(c if (c == outer and inset == 0) else None))
                 for c in ("north", "south", "east", "west")}
            f["down"] = _f("#bottom")            # 허리 턱 — 윗단이 내민 밑면 링이 굴곡을 그린다
            els.append(_el(fr, to, f))
    # 전 (벌어진 위 테) — 밖으로 0.5px 내밀고 안벽은 2/14 를 지킨다
    for fr, to in (([-0.5, 13, -0.5], [2, 16, 16.5]), ([14, 13, -0.5], [16.5, 16, 16.5]),
                   ([2, 13, -0.5], [14, 16, 2]), ([2, 13, 14], [14, 16, 16.5])):
        els.append(_el(fr, to, {
            "up": _f("#top", cull="up"), "down": _f("#side"),
            "north": _f("#side"), "south": _f("#side"),
            "east": _f("#side"), "west": _f("#side")}))
    # 전 팔각화 (2차 다듬기 — 여력분) — 가마솥 배와 같은 모죽임 변형: 벌어진 전의 네 귀를
    # 45° 회전 상자로 덮는다 (팔각 입 = 절구답다). 속이 빈 그릇이라 같은 크기 회전 쌍은 금지
    # (내용물 판 y15 가 묻힌다) — 귀 상자가 안으로 x11.3 까지만 닿아 위 낯 모서리만 팔각으로
    # 가린다. 상하 13.05/15.95 인셋 — 정방 테(13/16)와 수평 낯 z-fight 방지.
    for cx, cz in ((1.2, 1.2), (14.8, 1.2), (1.2, 14.8), (14.8, 14.8)):
        els.append(_el([cx - 2.5, 13.05, cz - 2.5], [cx + 2.5, 15.95, cz + 2.5], {
            "north": _f("#side", uv=[0, 0, 16, 3]), "south": _f("#side", uv=[0, 0, 16, 3]),
            "east": _f("#side", uv=[0, 0, 16, 3]), "west": _f("#side", uv=[0, 0, 16, 3]),
            "up": _f("#top"), "down": _f("#side")},
            rot=("y", 45, [cx, 8, cz])))
    return {
        "parent": "block/block",
        "textures": {"particle": TEX + "composter_side", "side": TEX + "composter_side",
                     "top": TEX + "composter_top", "bottom": TEX + "composter_bottom",
                     "inside": TEX + "composter_bottom"},
        "elements": els,
    }


def _jeolgu_contents(h, ready):
    """내용물 판 — 좌표([2..14]·높이)와 텍스처는 바닐라 composter_contents* 실측 그대로."""
    tex = TEX + ("composter_ready" if ready else "composter_compost")
    return {"textures": {"particle": tex, "inside": tex},
            "elements": [_el([2, 0, 2], [14, h, 14], {"up": _f("#inside")})]}


# ═══════════════════════════════════════════════════════════════════════════
# ⑥ loom → 베틀 (beteul)
#
# 형태 근거: 베틀 — 바닥틀(누운 틀) 위에 기둥 두 개, 상량(도투마리 얹는 보), 그 사이
# 22.5° 뒤로 기운 날실판 (loom_front 의 무늬 아트가 짜이는 천이 된다).
# ═══════════════════════════════════════════════════════════════════════════
def _beteul():
    els = [
        # 바닥틀
        _el([0, 0, 2], [16, 2, 14], {
            "up": _f("#top", uv=[0, 2, 16, 14]), "down": _f("#bottom", uv=[0, 2, 16, 14], cull="down"),
            "north": _f("#side", uv=[0, 14, 16, 16]), "south": _f("#side", uv=[0, 14, 16, 16]),
            "east": _f("#side", uv=[2, 14, 14, 16]), "west": _f("#side", uv=[2, 14, 14, 16])}),
        # 기둥 두 개
        _el([1, 2, 6], [3, 14, 10], {
            "north": _f("#side", uv=[1, 2, 3, 14]), "south": _f("#side", uv=[1, 2, 3, 14]),
            "east": _f("#side", uv=[6, 2, 10, 14]), "west": _f("#side", uv=[6, 2, 10, 14])}),
        _el([13, 2, 6], [15, 14, 10], {
            "north": _f("#side", uv=[13, 2, 15, 14]), "south": _f("#side", uv=[13, 2, 15, 14]),
            "east": _f("#side", uv=[6, 2, 10, 14]), "west": _f("#side", uv=[6, 2, 10, 14])}),
        # 상량 (도투마리 보)
        _el([0, 14, 5], [16, 16, 11], {
            "up": _f("#top", uv=[0, 5, 16, 11], cull="up"), "down": _f("#side", uv=[0, 5, 16, 11]),
            "north": _f("#side", uv=[0, 0, 16, 2]), "south": _f("#side", uv=[0, 0, 16, 2]),
            "east": _f("#side", uv=[5, 0, 11, 2]), "west": _f("#side", uv=[5, 0, 11, 2])}),
        # 날실판 — 뒤로 기운 천 (loom_front 무늬가 짜이는 자리)
        _el([1.5, 3, 6.5], [14.5, 13, 8.5], {
            "north": _f("#front", uv=[1.5, 3, 14.5, 13]), "south": _f("#front", uv=[1.5, 3, 14.5, 13]),
            "up": _f("#side", uv=[1.5, 6.5, 14.5, 8.5]), "down": _f("#side", uv=[1.5, 6.5, 14.5, 8.5]),
            "east": _f("#side", uv=[6.5, 3, 8.5, 13]), "west": _f("#side", uv=[6.5, 3, 8.5, 13])},
            rot=("x", -22.5, [8, 8, 7.5])),
    ]
    return {
        "parent": "block/block",
        "textures": {"particle": TEX + "loom_side", "front": TEX + "loom_front",
                     "side": TEX + "loom_side", "top": TEX + "loom_top",
                     "bottom": TEX + "loom_bottom"},
        "elements": els,
    }


# ═══════════════════════════════════════════════════════════════════════════
# ⑦ cauldron 4종 → 무쇠 가마솥 (gamasot + 내용물 7)
#
# 형태 근거: 가마솥 — 발 넷 위에 배가 부풀고, 위에 전(귀때 테)이 벌어진다.
# 안벽 2/14 · 안바닥 [2,3,2]..[14,4,14] 는 바닐라 계약 그대로 (물/용암/눈 판이 맞물린다).
# 내용물 판: [2,4,2]..[14,h,14] up · tintindex 0 — client jar template_cauldron_* 실측
# (h: level1=9 · level2=12 · full=15. 물은 바이옴 water_color 로 틴트된다 — 바닐라 동일).
# ═══════════════════════════════════════════════════════════════════════════
def _gamasot(content=None, height=None):
    els = []
    # 발 넷 (모서리)
    for fr, to in (([0.5, 0, 0.5], [3.5, 3, 3.5]), ([12.5, 0, 0.5], [15.5, 3, 3.5]),
                   ([0.5, 0, 12.5], [3.5, 3, 15.5]), ([12.5, 0, 12.5], [15.5, 3, 15.5])):
        els.append(_el(fr, to, {
            "north": _f("#side"), "south": _f("#side"), "east": _f("#side"), "west": _f("#side"),
            "down": _f("#bottom", cull="down")}))
    # 안바닥 (바닐라 좌표)
    els.append(_el([2, 3, 2], [14, 4, 14], {"up": _f("#inside"), "down": _f("#inside")}))
    # 네 벽 y3..13 — 안벽 2/14 계약 (위 낯은 전이 덮는다)
    for fr, to, outer, inner in (
            ([0, 3, 0], [2, 13, 16], "west", "east"), ([14, 3, 0], [16, 13, 16], "east", "west"),
            ([2, 3, 0], [14, 13, 2], "north", "south"), ([2, 3, 14], [14, 13, 16], "south", "north")):
        f = {c: _f("#side", cull=c if c == outer else None)
             for c in ("north", "south", "east", "west")}
        f["down"] = _f("#inside")
        els.append(_el(fr, to, f))
    # 배 (불룩) — 벽 밖에 두른 띠 (벽 속으로 0.25 물려 틈이 없다)
    for fr, to in (([-0.75, 4.5, -0.75], [0.25, 10.5, 16.75]), ([15.75, 4.5, -0.75], [16.75, 10.5, 16.75]),
                   ([0.25, 4.5, -0.75], [15.75, 10.5, 0.25]), ([0.25, 4.5, 15.75], [15.75, 10.5, 16.75])):
        els.append(_el(fr, to, {
            "north": _f("#side"), "south": _f("#side"), "east": _f("#side"), "west": _f("#side"),
            "up": _f("#side"), "down": _f("#side")}))
    # 배 팔각화 (2차 다듬기) — 네 귀에 45° 회전 상자를 앉혀 모를 죽인다 (실루엣 팔각).
    # 술독과 달리 **가마솥은 속이 비어야 한다** (내용물 판 계약) — 같은 크기 회전 쌍(속이 찬 상자)을
    # 겹치면 물 판(y9)이 상자 속에 묻힌다. 그래서 귀만 덮는 모죽임(chamfer) 변형을 쓴다:
    # 귀 상자가 안쪽으로 x11.5 까지 닿아 내용물 판의 네 모서리를 가린다 = 물낯도 팔각으로 읽힌다
    # (버그 아니라 의도 — 둥근 솥의 물은 둥글다. 잠정 — 인게임 육안 확정 대상).
    # 상하 4.55/10.45 인셋 — 정방 띠(4.5/10.5)와 수평 낯 동일 평면 z-fight 방지.
    for cx, cz in ((1.0, 1.0), (15.0, 1.0), (1.0, 15.0), (15.0, 15.0)):
        els.append(_el([cx - 2.5, 4.55, cz - 2.5], [cx + 2.5, 10.45, cz + 2.5], {
            "north": _f("#side", uv=[0, 5.5, 16, 11.5]), "south": _f("#side", uv=[0, 5.5, 16, 11.5]),
            "east": _f("#side", uv=[0, 5.5, 16, 11.5]), "west": _f("#side", uv=[0, 5.5, 16, 11.5]),
            "up": _f("#side"), "down": _f("#side")},
            rot=("y", 45, [cx, 8, cz])))
    # 전 (벌어진 테) y13..16 — 안벽 2/14 유지
    for fr, to in (([-1, 13, -1], [2, 16, 17]), ([14, 13, -1], [17, 16, 17]),
                   ([2, 13, -1], [14, 16, 2]), ([2, 13, 14], [14, 16, 17])):
        els.append(_el(fr, to, {
            "up": _f("#top", cull="up"), "down": _f("#side"),
            "north": _f("#side"), "south": _f("#side"),
            "east": _f("#side"), "west": _f("#side")}))
    doc = {
        "ambientocclusion": False,          # 바닐라 cauldron 동일 — 안이 그늘로 죽지 않게
        "textures": {"particle": TEX + "cauldron_side", "top": TEX + "cauldron_top",
                     "bottom": TEX + "cauldron_bottom", "side": TEX + "cauldron_side",
                     "inside": TEX + "cauldron_inner"},
    }
    if content:
        doc["textures"]["content"] = content
        els.append(_el([2, 4, 2], [14, height, 14], {"up": _f("#content", tint=0)}))
    doc["elements"] = els
    return doc


# ═══════════════════════════════════════════════════════════════════════════
# blockstate 표 — 바닐라 전 variant 재정의 (client jar 실측 사본. 하나라도 빠지면 깨짐)
# ═══════════════════════════════════════════════════════════════════════════
_Y_ROT = (("north", 0), ("east", 90), ("south", 180), ("west", 270))


def _apply(model, y=0, uvlock=False, x=0):
    a = {"model": REF + model}
    if uvlock:
        a["uvlock"] = True
    if x:
        a["x"] = x
    if y:
        a["y"] = y
    return a


def _bs_barrel():
    rot = {"down": {"x": 180}, "east": {"x": 90, "y": 90}, "north": {"x": 90},
           "south": {"x": 90, "y": 180}, "up": {}, "west": {"x": 90, "y": 270}}
    var = {}
    for facing in ("down", "east", "north", "south", "up", "west"):     # 바닐라 표기 순서
        for op, model in (("false", "suldok"), ("true", "suldok_open")):
            var[f"facing={facing},open={op}"] = _apply(
                model, x=rot[facing].get("x", 0), y=rot[facing].get("y", 0))
    return {"variants": var}


def _bs_facing(model):
    return {"variants": {f"facing={f}": _apply(model, y=y) for f, y in _Y_ROT}}


def _bs_yakjang():
    parts = []
    for facing, y in _Y_ROT:
        parts.append({"apply": _apply("yakjang", y=y, uvlock=True), "when": {"facing": facing}})
        for i, (pos, *_rest) in enumerate(_SLOTS):
            for occ, kind in (("true", "occupied"), ("false", "empty")):
                parts.append({"apply": _apply(f"yakjang_slot_{kind}_{pos}", y=y),
                              "when": {"AND": [{"facing": facing}, {f"slot_{i}_occupied": occ}]}})
    return {"multipart": parts}


def _bs_sireong(wood):
    base = f"sireong_{wood}"
    parts = [{"apply": _apply(base, y=y), "when": {"facing": f}} for f, y in _Y_ROT]
    parts += [{"apply": _apply(f"{base}_unpowered", y=y),
               "when": {"AND": [{"facing": f}, {"powered": "false"}]}} for f, y in _Y_ROT]
    for chain in ("unconnected", "left", "center", "right"):            # 바닐라 표기 순서
        parts += [{"apply": _apply(f"{base}_{chain}", y=y),
                   "when": {"AND": [{"facing": f}, {"powered": "true"}, {"side_chain": chain}]}}
                  for f, y in _Y_ROT]
    return {"multipart": parts}


def _bs_jeolgu():
    parts = [{"apply": _apply("jeolgu")}]
    parts += [{"apply": _apply(f"jeolgu_contents{n}"), "when": {"level": str(n)}} for n in range(1, 8)]
    parts.append({"apply": _apply("jeolgu_ready"), "when": {"level": "8"}})
    return {"multipart": parts}


def write_furniture_assets():
    """가구 3D 층을 굽는다 — (blockstate 수, 모델 수)를 돌려준다."""
    models = {"suldok": _suldok(False), "suldok_open": _suldok(True),
              "yakjang": _yakjang_body(), "seoan": _seoan(),
              "jeolgu": _jeolgu(), "jeolgu_ready": _jeolgu_contents(15, True),
              "beteul": _beteul(), "gamasot": _gamasot()}
    for pos, *_rest in _SLOTS:
        models[f"yakjang_slot_occupied_{pos}"] = _yakjang_slot(pos, True)
        models[f"yakjang_slot_empty_{pos}"] = _yakjang_slot(pos, False)
    for n, h in enumerate((3, 5, 7, 9, 11, 13, 15), start=1):           # 바닐라 contents1..7 높이
        models[f"jeolgu_contents{n}"] = _jeolgu_contents(h, False)
    models["template_sireong_body"] = _sireong_body()
    for kind in _SHELF_OVERLAYS:
        models[f"template_sireong_{kind}"] = _sireong_overlay(kind)
    for wood in _SHELF_WOODS:
        models[f"sireong_{wood}"] = {
            "parent": REF + "template_sireong_body",
            "textures": {"all": TEX + f"{wood}_shelf", "particle": TEX + f"{wood}_shelf"}}
        for kind in _SHELF_OVERLAYS:
            models[f"sireong_{wood}_{kind}"] = {
                "parent": REF + f"template_sireong_{kind}",
                "textures": {"all": TEX + f"{wood}_shelf", "particle": TEX + f"{wood}_shelf"}}
    # 가마솥 내용물 — 높이 9/12/15 · 텍스처는 바닐라 참조 그대로 (전부 팩 실물이 있다 — 축 ⑮)
    for name, tex, h in (("gamasot_water_1", "water_still", 9), ("gamasot_water_2", "water_still", 12),
                         ("gamasot_water_full", "water_still", 15), ("gamasot_lava", "lava_still", 15),
                         ("gamasot_snow_1", "powder_snow", 9), ("gamasot_snow_2", "powder_snow", 12),
                         ("gamasot_snow_full", "powder_snow", 15)):
        models[name] = _gamasot(content=TEX + tex, height=h)

    blockstates = {
        "barrel": _bs_barrel(),
        "chiseled_bookshelf": _bs_yakjang(),
        "lectern": _bs_facing("seoan"),
        "loom": _bs_facing("beteul"),
        "composter": _bs_jeolgu(),
        "cauldron": {"variants": {"": _apply("gamasot")}},
        "water_cauldron": {"variants": {"level=1": _apply("gamasot_water_1"),
                                        "level=2": _apply("gamasot_water_2"),
                                        "level=3": _apply("gamasot_water_full")}},
        "lava_cauldron": {"variants": {"": _apply("gamasot_lava")}},
        "powder_snow_cauldron": {"variants": {"level=1": _apply("gamasot_snow_1"),
                                              "level=2": _apply("gamasot_snow_2"),
                                              "level=3": _apply("gamasot_snow_full")}},
    }
    for wood in _SHELF_WOODS:
        blockstates[f"{wood}_shelf"] = _bs_sireong(wood)

    _check_counts(blockstates, models)
    for name in sorted(models):
        write_json(FURN_DIR / f"{name}.json", models[name])
    for name in sorted(blockstates):
        write_json(BS_DIR / f"{name}.json", blockstates[name])
    return len(blockstates), len(models)


def _check_counts(blockstates, models):
    """굽기 전 검산 — 바닐라 실측 개수와 다르면 variant 가 빠진 것이다 (빠지면 보라/검정 깨짐)."""
    expect = {"barrel": ("variants", 12), "chiseled_bookshelf": ("multipart", 52),
              "lectern": ("variants", 4), "loom": ("variants", 4),
              "composter": ("multipart", 9), "cauldron": ("variants", 1),
              "water_cauldron": ("variants", 3), "lava_cauldron": ("variants", 1),
              "powder_snow_cauldron": ("variants", 3)}
    for wood in _SHELF_WOODS:
        expect[f"{wood}_shelf"] = ("multipart", 24)
    if set(blockstates) != set(expect):
        raise ValueError(f"blockstate 대상이 표와 다르다: {sorted(set(blockstates) ^ set(expect))}")
    for name, (kind, n) in expect.items():
        got = len(blockstates[name][kind])
        if got != n:
            raise ValueError(f"{name}: {kind} {got} ≠ 바닐라 실측 {n} — variant 가 빠졌다 (깨짐)")
    # 모든 blockstate 가 가리키는 모델이 실제로 구워지는가 (사슬 절단 = 보라 큐브)
    refs = set()
    for doc in blockstates.values():
        for v in doc.get("variants", {}).values():
            refs.add(v["model"])
        for p in doc.get("multipart", []):
            refs.add(p["apply"]["model"])
    missing = {r for r in refs if r.split("/")[-1] not in models}
    if missing:
        raise ValueError(f"blockstate 가 없는 모델을 가리킨다: {sorted(missing)}")
