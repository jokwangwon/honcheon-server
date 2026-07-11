package com.honcheon.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 턴 렌더러 결선 — llm.yml roles.turn_renderer (기본 claude-haiku-4-5).
 * 대원칙: 엔진이 사실을 계산하고 LLM 은 서사만 렌더한다 — 실패·부재 시 폴백 템플릿(Narration).
 * ANTHROPIC_API_KEY 가 없으면 조용히 비활성 (알파 동작 그대로) — 봇은 LLM 없이도 돈다.
 */
final class LlmRenderer {

    /** 생성 7계의 렌더러 축약 — llm.yml generation_principles (수치 은닉·등록제 명사·길이 예산·문체) */
    private static final String SYSTEM = """
            너는 한국어 무협 텍스트 RPG의 서사 렌더러다. 규칙:
            1. 주어진 사실(판정 결과·상황)을 절대 바꾸지 않는다 — 성공을 정하는 것은 엔진이고, 너는 들었을 뿐이다.
            2. 서사에 숫자를 쓰지 않는다 — 마진·주사위·가격 언급 금지, 결과의 무게는 문장으로.
            3. 새 인명·지명·무공명을 지어내지 않는다 — 주어진 이름만 쓴다. 필요하면 무명으로 서술.
            4. 길이는 300~500자, 한 문단 산문. 선택지·목록·머리말 없이 서사 본문만.
            5. 문체는 한국어 무협 — 건조하고 즉물적으로, 형용사보다 사물과 동작으로.
            기준 서사가 주어지면 그 사실 범위 안에서만 살을 붙여라.""";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(LlmRenderer.class);
    private static final long WARN_COOLDOWN_MS = 60_000;

    private final String apiKey;
    private final String model;
    // 로컬 LLM (llm.yml local_provider) — OpenAI 호환 엔드포인트 (Ollama 등). 설정 시 로컬 우선
    private final String localUrl;
    private final String localModel;
    private final HttpClient http;
    // F25 — 폴백 관측성: 실패는 반드시 한 줄 WARN (스팸 방지 쿨다운 1분 + 억제 건수 보고)
    private final AtomicLong lastWarnAt = new AtomicLong();
    private final AtomicLong suppressed = new AtomicLong();

    LlmRenderer(String model) {
        this.apiKey = System.getenv("ANTHROPIC_API_KEY");
        this.model = model;
        this.localUrl = System.getenv("HONCHEON_LLM_URL");
        this.localModel = System.getenv().getOrDefault("HONCHEON_LLM_MODEL", "exaone3.5:7.8b");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    }

    private boolean localEnabled() {
        return localUrl != null && !localUrl.isBlank();
    }

    boolean enabled() {
        return localEnabled() || (apiKey != null && !apiKey.isBlank());
    }

    /** 기동 로그용 — 어느 공급자로 렌더하는가 */
    String providerLabel() {
        if (localEnabled()) {
            return "로컬 (" + localUrl + " · " + localModel + ")";
        }
        return apiKey != null && !apiKey.isBlank() ? "Claude API (" + model + ")" : "비활성 — 폴백 템플릿";
    }

    /**
     * 사실 → 서사. 비활성·오류·타임아웃·과대 응답 전부 fallback 으로 수렴한다.
     * 반환 future 는 절대 예외로 완료되지 않는다 — 호출부는 서사를 받기만 하면 된다.
     */
    CompletableFuture<String> render(String facts, String fallback) {
        if (!enabled()) {
            return CompletableFuture.completedFuture(fallback);
        }
        if (localEnabled()) {
            return renderLocal(facts, fallback);
        }
        try {
            ObjectNode body = JSON.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", 700);
            body.put("system", SYSTEM);
            body.putArray("messages").addObject()
                    .put("role", "user")
                    .put("content", facts);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/messages"))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(resp -> extract(resp, fallback))
                    .exceptionally(e -> fallbackWith("호출 예외/타임아웃: " + rootMessage(e), fallback));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                    fallbackWith("요청 구성 실패: " + rootMessage(e), fallback));
        }
    }

    /** 로컬 LLM — OpenAI 호환 /chat/completions (Ollama·llama.cpp·vLLM). 실패는 전부 폴백 수렴 */
    private CompletableFuture<String> renderLocal(String facts, String fallback) {
        try {
            ObjectNode body = JSON.createObjectNode();
            body.put("model", localModel);
            body.put("max_tokens", 700);
            var messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", SYSTEM);
            messages.addObject().put("role", "user").put("content", facts);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(localUrl.replaceAll("/+$", "") + "/chat/completions"))
                    .header("content-type", "application/json")
                    .timeout(Duration.ofSeconds(25))   // 로컬은 하드웨어 편차가 크다 (llm.yml hardware_note)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(resp -> extractLocal(resp, fallback))
                    .exceptionally(e -> fallbackWith("로컬 호출 예외/타임아웃: " + rootMessage(e), fallback));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                    fallbackWith("로컬 요청 구성 실패: " + rootMessage(e), fallback));
        }
    }

    private String extractLocal(HttpResponse<String> resp, String fallback) {
        try {
            if (resp.statusCode() != 200) {
                String snippet = resp.body() == null ? ""
                        : resp.body().substring(0, Math.min(160, resp.body().length())).replaceAll("\\s+", " ");
                return fallbackWith("로컬 HTTP " + resp.statusCode() + " — " + snippet, fallback);
            }
            JsonNode choices = JSON.readTree(resp.body()).path("choices");
            String text = choices.isArray() && choices.size() > 0
                    ? choices.get(0).path("message").path("content").asText("") : "";
            if (text.isBlank() || text.length() > 1500) {
                return fallbackWith("로컬 응답 길이 예산 밖 (" + text.length() + "자)", fallback);
            }
            return text.strip();
        } catch (Exception e) {
            return fallbackWith("로컬 응답 파싱 실패: " + rootMessage(e), fallback);
        }
    }

    private String extract(HttpResponse<String> resp, String fallback) {
        try {
            if (resp.statusCode() != 200) {
                // 크레딧 소진(400)·인증(401)·과부하(529) 등 — 사유를 남겨야 폴백과 구분된다 (F25)
                String snippet = resp.body() == null ? ""
                        : resp.body().substring(0, Math.min(160, resp.body().length())).replaceAll("\\s+", " ");
                return fallbackWith("HTTP " + resp.statusCode() + " — " + snippet, fallback);
            }
            JsonNode content = JSON.readTree(resp.body()).path("content");
            String text = content.isArray() && content.size() > 0
                    ? content.get(0).path("text").asText("") : "";
            // 길이 예산 밖(빈 응답·폭주)은 폴백 — embed description 한계도 방어
            if (text.isBlank() || text.length() > 1500) {
                return fallbackWith("응답 길이 예산 밖 (" + text.length() + "자)", fallback);
            }
            return text.strip();
        } catch (Exception e) {
            return fallbackWith("응답 파싱 실패: " + rootMessage(e), fallback);
        }
    }

    /** 폴백 발생 = 반드시 흔적 — 1분 쿨다운, 억제분은 다음 WARN에 합산 보고 (F25) */
    private String fallbackWith(String reason, String fallback) {
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last >= WARN_COOLDOWN_MS && lastWarnAt.compareAndSet(last, now)) {
            long missed = suppressed.getAndSet(0);
            LOG.warn("LLM 렌더 폴백 — {}{}", reason,
                    missed > 0 ? " (직전 1분간 " + missed + "건 추가 억제)" : "");
        } else {
            suppressed.incrementAndGet();
        }
        return fallback;
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + (cur.getMessage() == null ? "" : ": " + cur.getMessage());
    }
}
