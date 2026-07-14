package com.honcheon.bot;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 붓(筆) — <b>서사를 그리는 단 하나의 차선.</b> 「배는 한 명씩 탄다.」
 *
 * <p><b>★ 왜 이것이 있는가 — 실측이 시켰다 (2026-07-13).</b>
 * qwen3-30b / GB10 / 45.3GB 전부 VRAM:
 *
 * <pre>
 *   실제 서장(700토큰)  1건 ......... 22.4초
 *   실제 서장(700토큰)  4건 동시 .... 89.5초   ← 각자가 4배 느려진다
 *   짧은 글(120토큰)   4건 동시 .... 20.1초   ← 순차(8.8초)보다 **더 느리다**
 * </pre>
 *
 * <p>봇은 {@code CompletableFuture} + {@code sendAsync} 로 <b>동시에 던졌다.</b> 줄을 세우지 않았다.
 * 그런데 <b>GPU 는 하나다.</b> 넷을 동시에 던지면 넷이 서로를 밀어내며 각자 4배 느려지고 —
 * 옛 타임아웃(25초)에 <b>전원이 걸려 아무도 글을 못 받는다.</b> 병렬은 이득이 아니라 <b>경합</b>이었다.
 *
 * <p><b>줄을 세우면 첫 사람은 22초에 받는다.</b> 그것이 이 클래스다: 동시 <b>1건</b>.
 * 처리량은 같고(GPU 는 어차피 하나다) <b>완료 시각만 앞당겨진다</b> — 그리고 타임아웃이 살아난다.
 *
 * <p><b>★ 줄은 UI 가 아니라 이야기다.</b> 기다리는 사람에게 "로딩 중"이라고 하지 않는다 —
 * <b>나루의 사공은 한 번에 한 사람만 태운다.</b> 앞에 몇 사람인지 세계의 말로 말해 준다
 * ({@code seojang.yml ferry}). <b>침묵 금지</b> — 말없이 늦는 것이 이 저장소의 고질병이었다.
 *
 * <p><b>★ 그리고 미리 쓴다.</b> 가장 좋은 기다림은 <b>이미 끝난 기다림</b>이다. 사람이 나루를 걷고
 * 발판을 밟는 그 몇 분 동안 서장을 써 두면 22.4초가 <b>0초로 보인다</b> — 글을 한 자도 줄이지 않고.
 * 미리 쓰기의 방아쇠와 무효화 규칙은 {@code seojang.yml prerender} 가 정한다.
 */
final class Scribe {

    /**
     * 단일 차선 — <b>여기가 자물쇠다.</b> 스레드가 하나이므로 동시 실행이 <b>구조적으로</b> 1건이다
     * (세마포어처럼 "지키기로 약속한" 것이 아니라, 두 번째 배가 <b>뜰 수가 없다</b>).
     */
    private final ExecutorService lane = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "honcheon-scribe");
        t.setDaemon(true);
        return t;
    });

    /** 지금 줄에 선 사람 수 (그리는 중인 1건 포함) — 사람에게 차례를 말해 주는 값 */
    private final AtomicInteger queued = new AtomicInteger();

    private final LlmRenderer renderer;
    private final int queueMax;

    Scribe(LlmRenderer renderer, int queueMax) {
        this.renderer = renderer;
        this.queueMax = Math.max(1, queueMax);
    }

    /** 지금 앞에 몇 사람이 있는가 (나를 넣기 전 기준) */
    int waiting() {
        return queued.get();
    }

    /** 붓이 놀고 있는가 — 미리 쓰기를 지금 밀어 넣어도 되는가의 눈 */
    boolean idle() {
        return queued.get() == 0;
    }

    /**
     * 한 건을 줄에 세운다. <b>반환 future 는 절대 예외로 완료되지 않는다</b> — 무엇이 잘못돼도 폴백이 온다.
     *
     * <p><b>줄이 너무 길면 줄을 세우지 않는다</b> ({@code queue_max}) — 무한정 기다리게 하는 것보다
     * <b>지금 폴백을 주는 것</b>이 낫다. 폴백 문장도 진짜 서사다 (등록부가 쓴 글이다).
     *
     * @param onQueued 줄에 섰을 때 그 자리(앞선 사람 수)를 알려 준다 — <b>침묵 금지</b>.
     *                 0 이면 바로 붓이 든다 (아무 말도 할 필요 없다)
     * @return {@link Written} — 글과, <b>그 글이 폴백인가</b> (사람에게도 남는다)
     */
    CompletableFuture<Written> write(String facts, String fallback, java.util.function.IntConsumer onQueued) {
        if (!renderer.enabled()) {
            return CompletableFuture.completedFuture(new Written(fallback, true, "LLM 비활성"));
        }
        int ahead = queued.getAndIncrement();
        if (ahead >= queueMax) {
            // 줄이 넘쳤다 — 세우지 않는다. 기다리게 하느니 지금 준다
            queued.decrementAndGet();
            return CompletableFuture.completedFuture(
                    new Written(fallback, true, "줄이 넘쳤다 (" + ahead + "명 대기)"));
        }
        if (onQueued != null) {
            onQueued.accept(ahead);   // ★ 앞에 몇 사람인가 — 그것을 말해 준다
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    // ★ join — 이 스레드가 차선이다. 여기서 막혀 있는 동안 다른 배는 뜨지 못한다
                    String text = renderer.render(facts, fallback).join();
                    boolean fell = text == null || text.equals(fallback);
                    return new Written(fell ? fallback : text, fell, fell ? "렌더 폴백" : null);
                } catch (Exception e) {
                    return new Written(fallback, true, "붓이 부러졌다: " + e.getClass().getSimpleName());
                } finally {
                    queued.decrementAndGet();
                }
            }, lane);
        } catch (RejectedExecutionException e) {
            queued.decrementAndGet();
            return CompletableFuture.completedFuture(new Written(fallback, true, "붓이 닫혔다"));
        }
    }

    /**
     * ★ 대화도 같은 배를 탄다 (B-016) — {@code /혼천 대화} 가 렌더러를 직접 부르면 서장 lane 과
     * <b>같은 GPU 를 다툰다</b> (위 실측: 동시에 던지면 각자 4배 느려진다 — 배는 한 명씩 탄다).
     *
     * <p>규칙은 {@link #write} 와 <b>동형</b>이고, 다른 것은 시스템 프롬프트뿐이다
     * (서장 = 렌더러의 고정 SYSTEM, 대화 = NPC 페르소나 — {@link LlmRenderer#chat}).
     * 폴백 수렴·줄 넘침·차례 알림·<b>반환 future 는 절대 예외로 완료되지 않는다</b> — 전부 같다.
     */
    CompletableFuture<Written> chat(String system, String user, String fallback,
                                    java.util.function.IntConsumer onQueued) {
        if (!renderer.enabled()) {
            return CompletableFuture.completedFuture(new Written(fallback, true, "LLM 비활성"));
        }
        int ahead = queued.getAndIncrement();
        if (ahead >= queueMax) {
            // 줄이 넘쳤다 — 세우지 않는다. 기다리게 하느니 지금 준다 (write 와 같은 규칙)
            queued.decrementAndGet();
            return CompletableFuture.completedFuture(
                    new Written(fallback, true, "줄이 넘쳤다 (" + ahead + "명 대기)"));
        }
        if (onQueued != null) {
            onQueued.accept(ahead);   // ★ 앞에 몇 사람인가 — 대화에서도 침묵 금지
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    // ★ join — 서장과 **같은 스레드**가 차선이다. 대화가 그리는 동안 서장의 배도
                    //   뜨지 못한다 (그것이 요점이다 — GPU 는 하나니까)
                    String text = renderer.chat(system, user, fallback).join();
                    boolean fell = text == null || text.equals(fallback);
                    return new Written(fell ? fallback : text, fell, fell ? "렌더 폴백" : null);
                } catch (Exception e) {
                    return new Written(fallback, true, "붓이 부러졌다: " + e.getClass().getSimpleName());
                } finally {
                    queued.decrementAndGet();
                }
            }, lane);
        } catch (RejectedExecutionException e) {
            queued.decrementAndGet();
            return CompletableFuture.completedFuture(new Written(fallback, true, "붓이 닫혔다"));
        }
    }

    void stop() {
        lane.shutdownNow();
    }

    /**
     * 그려진 글 한 장. <b>{@code fallback} 은 숨기지 않는다</b> — 책의 간기(刊記)로 남고
     * 봇의 장부에도 남는다 (llm.yml runtime.fallback_visible_to_player).
     *
     * @param reason 폴백이면 왜인가 (운영자용 — 사람에게는 등록부의 한 줄만 보인다)
     */
    record Written(String text, boolean fallback, String reason) {
    }
}
