package com.pkb.rag;

/**
 * 检索结果片段（含来源标注：vector=向量检索，graph=图谱召回）。
 */
public record RetrievedChunk(
        long chunkId,
        long kbId,
        long docId,
        String docName,
        int position,
        String content,
        double score,
        String source) {
}
