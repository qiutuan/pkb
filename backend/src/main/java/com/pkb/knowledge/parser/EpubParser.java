package com.pkb.knowledge.parser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * EPUB：读取 container.xml → content.opf → spine 顺序提取各章节 HTML 文本。
 */
public class EpubParser implements DocumentParser {

    @Override
    public String parse(Path file) throws IOException {
        Map<String, byte[]> entries = readZip(file);
        String opfPath = findOpfPath(entries);
        if (opfPath == null) {
            throw new IOException("EPUB 缺少 content.opf");
        }
        String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
        String opfXml = new String(entries.getOrDefault(opfPath, new byte[0]), StandardCharsets.UTF_8);
        List<String> hrefs = parseSpineHrefs(opfXml);
        String title = parseTitle(opfXml);

        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append("# ").append(title.trim()).append("\n");
        }
        int idx = 1;
        for (String href : hrefs) {
            String key = resolve(opfDir, href);
            byte[] data = entries.get(key);
            if (data == null) {
                continue;
            }
            Document doc = Jsoup.parse(new String(data, StandardCharsets.UTF_8));
            doc.select("script,style,noscript,iframe").remove();
            Element body = doc.body();
            if (body == null) {
                continue;
            }
            String t = doc.title();
            sb.append("\n## 第 ").append(idx).append(" 章").append(t != null && !t.isBlank() ? "：" + t.trim() : "").append("\n");
            sb.append(body.text()).append("\n");
            idx++;
        }
        return sb.toString();
    }

    private Map<String, byte[]> readZip(Path file) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(file))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    byte[] data = zis.readAllBytes();
                    out.put(e.getName(), data);
                }
            }
        }
        return out;
    }

    private String findOpfPath(Map<String, byte[]> entries) {
        byte[] container = entries.get("META-INF/container.xml");
        if (container != null) {
            String xml = new String(container, StandardCharsets.UTF_8);
            int i = xml.indexOf("full-path=");
            if (i >= 0) {
                String rest = xml.substring(i);
                int a = rest.indexOf('"');
                int b = a >= 0 ? rest.indexOf('"', a + 1) : -1;
                if (a >= 0 && b > a) {
                    return rest.substring(a + 1, b);
                }
            }
        }
        for (String k : entries.keySet()) {
            if (k.endsWith(".opf")) {
                return k;
            }
        }
        return null;
    }

    private List<String> parseSpineHrefs(String opfXml) {
        List<String> out = new ArrayList<>();
        Map<String, String> id2href = new LinkedHashMap<>();
        // manifest: id -> href
        java.util.regex.Pattern m = java.util.regex.Pattern.compile("<item\\b[^>]*?>", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher mm = m.matcher(opfXml);
        while (mm.find()) {
            String tag = mm.group();
            String id = attr(tag, "id");
            String href = attr(tag, "href");
            if (id != null && href != null) {
                id2href.put(id, href);
            }
        }
        // spine: idref 顺序
        java.util.regex.Pattern s = java.util.regex.Pattern.compile("<itemref\\b[^>]*?>", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher sm = s.matcher(opfXml);
        while (sm.find()) {
            String ref = attr(sm.group(), "idref");
            if (ref != null && id2href.containsKey(ref)) {
                out.add(id2href.get(ref));
            }
        }
        return out;
    }

    private String parseTitle(String opfXml) {
        java.util.regex.Pattern t = java.util.regex.Pattern.compile("<dc:title[^>]*>(.*?)</dc:title>", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher tm = t.matcher(opfXml);
        return tm.find() ? tm.group(1).trim() : null;
    }

    private String attr(String tag, String name) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(name + "\\s*=\\s*\"([^\"]*)\"", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private String resolve(String dir, String href) {
        String h = href.split("#")[0];
        if (h.startsWith("/")) {
            return h.substring(1);
        }
        return dir + h;
    }
}
