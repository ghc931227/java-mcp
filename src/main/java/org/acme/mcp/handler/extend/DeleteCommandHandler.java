package org.acme.mcp.handler.extend;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.command.storage.CommandStorage;
import org.acme.mcp.handler.McpToolHandler;
import org.acme.mcp.service.McpService;

import java.util.HashMap;
import java.util.Map;

/**
 * 删除通过 add-command 注册的自定义命令，删除后立即移除对应的 MCP 工具。
 */
@ApplicationScoped
public class DeleteCommandHandler implements McpToolHandler {

    @Inject
    CommandStorage commandStorage;

    @Inject
    McpService mcpService;

    @Override
    public String getToolName() {
        return "delete-command";
    }

    @Override
    public String getDescription() {
        return "Delete a user-defined command (registered via add-command). " +
               "The command's MCP tool is removed immediately after deletion.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("name", Map.of("type", "string", "description", "Name of the command to delete"));

        schema.put("properties", properties);
        schema.put("required", new String[]{"name"});

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String name = (String) params.get("name");
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name is required");
        }
        name = name.trim();

        boolean deleted = commandStorage.delete(name);
        if (deleted) {
            mcpService.reloadDynamicCommands();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", deleted);
        result.put("name", name);
        result.put("message", deleted
                ? "Command deleted successfully. Its MCP tool has been removed."
                : "Command not found: " + name);

        return result;
    }
}
