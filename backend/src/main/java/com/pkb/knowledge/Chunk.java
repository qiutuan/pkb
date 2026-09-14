package com.pkb.knowledge;

import lombok.Data;

@Data
public class Chunk {
    private Long id;
    private Long kbId;
    private Long docId;
    private Integer position;
    private String content;
    private String meta;
    private String createdAt;
}
