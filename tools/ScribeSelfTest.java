package com.honcheon.bot;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 눈 — 「배는 한 명씩 탄다」가 <b>정말로</b> 지켜지는가.
 *
 * 실측(2026-07-13): 서장 1건 22.4초 · **4건 동시 89.5초** (GPU 는 하나다).
 * 봇은 전에 CompletableFuture 로 **동시에 던졌다** — 넷이 들면 전원이 타임아웃(25초)에 걸려
 * **아무도 글을 못 받았다.** Scribe 가 줄을 세운다. 그 줄이 진짜인지 여기서 잰다.
 *
 * 가짜 Ollama 를 세우고 **동시 요청 수를 센다.** 2 이상이 관측되면 직렬화가 깨진 것이다.
 */
public class ScribeSelfTest {

    static final AtomicInteger inFlight = new AtomicInteger();
    static final AtomicInteger maxConcurrent = new AtomicInteger();
    static final AtomicInteger served = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(18099), 0);
        server.setExecutor(Executors.newFixedThreadPool(16));   // ★ 서버는 병렬을 허용한다
        server.createContext("/v1/chat/completions", ex -> {
            int now = inFlight.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(120);   // "생성" 시간 (실물은 22.4초)
                String body = "{\"choices\":[{\"message\":{\"content\":"
                        + "\"붓이 그린 글 " + served.incrementAndGet() + " — 그날 밤은 길었다.\"}}]}";
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("content-type", "application/json");
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                inFlight.decrementAndGet();
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        System.out.println("가짜 나루 — http://localhost:" + port + "/v1  (동시 요청을 센다)\n");

        // ★ LlmRenderer 는 env 에서 URL 을 읽는다 — 그래서 이 시험은 env 를 세우고 돌려야 한다
        if (System.getenv("HONCHEON_LLM_URL") == null) {
            System.out.println("☠ HONCHEON_LLM_URL 이 없다 — 이 시험은 env 와 함께 돌려야 한다");
            System.exit(2);
        }

        LlmRenderer renderer = new LlmRenderer("test", 10, 10, 2, 700);
        System.out.println("renderer.enabled() = " + renderer.enabled()
                + "  · 공급자: " + renderer.providerLabel());

        Scribe scribe = new Scribe(renderer, 12);

        // ─── 여덟 사람이 **동시에** 서장에 든다 ───
        int n = 8;
        List<Integer> ferry = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Scribe.Written>> all = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            all.add(scribe.write("장면 " + i, "옛 필사본 " + i, ferry::add));
        }
        CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).join();
        long ms = System.currentTimeMillis() - t0;

        int fell = 0;
        for (CompletableFuture<Scribe.Written> f : all) {
            if (f.join().fallback()) {
                fell++;
            }
        }

        System.out.println("\n─ 결과 ─");
        System.out.println("  던진 사람        : " + n);
        System.out.println("  가짜 나루가 본 최대 동시 요청: " + maxConcurrent.get()
                + "   ← ★ 1 이어야 한다");
        System.out.println("  붓이 그린 글     : " + served.get());
        System.out.println("  폴백으로 떨어진 것: " + fell);
        System.out.println("  걸린 시간        : " + ms + "ms (순차라면 ≥ " + (n * 120) + "ms)");
        System.out.println("  사공이 말한 차례 : " + ferry + "   ← ★ 0,1,2,... 로 늘어야 한다");

        boolean ok = true;
        if (maxConcurrent.get() != 1) {
            System.out.println("\n☠ 직렬화가 깨졌다 — 동시 " + maxConcurrent.get()
                    + "건이 나갔다. 「배는 한 명씩」이 거짓이다.");
            ok = false;
        }
        if (served.get() != n || fell != 0) {
            System.out.println("\n☠ 글을 못 받은 사람이 있다 (그린 " + served.get()
                    + " · 폴백 " + fell + ")");
            ok = false;
        }
        if (ms < (n - 1) * 120L) {
            System.out.println("\n☠ 너무 빠르다 — 겹쳐 돌았다는 뜻이다");
            ok = false;
        }
        boolean rising = true;
        for (int i = 0; i < ferry.size(); i++) {
            if (ferry.get(i) < 0) {
                rising = false;
            }
        }
        if (!rising) {
            System.out.println("\n☠ 사공이 차례를 잘못 말했다");
            ok = false;
        }
        // ═══ ★ 일부러 어겨서 시험한다 — **옛 길**(Scribe 없이 곧장 던진다) ═══
        //   이것이 2026-07-13 이전의 봇이다. 넷이 들면 89.5초가 되던 그 코드.
        maxConcurrent.set(0);
        served.set(0);
        System.out.println("\n─ ★ 옛 길 (Scribe 를 우회해 renderer 를 곧장 8번) ─");
        List<CompletableFuture<String>> old = new ArrayList<>();
        long t1 = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            old.add(renderer.render("장면 " + i, "옛 필사본 " + i));
        }
        CompletableFuture.allOf(old.toArray(new CompletableFuture[0])).join();
        long ms2 = System.currentTimeMillis() - t1;
        System.out.println("  가짜 나루가 본 최대 동시 요청: " + maxConcurrent.get()
                + "   ← ★ 1 보다 크면 그것이 옛 병이다");
        System.out.println("  걸린 시간: " + ms2 + "ms");
        if (maxConcurrent.get() > 1) {
            System.out.println("  ☠ 옛 길은 " + maxConcurrent.get()
                    + "건을 **동시에** 던진다 — GPU 하나에 여덟이 몰린다.");
            System.out.println("     실물에서는 이것이 89.5초이고, 옛 타임아웃 25초에 **전원이 걸렸다.**");
            System.out.println("  ✓ 눈이 옛 병을 잡아낸다 (즉 이 눈은 진짜 눈이다)");
        } else {
            System.out.println("  ☠ 눈이 옛 병을 못 잡았다 — 이 시험은 믿을 수 없다");
            ok = false;
        }

        server.stop(0);
        scribe.stop();
        System.out.println(ok
                ? "\n눈이 조용하다 — 배는 한 명씩 탔고, 여덟 사람 모두 글을 받았다."
                : "\n눈이 운다.");
        System.exit(ok ? 0 : 1);
    }
}
