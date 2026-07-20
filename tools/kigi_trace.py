#!/usr/bin/env python3
"""레퍼런스 검기 프레임 3장을 **픽셀 그대로 추출**한다 (사용자 지정 순서).

왜: 지금까지 검기 실루엣을 수식(sin 아크)으로 만들었는데, 레퍼런스의 실제 그림은
    ① 짧고 굵은 대각 획 → ② 왼쪽으로 휘어 뻗은 발톱형 → ③ 넓은 ∩ 아치 다.
    수식으로 흉내낼 모양이 아니다. 사용자가 영상에서 뽑아 준 스크린샷에서
    **그려진 픽셀을 직접 읽어** 마스크로 쓴다.

순서(사용자 지정): image2 = 1번 · image1 = 2번 · image3 = 3번
출력: tools/kigi_shapes.json  (프레임마다 KIGI_W×KIGI_H 불린 마스크)
"""
import json
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
# (파일, 캔버스 crop(left, top, right, bottom))  — 에디터 화면에서 그림판만 잘라낸다
SOURCES = [
    ("kigi_reference/image2.png", (185, 5, 515, 390)),    # 1번 — 짧은 대각 획
    ("kigi_reference/image1.png", (70, 95, 500, 425)),    # 2번 — 휘어 뻗은 발톱
    ("kigi_reference/image3.png", (72, 88, 495, 380)),    # 3번 — 넓은 ∩ 아치
]
OUT_W, OUT_H = 64, 26        # 검기 텍스처 해상도 (가로로 긴 획)


def drawn_mask(img: Image.Image) -> np.ndarray:
    """캔버스에서 **그려진 픽셀**만 True. 배경은 회색 체커(무채색)이므로
    '어둡고 붉은 기가 도는' 픽셀만 고른다 (그림은 어두운 마룬으로 그려져 있다)."""
    a = np.asarray(img.convert("RGB")).astype(int)
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    mx, mn = a.max(axis=2), a.min(axis=2)
    chroma = mx - mn
    # 그림: 어둡다(밝기<150) + 무채색 체커가 아니다(chroma>=12) + 빨강 우세
    return (mx < 150) & (chroma >= 12) & (r >= b)


def to_grid(mask: np.ndarray) -> np.ndarray:
    """마스크를 OUT_W×OUT_H 격자에 **원본 비율을 지켜** 앉힌다.

    ★ 왜 비율을 지키나: 처음엔 그림을 격자에 꽉 채워 늘렸다. 그러자 세로로 긴 1번 프레임
    (짧고 굵은 대각 획)이 **가로로 뭉개져** 알아볼 수 없게 됐다. 레퍼런스의 모양을 쓰겠다면서
    모양을 망가뜨린 것이다. 그래서 긴 쪽에 맞춰 줄이고 짧은 쪽은 여백으로 둔다.
    """
    h, w = mask.shape
    scale = min(OUT_W / w, OUT_H / h)          # 넘치지 않게 긴 쪽 기준
    tw, th = max(1, int(w * scale)), max(1, int(h * scale))
    small = np.zeros((th, tw), dtype=bool)
    for gy in range(th):
        for gx in range(tw):
            y0, y1 = int(gy * h / th), max(int((gy + 1) * h / th), int(gy * h / th) + 1)
            x0, x1 = int(gx * w / tw), max(int((gx + 1) * w / tw), int(gx * w / tw) + 1)
            cell = mask[y0:y1, x0:x1]
            if cell.size and cell.mean() >= 0.25:
                small[gy, gx] = True
    out = np.zeros((OUT_H, OUT_W), dtype=bool)
    oy, ox = (OUT_H - th) // 2, (OUT_W - tw) // 2   # 가운데 앉힌다
    out[oy:oy + th, ox:ox + tw] = small
    return out


def main():
    shapes = []
    for name, crop in SOURCES:
        img = Image.open(ROOT / name).crop(crop)
        m = drawn_mask(img)
        # 그림이 실제로 있는 범위로 좁힌 뒤 격자화 — 캔버스 여백이 모양을 눌러 찌그러뜨리지 않게
        ys, xs = np.where(m)
        if len(xs) == 0:
            raise SystemExit(f"{name}: 그려진 픽셀을 못 찾았다 — crop 을 확인하라")
        m = m[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
        grid = to_grid(m)
        # ★ 에디터 UI 잔재 청소: **끝에서 끝까지 꽉 찬 세로선**을 지운다 (캔버스 테두리다).
        #   3번 프레임 x=49 가 26칸 전부 채워져 있었다 — 그림의 다른 열은 최대 16 이다.
        #   남기면 검기에 작대기가 붙는다. "고립된 열" 기준으로는 안 잡혔다(옆 열에 그림이 닿아서).
        full = grid.shape[0] * 0.9
        for gx in range(grid.shape[1]):
            if grid[:, gx].sum() >= full:
                grid[:, gx] = False
        shapes.append(grid.tolist())
        # 눈으로 확인할 미리보기
        prev = Image.fromarray((grid * 255).astype(np.uint8)).resize(
            (OUT_W * 8, OUT_H * 8), Image.NEAREST)
        prev.save(ROOT / "scratch" / f"trace_{name}")
        print(f"{name}: 원본 {m.shape} → 격자 {grid.shape} · 채운 칸 {int(grid.sum())}")
    (ROOT / "tools" / "kigi_shapes.json").write_text(
        json.dumps({"w": OUT_W, "h": OUT_H, "frames": shapes}), encoding="utf-8")
    print("저장: tools/kigi_shapes.json")


if __name__ == "__main__":
    main()
