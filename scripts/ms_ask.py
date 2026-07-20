#!/usr/bin/env python3
"""로컬 MagicSpells 문서 검색 (BM25).

왜 임베딩이 아니라 BM25(어휘 검색)인가
-------------------------------------
이 말뭉치에 던지는 질문은 대부분 **정확한 식별자**다 — `maxAngle`,
`relativeOffset`, `spell-class`, `.instant.DummySpell`, `EffectLibLineEffect`.
식별자 조회에서는 어휘 일치가 의미 임베딩보다 *더* 정확하다: 임베딩은
`maxAngle` 과 `minAngle` 을 거의 같은 점으로 뭉개지만, BM25 는 둘을 다른
토큰으로 정확히 가른다. 게다가 모델도, GPU 도, API 키도, 네트워크도 필요
없다. 그래서 Okapi BM25 를 (약 60줄로) 직접 구현한다 — 의존성을 하나
더 다는 것보다 낫다. `rank_bm25` 가 이미 깔려 있어도 굳이 쓰지 않는다:
아래 구현은 토큰 단위 posting 을 직접 들고 있어야 질의가 즉답이 된다.

이중 언어(한/영) 토크나이저
---------------------------
말뭉치는 영어(wiki 558쪽)인데 우리 노트와 질문은 한국어다. 공백 분할만으로는
한국어가 잡히지 않는다. 그래서:
  1. 소문자화하고 영숫자로 자르되 식별자 문자(`-`, `_`, `.`)는 **살린다** —
     `spell-class`, `.instant.DummySpell` 이 한 토큰으로 남는다.
  2. 식별자는 쪼갠 형태도 함께 색인한다 (`maxAngle` → `max`, `angle`) —
     "max angle" 로 물어도 찾힌다.
  3. 한글 구간은 **글자 2-gram** 을 뿜는다 — "파라미터" → 파라/라미/미터.
     한국어 질문이 한국어 노트에 걸린다.

쓰는 법
-------
    python3 scripts/ms_ask.py "how do I cast a spell from console"
    python3 scripts/ms_ask.py "ArcEffect 파라미터"
    python3 scripts/ms_ask.py "maxAngle" -n 3
    python3 scripts/ms_ask.py "..." --rebuild
"""

from __future__ import annotations

import argparse
import math
import os
import pickle
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CORPUS_GLOBS = [
    (REPO / "scratch" / "msdocs" / "wiki", "*.md"),
    (REPO / "docs" / "knowledge" / "magicspells", "*.md"),
]
CACHE_DIR = REPO / "scratch" / "ms-index"
CACHE_FILE = CACHE_DIR / "bm25.pkl"
CACHE_VERSION = 3

K1 = 1.5
B = 0.75

MIN_CHUNK = 200
MAX_CHUNK = 1500

# ────────────────────────────── 토크나이저 ──────────────────────────────

# 식별자 문자를 살린 토큰. 한글/CJK 는 따로 처리하므로 여기서는 ASCII 만.
_TOKEN_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._\-]*")
# 한글 구간 (완성형 + 자모 + 호환 자모)
_HANGUL_RE = re.compile(r"[가-힣ᄀ-ᇿ㄰-㆏]+")
# camelCase / PascalCase 경계
_CAMEL_RE = re.compile(r"[A-Z]+(?![a-z])|[A-Z][a-z0-9]*|[a-z0-9]+")
_SEP_RE = re.compile(r"[._\-]+")


def _split_forms(raw: str) -> list[str]:
    """식별자를 쪼갠 형태들. `maxAngle` → [max, angle], `spell-class` → [spell, class]."""
    parts: list[str] = []
    for piece in _SEP_RE.split(raw):
        if not piece:
            continue
        sub = _CAMEL_RE.findall(piece)
        if len(sub) > 1:
            parts.extend(sub)
        parts.append(piece)
    return [p.lower() for p in parts if len(p) > 1]


def tokenize(text: str) -> list[str]:
    """영어 식별자 + 쪼갠 형태 + 한글 2-gram 을 함께 뿜는다."""
    out: list[str] = []

    for m in _TOKEN_RE.finditer(text):
        raw = m.group(0)
        # 문장 끝 마침표 같은 꼬리 구두점은 턴다 (질의/문서 양쪽에 똑같이 적용된다).
        tok = raw.strip("._-").lower()
        if not tok:
            continue
        out.append(tok)
        if _SEP_RE.search(tok) or not tok.isalnum() or any(c.isupper() for c in raw):
            for form in _split_forms(raw):
                if form != tok:
                    out.append(form)

    for m in _HANGUL_RE.finditer(text):
        run = m.group(0)
        if len(run) == 1:
            out.append(run)
        else:
            out.extend(run[i : i + 2] for i in range(len(run) - 1))
            # 짧은 낱말(2~4자)은 통째로도 넣어 정확 일치에 가중치를 준다.
            if 2 <= len(run) <= 4:
                out.append(run)

    return out


# ─────────────────────────────── 청킹 ───────────────────────────────

_HEADING_RE = re.compile(r"^(#{1,3})\s+(.*?)\s*#*$")
_FENCE_RE = re.compile(r"^\s*(```|~~~)")


def _flush(chunks, title, path, body_lines):
    body = "\n".join(body_lines).strip()
    if not body:
        return
    if len(body) <= MAX_CHUNK:
        pieces = [body]
    else:
        # 너무 큰 절(예: Commands.md 의 거대한 표)은 줄 경계로 자른다.
        pieces, cur, size = [], [], 0
        for line in body.split("\n"):
            if size + len(line) > MAX_CHUNK and cur:
                pieces.append("\n".join(cur))
                cur, size = [], 0
            cur.append(line)
            size += len(line) + 1
        if cur:
            pieces.append("\n".join(cur))
    for piece in pieces:
        chunks.append({"title": title, "path": list(path), "text": piece})


def chunk_markdown(text: str, title: str) -> list[dict]:
    chunks: list[dict] = []
    stack: list[str] = []
    body: list[str] = []
    in_fence = False

    for line in text.split("\n"):
        if _FENCE_RE.match(line):
            in_fence = not in_fence
            body.append(line)
            continue
        m = None if in_fence else _HEADING_RE.match(line)
        if m:
            _flush(chunks, title, stack, body)
            body = []
            level = len(m.group(1))
            stack = stack[: level - 1]
            while len(stack) < level - 1:
                stack.append("")
            stack.append(m.group(2).strip())
        else:
            body.append(line)
    _flush(chunks, title, stack, body)

    # 너무 짧은 조각은 뒤 조각에 붙인다 (헤딩만 있고 본문이 한 줄인 경우).
    merged: list[dict] = []
    for ch in chunks:
        if merged and len(merged[-1]["text"]) < MIN_CHUNK and merged[-1]["title"] == ch["title"]:
            merged[-1]["text"] += "\n\n" + ch["text"]
            if not [p for p in merged[-1]["path"] if p]:
                merged[-1]["path"] = ch["path"]
        else:
            merged.append(ch)
    return merged


# ─────────────────────────────── 색인 ───────────────────────────────


def source_files() -> list[Path]:
    files: list[Path] = []
    for root, pattern in CORPUS_GLOBS:
        if root.is_dir():
            files.extend(sorted(root.glob(pattern)))
    return files


def stamp(files: list[Path]) -> dict:
    return {str(f): f.stat().st_mtime_ns for f in files}


def build_index(files: list[Path], verbose=True) -> dict:
    chunks: list[dict] = []
    for f in files:
        try:
            text = f.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        title = f.stem.replace("-", " ")
        for ch in chunk_markdown(text, title):
            ch["file"] = str(f.relative_to(REPO))
            chunks.append(ch)

    postings: dict[str, dict[int, int]] = defaultdict(dict)
    lengths: list[int] = []
    for i, ch in enumerate(chunks):
        heading = " ".join([ch["title"], *ch["path"]])
        # 헤딩 경로는 두 번 넣어 제목 일치에 가중치를 준다.
        toks = tokenize(ch["text"]) + tokenize(heading) * 2
        tf = Counter(toks)
        for t, n in tf.items():
            postings[t][i] = n
        lengths.append(len(toks) or 1)

    n = len(chunks)
    avgdl = sum(lengths) / n if n else 1.0
    idf = {t: math.log(1 + (n - len(d) + 0.5) / (len(d) + 0.5)) for t, d in postings.items()}

    if verbose:
        print(
            f"[index] {len(files)}개 파일 · {n}개 청크 · {len(postings)}개 토큰",
            file=sys.stderr,
        )
    return {
        "version": CACHE_VERSION,
        "chunks": chunks,
        "postings": dict(postings),
        "idf": idf,
        "lengths": lengths,
        "avgdl": avgdl,
        "stamp": stamp(files),
    }


def load_index(rebuild=False) -> dict:
    files = source_files()
    if not files:
        sys.exit(f"말뭉치를 찾지 못했다: {[str(r) for r, _ in CORPUS_GLOBS]}")
    if not rebuild and CACHE_FILE.exists():
        try:
            with CACHE_FILE.open("rb") as fh:
                idx = pickle.load(fh)
            if idx.get("version") == CACHE_VERSION and idx.get("stamp") == stamp(files):
                return idx
        except Exception:
            pass
    idx = build_index(files)
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    tmp = CACHE_FILE.with_suffix(".tmp")
    with tmp.open("wb") as fh:
        pickle.dump(idx, fh, protocol=pickle.HIGHEST_PROTOCOL)
    os.replace(tmp, CACHE_FILE)
    return idx


# ─────────────────────────────── 질의 ───────────────────────────────


def search(idx: dict, query: str, top_n: int) -> list[tuple[float, int]]:
    q = tokenize(query)
    if not q:
        return []
    lengths, avgdl = idx["lengths"], idx["avgdl"]
    scores: dict[int, float] = defaultdict(float)
    for term, qtf in Counter(q).items():
        posting = idx["postings"].get(term)
        if not posting:
            continue
        w = idx["idf"][term] * (1 + math.log(qtf))
        for doc, tf in posting.items():
            denom = tf + K1 * (1 - B + B * lengths[doc] / avgdl)
            scores[doc] += w * tf * (K1 + 1) / denom
    return sorted(((s, d) for d, s in scores.items()), key=lambda x: (-x[0], x[1]))[:top_n]


def excerpt(text: str, query: str, width=420) -> str:
    """질의 토큰이 가장 많이 걸리는 줄 근처를 잘라 보여 준다."""
    qset = set(tokenize(query))
    lines = text.split("\n")
    best, best_hits = 0, -1
    for i, line in enumerate(lines):
        hits = len(qset & set(tokenize(line)))
        if hits > best_hits:
            best, best_hits = i, hits
    if best_hits <= 0:
        return text[:width].strip() + ("…" if len(text) > width else "")
    out, size = [], 0
    for i in range(best, len(lines)):
        if size and size + len(lines[i]) > width:
            break
        out.append(lines[i])
        size += len(lines[i]) + 1
    head = lines[best - 1].strip() if best > 0 and size < width else ""
    if head:
        out.insert(0, head)
    return "\n".join(out).strip()


def main() -> int:
    ap = argparse.ArgumentParser(description="MagicSpells 문서 로컬 검색 (BM25)")
    ap.add_argument("query", nargs="*", help="찾을 말")
    ap.add_argument("-n", "--num", type=int, default=5, help="보여 줄 결과 수 (기본 5)")
    ap.add_argument("--rebuild", action="store_true", help="캐시를 버리고 다시 색인한다")
    args = ap.parse_args()

    query = " ".join(args.query).strip()
    idx = load_index(rebuild=args.rebuild)
    if not query:
        if args.rebuild:
            return 0
        ap.error("찾을 말을 적어라")

    hits = search(idx, query, args.num)
    if not hits:
        print(f"'{query}' — 걸린 것이 없다.")
        return 1

    for rank, (score, doc) in enumerate(hits, 1):
        ch = idx["chunks"][doc]
        path = " › ".join(p for p in ch["path"] if p) or "(문서 머리)"
        print(f"\n\033[1m[{rank}] {ch['file']}\033[0m  ({score:.2f})")
        print(f"    § {ch['title']} › {path}")
        print()
        for line in excerpt(ch["text"], query).split("\n"):
            print("    " + line)
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
