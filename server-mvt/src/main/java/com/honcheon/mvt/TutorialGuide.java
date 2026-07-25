package com.honcheon.mvt;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 뿌리내림 과정 — 본토 튜토리얼의 안내 층 (B-178 · 정본 {@code docs/design/tutorial_rooting.md}
 * · 등록부 {@code config/tutorial.yml}).
 *
 * <p><b>안내 층이지 판정 층이 아니다</b>: 문을 잠그지 않고(입도진 {@code gating: false} 상속),
 * 보상을 주지 않고(행위 자체가 보상 — 사용자 확정), 개인 사슬(personal_story)에 쓰지 않는다
 * (①마디 닫힘은 봇 원장 소관 — 이 층은 그 조건을 걷기 쉽게 보여 줄 뿐이다).
 *
 * <p>상태는 {@link PlayerLedger#tutorial()} 에 영속한다 (입도진의 메모리 장부와 다른 계약 —
 * 본토의 삶은 이어지는 삶이다). 거울 조건(첫_레벨·배분)은 {@link #mirror} 가 소급 인정한다:
 * 이미-한-몸(기존 캐릭터)은 그 정거장이 저절로 닫힌 채 시작한다.
 *
 * <p>표시는 두 채널뿐이다 — 사이드바 한 줄({@link #trackerLine} · 한월 A7 문법 · 기초 과정만
 * 카운터: personal_story §6.4 부분 개정)과 완료 채팅. 액션바는 쓰지 않는다 (처치 순간은
 * 판정 flash·획득 flash 가 이미 그 줄을 쓴다 — B-123 의 겹침을 다시 만들지 않는다).
 */
final class TutorialGuide implements org.bukkit.event.Listener {

    record Station(String id, String name, int count, String tracker, String doneLine) {
    }

    private final HoncheonMvt plugin;
    private final boolean enabled;
    private final String header;
    private final String format;
    private final String formatSingle;
    private final String doneFormat;
    private final List<String> finishLines;
    private final List<String> arrivalLines;
    private final List<Station> stations = new ArrayList<>();

    /** 과정 전체 완료 표식 — 정거장 id 와 겹치지 않는 예약 키 (finish_lines 를 한 번만 말한다) */
    private static final String FINISHED = "_끝";

    @SuppressWarnings("unchecked")
    TutorialGuide(HoncheonMvt plugin) {
        this.plugin = plugin;
        Map<String, Object> root = com.honcheon.core.rules.RulesConfig.section(
                com.honcheon.core.rules.RulesConfig.load(
                        new java.io.File(plugin.getDataFolder(), "config").toPath()
                                .resolve("tutorial.yml")), "tutorial");
        this.enabled = Boolean.TRUE.equals(root.get("enabled"));
        this.header = str(root.get("header"), "뿌리내림");
        this.format = str(root.get("format"), "§6{header} §f{n}. {tracker} §e{cur}/{max}");
        this.formatSingle = str(root.get("format_single"), "§6{header} §f{n}. {tracker}");
        this.doneFormat = str(root.get("done_format"), "§6과제 » §f{name} §7— 끝");
        List<String> arrive = new ArrayList<>();
        if (root.get("arrival_lines") instanceof List<?> al) {
            al.forEach(l -> arrive.add(String.valueOf(l)));
        }
        this.arrivalLines = List.copyOf(arrive);
        List<String> finish = new ArrayList<>();
        if (root.get("finish_lines") instanceof List<?> fl) {
            fl.forEach(l -> finish.add(String.valueOf(l)));
        }
        this.finishLines = List.copyOf(finish);
        if (root.get("stations") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> st = (Map<String, Object>) m;
                stations.add(new Station(String.valueOf(st.get("id")),
                        str(st.get("name"), String.valueOf(st.get("id"))),
                        st.get("count") instanceof Number n ? Math.max(1, n.intValue()) : 1,
                        str(st.get("tracker"), ""),
                        str(st.get("done_line"), null)));   // 정거장별 완료 문장 (없으면 done_format)
            }
        }
        // 기동 고지 — 침묵 금지: 안내 층이 몇 정거장으로 섰는지 로그가 말한다
        plugin.getLogger().info("뿌리내림 과정 — 정거장 " + stations.size() + " ("
                + (enabled ? "켜짐" : "꺼짐") + ")");
    }

    /**
     * 정거장 계수 +1 — 훅 6곳이 부른다. 접합 전의 몸에는 쌓지 않는다 (과정은 강호에 든
     * 몸의 것이다). 등록부 밖의 id 는 조용히 버리지 않고 경고한다 (훅과 등록부의 표류 감지).
     */
    /**
     * ★서장의 몸에는 침묵한다 (B-179 5차 · 실기동 2026-07-25 "배 위에서 우클릭 하니까
     * 과제 » 맞는 쪽의 선택이라는 문구가 떴음"): 뿌리내림은 <b>본토의 과정</b>이다 — 서장
     * 미완의 몸(항해 중)과 나루·서장 월드에 선 몸에는 계수도, 완료 문구도, 트래커 줄도
     * 전부 침묵한다. 우클릭·웅크림이 태세 가르침으로 세어지면 화면이 배 위에서 본토의
     * 말을 하는 것이다.
     */
    boolean silenced(Player player) {
        return WorldBridge.seojangHolds(player.getUniqueId())
                || Antechamber.isAntechamber(player.getWorld())
                || Voyage.isSea(player.getWorld());
    }

    /**
     * ★첫 출도의 첫걸음 (실기동 2026-07-25 "바로 청하현으로 가버렸고 뭘 해야할지 잘
     * 모르겠어") — 내린 몸에게 트래커 읽는 법과 첫 정거장을 한 번 말해 준다.
     * 첫 정거장을 이미 뗀 몸에게는 침묵한다 (안내 층 — 문은 안 잠근다).
     */
    void arrivalHint(Player player) {
        if (!enabled || arrivalLines.isEmpty() || stations.isEmpty() || silenced(player)) {
            return;
        }
        PlayerLedger ledger = plugin.ledger(player.getUniqueId());
        Station first = stations.get(0);
        if (!ledger.linked()
                || ledger.tutorial().getOrDefault(first.id(), 0) >= first.count()) {
            return;
        }
        arrivalLines.forEach(player::sendMessage);
    }

    void bump(Player player, String stationId) {
        if (!enabled || silenced(player)) {
            return;
        }
        Station st = stationOf(stationId);
        if (st == null) {
            plugin.getLogger().warning("뿌리내림 — 등록부에 없는 정거장을 훅이 불렀다: " + stationId);
            return;
        }
        PlayerLedger ledger = plugin.ledger(player.getUniqueId());
        if (!ledger.linked()) {
            return;
        }
        int cur = ledger.tutorial().getOrDefault(st.id(), 0);
        if (cur >= st.count()) {
            return;   // 이미 닫힌 정거장 — 다시 세지 않는다 (멱등)
        }
        ledger.tutorial().put(st.id(), cur + 1);
        if (cur + 1 >= st.count()) {
            player.sendMessage(st.doneLine() != null ? st.doneLine()
                    : doneFormat.replace("{name}", st.name()));
            finishIfDone(player, ledger);
        }
        plugin.updateSidebar(player);   // 5초 폴링을 기다리지 않는다 — 자는 즉시 움직인다
    }

    // ─── 몸짓 감지 (정거장 「몸짓」) — 장비 없이 언제나 되는 두 몸짓만 (막기는 예고) ───
    //   combat.yml defender_stance_mc.gestures 의 술어 그대로: 흘리기=isSneaking · 회피=isSprinting.
    //   몸짓마다 한 번만 센다 — 표식 키(몸짓·이름)가 정거장 계수와 별도로 장부에 남는다.

    @org.bukkit.event.EventHandler
    public void onSneak(org.bukkit.event.player.PlayerToggleSneakEvent event) {
        if (event.isSneaking()) {
            gesture(event.getPlayer(), "흘리기");
        }
    }

    @org.bukkit.event.EventHandler
    public void onSprint(org.bukkit.event.player.PlayerToggleSprintEvent event) {
        if (event.isSprinting()) {
            gesture(event.getPlayer(), "회피");
        }
    }

    private void gesture(Player player, String name) {
        if (!enabled || silenced(player) || stationOf("몸짓") == null) {
            return;
        }
        PlayerLedger ledger = plugin.ledger(player.getUniqueId());
        if (!ledger.linked()) {
            return;
        }
        String marker = "몸짓·" + name;
        if (ledger.tutorial().containsKey(marker)) {
            return;   // 이 몸짓은 이미 세었다 — 웅크림 연타가 정거장을 혼자 닫으면 오배선이다
        }
        ledger.tutorial().put(marker, 1);
        bump(player, "몸짓");
    }

    /**
     * 거울 소급 (syncSheet 마다) — 이미-한-몸의 정거장을 저절로 닫는다:
     * 첫_레벨 = 레벨 ≥ 2 · 배분 = 레벨 ≥ 2 이고 미사용 포인트 0 (레벨 2면 3포인트를 받았고
     * 남은 게 없으면 쓴 것이다). 그 외 정거장은 그 순간의 훅만이 안다 — 소급하지 않는다.
     */
    void mirror(Player player, PlayerLedger ledger) {
        if (!enabled || silenced(player) || !ledger.linked() || ledger.level() < 2) {
            return;
        }
        closeQuiet(ledger, "첫_레벨");
        if (ledger.points() == 0) {
            closeQuiet(ledger, "배분");
        }
        finishIfDone(player, ledger);
    }

    /**
     * 사이드바 트래커 한 줄 — <b>첫 미완 정거장</b>을 가리킨다 (순서는 안내일 뿐 레일이 아니다).
     * 과정이 없거나(비활성·미접합) 끝났으면 null — 줄이 사라진다 (배우는 동안만 자를 보인다).
     */
    String trackerLine(PlayerLedger ledger) {
        if (!enabled || !ledger.linked() || stations.isEmpty()
                || ledger.tutorial().containsKey(FINISHED)) {
            return null;
        }
        for (int i = 0; i < stations.size(); i++) {
            Station st = stations.get(i);
            int cur = ledger.tutorial().getOrDefault(st.id(), 0);
            if (cur < st.count()) {
                String base = st.count() == 1 ? formatSingle : format;
                return base.replace("{header}", header)
                        .replace("{n}", String.valueOf(i + 1))
                        .replace("{tracker}", st.tracker())
                        .replace("{cur}", String.valueOf(cur))
                        .replace("{max}", String.valueOf(st.count()));
            }
        }
        return null;   // 전부 닫혔는데 FINISHED 가 아직 — finishIfDone 이 곧 채운다
    }

    private void finishIfDone(Player player, PlayerLedger ledger) {
        if (ledger.tutorial().containsKey(FINISHED)) {
            return;
        }
        for (Station st : stations) {
            if (ledger.tutorial().getOrDefault(st.id(), 0) < st.count()) {
                return;
            }
        }
        ledger.tutorial().put(FINISHED, 1);
        finishLines.forEach(player::sendMessage);
    }

    private void closeQuiet(PlayerLedger ledger, String stationId) {
        Station st = stationOf(stationId);
        if (st != null && ledger.tutorial().getOrDefault(st.id(), 0) < st.count()) {
            ledger.tutorial().put(st.id(), st.count());   // 소급 — 이미 산 삶은 조용히 인정된다
        }
    }

    private Station stationOf(String id) {
        for (Station st : stations) {
            if (st.id().equals(id)) {
                return st;
            }
        }
        return null;
    }

    private static String str(Object o, String fallback) {
        return o instanceof String s && !s.isBlank() ? s : fallback;
    }
}
