package com.honcheon.bot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 혼천 봇 베타 (단계 Ⅱ~Ⅲ) — 생성 문답 → 서장(영속) → 출도 → 청하현 사냥·비무.
 * 환경 변수: DISCORD_TOKEN(필수), ANTHROPIC_API_KEY(선택 — 없으면 폴백 템플릿),
 *           HONCHEON_CONFIG(기본 config), HONCHEON_DB(기본 run/bot/honcheon.db)
 * 원칙: 수치는 config/*.yml — 봇은 배선만 한다. LLM 은 서사만 렌더한다 (llm.yml).
 */
public final class HoncheonBot {

    public static void main(String[] args) throws Exception {
        String token = System.getenv("DISCORD_TOKEN");
        if (token == null || token.isBlank()) {
            System.err.println("오류: DISCORD_TOKEN 환경 변수가 필요합니다 (docs/bot/bot_alpha_guide.md 참조)");
            System.exit(1);
        }
        Path configDir = Path.of(System.getenv().getOrDefault("HONCHEON_CONFIG", "config"));
        Path dbPath = Path.of(System.getenv().getOrDefault("HONCHEON_DB", "run/bot/honcheon.db"));
        Path schemaPath = Path.of(System.getenv().getOrDefault("HONCHEON_SCHEMA", "db/schema.sql"));

        Rules rules = new Rules(configDir);
        Db db = new Db(dbPath, schemaPath);
        LlmRenderer renderer = new LlmRenderer(rules.turnRendererModel());
        GameListener listener = new GameListener(rules, db, renderer);
        // 세계 개막 소문 — 등록 사건 3건은 첫날부터 이미 돌고 있다 (1회성, 멱등)
        listener.seedWorldRumors();

        // 세계 다리 — 마크(MVT)에서 벌어진 일이 이 장부로 흘러 들어온다 (config/world_bridge.yml).
        // 봇이 꺼져 있던 동안의 사건도 파일에 그대로 남아 있다 — 켜는 순간 전부 따라잡는다.
        Bridge bridge = new Bridge(rules, db, listener, configDir);
        bridge.start();

        JDA jda = JDABuilder.createLight(token)
                .addEventListeners(listener)
                .build()
                .awaitReady();

        var honcheon = Commands.slash("혼천", "무협 텍스트 RPG 혼천")
                .addSubcommands(
                        new SubcommandData("시작", "새 캐릭터를 만든다 — 유년의 기억 다섯 문항"),
                        new SubcommandData("정보", "내 캐릭터 정보를 본다"),
                        new SubcommandData("원장", "정보와 같다 — 옛 명령 (하위호환)"),
                        new SubcommandData("사냥", "청하현 뒷산 사냥 — 수련과 생계 (지역 채널에서)"),
                        new SubcommandData("비무", "비무 신청 — 양측 2d6 대립 판정")
                                .addOption(OptionType.USER, "상대", "비무를 청할 상대", true),
                        new SubcommandData("수련", "기초 단련 — 하루 한 번, 수련 +1일치"),
                        new SubcommandData("사사", "곽진에게 무공을 청한다 — 무공 백지만"),
                        new SubcommandData("의뢰", "소연의 게시판 — 오늘의 의뢰 3건 (수주·수행)"),
                        new SubcommandData("대화", "청하현 사람에게 말을 건다 — 자유 입력")
                                .addOptions(new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.STRING, "상대", "말을 걸 사람", true)
                                                .addChoice("객잔 주인 한백", "한백")
                                                .addChoice("의뢰소 관리인 소연", "소연")
                                                .addChoice("의원 유문", "유문")
                                                .addChoice("전장 지점주 금서방", "금서방")
                                                .addChoice("표사 곽진", "곽진")
                                                .addChoice("장터 잡화상 장쇠", "장쇠"),
                                        new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.STRING, "말", "하고 싶은 말 (자유 입력)", true)),
                        new SubcommandData("탐방", "폐사당을 살핀다 — 발품에는 이유가 있다 (하루 한 번)"),
                        new SubcommandData("운기", "심법으로 기를 돌린다 — 하루 한 번, 축기"),
                        new SubcommandData("출행", "세력의 문으로 직행한다 — 조건 불문, 세계가 대답한다")
                                .addOptions(new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                        OptionType.STRING, "목적지", "어디로 가는가", true)
                                        .addChoice("화산 (화산파 산문 — 여정 3~7일)", "화산")
                                        // ★ 9번째 루트 — 관은 걸어서 간다. 청하현 안에 있다 (여정 0일)
                                        .addChoice("관아 (청하현 관아 — 포쾌로 써 달라고 한다)", "관아")),
                        new SubcommandData("소문", "저잣거리에 도는 말 — 퍼질수록 이야기가 달라진다"),
                        new SubcommandData("의방", "부상을 다스리고 외상을 갚는다 (유문의 의방)"),
                        new SubcommandData("구조", "빈사의 동행을 지혈한다 — 의술 판정 (사람을 살리는 손)")
                                .addOption(OptionType.USER, "상대", "쓰러진 사람", true),
                        new SubcommandData("전장", "금서방의 전장 — 예치·인출·상속인 지정")
                                .addOptions(new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.INTEGER, "예치", "맡길 금액 (문)", false),
                                        new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.INTEGER, "인출", "찾을 금액 (문 — 수수료 별도)", false),
                                        new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.USER, "상속인", "내가 죽으면 예치금을 받을 사람", false)),
                        // ★ 신원 접합 — 마크에서 `/혼천 접속` 으로 받은 코드를 여기서 확정한다.
                        //   확정이 **인증된 자리**(디스코드)에 있어야 남의 캐릭터를 훔칠 수 없다
                        //   (world_bridge.yml identity.direction: mvt_issues_discord_confirms)
                        new SubcommandData("접속", "마크의 몸을 이 이름에 잇는다 — 마크에서 받은 코드로")
                                .addOptions(new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                        OptionType.STRING, "코드",
                                        "마크에서 `/혼천 접속` 을 쳐서 받은 6자리 코드 (10분간 유효)", true)),
                        new SubcommandData("접속해제", "마크의 몸과 이 이름을 끊는다 (혈채는 남는다)"),
                        new SubcommandData("지역등록", "이 채널을 청하현으로 등록 (서버 관리자)"),
                        new SubcommandData("정산", "세계일 +1 (서버 관리자 — 자정에는 자동)"),
                        new SubcommandData("사선", "플레이어에게 사선을 긋는다 — 죽음 검증용 (서버 관리자)")
                                .addOptions(new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.USER, "상대", "사선에 세울 플레이어", true),
                                        new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.STRING, "단계", "어디까지 (기본: 빈사)", false)
                                                .addChoice("부상 — 한 칸 (살의 없음, 중상까지)", "부상")
                                                .addChoice("빈사 — 사선 (사망 위기 파이프)", "빈사")
                                                .addChoice("즉사 — 개입 없이 확정 (유산·소문 검증)", "즉사"),
                                        new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.STRING, "장소", "어디서 쓰러졌나 (기본: 마을)", false)
                                                .addChoice("마을 안 — 누군가 의방으로 업고 뛴다", "마을")
                                                .addChoice("야외 — 아무도 옮겨 주지 않는다", "야외"),
                                        new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.STRING, "살해자", "피의 장부에 적힐 이름", false)),
                        new SubcommandData("사망", "NPC를 죽인다 — 사망 연쇄 검증용 (서버 관리자)")
                                .addOptions(new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.STRING, "상대", "죽일 등록 NPC", true)
                                                .addChoice("객잔 주인 한백", "한백")
                                                .addChoice("의뢰소 관리인 소연", "소연")
                                                .addChoice("의원 유문", "유문")
                                                .addChoice("전장 지점주 금서방", "금서방")
                                                .addChoice("표사 곽진", "곽진")
                                                .addChoice("장터 잡화상 장쇠", "장쇠")
                                                .addChoice("표국주 진철산", "진철산")
                                                .addChoice("화산파 진운", "진운")
                                                .addChoice("포두 박호 (관 — 법명분 +8)", "박호")
                                                .addChoice("현령 조문원 (관 — 법명분 +14, 즉시 절연)", "조문원"),
                                        new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.USER, "살해자", "피의 장부에 적힐 이름 "
                                                        + "(관을 죽였다면 이 사람에게 법명분이 붙는다)", false),
                                        new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.STRING, "사인", "죽음의 원인 (기본: 사건_피살)", false)
                                                .addChoice("노환·병사", "노환_병사")
                                                .addChoice("사고", "사고")
                                                .addChoice("사건·피살", "사건_피살")
                                                .addChoice("자멸·심마", "자멸_심마"),
                                        new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.INTEGER, "목격", "목격자 (0 없음 / 1 소수 / 2 다수)", false)
                                                .addChoice("0 — 없음", 0)
                                                .addChoice("1 — 소수", 1)
                                                .addChoice("2 — 다수 (백주·장터)", 2),
                                        new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.STRING, "시신", "시신 처리 (기본: 즉시_발견)", false)
                                                .addChoice("은닉", "은닉")
                                                .addChoice("유기 — 지연 발견", "유기_지연발견")
                                                .addChoice("즉시 발견", "즉시_발견")),
                        // 정치 검증 — 명분을 쌓고 연합을 성립시켜 본다 (faction_politics.yml)
                        new SubcommandData("명분", "명분을 쌓거나 끈다 — 연합 검증용 (서버 관리자)")
                                .addOptions(new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.STRING, "사건", "명분을 쌓는 사건 "
                                                        + "(faction_politics myeongbun.inputs)", false)
                                                .addChoice("관리의 사적 폭거 — 백성 살해 (+6 무고)",
                                                        "관리의_사적_폭거_백성살해")
                                                .addChoice("관의 은폐 발각 (+4 법의배신)", "관의_은폐_발각")
                                                .addChoice("관의 문파 제자 다수 살해 (+7 무고·무림침해)",
                                                        "관의_문파_제자_다수_살해")
                                                .addChoice("관의 문파 토벌 — 멸문 (+10 존망)",
                                                        "관의_문파_토벌_멸문")
                                                .addChoice("관의 무림 일제 검속 (+8 존망)", "관의_무림_일제_검속")
                                                .addChoice("관과 금기세력의 결탁 증거 (+9 금기)",
                                                        "관과_금기세력의_결탁_증거")
                                                .addChoice("마교 준동 확증 (+8 금기·존망)", "마교_준동_확증")
                                                .addChoice("무고한 학살 — 장터·촌락 (+8 무고)",
                                                        "무고한_학살_장터_촌락")
                                                .addChoice("문파 멸문 (+10 무림침해·존망)", "문파_멸문"),
                                        new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.STRING, "대상",
                                                "누가 했는가 — 명분이 겨누는 세력 id "
                                                        + "(기본: 관 → gwan_gun · 금기 → magyo)",
                                                false),
                                        // ★ 누가 당했는가 — 이것이 없으면 연합 계산의 절반이 죽는다
                                        //   (피해_당사자 -8 · 동맹_피해 -6 · 원한_세력_참여 +5)
                                        new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.STRING, "피해",
                                                "누가 당했는가 — 피해 세력 id (쉼표로 여럿. 비우면 '백성이 죽었다')",
                                                false),
                                        new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.INTEGER, "정확도",
                                                "소문의 정확도 = 명분의 배수 (80+ ×1.0 / 50+ ×0.7 / "
                                                        + "30+ ×0.5 대상전환 / 29- ×0)", false),
                                        new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.STRING, "해소",
                                                "명분을 끈다 — 관의 유일한 무기", false)
                                                .addChoice("가해자 처형 공개 (-6 꼬리 자르기)",
                                                        "가해자_처형_공개")
                                                .addChoice("배상 공개 (-4)", "배상_공개")
                                                .addChoice("피해 당사자 화해 (-8)", "피해_당사자_화해")
                                                .addChoice("참여 세력 이익 배분 — 매수 (-3)",
                                                        "참여_세력_이익_배분")
                                                .addChoice("진범 규명 (이전 — 대상에 진범을 댈 것)",
                                                        "진범_규명")),
                        // ★ 문파 사정 — 연합의 브레이크 (sect_life sect_state.internal_burden)
                        new SubcommandData("사정", "문파에 사정을 얹는다 — 연합 브레이크 검증용 (서버 관리자)")
                                .addOptions(new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.STRING, "세력",
                                                "어느 문파인가 (세력 id 또는 한글명 — 화산파·소림사 …)", true),
                                        new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.STRING, "사정",
                                                "무엇 때문인가 (sect_life sect_state.internal_burden.sources)",
                                                false)
                                                .addChoice("장문 교체기 (+3 — 결정할 사람이 없다)", "장문_교체기")
                                                .addChoice("내분·알력 (+2)", "내분_알력")
                                                .addChoice("사상자 누적 (+2 — 제자를 아낀다)", "사상자_누적")
                                                .addChoice("재정 궁핍 (+1 — 봉록을 못 준다)", "재정_궁핍")
                                                .addChoice("폐관·은둔 (+2 — 문을 닫아걸었다)", "폐관_은둔"),
                                        new net.dv8tion.jda.api.interactions.commands.build.OptionData(
                                                OptionType.BOOLEAN, "해소",
                                                "사정을 푼다 (후계가 섰다 / 곳간이 찼다)", false)),
                        new SubcommandData("도움말", "명령과 규칙 안내"));
        // 개발 중엔 길드 스코프 등록(즉시 반영) — HONCHEON_GUILD_ID 설정 시. 글로벌은 최대 1시간 지연
        String guildId = System.getenv("HONCHEON_GUILD_ID");
        var guild = guildId == null || guildId.isBlank() ? null : jda.getGuildById(guildId);
        if (guild != null) {
            guild.updateCommands().addCommands(honcheon).queue();
            System.out.println("명령 등록: 길드 스코프 (" + guild.getName() + ") — 즉시 반영");
        } else {
            jda.updateCommands().addCommands(honcheon).queue();
            System.out.println("명령 등록: 글로벌 — 첫 반영에 최대 1시간"
                    + (guildId == null || guildId.isBlank() ? " (개발 중엔 HONCHEON_GUILD_ID 권장)"
                            : " (경고: HONCHEON_GUILD_ID=" + guildId + " 길드를 찾지 못함)"));
        }

        scheduleMidnight(jda, db, listener);
        System.out.println("혼천 봇 기동 — 룰 로드: " + configDir + " / DB: " + dbPath
                + " / LLM 렌더러: " + renderer.providerLabel());
    }

    /**
     * 자정(Asia/Seoul)마다 세계일 +1 — 실제 하루 = 세계 1일.
     * 세계일이 넘어가면 세계가 산다: 소문이 새 망에 닿고, 세력이 인지하고, 빈사의 창구가 닫힌다.
     */
    private static void scheduleMidnight(JDA jda, Db db, GameListener listener) {
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "honcheon-day");
            t.setDaemon(true);
            return t;
        });
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            try {
                GameListener.Dawn dawn = listener.advanceWorld();
                db.getMeta("지역채널:청하현").ifPresent(channelId -> {
                    var channel = jda.getTextChannelById(channelId);
                    if (channel != null) {
                        channel.sendMessage("**" + dawn.day() + "일차 아침이 밝았다** — 몸이 개운하다. "
                                + "(일일 적립·수련·연속 감쇠 재시작)\n\n" + dawn.report()).queue();
                    }
                });
            } catch (Exception e) {
                System.err.println("세계일 정산 실패: " + e.getMessage());
            } finally {
                ZonedDateTime next = ZonedDateTime.now(seoul).plusDays(1).toLocalDate().atStartOfDay(seoul);
                sched.schedule(tick[0], Duration.between(ZonedDateTime.now(seoul), next).toMillis(),
                        TimeUnit.MILLISECONDS);
            }
        };
        ZonedDateTime next = ZonedDateTime.now(seoul).plusDays(1).toLocalDate().atStartOfDay(seoul);
        sched.schedule(tick[0], Duration.between(ZonedDateTime.now(seoul), next).toMillis(),
                TimeUnit.MILLISECONDS);
    }
}
