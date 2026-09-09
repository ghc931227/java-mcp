package org.acme.mcp.handler;

import java.util.Map;

public interface McpToolHandler {
    
    String getToolName();
    
    String getDescription();
    
    Map<String, Object> getInputSchema();
    
    Object execute(Map<String, Object> params) throws Exception;
}
