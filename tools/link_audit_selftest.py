#!/usr/bin/env python3
"""접합 감사의 **자기 시험** — 눈을 시험하는 눈.

"위반 0건"은 두 가지 뜻이다: **자물쇠가 걸렸다**, 또는 **눈이 멀었다.** 둘은 화면에서 똑같이 보인다.
이 눈은 만들자마자 한 번 거짓말했다 — `link_requests`(새 청 파일)가 `link_request`(죽은 kind)를
부분 문자열로 품는 바람에, 멀쩡한 코드에 ❌ 를 찍었다. 그래서 **일부러 어긴다.**

★ 이 시험의 심장은 ②다: **수락 없이 이어지는 문**을 실제로 뚫어 놓고, 눈이 잡는지 본다.
   (사용자가 명시적으로 요구했다: "일부러 어겨서 시험하라 — 특히 수락 없이 잇는 경로를 만들어 놓고")

끝나면 전부 되돌린다 (소스·config 는 손대지 않은 상태로 남는다).

사용법:  python3 tools/link_audit_selftest.py
종료 코드: 눈이 하나라도 놓치면 1.
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GAME = os.path.join(ROOT, "server-bot/src/main/java/com/honcheon/bot/GameListener.java")
BRIDGE = os.path.join(ROOT, "server-bot/src/main/java/com/honcheon/bot/Bridge.java")
DB = os.path.join(ROOT, "server-bot/src/main/java/com/honcheon/bot/Db.java")
WB = os.path.join(ROOT, "server-mvt/src/main/java/com/honcheon/mvt/WorldBridge.java")
CMD = os.path.join(ROOT, "server-mvt/src/main/java/com/honcheon/mvt/MvtCommand.java")
CFG = os.path.join(ROOT, "config/world_bridge.yml")
AUDIT = os.path.join(ROOT, "tools/link_audit.py")

# (이름, 파일, 원본조각, 바꿀조각, 눈이 뱉어야 하는 말의 조각)
MUTATIONS = [
    # ══ ★★ ② 수락 없이 잇는 문을 **실제로 뚫는다** — 이 시험의 이유 ══
    ("★ 닉네임만으로 곧장 잇는다 (도용의 문을 연다)", GAME,
     "        String token = token();\n        int ttl = rules.linkTtlSeconds();",
     "        db.linkMvt(body.uuid(), body.name(), chId);\n"
     "        String token = token();\n        int ttl = rules.linkTtlSeconds();",
     "askLink 는 아무것도 잇지 않는다"),

    ("★ 청을 앉히자마자 제 손으로 확정한다", GAME,
     "        linkCooldown.put(user.getId(), now);",
     "        linkCooldown.put(user.getId(), now);\n"
     "        completeLink(new Db.LinkRequest(token, body.uuid(), body.name(), chId,\n"
     "                user.getId(), user.getEffectiveName(), now, now + 1000L, \"대기\"),\n"
     "                body.name(), today);",
     "completeLink 를 부르는 자는 다리뿐"),

    # ★ 잇는 손을 **다른 파일**에 판다 — 눈이 GameListener 만 보고 있으면 여기서 뚫린다
    #   (오늘 Reset.java 가 옆에서 생겼다. 내일 또 무엇이 생길지 모른다 — 자물쇠는 행위에 건다)
    ("★ 다리가 제 손으로 잇는다 (수락을 건너뛴다)", BRIDGE,
     "        db.linkMvt(uuid, name, null);   // 몸의 이름만 갱신한다",
     "        db.linkMvt(uuid, name, 1L);   // 몸의 이름만 갱신한다",
     "봇 전체에서 잇는 손은 completeLink 하나뿐"),

    # ══ ③ 몸 대조를 뜯는다 — 남의 토큰으로 남의 몸을 잇게 된다 ══
    ("★ 마크가 몸을 대조하지 않는다 (남의 청을 받는다)", WB,
     "        if (!req.body().equals(player)) {",
     "        if (false) {",
     "청을 받은 그 몸만 수락한다"),

    ("★ 봇이 다리를 그냥 믿는다 (jsonl 한 줄이면 끝)", BRIDGE,
     "        if (!req.mcUuid().equals(uuid)) {",
     "        if (false) {",
     "다리를 믿지 않고 다시 대조한다"),

    # ══ ④ TTL ══
    ("만료 검사를 뺀다 (어제의 청으로 오늘의 몸을)", BRIDGE,
     "        if (!req.pending() || req.expired(at)) {",
     "        if (!req.pending()) {",
     "봇이 만료를 판정한다"),

    ("청의 수명을 하루로 늘린다 (등록부)", CFG,
     "  ttl_seconds: 120  ",
     "  ttl_seconds: 86400  ",
     "청의 수명이 짧다"),

    # ══ ⑤ 1회성 ══
    ("이미 답한 청을 또 태울 수 있게 한다", DB,
     "                        + \"WHERE token = ? AND state = '대기'\")) {",
     "                        + \"WHERE token = ?\")) {",
     "수락은 한 번만"),

    # ══ ⑥ 1:1 (수락 시점의 재검사) ══
    ("수락할 때 재검사를 뺀다 (2분 사이의 도둑)", GAME,
     "        var owner = db.rawCharacterOfMc(req.mcUuid());",
     "        var owner = java.util.Optional.<Long>empty();",
     "수락할 때 **다시** 본다"),

    # ══ ⑦ 연타 ══
    ("쿨다운을 0으로 (남의 화면을 물음으로 덮는다)", CFG,
     "  cooldown_seconds: 60  ",
     "  cooldown_seconds: 0  ",
     "쿨다운이 등록부에 있다"),

    # ══ ⑨ 명부 — 이름 캐기 ══
    ("오프라인인지 없는 이름인지 갈라 말한다", CFG,
     "  reveal_roster: false ",
     "  reveal_roster: true ",
     "없는 이름과 오프라인이 같은 말이다"),

    # ══ ⑩ 초기화 (사용자 지시) ══
    ("발판을 밟아도 낡은 청이 안 죽는다", CMD,
     "        WorldBridge.linkReset(player.getUniqueId(), player.getName());",
     "        // (초기화를 뺐다)",
     "/혼천 접속 이 낡은 청을 죽인다"),

    # ══ ① 옛 코드 길의 부활 ══
    ("옛 코드 발급을 되살린다", WB,
     "    public static int linkTtlSeconds() {",
     "    public static String requestLink(UUID player, String name) {\n"
     "        return \"ABC123\";\n"
     "    }\n\n"
     "    public static int linkTtlSeconds() {",
     "MVT 가 코드를 내지 않는다"),
]


def run_audit():
    r = subprocess.run([sys.executable, AUDIT, "--no-db"], capture_output=True, text=True)
    return r.returncode, r.stdout + r.stderr


def main():
    # 눈이 지금 깨끗한가 (여기서 이미 ❌ 면 시험이 무의미하다)
    code, out = run_audit()
    if code != 0:
        print("시험을 시작할 수 없다 — 눈이 지금 이미 위반을 보고 있다:")
        print(out)
        sys.exit(1)
    print("기준선: 눈이 깨끗하다 ✅\n")

    missed = []
    for name, path, before, after, expect in MUTATIONS:
        original = open(path, encoding="utf-8").read()
        if before not in original:
            print(f"⚠️  {name}\n     ↳ 원본 조각을 못 찾았다 (코드가 바뀌었다 — 시험을 고쳐라):\n"
                  f"        {before[:70]!r}")
            missed.append(name)
            continue
        try:
            open(path, "w", encoding="utf-8").write(original.replace(before, after, 1))
            code, out = run_audit()
            caught = code != 0 and expect in out
            print(f"{'✅' if caught else '❌'} {name}")
            if not caught:
                print(f"     ↳ 눈이 못 잡았다 (기대한 말: {expect!r})")
                missed.append(name)
        finally:
            open(path, "w", encoding="utf-8").write(original)   # 무슨 일이 있어도 되돌린다

    # 되돌린 뒤 다시 깨끗한가 (시험이 소스를 더럽히지 않았는가)
    code, _ = run_audit()
    print(f"\n복원 확인: {'✅ 눈이 다시 깨끗하다' if code == 0 else '❌ 원상복구 실패 — git diff 를 보라'}")
    if code != 0:
        missed.append("복원 실패")

    print()
    if missed:
        print(f"❌ 눈이 {len(missed)}가지를 놓쳤다 — 이 눈은 없느니만 못하다:")
        for m in missed:
            print(f"   · {m}")
        sys.exit(1)
    print(f"✅ 열세 가지를 일부러 어겼고, 눈이 전부 잡았다. **수락 없이 잇는 길은 없다.**")


if __name__ == "__main__":
    main()
