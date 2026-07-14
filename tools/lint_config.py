#!/usr/bin/env python3
"""config 정합성 린트 — 파일 간 교차 참조를 전수 검사한다.

검사 항목: 스킬 액션 데이터↔카탈로그, 지역↔NPC, 맵 스펙↔지역, 소문망 세력↔세력 정의,
기연 장소, 심법 겸수/상성 id, 경지 표 정합, 오의 문파.
사용법: python3 tools/lint_config.py  (문제 없으면 exit 0)
"""

import os
import sys
import yaml

ROOT = os.path.join(os.path.dirname(__file__), "..")


def load(name):
    with open(os.path.join(ROOT, "config", name), encoding="utf-8") as f:
        return yaml.safe_load(f)


issues = []
warns = []


def check(ok, msg):
    (issues if not ok else []).append(msg) if not ok else None


skills = load("skills.yml")
mech = load("skill_mechanics.yml")
simbeop = load("simbeop.yml")
regions = load("regions/cheongha_hyeon.yml")
npcs = load("npcs/cheongha_npcs.yml")
map_spec = load("map_spec/cheongha_hyeon_map.yml")["map_spec"]
rumor = load("rumor.yml")
factions = load("factions.yml")
fortune = load("fortune_encounters.yml")
cultivation = load("cultivation.yml")
creation = load("player_creation.yml")
energy = load("internal_energy.yml")
ultimate = load("ultimate_arts.yml")

# 1. 스킬 액션 데이터 ↔ 무공 카탈로그 (npc_only는 면제)
catalog_ids = set(skills["martial_arts"].keys()) | set(simbeop["simbeop"].keys())
for sid, s in mech["skills"].items():
    if isinstance(s, dict) and s.get("npc_only"):
        continue
    if sid not in catalog_ids:
        issues.append(f"skill_mechanics '{sid}' 가 무공 카탈로그(skills/simbeop)에 없음")

# 2. 지역 ↔ NPC
npc_ids = set(npcs["npcs"].keys())
for loc_id, loc in regions["locations"].items():
    for nid in loc.get("linked_npcs", []):
        if nid not in npc_ids:
            issues.append(f"regions '{loc_id}' 가 미정의 NPC '{nid}' 참조")
for inc_id, inc in regions["incidents"].items():
    for nid in inc.get("related_npcs", []):
        if nid not in npc_ids:
            issues.append(f"incident '{inc_id}' 가 미정의 NPC '{nid}' 참조")

# 3. 맵 스펙 ↔ 지역 장소
region_locs = set(regions["locations"].keys())
for loc_id in map_spec["locations"]:
    if loc_id not in region_locs:
        issues.append(f"map_spec 장소 '{loc_id}' 가 지역 정의에 없음")
for d_id, d in map_spec["districts"].items():
    for loc_id in d.get("contains", []):
        if loc_id not in region_locs:
            issues.append(f"map_spec 구역 '{d_id}' 가 미정의 장소 '{loc_id}' 포함")

# 4. 소문망 세력 접속 ↔ 세력 정의 (그룹 또는 멤버 id 허용)
faction_ids = set(factions["faction_groups"].keys())
for gid, grp in factions["faction_groups"].items():
    faction_ids |= set(grp.get("members", {}).keys())
for fid in rumor["faction_awareness"]["network_access"]:
    if fid not in faction_ids:
        issues.append(f"rumor 세력 접속 '{fid}' 가 factions.yml에 없음")

# 5. 기연 장소
for eid, e in fortune["encounters"].items():
    if e["location"] not in region_locs:
        issues.append(f"기연 '{eid}' 장소 '{e['location']}' 미정의")

# 5-b. 기연 관문 — ★ 산문(trigger)과 기계 판독(gate)이 같은 수인가.
#      한 파일 안에 정본이 둘이면, 배선된 쪽(gate)이 이기고 산문은 조용히 거짓말이 된다.
#      (이 저장소는 바로 그 병으로 취걸개를 3회/2건에 묶어 뒀다 — yml 은 30회/15건이라 적은 채로.)
import re as _re                                                       # noqa: E402

sim_all = set(simbeop["simbeop"].keys())
for eid, e in fortune["encounters"].items():
    gate = e.get("gate")
    if not gate:
        continue                     # 무대가 없는 기연 — 관문을 달지 않는다 (미배선 표식)
    prose = " ".join(str(x) for x in e.get("trigger", []))
    m = _re.search(r"방문\s*(\d+)\s*회", prose)
    if m and int(m.group(1)) != gate["visits"]:
        issues.append(f"기연 '{eid}' 관문 불일치: gate.visits={gate['visits']} ↔ trigger 산문 '{m.group(1)}회'")
    m = _re.search(r"의뢰_?완수\s*(\d+)", prose)
    if m and int(m.group(1)) != gate["deeds"]:
        issues.append(f"기연 '{eid}' 관문 불일치: gate.deeds={gate['deeds']} ↔ trigger 산문 '{m.group(1)}건'")
    if gate.get("max_realm") and f"{gate['max_realm']} 이하" not in prose:
        issues.append(f"기연 '{eid}' 관문 불일치: gate.max_realm='{gate['max_realm']}' 가 trigger 산문에 없다")
    grants = e.get("grants", {})
    if grants.get("simbeop") and grants["simbeop"] not in sim_all:
        issues.append(f"기연 '{eid}' grants.simbeop '{grants['simbeop']}' 가 simbeop.yml 등록부에 없다")

# 5-c. 사냥터 개체군 — 등록되지 않은 개체는 심지 않는다. 영물은 양산 금지(정원 0)
hunting = load("hunting_grounds.yml")
for gid, ground in (hunting.get("grounds") or {}).items():
    for nid, quota in (ground.get("population") or {}).items():
        if nid not in npcs["npcs"]:
            issues.append(f"사냥터 '{gid}' 개체 '{nid}' 가 npcs 등록부에 없다")
            continue
        rank = npcs["npcs"][nid].get("beast_rank")
        if rank == "영물" and (quota.get("day", 0) or quota.get("night", 0)):
            issues.append(f"사냥터 '{gid}' 영물 '{nid}' 정원 ≠ 0 — 양산 금지 (cultivation beast_ranks)")

# 6. 심법 겸수/상성 id
sim_ids = set(simbeop["simbeop"].keys())
for sid, s in simbeop["simbeop"].items():
    for other in s.get("dual_approved", []):
        if other not in sim_ids:
            issues.append(f"심법 '{sid}' dual_approved '{other}' 미정의")
for pair in simbeop["dual_cultivation"]["opposed"].get("examples", []):
    for sid in pair:
        if sid not in sim_ids:
            issues.append(f"상극 예시 심법 '{sid}' 미정의")

# 7. 경지 표 정합 — cultivation 9단계 = 능력치 상한표 = 기 운용 게이트
stages = [s["name"] for s in cultivation["cultivation_stages"]]
cap_realms = list(creation["attribute_cap_by_realm"].keys())
gate_realms = list(energy["realm_gates"].keys())
if stages != cap_realms:
    issues.append(f"경지 순서 불일치: cultivation {stages} vs 상한표 {cap_realms}")
missing_gates = [s for s in stages if s != "범인" and s not in gate_realms]
if missing_gates:
    issues.append(f"기 운용 게이트 누락 경지: {missing_gates}")

# 8. 오의 문파 실재 — factions.yml id_policy에 따라 group/member id만 참조값이다.
known_faction_ids = set(faction_ids)
known_faction_ids.update(factions.get("id_policy", {}).get("exempt", []))
for aid, art in ultimate["legacy_arts"].items():
    if art["faction"] not in known_faction_ids:
        issues.append(f"오의 '{aid}' 문파 '{art['faction']}' 가 세력 정의에 없음")

# 9. 지역 초기값 ↔ 시뮬레이터/테스트 가정 (치안 50/경제 48/민심 55)
state = regions["region"]["state"]
expected = {"security": 50, "economy": 48, "public_mood": 55}
if state != expected:
    warns.append(f"청하현 초기값 변경됨 {state} — 파이썬/Java 패리티 테스트 초기값과 대조 필요")

print(f"검사 완료 — 오류 {len(issues)}건, 경고 {len(warns)}건")
for m in issues:
    print("  ✗", m)
for m in warns:
    print("  ⚠", m)
sys.exit(1 if issues else 0)
