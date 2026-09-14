package com.pkb.dao;

import com.pkb.util.JdbcUtil;

import com.pkb.knowledge.KnowledgeBase;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class KnowledgeBaseDao {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbc;

    public KnowledgeBaseDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLS = "id, category_id, name, description, embedding_provider_id, chunk_strategy, "
            + "chunk_size, chunk_overlap, multimodal, graph_enabled, created_at, updated_at";

    public long insert(KnowledgeBase kb) {
        String now = LocalDateTime.now().format(FMT);
        return JdbcUtil.insertAndGetKey(jdbc, "INSERT INTO knowledge_base (category_id, name, description, embedding_provider_id, chunk_strategy, "
                        + "chunk_size, chunk_overlap, multimodal, graph_enabled, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                kb.getCategoryId() == null ? 0 : kb.getCategoryId(), kb.getName(), kb.getDescription() == null ? "" : kb.getDescription(),
                kb.getEmbeddingProviderId(), kb.getChunkStrategy(), kb.getChunkSize(), kb.getChunkOverlap(),
                bool(kb.getMultimodal()), bool(kb.getGraphEnabled()), now, now);
    }

    public void update(KnowledgeBase kb) {
        jdbc.update("UPDATE knowledge_base SET category_id=?, name=?, description=?, embedding_provider_id=?, "
                        + "chunk_strategy=?, chunk_size=?, chunk_overlap=?, multimodal=?, graph_enabled=?, updated_at=? WHERE id=?",
                kb.getCategoryId() == null ? 0 : kb.getCategoryId(), kb.getName(), kb.getDescription() == null ? "" : kb.getDescription(),
                kb.getEmbeddingProviderId(), kb.getChunkStrategy(), kb.getChunkSize(), kb.getChunkOverlap(),
                bool(kb.getMultimodal()), bool(kb.getGraphEnabled()), LocalDateTime.now().format(FMT), kb.getId());
    }

    public KnowledgeBase findById(long id) {
        List<KnowledgeBase> list = jdbc.query("SELECT " + COLS + " FROM knowledge_base WHERE id = ?",
                new BeanPropertyRowMapper<>(KnowledgeBase.class), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<KnowledgeBase> findAll() {
        return jdbc.query("SELECT " + COLS + " FROM knowledge_base ORDER BY id", new BeanPropertyRowMapper<>(KnowledgeBase.class));
    }

    public long countByCategory(long categoryId) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_base WHERE category_id = ?", Long.class, categoryId);
        return c == null ? 0 : c;
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM knowledge_base WHERE id = ?", id);
    }

    public long count() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_base", Long.class);
        return c == null ? 0 : c;
    }

    private static int bool(Boolean b) {
        return Boolean.TRUE.equals(b) ? 1 : 0;
    }
}
