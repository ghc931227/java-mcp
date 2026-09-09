package org.acme.mcp.handler.extend;

import org.acme.command.model.CommandConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * add-command / edit-command 共用的参数解析工具
 */
final class CommandParamParser {

    private CommandParamParser() {
    }

    /**
     * 解析 parameters 参数: { paramName: { type, description, required, defaultValue } }
     */
    static Map<String, CommandConfig.ParameterDef> parseParameters(Map<String, Object> parameters) {
        Map<String, CommandConfig.ParameterDef> out = new HashMap<>();
        if (parameters == null) {
            return out;
        }
        parameters.forEach((paramName, raw) -> {
            CommandConfig.ParameterDef def = new CommandConfig.ParameterDef();
            if (raw instanceof Map) {
                Map<?, ?> rawDef = (Map<?, ?>) raw;
                def.setType(rawDef.get("type") != null ? String.valueOf(rawDef.get("type")) : "string");
                def.setDescription(rawDef.get("description") != null ? String.valueOf(rawDef.get("description")) : null);
                def.setRequired(rawDef.get("required") != null && Boolean.parseBoolean(String.valueOf(rawDef.get("required"))));
                def.setDefaultValue(rawDef.get("defaultValue"));
            } else {
                def.setType("string");
            }
            out.put(paramName, def);
        });
        return out;
    }

    /**
     * 解析 jarPaths 参数, 兼容 List / 单元素 Map / 逗号分隔字符串等形式 (与 java-exec 一致)
     */
    static List<String> resolveJarPaths(Object raw) {
        if (raw == null) {
            return new ArrayList<>();
        }
        if (raw instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object o : (List<?>) raw) {
                if (o != null && !o.toString().trim().isEmpty()) {
                    out.add(o.toString().trim());
                }
            }
            return out;
        }
        if (raw instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) raw;
            return m.values().stream()
                    .map(String::valueOf)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
        if (raw instanceof String) {
            return Arrays.stream(((String) raw).split("[,;\\n]"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
        List<String> out = new ArrayList<>();
        out.add(raw.toString());
        return out;
    }
}
