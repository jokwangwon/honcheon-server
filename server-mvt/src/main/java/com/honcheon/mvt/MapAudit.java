package com.honcheon.mvt;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 지도의 눈 — <b>등록부의 모든 땅이 그 지형답게 서 있는가.</b>
 *
 * <h2>왜 이 눈이 있는가 — 침묵이 60배로 있었다</h2>
 * 강에서 우리는 이 병을 찾았다:
 * <pre>   물이 없으면 수로채가 안 선다 → 구역이 없다 → 검수가 안 불린다 → <b>위반 0건</b></pre>
 * <b>짓지 않으면 위반이 없다.</b> 그런데 그 침묵은 강 하나의 것이 아니었다.
 *
 * <p>등록부에는 좌표를 가진 장소가 <b>65곳</b> 있고, 그중 <b>땅이 있는 곳은 넷뿐</b>이다.
 * 나머지 61곳은 검수를 <b>통과하는 게 아니라 받지 않는다.</b> 그리고 그중 일부는
 * <b>지형이 선언만 되고 조성기가 빚지 않는다</b> — {@code Profile.그대로} 로 조용히 떨어진다.
 * 강이 정확히 그랬다. <b>아무도 그것을 재지 않았다.</b>
 *
 * <h2>이 눈이 묻는 셋</h2>
 * <ol>
 *   <li><b>빚는가</b> — 지형이 선언됐는데 조성기가 {@code 그대로} 로 떨어뜨리는가.
 *       그렇다면 <b>왜인지 등록부에 적혀 있는가</b>. 적혀 있지 않으면 그것은 <b>침묵</b>이고, 침묵은 위반이다</li>
 *   <li><b>땅이 있는가</b> — 조성됐는가. 안 됐으면 <b>"아직 땅이 없다"고 말한다</b> (조용히 통과시키지 않는다)</li>
 *   <li><b>미해결을 미해결이라 하는가</b> — 자연에 맡기되 <b>보장이 없는</b> 것 (섬)은
 *       'pending' 으로 등록해야 한다. 그러면 눈이 <b>계속 짖는다</b> — 잊히지 않도록</li>
 * </ol>
 *
 * <p><b>세계를 읽지 않는다.</b> 등록부와 조성기의 판단만 본다 — 그래서 <b>서버 없이 시험된다</b>.
 * (땅의 <i>품질</i>은 {@code TerrainAudit}·{@code RiverAudit} 이 잰다. 여기는 <b>빠짐</b>을 잰다.)
 */
final class MapAudit {

    private MapAudit() {
    }

    private static Map<String, String> natural = new LinkedHashMap<>();
    private static Map<String, String> pending = new LinkedHashMap<>();

    /** {@code config/terrain.yml} 의 {@code natural:} · {@code pending:} 를 읽는다 */
    @SuppressWarnings("unchecked")
    static void load(java.nio.file.Path configDir) {
        natural = new LinkedHashMap<>();
        pending = new LinkedHashMap<>();
        java.nio.file.Path file = configDir.resolve("terrain.yml");
        if (!java.nio.file.Files.isRegularFile(file)) {
            return;
        }
        Map<String, Object> root = com.honcheon.core.rules.RulesConfig.load(file);
        read(root.get("natural"), natural);
        read(root.get("pending"), pending);
    }

    private static void read(Object raw, Map<String, String> into) {
        if (raw instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                into.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
    }

    /**
     * <b>부르는 법 (한 줄).</b> 등록부 전체를 잰다.
     *
     * @param built 땅이 있는 장소의 id (조성 원장 — {@code plugin.regionBase} 가 아는 것 + 청하현 무대)
     */
    static List<String> audit(WorldMap map, Set<String> built) {
        List<Verdict> v = judge(map.all(),
                p -> TerrainForge.profile(p).name(),
                RiverForge.registeredIds(),
                natural, pending, built);
        return report(v);
    }

    private static final String OK = ChatColor.GREEN + "✓ ";
    private static final String BAD = ChatColor.RED + "✗ ";
    private static final String WARN = ChatColor.YELLOW + "! ";
    private static final String INFO = ChatColor.GRAY + "  ";
    private static final String HEAD = ChatColor.AQUA + "";

    /**
     * 한 장소의 판정.
     *
     * @param shaped  조성기가 이 땅을 빚는가 ({@code 그대로} 가 아닌가)
     * @param excuse  안 빚는다면 <b>등록된 사유</b> (없으면 null — 그것이 침묵이다)
     * @param pending 안 빚는데 <b>미해결</b>로 등록됐는가 (자연에 맡기지만 보장이 없다)
     * @param built   땅이 있는가
     */
    record Verdict(String id, String name, String terrain, String profile,
                   boolean shaped, String excuse, boolean pending, boolean built) {
    }

    /**
     * <b>알맹이.</b> 세계를 모른다 — 등록부만 본다. 그래서 시험할 수 있다.
     *
     * @param places  등록된 장소 (좌표 있는 것만)
     * @param profile 그 장소의 윤곽 이름을 돌려주는 것 ({@code TerrainForge.profile})
     * @param rivers  물길이 등록된 장소 ({@code rivers.yml}) — 이들은 {@code 그대로} 여도 <b>강이 판다</b>
     * @param natural 자연에 맡긴다고 <b>선언된</b> 것 → 사유 (terrain.yml {@code natural:})
     * @param pending <b>미해결</b>로 선언된 것 → 사유 (terrain.yml {@code pending:})
     * @param built   땅이 있는 장소 (조성 원장)
     */
    static List<Verdict> judge(List<WorldMap.Place> places,
                               java.util.function.Function<WorldMap.Place, String> profile,
                               Set<String> rivers, Map<String, String> natural,
                               Map<String, String> pending, Set<String> built) {
        List<Verdict> out = new ArrayList<>();
        for (WorldMap.Place p : places) {
            if (p.terrain() == null || p.terrain().isBlank()) {
                continue;   // 지형을 주문하지 않은 곳 — 빚을 것이 없다 (망·무거점)
            }
            String prof = profile.apply(p);
            boolean shaped = !"그대로".equals(prof);
            if (!shaped && rivers.contains(p.id())) {
                shaped = true;
                prof = "강";   // ★ 강·수향·물가 — RiverForge 가 판다 (윤곽표는 '그대로'지만 물은 파진다)
            }
            String excuse = natural.get(p.id());
            if (excuse == null) {
                excuse = natural.get(p.terrain());   // 지형 단위로 선언해도 된다 (밀림 전체 등)
            }
            String pend = pending.get(p.id());
            if (pend == null) {
                pend = pending.get(p.terrain());
            }
            out.add(new Verdict(p.id(), p.name(), p.terrain(), prof, shaped,
                    pend != null ? pend : excuse, pend != null, built.contains(p.id())));
        }
        return out;
    }

    /** 판정을 사람의 말로 */
    static List<String> report(List<Verdict> verdicts) {
        List<String> out = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        out.add(HEAD + "══ 지도 검수 — 등록된 땅이 그 지형답게 서 있는가 (" + verdicts.size() + "곳) ══");

        // ─── ① 빚는가 — 선언만 되고 조용히 안 빚어지는 땅 ───
        out.add(HEAD + "① 빚는가 — 지형이 선언됐는데 조성기가 그냥 지나치는가");
        List<String> silent = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        Map<String, List<String>> byExcuse = new LinkedHashMap<>();
        for (Verdict v : verdicts) {
            if (v.shaped()) {
                continue;
            }
            if (v.pending()) {
                pending.add(v.name() + "(" + v.terrain() + ")");
            } else if (v.excuse() != null) {
                byExcuse.computeIfAbsent(v.excuse(), k -> new ArrayList<>()).add(v.name());
            } else {
                silent.add(v.name() + "(" + v.terrain() + ")");
            }
        }
        for (Map.Entry<String, List<String>> e : byExcuse.entrySet()) {
            out.add(OK + "  자연에 맡긴다 (" + e.getValue().size() + "곳) — " + e.getKey());
        }
        if (!pending.isEmpty()) {
            // ★ 미해결은 **위반이다**. 다만 '몰랐다'가 아니라 '알고 있다'는 위반이다 — 잊히지 않도록 짖는다
            out.add(WARN + "  미해결 " + pending.size() + "곳 — " + String.join(" · ", pending));
            for (Verdict v : verdicts) {
                if (!v.shaped() && v.pending()) {
                    out.add(INFO + "    " + v.name() + ": " + v.excuse());
                }
            }
            violations.add("미해결 지형(" + pending.size() + "곳)");
        }
        if (!silent.isEmpty()) {
            out.add(BAD + "  ★ 침묵 " + silent.size() + "곳 — 지형이 선언됐는데 빚지도 않고 "
                    + "사유도 없다: " + String.join(" · ", silent));
            out.add(INFO + "    강이 정확히 이랬다. terrain.yml 의 natural: 또는 pending: 에 "
                    + "적든지, 윤곽을 만들든지 하라");
            violations.add("침묵 지형(" + silent.size() + "곳)");
        }
        if (silent.isEmpty() && pending.isEmpty()) {
            out.add(OK + "  빚지 않는 땅은 전부 이유가 적혀 있다");
        }

        // ─── ② 땅이 있는가 ───
        out.add(HEAD + "② 땅이 있는가 — 등록됐는데 아직 안 선 곳");
        List<String> unbuilt = new ArrayList<>();
        for (Verdict v : verdicts) {
            if (!v.built()) {
                unbuilt.add(v.name());
            }
        }
        int built = verdicts.size() - unbuilt.size();
        out.add(INFO + "  땅이 있다 " + built + "/" + verdicts.size() + "곳");
        if (!unbuilt.isEmpty()) {
            // ★ 이것은 **위반이 아니다** — 단계적 조성은 의도다 (build: later).
            //   그러나 **말은 해야 한다.** 조용히 통과시키면 그것이 그 침묵이다
            out.add(WARN + "  아직 땅이 없다 — " + unbuilt.size() + "곳 (검수를 통과한 게 아니라 "
                    + "**검수를 받지 않았다**)");
        } else {
            out.add(OK + "  등록된 땅이 전부 서 있다");
        }

        // ─── 윤곽 분포 (무엇을 어떻게 빚고 있는가) ───
        out.add(HEAD + "── 윤곽 분포 ──");
        Map<String, Integer> dist = new TreeMap<>();
        for (Verdict v : verdicts) {
            dist.merge(v.profile(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : dist.entrySet()) {
            out.add(INFO + "  " + e.getKey() + " " + e.getValue() + "곳");
        }

        out.add(HEAD + "── 총평 ──");
        out.add(violations.isEmpty()
                ? OK + "위반 0건 — 빚지 않는 땅도 전부 이유가 있다"
                : BAD + "위반 " + violations.size() + "건: " + String.join(" / ", violations));
        return out;
    }

    /** 위반만 (시험이 읽는다) */
    static List<String> violations(List<Verdict> verdicts) {
        List<String> out = new ArrayList<>();
        for (String line : report(verdicts)) {
            String plain = ChatColor.stripColor(line);
            if (plain.startsWith("✗ ")) {
                out.add(plain.substring(2).trim());
            }
        }
        return out;
    }
}
