#!/usr/bin/env python3
"""시트 → 판(일러스트) — 컨셉 시트의 그림을 ItemDisplay 텍스처로 그대로 옮긴다.

★ 왜 (2026-07-23 사용자): 점 방식(템플릿 2,600점)은 파티클 예산과 정면 충돌한다.
  일러스트(판)는 스윙당 엔티티 1장 — 그림 복잡도가 서버 비용 0 이다 (예산 비교 §대화 기록).
  "일러스트 방식으로 공격 디자인 변경 시도 + 1차 친구 피드백(시트 「저대로」) 반영".

입력: 2db88914-….jpg (루트 — 시트 정본. sheet_to_template.py 와 같은 원본·같은 crop)
출력: resourcepack/assets/honcheon/textures/item/kigi/sheet_a1..3.png (+모델·아이템 정의)
      sheet_b1..3 = 수평 반전 (B 스윙 — 올려베기 교대용)
      1 = 피크(시트 그대로) · 2 = 빈짐(α55%·먹 30%) · 3 = 소멸(α28%·먹 60%)

검증: scratch/sheet2game/plate_preview.png — 4장 나란히 (어두운/밝은 배경 각각)
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SHEET = ROOT / "2db88914-39b2-4e7d-a322-1c6ac80ca6de.jpg"
PACK = ROOT / "resourcepack"
TEX = PACK / "assets" / "honcheon" / "textures" / "item"
MODEL = PACK / "assets" / "honcheon" / "models" / "item"
ITEMDEF = PACK / "assets" / "honcheon" / "items"

CANVAS = 256                        # qi.py KIGI_CANVAS 와 같다 (서브픽셀 뭉개짐 방지)
CROP = (555, 50, 875, 400)          # sheet_to_template.py CROPS["tps"] 와 같은 영역
MASKS = [(0, 0, 50, 40), (265, 0, 320, 350),   # 좌상 「가이드」 · 우측 주석 열 (crop 상대)
         (195, 5, 262, 38), (185, 120, 250, 175)]   # 화살촉 잔재 2점 (실측 — 초승달 무접촉 영역)
INK = np.array([38, 34, 30], dtype=float)      # 먹 — 빈짐 색내림의 종착지


def extract() -> Image.Image:
    """시트 crop → RGBA (어두운 종이 = 투명). 색은 시트의 것을 그대로 남긴다."""
    img = np.asarray(Image.open(SHEET).convert("RGB")).astype(float)
    x0, y0, x1, y1 = CROP
    crop = img[y0:y1, x0:x1].copy()
    # 바탕 추정 — 네 귀퉁이 24px (sheet_to_template.classify 와 같은 문법)
    corners = np.concatenate([
        crop[:24, :24].reshape(-1, 3), crop[:24, -24:].reshape(-1, 3),
        crop[-24:, :24].reshape(-1, 3), crop[-24:, -24:].reshape(-1, 3)])
    bg = np.median(corners, axis=0)
    dist = np.linalg.norm(crop - bg, axis=2)
    # 농도 상향 (2026-07-23 사용자: "그림 색의 농도를 좀 올리고") — 문턱을 낮추고
    # 감마 0.75 로 중간 농도를 짙게 (인게임 반투명 체감 보정)
    alpha = np.clip((dist - 12.0) / (70.0 - 12.0), 0.0, 1.0) ** 0.75
    for mx0, my0, mx1, my1 in MASKS:            # 주석은 그림이 아니다
        alpha[my0:my1, mx0:mx1] = 0.0
    # 티끌 제거 — 3×3 이웃 평균이 옅으면 고립점이다
    pad = np.pad(alpha, 1)
    neigh = sum(pad[dy:dy + alpha.shape[0], dx:dx + alpha.shape[1]]
                for dy in range(3) for dx in range(3)) / 9.0
    alpha = np.where(neigh < 0.06, 0.0, alpha)
    # ★ 주석 화살표 제거 — 좌표로 직접 지운다 (crop 320×350 격자 실측 · tps_grid.png).
    #   플러드필은 못 쓴다: 화살촉이 그림에 닿아 초승달까지 한 성분이 된다 (실측으로 전멸시켰다).
    #   화살표는 그림이 아니라 주석이므로, 시트가 갈리면 이 좌표도 갈린다 — 시트 정본에 종속.
    ARROWS = [((320, 38), (200, 40)), ((200, 40), (163, 65)),      # 상 — 「밀도(청백…)」
              ((320, 129), (232, 133)), ((232, 133), (125, 191)),  # 중 — 「밀도(옥…)」
              ((320, 212), (252, 216)), ((252, 216), (198, 257))]  # 하
    yy, xx = np.mgrid[0:alpha.shape[0], 0:alpha.shape[1]]
    for (x0a, y0a), (x1a, y1a) in ARROWS:
        vx, vy = x1a - x0a, y1a - y0a
        ln2 = float(vx * vx + vy * vy)
        t = np.clip(((xx - x0a) * vx + (yy - y0a) * vy) / ln2, 0.0, 1.0)
        d2 = (xx - (x0a + t * vx)) ** 2 + (yy - (y0a + t * vy)) ** 2
        alpha = np.where(d2 <= 11.0 ** 2, 0.0, alpha)
    # 마지막 빗질 — 우상 내부(x>140 · y<200)의 **고립 소성분**은 주석 잔재다
    #   (시트의 진짜 비백 잔향은 하단 내부에만 산다 — 실측). 본체 대성분은 안 건드린다.
    solid = (alpha > 0.05)
    lab = np.zeros(solid.shape, dtype=int)
    nxt = 0
    for sy, sx in zip(*np.nonzero(solid)):
        if lab[sy, sx]:
            continue
        nxt += 1
        stack = [(sy, sx)]
        comp = []
        while stack:
            y, x = stack.pop()
            if not (0 <= y < solid.shape[0] and 0 <= x < solid.shape[1]) \
                    or lab[y, x] or not solid[y, x]:
                continue
            lab[y, x] = nxt
            comp.append((y, x))
            stack += [(y + 1, x), (y - 1, x), (y, x + 1), (y, x - 1),
                      (y + 1, x + 1), (y - 1, x - 1), (y + 1, x - 1), (y - 1, x + 1)]
        if len(comp) < 260:            # crop 공간 크기 — 화살촉 잔재 ≈200px (캔버스 108px 실측)
            cy = sum(c[0] for c in comp) / len(comp)
            cx = sum(c[1] for c in comp) / len(comp)
            if cx > 140 and cy < 200:
                for y, x in comp:
                    alpha[y, x] = 0.0

    ys, xs = np.nonzero(alpha > 0.05)
    by0, by1, bx0, bx1 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
    crop, alpha = crop[by0:by1, bx0:bx1], alpha[by0:by1, bx0:bx1]
    rgba = np.dstack([crop, alpha[..., None] * 255.0]).astype(np.uint8)
    out = Image.fromarray(rgba, "RGBA")
    # 정사각 캔버스 중앙 배치 (긴 변 → CANVAS·9% 여백)
    side = max(out.size)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(out, ((side - out.width) // 2, (side - out.height) // 2))
    return canvas.resize((CANVAS, CANVAS), Image.LANCZOS)


def stage(im: Image.Image, alpha_mul: float, ink_mix: float) -> Image.Image:
    """빈짐 단 — 시트의 3단(피크→빈짐→소멸)을 α·먹내림으로 유도한다 (시트에 단별 그림이 없다)."""
    a = np.asarray(im).astype(float)
    a[..., :3] = a[..., :3] * (1.0 - ink_mix) + INK * ink_mix
    a[..., 3] *= alpha_mul
    return Image.fromarray(a.astype(np.uint8), "RGBA")


def model_json(key: str) -> dict:
    """판 1장 — qi.py _kigi_model 문법 (중심 원점 계약 · 축 ⑰). 시트는 정사각이라 UV 전면."""
    l, h, t = 1.4 * 16.0, 1.4 * 8.0, 0.04 * 8.0
    faces = {f: {"texture": "#0",
                 "uv": [0, 0, 16, 16] if f in ("north", "south") else [0, 0, 0.01, 0.01]}
             for f in ("north", "south", "east", "west", "up", "down")}
    return {"textures": {"0": f"honcheon:item/{key}", "particle": f"honcheon:item/{key}"},
            "elements": [{"from": [8.0 - l / 2.0, 8.0 - h, 8.0 - t],
                          "to": [8.0 + l / 2.0, 8.0 + h, 8.0 + t], "faces": faces}],
            "gui_light": "front"}


def main() -> None:
    peak = extract()
    frames = [peak, stage(peak, 0.55, 0.30), stage(peak, 0.28, 0.60)]
    made = []
    for prefix, flip in (("sheet_a", False), ("sheet_b", True)):
        for i, fr in enumerate(frames, 1):
            im = fr.transpose(Image.FLIP_LEFT_RIGHT) if flip else fr
            key = f"kigi/{prefix}{i}"
            (TEX / "kigi").mkdir(parents=True, exist_ok=True)
            (MODEL / "kigi").mkdir(parents=True, exist_ok=True)
            (ITEMDEF / "kigi").mkdir(parents=True, exist_ok=True)
            im.save(TEX / f"{key}.png")
            (MODEL / f"{key}.json").write_text(
                json.dumps(model_json(key), ensure_ascii=False, indent=1))
            (ITEMDEF / f"{key}.json").write_text(json.dumps(
                {"model": {"type": "minecraft:model", "model": f"honcheon:item/{key}"}},
                ensure_ascii=False, indent=1))
            made.append(key)
    # 검증 시트 — 어두운/밝은 두 배경에 피크·빈짐 나란히
    prev = Image.new("RGB", (CANVAS * 3, CANVAS * 2))
    for row, bgc in enumerate(((26, 25, 22), (168, 196, 224))):
        for col, fr in enumerate(frames):
            tile = Image.new("RGBA", fr.size, bgc + (255,))
            tile.alpha_composite(fr)
            prev.paste(tile.convert("RGB"), (col * CANVAS, row * CANVAS))
    out = ROOT / "scratch" / "sheet2game" / "plate_preview.png"
    prev.save(out)
    print(f"판 {len(made)}키 구움 — {', '.join(made)}")
    print(f"검증 시트: {out}")


if __name__ == "__main__":
    main()
