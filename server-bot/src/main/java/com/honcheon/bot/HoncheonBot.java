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
                        new SubcommandData("지역등록", "이 채널을 청하현으로 등록 (서버 관리자)"),
                        new SubcommandData("정산", "세계일 +1 (서버 관리자 — 자정에는 자동)"),
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

        scheduleMidnight(jda, db);
        System.out.println("혼천 봇 기동 — 룰 로드: " + configDir + " / DB: " + dbPath
                + " / LLM 렌더러: " + (renderer.enabled()
                        ? rules.turnRendererModel() : "비활성 (ANTHROPIC_API_KEY 없음 — 폴백 템플릿)"));
    }

    /** 자정(Asia/Seoul)마다 세계일 +1 — 실제 하루 = 세계 1일. 청하현 채널이 있으면 아침을 알린다 */
    private static void scheduleMidnight(JDA jda, Db db) {
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "honcheon-day");
            t.setDaemon(true);
            return t;
        });
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            try {
                int day = db.advanceDay();
                db.getMeta("지역채널:청하현").ifPresent(channelId -> {
                    var channel = jda.getTextChannelById(channelId);
                    if (channel != null) {
                        channel.sendMessage("**" + day + "일차 아침이 밝았다** — 몸이 개운하다. "
                                + "(일일 적립·수련·연속 감쇠 재시작)").queue();
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
