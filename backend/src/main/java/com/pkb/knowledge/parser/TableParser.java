package com.pkb.knowledge.parser;

import com.pkb.util.JsonUtil;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel（xlsx/xls）与 CSV 解析。
 * 表格转文本：每个 sheet 转 Markdown 表格（表头保留），超过 1000 行自动分批；
 * 逐行 JSON：每行一条 JSON（列名→值），适合结构化问答。
 */
public class TableParser implements ChunkedParser {

    /** 单个 sheet 超过该行数自动分批 */
    public static final int BATCH_ROWS = 1000;

    @Override
    public String parse(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".csv")) {
            return csvToMarkdown(file, null);
        }
        try (InputStream in = Files.newInputStream(file);
             Workbook wb = name.endsWith(".xls") ? new HSSFWorkbook(in) : new XSSFWorkbook(in)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                sb.append(sheetToMarkdown(wb.getSheetAt(i), null));
            }
            return sb.toString();
        } catch (Exception e) {
            if (e instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("Excel 解析失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<String> parseChunks(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".csv")) {
            return csvToJsonLines(file);
        }
        try (InputStream in = Files.newInputStream(file);
             Workbook wb = name.endsWith(".xls") ? new HSSFWorkbook(in) : new XSSFWorkbook(in)) {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                out.addAll(sheetToJsonLines(wb.getSheetAt(i)));
            }
            return out;
        } catch (Exception e) {
            if (e instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("Excel 解析失败：" + e.getMessage(), e);
        }
    }

    /** 单 sheet → Markdown 表格（每批 1000 行重复表头） */
    private String sheetToMarkdown(Sheet sheet, String sheetName) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n### Sheet: ").append(sheetName == null ? sheet.getSheetName() : sheetName).append("\n");
        List<List<String>> rows = readSheet(sheet);
        if (rows.isEmpty()) {
            return sb.append("（空表）\n").toString();
        }
        List<String> header = rows.get(0);
        List<List<String>> data = rows.subList(1, rows.size());
        int batchSize = Math.max(1, BATCH_ROWS);
        for (int start = 0; start < data.size(); start += batchSize) {
            List<List<String>> batch = data.subList(start, Math.min(start + batchSize, data.size()));
            sb.append(markdownTable(header, batch)).append("\n");
        }
        return sb.toString();
    }

    private String markdownTable(List<String> header, List<List<String>> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", header)).append(" |\n");
        sb.append("| ").append(java.util.Collections.nCopies(header.size(), "---").stream().collect(java.util.stream.Collectors.joining(" | "))).append(" |\n");
        for (List<String> row : data) {
            sb.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        return sb.toString();
    }

    /** 单 sheet → 逐行 JSON */
    private List<String> sheetToJsonLines(Sheet sheet) {
        List<String> out = new ArrayList<>();
        List<List<String>> rows = readSheet(sheet);
        if (rows.isEmpty()) {
            return out;
        }
        List<String> header = rows.get(0);
        List<List<String>> data = rows.subList(1, rows.size());
        for (List<String> row : data) {
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("sheet", sheet.getSheetName());
            for (int i = 0; i < header.size(); i++) {
                obj.put(header.get(i), i < row.size() ? row.get(i) : "");
            }
            out.add(JsonUtil.toJson(obj));
        }
        return out;
    }

    private List<List<String>> readSheet(Sheet sheet) {
        List<List<String>> rows = new ArrayList<>();
        int first = sheet.getFirstRowNum();
        int last = sheet.getLastRowNum();
        for (int r = first; r <= last; r++) {
            Row row = sheet.getRow(r);
            List<String> cells = new ArrayList<>();
            if (row == null) {
                rows.add(cells);
                continue;
            }
            int cc = row.getLastCellNum();
            for (int c = 0; c < Math.max(0, cc); c++) {
                cells.add(cellText(row.getCell(c)));
            }
            rows.add(cells);
        }
        return trimEmptyTail(rows);
    }

    private List<List<String>> trimEmptyTail(List<List<String>> rows) {
        int end = rows.size();
        while (end > 0 && rows.get(end - 1).stream().allMatch(String::isBlank)) {
            end--;
        }
        return rows.subList(0, end);
    }

    private String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        try {
            if (cell.getCellType() == CellType.NUMERIC && org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate().toString();
            }
        } catch (Exception ignored) {
        }
        String v;
        try {
            v = switch (cell.getCellType()) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> {
                    double d = cell.getNumericCellValue();
                    yield d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
                }
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                case FORMULA -> String.valueOf(cell.getNumericCellValue());
                default -> "";
            };
        } catch (Exception e) {
            v = "";
        }
        return v == null ? "" : v.replace("\n", " ").replace("|", "\\|").trim();
    }

    /* ===== CSV ===== */

    private Charset csvCharset(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(3);
            if (head.length >= 3 && head[0] == (byte) 0xEF && head[1] == (byte) 0xBB && head[2] == (byte) 0xBF) {
                return StandardCharsets.UTF_8;
            }
        }
        String s = Files.readString(file, StandardCharsets.UTF_8);
        return s.contains("\uFFFD") ? Charset.forName("GBK") : StandardCharsets.UTF_8;
    }

    private String csvToMarkdown(Path file, String sheetName) throws IOException {
        List<List<String>> rows = readCsv(file);
        if (rows.isEmpty()) {
            return "### Sheet: " + (sheetName == null ? "CSV" : sheetName) + "\n（空表）\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n### Sheet: ").append(sheetName == null ? "CSV" : sheetName).append("\n");
        List<String> header = rows.get(0);
        List<List<String>> data = rows.subList(1, rows.size());
        for (int start = 0; start < data.size(); start += BATCH_ROWS) {
            List<List<String>> batch = data.subList(start, Math.min(start + BATCH_ROWS, data.size()));
            sb.append(markdownTable(header, batch)).append("\n");
        }
        return sb.toString();
    }

    private List<String> csvToJsonLines(Path file) throws IOException {
        List<List<String>> rows = readCsv(file);
        List<String> out = new ArrayList<>();
        if (rows.isEmpty()) {
            return out;
        }
        List<String> header = rows.get(0);
        for (List<String> row : rows.subList(1, rows.size())) {
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("sheet", "CSV");
            for (int i = 0; i < header.size(); i++) {
                obj.put(header.get(i), i < row.size() ? row.get(i) : "");
            }
            out.add(JsonUtil.toJson(obj));
        }
        return out;
    }

    private List<List<String>> readCsv(Path file) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        Charset cs = csvCharset(file);
        try (BufferedReader r = Files.newBufferedReader(file, cs)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                rows.add(splitCsv(line));
            }
        }
        int end = rows.size();
        while (end > 0 && rows.get(end - 1).stream().allMatch(String::isBlank)) {
            end--;
        }
        return rows.subList(0, end);
    }

    /** 简易 CSV 行切分：支持双引号包裹与转义 */
    private List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQ) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQ = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                inQ = true;
            } else if (ch == ',') {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString().trim());
        return out;
    }
}
