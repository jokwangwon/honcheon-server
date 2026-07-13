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
        'new NamespacedKey("honcheon", "weapon/" + series.modelId + "_" + grade.slug)',
        "병기 = weapon/<계열 modelId>_<등급 slug>",
    ),
    (
        "server-mvt/src/main/java/com/honcheon/mvt/Goods.java",
        'meta.setItemModel(new NamespacedKey("honcheon", modelPath));',
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

def weapon_keys() -> dict[str, str]:
    """Weapons.java 의 Series × Grade 를 **실제로 조립**한다 (접두사가 아니다)."""
    src = (ROOT / "server-mvt/src/main/java/com/honcheon/mvt/Weapons.java").read_text("utf-8")

    # enum Grade { 범철("beomcheol", ...), ... }
    grade_block = re.search(r"public enum Grade \{(.*?)\n    \}", src, re.S)
    grades = re.findall(r'^\s*\S+\("([a-z]+)"', grade_block.group(1), re.M) if grade_block else []

    # enum Series { 검("sword", Base.SWORD, ...), ... }  — modelId 가 null 이면 키를 안 얹는다
    series_block = re.search(r"public enum Series \{(.*?)\n\n", src, re.S)
    series = []
    if series_block:
        for name, model in re.findall(r"^\s*(\S+?)\((\"[a-z]+\"|null),", series_block.group(1), re.M):
            if model != "null":
                series.append(model.strip('"'))

    if not grades or not series:
        raise SystemExit("!! Weapons.java 의 Grade/Series 표를 못 읽었다 — 눈을 고쳐라")

    keys = {}
    for s in series:
        for g in grades:
            keys[f"{NS}:weapon/{s}_{g}"] = f"Weapons.java (계열 {s} × 등급 {g})"
    return keys


def goods_keys() -> dict[str, str]:
    """Goods.java 가 build(...) 에 넘기는 modelPath — 리터럴과 '조각 + 변수' 둘 다."""
    src = (ROOT / "server-mvt/src/main/java/com/honcheon/mvt/Goods.java").read_text("utf-8")
    keys = {}

    # build(Material.X, "tome/gugyeol", ...) — 리터럴.
    # ★ 뒤에 반드시 ',' 가 와야 한다. '"pelt/" + model' 처럼 **조각**인 것을 최종 키로 세면
    #   거짓 양성이다 (honcheon:pelt/ 라는 키는 존재하지 않는다 — 이 눈이 처음 그렇게 거짓말했다).
    for path in re.findall(r'build\(\s*Material\.\w+,\s*"([^"]+)"\s*,', src):
        keys[f"{NS}:{path}"] = "Goods.java (리터럴)"

    # build(Material.LEATHER, "pelt/" + model, ...) — 조립. model 은 바로 위 switch 가 낸다.
    for prefix in re.findall(r'build\(\s*Material\.\w+,\s*"([^"]+)"\s*\+\s*(\w+)', src):
        pre, var = prefix
        # switch (beast) { case "늑대" -> "wolf"; ... }  의 오른쪽 값들
        sw = re.search(rf"String {var} = switch \(.*?\) \{{(.*?)\}};", src, re.S)
        if not sw:
            raise SystemExit(f"!! Goods.java: '{pre}' + {var} 의 switch 를 못 찾았다 — 눈을 고쳐라")
        for val in re.findall(r'->\s*"([^"]+)"', sw.group(1)):
            keys[f"{NS}:{pre}{val}"] = f"Goods.java (조립 {pre}<{var}>)"
    return keys


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

    # 명병 — resourcepack_design.yml 은 honcheon:weapon/myeong/<sect> 로만 적는다 (접두사).
    # 팩은 8자루를 구웠고 Weapons.java 는 아직 그 키를 안 박는다 → 배선 대기 (죽은 자산 아님).
    for sect in ("hwasan", "mudang", "sorimsa", "namgung", "paengga",
                 "jeomchang", "jongnam", "dangga"):
        res[f"{NS}:weapon/myeong/{sect}"] = "resourcepack_design.yml (명병 — Weapons.java 배선 대기)"
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
        for key, path in self.keys().items():
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
GATES = {
    "SkillDisplay.java": ("GATED",
                          "packed(SUCCESSFULLY_LOADED) 인 눈에만 — spawn() 이 관람석을 가른다"),
    "MobDisplay.java": ("GATED",
                        "mob_models.yml pack_gate: adaptive — 관중 전원이 수락일 때만 형체를 띄운다"),
    "Weapons.java": ("UNGATED",
                     "인벤토리 실물 — 팩 없는 눈에 **보라 큐브**로 보인다 (병기 45자루 전부)"),
    "Goods.java": ("UNGATED",
                   "인벤토리 실물 — 팩 없는 눈에 **보라 큐브**로 보인다 (지물·재료·기물 전부)"),
}


def gate_audit() -> tuple[list[str], list[str]]:
    ok, violations = [], []
    jdir = ROOT / "server-mvt/src/main/java/com/honcheon/mvt"
    for src in sorted(jdir.glob("*.java")):
        if "setItemModel(" not in src.read_text("utf-8"):
            continue
        kind, why = GATES.get(src.name, ("UNKNOWN", "게이트를 모르는 새 경로다 — 눈에 등록하라"))
        line = f"{src.name:20s} {kind:8s} {why}"
        (ok if kind == "GATED" else violations).append(line)
    return ok, violations


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

    print("\n④ 팩 게이트 — item_model 을 얹는 손이 팩 상태를 보는가")
    gated, ungated = gate_audit()
    for g in gated:
        print("   ✓ " + g)
    for u in ungated:
        print("   !! " + u)
    if ungated:
        fail = 1
        print("   → 팩 게이트 불가침: 팩이 없어도 플레이는 된다. 팩 없는 사람에게 보라 큐브를")
        print("     보이는 것은 게이트를 세운 것보다 나쁘다 (게임이 고장난 것처럼 보인다).")
        print("     ItemStack 은 사람마다 가를 수 없으므로 — 팩이 보편이 아니라면 키를 얹지 말라.")
        print("   ★ 이 눈은 이것을 **통과시키지 않는다.** 깨진 불변식 위에서 초록불을 켜는 눈은")
        print("     눈이 아니다. 고치거나(키를 안 얹는다), 규약을 바꾸거나(팩을 필수로 한다) —")
        print("     둘 중 하나를 사람이 정해야 한다. 눈이 대신 정하지 않는다.")

    print()
    print(f"총평: 요구 {len(want)}키 · 없는 키 {len(missing)} · 미등록 죽은 자산 {len(unregistered)}"
          f" · 끊긴 사슬 {len(chain)} · 게이트 위반 {len(ungated)}"
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

    종료 코드에 기대지 않는다: 팩 게이트 위반(④) 때문에 이 눈은 지금 언제나 1 을 낸다.
    그러므로 **차이**를 본다 — 상처가 **새 지적을 낳는가.** 그것만이 눈이 보고 있다는 증거다.
    (오늘 이 프로젝트에서 눈이 열일곱 번 거짓말했다. 종료 코드만 본 눈이 그 중 여럿이었다.)
    """
    src = ROOT / "resourcepack"
    print("── 눈을 시험한다 (팩 사본에 상처를 내고 눈이 잡는지 본다) ──")
    print("   ※ resourcepack/ 원본은 건드리지 않는다 — 사본에만 상처를 낸다 (팩 담당의 것이다)\n")

    baseline = _findings(src)
    print(f"멀쩡한 팩의 지적 {len(baseline)}건 (전부 ④ 팩 게이트 — 키·사슬은 온전하다):")
    for b in sorted(baseline):
        print("   " + b)
    key_or_chain = [b for b in baseline if "UNGATED" not in b]
    clean_ok = not key_or_chain
    print(f"\n  [{'통과' if clean_ok else '거짓 양성!'}] 멀쩡한 팩에 키·사슬 지적이 없다")
    for b in key_or_chain:
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
        ("텍스처를 지운다 (textures/qi/slash_arc.png)",
         lambda r: (r / f"assets/{NS}/textures/qi/slash_arc.png").unlink(),
         "없는 텍스처"),
        ("모델을 [-16,32] 밖으로 민다 (클라이언트가 모델을 버린다)",
         lambda r: _push_out_of_range(r / f"assets/{NS}/models/item/qi/slash_arc.json"),
         "밖이다"),
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

    total = len(cases) + 1
    got = passed + (1 if clean_ok else 0)
    print(f"\n눈 시험: {got}/{total}" + ("  — 눈이 본다" if got == total else "  — 눈이 멀었다"))
    return 0 if got == total else 1


def _push_out_of_range(model: Path) -> None:
    doc = json.loads(model.read_text("utf-8"))
    doc["elements"][0]["from"][0] = -40.0      # [-16, 32] 밖 — 클라이언트가 모델을 버린다
    model.write_text(json.dumps(doc), encoding="utf-8")


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
