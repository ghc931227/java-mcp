package org.acme.mcp.handler.jdbc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.jdbc.model.ConnectionConfig;
import org.acme.jdbc.model.DatabaseType;
import org.acme.jdbc.storage.ConnectionStorage;
import org.acme.mcp.handler.McpToolHandler;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class EditConnectionHandler implements McpToolHandler {

    @Inject
    ConnectionStorage connectionStorage;
    
    @Override
    public String getToolName() {
        return "jdbc-edit-conn";
    }
    
    @Override
    public String getDescription() {
        return "Edit an existing database connection configuration";
    }
    
    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        properties.put("id", Map.of("type", "string", "description", "Connection ID to edit"));
        properties.put("name", Map.of("type", "string", "description", "Connection name"));
        properties.put("databaseType", Map.of("type", "string", "description", "Database type"));
        properties.put("host", Map.of("type", "string", "description", "Database host"));
        properties.put("port", Map.of("type", "integer", "description", "Database port"));
        properties.put("database", Map.of("type", "string", "description", "Database name"));
        properties.put("username", Map.of("type", "string", "description", "Database username"));
        properties.put("password", Map.of("type", "string", "description", "Database password"));
        properties.put("driverJarPath", Map.of("type", "string", "description", "Path to JDBC driver jar file"));
        properties.put("customDriverClass", Map.of("type", "string", "description", "Custom driver class name (optional)"));
        properties.put("customJdbcUrl", Map.of("type", "string", "description", "Custom JDBC URL (optional)"));
        
        schema.put("properties", properties);
        schema.put("required", new String[]{"id"});
        
        return schema;
    }
    
    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String id = (String) params.get("id");
        
        ConnectionConfig config = connectionStorage.getDecryptedConfig(id);
        if (config == null) {
            throw new IllegalArgumentException("Connection not found: " + id);
        }
        
        if (params.containsKey("name")) {
            config.setName((String) params.get("name"));
        }
        if (params.containsKey("databaseType")) {
            config.setDatabaseType(DatabaseType.valueOf(((String) params.get("databaseType")).toUpperCase()));
        }
        if (params.containsKey("host")) {
            config.setHost((String) params.get("host"));
        }
        if (params.containsKey("port") && params.get("port") != null) {
            config.setPort(((Number) params.get("port")).intValue());
        }
        if (params.containsKey("database")) {
            config.setDatabase((String) params.get("database"));
        }
        if (params.containsKey("username")) {
            config.setUsername((String) params.get("username"));
        }
        if (params.containsKey("password")) {
            config.setPassword((String) params.get("password"));
        }
        if (params.containsKey("driverJarPath")) {
            config.setDriverJarPath((String) params.get("driverJarPath"));
        }
        if (params.containsKey("customDriverClass")) {
            config.setCustomDriverClass((String) params.get("customDriverClass"));
        }
        if (params.containsKey("customJdbcUrl")) {
            config.setCustomJdbcUrl((String) params.get("customJdbcUrl"));
        }
        
        connectionStorage.save(config);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Connection updated successfully");
        
        return result;
    }
}