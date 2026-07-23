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
     * 읽기 backfill — sheet 에 원장/레벨/경험치/미사용포인트가 없으면 채운다 (있으면 손 안 댐).
     * 원장은 옛 능력치 실수의 제곱. 채운 sheet 는 다음 저장 때 영속화된다 (지연 마이그레이션).
     *
     * @return 무언가 채웠으면 true (호출부가 로그·저장 판단에 쓸 수 있다)
     */
    @SuppressWarnings("unchecked")
    static boolean backfill(Map<String, Object> sheet) {
        if (sheet == null) {
            return false;
        }
        boolean changed = false;
        if (!(sheet.get("원장") instanceof Map)) {
            Object attrsObj = sheet.get("능력치");
            Map<String, Object> raw = new LinkedHashMap<>();
            if (attrsObj instanceof Map<?, ?> attrs) {
                for (String axis : AXES) {
                    double x = num(attrs.get(axis));
                    raw.put(axis, x * x);   // 실수 보존 — x² (판정치 = floor(√) 로 옛 값 복원)
                }
            }
            sheet.put("원장", raw);
            changed = true;
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

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
