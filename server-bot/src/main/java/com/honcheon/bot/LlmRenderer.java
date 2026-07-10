package com.honcheon.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

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

    private final String apiKey;
    private final String model;
    private final HttpClient http;

    LlmRenderer(String model) {
        this.apiKey = System.getenv("ANTHROPIC_API_KEY");
        this.model = model;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    }

    boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 사실 → 서사. 비활성·오류·타임아웃·과대 응답 전부 fallback 으로 수렴한다.
     * 반환 future 는 절대 예외로 완료되지 않는다 — 호출부는 서사를 받기만 하면 된다.
     */
    CompletableFuture<String> render(String facts, String fallback) {
        if (!enabled()) {
            return CompletableFuture.completedFuture(fallback);
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
                    .exceptionally(e -> fallback);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(fallback);
        }
    }

    private String extract(HttpResponse<String> resp, String fallback) {
        try {
            if (resp.statusCode() != 200) {
                return fallback;
            }
            JsonNode content = JSON.readTree(resp.body()).path("content");
            String text = content.isArray() && content.size() > 0
                    ? content.get(0).path("text").asText("") : "";
            // 길이 예산 밖(빈 응답·폭주)은 폴백 — embed description 한계도 방어
            return (text.isBlank() || text.length() > 1500) ? fallback : text.strip();
        } catch (Exception e) {
            return fallback;
        }
    }
}
