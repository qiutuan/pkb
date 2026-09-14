package com.pkb.dao;

import com.pkb.util.JdbcUtil;

import com.pkb.knowledge.Chunk;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class ChunkDao {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbc;

    public ChunkDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLS = "id, kb_id, doc_id, position, content, meta, created_at";

    public long insert(Chunk c) {
        String now = LocalDateTime.now().format(FMT);
        return JdbcUtil.insertAndGetKey(jdbc, "INSERT INTO chunk (kb_id, doc_id, position, content, meta, created_at) VALUES (?,?,?,?,?,?)",
                c.getKbId(), c.getDocId(), c.getPosition(), c.getContent(),
                c.getMeta() == null ? "{}" : c.getMeta(), now);
    }

    public List<Chunk> findByDoc(long docId) {
        return jdbc.query("SELECT " + COLS + " FROM chunk WHERE doc_id = ? ORDER BY position",
                new BeanPropertyRowMapper<>(Chunk.class), docId);
    }

    public List<Chunk> findAllByKb(long kbId) {
        return jdbc.query("SELECT " + COLS + " FROM chunk WHERE kb_id = ? ORDER BY position",
                new BeanPropertyRowMapper<>(Chunk.class), kbId);
    }

    public List<Chunk> findByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.query("SELECT " + COLS + " FROM chunk WHERE id IN (" + placeholders + ")",
                new BeanPropertyRowMapper<>(Chunk.class), ids.toArray());
    }

    public List<Long> findIdsByDoc(long docId) {
        return jdbc.queryForList("SELECT id FROM chunk WHERE doc_id = ?", Long.class, docId);
    }

    public List<Long> findIdsByKb(long kbId) {
        return jdbc.queryForList("SELECT id FROM chunk WHERE kb_id = ?", Long.class, kbId);
    }

    public void deleteByDoc(long docId) {
        jdbc.update("DELETE FROM chunk WHERE doc_id = ?", docId);
    }

    public void deleteByKb(long kbId) {
        jdbc.update("DELETE FROM chunk WHERE kb_id = ?", kbId);
    }

    public long countByKb(long kbId) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM chunk WHERE kb_id = ?", Long.class, kbId);
        return c == null ? 0 : c;
    }

    public long countAll() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM chunk", Long.class);
        return c == null ? 0 : c;
    }
}
