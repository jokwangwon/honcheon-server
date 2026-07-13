#!/usr/bin/env python3
"""공백 감사 — 설계와 구현 사이의 틈을 기계가 잰다.

`game_audit.py` 는 config 가 **서로** 정합한지 잰다. 이 도구는 그 위층을 잰다:
**설계가 약속한 것이 실제로 굴러가는가.**

세계는 네 겹이다 — 설계(docs/design/*.md) → config(*.yml) → 엔진(core·mvt·bot) → 플레이어.
각 겹 사이에서 규칙이 조용히 증발한다. 이 도구는 그 증발 지점을 찾는다:

  ① 참조 그래프  — 어느 yml 을 어느 클래스가 읽는가. 아무도 안 읽는 config = 죽은 규칙.
  ② 죽은 코드    — 아무도 안 부르는 public 메서드. 테스트만 부르면 '검증된 죽음'이다.
  ③ 문서 ↔ config — 문서가 약속한 키가 config 에 실제로 있는가 (죽은 약속).
  ④ 도구 커버리지 — 어느 config 절이 어떤 검산 도구에도 안 잡히는가 (재지 않는 규칙).

읽기만 한다 — 아무것도 고치지 않는다.

사용법:
    python3 tools/gap_audit.py              # 전체
    python3 tools/gap_audit.py --graph      # ① 참조 그래프만
    python3 tools/gap_audit.py --dead       # ② 죽은 코드만
    python3 tools/gap_audit.py --docs       # ③ 문서 대조만
    python3 tools/gap_audit.py --coverage   # ④ 도구 커버리지만
    python3 tools/gap_audit.py --quiet      # 총평만

외부 라이브러리 없음. 종료 코드: 죽은 config(❌) 1건 이상이면 1.
"""

from __future__ import annotations

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, "config")
DOCS = os.path.join(ROOT, "docs", "design")
TOOLS = os.path.join(ROOT, "tools")

# 엔진 = 플레이어에게 닿는 코드. tools/ 는 엔진이 아니다 (재기만 한다).
ENGINE_ROOTS = {
    "core": os.path.join(ROOT, "core", "src", "main", "java"),
    # domain = 규칙(core)과 장부(포트)가 만나는 곳. **엔진이다** — 플레이어에게 닿는다.
    # ★ 이 줄이 없으면 눈이 거짓말한다: 도메인만이 부르는 core 메서드가 '죽었다'고 나온다.
    #   (어댑터가 도메인 뒤로 물러난 순간 호출자가 여기로 옮겨왔기 때문이다.)
    "domain": os.path.join(ROOT, "domain", "src", "main", "java"),
    "mvt": os.path.join(ROOT, "server-mvt", "src", "main", "java"),
    "bot": os.path.join(ROOT, "server-bot", "src", "main", "java"),
}
TEST_ROOTS = [
    os.path.join(ROOT, "core", "src", "test", "java"),
    os.path.join(ROOT, "domain", "src", "test", "java"),
]

OK, WARN, FAIL, DEAD = "✅", "⚠️", "❌", "🕳"


class Report:
    def __init__(self, quiet: bool = False):
        self.lines: list[str] = []
        self.quiet = quiet
        self.violations: list[str] = []
        self.warnings: list[str] = []

    def say(self, s: str = "") -> None:
        self.lines.append(s)

    def head(self, s: str) -> None:
        self.say()
        self.say("─" * 72)
        self.say(f"  {s}")
        self.say("─" * 72)

    def violation(self, s: str) -> None:
        self.violations.append(s)
        self.say(f"  {FAIL} {s}")

    def warn(self, s: str) -> None:
        self.warnings.append(s)
        self.say(f"  {WARN} {s}")

    def dump(self) -> None:
        if not self.quiet:
            print("\n".join(self.lines))


# ══════════════════════════════════════════════════════════════════════════════
#  수집 — 파일을 읽어 들인다
# ══════════════════════════════════════════════════════════════════════════════

def walk(root: str, ext: str) -> list[str]:
    out = []
    for dirpath, dirnames, filenames in os.walk(root):
        # 빌드 산출물·캐시는 소스가 아니다
        dirnames[:] = [d for d in dirnames if d not in ("build", "__pycache__", ".git", "run")]
        for fn in filenames:
            if fn.endswith(ext):
                out.append(os.path.join(dirpath, fn))
    return sorted(out)


def read(path: str) -> str:
    with open(path, encoding="utf-8", errors="replace") as fh:
        return fh.read()


def config_files() -> list[str]:
    return walk(CONFIG, ".yml")


def rel(path: str) -> str:
    return os.path.relpath(path, ROOT)


def engine_sources() -> dict[str, list[tuple[str, str]]]:
    """{계층: [(경로, 본문)]} — 엔진 코드만. 테스트·도구 제외."""
    out = {}
    for layer, root in ENGINE_ROOTS.items():
        if os.path.isdir(root):
            out[layer] = [(p, read(p)) for p in walk(root, ".java")]
    return out


def test_sources() -> list[tuple[str, str]]:
    out = []
    for root in TEST_ROOTS:
        if os.path.isdir(root):
            out += [(p, read(p)) for p in walk(root, ".java")]
    return out


def tool_sources() -> list[tuple[str, str]]:
    return [(p, read(p)) for p in walk(TOOLS, ".py")
            if os.path.basename(p) != "gap_audit.py"]   # 자기 자신은 커버리지가 아니다


# ══════════════════════════════════════════════════════════════════════════════
#  YAML 키 경로 추출 (값은 필요 없다 — 뼈대만)
# ══════════════════════════════════════════════════════════════════════════════

KEY_RE = re.compile(r"^(\s*)([A-Za-z_가-힣][\w가-힣.·-]*)\s*:")


def key_paths(text: str) -> tuple[list[str], list[str]]:
    """(최상위 절, 전체 키 경로 'a.b.c') — 들여쓰기로 트리를 세운다."""
    top: list[str] = []
    paths: list[str] = []
    stack: list[tuple[int, str]] = []
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].rstrip()
        if not line.strip() or line.lstrip().startswith("-"):
            continue
        m = KEY_RE.match(line)
        if not m:
            continue
        indent, key = len(m.group(1)), m.group(2)
        while stack and stack[-1][0] >= indent:
            stack.pop()
        stack.append((indent, key))
        path = ".".join(k for _, k in stack)
        paths.append(path)
        if indent == 0:
            top.append(key)
    return top, paths


# ══════════════════════════════════════════════════════════════════════════════
#  ① 참조 그래프 — 어느 yml 을 누가 읽는가
# ══════════════════════════════════════════════════════════════════════════════

UNWIRED_RE = re.compile(r"^unwired:\s*$", re.M)
# unwired 절의 세 키 — 조건 없는 '미룸'은 미룸이 아니라 방치다.
# 값은 한 줄이거나 접은 블록(`>-`) 이다 — 둘 다 읽는다 (접힌 값을 못 읽으면 눈이 '>-' 를 조건이라 부른다).
UNWIRED_KEY_RE = {
    key: re.compile(rf"^\s{{2}}{key}:[ \t]*(.*(?:\n(?:\s{{4,}}\S.*|\s*))*)", re.M)
    for key in ("reason", "condition", "doc")
}


def fold(raw: str) -> str:
    """YAML 접은 스칼라(`>-`) 를 한 줄로 — 값이 없으면 빈 문자열."""
    lines = [ln.strip() for ln in raw.strip().splitlines()]
    if lines and lines[0] in (">-", ">", "|-", "|"):
        lines = lines[1:]
    return " ".join(ln for ln in lines if ln).strip().strip('"').strip("'")


def unwired_marker(path: str) -> dict[str, str] | None:
    """★ 명시적 미배선 표식 — config 최상위 `unwired:` 절.

    죽은 config 를 '살았다'고 부르기 위한 뒷문이 **아니다.** 이 표식은 죽음을 부정하지 않는다 —
    죽음을 **서명한다**: 누가, 왜 미뤘고, 무엇이 성립하면 살리는가. 그래서 세 키를 강제한다
    (reason·condition·doc). 하나라도 없으면 표식은 무효이고 그 config 는 그냥 죽은 규칙이다.

    보고에서도 숨기지 않는다 — ⚠️ 로 조건과 함께 매번 다시 읽힌다. 조용한 죽음만이 금지다.
    """
    body = read(path)
    if not UNWIRED_RE.search(body):
        return None
    out = {}
    for key, pattern in UNWIRED_KEY_RE.items():
        m = pattern.search(body)
        if m and fold(m.group(1)):
            out[key] = fold(m.group(1))
    return out


def audit_graph(rep: Report, engines, tools) -> dict[str, dict]:
    rep.head("① config → 엔진 참조 그래프 — 아무도 안 읽는 규칙은 규칙이 아니다")

    graph: dict[str, dict] = {}
    for cfg in config_files():
        base = os.path.basename(cfg)
        # ★ 파일명만 찾으면 **디렉터리를 통째로 읽는 로더를 못 본다.**
        #   Populace 는 `configDir.resolve("npcs/regions")` 로 폴더를 열고 그 안의 yml 을 전부 읽는다 —
        #   코드 어디에도 "hwasan.yml" 이라는 글자는 없다. 그런데 그 파일은 살아 있다.
        #   눈이 이것을 못 보면 살아 있는 규칙을 죽었다고 부르고, 루프는 멀쩡한 것을 파러 간다.
        #   그러므로 **파일이 든 폴더의 이름**도 함께 찾는다 (config/ 아래 상대 경로).
        #   ※ **하위 폴더일 때만** 폴더를 잣대로 쓴다. config/ 바로 밑의 파일에까지 이 잣대를 대면
        #      needle 이 "config" 가 되어 **모든 코드와 매칭된다** — 눈이 반대 방향으로 거짓말한다.
        #      죽은 것을 살았다고 부르는 눈은, 산 것을 죽었다고 부르는 눈보다 나쁘다.
        holder = os.path.dirname(rel(cfg))            # 예: config/npcs/regions
        needles = [base]
        if holder.startswith("config/"):              # 하위 폴더에 사는 파일만
            needles.append(f'"{holder[len("config/"):]}"')   # resolve("npcs/regions")
        readers = {layer: [] for layer in ENGINE_ROOTS}
        for layer, files in engines.items():
            for path, body in files:
                if any(n in body for n in needles):
                    readers[layer].append(os.path.basename(path)[:-5])
        tool_readers = [os.path.basename(p)[:-3] for p, body in tools if base in body]
        graph[base] = {
            "path": rel(cfg),
            "readers": readers,
            "tools": tool_readers,
            "engine_count": sum(len(v) for v in readers.values()),
        }

    live = {k: v for k, v in graph.items() if v["engine_count"] > 0}
    dead = {k: v for k, v in graph.items() if v["engine_count"] == 0}

    rep.say(f"  config {len(graph)}종 — 엔진 연결 {len(live)} · 미연결 {len(dead)}")
    rep.say()
    for base in sorted(live):
        info = graph[base]
        tag = " ".join(
            f"{layer}:{len(info['readers'][layer])}"
            for layer in ("core", "domain", "mvt", "bot") if info["readers"][layer]
        )
        rep.say(f"    {OK} {base:<32} {tag}")

    if dead:
        rep.say()
        for base in sorted(dead):
            info = graph[base]
            marker = unwired_marker(os.path.join(ROOT, info["path"]))
            if marker is not None:
                missing = [k for k in ("reason", "condition", "doc") if not marker.get(k)]
                if missing:
                    rep.violation(
                        f"{base} — unwired 표식에 {'·'.join(missing)} 가 없다. "
                        f"조건 없는 '미룸'은 미룸이 아니라 방치다 (표식 무효 = 죽은 규칙)"
                    )
                    continue
                doc = marker["doc"]
                if not os.path.isfile(os.path.join(ROOT, doc)):
                    rep.violation(f"{base} — unwired.doc 이 없는 문서를 가리킨다: {doc}")
                    continue
                rep.warn(
                    f"{base} — ★ 명시적 미배선 (아무도 안 읽는다. 그리고 그렇다고 적혀 있다). "
                    f"조건: {marker['condition'][:110]}  → {doc}"
                )
                continue
            if info["tools"]:
                rep.violation(
                    f"{base} — 엔진이 아무도 안 읽는다. 검산 도구({', '.join(info['tools'])})만 읽는다: "
                    f"'재고는 있는데 파는 사람이 없다'"
                )
            else:
                rep.violation(f"{base} — 엔진도 도구도 아무도 안 읽는다. 완전한 죽은 규칙")
    return graph


# ══════════════════════════════════════════════════════════════════════════════
#  ② 죽은 코드 — 아무도 안 부르는 public 메서드
# ══════════════════════════════════════════════════════════════════════════════

# 선언 줄의 표식 — 수식어로 시작한다 (호출 줄에는 수식어가 없다)
DECL_MODIFIER_RE = re.compile(
    r"^[ \t]+(?:@\w+[ \t]+)*"
    r"(?:public|private|protected|static|final|synchronized|abstract|default)[ \t]"
)

# public <반환형> 이름( — 생성자·class/record/enum 선언은 제외
METHOD_RE = re.compile(
    r"^\s+public\s+(?:static\s+|final\s+|synchronized\s+)*"
    r"(?!class\b|record\b|enum\b|interface\b|@)"
    r"[\w.<>,\[\]?\s]+?\s+(\w+)\s*\(",
    re.MULTILINE,
)


def audit_dead_code(rep: Report, engines, tests) -> None:
    rep.head("② 죽은 코드 — 등록만 되고 아무도 안 부르는 public 메서드")

    all_engine = [(p, b) for files in engines.values() for p, b in files]
    test_blob = "\n".join(b for _, b in tests)

    dead_rows: list[tuple[str, str, str, int]] = []
    total = 0
    for path, body in all_engine:
        cls = os.path.basename(path)[:-5]
        for m in METHOD_RE.finditer(body):
            name = m.group(1)
            if name in ("main", "equals", "hashCode", "toString", "run", "clone", "compareTo"):
                continue
            total += 1
            call = f".{name}("
            # 자기 파일 밖의 엔진 코드가 부르는가
            callers = sum(
                1 for p2, b2 in all_engine
                if p2 != path and call in b2
            )
            # 자기 파일 안에서 스스로 부르는 것도 산다 (내부 재사용)
            #
            # ★ 예전에는 ".이름(" 만 셌다 — **점 없는 내부 호출을 놓쳤다.**
            #   rumorInput() 이 attentionInput(...) 을 그냥 부르면 (this. 없이) 눈은 그것을 못 보고
            #   attentionInput 을 '테스트만 부르는 죽은 메서드'라고 **거짓 사망 선고**했다.
            #   플레이어는 매일 그 규칙을 겪는데도.
            #
            #   선언 줄은 호출이 아니다. 선언은 **수식어(public/private/…)를 달고 있다**는 것으로
            #   가른다 — `return foo(...)` 같은 줄에는 수식어가 없다. 애매하면 '호출이 아니다' 쪽으로
            #   센다 (덜 세는 쪽이 안전하다: 죽음을 숨기지 않고 드러내는 방향이다).
            self_calls = body.count(call)
            for mm in re.finditer(rf"(?<![\w.]){re.escape(name)}\s*\(", body):
                line = body[body.rfind("\n", 0, mm.start()) + 1: mm.start()]
                if not DECL_MODIFIER_RE.match(line):
                    self_calls += 1

            if callers == 0 and self_calls == 0:
                t = test_blob.count(call)
                dead_rows.append((cls, name, layer_of(path), t))

    if not dead_rows:
        rep.say(f"  {OK} 죽은 public 메서드 없음")
        return

    # Bukkit/JDA 가 리플렉션으로 부르는 콜백은 죽은 게 아니다
    callbacks = ("on", "execute", "tabComplete")
    real = [r for r in dead_rows if not r[1].startswith(callbacks)]

    rep.say(f"  public 메서드 {total}개 중 호출자 0 = {len(real)}개")
    rep.say()
    by_test = [r for r in real if r[3] > 0]
    by_none = [r for r in real if r[3] == 0]

    if by_test:
        rep.say(f"  {DEAD} 테스트만 부른다 — '검증된 죽음' (규칙은 옳다. 아무도 그 규칙을 쓰지 않을 뿐)")
        for cls, name, layer, t in sorted(by_test):
            rep.say(f"      {layer:<4} {cls}.{name}()  ← 테스트 {t}회 · 프로덕션 0회")
        rep.warn(
            f"테스트만 부르는 메서드 {len(by_test)}개 — 파리티 테스트가 초록이어도 "
            f"플레이어는 그 규칙을 겪지 않는다"
        )
    if by_none:
        rep.say()
        rep.say(f"  {DEAD} 아무도 안 부른다 — 테스트조차")
        for cls, name, layer, _ in sorted(by_none):
            rep.say(f"      {layer:<4} {cls}.{name}()")
        rep.warn(f"호출자도 테스트도 없는 메서드 {len(by_none)}개")


def layer_of(path: str) -> str:
    for layer, root in ENGINE_ROOTS.items():
        if path.startswith(root):
            return layer
    return "?"


# ══════════════════════════════════════════════════════════════════════════════
#  ③ 문서 ↔ config — 문서가 약속한 키가 config 에 있는가
# ══════════════════════════════════════════════════════════════════════════════

# 문서가 config 를 가리키는 방법: `config/foo.yml` 또는 백틱 안의 점표기 키
CFG_REF_RE = re.compile(r"config/([\w/]+\.yml)")
# ★ 아직 없는 config 를 가리키되 **없다고 밝힌** 참조: `config/npc_visual.yml (미신설)`
#   문서가 "이 파일이 있다"고 말하면 거짓말이고, "아직 없다"고 말하면 계획이다. 그 차이를 눈이 본다.
#   (계획도 매번 ⚠️ 로 다시 읽힌다 — 잊히지 않는다.)
PLANNED_REF_RE = re.compile(r"config/([\w/]+\.yml)\s*\(미신설")
# ★ 묻힌 config 를 가리키는 참조: `config/interface.yml` (폐기 — …). 이것은 약속이 아니라 **기록**이다.
#   문서가 "그 파일은 묻었다"고 말하는 것을 눈이 '없는 파일을 가리킨다'고 잡으면, 역사를 지우라고
#   요구하는 셈이 된다. 그래서 세되(보고에 남기되) 공백으로는 세지 않는다.
BURIED_REF_RE = re.compile(r"config/([\w/]+\.yml)`?\s*(?:은|는)?\s*\**\s*\(?폐기")
# 점표기 키 — 파일명(.yml/.md/.py/.json)은 키가 아니다. 그것까지 세면 온통 오탐이 된다
DOTTED_RE = re.compile(r"`([a-z_]+(?:\.[a-z_]+){1,3})`")
FILE_SUFFIX = (".yml", ".md", ".py", ".json", ".java", ".sh")


def audit_docs(rep: Report, graph) -> None:
    rep.head("③ 문서 ↔ config — 문서가 약속한 키가 config 에 실제로 있는가")

    # config 키 경로 색인
    index: dict[str, set[str]] = {}
    for cfg in config_files():
        top, paths = key_paths(read(cfg))
        index[os.path.basename(cfg)] = set(paths) | set(top)

    docs = walk(DOCS, ".md")
    broken: list[tuple[str, str, str]] = []
    missing_cfg: list[tuple[str, str]] = []
    planned: list[tuple[str, str]] = []
    buried: list[tuple[str, str]] = []
    checked = 0

    for doc in docs:
        name = os.path.basename(doc)
        if name == "gap_audit.md":
            continue          # 감사 보고서 자신 — 깨진 참조를 '인용'하는 것이 그 일이다
        body = read(doc)
        refs = {os.path.basename(r) for r in CFG_REF_RE.findall(body)}
        planned_refs = {os.path.basename(r) for r in PLANNED_REF_RE.findall(body)}
        buried_refs = {os.path.basename(r) for r in BURIED_REF_RE.findall(body)}
        for ref in sorted(refs):
            if ref not in index:
                if ref in buried_refs:
                    buried.append((name, ref))       # 묻었다고 적힌 것 — 기록이지 약속이 아니다
                elif ref in planned_refs:
                    planned.append((name, ref))      # 아직 없다고 밝힌 것 — 계획이지 거짓말이 아니다
                else:
                    missing_cfg.append((name, ref))  # 있다고 말하는데 없는 것 — 죽은 약속
                continue
            # 이 문서가 언급한 점표기 키가 그 config 에 있는가
            for dotted in set(DOTTED_RE.findall(body)):
                if dotted.endswith(FILE_SUFFIX):
                    continue                      # 파일명이지 키가 아니다
                head = dotted.split(".")[0]
                # 그 config 의 최상위 절로 시작하는 키만 대조한다 (오탐 억제)
                tops = {p.split(".")[0] for p in index[ref]}
                if head not in tops:
                    continue
                checked += 1
                if dotted not in index[ref]:
                    broken.append((name, ref, dotted))

    if missing_cfg:
        for name, ref in sorted(set(missing_cfg)):
            rep.violation(f"{name} → config/{ref} 를 가리키는데 그런 파일이 없다 (죽은 약속)")

    if planned:
        for name, ref in sorted(set(planned)):
            rep.warn(
                f"{name} → config/{ref} 는 ★ 미신설이라고 문서가 밝혔다 (계획된 약속 — 거짓말은 아니다). "
                f"신설 조건이 문서 머리에 있는가를 사람이 본다"
            )

    if buried:
        rep.say()
        for name, ref in sorted(set(buried)):
            rep.say(f"  🪦 {name} → config/{ref} 는 묻혔다 (문서가 그렇게 적고 있다 — 기록이지 약속이 아니다)")

    if broken:
        rep.say()
        for name, ref, dotted in sorted(set(broken)):
            rep.warn(f"{name} 이 `{dotted}` 를 약속하는데 {ref} 에 그 키가 없다")
    if not missing_cfg and not broken and not planned:
        rep.say(f"  {OK} 문서가 가리키는 config 파일·키 {checked}건 — 모두 실재한다")
    else:
        rep.say()
        rep.say(f"  (점표기 키 {checked}건 대조)")


# ══════════════════════════════════════════════════════════════════════════════
#  ④ 검산 도구 커버리지 — 어느 절을 아무도 재지 않는가
# ══════════════════════════════════════════════════════════════════════════════

def audit_coverage(rep: Report, engines, tools) -> None:
    rep.head("④ 검산 도구 커버리지 — 아무 도구도 재지 않는 config 절")

    engine_blob = "\n".join(b for files in engines.values() for _, b in files)
    tool_blob = "\n".join(b for _, b in tools)

    rows: list[tuple[str, list[str], list[str]]] = []
    for cfg in config_files():
        base = os.path.basename(cfg)
        top, _ = key_paths(read(cfg))
        # 스키마 설명용 절은 규칙이 아니다
        top = [t for t in top if t not in ("schema", "_schema", "meta", "notes", "note")]
        unmeasured = [t for t in top if f'"{t}"' not in tool_blob and f"'{t}'" not in tool_blob
                      and t not in tool_blob]
        unused = [t for t in top if t not in engine_blob]
        if unmeasured or unused:
            rows.append((base, unmeasured, unused))

    ghost_total = 0
    for base, unmeasured, unused in sorted(rows):
        # 엔진도 안 쓰고 도구도 안 재는 절 = 유령 절
        ghosts = sorted(set(unmeasured) & set(unused))
        if not ghosts:
            continue
        ghost_total += len(ghosts)
        rep.say(f"  {DEAD} {base}")
        rep.say(f"      유령 절 (엔진도 안 쓰고 도구도 안 잰다): {', '.join(ghosts)}")

    if ghost_total:
        rep.warn(
            f"유령 절 {ghost_total}개 — 규칙으로 적혀 있고, 굴러가지 않고, "
            f"아무도 그 사실을 재지 않는다"
        )
    else:
        rep.say(f"  {OK} 모든 절이 엔진에 쓰이거나 도구에 잡힌다")


# ══════════════════════════════════════════════════════════════════════════════

def main() -> int:
    ap = argparse.ArgumentParser(description="공백 감사 — 설계와 구현 사이의 틈")
    ap.add_argument("--graph", action="store_true", help="① 참조 그래프만")
    ap.add_argument("--dead", action="store_true", help="② 죽은 코드만")
    ap.add_argument("--docs", action="store_true", help="③ 문서 대조만")
    ap.add_argument("--coverage", action="store_true", help="④ 도구 커버리지만")
    ap.add_argument("--quiet", action="store_true", help="총평만")
    args = ap.parse_args()

    picked = args.graph or args.dead or args.docs or args.coverage
    rep = Report(quiet=False)

    rep.say("═" * 72)
    rep.say("  공백 감사 — 설계가 약속한 것이 실제로 굴러가는가")
    rep.say("═" * 72)

    engines = engine_sources()
    tests = test_sources()
    tools = tool_sources()

    graph = {}
    if not picked or args.graph:
        graph = audit_graph(rep, engines, tools)
    if not picked or args.dead:
        audit_dead_code(rep, engines, tests)
    if not picked or args.docs:
        if not graph:
            graph = audit_graph(Report(quiet=True), engines, tools)
        audit_docs(rep, graph)
    if not picked or args.coverage:
        audit_coverage(rep, engines, tools)

    rep.say()
    rep.say("═" * 72)
    n_v, n_w = len(rep.violations), len(rep.warnings)
    if n_v == 0 and n_w == 0:
        rep.say(f"  총평: {OK} 죽은 규칙 0건 — 설계한 것이 전부 굴러간다")
    else:
        rep.say(f"  총평: 죽은 config {n_v}건 · 경고 {n_w}건")
        if n_v:
            rep.say()
            rep.say(f"  ── 죽은 규칙 ({FAIL}) — 설계는 있는데 세계에 없다")
            for i, v in enumerate(rep.violations, 1):
                rep.say(f"    {i:2}. {v}")
        if n_w:
            rep.say()
            rep.say(f"  ── 경고 ({WARN}) — 굴러가지만 절반이다")
            for i, w in enumerate(rep.warnings, 1):
                rep.say(f"    {i:2}. {w}")
    rep.say("═" * 72)

    if args.quiet:
        rep.lines = rep.lines[-(n_v + n_w + 8):]
    rep.dump()
    return 1 if n_v else 0


if __name__ == "__main__":
    sys.exit(main())
