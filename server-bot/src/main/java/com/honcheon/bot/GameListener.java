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
                case "출행" -> travel(event);
                case "소문" -> rumorBoard(event);        // B — 저잣거리에 도달한 소문
                case "의방" -> clinicVisit(event);       // A — 부상 치료·외상 상환
                case "구조" -> rescue(event);            // A — 빈사의 동행을 살린다
                case "전장" -> bank(event);              // A — 예치·상속인 지정 (죽어서 남기는 것)
                case "지역등록" -> registerRegion(event);
                case "정산" -> settleDay(event);
                case "사망" -> adminKill(event);
                case "사선" -> adminDeathLine(event);    // A — 죽음 검증용 (관리자)
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

        // A — 전생이 있는가. 죽은 자의 흔적(유산·원한·명성)은 세계에 남아 있다
        //     (death_and_legacy new_character.lineage_choice: 혈연 시작 / 무관 시작)
        var past = db.lastDeadCharacter(event.getUser().getId());
        if (past.isPresent()) {
            Map<String, Object> dead = past.get();
            long deadId = ((Number) dead.get("id")).longValue();
            int estate = db.bankBalance(deadId, BANK_BRANCH);
            event.replyEmbeds(new EmbedBuilder().setColor(BLOOD)
                            .setTitle("새 삶 — 그러나 세계는 전생을 기억한다")
                            .setDescription("**" + dead.get("name") + "**은(는) " + dead.get("died_day")
                                    + "일차에 죽었다 (" + dead.get("realm") + ").\n"
                                    + "그 이름은 이미 세계의 연표에 있다 — 사람들이 회고할 것이고, "
                                    + "그가 남긴 원한은 아직 누군가의 장부에 적혀 있다.\n\n"
                                    + "**혈연으로 시작한다** — 유족·제자로서 강호에 선다.\n"
                                    + "· " + String.join(" · ", rules.legacy.lineageGrants()).replace('_', ' ')
                                    + (estate > 0 ? "\n· 전장에 남은 **" + estate + "문**에 손이 닿는다" : "")
                                    + "\n· 집안은 **" + rules.legacy.lineageFamilyTemplate().replace('_', ' ')
                                    + "** 문법을 따른다\n\n"
                                    + "**무관으로 시작한다** — 완전한 백지. 전생과 접점이 없다.\n"
                                    + "· 물려받는 것도, 짊어지는 것도 없다"
                                    + (estate > 0 ? "\n· 전장의 " + estate
                                            + "문은 무주공산이 된다 (세력 귀속)" : ""))
                            .build())
                    .addComponents(ActionRow.of(
                            Button.danger("ln:kin", "혈연으로 시작한다 (유산 · 피의 장부)"),
                            Button.secondary("ln:none", "무관으로 시작한다 (백지)")))
                    .setEphemeral(true).queue();
            return;
        }
        event.replyEmbeds(questionEmbed(0))
                .addComponents(ActionRow.of(questionButtons(0)))
                .setEphemeral(true).queue();
    }

    /** 혈연/무관 2택 — 전생의 유산과 원한을 짊어질 것인가 (death_and_legacy lineage_choice) */
    private void onLineageChoice(ButtonInteractionEvent event, boolean kin) throws Exception {
        Creation c = creations.get(event.getUser().getId());
        if (c == null) {
            event.editMessage("세션이 만료됐다 — `/혼천 시작`으로 다시.").setComponents().queue();
            return;
        }
        c.lineage = kin;
        if (!kin) {
            // 무관 시작 — 전장의 몫은 무주공산이 된다 (legacy.예치.order 3순위: 세력_귀속_무주공산)
            db.lastDeadCharacter(event.getUser().getId()).ifPresent(dead -> {
                try {
                    long deadId = ((Number) dead.get("id")).longValue();
                    int estate = db.bankBalance(deadId, BANK_BRANCH);
                    if (estate > 0) {
                        db.bankMove(deadId, BANK_BRANCH, -estate);
                        db.logEvent("상속_무주공산", "character", String.valueOf(deadId), "faction",
                                "hyeollyeong", Map.of("금액", estate, "귀속", "관 — 무주공산"));
                    }
                } catch (Exception ignored) {
                    // 유산 처리 실패가 새 삶을 막지는 않는다 (원장에 남은 것이 진실이다)
                }
            });
        }
        event.editMessageEmbeds(questionEmbed(0))
                .setComponents(ActionRow.of(questionButtons(0))).queue();
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

        // ─── 단계 4 — 몸(부상·수명)·장부(외상·예치)·세계(세력 관계·피의 장부) ───
        long chId = ((Number) ch.get("id")).longValue();
        int today = db.worldDay();
        String wound = woundOf(sheet);
        if (wound != null) {
            eb.addField("🩸 부상", "**" + wound + "** — 모든 판정 " + rules.conditionModifier(wound)
                            + ("빈사".equals(wound)
                                    ? "\n**사망 위기** — 다음 세계일에 체력 판정이 다시 굴러간다 (`/혼천 구조`)"
                                    : " (`/혼천 의방`)"),
                    false);
        }
        int debt = ((Number) sheet.getOrDefault("외상", 0)).intValue();
        int deposit = db.bankBalance(chId, BANK_BRANCH);
        if (debt > 0 || deposit > 0) {
            eb.addField("장부", (deposit > 0 ? "전장 예치 **" + deposit + "문**" : "")
                            + (debt > 0 ? (deposit > 0 ? " · " : "") + "의원 외상 **" + debt + "문**" : ""),
                    true);
        }
        int burned = ((Number) sheet.getOrDefault("수명_소모", 0)).intValue();
        if (burned > 0) {
            eb.addField("선천진기", "수명 **" + (rules.innateQiTotal() - burned) + "/"
                    + rules.innateQiTotal() + "년** (태운 것은 돌아오지 않는다)", true);
        }
        List<Db.Standing> standings = db.standings(chId, today, rules.factions);
        if (!standings.isEmpty()) {
            StringBuilder rel = new StringBuilder();
            for (Db.Standing s : standings) {
                rel.append(standingLine(s)).append('\n');
            }
            eb.addField("세력 관계", rel.toString(), false);
        }
        if (sheet.get("피의_장부") instanceof List<?> grudges && !grudges.isEmpty()) {
            eb.addField("피의 장부", "전생의 원한: **"
                    + grudges.stream().map(g -> killerLabel(String.valueOf(g)))
                            .collect(java.util.stream.Collectors.joining(" · "))
                    + "**\n*이 빚은 죽음으로 사라지지 않았다.*", false);
        }
        if (sheet.get("전생") instanceof Map<?, ?> past) {
            eb.setFooter("전생: " + past.get("이름") + " — 그 이름은 세계의 연표에 있다");
        }
        if (sheet.get("심법") != null) {
            boolean opened = "개화".equals(sheet.get("단전"));
            eb.addField("심법", sheet.get("심법") + (opened ? " · 단전 개화" : " · 단전 미개화"), true);
            if (opened) {
                double naegong = naegongOf(((Number) sheet.getOrDefault("축기_원장", 0)).doubleValue());
                int cur = ((Number) sheet.getOrDefault("내력", 0)).intValue();
                eb.addField("내공", hwahuLabel(naegong) + " · 내력 " + cur + "/"
                        + rules.energy.pool(naegong), true);
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
                        + "`/혼천 출행 화산` 산문으로 직행한다 — 여비·기간을 치르고 문 앞에 선다 (조건 불문)\n"
                        + "`/혼천 소문` 저잣거리에 도는 말 — 퍼질수록 이야기가 달라진다\n"
                        + "`/혼천 의방` 부상을 다스리고 외상을 갚는다 (유문의 의방)\n"
                        + "`/혼천 구조 @상대` 빈사의 동행을 지혈한다 — 의술 판정 (사람을 살리는 유일한 손)\n"
                        + "`/혼천 전장 [예치] [인출] [상속인]` 금서방의 전장 — 죽어서 남길 수 있는 유일한 재산\n"
                        + "`/혼천 지역등록` 이 채널을 청하현으로 등록 (서버 관리자)\n"
                        + "`/혼천 정산` 세계일 +1 (서버 관리자 — 자정에는 자동)\n"
                        + "`/혼천 사망 <NPC>` NPC를 죽인다 — 연쇄 검증용 (서버 관리자)\n"
                        + "`/혼천 사선 @상대` 플레이어에게 사선을 긋는다 — 죽음 검증용 (서버 관리자)\n"
                        + "판정은 공개(2d6), 서사는 스레드에서.\n"
                        + "**죽음은 비가역 — 계정당 한 삶.** 패배의 기본값은 죽음이 아니다(제압·중상). "
                        + "그러나 살의 앞에서는 빈사에 이르고, 개입이 없으면 죽는다.")
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
                case "ex" -> onGateChoice(event, id[1], Integer.parseInt(id[2]), id[3]);
                case "ln" -> onLineageChoice(event, "kin".equals(id[1]));   // 새 삶 — 혈연 / 무관
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

        // A — 혈연 시작은 집안을 고르지 않는다: 몰락한 무가의 자식 문법이 곧 '물려받은 것'의 문법이다
        List<String> familyKeys = new ArrayList<>(rules.families().keySet());
        String family = c.lineage ? rules.legacy.lineageFamilyTemplate()
                : familyKeys.get(dice.nextInt(familyKeys.size()));
        if (!familyKeys.contains(family)) {
            family = familyKeys.get(dice.nextInt(familyKeys.size()));   // 등록부에 없으면 운명에 맡긴다
        }
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

        // A — 전생의 흔적: 유산(예치분) · 피의 장부(원한) · 명성(이름)은 세계에 남아 있다
        if (c.lineage) {
            var past = db.lastDeadCharacter(discordId);
            if (past.isPresent()) {
                long deadId = ((Number) past.get().get("id")).longValue();
                String deadName = String.valueOf(past.get().get("name"));
                db.setLineage(id, deadId);
                int estate = db.bankBalance(deadId, BANK_BRANCH);
                if (estate > 0) {
                    db.bankMove(deadId, BANK_BRANCH, -estate);
                    db.bankMove(id, BANK_BRANCH, estate);
                    db.logEvent("상속", "character", String.valueOf(deadId), "character",
                            String.valueOf(id), Map.of("금액", estate, "순위", "혈연_시작"));
                }
                // 피의 장부 상속 — 전생의 원한이 이 아이의 짐이 된다 (복수 훅)
                List<String> grudges = new ArrayList<>();
                for (Map<String, Object> e : db.eventsOf("피의_장부", String.valueOf(deadId))) {
                    grudges.add(String.valueOf(e.get("target_id")));
                }
                sheet.put("전생", Map.of("이름", deadName, "id", deadId));
                if (!grudges.isEmpty()) {
                    sheet.put("피의_장부", grudges);
                }
                db.updateCharacter(id, sheet, wallet, "범인", "서장", "서장");
                db.logEvent("혈연_시작", "character", String.valueOf(id), "character",
                        String.valueOf(deadId), Map.of("전생", deadName, "상속분", estate,
                                "피의_장부", grudges, "물려받은_것", rules.legacy.lineageGrants()));
            }
        }
        db.logEvent("생성", "character", String.valueOf(id),
                Map.of("성향", disposition, "집안", family, "발단", incident, "나이", age,
                        "혈연", c.lineage));
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

    @SuppressWarnings("unchecked")
    private void startHunt(SlashCommandInteractionEvent event) throws Exception {
        if (notInRegion(event)) {
            return;
        }
        var hunter = requireDebuted(event, event.getUser());
        if (hunter.isEmpty()
                || blockedByWound(event, (Map<String, Object>) hunter.get().get("sheet"))) {
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
        // 발경 — 개화자는 타격에 기를 싣는다 (내력 1 → +1, 부족하면 맨 기술)
        int balgyeong = canBalgyeong(sheet, String.valueOf(row.get("realm"))) ? 1 : 0;
        if (balgyeong > 0) {
            payBalgyeong(sheet);
        }
        int wound = woundMod(sheet);   // 성치 않은 몸으로 산에 오르면 산이 안다
        int power = stat + 2 + balgyeong + wound;
        int roll = dice.nextInt(6) + 1 + dice.nextInt(6) + 1;
        JudgmentEngine.Tier tier = rules.judgment.resolve(power, roll, beast.resist());
        int margin = power + roll - beast.resist();

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
            // B1 — 장쇠가 죽으면 파는 곳이 없어진다. 마삼이 좌판을 차지하면 장물 시세(0.3)다
            Deaths.Gap market = gapOf(MARKET_NPC, today, db.deadNpcs());
            int base = rules.economy.basePrice("사냥_부산물", beast.peltKey());
            if (!market.open()) {
                gains.append(gains.isEmpty() ? "" : " · ").append(beast.peltLabel())
                        .append(" — **살 사람이 없다** (좌판이 비었다: ").append(market.state()).append(')');
            } else {
                Double rate = market.buyRate();
                // C — 세력 반응이 값으로 돌아온다: 악명이 붙은 손에서는 아무도 제값을 안 준다
                //     (economy trading.black_market.rate — 뒷거래 시세. 새 수치 발명 없음)
                boolean notorious = rate == null && isNotorious(chId, today);
                if (notorious) {
                    rate = rules.blackMarketRate();
                }
                int sale = rate == null ? rules.economy.npcBuyPrice(base, false)
                        : (int) Math.floor(base * rate);
                wallet += sale;
                gains.append(gains.isEmpty() ? "" : " · ").append(beast.peltLabel())
                        .append(" 매각 **+").append(sale).append("문**")
                        .append(rate == null ? ""
                                : notorious
                                        ? " *(값을 후려친다 — 네 이름이 저잣거리에 돌고 있다. ×" + rate + ")*"
                                        : " *(" + rules.npcName(market.actorKey())
                                                + "의 좌판 — 장물 시세 ×" + rate + ")*");
            }
        }
        if (gains.isEmpty()) {
            gains.append("소득 없음");
        }
        String realm = promoteIfDue(sheet, String.valueOf(row.get("realm")));
        if (!realm.equals(row.get("realm"))) {
            gains.append("\n💥 **돌파 — ").append(realm).append("에 올랐다** (기초가 몸에 뱄다)");
            db.logEvent("승급", "character", String.valueOf(chId), Map.of("경지", realm));
        }
        db.logEvent("사냥", "character", String.valueOf(chId),
                Map.of("짐승", beast.name(), "접근", approach[0], "굴림", roll, "마진", margin,
                        "등급", tier.name()));

        // A — 사선: 짐승에게는 살의가 있다. 치명적 실패는 이빨로 돌아온다.
        //     상수(곰)의 급소 일격은 두 칸 (combat.critical). 뒷산은 야외다 — 아무도 옮겨 주지 않는다.
        String fall = "";
        if ("critical_failure".equals(tier.id())) {
            int steps = "상수".equals(beast.gap()) ? 2 : 1;
            fall = takeWound(row, sheet, steps, true, false, "야수_사냥", beast.name());
        }
        var alive = db.findCharacterById(chId);
        if (alive.isPresent() && !"사망".equals(alive.get().get("status"))) {
            db.updateCharacter(chId, sheet, wallet, realm, "강호", "청하현");
        }

        EmbedBuilder result = new EmbedBuilder().setColor(fall.isEmpty() ? INK : BLOOD)
                .setTitle("판정 — " + approach[0])
                .setDescription("**" + approach[1] + " " + stat + "**"
                        + (balgyeong > 0 ? " + ⚡발경 1" : "")
                        + (wound != 0 ? " " + wound + " (부상)" : "")
                        + " + 2d6 = **" + (power + roll)
                        + "** vs " + beast.resist() + " │ 마진 **" + (margin >= 0 ? "+" : "") + margin
                        + "** → **" + tier.name() + "**"
                        + (balgyeong > 0 ? "\n타격에 기가 실렸다 (내력 -1, 남은 "
                                + ((Number) sheet.getOrDefault("내력", 0)).intValue() + ")" : "")
                        + "\n" + gains
                        + "\n\n" + Narration.hunt(beast.name(), tier.name(), pelt)
                        + fall);
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

    /** 의뢰소 창구 = 소연 (service_gap.facilities.request_office) — 죽으면 창구가 바뀐다 */
    private static final String OFFICE_NPC = "soyeon";
    /** 장터 매입처 = 장쇠 (service_gap.facilities.market) — 죽으면 파는 곳이 없어진다 */
    private static final String MARKET_NPC = "market_peddler";

    /**
     * A2·B3 — 오늘의 동적 주입분. 입력은 (정파 favor · 상태 태그 · 사망 등록부 · 세계일)뿐 —
     * 주사위가 없으므로 같은 날 같은 상태면 같은 게시판이다 (결정론).
     */
    private List<Quests.Quest> injectedToday(long chId, Map<String, Object> sheet, int today)
            throws Exception {
        // C — 사파 악명은 정파의 문을 닫는다: 축객당한 자에게 정파 경유 의뢰는 오지 않는다
        //     (faction_entry_routes 악명_보유 분기·npc_death 수배 effects "민간·정파 발주 지명 제외")
        List<Quests.Quest> out = new ArrayList<>(isNotorious(chId, today) ? List.of()
                : Injections.routeQuests(rules, db.favor("orthodox", chId, today, rules.factions),
                        tagsOf(sheet).keySet()));
        out.addAll(Injections.deathQuests(rules, db.deadNpcs(), today));
        return out;
    }

    /**
     * C — 사파 악명은 정파의 문을 닫는다 (게시판 쪽 집행).
     *
     * 악명이 붙은 자는 의뢰소에게 '낯섦'으로 되돌아간다 — 우호 등급이 여는 문이 전부 닫히고,
     * faction_reaction favor.thresholds 의 최하단(잔심부름)만 남는다.
     * 등급 상한값은 config 가 정한다 (Factions.questGradeCap(0)). 평상시에는 이 문이 열려 있다 —
     * 게시판의 기본 접근을 우호로 조이지 않는다 (우호가 여는 것은 '세력 경유 의뢰'이고,
     * 그쪽은 faction_entry_routes.quest_injection 의 favor_min 이 이미 지키고 있다).
     */
    private java.util.function.Predicate<String> infamyGate(long chId, int today) throws Exception {
        if (!isNotorious(chId, today)) {
            return grade -> true;
        }
        List<String> ladder = rules.gradeLadder();
        int capRank = ladder.indexOf(rules.factions.questGradeCap(0));
        return grade -> capRank < 0 || ladder.indexOf(grade) <= capRank;
    }

    /** 지금 대장을 넘기는 사람 — 소연(정상) · 대행자 · 외지에서 온 낯선 사람 */
    private String clerkName(Deaths.Gap office) {
        if (office.normal() && office.actorKey() != null) {
            return rules.npcName(office.actorKey());
        }
        if (office.actorKey() != null) {
            return rules.npcName(office.actorKey());
        }
        return office.arrival() != null ? office.arrival() : "외지에서 온 접수인";
    }

    /** 대행 창구의 등급 상한 (소연 사후 백석: 호위_소탕까지) — quest_generation grade_ladder 서열 */
    private java.util.function.Predicate<String> gradeGate(Deaths.Gap office) {
        String cap = office.gradeCap();
        if (cap == null) {
            return grade -> true;
        }
        List<String> ladder = rules.gradeLadder();
        int capRank = ladder.indexOf(cap);
        return grade -> capRank < 0 || ladder.indexOf(grade) <= capRank;
    }

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
        if (blockedByWound(event, sheet)) {
            return;
        }
        long chId = ((Number) row.get("id")).longValue();
        int today = db.worldDay();
        var dead = db.deadNpcs();
        Deaths.Gap office = gapOf(OFFICE_NPC, today, dead);
        List<Quests.Quest> injected = injectedToday(chId, sheet, today);

        Map<String, Object> holding = (Map<String, Object>) sheet.get("의뢰");
        if (holding != null) {
            // 수주 중 — 수행 화면
            Quests.Quest q = Quests.find(String.valueOf(holding.get("키")), injected);
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

        // B1 — 서비스 공백: 창구가 비면 게시판 자체가 닫힌다 (문이 닫혀 있다)
        if (!office.open()) {
            event.replyEmbeds(new EmbedBuilder().setColor(INK)
                    .setTitle("의뢰소 — 문이 닫혀 있다")
                    .setDescription("널빤지가 대문에 가로질러 박혀 있다. " + rules.npcName(OFFICE_NPC)
                            + "이(가) 죽은 뒤로 대장을 넘길 사람이 없다.\n"
                            + "\"…발주도, 검수도 못 하오. 사람이 와야 하오.\"\n"
                            + "*(공백 — 상태 " + office.state() + ")*").build()).queue();
            return;
        }

        // 게시판 — 정적 3건 + 동적 주입분 (죽은 발주자의 의뢰는 사라진다)
        Map<String, Object> done = (Map<String, Object>) sheet.getOrDefault("의뢰_완료", Map.of());
        String clerk = clerkName(office);
        // C — 세력 반응이 창구의 태도가 된다 (stage_actions: 소문 인지 → 정보 수집 → 접촉·경고)
        Db.Standing orthodox = db.standing("orthodox", chId, today, rules.factions);
        var stage = rules.factions.stageOf(orthodox.attention());
        var level = rules.factions.favorLevelOf(orthodox.favor());
        String reaction = "";
        if (stage.stage() >= 1) {
            reaction = "\n\n🏮 *정파 — " + stage.stage() + "단계 **" + stage.name() + "** ("
                    + rules.factions.stageAction(stage.stage()) + ")*"
                    + (orthodox.favor() > 0 ? "\n*우호 **" + level.name() + "** — "
                            + String.join(" · ", rules.factions.favorBenefits(orthodox.favor())) + "*" : "");
        }
        if (isNotorious(chId, today)) {
            reaction += "\n\n⚫ **창구가 목소리를 낮춘다.** \"…자네 이름이 요새 험하게 돌더군. "
                    + "큰 일은 못 주네. 잔심부름이나 가져가게.\"\n"
                    + "*(사파 악명 — 정파 경유 의뢰 없음 · 등급 상한 "
                    + rules.factions.questGradeCap(0) + ")*";
        }
        EmbedBuilder eb = new EmbedBuilder().setColor(INK)
                .setTitle("의뢰소 게시판 — " + today + "일차")
                .setDescription((office.normal()
                        ? clerk + "이 대장을 넘긴다. \"골라 보게 — 보수는 완수 후, 검수는 확실히 하네.\""
                        : "**" + clerk + "**이(가) 대신 대장을 넘긴다. 손이 느리다.\n"
                                + "\"내 소관이 아니오만… 사람이 없으니 어쩌겠소. 큰 일은 못 받소.\"\n"
                                + "*(대행 — 등급 상한 " + office.gradeCap() + " · 보수 ×" + office.rewardMult() + ")*")
                        + reaction);
        List<Button> buttons = new ArrayList<>();
        var gate = gradeGate(office).and(infamyGate(chId, today));
        for (Quests.Quest q : Quests.board(today, injected, dead.keySet(), gate)) {
            boolean doneToday = today == ((Number) ((Map<String, Object>) done)
                    .getOrDefault(q.key(), -1)).intValue();
            boolean above = Quests.realmRank(q.realmReq()) > Quests.realmRank(String.valueOf(row.get("realm")));
            boolean fresh = Quests.POOL.stream().noneMatch(p -> p.key().equals(q.key()));
            eb.addField((fresh ? "🔸 " : "") + q.name() + (above ? " ⚠격상" : ""),
                    q.brief() + "\n등급 " + q.grade() + " · 권장 " + q.realmReq()
                            + (doneToday ? " · **오늘은 마감**" : ""), false);
            if (!doneToday && buttons.size() < 5) {
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
        if (found.isEmpty()) {
            event.editMessage("기록이 없다.").setComponents().queue();
            return;
        }
        Map<String, Object> row = found.get();
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        int today = db.worldDay();
        long ownerCh = ((Number) row.get("id")).longValue();
        Quests.Quest q = Quests.find(key, injectedToday(ownerCh, sheet, today));
        if (q == null) {
            event.editMessage("그 의뢰는 대장에서 내려졌다 — 발주자가 없거나 창구가 받지 않는다.")
                    .setComponents().queue();
            return;
        }
        if (sheet.get("의뢰") != null) {
            event.reply("이미 맡은 일이 있다 — 하나씩. (`/혼천 의뢰`로 진행)").setEphemeral(true).queue();
            return;
        }
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
        if (found.isEmpty()) {
            event.editMessage("기록이 없다.").setComponents().queue();
            return;
        }
        Map<String, Object> row = found.get();
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        Quests.Quest q = Quests.find(key,
                injectedToday(((Number) row.get("id")).longValue(), sheet, db.worldDay()));
        if (q == null) {
            event.editMessage("기록이 없다.").setComponents().queue();
            return;
        }
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
        int wound = woundMod(sheet);
        int power = stat + approach.bonus() + wound;
        int roll = dice.nextInt(6) + 1 + dice.nextInt(6) + 1;
        JudgmentEngine.Tier tier = rules.judgment.resolve(power, roll, resist);
        int margin = power + roll - resist;
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
                .setDescription("**" + approach.stat() + " " + stat + "**"
                        + (wound != 0 ? " " + wound + " (부상)" : "")
                        + " + 2d6 = **" + (power + roll) + "** vs " + resist
                        + " │ 마진 **" + (margin >= 0 ? "+" : "") + margin + "** → **" + tier.name() + "**");
        int wallet = ((Number) row.get("wallet")).intValue();
        String realm = String.valueOf(row.get("realm"));
        String name = String.valueOf(row.get("name"));
        if (margin >= 0) {
            // B1 — 대행 창구는 값을 깎는다 (standin_penalty.보수 0.9)
            Deaths.Gap office = gapOf(OFFICE_NPC, today, db.deadNpcs());
            String clerk = clerkName(office);
            int reward = (int) Math.round(rules.questReward(q.rewardKey(), dice) * office.rewardMult());
            wallet += reward;
            StringBuilder gains = new StringBuilder(clerk + "의 검수 통과 — 보수 **+" + reward + "문**"
                    + (office.rewardMult() < 1.0 ? " *(대행 창구 ×" + office.rewardMult() + ")*" : ""));
            if (q.combatMark()) {
                sheet.put("실전_마크", ((Number) sheet.getOrDefault("실전_마크", 0)).intValue() + 1);
                gains.append(" · **실전 마크 +1** (전투 의뢰)");
            }

            // C — 의뢰 완수는 발주 세력의 장부에 적힌다 (favor.inputs.공적_소)
            String faction = q.issuer() == null ? "orthodox" : orthodoxOrOwn(q.issuer());
            int favor = db.addFavor(faction, chId, rules.factions.favorInput("공적_소"),
                    rules.factions.favorMax(), today, rules.factions);
            db.logEvent("세력_반응", "character", String.valueOf(chId), "faction", faction,
                    Map.of("입력", "공적_소", "우호", favor, "사유", "의뢰_완수:" + q.key()));
            gains.append("\n🏮 **").append(rules.factionName(faction)).append(" 우호 ").append(favor)
                    .append("** (").append(rules.factions.favorLevelOf(favor).name()).append(")");

            // B — 플레이어가 소문의 주체가 된다: 의뢰소에서 사람들이 본 일이다 (소수 목격 — 강도 2)
            spread(rumorGroup("의뢰", chId, today),
                    name + "이(가) " + q.name() + " 일을 깨끗이 해냈다더군", name, chId,
                    List.of("무인", "의뢰", "협행"),
                    rules.rumors.intensityByVisibility("소수_목격_또는_간접"),
                    rules.initialAccuracy("직접_목격"), rules.originNetwork("request_office"), today);

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
                    "\n일이 어그러졌다 — 창구: \"빈손이면 보수도 없네. 게시판은 내일 다시 열리네.\"");
            db.logEvent("의뢰_실패", "character", String.valueOf(chId), "quest", q.key(),
                    Map.of("의뢰", q.key(), "굴림", roll, "마진", margin));
        }

        // A — 전투 의뢰의 치명적 실패는 칼로 돌아온다. 격상 도전(사선)이면 두 칸.
        //     의뢰는 사람이 보낸 일이다 — 쓰러져도 마을이 가깝다 (누군가 업고 뛴다)
        String fall = "";
        if ("critical_failure".equals(tier.id()) && q.combatMark()) {
            fall = takeWound(row, sheet, above ? 2 : 1, true, true, "의뢰_" + q.key(), "미상");
            result.setColor(BLOOD).appendDescription(fall);
        }
        var alive = db.findCharacterById(chId);
        if (alive.isPresent() && !"사망".equals(alive.get().get("status"))) {
            db.updateCharacter(chId, sheet, wallet, realm, "강호", "청하현");
        }
        event.editMessageEmbeds(event.getMessage().getEmbeds().get(0), result.build())
                .setComponents().queue();
    }

    /**
     * 발주 NPC 의 소속 세력 → 우호가 적립될 장부.
     * faction_awareness.network_access 에 조직 채널이 있는 세력만 반응 축을 갖는다 —
     * 민간·불가는 조직이 아니다 (개인의 고마움일 뿐). 그 몫은 정파 연락망(의뢰소)이 받는다.
     */
    private String orthodoxOrOwn(String issuerKey) {
        String faction = rules.npcFaction(issuerKey);
        return faction != null && rules.rumors.factionNetworks().containsKey(faction)
                ? faction : "orthodox";
    }

    // ─── 대화 — NPC 3층 구조의 최소 배선 (npc_dialogue.yml: 잡담/판정/세계) ───

    /** 대화 가능한 NPC 6인 (등록부 키) — 단서는 더 이상 고정 문구가 아니다 (아래 npcClue) */
    private static final List<String> TALKABLE = List.of("한백", "소연", "유문", "금서방", "곽진", "장쇠");

    /**
     * 정보 캐기 성공의 대가 — **살아 있는 소문**이다 (단계 4 B: 하드코딩 단서 6종 폐기).
     *
     * NPC 는 자기가 사는 장소의 소문망에 닿은 이야기만 안다 (rumor.yml origin_network_by_location):
     *   한백·소칠 = 객잔망 / 소연 = 정파망 / 유문·장쇠 = 민간망 / 금서방·곽진 = 상단망
     * 그 망에 아직 아무것도 닿지 않았으면 — 그는 정말 모른다. 모르는 것을 지어내지 않는다.
     * 정확도가 낮은 망일수록 뒤틀린 이야기를 내놓는다 (전언 게임: 같은 사건, 다른 소문).
     */
    private Rumors.Heard npcClue(String npcKey, int today) throws Exception {
        String network = rules.originNetwork(String.valueOf(rules.npcLocation(npcKey)));
        List<Rumors.Heard> heard = db.heard(today, network, rules.rumors.decayEveryDays());
        // 가장 센 이야기부터 (Db.heard 가 유효강도·최신순으로 준다) — 사람은 큰 소문부터 옮긴다
        return heard.isEmpty() ? null : heard.get(0);
    }

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
        if (npc == null || !TALKABLE.contains(npcName)) {
            event.reply("그 이름은 청하현에 없다.").setEphemeral(true).queue();
            return;
        }
        // B1 — 사망 게이트: 죽은 자와는 말할 수 없다. 그 자리를 누가 메웠는지만 알려준다
        String npcKey = rules.npcKeyByName(npcName);
        int day = db.worldDay();
        var deadNpcs = db.deadNpcs();
        if (npcKey != null && deadNpcs.containsKey(npcKey)) {
            Deaths.Gap gap = gapOf(npcKey, day, deadNpcs);
            String successor = gap.actorKey() != null ? rules.npcName(gap.actorKey()) : gap.arrival();
            event.replyEmbeds(new EmbedBuilder().setColor(BLOOD)
                    .setTitle("「" + npcName + "」 — 대답이 없다")
                    .setDescription(npcName + "은(는) 죽었다. 산 자의 말은 죽은 자에게 닿지 않는다.\n"
                            + (gap.open()
                                    ? "그 자리에는 지금 **" + (successor == null ? "낯선 사람" : successor)
                                            + "**이(가) 있다. *(" + gap.state() + ")*"
                                    : "그 자리는 아직 비어 있다. *(" + gap.state() + " — 사람이 와야 한다)*"))
                    .build()).queue();
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

    /**
     * 판정층 — 정보 캐기: 화술 vs 11 (judgment action_pairs.정보_캐기).
     * 성공하면 **그 NPC 의 망에 도달해 있는 소문**을 내준다 (더는 고정 문구가 아니다):
     *   같은 사건이라도 객잔망에서 듣는 것과 상단망에서 듣는 것이 다르다 (망별 정확도 = 왜곡).
     *   아무것도 안 닿았으면 그는 정말 모른다 — 세상 소식은 NPC 가 정하는 게 아니다.
     */
    @SuppressWarnings("unchecked")
    private void resolveInfoCheck(SlashCommandInteractionEvent event, Map<String, Object> row,
                                  long chId, String npcName, String say) throws Exception {
        Map<String, Object> sheet = (Map<String, Object>) row.get("sheet");
        Map<String, Object> attrs = (Map<String, Object>) sheet.get("능력치");
        int stat = ((Number) attrs.getOrDefault("화술", 2)).intValue();
        int roll = dice.nextInt(6) + 1 + dice.nextInt(6) + 1;
        int resist = 11;
        int power = stat + woundMod(sheet);   // 부상은 입도 무겁게 한다 (condition 보정)
        JudgmentEngine.Tier tier = rules.judgment.resolve(power, roll, resist);
        int margin = power + roll - resist;
        int today = db.worldDay();
        String npcKey = rules.npcKeyByName(npcName);
        Rumors.Heard clue = margin >= 0 && npcKey != null ? npcClue(npcKey, today) : null;
        // F38 — 판정 대화도 질문 요지를 남긴다 (세계층: NPC 기억 재료 — 잡담 경로와 대칭)
        db.logEvent("대화_판정", "character", String.valueOf(chId), npcName,
                Map.of("말", say.substring(0, Math.min(80, say.length())),
                        "굴림", roll, "마진", margin, "등급", tier.name(),
                        "단서", clue == null ? "없음" : clue.group()));

        String outcome;
        String footer = null;
        if (margin < 0) {
            outcome = "…" + npcName + "은(는) 화제를 돌렸다. 오늘은 입이 무겁다.";
        } else if (clue == null) {
            outcome = npcName + "이(가) 어깨를 으쓱한다. \"글쎄… 요새는 조용하네. "
                    + "들은 게 있어야 옮기지.\"\n*(이 사람의 소문망에는 아직 아무것도 닿지 않았다)*";
        } else {
            outcome = "\"" + rules.rumors.tell(clue) + "\"";
            footer = rules.rumors.networkName(clue.network()) + " · 강도 " + clue.intensity()
                    + " · " + rules.rumors.band(clue.accuracy()) + " (정확도 " + clue.accuracy() + ")";
        }
        EmbedBuilder reply = new EmbedBuilder().setColor(margin >= 0 ? BLOOD : INK)
                .setTitle("「" + npcName + "」").setDescription(outcome);
        if (footer != null) {
            reply.setFooter(footer);
        }
        event.getHook().sendMessageEmbeds(
                new EmbedBuilder().setColor(INK).setTitle("판정 — 정보 캐기")
                        .setDescription("**화술 " + stat + "**"
                                + (woundMod(sheet) != 0 ? " " + woundMod(sheet) + " (부상)" : "")
                                + " + 2d6 = **" + (power + roll) + "** vs " + resist
                                + " │ 마진 **" + (margin >= 0 ? "+" : "") + margin + "** → **"
                                + tier.name() + "**").build(),
                reply.build()).queue();
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

    /**
     * 발경 자격 — 개화 + 경지 게이트(삼류부터) + 내력 1 이상 (cost_bands 발경 = 1).
     * 부족하면 맨 기술 — 시전 불가가 아니라 다운캐스트 (internal_energy).
     */
    @SuppressWarnings("unchecked")
    private boolean canBalgyeong(Map<String, Object> sheet, String realm) {
        return "개화".equals(sheet.get("단전"))
                && rules.canUseQi(realm, "발경")
                && ((Number) sheet.getOrDefault("내력", 0)).intValue() >= 1;
    }

    /** 발경 대금 — 내력 1 차감 (호출 전 canBalgyeong 확인) */
    private void payBalgyeong(Map<String, Object> sheet) {
        int energy = ((Number) sheet.getOrDefault("내력", 0)).intValue();
        sheet.put("내력", Math.max(0, energy - 1));
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
        if (blockedByWound(event, sheet)) {
            return;
        }
        long chId = ((Number) row.get("id")).longValue();
        int today = db.worldDay();
        if (today == ((Number) sheet.getOrDefault("탐방일", -1)).intValue()) {
            event.reply("오늘은 이미 다녀왔다 — 해가 바뀌면 다시. (탐방은 하루 한 번)")
                    .setEphemeral(true).queue();
            return;
        }
        sheet.put("탐방일", today);
        String state = (String) sheet.get("취걸개");
        db.logEvent("탐방", "character", String.valueOf(chId), "place", "폐사당",
                Map.of("장소", "폐사당"));

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
            db.logEvent("기연_발견", "character", String.valueOf(chId), "fortune", "chuigeolgae_master",
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
                    db.logEvent("기연", "character", String.valueOf(chId), "fortune", "chuigeolgae_master",
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
        if (blockedByWound(event, sheet)) {
            return;
        }
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
            db.logEvent("개화", "character", String.valueOf(chId), "simbeop", "현천토납법",
                    Map.of("심법", "현천토납법"));
            // B — 개화는 숨길 수 없다: 사람이 달라 보인다 (흔적만 — 강도 1, 은밀한 축의 보상)
            spread(rumorGroup("개화", chId, today),
                    "그 낭인, 요새 사람이 달라 보이더군 — 어디서 뭘 배웠는지",
                    String.valueOf(row.get("name")), chId, List.of("무인", "무재"),
                    rules.rumors.intensityByVisibility("흔적만"),
                    rules.initialAccuracy("흔적_추론"), rules.originNetwork("cheongha_inn"), today);
            body.append("구결대로 숨을 고르자, 아랫배 깊은 곳에서 무언가 열렸다 — 옅은 백색의 기운이 "
                    + "실오라기처럼 돌기 시작한다.\n💥 **개화 — 단전이 열렸다** (이제 매일 운기로 기를 쌓는다)");
        } else {
            double days = ((Number) sheet.getOrDefault("축기_원장", 0)).doubleValue() + 1.0;
            sheet.put("축기_원장", days);
            double naegong = naegongOf(days);
            // 운기조식 — 내력 전량 회복 (internal_energy meditation, 알파: 하루 1회 운기에 통합)
            int pool = rules.energy.pool(naegong);
            sheet.put("내력", pool);
            db.logEvent("운기", "character", String.valueOf(chId), "simbeop",
                    String.valueOf(sheet.get("심법")), Map.of("적립", 1));
            body.append(String.format("가부좌를 틀고 한 주천을 돌렸다. 축기 **+1일치** (누적 %.0f일)\n"
                            + "내공 화후 **%s** · 내력 %d/%d (운기조식 — 가득 찼다)",
                    days, hwahuLabel(naegong), pool, pool));
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

    // ─── A1. 출행 — 세력 직행 (faction_entry_routes 제1원칙: 레일 금지) ───
    //
    // 화산은 오프스크린이다 — 신규 장소 없이 여비·기간만 소모한다.
    // 조건 미달의 결과는 '진행 불가'가 아니다: 잡역 제안 · 난이도 +4 · 축객 + 낮은 문 안내.
    // 무엇이 되었든 결과는 상태 태그로 시트에 남고, 그 태그가 A2(게시판)의 입력이 된다.

    private static final String HWASAN = Injections.HWASAN;
    private static final String TAG_KEY = "상태태그";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tagsOf(Map<String, Object> sheet) {
        return new LinkedHashMap<>((Map<String, Object>) sheet.getOrDefault(TAG_KEY, Map.of()));
    }

    private static void putTag(Map<String, Object> sheet, String tag, Object value) {
        Map<String, Object> tags = tagsOf(sheet);
        tags.put(tag, value);
        sheet.put(TAG_KEY, tags);
    }

    @SuppressWarnings("unchecked")
    private void travel(SlashCommandInteractionEvent event) throws Exception {
        if (notInRegion(event)) {
            return;
        }
        var found = requireDebuted(event, event.getUser());
        if (found.isEmpty()) {
            return;
        }
        var destOpt = event.getOption("목적지");
        String dest = destOpt == null ? "화산" : destOpt.getAsString();
        if (!"화산".equals(dest)) {
            event.reply("그 방면의 길은 아직 열리지 않았다 — 지금 청하현에서 닿는 산문은 **화산**뿐이다.")
                    .setEphemeral(true).queue();
            return;
        }
        Map<String, Object> row = found.get();
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        if (blockedByWound(event, sheet)) {
            return;
        }
        long chId = ((Number) row.get("id")).longValue();
        int today = db.worldDay();
        int back = ((Number) sheet.getOrDefault("출행_복귀일", -1)).intValue();
        if (today < back) {
            event.reply("아직 길 위다 — 청하현으로 돌아오려면 **" + (back - today) + "일** 남았다. "
                    + "(오프스크린 여정: 기간이 소모된다)").setEphemeral(true).queue();
            return;
        }

        // ① 여비·기간 — time.yml 지역권_이동 (3~7일) × economy.yml 생활 물가에서 유도한 노자
        List<Integer> range = rules.regionTravelDays();
        int days = range.get(0) + dice.nextInt(range.get(1) - range.get(0) + 1);
        int wallet = ((Number) row.get("wallet")).intValue();
        int fare = days * rules.dailyTravelCost();
        boolean begging = wallet < fare;
        StringBuilder road = new StringBuilder();
        if (begging) {
            // on_insufficient — 걸식·품팔이 여정: 기간 2배 + 원기 소모. 죽지 않는다 (잔인하되 공정하다)
            days *= 2;
            sheet.put("내력", 0);
            road.append("노자가 모자랐다. 걸식과 품팔이로 길을 이었다 — **").append(days)
                    .append("일**이 걸렸고, 몸에서 기운이 다 빠졌다. (내력 0)");
        } else {
            wallet -= fare;
            road.append("봉놋방과 국밥으로 **").append(days).append("일** 길을 갔다 — 노자 **-")
                    .append(fare).append("문**.");
        }
        sheet.put("출행_복귀일", today + days);
        sheet.put("출행_여정일", days);

        // ② 상태 조회 — favor · 단서 · 소문 · 악명
        int favor = db.favor("orthodox", chId, today, rules.factions);
        Routes.Infamy infamy = rules.routes.infamy(HWASAN);
        int haomunFavor = db.favor("haomun", chId, today, rules.factions);
        List<String> clues = rules.routes.clueItems(HWASAN);
        List<Object> items = sheet.get("소지품") instanceof List<?> l ? new ArrayList<>((List<Object>) l)
                : new ArrayList<>();
        boolean hasClue = items.stream().map(String::valueOf).anyMatch(clues::contains);
        Map<String, Object> tags = tagsOf(sheet);
        // 열쇠 ③ — 재목 소문이 정파망에 강도 2 이상으로 돌면 문파가 먼저 찾아온다 (gates.추천)
        boolean talent = hasRumor(today, rules.routes.gateRumorIntensity(HWASAN, "추천", 2),
                List.of("무재", "협행"));
        boolean notorious = haomunFavor >= infamy.haomunFavorMin()
                || hasRumor(today, infamy.rumorIntensityMin(), List.of(infamy.rumorTag()));
        int favorGate = rules.routes.gateFavorMin(HWASAN, "안면");
        String realm = String.valueOf(row.get("realm"));

        // ③ 직행 자체가 소문이 된다 — rumor_on_attempt (산문 앞에서 소리친 아이가 있었다).
        //    이제 이 소문은 발원망에 머무르지 않는다: 관심이 겹치는 망으로 건너가 세력의 귀에 든다
        Routes.Attempt attempt = rules.routes.rumorOnAttempt(HWASAN);
        spread(rumorGroup("출행", chId, today), attempt.text(), String.valueOf(row.get("name")), chId,
                List.of("무인", "문파"), attempt.intensityMin(), rules.initialAccuracy("간접_전문"),
                attempt.networks().get(0), today);

        // ④ 분기 — hwasan_entry.direct_approach.branches (닫히는 문은 없다)
        String branch;
        String body;
        List<Button> buttons = new ArrayList<>();
        int walkIn = rules.routes.walkInDifficultyModifier(HWASAN);
        if (notorious) {
            branch = "문전_축객_경고";
            putTag(sheet, "축객", today);
            body = "산문 앞에 서기도 전에 젊은 도사가 길을 막았다. 눈이 차다.\n"
                    + "\"…그 이름은 여기까지 왔소. 돌아가시오.\"\n"
                    + "문은 열리지 않았다. 다만 등 뒤로 한마디가 따라왔다 — \"**"
                    + String.join("·", infamy.lowDoors()) + "**의 길이 없는 것은 아니오. 그것도 문이오.\"\n"
                    + "*(사파망에서는 오히려 이름값이 올랐다)*";
        } else if (hasClue || favor >= favorGate || talent || tags.containsKey("심사_자격")) {
            branch = "즉시_접견";
            body = "산문지기가 당신을 위아래로 훑더니, 뜻밖에 옆으로 비켜섰다.\n"
                    + (hasClue ? "\"그 물건은 어디서 났소.\" — 손에 든 것이 말을 대신했다.\n"
                            : talent ? "\"산 아래에서 자네 이야기를 들었네.\" 소문이 먼저 도착해 있었다.\n"
                            : "\"연락망에서 자네 이름을 봤네.\" 쌓아 둔 것이 문을 열었다.\n")
                    + "\"들어오시오. 심사 장로께서 보시겠다 하오.\"";
            buttons.add(Button.primary("ex:exam:0:" + event.getUser().getId(), "심사에 응한다"));
        } else if (Quests.realmRank(realm) >= Quests.realmRank("삼류") && favor <= favorGate - 1) {
            branch = "즉석_심사";
            body = "\"추천장은.\" 없다고 하자 산문지기의 눈이 가늘어졌다.\n"
                    + "\"…굳이 보겠다면 말리지는 않소. 다만 보증인 없는 자의 심사는 다른 심사요.\"\n"
                    + "*(추천 없는 자의 벽 — 난이도 +" + walkIn + ")*";
            buttons.add(Button.danger("ex:exam:" + walkIn + ":" + event.getUser().getId(),
                    "그래도 심사를 본다 (난이도 +" + walkIn + ")"));
            buttons.add(Button.secondary("ex:chore:0:" + event.getUser().getId(), "문전 잡역을 청한다"));
        } else {
            branch = "문전_잡역_제안";
            body = "산문지기는 당신을 오래 보지도 않았다.\n"
                    + "\"이름도 없고, 보증도 없고, 든 것도 없소. …돌아가시오.\"\n"
                    + "돌아서려는데, 늙은 도사가 마당을 쓸다 말고 불렀다.\n"
                    + "\"물이나 길어 볼 텐가. 밥은 먹여 주네.\" — *문은 그렇게도 열린다.*";
            buttons.add(Button.primary("ex:chore:0:" + event.getUser().getId(), "문전 잡역을 청한다"));
            buttons.add(Button.secondary("ex:exam:" + walkIn + ":" + event.getUser().getId(),
                    "그래도 심사를 본다 (난이도 +" + walkIn + ")"));
        }

        db.updateCharacter(chId, sheet, wallet, realm, "강호", "청하현");
        db.logEvent("출행", "character", String.valueOf(chId), "faction", "화산파",
                Map.of("목적지", dest, "여정일", days, "노자", begging ? 0 : fare, "걸식", begging,
                        "분기", branch, "favor", favor));

        var reply = event.replyEmbeds(new EmbedBuilder().setColor(BLOOD)
                .setTitle("출행 — 화산으로 간다")
                .setDescription("청하현을 등지고 서쪽 길을 잡았다. " + road + "\n\n" + body
                        + "\n\n*(돌아오는 길까지 " + days + "일 — 그동안 다시 출행할 수 없다)*")
                .build());
        if (!buttons.isEmpty()) {
            reply.addComponents(ActionRow.of(buttons));
        }
        reply.queue();
    }

    /**
     * 소문 조회 — 지금 **살아 있는** 도달 중에 강도 하한 + 태그 일치가 있는가.
     * 감쇠가 반영된 유효강도로 본다 (Db.heard): 시든 소문은 문을 열지도, 닫지도 못한다.
     */
    private boolean hasRumor(int today, int minIntensity, List<String> anyTag) throws Exception {
        for (Rumors.Heard h : db.heard(today, null, rules.rumors.decayEveryDays())) {
            if (h.intensity() >= minIntensity && h.tags().stream().anyMatch(anyTag::contains)) {
                return true;
            }
        }
        return false;
    }

    /** 문전 잡역 · 즉석 심사 — 직행자의 두 문 (둘 다 상태 태그를 남긴다) */
    @SuppressWarnings("unchecked")
    private void onGateChoice(ButtonInteractionEvent event, String kind, int mod, String ownerId)
            throws Exception {
        if (!event.getUser().getId().equals(ownerId)) {
            event.reply("남의 산문 앞이다 — `/혼천 출행`으로 직접 서라.").setEphemeral(true).queue();
            return;
        }
        var found = db.findCharacter(ownerId);
        if (found.isEmpty()) {
            event.editMessage("기록이 없다.").setComponents().queue();
            return;
        }
        Map<String, Object> row = found.get();
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        long chId = ((Number) row.get("id")).longValue();
        int today = db.worldDay();
        String realm = String.valueOf(row.get("realm"));
        Map<String, Object> tags = tagsOf(sheet);

        if ("chore".equals(kind)) {
            // 일수당 favor +1 (상한 8) — 잡역 = 사실상의 안면 단계 진입. 누적 30일이면 심사 자격 자동
            int stay = Math.max(1, ((Number) sheet.getOrDefault("출행_여정일", 1)).intValue());
            int cap = rules.routes.choreFavorCap(HWASAN);
            int favor = db.addFavor("orthodox", chId, stay, cap, today, rules.factions);
            int chore = ((Number) tags.getOrDefault("문전_잡역", 0)).intValue() + stay;
            putTag(sheet, "문전_잡역", chore);
            putTag(sheet, rules.routes.choreTag(HWASAN), today);   // 눈여겨봄 — 이탈해도 남는다
            int auto = rules.routes.choreAutoQualifyDays(HWASAN);
            boolean qualified = chore >= auto;
            if (qualified) {
                putTag(sheet, "심사_자격", today);
            }
            db.updateCharacter(chId, sheet, ((Number) row.get("wallet")).intValue(), realm, "강호", "청하현");
            db.logEvent("세력_잡역", "character", String.valueOf(chId), "faction", "화산파",
                    Map.of("일수", stay, "누적", chore, "favor", favor));
            event.editMessageEmbeds(new EmbedBuilder().setColor(INK)
                    .setTitle("문전 잡역 — " + stay + "일")
                    .setDescription("물을 긷고, 마당을 쓸고, 장작을 팼다. 아무도 이름을 묻지 않았다.\n"
                            + "다만 마당을 지나던 늙은 도사가 한 번, 두 번 걸음을 늦췄다.\n\n"
                            + "**정파 favor " + favor + "** (상한 " + cap + ") · **문전 잡역 누적 "
                            + chore + "/" + auto + "일**\n"
                            + "상태 태그: **" + rules.routes.choreTag(HWASAN) + "**"
                            + (qualified ? " · **심사 자격** (추천장 없는 자의 정공법 — 다음 출행에서 접견)" : "")
                            + "\n\n*(청하현에 돌아오면 게시판이 조금 달라져 있을 것이다)*")
                    .build()).setComponents().queue();
            return;
        }

        // 즉석 심사 — 보통(12) + 추천 없는 자의 벽(+4) − 눈여겨봄(-2)
        int discount = tags.containsKey(rules.routes.choreTag(HWASAN))
                ? rules.routes.watchedTagDiscount(HWASAN) : 0;
        int resist = rules.difficulty("보통") + mod - discount;
        Map<String, Object> attrs = (Map<String, Object>) sheet.get("능력치");
        int talent = Math.max(((Number) attrs.getOrDefault("근력", 2)).intValue(),
                ((Number) attrs.getOrDefault("민첩", 2)).intValue());
        int roll = dice.nextInt(6) + 1 + dice.nextInt(6) + 1;
        JudgmentEngine.Tier tier = rules.judgment.resolve(talent, roll, resist);
        int margin = talent + roll - resist;

        EmbedBuilder judge = new EmbedBuilder().setColor(INK)
                .setTitle("판정 — 화산파 입문 심사" + (mod > 0 ? " (보증인 없음 +" + mod + ")" : ""))
                .setDescription("**무재 " + talent + "** + 2d6 = **" + (talent + roll) + "** vs " + resist
                        + (discount > 0 ? " *(눈여겨봄 -" + discount + ")*" : "")
                        + " │ 마진 **" + (margin >= 0 ? "+" : "") + margin + "** → **" + tier.name() + "**");
        EmbedBuilder scene = new EmbedBuilder();
        if (margin >= 0) {
            putTag(sheet, "화산_심사_통과", today);
            int favor = db.addFavor("orthodox", chId, rules.routes.gateFavorMin(HWASAN, "안면"),
                    rules.routes.choreFavorCap(HWASAN), today, rules.factions);
            if ("대성공".equals(tier.name())) {
                // judgment_link — 재목 소문이 정파망을 탄다 (다음 직행은 즉시 접견)
                spread(rumorGroup("심사", chId, today), "산문에서 아이 하나가 눈에 띄었다더군",
                        String.valueOf(row.get("name")), chId, List.of("무재", "무인", "문파"),
                        rules.routes.gateRumorIntensity(HWASAN, "추천", 2),
                        rules.initialAccuracy("직접_목격"), "orthodox_net", today);
            }
            db.logEvent("세력_심사", "character", String.valueOf(chId), "faction", "화산파",
                    Map.of("결과", "합격", "굴림", roll, "마진", margin, "등급", tier.name()));
            scene.setColor(BLOOD).setTitle("심사 — 장로는 손속을 보지 않았다")
                    .setDescription("검을 세 번 휘두르게 하고, 장로는 더 보지 않았다. 대신 물었다.\n"
                            + "\"…무엇이 되고 싶어 여기까지 왔나.\"\n\n"
                            + "당신의 대답을 장로는 오래 곱씹었다. 그리고 고개를 끄덕였다.\n"
                            + "**상태 태그: 화산_심사_통과** · 정파 favor **" + favor + "**\n"
                            + "*(입문식은 아직이다 — 사문 등록은 봇 다음 증분. 그러나 문은 열렸다)*");
        } else {
            putTag(sheet, rules.routes.choreTag(HWASAN), today);   // 낙방해도 눈여겨봄은 남는다
            db.logEvent("세력_심사", "character", String.valueOf(chId), "faction", "화산파",
                    Map.of("결과", "낙방", "굴림", roll, "마진", margin, "등급", tier.name()));
            scene.setColor(INK).setTitle("심사 — 낙방")
                    .setDescription("장로는 오래 보지 않았다. \"기초가 없군.\"\n"
                            + "그러나 문은 닫히지 않았다 — 마당을 쓸던 늙은 도사가 다시 물었다.\n"
                            + "\"물이나 길어 볼 텐가.\"\n\n"
                            + "**상태 태그: " + rules.routes.choreTag(HWASAN)
                            + "** (다시 오면 심사 난이도 -" + rules.routes.watchedTagDiscount(HWASAN) + ")");
        }
        db.updateCharacter(chId, sheet, ((Number) row.get("wallet")).intValue(), realm, "강호", "청하현");
        event.editMessageEmbeds(judge.build(), scene.build()).setComponents().queue();
    }

    // ─── B. NPC 사망 연쇄 — 죽음은 막다른 길이 아니다 (npc_death.yml) ───

    /** 오늘 기준 서비스 상태 — 죽은 자의 자리를 누가, 언제부터 메우는가 (결정론) */
    private Deaths.Gap gapOf(String npcKey, int today, Map<String, Map<String, Object>> dead)
            throws Exception {
        Map<String, Object> state = dead.get(npcKey);
        int elapsed = state == null ? 0
                : today - ((Number) state.getOrDefault("사망일", today)).intValue();
        return rules.deaths.serviceState(npcKey, Math.max(0, elapsed), dead.keySet());
    }

    /** 관리자 검증 명령 — NPC를 죽인다. 연쇄(공백·후계·소문·게시판)를 시험하는 유일한 손잡이 */
    private void adminKill(SlashCommandInteractionEvent event) throws Exception {
        if (event.getMember() == null || !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("서버 관리 권한이 필요하다.").setEphemeral(true).queue();
            return;
        }
        var target = event.getOption("상대");
        if (target == null) {
            event.reply("`/혼천 사망 상대:<NPC>`").setEphemeral(true).queue();
            return;
        }
        String name = target.getAsString();
        String npcKey = rules.npcKeyByName(name);
        if (npcKey == null) {
            event.reply("등록부에 없는 이름이다 — 세계에 없는 자는 죽지도 않는다.").setEphemeral(true).queue();
            return;
        }
        var dead = db.deadNpcs();
        if (dead.containsKey(npcKey)) {
            event.reply(name + "은(는) 이미 죽었다. (두 번 죽지는 않는다)").setEphemeral(true).queue();
            return;
        }
        String cause = optionOr(event, "사인", "사건_피살");
        int witness = event.getOption("목격") == null ? 1 : (int) event.getOption("목격").getAsLong();
        String bodyState = optionOr(event, "시신", "즉시_발견");
        int today = db.worldDay();

        Map<String, Object> state = new LinkedHashMap<>(Map.of("사망일", today, "사인", cause,
                "목격", witness, "시신", bodyState));
        db.killNpc(npcKey, rules.npcTier(npcKey), state);
        db.logEvent("사망", "world", "gm", "npc", npcKey,
                Map.of("사인", cause, "목격", witness, "시신", bodyState));

        // B2 — 사망 → 소문 자동 (killer_response.rumor_matrix). 목격 0 + 은닉 = 소문 없음 (은밀형의 보상)
        Deaths.RumorSpec spec = rules.deaths.rumorFor(witness, bodyState, cause);
        String facility = rules.deaths.facilityOf(npcKey);
        String network = rules.originNetwork(facility == null ? "market" : facility);
        StringBuilder note = new StringBuilder();
        if (spec.intensity() > 0) {
            // B — NPC 의 죽음도 이제 퍼진다: 발원망에서 관심 일치 망으로 건너가며 뒤틀린다
            int planted = spread(rumorGroup("npc사망", npcKey, today),
                    name + "이(가) 죽었다 (" + cause.replace('_', ' ') + ")", name, null,
                    List.of("살인", "괴사", "폭력", "치안"),
                    spec.intensity(), spec.accuracy(), network, today + spec.delayDays());
            note.append("소문 — 강도 **").append(spec.intensity()).append("** · 정확도 ")
                    .append(spec.accuracy()).append(" · 발원 ").append(network)
                    .append(" → **").append(planted).append("개 망**에 도달 예정")
                    .append(spec.delayDays() > 0 ? " (" + spec.delayDays() + "일 뒤부터)" : " (오늘부터)");
        } else {
            db.logEvent("실종", "world", "gm", "npc", npcKey,
                    Map.of("사유", "시신 은닉 — 소문 미생성", "발화일", today + rules.deaths.missingPersonDays()));
            note.append("소문 — **없음** (목격 0 + 시신 은닉: 은밀형의 보상). 다만 ")
                    .append(rules.deaths.missingPersonDays()).append("일 뒤 *실종*이 게시판에 뜬다");
        }

        // B1 — 서비스 공백·후계 예고
        StringBuilder gapNote = new StringBuilder();
        if (facility != null) {
            Deaths.Gap now = rules.deaths.serviceState(npcKey, 0, db.deadNpcs().keySet());
            Deaths.Gap later = rules.deaths.serviceState(npcKey, 30, db.deadNpcs().keySet());
            gapNote.append("시설 **").append(facility).append("** — 지금 상태 **").append(now.state())
                    .append("** (문이 닫혔다)");
            if (later.actorKey() != null) {
                gapNote.append(" → 이윽고 **").append(later.state()).append("** (")
                        .append(rules.npcName(later.actorKey())).append(")");
            } else if (later.arrival() != null) {
                gapNote.append(" → **").append(later.state()).append("** (").append(later.arrival()).append(")");
            } else {
                gapNote.append(" → **").append(later.state()).append("**");
            }
        } else {
            gapNote.append("시설 없음 — 서비스 공백 없이 서사·루트만 흔들린다");
        }

        event.replyEmbeds(new EmbedBuilder().setColor(BLOOD)
                .setTitle("[GM] " + name + "이(가) 죽었다")
                .setDescription("**" + rules.npcRole(npcKey) + "** · 사인 " + cause.replace('_', ' ')
                        + " · 목격 " + witness + " · 시신 " + bodyState + "\n\n"
                        + gapNote + "\n" + note + "\n\n"
                        + "· `/혼천 대화`에서 이 이름은 사라진다 (죽은 자와 말할 수 없다)\n"
                        + "· 내일 게시판이 바뀐다 — 죽음이 낳은 의뢰가 뜬다 (`/혼천 정산` → `/혼천 의뢰`)")
                .build()).queue();
    }

    private String optionOr(SlashCommandInteractionEvent event, String key, String fallback) {
        var opt = event.getOption(key);
        return opt == null ? fallback : opt.getAsString();
    }

    // ═══ 단계 4 B — 소문망: 생성만 하던 소문을 '퍼지게' 한다 ═══
    //
    // 소문 하나를 심으면 rumors 에 여러 행이 생긴다 — 망마다 도달일과 정확도가 다르다.
    // 발원망은 오늘, 관심 일치 망은 speed_days 뒤에, 그 망의 distortion 만큼 부정확하게.
    // 감쇠는 읽을 때 계산한다 (Db.heard). 전 과정 무주사위 — 같은 날이면 같은 소문판이다.

    /** 소문 파종 — 반환: 몇 개의 망에 닿게 되었는가 (0 = 소문 없음) */
    private int spread(String group, String truth, String subject, Long subjectId, List<String> tags,
                       int intensity, int accuracy, String originNet, int day) throws Exception {
        if (intensity <= 0) {
            return 0;
        }
        List<Db.Arrival> arrivals = new ArrayList<>();
        rules.rumors.arrivals(group, day, originNet, intensity, accuracy,
                        new java.util.LinkedHashSet<>(tags))
                .forEach((net, arrival) ->
                        arrivals.add(new Db.Arrival(net, arrival.day(), arrival.accuracy())));
        int planted = db.spreadRumor(group, truth, subject, subjectId, tags, intensity,
                arrivals, Db.REGION);
        db.logEvent("소문", subjectId == null ? "world" : "character",
                subjectId == null ? "world" : String.valueOf(subjectId), "rumor", group,
                Map.of("내용", truth, "강도", intensity, "정확도", accuracy,
                        "발원망", originNet, "도달망", arrivals.stream().map(Db.Arrival::network).toList()));
        // 오늘 곧장 조직 채널에 꽂힌 것(심사 대성공 → 정파망)은 지금 세어 준다 —
        // 다음 정산은 '오늘 이전'을 다시 훑지만, 그때까지 기다릴 이유가 없다 (멱등이라 안전하다)
        factionAwareness(day);
        return planted;
    }

    /** 세력 인지 재훑기 창 — 소문의 최대 수명(강도 5 × 감쇠 3일)보다 넉넉히 (놓친 도달을 줍는다) */
    private static final int AWARENESS_LOOKBACK_DAYS = 30;

    /** 소문군 키 — 같은 사건의 망별 도달을 묶는다 (세력 중복 가산 금지의 기준) */
    private static String rumorGroup(String kind, Object subject, int day) {
        return kind + ":" + subject + ":" + day;
    }

    /**
     * 세계 개막 소문 — 등록 사건 3건(regions/cheongha_hyeon.yml incidents)은 첫날부터 이미 돌고 있다.
     * 신규 사건 발명 없음. 각 사건의 surface(사람들이 하는 말)가 소문의 내용이고, truth 는 세계의 몫이다.
     * 1회성 — 이미 심어져 있으면 다시 심지 않는다.
     */
    void seedWorldRumors() throws Exception {
        int day = db.worldDay();
        Map<String, List<String>> tagsOf = Map.of(
                "north_road_bandits", List.of("도적", "치안", "물류", "관군"),
                "cheongha_fever_rumor", List.of("질병", "생활", "물가"),
                "inn_unorthodox_contact", List.of("사파", "조직원", "정보_유출"));
        Map<String, String> originOf = Map.of(
                "north_road_bandits", "north_road",
                "cheongha_fever_rumor", "market",
                "inn_unorthodox_contact", "cheongha_inn");
        for (Map.Entry<String, Object> e : rules.incidentsRegistry().entrySet()) {
            String key = e.getKey();
            if (!(e.getValue() instanceof Map<?, ?> raw) || db.rumorGroupExists("사건:" + key)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> incident = (Map<String, Object>) raw;
            String surface = String.valueOf(incident.get("surface")).strip();
            // 여러 줄 surface(열병)는 첫 줄만 — 소문은 한 문장으로 돈다
            String line = surface.lines().findFirst().orElse(surface).strip();
            // 흔적·전문 수준의 사건 — 목격자 없는 '들리는 이야기' (간접_전문 70)
            spread("사건:" + key, line, null, null,
                    tagsOf.getOrDefault(key, List.of("생활")),
                    rules.rumors.intensityByVisibility("소수_목격_또는_간접"),
                    rules.initialAccuracy("간접_전문"),
                    rules.originNetwork(originOf.getOrDefault(key, "market")), day);
        }
    }

    // ═══ 단계 4 C — 세력 반응: faction_standing 이 드디어 움직인다 ═══

    /**
     * 세력의 조직 채널에 소문이 닿았는가 — 그때에만 '조직적 인지'가 되고 점수가 된다.
     * 멱등이다 (세력_인지 이벤트로 중복 가산 금지) — 정산에서도, 소문을 심은 직후에도 돌린다.
     */
    private void factionAwareness(int day) throws Exception {
        for (Map<String, Object> arrival : db.arrivalsThrough(day, AWARENESS_LOOKBACK_DAYS)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> content = (Map<String, Object>) arrival.get("내용");
            Object subjectId = content.get("주체_id");
            if (subjectId == null) {
                continue;   // 주체가 플레이어가 아닌 소문 — 세계의 배경음 (대상별 점수가 없다)
            }
            long chId = ((Number) subjectId).longValue();
            String group = String.valueOf(content.get("군"));
            int accuracy = ((Number) arrival.get("정확도")).intValue();
            String network = String.valueOf(arrival.get("망"));
            for (String faction : rules.rumors.factionsListening(network)) {
                // no_double_count — "같은 소문이 여러 망으로 도달해도 세력당 1회만 가산"
                if (db.eventExists("세력_인지", faction, group)) {
                    continue;
                }
                int delta = rules.factions.rumorInput(accuracy);
                int score = db.addAttention(faction, chId, delta, day, rules.factions);
                db.logEvent("세력_인지", "world", faction, "rumor", group,
                        Map.of("세력", faction, "대상", chId, "망", network,
                                "정확도", accuracy, "가산", delta, "주목", score,
                                "단계", rules.factions.stageOf(score).name()));
            }
        }
    }

    /** 세력 반응 한 줄 — 주목 단계와 우호 등급을 함께 읽는다 (두 축은 독립이다) */
    private String standingLine(Db.Standing s) {
        var stage = rules.factions.stageOf(s.attention());
        var level = rules.factions.favorLevelOf(s.favor());
        return "**" + rules.factionName(s.faction()) + "** — 주목 " + s.attention()
                + " (" + stage.stage() + "단계 " + stage.name() + ") · 우호 " + s.favor()
                + " (" + level.name() + ")";
    }

    /**
     * 악명 — 사파 쪽에 이름이 팔리면 정파의 문이 닫힌다 (faction_entry_routes 악명_보유 분기와 동일 조건).
     * 하오문 우호가 문턱을 넘거나, 살인 태그 소문이 강도 하한 이상으로 돌고 있으면 참.
     */
    private boolean isNotorious(long chId, int today) throws Exception {
        Routes.Infamy infamy = rules.routes.infamy(HWASAN);
        return db.favor("haomun", chId, today, rules.factions) >= infamy.haomunFavorMin()
                || hasRumor(today, infamy.rumorIntensityMin(), List.of(infamy.rumorTag()));
    }

    // ═══ 단계 4 A — 플레이어의 죽음: 부상 → 빈사 → 사망 위기 → 비가역 ═══
    //
    // "죽음은 비가역 — 계정당 한 삶"이 이 게임의 뼈대다. 그러나 죽음은 갑자기 오지 않는다:
    //
    //   패배의 기본값은 죽음이 아니다 (death_pipeline.default_defeat = 제압·강탈·중상).
    //   살의(사선)만이 빈사 칸을 연다 — 야수의 이빨, 격상 의뢰, 세력의 습격.
    //   비무에는 살의가 없다: 아무리 크게 져도 중상에서 멈춘다.
    //
    //   빈사 → 매 라운드(=세계일) 체력 판정 (비대립 12) → 실패하면 사망 위기 (창구 1회):
    //     ① 회생   선천진기 10년 — 개화한 몸의 마지막 보험 (1회, 비가역)
    //     ② 구조   의술 지혈 (비대립 어려움 14) — 동행(`/혼천 구조`) 또는 NPC(유문)
    //     ③ 없음 → 사망 확정
    //
    //   ★ 구조의 NPC 축은 npc_death.yml 의 서비스 공백과 접합한다:
    //     유문이 죽으면 의방은 곰보영감의 대행이 되고, 그 대행의 불가_서비스에 '중상_치료'가 있다.
    //     → **유문을 죽이면 아무도 너를 살리지 못한다.** 그것이 그 죽음의 값이다.

    private static final String WOUND = "부상";
    private static final String BANK_BRANCH = "청하현";

    private static String woundOf(Map<String, Object> sheet) {
        Object w = sheet.get(WOUND);
        return w == null ? null : String.valueOf(w);
    }

    /** 부상 판정 보정 — 경상 -1 · 중상 -2 · 빈사 -3 (judgment.yml condition. 전투 밖에서도 지속) */
    private int woundMod(Map<String, Object> sheet) {
        return rules.conditionModifier(woundOf(sheet));
    }

    private boolean dying(Map<String, Object> sheet) {
        return "빈사".equals(woundOf(sheet));
    }

    /** 빈사자는 아무것도 못 한다 — 기어가는 것이 전부다 (mc_procedure.down: 기어가기만) */
    private boolean blockedByWound(SlashCommandInteractionEvent event, Map<String, Object> sheet) {
        if (!dying(sheet)) {
            return false;
        }
        event.replyEmbeds(new EmbedBuilder().setColor(BLOOD).setTitle("빈사 — 몸이 말을 듣지 않는다")
                .setDescription("숨이 얕다. 손끝 하나 뜻대로 움직이지 않는다.\n"
                        + "누군가 와야 한다 — 동행의 `/혼천 구조`, 아니면 세계가 너를 옮겨 주기를."
                        + "\n\n*(다음 세계일까지 아무 개입이 없으면 사망 위기 판정이 다시 돈다)*")
                .build()).setEphemeral(true).queue();
        return true;
    }

    /** 의방의 지금 상태 — 유문이 산 자의 자리를 지키는가 (npc_death 서비스 공백 접합) */
    private Deaths.Gap clinic(int today) throws Exception {
        return gapOf(Legacy.PHYSICIAN_NPC, today, db.deadNpcs());
    }

    /** 대행자는 중상을 못 본다 — standin_penalty.불가_서비스 에 '중상_치료'가 있으면 빈사는 손도 못 댄다 */
    private boolean canTreatCritical(Deaths.Gap clinic) {
        if (!clinic.open()) {
            return false;
        }
        Object blocked = clinic.penalty().get("불가_서비스");
        return !(blocked instanceof List<?> list
                && list.stream().map(String::valueOf).anyMatch(Legacy.CRITICAL_CARE::equals));
    }

    /**
     * 의원의 지혈 판정력 — NPC 판정 문법 그대로: 능력치 + 기술 + 상황 + 7 (주사위 없음 — 결정론).
     * 기술은 등록부 tier 를 전문성의 대리값으로 쓴다 (유문에게 '의술' 수치가 등록돼 있지 않다 — 한계).
     * 상황은 자기 의방(도구·약재 완비) = 상황 보정 상한. 대행이면 그 페널티(의술_판정 -2)를 얹는다.
     * 외지인 부임(actorKey 없음)은 outsider_arrival.quality "전임자 기준 -1칸".
     */
    private int physicianPower(Deaths.Gap clinic) {
        boolean outsider = clinic.actorKey() == null;   // 부임한 외지 의원 — 이 현을 모른다
        String actor = outsider ? Legacy.PHYSICIAN_NPC : clinic.actorKey();
        int skill = rules.npcTier(actor) - (outsider ? 1 : 0);   // outsider_arrival.quality: 전임자 -1칸
        Object mod = clinic.penalty().get("의술_판정");
        int penalty = mod instanceof Number n ? n.intValue() : 0;
        return rules.npcFixedBonus() + skill + rules.situationCap() + penalty;
    }

    /** 지금 의방을 지키는 자의 이름 (유문 · 대행자 · 외지에서 온 의원) */
    private String physicianName(Deaths.Gap clinic) {
        if (clinic.actorKey() != null) {
            return rules.npcName(clinic.actorKey());
        }
        return clinic.arrival() != null ? clinic.arrival() : "외지에서 온 의원";
    }

    /**
     * 사선의 대가 — 부상 사다리를 태우고, 빈사에 닿으면 사망 위기 파이프를 돈다.
     * lethal=false 면 아무리 크게 져도 중상에서 멈춘다 (살의 없는 패배의 기본값).
     * inTown: 마을 안에서 쓰러졌는가 — 야외(뒷산)의 다운은 아무도 옮겨 주지 않는다
     *         (death_and_legacy mc_procedure.unattended: "산길의 다운은 진짜 위험하다").
     * 반환: embed 에 붙일 서사. 시트·DB 는 이 안에서 갱신된다 (사망 확정 포함).
     */
    private String takeWound(Map<String, Object> row, Map<String, Object> sheet, int steps,
                             boolean lethal, boolean inTown, String cause, String killer)
            throws Exception {
        long chId = ((Number) row.get("id")).longValue();
        String cap = rules.legacy.woundCap(lethal);
        String before = woundOf(sheet);
        String now = rules.legacy.worsen(before, steps, cap);
        sheet.put(WOUND, now);
        db.logEvent("부상", "character", String.valueOf(chId),
                Map.of("부상", String.valueOf(now), "직전", String.valueOf(before),
                        "사인", cause, "살의", lethal));
        if (!"빈사".equals(now)) {
            return "\n🩸 **" + now + "** — " + (lethal
                    ? "숨은 붙어 있다. 죽일 뜻이 있었다면 여기서 끝났을 것이다."
                    : "제압당했다. 이것이 패배의 기본값이다 — 죽음이 아니라 중상.")
                    + " *(모든 판정 " + rules.conditionModifier(now) + " — `/혼천 의방`)*";
        }
        return crisis(row, sheet, inTown, cause, killer, true);
    }

    /**
     * 빈사 — 매 라운드(=세계일) 체력 판정. 버티면 창구가 열린 채로 남고, 실패하면 사망 위기다.
     * 사망 위기의 개입 순서: 회생(자동) → NPC 구조(이송 가능할 때) → 없으면 사망 확정.
     */
    private String crisis(Map<String, Object> row, Map<String, Object> sheet, boolean inTown,
                          String cause, String killer, boolean firstFall) throws Exception {
        long chId = ((Number) row.get("id")).longValue();
        int today = db.worldDay();
        sheet.put("빈사일", today);

        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) sheet.get("능력치");
        int con = ((Number) attrs.getOrDefault("체력", 2)).intValue();
        int resist = rules.legacy.dyingCheckDifficulty(rules.difficulty("보통"));
        int roll = dice.nextInt(6) + 1 + dice.nextInt(6) + 1;
        int power = con + rules.conditionModifier("빈사");   // 빈사 페널티는 자기 자신에게도 물린다
        int margin = power + roll - resist;
        db.logEvent("빈사", "character", String.valueOf(chId),
                Map.of("굴림", roll, "마진", margin, "사인", cause, "버팀", margin >= 0));

        StringBuilder body = new StringBuilder();
        if (firstFall) {
            body.append("\n\n💀 **빈사** — 무릎이 꺾였다. 시야가 가장자리부터 어두워진다.\n");
        } else {
            body.append("\n**빈사 — 또 하루를 버텨야 한다.**\n");
        }
        body.append("체력 판정: **체력 ").append(con).append("** ").append(rules.conditionModifier("빈사"))
                .append(" + 2d6 = **").append(power + roll).append("** vs ").append(resist)
                .append(" │ 마진 **").append(margin >= 0 ? "+" : "").append(margin).append("**\n");

        if (margin >= 0) {
            body.append("이를 악물고 버텼다 — 아직 숨이 있다.\n"
                    + "*(사망 위기는 유예됐다. 다음 세계일에 다시 굴린다 — 그 전에 누가 오기를)*");
            return body.toString();
        }

        // ─── 사망 위기 — 마지막 개입 창구 ───
        body.append("**숨이 넘어간다 — 사망 위기.**\n");

        // ① 회생 — 선천진기를 태운다 (사전 선언제 자동. 개화한 몸만, 평생 한 번)
        int cost = rules.revivalCostYears();
        int burned = ((Number) sheet.getOrDefault("수명_소모", 0)).intValue();
        if ("개화".equals(sheet.get("단전")) && sheet.get("회생_사용") == null
                && burned + cost < rules.innateQiTotal()) {
            sheet.put("회생_사용", today);
            sheet.put("수명_소모", burned + cost);
            db.logEvent("회생", "character", String.valueOf(chId),
                    Map.of("수명", cost, "누적_수명_소모", burned + cost));
            body.append("\n⚡ **회생** — 단전 깊은 곳에서 무언가가 스스로 타올랐다. 심장이 다시 뛴다.\n")
                    .append("선천진기 **").append(cost).append("년**을 태웠다 (누적 ")
                    .append(burned + cost).append("/").append(rules.innateQiTotal())
                    .append("년). 되돌릴 수 없다 — 그리고 두 번은 없다.\n")
                    .append("*(빈사는 유지된다. 여전히 누가 와야 한다)*");
            return body.toString();
        }

        // ② 구조 — NPC (이송이 되어야 의방에 닿는다)
        if (!inTown) {
            body.append("\n야산의 능선이다. 지나는 사람도, 옮겨 줄 손도 없다.\n"
                    + "*(마을에서 쓰러졌다면 누군가 의방으로 업고 뛰었을 것이다)*\n");
            return body + die(row, sheet, cause, killer);
        }
        Deaths.Gap clinic = clinic(today);
        if (!canTreatCritical(clinic)) {
            String who = rules.npcName(Legacy.PHYSICIAN_NPC);
            body.append("\n사람들이 당신을 들쳐 업고 의방으로 달렸다. 그러나 —\n")
                    .append(clinic.open()
                            ? "**" + physicianName(clinic) + "**이(가) 손을 떨며 물러섰다. \"…내 손으로는 안 되오. "
                                    + "이건 " + who + " 어른이나 보던 상처요.\"\n"
                                    + "*(대행 창구 — 불가 서비스: " + Legacy.CRITICAL_CARE + ")*\n"
                            : "의방은 닫혀 있다. **" + who + "**은(는) 죽었고, 그 자리는 아직 비어 있다.\n"
                                    + "*(서비스 공백 — 상태 " + clinic.state() + ")*\n");
            return body + die(row, sheet, cause, killer);
        }

        int treat = physicianPower(clinic);
        int need = rules.difficulty(rules.legacy.rescueDifficultyBand());
        int treatMargin = treat - need;
        db.logEvent("구조", "npc", clinic.actorKey() == null ? Legacy.PHYSICIAN_NPC : clinic.actorKey(),
                "character", String.valueOf(chId),
                Map.of("판정", "의술 지혈", "판정력", treat, "난이도", need, "마진", treatMargin,
                        "성공", treatMargin >= 0));
        body.append("\n사람들이 당신을 들쳐 업고 의방으로 달렸다.\n")
                .append("**").append(physicianName(clinic)).append("**의 의술 지혈 — 판정력 **")
                .append(treat).append("** vs ").append(need).append(" (").append(rules.legacy.rescueDifficultyBand())
                .append(") │ 마진 **").append(treatMargin >= 0 ? "+" : "").append(treatMargin).append("**\n");
        if (treatMargin < 0) {
            body.append("피가 멎지 않았다. 손은 최선을 다했지만, 세상에는 손으로 안 되는 것이 있다.\n");
            return body + die(row, sheet, cause, killer);
        }
        return body + stabilize(row, sheet, clinic, physicianName(clinic));
    }

    /**
     * 지혈 성공 — 침상에서 깨어난다. 중상 + 의원비 외상 장부 (빚).
     * death_and_legacy mc_procedure.wakeup: "의방_이송 — 중상 상태 + 의원비 외상 장부"
     */
    private String stabilize(Map<String, Object> row, Map<String, Object> sheet, Deaths.Gap clinic,
                             String who) throws Exception {
        long chId = ((Number) row.get("id")).longValue();
        sheet.put(WOUND, "중상");
        sheet.remove("빈사일");
        int fee = criticalCareFee(clinic);
        int debt = ((Number) sheet.getOrDefault("외상", 0)).intValue() + fee;
        sheet.put("외상", debt);
        db.logEvent("치료", "character", String.valueOf(chId),
                Map.of("결과", "안정", "외상", fee, "누적_외상", debt));
        return "피가 멎었다. 사흘 뒤, 당신은 의방의 침상에서 깨어났다.\n"
                + "🩸 **중상** — 살았다. 그러나 값은 남았다.\n"
                + "**" + who + "**이(가) 장부에 적는다. \"약재값·침값 — **" + fee + "문**. 외상일세. "
                + "몸이 낫거든 갚게. …갚지 않는 자에게는 일이 찾아가네.\"\n"
                + "*(누적 외상 " + debt + "문 — `/혼천 의방`에서 갚고 치료받는다)*";
    }

    /** economy.yml 의원.중상_치료 [2000, 5000] — 대행이면 가격 배율(1.3)까지 물린다 */
    private int criticalCareFee(Deaths.Gap clinic) {
        int base = rules.price("의원", Legacy.CRITICAL_CARE);
        Object mult = clinic.penalty().get("가격");
        return mult instanceof Number n ? (int) Math.round(base * n.doubleValue()) : base;
    }

    /**
     * 사망 확정 — 비가역. 여기서부터는 세계의 일이다.
     *   현장   전낭 전액이 그 자리에 흩어진다 (줍는 자의 것 — 주울 자가 없으면 흙에 스민다)
     *   예치   생전 지정 상속인 → (없으면) 혈연의 몫으로 봉인 → 무관 시작이면 세력 귀속·무주공산
     *   기억   소문(강도 2) · 명성 동결(세계 연표) · 피의 장부(원한은 살해자에게 이관)
     */
    private String die(Map<String, Object> row, Map<String, Object> sheet, String cause, String killer)
            throws Exception {
        long chId = ((Number) row.get("id")).longValue();
        String name = String.valueOf(row.get("name"));
        int today = db.worldDay();
        int wallet = ((Number) row.get("wallet")).intValue();

        sheet.put(WOUND, "빈사");
        sheet.put("사망", Map.of("일", today, "사인", cause, "살해자", String.valueOf(killer)));
        db.updateCharacter(chId, sheet, 0, String.valueOf(row.get("realm")), "사망", "황천");
        db.killCharacter(chId, today);
        db.logEvent("사망", "character", String.valueOf(chId), "npc", killer,
                Map.of("사인", cause, "살해자", String.valueOf(killer), "전낭_유실", wallet,
                        "경지", String.valueOf(row.get("realm"))));

        StringBuilder out = new StringBuilder("\n\n💀 **사망 — 개입은 없었다.**\n");

        // ① 현장 — 줍는 자의 것 (legacy.현장.drops)
        out.append("**현장**: ").append(String.join(" · ", rules.legacy.siteDrops()).replace('_', ' '))
                .append(" — 전낭 **").append(wallet).append("문**이 그 자리에 흩어졌다.\n");
        db.logEvent("유산_현장", "character", String.valueOf(chId),
                Map.of("전낭", wallet, "행방", "현장 드랍 — 줍는 자의 것"));

        // ② 예치 — 상속 순서 (legacy.예치.order)
        int deposit = db.bankBalance(chId, BANK_BRANCH);
        var heir = db.heirHint(chId, BANK_BRANCH);
        if (deposit > 0 && heir.isPresent()) {
            var heirChar = db.findCharacter(heir.get());
            if (heirChar.isPresent()) {
                long heirId = ((Number) heirChar.get().get("id")).longValue();
                db.bankMove(chId, BANK_BRANCH, -deposit);
                db.bankMove(heirId, BANK_BRANCH, deposit);
                out.append("**예치**: 생전 지정 상속인 **").append(heirChar.get().get("name"))
                        .append("**에게 전장 예치금 **").append(deposit).append("문**이 넘어갔다.\n");
                db.logEvent("상속", "character", String.valueOf(chId), "character",
                        String.valueOf(heirId), Map.of("금액", deposit, "순위", "생전_지정_상속인"));
            }
        } else if (deposit > 0) {
            out.append("**예치**: 전장에 **").append(deposit)
                    .append("문**이 남았다. 지정 상속인이 없다 — 핏줄이 찾아오지 않으면 무주공산이다.\n")
                    .append("*(다음 삶을 **혈연**으로 시작하면 이 몫에 손이 닿는다)*\n");
            db.logEvent("상속_보류", "character", String.valueOf(chId),
                    Map.of("금액", deposit, "순위", rules.legacy.inheritanceOrder()));
        }

        // ③ 명성 동결 — 별호는 세계 연표에 역사로 남는다 (NPC 가 회고할 수 있게)
        Map<String, Integer> tally = db.eventTally("character", String.valueOf(chId));
        db.logEvent("명성_동결", "world", "world", "character", String.valueOf(chId),
                Map.of("이름", name, "경지", String.valueOf(row.get("realm")), "사망일", today,
                        "사인", cause, "행적", tally, "규칙", rules.legacy.fameRule()));
        out.append("**명성**: 동결됐다. ").append(name).append("의 이름은 이제 세계의 연표에 있다 — ")
                .append("사람들이 언젠가 회고할 것이다.\n");

        // ④ 피의 장부 — 원한은 살해자에게 이관된다 (유족·새 캐릭터의 복수 훅)
        db.logEvent("피의_장부", "character", String.valueOf(chId), "npc", killer,
                Map.of("원한", killer, "사인", cause, "규칙", rules.legacy.bloodLedgerRule()));
        out.append("**피의 장부**: 원한이 **").append(killerLabel(killer))
                .append("**에게 적혔다. 이 빚은 죽음으로 사라지지 않는다.\n");

        // ⑤ 소문 — 죽음은 강도 2 소문이다 (억울한 죽음은 왜곡된다)
        int strength = rules.legacy.deathRumorStrength();
        spread(rumorGroup("사망", chId, today),
                name + "이(가) " + cause.replace('_', ' ') + "(으)로 죽었다더군", name, chId,
                List.of("살인", "괴사", "무인", "폭력"), strength,
                rules.initialAccuracy("간접_전문"), rules.originNetwork("market"), today);
        out.append("**소문**: 강도 ").append(strength)
                .append(" — 이 죽음은 청하현을 돈다. 먼 망에는 다른 이야기가 되어 닿을 것이다.\n\n")
                .append("*몸에 새긴 것은 무덤까지 간다 (")
                .append(String.join("·", rules.legacy.nonTransferable()))
                .append(" — 이전 불가).*\n")
                .append("**`/혼천 시작`으로 새 삶을 연다 — 전생의 흔적은 세계에 남아 있다.**");
        return out.toString();
    }

    /** 살해자 표기 — 등록 NPC 키면 이름으로, 그 밖(짐승·세력)이면 그대로 */
    private String killerLabel(String killer) {
        String name = rules.npcName(killer);
        return name == null ? killer : name;
    }

    // ─── 구조 — 동행이 온다 (사망 위기의 두 번째 문) ───

    /** `/혼천 구조 상대:@X` — 의술 지혈 (지혜 + 2d6 vs 어려움 14. action_pairs.치료) */
    @SuppressWarnings("unchecked")
    private void rescue(SlashCommandInteractionEvent event) throws Exception {
        if (notInRegion(event)) {
            return;
        }
        var medic = requireDebuted(event, event.getUser());
        if (medic.isEmpty()) {
            return;
        }
        var opt = event.getOption("상대");
        if (opt == null) {
            event.reply("`/혼천 구조 상대:@쓰러진 사람`").setEphemeral(true).queue();
            return;
        }
        User target = opt.getAsUser();
        var found = db.findCharacter(target.getId());
        if (found.isEmpty()) {
            event.reply("그 사람의 기록이 없다.").setEphemeral(true).queue();
            return;
        }
        Map<String, Object> row = found.get();
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        if (!dying(sheet)) {
            event.reply(row.get("name") + "은(는) 멀쩡히 서 있다 — 지혈할 상처가 없다.")
                    .setEphemeral(true).queue();
            return;
        }
        Map<String, Object> mine = (Map<String, Object>) medic.get().get("sheet");
        Map<String, Object> attrs = (Map<String, Object>) mine.get("능력치");
        Map<String, Object> skills = mine.get("기술") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        int wis = ((Number) attrs.getOrDefault("지혜", 2)).intValue();
        int medicine = ((Number) skills.getOrDefault("의술", 0)).intValue();
        int resist = rules.difficulty(rules.legacy.rescueDifficultyBand());
        int roll = dice.nextInt(6) + 1 + dice.nextInt(6) + 1;
        int power = wis + medicine + woundMod(mine);
        int margin = power + roll - resist;
        long medicId = ((Number) medic.get().get("id")).longValue();
        long chId = ((Number) row.get("id")).longValue();
        db.logEvent("구조", "character", String.valueOf(medicId), "character", String.valueOf(chId),
                Map.of("판정", "의술 지혈", "굴림", roll, "마진", margin, "성공", margin >= 0));

        EmbedBuilder judge = new EmbedBuilder().setColor(INK)
                .setTitle("판정 — 의술 지혈")
                .setDescription("**지혜 " + wis + "**" + (medicine > 0 ? " + 의술 " + medicine : "")
                        + " + 2d6 = **" + (power + roll) + "** vs " + resist
                        + " │ 마진 **" + (margin >= 0 ? "+" : "") + margin + "**");
        EmbedBuilder scene = new EmbedBuilder();
        if (margin >= 0) {
            sheet.put(WOUND, "중상");
            sheet.remove("빈사일");
            db.updateCharacter(chId, sheet, ((Number) row.get("wallet")).intValue(),
                    String.valueOf(row.get("realm")), "강호", "청하현");
            // 구명은 세력이 가장 크게 세는 공적이다 (favor.공적_대 / 주목 큰_공적_또는_구명)
            String faction = "orthodox";
            int favor = db.addFavor(faction, medicId, rules.factions.favorInput("공적_대"),
                    rules.factions.favorMax(), db.worldDay(), rules.factions);
            db.logEvent("세력_반응", "character", String.valueOf(medicId), "faction", faction,
                    Map.of("입력", "공적_대", "우호", favor, "사유", "구명"));
            scene.setColor(BLOOD).setTitle("구조 — 피가 멎었다")
                    .setDescription("옷자락을 찢어 상처를 동이고, 혈을 짚어 눌렀다. "
                            + row.get("name") + "의 숨이 다시 골라졌다.\n"
                            + "🩸 **중상** — 살렸다.\n"
                            + "*(정파 우호 **" + favor + "** — 사람을 살린 일은 조직이 오래 기억한다)*");
        } else {
            scene.setColor(INK).setTitle("구조 — 손이 미끄러진다")
                    .setDescription("피가 손가락 사이로 빠져나간다. 어디를 눌러야 하는지 모르겠다.\n"
                            + row.get("name") + "은(는) 아직 숨이 붙어 있지만, 시간이 없다.\n"
                            + "*(다시 시도할 수 있다 — 그러나 다음 세계일이 오면 사망 위기가 다시 굴러간다)*");
        }
        event.replyEmbeds(judge.build(), scene.build()).queue();
    }

    // ─── 의방 — 부상을 다스리고 외상을 갚는다 (economy.yml 의원 표) ───

    @SuppressWarnings("unchecked")
    private void clinicVisit(SlashCommandInteractionEvent event) throws Exception {
        if (notInRegion(event)) {
            return;
        }
        var found = requireDebuted(event, event.getUser());
        if (found.isEmpty()) {
            return;
        }
        Map<String, Object> row = found.get();
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        if (blockedByWound(event, sheet)) {
            return;   // 빈사는 제 발로 못 온다 — 세계가 옮겨 주거나, 동행이 오거나
        }
        int today = db.worldDay();
        Deaths.Gap gap = clinic(today);
        int wallet = ((Number) row.get("wallet")).intValue();
        long chId = ((Number) row.get("id")).longValue();
        String who = physicianName(gap);

        if (!gap.open()) {
            event.replyEmbeds(new EmbedBuilder().setColor(BLOOD).setTitle("의방 — 문이 닫혀 있다")
                    .setDescription("약재 냄새만 남고 사람이 없다. **" + rules.npcName(Legacy.PHYSICIAN_NPC)
                            + "**이(가) 죽은 뒤로 이 문은 열리지 않았다.\n"
                            + "*(공백 — 상태 " + gap.state() + ". 다치면 스스로 견디는 수밖에 없다)*").build()).queue();
            return;
        }

        int debt = ((Number) sheet.getOrDefault("외상", 0)).intValue();
        String wound = woundOf(sheet);
        // ① 외상부터 — 빚진 자는 새 치료를 못 받는다 (장부가 먼저다)
        if (debt > 0) {
            int pay = Math.min(wallet, debt);
            wallet -= pay;
            debt -= pay;
            sheet.put("외상", debt);
            db.updateCharacter(chId, sheet, wallet, String.valueOf(row.get("realm")), "강호", "청하현");
            db.logEvent("외상_상환", "character", String.valueOf(chId),
                    Map.of("상환", pay, "잔여", debt));
            event.replyEmbeds(new EmbedBuilder().setColor(INK).setTitle("의방 — 장부부터")
                    .setDescription(who + "이(가) 장부를 편다. \"먼저 셈부터 하세.\"\n"
                            + "외상 **" + pay + "문** 갚았다" + (debt > 0
                                    ? " — 아직 **" + debt + "문** 남았다. \"…다음에 또 오게.\""
                                    : " — 장부가 깨끗해졌다. \"이제 몸을 보세.\"")
                            + "\n*(소지금 " + wallet + "문)*").build()).queue();
            return;
        }
        if (wound == null) {
            event.replyEmbeds(new EmbedBuilder().setColor(INK).setTitle("의방")
                    .setDescription(who + "이(가) 맥을 짚어 보더니 손을 뗀다. "
                            + "\"성한 몸일세. 약은 아픈 사람 몫으로 두게.\"").build()).queue();
            return;
        }
        // ② 치료 — 중상은 대행이 못 본다 (불가_서비스)
        boolean critical = "중상".equals(wound);
        if (critical && !canTreatCritical(gap)) {
            event.replyEmbeds(new EmbedBuilder().setColor(BLOOD).setTitle("의방 — 손을 젓는다")
                    .setDescription("**" + who + "**이(가) 상처를 보고 고개를 젓는다. "
                            + "\"이건 내 손을 넘는 상처요. " + rules.npcName(Legacy.PHYSICIAN_NPC)
                            + " 어른이 계셨다면…\"\n"
                            + "*(대행 창구 — 불가 서비스: " + Legacy.CRITICAL_CARE + ". 중상은 세월로 낫는 수밖에)*")
                    .build()).queue();
            return;
        }
        int base = rules.price("의원", critical ? Legacy.CRITICAL_CARE : "경상_치료");
        Object mult = gap.penalty().get("가격");
        int fee = mult instanceof Number n ? (int) Math.round(base * n.doubleValue()) : base;
        if (wallet < fee) {
            event.replyEmbeds(new EmbedBuilder().setColor(INK).setTitle("의방 — 값이 모자란다")
                    .setDescription(who + "이(가) 약재를 도로 내려놓는다. \"" + wound + " 치료는 **"
                            + fee + "문**일세. 지금은 " + wallet + "문뿐이군.\"\n"
                            + "*(외상은 죽을 뻔한 자에게만 준다 — 걸어 들어온 자는 셈을 먼저 한다)*")
                    .build()).queue();
            return;
        }
        wallet -= fee;
        String healed = Legacy.heal(wound);
        if (healed == null) {
            sheet.remove(WOUND);
        } else {
            sheet.put(WOUND, healed);
        }
        db.updateCharacter(chId, sheet, wallet, String.valueOf(row.get("realm")), "강호", "청하현");
        db.logEvent("치료", "character", String.valueOf(chId), "npc",
                gap.actorKey() == null ? Legacy.PHYSICIAN_NPC : gap.actorKey(),
                Map.of("부상", wound, "이후", String.valueOf(healed), "비용", fee));
        event.replyEmbeds(new EmbedBuilder().setColor(INK).setTitle("의방 — " + wound + " 치료")
                .setDescription(who + "이(가) 침을 놓고 약을 달였다. **-" + fee + "문**\n"
                        + (healed == null
                                ? "🩹 **완쾌** — 몸이 가볍다. 판정 페널티가 사라졌다."
                                : "🩸 **" + healed + "** — 한 칸 나았다. *(판정 "
                                        + rules.conditionModifier(healed) + ")*")
                        + "\n*(소지금 " + wallet + "문)*").build()).queue();
    }

    // ─── 전장 — 예치·인출·상속인 지정 (금서방. 죽음이 건드리는 유일한 재산) ───

    @SuppressWarnings("unchecked")
    private void bank(SlashCommandInteractionEvent event) throws Exception {
        if (notInRegion(event)) {
            return;
        }
        var found = requireDebuted(event, event.getUser());
        if (found.isEmpty()) {
            return;
        }
        Map<String, Object> row = found.get();
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        if (blockedByWound(event, sheet)) {
            return;
        }
        long chId = ((Number) row.get("id")).longValue();
        int today = db.worldDay();
        int wallet = ((Number) row.get("wallet")).intValue();
        Deaths.Gap branch = gapOf("geumseobang", today, db.deadNpcs());
        if (!branch.open()) {
            // service_freeze — 지점주가 죽으면 상속 절차 자체가 멈춘다 (npc_death 주석 그대로)
            event.replyEmbeds(new EmbedBuilder().setColor(BLOOD).setTitle("전장 — 봉인")
                    .setDescription("전장 문에 봉인지가 붙어 있다. **" + rules.npcName("geumseobang")
                            + "**이(가) 죽은 뒤로 예치·인출·전표 발행이 전면 정지됐다.\n"
                            + "*(상태 " + branch.state() + " — 상속 절차조차 여기서 멈춘다)*").build()).queue();
            return;
        }

        var depositOpt = event.getOption("예치");
        var withdrawOpt = event.getOption("인출");
        var heirOpt = event.getOption("상속인");
        StringBuilder body = new StringBuilder();

        if (heirOpt != null) {
            User heir = heirOpt.getAsUser();
            var heirChar = db.findCharacter(heir.getId());
            if (heirChar.isEmpty()) {
                event.reply("그 사람은 아직 강호에 없다 — 상속인은 살아 있는 자라야 한다.")
                        .setEphemeral(true).queue();
                return;
            }
            db.setHeir(chId, BANK_BRANCH, heir.getId());
            db.logEvent("상속인_지정", "character", String.valueOf(chId), "character",
                    String.valueOf(((Number) heirChar.get().get("id")).longValue()),
                    Map.of("상속인", String.valueOf(heirChar.get().get("name"))));
            body.append("**상속인 지정** — ").append(heirChar.get().get("name"))
                    .append(". 금서방이 장부 귀퉁이에 이름을 적었다.\n")
                    .append("\"내가 죽거든, 이 사람에게.\" — 말은 그렇게 남는다.\n");
        }
        if (depositOpt != null) {
            int amount = Math.max(0, Math.min(wallet, (int) depositOpt.getAsLong()));
            wallet -= amount;
            int balance = db.bankMove(chId, BANK_BRANCH, amount);
            db.logEvent("예치", "character", String.valueOf(chId), "npc", "geumseobang",
                    Map.of("금액", amount, "잔액", balance));
            body.append("**예치 +").append(amount).append("문** — 전표를 받았다. (잔액 ")
                    .append(balance).append("문)\n");
        }
        if (withdrawOpt != null) {
            int want = Math.max(0, (int) withdrawOpt.getAsLong());
            int balance = db.bankBalance(chId, BANK_BRANCH);
            int amount = Math.min(want, balance);
            int fee = rules.economy.withdrawFee(amount, false);
            db.bankMove(chId, BANK_BRANCH, -amount);
            wallet += Math.max(0, amount - fee);
            db.logEvent("인출", "character", String.valueOf(chId), "npc", "geumseobang",
                    Map.of("금액", amount, "수수료", fee));
            body.append("**인출 ").append(amount).append("문** (수수료 ").append(fee)
                    .append("문) — 손에 쥔 것은 ").append(Math.max(0, amount - fee)).append("문.\n");
        }
        db.updateCharacter(chId, sheet, wallet, String.valueOf(row.get("realm")), "강호", "청하현");
        int balance = db.bankBalance(chId, BANK_BRANCH);
        var heir = db.heirHint(chId, BANK_BRANCH);
        String heirName = "없음 (무주공산 — 핏줄이 찾아오지 않으면 세력에 귀속된다)";
        if (heir.isPresent()) {
            var hc = db.findCharacter(heir.get());
            heirName = hc.map(c -> String.valueOf(c.get("name"))).orElse("(강호를 떠난 이)");
        }
        if (body.isEmpty()) {
            body.append(rules.npcName("geumseobang")).append("이 주판을 놓는다. ")
                    .append("\"맡기시려오, 찾으시려오? …아니면, 뒷일을 정하시려오?\"\n");
        }
        event.replyEmbeds(new EmbedBuilder().setColor(INK)
                .setTitle("전장 — 청하 지점")
                .setDescription(body + "\n**예치 잔액** " + balance + "문 · **소지금** " + wallet + "문\n"
                        + "**지정 상속인** " + heirName
                        + "\n\n*(현장의 것은 흩어진다 — 죽어서 지킬 수 있는 것은 맡긴 것뿐이다)*")
                .build()).queue();
    }

    // ─── 소문 — 저잣거리에 무슨 말이 도는가 (플레이어의 소문망 창구) ───

    private void rumorBoard(SlashCommandInteractionEvent event) throws Exception {
        if (notInRegion(event) || requireDebuted(event, event.getUser()).isEmpty()) {
            return;
        }
        int today = db.worldDay();
        // 저잣거리에서 들리는 것 = 민간/장터 망 (누구나 닿는 망)
        String net = rules.originNetwork("market");
        List<Rumors.Heard> heard = db.heard(today, net, rules.rumors.decayEveryDays());
        EmbedBuilder eb = new EmbedBuilder().setColor(INK)
                .setTitle("저잣거리 — " + today + "일차 " + rules.rumors.networkName(net))
                .setDescription(heard.isEmpty()
                        ? "장터가 조용하다. 값 흥정 소리뿐, 입에 오르내리는 이야기가 없다."
                        : "사람들이 모여 수군거린다. 같은 이야기라도 입을 옮길수록 달라진다 —");
        for (Rumors.Heard h : heard.stream().limit(6).toList()) {
            eb.addField("강도 " + h.intensity() + " · " + rules.rumors.band(h.accuracy())
                            + " (정확도 " + h.accuracy() + ")",
                    rules.rumors.tell(h) + "\n*— " + (today - h.bornDay()) + "일 전 이 망에 닿았다*", false);
        }
        eb.setFooter("먼 망에는 더 늦게, 더 뒤틀려 닿는다. `/혼천 대화`로 다른 망의 이야기를 캘 수 있다.");
        event.replyEmbeds(eb.build()).queue();
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
        if (blockedByWound(event, sheet)) {
            return;
        }
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

    /** 관리자 정산 — 세계일 +1 (자정 스케줄러의 수동판). 세계가 하루를 산다 */
    private void settleDay(SlashCommandInteractionEvent event) throws Exception {
        if (event.getMember() == null || !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("서버 관리 권한이 필요하다.").setEphemeral(true).queue();
            return;
        }
        Dawn dawn = advanceWorld();
        event.replyEmbeds(new EmbedBuilder().setColor(BLOOD)
                .setTitle(dawn.day() + "일차 아침이 밝았다")
                .setDescription("몸이 개운하다 — 일일 적립·수련·연속 감쇠가 새로 시작된다.\n\n" + dawn.report())
                .build()).queue();
    }

    /** 세계일 정산의 결과 — 지역 채널에 방송할 아침 소식 */
    record Dawn(int day, String report) {
    }

    /**
     * 세계가 하루를 산다 — 세계일 +1 이 부르는 것들 (자정 스케줄러·관리자 정산의 공용 지점):
     *   ① 소문 도달   오늘 닿는 도달들이 살아난다 (심을 때 계산해 둔 스케줄 — 여기서 하는 일은 없다)
     *   ② 세력 인지   그 도달들이 조직 채널에 닿았으면 주목이 오른다 (중복 가산 금지)
     *   ③ 빈사 마감   사망 위기의 창구가 닫힌다 — 개입 없는 빈사자는 여기서 죽는다
     * 감쇠(소문 강도·세력 주목·우호)는 여기서 깎지 않는다 — 읽는 순간 계산한다 (결정론).
     */
    Dawn advanceWorld() throws Exception {
        int day = db.advanceDay();
        StringBuilder report = new StringBuilder();

        factionAwareness(day);

        // 오늘 이 세계에 새로 닿은 이야기들
        int arrived = db.arrivalCountOn(day);
        if (arrived > 0) {
            report.append("🗣️ **소문 ").append(arrived)
                    .append("건이 새 망에 닿았다** — 먼 곳일수록 다른 이야기가 되어 도착한다.\n");
        }

        // 빈사 마감 — 개입 창구가 닫힌다 (death_pipeline.no_intervention: 사망_확정_비가역)
        for (Map<String, Object> row : db.activeCharacters()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
            if (!dying(sheet)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> fall = sheet.get("사망") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            String cause = String.valueOf(fall.getOrDefault("사인", "사선"));
            // 빈사자는 마을 안(의방으로 옮겨진 상태)으로 본다 — 야외에서 밤을 넘겼다면 이미 죽었다
            String body = crisis(row, sheet, true, cause, "미상", false);
            long chId = ((Number) row.get("id")).longValue();
            var still = db.findCharacterById(chId);
            if (still.isPresent() && !"사망".equals(still.get().get("status"))) {
                db.updateCharacter(chId, sheet, ((Number) row.get("wallet")).intValue(),
                        String.valueOf(row.get("realm")), "강호", "청하현");
            }
            report.append("\n💀 **").append(row.get("name")).append("** — 빈사의 밤이 지났다.")
                    .append(body.contains("사망 — 개입은 없었다") ? " **숨이 끊겼다.**" : " 아직 숨이 있다.")
                    .append('\n');
        }
        return new Dawn(day, report.isEmpty() ? "세계는 조용하다." : report.toString());
    }

    /**
     * 관리자 검증 명령 — 플레이어에게 사선을 긋는다 (`/혼천 사선`).
     * 죽음을 시험하는 유일한 손잡이다: 전투에서 치명적 실패가 뜨기를 기다릴 수는 없다.
     *   부상  한 칸 악화 (살의 없음 — 중상에서 멈춘다)
     *   빈사  사선을 긋는다 (살의 있음) — 사망 위기 파이프가 곧장 돈다
     *   즉사  개입 없이 사망 확정 (파이프 우회 — 유산·소문·피의 장부만 검증할 때)
     */
    @SuppressWarnings("unchecked")
    private void adminDeathLine(SlashCommandInteractionEvent event) throws Exception {
        if (event.getMember() == null || !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("서버 관리 권한이 필요하다.").setEphemeral(true).queue();
            return;
        }
        var opt = event.getOption("상대");
        if (opt == null) {
            event.reply("`/혼천 사선 상대:@유저 [단계] [장소] [살해자]`").setEphemeral(true).queue();
            return;
        }
        var found = db.findCharacter(opt.getAsUser().getId());
        if (found.isEmpty() || !"강호".equals(found.get().get("status"))) {
            event.reply("강호에 나와 있는 캐릭터가 아니다.").setEphemeral(true).queue();
            return;
        }
        Map<String, Object> row = found.get();
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        String stage = optionOr(event, "단계", "빈사");
        String killer = optionOr(event, "살해자", "미상");
        boolean inTown = !"야외".equals(optionOr(event, "장소", "마을"));
        long chId = ((Number) row.get("id")).longValue();
        event.deferReply().queue();

        String body;
        if ("즉사".equals(stage)) {
            body = die(row, sheet, "처형", killer);
        } else {
            boolean lethal = !"부상".equals(stage);
            int steps = "빈사".equals(stage) ? Legacy.WOUNDS.size() : 1;
            body = takeWound(row, sheet, steps, lethal, inTown, "사선", killer);
        }
        var still = db.findCharacterById(chId);
        if (still.isPresent() && !"사망".equals(still.get().get("status"))) {
            db.updateCharacter(chId, sheet, ((Number) row.get("wallet")).intValue(),
                    String.valueOf(row.get("realm")), "강호", "청하현");
        }
        event.getHook().sendMessageEmbeds(new EmbedBuilder().setColor(BLOOD)
                .setTitle("[GM] 사선 — " + row.get("name"))
                .setDescription("단계 **" + stage + "** · 장소 " + (inTown ? "마을 안" : "야외")
                        + " · 살해자 " + killer + body).build()).queue();
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

        // 빈사자는 포권도 못 한다
        Map<String, Object> sheetA = (Map<String, Object>) challengerRow.get().get("sheet");
        Map<String, Object> sheetB = (Map<String, Object>) targetRow.get().get("sheet");
        if (dying(sheetA) || dying(sheetB)) {
            event.editMessage("한쪽이 빈사다 — 비무가 성립하지 않는다. 살릴 사람이 먼저다 (`/혼천 구조`).")
                    .setComponents().queue();
            return;
        }

        // 발경 — 양측 각자 자격이 되면 기를 싣는다 (대칭 원칙, 차감은 duelGrant에서)
        boolean qiA = duelBalgyeong(challengerRow.get());
        boolean qiB = duelBalgyeong(targetRow.get());
        int execA = duelExec(challengerRow.get()) + (qiA ? 1 : 0) + woundMod(sheetA);
        int execB = duelExec(targetRow.get()) + (qiB ? 1 : 0) + woundMod(sheetB);
        int rollA = dice.nextInt(6) + 1 + dice.nextInt(6) + 1;
        int rollB = dice.nextInt(6) + 1 + dice.nextInt(6) + 1;
        JudgmentEngine.Opposed opposed = rules.judgment.directOpposed(execA, rollA, execB, rollB);

        // 양측 화후 — 패배도 배움이다 (동수·비무_대련, 각자의 일일 상한)
        // 승자 = 실전 마크 +1 (battle_marks: "비무 승리" — 이류 승급 요건의 사건 축)
        int today = db.worldDay();
        boolean challengerWins = !opposed.draw() && opposed.margin() > 0;
        boolean targetWins = !opposed.draw() && opposed.margin() < 0;
        double grantedA = duelGrant(challengerRow.get(), today, challengerWins, qiA);
        double grantedB = duelGrant(targetRow.get(), today, targetWins, qiB);

        String winner = opposed.draw() ? null : (opposed.margin() > 0 ? nameA : nameB);
        String loser = opposed.draw() ? null : (opposed.margin() > 0 ? nameB : nameA);
        db.logEvent("비무", "character", String.valueOf(((Number) challengerRow.get().get("id")).longValue()),
                "character", String.valueOf(((Number) targetRow.get().get("id")).longValue()),
                Map.of("상대", nameB, "굴림A", rollA, "굴림B", rollB, "마진", opposed.margin(),
                        "등급", opposed.tier().name(), "무승부", opposed.draw()));

        // A — 비무에는 살의가 없다 (death_pipeline.default_defeat): 크게 져도 중상에서 멈춘다.
        //     대성공으로 이긴다는 것은 상대를 크게 상하게 했다는 뜻이다 — 그래도 죽이지는 않는다.
        //     ※ duelGrant 가 이미 시트를 영속화했으므로 여기서는 다시 읽어 온다 (덮어쓰기 금지)
        String hurt = "";
        if ("대성공".equals(opposed.tier().name()) && !opposed.draw()) {
            long loserId = ((Number) (opposed.margin() > 0 ? targetRow : challengerRow)
                    .get().get("id")).longValue();
            var fresh = db.findCharacterById(loserId);
            if (fresh.isPresent()) {
                Map<String, Object> loserSheet = new LinkedHashMap<>(
                        (Map<String, Object>) fresh.get().get("sheet"));
                hurt = takeWound(fresh.get(), loserSheet, 1, false, true, "비무", String.valueOf(winner));
                db.updateCharacter(loserId, loserSheet,
                        ((Number) fresh.get().get("wallet")).intValue(),
                        String.valueOf(fresh.get().get("realm")), "강호", "청하현");
            }
        }

        // B — 플레이어가 소문의 주체가 된다: 비무는 사람들 앞에서 벌어진다 (공개 다수 목격 — 강도 3)
        if (winner != null) {
            long winnerId = ((Number) (opposed.margin() > 0 ? challengerRow : targetRow)
                    .get().get("id")).longValue();
            spread(rumorGroup("비무", winnerId, today),
                    winner + "이(가) " + loser + "을(를) 비무에서 눌렀다더군", winner, winnerId,
                    List.of("무인", "무재", "폭력"),
                    rules.rumors.intensityByVisibility("공개_다수_목격"),
                    rules.initialAccuracy("직접_목격"), rules.originNetwork("cheongha_inn"), today);
        }

        String verdict = opposed.draw()
                ? "동점 — **상쇄, 무승부**"
                : "마진 **" + (opposed.margin() > 0 ? "+" : "") + opposed.margin()
                        + "** → **" + opposed.tier().name() + "** (" + nameA + " 시점) — 승자 **" + winner + "**";
        EmbedBuilder result = new EmbedBuilder().setColor(BLOOD)
                .setTitle("비무 — " + nameA + " 대 " + nameB)
                .setDescription(nameA + ": 무예 " + execA + (qiA ? " ⚡발경" : "")
                        + " + 2d6 = **" + (execA + rollA) + "**" + extremeMark(rollA)
                        + "\n" + nameB + ": 무예 " + execB + (qiB ? " ⚡발경" : "")
                        + " + 2d6 = **" + (execB + rollB) + "**" + extremeMark(rollB)
                        + "\n" + verdict
                        + "\n수련: " + nameA + String.format(" +%.2f일치 · ", grantedA)
                        + nameB + String.format(" +%.2f일치", grantedB)
                        + "\n\n" + Narration.duel(winner, loser, opposed.draw())
                        + hurt);
        event.editMessageEmbeds(result.build()).setComponents().queue();
    }

    /** 비무 발경 자격 — 시트 읽기 전용 (차감은 duelGrant의 단일 영속 지점) */
    @SuppressWarnings("unchecked")
    private boolean duelBalgyeong(Map<String, Object> row) {
        return canBalgyeong((Map<String, Object>) row.get("sheet"), String.valueOf(row.get("realm")));
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
    private double duelGrant(Map<String, Object> row, int today, boolean won, boolean usedQi)
            throws Exception {
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        if (usedQi) {
            payBalgyeong(sheet);
        }
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
        if (blockedByWound(event, sheet)) {
            return;
        }
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
        /** 혈연 시작 — 전생의 유산·피의 장부를 짊어진다 (전생이 없으면 무의미) */
        boolean lineage;
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
