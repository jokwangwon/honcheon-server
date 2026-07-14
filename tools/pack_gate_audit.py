#!/usr/bin/env python3
"""팩 게이트 감사 — **문이 실제로 닫혔는가**를 재는 눈.

2026-07-13, 헌법이 바뀌었다. 옛 조항 「팩 게이트 불가침」(팩이 없어도 플레이는 된다)이 폐지됐다.
이제 **팩이 있어야 강호가 보이고, 보이지 않는 강호에는 들이지 않는다.**

그런데 이 문은 **조용히 열릴 수 있는 종류의 문**이다. 열려 있어도 아무 증상이 없다 —
팩을 거절한 사람도 멀쩡히 들어와 논다. 아무도 신고하지 않는다. 로그도 평온하다.
그러므로 사람의 눈으로는 못 지킨다. 이 도구가 지킨다.

  ① **실물이 있는가**            팩 zip 이 정말 있는가 — 없으면 배급이 꺼지고 **문도 같이 열린다** (B-004)
  ② **등록부가 문을 닫았는가**   resource_pack.yml → enabled · required: true · gate.enabled
  ③ **코드가 그것을 되돌리지 않는가**  옛 PackPusher 는 true 를 **false 로 강제**했다. 그 손이 죽었는가
  ④ **팩이 required 를 달고 나가는가**  setResourcePack(..., required) — 등록부의 값이 그대로 실리는가
  ⑤ **두 길이 다 막히는가**      거절(DECLINED)과 다운로드 실패(FAILED_DOWNLOAD) — 둘 다 내보내는가
  ⑥ **친절하게 막는가**          이유마다 다른 말이 등록부에 있는가 · 운영자 로그가 남는가
  ⑦ **등록부가 거짓말하지 않는가**  prompt 가 아직 "거절해도 플레이는 가능합니다" 라고 적고 있지 않은가

★ ① 이 태어난 사연 (B-004): 등록부·코드가 전부 옳아도, 문이 **실제로** 열리는 길은 하나뿐이었다 —
  팩 zip 실물이 없으면 load() 가 배급을 끄고(enabled=false), 팩을 안 보내니 거절도 실패도 침묵도
  일어나지 않는다. 등록부는 "닫혔다"고 말하는데 세계에선 열려 있다. 그 하나를 이 눈이 안 봤다.
  그래서 이제 코드가 재는 **바로 그 실물**을, 코드와 **같은 기준**(서버 폴더 run/mvt)으로 잰다.

config·소스를 고치지 않는다 — 재기만 한다. 문구·수치는 전부 등록부에서 읽는다.

사용법:  python3 tools/pack_gate_audit.py
눈을 시험하려면:  python3 tools/pack_gate_audit_selftest.py
종료 코드: 위반(❌) 1건 이상이면 1.
"""

from __future__ import annotations

import hashlib
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from game_audit import Report, load_all, dig  # noqa: E402  — 문법·출력 형식 계승

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PUSHER = os.path.join(ROOT, "server-mvt/src/main/java/com/honcheon/mvt/PackPusher.java")
# 서버 작업 디렉터리 — scripts/run_mvt_server.sh 의 RUN. 등록부의 local_path 는 여기 기준이다.
# (PackPusher 는 Path.of(local_path) 를 JVM 작업 디렉터리에서 푼다 — 그 디렉터리가 이곳이다.)
SERVER_DIR = os.path.join(ROOT, "run/mvt")

# 등록부가 반드시 갖춰야 할 말 — 실패의 이유마다 사람이 할 일이 다르므로 말도 달라야 한다
REQUIRED_MESSAGES = {
    "declined": "거절 — 그 사람이 **수락**하면 된다",
    "failed_download": "다운로드 실패 — 그 사람이 할 일은 **재시도**다",
    "failed_other": "그 밖(INVALID_URL·FAILED_RELOAD) — **관리자**를 불러야 한다",
}

# 옛 헌법의 거짓말 — prompt 에 이런 말이 남아 있으면 등록부가 사람을 속이고 있다
LIES = ("거절해도 플레이는 가능", "팩이 없어도 플레이", "받지 않아도 플레이")

# 반드시 막아야 하는 두 길 (Paper 의 상태값)
MUST_CLOSE = ("DECLINED", "FAILED_DOWNLOAD")


def source(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return fh.read()
    except OSError:
        return None


def sha1_of(path):
    """실물의 sha1 — PackPusher.sha1() 과 같은 것을 잰다 (못 읽으면 None, 코드도 그때 배급을 끈다)."""
    try:
        with open(path, "rb") as fh:
            return hashlib.sha1(fh.read()).hexdigest()
    except OSError:
        return None


def strip_comments(src):
    """주석은 **말이지 코드가 아니다.** 주석에 적힌 옛 문장에 눈이 속으면 안 된다."""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def body_of(src, signature):
    """중괄호 짝을 세어 메서드 **속**을 본다 — 이름만 보고 속지 않는다."""
    start = src.find(signature)
    if start < 0:
        return None
    brace = src.find("{", start)
    if brace < 0:
        return None
    depth, i = 0, brace
    while i < len(src):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[brace:i + 1]
        i += 1
    return None


def arm_of(switch_body, status):
    """switch 의 한 가지(case …)의 **속**을 꺼낸다.

    ★ 처음에 `[^:]*->` 로 썼다가 눈이 거짓말을 했다 — 탐욕 매칭이 그 가지를 **뛰어넘어**
      엉뚱한 `default ->` 를 붙잡았고, FAILED_DOWNLOAD 가 안 막혔다고 오보를 냈다.
      (거절 문구엔 콜론이 있고 실패 문구엔 없었다 — 그 차이로 결과가 갈렸다.)
      그래서 라벨은 **한 줄 안에서만** 찾고, 몸은 중괄호를 세어 꺼낸다.
    """
    m = re.search(rf"case\s+[^:\n{{}}]*\b{status}\b[^:\n]*->\s*", switch_body)
    if m is None:
        return None
    rest = switch_body[m.end():]
    if not rest.startswith("{"):
        return rest.split("\n", 1)[0]          # 한 줄짜리 가지
    depth = 0
    for i, ch in enumerate(rest):
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return rest[:i + 1]
    return rest


def main():
    rep = Report()
    rep.say("═" * 74)
    rep.say("  팩 게이트 감사 — 문이 실제로 닫혔는가")
    rep.say("  헌법(2026-07-13): 팩이 있어야 강호가 보인다. 보이지 않는 강호에는 들이지 않는다.")
    rep.say("═" * 74)

    cfg = load_all()
    pack = dig(cfg, "resource_pack.yml", "resource_pack", default=None)
    src_raw = source(PUSHER)

    if pack is None:
        rep.fail("등록부를 못 읽었다 — config/resource_pack.yml → resource_pack")
        rep.dump()
        return 1
    if src_raw is None:
        rep.fail(f"소스를 못 읽었다 — {PUSHER}")
        rep.dump()
        return 1
    src = strip_comments(src_raw)

    # ── ① 실물이 있는가 — 이 문이 실제로 열리는 유일한 길 ────────────────────
    # 【B-004】 실물이 없으면 load() 가 배급을 끈다(enabled=false). 팩을 안 보내니
    # 거절·실패·침묵의 문이 발동할 일 자체가 없다 — 게이트가 조용히 열린다.
    # 등록부의 required 는 그대로 true 다. 등록부는 닫혔다고 말하고, 세계에선 열려 있다.
    rep.head("① 실물이 있는가 — 이 문이 실제로 열리는 유일한 길")
    file_name = str(pack.get("file") or "honcheon_pack.zip")
    raw_local = pack.get("local_path")
    local_path = "../pack-http/" + file_name if raw_local is None else str(raw_local)
    resolved = local_path if os.path.isabs(local_path) \
        else os.path.normpath(os.path.join(SERVER_DIR, local_path))
    shown = os.path.relpath(resolved, ROOT)
    if not os.path.isfile(resolved):
        rep.fail(f"실물이 없다 — {shown} · 배급이 꺼지고(enabled=false) **게이트도 같이 열린다**."
                 " 팩을 구워 두라: scripts/run_mvt_server.sh 또는 scripts/serve_resourcepack.sh")
    elif os.path.getsize(resolved) == 0:
        rep.fail(f"실물이 비었다 (0바이트) — {shown} · 전원이 다운로드 실패로 튕긴다 (문이 아니라 벽이다)")
    else:
        sha = sha1_of(resolved)
        if sha is None:
            rep.fail(f"실물을 못 읽었다 — {shown} · 코드도 같은 이유로 배급을 끈다 (게이트가 열린다)")
        else:
            rep.ok(f"실물이 있다 — {shown} · {os.path.getsize(resolved):,}바이트 · sha1 {sha[:12]}…")
    # 눈과 코드가 **같은 곳**을 보는가 — 여기가 어긋나면 이 축 전체가 거짓말이 된다
    rep.verdict('"../pack-http/" + file' in src,
                "코드의 기본 경로(../pack-http/<file>)와 이 눈의 기본 경로가 같다")
    rep.verdict(bool(re.search(r"hash\s*=\s*sha1\(\s*localPath\s*\)", src)),
                "코드가 실물(localPath)에서 sha1 을 잰다 — 사람이 옮겨 적지 않는다")
    null_arm = body_of(src, "if (hash == null)")
    if null_arm is None:
        rep.fail("실물 부재를 다루는 가지(if (hash == null))가 없다 — 없는 팩을 배급할 수는 없다")
    else:
        rep.verdict("enabled = false" in null_arm,
                    "실물이 없으면 배급을 끈다 — 닿지 않는 주소로 팩을 보내지 않는다")
        rep.verdict(".severe(" in null_arm,
                    "실물이 없으면 **소리내어 말한다** (severe) — 열린 문이 조용하면 아무도 모른다")

    # ── ② 등록부가 문을 닫았는가 ────────────────────────────────────────────
    rep.head("② 등록부가 문을 닫았는가")
    enabled = pack.get("enabled")
    rep.verdict(enabled is not False,
                f"enabled — 배급 자체가 켜져 있다 (꺼지면 팩을 안 보내고, 문도 같이 열린다) (지금: {enabled!r})")
    required = pack.get("required")
    rep.verdict(required is True,
                f"required: true — 팩이 있어야 들어온다 (지금: {required!r})")
    gate = pack.get("gate") or {}
    rep.verdict(gate.get("enabled") is not False,
                f"gate.enabled — 못 받은 자를 내보낸다 (지금: {gate.get('enabled')!r})")

    # ── ② 코드가 그것을 되돌리지 않는가 ──────────────────────────────────────
    rep.head("③ 코드가 등록부를 되돌리지 않는가 (옛 강제의 시체가 남아 있지 않은가)")
    load_body = body_of(src, "void load(Path configDir)")
    if load_body is None:
        rep.fail("PackPusher.load() 를 못 찾았다 — 눈이 볼 것을 잃었다")
    else:
        reverted = re.search(r"required\s*=\s*false\s*;", load_body)
        rep.verdict(not reverted,
                    "load() 가 required 를 false 로 되돌리지 않는다 (옛 「불가침」 강제 제거됨)")
        reads = re.search(r"required\s*=\s*Boolean\.TRUE\.equals\(\s*s\.get\(\"required\"\)\s*\)",
                          load_body)
        rep.verdict(bool(reads), "load() 가 등록부의 required 를 그대로 읽는다")
    # 클래스 어디에도 재대입이 없어야 한다 (load 밖에 숨겨 두는 수도 있다)
    elsewhere = [m.start() for m in re.finditer(r"required\s*=\s*false\s*;", src)]
    rep.verdict(not elsewhere,
                f"클래스 어디에도 `required = false;` 재대입이 없다 (발견 {len(elsewhere)}건)")

    # ── ④ 팩을 보낼 때 required 가 실제로 실려 나가는가 ───────────────────────
    rep.head("④ 팩이 required 플래그를 달고 나가는가")
    push = re.search(r"setResourcePack\s*\([^;]*?,\s*(\w+)\s*\)\s*;", src, flags=re.S)
    if push is None:
        rep.fail("setResourcePack(...) 호출을 못 찾았다")
    else:
        arg = push.group(1)
        rep.verdict(arg == "required",
                    f"setResourcePack(..., required) — 등록부의 값이 그대로 실린다 (지금: {arg})")

    # ── ⑤ 두 길이 다 막히는가 ───────────────────────────────────────────────
    rep.head("⑤ 거절과 다운로드 실패 — 두 길이 다 막히는가")
    status_body = body_of(src, "void onStatus(PlayerResourcePackStatusEvent event)")
    if status_body is None:
        rep.fail("onStatus(PlayerResourcePackStatusEvent) 를 못 찾았다 — 문이 아예 없다")
        status_body = ""
    for st in MUST_CLOSE:
        arm = arm_of(status_body, st)
        if arm is None:
            rep.fail(f"{st} 를 듣는 가지가 없다 — 이 길은 열려 있다")
            continue
        rep.verdict("close(" in arm, f"{st} → close(...) 로 내보낸다")
    # close() 가 정말 내보내는가 — 이름만 보고 속지 않는다
    close_body = body_of(src, "void close(Player player, String message, String why)")
    if close_body is None:
        rep.fail("close(Player, String, String) 를 못 찾았다 — 내보내는 손이 없다")
    else:
        rep.verdict(".kick(" in close_body, "close() 가 실제로 player.kick(...) 을 부른다")
        rep.verdict("getLogger()" in close_body,
                    "close() 가 서버 로그에 남긴다 — 운영자가 누가 왜 못 들어왔는지 안다")
        # ★ 자기 시험이 잡아낸 구멍: `if (false)` 로 감싸 두면 **문자열은 그대로 있는데 아무 일도 안 한다.**
        #   이름만 보고 속지 않으려면 **죽은 코드**를 거절해야 한다.
        dead = re.search(r"if\s*\(\s*(false|0\s*==\s*1)\s*\)", src)
        rep.verdict(dead is None,
                    "PackPusher 에 죽은 가지(`if (false)`)가 없다 — 있으면 손은 있는데 아무 일도 안 한다")
    # DISCARDED 로는 내보내면 안 된다 (서버가 팩을 거둔 것이지 그 사람 탓이 아니다)
    discarded = arm_of(status_body, "DISCARDED")
    rep.verdict(discarded is None or "close(" not in discarded,
                "DISCARDED 로는 내보내지 않는다 (서버가 팩을 거둔 것 — 그 사람 탓이 아니다)")

    # ── ⑥ 친절하게 막는가 — 이유마다 다른 말이 있는가 ────────────────────────
    rep.head("⑥ 친절하게 막는가 — 실패의 이유마다 다른 말이 등록부에 있는가")
    seen = {}
    for key, why in REQUIRED_MESSAGES.items():
        msg = gate.get(key)
        if not msg or not str(msg).strip():
            rep.fail(f"gate.{key} 가 비었다 — {why}. 말없이 튕기면 그건 문이 아니라 벽이다")
            continue
        text = str(msg)
        seen[key] = text
        rep.ok(f"gate.{key} — {len(text)}자 ({why})")
    if len(set(seen.values())) != len(seen):
        rep.fail("gate 문구가 서로 같다 — 이유가 다른데 같은 말을 하면 사람은 할 일을 모른다")
    elif len(seen) == len(REQUIRED_MESSAGES):
        rep.ok("셋이 서로 다른 말을 한다 — 수락하라 / 재시도하라 / 관리자를 부르라")
    # 다시 들어오는 법을 말하는가
    for key in ("declined", "failed_download"):
        text = seen.get(key, "")
        rep.verdict("접속" in text or "수락" in text,
                    f"gate.{key} 가 **다시 들어오는 법**을 말한다")

    # ── ⑦ 등록부가 거짓말하지 않는가 ────────────────────────────────────────
    rep.head("⑦ 등록부가 거짓말하지 않는가")
    prompt = str(pack.get("prompt", ""))
    lie = next((l for l in LIES if l in prompt), None)
    rep.verdict(lie is None,
                f"prompt 가 옛 헌법의 거짓말을 하지 않는다"
                + (f" — 발견: “…{lie}…”" if lie else ""))

    # ── 총평 ───────────────────────────────────────────────────────────────
    rep.say()
    rep.say("─" * 74)
    if rep.violations:
        rep.say(f"  총평: ❌ 위반 {len(rep.violations)}건 — **문이 닫혔다고 말할 수 없다**")
    else:
        rep.say("  총평: ✅ 위반 0건 — 팩이 없으면 강호에 들어오지 못한다 (친절하게 막는다)")
    rep.say("─" * 74)
    rep.dump()
    return 1 if rep.violations else 0


if __name__ == "__main__":
    sys.exit(main())
