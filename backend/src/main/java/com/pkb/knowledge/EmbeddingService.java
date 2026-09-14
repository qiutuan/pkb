package com.pkb.knowledge;

import com.pkb.model.ModelFactory;
import com.pkb.model.ModelProvider;
import com.pkb.model.ModelProviderService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化服务：按知识库配置（或全局默认）的 Embedding 模型批量向量化。
 */
@Service
public class EmbeddingService {

    private static final int BATCH = 16;

    private final ModelFactory factory;
    private final ModelProviderService providerService;

    public EmbeddingService(ModelFactory factory, ModelProviderService providerService) {
        this.factory = factory;
        this.providerService = providerService;
    }

    /** 解析知识库使用的 Embedding Provider：知识库独立配置 > 全局默认 */
    public ModelProvider providerFor(KnowledgeBase kb) {
        if (kb.getEmbeddingProviderId() != null) {
            return providerService.requireEnabled(kb.getEmbeddingProviderId());
        }
        return providerService.defaultEmbeddingProvider();
    }

    public float[] embedOne(String text, ModelProvider provider) {
        EmbeddingModel model = factory.embeddingModel(provider);
        return model.embed(text == null ? "" : text).content().vector();
    }

    public List<float[]> embedBatch(List<String> texts, ModelProvider provider) {
        EmbeddingModel model = factory.embeddingModel(provider);
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH) {
            List<String> batch = texts.subList(i, Math.min(i + BATCH, texts.size()));
            List<TextSegment> segments = batch.stream().map(TextSegment::from).toList();
            var resp = model.embedAll(segments);
            resp.content().forEach(e -> out.add(e.vector()));
        }
        return out;
    }
}
