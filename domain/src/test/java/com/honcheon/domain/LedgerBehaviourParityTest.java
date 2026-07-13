package com.honcheon.domain;

import com.honcheon.core.rules.FactionReactionEngine;
import com.honcheon.core.rules.RegionStateEngine;
import com.honcheon.core.rules.RulesConfig;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ★ <b>행동 불변의 증거</b> — 리팩터링 전후로 <b>같은 입력에 같은 출력</b>인가.
 *
 * <p>이 테스트는 <b>리팩터링 이전의 봇 코드를 그대로 베껴</b> {@code Oracle} 로 세운다
 * (옛 {@code Db.addFavor}/{@code Db.addAttention}/{@code Db.breakFavor}/{@code Db.standing}/
 * {@code Db.nudgeRegion} 과 옛 {@code Factions.decayedAttention}/{@code decayedFavor} —
 * 산수도, 클램프도, 스탬프 순서도, 심지어 <b>코드에 박혀 있던 0~100 도</b> 그대로).
 * 그리고 새 {@link FactionService}/{@link RegionService} 와 <b>줄줄이 대조</b>한다.
 *
 * <p>대조하는 것은 반환값만이 아니다 — <b>장부에 적힌 행 전체</b>(주목·우호·스탬프일·
 * peak_stage·peak_favor)가 마지막 한 칸까지 같아야 한다. 그래야 "플레이어가 겪는 것이
 * 한 톨도 달라지지 않았다"고 말할 수 있다.
 *
 * <p>이것이 초록인 한, 죽은 쌍둥이를 죽인 대가로 세계가 바뀌지는 <b>않았다</b>.
 */
class LedgerBehaviourParityTest {

    static Path cfg(String name) {
        return Path.of(System.getProperty("honcheon.config.dir", "../config")).resolve(name);
    }

    static final Map<String, Object> FACTION_CFG = RulesConfig.load(cfg("faction_reaction.yml"));
    static final Map<String, Object> REGION_CFG = RulesConfig.load(cfg("region_state.yml"));

    // ─────────────────────────────────────────────────────────────────────────
    // 장부 — 메모리 판 (SQLite 대신. 포트가 있으니 도메인은 차이를 모른다)
    // ─────────────────────────────────────────────────────────────────────────

    static final class MemoryLedger implements FactionLedger, RegionLedger {
        final Map<String, Row> standings = new LinkedHashMap<>();
        final Map<String, Integer> region = new LinkedHashMap<>(
                Map.of("치안", 50, "경제", 48, "민심", 55));

        @Override
        public Row standingRow(String factionId, long characterId) {
            return standings.getOrDefault(factionId + "|" + characterId, Row.NONE);
        }

        @Override
        public List<String> standingFactions(long characterId) {
            List<String> out = new ArrayList<>();
            standings.keySet().stream()
                    .filter(k -> k.endsWith("|" + characterId))
                    .map(k -> k.substring(0, k.indexOf('|')))
                    .sorted()
                    .forEach(out::add);
            return out;
        }

        @Override
        public void writeStanding(String factionId, long characterId, Row row) {
            standings.put(factionId + "|" + characterId, row);
        }

        @Override
        public Map<String, Integer> region() {
            return new LinkedHashMap<>(region);
        }

        @Override
        public void writeRegion(Map<String, Integer> values) {
            region.putAll(values);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ORACLE — **리팩터링 이전의 봇 코드** (옛 Db + 옛 Factions, 한 글자도 안 고치고 옮겼다)
    // ─────────────────────────────────────────────────────────────────────────

    /** 옛 Db.Standing */
    record OldStanding(String faction, int attention, int favor, int peakStage, int peakFavor) {
    }

    /** 옛 Db.RawStanding */
    record OldRaw(int attention, int favor, int attentionDay, int favorDay,
                  int peakStage, int peakFavor) {
    }

    /** 옛 봇 코드 그대로 — config 를 제 손으로 읽고, 제 손으로 클램프하고, 제 손으로 감쇠한다 */
    static final class Oracle {
        final Map<String, OldRaw> rows = new LinkedHashMap<>();
        final Map<String, Integer> region = new LinkedHashMap<>(
                Map.of("치안", 50, "경제", 48, "민심", 55));

        // ── 옛 Factions (봇의 재구현) ──

        @SuppressWarnings("unchecked")
        int scoreMax() {
            List<Object> range = (List<Object>) RulesConfig.section(FACTION_CFG, "score").get("range");
            return RulesConfig.intValue(range.get(1));
        }

        @SuppressWarnings("unchecked")
        int favorMax() {
            Map<String, Object> favor = RulesConfig.section(FACTION_CFG, "favor");
            return RulesConfig.intValue(((List<Object>) favor.get("range")).get(1));
        }

        @SuppressWarnings("unchecked")
        int favorLevelMin(String levelName) {
            Map<String, Object> favor = RulesConfig.section(FACTION_CFG, "favor");
            for (Object raw : (List<Object>) favor.get("thresholds")) {
                Map<String, Object> level = (Map<String, Object>) raw;
                if (levelName.equals(level.get("name"))) {
                    return RulesConfig.intValue(level.get("level"));
                }
            }
            throw new IllegalArgumentException("등록되지 않은 우호 등급: " + levelName);
        }

        @SuppressWarnings("unchecked")
        int stageOf(int score) {
            List<Map<String, Object>> stages =
                    (List<Map<String, Object>>) FACTION_CFG.get("stage_thresholds");
            int best = 0;
            int bestMin = Integer.MIN_VALUE;
            for (Map<String, Object> raw : stages) {
                int min = RulesConfig.intValue(raw.get("min_score"));
                if (score >= min && min > bestMin) {
                    bestMin = min;
                    best = RulesConfig.intValue(raw.get("stage"));
                }
            }
            return best;
        }

        int attentionDecayEveryDays() {
            return RulesConfig.intValue(RulesConfig.section(FACTION_CFG, "decay").get("every_days"));
        }

        int attentionDecayAmount() {
            return RulesConfig.intValue(RulesConfig.section(FACTION_CFG, "decay").get("amount"));
        }

        int attentionFloorAfterStage3() {
            return RulesConfig.intValue(
                    RulesConfig.section(FACTION_CFG, "decay").get("floor_after_stage_3"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> favorDecay() {
            Map<String, Object> favor = RulesConfig.section(FACTION_CFG, "favor");
            return (Map<String, Object>) favor.get("decay");
        }

        /** 옛 Factions.decayedAttention */
        int decayedAttention(int attention, int peakStage, int favor, int lastDay, int today) {
            int every = attentionDecayEveryDays();
            int ticks = every <= 0 ? 0 : Math.max(0, (today - lastDay) / every);
            if (ticks == 0) {
                return attention;
            }
            int perTick = Math.abs(attentionDecayAmount()) + (favor >= favorLevelMin("공신") ? 1 : 0);
            int floor = peakStage >= 3 ? attentionFloorAfterStage3() : 0;
            return Math.max(floor, attention - ticks * perTick);
        }

        /** 옛 Factions.decayedFavor */
        int decayedFavor(int favor, int peakFavor, int lastDay, int today) {
            int every = RulesConfig.intValue(favorDecay().get("every_days"));
            int ticks = every <= 0 ? 0 : Math.max(0, (today - lastDay) / every);
            if (ticks == 0) {
                return favor;
            }
            int amount = Math.abs(RulesConfig.intValue(favorDecay().get("amount")));
            int floor = peakFavor >= favorLevelMin("공신")
                    ? RulesConfig.intValue(favorDecay().get("floor_after_공신")) : 0;
            return Math.max(floor, favor - ticks * amount);
        }

        // ── 옛 Db ──

        OldRaw raw(String faction, long ch) {
            return rows.getOrDefault(faction + "|" + ch, new OldRaw(0, 0, 0, 0, 0, 0));
        }

        /** 옛 Db.standing */
        OldStanding standing(String faction, long ch, int today) {
            OldRaw r = raw(faction, ch);
            int favor = decayedFavor(r.favor(), r.peakFavor(), r.favorDay(), today);
            int attention = decayedAttention(r.attention(), r.peakStage(), favor,
                    r.attentionDay(), today);
            return new OldStanding(faction, attention, favor, r.peakStage(), r.peakFavor());
        }

        void upsert(String faction, long ch, int attention, int favor, int attentionDay,
                    int favorDay, int peakStage, int peakFavor) {
            rows.put(faction + "|" + ch,
                    new OldRaw(attention, favor, attentionDay, favorDay, peakStage, peakFavor));
        }

        /** 옛 Db.addAttention */
        int addAttention(String faction, long ch, int delta, int today) {
            OldStanding now = standing(faction, ch, today);
            int next = Math.max(0, Math.min(scoreMax(), now.attention() + delta));
            int peakStage = Math.max(now.peakStage(), stageOf(next));
            upsert(faction, ch, next, now.favor(), today, today, peakStage,
                    Math.max(now.peakFavor(), now.favor()));
            return next;
        }

        /** 옛 Db.addFavor */
        int addFavor(String faction, long ch, int delta, int cap, int today) {
            OldStanding now = standing(faction, ch, today);
            int next = Math.max(0, Math.min(Math.min(cap, favorMax()), now.favor() + delta));
            upsert(faction, ch, now.attention(), next, today, today,
                    Math.max(now.peakStage(), stageOf(now.attention())),
                    Math.max(now.peakFavor(), next));
            return next;
        }

        /** 옛 Db.breakFavor */
        int breakFavor(String faction, long ch, int delta, int today) {
            OldStanding now = standing(faction, ch, today);
            int next = Math.max(0, Math.min(favorMax(), now.favor() + delta));
            upsert(faction, ch, now.attention(), next, today, today, now.peakStage(), next);
            return next;
        }

        /** 옛 Db.nudgeRegion — ★ 0 과 100 이 **코드에 박혀** 있었다 */
        Map<String, Integer> nudgeRegion(Map<String, Integer> deltas) {
            Map<String, String> column = Map.of("치안", "security", "경제", "economy", "민심", "sentiment");
            for (Map.Entry<String, Integer> e : deltas.entrySet()) {
                String col = column.get(e.getKey());
                if (col == null || e.getValue() == 0) {
                    continue;
                }
                region.put(e.getKey(),
                        Math.max(0, Math.min(100, region.get(e.getKey()) + e.getValue())));
            }
            return new LinkedHashMap<>(region);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 대조
    // ─────────────────────────────────────────────────────────────────────────

    private static void assertSameRow(Oracle oracle, MemoryLedger ledger, String faction, long ch,
                                      String where) {
        OldRaw expected = oracle.raw(faction, ch);
        FactionLedger.Row actual = ledger.standingRow(faction, ch);
        assertEquals(expected.attention(), actual.attention(), where + " — 주목");
        assertEquals(expected.favor(), actual.favor(), where + " — 우호");
        assertEquals(expected.attentionDay(), actual.attentionDay(), where + " — 주목 스탬프일");
        assertEquals(expected.favorDay(), actual.favorDay(), where + " — 우호 스탬프일");
        assertEquals(expected.peakStage(), actual.peakStage(), where + " — peak_stage");
        assertEquals(expected.peakFavor(), actual.peakFavor(), where + " — peak_favor");
    }

    @Test
    void 세력_장부_산수가_리팩터링_전과_한_톨도_다르지_않다() throws Exception {
        FactionReactionEngine rules = new FactionReactionEngine(FACTION_CFG);
        MemoryLedger ledger = new MemoryLedger();
        FactionService service = new FactionService(rules, ledger);
        Oracle oracle = new Oracle();

        String[] factionIds = {"orthodox", "haomun", "gwan_gun", "hyeolgyo", "sangdan"};
        // 등록된 입력들 + 경계를 때리는 값들 (상한 초과·하한 미만·0)
        int[] deltas = {0, 1, 2, 4, -4, 7, -1, 30, -30, 100, -100};
        int[] caps = {4, 8, 13, 30, 1};

        Random dice = new Random(20260713L);   // 고정 시드 — 결정론

        for (int step = 0; step < 4000; step++) {
            String faction = factionIds[dice.nextInt(factionIds.length)];
            long ch = 1 + dice.nextInt(3);
            int today = dice.nextInt(400);           // 감쇠가 실제로 물리는 범위 (30일 주기 × 여러 바퀴)
            int delta = deltas[dice.nextInt(deltas.length)];
            int cap = caps[dice.nextInt(caps.length)];

            switch (dice.nextInt(5)) {
                case 0 -> assertEquals(oracle.addAttention(faction, ch, delta, today),
                        service.addAttention(faction, ch, delta, today),
                        "addAttention(" + faction + ", " + ch + ", " + delta + ", d" + today + ")");
                case 1 -> assertEquals(oracle.addFavor(faction, ch, delta, cap, today),
                        service.addFavor(faction, ch, delta, cap, today),
                        "addFavor(" + faction + ", " + ch + ", " + delta + ", cap " + cap
                                + ", d" + today + ")");
                case 2 -> assertEquals(oracle.addFavor(faction, ch, delta, oracle.favorMax(), today),
                        service.addFavor(faction, ch, delta, today),
                        "addFavor(상한없음)");
                case 3 -> assertEquals(oracle.breakFavor(faction, ch, delta, today),
                        service.breakFavor(faction, ch, delta, today),
                        "breakFavor(" + faction + ", " + ch + ", " + delta + ", d" + today + ")");
                default -> {
                    // 읽기도 대조한다 — 감쇠는 **읽는 순간** 정산하므로 읽기가 곧 규칙이다
                    OldStanding expected = oracle.standing(faction, ch, today);
                    FactionService.Standing actual = service.standing(faction, ch, today);
                    assertEquals(expected.attention(), actual.attention(), "standing 주목");
                    assertEquals(expected.favor(), actual.favor(), "standing 우호");
                    assertEquals(expected.peakStage(), actual.peakStage(), "standing peak_stage");
                    assertEquals(expected.peakFavor(), actual.peakFavor(), "standing peak_favor");
                }
            }
            assertSameRow(oracle, ledger, faction, ch, "step " + step);
        }

        // 장부에 적힌 모든 칸이 마지막까지 같은가
        assertEquals(oracle.rows.size(), ledger.standings.size(), "장부의 칸 수");
        for (String key : oracle.rows.keySet()) {
            String faction = key.substring(0, key.indexOf('|'));
            long ch = Long.parseLong(key.substring(key.indexOf('|') + 1));
            assertSameRow(oracle, ledger, faction, ch, "종료 시점 " + key);
        }
    }

    @Test
    void 지역_장부_산수가_리팩터링_전과_한_톨도_다르지_않다() throws Exception {
        RegionStateEngine rules = new RegionStateEngine(REGION_CFG);
        MemoryLedger ledger = new MemoryLedger();
        RegionService service = new RegionService(rules, ledger);
        Oracle oracle = new Oracle();

        String[] stats = {"치안", "경제", "민심"};
        int[] deltas = {0, 1, -1, 2, -2, 3, -3, 5, -5, 50, -50, 200, -200};

        Random dice = new Random(20260713L);

        for (int step = 0; step < 3000; step++) {
            Map<String, Integer> nudge = new LinkedHashMap<>();
            for (String stat : stats) {
                if (dice.nextBoolean()) {
                    nudge.put(stat, deltas[dice.nextInt(deltas.length)]);
                }
            }
            // 세계에 없는 눈금도 섞는다 — 옛 코드는 조용히 무시했다. 새 코드도 그래야 한다.
            if (dice.nextInt(10) == 0) {
                nudge.put("존재하지_않는_눈금", 7);
            }

            Map<String, Integer> expected = oracle.nudgeRegion(nudge);
            Map<String, Integer> actual = service.nudge(nudge);

            for (String stat : stats) {
                assertEquals(expected.get(stat), actual.get(stat),
                        "step " + step + " — 반환된 " + stat + " (델타 " + nudge + ")");
                assertEquals(expected.get(stat), ledger.region.get(stat),
                        "step " + step + " — 장부에 적힌 " + stat);
            }
        }

        // 0~100 클램프가 그대로 살아 있는가 (이제 그 눈금은 config 가 정한다)
        assertEquals(0, rules.clamp(-999));
        assertEquals(100, rules.clamp(999));
    }

    @Test
    void 자연회복은_회복할_것이_없으면_장부를_건드리지_않는다() throws Exception {
        RegionStateEngine rules = new RegionStateEngine(REGION_CFG);
        MemoryLedger ledger = new MemoryLedger();
        RegionService service = new RegionService(rules, ledger);

        // 세 눈금 모두 기준값(50)에 앉혀 두면 되돌아갈 곳이 없다
        ledger.region.putAll(Map.of("치안", 50, "경제", 50, "민심", 50));
        assertEquals(Map.of(), service.recover(Map.of("민심", 0)), "회복할 것이 없으면 빈 맵");

        // 부채가 있으면 그만큼 천장이 내려간다 — 47 에서 멈춘다 (민심 부채 3)
        ledger.region.put("민심", 40);
        for (int i = 0; i < 20; i++) {
            service.recover(Map.of("민심", 3));
        }
        assertEquals(47, ledger.region.get("민심"),
                "무명을 죽인 값은 세월이 지워 주지 않는다 (회복의 천장 50 - 3)");
    }
}
