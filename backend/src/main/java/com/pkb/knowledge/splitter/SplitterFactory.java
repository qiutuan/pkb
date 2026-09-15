package com.pkb.knowledge.splitter;

/**
 * 分块器工厂。
 */
public final class SplitterFactory {

    private SplitterFactory() {
    }

    public static ChunkSplitter create(String strategy, int size, int overlap) {
        if ("parent_child".equalsIgnoreCase(strategy)) {
            return new ParentChildSplitter(size, overlap);
        }
        return "paragraph".equalsIgnoreCase(strategy)
                ? new ParagraphSplitter(size, overlap)
                : new FixedSizeSplitter(size, overlap);
    }
}
