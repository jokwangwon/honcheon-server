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
    ("⑫ 관문 순서를 뒤엉키게 한다 (3차 개정 재표적 — 나루를 맞이 앞에)", CFG,
     "    - { id: 나루, x: 24,  half: 4, lesson: 접속 }",
     "    - { id: 나루, x: -40, half: 4, lesson: 접속 }", "오름차순이 아니다"),
    ("⑬ 관문을 길 밖에 둔다 — 아무도 못 간다 (3차 개정 재표적)", CFG,
     "    - { id: 나루, x: 24,  half: 4, lesson: 접속 }",
     "    - { id: 나루, x: 99,  half: 4, lesson: 접속 }", "길 밖에 있다"),
    ("⑭ 눈 뜨는 자리를 물 위에 둔다", CFG,
     "  spawn: [-6, 0]", "  spawn: [-20, 0]", "딛을 수 있는 땅이 아니다"),
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
    ("⑲ 범인이 격·경공 관문에서 막힌다 (그 뒤를 영영 못 본다)", SRC,
     "        if (lacks(player, l)) {\n            return true;   // ★ 못 하는 것 때문에 길이 막히지 않는다. 그냥 지나간다 (판은 예고로 바뀐다)\n        }",
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
    ("㉚ 과제를 통째로 비운다 (문지방이 아무것도 안 가르친다)", CFG,
     "      - id: 접속", "      - id_없앰: 접속", "과제가 하나도 없다"),
    ("㉟ 과제 감지가 지어낸 이름을 단다 (감지기 없는 표지판)", CFG,
     "        detect: 명령", "        detect: 없는_감지", "감지기가 없다|표지판일 뿐이다"),
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
    ("㊴ 판이 과제와 딴말을 한다", SRC,
     "        return List.of(panelSpec.titlePrefix() + l.title(),\n                unavailableVariant ? l.unavailable() : l.how());",
     "        return List.of(panelSpec.titlePrefix() + s.id(), \"§7알아서 해라\");",
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
    ("㊹' ★ 타격 과제를 되살렸는데 허수아비가 없다 (가르치는데 상대가 없다)", CFG,
     "      - id: 접속",
     "      - id: 손\n        title: \"손 — 한 획\"\n        how: \"§7좌클릭 — 획이 호를 그리며 돈다 (횡_좌우 → 횡_우좌 → 올려베기). 콤보가 아니다\"\n"
     "        detect: 허수아비_타격\n        count: 3\n        done: \"세 합\"\n      - id: 접속",
     "때릴 상대가 없다"),
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
    ("(59) ★★ applicable() 이 못 하는 과제를 센다 (all_done 이 영영 안 뜬다)", SRC,
     "            if (lacks(player, l)) {\n                continue;\n            }", "",
     "all_done|못 했다고 센다"),
    # ((60) 지어낸 능·(61) 손 콤보 문장 — 3차 개정으로 표적 소멸 · 위 묘비 참조)

    # ─── ★★ 접합 — 디스코드가 되돌려보내는 선행 문 ───
    ("(62) ★ 접속 과제가 선행 문(/혼천 시작)을 안 말한다 (거기서 튕긴다)", CFG,
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

    print("\n─── ⑪ 세계 축 계수기 (합성 바이트) ───")
    census_bad = test_marker_census()

    print(f"\n═══ 눈의 시험: 잡음 {caught} · 놓침 {missed} / {len(MUTATIONS)}"
          f" · 계수기 실패 {census_bad} ═══")
    return 0 if missed == 0 and census_bad == 0 and rc == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
