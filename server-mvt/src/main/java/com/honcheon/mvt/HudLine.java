package com.honcheon.mvt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 액션바 <b>한 줄의 주인</b> — 순수 로직 (Bukkit 을 모른다 — {@code tools/HudLineSelfTest} 가 시험한다).
 *
 * <p><b>B-116 (실사용 발견)</b>: 액션바는 한 줄뿐인데 손이 여럿이었다 — 격 두름·경공의 순간 문구를
 * 4틱(0.2초)마다 도는 statusBar 가 곧바로 덮어, 화면에서 글이 겹쳐 읽혔다 (사람은 0.2초를 못 읽는다).
 * 그래서 주인 규칙을 하나로 못 박는다 (★Codex R6~R8: SkillHud 바깥 손들도 전부 이 규칙으로 온다):
 * <ol>
 *   <li><b>순간 사건</b>(판정·격 태세 전환·경공 도약·낙법·화후 적립·기세 읽기)은 {@link #flash} 로
 *       줄을 잠깐 갖는다 — 읽을 시간({@code skill_motion.yml hud.flash_read_ticks})이 지나면 놓는다.
 *       새 사건이 오면 그 사건이 이긴다 (마지막 사건이 가장 지금이다).</li>
 *   <li><b>바깥 지속 표시</b>(비무 카운트다운·서장 집필 대기·시신 은닉 진행)는 {@link #notice} 로
 *       <b>채널</b>을 갖는다 — 줄을 통째로 갖지 않고, statusBar 합성의 <b>한 조각</b>이 된다.
 *       채널당 글자 하나(재송신은 대체)·수명(TTL)이 지나면 조각이 빠진다. 서로 다른 채널은
 *       등재 순서대로 병기된다 — 마지막-승자 덮어쓰기가 구조적으로 불가능하다.</li>
 *   <li><b>지속 상태</b>(생명·태세·격 두름·내력·경공 유지)는 statusBar 합성 한 줄 —
 *       {@link #compose} 가 조각들을 한 구분자로 병기한다. 지속 상태끼리는 겹치지 않는다:
 *       같은 줄의 다른 자리다.</li>
 * </ol>
 *
 * <p>우선순위 계약: <b>flash(순간) &gt; statusBar 합성(지속 상태 + notice 조각들)</b>.
 * 직접 {@code sendActionBar} 는 금지다 — 남은 손은 {@code tools/HudLineSelfTest} 의
 * 정적 감사(⑨)가 짖는다.
 */
final class HudLine {

    private final Map<UUID, String> flashText = new HashMap<>();
    private final Map<UUID, Long> flashUntil = new HashMap<>();
    /** 채널 → 글자 — 등재 순서 유지 (조각이 줄 안에서 자리를 바꾸면 눈이 못 따라간다) */
    private final Map<UUID, LinkedHashMap<String, String>> noticeText = new HashMap<>();
    private final Map<UUID, Map<String, Long>> noticeUntil = new HashMap<>();

    /** 순간 사건이 줄을 갖는다 — {@code until} 틱까지. 새 사건은 이전 사건을 대체한다 */
    void flash(UUID id, String text, long until) {
        flashText.put(id, text);
        flashUntil.put(id, until);
    }

    /** 지금 줄의 주인 — 살아 있는 flash 의 글자. 없으면(만료 포함) null — 지속 상태 합성의 차례다 */
    String owner(UUID id, long now) {
        Long until = flashUntil.get(id);
        if (until == null) {
            return null;
        }
        if (now > until) {
            flashUntil.remove(id);
            flashText.remove(id);
            return null;
        }
        return flashText.get(id);
    }

    /**
     * 바깥 손의 지속 표시 — 채널 하나가 조각 하나를 갖는다 ({@code until} 틱까지).
     * 같은 채널의 재송신은 글자만 갈고 자리를 지킨다 (LinkedHashMap put 규약).
     */
    void notice(UUID id, String channel, String text, long until) {
        noticeText.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(channel, text);
        noticeUntil.computeIfAbsent(id, k -> new HashMap<>()).put(channel, until);
    }

    /** 채널을 비운다 — 판이 끝났으면 카운트다운도 끝이다 (TTL 만료를 기다리지 않는다) */
    void dropNotice(UUID id, String channel) {
        LinkedHashMap<String, String> texts = noticeText.get(id);
        Map<String, Long> untils = noticeUntil.get(id);
        if (texts != null) {
            texts.remove(channel);
        }
        if (untils != null) {
            untils.remove(channel);
        }
    }

    /** 살아 있는 notice 조각들 — 등재 순서. 만료된 채널은 여기서 걷어 낸다 (지연 청소) */
    List<String> notices(UUID id, long now) {
        LinkedHashMap<String, String> texts = noticeText.get(id);
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        Map<String, Long> untils = noticeUntil.get(id);
        List<String> alive = new ArrayList<>(texts.size());
        var it = texts.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Long until = untils == null ? null : untils.get(e.getKey());
            if (until == null || now > until) {
                it.remove();
                if (untils != null) {
                    untils.remove(e.getKey());
                }
                continue;
            }
            alive.add(e.getValue());
        }
        return alive;
    }

    void forget(UUID id) {
        flashUntil.remove(id);
        flashText.remove(id);
        noticeText.remove(id);
        noticeUntil.remove(id);
    }

    /** 지속 상태 합성 — null·빈 조각은 건너뛰고 구분자(색 코드 포함)로 병기한다 */
    static String compose(String separator, String... segments) {
        StringBuilder line = new StringBuilder();
        for (String segment : segments) {
            if (segment == null || segment.isEmpty()) {
                continue;
            }
            if (line.length() > 0) {
                line.append(separator);
            }
            line.append(segment);
        }
        return line.toString();
    }
}
