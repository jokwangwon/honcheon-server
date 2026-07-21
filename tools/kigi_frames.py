#!/usr/bin/env python3
"""검기 프레임 세트 — 삼합(청회·나 0.78)으로 **3프레임 vs 5프레임**을 만들어 견준다.

  한 번의 베기 = 시작 → 흐름 → 전체 → (잔상) → 스러짐.
  · 3프레임: 베는 찰나(46%) → 최대 → 스러짐          — 스윙당 9틱 (3틱/장) 기존 시스템 값
  · 5프레임: 시작(28%) → 흐름(62%) → 최대 → 잔상 → 스러짐 — 스윙당 10틱 (2틱/장)
  정지 스트립(어두운/밝은 배경)과 재생 GIF 를 함께 낸다 — 장수의 차이는 **움직임**에서 갈린다.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import kigi_forge as F
from PIL import Image

OUT = F.OUT


def frame(frac: float, decay: float) -> Image.Image:
    """베기의 한 장 — frac 만큼 그어졌고, decay 만큼 스러졌다."""
    pts = F.sil_arc()
    n = max(12, int(len(pts) * frac))
    sub = pts[:n]
    im = F.samhap(sub, F.palette("청회"))
    if decay > 0:
        # 스러짐 — 전체 알파를 낮추고, 폭이 물결치듯 사그라든 느낌은 알파 얼룩으로
        a = im.split()[3].point(lambda v: int(v * (1.0 - decay * 0.72)))
        im.putalpha(a)
    return im


SETS = {
    "3F": [(0.46, 0.0), (1.00, 0.0), (1.00, 0.65)],
    "5F": [(0.28, 0.0), (0.62, 0.0), (1.00, 0.0), (1.00, 0.35), (1.00, 0.75)],
}


def main():
    out = Path("작업물/검기/프레임")
    out.mkdir(parents=True, exist_ok=True)
    cell = OUT * 4
    pad = 8
    for name, specs in SETS.items():
        frames = [frame(fr, dc) for fr, dc in specs]
        for i, f in enumerate(frames, 1):
            f.save(out / f"{name}_{i}.png")
        # 스트립 (어두운/밝은)
        cols = len(frames)
        sheet = Image.new("RGB", ((cell + pad) * cols + pad, (cell + pad) * 2 + pad), (16, 18, 21))
        for j, bgc in enumerate([(24, 27, 31), (196, 203, 208)]):
            for i, f in enumerate(frames):
                big = f.resize((cell, cell), Image.NEAREST)
                bg = Image.new("RGBA", (cell, cell), (*bgc, 255))
                bg.alpha_composite(big)
                sheet.paste(bg.convert("RGB"), (pad + i * (cell + pad), pad + j * (cell + pad)))
        sheet.save(out / f"{name}_스트립.png")
        # 재생 GIF — 인게임 틱 그대로: 3F=3틱/장(150ms) · 5F=2틱/장(100ms) + 빈 마무리 1장
        dur = 150 if name == "3F" else 100
        gif_frames = []
        for f in frames:
            bg = Image.new("RGBA", (cell, cell), (24, 27, 31, 255))
            bg.alpha_composite(f.resize((cell, cell), Image.NEAREST))
            gif_frames.append(bg.convert("RGB"))
        gif_frames.append(Image.new("RGB", (cell, cell), (24, 27, 31)))   # 사라진 뒤
        gif_frames[0].save(out / f"{name}.gif", save_all=True, append_images=gif_frames[1:],
                           duration=[dur] * len(frames) + [420], loop=0)
        total = dur * len(frames)
        print(f"  {name}: {len(frames)}장 · {dur}ms/장 · 한 스윙 {total}ms → {out}/{name}.gif")


if __name__ == "__main__":
    main()
