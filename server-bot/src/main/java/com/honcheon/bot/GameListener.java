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
                case "원장" -> showSheet(event);
                case "사냥" -> startHunt(event);
                case "비무" -> startDuel(event);
                case "지역등록" -> registerRegion(event);
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
                        + " · " + sheet.get("집안") + " · 성향 " + sheet.get("성향")
                        + "\n" + ch.get("status") + " · " + ch.get("location"));
        StringBuilder stats = new StringBuilder();
        @SuppressWarnings("unchecked")
        Map<String, Integer> attr = (Map<String, Integer>) sheet.get("능력치");
        attr.forEach((k, v) -> stats.append(k).append(' ').append(v).append("  "));
        eb.addField("능력치", stats.toString(), false);
        eb.addField("전낭", ch.get("wallet") + "문", true);
        eb.addField("발단", String.valueOf(sheet.get("발단")), true);
        double hwahu = ((Number) sheet.getOrDefault("화후_원장", 0)).doubleValue();
        eb.addField("화후 원장", String.format("%.2f일치", hwahu), true);
        event.replyEmbeds(eb.build()).setEphemeral(true).queue();
    }

    private MessageEmbed help() {
        return new EmbedBuilder().setColor(INK).setTitle("혼천 — 무협 텍스트 RPG")
                .setDescription("`/혼천 시작` 캐릭터 생성 (유년의 기억 5문항 → 운명이 나머지를 정한다)\n"
                        + "`/혼천 원장` 시트 조회\n"
                        + "`/혼천 사냥` 청하현 뒷산 사냥 — 화후와 생계 (출도 후, 지역 채널에서)\n"
                        + "`/혼천 비무 @상대` 비무 신청 — 양측 2d6 대립 판정 (출도 후)\n"
                        + "`/혼천 지역등록` 이 채널을 청하현으로 등록 (서버 관리자)\n"
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
        @SuppressWarnings("unchecked")
        Map<String, Object> familyCfg = (Map<String, Object>) rules.families().get(family);
        @SuppressWarnings("unchecked")
        List<String> incidentPool = familyCfg != null && familyCfg.get("incident_pool") instanceof List<?>
                ? (List<String>) familyCfg.get("incident_pool")
                : new ArrayList<>(rules.incidents().keySet());
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
            event.editMessage("이 서장은 이미 끝났거나 기록이 없다 — `/혼천 원장`으로 상태를 확인하라.")
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
            db.updateCharacter(s.ch.id(), s.sheet, s.ch.wallet(), "서장", "서장");
            postScene(thread, s.ch, next, tier.name());
        } else {
            closeSeojang(thread, s, tier.name());
        }
    }

    /** 서장 종료 = 출도 — 신분 강호·위치 청하현, 지역 채널이 열린다 */
    private void closeSeojang(ThreadChannel thread, Seojang s, String lastTier) throws Exception {
        s.sheet.remove("서장_직전등급");
        db.updateCharacter(s.ch.id(), s.sheet, s.ch.wallet(), "강호", "청하현");
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
                    ? String.format("화후 **+%.2f일치**", granted)
                    : "화후 적립 없음 — *오늘은 몸이 벅차다* (일일 상한)");
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
        db.updateCharacter(chId, sheet, wallet, "강호", "청하현");
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
        int today = db.worldDay();
        double grantedA = duelGrant(challengerRow.get(), today);
        double grantedB = duelGrant(targetRow.get(), today);

        String winner = opposed.draw() ? null : (opposed.margin() > 0 ? nameA : nameB);
        String loser = opposed.draw() ? null : (opposed.margin() > 0 ? nameB : nameA);
        db.logEvent("비무", "character", String.valueOf(((Number) challengerRow.get().get("id")).longValue()),
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
                        + "\n화후: " + nameA + String.format(" +%.2f일치 · ", grantedA)
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
    private double duelGrant(Map<String, Object> row, int today) throws Exception {
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        Map<String, Object> streak = (Map<String, Object>) sheet.get("비무_연속");
        int rep = streak != null && today == ((Number) streak.getOrDefault("일", -1)).intValue()
                ? ((Number) streak.getOrDefault("횟수", 0)).intValue() : 0;
        sheet.put("비무_연속", Map.of("횟수", rep + 1, "일", today));
        double accrual = rules.progression.combatAccrualDays("동수", "비무_대련", rep);
        double granted = grantHwahu(sheet, accrual, today);
        db.updateCharacter(((Number) row.get("id")).longValue(), sheet,
                ((Number) row.get("wallet")).intValue(),
                String.valueOf(row.get("status")), String.valueOf(row.get("location")));
        return granted;
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
