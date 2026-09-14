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
    private String createdAt;
    private String updatedAt;
}
