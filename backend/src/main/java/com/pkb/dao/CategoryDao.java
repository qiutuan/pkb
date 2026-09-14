package com.pkb.dao;

import com.pkb.util.JdbcUtil;

import com.pkb.knowledge.Category;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class CategoryDao {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbc;

    public CategoryDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLS = "id, parent_id, name, created_at";

    public long insert(Category c) {
        String now = LocalDateTime.now().format(FMT);
        return JdbcUtil.insertAndGetKey(jdbc, "INSERT INTO category (parent_id, name, created_at) VALUES (?,?,?)",
                c.getParentId() == null ? 0 : c.getParentId(), c.getName(), now);
    }

    public void update(Category c) {
        jdbc.update("UPDATE category SET parent_id=?, name=? WHERE id=?",
                c.getParentId() == null ? 0 : c.getParentId(), c.getName(), c.getId());
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM category WHERE id = ?", id);
    }

    public Category findById(long id) {
        List<Category> list = jdbc.query("SELECT " + COLS + " FROM category WHERE id = ?",
                new BeanPropertyRowMapper<>(Category.class), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Category> findAll() {
        return jdbc.query("SELECT " + COLS + " FROM category ORDER BY id", new BeanPropertyRowMapper<>(Category.class));
    }

    public long countChildren(long parentId) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM category WHERE parent_id = ?", Long.class, parentId);
        return c == null ? 0 : c;
    }
}
