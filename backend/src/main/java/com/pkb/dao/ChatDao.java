package com.pkb.dao;

import com.pkb.util.JdbcUtil;

import com.pkb.rag.ChatMessage;
import com.pkb.rag.ChatSession;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class ChatDao {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbc;

    public ChatDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ===== 会话 =====

    public long insertSession(String title) {
        String now = LocalDateTime.now().format(FMT);
        return JdbcUtil.insertAndGetKey(jdbc, "INSERT INTO chat_session (title, created_at, updated_at) VALUES (?,?,?)",
                title == null || title.isBlank() ? "新对话" : title, now, now);
    }

    public ChatSession findSession(long id) {
        List<ChatSession> list = jdbc.query("SELECT id, title, created_at, updated_at FROM chat_session WHERE id=?",
                new BeanPropertyRowMapper<>(ChatSession.class), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<ChatSession> listSessions() {
        return jdbc.query("SELECT id, title, created_at, updated_at FROM chat_session ORDER BY updated_at DESC",
                new BeanPropertyRowMapper<>(ChatSession.class));
    }

    public void updateSessionTitle(long id, String title) {
        jdbc.update("UPDATE chat_session SET title=?, updated_at=? WHERE id=?",
                title, LocalDateTime.now().format(FMT), id);
    }

    public void touchSession(long id) {
        jdbc.update("UPDATE chat_session SET updated_at=? WHERE id=?",
                LocalDateTime.now().format(FMT), id);
    }

    public void deleteSession(long id) {
        jdbc.update("DELETE FROM chat_session WHERE id=?", id);
        jdbc.update("DELETE FROM chat_message WHERE session_id=?", id);
    }

    // ===== 消息 =====

    public long insertMessage(long sessionId, String role, String content, String sources) {
        String now = LocalDateTime.now().format(FMT);
        return JdbcUtil.insertAndGetKey(jdbc, "INSERT INTO chat_message (session_id, role, content, sources, created_at) VALUES (?,?,?,?,?)",
                sessionId, role, content, sources == null ? "[]" : sources, now);
    }

    public List<ChatMessage> messages(long sessionId) {
        return jdbc.query("SELECT id, session_id, role, content, sources, created_at FROM chat_message WHERE session_id=? ORDER BY id",
                new BeanPropertyRowMapper<>(ChatMessage.class), sessionId);
    }

    public void clearMessages(long sessionId) {
        jdbc.update("DELETE FROM chat_message WHERE session_id=?", sessionId);
    }

    public long countSessions() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM chat_session", Long.class);
        return c == null ? 0 : c;
    }
}
