#!/usr/bin/env python3
"""성향 테스트의 눈 — 유년의 기억이 **정말로 성향을 가르는가**를 전수로 잰다.

【왜 이 눈이 생겼나 (2026-07-13)】
  옛 5문항은 **5방 동점이 6.54%** 였다 (열다섯 중 하나). 그 동점의 최고점은 **2점** —
  즉 다섯 번 골랐는데 **다섯이 다 달랐다.** 어떤 성향도 두 번 안 골랐다는 뜻이다.
  그것은 '다면적인 아이'가 아니라 **신호 없음**이었다.

  뿌리는 **기회의 불평등**이었다: 야망형은 고를 수 있는 자리가 2군데뿐인데 탐구형은 4군데.
  그래서 단독 1위 비율이 탐구형 20.70% : 야망형 3.12% — **6.6배**. 구조가 성향을 정하고 있었다.

【이 눈이 지키는 것】
  ① 기회의 균형   — 성향마다 주(primary) 자리가 고르게 있는가
  ② 신호의 존재   — 5방 이상 동점(= 신호 없음)이 드문가
  ③ 편향의 부재   — 단독 1위 비율이 성향마다 비슷한가
  ④ 결(結)        — 성향이 집안을 낳는가 (family_affinity)
  ⑤ 마크의 첫 자리 — 집안이 앵커로 이어지는가 (mvt_start)
  ★ 등록부 무결   — 오타로 생긴 유령 성향이 없는가 (담당자가 실제로 "협의 형" 을 냈다)

문항을 고치면 **반드시 다시 돌려라.**
    python3 tools/disposition_audit.py
"""
import sys
import itertools
import collections
from pathlib import Path

try:
    import yaml
except ImportError:
    print("PyYAML 이 없다: pip install pyyaml", file=sys.stderr)
    sys.exit(2)

ROOT = Path(__file__).resolve().parent.parent
TEST = ROOT / "config" / "disposition_test.yml"
CREATION = ROOT / "config" / "player_creation.yml"

# ─── 문턱 (이 값을 넘으면 눈이 운다) ───
MAX_NO_SIGNAL = 1.0    # 5방 이상 동점 = 신호 없음. 1% 넘으면 병이다 (옛 값 6.54%)
MAX_BIAS = 2.5         # 단독 1위 최고/최저 비율. 2.5배 넘으면 구조가 성향을 정한다 (옛 값 6.6배)
MIN_PRIMARY = 4        # 성향마다 주(primary)로 고를 수 있는 자리의 최소 (옛 값: 야망 2)


def main() -> int:
    test = yaml.safe_load(TEST.read_text(encoding="utf-8"))
    creation = yaml.safe_load(CREATION.read_text(encoding="utf-8"))
    questions = test["questions"]

    # ★ 성향의 정본은 player_creation.yml disposition_presets 다 (여기서 지어내지 않는다)
    dispos = list(creation["disposition_presets"].keys())

    fails: list[str] = []

    # ─── ★ 등록부 무결 — 오타로 생긴 유령 성향 ───
    ghosts = set()
    prim: collections.Counter = collections.Counter()
    seco: collections.Counter = collections.Counter()
    for q in questions:
        for key, ch in q["choices"].items():
            for axis, tally in (("primary", prim), ("secondary", seco)):
                d = ch.get(axis)
                if d is None:
                    continue
                if d not in dispos:
                    ghosts.add(f"{q['id']}{key}.{axis} = {d!r}")
                tally[d] += 1
    if ghosts:
        fails.append("등록부에 없는 성향 (오타?): " + ", ".join(sorted(ghosts)))

    # ─── 전수 조사 ───
    per_q = []
    for q in questions:
        opts = []
        for _key, ch in sorted(q["choices"].items()):
            sc: collections.Counter = collections.Counter()
            if ch.get("primary"):
                sc[ch["primary"]] += 2
            if ch.get("secondary"):
                sc[ch["secondary"]] += 1
            opts.append(sc)
        per_q.append(opts)

    tie_hist: collections.Counter = collections.Counter()
    tie_score: collections.Counter = collections.Counter()
    winner: collections.Counter = collections.Counter()
    total = 0
    for combo in itertools.product(*per_q):
        total += 1
        sc = collections.Counter()
        for c in combo:
            sc.update(c)
        top = max(sc.values())
        tops = [d for d in dispos if sc.get(d, 0) == top]
        tie_hist[len(tops)] += 1
        if len(tops) >= 3:
            tie_score[top] += 1
        if len(tops) == 1:
            winner[tops[0]] += 1

    print(f"성향 테스트의 눈 — 문항 {len(questions)}개 · 전수 {total:,}가지\n")

    # ─── ① 기회의 균형 ───
    print("─ ① 기회 (주/부) — 성향마다 고르게 있어야 한다 ─")
    starved = []
    for d in dispos:
        mark = ""
        if prim[d] < MIN_PRIMARY:
            mark = f"  ☠ 굶었다 (최소 {MIN_PRIMARY})"
            starved.append(d)
        print(f"   {d}: 주 {prim[d]}  부 {seco[d]}{mark}")
    if starved:
        fails.append(f"주(primary) 자리가 {MIN_PRIMARY} 미만인 성향: {starved} "
                     "— 고를 자리가 없으면 그 성향은 나오지 않는다")

    # ─── ② 신호의 존재 ───
    print("\n─ ② 동점 분포 ─")
    for n in sorted(tie_hist):
        pct = 100 * tie_hist[n] / total
        print(f"   {n}방 동점: {tie_hist[n]:>8,}  ({pct:5.2f}%)")
    no_signal = sum(v for k, v in tie_hist.items() if k >= 5)
    ns_pct = 100 * no_signal / total
    wide = sum(v for k, v in tie_hist.items() if k >= 3)
    print(f"\n   3방 이상 (다면적) : {wide:,} ({100 * wide / total:.2f}%)")
    print(f"   5방 이상 (신호없음): {no_signal:,} ({ns_pct:.2f}%)   문턱 {MAX_NO_SIGNAL}%")
    if tie_score:
        print(f"   동점의 최고점 분포: {dict(sorted(tie_score.items()))}")
        print("     ★ 최고점이 2점이면 '다면적'이 아니라 **아무것도 두 번 안 골랐다**는 뜻이다")
    if ns_pct > MAX_NO_SIGNAL:
        fails.append(f"5방 이상 동점 {ns_pct:.2f}% > {MAX_NO_SIGNAL}% "
                     "— 답이 성향을 못 가른다 (문항을 늘리거나 기회를 고르게 하라)")

    # ─── ③ 편향의 부재 ───
    print("\n─ ③ 단독 1위 (편향) ─")
    for d, n in winner.most_common():
        print(f"   {d}: {n:>8,} ({100 * n / total:5.2f}%)")
    lost = [d for d in dispos if winner[d] == 0]
    if lost:
        fails.append(f"단독 1위가 **한 번도** 안 되는 성향: {lost}")
    if winner and min(winner.values()) > 0:
        bias = max(winner.values()) / min(winner.values())
        print(f"\n   최고/최저 = {bias:.1f}배   문턱 {MAX_BIAS}배")
        if bias > MAX_BIAS:
            fails.append(f"편향 {bias:.1f}배 > {MAX_BIAS}배 — 구조가 성향을 정하고 있다")

    # ─── ④ 결(結) — 성향이 집안을 낳는가 ───
    print("\n─ ④ 결 — 성향 → 집안 (family_affinity) ─")
    lifepath = creation["age_and_lifepath"]
    affinity = lifepath.get("family_affinity", {}) or {}
    families = set(lifepath["families"].keys())
    for d in dispos:
        row = affinity.get(d)
        if not row or not row.get("candidates"):
            fails.append(f"family_affinity 에 {d} 의 후보가 없다 — 그 성향은 집안을 못 낳는다")
            continue
        cands = row["candidates"]
        unknown = [c for c in cands if c not in families]
        if unknown:
            fails.append(f"family_affinity[{d}] 에 없는 집안: {unknown}")
        print(f"   {d} → {cands}")
    reachable = {c for row in affinity.values() if isinstance(row, dict)
                 for c in row.get("candidates", [])}
    # ★ birth: false = **태어나는 집이 아니다** (가출한_무가의_자식 — 세가를 거절해야만 닿는다).
    #   그런 집이 대응표에 없는 것은 **병이 아니라 설계다.** 눈이 헛짖으면 사람이 눈을 끈다.
    born_families = {f for f, cfg in lifepath["families"].items()
                     if not (isinstance(cfg, dict) and cfg.get("birth") is False)}
    not_born = families - born_families
    for f in sorted(not_born):
        src = lifepath["families"][f].get("from", "?")
        print(f"   ○ {f} — 태어나는 집이 아니다 (거절로만 닿는다: {src})")
    orphan = born_families - reachable
    if orphan:
        fails.append(f"어떤 성향으로도 닿지 않는 집안: {sorted(orphan)} "
                     "— 태어날 수 있는 집인데 아무도 그 집에 태어나지 못한다")

    # ★ 거절의 문이 실재하는 집을 가리키는가 (유령 집안으로 보내면 사람이 증발한다)
    refuse = lifepath.get("refuse_house", {}) or {}
    if refuse.get("enabled"):
        becomes = refuse.get("becomes")
        if becomes not in families:
            fails.append(f"refuse_house.becomes = {becomes!r} — 그런 집안이 없다")
        for f in refuse.get("applies_to", []):
            if f not in families:
                fails.append(f"refuse_house.applies_to 에 없는 집안: {f}")
        print(f"   ○ 세가 거절: {refuse.get('applies_to')} → {becomes}")

    # ─── ⑤ 마크의 첫 자리 — ★ 이제 **지역 × 집안**이다 (가문이 사는 고을에서 시작한다) ───
    print("\n─ ⑤ 마크의 첫 자리 — 지역 × 집안 (mvt_start.by_region) ─")
    mvt = lifepath.get("mvt_start", {}) or {}
    playable = mvt.get("playable") or []
    by_region = mvt.get("by_region") or {}
    if not playable:
        fails.append("mvt_start.playable 이 비었다 — 아무도 어디에서도 시작할 수 없다")
    for r in playable:
        reg = by_region.get(r)
        if not reg:
            fails.append(f"설 수 있다는 고을 {r} 에 앵커가 없다 (by_region) — 사람이 허공에 떨어진다")
            continue
        amap = reg.get("anchor_map") or {}
        bfam = reg.get("by_family") or {}
        print(f"   [{reg.get('name', r)}]")
        for fam in sorted(families):
            loc = bfam.get(fam, reg.get("default_family"))
            anchor = amap.get(loc)
            if anchor is None:
                fails.append(f"mvt_start[{r}]: 집안 {fam} 의 자리 {loc!r} 가 anchor_map 에 없다")
            else:
                print(f"     {fam} → {loc} → 앵커 「{anchor}」")

    # ─── ⑥ 탄생 소문 — 마을이 누가 났는지 아는가 ───
    print("\n─ ⑥ 탄생 소문 — 어디까지 퍼지는가 (birth_rumor) ─")
    rumor = yaml.safe_load((ROOT / "config" / "rumor.yml").read_text(encoding="utf-8"))
    reach = rumor["propagation"]["reach_by_intensity"]
    # ★★ **없는 것과 꺼진 것은 다른 사실이다.**
    #   담당자가 mvt_start 를 갈아 끼우다 이 절을 **통째로 지웠는데**, 코드는 계속 읽고 있었고
    #   눈은 "꺼져 있다"고만 하고 넘어갔다 — **탄생 소문이 조용히 안 났다.**
    #   침묵하는 실패다. 이제 **없으면 짖는다.**
    if "birth_rumor" not in lifepath:
        fails.append("birth_rumor 절이 **없다** — 코드(Rules.birthRumor)는 여전히 읽는다. "
                     "아무도 태어난 줄 모른다 (그리고 아무도 그 사실을 모른다)")
        br = {}
    else:
        br = lifepath["birth_rumor"] or {}
    if not br:
        pass
    elif not br.get("enabled"):
        print("   (일부러 껐다 — 아무도 태어난 줄 모른다)")
    else:
        # ★ 강도는 rumor.yml 의 사다리에 **실재하는 칸**이어야 한다 (수를 지어내면 안 된다)
        base = br.get("default_intensity")
        if base not in reach:
            fails.append(f"birth_rumor.default_intensity={base} — rumor.yml 사다리에 없는 칸")
        else:
            print(f"   보통의 집: 강도 {base} = 「{reach[base]}」")
        for fam, i in (br.get("house_intensity") or {}).items():
            if fam not in families:
                fails.append(f"birth_rumor.house_intensity 에 없는 집안: {fam}")
            elif i not in reach:
                fails.append(f"birth_rumor.house_intensity[{fam}]={i} — rumor.yml 사다리에 없는 칸")
            else:
                print(f"   {fam}: 강도 {i} = 「{reach[i]}」  ★ 제안값 (승인 대기)")
        # 태그가 실제로 어느 망이든 듣는가 — 아무도 안 듣는 태그면 소문은 심어도 죽는다
        heard = {t for net in rumor["networks"].values() for t in net.get("interests", [])}
        deaf = [t for t in (br.get("tags") or []) if t not in heard]
        if deaf:
            fails.append(f"birth_rumor.tags {deaf} — **어느 소문망도 듣지 않는 태그다** "
                         "(심어도 아무 데도 안 간다)")

        # ─── ★★ 시간의 비대칭 — 아우가 났는데 형이 모르면 위반 ───
        news = br.get("sibling_news") or {}
        if not br.get("kin_always_know"):
            fails.append("birth_rumor.kin_always_know 가 없다/거짓이다 — "
                         "**형이 소문의 범위 안에 있어야만 아우를 안다** (제 집의 일인데!)")
        if not news.get("enabled"):
            fails.append("sibling_news 가 꺼져 있다 — **아우가 나도 형이 모른다.** "
                         "형은 「어느 날 형이 되는 것」을 겪어야 한다")
        else:
            for k in ("title", "body", "channel", "ledger"):
                if not news.get(k):
                    fails.append(f"sibling_news.{k} 가 없다 — 코드가 말을 지어내게 된다")
            print(f"   ★ 아우가 나면 형이 안다: {news.get('channel')} "
                  f"(소문 범위와 무관 — 제 집의 일이다)")

    # ─── ⑦ 적서(嫡庶) — 같은 집인데 세상이 아는 무게가 다르다 ───
    print("\n─ ⑦ 적서 — 세가에만 있고, 무게를 가른다 (birth_rank) ─")
    rank = lifepath.get("birth_rank", {}) or {}
    if not rank.get("enabled"):
        print("   (꺼져 있다 — 세계에 적서가 없다)")
    else:
        houses = rank.get("houses") or []
        opts = rank.get("options") or {}
        weights = rank.get("weights") or {}

        # ★ 적서가 **없는 집**에 적서가 붙으면 위반 (객잔집 아이에게 적자·서자는 없다)
        for h in houses:
            if h not in families:
                fails.append(f"birth_rank.houses 에 없는 집안: {h}")
                continue
            fam = lifepath["families"][h]
            if fam.get("birth") is False:
                fails.append(f"birth_rank.houses[{h}] — **태어나는 집이 아니다** "
                             "(거절로만 닿는 집에 적서를 매길 수 없다)")
            # ★★ 적서는 **이름이 있는 집(무가 계열)** 에만 있다 — 객잔집 아이에게 적자·서자는 없다
            if fam.get("lineage") != "무가":
                fails.append(f"birth_rank.houses[{h}] — **무가 계열이 아니다** "
                             f"(lineage={fam.get('lineage')!r}). 적서는 이름이 있는 집의 것이다")
        # ★ 능력치를 주면 위반 (헌법: 집안은 능력치를 주지 않는다)
        if rank.get("grants_attributes"):
            fails.append("birth_rank.grants_attributes: true — **헌법 위반** "
                         "(집안도 적서도 능력치를 주지 않는다)")
        # ★ 주사위의 비율이 없으면 아무도 못 태어난다
        if not weights or sum(weights.values()) <= 0:
            fails.append("birth_rank.weights 가 비었다 — 적서를 굴릴 수 없다")
        for k in weights:
            if k not in opts:
                fails.append(f"birth_rank.weights[{k}] — options 에 없는 적서다")

        # ★★ 강도 — 적자는 서자보다 **멀리 가야 한다** (사용자 확정: 5 와 3)
        ints = {}
        for k, o in opts.items():
            i = o.get("rumor_intensity")
            if i not in reach:
                fails.append(f"birth_rank.options[{k}].rumor_intensity={i} "
                             "— rumor.yml 사다리에 없는 칸")
            else:
                ints[k] = i
                w = weights.get(k, 0)
                print(f"   {k}: 강도 {i} = 「{reach[i]}」 · 주사위 {w}")
        if "적자" in ints and "서자" in ints and ints["적자"] <= ints["서자"]:
            fails.append(f"적자(강도 {ints['적자']}) 가 서자(강도 {ints['서자']}) 보다 "
                         "멀리 가지 않는다 — **적서가 뒤집혔다**")
        base = br.get("default_intensity", 0) if br.get("enabled") else 0
        for k, i in ints.items():
            if i <= base:
                fails.append(f"{k}(강도 {i}) 가 보통의 집(강도 {base}) 보다 멀리 가지 않는다 "
                             "— 세가인 의미가 없다")
        print(f"   (보통의 집: 강도 {base})")

    # ─── ⑧ 혈연 — ★ **house_audit.py 로 옮겨갔다** ───
    #   형제는 이제 **집안 유형**이 아니라 **house_id**(한 채의 집)로 잡힌다.
    #   옛 `kin_group` 표는 죽었고, 그 검사는 tools/house_audit.py ⑤절이 한다
    #   (집을 나온 아이가 제 형과 남남이 되지 않는가 — families.<집안>.from).
    #   ★ 여기서 두 번 재지 않는다 — **두 벌이면 하나가 낡는다.**

    # ─── ⑧-b 가문(家門) — 「한 채의 집」이 섰는가 ───
    print("\n─ ⑧-b 가문 — 형제는 **같은 집**끼리만 (house_system) ─")
    hs = lifepath.get("house_system", {}) or {}
    mig = ROOT / "db" / "migrations" / "008_가문.sql"
    if not hs:
        fails.append("house_system 절이 없다 — 가문 축이 통째로 사라졌다")
    elif not hs.get("enabled"):
        opens = hs.get("open_questions") or []
        unanswered = [q for q in opens if not q.get("answer")]
        print(f"   ○ 가문이 아직 안 섰다 (enabled: false) — **형제는 비어 있다**")
        print(f"     거짓 형제(같은 유형 = 남매)를 **껐다**. 답을 기다리는 물음 {len(unanswered)}개")
        for q in unanswered:
            print(f"       {q['id']} {q['q']}")
        # ★ 켜져 있지 않은데 그릇도 없으면, 켤 방법이 없다
        if not mig.is_file():
            fails.append("db/migrations/008_가문.sql 이 없다 — 가문을 세울 그릇이 없다")
        # ★ 답이 안 나왔는데 켜면 위반 (아래 else 가 잡는다)
    else:
        # ★★ 켰다면 — **답이 다 나왔는가**. 배정 규칙 없이 켜면 아무도 집에 못 들어간다
        opens = hs.get("open_questions") or []
        unanswered = [q["id"] for q in opens if not q.get("answer")]
        if unanswered:
            fails.append(f"house_system.enabled: true 인데 **아직 안 정해진 물음이 있다**: "
                         f"{unanswered} — 배정 규칙 없이 켜면 아무도 집에 못 들어간다 "
                         "(그리고 형제는 여전히 안 생긴다)")
        if not mig.is_file():
            fails.append("house_system.enabled: true 인데 마이그레이션 파일이 없다")
        print("   ✓ 가문이 섰다 — 형제는 같은 house_id 끼리만 잡힌다")
    # ★ 헌법 — 가문이 실체가 되어도 능력치를 주지 않는다
    if hs.get("grants_attributes"):
        fails.append("house_system.grants_attributes: true — **헌법 위반** (가문은 능력치를 주지 않는다)")

    # ─── ⑨ 발단 — 택한 아이에게 당한 자의 발단을 주지 마라 ───
    print("\n─ ⑨ 발단 — 결이 맞는가 (incident_pool) ─")
    incidents = lifepath["inciting_incidents"]
    # ★ 집안이 질 수 있는 발단의 **결**. 등록부에서 읽는다 (코드가 짐작하지 않는다):
    #   거절로 생긴 집(가출) = **택한 것(출분)** 뿐이다 — 재난을 지면 결이 어긋난다.
    runaway_f = (lifepath.get("refuse_house") or {}).get("becomes")
    fam_kinds = {runaway_f: {"출분"}} if runaway_f else {}
    for f in sorted(families):
        pool = lifepath["families"][f].get("incident_pool")
        if not pool:
            continue
        ghost = [i for i in pool if i not in incidents]
        if ghost:
            fails.append(f"families[{f}].incident_pool 에 없는 발단: {ghost}")
        # ★ family_only 가 **다른 집**을 가리키는 발단을 쓰면 위반 (남의 전용 발단이다)
        stolen = [i for i in pool if i in incidents
                  and incidents[i].get("family_only") not in (None, f)]
        if stolen:
            fails.append(f"families[{f}].incident_pool 이 **남의 전용 발단**을 쓴다: {stolen}")
        # ★★ 결(kind)이 맞는가 — **택한 아이에게 당한 자의 발단을 주지 마라.**
        #   집이 멀쩡히 살아 있는데 발단이 '가문의 몰락' 일 수는 없다.
        want = fam_kinds.get(f)
        if want:
            wrong = [i for i in pool if i in incidents and incidents[i].get("kind") not in want]
            if wrong:
                got = {i: incidents[i].get("kind") for i in wrong}
                fails.append(f"families[{f}].incident_pool 의 발단 결이 어긋난다 — "
                             f"{f} 는 {sorted(want)} 이어야 하는데 {got} 다")
        kinds = sorted({incidents[i].get("kind") for i in pool if i in incidents})
        own = [i for i in pool if i in incidents and incidents[i].get("family_only") == f]
        print(f"   {f}: {pool}  · 결 {kinds}"
              + (f"  ★ 전용 {len(own)}종" if own else ""))
    # ★ 전용 발단인데 아무 집도 안 쓰면 유령이다
    for i, cfg in incidents.items():
        fo = cfg.get("family_only")
        if fo and fo not in families:
            fails.append(f"발단 {i}.family_only = {fo!r} — 그런 집안이 없다")

    print()
    if fails:
        for f in fails:
            print(f"☠ {f}")
        print(f"\n눈이 운다 — {len(fails)}건")
        return 1
    print("눈이 조용하다 — 성향은 답이 가르고, 집안은 성향이 가른다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
