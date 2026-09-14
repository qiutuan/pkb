package com.pkb.rag;

import com.pkb.common.BusinessException;
import com.pkb.model.ModelFactory;
import com.pkb.model.ModelProvider;
import com.pkb.model.ModelProviderService;
import com.pkb.util.JsonUtil;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * LLM 重排：把候选片段交给聊天模型打分（0-10），解析 JSON 后排序。
 * 质量更高但会消耗 token；失败时自动回退到混合重排。
 */
@Slf4j
public class LlmReranker implements Reranker {

    private static final String PROMPT = """
            你是一个检索重排引擎。根据用户问题，为下列候选片段的相关性打分（0-10 的整数，10 最相关）。

            用户问题：%s

            候选片段：
            %s

            只输出 JSON：{"scores":[依次给出每个片段的分数]}
            """;

    private final ModelProviderService providerService;
    private final ModelFactory factory;
    private final HybridReranker fallback = new HybridReranker();

    public LlmReranker(ModelProviderService providerService, ModelFactory factory) {
        this.providerService = providerService;
        this.factory = factory;
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        try {
            ModelProvider provider = providerService.defaultChatProvider();
            ChatModel model = factory.chatModel(provider);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < candidates.size(); i++) {
                RetrievedChunk c = candidates.get(i);
                String content = c.content().length() > 300 ? c.content().substring(0, 300) : c.content();
                sb.append('[').append(i + 1).append("] ").append(content.replace('\n', ' ')).append('\n');
            }
            String resp = model.chat(ChatRequest.builder().messages(List.of(
                            SystemMessage.from("你是检索重排引擎。"),
                            UserMessage.from(PROMPT.formatted(query, sb))))
                    .build()).aiMessage().text();
            String json = JsonUtil.extractFirstJsonObject(resp);
            if (json == null) {
                throw new BusinessException("重排输出无 JSON");
            }
            Map<String, Object> map = JsonUtil.fromJson(json, Map.class);
            Object raw = map.get("scores");
            if (!(raw instanceof List<?> scores) || scores.size() != candidates.size()) {
                throw new BusinessException("重排分数数量不匹配");
            }
            List<Scored> scored = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                double s = Double.parseDouble(String.valueOf(scores.get(i)));
                scored.add(new Scored(candidates.get(i), s / 10.0));
            }
            scored.sort(Comparator.comparingDouble(Scored::score).reversed());
            List<RetrievedChunk> out = new ArrayList<>();
            for (Scored s : scored) {
                if (topK > 0 && out.size() >= topK) {
                    break;
                }
                out.add(new RetrievedChunk(s.chunk().chunkId(), s.chunk().kbId(), s.chunk().docId(),
                        s.chunk().docName(), s.chunk().position(), s.chunk().content(), s.score(), s.chunk().source()));
            }
            return out;
        } catch (Exception e) {
            log.warn("LLM 重排失败，回退混合重排: {}", e.getMessage());
            return fallback.rerank(query, candidates, topK);
        }
    }

    private record Scored(RetrievedChunk chunk, double score) {
    }
}
