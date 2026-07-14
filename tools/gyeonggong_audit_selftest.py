#!/usr/bin/env python3
"""경공 감사의 **자기 시험** — 눈을 시험하는 눈 (발동 축 ⑧).

"위반 0건"은 두 가지 뜻이다: **경공이 손가락에서 나온다**, 또는 **눈이 멀었다.** 둘은 화면에서 똑같이 보인다.

이 시험이 재는 것은 하나다: **경공이 나에게 일어나는가, 내가 쓰는가.**
그래서 gyeonggong_audit.py 에게 일부러 거짓말을 먹인다 —
자동 발동을 되살리고, 날개를 진짜로 달아 주고, 크리에이티브를 깨뜨리고, 공짜로 날게 하고,
입을 막고, 삼류에게 허공을 준다. 그때마다 눈이 **실제로 잡는지** 본다.
잡으면 ✅, 못 잡으면 ❌. 끝나면 전부 되돌린다 (config·소스는 손대지 않은 상태로 남는다).

사용법:  python3 tools/gyeonggong_audit_selftest.py
종료 코드: 눈이 하나라도 놓치면 1.
"""
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CFG = os.path.join(ROOT, "config/gyeonggong.yml")
SRC = os.path.join(ROOT, "server-mvt/src/main/java/com/honcheon/mvt/GyeonggongListener.java")
AUDIT = os.path.join(ROOT, "tools/gyeonggong_audit.py")

# (이름, 파일, 원본조각, 바꿀조각, 잡아야 하는 말의 조각)
MUTATIONS = [
    # ══ 발동이 손가락에서 나오는가 ══
    ("① 자동 발동을 되살린다 (달리며 점프하면 알아서 켜진다)", SRC,
     "        Ride ride = gg == null ? null : riding.get(player.getUniqueId());\n"
     "        if (ride == null || ride.profile == null || !ride.profile.open()) {\n"
     "            return;   // 안 켜진 몸의 점프는 그냥 점프다 — **여기서 켜지 않는다**\n"
     "        }",
     "        if (gg == null || !player.isSprinting()) {\n"
     "            return;\n"
     "        }\n"
     "        Ride ride = riding.computeIfAbsent(player.getUniqueId(), id -> new Ride());\n"
     "        if (ride.profile == null) {\n"
     "            return;\n"
     "        }",
     "자동으로 켠다"),

    ("② 발동 핸들러를 없앤다 (손가락이 켤 자리를 지운다)", SRC,
     "    public void onAirJump(PlayerToggleFlightEvent event) {",
     "    public void onAirJump(PlayerToggleSneakEvent event) {",
     "핸들러가"),

    ("③ 뒷문을 낸다 (발동 핸들러 밖에서도 경공이 켜진다)", SRC,
     "    private void restore(Player player, Ride ride) {",
     "    private void backdoor(Player player) {\n"
     "        riding.put(player.getUniqueId(), new Ride());\n"
     "    }\n"
     "\n"
     "    private void restore(Player player, Ride ride) {",
     "발동 핸들러 밖에서 경공이 켜진다"),

    # ══ 날게 두지 않고 이벤트만 훔치는가 ══
    ("④ 진짜로 날게 둔다 (이벤트를 취소하지 않는다)", SRC,
     "        event.setCancelled(true);        // ★ 날게 두지 않는다\n"
     "        player.setFlying(false);",
     "        player.setFlying(true);",
     "진짜로 난다"),

    # ══ 크리에이티브 불가침 (연무장이 크리에이티브다) ══
    ("⑤ 크리에이티브의 날개를 뺏는다 (wings 의 가드를 뜯는다)", SRC,
     "    private void wings(Player player, boolean on) {\n"
     "        if (creativeFlight(player)) {\n"
     "            return;\n"
     "        }",
     "    private void wings(Player player, boolean on) {",
     "연무장(Dojang.enter = CREATIVE)의 비행이 깨진다"),

    ("⑥ 크리에이티브의 비행 토글까지 훔친다 (핸들러의 early-return 을 뺀다)", SRC,
     "        if (creativeFlight(player)) {\n"
     "            return;\n"
     "        }\n"
     "        if (!winged.contains(player.getUniqueId())) {",
     "        if (!winged.contains(player.getUniqueId())) {",
     "연무장에서 못 난다"),

    # ══ 값이 드는가 · 말을 하는가 ══
    ("⑦ 공짜로 난다 (내력을 안 태운다)", SRC,
     "        int cost = gg.leapCost();\n"
     "        if (state.energy < cost) {\n"
     "            deplete(gg, player, state);   // ★ 내력이 없으면 안 나간다 — 그리고 그렇게 말한다\n"
     "            return;\n"
     "        }\n"
     "        state.energy -= cost;\n"
     "\n"
     "        boolean fresh = ride == null;",
     "        int cost = 0;\n"
     "\n"
     "        boolean fresh = ride == null;",
     "공짜로 나는 몸이다"),

    ("⑧ 침묵시킨다 (허공을 다 썼는데 아무 말도 안 한다)", SRC,
     '            SkillHud.actionBar(player, ChatColor.DARK_GRAY + gg.message("air_spent"));\n'
     "            return;   // ★ 몇 번 딛는가는 경지가 정했다 (realm_ceiling.air_jumps)",
     "            return;",
     "발동 실패가 침묵한다"),

    # ══ 경지가 가르는가 (등록부 축) ══
    ("⑨ 삼류에게 허공을 준다 (개화 전이 난다)", CFG,
     "  삼류:   { speed_bonus: 0,    jump_bonus: 0,    fall_grace_m: 0,  wall_climb_m: 0,  "
     "water_run: false, air_jumps: 0 }",
     "  삼류:   { speed_bonus: 0,    jump_bonus: 0,    fall_grace_m: 0,  wall_climb_m: 0,  "
     "water_run: false, air_jumps: 1 }",
     "개화 전 경지가 허공을 딛는다"),

    ("⑩ 경지를 거꾸로 매단다 (일류가 생사경보다 많이 딛는다)", CFG,
     "  일류:   { speed_bonus: 0.12, jump_bonus: 0.30, fall_grace_m: 10, wall_climb_m: 0,  "
     "water_run: false, air_jumps: 1 }",
     "  일류:   { speed_bonus: 0.12, jump_bonus: 0.30, fall_grace_m: 10, wall_climb_m: 0,  "
     "water_run: false, air_jumps: 9 }",
     "도약 횟수가 줄어드는 자리가 있다"),

    ("⑪ 등록부가 거짓말한다 (activate_event 를 딴 것으로 적는다)", CFG,
     "  activate_event: PlayerToggleFlightEvent",
     "  activate_event: PlayerJumpEvent",
     "핸들러가"),
]


def run_audit():
    r = subprocess.run([sys.executable, AUDIT, "--lint-only"], capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr


def caught(out, needle):
    """그 말이 **❌ 로** 찍혔는가 — ✅ 로 찍힌 같은 말에 속으면 안 된다."""
    return any(needle in line and "❌" in line for line in out.splitlines())


def main():
    print("═" * 74)
    print("  경공 감사 — 자기 시험 (일부러 자동 발동을 되살려 놓고, 눈이 잡는지 본다)")
    print("═" * 74)

    base_rc, base_out = run_audit()
    if base_rc != 0:
        print("\n❌ 시작부터 위반이 있다 — 먼저 그것부터 고쳐라. 시험을 멈춘다.\n")
        print(base_out)
        return 1
    print("\n  기준선: 위반 0건 (손가락이 켠다) — 이제 하나씩 어겨 본다\n")

    backups = {}
    for path in (CFG, SRC):
        backups[path] = path + ".selftest.bak"
        shutil.copy2(path, backups[path])

    missed = []
    try:
        for name, path, old, new, needle in MUTATIONS:
            with open(path, encoding="utf-8") as fh:
                original = fh.read()
            if old not in original:
                print(f"  ⚠️  {name} — 원본 조각을 못 찾았다 (시험이 낡았다): {old[:48]!r}")
                missed.append(name + " (시험이 낡음)")
                continue
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(original.replace(old, new, 1))
            rc, out = run_audit()
            with open(path, "w", encoding="utf-8") as fh:   # 즉시 되돌린다
                fh.write(original)

            if rc != 0 and caught(out, needle):
                print(f"  ✅ {name} — 눈이 잡았다")
            elif rc != 0:
                print(f"  ⚠️  {name} — 위반은 났는데 **엉뚱한 것을 잡았다** (기대: “{needle}”)")
                missed.append(name + " (엉뚱한 것을 잡음)")
            else:
                print(f"  ❌ {name} — **눈이 못 잡았다** (위반 0건으로 통과시켰다)")
                missed.append(name)
    finally:
        for path, bak in backups.items():
            shutil.copy2(bak, path)
            os.remove(bak)

    rc, _ = run_audit()
    print("\n" + "─" * 74)
    if rc != 0:
        print("  ❌ 되돌리기 실패 — 파일이 원래대로가 아니다. 손으로 확인하라.")
        return 1
    print("  되돌렸다 — config·소스는 손대지 않은 상태다 (재감사: 위반 0건)")
    if missed:
        print(f"  총평: ❌ 눈이 {len(missed)}건을 놓쳤다 — " + " · ".join(missed))
        print("─" * 74)
        return 1
    print(f"  총평: ✅ {len(MUTATIONS)}건 전부 잡았다 — 이 눈은 볼 수 있다")
    print("─" * 74)
    return 0


if __name__ == "__main__":
    sys.exit(main())
