package com.honcheon.mvt;

/**
 * <b>접근(access) 판정기 — B-151 셋째 축.</b>
 *
 * <p>세계 지도의 세 축은 서로 다른 것이다 (world_map.yml §5.8 · schema.fields):
 * <ul>
 *   <li><b>build</b> — 조성 수명주기 (지금 서는가 · 나중 · 안 선다)</li>
 *   <li><b>hidden / player_map</b> — <b>표시</b> 축. 플레이어 지도에 보이는가
 *       ({@link MvtCommand#showMap} · {@link MvtCommand#travel} 목록이 읽는다)</li>
 *   <li><b>access</b> — <b>해금</b> 축. <b>이 판정기가 읽는다.</b> 좌표를 안다고 갈 수 있는 것이 아니다
 *       (새외가 그 증거다: "좌표는 안다. 그러나 못 간다").</li>
 * </ul>
 *
 * <p><b>왜 이 판정기가 생겼는가 (Codex 검토, 2026-07-15, CODEX_WAVE2_MIGYEOL_REVIEW §8):</b>
 * {@code /혼천 출행 <id>} 가 access 를 검사하지 않아, 비op 가 id 만 알면 hidden·소문·세력·관문을
 * <b>전부 우회</b>해 출행했다. 표시 축(hidden)만 서 있었고 접근 축은 <b>부재</b>였다.
 *
 * <p><b>단일 창구.</b> 목록·지도·id 직행·타 진입점이 <b>전부 이 판정기 하나</b>를 부른다 (두 벌 금지).
 *
 * <p><b>닫힌 타입 (등록제 §2.1 — 조용히 넘기지 마라).</b> access 값은 {@code config/world_map.yml}
 * 의 <b>실제 어휘</b>에서 유도한다 (지어내지 않는다). {@link Access} 에 없는 값은 <b>미지값</b>이고,
 * 미지값은 <b>비op 거부</b>다 (오타·발명을 조용히 통과시키지 않는다 — 그리고 {@code map_lint} 가 짖는다).
 *
 * <p><b>★ 평가 불가 조건의 처리.</b> 관문형(소문·세력·관문 등)의 <b>실제 조건</b>(누구의 세력인가,
 * favor 몇 이상인가)은 <b>장소 수준의 access 토큰에 담겨 있지 않다</b> — 그 매개변수는 travel 모드·산문에
 * 흩어져 있고, 개인별 <b>발견·소문·세력·통행 공개 장부</b>는 <b>아직 없다</b>. favor 자체는
 * {@code WorldBridge.state().favor} 로 조회되지만, 토큰이 (세력, 임계) 쌍을 주지 않으므로
 * <b>임계를 지어내는 것은 창작</b>이다. 그래서 관문형은 지금 <b>평가 불가</b>다 →
 * <b>비op 거부(안전) · op 통과(검수의 눈)</b>. 공개 장부가 서면 여기에 배선한다 (TODO).
 *
 * <p><b>서버 무의존.</b> 이 클래스는 Bukkit·WorldMap 을 <b>부르지 않는다</b> — 입력은 access 문자열과
 * {@code isOp} 뿐이다. 그래서 {@code tools/AccessJudgeSelfTest.java} 가 서버 없이 눈을 시험한다.
 */
final class AccessJudge {

    private AccessJudge() {
    }

    /**
     * 닫힌 access 어휘 — <b>config/world_map.yml 의 실제 값에서 유도했다</b> (지어낸 낱말이 아니다).
     * {@code open=true} 는 공개(누구나 허용), 나머지는 <b>관문형</b>(조건 평가 대상).
     *
     * <p>★ 이 목록이 바뀌면 {@code tools/map_lint.py} 의 {@code ACCESS_VOCAB} 도 함께 바뀌어야 한다
     * (둘은 같은 어휘를 봐야 한다 — map_lint 가 config 를, 이 enum 이 런타임을 지킨다).
     */
    enum Access {
        ALWAYS("항상", true),       // 공개 — 42곳
        ANYONE("누구나", true),     // 공개 — travel 모드 어휘(도보). 완결성을 위해 함께 둔다
        HIDDEN("hidden", false),    // 발견해야 열린다 (좌표는 알아도 길이 없다)
        RUMOR("소문", false),       // 소문을 들어야 위치가 열린다
        SERENDIPITY("기연", false), // 아무 때나 열리지 않는다
        TRADE_ROUTE("상로", false), // 상단 동행·상로 한정
        PUNISHMENT("형벌", false),  // 제 발로 가는 곳이 아니다
        SEA_ROUTE("해로", false),   // 바다에는 문이 없다 — 배가 있어야 한다
        CONDITIONAL("조건부", false), // 찾아가지 않는다 — 찾아온다
        RESTRICTED("제한", false),  // 지정 경로를 통과해야 한다
        STUDY("유학", false),       // 유학의 문으로만 돌아온다
        FACTION("세력", false),     // 소속이거나 적으로만 들어간다
        GATE("관문", false);        // 통행증·국경을 지나야 한다

        final String token;
        final boolean open;

        Access(String token, boolean open) {
            this.token = token;
            this.open = open;
        }
    }

    /** 판정 결과 — 허용 여부와 <b>한국어 사유</b>. 관문의 판정은 언제나 사유를 남긴다 (조용히 막으면 함정이다) */
    record Verdict(boolean allowed, String reason) {
    }

    /**
     * 닫힌 타입 파싱 — 어휘에 있으면 {@link Access}, 없으면(또는 null) {@code null}(미지값).
     * <b>미지값을 임의로 해석하지 않는다</b> (그것이 등록제다).
     */
    static Access classify(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        for (Access a : Access.values()) {
            if (a.token.equals(t)) {
                return a;
            }
        }
        return null;
    }

    /**
     * <b>단일 창구.</b> (access 문자열, op 여부) → 허용/거부 + 사유.
     *
     * <ul>
     *   <li><b>공개</b>(항상·누구나) → 누구나 허용</li>
     *   <li><b>관문형</b> → 평가 불가(공개 장부 미구현): 비op 거부 · op 통과(검수)</li>
     *   <li><b>미지값</b>(어휘 밖·오타·발명) → 비op 거부(등록제 위반) · op 통과</li>
     *   <li><b>미등록</b>(access 없음/null) → 비op 거부(안전) · op 통과</li>
     * </ul>
     */
    static Verdict judge(String rawAccess, boolean isOp) {
        Access a = classify(rawAccess);
        if (a != null && a.open) {
            return new Verdict(true, "공개(" + a.token + ")");
        }
        // 여기부터: 관문형 · 미지값 · 미등록 — 셋 다 개인별 공개 장부를 요구하는데, 그 장부가 아직 없다.
        if (isOp) {
            return new Verdict(true, "op 통과 — " + describe(a, rawAccess) + " (개인별 공개 장부 미구현)");
        }
        if (a == null) {
            return new Verdict(false, rawAccess == null
                    ? "이 곳은 접근 조건(access)이 등록되지 않았다 — 안전상 막는다"
                    : "알 수 없는 접근 조건 '" + rawAccess.trim() + "' — 등록제 위반이라 막는다");
        }
        return new Verdict(false, a.token
                + " 관문 — 아직 개인별 발견·소문·세력·통행 장부가 없어 평가할 수 없다 (안전상 막는다)");
    }

    /** op 통과 사유의 꼬리말 — 무엇을 통과시켰는지 검수의 눈에 남긴다 */
    private static String describe(Access a, String rawAccess) {
        if (a != null) {
            return a.token + " 관문";
        }
        return rawAccess == null ? "access 미등록(null)" : "미지 access '" + rawAccess.trim() + "'";
    }
}
