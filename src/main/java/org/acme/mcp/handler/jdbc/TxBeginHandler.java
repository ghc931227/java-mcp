package org.acme.mcp.handler.jdbc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.jdbc.pool.TransactionManager;
import org.acme.mcp.handler.McpToolHandler;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class TxBeginHandler implements McpToolHandler {

    @Inject
    TransactionManager transactionManager;

    @Override
    public String getToolName() {
        return "jdbc-tx-begin";
    }

    @Override
    public String getDescription() {
        return "Begin a new transaction on a connection and return a transaction ID. " +
               "Use the transaction ID as the 'transactionId' argument in query/exec, " +
               "and finish with tx-commit or tx-rollback.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("connectionId", Map.of("type", "string", "description", "Connection ID"));

        schema.put("properties", properties);
        schema.put("required", new String[]{"connectionId"});

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String connectionId = (String) params.get("connectionId");

        String txId = transactionManager.begin(connectionId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("transactionId", txId);
        result.put("message", "Transaction started. Use tx-commit or tx-rollback to finish.");

        return result;
    }
}
