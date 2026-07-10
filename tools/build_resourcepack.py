#!/usr/bin/env python3
"""혼천 서버 리소스팩 컴파일러 — 결정론 생성 (맵과 같은 철학: 팩도 컴파일한다).

산출: resourcepack/ (팩 소스) — 기동 스크립트가 zip으로 묶는다.
글리프 (사설 영역 코드포인트, minecraft:default 폰트에 주입 — 채팅/액션바 어디서나 렌더):
  U+E000        기세 아이콘 (백색 — 채팅 색 코드로 틴트: 회/백/황/적)
  U+E010~E018   화후 게이지 0~8칸 (경락도 원장용)
                내력·원기 게이지는 같은 글리프를 색 틴트로 재사용 (슬롯 절약 — 설계 등록)
  U+E020~E027   경지 문장 8단: 삼류~생사경 (M2 슬롯 — 플레이스홀더, 별 계열)
  U+E080        경락도 GUI 배경 (M2 슬롯 — 먹색 패널, 인벤토리 제목 음수 공백 기법용)
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
INK = (26, 24, 22, 235)    # 먹색 — GUI 배경 전용 (틴트 불가 채널이라 직접 채색 허용)
INK_EDGE = (214, 205, 186, 255)   # 화선지 테두리


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


def art_rows(art):
    return [[W if c == "#" else T for c in row] for row in art]


def gise_icon():
    """8x8 기세 아이콘 — 솟는 기운 (불꽃형)."""
    return art_rows([
        "...#....",
        "...##...",
        "..###...",
        "..####..",
        ".#####..",
        ".######.",
        "..####..",
        "...##...",
    ])


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


# ─── 경지 문장 8단 (E020~E027) — 8x8 2값 플레이스홀더 ───
# 디자인 언어: 하위 3단(삼류~일류)은 획, 중위(절정~화경)는 능형, 상위(현경·생사경)는 원환.
# 정식 아트는 M3 전 교체형 (resourcepack_design.yml promotion_timing).
REALM_CRESTS = {
    "삼류": [
        "........",
        "........",
        "........",
        ".######.",
        "........",
        "........",
        "........",
        "........",
    ],
    "이류": [
        "........",
        "........",
        ".######.",
        "........",
        ".######.",
        "........",
        "........",
        "........",
    ],
    "일류": [
        "........",
        ".######.",
        "........",
        ".######.",
        "........",
        ".######.",
        "........",
        "........",
    ],
    "절정": [
        "...#....",
        "..###...",
        ".#####..",
        "#######.",
        ".#####..",
        "..###...",
        "...#....",
        "........",
    ],
    "초절정": [
        "...#....",
        "..#.#...",
        ".#...#..",
        "#..#..#.",
        ".#...#..",
        "..#.#...",
        "...#....",
        "........",
    ],
    "화경": [
        "...#....",
        "..###...",
        ".##.##..",
        "###.###.",
        ".##.##..",
        "..###...",
        "...#....",
        "...#....",
    ],
    "현경": [
        "..####..",
        ".#....#.",
        "#......#",
        "#......#",
        "#......#",
        ".#....#.",
        "..####..",
        "........",
    ],
    "생사경": [
        "..####..",
        ".#....#.",
        "#..##..#",
        "#..##..#",
        "#..##..#",
        ".#....#.",
        "..####..",
        "........",
    ],
}


def gui_background():
    """176x110 경락도 GUI 배경 패널 — 먹색 + 화선지 테두리 (표준 상단 인벤 영역).
    인벤토리 제목에 음수 공백으로 얹는 기법용. 정확한 오프셋은 인게임 튜닝 (플레이스홀더)."""
    width, height = 176, 110
    rows = []
    for y in range(height):
        row = []
        for x in range(width):
            edge = y in (0, 1, height - 2, height - 1) or x in (0, 1, width - 2, width - 1)
            row.append(INK_EDGE if edge else INK)
        rows.append(row)
    return rows


def main():
    write_png(FONT_DIR / "gise.png", gise_icon())
    providers = [{
        "type": "bitmap", "file": "honcheon:font/gise.png",
        # PUA 리터럴 금지 — 편집기가 사설 영역 문자를 조용히 지울 수 있다 (M2b에서 실제 유실
        # → 빈 chars 하나가 팩 전체를 무효화). 다른 프로바이더처럼 chr()로 만든다.
        "height": 8, "ascent": 7, "chars": [chr(0xE000)],
    }]
    for n in range(9):
        write_png(FONT_DIR / f"gauge_{n}.png", gauge(n))
        providers.append({
            "type": "bitmap", "file": f"honcheon:font/gauge_{n}.png",
            "height": 7, "ascent": 6, "chars": [chr(0xE010 + n)],
        })
    for i, (realm, art) in enumerate(REALM_CRESTS.items()):
        write_png(FONT_DIR / f"crest_{i}.png", art_rows(art))
        providers.append({
            "type": "bitmap", "file": f"honcheon:font/crest_{i}.png",
            "height": 8, "ascent": 7, "chars": [chr(0xE020 + i)],
        })
    write_png(FONT_DIR / "gui_ledger.png", gui_background())
    providers.append({
        "type": "bitmap", "file": "honcheon:font/gui_ledger.png",
        "height": 110, "ascent": 13, "chars": [chr(0xE080)],   # ascent는 인게임 튜닝 대상
    })

    font = PACK / "assets" / "minecraft" / "font" / "default.json"
    font.parent.mkdir(parents=True, exist_ok=True)
    font.write_text(json.dumps({"providers": providers}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    (PACK / "pack.mcmeta").write_text(json.dumps({
        "pack": {"pack_format": 46, "description": "혼천 — 기세·화후·경지 문장·경락도 글리프 (M2)"}
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    total = 1 + 9 + len(REALM_CRESTS) + 1
    print(f"팩 컴파일 완료: {PACK.relative_to(ROOT)} (글리프 {total}종 + 폰트 주입 + pack.mcmeta)")


if __name__ == "__main__":
    main()
