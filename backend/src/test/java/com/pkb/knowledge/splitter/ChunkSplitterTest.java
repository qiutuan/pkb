package com.pkb.knowledge.splitter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSplitterTest {

    @Test
    void fixedSizeKeepsBoundAndOverlap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("这是用于测试分块的中文文本内容，包含一些信息。");
        }
        String text = sb.toString();
        List<String> chunks = new FixedSizeSplitter(200, 50).split(text);
        assertTrue(chunks.size() > 3);
        for (String c : chunks) {
            assertTrue(c.length() <= 220, "分块超长: " + c.length());
        }
    }

    @Test
    void paragraphSplitterWorks() {
        String text = "第一段内容。\n\n第二段内容，讲一些事情。\n\n第三段，也是最后一段。";
        List<String> chunks = new ParagraphSplitter(200, 20).split(text);
        // 小段落会被合并至阈值
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("第一段内容。"));
        assertTrue(chunks.get(0).contains("第三段"));
    }

    @Test
    void paragraphSplitterSplitsOversized() {
        String big = "A".repeat(400);
        List<String> chunks = new ParagraphSplitter(200, 20).split(big);
        assertTrue(chunks.size() >= 2);
        for (String c : chunks) {
            assertTrue(c.length() <= 220);
        }
    }

    @Test
    void blankInputReturnsEmpty() {
        assertTrue(new FixedSizeSplitter(100, 10).split("  \n ").isEmpty());
        assertTrue(new ParagraphSplitter(100, 10).split(null).isEmpty());
    }
}
