#!/usr/bin/env python3
"""bridge_audit의 domain 사슬과 종료 코드 눈을 시험한다."""

import importlib.util
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("bridge_audit", ROOT / "tools" / "bridge_audit.py")
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


def main():
    cases = []
    live = AUDIT.code_links()["지역이 회복한다"][0]
    cases.append(("GameListener → domain → core 회복 사슬을 본다", live))

    original = AUDIT.DOMAIN_DIR
    with tempfile.TemporaryDirectory(prefix="honcheon-bridge-") as td:
        AUDIT.DOMAIN_DIR = Path(td)
        broken = AUDIT.code_links()["지역이 회복한다"][0]
    AUDIT.DOMAIN_DIR = original
    cases.append(("domain 사슬을 끊으면 짖는다", not broken))

    kinds = {"bandit_camp_cleared", "bandit_boss_succeeded"}
    handlers = AUDIT.bot_handlers(kinds)
    cases.append(("도적 소탕·승계 수신부를 모두 본다", all(handlers.values())))

    original_bot = AUDIT.BOT_DIR
    with tempfile.TemporaryDirectory(prefix="honcheon-bridge-bot-") as td:
        fake = Path(td)
        (fake / "Bridge.java").write_text(
            'class Bridge { void apply(String kind) { switch (kind) { default -> {} } } }',
            encoding="utf-8",
        )
        AUDIT.BOT_DIR = fake
        missing = AUDIT.bot_handlers(kinds)
    AUDIT.BOT_DIR = original_bot
    cases.append(("도적 수신부를 제거하면 짖는다", not any(missing.values())))

    atomic, _ = AUDIT.bridge_delivery_atomic()
    cases.append(("inbox·세계 변경·커서 원자성을 본다", atomic))

    with tempfile.TemporaryDirectory(prefix="honcheon-bridge-atomic-") as td:
        fake = Path(td)
        (fake / "Bridge.java").write_text(
            "apply(line, checkpoint); db.applyBridgeEvent(id, kind, cursorKey, checkpoint);",
            encoding="utf-8",
        )
        (fake / "Db.java").write_text(
            "setMeta(cursorKey, checkpoint); conn.commit();",
            encoding="utf-8",
        )
        AUDIT.BOT_DIR = fake
        broken_atomic, _ = AUDIT.bridge_delivery_atomic()
    AUDIT.BOT_DIR = original_bot
    cases.append(("rollback을 제거하면 원자성 위반으로 짖는다", not broken_atomic))

    cases.append(("위반 verdict는 종료 코드 1", AUDIT.result_code([f"{AUDIT.NO} 끊김"]) == 1))
    cases.append(("경고만 있으면 종료 코드 0", AUDIT.result_code([f"{AUDIT.WARN} 미통과"]) == 0))

    ok = True
    print("══ bridge_audit 눈을 시험한다 ══")
    for name, caught in cases:
        print(("✓ " if caught else "✗ ") + name)
        ok &= caught
    print("── " + ("✓ 브리지 눈이 전부 잡았다" if ok else "✗ 눈이 놓쳤다"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

