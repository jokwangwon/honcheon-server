#!/usr/bin/env python3
"""검기(劍氣) — **카메라 봇** 자동 검증 하네스.

★ 왜 이것이 있는가 (막힌 지점을 우회한다)
`kigi_autotest.py` 는 봇 하나를 띄워 좌클릭으로 검기를 내고 그 화면을 찍는다.
좌클릭·RCON 은 잘 먹는다. 그런데 **F5(시점 전환)가 어떤 방법으로도 안 먹는다** —
`xdotool key F5` · `key --window` · `keydown/keyup` 전부 화면이 안 바뀐다(실측).
창 관리자 없는 Xvfb 에서 마인크래프트가 창 활성화를 요구하는 탓으로 보인다.
⇒ **늘 1인칭만 찍힌다.** 그런데 정작 알고 싶은 것은 "3인칭 뒤에서 검기가 어떻게 보이나"다.

  해법: **두 번째 봇을 카메라로 띄운다.**
  1인칭은 멀쩡히 돈다. 그러니 카메라 봇의 1인칭으로 **검기를 휘두르는 봇을 바라보면**
  그 화면이 곧 3인칭이다. 카메라의 위치·시선은 F5 가 아니라 RCON `tp` 로 정한다 —
  tp 는 잘 먹는다. 각도는 삼각함수로 **계산**한다(대충 찍지 않는다).

    :99  kigibot  — 검을 휘두른다 (좌클릭). 화면은 안 쓴다.
    :98  kigicam  — 스펙테이터. kigibot 을 바라본다. **이 화면을 찍는다.**

★ 안전
  · 라이브(25565 · run/mvt · 저장소 config/) 를 건드리지 않는다.
  · 테스트 자원은 run/mvt-test/ · scratch/ 안에만 산다.
  · RCON 은 127.0.0.1:25576(테스트) 전용 — kigi_rcon.py 가 25575 를 거부한다.

사용:
    scripts/kigi_cam_test.sh                      # back(뒤 6m) · 8회 스윙
    scripts/kigi_cam_test.sh --angle side --swings 6
    scripts/kigi_cam_test.sh --angle all          # back/front/side/high 를 차례로
"""

from __future__ import annotations

import argparse
import math
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from kigi_rcon import Rcon  # noqa: E402
import kigi_autotest as AT  # noqa: E402

ROOT = AT.ROOT
SCRATCH = AT.SCRATCH
VENV_PMC = AT.VENV_PMC
MAIN_DIR = AT.MC_DIR                    # 에셋·라이브러리·버전은 **공유**한다 (533MB 를 두 번 받지 않는다)
NATIVES = AT.NATIVES
JAVA = AT.JAVA
W, H = AT.W, AT.H

BOT = AT.BOT                            # 검을 휘두르는 쪽 (:99)
BOT_DISPLAY = AT.DISPLAY                # ":99"

CAM = "kigicam"                         # 바라보는 쪽 (:98)
CAM_UUID = "c1d50411a33c4bb8992e289dbbb2c14d"
CAM_DISPLAY = ":98"
CAM_WORK = SCRATCH / "mcdata-cam"       # ★ work-dir 분리 — options.txt·로그·스크린샷 충돌 방지
CAM_LOG = SCRATCH / "client-cam.log"

WINDOW_NAME = "Minecraft 1.21.11 - Multiplayer"

# 카메라 배치 — (거리, 높이오프셋, 봇기준 방위각°). 방위각 0=봇의 정면쪽, 180=봇의 등뒤.
ANGLES = {
    "back":  (6.0, 0.0, 180.0),
    "front": (6.0, 0.0, 0.0),
    "side":  (6.0, 0.0, 90.0),
    "high":  (6.0, 3.0, 180.0),
}
EYE = 1.62          # 플레이어 눈높이 (tp 는 발밑 좌표를 준다)
AIM_Y = 1.1         # 봇의 가슴께를 겨눈다 — 검과 검기가 화면 한복판에 오게


# ══════════════════════════════════════════════════════════════════
#  디스플레이별 도구 — :99 와 :98 을 따로 부린다
# ══════════════════════════════════════════════════════════════════
def xdo(display, *args):
    return subprocess.run(["xdotool", *args],
                          env=dict(os.environ, DISPLAY=display),
                          capture_output=True, text=True)


def window_of(display):
    r = xdo(display, "search", "--name", WINDOW_NAME)
    ids = [x for x in r.stdout.split() if x.strip()]
    return ids[0] if ids else None


def ensure_xvfb(display):
    env = dict(os.environ, DISPLAY=display)
    if subprocess.run(["xdpyinfo"], env=env, capture_output=True).returncode == 0:
        print(f"[Xvfb] {display} 이미 떠 있다")
        return
    print(f"[Xvfb] {display} 기동 ({W}x{H}x24)")
    subprocess.Popen(["Xvfb", display, "-screen", "0", f"{W}x{H}x24"],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(30):
        time.sleep(1)
        if subprocess.run(["xdpyinfo"], env=env, capture_output=True).returncode == 0:
            return
    raise SystemExit(f"Xvfb {display} 기동 실패")


def grab_one(display, path: Path):
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "x11grab",
                    "-video_size", f"{W}x{H}", "-i", f"{display}.0",
                    "-frames:v", "1", str(path)],
                   check=False, capture_output=True)


def start_capture(display, outdir: Path, fps: int):
    outdir.mkdir(parents=True, exist_ok=True)
    p = subprocess.Popen(
        ["ffmpeg", "-loglevel", "error", "-f", "x11grab", "-framerate", str(fps),
         "-video_size", f"{W}x{H}", "-i", display,
         "-vsync", "0", str(outdir / "frame_%04d.png")],
        env=dict(os.environ, DISPLAY=display),
        stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    time.sleep(1.5)
    return p


def stop_capture(p):
    p.send_signal(signal.SIGINT)
    try:
        p.wait(timeout=15)
    except subprocess.TimeoutExpired:
        p.kill()


def swing(display, win, n, gap):
    """좌클릭 = 평타 = 검기. :99(kigibot) 창에서만 부른다."""
    xdo(display, "windowfocus", win)
    xdo(display, "mousemove", str(W // 2), str(H // 2))
    time.sleep(0.3)
    for _ in range(n):
        xdo(display, "click", "1")
        time.sleep(gap)


def wait_until_ingame(display, timeout=300, label=""):
    """진짜 **게임 안**인지 화면으로 판정한다 (스플래시·로딩 회색을 배제)."""
    from PIL import Image
    import numpy as np
    probe = SCRATCH / f".ingame_probe{display.replace(':', '')}.png"
    print(f"[{label}] 게임 화면 진입 대기", end="", flush=True)
    t0 = time.time()
    while time.time() - t0 < timeout:
        grab_one(display, probe)
        if probe.exists() and probe.stat().st_size > 0:
            a = np.asarray(Image.open(probe).convert("RGB")).astype(int)
            r, g, b = a[:, :, 0].mean(), a[:, :, 1].mean(), a[:, :, 2].mean()
            mojang_red = r > 150 and g < 90 and b < 90
            flat = a.reshape(-1, 3).std(axis=0).mean() < 12
            if not mojang_red and not flat:
                print(f" — 들어갔다 ({int(time.time() - t0)}초)")
                time.sleep(2)
                return True
        print(".", end="", flush=True)
        time.sleep(3)
    print(" — 시간초과 (결과를 의심하라)")
    return False


# ══════════════════════════════════════════════════════════════════
#  ① 카메라 클라이언트 — :98 · work-dir 분리 · ARM64 네이티브 교체
# ══════════════════════════════════════════════════════════════════
def seed_cam_workdir():
    """카메라 work-dir 을 심는다 — 팩이 **조용히** 거절되지 않게.

    ★ options.txt: 새 work-dir 은 guiScale=auto 로 시작한다. kigibot 설정을 복사해 못 박는다.
      (렌더 거리만 낮춘다 — 클라 2개 소프트렌더는 무겁고, 대상은 6m 앞이라 무해하다.)

    ★★ servers.dat 가 **이 하네스의 급소**다 (2026-07-20 · 실측으로 찾았다).
      증상: 서버는 `[팩] kigicam 에게 보냈다` 를 찍는데 그 뒤가 **아무것도 없다** —
      「받는 중」도 「거절」도 「실패」도 없는 **침묵**이다. 클라 로그도
      `Reloading ResourceManager: vanilla` 뿐 — `server/...` 가 안 붙는다.
      ⇒ 팩이 없으니 검기 ItemDisplay 는 **모델이 없어 화면에 안 그려진다.**
         (검기는 잘 뜨고 있었다. 카메라가 못 본 것뿐이다 — 「0px」 를 검기 부재로 읽을 뻔했다.)
      까닭: 서버 리소스팩 수락 여부는 **서버 항목마다** servers.dat 의 `acceptTextures` 에 산다.
      새 work-dir 의 servers.dat 에는 그 플래그가 없어 클라가 팩 요청을 **말없이 무시**한다.
      kigibot 의 servers.dat 에는 `acceptTextures:1b` 가 박혀 있다 — 그래서 걔만 됐다.
      ⇒ 그 파일을 그대로 복사한다. 그러면 **픽셀 클릭이 아예 필요 없다** (프롬프트가 안 뜬다).
         (옛 방식인 좌표 클릭은 못 믿는다: 이 클라는 창이 뜨고 **스플래시까지만 70초**가 걸려
          「창 뜨고 10초 뒤 클릭」 은 늘 허공을 쳤다.)
    """
    CAM_WORK.mkdir(parents=True, exist_ok=True)
    src = MAIN_DIR / "options.txt"
    dst = CAM_WORK / "options.txt"
    if src.exists() and not dst.exists():
        shutil.copy2(src, dst)
        txt = dst.read_text()
        txt = re.sub(r"^renderDistance:.*$", "renderDistance:4", txt, flags=re.M)
        txt = re.sub(r"^simulationDistance:.*$", "simulationDistance:4", txt, flags=re.M)
        dst.write_text(txt)
    # ★ 매번 덮어쓴다 — 클라가 종료하며 acceptTextures 를 지운 채 다시 쓸 수 있다
    sdat = MAIN_DIR / "servers.dat"
    if sdat.exists():
        shutil.copy2(sdat, CAM_WORK / "servers.dat")
        print("[카메라] servers.dat 복사 (acceptTextures — 팩 자동 수락)")
    else:
        print("[카메라] ⚠ kigibot 의 servers.dat 이 없다 — 팩이 조용히 거절될 수 있다")


def wait_for_pack(logfile: Path, timeout=240, label="카메라"):
    """팩이 **실제로 켜졌는지** 클라 로그로 확인한다 (침묵을 성공으로 읽지 않는다).

    판정: `Reloading ResourceManager: vanilla, server/<...>` — server/ 가 붙어야 팩이 산 것이다.
    """
    print(f"[{label}] 리소스팩 적용 대기", end="", flush=True)
    t0 = time.time()
    while time.time() - t0 < timeout:
        if logfile.exists():
            txt = logfile.read_text(errors="replace")
            if re.search(r"Reloading ResourceManager: vanilla, server/", txt):
                print(f" — 켜졌다 ({int(time.time() - t0)}초)")
                return True
        print(".", end="", flush=True)
        time.sleep(3)
    print(" — ⚠ 팩이 안 켜졌다. 검기 모델이 안 보인다 (0px 를 검기 부재로 읽지 마라)")
    return False


def ensure_cam_client(force_restart=False):
    """★ ARM64 벽: Mojang 은 리눅스 LWJGL 네이티브를 x86_64 만 배급한다. 이 기계는 aarch64 다.
    그대로 띄우면 UnsatisfiedLinkError 로 죽는다. lwjgl 3.3.3 natives-linux-arm64 의 .so 를
    미리 뽑아 두고(scratch/arm64natives/bin), Mojang 네이티브 8개를 클래스패스에서 빼고
    우리 .so 를 심는다 — 두 번째 클라도 **똑같이** 해야 한다.
    """
    if window_of(CAM_DISPLAY) and not force_restart:
        print("[카메라] 이미 접속되어 있다")
        return
    if not NATIVES.is_dir() or not list(NATIVES.glob("*.so")):
        raise SystemExit(f"ARM64 네이티브가 없다: {NATIVES}")
    seed_cam_workdir()

    # ★ 새로 띄우기 전에 **이 디스플레이의 옛 클라를 거둔다** (2026-07-20 · 메모리 고갈 사건).
    #   왜: 하네스를 반복 실행하며 죽지 않은 클라가 **52개** 쌓여 가용 메모리가 1GB 까지 말랐고,
    #   재측정이 통째로 실패했다. 소프트렌더 클라는 한 대에 수 GB 다 — 누적되면 기계가 선다.
    #   라이브 서버·봇은 건드리지 않는다: work-dir 이름으로만 좁혀 죽인다.
    subprocess.run(["pkill", "-f", f"--work-dir {CAM_WORK}"], capture_output=True)
    subprocess.run(["pkill", "-f", str(CAM_WORK)], capture_output=True)
    time.sleep(2)
    print(f"[카메라] 마인크래프트 1.21.11 기동 (Xvfb {CAM_DISPLAY} · work-dir {CAM_WORK.name})")
    excl = []
    for art in ("lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-openal",
                "lwjgl-stb", "lwjgl-tinyfd", "lwjgl-jemalloc", "lwjgl-freetype"):
        excl += ["--exclude-lib", f"{art}::natives"]
    binz = []
    for so in sorted(NATIVES.glob("*.so")):
        binz += ["--include-bin", str(so)]

    cmd = [str(VENV_PMC),
           "--main-dir", str(MAIN_DIR),      # 에셋·라이브러리는 공유
           "--work-dir", str(CAM_WORK),      # 실행 자리는 분리
           "start", "--jvm", str(JAVA), "--resolution", f"{W}x{H}",
           "-u", CAM, "-i", CAM_UUID,
           *excl, *binz,
           "-s", AT.SERVER_HOST, "-p", str(AT.SERVER_PORT), "1.21.11"]
    log = open(CAM_LOG, "w")
    subprocess.Popen(cmd, cwd=ROOT, env=dict(os.environ, DISPLAY=CAM_DISPLAY),
                     stdout=log, stderr=log, stdin=subprocess.DEVNULL)

    for _ in range(180):
        time.sleep(2)
        if window_of(CAM_DISPLAY):
            break
    else:
        raise SystemExit(f"카메라 창이 안 떴다 — {CAM_LOG} 를 보라")
    # ★ 창이 떴다고 게임이 아니다 — 이 클라는 창 뜨고 **스플래시까지만 70초**가 걸린다(실측).
    print("[카메라] 창 확인 — 접속 대기 (소프트 렌더라 2~3분 걸린다)")
    wait_for_pack(CAM_LOG)
    wait_until_ingame(CAM_DISPLAY, label="카메라")


# ══════════════════════════════════════════════════════════════════
#  ② 카메라 배치 — 각도를 **계산**한다 (대충 찍지 않는다)
# ══════════════════════════════════════════════════════════════════
def read_pos(rcon: Rcon, who: str):
    out = rcon.cmd(f"data get entity {who} Pos")
    n = re.findall(r"(-?\d+(?:\.\d+)?)d", out)
    if len(n) < 3:
        raise SystemExit(f"{who} 좌표를 못 읽었다: {out!r}")
    return tuple(float(x) for x in n[:3])


def read_rot(rcon: Rcon, who: str):
    out = rcon.cmd(f"data get entity {who} Rotation")
    n = re.findall(r"(-?\d+(?:\.\d+)?(?:[eE]-?\d+)?)f", out)
    if len(n) < 2:
        raise SystemExit(f"{who} 시선을 못 읽었다: {out!r}")
    return float(n[0]), float(n[1])


def place_camera(rcon: Rcon, angle: str, mode: str = "spectator"):
    """kigibot 을 기준으로 카메라를 세우고 **정확히 kigibot 을 바라보게** 한다.

    ★ 마인크래프트 좌표계: yaw 0 = +Z(남), 90 = -X(서), 180 = -Z(북), 270 = +X(동).
      시선벡터 f = (-sin yaw, cos yaw). 방위각 a 만큼 봇 둘레를 돈 자리는 yaw' = yaw + a.
      (a=180 이면 봇의 **등 뒤**, a=0 이면 봇의 **정면 앞**, a=90 이면 옆.)
    ★ 되돌아보는 각은 삼각함수로 낸다 — 카메라 눈(발밑+1.62)에서 봇 가슴(발밑+1.1)을 향한
      벡터로 yaw=atan2(-dx, dz), pitch=atan2(-dy, √(dx²+dz²)) 를 구한다.
    """
    dist, dy_off, azim = ANGLES[angle]
    bx, by, bz = read_pos(rcon, BOT)
    byaw, _bpitch = read_rot(rcon, BOT)

    a = math.radians(byaw + azim)
    cx = bx + (-math.sin(a)) * dist
    cz = bz + (math.cos(a)) * dist
    cy = by + dy_off

    dx = bx - cx
    dz = bz - cz
    dy = (by + AIM_Y) - (cy + EYE)
    yaw = math.degrees(math.atan2(-dx, dz))
    pitch = math.degrees(math.atan2(-dy, math.hypot(dx, dz)))

    rcon.cmd(f"gamemode {mode} {CAM}")
    rcon.cmd(f"tp {CAM} {cx:.3f} {cy:.3f} {cz:.3f} {yaw:.2f} {pitch:.2f}")
    time.sleep(0.4)
    rcon.cmd(f"tp {CAM} {cx:.3f} {cy:.3f} {cz:.3f} {yaw:.2f} {pitch:.2f}")
    print(f"[카메라] {angle:<5} pos=({cx:.2f},{cy:.2f},{cz:.2f}) "
          f"yaw={yaw:.1f} pitch={pitch:.1f}  ← 봇({bx:.2f},{by:.2f},{bz:.2f}) yaw={byaw:.1f}")
    return dict(angle=angle, cam=(cx, cy, cz), yaw=yaw, pitch=pitch,
                bot=(bx, by, bz), bot_yaw=byaw)


def green_mask(arr):
    """검기 초록 마스크 — 문턱은 kigi_autotest 와 **같은 값**을 쓴다 (실측 g>40 & g−r>18 & g−b>12)."""
    r, g, b = arr[..., 0], arr[..., 1], arr[..., 2]
    return (g > AT.G_MIN) & (g - r > AT.GR_MIN) & (g - b > AT.GB_MIN)


def static_green(display, n=6, pause=0.7):
    """★ 스윙 **전에** 화면에 이미 있는 초록을 찍어 둔다 — 그것은 검기가 아니다.

    왜 반드시 있어야 하나 (2026-07-20 · 첫 카메라 촬영이 통째로 거짓이었다):
      첫 판에서 182프레임 **전부**가 「검출」로 나왔다. 면적도 bbox 도 한 픽셀 안 틀리고 똑같은
      243px @ (460,578)-(819,669). 검기가 아니라 **HUD 의 초록 글자(「막기」)와 경험치 바**였다.
      검기는 그 판에 한 번도 안 떴는데(팩이 없었다) 표는 「6회 전부 검출」이라 말할 뻔했다.
      스펙테이터라 핫바는 없지만 플러그인 액션바·사이드바는 여전히 그려진다. F1 은 못 믿는다
      (이 환경에서 키 입력이 안 먹는 것이 애초에 이 하네스가 생긴 이유다).
    ⇒ 정지 화면의 초록을 모아 **빼고 센다.** 검기는 움직이는 것이고 HUD 는 붙박이다.
      1px 번짐(글자 앤티앨리어싱·바 애니메이션)까지 삼키게 3x3 으로 한 칸 부풀린다.
    """
    from PIL import Image
    import numpy as np
    acc = None
    probe = SCRATCH / ".static_green.png"
    for _ in range(n):
        grab_one(display, probe)
        if not probe.exists() or probe.stat().st_size == 0:
            continue
        m = green_mask(np.asarray(Image.open(probe).convert("RGB")).astype(np.int16))
        acc = m if acc is None else (acc | m)
        time.sleep(pause)
    if acc is None:
        return None
    d = acc.copy()                       # 3x3 팽창 — 한 픽셀 흔들려도 HUD 로 친다
    for ax in (0, 1):
        for s in (-1, 1):
            d |= np.roll(acc, s, axis=ax)
    print(f"[촬영] 붙박이 초록(HUD) {int(acc.sum())}px 를 빼고 센다 → 팽창 후 {int(d.sum())}px")
    return d


def analyze_masked(outdir: Path, min_area: int, exclude):
    """kigi_autotest.analyze 와 같은 문턱 · 같은 출력. 다만 붙박이 초록을 뺀다."""
    import numpy as np
    from PIL import Image
    frames = sorted(outdir.glob("frame_*.png"))
    rows = []
    for f in frames:
        a = np.asarray(Image.open(f).convert("RGB")).astype(np.int16)
        mask = green_mask(a)
        if exclude is not None:
            mask &= ~exclude
        area = int(mask.sum())
        if area >= min_area:
            ys, xs = np.nonzero(mask)
            rows.append({"frame": f.name, "area": area,
                         "cx": int(xs.mean()), "cy": int(ys.mean()),
                         "x0": int(xs.min()), "x1": int(xs.max()),
                         "y0": int(ys.min()), "y1": int(ys.max())})
    return len(frames), rows


def bot_in_frame(display) -> tuple[bool, int]:
    """화면에 **kigibot 의 몸**이 보이는가 — 카메라가 딴 데를 보고 있지 않은지 눈으로 판정한다.

    ★ 왜: tp 는 조용히 어긋난다(청크 미로딩·스펙테이터 보간). 아무것도 안 보이는 화면을 찍고
      「검기 0px」 를 얻으면 그건 검기가 없는 게 아니라 **카메라가 딴 데를 본 것**이다.
      흰 무대 위 어두운 스킨 픽셀을 센다 — 초록(검기)은 제외해 검기를 몸으로 오인하지 않는다.
    """
    from PIL import Image
    import numpy as np
    probe = SCRATCH / ".cam_probe.png"
    grab_one(display, probe)
    if not probe.exists() or probe.stat().st_size == 0:
        return False, 0
    a = np.asarray(Image.open(probe).convert("RGB")).astype(int)
    band = a[int(H * 0.25):int(H * 0.90), int(W * 0.30):int(W * 0.70)]
    r, g, b = band[..., 0], band[..., 1], band[..., 2]
    dark = int(((band.sum(axis=2) < 330) & ~((g - r > 15) & (g - b > 10))).sum())
    return dark > 800, dark


# ══════════════════════════════════════════════════════════════════
#  ③ 한 각도 촬영
# ══════════════════════════════════════════════════════════════════
def shoot(rcon, angle, outdir: Path, args, bot_win):
    place_camera(rcon, angle, args.cam_mode)
    time.sleep(2.5)
    ok, dark = bot_in_frame(CAM_DISPLAY)
    print(f"[카메라] 프레임 안 봇 확인: {'보인다' if ok else '⚠ 안 보인다'} (어두운 픽셀 {dark})")
    still = outdir / "camera_view.png"
    outdir.mkdir(parents=True, exist_ok=True)
    grab_one(CAM_DISPLAY, still)

    # ★ 스윙 전 붙박이 초록(HUD)을 먼저 재 둔다 — 이것을 검기로 세면 표 전체가 거짓이 된다
    exclude = static_green(CAM_DISPLAY)

    print(f"[촬영] {CAM_DISPLAY} {args.fps}fps → {outdir}")
    cap = start_capture(CAM_DISPLAY, outdir, args.fps)
    try:
        swing(BOT_DISPLAY, bot_win, args.swings, args.gap)
        time.sleep(1.5)
    finally:
        stop_capture(cap)

    total, rows = analyze_masked(outdir, args.min_area, exclude)
    bursts = AT.group_bursts(rows)
    return dict(angle=angle, outdir=outdir, total=total, rows=rows,
                bursts=bursts, still=still, bot_visible=ok, dark=dark)


def report(res):
    o = res
    print()
    print("═" * 78)
    print(f"  카메라 봇 — 각도 {o['angle']}  ({o['outdir']})")
    print("═" * 78)
    print(f"  촬영 프레임      : {o['total']} 장")
    print(f"  검기 검출 프레임 : {len(o['rows'])} 장")
    print(f"  검기 발생 횟수   : {len(o['bursts'])} 회")
    print(f"  프레임 안 봇     : {'보인다' if o['bot_visible'] else '⚠ 안 보인다'} ({o['dark']}px)")
    print(f"  검출 문턱        : g>{AT.G_MIN} & g-r>{AT.GR_MIN} & g-b>{AT.GB_MIN}")
    print("-" * 78)
    if not o["rows"]:
        print("  ⚠ 초록 픽셀 0 — 검기가 안 떴거나, 팩이 없거나, 카메라가 딴 데를 본다.")
        print(f"    한 장 먼저 보라: {o['still']}")
    else:
        print(f"  {'프레임':<16}{'면적(px)':>10}{'cx':>7}{'cy':>7}   bbox")
        for r in o["rows"]:
            print(f"  {r['frame']:<16}{r['area']:>10}{r['cx']:>7}{r['cy']:>7}   "
                  f"({r['x0']},{r['y0']})-({r['x1']},{r['y1']})")
        print("-" * 78)
        for i, b in enumerate(o["bursts"], 1):
            pk = max(b, key=lambda r: r["area"])
            print(f"  {i}회차: {len(b)}프레임 · 최대 {pk['area']}px @ ({pk['cx']},{pk['cy']}) [{pk['frame']}]")
    print("═" * 78)


def main():
    ap = argparse.ArgumentParser(description="검기 — 카메라 봇 자동 검증")
    ap.add_argument("--angle", default="back",
                    help="back|front|side|high|all (쉼표로 여러 개도 됨)")
    ap.add_argument("--swings", type=int, default=8)
    ap.add_argument("--gap", type=float, default=1.4)
    ap.add_argument("--fps", type=int, default=15, help="카메라 촬영 fps (클라 2개라 낮춘다)")
    ap.add_argument("--item", default="minecraft:iron_sword")
    ap.add_argument("--min-area", type=int, default=40)
    ap.add_argument("--night", action="store_true")
    ap.add_argument("--cam-mode", default="spectator",
                    choices=["spectator", "creative"],
                    help="카메라 게임모드 (spectator 가 지형에 안 걸린다)")
    ap.add_argument("--restart-cam", action="store_true")
    ap.add_argument("--outdir", default=None)
    args = ap.parse_args()

    angles = list(ANGLES) if args.angle == "all" else [a.strip() for a in args.angle.split(",")]
    for a in angles:
        if a not in ANGLES:
            raise SystemExit(f"모르는 각도: {a} (가능: {', '.join(ANGLES)}, all)")

    stamp = time.strftime("%Y%m%d-%H%M%S")
    base = Path(args.outdir) if args.outdir else SCRATCH / "camtest" / stamp

    ensure_xvfb(BOT_DISPLAY)
    ensure_xvfb(CAM_DISPLAY)
    AT.ensure_server()
    AT.ensure_client()                      # kigibot (:99)
    ensure_cam_client(force_restart=args.restart_cam)

    bot_win = window_of(BOT_DISPLAY)
    if not bot_win:
        raise SystemExit("kigibot 창(:99)을 못 찾았다")

    results = []
    rcon = Rcon()
    try:
        held = AT.prepare_scene(rcon, args.item, args.night)
        print(f"[무대] 든 것: {held.strip()[:90]}")
        # ★ 카메라도 무대 위 하늘로 끌어온다 — 안 그러면 스폰 지점의 지하에서 허공을 본다
        sx, sy, sz = AT.STAGE
        rcon.cmd(f"gamemode {args.cam_mode} {CAM}")
        rcon.cmd(f"tp {CAM} {sx} {sy} {sz}")
        time.sleep(2.0)
        wait_until_ingame(CAM_DISPLAY, label="카메라")
        for a in angles:
            res = shoot(rcon, a, base / a, args, bot_win)
            report(res)
            sheet = AT.contact_sheet(res["outdir"], res["bursts"])
            if sheet:
                print(f"  한 장 요약: {sheet}")
            results.append(res)
    finally:
        rcon.close()

    print()
    print("━" * 78)
    print("  각도별 요약")
    print(f"  {'각도':<8}{'프레임':>8}{'검출':>7}{'횟수':>7}{'최대면적px':>12}   {'최대 위치':<14}대표 화면")
    for r in results:
        pk = max((x for b in r["bursts"] for x in b), key=lambda x: x["area"], default=None)
        peak = pk["area"] if pk else 0
        loc = f"({pk['cx']},{pk['cy']})" if pk else "-"
        print(f"  {r['angle']:<8}{r['total']:>8}{len(r['rows']):>7}{len(r['bursts']):>7}"
              f"{peak:>12}   {loc:<14}{r['still']}")
    print("━" * 78)
    print(f"\n  재실행: scripts/kigi_cam_test.sh --angle {args.angle} --swings {args.swings}\n")


if __name__ == "__main__":
    main()
