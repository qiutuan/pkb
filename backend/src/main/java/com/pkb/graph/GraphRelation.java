package com.pkb.graph;

import lombok.Data;

@Data
public class GraphRelation {
    private Long id;
    private Long kbId;
    private Long sourceId;
    private Long targetId;
    private String relationType;
    private String description;
    private String createdAt;
}
