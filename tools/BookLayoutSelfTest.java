package com.honcheon.mvt;

import java.util.List;

/**
 * SJ-001 조판 계약의 눈 — <b>서장 서책이 화면 밖으로 글을 흘리지 않는가.</b>
 *
 * <p>계약 (docs/design/seojang_presentation.md §SJ-001 닫는 조건):
 * <blockquote>
 *   "선택지가 본문에 밀려 잘리거나 빈 페이지 뒤에 놓이지 않는다." ·
 *   "같은 입력은 항상 같은 페이지 배열을 만든다." ·
 *   "장식 글리프를 쓰지 않는 fixture에서도 클릭 문구가 살아 있다."
 * </blockquote>
 *
 * <p>fixture (청사진 §SJ-001): 빈 본문 · 매우 긴 문장 · 여러 문단 · 한글·숫자 혼합 ·
 * GUI 배율 2·3·4용 긴 본문 (배율은 화면만 키우고 조판 폭 114px 은 그대로다 — 하나로 족하다).
 *
 * <h2>어떻게 돌리나 (저장소 루트에서 · 서버를 켜지 않는다 — BookLayout 은 Bukkit 을 모른다)</h2>
 * <pre>
 *   export JAVA_HOME=$PWD/run/jdk-21
 *   ./gradlew :server-mvt:compileJava -q
 *   CP=server-mvt/build/classes/java/main
 *   $JAVA_HOME/bin/javac -d /tmp/booklayout-eye -cp "$CP" tools/BookLayoutSelfTest.java
 *   $JAVA_HOME/bin/java -cp "$CP:/tmp/booklayout-eye" com.honcheon.mvt.BookLayoutSelfTest
 * </pre>
 */
public final class BookLayoutSelfTest {

    private static int eyes;

    /** 실제 머리말이 먹는 줄 수의 전형 — "제 1 장" 1줄 + 제목 1줄 + 빈 줄 1 (SeojangBook 계산과 같은 꼴) */
    private static final int HEADER = 3;

    private BookLayoutSelfTest() {
    }

    public static void main(String[] args) {
        // ══════════ ① 자(尺) — 한글·숫자 혼합은 글자 수가 아니라 픽셀로 갈린다 ══════════
        // 한글 전각 9px: 12자 = 108px ≤ 114 (한 줄) · 13자 = 117px (두 줄)
        eye("한글 12자는 한 줄이다 (12×9=108 ≤ 114)", BookLayout.lines("가".repeat(12), false) == 1);
        eye("한글 13자는 두 줄이다 (13×9=117 > 114)", BookLayout.lines("가".repeat(13), false) == 2);
        // 숫자 6px: 19자 = 114px 꼭 맞음 · 20자 = 넘침 — 같은 글자 수라도 한글과 쪽이 다르다
        eye("숫자 19자는 한 줄이다 (19×6=114)", BookLayout.lines("7".repeat(19), false) == 1);
        eye("숫자 20자는 두 줄이다", BookLayout.lines("7".repeat(20), false) == 2);
        // 굵은 글씨 +1px: 머리말 제목 폭 계산이 이 눈에 기댄다
        eye("굵은 한글 11자는 한 줄이다 (11×10=110)", BookLayout.lines("가".repeat(11), true) == 1);
        eye("굵은 한글 12자는 두 줄이다 (12×10=120)", BookLayout.lines("가".repeat(12), true) == 2);
        eye("빈 글은 0줄이다 (머리말 계산 관례)", BookLayout.lines("", false) == 0);
        eye("강제 줄바꿈 \\n 을 센다", BookLayout.lines("가\n나", false) == 2);

        // ══════════ ② 빈 본문 — 쪽을 만들지 않는다 (선택 쪽 앞의 빈 쪽 금지) ══════════
        eye("빈 본문은 0쪽이다", BookLayout.paginate("", HEADER).isEmpty());
        eye("null 본문은 0쪽이다", BookLayout.paginate(null, HEADER).isEmpty());
        eye("공백뿐인 본문은 0쪽이다", BookLayout.paginate("  \n\n  ", HEADER).isEmpty());

        // ══════════ ③ 결정론 — 같은 입력은 항상 같은 배열 (Random·시계 금지) ══════════
        String prose = fixtureParagraphs();
        eye("같은 입력은 같은 쪽 배열이다",
                BookLayout.paginate(prose, HEADER).equals(BookLayout.paginate(prose, HEADER)));

        // ══════════ ④ 여러 문단 — 문장 보존·용량·내용 보존 ══════════
        List<String> pages = BookLayout.paginate(prose, HEADER);
        eye("여러 문단 fixture 가 여러 쪽이 된다", pages.size() >= 2);
        eye("모든 쪽이 용량 안이다 (첫 쪽은 머리말 몫을 뺀다)", allFit(pages, HEADER));
        eye("빈 쪽이 없다", pages.stream().noneMatch(String::isBlank));
        eye("글자가 한 자도 사라지지 않았다", squeeze(String.join("", pages)).equals(squeeze(prose)));
        eye("모든 쪽이 문장 끝에서 끝난다 (가운데 잘림 없음)",
                pages.stream().allMatch(p -> p.endsWith(".") || p.endsWith("?") || p.endsWith("!")));
        eye("문단 사이 빈 줄이 쪽 안에 살아 있다", pages.stream().anyMatch(p -> p.contains("\n\n")));

        // ══════════ ⑤ 경계 길이 — 꼭 맞으면 한 쪽, 한 자 넘치면 두 쪽 ══════════
        // 공백 없는 한글: 12자/줄 × 14줄 = 168자가 쪽의 만석이다 (headerLines=0 기준)
        String full = "몸".repeat(12 * 14);
        eye("만석 168자는 정확히 한 쪽이다", BookLayout.paginate(full, 0).size() == 1);
        eye("169자는 두 쪽이 된다", BookLayout.paginate(full + "몸", 0).size() == 2);
        eye("두 쪽째도 비어 있지 않다", !BookLayout.paginate(full + "몸", 0).get(1).isBlank());

        // ══════════ ⑥ 매우 긴 문장 — 공백에서 자르고, 그마저 없으면 글자에서 ══════════
        // 공백 있는 긴 문장 (마침표 없음): 낱말 가운데를 자르지 않는다
        String spaced = "청산유수로흐른다 ".repeat(60).strip();
        List<String> sp = BookLayout.paginate(spaced, HEADER);
        eye("공백 있는 긴 문장이 여러 쪽으로 나뉜다", sp.size() >= 2);
        eye("긴 문장도 모든 쪽이 용량 안이다", allFit(sp, HEADER));
        eye("자를 때 낱말을 가르지 않는다 (모든 쪽이 온전한 낱말로 끝난다)",
                sp.stream().allMatch(p -> p.endsWith("다")));
        eye("긴 문장에서도 글자가 사라지지 않는다", squeeze(String.join("", sp)).equals(squeeze(spaced)));
        // 공백조차 없는 한 덩어리 — 최후의 수단: 글자에서 자르되 글자를 잃지 않는다
        String solid = "혼천의무너진하늘아래".repeat(50);
        List<String> so = BookLayout.paginate(solid, HEADER);
        eye("공백 없는 덩어리도 모든 쪽이 용량 안이다", allFit(so, HEADER));
        eye("글자에서 잘라도 글자를 잃지 않는다", squeeze(String.join("", so)).equals(squeeze(solid)));

        // ══════════ ⑦ 머리말 공간 — 첫 쪽만 좁다 ══════════
        List<String> h0 = BookLayout.paginate(full, 0);
        List<String> h6 = BookLayout.paginate(full, 6);
        eye("머리말이 클수록 첫 쪽 본문이 준다",
                BookLayout.lines(h6.get(0), false) <= BookLayout.PAGE_LINES - 6
                        && BookLayout.lines(h0.get(0), false) == BookLayout.PAGE_LINES);
        // 괴물 제목: 머리말이 첫 쪽을 다 먹으면 본문은 둘째 쪽부터 (첫 자리는 머리말이 채운다)
        List<String> mh = BookLayout.paginate("본문은 둘째 쪽부터 시작한다.", BookLayout.PAGE_LINES);
        eye("머리말이 쪽을 다 먹으면 [0] 은 빈 자리다 (머리말 전용 쪽)",
                mh.size() == 2 && mh.get(0).isEmpty() && !mh.get(1).isBlank());

        // ══════════ ⑧ 선택 쪽 보존 — 본문 마지막 쪽이 깨끗해야 선택 쪽이 산다 ══════════
        // SeojangBook 은 paginate 결과 **뒤에** 선택 쪽 하나를 덧붙인다. 그러니 조판이
        // (a) 빈 꼬리 쪽을 안 내고 (b) 용량을 안 넘기면, 선택지는 밀리지도 빈 쪽 뒤에 서지도 않는다
        for (List<String> p : List.of(pages, sp, so)) {
            if (!p.isEmpty() && (p.get(p.size() - 1).isBlank()
                    || BookLayout.lines(p.get(p.size() - 1), false) > BookLayout.PAGE_LINES)) {
                throw new AssertionError("본문 마지막 쪽이 비었거나 넘친다 — 선택 쪽이 위험하다");
            }
        }
        eye("본문 마지막 쪽은 언제나 차 있고 넘치지 않는다 (선택 쪽 보존)", true);
        // 장식 글리프 없는 맨글 fixture — 조판이 클릭 문구 재료(선택 라벨 글자)를 먹지 않는다
        String plain = "▸ 스승의 유품을 줍는다. 그리고 2d6 을 굴린다. 결과는 7 이상이어야 한다.";
        List<String> pl = BookLayout.paginate(plain, HEADER);
        eye("장식 글리프 없는 맨글에서도 표식·숫자가 그대로 남는다",
                String.join("", pl).contains("▸") && squeeze(String.join("", pl)).equals(squeeze(plain)));

        // ══════════ ⑨ GUI 배율 2·3·4용 긴 본문 — LLM 상한 길이도 끝까지 간다 ══════════
        String llm = fixtureParagraphs().repeat(8);   // ≈ LLM 상한급 본문
        List<String> big = BookLayout.paginate(llm, HEADER);
        eye("긴 본문도 모든 쪽이 용량 안이다 (배율은 조판 폭 114px 을 안 바꾼다)", allFit(big, HEADER));
        eye("긴 본문도 결정론이다", big.equals(BookLayout.paginate(llm, HEADER)));
        eye("긴 본문도 글자를 잃지 않는다", squeeze(String.join("", big)).equals(squeeze(llm)));
        eye("긴 본문에 빈 쪽이 없다", big.stream().noneMatch(String::isBlank));

        System.out.println("✔ 조판 계약 눈 " + eyes + "개 — 전부 통과");
    }

    /** 여러 문단 · 한글·숫자 혼합 · 대화 따옴표가 섞인 서장풍 fixture (마침표 계열로만 끝난다) */
    private static String fixtureParagraphs() {
        return """
                이경 무렵, 화산 남쪽 능선의 바람이 갑자기 멎었다. 스물세 해를 산 몸이 처음 듣는 고요였다. \
                담 너머에서 병장기 부딪는 소리가 세 번 울렸고, 네 번째는 없었다.

                사형의 방은 비어 있었다. 탁자 위에 놓인 서찰 한 장, 먹이 아직 마르지 않았다. \
                "닷새 안에 장문인의 인장을 되찾지 못하면, 화산은 문을 닫는다." 숫자 다섯이 낙인처럼 박혔다.

                품 안의 은자는 열두 냥. 발 아래 길은 셋이었다. 어느 길이든 되돌릴 수 없다는 것만은 분명했다.""";
    }

    /** 모든 쪽이 제 용량(첫 쪽은 머리말 몫을 뺀 값) 안인가 */
    private static boolean allFit(List<String> pages, int headerLines) {
        for (int i = 0; i < pages.size(); i++) {
            int cap = i == 0 ? BookLayout.PAGE_LINES - headerLines : BookLayout.PAGE_LINES;
            if (BookLayout.lines(pages.get(i), false) > cap) {
                return false;
            }
        }
        return true;
    }

    /** 공백을 걷어 낸 알맹이 — 조판은 여백만 만지고 글자는 못 만진다 */
    private static String squeeze(String s) {
        return s.replaceAll("\\s+", "");
    }

    private static void eye(String name, boolean passed) {
        if (!passed) {
            throw new AssertionError(name);
        }
        eyes++;
        System.out.println("  ✔ " + name);
    }
}
