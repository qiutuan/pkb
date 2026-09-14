package com.pkb.dao;

import com.pkb.util.JdbcUtil;

import com.pkb.model.ModelProvider;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class ModelProviderDao {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;

    public ModelProviderDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLS = "id, name, provider_type, base_url, api_key_enc, chat_model, embedding_model, "
            + "temperature, max_tokens, default_chat, default_embedding, enabled, created_at, updated_at";

    public long insert(ModelProvider p) {
        String now = LocalDateTime.now().format(FMT);
        return JdbcUtil.insertAndGetKey(jdbc, "INSERT INTO model_provider (name, provider_type, base_url, api_key_enc, chat_model, embedding_model, "
                        + "temperature, max_tokens, default_chat, default_embedding, enabled, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                p.getName(), p.getProviderType(), p.getBaseUrl(), p.getApiKeyEnc(), p.getChatModel(), p.getEmbeddingModel(),
                p.getTemperature(), p.getMaxTokens(), bool(p.getDefaultChat()), bool(p.getDefaultEmbedding()),
                bool(p.getEnabled()), now, now);
    }

    public void update(ModelProvider p) {
        jdbc.update("UPDATE model_provider SET name=?, provider_type=?, base_url=?, api_key_enc=?, chat_model=?, "
                        + "embedding_model=?, temperature=?, max_tokens=?, default_chat=?, default_embedding=?, enabled=?, updated_at=? WHERE id=?",
                p.getName(), p.getProviderType(), p.getBaseUrl(), p.getApiKeyEnc(), p.getChatModel(), p.getEmbeddingModel(),
                p.getTemperature(), p.getMaxTokens(), bool(p.getDefaultChat()), bool(p.getDefaultEmbedding()),
                bool(p.getEnabled()), LocalDateTime.now().format(FMT), p.getId());
    }

    public ModelProvider findById(long id) {
        List<ModelProvider> list = jdbc.query("SELECT " + COLS + " FROM model_provider WHERE id = ?",
                new BeanPropertyRowMapper<>(ModelProvider.class), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<ModelProvider> findAll() {
        return jdbc.query("SELECT " + COLS + " FROM model_provider ORDER BY id", new BeanPropertyRowMapper<>(ModelProvider.class));
    }

    public List<ModelProvider> findAllEnabled() {
        return jdbc.query("SELECT " + COLS + " FROM model_provider WHERE enabled = 1 ORDER BY id",
                new BeanPropertyRowMapper<>(ModelProvider.class));
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM model_provider WHERE id = ?", id);
    }

    public long count() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM model_provider", Long.class);
        return c == null ? 0 : c;
    }

    public void clearDefaultChat() {
        jdbc.update("UPDATE model_provider SET default_chat = 0");
    }

    public void clearDefaultEmbedding() {
        jdbc.update("UPDATE model_provider SET default_embedding = 0");
    }

    public ModelProvider findDefaultChat() {
        List<ModelProvider> list = jdbc.query("SELECT " + COLS + " FROM model_provider WHERE default_chat = 1 AND enabled = 1 LIMIT 1",
                new BeanPropertyRowMapper<>(ModelProvider.class));
        return list.isEmpty() ? null : list.get(0);
    }

    public ModelProvider findDefaultEmbedding() {
        List<ModelProvider> list = jdbc.query("SELECT " + COLS + " FROM model_provider WHERE default_embedding = 1 AND enabled = 1 LIMIT 1",
                new BeanPropertyRowMapper<>(ModelProvider.class));
        return list.isEmpty() ? null : list.get(0);
    }

    private static int bool(Boolean b) {
        return Boolean.TRUE.equals(b) ? 1 : 0;
    }
}
