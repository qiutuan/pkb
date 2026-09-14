package com.pkb.rag;

import com.pkb.common.BusinessException;
import com.pkb.dao.ChatDao;
import com.pkb.model.ModelFactory;
import com.pkb.model.ModelProvider;
import com.pkb.model.ModelProviderService;
import com.pkb.settings.SettingsService;
import com.pkb.util.JsonUtil;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话服务：检索 → 构建上下文 → 流式生成（SSE）→ 引用解析与历史持久化。
 */
@Slf4j
@Service
public class ChatService {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)\\]");

    private final RetrievalService retrievalService;
    private final SettingsService settings;
    private final ModelProviderService providerService;
    private final ModelFactory factory;
    private final ChatDao chatDao;
    private final ThreadPoolTaskExecutor executor;

    public ChatService(RetrievalService retrievalService, SettingsService settings,
                       ModelProviderService providerService, ModelFactory factory, ChatDao chatDao,
                       @Qualifier("chatExecutor") ThreadPoolTaskExecutor executor) {
        this.retrievalService = retrievalService;
        this.settings = settings;
        this.providerService = providerService;
        this.factory = factory;
        this.chatDao = chatDao;
        this.executor = executor;
    }

    public SseEmitter stream(ChatStreamRequest req) {
        SseEmitter emitter = new SseEmitter(0L);
        executor.execute(() -> run(emitter, req));
        return emitter;
    }

    private void run(SseEmitter emitter, ChatStreamRequest req) {
        boolean[] finished = {false};
        try {
            if (req.query() == null || req.query().isBlank()) {
                throw new BusinessException("问题内容为空");
            }
            // 会话
            long sessionId;
            if (req.sessionId() == null || req.sessionId() <= 0) {
                sessionId = chatDao.insertSession(null);
            } else {
                sessionId = req.sessionId();
                if (chatDao.findSession(sessionId) == null) {
                    throw new BusinessException("会话不存在");
                }
            }

            // 检索
            List<RetrievedChunk> chunks = retrievalService.retrieve(new RetrieveRequest(
                    req.kbIds(), req.query(), req.topK(), req.minScore(), req.rerank(), req.graphRag()));

            // 保存用户消息（历史仅持久化原始问题，上下文在每次请求时重组）
            chatDao.insertMessage(sessionId, "user", req.query(), "[]");
            ChatSession session = chatDao.findSession(sessionId);
            if (session != null && (session.getTitle() == null || "新对话".equals(session.getTitle()))) {
                String t = req.query().trim();
                chatDao.updateSessionTitle(sessionId, t.length() > 20 ? t.substring(0, 20) : t);
            }

            // 构建消息
            List<dev.langchain4j.data.message.ChatMessage> msgs = new ArrayList<>();
            msgs.add(SystemMessage.from(settings.systemPrompt()));
            if (req.graphRag() == null || req.graphRag()) {
                msgs.add(SystemMessage.from(settings.graphPrompt()));
            }
            List<ChatMessage> history = chatDao.messages(sessionId);
            int limit = settings.historyLimit();
            // 去掉刚保存的当前用户消息，取最近 N 轮
            if (!history.isEmpty()) {
                history = history.subList(0, history.size() - 1);
            }
            if (history.size() > limit * 2) {
                history = history.subList(history.size() - limit * 2, history.size());
            }
            for (ChatMessage h : history) {
                if ("user".equals(h.getRole())) {
                    msgs.add(UserMessage.from(h.getContent()));
                } else if ("assistant".equals(h.getRole())) {
                    msgs.add(AiMessage.from(h.getContent()));
                }
            }
            String context = buildContext(chunks, req.query());
            UserMessage userMsg;
            if (req.imageBase64() != null && !req.imageBase64().isBlank()) {
                String mime = req.imageMime() == null || req.imageMime().isBlank() ? "image/png" : req.imageMime();
                userMsg = UserMessage.from(TextContent.from(context), ImageContent.from(req.imageBase64(), mime));
            } else {
                userMsg = UserMessage.from(context);
            }
            msgs.add(userMsg);

            // 流式生成
            ModelProvider provider = providerService.defaultChatProvider();
            StreamingChatModel model = factory.streamingModel(provider);
            StringBuilder acc = new StringBuilder();
            model.chat(ChatRequest.builder().messages(msgs).build(), new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partial) {
                    if (partial != null && !partial.isEmpty()) {
                        acc.append(partial);
                        send(emitter, finished, Map.of("type", "delta", "content", partial));
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse resp) {
                    if (finished[0]) {
                        return;
                    }
                    finished[0] = true;
                    String text = acc.toString();
                    List<Map<String, Object>> sources = citations(text, chunks);
                    chatDao.insertMessage(sessionId, "assistant", text, JsonUtil.toJson(sources));
                    chatDao.touchSession(sessionId);
                    send(emitter, finished, Map.of("type", "done", "sessionId", sessionId,
                            "content", text, "sources", sources));
                    emitter.complete();
                }

                @Override
                public void onError(Throwable err) {
                    if (finished[0]) {
                        return;
                    }
                    finished[0] = true;
                    send(emitter, finished, Map.of("type", "error",
                            "message", err.getMessage() == null ? "生成失败" : err.getMessage()));
                    emitter.complete();
                }
            });
        } catch (Exception e) {
            send(emitter, finished, Map.of("type", "error",
                    "message", e.getMessage() == null ? "请求失败" : e.getMessage()));
            emitter.complete();
        }
    }

    private String buildContext(List<RetrievedChunk> chunks, String query) {
        StringBuilder sb = new StringBuilder();
        if (!chunks.isEmpty()) {
            sb.append("【参考资料】\n");
            for (int i = 0; i < chunks.size(); i++) {
                RetrievedChunk c = chunks.get(i);
                sb.append('[').append(i + 1).append("] 来源：").append(c.docName())
                        .append("（第").append(c.position() + 1).append("段）");
                if ("graph".equals(c.source()) || "both".equals(c.source())) {
                    sb.append("【图谱召回】");
                }
                sb.append('\n').append(c.content()).append("\n\n");
            }
            sb.append("【问题】\n").append(query);
        } else {
            sb.append("【问题】\n").append(query)
                    .append("\n\n（注意：未检索到相关参考资料，请如实告知用户资料库中暂无相关内容，不要编造。）");
        }
        return sb.toString();
    }

    private List<Map<String, Object>> citations(String text, List<RetrievedChunk> chunks) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (text == null || chunks.isEmpty()) {
            return out;
        }
        Matcher m = CITATION.matcher(text);
        Set<Integer> seen = new HashSet<>();
        while (m.find()) {
            try {
                int n = Integer.parseInt(m.group(1));
                if (n >= 1 && n <= chunks.size() && seen.add(n)) {
                    RetrievedChunk c = chunks.get(n - 1);
                    String preview = c.content().length() > 200 ? c.content().substring(0, 200) : c.content();
                    out.add(Map.of("index", n, "docName", c.docName(), "position", c.position() + 1,
                            "chunkId", c.chunkId(), "kbId", c.kbId(), "source", c.source(), "content", preview));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private void send(SseEmitter emitter, boolean[] finished, Map<String, Object> data) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name("message").data(JsonUtil.toJson(data)));
            }
        } catch (IOException e) {
            finished[0] = true;
            log.debug("SSE 客户端断开: {}", e.getMessage());
        }
    }
}
