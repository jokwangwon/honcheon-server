#!/usr/bin/env python3
"""입도진 감사의 **자기 시험** — 눈을 시험하는 눈.

이 프로젝트에서 눈이 여섯 번 거짓말했다. 엉뚱한 폴더를 봤고, 정규식이 틀렸고, 크래시했고,
**이름만 보고 속을 안 봤고**, 답을 손으로 써 넣고 있었다. 전부 "위반 0건"으로 보였다.

그래서 이 스크립트는 antechamber_audit.py 에게 **일부러 거짓말을 먹인다**:
문을 잠그고, 튜토리얼을 틀리게 적고, 글판을 겹치고, 난수를 넣고 — 그때마다 눈이 **실제로 잡는지** 본다.
잡으면 ✅, 못 잡으면 ❌. 끝나면 전부 되돌린다 (config·소스는 손대지 않은 상태로 남는다).

★ 이 시험이 antechamber_audit 의 실제 구멍 둘을 잡아냈다:
  · destination() 이 null 을 돌려주는데 `getSpawnLocation` 이 파일 **다른 데** 있다고 통과시켰다
  · build() 가 걷지 않고 세우는데 `clearPanels+spawnPanels` 쌍이 **다른 메서드**에 있다고 통과시켰다
  둘 다 "이름만 보고 속을 안 본" 병이었다. 그래서 눈에 body_of() (중괄호 짝맞춤)가 생겼다.

사용법:  python3 tools/antechamber_audit_selftest.py
종료 코드: 눈이 하나라도 놓치면 1.
"""
import shutil, subprocess, sys, os

ROOT = "/home/delangi/문서/project/category/honcheon-server"
CFG = f"{ROOT}/config/antechamber.yml"
SRC = f"{ROOT}/server-mvt/src/main/java/com/honcheon/mvt/Antechamber.java"
AUDIT = f"{ROOT}/tools/antechamber_audit.py"

# (이름, 파일, 원본조각, 바꿀조각, 잡아야 하는 말)
MUTATIONS = [
    ("① 문을 잠근다 (봇 꺼짐 우회 차단)", CFG,
     "allow_passage: true", "allow_passage: false", "bridge_down.allow_passage"),

    ("② 봇 꺼짐 우회를 코드에서 뜯는다", SRC,
     "if (bridgeDownAllows && plugin.worldDay() <= 0) {",
     "if (bridgeDownAllows && false) {", "우회가 코드에 없다"),

    ("③ 과제가 문을 잠그게 한다", CFG,
     "\n    gating: false", "\n    gating: true", "과제가 문을 잠근다"),

    ("④ 내릴 자리를 없앤다", CFG,
     "destinations: [흑수나루, 장터]", "destinations: []", "destinations 가 비었다"),

    ("⑤ 세계 스폰 최종 보루를 뜯는다", SRC,
     "return Bukkit.getWorlds().get(0).getSpawnLocation();",
     "return null;", "내릴 자리가 없다"),

    ("⑥ 튜토리얼이 거짓말한다 — 몸짓에서 방패(막기)를 뺀다", CFG,
     "gestures: [isBlocking, isSneaking, isSprinting]",
     "gestures: [isSneaking, isSprinting]", "화면이 세계에 대해 거짓말한다"),

    ("⑦ 튜토리얼이 거짓말한다 — 경공을 '웅크리고 점프'라 적는다", CFG,
     "§7§f달리며 점프§7 — 발이 이미 움직일 때만 몸이 뜬다.",
     "§7§f웅크리고 점프§7 — 낮춘 몸이 뜬다.", "gyeonggong.yml activate"),

    ("⑧ 코드가 경공을 딴 것으로 본다 (달림 조건 제거)", SRC,
     "if (player.isSprinting() && !player.isOnGround() && player.getFallDistance() <= 0.1f) {",
     "if (!player.isOnGround()) {", "달리며 점프'가 아니다"),

    ("⑨ 없는 명령을 가르친다 (수련 → 수렴)", CFG,
     'command: "수련"', 'command: "수렴"', "마크에 없는 명령을 가르친다"),

    ("⑩ 안내 문장이 없는 명령을 말한다", CFG,
     "§8부두 끝의 §f종§8 을 울리면 사공을 부른다.",
     "§8막히면 §f/혼천 비상탈출§8 을 쳐라.", "/혼천 비상탈출"),

    ("⑪ 범인에게 '격을 둘러라'를 가르친다", CFG,
     "requires_armable_grade: true", "requires_armable_grade: false",
     "존재하지 않는 조작"),

    ("⑫ 코드가 격의 가부를 지어낸다 (armableGrades 안 봄)", SRC,
     "return realm != null && !plugin.skillEngine().armableGrades(realm).isEmpty();",
     "return true;", "armableGrades"),

    ("⑬ 과제 감지기를 뜯는다 (허수아비를 쳐도 안 닫힌다)", SRC,
     'bump(player, "손");', "// bump 제거됨", "표지판일 뿐이다|해도 안 닫힌다"),

    ("⑭ 글판이 겹친다 (같은 자리 두 판)", CFG,
     "      only_if: not_armable       # 같은 자리 · 다른 사람 — 둘이 겹쳐 보이는 일은 없다",
     "      # only_if 제거됨", "글판이 같은 자리"),

    ("⑮ 글이 세계에 남는다 (shutdown 이 안 걷는다)", SRC,
     "            clearPanels(w);\n        }\n        stowed.clear();",
     "        }\n        stowed.clear();", "세계에 남는다"),

    ("⑯ 재조성이 겹친다 (걷기 전에 세운다)", SRC,
     "            clearPanels(w);       // ★ 다시 지으면 글이 두 겹으로 겹치면 안 된다\n            clearDummies(w);\n            spawnPanels(w);",
     "            clearDummies(w);\n            spawnPanels(w);",
     "두 겹으로 겹친다"),

    ("⑰ 표지판으로 돌아간다 (글자가 작다)", SRC,
     "Material.BELL));",
     "Material.BELL));\n        out.add(new Place(cx, gy + 1, cz, Material.OAK_SIGN));",
     "표지판\\(Sign\\)을 세운다"),

    ("⑱ 난수를 넣는다 (결정론 위반)", SRC,
     "boolean path = Math.floorMod(x + z, 7) == 0;",
     "boolean path = Math.random() < 0.2;", "난수가 있다"),

    ("⑲ 지면을 지어낸다 (y5 낙사 재발)", SRC,
     "return w.getHighestBlockYAt(cx + 512, cz + 512);", "return 5;",
     "월드에게 묻지 않는다"),

    ("⑳ 판이 과제와 딴말을 한다 (from_lesson 에 lines 를 덧붙인다)", CFG,
     "    - id: 손\n      pos: [-8, -8]\n      from_lesson: 손",
     "    - id: 손\n      pos: [-8, -8]\n      from_lesson: 손\n      lines: [\"§7아무렇게나\"]",
     "둘 다 가진다"),

    ("㉑ 조성이 틱을 다 먹는다 (슬라이싱 제거)", SRC,
     "TickBudget.slice(plugin, \"입도진\"", "noSlice(plugin, \"입도진\"",
     "틱 슬라이싱을 안 탄다"),

    ("㉒ 월드가 안 열려도 텔레포트한다 (없는 월드로 보낸다)", SRC,
     "        World w = world();\n        if (w == null) {\n            player.sendMessage(ChatColor.RED + displayName",
     "        World w = world();\n        player.teleport(player.getLocation());\n        if (w == null) {\n            player.sendMessage(ChatColor.RED + displayName",
     "먼저 teleport"),

    ("㉓ 종이 문에 안 이어진다 (손잡이 없는 문)", SRC,
     "        event.setCancelled(true);\n        cross(event.getPlayer());",
     "        event.setCancelled(true);", "손잡이 없는 문"),

    ("㉔ 물안개가 사람을 죽인다", SRC,
     "        player.teleport(spawnAt(player.getWorld()));\n        player.setFallDistance(0f);\n        if (!mistLine.isEmpty()) {",
     "        player.setHealth(0);\n        if (!mistLine.isEmpty()) {", "물안개가 사람을 죽인다"),
]


def run_audit():
    p = subprocess.run([sys.executable, AUDIT], capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr


def main():
    import re
    shutil.copy(CFG, CFG + ".bak")
    shutil.copy(SRC, SRC + ".bak")

    rc, out = run_audit()
    base_v = re.search(r"위반 (\d+)건", out)
    print(f"기준선: 종료코드 {rc} · {base_v.group(0) if base_v else '?'}")
    if rc != 0:
        print("!! 기준선이 이미 실패다. 시험이 무의미하다.")
        return 1
    print()

    caught = missed = 0
    for name, path, old, new, expect in MUTATIONS:
        src = open(path, encoding="utf-8").read()
        if old not in src:
            print(f"  ⁉️  {name}\n      └ 원본 조각을 못 찾았다 (시험 자체가 고장났다): {old[:60]!r}")
            missed += 1
            continue
        open(path, "w", encoding="utf-8").write(src.replace(old, new, 1))
        rc, out = run_audit()
        hit = rc != 0 and re.search(expect, out)
        if hit:
            print(f"  ✅  {name}\n      └ 눈이 잡았다 (종료 {rc})")
            caught += 1
        else:
            print(f"  ❌  {name}\n      └ 눈이 못 잡았다! (종료 {rc}, '{expect}' 없음)")
            missed += 1
        shutil.copy(CFG + ".bak", CFG)
        shutil.copy(SRC + ".bak", SRC)

    os.remove(CFG + ".bak")
    os.remove(SRC + ".bak")

    rc, out = run_audit()
    print(f"\n되돌린 뒤: 종료코드 {rc}")
    print(f"\n═══ 눈의 시험: 잡음 {caught} · 놓침 {missed} / {len(MUTATIONS)} ═══")
    return 0 if missed == 0 and rc == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
