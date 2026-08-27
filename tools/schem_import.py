#!/usr/bin/env python3
"""코퍼스 수입기 — 커뮤니티 schematic 을 덤프 정규형(TSV)으로 (SYSTEM_REVIEW ⑥ · E-1).

  python3 tools/schem_import.py run/corpus/raw/이름/파일.schem
  python3 tools/schem_import.py 파일.litematic --out run/corpus/tsv/이름.tsv
  python3 tools/schem_import.py --selftest

지원: Sponge `.schem` v1·v2·v3 · `.litematic` (Litematica).
출력: `run/mvt-test/dump/*.tsv` 와 같은 꼴 (x⇥y⇥z⇥data · 블록 상태 포함 · air 도 적는다 —
air 는 개구(빈칸) 부재라 버리면 쌓임이 파괴된다). `stack_mine.py` 가 바로 먹는다.

★옛 `.schematic`(1.12 이전 · 수치 ID)은 지원 밖 — 그렇게 말하고 죽는다.
★모르는 것은 조용히 안 넘어간다 — 팔레트의 비-`minecraft:` 이름(모드 블록)을 세어서
  크게 보고한다 (자홍 문화). 모드 팔레트 빌드는 코퍼스 기준 2 탈락이다.
"""

from __future__ import annotations

import argparse
import collections
import gzip
import math
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "run" / "corpus" / "tsv"


# ── NBT 읽기 (빅엔디언) ────────────────────────────────────────────────────

class _R:
    def __init__(self, b: bytes):
        self.b, self.o = b, 0

    def take(self, n: int) -> bytes:
        v = self.b[self.o : self.o + n]
        if len(v) < n:
            raise ValueError("NBT 가 중간에 끝났다")
        self.o += n
        return v

    def num(self, fmt: str):
        v = struct.unpack_from(fmt, self.b, self.o)[0]
        self.o += struct.calcsize(fmt)
        return v

    def s(self) -> str:
        return self.take(self.num(">H")).decode("utf-8", "replace")

    def payload(self, t: int):
        if t == 1:
            return self.num(">b")
        if t == 2:
            return self.num(">h")
        if t == 3:
            return self.num(">i")
        if t == 4:
            return self.num(">q")
        if t == 5:
            return self.num(">f")
        if t == 6:
            return self.num(">d")
        if t == 7:  # ByteArray — varint 해독을 위해 무부호 bytes 로 둔다
            return self.take(self.num(">i"))
        if t == 8:
            return self.s()
        if t == 9:
            et, n = self.num(">b"), self.num(">i")
            return [self.payload(et) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                ct = self.num(">b")
                if ct == 0:
                    return d
                # ★이름을 먼저 읽는다 — d[self.s()] = self.payload(ct) 로 적으면
                #   파이썬이 우변을 먼저 평가해 payload 가 이름보다 앞서 읽힌다
                name = self.s()
                d[name] = self.payload(ct)
        if t == 11:
            n = self.num(">i")
            return [self.num(">i") for _ in range(n)]
        if t == 12:
            n = self.num(">i")
            return [self.num(">q") for _ in range(n)]
        raise ValueError(f"모르는 NBT 태그 {t}")


def load_nbt(raw: bytes) -> dict:
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    r = _R(raw)
    t = r.num(">b")
    if t != 10:
        raise ValueError(f"뿌리가 컴파운드가 아니다 (태그 {t})")
    r.s()  # 뿌리 이름 — 안 쓴다
    return r.payload(10)


# ── Sponge .schem ──────────────────────────────────────────────────────────

def _varints(b: bytes):
    v = shift = 0
    for byte in b:
        v |= (byte & 0x7F) << shift
        if byte & 0x80:
            shift += 7
        else:
            yield v
            v = shift = 0


def import_schem(root: dict) -> dict[tuple[int, int, int], str]:
    doc = root.get("Schematic", root)  # v3 은 한 겹 안에 있다
    if "Blocks" in doc and isinstance(doc["Blocks"], dict):  # v3
        pal, data = doc["Blocks"]["Palette"], doc["Blocks"]["Data"]
    elif "Palette" in doc:  # v1·v2
        pal, data = doc["Palette"], doc["BlockData"]
    elif "Blocks" in doc:  # 옛 .schematic — 수치 ID
        sys.exit("옛 .schematic(1.12 이전 · 수치 ID)이다 — 지원 밖. WorldEdit 등으로 "
                 ".schem 으로 변환해서 다오.")
    else:
        sys.exit("스펀지 스키마가 아니다 — Palette 가 없다")
    w = doc["Width"] & 0xFFFF
    hh = doc["Height"] & 0xFFFF
    ln = doc["Length"] & 0xFFFF
    inv = {v: k for k, v in pal.items()}
    grid: dict[tuple[int, int, int], str] = {}
    for i, pid in enumerate(_varints(bytes(data))):
        x, z, y = i % w, (i // w) % ln, i // (w * ln)
        grid[(x, y, z)] = inv[pid]
    expect = w * hh * ln
    if len(grid) != expect:
        sys.exit(f"칸 수가 어긋난다: 해독 {len(grid)} vs 치수 {expect} — 파일이 상했다")
    return grid


# ── .litematic ─────────────────────────────────────────────────────────────

def _state_str(entry: dict) -> str:
    name = entry["Name"]
    props = entry.get("Properties") or {}
    if props:
        inner = ",".join(f"{k}={v}" for k, v in props.items())
        return f"{name}[{inner}]"
    return name


def import_litematic(root: dict) -> dict[tuple[int, int, int], str]:
    regions = root["Regions"]
    # 여러 리전을 전역 최소 모서리 기준으로 정규화한다
    corners = {}
    for name, r in regions.items():
        pos, size = r["Position"], r["Size"]
        corners[name] = tuple(
            pos[a] + (size[a] + 1 if size[a] < 0 else 0) for a in ("x", "y", "z")
        )
    gx = min(c[0] for c in corners.values())
    gy = min(c[1] for c in corners.values())
    gz = min(c[2] for c in corners.values())

    grid: dict[tuple[int, int, int], str] = {}
    for name, r in regions.items():
        ax, ay, az = (abs(r["Size"][a]) for a in ("x", "y", "z"))
        pal = [_state_str(e) for e in r["BlockStatePalette"]]
        bits = max(2, (len(pal) - 1).bit_length())
        mask = (1 << bits) - 1
        longs = [v & 0xFFFFFFFFFFFFFFFF for v in r["BlockStates"]]
        ox, oy, oz = (corners[name][0] - gx, corners[name][1] - gy, corners[name][2] - gz)
        for i in range(ax * ay * az):
            bit = i * bits
            wi, off = bit >> 6, bit & 63
            v = longs[wi] >> off
            if off + bits > 64:  # ★리터매티카는 롱 경계를 걸친다 (1.16+ 청크와 다르다)
                v |= longs[wi + 1] << (64 - off)
            pid = v & mask
            x, z, y = i % ax, (i // ax) % az, i // (ax * az)
            grid[(ox + x, oy + y, oz + z)] = pal[pid]
    return grid


# ── 출력 · 보고 ────────────────────────────────────────────────────────────

def write_tsv(grid: dict, out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        f.write("x\ty\tz\tdata\n")
        for (x, y, z) in sorted(grid):
            f.write(f"{x}\t{y}\t{z}\t{grid[(x, y, z)]}\n")


def report(grid: dict, label: str) -> None:
    names = collections.Counter(d.split("[")[0] for d in grid.values())
    alien = {n: c for n, c in names.items() if not n.startswith("minecraft:")}
    xs = [k[0] for k in grid]; ys = [k[1] for k in grid]; zs = [k[2] for k in grid]
    print(f"[{label}] {len(grid)}칸 · 상자 {max(xs)+1}×{max(ys)+1}×{max(zs)+1}"
          f" · 재료 {len(names)}종")
    if alien:
        print(f"  ★★모드 블록 {len(alien)}종 {sum(alien.values())}칸 — 코퍼스 기준 2 탈락 후보:")
        for n, c in sorted(alien.items(), key=lambda kv: -kv[1])[:10]:
            print(f"    {c:6d}  {n}")
    else:
        print("  전부 minecraft: — 바닐라 팔레트다")


def import_file(path: Path) -> dict:
    root = load_nbt(path.read_bytes())
    if "Regions" in root:
        return import_litematic(root)
    return import_schem(root)


# ── 눈 (selftest) — 합성 파일 왕복 ────────────────────────────────────────

def _nb(t, name, pay):  # 이름 있는 태그
    return bytes([t]) + struct.pack(">H", len(name.encode())) + name.encode() + pay


def _pc(**kw):  # 컴파운드 payload — 값은 (태그, payload bytes)
    out = b""
    for k, (t, p) in kw.items():
        out += _nb(t, k, p)
    return out + b"\x00"


def _pi(v):  # int payload
    return struct.pack(">i", v)


def _ps(v):
    return struct.pack(">H", len(v.encode())) + v.encode()


def _pack_longs(vals: list[int], bits: int) -> list[int]:
    buf = 0
    for i, v in enumerate(vals):
        buf |= (v & ((1 << bits) - 1)) << (i * bits)
    longs = []
    total = (len(vals) * bits + 63) // 64
    for i in range(total):
        chunk = (buf >> (i * 64)) & 0xFFFFFFFFFFFFFFFF
        if chunk >= 1 << 63:
            chunk -= 1 << 64
        longs.append(chunk)
    return longs


def selftest() -> int:
    fails, ran = [], [0]

    def eye(name, cond):
        ran[0] += 1
        print(("  ✓ " if cond else "  ✗ ") + name)
        if not cond:
            fails.append(name)

    # ① 스펀지 v2 — 2×2×1 · varint · gzip 까지 왕복
    pal = _pc(**{"minecraft:air": (3, _pi(0)), "minecraft:stone": (3, _pi(1))})
    body = _pc(
        Version=(3, _pi(2)),
        Width=(2, struct.pack(">h", 2)), Height=(2, struct.pack(">h", 2)),
        Length=(2, struct.pack(">h", 1)),
        Palette=(10, pal), BlockData=(7, struct.pack(">i", 4) + bytes([0, 1, 1, 0])),
    )
    raw = _nb(10, "Schematic", body)
    grid = import_schem(load_nbt(gzip.compress(raw)))
    eye("v2: gzip·varint·자리 (x+z·W+y·W·L)",
        grid == {(0, 0, 0): "minecraft:air", (1, 0, 0): "minecraft:stone",
                 (0, 1, 0): "minecraft:stone", (1, 1, 0): "minecraft:air"})

    # ② 스펀지 v3 — Blocks 안에 Palette·Data 가 있다
    body3 = _pc(
        Version=(3, _pi(3)),
        Width=(2, struct.pack(">h", 1)), Height=(2, struct.pack(">h", 1)),
        Length=(2, struct.pack(">h", 2)),
        Blocks=(10, _pc(Palette=(10, pal),
                        Data=(7, struct.pack(">i", 2) + bytes([1, 0])))),
    )
    raw3 = _nb(10, "", _pc(Schematic=(10, body3)))
    grid3 = import_schem(load_nbt(raw3))
    eye("v3: 한 겹 안 · Blocks.Palette",
        grid3 == {(0, 0, 0): "minecraft:stone", (0, 0, 1): "minecraft:air"})

    # ③ varint 두 바이트 — 팔레트 id 200
    vals = list(_varints(bytes([0xC8, 0x01, 0x05])))
    eye("varint 다바이트", vals == [200, 5])

    # ④ 리터매틱 — 팔레트 5종 → 3비트 · 27칸 = 81비트 · ★롱 경계 걸침
    ids = [i % 5 for i in range(27)]
    palL = [{"Name": "minecraft:air"}, {"Name": "minecraft:stone"},
            {"Name": "minecraft:oak_log", "Properties": {"axis": "y"}},
            {"Name": "minecraft:dirt"}, {"Name": "minecraft:sand"}]
    reg = {"Position": {"x": 0, "y": 0, "z": 0}, "Size": {"x": 3, "y": 3, "z": 3},
           "BlockStatePalette": palL, "BlockStates": _pack_longs(ids, 3)}
    gridL = import_litematic({"Regions": {"main": reg}})
    ok = all(gridL[(i % 3, i // 9, (i // 3) % 3)]
             == _state_str(palL[ids[i]]) for i in range(27))
    eye("litematic: 3비트·경계 걸침·상태 문자열", ok and len(gridL) == 27)

    # ⑤ 음수 Size 리전 — 모서리 정규화
    reg2 = {"Position": {"x": 5, "y": 0, "z": 5}, "Size": {"x": -2, "y": 1, "z": -2},
            "BlockStatePalette": [{"Name": "minecraft:air"}, {"Name": "minecraft:stone"}],
            "BlockStates": _pack_longs([1, 1, 1, 1], 2)}
    gridN = import_litematic({"Regions": {"m": reg2}})
    eye("음수 Size 정규화 (원점으로)",
        set(gridN) == {(0, 0, 0), (1, 0, 0), (0, 0, 1), (1, 0, 1)})

    # ⑥ 옛 .schematic 은 말하고 죽는다
    old = {"Blocks": b"\x01\x01", "Data": b"\x00\x00", "Width": 2, "Height": 1, "Length": 1}
    died = False
    try:
        import_schem(old)
    except SystemExit as e:
        died = "옛 .schematic" in str(e)
    eye("옛 포맷 거부 (조용히 안 삼킨다)", died)

    # ⑦ 모드 블록 보고 — 비-minecraft 이름이 세어진다
    import io as _io
    cap = _io.StringIO()
    stdout, sys.stdout = sys.stdout, cap
    try:
        report({(0, 0, 0): "conquest:beam", (1, 0, 0): "minecraft:stone"}, "눈")
    finally:
        sys.stdout = stdout
    eye("모드 블록이 크게 보고된다", "모드 블록 1종 1칸" in cap.getvalue())

    print(f"\n눈 {ran[0]}종 · 실패 {len(fails)}")
    return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("files", nargs="*", help=".schem / .litematic")
    ap.add_argument("--out", help="출력 tsv (파일 하나일 때만)")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if not args.files:
        ap.error("파일을 다오 (또는 --selftest)")
    if args.out and len(args.files) > 1:
        ap.error("--out 은 파일 하나일 때만")

    for p in map(Path, args.files):
        grid = import_file(p)
        out = Path(args.out) if args.out else OUT_DIR / f"{p.stem}.tsv"
        write_tsv(grid, out)
        report(grid, p.name)
        print(f"  → {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
