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
 * <p>HUD 매핑 (mc_action_mapping.md 2장 · ★사용자 확정 2026-07-15):
 * <ul>
 *   <li>경험치 바 = <b>v3 경험/레벨</b> (cultivation_v3_levels.md §4-b — 이 클래스는 손대지 않는다)</li>
 *   <li>보스바 = 내공/내력 ({@link EnergyBossBar} — 옛 XP바 세입자의 이사 목적지)</li>
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
    /**
     * <b>한 줄의 주인</b> — 순간 사건(판정·격 태세 전환·경공)의 flash 가 잠깐 이기고,
     * 그 밖에는 {@link #statusBar}(지속 상태 합성)가 주인이다.
     *
     * <p>flash 채널이 없으면 statusBar(4틱마다)가 그 글자를 0.2초 만에 덮어 버린다 —
     * 즉 <b>화면이 판정을 못 말한다</b> (B-116 이 실사용에서 그 겹침을 봤다). 새 채널(보스바·
     * 사이드바)을 열지 않고, 있던 액션바에 <b>우선권</b>을 준다: 읽을 시간
     * ({@code skill_motion.yml hud.flash_read_ticks}) 동안 이 줄이 이긴다.
     * 주인 규칙 자체는 {@link HudLine} — 순수해서 하네스가 시험할 수 있다.
     */
    private final HudLine line = new HudLine();
    /** 팔레트 이름(smoke·end_rod…) → 바닐라 파티클. 등록부의 문자열이 Bukkit 타입이 되는 <b>유일한 자리</b> */
    private final Map<String, Particle> resolved = new HashMap<>();
    private final java.util.Set<String> unknownReported = new java.util.HashSet<>();

    SkillHud(SkillEngine engine) {
        this.engine = engine;
    }

    void newTick() {
        globalThisTick = 0;
        viewThisTick.clear();
    }

    // ─── 팔레트 해석 ───

    /**
     * 등록부의 팔레트 이름 → 바닐라 파티클. <b>코드가 파티클을 고르지 않는다</b> — 이름을 받아 옮길 뿐이다
     * (등록제 규약: config/skill_motion.yml palette 가 정본. motion_audit ④가 팔레트 밖의 이름을 잡는다).
     * 모르는 이름은 조용히 버린다 — 연출이 없다고 판정이 멈추면 안 된다.
     */
    private Particle particle(String name) {
        if (name == null || name.isEmpty() || "null".equals(name)) {
            return null;
        }
        Particle cached = resolved.get(name);
        if (cached != null) {
            return cached;
        }
        try {
            Particle p = Particle.valueOf(name.toUpperCase(java.util.Locale.ROOT));
            resolved.put(name, p);
            return p;
        } catch (IllegalArgumentException e) {
            if (unknownReported.add(name)) {
                org.bukkit.Bukkit.getLogger().warning(
                        "[혼천] 모션 등록부에 없는 파티클: " + name + " (config/skill_motion.yml palette)");
            }
            return null;
        }
    }

    // ─── 먹빛 (inks) — 등록부의 색 이름이 Bukkit 의 색이 되는 **유일한 자리** ───

    /**
     * 등록부의 먹빛 이름 → {@link Particle.DustOptions}.
     *
     * <p><b>코드가 색을 고르지 않는다.</b> {@code config/skill_motion.yml inks} 가 RGB 를 대고,
     * 이 함수는 그것을 옮길 뿐이다. 등록되지 않은 이름은 <b>소리내어</b> 경고하고 null 을 준다 —
     * 그러면 DUST 는 발행되지 않는다 (색 없는 dust 는 바닐라가 거절한다: 조용히 예외를 던진다).
     *
     * <p>금지색(형광 핑크·네온 보라·과도한 노랑)은 여기서 재지 않는다. 그것은 <b>등록부의 죄</b>이므로
     * {@code motion_audit.py} 축 ⑦ 이 배포 전에 잡는다 — 런타임에 색을 검열하면 등록제가 두 겹이 된다.
     */
    private Object dust(String inkName) {
        if (inkName == null) {
            return null;
        }
        Particle.DustOptions cached = dusts.get(inkName);
        if (cached != null) {
            return cached;
        }
        SkillEngine.InkColor ink = engine.inkColor(inkName);
        if (ink == null) {
            if (unknownReported.add("ink:" + inkName)) {
                org.bukkit.Bukkit.getLogger().warning(
                        "[혼천] 모션 등록부에 없는 먹빛: " + inkName + " (config/skill_motion.yml inks)");
            }
            return null;
        }
        Particle.DustOptions opts = new Particle.DustOptions(
                org.bukkit.Color.fromRGB(ink.r(), ink.g(), ink.b()), ink.size());
        dusts.put(inkName, opts);
        return opts;
    }

    private final Map<String, Particle.DustOptions> dusts = new HashMap<>();

    /**
     * 색이 필요한 파티클인가 — DUST 는 데이터 없이 발행하면 바닐라가 예외를 던진다.
     * 등록부가 색을 안 적었으면 <b>발행하지 않는다</b> (조용한 예외보다 조용한 생략이 낫다 — 판정은 불변).
     */
    private boolean needsInk(Particle p) {
        return p.getDataType() == Particle.DustOptions.class;
    }

    // ─── 파티클 (등록부 이름으로 발행 — 이 이름 오버로드가 SkillListener 의 유일한 창구다) ───

    int emit(Location at, String particle, int count, double spread, double extra) {
        return emit(at, particle, null, count, spread, extra, false);
    }

    int emit(Location at, String particle, int count, double spread, double extra, Object data) {
        Particle p = particle(particle);
        return p == null ? 0 : emit(at, p, count, spread, spread, spread, extra, data, false);
    }

    /** 먹빛을 실은 발행 — 등록부의 색 이름을 그대로 받는다 (오의의 다층 궤적·운무가 이 길로 온다) */
    int emit(Location at, String particle, String ink, int count, double spread, double extra,
             boolean priority) {
        Particle p = particle(particle);
        if (p == null || count <= 0) {
            return 0;
        }
        Object data = dust(ink);
        if (data == null && needsInk(p)) {
            return 0;   // 색이 없는 dust — 등록부가 색을 안 적었다 (dust() 가 이미 소리냈다)
        }
        return emit(at, p, count, spread, spread, spread, extra, data, priority);
    }

    /** 오의 — 예산 내 최우선권 (생략되지 않고 깎인다) */
    int emitPriority(Location at, String particle, int count, double spread, double extra) {
        Particle p = particle(particle);
        return p == null ? 0 : emit(at, p, count, spread, spread, spread, extra, null, true);
    }

    /** 한 발 = 등록부의 Fx 한 줄 (파티클·개수·퍼짐·속도·<b>먹빛</b>) */
    int emit(Location at, SkillEngine.Fx fx, boolean priority) {
        return fx == null || !fx.present() ? 0 : emit(at, fx, fx.count(), priority);
    }

    /** 개수만 등록부에서 떼어 낸 발행 (타격 풀을 대상 수로 나눌 때 — budget.impact_pool) */
    int emit(Location at, SkillEngine.Fx fx, int count, boolean priority) {
        if (fx == null || fx.particle() == null || count <= 0) {
            return 0;
        }
        return emit(at, fx.particle(), fx.ink(), count, fx.spread(), fx.extra(), priority);
    }

    // ─── 파티클 (내부 — 예산 게이트) ───

    /**
     * 관람자별 발행 — 시야당 예산을 지키려면 브로드캐스트가 아니라 관람자별로 쏴야 한다.
     * LOD: 16m 이내 전량 · 16~32m 절반 · 32m 밖 생략.
     *
     * @return 실제 발행량 (예산 초과 시 0 — 강등)
     */
    private int emit(Location at, Particle particle, int count, double dx, double dy, double dz,
                     double extra, Object data) {
        return emit(at, particle, count, dx, dy, dz, extra, data, false);
    }

    /**
     * @param priority 오의 — "파티클 예산 내 최우선순위 (예외 아닌 우선권)" (ultimate_arts world_weight).
     *                 예산이 모자라면 <b>생략되는 대신 개수가 깎인다</b>. 예산 자체는 절대 넘지 않는다.
     */
    private int emit(Location at, Particle particle, int count, double dx, double dy, double dz,
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

    // ─── HUD ───

    /**
     * 생명 틱 — 몸을 등록부와 맞춘다 (경지·장비가 바뀌면 다음 틱에 몸이 따라온다).
     *
     * <p>승급 훅·장비 훅을 심는 대신 <b>매 틱 대조</b>한다. 훅은 빠뜨리면 조용히 틀리고,
     * 대조는 빠뜨릴 수가 없다. 어긋났을 때만 쓰므로 비용은 비교 한 번이다 (O(1)).
     * {@link Vitality} 가 배선되지 않았으면 아무 일도 하지 않는다.
     */
    void vitalityTick(Player player, SkillEngine.State state, PlayerLedger ledger) {
        Vitality vitality = Vitality.get();
        if (vitality != null) {
            // 원장을 함께 준다 — 내구는 경지만이 아니라 **수련한 몸**에서도 온다 (외공 = 근력·체력)
            vitality.reconcile(player, state.realm, ledger);
        }
    }

    // ★ 묘비 — energyBar(setLevel=내공·setExp=내력/풀)는 여기 살았다 (2026-07-15 철거).
    //   XP바는 이제 v3 경험/레벨의 자리다 (cultivation_v3_levels.md §4-b ★사용자 확정) —
    //   이 클래스는 다시는 setLevel/setExp 를 만지지 않는다. 내공/내력 표시는
    //   EnergyBossBar.update 로 이사했다 (등록부: skill_motion.yml hud.energy_bossbar).

    /**
     * 액션바 — <b>내구(부상)</b> · 격 태세 · 내력 · 쿨다운. 수치 비노출 원칙의 예외는 자원(내력·내구)뿐이다.
     *
     * <p><b>왜 내구가 액션바인가</b> (docs/design/vitality.md): 전투 중에 눈은 <b>조준점</b>에 있다.
     * 액션바는 조준점 바로 아래 — 눈이 이미 보고 있는 자리이고, 하트도 같은 자리에 있다.
     * 보스바(화면 위)는 눈이 가지 않는 곳이고, 사이드바는 화면을 <b>가리는</b> 바로 그 짓이다.
     * 그래서 생명은 이미 눈이 있는 곳에 그린다 — 새 채널을 열지 않는다.
     *
     * <p><b>부상이 보인다</b>: 이 세계는 HP 바가 아니라 부상으로 싸운다. 경상(−1)·중상(−2)·빈사(−3)는
     * <b>색과 글자</b>로 동시에 나온다 (색 단독 금지 — resourcepack_design colorblind_rule).
     * 팩이 없으면 게이지 글리프가 □ 로 보이지만 <b>숫자와 글자는 그대로 읽힌다</b>.
     */
    /**
     * 판정의 한 줄 — {@code until} 틱까지 액션바를 이 글자가 갖는다.
     * <b>막았으면 막았다고, 흘렸으면 흘렸다고, 피했으면 피했다고</b> 화면이 말해야 한다.
     */
    void flash(Player player, String text, long until) {
        line.flash(player.getUniqueId(), text, until);
        actionBar(player, text);   // 지금 당장 — 다음 statusBar 틱을 기다리지 않는다
    }

    void forget(UUID id) {
        line.forget(id);
    }

    /**
     * 지속 상태 합성 한 줄 — flash(순간 사건)가 살아 있으면 그것이 이긴다 ({@link HudLine} 주인 규칙).
     *
     * @param rider 경공 유지 조각 (B-116) — 켜져 있는 동안 상시 병기된다. null 이면 그리지 않는다.
     *              문구는 등록부(gyeonggong.yml messages.riding)의 것 — 만드는 손은 GyeonggongListener.
     */
    void statusBar(Player player, SkillEngine.State state, long now, String stance, String rider) {
        String owned = line.owner(player.getUniqueId(), now);
        if (owned != null) {
            actionBar(player, owned);
            return;   // 판정이 이긴다 — 상태 표시가 판정의 한 줄을 덮지 않는다
        }
        int pool = engine.pool(state.naegong);

        // ─── 방어 태세 — <b>내가 키운 것이 화면에 뜬다</b> (growth_axes.md §6-②) ───
        //   민첩을 키운 자는 '회피', 근력을 키운 자는 '막기', 감각을 키운 자는 '흘리기'가 뜬다.
        //   그리고 몸짓을 바꾸면 그 자리에서 글자가 바뀐다 — 무엇을 하고 있는지 화면이 안다.
        String stanceSeg = stance == null ? null : ChatColor.GREEN + stance;

        // ─── 격 두름·경공 — ★ 입도진에서는 그리지 않는다 ───
        //   입도진의 몸에는 경지가 없다 — 없는 것의 표시가 과제 안내와 한 줄을 다퉜다 (사용자 실측 2026-07-15).
        //   입도진의 겹침 실측은 격 두름과 나는발 둘이었다 — 사용자 2026-07-15.
        String gradeSeg;
        if (Antechamber.isAntechamber(player.getWorld())) {
            gradeSeg = null;
            rider = null;
        } else {
            String grade = state.armed == null ? SkillEngine.BARE : state.armed;
            gradeSeg = gradeColor(grade) + gradeLabel(grade);
        }

        String energySeg;
        if (pool > 0) {
            energySeg = ChatColor.AQUA + Glyphs.gauge(state.energy / (double) pool)
                    + ChatColor.WHITE + ' ' + state.energy + '/' + pool
                    + (engine.isDepleted(state.energy) ? ChatColor.RED + " (고갈 −2)" : "");
        } else {
            energySeg = ChatColor.GRAY + "내력 없음";
        }

        int shot = state.cooldownLeft("발출", now);
        String shotSeg = shot <= 0 ? null
                : ChatColor.YELLOW + "발출 " + String.format("%.1f초", shot / 20.0);

        // 오의 — 발동권('흐름')은 게이지가 아니라 '읽어낸 순간'이다. 찼다는 사실만 보여 준다
        String ultSeg = null;
        String stage = engine.ultimateStage(state.realm);
        if (stage != null && state.ultimateId != null) {
            SkillEngine.Ultimate art = engine.ultimate(state.ultimateId);
            boolean ready = state.flow >= engine.flowRequired()
                    && state.ultimateUses < engine.ultimateLimit(state.realm);
            ultSeg = (ready ? ChatColor.LIGHT_PURPLE : ChatColor.DARK_GRAY)
                    + (art == null ? "오의" : art.name())
                    + (ready ? ChatColor.WHITE + " ▶F" : ChatColor.DARK_GRAY + " "
                            + flowDots(state.flow, engine.flowRequired()));
        }

        // ─── 합성 — 생명(제일 앞. 목숨보다 앞에 오는 정보는 없다) │ 태세 │ 격 │ 내력 │ 경공 │ 발출 │ 오의 ───
        actionBar(player, HudLine.compose(ChatColor.DARK_GRAY + " │ ",
                vitality(player), stanceSeg, gradeSeg, energySeg, rider, shotSeg, ultSeg));
    }

    /**
     * 생명 — 내구 게이지 + 실수치 + 부상 단계.
     *
     * <p>게이지 글리프는 <b>내력의 것을 그대로 쓴다</b> (U+E010~E018) — 등록부가 그러라고 적어 뒀다:
     * "내력·원기 = 같은 글리프를 색 틴트로 재사용 (슬롯 절약)" (resourcepack_design.yml glyph_slots).
     * 가르는 것은 <b>색</b>이다: 내력은 청록(AQUA), 내구는 부상 단계의 색.
     *
     * <p>{@link Vitality} 가 배선되지 않았으면 null — 아무것도 그리지 않는다 (배선이 없다고 HUD가 죽지 않는다).
     */
    private String vitality(Player player) {
        Vitality vitality = Vitality.get();
        var attr = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (vitality == null || attr == null) {
            return null;
        }
        double max = attr.getValue();
        if (max <= 0) {
            return null;
        }
        double ratio = Math.max(0.0, Math.min(1.0, player.getHealth() / max));
        Vitality.Wound wound = vitality.woundOf(ratio);
        ChatColor color = woundColor(wound);

        StringBuilder out = new StringBuilder();
        out.append(color).append(Glyphs.gauge(ratio))                       // 팩 없으면 □ — 숫자가 뜻을 진다
                .append(' ').append((int) Math.ceil(player.getHealth()))
                .append(ChatColor.DARK_GRAY).append('/').append(color).append((int) Math.round(max));
        if (wound != Vitality.Wound.온전) {
            // 부상은 색과 글자로 동시에 — 색맹 규약(색 단독 금지)이자 팩 없는 눈을 위한 보험
            out.append(' ').append(wound.label()).append(' ')
                    .append(signed(vitality.penaltyOf(wound)));
        }
        return out.toString();
    }

    /**
     * 부상의 색 — 무채(수묵) 규약의 <b>예외</b>다. 격은 밝기로 가르지만 <b>부상은 색으로 가른다</b>:
     * 목숨은 1초 안에 읽혀야 하고, 그것이 이 HUD가 채도를 쓰는 유일한 이유다
     * (resourcepack_design: "채도는 의미(체력=주사)에만").
     */
    private static ChatColor woundColor(Vitality.Wound wound) {
        return switch (wound) {
            case 온전 -> ChatColor.WHITE;
            case 경상 -> ChatColor.YELLOW;
            case 중상 -> ChatColor.RED;
            case 빈사 -> ChatColor.DARK_RED;
        };
    }

    private static String signed(int penalty) {
        return penalty <= 0 ? "−" + Math.abs(penalty) : "+" + penalty;
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

    /**
     * 격의 색 — <b>등록부가 정한다</b> (skill_motion.yml grades[].color).
     *
     * <p>수묵 규약: 채색으로 격을 가르지 않는다. 색은 무채(회·백) 뿐이고, 격을 가르는 것은 <b>밝기</b>다
     * (파티클 수·잔광). 색 문법에 기대면 팩 없는 클라이언트에서 격이 사라진다 — 색맹 규약과도 어긋난다.
     * 호신강기만은 형태가 달라 노랑을 쓴다 (액션바 한 줄에서 공격 격과 방어 형태를 갈라야 하므로).
     */
    ChatColor gradeColor(String grade) {
        if (SkillEngine.GUARD.equals(grade)) {
            return ChatColor.YELLOW;
        }
        SkillEngine.GradeMotion m = engine.motionGrade(grade);
        try {
            return m == null ? ChatColor.GRAY : ChatColor.valueOf(m.color());
        } catch (IllegalArgumentException e) {
            return ChatColor.GRAY;
        }
    }

    static void actionBar(Player player, String message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
    }
}
