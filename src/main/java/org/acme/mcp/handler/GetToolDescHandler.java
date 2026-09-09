package org.acme.mcp.handler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.acme.command.storage.CommandStorage;
import org.acme.mcp.handler.extend.CustomCommandHandler;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class GetToolDescHandler implements McpToolHandler {

    @Inject
    Instance<McpToolHandler> toolHandlers;

    @Inject
    CommandStorage commandStorage;

    @Override
    public String getToolName() {
        return "tool-desc";
    }

    @Override
    public String getDescription() {
        return "Return the description and usage instructions (input schema) of a specific tool by name. " +
               "Call this when you need the details of a tool before invoking it.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("toolName", Map.of(
            "type", "string",
            "description", "Name of the tool whose description and usage instructions are requested"
        ));

        schema.put("properties", properties);
        schema.put("required", new String[]{"toolName"});

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String toolName = (String) params.get("toolName");
        if (toolName == null || toolName.trim().isEmpty()) {
            throw new IllegalArgumentException("toolName is required");
        }

        for (McpToolHandler handler : toolHandlers) {
            if (toolName.equals(handler.getToolName())) {
                Map<String, Object> result = new HashMap<>();
                result.put("name", handler.getToolName());
                result.put("description", handler.getDescription());
                result.put("inputSchema", handler.getInputSchema());
                return result;
            }
        }

        // 动态命令（非 CDI bean），从命令存储中查找
        CustomCommandHandler dynamic = commandStorage.getByName(toolName)
                .map(CustomCommandHandler::new)
                .orElse(null);
        if (dynamic != null) {
            Map<String, Object> result = new HashMap<>();
            result.put("name", dynamic.getToolName());
            result.put("description", dynamic.getDescription());
            result.put("inputSchema", dynamic.getInputSchema());
            return result;
        }

        throw new IllegalArgumentException("Unknown tool: " + toolName);
    }
}
