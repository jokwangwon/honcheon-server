package com.honcheon.mvt;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 내공/내력 <b>별도 바</b> — 보스바 채널 (웨이브-1 트랙 C).
 *
 * <p><b>왜 이 바인가</b> (docs/design/cultivation_v3_levels.md §4-b · ★사용자 확정 2026-07-15):
 * 바닐라 XP바는 v3 <b>경험/레벨</b>로 이사한다. 그때까지 XP바에 세 들어 살던 내공(레벨 숫자)·
 * 내력(게이지)은 집을 잃는다 — 그 살림이 이 보스바로 온다. {@link SkillHud#energyBar} 가
 * 하던 일({@code setLevel(내공)}·{@code setExp(내력/풀)})의 <b>이사 목적지</b>다.
 *
 * <p><b>등록제</b> (HANDOFF §2.1): 문구·색·구간(눈금)은 이 클래스가 고르지 않는다.
 * 등록부 키가 아직 없어 <b>생성자 인자</b>로 받는다 — 배선하는 손(조율자)이 등록부를 만들어
 * 그 값을 넘긴다. 코드 어디에도 기본 문구·기본 색은 없다.
 *
 * <p><b>침묵하는 실패 금지</b> (HANDOFF §2.2): 받은 색·구간 이름이 Adventure 의 것이 아니면
 * <b>소리내어</b> 경고하고 채널 자체를 열지 않는다 — 조용히 다른 색을 고르는 것이 곧 거짓말이다.
 * 바가 안 뜨는 것은 눈에 보이고, 경고는 무엇이 틀렸는지 이름을 댄다. 판정은 불변이다 —
 * 이 클래스는 판정을 모른다 (SkillHud 와 같은 한 방향 의존).
 *
 * <p><b>단전이 비었으면 바도 없다</b>: 풀이 0(입도진·범인·내공 0)인 몸에 빈 바를 띄우는 것은
 * "없는 것의 표시" — 입도진 액션바 겹침(사용자 실측 2026-07-15)과 같은 병이다. 숨긴다.
 *
 * <p>디자인 다듬기(질감·문구 최종)는 사용자 RP 몫 — 여기는 기능만.
 */
final class EnergyBossBar {

    /** 문구 템플릿 자리끼움 — 등록부의 문구가 이 세 이름으로 수치를 받는다 */
    static final String PH_ENERGY = "{내력}";
    static final String PH_POOL = "{내력최대}";
    static final String PH_NAEGONG = "{내공}";

    private final SkillEngine engine;
    private final String title;             // 등록부의 문구 템플릿 ({내력}·{내력최대}·{내공})
    private final String depletedSuffix;    // 고갈 시 문구 뒤에 붙는 조각 ("" 이면 안 붙는다)
    private final BossBar.Color color;
    private final BossBar.Color depletedColor;
    private final BossBar.Overlay overlay;  // PROGRESS 또는 NOTCHED_6/10/12/20 — 구간은 등록부가 정한다
    /** 등록부 값이 Adventure 에 없는 이름이었다 — 채널을 열지 않는다 (생성자가 이미 소리냈다) */
    private final boolean disabled;

    /** 플레이어별 바 — 만든 것만 여기 있다. 떠나면 {@link #forget}/{@link #hide} 가 지운다 */
    private final Map<UUID, BossBar> bars = new HashMap<>();

    /**
     * @param title          문구 템플릿 — 등록부의 것. {@code {내력}}·{@code {내력최대}}·{@code {내공}} 자리끼움
     * @param colorName      {@link BossBar.Color} 이름 (예: BLUE) — 등록부의 것
     * @param depletedColorName 고갈({@link SkillEngine#isDepleted}) 시 색 이름 — 등록부의 것
     * @param overlayName    {@link BossBar.Overlay} 이름 (PROGRESS·NOTCHED_6·NOTCHED_10·NOTCHED_12·NOTCHED_20)
     * @param depletedSuffix 고갈 시 문구 꼬리 (null 허용 — 없으면 안 붙는다. 액션바의 "(고갈 −2)" 대응)
     */
    EnergyBossBar(SkillEngine engine, String title, String colorName, String depletedColorName,
                  String overlayName, String depletedSuffix) {
        this.engine = engine;
        this.title = title;
        this.depletedSuffix = depletedSuffix == null ? "" : depletedSuffix;
        this.color = resolveColor(colorName);
        this.depletedColor = resolveColor(depletedColorName);
        this.overlay = resolveOverlay(overlayName);
        boolean ok = title != null && !title.isEmpty()
                && this.color != null && this.depletedColor != null && this.overlay != null;
        if (title == null || title.isEmpty()) {
            org.bukkit.Bukkit.getLogger().warning("[혼천] 내력 보스바 문구가 비었다 — 채널을 열지 않는다");
        }
        this.disabled = !ok;
    }

    /** 등록부의 색 이름 → Adventure 색. 모르는 이름은 <b>소리내어</b> 알리고 null — 코드가 색을 고르지 않는다 */
    private static BossBar.Color resolveColor(String name) {
        if (name == null || name.isEmpty()) {
            org.bukkit.Bukkit.getLogger().warning("[혼천] 내력 보스바 색이 비었다 — 채널을 열지 않는다");
            return null;
        }
        try {
            return BossBar.Color.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            org.bukkit.Bukkit.getLogger().warning(
                    "[혼천] 등록부에 없는 보스바 색: " + name + " (BLUE·GREEN·PINK·PURPLE·RED·WHITE·YELLOW)");
            return null;
        }
    }

    /** 등록부의 구간 이름 → Adventure 오버레이. 모르는 이름은 <b>소리내어</b> 알리고 null */
    private static BossBar.Overlay resolveOverlay(String name) {
        if (name == null || name.isEmpty()) {
            org.bukkit.Bukkit.getLogger().warning("[혼천] 내력 보스바 구간이 비었다 — 채널을 열지 않는다");
            return null;
        }
        try {
            return BossBar.Overlay.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            org.bukkit.Bukkit.getLogger().warning(
                    "[혼천] 등록부에 없는 보스바 구간: " + name
                            + " (PROGRESS·NOTCHED_6·NOTCHED_10·NOTCHED_12·NOTCHED_20)");
            return null;
        }
    }

    // ─── 갱신 — SkillHud.energyBar 가 서 있던 자리마다 이것이 불린다 ───

    /**
     * 바를 몸과 맞춘다 — 없으면 만들어 보여 주고, 있으면 수치·색·문구를 옮긴다.
     *
     * <p>매 틱 불려도 싸다: Adventure 보스바는 <b>값이 실제로 바뀔 때만</b> 패킷을 보낸다
     * (setter 가 equals 대조 후 변경 시에만 리스너를 깨운다). 훅 대신 매 틱 대조 —
     * {@link SkillHud#vitalityTick} 과 같은 문법이다 (훅은 빠뜨리면 조용히 틀린다).
     *
     * <p>풀이 0이면 <b>숨긴다</b> — 입도진·범인의 몸에는 단전이 없고, 없는 것의 바는 띄우지 않는다.
     */
    void update(Player player, SkillEngine.State state) {
        if (disabled || player == null || state == null) {
            return;
        }
        int pool = engine.pool(state.naegong);
        if (pool <= 0) {
            hide(player);
            return;
        }
        float progress = (float) Math.max(0.0, Math.min(1.0, state.energy / (double) pool));
        boolean depleted = engine.isDepleted(state.energy);
        Component name = Component.text(compose(state, pool, depleted));
        BossBar.Color now = depleted ? depletedColor : color;

        BossBar bar = bars.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(name, progress, now, overlay);
            bars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
            return;
        }
        bar.name(name);
        bar.progress(progress);
        bar.color(now);
    }

    /** 문구 합성 — 등록부의 템플릿에 수치를 끼운다. 내공은 정수 단계만 (SkillHud.energyBar 와 같은 자) */
    private String compose(SkillEngine.State state, int pool, boolean depleted) {
        String out = title
                .replace(PH_ENERGY, String.valueOf(state.energy))
                .replace(PH_POOL, String.valueOf(pool))
                .replace(PH_NAEGONG, String.valueOf((int) Math.floor(state.naegong)));
        return depleted ? out + depletedSuffix : out;
    }

    // ─── 해제 — 떠난 몸의 바는 남지 않는다 ───

    /** 바를 거둔다 — 풀이 사라졌거나(리셋·입도진 이송) 손으로 끌 때. 없으면 아무 일도 없다 */
    void hide(Player player) {
        BossBar bar = bars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    /** 로그아웃 — 연결이 끊긴 몸의 기록만 지운다 (SkillListener.onQuit 의 {@code hud.forget} 과 한 자리) */
    void forget(UUID id) {
        bars.remove(id);
    }

    /** 플러그인 내림 — 남은 바를 전부 거둔다. 리로드 후 유령 바가 화면에 살아남지 않게 */
    void clearAll() {
        for (Map.Entry<UUID, BossBar> e : bars.entrySet()) {
            Player player = org.bukkit.Bukkit.getPlayer(e.getKey());
            if (player != null) {
                player.hideBossBar(e.getValue());
            }
        }
        bars.clear();
    }
}
