#!/usr/bin/env python3
"""블록 색표 — <b>텍스처에서 뽑는다.</b> 손으로 유지하지 않는다.

    python3 tools/block_palette.py              # config/block_colors.json 을 굽는다
    python3 tools/block_palette.py --selftest   # 눈을 시험하는 눈

왜 있나
-------
`blueprint_draw.py` 는 재료 색을 <b>손으로 적은 17종짜리 표</b>로 갖고 있었다.
그 사이 팔레트가 여러 번 바뀌었고, 2026-08-11 에 재 보니 본전이 쓰는 재료 14종 중
<b>9종(64%)을 몰라</b> 정면도가 통째로 `?` 였다. <b>도면 도구가 고장난 채 몇 회차를 지났다.</b>

원인은 표가 틀린 것이 아니라 <b>손으로 유지하는 표</b>라는 것이다. 팔레트를 갈 때마다
사람이 따라 적어야 하고, 안 적어도 아무도 안 죽는다 — 그래서 조용히 낡는다.

그래서 <b>정본에서 뽑는다</b>: 클라이언트 jar 의 실제 블록 텍스처를 읽어 평균색을 낸다.
리소스팩이 그 블록을 덮었으면 <b>팩 것을 먼저</b> 쓴다 — 게임에서 보이는 것이 그것이다.

무엇을 재나
----------
* 알파 128 이상인 화소만 (유리·살창의 빈 곳은 색이 아니다)
* 평균은 <b>선형(sRGB→linear)</b>에서 낸다. 감마 공간에서 평균 내면 어두운 텍스처가
  실제보다 밝게 나온다 (한 화소가 튀는 텍스처에서 특히)
* `투명도` = 알파 128 이상 화소의 비율. 살창·울타리처럼 성긴 것을 도면이 알아야 한다
"""
from __future__ import annotations

import io
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLIENT = sorted((ROOT / "run" / "client").glob("client-*.jar"))
PACK_BLOCK = ROOT / "resourcepack" / "assets" / "minecraft" / "textures" / "block"
OUT = ROOT / "config" / "block_colors.json"

# 한 블록이 여러 면을 쓸 때 <b>정면도가 보는 면</b>. 안 적으면 <이름>.png 를 쓴다.
FACE = {
    "smooth_stone": "smooth_stone",
    "polished_andesite": "polished_andesite",
    "dark_oak_log": "dark_oak_log",          # 옆면 (세로결) — 위에서 보는 도면은 top 을 써야 하나
    "mangrove_log": "mangrove_log",
    "stripped_mangrove_log": "stripped_mangrove_log",
    "red_terracotta": "red_terracotta",
    "bone_block": "bone_block_side",
    "lantern": "lantern",
}
# 「재료가 아니라 처방」인 이름 — 도면이 쓰는 별칭이다 (Blueprint 가 실물로 푼다)
ALIAS = {
    "plaster": "bone_block",
    "lattice": "dark_oak_trapdoor",
}
# 계단·반블록·담장은 제 본체 텍스처를 쓴다
SUFFIX = ("_stairs", "_slab", "_wall", "_fence", "_trapdoor", "_door", "_sign")
BASE_OF = {
    "stone_brick_stairs": "stone_bricks", "stone_brick_slab": "stone_bricks",
    "stone_brick_wall": "stone_bricks",
    "deepslate_tile_stairs": "deepslate_tiles", "deepslate_tile_slab": "deepslate_tiles",
    "deepslate_tile_wall": "deepslate_tiles",
    "polished_andesite_slab": "polished_andesite",
    "andesite_wall": "andesite",
    "dark_oak_slab": "dark_oak_planks", "dark_oak_stairs": "dark_oak_planks",
    "mangrove_slab": "mangrove_planks", "mangrove_stairs": "mangrove_planks",
    "jungle_slab": "jungle_planks", "jungle_stairs": "jungle_planks",
    "spruce_slab": "spruce_planks",
    "warped_slab": "warped_planks", "warped_stairs": "warped_planks",
    "dark_oak_fence": "dark_oak_planks",
}


def _srgb_to_linear(v: float) -> float:
    v /= 255.0
    return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4


def _linear_to_srgb(v: float) -> int:
    s = 12.92 * v if v <= 0.0031308 else 1.055 * (v ** (1 / 2.4)) - 0.055
    return max(0, min(255, round(s * 255)))


def average(png_bytes: bytes) -> tuple[tuple[int, int, int], float] | None:
    """(평균색, 불투명 비율) — 알파 128 미만은 <b>색이 아니다</b>."""
    from PIL import Image

    im = Image.open(io.BytesIO(png_bytes)).convert("RGBA")
    # 애니메이션 시트(세로로 이어 붙인 것)는 첫 칸만 본다
    if im.height > im.width and im.height % im.width == 0:
        im = im.crop((0, 0, im.width, im.width))
    px = im.load()
    acc = [0.0, 0.0, 0.0]
    n = 0
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            if a < 128:
                continue
            acc[0] += _srgb_to_linear(r)
            acc[1] += _srgb_to_linear(g)
            acc[2] += _srgb_to_linear(b)
            n += 1
    if n == 0:
        return None
    total = im.width * im.height
    return (tuple(_linear_to_srgb(c / n) for c in acc), n / total)


def grain(png_bytes: bytes):
    """<b>결</b> — 무늬가 어느 쪽으로 흐르는가. 「무엇으로 읽히는가」의 첫 조각이다.

    2026-08-11 의 실패: 기둥을 `red_nether_bricks` 로 갈았더니 <b>색 지표는 만점</b>인데
    실물은 <b>조적 기둥</b>이었다. 목조 기둥은 세로결이어야 하는데 가로 줄눈이 뚜렷했다.
    색만 재는 자는 이것을 <b>영영 못 잡는다</b> — 색 분포가 같아도 무늬가 다르기 때문이다.

    그래서 <b>변화의 방향</b>을 잰다. 텍스처는 타일링되므로 가장자리는 <b>감싸서</b> 잇는다.

    * {@code Ex} = 가로로 갈 때의 변화량 → <b>세로선</b>이 만든다 (나무결·세로살)
    * {@code Ey} = 세로로 갈 때의 변화량 → <b>가로선</b>이 만든다 (벽돌 줄눈·단 이음)
    * {@code 방향} = (Ex−Ey)/(Ex+Ey) ∈ [−1, +1] — <b>+ 세로결 · − 가로 줄눈</b>
    * {@code 세기} = (Ex+Ey)/2 / 255 — 0 이면 민판, 크면 무늬가 세다

    ★이것은 <b>재료의 성질</b>이라 사진도 카메라도 필요 없다. 눈이 즉석에서 부를 수 있다.
    """
    from PIL import Image

    im = Image.open(io.BytesIO(png_bytes)).convert("RGBA")
    if im.height > im.width and im.height % im.width == 0:
        im = im.crop((0, 0, im.width, im.width))
    w, h = im.size
    px = im.load()

    def lum(x, y):
        r, g, b, a = px[x % w, y % h]
        return (0.299 * r + 0.587 * g + 0.114 * b) if a >= 128 else None

    ex = ey = 0.0
    nx = ny = 0
    for y in range(h):
        for x in range(w):
            c = lum(x, y)
            if c is None:
                continue
            r = lum(x + 1, y)
            if r is not None:
                ex += abs(c - r)
                nx += 1
            d = lum(x, y + 1)
            if d is not None:
                ey += abs(c - d)
                ny += 1
    if nx == 0 or ny == 0:
        return 0.0, 0.0
    ex /= nx
    ey /= ny
    tot = ex + ey
    return (round((ex - ey) / tot, 3) if tot > 1e-9 else 0.0), round(tot / 2 / 255, 4)


def texture_name(block: str) -> str:
    block = ALIAS.get(block, block)
    if block in FACE:
        return FACE[block]
    if block in BASE_OF:
        return BASE_OF[block]
    return block


def build() -> dict:
    if not CLIENT:
        raise SystemExit("클라이언트 jar 가 없다: run/client/client-*.jar")
    jar = CLIENT[-1]
    out: dict[str, dict] = {}
    with zipfile.ZipFile(jar) as z:
        names = {}
        for info in z.namelist():
            if info.startswith("assets/minecraft/textures/block/") and info.endswith(".png"):
                names[Path(info).stem] = info
        for stem, path in sorted(names.items()):
            # ★리소스팩이 덮은 블록은 <b>팩 것을 쓴다</b> — 게임에서 보이는 것이 그것이다
            packed = PACK_BLOCK / f"{stem}.png"
            src = "pack" if packed.exists() else "vanilla"
            data = packed.read_bytes() if packed.exists() else z.read(path)
            got = average(data)
            if got is None:
                continue
            (r, g, b), solid = got
            gdir, gstr = grain(data)
            out[stem] = {"rgb": [r, g, b], "solid": round(solid, 3), "src": src,
                         "lum": round(0.299 * r + 0.587 * g + 0.114 * b, 1),
                         # ★결 — 「무엇으로 읽히는가」의 첫 조각 (색만 재는 자가 못 보던 것)
                         "grain": gdir, "grain_strength": gstr}
    return out


# 파생 접미 — 「무엇의 계단인가」를 <b>규칙으로</b> 푼다 (손으로 다 적지 않는다)
_SUFFIX = ("_stairs", "_slab", "_wall", "_fence", "_fence_gate", "_trapdoor", "_button",
           "_pressure_plate", "_door", "_sign", "_wall_sign", "_hanging_sign")


def color_of(table: dict, block: str):
    """도면·실물이 쓰는 이름 → 색. 모르면 {@code None} (부르는 쪽이 <b>짖는다</b>).

    ★파생을 <b>규칙으로</b> 푼다. 2026-08-11 실측: 실물 덤프의 블록 이름은 도면의 것과
    다르다 (`deepslate_tile_stairs`·`stone_brick_wall`…). 손으로 적은 표는 14종을 몰랐다.
    """
    for cand in _derive(block):
        got = table.get(cand)
        if got is not None:
            return got
    return None


# 결을 <b>말</b>로 옮기는 문턱 — 자가 뭐라고 읽는지 사람이 알아야 고칠 수 있다
GRAIN_PLAIN = 0.012        # 이보다 약하면 무늬가 없는 것이다 (방향을 안 묻는다)
GRAIN_DIR = 0.15           # 이보다 치우치면 방향이 있다


def reads_as(entry) -> str:
    """이 재료는 <b>무엇으로 읽히는가</b> — 민판 · 세로결 · 가로줄눈 · 격자.

    ★세기를 <b>먼저</b> 본다. 무늬가 약하면 방향은 뜻이 없다
    (`red_terracotta` 는 방향이 −0.184 지만 세기가 0.007 이라 <b>민판</b>이다 —
     이걸 「가로줄눈」이라 부르면 자가 헛짖는다).
    """
    if entry is None:
        return "모름"
    s = entry.get("grain_strength", 0.0)
    g = entry.get("grain", 0.0)
    if s < GRAIN_PLAIN:
        return "민판"
    if g > GRAIN_DIR:
        return "세로결"
    if g < -GRAIN_DIR:
        return "가로줄눈"
    return "격자"


def _derive(block: str):
    """이름 하나에서 <b>시도할 텍스처 이름들</b>을 순서대로 낸다."""
    seen = []

    def add(v):
        if v and v not in seen:
            seen.append(v)

    add(texture_name(block))
    add(block)
    # 배너·깃발 — 천이다. 같은 색 양털로 읽는다
    if block.endswith("_banner"):
        add(block.replace("_wall_banner", "_wool").replace("_banner", "_wool"))
    base = block
    for suf in _SUFFIX:
        if base.endswith(suf):
            base = base[: -len(suf)]
            break
    if base != block:
        add(base)
        add(base + "s")                       # deepslate_tile → deepslate_tiles
        add(base + "_planks")                 # dark_oak → dark_oak_planks
        add(base.replace("_tile", "_tiles").replace("_brick", "_bricks"))
        add(base + "_block")
    add(block + "_side")                      # bone_block → bone_block_side
    add(block + "_top")
    return seen


# ───────────────────────── 눈을 시험하는 눈 ─────────────────────────

def _selftest() -> int:
    fails = []

    def check(name, cond, got=""):
        print(("  ✓ " if cond else "  ✗ ") + name + (f" — {got}" if not cond else ""))
        if not cond:
            fails.append(name)

    from PIL import Image

    def png(pixels, size):
        im = Image.new("RGBA", size)
        im.putdata(pixels)
        b = io.BytesIO()
        im.save(b, "PNG")
        return b.getvalue()

    # ① 단색은 그 색이 나온다
    got = average(png([(200, 100, 50, 255)] * 16, (4, 4)))
    check("단색 텍스처는 그 색을 준다", got[0] == (200, 100, 50), got)
    check("단색은 불투명 비율 1.0", got[1] == 1.0, got)

    # ② 투명 화소는 <b>안 센다</b> — 살창의 빈 곳은 색이 아니다
    half = [(255, 255, 255, 255)] * 8 + [(0, 0, 0, 0)] * 8
    got = average(png(half, (4, 4)))
    check("투명은 색으로 안 센다 (흰색만 남는다)", got[0] == (255, 255, 255), got)
    check("불투명 비율이 0.5 로 잡힌다", abs(got[1] - 0.5) < 1e-9, got)

    # ③ ★선형 평균 — 감마 공간 평균과 <b>다르다</b>. 검정+흰색의 참 평균은 188 쯤이다
    bw = [(0, 0, 0, 255)] * 8 + [(255, 255, 255, 255)] * 8
    got = average(png(bw, (4, 4)))
    check("★선형 평균이다 (검정+흰색 → 128 이 아니라 187 쯤)",
          180 <= got[0][0] <= 195, got[0])

    # ④ 별칭과 파생이 본체로 간다
    check("plaster → bone_block_side", texture_name("plaster") == "bone_block_side",
          texture_name("plaster"))
    check("lattice → dark_oak_trapdoor", texture_name("lattice") == "dark_oak_trapdoor",
          texture_name("lattice"))
    check("dark_oak_slab → dark_oak_planks",
          texture_name("dark_oak_slab") == "dark_oak_planks", texture_name("dark_oak_slab"))
    check("stone_brick_wall → stone_bricks",
          texture_name("stone_brick_wall") == "stone_bricks", texture_name("stone_brick_wall"))

    # ⑤ ★표가 <b>실제로 본전을 덮는가</b> — 이 도구가 태어난 이유다
    if OUT.exists():
        import re
        import yaml
        table = json.loads(OUT.read_text())
        d = yaml.safe_load((ROOT / "config/blueprints/hwasan_honjeon.yml").read_text())
        used = set()

        def collect(cols):
            for v in cols.values():
                for e in v:
                    m = re.match(r"(.+?)\*(\d+)$", e)
                    used.add(m.group(1) if m else e)

        collect(d["columns"])
        rf = list(d["roof"].values())[0]
        if "columns" in rf.get("upper", {}):
            collect(rf["upper"]["columns"])
        for tr in d.get("trim", []):
            used.add(tr["material"])
        used.discard("air")
        miss = sorted(m for m in used if color_of(table, m) is None)
        check(f"★본전이 쓰는 재료 {len(used)}종을 <b>전부</b> 안다", not miss, miss)
    else:
        check("색표가 구워져 있다", False, f"{OUT} 없음 — 먼저 굽는다")

    print(f"\n블록 색표의 눈 — {'통과' if not fails else f'실패 {len(fails)}: {fails}'}")
    return 1 if fails else 0


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(_selftest())
    if "--read" in sys.argv:
        tb = json.loads(OUT.read_text())
        for b in sys.argv[sys.argv.index("--read") + 1:]:
            e = color_of(tb, b)
            if e is None:
                print(f"{b:26s} 모름")
            else:
                print(f"{b:26s} {e['grain']:+7.3f} {e['grain_strength']:7.4f} "
                      f"명도 {e['lum']:5.1f}  <b>{reads_as(e)}</b>")
        sys.exit(0)
    table = build()
    OUT.write_text(json.dumps(table, ensure_ascii=False, indent=0, sort_keys=True))
    packed = sum(1 for v in table.values() if v["src"] == "pack")
    print(f"블록 색표 {len(table)}종 → {OUT.relative_to(ROOT)}  (팩이 덮은 것 {packed}종)")
