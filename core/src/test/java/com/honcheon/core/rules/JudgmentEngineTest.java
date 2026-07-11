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
        // 2026-07 재설계 — 문턱을 2d6 마진 분포에 맞췄다 (judgment.yml result_tiers 주석이 스펙)
        assertEquals("critical_success", engine.tierOf(4).id());   // 대등 판정의 쌍륙 = 마진 +4
        assertEquals("success", engine.tierOf(3).id());
        assertEquals("success", engine.tierOf(2).id());
        assertEquals("narrow_success", engine.tierOf(1).id());
        assertEquals("narrow_success", engine.tierOf(0).id());
        assertEquals("partial_success", engine.tierOf(-1).id());
        assertEquals("partial_success", engine.tierOf(-3).id());
        assertEquals("failure", engine.tierOf(-4).id());
        assertEquals("failure", engine.tierOf(-5).id());
        assertEquals("critical_failure", engine.tierOf(-6).id());  // 대등 판정의 뱀눈 = 마진 −6
    }

    @Test
    void 대등_판정에서_6단이_전부_굴러간다() {
        // 표준 대립: 실행력 5 vs 저항 13 → 마진 = 2d6 − 8 (범위 −6 ~ +4)
        // 신 문턱: 대성공 1/36 · 성공 5/36 · 아슬 9/36 · 부분 15/36 · 실패 5/36 · 치명 1/36
        Map<String, Double> dist = engine.distribution(5, 13);
        assertEquals(1 / 36.0, dist.get("critical_success"), 1e-9);
        assertEquals(5 / 36.0, dist.get("success"), 1e-9);
        assertEquals(9 / 36.0, dist.get("narrow_success"), 1e-9);
        assertEquals(15 / 36.0, dist.get("partial_success"), 1e-9);
        assertEquals(5 / 36.0, dist.get("failure"), 1e-9);
        assertEquals(1 / 36.0, dist.get("critical_failure"), 1e-9);
    }

    @Test
    void 턴3_한백_설득_분포() {
        // 정본 시나리오 턴 3: 실행력 기본 4 vs 저항 14 → 마진 = 2d6 − 10 (범위 −8 ~ +2)
        Map<String, Double> dist = engine.distribution(4, 14);
        assertEquals(1 / 36.0, dist.get("success"), 1e-9);          // 마진 +2 (쌍륙)
        assertEquals(5 / 36.0, dist.get("narrow_success"), 1e-9);   // 0 ~ +1
        assertEquals(15 / 36.0, dist.get("partial_success"), 1e-9); // −3 ~ −1
        assertEquals(9 / 36.0, dist.get("failure"), 1e-9);          // −5 ~ −4
        assertEquals(6 / 36.0, dist.get("critical_failure"), 1e-9); // ≤ −6 — 격차는 파국을 낳는다
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
