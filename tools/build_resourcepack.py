#!/usr/bin/env python3
"""혼천 서버 리소스팩 컴파일러 — 결정론 생성 (맵과 같은 철학: 팩도 컴파일한다).

산출: resourcepack/ (팩 소스) — 기동 스크립트가 zip으로 묶는다.
글리프 (사설 영역 코드포인트, minecraft:default 폰트에 주입 — 채팅/액션바 어디서나 렌더):
  U+E000        기세 아이콘 (백색 — 채팅 색 코드로 틴트: 회/백/황/적)
  U+E010~E018   화후 게이지 0~8칸 (경락도 원장용)
의존성 없음 — 순수 표준 라이브러리 PNG 작성기.
"""
import json
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACK = ROOT / "resourcepack"
FONT_DIR = PACK / "assets" / "honcheon" / "textures" / "font"

W = (255, 255, 255, 255)   # 백색 — 인게임 색 코드가 틴트한다
T = (0, 0, 0, 0)           # 투명


def write_png(path: Path, rows):
    """최소 PNG 작성기 (RGBA, 무압축 필터 0)."""
    height, width = len(rows), len(rows[0])
    raw = b"".join(b"\x00" + b"".join(struct.pack("4B", *px) for px in row) for row in rows)

    def chunk(tag, data):
        payload = tag + data
        return struct.pack(">I", len(data)) + payload + struct.pack(">I", zlib.crc32(payload))

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
                     + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def gise_icon():
    """8x8 기세 아이콘 — 솟는 기운 (불꽃형)."""
    art = [
        "...#....",
        "...##...",
        "..###...",
        "..####..",
        ".#####..",
        ".######.",
        "..####..",
        "...##...",
    ]
    return [[W if c == "#" else T for c in row] for row in art]


def gauge(filled: int):
    """20x6 화후 게이지 — 테두리 + 채움 (filled/8)."""
    width, height, inner = 20, 6, 18
    fill_px = round(inner * filled / 8)
    rows = []
    for y in range(height):
        row = []
        for x in range(width):
            border = y in (0, height - 1) or x in (0, width - 1)
            filled_cell = 1 <= y <= height - 2 and 1 <= x <= fill_px
            row.append(W if border or filled_cell else T)
        rows.append(row)
    return rows


def main():
    write_png(FONT_DIR / "gise.png", gise_icon())
    providers = [{
        "type": "bitmap", "file": "honcheon:font/gise.png",
        "height": 8, "ascent": 7, "chars": [""],
    }]
    for n in range(9):
        write_png(FONT_DIR / f"gauge_{n}.png", gauge(n))
        providers.append({
            "type": "bitmap", "file": f"honcheon:font/gauge_{n}.png",
            "height": 7, "ascent": 6, "chars": [chr(0xE010 + n)],
        })

    font = PACK / "assets" / "minecraft" / "font" / "default.json"
    font.parent.mkdir(parents=True, exist_ok=True)
    font.write_text(json.dumps({"providers": providers}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    (PACK / "pack.mcmeta").write_text(json.dumps({
        "pack": {"pack_format": 46, "description": "혼천 — 기세·화후 글리프 (MVT)"}
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(f"팩 컴파일 완료: {PACK.relative_to(ROOT)} (글리프 10종 + 폰트 주입 + pack.mcmeta)")


if __name__ == "__main__":
    main()
