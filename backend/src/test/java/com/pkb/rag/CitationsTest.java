package com.pkb.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CitationsTest {

    private RetrievedChunk chunk(long n, String docName) {
        return new RetrievedChunk(n, 1L, 1L, docName, (int) n, "内容内容内容内容内容内容内容内容内容内容内容内容", 0.9, "upload");
    }

    @Test
    void parsesInRangeCitations() {
        List<RetrievedChunk> chunks = List.of(chunk(1, "a.txt"), chunk(2, "b.txt"));
        List<java.util.Map<String, Object>> out = ChatService.citations("参考 [1] 与 [2] 的结论。", chunks);
        assertEquals(2, out.size());
    }

    @Test
    void ignoresOutOfRangeAndNonNumeric() {
        List<RetrievedChunk> chunks = List.of(chunk(1, "a.txt"));
        List<java.util.Map<String, Object>> out = ChatService.citations("引用 [1] 与 [5] 与 [abc] 与 [0]。", chunks);
        assertEquals(1, out.size());
    }

    @Test
    void deduplicatesRepeatedIndex() {
        List<RetrievedChunk> chunks = List.of(chunk(1, "a.txt"));
        List<java.util.Map<String, Object>> out = ChatService.citations("重复引用 [1] 和 [1]。", chunks);
        assertEquals(1, out.size());
    }

    @Test
    void emptyTextOrChunksReturnsEmpty() {
        assertTrue(ChatService.citations(null, List.of(chunk(1, "a.txt"))).isEmpty());
        assertTrue(ChatService.citations("hello", List.of()).isEmpty());
    }

    @Test
    void previewTruncatedTo200Chars() {
        StringBuilder longContent = new StringBuilder("c");
        for (int i = 0; i < 300; i++) {
            longContent.append("中");
        }
        RetrievedChunk c = new RetrievedChunk(1L, 1L, 1L, "a.txt", 0, longContent.toString(), 0.9, "upload");
        List<java.util.Map<String, Object>> out = ChatService.citations("[1]", List.of(c));
        assertEquals(1, out.size());
        String content = (String) out.get(0).get("content");
        assertEquals(200, content.length());
    }
}
