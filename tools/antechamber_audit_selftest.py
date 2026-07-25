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
SBK = f"{ROOT}/server-mvt/src/main/java/com/honcheon/mvt/SeojangBook.java"
VOY = f"{ROOT}/server-mvt/src/main/java/com/honcheon/mvt/Voyage.java"
SJS = f"{ROOT}/server-mvt/src/main/java/com/honcheon/mvt/SeojangStage.java"
STG = f"{ROOT}/config/seojang_stage.yml"
GLB = f"{ROOT}/server-bot/src/main/java/com/honcheon/bot/GameListener.java"
BSJ = f"{ROOT}/server-bot/src/main/java/com/honcheon/bot/Seojang.java"
SJY = f"{ROOT}/config/seojang.yml"
TUT = f"{ROOT}/server-mvt/src/main/java/com/honcheon/mvt/TutorialGuide.java"   # ★5차 — 침묵 게이트
MVT = f"{ROOT}/server-mvt/src/main/java/com/honcheon/mvt/HoncheonMvt.java"     # ★5차 — 사이드바 게이트
AUDIT = f"{ROOT}/tools/antechamber_audit.py"

# (이름, 파일, 원본조각, 바꿀조각, 잡아야 하는 말)
MUTATIONS = [
    # ─── 갇힘 ───
    ("① 문을 잠근다 (봇 꺼짐 우회 차단)", CFG,
     "allow_passage: true", "allow_passage: false", "bridge_down.allow_passage"),
    ("② 봇 꺼짐 우회를 코드에서 뜯는다", SRC,
     "if (bridgeDownAllows && plugin.worldDay() <= 0) {",
     "if (bridgeDownAllows && false) {", "우회가 코드에 없다"),
    # (③ 재표적 2026-07-24 ★5차 — lessons 절 폐지: 옛 표적 gating 키가 소멸. 절의 부활 자체가 위반이다)
    ("③ 과제 절을 되살린다 (lessons 부활 — 나루가 도로 시험장이 된다)", CFG,
     "\n  kit:", "\n  lessons:\n    gating: true\n\n  kit:", "lessons 절이 남아|과제는 폐지"),
    ("④ 과제가 문을 잠그게 한다 (코드) — 글판 진척을 문이 본다", SRC,
     # (B-118 뒤의 실제 꼴 — linked 블록 끝의 depart 앞에 과제 잠금을 심는다)
     "            depart(player, List.of());\n            return;\n        }\n        if (bridgeDownAllows",
     "            if (currentStation(player) < stations.size() - 1) {\n                return;\n            }\n            depart(player, List.of());\n            return;\n        }\n        if (bridgeDownAllows",
     "과제가 문을 잠근다"),
    ("⑤ 내릴 자리를 없앤다", CFG,
     "destinations: [흑수나루, 장터]", "destinations: []", "destinations 가 비었다"),
    ("⑥ 세계 스폰 최종 보루를 뜯는다", SRC,
     "        Location last = Bukkit.getWorlds().get(0).getSpawnLocation();",
     "        Location last = null;\n        if (true) {\n            return null;\n        }",
     "내릴 자리가 없다|null 을 돌려줄 수 있다"),
    # ★ 조각은 **enter() 의 것**이어야 한다 — rebuild() 도 같은 모양(World w = world(); if (w == null))
    #   으로 시작하고 파일에서 먼저 나온다. 첫 일치를 바꾸면 엉뚱한 메서드를 흔들고, 눈은 멀쩡한데
    #   시험이 "못 잡았다"고 거짓 보고한다 (자기 시험도 거짓말할 수 있다 — 실제로 한 번 했다).
    ("⑦ 월드가 안 열려도 텔레포트한다", SRC,
     '        World w = world();\n        if (w == null) {\n            player.sendMessage(ChatColor.RED + displayName + "을(를) 열 수 없다 — 강호로 바로 간다.");',
     '        World w = world();\n        player.teleport(player.getLocation());\n        if (w == null) {\n            player.sendMessage(ChatColor.RED + displayName + "을(를) 열 수 없다 — 강호로 바로 간다.");',
     "먼저 teleport"),
    ("⑧ 종이 문에 안 이어진다 (손잡이 없는 문)", SRC,
     "            event.setCancelled(true);\n            cross(player);\n            return;",
     "            event.setCancelled(true);\n            return;", "손잡이 없는 문"),
    ("⑨ 물안개가 사람을 죽인다", SRC,
     "        player.teleport(spawnAt(player.getWorld()));\n        player.setFallDistance(0f);\n        if (!mistLine.isEmpty()) {",
     "        player.setHealth(0);\n        if (!mistLine.isEmpty()) {", "물안개가 사람을 죽인다"),

    # ─── ★ 길 (2차 개정 · ★3차 개정 2026-07-24: 순수 문지방 — gaps·중간 관문이 사라져
    #      옛 뮤테이션 ⑩⑪(우회로)·⑫⑬(태세/장부 관문)은 표적이 소멸했다. 남은 두 관문으로 재표적) ───
    # (⑫⑬ 조각 갱신 ★5차 — 나루 관문이 블록 꼴(panel 안내판)로 바뀌어 x 줄만 겨눈다)
    ("⑫ 관문 순서를 뒤엉키게 한다 (나루를 맞이 앞에)", CFG,
     "      x: 24                       # 부두 · 종 · 사공의 집",
     "      x: -40                      # 부두 · 종 · 사공의 집", "오름차순이 아니다"),
    ("⑬ 관문을 길 밖에 둔다 — 아무도 못 간다", CFG,
     "      x: 24                       # 부두 · 종 · 사공의 집",
     "      x: 99                       # 부두 · 종 · 사공의 집", "길 밖에 있다"),
    ("⑭ 눈 뜨는 자리를 물 위에 둔다", CFG,
     "  spawn: [-6, 0]", "  spawn: [-20, 0]", "딛을 수 있는 땅이 아니다"),
    ("⑮ 끊긴 자리를 마당이 도로 메운다 (isDeck 에서 inGap 제거)", SRC,
     "        if (inGap(x, z)) {\n            return false;\n        }\n        return onRoad(x, z)",
     "        return onRoad(x, z)", "구멍을 도로 메운다"),
    ("⑯ 갈림길 — 등록 안 된 마른 땅을 낸다", SRC,
     "    private boolean onHut(int x, int z) {\n        return x >= hut.x1()",
     "    private boolean onHut(int x, int z) {\n        if (z == 8 && x > -30) {\n            return true;\n        }\n        return x >= hut.x1()",
     "갈림길|한 덩어리가 아니다|등록되지 않은"),

    # ─── ★ 흐름 — ★5차 개정 (과제 폐지): 옛 ⑰(one_at_a_time)·⑱(판 가림)·⑲(범인 통과)는
    #     순차 공개·예고 기계와 함께 표적이 소멸해 폐기. 새 표적 = **잔재의 부활** (audit_flow ①) ───
    ("⑰ 과제 기계를 되살린다 (bump 잔재 주입)", SRC,
     "        player.performCommand(cmd);",
     "        player.performCommand(cmd);\n        bump(player, \"접속\");", "잔재"),

    # ─── ★ 빛 ───
    ("⑳ 조명을 균일하게 깐다 (2칸 격자)", CFG,
     "post_every: 9", "post_every: 2", "균일|광원 밀도|등롱이 습지를 도배"),
    ("㉑ 어둠의 하한을 지운다 (등롱 도배를 허용)", CFG,
     "dark_min_pct: 12", "dark_min_pct: 0", "TownAudit 의 12"),
    # (㉒ 재표적 2026-07-24 명계 개정 — 옛 표적 「post_every 9→40」은 표적을 잃었다:
    #  이승 구간이 부두로 줄어 화톳불+집 등롱만으로 계약(암흑 ≤15%)이 유지된다. 그 어둠의
    #  눈 자체는 산다 — 부두의 화톳불을 꺼서 이승 구간이 어두워지는 것을 잡는지 본다)
    ("㉒ 부두의 화톳불을 끈다 (이승 구간 주 동선이 어두워진다)", CFG,
     "brazier_stations: [나루]", "brazier_stations: []", "주 동선 암흑"),
    # (㉓ 재표적 2026-07-24 명계 개정 — lampSide 가 두 리듬(every)을 고르므로 표적 조각이 바뀌었다)
    ("㉓ 코드가 등롱 간격을 지어낸다", SRC,
     "        int every = x < soulBoundary() ? soulEvery : light.postEvery();",
     "        int every = 6;", "간격을 지어낸다|박힌 숫자가 있다"),

    # ─── ★ 발판 ───
    ("㉔ 발판이 화면과 다른 명령을 친다 (★ 화면이 거짓말)", SRC,
     "        player.performCommand(cmd);",
     "        player.performCommand(\"혼천 시트\");", "직접 박혀 있다|다른 변수다"),
    # (3차 개정 — 수련·시트 발판 소멸: ㉕~㉘은 남은 접속 발판으로 재표적. 배분 대조(㉘)는
    #  표적 자체가 사라져 폐기 — 배분은 이제 본토 뿌리내림·디스코드 시트의 것이다)
    ("㉕ 발판이 없는 명령을 친다 (3차 개정 재표적)", CFG,
     'command: "혼천 접속" }', 'command: "혼천 접솟" }', "없는 명령"),
    ("㉖ 발판을 물 위에 놓는다 (영영 못 밟는다 — 3차 개정 재표적)", CFG,
     "{ id: 접속,      pos: [23,  0]", "{ id: 접속,      pos: [23, -14]", "밟을 수 없다"),
    ("㉙ 발판을 밟는 게 아니라 누르는 것으로 본다", SRC,
     "        if (event.getAction() != Action.PHYSICAL) {",
     "        if (event.getAction() != Action.LEFT_CLICK_AIR) {", "밟는"),

    # ─── 거짓말 (1차판부터 · ★3차 개정: 손·격·태세·경공 과제 소멸 — 그 문장·requires 를 겨누던
    #      ㉚~㉞·(55)~(61)류는 표적이 사라져 폐기/재표적. 감지기의 눈(㉟)은 접속 과제로 재표적) ───
    # (㉚ 재표적 ★5차 — 과제 목록 소멸: 침묵의 눈은 산다 — 안내판이 비면 문지방이 말을 잃는다.
    #  ㉟ detect 감지기 뮤테이션은 표적 소멸로 폐기 — 과제를 되살리는 날 함께 되살려라)
    ("㉚ 부두 관문의 안내판을 비운다 (문지방이 아무 말도 안 한다)", CFG,
     "      panel:", "      panel_없앰:", "안내판이 비었다"),
    ("㊱ 안내 문장이 없는 명령을 말한다 (4차 재문안 재표적)", CFG,
     "§8디스코드와 몸을 이으면(접속 발판) 사공이 태워 준다.",
     "§8막히면 §f/혼천 비상탈출§8 을 쳐라.", "/혼천 비상탈출"),

    # ─── 글판 · 규약 ───
    # (조각 갱신 2026-07-24 — B-170 이 clearPanels 와 stowed.clear() 사이에 saveStow() 를
    #  끼워 넣어 옛 조각이 죽어 있었다. 시험 자체가 고장난 채 잠들어 있던 것)
    ("㊲ 글이 세계에 남는다 (shutdown 이 안 걷는다)", SRC,
     "            clearPanels(w);\n        }\n        // ★ **지우기 전에 굽는다.**",
     "        }\n        // ★ **지우기 전에 굽는다.**", "세계에 남는다"),
    ("㊳ 재조성이 겹친다 (걷기 전에 세운다)", SRC,
     '            stage(w, "글판 걷기", this::clearPanels);\n            stage(w, "허수아비 걷기", this::clearDummies);\n            stage(w, "글판 세우기", this::spawnPanels);',
     '            stage(w, "허수아비 걷기", this::clearDummies);\n            stage(w, "글판 세우기", this::spawnPanels);',
     "두 겹으로 겹친다"),
    # (㊴ 재표적 ★5차 — 판의 출처가 과제 title/how → 등록부 Station.panel 로 바뀌었다)
    ("㊴ 판이 등록부와 딴말을 한다", SRC,
     "            return s.panel();", "            return List.of(\"§7알아서 해라\");",
     "딴말을 할 수 있다"),
    ("㊵ 표지판으로 돌아간다 (글자가 작다)", SRC,
     "Material.BELL,", "Material.OAK_SIGN,", "표지판\\(Sign\\)을 세운다"),
    ("㊶ 난수를 넣는다 (결정론 위반 — 4차: 갈대가 grain 격자 잡음이 됐다)", SRC,
     "double n = grain(x, z);",
     "double n = Math.random();", "난수가 있다"),
    ("㊷ 지면을 지어낸다 (y5 낙사 재발)", SRC,
     "return w.getHighestBlockYAt(cx + 512, cz + 512);", "return 5;", "월드에게 묻지 않는다"),
    ("㊸ 조성이 틱을 다 먹는다 (슬라이싱 제거)", SRC,
     'TickBudget.slice(plugin, "입도진"', 'noSlice(plugin, "입도진"', "틱 슬라이싱을 안 탄다"),

    # ─── ★★ 허수아비 (2026-07-13 · 사용자: "인증 전까지 때릴 상대가 없습니다") ───
    #
    # 이 눈은 그날까지 **없었다**. 감사는 위반 0건이라 했고, 마당은 비어 있었다.
    # 아래 열 가지는 그날의 병과 그 이웃들이다 — 하나라도 못 잡으면 눈이 또 거짓말하는 것이다.
    # (3차 개정 — 허수아비 0몸이 정상이 됐다: ㊹「통째로 뺀다」·㊺「손 관문 것을 뺀다」·
    #  (52)(53) 자리·내구 뮤테이션은 표적 소멸로 폐기. 대신 「과제 있는데 상대 없음」의 눈은
    #  산다 — 아래 ㊹' 가 손 과제를 되살려 넣고 상대가 없음을 잡는지 본다)
    # (㊹' 「과제 있는데 상대 없음」 뮤테이션 — ★5차 과제 폐지로 표적 소멸 · 폐기.
    #  과제를 되살리는 날 이 눈(허수아비 대조)도 함께 되살려라 — 이 묘비가 그 알림이다)
    ("㊻ ★★ 난이도를 평화로 되돌린다 (config) — 오늘의 병 그 자체", CFG,
     "  difficulty: EASY", "  difficulty: PEACEFUL", "평화는 허수아비"),
    ("㊼ ★★ 코드가 나루를 평화로 세운다 — 좀비가 조용히 지워진다", SRC,
     "            w.setDifficulty(difficulty);", "            w.setDifficulty(Difficulty.PEACEFUL);",
     "매 틱 조용히 지운다"),
    ("㊽ 평화를 버렸는데 사람을 지킬 손이 없다", CFG,
     "  damage_players: false", "  damage_players: true", "지킬 손"),
    ("㊾ ★ 체력에 숫자를 손으로 넣는다 (2048 병 재발 — 좀비가 아예 안 태어난다)", SRC,
     "                e.setHealth(attr.getValue());", "                e.setHealth(DUMMY_HEALTH);",
     "숫자를 손으로 넣는다"),
    ("㊿ ★ 허수아비 격리를 뜯는다 (하나가 죽으면 전부 안 선다)", SRC,
     "            try {\n                spawnDummy(w, d, y);\n                stood++;\n            } catch (Throwable t) {",
     "            if (true) {\n                spawnDummy(w, d, y);\n                stood++;\n            } else {",
     "격리해 세우지 않는다"),
    ("(51) ★ 조성 로그가 등록부의 개수를 찍는다 (로그가 거짓말한다)", SRC,
     "        int liveDummies = countDummies(w);", "        int liveDummies = dummies.size();",
     "세계에게 묻지 않는다"),
    ("(54) 명패에서 TTK 를 뺀다 (타격감을 잴 수 없다)", CFG,
     "상대 TTK {ttk}합", "상대 TTK", "맞은 것을 다 말하지 않는다"),

    # ─── ★★ 능(能) — 2026-07-13. **못 하는 것을 시키지 마라** ───
    #
    # 오늘의 가장 큰 거짓말: 나루에 오는 몸은 **범인**인데(air_jumps 0), 경공 과제는
    # "공중에서 점프를 한 번 더" 라고 시키고 있었다. 문장은 gyeonggong.yml 과 **글자 그대로 같았다** —
    # 그래서 옛 눈(문장 대조)은 통과시켰다. 눈이 **문장만 보고 몸을 안 봤다.**
    # 아래는 그 축을 시험한다.
    # (3차 개정 — 경공·격 과제 소멸: requires·unavailable 뮤테이션 (55)(56)(57)(60)은 표적이
    #  사라져 폐기. 「못 하는 것을 시키는」 병의 눈 자체는 ㊹' 재표적과 (58)(59)가 잇는다.
    #  ★과제를 되살리는 날에는 requires 뮤테이션도 함께 되살려라 — 이 묘비가 그 알림이다)
    # ((58) 허공_딛기 가부 코드 대조 — 3차 개정으로 그 능을 요구하는 과제가 없어 눈이 휴면.
    #  경공 과제를 되살리는 날 이 뮤테이션도 함께 되살려라)
    # ((59) applicable 뮤테이션 — ★5차 과제 폐지로 applicable 자체가 걷혔다 · 폐기. 위 묘비 참조)
    # ((60) 지어낸 능·(61) 손 콤보 문장 — 3차 개정으로 표적 소멸 · 위 묘비 참조)

    # ─── ★★ 접합 — 디스코드가 되돌려보내는 선행 문 ───
    ("(62) ★ 안내판이 선행 문(/혼천 시작)을 안 말한다 (거기서 튕긴다 · ★5차 재표적)", CFG,
     "§7① §f디스코드§7 에서 §f/혼천 시작§7 — 이름·성별을 짓고 §f서장§7 을 끝낸다.\\n",
     "", "선행 문"),

    # ─── ★★ 조성 완결성 — **반쯤 선 것을 '서 있다'고 하지 마라** ───
    #
    # 오늘 크래시가 나루를 반쯤 지어 놓고 죽였고, 조성기는 "이미 서 있다"며 건너뛰었다.
    # built() 가 본 것이 **블록 하나(종)** 였기 때문이다. 한 칸은 표본이 아니다.
    ("(63) ★★ 조성 완결성을 블록 하나(종)로 판단한다 — 오늘의 병 그 자체", SRC,
     "    private int completeness(World w) {\n        List<Place> plan = plan(groundY(w));",
     "    private boolean built(World w) {\n        return w.getBlockAt(cx + bell[0], "
     "groundY(w) + road.deckY() + 1, cz + bell[1]).getType() == Material.BELL;\n    }\n"
     "    private int completeness(World w) {\n        List<Place> plan = plan(groundY(w));",
     "블록 하나"),
    ("(64) ★ 완결성 표본을 난수로 집는다 (같은 세계가 매번 다른 점수를 받는다)", SRC,
     "        for (int i = 0; i < plan.size(); i += verifySample) {",
     "        for (int i = 0; i < plan.size(); i += 1 + (int) (Math.random() * verifySample)) {",
     "난수"),
    ("(65) ★ 완결성 문턱을 등록부에서 뗀다 (코드가 눈금을 지어낸다)", CFG,
     "    verify_min_pct: 97", "    verify_min_pct_없앰: 97", "눈금을 코드가 지어내"),
    ("(66) ★ 세어 놓고 그 답을 안 쓴다 (못 미쳐도 안 짓는다)", SRC,
     "        int score = force ? -1 : completeness(w);",
     "        int score = 100;",
     "견주지 않는다|세어 놓고"),

    # ─── ★★ 발판 — 2026-07-13. 사용자: **"발판 밟아도 메시지가 안 뜬다."** ───
    #
    # 재 보니 나루에 압력판이 **하나도 없었다.** 그런데:
    #   · 조성 로그는 "발판 6" 이라 찍었다 — 그것은 **등록부의 개수**(plates.size())였다
    #   · 완결도는 **97%** 였다 — 표본이 늪(4만 칸)에 묻혀 **발판 6칸을 못 봤다** (0.015%)
    # 그래서 조성기는 "이미 서 있다"며 건너뛰었고, 발판은 **영영 안 깔렸다.**
    # 표본은 **부피를 재지 의미를 재지 않는다.** 이정표는 전수 검사해야 한다.
    ("(68) ★★ 조성 로그가 발판을 등록부에서 센다 (0개가 깔려도 '발판 6')", SRC,
     '                + " · 발판 " + livePlates + "/" + plates.size()',
     '                + " · 발판 " + plates.size()', "세계에게 묻지 않는다|발판"),
    ("(69) ★★ 이정표(발판·종) 전수 검사를 뜯는다 — 발판 0인데 '이미 서 있다'", SRC,
     "        boolean marks = !force && landmarksStand(w);",
     "        boolean marks = true;", "이정표|전수"),
    ("(70) ★ 발판을 세계가 아니라 등록부에서 센다", SRC,
     "            if (w.getBlockAt(cx + p.x(), y, cz + p.z()).getType() == PLATE) {\n                n++;\n            }",
     "            n++;",
     "세계에게 묻지 않는다|발판"),

    # ─── ★ 허수아비가 쌓인다 — 등록부는 6인데 세계에 24 (오늘 저장된 나루에서 실제로 나왔다) ───
    ("(67) ★ ensureDummies 가 `>=` 로 판단한다 (허수아비가 쌓이고 영영 안 치워진다)", SRC,
     "        if (countDummies(w) == dummies.size()) {",
     "        if (countDummies(w) >= dummies.size()) {", "많은 것도 틀린 것이다"),

    # ─── ★3차 개정 추기 — 사공의 몸 (실사용 2026-07-24: "나루에 섭구가 없음") ───
    ("(71) ★ 사공을 말만 하고 몸을 안 세운다 (ensureFerryman 실종)", SRC,
     "    private void ensureFerryman(World w) {",
     "    private void ensureFerrymanX(World w) {", "말만 한다"),
    # ─── ★재방문 규약 (실사용 2026-07-24: "우클릭 하기도 전에 청하현으로") ───
    ("(72) ★ 재접속 접합자의 재방문 표식을 뗀다 (자동 출항이 다시 끌고 간다)", SRC,
     "                    revisiting.add(player.getUniqueId());\n                    if (!revisitLine.isEmpty()) {",
     "                    if (!revisitLine.isEmpty()) {", "재방문 표식"),

    # ─── ★★삼도천 화폭 (2026-07-24 사용자 확정 — 명계 개정) ───
    #
    # 화폭의 계약 넷: ① 서=넋등·동=등롱 (의미의 축) ② 저승은 어둑하되 전맹은 아니다
    # ③ 중경은 비대칭 (고사목=남·옛 잔교=북) ④ 원경은 동쪽에만. 하나라도 못 잡으면
    # 화폭이 조용히 무너져도 눈은 "위반 0건"이라 말한다.
    ("(73) 넋등을 등롱으로 되돌린다 (의미의 축이 죽는다)", SRC,
     "x < soulBoundary() ? Material.SOUL_LANTERN : Material.LANTERN",
     "Material.LANTERN", "넋등이 없다|의미의 축"),
    ("(74) 저승 경계가 등록부에 없는 관문을 가리킨다 (저승 구간이 통째로 증발)", CFG,
     "until_station: 나루", "until_station: 명부전", "모르는 관문|구간이 비었다"),
    ("(75) 고사목이 북쪽·잔교 곁을 침범한다 (비대칭이 죽는다)", CFG,
     "z_from: 6", "z_from: -6", "침범|비대칭"),
    ("(76) 고사목 문턱을 올려 군락을 지운다 (화폭에 중경이 없다)", CFG,
     "threshold: 0.55", "threshold: 0.99", "군락이 비었다"),
    # ((77) 조각 갱신 B-179 — 원경이 기슭 자리 x92 로 이사했다)
    ("(77) 이승의 불빛을 서쪽에 단다 (원경은 동쪽에만)", CFG,
     "light: [92, 0]", "light: [-92, 0]", "동쪽"),
    ("(78) 새벽을 황혼으로 되돌린다 (하늘이 축과 딴말을 한다)", CFG,
     "fixed_time: 22900", "fixed_time: 12800", "새벽 창"),
    ("(79) 시각을 코드가 지어낸다 (등록부를 안 본다)", SRC,
     "w.setTime(fixedTime);", "w.setTime(15000);", "시각을 코드가 지어낸다"),
    ("(80) 석등이 옛 잔교 선을 벗어난다 (유물이 아무 데나 선다)", CFG,
     "stone_lanterns: [1, 13]", "stone_lanterns: [1, 99]", "석등"),
    ("(81) 진흙 둔덕 띠가 갈대 띠를 침범한다 (두 띠가 같은 칸을 다툰다)", CFG,
     "mud_band: [0.475, 0.525]", "mud_band: [0.60, 0.70]", "갈대"),
    ("(82) 저승 구간의 넋등 리듬을 지운다 (어둑함이 전맹이 된다)", CFG,
     "post_every: 5", "post_every: 40", "전맹"),

    # ─── ★판의 자기일치 (실측 2026-07-24 — 겹쳐 쓴 판이 완결성 검증을 속여 94%) ───
    ("(83) 판의 겹쳐 쓰기 걷기를 우회한다 (검증이 제 판에 속는다)", SRC,
     "        return new ArrayList<>(dedup.values());", "        return out;",
     "겹쳐 쓰기를 안 걷어낸다"),
    ("(84) 겹친 기록의 자리를 안 옮긴다 (얹히는 것이 받침을 앞지른다)", SRC,
     "            dedup.remove(key);   // 자리를 끝으로 옮긴다 (put 만 하면 첫 자리에 남아 받침을 앞지른다)\n            dedup.put(key, p);",
     "            dedup.put(key, p);", "겹쳐 쓰기를 안 걷어낸다"),

    # ─── ★★기억의 회랑 (B-179 · 2026-07-25) — 강을 건너는 동안이 곧 서장이다 ───
    #
    # 회랑의 계약: 승선 세 길이 다 배를 띄운다 · 책은 정거장에서 열린다 · 배는 이승의 불빛에
    # 닿는다 · 명단이 끝나면 기슭=출도 (갇힘 금지). 하나라도 못 잡으면 회랑이 조용히 무너진다.
    # ((85)(86) 재표적 ★5차 — 나루 물길(정거장·기슭)이 서장 월드로 이사하며 표적 소멸.
    #   새 표적 = 서장 월드·나룻배 등록부)
    ("(85) 나룻배 치수를 눕힌다 (배 없는 바다에 사람을 내려놓는다)", CFG,
     "      half_len: 6              # 이물·고물 끝까지 반길이 (전장 13 · 동서로 눕는다 · 이물=동)",
     "      half_len: 3              # 이물·고물 끝까지 반길이 (전장 13 · 동서로 눕는다 · 이물=동)",
     "나룻배 치수"),
    ("(86) 서장 월드의 이름을 지운다 (별도 월드가 사라진다)", CFG,
     '      name: "honcheon_seojang" # 서장 월드 — 물뿐이다 (FLAT · 구조물 0 · 몹 0 · 결정론)',
     '      name: ""                 # 서장 월드 — 물뿐이다 (FLAT · 구조물 0 · 몹 0 · 결정론)',
     "서장 월드 이름"),
    ("(87) 책이 정거장을 모른다 (아무 데서나 열린다)", SBK,
     "        Antechamber ante = plugin.antechamber();\n        if (ante != null && ante.voyage().defer(player, scene)) {\n            return;\n        }",
     "", "정거장을 모른다|아무 데서나"),
    # ((88) 조각 갱신 ★3차 — 도하 개정: 명단 검사가 tick 으로 옮겨 갔다)
    ("(88) 기슭의 문을 잠근다 (명단이 끝나도 영원한 정박)", VOY,
     "            if (!WorldBridge.seojangHolds(body)) {\n                transit(player, r, -1);\n                continue;\n            }",
     "", "기슭의 문|영원한 정박"),
    # ((89) 재표적 ★4차 — 가짜 항해: 문장이 바뀌어도 침묵의 눈은 그대로 겨눈다)
    ("(89) 도하가 침묵한다 (물 위에서 아무도 말하지 않는다)", CFG,
     '      line: "§8노가 물을 가른다 — 물살이 뒤로 흘러가고, 안개가 마중을 나온다."   # 【제안】',
     '      line: ""', "도하가 침묵"),
    ("(90) 접합 직후의 승선 문을 닫는다 (부두 대기로 회귀)", SRC,
     "            voyage.embark(player);\n            return;\n        }\n        if (autoCrossSeconds > 0) {",
     "            return;\n        }\n        if (autoCrossSeconds > 0) {", "승선 문"),
    # ((91) 재표적 ★5차 — 문구가 「명단이 배 위에 쌓인다」로 바뀌었다)
    ("(91) 출도가 배를 안 걷는다 (명단이 배 위에 쌓인다)", SRC,
     "        voyage.disembark(id);   // ★B-179 — 배는 기슭에 남지 않는다 (항해는 메모리뿐이다)\n",
     "", "배를 안 걷는다|배 위에 쌓인다"),
    # ★실기동 1호 (2026-07-25 "이으니까 바로 책을 받고 읽기 시작") — 접합 직후의 경주
    # ((92) 재표적 ★5차 — 경주 봉인이 나루·바다 공통이 됐다)
    ("(92) 승선 전의 책을 안 붙든다 (책이 부두에서 열린다)", VOY,
     "            return (Antechamber.isAntechamber(player.getWorld()) || isSea(player.getWorld()))\n                    && !scene.writing();",
     "            return false;", "부두에서"),

    # ─── ★★기억의 무대 (B-179 2차 · "글이 아닌 몸으로 역사를 느끼는 형태") ───
    ("(93) 무대 제목이 장면과 어긋난다 (계열 판별이 죽는다)", STG,
     '      - title: "그날 밤"', '      - title: "그날_밤"', "못 알아본다"),
    ("(94) 강등 문을 뜯는다 (무대가 꺼진 날 책도 안 온다 — 침묵)", VOY,
     "            SeojangBook.get().deliver(player, scene);   // 강등 — 무대가 꺼져 있으면 책이 온다",
     "", "강등 문이 없다"),
    ("(95) 재배달 억제를 뜯는다 (무대 그릇인데 책이 몰래 온다)", VOY,
     "            return ante.stage().enabled();", "            return false;",
     "몰래 쥐여 줄 수 있다"),
    ("(96) 무대의 숨을 뺏는다 (조형만 있는 침묵)", STG,
     '        pulse:\n          - "§c불길이 담을 넘었다 §7— 매캐한 연기가 코끝을 찔렀다"\n          - "§7식구들의 그림자가 뿔뿔이 흩어졌다"',
     "        pulse: []", "조형만 있는 침묵"),
    ("(97) 등불이 다리에 안 얹는다 (골라도 아무 일도 없다)", SJS,
     "        WorldBridge.seojangChoice(player.getUniqueId(), player.getName(), token, choice);",
     "", "다리에 안 얹는다"),

    # ─── ★발단별 무대 (B-179 3차 · "모든 경우의 수") ───
    ("(98) 발단 무대의 이름을 지어낸다 (역병 → 돌림병)", STG,
     "    역병:                      # 흰 천과 향불 — 조용해진 마을",
     "    돌림병:                    # 흰 천과 향불 — 조용해진 마을", "지어낸 발단|무대가 없다"),
    ("(99) 봇이 발단을 안 싣는다 (첫 장이 영영 계열 폴백)", GLB,
     '            e.put("incident", ch.incident());', "", "발단을 안 싣는다"),
    ("(100) 첫 장이 발단을 안 본다 (역병의 밤도 불타는 집)", SJS,
     "            SceneSpec byIncident = incidents.get(scene.incident());",
     "            SceneSpec byIncident = null;", "발단을 안 본다"),

    # ─── ★B-181 — 출분의 제 벌 (갈래 배정 등록부) ───
    ("(101) 출분 발단을 갈래 등록부에서 뗀다 (재난 벌로 떨어진다)", SJY,
     "  담을_넘다: 출분\n", "", "재난 벌로 떨어진다"),
    ("(102) 봇이 갈래 등록부를 안 읽는다 (등록부가 있어도 출분은 재난 벌)", BSJ,
     '        Map<String, Object> bo = RulesConfig.section(cfg, "branch_of");',
     "        Map<String, Object> bo = Map.of();", "갈래 등록부"),
    # ─── ★세가 (2026-07-25 — 오대세가 자제 · 승격 주사위) ───
    ("(103) 세가 발단을 갈래 등록부에서 뗀다 (밀서가 재난 벌로 떨어진다)", SJY,
     "  밀서: 세가\n", "", "재난 벌로 떨어진다"),

    # ─── ★도하 (3차 개정 — 정박 무대 + 암전. 옛 (104)~(107) 탈것 뮤테이션은 표적 소멸 · 재표적) ───
    # ((104) 재표적 ★4차 — 가짜 항해: 암전 단발이 눈깜빡임으로 줄었다 — 눈은 그대로 겨눈다)
    ("(104) 도하가 연출 없는 순간이동이 된다 (눈깜빡임이 사라진다)", VOY,
     "                p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS,\n                        blinkTicks + 30, 0, false, false, false));",
     "", "연출 없는 순간이동"),
    # ((105) 재표적 ★5차 — 정박 갑판(나루 조성 판)이 걷히고 배는 승선이 세운다)
    ("(105) 승선이 배를 안 세운다 (배 없는 바다에 사람을 내려놓는다)", VOY,
     "        buildBarge(sea);   // 멱등 — 배 없는 바다에 사람을 내려놓지 않는다\n",
     "", "배를 안 세운다"),
    ("(106) 도하 뒤 장이 안 열린다 (갑판이 침묵한다)", VOY,
     "                open(p, still, scene);", "", "장이 안 열린다"),
    ("(108) 붓을 내려놔도 「적고 있다」가 남는다 (화면의 거짓말)", VOY,
     "        SeojangBook.get().settle(player);", "", "적고 있다\\S*가 안 걷힌다|기다림 기계를 걷어야"),

    # ─── ★낙하 3방어 (실사용 2026-07-25 — 우물 질식·이상 리스폰) ───
    # ((109) 재표적 — 「미리 열기」가 겉몸 치유(build+ensure)와 한 몸이 되며 조각이 자랐다.
    #   표류를 이 시험이 잡았다 (⁉️ 원본 조각을 못 찾았다 · 2026-07-25) — 눈은 그대로 겨눈다)
    ("(109) 나루를 미리 안 연다 (재기동 후 재접속 = 우물 낙하)", SRC,
     "        World pre = world();\n        if (pre != null) {",
     "        World pre = null;\n        if (pre != null) {", "미리 안 연다"),
    ("(110) 나루 밖의 항해자를 onJoin 이 안 집는다 (낙하한 몸 방치)", SRC,
     "                if (!isAntechamber(player.getWorld())\n                        && WorldBridge.seojangHolds(player.getUniqueId())) {",
     "                if (false) {", "안 집는다|방치"),
    ("(111) 죽은 넋이 나루로 못 돌아온다 (리스폰이 본세계에 세운다)", SRC,
     "        if (WorldBridge.seojangHolds(event.getPlayer().getUniqueId())) {",
     "        if (false) {", "못 돌아온다"),

    # ─── ★가짜 항해 + 안개 장막 (4차 개정 2026-07-25 — "정박+암전이 배 타고 건넌다로
    #     안 읽힌다" · "다음 정거장이 보인다") ───
    ("(112) 흐름 등록부를 뗀다 (도하에 항해의 몸이 없다)", CFG,
     "      flow_ticks: 110          # 세계가 흐르는 길이 (5.5초) 【제안】\n",
     "", "항해의 몸이 없다"),
    ("(113) 노 박자 등록부를 뗀다 (노 없는 널빤지)", CFG,
     "      row_period: 22           # 노 박자 간격 (틱) — 한 젓기 · 좌우 번갈아 【제안】\n",
     "", "노 박자가 없다"),
    # ((114)(116) 재표적 ★5차 — 안개 장막(중간점) → 안개 링(배 주위))
    ("(114) 안개 링의 키를 눕힌다 (빈 수평선이 세계를 좁힌다)", CFG,
     "      height: 5                # 물 위로 이 칸까지 【제안】",
     "      height: 0                # 물 위로 이 칸까지 【제안】", "수평선"),
    ("(115) 가짜 항해의 붓을 꺾는다 (암전 사이에 항해의 몸이 없다)", VOY,
     "        startFlow(player, r);", "", "가짜 항해가 말뿐"),
    ("(116) 시계가 링을 안 피운다 (링이 말뿐이다)", VOY,
     "            fogRing(player);   // ★안개 링 — 빈 수평선을 안개가 감싼다 (본인에게만)\n",
     "", "링이 말뿐"),

    # ─── ★5차 (2026-07-25 — 별도 서장 월드 · 한 배 위의 서장 · 튜토리얼 침묵) ───
    ("(117) 묘비를 파헤친다 (나루 물길 등록부의 부활)", CFG,
     "  voyage:\n    world:",
     "  voyage:\n    stations_x: [44, 60, 76]\n    world:", "묘비가 부활"),
    ("(118) 서장 월드를 낮으로 돌린다 (칠흑+달빛이 죽는다)", CFG,
     "      fixed_time: 18000        # 칠흑 + 달빛 — 자정 (사용자 확정 「칠흑 + 달빛」) 【제안】",
     "      fixed_time: 6000         # 칠흑 + 달빛 — 자정 (사용자 확정 「칠흑 + 달빛」) 【제안】",
     "밤이 아니다"),
    ("(119) 기동이 배를 안 세운다 (1차 방어에 배가 빠진다)", SRC,
     "            voyage.buildBarge(sea);\n", "", "기동이 배를 안 세운다"),
    ("(120) 튜토리얼 침묵 게이트를 뜯는다 (배 위 우클릭이 과제로 세인다)", TUT,
     "        if (!enabled || silenced(player)) {\n            return;\n        }",
     "        if (!enabled) {\n            return;\n        }", "과제가 말을 건다"),
    ("(121) 트래커가 항해 중에도 뜬다 (사이드바 침묵 구멍)", MVT,
     "        String tut = tutorial == null || tutorial.silenced(player)\n                ? null : tutorial.trackerLine(ledger);",
     "        String tut = tutorial == null ? null : tutorial.trackerLine(ledger);",
     "트래커가 뜬다"),
    ("(122) 서장 월드 재접속을 안 집는다 (3방어의 구멍)", SRC,
     "                if (Voyage.isSea(player.getWorld())\n                        && WorldBridge.seojangHolds(player.getUniqueId())) {",
     "                if (false) {", "재접속을 아무도 안 집는다"),
    # ─── ★5차 실기동 1호 (2026-07-25 "2장이 다시 시작되지도 않아" · "배에 뱃사공도 없어") ───
    ("(123) 패의 세계 검사를 나루로 되돌린다 (서장 월드의 몸에게 패가 영영 안 걸린다)", SJS,
     "            if (player.isOnline() && (Antechamber.isAntechamber(player.getWorld())\n                    || Voyage.isSea(player.getWorld()))) {",
     "            if (player.isOnline() && Antechamber.isAntechamber(player.getWorld())) {",
     "패가 서장 월드를 모른다"),
    ("(124) 승선이 사공을 안 세운다 (사공 없는 배)", VOY,
     "        ensureFerryman(sea);   // 사공 없는 배도 배가 아니다 (한 배에 한 사공)\n",
     "", "사공이 말뿐"),
    ("(125) 사공의 이름을 지운다 (등록부 없는 사공)", CFG,
     '      name: "§7사공"           # 명패 — 조용한 회색 【제안】',
     '      name: ""                 # 명패 — 조용한 회색 【제안】', "사공 등록부"),
    # ─── ★서사 글판·명패형·사다리·삿대 (2026-07-25 실기동 빨간펜 2회차) ───
    ("(126) 서사 글판을 안 세운다 (무엇에서 고르는지 패만 안다)", SJS,
     "        if (npEnabled && scene.narration() != null && !scene.narration().isBlank()) {",
     "        if (false) {", "서사 글판이 없다"),
    ("(127) 명패 문법을 굳힌다 (어느 패든 같은 글자)", STG,
     '    label_format: "§f[ {label} ]"   # 명패 문법 【제안】',
     '    label_format: "§f[ 패 ]"        # 명패 문법 【제안】', "명패형이 아니다"),
    ("(128) 사다리를 걷는다 (물에 빠진 몸이 못 오른다)", VOY,
     '            BlockData ladder = Bukkit.createBlockData(\n                    "minecraft:ladder[facing=" + facing + ",waterlogged=true]");',
     "            BlockData ladder = null;", "사다리가 없다"),
    ("(129) 삿대를 꺾는다 (떠내려간 몸이 밤바다에 남는다)", VOY,
     "            rescueIfAdrift(player);   // ★삿대 — 사다리로도 못 오르는 몸은 사공이 건져 올린다\n",
     "", "삿대가 없다"),
]


def run_audit():
    # --no-world: 이 시험은 **정적 눈**을 시험한다 (config·소스를 흔들어 본다).
    # 세계 축(⑪)은 저장된 월드를 읽으므로 소스를 흔든다고 바뀌지 않는다 — 그것은 따로 잰다.
    p = subprocess.run([sys.executable, AUDIT, "--no-world"], capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr


def test_marker_census():
    """⑪ 세계 축의 계수기를 잰다 — ★ 몸 하나가 키 둘(ipdo_dummy + ipdo_dummy_label)을
    지니므로, 부분 문자열을 그냥 세면 **한 몸이 두 번** 세어진다 (2026-07-14 실증:
    갓 지은 나루 · 실제 6몸을 눈이 12라 했다). 세계 축은 저장된 월드가 있어야 돌므로
    뮤테이션이 아니라 **합성 바이트**로 계수기만 직접 잰다."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("ante_audit", AUDIT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    # 좀비 한 몸의 저장 꼴 근사 — PDC 키 둘 + 무관한 이웃 글판 하나
    one_body = (b"\x00\x13honcheon:ipdo_dummy\x03\x00\x00\x00\x05"
                b"\x00\x19honcheon:ipdo_dummy_label\x00\x04\xea\xb2\x80\xea")
    one_panel = b"\x00\x13honcheon:ipdo_panel\x00\x05gate1"
    checks = [
        ("몸 하나(키 둘)는 1로 센다", one_body, 1, 0),
        ("몸 둘 + 글판 하나", one_body * 2 + one_panel, 2, 1),
        ("빈 청크는 0", b"", 0, 0),
    ]
    bad = 0
    for name, blob, want_d, want_p in checks:
        got = mod.marker_census(blob)
        ok = got["dummy"] == want_d and got["panel"] == want_p
        detail = "" if ok else " — dummy {}≠{} · panel {}≠{}".format(
            got["dummy"], want_d, got["panel"], want_p)
        print(f"  {'✅' if ok else '❌'}  (계수기) {name}{detail}")
        if not ok:
            bad += 1
    return bad


def main():
    import re
    # ★B-179 — 뮤테이션이 겨누는 파일이 넷으로 늘었다 (CFG·SRC·SBK·VOY). 목록에서 모아
    #   전부 백업한다 — 두 개만 되돌리면 나머지 파일의 뮤테이션이 **영구 감염**된다.
    paths = sorted({m[1] for m in MUTATIONS})
    for p in paths:
        shutil.copy(p, p + ".bak")

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
        for p in paths:
            shutil.copy(p + ".bak", p)

    for p in paths:
        os.remove(p + ".bak")

    rc, out = run_audit()
    print(f"\n되돌린 뒤: 종료코드 {rc}")

    print("\n─── ⑪ 세계 축 계수기 (합성 바이트) ───")
    census_bad = test_marker_census()

    print(f"\n═══ 눈의 시험: 잡음 {caught} · 놓침 {missed} / {len(MUTATIONS)}"
          f" · 계수기 실패 {census_bad} ═══")
    return 0 if missed == 0 and census_bad == 0 and rc == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
