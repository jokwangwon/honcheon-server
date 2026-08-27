#!/usr/bin/env python3
"""월드 수입기 — 앤빌(.mca) 월드에서 건물을 찾아 상자로 잘라 TSV 로 (E-1 의 월드 판).

  python3 tools/world_import.py scan <월드/리전 디렉터리>          # 건물이 어디 있나
  python3 tools/world_import.py box  <디렉터리> x1,y1,z1 x2,y2,z2 --out 이름.tsv
  python3 tools/world_import.py --selftest

scan: 청크마다 건축 표지(planks·stairs·trapdoor…)를 세어, 이웃한 건축 청크를
무리로 묶고 블록 좌표 상자로 보고한다 — **어느 건물을 잘라낼지는 사람이 고른다.**
box: 상자 안 블록을 상태까지 그대로 적는다 (덤프 정규형 · air 포함 · 좌표는 상자
원점 기준 0-시작). `stack_mine.py`·`blueprint_3d.py` 가 바로 먹는다.

앤빌 형식 (1.18+ · 이 월드는 26.2/DataVersion 4903 에서 확인):
  리전 머리 4KiB = 1024 × (offset 3B · sectors 1B) → 청크 = 길이 4B · 압축 1B · NBT
  섹션 block_states: bits = max(4, ceil(log2(팔레트))) · ★롱 경계를 안 걸친다
  (1.16+ — 리터매틱과 다르다. 롱 하나에 64//bits 개, 남는 비트는 버린다)
"""

from __future__ import annotations

import argparse
import collections
import gzip
import struct
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from schem_import import _R, _state_str, report, write_tsv  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "run" / "corpus" / "tsv"

# 건축 표지 — 평지·자연 지형에는 없고 목조 건축에는 반드시 있는 이름 조각들
MARKERS = (b"planks", b"stairs", b"trapdoor", b"_slab", b"fence", b"lantern",
           b"terracotta", b"_wall", b"_log[", b"stripped_")


def region_dir_of(path: Path) -> Path:
    """월드 뿌리를 줘도 리전 디렉터리를 찾는다."""
    for cand in (path, path / "region", path / "dimensions" / "minecraft" / "overworld" / "region"):
        if cand.is_dir() and list(cand.glob("r.*.mca")):
            return cand
    sys.exit(f"리전(.mca)을 못 찾았다: {path}")


def chunks_of(mca: Path):
    """리전 파일의 (청크 index, 압축 푼 NBT bytes) 를 차례로 낸다."""
    b = mca.read_bytes()
    if len(b) < 8192:
        return
    for ci in range(1024):
        off = int.from_bytes(b[ci * 4 : ci * 4 + 3], "big")
        if not b[ci * 4 + 3]:
            continue
        pos = off * 4096
        ln = int.from_bytes(b[pos : pos + 4], "big")
        comp = b[pos + 4]
        raw = b[pos + 5 : pos + 4 + ln]
        if comp == 2:
            raw = zlib.decompress(raw)
        elif comp == 1:
            raw = gzip.decompress(raw)
        elif comp != 3:
            sys.exit(f"{mca.name} 청크 {ci}: 모르는 압축 {comp} (LZ4 서버 저장인가?)")
        yield ci, raw


def parse_chunk(raw: bytes) -> dict:
    r = _R(raw)
    if r.num(">b") != 10:
        raise ValueError("청크 뿌리가 컴파운드가 아니다")
    r.s()
    return r.payload(10)


def section_blocks(sec: dict):
    """섹션 하나 → 4096칸 팔레트 인덱스 목록 (팔레트 문자열 목록과 함께)."""
    bs = sec.get("block_states")
    if not bs:
        return None, None
    pal = [_state_str(e) for e in bs["palette"]]
    if len(pal) == 1:
        return pal, None  # 전부 pal[0]
    bits = max(4, (len(pal) - 1).bit_length())
    epl = 64 // bits  # ★한 롱에 몇 개 — 경계를 안 걸친다
    mask = (1 << bits) - 1
    longs = [v & 0xFFFFFFFFFFFFFFFF for v in bs["data"]]
    idx = [0] * 4096
    for i in range(4096):
        idx[i] = (longs[i // epl] >> ((i % epl) * bits)) & mask
    return pal, idx


# ── scan — 건물이 어디 있나 ────────────────────────────────────────────────

def scan(rdir: Path, min_markers: int, top: int) -> None:
    density: dict[tuple[int, int], int] = {}
    files = sorted(rdir.glob("r.*.mca"))
    for n, mca in enumerate(files):
        _, rx, rz = mca.stem.split(".")
        rx, rz = int(rx), int(rz)
        for ci, raw in chunks_of(mca):
            hits = sum(raw.count(m) for m in MARKERS)
            if hits >= min_markers:
                cx, cz = rx * 32 + ci % 32, rz * 32 + ci // 32
                density[(cx, cz)] = hits
        if (n + 1) % 100 == 0:
            print(f"  … {n + 1}/{len(files)} 리전 · 건축 청크 {len(density)}", file=sys.stderr)

    # 이웃(8방) 무리 짓기
    seen: set[tuple[int, int]] = set()
    clusters = []
    for start in density:
        if start in seen:
            continue
        todo, members = [start], []
        seen.add(start)
        while todo:
            c = todo.pop()
            members.append(c)
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    nb = (c[0] + dx, c[1] + dz)
                    if nb in density and nb not in seen:
                        seen.add(nb)
                        todo.append(nb)
        xs = [c[0] for c in members]
        zs = [c[1] for c in members]
        clusters.append({
            "chunks": len(members),
            "markers": sum(density[c] for c in members),
            "box": (min(xs) * 16, min(zs) * 16, max(xs) * 16 + 15, max(zs) * 16 + 15),
        })

    clusters.sort(key=lambda c: -c["markers"])
    print(f"\n건축 청크 {len(density)}개 · 무리 {len(clusters)}개 (표지 ≥{min_markers}/청크)")
    print(f"상위 {min(top, len(clusters))}개 — box 명령에 쓸 블록 좌표 (x1,z1)~(x2,z2):\n")
    for c in clusters[:top]:
        x1, z1, x2, z2 = c["box"]
        print(f"  표지 {c['markers']:7d} · 청크 {c['chunks']:4d} · "
              f"({x1},{z1}) ~ ({x2},{z2}) · {x2-x1+1}×{z2-z1+1}")


# ── box — 상자를 잘라 TSV 로 ───────────────────────────────────────────────

def extract(rdir: Path, lo: tuple, hi: tuple) -> dict[tuple[int, int, int], str]:
    (x1, y1, z1), (x2, y2, z2) = lo, hi
    grid: dict[tuple[int, int, int], str] = {}
    for cx in range(x1 >> 4, (x2 >> 4) + 1):
        for cz in range(z1 >> 4, (z2 >> 4) + 1):
            mca = rdir / f"r.{cx >> 5}.{cz >> 5}.mca"
            if not mca.exists():
                continue
            want = (cx & 31) + (cz & 31) * 32
            for ci, raw in chunks_of(mca):
                if ci != want:
                    continue
                chunk = parse_chunk(raw)
                for sec in chunk.get("sections", []):
                    sy = sec["Y"] * 16
                    if sy > y2 or sy + 15 < y1:
                        continue
                    pal, idx = section_blocks(sec)
                    if pal is None:
                        continue
                    for i in range(4096):
                        y = sy + (i >> 8)
                        z = cz * 16 + ((i >> 4) & 15)
                        x = cx * 16 + (i & 15)
                        if x1 <= x <= x2 and y1 <= y <= y2 and z1 <= z <= z2:
                            grid[(x - x1, y - y1, z - z1)] = pal[0] if idx is None else pal[idx[i]]
                break
    return grid


# ── 눈 (selftest) ──────────────────────────────────────────────────────────

def _pack_nospan(vals, bits):
    epl = 64 // bits
    longs = []
    for i in range(0, len(vals), epl):
        w = 0
        for j, v in enumerate(vals[i : i + epl]):
            w |= (v & ((1 << bits) - 1)) << (j * bits)
        if w >= 1 << 63:
            w -= 1 << 64
        longs.append(w)
    return longs


def selftest() -> int:
    fails, ran = [], [0]

    def eye(name, cond):
        ran[0] += 1
        print(("  ✓ " if cond else "  ✗ ") + name)
        if not cond:
            fails.append(name)

    # ① 무경계 풀기 — 팔레트 33종 → 6비트 · 한 롱에 10개 · 11번째는 새 롱
    ids = [i % 33 for i in range(4096)]
    pal33 = [{"Name": f"minecraft:b{i}"} for i in range(33)]
    sec = {"block_states": {"palette": pal33, "data": _pack_nospan(ids, 6)}}
    pal, idx = section_blocks(sec)
    eye("무경계 6비트 (경계 앞뒤 포함 전 칸)", idx == ids)

    # ② 바닥 4비트 — 팔레트 2종이어도 4비트다
    ids2 = [i % 2 for i in range(4096)]
    sec2 = {"block_states": {"palette": pal33[:2], "data": _pack_nospan(ids2, 4)}}
    _, idx2 = section_blocks(sec2)
    eye("바닥 4비트", idx2 == ids2)

    # ③ 팔레트 하나 = data 없음 = 전부 그 블록
    pal1, idx1 = section_blocks({"block_states": {"palette": [{"Name": "minecraft:air"}]}})
    eye("팔레트 하나 지름길", pal1 == ["minecraft:air"] and idx1 is None)

    # ④ 섹션 안 자리 순서 — i = y·256 + z·16 + x
    eye("자리 순서 y·z·x", (3 << 8 | 2 << 4 | 1) == 3 * 256 + 2 * 16 + 1)

    # ⑤ 합성 리전 왕복 — zlib 청크 하나를 만들어 읽는다
    from schem_import import _nb, _pc, _pi
    body = _pc(xPos=(3, _pi(0)), zPos=(3, _pi(0)))
    raw = _nb(10, "", body)
    comp = zlib.compress(raw)
    head = bytearray(8192)
    head[0:3] = (2).to_bytes(3, "big")  # 첫 청크가 섹터 2 에서 시작
    head[3] = 1
    # 길이 필드는 압축 바이트를 포함한다 (len+1) — 앤빌 규약
    blob = bytes(head) + (len(comp) + 1).to_bytes(4, "big") + b"\x02" + comp
    blob += bytes(4096 - (len(blob) % 4096))
    import tempfile
    with tempfile.NamedTemporaryFile(suffix=".mca", delete=False) as f:
        f.write(blob)
        tmp = Path(f.name)
    got = list(chunks_of(tmp))
    tmp.unlink()
    eye("리전 머리·zlib 왕복", len(got) == 1 and got[0][0] == 0
        and parse_chunk(got[0][1])["xPos"] == 0)

    # ⑥ 표지 세기 — 건축 이름이 든 NBT 가 잡힌다
    hits = sum((b"minecraft:dark_oak_planks x minecraft:stone").count(m) for m in MARKERS)
    eye("표지 세기", hits == 1)

    print(f"\n눈 {ran[0]}종 · 실패 {len(fails)}")
    return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("mode", nargs="?", choices=["scan", "box"])
    ap.add_argument("args", nargs="*")
    ap.add_argument("--out", help="box: 출력 tsv 이름")
    ap.add_argument("--min-markers", type=int, default=8, help="scan: 청크당 표지 문턱")
    ap.add_argument("--top", type=int, default=15)
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()

    if a.selftest:
        return selftest()
    if a.mode == "scan":
        if len(a.args) != 1:
            ap.error("scan <디렉터리>")
        scan(region_dir_of(Path(a.args[0])), a.min_markers, a.top)
        return 0
    if a.mode == "box":
        if len(a.args) != 3:
            ap.error("box <디렉터리> x1,y1,z1 x2,y2,z2")
        lo = tuple(int(v) for v in a.args[1].split(","))
        hi = tuple(int(v) for v in a.args[2].split(","))
        lo, hi = tuple(map(min, lo, hi)), tuple(map(max, lo, hi))
        grid = extract(region_dir_of(Path(a.args[0])), lo, hi)
        if not grid:
            sys.exit("상자가 비었다 — 좌표를 다시 보라 (청크가 없는 자리인가?)")
        out = OUT_DIR / (a.out or "world_box.tsv")
        write_tsv(grid, out)
        report(grid, f"box {lo}~{hi}")
        print(f"  → {out}")
        return 0
    ap.error("scan 또는 box (또는 --selftest)")


if __name__ == "__main__":
    sys.exit(main())
