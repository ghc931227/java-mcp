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
public class ListDatabasesHandler implements McpToolHandler {

    @Inject
    ConnectionPoolManager poolManager;
    
    @Override
    public String getToolName() {
        return "jdbc-list-db";
    }
    
    @Override
    public String getDescription() {
        return "List all databases/catalogs in the connected server";
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
        
        List<String> databases = new ArrayList<>();
        
        try (Connection conn = poolManager.getConnection(connectionId)) {
            DatabaseMetaData metaData = conn.getMetaData();
            
            try (ResultSet rs = metaData.getCatalogs()) {
                while (rs.next()) {
                    databases.add(rs.getString("TABLE_CAT"));
                }
            }
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("databases", databases);
        result.put("count", databases.size());
        
        return result;
    }
}
