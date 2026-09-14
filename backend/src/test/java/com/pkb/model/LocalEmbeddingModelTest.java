package com.pkb.model;

import com.pkb.util.TextUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalEmbeddingModelTest {

    @Test
    void dimensionAndNormalization() {
        float[] v = LocalEmbeddingModel.embedVec("知识图谱与向量检索的混合检索");
        assertEquals(LocalEmbeddingModel.DIM, v.length);
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        assertTrue(Math.abs(norm - 1.0) < 1e-3, "向量未归一化: " + norm);
    }

    @Test
    void similarTextsCloserThanDissimilar() {
        float[] a = LocalEmbeddingModel.embedVec("深度学习是机器学习的一个分支");
        float[] b = LocalEmbeddingModel.embedVec("深度学习依赖于神经网络");
        float[] c = LocalEmbeddingModel.embedVec("今天天气很好适合出门散步");
        double ab = TextUtil.cosine(a, b);
        double ac = TextUtil.cosine(a, c);
        assertTrue(ab > ac, "相似文本应更接近: " + ab + " vs " + ac);
    }
}
