package com.honcheon.bot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 성장 v3 — 원장 층 (B-135 단계 1 · 무DDL 읽기 backfill).
 *
 * <p>능력치는 봇 {@code characters.sheet_json} 안에 산다 (별도 컬럼 아님). v3 는 여기에
 * <b>원장·레벨·경험치·미사용포인트</b>를 키로 더한다 — {@code CREATE/ALTER TABLE} 없이.
 *
 * <p><b>읽기 backfill</b> (attribute_scale_v3 §8.9 ③ 실수 보존안): sheet 에 원장이 없으면
 * 옛 능력치 실수 x 로 {@code 원장 = x²}(실수 저장)을 그 자리에서 만든다. 판정치 보존:
 * {@code floor(√(x²)) = floor(x) = 옛 판정치(정수부)}. 무손실·가역·결정론.
 *
 * <p><b>★비파괴 (단계 1)</b>: 이 층은 원장을 <b>채우기만</b> 한다 (if-missing). 판정의 권위는
 * 아직 능력치가 쥔다 — 판정치 = floor(√원장) 로 <b>읽는 전환은 단계 2</b>다. 그때까지 원장은
 * 예비 그림자다 (아무 판정도 안 바꾼다).
 */
final class GrowthV3 {

    /** 능력치 7축 — GameListener.STATS 와 같은 정본 (judgment.yml attributes) */
    static final List<String> AXES = List.of("근력", "민첩", "체력", "내공", "감각", "화술", "지혜");

    private GrowthV3() {
    }

    /** 판정치 = floor(√원장) — 환산층의 유일한 문 (attribute_scale_v3 §8.1). 단계 2가 쓴다. */
    static int judgmentValue(double raw) {
        return (int) Math.floor(Math.sqrt(Math.max(0.0, raw)));
    }

    /** 원장에서 파생 실수치 = √원장 (내력 풀·이속·내구가 읽는 값 · §8.9 ⑩) */
    static double realValue(double raw) {
        return Math.sqrt(Math.max(0.0, raw));
    }

    /**
     * 읽기 backfill + <b>화해</b> — sheet 에 원장/레벨/경험치/미사용포인트가 없으면 채우고,
     * 원장이 있어도 <b>끌어올리기만</b> 한다: {@code 원장 = max(원장, 실수 화후²)} (단계 3).
     *
     * <p>★실수의 정본은 {@code 능력치_화후} 다 (§8.9 ③ "옛 능력치 <b>실수</b> x") — 첫 판은
     * 정수 {@code 능력치}를 제곱해 화후의 소수부를 떨어뜨렸고, 원장이 한 번 서면 얼어붙어
     * <b>수련(화후 증가)이 판정·파생에 안 실리는 표류</b>가 있었다 (2026-07-24 수리).
     * 화해가 raise-only 인 이유:
     * ① 판정 보존 — {@code floor(√(화후²)) = floor(화후) = 능력치} (settle 가 그 불변식을 지킨다)
     * ② 단계 4(원장 독립 성장 · 화후 동결) 뒤에는 화후² ≤ 원장이라 자연히 무연산이 된다 —
     *    이 화해를 뜯어낼 날을 기다릴 필요가 없다.
     *
     * @return 무언가 채웠거나 끌어올렸으면 true (호출부가 로그·저장 판단에 쓸 수 있다)
     */
    @SuppressWarnings("unchecked")
    static boolean backfill(Map<String, Object> sheet) {
        if (sheet == null) {
            return false;
        }
        boolean changed = false;
        Object attrsObj = sheet.get("능력치");
        Object hwahuObj = sheet.get("능력치_화후");
        Map<String, Object> raw;
        if (sheet.get("원장") instanceof Map) {
            raw = (Map<String, Object>) sheet.get("원장");
        } else {
            raw = new LinkedHashMap<>();
            sheet.put("원장", raw);
            changed = true;
        }
        for (String axis : AXES) {
            double x = hwahuObj instanceof Map<?, ?> h ? num(((Map<String, Object>) h).get(axis)) : 0.0;
            if (x <= 0.0 && attrsObj instanceof Map<?, ?> a) {
                x = num(((Map<String, Object>) a).get(axis));   // 화후가 없으면 옛 능력치 정수가 곧 실수다
            }
            double target = x * x;                              // 실수 보존 — 화후² (소수부까지 원장에)
            double cur = num(raw.get(axis));
            if (target > cur || !raw.containsKey(axis)) {
                raw.put(axis, Math.max(cur, target));
                changed = true;
            }
        }
        if (!(sheet.get("레벨") instanceof Number)) {
            sheet.put("레벨", 1);
            changed = true;
        }
        if (!(sheet.get("경험치") instanceof Number)) {
            sheet.put("경험치", 0);
            changed = true;
        }
        if (!(sheet.get("미사용포인트") instanceof Number)) {
            sheet.put("미사용포인트", 0);
            changed = true;
        }
        return changed;
    }

    /** L→L+1 비용 — need(L) = base × growth^(L-1) (cultivation.yml levels.xp_curve · xp_pacing.py 동식) */
    static double need(int level, double base, double growth) {
        return base * Math.pow(growth, Math.max(0, level - 1));
    }

    /**
     * XP 적립 + 레벨업 (B-135 단계 4) — 시트의 경험치·레벨·미사용포인트를 굴린다.
     * 만렙 없음 · 매 레벨 {@code pointsPerLevel} 포인트 적립 (배분은 별도 손 — 캡 c² 는 배분이 지킨다).
     *
     * @return 오른 레벨 수 (호출부 로그·연출용)
     */
    static int grantXp(Map<String, Object> sheet, int xp, double base, double growth,
                       int pointsPerLevel) {
        if (sheet == null || xp <= 0 || base <= 0) {
            return 0;
        }
        double cur = num(sheet.get("경험치")) + xp;
        int level = Math.max(1, (int) num(sheet.get("레벨")));
        int points = (int) num(sheet.get("미사용포인트"));
        int ups = 0;
        while (cur >= need(level, base, growth)) {
            cur -= need(level, base, growth);
            level++;
            points += pointsPerLevel;
            ups++;
        }
        sheet.put("경험치", cur);
        sheet.put("레벨", level);
        sheet.put("미사용포인트", points);
        return ups;
    }

    /** 배분 결과 — 손이 왜 안 됐는지 말해야 한다 (침묵하는 실패 금지 · HANDOFF §2.2) */
    enum Allocation { OK, NO_POINTS, CAP }

    /**
     * 포인트 배분 — 미사용 포인트 1점 → 원장 1눈금 (B-135 단계 4 · attribute_scale_v3 §8.1).
     *
     * <p><b>캡은 원장에 선다</b> (§8.5 "포인트가 캡²를 넘겨 들어가지 못한다"): {@code 원장+1 > 캡}
     * 이면 거절한다. 화후 소수부가 캡 밑에 1 미만의 잔여 눈금을 남겨도 같다 — 반 눈금 배분은 없다.
     * 거절된 포인트는 사라지지 않는다 (§8.9 ⑨ <b>은행 무기한 보유</b> — 승급이 문이다).
     *
     * @param rawCap 이 경지의 원장 캡 c² ({@code cultivation.yml levels.raw_attribute_cap_by_realm}
     *               — 호출부가 등록부에서 읽는다. 코드는 수치를 지어내지 않는다)
     */
    @SuppressWarnings("unchecked")
    static Allocation allocate(Map<String, Object> sheet, String axis, int rawCap) {
        if (!AXES.contains(axis)) {
            throw new IllegalArgumentException("등록부 밖의 축이다: " + axis);
        }
        int points = (int) num(sheet.get("미사용포인트"));
        if (points <= 0) {
            return Allocation.NO_POINTS;
        }
        Map<String, Object> raw;
        if (sheet.get("원장") instanceof Map) {
            raw = (Map<String, Object>) sheet.get("원장");
        } else {
            raw = new LinkedHashMap<>();   // 이론상 backfill 뒤엔 없는 길 — 방어
            sheet.put("원장", raw);
        }
        double cur = num(raw.get(axis));
        if (cur + 1.0 > rawCap + 1e-9) {
            return Allocation.CAP;
        }
        raw.put(axis, cur + 1.0);
        sheet.put("미사용포인트", points - 1);
        return Allocation.OK;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
