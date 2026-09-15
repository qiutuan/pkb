package com.pkb.model;

import com.pkb.common.BusinessException;
import com.pkb.security.CryptoService;
import com.pkb.util.EncryptUtil;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 Provider 配置构建并缓存 LangChain4j 模型实例（Chat / Streaming / Embedding 分离）。
 */
@Component
public class ModelFactory {

    private final byte[] key;
    private final ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();

    public ModelFactory(CryptoService cryptoService) {
        this.key = cryptoService.getKey();
    }

    public void invalidate(long providerId) {
        cache.remove("chat:" + providerId);
        cache.remove("stream:" + providerId);
        cache.remove("embed:" + providerId);
    }

    public ChatModel chatModel(ModelProvider p) {
        return chatModel(p, p.getMaxTokens());
    }

    public ChatModel chatModel(ModelProvider p, Integer maxTokensOverride) {
        String key = "chat:" + p.getId() + ":" + maxTokensOverride;
        Object v = cache.get(key);
        if (v instanceof ChatModel m) {
            return m;
        }
        ChatModel m = buildChat(p, maxTokensOverride);
        cache.put(key, m);
        return m;
    }

    public StreamingChatModel streamingModel(ModelProvider p) {
        String key = "stream:" + p.getId();
        Object v = cache.get(key);
        if (v instanceof StreamingChatModel m) {
            return m;
        }
        StreamingChatModel m = buildStreaming(p);
        cache.put(key, m);
        return m;
    }

    public EmbeddingModel embeddingModel(ModelProvider p) {
        if ("local".equals(p.getProviderType())) {
            return new LocalEmbeddingModel();
        }
        String key = "embed:" + p.getId();
        Object v = cache.get(key);
        if (v instanceof EmbeddingModel m) {
            return m;
        }
        EmbeddingModel m = buildEmbedding(p);
        cache.put(key, m);
        return m;
    }

    public String decryptKey(ModelProvider p) {
        if (p.getApiKeyEnc() == null || p.getApiKeyEnc().isBlank()) {
            return null;
        }
        return EncryptUtil.decrypt(p.getApiKeyEnc(), key);
    }

    private ChatModel buildChat(ModelProvider p, Integer maxTokens) {
        String type = p.getProviderType();
        String url = p.getBaseUrl();
        String apiKey = decryptKey(p);
        double temp = p.getTemperature() == null ? 0.7 : p.getTemperature();
        return switch (type) {
            case "openai_compatible" -> OpenAiChatModel.builder()
                    .baseUrl(normalizeOpenAiUrl(url))
                    .apiKey(apiKey == null ? "sk-none" : apiKey)
                    .modelName(require(p.getChatModel(), "请填写聊天模型名称"))
                    .temperature(temp)
                    .maxTokens(maxTokens == null ? 2048 : maxTokens)
                    .timeout(Duration.ofSeconds(180))
                    .build();
            case "ollama" -> OllamaChatModel.builder()
                    .baseUrl(require(url, "请填写 Ollama 地址"))
                    .modelName(require(p.getChatModel(), "请填写聊天模型名称"))
                    .temperature(temp)
                    .build();
            case "anthropic" -> AnthropicChatModel.builder()
                    .apiKey(require(apiKey, "请填写 API Key"))
                    .modelName(require(p.getChatModel(), "请填写聊天模型名称"))
                    .temperature(temp)
                    .maxTokens(maxTokens == null ? 2048 : maxTokens)
                    .build();
            case "gemini" -> GoogleAiGeminiChatModel.builder()
                    .apiKey(require(apiKey, "请填写 API Key"))
                    .modelName(require(p.getChatModel(), "请填写聊天模型名称"))
                    .temperature(temp)
                    .maxOutputTokens(maxTokens == null ? 2048 : maxTokens)
                    .build();
            case "local" -> throw new BusinessException("内置本地模型仅支持向量化，不支持聊天");
            default -> throw new BusinessException("不支持的 Provider 类型: " + type);
        };
    }

    private StreamingChatModel buildStreaming(ModelProvider p) {
        String type = p.getProviderType();
        String url = p.getBaseUrl();
        String apiKey = decryptKey(p);
        double temp = p.getTemperature() == null ? 0.7 : p.getTemperature();
        return switch (type) {
            case "openai_compatible" -> OpenAiStreamingChatModel.builder()
                    .baseUrl(normalizeOpenAiUrl(url))
                    .apiKey(apiKey == null ? "sk-none" : apiKey)
                    .modelName(require(p.getChatModel(), "请填写聊天模型名称"))
                    .temperature(temp)
                    .maxTokens(p.getMaxTokens() == null ? 2048 : p.getMaxTokens())
                    .timeout(Duration.ofSeconds(180))
                    .build();
            case "ollama" -> OllamaStreamingChatModel.builder()
                    .baseUrl(require(url, "请填写 Ollama 地址"))
                    .modelName(require(p.getChatModel(), "请填写聊天模型名称"))
                    .temperature(temp)
                    .build();
            case "anthropic" -> AnthropicStreamingChatModel.builder()
                    .apiKey(require(apiKey, "请填写 API Key"))
                    .modelName(require(p.getChatModel(), "请填写聊天模型名称"))
                    .temperature(temp)
                    .maxTokens(p.getMaxTokens() == null ? 2048 : p.getMaxTokens())
                    .build();
            case "gemini" -> GoogleAiGeminiStreamingChatModel.builder()
                    .apiKey(require(apiKey, "请填写 API Key"))
                    .modelName(require(p.getChatModel(), "请填写聊天模型名称"))
                    .temperature(temp)
                    .build();
            case "local" -> throw new BusinessException("内置本地模型仅支持向量化，不支持聊天");
            default -> throw new BusinessException("不支持的 Provider 类型: " + type);
        };
    }

    private EmbeddingModel buildEmbedding(ModelProvider p) {
        String type = p.getProviderType();
        String url = p.getBaseUrl();
        String apiKey = decryptKey(p);
        return switch (type) {
            case "openai_compatible" -> OpenAiEmbeddingModel.builder()
                    .baseUrl(normalizeOpenAiUrl(url))
                    .apiKey(apiKey == null ? "sk-none" : apiKey)
                    .modelName(require(p.getEmbeddingModel(), "请填写 Embedding 模型名称"))
                    .timeout(Duration.ofSeconds(180))
                    .build();
            case "ollama" -> OllamaEmbeddingModel.builder()
                    .baseUrl(require(url, "请填写 Ollama 地址"))
                    .modelName(require(p.getEmbeddingModel(), "请填写 Embedding 模型名称"))
                    .build();
            case "gemini" -> GoogleAiEmbeddingModel.builder()
                    .apiKey(require(apiKey, "请填写 API Key"))
                    .modelName(require(p.getEmbeddingModel(), "请填写 Embedding 模型名称"))
                    .build();
            case "local" -> new LocalEmbeddingModel();
            default -> throw new BusinessException("该 Provider 类型不支持 Embedding（" + type + "）");
        };
    }

    /** OpenAI 兼容地址归一化：自动补 /v1（若缺失） */
    public static String normalizeOpenAiUrl(String url) {
        String u = require(url, "请填写 Base URL");
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (!u.endsWith("/v1")) {
            u = u + "/v1";
        }
        return u;
    }

    private static String require(String v, String msg) {
        if (v == null || v.isBlank()) {
            throw new BusinessException(msg);
        }
        return v;
    }
}
