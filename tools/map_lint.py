#!/usr/bin/env python3
"""지도 린트 — config/world_map.yml 이 스스로 선언한 스키마로 자기를 잰다.

왜 이 눈이 있는가
────────────────────────────────────────────────────────────────────────────
등록부에 68곳이 있는데 필드가 들쭉날쭉했다 (pos 68 · terrain 65 · faction 57 ·
tier 48 · architecture 26 · scale 24). **그래서 코드가 없는 필드를 지어내기 시작했다** —
tier 가 없으면 poor, terrain 이 없으면 "지형 요구 없음"(100점), 원형이 없으면 세력으로 추측.

이 눈이 묻는 여섯
  ① 필수 필드가 있는가        (없으면 코드가 폴백한다 — 그 폴백을 이름으로 부른다)
  ② 값이 등록부의 어휘인가    (factions.yml · terrain_types · regions — 밖의 값은 오타이거나 발명이다)
  ③ pending 이 사유를 갖는가  (★ 침묵 금지. 침묵은 '몰랐다'이고 미결은 '알고 있다'이다)
  ④ 일회성 키가 있는가        (한 곳에만 있는 키 = 스키마 드리프트)
  ⑤ 코드가 읽는데 지도가 안 주는가 (= **추측이 일어나는 자리**)
  ⑥ 근거 없는 값이 있는가     (등록부·인구 등록부와 어긋나는 것)

★ 세계를 읽지 않는다. 등록부만 읽는다 — 그래서 서버 없이 돈다 (MapAudit 은 인게임 검수의 것이다).

  사용법:  python3 tools/map_lint.py [--selftest]
  종료코드: 위반 0건이면 0, 아니면 1
"""
from __future__ import annotations

import sys
import pathlib
import collections

try:
    import yaml
except ImportError:  # pragma: no cover
    print("PyYAML 이 없다: pip install pyyaml")
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONFIG = ROOT / "config"

# 장소가 사는 다섯 절 — WorldMap.readSection 과 **같은 목록이어야 한다**
SECTIONS = ["places", "places_official", "hunting_grounds", "ruins", "resources"]

# ─── 코드가 지도 없이 폴백하는 자리 (WorldMap.java · TerrainForge.java · RemoteBuilder.java) ───
FALLBACKS = {
    "tier": 'WorldMap.readSection → "poor" (조용히 가난해진다)',
    "terrain": 'WorldMap.fit → 100점 "지형 요구 없음" (검사를 통과한 게 아니라 안 받았다)',
    "build": 'WorldMap.readSection → "later"',
    "faction": "RemoteBuilder.archetype → null (집이 안 선다)",
    "archetype": "RemoteBuilder.archetype → 세력+구역으로 **추측한다**",
    "scale": "★ 서사적 크기 (봇·LLM 이 읽는다)",
    "build_radius": "★ 아무도 안 읽는다 — siteRadius = noklim ? 24 : 64 (**코드가 마을 크기를 정한다**)",
}
MOUNTAINOUS = {"산", "험산", "설산", "고원"}


def load(path):
    with open(path, encoding="utf-8") as fh:
        return yaml.safe_load(fh)


class Lint:
    def __init__(self, wm, factions, terrain, region_files):
        self.wm = wm
        self.schema = wm.get("schema") or {}
        self.factions = factions
        self.terrain = terrain
        self.region_files = region_files          # {place_id: {archetype, faction, people}}
        self.bad = []                             # 위반
        self.warn = []                            # 경고 (사람이 정해야 하는 것)
        self.info = []

    # 등록부의 어휘 ------------------------------------------------------
    def faction_ids(self):
        out = set()
        for g in (self.factions.get("faction_groups") or {}).values():
            out |= set((g.get("members") or {}).keys())
        return out

    def places(self):
        """(section, id, place) — 좌표를 가진 것만. 망(網)은 장소가 아니다"""
        for sec in SECTIONS:
            for pid, pl in (self.wm.get(sec) or {}).items():
                if isinstance(pl, dict) and pl.get("pos"):
                    yield sec, pid, pl

    # ────────────────────────────────────────────────────────────────────
    def run(self):
        if not self.schema:
            self.bad.append("★ world_map.yml 에 `schema:` 가 없다 — 린트가 잴 기준이 없다")
            return
        req = self.schema.get("required") or {}
        enums = self.schema.get("enums") or {}
        known = set((self.schema.get("fields") or {}).keys())

        fac_ids = self.faction_ids()
        terrains = set((self.wm.get("terrain_types") or {}).keys())
        regions = set((self.wm.get("regions") or {}).keys())
        # ★ 어휘 = 이미 지어지는 원형(registered) ∪ 청구된 원형(requested).
        #   requested 는 **아직 코드에 없다** — 그러나 지도가 요구하는 것은 어휘다 (그것이 청구서의 뜻이다)
        arch_reg = self.wm.get("archetypes", {}) or {}
        archs = set((arch_reg.get("registered") or {}).keys()) | set((arch_reg.get("requested") or {}).keys())
        shaping = self.terrain.get("shaping") or {}
        natural = self.terrain.get("natural") or {}
        pending_t = self.terrain.get("pending") or {}

        key_use = collections.Counter()
        missing = collections.Counter()
        n = 0

        for sec, pid, pl in self.places():
            n += 1
            for k in pl:
                key_use[k] += 1
            # ★ 전용 조성기가 짓는 곳(청하현·산길 도적)은 원형으로 짓지 않는다 — exempt_if
            exempt = req.get("exempt_if") and pl.get(req["exempt_if"])
            buildable = (sec in (req.get("buildable_sections") or ["places"])
                         and pl.get("build") != "never" and not exempt)

            # ① 필수 필드 -------------------------------------------------
            need = list(req.get("all") or [])
            if buildable:
                need += list(req.get("buildable") or [])
            need += list(req.get(sec) or [])
            for f in need:
                if pl.get(f) is None:
                    missing[f] += 1
                    why = FALLBACKS.get(f, "지도가 말하지 않는다")
                    self.bad.append(f"[필수누락] {pid}.{f} — 폴백: {why}")

            # ② 어휘 -------------------------------------------------------
            def vocab(field, allowed, src):
                v = pl.get(field)
                if v is None or v == "pending":
                    return
                if v not in allowed:
                    self.bad.append(f"[어휘위반] {pid}.{field} = '{v}' — {src} 에 없다")

            vocab("build", set(enums.get("build") or []), "schema.enums.build")
            vocab("tier", set(enums.get("tier") or []), "schema.enums.tier")
            vocab("terrain", terrains, "§3 terrain_types")
            vocab("region", regions | {"전역"}, "§4 regions")
            vocab("faction", fac_ids, "config/factions.yml")
            vocab("archetype", archs, "§16 archetypes.registered")

            # ③ pending 이 사유를 갖는가 — ★ 침묵 금지 ---------------------
            pend = [f for f, v in pl.items() if v == "pending"]
            if pend and not pl.get("pending_why"):
                self.bad.append(
                    f"[침묵] {pid} — {'·'.join(pend)} 가 pending 인데 pending_why 가 없다. "
                    f"**모른다고 적되 왜 모르는지도 적어라**")
            elif pend:
                self.warn.append(f"[미결] {pid}.{'·'.join(pend)} — ★ 사람이 정해야 한다")

            # ⑤ 코드가 읽는데 지도가 안 주는 것 ---------------------------
            t = pl.get("terrain")
            if t in MOUNTAINOUS and pid not in shaping:
                if pid not in natural and t not in natural and pid not in pending_t and t not in pending_t:
                    self.bad.append(
                        f"[추측자리] {pid} — terrain '{t}'(산악)인데 terrain.yml shaping/natural/pending "
                        f"어디에도 없다 → TerrainForge.profile 이 세력·산문으로 **추측한다**")

            # ⑥ 근거 없는 값 — 인구 등록부와의 대조 ------------------------
            rf = self.region_files.get(pid)
            popfield = pl.get("population")
            if rf:
                a = pl.get("archetype")
                if a and a != "pending" and a != rf["archetype"]:
                    self.bad.append(
                        f"[근거없음] {pid}.archetype = '{a}' — 인구 등록부(npcs/regions/{pid}.yml)는 "
                        f"'{rf['archetype']}' 이라 말한다. **둘 중 하나가 거짓말이다**")
                if rf["faction"] and pl.get("faction") and rf["faction"] != pl.get("faction"):
                    self.bad.append(
                        f"[근거없음] {pid}.faction = '{pl.get('faction')}' — 인구 등록부는 '{rf['faction']}'")
                if not popfield:
                    self.warn.append(
                        f"[누락] {pid} — 사람이 {rf['people']}인 등록돼 있는데 지도가 population 을 안 적었다")
            elif popfield:
                self.bad.append(f"[근거없음] {pid}.population = '{popfield}' — 그 파일이 없다")
            elif pl.get("archetype") not in (None, "pending"):
                self.info.append(f"[인구없음] {pid} — 집은 서는데 **사람이 없다** (npcs/regions/{pid}.yml 없음)")

        # ④ 일회성 키 (스키마 드리프트) ------------------------------------
        for k, c in sorted(key_use.items()):
            if c == 1 and k not in known:
                self.warn.append(f"[일회성키] '{k}' — 68곳 중 **한 곳에만** 있다 (스키마 드리프트)")

        # 전역 추측 자리 -----------------------------------------------------
        self.info.append("[추측자리·전역] scale — 지도가 "
                         + str(sum(1 for _, _, p in self.places() if p.get("scale")))
                         + "곳에 적었는데 **코드가 한 번도 안 읽는다** (§16 wiring.W-A)")
        self.info.append("[추측자리·전역] 문파 목록이 코드에 **두 벌** 박혀 있다 "
                         "(TerrainForge.PEAK_FACTIONS · RemoteBuilder.SECTS) — 등록제 위반 (§16 wiring.W-C)")
        self.n = n
        self.missing = missing


def collect_region_files():
    out = {}
    d = CONFIG / "npcs" / "regions"
    if not d.is_dir():
        return out
    for f in sorted(d.glob("*.yml")):
        r = load(f) or {}
        out[f.stem] = {
            "archetype": r.get("archetype"),
            "faction": r.get("faction"),
            "people": len(r.get("people") or {}),
        }
    return out


def report(lint):
    print(f"══ 지도 린트 — 등록된 장소 {lint.n}곳 ══")
    for line in lint.bad:
        print("✗ " + line)
    for line in lint.warn:
        print("! " + line)
    for line in lint.info:
        print("  " + line)
    print("── 총평 ──")
    if lint.missing:
        top = " · ".join(f"{k} {v}곳" for k, v in lint.missing.most_common())
        print(f"  필수 누락 분포: {top}")
    print(f"{'✓ 위반 0건' if not lint.bad else f'✗ 위반 {len(lint.bad)}건'}"
          f"  ·  미결/경고 {len(lint.warn)}건 (★ 사람이 정해야 한다)")
    return 0 if not lint.bad else 1


def build():
    return Lint(load(CONFIG / "world_map.yml"),
                load(CONFIG / "factions.yml"),
                load(CONFIG / "terrain.yml"),
                collect_region_files())


def selftest():
    """★ 눈을 시험한다 — 일부러 병을 넣고 **눈이 짖는지** 본다.
    (오늘 이 프로젝트에서 눈이 스무 번 넘게 거짓말했다. 짖지 않는 눈은 눈이 아니다.)"""
    wm = load(CONFIG / "world_map.yml")
    fac = load(CONFIG / "factions.yml")
    ter = load(CONFIG / "terrain.yml")
    rfs = collect_region_files()
    cases = []

    def probe(name, mutate, expect):
        import copy
        w = copy.deepcopy(wm)
        mutate(w)
        lt = Lint(w, fac, ter, rfs)
        lt.run()
        hits = [x for x in lt.bad + lt.warn if expect in x]
        cases.append((name, bool(hits), hits[0] if hits else "— 짖지 않았다"))

    probe("필수 필드를 지운다 (hwasan.terrain)",
          lambda w: w["places"]["hwasan"].pop("terrain"), "[필수누락] hwasan.terrain")
    probe("어휘 밖의 값 (hwasan.tier = 대단히부자)",
          lambda w: w["places"]["hwasan"].update(tier="대단히부자"), "[어휘위반]")
    probe("등록되지 않은 세력 (hwasan.faction = 매화신교)",
          lambda w: w["places"]["hwasan"].update(faction="매화신교"), "[어휘위반] hwasan.faction")
    probe("사유 없는 pending (침묵)",
          lambda w: w["places"]["jongnam"].pop("pending_why"), "[침묵] jongnam")
    # ★ 여기서 한 번 속았다: 기대값이 "[일회성키]" 였더니 **원래 있던** 일회성 키('haegeum(海禁)')에
    #   맞고 통과했다 — 심은 병이 아니라 남의 병을 보고 짖은 것이다. 기대값은 **심은 것을 정확히** 가리켜야 한다.
    probe("스키마 드리프트 (일회성 키)",
          lambda w: w["places"]["hwasan"].update(매화나무수="많음"), "[일회성키] '매화나무수'")
    probe("인구 등록부와 어긋나는 원형 (hwasan.archetype = 산채)",
          lambda w: w["places"]["hwasan"].update(archetype="산채"), "[근거없음] hwasan.archetype")
    probe("없는 인구 파일 (hwasan.population)",
          lambda w: w["places"]["mudang"].update(population="config/npcs/regions/mudang.yml"),
          "[근거없음] mudang.population")
    probe("산악인데 terrain.yml 이 침묵 (새 장소)",
          lambda w: w["places"].update(시험봉={"name": "시험봉", "pos": [1, 1], "region": "섬서",
                                              "terrain": "험산", "build": "never", "access": "항상"}),
          "[추측자리] 시험봉")

    print("══ 눈을 시험한다 — 일부러 병을 넣고 짖는지 본다 ══")
    ok = True
    for name, caught, evidence in cases:
        print(("✓ " if caught else "✗ ") + f"{name}\n    → {evidence}")
        ok &= caught
    print("── " + ("✓ 눈이 여덟 번 다 짖었다" if ok else "✗ ★ 눈이 놓쳤다 — 눈을 고쳐라"))
    return 0 if ok else 1


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    lt = build()
    lt.run()
    sys.exit(report(lt))
