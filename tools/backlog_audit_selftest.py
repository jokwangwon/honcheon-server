#!/usr/bin/env python3
"""청구서 감사의 **자기 시험** — 눈을 시험하는 눈.

"위반 0건"은 두 가지 뜻이다: **장부가 정직하다**, 또는 **눈이 멀었다.** 둘은 화면에서 똑같이 보인다.

그러므로 backlog_audit.py 에게 **일부러 거짓말하는 장부를 먹인다.** 「닫힘」이라 적어 놓고
증거를 대지 않는 항목, 유령을 가리키는 항목, 없는 도구로 검증한다는 항목, 그리고 ★ 가장 위험한 것 —
**「닫힘」이라 적혀 있으나 감사를 돌리면 실제로는 짖는 항목**을 심는다.

그때마다 눈이 **실제로 잡는지** 본다. 잡으면 ✅, 못 잡으면 ❌.

★ 진짜 장부(docs/BACKLOG.md)는 **건드리지 않는다.** 임시 장부를 만들어 먹인다.

사용법:  python3 tools/backlog_audit_selftest.py
종료 코드: 눈이 하나라도 놓치면 1.
"""
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AUDIT = os.path.join(ROOT, "tools/backlog_audit.py")

# ── 정직한 장부 — 이것으로는 눈이 짖으면 안 된다 ──────────────────────────────
HONEST = """# 시험용 장부

## P1 — 첫 단계

### B-001 · 정직하게 열린 항목
- **상태**: 열림
- **분류**: ★세계
- **단계**: P1
- **위치**: `tools/backlog_audit.py:1`
- **의존**: —
- **닫는 조건**: 아무것도 아니다 (시험용)
- **검증**: `python3 tools/backlog_audit.py`
- **닫힘**: —

### B-002 · 정직하게 닫힌 항목
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P1
- **위치**: `tools/game_audit.py:1`
- **의존**: —
- **닫는 조건**: 팩 게이트가 닫힌다
- **검증**: `python3 tools/pack_gate_audit.py`
- **닫힘**: 2026-07-14 · `python3 tools/pack_gate_audit.py` → 위반 0건
"""

# (이름, 심을 거짓말, 눈이 잡아야 하는 말의 조각, --run 이 필요한가)
WOUNDS = [
    ("① 「닫힘」인데 증거가 *\"고쳤다\"* 뿐이다",
     HONEST.replace(
         "- **닫힘**: 2026-07-14 · `python3 tools/pack_gate_audit.py` → 위반 0건",
         "- **닫힘**: 2026-07-14 · 고쳤다"),
     "증거가 아니다", False),

    ("② 「닫힘」인데 닫힘 근거가 비어 있다",
     HONEST.replace(
         "- **닫힘**: 2026-07-14 · `python3 tools/pack_gate_audit.py` → 위반 0건",
         "- **닫힘**: —"),
     "닫힘 근거가 비어 있다", False),

    ("③ 「닫힘」인데 날짜가 없다 (언제 닫혔는지 모른다)",
     HONEST.replace(
         "- **닫힘**: 2026-07-14 · `python3 tools/pack_gate_audit.py` → 위반 0건",
         "- **닫힘**: `python3 tools/pack_gate_audit.py` → 위반 0건"),
     "날짜가 없다", False),

    ("④ 검증이 **없는 도구**를 가리킨다",
     HONEST.replace(
         "- **검증**: `python3 tools/pack_gate_audit.py`\n- **닫힘**: 2026-07-14",
         "- **검증**: `python3 tools/does_not_exist_audit.py`\n- **닫힘**: 2026-07-14"),
     "없는 것", False),

    ("⑤ 위치가 **유령**을 가리킨다 (없는 파일)",
     HONEST.replace("- **위치**: `tools/backlog_audit.py:1`",
                    "- **위치**: `server-mvt/src/main/java/com/honcheon/mvt/Ghost.java:42`"),
     "파일이 없다", False),

    ("⑥ 위치가 **유령**을 가리킨다 (파일은 있으나 그 줄이 없다)",
     HONEST.replace("- **위치**: `tools/backlog_audit.py:1`",
                    "- **위치**: `tools/backlog_audit.py:999999`"),
     "줄이 없다", False),

    ("⑦ 상태가 정해진 말이 아니다",
     HONEST.replace("- **상태**: 열림", "- **상태**: 대충함"),
     "상태가 다섯 중 하나다", False),

    ("⑧ ID 가 겹친다",
     HONEST.replace("### B-002 ·", "### B-001 ·"),
     "겹침", False),

    ("⑨ 의존이 **없는 항목**을 가리킨다",
     HONEST.replace("- **의존**: —\n- **닫는 조건**: 아무것도 아니다",
                    "- **의존**: B-999\n- **닫는 조건**: 아무것도 아니다"),
     "유령 의존", False),

    ("⑩ 필수 항목이 빠졌다",
     HONEST.replace("- **검증**: `python3 tools/backlog_audit.py`\n", ""),
     "빠짐", False),

    # ★ 2026-07-14 실사고의 지문 — 일괄 치환이 닫힘 근거 45곳을 한 문장으로 덮고
    #   상태 20건을 뒤집었다. 증거의 꼴은 멀쩡해서 ①~⑩ 이 전부 통과했다.
    #   같은 증거가 두 항목에 살면 그것은 복제다 — 한 증거는 한 항목의 것이다.
    ("⑫ 닫힘 근거가 **복제**됐다 (두 항목이 같은 문장 — 일괄 치환의 지문)",
     HONEST + """
### B-003 · 남의 증거로 닫힌 항목
- **상태**: 닫힘
- **분류**: 빚
- **단계**: P1
- **위치**: `tools/game_audit.py:1`
- **의존**: —
- **닫는 조건**: 아무것도 아니다 (시험용)
- **검증**: `python3 tools/pack_gate_audit.py`
- **닫힘**: 2026-07-14 · `python3 tools/pack_gate_audit.py` → 위반 0건
""",
     "복제됐다", False),

    # ★★ 이것이 이 눈의 심장이다 — 장부가 「닫힘」이라 말하는데, 감사를 돌리면 짖는다.
    #    ①~⑩ 은 장부의 *모양*만 보면 잡히지만, 이것은 **감사를 실제로 돌려야만** 잡힌다.
    #    ★ 표본은 남의 감사를 빌리지 않는다 — lint_config 를 빌렸다가 B-001 이 그 감사를
    #    고치자 표본이 조용해져 이 시험이 낡았다 (2026-07-14). always_barks 는 설계상
    #    언제나 「위반 1건」을 말하고, 종료 코드는 일부러 0 이다 (저장소의 함정 재현).
    ("★⑪ 「닫힘」인데 그 감사가 **실제로는 짖는다** (--run 이라야 잡힌다)",
     HONEST.replace(
         "- **검증**: `python3 tools/pack_gate_audit.py`\n"
         "- **닫힘**: 2026-07-14 · `python3 tools/pack_gate_audit.py` → 위반 0건",
         "- **검증**: `python3 tools/selftest_fixtures/always_barks.py`\n"
         "- **닫힘**: 2026-07-14 · `python3 tools/selftest_fixtures/always_barks.py` → 위반 0건"),
     "감사가 짖는다", True),
]


def run(path, with_run):
    cmd = [sys.executable, AUDIT, os.path.relpath(path, ROOT)]
    if with_run:
        cmd.append("--run")
    p = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=600)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def main():
    print()
    print("═" * 74)
    print("  청구서 감사의 자기 시험 — 눈이 정말로 보는가")
    print("═" * 74)
    print()

    tmpdir = tempfile.mkdtemp(prefix="backlog_selftest_", dir=os.path.join(ROOT, "tools"))
    honest_path = os.path.join(tmpdir, "honest.md")
    missed = []

    try:
        # ── ⓪ 먼저: 정직한 장부에는 짖지 않는가 (거짓 양성이 없는가) ──────────
        with open(honest_path, "w", encoding="utf-8") as f:
            f.write(HONEST)
        code, out = run(honest_path, with_run=True)
        if code == 0:
            print("  ✅ ⓪ 정직한 장부에는 짖지 않는다 (거짓 양성 없음)")
        else:
            print("  ❌ ⓪ **정직한 장부에 짖었다** — 눈이 과민하다 (거짓 양성)")
            bad = [l for l in out.splitlines() if "❌" in l]
            for l in bad[:5]:
                print("        " + l.strip())
            missed.append("⓪ 거짓 양성")

        # ── 상처를 하나씩 심는다 ──────────────────────────────────────────────
        for name, wounded, needle, needs_run in WOUNDS:
            path = os.path.join(tmpdir, "wounded.md")
            with open(path, "w", encoding="utf-8") as f:
                f.write(wounded)
            if wounded == HONEST:
                print(f"  ❌ {name} — **상처를 심지 못했다** (치환이 안 먹었다)")
                missed.append(name)
                continue

            code, out = run(path, with_run=needs_run)
            caught = code != 0 and needle in out
            if caught:
                tag = " (--run 이라야 잡힌다)" if needs_run else ""
                print(f"  ✅ {name} — 잡았다{tag}")
            else:
                print(f"  ❌ {name} — **놓쳤다** (종료 코드 {code}, “{needle}” 을 말하지 않았다)")
                missed.append(name)

            # ★⑪ 은 한 가지를 더 시험한다: --run 없이는 **못 잡아야** 한다.
            #    (그래야 --run 이 진짜 일을 한다는 뜻이다 — 모양만 봐서는 알 수 없는 거짓말이다)
            if needs_run:
                code2, _ = run(path, with_run=False)
                if code2 == 0:
                    print("        ↳ ✅ 그리고 `--run` 없이는 못 잡는다 — "
                          "**모양만 봐서는 알 수 없는 거짓말**이다. 그래서 --run 이 있다")
                else:
                    print("        ↳ ⚠️ `--run` 없이도 짖었다 — 다른 이유로 잡힌 것일 수 있다")

    finally:
        for fn in os.listdir(tmpdir):
            os.unlink(os.path.join(tmpdir, fn))
        os.rmdir(tmpdir)

    print()
    print("─" * 74)
    if missed:
        print(f"  총평: ❌ 눈이 {len(missed)}건을 놓쳤다 — **이 감사를 믿을 수 없다**")
        for m in missed:
            print(f"     · {m}")
    else:
        print(f"  총평: ✅ 심은 거짓말 {len(WOUNDS)}건을 전부 잡았다 · 정직한 장부에는 짖지 않았다")
        print("        — 눈은 뜨여 있다")
    print("─" * 74)
    print()
    return 1 if missed else 0


if __name__ == "__main__":
    sys.exit(main())
