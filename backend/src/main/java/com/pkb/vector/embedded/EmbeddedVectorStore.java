package com.pkb.vector.embedded;

import com.pkb.config.PkbProperties;
import com.pkb.util.TextUtil;
import com.pkb.util.VecCodec;
import com.pkb.vector.VectorStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内置向量库：SQLite 持久化向量 + 内存索引（HNSW/Flat）。
 * 启动时从 SQLite 重建索引，数据零外部依赖、本地优先。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddedVectorStore implements VectorStore {

    private final JdbcTemplate jdbc;
    private final PkbProperties props;

    private final Map<Long, Index> indexes = new ConcurrentHashMap<>();
    private final Map<Long, Long> chunkKb = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    @PostConstruct
    public synchronized void load() {
        if (loaded) {
            return;
        }
        jdbc.query("SELECT chunk_id, kb_id, vec FROM vectors", rs -> {
            long chunkId = rs.getLong("chunk_id");
            long kbId = rs.getLong("kb_id");
            byte[] bytes = rs.getBytes("vec");
            if (bytes != null) {
                indexFor(kbId).insert(chunkId, VecCodec.fromBytes(bytes));
                chunkKb.put(chunkId, kbId);
            }
        });
        loaded = true;
        long total = chunkKb.size();
        if (total > 0) {
            log.info("内置向量库加载完成，共 {} 个向量，索引算法: {}",
                    total, props.getVector().getEmbedded().getAlgorithm());
        }
    }

    private Index indexFor(long kbId) {
        return indexes.computeIfAbsent(kbId, k -> {
            String algo = props.getVector().getEmbedded().getAlgorithm();
            if ("flat".equalsIgnoreCase(algo)) {
                return new FlatIndex();
            }
            PkbProperties.Hnsw h = props.getVector().getEmbedded().getHnsw();
            return new HnswIndex(h.getM(), h.getEfConstruction(), h.getEfSearch(), h.getSeed());
        });
    }

    @Override
    public synchronized void add(long chunkId, long kbId, float[] vector) {
        float[] normalized = TextUtil.normalize(vector);
        jdbc.update("INSERT OR REPLACE INTO vectors(chunk_id, kb_id, dim, vec) VALUES (?,?,?,?)",
                chunkId, kbId, normalized.length, VecCodec.toBytes(normalized));
        indexFor(kbId).insert(chunkId, normalized);
        chunkKb.put(chunkId, kbId);
    }

    @Override
    public void deleteChunks(long kbId, List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        Index idx = indexes.get(kbId);
        for (long chunkId : chunkIds) {
            chunkKb.remove(chunkId);
            if (idx != null) {
                idx.remove(chunkId);
            }
            jdbc.update("DELETE FROM vectors WHERE chunk_id = ?", chunkId);
        }
    }

    @Override
    public void deleteByKb(long kbId) {
        List<Long> ids = jdbc.queryForList("SELECT chunk_id FROM vectors WHERE kb_id = ?", Long.class, kbId);
        deleteChunks(kbId, ids);
        indexes.remove(kbId);
    }

    @Override
    public List<ScoredId> search(long kbId, float[] query, int topK, double minScore) {
        load();
        Index idx = indexes.get(kbId);
        if (idx == null || idx.size() == 0) {
            return List.of();
        }
        float[] normalized = TextUtil.normalize(query);
        List<Index.Hit> hits = idx.search(normalized, topK * 4);
        List<ScoredId> out = new ArrayList<>();
        for (Index.Hit h : hits) {
            if (h.score() >= minScore) {
                out.add(new ScoredId(h.id(), h.score()));
                if (out.size() >= topK) {
                    break;
                }
            }
        }
        return out;
    }

    @Override
    public long count(long kbId) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM vectors WHERE kb_id = ?", Long.class, kbId);
        return c == null ? 0 : c;
    }
}
