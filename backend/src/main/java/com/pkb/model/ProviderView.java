package com.pkb.model;

/**
 * Provider 对外视图（API Key 仅返回掩码）。
 */
public record ProviderView(
        Long id,
        String name,
        String providerType,
        String baseUrl,
        String chatModel,
        String embeddingModel,
        Double temperature,
        Integer maxTokens,
        boolean defaultChat,
        boolean defaultEmbedding,
        boolean enabled,
        String capabilities,
        String apiKeyMasked,
        boolean hasApiKey,
        String createdAt,
        String updatedAt) {
}
