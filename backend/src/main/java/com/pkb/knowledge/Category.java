package com.pkb.knowledge;

import lombok.Data;

@Data
public class Category {
    private Long id;
    private Long parentId;
    private String name;
    /** 同级排序（前端拖拽排序） */
    private Integer sortOrder;
    private String createdAt;
}
