package org.acme.mcp.handler.extend;

import jakarta.enterprise.context.ApplicationScoped;
import org.acme.mcp.handler.McpToolHandler;
import org.acme.mcp.runtime.JdkRuntime;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class ExecJavaHandler implements McpToolHandler {

    private static final Logger LOG = Logger.getLogger(ExecJavaHandler.class);
    
    @Override
    public String getToolName() {
        return "java-exec";
    }
    
    @Override
    public String getDescription() {
        return "动态执行 Java 代码片段或完整类, 支持真实 Java 语法(lambda/泛型/try-with-resources 等)。"
                + "统一基于 JDK 实时编译 (javax.tools.JavaCompiler), 编译结果按 code+jars 哈希缓存。"
                + "传 jarPaths 时可直接 import 并调用外部 JAR 中的类(static method/field/反射均可)。"
                + "执行后修改写入 params map, execute 方法返回值会出现在 returnValue 字段。"
                + "代码在服务器进程内运行, 无沙箱, 请勿执行不可信代码 (需要 JDK 运行环境)。";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        properties.put("code", Map.of(
            "type", "string",
            "description", "Java 代码, 可以是方法体(自动包为 public static Object execute(Map))或完整类(含 execute(Map) 或 main(String[]) 方法)。"
                    + "代码中可直接使用 params Map 作为入参和返回值载体。"
        ));
        properties.put("params", Map.of(
            "type", "object",
            "description", "传递给代码的参数, 形参名为 params。代码修改 map 后会作为 result 返回;"
                    + "若 execute 方法有返回值, 会同时出现在 returnValue 字段。",
            "default", Map.of()
        ));
        properties.put("jarPaths", Map.of(
            "type", "array",
            "items", Map.of("type", "string"),
            "description", "外部 JAR 文件绝对路径列表(可选)。"
                    + "编译时 -classpath 自动加入这些 JAR, 让 import 能解析;"
                    + "运行时新建独立 URLClassLoader 加载这些 JAR, 父加载器为系统 ClassLoader, 不影响主进程;"
                    + "每次调用独立类加载器, finally 中关闭并恢复线程上下文 ClassLoader, 严格线程级隔离, 不影响其他调用与主进程状态。"
                    + "示例: 传 jodd-util.jar 可直接写 'import jodd.util.StringUtil; StringUtil.capitalize(\"hi\")'。",
            "default", List.of()
        ));

        schema.put("properties", properties);
        schema.put("required", new String[]{"code"});

        return schema;
    }
    
    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String code = (String) params.get("code");
        
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("code is required");
        }
        
        Object rawParams = params.get("params");
        Map<String, Object> codeParams;
        if (rawParams instanceof Map) {
            codeParams = (Map<String, Object>) rawParams;
        } else if (rawParams == null) {
            codeParams = new HashMap<>();
        } else if (rawParams instanceof String) {
            // MCP 层有时把空对象序列化为空字符串
            String s = ((String) rawParams).trim();
            codeParams = s.isEmpty() || "{}".equals(s) ? new HashMap<>() : new HashMap<>();
            if (!codeParams.isEmpty()) {
                LOG.warnf("params is a non-empty string (%s), treating as empty", s);
            }
        } else {
            LOG.warnf("params is unexpected type %s, treating as empty", rawParams.getClass().getName());
            codeParams = new HashMap<>();
        }
        List<String> jarPaths = resolveJarPaths(params.get("jarPaths"));

        LOG.infof("Executing Java code (%d chars, jarPaths=%d)", code.length(), jarPaths.size());

        try {
            // 基于 JDK javax.tools.JavaCompiler 实时编译执行
            // jarPaths 非空时新建独立 URLClassLoader 加载外部 JAR (线程级隔离)
            Object execResult = JdkRuntime.evalJavaCode(code, codeParams, jarPaths);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("result", codeParams);
            if (execResult != null) {
                response.put("returnValue", execResult);
            }

            LOG.info("Java code executed successfully");

            return response;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to execute Java code");

            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());
            response.put("errorType", e.getClass().getSimpleName());

            return response;
        }
    }

    /**
     * 解析 jarPaths 参数, 兼容以下入参形式:
     * 1) 正常 List<String>
     * 2) MCP 框架对 items:{type:"string"} 数组反序列化异常时退化为单元素 Map (如 {item:"path"})
     * 3) 逗号/换行/分号分隔的字符串
     */
    @SuppressWarnings("unchecked")
    private List<String> resolveJarPaths(Object raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        if (raw instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object o : (List<Object>) raw) {
                if (o != null) {
                    out.add(o.toString());
                }
            }
            return out;
        }
        if (raw instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) raw;
            return m.values().stream()
                    .map(String::valueOf)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
        if (raw instanceof String) {
            return java.util.Arrays.stream(((String) raw).split("[,;\\n]"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
        return List.of(raw.toString());
    }
}
