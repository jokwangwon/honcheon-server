#!/usr/bin/env python3
"""perf_audit 의 등록제 눈을 일부러 깨뜨려 시험한다.

【왜】 B-028 에서 눈에 두 간접 배선을 가르쳤다 — ① metrics.probes(티커→예산항목)를 통해
subsystem_budget_ms 를 소비하는 Metrics 의 길 · ② performance.yml 을 파는 파이썬 감사 도구.
간접 참조를 배운 눈은 반드시 이 질문에 답해야 한다: **여전히 죽은 예산을 죽었다고 말하는가?**
— 못 하면 그것은 배움이 아니라 눈감음이다. 여기의 변이들이 그 답을 강제한다.

【B-107】 판정에 파일 차원이 들어갔다: 한 파일이 yml 을 여럿 연다 (SkillEngine 은
performance.yml 과 skill_mechanics.yml 을 둘 다 연다). 종전 눈은 "leaf 이름이 그 파일
어딘가에 있다"로 쳤고, 그래서 skill_mechanics 를 파는 줄의 max_targets_default 가
performance.yml 의 같은 이름 키를 살려 줬다 (B-106 — 그 ✓ 는 거짓이었다). 이제 leaf 는
performance.yml 에서 온 값이 흐르는 **문장 안**에서만 산다. 아래 변이들이 그 성질을 박는다:
다른 yml 을 읽는 코드에 같은 leaf 이름이 있어도 performance.yml 키는 죽었다고 말해야 한다.
"""

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

import perf_audit  # noqa: E402


def rows_of(perf, code, tools_src):
    rows, _ = perf_audit.registry_audit(perf, code, tools_src)
    return {r["key"]: r["readers"] for r in rows}


def main():
    cases = []

    def case(name, ok, detail=""):
        cases.append((name, ok, detail))

    # ── 합성 재료 — 실물의 골격을 흉내 낸다 (B-107 뒤로 판정이 문장 단위라서,
    #    "파일에 문자열이 있다"가 아니라 변수 사슬이 실제로 이어져야 산다) ──
    perf = {
        "tick_budget": {"subsystem_budget_ms": {
            "direct_read": 8,       # Java 가 performance.yml 사슬 안에서 이름으로 집는다
            "probe_read": 5,        # probes 간접 배선으로만 산다
            "dead_budget": 6,       # 아무도 안 읽는다 — 죽었다고 말해야 한다
        }},
        "metrics": {"probes": {"some_ticker": "probe_read"}},
        "skills": {
            "same_leaf_trap": 5,    # ★ B-106 의 무대 — 같은 leaf 를 skill_mechanics 가 갖는다
            "window_ticks": 1,      # performance.yml 사슬로 집는다 — 살아야 한다
        },
        "particles": {"chain_read": 4000},   # 두 홉 사슬 (pf → section → get)
        "load_test": {"tool_read": 20, "tool_dead": 30},
    }
    # 실물 Metrics 의 골격 — file → root → tb → sub 의 변수 사슬
    metrics_java = (
        'Path file = configDir.resolve("performance.yml");'
        ' Map<String, Object> root = RulesConfig.load(file);'
        ' Map<String, Object> tb = section(root, "tick_budget");'
        ' Map<String, Object> sub = section(tb, "subsystem_budget_ms");'
        ' Map<String, Object> pm = section(root, "probes");'
        ' int d = num(sub.get("direct_read"));')
    # 실물 SkillEngine 의 골격 — performance.yml 과 skill_mechanics.yml 을 **둘 다** 연다
    engine_java = (
        'Map<String, Object> pf = RulesConfig.load(cfg.resolve("performance.yml"));'
        ' Map<String, Object> mech = RulesConfig.load(cfg.resolve("skill_mechanics.yml"));'
        ' int trap = intValue(mech.get("same_leaf_trap"));'
        ' int dw = intValue(RulesConfig.section(pf, "skills").get("window_ticks"));'
        ' Map<String, Object> particles = RulesConfig.section(pf, "particles");'
        ' int g = intValue(particles.get("chain_read"));')
    code = {"/fake/Metrics.java": metrics_java, "/fake/SkillEngine.java": engine_java}
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

    # ② 변수 사슬(file→root→tb→sub)로 집는 키는 읽힌다
    case("performance.yml 사슬로 집는 키는 읽힌다",
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
                                 'Map<String, Object> root ='
                                 ' load(resolve("performance.yml"));'
                                 ' int d = num(root.get("direct_read"));'},
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

    # ── B-107 — 파일 차원: 어느 파일이 아니라 **어느 yml 을 여는 문맥**이 보증한다 ──

    # ⑧ ★ 변이 — performance.yml 도 여는 파일이 **딴 yml 사슬**에서 같은 leaf 를 집어도,
    #    performance.yml 키는 죽었다고 말한다 (B-106: max_targets_default 의 거짓 ✓)
    case("딴 yml 을 읽는 코드의 같은 leaf 는 못 살린다 (B-106 재현)",
         rows["skills.same_leaf_trap"] == [],
         str(rows["skills.same_leaf_trap"]))

    # ⑨ 같은 파일의 performance.yml 사슬(pf) 조회는 종전대로 산다 — 눈이 좁아진 것이지
    #    먼 것이 아니다
    case("같은 파일의 performance.yml 사슬 조회는 산다",
         any(r.endswith("SkillEngine.java") for r in rows["skills.window_ticks"]),
         str(rows["skills.window_ticks"]))

    # ⑩ 두 홉 변수 사슬(pf → section 변수 → get)도 흐름을 따라간다
    case("두 홉 변수 사슬도 읽는 자로 센다",
         any(r.endswith("SkillEngine.java") for r in rows["particles.chain_read"]),
         str(rows["particles.chain_read"]))

    # ⑪ ★ 변이 — 주석은 독자가 아니다: 실물 Metrics 의 javadoc 예제("skill_execution")가
    #    직접 조회로 쳐지던 병이 있었다. 주석을 벗긴 소스로 재면 그 키는 probes 배선으로만
    #    살아야 한다 (아래 실물 검사 ⑬에서 재확인)
    commented = {"/fake/Doc.java":
                 'Map<String, Object> root = load(resolve("performance.yml"));'
                 ' int x = num(root.get("window_ticks"));'}
    stripped_doc = perf_audit.strip_comments(
        '// wrap("same_leaf_trap", ...) — 주석 속 예제\n' + commented["/fake/Doc.java"])
    rows_doc = rows_of(perf, {"/fake/Doc.java": stripped_doc}, tools_src)
    case("주석 속 leaf 언급은 못 살린다 (주석 벗긴 소스로 잰다)",
         rows_doc["skills.same_leaf_trap"] == [] and bool(rows_doc["skills.window_ticks"]),
         "trap=%s window=%s" % (rows_doc["skills.same_leaf_trap"],
                                rows_doc["skills.window_ticks"]))

    # ── 실물 — 지금 저장소에 대고 눈을 굴려 본다 ──
    live = subprocess.run([sys.executable, str(ROOT / "tools" / "perf_audit.py")],
                          cwd=ROOT, capture_output=True, text=True, check=False)
    case("실물: metrics.probes 간접 배선이 붙는다",
         "(metrics.probes:" in live.stdout, live.stdout[:400])
    case("실물: skill_execution 은 주석이 아니라 probes 배선으로 산다",
         "(metrics.probes: skill_execution, skill_cast)" in live.stdout, live.stdout[:400])
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
