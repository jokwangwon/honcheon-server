package com.honcheon.core.rules;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 성장·경제 엔진 패리티 테스트 — 1막 「생존과 첫 실전」.
 * 설계 문서의 손계산 예시값이 스펙이다 (stats_and_progression.md, economy_system.md).
 */
class ProgressionEconomyParityTest {

    private static ProgressionEngine progression;
    private static EconomyEngine economy;

    static Path cfg(String name) {
        return Path.of(System.getProperty("honcheon.config.dir", "../config")).resolve(name);
    }

    @BeforeAll
    static void setUp() {
        progression = new ProgressionEngine(
                RulesConfig.load(cfg("cultivation.yml")), RulesConfig.load(cfg("training.yml")));
        economy = new EconomyEngine(RulesConfig.load(cfg("economy.yml")));
    }

    // ─── 성장: 실전의 경험치화 v2.1 ───

    @Test
    void 실전_적립_배율() {
        // 동수 × 생사 = 2×1.0×2.0 — 소년의 첫 늑대는 사선이다
        assertEquals(4.0, progression.combatAccrualDays("동수", "생사", 0), 1e-9);
        // 상수 × 생사 = 최고의 스승
        assertEquals(8.0, progression.combatAccrualDays("상수", "생사", 0), 1e-9);
        // 하수 × 사냥 = 배울 게 적다
        assertEquals(0.5, progression.combatAccrualDays("하수", "실전_사냥", 0), 1e-9);
        // 압도적 하수 = 0 — 늑대 백 마리째에는 늑대에게 배울 게 없다
        assertEquals(0.0, progression.combatAccrualDays("압도적_하수", "실전_사냥", 7), 1e-9);
        // 비무는 절반
        assertEquals(1.0, progression.combatAccrualDays("동수", "비무_대련", 0), 1e-9);
    }

    @Test
    void 반복_감쇠() {
        assertEquals(2.0, progression.combatAccrualDays("동수", "실전_사냥", 0), 1e-9);
        assertEquals(1.5, progression.combatAccrualDays("동수", "실전_사냥", 1), 1e-9);   // -25%
        assertEquals(1.125, progression.combatAccrualDays("동수", "실전_사냥", 2), 1e-9); // ×0.75²
    }

    @Test
    void 일일_상한() {
        assertEquals(4.0, progression.cappedGrant(0.0, 4.0), 1e-9);
        assertEquals(1.0, progression.cappedGrant(15.0, 4.0), 1e-9);  // 상한 16 — 잘린다
        assertEquals(0.0, progression.cappedGrant(16.0, 2.0), 1e-9);  // "오늘은 몸이 벅차다"
    }

    @Test
    void 수련_환산표() {
        assertEquals(90, progression.skillLevelUpDays(0));     // 0→1 = 3개월
        assertEquals(180, progression.skillLevelUpDays(1));    // 1→2 = 6개월
        assertEquals(360, progression.skillLevelUpDays(2));    // 2→3 = 1년
        assertEquals(2880, progression.skillLevelUpDays(5));   // 5→6 = 8년 (벽 직전)
        assertEquals(-1, progression.skillLevelUpDays(6));     // 수련만으로 불가 — beyond
        assertEquals(360, progression.attributePointDays());   // 능력치 +1 = 1년
    }

    @Test
    void 수련_효율_곱연산() {
        // 문파 정규 1.2 × 유년·소년 1.5 — 90일 수련이 162일치
        assertEquals(162.0, progression.trainingAccrualDays(90, 1.2, 1.5), 1e-9);
        // 독학 0.5
        assertEquals(45.0, progression.trainingAccrualDays(90, 0.5), 1e-9);
    }

    @Test
    void 화후_돌파() {
        // 판정은 정수, 자원은 실수 — 3.9와 4.0은 세계가 다르다
        assertTrue(ProgressionEngine.breakthrough(3.9, 0.2));
        assertFalse(ProgressionEngine.breakthrough(3.2, 0.5));
        // 진행도: 사선 마크 30일치 / 능력치 1년(360일) = 8.3%
        assertEquals(30.0 / 360.0, ProgressionEngine.progressRatio(30, 360), 1e-9);
    }

    // ─── 경제 ───

    @Test
    void 물가_배율_지역_연동() {
        assertEquals(0.9, economy.priceMultiplier(80), 1e-9);   // 호황
        assertEquals(1.0, economy.priceMultiplier(50), 1e-9);   // 기준
        assertEquals(1.3, economy.priceMultiplier(20), 1e-9);   // 경제 저하 임계
        assertEquals(1.6, economy.priceMultiplier(10), 1e-9);   // 기근
        // 만두 8문이 기근에 10문 — threshold_effects의 수치화
        assertEquals(10, economy.adjustedPrice(economy.basePrice("생활", "만두_한_끼"), 20));
        // 보수는 반대로 내려간다
        assertEquals(0.7, economy.rewardMultiplier(20), 1e-9);
    }

    @Test
    void 매입가_상술_캡() {
        int tigerPelt = economy.basePrice("사냥_부산물", "호랑이_가죽");   // 15,000문
        assertEquals(7500, economy.npcBuyPrice(tigerPelt, false));       // 시세 50%
        assertEquals(10500, economy.npcBuyPrice(tigerPelt, true));       // 상술 성공 70% 캡
        // 늑대 가죽 100 → 매입 50문 — 서민 일당(30~50)과 정합: 사냥꾼 생계 성립
        assertEquals(50, economy.npcBuyPrice(economy.basePrice("사냥_부산물", "늑대_가죽"), false));
    }

    @Test
    void 전장_수수료() {
        assertEquals(100, economy.withdrawFee(10000, false));   // 은 10냥 인출 = 100문
        assertEquals(300, economy.withdrawFee(10000, true));    // 타지 환거래 3배
    }

    @Test
    void 품목_시세_이벤트_선형_감쇠() {
        // 상단 습격 +20%, 4주 감쇠: 직후 120 → 2주 110 → 4주+ 100
        assertEquals(120, economy.eventAdjustedPrice(100, 0.2, 0, 4));
        assertEquals(110, economy.eventAdjustedPrice(100, 0.2, 2, 4));
        assertEquals(100, economy.eventAdjustedPrice(100, 0.2, 4, 4));
        assertEquals(100, economy.eventAdjustedPrice(100, 0.2, 6, 4));
    }

    @Test
    void 비매품은_시장_밖() {
        // 엔진은 비매품에 가격을 만들지 않는다 — 강함은 돈으로 못 산다
        assertThrows(IllegalArgumentException.class, () -> economy.basePrice("장비", "신병"));
        assertThrows(IllegalArgumentException.class, () -> economy.basePrice("무공", "진본_비급"));
    }
}
