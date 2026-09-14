package com.pkb.model;

import com.pkb.util.TextUtil;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingRequest;
import dev.langchain4j.model.embedding.response.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置本地向量模型（离线，零依赖）：
 * 中文 n-gram（单字/二元/三元）+ 英文词袋，带高频停用过滤与类型加权，
 * 投影到 512 维并归一化。相关文本余弦相似度约 0.3~0.55，无关文本约 0.05~0.15。
 * 用于开箱即用、离线演示与流水线自测；生产使用建议切换为 bge-m3 等中文 Embedding 模型。
 */
public class LocalEmbeddingModel implements EmbeddingModel {

    public static final int DIM = 512;
    public static final String MODEL_NAME = "local-embed-v2";

    @Override
    public EmbeddingResponse doEmbed(EmbeddingRequest request) {
        List<Embedding> out = new ArrayList<>();
        for (var input : request.inputs()) {
            out.add(new Embedding(embedVec(input.text())));
        }
        return EmbeddingResponse.builder().embeddings(out).build();
    }

    @Override
    public int dimension() {
        return DIM;
    }

    public static float[] embedVec(String text) {
        float[] vec = new float[DIM];
        for (String t : TextUtil.tokenize(text == null ? "" : text)) {
            int h = t.hashCode();
            int idx = Math.floorMod(h, DIM);
            double w;
            char kind = t.charAt(0);
            if (kind == 'c') {
                char lvl = t.charAt(1);
                if (lvl == '1') {
                    w = 0.6;          // 单字
                } else if (lvl == '2') {
                    w = 1.0;          // 二元组
                } else {
                    w = 1.5;          // 三元组
                }
            } else if (kind == 'w') {
                w = Math.min(2.5, 0.8 + (t.length() - 1) * 0.25);  // 英文/数字词，越长越特异
            } else {
                w = 1.0;
            }
            vec[idx] += (h < 0 ? -w : w);
        }
        return TextUtil.normalize(vec);
    }
}
