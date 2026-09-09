package org.acme.mcp.handler.jdbc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.jdbc.pool.ConnectionPoolManager;
import org.acme.jdbc.pool.SqlTimeoutConfig;
import org.acme.jdbc.pool.TransactionManager;
import org.acme.mcp.handler.McpToolHandler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ExecHandler implements McpToolHandler {

    @Inject
    ConnectionPoolManager poolManager;

    @Inject
    TransactionManager transactionManager;

    @Inject
    SqlTimeoutConfig timeoutConfig;

    @Override
    public String getToolName() {
        return "jdbc-exec";
    }

    @Override
    public String getDescription() {
        return "Execute a SQL statement (INSERT, UPDATE, DELETE, DDL) and return affected rows. " +
               "Use '?' placeholders with the 'params' array for parameterized statements. " +
               "Optionally route through an active transaction with 'transactionId'.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("connectionId", Map.of("type", "string", "description", "Connection ID (required unless transactionId is given)"));
        properties.put("sql", Map.of("type", "string", "description", "SQL statement to execute"));
        properties.put("params", Map.of("type", "array", "description", "Optional positional parameter values for '?' placeholders (strings, numbers, booleans or null)", "items", Map.of("type", List.of("string", "number", "boolean", "null"))));
        properties.put("transactionId", Map.of("type", "string", "description", "Optional active transaction ID to run the statement in"));
        properties.put("timeoutSeconds", Map.of("type", "integer", "description", "Statement timeout in seconds (default: 180, max: 1800)"));

        schema.put("properties", properties);
        schema.put("required", new String[]{"sql"});

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String connectionId = (String) params.get("connectionId");
        String sql = (String) params.get("sql");
        String transactionId = (String) params.get("transactionId");

        int timeoutSeconds = timeoutConfig.resolve(params);

        @SuppressWarnings("unchecked")
        List<Object> bindParams = params.get("params") == null
                ? List.of()
                : (List<Object>) params.get("params");

        int affectedRows;

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
                stmt.setQueryTimeout(timeoutSeconds);
                affectedRows = stmt instanceof PreparedStatement
                        ? ((PreparedStatement) stmt).executeUpdate()
                        : stmt.executeUpdate(sql);
            }
        } finally {
            if (borrowFromPool && conn != null) {
                conn.close();
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("affectedRows", affectedRows);
        result.put("message", "SQL executed successfully");

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
}
