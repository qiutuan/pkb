package com.pkb.vector.embedded;

import com.pkb.util.TextUtil;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HnswIndexTest {

    @Test
    void recallShouldBeHigh() {
        Random rnd = new Random(7);
        int n = 3000;
        int dim = 128;
        HnswIndex index = new HnswIndex(16, 200, 128, 42L);
        for (int i = 0; i < n; i++) {
            index.insert(i, TextUtil.normalize(randomVec(rnd, dim)));
        }
        // 扁平检索做基准（与 HNSW 使用同一随机序列，保证数据一致）
        FlatIndex flat = new FlatIndex();
        Random r2 = new Random(7);
        for (int i = 0; i < n; i++) {
            flat.insert(i, TextUtil.normalize(randomVec(r2, dim)));
        }

        int hits = 0;
        int total = 50;
        for (int q = 0; q < total; q++) {
            float[] query = TextUtil.normalize(randomVec(new Random(1000 + q), dim));
            List<Index.Hit> hnsw = index.search(query, 10);
            List<Index.Hit> flatRes = flat.search(query, 10);
            Set<Long> flatIds = new HashSet<>();
            flatRes.forEach(h -> flatIds.add(h.id()));
            for (Index.Hit h : hnsw) {
                if (flatIds.contains(h.id())) {
                    hits++;
                }
            }
        }
        double recall = (double) hits / (total * 10);
        assertTrue(recall >= 0.95, "HNSW top10 召回率过低: " + recall);
    }

    private static float[] randomVec(Random r, int dim) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = (float) r.nextGaussian();
        }
        return v;
    }
}
