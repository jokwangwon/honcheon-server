#!/usr/bin/env python3
"""무대 촬영 — 좌표를 주면 그 자리에서 한 컷 찍는다.

  python3 scripts/stage_shot.py <월드> <나갈곳> \
      "이름=x,y,z,yaw,pitch" "이름=x,y,z,yaw,pitch" ...

★ 왜 따로 있는가: {@code kigi_cam_test.py} 는 검기 촬영용이라 봇 둘·스윙·몹이 딸린다.
  건축 판정은 「그 자리에서 한 컷」이면 된다. 그 최소한만 떼어 쓴다.

★★ 계율 — <b>촬영 클라는 반드시 회수한다.</b> 2026-08-04 에 헤드리스 클라 24개가 램
   120GB 를 먹어 세션과 테스트 서버가 함께 죽었다. 이 스크립트는 성공·실패·중단
   무관하게 finally 에서 제 클라를 죽이고, 끝에 남은 수를 센다.

★ 라이브(25565·run/mvt) 무접촉 — 테스트(25566·25576)만 본다.
"""
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import kigi_cam_test as K            # noqa: E402  — 클라 기동·붙잡기를 그대로 쓴다

OUT = Path(__file__).resolve().parent.parent / "run" / "stage_render"


def rcon():
    return K.Rcon()        # kigi_rcon 의 기본값 = 테스트 서버 (25575 는 거부한다)


def live_clients():
    """지금 살아 있는 헤드리스 클라 수 — 회수의 증거."""
    r = subprocess.run(["ps", "-eo", "pid,cmd"], capture_output=True, text=True)
    return [ln.split()[0] for ln in r.stdout.splitlines()
            if "mcdata" in ln and "jdk-21" in ln and "grep" not in ln]


def tune_options():
    """건축 판정용 카메라 설정 — <b>검기 촬영과 요구가 다르다</b>.

    검기는 봇 코앞을 찍으니 렌더 4청크로 족했다. 건축은 산까지 봐야 하므로 멀리 봐야 하고
    (renderDistance 4 → 24), 안개가 걷혀야 색을 잴 수 있다. 판정에 HUD가 끼면 화소 실측이
    오염되므로 GUI도 끈다.
    """
    opt = K.CAM_WORK / "options.txt"
    if not opt.exists():
        return
    want = {"renderDistance": "24", "simulationDistance": "12",
            "guiScale": "2", "hideGui": "true", "fov": "0.0",
            "graphicsMode": "1", "gamma": "1.0", "bobView": "false"}
    lines, seen = [], set()
    for ln in opt.read_text().splitlines():
        k = ln.split(":", 1)[0]
        if k in want:
            lines.append(f"{k}:{want[k]}")
            seen.add(k)
        else:
            lines.append(ln)
    for k, v in want.items():
        if k not in seen:
            lines.append(f"{k}:{v}")
    opt.write_text("\n".join(lines) + "\n")
    print(f"[카메라] 렌더 거리 24 · HUD 끔")


def main():
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    world, exit_to = sys.argv[1], sys.argv[2]
    shots = []
    for spec in sys.argv[3:]:
        name, nums = spec.split("=", 1)
        x, y, z, yaw, pitch = (float(v) for v in nums.split(","))
        shots.append((name, x, y, z, yaw, pitch))

    OUT.mkdir(parents=True, exist_ok=True)
    before = live_clients()
    print(f"[회수] 촬영 전 클라 {len(before)}대")
    tune_options()
    try:
        K.ensure_xvfb(K.CAM_DISPLAY)
        K.ensure_cam_client(force_restart=True)   # 옵션을 새로 읽히려면 다시 띄운다
        # ★HUD 는 options.txt 로 못 끈다 (hideGui 는 저장되지 않는 런타임 토글이다).
        #   F1 을 창에 직접 넣는다 — 화소 실측에 HUD 가 끼면 그 자체가 오염이다.
        win = K.window_of(K.CAM_DISPLAY)
        if win:
            K.xdo(K.CAM_DISPLAY, "key", "--window", win, "F1")
            time.sleep(1)
        r = rcon()
        r.cmd(f"scoreboard objectives setdisplay sidebar")   # 판정 화면에서 장부를 치운다
        r.cmd(f"gamemode spectator {K.CAM}")
        r.cmd("time set noon")
        r.cmd("weather clear")
        r.cmd(f"gamerule doDaylightCycle false")
        for name, x, y, z, yaw, pitch in shots:
            r.cmd(f"execute in minecraft:{world} run tp {K.CAM} "
                  f"{x:.2f} {y:.2f} {z:.2f} {yaw:.2f} {pitch:.2f}")
            time.sleep(6)             # 청크가 그려질 참
            r.cmd(f"execute in minecraft:{world} run tp {K.CAM} "
                  f"{x:.2f} {y:.2f} {z:.2f} {yaw:.2f} {pitch:.2f}")
            time.sleep(4)
            path = OUT / f"{name}.png"
            K.grab_one(K.CAM_DISPLAY, path)
            print(f"[컷] {path}  cam({x:.0f},{y:.0f},{z:.0f}) yaw{yaw:.0f} pitch{pitch:.0f}")
        r.cmd(f"execute in minecraft:{exit_to} run tp {K.CAM} 0 100 0")
    finally:
        # ★계율 — 무슨 일이 있었든 이 세션이 띄운 클라를 거둔다
        for pid in live_clients():
            if pid not in before:
                print(f"[회수] kill {pid}")
                subprocess.run(["kill", pid], capture_output=True)
        time.sleep(3)
        rest = live_clients()
        print(f"[회수] 촬영 후 클라 {len(rest)}대", rest)
        free = subprocess.run(["free", "-g"], capture_output=True, text=True)
        print(free.stdout.splitlines()[1])


if __name__ == "__main__":
    main()
