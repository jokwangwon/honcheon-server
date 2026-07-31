#!/usr/bin/env python3
"""공간덤프 대조 — 실세계와 도면이 어긋난 곳을 센다 (B-194 파이프라인 ②).

「사진 찍고 좌표 찍는」 왕복의 반대편 눈: /혼천 서장무대 덤프 (콘솔함 가능) 가 뜬
stage_dump.json 을 도면과 전수 대조한다. 첫 실측(2026-07-31)이 어긋남 0종 +
보이지 않는 고아 식구 둘을 잡았다 — 블록이 아니라 엔티티가 병이었다.

사용법:  python3 tools/stage_dump_diff.py <무대이름> [덤프경로]
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from stage_render import load   # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DUMP = ROOT / "run/mvt/plugins/HoncheonMVT/stage_dump.json"


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    name = sys.argv[1]
    dump_path = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_DUMP
    cfg, layers, bad = load(name)
    if bad:
        print("❌ 도면이 어긋났다 — stage_render 를 먼저 통과시켜라")
        return 1
    d = json.loads(dump_path.read_text(encoding="utf-8"))
    w, dep = cfg["meta"]["size"]
    real = {}
    for c, y, r, m in d["blocks"]:
        real[(c, y, r)] = m.replace("minecraft:", "").split("[")[0]
    mismatch = {}
    for y, (_, grid) in enumerate(layers):
        for r in range(dep):
            for c in range(w):
                want = grid[r][c].replace("minecraft:", "").split("[")[0]
                got = real.get((c, y, r), "air")
                if want != got:
                    mismatch.setdefault((want, got), []).append((c, y, r))
    print(f"어긋남 유형 {len(mismatch)}종 · 엔티티 {len(d.get('entities') or [])}")
    for (want, got), cells in sorted(mismatch.items(), key=lambda kv: -len(kv[1])):
        print(f"  ❌ 기대 {want!r} → 실제 {got!r} : {len(cells)}곳  예) {cells[:4]}")
    for e in d.get("entities") or []:
        print(f"  · {e['type']} ({e['x']},{e['y']},{e['z']}) name={e['name']!r} "
              f"visible_default={e['visible_default']}")
    return 1 if mismatch else 0


if __name__ == "__main__":
    sys.exit(main())
