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
public class ListViewsHandler implements McpToolHandler {

    @Inject
    ConnectionPoolManager poolManager;
    
    @Override
    public String getToolName() {
        return "jdbc-list-views";
    }
    
    @Override
    public String getDescription() {
        return "List all views in the database";
    }
    
    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        properties.put("connectionId", Map.of("type", "string", "description", "Connection ID"));
        properties.put("schemaPattern", Map.of("type", "string", "description", "Schema pattern (optional)"));
        
        schema.put("properties", properties);
        schema.put("required", new String[]{"connectionId"});
        
        return schema;
    }
    
    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String connectionId = (String) params.get("connectionId");
        String schemaPattern = (String) params.get("schemaPattern");
        
        List<Map<String, String>> views = new ArrayList<>();
        
        try (Connection conn = poolManager.getConnection(connectionId)) {
            DatabaseMetaData metaData = conn.getMetaData();
            
            try (ResultSet rs = metaData.getTables(null, schemaPattern, null, new String[]{"VIEW"})) {
                while (rs.next()) {
                    Map<String, String> view = new HashMap<>();
                    view.put("catalog", rs.getString("TABLE_CAT"));
                    view.put("schema", rs.getString("TABLE_SCHEM"));
                    view.put("name", rs.getString("TABLE_NAME"));
                    view.put("remarks", rs.getString("REMARKS"));
                    views.add(view);
                }
            }
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("views", views);
        result.put("count", views.size());
        
        return result;
    }
}
