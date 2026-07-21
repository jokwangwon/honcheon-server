#!/usr/bin/env python3
"""라이브(25575) RCON — 조율자 전용 정본 헬퍼.

★ kigi_rcon.py 는 25575 를 **거부한다** (하네스가 라이브를 건드리지 못하게 — 그 벽은 옳다).
  조율자의 라이브 조작은 이 파일 하나로만 한다. 인라인 소켓 코드를 매번 새로 짜다
  두 번 사고가 났다 (2026-07-21: 인증 2패킷 미처리 → stop 미전달 → 중복 기동 시도).

쓰는 법:
    from live_rcon import LiveRcon
    with LiveRcon() as r:
        print(r.cmd("list"))
"""
from __future__ import annotations

import re
import socket
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
PROPS = ROOT / "run" / "mvt" / "server.properties"

SERVERDATA_AUTH = 3
SERVERDATA_EXECCOMMAND = 2


def _password() -> str:
    for line in PROPS.read_text(encoding="utf-8").splitlines():
        if line.startswith("rcon.password="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("라이브 rcon.password 를 못 찾았다 (run/mvt/server.properties)")


class LiveRcon:
    """kigi_rcon.Rcon 과 같은 패킷 규약 — 인증의 빈 응답·-1 거부까지 동일하게 다룬다."""

    def __init__(self, timeout: float = 15.0):
        self.sock = socket.create_connection(("127.0.0.1", 25575), timeout=timeout)
        self._id = 0
        if self._send(SERVERDATA_AUTH, _password()) is None:
            raise SystemExit("라이브 RCON 인증 실패")

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def _read(self, n: int) -> bytes | None:
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                return None
            buf += chunk
        return buf

    def _recv_packet(self):
        head = self._read(4)
        if not head:
            return None
        (length,) = struct.unpack("<i", head)
        data = self._read(length)
        if data is None:
            return None
        rid, kind = struct.unpack("<ii", data[:8])
        return rid, kind, data[8:-2].decode("utf-8", "replace")

    def _send(self, kind: int, body: str):
        self._id += 1
        payload = struct.pack("<ii", self._id, kind) + body.encode("utf-8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(payload)) + payload)
        while True:
            raw = self._recv_packet()
            if raw is None:
                return None
            rid, _k, text = raw
            if rid == -1:
                return None                      # 인증 거부
            if kind == SERVERDATA_AUTH and rid != self._id:
                continue                         # 인증 앞의 빈 응답 패킷 — 삼킨다
            return text

    def cmd(self, command: str) -> str:
        out = self._send(SERVERDATA_EXECCOMMAND, command)
        return re.sub(r"§.", "", out or "")

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass
