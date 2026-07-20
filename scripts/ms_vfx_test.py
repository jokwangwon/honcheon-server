#!/usr/bin/env python3
"""MagicSpells 스펠의 시각효과를 **실제로 보이는지** 재는 하네스.

★ 왜 따로 있나
  kigi_cam_test.py 는 우리 자체 검기(좌클릭 스윙)를 잰다. MagicSpells 는 좌클릭이 아니라
  RCON `ms cast as` 로 시전한다. 카메라 장치(kigicam · :98 · tp 로 각도 잡기)는 똑같이 쓰되,
  **무엇을 시키는가**만 갈아 끼운 것이다.

★ 무엇을 믿는가
  검출은 vfx_detect 하나만 쓴다 (자가시험을 받는 눈). 붙박이 초록(HUD·경험치바)은
  시전 **전에** 찍어 두고 빼고 센다 — 안 그러면 HUD 글자를 검기라 부르게 된다.
  그래서 파티클은 초록 계열(happy_villager)로 골랐다.

사용:
    python3 scripts/ms_vfx_test.py 시험1 시험2 --angle side
    python3 scripts/ms_vfx_test.py --spells 시험1,시험2,시험3,시험4,시험_원본

★ 테스트 서버(25576) 전용. 라이브(25565)는 kigi_rcon 이 애초에 거부한다.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import kigi_cam_test as CT  # noqa: E402
from kigi_rcon import Rcon  # noqa: E402


def cast(rcon: Rcon, spell: str, who: str = CT.BOT):
    """콘솔에서 스펠을 시전한다.

    ★ `ms cast as` 는 성공해도 **아무 말도 하지 않는다**. 조용함은 실패가 아니다.
      실패했다면 오히려 말을 한다 ("No matching spell found"). 그러니 조용함을 근거로
      「시전이 안 됐다」고 결론내면 안 된다 — 그것이 이 하네스가 생긴 이유다.
    """
    return rcon.cmd(f"ms cast as {who} {spell}")


def ensure_on_stage(rcon, tol=3.0) -> bool:
    """봇이 아직 흰 무대 위에 서 있는가 — **매 측정 직전에** 다시 묻는다.

    ★ 왜 (2026-07-20 실제 사고): 무대를 깔고 카메라를 앉힌 뒤에도 봇이 도중에
      스폰 지점 지하(Y=-60)로 돌아가 버렸다. 카메라는 무대를 계속 보고 있었으므로
      화면은 「깨끗한 흰 바닥」이었고 측정은 **조용히 0px** 를 냈다.
      0px 는 「이펙트가 없다」가 아니라 「잴 대상이 화면에 없다」였다.
    ⇒ 어긋났으면 되돌리고, 되돌려지지 않으면 **측정을 거부한다** (0을 반환하지 않는다).
    """
    sx, sy, sz = CT.AT.STAGE
    for _ in range(3):
        x, y, z = CT.read_pos(rcon, CT.BOT)
        if abs(x - sx) < tol and abs(y - sy) < tol and abs(z - sz) < tol:
            return True
        print(f"  ⚠ 봇이 무대를 벗어났다 ({x:.1f},{y:.1f},{z:.1f}) → 무대로 되돌린다")
        rcon.cmd(f"tp {CT.BOT} {sx} {sy} {sz} 90 -8")
        time.sleep(2.0)
    return False


def run_one(rcon, spell, outroot: Path, args, exclude):
    if not ensure_on_stage(rcon):
        print(f"  ✗ {spell}: 봇을 무대에 세우지 못했다 — 이 스펠은 재지 않는다")
        return dict(spell=spell, frames=0, hits=0, best=-1, cx=0, cy=0, reply="측정거부")

    outdir = outroot / spell
    outdir.mkdir(parents=True, exist_ok=True)
    for old in outdir.glob("frame_*.png"):
        old.unlink()

    cap = CT.start_capture(CT.CAM_DISPLAY, outdir, args.fps)
    time.sleep(0.5)
    replies = []
    for _ in range(args.casts):
        replies.append(cast(rcon, spell))
        time.sleep(args.gap)
    time.sleep(1.0)
    CT.stop_capture(cap)

    total, rows = CT.analyze_masked(outdir, args.min_area, exclude)
    top = max(rows, key=lambda r: r["area"], default=None)
    best = top["area"] if top else 0
    # ★ 면적만으로는 부족하다 — 하늘 저 위에 큰 호를 그려도 면적은 크다.
    #   **어디에** 그렸는지(중심)를 같이 봐야 「몸에 걸친 칼자국」인지 안다.
    return dict(spell=spell, frames=total, hits=len(rows), best=best,
                cx=top["cx"] if top else 0, cy=top["cy"] if top else 0,
                reply=" | ".join(r.strip() for r in replies if r and r.strip()))


def main():
    ap = argparse.ArgumentParser(description="MagicSpells 시각효과 측정")
    ap.add_argument("spells", nargs="*", help="시전할 스펠 이름들")
    ap.add_argument("--spells", dest="spells_csv", help="쉼표로 구분한 스펠 목록")
    ap.add_argument("--angle", default="side", help="back|front|side|high")
    ap.add_argument("--casts", type=int, default=3, help="스펠당 시전 횟수")
    ap.add_argument("--gap", type=float, default=1.2)
    ap.add_argument("--fps", type=int, default=10)
    ap.add_argument("--min-area", type=int, default=12)
    ap.add_argument("--cam-mode", default="spectator", choices=["spectator", "creative"])
    ap.add_argument("--item", default="minecraft:iron_sword", help="봇이 들 무기")
    ap.add_argument("--night", action="store_true")
    ap.add_argument("--restart-cam", action="store_true")
    ap.add_argument("--outdir", default=None)
    args = ap.parse_args()

    spells = list(args.spells)
    if args.spells_csv:
        spells += [s.strip() for s in args.spells_csv.split(",") if s.strip()]
    if not spells:
        ap.error("잴 스펠을 하나 이상 대라")

    outroot = Path(args.outdir) if args.outdir else CT.SCRATCH / "ms-vfx"
    outroot.mkdir(parents=True, exist_ok=True)

    # ★ 서버·클라가 살아 있다고 **가정하지 않는다**. 한 번 이 가정 때문에 판이 통째로
    #   깨졌다 (서버가 중간에 재기동되어 두 봇이 다 떨어졌는데 스크립트는 RCON 거부로 죽었다).
    CT.AT.ensure_server()
    CT.AT.ensure_client()                       # kigibot (:99) — 검을 든 쪽
    CT.ensure_cam_client(force_restart=args.restart_cam)   # kigicam (:98) — 보는 쪽

    rcon = Rcon()

    # ★ 무대를 먼저 깐다 — 이걸 빼먹으면 봇이 스폰 자리(때로 지하 Y=-59)에 서고
    #   측정은 **조용히 전부 0** 이 된다. 저장소에 같은 사고 기록이 이미 있다
    #   (kigi_autotest.prepare_scene 주석). 흰 바닥 + 빈 하늘 + 정오로 못 박는다.
    CT.AT.prepare_scene(rcon, args.item, args.night)

    CT.place_camera(rcon, args.angle, args.cam_mode)
    time.sleep(2.5)

    ok, dark = CT.bot_in_frame(CT.CAM_DISPLAY)
    print(f"[검사] 화면에 봇이 보이는가: {'예' if ok else '아니오'} (어두운 픽셀 {dark})")
    if not ok:
        print("  ⚠ 카메라가 딴 데를 보고 있다. 이 판의 숫자는 근거로 쓰지 마라.")

    # 붙박이 초록(HUD)을 먼저 재서 빼고 센다
    exclude = CT.static_green(CT.CAM_DISPLAY)

    results = [run_one(rcon, s, outroot, args, exclude) for s in spells]

    print("\n" + "=" * 74)
    print(f"{'스펠':<20} {'프레임':>6} {'검출':>5} {'최대면적px':>10} {'중심(x,y)':>12}")
    print("-" * 74)
    # 봇은 화면 한복판(640,380) 근처에 선다 — 중심이 거기서 멀면 엉뚱한 데 그린 것이다
    for r in results:
        if r["best"] < 0:
            print(f"{r['spell']:<20} {'—':>6} {'—':>5} {'측정거부':>10}")
            continue
        mark = "○" if r["best"] > 0 else "×"
        pos = f"({r['cx']},{r['cy']})" if r["best"] else "-"
        near = "" if not r["best"] else ("  ← 몸 근처" if abs(r["cy"] - 380) < 130 else "  ← 빗나감")
        print(f"{r['spell']:<20} {r['frames']:>6} {r['hits']:>5} {r['best']:>10} {pos:>12} {mark}{near}")
    print("=" * 74)
    print(f"프레임 저장 위치: {outroot}")


if __name__ == "__main__":
    main()
