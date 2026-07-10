package com.honcheon.bot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.nio.file.Path;

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

        jda.updateCommands().addCommands(
                Commands.slash("혼천", "무협 텍스트 RPG 혼천")
                        .addSubcommands(
                                new SubcommandData("시작", "새 캐릭터를 만든다 — 유년의 기억 다섯 문항"),
                                new SubcommandData("원장", "내 캐릭터 시트를 본다"),
                                new SubcommandData("사냥", "청하현 뒷산 사냥 — 화후와 생계 (지역 채널에서)"),
                                new SubcommandData("비무", "비무 신청 — 양측 2d6 대립 판정")
                                        .addOption(OptionType.USER, "상대", "비무를 청할 상대", true),
                                new SubcommandData("지역등록", "이 채널을 청하현으로 등록 (서버 관리자)"),
                                new SubcommandData("도움말", "명령과 규칙 안내"))
        ).queue();

        System.out.println("혼천 봇 기동 — 룰 로드: " + configDir + " / DB: " + dbPath
                + " / LLM 렌더러: " + (renderer.enabled()
                        ? rules.turnRendererModel() : "비활성 (ANTHROPIC_API_KEY 없음 — 폴백 템플릿)"));
    }
}
