package com.honcheon.mvt;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 액션바 <b>한 줄의 주인</b> — 순수 로직 (Bukkit 을 모른다 — {@code tools/HudLineSelfTest} 가 시험한다).
 *
 * <p><b>B-116 (실사용 발견)</b>: 액션바는 한 줄뿐인데 손이 여럿이었다 — 격 두름·경공의 순간 문구를
 * 4틱(0.2초)마다 도는 statusBar 가 곧바로 덮어, 화면에서 글이 겹쳐 읽혔다 (사람은 0.2초를 못 읽는다).
 * 그래서 주인 규칙을 하나로 못 박는다:
 * <ol>
 *   <li><b>순간 사건</b>(판정·격 태세 전환·경공 도약·낙법)은 {@link #flash} 로 줄을 잠깐 갖는다 —
 *       읽을 시간({@code skill_motion.yml hud.flash_read_ticks})이 지나면 놓는다.
 *       새 사건이 오면 그 사건이 이긴다 (마지막 사건이 가장 지금이다).</li>
 *   <li><b>지속 상태</b>(생명·태세·격 두름·내력·경공 유지)는 statusBar 합성 한 줄 —
 *       {@link #compose} 가 조각들을 한 구분자로 병기한다. 지속 상태끼리는 겹치지 않는다:
 *       같은 줄의 다른 자리다.</li>
 * </ol>
 */
final class HudLine {

    private final Map<UUID, String> flashText = new HashMap<>();
    private final Map<UUID, Long> flashUntil = new HashMap<>();

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
            forget(id);
            return null;
        }
        return flashText.get(id);
    }

    void forget(UUID id) {
        flashUntil.remove(id);
        flashText.remove(id);
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
