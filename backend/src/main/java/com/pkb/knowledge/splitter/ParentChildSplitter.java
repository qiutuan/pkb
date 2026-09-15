package com.pkb.knowledge.splitter;

import java.util.ArrayList;
import java.util.List;

/**
 * 父子分块：先按父块大小切出大块，每个父块再切小子块。
 * 检索命中小子块（向量索引），返回时展开为其父块完整内容（见 DocumentPipelineService / RetrievalService）。
 */
public class ParentChildSplitter implements ChunkSplitter {

    private final FixedSizeSplitter parentSplitter;
    private final FixedSizeSplitter childSplitter;

    public ParentChildSplitter(int parentSize, int overlap) {
        this.parentSplitter = new FixedSizeSplitter(parentSize, overlap);
        int childSize = Math.max(120, parentSize / 3);
        int childOverlap = Math.min(overlap, childSize / 2);
        this.childSplitter = new FixedSizeSplitter(childSize, childOverlap);
    }

    /** 父块大小（构造参数校验用） */
    public int parentSize() {
        return parentSplitter.size();
    }

    public int childSize() {
        return childSplitter.size();
    }

    @Override
    public List<String> split(String text) {
        // 兼容 ChunkSplitter 接口：返回子块文本（父块信息在 splitWithParents 中）
        return splitWithParents(text).stream().map(ParentChild::child).toList();
    }

    /** 返回子块 + 其父块全文 */
    public List<ParentChild> splitWithParents(String text) {
        List<ParentChild> out = new ArrayList<>();
        List<String> parents = parentSplitter.split(text);
        for (String parent : parents) {
            List<String> children = childSplitter.split(parent);
            if (children.isEmpty()) {
                out.add(new ParentChild(parent, parent));
            } else {
                for (String child : children) {
                    out.add(new ParentChild(child, parent));
                }
            }
        }
        return out;
    }

    /** 子块 + 父块 */
    public record ParentChild(String child, String parent) {
    }
}
