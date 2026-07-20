#!/usr/bin/env python3
"""검기(劍氣) 시각효과 — 사람 없는 자동 검증 하네스.

★ 왜 이것이 있는가
검기는 **클라이언트에서 렌더**된다. 서버 로그에도, 조율자(AI)의 눈에도 안 보인다.
그래서 값 하나를 바꿀 때마다 사람이 인게임에서 녹화해 보내야 했다. 그 왕복을 끊는다.

  Xvfb(:99) 위에 진짜 마인크래프트 클라이언트를 띄우고 → 테스트 서버에 붙이고 →
  검을 쥐여 주고 → 3인칭으로 돌리고 → 좌클릭으로 휘두르고 → 프레임을 연속 촬영하고 →
  초록 픽셀을 세어 **검기가 몇 번째 프레임에 · 얼마나 크게 · 화면 어디에** 떴는지 표로 뱉는다.

★ 안전 (되돌릴 수 없는 것을 먼저 못 박는다)
  · 라이브 서버(25565) · 라이브 월드 · 라이브 config 를 건드리지 않는다.
  · 테스트 자원은 전부 run/mvt-test/ · scratch/ 안에만 산다.
  · RCON 은 127.0.0.1:25576 (테스트) 전용 — 25575(라이브)는 kigi_rcon.py 가 거부한다.
  · ★ 25566 / 25576 을 공유기에 포워딩하지 마라. 이 서버는 online-mode=false 다.

사용:
    scripts/kigi_autotest.sh                 # 기본 (8회 스윙)
    scripts/kigi_autotest.sh --swings 12
    scripts/kigi_autotest.sh --fps 30 --keep-client
"""

from __future__ import annotations

import argparse
import os
import shutil
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from kigi_rcon import Rcon  # noqa: E402
import vfx_detect as DET  # noqa: E402
import vfx_preflight as PF  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
TEST_DIR = ROOT / "run" / "mvt-test"
SCRATCH = ROOT / "scratch"
VENV_PMC = SCRATCH / "mcvenv" / "bin" / "portablemc"
MC_DIR = SCRATCH / "mcdata"
NATIVES = SCRATCH / "arm64natives" / "bin"
JAVA = ROOT / "run" / "jdk-21" / "bin" / "java"
CLIENT_LOG = SCRATCH / "client.log"
# ★ 시점 상태를 우리가 적어 둔다 — 마크는 시점을 물어볼 길이 없고 F5 는 순환일 뿐이다
#   (1인칭 → 3인칭 뒤 → 3인칭 앞 → 1인칭). 클라를 새로 띄우면 늘 1인칭에서 시작한다.
#   F5 를 누르는 것은 이 스크립트뿐이므로 이 숫자는 진실이다.
PERSPECTIVE_STATE = SCRATCH / ".mc_perspective"

DISPLAY = ":99"
W, H = 1280, 720
# ★ 시험대 고정 좌표 — 빈 하늘 한복판. 지형·구조물·조명이 측정에 끼어들지 못하게 못 박는다.
#   (봇이 선 자리에 무대를 깔던 옛 방식은 봇이 지하 구조물에 스폰하자 측정을 통째로 무효화했다)
STAGE = (200, 100, 200)
BOT = "kigibot"
BOT_UUID = "b0c40300922b3aa7881d178caaa13b03"
SERVER_HOST, SERVER_PORT = "127.0.0.1", 25566

# 검기 검출 문턱 — **정본은 vfx_detect 하나뿐이다** (여기서는 그것을 비춰 볼 뿐이다).
#   왜 옮겼나: 같은 문턱이 이 파일과 kigi_cam_test 에 복사돼 있었다. 한쪽만 고치면 다른 쪽은
#   옛 문턱으로 계속 재고, 그 차이는 **조용하다** — 숫자는 나오고 표는 채워진다.
#   그리고 그 로직은 한 번도 시험받은 적이 없었다 (옛 문턱 g>150 은 검기 g≈86 을 못 잡았다).
#   이제 검출은 vfx_detect 가 하고, 그 눈은 매 실행마다 합성 이미지로 자가시험을 받는다.
G_MIN, GR_MIN, GB_MIN = DET.G_MIN, DET.GR_MIN, DET.GB_MIN

# preflight 가 검사할 config 키 — 검기 측정이 실제로 읽는 값들
PREFLIGHT_KEYS = [
    "kigi_slash.enabled", "kigi_slash.scale", "kigi_slash.orbit_radius",
    "kigi_slash.sweep_deg", "kigi_slash.tilt_deg", "kigi_slash.blade_pitch_deg",
    "kigi_slash.draw_ticks", "kigi_slash.fade_ticks", "kigi_slash.frame_ticks",
]


def sh(cmd, **kw):
    return subprocess.run(cmd, shell=isinstance(cmd, str), **kw)


def xdo(*args, check=False):
    env = dict(os.environ, DISPLAY=DISPLAY)
    return subprocess.run(["xdotool", *args], env=env, check=check,
                          capture_output=True, text=True)


def port_open(host, port, timeout=1.0):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


# ══════════════════════════════════════════════════════════════════
#  ① Xvfb — 눈이 없으면 그릴 데가 없다
# ══════════════════════════════════════════════════════════════════
def ensure_xvfb():
    env = dict(os.environ, DISPLAY=DISPLAY)
    if subprocess.run(["xdpyinfo"], env=env, capture_output=True).returncode == 0:
        print(f"[Xvfb] {DISPLAY} 이미 떠 있다")
        return
    print(f"[Xvfb] {DISPLAY} 기동 ({W}x{H}x24)")
    subprocess.Popen(["Xvfb", DISPLAY, "-screen", "0", f"{W}x{H}x24"],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(30):
        time.sleep(1)
        if subprocess.run(["xdpyinfo"], env=env, capture_output=True).returncode == 0:
            return
    raise SystemExit("Xvfb 기동 실패")


# ══════════════════════════════════════════════════════════════════
#  ② 테스트 서버 (25566) — 라이브(25565)와 완전히 별개다
# ══════════════════════════════════════════════════════════════════
def ensure_server():
    if port_open(SERVER_HOST, SERVER_PORT):
        print(f"[서버] 테스트 서버 {SERVER_PORT} 이미 떠 있다")
        return
    print(f"[서버] run/mvt-test 기동 ({SERVER_PORT})")
    log = open(TEST_DIR / "console.log", "a")
    subprocess.Popen([str(JAVA), "-Xms1G", "-Xmx2G", "-jar", "paper.jar", "nogui"],
                     cwd=TEST_DIR, stdout=log, stderr=log, stdin=subprocess.DEVNULL)
    for _ in range(180):
        time.sleep(1)
        if port_open(SERVER_HOST, SERVER_PORT):
            time.sleep(3)
            print("[서버] 준비됨")
            return
    raise SystemExit("테스트 서버가 25566 을 열지 않았다 — run/mvt-test/console.log 를 보라")


# ══════════════════════════════════════════════════════════════════
#  ③ 헤드리스 클라이언트 — ARM64 네이티브를 손수 갈아 끼운다
# ══════════════════════════════════════════════════════════════════
def mc_window():
    r = xdo("search", "--name", "Minecraft 1.21.11 - Multiplayer")
    ids = [x for x in r.stdout.split() if x.strip()]
    return ids[0] if ids else None


def ensure_client(force_restart=False):
    """★ 왜 --exclude-lib / --include-bin 인가:
    Mojang 은 리눅스 LWJGL 네이티브를 **x86_64 만** 배급한다. 이 기계는 aarch64 다.
    그대로 띄우면 UnsatisfiedLinkError 로 죽는다. 그래서 Maven Central 의
    lwjgl 3.3.3 natives-linux-arm64 를 받아 .so 를 뽑아 두고(scratch/arm64natives/bin),
    Mojang 네이티브 8개를 클래스패스에서 빼고 우리 .so 를 bin 디렉터리에 심는다.
    """
    if mc_window() and not force_restart:
        print("[클라] 이미 접속되어 있다")
        return
    if not NATIVES.is_dir() or not list(NATIVES.glob("*.so")):
        raise SystemExit(f"ARM64 네이티브가 없다: {NATIVES} (README 참조 — setup 을 먼저 돌려라)")

    print("[클라] 마인크래프트 1.21.11 기동 (Xvfb :99 · llvmpipe)")
    excl = []
    for art in ("lwjgl", "lwjgl-glfw", "lwjgl-opengl", "lwjgl-openal",
                "lwjgl-stb", "lwjgl-tinyfd", "lwjgl-jemalloc", "lwjgl-freetype"):
        excl += ["--exclude-lib", f"{art}::natives"]
    binz = []
    for so in sorted(NATIVES.glob("*.so")):
        binz += ["--include-bin", str(so)]

    cmd = [str(VENV_PMC), "--main-dir", str(MC_DIR), "start",
           "--jvm", str(JAVA), "--resolution", f"{W}x{H}",
           "-u", BOT, "-i", BOT_UUID,
           *excl, *binz,
           "-s", SERVER_HOST, "-p", str(SERVER_PORT), "1.21.11"]
    log = open(CLIENT_LOG, "w")
    subprocess.Popen(cmd, cwd=ROOT, env=dict(os.environ, DISPLAY=DISPLAY),
                     stdout=log, stderr=log, stdin=subprocess.DEVNULL)
    PERSPECTIVE_STATE.write_text("0")   # 새 클라 = 1인칭 · HUD 켜짐

    for _ in range(180):
        time.sleep(2)
        if mc_window():
            break
    else:
        raise SystemExit(f"클라이언트 창이 안 떴다 — {CLIENT_LOG} 를 보라")
    print("[클라] 창 확인 — 접속 대기")
    time.sleep(8)
    accept_resource_pack()
    wait_until_ingame()


def grab_one(path: Path):
    """지금 화면 한 장 — 준비 판정용."""
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "x11grab",
                    "-video_size", f"{W}x{H}", "-i", f"{DISPLAY}.0",
                    "-frames:v", "1", str(path)],
                   check=False, capture_output=True)


def wait_until_ingame(timeout=240):
    """★ 실제로 **게임 안**에 들어갔는지 화면으로 확인한다.

    왜 이것이 있어야 하나 (2026-07-20 · 측정 전체가 무효가 된 사건):
      창이 떴다고 게임에 들어간 것이 아니다. 소프트 렌더(llvmpipe)라
      Mojang 스플래시 → 메뉴 → 접속 → 「Loading terrain…」 까지 수십 초가 걸린다.
      그 사이를 찍고서 「초록 0px」 를 얻었고, 그것을 근거로 빌보드·forward 결론을
      낼 뻔했다. **세계가 아직 없었을 뿐인데 검기가 없다고 읽은 것이다.**
    판정: Mojang 빨강(스플래시)도 아니고, 거의 단색(로딩 회색)도 아니어야 한다.
    """
    from PIL import Image
    import numpy as np
    probe = SCRATCH / ".ingame_probe.png"
    print("[클라] 게임 화면 진입 대기", end="", flush=True)
    t0 = time.time()
    while time.time() - t0 < timeout:
        grab_one(probe)
        if probe.exists() and probe.stat().st_size > 0:
            a = np.asarray(Image.open(probe).convert("RGB")).astype(int)
            r, g, b = a[:, :, 0].mean(), a[:, :, 1].mean(), a[:, :, 2].mean()
            mojang_red = r > 150 and g < 90 and b < 90        # 스플래시
            flat = a.reshape(-1, 3).std(axis=0).mean() < 12   # 로딩 단색
            if not mojang_red and not flat:
                print(f" — 들어갔다 ({int(time.time() - t0)}초)")
                time.sleep(2)
                return True
        print(".", end="", flush=True)
        time.sleep(3)
    print(" — 시간초과(그대로 진행하되 결과를 의심하라)")
    return False


def accept_resource_pack():
    """리소스팩 「Yes」를 누른다 — 팩이 없으면 검기 모델이 안 보인다.

    ★ 창 관리자가 없어서 _NET_ACTIVE_WINDOW 가 안 먹는다. 대신 포인터를 창 안에 두고
    XTEST 로 보낸다 (포커스가 포인터를 따른다). 클릭 좌표는 1280x720 · guiScale 2 기준.
    """
    win = mc_window()
    if not win:
        return
    xdo("windowfocus", win)
    time.sleep(0.5)
    xdo("mousemove", str(W // 2 - 156), "435")
    time.sleep(0.3)
    xdo("click", "1")
    print("[클라] 리소스팩 「Yes」 클릭 — 적용 대기 (최대 60초)")
    for _ in range(60):
        time.sleep(1)
        if "server/" in CLIENT_LOG.read_text(errors="replace")[-8000:]:
            print("[클라] 리소스팩 적용됨")
            return
    print("[클라] ⚠ 리소스팩 적용 확인 못 함 — 검기 모델이 안 보일 수 있다")


# ══════════════════════════════════════════════════════════════════
#  ④ 인게임 준비 — 검을 쥐여 주고 하늘을 맑게
# ══════════════════════════════════════════════════════════════════
def prepare_scene(rcon: Rcon, item: str, night: bool):
    """★ 왜 이 자세인가: 초록 검기를 **배경과 떼어** 세어야 한다.
    평평한 지면 위, 하늘을 등지고, 수평보다 살짝 위를 본다 — 초록 풀·나뭇잎이 화면에서 빠진다.
    """
    print("[무대] 검 지급 · 시야 정리")
    rcon.cmd(f"op {BOT}")
    rcon.cmd(f"gamemode creative {BOT}")
    rcon.cmd("gamerule doDaylightCycle false")
    rcon.cmd("gamerule doWeatherCycle false")
    rcon.cmd("weather clear")
    rcon.cmd(f"time set {'midnight' if night else 'noon'}")
    # ★★ 먼저 **빈 하늘 한복판**으로 옮긴다 (2026-07-20 — 측정이 통째로 무효가 된 사건).
    #   왜: 무대는 「봇이 선 자리」에 깔린다. 그런데 재기동을 거듭하자 봇이 나루(입도진) 구조물
    #   **지하 Y=-59** 에 스폰했고, 거기 흰 바닥을 깔아 봐야 사방이 막힌 캄캄한 방이었다.
    #   그 상태로 8조합을 돌려 **전부 0px** 를 얻었다 — 검기가 안 뜬 것이 아니라 아무것도 못 잰 것이다.
    #   (프레임을 눈으로 보고서야 알았다. 숫자만 봤으면 거짓 결론을 낼 뻔했다.)
    #   ⇒ 고정 좌표의 **높은 빈 하늘**로 못 박는다. 구조물·지형·조명이 측정에 끼어들 수 없다.
    #   ★ 청크를 먼저 연다 — 안 열린 청크에는 fill 이 안 먹고 봇은 그대로 추락한다
    #     (좌표를 3000 으로 뒀다가 정확히 그 일이 났다: 발판 없이 빈 하늘만 찍혔다)
    sx, sy, sz = STAGE
    rcon.cmd(f"forceload add {sx - 32} {sz - 32} {sx + 32} {sz + 32}")
    rcon.cmd(f"tp {BOT} {sx} {sy} {sz} 90 -8")
    time.sleep(1.5)
    # 발판을 먼저 깔고 그 위에 세운다 (추락 방지)
    rcon.cmd(f"fill {sx - 12} {sy - 1} {sz - 12} {sx + 12} {sy - 1} {sz + 12} minecraft:white_concrete")
    rcon.cmd(f"fill {sx - 12} {sy} {sz - 12} {sx + 12} {sy + 4} {sz + 12} minecraft:air")
    rcon.cmd(f"tp {BOT} {sx} {sy} {sz} 90 -8")
    time.sleep(0.6)
    # ★ 흰 시험대 — 봇이 선 자리 둘레를 흰 콘크리트로 깐다.
    #   왜: 검기는 **초록**이다. 풀밭 위에서 세면 배경이 같이 세어진다.
    #   월드 이름을 박지 않는다 (봇이 어느 월드에 있든 제 발밑에 깐다).
    rcon.cmd(f"execute as {BOT} at {BOT} run fill ~-12 ~-1 ~-12 ~12 ~-1 ~12 minecraft:white_concrete")
    rcon.cmd(f"execute as {BOT} at {BOT} run fill ~-12 ~ ~-12 ~12 ~3 ~12 minecraft:air")
    # 수평보다 살짝 위를 본다 — 지평선 아래(땅)를 화면에서 줄인다
    rcon.cmd(f"execute as {BOT} at {BOT} run tp {BOT} ~ ~ ~ 90 -8")
    rcon.cmd(f"clear {BOT}")
    rcon.cmd(f"give {BOT} {item}")
    # ★ 지형 로딩을 기다린다 — 텔레포트 직후 클라는 「Loading terrain…」 을 띄운 채 회색 화면을 찍는다.
    #   (그 상태로 찍어 「검출 0」 을 얻은 적이 있다 — 검기가 없는 게 아니라 세계가 아직 없었다)
    time.sleep(6.0)
    rcon.cmd(f"item replace entity {BOT} hotbar.0 with {item}")
    return rcon.cmd(f"data get entity {BOT} SelectedItem")


VIEWS = {"first": 0, "back": 1, "front": 2}


def set_view(win, view="back", hide_hud=True):
    """시점 고정 + HUD 끄기.

    ★ 왜 상태 파일인가: F5 는 **순환**이다(1인칭→3인칭뒤→3인칭앞). 마크에 "지금 몇 인칭이냐"를
    물을 길이 없다. 그래서 우리가 누른 횟수를 적어 둔다 — F5 를 누르는 것은 이 스크립트뿐이다.
    ★ 왜 HUD 를 끄나: HUD 의 초록 글자(「막기」)가 **매 프레임** 초록 마스크에 걸린다.
    그것을 검기로 세면 "검기가 389프레임 내내 떠 있다"는 거짓말이 나온다 (실제로 그렇게 나왔다).
    """
    xdo("windowfocus", win)
    xdo("mousemove", str(W // 2), str(H // 2))
    time.sleep(0.4)
    try:
        cur = int(PERSPECTIVE_STATE.read_text().strip())
    except Exception:
        cur = 0
    want = VIEWS[view]
    for _ in range((want - cur) % 3):
        xdo("key", "F5")
        time.sleep(0.4)
    PERSPECTIVE_STATE.write_text(str(want))
    # ★ 세었으면 **눈으로 확인한다** (2026-07-20).
    #   왜: 상태 파일은 어긋난다 — 클라가 팩 리로드로 되살아나거나 사람이 F5 를 누르면 셈이 틀어진다.
    #   실제로 「3인칭 뒤」를 청구하고 1인칭을 찍어, 뒤 시점 결론을 두 번 거짓으로 낼 뻔했다.
    #   판정: 3인칭이면 화면 한복판에 **플레이어 몸**이 있다 (흰 무대 위 어두운 덩어리).
    #   최대 3번까지 F5 를 더 눌러 맞춘다 — 못 맞추면 소리내어 알린다 (조용히 틀리지 않는다).
    if view in ("back", "front"):
        for attempt in range(3):
            if _player_visible():
                break
            xdo("key", "F5")
            time.sleep(0.6)
            PERSPECTIVE_STATE.write_text(str((VIEWS[view] + attempt + 1) % 3))
        else:
            print("  ⚠ 3인칭 확인 실패 — 결과를 의심하라 (화면에 플레이어가 안 보인다)")
    if hide_hud:
        xdo("key", "F1")          # HUD 토글 — 초록 글자·핫바·조준선을 화면에서 뺀다
        time.sleep(0.6)
    time.sleep(0.8)


def _player_visible():
    """화면 한복판에 플레이어 몸이 있는가 — 3인칭인지 눈으로 판정한다.

    흰 무대(밝은 회색) 위에 선 어두운 스킨을 센다. 1인칭이면 몸이 없어 0 에 가깝다.
    """
    from PIL import Image
    import numpy as np
    probe = SCRATCH / ".view_probe.png"
    grab_one(probe)
    if not probe.exists() or probe.stat().st_size == 0:
        return False
    a = np.asarray(Image.open(probe).convert("RGB")).astype(int)
    # 화면 중앙 세로 띠 — 3인칭에서 플레이어가 서는 자리
    band = a[int(H * 0.35):int(H * 0.85), int(W * 0.44):int(W * 0.56)]
    r, g, b = band[..., 0], band[..., 1], band[..., 2]
    # 어둡되 **초록이 아닌** 픽셀만 — 검기(초록)를 몸으로 오인하면 1인칭을 3인칭이라 우긴다
    # (실제로 그렇게 통과시켜 1인칭 화면을 「뒤 시점」이라 보고할 뻔했다)
    dark = ((band.sum(axis=2) < 330) & ~((g - r > 15) & (g - b > 10))).sum()
    return dark > 1200                             # 스킨 실루엣이면 수천 px 나온다


def restore_hud(win):
    xdo("windowfocus", win)
    xdo("key", "F1")
    time.sleep(0.3)


# ══════════════════════════════════════════════════════════════════
#  ⑤ 촬영 — ffmpeg x11grab 연속 프레임
# ══════════════════════════════════════════════════════════════════
def start_capture(outdir: Path, fps: int):
    outdir.mkdir(parents=True, exist_ok=True)
    p = subprocess.Popen(
        ["ffmpeg", "-loglevel", "error", "-f", "x11grab", "-framerate", str(fps),
         "-video_size", f"{W}x{H}", "-i", DISPLAY,
         "-vsync", "0", str(outdir / "frame_%04d.png")],
        env=dict(os.environ, DISPLAY=DISPLAY),
        stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    time.sleep(1.5)
    return p


def stop_capture(p):
    p.send_signal(signal.SIGINT)
    try:
        p.wait(timeout=15)
    except subprocess.TimeoutExpired:
        p.kill()


def swing(win, n, gap):
    """좌클릭 = 평타 = 검기. 스윙 간격은 draw_ticks(12)+fade(5) 보다 넉넉해야 겹치지 않는다."""
    xdo("windowfocus", win)
    xdo("mousemove", str(W // 2), str(H // 2))
    time.sleep(0.3)
    for i in range(n):
        xdo("click", "1")
        time.sleep(gap)


# ══════════════════════════════════════════════════════════════════
#  ⑥ 판독 — 초록 픽셀을 센다 (조율자가 좌표로 판단할 수 있게)
# ══════════════════════════════════════════════════════════════════
def analyze(outdir: Path, min_area: int, exclude=None):
    """프레임을 읽어 검기를 센다. **검출 자체는 vfx_detect 가 한다** (자가시험을 받는 눈).

    exclude: 붙박이 초록(HUD) 마스크. 주면 빼고 센다.
    """
    frames = sorted(outdir.glob("frame_*.png"))
    rows = []
    for f in frames:
        m = DET.measure_frame(f, exclude=exclude, min_area=min_area)
        if m:
            rows.append({"frame": f.name, **m})
    return len(frames), rows


def group_bursts(rows, gap=3):
    """연속 프레임 뭉치 = 스윙 1회. 검기가 몇 번 떴는지 세려면 뭉쳐야 한다."""
    bursts, cur = [], []
    prev = None
    for r in rows:
        idx = int(r["frame"][6:10])
        if prev is not None and idx - prev > gap:
            bursts.append(cur)
            cur = []
        cur.append(r)
        prev = idx
    if cur:
        bursts.append(cur)
    return bursts


def contact_sheet(outdir: Path, bursts, cols=6):
    """검기가 뜬 프레임만 모아 한 장으로 — 조율자가 3단계·공전을 **한 눈에** 본다.

    ★ 왜 필요한가: 프레임이 400장이면 사람도 AI 도 안 본다. 검기가 뜬 것만 시간순으로 붙이면
    「1단계→2단계→3단계」와 초승달이 도는 궤적이 한 장에 남는다.
    """
    from PIL import Image
    if not bursts:
        return None
    picks = []
    for b in bursts:
        seen = set()
        for r in b:                      # 소프트 렌더라 같은 그림이 여러 번 잡힌다 — 면적이 바뀔 때만
            if r["area"] not in seen:
                seen.add(r["area"])
                picks.append(r["frame"])
    if not picks:
        return None
    tw, th = 320, 180
    rows_n = (len(picks) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * tw, rows_n * th), (20, 20, 20))
    for i, name in enumerate(picks):
        im = Image.open(outdir / name).convert("RGB").resize((tw, th))
        sheet.paste(im, ((i % cols) * tw, (i // cols) * th))
    out = outdir / "contact_sheet.png"
    sheet.save(out)
    return out


def report(outdir, total, rows, bursts):
    print()
    print("═" * 78)
    print(f"  검기 자동 검증 결과 — {outdir}")
    print("═" * 78)
    print(f"  촬영 프레임      : {total} 장")
    print(f"  검기 검출 프레임 : {len(rows)} 장")
    print(f"  검기 발생 횟수   : {len(bursts)} 회 (연속 프레임 뭉치)")
    print(f"  검출 문턱        : g>{G_MIN} & g-r>{GR_MIN} & g-b>{GB_MIN}")
    print(f"  화면             : {W}x{H} (중심 {W//2},{H//2})")
    print("-" * 78)
    if not rows:
        print("  ⚠ 초록 픽셀이 한 프레임도 없다 — 검기가 안 떴거나, 팩이 없거나, 카메라가 딴 데를 본다.")
        print(f"    프레임을 직접 보라: {outdir}/frame_0001.png")
        print("═" * 78)
        return
    print(f"  {'프레임':<16}{'면적(px)':>10}{'cx':>7}{'cy':>7}   {'bbox(x0,y0)-(x1,y1)':<26}위치")
    for r in rows:
        dx, dy = r["cx"] - W // 2, r["cy"] - H // 2
        where = ("왼쪽" if dx < -80 else "오른쪽" if dx > 80 else "정면") + \
                ("·위" if dy < -80 else "·아래" if dy > 80 else "·중앙")
        print(f"  {r['frame']:<16}{r['area']:>10}{r['cx']:>7}{r['cy']:>7}   "
              f"({r['x0']},{r['y0']})-({r['x1']},{r['y1']})".ljust(26 + 44) [:70]
              + f"  {where}")
    print("-" * 78)
    for i, b in enumerate(bursts, 1):
        peak = max(b, key=lambda r: r["area"])
        print(f"  {i}회차: {len(b)}프레임 · 최대면적 {peak['area']}px @ ({peak['cx']},{peak['cy']}) "
              f"[{peak['frame']}]")
    print("═" * 78)


def main():
    ap = argparse.ArgumentParser(description="검기 시각효과 자동 검증 하네스")
    ap.add_argument("--swings", type=int, default=8, help="좌클릭 공격 횟수")
    ap.add_argument("--gap", type=float, default=1.4, help="스윙 간격(초)")
    ap.add_argument("--fps", type=int, default=30, help="연속 촬영 프레임레이트")
    ap.add_argument("--item", default="minecraft:iron_sword", help="봇이 들 무기")
    ap.add_argument("--min-area", type=int, default=40, help="검기로 칠 최소 초록 픽셀 수")
    ap.add_argument("--view", choices=list(VIEWS), default="back",
                    help="시점: back=3인칭 뒤(기본) · front=3인칭 앞 · first=1인칭")
    ap.add_argument("--night", action="store_true", help="밤으로 (초록이 더 잘 뜬다)")
    ap.add_argument("--keep-hud", action="store_true",
                    help="HUD 를 끄지 않는다 (초록 글자가 검출을 오염시킨다 — 눈으로 볼 때만)")
    ap.add_argument("--restart-client", action="store_true", help="클라이언트를 새로 띄운다")
    ap.add_argument("--outdir", default=None)
    ap.add_argument("--force", action="store_true",
                    help="preflight 가 어긋나도 강행한다 (그 숫자는 근거로 쓰지 마라)")
    ap.add_argument("--cleanup", action="store_true",
                    help="측정이 끝나면 띄운 클라를 거둔다 (누수 방지)")
    args = ap.parse_args()

    stamp = time.strftime("%Y%m%d-%H%M%S")
    outdir = Path(args.outdir) if args.outdir else SCRATCH / "autotest" / stamp

    ensure_xvfb()
    ensure_server()
    ensure_client(force_restart=args.restart_client)

    # ★ 재기 **직전에** 전제를 검사한다 — 어긋나면 숫자를 내지 않고 멈춘다.
    #   왜 여기(클라 접속 뒤)인가: 팩 사슬의 셋째 고리(봇이 받은 ?v=)는 **봇이 들어와야** 로그에 생긴다.
    #   결과 폴더에 preflight.txt 로 남긴다 — 나중에 "그때 조건이 뭐였나"를 답할 수 있게.
    PF.gate(keys=PREFLIGHT_KEYS, work_dirs=[MC_DIR], players=[BOT],
            outdir=outdir, force=args.force)

    win = mc_window()
    if not win:
        raise SystemExit("마인크래프트 창을 못 찾았다")

    rcon = Rcon()
    try:
        held = prepare_scene(rcon, args.item, args.night)
        print(f"[무대] 든 것: {held.strip()[:90]}")
        # ★ 촬영 직전에 **한 번 더** 게임 화면인지 본다 (2026-07-20).
        #   왜: 접속 때만 확인하면 부족하다. 팩이 바뀌면 클라가 텍스처 아틀라스를 다시 구우며
        #   **스플래시 화면으로 되돌아간다**. 그 사이를 찍고 「검출 0」 을 얻어, 뒤 시점 결론을
        #   또 한 번 거짓으로 낼 뻔했다. 무대를 차린 뒤가 그 위험이 가장 큰 자리다.
        wait_until_ingame()
        set_view(win, view=args.view, hide_hud=not args.keep_hud)
        print(f"[촬영] {args.fps}fps 연속 촬영 시작 → {outdir}")
        cap = start_capture(outdir, args.fps)
        try:
            swing(win, args.swings, args.gap)
            time.sleep(1.5)
        finally:
            stop_capture(cap)
            if not args.keep_hud:
                restore_hud(win)
    finally:
        rcon.close()

    total, rows = analyze(outdir, args.min_area)
    bursts = group_bursts(rows)
    report(outdir, total, rows, bursts)
    sheet = contact_sheet(outdir, bursts)
    if sheet:
        print(f"  한 장 요약(검기가 뜬 프레임만): {sheet}")
    print(f"  잰 조건: {outdir}/preflight.txt")
    if args.cleanup:
        PF.reap_clients(MC_DIR)
    print(f"\n  재실행: scripts/kigi_autotest.sh --swings {args.swings} --view {args.view}\n")


if __name__ == "__main__":
    main()
