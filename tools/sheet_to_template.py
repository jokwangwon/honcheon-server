#!/usr/bin/env python3
"""컨셉 시트 → 검기 점 템플릿 변환기.

★ 왜: 파라미터(호·폭·테이퍼)로 시트를 근사하는 길은 세 번 다듬어도 "다르다"가 나왔다
  (2026-07-22 사용자: "시트처럼 완벽히 그대로 될때까지"). 그래서 근사를 버리고
  **그림 자체를 점으로 옮긴다** — 시트의 초승달 픽셀을 팔레트로 분류해 정규화 좌표
  템플릿(JSON)으로 굽고, 게임은 그 점들을 베기면에 그대로 놓는다.

사용:
  python3 tools/sheet_to_template.py            # 추출 + 분류 검증 이미지 + JSON
출력:
  scratch/sheet2game/tps_classified.png         # 분류 검증(눈으로 확인용)
  config/kigi_template_tps.json                 # 3인칭 템플릿 (정본)
  run/mvt-test/plugins/HoncheonMVT/config/kigi_template_tps.json (사본)

JSON 형식: {"name":..., "points":[[x,y,"잉크",크기],...]}
  x,y ∈ [-1,1] — 초승달 bbox 정규화 (y는 아래가 +, 화면 좌표 그대로)
  스윕 공개 순서는 게임 쪽이 y(위→아래)로 정렬해 정한다 — 시트의 베기 방향 화살표가 위→아래다.
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SHEET = ROOT / "2db88914-39b2-4e7d-a322-1c6ac80ca6de.jpg"

# 등록부 잉크 (docs/design/vfx_primitives.md 와 같은 값)
PALETTE = {
    "먹": (38, 46, 54),
    "청회": (124, 143, 152),
    "청록": (82, 158, 146),
    "옥": (166, 214, 199),
    "청백": (226, 240, 238),
}
# 잉크별 게임 점 크기 — 날은 잘고 밝게, 잔향은 굵고 성기게 (시트 질감)
SIZES = {"청백": 0.28, "옥": 0.30, "청록": 0.32, "청회": 0.33, "먹": 0.35}   # r8: 0.22 는 하늘에 씻겼다 — 수는 count 로 벌고 크기는 가독 하한

# 시트 안 영역 (원본 1024×717 좌표) + 크롭 내부의 글자·조준선 제외 사각형(크롭 좌표)
REGIONS = {
    "tps": (555, 50, 875, 400),   # 3인칭 시점 가이드 (큰 초승달)
    "fps": (30, 75, 440, 240),    # 1인칭 시점 가이드
    "peak": (20, 325, 210, 510),  # 수명 시트 · 피크 (무주석 대조용)
}
EXCLUDE = {
    "tps": [(0, 0, 50, 40), (265, 0, 320, 350)],      # 좌상 「가이드」 · 우측 주석 열
    "fps": [(0, 0, 410, 10), (168, 62, 212, 110), (380, 0, 410, 20)],  # 제목 줄 · 조준선 · 우상 글자
    "peak": [(0, 0, 190, 10), (0, 145, 190, 185)],    # 제목 조각 · 하단 캡션
}


def classify(crop: np.ndarray):
    """픽셀 → 팔레트 분류. 배경은 국소 배경색과의 거리로 뺀다.

    ★ 먹(38,46,54)과 배경(~52,58,62)은 14px 거리라 아슬아슬하다 — 전역 임계가 아니라
      배경 표본 클러스터와 팔레트 최근접을 **경쟁**시켜 가른다.
    """
    h, w, _ = crop.shape
    flat = crop.reshape(-1, 3).astype(int)
    # 배경 표본: 네 모서리 24×24
    corners = np.concatenate([
        crop[:24, :24].reshape(-1, 3), crop[:24, -24:].reshape(-1, 3),
        crop[-24:, :24].reshape(-1, 3), crop[-24:, -24:].reshape(-1, 3)])
    bg = corners.mean(0)
    names = list(PALETTE)
    cols = np.array([PALETTE[n] for n in names])
    d_pal = np.linalg.norm(flat[:, None, :] - cols[None, :, :], axis=2)  # (N,5)
    d_bg = np.linalg.norm(flat - bg, axis=1)                             # (N,)
    best = d_pal.argmin(1)
    # 배경이 팔레트보다 가깝거나, 어느 쪽에도 멀면 배경 취급
    is_fg = (d_pal.min(1) < d_bg) & (d_pal.min(1) < 60)
    lab = np.where(is_fg, best, -1).reshape(h, w)
    return lab, names


def drop_big_grey(lab: np.ndarray, names, max_px=70):
    """화살표·글자 제거 — 청회/먹 분류의 **큰** 연결 성분만 지운다.

    ★ 왜 크기인가: 주석 화살표(선+촉)와 글자는 수백 px 성분, 알갱이는 ~30px 이하다.
      청백·옥·청록의 큰 성분(날 띠)은 초승달 본체라 건드리면 안 된다.
    (scipy 없이 — 저장소 도구는 표준 라이브러리+numpy/PIL 만 쓴다)
    """
    h, w = lab.shape
    for cls in ("청회", "먹"):
        k = names.index(cls)
        mask = lab == k
        seen = np.zeros_like(mask)
        for sy, sx in zip(*np.nonzero(mask)):
            if seen[sy, sx]:
                continue
            stack, comp = [(sy, sx)], []
            seen[sy, sx] = True
            while stack:
                y, x = stack.pop()
                comp.append((y, x))
                for ny, nx in ((y-1,x),(y+1,x),(y,x-1),(y,x+1)):
                    if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        stack.append((ny, nx))
            if len(comp) > max_px:
                for y, x in comp:
                    lab[y, x] = -1
    return lab


def drop_orphan_dark(lab: np.ndarray, names, reach=6):
    """화살표 잔재 제거 2차 — **밝은 몸체에서 떨어진** 먹/청회 점을 지운다.

    ★ 왜: 큰 성분 제거 후에도 화살표 선의 점선 조각(≤70px)이 빈 공간에 남는다.
      진짜 잔향 먹점은 시트에서 항상 몸체(청백/옥/청록) 근방에 흩어져 있다 —
      reach px 안에 밝은 픽셀이 하나도 없는 어두운 점은 주석 잔재다.
    """
    bright = np.isin(lab, [names.index(n) for n in ("청백", "옥", "청록")])
    near = bright.copy()
    for dy in range(-reach, reach + 1):        # Chebyshev 팽창 (scipy 없이)
        for dx in range(-reach, reach + 1):
            if dy or dx:
                near |= np.roll(np.roll(bright, dy, 0), dx, 1)
    for cls in ("청회", "먹"):
        k = names.index(cls)
        lab[(lab == k) & ~near] = -1
    return lab


def render_check(lab: np.ndarray, names, out: Path):
    h, w = lab.shape
    img = np.full((h, w, 3), (52, 58, 62), np.uint8)
    for i, n in enumerate(names):
        img[lab == i] = PALETTE[n]
    Image.fromarray(img).resize((w * 2, h * 2), Image.NEAREST).save(out)


def to_template(lab: np.ndarray, names, n_points: int, rng):
    """분류 픽셀 → n_points 개 템플릿. 밀도를 보존하려 픽셀에서 균일 표본을 뽑는다."""
    ys, xs = np.nonzero(lab >= 0)
    if len(xs) == 0:
        sys.exit("추출 실패 — 전경 픽셀이 없다")
    # ★ 청백 가중 1.6 (r5): 날 림의 연속성이 실루엣을 정의한다 — 균일 표본으로는 림이 끊겨 읽혔다
    w = np.ones(len(xs))
    w[lab[ys, xs] == names.index("청백")] = 1.15   # r6: 1.6 은 몸통을 깎았다
    idx = rng.choice(len(xs), size=min(n_points, len(xs)), replace=False, p=w / w.sum())
    # bbox 정규화 [-1,1]
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    pts = []
    for i in idx:
        x = (xs[i] - x0) / (x1 - x0) * 2 - 1
        y = (ys[i] - y0) / (y1 - y0) * 2 - 1
        ink = names[lab[ys[i], xs[i]]]
        pts.append([round(float(x), 4), round(float(y), 4), ink, SIZES[ink]])
    ratio = (y1 - y0) / (x1 - x0)
    return pts, ratio


def main():
    a = np.asarray(Image.open(SHEET).convert("RGB")).astype(int)
    rng = np.random.default_rng(42)   # 재현 가능해야 "같은 시트 → 같은 템플릿"
    outdir = ROOT / "scratch/sheet2game"
    outdir.mkdir(parents=True, exist_ok=True)
    for name, (x0, y0, x1, y1) in REGIONS.items():
        crop = a[y0:y1, x0:x1]
        lab, names = classify(crop)
        for ex0, ey0, ex1, ey1 in EXCLUDE.get(name, []):
            lab[ey0:ey1, ex0:ex1] = -1
        lab = drop_big_grey(lab, names)
        lab = drop_orphan_dark(lab, names)
        render_check(lab, names, outdir / f"{name}_classified.png")
        n = {"tps": 2600, "fps": 1000, "peak": 760}[name]  # r8: 시트 전경 2.7만px 에 다가서는 밀도 (점당 count2 와 짝)
        pts, ratio = to_template(lab, names, n, rng)
        counts = {}
        for p in pts:
            counts[p[2]] = counts.get(p[2], 0) + 1
        tpl = {"name": name, "aspect_h_over_w": round(float(ratio), 3), "points": pts}
        path = ROOT / f"config/kigi_template_{name}.json"
        path.write_text(json.dumps(tpl, ensure_ascii=False), encoding="utf-8")
        print(f"{name}: 전경 {int((lab >= 0).sum())}px → 점 {len(pts)} · 세로/가로 {ratio:.2f} · {counts}")


if __name__ == "__main__":
    main()
