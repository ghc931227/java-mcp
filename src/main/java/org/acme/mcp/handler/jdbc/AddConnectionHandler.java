package org.acme.mcp.handler.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.jdbc.model.ConnectionConfig;
import org.acme.jdbc.model.DatabaseType;
import org.acme.jdbc.storage.ConnectionStorage;
import org.acme.mcp.handler.McpToolHandler;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class AddConnectionHandler implements McpToolHandler {

    @Inject
    ConnectionStorage connectionStorage;
    
    @Inject
    ObjectMapper objectMapper;
    
    @Override
    public String getToolName() {
        return "jdbc-add-conn";
    }
    
    @Override
    public String getDescription() {
        return "Add a new database connection configuration";
    }
    
    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        properties.put("name", Map.of("type", "string", "description", "Connection name"));
        properties.put("databaseType", Map.of("type", "string", "description", "Database type (MYSQL, POSTGRESQL, ORACLE, SQLSERVER, H2, SQLITE, MARIADB, KINGBASE, DM, CUSTOM)"));
        properties.put("host", Map.of("type", "string", "description", "Database host (required for MYSQL/POSTGRESQL/ORACLE/SQLSERVER/MARIADB/KINGBASE/DM; ignored for H2/SQLITE/CUSTOM with customJdbcUrl)"));
        properties.put("port", Map.of("type", "integer", "description", "Database port (required for MYSQL/POSTGRESQL/ORACLE/SQLSERVER/MARIADB/KINGBASE/DM; ignored for H2/SQLITE/CUSTOM with customJdbcUrl)"));
        properties.put("database", Map.of("type", "string", "description", "Database name (for H2/SQLITE this is the database/file name)"));
        properties.put("username", Map.of("type", "string", "description", "Database username (optional)"));
        properties.put("password", Map.of("type", "string", "description", "Database password (optional)"));
        properties.put("driverJarPath", Map.of("type", "string", "description", "Path to JDBC driver jar file (optional)"));
        properties.put("customDriverClass", Map.of("type", "string", "description", "Custom driver class name (optional)"));
        properties.put("customJdbcUrl", Map.of("type", "string", "description", "Custom JDBC URL (required for CUSTOM)"));

        schema.put("properties", properties);
        schema.put("required", new String[]{"name", "databaseType", "database"});
        
        return schema;
    }
    
    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        ConnectionConfig config = new ConnectionConfig();
        config.setName((String) params.get("name"));
        DatabaseType type = DatabaseType.valueOf(((String) params.get("databaseType")).toUpperCase());
        config.setDatabaseType(type);

        String host = (String) params.get("host");
        String database = (String) params.get("database");
        Integer port = params.containsKey("port") && params.get("port") != null
                ? ((Number) params.get("port")).intValue()
                : 0;
        String customJdbcUrl = (String) params.get("customJdbcUrl");

        // 按类型校验必填字段
        switch (type) {
            case MYSQL, POSTGRESQL, ORACLE, SQLSERVER, MARIADB, KINGBASE, DM -> {
                if (host == null || host.isBlank()) {
                    throw new IllegalArgumentException("host is required for " + type);
                }
                if (port <= 0) {
                    throw new IllegalArgumentException("port is required for " + type);
                }
            }
            case CUSTOM -> {
                if (customJdbcUrl == null || customJdbcUrl.isBlank()) {
                    throw new IllegalArgumentException("customJdbcUrl is required for CUSTOM");
                }
            }
            default -> {
                // H2 / SQLITE 只需要 database
            }
        }

        config.setHost(host);
        config.setPort(port);
        config.setDatabase(database);
        config.setUsername((String) params.get("username"));
        config.setPassword((String) params.get("password"));
        
        if (params.containsKey("driverJarPath")) {
            config.setDriverJarPath((String) params.get("driverJarPath"));
        }
        if (params.containsKey("customDriverClass")) {
            config.setCustomDriverClass((String) params.get("customDriverClass"));
        }
        if (params.containsKey("customJdbcUrl")) {
            config.setCustomJdbcUrl(customJdbcUrl);
        }
        
        ConnectionConfig saved = connectionStorage.save(config);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("id", saved.getId());
        result.put("message", "Connection added successfully");
        
        return result;
    }
}
