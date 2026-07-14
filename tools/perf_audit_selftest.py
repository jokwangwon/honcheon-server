#!/usr/bin/env python3
"""perf_audit 의 등록제 눈을 일부러 깨뜨려 시험한다.

【왜】 B-028 에서 눈에 두 간접 배선을 가르쳤다 — ① metrics.probes(티커→예산항목)를 통해
subsystem_budget_ms 를 소비하는 Metrics 의 길 · ② performance.yml 을 파는 파이썬 감사 도구.
간접 참조를 배운 눈은 반드시 이 질문에 답해야 한다: **여전히 죽은 예산을 죽었다고 말하는가?**
— 못 하면 그것은 배움이 아니라 눈감음이다. 여기의 변이들이 그 답을 강제한다.
"""

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

import perf_audit  # noqa: E402


def rows_of(perf, code, tools_src):
    rows, _ = perf_audit.registry_audit(perf, code, code, tools_src)
    return {r["key"]: r["readers"] for r in rows}


def main():
    cases = []

    def case(name, ok, detail=""):
        cases.append((name, ok, detail))

    # ── 합성 재료: performance.yml 을 여는 가짜 Metrics + 키를 파는 가짜 도구 ──
    perf = {
        "tick_budget": {"subsystem_budget_ms": {
            "direct_read": 8,       # Java 가 이름으로 집는다
            "probe_read": 5,        # probes 간접 배선으로만 산다
            "dead_budget": 6,       # 아무도 안 읽는다 — 죽었다고 말해야 한다
        }},
        "metrics": {"probes": {"some_ticker": "probe_read"}},
        "load_test": {"tool_read": 20, "tool_dead": 30},
    }
    metrics_java = ('load(resolve("performance.yml")); get("tick_budget");'
                    ' get("subsystem_budget_ms"); get("probes"); get("direct_read");')
    code = {"/fake/Metrics.java": metrics_java}
    tools_src = {
        "/fake/tools/motion_audit.py":
            'cluster = dig(cfg, "performance.yml", "load_test", "tool_read", default=20)\n'
            '# "tool_dead" — 이름만 부르는 주석 (같은 줄에 등록부 파일명이 없다)\n'
            'x = conf["tool_dead"]  # 딴 등록부를 파는 코드다\n',
    }
    rows = rows_of(perf, code, tools_src)

    # ① ★ 변이 — 간접 배선을 배웠어도 진짜 죽은 예산은 여전히 죽었다고 말한다
    case("죽은 예산은 여전히 죽었다고 말한다 (subsystem)",
         rows["tick_budget.subsystem_budget_ms.dead_budget"] == [],
         str(rows["tick_budget.subsystem_budget_ms.dead_budget"]))

    # ② 직접 조회는 종전대로 산다
    case("이름으로 집는 키는 종전대로 읽힌다",
         bool(rows["tick_budget.subsystem_budget_ms.direct_read"]),
         str(rows["tick_budget.subsystem_budget_ms.direct_read"]))

    # ③ probes 가 가리키고, 맵을 통째로 적재하는 자가 있으면 — 읽힌다 (간접 배선)
    case("metrics.probes 간접 배선을 읽는 자로 센다",
         any("metrics.probes: some_ticker" in r
             for r in rows["tick_budget.subsystem_budget_ms.probe_read"]),
         str(rows["tick_budget.subsystem_budget_ms.probe_read"]))

    # ④ ★ 변이 — probes 에서 빼면 그 키는 다시 죽는다 (간접 배선은 배선이지 사면이 아니다)
    perf_cut = {**perf, "metrics": {"probes": {}}}
    rows_cut = rows_of(perf_cut, code, tools_src)
    case("probes 배선을 끊으면 다시 죽는다",
         rows_cut["tick_budget.subsystem_budget_ms.probe_read"] == [],
         str(rows_cut["tick_budget.subsystem_budget_ms.probe_read"]))

    # ⑤ ★ 변이 — probes 를 적재하는 자가 없으면 간접 배선도 안 산다 (yml 혼자서는 못 산다)
    rows_noload = rows_of(perf, {"/fake/Other.java":
                                 'load(resolve("performance.yml")); get("direct_read");'},
                          tools_src)
    case("probes 적재자가 없으면 간접 배선도 죽는다",
         rows_noload["tick_budget.subsystem_budget_ms.probe_read"] == [],
         str(rows_noload["tick_budget.subsystem_budget_ms.probe_read"]))

    # ⑥ 파이썬 도구가 **같은 줄**에서 performance.yml 을 파고 키를 집으면 — 읽힌다
    case("파이썬 도구의 같은 줄 조회를 읽는 자로 센다",
         any(r.endswith("motion_audit.py") for r in rows["load_test.tool_read"]),
         str(rows["load_test.tool_read"]))

    # ⑦ ★ 변이 — 이름만 부르는 줄(주석·딴 등록부)은 키를 못 살린다
    case("performance.yml 없는 줄의 이름 언급은 안 쳐준다",
         rows["load_test.tool_dead"] == [],
         str(rows["load_test.tool_dead"]))

    # ⑧ 실물 — 지금 저장소에서 간접 배선이 실제로 붙고, 죽은 예산이 0 이다
    live = subprocess.run([sys.executable, str(ROOT / "tools" / "perf_audit.py")],
                          cwd=ROOT, capture_output=True, text=True, check=False)
    case("실물: metrics.probes 간접 배선이 붙는다",
         "(metrics.probes:" in live.stdout, live.stdout[:400])
    case("실물: 파이썬 도구 읽는 자가 붙는다 (combat_cluster_size)",
         "motion_audit.py" in live.stdout, live.stdout[:400])
    case("실물: 안 읽히는 것 0개", "안 읽히는 것 0개" in live.stdout, live.stdout[:400])

    ok = True
    print("══ perf_audit 의 눈을 시험한다 — 간접 배선을 배운 눈이 눈감지 않는가 ══")
    for name, caught, detail in cases:
        print(("✓ " if caught else "✗ ") + name)
        if not caught:
            print("   " + detail)
        ok &= caught
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
