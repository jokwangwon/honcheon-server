package com.honcheon.bot;

import com.honcheon.core.rules.JudgmentEngine;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 봇 베타의 심장 — 생성 문답 → 서장(영속화) → 출도 → 청하현 사냥·비무.
 * 규칙: 판정은 embed 공개 (interface_decision), 서사는 LLM 렌더러(폴백 = Narration 템플릿),
 *       수치는 엔진만 정한다. 서장 진행은 scenes 테이블로 재시작을 견딘다.
 */
public final class GameListener extends ListenerAdapter {

    private static final List<String> STATS = List.of("근력", "민첩", "체력", "내공", "감각", "화술", "지혜");
    private static final Color INK = new Color(0x2B2B2B);
    private static final Color BLOOD = new Color(0x8B2E2E);
    private static final String REGION_KEY = "지역채널:청하현";

    private final Rules rules;
    private final Db db;
    private final LlmRenderer renderer;
    private final Random dice = new Random();

    private final Map<String, Creation> creations = new ConcurrentHashMap<>();
    private final Map<Long, Seojang> seojangs = new ConcurrentHashMap<>();

    public GameListener(Rules rules, Db db, LlmRenderer renderer) {
        this.rules = rules;
        this.db = db;
        this.renderer = renderer;
    }

    // ─── 슬래시 명령 ───

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        try {
            switch (String.valueOf(event.getSubcommandName())) {
                case "시작" -> startCreation(event);
                case "원장", "정보" -> showSheet(event);   // 원장 = 하위호환 (terminology)
                case "사냥" -> startHunt(event);
                case "비무" -> startDuel(event);
                case "수련" -> train(event);
                case "사사" -> apprentice(event);
                case "의뢰" -> questBoard(event);
                case "대화" -> talkToNpc(event);
                case "탐방" -> visitShrine(event);
                case "운기" -> circulate(event);
                case "지역등록" -> registerRegion(event);
                case "정산" -> settleDay(event);
                default -> event.replyEmbeds(help()).setEphemeral(true).queue();
            }
        } catch (Exception e) {
            event.reply("오류: " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    private void startCreation(SlashCommandInteractionEvent event) throws Exception {
        if (db.findCharacter(event.getUser().getId()).isPresent()) {
            event.reply("이미 살아 있는 캐릭터가 있다 — `/혼천 정보`로 확인하라. (계정당 하나, 죽음만이 끝낸다)")
                    .setEphemeral(true).queue();
            return;
        }
        if (!(event.getChannel() instanceof TextChannel)) {
            event.reply("서버의 일반 채널에서 시작하라 — 서장 스레드가 여기서 열린다.").setEphemeral(true).queue();
            return;
        }
        Creation c = new Creation();
        creations.put(event.getUser().getId(), c);
        event.replyEmbeds(questionEmbed(0))
                .addComponents(ActionRow.of(questionButtons(0)))
                .setEphemeral(true).queue();
    }

    private void showSheet(SlashCommandInteractionEvent event) throws Exception {
        var found = db.findCharacter(event.getUser().getId());
        if (found.isEmpty()) {
            event.reply("캐릭터가 없다 — `/혼천 시작`으로 만들어라.").setEphemeral(true).queue();
            return;
        }
        Map<String, Object> ch = found.get();
        @SuppressWarnings("unchecked")
        Map<String, Object> sheet = (Map<String, Object>) ch.get("sheet");
        EmbedBuilder eb = new EmbedBuilder().setColor(INK)
                .setTitle("정보 — " + ch.get("name"))
                .setDescription(sheet.get("나이") + "세 " + sheet.get("연령대") + " · " + ch.get("realm")
                        + " · " + sheet.get("집안") + " · 성향 " + sheet.get("성향")
                        + "\n" + ch.get("status") + " · " + ch.get("location"));
        StringBuilder stats = new StringBuilder();
        @SuppressWarnings("unchecked")
        Map<String, Integer> attr = (Map<String, Integer>) sheet.get("능력치");
        attr.forEach((k, v) -> stats.append(k).append(' ').append(v).append("  "));
        eb.addField("능력치", stats.toString(), false);
        eb.addField("소지금", ch.get("wallet") + "문", true);
        eb.addField("발단", String.valueOf(sheet.get("발단")), true);
        double hwahu = ((Number) sheet.getOrDefault("화후_원장", 0)).doubleValue();
        eb.addField("수련 누적", String.format("%.2f일치", hwahu), true);
        if (sheet.get("가문_대여") != null) {
            eb.addField("소지품", String.valueOf(sheet.get("가문_대여")), false);
        }
        if (sheet.get("기술") instanceof Map<?, ?> skills && !skills.isEmpty()) {
            StringBuilder sk = new StringBuilder();
            skills.forEach((k, v) -> sk.append(k).append(' ').append(v).append("  "));
            eb.addField("기술", sk.toString(), false);
            // 주력 수련 진행 — 다음 숙련까지 (training 환산표)
            if (sheet.get("기술_수련") instanceof Map<?, ?> prog) {
                StringBuilder pr = new StringBuilder();
                prog.forEach((k, v) -> {
                    Object levelRaw = ((Map<?, ?>) skills).get(k);
                    int level = levelRaw instanceof Number n ? n.intValue() : 0;
                    int cost = rules.progression.skillLevelUpDays(level);
                    pr.append(k).append(String.format(" %.1f", ((Number) v).doubleValue()))
                            .append(cost > 0 ? "/" + cost + "일" : "일 (환산표 밖 — 수련만으론 불가)")
                            .append("  ");
                });
                if (!pr.isEmpty()) {
                    eb.addField("수련 진행", pr.toString(), false);
                }
            }
        }
        int marks = ((Number) sheet.getOrDefault("실전_마크", 0)).intValue();
        if (marks > 0) {
            eb.addField("실전 마크", String.valueOf(marks), true);
        }
        if (sheet.get("심법") != null) {
            boolean opened = "개화".equals(sheet.get("단전"));
            eb.addField("심법", sheet.get("심법") + (opened ? " · 단전 개화" : " · 단전 미개화"), true);
            if (opened) {
                double naegong = naegongOf(((Number) sheet.getOrDefault("축기_원장", 0)).doubleValue());
                eb.addField("내공", hwahuLabel(naegong) + " · 내력 " + Math.round(naegong * 3), true);
            }
        }
        event.replyEmbeds(eb.build()).setEphemeral(true).queue();
    }

    private MessageEmbed help() {
        return new EmbedBuilder().setColor(INK).setTitle("혼천 — 무협 텍스트 RPG")
                .setDescription("`/혼천 시작` 캐릭터 생성 (유년의 기억 5문항 → 운명이 나머지를 정한다)\n"
                        + "`/혼천 정보` 내 캐릭터 정보 (`원장`도 동작)\n"
                        + "`/혼천 사냥` 청하현 뒷산 사냥 — 수련과 생계 (출도 후, 지역 채널에서)\n"
                        + "`/혼천 비무 @상대` 비무 신청 — 양측 2d6 대립 판정 (출도 후)\n"
                        + "`/혼천 수련` 기초 단련 — 하루 한 번, 수련 +1일치 (출도 후)\n"
                        + "`/혼천 사사` 곽진에게 무공을 청한다 — 무공 백지만 (과제→시험→입문)\n"
                        + "`/혼천 의뢰` 소연의 게시판 — 오늘의 의뢰 3건, 수주 1건씩 (출도 후)\n"
                        + "`/혼천 대화` 청하현 사람에게 말 걸기 — 자유 입력, 정보를 캐면 판정 (출도 후)\n"
                        + "`/혼천 탐방` 폐사당을 살핀다 — 발품에는 이유가 있다 (출도 후, 하루 한 번)\n"
                        + "`/혼천 운기` 심법으로 기를 돌린다 — 하루 한 번, 축기 (심법 보유자)\n"
                        + "`/혼천 지역등록` 이 채널을 청하현으로 등록 (서버 관리자)\n"
                        + "`/혼천 정산` 세계일 +1 (서버 관리자 — 자정에는 자동)\n"
                        + "판정은 공개(2d6), 서사는 스레드에서. 죽음은 비가역 — 계정당 한 삶.")
                .build();
    }

    private void registerRegion(SlashCommandInteractionEvent event) throws Exception {
        if (event.getMember() == null || !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("서버 관리 권한이 필요하다.").setEphemeral(true).queue();
            return;
        }
        if (!(event.getChannel() instanceof TextChannel)) {
            event.reply("일반 텍스트 채널에서 등록하라.").setEphemeral(true).queue();
            return;
        }
        db.setMeta(REGION_KEY, event.getChannel().getId());
        event.reply("이 채널이 **청하현**으로 등록됐다 — 출도한 이들은 여기서 `/혼천 사냥` `/혼천 비무`를 쓴다.").queue();
    }

    // ─── 버튼 라우팅 ───

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        try {
            String[] id = event.getComponentId().split(":");
            switch (id[0]) {
                case "ct" -> onTestAnswer(event, Integer.parseInt(id[1]), id[2]);
                case "tn" -> onTurnChoice(event, Integer.parseInt(id[1]), Integer.parseInt(id[2]));
                case "ht" -> onHuntChoice(event, Integer.parseInt(id[1]), Integer.parseInt(id[2]), id[3]);
                case "bm" -> onDuelAnswer(event, "ok".equals(id[1]), id[2], id[3]);
                case "qa" -> onQuestAccept(event, id[1], id[2]);
                case "qp" -> onQuestPerform(event, id[1], Integer.parseInt(id[2]), id[3]);
                case "gs" -> onMealChoice(event, "share".equals(id[1]), id[2]);
                default -> event.deferEdit().queue();
            }
        } catch (Exception e) {
            event.reply("오류: " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    // ─── 생성: 유년의 기억 5문항 (disposition_test.yml) ───

    private void onTestAnswer(ButtonInteractionEvent event, int qIdx, String choice) throws Exception {
        Creation c = creations.get(event.getUser().getId());
        if (c == null) {
            event.editMessage("세션이 만료됐다 — `/혼천 시작`으로 다시.").setComponents().queue();
            return;
        }
        score(c, qIdx, choice);
        c.answers.add(choice);
        int next = qIdx + 1;
        if (next < rules.questions().size()) {
            event.editMessageEmbeds(questionEmbed(next))
                    .setComponents(ActionRow.of(questionButtons(next))).queue();
            return;
        }
        // 문답 종료 — 나머지는 운명이 정한다 (F15 opt-out)
        creations.remove(event.getUser().getId());
        Born born = birth(event.getUser().getId(), event.getUser().getEffectiveName(), c);
        event.editMessageEmbeds(birthEmbed(born.ch)).setComponents().queue();
        openSeojang(event, born);
    }

    @SuppressWarnings("unchecked")
    private void score(Creation c, int qIdx, String choice) {
        Map<String, Object> q = rules.questions().get(qIdx);
        Map<String, Object> choices = (Map<String, Object>) q.get("choices");
        Map<String, Object> picked = (Map<String, Object>) choices.get(choice);
        c.scores.merge((String) picked.get("primary"), 2, Integer::sum);
        Object secondary = picked.get("secondary");
        if (secondary != null) {
            c.scores.merge((String) secondary, 1, Integer::sum);
        }
    }

    @SuppressWarnings("unchecked")
    private MessageEmbed questionEmbed(int qIdx) {
        Map<String, Object> q = rules.questions().get(qIdx);
        Map<String, Object> choices = (Map<String, Object>) q.get("choices");
        StringBuilder body = new StringBuilder();
        choices.forEach((key, v) ->
                body.append("**").append(key).append(".** ")
                        .append(((Map<String, Object>) v).get("text")).append('\n'));
        return new EmbedBuilder().setColor(INK)
                .setTitle("유년의 기억 " + (qIdx + 1) + "/" + rules.questions().size())
                .setDescription(q.get("scene") + "\n\n" + body)
                .setFooter("답은 성향 추천일 뿐 — 그 어긋남 자체가 캐릭터다").build();
    }

    @SuppressWarnings("unchecked")
    private List<Button> questionButtons(int qIdx) {
        Map<String, Object> q = rules.questions().get(qIdx);
        Map<String, Object> choices = (Map<String, Object>) q.get("choices");
        List<Button> buttons = new ArrayList<>();
        choices.keySet().forEach(key -> buttons.add(Button.secondary("ct:" + qIdx + ":" + key, key)));
        return buttons;
    }

    // ─── 운명 생성 — 성향 확정 + 집안·나이·발단 무작위 (opt-out 기본값) ───

    private Born birth(String discordId, String name, Creation c) throws Exception {
        int max = c.scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> top = c.scores.entrySet().stream()
                .filter(e -> e.getValue() == max).map(Map.Entry::getKey).toList();
        String disposition = String.join("·", top);   // wide_tie: 다면성은 오류가 아니라 결과다

        List<String> familyKeys = new ArrayList<>(rules.families().keySet());
        String family = familyKeys.get(dice.nextInt(familyKeys.size()));
        // 집안별 발단 풀 — 건재한 집에 재난형 발단은 모순 (incident_pool 명시 시 그 안에서만)
        // F27: 풀이 없는 집안도 family_only 표기된 전용 발단(수행_파견)은 뽑지 않는다
        @SuppressWarnings("unchecked")
        Map<String, Object> familyCfg = (Map<String, Object>) rules.families().get(family);
        @SuppressWarnings("unchecked")
        List<String> incidentPool = familyCfg != null && familyCfg.get("incident_pool") instanceof List<?>
                ? (List<String>) familyCfg.get("incident_pool")
                : defaultIncidentPool(family);
        String incident = incidentPool.get(dice.nextInt(incidentPool.size()));
        String bracket = dice.nextBoolean() ? "유년" : "소년";

        Map<String, Integer> attrs = allocate(top.get(0), bracket);
        @SuppressWarnings("unchecked")
        Map<String, Object> bracketCfg = (Map<String, Object>) rules.ageBrackets().get(bracket);
        @SuppressWarnings("unchecked")
        List<Integer> ageRange = (List<Integer>) bracketCfg.get("age_range");
        int age = ageRange.get(0) + dice.nextInt(ageRange.get(1) - ageRange.get(0) + 1);
        int wallet = rules.startingMoney(bracket, family, dice);

        Map<String, Object> sheet = new LinkedHashMap<>();
        sheet.put("성향", disposition);
        sheet.put("집안", family.replace('_', ' '));
        if ("무가의_자식".equals(family)) {
            // armory 시작 대여 — 판정 보정 0 (equipment.yml), 팔거나 잃으면 support 단계 입력
            sheet.put("가문_대여", "정련급 가문 검 (가문 소유 — 잃으면 문책)");
        }
        // 집안 grants의 기술 축 배선 — 몰락_무가 검법0 = "아무 무공 입문 (숙련 0)" 승급 요건 충족
        if (familyCfg != null && familyCfg.get("grants") instanceof Map<?, ?> grants
                && grants.get("기술") instanceof Map<?, ?> granted) {
            sheet.put("기술", new LinkedHashMap<>((Map<String, Object>) granted));
        }
        sheet.put("연령대", bracket);
        sheet.put("나이", age);
        sheet.put("발단", incident.replace('_', ' '));
        sheet.put("능력치", attrs);
        sheet.put("유년의_기억", c.answers);

        long id = db.createCharacter(discordId, name, sheet, wallet);
        db.logEvent("생성", "character", String.valueOf(id),
                Map.of("성향", disposition, "집안", family, "발단", incident, "나이", age));
        return new Born(new Character(id, name, disposition, family, incident, bracket, age, attrs, wallet), sheet);
    }

    /** F27 — 기본 발단 풀: family_only가 없거나 이 집안을 가리키는 발단만 */
    private List<String> defaultIncidentPool(String family) {
        List<String> pool = new ArrayList<>();
        rules.incidents().forEach((key, value) -> {
            Object only = value instanceof Map<?, ?> m ? m.get("family_only") : null;
            if (only == null || only.equals(family)) {
                pool.add(key);
            }
        });
        return pool;
    }

    /** 브래킷 규칙(base·free·cap)대로 배분 — 프리셋의 높은 능력치부터 (player_creation 정합) */
    @SuppressWarnings("unchecked")
    private Map<String, Integer> allocate(String disposition, String bracket) {
        Map<String, Object> bracketCfg = (Map<String, Object>) rules.ageBrackets().get(bracket);
        int base = ((Number) bracketCfg.get("base_value")).intValue();
        int free = ((Number) bracketCfg.get("free_points")).intValue();
        int cap = ((Number) bracketCfg.get("creation_max")).intValue();
        List<Integer> preset = rules.presetStats(disposition);

        Map<String, Integer> attrs = new LinkedHashMap<>();
        STATS.forEach(s -> attrs.put(s, base));
        // 프리셋 값이 큰 순서로 자유 포인트 배분 (상한 내)
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < STATS.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> preset.get(b) - preset.get(a));
        int remaining = free;
        while (remaining > 0) {
            boolean spent = false;
            for (int idx : order) {
                String stat = STATS.get(idx);
                if (remaining > 0 && attrs.get(stat) < cap) {
                    attrs.merge(stat, 1, Integer::sum);
                    remaining--;
                    spent = true;
                }
            }
            if (!spent) {
                break;   // 전 능력치 상한 도달
            }
        }
        return attrs;
    }

    private MessageEmbed birthEmbed(Character ch) {
        return new EmbedBuilder().setColor(BLOOD)
                .setTitle("한 아이가 태어났다 — " + ch.name)
                .setDescription("성향 **" + ch.disposition + "** · " + ch.bracket + " " + ch.age + "세 · **"
                        + ch.family.replace('_', ' ') + "**\n발단: **" + ch.incident.replace('_', ' ')
                        + "** — 나머지는 운명이 정했다.\n서장 스레드가 열렸다 — 그곳에서 이야기가 시작된다.")
                .build();
    }

    // ─── 서장 — 프라이빗 스레드 + 발단 3장면 턴 루프 (scenes 테이블로 재시작 생존) ───

    private void openSeojang(ButtonInteractionEvent event, Born born) {
        TextChannel channel = event.getChannel().asTextChannel();
        channel.createThreadChannel("서장 — " + born.ch.name(), true).queue(thread -> {
            thread.addThreadMember(event.getUser()).queue();
            try {
                db.openScene(channel.getId(), thread.getId(), born.ch.id());
            } catch (Exception e) {
                thread.sendMessage("장면 기록 실패: " + e.getMessage()).queue();
            }
            seojangs.put(thread.getIdLong(), new Seojang(born.ch, born.sheet));
            postScene(thread, born.ch, 0, null);
        });
    }

    /** 재시작 후 첫 버튼 — scenes 테이블에서 서장을 복원한다 (알파 한계 1 해소) */
    @SuppressWarnings("unchecked")
    private Seojang restoreSeojang(long threadId) throws Exception {
        Optional<Long> chId = db.sceneCharacter(String.valueOf(threadId));
        if (chId.isEmpty()) {
            return null;
        }
        var row = db.findCharacterById(chId.get());
        if (row.isEmpty() || !"서장".equals(row.get().get("status"))) {
            return null;
        }
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get().get("sheet"));
        Seojang s = new Seojang(fromDb(row.get()), sheet);
        seojangs.put(threadId, s);
        return s;
    }

    private void onTurnChoice(ButtonInteractionEvent event, int scene, int option) throws Exception {
        Seojang s = seojangs.get(event.getChannel().getIdLong());
        if (s == null) {
            s = restoreSeojang(event.getChannel().getIdLong());
        }
        if (s == null) {
            event.editMessage("이 서장은 이미 끝났거나 기록이 없다 — `/혼천 정보`로 상태를 확인하라.")
                    .setComponents().queue();
            return;
        }
        Scene sc = scenes(s.ch)[scene];
        Choice pick = sc.choices[option];

        int stat = s.ch.attrs().getOrDefault(pick.stat, 2);
        int roll = dice.nextInt(6) + 1 + dice.nextInt(6) + 1;
        JudgmentEngine.Tier tier = rules.judgment.resolve(stat + pick.bonus, roll, sc.resist);
        int margin = stat + pick.bonus + roll - sc.resist;
        db.logEvent("판정", "character", String.valueOf(s.ch.id()),
                Map.of("장면", sc.title, "선택", pick.label, "굴림", roll, "마진", margin, "등급", tier.name()));

        EmbedBuilder result = new EmbedBuilder().setColor(INK)
                .setTitle("판정 — " + pick.label)
                .setDescription("**" + pick.stat + " " + stat + "** + 2d6 = **" + (stat + pick.bonus + roll)
                        + "** vs " + sc.resist + " │ 마진 **" + (margin >= 0 ? "+" : "") + margin
                        + "** → **" + tier.name() + "**");
        event.editMessageEmbeds(event.getMessage().getEmbeds().get(0), result.build()).setComponents().queue();

        ThreadChannel thread = event.getChannel().asThreadChannel();
        int next = scene + 1;
        if (next < scenes(s.ch).length) {
            // 진행 영속화 — 재시작해도 직전 등급의 이음새가 유지된다
            s.sheet.put("서장_직전등급", tier.name());
            db.updateCharacter(s.ch.id(), s.sheet, s.ch.wallet(), "범인", "서장", "서장");
            postScene(thread, s.ch, next, tier.name());
        } else {
            closeSeojang(thread, s, tier.name());
        }
    }

    /** 서장 종료 = 출도 — 신분 강호·위치 청하현, 지역 채널이 열린다 */
    private void closeSeojang(ThreadChannel thread, Seojang s, String lastTier) throws Exception {
        s.sheet.remove("서장_직전등급");
        db.updateCharacter(s.ch.id(), s.sheet, s.ch.wallet(), "범인", "강호", "청하현");
        db.closeScene(thread.getId());
        db.logEvent("출도", "character", String.valueOf(s.ch.id()), Map.of("지역", "청하현", "등급", lastTier));
        seojangs.remove(thread.getIdLong());

        Character ch = s.ch;
        String fallback = Narration.epilogue(ch, lastTier);
        renderer.render(epilogueFacts(ch, lastTier, fallback), fallback)
                .thenAccept(text -> thread.sendMessageEmbeds(new EmbedBuilder().setColor(BLOOD)
                        .setTitle("서장의 첫 밤이 저물었다")
                        .setDescription(text + "\n\n" + Narration.debut(ch)).build()).queue());
    }

    private void postScene(ThreadChannel thread, Character ch, int idx, String prevTier) {
        Scene sc = scenes(ch)[idx];
        String fallback = Narration.scene(ch, idx, prevTier);
        List<Button> buttons = new ArrayList<>();
        for (int i = 0; i < sc.choices.length; i++) {
            buttons.add(Button.primary("tn:" + idx + ":" + i, sc.choices[i].label));
        }
        renderer.render(sceneFacts(ch, sc.title, prevTier, fallback), fallback)
                .thenAccept(text -> thread.sendMessageEmbeds(new EmbedBuilder().setColor(INK)
                                .setTitle(sc.title).setDescription(text).build())
                        .setComponents(ActionRow.of(buttons)).queue());
    }

    // ─── LLM 렌더 사실 묶음 — 엔진이 계산한 것만 넘긴다 (7계: 엔진 불가침) ───

    private String personLine(Character ch) {
        return ch.name() + " (" + ch.bracket() + " " + ch.age() + "세, 성향 " + ch.disposition()
                + ", 집안 " + ch.family().replace('_', ' ') + ", 발단 " + ch.incident().replace('_', ' ') + ")";
    }

    private String sceneFacts(Character ch, String title, String prevTier, String base) {
        return "장면: " + title + "\n인물: " + personLine(ch) + "\n"
                + (prevTier == null ? "" : "직전 판정 결과: " + prevTier + "\n")
                + "기준 서사(이 사실 범위 안에서만 확장하라):\n" + base;
    }

    private String epilogueFacts(Character ch, String lastTier, String base) {
        return "장면: 서장 에필로그 — 청하현 정착의 첫 밤\n인물: " + personLine(ch)
                + "\n마지막 판정 결과: " + lastTier
                + "\n기준 서사(이 사실 범위 안에서만 확장하라):\n" + base;
    }

    /** 발단 3장면 — 엔진 골격 (서사는 렌더러/템플릿, 수치는 여기) */
    private Scene[] scenes(Character ch) {
        if ("수행_파견".equals(ch.incident())) {
            // 무가의 자식 전용 서장 — 재난이 아니라 명령: 도주극이 아닌 첫 출문의 이야기
            return new Scene[]{
                    new Scene("출문(出門)", 9, new Choice[]{
                            new Choice("예를 다해 하직한다", "화술", 2),
                            new Choice("무기고에서 손에 맞는 검을 고른다", "감각", 2),
                            new Choice("밤새 가전 검형을 다잡는다", "근력", 2)}),
                    new Scene("이름 없는 길", 11, new Choice[]{
                            new Choice("상단 행렬에 동행을 청한다", "화술", 2),
                            new Choice("산길 지름길로 접어든다", "감각", 2),
                            new Choice("대로를 당당히 걷는다", "체력", 2)}),
                    new Scene("청하현 — 전표와 이름", 10, new Choice[]{
                            new Choice("전장에서 전표부터 수령한다", "지혜", 2),
                            new Choice("객잔에 들어 소문부터 듣는다", "화술", 2),
                            new Choice("저잣거리와 뒷골목을 눈에 익힌다", "민첩", 2)})
            };
        }
        return new Scene[]{
                new Scene("그날 밤", 10, new Choice[]{
                        new Choice("숨을 죽이고 숨는다", "민첩", 2),
                        new Choice("소리 나는 쪽을 살핀다", "감각", 2),
                        new Choice("식구들부터 깨운다", "체력", 2)}),
                new Scene("길 위에서", 12, new Choice[]{
                        new Choice("밤새 걸음을 서두른다", "체력", 2),
                        new Choice("흔적을 지우며 간다", "감각", 2),
                        new Choice("행인 무리에 섞여든다", "화술", 2)}),
                new Scene("낯선 고을 청하현", 10, new Choice[]{
                        new Choice("객잔 일손을 구걸한다", "화술", 2),
                        new Choice("장터에서 잔심부름을 찾는다", "민첩", 2),
                        new Choice("의방 앞에서 허드렛일을 청한다", "지혜", 2)})
        };
    }

    // ─── 사냥 — 청하현 뒷산: 화후 적립 + 생계 (combat_hwahu·economy 배선) ───

    private static final List<Beast> BEASTS = List.of(
            new Beast("여우", "하수", 9, "여우_가죽", "여우 가죽"),
            new Beast("늑대", "동수", 10, "늑대_가죽", "늑대 가죽"),
            new Beast("곰", "상수", 13, "웅담", "웅담"));

    private static final String[][] HUNT_APPROACHES = {
            {"정면 승부", "근력"}, {"몰이와 함정", "지혜"}, {"급소 노림", "감각"}};

    private boolean notInRegion(SlashCommandInteractionEvent event) throws Exception {
        Optional<String> region = db.getMeta(REGION_KEY);
        if (region.isEmpty()) {
            event.reply("청하현 채널이 아직 없다 — 관리자가 `/혼천 지역등록`으로 열어야 한다.").setEphemeral(true).queue();
            return true;
        }
        if (!region.get().equals(event.getChannel().getId())) {
            event.reply("여기는 청하현이 아니다 — <#" + region.get() + "> 에서 하라.").setEphemeral(true).queue();
            return true;
        }
        return false;
    }

    /** 출도 여부 검사 — 서장 중이면 거절 */
    private Optional<Map<String, Object>> requireDebuted(SlashCommandInteractionEvent event, User user)
            throws Exception {
        var found = db.findCharacter(user.getId());
        if (found.isEmpty()) {
            event.reply(user.getEffectiveName() + " — 캐릭터가 없다. `/혼천 시작`부터.").setEphemeral(true).queue();
            return Optional.empty();
        }
        if (!"강호".equals(found.get().get("status"))) {
            event.reply(user.getEffectiveName() + " — 아직 서장 중이다. 서장 스레드를 끝내야 출도한다.")
                    .setEphemeral(true).queue();
            return Optional.empty();
        }
        return found;
    }

    private void startHunt(SlashCommandInteractionEvent event) throws Exception {
        if (notInRegion(event) || requireDebuted(event, event.getUser()).isEmpty()) {
            return;
        }
        int beastIdx = dice.nextInt(BEASTS.size());
        Beast beast = BEASTS.get(beastIdx);
        String aura = switch (beast.gap()) {
            case "하수" -> "만만해 보인다";
            case "동수" -> "서로를 재고 있다";
            default -> "공기가 무겁다 — 상대가 위다";
        };
        List<Button> buttons = new ArrayList<>();
        for (int i = 0; i < HUNT_APPROACHES.length; i++) {
            buttons.add(Button.primary("ht:" + beastIdx + ":" + i + ":" + event.getUser().getId(),
                    HUNT_APPROACHES[i][0] + " (" + HUNT_APPROACHES[i][1] + ")"));
        }
        event.replyEmbeds(new EmbedBuilder().setColor(INK)
                        .setTitle("사냥 — " + beast.name() + " 조우")
                        .setDescription("뒷산 능선에서 " + beast.name() + "와(과) 마주쳤다. 기세: **" + aura + "**")
                        .build())
                .addComponents(ActionRow.of(buttons)).queue();
    }

    @SuppressWarnings("unchecked")
    private void onHuntChoice(ButtonInteractionEvent event, int beastIdx, int opt, String ownerId)
            throws Exception {
        if (!event.getUser().getId().equals(ownerId)) {
            event.reply("남의 사냥감이다 — `/혼천 사냥`으로 네 몫을 찾아라.").setEphemeral(true).queue();
            return;
        }
        var found = db.findCharacter(ownerId);
        if (found.isEmpty() || !"강호".equals(found.get().get("status"))) {
            event.editMessage("사냥꾼의 기록이 없다.").setComponents().queue();
            return;
        }
        Map<String, Object> row = found.get();
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        long chId = ((Number) row.get("id")).longValue();
        int wallet = ((Number) row.get("wallet")).intValue();
        Beast beast = BEASTS.get(beastIdx);
        String[] approach = HUNT_APPROACHES[opt];

        Map<String, Object> attrsRaw = (Map<String, Object>) sheet.get("능력치");
        int stat = ((Number) attrsRaw.getOrDefault(approach[1], 2)).intValue();
        int roll = dice.nextInt(6) + 1 + dice.nextInt(6) + 1;
        JudgmentEngine.Tier tier = rules.judgment.resolve(stat + 2, roll, beast.resist());
        int margin = stat + 2 + roll - beast.resist();

        boolean effective = !tier.id().equals("failure") && !tier.id().equals("critical_failure");
        boolean pelt = margin >= 0;
        int today = db.worldDay();

        StringBuilder gains = new StringBuilder();
        if (effective) {
            int rep = huntRepetition(sheet, beast.name(), today);
            double accrual = rules.progression.combatAccrualDays(beast.gap(), "실전_사냥", rep);
            double granted = grantHwahu(sheet, accrual, today);
            gains.append(granted > 0
                    ? String.format("수련 **+%.2f일치**", granted)
                    : "수련 적립 없음 — *오늘은 몸이 벅차다* (일일 상한)");
            for (String note : creditSkill(sheet, granted, today, tier.name())) {
                gains.append('\n').append(note);
            }
        }
        if (pelt) {
            int base = rules.economy.basePrice("사냥_부산물", beast.peltKey());
            int sale = rules.economy.npcBuyPrice(base, false);
            wallet += sale;
            gains.append(gains.isEmpty() ? "" : " · ").append(beast.peltLabel())
                    .append(" 매각 **+").append(sale).append("문**");
        }
        if (gains.isEmpty()) {
            gains.append("소득 없음");
        }
        String realm = promoteIfDue(sheet, String.valueOf(row.get("realm")));
        if (!realm.equals(row.get("realm"))) {
            gains.append("\n💥 **돌파 — ").append(realm).append("에 올랐다** (기초가 몸에 뱄다)");
            db.logEvent("승급", "character", String.valueOf(chId), Map.of("경지", realm));
        }
        db.updateCharacter(chId, sheet, wallet, realm, "강호", "청하현");
        db.logEvent("사냥", "character", String.valueOf(chId),
                Map.of("짐승", beast.name(), "접근", approach[0], "굴림", roll, "마진", margin,
                        "등급", tier.name()));

        EmbedBuilder result = new EmbedBuilder().setColor(INK)
                .setTitle("판정 — " + approach[0])
                .setDescription("**" + approach[1] + " " + stat + "** + 2d6 = **" + (stat + 2 + roll)
                        + "** vs " + beast.resist() + " │ 마진 **" + (margin >= 0 ? "+" : "") + margin
                        + "** → **" + tier.name() + "**\n" + gains
                        + "\n\n" + Narration.hunt(beast.name(), tier.name(), pelt));
        event.editMessageEmbeds(event.getMessage().getEmbeds().get(0), result.build()).setComponents().queue();
    }

    /** 같은 유형 연속 사냥 카운트 — 회당 -25% 감쇠의 지수 (새 유형·새 날 리셋) */
    private int huntRepetition(Map<String, Object> sheet, String beastName, int today) {
        @SuppressWarnings("unchecked")
        Map<String, Object> streak = (Map<String, Object>) sheet.get("사냥_연속");
        int rep = 0;
        if (streak != null && beastName.equals(streak.get("유형"))
                && today == ((Number) streak.getOrDefault("일", -1)).intValue()) {
            rep = ((Number) streak.getOrDefault("횟수", 0)).intValue();
        }
        sheet.put("사냥_연속", Map.of("유형", beastName, "횟수", rep + 1, "일", today));
        return rep;
    }

    /**
     * 주력 무공 수련 적립 (이류+ 승급 축) — combat_hwahu "사용 무공·기술 숙련 화후" 배선.
     * 단발 보정(대성공·치명적 실패 = +1일치, 기술당 1일 1회)과 숙련 상승(training 환산표)을 함께 처리.
     * 반환: 결과 embed에 붙일 알림 문장들.
     */
    @SuppressWarnings("unchecked")
    private List<String> creditSkill(Map<String, Object> sheet, double granted, int today, String tierName) {
        List<String> notes = new ArrayList<>();
        Map<String, Object> skills = (Map<String, Object>) sheet.get("기술");
        if (skills == null) {
            return notes;
        }
        String main = MARTIAL_SKILLS.stream().filter(skills::containsKey).findFirst().orElse(null);
        if (main == null) {
            return notes;
        }
        double add = Math.max(0, granted);
        // 단발_보정.대성공_또는_치명_실패 — 해당 기술 화후 +1일치 (기술당 1일 1회)
        if ("대성공".equals(tierName) || "치명적 실패".equals(tierName)) {
            Map<String, Object> bonusDays = new LinkedHashMap<>(
                    (Map<String, Object>) sheet.getOrDefault("단발_보정일", Map.of()));
            if (today != ((Number) bonusDays.getOrDefault(main, -1)).intValue()) {
                add += 1.0;
                bonusDays.put(main, today);
                sheet.put("단발_보정일", bonusDays);
                notes.add("단발 보정 — " + tierName + ": " + main + " 수련 +1.00일치");
            }
        }
        if (add <= 0) {
            return notes;
        }
        Map<String, Object> prog = new LinkedHashMap<>(
                (Map<String, Object>) sheet.getOrDefault("기술_수련", Map.of()));
        double days = ((Number) prog.getOrDefault(main, 0)).doubleValue() + add;
        int level = ((Number) skills.getOrDefault(main, 0)).intValue();
        int cost;
        while ((cost = rules.progression.skillLevelUpDays(level)) > 0 && days >= cost) {
            days -= cost;
            level++;
            notes.add("⚡ **숙련 상승 — " + main + " " + level + "** (손이 검결을 기억하기 시작한다)");
        }
        Map<String, Object> newSkills = new LinkedHashMap<>(skills);
        newSkills.put(main, level);
        sheet.put("기술", newSkills);
        prog.put(main, days);
        sheet.put("기술_수련", prog);
        return notes;
    }

    /** 화후 적립 — 일일 상한(cappedGrant)을 시트에 기장하고 실적립분을 돌려준다 */
    private double grantHwahu(Map<String, Object> sheet, double accrual, int today) {
        int day = ((Number) sheet.getOrDefault("적립일", -1)).intValue();
        double grantedToday = day == today ? ((Number) sheet.getOrDefault("오늘_적립", 0)).doubleValue() : 0;
        double granted = rules.progression.cappedGrant(grantedToday, accrual);
        sheet.put("적립일", today);
        sheet.put("오늘_적립", grantedToday + granted);
        sheet.put("화후_원장", ((Number) sheet.getOrDefault("화후_원장", 0)).doubleValue() + granted);
        return granted;
    }

    // ─── 의뢰 — 소연의 게시판: 세계일 결정론 3건, 수주 1건, 판정 완수 (quest_generation G1) ───

    @SuppressWarnings("unchecked")
    private void questBoard(SlashCommandInteractionEvent event) throws Exception {
        if (notInRegion(event)) {
            return;
        }
        var found = requireDebuted(event, event.getUser());
        if (found.isEmpty()) {
            return;
        }
        Map<String, Object> row = found.get();
        Map<String, Object> sheet = (Map<String, Object>) row.get("sheet");
        int today = db.worldDay();

        Map<String, Object> holding = (Map<String, Object>) sheet.get("의뢰");
        if (holding != null) {
            // 수주 중 — 수행 화면
            Quests.Quest q = Quests.byKey(String.valueOf(holding.get("키")));
            if (q == null) {
                event.reply("대장에 없는 의뢰다 — 소연에게 문의하라 (기록 오류).").setEphemeral(true).queue();
                return;
            }
            boolean above = Quests.realmRank(q.realmReq()) > Quests.realmRank(String.valueOf(row.get("realm")));
            List<Button> buttons = new ArrayList<>();
            for (int i = 0; i < q.approaches().size(); i++) {
                buttons.add(Button.primary("qp:" + q.key() + ":" + i + ":" + event.getUser().getId(),
                        q.approaches().get(i).label()));
            }
            event.replyEmbeds(new EmbedBuilder().setColor(INK)
                            .setTitle("수행 — " + q.name() + (above ? " (격상 — 사선)" : ""))
                            .setDescription(q.brief() + "\n\n어떻게 해낼 것인가.").build())
                    .addComponents(ActionRow.of(buttons)).queue();
            return;
        }

        // 게시판 — 오늘의 의뢰 3건
        Map<String, Object> done = (Map<String, Object>) sheet.getOrDefault("의뢰_완료", Map.of());
        EmbedBuilder eb = new EmbedBuilder().setColor(INK)
                .setTitle("의뢰소 게시판 — " + today + "일차")
                .setDescription("소연이 대장을 넘긴다. \"골라 보게 — 보수는 완수 후, 검수는 확실히 하네.\"");
        List<Button> buttons = new ArrayList<>();
        for (Quests.Quest q : Quests.board(today)) {
            boolean doneToday = today == ((Number) ((Map<String, Object>) done)
                    .getOrDefault(q.key(), -1)).intValue();
            boolean above = Quests.realmRank(q.realmReq()) > Quests.realmRank(String.valueOf(row.get("realm")));
            eb.addField(q.name() + (above ? " ⚠격상" : ""),
                    q.brief() + "\n등급 " + q.grade() + " · 권장 " + q.realmReq()
                            + (doneToday ? " · **오늘은 마감**" : ""), false);
            if (!doneToday) {
                buttons.add(Button.secondary("qa:" + q.key() + ":" + event.getUser().getId(),
                        "수주 — " + q.name()));
            }
        }
        var reply = event.replyEmbeds(eb.build());
        if (!buttons.isEmpty()) {
            reply.addComponents(ActionRow.of(buttons));
        }
        reply.queue();
    }

    @SuppressWarnings("unchecked")
    private void onQuestAccept(ButtonInteractionEvent event, String key, String ownerId) throws Exception {
        if (!event.getUser().getId().equals(ownerId)) {
            event.reply("남의 게시판 화면이다 — `/혼천 의뢰`로 직접 보라.").setEphemeral(true).queue();
            return;
        }
        var found = db.findCharacter(ownerId);
        Quests.Quest q = Quests.byKey(key);
        if (found.isEmpty() || q == null) {
            event.editMessage("기록이 없다.").setComponents().queue();
            return;
        }
        Map<String, Object> row = found.get();
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        if (sheet.get("의뢰") != null) {
            event.reply("이미 맡은 일이 있다 — 하나씩. (`/혼천 의뢰`로 진행)").setEphemeral(true).queue();
            return;
        }
        int today = db.worldDay();
        sheet.put("의뢰", Map.of("키", q.key(), "수주일", today));
        long chId = ((Number) row.get("id")).longValue();
        db.updateCharacter(chId, sheet, ((Number) row.get("wallet")).intValue(),
                String.valueOf(row.get("realm")), "강호", "청하현");
        db.logEvent("의뢰_수주", "character", String.valueOf(chId), "quest", q.key(), Map.of("의뢰", q.key()));
        event.editMessageEmbeds(new EmbedBuilder().setColor(INK)
                        .setTitle("수주 — " + q.name())
                        .setDescription("소연이 대장에 이름을 적었다. \"기한은 오늘 해 질 녘까지 — 검수는 확실히 하네.\"\n"
                                + "`/혼천 의뢰`로 수행하라.").build())
                .setComponents().queue();
    }

    @SuppressWarnings("unchecked")
    private void onQuestPerform(ButtonInteractionEvent event, String key, int opt, String ownerId)
            throws Exception {
        if (!event.getUser().getId().equals(ownerId)) {
            event.reply("남의 의뢰다.").setEphemeral(true).queue();
            return;
        }
        var found = db.findCharacter(ownerId);
        Quests.Quest q = Quests.byKey(key);
        if (found.isEmpty() || q == null) {
            event.editMessage("기록이 없다.").setComponents().queue();
            return;
        }
        Map<String, Object> row = found.get();
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        Map<String, Object> holding = (Map<String, Object>) sheet.get("의뢰");
        if (holding == null || !q.key().equals(holding.get("키"))) {
            event.editMessage("이 의뢰는 이미 끝났다 — `/혼천 의뢰`로 게시판을 보라.").setComponents().queue();
            return;
        }
        Quests.Approach approach = q.approaches().get(opt);
        Map<String, Object> attrs = (Map<String, Object>) sheet.get("능력치");
        int stat = ((Number) attrs.getOrDefault(approach.stat(), 2)).intValue();
        // 격상 도전 — 금지가 아니라 사선: 저항 +2 (gate 느슨)
        boolean above = Quests.realmRank(q.realmReq()) > Quests.realmRank(String.valueOf(row.get("realm")));
        int resist = q.resist() + (above ? 2 : 0);
        int roll = dice.nextInt(6) + 1 + dice.nextInt(6) + 1;
        JudgmentEngine.Tier tier = rules.judgment.resolve(stat + approach.bonus(), roll, resist);
        int margin = stat + approach.bonus() + roll - resist;
        int today = db.worldDay();
        long chId = ((Number) row.get("id")).longValue();

        // 의뢰 종결 — 성패 무관 오늘 재수주 불가 (실패 = 게시판 복귀, 만료 시계는 후속)
        sheet.remove("의뢰");
        Map<String, Object> done = new LinkedHashMap<>(
                (Map<String, Object>) sheet.getOrDefault("의뢰_완료", Map.of()));
        done.put(q.key(), today);
        sheet.put("의뢰_완료", done);

        EmbedBuilder result = new EmbedBuilder()
                .setTitle("판정 — " + approach.label() + (above ? " (격상)" : ""))
                .setDescription("**" + approach.stat() + " " + stat + "** + 2d6 = **"
                        + (stat + approach.bonus() + roll) + "** vs " + resist
                        + " │ 마진 **" + (margin >= 0 ? "+" : "") + margin + "** → **" + tier.name() + "**");
        int wallet = ((Number) row.get("wallet")).intValue();
        String realm = String.valueOf(row.get("realm"));
        if (margin >= 0) {
            int reward = rules.questReward(q.rewardKey(), dice);
            wallet += reward;
            StringBuilder gains = new StringBuilder("소연의 검수 통과 — 보수 **+" + reward + "문**");
            if (q.combatMark()) {
                sheet.put("실전_마크", ((Number) sheet.getOrDefault("실전_마크", 0)).intValue() + 1);
                gains.append(" · **실전 마크 +1** (전투 의뢰)");
            }
            String promoted = promoteIfDue(sheet, realm);
            if (!promoted.equals(realm)) {
                gains.append("\n💥 **돌파 — ").append(promoted).append("에 올랐다**");
                db.logEvent("승급", "character", String.valueOf(chId), Map.of("경지", promoted));
                realm = promoted;
            }
            result.setColor(BLOOD).appendDescription("\n" + gains);
            db.logEvent("의뢰_완수", "character", String.valueOf(chId), "quest", q.key(),
                    Map.of("의뢰", q.key(), "굴림", roll, "마진", margin, "보수", reward));
        } else {
            result.setColor(INK).appendDescription(
                    "\n일이 어그러졌다 — 소연: \"빈손이면 보수도 없네. 게시판은 내일 다시 열리네.\"");
            db.logEvent("의뢰_실패", "character", String.valueOf(chId), "quest", q.key(),
                    Map.of("의뢰", q.key(), "굴림", roll, "마진", margin));
        }
        db.updateCharacter(chId, sheet, wallet, realm, "강호", "청하현");
        event.editMessageEmbeds(event.getMessage().getEmbeds().get(0), result.build())
                .setComponents().queue();
    }

    // ─── 대화 — NPC 3층 구조의 최소 배선 (npc_dialogue.yml: 잡담/판정/세계) ───

    /** 대화 가능한 NPC 6인 + 정보 캐기 성공 시 내주는 단서 (등록 사건 기반 — 세계층의 지식 경계) */
    private static final Map<String, String> NPC_HINTS = Map.of(
            "한백", "요즘 밤에만 드나드는 낯선 손이 있어. 셋째 방 — 아니, 내가 말이 많았군.",
            "소연", "북쪽 산길에 도적이 늘었네. 조만간 현상 의뢰가 걸릴 걸세 — 게시판을 봐 두게.",
            "유문", "고을에 열병 소문이 돌아. 약재가 동나기 전에 구해 두는 게 좋을 게야.",
            "금서방", "시세가 들썩입니다 — 북쪽 길 소문 때문이지요. 가죽 값도 곧 움직일 겁니다.",
            "곽진", "상단로가 요즘 험하다. 호위 삯이 오르는 중이야 — 실력이 있다면 기회지.",
            "장쇠", "요즘 은근히 좋은 물건을 싸게 넘기려는 자들이 있어 — 출처는 안 물어봤네.");

    /**
     * F36 — 정보 질문 결정론 게이트: LLM 분류(확률적)와 OR. 키워드가 있으면 LLM을 거치지 않고
     * 곧장 판정층 — 오분류로 잡담층이 소문을 발명해 판정 게이트를 우회하는 길을 막는다 (8차-②).
     */
    private static final List<String> INFO_KEYWORDS = List.of(
            "소문", "소식", "들리는", "들은 거", "들은 것", "들은 얘기",
            "아는 거", "아는 것", "정보", "무슨 일", "알려주", "알려 주", "귀띔");

    /** F37 — 전언 종결(…라던데)은 게이트 제외: 진짜 확인 질문이면 LLM 백업이 잡는다 (OR 구조 유지) */
    private static final List<String> HEARSAY_ENDINGS = List.of(
            "라던데", "다던데", "라며", "다며", "라더군", "다더군", "라더라", "다더라");

    private static boolean isInfoSeeking(String say) {
        // F37 — 관형형 "소문난 ~"(유명하다는 뜻)은 정보 요구가 아니다 (8차-③ FP: 소문난 국밥집)
        String s = say.replace("소문난", "");
        String core = s.replaceAll("[?!.…~\\s]+$", "");
        if (HEARSAY_ENDINGS.stream().anyMatch(core::endsWith)) {
            return false;
        }
        return INFO_KEYWORDS.stream().anyMatch(s::contains);
    }

    private final Map<String, Long> talkCooldown = new ConcurrentHashMap<>();

    private void talkToNpc(SlashCommandInteractionEvent event) throws Exception {
        if (notInRegion(event)) {
            return;
        }
        var found = requireDebuted(event, event.getUser());
        if (found.isEmpty()) {
            return;
        }
        var npcOpt = event.getOption("상대");
        var sayOpt = event.getOption("말");
        if (npcOpt == null || sayOpt == null) {
            event.reply("`/혼천 대화 상대:<이름> 말:<하고 싶은 말>`").setEphemeral(true).queue();
            return;
        }
        String npcName = npcOpt.getAsString();
        String say = sayOpt.getAsString();
        Map<String, Object> npc = rules.npcByName(npcName);
        if (npc == null || !NPC_HINTS.containsKey(npcName)) {
            event.reply("그 이름은 청하현에 없다.").setEphemeral(true).queue();
            return;
        }
        long now = System.currentTimeMillis();
        Long last = talkCooldown.get(event.getUser().getId());
        if (last != null && now - last < 5_000) {
            event.reply("숨 좀 돌리고 — (대화는 5초에 한 번)").setEphemeral(true).queue();
            return;
        }
        talkCooldown.put(event.getUser().getId(), now);

        Map<String, Object> row = found.get();
        long chId = ((Number) row.get("id")).longValue();
        String persona = personaPrompt(npcName, npc);
        String fallback = fallbackLine(npcName, npc);
        event.deferReply().queue();   // 로컬 LLM 1~3초 — 3초 응답 제한 회피
        // F36 — 키워드 게이트가 먼저: 정보 질문은 결정론으로 판정층 (LLM 호출도 절약)
        if (isInfoSeeking(say)) {
            resolveInfoCheck(event, row, chId, npcName, say);
            return;
        }
        renderer.chat(persona, say, fallback).thenAccept(reply -> {
            try {
                // F35 — 소형 모델이 잡담과 [판정]을 섞어 낸다: 관용 매칭 + 앞 텍스트 폐기
                if (reply.contains("[판정]")) {
                    resolveInfoCheck(event, row, chId, npcName, say);
                    return;
                }
                // 잔여 토큰 방어 — 원시 토큰이 사용자에게 보이면 안 된다
                String clean = reply.replace("[판정]", "").strip();
                if (clean.isBlank()) {
                    clean = fallback;
                }
                // 잡담층 + 세계층 기록 (대화 요지 — NPC 기억의 재료)
                db.logEvent("대화", "character", String.valueOf(chId), npcName,
                        Map.of("말", say.substring(0, Math.min(80, say.length()))));
                event.getHook().sendMessageEmbeds(new EmbedBuilder().setColor(INK)
                        .setTitle("「" + npcName + "」")
                        .setDescription(clean).build()).queue();
            } catch (Exception e) {
                event.getHook().sendMessage("오류: " + e.getMessage()).queue();
            }
        });
    }

    /** 판정층 — 정보 캐기: 화술 vs 11 (judgment action_pairs). 성공 = 등록 단서, 실패 = 침묵 */
    @SuppressWarnings("unchecked")
    private void resolveInfoCheck(SlashCommandInteractionEvent event, Map<String, Object> row,
                                  long chId, String npcName, String say) throws Exception {
        Map<String, Object> sheet = (Map<String, Object>) row.get("sheet");
        Map<String, Object> attrs = (Map<String, Object>) sheet.get("능력치");
        int stat = ((Number) attrs.getOrDefault("화술", 2)).intValue();
        int roll = dice.nextInt(6) + 1 + dice.nextInt(6) + 1;
        int resist = 11;
        JudgmentEngine.Tier tier = rules.judgment.resolve(stat, roll, resist);
        int margin = stat + roll - resist;
        // F38 — 판정 대화도 질문 요지를 남긴다 (세계층: NPC 기억 재료 — 잡담 경로와 대칭)
        db.logEvent("대화_판정", "character", String.valueOf(chId), npcName,
                Map.of("말", say.substring(0, Math.min(80, say.length())),
                        "굴림", roll, "마진", margin, "등급", tier.name()));
        String outcome = margin >= 0
                ? NPC_HINTS.get(npcName)
                : "…" + npcName + "은(는) 화제를 돌렸다. 오늘은 입이 무겁다.";
        event.getHook().sendMessageEmbeds(
                new EmbedBuilder().setColor(INK).setTitle("판정 — 정보 캐기")
                        .setDescription("**화술 " + stat + "** + 2d6 = **" + (stat + roll) + "** vs " + resist
                                + " │ 마진 **" + (margin >= 0 ? "+" : "") + margin + "** → **"
                                + tier.name() + "**").build(),
                new EmbedBuilder().setColor(margin >= 0 ? BLOOD : INK)
                        .setTitle("「" + npcName + "」").setDescription(outcome).build()).queue();
    }

    /** 페르소나 시스템 프롬프트 — 등록부(역할·성향)가 원천, 지식 경계·판정 라우팅 포함 */
    @SuppressWarnings("unchecked")
    private String personaPrompt(String name, Map<String, Object> npc) {
        Object disp = npc.get("disposition");
        String dispositions = disp instanceof List<?> list
                ? String.join(", ", list.stream().map(String::valueOf).toList()) : "";
        // v3 — 8차-② 채집물 반영: 소문 발명(금서방 철공소·소연 청하정)이 판정 게이트를 우회 →
        //      질문 예시 명시 + 소문·사건 발명을 별도 금지 조항으로 (서버측 F36 키워드 게이트와 이중 방어)
        return "너는 무협 세계 청하현의 NPC 「" + name + "」이다. 너의 역할: " + npc.get("role")
                + ". 너의 성향: " + dispositions + ".\n"
                + "말을 거는 상대는 강호에 갓 나온 손님이다 — 상대에게 너의 직업을 투사하지 마라.\n"
                + "규칙 (가장 중요한 것부터):\n"
                + "1. 상대의 말이 정보 요구·부탁·설득·흥정·위협이면 **다른 글자 없이 [판정] 넉 자만** 출력하라."
                + " \"요즘 소문 있소?\" \"무슨 소식 없나?\" \"들리는 얘기 좀\" 이 대표 예다 —"
                + " 소문·소식·정보를 묻는 말에 잡담으로 답하는 것은 금지다.\n"
                + "2. 그 외(인사·잡담·농담)는 2문장 이내로 대답한다 — 판정을 억지로 만들지 않는다.\n"
                + "3. 소문·사건·시설을 지어내지 마라 — 세상 소식은 네가 정하는 게 아니다."
                + " 모르면 모른다고 하거나 [판정]으로 넘겨라.\n"
                + "4. 네가 아는 것만 말한다 — 청하현 안의 일상 수준. 새 인명·지명·조직명·직함 발명 금지."
                + " 너의 이름과 직함은 위에 주어진 것이 전부다. 숫자 금지.\n"
                + "5. 말투는 처음부터 끝까지 하나로 — 하게체(장사꾼·무인) 또는 하오체. 존댓말로 바꾸지 마라.\n"
                + "6. 직업의 어휘를 써라 — 의원이면 맥·약재, 상인이면 값·물건, 무인이면 손속·길.";
    }

    /** LLM 비활성·실패 시 폴백 — 역할 기반 한 줄 (대화가 죽지 않는다) */
    private String fallbackLine(String name, Map<String, Object> npc) {
        return npc.get("role") + " " + name + "은(는) 고개만 끄덕였다. 바빠 보인다.";
    }

    // ─── 개화 축 (단계 2) — 폐사당의 취걸개(fortune_encounters) → 심법 전수 → 운기·축기 → 일류 ───

    /** 기연 트리거 (chuigeolgae_master 알파 배선): 방문 3회 + 선행 기억(의뢰 완수) 2건 + 이류 이하 */
    private static final int SHRINE_VISITS_REQUIRED = 3;
    private static final int GOOD_DEEDS_REQUIRED = 2;
    private static final int MEAL_SHARE_DAYS = 3;
    private static final String FORTUNE_KEY = "기연:chuigeolgae_master";

    /** 축기 환산 — 내공 n→n+1 = max(1,n)년 전용 수련 (simbeop accumulation_cost, 1년 = 360일) */
    private static final double YEAR_DAYS = 360.0;

    /** 축기 누적일 → 내공 실수 (화후 규칙 — 내부는 연속) */
    private static double naegongOf(double days) {
        int level = 0;
        double remain = days;
        while (true) {
            double cost = Math.max(1, level) * YEAR_DAYS;
            if (remain < cost) {
                return level + remain / cost;
            }
            remain -= cost;
            level++;
        }
    }

    /** 화후 어법 표시 — "0단계 3성" (dantian hwahu display) */
    private static String hwahuLabel(double naegong) {
        int stage = (int) naegong;
        int seong = (int) ((naegong - stage) * 10);
        return stage + "단계 " + seong + "성";
    }

    @SuppressWarnings("unchecked")
    private void visitShrine(SlashCommandInteractionEvent event) throws Exception {
        if (notInRegion(event)) {
            return;
        }
        var found = requireDebuted(event, event.getUser());
        if (found.isEmpty()) {
            return;
        }
        Map<String, Object> row = found.get();
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        long chId = ((Number) row.get("id")).longValue();
        int today = db.worldDay();
        if (today == ((Number) sheet.getOrDefault("탐방일", -1)).intValue()) {
            event.reply("오늘은 이미 다녀왔다 — 해가 바뀌면 다시. (탐방은 하루 한 번)")
                    .setEphemeral(true).queue();
            return;
        }
        sheet.put("탐방일", today);
        String state = (String) sheet.get("취걸개");
        db.logEvent("탐방", "character", String.valueOf(chId), Map.of("장소", "폐사당"));

        // 1회성 — 획득 즉시 세계에서 소모 (공유 세계 선착순, fortune rules)
        boolean consumed = db.getMeta(FORTUNE_KEY).isPresent();
        if ("전수".equals(state) || (consumed && !"시험".equals(state))) {
            persistAndReply(event, row, sheet, "폐사당",
                    "무너진 사당은 비어 있다. 낡은 신상만 먼지 속에 앉아 있을 뿐 — 인연은 한 번뿐이다.");
            return;
        }
        if ("시험".equals(state)) {
            // 시험 중 — 걸인이 곁에 앉는다. 시험임은 알려주지 않는다 (선택_시험)
            db.updateCharacter(chId, sheet, ((Number) row.get("wallet")).intValue(),
                    String.valueOf(row.get("realm")), "강호", "청하현");
            int streak = ((Number) sheet.getOrDefault("밥_연속", 0)).intValue();
            event.replyEmbeds(new EmbedBuilder().setColor(INK).setTitle("폐사당")
                            .setDescription("걸인이 오늘도 그 자리에 있다. 해진 발우를 무릎에 얹고, "
                                    + "당신이 꺼낸 끼니를 물끄러미 본다."
                                    + (streak > 0 ? " 어제 나눈 밥을 기억하는 눈치다." : ""))
                            .build())
                    .addComponents(ActionRow.of(
                            Button.primary("gs:share:" + event.getUser().getId(), "밥을 나눈다"),
                            Button.secondary("gs:alone:" + event.getUser().getId(), "혼자 먹는다")))
                    .queue();
            return;
        }
        // 발견 전 — 방문 적립 + 트리거 판정 (등록 조건의 알파 배선: 선행 기억 태그 → 의뢰 완수 이력)
        int visits = ((Number) sheet.getOrDefault("폐사당_방문", 0)).intValue() + 1;
        sheet.put("폐사당_방문", visits);
        String realm = String.valueOf(row.get("realm"));
        boolean realmOk = List.of("범인", "삼류", "이류").contains(realm);
        int deeds = db.countEvents("character", String.valueOf(chId), List.of("의뢰_완수"));
        if (visits >= SHRINE_VISITS_REQUIRED && deeds >= GOOD_DEEDS_REQUIRED && realmOk) {
            sheet.put("취걸개", "시험");
            db.logEvent("기연_발견", "character", String.valueOf(chId),
                    Map.of("기연", "chuigeolgae_master"));
            persistAndReply(event, row, sheet, "폐사당",
                    "그늘에서 쉰 목소리가 났다. \"…또 왔군.\" 처음 보는 걸인이 — 아니, 늘 있었던 걸인이 "
                            + "당신을 보고 있다. \"젊은 것이 발품은 부지런해. 밥은 먹고 다니나?\"\n"
                            + "*(내일부터 폐사당에서 그를 다시 만날 수 있을 것 같다)*");
            return;
        }
        String scene = switch (Math.min(visits, 3)) {
            case 1 -> "무너진 사당이다. 지붕은 반이 내려앉았고, 낡은 신상 앞에 탄 향 자국만 남았다.";
            case 2 -> "구석에 밥그릇이 하나 있다 — 최근 것이다. 누가 여기 사는 걸까.";
            default -> visits >= SHRINE_VISITS_REQUIRED && deeds < GOOD_DEEDS_REQUIRED
                    ? "그늘에 걸인이 앉아 있다. 눈길도 주지 않는다. \"…네 얼굴엔 아직 이야기가 없군.\" "
                            + "(청하현 사람들을 도운 적이 있던가 — 의뢰 게시판이 떠오른다)"
                    : "그늘에 걸인이 앉아 있다. 눈길도 주지 않는다.";
        };
        persistAndReply(event, row, sheet, "폐사당", scene);
    }

    private void persistAndReply(SlashCommandInteractionEvent event, Map<String, Object> row,
                                 Map<String, Object> sheet, String title, String body) throws Exception {
        db.updateCharacter(((Number) row.get("id")).longValue(), sheet,
                ((Number) row.get("wallet")).intValue(),
                String.valueOf(row.get("realm")), "강호", "청하현");
        event.replyEmbeds(new EmbedBuilder().setColor(INK).setTitle(title)
                .setDescription(body).build()).queue();
    }

    /** 밥 나눔 선택 — 사흘 연속이면 전수 (시험임을 알려주지 않았다 — trial 선택_시험) */
    @SuppressWarnings("unchecked")
    private void onMealChoice(ButtonInteractionEvent event, boolean share, String userId) throws Exception {
        if (!event.getUser().getId().equals(userId)) {
            event.reply("남의 끼니다.").setEphemeral(true).queue();
            return;
        }
        var found = db.findCharacter(userId);
        if (found.isEmpty()) {
            event.editMessage("캐릭터가 없다.").setComponents().queue();
            return;
        }
        Map<String, Object> row = found.get();
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        long chId = ((Number) row.get("id")).longValue();
        if (!"시험".equals(sheet.get("취걸개"))) {
            event.editMessage("그 순간은 지나갔다.").setComponents().queue();
            return;
        }
        int today = db.worldDay();
        if (today == ((Number) sheet.getOrDefault("밥_선택일", -1)).intValue()) {
            event.editMessage("오늘 끼니는 이미 정했다.").setComponents().queue();
            return;
        }
        sheet.put("밥_선택일", today);
        String body;
        if (!share) {
            sheet.put("밥_연속", 0);
            body = "당신은 등을 돌리고 혼자 먹었다. 걸인은 아무 말이 없다.";
        } else {
            int last = ((Number) sheet.getOrDefault("밥_최종일", -99)).intValue();
            int streak = (last == today - 1)
                    ? ((Number) sheet.getOrDefault("밥_연속", 0)).intValue() + 1 : 1;
            sheet.put("밥_연속", streak);
            sheet.put("밥_최종일", today);
            if (streak >= MEAL_SHARE_DAYS) {
                if (db.getMeta(FORTUNE_KEY).isPresent()) {
                    // 그 사이 다른 이가 인연을 맺었다 — 공유 세계의 선착순
                    sheet.put("취걸개", "전수");   // 재시도 무의미 — 상태만 닫는다
                    body = "걸인의 자리에 빈 발우만 남아 있다. 인연은 이미 다른 손을 잡았다.";
                } else {
                    db.setMeta(FORTUNE_KEY, String.valueOf(chId));
                    sheet.put("취걸개", "전수");
                    sheet.put("심법", "현천토납법");
                    List<String> ties = sheet.get("인연") instanceof List<?> l
                            ? new ArrayList<>((List<String>) l) : new ArrayList<>();
                    ties.add("개방_계열(취걸개)");
                    sheet.put("인연", ties);
                    db.logEvent("기연", "character", String.valueOf(chId),
                            Map.of("기연", "chuigeolgae_master", "보상", "현천토납법",
                                    "대가", List.of("발설_금지", "원수_상속")));
                    body = "사흘째 밥을 나누자, 걸인이 문득 자세를 고쳐 앉았다 — 등이 산처럼 펴진다.\n"
                            + "\"사흘을 나눴으면 됐다. 숨 쉬는 법부터 가르쳐 주지.\"\n"
                            + "그날 밤, 당신은 **현천토납법**의 구결을 받았다. (`/혼천 운기`)\n"
                            + "*\"이 인연을 입에 올리면 나는 없던 사람이다. …그리고 언젠가, 내 빚을 네가 갚게 될지도 모르지.\"*";
                }
            } else {
                body = "밥을 반으로 갈랐다. 걸인은 말없이 받아, 천천히 씹었다. (" + streak + "일째)";
            }
        }
        db.updateCharacter(chId, sheet, ((Number) row.get("wallet")).intValue(),
                String.valueOf(row.get("realm")), "강호", "청하현");
        event.editMessageEmbeds(new EmbedBuilder().setColor(INK).setTitle("폐사당")
                .setDescription(body).build()).setComponents().queue();
    }

    /** 운기 — 개화(첫 운기, 현천토납법 = 실패 없음) 후 매일 좌공 축기 1일치 */
    @SuppressWarnings("unchecked")
    private void circulate(SlashCommandInteractionEvent event) throws Exception {
        if (notInRegion(event)) {
            return;
        }
        var found = requireDebuted(event, event.getUser());
        if (found.isEmpty()) {
            return;
        }
        Map<String, Object> row = found.get();
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        if (sheet.get("심법") == null) {
            event.reply("기를 돌릴 법을 모른다 — 심법이 없다. (강호 어딘가에 스승이 있을지도)")
                    .setEphemeral(true).queue();
            return;
        }
        int today = db.worldDay();
        if (today == ((Number) sheet.getOrDefault("운기일", -1)).intValue()) {
            event.reply("오늘 몫의 운기는 끝났다 — 단전이 벅차다. (운기는 하루 한 번)")
                    .setEphemeral(true).queue();
            return;
        }
        sheet.put("운기일", today);
        long chId = ((Number) row.get("id")).longValue();
        StringBuilder body = new StringBuilder();
        if (!"개화".equals(sheet.get("단전"))) {
            // 개화 — 단전 개방. 현천토납법은 실패 없음 (기초의 미덕), 수명을 태우지 않는다
            sheet.put("단전", "개화");
            sheet.put("축기_원장", 0.0);
            db.logEvent("개화", "character", String.valueOf(chId), Map.of("심법", "현천토납법"));
            body.append("구결대로 숨을 고르자, 아랫배 깊은 곳에서 무언가 열렸다 — 옅은 백색의 기운이 "
                    + "실오라기처럼 돌기 시작한다.\n💥 **개화 — 단전이 열렸다** (이제 매일 운기로 기를 쌓는다)");
        } else {
            double days = ((Number) sheet.getOrDefault("축기_원장", 0)).doubleValue() + 1.0;
            sheet.put("축기_원장", days);
            double naegong = naegongOf(days);
            db.logEvent("운기", "character", String.valueOf(chId), Map.of("적립", 1));
            body.append(String.format("가부좌를 틀고 한 주천을 돌렸다. 축기 **+1일치** (누적 %.0f일)\n"
                            + "내공 화후 **%s** · 내력 %d",
                    days, hwahuLabel(naegong), Math.round(naegong * 3)));
        }
        String realm = promoteIfDue(sheet, String.valueOf(row.get("realm")));
        if (!realm.equals(row.get("realm"))) {
            body.append("\n💥 **돌파 — ").append(realm).append("에 올랐다** (개화한 몸 — 정식 무인이다)");
            db.logEvent("승급", "character", String.valueOf(chId), Map.of("경지", realm));
        }
        db.updateCharacter(chId, sheet, ((Number) row.get("wallet")).intValue(),
                realm, "강호", "청하현");
        event.replyEmbeds(new EmbedBuilder().setColor(INK)
                .setTitle("운기 — " + row.get("name")).setDescription(body).build()).queue();
    }

    // ─── 수련 — 기초 단련: 하루 한 번, 화후 1일치 (training 축의 최소 배선) ───

    @SuppressWarnings("unchecked")
    private void train(SlashCommandInteractionEvent event) throws Exception {
        if (notInRegion(event)) {
            return;
        }
        var found = requireDebuted(event, event.getUser());
        if (found.isEmpty()) {
            return;
        }
        Map<String, Object> row = found.get();
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        int today = db.worldDay();
        if (today == ((Number) sheet.getOrDefault("수련일", -1)).intValue()) {
            event.reply("오늘 몫의 수련은 끝났다 — 몸은 하루치 이상을 받아들이지 못한다. (다음 날에 다시)")
                    .setEphemeral(true).queue();
            return;
        }
        // 수련은 실전 연속식과 별개 축 — 일일 상한(cappedGrant) 미적용 (cultivation daily_cap 주석)
        double granted = rules.progression.trainingAccrualDays(1.0);
        sheet.put("수련일", today);
        sheet.put("화후_원장", ((Number) sheet.getOrDefault("화후_원장", 0)).doubleValue() + granted);

        long chId = ((Number) row.get("id")).longValue();
        StringBuilder body = new StringBuilder(String.format(
                "해 뜨기 전부터 몸을 다졌다. 수련 **+%.2f일치** (누적 %.2f일치)",
                granted, ((Number) sheet.get("화후_원장")).doubleValue()));
        for (String note : creditSkill(sheet, granted, today, null)) {
            body.append('\n').append(note);
        }
        // 사사 과제 연동 — 곽진이 새벽마다 지켜보고 있다
        Map<String, Object> sasa = (Map<String, Object>) sheet.get("사사");
        if (sasa != null) {
            int done = ((Number) sasa.getOrDefault("과제_수련", 0)).intValue() + 1;
            Map<String, Object> updated = new LinkedHashMap<>(sasa);
            updated.put("과제_수련", done);
            sheet.put("사사", updated);
            body.append("\n곽진의 과제 — 새벽 수련 **").append(Math.min(done, APPRENTICE_TRAININGS))
                    .append("/").append(APPRENTICE_TRAININGS).append("**")
                    .append(done >= APPRENTICE_TRAININGS ? " — 다 채웠다. `/혼천 사사`로 찾아가라." : "");
        }
        String realm = promoteIfDue(sheet, String.valueOf(row.get("realm")));
        if (!realm.equals(row.get("realm"))) {
            body.append("\n💥 **돌파 — ").append(realm).append("에 올랐다** (기초가 몸에 뱄다)");
            db.logEvent("승급", "character", String.valueOf(chId), Map.of("경지", realm));
        }
        db.updateCharacter(chId, sheet, ((Number) row.get("wallet")).intValue(),
                realm, "강호", "청하현");
        db.logEvent("수련", "character", String.valueOf(chId), Map.of("적립", granted));
        event.replyEmbeds(new EmbedBuilder().setColor(INK)
                .setTitle("수련 — " + row.get("name")).setDescription(body).build()).queue();
    }

    /** 관리자 정산 — 세계일 +1 (자정 스케줄러의 수동판, 감쇠·수련 리셋 테스트용) */
    private void settleDay(SlashCommandInteractionEvent event) throws Exception {
        if (event.getMember() == null || !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("서버 관리 권한이 필요하다.").setEphemeral(true).queue();
            return;
        }
        int day = db.advanceDay();
        event.replyEmbeds(new EmbedBuilder().setColor(BLOOD)
                .setTitle(day + "일차 아침이 밝았다")
                .setDescription("몸이 개운하다 — 일일 적립·수련·연속 감쇠가 새로 시작된다.").build()).queue();
    }

    // ─── 비무 — pvp direct_opposed (F22) + 극단 주사위 (F24) 실전 배선 ───

    private void startDuel(SlashCommandInteractionEvent event) throws Exception {
        if (notInRegion(event)) {
            return;
        }
        var opt = event.getOption("상대");
        if (opt == null) {
            event.reply("상대를 지정하라 — `/혼천 비무 @상대`").setEphemeral(true).queue();
            return;
        }
        User target = opt.getAsUser();
        if (target.isBot() || target.getId().equals(event.getUser().getId())) {
            event.reply("그 상대와는 비무가 성립하지 않는다.").setEphemeral(true).queue();
            return;
        }
        var challenger = requireDebuted(event, event.getUser());
        if (challenger.isEmpty()) {
            return;
        }
        var found = db.findCharacter(target.getId());
        if (found.isEmpty() || !"강호".equals(found.get().get("status"))) {
            event.reply(target.getEffectiveName() + " — 아직 강호에 나오지 않은 상대다.").setEphemeral(true).queue();
            return;
        }
        event.replyEmbeds(new EmbedBuilder().setColor(INK)
                        .setTitle("비무 신청")
                        .setDescription("**" + challenger.get().get("name") + "** 이(가) **" + found.get().get("name")
                                + "** 에게 포권했다.\n" + target.getAsMention() + " — 받겠는가?")
                        .build())
                .addComponents(ActionRow.of(
                        Button.success("bm:ok:" + event.getUser().getId() + ":" + target.getId(), "수락"),
                        Button.danger("bm:no:" + event.getUser().getId() + ":" + target.getId(), "사양"))).queue();
    }

    @SuppressWarnings("unchecked")
    private void onDuelAnswer(ButtonInteractionEvent event, boolean accepted, String challengerId,
                              String targetId) throws Exception {
        if (!event.getUser().getId().equals(targetId)) {
            event.reply("이 포권은 네게 온 것이 아니다.").setEphemeral(true).queue();
            return;
        }
        var challengerRow = db.findCharacter(challengerId);
        var targetRow = db.findCharacter(targetId);
        if (challengerRow.isEmpty() || targetRow.isEmpty()) {
            event.editMessage("비무 상대의 기록이 없다.").setComponents().queue();
            return;
        }
        String nameA = String.valueOf(challengerRow.get().get("name"));
        String nameB = String.valueOf(targetRow.get().get("name"));
        if (!accepted) {
            event.editMessageEmbeds(new EmbedBuilder().setColor(INK).setTitle("비무 — 사양")
                    .setDescription(nameB + " 이(가) 포권으로 사양했다. 강요는 비무가 아니다.").build())
                    .setComponents().queue();
            return;
        }

        int execA = duelExec(challengerRow.get());
        int execB = duelExec(targetRow.get());
        int rollA = dice.nextInt(6) + 1 + dice.nextInt(6) + 1;
        int rollB = dice.nextInt(6) + 1 + dice.nextInt(6) + 1;
        JudgmentEngine.Opposed opposed = rules.judgment.directOpposed(execA, rollA, execB, rollB);

        // 양측 화후 — 패배도 배움이다 (동수·비무_대련, 각자의 일일 상한)
        // 승자 = 실전 마크 +1 (battle_marks: "비무 승리" — 이류 승급 요건의 사건 축)
        int today = db.worldDay();
        boolean challengerWins = !opposed.draw() && opposed.margin() > 0;
        boolean targetWins = !opposed.draw() && opposed.margin() < 0;
        double grantedA = duelGrant(challengerRow.get(), today, challengerWins);
        double grantedB = duelGrant(targetRow.get(), today, targetWins);

        String winner = opposed.draw() ? null : (opposed.margin() > 0 ? nameA : nameB);
        String loser = opposed.draw() ? null : (opposed.margin() > 0 ? nameB : nameA);
        db.logEvent("비무", "character", String.valueOf(((Number) challengerRow.get().get("id")).longValue()),
                "character", String.valueOf(((Number) targetRow.get().get("id")).longValue()),
                Map.of("상대", nameB, "굴림A", rollA, "굴림B", rollB, "마진", opposed.margin(),
                        "등급", opposed.tier().name(), "무승부", opposed.draw()));

        String verdict = opposed.draw()
                ? "동점 — **상쇄, 무승부**"
                : "마진 **" + (opposed.margin() > 0 ? "+" : "") + opposed.margin()
                        + "** → **" + opposed.tier().name() + "** (" + nameA + " 시점) — 승자 **" + winner + "**";
        EmbedBuilder result = new EmbedBuilder().setColor(BLOOD)
                .setTitle("비무 — " + nameA + " 대 " + nameB)
                .setDescription(nameA + ": 무예 " + execA + " + 2d6 = **" + (execA + rollA) + "**" + extremeMark(rollA)
                        + "\n" + nameB + ": 무예 " + execB + " + 2d6 = **" + (execB + rollB) + "**" + extremeMark(rollB)
                        + "\n" + verdict
                        + "\n수련: " + nameA + String.format(" +%.2f일치 · ", grantedA)
                        + nameB + String.format(" +%.2f일치", grantedB)
                        + "\n\n" + Narration.duel(winner, loser, opposed.draw()));
        event.editMessageEmbeds(result.build()).setComponents().queue();
    }

    /** 비무 실행력 = 근력·민첩 중 높은 쪽 (베타 단순화 — 무공 숙련은 후속) */
    @SuppressWarnings("unchecked")
    private int duelExec(Map<String, Object> row) {
        Map<String, Object> attrs = (Map<String, Object>) ((Map<String, Object>) row.get("sheet")).get("능력치");
        int str = ((Number) attrs.getOrDefault("근력", 2)).intValue();
        int agi = ((Number) attrs.getOrDefault("민첩", 2)).intValue();
        return Math.max(str, agi);
    }

    @SuppressWarnings("unchecked")
    private double duelGrant(Map<String, Object> row, int today, boolean won) throws Exception {
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        Map<String, Object> streak = (Map<String, Object>) sheet.get("비무_연속");
        int rep = streak != null && today == ((Number) streak.getOrDefault("일", -1)).intValue()
                ? ((Number) streak.getOrDefault("횟수", 0)).intValue() : 0;
        sheet.put("비무_연속", Map.of("횟수", rep + 1, "일", today));
        double accrual = rules.progression.combatAccrualDays("동수", "비무_대련", rep);
        double granted = grantHwahu(sheet, accrual, today);
        creditSkill(sheet, granted, today, null);   // 비무 단발 보정은 후속 (양측 등급 거울 계산 필요)
        if (won) {
            sheet.put("실전_마크", ((Number) sheet.getOrDefault("실전_마크", 0)).intValue() + 1);
        }
        String realm = promoteIfDue(sheet, String.valueOf(row.get("realm")));
        long id = ((Number) row.get("id")).longValue();
        if (!realm.equals(row.get("realm"))) {
            db.logEvent("승급", "character", String.valueOf(id), Map.of("경지", realm));
        }
        db.updateCharacter(id, sheet, ((Number) row.get("wallet")).intValue(),
                realm, String.valueOf(row.get("status")), String.valueOf(row.get("location")));
        return granted;
    }

    // ─── 사사 — 무공 입문: 곽진의 태조장권 (skills.yml "가장 흔한 첫 무공") ───

    /** 시트에 기록되는 무공 기술 키 — 승급 요건 "아무 무공 입문"의 판별 집합 */
    private static final java.util.Set<String> MARTIAL_SKILLS = java.util.Set.of("검법", "태조장권");
    private static final int APPRENTICE_TRAININGS = 3;   // 곽진의 과제 — 새벽 수련 사흘

    @SuppressWarnings("unchecked")
    private void apprentice(SlashCommandInteractionEvent event) throws Exception {
        if (notInRegion(event)) {
            return;
        }
        var found = requireDebuted(event, event.getUser());
        if (found.isEmpty()) {
            return;
        }
        Map<String, Object> row = found.get();
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        long chId = ((Number) row.get("id")).longValue();
        int today = db.worldDay();

        Map<String, Object> skills = (Map<String, Object>) sheet.get("기술");
        if (skills != null && skills.keySet().stream().anyMatch(MARTIAL_SKILLS::contains)) {
            // 백지의 역설 — 사승은 백지에게만 (fortune_and_wanderer)
            event.replyEmbeds(new EmbedBuilder().setColor(INK).setTitle("사사 — 곽진")
                    .setDescription("곽진이 손을 저었다. \"이미 길이 있는 몸이다 — 두 길을 함께 걷다간 둘 다 잃는다. "
                            + "네 집의 것을 갈고닦아라.\"").build()).queue();
            return;
        }
        Map<String, Object> attrs = (Map<String, Object>) sheet.get("능력치");
        if (((Number) attrs.getOrDefault("근력", 2)).intValue() < 2) {
            // 태조장권 required_stats { 근력: 2 }
            event.reply("곽진이 어깨를 짚어 보더니 고개를 저었다. \"아직 몸이 여물지 않았다 — 밥부터 먹여라.\"")
                    .setEphemeral(true).queue();
            return;
        }

        Map<String, Object> sasa = (Map<String, Object>) sheet.get("사사");
        if (sasa == null) {
            sheet.put("사사", Map.of("스승", "곽진", "무공", "태조장권", "과제_수련", 0));
            db.updateCharacter(chId, sheet, ((Number) row.get("wallet")).intValue(),
                    String.valueOf(row.get("realm")), "강호", "청하현");
            db.logEvent("사사_시작", "character", String.valueOf(chId), "곽진", Map.of("스승", "곽진"));
            event.replyEmbeds(new EmbedBuilder().setColor(INK).setTitle("사사 — 곽진")
                    .setDescription("장터 어귀의 표사 곽진은 한참 아래위를 훑어보더니 짧게 말했다.\n\n"
                            + "\"배우고 싶으면 성의부터 보여라 — **새벽 수련 사흘**. 하루라도 빼먹으면 없던 일이다.\"\n\n"
                            + "*(`/혼천 수련` " + APPRENTICE_TRAININGS + "회를 채우고 다시 찾아오라)*").build()).queue();
            return;
        }
        int done = ((Number) sasa.getOrDefault("과제_수련", 0)).intValue();
        if (done < APPRENTICE_TRAININGS) {
            event.reply("곽진: \"아직이다 — 새벽 수련 " + done + "/" + APPRENTICE_TRAININGS
                    + ". 몸이 먼저 대답하게 하라.\"").setEphemeral(true).queue();
            return;
        }
        if (today == ((Number) sasa.getOrDefault("마지막_시도일", -1)).intValue()) {
            event.reply("곽진: \"오늘 보인 것은 봤다 — 내일 다시.\" (하루 한 번)").setEphemeral(true).queue();
            return;
        }

        // 문답 — 곽진 앞에서 권형을 밟는다 (근력 판정, 공개)
        int str = ((Number) attrs.getOrDefault("근력", 2)).intValue();
        int roll = dice.nextInt(6) + 1 + dice.nextInt(6) + 1;
        int resist = 10;
        JudgmentEngine.Tier tier = rules.judgment.resolve(str + 2, roll, resist);
        int margin = str + 2 + roll - resist;
        db.logEvent("사사_문답", "character", String.valueOf(chId),
                Map.of("굴림", roll, "마진", margin, "등급", tier.name()));

        EmbedBuilder result = new EmbedBuilder().setColor(INK)
                .setTitle("시험 — 곽진 앞에서 권형을 밟는다")
                .setDescription("**근력 " + str + "** + 2d6 = **" + (str + 2 + roll) + "** vs " + resist
                        + " │ 마진 **" + (margin >= 0 ? "+" : "") + margin + "** → **" + tier.name() + "**");
        if (margin >= 0) {
            Map<String, Object> newSkills = skills == null ? new LinkedHashMap<>() : new LinkedHashMap<>(skills);
            newSkills.put("태조장권", 0);
            sheet.put("기술", newSkills);
            sheet.remove("사사");
            StringBuilder body = new StringBuilder(
                    "곽진이 처음으로 자세를 고쳐 앉았다. \"됐다 — 오늘부터 **태조장권**이다. "
                            + "가장 흔한 권법이지만, 흔한 것은 이유가 있어 흔한 법이다.\"\n기술에 **태조장권 0** 이 올랐다.");
            String realm = promoteIfDue(sheet, String.valueOf(row.get("realm")));
            if (!realm.equals(row.get("realm"))) {
                body.append("\n💥 **돌파 — ").append(realm).append("에 올랐다** (쌓아 둔 기초가 문을 밀었다)");
                db.logEvent("승급", "character", String.valueOf(chId), Map.of("경지", realm));
            }
            db.updateCharacter(chId, sheet, ((Number) row.get("wallet")).intValue(),
                    realm, "강호", "청하현");
            db.logEvent("입문", "character", String.valueOf(chId), Map.of("무공", "태조장권", "스승", "곽진"));
            event.replyEmbeds(result.build(), new EmbedBuilder().setColor(BLOOD)
                    .setTitle("입문 — 태조장권").setDescription(body.toString()).build()).queue();
        } else {
            Map<String, Object> updated = new LinkedHashMap<>(sasa);
            updated.put("마지막_시도일", today);
            sheet.put("사사", updated);
            db.updateCharacter(chId, sheet, ((Number) row.get("wallet")).intValue(),
                    String.valueOf(row.get("realm")), "강호", "청하현");
            event.replyEmbeds(result.build(), new EmbedBuilder().setColor(INK)
                    .setTitle("곽진은 고개를 저었다")
                    .setDescription("\"힘만 앞섰다 — 권은 어깨가 아니라 허리로 치는 것이다. 내일 다시.\"").build()).queue();
        }
    }

    // ─── 승급 — 요건은 문턱이고 계기가 문이다 (cultivation_stages) ───

    /** 기초 단련 3개월 = 90일치 — 엔진 환산 (하드코딩 아님) */
    private static final int BASIC_TRAINING_DAYS =
            com.honcheon.core.rules.ProgressionEngine.durationToDays("3개월");

    /**
     * 범인 → 삼류 (trigger 자동): 아무 무공 입문(기술에 검법 — 숙련 0도 입문이다) + 기초 단련 3개월.
     * 이류+ 요건(주력 숙련·실전 마크)은 기술 화후 축과 함께 다음 증분. 승급 후 경지를 돌려준다.
     */
    @SuppressWarnings("unchecked")
    private String promoteIfDue(Map<String, Object> sheet, String realm) {
        Map<String, Object> skills = (Map<String, Object>) sheet.get("기술");
        if ("범인".equals(realm)) {
            boolean martial = skills != null && skills.keySet().stream().anyMatch(MARTIAL_SKILLS::contains);
            double hwahu = ((Number) sheet.getOrDefault("화후_원장", 0)).doubleValue();
            return (martial && hwahu >= BASIC_TRAINING_DAYS) ? "삼류" : realm;
        }
        if ("삼류".equals(realm) && skills != null) {
            // 이류 (trigger 자동): 주력 무공 숙련 2 + 실전 마크 1 (cultivation_stages·battle_marks)
            int mastery = MARTIAL_SKILLS.stream().filter(skills::containsKey)
                    .mapToInt(k -> ((Number) skills.get(k)).intValue()).max().orElse(0);
            int marks = ((Number) sheet.getOrDefault("실전_마크", 0)).intValue();
            return (mastery >= 2 && marks >= 1) ? "이류" : realm;
        }
        if ("이류".equals(realm) && skills != null) {
            // 일류 (trigger 자동 — 개화가 사실상의 관문): 주력 숙련 3 + 개화 + 실전 마크 3
            int mastery = MARTIAL_SKILLS.stream().filter(skills::containsKey)
                    .mapToInt(k -> ((Number) skills.get(k)).intValue()).max().orElse(0);
            int marks = ((Number) sheet.getOrDefault("실전_마크", 0)).intValue();
            boolean opened = "개화".equals(sheet.get("단전"));
            return (mastery >= 3 && opened && marks >= 3) ? "일류" : realm;
        }
        return realm;   // 절정+ 은 벽(壁) — 깨달음 사건이 문 (자동 승급 없음)
    }

    private String extremeMark(int roll) {
        if (!rules.judgment.extremeDiceEnabled()) {
            return "";
        }
        return roll == 12 ? " ⚡쌍륙" : roll == 2 ? " 💥쌍일" : "";
    }

    // ─── 상태 객체 ───

    static final class Creation {
        final Map<String, Integer> scores = new LinkedHashMap<>();
        final List<String> answers = new ArrayList<>();
    }

    record Character(long id, String name, String disposition, String family, String incident,
                     String bracket, int age, Map<String, Integer> attrs, int wallet) {
    }

    /** 시트 원본을 함께 들고 다닌다 — 진행 영속화의 갱신 지점 */
    record Born(Character ch, Map<String, Object> sheet) {
    }

    static final class Seojang {
        final Character ch;
        final Map<String, Object> sheet;

        Seojang(Character ch, Map<String, Object> sheet) {
            this.ch = ch;
            this.sheet = sheet;
        }
    }

    record Scene(String title, int resist, Choice[] choices) {
    }

    record Choice(String label, String stat, int bonus) {
    }

    record Beast(String name, String gap, int resist, String peltKey, String peltLabel) {
    }

    /** DB 행 → Character 복원 — 시트의 표기(공백)를 키(밑줄)로 되돌린다 */
    @SuppressWarnings("unchecked")
    private Character fromDb(Map<String, Object> row) {
        Map<String, Object> sheet = (Map<String, Object>) row.get("sheet");
        Map<String, Integer> attrs = new LinkedHashMap<>();
        ((Map<String, Object>) sheet.get("능력치"))
                .forEach((k, v) -> attrs.put(k, ((Number) v).intValue()));
        return new Character(((Number) row.get("id")).longValue(), String.valueOf(row.get("name")),
                String.valueOf(sheet.get("성향")),
                String.valueOf(sheet.get("집안")).replace(' ', '_'),
                String.valueOf(sheet.get("발단")).replace(' ', '_'),
                String.valueOf(sheet.get("연령대")),
                ((Number) sheet.getOrDefault("나이", 0)).intValue(),
                attrs, ((Number) row.get("wallet")).intValue());
    }
}
