package com.pkb.knowledge;

import lombok.Data;

@Data
public class Category {
    private Long id;
    private Long parentId;
    private String name;
    private String createdAt;
}
