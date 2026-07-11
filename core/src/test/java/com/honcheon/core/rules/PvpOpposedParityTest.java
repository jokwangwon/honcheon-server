package com.honcheon.core.rules;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * pvp direct_opposed(F22) + 극단 주사위 배선(F24) 패리티 테스트.
 * judgment.yml pvp·optional_extreme_dice 블록이 스펙이다 (봇 베타 배선 전 검증).
 */
class PvpOpposedParityTest {

    private static JudgmentEngine judgment;

    static Path cfg(String name) {
        return Path.of(System.getProperty("honcheon.config.dir", "../config")).resolve(name);
    }

    @BeforeAll
    static void setUp() {
        judgment = new JudgmentEngine(RulesConfig.load(cfg("judgment.yml")));
    }

    @Test
    void 동점은_상쇄_무승부다() {
        var r = judgment.directOpposed(5, 7, 4, 8);   // 12 = 12
        assertTrue(r.draw());
        assertEquals(0, r.margin());
    }

    // 2026-07 등급 문턱 재설계 반영 — 대성공 ≥+4 / 성공 +2~+3 / 아슬 0~+1 / 부분 −3~−1 / 실패 −5~−4 / 치명 ≤−6

    @Test
    void 마진은_양측_실행력_차다() {
        var r = judgment.directOpposed(5, 9, 4, 8);   // 14 - 12 = +2
        assertFalse(r.draw());
        assertEquals(2, r.margin());
        assertEquals("성공", r.tier().name());
    }

    @Test
    void 쌍륙은_등급을_한_단계_올린다_F24() {
        // A: 3+12=15, B: 5+8=13 → 마진 +2 = 성공, 쌍륙으로 대성공 승격
        var r = judgment.directOpposed(3, 12, 5, 8);
        assertEquals(2, r.margin());
        assertEquals("대성공", r.tier().name());
    }

    @Test
    void 쌍일은_등급을_한_단계_내린다_F24() {
        // A: 8+2=10, B: 1+5=6 → 마진 +4 = 대성공, 쌍일로 성공 강등
        var r = judgment.directOpposed(8, 2, 1, 5);
        assertEquals(4, r.margin());
        assertEquals("성공", r.tier().name());
    }

    @Test
    void 상대의_극단은_부호가_뒤집힌다() {
        // B가 쌍륙 → A측 등급 1단계 하락: A 10+8=18, B 2+12=14 → 마진 +4 대성공 → 성공
        var r = judgment.directOpposed(10, 8, 2, 12);
        assertEquals(4, r.margin());
        assertEquals("성공", r.tier().name());
    }

    @Test
    void 양측_같은_극단은_상쇄된다() {
        // 쌍륙 vs 쌍륙: A 5+12=17, B 1+12=13 → 마진 +4, 이동 없이 대성공
        var r = judgment.directOpposed(5, 12, 1, 12);
        assertEquals("대성공", r.tier().name());
    }

    @Test
    void 등급_사다리_경계에서_고정된다() {
        // 마진 +9 = 대성공, 쌍륙이어도 더 오를 곳 없음
        var r = judgment.directOpposed(5, 12, 3, 5);
        assertEquals(9, r.margin());
        assertEquals("대성공", r.tier().name());
    }

    @Test
    void 무승부는_극단으로_뒤집히지_않는다() {
        // 쌍륙 vs 다른 주사위지만 합이 같으면 여전히 무승부
        var r = judgment.directOpposed(2, 12, 7, 7);   // 14 = 14
        assertTrue(r.draw());
    }
}
