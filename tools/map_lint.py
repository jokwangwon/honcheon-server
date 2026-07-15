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

import re
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

MVT = ROOT / "server-mvt" / "src" / "main" / "java" / "com" / "honcheon" / "mvt"

# ─── 코드가 지도 없이 폴백하는 자리 (WorldMap.java · TerrainForge.java · RemoteBuilder.java) ───
FALLBACKS = {
    "tier": 'WorldMap.readSection → "poor" (조용히 가난해진다)',
    "terrain": 'WorldMap.fit → 100점 "지형 요구 없음" (검사를 통과한 게 아니라 안 받았다)',
    "build": 'WorldMap.readSection → "later"',
    "faction": "소속. ★ 더는 원형을 고르지 않는다 (배선 W-C 이후)",
    "archetype": "RemoteBuilder.archetype → null → **집이 안 선다** (추측하지 않는다)",
    "scale": "★ 서사적 크기 (봇·LLM 이 읽는다) — 코드는 안 읽는다. **그것이 설계다**",
    "build_radius": "RemoteBuilder.siteRadius → ★ 없으면 조성이 **거절한다** (unbuildableReasons)",
}
MOUNTAINOUS = {"산", "험산", "설산", "고원"}

# ─── ★★★ 상업 거점에 **부여하면 안 되는 것** (2026-07-13 사용자 결정 · docs/design/scale_systems.md) ───
#
#   *"`상단연합`은 **무림 문파도 정치 세력도 아니다.** 그러므로:
#     · `roster` 에 추가하지 않는다
#     · `martial` · `mandate_weight` 를 임의로 부여하지 않는다"*
#
#   ★★ 왜 눈이 필요한가: 이 둘을 부여하는 것이 **가장 쉬운 유혹**이기 때문이다 —
#     §17 사다리가 martial 을 먹으므로, 상업 도시의 크기를 정하려면 **무력을 지어내는 것이 제일 빠르다.**
#     그리고 그것이 정확히 **지어내기**다. **상인의 도시를 무력으로 재면 성곽 도시가 오두막이 된다.**
COMMERCIAL_FORBIDDEN = ["martial", "mandate_weight", "influence", "influence_band"]

# ─── ★★★ 상업 거점에 **수동 반경을 함께 적지 않는다** (린트 규칙 4) ───
#
#   ★ 왜: 반경이 두 군데서 오면 **어느 것이 참인지 아무도 모른다.** 그리고 둘이 갈라지는 날
#     사람은 **더 큰 쪽**을 믿는다 (그것이 눈에 보이므로). 그 순간 등급표는 장식이 된다.
#   ⇒ 상업 거점의 반경은 **오직 commercial_scale.radius_ladder 에서만** 온다.
COMMERCIAL_MANUAL_RADIUS = ["radius", "site_radius", "manual_radius",
                            "radius_override", "build_radius_override"]

# ★ 상업 등급이 **건축 원형을 고르면 안 된다** (규칙 7) / 원형이 **등급을 고치면 안 된다** (규칙 6)
ARCHETYPE_KEYS = ["archetype", "archetypes", "원형"]
COMMERCIAL_KEYS = ["commercial_class", "wealth_tier", "route_centrality", "settlement_role"]

# ★ faction_politics.yml roster 에 **있으면 안 되는 id** — 무림의 눈금을 갖지 않는 세력
NOT_IN_ROSTER = {
    "sangdan": "★ 상단연합은 **무림 문파도 정치 세력도 아니다.** roster 에 넣는 순간 "
               "martial·mandate_weight 를 **지어내야 한다** (사용자 결정 2026-07-13)",
}

# ─── ★ 배선(§16 wiring) — **코드에 있으면 안 되는 것.** 있으면 등록제가 깨진 것이다 ───
#   각 항목: (파일, 찾을 문자열, 무엇이 잘못인가)
#   ★ 이 눈이 필요한 이유: 지운 것은 **다시 돌아온다.** 사람이 급하면 목록 한 줄이 제일 빠르기 때문이다.
BANNED = [
    ("TerrainForge.java", "PEAK_FACTIONS",
     "W-C 문파 목록이 코드에 박혔다 — 등록제 위반 (terrain.yml shaping: 가 봉우리를 정한다)"),
    ("RemoteBuilder.java", "SECTS",
     "W-C 문파 목록이 코드에 박혔다 — 등록제 위반 (world_map.yml archetype: 가 원형을 정한다)"),
    ("TerrainForge.java", 'note.contains(',
     "W-D 코드가 architecture **산문을 뒤진다** — 글을 고치면 굴이 사라진다"),
    ("TerrainForge.java", 'arch.contains(',
     "W-D 코드가 architecture **산문을 뒤진다** — 글을 고치면 산이 사라진다"),
    ("RemoteBuilder.java", '"noklim".equals(place.faction()) ? 24 : 64',
     "W-A 코드가 마을 크기를 정한다 — build_radius 를 읽어라 (§17 influence)"),
    # ★★★ 2026-07-13 — **마지막 하드코딩.** 땅이 세력을 알고 있었다
    ("MvtCommand.java", '"noklim".equals(place.faction()) ? 24 : 110',
     "★★ **땅이 세력을 안다** — 사용자: *'땅은 세력을 모른다. 산은 문파가 흥하든 망하든 "
     "그 자리에 그만큼 있다.'* land.forge_radius 를 읽어라 (§1-b)"),
    ("MvtCommand.java", '"noklim".equals(place.faction()) ? 64 : 150',
     "★ 청크 선적 반경까지 **세력을 묻는다** — 땅은 세력을 모른다 (§1-b land.probe_margin)"),
]

# ─── ★★ B-151 — **있어야 하는 배선.** hidden 의 독자 (2026-07-15) ───
#
#   B-151 의 병은 "등록만 되고 아무도 안 숨긴다"였다 — hidden/player_map 키에 **독자가 없었다.**
#   독자를 심었으면 이번엔 **독자가 사라지는 것**을 지켜야 한다: 지우면 빚이 소리 없이 되돌아온다
#   (키는 그대로 남으므로 등록부만 봐서는 아무 증상이 없다 — 그것이 이 빚의 모양이었다).
#   각 항목: (파일, 있어야 하는 문자열, 없으면 무엇이 잘못인가)
REQUIRED_WIRING = [
    ("WorldMap.java", '"hidden"',
     "B-151 — hidden 키의 판독이 사라졌다: **등록만 되고 아무도 안 숨긴다** (그 빚이 되돌아왔다)"),
    ("WorldMap.java", '"player_map"',
     "B-151 — player_map 키의 판독이 사라졌다 (§5.8 문법의 반쪽이 죽었다)"),
    ("MvtCommand.java", ".hidden()",
     "B-151 — 지도 렌더의 숨김 차단이 사라졌다: /혼천 지도 가 비밀 장소를 편다 "
     "(마교 전초가 4일 거리라는 사실 자체가 스포일러다)"),
]

# ─── ★ 코드에 **수(數)가 박히는 것** 자체를 막는다 — 문자열 목록은 다음 하드코딩을 못 막는다 ───
#
#   ★★ 왜 정규식이 따로 필요한가: 위 BANNED 는 **그 문장 그대로**만 잡는다. 그런데 하드코딩은
#     모양을 바꿔 돌아온다 (`? 24 : 110` → `? 32 : 110` → `= 110`). **수가 박히는 자리**를 지킨다.
#   ⇒ forgeRadius 에 **리터럴이 대입되면** 그것이 무슨 수든 위반이다. 등록부에서 읽어야 한다.
BANNED_RE = [
    ("MvtCommand.java", r"forgeRadius\s*=\s*[^;]*\d",
     "★★★ forgeRadius 에 **수가 박혔다** — 땅의 크기는 등록부가 정한다 "
     "(world_map.yml §1-b land.forge_radius). ★ 어떤 수든, 어떤 조건이든 위반이다"),
]


def java_code_only(path):
    """주석과 문자열을 **뺀** 자바 소스 — ★ 금칙어 검사는 **코드**를 봐야 한다.

    처음엔 파일을 통째로 훑었더니 **지운 하드코딩을 설명하는 주석**에 걸려 짖었다
    ("여기 SECTS 가 있었다 — 지웠다"). 그것은 병이 아니라 **병의 기록**이다.
    ★ 그리고 이 함정이 반대 방향으로도 산다: 주석을 못 보면 눈이 조용해지므로,
      **주석에 병을 숨길 수는 없다** — 주석은 돌지 않기 때문이다. 코드만 본다.
    """
    return strip_java(path.read_text(encoding="utf-8"))


def strip_java(src):
    src = re.sub(r"/\*.*?\*/", " ", src, flags=re.S)      # 블록 주석 (javadoc 포함)
    src = re.sub(r"//[^\n]*", " ", src)                   # 줄 주석
    return src


# ★ 시험이 갈아끼우는 손잡이 — **눈을 시험하려면 병을 심을 수 있어야 한다** (selftest 참조)
READ_JAVA = java_code_only


def code_archetypes():
    """★ **코드가 실제로 지을 수 있는 원형** — RemoteBuilder.Archetype enum 을 소스에서 읽는다.

    왜 소스를 읽는가: world_map §16 의 `registered:` 는 **지도의 주장**이다. 주장과 사실이
    갈라지면 지도가 거짓말을 한다. **사실은 코드에만 있다** — 그래서 코드를 읽고, 주장과 **대조한다**.
    """
    m = re.search(r"enum\s+Archetype\s*\{([^}]*)\}", READ_JAVA(MVT / "RemoteBuilder.java"))
    if not m:
        return None
    return {x.strip() for x in m.group(1).split(",") if x.strip()}


def load(path):
    with open(path, encoding="utf-8") as fh:
        return yaml.safe_load(fh)


class Lint:
    def __init__(self, wm, factions, terrain, region_files, politics=None, raw=None):
        self.wm = wm
        self.schema = wm.get("schema") or {}
        self.factions = factions
        self.terrain = terrain
        self.politics = politics or {}            # config/faction_politics.yml (roster 대조용)
        # ★ **원문(raw)** — 앵커/별칭(`&x` / `*x`)은 파싱하면 사라진다. 참조 공유를 잡으려면 글자를 봐야 한다
        self.raw = raw if raw is not None else (CONFIG / "world_map.yml").read_text(encoding="utf-8")
        self.region_files = region_files          # {place_id: {archetype, faction, people}}
        self.bad = []                             # 위반
        self.warn = []                            # 경고 (사람이 정해야 하는 것)
        self.info = []
        # ★★★ 미결(unresolved) — **위반이 아니다. 경고도 아니다. 정직이다.**
        #   린트는 이것을 **채우라고 짖지 않는다** (채우려면 지어내야 하므로).
        #   다만 **몇 곳인지는 매번 소리내어 센다** — 침묵하면 그것은 잊힌다.
        self.unresolved = collections.Counter()   # 필드 → 곳 수
        self.unresolved_places = set()

    # 등록부의 어휘 ------------------------------------------------------
    def faction_ids(self):
        out = set()
        for g in (self.factions.get("faction_groups") or {}).values():
            out |= set((g.get("members") or {}).keys())
        return out

    def hidden_faction_ids(self):
        """★ B-151 — **숨은 세력** (factions.yml faction_groups.<계열>.hidden: true 의 members).

        지금은 forbidden 계열(마교·혈교)뿐이다. ★ 새외는 hidden: false 다 —
        "마교는 hidden = 모른다 / 새외는 안다 = 못 갈 뿐이다. 이 대비가 두 축의 정의다" (§5.9).
        """
        out = set()
        for g in (self.factions.get("faction_groups") or {}).values():
            if isinstance(g, dict) and g.get("hidden") is True:
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
        hidden_fac = self.hidden_faction_ids()
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

            # ①-b 권고 필드 — 없어도 코드는 안 흔들린다. 다만 **비었다는 사실은 말한다**
            if buildable:
                for f in (req.get("advisory") or []):
                    if pl.get(f) is None:
                        self.info.append(f"[서사없음] {pid}.{f} — 봇·LLM 이 이곳의 크기를 모른다 (코드는 안 흔들린다)")

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
            # ★★ 크기의 자 (2026-07-13) — **하나의 자를 온 세계에 들이대지 않는다**
            vocab("scale_system", set(enums.get("scale_system") or []), "schema.enums.scale_system")
            vocab("commercial_class", set(enums.get("commercial_class") or []),
                  "schema.enums.commercial_class")

            # ★★★ 미결(unresolved) — **세되 짖지 않는다.** (`unresolved` 는 실패가 아니라 정직이다)
            for f, v in pl.items():
                if v == "unresolved":
                    self.unresolved[f] += 1
                    self.unresolved_places.add(pid)

            # ★★★ 상업 거점 — **무림의 자를 대지 않는다** (§17-a · scale_systems.md)
            if pl.get("scale_system") == "commercial":
                for f in COMMERCIAL_FORBIDDEN:
                    if pl.get(f) is not None:
                        self.bad.append(
                            f"[상업위반] {pid}.{f} = {pl.get(f)!r} — ★★ **상업 거점에 무력을 부여하지 않는다.** "
                            f"상단연합은 무림 문파가 아니다. 크기는 `commercial_class` 가 정한다 (§17-b). "
                            f"**상인의 도시를 무력으로 재면 성곽 도시가 오두막이 된다**")

            # ★★ B-151 — 표시 축 (hidden / player_map) --------------------
            #   독자: WorldMap.readSection → MvtCommand.showMap/travel (지도 렌더 차단).
            #   세 축 분리: build(조성 수명주기) · hidden/player_map(표시) · access(해금) —
            #   access: hidden 은 **해금이 비밀**이라는 뜻이지 표시 은닉이 아니다 (새외가 그 증거다).
            h, pm = pl.get("hidden"), pl.get("player_map")
            for f, v in (("hidden", h), ("player_map", pm)):
                if v is not None and not isinstance(v, bool):
                    self.bad.append(
                        f"[표시타입] {pid}.{f} = {v!r} — ★ 독자(WorldMap.readSection)는 **불리언만** 읽는다. "
                        f"문자열은 **조용히 안 숨긴다** — 비밀이 지도에 뜨는데 아무 증상이 없다")
            if isinstance(h, bool) and isinstance(pm, bool) and h == pm:
                self.bad.append(
                    f"[표시모순] {pid} — hidden: {str(h).lower()} 인데 player_map: {str(pm).lower()} — "
                    f"두 표기는 서로 반대말이어야 한다 (§5.8 문법: hidden: true ↔ player_map: false). "
                    f"★ 둘이 어긋나면 **어느 쪽이 참인지 아무도 모른다**")
            # 숨김누락 — 숨은 세력(factions.yml hidden: true)의 거점인데 표기가 없다
            if pl.get("faction") in hidden_fac and not (h is True or pm is False):
                self.bad.append(
                    f"[숨김누락] {pid} — faction '{pl.get('faction')}' 은 숨은 세력"
                    f"(factions.yml faction_groups hidden: true)인데 hidden/player_map 표기가 없다 — "
                    f"★ **비밀이 플레이어 지도에 뜬다** (§5.8: 금기는 hidden — 존재를 모른다)")

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

        # ⑦ ★ 배선 — **코드가 등록부를 읽는가** (§16 wiring W-A~W-D) -----------
        self.wiring()

        # ⑩ ★★ 상업 — **상단은 무림의 눈금을 갖지 않는다**
        self.roster_purity()

        # ⑬ ★★★ 상업 반경 — **사용자가 세운 일곱 규칙** (2026-07-13)
        self.commercial_radius()

        # ⑪ ★★★ 명시적 예외가 **전파되지 않는가** (녹림 석채의 석축)
        self.exception_containment()

        # ⑫ ★★★ 섬 — ④ 면제의 **대가**. 배가 닿을 자리가 등록됐는가
        self.docks()

        # ⑧ ★ 2계층 계약 — **집이 제가 선 땅보다 크지 않은가** -------------------
        self.two_layers(req)

        # ⑨ ★★ 조성 검산 — **지금 조성을 돌리면 무엇이 서는가** -----------------
        self.census(req, arch_reg)

        self.n = n
        self.missing = missing

    # ────────────────────────────────────────────────────────────────────
    def wiring(self):
        """★ 코드에 **있으면 안 되는 것**이 돌아왔는가.

        지운 하드코딩은 **다시 돌아온다** — 급할 때 목록 한 줄이 제일 빠르기 때문이다.
        그래서 눈이 지키고 선다. (린트는 서버 없이 도니까, 소스를 **글자로** 읽는다)
        """
        for fname, needle, why in BANNED:
            path = MVT / fname
            if not path.is_file():
                self.warn.append(f"[배선] {fname} 를 못 찾았다 — 배선 검사를 못 했다 (★ 침묵이 아니라 미검사다)")
                continue
            if needle in READ_JAVA(path):
                self.bad.append(f"[배선위반] {fname} 에 `{needle}` 이 있다 — {why}")

        # ★★ B-151 — **있어야 하는 것**이 사라졌는가. 금칙어의 반대 방향이다:
        #   hidden 의 병은 코드에 무엇이 **있어서**가 아니라 **없어서** 생겼다 (등록만 되고 아무도 안 숨겼다).
        #   독자를 지워도 등록부는 멀쩡하므로, 등록부만 보는 눈은 그 퇴행을 영영 모른다 — 그래서 코드를 본다.
        for fname, needle, why in REQUIRED_WIRING:
            path = MVT / fname
            if not path.is_file():
                self.warn.append(f"[독자소실] {fname} 를 못 찾았다 — B-151 독자 검사를 못 했다 (미검사다)")
                continue
            if needle not in READ_JAVA(path):
                self.bad.append(f"[독자소실] {fname} 에 `{needle}` 이 없다 — {why}")

        # ★★ 문자열 목록은 **다음 하드코딩**을 못 막는다 — 모양을 바꿔 돌아오므로. **수가 박히는 자리**를 지킨다
        for fname, pattern, why in BANNED_RE:
            path = MVT / fname
            if not path.is_file():
                continue
            m = re.search(pattern, READ_JAVA(path))
            if m:
                self.bad.append(f"[배선위반] {fname} — `{m.group(0).strip()}` — {why}")

    # ────────────────────────────────────────────────────────────────────
    def roster_purity(self):
        """★★ **상단은 무림의 눈금을 갖지 않는다** (2026-07-13 사용자 결정).

        ★ 왜 이 눈이 있는가 — **유혹이 구조적이기 때문이다.**
          §17 사다리는 `martial + mandate_weight` 를 먹는다. 그러므로 상업 도시의 크기를 정하려는 사람은
          **roster 에 sangdan 을 한 줄 넣는 것이 제일 빠르다.** 그리고 그 한 줄이 곧 **지어내기**다 —
          상단연합에는 무력이 없다. 없는 것을 적으면 세계가 거짓말을 시작한다.
        """
        roster = self.politics.get("roster") or {}
        for fid, why in NOT_IN_ROSTER.items():
            if fid in roster:
                self.bad.append(
                    f"[상업위반] faction_politics.yml roster 에 '{fid}' 가 있다 — {why}")

    # ────────────────────────────────────────────────────────────────────
    def commercial_radius(self):
        """★★★ **상업 반경의 일곱 규칙** — 사용자가 직접 열거했다 (2026-07-13).

        ★ 계약의 심장은 두 줄이다:

          ① *"무림 사다리에 같은 숫자가 존재하더라도 상업 사다리는 **독립된 계약**이다.
             향후 무림 반경이 변경되어도 상업 반경은 자동으로 변하지 않으며, 반대의 경우도 같다."*

          ② *"`commercial_class` 가 없으면 outpost / 잘못되면 radius 40 — **이 구현을 금지한다.**
             **모르는 장소를 작은 장소로 간주하는 것도 하나의 창작이기 때문이다.**"*

        ★★ 두 줄이 각각 **가장 편한 지름길**을 막는다:
          ①은 *"표가 똑같은데 왜 두 벌을 두느냐"* 를 막고 (합치는 순간 두 세계가 한 줄에 묶인다),
          ②는 *"모르니까 일단 제일 작은 걸로"* 를 막는다 (그 순간 **모름이 창작으로 바뀐다**).
        """
        cs = self.wm.get("commercial_scale") or {}
        ladder = cs.get("radius_ladder")
        classes = set((cs.get("classes") or {}).keys())
        mart = ((self.wm.get("influence") or {}).get("ladder") or {})

        if not isinstance(ladder, dict):
            self.bad.append(
                "[상업사다리없음] §17-b commercial_scale.radius_ladder 가 없다 — "
                "★ 상업 거점의 크기를 잴 표가 없다 (사용자가 준 표: outpost 40 · market_town 48 · "
                "trade_city 64 · grand_trade_hub 80)")
            return

        # ══ 규칙 5 ★★★ — **두 표가 같은 객체나 참조를 공유하지 않는다** ══════════════
        #
        #   ★ 이것이 일곱 중 가장 무서운 규칙이다. 왜냐하면 **깨져도 아무 증상이 없기 때문이다** —
        #     표가 같은 값이면 세계는 멀쩡히 돈다. 그러다 **무림 반경을 한 번 고치는 날**
        #     상업 도시 여섯이 **소리 없이 따라 움직인다.** 아무도 그것을 명령하지 않았는데.
        #   ★★ 그러므로 **값이 같은지가 아니라 '한 몸인지'를 잰다** — 객체 동일성(identity)을 본다.
        self.ladder_independence(ladder, mart)

        # ══ 규칙 2 · 7 — 표 자체를 검사한다 ═══════════════════════════════════════
        for cls, r in ladder.items():
            # 규칙 2: 표의 키가 어휘 안에 있는가 (등급 이름이 갈라지면 조회가 조용히 실패한다)
            if cls not in classes:
                self.bad.append(
                    f"[상업등급어휘] radius_ladder 에 '{cls}' 가 있는데 "
                    f"§17-b classes 에 **없다** — 표 둘이 갈라졌다")
            # 규칙 7: **상업 등급은 건축 원형을 임의로 선택하지 않는다**
            if not isinstance(r, int):
                self.bad.append(
                    f"[상업등급월권] radius_ladder.{cls} = {r!r} — ★ 등급표는 **수(반경)만** 준다. "
                    f"원형·형태를 말하는 순간 **크기와 형태가 다시 엉킨다** (사용자: "
                    f"'도시의 규모는 상업 등급이 정하고, **내부의 생김새와 밀도는 건축 원형이 정한다**')")
        for k in ARCHETYPE_KEYS:
            if k in cs or k in (cs.get("classes") or {}):
                self.bad.append(
                    f"[상업등급월권] §17-b commercial_scale 에 '{k}' 가 있다 — "
                    f"★★ **상업 등급이 건축 원형을 고르려 한다.** 크기와 형태는 다른 문제다 (규칙 7)")

        # ══ 규칙 6 — **건축 원형은 상업 등급을 임의로 변경하지 않는다** ═══════════════
        arch_reg = self.wm.get("archetypes") or {}
        for group in ("registered", "requested"):
            for name, spec in (arch_reg.get(group) or {}).items():
                if not isinstance(spec, dict):
                    continue
                for k in COMMERCIAL_KEYS:
                    if k in spec:
                        self.bad.append(
                            f"[원형월권] §16 archetypes.{group}.{name} 에 '{k}' 가 있다 — "
                            f"★★ **건축 원형이 상업 등급을 정하려 한다.** 원형은 **반경 안의 생김새**를 정할 뿐이다 "
                            f"(규칙 6). 등급은 wealth_tier·route_centrality·settlement_role 이 **판정**한다")

        # ══ 규칙 1 · 3 · 4 — 장소마다 ═══════════════════════════════════════════
        for _sec, pid, pl in self.places():
            if pl.get("scale_system") != "commercial":
                continue
            cls = pl.get("commercial_class")
            r = pl.get("build_radius")

            # 규칙 4: **수동 반경을 함께 적지 않는다**
            for k in COMMERCIAL_MANUAL_RADIUS:
                if k in pl:
                    self.bad.append(
                        f"[상업수동반경] {pid}.{k} — ★★ 상업 거점의 반경은 **오직 radius_ladder 에서만** 온다. "
                        f"반경이 두 군데서 오면 **어느 것이 참인지 아무도 모른다** (규칙 4)")

            # 규칙 2: 확정된 등급은 상업 반경표의 키와 일치해야 한다
            if cls is not None and cls != "unresolved" and cls not in ladder:
                self.bad.append(
                    f"[상업등급어휘] {pid}.commercial_class = '{cls}' — ★ 상업 반경표에 그 키가 **없다** "
                    f"(있는 것: {' · '.join(ladder)}). **표에 없는 등급은 크기를 갖지 못한다** (규칙 2)")
                continue

            # 규칙 3 ★★★: unresolved 면 반경도 미결이어야 한다 — **40 으로 대체 금지**
            if cls == "unresolved":
                if isinstance(r, int):
                    self.bad.append(
                        f"[상업미결대체] {pid}.build_radius = {r} 인데 commercial_class 가 **unresolved** 다 — "
                        f"★★★ **모르는 장소를 작은 장소로 간주하는 것도 하나의 창작이다** (사용자 원문). "
                        f"등급을 모르면 **반경도 모르는 것이다.** `build_radius: unresolved` 로 둔다 (규칙 3)")
                elif r != "unresolved":
                    self.bad.append(
                        f"[상업미결] {pid}.commercial_class 가 unresolved 인데 "
                        f"build_radius = {r!r} 다 — ★ **미결은 미결로 적는다** (`unresolved`. 규칙 3)")
                continue

            # 규칙 3-b (역방향): 등급이 **확정됐는데** 반경이 미결이면, 사다리가 이미 답을 갖고 있다
            if cls is None:
                self.bad.append(
                    f"[상업등급없음] {pid} — scale_system 이 commercial 인데 "
                    f"**commercial_class 가 없다.** ★ 모르면 `unresolved` 라 적어라 — **침묵은 미결이 아니다**")
                continue
            want = ladder[cls]
            if r in ("unresolved", "pending"):
                self.bad.append(
                    f"[상업미결역행] {pid}.commercial_class = '{cls}' 로 **확정됐는데** "
                    f"build_radius = '{r}' 다 — ★ 사다리가 이미 답을 갖고 있다 ({want}). "
                    f"**아는 것을 모른다고 적는 것도 거짓이다**")
            # 규칙 1 · 4: 반경은 **상업 표에서만** 온다 — 다른 수면 무림 표에서 집어 온 것이다
            elif r != want:
                self.bad.append(
                    f"[상업무림혼용] {pid}.build_radius = {r!r} 인데 상업 반경표는 "
                    f"'{cls}' → **{want}** 라 말한다 — ★★ **어느 표에서 이 수가 왔는가?** "
                    f"`scale_system: commercial` 이면 **무림의 자로 반경을 산출하지 않는다** (규칙 1)")

    # ────────────────────────────────────────────────────────────────────
    def ladder_independence(self, com, mart):
        """★★★ **규칙 5 — 무림 반경표와 상업 반경표가 같은 객체나 참조를 공유하지 않는다.**

        ★ 사용자 원문: *"향후 무림 반경이 변경되어도 상업 반경은 자동으로 변하지 않으며, 반대의 경우도 같다."*

        ★★ **값이 같은 것은 죄가 아니다** (48 은 양쪽에 다 있다 — 그리고 그것이 옳다).
          죄는 **한 몸이 되는 것**이다. 그래서 이 눈은 값을 비교하지 않고 **정체(identity)를 본다**:

            ① 파이썬 객체 동일성 — YAML 별칭(`*bands`)은 파싱하면 **같은 객체**가 된다
            ② 하위 컨테이너의 공유 — 표 안쪽만 몰래 잇는 경우
            ③ ★ **원문의 앵커/별칭** — 스칼라 별칭(`trade_city: *hwasan`)은 int 로 풀려서
              ①②로 안 잡힌다 (작은 int 는 파이썬이 공유하므로 동일성이 무의미하다). **글자를 본다**
        """
        def containers(o, acc):
            if isinstance(o, dict):
                acc[id(o)] = o
                for v in o.values():
                    containers(v, acc)
            elif isinstance(o, list):
                acc[id(o)] = o
                for v in o:
                    containers(v, acc)
            return acc

        # ①② 객체 동일성 — 두 트리가 **한 컨테이너라도** 공유하면 위반이다
        shared = set(containers(com, {})) & set(containers(mart, {}))
        if shared:
            self.bad.append(
                "[사다리공유] ★★★ **무림 반경표와 상업 반경표가 같은 객체를 가리킨다** — "
                "두 사다리는 숫자가 같아도 **별개의 계약이다** (사용자 원문). "
                "★ 지금은 아무 증상이 없다. 그러나 **무림 반경을 한 번 고치는 날 상업 도시가 소리 없이 따라 움직인다** — "
                "아무도 그것을 명령하지 않았는데. **각 체계에 별도의 명시적 매핑을 둔다** (규칙 5)")

        # ③ ★★ 원문의 **YAML 별칭** — 스칼라를 이으면 파싱 뒤에 흔적이 없다.
        #    `trade_city: *hwasan_r` 은 그냥 int 64 가 되고, 작은 int 는 파이썬이 공유하므로
        #    ①②의 동일성 검사가 **아무것도 못 잡는다.** 그래서 **파서의 노드 그래프**를 본다 —
        #    별칭은 노드 트리에서 **같은 Node 객체**로 나타난다 (주석은 파서가 이미 지웠으므로
        #    이 절의 산문에 `*bands` 라고 **적어 두어도** 짖지 않는다 — 글자가 아니라 구조를 본다).
        self.alias_graph()

    def alias_graph(self):
        """★ YAML **노드 그래프**로 별칭(alias)을 잡는다 — 파싱 결과가 아니라 **문서의 구조**를 본다"""
        try:
            root = yaml.compose(self.raw)
        except yaml.YAMLError:
            self.warn.append("[사다리공유] world_map.yml 원문을 못 읽었다 — 참조 공유 검사를 못 했다")
            return
        if root is None:
            return
        top = {k.value: v for k, v in root.value}
        want = ("influence", "commercial_scale")
        if not all(k in top for k in want):
            return

        def nodes(n, acc):
            acc.add(id(n))
            if isinstance(n, yaml.MappingNode):
                for k, v in n.value:
                    nodes(k, acc)
                    nodes(v, acc)
            elif isinstance(n, yaml.SequenceNode):
                for v in n.value:
                    nodes(v, acc)
            return acc

        if nodes(top["influence"], set()) & nodes(top["commercial_scale"], set()):
            self.bad.append(
                "[사다리공유] ★★★ **`influence:` 와 `commercial_scale:` 이 YAML 별칭(alias)으로 이어져 있다** — "
                "★★ 스칼라 별칭은 파싱하면 그냥 수가 되어 **객체 동일성 검사를 빠져나간다.** "
                "그래서 눈이 **문서의 구조**를 본다. "
                "★ 두 사다리는 숫자가 같아도 **별개의 계약이다** — 참조로 잇지 않는다 (규칙 5)")

    # ────────────────────────────────────────────────────────────────────
    def exception_containment(self):
        """★★★ **일반 규칙은 명시적인 예외를 지우기 위해 존재하지 않는다** — 그리고 그 역도 참이다.

        ★ 녹림 석채는 **예외다**: §17 의 *"도적의 집은 영향력이 커도 목책이다"* 를 **폐기하지 않는다.**
          그 문장은 일반 산채(소채·중채·산길)에 그대로 산다.

        ★★ **이 눈이 지키는 것은 예외가 아니라 예외의 울타리다.**
          예외를 여는 열쇠는 martial 이 아니라 **`exception_rule` 이라는 이름**이다 —
          영향력이 큰 산채가 새로 생겨도 그 산채는 **여전히 목책이다** (이름을 받지 못했으므로).
          ★ 이름 없이 석축을 두르면 **그때부터 모든 산채가 석성이 된다.** 그것을 막는다.
        """
        exc = (((self.wm.get("influence") or {}).get("ladder") or {}).get("exceptions")) or {}
        for name, rule in exc.items():
            allowed = set(rule.get("only") or [])
            arch = rule.get("archetype")
            fort = rule.get("fortification")
            if not allowed:
                self.bad.append(
                    f"[예외무한] §17 exceptions.{name} 에 `only:` 가 없다 — "
                    f"★★ **울타리 없는 예외는 예외가 아니라 새 규칙이다**")
                continue
            for _sec, pid, pl in self.places():
                if pid in allowed:
                    continue
                if arch and pl.get("archetype") == arch:
                    self.bad.append(
                        f"[예외전파] {pid}.archetype = '{arch}' — ★★★ 그 원형은 "
                        f"**{'·'.join(sorted(allowed))} 의 것이다** (§17 exceptions.{name}.only). "
                        f"★ 도적의 집은 영향력이 커도 **목책이다** — 총채의 석축은 상속되지 않는다")
                if fort and pl.get("fortification") == fort:
                    self.bad.append(
                        f"[예외전파] {pid}.fortification = '{fort}' — ★★★ 석축은 "
                        f"**{'·'.join(sorted(allowed))} 만의 것이다** (§17 exceptions.{name}). "
                        f"★ **부유한 산채조차 목책을 유지하고 내부 시설만 확대한다**")
                if pl.get("exception_rule") == name:
                    self.bad.append(
                        f"[예외전파] {pid}.exception_rule = '{name}' — ★★★ 이 예외의 이름을 "
                        f"**{'·'.join(sorted(allowed))} 밖에서 쓸 수 없다.** 이름이 곧 울타리다")

    # ────────────────────────────────────────────────────────────────────
    def docks(self):
        """★★★ 섬 — 검수 ④(연결성) **면제의 대가**.

        ★ 사용자 결정 (2026-07-13):
            *"섬은 검수 ④(연결성) **면제**. 대신 「나루가 있는가」를 재는 새 축을 그 자리에 세워라.
              섬은 빚되 **배가 닿는 자리를 반드시 가져야 한다.**"*

        ★★ **면제는 은닉이 아니다** — 팩 담당의 NON_ICON 이 가르쳐 준 교훈 그대로:
          *"축이 시끄럽다고 파일을 숨기면 축은 조용해지고 세계가 깨진다."*
          **면제는 '재지 않는다'가 아니라 '다른 자로 잰다'는 뜻이다.**

        ★★★ 그러므로 이 눈은 **면제 자체를 검사한다**:
          ① 면제가 **등록돼 있는가** (terrain.yml audit.connectivity_exempt)
          ② 그 자리에 **설 축이 있는가** (replaced_by) — 없으면 면제가 아니라 **구멍**이다
          ③ 면제받는 장소마다 **나루가 등록됐는가** — 배가 닿을 자리가 없으면 섬이 섬이 아니다
        """
        audit = self.terrain.get("audit") or {}
        exempt = audit.get("connectivity_exempt") or {}
        dock = audit.get("dock_axis") or {}
        applies = set(dock.get("applies_to") or [])
        registered = set((dock.get("places") or {}).keys())

        for _sec, pid, pl in self.places():
            t = pl.get("terrain")
            if t not in exempt:
                continue
            rule = exempt.get(t) or {}
            replaced = rule.get("replaced_by")
            # ② 면제만 있고 대신 설 축이 없다 = **구멍**
            if not replaced:
                self.bad.append(
                    f"[면제구멍] {pid} — terrain '{t}' 이 검수 ④ 를 면제받는데 "
                    f"**대신 설 축이 없다** (terrain.yml audit.connectivity_exempt.{t}.replaced_by 없음). "
                    f"★★ 그것은 면제가 아니라 **구멍**이다")
                continue
            if replaced != "나루":
                continue
            # ③ 나루 축이 이 지형에 걸려 있는가
            if t not in applies:
                self.bad.append(
                    f"[나루없음] terrain '{t}' 이 「나루」로 대체된다는데 "
                    f"audit.dock_axis.applies_to 에 '{t}' 가 **없다** — 축이 안 걸린다")
            # ③ 이 장소가 나루를 등록했는가
            if pid not in registered:
                self.bad.append(
                    f"[나루없음] {pid} — terrain '{t}' 인데 terrain.yml audit.dock_axis.places 에 "
                    f"**나루가 등록되지 않았다.** ★★ 섬은 빚되 **배가 닿는 자리를 반드시 가져야 한다** "
                    f"(면제만 받고 축을 안 세우면 그것은 **구멍**이다)")

        # ★ 반대 방향 — 나루를 등록해 놓고 **섬이 아닌** 곳
        island_ids = {pid for _s, pid, pl in self.places()
                      if pl.get("terrain") in exempt}
        for pid in sorted(registered - island_ids):
            self.warn.append(
                f"[나루잉여] terrain.yml audit.dock_axis.places 에 '{pid}' 가 있는데 "
                f"지도에서 **섬이 아니다** (또는 장소가 없다) — 등록부 둘이 갈라졌다")

    # ────────────────────────────────────────────────────────────────────
    def two_layers(self, req):
        """★★ 2계층 계약 — **집이 제가 선 땅보다 크면 안 된다.**

        지형 계층이 빚는 반경(MvtCommand.forgeRadius)과 건축 계층이 쓰는 반경(build_radius)은
        **다른 층의 수**다. 지형이 건축을 따라가면 안 된다 — *"건축이 바뀌어도 지형은 변하면 안 된다"* —
        그래서 forgeRadius 를 build_radius 로 잇지 **않았다**.

        ★ 그러나 잇지 않았기 때문에 **조용히 어긋날 수 있다**: build_radius 가 forgeRadius 를 넘으면
          집이 **빚지 않은 땅** 위로 걸어 나간다. 조성은 아무 말 없이 성공하고, 건물 절반이 벼랑에 걸린다.
          **이 눈이 그것을 막는다.** (지금은 최대 build_radius 80 < 110 이라 여유가 있다)

        ★★ 2026-07-13 — 이 눈이 **자바를 안 읽는다.** 땅의 반경이 등록부로 옮겨 왔기 때문이다 (§1-b land):
          예전엔 `forgeRadius = noklim ? 24 : 110` 을 **정규식으로** 긁었다 — 그때는 그 수가 코드에만 있었다.
          ★ 이제 등록부가 원천이고, **코드에 수가 박히면 배선 검사(BANNED_RE)가 따로 짖는다.** 눈을 갈랐다.
        """
        land = self.wm.get("land") or {}
        forge = land.get("forge_radius")
        if not isinstance(forge, int):
            self.bad.append(
                "[2계층] world_map.yml §1-b `land.forge_radius` 가 없다 — "
                "★★ **땅의 반경을 등록부가 말하지 않는다.** 그러면 코드가 정한다 (그것이 방금 고친 병이다). "
                "★ 그리고 **집이 땅보다 큰지 잴 수가 없다**")
            return
        for sec, pid, pl in self.places():
            if sec not in (req.get("buildable_sections") or ["places"]):
                continue
            r = pl.get("build_radius")
            if isinstance(r, int) and r > forge:
                self.bad.append(
                    f"[2계층위반] {pid}.build_radius = {r} > 지형이 빚는 반경 {forge} — "
                    f"★ **집이 빚지 않은 땅 위로 나간다.** 조성은 조용히 성공하고 건물이 벼랑에 걸린다")

    # ────────────────────────────────────────────────────────────────────
    def census(self, req, arch_reg):
        """★★ **지도가 주문한 곳 중 지금 코드가 실제로 세울 수 있는 곳은 몇 곳인가.**

        ★★★ 이 눈이 있는 이유 — **"짓지 않으면 위반이 없다."**
          원형이 없으면 RemoteBuilder.build 가 **빈 목록**을 돌려준다 → Zone 이 없다 →
          검수(RegionAudit)가 **안 불린다** → **위반 0건**이 찍힌다 → **침묵이 성공으로 읽힌다.**
          이 프로젝트가 이미 당한 병이다. 그러므로 **안 서는 집을 세어서 이름을 부른다.**
        """
        code = code_archetypes()
        if code is None:
            self.bad.append("[검산] RemoteBuilder.Archetype enum 을 못 읽었다 — **검산을 못 했다**")
            return
        # ★ 지도의 주장(§16 registered)과 코드의 사실을 대조한다 — 갈라지면 지도가 거짓말 중이다
        claimed = set((arch_reg.get("registered") or {}).keys())
        for ghost in sorted(claimed - code):
            self.bad.append(f"[검산] §16 registered 에 '{ghost}' 이 있는데 **코드엔 없다** — 지도가 거짓말한다")
        for orphan in sorted(code - claimed):
            self.bad.append(f"[검산] 코드가 '{orphan}' 을 지을 줄 아는데 **§16 registered 에 없다** — 지도가 모른다")

        self.code_archetypes = code
        buckets = collections.defaultdict(list)
        for sec, pid, pl in self.places():
            if sec not in (req.get("buildable_sections") or ["places"]):
                continue                                     # 사냥터·폐허·산지 — **집이 없는 것이 옳다**
            if pl.get("build") == "never":
                buckets["안_짓기로_함"].append(pid)
                continue
            if req.get("exempt_if") and pl.get(req["exempt_if"]):
                buckets["전용_조성기"].append(pid)           # 청하현·산길 도적 — CheonghaBuilder 가 짓는다
                continue
            a, r = pl.get("archetype"), pl.get("build_radius")
            if a is None:
                buckets["✗ 지도가_침묵"].append(pid)
            elif a == "pending":
                buckets["✗ 미결 — 사람이_정해야"].append(pid)
            elif a not in code:
                buckets[f"✗ 청구됨·손이_없다 ({a})"].append(pid)
                # ★ 경고로도 센다 — 검산표만 찍고 넘어가면 **그 줄이 곧 침묵이 된다.**
                #   이건 지도의 흠이 아니라 **건축 계층의 빚**이다. 그러나 빚은 소리내어 세야 갚는다.
                self.warn.append(f"[미구현] {pid}.archetype = '{a}' — 지도가 청구했으나 "
                                 f"RemoteBuilder 에 그 원형이 **없다**. 조성해도 **아무것도 안 선다** (§16 requested)")
            elif r == "unresolved":
                # ★★★ **모르는 장소를 작은 장소로 간주하지 않는다** — 그래서 안 선다. **그것이 옳다**
                buckets["✗ 미결(unresolved) — 근거가_없다"].append(pid)
            elif not isinstance(r, int):
                buckets["✗ build_radius_없음"].append(pid)
            else:
                buckets["✓ 선다"].append(pid)
        self.buckets = buckets


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
    census(lint)
    unresolved(lint)
    print("── 총평 ──")
    if lint.missing:
        top = " · ".join(f"{k} {v}곳" for k, v in lint.missing.most_common())
        print(f"  필수 누락 분포: {top}")
    print(f"{'✓ 위반 0건' if not lint.bad else f'✗ 위반 {len(lint.bad)}건'}"
          f"  ·  미결/경고 {len(lint.warn)}건 (★ 사람이 정해야 한다)"
          f"  ·  ★ 미결(unresolved) {len(lint.unresolved_places)}곳 (**위반이 아니다 — 정직이다**)")
    return 0 if not lint.bad else 1


def unresolved(lint):
    """★★★ 미결(unresolved) — **채우라고 짖지 않는다. 다만 소리내어 센다.**

    ★ 사용자 결정 (2026-07-13 · docs/design/scale_systems.md):
        *"근거가 부족한 장소는 `commercial_class: unresolved` 로 남긴다.
          **`unresolved` 는 실패가 아니라 정직이다.**"*

    ★★ 지금까지 린트는 미결을 **채우라고 짖기만** 했고, 채우려면 **지어내야** 했다.
      이제 "모른다"고 적을 수 있는 칸이 있다. 그러나 **조용해서는 안 된다** —
      침묵과 미결은 다르다. 그래서 **위반으로 세지 않되, 매번 이름을 부른다.**
    """
    if not lint.unresolved:
        return
    print()
    print(f"══ ★ 미결 (unresolved) — **{len(lint.unresolved_places)}곳** ══")
    print("   (★ 위반이 아니다. **근거가 없어서 비워 둔 것**이고, 그것이 옳다 — 채우려면 지어내야 한다)")
    for field, n in lint.unresolved.most_common():
        who = sorted(pid for _s, pid, pl in lint.places() if pl.get(field) == "unresolved")
        print(f"  {field:20} {n:2}곳   {' '.join(who)}")
    print("   ★★ **채우는 것은 사람의 결정이다.** 린트는 세기만 한다")


def census(lint):
    """★★ 조성 검산 — **지금 `/혼천 지역조성` 을 다 돌리면 무엇이 서는가.**

    ★ 이 표가 이 도구의 나침반이다. **위반 0건은 "다 됐다"는 뜻이 아니다** —
      안 짓고 있으면 위반도 안 나온다. 그러므로 **안 서는 집을 세어 이름을 부른다.**
    """
    b = getattr(lint, "buckets", None)
    if not b:
        return
    total = sum(len(v) for v in b.values())
    ok = len(b.get("✓ 선다", []))
    print()
    print(f"══ ★ 조성 검산 — 집이 설 자리 {total}곳 중 **지금 서는 곳 {ok}곳** ══")
    print(f"   (코드가 아는 원형: {' · '.join(sorted(lint.code_archetypes))})")
    for key in sorted(b, key=lambda k: (not k.startswith("✓"), k)):
        pids = b[key]
        print(f"  {key:34} {len(pids):3}곳  {' '.join(sorted(pids))}")
    silent = total - ok - len(b.get("안_짓기로_함", [])) - len(b.get("전용_조성기", []))
    if silent:
        print(f"  ★★ 그러므로 **{silent}곳은 조성을 쳐도 조용히 아무것도 안 선다.**")
        print("     검수는 그 침묵을 위반으로 세지 않는다 — **'짓지 않으면 위반이 없다'** 는 그 병이다.")


def build():
    return Lint(load(CONFIG / "world_map.yml"),
                load(CONFIG / "factions.yml"),
                load(CONFIG / "terrain.yml"),
                collect_region_files(),
                load(CONFIG / "faction_politics.yml"))


def selftest():
    """★ 눈을 시험한다 — 일부러 병을 넣고 **눈이 짖는지** 본다.
    (오늘 이 프로젝트에서 눈이 스무 번 넘게 거짓말했다. 짖지 않는 눈은 눈이 아니다.)"""
    wm = load(CONFIG / "world_map.yml")
    fac = load(CONFIG / "factions.yml")
    ter = load(CONFIG / "terrain.yml")
    pol = load(CONFIG / "faction_politics.yml")
    rfs = collect_region_files()
    cases = []

    def probe(name, mutate, expect):
        import copy
        w = copy.deepcopy(wm)
        mutate(w)
        lt = Lint(w, fac, ter, rfs, pol)
        lt.run()
        hits = [x for x in lt.bad + lt.warn if expect in x]
        cases.append((name, bool(hits), hits[0] if hits else "— 짖지 않았다"))

    def probe_cfg(name, mut_ter, mut_pol, expect):
        """★ 병을 **다른 등록부**에 심는다 (terrain.yml · faction_politics.yml)"""
        import copy
        t, p = copy.deepcopy(ter), copy.deepcopy(pol)
        if mut_ter:
            mut_ter(t)
        if mut_pol:
            mut_pol(p)
        lt = Lint(wm, fac, t, rfs, p)
        lt.run()
        hits = [x for x in lt.bad + lt.warn if expect in x]
        cases.append((name, bool(hits), hits[0] if hits else "— 짖지 않았다"))

    def probe_quiet(name, mutate, forbidden, evidence):
        """★★ **반대 방향의 시험** — 짖으면 **안 되는** 것에 짖는가.

        ★ 눈이 지나치게 짖으면 사람이 눈을 끈다. 그리고 꺼진 눈은 없는 눈이다.
          특히 **`unresolved` 는 정직이다** — 그것을 위반으로 세면 사람은 다시 **지어내기** 시작한다.
        """
        import copy
        w = copy.deepcopy(wm)
        if mutate:
            mutate(w)
        lt = Lint(w, fac, ter, rfs, pol)
        lt.run()
        noisy = [x for x in lt.bad if forbidden in x]
        cases.append((name, not noisy, evidence if not noisy else f"✗ 짖었다: {noisy[0]}"))

    probe("필수 필드를 지운다 (hwasan.terrain)",
          lambda w: w["places"]["hwasan"].pop("terrain"), "[필수누락] hwasan.terrain")
    probe("어휘 밖의 값 (hwasan.tier = 대단히부자)",
          lambda w: w["places"]["hwasan"].update(tier="대단히부자"), "[어휘위반]")
    probe("등록되지 않은 세력 (hwasan.faction = 매화신교)",
          lambda w: w["places"]["hwasan"].update(faction="매화신교"), "[어휘위반] hwasan.faction")
    # ★ 처음엔 jongnam 을 썼는데, 이번 바퀴에 종남이 archetype(암자)을 얻어 pending_why 가 사라졌다 —
    #   **시험이 지도를 따라와야 한다.** (그 KeyError 가 곧 눈이 살아 있다는 증거다.)
    probe("사유 없는 pending (침묵)",
          lambda w: w["places"]["jeomchang"].pop("pending_why"), "[침묵] jeomchang")
    # ★ 여기서 한 번 속았다: 기대값이 "[일회성키]" 였더니 **원래 있던** 일회성 키('haegeum(海禁)')에
    #   맞고 통과했다 — 심은 병이 아니라 남의 병을 보고 짖은 것이다. 기대값은 **심은 것을 정확히** 가리켜야 한다.
    probe("스키마 드리프트 (일회성 키)",
          lambda w: w["places"]["hwasan"].update(매화나무수="많음"), "[일회성키] '매화나무수'")
    probe("인구 등록부와 어긋나는 원형 (hwasan.archetype = 산채)",
          lambda w: w["places"]["hwasan"].update(archetype="산채"), "[근거없음] hwasan.archetype")
    probe("없는 인구 파일 (hwasan.population)",
          lambda w: w["places"]["mudang"].update(population="config/npcs/regions/mudang.yml"),
          "[근거없음] mudang.population")
    probe("★ 새 필수 필드를 지운다 (hwasan.build_radius — 코드가 마을 크기를 정하게 된다)",
          lambda w: w["places"]["hwasan"].pop("build_radius"), "[필수누락] hwasan.build_radius")
    probe("청구된 원형 밖의 이름 (sorimsa.archetype = 대웅전)",
          lambda w: w["places"]["sorimsa"].update(archetype="대웅전"), "[어휘위반] sorimsa.archetype")
    probe("산악인데 terrain.yml 이 침묵 (새 장소)",
          lambda w: w["places"].update(시험봉={"name": "시험봉", "pos": [1, 1], "region": "섬서",
                                              "terrain": "험산", "build": "never", "access": "항상"}),
          "[추측자리] 시험봉")

    # ─── ★ 새 눈(배선·검산)을 시험한다 — 배선은 **코드**를 읽으므로 병도 코드에 심어야 한다 ───
    # ★★ 2026-07-13: 구판은 `sorimsa.사찰` 을 썼다. 그런데 건축 담당이 **사찰에 손을 붙였다**
    #   (RemoteBuilder.Archetype 5 → 21 · SectBuilder). 그러자 린트가 옳게 조용해졌고 **이 시험이 깨졌다.**
    #   ★ 그것은 눈이 틀린 게 아니라 **시험이 지도를 못 따라온 것**이다 (이 파일이 이미 배운 교훈:
    #     "종남이 archetype 을 얻자 pending_why 가 사라졌다 — **시험이 지도를 따라와야 한다**").
    #   ★★ 지금 **손이 없는 유일한 청구 원형은 기관저택(제갈)**이다 — 계약이 pending 이라 코드가 안 만들었다 (Q-B).
    probe("★ 지도가 청구했으나 코드에 손이 없는 원형 (jegal.기관저택) — **조성해도 안 선다**",
          lambda w: None, "[미구현] jegal.archetype = '기관저택'")
    probe("★ 2계층 — 집이 **빚지 않은 땅** 위로 나간다 (hwasan.build_radius = 400)",
          lambda w: w["places"]["hwasan"].update(build_radius=400), "[2계층위반] hwasan.build_radius = 400")
    probe("★ §16 registered 가 코드에 없는 원형을 주장한다 (유령 원형)",
          lambda w: w["archetypes"]["registered"].update(수정궁={"계약": "없다"}),
          "[검산] §16 registered 에 '수정궁' 이 있는데 **코드엔 없다**")
    probe("★ 코드는 지을 줄 아는데 §16 registered 가 모른다 (도관을 지운다)",
          lambda w: w["archetypes"]["registered"].pop("도관"),
          "[검산] 코드가 '도관' 을 지을 줄 아는데 **§16 registered 에 없다**")

    def probe_code(name, inject, expect):
        """★ **코드에 병을 심는다** — 지운 하드코딩이 돌아오면 눈이 짖는가.

        (지운 것은 돌아온다. 급할 때 목록 한 줄이 제일 빠르기 때문이다 — 그래서 이 시험이 있다)
        """
        global READ_JAVA
        original = READ_JAVA
        READ_JAVA = lambda p: original(p) + inject.get(p.name, "")   # noqa: E731
        try:
            lt = Lint(wm, fac, ter, rfs)
            lt.run()
            hits = [x for x in lt.bad + lt.warn if expect in x]
        finally:
            READ_JAVA = original
        cases.append((name, bool(hits), hits[0] if hits else "— 짖지 않았다"))

    probe_code("★ RemoteBuilder 에 문파 목록(SECTS)이 되돌아온다",
               {"RemoteBuilder.java": '\nSet<String> SECTS = Set.of("hwasan");\n'},
               "[배선위반] RemoteBuilder.java 에 `SECTS`")
    probe_code("★ TerrainForge 에 봉우리 문파 목록(PEAK_FACTIONS)이 되돌아온다",
               {"TerrainForge.java": '\nSet<String> PEAK_FACTIONS = Set.of("hwasan");\n'},
               "[배선위반] TerrainForge.java 에 `PEAK_FACTIONS`")
    probe_code("★ TerrainForge 가 다시 **산문을 뒤진다** (note.contains)",
               {"TerrainForge.java": '\nif (note.contains("동굴")) return CaveKind.은신처;\n'},
               "[배선위반] TerrainForge.java 에 `note.contains(`")
    probe_code("★ siteRadius 가 다시 **코드로 마을 크기를 정한다**",
               {"RemoteBuilder.java": '\nreturn "noklim".equals(place.faction()) ? 24 : 64;\n'},
               "[배선위반] RemoteBuilder.java 에 `\"noklim\".equals(place.faction()) ? 24 : 64`")

    # ═══════════════════════════════════════════════════════════════════════
    # ★★ B-151 — 표시 축의 눈들 (hidden / player_map · 2026-07-15)
    # ═══════════════════════════════════════════════════════════════════════

    probe("★★ B-151 — 숨은 세력의 거점에서 **숨김을 벗긴다** (magyo_jinryeong.hidden·player_map 삭제)\n"
          "      → 마교 전초가 4일 거리라는 사실 자체가 스포일러다",
          lambda w: (w["places"]["magyo_jinryeong"].pop("hidden"),
                     w["places"]["magyo_jinryeong"].pop("player_map")),
          "[숨김누락] magyo_jinryeong")
    probe("★★ B-151 — 두 표기가 **서로 반대말이 아니다** (magyo_bonkyo.player_map = true, hidden = true)",
          lambda w: w["places"]["magyo_bonkyo"].update(player_map=True),
          "[표시모순] magyo_bonkyo")
    probe("★★ B-151 — 문자열 'true' 는 **숨기지 못한다** (dokmun.hidden = 'true') — 독자는 불리언만 읽는다",
          lambda w: w["places"]["dokmun"].update(hidden="true"),
          "[표시타입] dokmun.hidden")

    def probe_gone(name, fname, needle, expect):
        """★★ **독자를 지워 본다** — B-151 은 '독자가 없다'는 빚이었다. 독자가 다시 사라지면 짖어야 한다"""
        global READ_JAVA
        original = READ_JAVA
        READ_JAVA = lambda p: (original(p).replace(needle, " ")   # noqa: E731
                               if p.name == fname else original(p))
        try:
            lt = Lint(wm, fac, ter, rfs)
            lt.run()
            hits = [x for x in lt.bad if expect in x]
        finally:
            READ_JAVA = original
        cases.append((name, bool(hits), hits[0] if hits else "— 짖지 않았다"))

    probe_gone("★★★ B-151 — 지도 렌더에서 **숨김 차단을 지운다** (MvtCommand 의 .hidden() 소실)\n"
               "      → 등록부는 멀쩡하므로 등록부만 보는 눈은 이 퇴행을 영영 모른다",
               "MvtCommand.java", ".hidden()", "[독자소실] MvtCommand.java")
    probe_gone("★★★ B-151 — WorldMap 이 hidden 을 **더는 읽지 않는다** — 등록만 되고 아무도 안 숨긴다",
               "WorldMap.java", '"hidden"', "[독자소실] WorldMap.java")

    probe_quiet("★★ B-151 — 새외에 숨김을 **요구하지 않는가** (bukmak — 새외는 알려져 있다. 다만 멀다)",
                None, "[숨김누락] bukmak",
                "✓ 조용했다 — 새외는 player_map: true 다. **못 갈 뿐, 지도에 있다** (§5.9 두 축의 정의)")
    probe_quiet("★★ B-151 — hidden 만 적고 player_map 을 안 적은 곳(dokmun)에 **모순을 씌우지 않는가**",
                None, "[표시모순] dokmun",
                "✓ 조용했다 — 한쪽 표기만으로도 숨는다 (독자가 OR 로 읽는다). 모순은 **둘 다 있고 어긋날 때**다")

    # ═══════════════════════════════════════════════════════════════════════
    # ★★★ 크기의 자 — 2026-07-13 사용자 결정의 눈들 (docs/design/scale_systems.md 외)
    # ═══════════════════════════════════════════════════════════════════════

    # ── ① 녹림 석채의 석축 예외가 **다른 산채로 전파**되는가 (사용자가 지목한 시험) ──
    probe("★★★ 녹림 석채의 예외를 **다른 산채에 전파** (nokrim_sochae.archetype = 녹림석채)",
          lambda w: w["places"]["nokrim_sochae"].update(archetype="녹림석채"),
          "[예외전파] nokrim_sochae.archetype = '녹림석채'")
    probe("★★★ 석축만 슬쩍 옮긴다 (nokrim_jungchae.fortification = stone) — **이름 없이 예외를 훔친다**",
          lambda w: w["places"]["nokrim_jungchae"].update(fortification="stone"),
          "[예외전파] nokrim_jungchae.fortification = 'stone'")
    probe("★★★ 예외의 **이름**을 훔친다 (nokrim_sochae.exception_rule = greenwood_headquarters)",
          lambda w: w["places"]["nokrim_sochae"].update(exception_rule="greenwood_headquarters"),
          "[예외전파] nokrim_sochae.exception_rule = 'greenwood_headquarters'")
    probe("★★ 예외의 **울타리를 없앤다** (§17 exceptions.only 삭제) — 울타리 없는 예외는 **새 규칙**이다",
          lambda w: w["influence"]["ladder"]["exceptions"]["greenwood_headquarters"].pop("only"),
          "[예외무한]")

    # ── ② 상업 거점에 **무력을 부여**하는가 (사용자가 지목한 시험) ──
    probe("★★★ 상업 거점에 **martial 부여** (taewon.martial = 8) — 상인의 도시를 무력으로 잰다",
          lambda w: w["places"]["taewon"].update(martial=8),
          "[상업위반] taewon.martial")
    probe("★★★ 상업 거점에 **mandate_weight 부여** (soju.mandate_weight = 2)",
          lambda w: w["places"]["soju"].update(mandate_weight=2),
          "[상업위반] soju.mandate_weight")
    probe_cfg("★★★ **`sangdan` 을 roster 에 넣는다** — 없는 무력을 지어내는 가장 빠른 길",
              None,
              lambda p: p["roster"].update(sangdan={"name": "상단연합", "martial": 4, "mandate_weight": 1}),
              "[상업위반] faction_politics.yml roster 에 'sangdan'")

    # ── ③ 섬에서 **나루를 제거**하는가 (사용자가 지목한 시험) ──
    probe_cfg("★★★ **섬에서 나루를 뺀다** (haenam) — 배가 닿을 자리가 없는 섬",
              lambda t: t["audit"]["dock_axis"]["places"].pop("haenam"),
              None,
              "[나루없음] haenam")
    probe_cfg("★★★ **면제만 받고 축을 안 세운다** (replaced_by 제거) — 그것은 면제가 아니라 **구멍**이다",
              lambda t: t["audit"]["connectivity_exempt"]["섬"].pop("replaced_by"),
              None,
              "[면제구멍]")
    probe_cfg("★★ 나루 축을 섬에서 **떼어낸다** (applies_to 비움)",
              lambda t: t["audit"]["dock_axis"].update(applies_to=[]),
              None,
              "[나루없음]")

    # ═══════════════════════════════════════════════════════════════════════
    # ★★★ 상업 반경의 일곱 규칙 — **사용자가 열거했고, 일곱을 다 어겨 본다** (2026-07-13)
    #
    #   ★ 사용자가 특히 두 가지를 지목했다:
    #     *"특히 **규칙 5(참조 공유)**: 두 표가 같은 객체를 가리키게 만들어 놓고 눈이 잡는지 보라.
    #       그리고 **`unresolved` → 40 자동 대체**를 심어 놓고 잡는지 보라"*
    # ═══════════════════════════════════════════════════════════════════════

    # ── 규칙 1 — `scale_system: commercial` 인데 **무림의 자로 잰다** ──
    probe("★★★ 규칙1 — 상업 거점의 반경을 **다른 표에서 집어 온다** (muhan.build_radius = 48)",
          lambda w: w["places"]["muhan"].update(build_radius=48),
          "[상업무림혼용] muhan.build_radius = 48")
    probe("★★★ 규칙1 — 상업 거점에 **무력을 부여** (taewon.martial = 8): 상인의 도시를 무력으로 잰다",
          lambda w: w["places"]["taewon"].update(martial=8),
          "[상업위반] taewon.martial")

    # ── 규칙 2 — 확정된 등급이 **상업 반경표의 키와 일치**해야 한다 ──
    probe("★ 규칙2 — 표에서 등급을 **지운다** (radius_ladder 에서 trade_city 제거) → 무한이 크기를 잃는다",
          lambda w: w["commercial_scale"]["radius_ladder"].pop("trade_city"),
          "[상업등급어휘] muhan.commercial_class = 'trade_city'")
    probe("★ 규칙2 — 표에 **없는 등급을 넣는다** (radius_ladder.mega_hub = 96)",
          lambda w: w["commercial_scale"]["radius_ladder"].update(mega_hub=96),
          "[상업등급어휘] radius_ladder 에 'mega_hub'")

    # ── ★★★ 규칙 3 — **`unresolved` → 40 자동 대체** (사용자가 직접 지목한 시험) ──
    probe("★★★ 규칙3 — **`unresolved` 를 40 으로 자동 대체한다** (taewon.build_radius = 40)\n"
          "      → *모르는 장소를 작은 장소로 간주하는 것도 하나의 창작이다*",
          lambda w: w["places"]["taewon"].update(build_radius=40),
          "[상업미결대체] taewon.build_radius = 40")
    probe("★★ 규칙3 — 미결을 **pending 으로 얼버무린다** (soju.build_radius = pending) — 미결은 미결로 적는다",
          lambda w: w["places"]["soju"].update(build_radius="pending"),
          "[상업미결] soju.commercial_class 가 unresolved")
    probe("★★ 규칙3(역) — 등급은 **확정됐는데** 반경을 미결로 둔다 (muhan.build_radius = unresolved)\n"
          "      → **아는 것을 모른다고 적는 것도 거짓이다**",
          lambda w: w["places"]["muhan"].update(build_radius="unresolved"),
          "[상업미결역행] muhan.commercial_class = 'trade_city'")

    # ── 규칙 4 — 상업 거점에 **수동 반경을 함께 적지 않는다** ──
    probe("★★ 규칙4 — 등급 옆에 **수동 반경**을 슬쩍 적는다 (muhan.radius = 96)",
          lambda w: w["places"]["muhan"].update(radius=96),
          "[상업수동반경] muhan.radius")

    # ── ★★★ 규칙 5 — **두 표가 같은 객체·참조를 공유한다** (사용자가 직접 지목한 시험) ──
    probe("★★★ 규칙5 — **두 표를 한 몸으로 만든다** (radius_ladder ← influence.ladder.bands 같은 객체)\n"
          "      → 값은 그대로다. 증상도 없다. **무림 반경을 고치는 날 상업 도시가 소리 없이 따라 움직인다**",
          lambda w: w["commercial_scale"].update(radius_ladder=w["influence"]["ladder"]["bands"]),
          "[사다리공유]")
    probe("★★★ 규칙5 — 표 **안쪽만** 몰래 잇는다 (pinned_by_archetype 를 상업표 안으로)",
          lambda w: w["commercial_scale"]["radius_ladder"].update(
              _borrowed=w["influence"]["ladder"]["pinned_by_archetype"]),
          "[사다리공유]")

    def probe_raw(name, raw, expect):
        """★★ **원문에 병을 심는다** — YAML 별칭(`*bands`)은 **파싱하면 흔적이 없다.**

        ★ 스칼라 별칭(`trade_city: *hwasan_r`)은 그냥 int 64 가 되고, 작은 int 는 파이썬이 공유하므로
          객체 동일성 검사가 **아무것도 못 잡는다.** 그래서 눈이 **문서의 구조(노드 그래프)**를 본다.
        """
        lt = Lint(yaml.safe_load(raw), fac, ter, rfs, pol, raw=raw)
        lt.run()
        hits = [x for x in lt.bad + lt.warn if expect in x]
        cases.append((name, bool(hits), hits[0] if hits else "— 짖지 않았다"))

    raw_ok = (CONFIG / "world_map.yml").read_text(encoding="utf-8")
    # ★ 상업 표에 **앵커**를 달고, 무림 표(bands)가 그것을 **별칭으로 가져간다**.
    #   ★★ 두 표가 **한 몸**이 된다 — 그런데 `yaml.safe_load` 결과만 보면 그냥 dict 둘이다.
    #     (방향은 상관없다. commercial_scale 이 파일에서 먼저 오므로 앵커를 거기 단다 — YAML 규칙)
    raw_alias = re.sub(r"^  radius_ladder:.*$", "  radius_ladder: &shared_ladder",
                       raw_ok, count=1, flags=re.M)
    raw_alias = re.sub(r"^    bands:\n(?:      .*\n)+", "    bands: *shared_ladder\n",
                       raw_alias, count=1, flags=re.M)
    probe_raw("★★★ 규칙5 — **YAML 별칭으로 두 표를 잇는다** (`bands: *shared_ladder`)\n"
              "      → 파싱하면 흔적이 없다. **글자가 아니라 문서의 구조**를 봐야 잡힌다",
              raw_alias, "[사다리공유]")

    # ── 규칙 6 — **건축 원형이 상업 등급을 바꾼다** ──
    probe("★★ 규칙6 — 건축 원형이 **상업 등급을 정한다** (§16 registered.도관.commercial_class = trade_city)",
          lambda w: w["archetypes"]["registered"]["도관"].update(commercial_class="trade_city"),
          "[원형월권] §16 archetypes.registered.도관")

    # ── 규칙 7 — **상업 등급이 건축 원형을 고른다** ──
    probe("★★ 규칙7 — 상업 등급표가 **원형을 고른다** (commercial_scale.archetype = 상단)",
          lambda w: w["commercial_scale"].update(archetype="상단"),
          "[상업등급월권] §17-b commercial_scale 에 'archetype'")
    probe("★★ 규칙7 — 반경표가 **수 대신 집을 말한다** (radius_ladder.trade_city = '상단 회관')",
          lambda w: w["commercial_scale"]["radius_ladder"].update(trade_city="상단 회관"),
          "[상업등급월권] radius_ladder.trade_city")

    # ── ★★★ 땅의 반경 — **어디나 같은 값** ──
    probe("★★★ 땅 — 등록부에서 `land.forge_radius` 를 **지운다** → 집이 땅보다 큰지 **잴 수가 없다**",
          lambda w: w["land"].pop("forge_radius"),
          "[2계층] world_map.yml §1-b `land.forge_radius` 가 없다")
    probe_code("★★★ 땅 — **마지막 하드코딩이 돌아온다** (`forgeRadius = noklim ? 24 : 110`)\n"
               "      → *땅은 세력을 모른다. 산은 문파가 흥하든 망하든 그 자리에 그만큼 있다*",
               {"MvtCommand.java":
                '\nint forgeRadius = "noklim".equals(place.faction()) ? 24 : 110;\n'},
               "[배선위반] MvtCommand.java")
    probe_code("★★★ 땅 — 하드코딩이 **모양을 바꿔** 돌아온다 (`int forgeRadius = 96;`)\n"
               "      → 문자열 목록은 다음 하드코딩을 못 막는다. **수가 박히는 자리**를 지킨다",
               {"MvtCommand.java": "\nint forgeRadius = 96;\n"},
               "[배선위반] MvtCommand.java")
    probe_code("★★ 땅 — 세력이 **청크 선적 반경**으로 되돌아온다 (`? 64 : 150`)",
               {"MvtCommand.java":
                '\nint pre = "noklim".equals(place.faction()) ? 64 : 150;\n'},
               '[배선위반] MvtCommand.java 에 `"noklim".equals(place.faction()) ? 64 : 150`')

    # ── ④ 새 어휘 — 자와 등급 ──
    probe("★ 어휘 밖의 자 (hwasan.scale_system = 무력적)",
          lambda w: w["places"]["hwasan"].update(scale_system="무력적"),
          "[어휘위반] hwasan.scale_system")
    probe("★ 어휘 밖의 상업 등급 (taewon.commercial_class = 대도시)",
          lambda w: w["places"]["taewon"].update(commercial_class="대도시"),
          "[어휘위반] taewon.commercial_class")
    probe("★ 새 필수 필드를 지운다 (hwasan.scale_system) — **어느 자로 잴지 모르면 크기를 못 정한다**",
          lambda w: w["places"]["hwasan"].pop("scale_system"),
          "[필수누락] hwasan.scale_system")

    # ═══════════════════════════════════════════════════════════════════════
    # ★★★ 반대 방향의 시험 — **짖으면 안 되는 것에 짖는가**
    #
    #   ★ 이것이 이번 결정의 심장이다: **`unresolved` 는 실패가 아니라 정직이다.**
    #     린트가 미결을 **채우라고 짖으면**, 채우는 유일한 방법은 **지어내는 것**이다.
    #     그러면 눈이 병을 만든다 — 그것이 이 프로젝트가 고치고 있는 바로 그 병이다.
    # ═══════════════════════════════════════════════════════════════════════
    probe_quiet("★★★ **`unresolved` 는 위반이 아니다** — 린트가 채우라고 짖지 않는가",
                None, "unresolved",
                "✓ 조용했다 — **모른다고 적을 수 있는 칸**이 생겼다 (그러나 세어서 소리내어 말한다)")
    probe_quiet("★★ 예외의 **당사자**(nokrim_chongchae)에게는 짖지 않는가 — 예외는 예외로 살아야 한다",
                None, "[예외전파] nokrim_chongchae",
                "✓ 조용했다 — 녹림 총채는 석축을 **가질 자격이 있다** (only 목록 안에 있다)")
    probe_quiet("★★ 섬이 아닌 곳에 **나루를 요구하지 않는가** (hwasan 은 산이다)",
                None, "[나루없음] hwasan",
                "✓ 조용했다 — 화산에 배가 닿을 필요는 없다")
    # ★★★ **값이 같은 것은 죄가 아니다.** 무한(trade_city) 64 = 남궁(영향력 10) 64 —
    #   두 표가 **우연히 같은 수**를 갖는 것은 정상이다. 죄는 두 표가 **한 몸이 되는 것**이다 (규칙 5).
    #   ★ 이 시험이 없으면 다음 사람이 "숫자가 겹치니 표를 합치자"고 말한다 — 그리고 그것이 정확히 그 병이다.
    probe_quiet("★★★ 두 표의 **숫자가 겹쳐도** 짖지 않는가 (무한 64 · 남궁 64 — 같은 수, 다른 계약)",
                None, "[사다리공유]",
                "✓ 조용했다 — **같은 반경이 같은 장소를 뜻하지 않는다.** "
                "무당 본산과 대교역 중심지가 모두 80일 수 있다 (내부의 기하와 밀도가 다를 뿐이다)")
    probe_quiet("★★ 상업 거점의 **정당한 반경**에는 짖지 않는가 (muhan = trade_city → 64)",
                None, "[상업무림혼용]",
                "✓ 조용했다 — 상업 표에서 읽은 수는 옳다")
    probe_quiet("★★★ 한정·유배지의 **원형이 못 박은 반경**에 짖지 않는가 (천막 48 · 유배지 24)",
                None, "[2계층위반] bukmak_hanjeong",
                "✓ 조용했다 — **게르 몇 채와 말 떼가 곧 한정의 크기다** (원형의 기하가 정했다)")
    # ★★ **주석에 병을 숨길 수 없다는 것**도 시험한다 — 반대 방향의 함정이다.
    #    (금칙어를 주석으로 감싸면 눈이 조용해진다. 그것이 옳다 — 주석은 돌지 않는다.
    #     그러나 **그 사실을 시험이 못 박아 둬야** 누가 나중에 "주석도 봐야 하는 것 아니냐"고 묻지 않는다)
    global READ_JAVA
    _orig = READ_JAVA
    # ★ 병을 **주석 안에** 심는다 — 파일을 통째로 읽어 금칙어를 붙이고, 그 다음 주석을 벗긴다
    READ_JAVA = lambda p: strip_java(p.read_text(encoding="utf-8")   # noqa: E731
                                     + ("\n// SECTS = Set.of(\"hwasan\");\n"
                                        if p.name == "RemoteBuilder.java" else ""))
    hidden = Lint(wm, fac, ter, rfs)
    hidden.run()
    READ_JAVA = _orig
    quiet = not any("[배선위반]" in x for x in hidden.bad)
    cases.append(("주석 속의 금칙어는 **짖지 않는다** (코드가 아니다 — 돌지 않으므로)",
                  quiet, "✓ 조용했다 — 병의 기록은 병이 아니다" if quiet else "✗ 주석에 짖었다"))

    print("══ 눈을 시험한다 — 일부러 병을 넣고 짖는지 본다 ══")
    ok = True
    for name, caught, evidence in cases:
        print(("✓ " if caught else "✗ ") + f"{name}\n    → {evidence}")
        ok &= caught
    print("── " + (f"✓ 눈이 {len(cases)}번 다 짖었다" if ok else "✗ ★ 눈이 놓쳤다 — 눈을 고쳐라"))
    return 0 if ok else 1


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    lt = build()
    lt.run()
    sys.exit(report(lt))
