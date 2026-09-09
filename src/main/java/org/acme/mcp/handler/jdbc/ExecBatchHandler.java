package org.acme.mcp.handler.jdbc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.jdbc.pool.ConnectionPoolManager;
import org.acme.jdbc.pool.SqlTimeoutConfig;
import org.acme.jdbc.pool.TransactionManager;
import org.acme.mcp.handler.McpToolHandler;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量 SQL 执行：多条语句在同一事务中依次执行，默认全部成功才提交，任一失败回滚。
 */
@ApplicationScoped
public class ExecBatchHandler implements McpToolHandler {

    @Inject
    ConnectionPoolManager poolManager;

    @Inject
    TransactionManager transactionManager;

    @Inject
    SqlTimeoutConfig timeoutConfig;

    @Override
    public String getToolName() {
        return "jdbc-exec-batch";
    }

    @Override
    public String getDescription() {
        return "批量执行多条 SQL 语句（INSERT/UPDATE/DELETE/DDL）。" +
               "所有语句默认在同一事务中执行：全部成功后统一提交，任一失败则回滚。" +
               "设置 continueOnError=true 可忽略失败语句继续执行（此时失败不会触发回滚）。" +
               "传入 transactionId 时语句在外部事务中执行且不自动提交。";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("connectionId", Map.of("type", "string",
                "description", "连接 ID（未传 transactionId 时必填）"));
        properties.put("statements", Map.of("type", "array",
                "description", "SQL 语句字符串数组，按顺序执行；空语句自动跳过",
                "items", Map.of("type", "string")));
        properties.put("transactionId", Map.of("type", "string",
                "description", "可选：已有事务 ID，语句在该事务内执行且不提交"));
        properties.put("continueOnError", Map.of("type", "boolean",
                "description", "某条语句失败时是否继续执行后续语句（默认 false：失败即回滚并终止）"));
        properties.put("timeoutSeconds", Map.of("type", "integer",
                "description", "单条语句超时秒数（默认 180，最大 1800）"));

        schema.put("properties", properties);
        schema.put("required", new String[]{"statements"});
        return schema;
    }

    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        @SuppressWarnings("unchecked")
        List<String> statements = (List<String>) params.get("statements");
        if (statements == null || statements.isEmpty()) {
            throw new IllegalArgumentException("statements 不能为空");
        }
        String connectionId = (String) params.get("connectionId");
        String transactionId = (String) params.get("transactionId");
        boolean continueOnError = Boolean.TRUE.equals(params.get("continueOnError"));
        int timeoutSeconds = timeoutConfig.resolve(params);

        Connection conn = null;
        boolean borrowFromPool = false;
        List<Map<String, Object>> results = new ArrayList<>();
        int executed = 0;
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

            boolean ownTx = borrowFromPool;
            try {
                if (ownTx) {
                    conn.setAutoCommit(false);
                }
                try (Statement stmt = conn.createStatement()) {
                    stmt.setQueryTimeout(timeoutSeconds);
                    int index = 0;
                    for (String sql : statements) {
                        if (sql == null || sql.trim().isEmpty()) {
                            continue;
                        }
                        index++;
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("index", index);
                        try {
                            int affected = stmt.executeUpdate(sql);
                            executed++;
                            item.put("success", true);
                            item.put("affectedRows", affected);
                        } catch (SQLException e) {
                            item.put("success", false);
                            item.put("error", e.getMessage());
                            if (!continueOnError) {
                                results.add(item);
                                throw new SQLException("第 " + index + " 条语句执行失败: " + e.getMessage(), e);
                            }
                        }
                        results.add(item);
                    }
                }
                if (ownTx) {
                    conn.commit();
                }
            } catch (Exception e) {
                if (ownTx) {
                    try {
                        conn.rollback();
                    } catch (SQLException ignore) {
                    }
                }
                throw e;
            } finally {
                if (ownTx) {
                    try {
                        conn.setAutoCommit(true);
                    } catch (SQLException ignore) {
                    }
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("executedStatements", executed);
            result.put("results", results);
            result.put("message", "批量 SQL 执行成功" + (ownTx ? "（已提交）" : "（外部事务，请自行提交）"));
            return result;
        } finally {
            if (borrowFromPool && conn != null) {
                conn.close();
            }
        }
    }
}
