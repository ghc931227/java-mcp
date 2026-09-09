package org.acme.mcp.handler.jdbc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.jdbc.pool.ConnectionPoolManager;
import org.acme.jdbc.pool.SqlTimeoutConfig;
import org.acme.jdbc.pool.TransactionManager;
import org.acme.mcp.handler.McpToolHandler;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class QueryHandler implements McpToolHandler {

    private static final int DEFAULT_MAX_ROWS = 100;
    private static final int MAX_MAX_ROWS = 10000;

    @Inject
    ConnectionPoolManager poolManager;

    @Inject
    TransactionManager transactionManager;

    @Inject
    SqlTimeoutConfig timeoutConfig;

    @Override
    public String getToolName() {
        return "jdbc-query";
    }

    @Override
    public String getDescription() {
        return "Execute a SQL query (SELECT) and return results. " +
               "Use '?' placeholders with the 'params' array for parameterized queries. " +
               "Optionally route through an active transaction with 'transactionId'.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("connectionId", Map.of("type", "string", "description", "Connection ID (required unless transactionId is given)"));
        properties.put("sql", Map.of("type", "string", "description", "SQL query to execute"));
        properties.put("params", Map.of("type", "array", "description", "Optional positional parameter values for '?' placeholders (strings, numbers, booleans or null)", "items", Map.of("type", List.of("string", "number", "boolean", "null"))));
        properties.put("transactionId", Map.of("type", "string", "description", "Optional active transaction ID to run the query in"));
        properties.put("maxRows", Map.of("type", "integer", "description", "Maximum number of rows to return (default: 100, max: 10000)"));
        properties.put("timeoutSeconds", Map.of("type", "integer", "description", "Query timeout in seconds (default: 180, max: 1800)"));

        schema.put("properties", properties);
        schema.put("required", new String[]{"sql"});

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String connectionId = (String) params.get("connectionId");
        String sql = (String) params.get("sql");
        String transactionId = (String) params.get("transactionId");

        int maxRows = resolveMaxRows(params);
        int timeoutSeconds = timeoutConfig.resolve(params);

        @SuppressWarnings("unchecked")
        List<Object> bindParams = params.get("params") == null
                ? List.of()
                : (List<Object>) params.get("params");

        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> columns = new ArrayList<>();

        Connection conn = null;
        boolean borrowFromPool = false;
        try {
            if (transactionId != null && !transactionId.isEmpty()) {
                conn = transactionManager.getConnection(transactionId);
                if (conn == null) {
                    throw new IllegalArgumentException("Transaction not found: " + transactionId);
                }
            } else {
                conn = poolManager.getConnection(connectionId);
                borrowFromPool = true;
            }

            try (Statement stmt = buildStatement(conn, sql, bindParams)) {
                stmt.setMaxRows(maxRows);
                stmt.setQueryTimeout(timeoutSeconds);

                try (ResultSet rs = stmt instanceof PreparedStatement
                        ? ((PreparedStatement) stmt).executeQuery()
                        : stmt.executeQuery(sql)) {
                    ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();

                    for (int i = 1; i <= columnCount; i++) {
                        columns.add(metaData.getColumnLabel(i));
                    }

                    while (rs.next()) {
                        Map<String, Object> row = new HashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            row.put(columns.get(i - 1), rs.getObject(i));
                        }
                        rows.add(row);
                    }
                }
            }
        } finally {
            if (borrowFromPool && conn != null) {
                conn.close();
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("columns", columns);
        result.put("rows", rows);
        result.put("rowCount", rows.size());

        return result;
    }

    private Statement buildStatement(Connection conn, String sql, List<Object> bindParams) throws SQLException {
        if (bindParams.isEmpty()) {
            return conn.createStatement();
        }
        PreparedStatement ps = conn.prepareStatement(sql);
        for (int i = 0; i < bindParams.size(); i++) {
            Object value = bindParams.get(i);
            if (value == null) {
                ps.setObject(i + 1, null);
            } else {
                ps.setObject(i + 1, value);
            }
        }
        return ps;
    }

    private int resolveMaxRows(Map<String, Object> params) {
        if (!params.containsKey("maxRows")) {
            return DEFAULT_MAX_ROWS;
        }
        int value = ((Number) params.get("maxRows")).intValue();
        if (value <= 0) {
            return DEFAULT_MAX_ROWS;
        }
        return Math.min(value, MAX_MAX_ROWS);
    }
}
