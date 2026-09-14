package com.pkb.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * 模型提供者：一个 Provider 同时承载聊天模型与 Embedding 模型（二者可分别配置）。
 * apiKey 为输入专用（接收明文，不参与序列化）；apiKeyEnc 为库中密文，对外视图只暴露掩码。
 */
@Data
public class ModelProvider {
    private Long id;
    private String name;
    /** openai_compatible | ollama | anthropic | gemini | local */
    private String providerType;
    private String baseUrl;
    /** 加密后的 API Key（仅内部使用，不对外序列化） */
    @JsonIgnore
    private String apiKeyEnc;
    /** 明文 API Key：仅在创建/更新时接收，编辑时留空表示不修改 */
    private String apiKey;
    private String chatModel;
    private String embeddingModel;
    private Double temperature;
    private Integer maxTokens;
    private Boolean defaultChat;
    private Boolean defaultEmbedding;
    private Boolean enabled;
    private String createdAt;
    private String updatedAt;
}
