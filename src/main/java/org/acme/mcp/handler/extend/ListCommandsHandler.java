package org.acme.mcp.handler.extend;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.command.model.CommandConfig;
import org.acme.command.storage.CommandStorage;
import org.acme.mcp.handler.McpToolHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 列出通过 add-command 注册的全部自定义命令（按创建时间排序）。
 */
@ApplicationScoped
public class ListCommandsHandler implements McpToolHandler {

    @Inject
    CommandStorage commandStorage;

    @Override
    public String getToolName() {
        return "list-commands";
    }

    @Override
    public String getDescription() {
        return "List all user-defined commands (registered via add-command) with their descriptions, " +
               "sorted by creation time. Use get-command for the full configuration of a command.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        return schema;
    }

    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        List<Map<String, Object>> commands = new ArrayList<>();
        for (CommandConfig config : commandStorage.listAll()) {
            Map<String, Object> item = new HashMap<>();
            item.put("name", config.getName());
            item.put("description", config.getDescription());
            item.put("jarPaths", config.getJarPaths());
            item.put("parameters", config.getParameters() == null
                    ? List.of()
                    : config.getParameters().keySet());
            item.put("createdAt", config.getCreatedAt());
            item.put("updatedAt", config.getUpdatedAt());
            commands.add(item);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("count", commands.size());
        result.put("commands", commands);

        return result;
    }
}
