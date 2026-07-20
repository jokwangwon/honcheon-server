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
import vfx_detect as DET  # noqa: E402
import vfx_preflight as PF  # noqa: E402

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


def place_camera(rcon: Rcon, angle: str, mode: str = "spectator",
                 target: str = None, aim_y: float = None, dist_mul: float = 1.0,
                 quiet: bool = False, dim: str = None):
    """표적을 기준으로 카메라를 세우고 **정확히 그 표적을 바라보게** 한다.

    ★ 마인크래프트 좌표계: yaw 0 = +Z(남), 90 = -X(서), 180 = -Z(북), 270 = +X(동).
      시선벡터 f = (-sin yaw, cos yaw). 방위각 a 만큼 표적 둘레를 돈 자리는 yaw' = yaw + a.
      (a=180 이면 표적의 **등 뒤**, a=0 이면 **정면 앞**, a=90 이면 옆.)
    ★ 되돌아보는 각은 삼각함수로 낸다 — 카메라 눈(발밑+1.62)에서 표적의 가슴께를 향한
      벡터로 yaw=atan2(-dx, dz), pitch=atan2(-dy, √(dx²+dz²)) 를 구한다.

    ★ target 은 플레이어 이름이든 **엔티티 UUID** 든 된다 (`data get entity <uuid>`).
      몹 촬영에서 이 축이 필요하다 — 호랑이는 kigibot 이 아니다.
      aim_y/dist_mul 은 표적의 덩치에 맞춘다 (호랑이는 낮고 넓다 — 더 멀리서 더 낮게 본다).
    """
    who = target or BOT
    aim = AIM_Y if aim_y is None else aim_y
    dist, dy_off, azim = ANGLES[angle]
    dist *= dist_mul
    bx, by, bz = read_pos(rcon, who)
    byaw, _bpitch = read_rot(rcon, who)

    a = math.radians(byaw + azim)
    cx = bx + (-math.sin(a)) * dist
    cz = bz + (math.cos(a)) * dist
    cy = by + dy_off

    dx = bx - cx
    dz = bz - cz
    dy = (by + aim) - (cy + EYE)
    yaw = math.degrees(math.atan2(-dx, dz))
    pitch = math.degrees(math.atan2(-dy, math.hypot(dx, dz)))

    # ★ **차원을 명시해서** 옮긴다 (`execute in <dim> run tp`).
    #   맨 `tp <이름> <좌표>` 는 **그 사람이 지금 있는 차원**의 좌표로 간다. 카메라 클라는
    #   가끔 끊겼다 붙는데(소프트렌더), 연무장은 재접속한 사람을 오버월드로 내보낸다
    #   (Dojang 의 onJoin 안전장치). 그러면 카메라는 오버월드의 **똑같이 생긴 초원**에서
    #   같은 좌표에 서서 「빈 들판」을 찍는다 — 표적은 연무장에 있는데.
    #   차원을 박아 두면 떠내려가도 매번 제자리로 끌려온다.
    # ★ 차원은 **불러 준 쪽이 알려 준다.** 선택자(@e[...])로 읽으면 빈 대답이 오는 일이 있고
    #   (실측), 그때 조용히 prefix 가 빠져 카메라가 옛 차원에 남는다 — 그것이 「빈 들판」의 정체다.
    d = dim or read_dim(rcon, who)
    prefix = f"execute in {d} run " if d and d != "?" else ""
    rcon.cmd(f"gamemode {mode} {CAM}")
    rcon.cmd(f"{prefix}tp {CAM} {cx:.3f} {cy:.3f} {cz:.3f} {yaw:.2f} {pitch:.2f}")
    time.sleep(0.4)
    rcon.cmd(f"{prefix}tp {CAM} {cx:.3f} {cy:.3f} {cz:.3f} {yaw:.2f} {pitch:.2f}")
    if not quiet:
        label = who if len(who) < 20 else who[:8] + "…"
        print(f"[카메라] {angle:<5} pos=({cx:.2f},{cy:.2f},{cz:.2f}) "
              f"yaw={yaw:.1f} pitch={pitch:.1f}  ← {label}({bx:.2f},{by:.2f},{bz:.2f}) yaw={byaw:.1f}")
    return dict(angle=angle, cam=(cx, cy, cz), yaw=yaw, pitch=pitch,
                bot=(bx, by, bz), bot_yaw=byaw)


# ══════════════════════════════════════════════════════════════════
#  ②-b 몹 무대 — 형체(MobDisplay)가 실제로 붙는가 · 다리가 실제로 걷는가
# ══════════════════════════════════════════════════════════════════
#
# ★ 왜 `summon ravager {Tags:[…]}` 로는 안 되는가 (오늘 부딪힌 벽)
#   형체 부착은 **태그가 아니라 PDC** 로 판정된다 — MobDisplay.scan() 이 보는 것은
#   `HuntingGrounds.KEY_ID`(honcheon:foe_id) 라는 PersistentDataContainer 키다.
#   바닐라 `summon` 은 PDC 를 못 심는다(NBT 의 BukkitValues 를 손으로 쓰는 건 취약하다).
#   ⇒ **등록부를 지나는 유일한 정문**은 `HuntingGrounds.spawnById()` 이고, 그것을 부르는
#     인게임 손은 `/혼천 시험 몹 <id>` 다. 그런데 그 명령은 **몸**을 요구한다(콘솔 거절).
#   ⇒ 그래서 `/혼천 대행` 을 냈다: 콘솔이 봇의 손을 빌려 그 명령을 친다.
#     RCON → 혼천 대행 kigibot 혼천 시험 몹 horangi 걷기 → spawnById → PDC → attach.
#
# ★ 「걷기」가 왜 따로 있는가
#   `Dojang.mob` 은 기본이 `setAI(false)` 다(계기용). 그런데 형체의 **다리 관절 위상은
#   「실제로 움직인 거리」로 돈다** (MobDisplay: `rig.phase += moved * walkBobRate`).
#   AI 를 끄면 다리는 영원히 안 흔들린다 — 걷기를 보려면 AI 를 켜고 표적을 줘야 한다.

MOB_TAG = "camtarget"          # 카메라가 조준할 몸에 붙이는 표 (선택자 하나로 잡는다)
MOB_SEL = f"@e[tag={MOB_TAG},limit=1]"


def _tag_count(reply: str) -> int:
    """`/tag … add X` 의 대답에서 **몇 마리에 붙었는지**를 읽는다.

    바닐라에는 「세어라」 명령이 없다. 그런데 tag 는 붙인 개수를 말해 준다 —
    "Added tag 'x' to N entities" / 한 마리면 "…to <이름>". 그 수를 센다.
    """
    m = re.search(r"to (\d+) entit", reply)
    if m:
        return int(m.group(1))
    if "Added tag" in reply or "Removed tag" in reply:
        return 1               # 한 마리는 이름으로 말한다
    return 0


DOJANG_DIM = "minecraft:honcheon_dojang"


def read_dim(rcon: Rcon, who: str) -> str:
    out = rcon.cmd(f"execute as {who} run data get entity @s Dimension")
    m = re.search(r'"([a-z_]+:[a-z_]+)"', out)
    return m.group(1) if m else "?"


def ensure_dojang(rcon: Rcon, host: str, tries: int = 3) -> bool:
    """봇이 **실제로 연무장 안에 서 있는지** 확인한다 — 믿지 않고 잰다.

    ★ 왜 재야 하나 (실측 2026-07-20): `/혼천 연무장` 은 **상태를 가진** 명령이다.
      들어간 채로 밖으로 tp 되면 연무장의 장부는 「안에 있다」로 남고, 그 상태에서 다시
      `연무장` 을 쳐도 **아무 일도 안 일어난다** — 그리고 거절 메시지는 **봇의 채팅**으로
      가서 RCON 에는 안 보인다. 「대행 성공」만 보고 들어갔다고 믿으면 그 다음 단계가
      통째로 헛돈다(실제로 두 판을 그렇게 날렸다).
    ⇒ 한 번 `귀환` 해서 상태를 풀고 `연무장` 으로 들어간 뒤, **차원을 읽어 확인**한다.
    """
    for i in range(1, tries + 1):
        dim = read_dim(rcon, host)
        if dim == DOJANG_DIM:
            print(f"[연무장] {host} 확인 — {dim}")
            return True
        rcon.cmd(f"혼천 대행 {host} 혼천 귀환")
        time.sleep(1.5)
        rcon.cmd(f"혼천 대행 {host} 혼천 연무장")
        time.sleep(2.5)
        print(f"[연무장] 진입 시도 {i}/{tries} — 지금 차원 {read_dim(rcon, host)}")
    dim = read_dim(rcon, host)
    if dim == DOJANG_DIM:
        return True
    print(f"[연무장] ⚠ 못 들어갔다 — {host} 는 {dim} 에 있다 (봇 채팅에 이유가 있을 것이다)")
    return False


def mob_body_type(mob_id: str) -> str:
    """등록부가 말하는 **바닐라 몸**(호랑이=ravager) — 선택자를 좁히는 데 쓴다.

    ★ 왜 필요한가: 「봇 곁의 플레이어 아닌 것 중 가장 가까운 것」으로 잡으면 연무장의
      허수아비·명패·interaction 을 집는다(실측으로 파츠 0개를 얻었다). 몸의 종류를
      **등록부에서 읽어** 그 타입만 고른다 — 하네스가 config 를 추측하지 않는다.
    """
    import yaml
    p = ROOT / "config" / "mob_models.yml"
    try:
        doc = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
        body = (doc.get("foes", {}).get(mob_id, {}) or {}).get("body")
        if body:
            return str(body).lower()
    except Exception as e:
        print(f"[몹] ⚠ mob_models.yml 을 못 읽었다 ({e}) — 타입 없이 고른다")
    return None


def summon_mob(rcon: Rcon, mob_id: str, walking: bool, host: str = BOT):
    """등록부의 몹을 무대에 세운다 — **대행**으로 봇의 손을 빌려서.

    반환: (성공?, 선택자). 조용히 실패하지 않는다 — 대행의 대답을 그대로 보여 준다.
    """
    # ★ 무대는 **연무장**이다 — 검기의 흰 발판(하늘 200,100,200)이 아니다.
    #   왜(실측 2026-07-20): 흰 발판에서 `혼천 시험 몹` 은 「대행 성공」을 내고도 몹이 안 섰다.
    #   Dojang.mob 은 `footing()` 으로 **앞 4칸의 설 자리**를 찾는데 그 발판에서는 못 찾았고,
    #   실패 메시지는 **봇의 채팅**으로 가서 RCON 에는 안 보였다(조용한 실패의 전형).
    #   연무장은 애초에 몹 시험용으로 지어진 월드다 — 바닥·항상 낮·자연 스폰 없음. 거기로 간다.
    if not ensure_dojang(rcon, host):
        return False, None
    # ★ 카메라도 **정식으로** 든다. 몰래 tp 로 넣으면 연무장이 도로 내보낸다 —
    #   그리고 오버월드도 같은 초원이라 **똑같아 보이는 빈 들판**을 찍게 된다.
    #   (이 함정에 세 판을 빠졌다: 파츠는 7개로 잡히는데 화면엔 아무것도 없었다.)
    rcon.cmd(f"gamemode survival {CAM}")
    if not ensure_dojang(rcon, CAM):
        print("[연무장] ⚠ 카메라가 못 들어갔다 — 빈 들판을 찍게 된다")
        return False, None
    rcon.cmd(f"gamemode spectator {CAM}")
    time.sleep(6.0)          # 교차 차원 뒤 청크·엔티티 스트리밍 — 서두르면 빈 화면이다

    # 지난 판의 몸과 파츠를 걷는다 — 안 그러면 파츠가 7·14·28 로 쌓여 계측이 뒤섞인다
    rcon.cmd(f"혼천 대행 {host} 혼천 시험 몹 치움")
    time.sleep(1.0)

    rcon.cmd(f"tag @e[tag={MOB_TAG}] remove {MOB_TAG}")   # 지난 판의 표를 걷는다
    walk = " 걷기" if walking else ""
    reply = rcon.cmd(f"혼천 대행 {host} 혼천 시험 몹 {mob_id}{walk}")
    print(f"[몹] 대행 대답: {reply.strip()[:200]}")
    if "대행 성공" not in reply:
        print(f"[몹] ⚠ 대행이 성공을 말하지 않았다 — 소환이 안 됐을 수 있다")
    time.sleep(1.2)

    # 방금 선 몸을 잡는다 — 봇 곁의, 플레이어가 아닌 살아 있는 것.
    # ★ 반드시 `execute at <봇>` 로 **자리를 준다**: RCON 콘솔에는 위치가 없어서
    #   맨 `@e[distance=..]` 는 조용히 "No entity was found" 가 된다 (실측으로 물린 자리다).
    body = mob_body_type(mob_id)
    filt = (f"type={body}" if body else
            "type=!player,type=!item_display,type=!text_display,type=!interaction")
    got = _tag_count(rcon.cmd(
        f"execute at {host} run tag @e[{filt},distance=..16,limit=1,sort=nearest] add {MOB_TAG}"))
    if got == 0:
        print("[몹] ⚠ 세워진 몸을 못 찾았다")
        return False, None
    name = rcon.cmd(f"data get entity {MOB_SEL} CustomName").strip()
    print(f"[몹] 잡았다 → {MOB_SEL}  {name[:160]}")
    return True, MOB_SEL


def summon_bm(rcon: Rcon, model: str, host: str = BOT):
    """BetterModel 의 형체를 무대에 세운다 — 우리 등록부가 아니라 `/bm spawn` 을 지난다.

    우리 MobDisplay 와 **다른 길**이라는 것이 요점이다: PDC foe_id 도, item_display 파츠도
    쓰지 않는다(BetterModel 은 제 방식으로 표시 엔티티를 관리한다). 그러니 여기서 재는 것은
    파츠 수가 아니라 **프레임 간 변화량** 하나다 — 팔이 실제로 도는가.

    반환: (성공?, 선택자)
    """
    if not ensure_dojang(rcon, host):
        return False, None
    rcon.cmd(f"gamemode survival {CAM}")
    if not ensure_dojang(rcon, CAM):
        print("[연무장] ⚠ 카메라가 못 들어갔다 — 빈 들판을 찍게 된다")
        return False, None
    rcon.cmd(f"gamemode spectator {CAM}")
    time.sleep(6.0)

    rcon.cmd(f"tag @e[tag={MOB_TAG}] remove {MOB_TAG}")
    # 지난 판의 형체·고아 파츠를 걷는다 — 안 그러면 **남의 움직임을 이 모델의 것으로 읽는다**
    #   (실측 2026-07-20: spawn 이 실패했는데 직전 판의 호랑이가 잡혀 "팔이 돈다" 가 나왔다)
    rcon.cmd(f"kill @e[type=!player,tag={MOB_TAG}]")
    rcon.cmd("kill @e[type=item_display]")
    rcon.cmd("kill @e[type=text_display]")
    time.sleep(0.8)

    # ★ `bm spawn` 은 **콘솔에서 안 된다** (자리가 없어 인자 파싱이 거부된다) — 대행으로도
    #   조용히 실패했다. 대신 **봇 자신을 그 형체로 변장**시킨다: 봇은 이미 무대에 서 있고
    #   카메라가 겨누는 대상이라 자리 문제가 없다.
    rcon.cmd(f"혼천 대행 {host} bm undisguise")
    time.sleep(0.5)
    reply = rcon.cmd(f"혼천 대행 {host} bm disguise {model}")
    print(f"[BM] 변장 대답: {reply.strip()[:160]}")
    if "대행 성공" not in reply:
        print("[BM] ⚠ 대행이 성공을 말하지 않았다")
        return False, None
    time.sleep(2.0)

    # ★ 파츠를 **세지 않는다.** BetterModel 3.x 는 표시 엔티티를 **패킷으로** 보낸다 —
    #   월드에 실물이 없으니 `@e` 로는 원리상 0 이다. 여기서 파츠 0 은 고장이 아니다.
    #   그래서 이 갈래의 유일한 증인은 **화면**이다 (변화량과 그림).
    print(f"[BM] 변장 걸었다 — 표적은 봇 자신({host}). 파츠 수는 재지 않는다(패킷 방식)")
    return True, host


def mob_foe_id(rcon: Rcon) -> str:
    """그 몸의 **PDC** 에 등록부 id 가 실제로 박혔는가 — 형체 부착의 전제다.

    이것이 없으면 MobDisplay 는 그 몸을 쳐다보지도 않는다. 그러니 파츠를 세기 전에
    **여기부터** 본다 (파츠 0개일 때 「왜」를 정확히 답하기 위해서다).

    ★ PDC 는 루트 NBT 의 `BukkitValues` 아래 산다 — `data get entity <sel> data` 가 아니다
      (그 길은 "Found no elements" 를 낸다). 전체 NBT 를 받으면 RCON 이 잘라 먹으니
      **경로를 콕 집어** 묻는다.
    """
    out = rcon.cmd(f'data get entity {MOB_SEL} BukkitValues."honcheon:foe_id"')
    m = re.search(r'entity data:\s*"([^"]+)"', out)
    return m.group(1) if m else None


def count_parts(rcon: Rcon) -> int:
    """몸에 붙은 **형체 파츠(item_display) 개수** — 형체가 실제로 붙었는가의 실측.

    말로 「붙었다」 하지 않는다. 몹 주위 4m 안의 item_display 를 **센다**.
    호랑이(v2 관절)는 7개여야 한다: torso · head · tail · 다리 4.
    """
    n = _tag_count(rcon.cmd(
        f"execute at {MOB_SEL} run tag @e[type=item_display,distance=..4] add _partprobe"))
    rcon.cmd("tag @e[tag=_partprobe] remove _partprobe")
    return n


def green_mask(arr):
    """검기 초록 마스크 — **정본은 vfx_detect 하나뿐이다** (복사본을 없앴다).

    옛 코드는 여기에 문턱을 한 번 더 적어 뒀다. 한쪽만 고치면 다른 쪽은 옛 문턱으로 계속
    재고, 그 차이는 조용하다. 이제 두 하네스가 **같은 눈**을 쓰고, 그 눈은 매 실행마다
    합성 이미지로 자가시험을 받는다.
    """
    return DET.green_mask(arr)


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
    frames = []
    probe = SCRATCH / ".static_green.png"
    for _ in range(n):
        grab_one(display, probe)
        if probe.exists() and probe.stat().st_size > 0:
            frames.append(np.asarray(Image.open(probe).convert("RGB")).astype(np.int16))
        time.sleep(pause)
    if not frames:
        return None
    # 마스크 만들기·팽창은 vfx_detect 가 한다 — 그쪽이 자가시험(③·③-b)을 받는 코드다
    raw = int(sum(int(DET.green_mask(a).sum()) for a in frames) / len(frames))
    d = DET.build_static_mask(frames)
    print(f"[촬영] 붙박이 초록(HUD) 평균 {raw}px 를 빼고 센다 → 팽창 후 {int(d.sum())}px")
    return d


def analyze_masked(outdir: Path, min_area: int, exclude):
    """kigi_autotest.analyze 와 **같은 함수**를 쓴다 (문턱도 출력도 하나뿐이다)."""
    return AT.analyze(outdir, min_area, exclude=exclude)


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


def shoot_mob(rcon, angle, outdir: Path, args, target):
    """몹을 촬영한다 — 검기가 아니라 **형체와 걸음**을 본다 (초록 판정을 쓰지 않는다).

    검기 하네스의 초록 마스크는 여기서 의미가 없다. 대신 재는 것은 두 가지다:
      ① 파츠 수 (item_display) — 형체가 붙었는가
      ② 프레임 간 **변화량** — 다리가 실제로 움직이는가 (정지 화면이면 0 이다)
    """
    outdir.mkdir(parents=True, exist_ok=True)
    # 몹은 봇 곁에 선다 — 그러니 **봇의 차원**이 곧 표적의 차원이다 (선택자보다 이쪽이 확실하다)
    dim = read_dim(rcon, BOT)
    place_camera(rcon, angle, args.cam_mode, target=target,
                 aim_y=args.aim_y, dist_mul=args.dist_mul, dim=dim)
    # ★ 차원을 건넌 뒤에는 **가만히 둬야** 클라가 새 월드를 다 그린다 (실측 8초면 넉넉하다).
    #   매 초 다시 tp 하면 클라는 영원히 로딩 중이라 **직전 월드(오버월드)를 계속 그린다** —
    #   그래서 파츠는 7개로 잡히는데 화면은 빈 초원이었다. 서두르는 것이 곧 거짓 화면이다.
    time.sleep(8.0)

    # ★ 믿지 말고 **재라** — 카메라가 표적과 같은 월드에 있는가.
    #   오버월드도 연무장도 y=-60 의 평지라 화면만 봐서는 구별이 안 된다. 그 닮음이
    #   「빈 들판」 다섯 판을 만들었다. 여기서 어긋나면 고치고, 못 고치면 **말하고 멈춘다**.
    for _ in range(3):
        cd, td = read_dim(rcon, CAM), dim
        if cd == td:
            break
        print(f"[카메라] ⚠ 차원이 어긋났다 (카메라 {cd} ≠ 표적 {td}) — 다시 끌어온다")
        place_camera(rcon, angle, args.cam_mode, target=target,
                     aim_y=args.aim_y, dist_mul=args.dist_mul, quiet=True, dim=dim)
        time.sleep(8.0)
    cd, td = read_dim(rcon, CAM), dim
    print(f"[카메라] 차원 확인: 카메라 {cd} / 표적 {td}"
          + ("" if cd == td else "   ★ 어긋난 채로 찍는다 — 이 화면을 근거로 쓰지 마라"))

    parts = count_parts(rcon)
    foe = mob_foe_id(rcon)
    print(f"[몹] PDC foe_id = {foe or '⚠ 없다'}   형체 파츠(item_display) = {parts}개")

    # ★ **찍기 전에 눈이 살았는지 본다** — 8초를 찍고 나서 「죽은 화면이었다」를 아는 건 늦다.
    #   실측 2026-07-20: 연무장에서 카메라를 표적 앞 5.4칸(z=5.9)에 두면 llvmpipe 가 그 시야를
    #   **잘못 렌더한다** — 반투명 판이 화면을 덮고 바닥이 황토빛으로 물들며, 그 그림은
    #   완전히 정지한다(141프레임 바이트 동일). 2.0·3.0·7.5칸에서는 멀쩡하다.
    #   변장을 풀어도, 구름을 꺼도, 클라·Xvfb 를 새로 띄워도 같은 자리면 같은 그림이다.
    #   → 원인은 Mesa 쪽이라 우리가 못 고친다. 대신 **그 자리를 피한다.**
    for attempt, mul in enumerate((args.dist_mul, args.dist_mul * 0.62, args.dist_mul * 1.35)):
        if camera_alive(CAM_DISPLAY):
            if attempt:
                print(f"[카메라] 거리 {mul:.2f}배에서 눈이 살아났다")
            break
        print(f"[카메라] ★ 화면이 죽었다 — 거리를 {mul:.2f}배로 흔들어 다시 세운다")
        place_camera(rcon, angle, args.cam_mode, target=target,
                     aim_y=args.aim_y, dist_mul=mul, quiet=True, dim=dim)
        time.sleep(7.0)
    else:
        print("[카메라] ★ 세 자리 모두 죽은 화면이다 — 이 촬영은 근거가 못 된다")

    still = outdir / "mob_view.png"
    grab_one(CAM_DISPLAY, still)

    print(f"[촬영] {CAM_DISPLAY} {args.fps}fps · {args.seconds}초 → {outdir}")
    cap = start_capture(CAM_DISPLAY, outdir, args.fps)
    try:
        # 걷게 두는 동안 찍는다. 봇이 물러나면 몹이 따라온다 — 도착해 멈추면 보행 위상이 죽는다.
        # 카메라도 **매 초 다시 겨눈다**: 걷는 몹은 가만있지 않는다(고정 카메라는 빈 들판을 찍는다).
        end = time.time() + args.seconds
        last_aim = time.time()
        last_anim = 0.0
        while time.time() < end:
            # ★ BetterModel 애니메이션은 **한 번 재생하고 끝난다**(loop: once).
            #   재생 길이보다 촬영이 기니 주기적으로 다시 걸어야 팔이 도는 구간이 프레임에 남는다.
            #   안 그러면 대부분의 프레임이 idle 이라 「변화량 0」 이 나온다 — 없는 게 아니라 놓친 것이다.
            if getattr(args, "bm_anim", None) and time.time() - last_anim > 2.0:
                # 변장한 **봇 자신**에게 건다 — 대행이라야 「자리 있는 손」이 된다
                rcon.cmd(f"혼천 대행 {BOT} bm test {args.bm_model} {args.bm_anim}")
                last_anim = time.time()
            if args.walk:
                rcon.cmd(f"execute at {target} run tp {BOT} ^ ^ ^-3 facing entity {target}")
                # 다시 겨누는 것은 **드문드문** — 매 초 옮기면 클라가 로딩을 못 끝낸다(위 주석)
                if time.time() - last_aim > 4.0:
                    place_camera(rcon, angle, args.cam_mode, target=target,
                                 aim_y=args.aim_y, dist_mul=args.dist_mul,
                                 quiet=True, dim=dim)
                    last_aim = time.time()
            time.sleep(1.0)
    finally:
        stop_capture(cap)

    motion = frame_motion(outdir)
    return dict(angle=angle, outdir=outdir, still=still, parts=parts,
                foe=foe, motion=motion)


def camera_alive(display: str, gap: float = 2.2, thresh: int = 60) -> bool:
    """**눈이 살아 있는가** — 두 장을 사이 두고 긁어 서로 다른지 본다.

    살아 있는 마인크래프트 화면은 가만 둬도 변한다 (구름·하늘·HUD 시계·엔티티 숨결).
    두 장이 사실상 같으면 그건 「세상이 멈췄다」가 아니라 **「내가 못 본다」**다.
    이 물음을 **찍기 전에** 던져야 8초를 버리지 않는다.
    """
    import tempfile
    from PIL import Image
    import numpy as np
    with tempfile.TemporaryDirectory() as td:
        a, b = Path(td) / "a.png", Path(td) / "b.png"
        grab_one(display, a)
        time.sleep(gap)
        grab_one(display, b)
        try:
            xa = np.asarray(Image.open(a).convert("L")).astype(np.int16)
            xb = np.asarray(Image.open(b).convert("L")).astype(np.int16)
        except Exception:
            return True          # 못 읽으면 막지 않는다 — 검사기가 촬영을 잡아먹으면 안 된다
        if xa.shape != xb.shape:
            return True
        return int((np.abs(xa - xb) > 25).sum()) > thresh


def frame_motion(outdir: Path):
    """연속 프레임의 **차이**를 잰다 — 「걷는가」를 눈이 아니라 숫자로 답한다.

    형체가 붙어도 AI 가 꺼져 있으면 그림은 **완전히 정지**한다(다리 위상이 이동거리로 도니까).
    그러니 프레임 간 변화 픽셀 수가 0 에 가까우면 그것은 「안 걷는다」의 실측 증거다.
    """
    from PIL import Image
    import numpy as np
    frames = sorted(outdir.glob("*.png"))
    frames = [f for f in frames if f.name not in ("mob_view.png",)]
    if len(frames) < 3:
        return None
    diffs = []
    prev = None
    for f in frames:
        try:
            a = np.asarray(Image.open(f).convert("L")).astype(np.int16)
        except Exception:
            continue
        if prev is not None and prev.shape == a.shape:
            diffs.append(int((np.abs(a - prev) > 18).sum()))
        prev = a
    if not diffs:
        return None
    # ★ **화면이 얼었는가** — 「안 움직인다」와 「내 눈이 멀었다」를 가른다.
    #   실측 2026-07-20: Xvfb :98 의 프레임버퍼가 굳어 168프레임이 **바이트까지 동일**했다.
    #   그 화면에도 하늘·구름·HUD 가 있었으니 살아 있었다면 최소한의 흔들림은 남는다 —
    #   peak 이 한 자리면 그건 세상이 멈춘 것이 아니라 **카메라가 죽은 것**이다.
    #   여기서 0 을 「정지」로 적어 넘기면 그 뒤 판단이 전부 거짓 위에 선다.
    frozen = max(diffs) <= 2
    return dict(n=len(diffs), mean=int(sum(diffs) / len(diffs)),
                peak=max(diffs), zero=sum(1 for d in diffs if d < 50),
                frozen=frozen)


def make_gif(outdir: Path, out: Path, fps: int = 10, max_frames: int = 90):
    """걷는 모습을 **한 장으로 못 보여 준다** — 다리는 시간 위에서만 움직인다. 그래서 GIF."""
    from PIL import Image
    frames = [f for f in sorted(outdir.glob("*.png")) if f.name != "mob_view.png"]
    if len(frames) < 4:
        return None
    step = max(1, len(frames) // max_frames)
    imgs = []
    for f in frames[::step][:max_frames]:
        try:
            im = Image.open(f).convert("RGB")
            imgs.append(im.resize((im.width // 2, im.height // 2)))
        except Exception:
            pass
    if len(imgs) < 4:
        return None
    imgs[0].save(out, save_all=True, append_images=imgs[1:],
                 duration=int(1000 / fps), loop=0, optimize=True)
    return out


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
    ap.add_argument("--force", action="store_true",
                    help="preflight 가 어긋나도 강행한다 (그 숫자는 근거로 쓰지 마라)")
    ap.add_argument("--cleanup", action="store_true",
                    help="측정이 끝나면 두 클라를 모두 거둔다 (누수 방지)")
    ap.add_argument("--preflight-only", action="store_true",
                    help="조건만 검사하고 촬영하지 않는다")
    # ── 몹 촬영 (검기가 아니라 형체·걸음을 본다) ──
    ap.add_argument("--summon", default=None, metavar="몹id",
                    help="config/mob_models.yml 의 몹을 무대에 세우고 찍는다 (예: horangi). "
                         "대행으로 등록부 정문(spawnById)을 지난다 — 그래야 형체가 붙는다")
    ap.add_argument("--target", default=None,
                    help="카메라가 조준할 대상 (기본 kigibot · --summon 이면 세운 몹)")
    ap.add_argument("--walk", action="store_true",
                    help="AI 를 켜고 표적을 줘서 **걷게** 한다 — 다리 관절 위상은 이동거리로 돈다")
    ap.add_argument("--seconds", type=float, default=12.0, help="몹 촬영 길이(초)")
    ap.add_argument("--aim-y", type=float, default=None,
                    help="조준 높이 (기본 1.1 — 호랑이는 낮아서 0.8 쯤이 좋다)")
    ap.add_argument("--dist-mul", type=float, default=1.0, help="카메라 거리 배수")
    ap.add_argument("--gif", action="store_true", help="촬영을 GIF 로 묶는다 (걸음은 시간 위에 있다)")
    # ── BetterModel 갈래 (저작 도구로 만든 형체·애니메이션을 본다) ──
    ap.add_argument("--bm-model", default=None, metavar="모델명",
                    help="BetterModel 의 모델을 세우고 찍는다 (예: demon_knight). "
                         "우리 등록부가 아니라 `/bm spawn` 을 지난다")
    ap.add_argument("--bm-anim", default=None, metavar="애니메이션",
                    help="세운 모델에 재생시킬 애니메이션 (예: hammer_attack_1). "
                         "지정하면 촬영 중 반복 재생한다 — 팔이 실제로 도는지 재기 위해서다")
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

    # ★ 재기 **직전에** 전제를 검사한다 — 어긋나면 숫자를 내지 않고 멈춘다.
    #   왜 여기(두 클라가 붙은 뒤)인가: 팩 사슬의 셋째 고리(봇이 받은 ?v=)는 **봇이 들어와야** 생긴다.
    #   결과는 base/preflight.txt 로 남는다 — "그때 조건이 뭐였나"를 나중에 답할 수 있게.
    PF.gate(keys=AT.PREFLIGHT_KEYS, work_dirs=[MAIN_DIR, CAM_WORK], players=[BOT, CAM],
            outdir=base, force=args.force)
    if args.preflight_only:
        print(f"  preflight 만 돌렸다 (촬영하지 않았다) — {base}/preflight.txt")
        return

    bot_win = window_of(BOT_DISPLAY)
    if not bot_win:
        raise SystemExit("kigibot 창(:99)을 못 찾았다")

    results = []
    rcon = Rcon()
    try:
        # ★ 검기의 흰 발판(하늘 200,100,200)은 **몹 갈래에서는 깔지 않는다.**
        #   prepare_scene 은 봇을 그 발판으로 tp 한다 — 그러면 방금 들어간 연무장에서
        #   도로 끌려 나오고, 연무장 장부는 「안에 있다」로 남아 다음 진입이 조용히 막힌다
        #   (실측으로 두 판을 날린 자리다). 몹은 제 무대(연무장)에서 선다.
        if not args.summon and not args.bm_model:
            held = AT.prepare_scene(rcon, args.item, args.night)
            print(f"[무대] 든 것: {held.strip()[:90]}")
            # ★ 카메라도 무대 위 하늘로 끌어온다 — 안 그러면 스폰 지점의 지하에서 허공을 본다
            sx, sy, sz = AT.STAGE
            rcon.cmd(f"gamemode {args.cam_mode} {CAM}")
            rcon.cmd(f"tp {CAM} {sx} {sy} {sz}")
            time.sleep(2.0)
        else:
            rcon.cmd(f"gamemode {args.cam_mode} {CAM}")
        wait_until_ingame(CAM_DISPLAY, label="카메라")

        # ★ 몹 갈래 — 검기 판정(초록)을 타지 않는다. 재는 것은 형체 파츠와 걸음이다
        if args.summon or args.bm_model:
            if args.bm_model:
                ok, sel = summon_bm(rcon, args.bm_model)
                if not ok:
                    raise SystemExit(f"BetterModel 형체를 못 세웠다: {args.bm_model}")
            else:
                ok, sel = summon_mob(rcon, args.summon, args.walk)
                if not ok:
                    raise SystemExit(f"몹을 못 세웠다: {args.summon}")
            tgt = args.target or sel
            for a in angles:
                m = shoot_mob(rcon, a, base / f"mob-{a}", args, tgt)
                print()
                print("═" * 78)
                label = (f"BM {args.bm_model}" + (f" · {args.bm_anim}" if args.bm_anim else "")
                         if args.bm_model else f"몹 {args.summon}")
                print(f"  {label} — 각도 {a}   ({m['outdir']})")
                print("═" * 78)
                # ★ BetterModel 은 우리 등록부를 지나지 않는다 — PDC·파츠 수는 여기서 뜻이 없다.
                #   없는 잣대를 들이대면 「⚠ 없다」가 고장으로 읽힌다. 그래서 갈래마다 다르게 적는다.
                if args.bm_model:
                    print(f"  경로              : BetterModel (`/bm spawn`) — 우리 MobDisplay 아님")
                else:
                    print(f"  PDC foe_id        : {m['foe'] or '⚠ 없다 (형체가 붙을 리 없다)'}")
                    print(f"  형체 파츠(item_display): {m['parts']}개")
                if m["motion"]:
                    mo = m["motion"]
                    print(f"  프레임 간 변화    : 평균 {mo['mean']}px · 최대 {mo['peak']}px "
                          f"· 정지프레임 {mo['zero']}/{mo['n']}")
                    moving = mo['mean'] > 200
                    if mo.get("frozen"):
                        print("  ★ 화면이 얼었다 — 프레임이 서로 **완전히 동일**하다.")
                        print("     이건 「안 움직인다」가 아니라 「못 봤다」다. 이 숫자를 근거로 쓰지 마라.")
                        print("     고치는 법: 카메라 클라와 Xvfb 를 함께 내리고 다시 띄운다.")
                    elif args.bm_anim:
                        print(f"  → {'팔이 돈다' if moving else '⚠ 거의 정지 — 애니메이션이 안 걸렸다'}")
                    else:
                        print(f"  → {'움직인다' if moving else '⚠ 거의 정지 — 걷지 않는다'}")
                print(f"  한 장             : {m['still']}")
                if args.gif:
                    g = make_gif(m["outdir"], base / f"mob-{a}.gif", fps=10)
                    if g:
                        print(f"  움직임(GIF)       : {g}")
                print("═" * 78)
            return

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
    print(f"  잰 조건: {base}/preflight.txt")
    if args.cleanup:
        PF.reap_clients(MAIN_DIR)
        PF.reap_clients(CAM_WORK)
    print(f"\n  재실행: scripts/kigi_cam_test.sh --angle {args.angle} --swings {args.swings}\n")


if __name__ == "__main__":
    main()
