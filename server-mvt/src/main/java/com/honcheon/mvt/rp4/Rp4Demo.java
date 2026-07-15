package com.honcheon.mvt.rp4;

import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Path;

/**
 * RP-4 A안 데모의 문 — 이 패키지에서 유일하게 밖에 보이는 클래스.
 *
 * <p><b>배선 지점 (한 줄)</b> — {@code HoncheonMvt.onEnable} 의
 * {@code getCommand("honcheon").setExecutor(...)} 다음 줄에:
 * <pre>{@code com.honcheon.mvt.rp4.Rp4Demo.register(this);}</pre>
 * (선택) {@code onDisable} 의 {@code hunting.mobDisplay().clearAll()} 곁에:
 * <pre>{@code com.honcheon.mvt.rp4.Rp4Demo.shutdown();}</pre>
 * shutdown 을 안 달아도 형체는 {@code setPersistent(false)} 라 저장되지 않고, 그 세션의
 * 잔재는 다음 register 의 유령 청소가 걷는다 (MobDisplay.start 와 같은 문법).
 *
 * <p><b>이 패키지가 건드리지 않는 것</b> — MobDisplay · HuntingGrounds · 팩 게이트 · 판정.
 * 데모는 몸 없는 무대 배우다: 형체를 세우고 걷히는 일 말고는 세계에 아무 자국도 안 남긴다.
 * 자세한 규약과 대본은 {@code docs/design/rp4_pilot.md} §8.
 */
public final class Rp4Demo {

    private static Rp4DemoStage stage;

    private Rp4Demo() {
    }

    /** 등록 — 등록부 판독 · 지난 생의 유령 청소 · /rp4demo 명령. 등록부가 없으면 조용히 접는다 */
    public static void register(JavaPlugin plugin) {
        Path cfg = new File(plugin.getDataFolder(), "config").toPath();
        Rp4Registry.Sheet sheet = Rp4Registry.load(cfg);
        if (sheet == null) {
            plugin.getLogger().warning("[RP-4] mob_models.yml 의 호랑이 절을 못 읽었다 — 데모 잠듦");
            return;
        }
        int swept = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e instanceof Display && e.getPersistentDataContainer().has(Rp4DemoRig.KEY_DEMO)) {
                    e.remove();
                    swept++;
                }
            }
        }
        stage = new Rp4DemoStage(plugin, sheet);
        plugin.getServer().getCommandMap().register("honcheon", new Rp4DemoCommand(stage));
        plugin.getLogger().info("[RP-4] A안 데모 등록 — /rp4demo (관절 "
                + sheet.model().parts().size() + "파트 · 군집 눈금 " + sheet.clusterSize()
                + (swept > 0 ? " · 유령 " + swept + "개 청소" : "") + ")");
    }

    /** (선택 배선) 내려갈 때 무대를 걷는다 — 안 불려도 유령 청소가 다음 기동에 걷는다 */
    public static void shutdown() {
        if (stage != null) {
            stage.clear();
            stage = null;
        }
    }
}
