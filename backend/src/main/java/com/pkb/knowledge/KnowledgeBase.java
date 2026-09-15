package com.pkb.knowledge;

import lombok.Data;

@Data
public class KnowledgeBase {
    private Long id;
    private Long categoryId;
    private String name;
    private String description;
    /** 独立 Embedding 模型；为空则使用全局默认 */
    private Long embeddingProviderId;
    private String chunkStrategy;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Boolean multimodal;
    private Boolean graphEnabled;
    /** Contextual 模式：入库时由 LLM 为每个分块生成文档上下文头并拼入向量文本（一次性入库成本） */
    private Boolean contextual;
    private String createdAt;
    private String updatedAt;
}
