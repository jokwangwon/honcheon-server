package com.honcheon.mvt;

/**
 * 리소스팩 글리프 (tools/build_resourcepack.py 가 minecraft:default 폰트에 주입).
 * 팩 미설치 클라이언트에는 □ 로 보인다 — MVT에서는 허용 (가이드에 설치 안내).
 * 글리프는 백색 비트맵 — 채팅 색 코드가 그대로 틴트한다 (기세 4색 = 색 코드 4종 × 아이콘 1종).
 */
final class Glyphs {

    /** U+E000 — 기세 아이콘 (색 코드로 틴트: 회/백/황/적) */
    static final String GISE = "\uE000";   // F26: PUA 리터럴 금지 — 이스케이프로만 쓴다

    /**
     * U+E030 — 비무 표식 (교차한 목검 + 띠 · 백색계 4단 명암, 색 코드로 틴트).
     * 용처 정본은 빌더 주석 (build_resourcepack.py "비무 표식 (E030)" 절): "액션바·명패 앞에
     * 붙일 글리프 … Sparring 의 액션바 문구만으로도 읽힌다". 액션바 쪽은 {@link Sparring}
     * 카운트다운 앞에 배선됐다 (2026-07-16 · B-116 회차). 명패 쪽은 문구·자리 정본이 없어
     * 미배선 — 미결. 팩 미설치 시 □ — 빌더 주석의 계약대로 문구만으로도 읽힌다.
     */
    static final String BIMU = "\uE030";

    private static final char GAUGE_BASE = '\uE010';

    private Glyphs() {
    }

    /** U+E010~E018 — 화후 게이지 0~8칸 */
    static String gauge(double ratio) {
        int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, ratio)) * 8);
        return String.valueOf((char) (GAUGE_BASE + filled));
    }

    private static final char CREST_BASE = 0xE020;
    /** cultivation_stages 순서 (범인 제외) — build_resourcepack.py REALM_CRESTS와 동서열 */
    private static final String[] CREST_REALMS =
            {"삼류", "이류", "일류", "절정", "초절정", "화경", "현경", "생사경"};

    /** U+E020~E027 — 경지 문장 8단 (미등록 경지·범인은 빈 문자열) */
    static String realmCrest(String realm) {
        for (int i = 0; i < CREST_REALMS.length; i++) {
            if (CREST_REALMS[i].equals(realm)) {
                return String.valueOf((char) (CREST_BASE + i));
            }
        }
        return "";
    }

    static String[] crestRealms() {
        return CREST_REALMS.clone();
    }

    /** U+E080 — 경락도 GUI 배경 패널 (음수 공백 기법 — 정식 GUI 결선 시 사용) */
    static final String GUI_LEDGER = String.valueOf((char) 0xE080);
}
