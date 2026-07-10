package com.honcheon.bot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.nio.file.Path;

/**
 * 혼천 봇 알파 (단계 Ⅱ) — 생성 문답 → 서장(프라이빗 스레드) → 판정 턴 루프.
 * 환경 변수: DISCORD_TOKEN(필수), HONCHEON_CONFIG(기본 config), HONCHEON_DB(기본 run/bot/honcheon.db)
 * 원칙: 수치는 config/*.yml — 봇은 배선만 한다. 렌더는 폴백 템플릿(LLM은 후속 결선).
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
        GameListener listener = new GameListener(rules, db);

        JDA jda = JDABuilder.createLight(token)
                .addEventListeners(listener)
                .build()
                .awaitReady();

        jda.updateCommands().addCommands(
                Commands.slash("혼천", "무협 텍스트 RPG 혼천")
                        .addSubcommands(
                                new SubcommandData("시작", "새 캐릭터를 만든다 — 유년의 기억 다섯 문항"),
                                new SubcommandData("원장", "내 캐릭터 시트를 본다"),
                                new SubcommandData("도움말", "명령과 규칙 안내"))
        ).queue();

        System.out.println("혼천 봇 기동 — 룰 로드: " + configDir + " / DB: " + dbPath);
    }
}
