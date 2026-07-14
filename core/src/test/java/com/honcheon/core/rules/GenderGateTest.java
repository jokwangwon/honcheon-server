package com.honcheon.core.rules;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 성별의 눈 — <b>눈을 만들면 눈을 시험하라.</b>
 *
 * <p>이 눈이 지키는 네 가지:
 * <ol>
 *   <li><b>히든</b> — 성별 보정이 시트에 새지 않는가 (엔진이 시트를 건드리지 않는가)</li>
 *   <li><b>실효</b> — 그런데도 판정에는 <b>실제로</b> 드는가 (안 들면 보정이 아니라 장식이다)</li>
 *   <li><b>등록제</b> — 코드가 수치·이름을 지어내지 않는가 (등록부를 비우면 아무 일도 없어야 한다)</li>
 *   <li><b>생성 중립</b> — 성별이 캐릭터 생성의 선택지를 줄이지 않는가</li>
 * </ol>
 *
 * <p>각 항목마다 <b>일부러 어겨 보는</b> 시험이 함께 있다 (빈 등록부 · 상한 초과 · 없는 문파).
 */
class GenderGateTest {

    private static GenderEngine gender;

    static Path configDir() {
        return Path.of(System.getProperty("honcheon.config.dir", "../config"));
    }

    @BeforeAll
    static void setUp() {
        gender = GenderEngine.of(RulesConfig.load(configDir().resolve("player_creation.yml")));
    }

    // ═══════════════ ① 등록제 — 코드가 지어내지 않는가 ═══════════════

    /**
     * ★ 일부러 어긴다: <b>등록부를 통째로 비운다.</b>
     * 코드가 값을 몰래 갖고 있다면 여기서 튀어나온다 — 아무 일도 일어나지 않아야 한다.
     */
    @Test
    void 등록부가_비면_성별은_아무것도_하지_않는다() {
        GenderEngine empty = new GenderEngine(Map.of());

        assertEquals(0, empty.attrModifier("남", "근력"), "빈 등록부인데 근력 보정이 나왔다 — 코드가 지어냈다");
        assertEquals(0, empty.attrModifier("여", "민첩"), "빈 등록부인데 민첩 보정이 나왔다 — 코드가 지어냈다");
        assertEquals(7, empty.judgmentStat("남", "근력", 7), "빈 등록부인데 판정치가 움직였다");

        assertTrue(empty.factionAllowed("남", "ami"), "빈 등록부인데 문을 막았다 — 코드가 세계관을 지어냈다");
        assertTrue(empty.genderedFactions().isEmpty(), "빈 등록부인데 성별 문파가 있다");

        // 호칭도 지어내지 않는다 — 모르면 'unknown' 으로 물러선다
        assertEquals(empty.unknownHonorific(), empty.sectHonorific("남", "선배"));
        assertEquals(empty.unknownHonorific(), empty.jianghuHonorific("여"));
    }

    /** 등록되지 않은 성별은 세계에 존재하지 않는다 — 보정도, 차단도 없다 */
    @Test
    void 등록부에_없는_성별은_존재하지_않는다() {
        assertFalse(gender.isRegistered("제3의성별"));
        assertFalse(gender.isRegistered(null), "null 은 성별이 아니다");
        assertEquals(0, gender.attrModifier("제3의성별", "근력"),
                "등록부에 없는 성별에 보정이 붙었다");
    }

    /** 실제 등록부에는 남·여 둘뿐이다 (여기 없는 성별은 버튼도 안 생긴다) */
    @Test
    void 실제_등록부의_성별은_남과_여다() {
        assertTrue(gender.isRegistered("남"));
        assertTrue(gender.isRegistered("여"));
        assertEquals(2, gender.options().size());
    }

    // ═══════════════ ② 능력치 — 히든이되 실효 ═══════════════

    /**
     * <b>실효</b> — 판정에는 <b>실제로</b> 든다.
     * 이게 깨지면 성별 보정은 등록부에만 있는 장식이다.
     */
    @Test
    void 성별_보정은_판정에_실제로_든다() {
        // 남: 근력 +1 — 근력 4 인 사내는 판정에서 5 로 구른다
        assertEquals(5, gender.judgmentStat("남", "근력", 4));
        assertNotEquals(4, gender.judgmentStat("남", "근력", 4),
                "사내의 근력 보정이 판정에 안 들었다 — 장식일 뿐이다");

        // 여: 민첩 +1 — 이속의 손잡이이기도 하다 (gyeonggong.yml attribute: 민첩)
        assertEquals(5, gender.judgmentStat("여", "민첩", 4));
        assertNotEquals(4, gender.judgmentStat("여", "민첩", 4),
                "계집의 민첩 보정이 판정에 안 들었다");
    }

    /**
     * <b>히든</b> — 엔진은 <b>시트를 건드리지 않는다.</b>
     * 보정이 시트에 저장되면 {@code /혼천 정보} 가 능력치 맵을 통째로 훑어 출력하므로
     * 그 순간 히든이 깨진다. 그래서 보정은 <b>저장되지 않고</b> 판정이 읽을 때만 얹힌다.
     */
    @Test
    void 성별_보정은_시트에_저장되지_않는다() {
        Map<String, Object> sheet = new LinkedHashMap<>();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("근력", 4);
        attrs.put("민첩", 3);
        sheet.put("능력치", attrs);
        sheet.put("성별", "남");

        Map<String, Object> before = new LinkedHashMap<>(attrs);

        // 판정을 여러 번 굴려도 시트는 그대로여야 한다
        for (int i = 0; i < 10; i++) {
            gender.judgmentStat("남", "근력", ((Number) attrs.get("근력")).intValue());
        }

        assertEquals(before, attrs,
                "성별 보정이 시트의 능력치를 바꿨다 — /혼천 정보 가 그대로 출력한다. 히든이 깨졌다");
        assertEquals(4, attrs.get("근력"),
                "시트의 근력이 5 로 올랐다 — 플레이어가 보정을 눈으로 본다");
        assertTrue(gender.attributesHidden(),
                "등록부가 히든을 껐다 — 시트 노출 금지가 풀렸다");
    }

    /**
     * <b>성별은 강약을 정하지 않는다</b> — 총합이 같다.
     * 무협은 여협이 사내를 벤다. 유리한 성별이 있으면 사람들은 역할이 아니라 최적해로 고른다.
     */
    @Test
    void 성별은_강약이_아니라_결이다_총합이_같다() {
        List<String> axes = List.of("근력", "민첩", "체력", "내공", "감각", "화술", "지혜");

        int male = 0, female = 0;
        for (String axis : axes) {
            male += gender.attrModifier("남", axis);
            female += gender.attrModifier("여", axis);
        }
        assertEquals(male, female,
                "성별의 보정 총합이 다르다 — 유리한 성별이 생겼다. 사람들은 그것만 고른다");

        // 그리고 결은 실제로 다르다 (총합만 같고 아무것도 안 하면 그것도 실패다)
        assertNotEquals(gender.attrModifier("남", "근력"), gender.attrModifier("여", "근력"),
                "성별이 아무것도 가르지 않는다 — 차별화가 없다");
    }

    /**
     * ★ 일부러 어긴다: 등록부에 <b>상한을 넘는 값</b>(근력 5)을 적어 본다.
     * 엔진이 그대로 삼키면 성별이 강약을 정해 버린다 — 깎여야 한다.
     */
    @Test
    void 상한을_넘는_보정은_깎인다() {
        Map<String, Object> rogue = Map.of(
                "options", Map.of("남", Map.of("label", "사내")),
                "gates", Map.of("attributes", Map.of(
                        "cap", 1,
                        "남", Map.of("근력", 5, "민첩", -9))));
        GenderEngine engine = new GenderEngine(rogue);

        assertEquals(1, engine.attrModifier("남", "근력"),
                "상한(1)을 넘는 보정 5 가 그대로 들었다 — 성별이 강약을 정한다");
        assertEquals(-1, engine.attrModifier("남", "민첩"),
                "하한(-1)을 넘는 보정 -9 가 그대로 들었다");
    }

    /** 실제 등록부의 보정은 상한 안에 있다 */
    @Test
    void 실제_등록부의_보정이_상한을_지킨다() {
        List<String> axes = List.of("근력", "민첩", "체력", "내공", "감각", "화술", "지혜");
        int cap = gender.cap();
        for (String g : gender.options().keySet()) {
            for (String axis : axes) {
                int mod = gender.attrModifier(g, axis);
                assertTrue(Math.abs(mod) <= cap,
                        g + "의 " + axis + " 보정 " + mod + " 이 상한 " + cap + " 을 넘는다");
            }
        }
    }

    /** 성별을 모르는 옛 캐릭터 — 코드는 <b>추측하지 않는다</b> (보정 0) */
    @Test
    void 성별을_모르면_추측하지_않는다() {
        assertEquals(0, gender.attrModifier(null, "근력"),
                "성별이 없는 옛 캐릭터에 보정이 붙었다 — 코드가 성별을 추측했다");
        assertEquals(4, gender.judgmentStat(null, "근력", 4));
    }

    // ═══════════════ ③ 문파 입문 ═══════════════

    /**
     * 아미는 여승의 문파다.
     * 근거는 <b>등록부에 있다</b> — factions.yml "사천의 여승 문파" + ami_architecture.md "여성 중심 문파".
     */
    @Test
    void 아미는_여승의_문파다() {
        assertEquals(List.of("여"), gender.allowedGenders("ami"));
        assertTrue(gender.factionAllowed("여", "ami"), "계집이 아미에 못 든다");
        assertFalse(gender.factionAllowed("남", "ami"), "사내가 비구니원에 들어갔다");
    }

    /**
     * ★ 일부러 어긴다: 등록부가 <b>말한 적 없는</b> 문파를 물어본다.
     * 소림·불가가 남성 전용이라는 근거는 등록부에 <b>없다</b> — 그러니 코드가 막으면 안 된다.
     * (막아야 한다면 사용자가 등록부에 적을 일이다 — open_questions ②)
     */
    @Test
    void 근거가_없는_문파는_막지_않는다() {
        assertTrue(gender.factionAllowed("여", "sorimsa"),
                "등록부에 근거가 없는데 소림이 여자를 막았다 — 코드가 세계관을 지어냈다");
        assertTrue(gender.factionAllowed("남", "hwasan"), "화산이 사내를 막았다");
        assertTrue(gender.factionAllowed("여", "hwasan"), "화산이 계집을 막았다");
        assertTrue(gender.factionAllowed("남", "존재하지_않는_문파"));

        // 성별을 가리는 문파는 지금 아미 하나뿐이다
        assertEquals(1, gender.genderedFactions().size(),
                "성별을 가리는 문파가 아미 말고 또 있다 — 등록부에 근거가 있는가?");
    }

    /** 성별을 모르는 옛 캐릭터를 문 앞에 가두지 않는다 */
    @Test
    void 성별을_모르면_문을_막지_않는다() {
        assertTrue(gender.factionAllowed(null, "ami"),
                "성별 없는 옛 캐릭터가 문 앞에서 막혔다 — 추측해서 막았다");
    }

    // ═══════════════ ④ 생성 중립 — 성별이 생성을 제한하지 않는가 ═══════════════

    /**
     * ★★ 사용자의 최초 불만: <b>"성별 선택이 없어 강제로 루트가 제한됨."</b>
     * 성별을 넣어 놓고 <b>그 성별 때문에 못 고르는 문항</b>이 생기면
     * <b>같은 병을 반대편으로 옮긴 것</b>이다.
     *
     * <p>문파 입문 제한은 <b>세계관</b>이다 — 그것은 <b>문파에 들어갈 때</b> 서는 관문이지
     * <b>캐릭터를 만들 때</b> 서는 관문이 아니다.
     */
    @Test
    void 성별은_캐릭터_생성을_제한하지_않는다() {
        assertFalse(gender.mayRestrictCreation(),
                "성별이 생성을 제한하게 열려 있다 — 사용자가 겪은 그 병이 반대편으로 돌아왔다");
    }

    /** 성별은 <b>고르는 것이다</b> — 무작위도, 다른 문항에서 추론되는 것도 아니다 */
    @Test
    void 성별은_고르는_것이지_주어지는_것이_아니다() {
        assertTrue(gender.chosenNeverInferred(),
                "성별이 추론될 수 있게 열려 있다 — 성별은 고르는 것이다");
    }

    /**
     * 아미가 여성 문파라는 사실이 <b>생성</b>에 새지 않는가.
     * 성별 관문은 {@code faction_entry} 에만 있어야 한다 — 생성 문항(성향·집안·유년의 기억)에는 없다.
     */
    @Test
    void 문파_관문은_생성이_아니라_입문에_선다() {
        // 등록부의 성별 관문은 faction_entry 하나뿐이다 — 생성 문항을 가리는 관문은 없다
        assertFalse(gender.genderedFactions().isEmpty(), "입문 관문이 아예 없다");
        assertFalse(gender.mayRestrictCreation(), "생성 관문이 생겼다");
    }

    // ═══════════════ ⑤ 호칭 ═══════════════

    /**
     * 무협의 호칭은 <b>성별 × 선후배</b>로 갈린다.
     * 서열의 축은 <b>입문 순서</b>다 — sect_life.yml {@code brotherhood.order: 입문_순} (등록부에 이미 있었다).
     */
    @Test
    void 문파_호칭이_성별과_선후배로_갈린다() {
        assertEquals("사형", gender.sectHonorific("남", "선배"));
        assertEquals("사저", gender.sectHonorific("여", "선배"));
        assertEquals("사제", gender.sectHonorific("남", "후배"));
        assertEquals("사매", gender.sectHonorific("여", "후배"));
    }

    /**
     * ★ 방향 — 호칭은 <b>부르는 자</b>가 아니라 <b>불리는 자</b>의 성별로 정해진다.
     * 내가 계집이어도, 나보다 먼저 든 사내는 <b>사형</b>이다 (사저가 아니다).
     */
    @Test
    void 호칭은_불리는_자의_성별로_정해진다() {
        // 나(여)보다 먼저 입문한 사내 → 사형
        String relation = gender.sectRelation(5, 2);      // 내 순번 5, 그의 순번 2 → 그가 선배
        assertEquals("선배", relation);
        assertEquals("사형", gender.sectHonorific("남", relation),
                "계집이 부른다고 사내 선배가 '사저'가 됐다 — 부르는 자의 성별을 봤다");

        // 나(남)보다 늦게 입문한 계집 → 사매
        String later = gender.sectRelation(2, 5);
        assertEquals("후배", later);
        assertEquals("사매", gender.sectHonorific("여", later));
    }

    /** 서열은 입문 순서가 정한다 — 코드가 지어내지 않는다 */
    @Test
    void 서열은_입문_순서가_정한다() {
        assertEquals("선배", gender.sectRelation(10, 1));
        assertEquals("후배", gender.sectRelation(1, 10));
        assertEquals("선배", gender.sectRelation(3, 3), "동기는 선배로 예우한다");
    }

    /** 강호의 호칭 (문파 밖) */
    @Test
    void 강호의_호칭() {
        assertEquals("소협", gender.jianghuHonorific("남"));
        assertEquals("여협", gender.jianghuHonorific("여"));
    }

    /** 불문의 호칭 (아미·소림) */
    @Test
    void 불문의_호칭() {
        assertEquals("사태", gender.buddhistHonorific("여"));
        assertEquals("대사", gender.buddhistHonorific("남"));
    }

    /** 성별을 모르는 자를 부를 말 — 코드가 성별을 <b>추측해서</b> 부르지 않는다 */
    @Test
    void 성별을_모르면_추측해서_부르지_않는다() {
        assertEquals("무인", gender.jianghuHonorific(null));
        assertEquals("무인", gender.sectHonorific(null, "선배"));
        assertEquals("무인", gender.unknownHonorific());
    }
}
