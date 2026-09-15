package com.pkb.rag;

import com.pkb.common.BusinessException;
import com.pkb.model.ModelFactory;
import com.pkb.model.ModelProvider;
import com.pkb.model.ModelProviderService;
import com.pkb.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 外部 Rerank 模型重排：调用 OpenAI 协议兼容的 /v1/rerank 端点（bge-reranker / Cohere 类服务）。
 * 调用失败时回退到内置混合重排，保证检索链路不中断。
 */
@Slf4j
public class RerankModelReranker implements Reranker {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final ModelProviderService providerService;
    private final ModelFactory factory;
    private final Long providerId;

    public RerankModelReranker(ModelProviderService providerService, ModelFactory factory, Long providerId) {
        this.providerService = providerService;
        this.factory = factory;
        this.providerId = providerId;
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        ModelProvider provider = resolveProvider();
        if (provider == null) {
            log.warn("未配置可用的 Rerank 模型，回退混合重排");
            return new HybridReranker().rerank(query, candidates, topK);
        }
        try {
            String base = ModelFactory.normalizeOpenAiUrl(provider.getBaseUrl());
            List<String> docs = candidates.stream().map(RetrievedChunk::content).toList();
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", provider.getChatModel());
            body.put("query", query);
            body.put("documents", docs);
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/rerank"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body)));
            String key = factory.decryptKey(provider);
            if (key != null && !key.isBlank()) {
                rb.header("Authorization", "Bearer " + key);
            }
            HttpResponse<String> resp = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("Rerank 服务返回 {}：{}", resp.statusCode(), truncate(resp.body()));
                return new HybridReranker().rerank(query, candidates, topK);
            }
            Map<String, Object> parsed = JsonUtil.fromJson(resp.body(), Map.class);
            Object resultsObj = parsed.get("results");
            if (!(resultsObj instanceof List<?> results) || results.isEmpty()) {
                log.warn("Rerank 响应无 results 字段，回退混合重排");
                return new HybridReranker().rerank(query, candidates, topK);
            }
            // index + relevance_score 按分降序
            List<double[]> scored = new ArrayList<>();
            for (Object o : results) {
                if (o instanceof Map<?, ?> m) {
                    int idx = ((Number) m.get("index")).intValue();
                    double s = m.get("relevance_score") instanceof Number n ? n.doubleValue() : 0;
                    if (idx >= 0 && idx < candidates.size()) {
                        scored.add(new double[]{idx, s});
                    }
                }
            }
            scored.sort((a, b) -> Double.compare(b[1], a[1]));
            List<RetrievedChunk> out = new ArrayList<>();
            for (double[] pair : scored) {
                if (out.size() >= topK) {
                    break;
                }
                RetrievedChunk c = candidates.get((int) pair[0]);
                out.add(new RetrievedChunk(c.chunkId(), c.kbId(), c.docId(), c.docName(), c.position(),
                        c.content(), pair[1], "rerank"));
            }
            return out;
        } catch (Exception e) {
            log.warn("Rerank 调用失败，回退混合重排: {}", e.getMessage());
            return new HybridReranker().rerank(query, candidates, topK);
        }
    }

    private ModelProvider resolveProvider() {
        try {
            if (providerId != null && providerId > 0) {
                return providerService.requireEnabled(providerId);
            }
            return providerService.defaultRerankProvider();
        } catch (BusinessException e) {
            return null;
        }
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 200 ? s : s.substring(0, 200);
    }
}
