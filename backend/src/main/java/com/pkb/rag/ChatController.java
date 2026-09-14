package com.pkb.rag;

import com.pkb.common.ApiResponse;
import com.pkb.common.BusinessException;
import com.pkb.dao.ChatDao;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ChatDao chatDao;

    public ChatController(ChatService chatService, ChatDao chatDao) {
        this.chatService = chatService;
        this.chatDao = chatDao;
    }

    @GetMapping("/sessions")
    public ApiResponse<List<ChatSession>> sessions() {
        return ApiResponse.ok(chatDao.listSessions());
    }

    @PostMapping("/sessions")
    public ApiResponse<ChatSession> createSession(@RequestBody(required = false) Map<String, String> body) {
        String title = body == null ? null : body.get("title");
        long id = chatDao.insertSession(title);
        return ApiResponse.ok(chatDao.findSession(id));
    }

    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Void> deleteSession(@PathVariable long id) {
        if (chatDao.findSession(id) == null) {
            throw new BusinessException("会话不存在");
        }
        chatDao.deleteSession(id);
        return ApiResponse.ok();
    }

    @PostMapping("/sessions/{id}/clear")
    public ApiResponse<Void> clear(@PathVariable long id) {
        if (chatDao.findSession(id) == null) {
            throw new BusinessException("会话不存在");
        }
        chatDao.clearMessages(id);
        return ApiResponse.ok();
    }

    @GetMapping("/sessions/{id}/messages")
    public ApiResponse<List<ChatMessage>> messages(@PathVariable long id) {
        return ApiResponse.ok(chatDao.messages(id));
    }

    /** 流式对话（SSE） */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatStreamRequest req) {
        return chatService.stream(req);
    }
}
