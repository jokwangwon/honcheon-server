#!/usr/bin/env python3
"""모델 키 검수 — 코드·등록부가 요구하는 item_model 키가 팩에 실재하는가.

═══════════════════════════════════════════════════════════════════════════════
 왜 이 눈이 생겼나 — 보라 큐브의 진범
═══════════════════════════════════════════════════════════════════════════════
84baced (22:29) 이 config/skill_motion.yml 의 획 키를 honcheon:qi/blade_arc →
honcheon:qi/slash_arc 로 **바꿨다**. 팩은 그 커밋에서 안 구워졌다 — 모델의 개명은
a5bc2ba (22:43) 에 왔다. 그 사이(그리고 그 사이에 구운 팩을 든 모든 클라이언트에게)
서버는 **팩에 없는 키**를 얹었고, 클라이언트는 규약대로 '없는 모델'을 그렸다:
보라-검정 큐브. 휘두를 때마다.

1.21.4+ 규약: minecraft:item_model 이 가리키는 assets/<ns>/items/<경로>.json 이
**없으면 바탕 아이템으로 폴백하지 않는다.** 없는 모델(보라 큐브)을 그린다.
그러므로 **키를 얹는 것은 팩에 그 키가 있다는 약속**이다. 아무도 그 약속을 재지 않았다.

이 눈이 재는 것:
  ① 없는 키   — 코드·등록부가 요구하는데 팩에 없다 → **보라 큐브** (치명)
  ② 죽은 자산 — 팩에만 있고 아무도 안 쓴다 (등록제 위반의 다른 얼굴)
  ③ 사슬      — items/*.json → models/** → textures/** (한 고리만 끊겨도 보라 큐브)
  ④ 팩 게이트 — item_model 을 얹는 코드 경로가 **팩 상태를 보는가**
                (팩 없는 눈에 키를 얹으면 그 사람 화면이 보라색이다 — 게이트보다 나쁘다)

★ 접두사를 최종 키로 착각하지 않는다. Weapons·Goods 는 키를 **문자열로 조립**하므로
  이 눈도 **똑같이 조립해서** 최종 키를 낸다. 그리고 조립의 근거가 되는 소스 표현식이
  바뀌면 **소리내어 실패한다** (§ANCHORS) — 눈이 조용히 낡는 것을 막는다.

쓰임:
  python3 tools/model_key_audit.py                      # resourcepack/ 을 잰다
  python3 tools/model_key_audit.py --pack run/pack-http/honcheon_pack.zip
  python3 tools/model_key_audit.py --selftest           # 눈을 시험한다 (팩을 안 건드린다)
종료 코드: 0 = 통과, 1 = 위반
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NS = "honcheon"


# ═════════════════════════════════════════════════════════════════════════════
#  §ANCHORS — 코드가 키를 조립하는 **바로 그 표현식**.
#  이 표현식이 소스에서 사라지면 눈은 조용히 낡는 대신 **소리내어 죽는다.**
#  (오늘 이 프로젝트에서 눈이 열일곱 번 거짓말했다. 그 중 하나가 접두사를 최종 키로 본 눈이었다.)
# ═════════════════════════════════════════════════════════════════════════════
ANCHORS = [
    (
        "server-mvt/src/main/java/com/honcheon/mvt/Weapons.java",
        'return "honcheon:weapon/" + series.modelId + "_" + grade.slug;',
        "병기 = weapon/<계열 modelId>_<등급 slug>",
    ),
    (
        "server-mvt/src/main/java/com/honcheon/mvt/Weapons.java",
        'return "honcheon:weapon/myeong/" + sect;',
        "명병 = weapon/myeong/<sect> (계열이 문파와 맞을 때만)",
    ),
    (
        "server-mvt/src/main/java/com/honcheon/mvt/Goods.java",
        'cmd.setStrings(List.of("honcheon:" + modelPath));',
        "지물 = build(...) 의 modelPath 리터럴",
    ),
    (
        "server-mvt/src/main/java/com/honcheon/mvt/SkillDisplay.java",
        "NamespacedKey key = NamespacedKey.fromString(model.key());",
        "무공 획 = skill_motion.yml display.models[].key (리터럴)",
    ),
    (
        "server-mvt/src/main/java/com/honcheon/mvt/MobDisplay.java",
        "NamespacedKey key = NamespacedKey.fromString(part.key());",
        "짐승 형체 = mob_models.yml parts[].key (리터럴)",
    ),
]


def check_anchors() -> list[str]:
    """조립 규칙이 소스에 그대로 있는가. 없으면 눈이 낡은 것이다."""
    broken = []
    for rel, expr, what in ANCHORS:
        src = ROOT / rel
        if not src.exists():
            broken.append(f"{rel} 이 없다 ({what})")
        elif expr not in src.read_text(encoding="utf-8"):
            broken.append(f"{rel} 에서 조립 표현식이 사라졌다 — {what}\n      찾던 것: {expr}")
    return broken


# ═════════════════════════════════════════════════════════════════════════════
#  요구되는 키 — ① 코드가 **조립**하는 것  ② 등록부의 **리터럴**
# ═════════════════════════════════════════════════════════════════════════════

WEAPONS_SRC = ROOT / "server-mvt/src/main/java/com/honcheon/mvt/Weapons.java"
GOODS_SRC = ROOT / "server-mvt/src/main/java/com/honcheon/mvt/Goods.java"


def weapon_table() -> tuple[list[str], list[tuple[str, str, str]], dict[str, list[str]], dict[str, str]]:
    """Weapons.java 의 표를 **그대로** 읽는다 — 등급 slug · 계열(한글·modelId·Base) · Base→재질 · 명병 문파.

    ★ 이것이 이 눈의 심장이다. 베이스(바닐라 아이템)를 코드에서 직접 읽어야
      '팩 없는 눈이 무엇을 보는가' 를 **짐작하지 않고 잴 수 있다.**
    """
    src = WEAPONS_SRC.read_text("utf-8")

    grade_block = re.search(r"public enum Grade \{(.*?)\n    \}", src, re.S)
    grades = re.findall(r'^\s*\S+\("([a-z]+)"', grade_block.group(1), re.M) if grade_block else []

    # enum Series { 검("sword", Base.SWORD, ...) }  — modelId 가 null 이면 키를 안 얹는다
    series_block = re.search(r"public enum Series \{(.*?)\n\n", src, re.S)
    series: list[tuple[str, str, str]] = []      # (한글, modelId, BASE)
    if series_block:
        for kor, model, base in re.findall(
                r'^\s*(\S+?)\("([a-z]+)",\s*Base\.(\w+),', series_block.group(1), re.M):
            series.append((kor, model, base))

    # private enum Base { SWORD(Material.STONE_SWORD, Material.IRON_SWORD, ...) }  — 등급 순
    base_block = re.search(r"private enum Base \{(.*?)\n    \}", src, re.S)
    bases: dict[str, list[str]] = {}
    if base_block:
        for name, mats in re.findall(r"(\w+)\(((?:Material\.\w+,?\s*)+)\)", base_block.group(1)):
            bases[name] = [m.lower() for m in re.findall(r"Material\.(\w+)", mats)]

    # Map<String, Series> MYEONG_SERIES = Map.of("hwasan", Series.검, ...)
    myeong_block = re.search(r"MYEONG_SERIES = Map\.of\((.*?)\);", src, re.S)
    myeong: dict[str, str] = {}
    if myeong_block:
        # ★ 종결자([,)])를 요구하지 말 것 — Map.of 의 **마지막 항목**은 블록 끝이라 뒤가 없다.
        #   그렇게 쓴 첫 판이 sorimsa 를 통째로 놓쳤다 (그리고 그것을 '죽은 자산'이라 불렀다).
        myeong = dict(re.findall(r'"(\w+)",\s*Series\.(\w+)', myeong_block.group(1)))

    if not grades or not series or not bases or not myeong:
        raise SystemExit("!! Weapons.java 의 Grade/Series/Base/MYEONG_SERIES 표를 못 읽었다 — 눈을 고쳐라")
    for _, _, b in series:
        if b not in bases:
            raise SystemExit(f"!! Series 가 가리키는 Base.{b} 를 못 읽었다 — 눈을 고쳐라")
        if len(bases[b]) != len(grades):
            raise SystemExit(f"!! Base.{b} 의 재질 {len(bases[b])}개 ≠ 등급 {len(grades)}개 — 눈을 고쳐라")
    return grades, series, bases, myeong


def weapon_keys() -> dict[str, str]:
    """Weapons.java 의 Series × Grade + 명병 8자루를 **실제로 조립**한다 (접두사가 아니다)."""
    grades, series, _, myeong = weapon_table()
    keys = {}
    for _, model, _ in series:
        for g in grades:
            keys[f"{NS}:weapon/{model}_{g}"] = f"Weapons.java (계열 {model} × 등급 {g})"
    for sect in myeong:
        keys[f"{NS}:weapon/myeong/{sect}"] = f"Weapons.java enshrine() (명병 — {sect})"
    return keys


def weapon_bases() -> dict[str, set[str]]:
    """병기 키 → 그 키가 얹힐 수 있는 **바닐라 베이스 아이템**들 (팩 없는 눈이 보는 것)."""
    grades, series, bases, myeong = weapon_table()
    out: dict[str, set[str]] = {}
    kor_to = {kor: (model, base) for kor, model, base in series}
    for _, model, base in series:
        for i, g in enumerate(grades):
            out.setdefault(f"{NS}:weapon/{model}_{g}", set()).add(bases[base][i])
    # 명병은 등급을 가리지 않는다 (범철 매화검도 매화검이다) → 그 계열의 5재질 전부
    for sect, kor in myeong.items():
        if kor not in kor_to:
            raise SystemExit(f"!! MYEONG_SERIES 의 Series.{kor} 가 Series enum 에 없다 — 눈을 고쳐라")
        _, base = kor_to[kor]
        out[f"{NS}:weapon/myeong/{sect}"] = set(bases[base])
    return out


def _goods_pairs() -> list[tuple[str, str]]:
    """Goods.java 의 build(Material.X, "<키>", ...) — (바닐라 베이스, 키) 쌍."""
    src = GOODS_SRC.read_text("utf-8")
    pairs = []
    # build(Material.PAPER, "coin/jeonpyo", ...) — 리터럴.
    # ★ 뒤에 반드시 ',' 가 와야 한다. '"pelt/" + model' 처럼 **조각**인 것을 최종 키로 세면
    #   거짓 양성이다 (honcheon:pelt/ 라는 키는 존재하지 않는다 — 이 눈이 처음 그렇게 거짓말했다).
    for mat, path in re.findall(r'build\(\s*Material\.(\w+),\s*"([^"]+)"\s*,', src):
        pairs.append((mat.lower(), path))
    # build(Material.LEATHER, "pelt/" + model, ...) — 조립. model 은 바로 위 switch 가 낸다.
    for mat, pre, var in re.findall(r'build\(\s*Material\.(\w+),\s*"([^"]+)"\s*\+\s*(\w+)', src):
        sw = re.search(rf"String {var} = switch \(.*?\) \{{(.*?)\}};", src, re.S)
        if not sw:
            raise SystemExit(f"!! Goods.java: '{pre}' + {var} 의 switch 를 못 찾았다 — 눈을 고쳐라")
        for val in re.findall(r'->\s*"([^"]+)"', sw.group(1)):
            pairs.append((mat.lower(), f"{pre}{val}"))
    if not pairs:
        raise SystemExit("!! Goods.java 의 build(...) 를 하나도 못 읽었다 — 눈을 고쳐라")
    return pairs


def goods_keys() -> dict[str, str]:
    return {f"{NS}:{path}": "Goods.java" for _, path in _goods_pairs()}


def goods_bases() -> dict[str, set[str]]:
    out: dict[str, set[str]] = {}
    for mat, path in _goods_pairs():
        out.setdefault(f"{NS}:{path}", set()).add(mat)
    return out


def inventory_bases() -> dict[str, set[str]]:
    """**실물 아이템** 키 → 바닐라 베이스. 이 키들은 팩 없는 눈에도 나가므로 폴백이 있어야 한다.
    (무공 획·짐승 형체는 여기 없다 — 그건 팩 수락자에게만 나가는 ItemDisplay 다.)"""
    out = weapon_bases()
    out.update(goods_bases())
    return out


def _yaml_literal_keys(path: Path, field: str) -> dict[str, str]:
    """등록부의 리터럴 키 — 'key: "honcheon:..."' 한 줄씩 (yaml 의존 없이 읽는다)."""
    keys = {}
    if not path.exists():
        return keys
    for n, line in enumerate(path.read_text("utf-8").splitlines(), 1):
        m = re.match(rf'\s*{field}:\s*"({NS}:[^"]+)"', line)
        if m:
            keys[m.group(1)] = f"{path.relative_to(ROOT)}:{n}"
    return keys


def required_keys() -> dict[str, str]:
    keys: dict[str, str] = {}
    keys.update(weapon_keys())
    keys.update(goods_keys())
    # 무공 획·오의 (SkillDisplay 가 읽는다) · 짐승 형체 (MobDisplay 가 읽는다)
    keys.update(_yaml_literal_keys(ROOT / "config/skill_motion.yml", "key"))
    keys.update(_yaml_literal_keys(ROOT / "config/mob_models.yml", "key"))
    return keys


def reserved_keys() -> dict[str, str]:
    """아직 코드가 안 박는 **예약** 키 — 등록부에 그렇게 적혀 있으면 죽은 자산이 아니다."""
    res = {}
    res.update(_yaml_literal_keys(ROOT / "config/mob_models.yml", "reserved_model"))

    design = ROOT / "config/resourcepack_design.yml"
    if design.exists():
        for n, line in enumerate(design.read_text("utf-8").splitlines(), 1):
            m = re.search(rf'model_key:\s*"({NS}:[^"]+)"', line)
            if not m:
                continue
            key = m.group(1)
            if "<" in key:          # honcheon:weapon/sword_<등급> — 접두사다. 최종 키가 아니다.
                continue            # (조립은 weapon_keys() 가 한다 — 여기서 통과시키면 거짓 양성이다)
            res[key] = f"resourcepack_design.yml:{n} (등록·미배선)"
    # 명병 8자루는 더 이상 예약이 아니다 — Weapons.enshrine() 이 키를 박는다 (weapon_keys() 가 센다).
    return res


# ═════════════════════════════════════════════════════════════════════════════
#  팩 — assets/honcheon/items/**.json 이 곧 키다 (1.21.4+: 이 정의가 없으면 모델이 안 뜬다)
# ═════════════════════════════════════════════════════════════════════════════

class Pack:
    def __init__(self, root: Path):
        self.root = root

    def keys(self) -> dict[str, Path]:
        base = self.root / "assets" / NS / "items"
        if not base.is_dir():
            raise SystemExit(f"!! 팩에 assets/{NS}/items/ 가 없다 ({self.root})\n"
                             "   1.21.4+ 는 이 정의가 있어야 item_model 이 뜬다 — 없으면 전부 보라 큐브다")
        return {f"{NS}:{p.relative_to(base).with_suffix('').as_posix()}": p
                for p in sorted(base.rglob("*.json"))}

    def has_model(self, ref: str) -> bool:
        ns, path = ref.split(":", 1) if ":" in ref else ("minecraft", ref)
        if ns == "minecraft":
            return True     # 바닐라 모델은 클라이언트가 갖고 있다
        return (self.root / "assets" / ns / "models" / f"{path}.json").exists()

    def has_texture(self, ref: str) -> bool:
        ns, path = ref.split(":", 1) if ":" in ref else ("minecraft", ref)
        if ns == "minecraft":
            return True
        return (self.root / "assets" / ns / "textures" / f"{path}.png").exists()

    def chain(self) -> list[str]:
        """③ 사슬 — items → models → textures. 한 고리만 끊겨도 그 키는 보라 큐브다."""
        bad = []
        # ★ 바닐라 베이스 분기(minecraft/items/**)도 **똑같이** 건넌다. 여기서 모델 경로를 한 번
        #   틀리면(honcheon:item/weapon/weapon/… 처럼) 팩 있는 눈에 보라 큐브가 뜬다 — 실제로
        #   이 배선의 첫 판이 그렇게 틀렸고, 그때 이 눈은 honcheon/items 만 보느라 **놓쳤다.**
        defs = dict(self.keys())
        vdir = self.root / "assets" / "minecraft" / "items"
        for p in sorted(vdir.rglob("*.json")) if vdir.is_dir() else []:
            defs[f"minecraft:{p.relative_to(vdir).with_suffix('').as_posix()}"] = p

        for key, path in defs.items():
            try:
                doc = json.loads(path.read_text("utf-8"))
            except json.JSONDecodeError as e:
                bad.append(f"{key} — 아이템 정의가 깨진 JSON ({e})")
                continue
            refs: list[str] = []
            _collect_models(doc, refs)
            if not refs:
                bad.append(f"{key} — 아이템 정의가 모델을 하나도 안 가리킨다")
            for ref in refs:
                if not self.has_model(ref):
                    bad.append(f"{key} → 없는 모델 {ref}")

        mdir = self.root / "assets" / NS / "models"
        for mp in sorted(mdir.rglob("*.json")) if mdir.is_dir() else []:
            try:
                doc = json.loads(mp.read_text("utf-8"))
            except json.JSONDecodeError as e:
                bad.append(f"models/{mp.relative_to(mdir)} — 깨진 JSON ({e})")
                continue
            name = mp.relative_to(mdir).as_posix()
            parent = doc.get("parent")
            if parent and not self.has_model(parent):
                bad.append(f"models/{name} → 없는 부모 {parent}")
            for slot, tex in (doc.get("textures") or {}).items():
                if isinstance(tex, str) and not tex.startswith("#") and not self.has_texture(tex):
                    bad.append(f"models/{name} → 없는 텍스처 [{slot}] {tex}")
            # 클라이언트가 모델 파싱에 실패해도 결과는 같다: 보라 큐브
            for i, el in enumerate(doc.get("elements") or []):
                for ax, axis in enumerate("xyz"):
                    lo, hi = el.get("from", [0, 0, 0])[ax], el.get("to", [0, 0, 0])[ax]
                    if not (-16 <= lo <= 32) or not (-16 <= hi <= 32):
                        bad.append(f"models/{name} el{i}.{axis} 가 [-16,32] 밖이다 ({lo}..{hi})"
                                   " — 클라이언트가 모델을 버린다")
                    if lo > hi:
                        bad.append(f"models/{name} el{i}.{axis} from > to ({lo} > {hi})")
        return bad


def _collect_models(node, out: list[str]) -> None:
    if isinstance(node, dict):
        if node.get("type") == "minecraft:model" and isinstance(node.get("model"), str):
            out.append(node["model"])
        for v in node.values():
            _collect_models(v, out)
    elif isinstance(node, list):
        for v in node:
            _collect_models(v, out)


# ═════════════════════════════════════════════════════════════════════════════
#  ④ 팩 게이트 — 키를 얹는 손이 **팩 상태를 보는가**
# ═════════════════════════════════════════════════════════════════════════════
#  ItemDisplay 는 서버가 아니라 **클라이언트가 그린다.** 팩 없는 눈에 키를 얹은 아이템을
#  보내면 그 사람만 보라 큐브를 본다. 팩 게이트 불가침(팩 없어도 플레이 가능)이 여기서 깨진다.
#
#  GATED   — 팩 수락자에게만 키가 나간다 (SkillDisplay: withPack · MobDisplay: pack_gate)
#  UNGATED — 인벤토리의 실물 아이템. ItemStack 은 **모두에게 같은 바이트**라 사람마다 가를 수 없다.
#            팩을 거절한 사람은 그 아이템을 **보라 큐브로 본다.** 구조적 위반이다 (아래 보고 참조).
#  ★ 이 표는 더 이상 '판정'이 아니라 '기대'다. 판정은 아래 gate_audit() 이 **소스를 읽어서** 낸다.
#     (전임자의 눈은 여기 적힌 UNGATED 를 그대로 출력했다 — 표를 읽는 눈은 눈이 아니라 메아리다.)
GATED = {
    "SkillDisplay.java": "packed(SUCCESSFULLY_LOADED) 인 눈에만 — spawn() 이 관람석을 가른다",
    "MobDisplay.java": "mob_models.yml pack_gate: adaptive — 관중 전원이 수락일 때만 형체를 띄운다",
}
#  실물 ItemStack 을 만드는 손. ItemStack 은 **모두에게 같은 바이트**라 사람마다 가를 수 없다 —
#  그러므로 이 손들은 item_model 을 **쓰면 안 된다** (팩 거절자에게 보라 큐브가 간다).
#  대신 custom_model_data 로 키를 담고, 팩이 바닐라 베이스에서 분기한다 (팩 없으면 컴포넌트가 불활성).
INVENTORY = {
    "Weapons.java": "병기 45 + 명병 8 — 인벤토리 실물",
    "Goods.java": "지물·재료·기물 — 인벤토리 실물",
}


def gate_audit(jdir: Path | None = None) -> tuple[list[str], list[str]]:
    """④ 키를 얹는 손이 팩 상태를 보는가 — **소스를 읽어서** 판정한다.

    (jdir 은 눈 시험용이다 — 사본에 상처를 내고 이 눈이 정말 잡는지 본다.)"""
    ok, violations = [], []
    jdir = jdir or ROOT / "server-mvt/src/main/java/com/honcheon/mvt"
    for src in sorted(jdir.glob("*.java")):
        text = src.read_text("utf-8")
        uses_item_model = "setItemModel(" in text
        uses_cmd = "setCustomModelDataComponent(" in text
        name = src.name

        if name in INVENTORY:
            what = INVENTORY[name]
            if uses_item_model:
                violations.append(
                    f"{name:20s} UNGATED  {what} — item_model 을 실물에 박는다.\n"
                    f"   {'':20s}          팩 없는 눈엔 **보라 큐브**다 (1.21.4+ 는 바탕으로 폴백하지 않는다)")
            elif not uses_cmd:
                violations.append(f"{name:20s} ?        {what} — 모델 키를 얹는 손이 사라졌다. 눈을 고쳐라")
            else:
                ok.append(f"{name:20s} FALLBACK {what} — custom_model_data (팩 없으면 불활성 → 바닐라 그대로)")
            continue

        if not uses_item_model:
            continue
        if name in GATED:
            ok.append(f"{name:20s} GATED    {GATED[name]}")
        else:
            violations.append(f"{name:20s} UNKNOWN  item_model 을 얹는데 게이트를 모르는 새 경로다 — 눈에 등록하라")
    return ok, violations


# ═════════════════════════════════════════════════════════════════════════════
#  ⑤ 바닐라 폴백 — 팩 없는 눈이 **무엇을 보는가**
# ═════════════════════════════════════════════════════════════════════════════
#  실물 아이템 키는 custom_model_data.strings[0] 으로 나간다. 팩이 그 키를 바닐라 베이스에서
#  분기해야(assets/minecraft/items/<base>.json) 팩 있는 눈이 3D 를 본다. 그리고 그 분기에는
#  **fallback 이 있어야** 팩 있는 눈의 '평범한 철검'이 멀쩡하다 (전역 오염 0).
#
#  ★ 이 눈은 **베이스까지 대조한다.** 키가 엉뚱한 베이스에 구워지면 (예: 정련검 키를 stone_sword 에)
#    클라이언트는 조용히 바닐라 철검을 그린다 — 크래시도 보라 큐브도 없이 **3D 가 그냥 안 나온다.**
#    소리 없는 실패라서, 재지 않으면 아무도 모른다.

def dispatch_audit(pack: "Pack") -> list[str]:
    bad = []
    want = inventory_bases()
    have: dict[str, set[str]] = {}          # 키 → 그 키를 무는 베이스 파일들

    vdir = pack.root / "assets" / "minecraft" / "items"
    for p in sorted(vdir.rglob("*.json")) if vdir.is_dir() else []:
        base = p.relative_to(vdir).with_suffix("").as_posix()
        try:
            doc = json.loads(p.read_text("utf-8"))
        except json.JSONDecodeError as e:
            bad.append(f"minecraft/items/{base}.json — 깨진 JSON ({e})")
            continue
        model = doc.get("model") or {}
        if model.get("type") != "minecraft:select" \
                or model.get("property") != "minecraft:custom_model_data":
            bad.append(f"minecraft/items/{base}.json — custom_model_data select 가 아니다"
                       f" (팩 없는 눈은 몰라도, 팩 있는 눈이 이 베이스의 혼천 아이템을 못 본다)")
            continue
        if "fallback" not in model:
            bad.append(f"minecraft/items/{base}.json — **fallback 이 없다**. 컴포넌트 없는 바닐라"
                       f" {base} 가 보라 큐브가 된다 (전역 오염)")
        for case in model.get("cases") or []:
            whens = case.get("when")
            for w in (whens if isinstance(whens, list) else [whens]):
                if isinstance(w, str) and w.startswith(f"{NS}:"):
                    have.setdefault(w, set()).add(base)

    for key, bases in sorted(want.items()):
        got = have.get(key, set())
        if not got:
            bad.append(f"{key} — 어떤 바닐라 베이스도 이 키를 안 문다. 팩 있는 눈에 **3D 가 안 뜬다**"
                       f" (기대: {', '.join(sorted(bases))})")
            continue
        if missing := bases - got:
            bad.append(f"{key} — 베이스 {', '.join(sorted(missing))} 에 분기가 없다."
                       f" 그 등급을 들면 **바닐라 아이템이 그대로 보인다** (조용한 실패)")
        if extra := got - bases:
            bad.append(f"{key} — 코드가 안 쓰는 베이스 {', '.join(sorted(extra))} 에 분기가 있다"
                       f" (죽은 분기 — 코드는 {', '.join(sorted(bases))} 만 만든다)")
    return bad


# ═════════════════════════════════════════════════════════════════════════════

def audit(pack_root: Path) -> int:
    print("── 모델 키 검수 (item_model ⟷ 팩) ──")
    print(f"팩: {pack_root}")

    broken = check_anchors()
    if broken:
        print("\n!! 눈이 낡았다 — 코드의 키 조립 방식이 바뀌었다:")
        for b in broken:
            print("   · " + b)
        print("   tools/model_key_audit.py 의 §ANCHORS 를 고쳐라. 그 전엔 이 검수를 믿지 말라.")
        return 1

    pack = Pack(pack_root)
    have = pack.keys()
    want = required_keys()
    reserved = reserved_keys()
    print(f"요구 키 {len(want)}개 (코드 조립 + 등록부 리터럴) · 팩 정의 {len(have)}개\n")

    fail = 0

    print("① 없는 키 — 코드가 요구하는데 팩에 없다 (= 보라 큐브)")
    missing = sorted(k for k in want if k not in have)
    if missing:
        fail = 1
        for k in missing:
            print(f"   !! {k}   ← {want[k]}")
        print("   → 팩을 굽거나(모델을 만든다) 키를 안 얹는다(폴백 바닐라). 둘 중 하나다.")
    else:
        print("   없음 — 요구된 키가 전부 팩에 있다")

    print("\n② 죽은 자산 — 팩에만 있고 아무도 안 쓴다")
    dead = sorted(k for k in have if k not in want)
    unregistered = [k for k in dead if k not in reserved]
    for k in dead:
        if k in reserved:
            print(f"   · {k}   (예약 — {reserved[k]})")
    if unregistered:
        fail = 1
        for k in unregistered:
            print(f"   !! {k}   ← 등록부에 없다 (예약도 아니다)")
    elif not dead:
        print("   없음")

    print("\n③ 사슬 — items → models → textures")
    chain = pack.chain()
    if chain:
        fail = 1
        for c in chain:
            print(f"   !! {c}")
    else:
        print(f"   온전하다 ({len(have)}개 키 전부 모델·텍스처까지 닿는다)")

    print("\n④ 팩 게이트 — 키를 얹는 손이 팩 상태를 보는가")
    gated, ungated = gate_audit()
    for g in gated:
        print("   ✓ " + g)
    for u in ungated:
        print("   !! " + u)
    if ungated:
        fail = 1
        print("   → 팩 게이트 불가침: 팩이 없어도 플레이는 된다. 팩 없는 사람에게 보라 큐브를")
        print("     보이는 것은 게이트를 세운 것보다 나쁘다 (게임이 고장난 것처럼 보인다).")
        print("     ItemStack 은 사람마다 가를 수 없으므로 — 실물에 item_model 을 박지 말라.")
        print("     실물의 길은 custom_model_data + 바닐라 베이스 분기다 (팩 없으면 불활성).")

    print("\n⑤ 바닐라 폴백 — 팩 없는 눈이 무엇을 보는가 (실물 아이템 키의 베이스 대조)")
    disp = dispatch_audit(pack)
    if disp:
        fail = 1
        for d in disp:
            print(f"   !! {d}")
    else:
        inv = inventory_bases()
        vdir = pack_root / "assets" / "minecraft" / "items"
        n = len(list(vdir.rglob("*.json"))) if vdir.is_dir() else 0
        print(f"   온전하다 — 실물 {len(inv)}키가 전부 바닐라 베이스 {n}종에서 분기한다 (fallback 포함).")
        print("   팩 없는 눈 = 바닐라 아이템 그대로 (돌·철·다이아·금 검 / 종이 / 가죽) — **보라 큐브 아님**")
        print("   팩 있는 눈 = 3D 병기·명병·지물. 컴포넌트 없는 바닐라 아이템 = fallback (전역 오염 0)")

    print()
    print(f"총평: 요구 {len(want)}키 · 없는 키 {len(missing)} · 미등록 죽은 자산 {len(unregistered)}"
          f" · 끊긴 사슬 {len(chain)} · 게이트 위반 {len(ungated)} · 폴백 위반 {len(disp)}"
          + ("  → 위반" if fail else "  → 통과"))
    print("판정: " + ("위반 — 위의 !! 를 보라" if fail else "통과"))
    return fail


# ═════════════════════════════════════════════════════════════════════════════
#  눈을 시험한다 — 일부러 부러뜨리고 눈이 잡는지 본다 (팩 원본은 건드리지 않는다)
# ═════════════════════════════════════════════════════════════════════════════

def _findings(pack: Path) -> set[str]:
    """그 팩에서 이 눈이 낸 지적(!!) 들."""
    out = subprocess.run([sys.executable, __file__, "--pack", str(pack)],
                         capture_output=True, text=True)
    return {ln.strip() for ln in out.stdout.splitlines()
            if ln.lstrip().startswith("!!")}   # '판정: 위반 — 위의 !! 를 보라' 는 지적이 아니다


def selftest() -> int:
    """★ 눈을 시험한다 — 일부러 부러뜨리고 눈이 **정말로** 잡는지 본다.

    **차이**를 본다 — 상처가 **새 지적을 낳는가.** 그것만이 눈이 보고 있다는 증거다.
    (이 프로젝트에서 눈이 스무 번 넘게 거짓말했다. 종료 코드만 본 눈이 그 중 여럿이었다.)
    """
    src = ROOT / "resourcepack"
    print("── 눈을 시험한다 (사본에 상처를 내고 눈이 잡는지 본다) ──")
    print("   ※ resourcepack/ · 소스 원본은 건드리지 않는다 — 사본에만 상처를 낸다\n")

    baseline = _findings(src)
    clean_ok = not baseline
    print(f"  [{'통과' if clean_ok else '거짓 양성!'}] 멀쩡한 팩에 지적이 없다 ({len(baseline)}건)")
    for b in sorted(baseline):
        print("      거짓 양성: " + b)

    cases = [
        ("모델 하나를 지운다 (items/qi/slash_arc.json) — 진범이 낸 바로 그 상처",
         lambda r: (r / f"assets/{NS}/items/qi/slash_arc.json").unlink(),
         f"{NS}:qi/slash_arc"),
        ("키를 오타 낸다 (weapon/sword_beomcheol → sword_beomchoel)",
         lambda r: (r / f"assets/{NS}/items/weapon/sword_beomcheol.json").rename(
             r / f"assets/{NS}/items/weapon/sword_beomchoel.json"),
         f"{NS}:weapon/sword_beomcheol"),
        ("사슬을 끊는다 (models/item/qi/slash_arc.json 을 지운다)",
         lambda r: (r / f"assets/{NS}/models/item/qi/slash_arc.json").unlink(),
         "없는 모델"),
        # 획 텍스처는 **아틀라스 안**(textures/item/qi/) 에 산다 — 그 밖은 클라이언트가 안 훑는다
        # (2026-07 보라 큐브의 원인. texture_audit 축 ⑮ 가 그 자리를 지킨다)
        ("텍스처를 지운다 (textures/item/qi/slash_arc.png)",
         lambda r: (r / f"assets/{NS}/textures/item/qi/slash_arc.png").unlink(),
         "없는 텍스처"),
        ("모델을 [-16,32] 밖으로 민다 (클라이언트가 모델을 버린다)",
         lambda r: _push_out_of_range(r / f"assets/{NS}/models/item/qi/slash_arc.json"),
         "밖이다"),
        # ─── ⑤ 바닐라 폴백 — 새 눈 ───
        ("바닐라 분기를 통째로 지운다 (items/iron_sword.json) — 팩 있는 눈에 3D 가 안 뜬다",
         lambda r: (r / "assets/minecraft/items/iron_sword.json").unlink(),
         "안 문다"),
        ("fallback 을 뽑는다 (iron_sword.json) — 세상의 모든 철검이 보라 큐브가 된다",
         lambda r: _drop_fallback(r / "assets/minecraft/items/iron_sword.json"),
         "fallback 이 없다"),
        ("분기의 모델 경로를 틀린다 (honcheon:item/weapon/weapon/…) — 이 배선의 첫 판이 낸 진짜 상처",
         lambda r: _double_prefix(r / "assets/minecraft/items/iron_sword.json"),
         "없는 모델"),
        ("키를 엉뚱한 베이스에 굽는다 (정련검을 stone_sword 로) — **소리 없는** 실패",
         lambda r: _move_case(r / "assets/minecraft/items/iron_sword.json",
                              r / "assets/minecraft/items/stone_sword.json",
                              f"{NS}:weapon/sword_jeongryeon"),
         "분기가 없다"),
    ]
    passed = 0
    for name, wound, expect in cases:
        with tempfile.TemporaryDirectory() as tmp:
            copy = Path(tmp) / "pack"
            shutil.copytree(src, copy)
            wound(copy)
            new = _findings(copy) - baseline          # ★ 상처가 낳은 **새** 지적만 센다
            caught = any(expect in f for f in new)
            print(f"  [{'잡았다' if caught else '놓쳤다'}] {name}")
            if caught:
                passed += 1
                for f in sorted(new):
                    if expect in f:
                        print("      → " + f)
            else:
                print(f"      기대한 새 지적: '{expect}' · 실제로 새로 난 지적: {sorted(new) or '없음'}")

    # ─── ④ 팩 게이트 — 소스에 상처를 낸다 (팩이 아니라 **손**을 시험한다) ───
    #  이것이 오늘의 핵심 위반이었다. 눈이 이것을 못 잡으면 되돌아가도 아무도 모른다.
    _, live_viol = gate_audit()
    gate_clean = not live_viol
    print(f"  [{'통과' if gate_clean else '거짓 양성!'}] 멀쩡한 소스에 게이트 지적이 없다")
    for v in live_viol:
        print("      거짓 양성: " + v.splitlines()[0])

    with tempfile.TemporaryDirectory() as tmp:
        jsrc = ROOT / "server-mvt/src/main/java/com/honcheon/mvt"
        jcopy = Path(tmp) / "mvt"
        shutil.copytree(jsrc, jcopy)
        w = jcopy / "Weapons.java"
        # 되돌린다: 실물 ItemStack 에 item_model 을 다시 박는다 (= 오늘 고친 바로 그 병)
        w.write_text(w.read_text("utf-8").replace(
            "        applyModel(meta, series, grade, null, demonic ? STATE_UNIDENTIFIED : null);",
            '        meta.setItemModel(new NamespacedKey("honcheon", "x"));'), encoding="utf-8")
        _, viol = gate_audit(jcopy)
        regressed = any("UNGATED" in v and "Weapons.java" in v for v in viol)
        print(f"  [{'잡았다' if regressed else '놓쳤다'}] "
              "실물에 item_model 을 되돌려 박는다 (오늘 고친 그 병) — 게이트가 다시 깨지는가")
        for v in viol:
            print("      → " + v.splitlines()[0])

    total = len(cases) + 3
    got = passed + (1 if clean_ok else 0) + (1 if gate_clean else 0) + (1 if regressed else 0)
    print(f"\n눈 시험: {got}/{total}" + ("  — 눈이 본다" if got == total else "  — 눈이 멀었다"))
    return 0 if got == total else 1


def _push_out_of_range(model: Path) -> None:
    doc = json.loads(model.read_text("utf-8"))
    doc["elements"][0]["from"][0] = -40.0      # [-16, 32] 밖 — 클라이언트가 모델을 버린다
    model.write_text(json.dumps(doc), encoding="utf-8")


def _drop_fallback(item_def: Path) -> None:
    doc = json.loads(item_def.read_text("utf-8"))
    doc["model"].pop("fallback", None)
    item_def.write_text(json.dumps(doc), encoding="utf-8")


def _double_prefix(item_def: Path) -> None:
    doc = json.loads(item_def.read_text("utf-8"))
    c = doc["model"]["cases"][0]["model"]
    c["model"] = c["model"].replace("honcheon:item/", "honcheon:item/weapon/")
    item_def.write_text(json.dumps(doc), encoding="utf-8")


def _move_case(frm: Path, to: Path, key: str) -> None:
    """키를 엉뚱한 베이스로 옮긴다 — 클라이언트는 조용히 바닐라를 그린다 (보라 큐브도 아니다)."""
    a = json.loads(frm.read_text("utf-8"))
    b = json.loads(to.read_text("utf-8"))
    moved = [c for c in a["model"]["cases"] if c["when"] == key]
    a["model"]["cases"] = [c for c in a["model"]["cases"] if c["when"] != key]
    b["model"]["cases"].extend(moved)
    frm.write_text(json.dumps(a), encoding="utf-8")
    to.write_text(json.dumps(b), encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser(description="모델 키 검수 — item_model 키가 팩에 실재하는가")
    ap.add_argument("--pack", default=str(ROOT / "resourcepack"),
                    help="팩 디렉터리 또는 zip (기본: resourcepack/)")
    ap.add_argument("--selftest", action="store_true", help="눈을 시험한다 (팩을 안 건드린다)")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    p = Path(args.pack)
    if not p.exists():
        raise SystemExit(f"!! 팩이 없다: {p}")
    if p.suffix == ".zip":
        with tempfile.TemporaryDirectory() as tmp:
            with zipfile.ZipFile(p) as z:
                z.extractall(tmp)
            return audit(Path(tmp))
    return audit(p)


if __name__ == "__main__":
    sys.exit(main())
