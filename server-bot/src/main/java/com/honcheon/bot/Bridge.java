package com.honcheon.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.honcheon.core.rules.RulesConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 세계 다리(世界橋) — 봇 쪽 수신부. <b>몸에서 벌어진 일을 장부에 적는다.</b>
 *
 * <p>지금까지 세계는 둘로 쪼개져 있었다. 마크에서 마을 사람을 죽여도 봇의 소문·명분·수사는
 * 아무것도 몰랐다. 이 클래스가 그 사이를 잇는다 — <b>한쪽 방향으로만 흐르는 사실</b>과
 * <b>반대로 흐르는 상태</b>로.
 *
 * <p><b>사실은 큐로 흐른다.</b> MVT 는 {@code run/bridge/mvt/<날짜>.jsonl} 에 한 줄씩 덧붙이고,
 * 봇은 커서(world_meta '다리:커서' = "파일:바이트오프셋")를 들고 그 뒤를 따라간다.
 * 봇이 사흘 꺼져 있었어도 사흘치가 그대로 거기 있다 — <b>파일은 잊지 않는다.</b>
 * 멱등은 bridge_inbox(PK=event_id)가 지킨다: 같은 줄을 두 번 읽어도 사람은 한 번만 죽는다.
 *
 * <p><b>상태는 스냅숏으로 흐른다.</b> {@code run/bridge/world_state.json} 을 원자적으로 갈아 끼운다.
 * 어제의 소문판을 다시 트는 것은 세계가 아니라 녹음이다 — 되먹임에 재생은 없다. 최신이 이긴다.
 *
 * <p><b>등록제.</b> 이벤트의 종류·페이로드·귀결(소문 태그·강도·지역 델타)은 전부
 * {@code config/world_bridge.yml} 이다. 이 클래스는 그 표를 읽어 <b>이미 있는 세계 기계</b>
 * (Deaths.rumor_matrix · Rumors.arrivals · Db.spreadRumor · factionAwareness)에 밀어 넣을 뿐이다.
 * 새 수치를 발명하지 않는다 — 그것이 다리가 세계를 왜곡하지 않는 유일한 방법이다.
 */
final class Bridge {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Rules rules;
    private final Db db;
    private final GameListener game;
    private final Map<String, Object> cfg;

    private final Path bridgeDir;
    private final Path outboxDir;
    private final Path snapshotFile;
    private final String cursorKey;
    private final int pollSeconds;
    private final int snapshotSeconds;

    private ScheduledExecutorService sched;

    Bridge(Rules rules, Db db, GameListener game, Path configDir) {
        this.rules = rules;
        this.db = db;
        this.game = game;
        this.cfg = RulesConfig.load(configDir.resolve("world_bridge.yml"));

        Map<String, Object> transport = RulesConfig.section(cfg, "transport");
        this.bridgeDir = Path.of(String.valueOf(transport.getOrDefault("dir", "run/bridge")));
        this.outboxDir = bridgeDir.resolve(String.valueOf(transport.getOrDefault("outbox", "mvt")));
        this.snapshotFile = bridgeDir.resolve(
                String.valueOf(transport.getOrDefault("snapshot", "world_state.json")));
        this.cursorKey = String.valueOf(transport.getOrDefault("cursor_key", "다리:커서"));
        this.pollSeconds = num(transport.get("poll_seconds"), 5);
        this.snapshotSeconds = num(transport.get("snapshot_seconds"), 20);
    }

    /** 다리를 연다 — 밀린 것을 먼저 따라잡고(봇이 꺼져 있던 동안의 세계), 그 뒤로 계속 듣는다 */
    void start() {
        try {
            Files.createDirectories(outboxDir);
        } catch (IOException e) {
            System.err.println("세계 다리를 열 수 없다 (" + outboxDir + "): " + e.getMessage());
            return;
        }
        sched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "honcheon-bridge");
            t.setDaemon(true);
            return t;
        });
        sched.scheduleWithFixedDelay(this::drainQuietly, 0, pollSeconds, TimeUnit.SECONDS);
        sched.scheduleWithFixedDelay(this::publishQuietly, 2, snapshotSeconds, TimeUnit.SECONDS);
        System.out.println("세계 다리 — 수신 " + outboxDir + " (" + pollSeconds + "초) · 되먹임 "
                + snapshotFile + " (" + snapshotSeconds + "초) · 등록 이벤트 " + kinds());
    }

    void stop() {
        if (sched != null) {
            sched.shutdownNow();
        }
    }

    Set<String> kinds() {
        return RulesConfig.section(cfg, "events").keySet();
    }

    // ══════════════ 수신 — 커서를 들고 파일을 따라간다 ══════════════

    private void drainQuietly() {
        try {
            drain();
        } catch (Exception e) {
            System.err.println("다리 수신 실패: " + e.getMessage());
        }
    }

    /**
     * 새로 적힌 줄들을 읽어 세계에 적용한다. 반환: 적용한 사건 수.
     *
     * <p>커서는 (파일, 바이트오프셋)이다. 조각 파일은 날짜순이므로 이름순 = 시간순.
     * <b>끝이 개행으로 닫히지 않은 줄은 읽지 않는다</b> — 쓰는 쪽이 아직 쓰는 중일 수 있다
     * (다음 폴에서 온전해진 뒤 읽는다. 반쪽 사건을 세계에 들이지 않는 유일한 방법이다).
     */
    int drain() throws Exception {
        if (!Files.isDirectory(outboxDir)) {
            return 0;
        }
        String cursor = db.getMeta(cursorKey).orElse("");
        String cursorFile = cursor.contains(":") ? cursor.substring(0, cursor.lastIndexOf(':')) : "";
        long cursorOffset = cursor.contains(":")
                ? Long.parseLong(cursor.substring(cursor.lastIndexOf(':') + 1)) : 0;

        List<Path> segments;
        try (var files = Files.list(outboxDir)) {
            segments = files.filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
        int applied = 0;
        for (Path segment : segments) {
            String name = segment.getFileName().toString();
            if (name.compareTo(cursorFile) < 0) {
                continue;   // 이미 다 읽은 조각
            }
            long offset = name.equals(cursorFile) ? cursorOffset : 0;
            byte[] all = Files.readAllBytes(segment);
            if (offset >= all.length) {
                continue;
            }
            // 마지막 개행까지만 — 그 뒤는 아직 쓰는 중인 반쪽 줄일 수 있다
            int end = all.length;
            while (end > offset && all[end - 1] != '\n') {
                end--;
            }
            if (end <= offset) {
                continue;
            }
            String chunk = new String(all, (int) offset, (int) (end - offset), StandardCharsets.UTF_8);
            long at = offset;
            for (String line : chunk.split("\n")) {
                at += line.getBytes(StandardCharsets.UTF_8).length + 1;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    if (apply(line)) {
                        applied++;
                    }
                } catch (Exception e) {
                    System.err.println("다리 사건 적용 실패 (건너뛴다): " + e.getMessage() + " — " + line);
                }
                db.setMeta(cursorKey, name + ":" + at);   // 한 줄마다 못을 박는다 (중간에 죽어도 여기부터)
            }
        }
        return applied;
    }

    /** 한 줄 = 한 사건. 이미 적용한 것이면 조용히 넘긴다 (멱등 — bridge_inbox 가 판정한다) */
    @SuppressWarnings("unchecked")
    private boolean apply(String line) throws Exception {
        Map<String, Object> envelope = JSON.readValue(line, Map.class);
        String id = String.valueOf(envelope.get("id"));
        String kind = String.valueOf(envelope.get("kind"));
        if (!kinds().contains(kind)) {
            System.err.println("다리 — 미등록 이벤트 무시: " + kind);
            return false;
        }
        if (!db.claimBridgeEvent(id, kind)) {
            return false;   // 이미 세계에 들어온 사건 (재생은 무해하다)
        }
        Map<String, Object> data = envelope.get("data") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        int today = db.worldDay();
        switch (kind) {
            case "npc_death" -> npcDeath(data, today);
            case "surrender" -> surrender(data, today);
            case "populace_quest" -> populaceQuest(data, today);
            case "bandit_slain" -> slain(data, today, true);
            case "beast_slain" -> slain(data, today, false);
            case "qi_manifested" -> qiManifested(data, today);
            case "sparring" -> sparring(data, today);
            case "link_request" -> linkRequest(envelope, data);
            case "cultivation_logged" -> cultivationLogged(data, today);
            default -> System.err.println("다리 — 처리기 없음: " + kind);
        }
        return true;
    }

    // ─── link_request — 신원 접합의 첫 걸음 (아직 아무것도 이어지지 않았다) ───

    /**
     * 마크가 코드를 냈다. <b>여기서는 대기열에 앉히기만 한다</b> — 확정은 디스코드에서 한다.
     *
     * <p>왜 그런가: 최종 결속을 <b>인증된 자리</b>(디스코드)에 두면, 남의 캐릭터를 뺏으려면
     * 남의 디스코드 계정이 있어야 한다. 코드가 새어 나가도 도둑이 할 수 있는 최악은
     * '제 캐릭터에 남의 마크 몸을 붙이는 것' — 자해다. (world_bridge.yml identity.direction)
     *
     * <p>만료는 <b>봉투의 at</b> 을 기준으로 잰다. 봇이 꺼져 있던 동안 발급된 코드는 켜는 순간
     * 이미 죽어 있다 — 그것이 옳다. 10분 전의 승낙으로 지금의 몸을 잇지 않는다.
     */
    private void linkRequest(Map<String, Object> envelope, Map<String, Object> data) throws Exception {
        String code = str(data.get("code"), "").toUpperCase(java.util.Locale.ROOT);
        String uuid = str(data.get("mc_uuid"), "");
        String name = str(data.get("mc_name"), uuid);
        if (code.isBlank() || uuid.isBlank()) {
            return;
        }
        long issuedAt = envelope.get("at") instanceof Number n ? n.longValue()
                : System.currentTimeMillis();
        int ttl = num(data.get("ttl_seconds"),
                num(map(RulesConfig.section(cfg, "identity")).get("ttl_seconds"), 600));
        db.pendLinkCode(code, uuid, name, issuedAt, issuedAt + ttl * 1000L);
        db.linkMvt(uuid, name, null);   // 몸은 등록해 둔다 (이름 갱신) — 이어지는 것은 아직 아니다
        System.out.println("다리 — 접합 코드 대기: " + name + " (" + ttl + "초)");
    }

    // ─── cultivation_logged — 몸에서 쌓인 것이 장부로 온다 ───

    /**
     * <b>마크의 수련·실전이 시트를 바꾼다.</b> 시트를 적는 손은 여기 하나뿐이다.
     *
     * <p>규칙(curriculum → 능력치·내공·숙련)은 마크의 {@code Growth} 에 <b>한 벌만</b> 있고, 봇은 그
     * <b>결과인 증분</b>만 받는다. 봇이 환산을 다시 구현하면 규칙의 정본이 둘이 되고, 마크가 시트를 쓰면
     * 장부의 정본이 둘이 된다 — 이 저장소가 반복해서 데인 병이다. 그래서 이렇게 잘라 뒀다:
     * <b>규칙은 마크에 하나 · 장부는 봇에 하나.</b>
     *
     * <p>접합되지 않은 몸은 여기 오지 않는다 (마크가 애초에 안 보낸다). 와도 캐릭터가 없으면 버린다 —
     * 강호에 이름이 없는 자의 화후는 적을 곳이 없다.
     *
     * <p>재생은 무해하다: {@code bridge_inbox}(PK=event_id)가 이미 못을 박았다. 여기 오는 줄은
     * <b>처음 오는 줄</b>이다.
     */
    @SuppressWarnings("unchecked")
    private void cultivationLogged(Map<String, Object> data, int today) throws Exception {
        String uuid = str(data.get("player_uuid"), "");
        if (uuid.isBlank()) {
            return;
        }
        var linked = db.characterOfMc(uuid);
        if (linked.isEmpty()) {
            System.err.println("다리 — 접합되지 않은 몸의 화후 (버린다): " + str(data.get("player_name"), uuid));
            return;
        }
        long chId = linked.get();
        var found = db.findCharacterById(chId);
        if (found.isEmpty()) {
            return;
        }
        Map<String, Object> ch = found.get();
        Map<String, Object> sheet = new java.util.LinkedHashMap<>(
                (Map<String, Object>) ch.get("sheet"));
        String before = String.valueOf(ch.get("realm"));
        String after = game.applyCultivation(sheet, before, data, today);

        db.updateCharacter(chId, sheet, ((Number) ch.get("wallet")).intValue(), after,
                String.valueOf(ch.get("status")), String.valueOf(ch.get("location")));
        if (!before.equals(after)) {
            // 승급은 강호가 인정하는 것이다 — 장부에 남고, 다음 스냅숏으로 몸에 내려간다
            db.logEvent("승급", "character", String.valueOf(chId),
                    Map.of("경지", after, "출처", "mvt"));
            System.out.println("다리 — 승급: " + ch.get("name") + " " + before + " → " + after);
        }
    }

    // ─── npc_death — 사람이 죽었다 ───

    /**
     * 무명(populace)이든 등록 NPC(cheongha_npcs)든 죽음은 같은 기계를 탄다:
     * 사망 등록 → 목격·시신이 정하는 소문(npc_death.yml killer_response.rumor_matrix) →
     * 지역의 냉기(민심) → 관을 죽였다면 법명분.
     *
     * <p>다른 것은 <b>연쇄의 깊이</b>다. 등록 NPC 는 후계·서비스 공백·의뢰를 낳지만, 무명에게는
     * 아무도 없다 (populace.yml death.revenge: civil_debt). "가장 약한 자를 죽인 대가는
     * 복수가 아니라 지역의 냉기다" — 그 냉기만이 장부에 남는다.
     *
     * <p>★ 소문의 <b>주체는 가해자</b>다 (목격자가 있을 때만). 그래야 세력이 그를 주목하기 시작한다 —
     * GameListener.factionAwareness 가 소문의 주체_id 를 보고 움직인다. 아무도 못 봤으면 이름은 남지 않는다.
     */
    /**
     * <b>자수</b> — 관(官)의 법만 움직인다.
     *
     * <p>★ <b>혈채는 삭제되지 않는다</b> ({@code blood_debt atonement.principle}). 법정이 둘이다 —
     * 관의 법(법명분)은 자수로 덜 수 있지만, <b>피는 관이 사면하지 못한다.</b>
     * 둘 다 지워 주겠다고 약속하면 그것이 거짓말이다.
     */
    private void surrender(Map<String, Object> data, int today) throws Exception {
        Killer who = killer(data, "player_uuid", "player_name");
        Map<String, Object> effects = effects("surrender");
        db.logEvent("자수", who.actorType(), who.actorId(), "gwan_gun", "자수",
                Map.of("벌금", num(data.get("fine"), 0), "출처", "mvt"));
        db.nudgeRegion(deltas(effects.get("region")));
        if (!who.linked()) {
            return;   // 이름 없는 몸의 자수는 관의 장부에 오르지 못한다
        }
        Integer drain = rules.politics.mandateDrain(str(effects.get("mandate_drain"), "자수"));
        if (drain != null) {
            int mandate = db.addMandate(who.characterId(), drain, today, rules.politics);
            db.logEvent("법명분", "character", String.valueOf(who.characterId()), "gwan_gun", "자수",
                    Map.of("가산", drain, "법명분", mandate, "출처", "mvt",
                            "구간", String.valueOf(rules.politics.mandateEffect(mandate))));
        }
    }

    /** 무명의 의뢰 — <b>결말의 이름만 온다.</b> 값은 봇이 등록부에서 읽는다 (등록부는 하나다) */
    private void populaceQuest(Map<String, Object> data, int today) throws Exception {
        String rule = str(data.get("rule"), "");
        String outcome = str(data.get("outcome"), "");
        Killer who = killer(data, "player_uuid", "player_name");
        db.logEvent("무명의뢰", who.actorType(), who.actorId(), "populace", rule,
                Map.of("사건", str(data.get("event"), "발생"), "결말", outcome,
                        "발주", str(data.get("issuer"), ""), "대상", str(data.get("victim"), ""),
                        "출처", "mvt"));
        if (outcome.isBlank()) {
            return;   // 발생만 했다 — 세계는 아직 아무것도 갚지 않았다
        }
        Map<String, Integer> region = rules.populaceQuestRegion(rule, outcome);
        if (!region.isEmpty()) {
            db.nudgeRegion(region);
        }
        // ★ 살해자가 유족의 의뢰를 완수했다 — 혈교는 그것을 자격으로 읽는다
        if (Boolean.TRUE.equals(data.get("irony")) && who.linked()) {
            int blood = rules.populaceQuestIrony(rule);
            if (blood != 0) {
                int favor = db.addFavor("hyeolgyo", who.characterId(), blood,
                        rules.factions.favorMax(), today, rules.factions);
                db.logEvent("혈채_세력", "character", String.valueOf(who.characterId()),
                        "faction", "hyeolgyo",
                        Map.of("입력", "무명_유족_기만", "가산", blood, "우호", favor, "출처", "mvt"));
            }
        }
    }

    private void npcDeath(Map<String, Object> data, int today) throws Exception {
        Map<String, Object> effects = effects("npc_death");
        String registry = str(data.get("registry"), "populace");
        boolean nameless = !"cheongha_npcs".equals(registry);
        String npcId = str(data.get("npc_id"), "unknown");
        String npcName = str(data.get("npc_name"), npcId);
        String job = str(data.get("job"), "행인");
        String place = str(data.get("place"), "market");
        String body = str(data.get("body"), "즉시_발견");
        String cause = str(data.get("cause"), "사건_피살");
        int witnesses = num(data.get("witnesses"), 0);
        int band = witnessBand(effects, witnesses);

        String key = nameless ? "무명:" + npcId : npcId;
        int tier = nameless ? 0 : rules.npcTier(npcId);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("사망일", today);
        state.put("사인", cause);
        state.put("목격", band);
        state.put("시신", body);
        state.put("이름", npcName);
        state.put("생업", job);
        state.put("출처", "mvt");
        db.killNpc(key, tier, state);

        Killer killer = killer(data, "killer_uuid", "killer_name");
        db.logEvent("사망", killer.actorType(), killer.actorId(), "npc", key,
                Map.of("사인", cause, "목격", witnesses, "시신", body, "등록부", registry,
                        "장소", place, "출처", "mvt"));

        // 소문 — 강도·정확도·지연은 npc_death.yml 의 행렬이 정한다 (여기서 발명하지 않는다)
        Deaths.RumorSpec spec = rules.deaths.rumorFor(band, body, cause);
        // 목격자가 있어야 가해자의 이름이 소문에 실린다 (subject_needs_witness) —
        // 그 이름이 실려야만 세력이 그를 주목한다. 아무도 못 봤으면 죽음만 남고 범인은 남지 않는다.
        boolean subjectKiller = truthy(rumor("npc_death").get("subject_is_killer"))
                && witnesses >= 1 && killer.named();
        String truth = subjectKiller
                ? killer.name() + josa(killer.name(), "이", "가") + " " + job + " " + npcName
                        + josa(npcName, "을", "를") + " 베었다"
                : job + " " + npcName + josa(npcName, "이", "가") + " 죽었다 ("
                        + cause.replace('_', ' ') + ")";
        if (spec.intensity() > 0) {
            game.spread(group(nameless ? "무명사망" : "npc사망", key, today), truth,
                    subjectKiller ? killer.name() : npcName,
                    subjectKiller ? killer.characterId() : null,
                    tags("npc_death"), spec.intensity(), spec.accuracy(),
                    network(place), today + spec.delayDays());
        } else {
            db.logEvent("실종", "world", "mvt", "npc", key,
                    Map.of("사유", "목격 없음 + 시신 은닉 — 소문 미생성",
                            "발화일", today + rules.deaths.missingPersonDays()));
        }

        // 지역의 냉기 — 복수자 없는 죽음의 유일한 대가 (populace.yml death.region_delta)
        db.nudgeRegion(deltas(effects.get("region")));

        // ★ 민심 부채 — 무명의 죽음이 깎은 민심은 **자연 회복에서 제외된다**
        //   (npc_death populace_layer.civil_debt: "대가는 복수가 아니라 지역의 냉기다").
        //   회복이 이것마저 되돌리면, 사람을 죽여도 열흘만 기다리면 없던 일이 된다.
        if (nameless) {
            int debt = Integer.parseInt(db.getMeta("지역:민심부채").orElse("0"));
            db.setMeta("지역:민심부채", String.valueOf(debt
                    + Math.abs(deltas(effects.get("region")).getOrDefault("민심", 0))));
        }

        // 관(官)을 죽였는가 — 법명분은 npc_death.yml succession 등록부만이 정한다
        Integer mandateDelta = nameless ? null : rules.deaths.authorityMandateOf(npcId);
        if (mandateDelta != null && killer.linked()) {
            int mandate = db.addMandate(killer.characterId(), mandateDelta, today, rules.politics);
            db.logEvent("법명분", "character", String.valueOf(killer.characterId()), "npc", key,
                    Map.of("가산", mandateDelta, "법명분", mandate, "출처", "mvt",
                            "구간", String.valueOf(rules.politics.mandateEffect(mandate))));
        }

        // ★ 혈채 — 무고의 장부. 이 한 줄이 없으면 살인 1건과 10건이 같아진다
        bloodDebt(killer, registry, npcId, job, witnesses, body, cause, spec.accuracy(),
                flags(data), group(nameless ? "무명사망" : "npc사망", key, today), today);
    }

    /**
     * ★ 혈채(血債) — 이 죽음은 얼마인가.
     *
     * <p><b>악행 포인트가 아니다.</b> 도적을 베어도(bandit_slain), 비무에서 죽여도(sparring),
     * 관인을 죽여도(법명분으로 간다) 0이다. 빚은 <b>갚을 수 없는 자를 죽였을 때</b>만 생긴다.
     *
     * <p>장부는 둘이다: <b>암혈채</b>는 노출과 무관하게 자라고 <b>감쇠하지 않는다</b> (몸이 안다).
     * <b>현혈채</b>만 노출·정확도 배수를 먹는다 — 아무도 못 보고 시신을 감췄으면 세계는 모른다.
     * <b>완전 범죄는 가능하다. 다만 몸은 속일 수 없다.</b>
     *
     * <p>아직 접합되지 않은 몸의 빚은 {@code mc:<uuid>} 원장에 쌓이고, 접합하는 순간
     * 그 사람의 이름으로 <b>병합된다</b> (GameListener.linkAccount).
     */
    private void bloodDebt(Killer killer, String registry, String npcId, String job, int witnesses,
                           String body, String cause, int accuracy, List<String> flags,
                           String rumorGroup, int today) throws Exception {
        BloodDebt bd = rules.bloodDebt;
        boolean registered = "cheongha_npcs".equals(registry);
        String category = bd.classify(registry,
                registered ? rules.npcFaction(npcId) : null,
                registered ? rules.npcRole(npcId) : job,
                registered ? rules.npcTier(npcId) : 0, cause);

        String subject = killer.linked() ? "character:" + killer.characterId()
                : killer.uuid() != null ? "mc:" + killer.uuid() : "미상의_살인마";
        double floor = db.bloodDebt(subject).exposureFloor();   // ★ B6 — 마공은 숨지 못한다
        BloodDebt.Charge charge = bd.charge(category, flags, witnesses, body, accuracy,
                rules.rumors.band(accuracy), floor);
        if (!charge.any()) {
            return;   // 무장 상대·관인·비무·병사 — 빚이 아니다 (그리고 그것이 이 축의 정직함이다)
        }
        Db.Debt debt = db.addBloodDebt(subject, killer.characterId(), charge.hidden(),
                charge.known(), charge.publicKill(), today);
        db.logEvent("혈채", killer.actorType(), killer.actorId(), "npc", npcId,
                Map.of("분류", category, "건당", charge.base(), "배수", charge.multiplier(),
                        "노출", charge.exposure(), "노출배수", charge.exposureMult(),
                        "정확도밴드", charge.band(), "암혈채", debt.hidden(),
                        "현혈채", bd.decayedKnown(debt.knownRaw(), debt.publicCount(),
                                debt.knownDay(), today),
                        "출처", "mvt"));
        if (killer.linked()) {
            // 사다리 — 수배·현상금 · 정파의 등돌림 · 명분 · 법명분. 세계가 한 층씩 그를 본다
            game.bloodDebtLadder(killer.characterId(), killer.name(), rumorGroup, today);
        }
    }

    /**
     * 배수 플래그 (의식_살인 ×2.0 · 무력한_자 ×1.5 · 항복자_살해 ×1.5 — 곱해진다).
     * 마크는 아직 이것을 보내지 않는다 (흡성 시전·포박 상태가 MVT 에 없다) — 오면 그대로 먹는다.
     * (등록부의 자리: world_bridge.yml npc_death.payload.flags)
     */
    private static List<String> flags(Map<String, Object> data) {
        List<String> out = new ArrayList<>();
        for (Object f : list(data.get("flags"))) {
            out.add(String.valueOf(f));
        }
        return out;
    }

    // ─── bandit_slain / beast_slain — 벤 자의 이름이 강호에 돈다 ───

    private void slain(Map<String, Object> data, int today, boolean bandit) throws Exception {
        String kind = bandit ? "bandit_slain" : "beast_slain";
        Map<String, Object> effects = effects(kind);
        Map<String, Object> rumor = rumor(kind);
        String foeId = str(data.get("foe_id"), "unknown");
        String foeName = str(data.get("foe_name"), foeId);
        String role = str(data.get("role"), "졸개");
        String place = str(data.get("place"), "north_road");
        int witnesses = num(data.get("witnesses"), 0);
        Killer killer = killer(data, "killer_uuid", "killer_name");

        int intensity;
        if (bandit) {
            intensity = num(map(rumor.get("intensity_by_role")).get(role), 1);
        } else {
            List<Object> rare = list(rumor.get("rare_foes"));
            intensity = rare.contains(foeId) ? num(rumor.get("rare_intensity"), 3)
                    : num(rumor.get("intensity"), 1);
        }
        db.logEvent(bandit ? "토벌" : "사냥", killer.actorType(), killer.actorId(),
                "npc", foeId, Map.of("이름", foeName, "역할", role, "장소", place,
                        "목격", witnesses, "출처", "mvt"));

        int min = num(rumor.get("min_intensity_to_spread"), 1);
        if (intensity >= min && killer.named()) {
            game.spread(group(bandit ? "토벌" : "사냥", foeId, today),
                    killer.name() + josa(killer.name(), "이", "가") + " " + foeName
                            + josa(foeName, "을", "를") + " 베었다", killer.name(),
                    killer.characterId(), tags(kind), intensity,
                    rules.initialAccuracy(str(rumor.get("accuracy_kind"), "직접_목격")),
                    network(place), today);
        }
        // 도적이 줄면 길이 안전해진다 (두목이면 두 배 — 등록부가 정한다)
        Map<String, Integer> region = deltas(effects.get("region"));
        if (!region.isEmpty()) {
            int scale = bandit ? num(map(effects.get("scale_by_role")).get(role), 1) : 1;
            Map<String, Integer> scaled = new LinkedHashMap<>();
            region.forEach((k, v) -> scaled.put(k, v * scale));
            db.nudgeRegion(scaled);
        }
    }

    // ─── qi_manifested — 소문의 씨앗 ───

    /**
     * "그자가 검기를 뿜었다더라."
     *
     * <p>격은 지금까지 마크 안에서만 빛났다. 강기를 터뜨려도 강호는 그를 몰랐다.
     * 이제 <b>본 자가 있으면</b> 그것은 소문이 되고, 소문이 세력의 조직 채널에 닿으면
     * 정파도 사파도 그의 이름을 적기 시작한다 (faction_standing.attention).
     * 아무도 없는 산속의 검기는 여전히 아무것도 아니다 — 무공은 목격될 때 비로소 강호의 일이 된다.
     */
    private void qiManifested(Map<String, Object> data, int today) throws Exception {
        Map<String, Object> rumor = rumor("qi_manifested");
        String grade = str(data.get("grade"), "발경");
        String place = str(data.get("place"), "north_road");
        int witnesses = num(data.get("witnesses"), 0);
        Killer who = killer(data, "player_uuid", "player_name");

        int intensity = num(map(rumor.get("intensity_by_grade")).get(grade), 1);
        db.logEvent("격_목격", who.actorType(), who.actorId(), "place", place,
                Map.of("격", grade, "목격", witnesses, "강도", intensity, "출처", "mvt"));

        if (witnesses < num(rumor.get("min_witnesses"), 1)
                || intensity < num(rumor.get("min_intensity_to_spread"), 1)
                || !who.named()) {
            return;   // 본 자가 없다 — 세계는 조용하다
        }
        game.spread(group("격", who.name() + ":" + grade, today),
                who.name() + josa(who.name(), "이", "가") + " " + grade
                        + josa(grade, "을", "를") + " 뿜었다", who.name(), who.characterId(),
                tags("qi_manifested"), intensity,
                rules.initialAccuracy(str(rumor.get("accuracy_kind"), "직접_목격")),
                network(place), today);
    }

    // ─── sparring — 죽이지 않는 싸움 ───

    private void sparring(Map<String, Object> data, int today) throws Exception {
        Map<String, Object> rumor = rumor("sparring");
        Map<String, Object> effects = effects("sparring");
        Killer winner = killer(data, "winner_uuid", "winner_name");
        String loser = str(data.get("loser_name"), "이름 없는 무인");
        String reason = str(data.get("reason"), "중상");
        String place = str(data.get("place"), "market");
        int witnesses = num(data.get("witnesses"), 0);

        db.logEvent("비무", winner.actorType(), winner.actorId(), "character", loser,
                Map.of("승", winner.name(), "패", loser, "사유", reason, "목격", witnesses,
                        "장소", place, "출처", "mvt"));

        int intensity = witnesses >= num(effects.get("witness_many_min"), 2)
                ? num(rumor.get("public_intensity"), 2) : num(rumor.get("intensity"), 1);
        if (intensity < num(rumor.get("min_intensity_to_spread"), 1) || !winner.named()) {
            return;
        }
        game.spread(group("비무", winner.name() + ":" + loser, today),
                winner.name() + josa(winner.name(), "이", "가") + " " + loser
                        + josa(loser, "을", "를") + " 비무에서 눌렀다 (" + reason + ")",
                winner.name(), winner.characterId(), tags("sparring"), intensity,
                rules.initialAccuracy(str(rumor.get("accuracy_kind"), "직접_목격")),
                network(place), today);
    }

    // ══════════════ 되먹임 — 장부가 몸에게 (스냅숏: 최신이 이긴다) ══════════════

    private void publishQuietly() {
        try {
            publish();
        } catch (Exception e) {
            System.err.println("세계 상태 발행 실패: " + e.getMessage());
        }
    }

    /**
     * 지금 세계의 상태를 한 장으로 찍어 마크에 건넨다 — 원자적 교체(temp → move)라 반쪽을 읽을 일이 없다.
     *
     * <p>여기 담기는 것은 셋이다:
     *   ① <b>소문</b>  살아 있는 태그 → populace.yml reactions 키 (도적 소문이 돌면 나무꾼이 산길을 피한다)
     *   ② <b>수배</b>  법명분 게이지 (관을 죽인 자에게 마을 관졸이 등을 돌린다)
     *   ③ <b>우호</b>  세력별 favor (산문의 문이 열리고 닫힌다)
     */
    Path publish() throws Exception {
        int today = db.worldDay();
        Set<String> tags = db.liveRumorTags(today, rules.rumors.decayEveryDays());

        Map<String, Object> feedback = RulesConfig.section(cfg, "feedback");
        Set<String> reactions = new LinkedHashSet<>();
        map(feedback.get("reaction_map")).forEach((reaction, want) -> {
            for (Object w : list(want)) {
                if (tags.contains(String.valueOf(w))) {
                    reactions.add(reaction);
                    return;
                }
            }
        });

        Map<String, Object> wantedCfg = map(feedback.get("wanted"));
        Map<String, Object> wanted = new LinkedHashMap<>();
        Map<String, Object> favor = new LinkedHashMap<>();
        Map<String, Object> links = new LinkedHashMap<>();
        Map<String, Object> bounty = new LinkedHashMap<>();
        Map<String, Object> sheets = new LinkedHashMap<>();   // ★ 시트 — 마크의 몸에 실릴 캐릭터
        int bountyAmount = rules.price("의뢰_보수", rules.bloodDebt.bountyRef());
        for (Map.Entry<String, Long> body : db.linkedBodies().entrySet()) {
            long chId = body.getValue();
            int mandate = db.mandate(chId, today, rules.politics);
            if (mandate > 0) {
                wanted.put(body.getKey(), mandate);
            }
            Map<String, Object> mine = new LinkedHashMap<>();
            for (Db.Standing s : db.standings(chId, today, rules.factions)) {
                if (s.favor() != 0) {
                    mine.put(s.faction(), s.favor());
                }
            }
            if (!mine.isEmpty()) {
                favor.put(body.getKey(), mine);
            }
            // ★ 신원 — 마크가 "나는 누구인가"를 읽는 곳 (접합된 몸만 여기 있다)
            db.findCharacterById(chId).ifPresent(ch -> {
                links.put(body.getKey(),
                        Map.of("character_id", chId, "name", String.valueOf(ch.get("name")),
                                "realm", String.valueOf(ch.get("realm"))));
                // ★★ 시트 — 경지·능력치·심법·내공·주무공·숙련·마크·소지금.
                //    이것이 없어서 마크에는 캐릭터가 없었다 (realm 은 "이류"로 박혀 있었고 능력치는 0.0).
                //    갓 접속한 몸이 제 급의 산적보다 약했던 이유가 이 한 칸의 부재다.
                sheets.put(body.getKey(), game.mvtSheet(ch));
            });
            // ★ 현상금 — 혈채가 문턱을 넘으면 관이 방을 붙인다.
            //   ★★ 혈채 수치 자체는 절대 안 내려간다 (visibility: 내부) — 내려가는 것은 세계의 반응뿐이다
            Db.Debt debt = db.bloodDebtOf(chId);
            int known = rules.bloodDebt.decayedKnown(debt.knownRaw(), debt.publicCount(),
                    debt.knownDay(), today);
            if (known >= rules.bloodDebt.bountyMin()) {
                bounty.put(body.getKey(), bountyAmount);
            }
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("world_day", today);
        snapshot.put("generated_at", System.currentTimeMillis());
        snapshot.put("rumor_tags", List.copyOf(tags));
        snapshot.put("populace_reactions", List.copyOf(reactions));
        snapshot.put("region", db.region());
        snapshot.put("wanted", wanted);
        snapshot.put("favor", favor);
        snapshot.put("links", links);
        snapshot.put("bounty", bounty);
        snapshot.put("sheet", sheets);   // ★ 마크의 몸에 실릴 캐릭터 (절대값 — 마크는 이것으로 덮어쓴다)
        snapshot.put("thresholds", Map.of(
                "wanted", num(wantedCfg.get("gauge_min"), 8),
                "disavowal", num(wantedCfg.get("disavowal_min"), 10)));

        Files.createDirectories(bridgeDir);
        Path tmp = snapshotFile.resolveSibling(snapshotFile.getFileName() + ".tmp");
        Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot),
                StandardCharsets.UTF_8);
        Files.move(tmp, snapshotFile, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        return snapshotFile;
    }

    // ══════════════ 등록부 판독 ══════════════

    private Map<String, Object> event(String kind) {
        return map(RulesConfig.section(cfg, "events").get(kind));
    }

    private Map<String, Object> effects(String kind) {
        return map(event(kind).get("effects"));
    }

    private Map<String, Object> rumor(String kind) {
        return map(effects(kind).get("rumor"));
    }

    private List<String> tags(String kind) {
        List<String> out = new ArrayList<>();
        for (Object t : list(rumor(kind).get("tags"))) {
            out.add(String.valueOf(t));
        }
        return out;
    }

    /** 목격자 수 → npc_death.yml killer_response.rumor_matrix 의 witness 칸 (0 없음 / 1 소수 / 2 다수) */
    private int witnessBand(Map<String, Object> effects, int witnesses) {
        int many = num(effects.get("witness_many_min"), 2);
        if (witnesses <= 0) {
            return 0;
        }
        return witnesses >= many ? 2 : 1;
    }

    private Map<String, Integer> deltas(Object raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        map(raw).forEach((k, v) -> {
            if (v instanceof Number n) {
                out.put(k, n.intValue());
            }
        });
        return out;
    }

    /** 소문군 키 — 같은 사건의 망별 도달을 묶는다 (세력 중복 가산 금지의 기준) */
    private static String group(String kind, Object subject, int day) {
        return kind + ":" + subject + ":" + day;
    }

    /**
     * 발원망 — 마크의 자리(populace.yml places)를 소문망의 장소 키로 옮긴 뒤 rumor.yml 에 묻는다.
     * 이 번역이 없으면 산길에서 난 일이 장터의 소문이 된다 (발원망이 곧 그 소문이 처음 도는 곳이다).
     */
    private String network(String place) {
        Object mapped = map(cfg.get("place_map")).get(place);
        return rules.originNetwork(mapped == null ? place : String.valueOf(mapped));
    }

    /**
     * 가해자 — 마크의 몸을 봇의 장부에 붙여 본다.
     * 링크가 없으면 이름만 남는다 (주체 없는 소문 = 세계의 배경음. 세력은 그를 주목하지 않는다).
     */
    private Killer killer(Map<String, Object> data, String uuidKey, String nameKey) throws Exception {
        String uuid = data.get(uuidKey) == null ? null : String.valueOf(data.get(uuidKey));
        String name = data.get(nameKey) == null ? null : String.valueOf(data.get(nameKey));
        if (uuid == null) {
            return new Killer(null, name, null);
        }
        db.linkMvt(uuid, name == null ? uuid : name, null);   // 본 몸은 일단 등록한다 (이름 갱신)
        Optional<Long> chId = db.characterOfMc(uuid);
        return new Killer(uuid, name, chId.orElse(null));
    }

    /** 마크의 몸 + (있다면) 봇의 캐릭터. characterId 가 있어야 세력이 그를 셈한다 */
    private record Killer(String uuid, String name, Long characterId) {

        boolean linked() {
            return characterId != null;
        }

        boolean named() {
            return name != null && !name.isBlank();
        }

        String actorType() {
            return linked() ? "character" : "world";
        }

        String actorId() {
            return linked() ? String.valueOf(characterId) : (named() ? name : "mvt");
        }
    }

    // ─── 자잘한 판독 도우미 ───

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    private static int num(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static String str(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equals(String.valueOf(value));
    }

    /**
     * 조사(助詞) — 받침이 있으면 앞엣것, 없으면 뒤엣것 ("검기를 뿜었다" / "발경을 뿜었다").
     * 소문은 사람의 입에서 나온 문장이다 — "검기을(를)"이라고 말하는 사람은 없다.
     *
     * <p>한글이 아니면(영문 닉) 받침을 따질 수 없으므로 집안의 관행대로 병기한다: "Lindydone이(가)".
     */
    private static String josa(String word, String withBatchim, String without) {
        if (word == null || word.isBlank()) {
            return withBatchim + "(" + without + ")";
        }
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return withBatchim + "(" + without + ")";   // 영문 닉·숫자 — 관행대로 병기
        }
        return (last - 0xAC00) % 28 == 0 ? without : withBatchim;
    }
}
