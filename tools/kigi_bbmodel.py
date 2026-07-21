#!/usr/bin/env python3
"""검기의 호를 **3D 리본**으로 굽는다 — BetterModel 이 읽는 `.bbmodel` (JSON).

【왜 3D 인가 — A·B 실측이 시킨 것】
  같은 무대에서 층을 갈라 재 봤다 (2026-07-20):
    · A 판(ItemDisplay)만 : 뒤 85px  옆 1,708px  앞 633px — 옆에서 **초승달로 읽힌다**. 뒤에선 사라진다.
    · B 점(파티클)만      : 뒤 2,029  옆 2,437   앞 3,480 — 어디서나 보이는데 **형태가 없다**(티끌·이끼).
  둘의 약점이 정확히 반대다. 납작한 판은 옆면이 되면 사라지고, 점은 모양을 못 만든다.
  ⇒ **굽은 리본**이면 둘 다 된다: 면이 굽어 있으니 뒤에서는 반대쪽 면이 보이고, 그러면서 형태가 남는다.

【왜 손이 아니라 코드로 굽는가】
  `.bbmodel` 은 평범한 JSON 이다. 우리는 이미 텍스처·아이템 모델을 코드로 굽고 있으므로
  Blockbench(GUI)를 손으로 만질 이유가 없다. 굽는 것이 코드면 **다시 굽을 수 있고, 감사할 수 있다.**

【색】 등록부의 격 사다리를 따른다 — 검기는 **청회**다 (config/skill_motion.yml inks).
  먹 → 청회 → 회백 → 청백 램프. 여기 숫자를 새로 지어내지 않는다.
"""
from __future__ import annotations

import base64
import io
import json
import math
import uuid
from pathlib import Path

from PIL import Image

# ── 등록부의 색 (config/skill_motion.yml inks) ──────────────────────────
MEOK = (46, 49, 54)
CHEONGHOE = (124, 143, 152)
HOEBAEK = (178, 181, 178)
CHEONGBAEK = (226, 240, 238)

# ── 호의 기하 (모델 단위: 16 = 1블록) ───────────────────────────────────
#
# ★★ 검의 궤적은 **평평한 초승달**이다 (2026-07-21 · 사용자 교정).
#   앞서 「뒤에서도 보여야 한다」는 데 집착해 원통(울타리)으로 만들었다 — 그건 궤적이 아니라 통이다.
#   날이 지나간 자리는 얇은 판이라: **궤적면을 마주 보면 넓고(초승달), 모서리로 보면 얇다.**
#   그 얇음은 버그가 아니라 **검의 궤적이라는 증거**다. 뒤에서 얇아지는 것도 정상이다.
# ★★★ 검의 궤적 = **혜성 꼴 붓질** (2026-07-21 · 사용자 재교정).
#   "검의 궤적은 시작이 검의 모습에서 흘러가는 형태여야 하는데 지금은 초승달을 고수하는 느낌."
#   맞다 — 「깊이 있는 초승달 조각」이라는 틀에 갇혀 있었다. 진짜 검기는:
#     · 머리(날이 지금 있는 곳)는 **밝고 날카로운 날의 모습** — 여기서 궤적이 시작된다
#     · 거기서 **뒤로 흘러나가며** 얇아지고 흩어진다 (기가 남긴 자리)
#   그래서 대칭 초승달이 아니라 **혜성/붓질**이다: 뾰족한 머리 → 부푼 몸 → 흩날리는 꼬리.
SEGMENTS = 20          # 조각 수 — 긴 붓질을 매끄럽게
RADIUS = 26.0          # 호의 반지름 (≈1.6블록)
SWEEP = 172.0          # ★무는 각을 넓혀 획을 길게 (검이 크게 벤다)
DEPTH_MAX = 11.0       # ★혜성 몸통의 최대 폭 (방사 방향) — 머리 뒤가 가장 부푼다
THIN = 0.5             # 판의 두께 — 얇다 (모서리에서 얇게 보인다)
TEX = 32               # 텍스처 한 변

# ── 애니메이션 시간 (초) — 검이 호를 달리는 리듬 ────────────────────────────
#   ★ 흐름을 **길게** 한다 (옛 0.33초는 획이 안 남았다). 날이 천천히 크게 긋고, 꼬리가 길게 흐른다.
SWING_T = 0.36         # 날이 시작→끝을 긋는 시간
TRAIL = 0.42           # ★꼬리가 흐르는 시간 — 이 값이 「흘러나가는 획」의 길이다 (길게)
FADE = 0.16            # 꼬리가 흩어지는 시간
ANIM_LEN = SWING_T + TRAIL + FADE   # 전체 (≈0.94초)


def _uid() -> str:
    return str(uuid.uuid4())


# ★ 채도를 살린 청회 — 등록부 청회(chroma 28)는 낮에 회색과 안 갈렸다 (실측 chroma 7).
#   등록부 색을 **버리는 게 아니라 대비를 키운다**: 인선은 청백, 심은 청회를 **더 진하게**,
#   배는 먹. hue 는 그대로 청회 계열(≈200°)에 두되 채도를 올려 「기가 실린 날」로 읽히게.
CHEONGHOE_DEEP = (86, 128, 148)   # 청회를 진하게 (chroma 62 — 등록부 청록 수준, hue 199° 유지)


def _texture() -> str:
    """세로 램프 — 위가 인선(밝은 청백), 아래로 청회 심 → 먹 배. 대비를 세워 낮에도 갈리게."""
    im = Image.new("RGBA", (TEX, TEX), (0, 0, 0, 0))
    px = im.load()
    for y in range(TEX):
        u = y / (TEX - 1)
        # 위 절반을 밝은 심(청백→진한 청회)에 넓게 준다 — 검기는 인선이 주역이다
        if u < 0.30:
            c = _lerp(CHEONGBAEK, CHEONGHOE_DEEP, u / 0.30)       # 인선 → 진한 청회
        elif u < 0.72:
            c = _lerp(CHEONGHOE_DEEP, CHEONGHOE, (u - 0.30) / 0.42)  # 심
        else:
            c = _lerp(CHEONGHOE, MEOK, (u - 0.72) / 0.28)         # 배(먹)로 짧게 떨어진다
        a = int(252 * (1.0 - max(0.0, (u - 0.86) / 0.14) * 0.80))
        for x in range(TEX):
            px[x, y] = (c[0], c[1], c[2], a)
    buf = io.BytesIO()
    im.save(buf, format="PNG")
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


def _lerp(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def build() -> dict:
    """호를 따라 조각을 늘어놓는다. 조각마다 뼈 하나 — 그래야 **자라는 애니메이션**을 걸 수 있다."""
    elements, outliner, animators = [], [], {}
    half = math.radians(SWEEP) / 2.0
    # 조각 하나가 무는 각. 살짝 겹치게 해서 이음매가 벌어지지 않게 한다
    step = math.radians(SWEEP) / SEGMENTS
    seg_len = 2.0 * RADIUS * math.tan(step / 2.0) * 1.35

    for i in range(SEGMENTS):
        th = -half + step * (i + 0.5)
        # ★ 조각을 **평평한 초승달**의 한 마디로 놓는다 — 궤적면은 XZ(수평)에 눕고, 판은 Y로 얇다.
        #   궤적면을 위에서 마주 보면 초승달이 넓게, 앞(모서리)에서 보면 얇게 보인다 (검의 궤적).
        #   깊이(방사 폭)는 가운데가 깊고 양 끝이 뾰족하다 → 초승달·콤마 꼴.
        cx = math.sin(th) * RADIUS
        cz = math.cos(th) * RADIUS
        # ★ **혜성 꼴** — 머리(날)는 뾰족하고, 그 바로 뒤가 가장 부풀며, 꼬리로 갈수록 얇게 흩어진다.
        #   f: 0=흩날리는 꼬리 … 1=날 끝(머리). 검의 궤적은 머리에서 「시작해」 꼬리로 「흘러나간다」.
        #     · 머리(f≈1): 날카롭게 좁힌다 — 검의 날이 여기 있다
        #     · 어깨(f≈0.72): 가장 부푼다 — 날 바로 뒤의 기 뭉치
        #     · 꼬리(f→0): 얇은 실오라기로 사그라든다 (흘러나간 자리)
        f = i / max(1, SEGMENTS - 1)
        if f > 0.80:                                        # 머리 — 날카롭게 좁힌다
            prof = 1.0 - (f - 0.80) / 0.20 * 0.70
        else:                                               # 어깨→꼬리 — 부풀었다 실처럼 사그라든다
            prof = (f / 0.80) ** 0.85                       # 꼬리에서 0 에 가깝게 (뾰족한 실)
        depth = max(0.35, DEPTH_MAX * prof)
        eid, gid = _uid(), _uid()
        elements.append({
            "name": f"seg{i}", "type": "cube", "uuid": eid,
            # x = 접선(길이), y = **두께(얇다)**, z = 방사 깊이(초승달의 폭)
            "from": [cx - seg_len / 2.0, -THIN / 2.0, cz - depth],
            "to": [cx + seg_len / 2.0, THIN / 2.0, cz],
            "origin": [cx, 0.0, cz],
            # 수평면이니 회전축은 Y. 접선 각 th 만큼 마디를 돌려 호에 붙인다.
            "rotation": [0.0, math.degrees(th), 0.0],
            # ★ 넓은 면은 **위·아래**(up/down) — 궤적면이다. 램프를 방사(깊이) 방향에 물린다:
            #   바깥(호의 가장자리=날 끝)이 밝고, 안(몸쪽)으로 짙어진다. z 가 깊이축이니 UV v 가 그것.
            "faces": {
                "up":    {"uv": [0, 0, TEX, TEX], "texture": 0},
                "down":  {"uv": [0, 0, TEX, TEX], "texture": 0},
                "north": {"uv": [0, 0, TEX, 2], "texture": 0},   # 얇은 테두리 (바깥 끝)
                "south": {"uv": [0, 0, TEX, 2], "texture": 0},
                "east":  {"uv": [0, 0, 2, TEX], "texture": 0},
                "west":  {"uv": [0, 0, 2, TEX], "texture": 0},
            },
        })
        outliner.append({
            "name": f"seg{i}", "uuid": gid, "isOpen": False,
            "origin": [cx, 0.0, cz],
            "children": [eid],
        })
        # ★★ **검이 호를 따라 달린다** — 날(머리)이 시작→끝으로 훑고, 지나간 자리에 꼬리가 남아 흐른다.
        #   레퍼런스가 말한 「시작 위치 → 베는 흐름 → 전체 흐름」이 이 **달리는 콤마**로 표현된다:
        #   어느 순간이든 화면엔 날 끝(밝은 머리) + 뒤로 늘어진 꼬리(스러지는) 만 보인다 = 검이 베는 모습.
        #   (옛 애니는 「통째로 나타났다 사라짐」이라 방향도 흐름도 없었다 — 사용자 교정.)
        #   ★ 키프레임 시간은 **초** 단위다 (length=SWING+TRAIL+FADE). 옛 값은 length(0.9)를 넘겨 잘렸다.
        arrive = SWING_T * f                                # 날이 이 마디에 닿는 순간
        gone = arrive + TRAIL                               # 날이 지나가고 꼬리가 스러지기 시작
        animators[gid] = {
            "name": f"seg{i}", "type": "bone",
            "keyframes": [
                _kf("scale", 0.0, [0.0, 0.0, 0.0]),
                _kf("scale", max(0.001, arrive), [0.0, 0.0, 0.0]),         # 날이 오기 전엔 없다
                _kf("scale", arrive + 0.035, [1.0, 1.0, 1.35]),           # 닿는 순간 날 끝이 번쩍(깊이로 부풀)
                _kf("scale", arrive + 0.08, [1.0, 1.0, 1.0]),             # 제 크기 (꼬리로 남는다)
                _kf("scale", gone, [1.0, 1.0, 1.0]),                      # 꼬리로 흐르는 동안 유지
                _kf("scale", gone + FADE, [1.0, 1.0, 0.0]),               # 안쪽부터 스러진다
            ],
        }

    return {
        "meta": {"format_version": "4.5", "model_format": "free", "box_uv": False},
        "name": "kigi_arc",
        "model_identifier": "kigi_arc",
        "resolution": {"width": TEX, "height": TEX},
        "elements": elements,
        "outliner": outliner,
        "textures": [{
            "id": "0", "name": "kigi.png", "folder": "", "namespace": "", "particle": False,
            "render_mode": "emissive",       # 검기는 스스로 빛난다 (판의 brightness 15 와 같은 뜻)
            "render_sides": "double",        # ★ 양면 — 뒤에서 반대쪽 면을 보게 하는 값이다
            "width": TEX, "height": TEX, "uv_width": TEX, "uv_height": TEX,
            "uuid": _uid(), "source": _texture(),
        }],
        "animations": [{
            "uuid": _uid(), "name": "grow", "loop": "once", "override": False,
            "length": round(ANIM_LEN, 3), "snapping": 24, "animators": animators,
        }],
    }


def _kf(channel, t, val):
    return {"channel": channel, "uuid": _uid(), "time": round(t, 3),
            "interpolation": "linear",
            "data_points": [{"x": str(val[0]), "y": str(val[1]), "z": str(val[2])}]}


def main():
    out = Path("run/mvt-test/plugins/BetterModel/models/kigi_arc.bbmodel")
    out.parent.mkdir(parents=True, exist_ok=True)
    d = build()
    out.write_text(json.dumps(d, ensure_ascii=False), encoding="utf-8")
    print(f"  구웠다: {out}  ({out.stat().st_size // 1024}KB)")
    print(f"  조각 {SEGMENTS}개 · 반지름 {RADIUS/16:.2f}블록 · 무는 각 {SWEEP}도 · 두께 {THIN}(얇다) · 깊이 {DEPTH_MAX}")
    print(f"  애니메이션: grow ({ANIM_LEN:.2f}초 · 날이 호를 달리고 꼬리가 흐른다)")


if __name__ == "__main__":
    main()
