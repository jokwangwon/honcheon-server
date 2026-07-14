#!/usr/bin/env python3
"""persistence_port_audit가 구체 Db 재결합을 놓치지 않는지 시험한다."""

from __future__ import annotations

import importlib.util
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location(
    "persistence_port_audit", ROOT / "tools/persistence_port_audit.py")
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


def write_fixture(root: Path, concrete_reset: bool = False, concrete_bridge: bool = False,
                  concrete_game: bool = False) -> None:
    bot = root / "server-bot/src/main/java/com/honcheon/bot"
    bot.mkdir(parents=True)
    reset_store = "Db" if concrete_reset else "ResetStore"
    bridge_store = "Db" if concrete_bridge else "BridgeStore"
    game_store = "Db" if concrete_game else "GameStore"
    (bot / "ResetStore.java").write_text("interface ResetStore {}", encoding="utf-8")
    (bot / "WorldMetaReader.java").write_text("interface WorldMetaReader {}", encoding="utf-8")
    (bot / "BridgeStore.java").write_text("interface BridgeStore {}", encoding="utf-8")
    game_ports = ("GameCharacterStore", "HouseStore", "EventStore", "PoliticsStore",
                  "IdentityStore", "WorldStore")
    for name in game_ports:
        (bot / f"{name}.java").write_text(f"interface {name} {{}}", encoding="utf-8")
    (bot / "GameStore.java").write_text(
        "interface GameStore extends " + ", ".join(game_ports) + " {}", encoding="utf-8")
    for name in ("LinkRequest", "BloodDebtEntry", "HouseEntry", "MyeongbunIssue",
                 "RumorArrival"):
        (bot / f"{name}.java").write_text(f"record {name}() {{}}", encoding="utf-8")
    (bot / "Reset.java").write_text(
        f"class Reset {{ private final {reset_store} db; "
        f"Reset({reset_store} db) {{ this.db = db; }} }}",
        encoding="utf-8",
    )
    (bot / "Bridge.java").write_text(
        f"class Bridge {{ private final {bridge_store} db; "
        f"Bridge(Rules rules, {bridge_store} db) {{ this.db = db; }} }}",
        encoding="utf-8",
    )
    (bot / "GameListener.java").write_text(
        f"class GameListener {{ private final {game_store} db; "
        f"GameListener(Rules rules, {game_store} db) {{ this.db = db; }} }}",
        encoding="utf-8",
    )
    (bot / "HoncheonBot.java").write_text(
        "class HoncheonBot { Db db = new Db(); "
        "void scheduleMidnight(JDA jda, WorldMetaReader db, Object listener) {} }",
        encoding="utf-8",
    )
    (bot / "Db.java").write_text(
        "class Db implements ResetStore, WorldMetaReader, BridgeStore, GameStore {}",
        encoding="utf-8")


def main() -> int:
    cases = []
    actual = AUDIT.checks(ROOT)
    cases.append(("실제 저장소 포트 배선을 본다", all(actual.values())))

    with tempfile.TemporaryDirectory(prefix="honcheon-port-valid-") as td:
        root = Path(td)
        write_fixture(root)
        cases.append(("정상 포트 배선을 통과시킨다", all(AUDIT.checks(root).values())))

    with tempfile.TemporaryDirectory(prefix="honcheon-port-reset-broken-") as td:
        root = Path(td)
        write_fixture(root, concrete_reset=True)
        broken = AUDIT.checks(root)
        cases.append(("Reset이 Db로 되돌아가면 잡는다",
                      not broken["reset_uses_port"] and not broken["reset_has_no_concrete_db"]))

    with tempfile.TemporaryDirectory(prefix="honcheon-port-bridge-broken-") as td:
        root = Path(td)
        write_fixture(root, concrete_bridge=True)
        broken = AUDIT.checks(root)
        cases.append(("Bridge가 Db로 되돌아가면 잡는다",
                      not broken["bridge_uses_port"]
                      and not broken["bridge_has_no_concrete_db"]))

    with tempfile.TemporaryDirectory(prefix="honcheon-port-game-broken-") as td:
        root = Path(td)
        write_fixture(root, concrete_game=True)
        broken = AUDIT.checks(root)
        cases.append(("GameListener가 Db로 되돌아가면 잡는다",
                      not broken["game_uses_composed_port"]
                      and not broken["game_has_no_concrete_db"]
                      and not broken["composition_root_owns_db"]))

    ok = True
    print("══ persistence_port_audit 눈을 시험한다 ══")
    for name, passed in cases:
        print(("✓ " if passed else "✗ ") + name)
        ok &= passed
    print("── " + ("✓ 저장소 포트 눈이 전부 잡았다" if ok else "✗ 포트 눈이 놓쳤다"))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
