package com.pkb.graph;

import lombok.Data;

@Data
public class GraphEntity {
    private Long id;
    private Long kbId;
    private String name;
    private String entityType;
    private String description;
    private String createdAt;
}
