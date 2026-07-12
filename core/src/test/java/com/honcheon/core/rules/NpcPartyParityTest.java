package com.honcheon.core.rules;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * NPC 생애·동행 엔진 패리티 테스트 — 2막 「강호의 관계」.
 * npc_lifecycle.yml·party.yml 설계값과 PT-007 정산이 스펙이다.
 */
class NpcPartyParityTest {

    private static NpcLifecycleEngine lifecycle;
    private static PartyEngine party;

    static Path cfg(String name) {
        return Path.of(System.getProperty("honcheon.config.dir", "../config")).resolve(name);
    }

    @BeforeAll
    static void setUp() {
        lifecycle = new NpcLifecycleEngine(
                RulesConfig.load(cfg("npc_lifecycle.yml")), RulesConfig.load(cfg("judgment.yml")));
        party = new PartyEngine(RulesConfig.load(cfg("party.yml")));
    }

    // ─── 생애 시계 ───

    @Test
    void 상인의_길_사다리() {
        assertEquals(12, lifecycle.rungDifficulty("상인의_길", "점포주"));
        assertEquals(18, lifecycle.rungDifficulty("상인의_길", "전장_가주"));
        assertEquals("상단_지점주", lifecycle.nextRung("상인의_길", "점포주"));
        assertNull(lifecycle.nextRung("상인의_길", "전장_가주"));   // 정점 — 그 위는 없다
        // 무인의 길은 cultivation 관문 위임 — 구조화 사다리가 아니다
        assertThrows(IllegalArgumentException.class,
                () -> lifecycle.rungDifficulty("무인의_길", "절정"));
    }

    @Test
    void 계절_정산_한백() {
        // 한백(점포주 → 상단_지점주 14): 화술 3 + 고객_파악 4 + 상황 0 + 7 = 14 → 마진 0 (문턱)
        int power = lifecycle.lifeCheckPower(3, 4, 0);
        assertEquals(14, power);
        assertEquals(0, lifecycle.seasonMargin(power, lifecycle.rungDifficulty("상인의_길", "상단_지점주")));
        // 플레이어 개입 +1이면 마진 +1 — 은혜가 문턱을 넘긴다 (PT-007 재현)
        assertEquals(1, lifecycle.seasonMargin(lifecycle.lifeCheckPower(3, 4, 1), 14));
    }

    @Test
    void 개입_보정_클램프() {
        // 은혜도 원한도 ±3을 넘지 않는다 — 기록은 무제한, 계절 반영은 캡
        assertEquals(3, lifecycle.clampPlayerHand(7));
        assertEquals(-3, lifecycle.clampPlayerHand(-9));
        assertEquals(2, lifecycle.clampPlayerHand(2));
        assertEquals(1, lifecycle.helpBonusMin());
    }

    @Test
    void 일과_컨텍스트() {
        assertEquals(1, lifecycle.activityModifier("접객_중", "화술_판정"));
        assertEquals(-1, lifecycle.activityModifier("집무_중", "화술_판정"));
        assertEquals(1, lifecycle.activityModifier("술자리", "정보_캐기"));
        assertEquals(0, lifecycle.activityModifier("취침", "화술_판정"));   // 미등록 = 0
        assertEquals(-2, lifecycle.nightIntrusionAlertnessDelta());
    }

    // ─── 동행 ───

    @Test
    void 조력_보정() {
        assertEquals(1, party.assistBonus(1));
        assertEquals(2, party.assistBonus(2));
        assertEquals(2, party.assistBonus(4));    // 캡 +2 — 상황보정 ±5 내 계상
    }

    @Test
    void 전원형_최약자_규칙() {
        // 무리는 가장 서툰 발소리만큼 조용하다
        assertEquals(8, party.groupCheckPower(new int[]{12, 8, 10}, 3));
        assertEquals(7, party.groupCheckPower(new int[]{12, 8, 10, 11}, 4));   // 4인+ 추가 -1
    }

    @Test
    void 협공과_포위() {
        assertEquals(0, party.coopAttackBonus(1));   // 혼자는 협공이 아니다
        assertEquals(1, party.coopAttackBonus(2));   // PT-007 T2 재현 — 둘이서 +1
        // 2026-07 전투 정합: 캡 3 → 2. 포위 슬롯 3(한 표적을 동시에 칠 수 있는 손은 셋)의 귀결이다.
        // 5인이 몰려도 넷째부터는 대기 — 머릿수는 '교대'가 되지 '동시타'가 되지 않는다 (party.yml combat_coop).
        assertEquals(2, party.coopAttackBonus(5));   // 캡 +2 (합격진만이 슬롯 5를 연다)
        assertEquals(-2, party.encirclementEscapePenalty());
        assertEquals(5, party.maxPartySize());       // 그 이상은 세력의 영역
    }
}
