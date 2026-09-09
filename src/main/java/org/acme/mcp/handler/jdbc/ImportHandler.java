package org.acme.mcp.handler.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.jdbc.pool.ConnectionPoolManager;
import org.acme.jdbc.pool.SqlTimeoutConfig;
import org.acme.mcp.handler.McpToolHandler;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 数据导入：支持三种格式
 * 1) inserts —— 导出的 INSERT 语句文件（支持单条/多行 VALUES 元组，自动重写目标表名）
 * 2) csv     —— 首行为列名的 CSV 文件
 * 3) json    —— 对象数组 JSON 文件（表头取所有对象键的并集，缺失键填 NULL）
 * 整体一个事务，全部成功后提交，任一失败回滚。
 */
@ApplicationScoped
public class ImportHandler implements McpToolHandler {

    private static final int DEFAULT_BATCH = 500;

    @Inject
    ConnectionPoolManager poolManager;

    @Inject
    SqlTimeoutConfig timeoutConfig;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public String getToolName() {
        return "jdbc-import";
    }

    @Override
    public String getDescription() {
        return "数据导入：将文件中的数据批量导入指定数据库表，整体一个事务（全部成功才提交）。" +
               "支持格式：inserts（导出的 INSERT 语句文件，自动解析 VALUES 元组并重写目标表名）、" +
               "csv（首行列名）、json（对象数组）。格式默认按文件扩展名/内容自动识别。";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("connectionId", Map.of("type", "string", "description", "连接 ID（必填）"));
        properties.put("filePath", Map.of("type", "string", "description", "待导入文件绝对路径"));
        properties.put("tableName", Map.of("type", "string",
                "description", "目标表名（inserts 格式可选，覆盖原语句中的表名；csv/json 格式必填）"));
        properties.put("format", Map.of("type", "string",
                "description", "格式：inserts / csv / json，默认按扩展名与内容自动识别"));
        properties.put("encoding", Map.of("type", "string", "description", "文件编码，默认 UTF-8"));
        properties.put("delimiter", Map.of("type", "string", "description", "csv 分隔符，默认 ,"));
        properties.put("hasHeader", Map.of("type", "boolean", "description", "csv 首行是否为列名（默认 true）"));
        properties.put("columnNames", Map.of("type", "array",
                "description", "csv/json 列名覆盖（csv 无表头时必填）",
                "items", Map.of("type", "string")));
        properties.put("batchRows", Map.of("type", "integer", "description", "批量提交大小（默认 500）"));
        properties.put("truncateFirst", Map.of("type", "boolean",
                "description", "导入前是否先清空目标表（默认 false；在事务内用 DELETE，可随整体回滚）"));
        properties.put("timeoutSeconds", Map.of("type", "integer", "description", "单条语句超时秒数（默认 180，最大 1800）"));

        schema.put("properties", properties);
        schema.put("required", new String[]{"connectionId", "filePath"});
        return schema;
    }

    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String connectionId = (String) params.get("connectionId");
        String filePath = (String) params.get("filePath");
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("filePath 不能为空");
        }
        String tableName = (String) params.get("tableName");
        String format = params.get("format") == null ? null : params.get("format").toString().toLowerCase(Locale.ROOT);
        String encoding = params.get("encoding") == null ? "UTF-8" : params.get("encoding").toString();
        String delimiter = params.get("delimiter") == null ? "," : params.get("delimiter").toString();
        boolean hasHeader = !Boolean.FALSE.equals(params.get("hasHeader"));
        boolean truncateFirst = Boolean.TRUE.equals(params.get("truncateFirst"));
        int batchRows = params.get("batchRows") instanceof Number ? ((Number) params.get("batchRows")).intValue() : DEFAULT_BATCH;
        if (batchRows <= 0) {
            batchRows = DEFAULT_BATCH;
        }
        int timeoutSeconds = timeoutConfig.resolve(params);

        @SuppressWarnings("unchecked")
        List<String> columnNames = params.get("columnNames") == null
                ? null : (List<String>) params.get("columnNames");

        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }
        String content = new String(Files.readAllBytes(path), Charset.forName(encoding));

        if (format == null || format.isEmpty() || "auto".equals(format)) {
            String lower = filePath.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".sql")) {
                format = "inserts";
            } else if (lower.endsWith(".csv")) {
                format = "csv";
            } else if (lower.endsWith(".json")) {
                format = "json";
            } else {
                String head = content.trim().toUpperCase(Locale.ROOT);
                format = head.startsWith("INSERT") ? "inserts"
                        : head.startsWith("[") ? "json" : "csv";
            }
        }

        try (Connection conn = poolManager.getConnection(connectionId)) {
            conn.setAutoCommit(false);
            long rows;
            String target;
            try {
                if (truncateFirst) {
                    if (tableName == null || tableName.trim().isEmpty()) {
                        throw new IllegalArgumentException("truncateFirst=true 时必须提供 tableName");
                    }
                    try (Statement st = conn.createStatement()) {
                        st.setQueryTimeout(timeoutSeconds);
                        st.executeUpdate("DELETE FROM " + tableName);
                    }
                }
                switch (format) {
                    case "inserts":
                        rows = importInserts(conn, content, tableName, batchRows, timeoutSeconds);
                        target = tableName;
                        break;
                    case "csv":
                        if (tableName == null || tableName.trim().isEmpty()) {
                            throw new IllegalArgumentException("csv 格式必须提供 tableName");
                        }
                        rows = importTabular(conn, tableName,
                                parseCsv(content, delimiter), hasHeader, columnNames, batchRows, timeoutSeconds);
                        target = tableName;
                        break;
                    case "json":
                        if (tableName == null || tableName.trim().isEmpty()) {
                            throw new IllegalArgumentException("json 格式必须提供 tableName");
                        }
                        rows = importTabular(conn, tableName,
                                parseJson(content), true, columnNames, batchRows, timeoutSeconds);
                        target = tableName;
                        break;
                    default:
                        throw new IllegalArgumentException("不支持的 format: " + format);
                }
                conn.commit();
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException ignore) {
                }
                throw e;
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("format", format);
            result.put("target", target);
            result.put("rowsImported", rows);
            result.put("message", "导入成功，已提交 " + rows + " 行");
            return result;
        }
    }

    // ---------- inserts 格式 ----------

    private long importInserts(Connection conn, String content, String tableNameOverride,
                               int batchRows, int timeoutSeconds) throws Exception {
        long total = 0;
        int batch = 0;
        Statement batchStmt = conn.createStatement();
        batchStmt.setQueryTimeout(timeoutSeconds);
        try {
            List<String> statements = splitStatements(content);
            for (String stmt : statements) {
                String trimmed = stmt.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String upper = trimmed.toUpperCase(Locale.ROOT);
                if (!upper.startsWith("INSERT")) {
                    // 忽略非 INSERT 语句（例如尾部残留、注释等）
                    continue;
                }
                ParsedInsert parsed = parseInsert(trimmed);
                String target = tableNameOverride != null && !tableNameOverride.trim().isEmpty()
                        ? tableNameOverride : parsed.target;
                for (String tuple : parsed.tuples) {
                    batchStmt.addBatch("INSERT INTO " + target + " (" + parsed.columns + ") VALUES " + tuple);
                    if (++batch >= batchRows) {
                        total += sum(batchStmt.executeBatch());
                        batch = 0;
                    }
                }
            }
            if (batch > 0) {
                total += sum(batchStmt.executeBatch());
            }
        } finally {
            batchStmt.close();
        }
        return total;
    }

    private static class ParsedInsert {
        String target;
        String columns;
        List<String> tuples;
    }

    /** 解析单条 INSERT 语句：目标表、列名、VALUES 中各顶层元组 */
    private ParsedInsert parseInsert(String stmt) {
        ParsedInsert p = new ParsedInsert();
        int len = stmt.length();
        int i = "INSERT".length();
        // 跳过 INTO 关键字与空白
        while (i < len && Character.isWhitespace(stmt.charAt(i))) i++;
        if (i + 4 <= len && stmt.substring(i, i + 4).equalsIgnoreCase("INTO")) {
            i += 4;
        }
        while (i < len && Character.isWhitespace(stmt.charAt(i))) i++;
        // 目标表名：到第一个顶层 '(' 为止
        int targetStart = i;
        while (i < len && stmt.charAt(i) != '(') i++;
        p.target = stmt.substring(targetStart, i).trim();
        // 第一个括号组 = 列名
        int[] colsRange = readTopLevelGroup(stmt, i);
        p.columns = stmt.substring(colsRange[0] + 1, colsRange[1]).trim();
        // VALUES 之后的所有顶层括号组 = 元组
        String upper = stmt.toUpperCase(Locale.ROOT);
        int valuesIdx = upper.indexOf("VALUES", colsRange[1]);
        if (valuesIdx < 0) {
            throw new IllegalArgumentException("INSERT 语句缺少 VALUES: "
                    + stmt.substring(0, Math.min(120, stmt.length())));
        }
        p.tuples = new ArrayList<>();
        i = valuesIdx + "VALUES".length();
        while (i < len) {
            char c = stmt.charAt(i);
            if (c == '(') {
                int[] range = readTopLevelGroup(stmt, i);
                p.tuples.add(stmt.substring(range[0], range[1] + 1));
                i = range[1] + 1;
            } else {
                i++;
            }
        }
        if (p.tuples.isEmpty()) {
            throw new IllegalArgumentException("INSERT 语句未解析到 VALUES 元组: "
                    + stmt.substring(0, Math.min(120, stmt.length())));
        }
        return p;
    }

    /** 从 start（应为 '('）读取一个顶层括号组，返回 [start, endCloseIndex]，字符串字面量内的括号忽略 */
    private int[] readTopLevelGroup(String s, int start) {
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\'') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inStr = false;
                    }
                }
            } else if (c == '\'') {
                inStr = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return new int[]{start, i};
                }
            }
        }
        throw new IllegalArgumentException("括号不匹配: " + s.substring(start, Math.min(start + 120, s.length())));
    }

    /** 按分号切分 SQL 语句（字符串字面量内的分号忽略） */
    private List<String> splitStatements(String content) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inStr = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inStr) {
                cur.append(c);
                if (c == '\'') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '\'') {
                        cur.append('\'');
                        i++;
                    } else {
                        inStr = false;
                    }
                }
            } else if (c == '\'') {
                inStr = true;
                cur.append(c);
            } else if (c == ';') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.toString().trim().length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    // ---------- csv / json 格式 ----------

    private long importTabular(Connection conn, String tableName, List<List<String>> rows,
                               boolean hasHeader, List<String> columnNamesOverride,
                               int batchRows, int timeoutSeconds) throws Exception {
        if (rows.isEmpty()) {
            return 0;
        }
        List<String> columns;
        List<List<String>> dataRows;
        if (hasHeader) {
            columns = new ArrayList<>();
            for (String h : rows.get(0)) {
                if (h != null && !h.trim().isEmpty()) {
                    columns.add(h.trim());
                }
            }
            dataRows = rows.subList(1, rows.size());
        } else {
            if (columnNamesOverride == null || columnNamesOverride.isEmpty()) {
                throw new IllegalArgumentException("csv 无表头时必须提供 columnNames");
            }
            columns = new ArrayList<>(columnNamesOverride);
            dataRows = rows;
        }
        if (columnNamesOverride != null && !columnNamesOverride.isEmpty() && hasHeader) {
            columns = new ArrayList<>(columnNamesOverride);
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("未能解析出任何列名");
        }

        Map<String, Integer> colTypes = loadColumnTypes(conn, tableName);

        StringBuilder sql = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(',');
            sql.append(columns.get(i));
        }
        sql.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sql.append(',');
            sql.append('?');
        }
        sql.append(')');

        long total = 0;
        int batch = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setQueryTimeout(timeoutSeconds);
            for (List<String> row : dataRows) {
                for (int i = 0; i < columns.size(); i++) {
                    String value = i < row.size() ? row.get(i) : null;
                    bindValue(ps, i + 1, value, colTypes.get(columns.get(i).toUpperCase(Locale.ROOT)));
                }
                ps.addBatch();
                if (++batch >= batchRows) {
                    total += sum(ps.executeBatch());
                    batch = 0;
                }
            }
            if (batch > 0) {
                total += sum(ps.executeBatch());
            }
        }
        return total;
    }

    private List<List<String>> parseCsv(String content, String delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        String d = delimiter.length() == 0 ? "," : delimiter;
        char delim = d.charAt(0);
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"' && field.length() == 0) {
                inQuotes = true;
            } else if (c == delim) {
                cur.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                cur.add(field.toString());
                field.setLength(0);
                if (!isEmptyRow(cur)) {
                    rows.add(cur);
                }
                cur = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !cur.isEmpty()) {
            cur.add(field.toString());
            if (!isEmptyRow(cur)) {
                rows.add(cur);
            }
        }
        return rows;
    }

    private boolean isEmptyRow(List<String> row) {
        return row.stream().allMatch(v -> v == null || v.isEmpty());
    }

    /** 表头取所有对象键的并集（保持首次出现顺序），缺失键填 NULL，避免后续对象多出的字段静默丢失 */
    private List<List<String>> parseJson(String content) throws IOException {
        JsonNode root = objectMapper.readTree(content);
        if (!root.isArray()) {
            throw new IllegalArgumentException("json 格式要求顶层数组");
        }
        List<List<String>> rows = new ArrayList<>();
        Set<String> header = new LinkedHashSet<>();
        for (JsonNode element : root) {
            if (!element.isObject()) {
                throw new IllegalArgumentException("json 数组元素必须是对象");
            }
            element.fieldNames().forEachRemaining(header::add);
        }
        if (header.isEmpty()) {
            throw new IllegalArgumentException("json 数组为空或对象无字段");
        }
        rows.add(new ArrayList<>(header));
        for (JsonNode element : root) {
            List<String> values = new ArrayList<>();
            for (String key : header) {
                JsonNode v = element.get(key);
                values.add(v == null || v.isNull() ? null : v.isValueNode() ? v.asText() : v.toString());
            }
            rows.add(values);
        }
        return rows;
    }

    private void bindValue(PreparedStatement ps, int index, String value, Integer sqlType) throws SQLException {
        if (value == null || value.equalsIgnoreCase("NULL")) {
            ps.setNull(index, sqlType == null ? Types.VARCHAR : sqlType);
            return;
        }
        String v = value.trim();
        if (sqlType != null) {
            try {
                if (sqlType == Types.DATE) {
                    ps.setDate(index, Date.valueOf(v.length() >= 10 ? v.substring(0, 10) : v));
                    return;
                } else if (sqlType == Types.TIMESTAMP || sqlType == Types.TIMESTAMP_WITH_TIMEZONE) {
                    ps.setTimestamp(index, parseTimestamp(v));
                    return;
                }
            } catch (Exception e) {
                // 解析失败回退为字符串绑定
            }
        }
        ps.setObject(index, value);
    }

    private Timestamp parseTimestamp(String v) {
        String s = v.replace('T', ' ');
        if (s.length() == 10) {
            s = s + " 00:00:00";
        }
        if (s.length() == 16) {
            s = s + ":00";
        }
        return Timestamp.valueOf(s);
    }

    /** 目标表列类型（大写键），获取失败时返回空 Map（按字符串绑定） */
    private Map<String, Integer> loadColumnTypes(Connection conn, String tableName) {
        Map<String, Integer> types = new HashMap<>();
        try {
            String table = tableName;
            String schema = null;
            if (table.contains(".")) {
                int dot = table.lastIndexOf('.');
                schema = table.substring(0, dot).replace("\"", "").trim();
                table = table.substring(dot + 1).replace("\"", "").trim();
            }
            table = table.replace("\"", "").trim();
            var meta = conn.getMetaData();
            try (ResultSet rs = meta.getColumns(null, schema, table, "%")) {
                while (rs.next()) {
                    types.put(rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT), rs.getInt("DATA_TYPE"));
                }
            }
            // 大小写兜底：Oracle 元数据按大写存储
            if (types.isEmpty()) {
                try (ResultSet rs = meta.getColumns(null, schema, table.toUpperCase(Locale.ROOT), "%")) {
                    while (rs.next()) {
                        types.put(rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT), rs.getInt("DATA_TYPE"));
                    }
                }
            }
        } catch (SQLException ignore) {
            // 元数据获取失败时按字符串绑定
        }
        return types;
    }

    private long sum(int[] a) {
        long s = 0;
        for (int x : a) {
            // SUCCESS_NO_INFO(-2)：批量执行成功但单行计数未知，按 1 行计
            s += x == Statement.SUCCESS_NO_INFO ? 1 : Math.max(x, 0);
        }
        return s;
    }
}
