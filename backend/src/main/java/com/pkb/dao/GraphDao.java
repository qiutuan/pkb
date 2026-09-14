package com.pkb.dao;

import com.pkb.util.JdbcUtil;

import com.pkb.graph.GraphEntity;
import com.pkb.graph.GraphRelation;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class GraphDao {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbc;

    public GraphDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String ENTITY_COLS = "id, kb_id, name, entity_type, description, created_at";
    private static final String REL_COLS = "id, kb_id, source_id, target_id, relation_type, description, created_at";

    // ===== 实体 =====

    public long insertEntity(GraphEntity e) {
        String now = LocalDateTime.now().format(FMT);
        return JdbcUtil.insertAndGetKey(jdbc, "INSERT INTO graph_entity (kb_id, name, entity_type, description, created_at) VALUES (?,?,?,?,?)",
                e.getKbId(), e.getName(), e.getEntityType() == null ? "" : e.getEntityType(),
                e.getDescription() == null ? "" : e.getDescription(), now);
    }

    public GraphEntity findEntityByName(long kbId, String name) {
        List<GraphEntity> list = jdbc.query("SELECT " + ENTITY_COLS + " FROM graph_entity WHERE kb_id=? AND name=?",
                new BeanPropertyRowMapper<>(GraphEntity.class), kbId, name);
        return list.isEmpty() ? null : list.get(0);
    }

    public GraphEntity findEntityById(long id) {
        List<GraphEntity> list = jdbc.query("SELECT " + ENTITY_COLS + " FROM graph_entity WHERE id=?",
                new BeanPropertyRowMapper<>(GraphEntity.class), id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<GraphEntity> searchEntitiesByName(long kbId, String like) {
        return jdbc.query("SELECT " + ENTITY_COLS + " FROM graph_entity WHERE kb_id=? AND name LIKE ? LIMIT 50",
                new BeanPropertyRowMapper<>(GraphEntity.class), kbId, "%" + like + "%");
    }

    public void updateEntity(GraphEntity e) {
        jdbc.update("UPDATE graph_entity SET entity_type=?, description=? WHERE id=?",
                e.getEntityType() == null ? "" : e.getEntityType(), e.getDescription() == null ? "" : e.getDescription(), e.getId());
    }

    public void saveEntityEmbedding(long entityId, byte[] vec) {
        jdbc.update("UPDATE graph_entity SET embedding=? WHERE id=?", vec, entityId);
    }

    public byte[] loadEntityEmbedding(long entityId) {
        List<byte[]> list = jdbc.query("SELECT embedding FROM graph_entity WHERE id=?", (rs, i) -> rs.getBytes(1), entityId);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<GraphEntity> findAllEntities(long kbId) {
        return jdbc.query("SELECT " + ENTITY_COLS + " FROM graph_entity WHERE kb_id=? ORDER BY id",
                new BeanPropertyRowMapper<>(GraphEntity.class), kbId);
    }

    public long countEntities(long kbId) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM graph_entity WHERE kb_id=?", Long.class, kbId);
        return c == null ? 0 : c;
    }

    // ===== 关系 =====

    public long insertRelation(GraphRelation r) {
        String now = LocalDateTime.now().format(FMT);
        return JdbcUtil.insertAndGetKey(jdbc, "INSERT INTO graph_relation (kb_id, source_id, target_id, relation_type, description, created_at) VALUES (?,?,?,?,?,?)",
                r.getKbId(), r.getSourceId(), r.getTargetId(),
                r.getRelationType() == null ? "" : r.getRelationType(),
                r.getDescription() == null ? "" : r.getDescription(), now);
    }

    public GraphRelation findRelation(long kbId, long sourceId, long targetId, String type) {
        List<GraphRelation> list = jdbc.query("SELECT " + REL_COLS + " FROM graph_relation WHERE kb_id=? AND source_id=? AND target_id=? AND relation_type=?",
                new BeanPropertyRowMapper<>(GraphRelation.class), kbId, sourceId, targetId, type == null ? "" : type);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<GraphRelation> relationsOfEntity(long kbId, long entityId) {
        return jdbc.query("SELECT " + REL_COLS + " FROM graph_relation WHERE kb_id=? AND (source_id=? OR target_id=?)",
                new BeanPropertyRowMapper<>(GraphRelation.class), kbId, entityId, entityId);
    }

    public List<GraphRelation> findAllRelations(long kbId) {
        return jdbc.query("SELECT " + REL_COLS + " FROM graph_relation WHERE kb_id=? ORDER BY id",
                new BeanPropertyRowMapper<>(GraphRelation.class), kbId);
    }

    public long countRelations(long kbId) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM graph_relation WHERE kb_id=?", Long.class, kbId);
        return c == null ? 0 : c;
    }

    // ===== 实体-片段 溯源 =====

    public void linkEntityChunk(long entityId, long chunkId) {
        jdbc.update("INSERT OR IGNORE INTO entity_chunk (entity_id, chunk_id) VALUES (?,?)", entityId, chunkId);
    }

    public List<Long> chunkIdsOfEntity(long entityId) {
        return jdbc.queryForList("SELECT chunk_id FROM entity_chunk WHERE entity_id=?", Long.class, entityId);
    }

    public void deleteEntityChunkByChunkIds(List<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        for (long cid : chunkIds) {
            jdbc.update("DELETE FROM entity_chunk WHERE chunk_id=?", cid);
        }
    }

    // ===== 级联 =====

    public void deleteByKb(long kbId) {
        jdbc.update("DELETE FROM graph_entity WHERE kb_id=?", kbId);
        jdbc.update("DELETE FROM graph_relation WHERE kb_id=?", kbId);
        jdbc.update("DELETE FROM entity_chunk WHERE entity_id NOT IN (SELECT id FROM graph_entity)");
    }
}
