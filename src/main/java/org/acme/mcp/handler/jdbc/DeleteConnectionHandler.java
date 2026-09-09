package org.acme.mcp.handler.jdbc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.jdbc.pool.ConnectionPoolManager;
import org.acme.jdbc.storage.ConnectionStorage;
import org.acme.mcp.handler.McpToolHandler;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class DeleteConnectionHandler implements McpToolHandler {

    @Inject
    ConnectionStorage connectionStorage;
    
    @Inject
    ConnectionPoolManager poolManager;
    
    @Override
    public String getToolName() {
        return "jdbc-del-conn";
    }
    
    @Override
    public String getDescription() {
        return "Delete a database connection configuration";
    }
    
    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        properties.put("id", Map.of("type", "string", "description", "Connection ID to delete"));
        
        schema.put("properties", properties);
        schema.put("required", new String[]{"id"});
        
        return schema;
    }
    
    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String id = (String) params.get("id");
        
        poolManager.closeConnection(id);
        
        boolean deleted = connectionStorage.delete(id);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", deleted);
        result.put("message", deleted ? "Connection deleted successfully" : "Connection not found");
        
        return result;
    }
}
