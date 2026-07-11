#!/usr/bin/env python3
"""혼천 서버 리소스팩 컴파일러 — 결정론 생성 (맵과 같은 철학: 팩도 컴파일한다).

산출: resourcepack/ (팩 소스) — 기동 스크립트가 zip으로 묶는다.
글리프 (사설 영역 코드포인트, minecraft:default 폰트에 주입 — 채팅/액션바 어디서나 렌더):
  U+E000        기세 아이콘 (백색 — 채팅 색 코드로 틴트: 회/백/황/적)
  U+E010~E018   화후 게이지 0~8칸 (경락도 원장용)
                내력·원기 게이지는 같은 글리프를 색 틴트로 재사용 (슬롯 절약 — 설계 등록)
  U+E020~E027   경지 문장 8단: 삼류~생사경 (하위=획 수, 중위=봉우리/검, 상위=원환+중심)
  U+E080        경락도 GUI 배경 (먹색 패널 + 전각 도장풍 모서리 + 제목 구분선 + 여백 가이드,
                인벤토리 제목 음수 공백 기법용)
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
INK_LINE = (214, 205, 186, 150)   # 제목 구분선 — 화선지 톤 절반 농도
INK_GUIDE = (214, 205, 186, 70)   # 여백 가이드 점선 — 희미하게 (배경은 조용하게)
SEAL = (150, 56, 44, 255)         # 주사(朱砂) — 전각 도장풍 모서리 장식 전용 (저채도 진사홍)


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
    """8x8 기세 아이콘 — 솟는 기운 (불꽃형).
    아래로 갈수록 넓어지는 화염 실루엣 + 우상단 분리 불티 1px = 상승 운동감 (1초 가독)."""
    return art_rows([
        "...#..#.",
        "...##...",
        "..###...",
        "..####..",
        ".#####..",
        ".######.",
        ".######.",
        "..####..",
    ])


def gauge(filled: int):
    """20x6 화후 게이지 — 테두리 + 채움 (filled/8).
    눈금: 2단위 간격 상단 틱 3개 (x=5·9·14) — 빈 구간에만 보이고 채움에 흡수된다.
    끝단: 부분 채움(1~7)의 선두 열은 상하 1px 깎은 테이퍼 — 진행 끝이 한눈에 읽힌다.
          8칸 만충은 테이퍼 없이 우측 테두리 밀착 (완충 = 꽉 참)."""
    width, height, inner = 20, 6, 18

    def unit_px(k):
        return (inner * k + 4) // 8   # 반올림(half-up) — 단조 증가 보장

    fill_px = unit_px(filled)
    ticks = {unit_px(k) for k in (2, 4, 6)}
    rows = []
    for y in range(height):
        row = []
        for x in range(width):
            border = y in (0, height - 1) or x in (0, width - 1)
            in_fill = 1 <= y <= height - 2 and 1 <= x <= fill_px
            if in_fill and x == fill_px and 1 <= filled <= 7 and y in (1, height - 2):
                in_fill = False   # 끝단 테이퍼
            tick = y == 1 and x in ticks and x > fill_px
            row.append(W if border or in_fill or tick else T)
        rows.append(row)
    return rows


# ─── 경지 문장 8단 (E020~E027) — 8x8 2값 플레이스홀더 ───
# 디자인 언어 (1초 상호 구별):
#   하위 3단(삼류~일류) = 획 수 一二三 (윗획 짧게·아랫획 길게 — 서예 리듬)
#   중위 3단: 절정=봉우리(산), 초절정=검(劍), 화경=검+칼끝 검기 광점
#   상위 2단: 현경=8행 원환(空), 생사경=원환+만월 중심핵(滿)
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
        "..####..",
        "........",
        ".######.",
        "........",
        "........",
        "........",
    ],
    "일류": [
        "........",
        "..####..",
        "........",
        "..####..",
        "........",
        ".######.",
        "........",
        "........",
    ],
    "절정": [
        "........",
        "...#....",
        "..###...",
        "..###...",
        ".#####..",
        "#######.",
        "........",
        "........",
    ],
    "초절정": [
        "...#....",
        "..###...",
        "..###...",
        "..###...",
        ".#####..",
        "...#....",
        "...#....",
        "..###...",
    ],
    "화경": [
        ".#.#.#..",
        "..###...",
        "..###...",
        "..###...",
        ".#####..",
        "...#....",
        "...#....",
        "..###...",
    ],
    "현경": [
        "..####..",
        ".#....#.",
        "#......#",
        "#......#",
        "#......#",
        "#......#",
        ".#....#.",
        "..####..",
    ],
    "생사경": [
        "..####..",
        ".#....#.",
        "#..##..#",
        "#.####.#",
        "#.####.#",
        "#..##..#",
        ".#....#.",
        "..####..",
    ],
}


def gui_background():
    """176x110 경락도 GUI 배경 패널 — 먹색 + 화선지 테두리 + 수묵 표구 장식.
    - 모서리: 전각 도장풍 주사색 ㄱ자 쌍획 4귀 (인장 테두리 모티프, 4px 인셋·팔 6px·2px 두께)
    - 제목 구분선: y=17 가로선 — 상단 16px 제목 영역과 본문 분리
    - 여백 가이드: 5px 인셋 점선 사각 — 내용 배치 기준선 (희미한 화선지 톤, 배경은 조용하게)
    인벤토리 제목에 음수 공백으로 얹는 기법용. 정확한 오프셋은 인게임 튜닝 (플레이스홀더)."""
    width, height = 176, 110
    grid = []
    for y in range(height):
        row = []
        for x in range(width):
            edge = y in (0, 1, height - 2, height - 1) or x in (0, 1, width - 2, width - 1)
            row.append(INK_EDGE if edge else INK)
        grid.append(row)

    # 여백 가이드 — 5px 인셋 점선 사각
    inset = 5
    for x in range(inset, width - inset):
        if x % 2 == 0:
            grid[inset][x] = INK_GUIDE
            grid[height - 1 - inset][x] = INK_GUIDE
    for y in range(inset, height - inset):
        if y % 2 == 0:
            grid[y][inset] = INK_GUIDE
            grid[y][width - 1 - inset] = INK_GUIDE

    # 제목 구분선 — 제목 영역(상단 16px)과 본문 경계
    for x in range(10, width - 10):
        grid[17][x] = INK_LINE

    # 모서리 전각 도장풍 ㄱ자 쌍획 — 4귀, 가이드·구분선 위에 마지막으로 얹는다
    arm, thick, off = 6, 2, 4
    for oy, sy in ((off, 1), (height - 1 - off, -1)):
        for ox, sx in ((off, 1), (width - 1 - off, -1)):
            for t in range(thick):
                for a in range(arm):
                    grid[oy + sy * t][ox + sx * a] = SEAL   # 가로 팔
                    grid[oy + sy * a][ox + sx * t] = SEAL   # 세로 팔
    return grid


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
    # ensure_ascii=True — 산출 JSON에서도 PUA가 \uXXXX 이스케이프로 남는다 (F26: 리터럴 유실 방지)
    font.write_text(json.dumps({"providers": providers}, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")

    (PACK / "pack.mcmeta").write_text(json.dumps({
        "pack": {"pack_format": 46, "description": "혼천 — 기세·화후·경지 문장·경락도 글리프 (M2)"}
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    total = 1 + 9 + len(REALM_CRESTS) + 1
    print(f"팩 컴파일 완료: {PACK.relative_to(ROOT)} (글리프 {total}종 + 폰트 주입 + pack.mcmeta)")


if __name__ == "__main__":
    main()
