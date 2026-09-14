package com.pkb.vector.embedded;

import com.pkb.util.TextUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * HNSW（Hierarchical Navigable Small World）图索引——自研实现，零外部依赖。
 * <p>
 * - 节点按随机几何级别分层，高层稀疏、低层稠密；
 * - 插入时逐层贪心搜索 + 双向连接，邻居列表按距离裁剪；
 * - 检索时自顶向下逼近，底层以 efSearch 候选集扩展后按余弦返回 topK。
 * <p>
 * 向量在入库前已 L2 归一化，因此内部使用欧氏距离近似余弦（dot = 1 - d²/2），
 * 最终分数换算为余弦相似度。
 */
public class HnswIndex implements Index {

    private final int m;
    private final int efConstruction;
    private final int efSearch;
    private final double mL;
    private final Random random;

    private final ConcurrentHashMap<Long, Node> nodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, float[]> vecs = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile long entryId = -1;
    private volatile int entryLevel = -1;

    private static final int MAX_LEVEL_CAP = 16;

    private static class Node {
        final long id;
        final int level;
        final List<List<Long>> neighbors;

        Node(long id, int level) {
            this.id = id;
            this.level = level;
            this.neighbors = new ArrayList<>(level + 1);
            for (int i = 0; i <= level; i++) {
                neighbors.add(new ArrayList<>());
            }
        }
    }

    private record Candidate(long id, float dist) {
    }

    public HnswIndex(int m, int efConstruction, int efSearch, long seed) {
        this.m = Math.max(2, m);
        this.efConstruction = Math.max(10, efConstruction);
        this.efSearch = Math.max(10, efSearch);
        this.mL = 1.0 / Math.log(this.m);
        this.random = new Random(seed);
    }

    @Override
    public void insert(long id, float[] normalizedVec) {
        lock.writeLock().lock();
        try {
            if (vecs.containsKey(id)) {
                return;
            }
            vecs.put(id, normalizedVec);
            int level = randomLevel();
            Node node = new Node(id, level);
            nodes.put(id, node);

            if (entryId < 0) {
                entryId = id;
                entryLevel = level;
                return;
            }

            // 自顶向下贪心逼近到 level+1 层
            long curId = entryId;
            float curDist = dist(normalizedVec, vecs.get(curId));
            for (int l = entryLevel; l > level; l--) {
                curId = greedyStep(normalizedVec, curId, l, curDist).id;
                curDist = dist(normalizedVec, vecs.get(curId));
            }

            // 逐层连接
            for (int l = Math.min(level, entryLevel); l >= 0; l--) {
                List<Candidate> candidates = searchLayer(normalizedVec, l, efConstruction, curId, new HashSet<>());
                List<Long> selected = selectNeighborsHeuristic(normalizedVec, candidates, m);
                List<Long> myNeighbors = node.neighbors.get(l);
                for (long nid : selected) {
                    if (nid == id) {
                        continue;
                    }
                    myNeighbors.add(nid);
                    Node other = nodes.get(nid);
                    if (other == null) {
                        continue;
                    }
                    List<Long> otherNeighbors = other.neighbors.get(l);
                    otherNeighbors.add(id);
                    int maxN = l == 0 ? m * 2 : m;
                    if (otherNeighbors.size() > maxN) {
                        List<Long> pruned = selectNeighborsHeuristic(vecs.get(nid), toCandidates(vecs.get(nid), otherNeighbors), maxN);
                        otherNeighbors.clear();
                        otherNeighbors.addAll(pruned);
                    }
                }
                // 下一层从当前层最近邻继续
                if (!selected.isEmpty()) {
                    long bestId = selected.get(0);
                    float best = dist(normalizedVec, vecs.get(bestId));
                    for (long nid : selected) {
                        float d = dist(normalizedVec, vecs.get(nid));
                        if (d < best) {
                            best = d;
                            bestId = nid;
                        }
                    }
                    curId = bestId;
                    curDist = best;
                }
            }

            if (level > entryLevel) {
                entryId = id;
                entryLevel = level;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static class GreedyResult {
        final long id;
        final float dist;

        GreedyResult(long id, float dist) {
            this.id = id;
            this.dist = dist;
        }
    }

    private GreedyResult greedyStep(float[] q, long curId, int layer, float curDist) {
        boolean changed = true;
        while (changed) {
            changed = false;
            Node node = nodes.get(curId);
            if (node == null) {
                break;
            }
            List<Long> neigh = node.neighbors.get(layer);
            for (long nid : neigh) {
                float[] v = vecs.get(nid);
                if (v == null) {
                    continue;
                }
                float d = dist(q, v);
                if (d < curDist) {
                    curDist = d;
                    curId = nid;
                    changed = true;
                }
            }
        }
        return new GreedyResult(curId, curDist);
    }

    private List<Candidate> searchLayer(float[] q, int layer, int ef, long startId, Set<Long> visited) {
        PriorityQueue<Candidate> candidates = new PriorityQueue<>((a, b) -> Float.compare(a.dist, b.dist));
        PriorityQueue<Candidate> results = new PriorityQueue<>((a, b) -> Float.compare(b.dist, a.dist));
        float sd = dist(q, vecs.get(startId));
        Candidate start = new Candidate(startId, sd);
        candidates.add(start);
        results.add(start);
        visited.add(startId);

        while (!candidates.isEmpty()) {
            Candidate c = candidates.poll();
            Candidate worst = results.peek();
            if (worst != null && c.dist > worst.dist) {
                break;
            }
            Node node = nodes.get(c.id);
            if (node == null) {
                continue;
            }
            List<Long> neigh = node.neighbors.get(layer);
            for (long nid : neigh) {
                if (!visited.add(nid)) {
                    continue;
                }
                float[] v = vecs.get(nid);
                if (v == null) {
                    continue;
                }
                float d = dist(q, v);
                Candidate cand = new Candidate(nid, d);
                if (results.size() < ef || d < results.peek().dist) {
                    candidates.add(cand);
                    results.add(cand);
                    if (results.size() > ef) {
                        results.poll();
                    }
                }
            }
        }
        return new ArrayList<>(results);
    }

    @Override
    public List<Hit> search(float[] query, int topK) {
        lock.readLock().lock();
        try {
            if (entryId < 0 || nodes.isEmpty()) {
                return List.of();
            }
            int ef = Math.max(efSearch, topK);
            long curId = entryId;
            float curDist = dist(query, vecs.get(curId));
            for (int l = entryLevel; l > 0; l--) {
                GreedyResult r = greedyStep(query, curId, l, curDist);
                curId = r.id;
                curDist = r.dist;
            }
            List<Candidate> candidates = searchLayer(query, 0, ef, curId, new HashSet<>());
            candidates.sort(Comparator.comparingDouble(Candidate::dist));
            List<Hit> hits = new ArrayList<>();
            for (int i = 0; i < Math.min(topK, candidates.size()); i++) {
                Candidate c = candidates.get(i);
                float score = (float) (1.0 - (c.dist * c.dist) / 2.0);
                hits.add(new Hit(c.id, Math.max(score, 0f)));
            }
            return hits;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void remove(long id) {
        lock.writeLock().lock();
        try {
            vecs.remove(id);
            nodes.remove(id);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int size() {
        return vecs.size();
    }

    private List<Candidate> toCandidates(float[] center, List<Long> ids) {
        List<Candidate> out = new ArrayList<>(ids.size());
        for (long id : ids) {
            float[] v = vecs.get(id);
            out.add(new Candidate(id, v == null ? Float.MAX_VALUE : dist(center, v)));
        }
        return out;
    }

    /**
     * 标准 HNSW 多样性启发式选邻居：
     * 优先选取与查询更近、且彼此足够分散的节点，显著提升召回率。
     */
    private List<Long> selectNeighborsHeuristic(float[] query, List<Candidate> candidates, int k) {
        List<Candidate> queue = new ArrayList<>(candidates);
        queue.sort(Comparator.comparingDouble(Candidate::dist));
        List<Long> out = new ArrayList<>();
        for (Candidate c : queue) {
            if (out.size() >= k) {
                break;
            }
            float[] v = vecs.get(c.id);
            if (v == null) {
                continue;
            }
            boolean redundant = false;
            for (long oid : out) {
                float[] ov = vecs.get(oid);
                if (ov != null && dist(v, ov) < c.dist) {
                    redundant = true;
                    break;
                }
            }
            if (!redundant) {
                out.add(c.id);
            }
        }
        // 启发式不足 k 个时按距离补齐
        if (out.size() < k) {
            for (Candidate c : queue) {
                if (out.size() >= k) {
                    break;
                }
                if (!out.contains(c.id) && vecs.get(c.id) != null) {
                    out.add(c.id);
                }
            }
        }
        return out;
    }

    private int randomLevel() {
        int level = (int) (-Math.log(random.nextDouble()) * mL);
        return Math.min(level, MAX_LEVEL_CAP);
    }

    private static float dist(float[] a, float[] b) {
        return TextUtil.l2Distance(a, b);
    }
}
