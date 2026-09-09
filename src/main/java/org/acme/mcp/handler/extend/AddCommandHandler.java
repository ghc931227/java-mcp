package org.acme.mcp.handler.extend;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.acme.command.model.CommandConfig;
import org.acme.command.storage.CommandStorage;
import org.acme.mcp.handler.McpToolHandler;
import org.acme.mcp.service.McpService;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class AddCommandHandler implements McpToolHandler {

    @Inject
    CommandStorage commandStorage;

    @Inject
    Instance<McpToolHandler> toolHandlers;

    @Inject
    McpService mcpService;

    @Override
    public String getToolName() {
        return "add-command";
    }

    @Override
    public String getDescription() {
        return "Register a user-defined command as a new MCP tool. " +
               "The command name becomes the tool name; its Java code is compiled and executed when called. " +
               "Optional jarPaths add external JARs to the compile classpath and runtime classloader. " +
               "Parameters are declared as an object, each value being {type, description, required, defaultValue}.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("name", Map.of("type", "string", "description", "Command name; becomes the MCP tool name (must not conflict with existing tools)"));
        properties.put("description", Map.of("type", "string", "description", "Description of the command"));
        properties.put("javaCode", Map.of("type", "string", "description", "Java code to run when the command is called. Snippet (wrapped in execute(Map) method) or complete class with execute(Map<String, Object> params)"));
        properties.put("jarPaths", Map.of(
            "type", "array",
            "items", Map.of("type", "string"),
            "description", "Optional external JAR absolute paths. Added to compile classpath and runtime URLClassLoader, same behavior as java-exec jarPaths"));
        properties.put("parameters", Map.of("type", "object", "description", "Optional parameter definitions: { paramName: { type, description, required, defaultValue } }"));

        schema.put("properties", properties);
        schema.put("required", new String[]{"name", "javaCode"});

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String name = (String) params.get("name");
        String javaCode = (String) params.get("javaCode");

        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name is required");
        }
        name = name.trim();
        if (javaCode == null || javaCode.trim().isEmpty()) {
            throw new IllegalArgumentException("javaCode is required");
        }

        // 内置工具名冲突检查
        for (McpToolHandler handler : toolHandlers) {
            if (name.equals(handler.getToolName())) {
                throw new IllegalArgumentException("Command name conflicts with built-in tool: " + name);
            }
        }
        // 已有命令重名检查
        if (commandStorage.exists(name)) {
            throw new IllegalArgumentException("Command already exists: " + name);
        }

        CommandConfig config = new CommandConfig();
        config.setName(name);
        config.setDescription((String) params.get("description"));
        config.setJavaCode(javaCode);
        config.setJarPaths(CommandParamParser.resolveJarPaths(params.get("jarPaths")));

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) params.get("parameters");
        if (parameters != null) {
            config.setParameters(CommandParamParser.parseParameters(parameters));
        }

        CommandConfig saved = commandStorage.save(config);
        mcpService.reloadDynamicCommands();

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("id", saved.getId());
        result.put("name", saved.getName());
        result.put("message", "Command registered successfully. Its MCP tool is available immediately.");

        return result;
    }
}
