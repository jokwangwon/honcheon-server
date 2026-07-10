package com.honcheon.core.rules;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 판정 엔진 패리티 테스트 — tools/simulate_judgment.py 의 출력과 대조.
 * 파이썬 시뮬레이터가 자바 엔진의 스펙이다 (minecraft_port_feasibility.md 3장).
 */
class JudgmentEngineTest {

    private static JudgmentEngine engine;

    static Path configDir() {
        return Path.of(System.getProperty("honcheon.config.dir", "../config"));
    }

    @BeforeAll
    static void setUp() {
        engine = new JudgmentEngine(RulesConfig.load(configDir().resolve("judgment.yml")));
    }

    @Test
    void 등급_경계가_설계_문서와_일치한다() {
        assertEquals("critical_success", engine.tierOf(6).id());
        assertEquals("success", engine.tierOf(5).id());
        assertEquals("success", engine.tierOf(2).id());
        assertEquals("narrow_success", engine.tierOf(1).id());
        assertEquals("narrow_success", engine.tierOf(0).id());
        assertEquals("partial_success", engine.tierOf(-1).id());
        assertEquals("partial_success", engine.tierOf(-3).id());
        assertEquals("failure", engine.tierOf(-4).id());
        assertEquals("failure", engine.tierOf(-7).id());
        assertEquals("critical_failure", engine.tierOf(-8).id());
    }

    @Test
    void 턴3_한백_설득_분포가_파이썬_시뮬레이터와_일치한다() {
        // 정본 시나리오 턴 3: 실행력 기본 4 vs 저항 14
        // 파이썬 출력: 성공 2.8% / 아슬 13.9% / 부분 41.7% / 실패 38.9% / 치명 2.8%
        Map<String, Double> dist = engine.distribution(4, 14);
        assertEquals(1 / 36.0, dist.get("success"), 1e-9);
        assertEquals(5 / 36.0, dist.get("narrow_success"), 1e-9);
        assertEquals(15 / 36.0, dist.get("partial_success"), 1e-9);
        assertEquals(14 / 36.0, dist.get("failure"), 1e-9);
        assertEquals(1 / 36.0, dist.get("critical_failure"), 1e-9);
    }

    @Test
    void PT001_판정_결과를_재현한다() {
        // T3 한백 설득: exec 4, 🎲6, resist 14 → 마진 -4 실패
        assertEquals("failure", engine.resolve(4, 6, 14).id());
        // T4 잠복: exec 7, 🎲4, resist 10 → 마진 +1 아슬아슬한 성공
        assertEquals("narrow_success", engine.resolve(7, 4, 10).id());
        // T5 미행: exec 6, 🎲4, resist 15 → 마진 -5 실패
        assertEquals("failure", engine.resolve(6, 4, 15).id());
    }

    @Test
    void 판정_생략_규칙이_작동한다() {
        // 생사경 고수 vs 삼류 도적: 실행력 17 vs 저항 12 → 기대 마진 +12 → 자동 성공
        assertTrue(engine.isAutoSuccess(17, 12));
        assertFalse(engine.isAutoFail(17, 12));
        // 정본 턴 3: 기대 마진 -3 → 자동 처리 아님, 판정 필요
        assertFalse(engine.isAutoSuccess(4, 14));
        assertFalse(engine.isAutoFail(4, 14));
    }
}
