package com.pkb.rag;

import com.pkb.model.ModelFactory;
import com.pkb.model.ModelProviderService;
import org.springframework.stereotype.Component;

/**
 * 重排器工厂：none | hybrid | llm。
 */
@Component
public class RerankerFactory {

    private final ModelProviderService providerService;
    private final ModelFactory factory;

    public RerankerFactory(ModelProviderService providerService, ModelFactory factory) {
        this.providerService = providerService;
        this.factory = factory;
    }

    public Reranker get(String mode, Long rerankProviderId) {
        return switch (mode == null ? "hybrid" : mode) {
            case "none" -> new NoneReranker();
            case "llm" -> new LlmReranker(providerService, factory);
            case "rerank_model" -> new RerankModelReranker(providerService, factory, rerankProviderId);
            default -> new HybridReranker();
        };
    }
}
