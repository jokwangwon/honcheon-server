#!/usr/bin/env python3
"""세계 시계 감사의 **자기 시험** — 눈을 시험하는 눈 (헌법 2.3).

"위반 0건"은 두 가지 뜻이다: **시계가 정직하다**, 또는 **눈이 멀었다.** 둘은 화면에서 똑같다.
그래서 **일부러 어긴다**: 등록부와 배선을 하나씩 실제로 뜯어 놓고, 눈이 그때마다 소리를
내는지 본다. 끝나면 전부 되돌린다 (소스·config 는 손대지 않은 상태로 남는다).

★ 이 시험의 심장은 두 자리다:
  - 표식 거짓말 (unwired 를 되붙인다) — 파일이 제 처지를 속이는 것이 이 저장소의 근본 병이다
  - 자정 편입 절단 (advanceWorld 에서 tick 을 뗀다) — 시계가 서 있는데 아무도 태엽을 안 감는 상태

사용법:  python3 tools/world_clock_audit_selftest.py
종료 코드: 눈이 하나라도 놓치면 1.
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BOT = ROOT / "server-bot/src/main/java/com/honcheon/bot"
ENGINE = BOT / "WorldClockEngine.java"
LISTENER = BOT / "GameListener.java"
BOOT = BOT / "HoncheonBot.java"
YML = ROOT / "config/world_clock.yml"
AUDIT = ROOT / "tools/world_clock_audit.py"

# (이름, 파일, 원본조각, 바꿀조각, 눈이 뱉어야 하는 말의 조각)
MUTATIONS = [
    # ══ ★ ④ 표식 거짓말 — 배선이 섰는데 unwired 를 되붙인다 ══
    ("★ unwired 표식을 되붙인다 (파일이 제 처지를 속인다)", YML,
     "meta:\n  canon: docs/story_summary.md",
     "unwired:\n  reason: selftest — 일부러 붙인 거짓 표식\n\n"
     "meta:\n  canon: docs/story_summary.md",
     "파일이 거짓말한다"),

    # ══ ★ ③ 자정 편입 절단 — 시계는 있는데 태엽을 감는 자가 없다 ══
    ("★ advanceWorld 에서 tick 을 뗀다", LISTENER,
     "        String clockReport = worldClock.tick(day);",
     "        String clockReport = \"\";   // selftest — 편입 절단",
     "worldClock.tick( 호출이 없다"),

    # ══ ① 막 사슬 — requires_beat 이 유령 박을 가리킨다 ══
    ("requires_beat 을 유령 박으로 바꾼다", YML,
     "requires_beat: magyo_amryu.ilcha_chimryak_gyeoktoe",
     "requires_beat: magyo_amryu.eopneun_bak",
     "직전 막(magyo_amryu)의 실존 박이 아니다"),

    ("막 order 를 찢는다 (0~4 연속이 아니게)", YML,
     "    order: 2",
     "    order: 7",
     "연속이 아니다"),

    ("human gate 를 미등록 값으로 바꾼다", YML,
     "      gate: human                               # ★ 자동 진입 금지",
     "      gate: maybe                               # ★ 자동 진입 금지",
     "등록되지 않은 gate"),

    ("등록되지 않은 do 유형을 심는다", YML,
     "        do: { world_event: 방어전 }",
     "        do: { boss_spawn: 방어전 }",
     "등록되지 않은 do 유형"),

    # ══ ② 이웃 등록부 — 이름을 지어낸다 ══
    ("소문 망을 유령 망으로 바꾼다", YML,
     "do: { rumor: { 강도: 2, 태그: [괴사, 정치], 망: inn_net, 문안키: 마교_부활_조짐 } }",
     "do: { rumor: { 강도: 2, 태그: [괴사, 정치], 망: ghost_net, 문안키: 마교_부활_조짐 } }",
     "rumor.yml 에 없는 망"),

    ("명분 대상을 유령 세력으로 바꾼다", YML,
     'myeongbun: { issue: "마교_침략:magyo", target: magyo, tags: [존망, 무림침해], gauge: 13 }',
     'myeongbun: { issue: "마교_침략:magyo", target: sansinryeong, tags: [존망, 무림침해], gauge: 13 }',
     "roster 에 없다"),

    ("지역 델타에 유령 눈금을 심는다", YML,
     "region_delta: { region: cheongha_hyeon, 민심: -3 }",
     "region_delta: { region: cheongha_hyeon, 사기: -3 }",
     "없는 눈금"),

    # ══ ③ 명령·권한 — human gate 의 손이 사라지거나 헐거워진다 ══
    ("/막개전 등록을 뗀다 (HoncheonBot)", BOOT,
     'var actGate = Commands.slash("막개전",',
     'var actGate = Commands.slash("막개전봉인",',
     "/막개전 등록이 없다"),

    ("addCommands 에서 막개전을 뺀다 (유령 명령)", BOOT,
     "            guild.updateCommands().addCommands(honcheon, gate, wipe, panel, actGate).queue();",
     "            guild.updateCommands().addCommands(honcheon, gate, wipe, panel).queue();",
     "addCommands 에 안 실렸다"),

    ("승인의 권한 검사를 뗀다 (아무나 최종장을 연다)", LISTENER,
     "    private void approveActGate(SlashCommandInteractionEvent event) throws Exception {\n"
     "        if (event.getMember() == null || !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {\n"
     "            event.reply(\"서버 관리 권한이 필요하다.\").setEphemeral(true).queue();\n"
     "            return;\n"
     "        }",
     "    private void approveActGate(SlashCommandInteractionEvent event) throws Exception {",
     "권한을 검사하지 않는다"),

    # ══ ③ 등록부 수치 — 코드가 등록부 대신 제 머리를 믿게 한다 ══
    ("엔진이 tempo_clamp 를 안 읽게 한다", ENGINE,
     'this.tempoClamp = RulesConfig.intValue(clock.get("tempo_clamp"));',
     "this.tempoClamp = 20;   // selftest — 등록부 대신 박은 수",
     "tempo_clamp"),

    ("원장 타입 막관문을 코드에서 지운다", ENGINE,
     'static final String EVENT_GATE = "막관문";',
     'static final String EVENT_GATE = "막문관";',
     "'막관문'"),
]


def run_audit() -> tuple[int, str]:
    p = subprocess.run([sys.executable, str(AUDIT)], capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr


def main() -> int:
    # 시험 전제: 지금 상태는 깨끗해야 한다 (더러운 밭에서는 어떤 씨앗이 자랐는지 알 수 없다)
    code, out = run_audit()
    if code != 0:
        print("⚠️ 시험 불가 — 현재 상태부터 위반이 있다. 먼저 그것을 고쳐라:\n" + out)
        return 1
    print("기준선: ✅ 위반 0건 — 이제 하나씩 일부러 어긴다\n")

    missed = 0
    for name, path, before, after, must_say in MUTATIONS:
        original = path.read_text(encoding="utf-8")
        if before not in original:
            print(f"  ⚠️ 시험 자체가 낡았다 — 원본 조각을 못 찾음: {name}")
            print(f"     (파일: {path.name} / 찾던 조각: {before[:60]!r}...)")
            missed += 1
            continue
        if original.count(before) != 1:
            print(f"  ⚠️ 시험 자체가 위험하다 — 원본 조각이 유일하지 않음: {name}")
            missed += 1
            continue
        try:
            path.write_text(original.replace(before, after, 1), encoding="utf-8")
            code, out = run_audit()
            caught = code != 0 and must_say in out
            if caught:
                print(f"  ✅ 잡았다: {name}")
            else:
                missed += 1
                why = "종료 코드가 0" if code == 0 else f"말이 다르다 (기대한 조각: {must_say!r})"
                print(f"  ❌ 놓쳤다: {name} — {why}")
        finally:
            path.write_text(original, encoding="utf-8")

    # 되돌림 검산 — 시험이 밭을 더럽히고 갔는지
    code, out = run_audit()
    if code != 0:
        print("\n❌ 시험이 밭을 더럽혔다 — 되돌림 실패. git diff 를 보라:\n" + out)
        return 1

    print(f"\n{'✅ 눈이 전부 잡았다' if missed == 0 else f'❌ {missed}건을 놓쳤다'} "
          f"(변이 {len(MUTATIONS)}건 · 전부 되돌림)")
    return 1 if missed else 0


if __name__ == "__main__":
    sys.exit(main())
