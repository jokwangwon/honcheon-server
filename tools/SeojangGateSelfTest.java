package com.honcheon.mvt;

import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>서장 게이트의 눈</b> — <b>새 몸이 서장 없이 강호로 나가지 않는가</b> (B-118).
 *
 * <p>실사용 (2026-07-14 · 부계정): 접합 직후 자동 출도가 서장을 통째로 건너뛰고 사람을
 * 청하현으로 보냈다. 게이트가 SeojangBook <b>토큰</b>(이미 배달된 책)만 봤는데, 새 몸은
 * 붓(LLM)이 서장을 짓는 수십 초 동안 토큰이 없다 — <b>경주에서 게이트가 이겼다.</b>
 *
 * <p>고친 판정: {@link WorldBridge#seojangHolds(UUID)} — 봇의 서장 명단(seojang.json)에
 * 실려 있는가(쓰는_중 포함). 이 눈은 <b>그리는 코드와 같은 문</b>({@code ingestSeojang})으로
 * 봇이 실제로 굽는 꼴의 JSON 을 밀어 넣고, 네 갈래를 전부 잰다:
 *
 * <ol>
 *   <li><b>서장 미완·쓰는_중</b> (토큰 없음 — B-118 의 경주) → 붙든다</li>
 *   <li><b>서장 미완·펼침</b> (책을 읽는 중) → 붙든다</li>
 *   <li><b>서장 완료/미대상</b> (명단에 없다) → 보낸다</li>
 *   <li><b>다리 죽음</b> (명단에 있으나 심장 소리가 낡았다) → 보낸다 — <b>가두지 않는다</b></li>
 * </ol>
 *
 * <h2>어떻게 돌리나 (저장소 루트에서 · 서버를 켜지 않는다)</h2>
 * <pre>
 *   export JAVA_HOME=$PWD/run/jdk-21
 *   ./gradlew :server-mvt:jar
 *   CP="server-mvt/build/libs/server-mvt-1.0.0.jar"
 *   $JAVA_HOME/bin/javac -nowarn -d /tmp/seojang-eye -cp "$CP" tools/SeojangGateSelfTest.java
 *   $JAVA_HOME/bin/java -cp "$CP:/tmp/seojang-eye" com.honcheon.mvt.SeojangGateSelfTest
 * </pre>
 */
public final class SeojangGateSelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    /** 봇의 GameListener.seojangEntries 가 굽는 꼴 그대로 (Bridge.publishSeojang) */
    private static String book(long at, String... entries) {
        return "{\"at\":" + at + ",\"scenes\":[" + String.join(",", entries) + "]}";
    }

    private static String writingEntry(UUID body) {
        return "{\"mc_uuid\":\"" + body + "\",\"character\":\"은천\",\"scene\":0,\"total\":3,"
                + "\"final\":false,\"title\":\"낯선 고을 청하현\",\"state\":\"쓰는_중\"}";
    }

    private static String openEntry(UUID body) {
        return "{\"mc_uuid\":\"" + body + "\",\"character\":\"은천\",\"scene\":1,\"total\":3,"
                + "\"final\":false,\"title\":\"낯선 고을 청하현\",\"state\":\"펼침\","
                + "\"narration\":\"그날 밤…\",\"token\":\"tok-1\",\"fallback\":false,"
                + "\"choices\":[{\"n\":0,\"label\":\"버틴다\"},{\"n\":1,\"label\":\"달아난다\"}]}";
    }

    public static void main(String[] args) {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        long now = System.currentTimeMillis();

        // 그리는 손이 받는 것과 같은 장면 목록을 이 눈도 받아 본다 (배선이 실제로 울리는가)
        AtomicReference<List<WorldBridge.SeojangScene>> heard = new AtomicReference<>(List.of());
        WorldBridge.onSeojang(heard::set);

        // ══════════ ① 서장 미완·쓰는_중 — ★ B-118 의 경주 (토큰이 아직 없다) ══════════
        WorldBridge.ingestSeojang(book(now, writingEntry(me)), now);
        check("① 쓰는_중(토큰 없음)의 몸을 문이 붙든다", WorldBridge.seojangHolds(me));
        check("① 장면이 실제로 배선을 울렸다 (쓰는_중)", heard.get().size() == 1
                && heard.get().get(0).writing() && heard.get().get(0).token() == null);
        check("① 명단에 없는 남의 몸은 붙들지 않는다", !WorldBridge.seojangHolds(other));

        // ══════════ ② 서장 미완·펼침 — 책을 읽는 중 ══════════
        WorldBridge.ingestSeojang(book(now, openEntry(me)), now);
        check("② 책을 읽는 중(펼침·토큰 있음)의 몸을 문이 붙든다", WorldBridge.seojangHolds(me));
        check("② 장면의 토큰이 실렸다", heard.get().size() == 1
                && "tok-1".equals(heard.get().get(0).token()));

        // ══════════ ③ 서장 완료/미대상 — 명단에서 사라졌다 (에필로그 [강호로 나선다]) ══════════
        WorldBridge.ingestSeojang(book(now), now);
        check("③ 명단에서 사라진 몸(출도·구세대)은 보낸다", !WorldBridge.seojangHolds(me));

        // ══════════ ④ 다리 죽음 — 심장 소리(at)가 낡았다. **가두지 않는다** ══════════
        long dead = now - 10 * 60_000L;   // 10분 전 — seojang_stale_seconds(60) 를 한참 넘겼다
        WorldBridge.ingestSeojang(book(dead, writingEntry(me)), dead);
        check("④ 명단에 있어도 심장 소리가 낡으면 보낸다 (무한 대기 금지)",
                !WorldBridge.seojangHolds(me));

        // ══════════ 순수 판정의 모서리 — 그리는 코드와 같은 눈 (seojangHolds 4결 + 경계) ══════════
        long stale = 60_000L;
        check("순수: 미완·신선 → 붙든다", WorldBridge.seojangHolds(true, now, now, stale));
        check("순수: 완료/미대상 → 보낸다", !WorldBridge.seojangHolds(false, now, now, stale));
        check("순수: 미완·죽은 다리 → 보낸다",
                !WorldBridge.seojangHolds(true, now - stale - 1, now, stale));
        check("순수: 경계 — 딱 stale 까지는 산 것이다",
                WorldBridge.seojangHolds(true, now - stale, now, stale));
        check("순수: 책이 한 번도 안 구워졌다(at=0) → 붙들지 않는다",
                !WorldBridge.seojangHolds(true, 0, now, stale));

        // ══════════ 깨진 입력 — 문이 넘어지지 않는가 ══════════
        WorldBridge.ingestSeojang(book(now, writingEntry(me)), now);
        WorldBridge.ingestSeojang("{\"at\":" + now + ",\"scenes\":[{\"mc_uuid\":\"이름이-아니다\","
                + "\"state\":\"쓰는_중\"}]}", now);
        check("깨진 uuid 는 명단에 못 든다 (그리고 옛 명단을 지운다 — 최신이 이긴다)",
                !WorldBridge.seojangHolds(me));
        WorldBridge.ingestSeojang(book(now, writingEntry(me)), now);
        WorldBridge.ingestSeojang("이것은 JSON 이 아니다", now);
        check("통째로 깨진 책은 버린다 — 마지막 성한 명단이 남는다 (넘어지지 않는다)",
                WorldBridge.seojangHolds(me));

        // ★ at 이 안 실렸으면 파일 시각(mtime)이 대타다 — 심장 소리가 0 이 되지 않는다
        WorldBridge.ingestSeojang("{\"scenes\":[" + writingEntry(me) + "]}", now);
        check("at 이 없으면 mtime 이 대타 — 여전히 붙든다", WorldBridge.seojangHolds(me));

        if (!failures.isEmpty()) {
            System.out.println("\n✘ " + failures.size() + "개 실패:");
            failures.forEach(f -> System.out.println("  - " + f));
            System.exit(1);
        }
        System.out.println("\n✔ 서장 게이트 눈 " + passed + "개 — 전부 통과");
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "  ✅ " : "  ❌ ") + name);
        if (ok) {
            passed++;
        } else {
            failures.add(name);
        }
    }

    private SeojangGateSelfTest() {
    }
}
