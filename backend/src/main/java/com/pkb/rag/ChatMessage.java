package com.pkb.rag;

import lombok.Data;

@Data
public class ChatMessage {
    private Long id;
    private Long sessionId;
    private String role;
    private String content;
    /** 引用来源 JSON 数组 */
    private String sources;
    private String createdAt;
}
