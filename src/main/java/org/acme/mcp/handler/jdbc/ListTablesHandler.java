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
public class ListTablesHandler implements McpToolHandler {

    @Inject
    ConnectionPoolManager poolManager;
    
    @Override
    public String getToolName() {
        return "jdbc-list-tables";
    }
    
    @Override
    public String getDescription() {
        return "List all tables in the database";
    }
    
    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        properties.put("connectionId", Map.of("type", "string", "description", "Connection ID"));
        properties.put("schemaPattern", Map.of("type", "string", "description", "Schema pattern (optional)"));
        properties.put("tableNamePattern", Map.of("type", "string", "description", "Table name pattern (optional)"));
        
        schema.put("properties", properties);
        schema.put("required", new String[]{"connectionId"});
        
        return schema;
    }
    
    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String connectionId = (String) params.get("connectionId");
        String schemaPattern = (String) params.get("schemaPattern");
        String tableNamePattern = (String) params.get("tableNamePattern");
        
        List<Map<String, String>> tables = new ArrayList<>();
        
        try (Connection conn = poolManager.getConnection(connectionId)) {
            DatabaseMetaData metaData = conn.getMetaData();
            
            try (ResultSet rs = metaData.getTables(null, schemaPattern, tableNamePattern, new String[]{"TABLE"})) {
                while (rs.next()) {
                    Map<String, String> table = new HashMap<>();
                    table.put("catalog", rs.getString("TABLE_CAT"));
                    table.put("schema", rs.getString("TABLE_SCHEM"));
                    table.put("name", rs.getString("TABLE_NAME"));
                    table.put("type", rs.getString("TABLE_TYPE"));
                    table.put("remarks", rs.getString("REMARKS"));
                    tables.add(table);
                }
            }
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("tables", tables);
        result.put("count", tables.size());
        
        return result;
    }
}
