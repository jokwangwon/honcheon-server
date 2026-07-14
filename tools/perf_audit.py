#!/usr/bin/env python3
"""성능 검산 — 예산과 코드를 대질(對質)시킨다.

이 서버는 한 번도 계측된 적이 없다. 그런데 config/performance.yml 에는 예산이 적혀 있다
(npc_logic 6ms · 파티클 600/시야 · vfx 120 …). **적혀 있다는 것과 지켜진다는 것은 다른 말이다.**
계측(spark·Metrics)을 붙이기 전에, 정적으로 답할 수 있는 세 가지를 먼저 답한다:

  ① 등록제  — performance.yml 의 예산 항목을 **읽는 코드가 있는가**.
              읽는 자가 없으면 그 예산은 예산이 아니라 주석이다. 그리고 그 값이 다른 yml 이나
              코드 리터럴에 **한 번 더** 적혀 있다면, 두 수치는 언젠가 갈라진다 (정본이 둘이므로).
  ② 매 틱 도는 것 — runTaskTimer 로 등록된 티커와 주기, @EventHandler 리스너 수.
              "함께 돌 때 무슨 일이 나는지 아무도 모른다"의 **목록**을 먼저 만든다.
  ③ 한 틱 폭탄 — 한 호출 안에서 상한 없이 세계를 쓰는 자리. 중첩 루프의 경계를 상수까지 풀어
              반복 횟수를 추정하고, 그 자리가 틱 예산 가드(nanoTime 대조) 아래 있는지 본다.
              호출 그래프를 한 겹 펴서 **진입점 하나가 여는 총 쓰기 수**까지 합산한다.

사용법:
    python3 tools/perf_audit.py            # 사람이 읽는 표
    python3 tools/perf_audit.py --json     # 기계 판독
    python3 tools/perf_audit.py --strict   # 위반이 있으면 exit 1 (CI 용)
"""

import argparse
import json
import os
import re
import sys

import yaml

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_ROOTS = [
    os.path.join(ROOT, "server-mvt", "src", "main", "java"),
    os.path.join(ROOT, "core", "src", "main", "java"),
]
CONFIG_DIR = os.path.join(ROOT, "config")

# 세계를 쓰는 호출 — 이것이 한 틱에 몇 번 도는가가 곧 정지 시간이다
WRITE_CALLS = ("setType(", "setBlockData(", "setBiome(", "spawnEntity(", "breakNaturally(")

# 블록 쓰기 하나가 5~20µs (조명 재계산 · 청크 팔레트 · 이웃 갱신 · 패킷). 중간값 10µs 로 환산한다.
NANOS_PER_WRITE = 10e-6
# 한 호출에서 이만큼을 넘게 쓰면 '폭탄'. 20,000 × 10µs = 200ms = 틱 4개를 통째로 먹는다.
BOMB_THRESHOLD = 20_000
# 루프 경계를 못 푼 자리 — 이만큼만 넘어도 의심한다 (모르는 곱셈이 하나 더 있으므로)
SUSPECT_THRESHOLD = 5_000

BUDGET_GUARD = re.compile(r"System\.nanoTime\(\)\s*-\s*\w+\s*<|TICK_BUDGET|TickBudget\.")
OPENS_PERF_YML = re.compile(r'resolve\(\s*"performance\.yml"|"config/performance\.yml"')


# ────────────────────────────────────────────────────────────────────
#  Java 훑기 — 가벼운 구문 분석 (중괄호 짝맞추기)
# ────────────────────────────────────────────────────────────────────

def java_files():
    for root in JAVA_ROOTS:
        for dirpath, _, names in os.walk(root):
            for n in sorted(names):
                if n.endswith(".java"):
                    yield os.path.join(dirpath, n)


def strip_comments(src):
    """주석·문자열 리터럴을 지운다 — 주석 속 숫자에 속지 않기 위해서다.
    (이 저장소는 주석에 예산을 자주 적는다. "performance.yml npc_logic 6ms" 같은 줄이
     코드처럼 보이면 검산이 거짓말을 한다. 그것이 바로 우리가 잡으려는 병이다.)"""
    src = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


def strip_strings(src):
    return re.sub(r'"(\\.|[^"\\])*"', '""', src)


METHOD_RE = re.compile(
    r"^[ \t]*(?:public|private|protected|static|final|synchronized|abstract|\s)*"
    r"[\w<>\[\],.?\s]+\s+(\w+)\s*\([^;{]*\)\s*(?:throws [\w.,\s]+)?\{",
    re.M)


def methods(src):
    """(이름, 시작줄, 본문). 중괄호를 세어 본문을 잘라 낸다."""
    out = []
    for m in METHOD_RE.finditer(src):
        start = m.end() - 1
        depth, i = 0, start
        while i < len(src):
            if src[i] == "{":
                depth += 1
            elif src[i] == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        out.append((m.group(1), src.count("\n", 0, m.start()) + 1, src[start:i + 1]))
    return out


def constants(src):
    """static int 상수표 — 루프 경계와 티커 주기를 푸는 데 쓴다 (SITE_R=62 → 반복 125칸).
    final 이 아닌 것도 담는다: 티커 주기는 yml 이 덮어쓰는 static 필드다 (tickerPeriod = 40)."""
    out = {}
    for m in re.finditer(r"static\s+(?:final\s+)?(?:int|long)\s+(\w+)\s*=\s*(-?\d+)", src):
        out[m.group(1)] = int(m.group(2))
    return out


def split_args(src, open_paren):
    """괄호 안 인자를 depth 를 세며 자른다 (정규식이 못 하는 일 — 람다·중첩 괄호 때문)."""
    args, depth, cur, i = [], 0, [], open_paren + 1
    while i < len(src):
        c = src[i]
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                args.append("".join(cur).strip())
                return args, i
            depth -= 1
        if c == "," and depth == 0:
            args.append("".join(cur).strip())
            cur = []
        else:
            cur.append(c)
        i += 1
    return args, i


# ─── ③ 한 틱 폭탄 ───

FOR_RE = re.compile(r"for\s*\(\s*(?:int|long)\s+(\w+)\s*=\s*([^;]+);\s*\1\s*(<=|<)\s*([^;]+);")


def loop_trips(lo, op, hi, consts):
    """루프 한 겹의 반복 횟수. 못 풀면 None — 모르는 것을 아는 척하지 않는다."""
    def val(expr):
        m = re.fullmatch(r"(-?)(\w+)", expr.strip().replace(" ", ""))
        if not m:
            return None
        sign, name = m.groups()
        v = int(name) if name.lstrip("-").isdigit() else consts.get(name)
        return None if v is None else (-v if sign == "-" else v)

    a, b = val(lo), val(hi)
    if a is None or b is None:
        return None
    return max(0, b - a + (1 if op == "<=" else 0))


def scan_method(body, consts):
    """이 메서드 안에서 세계를 쓰는 자리의 '최악 누적 반복수' + 못 푼 루프 겹수."""
    worst, worst_unknown, sites = 0, 0, 0
    stack, depth, i = [], 0, 0
    while i < len(body):
        c = body[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            while stack and stack[-1][0] > depth:
                stack.pop()
        else:
            m = FOR_RE.match(body, i)
            if m:
                stack.append((depth + 1, loop_trips(m.group(2), m.group(3), m.group(4), consts)))
                i = m.end()
                continue
            if any(body.startswith(w, i) for w in WRITE_CALLS):
                sites += 1
                mult, unknown = 1, 0
                for _, t in stack:
                    if t is None:
                        unknown += 1
                    else:
                        mult *= t
                if mult > worst:
                    worst, worst_unknown = mult, unknown
        i += 1
    return worst, worst_unknown, sites


CALL_RE = re.compile(r"(?<![\w.])(\w+)\s*\(\s*world\b")   # 조성기의 관용구: f(world, ...)


def bomb_scan(path, src, consts):
    """메서드별 추정 + 호출 그래프 한 겹 롤업 (진입점 하나가 여는 총 쓰기 수)."""
    rel = os.path.relpath(path, ROOT)
    table = {}
    for name, line, body in methods(src):
        worst, unknown, sites = scan_method(body, consts)
        table[name] = {
            "file": rel, "method": name, "line": line,
            "writes": worst, "unresolved_loops": unknown, "sites": sites,
            "guarded": bool(BUDGET_GUARD.search(body)),
            "calls": sorted({m.group(1) for m in CALL_RE.finditer(body)}),
            "body_len": len(body),
        }

    # 롤업 — f(world, ...) 로 부르는 자식의 쓰기를 더한다 (조성기는 이 형태로만 조립된다)
    for e in table.values():
        e["rollup"] = e["writes"] + sum(
            table[c]["writes"] for c in e["calls"] if c in table and c != e["method"])
        e["rollup_unknown"] = e["unresolved_loops"] + sum(
            table[c]["unresolved_loops"] for c in e["calls"] if c in table and c != e["method"])

    out = []
    for e in table.values():
        if e["guarded"] or e["sites"] + len(e["calls"]) == 0:
            continue
        if e["rollup"] >= BOMB_THRESHOLD:
            e["kind"] = "폭탄"          # 상한을 정적으로 풀었다 — 값이 크다
        elif e["rollup"] >= SUSPECT_THRESHOLD and e["rollup_unknown"] > 0:
            e["kind"] = "폭탄"
        elif e["sites"] > 0 and e["unresolved_loops"] >= 2:
            # 루프 경계가 **런타임 값**이다 (반경·높이가 인자로 온다 — 봉우리 r=60, 사구 r=40).
            # 정적으로는 상한이 없다 = 정적 검사가 잡을 수 있는 것의 한계. 그래서 따로 세운다.
            e["kind"] = "상한 미상"
        else:
            continue
        out.append(e)
    return out


# ─── ② 티커 ───

def ticker_scan(path, src, consts):
    rel, out = os.relpath if False else os.path.relpath(path, ROOT), []
    for m in re.finditer(r"runTaskTimer(Asynchronously)?\s*\(", src):
        args, _ = split_args(src, m.end() - 1)
        if len(args) < 4:
            continue
        task, period = re.sub(r"\s+", " ", args[1]), args[3].rstrip("L")
        p = consts.get(period, period)
        try:
            p = int(p)
        except (TypeError, ValueError):
            pass
        out.append({
            "file": rel, "line": src.count("\n", 0, m.start()) + 1,
            "task": task[:44], "period_ticks": p,
            "hz": round(20 / p, 2) if isinstance(p, int) and p > 0 else None,
            "async": bool(m.group(1)),
        })
    return out


# ─── ① 등록제 ───

def flatten(node, prefix=""):
    if isinstance(node, dict):
        for k, v in node.items():
            yield from flatten(v, f"{prefix}.{k}" if prefix else str(k))
    elif isinstance(node, (int, float)) and not isinstance(node, bool):
        yield prefix, node


def camel(snake):
    head, *rest = snake.split("_")
    return head + "".join(w.capitalize() for w in rest)


def other_yml_defs():
    """다른 yml 이 같은 이름의 값을 또 정의하는가 — 정본이 둘이면 언젠가 갈라진다."""
    defs = {}
    for dirpath, _, names in os.walk(CONFIG_DIR):
        for n in sorted(names):
            if not n.endswith((".yml", ".yaml")) or n == "performance.yml":
                continue
            p = os.path.join(dirpath, n)
            try:
                with open(p, encoding="utf-8") as f:
                    doc = yaml.safe_load(f)
            except Exception:
                continue
            for k, v in flatten(doc or {}):
                defs.setdefault(k.split(".")[-1], []).append(
                    (os.path.relpath(p, ROOT), k, v))
    return defs


def tool_sources():
    """파이썬 도구도 읽는 자다 — 감사가 performance.yml 의 값을 검산 기준으로 읽는다
    (예: motion_audit 이 load_test.combat_cluster_size 로 군집 예산을 잰다).
    perf_audit 자신과 selftest 는 뺀다 — 눈이 저를 읽는 자로 세면 안 되고,
    시험용 언급이 키를 살려 주면 안 된다."""
    tdir = os.path.join(ROOT, "tools")
    out = {}
    for n in sorted(os.listdir(tdir)):
        if not n.endswith(".py") or n == "perf_audit.py" or n.endswith("_selftest.py"):
            continue
        p = os.path.join(tdir, n)
        try:
            with open(p, encoding="utf-8") as f:
                out[p] = f.read()
        except OSError:
            continue
    return out


def registry_audit(perf, code, stripped, tools_src=None):
    readers_of_perf = [p for p, s in stripped.items() if OPENS_PERF_YML.search(s)]
    dup = other_yml_defs()
    tools_src = tools_src if tools_src is not None else {}

    # 【간접 배선 — metrics.probes】 Metrics 는 subsystem_budget_ms **맵 전체**를 적재하고
    # probes(티커→예산항목)의 **값**으로 꺼내 쓴다. 키 이름이 Java 리터럴로 안 박히는 것이
    # 설계다 (등록제: 코드는 수치를 모른다). 눈이 이 간접 참조를 모르면 살아 있는 예산을
    # 죽었다고 말한다 — B-028 에서 실제로 그랬다 (effect_ticker 등 4키를 죽은 예산으로 몰았다).
    probe_targets = {}
    mm = perf.get("metrics") if isinstance(perf, dict) else None
    pm = mm.get("probes") if isinstance(mm, dict) else None
    if isinstance(pm, dict):
        for ticker, item in pm.items():
            probe_targets.setdefault(str(item), []).append(str(ticker))
    probe_loaders = [p for p in readers_of_perf
                     if '"probes"' in code[p] and '"subsystem_budget_ms"' in code[p]]

    rows = []
    for key, value in flatten(perf):
        leaf = key.split(".")[-1]
        # 읽는 자 = performance.yml 을 실제로 여는 파일 중, 이 키를 문자열로 조회하는 것
        readers = [os.path.relpath(p, ROOT) for p in readers_of_perf
                   if f'"{leaf}"' in code[p]]
        # probes 가 이 예산 항목을 가리키고, 그 맵을 통째로 적재하는 자가 있으면 — 읽힌다
        if not readers and key.startswith("tick_budget.subsystem_budget_ms.") \
                and leaf in probe_targets and probe_loaders:
            readers = ["%s (metrics.probes: %s)" % (os.path.relpath(p, ROOT),
                                                    ", ".join(probe_targets[leaf]))
                       for p in probe_loaders]
        # 파이썬 도구 — **같은 줄**에서 performance.yml 을 파고 이 키를 집는 것만 센다
        # (주석 속 언급이나 딴 yml 을 파는 코드가 키를 살려 주면 그것이 곧 거짓말이다)
        if not readers:
            readers = [os.path.relpath(p, ROOT) for p, s in tools_src.items()
                       if any("performance.yml" in ln and f'"{leaf}"' in ln
                              for ln in s.splitlines())]
        # 같은 이름을 다른 yml 이 또 정의하는가 (값이 다르면 그게 곧 불일치다)
        others = [(f, k, v) for f, k, v in dup.get(leaf, []) if v != value]
        same = [(f, k, v) for f, k, v in dup.get(leaf, []) if v == value]
        # 코드에 리터럴로 박혔는가 — 같은 줄에 키 이름(snake 또는 camel)이 있는 경우만 센다
        hard = []
        if isinstance(value, int) and value > 2:
            pat = re.compile(r"(?<![\w.])%d(?![\w.])" % value)
            names = (leaf, camel(leaf))
            for p, s in stripped.items():
                for ln in s.splitlines():
                    if pat.search(ln) and any(n in ln for n in names):
                        hard.append(os.path.relpath(p, ROOT))
                        break
        rows.append({
            "key": key, "value": value,
            "readers": readers,
            "redefined_elsewhere": [{"file": f, "key": k, "value": v} for f, k, v in others],
            "duplicated_same_value": [f for f, _, _ in same],
            "hardcoded_in": sorted(set(hard)),
        })
    return rows, [os.path.relpath(p, ROOT) for p in readers_of_perf]


# ────────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--strict", action="store_true", help="위반이 있으면 exit 1")
    args = ap.parse_args()

    with open(os.path.join(CONFIG_DIR, "performance.yml"), encoding="utf-8") as f:
        perf = yaml.safe_load(f)

    code, stripped, consts = {}, {}, {}
    for p in java_files():
        with open(p, encoding="utf-8") as f:
            raw = f.read()
        code[p] = raw
        stripped[p] = strip_comments(raw)
        consts[p] = constants(strip_strings(stripped[p]))

    bombs, tickers, listeners = [], [], {}
    for p in code:
        s = strip_strings(stripped[p])
        bombs += bomb_scan(p, s, consts[p])
        tickers += ticker_scan(p, stripped[p], consts[p])
        n = len(re.findall(r"@EventHandler", stripped[p]))
        if n:
            listeners[os.path.relpath(p, ROOT)] = n

    rows, perf_readers = registry_audit(perf, code, stripped, tool_sources())
    bombs.sort(key=lambda b: (b["kind"] != "폭탄", -b["rollup"]))
    tickers.sort(key=lambda t: (t["period_ticks"] if isinstance(t["period_ticks"], int) else 9999))

    unread = [r for r in rows if not r["readers"]]
    conflicts = [r for r in rows if r["redefined_elsewhere"]]

    if args.json:
        print(json.dumps({"registry": rows, "perf_yml_readers": perf_readers,
                          "tickers": tickers, "listeners": listeners, "bombs": bombs},
                         ensure_ascii=False, indent=2))
        return 1 if (args.strict and (unread or bombs)) else 0

    bar = "═" * 84
    print(bar)
    print("  ① 등록제 — performance.yml 의 예산을 읽는 코드가 있는가")
    print(bar)
    print("  performance.yml 을 여는 파일: %s" % (
        ", ".join(os.path.basename(p) for p in perf_readers) if perf_readers else "★ 하나도 없다"))
    print("  예산 항목 %d개 중 읽히는 것 %d개 · 안 읽히는 것 %d개"
          % (len(rows), len(rows) - len(unread), len(unread)))
    print()
    for r in rows:
        mark = "✓" if r["readers"] else "✗"
        note = ""
        if r["readers"]:
            note = "→ " + ", ".join(os.path.basename(x) for x in r["readers"])
        elif r["redefined_elsewhere"]:
            d = r["redefined_elsewhere"][0]
            note = "★ 정본이 둘 — %s 의 %s = %s (여긴 %s)" % (
                os.path.basename(d["file"]), d["key"], d["value"], r["value"])
        elif r["duplicated_same_value"]:
            note = "△ %s 에도 같은 값이 적혀 있다 (지금은 같다 — 언젠가 갈라진다)" % ", ".join(
                os.path.basename(x) for x in r["duplicated_same_value"])
        elif r["hardcoded_in"]:
            note = "△ 코드에 박힘: %s" % ", ".join(os.path.basename(x) for x in r["hardcoded_in"])
        else:
            note = "— 읽는 자 없음 (죽은 예산)"
        print("  %s %-46s %-8s %s" % (mark, r["key"], r["value"], note))

    print()
    print(bar)
    print("  ② 매 틱 도는 것 — 티커 %d개 · @EventHandler %d개" % (
        len(tickers), sum(listeners.values())))
    print(bar)
    print("  %-46s %8s %8s  %s" % ("티커", "주기(틱)", "빈도", "위치"))
    print("  " + "─" * 80)
    for t in tickers:
        print("  %-46s %8s %6s Hz  %s:%d" % (
            t["task"], t["period_ticks"],
            t["hz"] if t["hz"] is not None else "?",
            os.path.basename(t["file"]), t["line"]))
    print()
    for f, n in sorted(listeners.items(), key=lambda kv: -kv[1]):
        print("  리스너 %-40s %2d개" % (os.path.basename(f), n))

    print()
    print(bar)
    print("  ③ 한 틱 폭탄 — 예산 가드 없이 세계를 쓰는 자리")
    print(bar)
    if not bombs:
        print("  없음.")
    for b in bombs[:16]:
        approx = "" if b["rollup_unknown"] == 0 else " 이상 (못 푼 루프 %d겹)" % b["rollup_unknown"]
        print("  ✗ [%s] %s.%s()  (%s:%d)" % (
            b["kind"], os.path.basename(b["file"])[:-5], b["method"],
            os.path.basename(b["file"]), b["line"]))
        if b["kind"] == "폭탄":
            print("      추정 블록 쓰기 %s회%s · 쓰기 지점 %d곳 · 틱 예산 가드 없음"
                  % (f"{b['rollup']:,}", approx, b["sites"]))
            print("      → 10µs/블록이면 이 한 호출이 메인 스레드를 %.1f초 세운다."
                  % (b["rollup"] * NANOS_PER_WRITE))
        else:
            print("      루프 경계가 런타임 값이다 (%d겹) — 정적으로는 상한이 **없다**."
                  % b["unresolved_loops"])
            print("      쓰기 지점 %d곳 · 틱 예산 가드 없음. 반경이 인자로 오는 자리다"
                  % b["sites"])
            print("      (봉우리 r=60×h=40 · 사구 r=40 — 호출자가 값을 정하므로 코드만 봐선 못 센다).")
    print()
    print("  ※ 정적 검사의 한계: 반경이 인자로 오면 상한을 셀 수 없다. '상한 미상'이 그것이다 —")
    print("     이것은 '작다'는 뜻이 아니라 '코드만 봐선 모른다'는 뜻이고, 그래서 더 나쁘다.")
    print("     여기서 정적 검사는 끝나고 계측(spark · /혼천 계측)이 시작된다.")
    print()
    print("  처방 — 조성기는 한 줄도 고치지 않는다. 호출부만 감싼다:")
    print("      TickBudget.build(plugin, 이름, world, w -> 원래호출(w, cx, cy, cz, …), …)")
    print("      (docs/design/performance.md · TickBudget.java)")

    if args.strict and (unread or bombs):
        print()
        print("  ✗ strict: 죽은 예산 %d개 · 한 틱 폭탄 %d개" % (len(unread), len(bombs)))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
