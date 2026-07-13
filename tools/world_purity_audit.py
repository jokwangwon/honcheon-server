#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""세계 순도(純度) 검산 — 바닐라의 흔적이 남아 있는가.

  등록부 : config/world_purity.yml   ← **정본**
  산출물 : worldgen/honcheon_purity/ ← 등록부에서 파생된 데이터팩
  문서   : docs/design/world_purity.md

세 가지 모드:

    python3 tools/world_purity_audit.py --build
        바닐라 정본(mojang jar + 데이터 생성기)에서 데이터팩을 **빚는다**.
        스키마를 추측하지 않는다 — 정본을 읽어 필드 하나만 고친다.

    python3 tools/world_purity_audit.py            (기본)
        등록부 ↔ 데이터팩을 대조한다. 끄기로 한 것이 실제로 꺼져 있는가.
        java 없이 돈다(바이옴 심층 대조는 캐시가 있을 때만).

    python3 tools/world_purity_audit.py --emit-rcon
        인게임 실측 명령을 뱉는다. 서버에 RCON 으로 붓고 응답을 보면 된다.

────────────────────────────────────────────────────────────────────────────
안전성 — 레지스트리 실패 = 서버 사망. 이 도구가 지키는 불변식:

  구조물 : structure_set 의 placement 에서 **frequency 한 필드만** 0 으로 만든다.
           (strongholds 는 concentric_rings 라 count 도 0 — 고리 좌표 자체를 안 만든다)
  피처   : placed_feature 의 placement 배열에 **count: 0** 을 놓는다.
  바이옴 : 기후 상자(parameters)는 **한 글자도 건드리지 않는다.** 붙은 이름표(biome)만 간다.
           → 상자 수 불변 · 기후 공간의 분할 불변 → 구멍도 겹침도 원리적으로 불가능.
             audit 이 이 등식을 매번 다시 증명한다(--build 시 쓴 정본과 바이트 비교).
────────────────────────────────────────────────────────────────────────────
"""
from __future__ import annotations

import argparse
import io
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.exit("pyyaml 이 필요하다: pip install pyyaml")

ROOT = Path(__file__).resolve().parent.parent
REGISTRY = ROOT / "config" / "world_purity.yml"
CACHE = ROOT / "run" / "mvt" / "cache" / "datagen"          # run/ 은 gitignore — 캐시 자리
JDK = ROOT / "run" / "jdk-21" / "bin" / "java"

# 바닐라 기후 상자에서 「해안 기둥」의 대륙성 구간 (OverworldBiomeBuilder 의 COAST 상수)
# 이 값은 우리가 정한 것이 아니라 바닐라가 뱉은 데이터에서 **읽은** 것이다.
BIOME_SOURCE_KEY = "biomes"

OK, BAD, WARN = "✔", "✘", "⚠"


# ══════════════════════════════════════════════════════════════════════════
# 등록부 · 바닐라 정본
# ══════════════════════════════════════════════════════════════════════════
def registry() -> dict:
    with REGISTRY.open(encoding="utf-8") as f:
        return yaml.safe_load(f)["world_purity"]


def pack_dir(reg: dict) -> Path:
    return ROOT / reg["datapack"]


def _nested_server_jar(bundler: Path) -> zipfile.ZipFile:
    """mojang jar 은 번들러다 — 안에 든 진짜 server jar 를 연다."""
    outer = zipfile.ZipFile(bundler)
    inner = [n for n in outer.namelist()
             if n.startswith("META-INF/versions/") and n.endswith(".jar")]
    if not inner:
        raise SystemExit(f"{BAD} {bundler} 안에서 server jar 를 못 찾았다")
    return zipfile.ZipFile(io.BytesIO(outer.read(inner[0])))


def vanilla_json(jar: zipfile.ZipFile, path: str) -> dict:
    """바닐라 정본 한 조각. 없으면 죽는다 — 추측하지 않는다."""
    try:
        return json.loads(jar.read(f"data/minecraft/{path}"))
    except KeyError:
        raise SystemExit(f"{BAD} 바닐라 정본에 없다: {path} — 버전이 바뀌었는가?")


def vanilla_biome_names(jar: zipfile.ZipFile) -> set[str]:
    """바닐라 바이옴 **파일 이름** (namespace 없음) — 65종."""
    return {
        n.split("/")[-1][:-5]
        for n in jar.namelist()
        if n.startswith("data/minecraft/worldgen/biome/") and n.endswith(".json")
    }


def vanilla_biome_ids(jar: zipfile.ZipFile) -> set[str]:
    return {"minecraft:" + n for n in vanilla_biome_names(jar)}


def vanilla_entity_ids() -> set[str] | None:
    """바닐라 엔티티 id 전부 — 데이터 생성기의 registries.json 이 정본.

    이게 있어야 `wild_spawn.allow` 의 **오타를 잡는다.** 자바 쪽 판독기는 모르는 이름을
    조용히 건너뛰고(`IllegalArgumentException ignored`), 데이터팩 쪽은 모르는 이름이
    허용 목록에 있어도 그냥 아무것도 통과시키지 않는다 — 둘 다 **말없이** 실패한다.
    오타 하나로 이리가 세계에서 사라져도 아무도 모른다. 그래서 여기서 잡는다.
    """
    reports = CACHE / "out" / "reports" / "registries.json"
    if not reports.is_file():
        return None
    data = json.loads(reports.read_text())
    return set(data["minecraft:entity_type"]["entries"].keys())


def allow_ids(reg: dict) -> set[str]:
    """등록부의 야생 스폰 허용 목록 → `minecraft:wolf` 꼴. **데이터팩과 자바가 같은 줄을 읽는다.**"""
    return {"minecraft:" + str(n).lower() for n in reg["wild_spawn"]["allow"]}


def vanilla_climate(reg: dict, *, allow_datagen: bool = True) -> list[dict] | None:
    """오버월드의 기후 상자 7,593 개 — **바닐라 데이터 생성기**가 뱉은 정본.

    multi_noise_biome_source_parameter_list 레지스트리는 preset **이름만** 담는다(37 바이트).
    덮어도 소용없다. 실제 구획은 Java 안(OverworldBiomeBuilder)에 있고,
    그것을 밖으로 꺼내는 유일한 정공법이 `--reports` 다.
    """
    cached = CACHE / "biome_parameters_overworld.json"
    if cached.is_file():
        return json.loads(cached.read_text())["biomes"]
    if not allow_datagen:
        return None

    jar = ROOT / reg["vanilla_jar"]
    if not jar.is_file():
        raise SystemExit(f"{BAD} 바닐라 jar 이 없다: {jar}")
    if not JDK.is_file():
        raise SystemExit(f"{BAD} JDK 21 이 없다: {JDK}")

    print(f"   … 바닐라 데이터 생성기를 돌린다 (한 번만; {cached} 에 캐시)")
    CACHE.mkdir(parents=True, exist_ok=True)
    out = CACHE / "out"
    subprocess.run(
        [str(JDK), "-DbundlerMainClass=net.minecraft.data.Main", "-jar", str(jar),
         "--server", "--reports", "--output", str(out)],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    src = out / "reports" / "biome_parameters" / "minecraft" / "overworld.json"
    if not src.is_file():
        raise SystemExit(f"{BAD} 데이터 생성기가 biome_parameters 를 안 뱉었다: {src}")
    cached.write_bytes(src.read_bytes())
    return json.loads(cached.read_text())["biomes"]


# ══════════════════════════════════════════════════════════════════════════
# 바이옴 이름표 갈아끼우기 — parameters 는 건드리지 않는다
# ══════════════════════════════════════════════════════════════════════════
def remap_biome(box: dict, rules: list[dict]) -> str:
    """기후 상자 하나에 붙일 **이름표**를 고른다. 위에서부터 첫 일치."""
    name = box["biome"]
    for rule in rules:
        if rule["from"] != name:
            continue
        when = rule.get("when")
        if when and any(box["parameters"].get(axis) != bound for axis, bound in when.items()):
            continue
        return rule["to"]
    return name


def build_climate(reg: dict, vanilla: list[dict]) -> list[dict]:
    rules = reg["biomes"]["remap"]
    return [{"biome": remap_biome(b, rules), "parameters": b["parameters"]} for b in vanilla]


# ══════════════════════════════════════════════════════════════════════════
# --build : 등록부 → 데이터팩
# ══════════════════════════════════════════════════════════════════════════
def write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def build(reg: dict) -> int:
    jar = _nested_server_jar(ROOT / reg["vanilla_jar"])
    pack = pack_dir(reg)
    data = pack / "data" / "minecraft" / "worldgen"

    write_json(pack / "pack.mcmeta", {
        "pack": {
            "description": "혼천 — 세계 순도 (바닐라 구조물·사막·빙설 없음)",
            "pack_format": reg["pack_format"],
            "supported_formats": [reg["pack_format"], 99],
            "min_format": reg["pack_format"],
            "max_format": 99,
        }
    })

    # ── ① 구조물 : placement.frequency = 0 (정본에서 한 필드만) ─────────────
    for entry in reg["structures"]["disable"]:
        name = entry["id"].split(":", 1)[1]
        doc = vanilla_json(jar, f"worldgen/structure_set/{name}.json")
        # 한 필드만 — frequency. 다른 것은 절대 건드리지 않는다.
        #
        #   ⚠ 여기서 「strongholds 는 concentric_rings 니까 count: 0 도 주자」는 유혹에 넘어가지 마라.
        #     count 의 코덱은 Codec.intRange(1, 4095) 다 — **0 은 레지스트리 로딩을 깨뜨린다.**
        #     (--load-test 가 실제로 이걸 잡았다: "Value 0 outside of range [1:4095]")
        #     frequency 0 이면 concentric_rings 도 막힌다: StructurePlacement.isStructureChunk() 의
        #     확률 감쇠는 배치 종류와 무관한 **상위 클래스**의 관문이고, nextFloat() < 0.0 은 항상 거짓이다.
        doc["placement"]["frequency"] = 0.0
        write_json(data / "structure_set" / f"{name}.json", doc)
    print(f"{OK} structure_set {len(reg['structures']['disable'])} 종")

    # ── ② 피처 : placement 에 count 0 ──────────────────────────────────────
    for entry in reg["features"]["disable"]:
        name = entry["id"].split(":", 1)[1]
        doc = vanilla_json(jar, f"worldgen/placed_feature/{name}.json")
        mods = doc["placement"]
        existing = next((m for m in mods if m.get("type") == "minecraft:count"), None)
        if existing:
            existing["count"] = 0              # 있던 count 를 0 으로 (최소 편집)
        else:
            mods.insert(0, {"type": "minecraft:count", "count": 0})
        write_json(data / "placed_feature" / f"{name}.json", doc)
    print(f"{OK} placed_feature {len(reg['features']['disable'])} 종")

    # ── ③ 바이옴 : world_preset 의 biome_source 를 명시 목록으로 ────────────
    vanilla = vanilla_climate(reg)
    preset = vanilla_json(jar, "worldgen/world_preset/normal.json")
    overworld = preset["dimensions"]["minecraft:overworld"]["generator"]["biome_source"]
    if overworld.get("preset") != "minecraft:overworld":
        raise SystemExit(f"{BAD} world_preset/normal.json 의 모양이 예상과 다르다: {overworld}")
    boxes = build_climate(reg, vanilla)
    preset["dimensions"]["minecraft:overworld"]["generator"]["biome_source"] = {
        "type": "minecraft:multi_noise",
        BIOME_SOURCE_KEY: "@@BOXES@@",
    }
    # 기후 상자는 **한 줄에 하나** — 7,593줄. 예쁜 들여쓰기(5MB)와 한 줄 압축(diff 불가) 사이.
    # 이름표 하나가 바뀌면 diff 도 한 줄만 바뀐다.
    body = ",\n".join(
        " " * 12 + json.dumps(b, ensure_ascii=False, separators=(",", ":")) for b in boxes
    )
    text = json.dumps(preset, indent=2, ensure_ascii=False)
    text = text.replace('"@@BOXES@@"', "[\n" + body + "\n          ]")
    out = data / "world_preset" / "normal.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text + "\n", encoding="utf-8")
    json.loads(text)   # 우리가 손으로 이어붙인 텍스트다 — 파싱되는지 즉시 확인한다

    changed = sum(1 for a, b in zip(vanilla, boxes) if a["biome"] != b["biome"])
    print(f"{OK} world_preset/normal.json — 기후 상자 {len(boxes)}개 중 {changed}개 이름표 교체")

    # ── ④ 야생 스폰 : 바이옴의 spawners 를 **허용 목록으로 거른다** ────────────
    #
    #   지금까지 자연 스폰은 **리스너 한 겹**으로만 막혔다 (HuntingGrounds.onSpawn 이 취소).
    #   그것은 옳게 작동하지만 — 마인크래프트는 여전히 매 틱 **뿌리려고 시도한다.**
    #   개체를 고르고, 자리를 찾고, 청크를 훑고, 이벤트를 쏘고, 우리가 취소한다.
    #   막는 것과 **애초에 나지 않는 것**은 다르다. 여기서 원천을 끈다.
    #
    #   방법 — 구조물·피처와 같은 문법: 바닐라 정본에서 **한 필드만.**
    #     biome json 의 `spawners` (MobCategory → 후보 목록) 에서 허용 목록에 없는 줄을 **뺀다.**
    #     `spawn_costs` 도 같이 (남은 것을 가리키는 비용만 남긴다).
    #     parameters·features·carvers·effects 는 **한 글자도 건드리지 않는다.**
    #     빈 목록은 코덱이 받는다 (simpleMap + LIST_CODEC — 빈 리스트가 정상값이다).
    #     → 그 바이옴은 그 범주의 몹을 **고를 후보가 없다.** 확률이 아니라 불가능이다.
    #
    #   ★ 그런데 **끄기만 하면 세계가 텅 빈다.** 그래서 이것은 「전부 지우기」가 아니라 「거르기」다:
    #     허용 목록(WOLF·FOX·RABBIT·BAT·물고기)의 줄은 **그대로 남는다.** 산야는 살아 있다.
    #     산늑대·멧돼지·호랑이·반달곰·산적은 HuntingGrounds 가 정원제로 **심는다** (CUSTOM).
    #     아래 「남은 것」 표가 세계가 비지 않았음을 매 빌드마다 다시 증명한다.
    allow = allow_ids(reg)
    biome_dir = data / "biome"
    if biome_dir.is_dir():
        for stale in biome_dir.glob("*.json"):
            stale.unlink()   # 등록부에서 빠진 것이 파일로 남으면 등록제가 아니다

    survivors: dict[str, int] = {}
    written = 0
    for name in sorted(vanilla_biome_names(jar)):
        doc = vanilla_json(jar, f"worldgen/biome/{name}.json")
        touched = False
        for category, entries in doc.get("spawners", {}).items():
            keep = [e for e in entries if e["type"] in allow]
            if len(keep) != len(entries):
                touched = True
            doc["spawners"][category] = keep
            for e in keep:
                survivors[e["type"]] = survivors.get(e["type"], 0) + 1
        costs = doc.get("spawn_costs", {})
        for gone in [t for t in costs if t not in allow]:
            del costs[gone]
            touched = True
        if touched:
            write_json(biome_dir / f"{name}.json", doc)
            written += 1

    print(f"{OK} biome {written}/{len(vanilla_biome_names(jar))} 종 — spawners 를 허용 {len(allow)}종으로 거름")
    if survivors:
        alive = ", ".join(f"{t.split(':')[1]}×{n}" for t, n in sorted(survivors.items()))
        print(f"{OK} 남은 것 (세계는 비지 않았다) — {alive}")
    dead = sorted(allow - set(survivors))
    for d in dead:
        print(f"{WARN} 허용했으나 어느 바이옴도 뿌리지 않는다: {d} "
              f"(바닐라가 애초에 안 뿌리거나 — **오타다**)")

    print(f"\n{OK} 데이터팩: {pack.relative_to(ROOT)}")
    print("   → 새 월드의 datapacks/ 에 넣어라. **기존 월드의 청크는 안 바뀐다.**")
    return 0


# ══════════════════════════════════════════════════════════════════════════
# 눈 ④ — 야생 스폰. **필드에 등록 안 된 것이 나는가.**
#
#   이 검사는 예전에 **거짓말이었다.** 하는 일이 이것뿐이었다:
#       if "world_purity.yml" not in HuntingGrounds.java: fail()
#   즉 자바 파일 안에 그 **문자열이 있는지**만 봤다. 허용 목록을 통째로 비워도,
#   이름을 전부 오타내도, 데이터팩이 스폰을 하나도 안 껐어도 — **「위반 0건」이었다.**
#   이름만 보고 속을 안 본 눈이다. 아래는 속을 본다.
# ══════════════════════════════════════════════════════════════════════════
HG = ROOT / "server-mvt" / "src" / "main" / "java" / "com" / "honcheon" / "mvt" / "HuntingGrounds.java"

# biome json 에서 우리가 손대도 되는 유일한 두 필드. 나머지는 바닐라 정본과 **바이트가 같아야** 한다.
SPAWN_FIELDS = {"spawners", "spawn_costs"}


def _java_block(src: str, anchor: str, opener: str = "(") -> str:
    """자바 소스에서 `anchor` 뒤의 괄호 블록 한 덩이 (중첩 괄호까지 센다)."""
    i = src.index(anchor) + len(anchor)
    i = src.index(opener, i)
    depth, j = 0, i
    close = {"(": ")", "{": "}"}[opener]
    while j < len(src):
        if src[j] == opener:
            depth += 1
        elif src[j] == close:
            depth -= 1
            if depth == 0:
                return src[i + 1:j]
        j += 1
    raise SystemExit(f"{BAD} 자바 블록을 못 닫았다: {anchor}")


def audit_spawn(reg: dict, data: Path, jar, fail, warn) -> None:
    allow = allow_ids(reg)
    if jar is None:
        warn.append("바닐라 jar 이 없어 야생 스폰의 **데이터팩 층**을 검사하지 못했다 "
                    "(리스너 층만 봤다 — 원천이 켜져 있어도 모른다)")

    # ④-a 허용 목록의 이름이 **실재하는가.** 오타는 양쪽에서 조용히 죽는다:
    #     자바는 모르는 이름을 건너뛰고(catch IllegalArgumentException ignored),
    #     데이터팩은 그냥 아무것도 통과시키지 않는다. WOFL 이라 적으면 이리가 사라지는데 아무도 모른다.
    known = vanilla_entity_ids()
    if known is None:
        warn.append("registries.json 캐시가 없어 허용 목록의 **오타 검사**를 못 했다 (`--build` 한 번)")
    else:
        for typo in sorted(allow - known):
            fail(f"★ 허용 목록에 **존재하지 않는 엔티티**: {typo} — 자바도 데이터팩도 조용히 무시한다")

    # ④-b 자바가 등록부를 읽는가 · ④-c 폴백이 등록부와 표류하는가
    if not HG.is_file():
        warn.append("HuntingGrounds.java 를 못 찾았다 — 리스너 층을 검사하지 못했다")
        return
    src = HG.read_text(encoding="utf-8")
    if "world_purity.yml" not in src or "loadWildAllow" not in src:
        fail("HuntingGrounds 가 world_purity.yml 을 읽지 않는다 — 야생 스폰 등록부가 배선되지 않았다")
    fallback = {"minecraft:" + m.lower()
                for m in re.findall(r"EntityType\.(\w+)", _java_block(src, "wildAllow = EnumSet.of"))}
    #   폴백은 **등록부의 사본**이지 다른 정본이 아니다 (loadGrounds 의 규약 그대로).
    #   표류하면 config 가 유실된 서버는 **다른 세계**가 된다. 그런 폴백은 안전망이 아니라 두 번째 진실이다.
    for drift in sorted(fallback ^ allow):
        side = "코드 폴백에만" if drift in fallback else "등록부에만"
        fail(f"★ 야생 허용 목록 표류 — {drift} 가 {side} 있다. 폴백은 등록부의 사본이어야 한다")

    # ④-d/e/f 데이터팩 — **원천이 꺼졌는가.** 리스너는 「막는」 층이고 여기는 「나지 않는」 층이다.
    if jar is None:
        return
    biome_dir = data / "biome"
    if not biome_dir.is_dir():
        fail("★ 데이터팩에 biome override 가 하나도 없다 — 바닐라가 **여전히 뿌린다**. "
             "리스너가 취소해 주지만 원천은 켜져 있다 (`--build`)")
        return
    on_disk = {p.stem for p in biome_dir.glob("*.json")}
    survivors: dict[str, int] = {}
    leaks = 0
    for name in sorted(vanilla_biome_names(jar)):
        van = vanilla_json(jar, f"worldgen/biome/{name}.json")
        dirty = any(e["type"] not in allow
                    for lst in van.get("spawners", {}).values() for e in lst) \
            or any(t not in allow for t in van.get("spawn_costs", {}))
        if name not in on_disk:
            if dirty:
                fail(f"★ 바닐라가 금지 몹을 뿌리는데 override 가 없다 — 이 바이옴에선 **난다**: biome/{name}")
            continue
        doc = json.loads((biome_dir / f"{name}.json").read_text())
        for category, entries in doc.get("spawners", {}).items():
            for e in entries:
                if e["type"] not in allow:
                    leaks += 1
                    fail(f"★ 허용 목록 밖인데 override 에 남아 있다 — **샌다**: "
                         f"biome/{name} · {category} · {e['type']}")
                else:
                    survivors[e["type"]] = survivors.get(e["type"], 0) + 1
        for t in doc.get("spawn_costs", {}):
            if t not in allow:
                fail(f"★ spawn_costs 에 금지 몹이 남아 있다: biome/{name} · {t}")
        # 최소 편집 불변식 — spawners/spawn_costs 말고는 정본과 **같아야** 한다.
        #   (구조물은 frequency 한 필드, 바이옴 이름표는 라벨만 — 같은 규약. 이걸 안 재면
        #    「스폰 끄기」가 몰래 features 나 carvers 를 갈아엎어도 통과한다)
        for field in set(van) | set(doc):
            if field in SPAWN_FIELDS:
                continue
            if van.get(field) != doc.get(field):
                fail(f"★ 최소 편집 위반 — biome/{name} 의 `{field}` 가 바닐라 정본과 다르다. "
                     f"스폰만 건드리기로 했다")

    for extra in sorted(on_disk - vanilla_biome_names(jar)):
        fail(f"바닐라에 없는 바이옴 override (무허가): biome/{extra}")

    if not leaks:
        print(f"{OK} 야생 스폰 — 바이옴 {len(on_disk)}종의 spawners 를 허용 {len(allow)}종으로 거름. "
              f"금지 몹은 **후보 목록에 없다**(확률이 아니라 불가능)")

    # ④-g ★ 양성 대조 — **끄기만 하고 켜는 걸 잊으면 세계가 텅 빈다.** 이게 가장 흔한 실패다.
    dead = sorted(allow - set(survivors))
    if not survivors:
        fail("★★ 야생에 **아무것도 남지 않았다** — 산야가 죽었다. 거르기가 아니라 전멸이다")
    else:
        alive = ", ".join(f"{t.split(':')[1]}×{n}" for t, n in sorted(survivors.items()))
        print(f"{OK} 산야는 살아 있다 (양성 대조) — {alive}  ← 바이옴 수")
    for d in dead:
        warn.append(f"허용했으나 어느 바이옴도 뿌리지 않는다: {d} (바닐라가 원래 안 뿌리는 곳일 수 있다)")


# ══════════════════════════════════════════════════════════════════════════
# 눈 ⑤ — 사냥터. **정원이 지켜지는가 · 우리가 부른 것은 나오는가.**
#
#   ★ 끄는 눈만 있으면 「위반 0건」은 **텅 빈 세계**와 구별되지 않는다.
#     이 눈은 반대쪽을 본다: 등록부가 심기로 한 것이 **인게임에 설 수 있는가.**
# ══════════════════════════════════════════════════════════════════════════
def audit_hunt(fail, warn) -> None:
    gy = ROOT / "config" / "hunting_grounds.yml"
    ny = ROOT / "config" / "npcs" / "cheongha_npcs.yml"
    if not gy.is_file() or not ny.is_file() or not HG.is_file():
        warn.append("사냥터 등록부나 HuntingGrounds.java 가 없어 ⑤ 를 건너뛰었다")
        return
    with gy.open(encoding="utf-8") as f:
        gr = yaml.safe_load(f)
    with ny.open(encoding="utf-8") as f:
        npcs = yaml.safe_load(f)["npcs"]
    src = HG.read_text(encoding="utf-8")
    known = vanilla_entity_ids()

    # 코드 폴백의 몸 매핑 (npcs 에 mc_entity 가 서면 이쪽이 죽는다 — 지금은 둘 다 본다)
    body = {k: "minecraft:" + v.lower() for k, v in
            re.findall(r'"(\w+)",\s*EntityType\.(\w+)', _java_block(src, "DEFAULT_ENTITY = Map.of"))}

    # ⑤-a ★ **가장 흔한 실패** — 우리가 심는 몸이 우리 손에 취소당하는가.
    #   산적·갈호의 몸은 ZOMBIE 다 (`mob_models.yml` 이 좀비를 산적의 몸으로 징발했다).
    #   그런데 onSpawn 은 `entity instanceof Enemy` 를 **전역 취소**한다 — 좀비는 Enemy 다.
    #   둘이 공존하는 유일한 이유는 **순서**다: ALLOWED_REASONS(CUSTOM) 관문이 Enemy 취소보다
    #   **먼저** 있다. 이 두 줄의 순서가 뒤집히면 — 컴파일도 되고, 검사도 통과하고,
    #   좀비도 안 나오고 — **산적이 세계에서 사라진다.** 그래서 순서를 잰다.
    on_spawn = _java_block(src, "public void onSpawn(CreatureSpawnEvent event)", "{")
    gate = on_spawn.find("ALLOWED_REASONS.contains")
    enemy = on_spawn.find("instanceof Enemy")
    if gate < 0:
        fail("★ onSpawn 에 ALLOWED_REASONS 관문이 없다 — **우리가 부른 것까지 취소된다**")
    elif 0 <= enemy < gate:
        fail("★★ onSpawn 이 Enemy 취소를 CUSTOM 관문보다 **먼저** 한다 — "
             "우리가 심은 산적(ZOMBIE)이 스폰 즉시 취소된다. 산길이 텅 빈다")
    reasons = _java_block(src, "ALLOWED_REASONS = EnumSet.of")
    if "SpawnReason.CUSTOM" not in reasons:
        fail("★★ ALLOWED_REASONS 에 CUSTOM 이 없다 — HuntingGrounds.spawn() 의 짐승·산적이 "
             "**전부 취소된다.** 세계가 텅 빈다")

    spawn = gr.get("spawn", {})
    cap = spawn.get("zone_entity_cap", 0)
    town = gr.get("town", {}).get("zone")
    total_planted = 0

    # ★ 치안이 정원을 **올린다**. 그러니 상한과 겨룰 것은 등록 정원이 아니라 **최악의 정원**이다.
    #   (이 눈은 처음에 등록 정원만 쟀다 — 그래서 내가 방금 넣은 진짜 버그를 놓쳤다:
    #    치안_붕괴 × 밤이면 북쪽 산길이 19마리가 되는데 상한이 18이라 repopulate 가 상한에서
    #    돌아서 버린다. 등록된 정원이 **영원히 안 채워진다** — 세계가 가장 위험해야 할 바로 그때.
    #    「위반 0건」이었다. 눈이 재는 자리가 틀리면 눈은 조용히 거짓말한다.)
    sec_cfg = gr.get("security") or {}
    sec_role = sec_cfg.get("applies_to", "졸개")
    worst = {"day": 0, "night": 0}
    for band in (sec_cfg.get("bands") or []):
        for k in worst:
            worst[k] = max(worst[k], int(band.get(k, 0)))   # 최악 = 가장 크게 **올리는** 구간

    print(f"{OK} 사냥터 정원 — 등록부 {gy.relative_to(ROOT)}")
    for key, ground in (gr.get("grounds") or {}).items():
        zone = ground.get("zone")
        if zone == town:
            fail(f"★ 사냥터 「{key}」의 구역이 마을 구역과 같다 ({zone}) — "
                 f"마을은 무스폰이다. 여긴 **아무것도 안 난다**")
        pop = ground.get("population") or {}
        day = night = peak_day = peak_night = 0
        for foe_id, quota in pop.items():
            d, n = int(quota.get("day", 0)), int(quota.get("night", 0))
            day, night = day + d, night + n
            total_planted += d + n
            # 치안이 움직이는 것은 등록 정원 > 0 인 `applies_to` 배역뿐 (quotaFor 와 같은 규칙)
            role = str(npcs.get(foe_id, {}).get("role", ""))
            if role == sec_role or (foe_id in npcs and sec_role == "졸개"
                                    and not npcs[foe_id].get("beast_rank")
                                    and foe_id not in ("galho", "gwakjin")):
                peak_day += d + (worst["day"] if d > 0 else 0)
                peak_night += n + (worst["night"] if n > 0 else 0)
            else:
                peak_day, peak_night = peak_day + d, peak_night + n
            # ⑤-b 심기로 한 것이 등록부에 있는가
            if foe_id not in npcs:
                fail(f"★ 사냥터가 심으려는 개체가 NPC 등록부에 없다 — **영원히 안 난다**: {foe_id}")
                continue
            # ⑤-c 몸(EntityType)을 얻는가
            mc = npcs[foe_id].get("mc_entity")
            mc = f"minecraft:{str(mc).lower()}" if mc else body.get(foe_id)
            if not mc:
                fail(f"★ 개체 「{foe_id}」에 몸이 없다 (mc_entity 도 없고 코드 폴백에도 없다) — "
                     f"HuntingGrounds.parse 가 null 을 뱉는다. **안 난다**")
            elif known and mc not in known:
                fail(f"★ 개체 「{foe_id}」의 몸이 실재하지 않는 엔티티다: {mc}")
        # ⑤-d 정원 합 ≤ 구역 상한 — 안 그러면 상한이 정원을 **잘라먹는다** (등록부가 거짓말이 된다).
        #      ★ 겨루는 것은 **치안 최악의 정원**이다. 평시에 여유가 있어도 치안이 무너진 밤에
        #        상한을 넘으면, 하필 그때 도적이 안 찬다.
        for label, tot, peak in (("낮", day, peak_day), ("밤", night, peak_night)):
            mark = OK
            if cap and peak > cap:
                fail(f"★ 「{zone}」{label} — 치안 최악 정원 {peak} > zone_entity_cap {cap} "
                     f"(등록 {tot} + 치안 {peak - tot}). 상한이 먼저 걸려 "
                     f"**치안이 무너진 바로 그때 도적이 안 찬다**")
                mark = BAD
            extra = f"  (치안 최악 {peak})" if peak != tot else ""
            print(f"   {mark} {zone} · {label} 정원 합 {tot} / 구역 상한 {cap}{extra}")

    # ⑤-e ★ 양성 대조 — 정원이 전부 0이면 「위반 0건」인데 세계는 비어 있다
    if total_planted == 0:
        fail("★★ 등록된 정원이 **전부 0** 이다 — 사냥터가 텅 빈다. 끄기만 하고 켜는 걸 잊었다")

    # ⑤-f 치안의 이음매 — region_state.yml threshold_effects.치안_저하 가 약속한 것
    #
    #   ★ 이 검사는 처음에 **거짓말했다.** 이렇게 썼었다:  if "securityBands" not in src
    #     그런데 필드를 `securityBandsRenamed` 로 갈고 **읽은 값을 버리게** 만들어도
    #     그 문자열은 여전히 소스 안에 있었다 — 부분 문자열에 속은 것이다. (눈 시험이 잡았다.)
    #   그래서 이제 **이름이 아니라 배선의 세 마디**를 잰다. 하나라도 끊기면 등록부는 거짓말이 된다:
    #     ① 절을 **읽는가**  ② 읽은 구간을 **간직하는가**(버리지 않는가)  ③ 그것이 **정원에 닿는가**
    sec = gr.get("security")
    if not sec:
        warn.append("hunting_grounds.yml 에 security 절이 없다 — "
                    "region_state.yml 의 「치안<30 → 도적 자동 발생」이 인게임에 안 닿는다")
    else:
        reads = re.search(r'root\.get\("security"\)', src)
        keeps = re.search(r"\bsecurityBands\s*=\s*List\.copyOf", src)
        try:
            applies = "securityDelta(" in _java_block(src, "private static int quotaFor", "{")
        except (ValueError, SystemExit):
            applies = False
        if not reads:
            fail("★ hunting_grounds.yml 에 security 를 적어 두고 HuntingGrounds 가 **안 읽는다**")
        elif not keeps:
            fail("★ HuntingGrounds 가 security.bands 를 읽고 **버린다** (구간이 필드에 안 남는다) — "
                 "치안이 아무리 무너져도 도적은 늘지 않는다. 등록부가 거짓말이 된다")
        elif not applies:
            fail("★ 치안 구간을 읽어 두고 **정원에 안 쓴다** (quotaFor 가 securityDelta 를 안 부른다) — "
                 "region_state.yml 의 약속이 인게임에 안 닿는다")
        else:
            bands = sec.get("bands") or []
            print(f"{OK} 치안의 이음매 — {len(bands)}개 구간이 도적 정원에 배선됨 "
                  f"(region_state.yml threshold_effects → quotaFor)")


# ══════════════════════════════════════════════════════════════════════════
# 기본 : 검산
# ══════════════════════════════════════════════════════════════════════════
def audit(reg: dict) -> int:
    pack, data = pack_dir(reg), pack_dir(reg) / "data" / "minecraft" / "worldgen"
    bad: list[str] = []
    warn: list[str] = []

    def fail(msg: str) -> None:
        bad.append(msg)

    if not (pack / "pack.mcmeta").is_file():
        fail(f"데이터팩이 없다: {pack.relative_to(ROOT)} — `--build` 를 먼저 돌려라")
        print(f"{BAD} " + bad[0])
        return 1

    # ── ① 구조물 : 등록부 ↔ override 파일 ──────────────────────────────────
    listed = {e["id"].split(":", 1)[1] for e in reg["structures"]["disable"]}
    on_disk = {p.stem for p in (data / "structure_set").glob("*.json")}
    for missing in sorted(listed - on_disk):
        fail(f"등록부에 있으나 override 파일이 없다: structure_set/{missing}")
    for extra in sorted(on_disk - listed):
        fail(f"override 파일이 있으나 등록부에 없다(무허가): structure_set/{extra}")

    jar_path = ROOT / reg["vanilla_jar"]
    jar = _nested_server_jar(jar_path) if jar_path.is_file() else None
    if jar:
        vanilla_sets = {
            n.split("/")[-1][:-5]
            for n in jar.namelist()
            if n.startswith("data/minecraft/worldgen/structure_set/") and n.endswith(".json")
        }
        for forgotten in sorted(vanilla_sets - listed):
            fail(f"★ 바닐라 구조물인데 등록부에 없다 — **켜져 있다**: {forgotten}")
    else:
        warn.append(f"바닐라 jar 이 없어 「빠뜨린 구조물」 검사를 못 했다: {jar_path}")

    for f in sorted((data / "structure_set").glob("*.json")):
        doc = json.loads(f.read_text())
        pl = doc.get("placement", {})
        if pl.get("frequency") != 0.0:
            fail(f"structure_set/{f.name}: frequency 가 0 이 아니다 ({pl.get('frequency')!r}) — 이 구조물은 **선다**")
        if pl.get("count") == 0:
            fail(f"structure_set/{f.name}: count 가 0 이다 — concentric_rings 의 count 코덱은 [1:4095] 다. "
                 "**레지스트리 로딩이 깨진다**(서버 사망). frequency 로만 끈다")
        if not doc.get("structures"):
            fail(f"structure_set/{f.name}: structures 목록이 비었다 — 정본에서 최소 편집하라")
    print(f"{OK if not bad else BAD} 구조물 — 등록 {len(listed)}종 / 파일 {len(on_disk)}종")

    # ── ② 피처 ────────────────────────────────────────────────────────────
    feat_listed = {e["id"].split(":", 1)[1] for e in reg["features"]["disable"]}
    feat_disk = {p.stem for p in (data / "placed_feature").glob("*.json")}
    for missing in sorted(feat_listed - feat_disk):
        fail(f"등록부에 있으나 override 파일이 없다: placed_feature/{missing}")
    for extra in sorted(feat_disk - feat_listed):
        fail(f"override 파일이 있으나 등록부에 없다(무허가): placed_feature/{extra}")
    for f in sorted((data / "placed_feature").glob("*.json")):
        doc = json.loads(f.read_text())
        counts = [m.get("count") for m in doc.get("placement", []) if m.get("type") == "minecraft:count"]
        if 0 not in counts:
            fail(f"placed_feature/{f.name}: count 0 이 없다 — 이 피처는 **놓인다**")
    print(f"{OK if not bad else BAD} 피처 — 등록 {len(feat_listed)}종 / 파일 {len(feat_disk)}종")

    # ── 용암 — 「필드에 용암이 없다」를 지키는 관문 ──────────────────────────
    #   오버월드에서 y ≥ -54 에 용암을 놓는 피처는 이 다섯뿐이다. 하나라도 빠지면 필드가 더러워진다.
    #   (spring_lava_frozen 을 특히 조심하라 — 우리가 `grove` 를 **남겼기** 때문에 살아 있다.)
    LAVA = {"lake_lava_surface", "lake_lava_underground",
            "spring_lava", "spring_lava_frozen", "underwater_magma"}
    for leak in sorted(LAVA - feat_listed):
        fail(f"★ 용암을 놓는 피처인데 등록부에 없다 — **필드에 용암이 남는다**: {leak}")
    if not (LAVA - feat_listed):
        print(f"{OK} 용암 — 지표 용암 5종 전부 등록·차단. "
              f"y ≥ -54 에 용암을 놓을 수 있는 피처가 **없다**")
        print(f"     (y < -54 의 용암 바다는 남는다 — 아쿠아퍼 하드코딩. 데이터팩의 손이 안 닿고, 끌 이유도 없다)")

    # ── ③ 바이옴 ──────────────────────────────────────────────────────────
    preset_path = data / "world_preset" / "normal.json"
    forbidden = {r["from"] for r in reg["biomes"]["remap"]}
    if not preset_path.is_file():
        fail("world_preset/normal.json 이 없다 — 사막·빙설이 그대로 있다")
    else:
        preset = json.loads(preset_path.read_text())
        src = preset["dimensions"]["minecraft:overworld"]["generator"]["biome_source"]
        if src.get("type") != "minecraft:multi_noise" or BIOME_SOURCE_KEY not in src:
            fail("world_preset 의 오버월드 biome_source 가 명시 목록이 아니다 (preset 참조는 소용없다)")
        else:
            boxes = src[BIOME_SOURCE_KEY]
            used = {b["biome"] for b in boxes}

            for f in sorted(used & forbidden):
                fail(f"★ 금지 바이옴이 기후 구획에 **남아 있다**: {f}")

            if jar:
                unknown = used - vanilla_biome_ids(jar)
                for u in sorted(unknown):
                    fail(f"★ 존재하지 않는 바이옴 id — **레지스트리 로딩이 실패한다**: {u}")

            # 핵심 불변식 — 기후 상자를 건드리지 않았는가 (정본과 바이트 비교)
            vanilla = vanilla_climate(reg, allow_datagen=False)
            if vanilla is None:
                warn.append("바닐라 기후 정본 캐시가 없어 「구획 불변」 심층 대조를 건너뛰었다 "
                            "(`--build` 를 한 번 돌리면 캐시가 생긴다)")
            elif len(vanilla) != len(boxes):
                fail(f"★ 기후 상자 수가 다르다: 바닐라 {len(vanilla)} → 우리 {len(boxes)}. "
                     "구획이 바뀌었다 = 기후 공간에 구멍이나 겹침이 생겼을 수 있다")
            else:
                drift = [i for i, (a, b) in enumerate(zip(vanilla, boxes))
                         if a["parameters"] != b["parameters"]]
                if drift:
                    fail(f"★ 기후 상자 {len(drift)}개의 parameters 가 정본과 다르다 (첫 위치 #{drift[0]}). "
                         "우리는 이름표만 갈기로 했다 — 구획을 건드리면 안 된다")
                else:
                    relabel = sum(1 for a, b in zip(vanilla, boxes) if a["biome"] != b["biome"])
                    print(f"{OK} 바이옴 — 기후 상자 {len(boxes)}개 구획 **불변**, 이름표 {relabel}개 교체")
                    print(f"     금지 {len(forbidden)}종이 구획에서 0회 등장 → 이 세계는 그것들을 "
                          f"**생성할 수 없다**(확률이 아니라 불가능)")

    # ── ④ 야생 스폰 ───────────────────────────────────────────────────────
    audit_spawn(reg, data, jar, fail, warn)

    # ── ⑤ 사냥터 — **우리가 부른 것은 나오는가** ──────────────────────────
    audit_hunt(fail, warn)

    # ── 결과 ──────────────────────────────────────────────────────────────
    print()
    for w in warn:
        print(f"{WARN} {w}")
    if bad:
        print(f"\n{BAD} 순도 검산 실패 — {len(bad)}건")
        for b in bad:
            print(f"   {BAD} {b}")
        return 1
    print(f"{OK} 세계 순도 검산 통과. 바닐라의 흔적을 찾지 못했다.")
    print(f"   실측은 `--emit-rcon` 으로. 정적 검사는 「생성될 수 없음」까지만 말한다.")
    return 0


# ══════════════════════════════════════════════════════════════════════════
# --load-test : **레지스트리 로딩 시험** — 서버를 띄우지 않고 진짜 코덱에 먹인다
#
#   이 프로젝트의 첫째 규약이 「레지스트리 실패 = 서버 사망」이다.
#   그런데 JSON 이 옳은지 아는 확실한 방법은 **바닐라 코덱에 먹여 보는 것**뿐이다.
#   이 시험이 실제로 서버를 죽일 버그를 잡았다 (strongholds count: 0 → range [1:4095]).
#
#   Paper 의 jar 은 mojang 매핑이라 net.minecraft.* 를 이름 그대로 쓸 수 있다.
# ══════════════════════════════════════════════════════════════════════════
def _classpath(reg: dict) -> str:
    """1.21.11 만 골라 담는다.

    ⚠ run/mvt/libraries 에는 **1.21.4 잔재가 함께 산다.** 그냥 다 담으면
      낡은 DataFixerUpper·paper-api 가 앞서 잡혀서 엉뚱한 데서 죽는다
      (NoSuchFieldError: RegistryKey.GAME_RULE — 이 함정에 실제로 빠졌다).
      바닐라 번들러 안의 라이브러리를 **먼저** 놓아 버전을 못 박는다.
    """
    ver = "1.21.11"
    paper = ROOT / "run" / "mvt" / "versions" / ver / f"paper-{ver}.jar"
    if not paper.is_file():
        raise SystemExit(f"{BAD} Paper jar 이 없다: {paper}")

    libs = CACHE / "libs"
    if not libs.is_dir():
        with zipfile.ZipFile(ROOT / reg["vanilla_jar"]) as z:
            for n in z.namelist():
                if n.startswith("META-INF/libraries/") and n.endswith(".jar"):
                    tgt = libs / Path(n).name
                    tgt.parent.mkdir(parents=True, exist_ok=True)
                    tgt.write_bytes(z.read(n))

    jars = [str(paper)]
    jars += sorted(str(p) for p in libs.glob("*.jar"))                       # 바닐라 정본 라이브러리 우선
    jars += sorted(str(p) for p in (ROOT / "run" / "mvt" / "libraries").rglob("*.jar")
                   if "1.21.4" not in str(p))                                # Paper 것(adventure 등) — 낡은 건 뺀다
    return ":".join(jars)


def load_test(reg: dict) -> int:
    src = ROOT / "worldgen" / "loadtest" / "PurityLoadTest.java"
    if not src.is_file():
        raise SystemExit(f"{BAD} 시험 코드가 없다: {src}")
    javac = JDK.parent / "javac"
    if not javac.is_file():
        raise SystemExit(f"{BAD} JDK 21 이 없다: {javac}")

    cp = _classpath(reg)
    classes = CACHE / "loadtest"
    classes.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(javac), "-nowarn", "-cp", cp, "-d", str(classes), str(src)], check=True)

    print(f"   … 바닐라 코덱으로 데이터팩을 파싱한다 (서버는 안 띄운다)\n")
    proc = subprocess.run(
        [str(JDK), "-cp", f"{classes}:{cp}", "PurityLoadTest", str(pack_dir(reg))],
        capture_output=True, text=True,
    )
    for line in (proc.stdout + proc.stderr).splitlines():
        if "[STDOUT]:" in line:
            print("  " + line.split("[STDOUT]:", 1)[1].strip())
        elif "PASS —" in line or "FAIL —" in line:
            print("  " + line.strip())
    if proc.returncode == 0:
        print(f"\n{OK} 레지스트리 로딩 시험 통과 — 이 데이터팩으로 서버는 뜬다.")
    else:
        print(f"\n{BAD} 레지스트리 로딩 시험 **실패** — 이대로 월드를 깔면 서버가 안 뜬다.")
    return proc.returncode


# ══════════════════════════════════════════════════════════════════════════
# --emit-rcon : 인게임 실측
# ══════════════════════════════════════════════════════════════════════════
def emit_rcon(reg: dict) -> int:
    NETHER = {"fortress", "bastion_remnant", "nether_fossil"}
    END = {"end_city"}

    print("# ═══════════════════════════════════════════════════════════════════")
    print("# 혼천 — 세계 순도 실측 (RCON)")
    print("#")
    print("#   **새 월드에서 돌려라.** 기존 월드의 이미 생성된 청크는 안 바뀐다.")
    print("#   전제: worldgen/honcheon_purity 와 worldgen/honcheon_no_caves 를")
    print("#         <world>/datapacks/ 에 넣고 **월드를 새로 만든 뒤** 접속.")
    print("#")
    print("#   통과 기준을 각 줄 옆에 적었다. 하나라도 어긋나면 순도가 깨진 것이다.")
    print("# ═══════════════════════════════════════════════════════════════════")

    print("\n# ── ⓪ 데이터팩이 실제로 물렸는가 (이게 아니면 아래는 전부 무의미) ──")
    print("datapack list")
    print("#   통과: honcheon_purity · honcheon_no_caves 가 **enabled** 목록에 보인다")
    print("#   실패: disabled 에 있거나 안 보이면 → 서버 로그에서 레지스트리 오류를 찾아라")

    print("\n# ── ① 구조물: 전부 「찾을 수 없음」이어야 한다 ──")
    print("#   통과: \"Could not find a structure of type ... within reasonable distance\"")
    print("#   실패: 좌표가 나오면 그 구조물은 **살아 있다**")
    for entry in reg["structures"]["disable"]:
        for s in entry["holds"]:
            if s in NETHER:
                print(f"execute in minecraft:the_nether run locate structure minecraft:{s}")
            elif s in END:
                print(f"execute in minecraft:the_end run locate structure minecraft:{s}")
            else:
                print(f"locate structure minecraft:{s}")

    print("\n# ── ② 바이옴(금지): 전부 「찾을 수 없음」이어야 한다 ──")
    print("#   통과: \"Could not find a biome of type ... within reasonable distance\"")
    print("#   실패: 좌표가 나오면 기후 구획에 그 이름표가 남아 있다는 뜻이다 — 정적 검사부터 다시")
    for r in reg["biomes"]["remap"]:
        print(f"locate biome {r['from']}")

    print("\n# ── ③ 바이옴(양성 대조): 전부 **좌표가 나와야** 한다 ──")
    print("#   이게 없으면 「전부 못 찾음」이 순도가 아니라 **고장**이라는 뜻이다.")
    print("#   특히 savanna — 사막이 갔던 자리다. 이게 나와야 「더운 곳이 살아 있다」는 증거다.")
    for b in ["savanna", "badlands", "plains", "forest", "taiga", "grove",
              "jagged_peaks", "beach", "river", "cherry_grove", "jungle", "bamboo_jungle"]:
        print(f"locate biome minecraft:{b}")

    print("\n# ── ④ 야생의 가축·마스코트: 산야를 2000블록쯤 돌아다닌 **뒤에** 세어라 ──")
    print("#   (셀렉터는 **로드된 청크**만 본다 — 날아다니며 청크를 열고 나서 돌려야 뜻이 있다)")
    print("#   ★ **사냥터와 마을 밖**에서 돌려라. 마을 안에서 소·닭이 나오는 것은 **정상**이다")
    print("#     — 가축은 마을의 살림이고, 조성기가 CUSTOM 으로 놓은 것이다(번식·알로도 는다).")
    print("#   통과: 전부 \"Test failed\" (count 0)")
    for m in ["cow", "sheep", "pig", "chicken", "horse", "donkey", "llama", "villager",
              "wandering_trader", "goat", "iron_golem", "mooshroom", "panda", "frog"]:
        print(f"execute if entity @e[type=minecraft:{m}]")

    print("\n# ── ④-b 적대 몹: 무협에 언데드는 없다 ──")
    print("#   통과: 전부 \"Test failed\" (count 0)")
    for m in ["skeleton", "creeper", "enderman", "spider", "witch", "slime", "phantom"]:
        print(f"execute if entity @e[type=minecraft:{m}]")
    print("#")
    print("#   ⚠ **zombie 는 여기 없다 — 일부러 뺐다.** 예전 이 목록은 zombie 를 「0이어야 한다」고 적었는데")
    print("#     그것은 **틀린 눈**이었다: 산적·갈호·곽진의 몸이 바로 ZOMBIE 다")
    print("#     (mob_models.yml 이 좀비를 산적의 몸으로 징발했다 — 「사람은 조각이 될 수 없다」).")
    print("#     건강한 세계에서 zombie 수는 **0이 아니다**. 그 경보를 믿고 「고치면」 산적이 사라진다.")
    print("#     좀비를 세는 옳은 방법은 ⑥ 이다 — 태그로 가른다.")

    print("\n# ── ⑤ 야생의 짐승(양성 대조): 우리가 허용한 것은 **있어야** 한다 ──")
    for m in reg["wild_spawn"]["allow"]:
        print(f"execute if entity @e[type=minecraft:{m.lower()}]")
    print("#   통과: 최소한 몇은 \"Test passed\". 전부 0 이면 산야가 **죽은 것**이다 — 허용 목록이 안 먹었다")

    print("\n# ── ⑥ ★ 우리가 부른 것은 나오는가 — **가장 중요한 검사** ──")
    print("#")
    print("#   끄기만 하고 켜는 걸 잊으면 세계가 텅 빈다. 위의 ①~⑤ 는 전부 「없음」을 재는 눈이라,")
    print("#   **세계가 완전히 비어도 만점을 준다.** 이 검사만이 그것과 순도를 가른다.")
    print("#")
    print("#   ㉠ 사냥터 census — 등록부의 정원이 인게임에 서 있는가 (태그로 센다. 몸이 아니라)")
    print("#      ★ 북쪽 산길 안에 서서 돌려라 (구역 밖이면 스포너가 안 돈다 — player_near 96)")
    print("#      통과: 각 줄이 `n / 정원 m` 이고 **n > 0** (밤이면 맹수도). 전부 0/… 이면 산이 죽었다")
    print("혼천 사냥터")
    print("#")
    print("#   ㉡ 징발한 몸 — 이것들이 보이는 것이 **정상**이다 (우리가 CUSTOM 으로 심은 것)")
    print("#      통과: **\"Test passed\"** — 여기서 0 이 나오면 리스너가 우리 것까지 취소하고 있다")
    for m in ["zombie", "wolf", "hoglin", "ravager", "polar_bear"]:
        print(f"execute if entity @e[type=minecraft:{m}]")
    print("#")
    print("#   ㉢ 치안의 이음매 — region_state threshold_effects 가 인게임에 닿는가")
    print("#      치안을 30 아래로 떨어뜨린 뒤 `혼천 사냥터` 를 다시 보라.")
    print("#      통과: 도적 줄에 `(등록 3 · 치안 nn ↑)` 이 붙고 **정원이 늘어 있다**")
    print("#      (봇의 장부가 정본이다 — MVT 는 읽기만 한다)")

    print("\n# ── ⑥ 바이옴 분포 표본 (선택) — 스폰 둘레 4,000블록을 400점 격자로 뜬다 ──")
    print("#   각 줄은 Test passed / Test failed 를 뱉는다. passed 수를 세면 분포다.")
    print("#   ⚠ 청크를 로드한다 — 무겁다. 먼저 `forceload` 하거나 view-distance 를 줄이고 돌려라.")
    step, half = 200, 2000
    for x in range(-half, half + 1, step):
        for z in range(-half, half + 1, step):
            print(f"execute positioned {x} 96 {z} if biome ~ ~ ~ minecraft:desert")
    print("#   통과: **passed 0 / 441**. desert 를 savanna 로 바꿔 다시 돌리면 여럿 passed 여야 한다")

    print("\n# ── ⑦ 지표 용암 — 여기서는 못 센다 ──")
    print("#   `/locate` 로는 블록을 셀 수 없고 표본 격자로도 안 된다")
    print("#   (지표 용암 호수는 200청크에 하나 — 격자 점이 그 위에 떨어질 확률이 사실상 0 이다).")
    print("#   전수 조사는 **파괴적**이라 따로 뺐다. 위를 전부 끝낸 **뒤에** 마지막으로:")
    print("#       python3 tools/world_purity_audit.py --emit-lava-census --radius 256")
    return 0


# ══════════════════════════════════════════════════════════════════════════
# --emit-lava-census : 지표 용암 0블록을 **세어서** 증명한다
#
#   왜 따로인가 — `/locate` 로는 블록을 못 센다. 표본 격자로도 안 된다:
#   지표 용암 호수는 200청크에 하나라 격자 점이 그 위에 떨어질 확률이 사실상 0 이다.
#   블록을 **전수 조사**하는 유일한 명령이 `/fill … replace` 다 —
#   바꾼 블록 수를 돌려주기 때문이다.
#
#   ⚠ **파괴적이다.** 용암을 공기로 바꾼다. 반드시 **버려도 되는 시험 월드**에서 돌려라.
#      (어차피 새 월드를 깔아 실측하므로 문제가 없다. 다른 검사를 **먼저** 끝내고 이걸 마지막에.)
# ══════════════════════════════════════════════════════════════════════════
FILL_VOLUME_CAP = 32768        # 바닐라 FillCommand: 부피가 이보다 크면 거부한다
TILE = 128                     # 수평 타일 한 변 → 128×128×2 = 32,768 (상한에 딱 맞다)
YSTEP = FILL_VOLUME_CAP // (TILE * TILE)


def emit_lava_census(reg: dict, radius: int) -> int:
    """`/fill … replace` 만이 블록을 **센다**.

    `/locate` 는 블록을 못 세고, 표본 격자도 안 된다 — 지표 용암 호수는 200청크에 하나라
    격자 점이 그 위에 떨어질 확률이 사실상 0 이다. 전수 조사만이 「0」을 증명한다.
    """
    tiles = [(x, z)
             for x in range(-radius, radius, TILE)
             for z in range(-radius, radius, TILE)]

    def sweep(y0: int, y1: int, block: str, area: list[tuple[int, int]]) -> int:
        n = 0
        for (x, z) in area:
            for y in range(y0, y1 + 1, YSTEP):
                top = min(y + YSTEP - 1, y1)
                print(f"fill {x} {y} {z} {x + TILE - 1} {top} {z + TILE - 1} "
                      f"minecraft:air replace {block}")
                n += 1
        return n

    span = radius * 2
    print("# ═══════════════════════════════════════════════════════════════════")
    print("# 혼천 — 지표 용암 전수 조사 (RCON)")
    print("#")
    print(f"#   구역: X/Z −{radius}‥{radius - 1}  ({span}×{span} 블록 = {(span // 16) ** 2} 청크)")
    print("#   각 명령이 \"Successfully filled N blocks\" / \"No blocks were filled\" 를 뱉는다.")
    print("#   **N 을 전부 더한 것이 그 구간의 블록 수다.**")
    print("#")
    print("#   ⚠ **파괴적이다** — 용암을 공기로 바꾼다. **버려도 되는 시험 월드**에서,")
    print("#      다른 검사(--emit-rcon)를 전부 끝낸 **뒤에** 마지막으로 돌려라.")
    print("#   ⚠ 청크를 생성하며 돈다. 느리다.")
    print("# ═══════════════════════════════════════════════════════════════════")

    print("\n# ── ⓪ 양성 대조: **철광석** — 이것부터 돌려라 ──")
    print("#   우리는 광맥을 **남겼다**. 그러니 N > 0 이 나와야 한다.")
    print("#   0 이면 순도가 아니라 **고장**이다 — 피처 시스템이 죽었거나 이 측정법이 틀렸다.")
    print("#   **이게 0 이면 아래의 「용암 0」은 아무것도 증명하지 못한다.** (타일 하나면 충분하다)")
    c = sweep(0, 319, "minecraft:iron_ore", tiles[:1])
    c += sweep(-64, -1, "minecraft:deepslate_iron_ore", tiles[:1])
    print(f"#   → {c} 개 명령. 통과: 합계 **N > 0**")

    print("\n# ── ① 지표·상층 (y 0‥319) — **여기가 「필드」다** ──")
    print("#   lake_lava_surface · spring_lava · spring_lava_frozen · underwater_magma 가 놓이던 층.")
    c = sweep(0, 319, "minecraft:lava", tiles)
    print(f"#   → {c} 개 명령. ★ 통과: 합계 **0**. 한 블록이라도 나오면 순도가 깨진 것이다")

    print("\n# ── ② 지하 (y −54‥−1) — 여기도 0 이어야 한다 ──")
    print("#   아쿠아퍼 규칙상 −54 위의 유체는 **물**이다. 용암을 놓던 것은 lake_lava_underground 뿐인데")
    print("#   그걸 껐다. 그러니 0.")
    c = sweep(-54, -1, "minecraft:lava", tiles)
    print(f"#   → {c} 개 명령. 통과: 합계 **0**")

    print("\n# ── ③ 지심 (y −64‥−55) — **관측만 한다. 통과/실패가 아니다** ──")
    print("#   바닐라가 아쿠아퍼에 용암을 채우는 층. NoiseBasedChunkGenerator 에 **하드코딩**되어 있어")
    print("#   데이터팩으로 끌 수 없고, 끌 이유도 없다 — 거기까지 판 자는 지심을 만날 자격이 있다.")
    print("#")
    print("#   ⚠ 그런데 **0 에 가깝게 나올 것이다.** 예측을 먼저 적어 둔다 (맞든 틀리든 정직하게):")
    print("#     용암 바다는 「액체로 찬 **공동**」이지 「돌을 녹인 것」이 아니다.")
    print("#     우리는 자연 동굴을 껐다(10.08% → 0.04%) — **용암이 들어앉을 자리 자체가 없다.**")
    print("#     여기서 0 이 나와도 결함이 아니라 no_caves 의 당연한 귀결이다.")
    print("#     수치를 알려주면 문서에 확정하겠다.")
    c = sweep(-64, -55, "minecraft:lava", tiles)
    print(f"#   → {c} 개 명령. **관측값을 보고하라** (0 이든 아니든 정상)")

    print("\n# ── ④ 대조군 (권장) ──")
    print("#   **같은 시드**로 `honcheon_no_caves` **만** 건 월드를 하나 더 깔고 위를 그대로 돌려라.")
    print("#   (바닐라 생짜와 비교하면 동굴 용암이 섞여 **교란된다** — 동굴은 이미 no_caves 가 껐다.")
    print("#    두 월드의 차이가 정확히 `honcheon_purity` 가 지운 용암이어야 한다.)")
    print("#   → 「대조 N / 우리 0」이 나오면 「전부 0」이 순도의 증거지 측정 실패가 아님이 확정된다.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="세계 순도 검산 — 바닐라의 흔적이 남아 있는가")
    ap.add_argument("--build", action="store_true", help="등록부에서 데이터팩을 빚는다 (바닐라 정본 필요)")
    ap.add_argument("--load-test", action="store_true",
                    help="★ 바닐라 코덱으로 데이터팩을 파싱한다 — 레지스트리 실패 = 서버 사망")
    ap.add_argument("--emit-rcon", action="store_true", help="인게임 실측 명령을 뱉는다")
    ap.add_argument("--emit-lava-census", action="store_true",
                    help="지표 용암 전수 조사 명령을 뱉는다 (파괴적 — 시험 월드 전용)")
    ap.add_argument("--radius", type=int, default=128,
                    help="용암 조사 반경(블록). 기본 128 → 256×256 = 256청크")
    args = ap.parse_args()

    reg = registry()
    if args.build:
        return build(reg) or load_test(reg)   # 빚었으면 곧바로 코덱에 먹여 본다
    if args.load_test:
        return load_test(reg)
    if args.emit_lava_census:
        return emit_lava_census(reg, args.radius)
    if args.emit_rcon:
        return emit_rcon(reg)
    return audit(reg)


if __name__ == "__main__":
    sys.exit(main())
