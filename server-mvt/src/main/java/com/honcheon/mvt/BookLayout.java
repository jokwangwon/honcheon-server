package com.honcheon.mvt;

import java.util.ArrayList;
import java.util.List;

/**
 * 서책 조판기(組版機) — <b>글자 수가 아니라 줄 폭과 줄 수로 잰다.</b>
 *
 * <p><b>★ 왜 따로 사는가.</b> 예전 서장은 "130자마다 자른다"였다 — 자는 글자 수인데 화면은
 * 픽셀이다. 한글은 넓고 숫자는 좁으니 같은 130자라도 어떤 쪽은 넘치고 어떤 쪽은 반이 빈다.
 * 넘친 본문은 바닐라 책 화면이 <b>말없이 자른다</b> (아래로 흘려 주지 않는다) — 그러면 선택지가
 * 밀리거나 문장이 실종된다. 그래서 조판은 화면이 재는 그대로 재야 한다: <b>줄의 픽셀 폭 × 쪽의 줄 수</b>.
 *
 * <p><b>★★ 여기에는 이야기도 클릭도 없다.</b> 글은 봇의 것이고 클릭 문구는 {@link SeojangBook}
 * 이 짓는다. 이 클래스는 순수한 자(尺)다 — Bukkit 도 Random 도 시계도 모른다.
 * <b>같은 입력은 언제나 같은 쪽 배열이다</b> (청사진 SJ-001 닫는 조건: 결정론).
 *
 * <p>쪽의 문법 (청사진 §3.2):
 * <ol>
 *   <li>첫 쪽 — 머리말(장 번호·제목·빈 줄)이 먼저 먹은 뒤 본문 시작 ({@code headerLines} 로 뺀다)</li>
 *   <li>가운데 쪽 — 본문만, 문단과 문장을 가능한 한 보존</li>
 *   <li>마지막 쪽 — 본문 끝. <b>빈 쪽은 절대 만들지 않는다</b> (선택 쪽이 빈 쪽 뒤에 서면 안 된다)</li>
 *   <li>선택 쪽 — 여기 소관이 아니다. {@link SeojangBook#pages} 가 따로 한 쪽을 덧붙인다</li>
 * </ol>
 */
final class BookLayout {

    /**
     * 본문 한 줄의 픽셀 폭 = <b>114</b>.
     * 근거: 바닐라 클라이언트 {@code BookViewScreen}(모장 매핑)이 본문을 폭 114px 로 줄바꿈한다
     * ({@code BookEditScreen.TEXT_WIDTH} 와 같은 값. 위키의 "책 한 줄 ≈ ASCII 19자" 통설과 일치:
     * 19자 × 6px = 114). GUI 배율 2·3·4 는 화면만 키우고 <b>이 논리 폭은 그대로다</b> —
     * 그래서 배율별로 다른 조판이 필요 없다.
     */
    static final int LINE_PIXELS = 114;

    /**
     * 한 쪽의 줄 수 = <b>14</b>.
     * 근거: 같은 화면의 본문 영역 높이 128px ÷ 글줄 높이 9px = 14줄 (통설 "페이지당 14줄"과 일치).
     * 15번째 줄부터는 화면 밖 — 그려지지 않고 <b>조용히 사라진다</b>. 그래서 이 수를 넘기면 안 된다.
     */
    static final int PAGE_LINES = 14;

    /**
     * 바닐라 {@code ascii.png} 글리프의 전진 폭(자간 1px 포함), 0x20(공백)~0x7E(~).
     * 대부분 6px 이지만 좁은 놈들이 있다: 공백 4 · {@code i},{@code !},{@code .},{@code ,} 2 ·
     * {@code l} 3 · {@code t},{@code I} 4 · {@code f},{@code k} 5. 숫자는 전부 6.
     */
    private static final int[] ASCII = {
            4, 2, 5, 6, 6, 6, 6, 3, 5, 5, 5, 6, 2, 6, 2, 6,   // (공백) ! " # $ % & ' ( ) * + , - . /
            6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 2, 2, 5, 6, 5, 6,   // 0-9 : ; < = > ?
            7, 6, 6, 6, 6, 6, 6, 6, 6, 4, 6, 6, 6, 6, 6, 6,   // @ A-O (I=4)
            6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 4, 6, 4, 6, 6,   // P-Z [ \ ] ^ _
            3, 6, 6, 6, 6, 6, 5, 6, 6, 2, 6, 5, 3, 6, 6, 6,   // ` a-o (f=5 i=2 k=5 l=3)
            6, 6, 6, 6, 4, 6, 6, 6, 6, 6, 6, 5, 2, 5, 7       // p-z { | } ~ (t=4)
    };

    private BookLayout() {
    }

    /**
     * 글자 하나의 전진 폭(px).
     *
     * <p>비ASCII = <b>9px 균일</b>. 근거: 바닐라 한글(과 CJK)은 GNU Unifont 의 전각 글리프
     * (16px 폭)를 반 축척 8px 로 그리고 자간 1px 을 더한다 — 한글 음절(U+AC00~D7A3)은 전부
     * 전각이라 폭이 균일하다. 반각 유니폰트 문자(일부 기호)는 5px 지만 여기서는 구분하지 않는다:
     * <b>과대추정은 자리 낭비, 과소추정은 글자 실종</b> — 낭비 쪽이 안전하다.
     */
    static int advance(int cp, boolean bold) {
        int w = cp >= 0x20 && cp <= 0x7E ? ASCII[cp - 0x20] : 9;
        return bold ? w + 1 : w;   // 굵은 글씨는 그림자 획 하나만큼 1px 넓다 (바닐라 규칙)
    }

    /** 화면이 줄바꿈한 뒤 이 글이 차지할 줄 수. 빈 글은 0 (머리말 계산이 이 관례를 쓴다). */
    static int lines(String text, boolean bold) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Wrap w = new Wrap(bold);
        text.codePoints().forEach(w::feed);
        return w.lines;
    }

    /**
     * 서사를 낱장으로 나눈다 — 반환 목록의 [0] 은 <b>머리말 아래 붙을 첫 쪽</b>이다.
     *
     * <p>규칙 (우선순위 순):
     * <ol>
     *   <li>어떤 쪽도 용량({@code capacity})을 넘지 않는다 — 넘치면 화면이 자르기 때문</li>
     *   <li><b>문장 가운데를 자르지 않는다</b> — 안 들어가는 문장은 통째로 다음 쪽</li>
     *   <li>단, 문장 하나가 쪽 하나보다 길면 어쩔 수 없이 자른다: 공백에서, 그마저 없으면 글자에서</li>
     *   <li>문단(빈 줄)은 같은 쪽 안에서만 산다 — 쪽 머리·꼬리의 빈 줄은 걷어 낸다</li>
     *   <li><b>빈 쪽을 내지 않는다</b> — 선택 쪽이 빈 쪽 뒤에 서면 안 된다 (닫는 조건)</li>
     * </ol>
     *
     * @param headerLines 첫 쪽에서 머리말이 이미 먹은 줄 수 (장 번호 + 제목 + 빈 줄)
     */
    static List<String> paginate(String prose, int headerLines) {
        List<String> out = new ArrayList<>();
        if (prose == null || prose.isBlank()) {
            return out;
        }
        // 머리말이 첫 쪽을 통째로 먹었다 (제목이 괴물처럼 길다) — 본문은 둘째 쪽부터.
        // 빈 [0] 은 SeojangBook 이 머리말로 채우니 화면에서는 빈 쪽이 아니다.
        if (headerLines >= PAGE_LINES) {
            out.add("");
        }
        StringBuilder page = new StringBuilder();
        for (String para : prose.strip().split("\n\n+")) {
            for (String sentence : splitSentences(para.strip())) {
                place(out, page, sentence, headerLines);
            }
            if (!page.isEmpty()) {
                page.append("\n\n");   // 문단 사이 빈 줄 — flush 가 꼬리 것은 걷어 낸다
            }
        }
        flush(out, page);
        return out;
    }

    /** 이 쪽(0번부터)의 본문 용량 — 첫 쪽만 머리말 몫을 뺀다 (청사진: 머리말 공간 별도 계산) */
    private static int capacity(int pageIndex, int headerLines) {
        return pageIndex == 0 ? PAGE_LINES - headerLines : PAGE_LINES;
    }

    /** 문장 하나를 지금 쪽에 앉히거나, 안 들어가면 쪽을 덮고 다음 쪽으로 보낸다 */
    private static void place(List<String> out, StringBuilder page, String sentence, int headerLines) {
        int cap = capacity(out.size(), headerLines);
        // 이 문장을 더하면 넘친다 → 지금 쪽을 덮는다. **문장 가운데 잘림 방지가 여백보다 우선**이다
        if (!page.isEmpty() && lines(page + sentence, false) > cap) {
            flush(out, page);
            cap = capacity(out.size(), headerLines);
        }
        String alone = sentence.strip();
        if (page.isEmpty() && lines(alone, false) > cap) {
            // 문장 하나가 쪽보다 길다 (LLM 이 숨을 안 쉬었다) — 최후의 수단으로 자른다:
            // 공백에서, 그마저 없으면 글자에서. 자리는 쪽 용량을 꽉 채운다 (빈 쪽 금지)
            String rest = alone;
            while (lines(rest, false) > cap) {
                int cut = fitIndex(rest, cap);
                out.add(rest.substring(0, cut).strip());
                rest = rest.substring(cut).stripLeading();
                cap = capacity(out.size(), headerLines);
            }
            page.append(rest);
            return;
        }
        page.append(sentence);
    }

    /** 쪽을 덮는다 — 앞뒤 여백을 걷고, **빈 쪽은 버린다** (선택 쪽 앞에 빈 쪽 금지) */
    private static void flush(List<String> out, StringBuilder page) {
        String t = page.toString().strip();
        if (!t.isEmpty()) {
            out.add(t);
        }
        page.setLength(0);
    }

    /**
     * {@code cap} 줄 안에 들어가는 가장 긴 머리의 끝 인덱스 — 공백이 있었다면 마지막 공백에서 끊는다
     * (낱말 가운데 잘림도 피한다). 최소 1글자는 반환한다 (전진 보장 — 무한 루프 금지).
     */
    private static int fitIndex(String s, int cap) {
        Wrap w = new Wrap(false);
        int lastSpace = -1;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            w.feed(cp);
            if (w.lines > cap) {
                return lastSpace > 0 ? lastSpace : Math.max(1, i);
            }
            if (cp == ' ') {
                lastSpace = i;
            }
            i += Character.charCount(cp);
        }
        return s.length();
    }

    /** 문장 단위 — 마침표·물음표·느낌표 뒤에서 끊는다 (따옴표는 붙여 둔다) */
    static List<String> splitSentences(String para) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < para.length(); i++) {
            char c = para.charAt(i);
            cur.append(c);
            if ((c == '.' || c == '?' || c == '!' || c == '\n')
                    && i + 1 < para.length() && para.charAt(i + 1) == ' ') {
                cur.append(' ');
                i++;
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    /**
     * 바닐라 줄바꿈 흉내 — 클라이언트 {@code StringSplitter} 의 탐욕 줄바꿈:
     * 폭이 넘치면 <b>그 줄의 마지막 공백</b>에서 꺾고 (공백 자체는 줄바꿈에 먹힌다),
     * 공백이 없으면 (한 낱말이 한 줄보다 길면) 글자에서 꺾는다. {@code \n} 은 강제 줄바꿈.
     */
    private static final class Wrap {
        final boolean bold;
        int lines = 1;
        int lineW;   // 지금 줄의 픽셀 폭
        int wordW;   // 마지막 공백 이후(= 지금 낱말)의 픽셀 폭 — 꺾을 때 낱말째 들고 간다

        Wrap(boolean bold) {
            this.bold = bold;
        }

        void feed(int cp) {
            if (cp == '\n') {
                lines++;
                lineW = 0;
                wordW = 0;
                return;
            }
            int w = advance(cp, bold);
            if (lineW + w > LINE_PIXELS) {
                if (cp == ' ') {   // 줄 끝에 걸친 공백 — 줄바꿈이 삼킨다 (다음 줄로 안 넘어간다)
                    lines++;
                    lineW = 0;
                    wordW = 0;
                    return;
                }
                if (wordW + w <= LINE_PIXELS && wordW < lineW) {
                    lines++;               // 낱말째 다음 줄로 (그 줄의 마지막 공백에서 꺾인 셈)
                    lineW = wordW + w;
                } else {
                    lines++;               // 낱말 하나가 한 줄보다 길다 — 글자에서 꺾는다
                    lineW = w;
                    wordW = 0;
                }
            } else {
                lineW += w;
            }
            wordW = cp == ' ' ? 0 : wordW + w;
        }
    }
}
