package org.acme.mcp.handler.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.jdbc.pool.ConnectionPoolManager;
import org.acme.jdbc.pool.SqlTimeoutConfig;
import org.acme.mcp.handler.McpToolHandler;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 数据导出：执行查询并将结果写入文件。
 * 支持 csv / json / inserts（INSERT 语句文件）。
 * 二进制列（BLOB/BINARY 等）在 csv/json 中导出为 Base64 字符串，inserts 格式不支持。
 */
@ApplicationScoped
public class ExportHandler implements McpToolHandler {

    private static final int DEFAULT_MAX_ROWS = 100000;
    private static final int MAX_MAX_ROWS = 2000000;
    private static final int FETCH_SIZE = 1000;

    @Inject
    ConnectionPoolManager poolManager;

    @Inject
    SqlTimeoutConfig timeoutConfig;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public String getToolName() {
        return "jdbc-export";
    }

    @Override
    public String getDescription() {
        return "数据导出：执行 SELECT 查询并将结果写入文件。" +
               "支持格式：csv、json（对象数组）、inserts（INSERT 语句文件，需提供 tableName，日期导出为 TIMESTAMP/DATE 字面量）。" +
               "二进制列（BLOB 等）在 csv/json 中导出为 Base64 字符串。" +
               "格式默认按文件扩展名识别（.sql→inserts、.json→json、其他→csv）。";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("connectionId", Map.of("type", "string", "description", "连接 ID（必填）"));
        properties.put("sql", Map.of("type", "string", "description", "SELECT 查询语句（必填）"));
        properties.put("filePath", Map.of("type", "string", "description", "导出文件绝对路径（必填，父目录不存在会自动创建）"));
        properties.put("format", Map.of("type", "string",
                "description", "格式：csv / json / inserts，默认按扩展名识别"));
        properties.put("tableName", Map.of("type", "string",
                "description", "inserts 格式必填：INSERT 语句中的目标表名"));
        properties.put("maxRows", Map.of("type", "integer",
                "description", "最大导出行数（默认 100000，最大 2000000）"));
        properties.put("timeoutSeconds", Map.of("type", "integer",
                "description", "查询超时秒数（默认 180，最大 1800）"));

        schema.put("properties", properties);
        schema.put("required", new String[]{"connectionId", "sql", "filePath"});
        return schema;
    }

    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String connectionId = (String) params.get("connectionId");
        String sql = (String) params.get("sql");
        String filePath = (String) params.get("filePath");
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("sql 不能为空");
        }
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("filePath 不能为空");
        }
        String tableName = (String) params.get("tableName");
        String format = params.get("format") == null ? null : params.get("format").toString().toLowerCase(Locale.ROOT);
        int maxRows = params.get("maxRows") instanceof Number ? ((Number) params.get("maxRows")).intValue() : DEFAULT_MAX_ROWS;
        maxRows = Math.min(Math.max(maxRows, 1), MAX_MAX_ROWS);
        int timeoutSeconds = timeoutConfig.resolve(params);

        if (format == null || format.isEmpty()) {
            String lower = filePath.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".sql")) {
                format = "inserts";
            } else if (lower.endsWith(".json")) {
                format = "json";
            } else {
                format = "csv";
            }
        }
        if ("inserts".equals(format) && (tableName == null || tableName.trim().isEmpty())) {
            throw new IllegalArgumentException("inserts 格式必须提供 tableName");
        }

        Path path = Paths.get(filePath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        long rows;
        try (Connection conn = poolManager.getConnection(connectionId);
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(timeoutSeconds);
            stmt.setMaxRows(maxRows);
            // 大表导出性能关键：Oracle 默认 fetchSize 仅 10
            stmt.setFetchSize(FETCH_SIZE);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                rows = writeResult(rs, path, format, tableName);
            }
        }

        // 空结果时删除残留的空文件
        if (rows == 0) {
            Files.deleteIfExists(path);
        }

        long size = Files.exists(path) ? Files.size(path) : 0;
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("format", format);
        result.put("filePath", filePath);
        result.put("rowsExported", rows);
        result.put("fileSizeBytes", size);
        result.put("message", "导出成功，共 " + rows + " 行");
        return result;
    }

    private long writeResult(ResultSet rs, Path path, String format, String tableName) throws Exception {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        String[] columns = new String[columnCount];
        int[] columnTypes = new int[columnCount];
        for (int i = 1; i <= columnCount; i++) {
            columns[i - 1] = meta.getColumnLabel(i);
            columnTypes[i - 1] = meta.getColumnType(i);
        }

        switch (format) {
            case "csv":
                return writeCsv(rs, path, columns, columnTypes);
            case "json":
                return writeJson(rs, path, columns, columnTypes);
            case "inserts":
                return writeInserts(rs, path, columns, columnTypes, tableName);
            default:
                throw new IllegalArgumentException("不支持的 format: " + format);
        }
    }

    /** 按列类型取值：CLOB→字符串，BLOB/BINARY→Base64，其他→getObject */
    private Object readValue(ResultSet rs, int index, int columnType) throws SQLException {
        switch (columnType) {
            case Types.CLOB:
            case Types.NCLOB: {
                Clob c = rs.getClob(index);
                return c == null ? null : c.getSubString(1, (int) c.length());
            }
            case Types.BLOB:
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY: {
                byte[] bytes = rs.getBytes(index);
                return bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
            }
            default:
                return rs.getObject(index);
        }
    }

    private boolean isBinary(int columnType) {
        return columnType == Types.BLOB || columnType == Types.BINARY
                || columnType == Types.VARBINARY || columnType == Types.LONGVARBINARY;
    }

    private long writeCsv(ResultSet rs, Path path, String[] columns, int[] columnTypes) throws Exception {
        long count = 0;
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write(joinCsv(columns));
            w.write("\r\n");
            while (rs.next()) {
                String[] values = new String[columns.length];
                for (int i = 0; i < columns.length; i++) {
                    Object v = readValue(rs, i + 1, columnTypes[i]);
                    values[i] = v == null ? null : v.toString();
                }
                w.write(joinCsv(values));
                w.write("\r\n");
                count++;
            }
        }
        return count;
    }

    private String joinCsv(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            String v = values[i];
            if (v != null && (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r"))) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else if (v != null) {
                sb.append(v);
            }
        }
        return sb.toString();
    }

    private long writeJson(ResultSet rs, Path path, String[] columns, int[] columnTypes) throws Exception {
        long count = 0;
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("[");
            boolean first = true;
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < columns.length; i++) {
                    row.put(columns[i], readValue(rs, i + 1, columnTypes[i]));
                }
                if (!first) {
                    w.write(",");
                }
                first = false;
                w.write(objectMapper.writeValueAsString(row));
                count++;
            }
            w.write("]");
        }
        return count;
    }

    /** 流式写出 INSERT 语句文件，行间以逗号分隔，末尾补分号 */
    private long writeInserts(ResultSet rs, Path path, String[] columns, int[] columnTypes, String tableName) throws Exception {
        for (int type : columnTypes) {
            if (isBinary(type)) {
                throw new IllegalArgumentException("inserts 格式不支持二进制列（BLOB/BINARY），请使用 csv/json 格式");
            }
        }
        long count = 0;
        StringBuilder colList = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) colList.append(',');
            colList.append(columns[i]);
        }
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("INSERT INTO " + tableName + " (" + colList + ") VALUES");
            w.write("\r\n");
            StringBuilder tuple = new StringBuilder();
            while (rs.next()) {
                if (count > 0) {
                    w.write(",\r\n");
                }
                tuple.setLength(0);
                tuple.append('\t').append('(');
                for (int i = 1; i <= columns.length; i++) {
                    if (i > 1) tuple.append(',');
                    tuple.append(formatLiteral(rs, i));
                }
                tuple.append(')');
                w.write(tuple.toString());
                count++;
            }
            if (count == 0) {
                return 0;
            }
            w.write(";\r\n");
        }
        return count;
    }

    private String formatLiteral(ResultSet rs, int index) throws SQLException {
        Object v = rs.getObject(index);
        if (v == null) {
            return "NULL";
        }
        if (v instanceof Timestamp) {
            return "TIMESTAMP'" + v.toString() + "'";
        }
        if (v instanceof Date) {
            return "DATE'" + v.toString() + "'";
        }
        if (v instanceof Time) {
            return "TIMESTAMP'1970-01-01 " + v.toString() + "'";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        String s;
        if (v instanceof Clob) {
            s = ((Clob) v).getSubString(1, (int) ((Clob) v).length());
        } else if (v instanceof Blob) {
            throw new IllegalArgumentException("inserts 格式不支持二进制列，请使用 csv/json 格式");
        } else {
            s = v.toString();
        }
        return "'" + s.replace("'", "''") + "'";
    }
}
