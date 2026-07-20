#!/usr/bin/env python3
"""시각효과 하네스 — **측정 직전 검사(preflight)**.

★ 왜 이것이 있는가 (2026-07-20 · 하루에 일곱 번 조용히 거짓말한 뒤에 태어났다)

하네스는 잘 돌았다. 사람 없이 네 각도를 촬영하고 면적·좌표를 잰다. 그런데 그날 하루에
**일곱 번** 측정이 거짓을 말했다. 그리고 일곱 번 전부 측정 *결과*가 아니라 측정 *조건*이
틀린 경우였다 — 팩이 안 켜졌거나, 옛 팩을 재고 있거나, 죽은 서버의 로그를 읽거나,
클라가 52개 새어 메모리가 말랐거나.

  **0px 는 「효과가 없다」가 아니라 「내 눈이 멀었다」일 수 있다.**

그 둘을 구별할 방법이 하네스 안에 없었다. 그래서 숫자는 늘 나왔고, 늘 그럴듯했다.
⇒ 재기 **전에** 전제를 검사한다. 하나라도 어긋나면 **숫자를 내지 않고 중단한다.**
   0 을 돌려주지 않는다 — 0 은 「효과가 없다」로 읽히기 때문이다.

검사 (계획서 docs/design/vfx_harness.md · A 표):
  ① 팩 사슬   : 배포 zip sha1 = 서버가 로그에 적은 sha1 = 봇에게 보낸 ?v=  (셋이 같은가)
  ② jar       : plugins/*.jar mtime ≥ build/libs/*.jar mtime (+ 서버가 그 jar 로 떴는가)
  ③ config    : 시험할 키가 저장소 config 와 플러그인 config 에서 같은가
  ④ 로그 신선도: 읽는 로그가 **지금 도는 프로세스**의 것인가
  ⑤ 봇 접속   : 찍을 봇이 정말 서버에 붙어 있는가 (창이 있는 것과 다르다 — RCON `list` 로 묻는다)
  ⑥ 자원      : 가용 메모리 ≥ 8GB · 누수 클라 0
  ⑦ 검출기    : 자가시험 통과 (vfx_detect.selftest — 합성 이미지로 눈을 시험한다)
  ⑧ 메모리 신선도: 플러그인 config 파일 ↔ **실제로 메모리에 실린 값** (핫 리로드가 생긴 뒤의 새 거짓말)

`--force` 로만 무시할 수 있다. 결과는 결과 폴더에 `preflight.txt` 로 남는다 —
나중에 "그때 조건이 뭐였나"를 답할 수 있게.

단독 실행 (아무것도 측정하지 않고 조건만 본다):
    python3 scripts/vfx_preflight.py
    python3 scripts/vfx_preflight.py --keys kigi_slash.scale kigi_slash.sweep_deg
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import vfx_detect  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
TEST_DIR = ROOT / "run" / "mvt-test"
# ★ 배급 zip — 서버가 sha1 을 재는 그 실물이다 (resource_pack.yml 의 local_path).
#   VFX_PACK_ZIP 은 **이 검사기 자신을 시험할 때만** 쓴다: 라이브와 공유하는 실물 zip 을
#   건드리지 않고 "팩이 바뀌었는데 서버는 모른다" 를 재현하기 위한 문이다.
#   (실물을 바꿔치면 그 순간 라이브에 들어온 사람이 못 받는 팩을 받게 된다 — 그럴 수 없다.)
#
#   ★ 경로를 **못 박지 않는다** — 테스트 서버가 제 팩(`run/pack-http-test/`)을 쓰게 된 뒤로
#     못 박힌 라이브 경로는 **엉뚱한 파일과 견주는 눈**이 됐다: 서버가 잰 값과 봇에게 보낸 값이
#     서로 맞는데도 "서버가 옛 팩을 잰다"고 외쳤다. 검사기가 틀리면 측정을 막는 게 아니라
#     **막을 것을 못 막는다** — 그러니 서버가 실제로 재는 그 파일을 설정에서 읽는다.
def _configured_pack_zip() -> Path:
    """테스트 서버가 **실제로 sha1 을 재는** zip 을 resource_pack.yml 에서 읽는다."""
    cfg = TEST_DIR / "plugins" / "HoncheonMVT" / "config" / "resource_pack.yml"
    try:
        for line in cfg.read_text(encoding="utf-8").splitlines():
            m = re.match(r"\s*local_path:\s*(\S+)", line)
            if m:
                # local_path 는 **서버 폴더 기준**의 상대경로다 (run/mvt-test/)
                return (TEST_DIR / m.group(1)).resolve()
    except OSError:
        pass
    return ROOT / "run" / "pack-http" / "honcheon_pack.zip"   # 못 읽으면 옛 자리로


PACK_ZIP = Path(os.environ["VFX_PACK_ZIP"]) if os.environ.get("VFX_PACK_ZIP") else _configured_pack_zip()
BUILD_LIBS = ROOT / "server-mvt" / "build" / "libs"
REPO_CONFIG = ROOT / "config"
TEST_CONFIG = TEST_DIR / "plugins" / "HoncheonMVT" / "config"

# ★ 촬영 편의로 **일부러** 다르게 두는 키 — 어긋나도 막지 않되 「의도적 차이」라고 말한다.
#   왜: 검기의 진짜 값은 12틱 만에 사라진다. 15fps 촬영으로는 한두 프레임밖에 안 걸려
#   형태를 볼 수 없다. 그래서 테스트 서버에서만 길게 늘여 둔다 — 그것은 버그가 아니다.
#   다만 **면적·지속시간 수치를 라이브의 값으로 읽으면 안 된다.** 그래서 소리내어 적는다.
INTENTIONAL_DIFF = {
    "draw_ticks", "fade_ticks", "frame_ticks",
}

MIN_FREE_GB = 8.0

# 서버가 한 판(기동)을 시작할 때 반드시 찍는 줄 — 로그에서 **이번 판**만 도려내는 표식
SERVER_BOOT_MARK = re.compile(r"Starting minecraft server version")


class Check:
    def __init__(self, name, ok, detail, fatal=True):
        self.name = name
        self.ok = ok
        self.detail = detail if isinstance(detail, list) else [detail]
        self.fatal = fatal

    def __str__(self):
        head = "  통과" if self.ok else ("★ 중단" if self.fatal else "  주의")
        out = [f"{head}  {self.name}"]
        out += [f"          {d}" for d in self.detail]
        return "\n".join(out)


# ══════════════════════════════════════════════════════════════════
#  도구 — 지금 도는 프로세스를 찾는다 (D. 위생)
# ══════════════════════════════════════════════════════════════════
def _boot_time():
    """기계가 켜진 시각(epoch). /proc 의 starttime 은 이 값에 더해야 사람 시각이 된다."""
    for line in Path("/proc/stat").read_text().splitlines():
        if line.startswith("btime "):
            return float(line.split()[1])
    return 0.0


def proc_start_time(pid: int):
    """PID 가 **언제 떴는지**(epoch). 죽었으면 None."""
    try:
        raw = Path(f"/proc/{pid}/stat").read_text()
    except OSError:
        return None
    # comm 에 공백·괄호가 들어갈 수 있어 마지막 ')' 뒤부터 센다
    fields = raw[raw.rindex(")") + 2:].split()
    ticks = float(fields[19])                      # 22번째 필드 = starttime
    return _boot_time() + ticks / os.sysconf("SC_CLK_TCK")


def find_server_pid(server_dir: Path):
    """`server_dir` 에서 도는 paper 서버의 PID. 없으면 None.

    ★ cwd 로 찾는다 — 명령줄이 아니라. 라이브(run/mvt)와 테스트(run/mvt-test)는
      명령줄이 거의 같고 cwd 만 다르다. 이름으로 찾으면 **라이브를 테스트로 착각**한다.
    """
    want = server_dir.resolve()
    for p in Path("/proc").iterdir():
        if not p.name.isdigit():
            continue
        try:
            if Path(os.readlink(p / "cwd")).resolve() != want:
                continue
            cmd = (p / "cmdline").read_bytes().decode("utf-8", "replace")
        except OSError:
            continue
        if "paper.jar" in cmd:
            return int(p.name)
    return None


def find_live_log(server_dir: Path, pid=None):
    """**지금 도는 프로세스가 쓰고 있는** 로그를 고른다 (파일명을 박지 않는다).

    ★ 왜 (2026-07-20 · 일곱 번째 거짓말): 죽은 서버의 옛 `console.log` 를 읽고
      거기 적힌 옛 sha1 을 「최신」이라 읽었다. 새 서버는 다른 파일에 쓰고 있었다.
    ⇒ 후보(logs/latest.log · console.log) 중 **가장 신선한 것**을 고르고,
      그 mtime 이 프로세스 기동 시각보다 오래됐으면 **그것은 이번 판의 로그가 아니다.**
    """
    cands = [server_dir / "logs" / "latest.log", server_dir / "console.log"]
    cands = [c for c in cands if c.exists()]
    if not cands:
        return None, "로그 파일이 하나도 없다"
    best = max(cands, key=lambda c: c.stat().st_mtime)
    if pid is not None:
        started = proc_start_time(pid)
        if started and best.stat().st_mtime < started:
            age = int(started - best.stat().st_mtime)
            return None, (f"{best.name} 은 서버(PID {pid}) 기동보다 {age}초 낡았다 "
                          f"— 죽은 서버의 옛 로그다")
    return best, f"{best.relative_to(ROOT)} (mtime {time.strftime('%H:%M:%S', time.localtime(best.stat().st_mtime))})"


def current_run_text(log: Path):
    """로그에서 **이번 기동분만** 잘라낸다.

    ★ 왜: console.log 는 재기동을 넘어 계속 이어 붙는다. 통째로 읽으면 세 판 전의
      sha1 이 「최신」 행세를 한다 (실제로 그렇게 오판했다). 마지막 기동 표식 뒤만 읽는다.
    """
    txt = log.read_text(errors="replace")
    marks = list(SERVER_BOOT_MARK.finditer(txt))
    return txt[marks[-1].start():] if marks else txt


def sha1_of(path: Path):
    if not path.exists():
        return None
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _same_prefix(a, b):
    """서버 로그는 sha1 을 12자리로 줄여 적는다 — 짧은 쪽 길이만큼만 견준다."""
    if not a or not b:
        return False
    n = min(len(a), len(b))
    return a[:n] == b[:n]


# ══════════════════════════════════════════════════════════════════
#  ① 팩 사슬 — 배포 zip · 서버가 잰 sha1 · 봇에게 보낸 ?v= 가 셋 다 같은가
# ══════════════════════════════════════════════════════════════════
RE_PREPARED = re.compile(r"\[팩\] 배급 준비 —.*?sha1 ([0-9a-f]{6,})")
RE_CHANGED = re.compile(r"\[팩\] 팩이 바뀌었다 — sha1 [0-9a-f]+…? → ([0-9a-f]{6,})")
RE_SENT = re.compile(r"\[팩\] (\S+) 에게 보냈다 —.*?[?&]v=([0-9a-f]{8,})")


def check_pack_chain(log: Path):
    zip_sha = sha1_of(PACK_ZIP)
    if zip_sha is None:
        return Check("팩 사슬", False, f"배포 zip 이 없다: {PACK_ZIP}")
    txt = current_run_text(log)

    prepared = RE_PREPARED.findall(txt)
    changed = RE_CHANGED.findall(txt)
    sent = RE_SENT.findall(txt)

    try:
        where = PACK_ZIP.relative_to(ROOT)
    except ValueError:
        where = PACK_ZIP
    detail = [f"① 배포 zip        : {zip_sha[:12]}… ({where})"
              + ("  ※ VFX_PACK_ZIP 로 바꿔 끼운 zip (검사기 시험용)"
                 if os.environ.get("VFX_PACK_ZIP") else "")]

    if not prepared:
        detail.append("② 서버가 잰 sha1  : **로그에 없다** — 이 판의 서버가 팩을 안 읽었다")
        return Check("팩 사슬", False, detail)
    boot_sha = prepared[-1]
    # 서버는 접속마다 다시 재기도 한다 — 그러면 그 값이 지금의 진실이다
    server_sha = changed[-1] if changed else boot_sha
    detail.append(f"② 서버가 잰 sha1  : {boot_sha[:12]}… (기동 때)"
                  + (f" → {server_sha[:12]}… (재측정)" if changed else ""))

    if sent:
        who, v = sent[-1]
        detail.append(f"③ 봇에게 보낸 ?v= : {v[:12]}… ({who})")
    else:
        v = None
        detail.append("③ 봇에게 보낸 ?v= : 아직 아무도 안 받았다 (첫 접속 전 — 이 항목은 건너뛴다)")

    bad = []
    if not _same_prefix(zip_sha, server_sha):
        bad.append("서버가 잰 sha1 ≠ 배포 zip")
    if v is not None and not _same_prefix(zip_sha, v):
        bad.append("봇에게 보낸 ?v= ≠ 배포 zip")

    if bad:
        detail.append("")
        detail.append("★ " + " · ".join(bad))
        detail.append("★ **서버가 옛 팩을 잰다 — 테스트 서버를 재기동하라.**")
        detail.append("   (서버는 팩 sha1 을 기동 때 잰다. 팩을 새로 구워 배포해도 재기동 전엔 모른다.")
        detail.append("    이 상태로 재면 옛 텍스처를 보며 「새 형태가 반영 안 됐다」고 오판한다.)")
        return Check("팩 사슬", False, detail)

    detail.append("→ 셋이 일치한다")
    return Check("팩 사슬", True, detail)


# ══════════════════════════════════════════════════════════════════
#  ② jar — 배포한 것이 빌드한 것보다 새것인가 · 서버가 그것으로 떴는가
# ══════════════════════════════════════════════════════════════════
def check_jar(server_pid=None):
    deployed = sorted((TEST_DIR / "plugins").glob("server-mvt-*.jar"))
    built = sorted(BUILD_LIBS.glob("server-mvt-*.jar"))
    if not deployed:
        return Check("jar", False, f"플러그인 jar 이 없다: {TEST_DIR / 'plugins'}")
    if not built:
        return Check("jar", True, "빌드 산출물이 없다 — 견줄 대상이 없어 건너뛴다", fatal=False)
    d, b = deployed[-1], built[-1]
    dm, bm = d.stat().st_mtime, b.stat().st_mtime
    fmt = lambda t: time.strftime("%m-%d %H:%M:%S", time.localtime(t))  # noqa: E731
    detail = [f"배포 {d.name} {fmt(dm)}", f"빌드 {b.name} {fmt(bm)}"]
    if dm < bm:
        detail.append(f"★ 배포본이 {int(bm - dm)}초 낡았다 — **옛 jar 로 돈다. 재배포하라** "
                      f"(scripts/redeploy_mvt.sh)")
        return Check("jar", False, detail)
    if server_pid:
        started = proc_start_time(server_pid)
        if started and dm > started:
            detail.append(f"★ jar 이 서버 기동({fmt(started)}) **뒤에** 배포됐다 — "
                          f"돌고 있는 것은 옛 코드다. 테스트 서버를 재기동하라")
            return Check("jar", False, detail)
        detail.append(f"서버 기동 {fmt(started)} — 배포보다 나중 (이 jar 으로 돈다)")
    return Check("jar", True, detail)


# ══════════════════════════════════════════════════════════════════
#  ③ config — 시험할 키가 저장소와 플러그인에서 같은가
# ══════════════════════════════════════════════════════════════════
def _dig(root, dotted):
    cur = root
    for part in dotted.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return KeyError
        cur = cur[part]
    return cur


def check_config(keys):
    """keys: ["skill_motion.yml:kigi_slash.scale", ...] 또는 "kigi_slash.scale"(파일 생략 시 skill_motion.yml)."""
    if not keys:
        return Check("config", True, "시험할 키가 지정되지 않았다 — 건너뛴다", fatal=False)
    import yaml
    by_file = {}
    for k in keys:
        fname, _, path = k.rpartition(":")
        by_file.setdefault(fname or "skill_motion.yml", []).append(path)

    detail, bad, intentional = [], [], []
    for fname, paths in by_file.items():
        a, b = REPO_CONFIG / fname, TEST_CONFIG / fname
        if not a.exists() or not b.exists():
            bad.append(f"{fname} 이 한쪽에 없다 (저장소 {a.exists()} · 플러그인 {b.exists()})")
            continue
        ya = yaml.safe_load(a.read_text(encoding="utf-8"))
        yb = yaml.safe_load(b.read_text(encoding="utf-8"))
        for path in paths:
            va, vb = _dig(ya, path), _dig(yb, path)
            leaf = path.rsplit(".", 1)[-1]
            if va is KeyError or vb is KeyError:
                bad.append(f"{fname}:{path} — 키가 없다 (저장소 {va is not KeyError} · "
                           f"플러그인 {vb is not KeyError})")
            elif va != vb:
                line = f"{fname}:{path} — 저장소 {va!r} ≠ 플러그인 {vb!r}"
                (intentional if leaf in INTENTIONAL_DIFF else bad).append(line)
            else:
                detail.append(f"{fname}:{path} = {va!r} (같다)")

    for line in intentional:
        detail.append(f"※ 의도적 차이 — {line}")
    if intentional:
        detail.append("   (촬영 편의로 테스트에서만 늘여 둔 값이다. 지속시간·프레임 수를 "
                      "라이브 값으로 읽지 마라.)")
    if bad:
        detail.append("")
        for line in bad:
            detail.append(f"★ {line}")
        detail.append("★ **config 미동기화 — 위 키로는 라이브와 다른 것을 재게 된다.**")
        return Check("config", False, detail)
    return Check("config", True, detail)


# ══════════════════════════════════════════════════════════════════
#  ⑧ 메모리 신선도 — 파일을 고치고 **재적재를 안 했는가**
# ══════════════════════════════════════════════════════════════════
#
# ★ 왜 이것이 새로 필요해졌나.
#   ③ config 검사는 「저장소 파일 ↔ 플러그인 파일」을 본다. 핫 리로드(/혼천 모션 재적재)가
#   생기기 전에는 그것으로 충분했다 — 파일이 같으면 서버는 반드시 그 파일로 떴으니까.
#
#   이제는 아니다. **파일만 고치고 재적재를 안 하면** 두 파일은 같은데 메모리엔 옛 값이 있다.
#   그러면 ③ 은 초록불을 켜고, 하네스는 "scale 3.0 을 재고 있다"고 믿으며 2.0 을 잰다.
#   ⇒ 핫 리로드는 루프를 20배 빠르게 하면서 **새로운 조용한 거짓말을 하나 만들었다.**
#      이 검사가 그 자리를 막는다.
#
# 어떻게 재는가: 플러그인이 재적재할 때마다 로그에 한 줄을 찍는다
#     [모션] 재적재 — 2026-07-20 22:31:04 · 파일 mtime 2026-07-20 22:30:58 · 12ms
# 그 시각과 파일 mtime 을 견준다. **파일이 더 새로우면 재적재를 안 한 것이다.**
# 한 번도 재적재를 안 했다면 기준은 프로세스 기동 시각이다 (그때 onEnable 이 읽었으니까).

MOTION_RELOAD_RE = re.compile(r"\[모션\] 재적재 — (\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})")
MOTION_FILE = "skill_motion.yml"


def _parse_clock(text):
    try:
        return time.mktime(time.strptime(text, "%Y-%m-%d %H:%M:%S"))
    except ValueError:
        return None


def check_motion_memory(log: Path, server_pid=None):
    """플러그인 config 파일이 **메모리에 실린 값보다 새로운가** — 새로우면 재적재를 안 한 것이다."""
    f = TEST_CONFIG / MOTION_FILE
    if not f.exists():
        return Check("메모리 신선도", True,
                     f"{MOTION_FILE} 이 플러그인 config 에 없다 — 잴 것이 없다", fatal=False)
    mtime = f.stat().st_mtime

    loaded_at, how = None, None
    if log is not None and log.exists():
        # 로그는 이번 판의 것이어야 한다 (④ 가 이미 그것을 보증한다)
        stamps = MOTION_RELOAD_RE.findall(log.read_text(encoding="utf-8", errors="replace"))
        if stamps:
            loaded_at, how = _parse_clock(stamps[-1]), "마지막 재적재"

    if loaded_at is None:
        # 재적재를 한 번도 안 했다 — 그러면 onEnable 이 읽은 것, 즉 프로세스 기동 시각이 기준이다
        pid = server_pid if server_pid is not None else find_server_pid(TEST_DIR)
        started = proc_start_time(pid) if pid else None
        if started is None:
            return Check("메모리 신선도", True,
                         "서버 기동 시각도 재적재 기록도 못 찾았다 — 못 잰다", fatal=False)
        loaded_at, how = started, "서버 기동 (재적재 기록 없음)"

    fmt = lambda t: time.strftime("%H:%M:%S", time.localtime(t))  # noqa: E731
    detail = [f"{MOTION_FILE} mtime {fmt(mtime)} · 메모리 {fmt(loaded_at)} ({how})"]
    if mtime > loaded_at + 1:      # 1초 여유 — 같은 초 안의 순서까지 따지지 않는다
        detail.append("")
        detail.append(f"★ 파일이 메모리보다 {int(mtime - loaded_at)}초 새롭다 — "
                      f"**고쳐 놓고 재적재를 안 했다.**")
        detail.append("★ 지금 재면 파일에 적힌 값이 아니라 **옛 값**을 재게 된다.")
        detail.append("   고치는 법:  /혼천 모션 재적재   (RCON 으로도 된다 · 서버는 안 내려간다)")
        return Check("메모리 신선도", False, detail)
    detail.append("파일 ↔ 메모리 동기 — 지금 도는 값이 파일이 말하는 값이다")
    return Check("메모리 신선도", True, detail)


# ══════════════════════════════════════════════════════════════════
#  ④ 로그 신선도 — 읽는 로그가 지금 도는 프로세스의 것인가
# ══════════════════════════════════════════════════════════════════
def check_log(server_dir: Path):
    pid = find_server_pid(server_dir)
    if pid is None:
        return None, Check("로그 신선도", False,
                           [f"{server_dir.relative_to(ROOT)} 에서 도는 서버가 없다",
                            "★ 테스트 서버를 먼저 띄워라 (하네스가 띄우기 전이면 이 검사는 그 뒤에 돈다)"])
    log, why = find_live_log(server_dir, pid)
    started = proc_start_time(pid)
    head = [f"서버 PID {pid} · 기동 {time.strftime('%m-%d %H:%M:%S', time.localtime(started))}"]
    if log is None:
        return None, Check("로그 신선도", False, head + [f"★ {why}",
                                                    "★ **죽은 서버의 옛 로그를 읽고 있다.**"])
    return (pid, log), Check("로그 신선도", True, head + [f"읽는 로그: {why}"])


# ══════════════════════════════════════════════════════════════════
#  ⑤ 자원 — 메모리 · 누수 클라
# ══════════════════════════════════════════════════════════════════
def _mem_available_gb():
    for line in Path("/proc/meminfo").read_text().splitlines():
        if line.startswith("MemAvailable:"):
            return int(line.split()[1]) / (1024 * 1024)
    return 0.0


def count_clients(work_dir: Path):
    """이 work-dir 로 도는 마인크래프트 클라 프로세스의 PID 들.

    ★ pgrep -f <경로> 를 그냥 쓰면 안 된다 — 두 가지로 틀린다(실측):
      ① `scratch/mcdata` 가 `scratch/mcdata-cam` 에도 부분일치해 카메라를 봇으로 겹쳐 센다.
      ② 카메라 클라의 클래스패스는 **에셋을 공유**하느라 `scratch/mcdata/...` 를 수백 번 담고 있다.
      그래서 「누수 0」인데 「6개 누수」라고 소리쳤다. 소리내는 눈도 틀릴 수 있다.
    ⇒ 정확한 인자로만 센다: 클라는 `--gameDir <work-dir>` 로 제 자리를 받는다. 그것 하나만 본다.
      (메모리를 먹는 것도 이 자바 프로세스다. 런처(python)는 셀 값어치가 없다.)
    """
    return _pids_for(work_dir, ("--gameDir",))


def _pids_for(work_dir, flags):
    want = str(Path(work_dir).resolve())
    pids = []
    for p in Path("/proc").iterdir():
        if not p.name.isdigit():
            continue
        try:
            argv = (p / "cmdline").read_bytes().decode("utf-8", "replace").split("\0")
        except OSError:
            continue
        for flag in flags:
            if flag in argv:
                i = argv.index(flag)
                if i + 1 < len(argv) and argv[i + 1].rstrip("/") == want:
                    pids.append(int(p.name))
                    break
    return sorted(pids)


def reap_clients(work_dir: Path, verbose=True):
    """이 work-dir 의 클라를 거둔다. 거둔 PID 를 돌려준다.

    ★ work-dir 인자로만 좁혀 죽인다 — 라이브 서버·봇은 건드리지 않는다.
      런처(--work-dir)까지 함께 거둔다: 자바만 죽이면 파이썬 런처가 고아로 남는다.
    """
    flags = ("--gameDir", "--work-dir")
    pids = _pids_for(work_dir, flags)
    for pid in pids:
        try:
            os.kill(pid, 15)
        except OSError:
            pass
    if pids:
        time.sleep(2)
        for pid in _pids_for(work_dir, flags):  # 아직 살아 있으면 확실히
            try:
                os.kill(pid, 9)
            except OSError:
                pass
        if verbose:
            print(f"[정리] {Path(work_dir).name}: 클라 {len(pids)}개 거둠 {pids}")
    return pids


def check_players(names):
    """시험에 나올 봇들이 **정말 서버에 붙어 있는가** — 창이 떠 있는 것과는 다른 이야기다.

    ★ 왜 이것이 필요한가 (2026-07-20 · 이 검사기를 세우다가 실물로 만났다):
      테스트 서버를 내렸다 올리면 옛 클라는 죽지 않는다 — 「연결이 끊겼습니다」 화면에 남는다.
      그런데 하네스의 생존 판정은 **창이 있는가**였다. 창은 멀쩡히 있다. 게임 화면 판정도
      통과한다 (그 화면은 Mojang 빨강도 아니고 단색도 아니다). 그래서 하네스는
      **접속이 끊긴 클라의 정지 화면을 8회 촬영하고 「검기 0px」 를 뱉는다.**
    ⇒ 창을 믿지 않는다. **서버에게 묻는다** — `list` 에 이름이 있는가.
    """
    if not names:
        return Check("봇 접속", True, "확인할 봇이 지정되지 않았다 — 건너뛴다", fatal=False)
    try:
        from kigi_rcon import Rcon
        r = Rcon()
        out = r.cmd("list")
        r.close()
    except SystemExit as e:
        return Check("봇 접속", False, f"RCON 으로 물어볼 수 없다: {e}")
    except OSError as e:
        return Check("봇 접속", False, f"RCON 연결 실패: {e}")
    detail = [f"서버 응답: {out.strip()}"]
    missing = [n for n in names if not re.search(rf"\b{re.escape(n)}\b", out)]
    if missing:
        detail.append(f"★ 접속되어 있지 않다: {', '.join(missing)}")
        detail.append("★ **창이 떠 있어도 접속은 끊겼을 수 있다** (서버를 내렸다 올리면 그렇게 된다).")
        detail.append("   그 화면을 찍으면 「검기 0px」 가 나오는데, 그것은 검기가 없어서가 아니다.")
        detail.append("   → 클라를 거두고 다시 띄워라 (--restart-cam · --cleanup 뒤 재실행)")
        return Check("봇 접속", False, detail)
    detail.append(f"→ {', '.join(names)} 전부 접속 중")
    return Check("봇 접속", True, detail)


def check_resources(work_dirs):
    free = _mem_available_gb()
    detail = [f"가용 메모리 {free:.1f}GB (필요 {MIN_FREE_GB:.0f}GB 이상)"]
    ok = free >= MIN_FREE_GB
    if not ok:
        detail.append("★ **메모리 부족 — 옛 클라를 거두고 다시 재라** "
                      "(--cleanup 또는 pkill -f <work-dir>)")
    leaked = []
    for wd in work_dirs:
        pids = count_clients(Path(wd))
        detail.append(f"{Path(wd).name}: 클라 {len(pids)}개 {pids if pids else ''}")
        if len(pids) > 1:      # work-dir 하나에 클라는 하나뿐이어야 한다
            leaked.append(f"{Path(wd).name} 에 {len(pids)}개 (있어야 할 것은 1개)")
    if leaked:
        ok = False
        detail.append("★ 누수 클라: " + " · ".join(leaked))
        detail.append("★ 소프트렌더 클라는 한 대에 수 GB 다 — 쌓이면 기계가 선다 "
                      "(그날 52개가 쌓여 가용 1GB 가 됐다)")
    return Check("자원", ok, detail)


def check_bridge():
    """★ 테스트의 세계다리가 라이브와 갈려 있는가 (2026-07-20).

    왜 이것이 목숨줄인가: MVT 플러그인에는 **DB 연결 코드가 없다**(실측).
    DB 에 쓰는 것은 봇이고, MVT 는 `world_bridge.yml transport.dir` 의
    **파일 스풀**로 봇에게 사건을 넘긴다. 두 서버가 같은 dir 를 보면
    **테스트가 만든 사건이 라이브 봇에게 흘러가 운영 DB 에 기록된다.**
    DB 권한을 아무리 나눠도 막지 못한다 — 쓰는 주체가 라이브 봇이기 때문이다.
    운영 데이터는 한 번 오염되면 되돌릴 수 없다. 그래서 매번 검사한다.
    """
    live = ROOT / "run" / "mvt" / "plugins" / "HoncheonMVT" / "config" / "world_bridge.yml"
    test = ROOT / "run" / "mvt-test" / "plugins" / "HoncheonMVT" / "config" / "world_bridge.yml"
    pat = re.compile(r"(?m)^\s*dir:\s*(\S+)")

    def read_dir(p):
        if not p.exists():
            return None
        m = pat.search(p.read_text(encoding="utf-8", errors="replace"))
        return m.group(1) if m else None

    ld, td = read_dir(live), read_dir(test)
    detail = [f"라이브 dir: {ld or '읽기 실패'}", f"테스트 dir: {td or '읽기 실패'}"]
    if td is None:
        return Check("세계다리 격리", False, detail + ["★ 테스트 world_bridge.yml 을 못 읽었다"])
    if ld is not None and ld == td:
        return Check("세계다리 격리", False, detail + [
            "★ **같은 경로다 — 테스트 사건이 라이브 봇을 거쳐 운영 DB 로 샌다**",
            "★ 테스트의 transport.dir 을 run/bridge-test 로 갈라라"])
    detail.append("갈려 있다 — 테스트 사건이 라이브 봇에게 가지 않는다")
    return Check("세계다리 격리", True, detail)


# ══════════════════════════════════════════════════════════════════
#  묶어서 — 하네스가 부르는 자리
# ══════════════════════════════════════════════════════════════════
class PreflightResult:
    def __init__(self, checks, lines, ok, forced):
        self.checks = checks
        self.lines = lines
        self.ok = ok
        self.forced = forced
        self.log = None

    def write(self, outdir: Path):
        """결과 폴더에 남긴다 — 나중에 "그때 조건이 뭐였나"를 답할 수 있게."""
        outdir.mkdir(parents=True, exist_ok=True)
        p = outdir / "preflight.txt"
        p.write_text("\n".join(self.lines) + "\n", encoding="utf-8")
        return p


def run(keys=None, work_dirs=(), players=(), server_dir=TEST_DIR, outdir=None,
        force=False, verbose=True, detector=True):
    """전제를 검사한다. 어긋나면 `ok=False` — 부른 쪽은 **측정하지 말고 멈춰야 한다**."""
    lines = []

    def say(s=""):
        lines.append(s)
        if verbose:
            print(s)

    say()
    say("┏" + "━" * 76)
    say(f"┃  preflight — 재기 전에 전제를 검사한다   {time.strftime('%Y-%m-%d %H:%M:%S')}")
    say("┃  (0px 는 「효과가 없다」가 아니라 「내 눈이 멀었다」일 수 있다)")
    say("┗" + "━" * 76)

    checks = []
    found, log_check = check_log(server_dir)
    checks.append(log_check)
    pid, log = found if found else (None, None)

    if log is not None:
        checks.append(check_pack_chain(log))
    else:
        checks.append(Check("팩 사슬", False, "로그를 못 골라 사슬을 못 잇는다 (위 항목을 먼저 고쳐라)"))
    checks.append(check_jar(pid))
    checks.append(check_config(keys))
    # ★ ③ 다음에 온다 — ③ 은 「파일끼리 같은가」, 이것은 「그 파일이 정말 메모리에 실렸는가」.
    #   핫 리로드가 생긴 뒤로는 둘 다 봐야 한다 (③ 만 보면 초록불 아래서 옛 값을 잰다).
    checks.append(check_motion_memory(log, pid))
    checks.append(check_players(players))
    checks.append(check_bridge())   # ★ 되돌릴 수 없는 것부터 — 운영 데이터 오염을 막는다
    checks.append(check_resources(work_dirs))

    for c in checks:
        say(str(c))

    if detector:
        ok_det, det_lines = vfx_detect.selftest(verbose=False)
        c = Check("검출기 자가시험", ok_det, [l.strip() for l in det_lines])
        checks.append(c)
        say(str(c))

    bad = [c for c in checks if not c.ok and c.fatal]
    ok = not bad
    say()
    if ok:
        say("  ✔ preflight 통과 — 이 조건에서 잰 숫자를 믿어도 된다")
    elif force:
        say(f"  ⚠ preflight {len(bad)}건 실패 — 그러나 --force 다. **이 측정의 숫자는 근거로 쓰지 마라.**")
        for c in bad:
            say(f"      · {c.name}")
    else:
        say(f"  ✘ preflight {len(bad)}건 실패 — **측정을 중단한다** (0px 를 「효과 없음」으로 읽지 않기 위해)")
        for c in bad:
            say(f"      · {c.name}")
        say("    조건을 고치고 다시 부르라. 정말 무시하려면 --force (권장하지 않는다).")
    say()

    res = PreflightResult(checks, lines, ok or force, force and not ok)
    res.log = log
    if outdir:
        res.write(Path(outdir))
    return res


def gate(**kw):
    """검사하고, 어긋났으면 **여기서 끝낸다.** 하네스는 이것만 부르면 된다."""
    res = run(**kw)
    if not res.ok:
        raise SystemExit("preflight 실패 — 측정하지 않았다 (조건을 고쳐라)")
    return res


def main():
    ap = argparse.ArgumentParser(description="시각효과 하네스 preflight (조건만 검사한다)")
    ap.add_argument("--keys", nargs="*", default=[],
                    help="검사할 config 키 (예: kigi_slash.scale · skills.yml:foo.bar)")
    ap.add_argument("--work-dirs", nargs="*", default=[])
    ap.add_argument("--players", nargs="*", default=[],
                    help="접속해 있어야 할 봇 이름 (예: kigibot kigicam)")
    ap.add_argument("--outdir", default=None)
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--no-detector", action="store_true")
    a = ap.parse_args()
    res = run(keys=a.keys, work_dirs=a.work_dirs, players=a.players, outdir=a.outdir,
              force=a.force, detector=not a.no_detector)
    sys.exit(0 if res.ok and not res.forced else 1)


if __name__ == "__main__":
    main()
