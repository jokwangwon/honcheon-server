#!/usr/bin/env python3
"""시각효과 **채점기** — 관측을 명세와 대조해 판정하고, 어느 쪽으로 고칠지 말한다.

★ 왜 이것이 있는가 (2026-07-20 · 자동 루프의 빠진 조각)

하네스에는 눈(`vfx_detect`)과 손(핫 리로드·카메라 봇)이 있었다. 그런데 **"이게 맞나"를
답하는 층**이 없었다. 그래서 숫자는 나오는데 그 숫자가 좋은지 나쁜지는 늘 사람이 봐야 했고,
루프가 닫히지 않았다.

이 파일이 그 자리다. 하는 일은 셋이다:

  ① **판정**   — 관측이 명세의 필수/금지 조건을 만족하는가 (항목별 통과/미달)
  ② **힌트**   — 미달이면 **어느 키를 어느 쪽으로** 움직여야 하는가
                 (예: "back 900px < 1500 — scale 을 ×1.29 하라. 면적 ∝ scale²")
  ③ **닮음**   — 레퍼런스가 있으면 **구조 유사도**로 점수를 낸다
                 (픽셀 일치를 요구하지 않는다 — 렌더 조건이 다르다. 실루엣의 모양만 본다)

★ 이 채점기가 지켜야 할 것 (저장소 관례)
  · **조용히 틀리지 않는다** — 레퍼런스 마스크가 비었거나 화면을 통째로 먹으면
    유사도 0 을 뱉지 않고 **못 쟀다고 말한다**. 0 은 「안 닮았다」로 읽히기 때문이다.
  · **눈을 시험하는 눈** — 합성 관측값으로 매 실행 자가시험한다 (`selftest`).
    통과만 하는 시험은 시험이 아니다: 틀린 판정·틀린 힌트 방향까지 잡는다.
  · **명세에 없는 키는 건드리라고 말하지 않는다** — 힌트는 `조절키` 안에서만 나온다.

단독 실행:
    python3 scripts/vfx_judge.py                       # 자가시험 (통과=0 · 실패=1)
    python3 scripts/vfx_judge.py --spec specs/x.yml --obs 관측.json
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


# ══════════════════════════════════════════════════════════════════
#  명세 — 기계가 읽는 목표
# ══════════════════════════════════════════════════════════════════
def load_spec(path):
    """명세 YAML 을 읽는다. 형식이 어긋나면 **여기서** 죽는다 (루프 중간이 아니라)."""
    import yaml
    spec = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    if not isinstance(spec, dict):
        raise SystemExit(f"명세가 사전(dict)이 아니다: {path}")
    for must in ("스킬", "조절키"):
        if must not in spec:
            raise SystemExit(f"명세에 '{must}' 가 없다: {path}")
    if not spec.get("필수") and not spec.get("금지"):
        raise SystemExit(f"명세에 '필수' 도 '금지' 도 없다 — 채점할 것이 없다: {path}")
    tunable = list(spec["조절키"])
    ranges = spec.get("범위", {}) or {}
    for k in ranges:
        if k not in tunable:
            raise SystemExit(f"명세의 '범위' 에 조절키가 아닌 키가 있다: {k}")
    return spec


# ── 비교식 파서 — ">=1500" · "<=60000" · "1500" ────────────────────
_CMP = re.compile(r"^\s*(>=|<=|>|<|==|=)?\s*(-?\d+(?:\.\d+)?)\s*(px|틱)?\s*$")


def parse_cmp(raw):
    """">=1500" → ("&gt;=", 1500.0). 기본 연산자는 >= (가시성 명세가 대부분 하한이라)."""
    m = _CMP.match(str(raw))
    if not m:
        raise SystemExit(f"비교식을 못 읽었다: {raw!r} (예: '>=1500' · '<=60000')")
    op = m.group(1) or ">="
    return ("==" if op == "=" else op), float(m.group(2))


def _satisfied(op, got, want):
    return {">=": got >= want, "<=": got <= want, ">": got > want,
            "<": got < want, "==": got == want}[op]


def _closeness(op, got, want):
    """0~1 — **얼마나 가까운가**. 정체(개선 없음) 판정이 이 값으로 돈다.

    통과면 1.0. 미달이면 모자란 비율. (통과/미달의 이분법만으로는 "나아지고 있는가"를
    못 잰다 — 900→1400 은 둘 다 미달이지만 분명히 나아진 것이다.)
    """
    if _satisfied(op, got, want):
        return 1.0
    if want == 0:
        return 0.0
    if op in (">=", ">"):
        return max(0.0, min(1.0, got / want))
    if op in ("<=", "<"):
        return max(0.0, min(1.0, want / got)) if got else 1.0
    return 0.0


# ══════════════════════════════════════════════════════════════════
#  영향표 — 어느 키가 어느 지표를 어느 쪽으로 미는가
# ══════════════════════════════════════════════════════════════════
#
# ★ 이 표는 **짐작이 아니라 기하**다 (SkillDisplay.kigiSlash 가 값을 쓰는 방식에서 나왔다):
#   · scale        — 초승달의 크기(m). 화면 면적은 대략 **제곱**으로 는다 ⇒ ×√(목표/현재)
#   · blade_pitch  — 날의 눕힘. 90 이면 판이 카메라를 향해 눕고(투영 면적 최대),
#                    0 이면 모로 서서(edge-on) 거의 선으로 보인다 ⇒ 90 에 가까울수록 넓다
#   · orbit_radius — 공전 반경. 크면 몸에서 멀어져 화면 밖으로 나가거나 원근으로 작아진다
#   · draw_ticks   — 공전 보간 시간 = 검기가 떠 있는 시간의 몸통
#   · fade_ticks   — 꼬리가 사라지는 시간 (지속의 나머지)
#
# ★ 그러나 표를 **믿기만 하지는 않는다**. 루프는 이 방향으로 한 번 밀어 보고,
#   지표가 반대로 움직이면 방향을 뒤집는다 (vfx_loop 의 경험적 되먹임).
#   표는 첫 수를 고르는 데 쓰고, 진실은 실측이 정한다.
INFLUENCE = {
    "면적": [
        {"키": "kigi_slash.scale", "쪽": +1, "힘": "강",
         "왜": "초승달 크기 — 화면 면적은 대략 scale²"},
        {"키": "kigi_slash.blade_pitch_deg", "쪽": "→90", "힘": "중",
         "왜": "날의 눕힘 — 90 이면 판이 카메라를 향해 눕는다(투영 면적 최대)"},
        {"키": "kigi_slash.orbit_radius", "쪽": -1, "힘": "약",
         "왜": "공전 반경 — 작으면 몸 가까이 머물러 화면 안에 크게 남는다"},
    ],
    "지속": [
        {"키": "kigi_slash.draw_ticks", "쪽": +1, "힘": "강",
         "왜": "공전 보간 시간 — 검기가 떠 있는 시간의 몸통"},
        {"키": "kigi_slash.fade_ticks", "쪽": +1, "힘": "중",
         "왜": "꼬리가 사라지는 시간"},
    ],
}


def _hint_for(metric, need_more, tunable, got=None, want=None):
    """이 지표를 키우려면(need_more) / 줄이려면 어느 키를 어느 쪽으로.

    ★ `tunable` 밖의 키는 **말하지 않는다.** 명세가 허락하지 않은 손잡이를 권하면
      루프가 못 만지는 것을 권하는 셈이고, 사람에게는 거짓 처방이 된다.
    """
    out = []
    for e in INFLUENCE.get(metric, []):
        if e["키"] not in tunable:
            continue
        쪽 = e["쪽"]
        if 쪽 == "→90":
            목표 = 90.0 if need_more else 0.0
            말 = f"{e['키']} 을 {목표:.0f} 쪽으로"
            방향 = ("→", 목표)
        else:
            up = (쪽 > 0) == need_more
            말 = f"{e['키']} 을 {'키워라' if up else '줄여라'}"
            방향 = ("+" if up else "-", None)
        # 정량 힌트 — 면적은 scale² 이므로 필요한 배수를 계산해 준다 (짐작이 아니라 셈)
        배수 = None
        if (metric == "면적" and e["키"] == "kigi_slash.scale"
                and got and want and got > 0):
            배수 = math.sqrt(want / got)
            말 += f" (×{배수:.2f} 면 닿는다 — 면적 ∝ scale²)"
        out.append({"키": e["키"], "말": 말, "힘": e["힘"], "왜": e["왜"],
                    "방향": 방향, "배수": 배수})
    return out


# ══════════════════════════════════════════════════════════════════
#  구조 유사도 — 레퍼런스와 **모양**이 닮았나 (픽셀 일치가 아니다)
# ══════════════════════════════════════════════════════════════════
def _largest_component(mask):
    """가장 큰 덩어리만 남긴다 — 에디터 UI 조각·잡티를 떨군다 (scipy 없이).

    BFS 한 번. 마스크가 수만 픽셀이어도 파이썬으로 충분히 빠르다.
    """
    import numpy as np
    h, w = mask.shape
    seen = np.zeros_like(mask, dtype=bool)
    best, best_n = None, 0
    idx = np.argwhere(mask)
    for sy, sx in idx:
        if seen[sy, sx]:
            continue
        stack = [(int(sy), int(sx))]
        seen[sy, sx] = True
        cells = []
        while stack:
            y, x = stack.pop()
            cells.append((y, x))
            for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                ny, nx = y + dy, x + dx
                if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                    seen[ny, nx] = True
                    stack.append((ny, nx))
        if len(cells) > best_n:
            best_n, best = len(cells), cells
    out = np.zeros_like(mask, dtype=bool)
    if best:
        ys, xs = zip(*best)
        out[list(ys), list(xs)] = True
    return out


def normalize_shape(mask, size=128):
    """실루엣을 **크기·자리와 무관하게** 만든다 — bbox 로 자르고 정사각으로 맞춘다.

    ★ 왜 이렇게 하나: 레퍼런스는 텍스처 편집기 화면이고 우리 것은 6m 밖에서 찍은 인게임
      렌더다. 픽셀도 색도 배율도 자리도 다르다. **같아야 하는 것은 모양(실루엣)뿐**이다.
      ⇒ 자리(평행이동)와 크기(배율)를 지우고 **가로세로비는 남긴다** (비율은 모양의 일부다:
        납작한 초승달과 둥근 원반은 달라야 한다).
    """
    import numpy as np
    from PIL import Image
    if mask is None or not mask.any():
        return None
    ys, xs = np.nonzero(mask)
    crop = mask[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    h, w = crop.shape
    s = size / max(h, w)                    # 긴 변을 size 에 맞춘다 (비율 보존)
    nh, nw = max(1, int(round(h * s))), max(1, int(round(w * s)))
    im = Image.fromarray((crop * 255).astype("uint8")).resize((nw, nh), Image.NEAREST)
    canvas = np.zeros((size, size), dtype=bool)
    y0, x0 = (size - nh) // 2, (size - nw) // 2
    canvas[y0:y0 + nh, x0:x0 + nw] = np.asarray(im) > 127
    return canvas


def _hu(mask):
    """Hu 모멘트(로그) — 회전·배율에 둔한 모양 기술자. IoU 를 견제하는 둘째 눈."""
    import numpy as np
    ys, xs = np.nonzero(mask)
    if len(xs) == 0:
        return None
    x, y = xs.astype(float), ys.astype(float)
    xm, ym = x.mean(), y.mean()
    m00 = float(len(xs))

    def mu(p, q):
        return float((((x - xm) ** p) * ((y - ym) ** q)).sum())

    def nu(p, q):
        return mu(p, q) / (m00 ** (1 + (p + q) / 2.0))

    n20, n02, n11 = nu(2, 0), nu(0, 2), nu(1, 1)
    n30, n03, n21, n12 = nu(3, 0), nu(0, 3), nu(2, 1), nu(1, 2)
    h = [
        n20 + n02,
        (n20 - n02) ** 2 + 4 * n11 ** 2,
        (n30 - 3 * n12) ** 2 + (3 * n21 - n03) ** 2,
        (n30 + n12) ** 2 + (n21 + n03) ** 2,
    ]
    return [math.copysign(math.log10(abs(v) + 1e-30), v) for v in h]


def shape_similarity(mask_a, mask_b):
    """두 실루엣이 얼마나 닮았나 → {IoU, Hu거리, 점수}. 못 재면 None 을 담아 **그렇게 말한다**."""
    import numpy as np
    A, B = normalize_shape(mask_a), normalize_shape(mask_b)
    if A is None or B is None:
        return {"잼": False, "왜": "한쪽 실루엣이 비었다 — 유사도를 못 잰다"}
    inter = int((A & B).sum())
    union = int((A | B).sum())
    iou = inter / union if union else 0.0
    ha, hb = _hu(A), _hu(B)
    hud = (sum(abs(p - q) for p, q in zip(ha, hb)) / len(ha)) if (ha and hb) else None
    # 점수 — IoU 를 주로 쓰고 Hu 로 보정 (Hu 거리 0 이면 1.0, 2 이상이면 0)
    hu점 = max(0.0, 1.0 - (hud / 2.0)) if hud is not None else iou
    점수 = 0.7 * iou + 0.3 * hu점
    return {"잼": True, "IoU": round(iou, 4), "Hu거리": None if hud is None else round(hud, 4),
            "점수": round(점수, 4)}


def reference_mask(ref):
    """명세가 가리키는 레퍼런스에서 **실루엣**을 뽑는다.

    ★ 왜 추출법을 명세가 선언하나: 레퍼런스는 무엇이든 될 수 있다 — 인게임 프레임일 수도,
      텍스처 편집기 화면일 수도 있다(실제로 검기 레퍼런스가 그렇다). 색으로 짐작하면
      **조용히 틀린다**: 검기 초록 문턱으로 그 편집기 화면을 재면 0px 가 나오는데,
      그것은 "안 닮았다"가 아니라 "엉뚱한 자를 댔다"이다.
    ⇒ 어떻게 뽑을지 명세가 적고, 뽑은 결과가 **말이 되는지 검사**한다 (비었거나 화면을
      통째로 먹으면 거부). 못 재면 0 을 주지 않고 「못 쟀다」고 말한다.

    ref: {파일, 마스크: 어두움|초록, 자르기: [y0,x0,y1,x1]}  (문자열이면 파일 경로로 본다)
    """
    import numpy as np
    from PIL import Image
    if isinstance(ref, str):
        ref = {"파일": ref}
    path = ROOT / ref["파일"] if not Path(ref["파일"]).is_absolute() else Path(ref["파일"])
    if not path.exists():
        return None, f"레퍼런스 파일이 없다: {path}"
    a = np.asarray(Image.open(path).convert("RGB")).astype(int)
    crop = ref.get("자르기")
    if crop:
        y0, x0, y1, x1 = crop
        a = a[y0:y1, x0:x1]
    how = ref.get("마스크", "어두움")
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    if how == "어두움":
        # 밝은 바탕(체커·흰 캔버스) 위의 **어둡고 붉은 기 도는** 획 — 검기 레퍼런스가 그렇다
        m = (a.sum(2) < ref.get("밝기상한", 400)) & (r - g > 8) & (r - b > 4)
    elif how == "초록":
        sys.path.insert(0, str(Path(__file__).resolve().parent))
        import vfx_detect as DET
        m = DET.green_mask(a.astype(np.int16))
    else:
        return None, f"모르는 마스크 추출법: {how} (어두움 · 초록)"
    if not m.any():
        return None, f"레퍼런스에서 실루엣을 못 뽑았다 (마스크: {how}) — 0 을 「안 닮았다」로 읽지 마라"
    frac = m.mean()
    if frac > 0.9:
        return None, f"레퍼런스 마스크가 화면의 {frac*100:.0f}% 다 — 배경까지 먹었다. 자르기/문턱을 고쳐라"
    m = _largest_component(m)          # 편집기 UI 조각을 떨군다
    return m, f"{int(m.sum())}px ({how}{' · 자름' if crop else ''})"


# ══════════════════════════════════════════════════════════════════
#  채점 — 항목별 통과/미달 + 총평 + 힌트
# ══════════════════════════════════════════════════════════════════
class Item:
    def __init__(self, 이름, 각도, got, op, want, 단위, ok, 가까움, hints):
        self.이름, self.각도, self.got = 이름, 각도, got
        self.op, self.want, self.단위 = op, want, 단위
        self.ok, self.가까움, self.hints = ok, 가까움, hints

    @property
    def 라벨(self):
        return f"{self.이름}·{self.각도}" if self.각도 else self.이름

    def __str__(self):
        head = "통과" if self.ok else "미달"
        got = f"{self.got:.0f}" if isinstance(self.got, float) else f"{self.got}"
        return (f"  {head}  {self.라벨:<18} {got}{self.단위} "
                f"(요구 {self.op}{self.want:g}{self.단위})")


class Verdict:
    def __init__(self, items, 유사도, 총평, 점수):
        self.items, self.유사도, self.총평, self.점수 = items, 유사도, 총평, 점수

    @property
    def ok(self):
        return all(i.ok for i in self.items) and bool(self.items)

    @property
    def 미달(self):
        return [i for i in self.items if not i.ok]

    def hints(self):
        """미달 항목의 힌트를 **키별로** 모은다 (같은 키를 여러 항목이 가리킬 수 있다)."""
        by = {}
        for it in self.미달:
            for h in it.hints:
                by.setdefault(h["키"], {"키": h["키"], "말": h["말"], "힘": h["힘"],
                                        "왜": h["왜"], "방향": h["방향"],
                                        "배수": h["배수"], "때문에": []})
                by[h["키"]]["때문에"].append(it.라벨)
                # 여러 항목이 같은 키를 가리키면 가장 큰 배수를 따른다 (모자란 쪽에 맞춘다)
                if h["배수"] and (by[h["키"]]["배수"] is None
                                  or h["배수"] > by[h["키"]]["배수"]):
                    by[h["키"]]["배수"] = h["배수"]
                    by[h["키"]]["말"] = h["말"]
        순 = {"강": 0, "중": 1, "약": 2}
        return sorted(by.values(), key=lambda h: 순.get(h["힘"], 9))

    def report(self):
        out = ["", "─" * 74, f"  채점 — {self.총평}", "─" * 74]
        for i in self.items:
            out.append(str(i))
        if self.유사도:
            s = self.유사도
            if s.get("잼"):
                out.append(f"  참고  {'레퍼런스 닮음':<18} IoU {s['IoU']:.3f} · "
                           f"Hu거리 {s['Hu거리']} · 점수 {s['점수']:.3f}")
            else:
                out.append(f"  ⚠     레퍼런스 닮음      못 쟀다 — {s.get('왜')}")
        out.append("─" * 74)
        out.append(f"  충족도 {self.점수:.3f}  ·  통과 {len(self.items) - len(self.미달)}/{len(self.items)}")
        if self.미달:
            out.append("")
            out.append("  고칠 방향 (명세의 조절키 안에서만):")
            for h in self.hints():
                out.append(f"    · {h['말']}")
                out.append(f"        왜: {h['왜']}")
                out.append(f"        때문에: {', '.join(h['때문에'])}")
        out.append("─" * 74)
        return "\n".join(out)


def judge(spec, obs, ref_mask=None, our_mask=None):
    """관측(obs)을 명세(spec)와 대조한다.

    obs = {"fps": 15, "각도": {"back": {"최대면적": px, "지속틱": t, ...}, ...}}
    """
    tunable = list(spec["조절키"])
    items = []
    각도들 = obs.get("각도", {})

    def 관측(angle, field):
        return (각도들.get(angle) or {}).get(field)

    # ── 필수 · 가시성 (각도별 면적 하한) ──────────────────────────
    필수 = spec.get("필수", {}) or {}
    for angle, raw in (필수.get("가시성") or {}).items():
        op, want = parse_cmp(raw)
        got = 관측(angle, "최대면적")
        if got is None:
            # ★ 안 찍힌 각도를 「0px」 로 채우지 않는다 — 그것은 「안 보인다」로 읽힌다
            items.append(Item("가시성", angle, -1, op, want, "px", False, 0.0,
                              [{"키": "-", "말": f"{angle} 각도를 아예 안 쟀다 — 촬영부터 고쳐라",
                                "힘": "강", "왜": "관측에 이 각도가 없다", "방향": ("?", None),
                                "배수": None}]))
            continue
        ok = _satisfied(op, got, want)
        hints = [] if ok else _hint_for("면적", op in (">=", ">"), tunable, got, want)
        items.append(Item("가시성", angle, got, op, want, "px", ok,
                          _closeness(op, got, want), hints))

    # ── 필수 · 지속 (틱) ──────────────────────────────────────────
    지속 = 필수.get("지속") or {}
    if 지속:
        # 지속은 각도마다 같아야 한다 — 대표로 가장 잘 잡힌 각도의 값을 쓴다
        cands = [(a, 관측(a, "지속틱")) for a in 각도들 if 관측(a, "지속틱")]
        got = max((v for _, v in cands), default=None)
        기준각 = max(cands, key=lambda t: t[1])[0] if cands else None
        if got is None:
            items.append(Item("지속", None, -1, ">=", 지속.get("최소틱", 0), "틱", False, 0.0,
                              [{"키": "-", "말": "지속을 못 쟀다 (검출 프레임이 없다)", "힘": "강",
                                "왜": "어느 각도에서도 효과가 안 잡혔다", "방향": ("?", None),
                                "배수": None}]))
        else:
            if "최소틱" in 지속:
                w = float(지속["최소틱"])
                ok = got >= w
                items.append(Item("지속(최소)", 기준각, got, ">=", w, "틱", ok,
                                  _closeness(">=", got, w),
                                  [] if ok else _hint_for("지속", True, tunable)))
            if "최대틱" in 지속:
                w = float(지속["최대틱"])
                # ★ 효과가 촬영 끝까지 남아 있었다면 지속은 **하한**이다 — 상한을 판정할 수 없다.
                #   그때 「42.7틱 ≤ 30틱 실패」라고 적으면 모르는 것을 아는 척하는 것이다.
                #   ⇒ 판정하지 않고 **촬영창을 늘리라고 말한다** (측정을 고쳐야지 값을 고칠 일이 아니다).
                잘림 = bool((각도들.get(기준각) or {}).get("지속잘림"))
                if 잘림:
                    items.append(Item("지속(최대)", 기준각, got, "<=", w, "틱", False, 0.0,
                                      [{"키": "-", "말": f"지속이 잘렸다 (효과가 촬영 끝까지 남았다) — "
                                                        f"측정된 {got:g}틱은 **하한**이다. "
                                                        f"명세의 '촬영초' 를 늘려 다시 재라",
                                        "힘": "강", "왜": "촬영창이 효과보다 짧다 — 값이 아니라 측정을 고쳐야 한다",
                                        "방향": ("?", None), "배수": None}]))
                else:
                    ok = got <= w
                    items.append(Item("지속(최대)", 기준각, got, "<=", w, "틱", ok,
                                      _closeness("<=", got, w),
                                      [] if ok else _hint_for("지속", False, tunable)))

    # ── 금지 · 화면덮음 (상한) ────────────────────────────────────
    금지 = spec.get("금지", {}) or {}
    for angle, raw in (금지.get("화면덮음") or {}).items():
        op, want = parse_cmp(raw)
        if op in (">=", ">"):        # 금지는 상한이다 — 하한으로 적었으면 명세가 틀렸다
            raise SystemExit(f"금지.화면덮음.{angle} 은 상한이어야 한다 (예: '<=60000'): {raw!r}")
        got = 관측(angle, "최대면적")
        if got is None:
            continue                 # 안 잰 각도는 금지 위반을 주장하지 않는다 (조용히 넘긴다)
        ok = _satisfied(op, got, want)
        hints = [] if ok else _hint_for("면적", False, tunable, got, want)
        items.append(Item("화면덮음", angle, got, op, want, "px", ok,
                          _closeness(op, got, want), hints))

    # ── 참고 · 레퍼런스 닮음 (게이트가 아니라 점수다) ──────────────
    유사도 = None
    if ref_mask is not None and our_mask is not None:
        유사도 = shape_similarity(ref_mask, our_mask)
        요구 = (필수.get("유사도") or {}).get("최소")
        if 요구 is not None:
            if not 유사도.get("잼"):
                items.append(Item("유사도", None, -1, ">=", float(요구), "", False, 0.0,
                                  [{"키": "-", "말": f"유사도를 못 쟀다 — {유사도.get('왜')}",
                                    "힘": "강", "왜": "레퍼런스 마스크가 성치 않다",
                                    "방향": ("?", None), "배수": None}]))
            else:
                got = 유사도["점수"]
                ok = got >= float(요구)
                items.append(Item("유사도", None, got, ">=", float(요구), "", ok,
                                  _closeness(">=", got, float(요구)),
                                  [] if ok else _hint_for("면적", True, tunable)))

    점수 = (sum(i.가까움 for i in items) / len(items)) if items else 0.0
    통과 = all(i.ok for i in items) and bool(items)
    총평 = ("전부 충족 — 객관 기준 통과" if 통과
            else f"{len([i for i in items if not i.ok])}건 미달")
    return Verdict(items, 유사도, 총평, 점수)


# ══════════════════════════════════════════════════════════════════
#  눈을 시험하는 눈 — 합성 관측값으로 채점기를 검사한다
# ══════════════════════════════════════════════════════════════════
SPEC_T = {
    "스킬": "시험용",
    "조절키": ["kigi_slash.scale", "kigi_slash.orbit_radius", "kigi_slash.draw_ticks"],
    "필수": {"가시성": {"back": ">=1500", "front": ">=2000"},
             "지속": {"최소틱": 8, "최대틱": 30}},
    "금지": {"화면덮음": {"first": "<=60000"}},
}


def _obs(back=None, front=None, first=None, dur=None, 잘림=False):
    각 = {}
    for name, area in (("back", back), ("front", front), ("first", first)):
        if area is not None:
            각[name] = {"최대면적": area, "지속틱": dur, "지속잘림": 잘림}
    return {"fps": 15, "각도": 각}


def selftest(verbose=True):
    """합성 관측값 → **알려진 판정**. 판정만이 아니라 **힌트 방향**까지 시험한다."""
    import numpy as np
    lines, ok = [], True

    def say(s):
        lines.append(s)
        if verbose:
            print(s)

    def check(label, cond, detail=""):
        nonlocal ok
        ok &= bool(cond)
        say(f"    {label:<24}{'통과' if cond else '★실패'}  {detail}")

    say("  [자가시험] 채점기 — 알려진 관측으로 판정과 힌트를 시험한다")

    # ① 전부 충족 → 통과 · 힌트 없음
    v = judge(SPEC_T, _obs(back=1800, front=2400, first=30000, dur=14))
    check("① 충족", v.ok and not v.hints() and v.점수 == 1.0,
          f"점수 {v.점수:.2f} · 미달 {len(v.미달)}")

    # ② 면적 미달 → 미달 + scale 을 **키우라** + 배수가 √(1500/900) ≈ 1.29
    v = judge(SPEC_T, _obs(back=900, front=2400, first=30000, dur=14))
    h = next((x for x in v.hints() if x["키"] == "kigi_slash.scale"), None)
    good = (not v.ok and h is not None and h["방향"][0] == "+"
            and abs(h["배수"] - math.sqrt(1500 / 900)) < 0.01)
    check("② 면적 미달·힌트", good,
          f"배수 {h['배수']:.3f} (기대 {math.sqrt(1500/900):.3f})" if h else "힌트 없음")

    # ③ 화면덮음 위반 → 미달 + scale 을 **줄이라** (방향이 뒤집혀야 한다)
    v = judge(SPEC_T, _obs(back=1800, front=2400, first=90000, dur=14))
    h = next((x for x in v.hints() if x["키"] == "kigi_slash.scale"), None)
    check("③ 화면덮음·방향", not v.ok and h is not None and h["방향"][0] == "-",
          f"방향 {h['방향'][0] if h else '없음'} (기대 -)")

    # ④ 지속 초과 → draw_ticks 를 **줄이라**
    v = judge(SPEC_T, _obs(back=1800, front=2400, first=30000, dur=40))
    h = next((x for x in v.hints() if x["키"] == "kigi_slash.draw_ticks"), None)
    check("④ 지속 초과·방향", not v.ok and h is not None and h["방향"][0] == "-",
          f"방향 {h['방향'][0] if h else '없음'} (기대 -)")

    # ⑤ 조절키 울타리 — 명세에 없는 키는 **권하지 않는다**
    narrow = dict(SPEC_T, 조절키=["kigi_slash.orbit_radius"])
    v = judge(narrow, _obs(back=900, front=2400, first=30000, dur=14))
    keys = {x["키"] for x in v.hints()}
    check("⑤ 조절키 울타리", "kigi_slash.scale" not in keys and keys,
          f"권한 키 {sorted(keys)}")

    # ⑥ 점수 단조성 — 나아지면 점수가 올라야 한다 (정체 판정이 이것에 걸려 있다)
    s1 = judge(SPEC_T, _obs(back=600, front=2400, first=30000, dur=14)).점수
    s2 = judge(SPEC_T, _obs(back=1200, front=2400, first=30000, dur=14)).점수
    s3 = judge(SPEC_T, _obs(back=1800, front=2400, first=30000, dur=14)).점수
    check("⑥ 점수 단조", s1 < s2 < s3, f"{s1:.3f} < {s2:.3f} < {s3:.3f}")

    # ⑦ 안 잰 각도를 0 으로 채우지 않는다 (「안 보인다」로 읽히면 안 된다)
    v = judge(SPEC_T, _obs(back=1800, first=30000, dur=14))
    it = next((i for i in v.items if i.라벨 == "가시성·front"), None)
    check("⑦ 미측정≠0", it is not None and not it.ok and it.got == -1,
          f"front got={it.got if it else '없음'} (0 이 아니라 -1 = 못 쟀다)")

    # ⑦-b 잘린 지속은 **상한을 판정하지 않는다** — 모르는 것을 아는 척하지 않는다.
    #     (판정은 미달로 두되, 힌트는 「값을 고쳐라」가 아니라 「측정을 고쳐라」여야 한다)
    v = judge(SPEC_T, _obs(back=1800, front=2400, first=30000, dur=42, 잘림=True))
    it = next((i for i in v.items if i.이름 == "지속(최대)"), None)
    h = it.hints[0] if (it and it.hints) else None
    check("⑦-b 잘린 지속", it is not None and not it.ok and h is not None
          and "촬영초" in h["말"] and h["키"] == "-",
          "측정을 고치라고 말한다 (값이 아니라)")
    # 안 잘렸으면 평소대로 값을 고치라고 한다 (draw_ticks 쪽)
    v2 = judge(dict(SPEC_T, 조절키=SPEC_T["조절키"]),
               _obs(back=1800, front=2400, first=30000, dur=42, 잘림=False))
    it2 = next((i for i in v2.items if i.이름 == "지속(최대)"), None)
    check("⑦-c 안 잘린 지속", it2 is not None and not it2.ok
          and any(x["키"] == "kigi_slash.draw_ticks" for x in it2.hints),
          "값을 고치라고 말한다")

    # ⑧ 유사도 — 같은 모양은 높게, 다른 모양은 낮게 (자리·크기가 달라도)
    arc = np.zeros((200, 200), bool)
    yy, xx = np.ogrid[:200, :200]
    r = np.sqrt((xx - 100) ** 2 + (yy - 140) ** 2)
    arc |= (r > 60) & (r < 80) & (yy < 140)                 # 초승달(아치)
    arc2 = np.zeros((300, 300), bool)                        # 같은 모양 · 다른 자리/크기
    yy2, xx2 = np.ogrid[:300, :300]
    r2 = np.sqrt((xx2 - 160) ** 2 + (yy2 - 210) ** 2)
    arc2 |= (r2 > 90) & (r2 < 120) & (yy2 < 210)
    disc = np.zeros((200, 200), bool)
    disc |= ((xx - 100) ** 2 + (yy - 100) ** 2) < 60 ** 2    # 원반 — 분명히 다른 모양
    같음 = shape_similarity(arc, arc2)
    다름 = shape_similarity(arc, disc)
    check("⑧ 유사도 분별", 같음["점수"] > 0.8 and 다름["점수"] < 0.6,
          f"같은모양 {같음['점수']:.3f} · 다른모양 {다름['점수']:.3f}")

    # ⑨ 빈 마스크는 **0 이 아니라 「못 쟀다」** (조용히 틀리지 않는다)
    빈 = shape_similarity(arc, np.zeros((50, 50), bool))
    check("⑨ 빈 마스크 거부", 빈.get("잼") is False, 빈.get("왜", ""))

    say(f"  [자가시험] {'전부 통과 — 이 채점을 믿어도 된다' if ok else '★ 실패 — 채점을 거부한다'}")
    return ok, lines


def main():
    ap = argparse.ArgumentParser(description="시각효과 채점기")
    ap.add_argument("--spec")
    ap.add_argument("--obs", help="관측 JSON")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.spec and a.obs:
        spec = load_spec(a.spec)
        obs = json.loads(Path(a.obs).read_text(encoding="utf-8"))
        v = judge(spec, obs)
        print(v.report())
        sys.exit(0 if v.ok else 1)
    good, _ = selftest()
    sys.exit(0 if good else 1)


if __name__ == "__main__":
    main()
