package com.pkb.rag;

import lombok.Data;

@Data
public class ChatSession {
    private Long id;
    private String title;
    /** 会话记忆的聊天模型 Provider id（可为空 = 跟随默认） */
    private Long modelProviderId;
    private String createdAt;
    private String updatedAt;
}
