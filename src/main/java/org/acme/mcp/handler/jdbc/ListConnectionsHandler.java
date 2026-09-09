package org.acme.mcp.handler.jdbc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.jdbc.model.ConnectionConfig;
import org.acme.jdbc.storage.ConnectionStorage;
import org.acme.mcp.handler.McpToolHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class ListConnectionsHandler implements McpToolHandler {

    @Inject
    ConnectionStorage connectionStorage;
    
    @Override
    public String getToolName() {
        return "jdbc-list-conn";
    }
    
    @Override
    public String getDescription() {
        return "List all database connections";
    }
    
    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }
    
    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        List<ConnectionConfig> connections = connectionStorage.listAll();
        
        return connections.stream().map(conn -> {
            Map<String, Object> info = new HashMap<>();
            info.put("id", conn.getId());
            info.put("name", conn.getName());
            info.put("databaseType", conn.getDatabaseType());
            info.put("host", conn.getHost());
            info.put("port", conn.getPort());
            info.put("database", conn.getDatabase());
            info.put("username", conn.getUsername());
            info.put("createdAt", conn.getCreatedAt());
            info.put("updatedAt", conn.getUpdatedAt());
            return info;
        }).collect(Collectors.toList());
    }
}
