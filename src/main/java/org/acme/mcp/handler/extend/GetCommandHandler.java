package org.acme.mcp.handler.extend;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.command.model.CommandConfig;
import org.acme.command.storage.CommandStorage;
import org.acme.mcp.handler.McpToolHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * 查看通过 add-command 注册的自定义命令完整配置。
 */
@ApplicationScoped
public class GetCommandHandler implements McpToolHandler {

    @Inject
    CommandStorage commandStorage;

    @Override
    public String getToolName() {
        return "get-command";
    }

    @Override
    public String getDescription() {
        return "Get the full configuration of a user-defined command (registered via add-command), " +
               "including its Java code, jarPaths and parameter definitions.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("name", Map.of("type", "string", "description", "Name of the command to get"));

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

        String finalName = name;
        CommandConfig config = commandStorage.getByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Command not found: " + finalName));

        Map<String, Object> command = new HashMap<>();
        command.put("id", config.getId());
        command.put("name", config.getName());
        command.put("description", config.getDescription());
        command.put("javaCode", config.getJavaCode());
        command.put("jarPaths", config.getJarPaths());
        command.put("parameters", config.getParameters());
        command.put("createdAt", config.getCreatedAt());
        command.put("updatedAt", config.getUpdatedAt());

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("command", command);

        return result;
    }
}
