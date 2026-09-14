package com.pkb.vector.pg;

import com.pkb.config.PkbProperties;
import com.pkb.util.TextUtil;
import com.pkb.vector.VectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PostgreSQL + pgvector 向量存储。
 * 每个知识库一张向量表（维度随首个向量自动建表），切换仅需改配置 pkb.vector.mode=pgvector。
 */
@Slf4j
@Component
public class PgVectorStore implements VectorStore {

    private final PkbProperties props;
    private final Map<Long, Integer> tableDims = new ConcurrentHashMap<>();
    private volatile JdbcTemplate jdbc;
    private volatile boolean failedLogged = false;

    public PgVectorStore(PkbProperties props) {
        this.props = props;
    }

    private synchronized JdbcTemplate jdbc() {
        if (jdbc == null) {
            PkbProperties.Pgvector p = props.getVector().getPgvector();
            DriverManagerDataSource ds = new DriverManagerDataSource();
            ds.setDriverClassName("org.postgresql.Driver");
            ds.setUrl("jdbc:postgresql://" + p.getHost() + ":" + p.getPort() + "/" + p.getDatabase());
            ds.setUsername(p.getUsername());
            ds.setPassword(p.getPassword());
            jdbc = new JdbcTemplate(ds);
            jdbc.setQueryTimeout(30);
            try {
                jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
            } catch (Exception e) {
                if (!failedLogged) {
                    failedLogged = true;
                    log.warn("pgvector 扩展创建失败（可能无权限）：{}", e.getMessage());
                }
            }
            log.info("pgvector 存储已连接: {}:{}/{}", p.getHost(), p.getPort(), p.getDatabase());
        }
        return jdbc;
    }

    private String table(long kbId) {
        return props.getVector().getPgvector().getTablePrefix() + kbId;
    }

    private synchronized void ensureTable(long kbId, int dim) {
        Integer existing = tableDims.get(kbId);
        if (existing != null) {
            if (existing != dim) {
                throw new IllegalStateException("知识库 " + kbId + " 向量维度不一致（已有 " + existing + "，新向量 " + dim + "），请更换为维度一致的 Embedding 模型");
            }
            return;
        }
        jdbc().execute("CREATE TABLE IF NOT EXISTS " + table(kbId)
                + " (chunk_id BIGINT PRIMARY KEY, kb_id BIGINT NOT NULL, embedding vector(" + dim + "))");
        tableDims.put(kbId, dim);
    }

    private String vecString(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }

    @Override
    public void add(long chunkId, long kbId, float[] vector) {
        ensureTable(kbId, vector.length);
        String table = table(kbId);
        String vs = vecString(vector);
        jdbc().update("INSERT INTO " + table + " (chunk_id, kb_id, embedding) VALUES (?,?,?::vector) "
                        + "ON CONFLICT (chunk_id) DO UPDATE SET embedding = EXCLUDED.embedding",
                chunkId, kbId, vs);
    }

    @Override
    public void deleteChunks(long kbId, List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        String table = table(kbId);
        for (long id : chunkIds) {
            jdbc().update("DELETE FROM " + table + " WHERE chunk_id = ?", id);
        }
    }

    @Override
    public void deleteByKb(long kbId) {
        try {
            jdbc().execute("DROP TABLE IF EXISTS " + table(kbId));
        } catch (Exception e) {
            log.warn("删除 pgvector 表失败: {}", e.getMessage());
        }
        tableDims.remove(kbId);
    }

    @Override
    public List<ScoredId> search(long kbId, float[] query, int topK, double minScore) {
        try {
            ensureTable(kbId, query.length);
        } catch (Exception e) {
            return List.of();
        }
        String table = table(kbId);
        String vs = vecString(TextUtil.normalize(query));
        return jdbc().query(
                "SELECT chunk_id, 1 - (embedding <=> ?::vector) AS score FROM " + table
                        + " WHERE 1 - (embedding <=> ?::vector) >= ? ORDER BY embedding <=> ?::vector LIMIT ?",
                (rs, i) -> new ScoredId(rs.getLong("chunk_id"), rs.getFloat("score")),
                vs, vs, minScore, topK);
    }

    @Override
    public long count(long kbId) {
        try {
            if (!tableDims.containsKey(kbId)) {
                return 0;
            }
            Long c = jdbc().queryForObject("SELECT COUNT(*) FROM " + table(kbId), Long.class);
            return c == null ? 0 : c;
        } catch (Exception e) {
            return 0;
        }
    }
}
