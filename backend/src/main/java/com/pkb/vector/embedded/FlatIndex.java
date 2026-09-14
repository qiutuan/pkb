package com.pkb.vector.embedded;

import com.pkb.util.TextUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 扁平索引：暴力余弦检索（小规模精确，默认备选）。
 */
public class FlatIndex implements Index {

    private final Map<Long, float[]> vecs = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @Override
    public void insert(long id, float[] normalizedVec) {
        lock.writeLock().lock();
        try {
            vecs.put(id, normalizedVec);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void remove(long id) {
        lock.writeLock().lock();
        try {
            vecs.remove(id);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<Hit> search(float[] query, int topK) {
        lock.readLock().lock();
        try {
            List<Hit> hits = new ArrayList<>();
            for (var e : vecs.entrySet()) {
                float score = (float) TextUtil.cosine(query, e.getValue());
                if (score > 0) {
                    hits.add(new Hit(e.getKey(), score));
                }
            }
            hits.sort(Comparator.comparingDouble(Hit::score).reversed());
            if (hits.size() > topK) {
                return new ArrayList<>(hits.subList(0, topK));
            }
            return hits;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int size() {
        return vecs.size();
    }
}
