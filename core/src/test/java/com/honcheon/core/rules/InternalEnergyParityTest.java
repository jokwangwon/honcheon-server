package com.honcheon.core.rules;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 내공·심법 엔진 패리티 테스트 — PT-003/PT-004 플레이 테스트의 실측값이 스펙이다.
 */
class InternalEnergyParityTest {

    private static InternalEnergyEngine energy;
    private static SimbeopEngine simbeop;

    static Path cfg(String name) {
        return Path.of(System.getProperty("honcheon.config.dir", "../config")).resolve(name);
    }

    @BeforeAll
    static void setUp() {
        energy = new InternalEnergyEngine(RulesConfig.load(cfg("internal_energy.yml")));
        simbeop = new SimbeopEngine(RulesConfig.load(cfg("simbeop.yml")));
    }

    @Test
    void 내력_풀_화후_규칙() {
        assertEquals(6, energy.pool(2.0));    // PT-003 소년: 내공 2 → 내력 6
        assertEquals(24, energy.pool(8.0));   // PT-004 청현자: 내공 8 → 내력 24
        // 양자화 절벽 제거 — 2.9와 3.0은 같은 내력
        assertEquals(9, energy.pool(2.9));
        assertEquals(9, energy.pool(3.0));
    }

    @Test
    void 기_운용_경지_게이트() {
        // 절정에야 검기 — 기가 처음 몸 밖으로 나온다
        assertFalse(energy.canUse("일류", "검기"));
        assertTrue(energy.canUse("절정", "검기"));
        // 초절정 누락 수정 검증 — 오의_개안 배치, 강기는 화경부터
        assertTrue(energy.canUse("초절정", "오의_개안"));
        assertFalse(energy.canUse("초절정", "강기"));
        assertTrue(energy.canUse("화경", "오의"));
        assertTrue(energy.canUse("생사경", "창작_오의"));
    }

    @Test
    void 다운캐스트_규칙() {
        assertEquals(2, energy.payOrDowncast(5, 2));   // 충분 — 정상 소모
        assertEquals(0, energy.payOrDowncast(1, 2));   // 부족 — 맨 기술 (소모 0)
        assertTrue(energy.isDepleted(0));
    }

    @Test
    void 정화력_PT004_재현() {
        // 청현자: 태극기공(도가, 순도 1.5) + 내공 8 → 정화력 8×1.5+2 = 14
        assertEquals(14, simbeop.purificationPower("taegeuk_gigong", 8.0));
        // PT-003 소년: 검문 동형(정심검결, 순도 1.0) + 내공 2 → 정화력 2 (계열 보정 없음)
        assertEquals(2, simbeop.purificationPower("jeongsim_geomgyeol", 2.0));
        // 상급 독 기준치 16 — 청현자 기대 마진 +5 (판정 필요, 자동 아님: PT-004 T2)
        assertEquals(16, simbeop.poisonDifficulty("상급"));
    }

    @Test
    void 도인도기_자격() {
        assertTrue(simbeop.canPurgeOthers("taegeuk_gigong"));     // 도가 1.5
        assertTrue(simbeop.canPurgeOthers("jaha_singong"));       // 도가 1.3
        assertFalse(simbeop.canPurgeOthers("jeongsim_geomgyeol")); // 검문 — 불가
        assertFalse(simbeop.canPurgeOthers("chilsal_eumdokgyeong")); // 독문 — 동화는 정화가 아니다
        assertFalse(simbeop.canPurgeOthers("hyeolgi_simgong"));   // 마도
    }

    @Test
    void 단전_용량() {
        assertEquals(4, simbeop.capacity("hyeoncheon_tonapbeop"));  // 기초의 한계
        assertEquals(8, simbeop.capacity("taegeuk_gigong"));
        assertEquals(10, simbeop.capacity("hyeolgi_simgong"));      // 가장 크다 — 마공의 유혹
    }
}
