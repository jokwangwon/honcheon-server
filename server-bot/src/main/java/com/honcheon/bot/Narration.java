package com.honcheon.bot;

import com.honcheon.core.rules.RulesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 서사 폴백의 <b>읽는 손</b> — 사냥·비무 결과 산문의 정본은 {@code config/narration.yml} 이다
 * (llm.yml failure_handling: LLM 없이도 게임은 돈다).
 *
 * <p><b>★ 이 클래스는 이야기를 짓지 않는다.</b> 전에는 지었다 — 사냥 7문장과 비무 2문장이
 * 자바 문자열로 여기 박혀 있었다 (BACKLOG B-074). 등록제 위반이다: 코드가 이야기를 지고 있었다.
 * 서장의 글이 {@code config/seojang.yml} 로 나간 것(2026-07-13)과 같은 처방으로 전부
 * <b>{@code config/narration.yml}</b> 로 옮겼다. <b>여기에 문장을 다시 적지 마라</b> —
 * 두 벌이 되면 하나가 낡는다.
 *
 * <p><b>치환 자리 규약</b>(등록부에도 적혀 있다): {@code {짐승}} · {@code {승자}} · {@code {패자}}.
 * 코드는 이 자리만 채운다 — 문장을 잇지 않는다.
 *
 * <p><b>왜 스스로 등록부를 여는가.</b> 호출부({@code GameListener})가 정적으로 부르는 시그니처를
 * 지키기로 했다 — {@link Rules} 를 꿰어 넣으면 호출부가 전부 흔들린다. 그래서 {@code HoncheonBot}
 * 과 같은 규약({@code HONCHEON_CONFIG}, 기본 {@code config})으로 첫 호출에 한 번만 읽는다.
 *
 * <p><b>등록부가 없거나 깨지면</b> 봇은 죽지 않는다 — 산문 없는 생존 한 줄로 버티되,
 * severe 로그로 소리낸다 (조용한 실패 금지).
 */
final class Narration {

    private static final Logger LOG = LoggerFactory.getLogger(Narration.class);

    // ★ 생존 한 줄 — 등록부가 죽었을 때의 최소한. 산문이 아니다 (여기가 이야기를 지면 B-074 재발이다)
    private static final String SURVIVE_HUNT = "사냥이 끝났다.";
    private static final String SURVIVE_DUEL = "비무가 끝났다.";

    /** 등록부의 평면 사본 ("hunt.대성공" → 문장). 첫 호출에 한 번만 읽는다 — null 이면 아직 안 읽었다 */
    private static volatile Map<String, String> lines;

    private Narration() {
    }

    /** 사냥 결과 폴백 — 등급 5분류로 온도를 정한다 (수치는 embed 몫). 문장은 narration.yml hunt.* */
    static String hunt(String beast, String tierName, boolean pelt) {
        // ★ 환산점은 Seojang.grade 하나다 — 전에는 여기 사설 enum 이 같은 일을 두 벌로 했다
        String grade = Seojang.grade(tierName);
        String key = switch (grade) {
            // 성공·중간만 가죽의 유무가 문장을 가른다 (등록부의 가지와 일치)
            case "성공", "중간" -> "hunt." + grade + (pelt ? ".가죽_있음" : ".가죽_없음");
            default -> "hunt." + grade;
        };
        return line(key, SURVIVE_HUNT).replace("{짐승}", beast);
    }

    /** 비무 결과 폴백 — 승/무/패는 엔진이 정했고, 여기는 예의만. 문장은 narration.yml duel.* */
    static String duel(String winner, String loser, boolean draw) {
        if (draw) {
            return line("duel.무승부", SURVIVE_DUEL);
        }
        return line("duel.승패", SURVIVE_DUEL)
                .replace("{승자}", winner)
                .replace("{패자}", loser);
    }

    /** 등록부의 문장 하나 — 없으면 생존 한 줄로 버티고 **소리낸다** (조용한 실패 금지) */
    private static String line(String key, String survive) {
        String v = registry().get(key);
        if (v == null || v.isBlank()) {
            LOG.error("서사 등록부에 문장이 없다 — config/narration.yml {} (생존 한 줄로 버틴다)", key);
            return survive;
        }
        return v;
    }

    /** 첫 호출에 한 번만 읽는다 — 등록부가 깨져도 봇은 죽지 않는다 (빈 사본으로 버틴다) */
    private static Map<String, String> registry() {
        Map<String, String> l = lines;
        if (l == null) {
            synchronized (Narration.class) {
                l = lines;
                if (l == null) {
                    l = load();
                    lines = l;
                }
            }
        }
        return l;
    }

    private static Map<String, String> load() {
        Map<String, String> flat = new LinkedHashMap<>();
        try {
            // HoncheonBot 과 같은 규약 — 봇의 설정 디렉터리는 이 env 하나로 정해진다
            Path dir = Path.of(System.getenv().getOrDefault("HONCHEON_CONFIG", "config"));
            flatten("", RulesConfig.load(dir.resolve("narration.yml")), flat);
        } catch (RuntimeException broken) {
            // ★ 깨진 채로 산다 — 빈 사본이면 모든 조회가 생존 한 줄로 떨어지고, line() 이 건마다 소리낸다
            LOG.error("서사 등록부를 못 읽었다 — config/narration.yml (생존 한 줄로 버틴다)", broken);
        }
        return Map.copyOf(flat);
    }

    /** 중첩 맵을 "hunt.성공.가죽_있음" 꼴의 평면 키로 — 등록부의 가지 모양을 코드가 외우지 않기 위해 */
    private static void flatten(String prefix, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> m) {
            m.forEach((k, v) -> flatten(prefix.isEmpty() ? String.valueOf(k) : prefix + "." + k, v, out));
        } else if (node != null) {
            out.put(prefix, String.valueOf(node));
        }
    }
}
