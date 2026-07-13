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
    # ─── 갇힘 ───
    ("① 문을 잠근다 (봇 꺼짐 우회 차단)", CFG,
     "allow_passage: true", "allow_passage: false", "bridge_down.allow_passage"),
    ("② 봇 꺼짐 우회를 코드에서 뜯는다", SRC,
     "if (bridgeDownAllows && plugin.worldDay() <= 0) {",
     "if (bridgeDownAllows && false) {", "우회가 코드에 없다"),
    ("③ 과제가 문을 잠그게 한다 (config)", CFG,
     "\n    gating: false", "\n    gating: true", "과제가 문을 잠근다"),
    ("④ 과제가 문을 잠그게 한다 (코드) — 글판 진척을 문이 본다", SRC,
     "        if (plugin.ledger(player.getUniqueId()).linked()) {\n            depart(player, List.of());\n            return;\n        }\n        if (bridgeDownAllows",
     "        if (currentStation(player) < stations.size() - 1) {\n            return;\n        }\n        if (plugin.ledger(player.getUniqueId()).linked()) {\n            depart(player, List.of());\n            return;\n        }\n        if (bridgeDownAllows",
     "과제가 문을 잠근다"),
    ("⑤ 내릴 자리를 없앤다", CFG,
     "destinations: [흑수나루, 장터]", "destinations: []", "destinations 가 비었다"),
    ("⑥ 세계 스폰 최종 보루를 뜯는다", SRC,
     "return Bukkit.getWorlds().get(0).getSpawnLocation();", "return null;", "내릴 자리가 없다"),
    ("⑦ 월드가 안 열려도 텔레포트한다", SRC,
     "        World w = world();\n        if (w == null) {\n            player.sendMessage(ChatColor.RED + displayName",
     "        World w = world();\n        player.teleport(player.getLocation());\n        if (w == null) {\n            player.sendMessage(ChatColor.RED + displayName",
     "먼저 teleport"),
    ("⑧ 종이 문에 안 이어진다 (손잡이 없는 문)", SRC,
     "            event.setCancelled(true);\n            cross(player);\n            return;",
     "            event.setCancelled(true);\n            return;", "손잡이 없는 문"),
    ("⑨ 물안개가 사람을 죽인다", SRC,
     "        player.teleport(spawnAt(player.getWorld()));\n        player.setFallDistance(0f);\n        if (!mistLine.isEmpty()) {",
     "        player.setHealth(0);\n        if (!mistLine.isEmpty()) {", "물안개가 사람을 죽인다"),

    # ─── ★ 길 (2차 개정) ───
    ("⑩ 우회로를 없앤다 — 점프 못 하는 사람이 갇힌다", CFG,
     "bypass_z: [2, 3]", "bypass_z: []", "우회로가 없다"),
    ("⑪ 잔교를 못 건널 만큼 넓게 끊는다", CFG,
     "{ from: 10, to: 12, bypass_z: [2, 3]", "{ from: 10, to: 20, bypass_z: [2, 3]",
     "못 건넌다"),
    ("⑫ 관문 순서를 뒤엉키게 한다", CFG,
     "    - { id: 태세, x: 2,   half: 4, lesson: 태세 }",
     "    - { id: 태세, x: -25, half: 4, lesson: 태세 }", "오름차순이 아니다"),
    ("⑬ 관문을 길 밖에 둔다 — 아무도 못 간다", CFG,
     "    - { id: 장부, x: 17,  half: 4, lesson: 수련 }",
     "    - { id: 장부, x: 99,  half: 4, lesson: 수련 }", "길 밖에 있다"),
    ("⑭ 눈 뜨는 자리를 물 위에 둔다", CFG,
     "  spawn: [-30, 0]", "  spawn: [-36, 0]", "딛을 수 있는 땅이 아니다"),
    ("⑮ 끊긴 자리를 마당이 도로 메운다 (isDeck 에서 inGap 제거)", SRC,
     "        if (inGap(x, z)) {\n            return false;\n        }\n        return onRoad(x, z)",
     "        return onRoad(x, z)", "구멍을 도로 메운다"),
    ("⑯ 갈림길 — 등록 안 된 마른 땅을 낸다", SRC,
     "    private boolean onHut(int x, int z) {\n        return x >= hut.x1()",
     "    private boolean onHut(int x, int z) {\n        if (z == 8 && x > -30) {\n            return true;\n        }\n        return x >= hut.x1()",
     "갈림길|한 덩어리가 아니다|등록되지 않은"),

    # ─── ★ 흐름 (한 번에 하나만) ───
    ("⑰ 과제가 전부 한꺼번에 보이게 한다", CFG,
     "one_at_a_time: true", "one_at_a_time: false", "한꺼번에 보인다"),
    ("⑱ 앞 관문의 판을 안 감춘다", SRC,
     "        if (visible) {\n            player.showEntity(plugin, d);\n        } else {\n            player.hideEntity(plugin, d);\n        }",
     "        player.showEntity(plugin, d);", "감추지 않는다"),
    ("⑲ 범인이 격 관문에서 막힌다 (그 뒤를 영영 못 본다)", SRC,
     "        if (l.requiresArmable() && !armable(player)) {\n            return true;   // ★ 못 하는 것 때문에 길이 막히지 않는다. 그냥 지나간다\n        }",
     "", "지나가게 하지 않는다"),

    # ─── ★ 빛 ───
    ("⑳ 조명을 균일하게 깐다 (2칸 격자)", CFG,
     "post_every: 9", "post_every: 2", "균일|광원 밀도|등롱이 습지를 도배"),
    ("㉑ 어둠의 하한을 지운다 (등롱 도배를 허용)", CFG,
     "dark_min_pct: 12", "dark_min_pct: 0", "TownAudit 의 12"),
    ("㉒ 주 동선을 어둡게 (등롱을 아주 성기게)", CFG,
     "post_every: 9", "post_every: 40", "주 동선 암흑|균일"),
    ("㉓ 코드가 등롱 간격을 지어낸다", SRC,
     "        if (Math.floorMod(n, light.postEvery()) != 0) {",
     "        if (Math.floorMod(n, 6) != 0) {", "박힌 숫자가 있다"),

    # ─── ★ 발판 ───
    ("㉔ 발판이 화면과 다른 명령을 친다 (★ 화면이 거짓말)", SRC,
     "        player.performCommand(cmd);",
     "        player.performCommand(\"혼천 시트\");", "직접 박혀 있다|다른 변수다"),
    ("㉕ 발판이 없는 명령을 친다", CFG,
     'command: "혼천 수련 외공 2"', 'command: "혼천 수렴 외공 2"', "없는 명령을 친다"),
    ("㉖ 발판을 물 위에 놓는다 (영영 못 밟는다)", CFG,
     "{ id: 시트,      pos: [19, -2]", "{ id: 시트,      pos: [19, -14]", "밟을 수 없다"),
    ("㉗ 발판을 등롱 기둥 속에 놓는다", CFG,
     "{ id: 시트,      pos: [19, -2]", "{ id: 시트,      pos: [24, 2]", "밟을 수 없다"),
    ("㉘ 발판이 config 와 다른 배분을 깐다", CFG,
     'command: "혼천 수련 외공 2"', 'command: "혼천 수련 외공 5"', "다른 빌드를 깐다"),
    ("㉙ 발판을 밟는 게 아니라 누르는 것으로 본다", SRC,
     "        if (event.getAction() != Action.PHYSICAL) {",
     "        if (event.getAction() != Action.LEFT_CLICK_AIR) {", "밟는"),

    # ─── 거짓말 (1차판부터) ───
    ("㉚ 몸짓에서 방패(막기)를 뺀다", CFG,
     "gestures: [isBlocking, isSneaking, isSprinting]",
     "gestures: [isSneaking, isSprinting]", "화면이 세계에 대해 거짓말한다"),
    ("㉛ 경공을 '웅크리고 점프'라 적는다", CFG,
     "§f달리며 점프§7 — 발이 이미 움직일 때만 몸이 뜬다.",
     "§f웅크리고 점프§7 — 낮춘 몸이 뜬다.", "gyeonggong.yml activate"),
    ("㉜ 코드가 경공을 딴 것으로 본다", SRC,
     "if (player.isSprinting() && !player.isOnGround() && player.getFallDistance() <= 0.1f) {",
     "if (!player.isOnGround()) {", "달리며 점프'가 아니다"),
    ("㉝ 범인에게 '격을 둘러라'를 가르친다", CFG,
     "requires_armable_grade: true", "requires_armable_grade: false", "존재하지 않는 조작"),
    ("㉞ 코드가 격의 가부를 지어낸다", SRC,
     "return realm != null && !plugin.skillEngine().armableGrades(realm).isEmpty();",
     "return true;", "armableGrades"),
    ("㉟ 과제 감지기를 뜯는다 (쳐도 안 닫힌다)", SRC,
     'bump(player, "손");', "// bump 제거됨", "표지판일 뿐이다|해도 안 닫힌다"),
    ("㊱ 안내 문장이 없는 명령을 말한다", CFG,
     "§8길이 하나인 것은 네가 아직 고를 것이 없기 때문이다.",
     "§8막히면 §f/혼천 비상탈출§8 을 쳐라.", "/혼천 비상탈출"),

    # ─── 글판 · 규약 ───
    ("㊲ 글이 세계에 남는다 (shutdown 이 안 걷는다)", SRC,
     "            clearPanels(w);\n        }\n        stowed.clear();",
     "        }\n        stowed.clear();", "세계에 남는다"),
    ("㊳ 재조성이 겹친다 (걷기 전에 세운다)", SRC,
     "            clearPanels(w);       // ★ 다시 지으면 글이 두 겹으로 겹치면 안 된다\n            clearDummies(w);\n            spawnPanels(w);",
     "            clearDummies(w);\n            spawnPanels(w);", "두 겹으로 겹친다"),
    ("㊴ 판이 과제와 딴말을 한다", SRC,
     "        return List.of(panelSpec.titlePrefix() + l.title(),\n                unavailableVariant ? l.unavailable() : l.how());",
     "        return List.of(panelSpec.titlePrefix() + s.id(), \"§7알아서 해라\");",
     "딴말을 할 수 있다"),
    ("㊵ 표지판으로 돌아간다 (글자가 작다)", SRC,
     "Material.BELL,", "Material.OAK_SIGN,", "표지판\\(Sign\\)을 세운다"),
    ("㊶ 난수를 넣는다 (결정론 위반)", SRC,
     "if (Math.floorMod(x * 31 + z * 17, marsh.reedHash()) == 0) {",
     "if (Math.random() < 0.1) {", "난수가 있다"),
    ("㊷ 지면을 지어낸다 (y5 낙사 재발)", SRC,
     "return w.getHighestBlockYAt(cx + 512, cz + 512);", "return 5;", "월드에게 묻지 않는다"),
    ("㊸ 조성이 틱을 다 먹는다 (슬라이싱 제거)", SRC,
     'TickBudget.slice(plugin, "입도진"', 'noSlice(plugin, "입도진"', "틱 슬라이싱을 안 탄다"),
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
