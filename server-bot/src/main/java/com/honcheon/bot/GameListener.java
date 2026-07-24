package com.honcheon.bot;

import com.honcheon.core.rules.JudgmentEngine;
import com.honcheon.core.rules.RulesConfig;
import com.honcheon.domain.FactionService;
import com.honcheon.domain.RegionService;
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
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.modals.Modal;

import java.awt.Color;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
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
    /** 접속의 문이 선 자리 (world_meta) — 마크의 [혼천 접속] 클릭이 여기로 온다. 등록부가 키를 정한다 */
    private static final String GATE_CHANNEL_KEY = "접합:채널";
    private static final String GATE_GUILD_KEY = "접합:길드";

    private final Rules rules;
    private final GameStore db;
    private final LlmRenderer renderer;
    private final Random dice = new Random();

    /**
     * ★ 세력·지역의 <b>도메인 서비스</b> — 이 어댑터가 규칙을 아는 유일한 방법.
     *
     * <p>전에는 이 자리에서 {@code db.addFavor(…, rules.factions)} 라고 불렀다 — 장부가 규칙을
     * 인자로 받아 제 안에서 산수를 했다. 그래서 <b>디스코드가 도메인을 들고 있었다.</b>
     * 이제 이 클래스는 <b>묻고 렌더할 뿐</b>이다: 얼마가 되었는지는 도메인이 안다.
     */
    private final FactionService factions;
    private final RegionService regions;

    /**
     * 개인 메인스토리 (B-109) — 발단별 사슬의 평가기. <b>렌즈이지 콘텐츠가 아니다</b>:
     * 원장에 새 사건이 적힌 직후 {@link #storyTick} 가 열린 마디를 재평가한다 (폴링 없음).
     * 등록부(config/personal_story.yml)가 깨져도 봇은 죽지 않는다 — 사슬만 잠긴다.
     */
    private final PersonalStory story;

    /**
     * ★ B-110 — 세계 시계: 막(幕)이 박(拍)을 발화하고, 유효일이 차면 다음 막으로.
     * 등록부는 {@code config/world_clock.yml} — 깨져도 봇은 죽지 않는다, 시계만 잠긴다
     * (PersonalStory 와 같은 문법). 자정 정산 {@link #advanceWorld} 의 한 단계로 돈다.
     */
    private final WorldClockEngine worldClock;

    private final Map<String, Creation> creations = new ConcurrentHashMap<>();

    /**
     * ★★ <b>붓 — 서사를 그리는 단 하나의 차선</b> (「배는 한 명씩 탄다」).
     *
     * <p>전에는 {@link LlmRenderer} 를 <b>곧장</b> 불렀다 — 즉 넷이 동시에 서장에 들면 넷을
     * <b>동시에 던졌다.</b> 그런데 GPU 는 하나다: 실측 4건 동시 = <b>89.5초</b> (1건은 22.4초).
     * 옛 타임아웃 25초에 <b>전원이 걸려 아무도 글을 못 받았다.</b> 이제 {@link Scribe} 가 줄을 세운다.
     */
    private final Scribe scribe;

    /** 접합의 수락은 <b>다른 스레드·몇 초 뒤</b>에 온다 — 그때 사람에게 말을 걸 손 (onReady 가 쥔다) */
    private volatile net.dv8tion.jda.api.JDA jda;

    /** 되돌리는 손 — 시험을 위해 장부를 지운다 (HoncheonBot 이 쥐여 준다. 없으면 명령이 잠긴다) */
    private volatile Reset reset;

    public GameListener(Rules rules, GameStore db, LlmRenderer renderer, Scribe scribe) {
        this.rules = rules;
        this.db = db;
        this.renderer = renderer;
        this.scribe = scribe;
        this.factions = new FactionService(rules.factions, db);
        this.regions = new RegionService(rules.regions, db);
        this.story = PersonalStory.load();   // HONCHEON_CONFIG 규약 — 깨지면 severe 내고 잠긴다
        // ★ B-110 — 세계 시계. 소문은 spread(전파·세력 인지 배선)로, 지역은 도메인 서비스로 심는다
        this.worldClock = new WorldClockEngine(rules, db, this.regions, this::spread);
    }

    /**
     * ★ B-109 — <b>logEvent 훅</b>: 원장에 사슬이 세는 사건(의뢰_완수·사냥·비무·대화·탐방·기연·
     * 승급·favor)이 적힌 직후 이 손이 돈다. 마디 전이는 {@link PersonalStory#tick} 이
     * {@code 사슬_마디} 사건으로 (멱등하게) 적는다 — 원장이 곧 상태다.
     *
     * <p>실패는 삼킨다 — 사슬은 렌즈일 뿐, 사냥·의뢰의 본 흐름을 막을 무게가 아니다.
     *
     * @param realmOverride 방금 승급했으면 그 경지 (DB 반영 전일 수 있다). 모르면 null — 행에서 읽는다
     */
    private void storyTick(long chId, String realmOverride) {
        if (story.disabled()) {
            return;
        }
        try {
            var found = db.findCharacterById(chId);
            if (found.isEmpty()) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> sheet = (Map<String, Object>) found.get().get("sheet");
            String realm = realmOverride != null
                    ? realmOverride : String.valueOf(found.get().get("realm"));
            story.tick(db, factions, chId, db.worldDay(), realm, sheet);
        } catch (Exception e) {
            System.err.println("개인 사슬 평가 실패 (chId=" + chId + "): " + e.getMessage());
        }
    }

    void setReset(Reset reset) {
        this.reset = reset;
    }

    // ─── 초기화 — 되돌린다 (시험용). **두 번 묻는다: 명령 한 번, 버튼 한 번** ───
    //
    // ★ 되돌릴 수 없는 일이다. 그래서 명령은 **아무것도 지우지 않는다** — 무엇을 지울지 말하고 묻기만 한다.
    //   지우는 손은 [되돌린다] 버튼 하나뿐이고, 그 버튼은 **명령을 친 사람에게만** 듣는다.

    private void resetAsk(SlashCommandInteractionEvent event) throws Exception {
        Reset r = this.reset;
        if (r == null || r.locked()) {
            event.reply("초기화가 잠겨 있다 — 등록부(config/reset.yml)를 못 읽었다."
                            + (r == null ? "" : "\n> " + r.fault()))
                    .setEphemeral(true).queue();
            return;
        }
        String scope = event.getOption("범위") == null ? "" : event.getOption("범위").getAsString();
        if (r.say(scope).isEmpty()) {
            event.reply("모르는 범위: " + scope).setEphemeral(true).queue();
            return;
        }
        // ★ 남의 것은 못 지운다 — 대상을 대려면 서버 관리자여야 한다
        User target = event.getOption("대상") == null ? null : event.getOption("대상").getAsUser();
        if (target != null && !target.getId().equals(event.getUser().getId())) {
            if (event.getMember() == null
                    || !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
                event.reply("남의 것은 못 지운다 — 대상을 대려면 **서버 관리자**여야 한다.")
                        .setEphemeral(true).queue();
                return;
            }
        }
        resetConfirm(event, scope, target == null ? event.getUser() : target, event.getUser());
    }

    /**
     * <b>두 번째 물음</b> — 여기서도 아무것도 안 지운다. 지우는 손은 [되돌린다] 버튼 하나뿐이다.
     *
     * <p>슬래시({@code /초기화})와 안내판의 [처음부터 다시] 가 <b>둘 다 여기로 온다</b> —
     * 되돌릴 수 없는 일에 문이 둘이면, 언젠가 한쪽만 고쳐진다.
     */
    private void resetConfirm(IReplyCallback event, String scope, User who, User asker)
            throws Exception {
        Reset r = this.reset;
        String key = "rs:" + scope + ":" + who.getId() + ":" + asker.getId();
        event.reply("**되돌리려는가 — " + scope + "**\n> " + r.say(scope)
                        + "\n\n대상: " + who.getAsMention()
                        + "\n\n*백업은 항상 뜬다 (`run/backup-<시각>/`). 백업이 실패하면 아무것도 안 지운다.*"
                        + "\n*세계(청하현·사람·소문·원장·달력)는 건드리지 않는다.*"
                        + "\n**되돌릴 수 없다.**")
                .addComponents(ActionRow.of(
                        Button.danger(key, "되돌린다"),
                        Button.secondary("rx:-:-:-", "그만둔다")))
                .setEphemeral(true).queue();
    }

    private void onResetConfirm(ButtonInteractionEvent event, String scope, String targetId,
            String askerId) throws Exception {
        Reset r = this.reset;
        if (r == null || r.locked()) {
            event.editMessage("초기화가 잠겨 있다.").setComponents().queue();
            return;
        }
        // 버튼은 **물은 사람에게만** 듣는다 (남의 확인 창을 눌러 남을 지울 수 없다)
        if (!event.getUser().getId().equals(askerId)) {
            event.reply("이 물음은 당신에게 온 것이 아니다.").setEphemeral(true).queue();
            return;
        }
        event.deferEdit().queue();
        try {
            Reset.Report report = r.reset(scope, targetId, null,
                    "discord:" + event.getUser().getName(), null);
            event.getHook().editOriginal(Reset.render(report)).setComponents().queue();
        } catch (Exception e) {
            // ★ 백업 실패도 여기로 온다 — 그때는 **한 행도 안 지워졌다**. 그대로 말한다.
            event.getHook().editOriginal("초기화 실패 — **아무것도 지우지 않았다.**\n> " + e.getMessage())
                    .setComponents().queue();
        }
    }

    // ─── 슬래시 명령 ───

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        try {
            // ★ /접합문 은 **최상위 명령**이다 (/혼천 의 서브커맨드가 아니다 — 25칸 상한).
            //   그래서 서브커맨드 이름이 null 이다. 이름으로 먼저 가른다.
            if ("접합문".equals(event.getName())) {
                postLinkGate(event);
                return;
            }
            // ★ /초기화 도 **최상위 명령**이다 — /혼천 은 서브커맨드 25칸이 이미 꽉 찼다.
            //   (26번째를 넣으면 봇이 기동조차 못 한다: "Cannot have more than 25 subcommands")
            if ("초기화".equals(event.getName())) {
                resetAsk(event);
                return;
            }
            // ★★ /안내판 — **사람이 칠 마지막 명령**이다. 한 번 세우면 그 뒤로는 아무도 안 친다
            //   (/접합문 과 같은 문법. 그리고 최상위이므로 25칸을 **먹지 않는다**)
            if ("안내판".equals(event.getName())) {
                postPanel(event);
                return;
            }
            // ★ /막개전 — 세계 시계 human gate 의 승인 (B-110 설계 §4). /혼천 의 25칸은 꽉 찼으므로
            //   최상위 명령이다 (/안내판·/접합문의 선례 — B-020 탈출구 ①)
            if ("막개전".equals(event.getName())) {
                approveActGate(event);
                return;
            }
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
                case "접속" -> linkAccount(event);        // ★ 신원 접합 — 마크의 몸을 이 이름에 잇는다
                case "접속해제" -> unlinkAccount(event);  // 스스로 끊는다 (혈채는 남는다)
                // ★ 여기 `case "접합문"` 이 있었다 — **닿을 수 없는 줄이다.** 접합문은 25칸 상한 때문에
                //   최상위 명령으로 빠졌고(HoncheonBot:76), 그래서 위의 이름 검사가 먼저 가로챈다.
                //   /혼천 에는 그런 서브커맨드가 **등록조차 되지 않는다.** 죽은 줄은 남기지 않는다.
                case "지역등록" -> registerRegion(event);
                case "정산" -> settleDay(event);
                case "사망" -> adminKill(event);
                case "사선" -> adminDeathLine(event);    // A — 죽음 검증용 (관리자)
                case "명분" -> adminMyeongbun(event);    // 단계 5 — 정치 검증용 (관리자)
                case "사정" -> adminSectBurden(event);   // ★ 연합의 브레이크 (관리자)
                default -> event.replyEmbeds(help()).setEphemeral(true).queue();
            }
        } catch (Exception e) {
            event.reply("오류: " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    /**
     * 캐릭터 생성 — <b>슬래시({@code /혼천 시작})와 안내판의 [강호에 들다] 가 둘 다 여기로 온다.</b>
     *
     * <p>★ 그래서 인자가 {@code SlashCommandInteractionEvent} 가 아니라 {@link IReplyCallback} 이다:
     * <b>길은 둘이어도 문은 하나여야 한다.</b> 두 벌을 만들면 하나가 낡는다 (이 저장소가 반복해서 데인 병).
     * 이 함수의 <b>로직은 한 줄도 바뀌지 않았다</b> — 문의 손잡이가 넓어졌을 뿐이다.
     */
    private void startCreation(IReplyCallback event) throws Exception {
        var existing = db.findCharacter(event.getUser().getId());
        if (existing.isPresent()) {
            // ★★ 안내판의 [강호에 들다] 는 **누구에게나 보인다** (공용 메시지라 가릴 수가 없다).
            //   그러므로 **이미 태어난 사람이 누를 수 있고**, 그때 **침묵하면 위반**이다 — 그렇다고 말한다.
            //   이 대답은 그 사람에게만 간다 (ephemeral). 문장은 등록부에 있다 — 코드가 짓지 않는다.
            event.reply(rules.panelBoard("already", "이미 캐릭터가 있다 — [내 자리] 를 눌러라.")
                            .replace("{name}", String.valueOf(existing.get().get("name"))))
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
        if (rules.genderAsk()) {
            event.replyEmbeds(genderEmbed())
                    .addComponents(ActionRow.of(genderButtons()))
                    .setEphemeral(true).queue();
            return;
        }
        event.replyEmbeds(questionEmbed(0))
                .addComponents(ActionRow.of(questionButtons(0)))
                .setEphemeral(true).queue();
    }

    // ─── 성별 — 생성의 첫 물음 (player_creation.yml gender) ───
    //
    // ★ 왜 생겼나: 사용자가 겪었다 — "성별 선택이 없어 강제로 루트가 제한됨."
    //   생성 문답에는 성별이 없었고, 그래서 캐릭터는 성별 없이 태어났다. 이제 묻는다.
    //
    // ★★ 여기는 **묻고 기록하는 것까지만** 한다. 성별이 무엇을 여닫는지(입문 가능 문파·호칭·무공 계열·
    //   NPC 반응)는 **아직 등록부에 없다** (player_creation.yml gender.gates 는 비어 있다).
    //   그 빈 칸을 코드가 채우지 않는다 — 사용자가 정한다. 지금은 아무것도 막지 않는다.

    private MessageEmbed genderEmbed() {
        StringBuilder body = new StringBuilder();
        rules.genderOptions().forEach((key, v) ->
                body.append("**").append(rules.genderLabel(key)).append("**  "));
        return new EmbedBuilder().setColor(INK)
                .setTitle("태어남 — 성별")
                .setDescription(rules.genderText("prompt", "너는 사내로 태어났는가, 계집으로 태어났는가.")
                        + "\n\n" + body)
                .setFooter(rules.genderText("footer", "성별은 바꿀 수 없다 — 이 몸으로 강호에 선다."))
                .build();
    }

    /** 등록부에 있는 성별만 버튼이 된다 (등록제 — 여기 없는 성별은 세계에 존재하지 않는다) */
    private List<Button> genderButtons() {
        List<Button> buttons = new ArrayList<>();
        rules.genderOptions().keySet().forEach(key ->
                buttons.add(Button.secondary("gd:" + key, rules.genderLabel(key))));
        return buttons;
    }

    private void onGenderChoice(ButtonInteractionEvent event, String key) throws Exception {
        Creation c = creations.get(event.getUser().getId());
        if (c == null) {
            event.editMessage("세션이 만료됐다 — `/혼천 시작`으로 다시.").setComponents().queue();
            return;
        }
        if (!rules.genderOptions().containsKey(key)) {
            event.deferEdit().queue();   // 등록부에 없는 성별 — 세계에 존재하지 않는다
            return;
        }
        c.gender = key;
        event.editMessageEmbeds(questionEmbed(0))
                .setComponents(ActionRow.of(questionButtons(0))).queue();
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
        // 혈연/무관을 고른 다음에도 성별은 묻는다 — 전생이 있어도 이 몸은 새로 태어난다
        if (rules.genderAsk()) {
            event.editMessageEmbeds(genderEmbed())
                    .setComponents(ActionRow.of(genderButtons())).queue();
            return;
        }
        event.editMessageEmbeds(questionEmbed(0))
                .setComponents(ActionRow.of(questionButtons(0))).queue();
    }

    /** 시트 — 슬래시({@code /혼천 정보})와 안내판의 [내 시트] 가 둘 다 여기로 온다 (문은 하나다) */
    private void showSheet(IReplyCallback event) throws Exception {
        var found = db.findCharacter(event.getUser().getId());
        if (found.isEmpty()) {
            event.reply("캐릭터가 없다 — `/혼천 시작`으로 만들어라.").setEphemeral(true).queue();
            return;
        }
        Map<String, Object> ch = found.get();
        @SuppressWarnings("unchecked")
        Map<String, Object> sheet = (Map<String, Object>) ch.get("sheet");
        Object gender = sheet.get(rules.genderSheetKey());   // ★ 성별 (옛 캐릭터에는 없다 — 그러면 안 뜬다)
        EmbedBuilder eb = new EmbedBuilder().setColor(INK)
                .setTitle("정보 — " + ch.get("name"))
                .setDescription((gender == null ? ""
                        : rules.genderLabel(String.valueOf(gender)) + " · ")
                        + sheet.get("나이") + "세 " + sheet.get("연령대") + " · " + ch.get("realm")
                        + " · " + sheet.get("집안") + " · 성향 " + sheet.get("성향")
                        + "\n" + ch.get("status") + " · " + ch.get("location"));
        StringBuilder stats = new StringBuilder();
        @SuppressWarnings("unchecked")
        Map<String, Object> rawLedger = sheet.get("원장") instanceof Map
                ? (Map<String, Object>) sheet.get("원장") : null;
        if (rules.levelsEnabled && rawLedger != null) {
            // ★v3 — 시트는 **원장만** 표기 (§8.9 ⑪ 사용자 확정 · 판정치 병기 없음).
            //   성별 보정은 genderStat 을 지나야 든다 — 시트가 지나면 히든이 깨진다 (Rules 주석).
            for (String axis : GrowthV3.AXES) {
                if (rawLedger.get(axis) instanceof Number n) {
                    stats.append(axis).append(' ').append(ledgerLabel(n.doubleValue())).append("  ");
                }
            }
            eb.addField("원장", stats.toString().strip(), false);
            int level = Math.max(1, ((Number) sheet.getOrDefault("레벨", 1)).intValue());
            double xpCur = ((Number) sheet.getOrDefault("경험치", 0)).doubleValue();
            eb.addField("레벨", "Lv" + level + " · 경험 " + (int) xpCur + "/"
                    + (int) Math.ceil(GrowthV3.need(level, rules.xpBase, rules.xpGrowth)), true);
            int pts = ((Number) sheet.getOrDefault("미사용포인트", 0)).intValue();
            if (pts > 0) {
                eb.addField("미사용 포인트", "**" + pts + "** — 아래 [포인트 배분]", true);
            }
        } else {
            @SuppressWarnings("unchecked")
            Map<String, Integer> attr = (Map<String, Integer>) sheet.get("능력치");
            attr.forEach((k, v) -> stats.append(k).append(' ').append(v).append("  "));
            eb.addField("능력치", stats.toString(), false);
        }
        eb.addField("소지금", ch.get("wallet") + "문", true);
        eb.addField("발단", String.valueOf(sheet.get("발단")), true);

        // ★ B-109 — 심중(心中): 현재 열린 마디의 heart 한 줄. 목표 창이 아니라 속마음이다 —
        //   수치·단계("2/4") 표기는 등록부에도 코드에도 없다 (설계 §3 수치 은닉).
        //   시트를 여는 김에 tick 을 한 번 돈다 — 훅이 못 본 전이(favor 감쇠 회복 등)의 안전망(소급 인정).
        if (!story.disabled()) {
            try {
                long storyChId = ((Number) ch.get("id")).longValue();
                story.tick(db, factions, storyChId, db.worldDay(),
                        String.valueOf(ch.get("realm")), sheet);
                String heart = story.heart(db, storyChId, sheet);
                if (heart != null && !heart.isBlank()) {
                    eb.addField(story.sheetField(), "*" + heart + "*", false);
                }
            } catch (Exception e) {
                System.err.println("심중을 읽지 못했다: " + e.getMessage());
            }
        }

        // ═══ ★★ 가문과 형제 — **지금**의 것이다 (서장은 과거, 시트는 현재) ═══
        //
        // 【시간의 비대칭】 형의 서장은 동생이 나기 전에 쓰였다 — 그 글은 "나는 혼자였다"고 말한다.
        //   **그것은 거짓말이 아니다.** 그때는 정말 혼자였다.
        //   그러나 **지금** 그에게는 아우가 있다. 그 사실이 사는 곳이 **여기**다.
        try {
            long chId = ((Number) ch.get("id")).longValue();
            Long houseId = db.houseOfCharacter(chId);
            if (houseId != null) {
                var h = db.house(houseId);
                if (h.isPresent()) {
                    String st = h.get().state() == null ? "" : " · " + h.get().state();
                    Object rank = sheet.get(rules.birthRankSheetKey());
                    eb.addField("가문", h.get().name() + st
                            + (rank == null ? "" : " · " + rank), false);
                }
            }
            List<Map<String, Object>> kin = kinOf(chId);
            if (!kin.isEmpty()) {
                StringBuilder ks = new StringBuilder();
                for (Map<String, Object> k : kin) {
                    ks.append(k.get("title")).append(' ').append(k.get("name")).append("   ");
                }
                eb.addField("형제", ks.toString().strip(), false);
            }
        } catch (Exception e) {
            System.err.println("가문·형제를 읽지 못했다: " + e.getMessage());
        }
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
        List<FactionService.Standing> standings = factions.standings(chId, today);
        if (!standings.isEmpty()) {
            StringBuilder rel = new StringBuilder();
            for (FactionService.Standing s : standings) {
                rel.append(standingLine(s)).append('\n');
            }
            eb.addField("세력 관계", rel.toString(), false);
        }

        // ─── 단계 5 — 정치: 세계가 무엇으로 뭉치는가 / 세계가 나를 어떻게 보는가 ───
        int mandate = db.mandate(chId, today, rules.politics);
        if (mandate > 0) {
            boolean cut = disavowed(chId, today);
            eb.addField("⚖️ 법명분 (관)", "**" + mandate + "** — " + rules.politics.mandateEffect(mandate)
                            + "\n*" + rules.politics.observableMandate() + "*"
                            + (cut ? "\n\n🩸 **강호의 절연** — 어느 문파도 관 앞에서 너를 감싸지 않는다. "
                                    + "현상금 ×" + rules.politics.bountyMultiplier()
                                    + " · **입문 루트 전부 폐쇄 (마교 루트만 남는다)**"
                                    + "\n출구: 자수(" + rules.politics.mandateDrain("자수") + ") · 배상("
                                    + rules.politics.mandateDrain("배상") + ") · 진범 규명(전량)"
                                    : "\n*절연 문턱은 " + rules.politics.disavowalMandateMin()
                                            + " — 한 번 더 하면 끝이다*"),
                    false);
        }
        List<Issue> issues = politics(today);
        if (!issues.isEmpty()) {
            StringBuilder pol = new StringBuilder();
            for (Issue issue : issues) {
                pol.append(coalitionLine(issue)).append('\n');
            }
            eb.addField("🏛️ 명분과 연합 (강호의 판)", pol.toString(), false);
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
        var reply = event.replyEmbeds(eb.build()).setEphemeral(true);
        int unspent = ((Number) sheet.getOrDefault("미사용포인트", 0)).intValue();
        if (rules.levelsEnabled && unspent > 0) {
            reply = reply.addComponents(ActionRow.of(Button.primary(
                    "al:open:" + event.getUser().getId(), "포인트 배분 (" + unspent + ")")));
        }
        reply.queue();
    }

    /** 원장 표기 — 정수면 정수로, 화후 소수부가 남았으면 한 자리로 (%f 에 long 을 넣지 마라 — 기동 사고 전력) */
    private static String ledgerLabel(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.1f", v);
    }

    // ─── ★성장 v3 — 포인트 배분 손 (B-135 단계 4 · attribute_scale_v3 §8.5·§8.9 ⑨⑪) ───
    //
    //   레벨업(grantXp)이 쌓은 미사용 포인트가 여기서 원장으로 들어간다. 판정(genderStat)·
    //   파생(√원장)·마크 거울(mvtSheet attrs) 이 전부 원장을 읽으므로(단계 2·3), 이 한 손이
    //   곧 「찍으면 몸이 는다」의 전부다. 캡 c² 는 GrowthV3.allocate 가 지키고, 캡 표는
    //   cultivation.yml 이 정본이다 — 이 코드에 수치는 없다.

    @SuppressWarnings("unchecked")
    private void onAllocate(ButtonInteractionEvent event, String[] id) throws Exception {
        String ownerId = id[id.length - 1];
        if (!event.getUser().getId().equals(ownerId)) {
            event.reply("남의 시트다 — 네 몫은 안내판 [내 자리]에 있다.").setEphemeral(true).queue();
            return;
        }
        if ("done".equals(id[1])) {
            event.editMessage("배분을 마쳤다 — 시트는 안내판 [내 자리] 또는 `/혼천 정보`.")
                    .setEmbeds().setComponents().queue();
            return;
        }
        var found = db.findCharacter(ownerId);
        if (found.isEmpty()) {
            event.editMessage("캐릭터가 없다 — `/혼천 시작`으로 만들어라.")
                    .setEmbeds().setComponents().queue();
            return;
        }
        Map<String, Object> row = found.get();
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        String realm = String.valueOf(row.get("realm"));
        Integer cap = rules.rawCapByRealm.get(realm);
        if (cap == null || cap <= 0) {
            // 등록제 — 경지가 캡 표에 없으면 코드는 수치를 지어내지 않는다 (침묵 금지: 말하고 멈춘다)
            event.reply("이 경지(" + realm + ")의 원장 캡이 등록부에 없다 — 관리자에게 알려라.")
                    .setEphemeral(true).queue();
            return;
        }
        String notice = null;
        if ("ax".equals(id[1])) {
            String axis = GrowthV3.AXES.get(Integer.parseInt(id[2]));
            switch (GrowthV3.allocate(sheet, axis, cap)) {
                case OK -> {
                    db.updateCharacter(((Number) row.get("id")).longValue(), sheet,
                            ((Number) row.get("wallet")).intValue(), realm,
                            String.valueOf(row.get("status")), String.valueOf(row.get("location")));
                    double now = ((Number) ((Map<String, Object>) sheet.get("원장")).get(axis)).doubleValue();
                    notice = "**" + axis + " +1** — 원장 " + ledgerLabel(now);
                }
                case CAP -> notice = "캡이 막았다 — " + realm + "의 원장 캡은 **" + cap
                        + "**. 포인트는 은행에 남는다 (승급이 문이다).";
                case NO_POINTS -> notice = "미사용 포인트가 없다.";
            }
        }
        event.editMessageEmbeds(allocEmbed(sheet, realm, cap, notice))
                .setComponents(allocRows(sheet, ownerId, cap)).queue();
    }

    @SuppressWarnings("unchecked")
    private MessageEmbed allocEmbed(Map<String, Object> sheet, String realm, int cap, String notice) {
        int points = ((Number) sheet.getOrDefault("미사용포인트", 0)).intValue();
        Map<String, Object> raw = sheet.get("원장") instanceof Map
                ? (Map<String, Object>) sheet.get("원장") : Map.of();
        StringBuilder axes = new StringBuilder();
        for (String axis : GrowthV3.AXES) {
            double v = raw.get(axis) instanceof Number n ? n.doubleValue() : 0.0;
            axes.append(axis).append(' ').append(ledgerLabel(v))
                    .append(v + 1.0 > cap + 1e-9 ? " (캡)" : "").append("  ");
        }
        return new EmbedBuilder().setColor(INK).setTitle("포인트 배분")
                .setDescription("미사용 포인트 **" + points + "** · " + realm + " (원장 캡 " + cap + ")\n"
                        + axes.toString().strip()
                        + (notice == null ? "" : "\n\n" + notice))
                .build();
    }

    /** 축 7버튼 (4+3) + [그만] — 캡에 닿았거나 포인트가 없으면 눌리지 않는다 (이유는 embed 가 말한다) */
    @SuppressWarnings("unchecked")
    private List<ActionRow> allocRows(Map<String, Object> sheet, String ownerId, int cap) {
        int points = ((Number) sheet.getOrDefault("미사용포인트", 0)).intValue();
        Map<String, Object> raw = sheet.get("원장") instanceof Map
                ? (Map<String, Object>) sheet.get("원장") : Map.of();
        List<Button> buttons = new ArrayList<>();
        for (int i = 0; i < GrowthV3.AXES.size(); i++) {
            String axis = GrowthV3.AXES.get(i);
            double v = raw.get(axis) instanceof Number n ? n.doubleValue() : 0.0;
            boolean blocked = points <= 0 || v + 1.0 > cap + 1e-9;
            buttons.add(Button.primary("al:ax:" + i + ":" + ownerId, axis + " +1")
                    .withDisabled(blocked));
        }
        return List.of(ActionRow.of(buttons.subList(0, 4)), ActionRow.of(buttons.subList(4, 7)),
                ActionRow.of(Button.secondary("al:done:" + ownerId, "그만")));
    }

    private MessageEmbed help() {
        return new EmbedBuilder().setColor(INK).setTitle("혼천 — 무협 텍스트 RPG")
                // ★ 주된 길은 **버튼**이다. 아래 명령들은 **뒷문**이다 (버튼이 안 뜨거나 모바일에서 막힐 때).
                //   지우지 않는 이유가 그것이다 — 그러나 처음 온 사람이 외울 것은 안내판 하나다.
                .setDescription("**▸ 명령을 칠 일이 없다 — 채널의 안내판에서 [내 자리] 를 눌러라.**\n"
                        + "*(안내판이 안 보이면 관리자에게 `/안내판` 을 청하라. 아래는 **뒷문**이다)*\n\n"
                        + "`/혼천 시작` 캐릭터 생성 (유년의 기억 5문항 → 운명이 나머지를 정한다)\n"
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
                        + "`/혼천 출행 관아` 청하현 관아 — 포쾌로 써 달라고 한다. **관은 그냥 적는다** "
                        + "(문턱이 가장 낮은 문. 대가는 강호가 청구한다)\n"
                        + "`/혼천 소문` 저잣거리에 도는 말 — 퍼질수록 이야기가 달라진다\n"
                        + "`/혼천 의방` 부상을 다스리고 외상을 갚는다 (유문의 의방)\n"
                        + "`/혼천 구조 @상대` 빈사의 동행을 지혈한다 — 의술 판정 (사람을 살리는 유일한 손)\n"
                        + "`/혼천 전장 [예치] [인출] [상속인]` 금서방의 전장 — 죽어서 남길 수 있는 유일한 재산\n"
                        // ★ 2026-07-14 고침: 여기에 `/혼천 접합문` 이라 적혀 있었다 — **없는 명령이다.**
                        //   접합문은 25칸 상한 때문에 **최상위**로 빠졌는데(HoncheonBot:76) 도움말만 옛말이었다.
                        //   도움말이 없는 명령을 가리키는 것은 죽은 버튼과 같은 병이다.
                        + "`/안내판` 이 채널에 **안내판**을 세운다 — **명령을 안 쳐도 되게** 한다 (서버 관리자)\n"
                        + "`/접합문` 이 채널에 **접속의 문**을 세운다 — 마크의 [혼천 접속] 클릭이 여기로 온다 "
                        + "(서버 관리자)\n"
                        + "`/혼천 지역등록` 이 채널을 청하현으로 등록 (서버 관리자)\n"
                        + "`/혼천 정산` 세계일 +1 (서버 관리자 — 자정에는 자동)\n"
                        + "`/혼천 사망 <NPC> [살해자]` NPC를 죽인다 — 연쇄 검증용 (서버 관리자)\n"
                        + "`/혼천 사선 @상대` 플레이어에게 사선을 긋는다 — 죽음 검증용 (서버 관리자)\n"
                        + "`/혼천 명분 <사건>` 명분을 쌓는다 — 연합·절연 검증용 (서버 관리자)\n"
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

    /**
     * 봇이 떴다 — <b>접속의 문이 어디에 서 있는지 확인한다.</b>
     *
     * <p>길드 id 를 config 에 적지 않는 이유: <b>코드도 config 도 채널 id 를 지어내면 안 된다.</b>
     * 봇은 제가 붙어 있는 길드를 <b>실제로 안다</b> — 그래서 여기서 채널을 되찾아 길드를 적어 둔다.
     * 그 둘로 {@link Bridge#publish} 가 마크에 내려보낼 URL 을 만든다 (스냅숏 {@code discord}).
     *
     * <p>문(/혼천 접합문)이 아직 안 섰으면 지역 채널(/혼천 지역등록)로 떨어진다 — 거기라도 데려다 놓는 것이
     * 아무 데도 못 가는 것보다 낫다. 둘 다 없으면 마크는 <b>[코드 복사]만</b> 띄운다 (없는 URL 을 짓지 않는다).
     */
    @Override
    public void onReady(net.dv8tion.jda.api.events.session.ReadyEvent event) {
        // ★ 수락은 **몇 초 뒤 다른 스레드**로 온다 (다리 폴러). 그때 청한 사람에게 말을 걸려면
        //   JDA 를 쥐고 있어야 한다 (인터랙션 훅이 죽었으면 DM 으로 간다 — GameListener.dm)
        this.jda = event.getJDA();
        try {
            String chKey = rules.gateMetaKey("channel_meta", GATE_CHANNEL_KEY);
            String channelId = db.getMeta(chKey).or(() -> {
                try {
                    return db.getMeta(REGION_KEY);
                } catch (Exception e) {
                    return java.util.Optional.empty();
                }
            }).orElse(null);
            if (channelId == null) {
                System.out.println("접속의 문 — 아직 안 섰다 (/접합문). 사람은 /혼천 접속 닉네임:… 으로도 청할 수 있다");
                return;
            }
            var channel = event.getJDA().getTextChannelById(channelId);
            if (channel == null) {
                System.err.println("접속의 문 — 채널을 찾을 수 없다: " + channelId);
                return;
            }
            db.setMeta(chKey, channelId);
            db.setMeta(rules.gateMetaKey("guild_meta", GATE_GUILD_KEY), channel.getGuild().getId());
            System.out.println("접속의 문 — " + channel.getGuild().getName() + " #" + channel.getName()
                    + " (마크의 [혼천 접속] 클릭이 여기로 온다)");
            warnIfNoInvite();
        } catch (Exception e) {
            System.err.println("접속의 문 확인 실패: " + e.getMessage());
        }
    }

    /**
     * <b>★ 초대 링크가 비었으면 소리내어 알린다</b> — 이 디스코드는 <b>공개가 아니다.</b>
     *
     * <p>이제 마크가 채팅에 거는 것은 <b>초대 링크 하나뿐</b>이다 (코드도, 채널 URL 도 걸지 않는다).
     * 그러므로 이 칸이 비면 <b>마크에서 디스코드로 가는 길이 아예 없다</b> — 문이 없는 담이다.
     * 그 담을 여는 것은 <b>관리자</b>뿐이다 (실제 링크는 저장소에 커밋되지 않는다).
     */
    private void warnIfNoInvite() {
        if (rules.gateInviteUrl() != null) {
            System.out.println("초대 링크 — 등록됨 (마크의 /혼천 접속 이 [초대 링크] 를 띄운다)");
            return;
        }
        System.err.println("""

                ════════════════════════════════════════════════════════════════
                ★ 초대 링크가 비어 있다 — 마크에서 디스코드로 가는 길이 **아예 없다.**
                  이 디스코드는 공개가 아니다. 그리고 이제 마크가 거는 것은 초대 링크 하나뿐이다.

                  넣는 곳:  config/world_bridge.yml  →  identity.gate.invite_url
                  만드는 법: 디스코드 접속 채널 우클릭 → 「초대 링크 만들기」
                            → 만료 기한 **없음** · 최대 사용 횟수 **무제한** 으로 고칠 것
                            → 나온 https://discord.gg/XXXXXX 를 그 칸에 붙여넣고 봇을 다시 띄운다

                  (비어 있는 동안 마크는 [초대 링크] 버튼을 띄우지 않는다 — 없는 문을 걸지 않는다)
                ════════════════════════════════════════════════════════════════
                """);
    }

    // ─── 버튼 라우팅 ───

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        try {
            String[] id = event.getComponentId().split(":");
            switch (id[0]) {
                case "ct" -> onTestAnswer(event, Integer.parseInt(id[1]), id[2]);
                // ★ "tn" (서장 턴 버튼) 은 **없앴다** — 서장은 이제 강호의 책에서 흐른다.
                //   두 벌을 남기지 않는다: 옛 버튼이 살아 있으면 디스코드로도 진행할 수 있고,
                //   그러면 **정본이 둘**이 된다 (그리고 하나가 낡는다).
                case "ht" -> onHuntChoice(event, Integer.parseInt(id[1]), Integer.parseInt(id[2]), id[3]);
                case "bm" -> onDuelAnswer(event, "ok".equals(id[1]), id[2], id[3]);
                case "qa" -> onQuestAccept(event, id[1], id[2]);
                case "qp" -> onQuestPerform(event, id[1], Integer.parseInt(id[2]), id[3]);
                case "gs" -> onMealChoice(event, "share".equals(id[1]), id[2]);
                case "ex" -> onGateChoice(event, id[1], Integer.parseInt(id[2]), id[3]);
                case "gw" -> onGwanaChoice(event, id[1], id[2]);            // ★ 관아 — 9번째 루트
                case "gd" -> onGenderChoice(event, id[1]);                  // ★ 성별 — 생성의 첫 물음
                case "hs" -> onHouseChoice(event, "stay".equals(id[1]));    // ★ 세가 — 남는가, 나오는가
                case "ln" -> onLineageChoice(event, "kin".equals(id[1]));   // 새 삶 — 혈연 / 무관
                case "lk" -> openLinkModal(event);   // ★ 접속의 문 — 코드 창을 연다 (확정은 모달에서)
                case "al" -> onAllocate(event, id);  // ★ 포인트 배분 — v3 성장의 손 (B-135 단계 4)
                case "np" -> onPanel(event, id);     // ★★ 안내판 — 명령을 치지 않게 하는 판
                case "rs" -> onResetConfirm(event, id[1], id[2], id[3]);   // 되돌린다 — 확인의 손
                case "rx" -> event.editMessage("초기화를 그만두었다. **아무것도 지우지 않았다.**")
                        .setComponents().queue();
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
        // ═══ 문답 종료 — ★ **이제 답이 집안을 정한다** (옛 길: 전체 집안에서 통째로 주사위) ═══
        //
        //   결(結)  = 테스트   — 어느 갈래인가 (내 선택이 세계에 자국을 남긴다)
        //   무늬    = 주사위   — 그 갈래 안에서 누구인가 (같은 답이 같은 사람이 되지 않는다)
        c.family = rollFamily(c);

        // ★ 세가가 걸렸다 — **거절할 수 있다** (사용자: "세가가 걸렸을 경우 ... 거절할 수도 있다")
        if (rules.canRefuseHouse(c.family)) {
            event.editMessageEmbeds(houseEmbed(c.family))
                    .setComponents(ActionRow.of(
                            Button.primary("hs:stay", rules.refuseText("accept_label", "가문의 아이로 남는다")),
                            Button.secondary("hs:leave", rules.refuseText("refuse_label", "집을 나온다"))))
                    .queue();
            return;
        }
        finishCreation(event, c);
    }

    /**
     * ★★ <b>결과 무늬</b> — 유년의 기억이 <b>갈래</b>를 좁히고, 주사위가 그 안에서 <b>한 집</b>을 고른다.
     *
     * <p><b>옛 길의 병:</b> {@code familyKeys.get(dice.nextInt(familyKeys.size()))} — 집안을 <b>전체에서</b>
     * 뽑았다. 그래서 장터에서 뛰어들어 막아선 아이와 문틈에 귀를 댄 아이가 <b>같은 확률로 같은 집</b>에서
     * 태어났다. 아홉 문항이 집안에 아무 자국도 남기지 않았다.
     *
     * <p><b>그러나 주사위를 없애지도 않는다</b> (사용자: "같은 선택지에선 주사위로 특색을 부과한다") —
     * 테스트만이면 <b>같은 답 = 같은 캐릭터</b>가 되고, 최적해가 발견되는 순간 모두가 같아진다.
     *
     * <p>동점(아직 정해지지 않은 아이)이면 <b>후보를 합친다</b> — 좁히지 않는다
     * (disposition_test.yml scoring.wide_tie 의 정신 그대로).
     */
    private String rollFamily(Creation c) {
        // 혈연 시작은 집안을 고르지 않는다 — 몰락한 무가의 자식 문법이 곧 '물려받은 것'의 문법이다
        if (c.lineage) {
            return rules.legacy.lineageFamilyTemplate();
        }
        int max = c.scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> pool = new ArrayList<>();
        for (Map.Entry<String, Integer> e : c.scores.entrySet()) {
            if (e.getValue() == max) {
                for (String f : rules.familyCandidates(e.getKey())) {
                    if (!pool.contains(f)) {
                        pool.add(f);   // ★ 동점이면 합집합 (on_tie: 후보_합집합)
                    }
                }
            }
        }
        if (pool.isEmpty()) {
            // 등록부에 그 성향의 후보가 없다 — ★ 코드가 짝을 지어내지 않는다. 운명에 맡긴다
            System.err.println("생성 — family_affinity 에 후보가 없다 (등록부를 보라). 전체에서 뽑는다");
            List<String> all = new ArrayList<>(rules.families().keySet());
            return all.get(dice.nextInt(all.size()));
        }
        return pool.get(dice.nextInt(pool.size()));   // ★ 무늬 — 갈래 **안에서만** 구른다
    }

    /** 세가의 문 — 남을 것인가, 나올 것인가 (문장은 등록부의 것이다) */
    private MessageEmbed houseEmbed(String family) {
        return new EmbedBuilder().setColor(BLOOD)
                .setTitle("가문 — " + family.replace('_', ' '))
                .setDescription("**" + rules.refuseText("prompt",
                        "가문이 너를 부른다 — 검을 쥐여 주고, 이름을 지우지 말라 한다.") + "**\n\n"
                        + "**남는다** — " + rules.refuseText("accept_note", "검법과 예법, 월례 전표.") + "\n"
                        + "**나온다** — " + rules.refuseText("refuse_note", "남는 것은 이름 하나."))
                .build();
    }

    /** 세가의 문 앞에서 답했다 — 집을 나오면 **몰락무가 루트에 합류한다** (등록부: 절연 = 그 길) */
    private void onHouseChoice(ButtonInteractionEvent event, boolean stay) throws Exception {
        Creation c = creations.get(event.getUser().getId());
        if (c == null) {
            event.editMessage("세션이 만료됐다 — `/혼천 시작`으로 다시.").setComponents().queue();
            return;
        }
        if (!stay) {
            c.family = rules.refusedFamily();
            c.leftHouse = true;
        }
        finishCreation(event, c);
    }

    /** 태어난다 — 그리고 디스코드는 **이정표만 세우고 입을 다문다** */
    private void finishCreation(ButtonInteractionEvent event, Creation c) throws Exception {
        creations.remove(event.getUser().getId());
        Born born = birth(event.getUser().getId(), event.getUser().getEffectiveName(), c);
        // ★★ 스레드를 열지 않는다. **이야기는 강호에서 흐른다**
        //   (사용자: "디스코드에서 채팅을 치고 진행하는 것이 아닌 마크로 넘어가는 걸로 표현하자")
        // ★ B-117 — 이정표가 "명령을 쳐라"로 끝나면 안 된다 (사용자 실측: "접속이 명령어 타이핑이다").
        //   [마크와 잇기] 버튼이 함께 선다 — 접합문의 버튼과 **같은 문**(lk:open → 모달 → askLink).
        event.editMessageEmbeds(birthEmbed(born), signpostEmbed(born))
                .setComponents(ActionRow.of(signpostLinkButton(rules))).queue();
        startSeojang(born);   // ★ 그리고 **지금부터 서장을 미리 쓴다** (사람이 마크로 걸어오는 동안)
    }

    /**
     * ★ 이정표 — <b>디스코드가 마지막으로 하는 말.</b> "여기가 아니라 강호에서 이어진다."
     *
     * <p>스레드를 지우지 않고 <b>애초에 열지 않는다.</b> 지울 것이 없어야 낡을 것도 없다.
     * 문장은 {@code seojang.yml signpost} 가 정한다 — 코드가 짓지 않는다.
     */
    private MessageEmbed signpostEmbed(Born born) {
        return new EmbedBuilder().setColor(INK)
                .setTitle(rules.seojang.signpost("title", "태어났다 — 그러나 이야기는 여기서 흐르지 않는다"))
                .setDescription(rules.seojang.signpost("body", "서장은 강호에서 열린다.")
                        .replace("{name}", born.ch().name()))
                .setFooter(rules.seojang.signpost("footer", "디스코드는 이름을 지키고, 이야기는 몸이 겪는다."))
                .build();
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

        // ★★ 집안은 **이미 정해져 왔다** — 유년의 기억이 갈래를 좁혔고(결), 주사위가 그 안에서 골랐다(무늬).
        //   {@link #rollFamily} 를 보라. 여기서 **다시 뽑지 않는다** (세가 거절도 이미 반영돼 있다).
        List<String> familyKeys = new ArrayList<>(rules.families().keySet());
        String family = c.family;
        if (family == null || !familyKeys.contains(family)) {
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
        // ★ 성별 — 시트의 첫 칸 (DB 스키마는 건드리지 않았다: sheet_json 은 원래 자유 서식이다.
        //   마이그레이션도 봇 정지도 필요 없었다). 등록부가 ask:false 면 아예 적히지 않는다.
        if (c.gender != null) {
            sheet.put(rules.genderSheetKey(), c.gender);
        }
        sheet.put("성향", disposition);
        sheet.put("집안", family.replace('_', ' '));
        // ★★ 적서(嫡庶) — **무늬**다. 결(집안)은 심리 테스트가 정했고, 이 칸은 **주사위**가 정한다.
        //   같은 집에 태어났는데 **세상이 아는 무게가 다르다** (적자 5 = 천하 · 서자 3 = 인접 현).
        //   ★ 탄생에 한 번 구르고 시트에 **박힌다** — 재굴림 없다 (미리 쓰기와 부딪치지 않는다).
        //   ★ 능력치는 주지 않는다 (birth_rank.grants_attributes: false — 집안 헌법과 같다).
        String rank = rules.hasBirthRank(family) ? rules.rollBirthRank(dice) : null;
        if (rank != null) {
            sheet.put(rules.birthRankSheetKey(), rank);
        }
        // ★ 집을 나온 아이 — 세가를 거절했다. 검도 전표도 없고, 남은 것은 이름 하나다.
        //   (등록부: refuse_house — "절연 = 사실상 몰락_무가 루트 합류. 흔적 '이름'만 남는다")
        if (c.leftHouse) {
            sheet.put("집안_이탈", true);
        }
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
        // ★★ 가문 — **한 채의 집**에 앉힌다 (주사위: 기존 집인가 새 집인가)
        assignHouse(id, family);
        // ★★ **시간의 비대칭** — 이 아이가 태어난 **그 순간**의 형제를 시트에 박제한다.
        //   형의 서장은 동생이 나기 전에 쓰였다. 그때 형은 **정말로 혼자였다.**
        //   그 글을 소급해서 고치지 않는다 — 대신 **형에게 소식을 보낸다** (아래).
        snapshotKinAtBirth(id, sheet);
        db.updateCharacter(id, sheet, wallet, "범인", "서장", "서장");

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
        // ★★ 탄생은 **세계의 사건**이다 (사용자: "플레이어가 소환되는 것도 사건화. 세상이 누가
        //   태어났는지 알아야 하며"). 장부에 남고 — **마을이 안다** (아래 소문).
        Map<String, Object> bornLog = new LinkedHashMap<>(Map.of(
                "성향", disposition, "집안", family, "발단", incident, "나이", age,
                "혈연", c.lineage, "성별", c.gender == null ? "미상" : c.gender,
                "집안_이탈", c.leftHouse));
        if (rank != null) {
            bornLog.put("적서", rank);
        }
        db.logEvent("탄생", "character", String.valueOf(id), bornLog);
        birthRumor(id, name, family, rank);
        tellElders(id, name);   // ★ 아우가 났다 — **형은 언제나 안다** (소문의 범위와 무관하다)
        return new Born(new Character(id, name, disposition, family, incident, bracket, age, attrs, wallet), sheet);
    }

    /**
     * ★★ <b>마을이 안다 — 누가 태어났는지.</b> (사용자: "세상이 누가 태어났는지 알아야 하며")
     *
     * <p><b>얼마나 멀리 퍼지는가</b> (사용자 2026-07-14):
     * <i>"<b>세가를 제외하곤 지역까지만 퍼짐 (해당 마을)</b>"</i>
     *
     * <p><b>★ 범위의 어휘를 지어내지 않았다</b> — {@code rumor.yml propagation.reach_by_intensity}
     * 가 이미 갖고 있었다: <b>강도 2 = "현 내 관심 일치 망 전체"</b> — 사용자의 "해당 마을"이
     * 정확히 이것이다. 세가만 더 멀리 간다 ({@code birth_rumor.house_intensity} —
     * <b>그 값은 담당자의 제안이고 승인 대기다</b>).
     *
     * <p><b>소문이 아니면 NPC 도 모른다</b> — 장부(events.탄생)는 <b>봇의 기억</b>일 뿐이고,
     * NPC 가 "자네가 그 무가의 아이인가" 라고 말하려면 <b>소문망에 실려야</b> 한다.
     */
    private void birthRumor(long id, String name, String family, String rank) {
        try {
            Map<String, Object> cfg = rules.birthRumor();
            if (!Boolean.TRUE.equals(cfg.get("enabled"))) {
                return;
            }
            // ★ 적서가 무게를 가른다 (사용자 확정): 적자 5 = 「천하」 · 서자 3 = 「현 + 인접 현」
            //   적서가 없는 집이면 default_intensity 2 = 「현 내」 (해당 마을)
            int intensity = rules.birthRumorIntensity(family, rank);
            if (intensity <= 0) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<String> tags = cfg.get("tags") instanceof List<?> l
                    ? l.stream().map(String::valueOf).toList() : List.of("생활");
            String house = family.replace('_', ' ');
            // ★ 문장은 사실만 — 소문이 왜곡하는 것은 소문망이 한다 (accuracy_bands).
            //   적서가 있으면 **그것이 소문의 핵심**이다 (사용자: "어느 세가의 둘째아들이 태어났다")
            String truth = rank == null
                    ? house + "에 아이가 하나 났다 — " + name + "이라 한다."
                    : house + "에 " + rules.birthRankOption(rank).getOrDefault("label", rank)
                    + "가 났다 — " + name + "이라 한다.";
            int accuracy = rules.initialAccuracy(String.valueOf(
                    cfg.getOrDefault("accuracy_kind", "직접_목격")));
            // 발원 망 — 아이가 난 것은 **장터가 먼저 안다** (rumor.yml origin_network_by_location)
            spread(birthGroup(id), truth, name, id, tags, intensity, accuracy,
                    rules.originNetwork("market"), db.worldDay());
        } catch (Exception e) {
            // ★ 소문을 못 심어도 아이는 태어난다 — 생성이 소문 때문에 죽으면 안 된다
            System.err.println("탄생 소문을 심지 못했다 (아이는 태어났다): " + e.getMessage());
        }
    }

    /** 탄생 소문의 군(群) 키 — 심는 쪽(birthRumor)과 읽는 쪽(houseNews, B-073)이 같은 열쇠를 쥔다 */
    private static String birthGroup(long characterId) {
        return "탄생:" + characterId;
    }

    /**
     * ★★ <b>가문 배정 — 기존 집에 태어나는가, 새 집이 서는가</b> (사용자 확정: <b>(다) 주사위</b>).
     *
     * <p><b>★ 이것이 형제의 존재 조건이다.</b> 아무도 기존 집에 안 들어가면 형제는 영원히 안 생긴다.
     * 그래서 이 함수의 수는 <b>형제가 실제로 몇 %나 생기는지</b>로 검산한다
     * ({@code tools/house_audit.py} — 몬테카를로).
     *
     * <p><b>★ 대가족은 「자식 수 상한」이 막는다 — 그리고 그 상한이 곧 새 집의 방아쇠다:</b>
     * <ul>
     *   <li>빈자리가 0 인 집은 <b>후보에서 빠진다</b> (스무 명이 한 집에 몰리지 않는다)</li>
     *   <li>남은 집 중에서는 <b>빈자리가 많을수록</b> 잘 뽑힌다 (한 집만 꽉 차는 것을 막는다)</li>
     *   <li>후보가 <b>하나도 없으면 새 집이 선다</b> — 상한 하나가 두 일을 한다</li>
     * </ul>
     *
     * <p><b>★ 지역</b>은 <b>사람이 실제로 설 수 있는 고을</b>에서만 뽑는다 (mvt_start.playable).
     * 블록도 앵커도 없는 고을에 집을 두면 <b>갈 수 없는 집</b>이 되고 사람은 허공에 떨어진다.
     */
    private void assignHouse(long characterId, String family) {
        if (!rules.houseSystemEnabled()) {
            return;
        }
        try {
            // ═══ ★★ 집을 나온 아이는 **자기가 태어난 집**에 앉는다 ═══
            //
            // 【담당자가 만들 뻔한 병】 세가를 거절하면 집안이 `가출한_무가의_자식` 이 된다.
            //   그 키로 집을 찾으면 **「가출한 무가」라는 새 집**이 서고 —
            //   **제 형과 남남이 된다.** 그것은 사용자가 확정한 것과 정반대다:
            //   *"절연은 관계를 끊는 것이 아니라 **관계를 무겁게** 만든다. 호적에서 지워도 **형은 형이다.**"*
            //
            // 【고침】 그 아이는 **무가의_자식의 집에서 태어났다.** 나온 것은 그 **뒤**의 일이다.
            //   등록부가 어느 집을 나왔는지 알고 있다 (families.가출한_무가의_자식.from: 무가의_자식).
            //   ★ 그래서 **집을 찾을 때만** 원래 집안을 쓴다 (시트의 집안·성향·발단은 그대로 '가출한' 이다).
            String houseFamily = rules.birthFamilyOf(family);

            int cap = rules.houseAssign("children_cap", 4);
            int joinPct = rules.houseAssign("join_existing", 60);
            int today = db.worldDay();

            // ★ 빈자리가 있는 집만 후보다 (상한이 곧 새 집의 방아쇠)
            List<HouseEntry> room = new ArrayList<>();
            for (HouseEntry h : db.housesOf(houseFamily)) {
                if (h.born() < cap) {
                    room.add(h);
                }
            }
            Long houseId = null;
            if (!room.isEmpty() && dice.nextInt(100) < joinPct) {
                // 빈자리가 많은 집일수록 잘 뽑힌다 (가중 추첨)
                int total = 0;
                for (HouseEntry h : room) {
                    total += cap - h.born();
                }
                int roll = dice.nextInt(total);
                for (HouseEntry h : room) {
                    roll -= cap - h.born();
                    if (roll < 0) {
                        houseId = h.id();
                        break;
                    }
                }
            }
            if (houseId == null) {
                // ★ 새 집이 선다 — 세계에 없던 가문 하나가 생긴다
                List<String> regions = rules.playableRegions();
                String region = regions.get(dice.nextInt(regions.size()));
                houseId = db.createHouse(houseFamily,
                        houseName(houseFamily, region), region,
                        rules.houseState(houseFamily, dice), today);
                db.logEvent("가문_창건", "house", String.valueOf(houseId),
                        Map.of("집안", houseFamily, "지역", region));
            }
            if (houseId != null && houseId > 0) {
                db.setHouse(characterId, houseId);
            }
        } catch (Exception e) {
            // ★ 집을 못 지어도 아이는 태어난다 (생성이 가문 때문에 죽으면 안 된다)
            System.err.println("가문을 세우지 못했다 (아이는 태어났다): " + e.getMessage());
        }
    }

    /**
     * 집의 이름 — ★ <b>성(姓)은 무가 계열에만 붙는다.</b>
     *
     * <p>이 세계의 등록 NPC 는 <b>32명 전원 성이 없고</b>(한백·묵삼·곽진 — 두 자 이름),
     * <b>세가만 성으로 불린다</b>(남궁세가·팽가·당가). 즉 <b>성을 가진다는 것이 곧 가문을 가진다는 뜻</b>이다.
     * 그러므로 농가의 집은 「청하현 농가의 자식」 이지 「이(李)씨 농가」가 아니다.
     */
    private String houseName(String family, String region) {
        String regionName = rules.regionName(region);
        if (!rules.isMartialHouse(family)) {
            return rules.houseNameFormat(false)
                    .replace("{region}", regionName)
                    .replace("{family}", family.replace('_', ' '));
        }
        String surname = rules.rollSurname(dice);
        if (surname == null) {
            return regionName + " " + family.replace('_', ' ');
        }
        return rules.houseNameFormat(true)
                .replace("{region}", regionName)
                .replace("{surname}", surname);
    }

    /**
     * ★★ <b>탄생 순간의 형제를 시트에 박제한다</b> ({@code 서장_형제}).
     *
     * <p><b>이것이 시간의 비대칭을 푸는 못이다.</b> 사용자가 짚었다:
     * <i>"형이 먼저 태어남 (동생이 있는지는 모름), 동생이 태어남 (형이 있는 줄 앎)."</i>
     *
     * <p>서장은 <b>미리 쓴다.</b> 형의 1장은 탄생에 굳는다 — 그때는 <b>정말로 혼자였다.</b>
     * 그런데 2장·3장은 <b>나중에</b> 그려진다. 그때 형제를 <b>산 채로</b> 읽으면
     * <b>없던 동생이 2장에서 튀어나온다</b> — 1장과 어긋난다.
     *
     * <p>그래서 <b>탄생의 형제를 못 박는다.</b> 서장의 모든 장면이 이 스냅숏만 읽는다:
     * <ul>
     *   <li><b>형</b> — 빈 목록. 그의 서장은 <b>영원히</b> "나는 혼자였다"고 말한다. <b>거짓말이 아니다</b></li>
     *   <li><b>동생</b> — 형이 든다. 그의 서장은 형이 있는 세계에서 쓰였다</li>
     * </ul>
     *
     * <p><b>★ 지금의 형제는 다른 곳에 산다</b> — {@code mvtSheet.kin} 과 {@code /혼천 정보} 는
     * <b>산 것</b>을 읽는다 (houseMembers). <b>서장은 과거고, 시트는 현재다.</b>
     */
    private void snapshotKinAtBirth(long characterId, Map<String, Object> sheet) {
        try {
            List<Map<String, Object>> kin = kinOf(characterId);
            List<String> frozen = new ArrayList<>();
            for (Map<String, Object> k : kin) {
                frozen.add(k.get("title") + " " + k.get("name"));
            }
            // ★ 빈 목록도 **적는다** — "형제가 없었다" 는 것도 사실이다 (없는 칸과 다르다)
            sheet.put(Seojang.SHEET_KIN, frozen);
        } catch (Exception e) {
            System.err.println("탄생의 형제를 박제하지 못했다: " + e.getMessage());
        }
    }

    /**
     * ★★ <b>아우가 났다 — 형에게 소식이 간다.</b>
     *
     * <p><b>형은 「어느 날 형이 되는 것」을 겪는다.</b> 처음부터 형제였던 것이 아니다.
     *
     * <p><b>★ 소문의 범위와 무관하다</b> ({@code birth_rumor.kin_always_know}).
     * 세상은 강도만큼만 안다 (서자면 인접 현까지). 그러나 <b>형은 언제나 안다</b> —
     * 소문의 문제가 아니라 <b>제 집의 일</b>이다.
     *
     * <p>어디로 가는가: <b>디스코드 DM</b> (등록부가 정한다). 형은 <b>접속해 있지 않을 수 있고</b>,
     * DM 은 언제 봐도 거기 있다. ★ 게임 안 통로는 <b>아직 없다</b> (B-091).
     */
    private void tellElders(long newbornId, String newbornName) {
        try {
            Map<String, Object> news = rules.siblingNews();
            if (!Boolean.TRUE.equals(news.get("enabled"))) {
                return;
            }
            Long houseId = db.houseOfCharacter(newbornId);
            if (houseId == null) {
                return;
            }
            String houseName = db.house(houseId).map(HouseEntry::name).orElse("그 집");
            Object gRaw = db.findCharacterById(newbornId)
                    .map(r -> ((Map<?, ?>) r.get("sheet")).get(rules.genderSheetKey()))
                    .orElse(null);
            String newbornGender = gRaw == null ? null : String.valueOf(gRaw);

            for (Map<String, Object> elder : db.houseMembers(houseId)) {
                long elderId = ((Number) elder.get("id")).longValue();
                if (elderId == newbornId) {
                    continue;   // 나 자신에게는 안 보낸다
                }
                // ★ 손위만? — 아니다. **집의 모든 식구**가 안다 (누나도 아우가 난 것을 안다).
                //   호칭은 **불리는 자(갓난아이)의 성별**로 정해진다 — 아우/누이
                String title = rules.kinTitle(false, newbornGender);
                var row = db.findCharacterById(elderId);
                if (row.isEmpty()) {
                    continue;
                }
                String discordId = String.valueOf(row.get().get("discord_id"));
                db.logEvent(String.valueOf(news.getOrDefault("ledger", "형제_탄생")),
                        "character", String.valueOf(elderId), "character", String.valueOf(newbornId),
                        Map.of("아이", newbornName, "호칭", title, "집", houseName));
                if (discordId == null || discordId.isBlank() || "null".equals(discordId)) {
                    continue;
                }
                String body = String.valueOf(news.getOrDefault("body", "{house}에 아이가 하나 더 났다."))
                        .replace("{house}", houseName)
                        .replace("{name}", newbornName)
                        .replace("{title}", title);
                dm(discordId, null, new EmbedBuilder().setColor(BLOOD)
                        .setTitle(String.valueOf(news.getOrDefault("title", "네 아우가 났다")))
                        .setDescription(body)
                        // ★ 형의 서장은 그대로다 — 그 사실을 **말해 준다** (거짓말이 아니었다)
                        .setFooter(String.valueOf(news.getOrDefault("footer",
                                "네 서장은 그때의 것이다 — 그때는 정말 혼자였다.")))
                        .build());
            }
        } catch (Exception e) {
            System.err.println("형에게 소식을 전하지 못했다: " + e.getMessage());
        }
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

    private MessageEmbed birthEmbed(Born born) {
        Character ch = born.ch();
        // ★ 성별은 시트에서 읽는다 (Character 레코드가 아니라 — 시트가 사실의 원본이다)
        Object gender = born.sheet().get(rules.genderSheetKey());
        String head = gender == null ? "" : "**" + rules.genderLabel(String.valueOf(gender)) + "** · ";
        return new EmbedBuilder().setColor(BLOOD)
                .setTitle("한 아이가 태어났다 — " + ch.name)
                .setDescription(head + "성향 **" + ch.disposition + "** · " + ch.bracket + " " + ch.age
                        + "세 · **" + ch.family.replace('_', ' ') + "**\n발단: **"
                        + ch.incident.replace('_', ' ')
                        + "** — 나머지는 운명이 정했다.")
                .build();
    }

    // ═══════════════ 서장(序章) — ★ **책 한 권으로 강호에서 흐른다** ═══════════════
    //
    // 【★ 옛 길은 죽었다 (2026-07-13)】 서장은 **디스코드의 프라이빗 스레드**에서 흘렀다:
    //   임베드가 뜨고, 버튼 셋(customId "tn:<장면>:<선택>")이 붙고, 눌렀다.
    //   사용자: *"이제 봇도 스토리 마크로 진행화 합시다. **디스코드에서 채팅을 치고 진행하는 것이
    //           아닌 마크로 넘어가는 걸로** 표현하자."*
    //
    //   그래서 **스레드는 더 이상 열리지 않고, tn 버튼은 없앴다.** 두 벌을 남기지 않는다 —
    //   "두 벌이면 하나가 낡는다." 디스코드에 남은 것은 **생성 문답**과 **이정표 하나**뿐이다.
    //
    // 【★ 그런데 그리는 것은 여전히 봇이다】 마크는 **서책**이고 봇이 **저자**다:
    //   · LLM(LlmRenderer)도 · 판정 엔진(2d6·성별 보정)도 · 시트도 — **봇에만 있다**
    //   · 마크는 **DB 를 열지 않는다** (단일 작성자 규약)
    //   → 마크가 문장을 지으면 **정본이 둘**이 된다. 그래서 마크에는 문장이 한 줄도 없다.
    //
    // 【진행은 시트에 산다 — DB 스키마를 건드리지 않았다】 (sheet_json 은 원래 자유 서식이다.
    //   마이그레이션도, **봇 정지도** 필요 없었다):
    //     서장_장면 (0..N, N = 에필로그) · 서장_직전등급 · 서장_본문 · 서장_토큰 · 서장_본문_지문
    //   ★ 옛 길의 `scenes` 표(스레드 잠금)는 서장에서 **더 이상 쓰지 않는다** — 스레드가 없으니까.
    //
    // 【흐름】 birth → (미리 쓰기) → 접합 → 다리가 책을 내려보냄 → 클릭 → seojang_choice →
    //         판정 → 다음 장 (미리 쓰기) → … → 에필로그 → [강호로 나선다] → 출도

    /** 태어난 직후 — 서장 0장을 **미리 쓴다** (사람은 아직 마크로 걸어오는 중이다) */
    private void startSeojang(Born born) throws Exception {
        Map<String, Object> sheet = born.sheet();
        sheet.put(Seojang.SHEET_SCENE, 0);
        db.updateCharacter(born.ch().id(), sheet, born.ch().wallet(), "범인", "서장", "서장");
        if (rules.seojang.prerender()) {
            // ★ 미리 쓰기 — 접합도 하기 전이다. 사람이 디스코드를 떠나 마크를 켜고 나루를 걷는
            //   그 몇 분이 22.4초를 삼킨다. 책이 손에 올 때 **이미 쓰여 있다.**
            writeScene(born.ch().id(), 0, null);
        }
    }

    /**
     * ★★ <b>한 장을 그린다</b> — 붓은 하나다 ({@link Scribe} — 「배는 한 명씩 탄다」).
     *
     * <p>글은 <b>장면이 넘어가는 순간 한 번만</b> 그리고 시트에 못 박는다. 다리는 2초마다
     * {@code seojang.json} 을 찍는데, 그때마다 LLM 을 부르면 <b>2초마다 새 소설이 쓰인다</b> —
     * 사람이 읽던 문장이 눈앞에서 바뀐다. 다리는 <b>적힌 것을 옮길 뿐</b>이다.
     *
     * @param idx 장면 번호. {@code sceneCount} 와 같으면 <b>에필로그</b>다
     */
    /**
     * ★★ <b>지금 붓이 들려 있는 서장</b> — 같은 장을 두 번 그리지 않게 막는 빗장.
     *
     * <p><b>이것이 없으면 무한 재렌더다:</b> 다리는 2초마다 {@code seojang.json} 을 찍고, 그때
     * 글이 아직 없으면 다시 그리라고 시킨다 — <b>그런데 붓은 아직 그리는 중이다.</b> 2초마다
     * 새 요청이 줄에 서고, 줄은 영영 안 줄어든다.
     */
    private final java.util.Set<Long> painting = ConcurrentHashMap.newKeySet();

    private void writeScene(long chId, int idx, String prevTier) throws Exception {
        if (!painting.add(chId)) {
            return;   // 이미 이 사람의 붓이 들려 있다 — 두 번 그리지 않는다
        }
        boolean handed = false;
        try {
            handed = writeScene0(chId, idx, prevTier);
        } finally {
            if (!handed) {
                painting.remove(chId);   // 붓을 못 들었다 (죽었거나 출도했다) — 빗장을 푼다
            }
        }
    }

    /** @return 붓을 실제로 들었는가 (들었으면 {@link #persistScene} 이 빗장을 푼다) */
    private boolean writeScene0(long chId, int idx, String prevTier) throws Exception {
        var row = db.findCharacterById(chId);
        if (row.isEmpty() || !"서장".equals(row.get().get("status"))) {
            return false;
        }
        Character ch = fromDb(row.get());
        int total = rules.seojang.sceneCount(ch.incident());
        boolean epilogue = idx >= total;

        // ★ 적서 — 적자의 유년과 서자의 유년은 **같은 글일 수 없다** (시트가 정본이다)
        @SuppressWarnings("unchecked")
        Map<String, Object> sh = (Map<String, Object>) row.get().get("sheet");
        Object rk = sh.get(rules.birthRankSheetKey());
        String rank = rk == null ? null : String.valueOf(rk);

        // ★★ 가문 — **흥한 집과 기우는 집의 유년은 같지 않다.** 그리고 고을도 다르다.
        //   ★ 형태는 **탄생에 고정**이므로 (사용자 확정) 미리 쓴 글이 거짓말이 될 일이 없다.
        String hState = null;
        String hRegion = null;
        try {
            Long hid = db.houseOfCharacter(chId);
            if (hid != null) {
                var h = db.house(hid);
                if (h.isPresent()) {
                    hState = h.get().state();
                    hRegion = h.get().region();
                }
            }
        } catch (Exception ignored) {
            // 가문을 못 읽어도 서장은 흐른다 (색이 하나 빠질 뿐이다)
        }

        String regionName = hRegion == null ? null : rules.regionName(hRegion);
        // ★★ **탄생 순간의** 형제를 읽는다 (산 것이 아니라 **박제된 것**).
        //   형의 서장은 동생이 나기 전에 쓰였다 — 2장·3장이 나중에 그려져도 **없던 동생이 안 튀어나온다.**
        @SuppressWarnings("unchecked")
        List<String> kinAtBirth = sh.get(Seojang.SHEET_KIN) instanceof List<?> kl
                ? kl.stream().map(String::valueOf).toList() : List.of();

        String base = epilogue ? rules.seojang.epilogue(ch, prevTier, regionName)
                : rules.seojang.sceneBody(ch, idx, prevTier, rank, hState, hRegion, kinAtBirth);
        String title = epilogue ? rules.seojang.book("epilogue_header", "서장의 끝")
                : rules.seojang.title(ch.incident(), idx, regionName);
        String facts = epilogue ? epilogueFacts(ch, prevTier, base)
                : sceneFacts(ch, title, prevTier, base, rank, hState, hRegion);

        // ★ 지문 — 이 글이 **어느 캐릭터의 어느 장면의 어느 이음새**를 위해 쓰였는가.
        //   내려보낼 때 지금과 다르면 그 글은 낡았다 (seojang.yml prerender.invalidate).
        String print = Seojang.fingerprint(chId, idx, prevTier);
        String token = print + ":" + Long.toHexString(dice.nextLong() & 0xffffffffL);

        scribe.write(facts, base, ahead -> ferryTell(chId, ahead))
                .thenAccept(written -> persistScene(chId, idx, token, print, written));
        return true;
    }

    /** 그려진 글을 시트에 못 박고, 다리에 내려보내라고 이른다 (붓 스레드에서 불린다) */
    private void persistScene(long chId, int idx, String token, String print, Scribe.Written written) {
        try {
            var row = db.findCharacterById(chId);
            if (row.isEmpty() || !"서장".equals(row.get().get("status"))) {
                return;   // 그 사이에 초기화됐거나 죽었다 — 낡은 글은 버린다
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get().get("sheet"));
            // ★ 지금 이 캐릭터가 서 있는 장면이 아니면 **버린다** (그 사이 사람이 앞서 갔다)
            if (sceneIdx(sheet.get(Seojang.SHEET_SCENE)) != idx) {
                return;
            }
            sheet.put(Seojang.SHEET_BODY, written.text());
            sheet.put(Seojang.SHEET_TOKEN, token);
            sheet.put(Seojang.SHEET_PRINT, print);
            sheet.put(Seojang.SHEET_FALLBACK, written.fallback());
            db.updateCharacter(chId, sheet, ((Number) row.get().get("wallet")).intValue(),
                    "범인", "서장", "서장");
            if (written.fallback()) {
                // ★ 폴백은 침묵하지 않는다 — 장부에 남는다 (사람에겐 책의 간기 한 줄)
                db.logEvent("서사_폴백", "character", String.valueOf(chId),
                        Map.of("장면", idx, "사유", written.reason() == null ? "미상" : written.reason()));
            }
            if (bridge != null) {
                bridge.publishSeojang();   // ★ 사람이 책을 펴 놓고 기다린다 — 다음 바퀴를 기다리지 않는다
            }
        } catch (Exception e) {
            System.err.println("서장 — 글을 장부에 못 박지 못했다: " + e.getMessage());
        } finally {
            painting.remove(chId);   // ★ 붓을 내려놓는다 — 실패해도 반드시 (안 그러면 영영 못 그린다)
        }
    }

    /** 나루의 사공 — <b>차례를 말해 준다</b> (침묵 금지). 앞에 아무도 없으면 굳이 말하지 않는다 */
    private void ferryTell(long chId, int ahead) {
        if (ahead <= 0 || bridge == null) {
            return;
        }
        bridge.ferryNotice(chId, rules.seojang.ferry("queued",
                "나루의 사공은 한 번에 한 사람만 태운다 — 앞에 {ahead}인.")
                .replace("{ahead}", String.valueOf(ahead)));
    }

    /**
     * ★★ <b>그 손이 책의 글자를 눌렀다</b> — 서장의 유일한 진행 신호 (다리의 {@code seojang_choice}).
     *
     * <p><b>★ 다리를 믿지 않는다.</b> jsonl 은 파일이다 — 손으로 한 줄 끼워 넣을 수 있다. 그래서:
     * <ol>
     *   <li>그 몸이 <b>정말 그 캐릭터인가</b> (mvt_link 대조 — 남의 서장은 못 넘긴다)</li>
     *   <li>토큰이 <b>지금 그 장면의 것인가</b> (낡은 책·연타는 여기서 죽는다)</li>
     *   <li>고른 번호가 <b>등록부에 있는 선택지인가</b> (없는 길은 못 간다)</li>
     * </ol>
     *
     * @param choice 선택지 번호. <b>-1 = 출도</b> (마지막 책의 [강호로 나선다])
     */
    void seojangChoice(String mcUuid, String token, int choice, int today) throws Exception {
        Optional<Long> owner = db.characterOfMc(mcUuid);
        if (owner.isEmpty()) {
            return;   // 접합되지 않은 몸 — 서장이 갈 곳이 없다
        }
        long chId = owner.get();
        var row = db.findCharacterById(chId);
        if (row.isEmpty() || !"서장".equals(row.get().get("status"))) {
            return;   // 이미 출도했거나 죽었다 — 낡은 책의 클릭이다
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get().get("sheet"));

        // ★ 토큰 대조 — **낡은 책의 클릭은 아무것도 하지 않는다** (연타도 여기서 죽는다)
        String want = String.valueOf(sheet.get(Seojang.SHEET_TOKEN));
        if (token == null || !token.equals(want)) {
            System.err.println("서장 — 낡은/남의 토큰 (버린다): " + token + " ≠ " + want);
            return;
        }
        Character ch = fromDb(row.get());
        int idx = sceneIdx(sheet.get(Seojang.SHEET_SCENE));
        int total = rules.seojang.sceneCount(ch.incident());
        int wallet = ((Number) row.get().get("wallet")).intValue();

        // ─── 에필로그의 단 하나의 선택 = 출도(出道) ───
        if (idx >= total || choice < 0) {
            // ★ 토큰을 이미 태웠다 — 두 번 눌러도 두 번 출도하지 않는다 (위의 대조가 막는다)
            sheet.remove(Seojang.SHEET_SCENE);
            sheet.remove(Seojang.SHEET_TIER);
            sheet.remove(Seojang.SHEET_BODY);
            sheet.remove(Seojang.SHEET_TOKEN);
            sheet.remove(Seojang.SHEET_PRINT);
            sheet.remove(Seojang.SHEET_FALLBACK);
            db.updateCharacter(chId, sheet, wallet, "범인", "강호", "청하현");
            db.logEvent("출도", "character", String.valueOf(chId), "mvt", mcUuid,
                    Map.of("지역", "청하현", "경로", "게임내_책"));
            if (bridge != null) {
                bridge.publishSeojang();   // 목록에서 사라진다 — 책은 회수된다
            }
            return;
        }

        List<Seojang.Scene> list = rules.seojang.scenesOf(ch.incident());
        Seojang.Scene sc = list.get(idx);
        if (choice >= sc.choices().size()) {
            return;   // 등록부에 없는 길 (다리가 지어낸 번호) — 버린다
        }
        Seojang.Choice pick = sc.choices().get(choice);

        int stat = rules.genderStat(sheet, pick.stat(), 2);   // ★ 성별 보정(히든)이 여기서 든다
        int roll = dice.nextInt(6) + 1 + dice.nextInt(6) + 1;
        JudgmentEngine.Tier tier = rules.judgment.resolve(stat + pick.bonus(), roll, sc.resist());
        int margin = stat + pick.bonus() + roll - sc.resist();
        db.logEvent("판정", "character", String.valueOf(chId), "mvt", mcUuid,
                Map.of("장면", sc.title(), "선택", pick.label(), "굴림", roll,
                        "마진", margin, "등급", tier.name(), "출처", "서장_책"));

        // 다음 장으로 — 본문·토큰은 **새로 그릴 때까지 비운다** (낡은 글이 책이 되면 안 된다)
        int next = idx + 1;
        sheet.put(Seojang.SHEET_SCENE, next);
        sheet.put(Seojang.SHEET_TIER, tier.name());
        sheet.remove(Seojang.SHEET_BODY);
        sheet.remove(Seojang.SHEET_TOKEN);
        sheet.remove(Seojang.SHEET_PRINT);
        db.updateCharacter(chId, sheet, wallet, "범인", "서장", "서장");
        if (bridge != null) {
            bridge.publishSeojang();   // ★ "붓이 다음 장을 적고 있다" — 침묵하지 않는다
        }
        writeScene(chId, next, tier.name());
    }

    /**
     * 다리가 내려보낼 <b>지금 서장 중인 몸들</b> — 접합된 산 자만 (Bridge.publishSeojang 이 부른다).
     *
     * <p>글이 아직 안 그려졌으면 {@code narration} 이 null 이다 — 그때 마크는 <b>책을 주지 않고</b>
     * 사공의 말만 한다. 그리고 <b>지문이 어긋나면 다시 그린다</b> (미리 쓴 글이 낡았을 수 있다).
     */
    List<Map<String, Object>> seojangEntries() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Long> body : db.linkedBodies().entrySet()) {
            var row = db.findCharacterById(body.getValue());
            if (row.isEmpty() || !"서장".equals(row.get().get("status"))) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> sheet = (Map<String, Object>) row.get().get("sheet");
            Character ch = fromDb(row.get());
            int idx = sceneIdx(sheet.get(Seojang.SHEET_SCENE));
            int total = rules.seojang.sceneCount(ch.incident());
            boolean epilogue = idx >= total;
            String prevTier = sheet.get(Seojang.SHEET_TIER) == null
                    ? null : String.valueOf(sheet.get(Seojang.SHEET_TIER));

            Map<String, Object> e = new LinkedHashMap<>();
            e.put("mc_uuid", body.getKey());
            e.put("character", ch.name());
            e.put("scene", idx);
            e.put("total", total);
            e.put("final", epilogue);
            // ★ 책의 제목에도 고을이 든다 (「낯선 고을 청하현」 — 강남의 아이는 「강남 상로」다)
            String rn = null;
            try {
                Long hid2 = db.houseOfCharacter(ch.id());
                if (hid2 != null) {
                    var h2 = db.house(hid2);
                    if (h2.isPresent()) {
                        rn = rules.regionName(h2.get().region());
                    }
                }
            } catch (Exception ignored) {
                // 가문을 못 읽어도 책은 온다
            }
            e.put("title", epilogue ? rules.seojang.book("epilogue_header", "서장의 끝")
                    : rules.seojang.title(ch.incident(), idx, rn));

            Object body0 = sheet.get(Seojang.SHEET_BODY);
            String print = String.valueOf(sheet.get(Seojang.SHEET_PRINT));
            String now = Seojang.fingerprint(ch.id(), idx, prevTier);
            if (body0 == null) {
                e.put("state", "쓰는_중");   // 붓이 아직 들려 있다 — 마크는 사공의 말만 한다
                // ★★ **막다른 곳을 막는다.** 글이 없는데 붓도 안 들려 있다면 그 서장은 **버려진 것**이다
                //   (봇이 그리는 도중에 꺼졌다 — 미리 쓰기가 날아갔다). 그대로 두면 사람은
                //   "쓰는 중" 이라는 말만 보며 **영영 책을 못 받는다.** 다시 든다.
                //   (painting 이 빗장이라 2초마다 중복으로 그리지는 않는다)
                if (!painting.contains(ch.id())) {
                    System.out.println("서장 — 버려진 붓을 다시 든다: " + ch.name() + " (장면 " + idx + ")");
                    writeScene(ch.id(), idx, prevTier);
                }
            } else if (!now.equals(print)) {
                // ★ 미리 쓴 글이 **낡았다** — 지문이 어긋난다. 버리고 다시 쓴다
                e.put("state", "쓰는_중");
                writeScene(ch.id(), idx, prevTier);
            } else {
                e.put("state", "펼침");
                e.put("narration", String.valueOf(body0));
                e.put("token", String.valueOf(sheet.get(Seojang.SHEET_TOKEN)));
                e.put("fallback", Boolean.TRUE.equals(sheet.get(Seojang.SHEET_FALLBACK))
                        && rules.fallbackVisible());
                List<Map<String, Object>> choices = new ArrayList<>();
                if (!epilogue) {
                    List<Seojang.Choice> cs = rules.seojang.scenesOf(ch.incident()).get(idx).choices();
                    for (int i = 0; i < cs.size(); i++) {
                        choices.add(Map.of("n", i, "label", cs.get(i).label()));
                    }
                }
                e.put("choices", choices);   // 에필로그는 빈 목록 — 마크가 [강호로 나선다] 를 붙인다
            }
            out.add(e);
        }
        return out;
    }

    /** 시트의 장면 번호 — 없으면 0 (서장의 첫 장). ★ 이미 있는 num(Object):double 과 다른 축이다 */
    private static int sceneIdx(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    /**
     * ★★ <b>형제자매</b> — 같은 집에 태어난 아이들, <b>태어난 순서대로</b>.
     *
     * <p>사용자: <i>"같은 세가에 같이 태어나게 되었다면 <b>순서대로 형, 누나</b>가 되어야 함."</i>
     *
     * <p><b>★ 문파의 호칭과 다르다 — 섞지 않는다.</b> 문파는 <b>입문 순</b>의 사형·사저·사제·사매고
     * (sect_life.yml brotherhood.order), 혈연은 <b>태어난 순</b>의 형·누나·동생이다.
     * 어휘는 {@code player_creation.yml gender.gates.honorifics.kin} 이 정한다 —
     * <b>코드가 말을 지어내지 않는다.</b>
     *
     * <p><b>★ 호칭은 불리는 자의 성별로 정해진다</b> ({@code resolve_by: 대상의_성별}) —
     * 내가 사내든 계집이든, 먼저 난 사내는 '형'이다.
     *
     * <p><b>표를 만들지 않았다 (마이그레이션 없음).</b> 서열은 {@code characters.id} 순의 <b>파생값</b>이다 —
     * 파생값은 낡을 수가 없다. 별도 표를 두면 그 표가 진실과 갈라진다.
     */
    private List<Map<String, Object>> kinOf(long me) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        // ═══ ★★ 형제는 **한 채의 집**으로만 잡는다 (유형이 아니라) — B-077 ═══
        //
        // 【담당자가 만들었던 병】 형제를 **집안 유형**으로 묶었다. 그래서 **농가의 아이 둘이 남매**가 됐다 —
        //   서로 다른 농가인데. 이제 **같은 house_id 를 가진 산 자만** 남매다.
        //
        // 【★ 이것은 「지금」의 형제다 — 서장의 형제가 아니다】
        //   서장은 **탄생 순간의 형제**를 박제해 읽는다 (Seojang.SHEET_KIN). 형의 서장은 동생이
        //   나기 전에 쓰였고, 그때 그는 **정말로 혼자였다.** 그 글은 고치지 않는다.
        //   **서장은 과거고, 이 함수는 현재다.** 둘을 섞지 마라.
        if (!rules.houseSystemEnabled()) {
            return out;   // 가문이 아직 없다 — 형제도 아직 없다
        }
        // ★★ 여기서부터는 **가문 실체(house_id) 기준**이다. 유형으로 묶지 않는다.
        Long houseId = db.houseOfCharacter(me);
        if (houseId == null) {
            return out;   // 이 사람은 아직 집이 없다 (마이그레이션 전에 태어났다)
        }
        List<Map<String, Object>> born = db.houseMembers(houseId);   // 태어난 순 (id 오름차순)
        boolean before = true;   // 나를 만나기 전까지는 전부 손위다
        for (Map<String, Object> k : born) {
            long id = ((Number) k.get("id")).longValue();
            if (id == me) {
                before = false;
                continue;
            }
            String theirGender = k.get("성별") == null ? null : String.valueOf(k.get("성별"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", k.get("name"));
            row.put("elder", before);
            // ★ 등록부의 말 (형/누나/동생) — 없으면 성별 담당의 unknown ('무인')
            row.put("title", rules.kinTitle(before, theirGender));
            out.add(row);
        }
        return out;
    }

    // ─── LLM 렌더 사실 묶음 — 엔진이 계산한 것만 넘긴다 (7계: 엔진 불가침) ───

    /**
     * ★ 인물 한 줄 — LLM 에게 주는 <b>사실</b>. <b>적서가 여기 실린다</b>:
     * 적자의 유년과 서자의 유년은 <b>같은 글일 수 없다</b> (담장 안에서부터 무게가 다르다).
     */
    private String personLine(Character ch) {
        return personLine(ch, null);
    }

    private String personLine(Character ch, String rank) {
        return ch.name() + " (" + ch.bracket() + " " + ch.age() + "세, 성향 " + ch.disposition()
                + ", 집안 " + ch.family().replace('_', ' ')
                + (rank == null ? "" : ", **" + rank + "**")
                + ", 발단 " + ch.incident().replace('_', ' ') + ")";
    }

    /**
     * ★ 장면의 사실 — <b>집안이 여기 실린다.</b>
     *
     * <p>사용자: <i>"모든 사람이 모두 똑같은 시작이 아니라는 뜻."</i> 무가의 자식과 객잔집 자식의
     * 서장이 <b>같은 문장이면 안 된다.</b> {@link #personLine} 이 이미 집안·발단·성향·나이를 싣고,
     * 폴백 문장도 집안별로 갈린다 (seojang.yml prose.family_color — LLM 이 죽어도 글이 갈린다).
     */
    private String sceneFacts(Character ch, String title, String prevTier, String base, String rank,
                             String houseState, String region) {
        String houseLine = houseState == null && region == null ? ""
                : "가문: " + (region == null ? "" : rules.regionName(region) + " ")
                + (houseState == null ? "" : "(" + houseState + "한 집)") + "\n";
        return "장면: " + title + "\n인물: " + personLine(ch, rank) + "\n" + houseLine
                + (prevTier == null ? "" : "직전 판정 결과: " + prevTier + "\n")
                + "기준 서사(이 사실 범위 안에서만 확장하라):\n" + base;
    }

    private String epilogueFacts(Character ch, String lastTier, String base) {
        return "장면: 서장 에필로그 — 청하현 정착의 첫 밤\n인물: " + personLine(ch)
                + "\n마지막 판정 결과: " + lastTier
                + "\n기준 서사(이 사실 범위 안에서만 확장하라):\n" + base;
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

    /**
     * 출도 여부 검사 — 서장 중이면 거절.
     *
     * <p>★ <b>이 문은 그대로 잠겨 있다.</b> 접합은 서장 중에도 되지만(몸과 이름을 잇는 일이다),
     * 사냥·비무·의뢰·대화는 <b>출도한 자의 것</b>이다. 옛 안내문은 "서장 <b>스레드</b>를 끝내라"고
     * 했는데 — <b>그 스레드는 이제 없다.</b> 서장은 강호의 책에서 끝난다.
     */
    /**
     * <b>이름이 있는가</b> — 그것만 본다 (출도는 안 본다).
     *
     * <p>접합·해제·시트는 <b>서장 중에도</b> 되어야 하는 일이다. 그 문에 출도를 요구하면 순환 교착이 된다
     * ({@link #askLink} 의 주석 참조 — 같은 병을 {@link #unlinkAccount} 에서 한 번 더 잡았다).
     */
    private Optional<Map<String, Object>> requireCharacter(IReplyCallback event, User user)
            throws Exception {
        var found = db.findCharacter(user.getId());
        if (found.isEmpty()) {
            event.reply(user.getEffectiveName() + " — 캐릭터가 없다. `/혼천 시작`부터.")
                    .setEphemeral(true).queue();
        }
        return found;
    }

    private Optional<Map<String, Object>> requireDebuted(SlashCommandInteractionEvent event, User user)
            throws Exception {
        var found = db.findCharacter(user.getId());
        if (found.isEmpty()) {
            event.reply(user.getEffectiveName() + " — 캐릭터가 없다. `/혼천 시작`부터.").setEphemeral(true).queue();
            return Optional.empty();
        }
        if (!"강호".equals(found.get().get("status"))) {
            event.reply(user.getEffectiveName() + " — 아직 **서장** 중이다. 강호에서 **서책**을 끝내야 출도한다"
                            + " (마크에 접속해 `/혼천 접속` 으로 몸을 잇고, 품에 온 책을 펼쳐라).")
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

        int stat = rules.genderStat(sheet, approach[1], 2);   // ★ 성별 보정(히든)
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
        storyTick(chId, realm);   // B-109 — 사냥·승급이 마디를 닫을 수 있다

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
                : Injections.routeQuests(rules, factions.favor("orthodox", chId, today),
                        tagsOf(sheet).keySet()));
        out.addAll(Injections.deathQuests(rules, db.deadNpcs(), today));
        // 단계 5 — 강호의 판이 게시판에 비친다 (명분 조사 · 토벌령).
        // 단 절연당한 자에게 토벌대는 손을 내밀지 않는다 — 그가 곧 토벌의 대상이다
        if (!disavowed(chId, today)) {
            out.addAll(Injections.politicsQuests(rules, politics(today)));
        }
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

        // ★ B7 — 혈채 15+: 게시판이 그를 위해 닫힌다. 세계에서 **일이 사라진다**
        //   (blood_debt §11 — 객잔이 문을 잠근다. 남은 수입은 약탈뿐이다. 녹림 산채_등재와 같은 문법)
        if (boardClosedByDebt(chId, today)) {
            event.replyEmbeds(new EmbedBuilder().setColor(BLOOD)
                    .setTitle("의뢰소 — 대장을 덮는다")
                    .setDescription("문을 밀고 들어서자 말소리가 끊긴다. " + clerkName(office)
                            + "이(가) 대장을 소리 없이 덮고, 눈을 들지 않는다.\n"
                            + "\"…오늘은 붙은 것이 없소.\"\n"
                            + "널빤지에는 방이 두 장 붙어 있다. **둘 다 당신 이야기다.**\n"
                            + "*(세계에서 일이 사라졌다 — 남은 벌이는 약탈뿐이다)*").build()).queue();
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
        FactionService.Standing orthodox = factions.standing("orthodox", chId, today);
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
        // 단계 5 — 절연: 게시판에 붙은 방(榜)에 **네 이름이 적혀 있다**
        if (disavowed(chId, today)) {
            reaction += "\n\n🩸 **게시판 한복판에 방(榜)이 붙어 있다 — 네 이름이다.**\n"
                    + "\"…관의 사람을 벤 자라더군. 현상금이 두 배로 걸렸소.\"\n"
                    + "창구는 눈을 마주치지 않는다. 어제까지 밥을 먹던 객잔도 문을 잠갔다.\n"
                    + "*(강호의 절연 — 법명분 " + db.mandate(chId, today, rules.politics)
                    + " · 세력 경유 의뢰 없음 · 현상금 ×" + rules.politics.bountyMultiplier() + ")*";
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
        int stat = rules.genderStat(sheet, approach.stat(), 2);   // ★ 성별 보정(히든)
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
            int favor = factions.addFavor(faction, chId, rules.factions.favorInput("공적_소"),
                    rules.factions.favorMax(), today);
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
            storyTick(chId, realm);   // B-109 — 의뢰_완수·favor·승급이 마디를 닫을 수 있다
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
        // 가장 센 이야기부터 (PoliticsStore.heard 가 유효강도·최신순으로 준다) — 사람은 큰 소문부터 옮긴다
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

    /**
     * llm.yml runtime 의 <b>문장</b> 키 (chat_queue_notice·chat_fallback_mark — B-016·B-017).
     *
     * <p>{@link Rules#llmRuntime} 은 숫자만 내주는 손잡이라 (Rules 는 다른 트랙 소유 — 못 넓힌다)
     * 같은 등록부를 여기서 한 번 더 읽는다. 원칙은 같다 — <b>문구는 코드가 짓지 않는다.</b>
     * 파일을 못 읽어도 봇은 죽지 않는다 (아래 기본 문장으로 흐른다).
     */
    private final Map<String, Object> llmRuntimeCfg = loadLlmRuntime();

    private static Map<String, Object> loadLlmRuntime() {
        try {
            // HONCHEON_CONFIG 규약은 HoncheonBot.main 과 같다 (기본 config) — 다른 파일을 읽으면 거짓말이 된다
            return RulesConfig.section(RulesConfig.load(
                    Path.of(System.getenv().getOrDefault("HONCHEON_CONFIG", "config")).resolve("llm.yml")),
                    "runtime");
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String llmRuntimeText(String key, String fallback) {
        Object v = llmRuntimeCfg.get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    /**
     * npc_dialogue.yml {@code house_name_by_rumor} — <b>가문 이름의 빗장</b> (B-073).
     * 문턱(known_min_accuracy)과 프롬프트 문구는 등록부가 정본 — 코드가 짓지 않는다.
     * {@code llmRuntimeCfg} 와 같은 관행 (Rules 는 다른 트랙 소유 — 못 넓힌다).
     * 못 읽으면 빈 맵 = <b>빗장은 닫힌 쪽으로 진다</b> (이름을 대지 않는다).
     */
    private final Map<String, Object> houseNameCfg = loadHouseNameCfg();

    private static Map<String, Object> loadHouseNameCfg() {
        try {
            return RulesConfig.section(RulesConfig.load(
                    Path.of(System.getenv().getOrDefault("HONCHEON_CONFIG", "config"))
                            .resolve("npc_dialogue.yml")), "house_name_by_rumor");
        } catch (Exception e) {
            return Map.of();
        }
    }

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
        // ★ B-073 — 이 NPC 의 망에 탄생 소문이 닿았으면 가문 이름의 빗장이 풀린다 (닿기 전엔 잠김)
        String persona = personaPrompt(npcName, npc, (Map<String, Object>) row.get("sheet"),
                houseNews(npcKey, chId, day));
        String fallback = fallbackLine(npcName, npc);
        event.deferReply().queue();   // 로컬 LLM 1~3초 — 3초 응답 제한 회피
        // F36 — 키워드 게이트가 먼저: 정보 질문은 결정론으로 판정층 (LLM 호출도 절약)
        if (isInfoSeeking(say)) {
            resolveInfoCheck(event, row, chId, npcName, say);
            return;
        }
        // ★ B-016 — 대화도 **같은 배**를 탄다: 전에는 여기서 렌더러를 직접 불렀다 — 서장 lane 과
        //   GPU 를 다퉜다 (Scribe 실측: 동시에 던지면 각자 4배). 이제 줄(단일 차선)을 지나므로
        //   llm.yml runtime.serialize 가 대화에도 걸린다. 줄이 길면 차례를 말해 준다 (침묵 금지).
        scribe.chat(persona, say, fallback, ahead -> chatFerryTell(event, npcName, ahead))
                .thenAccept(written -> {
            try {
                String reply = written.text();
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
                // ★ B-017 — 폴백은 침묵하지 않는다 (서장의 간기 fallback_mark 와 동형):
                //   사람에게는 등록부의 표식 한 줄, 장부에는 events '서사_폴백' 한 건.
                if (written.fallback()) {
                    if (rules.fallbackVisible()) {
                        clean = clean + "\n" + llmRuntimeText("chat_fallback_mark",
                                "*(붓이 더디어 몸짓만 돌아왔다)*");
                    }
                    db.logEvent("서사_폴백", "character", String.valueOf(chId),
                            Map.of("경로", "대화", "상대", npcName,
                                    "사유", written.reason() == null ? "미상" : written.reason()));
                }
                // 잡담층 + 세계층 기록 (대화 요지 — NPC 기억의 재료)
                db.logEvent("대화", "character", String.valueOf(chId), npcName,
                        Map.of("말", say.substring(0, Math.min(80, say.length()))));
                storyTick(chId, null);   // B-109 — 대화(target=NPC)가 마디를 닫을 수 있다
                event.getHook().sendMessageEmbeds(new EmbedBuilder().setColor(INK)
                        .setTitle("「" + npcName + "」")
                        .setDescription(clean).build()).queue();
            } catch (Exception e) {
                event.getHook().sendMessage("오류: " + e.getMessage()).queue();
            }
        });
    }

    /**
     * 나루의 사공 — <b>대화의 줄에도 차례를 말해 준다</b> (침묵 금지 — 서장 {@code ferryTell} 과 동형,
     * 단 문(門)이 다르다: 서장은 마크의 화면, 대화는 디스코드의 hook).
     *
     * <p>서장과 달리 문턱이 있다 (llm.yml runtime.queue_warn_depth) — 대화는 짧고 디스코드가
     * "생각 중…" 을 이미 보여 주므로, 줄이 정말 길 때만 세계의 말로 이유를 댄다.
     */
    private void chatFerryTell(SlashCommandInteractionEvent event, String npcName, int ahead) {
        if (ahead < rules.llmRuntime("queue_warn_depth", 3)) {
            return;
        }
        event.getHook().sendMessage(llmRuntimeText("chat_queue_notice",
                "…{npc}은(는) 다른 손님을 상대하고 있다 — 조금만. (앞에 {ahead}인)")
                .replace("{npc}", npcName).replace("{ahead}", String.valueOf(ahead))).queue();
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
        int stat = rules.genderStat(sheet, "화술", 2);   // ★ 성별 보정(히든) — 등록부가 화술을 안 가르면 0
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
        storyTick(chId, null);   // B-109 — 판정 대화도 대화다 (NPC 에게 물은 것)

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

    /**
     * 페르소나 시스템 프롬프트 — 등록부(역할·성향)가 원천, 지식 경계·판정 라우팅 포함.
     *
     * <p>★ 호칭(v4): NPC 가 플레이어를 <b>성별에 맞게</b> 부른다 — 소협(사내) / 여협(계집).
     * 그 말은 <b>등록부가 정한다</b> (player_creation gender.gates.honorifics.jianghu).
     * 성별을 모르는 옛 캐릭터는 {@code unknown}(무인) 으로 부른다 — <b>LLM 이 성별을 추측하지 못하게</b>
     * 못을 박는다 (그러지 않으면 모델이 멋대로 '소협'이라 부른다).
     */
    @SuppressWarnings("unchecked")
    private String personaPrompt(String name, Map<String, Object> npc, Map<String, Object> sheet,
                                 HouseNews news) {
        Object disp = npc.get("disposition");
        String dispositions = disp instanceof List<?> list
                ? String.join(", ", list.stream().map(String::valueOf).toList()) : "";
        Object g = sheet == null ? null : sheet.get(rules.genderSheetKey());
        String honorific = rules.genderEngine.jianghuHonorific(g == null ? null : String.valueOf(g));
        // v3 — 8차-② 채집물 반영: 소문 발명(금서방 철공소·소연 청하정)이 판정 게이트를 우회 →
        //      질문 예시 명시 + 소문·사건 발명을 별도 금지 조항으로 (서버측 F36 키워드 게이트와 이중 방어)
        // ═══ ★★ NPC 가 **내가 누구의 자식인지** 안다 (2026-07-13 사용자 지시) ═══
        //
        // 사용자: *"**NPC와의 대화에서도 적용**되어야 함"* — "자네가 그 무가의 아이인가."
        //
        // 【★ 그러나 **모르는 것을 아는 척하면 안 된다**】 청하현의 객잔 주인이 사천 무가의 아이를
        //   **첫눈에** 알아보면 그것은 세계가 깨진 것이다. 그래서 경계를 이렇게 긋는다:
        //     · NPC 는 **집안의 결**을 안다 (말씨·옷차림·예법은 몸에 밴다 — 숨길 수 없다)
        //     · NPC 는 **가문의 이름**은 소문이 닿아야 안다 — ★ B-073 배선 완료: 탄생 소문
        //       (birthRumor, 군 탄생:<id>)이 이 NPC 의 망에 도달했으면 news 가 이름을 든다.
        //   ★ 즉 소문 전엔 "무가의 아이 같군" 까지, 소문 후엔 "자네가 그 집 아이인가" 가 된다.
        //   정확도가 낮으면(오해·괴담 밴드) **확신 없는 언급**만 — rumor.yml accuracy_bands 문법.
        String house = sheet == null || sheet.get("집안") == null ? null
                : String.valueOf(sheet.get("집안"));
        boolean left = sheet != null && Boolean.TRUE.equals(sheet.get("집안_이탈"));
        String houseLine = houseLine(houseNameCfg, house, left,
                news == null ? null : news.name(), news == null ? -1 : news.accuracy());

        return "너는 무협 세계 청하현의 NPC 「" + name + "」이다. 너의 역할: " + npc.get("role")
                + ". 너의 성향: " + dispositions + ".\n"
                + "말을 거는 상대는 강호에 갓 나온 손님이다 — 상대에게 너의 직업을 투사하지 마라.\n"
                + houseLine
                + "0. 상대를 부를 때는 반드시 **「" + honorific + "」** 이라 불러라 —"
                + " 다른 호칭을 지어내지 마라. 상대의 성별을 네가 추측하지 마라.\n"
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

    /** NPC 가 아는 가문의 「이름」 — 탄생 소문(B-069)이 그 NPC 의 망에 닿았을 때만 존재한다 (B-073) */
    record HouseNews(String name, int accuracy) {
    }

    /**
     * ★ B-073 — <b>이 NPC 는 이 아이의 가문 이름을 아는가.</b>
     *
     * <p>장부(events.탄생)가 아니라 <b>소문망</b>에 묻는다: 탄생 소문(군 {@link #birthGroup})이
     * 그 NPC 가 사는 장소의 망(rumor.yml origin_network_by_location — {@code npcClue} 와 같은
     * 배선)에 <b>도달했는가</b> ({@code Db.rumorAccuracyIn}, -1 = 아직). 도달했으면 그 망의
     * 정확도가 곧 앎의 질이다 — 같은 아이라도 객잔망과 상단망이 다른 크기로 안다.
     *
     * <p><b>감쇠 후에도 잊지 않는다</b> — 강도 감쇠는 소문의 '옮겨짐'을 죽이지, 들은 사람의
     * 기억을 지우지 않는다 (rumor.yml npc_memory_tags: "소문 소멸 후에도 개인의 기억은 남는다").
     * 그래서 유효강도(heard)가 아니라 <b>도달</b>(rumorAccuracyIn)로 묻는다.
     */
    private HouseNews houseNews(String npcKey, long chId, int today) {
        try {
            if (npcKey == null) {
                return null;
            }
            Long houseId = db.houseOfCharacter(chId);
            if (houseId == null) {
                return null;   // 집이 없는 아이 (옛 캐릭터·가문 배정 실패) — 알 이름 자체가 없다
            }
            String houseName = db.house(houseId).map(HouseEntry::name).orElse(null);
            if (houseName == null || houseName.isBlank()) {
                return null;
            }
            String network = rules.originNetwork(String.valueOf(rules.npcLocation(npcKey)));
            int accuracy = db.rumorAccuracyIn(birthGroup(chId), network, today);
            return accuracy < 0 ? null : new HouseNews(houseName, accuracy);
        } catch (Exception e) {
            // 소문을 못 읽으면 모르는 쪽으로 — 대화가 소문 때문에 죽으면 안 된다
            return null;
        }
    }

    /** 등록부가 없을 때의 빗장 — 닫힌 쪽으로 진다 (이름을 대지 않는다). 문구 정본은 npc_dialogue.yml */
    private static final String HOUSE_UNKNOWN_FALLBACK =
            "너는 그 **결**만 알아본다 (말씨·옷차림·몸에 밴 예법). "
                    + "가문의 **이름**은 모른다 — 소문이 닿지 않았다. "
                    + "'무가의 아이 같군' 은 되고, 가문 이름을 대는 것은 금지다.";

    /**
     * 집안 줄 — <b>빗장의 세 상태</b> (B-073):
     * 소문이 안 닿았다(unknown — 이름 금지) / 뒤틀려 닿았다(distorted — 확신 없는 언급만) /
     * 제대로 닿았다(known — 이름을 안다, 정확도 N).
     *
     * <p>문턱({@code known_min_accuracy})과 문구는 <b>npc_dialogue.yml house_name_by_rumor 가
     * 정본</b> — 코드가 짓지 않는다. 등록부가 비거나 문구가 빠지면 <b>unknown 으로 진다</b>
     * (열리는 실수보다 잠기는 실수가 싸다).
     *
     * <p>static 인 이유: 프롬프트 조립의 단위 검증(하네스)이 DB 없이 이 함수를 직접 친다.
     */
    static String houseLine(Map<String, Object> cfg, String house, boolean left,
                            String houseName, int accuracy) {
        if (house == null) {
            return "";
        }
        // 문턱이 등록부에 없으면 어떤 정확도도 known 에 못 미친다 (닫힌 빗장)
        int knownMin = cfg.get("known_min_accuracy") instanceof Number n
                ? n.intValue() : Integer.MAX_VALUE;
        String key = houseName == null || accuracy < 0 ? "unknown"
                : accuracy >= knownMin ? "known" : "distorted";
        Object raw = cfg.get(key);
        String text = raw instanceof String s && !s.isBlank() ? s : HOUSE_UNKNOWN_FALLBACK;
        return "상대의 집안: **" + house + "**"
                + (left ? " (집을 나온 아이 — 가문의 뒷배가 없다)" : "") + ". "
                + text.replace("{house_name}", houseName == null ? "" : houseName)
                        .replace("{accuracy}", String.valueOf(accuracy))
                + "\n";
    }

    /** LLM 비활성·실패 시 폴백 — 역할 기반 한 줄 (대화가 죽지 않는다) */
    private String fallbackLine(String name, Map<String, Object> npc) {
        return npc.get("role") + " " + name + "은(는) 고개만 끄덕였다. 바빠 보인다.";
    }

    // ─── 개화 축 (단계 2) — 폐사당의 취걸개(fortune_encounters) → 심법 전수 → 운기·축기 → 일류 ───

    /**
     * 기연 id — 관문 수치는 여기 없다. <b>config/fortune_encounters.yml 이 정본</b>이고
     * {@link Fortunes} 가 그것을 읽는다 (방문 30회 · 의뢰_완수 15건 · 이류 이하 · 사흘 연속).
     * 코드가 이름·수치를 짓지 않는다 — 등록제.
     */
    private static final String FORTUNE_ID = "chuigeolgae_master";

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
        Fortunes.Gate gate = rules.fortunes.gate(FORTUNE_ID);
        db.logEvent("탐방", "character", String.valueOf(chId), "place", gate.place(),
                Map.of("장소", gate.place()));
        storyTick(chId, null);   // B-109 — 탐방(target=장소)이 마디를 닫을 수 있다

        // 1회성 — 획득 즉시 세계에서 소모 (공유 세계 선착순, fortune rules)
        boolean consumed = db.getMeta(rules.fortunes.metaKey(FORTUNE_ID)).isPresent();
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
        // 발견 전 — 방문 적립 + 관문 판정. 수치는 전부 등록부(gate)에서 온다.
        int visits = ((Number) sheet.getOrDefault("폐사당_방문", 0)).intValue() + 1;
        sheet.put("폐사당_방문", visits);
        String realm = String.valueOf(row.get("realm"));
        boolean realmOk = rules.fortunes.realmAllowed(FORTUNE_ID, realm);
        int deeds = db.countEvents("character", String.valueOf(chId), List.of(gate.deedEvent()));
        if (visits >= gate.visits() && deeds >= gate.deeds() && realmOk) {
            sheet.put("취걸개", "시험");
            db.logEvent("기연_발견", "character", String.valueOf(chId), "fortune", FORTUNE_ID,
                    Map.of("기연", FORTUNE_ID));
            persistAndReply(event, row, sheet, gate.place(),
                    "그늘에서 쉰 목소리가 났다. \"…또 왔군.\" 처음 보는 걸인이 — 아니, 늘 있었던 걸인이 "
                            + "당신을 보고 있다. \"젊은 것이 발품은 부지런해. 밥은 먹고 다니나?\"\n"
                            + "*(내일부터 폐사당에서 그를 다시 만날 수 있을 것 같다)*");
            return;
        }
        // 걸인은 사람을 한 달 지켜보고서야 입을 연다 — 발품의 장면은 방문 회차를 따라 깊어진다.
        String scene;
        if (visits == 1) {
            scene = "무너진 사당이다. 지붕은 반이 내려앉았고, 낡은 신상 앞에 탄 향 자국만 남았다.";
        } else if (visits == 2) {
            scene = "구석에 밥그릇이 하나 있다 — 최근 것이다. 누가 여기 사는 걸까.";
        } else if (visits < gate.visits()) {
            scene = "그늘에 걸인이 앉아 있다. 눈길도 주지 않는다."
                    + (visits * 2 >= gate.visits() ? " …그러나 오늘은, 당신이 왔다 가는 것을 눈으로 좇았다." : "");
        } else if (deeds < gate.deeds()) {
            scene = "그늘에 걸인이 앉아 있다. \"…네 얼굴엔 아직 이야기가 없군.\" "
                    + "(청하현 사람들을 도운 적이 있던가 — 의뢰 게시판이 떠오른다)";
        } else {
            scene = "그늘에 걸인이 앉아 있다. 눈길도 주지 않는다. 당신의 손속이 이미 너무 무겁다 — "
                    + "가르칠 것이 남아 있지 않은 자에게는 아무 말도 하지 않는다.";
        }
        persistAndReply(event, row, sheet, gate.place(), scene);
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
            if (streak >= rules.fortunes.trialStreakDays(FORTUNE_ID)) {
                if (db.getMeta(rules.fortunes.metaKey(FORTUNE_ID)).isPresent()) {
                    // 그 사이 다른 이가 인연을 맺었다 — 공유 세계의 선착순
                    sheet.put("취걸개", "전수");   // 재시도 무의미 — 상태만 닫는다
                    body = "걸인의 자리에 빈 발우만 남아 있다. 인연은 이미 다른 손을 잡았다.";
                } else {
                    String simbeop = rules.fortunes.grantedSimbeopName(FORTUNE_ID);
                    db.setMeta(rules.fortunes.metaKey(FORTUNE_ID), String.valueOf(chId));
                    sheet.put("취걸개", "전수");
                    sheet.put("심법", simbeop);
                    List<String> ties = sheet.get("인연") instanceof List<?> l
                            ? new ArrayList<>((List<String>) l) : new ArrayList<>();
                    ties.add(rules.fortunes.grantedTie(FORTUNE_ID));
                    sheet.put("인연", ties);
                    db.logEvent("기연", "character", String.valueOf(chId), "fortune", FORTUNE_ID,
                            Map.of("기연", FORTUNE_ID, "보상", simbeop,
                                    "대가", List.of("발설_금지", "원수_상속")));
                    storyTick(chId, null);   // B-109 — 기연 획득이 마디를 닫을 수 있다
                    body = "사흘째 밥을 나누자, 걸인이 문득 자세를 고쳐 앉았다 — 등이 산처럼 펴진다.\n"
                            + "\"사흘을 나눴으면 됐다. 숨 쉬는 법부터 가르쳐 주지.\"\n"
                            + "그날 밤, 당신은 **" + simbeop + "**의 구결을 받았다. (`/혼천 운기`)\n"
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
            // ★ B6 — 숨길 수 없는 심법이면 운기 색이 곧 자백이다 (혈교는 숨을 수 없다)
            magongWitnessed(chId, String.valueOf(row.get("name")),
                    String.valueOf(sheet.get("심법")), today);
            body.append(String.format("가부좌를 틀고 한 주천을 돌렸다. 축기 **+1일치** (누적 %.0f일)\n"
                            + "내공 화후 **%s** · 내력 %d/%d (운기조식 — 가득 찼다)",
                    days, hwahuLabel(naegong), pool, pool));
        }
        String realm = promoteIfDue(sheet, String.valueOf(row.get("realm")));
        if (!realm.equals(row.get("realm"))) {
            body.append("\n💥 **돌파 — ").append(realm).append("에 올랐다** (개화한 몸 — 정식 무인이다)");
            db.logEvent("승급", "character", String.valueOf(chId), Map.of("경지", realm));
            storyTick(chId, realm);   // B-109 — 승급(realm_min)이 마디를 닫을 수 있다
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

    /**
     * ★ 성별의 산문 — 이 문이 이 성별을 받는가 (player_creation gender.gates.faction_entry).
     *
     * <p><b>이 관문은 「문파에 들어갈 때」 선다 — 「캐릭터를 만들 때」가 아니다.</b>
     * 캐릭터는 무엇이든 만들 수 있다. 다만 <b>비구니원의 문</b>은 사내를 들이지 않는다 (세계관).
     *
     * <p>등록부가 그 문파를 안 가리면 <b>아무나 들어간다</b> — 지금 등록부가 가리는 것은
     * 아미 하나뿐이다 (근거: factions.yml "여승 문파"). 화산은 가리지 않으므로 이 문은 늘 열려 있다.
     * <b>코드는 등록부에 없는 문을 스스로 닫지 않는다.</b>
     *
     * @return 막혔으면 true (호출부는 즉시 return)
     */
    private boolean genderBarred(SlashCommandInteractionEvent event,
                                 Map<String, Object> sheet, String factionId) {
        Object g = sheet.get(rules.genderSheetKey());
        String gender = g == null ? null : String.valueOf(g);   // 옛 캐릭터는 성별이 없다 — 막지 않는다
        if (rules.genderEngine.factionAllowed(gender, factionId)) {
            return false;
        }
        event.reply(rules.genderEngine.refusal()).setEphemeral(true).queue();
        return true;
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
        if ("관아".equals(dest)) {
            gwanaWalkIn(event, found.get());   // ★ 9번째 루트 — 관아는 걸어서 간다 (여정 0일)
            return;
        }
        if (!"화산".equals(dest)) {
            event.reply("그 방면의 길은 아직 열리지 않았다 — 지금 청하현에서 닿는 산문은 **화산**뿐이고, "
                    + "관아는 걸어서 반 시진이다.").setEphemeral(true).queue();
            return;
        }
        Map<String, Object> row = found.get();
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        if (genderBarred(event, sheet, HWASAN)) {
            return;   // ★ 성별의 산문 — 등록부가 화산을 안 가리므로 지금은 늘 통과한다 (아미가 열리면 여기서 선다)
        }
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
        int favor = factions.favor("orthodox", chId, today);
        Routes.Infamy infamy = rules.routes.infamy(HWASAN);
        int haomunFavor = factions.favor("haomun", chId, today);
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
        //   단 하나의 예외가 있다: **강호의 절연.** 관을 죽인 자 앞에서는 문이 닫힌다 —
        //   냉혹함이 아니라 생존이다 (한 사람 때문에 관무전쟁을 할 수는 없다).
        String branch;
        String body;
        List<Button> buttons = new ArrayList<>();
        int walkIn = rules.routes.walkInDifficultyModifier(HWASAN);
        if (disavowed(chId, today)) {
            branch = "절연_문전_폐쇄";
            putTag(sheet, "절연", today);
            body = "산문이 보이기도 전에 길이 막혔다. 화산의 제자 셋이 검을 든 채 서 있다.\n"
                    + "\"관(官)의 사람을 벤 자가 산문에 온다고?\" — 목소리에 두려움이 섞여 있다.\n"
                    + "\"…우리는 자네를 감싸지 않네. 감쌀 수 없네. **자네 하나 때문에 관과 전쟁을 할 수는 없어.**\"\n"
                    + "검끝이 흔들리지 않는다. 이들은 자네를 쫓아내러 온 게 아니라 **베러** 왔다 —\n"
                    + "관보다 먼저. 관에게 무림을 칠 구실을 주지 않으려고.\n\n"
                    + "*(강호의 절연 — 법명분 " + db.mandate(chId, today, rules.politics)
                    + " · 전 입문 루트 폐쇄 · 현상금 ×" + rules.politics.bountyMultiplier() + ")*\n"
                    + "*열려 있는 문은 하나뿐이다 — 아무도 이름을 묻지 않는 쪽. 출구: 자수 · 배상 · 진범 규명.*";
        } else if (notorious) {
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

    // ─── ★ 9번째 루트 — 관아 직행 (gwangun_entry.direct_approach) ───
    //
    // 다른 여덟 루트는 강호 **안**의 문이다. 이 문만 강호 **밖**으로 난다.
    //   ★ 세계에서 유일하게 **직행이 정공법보다 빠른** 문이다 — 관은 언제나 사람이 모자라다.
    //     하오문조차 미끼를 던지는데, 관은 그냥 **적는다.** 이름과 출신을.
    //   ★ 그리고 그것이 함정이다: 아무 대가 없이 얻은 것 같지만, **대가는 강호가 청구한다** (murim_gaze).
    //
    // 무대는 청하현 관아(county_office) — 조성기가 아직 짓지 않았다. 봇은 장소가 없어도 된다.
    // 여정 0일 (걸어서 반 시진) — 화산과 달리 오프스크린 여정이 아니다.

    private static final String GWANGUN = Routes.GWANGUN;

    @SuppressWarnings("unchecked")
    private void gwanaWalkIn(SlashCommandInteractionEvent event, Map<String, Object> row)
            throws Exception {
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        if (blockedByWound(event, sheet)) {
            return;
        }
        long chId = ((Number) row.get("id")).longValue();
        int today = db.worldDay();
        int back = ((Number) sheet.getOrDefault("출행_복귀일", -1)).intValue();
        if (today < back) {
            event.reply("아직 화산 길 위다 — 청하현 관아는 **" + (back - today)
                    + "일** 뒤에나 닿는다. (몸은 하나다)").setEphemeral(true).queue();
            return;
        }
        String realm = String.valueOf(row.get("realm"));
        Map<String, Object> tags = tagsOf(sheet);

        int gwanFavor = factions.favor("gwan_gun", chId, today);
        int haomunFavor = factions.favor("haomun", chId, today);
        Routes.Infamy infamy = rules.routes.infamy(HWASAN);   // 악명의 기준은 하나다 (루트마다 다르지 않다)
        boolean sapaKnown = haomunFavor >= infamy.haomunFavorMin()
                || hasRumor(today, 2, List.of("사파", infamy.rumorTag()));
        int poqwaeFavor = rules.routes.gwangunGateFavor("포쾌_등재", 4);

        String branch;
        String body;
        List<Button> buttons = new ArrayList<>();

        if (disavowed(chId, today)) {
            // ★ 유일하게 세계가 '문'을 주지 않는 경우 — 관을 죽인 자가 관아에 걸어 들어왔다.
            //   그러나 이것도 문이다. **자수**다 (authority_mandate.drains.자수 -10).
            branch = "즉시_포박";
            putTag(sheet, "절연", today);
            body = "관아 마당에 들어서는 순간, 포쾌 넷이 동시에 일어섰다. 아무도 소리치지 않았다.\n"
                    + "박호가 천천히 걸어 나온다. 그의 눈에 놀라움이 없다 — **기다리고 있었다.**\n"
                    + "\"…제 발로 왔군.\"\n\n"
                    + "포박은 조용했다. 관은 관을 죽인 자에게 화를 내지 않는다. **절차를 밟을 뿐이다.**\n"
                    + "*(법명분 " + db.mandate(chId, today, rules.politics)
                    + " · 강호의 절연 — 정파 토벌대가 오기 전에 관아에 든 것은, 어쩌면 운이었다)*\n"
                    + "*걸어 들어온 자에게 관은 형(刑)을 주지 살(殺)을 주지 않는다. 자수 = 법명분 "
                    + rules.politics.mandateDrain("자수") + ".*";
        } else if (tags.containsKey("수배")) {
            // ★ 관은 기록한다. 한 번 오른 이름은 안 지워진다 — 세계에서 가장 정직한 거절
            branch = "즉시_거절";
            body = "서리가 명부를 넘기다 손을 멈췄다. 그리고 당신의 얼굴을 다시 본다.\n"
                    + "\"…이름이 여기 있소.\"\n"
                    + "그것으로 끝이었다. 화도 내지 않고, 부르지도 않았다. **관은 기록한다.**\n"
                    + "돌아서는 등 뒤로, 늙은 서리가 낮게 덧붙였다.\n"
                    + "\"변경이라면 받아 주지. 거기선 이름을 안 묻네.\" — *유일하게 남은 관의 문이다.*\n\n"
                    + "*(군진 입대 — 대동 25일 · 산해관 37일 · 가욕관 46일. 돌아오지 못한다)*";
        } else if (sapaKnown) {
            // ★ 관도 쓸모를 안다. 사파를 아는 자는 사파를 잡는 데 쓴다 — 다만 언제든 버릴 수 있는 패다
            //   (하오문의 '위험한 일부터 준다'와 정확히 대칭이다. 세계는 양쪽에서 똑같이 정직하다)
            branch = "시험적_고용";
            Integer wall = rules.routes.gwangunBranchDifficulty("사파_소문");
            putTag(sheet, "감시", today);
            body = "박호가 팔짱을 낀 채 당신을 오래 보았다.\n"
                    + "\"자네 이름, 저잣거리에서 들었네. 좋은 쪽으로는 아니고.\"\n"
                    + "그런데 그가 웃는다. \"…그래서 쓸 만하겠군. 사파를 아는 자가 사파를 잡는 법이지.\"\n\n"
                    + "*(시험적 고용 — 화술 판정의 벽 +" + (wall == null ? 4 : wall)
                    + " · '감시' 태그. 관은 당신을 **언제든 버릴 수 있는 패**로 쓴다)*\n"
                    + "*하오문이 신원 미상자에게 위험한 일부터 주는 것과 정확히 대칭이다 — 세계는 양쪽에서 똑같이 정직하다.*";
            buttons.add(Button.danger("gw:enlist:" + event.getUser().getId(),
                    "그래도 등재를 청한다 (감시 하)"));
        } else if (gwanFavor >= poqwaeFavor || realmRankOf(realm) >= 0) {
            // ★ 기본형 — 받아 준다. 관은 언제나 사람이 모자라다
            branch = "즉석_등재";
            body = "관아 문은 그냥 열려 있었다. 아무도 막지 않았다.\n"
                    + "서리가 붓을 든 채 고개도 들지 않고 물었다. \"이름. 본관. 나이.\"\n"
                    + "당신이 답하자 그는 그것을 **적었다.** 그게 전부였다.\n"
                    + "\"내일 새벽 인시. 늦지 마시오.\"\n\n"
                    + "산문에서는 심사를 받고, 객잔 2층에서는 미끼를 물고, 산채에서는 얻어맞는다.\n"
                    + "**관은 그냥 적는다.** 세계에서 문턱이 가장 낮은 문이다.\n"
                    + "*…그리고 세계에서 가장 비싼 문이기도 하다.*";
            buttons.add(Button.primary("gw:enlist:" + event.getUser().getId(), "포쾌로 등재한다"));
            buttons.add(Button.secondary("gw:leave:" + event.getUser().getId(),
                    "생각해 보겠다고 하고 나온다"));
        } else {
            branch = "보증인_요구";
            putTag(sheet, "눈여겨봄", today);
            body = "\"보증인은.\"\n"
                    + "없다고 하자 서리가 붓을 내려놓았다. \"…호적은.\"\n"
                    + "그것도 없다. 서리가 처음으로 당신을 본다.\n"
                    + "\"이 사람아, 관은 이름을 적는 곳이오. 적을 이름이 없는데 어쩌란 말이오.\"\n\n"
                    + "돌아서려는데 마당을 쓸던 늙은 포쾌가 불렀다.\n"
                    + "\"객잔 한백이나 의뢰소 소연이 서 주면 되네. …아니면 마당이나 쓸든가. 밥은 먹여 주지.\"\n"
                    + "*— 문은 그렇게도 열린다 (관아 잡역 — 화산 문전 잡역의 관아판).*";
            buttons.add(Button.secondary("gw:chore:" + event.getUser().getId(), "관아 잡역을 청한다"));
        }

        // 직행 시도 자체가 소문이 된다 — ★ 강호에서 이보다 빨리 도는 이야기는 없다
        Routes.Attempt attempt = rules.routes.rumorOnAttempt(GWANGUN);
        spread(rumorGroup("관아", chId, today), attempt.text(), String.valueOf(row.get("name")), chId,
                List.of("관군", "무인"), attempt.intensityMin(), rules.initialAccuracy("직접_목격"),
                attempt.networks().get(0), today);

        db.updateCharacter(chId, sheet, ((Number) row.get("wallet")).intValue(), realm,
                "강호", "청하현");
        db.logEvent("출행", "character", String.valueOf(chId), "faction", "관군",
                Map.of("목적지", "관아", "분기", branch, "관군_favor", gwanFavor));

        var reply = event.replyEmbeds(new EmbedBuilder().setColor(INK)
                .setTitle("출행 — 청하현 관아")
                .setDescription("장터를 가로질러 반 시진. 백벽에 청기와, 돌 기단. "
                        + "마을에서 가장 단단한 집이다.\n\n" + body)
                .setFooter("관에 드는 것은 세력을 고르는 일이 아니라 층위를 바꾸는 일이다 — "
                        + "무림과 다른 편에 선다. 싸우지 않아도.")
                .build());
        if (!buttons.isEmpty()) {
            reply.addComponents(ActionRow.of(buttons));
        }
        reply.queue();
    }

    /** 경지 등급 (범인 = 0) — 관은 무공을 보지 않는다. 다만 '몸 성한 양민'인지는 본다 */
    private static int realmRankOf(String realm) {
        return Quests.realmRank(realm) - Quests.realmRank("범인");
    }

    /** 관아 앞의 선택 — 등재 / 잡역 / 물러남 (제1원칙: 어느 쪽도 '진행 불가'가 아니다) */
    @SuppressWarnings("unchecked")
    private void onGwanaChoice(ButtonInteractionEvent event, String kind, String ownerId)
            throws Exception {
        if (!event.getUser().getId().equals(ownerId)) {
            event.reply("남의 관아 마당이다 — `/혼천 출행 목적지:관아`로 직접 서라.").setEphemeral(true).queue();
            return;
        }
        var found = db.findCharacter(ownerId);
        if (found.isEmpty()) {
            event.editMessage("기록이 없다.").setComponents().queue();
            return;
        }
        Map<String, Object> row = found.get();
        long chId = ((Number) row.get("id")).longValue();
        int today = db.worldDay();
        event.getMessage().editMessageComponents().queue();   // 선택은 한 번뿐이다

        switch (kind) {
            case "enlist" -> gwangunEnlist(event, row);
            case "chore" -> {
                // 관아 잡역 — 화산 문전 잡역의 관아판 (대칭). 관군 favor 를 벌어 게이트를 채운다
                Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
                int cap = rules.routes.gwangunGateFavor("포쾌_등재", 4);
                int favor = factions.addFavor("gwan_gun", chId, 1, cap, today);
                putTag(sheet, "눈여겨봄", today);
                db.updateCharacter(chId, sheet, ((Number) row.get("wallet")).intValue(),
                        String.valueOf(row.get("realm")), "강호", "청하현");
                db.logEvent("세력_잡역", "character", String.valueOf(chId), "faction", "gwan_gun",
                        Map.of("favor", favor));
                storyTick(chId, null);   // B-109 — favor(any_entry 문턱)가 마디를 닫을 수 있다
                event.replyEmbeds(new EmbedBuilder().setColor(INK)
                        .setTitle("관아 잡역")
                        .setDescription("마당을 쓸고, 문서를 나르고, 포쾌들의 밥을 지었다.\n"
                                + "박호가 지나가다 한 번, 두 번 걸음을 늦췄다. 아무 말도 하지 않았다.\n\n"
                                + "**관군 우호 " + favor + "** (등재 문턱 " + cap + ")\n"
                                + "*보증인이 없는 자의 정공법이다 — 관은 시간을 들인 자를 기억한다.*")
                        .build()).queue();
            }
            default -> event.replyEmbeds(new EmbedBuilder().setColor(INK)
                    .setTitle("관아를 나선다")
                    .setDescription("서리가 붓을 들었다가, 다시 내려놓았다.\n"
                            + "\"…마음이 바뀌면 오시오. 관은 언제나 사람이 모자라니.\"\n\n"
                            + "*문은 닫히지 않았다. 관아의 문은 원래 닫히지 않는다.*")
                    .build()).queue();
        }
    }

    /**
     * ★ 포쾌 등재 — 이 루트의 첫 걸음이자, 강호가 등을 돌리기 시작하는 지점.
     *
     * 관군 favor 를 얻는다. 그리고 **정파 -2 · 사파 -6** (murim_gaze.by_gate.포쾌_등재).
     * 아무도 욕하지 않는다. 다만 묵삼이 말을 아끼고, 갈호는 이제 당신을 죽여도 되는 사람으로 본다.
     *
     * ★ 되돌릴 수 있다 — 포쾌·포두는 '관에 고용된 무림인'일 뿐이다 (관무불가침 예외 조항).
     *   사직하면 favor 만 남고 문파의 문은 다시 열린다. 무과를 넘는 순간 닫힌다.
     */
    private void gwangunEnlist(ButtonInteractionEvent event, Map<String, Object> row)
            throws Exception {
        long chId = ((Number) row.get("id")).longValue();
        int today = db.worldDay();
        @SuppressWarnings("unchecked")
        Map<String, Object> sheet = new LinkedHashMap<>((Map<String, Object>) row.get("sheet"));
        int need = rules.routes.gwangunGateFavor("포쾌_등재", 4);
        int gwan = factions.favor("gwan_gun", chId, today);
        int granted = factions.addFavor("gwan_gun", chId, Math.max(0, need - gwan), rules.factions.favorMax(),
                today);

        Routes.Gaze gaze = rules.routes.gaze("포쾌_등재");
        int orthodox = factions.addFavor("orthodox", chId, gaze.orthodoxFavor(), rules.factions.favorMax(),
                today);
        int unorthodox = factions.addFavor("unorthodox", chId, gaze.unorthodoxFavor(),
                rules.factions.favorMax(), today);
        factions.addFavor("haomun", chId, gaze.unorthodoxFavor(), rules.factions.favorMax(), today);
        factions.addFavor("noklim", chId, gaze.unorthodoxFavor(), rules.factions.favorMax(), today);

        putTag(sheet, "관_계급", "포쾌");
        db.updateCharacter(chId, sheet, ((Number) row.get("wallet")).intValue(),
                String.valueOf(row.get("realm")), "강호", "청하현");

        // ★ 이 소문은 막을 수 없다 — 관아 명부는 공개 문서다
        spread(rumorGroup("포쾌", chId, today), "그 아이, 관아에 들어갔다더군",
                String.valueOf(row.get("name")), chId, List.of("관군", "무인"), 1,
                rules.initialAccuracy("직접_목격"), rules.originNetwork("market"), today);

        db.logEvent("포쾌_등재", "character", String.valueOf(chId), "faction", "gwan_gun",
                Map.of("관군_favor", granted, "정파_favor", orthodox, "사파_favor", unorthodox));

        event.replyEmbeds(new EmbedBuilder().setColor(INK)
                .setTitle("포쾌 등재 — " + row.get("name"))
                .setDescription("붓이 종이를 긁는 소리. 그리고 끝이었다.\n"
                        + "포쾌 패(牌)와 월 봉록, 그리고 **수배 명단 열람권.**\n"
                        + "문파의 비급각보다 값진 것일 수도 있다 — 강호의 모든 이름이 거기 있다.\n\n"
                        + "그날 저녁 객잔에서 묵삼이 당신을 보았다. 그리고 **말을 걸지 않았다.**\n"
                        + "아무도 욕하지 않는다. 아무 일도 일어나지 않는다.\n"
                        + "다만 이제, 누구도 당신에게 등을 보이지 않는다.\n\n"
                        + "*관군 우호 **" + granted + "** (포쾌_등재 게이트 충족)\n"
                        + "정파 우호 " + gaze.orthodoxFavor() + " → **" + orthodox + "** · "
                        + "사파 우호 " + gaze.unorthodoxFavor() + " → **" + unorthodox + "***\n"
                        + "*" + (gaze.note() == null ? "" : gaze.note()) + "*")
                .setFooter("★ 되돌릴 수 있다 — 포쾌는 '관에 고용된 무림인'일 뿐이다 (관무불가침 예외). "
                        + "무과를 넘는 순간 닫힌다.")
                .build()).queue();
    }

    /**
     * 소문 조회 — 지금 **살아 있는** 도달 중에 강도 하한 + 태그 일치가 있는가.
     * 감쇠가 반영된 유효강도로 본다 (PoliticsStore.heard): 시든 소문은 문을 열지도, 닫지도 못한다.
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
            int favor = factions.addFavor("orthodox", chId, stay, cap, today);
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
            storyTick(chId, null);   // B-109 — favor(any_entry 문턱)가 마디를 닫을 수 있다
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
        // ★ 성별 보정(히든) — 남녀 모두 한 축씩 받으므로 '무재'의 기대값은 같다 (유리한 성별 없음)
        int talent = Math.max(rules.genderStat(sheet, "근력", 2),
                rules.genderStat(sheet, "민첩", 2));
        int roll = dice.nextInt(6) + 1 + dice.nextInt(6) + 1;
        JudgmentEngine.Tier tier = rules.judgment.resolve(talent, roll, resist);
        int margin = talent + roll - resist;

        EmbedBuilder judge = new EmbedBuilder().setColor(INK)
                .setTitle("판정 — 화산파 입문 심사" + (mod > 0 ? " (보증인 없음 +" + mod + ")" : ""))
                .setDescription("**실행력 " + talent + "** (무재) + 2d6 = **" + (talent + roll) + "** vs " + resist
                        + (discount > 0 ? " *(눈여겨봄 -" + discount + ")*" : "")
                        + " │ 마진 **" + (margin >= 0 ? "+" : "") + margin + "** → **" + tier.name() + "**");
        EmbedBuilder scene = new EmbedBuilder();
        if (margin >= 0) {
            putTag(sheet, "화산_심사_통과", today);
            int favor = factions.addFavor("orthodox", chId, rules.routes.gateFavorMin(HWASAN, "안면"),
                    rules.routes.choreFavorCap(HWASAN), today);
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

        // ★ 단계 5 — 관을 죽였는가. 그렇다면 오르는 것은 무림의 명분이 아니라 **관의 법명분**이다
        //   (npc_death.yml succession.<npc>.political.authority_mandate — 포두 +8 · 현령 +14)
        StringBuilder politicsNote = new StringBuilder();
        Integer mandateDelta = rules.deaths.authorityMandateOf(npcKey);
        var killerOpt = event.getOption("살해자");
        Map<String, Object> killer = killerOpt == null ? null
                : db.findCharacter(killerOpt.getAsUser().getId()).orElse(null);
        if (mandateDelta != null && killer != null) {
            long killerId = ((Number) killer.get("id")).longValue();
            int mandate = db.addMandate(killerId, mandateDelta, today, rules.politics);
            db.logEvent("법명분", "character", String.valueOf(killerId), "npc", npcKey,
                    Map.of("가산", mandateDelta, "법명분", mandate,
                            "구간", String.valueOf(rules.politics.mandateEffect(mandate))));
            int murim = murimGaugeAgainstAuthority(today);
            politicsNote.append("\n\n⚖️ **법명분 +").append(mandateDelta).append(" → ").append(mandate)
                    .append("** (").append(killer.get("name")).append(")\n*")
                    .append(rules.politics.mandateEffect(mandate)).append('*');
            if (rules.politics.disavowed(mandate, murim)) {
                boolean fresh = applyDisavowal(killerId, npcKey, today);
                politicsNote.append("\n\n🩸 **강호의 절연 (murim_disavowal)**")
                        .append(fresh ? "" : " — *이미 발동해 있다*")
                        .append("\n무림 명분은 **").append(murim).append("** — 관이 잘못한 게 없다. ")
                        .append("연합의 방아쇠가 당겨지지 않는다.\n")
                        .append("대신 **강호가 먼저 그를 친다** — 관에게 무림을 칠 구실을 주지 않으려고.\n")
                        .append("· 정파 주목 **+").append(rules.politics.disavowalOrthodoxAttention())
                        .append("** · 정파 우호 **").append(rules.politics.disavowalOrthodoxFavor())
                        .append("** (공신 이력의 하한조차 무너진다) · 사파 우호 **")
                        .append(rules.politics.disavowalUnorthodoxFavor()).append("**\n")
                        .append("· 현상금 **×").append(rules.politics.bountyMultiplier())
                        .append("** · 비호 정지 · **전 입문 루트 폐쇄 — 마교 루트만 남는다**\n")
                        .append("· 정파 토벌대(").append(rules.deaths.factionProxy("orthodox"))
                        .append(")가 관(").append(rules.deaths.factionProxy("gwan_gun"))
                        .append(")보다 먼저 온다");
            } else {
                politicsNote.append("\n*절연 문턱 ").append(rules.politics.disavowalMandateMin())
                        .append(" 미달 — 아직 강호는 등을 돌리지 않았다*");
            }
            String murimEscalation = rules.deaths.escalationMurim(npcKey);
            if (murimEscalation != null) {
                politicsNote.append("\n\n*").append(murimEscalation).append('*');
            }
        } else if (mandateDelta != null) {
            politicsNote.append("\n\n⚖️ *관(官)의 사람이다 — 법명분 +").append(mandateDelta)
                    .append(". 다만 **살해자**를 지정하지 않았다 (`살해자:@아무개`) → 장부에 이름이 없다.*");
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
                        + gapNote + "\n" + note + politicsNote + "\n\n"
                        + "· `/혼천 대화`에서 이 이름은 사라진다 (죽은 자와 말할 수 없다)\n"
                        + "· 내일 게시판이 바뀐다 — 죽음이 낳은 의뢰가 뜬다 (`/혼천 정산` → `/혼천 의뢰`)")
                .build()).queue();
    }

    /**
     * 관리자 검증 명령 — 명분을 쌓거나 끈다 (`/혼천 명분`). 12차 검증의 손잡이.
     *
     * 사건은 등록부에서만 온다 (faction_politics.yml myeongbun.inputs — 신규 사건 발명 금지).
     * ★ 정확도가 곧 배수다: 같은 사건도 괴담(29-)이면 명분이 되지 못하고,
     *   오해(30~49)면 **엉뚱한 세력에게 붙는다** (마교 이간의 통로).
     * 사건은 소문으로 세상에 나간다 — 그 소문이 각 세력의 조직 채널에 닿아야 그들이 셈을 시작한다.
     */
    private void adminMyeongbun(SlashCommandInteractionEvent event) throws Exception {
        if (event.getMember() == null || !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("서버 관리 권한이 필요하다.").setEphemeral(true).queue();
            return;
        }
        String key = optionOr(event, "사건", "");
        String drain = optionOr(event, "해소", null);
        String target = optionOr(event, "대상", null);
        int today = db.worldDay();

        if (drain != null) {
            resolveMyeongbun(event, drain, key, target, today);
            return;
        }
        if (!rules.politics.inputKeys().contains(key)) {
            event.reply("등록부에 없는 사건이다 — faction_politics.yml myeongbun.inputs 의 것만 쓴다.\n등록된 것: "
                    + String.join(" · ", rules.politics.inputKeys())).setEphemeral(true).queue();
            return;
        }
        if (target == null) {
            target = defaultTarget(key);
        }
        if (rules.politics.coalitionOf(target) == null) {
            event.reply("등록부에 없는 세력이다 — factions.yml 의 id 만 쓴다 (예: gwan_gun · magyo · noklim)")
                    .setEphemeral(true).queue();
            return;
        }
        int accuracy = event.getOption("정확도") == null ? rules.initialAccuracy("직접_목격")
                : (int) event.getOption("정확도").getAsLong();
        String band = rules.rumors.band(accuracy);
        int value = rules.politics.inputValue(key);
        List<String> tags = rules.politics.inputTags(key);
        String issueKey = key + ":" + target;

        // ★ 피해 세력 — **누가 당했는가.** 이것이 없으면 연합 계산의 절반이 죽는다:
        //   피해_당사자(-8) 는 먼저 붙고, 동맹_피해(-6) 가 따라 붙고, **원한_세력_참여(+5) 는 빠진다.**
        //   비워 두는 것도 옳다 — 백성이 죽은 사건에는 피해 '세력'이 없다. 그래서 아무도 급하지 않다.
        List<String> victims = new ArrayList<>();
        String victimOpt = optionOr(event, "피해", null);
        if (victimOpt != null && !victimOpt.isBlank()) {
            for (String v : victimOpt.split("[,·\\s]+")) {
                String id = rules.factionId(v.strip());
                if (id == null || rules.politics.coalitionOf(id) == null) {
                    event.reply("등록부에 없는 피해 세력이다 — factions.yml 의 id 만 쓴다: " + v)
                            .setEphemeral(true).queue();
                    return;
                }
                victims.add(id);
            }
        }

        // 사건은 소문을 타고 온다 — 이 소문이 곧 명분의 진위이자, 세력별 참전 시차의 원천이다
        String group = rumorGroup("명분", issueKey, today);
        List<String> rumorTags = new ArrayList<>(tags);
        rumorTags.add("정치");
        int intensity = rules.rumors.intensityByVisibility(
                value >= 8 ? "공개_다수_목격" : "소수_목격_또는_간접");
        spread(group, "《" + key.replace('_', ' ') + "》 — " + rules.factionName(target) + "의 소행이라 한다",
                rules.factionName(target), null, rumorTags, intensity, accuracy,
                rules.originNetwork("market"), today);

        MyeongbunIssue row = db.addMyeongbun(issueKey, target, victims, tags, value, accuracy, group,
                null, today, 30, rules.politics);
        Issue issue = readIssue(row, today);
        db.logEvent("명분", "world", "gm", "faction", issueKey,
                Map.of("사건", key, "대상", target, "피해", row.victims(), "사건점수", value,
                        "정확도", accuracy, "밴드", band,
                        "배수", rules.politics.accuracyMultiplier(band),
                        "명분", issue.gauge(), "태그", tags,
                        "참여", issue.coalition().participants()));
        coalitionWatch(today);

        EmbedBuilder eb = new EmbedBuilder().setColor(BLOOD)
                .setTitle("[GM] 명분 — " + key.replace('_', ' '))
                .setDescription("사건 점수 **" + value + "** × 정확도 " + accuracy + " (**" + band
                        + "** ×" + rules.politics.accuracyMultiplier(band) + ") → 명분 **"
                        + issue.gauge() + "**\n태그: " + String.join(" · ", tags)
                        + "\n대상(누가 했는가): **" + rules.factionName(target) + "**"
                        + "\n피해(누가 당했는가): " + (row.victims().isEmpty()
                                ? "*없음 — 백성이 죽었다. 세력이 당한 것이 아니다 (그래서 아무도 급하지 않다)*"
                                : "**" + row.victims().stream().map(rules::factionName)
                                        .collect(java.util.stream.Collectors.joining(" · "))
                                        + "** — 당사자는 먼저 붙고, 그 원수는 빠진다")
                        + (rules.politics.targetSwap(band)
                                ? "\n\n⚠️ **오해 밴드 — 명분이 엉뚱한 세력에게 붙었다.** "
                                        + "범인·동기·대상이 뒤바뀌었다. 진범 규명만이 이것을 되돌린다 "
                                        + "(`/혼천 명분 해소:진범_규명 대상:<진범>`)"
                                : ""));
        eb.addField("정치판", coalitionLine(issue), false);
        Map<String, Integer> burdened = db.sectBurdens(today, rules.burdenDecayEveryDays());
        if (!burdened.isEmpty()) {
            eb.addField("자파 사정 (남의 싸움에 낄 여력이 없는 자들)",
                    burdened.entrySet().stream()
                            .map(e -> rules.factionName(e.getKey()) + " +" + e.getValue())
                            .collect(java.util.stream.Collectors.joining(" · ")), false);
        }
        eb.addField("균형점", rules.politics.balancePoint()
                + " — 관은 이 상태를 만들지 않는 데 전력을 쓴다", false);
        eb.setFooter("소문이 각 세력의 조직 채널에 닿아야 그들이 셈을 시작한다 "
                + "(개방 1일 · 정파망 3일) — `/혼천 정산` 으로 날을 넘겨 보라");
        event.replyEmbeds(eb.build()).queue();
    }

    /** 명분의 기본 대상 — 관의 폭거는 관에게, 금기는 마교에게 (등록부 밖의 대상은 발명하지 않는다) */
    private String defaultTarget(String key) {
        if (key.startsWith("관")) {
            return "gwan_gun";
        }
        if (key.startsWith("마교") || key.startsWith("금기") || key.startsWith("인신공양")) {
            return "magyo";
        }
        return "noklim";   // 무림 내부 사건의 기본 가해자 — 대상은 옵션으로 덮어쓴다
    }

    /**
     * 명분을 끈다 — 관의 유일한 무기 (myeongbun.drains).
     * 가해자 처형 -6 (꼬리 자르기) · 배상 -4 · 화해 -8 · 진범 규명 = **이전(transfer)**.
     * 진범 규명만이 소멸이 아니다 — 명분은 사라지지 않고 진짜 가해자에게 옮겨 간다.
     */
    private void resolveMyeongbun(SlashCommandInteractionEvent event, String drain, String key,
                                  String target, int today) throws Exception {
        MyeongbunIssue row = null;
        for (MyeongbunIssue candidate : db.issues()) {
            if (key.isBlank() || candidate.issue().startsWith(key + ":")) {
                row = candidate;
                break;
            }
        }
        if (row == null) {
            event.reply("걸려 있는 사안이 없다 — 먼저 명분을 쌓아라.").setEphemeral(true).queue();
            return;
        }
        if ("진범_규명".equals(drain)) {
            if (target == null || rules.politics.coalitionOf(target) == null) {
                event.reply("진범을 대야 한다 — `대상:<세력 id>` (등록부의 id 만)").setEphemeral(true).queue();
                return;
            }
            int confirmed = rules.initialAccuracy("직접_목격");   // 증거는 정확도를 사실적 밴드로 올린다
            db.transferMyeongbun(row.issue(), target, confirmed, today);
            Issue after = readIssue(db.issue(row.issue()).orElseThrow(), today);
            db.logEvent("명분_해소", "world", "gm", "faction", row.issue(),
                    Map.of("수단", drain, "이전", target, "이전_전_대상", row.target(),
                            "명분", after.gauge()));
            coalitionWatch(today);
            event.replyEmbeds(new EmbedBuilder().setColor(INK)
                    .setTitle("[GM] 진범 규명 — 명분이 이전됐다")
                    .setDescription("명분은 소멸하지 않았다. **" + rules.factionName(row.target())
                            + "**에게서 **" + rules.factionName(target) + "**에게로 옮겨 갔다.\n"
                            + "정확도가 사실적 밴드(" + confirmed + ")로 확정된다 — 조사가 곧 명분의 확정이다.\n\n"
                            + coalitionLine(after)).build()).queue();
            return;
        }
        Integer value = rules.politics.drainValue(drain);
        if (value == null) {
            event.reply("등록되지 않은 해소 수단이다 — 등록된 것: "
                    + String.join(" · ", rules.politics.drainKeys())).setEphemeral(true).queue();
            return;
        }
        MyeongbunIssue updated = db.addMyeongbun(row.issue(), row.target(), row.victims(), row.tags(),
                value, row.originAccuracy(), row.originRumor(), row.trueTarget(), today, 30,
                rules.politics);
        Issue after = readIssue(updated, today);
        db.logEvent("명분_해소", "world", "gm", "faction", row.issue(),
                Map.of("수단", drain, "가산", value, "명분", after.gauge(),
                        "참여", after.coalition().participants()));
        event.replyEmbeds(new EmbedBuilder().setColor(INK)
                .setTitle("[GM] 명분 해소 — " + drain.replace('_', ' '))
                .setDescription("명분 **" + value + "** → 남은 명분 **" + after.gauge() + "**\n"
                        + "*명분은 소진된다. 그래서 관은 버티기만 해도 이긴다 — 이것이 억지의 절반이다.*\n\n"
                        + coalitionLine(after)).build()).queue();
    }

    private String optionOr(SlashCommandInteractionEvent event, String key, String fallback) {
        var opt = event.getOption(key);
        return opt == null ? fallback : opt.getAsString();
    }

    /**
     * ★ 문파의 사정 (`/혼천 사정`) — 연합의 브레이크. 13차 검증의 손잡이.
     *
     * "문파가 제 코가 석 자면 남의 싸움에 못 낀다."
     * 사정은 등록부에서만 온다 (sect_life.yml sect_state.internal_burden.sources — 신규 사정 발명 금지).
     * ★ 가장 무거운 사정(다른_전쟁_중 +4)은 여기서 얹지 않는다 — **오늘의 연합에서 읽는다.**
     *   저장할 이유가 없다: 이미 다른 판에 서 있는 자는 오늘의 판에서 보면 안다.
     */
    private void adminSectBurden(SlashCommandInteractionEvent event) throws Exception {
        if (event.getMember() == null || !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("서버 관리 권한이 필요하다.").setEphemeral(true).queue();
            return;
        }
        String faction = rules.factionId(optionOr(event, "세력", ""));
        if (faction == null || rules.politics.coalitionOf(faction) == null) {
            event.reply("등록부에 없는 세력이다 — factions.yml 의 id 나 한글명만 쓴다 "
                    + "(화산파 · 소림사 · 남궁세가 …)").setEphemeral(true).queue();
            return;
        }
        int today = db.worldDay();
        int decayDays = rules.burdenDecayEveryDays();
        boolean clear = event.getOption("해소") != null && event.getOption("해소").getAsBoolean();
        String source = optionOr(event, "사정", null);

        int now;
        if (clear) {
            now = db.addSectBurden(faction, -rules.burdenMax(), null, today, decayDays,
                    rules.burdenMax());
        } else {
            if (source == null || !rules.burdenSourceKeys().contains(source)) {
                event.reply("등록되지 않은 사정이다 — 등록된 것: "
                        + String.join(" · ", rules.burdenSourceKeys())).setEphemeral(true).queue();
                return;
            }
            now = db.addSectBurden(faction, rules.burdenSource(source), source, today, decayDays,
                    rules.burdenMax());
        }
        int baseline = rules.politics.burdenBaseline(faction);
        int total = Math.min(rules.burdenMax(), baseline + now);
        db.logEvent("문파_사정", "world", "gm", "faction", faction,
                Map.of("세력", faction, "사정", clear ? "해소" : source, "사건_부담", now,
                        "기준선", baseline, "합계", total));

        EmbedBuilder eb = new EmbedBuilder().setColor(INK)
                .setTitle("[GM] 문파의 사정 — " + rules.factionName(faction))
                .setDescription(clear
                        ? "후계가 섰다. 곳간이 찼다. 사정이 풀렸다.\n\n**사건 부담 0** "
                                + "(기준선 " + baseline + " 는 남는다 — 상시 부담은 문파의 성격이다)"
                        : "**" + source.replace('_', ' ') + "** (+" + rules.burdenSource(source)
                                + ") — 그 문파는 지금 제 앞가림이 급하다.\n\n"
                                + "사건 부담 **" + now + "** + 기준선 **" + baseline
                                + "** = 연합 임계 **+" + total + "**")
                .addField("이것이 하는 일",
                        "이 문파의 join_threshold 가 **+" + total + "** 된다.\n"
                                + "명분이 아무리 커도, **낄 여력이 없는 문파는 안 낀다.**\n"
                                + "*수치로 보이지 않는다 — '무당은 장문 자리가 비었다는데'로 보인다.*", false)
                .setFooter("사정은 " + decayDays + "일마다 -1 로 풀린다 (느리다). "
                        + "★ 다른 전쟁 중(+" + rules.burdenSource("다른_전쟁_중")
                        + ")은 저장하지 않는다 — 오늘의 연합에서 읽는다");
        event.replyEmbeds(eb.build()).queue();
    }

    // ═══ 단계 4 B — 소문망: 생성만 하던 소문을 '퍼지게' 한다 ═══
    //
    // 소문 하나를 심으면 rumors 에 여러 행이 생긴다 — 망마다 도달일과 정확도가 다르다.
    // 발원망은 오늘, 관심 일치 망은 speed_days 뒤에, 그 망의 distortion 만큼 부정확하게.
    // 감쇠는 읽을 때 계산한다 (PoliticsStore.heard). 전 과정 무주사위 — 같은 날이면 같은 소문판이다.

    /** 소문 파종 — 반환: 몇 개의 망에 닿게 되었는가 (0 = 소문 없음) */
    int spread(String group, String truth, String subject, Long subjectId, List<String> tags,
                       int intensity, int accuracy, String originNet, int day) throws Exception {
        if (intensity <= 0) {
            return 0;
        }
        List<RumorArrival> arrivals = new ArrayList<>();
        rules.rumors.arrivals(group, day, originNet, intensity, accuracy,
                        new java.util.LinkedHashSet<>(tags))
                .forEach((net, arrival) ->
                        arrivals.add(new RumorArrival(net, arrival.day(), arrival.accuracy())));
        int planted = db.spreadRumor(group, truth, subject, subjectId, tags, intensity,
                arrivals, WorldStore.PRIMARY_REGION);
        db.logEvent("소문", subjectId == null ? "world" : "character",
                subjectId == null ? "world" : String.valueOf(subjectId), "rumor", group,
                Map.of("내용", truth, "강도", intensity, "정확도", accuracy,
                        "발원망", originNet, "도달망", arrivals.stream().map(RumorArrival::network).toList()));
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
                int score = factions.addAttention(faction, chId, delta, day);
                db.logEvent("세력_인지", "world", faction, "rumor", group,
                        Map.of("세력", faction, "대상", chId, "망", network,
                                "정확도", accuracy, "가산", delta, "주목", score,
                                "단계", rules.factions.stageOf(score).name()));
            }
        }
    }

    /** 세력 반응 한 줄 — 주목 단계와 우호 등급을 함께 읽는다 (두 축은 독립이다) */
    private String standingLine(FactionService.Standing s) {
        var stage = rules.factions.stageOf(s.attention());
        var level = rules.factions.favorLevelOf(s.favor());
        return "**" + rules.factionName(s.faction()) + "** — 주목 " + s.attention()
                + " (" + stage.stage() + "단계 " + stage.name() + ") · 우호 " + s.favor()
                + " (" + level.name() + ")";
    }

    // ═══ 단계 5 — 정치층: 명분이 연합을 부르고, 관을 건드리면 강호가 등을 돌린다 ═══
    //
    // faction_reaction(주목·우호)이 '세력 대 개인'이라면 이 층은 '세력 대 세력'이다.
    //
    //   ① 명분   사건이 쌓는 0~30 게이지 (myeongbun 테이블). **소문의 정확도가 곧 명분의 배수다.**
    //   ② 연합   세력별 임계를 넘은 자만 붙는다. 저장하지 않는다 — 읽는 순간 계산한다 (결정론).
    //   ③ 절연   관을 죽이면 법명분이 오르고, 강호가 **관보다 먼저** 그를 친다. 전 입문 루트 폐쇄.
    //
    // ★ 각 세력은 **자기 조직 채널에 닿은 정확도**로 명분을 읽는다 (rumor.yml network_access):
    //   개방은 거리에서 오늘 듣고, 정파망은 사흘 뒤 조금 뒤틀린 이야기로 듣는다.
    //   → 망별 속도가 곧 참전 시차이고, 오해 밴드는 명분이 엉뚱한 세력에게 붙는 통로다.

    /** 한 사안의 오늘 모습 — 게이지(정산·배수 반영)와 지금 붙어 있는 자들 */
    record Issue(MyeongbunIssue row, int gauge, Politics.Coalition coalition) {
    }

    /**
     * 사안 하나를 오늘 기준으로 읽는다 — 감쇠 정산 + 정확도 배수 + 연합 계산.
     * 세계 게이지는 발원 정확도로, 각 세력의 게이지는 **그 세력의 조직 채널 정확도**로 잰다.
     *
     * ★ 13차 — 연합에는 브레이크가 둘 붙는다:
     *   ① victims (누가 당했는가)      → 당사자(-8)·동맹(-6)이 먼저 붙고, **원수(+5)는 빠진다**
     *   ② internal_burden (자파 사정)  → 제 코가 석 자면 못 낀다. **다른 전쟁 중(+4)이 그 심장이다**
     */
    Issue readIssue(MyeongbunIssue row, int today) throws Exception {
        return readIssue(row, today, burdens(today, row.issue()));
    }

    private Issue readIssue(MyeongbunIssue row, int today, Map<String, Integer> burdens) throws Exception {
        int raw = rules.politics.decayed(row.rawGauge(), row.tags(), row.updatedDay(), today);
        int world = rules.politics.gaugeFrom(raw, rules.rumors.band(row.originAccuracy()));

        Map<String, Integer> byFaction = new LinkedHashMap<>();
        for (String faction : rules.politics.murim()) {
            int accuracy = -1;
            for (String net : rules.rumors.politicalNetworksOf(faction,
                    rules.politics.coalitionOf(faction))) {
                accuracy = Math.max(accuracy,
                        row.originRumor() == null ? -1
                                : db.rumorAccuracyIn(row.originRumor(), net, today));
            }
            if (accuracy < 0) {
                continue;   // 조직 채널에 소식이 닿지 않았다 — 평가 자체가 없다 (formation.channel_gate)
            }
            byFaction.put(faction, rules.politics.gaugeFrom(raw, rules.rumors.band(accuracy)));
        }
        return new Issue(row, world, rules.politics.form(byFaction, row.tags(), row.target(),
                row.victims(), REGION_SECT, burdens));
    }

    /**
     * ★ 오늘 각 세력의 '사정' (자파_내부_사정 0~6) — 이 사안을 볼 때의 여력.
     *
     *   기준선 (roster.internal_burden)  — 개방 0 (지킬 게 없다) … 당가·녹림 4 (집안이 시끄럽다)
     * + 사건이 얹은 것 (sect_state 표)    — 장문 교체기 +3 · 내분 +2 · 사상자 +2 …
     * + ★ 다른 전쟁 중 (+4)              — **이미 다른 사안의 연합에 들어가 있다**
     *
     * 마지막 것이 이 축의 심장이다: **무림은 동시에 두 전쟁을 하지 못한다.**
     * 관이 두 사안을 동시에 흘려도 무림은 하나만 든다 — 억지의 숨은 절반이다.
     * 저장하지 않는다. 오늘의 판에서 읽는다 (연합에 표가 없는 것과 같은 이유).
     */
    private Map<String, Integer> burdens(int today, String exceptIssue) throws Exception {
        int decayDays = rules.burdenDecayEveryDays();
        Map<String, Integer> events = db.sectBurdens(today, decayDays);
        Set<String> atWar = new java.util.LinkedHashSet<>();
        for (MyeongbunIssue other : db.issues()) {
            if (other.issue().equals(exceptIssue)) {
                continue;
            }
            // 무한 재귀 금지: 다른 사안의 연합은 **사정 없이**(기준선만으로) 셈한다.
            // 이미 붙어 있는 자를 찾는 것이 목적이지, 그 판의 정확한 크기를 재는 것이 아니다.
            atWar.addAll(readIssue(other, today, Map.of()).coalition().participants());
        }
        int otherWar = rules.burdenSource("다른_전쟁_중");
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String faction : rules.politics.murim()) {
            out.put(faction, rules.politics.burden(faction, events.getOrDefault(faction, 0),
                    atWar.contains(faction), otherWar));
        }
        return out;
    }

    /**
     * 이 지역권의 문파 — 청하현이 닿는 산문은 화산뿐이다 (travel() 의 제약과 같은 사실).
     * 여기 사건은 화산의 앞마당 일이다 (인접_지역 -4). 곤륜·해남에게는 남의 땅이다 (거리_원거리 +2).
     */
    private static final String REGION_SECT = "hwasan";

    /** 오늘의 정치판 — 게이지가 살아 있는 사안들 (같은 세계일이면 같은 판) */
    List<Issue> politics(int today) throws Exception {
        List<Issue> out = new ArrayList<>();
        for (MyeongbunIssue row : db.issues()) {
            Issue issue = readIssue(row, today);
            if (issue.gauge() > 0 || issue.coalition().count() > 0) {
                out.add(issue);
            }
        }
        return out;
    }

    /**
     * 무림이 관에 대해 가진 명분 — 절연의 두 번째 조건 ("무림 명분 < 8").
     * 관이 잘못한 게 있으면 강호는 그를 버리지 않는다. 그래서 마교는 관을 사칭한다.
     */
    int murimGaugeAgainstAuthority(int today) throws Exception {
        int max = 0;
        for (Issue issue : politics(today)) {
            if ("authority".equals(rules.politics.coalitionOf(issue.row().target()))) {
                max = Math.max(max, issue.gauge());
            }
        }
        return max;
    }

    /** 지금 이 사람은 강호에게 버림받았는가 (법명분 >= 10 AND 무림 명분 < 8) */
    boolean disavowed(long chId, int today) throws Exception {
        return rules.politics.disavowed(db.mandate(chId, today, rules.politics),
                murimGaugeAgainstAuthority(today));
    }

    /**
     * ★ 강호의 절연 집행 — 정파 주목 +6 / 정파 우호 -8 / 사파 우호 -4.
     * breakFavor 는 공신 이력(peak_favor)까지 무효화한다 — favor floor 를 무너뜨리는 유일한 예외다.
     * 봇이 실제로 게이트에 쓰는 그룹 id(orthodox)까지 함께 친다: **입문 루트가 여기서 닫힌다.**
     * 멱등 — 같은 사건으로 두 번 등을 돌리지는 않는다 (events 원장이 문지기다).
     */
    boolean applyDisavowal(long chId, String cause, int today) throws Exception {
        if (db.eventExists("절연", String.valueOf(chId), cause)) {
            return false;
        }
        int attention = rules.politics.disavowalOrthodoxAttention();
        int orthodoxFavor = rules.politics.disavowalOrthodoxFavor();
        int unorthodoxFavor = rules.politics.disavowalUnorthodoxFavor();
        for (String faction : rules.politics.murim()) {
            if ("orthodox".equals(rules.politics.coalitionOf(faction))) {
                factions.addAttention(faction, chId, attention, today);
                factions.breakFavor(faction, chId, orthodoxFavor, today);
            } else {
                factions.breakFavor(faction, chId, unorthodoxFavor, today);
            }
        }
        // 계열 id — 입문 게이트(Routes)·게시판이 읽는 축. 여기가 닫혀야 '전 루트 폐쇄'가 참이 된다
        factions.addAttention("orthodox", chId, attention, today);
        factions.breakFavor("orthodox", chId, orthodoxFavor, today);
        factions.breakFavor("unorthodox", chId, unorthodoxFavor, today);
        db.logEvent("절연", "character", String.valueOf(chId), "faction", cause,
                Map.of("법명분", db.mandate(chId, today, rules.politics),
                        "정파_주목", attention, "정파_우호", orthodoxFavor,
                        "사파_우호", unorthodoxFavor,
                        "현상금_배수", rules.politics.bountyMultiplier(),
                        "입문", "전 루트 폐쇄 — 마교 루트만 남는다"));
        return true;
    }

    /**
     * 연합의 성립은 그 자체가 소문이다 (rumor.yml myeongbun_link.intensity_on_coalition).
     * 참여 3+ = 강도 4(지역권) · 참여 10+ = 강도 5(천하). 플레이어는 이것으로만 정치를 본다.
     * 멱등 — 같은 사안의 같은 크기는 한 번만 방(榜)이 붙는다.
     */
    int coalitionWatch(int today) throws Exception {
        int announced = 0;
        for (Issue issue : politics(today)) {
            Politics.Coalition c = issue.coalition();
            if (!rules.politics.formed(c.count())) {
                continue;
            }
            String key = issue.row().issue() + ":" + c.size();
            if (db.eventExists("연합", c.size(), key)) {
                continue;
            }
            boolean world = c.count() >= 10;
            int intensity = rules.rumors.coalitionIntensity(world ? "무림공적_선포" : "연합_성립");
            String targetName = rules.factionName(issue.row().target());
            String names = c.participants().stream().map(rules.politics::displayName)
                    .collect(java.util.stream.Collectors.joining("·"));
            spread(rumorGroup("연합", issue.row().issue(), today),
                    (world ? "천하에 방이 붙었다 — " : "무림첩이 돈다 — ") + names
                            + "이(가) " + targetName + "을(를) 두고 뭉쳤다 ("
                            + rules.politics.displayName(c.leader()) + " 주도)",
                    null, null, List.of("정치", "연합", "문파"),
                    intensity, rules.initialAccuracy("간접_전문"),
                    rules.originNetwork("market"), today);
            db.logEvent("연합", "world", c.size(), "faction", key,
                    Map.of("사안", issue.row().issue(), "대상", issue.row().target(),
                            "참여", c.participants(), "맹주", String.valueOf(c.leader()),
                            "무력", c.martial(), "관의_인식", rules.politics.gwanPerception(c.count()),
                            "맹주다툼", c.dispute()));
            announced++;
        }
        return announced;
    }

    /** 세력 관계 한 줄의 정치판 판본 — 참여 세력·맹주·관의 인식 */
    private String coalitionLine(Issue issue) {
        Politics.Coalition c = issue.coalition();
        String targetName = rules.factionName(issue.row().target());
        StringBuilder sb = new StringBuilder("**" + targetName + "** 명분 **" + issue.gauge() + "**");
        String observable = rules.politics.observable(issue.gauge());
        if (observable != null) {
            sb.append(" — *").append(observable).append('*');
        }
        if (c.count() == 0) {
            sb.append("\n*아직 아무도 나서지 않았다 (임계 미달 — 소식이 닿은 문파도 셈부터 한다)*");
            return sb.toString();
        }
        sb.append("\n**").append(c.size()).append("** (참여 ").append(c.count()).append(") — ")
                .append(c.participants().stream().map(rules.politics::displayName)
                        .collect(java.util.stream.Collectors.joining("·")));
        if (c.leader() != null) {
            sb.append("\n맹주 **").append(rules.politics.displayName(c.leader())).append("** · 무력 ")
                    .append(c.martial()).append(" · 관의 인식 **")
                    .append(rules.politics.gwanPerception(c.count())).append("**");
        }
        if (c.dispute()) {
            sb.append("\n*맹주 다툼 — 연합 명분이 주당 깎인다 (이겨도 흩어진다)*");
        }
        return sb.toString();
    }

    /**
     * 악명 — 사파 쪽에 이름이 팔리면 정파의 문이 닫힌다 (faction_entry_routes 악명_보유 분기와 동일 조건).
     * 하오문 우호가 문턱을 넘거나, 살인 태그 소문이 강도 하한 이상으로 돌고 있으면 참.
     */
    private boolean isNotorious(long chId, int today) throws Exception {
        Routes.Infamy infamy = rules.routes.infamy(HWASAN);
        return factions.favor("haomun", chId, today) >= infamy.haomunFavorMin()
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

        int con = rules.genderStat(sheet, "체력", 2);   // ★ 성별 보정(히든) — 등록부가 체력을 안 가르면 0
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
        Map<String, Object> skills = mine.get("기술") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        int wis = rules.genderStat(mine, "지혜", 2);   // ★ 성별 보정(히든) — 등록부가 지혜를 안 가르면 0
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
            int favor = factions.addFavor(faction, medicId, rules.factions.favorInput("공적_대"),
                    rules.factions.favorMax(), db.worldDay());
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
            storyTick(chId, realm);   // B-109 — 승급(realm_min)이 마디를 닫을 수 있다
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

    /**
     * ★ B-110 — 막 관문 승인 ({@code /막개전}). settleDay 와 같은 권한 문법(MANAGE_SERVER).
     * '관문_대기'(human gate 성숙)일 때만 연다 — 삼파전 진입은 살상 PvP 개방과 맞물리므로
     * 자동 진입이 금지돼 있고(설계 §4), 이 명령이 그 유일한 손이다. 되돌림 명령은 없다 (§6).
     */
    private void approveActGate(SlashCommandInteractionEvent event) throws Exception {
        if (event.getMember() == null || !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("서버 관리 권한이 필요하다.").setEphemeral(true).queue();
            return;
        }
        WorldClockEngine.Approval answer = worldClock.approve(db.worldDay());
        event.replyEmbeds(new EmbedBuilder().setColor(answer.ok() ? BLOOD : INK)
                .setTitle(answer.ok() ? "막개전 — 관문이 열렸다" : "막개전 — 열 관문이 없다")
                .setDescription(answer.body()).build()).queue();
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

        // ★ B-110 — 세계 시계: 박 발화(소문·명분·지역 델타) → 전조 → 막 진입 판정.
        //   지역 회복·세력 인지와 나란한 자정 정산의 한 단계다 (설계 §8 ①). 등록부: world_clock.yml
        String clockReport = worldClock.tick(day);
        if (!clockReport.isEmpty()) {
            report.append(clockReport);
        }

        // ★ 지역 자연 회복 — region_state.yml recovery (10일마다 기준값 50 을 향해 1).
        //   이것이 없어서 **마을을 구해도 치안이 제자리로 안 돌아왔다.** 눈금은 한 번 내려가면 영원히 내려가 있었다.
        //   다만 **민심 부채는 제외된다** — 무명의 죽음이 깎은 민심까지 되돌리면,
        //   사람을 죽여도 열흘만 기다리면 없던 일이 된다.
        int lastRecovery = Integer.parseInt(db.getMeta("지역:회복일").orElse("0"));
        if (day - lastRecovery >= rules.regions.recoveryEveryDays()) {
            int debt = Integer.parseInt(db.getMeta("지역:민심부채").orElse("0"));
            // 회복할 것이 없으면 빈 맵 — 도메인이 장부를 건드리지 않았다는 뜻이다
            Map<String, Integer> now = regions.recover(Map.of("민심", debt));
            if (!now.isEmpty()) {
                report.append("🏞️ **지역이 조금 되돌아왔다** — 치안 ").append(now.get("치안"))
                        .append(" · 경제 ").append(now.get("경제"))
                        .append(" · 민심 ").append(now.get("민심"))
                        .append(debt > 0 ? " *(민심 부채 " + debt + " — 이만큼은 돌아오지 않는다)*" : "")
                        .append('\n');
            }
            db.setMeta("지역:회복일", String.valueOf(day));
        }

        // 오늘 이 세계에 새로 닿은 이야기들
        int arrived = db.arrivalCountOn(day);
        if (arrived > 0) {
            report.append("🗣️ **소문 ").append(arrived)
                    .append("건이 새 망에 닿았다** — 먼 곳일수록 다른 이야기가 되어 도착한다.\n");
        }

        // ★ 정치 — 소문이 조직 채널에 닿으면 문파가 셈을 시작한다. 임계를 넘은 자가 붙는다.
        //   연합의 성립은 그 자체가 소문이 되어 다시 세계로 나간다 (무림첩 → 방(榜)).
        //   감쇠는 여기서 깎지 않는다 — 읽는 순간 정산한다 (명분도 주목·소문과 같은 관행).
        coalitionWatch(day);
        for (Issue issue : politics(day)) {
            Politics.Coalition c = issue.coalition();
            report.append("🏛️ **").append(rules.factionName(issue.row().target()))
                    .append("** 명분 **").append(issue.gauge()).append("**");
            if (c.count() > 0) {
                report.append(" · ").append(c.size()).append(" (참여 ").append(c.count())
                        .append(" — ").append(c.participants().stream()
                                .map(rules.politics::displayName)
                                .collect(java.util.stream.Collectors.joining("·")))
                        .append(")");
            }
            String observable = rules.politics.observable(issue.gauge());
            report.append(observable == null ? "" : "\n*" + observable + "*").append('\n');
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
        // B-109 — 비무·(duelGrant 의) 승급이 마디를 닫을 수 있다. ★ 원장은 도전자만 actor 로 적으므로
        //   비무 계수는 도전자에게만 쌓인다 — 상대는 승급 등 다른 축만 재평가된다 (Db 원장 API 한계).
        storyTick(((Number) challengerRow.get().get("id")).longValue(), null);
        storyTick(((Number) targetRow.get().get("id")).longValue(), null);

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

    /**
     * 비무 실행력 = 근력·민첩 중 높은 쪽 (베타 단순화 — 무공 숙련은 후속).
     *
     * <p>★ 성별 보정(히든)이 여기서 든다 — 사내는 근력으로, 계집은 민첩으로 민다.
     * 총합이 같으므로 <b>유리한 성별은 없다</b>: 둘 중 높은 쪽을 쓰는 이 식에서 남녀 모두 +1 을 받는다.
     * 결이 다를 뿐이다 (사내는 힘의 축이, 계집은 빠름의 축이 선다).
     */
    @SuppressWarnings("unchecked")
    private int duelExec(Map<String, Object> row) {
        Map<String, Object> sheet = (Map<String, Object>) row.get("sheet");
        int str = rules.genderStat(sheet, "근력", 2);
        int agi = rules.genderStat(sheet, "민첩", 2);
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
        // ★★ 요건 관문은 **시트 원본**을 읽는다 — 성별 보정을 태우지 않는다 (genderStat 을 지나지 않는다).
        //   태우면 사내는 근력 1 로도 통과하고 계집은 못 통과한다 = **성별이 무공을 여닫는다.**
        //   그것은 사용자가 아직 답하지 않은 질문이다 (player_creation open_questions ③ 무공 계열).
        //   보정은 **판정에만** 든다 — 문을 여닫지 않는다.
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
        // ★ 여기는 **판정**이다 — 요건 관문과 달리 성별 보정(히든)이 든다
        int str = rules.genderStat(sheet, "근력", 2);
        int roll = dice.nextInt(6) + 1 + dice.nextInt(6) + 1;
        int resist = 10;
        JudgmentEngine.Tier tier = rules.judgment.resolve(str + 2, roll, resist);
        int margin = str + 2 + roll - resist;
        db.logEvent("사사_문답", "character", String.valueOf(chId),
                Map.of("굴림", roll, "마진", margin, "등급", tier.name()));

        EmbedBuilder result = new EmbedBuilder().setColor(INK)
                .setTitle("시험 — 곽진 앞에서 권형을 밟는다")
                .setDescription("**실행력 " + (str + 2) + "** (근력) + 2d6 = **" + (str + 2 + roll) + "** vs " + resist
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
                storyTick(chId, realm);   // B-109 — 승급(realm_min)이 마디를 닫을 수 있다
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
     *
     * <p>★v3 이중 관문 (B-135 단계 4 — cultivation_v3_levels.md "레벨은 자격, 사건이 문"):
     * 기존 요건(무공·화후·마크·개화 = 사건 축)에 <b>자격 레벨 N_k</b> 가 AND 로 얹힌다.
     * {@code levels.enabled: false} 면 v2 그대로 (관문 추가 없음).
     */
    @SuppressWarnings("unchecked")
    private String promoteIfDue(Map<String, Object> sheet, String realm) {
        Map<String, Object> skills = (Map<String, Object>) sheet.get("기술");
        if ("범인".equals(realm)) {
            boolean martial = skills != null && skills.keySet().stream().anyMatch(MARTIAL_SKILLS::contains);
            double hwahu = ((Number) sheet.getOrDefault("화후_원장", 0)).doubleValue();
            return (martial && hwahu >= BASIC_TRAINING_DAYS && levelQualified(sheet, "삼류"))
                    ? "삼류" : realm;
        }
        if ("삼류".equals(realm) && skills != null) {
            // 이류 (trigger 자동): 주력 무공 숙련 2 + 실전 마크 1 (cultivation_stages·battle_marks)
            int mastery = MARTIAL_SKILLS.stream().filter(skills::containsKey)
                    .mapToInt(k -> ((Number) skills.get(k)).intValue()).max().orElse(0);
            int marks = ((Number) sheet.getOrDefault("실전_마크", 0)).intValue();
            return (mastery >= 2 && marks >= 1 && levelQualified(sheet, "이류")) ? "이류" : realm;
        }
        if ("이류".equals(realm) && skills != null) {
            // 일류 (trigger 자동 — 개화가 사실상의 관문): 주력 숙련 3 + 개화 + 실전 마크 3
            int mastery = MARTIAL_SKILLS.stream().filter(skills::containsKey)
                    .mapToInt(k -> ((Number) skills.get(k)).intValue()).max().orElse(0);
            int marks = ((Number) sheet.getOrDefault("실전_마크", 0)).intValue();
            boolean opened = "개화".equals(sheet.get("단전"));
            return (mastery >= 3 && opened && marks >= 3 && levelQualified(sheet, "일류"))
                    ? "일류" : realm;
        }
        return realm;   // 절정+ 은 벽(壁) — 깨달음 사건이 문 (자동 승급 없음)
    }

    /**
     * 자격 레벨 N_k 관문 — {@code cultivation.yml levels.qualifying_level} 이 정본.
     * 표에 없는 경지는 레벨 관문이 등록되지 않은 것이다 (기존 요건만 — 수치를 지어내지 않는다).
     */
    private boolean levelQualified(Map<String, Object> sheet, String target) {
        if (!rules.levelsEnabled) {
            return true;
        }
        Integer need = rules.qualifyingLevel.get(target);
        if (need == null || need <= 0) {
            return true;
        }
        return ((Number) sheet.getOrDefault("레벨", 1)).intValue() >= need;
    }

    private String extremeMark(int roll) {
        if (!rules.judgment.extremeDiceEnabled()) {
            return "";
        }
        return roll == 12 ? " ⚡쌍륙" : roll == 2 ? " 💥쌍일" : "";
    }

    // ═══════════════ 마크 접합 — 몸에서 쌓인 것이 장부로 온다 ═══════════════
    //
    // 【정본은 여기다】 시트를 적는 손은 **하나**여야 한다. 마크(MVT)는 규칙의 계산기이고
    // (Growth 가 curriculum → 능력치·내공·숙련을 계산한다), 그 **결과인 증분**만 다리를 건너온다.
    // 봇은 그것을 시트에 더하고, **다시 경지 캡으로 조이고**, promoteIfDue 를 굴린다.
    // 확정된 시트는 world_state.json 의 sheet 블록으로 되내려간다 — 마크는 그것으로 제 거울을 덮어쓴다.
    //
    // 내려가는 것이 **절대값**이므로 두 원장은 갈라질 수 없다. 마크가 낙관적으로 먼저 더해 둬도
    // 다음 스냅숏이 진실로 되돌린다. 이 방향성이 이 패스의 전부다.
    //
    // 【단위】 두 몸은 같은 것을 다른 단위로 적는다. 여기가 그 환산의 유일한 자리다:
    //   능력치   봇 = 정수(판정치) · 마크 = 실수(화후). → `능력치_화후`(실수)를 정본으로 두고 정수부를 능력치에 싣는다
    //   무공     봇 = 레벨 + 잔여일치(기술_수련) · 마크 = 누적일치. → 잔여에 더하고 환산표를 걷는다
    //   내공     봇 = 축기 누적일(축기_원장) · 마크 = 내공 '점'. → naegongOf 의 역함수로 되돌린다
    //   수련     봇 = 화후_원장(총 일치) — 범인→삼류의 관문. 마크는 train_days 로 보낸다

    /** 축기 실수 → 누적일 (naegongOf 의 역함수 — 환산은 한 자리에서만 산다) */
    static double naegongDays(double points) {
        double days = 0;
        int level = 0;
        while (level < (int) points) {
            days += Math.max(1, level) * YEAR_DAYS;
            level++;
        }
        return days + (points - level) * Math.max(1, level) * YEAR_DAYS;
    }

    /**
     * <b>마크에서 쌓인 것을 시트에 더한다.</b> 반환: 더한 뒤의 경지 (승급했으면 새 경지).
     *
     * <p>캡은 <b>여기서 다시 건다</b> — 마크가 알고 있던 경지가 낡았을 수 있고, 장부의 캡을 지키는 것은
     * 장부의 몫이다 ({@code player_creation.yml attribute_cap_by_realm}).
     *
     * <p>배우지 않은 무공은 자라지 않는다 — {@code 기술} 에 없는 키는 무시한다. 마크도 같은 게이트를
     * 갖고 있지만(주무공 null → 구간이 샌다) 장부가 스스로를 지키지 않으면 그것은 장부가 아니다.
     */
    @SuppressWarnings("unchecked")
    String applyCultivation(Map<String, Object> sheet, String realm, Map<String, Object> data, int today) {
        // ═══ 하루는 하루다 — **몸이 둘이어도 하루치 이상은 못 받는다** ═══
        //
        // `train_days` 가 실린 줄은 마크의 **하루 정산**이다 (수련 배분이 갈린 것).
        // 봇에도 같은 것이 있다: `/혼천 수련` 은 `수련일 == today` 면 거절한다
        // ("오늘 몫의 수련은 끝났다 — 몸은 하루치 이상을 받아들이지 못한다").
        //
        // 그 두 문이 **각자의 빗장**을 걸고 있으면, 디스코드에서 한 번 수련하고 마크에 접속해
        // 또 하루치를 받는다 — 같은 세계일에 **이틀을 산다**. 그래서 빗장을 하나로 합친다:
        // 마크의 정산도 `수련일` 을 보고, 쓰면 `수련일` 을 찍는다. **하루는 한 몸에서만 쓴다.**
        //
        // (실전 화후·마크는 이 문 밖이다 — 사냥은 수련이 아니고, 그쪽 상한은 따로 돈다.)
        boolean settling = num(data.get("train_days")) > 0;
        boolean daySpent = today == ((Number) sheet.getOrDefault("수련일", -1)).intValue();
        boolean train = settling && !daySpent;
        if (settling && daySpent) {
            System.out.println("다리 — 오늘 몫의 수련은 이미 끝났다 (봇에서 썼다): "
                    + data.get("player_name"));
        }

        // ─ 능력치 — 실수 원장(능력치_화후)이 정본, 정수부가 판정치 ─
        Map<String, Object> attrs = (Map<String, Object>) sheet.get("능력치");
        Map<String, Object> deltaAttr = train ? asMap(data.get("attr_days")) : Map.of();
        if (attrs != null && !deltaAttr.isEmpty()) {
            Map<String, Object> hwahu = new LinkedHashMap<>(
                    (Map<String, Object>) sheet.getOrDefault("능력치_화후", Map.of()));
            int cap = attrCap(realm);
            Map<String, Object> next = new LinkedHashMap<>(attrs);
            deltaAttr.forEach((attr, raw) -> {
                if (!attrs.containsKey(attr)) {
                    return;   // 등록되지 않은 능력치 축 — judgment.yml attributes 가 정본이다
                }
                // 첫 접합 — 실수 원장이 없으면 지금까지의 판정치가 곧 화후의 정수부다
                double cur = ((Number) hwahu.getOrDefault(attr,
                        ((Number) attrs.get(attr)).doubleValue())).doubleValue();
                double grown = Math.min(cap, cur + Math.max(0, num(raw)));
                hwahu.put(attr, grown);
                next.put(attr, (int) grown);
            });
            sheet.put("능력치_화후", hwahu);
            sheet.put("능력치", next);
            // ★원장 화해 (v3 단계 3) — 수련이 화후를 밀면 원장도 같이 선다 (raise-only ·
            //   이게 없으면 원장이 backfill 시점에 얼어붙어 수련이 판정·파생에 안 실린다)
            GrowthV3.backfill(sheet);
        }

        // ─ 무공 — 잔여 일치에 더하고 환산표를 걷는다 (배우지 않은 무공은 자라지 않는다) ─
        //   하루 정산(train_days)이 이미 쓰인 날이면 초식 수련분도 안 들어간다. 실전 화후는 별개 줄로 온다.
        Map<String, Object> skills = (Map<String, Object>) sheet.get("기술");
        Map<String, Object> deltaSkill = settling && !train ? Map.of() : asMap(data.get("skill_days"));
        if (skills != null && !deltaSkill.isEmpty()) {
            Map<String, Object> prog = new LinkedHashMap<>(
                    (Map<String, Object>) sheet.getOrDefault("기술_수련", Map.of()));
            Map<String, Object> next = new LinkedHashMap<>(skills);
            deltaSkill.forEach((art, raw) -> {
                if (!skills.containsKey(art)) {
                    return;   // 익히지 않은 무공 (무공 백지 = 기연 자격 — 손이 없는 것은 자라지 않는다)
                }
                double days = ((Number) prog.getOrDefault(art, 0)).doubleValue() + Math.max(0, num(raw));
                int level = ((Number) skills.getOrDefault(art, 0)).intValue();
                int cost;
                while ((cost = rules.progression.skillLevelUpDays(level)) > 0 && days >= cost) {
                    days -= cost;
                    level++;
                }
                prog.put(art, days);
                next.put(art, level);
            });
            sheet.put("기술_수련", prog);
            sheet.put("기술", next);
        }

        // ─ 내공 — 점(마크) → 축기 누적일(봇). 심법·개화 게이트는 마크가 이미 걸렀다 ─
        double naegong = train ? num(data.get("naegong")) : 0;
        if (naegong > 0 && sheet.get("심법") != null) {
            double cur = ((Number) sheet.getOrDefault("축기_원장", 0)).doubleValue();
            sheet.put("축기_원장", naegongDays(naegongOf(cur) + naegong));
        }

        // ─ 수련 총량 — 범인 → 삼류의 관문 ("기초 단련 3개월" = 90일치). 그리고 오늘의 빗장을 지른다 ─
        if (train) {
            sheet.put("화후_원장", ((Number) sheet.getOrDefault("화후_원장", 0)).doubleValue()
                    + num(data.get("train_days")));
            sheet.put("수련일", today);   // ★ 오늘 몫은 마크가 썼다 — 봇의 /혼천 수련 도 이 날은 거절한다
        }

        // ─ 마크 — 승급 요건 '실전 마크 N' 을 채우는 값 ─
        int fought = (int) num(data.get("marks_실전"));
        int deadly = (int) num(data.get("marks_사선"));
        if (fought + deadly > 0) {
            sheet.put("실전_마크",
                    ((Number) sheet.getOrDefault("실전_마크", 0)).intValue() + fought + deadly);
        }
        if (deadly > 0) {
            sheet.put("사선_마크",
                    ((Number) sheet.getOrDefault("사선_마크", 0)).intValue() + deadly);
        }

        // ─ ★성장 v3 XP (B-135 단계 4) — 처치·의뢰가 낸 경험이 레벨·포인트로 (사용자 확정 2026-07-24) ─
        int xp = (int) num(data.get("xp"));
        if (xp > 0 && rules.levelsEnabled) {
            int ups = GrowthV3.grantXp(sheet, xp, rules.xpBase, rules.xpGrowth, rules.pointsPerLevel);
            if (ups > 0) {
                System.out.println("레벨업 — " + String.valueOf(data.get("player_name")) + " → Lv"
                        + sheet.get("레벨") + " (+" + (ups * rules.pointsPerLevel) + "포인트 · 미사용 "
                        + sheet.get("미사용포인트") + ")");
            }
        }

        return promoteIfDue(sheet, realm);   // 승급은 강호가 인정하는 것이다
    }

    /**
     * <b>마크로 내려보낼 시트 한 장.</b> 여기 담기지 않는 값은 마크에 존재하지 않는다 —
     * 그래서 {@code SkillEngine.State.realm} 이 {@code "이류"} 로 박혀 있었고 능력치는 전부 0.0 이었다.
     *
     * <p>전부 <b>절대값</b>이다 (증분이 아니다). 마크는 이것으로 제 거울을 통째로 덮어쓴다.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> mvtSheet(Map<String, Object> character) {
        Map<String, Object> sheet = (Map<String, Object>) character.get("sheet");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("realm", String.valueOf(character.get("realm")));
        out.put("money", ((Number) character.getOrDefault("wallet", 0)).intValue());

        // ═══ ★★ 신분 — **마크가 내가 누구의 자식인지 알아야 한다** (2026-07-13) ═══
        //
        // 사용자: *"**마인크래프트에서도 신분이 적용**되어야 합니다",
        //          "**모든 사람이 똑같은 위치에서 똑같이 소환되는 것도 아니고**"*
        //
        // 전에는 마크가 **집안을 몰랐다** — 시트에 realm/attrs/naegong 만 실렸다. 그래서
        // 무가의 자식과 객잔집 자식이 **같은 자리에 내려 같은 대접을 받았다.**
        String house = sheet.get("집안") == null ? null
                : String.valueOf(sheet.get("집안")).replace(' ', '_');
        out.put("house", house);
        out.put("gender", sheet.get(rules.genderSheetKey()));
        out.put("left_house", Boolean.TRUE.equals(sheet.get("집안_이탈")));
        // ★ 적서 — 같은 집인데 세상이 아는 무게가 다르다 (null = 적서가 없는 집)
        out.put("birth_rank", sheet.get(rules.birthRankSheetKey()));
        // ★ 첫 자리 — 집안이 정한다 (player_creation.yml mvt_start). 앵커 **이름**만 내려보낸다:
        //   좌표는 마크가 안다 (anchors.yml — CheonghaBuilder 가 심는다). 봇이 좌표를 지어내지 않는다.
        // ★★ 첫 자리는 이제 **지역 × 집안**의 함수다 (사용자: "각 지역 분지마다 시작 위치가 정해져
        //   실제 여러 세상에서 시작하는 것처럼"). 무가는 **그 고을의** 전장, 객잔집은 **그 고을의** 객잔.
        String hRegion = null;
        String hName = null;
        String hState = null;
        try {
            Long hid = db.houseOfCharacter(((Number) character.get("id")).longValue());
            if (hid != null) {
                var h = db.house(hid);
                if (h.isPresent()) {
                    hRegion = h.get().region();
                    hName = h.get().name();
                    hState = h.get().state();
                }
            }
        } catch (Exception e) {
            System.err.println("가문을 읽지 못했다: " + e.getMessage());
        }
        out.put("house_name", hName);     // ★ 「청하현 이가(李家)」 — 마크가 내 집의 이름을 안다
        out.put("house_region", hRegion);
        out.put("house_state", hState);   // 흥·쇠·멸 (탄생에 고정 — 변하지 않는다)
        out.put("start_anchor", house == null ? null : rules.startAnchor(hRegion, house));

        // ★ 형제자매 — 같은 집에 태어난 아이들. **순서는 태어난 순**이다 (사용자 지시).
        //   파생값이다 (표를 만들지 않았다 — 마이그레이션 없음). 어휘는 문파와 다르다: 형/누나/동생.
        try {
            long me = ((Number) character.get("id")).longValue();
            out.put("kin", kinOf(me));
        } catch (Exception e) {
            System.err.println("형제 서열을 세우지 못했다: " + e.getMessage());
        }

        // 능력치 — ★v3 원장이 정본 (단계 3 · §8.9 ⑩ 파생치 계약): 마크에 내려가는 실수치 = √원장.
        //   원장은 화해(backfill raise-only)로 화후² 와 동등하므로 지금은 값이 같다 — 단계 4 에서
        //   원장이 독립 성장하면 이 한 줄이 곧 "레벨업이 몸을 민다"의 배선이 된다.
        //   원장이 없으면(이론상 backfill 뒤엔 없음) 옛 길: 화후 실수 → 능력치 정수.
        Map<String, Object> hwahu = (Map<String, Object>) sheet.get("능력치_화후");
        Map<String, Object> rawLedger = (Map<String, Object>) sheet.get("원장");
        Map<String, Object> attrs = (Map<String, Object>) sheet.get("능력치");
        Map<String, Object> outAttrs = new LinkedHashMap<>();
        if (attrs != null) {
            attrs.forEach((k, v) -> outAttrs.put(k,
                    rawLedger != null && rawLedger.get(k) instanceof Number rn
                            ? GrowthV3.realValue(rn.doubleValue())
                            : (hwahu != null && hwahu.get(k) instanceof Number n
                                    ? n.doubleValue() : ((Number) v).doubleValue())));
        }
        out.put("attrs", outAttrs);

        out.put("simbeop", sheet.get("심법"));   // null = 개화 전 (마크의 내공 과목 게이트)
        double naegong = "개화".equals(sheet.get("단전"))
                ? naegongOf(((Number) sheet.getOrDefault("축기_원장", 0)).doubleValue()) : 0.0;
        out.put("naegong", naegong);

        // 주무공 — 승급 요건('주력 무공 숙련')이 쌓이는 무공. 없으면 null (아직 아무것도 안 배웠다)
        Map<String, Object> skills = (Map<String, Object>) sheet.get("기술");
        String primary = skills == null ? null
                : MARTIAL_SKILLS.stream().filter(skills::containsKey).findFirst().orElse(null);
        out.put("primary_art", primary);

        // 누적 일치 — 마크의 원장은 '일치'가 정본이고 레벨은 환산표로 파생된다.
        // 봇은 (레벨, 잔여)로 쪼개 갖고 있으므로 여기서 되합친다: 지나온 레벨들의 비용 + 잔여.
        Map<String, Object> prog = (Map<String, Object>) sheet.getOrDefault("기술_수련", Map.of());
        Map<String, Object> outSkills = new LinkedHashMap<>();
        if (skills != null) {
            skills.forEach((art, lv) -> {
                double days = ((Number) prog.getOrDefault(art, 0)).doubleValue();
                for (int i = 0; i < ((Number) lv).intValue(); i++) {
                    days += rules.progression.skillLevelUpDays(i);
                }
                outSkills.put(art, days);
            });
        }
        out.put("skill_days", outSkills);
        out.put("marks_실전", ((Number) sheet.getOrDefault("실전_마크", 0)).intValue());
        out.put("marks_사선", ((Number) sheet.getOrDefault("사선_마크", 0)).intValue());
        return out;
    }

    /** 경지별 능력치 천장 — player_creation.yml attribute_cap_by_realm (여기서 발명하지 않는다) */
    @SuppressWarnings("unchecked")
    private int attrCap(String realm) {
        Object caps = rules.playerCreation.get("attribute_cap_by_realm");
        Object v = caps instanceof Map<?, ?> m ? ((Map<String, Object>) m).get(realm) : null;
        return v instanceof Number n ? n.intValue() : 3;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : Map.of();
    }

    private static double num(Object raw) {
        return raw instanceof Number n ? n.doubleValue() : 0.0;
    }

    // ─── 상태 객체 ───

    static final class Creation {
        final Map<String, Integer> scores = new LinkedHashMap<>();
        final List<String> answers = new ArrayList<>();
        /** 혈연 시작 — 전생의 유산·피의 장부를 짊어진다 (전생이 없으면 무의미) */
        boolean lineage;
        /**
         * ★ 성별 — player_creation.yml gender.options 의 키 (남·여). 등록부가 ask:false 면 null.
         * <b>무엇을 가르는지는 아직 세계가 모른다</b> — 지금은 기록될 뿐이다 (gender.gates 는 비어 있다).
         */
        String gender;
        /** ★ 결(結) — 유년의 기억이 좁힌 갈래 안에서 주사위가 고른 집 ({@link #rollFamily}) */
        String family;
        /** ★ 세가를 거절했는가 — 집을 나온 아이 (검도 전표도 없다. 이름 하나만 남는다) */
        boolean leftHouse;
    }

    record Character(long id, String name, String disposition, String family, String incident,
                     String bracket, int age, Map<String, Integer> attrs, int wallet) {
    }

    /** 시트 원본을 함께 들고 다닌다 — 진행 영속화의 갱신 지점 */
    record Born(Character ch, Map<String, Object> sheet) {
    }

    // ★ 옛 서장의 상태 객체(Seojang·Scene·Choice)는 **여기서 죽었다.**
    //   · Seojang(스레드별 메모리 상태) — 스레드가 없다. 진행은 **시트**에 산다 (재시작을 그냥 견딘다)
    //   · Scene·Choice(자바에 박힌 장면 뼈대) — **등록부로 갔다** (config/seojang.yml → Seojang.java)
    //   코드는 이제 이야기를 지지 않는다.

    record Beast(String name, String gap, int resist, String peltKey, String peltLabel) {
    }

    // ═══════════════ 신원 접합(身元接合) — **디스코드가 청하고, 그 몸이 게임에서 수락한다** ═══════════════
    //
    // 【옛 길 — 폐기됐다】 마크가 6자 코드를 내고, 사람이 그것을 날라 디스코드에 붙여넣었다.
    //   자물쇠는 튼튼했으나 **사람이 코드를 날랐다.** 사용자의 판정: "코드 복사는 없어져도 된다."
    //
    // 【새 길 — 두 손】 코드가 없어도 **두 신원**은 그대로 필요하다:
    //   ① 디스코드에서 청한다 (/혼천 접속 닉네임:…)  ← 디스코드가 **서명한** 신원 (위조 불가)
    //   ② 그 몸이 게임 화면에서 [잇는다] 를 누른다   ← **그 몸에 로그인한 사람** (위조 불가)
    //
    // 【★ 왜 닉네임을 아무나 대도 되는가】 닉네임은 **열쇠가 아니라 수신인**이다. 남의 닉을 대면
    //   그 사람의 화면에 물음이 뜰 뿐이고, 그가 [아니다] 를 누르거나 그냥 두면 2분 뒤 죽는다.
    //   도둑이 얻는 것은 **남의 화면에 뜬 물음 하나**뿐이다 (그리고 연타는 쿨다운이 막는다).
    //
    // 【★ 이 클래스는 절대 잇지 않는다】 여기서 하는 일은 **청을 앉히는 것**뿐이다.
    //   mvt_link.character_id 를 채우는 손은 이 파일에 **하나뿐**이고 ({@link #completeLink}),
    //   그것은 오직 Bridge 의 link_confirm(= 게임 안의 수락)만이 부른다. 그것이 이 설계의 전부다.

    /**
     * <b>청(請)을 보관하는 자리</b> — 토큰 → 그 청을 낸 인터랙션.
     *
     * <p>수락은 <b>몇 초 뒤 다른 스레드</b>(다리 폴러)로 온다. 그때 사람에게 "이어졌다"고 말해 주려면
     * 그가 청했던 그 자리를 기억하고 있어야 한다 (인터랙션 훅은 15분간 유효하다 — 청은 2분이므로 넉넉하다).
     * 훅이 없으면 DM 으로 떨어진다 ({@link #tellRequester}).
     */
    private final Map<String, net.dv8tion.jda.api.interactions.InteractionHook> linkHooks =
            new ConcurrentHashMap<>();

    /** ★ 연타 방지 — 디스코드 사용자별 마지막 청의 시각 (DB 의 쿨다운과 겹으로 건다) */
    private final Map<String, Long> linkCooldown = new ConcurrentHashMap<>();

    /** 다리 — 명부를 읽고(누가 접속해 있는가) 청을 즉시 내려보낸다. HoncheonBot 이 꽂아 준다 */
    private Bridge bridge;

    void bridge(Bridge bridge) {
        this.bridge = bridge;
    }

    private void linkAccount(SlashCommandInteractionEvent event) throws Exception {
        var opt = event.getOption("닉네임");
        askLink(event, event.getUser(), opt == null ? "" : opt.getAsString());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 안내판(案內板) — ★ **디스코드에서 명령을 치지 않게 하는 판** (discord_panel.yml)
    // ══════════════════════════════════════════════════════════════════════════
    //
    // 【왜】 사용자의 말: *"디스코드 명령을 치는 게 이상하다 생각함."* 그리고 이 프로젝트의 축:
    //   *"디스코드를 인증 서비스와 소셜로 두고, 다른 로직을 백엔드로."*
    //   **명령을 외워 치는 것은 로직의 문법이지 소셜의 문법이 아니다.**
    //
    // 【문법】 `/접합문` 과 **똑같다** — 관리자가 한 번 치면 채널에 상시 버튼이 박히고, 그 뒤로는
    //   아무도 명령을 안 친다. 새 문법을 발명하지 않았다. 이미 서 있는 선례를 **넓혔다.**
    //
    // 【★ 길은 하나】 판에 박히는 버튼은 **하나**다 (「내 자리」). 누르면 그 사람의 **상태**에 맞는
    //   버튼만 뜬다 (ephemeral — 남에게 안 보인다). 캐릭터가 없으면 [강호에 들다] 하나뿐이다.
    //
    // 【★★ 죽은 버튼 금지 · 침묵 금지】 여기 뜨는 버튼은 **지금 누를 수 있는 것뿐**이고, 못 누르는 것은
    //   **왜 못 누르는지 그 자리에서 말한다.** 버튼을 조용히 없애면 사람은 "왜 나만 안 보이지"를
    //   혼자 추측한다 — 그것이 이 저장소가 반복해서 데인 병이다 (`tools/panel_audit.py` 가 잰다).

    /** 안내판을 세운다 (관리자) — {@code /접합문} 과 같은 문법: 한 번 치면 **상시** 박힌다 */
    private void postPanel(SlashCommandInteractionEvent event) throws Exception {
        if (event.getMember() == null || !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("서버 관리 권한이 필요하다.").setEphemeral(true).queue();
            return;
        }
        if (!(event.getChannel() instanceof TextChannel channel)) {
            event.reply("일반 텍스트 채널에서 세워라.").setEphemeral(true).queue();
            return;
        }
        // ★ 등록부가 깨졌으면 **판을 세우지 않는다** (코드가 문장을 지어내지 않는다). 그리고 그렇게 말한다.
        if (rules.panelLocked()) {
            event.reply("안내판이 잠겨 있다 — 등록부(`config/discord_panel.yml`)를 못 읽었다. "
                    + "봇 로그를 보라.").setEphemeral(true).queue();
            return;
        }
        String metaKey = rules.panelChannelMeta();
        Optional<String> old = db.getMeta(metaKey);
        db.setMeta(metaKey, channel.getId());
        channel.sendMessageEmbeds(new EmbedBuilder().setColor(INK)
                        .setTitle(rules.panelBoard("title", "혼천"))
                        .setDescription(rules.panelBoard("body", ""))
                        .build())
                // ★★ 버튼이 **둘**이다. 첫 버튼이 곧 첫 걸음이다 — **처음 온 사람은 한 번만 누른다.**
                //   옛 판은 [내 자리] 하나였고, 그것을 눌러야 그 **안에서** [강호에 들다] 가 보였다.
                //   판은 공용 메시지라 사람마다 다르게 못 보인다 — 그래서 [강호에 들다] 는 **누구에게나**
                //   보이고, **이미 태어난 사람이 누르면 그렇다고 말해 준다** (startCreation — 침묵 금지).
                .addComponents(ActionRow.of(
                        Button.primary("np:start", rules.panelBoard("start_label", "강호에 들다")),
                        Button.secondary("np:me", rules.panelBoard("button_label", "내 자리")),
                        // ★ B-117 — 사용자 실측(2026-07-14): "접속이 명령어 타이핑이다."  잇기도 **버튼**이다.
                        //   np:link → onPanel → openLinkModal → lk:submit 모달 → askLink —
                        //   `/혼천 접속` 과 **같은 파이프**다 (새 검증을 발명하지 않았다. 자물쇠는 게임 안).
                        Button.primary("np:link", rules.panelBoard("link_label", "마크와 잇기"))))
                .queue();
        String say = rules.panelBoard("posted", "안내판이 섰다.");
        // ★ 판이 둘이면 하나가 낡는다 — 옛 자리를 **말해 준다** (조용히 옮기지 않는다)
        if (old.isPresent() && !old.get().equals(channel.getId())) {
            say += "\n\n" + rules.panelBoard("moved", "옛 판은 {old} 에 남아 있다 — 지워라.")
                    .replace("{old}", "<#" + old.get() + ">");
        }
        event.reply(say).setEphemeral(true).queue();
    }

    /** 안내판의 버튼 — {@code np:<무엇>[:<인자>]} */
    private void onPanel(ButtonInteractionEvent event, String[] id) throws Exception {
        switch (id.length > 1 ? id[1] : "me") {
            case "me" -> myPlace(event);
            case "start" -> startCreation(event);      // ← /혼천 시작 과 **같은 문**
            case "sheet" -> showSheet(event);          // ← /혼천 정보 와 같은 문
            case "link" -> openLinkModal(event);       // ← 접합문의 버튼과 **같은 창** (자물쇠는 게임 안)
            case "unlink" -> unlinkAccount(event);     // ← /혼천 접속해제 와 같은 문
            case "reset" -> resetPick(event);
            case "rs" -> resetConfirm(event, id[2], event.getUser(), event.getUser());
            // ★ 모르는 버튼에 **침묵하지 않는다** — 낡은 판을 누른 사람에게 그렇다고 말한다
            default -> event.reply("이 버튼은 이 봇이 모르는 것이다 (낡은 안내판일 수 있다). "
                    + "관리자에게 `/안내판` 을 다시 세워 달라고 하라.").setEphemeral(true).queue();
        }
    }

    /**
     * <b>내 자리</b> — 누른 사람에게만 보이는 판. <b>지금 누를 수 있는 것만</b> 뜬다.
     *
     * <p>상태는 다섯이다 (등록부 {@code panel.me.states} 가 이름을 정한다):
     * {@code 없음 · 서장_미접합 · 서장_접합 · 강호_미접합 · 강호_접합}.
     *
     * <p><b>★ 등록부가 모르는 상태를 코드가 지어내지 않는다.</b> 그런 상태에 닿으면 <b>그렇다고 말한다</b>
     * (빈 판을 내밀지 않는다 — 침묵이 가장 나쁜 대답이다).
     */
    private void myPlace(ButtonInteractionEvent event) throws Exception {
        if (rules.panelLocked()) {
            event.reply("안내판이 잠겨 있다 — 등록부(`config/discord_panel.yml`)를 못 읽었다.")
                    .setEphemeral(true).queue();
            return;
        }
        var found = db.findCharacter(event.getUser().getId());
        boolean has = found.isPresent();
        String name = has ? String.valueOf(found.get().get("name")) : "";
        Optional<String> mc = has
                ? db.mcOfCharacter(((Number) found.get().get("id")).longValue())
                : Optional.empty();
        // ★ 사람에게 uuid 를 보여 주지 않는다 — 그 몸이 마크에서 쓰는 **이름**을 보여 준다
        String body = mc.isPresent() ? db.mcName(mc.get()).orElse(mc.get()) : "";
        boolean debuted = has && "강호".equals(found.get().get("status"));
        String state = !has ? "없음"
                : (debuted ? "강호" : "서장") + (mc.isPresent() ? "_접합" : "_미접합");

        if (rules.panelStateSay(state).isEmpty()) {
            event.reply("안내판의 등록부에 이 상태가 없다: **" + state + "**\n"
                            + "`config/discord_panel.yml` → `panel.me.states` 를 보라. "
                            + "*(코드는 말을 지어내지 않는다 — 그래서 여기서 멈춘다)*")
                    .setEphemeral(true).queue();
            return;
        }

        // ★ 지금의 세계 — 마크가 살아 있는가 · 초기화가 열려 있는가. **버튼은 사실을 따라간다**
        boolean worldUp = bridge != null && bridge.worldOnline();
        boolean resetUp = reset != null && !reset.locked();

        List<String> want = rules.panelStateButtons(state);
        List<Button> live = new ArrayList<>();
        StringBuilder locked = new StringBuilder();
        for (String key : rules.panelButtonKeys()) {
            String why = whyLocked(key, want, has, mc.isPresent(), worldUp, resetUp);
            if (why == null) {
                live.add(panelButton(key));
            } else {
                locked.append("· **").append(rules.panelButtonLabel(key)).append("** — ")
                        .append(fill(why, name, body)).append('\n');
            }
        }

        String desc = fill(rules.panelStateSay(state), name, body);
        // ★ 옛 길을 **숨기지 않는다** — 아직 디스코드에도 남아 있다는 사실을 그대로 말한다 (B-079)
        if (debuted) {
            desc += "\n\n" + rules.panelMe("legacy_note", "");
        }
        if (event.getMember() != null && event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            desc += "\n\n" + rules.panelAdminNote();
        }
        EmbedBuilder eb = new EmbedBuilder().setColor(INK)
                .setTitle(rules.panelMe("title", "내 자리"))
                .setDescription(desc);
        if (!locked.isEmpty()) {
            eb.addField(rules.panelLock("header", "지금 못 누르는 것"), locked.toString(), false);
        }
        var reply = event.replyEmbeds(eb.build()).setEphemeral(true);
        if (!live.isEmpty()) {
            reply = reply.addComponents(ActionRow.of(live));
        }
        reply.queue();
    }

    /**
     * <b>왜 못 누르는가</b> — 누를 수 있으면 {@code null}.
     *
     * <p>등록부는 상태마다 <b>뜰 버튼</b>을 적어 둔다. 그 목록에 없는 버튼은 <b>이유가 있어서</b> 없는 것이고,
     * 그 이유를 여기서 고른다 (문장 자체는 등록부 {@code panel.locks} 에 있다 — 코드가 짓지 않는다).
     * 목록에 <b>있어도</b> 세계가 지금 못 받는 것이 있다: 마크가 꺼졌으면 청을 보낼 화면이 없고,
     * 등록부가 깨졌으면 되돌릴 수 없다.
     */
    private String whyLocked(String key, List<String> want, boolean hasCharacter, boolean linked,
                             boolean worldUp, boolean resetUp) {
        if (!want.contains(key)) {
            return switch (key) {
                case "start" -> rules.panelLock("이미_있다", "이미 캐릭터가 있다.");
                case "link" -> linked ? rules.panelLock("이미_이어짐", "이미 이어져 있다.")
                        : rules.panelLock("캐릭터_없음", "캐릭터가 없다.");
                case "unlink" -> hasCharacter ? rules.panelLock("몸이_없다", "이어진 몸이 없다.")
                        : rules.panelLock("캐릭터_없음", "캐릭터가 없다.");
                default -> rules.panelLock("캐릭터_없음", "캐릭터가 없다.");
            };
        }
        if ("link".equals(key) && !worldUp) {
            return rules.panelLock("마크_꺼짐", "마크 서버가 꺼져 있다.");
        }
        if ("reset".equals(key) && !resetUp) {
            return rules.panelLock("초기화_잠김", "초기화가 잠겨 있다.");
        }
        return null;
    }

    /** 버튼 하나 — 이름도 결(style)도 등록부가 정한다 */
    private Button panelButton(String key) {
        String label = rules.panelButtonLabel(key);
        return switch (rules.panelButtonStyle(key)) {
            case "primary" -> Button.primary("np:" + key, label);
            case "danger" -> Button.danger("np:" + key, label);
            default -> Button.secondary("np:" + key, label);
        };
    }

    private static String fill(String text, String name, String body) {
        return text.replace("{name}", name).replace("{body}", body);
    }

    /**
     * 되돌리기 — 범위를 고른다. <b>범위도 그 뜻도 {@code config/reset.yml} 이 정한다</b>
     * (코드가 "접합·캐릭터·전부"를 알고 있지 않다 — 그러면 등록부가 둘이 된다).
     *
     * <p>여기서도 <b>아무것도 안 지운다.</b> 고르면 {@link #resetConfirm} 이 한 번 더 묻고,
     * 지우는 손은 그 다음의 [되돌린다] 하나뿐이다 — 슬래시({@code /초기화})와 <b>같은 문</b>이다.
     */
    private void resetPick(ButtonInteractionEvent event) throws Exception {
        Reset r = this.reset;
        if (r == null || r.locked()) {
            event.reply(rules.panelLock("초기화_잠김", "초기화가 잠겨 있다.")
                    + (r == null ? "" : "\n> " + r.fault())).setEphemeral(true).queue();
            return;
        }
        List<Button> picks = new ArrayList<>();
        StringBuilder says = new StringBuilder();
        for (String scope : r.scopeNames()) {
            picks.add(Button.danger("np:rs:" + scope, scope));
            says.append("· **").append(scope).append("** — ").append(r.say(scope)).append('\n');
        }
        picks.add(Button.secondary("rx:-:-:-", rules.panelReset("cancel_label", "그만둔다")));
        event.replyEmbeds(new EmbedBuilder().setColor(BLOOD)
                        .setTitle(rules.panelReset("title", "처음부터 다시"))
                        .setDescription(rules.panelReset("body", "되돌릴 수 없다.")
                                + "\n\n" + says)
                        .build())
                .addComponents(ActionRow.of(picks))
                .setEphemeral(true).queue();
    }

    // ─── ★ 접합의 손잡이 — id 를 **한 곳에** 박는다 (조립하는 손과 알아보는 손이 같은 상수를 쥔다) ───
    //   문은 셋(접합문 · 안내판 np:link · 생성 완료 이정표)이지만 **창은 하나**(lk:submit)고,
    //   창이 흘러드는 파이프도 하나(askLink — `/혼천 접속` 과 같은 것)다. B-117.

    /** 접합 창을 여는 버튼의 id — {@code onButtonInteraction} 의 {@code case "lk"} 가 받는다 */
    static final String LINK_OPEN_ID = "lk:open";
    /** 접합 모달의 id — {@link #linkModal} 이 만들고 {@link #onModalInteraction} 이 이 값으로 알아본다 */
    static final String LINK_MODAL_ID = "lk:submit";
    /** 모달의 닉네임 칸 id — 만드는 쪽과 읽는 쪽({@code event.getValue})이 같은 이름을 쥔다 */
    static final String LINK_NICK_INPUT = "닉네임";

    /** 접합문의 버튼 — 라벨은 등록부({@code world_bridge.yml identity.gate.discord.button_label}) */
    static Button gateLinkButton(Rules rules) {
        return Button.primary(LINK_OPEN_ID, rules.gateText("button_label", "마크의 몸을 잇는다"));
    }

    /**
     * ★ B-117 — 생성 완료 이정표에 싣는 [마크와 잇기].
     * 라벨은 등록부({@code seojang.yml signpost.link_label}) — 이정표 본문이 이 라벨을 가리킨다.
     */
    static Button signpostLinkButton(Rules rules) {
        return Button.primary(LINK_OPEN_ID, rules.seojang.signpost("link_label", "마크와 잇기"));
    }

    /**
     * 접합 모달 — 닉네임 <b>한 칸</b>. 문장은 전부 등록부({@code gate.discord})가 진다.
     * <b>여기서는 아무것도 확정하지 않는다</b> — 제출은 {@link #onModalInteraction} 을 거쳐
     * {@link #askLink} 로 흐른다. 슬래시({@code /혼천 접속})와 <b>같은 파이프</b>다.
     */
    static Modal linkModal(Rules rules) {
        TextInput nick = TextInput.create(LINK_NICK_INPUT, rules.gateText("modal_field", "마크 닉네임"),
                        TextInputStyle.SHORT)
                .setPlaceholder(rules.gateText("modal_hint", "지금 마크에 접속해 있는 그대의 이름"))
                .setMinLength(3)
                .setMaxLength(16)
                .setRequired(true)
                .build();
        return Modal.create(LINK_MODAL_ID, rules.gateText("modal_title", "접합"))
                .addComponents(ActionRow.of(nick)).build();
    }

    /** 접속의 문을 세운다 (관리자) — 이 채널이 마크의 [혼천 접속] 클릭이 닿을 자리가 된다 */
    private void postLinkGate(SlashCommandInteractionEvent event) throws Exception {
        if (event.getMember() == null || !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
            event.reply("서버 관리 권한이 필요하다.").setEphemeral(true).queue();
            return;
        }
        if (!(event.getChannel() instanceof TextChannel channel)) {
            event.reply("일반 텍스트 채널에서 세워라.").setEphemeral(true).queue();
            return;
        }
        db.setMeta(rules.gateMetaKey("channel_meta", GATE_CHANNEL_KEY), channel.getId());
        db.setMeta(rules.gateMetaKey("guild_meta", GATE_GUILD_KEY), channel.getGuild().getId());
        channel.sendMessageEmbeds(new EmbedBuilder().setColor(INK)
                        .setTitle(rules.gateText("title", "접합의 문"))
                        .setDescription(rules.gateText("body", "마크에서 `/혼천 접속` 을 쳐서 코드를 받아라."))
                        .build())
                .addComponents(ActionRow.of(gateLinkButton(rules)))
                .queue();
        // ★ 초대 링크가 없으면 관리자에게 그 자리에서 알린다 — 문은 섰지만 담이 없다
        String posted = rules.gateText("gate_posted", "이 채널이 접속의 문으로 섰다.");
        if (rules.gateInviteUrl() == null) {
            posted += "\n\n⚠ **초대 링크가 비어 있다.** 이 디스코드는 공개가 아니다 — 서버 밖의 사람은"
                    + " 마크에서 [혼천 접속] 을 눌러도 **아무 데도 못 간다.**\n"
                    + "`config/world_bridge.yml` → `identity.gate.invite_url` 에"
                    + " **만료 없음 · 무제한** 초대(`https://discord.gg/...`)를 넣고 봇을 다시 띄워라.";
        }
        event.reply(posted).setEphemeral(true).queue();
    }

    /**
     * 문을 눌렀다 — <b>닉네임</b> 한 칸짜리 창을 연다. <b>여기서는 아무것도 확정하지 않는다</b>
     * (버튼은 공개다. 누가 눌러도 좋다 — 버튼은 아무것도 담고 있지 않다).
     */
    private void openLinkModal(ButtonInteractionEvent event) {
        event.replyModal(linkModal(rules)).queue();
    }

    /**
     * 창에 적은 닉네임이 왔다 — 슬래시 명령과 <b>같은 신원</b>이고 같은 {@link #askLink} 를 지난다.
     * 문은 손잡이일 뿐이다. <b>자물쇠는 게임 안에 있다.</b>
     */
    @Override
    public void onModalInteraction(net.dv8tion.jda.api.events.interaction.ModalInteractionEvent event) {
        try {
            if (!LINK_MODAL_ID.equals(event.getModalId())) {
                return;
            }
            var value = event.getValue(LINK_NICK_INPUT);
            askLink(event, event.getUser(), value == null ? "" : value.getAsString());
        } catch (Exception e) {
            event.reply("오류: " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    /**
     * <b>청(請)을 보낸다 — 그리고 그것이 전부다.</b> 슬래시({@code /혼천 접속 닉네임:…})와 모달이
     * 둘 다 여기로 온다. <b>이 함수는 아무것도 잇지 않는다</b> (잇는 손은 {@link #completeLink} 하나뿐이고,
     * 그것은 게임 안의 수락만이 부른다).
     *
     * <p>문지기는 넷이다:
     * <ol>
     *   <li><b>신원</b>  캐릭터가 있고 출도했는가 (디스코드가 서명한 {@code user} — 대신 못 눌러 준다)</li>
     *   <li><b>연타</b>  쿨다운 (한 사람이, 그리고 한 몸에게 — 화면에 물음을 도배할 수 없다)</li>
     *   <li><b>재적</b>  그 이름이 <b>지금 마크에 있는가</b> — 없으면 물어볼 화면이 없다.
     *       ★ 없는 이름과 오프라인은 <b>같은 말</b>로 거절한다 ({@code reveal_roster: false} —
     *       이 문이 "누가 접속했나"를 캐는 도구가 되면 안 된다)</li>
     *   <li><b>1:1</b>   그 몸/그 이름이 이미 이어져 있지 않은가 (수락 시점에 <b>다시</b> 본다)</li>
     * </ol>
     *
     * @param user <b>디스코드가 서명한 신원</b> — 인터랙션의 주인. 위조할 수 없는 값이다
     */
    private void askLink(net.dv8tion.jda.api.interactions.callbacks.IReplyCallback event,
                         User user, String raw) throws Exception {
        var found = db.findCharacter(user.getId());
        if (found.isEmpty()) {
            // ★ B-117 — 여기서도 명령을 가리키지 않는다. 문장은 등록부(panel.locks.캐릭터_없음)의 것이다
            event.reply(user.getEffectiveName() + " — "
                            + rules.panelLock("캐릭터_없음", "캐릭터가 없다 — 먼저 **[강호에 들다]**."))
                    .setEphemeral(true).queue();
            return;
        }
        // ═══ ★★ 여기가 **자물쇠가 제 문을 잠그고 있던 자리**다 (2026-07-13) ═══
        //
        // 【옛 빗장】 `if (!"강호".equals(status))` — **출도한 자만 접합할 수 있다.**
        //   그때는 옳았다: 서장이 디스코드에서 끝나야 마크로 나왔으니까.
        //
        // 【그런데 서장이 마크로 갔다】 그러면 이 빗장은 **순환 교착**이 된다:
        //     서장을 하려면 → 마크에 접합해야 하고
        //     접합하려면   → 출도해야 하고
        //     출도하려면   → 서장을 끝내야 한다   ← ★ 시작할 방법이 없다
        //
        // 그래서 **서장 중에도 접합을 허락한다.** 접합은 「몸과 이름을 잇는 일」이지
        // 「강호에 나서는 일」이 아니다 — 둘은 다른 문이다.
        //
        // ★ **출도의 문은 그대로 잠겨 있다**: 사냥·비무·의뢰·대화는 여전히 requireDebuted 가 막는다
        //   (아래 1152줄과 같은 검사). 서장 중인 몸은 **접합해서 책을 받을 수 있을 뿐**이다.
        if ("사망".equals(found.get().get("status"))) {
            event.reply(user.getEffectiveName() + " — 그 이름은 이제 강호에 없다.")
                    .setEphemeral(true).queue();
            return;
        }
        Map<String, Object> row = found.get();
        long chId = ((Number) row.get("id")).longValue();
        String name = String.valueOf(row.get("name"));
        int today = db.worldDay();
        long now = System.currentTimeMillis();
        String nick = raw.strip();

        if (bridge == null) {
            event.reply("세계 다리가 서지 않았다 — 관리자에게 알려라.").setEphemeral(true).queue();
            return;
        }
        // ① 이 이름에 이미 몸이 붙어 있는가 (1:1 — 먼저 끊어야 다시 잇는다. 도난 방지의 반쪽)
        var mine = db.mcOfCharacter(chId);
        if (mine.isPresent()) {
            event.reply("이 이름에는 이미 몸이 붙어 있다. 먼저 `/혼천 접속해제` 를 하라.")
                    .setEphemeral(true).queue();
            return;
        }
        // ② 연타 — 한 사람이 1분에 한 번 (남의 화면에 물음을 도배하지 못하게)
        int cooldown = rules.linkCooldownSeconds();
        Long last = linkCooldown.get(user.getId());
        if (last != null && now - last < cooldown * 1000L) {
            long wait = (cooldown * 1000L - (now - last) + 999) / 1000;
            event.reply("조금 전에 청했다 — " + wait + "초 뒤에 다시 청하라.").setEphemeral(true).queue();
            return;
        }
        // ③ 이름 꼴 — 마크의 이름은 3~16자 영숫자·밑줄이다. 그 밖의 것은 **볼 것도 없이** 같은 말로 거절한다
        //   (이 클래스의 Character 는 **캐릭터 레코드**다 — java.lang.Character 가 아니다. 그래서 정규식)
        boolean shaped = nick.matches("[A-Za-z0-9_]{3,16}");
        var who = shaped ? bridge.online(nick) : Optional.<Bridge.Online>empty();
        if (who.isEmpty()) {
            // ★★ 없는 이름 · 오프라인 · 오타 — **전부 같은 말이다.** 여기서 갈라 말하면
            //    이 문이 '누가 접속해 있는지' 캐는 도구가 된다 (identity.reveal_roster: false).
            //    다만 **마크 서버 자체가 꺼진 것**은 다른 말이다 — 그것은 그 사람의 잘못이 아니다.
            String why = bridge.worldOnline()
                    ? "**" + nick + "** — 그 이름은 지금 강호에 없다. 마크에 접속한 뒤 다시 청하라."
                    : "지금 강호의 문이 닫혀 있다 (마크 서버가 꺼져 있다). 서버가 열리면 다시 청하라.";
            event.reply(why).setEphemeral(true).queue();
            return;
        }
        Bridge.Online body = who.get();
        // ④ 그 몸이 이미 남에게 이어져 있는가 — 산 사람의 몸은 뺏지 못한다
        //    (죽은 자의 몸은 놓아준다 — 새 삶은 같은 몸으로 시작한다. identity.on_character_death)
        var owner = db.rawCharacterOfMc(body.uuid());
        if (owner.isPresent() && owner.get() != chId) {
            var other = db.findCharacterById(owner.get());
            if (other.isPresent() && !"사망".equals(other.get().get("status"))) {
                event.reply("그 몸은 이미 다른 이름에 이어져 있다. 그쪽에서 `/혼천 접속해제` 를 해야 한다.")
                        .setEphemeral(true).queue();
                return;
            }
        }
        // ⑤ 그 몸이 방금 다른 청을 받았는가 — 받는 쪽의 연타 방지 (한 화면을 물음으로 덮지 못하게)
        var recent = db.lastLinkRequestTo(body.uuid());
        if (recent.isPresent() && !recent.get().pending()
                && now - recent.get().issuedAt() < cooldown * 1000L
                && recent.get().characterId() != chId) {
            event.reply("그 몸은 방금 다른 청을 받았다 — 잠시 뒤에 다시 청하라.")
                    .setEphemeral(true).queue();
            return;
        }

        // ★ 청을 앉힌다 — **여기서는 아무것도 이어지지 않는다.** 물음은 그 몸의 화면으로 간다.
        //   같은 몸/같은 캐릭터의 옛 청은 여기서 함께 폐기된다 (one_pending_per_body).
        String token = token();
        int ttl = rules.linkTtlSeconds();
        db.pendLinkRequest(token, body.uuid(), body.name(), chId, user.getId(),
                user.getEffectiveName(), now, now + ttl * 1000L);
        db.logEvent("접합_청", "character", String.valueOf(chId), "mvt", body.uuid(),
                Map.of("마크이름", body.name(), "디스코드", user.getEffectiveName(), "토큰", token));
        linkCooldown.put(user.getId(), now);
        bridge.publishLinkRequests();   // ★ 즉시 — 사람이 화면 앞에서 기다리고 있다 (스냅숏 20초를 기다리지 않는다)

        event.replyEmbeds(new EmbedBuilder().setColor(INK)
                        .setTitle("청을 강호로 보냈다 — 아직 이어지지 않았다")
                        .setDescription("**" + body.name() + "** 의 게임 화면에 물음이 떴다.\n"
                                + "그 몸의 주인이 **[잇는다]** 를 눌러야 이어진다 — "
                                + "**닉네임만으로는 아무것도 이어지지 않는다.**\n\n"
                                + "*(그대가 그 몸의 주인이라면 마크 창으로 돌아가 눌러라. "
                                + ttl + "초 뒤에 이 청은 죽는다)*")
                        .build()).setEphemeral(true)
                .queue(hook -> linkHooks.put(token, hook), err -> { });
    }

    /** 청의 토큰 — <b>열쇠가 아니다</b> (지목일 뿐. 수락할 수 있는 손은 그 몸 하나뿐이다) */
    private String token() {
        String alphabet = rules.linkTokenAlphabet();
        var rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rules.linkTokenLength(); i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** 장부의 이름 — 마크 화면에 "이 몸을 **누구**에게 잇는가" 를 보여 주려면 필요하다 */
    String characterNameOf(long chId) throws Exception {
        return db.findCharacterById(chId).map(c -> String.valueOf(c.get("name"))).orElse("?");
    }

    /**
     * ★★ <b>접합 — 몸과 이름이 이어진다. 이 함수가 이 파일에서 {@code linkMvt} 를 부르는 유일한 손이다.</b>
     *
     * <p>부르는 자는 하나뿐이다: {@link Bridge}의 {@code link_confirm} — 즉 <b>그 몸의 게임 화면에서
     * 누른 [잇는다]</b>. 디스코드의 어떤 명령도, 어떤 버튼도 여기로 곧장 올 수 없다.
     * <b>그것이 도용을 막는 전부다.</b>
     *
     * <p>TTL·1회성·1:1·감사·혈채 병합은 여기까지 오는 길(Bridge.linkConfirm)과 이 함수가 나눠 진다.
     */
    void completeLink(LinkRequest req, String mcName, int today) throws Exception {
        long chId = req.characterId();
        var found = db.findCharacterById(chId);
        if (found.isEmpty() || "사망".equals(found.get().get("status"))) {
            tellRequester(req, "그 이름은 이제 강호에 없다 — 접합하지 못했다.");
            return;
        }
        String name = String.valueOf(found.get().get("name"));

        // ★ 마지막 문지기 — 청이 뜬 2분 사이에 세상이 바뀌었을 수 있다 (그 사이 남이 그 몸을 이었을 수도)
        var owner = db.rawCharacterOfMc(req.mcUuid());
        if (owner.isPresent() && owner.get() != chId) {
            var other = db.findCharacterById(owner.get());
            if (other.isPresent() && !"사망".equals(other.get().get("status"))) {
                tellRequester(req, "그 몸은 이미 다른 이름에 이어져 있다 — 접합하지 못했다.");
                return;
            }
        }
        var mine = db.mcOfCharacter(chId);
        if (mine.isPresent() && !mine.get().equals(req.mcUuid())) {
            tellRequester(req, "이 이름에는 이미 다른 몸이 붙어 있다 — 접합하지 못했다.");
            return;
        }

        db.linkMvt(req.mcUuid(), mcName, chId);
        // ★ 병합 — 이름 없이 쌓인 장부가 한 사람의 이름으로 합산된다.
        //   그 전까지 세계는 열 개의 사고를 보았고, 그 후로 세계는 한 마리의 짐승을 본다.
        BloodDebtEntry merged = db.mergeBloodDebt("mc:" + req.mcUuid(), chId, today);
        db.logEvent("접합", "character", String.valueOf(chId), "mvt", req.mcUuid(),
                Map.of("마크이름", mcName, "디스코드", req.discordName(), "경로", "게임내_수락",
                        "토큰", req.token(), "병합_혈채_건수", merged.kills()));
        bloodDebtLadder(chId, name, null, today);   // 병합으로 칸을 넘었을 수 있다 — 세계가 이제 그를 본다

        // ★ 서장 중이었다면 **바로 지금** 책이 손에 간다 (다음 바퀴를 기다리지 않는다 —
        //   사람은 방금 [잇는다] 를 누르고 화면을 보고 있다)
        boolean inSeojang = "서장".equals(found.get().get("status"));
        if (inSeojang && bridge != null) {
            bridge.publishSeojang();
        }

        tellRequester(req, null, new EmbedBuilder().setColor(INK)
                .setTitle("접합 — 몸과 이름이 이어졌다")
                .setDescription("마크의 **" + mcName + "** 이(가) 이제 **" + name + "** 이다.\n"
                        + "이제부터 그 손이 하는 일은 전부 이 이름의 장부에 적힌다 — "
                        + "벤 것도, 뿜은 것도, **죽인 것도.**\n"
                        + (inSeojang
                        ? "\n★ **품 안에 서책 한 권이 들어왔다** — 펼치면 서장이 시작된다."
                        + " 이야기는 **강호에서** 흐른다.\n"
                        : "")
                        + "*(끊으려면 `/혼천 접속해제`. 다만 이미 적힌 것은 지워지지 않는다)*")
                .build());
    }

    /** 그 몸이 [아니다] 를 눌렀다 — 청한 사람에게 말해 준다 (누가 거절했는지는 이미 그가 안다) */
    void linkRejected(LinkRequest req) {
        tellRequester(req, "**" + req.mcName() + "** 이(가) 청을 물렸다. 그 몸의 주인이 아니라면 그것이 옳다.");
    }

    /** 그 몸이 마크에서 `/혼천 접속` 을 다시 불렀다 — 낡은 청은 죽는다 (초기화) */
    void linkRequestCancelled(LinkRequest req) {
        tellRequester(req, "**" + req.mcName() + "** 이(가) 게임에서 접합을 다시 시작했다 — 이 청은 죽었다.");
    }

    /** 청한 자리로 돌아가 말해 준다 (훅이 죽었으면 DM 으로) — 그는 2분 전 그 창을 보고 있다 */
    private void tellRequester(LinkRequest req, String text, MessageEmbed... embeds) {
        var hook = linkHooks.remove(req.token());
        if (hook != null) {
            var action = embeds.length > 0 ? hook.editOriginalEmbeds(embeds).setContent(null)
                    : hook.editOriginal(text == null ? "—" : text).setEmbeds();
            action.queue(ok -> { }, err -> dm(req.discordId(), text, embeds));
            return;
        }
        dm(req.discordId(), text, embeds);
    }

    private void dm(String discordId, String text, MessageEmbed... embeds) {
        var jda = this.jda;
        if (jda == null) {
            return;
        }
        jda.retrieveUserById(discordId).queue(user -> user.openPrivateChannel().queue(ch -> {
            var msg = embeds.length > 0 ? ch.sendMessageEmbeds(embeds[0])
                    : ch.sendMessage(text == null ? "—" : text);
            msg.queue(ok -> { }, err -> { });
        }, err -> { }), err -> { });
    }

    /**
     * 접합을 끊는다 — 슬래시({@code /혼천 접속해제})와 안내판의 [몸을 끊는다] 가 둘 다 여기로 온다.
     *
     * <p>★★ <b>여기에 「자물쇠가 제 문을 잠그던」 자리가 하나 더 있었다</b> (2026-07-14, 안내판을 세우다 발견).
     * 옛 코드는 {@code requireDebuted} 를 불렀다 — <b>출도한 자만 끊을 수 있다.</b> 그런데 {@link #askLink}
     * 는 이미 <b>서장 중에도 잇는 것을 허락한다</b> (그래야 서장이 시작되니까). 그래서 문이 <b>비대칭</b>이었다:
     *
     * <pre>  서장 중에 잘못된 몸을 이었다 → 끊을 수 없다 ("아직 서장 중이다") → 다시 이을 수도 없다 (relink 거부)</pre>
     *
     * <p>사람은 거기서 막힌다. 그래서 이 문의 검사를 <b>접합의 문과 같은 것</b>으로 맞춘다:
     * <b>캐릭터가 있으면 끊을 수 있다.</b> 접합은 「몸과 이름을 잇는 일」이지 「강호에 나서는 일」이 아니다 —
     * 잇는 문과 끊는 문이 다른 열쇠를 요구하면 그것은 문이 아니라 덫이다.
     */
    private void unlinkAccount(IReplyCallback event) throws Exception {
        var found = requireCharacter(event, event.getUser());
        if (found.isEmpty()) {
            return;
        }
        long chId = ((Number) found.get().get("id")).longValue();
        var mine = db.mcOfCharacter(chId);
        if (mine.isEmpty()) {
            event.reply("이어진 몸이 없다.").setEphemeral(true).queue();
            return;
        }
        db.unlinkMc(mine.get());
        db.logEvent("접합해제", "character", String.valueOf(chId), "mvt", mine.get(), Map.of());
        event.reply("접합을 끊었다 — 마크의 그 몸은 다시 **이름 없는 자**가 된다.\n"
                        + "*(혈채는 이 이름의 장부에 그대로 남는다. 빚은 몸을 바꾼다고 없어지지 않는다)*")
                .setEphemeral(true).queue();
    }

    // ═══════════════ 혈채(血債) — 감쇠하지 않는 유일한 축 ═══════════════
    //
    // 주목은 7일에 1씩 준다. 우호는 30일에 1씩. **세계는 잊는다.**
    // 그런데 죽은 사람은 돌아오지 않는다. 그래서 이 장부만은 안 준다 (암혈채).
    //
    // 여기 있는 것은 배선뿐이다 — 수치·문턱·발화 대상은 전부 faction_reaction.yml blood_debt.engine.

    /** 오늘의 현혈채 — 세계가 아는 몫 (읽는 순간 정산. 암혈채는 여기 없다. 세계는 그것을 모른다) */
    int knownBloodDebt(long chId, int today) throws Exception {
        BloodDebtEntry d = db.bloodDebtOf(chId);
        return rules.bloodDebt.decayedKnown(d.knownRaw(), d.publicCount(), d.knownDay(), today);
    }

    /** ★ B7 — 혈채 15+ 면 게시판이 닫힌다. 세계에서 일이 사라진다 (남는 수입은 약탈뿐이다) */
    boolean boardClosedByDebt(long chId, int today) throws Exception {
        return knownBloodDebt(chId, today) >= rules.bloodDebt.boardBlockMin();
    }

    /**
     * ★ 사다리 — 살인 1건과 10건은 다르다.
     *
     * <p>각 칸은 <b>기존 표만 호출한다</b> (신규 반응 발명 없음): 수배·현상금(economy) ·
     * 정파 주목/우호(faction_reaction) · 명분(faction_politics) · 법명분(authority_mandate).
     * 멱등이다 — 한 칸은 한 번만 발화한다 (events 원장이 문지기다).
     *
     * @param rumorGroup 이 혈채를 낳은 소문의 군 (없으면 null) — 세력이 자기 채널로 읽을 명분의 근거다
     */
    void bloodDebtLadder(long chId, String name, String rumorGroup, int today) throws Exception {
        int known = knownBloodDebt(chId, today);
        if (known <= 0) {
            return;
        }
        BloodDebt bd = rules.bloodDebt;
        String actor = String.valueOf(chId);
        BloodDebt.Rung rung = bd.rungOf(known);

        // ① 관 — 수배와 현상금 (혈채 3+). 아직 이름은 없다. 세계는 '누군가'를 찾는다
        if (known >= bd.bountyMin() && !db.eventExists("수배", actor, "혈채:수배")) {
            int bounty = rules.price("의뢰_보수", bd.bountyRef());
            db.logEvent("수배", "character", actor, "blood_debt", "혈채:수배",
                    Map.of("현상금", bounty, "혈채", known, "칸", rung.name(), "발주", "gwan_gun"));
            if (rung.rumorIntensity() > 0) {
                spread(rumorGroup("수배", chId, today),
                        "관아에 방이 붙었다 — " + name + josa(name, "을", "를") + " 잡는 자에게 "
                                + bounty + "문", name, chId, List.of("수배", "치안", "살인"),
                        rung.rumorIntensity(), rules.initialAccuracy("간접_전문"),
                        rules.originNetwork("market"), today);
            }
        }

        // ② 정파 — 세계가 그를 버리는 칸 (혈채 6+: 주목 +4 / 우호 -4).
        //    ★ 바로 그 지점에서 어둠이 그를 줍는다 (마교 접촉·혈교 회수조는 faction_entry_routes 의 몫)
        for (BloodDebt.FactionStep step : bd.factionSteps()) {
            String key = "혈채:" + step.faction() + ":" + step.min();
            if (known < step.min() || db.eventExists("혈채_세력", actor, key)) {
                continue;
            }
            int score = factions.addAttention(step.faction(), chId, step.score(), today);
            int favor = factions.addFavor(step.faction(), chId, step.favor(), rules.factions.favorMax(),
                    today);
            db.logEvent("혈채_세력", "character", actor, "faction", key,
                    Map.of("세력", step.faction(), "주목", score, "우호", favor,
                            "혈채", known, "칸", rung.name()));
        }

        // ③ 명분 — 혈채는 명분을 **만들지 않고 발화시킨다** (faction_politics 제2원칙).
        //    ★ 그래도 무림은 잘 안 온다: victims = [mingan] 이므로 이해관계 보정이 전부 0이다.
        //      뭉치는 것은 무고가 아니라 **금기**다 (B6 — 마공 목격이 그 문을 연다)
        for (BloodDebt.Step step : bd.myeongbunSteps()) {
            String key = step.input() + ":" + chId;
            if (known < step.min() || db.eventExists("혈채_명분", actor, key)) {
                continue;
            }
            fireMyeongbun(chId, name, step.input(), step.victims(), rumorGroup, today);
            db.logEvent("혈채_명분", "character", actor, "myeongbun", key,
                    Map.of("사건", step.input(), "혈채", known, "칸", rung.name()));
        }

        // ④ 법명분 — 관이 층위를 올린다 (혈채 15+ 사교_준동_확증 16 · 26+ 사교_대발호 30).
        //    ★ 가산이 아니라 **개방**이다: 게이지를 그 값까지 끌어올린다 (등록부의 절대값)
        for (BloodDebt.Step step : bd.mandateSteps()) {
            String key = step.input() + ":" + chId;
            if (known < step.min() || db.eventExists("혈채_법명분", actor, key)) {
                continue;
            }
            int now = db.mandate(chId, today, rules.politics);
            int mandate = step.value() > now
                    ? db.addMandate(chId, step.value() - now, today, rules.politics) : now;
            db.logEvent("혈채_법명분", "character", actor, "authority_mandate", key,
                    Map.of("입력", step.input(), "법명분", mandate, "혈채", known,
                            "구간", String.valueOf(rules.politics.mandateEffect(mandate))));
            // 관이 그를 치는데 무림이 막지 않는다 — 두 층위 모두에서 벌거벗는 자리
            if (disavowed(chId, today)) {
                applyDisavowal(chId, "혈채:" + step.input(), today);
            }
        }
    }

    /**
     * 명분 하나를 발화시킨다 — 값·태그는 faction_politics.yml myeongbun.inputs 가 정한다.
     * 대상은 <b>사람</b>이다 (blood_debt_ignition.target — 개인 대상은 이미 인정돼 있다).
     * 피해자는 [mingan] — 민간은 등재 세력이지만 <b>무림이 아니다.</b> 그래서 아무도 급하지 않다.
     *
     * <p>★ <b>사안은 사람마다 하나다</b> (사건마다 하나가 아니다). 그래야 값이 <b>쌓이고</b>
     * 태그가 <b>겹친다</b> — 그리고 그것이 이 축의 발화 지점이다:
     * <b>무고(-3)만으로는 안 뭉친 무림이, 같은 사안에 금기(-4)가 붙는 순간 뭉친다.</b>
     * (blood_debt.md §4① — 누적 명분 13 = 무고 8 + 금기 5 → 소연합 → 토벌대)
     */
    private void fireMyeongbun(long chId, String name, String input, List<String> victims,
                               String rumorGroup, int today) throws Exception {
        int value = rules.politics.inputValue(input);
        List<String> tags = rules.politics.inputTags(input);
        MyeongbunIssue issue = db.addMyeongbun("혈채:" + chId, name, victims, tags, value,
                rules.initialAccuracy("직접_목격"), rumorGroup, null, today,
                rules.politics.gaugeMax(), rules.politics);
        db.logEvent("명분", "character", String.valueOf(chId), "myeongbun", issue.issue(),
                Map.of("사건", input, "가산", value, "게이지", issue.rawGauge(),
                        "태그", issue.tags(), "피해", issue.victims()));
    }

    /**
     * ★ B6 — 운기조식을 목격당했다. <b>혈교는 숨을 수 없다</b> (simbeop stealth_option: false).
     *
     * <p>운기 색이 곧 자백이다: 노출 배수 하한 1.0 이 이 몸에 박힌다 — 그 뒤로 <b>완전 범죄는 없다.</b>
     * 그리고 금기 태그가 켜진다 — <b>무고만으로는 안 뭉친 무림이 금기에는 뭉친다.</b>
     * 마교는 이 발화를 피할 수 있고(은폐 가능), 혈교는 못 피한다. 두 어둠의 수명이 여기서 갈린다.
     */
    void magongWitnessed(long chId, String name, String simbeop, int today) throws Exception {
        if (simbeop == null || !rules.isMagong(simbeop) || rules.canHideCirculation(simbeop)) {
            return;   // 정파의 심법 · 또는 숨길 수 있는 마공 (마교) — 아무도 모른다
        }
        String actor = String.valueOf(chId);
        if (db.eventExists("마공_목격", actor, simbeop)) {
            return;   // 이미 한 번 들켰다 — 자백은 한 번이면 족하다
        }
        Map<String, Object> cfg = rules.bloodDebt.magongWitness();
        db.setExposureFloor("character:" + chId, chId, rules.bloodDebt.magongExposureFloor(), today);

        @SuppressWarnings("unchecked")
        Map<String, Object> rumorCfg = cfg.get("rumor") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        List<String> tags = new ArrayList<>();
        if (rumorCfg.get("tags") instanceof List<?> l) {
            l.forEach(t -> tags.add(String.valueOf(t)));
        }
        int intensity = rumorCfg.get("intensity") instanceof Number n ? n.intValue() : 3;
        String group = rumorGroup("마공", chId, today);
        spread(group, name + josa(name, "이", "가") + " 기를 돌리는데 그 빛이 탁한 적색이었다 — "
                        + "사람들이 뒤로 물러섰다", name, chId, tags, intensity,
                rules.initialAccuracy(String.valueOf(rumorCfg.getOrDefault("accuracy_kind", "직접_목격"))),
                rules.originNetwork("market"), today);
        db.logEvent("마공_목격", "character", actor, "simbeop", simbeop,
                Map.of("심법", simbeop, "은폐", false, "노출_하한", rules.bloodDebt.magongExposureFloor()));

        Object input = cfg.get("myeongbun");
        if (input != null) {
            fireMyeongbun(chId, name, String.valueOf(input), List.of("mingan"), group, today);
        }
        bloodDebtLadder(chId, name, group, today);
    }

    /** 조사(助詞) — 소문은 사람의 입에서 나온 문장이다 ("이름을" / "이름를"이라 말하는 사람은 없다) */
    private static String josa(String word, String withBatchim, String without) {
        if (word == null || word.isBlank()) {
            return withBatchim + "(" + without + ")";
        }
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return withBatchim + "(" + without + ")";
        }
        return (last - 0xAC00) % 28 == 0 ? without : withBatchim;
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
