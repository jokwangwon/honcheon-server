#!/usr/bin/env python3
"""최소 RCON 클라이언트 — 검기 자동검증 하네스 전용 (의존성 없음).

★ 테스트 서버(127.0.0.1:25576) 전용이다. 라이브 RCON(25575)에 쓰지 마라.
사용: python3 scripts/kigi_rcon.py "명령" ["명령" ...]
"""
import socket
import struct
import sys

HOST = "127.0.0.1"
PORT = 25576
PASSWORD = "kigitest"

SERVERDATA_AUTH = 3
SERVERDATA_EXECCOMMAND = 2


class Rcon:
    def __init__(self, host=HOST, port=PORT, password=PASSWORD, timeout=15.0):
        if port == 25575:
            raise SystemExit("거부: 25575 는 라이브 RCON 이다. 테스트는 25576 이다.")
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self._id = 0
        if self._send(SERVERDATA_AUTH, password) is None:
            raise SystemExit("RCON 인증 실패")

    def _send(self, kind, body):
        self._id += 1
        req = self._id
        payload = struct.pack("<ii", req, kind) + body.encode("utf-8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(payload)) + payload)
        out = []
        while True:
            raw = self._recv_packet()
            if raw is None:
                return None
            rid, _kind, text = raw
            if rid == -1:
                return None
            out.append(text)
            # 더 읽을 것이 없으면 끝 (짧은 응답은 한 패킷)
            self.sock.settimeout(0.35)
            try:
                self.sock.recv(0)
            except Exception:
                pass
            finally:
                self.sock.settimeout(15.0)
            return "".join(out)

    def _recv_packet(self):
        head = self._read(4)
        if not head:
            return None
        (length,) = struct.unpack("<i", head)
        data = self._read(length)
        rid, kind = struct.unpack("<ii", data[:8])
        return rid, kind, data[8:-2].decode("utf-8", "replace")

    def _read(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                return None
            buf += chunk
        return buf

    def cmd(self, command):
        return self._send(SERVERDATA_EXECCOMMAND, command) or ""

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass


def main():
    r = Rcon()
    for c in sys.argv[1:]:
        print(f"> {c}")
        print(r.cmd(c))
    r.close()


if __name__ == "__main__":
    main()
