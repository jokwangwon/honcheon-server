#!/usr/bin/env python3
"""시각효과 **자동 루프** — AI 가 제가 만든 스킬을 보고 스스로 고친다.

★ 무엇을 닫는가 (docs/design/vfx_harness_v2.md 「자동 루프」)

    [명세]  기계가 읽는 목표 (specs/*.yml)
       ↓
    [preflight]  조건이 성한가 — 어긋나면 **즉시 중단** (틀린 눈으로 고치면 디자인이 망가진다)
       ↓
    [적용]  조절키를 테스트 config 에 쓰고 → /혼천 모션 재적재 (0.4초 · 클라를 안 끊는다)
       ↓
    [발동]  명세의 RCON 명령 한 줄 (사람도 xdotool 도 없이)
       ↓
    [관측]  네 각도 촬영 + 측정 (vfx_detect 가 「눈이 성한가」를 보증)
       ↓
    [채점]  vfx_judge 가 명세와 대조 — 항목별 통과/미달 + **고칠 방향**
       ├─ 충족  → 사람 호출 (1차: 그림으로) — "객관 기준 통과. 미감 확인 부탁드립니다"
       ├─ 미달  → 값 조정 → [적용] 로 되돌아간다
       └─ 막힘  → 사람 호출 ("N회 개선 없음 / 상한") + 시도 기록 전부

★ 이 루프가 스스로에게 채운 족쇄
  · **명세에 없는 키는 못 만진다** (`조절키` · `범위` 밖으로 안 나간다)
  · **라이브를 못 만진다** — 테스트(25566 · run/mvt-test)에만 쓴다. 저장소 config/ 는 안 건드린다
  · **주석을 안 죽인다** — YAML 을 통째로 다시 쓰지 않고 **그 값 한 자리만** 고친다
    (skill_motion.yml 은 주석이 정본의 절반이다 — 프로그램이 쓰면 그 절반이 죽는다)
  · **억지로 수렴하지 않는다** — 헛돌면 스스로 인정하고 사람을 부른다

사용:
    python3 scripts/vfx_loop.py --spec specs/검기_평타.yml
    python3 scripts/vfx_loop.py --spec ... --시작값 kigi_slash.scale=0.6   # 일부러 어긋나게
    python3 scripts/vfx_loop.py --selftest
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from kigi_rcon import Rcon  # noqa: E402
import kigi_autotest as AT  # noqa: E402
import kigi_cam_test as CAM  # noqa: E402
import vfx_detect as DET  # noqa: E402
import vfx_judge as JUDGE  # noqa: E402
import vfx_preflight as PF  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
TEST_CONFIG = ROOT / "run" / "mvt-test" / "plugins" / "HoncheonMVT" / "config"
OUT_BASE = ROOT / "작업물" / "루프"
FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"


def say(s=""):
    print(s, flush=True)


# ══════════════════════════════════════════════════════════════════
#  ① 값 쓰기 — **주석을 죽이지 않고** 그 한 자리만 고친다
# ══════════════════════════════════════════════════════════════════
def set_config_value(path: Path, dotted: str, value):
    """`kigi_slash.scale` 같은 점 표기 키의 값 **한 자리만** 바꾼다. 바꾼 옛 값을 돌려준다.

    ★ 왜 yaml.dump 로 다시 쓰지 않나: `skill_motion.yml` 은 2000줄이 넘고 그 절반이 주석이다
      (「왜 이 값인가」가 거기 산다). yaml 라이브러리로 읽어 쓰면 **주석·순서·서식이 전부 사라진다.**
      한 번 죽으면 되살릴 수 없다. 그래서 텍스트를 그대로 두고 **그 줄의 숫자만** 갈아끼운다.
    ★ 그리고 쓴 뒤 **다시 읽어 확인한다** — 썼다고 믿는 것과 써진 것은 다르다.
    """
    import yaml
    section, _, leaf = dotted.rpartition(".")
    if not section:
        raise SystemExit(f"점 표기 키가 아니다: {dotted}")
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)

    # 최상위 절(kigi_slash:)을 찾는다 — 들여쓰기 0
    top = section.split(".")[0]
    start = None
    for i, ln in enumerate(lines):
        if re.match(rf"^{re.escape(top)}\s*:", ln):
            start = i
            break
    if start is None:
        raise SystemExit(f"{path.name} 에 '{top}:' 절이 없다")
    # 그 절의 끝 = 다음 들여쓰기 0 줄
    end = len(lines)
    for i in range(start + 1, len(lines)):
        if lines[i].strip() and not lines[i][0].isspace() and not lines[i].lstrip().startswith("#"):
            end = i
            break

    # 절 안에서 leaf 키 줄을 찾는다 (주석 줄은 건너뛴다)
    pat = re.compile(rf"^(\s*){re.escape(leaf)}(\s*:\s*)([^#\n]*?)(\s*)(#.*)?$")
    hit = None
    for i in range(start + 1, end):
        if lines[i].lstrip().startswith("#"):
            continue
        m = pat.match(lines[i].rstrip("\n"))
        if m:
            hit = (i, m)
            break
    if hit is None:
        raise SystemExit(f"{path.name} 의 '{top}' 절에서 '{leaf}' 를 못 찾았다")

    i, m = hit
    old_raw = m.group(3).strip()
    new_raw = (f"{value:g}" if isinstance(value, float) else str(value))
    lines[i] = f"{m.group(1)}{leaf}{m.group(2)}{new_raw}{m.group(4) or ''}{m.group(5) or ''}\n"
    path.write_text("".join(lines), encoding="utf-8")

    # ★ 확인 — 정말 그 값이 됐는가 (그리고 파일이 여전히 성한 YAML 인가)
    got = JUDGE and yaml.safe_load(path.read_text(encoding="utf-8"))
    cur = got
    for part in dotted.split("."):
        cur = cur[part]
    if abs(float(cur) - float(value)) > 1e-6:
        raise SystemExit(f"값을 썼는데 읽으니 다르다: {dotted} = {cur!r} (쓴 것 {value!r})")
    return old_raw


def read_config_value(path: Path, dotted: str):
    import yaml
    cur = yaml.safe_load(path.read_text(encoding="utf-8"))
    for part in dotted.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return None
        cur = cur[part]
    return cur


# 명령이 **거절당했을 때** 서버가 돌려주는 표식 — 이게 보이면 발동은 일어나지 않았다
REFUSED = ("몸이 있어야", "모르는", "Unknown or incomplete command",
           "관리자의 몫", "숫자가 아니다", "No player was found",
           "That player cannot be found", "등록부가 없다")


def check_trigger(rcon: Rcon, spec):
    """발동 명령이 **정말 먹는가**를 재기 전에 확인한다.

    ★ 왜 이것이 있는가 (2026-07-20 · 이 루프가 세 회차를 헛돈 뒤에 태어났다):
      명세의 발동 줄이 틀려 있었다 (`execute as …` — 페이퍼가 안 받는 형태). 그런데
      **촬영도 측정도 멀쩡히 돌았다.** 검기만 안 떴을 뿐이다. 그래서 하네스는 「0px」 를 뱉었고,
      루프는 그것을 「검기가 너무 작다」로 읽어 scale 을 0.6 → 4.19 까지 키우며 헛돌았다.
      숫자는 계속 나왔고, 표는 채워졌고, 아무도 안 틀렸다고 말해 주지 않았다.

      **0px 는 「효과가 없다」가 아니라 「발동이 안 됐다」일 수 있다.**

    ⇒ 재기 전에 발동을 한 번 쳐 보고, 서버가 **거절했으면 측정하지 않는다.**
      (거절이 아니면 통과시킨다 — 이 명령들은 성공 시 응답을 몸에게만 보내 콘솔엔 빈 줄이 온다.)
    """
    out = rcon.cmd(spec["발동"])
    plain = re.sub(r"§.", "", out or "").strip()
    거절 = [w for w in REFUSED if w in plain]
    if 거절:
        return False, plain
    return True, plain


def hot_reload(rcon: Rcon):
    """/혼천 모션 재적재 — 서버를 안 내리고 등록부를 다시 읽는다. (성공?, 걸린 ms, 요약줄)"""
    t0 = time.time()
    out = rcon.cmd("혼천 모션 재적재")
    took = (time.time() - t0) * 1000
    plain = re.sub(r"§.", "", out)
    ok = "재적재 실패" not in plain
    return ok, took, plain.strip().splitlines()


# ══════════════════════════════════════════════════════════════════
#  ② 관측 — 각도별로 발동시키고 재다
# ══════════════════════════════════════════════════════════════════
#  ★ 「first」(1인칭)만 다른 화면을 본다: 카메라 봇(:98)이 아니라 **휘두르는 봇 자신의 눈**(:99).
#    금지 조건(화면을 덮는가)은 싸우는 사람의 시점에서만 뜻이 있다.
FIRST = "first"


def _rel(p: Path):
    """저장소 안이면 상대경로, 밖이면 절대경로 — 기록이 어디서든 읽히게."""
    p = Path(p).resolve()
    try:
        return str(p.relative_to(ROOT))
    except ValueError:
        return str(p)


def observe_angle(rcon, angle, outdir: Path, spec, fps):
    """한 각도에서 발동시키고 촬영·측정한다 → 관측 dict."""
    outdir = Path(outdir).resolve()
    outdir.mkdir(parents=True, exist_ok=True)
    trigger = spec["발동"]
    if angle == FIRST:
        display = CAM.BOT_DISPLAY                      # 봇 자신의 눈
        bot_ok, dark = True, -1
    else:
        display = CAM.CAM_DISPLAY
        CAM.place_camera(rcon, angle, "spectator")
        time.sleep(2.0)
        bot_ok, dark = CAM.bot_in_frame(CAM.CAM_DISPLAY)

    CAM.grab_one(display, outdir / "무대.png")
    exclude = CAM.static_green(display)                # 붙박이 초록(HUD)을 먼저 재 둔다

    cap = CAM.start_capture(display, outdir, fps)
    try:
        rcon.cmd(trigger)
        time.sleep(spec.get("촬영초", 2.2))
    finally:
        CAM.stop_capture(cap)

    total, rows = AT.analyze(outdir, spec.get("최소면적", 40), exclude=exclude)
    bursts = AT.group_bursts(rows)
    peak = max((r for b in bursts for r in b), key=lambda r: r["area"], default=None)
    big = max(bursts, key=len, default=[])

    # ★ 잘렸는가 — 효과가 **촬영이 끝날 때까지** 남아 있었다면 지속은 「그 값」이 아니라 「하한」이다.
    #   왜 이것이 필요한가 (2026-07-20 · 실측에서 바로 만났다): 촬영창을 2.2초로 두고 쟀더니
    #   마지막 프레임까지 검기가 남아 42.7틱이 나왔다. 그 숫자를 그대로 「지속 42.7틱」이라
    #   적으면 **모르는 것을 아는 척**하는 것이다 (진짜 지속은 그보다 길다).
    #   ⇒ 잘렸으면 소리내어 적고, 채점기에는 「최대틱」 판정을 맡기지 않는다.
    잘림 = bool(big) and int(big[-1]["frame"][6:10]) >= total - 1
    return {
        "촬영프레임": total, "검출프레임": len(rows), "발생횟수": len(bursts),
        "최대면적": peak["area"] if peak else 0,
        "중심": [peak["cx"], peak["cy"]] if peak else None,
        "bbox": [peak["x0"], peak["y0"], peak["x1"], peak["y1"]] if peak else None,
        "지속틱": round(len(big) / fps * 20, 1) if big else 0.0,
        "지속잘림": 잘림,
        "대표프레임": _rel(outdir / peak["frame"]) if peak else None,
        "봇보임": bot_ok, "어두운픽셀": dark,
        "_burst": [r["frame"] for r in big],
    }


def observe(rcon, spec, outdir: Path, fps):
    angles = spec.get("각도") or ["back", "front", "side", "high"]
    obs = {"fps": fps, "각도": {}}
    for a in angles:
        say(f"    [관측] {a} …")
        obs["각도"][a] = observe_angle(rcon, a, outdir / a, spec, fps)
        o = obs["각도"][a]
        say(f"           최대 {o['최대면적']}px · 지속 {o['지속틱']}틱 · "
            f"검출 {o['검출프레임']}/{o['촬영프레임']}프레임")
    return obs


def our_shape_mask(obs, 제외=("first",)):
    """대표 프레임에서 **우리 실루엣**을 뽑는다 (레퍼런스와 견줄 것).

    ★ **가장 크게 잡힌 각도**를 고른다 — 정해진 순서로 고르지 않는다.
      왜 (2026-07-20 · 첫 산출물에서 바로 걸렸다): 처음엔 back 을 먼저 봤는데, 등 뒤에서는
      획이 64px 짜리 **실오라기**로 보인다 (모로 서 있다). 그 실루엣을 레퍼런스의 초승달과
      견주니 IoU 0.05 가 나왔고, 그것은 「안 닮았다」가 아니라 **「모양이 안 보이는 각도에서
      모양을 쟀다」**였다. 모양을 보려면 모양이 보이는 각도에서 봐야 한다.
    ★ 1인칭은 뺀다 — 제 손·무기가 화면을 가려 실루엣이 잘린다.
    """
    import numpy as np
    from PIL import Image
    cands = [(o["최대면적"], a, o) for a, o in obs["각도"].items()
             if a not in 제외 and o.get("대표프레임") and o["최대면적"] > 0]
    for _, a, o in sorted(cands, reverse=True):
        arr = np.asarray(Image.open(ROOT / o["대표프레임"]).convert("RGB")).astype(np.int16)
        m = DET.green_mask(arr)
        if m.any():
            return JUDGE._largest_component(m), a
    return None, None


# ══════════════════════════════════════════════════════════════════
#  ③ 값 조정 — 채점기의 힌트를 따라, 명세의 울타리 안에서만
# ══════════════════════════════════════════════════════════════════
class Tuner:
    """힌트를 실제 숫자로 옮긴다.

    ★ 조정 원칙 (설계서 · 사용자 확정)
      · 보수적으로 시작한다 (±20%). 정량 힌트(면적 ∝ scale²)가 있으면 그것을 따르되 한 번에
        너무 크게 뛰지 않게 **완충**한다 — 지나쳐 금지 조건(화면덮음)을 밟으면 왕복이 는다.
      · **같은 방향으로 두 번 실패하면 폭을 키운다** (0.2 → 0.35 → 0.6 …).
      · **지표가 반대로 움직이면 방향을 뒤집는다** — 영향표는 첫 수를 고르는 데 쓸 뿐,
        진실은 실측이 정한다 (표가 틀릴 수 있다는 것을 루프가 인정한다).
    """

    def __init__(self, spec):
        self.tunable = list(spec["조절키"])
        self.범위 = spec.get("범위", {}) or {}
        self.step = {k: 0.20 for k in self.tunable}
        self.last_dir = {}
        self.streak = {}

    def clamp(self, key, v):
        lo, hi = self.범위.get(key, [None, None])
        if lo is not None:
            v = max(float(lo), v)
        if hi is not None:
            v = min(float(hi), v)
        return v

    def at_limit(self, key, v):
        lo, hi = self.범위.get(key, [None, None])
        return ((lo is not None and abs(v - float(lo)) < 1e-9)
                or (hi is not None and abs(v - float(hi)) < 1e-9))

    def propose(self, hints, current, feedback=None, 안보임=False):
        """힌트 → {키: 새 값}. feedback: 지난 회차에 (키, 방향, 지표가 나아졌나).

        가장 힘센 힌트 **하나**만 움직인다 — 여러 개를 한꺼번에 바꾸면 무엇이 들었는지 모른다
        (그러면 다음 회차의 되먹임이 거짓말이 된다).

        `안보임` = 어느 각도에서도 효과가 **한 픽셀도 안 잡혔다**.
        ★ 이 경우를 따로 두는 까닭 (2026-07-20 · 첫 실전 루프에서 바로 걸렸다):
          scale 0.6 → 0.72 → 0.96 을 밀었는데 셋 다 0px 였다. 점수가 안 올랐으니 되먹임은
          「이 방향이 틀렸다」고 읽고 **방향을 뒤집었다** (0.96 → 0.77). 그러나 0px 는
          「방향이 틀렸다」가 아니라 **「아직 문턱에 못 미쳐 기울기가 없다」**이다.
          검출 문턱 아래에서는 어느 쪽으로 밀든 0 이다 — 거기서 되먹임을 믿으면 제자리를 맴돈다.
        ⇒ 안 보이면 ① **방향을 절대 안 뒤집고** ② 야금야금 대신 **울타리까지 로그-이분**으로
          건너뛴다 (√(현재×끝) — 보일 때까지 창을 반씩 접는다). 보이기 시작하면 그때부터
          정량 힌트(면적 ∝ scale²)로 정밀하게 좁힌다.
        """
        for h in hints:
            key = h["키"]
            if key not in self.tunable:
                continue
            cur = float(current[key])
            방향, 목표 = h["방향"]

            if 방향 == "→":            # 특정 값 쪽으로 (blade_pitch → 90)
                new = cur + (float(목표) - cur) * 0.5
            else:
                sign = +1 if 방향 == "+" else -1

                if 안보임:
                    # 기울기가 없다 — 되먹임을 믿지 않고, 울타리 쪽으로 로그-이분해 건너뛴다
                    lo, hi = self.범위.get(key, [None, None])
                    끝 = hi if sign > 0 else lo
                    if 끝 is not None and float(끝) > 0 and cur > 0:
                        new = math.sqrt(cur * float(끝))
                    else:
                        new = cur * (1 + sign * 0.5)
                    self.last_dir[key] = sign
                    h = dict(h, 말=h["말"] + "  ※ 아직 한 픽셀도 안 보인다 — "
                                            "기울기가 없어 울타리까지 로그-이분으로 건너뛴다")
                else:
                    # 되먹임: 지난번 같은 키를 같은 쪽으로 밀었는데 안 나아졌다면 뒤집는다
                    if feedback and feedback.get("키") == key and not feedback.get("나아짐"):
                        if feedback.get("쪽") == sign:
                            sign = -sign
                            h = dict(h, 말=h["말"] + "  ※ 지난 회차에 안 먹혀 방향을 뒤집었다")
                    # 같은 방향 연속 실패 → 폭을 키운다
                    prev = self.last_dir.get(key)
                    self.streak[key] = self.streak.get(key, 0) + 1 if prev == sign else 0
                    self.step[key] = min(0.20 * (1.7 ** self.streak[key]), 1.5)
                    if h.get("배수"):
                        # 정량 힌트 — 필요한 배수를 따르되 한 걸음 폭 안에서 완충
                        want = cur * h["배수"] if sign > 0 else cur / h["배수"]
                        cap = (cur * (1 + self.step[key]) if sign > 0
                               else cur * (1 - self.step[key]))
                        new = min(want, cap) if sign > 0 else max(want, cap)
                    else:
                        new = cur * (1 + sign * self.step[key])
                    self.last_dir[key] = sign

            new = self.clamp(key, new)
            if abs(new - cur) < 1e-6:
                continue               # 이 키는 이미 끝(울타리)이다 — 다음 힌트를 본다
            return key, new, h
        return None, None, None


# ══════════════════════════════════════════════════════════════════
#  ④ 1차 확인용 산출물 — 사람이 **그림으로** 판단할 수 있게
# ══════════════════════════════════════════════════════════════════
def _font(size):
    from PIL import ImageFont
    try:
        return ImageFont.truetype(FONT, size)
    except Exception:
        return ImageFont.load_default()


def make_gif(obs, outdir: Path, angle="back", fps=15):
    """움직이는 미리보기 — **모션은 정지 화면으로 판단할 수 없다** (설계서)."""
    from PIL import Image
    o = obs["각도"].get(angle) or {}
    names = o.get("_burst") or []
    if not names:
        return None
    src = outdir / angle
    frames = [Image.open(src / n).convert("RGB").resize((640, 360)) for n in names]
    if len(frames) < 2:
        return None
    gif = outdir / f"미리보기_{angle}.gif"
    frames[0].save(gif, save_all=True, append_images=frames[1:],
                   duration=int(1000 / fps), loop=0)
    return gif


def summary_sheet(outdir: Path, spec, obs, verdict, values, prev, pf_lines, 회차, 멈춤):
    """**한 장**으로 낸다 — 네 각도 · 채점표 · 직전 대조 · preflight · 한계."""
    from PIL import Image, ImageDraw
    angles = [a for a in (spec.get("각도") or []) if a in obs["각도"]]
    tw, th = 420, 236
    cols = min(4, max(1, len(angles)))
    rows = (len(angles) + cols - 1) // cols
    W = max(1180, cols * tw + 40)
    grid_h = rows * (th + 26)
    # 아래쪽 글 높이를 **세어서** 잡는다 — 고정값으로 두면 항목이 늘 때 조용히 잘린다
    #   (첫 산출물에서 「한계」 절이 통째로 잘려 나갔다: 한계를 안 적는 그림이 되어 버렸다)
    글줄 = len(verdict.items) + (1 if verdict.유사도 else 0)
    H = 150 + grid_h + (26 + 글줄 * 21 + 8 + 26 + 24 + 6 + 24 + 6 * 17 + 8 + 24 + 4 * 18 + 30)
    im = Image.new("RGB", (W, H), (24, 24, 28))
    d = ImageDraw.Draw(im)
    f_t, f_h, f_b, f_s = _font(30), _font(19), _font(16), _font(14)

    d.text((22, 18), f"{spec['스킬']} — 자동 루프 {회차}회차", font=f_t, fill=(240, 240, 240))
    d.text((22, 58), f"멈춘 까닭: {멈춤}", font=f_h,
           fill=(120, 220, 140) if verdict.ok else (240, 190, 110))
    d.text((22, 84), "값: " + " · ".join(f"{k.split('.')[-1]}={v:g}" for k, v in values.items()),
           font=f_b, fill=(200, 200, 210))
    d.text((22, 108), f"충족도 {verdict.점수:.3f} — "
                      f"통과 {len(verdict.items)-len(verdict.미달)}/{len(verdict.items)}",
           font=f_b, fill=(200, 200, 210))

    y0 = 140
    for i, a in enumerate(angles):
        o = obs["각도"][a]
        x = 22 + (i % cols) * tw
        y = y0 + (i // cols) * (th + 26)
        p = o.get("대표프레임")
        if p and (ROOT / p).exists():
            im.paste(Image.open(ROOT / p).convert("RGB").resize((tw - 14, th)), (x, y))
        else:
            d.rectangle([x, y, x + tw - 14, y + th], fill=(45, 45, 50))
            d.text((x + 12, y + th // 2), "검출 없음", font=f_b, fill=(200, 120, 120))
        label = {"back": "뒤", "front": "앞", "side": "옆", "high": "위", "first": "1인칭"}.get(a, a)
        d.text((x + 4, y + th + 3), f"{label}  {o['최대면적']}px · {o['지속틱']}틱",
               font=f_b, fill=(225, 225, 235))

    ty = y0 + grid_h + 10
    d.text((22, ty), "채점표", font=f_h, fill=(240, 240, 240))
    ty += 26
    for it in verdict.items:
        got = f"{it.got:.0f}" if isinstance(it.got, float) else str(it.got)
        got = "못 쟀다" if it.got == -1 else got
        d.text((30, ty), f"{'통과' if it.ok else '미달'}  {it.라벨:<16} "
                         f"{got}{it.단위}  (요구 {it.op}{it.want:g}{it.단위})",
               font=f_b, fill=(140, 220, 150) if it.ok else (240, 150, 140))
        ty += 21
    if verdict.유사도 and verdict.유사도.get("잼"):
        d.text((30, ty), f"참고  레퍼런스 닮음  IoU {verdict.유사도['IoU']:.3f} · "
                         f"점수 {verdict.유사도['점수']:.3f}", font=f_b, fill=(190, 190, 220))
        ty += 21

    # 직전 회차와 나란히 — "방금 바꾼 게 나아졌나"
    ty += 8
    if prev:
        d.text((22, ty), "직전 회차와 (뒤 각도 최대 면적)", font=f_h, fill=(240, 240, 240))
        ty += 26
        pa = (prev["관측"]["각도"].get("back") or {}).get("최대면적", 0)
        ca = (obs["각도"].get("back") or {}).get("최대면적", 0)
        화살 = "↑ 나아졌다" if ca > pa else ("↓ 나빠졌다" if ca < pa else "→ 그대로")
        d.text((30, ty), f"{prev['회차']}회차 {pa}px  →  {회차}회차 {ca}px   {화살}"
                         f"   (충족도 {prev['점수']:.3f} → {verdict.점수:.3f})",
               font=f_b, fill=(200, 220, 200))
        ty += 24
    else:
        d.text((22, ty), "직전 회차 없음 (첫 회차)", font=f_b, fill=(160, 160, 170))
        ty += 24

    ty += 6
    d.text((22, ty), "이 그림이 성한 조건에서 찍혔다는 보증 (preflight)", font=f_h,
           fill=(240, 240, 240))
    ty += 24
    # ★ preflight 항목은 **여러 줄짜리**다 (Check.__str__ 이 줄바꿈을 품는다).
    #   그대로 그리면 PIL 이 한 자리에 여러 줄을 겹쳐 그려 글자가 뭉갠다 (첫 산출물에서 그랬다).
    #   ⇒ 줄바꿈으로 쪼개 **머리줄만** 쓴다.
    heads = []
    for ln in pf_lines:
        first = str(ln).splitlines()[0].strip()
        if first and first not in heads:
            heads.append(first)
    for ln in heads[:6]:
        d.text((30, ty), ln[:150], font=f_s, fill=(180, 200, 190))
        ty += 17

    ty += 8
    d.text((22, ty), "한계 — 이 그림으로 판단하면 안 되는 것", font=f_h, fill=(240, 200, 140))
    ty += 24
    for ln in ["· 소프트렌더(llvmpipe) 6~7fps 로 찍었다 — 실제 게임은 더 부드럽다. 끊김을 결함으로 읽지 마라.",
               "· 색은 조명·시간대에 따라 다르다 (이 무대는 정오·맑음 고정).",
               "· 면적(px)은 6m 거리·1280x720 기준이다 — 절대 크기가 아니라 회차끼리 견주는 값이다.",
               "· 촬영 편의로 테스트에서만 늘여 둔 값이 있으면 preflight 가 「의도적 차이」로 적는다."]:
        d.text((30, ty), ln, font=f_s, fill=(210, 190, 160))
        ty += 18

    out = outdir / "1차확인.png"
    im.save(out)
    return out


# ══════════════════════════════════════════════════════════════════
#  ⑤ 루프
# ══════════════════════════════════════════════════════════════════
def run_loop(spec, args):
    스킬 = spec["스킬"]
    stamp = time.strftime("%Y%m%d-%H%M%S")
    base = OUT_BASE / 스킬 / stamp
    base.mkdir(parents=True, exist_ok=True)
    motion = TEST_CONFIG / "skill_motion.yml"
    fps = args.fps

    tunable = list(spec["조절키"])
    tuner = Tuner(spec)

    # ★ 시작값은 **preflight 뒤에** 쓴다 (아래). 순서를 지키는 까닭:
    #   먼저 쓰면 「파일이 메모리보다 새롭다」 = 메모리 신선도 실패로 preflight 가 막는다.
    #   그건 검사가 틀린 게 아니라 **내 순서가 틀린 것**이다 (실측으로 바로 걸렸다).
    #   조건을 먼저 검사하고, 성하다는 것을 확인한 뒤에 손을 댄다.

    # 무기·좌표를 먼저 확인할 인자 검사만 여기서 (파일은 안 건드린다)
    for kv in args.시작값 or []:
        k, _, _v = kv.partition("=")
        if k not in tunable:
            raise SystemExit(f"--시작값 의 키가 명세의 조절키가 아니다: {k}")

    # 무대 · 클라 · 서버
    CAM.ensure_xvfb(CAM.BOT_DISPLAY)
    CAM.ensure_xvfb(CAM.CAM_DISPLAY)
    AT.ensure_server()
    AT.ensure_client()
    CAM.ensure_cam_client()

    # ★ preflight — 조절키는 **루프가 움직이는 값**이라 저장소와 다를 수밖에 없다.
    #   그 키를 검사에 넣으면 루프는 첫 회차에 제 손으로 만든 차이 때문에 멈춘다.
    #   ⇒ 조절키만 빼고 **나머지는 전부** 검사한다 (팩·jar·로그·메모리 신선도·봇 접속·자원·검출기).
    pf_keys = [k for k in AT.PREFLIGHT_KEYS if k not in tunable]

    # ★ 시작 전 **한 번** 동기화한다 — 그리고 그것을 소리내어 적는다.
    #   왜 이것이 정당한가: 「메모리 신선도」 검사는 *사람이 파일을 고치고 재적재를 잊은 것*을
    #   잡으려고 있다. 그런데 루프는 이제부터 이 값들의 **유일한 편집자**이고, 매 회차 첫 일이
    #   재적재다 (그리고 재적재가 먹었는지 회차마다 다시 검사한다).
    #   앞선 루프가 중간에 멎어 파일이 어긋난 채 남는 일은 흔하다 — 그때마다 사람을 부르면
    #   자동 루프가 아니다. 다만 **조용히** 맞추지는 않는다: 무엇을 왜 맞췄는지 적는다.
    sync = Rcon()
    try:
        mem = PF.check_motion_memory(None, PF.find_server_pid(PF.TEST_DIR))
        if not mem.ok:
            ok, took, _ = hot_reload(sync)
            say("")
            say(f"[동기화] 파일이 메모리보다 새로웠다 — 시작 전에 한 번 재적재했다 ({took:.0f}ms).")
            say("         (앞선 루프가 중간에 멎으면 이렇게 남는다. 이제부터는 루프가 값의 유일한 편집자다.)")
            if not ok:
                raise SystemExit("시작 전 재적재가 실패했다 — config 가 깨졌는지 보라")
    finally:
        sync.close()

    say("")
    say("[preflight] 조건 검사 — 어긋나면 측정하지 않고 즉시 중단한다")
    say(f"           (조절키 {', '.join(tunable)} 는 루프가 움직이는 값이라 검사에서 뺀다)")
    pf = PF.run(keys=pf_keys, work_dirs=[CAM.MAIN_DIR, CAM.CAM_WORK],
                players=[CAM.BOT, CAM.CAM], outdir=base, force=args.force, verbose=True)
    if not pf.ok:
        say("")
        say("★ preflight 실패 — **루프를 시작하지 않는다.**")
        say("  틀린 눈으로 값을 고치면 디자인이 망가진다. 조건을 고치고 다시 부르라.")
        (base / "중단.txt").write_text(
            "preflight 실패로 루프를 시작하지 않았다\n\n" + "\n".join(pf.lines), encoding="utf-8")
        return 2, base

    # ── 조건이 성하다. 이제 손을 댄다 ────────────────────────────────
    for kv in args.시작값 or []:
        k, _, v = kv.partition("=")
        set_config_value(motion, k, float(v))
        say(f"[시작값] {k} = {v}  (일부러 어긋나게 두었다 — 루프가 스스로 고치는지 본다)")

    # 레퍼런스
    ref_mask, ref_why = (None, None)
    if spec.get("레퍼런스"):
        ref_mask, ref_why = JUDGE.reference_mask(spec["레퍼런스"])
        say(f"[레퍼런스] {ref_why}")

    rcon = Rcon()
    기록, prev, last_change = [], None, None
    best_score, since_best = -1.0, 0
    멈춤 = None
    try:
        AT.prepare_scene(rcon, spec.get("무기", "minecraft:iron_sword"), False)
        sx, sy, sz = AT.STAGE
        rcon.cmd(f"gamemode spectator {CAM.CAM}")
        rcon.cmd(f"tp {CAM.CAM} {sx} {sy} {sz}")
        time.sleep(2.0)
        CAM.wait_until_ingame(CAM.CAM_DISPLAY, label="카메라")

        # ★ 발동이 먹는지 먼저 확인한다 — 안 먹으면 0px 를 「효과 없음」으로 읽게 된다
        먹나, 답 = check_trigger(rcon, spec)
        say(f"[발동] {spec['발동']!r} → "
            + (f"받아들여졌다{(' · ' + 답[:60]) if 답 else ' (응답은 몸에게 간다)'}" if 먹나
               else f"★ 거절당했다: {답[:120]}"))
        if not 먹나:
            멈춤 = "발동 명령이 거절당했다 — 측정하지 않는다"
            say("")
            say("★ 발동이 안 되는데 촬영하면 「0px」 가 나오고, 그것은 「효과가 없다」로 읽힌다.")
            say("  명세의 '발동' 줄을 고쳐라 (콘솔은 몸을 이름으로 지목해야 한다).")
            (base / "중단.txt").write_text(
                f"발동 거절 — 측정하지 않았다\n명령: {spec['발동']}\n응답: {답}\n",
                encoding="utf-8")
            return 2, base

        for 회차 in range(1, args.최대회차 + 1):
            say("")
            say("━" * 74)
            say(f"  {회차}회차 / 최대 {args.최대회차}")
            say("━" * 74)
            it_dir = base / f"{회차:02d}회차"
            it_dir.mkdir(parents=True, exist_ok=True)

            # ── 적용: 핫 리로드 (클라를 안 끊는다) ──
            ok, took, lines = hot_reload(rcon)
            if not ok:
                멈춤 = "핫 리로드 실패 — config 가 깨졌다"
                say(f"  ★ {멈춤}")
                for ln in lines:
                    say("    " + ln)
                break
            values = {k: float(read_config_value(motion, k)) for k in tunable}
            say(f"  [적용] 재적재 {took:.0f}ms · " + " · ".join(
                f"{k.split('.')[-1]}={v:g}" for k, v in values.items()))

            # ★ 재적재가 정말 먹었는가 — 파일만 고치고 메모리는 옛 값인 경우를 막는다
            mem = PF.check_motion_memory(pf.log, PF.find_server_pid(PF.TEST_DIR))
            if not mem.ok:
                멈춤 = "메모리 신선도 실패 — 재적재가 안 먹었다"
                say(f"  ★ {멈춤}")
                break

            # ★ 조명을 회차마다 **다시 못 박는다** — 안 그러면 회차끼리 견줄 수 없다.
            #   왜 (2026-07-20 · 첫 산출물이 한밤중이었다): 무대를 차릴 때 한 번 noon 으로
            #   맞췄는데, 루프가 15분을 도는 사이 화면이 노을·한밤으로 넘어갔다. 어두우면
            #   초록 대비가 떨어져 **같은 값인데 면적이 달라 보인다** — 회차 비교가 거짓이 된다.
            #   (gamerule 이 풀렸든 월드가 달랐든, 원인을 캐기 전에 먼저 못을 박는다.)
            rcon.cmd("gamerule doDaylightCycle false")
            rcon.cmd("gamerule doWeatherCycle false")
            rcon.cmd("weather clear")
            rcon.cmd("time set noon")

            # ── 관측 ──
            obs = observe(rcon, spec, it_dir, fps)

            # ── 채점 ──
            our_mask, from_angle = our_shape_mask(obs)
            v = JUDGE.judge(spec, obs, ref_mask=ref_mask, our_mask=our_mask)
            say(v.report())
            if v.유사도 and v.유사도.get("잼"):
                say(f"  (닮음은 {from_angle} 각도의 대표 프레임으로 쟀다)")

            # 기록 — 나중에 "왜 이 값이 됐나"를 답할 수 있게
            rec = {"회차": 회차, "값": values, "관측": obs, "점수": v.점수,
                   "통과": v.ok,
                   "항목": [{"라벨": i.라벨, "got": i.got, "요구": f"{i.op}{i.want:g}",
                             "ok": i.ok} for i in v.items],
                   "유사도": v.유사도}
            (it_dir / "회차.json").write_text(
                json.dumps(rec, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
            (it_dir / "채점.txt").write_text(v.report(), encoding="utf-8")
            기록.append(rec)

            # ── 멈추는 조건 ──
            if v.ok:
                멈춤 = "충족 — 객관 기준 전부 통과"
                say(f"  ✔ {멈춤}")
                break
            if v.점수 > best_score + 0.005:
                best_score, since_best = v.점수, 0
            else:
                since_best += 1
                say(f"  · 개선 없음 {since_best}회 (최고 충족도 {best_score:.3f})")
            if since_best >= args.정체:
                멈춤 = f"정체 — {args.정체}회 연속 개선 없음"
                say(f"  ✘ {멈춤}")
                break
            if 회차 >= args.최대회차:
                멈춤 = f"상한 — 최대 {args.최대회차}회를 다 썼다"
                say(f"  ✘ {멈춤}")
                break

            # ── 조정 ──
            # ★ 되먹임은 **지난 회차에 민 것**을 두고 묻는다: 그게 먹혔나?
            #   (한 칸 어긋나면 엉뚱한 키의 성패를 보고 방향을 뒤집는다 — 그러면 표보다 더 나쁘다)
            feedback = None
            if last_change and len(기록) >= 2:
                feedback = dict(last_change, 나아짐=기록[-1]["점수"] > 기록[-2]["점수"])
            # 어느 각도에서도 한 픽셀도 안 잡혔나 — 그러면 되먹임에 기울기가 없다
            안보임 = all(o["최대면적"] == 0 for o in obs["각도"].values())
            if 안보임:
                say("  · 어느 각도에서도 검출 0px — 문턱 아래다 (되먹임을 믿지 않는다)")
            key, new, hint = tuner.propose(v.hints(), values, feedback, 안보임=안보임)
            if key is None:
                멈춤 = "더 움직일 수 있는 조절키가 없다 (전부 명세의 범위 끝)"
                say(f"  ✘ {멈춤}")
                break
            old = values[key]
            set_config_value(motion, key, round(new, 4))
            say(f"  [조정] {key}  {old:g} → {new:.4g}")
            say(f"         까닭: {hint['말']}")
            last_change = {"키": key, "쪽": +1 if new > old else -1}
            prev = rec
        else:
            멈춤 = f"상한 — 최대 {args.최대회차}회를 다 썼다"
    finally:
        rcon.close()

    # ── 산출물 ──
    last = 기록[-1] if 기록 else None
    if last:
        it_dir = base / f"{last['회차']:02d}회차"
        obs = last["관측"]
        our_mask, _ = our_shape_mask(obs)
        v = JUDGE.judge(spec, obs, ref_mask=ref_mask, our_mask=our_mask)
        gif = make_gif(obs, it_dir, "back", fps)
        prev_rec = 기록[-2] if len(기록) >= 2 else None
        sheet = summary_sheet(it_dir, spec, obs, v, last["값"], prev_rec,
                              [l for l in pf.lines if "통과" in l or "중단" in l],
                              last["회차"], 멈춤 or "?")
        표 = 회차표(기록, tunable)
        (base / "회차표.txt").write_text(표, encoding="utf-8")
        (base / "요약.json").write_text(
            json.dumps({"스킬": 스킬, "멈춤": 멈춤, "회차": len(기록),
                        "기록": [{"회차": r["회차"], "값": r["값"], "점수": r["점수"],
                                  "통과": r["통과"],
                                  "면적": {a: o["최대면적"] for a, o in r["관측"]["각도"].items()}}
                                 for r in 기록]},
                       ensure_ascii=False, indent=2), encoding="utf-8")
        say("")
        say(표)
        say("")
        say("  ── 사람에게 내는 것 (1차: 그림으로 확인) ─────────────────")
        say(f"    한 장 요약 : {sheet}")
        if gif:
            say(f"    움직이는 것: {gif}")
        say(f"    회차 기록  : {base}/회차표.txt · 요약.json")
        say(f"    잰 조건    : {base}/preflight.txt")
        say("")
        if v.ok:
            say("  ★ 객관 기준은 통과했습니다. **미감은 제가 판단할 수 없습니다** —")
            say("    예쁜가 · 세계관 톤에 맞는가 · 맞서 싸울 때 기분이 좋은가.")
            say("    위 그림과 GIF 로 봐 주시고, 그림으로 답이 안 나오면 그때 접속해 주십시오.")
        else:
            say("  ★ 스스로 못 닫았습니다. 헛도는 것을 인정하고 넘깁니다 —")
            say(f"    {멈춤}. 시도 기록이 위 표에 전부 있습니다.")
            say("    이 방향이 맞는지 판단해 주십시오 (명세가 틀렸을 수도 있습니다).")
    return (0 if (last and last["통과"]) else 1), base


def 회차표(기록, tunable):
    """회차별 값 → 면적 → 판정 — **왜 이 값이 됐나**를 한 장으로 답한다."""
    각도 = list(기록[0]["관측"]["각도"].keys()) if 기록 else []
    head = (f"  {'회차':<5}" + "".join(f"{k.split('.')[-1]:>13}" for k in tunable)
            + "".join(f"{a+'px':>10}" for a in 각도) + f"{'지속틱':>8}{'충족도':>9}  판정")
    out = ["  회차별 값 → 면적 → 판정", "  " + "─" * (len(head) + 4), head,
           "  " + "─" * (len(head) + 4)]
    for r in 기록:
        dur = max((o["지속틱"] for o in r["관측"]["각도"].values()), default=0)
        out.append(f"  {r['회차']:<5}"
                   + "".join(f"{r['값'][k]:>13.4g}" for k in tunable)
                   + "".join(f"{r['관측']['각도'][a]['최대면적']:>10}" for a in 각도)
                   + f"{dur:>8.1f}{r['점수']:>9.3f}  {'통과' if r['통과'] else '미달'}")
    out.append("  " + "─" * (len(head) + 4))
    return "\n".join(out)


# ══════════════════════════════════════════════════════════════════
#  자가시험 — 루프의 손(값 쓰기·조정)이 성한가
# ══════════════════════════════════════════════════════════════════
def selftest(verbose=True):
    import tempfile
    lines, ok = [], True

    def s(x):
        lines.append(x)
        if verbose:
            print(x)

    def check(label, cond, detail=""):
        nonlocal ok
        ok &= bool(cond)
        s(f"    {label:<26}{'통과' if cond else '★실패'}  {detail}")

    s("  [자가시험] 루프의 손 — 값 쓰기와 조정을 시험한다")

    # ① 값 쓰기가 **주석을 안 죽이는가** (이 저장소에서 가장 비싼 실수)
    src = ("# 머리 주석\n"
           "kigi_slash:\n"
           "  enabled: true\n"
           "  # ★ 이 주석이 살아야 한다 — 정본의 절반이다\n"
           "  scale: 2.0  # 초승달 크기(대략 m)\n"
           "  orbit_radius: 1.25\n"
           "other:\n"
           "  scale: 99.0   # 다른 절의 같은 이름 — 이걸 고치면 안 된다\n")
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "t.yml"
        p.write_text(src, encoding="utf-8")
        old = set_config_value(p, "kigi_slash.scale", 5.5)
        txt = p.read_text(encoding="utf-8")
        check("① 값이 바뀌었나", read_config_value(p, "kigi_slash.scale") == 5.5, f"옛값 {old}")
        check("② 줄 주석 보존", "# 초승달 크기(대략 m)" in txt)
        check("③ 블록 주석 보존", "★ 이 주석이 살아야 한다" in txt and "# 머리 주석" in txt)
        check("④ 남의 절 안 건드림", read_config_value(p, "other.scale") == 99.0)
        check("⑤ 이웃 키 안 건드림", read_config_value(p, "kigi_slash.orbit_radius") == 1.25)

    # ⑥ 조정이 **명세의 범위**를 안 넘는가
    spec = {"스킬": "t", "조절키": ["kigi_slash.scale"],
            "범위": {"kigi_slash.scale": [0.5, 3.0]},
            "필수": {"가시성": {"back": ">=1500"}}}
    t = Tuner(spec)
    hints = JUDGE.judge(spec, {"fps": 15, "각도": {"back": {"최대면적": 10, "지속틱": 12}}}).hints()
    k, new, _ = t.propose(hints, {"kigi_slash.scale": 2.9})
    check("⑥ 범위 상한 지킴", k == "kigi_slash.scale" and new <= 3.0, f"→ {new}")
    k2, new2, _ = t.propose(hints, {"kigi_slash.scale": 3.0})
    check("⑦ 끝에서 멈춤", k2 is None, "상한에 붙었으면 더 못 민다")

    # ⑧ 조정 방향 — 면적이 모자라면 키워야 한다
    t2 = Tuner(spec)
    k3, new3, _ = t2.propose(hints, {"kigi_slash.scale": 1.0})
    check("⑧ 조정 방향", k3 and new3 > 1.0, f"1.0 → {new3:.3f} (커져야 한다)")

    # ⑧-b 안 보이면 **방향을 안 뒤집는다** (실전에서 이것 때문에 제자리를 맴돌았다)
    #     되먹임이 「안 나아졌다」고 말해도, 0px 는 방향이 틀렸다는 증거가 아니다.
    t_dead = Tuner(spec)
    나쁜되먹임 = {"키": "kigi_slash.scale", "쪽": +1, "나아짐": False}
    _, up, _ = t_dead.propose(hints, {"kigi_slash.scale": 1.0}, 나쁜되먹임, 안보임=True)
    check("⑧-b 안보임: 방향 유지", up > 1.0, f"1.0 → {up:.3f} (뒤집히면 안 된다)")
    # 그리고 야금야금이 아니라 울타리까지 로그-이분으로 건너뛴다 (√(1×3)≈1.732)
    check("⑧-c 안보임: 로그-이분", abs(up - math.sqrt(1.0 * 3.0)) < 0.01,
          f"→ {up:.3f} (기대 {math.sqrt(3.0):.3f})")
    # 보이는데 안 나아졌으면 그때는 뒤집는다 (되먹임이 살아 있어야 한다)
    t_live = Tuner(spec)
    _, dn, _ = t_live.propose(hints, {"kigi_slash.scale": 1.0}, 나쁜되먹임, 안보임=False)
    check("⑧-d 보임: 방향 뒤집음", dn < 1.0, f"1.0 → {dn:.3f} (뒤집혀야 한다)")

    # ⑨ 명세에 없는 키는 못 만진다
    spec2 = dict(spec, 조절키=["kigi_slash.orbit_radius"])
    t3 = Tuner(spec2)
    h2 = JUDGE.judge(spec2, {"fps": 15, "각도": {"back": {"최대면적": 10, "지속틱": 12}}}).hints()
    k4, _, _ = t3.propose(h2, {"kigi_slash.orbit_radius": 1.0})
    check("⑨ 조절키 울타리", k4 != "kigi_slash.scale", f"고른 키 {k4}")

    s(f"  [자가시험] {'전부 통과' if ok else '★ 실패'}")
    return ok, lines


def main():
    ap = argparse.ArgumentParser(description="시각효과 자동 루프")
    ap.add_argument("--spec")
    ap.add_argument("--시작값", nargs="*", help="키=값 (일부러 어긋나게 두고 시작한다)")
    ap.add_argument("--최대회차", type=int, default=15)
    ap.add_argument("--정체", type=int, default=5, help="N회 연속 개선 없으면 사람을 부른다")
    ap.add_argument("--fps", type=int, default=15)
    ap.add_argument("--force", action="store_true", help="preflight 를 무시한다 (권장하지 않는다)")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()

    if a.selftest or not a.spec:
        good, _ = selftest()
        okj, _ = JUDGE.selftest()
        sys.exit(0 if (good and okj) else 1)

    spec = JUDGE.load_spec(a.spec)
    code, base = run_loop(spec, a)
    sys.exit(code)


if __name__ == "__main__":
    main()
