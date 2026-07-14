#!/usr/bin/env python3
"""팩 게이트 감사의 **자기 시험** — 눈을 시험하는 눈.

"위반 0건"은 두 가지 뜻이다: **문이 닫혔다**, 또는 **눈이 멀었다.** 둘은 화면에서 똑같이 보인다.
이 프로젝트에서 눈은 이미 여러 번 거짓말했다. 이 감사도 만들자마자 한 번 거짓말했다 —
탐욕 정규식이 `FAILED_DOWNLOAD` 가지를 뛰어넘어 엉뚱한 곳을 보고 오보를 냈다.

그래서 pack_gate_audit.py 에게 **일부러 거짓말을 먹인다**: 게이트를 열어 두고, 옛 강제를 되살리고,
막는 손을 뜯고, 할 말을 지운다 — 그때마다 눈이 **실제로 잡는지** 본다.
잡으면 ✅, 못 잡으면 ❌. 끝나면 전부 되돌린다 (config·소스는 손대지 않은 상태로 남는다).

사용법:  python3 tools/pack_gate_audit_selftest.py
종료 코드: 눈이 하나라도 놓치면 1.
"""
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CFG = os.path.join(ROOT, "config/resource_pack.yml")
SRC = os.path.join(ROOT, "server-mvt/src/main/java/com/honcheon/mvt/PackPusher.java")
AUDIT = os.path.join(ROOT, "tools/pack_gate_audit.py")
# 배급 실물 — 감사 축 ① 이 재는 바로 그 파일 (등록부 local_path 를 서버 폴더 run/mvt 기준으로 푼 곳)
ZIP = os.path.join(ROOT, "run/pack-http/honcheon_pack.zip")

# (이름, 파일, 원본조각, 바꿀조각, 잡아야 하는 말의 조각)
# ★ 원본조각이 None 이면 텍스트 변이가 아니라 **파일 자체를 잠시 숨기는** 변이다 (끝나면 되돌린다)
MUTATIONS = [
    ("① 게이트를 도로 연다 (등록부)", CFG,
     "  required: true ", "  required: false ",
     "required: true"),

    ("② 옛 강제를 되살린다 (코드가 등록부를 되돌린다)", SRC,
     "        required = Boolean.TRUE.equals(s.get(\"required\"));",
     "        required = Boolean.TRUE.equals(s.get(\"required\"));\n"
     "        if (required) { required = false; }",
     "false 로 되돌리지 않는다"),

    ("③ 팩을 required 없이 내보낸다", SRC,
     "net.kyori.adventure.text.Component.text(prompt), required);",
     "net.kyori.adventure.text.Component.text(prompt), false);",
     "등록부의 값이 그대로 실린다"),

    ("④ 거절한 자를 도로 들여보낸다", SRC,
     "                close(player, msgDeclined, \"거절\");",
     "                ;",
     "DECLINED → close"),

    ("⑤ 다운로드 실패한 자를 도로 들여보낸다", SRC,
     "                close(player, msgFailedDownload, \"다운로드 실패\");",
     "                ;",
     "FAILED_DOWNLOAD → close"),

    ("⑥ 내보내는 손을 뜯는다 (문구는 그대로 두고 kick 만 없앤다)", SRC,
     "        Runnable door = () -> player.kick(said);",
     "        Runnable door = () -> { };",
     "player.kick"),

    ("⑦ 손은 두고 **죽은 가지**로 감싼다 (문자열은 남는데 아무 일도 안 한다)", SRC,
     "        if (plugin.getServer().isPrimaryThread()) {",
     "        if (false) {",
     "죽은 가지"),

    ("⑧ 말없이 튕긴다 (거절 문구를 지운다)", CFG,
     "    declined: |-", "    declined_UNUSED: |-",
     "gate.declined 가 비었다"),

    ("⑨ 이유마다 다른 말을 안 한다 (실패 문구를 지운다)", CFG,
     "    failed_download: |-", "    failed_download_UNUSED: |-",
     "gate.failed_download 가 비었다"),

    ("⑩ 등록부가 다시 거짓말한다 (prompt)", CFG,
     "  prompt: \"혼천 리소스팩 — 수묵 HUD·기세/화후 글리프. 이 팩이 있어야 강호가 보인다. 받아야 들어올 수 있다.\"",
     "  prompt: \"혼천 리소스팩 — 거절해도 플레이는 가능합니다.\"",
     "옛 헌법의 거짓말"),

    ("⑪ 억울한 자를 튕긴다 (DISCARDED 로 내보낸다)", SRC,
     "            default -> plugin.getLogger().warning(\"[팩] \" + who + \" — \" + status);",
     "            case DISCARDED -> close(player, msgFailedOther, \"DISCARDED\");\n"
     "            default -> plugin.getLogger().warning(\"[팩] \" + who + \" — \" + status);",
     "DISCARDED 로는 내보내지 않는다"),

    # ── 【B-004】 실물의 눈 — 문이 실제로 열리는 유일한 길을 이 눈이 보는가 ──────
    ("⑫ 등록부가 없는 실물을 가리킨다 (local_path 표류)", CFG,
     "  local_path: ../pack-http/honcheon_pack.zip",
     "  local_path: ../pack-http/honcheon_pack_GHOST.zip",
     "실물이 없다"),

    ("⑬ 실물 그 자체를 치운다 (파일을 잠시 숨긴다)", ZIP, None, None,
     "실물이 없다"),

    ("⑭ 배급 자체를 끈다 (enabled: false — 팩을 안 보내면 문도 안 선다)", CFG,
     "resource_pack:\n  enabled: true",
     "resource_pack:\n  enabled: false",
     "배급 자체가 켜져 있다"),

    ("⑮ 코드가 다른 곳을 보게 한다 (기본 경로 표류 — 눈과 코드가 갈라진다)", SRC,
     "localPath = Path.of(path == null ? \"../pack-http/\" + file : String.valueOf(path));",
     "localPath = Path.of(path == null ? \"../pack-cache/\" + file : String.valueOf(path));",
     "기본 경로"),

    ("⑯ 실물 부재를 조용하게 만든다 (severe → info)", SRC,
     ".severe(\"[팩] ★ 게이트가 required 인데 팩 실물이 없다 —\"",
     ".info(\"[팩] ★ 게이트가 required 인데 팩 실물이 없다 —\"",
     "소리내어"),
]


def run_audit():
    r = subprocess.run([sys.executable, AUDIT], capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr


def caught(out, needle):
    """그 말이 **❌ 로** 찍혔는가 — ✅ 로 찍힌 같은 말에 속으면 안 된다."""
    return any(needle in line and "❌" in line for line in out.splitlines())


def main():
    print("═" * 74)
    print("  팩 게이트 감사 — 자기 시험 (일부러 문을 열어 두고, 눈이 잡는지 본다)")
    print("═" * 74)

    base_rc, base_out = run_audit()
    if base_rc != 0:
        print("\n❌ 시작부터 위반이 있다 — 먼저 그것부터 고쳐라. 시험을 멈춘다.\n")
        print(base_out)
        return 1
    print("\n  기준선: 위반 0건 (문이 닫혀 있다) — 이제 하나씩 열어 본다\n")

    backups = {}
    for path in (CFG, SRC):
        backups[path] = path + ".selftest.bak"
        shutil.copy2(path, backups[path])

    missed = []
    try:
        for name, path, old, new, needle in MUTATIONS:
            if old is None:
                # 파일 숨김 변이 — 실물을 잠시 개명하고, 무슨 일이 있어도 되돌린다
                if not os.path.isfile(path):
                    print(f"  ⚠️  {name} — 실물이 애초에 없다 (시험이 낡았거나 팩을 안 구웠다): {path}")
                    missed.append(name + " (시험이 낡음)")
                    continue
                hidden = path + ".selftest.hidden"
                os.rename(path, hidden)
                try:
                    rc, out = run_audit()
                finally:
                    os.rename(hidden, path)   # 즉시 되돌린다
            else:
                with open(path, encoding="utf-8") as fh:
                    original = fh.read()
                if old not in original:
                    print(f"  ⚠️  {name} — 원본 조각을 못 찾았다 (시험이 낡았다): {old[:48]!r}")
                    missed.append(name + " (시험이 낡음)")
                    continue
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(original.replace(old, new, 1))
                rc, out = run_audit()
                with open(path, "w", encoding="utf-8") as fh:   # 즉시 되돌린다
                    fh.write(original)

            if rc != 0 and caught(out, needle):
                print(f"  ✅ {name} — 눈이 잡았다")
            elif rc != 0:
                print(f"  ⚠️  {name} — 위반은 났는데 **엉뚱한 것을 잡았다** (기대: “{needle}”)")
                missed.append(name + " (엉뚱한 것을 잡음)")
            else:
                print(f"  ❌ {name} — **눈이 못 잡았다** (위반 0건으로 통과시켰다)")
                missed.append(name)
    finally:
        for path, bak in backups.items():
            shutil.copy2(bak, path)
            os.remove(bak)

    rc, _ = run_audit()
    print("\n" + "─" * 74)
    if rc != 0:
        print("  ❌ 되돌리기 실패 — 파일이 원래대로가 아니다. 손으로 확인하라.")
        return 1
    print("  되돌렸다 — config·소스는 손대지 않은 상태다 (재감사: 위반 0건)")
    if missed:
        print(f"  총평: ❌ 눈이 {len(missed)}건을 놓쳤다 — " + " · ".join(missed))
        print("─" * 74)
        return 1
    print(f"  총평: ✅ {len(MUTATIONS)}건 전부 잡았다 — 이 눈은 볼 수 있다")
    print("─" * 74)
    return 0


if __name__ == "__main__":
    sys.exit(main())
