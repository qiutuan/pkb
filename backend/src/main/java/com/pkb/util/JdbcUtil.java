package com.pkb.util;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;

/**
 * JDBC 工具：在单条连接内完成 INSERT 并取回自增主键。
 * 注意：SQLite 的 last_insert_rowid() 是连接级函数，若 insert 与取 id 分属两条连接将恒为 0，
 * 必须使用 GeneratedKeyHolder 保证同连接取键。
 */
public final class JdbcUtil {

    private JdbcUtil() {
    }

    public static long insertAndGetKey(JdbcTemplate jdbc, String sql, Object... args) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, kh);
        Number key = kh.getKey();
        if (key == null) {
            throw new IllegalStateException("未能取回自增主键: " + sql);
        }
        return key.longValue();
    }
}
