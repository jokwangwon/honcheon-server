#!/usr/bin/env python3
"""PG-002 저장소 포트가 구체 Db 의존을 실제로 줄였는지 확인한다."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BOT = Path("server-bot/src/main/java/com/honcheon/bot")


def strip_java_comments(source: str) -> str:
    return re.sub(r"/\*.*?\*/|//[^\r\n]*", "", source, flags=re.DOTALL)


def checks(root: Path = ROOT) -> dict[str, bool]:
    bot = root / BOT
    read = lambda name: (bot / name).read_text(encoding="utf-8") if (bot / name).exists() else ""
    reset = strip_java_comments(read("Reset.java"))
    main = strip_java_comments(read("HoncheonBot.java"))
    bridge = strip_java_comments(read("Bridge.java"))
    game = strip_java_comments(read("GameListener.java"))
    db = strip_java_comments(read("Db.java"))
    reset_port = read("ResetStore.java")
    meta_port = read("WorldMetaReader.java")
    bridge_port = read("BridgeStore.java")
    link_request = read("LinkRequest.java")
    blood_debt = read("BloodDebtEntry.java")
    game_port = read("GameStore.java")
    game_ports = {
        "GameCharacterStore": read("GameCharacterStore.java"),
        "HouseStore": read("HouseStore.java"),
        "EventStore": read("EventStore.java"),
        "PoliticsStore": read("PoliticsStore.java"),
        "IdentityStore": read("IdentityStore.java"),
        "WorldStore": read("WorldStore.java"),
    }
    game_dtos = {
        "HouseEntry": read("HouseEntry.java"),
        "MyeongbunIssue": read("MyeongbunIssue.java"),
        "RumorArrival": read("RumorArrival.java"),
    }
    direct_consumers = []
    for path in sorted(bot.glob("*.java")):
        if path.name != "Db.java" and re.search(r"\bDb\b", strip_java_comments(
                path.read_text(encoding="utf-8"))):
            direct_consumers.append(path.name)
    return {
        "reset_port_exists": "interface ResetStore" in reset_port,
        "reset_uses_port": "private final ResetStore" in reset and "Reset(ResetStore" in reset,
        "reset_has_no_concrete_db": re.search(r"\bDb\b", reset) is None,
        "meta_reader_exists": "interface WorldMetaReader" in meta_port,
        "scheduler_uses_reader": "scheduleMidnight(JDA jda, WorldMetaReader" in main,
        "bridge_port_exists": "interface BridgeStore" in bridge_port,
        "bridge_uses_port": (
            "private final BridgeStore" in bridge and "Bridge(Rules rules, BridgeStore" in bridge
        ),
        "bridge_has_no_concrete_db": re.search(r"\bDb\b", bridge) is None,
        "bridge_dtos_are_independent": (
            "record LinkRequest" in link_request and "record BloodDebtEntry" in blood_debt
            and "Db.LinkRequest" not in bridge and "Db.Debt" not in bridge
        ),
        "game_ports_exist": (
            all(f"interface {name}" in source for name, source in game_ports.items())
            and "interface GameStore extends" in game_port
            and all(name in game_port for name in game_ports)
        ),
        "game_uses_composed_port": (
            "private final GameStore" in game and "GameListener(Rules rules, GameStore" in game
        ),
        "game_has_no_concrete_db": re.search(r"\bDb\b", game) is None,
        "game_dtos_are_independent": (
            all(f"record {name}" in source for name, source in game_dtos.items())
            and not any(f"Db.{name}" in game for name in ("House", "Issue", "Arrival"))
        ),
        "composition_root_owns_db": (
            direct_consumers == ["HoncheonBot.java"]
            and ("new Db(" in main or "Db.open(" in main)
        ),
        "db_implements_ports": (
            re.search(r"implements[^\{]*\bResetStore\b", db) is not None
            and re.search(r"implements[^\{]*\bWorldMetaReader\b", db) is not None
            and re.search(r"implements[^\{]*\bBridgeStore\b", db) is not None
            and re.search(r"implements[^\{]*\bGameStore\b", db) is not None
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    result = checks(args.root.resolve())
    labels = {
        "reset_port_exists": "초기화 업무 포트가 있다",
        "reset_uses_port": "Reset이 ResetStore만 사용한다",
        "reset_has_no_concrete_db": "Reset에 구체 Db 의존이 없다",
        "meta_reader_exists": "세계 메타 읽기 포트가 있다",
        "scheduler_uses_reader": "자정 스케줄러가 읽기 포트를 사용한다",
        "bridge_port_exists": "브리지 업무 포트가 있다",
        "bridge_uses_port": "Bridge가 BridgeStore만 사용한다",
        "bridge_has_no_concrete_db": "Bridge에 구체 Db 의존이 없다",
        "bridge_dtos_are_independent": "접합·혈채 DTO가 Db 밖에 있다",
        "game_ports_exist": "게임 원장이 업무별 포트로 나뉘어 있다",
        "game_uses_composed_port": "GameListener가 조합 포트만 사용한다",
        "game_has_no_concrete_db": "GameListener에 구체 Db 의존이 없다",
        "game_dtos_are_independent": "가문·명분·소문 DTO가 Db 밖에 있다",
        "composition_root_owns_db": "composition root만 구체 Db를 소유한다",
        "db_implements_ports": "현재 SQLite Db가 모든 포트를 구현한다",
    }
    print("PG-002 저장소 포트 감사")
    for key, ok in result.items():
        print(("✓ " if ok else "✗ ") + labels[key])
    return 0 if all(result.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
