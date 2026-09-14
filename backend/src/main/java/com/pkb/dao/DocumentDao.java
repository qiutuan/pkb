package com.pkb.dao;

import com.pkb.util.JdbcUtil;

import com.pkb.knowledge.Document;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class DocumentDao {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbc;

    public DocumentDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLS = "id, kb_id, file_name, file_type, file_size, file_path, status, progress, "
            + "error_message, chunk_count, created_at, updated_at";

    public long insert(Document d) {
        String now = LocalDateTime.now().format(FMT);
        return JdbcUtil.insertAndGetKey(jdbc, "INSERT INTO document (kb_id, file_name, file_type, file_size, file_path, status, progress, "
                        + "error_message, chunk_count, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                d.getKbId(), d.getFileName(), d.getFileType(), d.getFileSize(), d.getFilePath(),
                d.getStatus() == null ? "PENDING" : d.getStatus(),
                d.getProgress() == null ? 0 : d.getProgress(),
                d.getErrorMessage(), d.getChunkCount() == null ? 0 : d.getChunkCount(), now, now);
    }

    public Document findById(long id) {
        List<Document> list = jdbc.query("SELECT " + COLS + " FROM document WHERE id = ?",
                new BeanPropertyRowMapper<>(Document.class), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Document> findByKb(long kbId) {
        return jdbc.query("SELECT " + COLS + " FROM document WHERE kb_id = ? ORDER BY id DESC",
                new BeanPropertyRowMapper<>(Document.class), kbId);
    }

    public void updateStatus(long id, String status, Double progress, String errorMessage) {
        jdbc.update("UPDATE document SET status=?, progress=?, error_message=?, updated_at=? WHERE id=?",
                status, progress == null ? 0 : progress, errorMessage, LocalDateTime.now().format(FMT), id);
    }

    public void updateChunkCount(long id, int chunkCount) {
        jdbc.update("UPDATE document SET chunk_count=?, updated_at=? WHERE id=?",
                chunkCount, LocalDateTime.now().format(FMT), id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM document WHERE id = ?", id);
    }

    public long countByKb(long kbId) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE kb_id = ?", Long.class, kbId);
        return c == null ? 0 : c;
    }

    public long countByKbAndStatus(long kbId, String status) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE kb_id = ? AND status = ?", Long.class, kbId, status);
        return c == null ? 0 : c;
    }

    public long countAll() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM document", Long.class);
        return c == null ? 0 : c;
    }
}
