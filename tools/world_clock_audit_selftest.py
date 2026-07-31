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
BRIDGE = BOT / "Bridge.java"
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
     "requires_beat: murimmaeng_changseol.maeng_changseol",
     "requires_beat: murimmaeng_changseol.eopneun_bak",
     "직전 막(murimmaeng_changseol)의 실존 박이 아니다"),

    ("막 order 를 찢는다 (0~4 연속이 아니게)", YML,
     "    order: 2",
     "    order: 7",
     "연속이 아니다"),

    ("human gate 를 미등록 값으로 바꾼다", YML,
     "      gate: human                          # ★세계의 구조가 바뀐다",
     "      gate: maybe                               # ★ 자동 진입 금지",
     "등록되지 않은 gate"),

    ("등록되지 않은 do 유형을 심는다", YML,
     "        do: { world_event: 맹주선출 }",
     "        do: { boss_spawn: 방어전 }",
     "등록되지 않은 do 유형"),

    # ══ ② 이웃 등록부 — 이름을 지어낸다 ══
    # ★2026-07-28 재표적: 앵커 문안이 「사도천의_자칭」 → 「패도천의_자칭」으로 갈렸다
    #   (사도천 → 패도천 개명). 도구가 「시험 자체가 낡았다」고 스스로 경고해서 살았다 —
    #   그 경고가 없으면 **낡은 시험이 조용히 통과하는 눈**이 된다.
    ("소문 망을 유령 망으로 바꾼다", YML,
     "do: { rumor: { 강도: 3, 태그: [사파, 정치], 망: inn_net, 문안키: 패도천의_자칭 } }",
     "do: { rumor: { 강도: 2, 태그: [괴사, 정치], 망: ghost_net, 문안키: 마교_부활_조짐 } }",
     "rumor.yml 에 없는 망"),

    ("명분 대상을 유령 세력으로 바꾼다", YML,
     'myeongbun: { issue: "마교_침략:magyo", target: magyo, tags: [존망, 무림침해], gauge: 13 }',
     'myeongbun: { issue: "마교_침략:magyo", target: sansinryeong, tags: [존망, 무림침해], gauge: 13 }',
     "roster 에 없다"),

    ("지역 델타에 유령 눈금을 심는다", YML,
     "region_delta: { region: cheongha_hyeon, 민심: -2 }",
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
    # ══ ★ ⑤ 엔딩 분기 (2026-07-26 신설) ══
    # ★핵심: fallback 을 지우면 '아무 엔딩도 없는 세계'가 가능해진다
    ("★ fallback 엔딩에 조건을 붙인다 (엔딩 없는 세계가 가능해진다)", YML,
     '      when: {}                                   # ★fallback',
     '      when: { 마교_승패: 격퇴 }                    # ★fallback',
     "fallback)이 정확히 하나가 아니다"),

    # ★fallback 을 맨 앞으로 올리면 조건부 엔딩이 영영 안 뽑힌다
    ("★ fallback 의 우선순위를 1로 올린다 (앞의 엔딩이 전부 사문화)", YML,
     "    - id: hyeolgyo_amyak\n      priority: 3",
     "    - id: hyeolgyo_amyak\n      priority: 1",
     "priority 가 1부터 연속이 아니다"),

    # ★판정 시점이 실존하지 않는 박을 가리킨다
    #   ★2026-07-28 재표적: 종결박 jeonmyeonjeon(혈교_전면전) → bongin(혈교_봉인)
    ("★ 엔딩 판정 시점을 없는 박으로 돌린다", YML,
     "    decided_at: hyeolgyo_siltche.bongin",
     "    decided_at: hyeolgyo_siltche.___없는박___",
     "마지막 막"),

    # ★개인 엔딩의 scope 를 지우면 세계 엔딩과 배타로 오해된다
    #   ★2026-07-28 재표적: D 가 셋(은거·혈화·선경)으로 갈리며 절이 재작성됐다.
    #     ★앵커를 **id 와 붙여** 잡는다 — scope 줄만 잡으면 셋 중 어느 것인지 모호해진다.
    ("★ 개인 엔딩의 scope 를 world 로 바꾼다 (동시 성립이 깨진다)", YML,
     "    - id: eungeo\n      name: \"D-1. 은거(隱居)\"\n      scope: personal",
     "    - id: eungeo\n      name: \"D-1. 은거(隱居)\"\n      scope: world",
     "scope 가 'personal' 이 아니다"),

    # ★엔딩의 소문 망을 없는 것으로
    ("★ 엔딩 소문의 망을 없는 것으로 바꾼다", YML,
     "        rumor: { 강도: 5, 태그: [정치, 무인], 망: orthodox_net, 문안키: 봉인의_날 }",
     "        rumor: { 강도: 5, 태그: [정치, 무인], 망: ___없는망___, 문안키: 봉인의_날 }",
     "rumor.yml 에 없는 망"),

    # ★endings 절 자체를 지운다 — 정본이 4분기를 말하는데 등록부가 침묵하면 위반
    ("★ endings 절을 통째로 지운다 (정본은 4분기를 말한다)", YML,
     "endings:\n  meta:",
     "endings_DISABLED:\n  meta:",
     "endings 절이 없다"),

    # ══ ⑥ 해소 그릇 (B-190 · 2026-07-31 신설) — 판정 입력이 태어나는 자리를 지킨다 ══
    #   ★앵커는 id·박key 와 붙여 잡는다 (재표적 계율 — 흔한 줄만 잡으면 모호해진다)

    # ★해소 유형을 유령으로 — 엔진이 시계를 잠그는 값이니 눈이 먼저 잡아야 한다
    ("★⑥ 해소 유형을 유령으로 바꾼다 (맹주 자리_판독)", YML,
     "resolution: { key: 맹주_진영, kind: 자리_판독, 자리: 맹주, npc_default: namgung }",
     "resolution: { key: 맹주_진영, kind: 유령_판독, 자리: 맹주, npc_default: namgung }",
     "등록되지 않은 해소 유형"),

    # ★판정 입력이 아무도 안 채우는 그릇을 읽게 한다 — 이 눈의 핵심 (등록부의 거짓말)
    ("★⑥ 연대_폭 state_from 을 해소 없는 박으로 돌린다", YML,
     "state_from: \"막해소:paedocheon_daeripp.bonjin_ui_gil\"",
     "state_from: \"막해소:paedocheon_daeripp.wiseon_ui_myeongbun\"",
     "해소를 낳는 박이 아니다"),

    # ★다리 kind 를 미등록으로 — 등록제: 없는 kind 는 세계에 존재하지 않는다
    ("★⑥ 다리_보고의 bridge_kind 를 미등록으로 바꾼다", YML,
     "kind: 다리_보고, bridge_kind: raid_resolved,",
     "kind: 다리_보고, bridge_kind: raid_done,",
     "world_bridge.yml events 에 없다"),

    # ★처리기 절단 — 등재만 되고 아무도 안 받는 보고
    ("★⑥ Bridge 에서 raid_resolved 처리기를 뗀다", BRIDGE,
     "                case \"raid_resolved\" -> raidResolved(data, today);",
     "                // selftest — 처리기 절단",
     "처리기(case)가 Bridge 에 없다"),

    # ★임계값을 (0,1] 밖으로
    ("★⑥ 노선 집계 threshold 를 2.0 으로 올린다", YML,
     "threshold: 0.5, 넓다_값: 넓다",
     "threshold: 2.0, 넓다_값: 넓다",
     "threshold 가 (0,1] 실수가 아니다"),

    # ★배선 선언 거짓말 — 엔진이 읽는데 미배선이라 적으면 표식이 낡은 것
    ("★⑥ wiring_status 를 미배선으로 되돌린다 (엔진은 읽는데)", YML,
     "  wiring_status: 배선",
     "  wiring_status: 미배선",
     "표식을 걷어라"),

    # ★노선을 적는 손 절단 — 아무도 안 적는 장부를 집계하는 판정
    ("★⑥ GameListener 의 개인_노선 기록을 뗀다", LISTENER,
     "db.logEvent(\"개인_노선\", \"character\", String.valueOf(chId), \"노선\", q.noseon(),",
     "db.logEvent(\"개인_노선_절단\", \"character\", String.valueOf(chId), \"노선\", q.noseon(),",
     "적는 손이 GameListener 에 없다"),

    # ★산술 폴백을 지운다 — 보고 없는 판의 엔딩이 공중에 뜬다
    ("★⑥ endings.산술 의 침공_규모를 지운다", YML,
     "    침공_규모: { from: \"계보:붙듦수\", 기본값: 3 }",
     "    침공_규모_절단: { from: \"계보:붙듦수\", 기본값: 3 }",
     "endings.산술 이 비었다"),

    # ══ ⑥-c 천마 루트 (B-190 ① · 2026-07-31) — 선언만 하고 안 만든 손을 잡는가 ══

    # ★자정의 접촉 호출을 뗀다 — 선언(플레이어_루트_기계)은 남는데 손이 사라진다
    ("★⑥ 자정의 그릇 접촉 호출을 뗀다", LISTENER,
     "        cheonmaContact(day);\n        cheonmaAppoint(day);",
     "        ;   // selftest — 접촉 절단\n        cheonmaAppoint(day);",
     "접촉의 손"),

    # ★시험 보고의 처리기를 뗀다 — 등재만 되고 아무도 안 받는 통과
    ("★⑥ Bridge 에서 trial_passed 처리기를 뗀다", BRIDGE,
     "                case \"trial_passed\" -> trialPassed(data, today);",
     "                // selftest — 시험 처리기 절단",
     "시험 보고의 처리기"),

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
