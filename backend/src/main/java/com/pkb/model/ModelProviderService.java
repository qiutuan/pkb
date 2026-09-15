package com.pkb.model;

import com.pkb.common.BusinessException;
import com.pkb.config.DatabaseInitializer;
import com.pkb.dao.ModelProviderDao;
import com.pkb.security.CryptoService;
import com.pkb.util.EncryptUtil;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.ollama.OllamaModel;
import dev.langchain4j.model.ollama.OllamaModels;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 模型提供者管理：增删改查、默认模型、连接测试、Ollama 模型列表。
 */
@Slf4j
@Service
@DependsOn("databaseInitializer")
public class ModelProviderService {

    private static final Set<String> TYPES = Set.of("openai_compatible", "ollama", "anthropic", "gemini", "local", "rerank");

    private final ModelProviderDao dao;
    private final ModelFactory factory;
    private final CryptoService crypto;

    public ModelProviderService(ModelProviderDao dao, ModelFactory factory, CryptoService crypto) {
        this.dao = dao;
        this.factory = factory;
        this.crypto = crypto;
    }

    @PostConstruct
    public void seedLocalProvider() {
        if (dao.count() == 0) {
            ModelProvider p = new ModelProvider();
            p.setName("内置本地向量模型（离线）");
            p.setProviderType("local");
            p.setEmbeddingModel(LocalEmbeddingModel.MODEL_NAME);
            p.setDefaultEmbedding(true);
            p.setDefaultChat(false);
            p.setEnabled(true);
            p.setTemperature(0.7);
            p.setMaxTokens(2048);
            save(p);
            log.info("已自动创建内置本地 Embedding 模型（离线，512 维，用于开箱即用与自测）");
        }
    }

    public List<ProviderView> list() {
        return dao.findAll().stream().map(this::toView).toList();
    }

    public ProviderView save(ModelProvider p) {
        validate(p);
        if (p.getId() == null) {
            // 明文 apiKey 优先；兼容旧字段 apiKeyEnc
            String plain = p.getApiKey();
            if (plain == null || plain.isBlank()) {
                plain = p.getApiKeyEnc();
            }
            if (plain != null && !plain.isBlank()) {
                p.setApiKeyEnc(EncryptUtil.encrypt(plain, crypto.getKey()));
            }
            p.setApiKey(null);
            // 新增 Provider 默认「停用」，用户手动开启后才可被调用
            if (p.getEnabled() == null) {
                p.setEnabled(false);
            }
            boolean first = dao.count() == 0;
            if (first) {
                p.setDefaultChat(p.getChatModel() != null && !p.getChatModel().isBlank());
            }
            if (Boolean.TRUE.equals(p.getDefaultChat())) {
                dao.clearDefaultChat();
            }
            if (Boolean.TRUE.equals(p.getDefaultEmbedding())) {
                dao.clearDefaultEmbedding();
            }
            long id = dao.insert(p);
            p.setId(id);
        } else {
            ModelProvider old = dao.findById(p.getId());
            if (old == null) {
                throw new BusinessException("模型不存在");
            }
            // 停用保护：默认聊天 / 默认向量不可停用
            if (!Boolean.TRUE.equals(p.getEnabled()) && Boolean.TRUE.equals(old.getEnabled())) {
                if (Boolean.TRUE.equals(old.getDefaultChat()) || Boolean.TRUE.equals(old.getDefaultEmbedding())) {
                    throw new BusinessException("默认聊天 / 默认向量的模型不可停用，请先转移默认身份");
                }
            }
            // apiKey 为空表示不修改
            if (p.getApiKey() != null && !p.getApiKey().isBlank()) {
                p.setApiKeyEnc(EncryptUtil.encrypt(p.getApiKey(), crypto.getKey()));
            } else {
                p.setApiKeyEnc(old.getApiKeyEnc());
            }
            p.setApiKey(null);
            if (Boolean.TRUE.equals(p.getDefaultChat())) {
                dao.clearDefaultChat();
            }
            if (Boolean.TRUE.equals(p.getDefaultEmbedding())) {
                dao.clearDefaultEmbedding();
            }
            dao.update(p);
        }
        factory.invalidate(p.getId());
        return toView(dao.findById(p.getId()));
    }

    /** 启用/停用（列表内联开关）；默认聊天/向量不可停用 */
    public ProviderView toggle(long id, boolean enabled) {
        ModelProvider p = dao.findById(id);
        if (p == null) {
            throw new BusinessException("模型不存在");
        }
        if (!enabled && (Boolean.TRUE.equals(p.getDefaultChat()) || Boolean.TRUE.equals(p.getDefaultEmbedding()))) {
            throw new BusinessException("默认聊天 / 默认向量的模型不可停用，请先转移默认身份");
        }
        dao.updateEnabled(id, enabled);
        factory.invalidate(id);
        return toView(dao.findById(id));
    }

    public void delete(long id) {
        ModelProvider p = dao.findById(id);
        if (p == null) {
            throw new BusinessException("模型不存在");
        }
        if (Boolean.TRUE.equals(p.getDefaultChat()) || Boolean.TRUE.equals(p.getDefaultEmbedding())) {
            throw new BusinessException("默认聊天 / 默认向量的模型不可删除，请先转移默认身份");
        }
        dao.delete(id);
        factory.invalidate(id);
    }

    public ProviderView get(long id) {
        ModelProvider p = dao.findById(id);
        if (p == null) {
            throw new BusinessException("模型不存在");
        }
        return toView(p);
    }

    public ModelProvider requireEnabled(long id) {
        ModelProvider p = dao.findById(id);
        if (p == null || !Boolean.TRUE.equals(p.getEnabled())) {
            throw new BusinessException("模型不存在或已停用");
        }
        return p;
    }

    public ModelProvider defaultChatProvider() {
        ModelProvider p = dao.findDefaultChat();
        if (p == null) {
            throw new BusinessException("尚未配置默认聊天模型，请到「模型管理」添加并设为默认");
        }
        return p;
    }

    public ModelProvider defaultEmbeddingProvider() {
        ModelProvider p = dao.findDefaultEmbedding();
        if (p == null) {
            throw new BusinessException("尚未配置默认 Embedding 模型，请到「模型管理」添加并设为默认");
        }
        return p;
    }

    public Map<String, Object> defaults() {
        ModelProvider chat = dao.findDefaultChat();
        ModelProvider embed = dao.findDefaultEmbedding();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chat", chat == null ? null : toView(chat));
        out.put("embedding", embed == null ? null : toView(embed));
        return out;
    }

    /** 连接测试：优先走免费接口（/models、/api/tags），否则用最小 chat 请求 */
    public Map<String, Object> test(long id) {
        ModelProvider p = requireEnabled(id);
        try {
            String detail = switch (p.getProviderType()) {
                case "ollama" -> testOllama(p);
                case "openai_compatible" -> testOpenAiCompatible(p);
                case "anthropic", "gemini" -> testChatPing(p);
                case "rerank" -> testRerank(p);
                case "local" -> "内置本地模型，无需连接";
                default -> "未知类型";
            };
            return Map.of("ok", true, "message", "连接成功", "detail", detail);
        } catch (Exception e) {
            return Map.of("ok", false, "message", "连接失败", "detail", String.valueOf(e.getMessage()));
        }
    }

    private String testOllama(ModelProvider p) throws Exception {
        String url = trimSlash(p.getBaseUrl()) + "/api/tags";
        HttpResponse<String> resp = http().send(HttpRequest.newBuilder(URI.create(url)).GET()
                .timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            return "Ollama 服务正常（HTTP " + resp.statusCode() + "）";
        }
        return chatPingRaw(p);
    }

    private String testOpenAiCompatible(ModelProvider p) throws Exception {
        String base = ModelFactory.normalizeOpenAiUrl(p.getBaseUrl());
        String url = base + "/models";
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(15));
        String key = decrypt(p);
        if (key != null && !key.isBlank()) {
            rb.header("Authorization", "Bearer " + key);
        }
        HttpResponse<String> resp = http().send(rb.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            return "OpenAI 兼容接口正常（HTTP " + resp.statusCode() + "）";
        }
        return chatPingRaw(p);
    }

    private String testRerank(ModelProvider p) throws Exception {
        String base = ModelFactory.normalizeOpenAiUrl(p.getBaseUrl());
        String url = base + "/rerank";
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(15));
        String key = decrypt(p);
        if (key != null && !key.isBlank()) {
            rb.header("Authorization", "Bearer " + key);
        }
        HttpResponse<String> resp = http().send(rb.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            return "Rerank 接口正常（HTTP " + resp.statusCode() + "）";
        }
        return "Rerank 接口可用性未知（HTTP " + resp.statusCode() + "，POST /rerank 生效）";
    }

    /** 默认 Rerank 模型：类型为 rerank 且启用的第一个 */
    public ModelProvider defaultRerankProvider() {
        List<ModelProvider> list = dao.findAllEnabled();
        for (ModelProvider p : list) {
            if ("rerank".equals(p.getProviderType())) {
                return p;
            }
        }
        throw new BusinessException("尚未配置 Rerank 模型（类型为 Rerank 的 Provider），请到「模型管理」添加");
    }

    private String testChatPing(ModelProvider p) {
        ChatModel model = factory.chatModel(p, 1);
        model.chat(ChatRequest.builder().messages(List.of(UserMessage.from("你好，请回复：正常"))).build());
        return "模型调用成功";
    }

    private String chatPingRaw(ModelProvider p) {
        ChatModel model = factory.chatModel(p, 1);
        model.chat(ChatRequest.builder().messages(List.of(UserMessage.from("你好，请回复：正常"))).build());
        return "模型调用成功（/models 接口不可用，已通过 chat 验证）";
    }

    /** 拉取 Ollama 本地模型列表 */
    public List<String> ollamaModels(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException("请先填写 Ollama 地址");
        }
        try {
            OllamaModels om = OllamaModels.builder().baseUrl(trimSlash(baseUrl)).build();
            List<String> names = new ArrayList<>();
            for (OllamaModel m : om.availableModels().content()) {
                names.add(m.getName());
            }
            return names;
        } catch (Exception e) {
            throw new BusinessException("拉取 Ollama 模型列表失败：" + e.getMessage());
        }
    }

    private void validate(ModelProvider p) {
        if (p.getName() == null || p.getName().isBlank()) {
            throw new BusinessException("请填写模型名称");
        }
        if (p.getProviderType() == null || !TYPES.contains(p.getProviderType())) {
            throw new BusinessException("不支持的模型类型");
        }
        // 能力默认：文本
        if (p.getCapabilities() == null || p.getCapabilities().isBlank()) {
            p.setCapabilities("text");
        } else {
            List<String> caps = new ArrayList<>();
            for (String c : p.getCapabilities().split(",")) {
                String t = c.trim();
                if (!t.isEmpty() && !caps.contains(t)) {
                    caps.add(t);
                }
            }
            p.setCapabilities(String.join(",", caps));
        }
        if ("local".equals(p.getProviderType())) {
            p.setBaseUrl(null);
            p.setChatModel(null);
            p.setDefaultChat(false);
            if (p.getEmbeddingModel() == null || p.getEmbeddingModel().isBlank()) {
                throw new BusinessException("内置本地向量模型需配置向量模型名");
            }
            return;
        }
        // Rerank 模型：仅需 base_url + api_key + 模型名（存于 chatModel），不参与聊天/向量
        if ("rerank".equals(p.getProviderType())) {
            p.setEmbeddingModel(null);
            p.setDefaultChat(false);
            p.setDefaultEmbedding(false);
            if (p.getChatModel() == null || p.getChatModel().isBlank()) {
                throw new BusinessException("Rerank 模型需填写模型名（存于聊天模型名）");
            }
            return;
        }
        boolean hasChat = p.getChatModel() != null && !p.getChatModel().isBlank();
        boolean hasEmbed = p.getEmbeddingModel() != null && !p.getEmbeddingModel().isBlank();
        if (!hasChat && !hasEmbed) {
            throw new BusinessException("聊天模型与向量模型至少配置一项");
        }
        // 默认身份只能指向具备对应能力的 Provider
        if (Boolean.TRUE.equals(p.getDefaultChat()) && !hasChat) {
            throw new BusinessException("「默认聊天」只能选择配置了聊天模型的 Provider");
        }
        if (Boolean.TRUE.equals(p.getDefaultEmbedding()) && !hasEmbed) {
            throw new BusinessException("「默认向量」只能选择配置了向量模型的 Provider");
        }
        // Anthropic 不提供 Embedding 接口：仅需聊天模型
        if ("anthropic".equals(p.getProviderType())) {
            p.setEmbeddingModel(null);
            return;
        }
        // Ollama 例外：可共用本地内置向量模型，允许 Embedding 为空
        if ("ollama".equals(p.getProviderType()) && !hasEmbed) {
            return;
        }
    }

    private String decrypt(ModelProvider p) {
        if (p.getApiKeyEnc() == null || p.getApiKeyEnc().isBlank()) {
            return null;
        }
        return EncryptUtil.decrypt(p.getApiKeyEnc(), crypto.getKey());
    }

    private ProviderView toView(ModelProvider p) {
        boolean hasKey = p.getApiKeyEnc() != null && !p.getApiKeyEnc().isBlank();
        return new ProviderView(
                p.getId(), p.getName(), p.getProviderType(), p.getTemplateName(), p.getBaseUrl(),
                p.getChatModel(), p.getEmbeddingModel(), p.getTemperature(), p.getMaxTokens(),
                Boolean.TRUE.equals(p.getDefaultChat()), Boolean.TRUE.equals(p.getDefaultEmbedding()),
                Boolean.TRUE.equals(p.getEnabled()),
                p.getCapabilities() == null || p.getCapabilities().isBlank() ? "text" : p.getCapabilities(),
                hasKey ? EncryptUtil.mask(EncryptUtil.decrypt(p.getApiKeyEnc(), crypto.getKey())) : "",
                hasKey, p.getCreatedAt(), p.getUpdatedAt());
    }

    private static HttpClient http() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    private static String trimSlash(String s) {
        String u = s == null ? "" : s.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
