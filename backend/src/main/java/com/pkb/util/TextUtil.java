package com.pkb.util;

import org.apache.commons.lang3.StringUtils;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本工具：中英文分词（字符二元组 + 英文单词）、归一化、相似度、文件类型判断。
 */
public final class TextUtil {

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fa5]");
    private static final Pattern WORD = Pattern.compile("[a-zA-Z0-9]+");
    private static final Set<String> IMAGE_EXT = Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "ico", "tiff");
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "mov", "avi", "mkv", "webm", "flv", "wmv", "m4v", "ts");

    /** 高频虚字（做轻量停用，降低无关文本的向量相似度） */
    private static final String STOP_CHARS = "的一是在了有和与就这那也都而及或把被很个中上下为等对从到于之以其所者能会可要还但并";
    /** 高频虚词二元组 */
    private static final Set<String> STOP_BIGRAMS = Set.of(
            "可以", "我们", "你们", "他们", "它们", "这个", "那个", "一个", "没有", "进行",
            "以及", "但是", "因为", "所以", "如果", "就是", "什么", "怎么", "时候", "自己",
            "现在", "已经", "通过", "对于", "还有", "不是", "一些", "相关", "然后", "这样",
            "那样", "起来", "下来", "出来", "过去", "东西", "地方", "事情", "情况", "需要",
            "使用", "能够", "更加", "非常", "主要", "包括", "关于", "由于", "因此", "其中");

    private TextUtil() {
    }

    /**
     * 中英文分词（带类型前缀，供向量化与关键词打分复用）：
     * 英文单词 w…、中文单字 c1…、二元组 c2…、三元组 c3…。
     * 单字与二元组做高频停用过滤，降低泛化噪声。
     */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            tokens.add("w" + m.group().toLowerCase(Locale.ROOT));
        }
        String cjk = text.replaceAll("[^\\u4e00-\\u9fa5]", "");
        int n = cjk.length();
        for (int i = 0; i < n; i++) {
            char ch = cjk.charAt(i);
            if (STOP_CHARS.indexOf(ch) < 0) {
                tokens.add("c1" + ch);
            }
            if (i + 1 < n) {
                String bg = cjk.substring(i, i + 2);
                if (!STOP_BIGRAMS.contains(bg)) {
                    tokens.add("c2" + bg);
                }
            }
            if (i + 2 < n) {
                tokens.add("c3" + cjk.substring(i, i + 3));
            }
        }
        return tokens;
    }

    /** 词频统计（用于 BM25 风格打分） */
    public static java.util.Map<String, Integer> termFreq(String text) {
        java.util.Map<String, Integer> tf = new java.util.HashMap<>();
        for (String t : tokenize(text)) {
            tf.merge(t, 1, Integer::sum);
        }
        return tf;
    }

    /**
     * 轻量关键词相似度（0~1）：覆盖率 + 词频命中率。
     */
    public static double keywordScore(String query, String text) {
        java.util.Map<String, Integer> q = termFreq(query);
        java.util.Map<String, Integer> c = termFreq(text);
        if (q.isEmpty()) {
            return 0;
        }
        double coverageSum = 0;
        double tfHitSum = 0;
        double tfQuerySum = 0;
        for (var e : q.entrySet()) {
            int cTf = c.getOrDefault(e.getKey(), 0);
            tfQuerySum += Math.min(e.getValue(), 2.0);
            if (cTf > 0) {
                coverageSum += 1;
                tfHitSum += Math.min(cTf, 2.0);
            }
        }
        if (tfQuerySum == 0) {
            return 0;
        }
        double coverage = coverageSum / q.size();
        double tfRatio = tfHitSum / tfQuerySum;
        return 0.7 * coverage + 0.3 * tfRatio;
    }

    /** 余弦相似度（向量需已归一化） */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }

    /** 欧氏距离（用于 HNSW 内部，向量已归一化时与余弦等价） */
    public static float l2Distance(float[] a, float[] b) {
        float sum = 0;
        for (int i = 0; i < a.length; i++) {
            float d = a[i] - b[i];
            sum += d * d;
        }
        return (float) Math.sqrt(sum);
    }

    public static float[] normalize(float[] v) {
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            return v;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }

    /** Levenshtein 相似度（0~1） */
    public static double levenshteinSimilarity(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        if (a.equals(b)) {
            return 1;
        }
        int m = a.length(), n = b.length();
        if (m == 0 || n == 0) {
            return 0;
        }
        int[] prev = new int[n + 1];
        int[] cur = new int[n + 1];
        for (int j = 0; j <= n; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= m; i++) {
            cur[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 1.0 - (double) prev[n] / Math.max(m, n);
    }

    /** 实体名归一化：去除空白、统一大小写、去除标点 */
    public static String normalizeName(String s) {
        if (s == null) {
            return "";
        }
        String out = Normalizer.normalize(s, Normalizer.Form.NFKC);
        out = out.replaceAll("[\\s\\p{Punct}]+", "").toLowerCase(Locale.ROOT);
        return out;
    }

    public static String extOf(String filename) {
        if (filename == null) {
            return "";
        }
        int i = filename.lastIndexOf('.');
        if (i < 0 || i == filename.length() - 1) {
            return "";
        }
        return filename.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isImageFile(String filename) {
        return IMAGE_EXT.contains(extOf(filename));
    }

    public static boolean isVideoFile(String filename) {
        return VIDEO_EXT.contains(extOf(filename));
    }

    public static boolean isMediaFile(String filename) {
        return isImageFile(filename) || isVideoFile(filename);
    }

    public static boolean isTextDoc(String filename) {
        return Set.of("txt", "md", "markdown", "pdf", "docx", "doc").contains(extOf(filename));
    }

    public static String safeFileName(String name) {
        if (StringUtils.isBlank(name)) {
            return "unnamed";
        }
        return name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
    }
}
