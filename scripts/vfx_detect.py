#!/usr/bin/env python3
"""시각효과 **검출기** — 그리고 그 눈을 시험하는 눈.

★ 왜 따로 떼어냈는가
검출 로직이 `kigi_autotest.analyze` 와 `kigi_cam_test.analyze_masked` 두 곳에 복사돼 있었다.
문턱을 한 곳에서 고치면 다른 곳은 옛 문턱으로 계속 재고, 그 차이는 **조용하다** —
숫자는 나오고, 표는 채워지고, 아무도 안 틀렸다고 말해 주지 않는다.
게다가 그 로직은 **한 번도 시험받은 적이 없었다.** 실제로 옛 문턱(g>150)은 검기(g≈86)를
한 픽셀도 못 잡았고, 그 0px 를 근거로 「뒤에서 58배 작아진다」는 거짓 결론이 나왔다.

⇒ 검출을 하나의 함수로 모으고, **알려진 정답이 든 합성 이미지**로 매 실행마다 시험한다.
   틀리면 측정을 거부한다. 이 저장소의 관례와 같다:
   *"눈을 시험하는 눈이 통과할 때만 「위반 0건」을 믿어라."*

시험 항목 (`selftest()`):
  1. 알려진 색·면적의 도형   → 검출 면적이 정답의 ±10% 안인가
  2. 배경(흰 무대 + 하늘)만  → 0 이 나오는가 (오검출 없음)
  3. HUD 초록 글자 모사      → 정적 마스크로 걸러지는가

단독 실행:
    python3 scripts/vfx_detect.py          # 자가 시험만 돌린다 (통과=0 · 실패=1)
"""

from __future__ import annotations

import sys
from pathlib import Path

# ── 검기 초록 문턱 — **여기가 유일한 정본이다** ───────────────────────────────
#   실측(2026-07-20): 검기 평균 RGB(49,86,61) · g 44~129 · g−r 21~47 · g−b 16~31.
#   밝은 형광이 아니라 **어두운 이끼색**이다. 옛 문턱(150,45,35)은 한 픽셀도 못 잡았다.
#   흰 무대(207,213,214)는 g−r≈6, 하늘(128,178,255)은 g−b<0 이라 아래 문턱에 안 걸린다.
G_MIN, GR_MIN, GB_MIN = 40, 18, 12


def green_mask(arr, g_min=None, gr_min=None, gb_min=None):
    """초록(검기) 마스크. arr 은 HxWx3 정수 배열(RGB).

    ★ 문턱을 **부를 때** 모듈 전역에서 읽는다 (기본인자로 굳히지 않는다).
      그래야 자가시험을 시험할 수 있다 — 문턱을 일부러 틀리게 바꿔 놓고
      `selftest()` 가 **정말로 실패하는지** 확인하는 길이 열린다.
      (통과만 하는 시험은 시험이 아니다. 저장소 관례: 눈을 시험하는 눈에도 self-test 가 있다.)
    """
    g_min = G_MIN if g_min is None else g_min
    gr_min = GR_MIN if gr_min is None else gr_min
    gb_min = GB_MIN if gb_min is None else gb_min
    r, g, b = arr[..., 0], arr[..., 1], arr[..., 2]
    return (g > g_min) & (g - r > gr_min) & (g - b > gb_min)


def measure(mask):
    """마스크 하나에서 면적·중심·bbox 를 뽑는다. 빈 마스크면 None."""
    import numpy as np
    area = int(mask.sum())
    if area == 0:
        return None
    ys, xs = np.nonzero(mask)
    return {"area": area,
            "cx": int(xs.mean()), "cy": int(ys.mean()),
            "x0": int(xs.min()), "x1": int(xs.max()),
            "y0": int(ys.min()), "y1": int(ys.max())}


def measure_frame(path: Path, exclude=None, min_area: int = 0):
    """프레임 한 장 → 검출 결과(dict) 또는 None (min_area 미만이면 None).

    exclude: 정적 마스크(HUD 등). True 인 자리는 세지 않는다.
    """
    import numpy as np
    from PIL import Image
    a = np.asarray(Image.open(path).convert("RGB")).astype(np.int16)
    mask = green_mask(a)
    if exclude is not None:
        mask &= ~exclude
    m = measure(mask)
    if m is None or m["area"] < min_area:
        return None
    return m


def build_static_mask(arrays, dilate=1):
    """유휴 프레임 여러 장에서 **붙박이 초록**(HUD·경험치 바)의 마스크를 만든다.

    ★ 왜: 첫 카메라 촬영에서 182프레임 **전부**가 「검출」로 나왔다 — 면적도 bbox 도 한 픽셀 안
      틀리고 똑같은 243px. 검기가 아니라 HUD 의 초록 글자(「막기」)였다. 검기는 그 판에 한 번도
      안 떴는데(팩이 없었다) 표는 「6회 전부 검출」이라 말할 뻔했다.
    ⇒ 정지 화면의 초록을 모아 **빼고 센다.** 검기는 움직이고 HUD 는 붙박이다.
      글자 앤티앨리어싱·바 애니메이션의 1px 흔들림까지 삼키게 한 칸 부풀린다.
    """
    import numpy as np
    acc = None
    for a in arrays:
        m = green_mask(a)
        acc = m if acc is None else (acc | m)
    if acc is None:
        return None
    d = acc.copy()
    for _ in range(dilate):
        cur = d.copy()
        for ax in (0, 1):
            for s in (-1, 1):
                d |= np.roll(cur, s, axis=ax)
    return d


# ══════════════════════════════════════════════════════════════════
#  눈을 시험하는 눈 — 합성 이미지로 검출기를 검사한다
# ══════════════════════════════════════════════════════════════════
# 합성 화면에 쓰는 색 — 실측에서 그대로 가져왔다 (짐작이 아니다)
BG_STAGE = (207, 213, 214)      # 흰 콘크리트 무대
BG_SKY = (128, 178, 255)        # 낮 하늘
KIGI = (49, 86, 61)             # 검기 초록 (실측 평균)
HUD_GREEN = (85, 255, 85)       # 마인크래프트 HUD 초록 글자(§a)

W, H = 320, 180                 # 시험용 작은 화면 (빠르다 · 판정은 비율로 한다)


def _canvas():
    """배경만 있는 화면 — 위쪽은 하늘, 아래쪽은 흰 무대."""
    import numpy as np
    a = np.zeros((H, W, 3), dtype=np.int16)
    a[: H // 2] = BG_SKY
    a[H // 2:] = BG_STAGE
    return a


def _draw_disc(a, cx, cy, r, rgb):
    """반지름 r 의 원반을 그린다. **정답 면적**(픽셀 수)을 돌려준다 — 공식이 아니라 실제로 센다."""
    import numpy as np
    ys, xs = np.ogrid[:H, :W]
    m = (xs - cx) ** 2 + (ys - cy) ** 2 <= r * r
    a[m] = rgb
    return int(m.sum())


def _draw_hud_text(a, x0, y0):
    """HUD 초록 글자 모사 — 5x7 픽셀 글자 몇 개를 늘 같은 자리에 찍는다."""
    truth = 0
    for k in range(4):                       # 글자 4개
        for row in range(7):
            for col in range(5):
                if (row + col + k) % 3 == 0:  # 획처럼 성긴 무늬
                    a[y0 + row, x0 + k * 7 + col] = HUD_GREEN
                    truth += 1
    return truth


def selftest(verbose=True):
    """매 실행 전에 부른다. (통과 여부, 출력 줄들) 을 돌려준다."""
    import numpy as np
    lines = []
    ok = True

    def say(s):
        lines.append(s)
        if verbose:
            print(s)

    say("  [자가시험] 검출기 — 알려진 정답으로 눈을 시험한다")

    # ① 알려진 색·면적의 도형 → ±10% 안인가
    a = _canvas()
    truth = _draw_disc(a, 160, 100, 28, KIGI)
    got = int(green_mask(a).sum())
    err = abs(got - truth) / truth * 100 if truth else 100.0
    passed = err <= 10.0
    ok &= passed
    say(f"    ① 도형 검출     : 정답 {truth}px · 검출 {got}px · 오차 {err:.1f}%  "
        f"{'통과' if passed else '★실패 (±10% 밖)'}")

    # ①-b 두 배 큰 도형도 비례해서 잡는가 (면적 비교가 의미를 가지려면)
    a2 = _canvas()
    truth2 = _draw_disc(a2, 160, 100, 56, KIGI)
    got2 = int(green_mask(a2).sum())
    ratio = (got2 / got) if got else 0.0
    expect = truth2 / truth if truth else 0.0
    passed = abs(ratio - expect) / expect * 100 <= 10.0 if expect else False
    ok &= passed
    say(f"    ①-b 비례        : 정답비 {expect:.2f}배 · 검출비 {ratio:.2f}배  "
        f"{'통과' if passed else '★실패'}")

    # ② 배경만 → 0 이어야 한다 (오검출 없음)
    got = int(green_mask(_canvas()).sum())
    passed = got == 0
    ok &= passed
    say(f"    ② 배경 오검출   : {got}px  {'통과 (0)' if passed else '★실패 — 배경을 효과로 센다'}")

    # ③ HUD 초록 글자 → 정적 마스크로 걸러지는가
    base = _canvas()
    hud_truth = _draw_hud_text(base, 20, 20)
    idle = [base.copy() for _ in range(4)]          # 유휴 프레임: HUD 만 있다
    static = build_static_mask(idle)
    raw = int(green_mask(base).sum())
    left = int((green_mask(base) & ~static).sum())
    passed = raw >= hud_truth * 0.9 and left == 0
    ok &= passed
    say(f"    ③ HUD 제거      : 글자 {hud_truth}px 를 {raw}px 로 잡고 → 정적 마스크 후 {left}px  "
        f"{'통과' if passed else '★실패 — HUD 를 효과로 센다'}")

    # ③-b 정적 마스크가 **효과까지** 지워 버리지는 않는가 (과잉 제거 검사)
    live = base.copy()
    eff_truth = _draw_disc(live, 200, 120, 24, KIGI)   # HUD 와 겹치지 않는 자리
    left = int((green_mask(live) & ~static).sum())
    err = abs(left - eff_truth) / eff_truth * 100 if eff_truth else 100.0
    passed = err <= 10.0
    ok &= passed
    say(f"    ③-b 과잉제거    : 효과 {eff_truth}px 중 {left}px 남음 · 오차 {err:.1f}%  "
        f"{'통과' if passed else '★실패 — 정적 마스크가 효과를 먹는다'}")

    # ④ measure() 의 좌표가 맞는가 — 면적만 맞고 자리가 틀리면 궤적 결론이 거짓이 된다
    a = _canvas()
    _draw_disc(a, 240, 60, 20, KIGI)
    m = measure(green_mask(a))
    passed = m is not None and abs(m["cx"] - 240) <= 2 and abs(m["cy"] - 60) <= 2
    ok &= passed
    got = f"({m['cx']},{m['cy']})" if m else "없음"
    say(f"    ④ 중심 좌표     : 정답 (240,60) · 검출 {got}  {'통과' if passed else '★실패'}")

    say(f"  [자가시험] {'전부 통과 — 이 눈으로 잰 숫자를 믿어도 된다' if ok else '★ 실패 — 측정을 거부한다'}")
    return ok, lines


if __name__ == "__main__":
    good, _ = selftest()
    sys.exit(0 if good else 1)
