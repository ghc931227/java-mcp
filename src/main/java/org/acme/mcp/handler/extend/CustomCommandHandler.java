package org.acme.mcp.handler.extend;

import org.acme.command.model.CommandConfig;
import org.acme.mcp.handler.McpToolHandler;
import org.acme.mcp.runtime.JdkRuntime;

import java.util.HashMap;
import java.util.Map;

/**
 * 将用户通过 add-command 保存的自定义命令包装为 MCP 工具。
 * 工具名即命令名，执行时编译运行命令中保存的 Java 代码。
 */
public class CustomCommandHandler implements McpToolHandler {

    private final CommandConfig command;

    public CustomCommandHandler(CommandConfig command) {
        this.command = command;
    }

    @Override
    public String getToolName() {
        return command.getName();
    }

    @Override
    public String getDescription() {
        return command.getDescription() != null && !command.getDescription().isBlank()
                ? command.getDescription()
                : "User-defined command: " + command.getName();
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        java.util.List<String> required = new java.util.ArrayList<>();

        if (command.getParameters() != null) {
            command.getParameters().forEach((name, def) -> {
                Map<String, Object> prop = new HashMap<>();
                prop.put("type", def.getType() != null ? def.getType() : "string");
                if (def.getDescription() != null) {
                    prop.put("description", def.getDescription());
                }
                if (def.getDefaultValue() != null) {
                    prop.put("default", def.getDefaultValue());
                }
                properties.put(name, prop);
                if (def.isRequired()) {
                    required.add(name);
                }
            });
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required.toArray(new String[0]));
        }

        return schema;
    }

    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String javaCode = command.getJavaCode();
        if (javaCode == null || javaCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Command has no javaCode: " + command.getName());
        }

        Map<String, Object> merged = new HashMap<>();
        if (params != null) {
            merged.putAll(params);
        }

        // 未传参的参数填入默认值
        if (command.getParameters() != null) {
            command.getParameters().forEach((name, def) -> {
                if (!merged.containsKey(name) && def.getDefaultValue() != null) {
                    merged.put(name, def.getDefaultValue());
                }
            });
        }

        JdkRuntime.evalJavaCode(javaCode, merged, command.getJarPaths());

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("result", merged);
        response.put("resultType", merged.getClass().getName());

        return response;
    }
}
