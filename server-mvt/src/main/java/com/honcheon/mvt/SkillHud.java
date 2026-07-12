package com.honcheon.mvt;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 무공 HUD + 파티클 예산 게이트.
 *
 * <p>HUD 매핑 (mc_action_mapping.md 2장 · cultivation.yml "XP 바 = 내력 유지"):
 * <ul>
 *   <li>경험치 바 = 내력 (레벨 숫자 = 내공 화후 단계, 게이지 = 내력 잔량)</li>
 *   <li>액션바 = 격 태세 · 내력 게이지 글리프 · 쿨다운</li>
 *   <li>아이템 쿨다운 = 무공 후딜·발출 쿨다운 (바닐라 스와이프)</li>
 * </ul>
 *
 * <p>파티클 예산 (performance.yml F-P1): 전역 4000/틱 · 시야당 600/틱 · LOD 16m 전량 /
 * 32m 절반 / 그 밖 컬링. 예산 초과 시 <b>연출만 강등되고 판정은 불변이다</b> —
 * 이 클래스는 판정을 모른다 (한 방향 의존).
 */
final class SkillHud {

    private final SkillEngine engine;

    /** 이번 틱 전역 발행량 — newTick() 이 0으로 되돌린다 (중앙 티커 1개, F-P2) */
    private int globalThisTick;
    private final Map<UUID, Integer> viewThisTick = new HashMap<>();

    SkillHud(SkillEngine engine) {
        this.engine = engine;
    }

    void newTick() {
        globalThisTick = 0;
        viewThisTick.clear();
    }

    // ─── 파티클 ───

    /**
     * 관람자별 발행 — 시야당 예산을 지키려면 브로드캐스트가 아니라 관람자별로 쏴야 한다.
     * LOD: 16m 이내 전량 · 16~32m 절반 · 32m 밖 생략.
     *
     * @return 실제 발행량 (예산 초과 시 0 — 강등)
     */
    int emit(Location at, Particle particle, int count, double dx, double dy, double dz,
             double extra, Object data) {
        return emit(at, particle, count, dx, dy, dz, extra, data, false);
    }

    /**
     * @param priority 오의 — "파티클 예산 내 최우선순위 (예외 아닌 우선권)" (ultimate_arts world_weight).
     *                 예산이 모자라면 <b>생략되는 대신 개수가 깎인다</b>. 예산 자체는 절대 넘지 않는다.
     */
    int emit(Location at, Particle particle, int count, double dx, double dy, double dz,
             double extra, Object data, boolean priority) {
        if (count <= 0 || at.getWorld() == null) {
            return 0;
        }
        int emitted = 0;
        for (Player viewer : at.getWorld().getPlayers()) {
            if (!viewer.getWorld().equals(at.getWorld())) {
                continue;
            }
            double dist = viewer.getLocation().distance(at);
            if (dist > engine.cullBeyond()) {
                continue;                                     // 32m 밖 — 생략
            }
            int n = dist <= engine.lodFull() ? count
                    : dist <= engine.lodHalf() ? Math.max(1, count / 2) : 0;
            if (n <= 0) {
                continue;
            }
            UUID id = viewer.getUniqueId();
            int used = viewThisTick.getOrDefault(id, 0);
            int room = Math.min(engine.particlePerPlayerPerTick() - used,
                    engine.particleGlobalPerTick() - globalThisTick);
            if (n > room) {
                if (!priority || room <= 0) {
                    continue;                                 // 예산 초과 — 이 관람자에겐 강등(생략)
                }
                n = room;                                     // 오의는 생략되지 않는다 — 깎일 뿐이다
            }
            viewThisTick.put(id, used + n);
            globalThisTick += n;
            emitted += n;
            viewer.spawnParticle(particle, at, n, dx, dy, dz, extra, data);
        }
        return emitted;
    }

    int emit(Location at, Particle particle, int count, double spread, double extra) {
        return emit(at, particle, count, spread, spread, spread, extra, null, false);
    }

    /** 오의 전용 — 예산 내 최우선순위 */
    int emitPriority(Location at, Particle particle, int count, double spread, double extra) {
        return emit(at, particle, count, spread, spread, spread, extra, null, true);
    }

    // ─── HUD ───

    /** 경험치 바 = 내력. 레벨 숫자 = 내공 화후 단계 (소수 2자리 × 100 은 과하다 — 정수 단계만) */
    void energyBar(Player player, SkillEngine.State state) {
        int pool = engine.pool(state.naegong);
        player.setLevel((int) Math.floor(state.naegong));
        player.setExp(pool <= 0 ? 0f : (float) Math.max(0.0, Math.min(1.0, state.energy / (double) pool)));
    }

    /** 액션바 — 격 태세 · 내력 · 쿨다운. 수치 비노출 원칙의 예외는 자원(내력)뿐이다 */
    void statusBar(Player player, SkillEngine.State state, long now) {
        int pool = engine.pool(state.naegong);
        StringBuilder line = new StringBuilder();
        String grade = state.armed == null ? SkillEngine.BARE : state.armed;
        line.append(gradeColor(grade)).append(gradeLabel(grade));
        if (pool > 0) {
            line.append(ChatColor.DARK_GRAY).append(" │ ")
                    .append(ChatColor.AQUA).append(Glyphs.gauge(state.energy / (double) pool))
                    .append(ChatColor.WHITE).append(' ').append(state.energy).append('/').append(pool);
        } else {
            line.append(ChatColor.DARK_GRAY).append(" │ ").append(ChatColor.GRAY).append("내력 없음");
        }
        if (engine.isDepleted(state.energy) && pool > 0) {
            line.append(ChatColor.RED).append(" (고갈 −2)");
        }
        int shot = state.cooldownLeft("발출", now);
        if (shot > 0) {
            line.append(ChatColor.DARK_GRAY).append(" │ ").append(ChatColor.YELLOW)
                    .append("발출 ").append(String.format("%.1f초", shot / 20.0));
        }
        // 오의 — 발동권('흐름')은 게이지가 아니라 '읽어낸 순간'이다. 찼다는 사실만 보여 준다
        String stage = engine.ultimateStage(state.realm);
        if (stage != null && state.ultimateId != null) {
            SkillEngine.Ultimate art = engine.ultimate(state.ultimateId);
            boolean ready = state.flow >= engine.flowRequired()
                    && state.ultimateUses < engine.ultimateLimit(state.realm);
            line.append(ChatColor.DARK_GRAY).append(" │ ")
                    .append(ready ? ChatColor.LIGHT_PURPLE : ChatColor.DARK_GRAY)
                    .append(art == null ? "오의" : art.name())
                    .append(ready ? ChatColor.WHITE + " ▶F" : ChatColor.DARK_GRAY + " "
                            + flowDots(state.flow, engine.flowRequired()));
        }
        actionBar(player, line.toString());
    }

    /** 흐름 — 아슬아슬한 성공 이상 공방 누적 (수치가 아니라 점으로 — 오의는 게이지가 아니다) */
    private static String flowDots(int flow, int need) {
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < need; i++) {
            dots.append(i < flow ? '●' : '○');
        }
        return dots.toString();
    }

    static String gradeLabel(String grade) {
        if (SkillEngine.GUARD.equals(grade)) {
            return "호신강기(護身)";
        }
        return SkillEngine.BARE.equals(grade) ? "외공(外功)" : grade + "(氣)";
    }

    /** 격의 색 — 응집이 높을수록 밝고 차갑다 (텔레그래프의 색 문법: 상대가 눈으로 읽는다) */
    static ChatColor gradeColor(String grade) {
        return switch (grade == null ? "" : grade) {
            case "발경" -> ChatColor.GOLD;
            case "검기" -> ChatColor.AQUA;
            case "강기" -> ChatColor.LIGHT_PURPLE;
            case "호신강기" -> ChatColor.YELLOW;
            case "어검" -> ChatColor.DARK_PURPLE;
            case "심검" -> ChatColor.WHITE;
            default -> ChatColor.GRAY;
        };
    }

    static void actionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
    }
}
