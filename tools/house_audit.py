#!/usr/bin/env python3
"""가문(家門)의 눈 — 「한 채의 집」이 제대로 서는가.

【이 눈이 지키는 것】
  ① 성씨   — **오대세가의 성을 훔치지 않는가** (남궁·팽·당·제갈·모용은 그들의 집이다)
             그리고 성은 **무가 계열에만** 붙는가 (이 세계에서 성 = 「가문이 있다」는 표시)
  ② 지역   — 가문이 **사람이 설 수 있는 고을**에만 사는가
             ★ 블록도 앵커도 없는 고을에 집을 두면 **갈 수 없는 집**이 되고 사람은 허공에 떨어진다
  ③ 형태   — 흥/쇠/멸이 집안과 **어긋나지 않는가** (몰락무가인데 '흥'이면 세계가 모순된다)
  ④ ★★ **형제가 실제로 생기는가** — 몬테카를로로 잰다
             사용자가 (다) 주사위를 고른 뜻은 **형제가 생기라는 것**이다.
             기존 가문 확률이 너무 낮으면 **형제는 사실상 안 생기고**, 그러면 (다)를 고른 뜻이 죽는다.

수를 고치면 **반드시 다시 돌려라.**
    python3 tools/house_audit.py
"""
import sys
import random
import collections
from pathlib import Path

try:
    import yaml
except ImportError:
    print("PyYAML 이 없다: pip install pyyaml", file=sys.stderr)
    sys.exit(2)

ROOT = Path(__file__).resolve().parent.parent
CREATION = ROOT / "config" / "player_creation.yml"
FACTIONS = ROOT / "config" / "factions.yml"

TRIALS = 20000   # 몬테카를로 표본
# ★★ 문턱은 **등록부가 정한다** (house_system.assignment.sibling_check).
#   전에는 여기 `MIN_SIBLING_PCT = 20.0` 이 **박혀 있었다** — 그런데 사용자가 고른 정본(10%)이
#   20명에서 13% 다. **눈이 정본을 위반이라고 짖었을 것이다.** 코드가 취향을 쥐고 있었다.


def main() -> int:
    creation = yaml.safe_load(CREATION.read_text(encoding="utf-8"))
    lp = creation["age_and_lifepath"]
    hs = lp.get("house_system") or {}
    families = lp["families"]
    fails: list[str] = []

    if not hs.get("enabled"):
        print("가문이 아직 안 섰다 (house_system.enabled: false) — 형제는 비어 있다.")
        return 0

    # ─── ① 성씨 ───
    print("─ ① 성씨 — 성은 「가문이 있다」는 표시다 ─")
    sn = hs.get("surnames") or {}
    pool = sn.get("pool") or []
    forbidden = set(sn.get("forbidden") or [])
    print(f"   성씨 {len(pool)}개 · 금지 {sorted(forbidden)}")
    if not pool:
        fails.append("surnames.pool 이 비었다 — 무가에 이름을 지어 줄 수 없다")
    # ★★ 오대세가의 성을 훔치면 위반
    stolen = sorted(set(pool) & forbidden)
    if stolen:
        fails.append(f"성씨 목록이 **오대세가의 성**을 쓴다: {stolen} — 그건 그들의 집이다")
    # ★ 금지 목록이 factions.yml 의 오대세가와 맞는가 (등록부가 진짜 근거인지 대조)
    fac = yaml.safe_load(FACTIONS.read_text(encoding="utf-8"))
    clans = set()

    def walk(node):
        if isinstance(node, dict):
            if node.get("name") == "오대세가":
                for c in node.get("clans", []):
                    clans.add(str(c))
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)

    walk(fac)
    if clans:
        print(f"   factions.yml 오대세가: {sorted(clans)}")
        missing = [f for f in forbidden if not any(f in c for c in clans)]
        if missing:
            print(f"   ⚠ 금지 성 {missing} 이 오대세가 명단에 안 보인다 (이름이 바뀌었는가?)")
    if sn.get("format_martial") and "{surname}" not in sn["format_martial"]:
        fails.append("format_martial 에 {surname} 이 없다 — 무가에 성이 안 붙는다")
    if sn.get("format_common") and "{surname}" in sn.get("format_common", ""):
        fails.append("format_common 에 {surname} 이 있다 — **평민 집에 성이 붙는다** "
                     "(이 세계의 NPC 32명은 전원 성이 없다)")

    # ─── ② 지역 ───
    print("\n─ ② 지역 — 가문은 **설 수 있는 고을**에만 산다 ─")
    mvt = lp["mvt_start"]
    playable = mvt.get("playable") or []
    by_region = mvt.get("by_region") or {}
    regions_cfg = lp["start_regions"]
    print(f"   설 수 있는 고을: {playable}")
    if not playable:
        fails.append("mvt_start.playable 이 비었다 — 아무도 어디에서도 시작할 수 없다")
    for r in playable:
        # ★★ 앵커가 없는 고을에 사람을 세우면 **허공에 떨어진다**
        if r not in by_region:
            fails.append(f"설 수 있다는 고을 {r} 에 **앵커가 없다** (by_region 에 없다) "
                         "— 사람이 허공에 떨어진다")
            continue
        reg = by_region[r]
        if not reg.get("anchor_map") or not reg.get("by_family"):
            fails.append(f"by_region[{r}] 에 anchor_map 또는 by_family 가 없다")
            continue
        # ★ 그 고을에 **모든 집안**의 자리가 있는가 (없으면 그 집안은 거기서 설 곳이 없다)
        missing = [f for f in families if f not in reg["by_family"]]
        if missing and not reg.get("default_family"):
            fails.append(f"by_region[{r}] 에 자리 없는 집안: {missing} (default_family 도 없다)")
        # ★ 앵커 id 가 실재하는가
        for f, loc in reg["by_family"].items():
            if loc not in reg["anchor_map"]:
                fails.append(f"by_region[{r}].by_family[{f}] = {loc!r} — anchor_map 에 없다")
        # ★ 등록부가 이 고을을 '완비' 라고 하는가
        st = regions_cfg.get(r)
        status = st.get("status") if isinstance(st, dict) else None
        mark = "" if status == "완비" else f"   ☠ status={status!r}"
        print(f"   {r} ({reg.get('name')}) — 집안 {len(reg['by_family'])}종{mark}")
        if status != "완비":
            fails.append(f"설 수 있다는 고을 {r} 의 status 가 '완비' 가 아니다 ({status!r}) "
                         "— **블록도 앵커도 없는 고을에 사람을 세운다**")
    # ★ 안 선 고을은 playable 에 없어야 한다
    stub = [r for r, v in regions_cfg.items()
            if isinstance(v, dict) and v.get("status") == "스텁" and r in playable]
    if stub:
        fails.append(f"스텁 고을이 playable 에 있다: {stub} — **갈 수 없는 집이 된다**")

    # ─── ③ 형태 ───
    print("\n─ ③ 가문의 형태 — 탄생에 고정 (흥·쇠·멸) ─")
    st_cfg = hs.get("state") or {}
    by_fam = st_cfg.get("by_family") or {}
    opts = set(st_cfg.get("options") or {})
    martial = [f for f, v in families.items() if v.get("lineage") == "무가"]
    for f, v in by_fam.items():
        if f not in families:
            fails.append(f"state.by_family 에 없는 집안: {f}")
        elif v not in opts:
            fails.append(f"state.by_family[{f}] = {v!r} — options 에 없는 형태")
        elif f not in martial:
            fails.append(f"state.by_family[{f}] — **무가 계열이 아니다** "
                         "(농가의 '흥망'은 이 세계의 어휘가 아니다)")
        else:
            print(f"   {f} → 「{v}」 (집안이 이미 말했다 — 굴리지 않는다)")
    lw = st_cfg.get("living_weights") or {}
    for k in lw:
        if k not in opts:
            fails.append(f"state.living_weights[{k}] — options 에 없는 형태")
    diced = [f for f in martial if f not in by_fam]
    print(f"   {diced} → 주사위 {lw}  (살아 있는 집이 흥한가 기우는가)")
    # ★ 몰락무가가 '멸' 이 아니면 세계가 모순된다 (world_link: "가문의 과거")
    if "몰락_무가의_자식" in families and by_fam.get("몰락_무가의_자식") != "멸":
        fails.append("몰락_무가의_자식 의 형태가 '멸' 이 아니다 — "
                     "world_link 가 '벽에 걸린 낡은 검 — **가문의 과거**' 라고 말한다 (이미 무너진 집)")

    # ─── ④ ★★ 형제가 실제로 생기는가 — **이 코드의 배정 로직 그대로** ───
    print("\n─ ④ ★★ 형제가 실제로 생기는가 (몬테카를로) ─")
    asg = hs.get("assignment") or {}
    cap = int(asg.get("children_cap", 4))
    join = int(asg.get("join_existing", 10))
    chk = asg.get("sibling_check") or {}
    ref = int(chk.get("reference_people", 20))
    lo = float(chk.get("min_pct", 5))
    hi = float(chk.get("max_pct", 40))
    if cap < 2:
        fails.append(f"children_cap = {cap} — 상한이 2 미만이면 **형제가 절대 안 생긴다**")
    print(f"   기존 집에 태어날 확률 {join}% · 자식 수 상한 {cap}")
    print(f"   문턱(등록부): {ref}명일 때 형제율 {lo}~{hi}%")

    # ★ 태어날 수 있는 집안만 (birth: false = 거절로만 닿는 집 — 아무도 거기서 태어나지 않는다)
    #   ★★ 그리고 **집을 찾을 때는 「태어난 집안」을 쓴다** (가출한 → 무가) — GameListener.assignHouse 와 동형
    born_fams = [f for f, v in families.items() if v.get("birth") is not False]

    def simulate(n_people: int, join_pct: int, seed: int) -> float:
        """이 서버에 n_people 명이 태어나면 **형제가 있는 사람**이 몇 %인가.
        ★ GameListener.assignHouse 와 **같은 순서**로 굴린다:
           빈자리 있는 집만 후보 → 확률로 합류(빈자리 가중) → 아니면 새 집."""
        r = random.Random(seed)
        houses_by_fam: dict[str, list[int]] = collections.defaultdict(list)
        for _ in range(n_people):
            f = r.choice(born_fams)
            room = [i for i, c in enumerate(houses_by_fam[f]) if c < cap]
            pick = None
            if room and r.randrange(100) < join_pct:
                tot = sum(cap - houses_by_fam[f][i] for i in room)
                rr = r.randrange(tot)
                for i in room:
                    rr -= cap - houses_by_fam[f][i]
                    if rr < 0:
                        pick = i
                        break
            if pick is None:
                houses_by_fam[f].append(0)
                pick = len(houses_by_fam[f]) - 1
            houses_by_fam[f][pick] += 1
        sizes = [c for lst in houses_by_fam.values() for c in lst]
        over = [c for c in sizes if c > cap]
        if over:
            raise AssertionError(f"상한({cap})을 넘은 집: {sorted(set(over))}")
        return 100.0 * sum(c for c in sizes if c >= 2) / n_people

    def measure(n_people: int, join_pct: int, reps: int = 3000) -> float:
        return sum(simulate(n_people, join_pct, s) for s in range(reps)) / reps

    print("\n   형제가 있는 사람의 비율 (%) — ★ 이 코드의 배정 로직으로 실측")
    cols = (60, 30, 20, 10, 5)
    print("   사람 수 │ " + " │ ".join(f"{c:>3}%" for c in cols))
    print("   ────────┼" + "┼".join("──────" for _ in cols))
    for n_people in (5, 10, 20, 50):
        cells = []
        for c in cols:
            v = measure(n_people, c)
            cells.append(f"{v:5.1f}" + ("*" if c == join else " "))
        mark = "  ← 20명이면 두어 명" if n_people == ref else ""
        print(f"   {n_people:>6} │ " + " │ ".join(cells) + mark)
    print(f"   (* = 지금의 정본 {join}%)")

    # ★★ 문턱 검사 — **등록부의 눈금**으로
    actual = measure(ref, join, reps=6000)
    print(f"\n   ★ {ref}명일 때 형제가 있는 사람: **{actual:.1f}%**  (문턱 {lo}~{hi}%)")
    print(f"     → {ref}명 중 약 **{actual * ref / 100:.1f}명** — 「간혹가다 한 명 두 명」")
    if actual < lo:
        fails.append(f"{ref}명일 때 형제가 {actual:.1f}% 뿐이다 (< {lo}%) — "
                     f"{ref}명이 모여도 **형제가 한 명도 안 생긴다.** (다) 주사위를 고른 뜻이 죽는다")
    if actual > hi:
        fails.append(f"{ref}명일 때 형제가 {actual:.1f}% 다 (> {hi}%) — "
                     "**다들 형제처럼 느껴진다** (사용자가 거부한 그것이다)")

    # ─── ⑤ ★★ **집을 나온 아이는 제 형과 남남이 되지 않는가** ───
    #
    # 사용자 확정: *"절연은 관계를 끊는 것이 아니라 **관계를 무겁게** 만든다.
    #               호적에서 지워도 **형은 형이다.**"*
    # → 그 아이는 **자기가 태어난 집(무가)에 앉아야** 한다. 「가출한 무가」라는 **새 집**이 서면
    #   같은 house_id 가 아니게 되고 — **제 형과 남남이 된다.**
    print("\n─ ⑤ 집을 나온 아이 — 제 형과 남남이 되지 않는가 ─")
    refuse = lp.get("refuse_house") or {}
    if refuse.get("enabled"):
        runaway = refuse.get("becomes")
        origin = (families.get(runaway) or {}).get("from")
        if not origin:
            fails.append(f"families[{runaway}].from 이 없다 — **어느 집을 나왔는지 세계가 모른다.** "
                         "그러면 코드가 새 집을 세우고 **제 형과 남남이 된다**")
        elif origin not in families:
            fails.append(f"families[{runaway}].from = {origin!r} — 그런 집안이 없다")
        else:
            print(f"   {runaway} → 태어난 집: 「{origin}」  ← ★ 그 집에 앉는다 (형은 형이다)")
        if (families.get(runaway) or {}).get("birth") is not False:
            fails.append(f"{runaway} 에 birth: false 가 없다 — **거기서 태어나는 사람이 생긴다**")
        if runaway in born_fams:
            fails.append(f"{runaway} 이 태어날 수 있는 집안 목록에 있다 — 새 집이 선다")

    # ★ kin_group 은 죽었다 — 아직 남아 있으면 **두 벌**이다 (하나가 낡는다)
    if lp.get("kin_group") is not None:
        fails.append("kin_group 이 아직 등록부에 있다 — **죽은 표다** (형제는 house_id 로만 잡힌다)")

    print()
    if fails:
        for f in fails:
            print(f"☠ {f}")
        print(f"\n눈이 운다 — {len(fails)}건")
        return 1
    print("눈이 조용하다 — 집은 설 수 있는 고을에 서고, 형제는 같은 집에서 난다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
