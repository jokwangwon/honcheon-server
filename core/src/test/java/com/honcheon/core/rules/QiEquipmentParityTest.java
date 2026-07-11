package com.honcheon.core.rules;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 3막 「격과 재산」 패리티 테스트 — qi_manifestation.yml·equipment.yml 손계산이 스펙.
 * 상성 한 줄 검증: "격은 막기를 부수고, 회피는 격을 속인다" + "무기가 격을 견뎌야 한다".
 */
class QiEquipmentParityTest {

    private static QiManifestationEngine qi;
    private static EquipmentEngine equipment;

    static Path cfg(String name) {
        return Path.of(System.getProperty("honcheon.config.dir", "../config")).resolve(name);
    }

    @BeforeAll
    static void setUp() {
        qi = new QiManifestationEngine(RulesConfig.load(cfg("qi_manifestation.yml")));
        equipment = new EquipmentEngine(RulesConfig.load(cfg("equipment.yml")));
    }

    // ─── 격 사다리 ───

    @Test
    void 격_사다리_등수와_게이트() {
        assertEquals(1, qi.gradeRank("발경"));
        assertEquals(2, qi.gradeRank("검기"));
        assertEquals(3, qi.gradeRank("강기"));
        assertEquals(4, qi.gradeRank("어검"));
        assertEquals(5, qi.gradeRank("심검"));
        assertEquals("삼류", qi.gradeGate("발경"));
        assertEquals("절정", qi.gradeGate("검기"));
        assertEquals("화경", qi.gradeGate("강기"));
        assertThrows(IllegalArgumentException.class, () -> qi.gradeRank("없는격"));
    }

    @Test
    void 원칙_1_관통_한_격_위는_아래_기_방어를_부순다() {
        assertTrue(qi.piercesGuard("강기", "검기"));    // 검강은 검기를 벤다
        assertTrue(qi.piercesGuard("검기", "발경"));
        assertFalse(qi.piercesGuard("검기", "검기"));   // 동격 — 상쇄전
        assertFalse(qi.piercesGuard("검기", "강기"));   // 아래가 위를 못 뚫는다
    }

    // ─── 무기 파괴 — 원칙 3 (손계산 스펙: over = 격 rank − 감당 rank) ───

    @Test
    void 무기_격돌_감당_이상은_손상_없음() {
        assertEquals(QiManifestationEngine.Clash.NONE, qi.clash("정련", "검기", 0));
        assertEquals(QiManifestationEngine.Clash.NONE, qi.clash("보병", "강기", 0));
        assertEquals(QiManifestationEngine.Clash.NONE, qi.clash("신병", "어검", 0));
        assertEquals(QiManifestationEngine.Clash.NONE, qi.clash("신병", "심검", 0));
    }

    @Test
    void 무기_격돌_1격_초과는_누적_2격_초과는_절단() {
        // 범철(감당 발경1) vs 검기(2) = 1 초과 → 누적 (격돌 3회째 파괴)
        assertEquals(QiManifestationEngine.Clash.CRACK, qi.clash("범철", "검기", 0));
        assertEquals(3, qi.breaksAt());
        // 범철 vs 검강(3) = 2 초과 → 즉시 절단 — 한 합
        assertEquals(QiManifestationEngine.Clash.SEVER, qi.clash("범철", "강기", 0));
        // 정련(감당 검기2) vs 강기(3) = 1 초과 → 누적
        assertEquals(QiManifestationEngine.Clash.CRACK, qi.clash("정련", "강기", 0));
    }

    @Test
    void 발경_예외_상대_무기를_상하게_하지_않는다() {
        assertEquals(QiManifestationEngine.Clash.NONE, qi.clash("범철", "발경", 0));
    }

    @Test
    void 감당_상향_취급_명공각인_정련은_강기를_받아낸다() {
        // 명공각인·애병 통령·마병 = 감당 격 +1 취급 (weapon_break 계산에만)
        assertEquals(QiManifestationEngine.Clash.NONE, qi.clash("정련", "강기", 1));
        assertEquals(QiManifestationEngine.Clash.CRACK, qi.clash("범철", "강기", 1));
    }

    @Test
    void 자기_무기_자해_범철에_검기_두름() {
        assertTrue(qi.selfDamages("범철", "검기", 0));    // 시전 3회마다 손상 1
        assertEquals(3, qi.selfDamageEvery());
        assertFalse(qi.selfDamages("정련", "검기", 0));
        assertFalse(qi.selfDamages("범철", "발경", 0));   // 발경은 몸 안에 머문다
    }

    // ─── 형태(形) 코스트 — internal_energy cost_bands의 세분 (본 파일 우선) ───

    @Test
    void 형태별_코스트() {
        assertEquals(1, qi.sustainPerRound("검기_두름"));
        assertEquals(2, qi.sustainPerRound("검강_두름"));
        assertEquals(3, qi.oneShotCost("검기_참격"));      // 내력 관리의 도박
        assertEquals(6, qi.oneShotCost("강기_포"));
        assertEquals(2, qi.deployCost("호신강기"));
        assertEquals(2, qi.sustainPerRound("호신강기"));
        assertEquals(4, qi.deployCost("이기어검"));        // cast 4 + 유지 2/라운드
        assertEquals(2, qi.sustainPerRound("이기어검"));
        assertEquals(-2, qi.eogeomCasterDodgePenalty());   // 마음이 검에 가 있다
    }

    // ─── 장비 — 격차가 아니라 속성과 서사 ───

    @Test
    void 장비_판정_보정_캡_2() {
        assertEquals(0, equipment.weaponJudgmentBonus("범철"));
        assertEquals(0, equipment.weaponJudgmentBonus("정련"));   // 가치는 생존이지 보정이 아니다
        assertEquals(1, equipment.weaponJudgmentBonus("보병"));
        assertEquals(1, equipment.weaponJudgmentBonus("신병"));
        assertEquals(2, equipment.cappedBonus(4));    // 보병 1 + 애병 1 + 기물 2 시도 → 캡 2
        assertEquals(1, equipment.cappedBonus(1));
        assertEquals(2, equipment.trinketSlots());
    }

    @Test
    void 속성_슬롯_정련_1_신병_2() {
        assertEquals(0, equipment.weaponPropertySlots("범철"));
        assertEquals(1, equipment.weaponPropertySlots("정련"));   // 명공 각인 가능
        assertEquals(2, equipment.weaponPropertySlots("신병"));   // 내재 + 내력(來歷)
    }

    @Test
    void 방어구_갑옷은_회피를_판다() {
        assertEquals(0, equipment.armorMitigation("무복"));
        assertEquals(0, equipment.armorDodgePenalty("무복"));
        assertEquals(1, equipment.armorMitigation("피갑"));
        assertEquals(-1, equipment.armorDodgePenalty("피갑"));
        assertEquals(2, equipment.armorMitigation("철갑"));
        assertEquals(-2, equipment.armorDodgePenalty("철갑"));
        assertEquals(1, equipment.armorMitigation("내갑"));
        assertEquals(0, equipment.armorDodgePenalty("내갑"));     // 보물급의 이유
    }

    @Test
    void 애병_주인의_손에서만() {
        assertEquals(1, equipment.belovedOwnerBonus("손에_익다"));
        assertEquals(0, equipment.belovedWithstandRankUp("손에_익다"));
        assertEquals(1, equipment.belovedWithstandRankUp("통령"));   // 정련이 보병급을 감당
        // 통령 정련검 + 검강 격돌 = 손상 없음 (엔진 간 접합)
        assertEquals(QiManifestationEngine.Clash.NONE,
                qi.clash("정련", "강기", equipment.belovedWithstandRankUp("통령")));
    }

    @Test
    void 마병_힘과_대가() {
        assertEquals(1, equipment.demonicWithstandRankUp());
        assertEquals(2, equipment.demonicDamageBonus());
        EquipmentEngine.Drain drain = equipment.demonicDrain();
        assertEquals(-2, drain.targetVitality());        // 적중 시 상대 원기 흡수
        assertEquals(2, drain.selfInternalEnergy());     // 자기 내력 충전 — 마공 분류 ①
    }
}
