package com.honcheon.mvt;

/**
 * 리소스팩 글리프 (tools/build_resourcepack.py 가 minecraft:default 폰트에 주입).
 * 팩 미설치 클라이언트에는 □ 로 보인다 — MVT에서는 허용 (가이드에 설치 안내).
 * 글리프는 백색 비트맵 — 채팅 색 코드가 그대로 틴트한다 (기세 4색 = 색 코드 4종 × 아이콘 1종).
 */
final class Glyphs {

    /** U+E000 — 기세 아이콘 (색 코드로 틴트: 회/백/황/적) */
    static final String GISE = "";

    private static final char GAUGE_BASE = '';

    private Glyphs() {
    }

    /** U+E010~E018 — 화후 게이지 0~8칸 */
    static String gauge(double ratio) {
        int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, ratio)) * 8);
        return String.valueOf((char) (GAUGE_BASE + filled));
    }
}
