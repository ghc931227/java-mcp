package org.acme.mcp.handler.jdbc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.jdbc.pool.TransactionManager;
import org.acme.mcp.handler.McpToolHandler;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class TxRollbackHandler implements McpToolHandler {

    @Inject
    TransactionManager transactionManager;

    @Override
    public String getToolName() {
        return "jdbc-tx-rollback";
    }

    @Override
    public String getDescription() {
        return "Roll back an active transaction and release its connection.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("transactionId", Map.of("type", "string", "description", "Transaction ID returned by tx-begin"));

        schema.put("properties", properties);
        schema.put("required", new String[]{"transactionId"});

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String transactionId = (String) params.get("transactionId");

        transactionManager.rollback(transactionId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Transaction rolled back");

        return result;
    }
}
