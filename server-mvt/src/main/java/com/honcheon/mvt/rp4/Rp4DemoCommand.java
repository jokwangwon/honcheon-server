package com.honcheon.mvt.rp4;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * {@code /rp4demo} — RP-4 A안 비교 데모의 손잡이. <b>OP 전용 무대 장치</b>다 (운영 명령이 아니다).
 *
 * <p>plugin.yml 을 고치지 않으려고 CommandMap 에 직접 등록한다 (파일럿 규약: 기존 파일 수정 금지).
 * 채택안이 정해지면 이 명령은 무대와 함께 걷힌다 — 살아남을 자격은 rp4_pilot.md §5.4 가 정한다.
 */
final class Rp4DemoCommand extends Command {

    private final Rp4DemoStage stage;

    Rp4DemoCommand(Rp4DemoStage stage) {
        super("rp4demo",
                "RP-4 몹 파일럿 A안 데모 — 호랑이 5동작 무대 (docs/design/rp4_pilot.md §8)",
                "/rp4demo <무대 [실루엣]|군집 [마리수]|통계|정리>",
                List.of());
        this.stage = stage;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("[RP-4] 무대는 눈이 있어야 선다 — 인게임에서 쳐라.");
            return true;
        }
        if (!p.isOp()) {
            p.sendMessage("[RP-4] OP 전용 무대 장치다.");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "무대", "stage" -> {
                boolean low = args.length > 1
                        && ("실루엣".equals(args[1]) || "low".equalsIgnoreCase(args[1]));
                p.sendMessage("[RP-4] " + stage.startStage(p, low));
            }
            case "군집", "cluster" -> {
                int n = 0;
                if (args.length > 1) {
                    try {
                        n = Integer.parseInt(args[1]);
                    } catch (NumberFormatException bad) {
                        p.sendMessage("[RP-4] 마리수가 아니다: " + args[1]);
                        return true;
                    }
                }
                p.sendMessage("[RP-4] " + stage.startCluster(p, n));
            }
            case "통계", "stats" -> p.sendMessage("[RP-4] " + stage.stats());
            case "정리", "clear" -> p.sendMessage("[RP-4] " + stage.clear());
            default -> p.sendMessage("[RP-4] " + getUsage()
                    + " — 무대: 5동작 대본 1마리 (실루엣 = lod_parts 등급) · 군집: 부하 실측 (기본 "
                    + "load_test.combat_cluster_size 마리, 60초 자동 종료)");
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("무대", "군집", "통계", "정리");
        }
        if (args.length == 2 && ("무대".equals(args[0]) || "stage".equalsIgnoreCase(args[0]))) {
            return List.of("실루엣");
        }
        return List.of();
    }
}
