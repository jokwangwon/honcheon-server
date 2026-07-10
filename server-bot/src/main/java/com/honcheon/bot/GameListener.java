package com.honcheon.bot;

import com.honcheon.core.rules.JudgmentEngine;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
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
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 봇 알파의 심장 — 생성 문답(유년의 기억 5문항) → 운명 생성 → 서장 스레드 → 판정 턴 루프.
 * 규칙: 판정은 embed 공개 (interface_decision), 렌더는 폴백 템플릿 (llm.yml failure_handling),
 *       수치는 엔진만 정한다.
 */
public final class GameListener extends ListenerAdapter {

    private static final List<String> STATS = List.of("근력", "민첩", "체력", "내공", "감각", "화술", "지혜");
    private static final Color INK = new Color(0x2B2B2B);
    private static final Color BLOOD = new Color(0x8B2E2E);

    private final Rules rules;
    private final Db db;
    private final Random dice = new Random();

    private final Map<String, Creation> creations = new ConcurrentHashMap<>();
    private final Map<Long, Seojang> seojangs = new ConcurrentHashMap<>();

    public GameListener(Rules rules, Db db) {
        this.rules = rules;
        this.db = db;
    }

    // ─── 슬래시 명령 ───

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        try {
            switch (String.valueOf(event.getSubcommandName())) {
                case "시작" -> startCreation(event);
                case "원장" -> showSheet(event);
                default -> event.replyEmbeds(help()).setEphemeral(true).queue();
            }
        } catch (Exception e) {
            event.reply("오류: " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    private void startCreation(SlashCommandInteractionEvent event) throws Exception {
        if (db.findCharacter(event.getUser().getId()).isPresent()) {
            event.reply("이미 살아 있는 캐릭터가 있다 — `/혼천 원장`으로 확인하라. (계정당 하나, 죽음만이 끝낸다)")
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
                .setTitle("원장 — " + ch.get("name"))
                .setDescription(sheet.get("나이") + "세 " + sheet.get("연령대") + " · " + ch.get("realm")
                        + " · " + sheet.get("집안") + " · 성향 " + sheet.get("성향"));
        StringBuilder stats = new StringBuilder();
        @SuppressWarnings("unchecked")
        Map<String, Integer> attr = (Map<String, Integer>) sheet.get("능력치");
        attr.forEach((k, v) -> stats.append(k).append(' ').append(v).append("  "));
        eb.addField("능력치", stats.toString(), false);
        eb.addField("전낭", ch.get("wallet") + "문", true);
        eb.addField("발단", String.valueOf(sheet.get("발단")), true);
        event.replyEmbeds(eb.build()).setEphemeral(true).queue();
    }

    private MessageEmbed help() {
        return new EmbedBuilder().setColor(INK).setTitle("혼천 — 무협 텍스트 RPG")
                .setDescription("`/혼천 시작` 캐릭터 생성 (유년의 기억 5문항 → 운명이 나머지를 정한다)\n"
                        + "`/혼천 원장` 시트 조회\n판정은 공개(2d6), 서사는 스레드에서. 죽음은 비가역 — 계정당 한 삶.")
                .build();
    }

    // ─── 버튼 라우팅 ───

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        try {
            String[] id = event.getComponentId().split(":");
            switch (id[0]) {
                case "ct" -> onTestAnswer(event, Integer.parseInt(id[1]), id[2]);
                case "tn" -> onTurnChoice(event, Integer.parseInt(id[1]), Integer.parseInt(id[2]));
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
        Character made = birth(event.getUser().getId(), event.getUser().getEffectiveName(), c);
        event.editMessageEmbeds(birthEmbed(made)).setComponents().queue();
        openSeojang(event, made);
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

    private Character birth(String discordId, String name, Creation c) throws Exception {
        int max = c.scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> top = c.scores.entrySet().stream()
                .filter(e -> e.getValue() == max).map(Map.Entry::getKey).toList();
        String disposition = String.join("·", top);   // wide_tie: 다면성은 오류가 아니라 결과다

        List<String> familyKeys = new ArrayList<>(rules.families().keySet());
        String family = familyKeys.get(dice.nextInt(familyKeys.size()));
        List<String> incidentKeys = new ArrayList<>(rules.incidents().keySet());
        String incident = incidentKeys.get(dice.nextInt(incidentKeys.size()));
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
        sheet.put("연령대", bracket);
        sheet.put("나이", age);
        sheet.put("발단", incident.replace('_', ' '));
        sheet.put("능력치", attrs);
        sheet.put("유년의_기억", c.answers);

        long id = db.createCharacter(discordId, name, sheet, wallet);
        db.logEvent("생성", "character", String.valueOf(id),
                Map.of("성향", disposition, "집안", family, "발단", incident, "나이", age));
        return new Character(id, name, disposition, family, incident, bracket, age, attrs, wallet);
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

    // ─── 서장 — 프라이빗 스레드 + 발단 3장면 턴 루프 ───

    private void openSeojang(ButtonInteractionEvent event, Character ch) {
        TextChannel channel = event.getChannel().asTextChannel();
        channel.createThreadChannel("서장 — " + ch.name, true).queue(thread -> {
            thread.addThreadMember(event.getUser()).queue();
            seojangs.put(thread.getIdLong(), new Seojang(ch));
            postScene(thread, ch, 0, null);
        });
    }

    private void onTurnChoice(ButtonInteractionEvent event, int scene, int option) throws Exception {
        Seojang s = seojangs.get(event.getChannel().getIdLong());
        if (s == null) {
            event.editMessage("서장 세션이 만료됐다 (봇 재시작) — 알파 한계: `/혼천 시작` 전 상태는 원장에 남아 있다.")
                    .setComponents().queue();
            return;
        }
        Scene sc = scenes(s.ch)[scene];
        Choice pick = sc.choices[option];

        int stat = s.ch.attrs.getOrDefault(pick.stat, 2);
        int roll = dice.nextInt(6) + 1 + dice.nextInt(6) + 1;
        JudgmentEngine.Tier tier = rules.judgment.resolve(stat + pick.bonus, roll, sc.resist);
        int margin = stat + pick.bonus + roll - sc.resist;
        db.logEvent("판정", "character", String.valueOf(s.ch.id),
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
            postScene(thread, s.ch, next, tier.name());
        } else {
            thread.sendMessageEmbeds(new EmbedBuilder().setColor(BLOOD)
                    .setTitle("서장의 첫 밤이 저물었다")
                    .setDescription(Narration.epilogue(s.ch, tier.name())
                            + "\n\n*(알파 범위는 여기까지 — 출도·공유 세계는 봇 베타에서 열린다)*").build()).queue();
            seojangs.remove(thread.getIdLong());
        }
    }

    private void postScene(ThreadChannel thread, Character ch, int idx, String prevTier) {
        Scene sc = scenes(ch)[idx];
        EmbedBuilder eb = new EmbedBuilder().setColor(INK)
                .setTitle(sc.title)
                .setDescription(Narration.scene(ch, idx, prevTier));
        List<Button> buttons = new ArrayList<>();
        for (int i = 0; i < sc.choices.length; i++) {
            buttons.add(Button.primary("tn:" + idx + ":" + i, sc.choices[i].label));
        }
        thread.sendMessageEmbeds(eb.build()).setComponents(ActionRow.of(buttons)).queue();
    }

    /** 발단 3장면 — 엔진 골격 (서사는 Narration 템플릿, 수치는 여기) */
    private Scene[] scenes(Character ch) {
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

    // ─── 상태 객체 ───

    static final class Creation {
        final Map<String, Integer> scores = new LinkedHashMap<>();
        final List<String> answers = new ArrayList<>();
    }

    record Character(long id, String name, String disposition, String family, String incident,
                     String bracket, int age, Map<String, Integer> attrs, int wallet) {
    }

    static final class Seojang {
        final Character ch;

        Seojang(Character ch) {
            this.ch = ch;
        }
    }

    record Scene(String title, int resist, Choice[] choices) {
    }

    record Choice(String label, String stat, int bonus) {
    }
}
