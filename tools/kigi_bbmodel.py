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
SEGMENTS = 16          # 조각 수 — 매끄러운 호
RADIUS = 24.0          # 호의 반지름 (≈1.5블록)
SWEEP = 155.0          # 무는 각(도)
DEPTH_MAX = 9.0        # ★초승달의 **깊이**(방사 방향 폭) — 가운데가 깊고 양 끝이 뾰족하다
THIN = 0.5             # ★판의 두께 — **얇다.** 이것이 모서리에서 얇게 보이게 하는 값이다
TEX = 32               # 텍스처 한 변


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
        # 끝을 뾰족하게: 가운데(th=0)에서 깊고 양 끝에서 0 으로 (sin 곡선의 뿌리)
        depth = DEPTH_MAX * (math.cos(th / half * (math.pi / 2.0)) ** 0.6)
        depth = max(0.6, depth)
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
        # ★ **베기 한 번**의 애니 — 조각이 궤적을 따라 차례로 그어지고(앞), 꼬리부터 사라진다(뒤).
        #   한 번 베는 것이지 「자라서 서 있는」 게 아니다 (실측: 옛 grow 는 대부분 정지라 촬영에 안 잡혔다).
        #   f=0.55 까지 다 그어지고, f=0.62 부터 앞 조각(먼저 그은)부터 스러진다.
        t_in = 0.55 * i / max(1, SEGMENTS - 1)              # 그어지는 시점 (머리가 앞선다)
        t_out = 0.62 + 0.30 * i / max(1, SEGMENTS - 1)      # 스러지는 시점 (꼬리가 뒤에 남는다)
        animators[gid] = {
            "name": f"seg{i}", "type": "bone",
            "keyframes": [
                _kf("scale", 0.0, [0.0, 0.0, 0.0]),
                _kf("scale", max(0.001, t_in), [0.0, 0.0, 0.0]),
                _kf("scale", min(t_in + 0.07, 0.54), [1.15, 1.15, 1.15]),   # 그어지며 살짝 부풀고
                _kf("scale", min(t_in + 0.14, 0.60), [1.0, 1.0, 1.0]),      # 제 크기로
                _kf("scale", min(t_out, 0.98), [1.0, 1.0, 1.0]),
                _kf("scale", min(t_out + 0.12, 1.0), [1.0, 0.0, 1.0]),      # 세로로 스러진다
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
            "length": 0.9, "snapping": 24, "animators": animators,
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
    print(f"  애니메이션: grow ({0.9}초 · 궤적을 따라 그어지고 스러진다)")


if __name__ == "__main__":
    main()
