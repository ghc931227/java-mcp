package org.acme.mcp.handler.extend;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.command.model.CommandConfig;
import org.acme.command.storage.CommandStorage;
import org.acme.mcp.handler.McpToolHandler;
import org.acme.mcp.service.McpService;

import java.util.HashMap;
import java.util.Map;

/**
 * 编辑通过 add-command 注册的自定义命令，仅更新传入的字段，
 * 保存后立即热重载对应的 MCP 工具（无需重启）。
 */
@ApplicationScoped
public class EditCommandHandler implements McpToolHandler {

    @Inject
    CommandStorage commandStorage;

    @Inject
    McpService mcpService;

    @Override
    public String getToolName() {
        return "edit-command";
    }

    @Override
    public String getDescription() {
        return "Edit an existing user-defined command (registered via add-command). " +
               "Only provided fields are updated; providing 'parameters' replaces the whole parameter definition. " +
               "The command's MCP tool is refreshed immediately after saving.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("name", Map.of("type", "string", "description", "Name of the command to edit (must already exist, cannot be renamed)"));
        properties.put("description", Map.of("type", "string", "description", "New description of the command"));
        properties.put("javaCode", Map.of("type", "string", "description", "New Java code to run. Snippet (wrapped in execute(Map) method) or complete class with execute(Map<String, Object> params)"));
        properties.put("jarPaths", Map.of(
            "type", "array",
            "items", Map.of("type", "string"),
            "description", "New external JAR absolute paths replacing the existing list. Same behavior as java-exec jarPaths"));
        properties.put("parameters", Map.of("type", "object", "description", "New parameter definitions replacing all existing ones: { paramName: { type, description, required, defaultValue } }"));

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

        Object description = params.get("description");
        if (description != null) {
            config.setDescription(String.valueOf(description));
        }

        Object javaCode = params.get("javaCode");
        if (javaCode != null) {
            String code = String.valueOf(javaCode);
            if (code.trim().isEmpty()) {
                throw new IllegalArgumentException("javaCode cannot be empty");
            }
            config.setJavaCode(code);
        }

        if (params.containsKey("jarPaths")) {
            config.setJarPaths(CommandParamParser.resolveJarPaths(params.get("jarPaths")));
        }

        if (params.containsKey("parameters")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) params.get("parameters");
            config.setParameters(parameters == null
                    ? new HashMap<>()
                    : CommandParamParser.parseParameters(parameters));
        }

        CommandConfig saved = commandStorage.save(config);
        mcpService.reloadDynamicCommands();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("id", saved.getId());
        result.put("name", saved.getName());
        result.put("message", "Command updated successfully. Its MCP tool has been refreshed.");

        return result;
    }
}
