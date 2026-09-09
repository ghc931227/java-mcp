package org.acme.mcp.handler.jdbc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.jdbc.pool.ConnectionPoolManager;
import org.acme.mcp.handler.McpToolHandler;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ListSchemasHandler implements McpToolHandler {

    @Inject
    ConnectionPoolManager poolManager;
    
    @Override
    public String getToolName() {
        return "jdbc-list-schema";
    }
    
    @Override
    public String getDescription() {
        return "List all schemas in the database";
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
        
        List<String> schemas = new ArrayList<>();
        
        try (Connection conn = poolManager.getConnection(connectionId)) {
            DatabaseMetaData metaData = conn.getMetaData();
            
            try (ResultSet rs = metaData.getSchemas()) {
                while (rs.next()) {
                    schemas.add(rs.getString("TABLE_SCHEM"));
                }
            }
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("schemas", schemas);
        result.put("count", schemas.size());
        
        return result;
    }
}
