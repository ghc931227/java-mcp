package org.acme.mcp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.acme.command.model.CommandConfig;
import org.acme.command.storage.CommandStorage;
import org.acme.mcp.handler.extend.CustomCommandHandler;
import org.acme.mcp.handler.McpToolHandler;
import org.acme.mcp.model.McpRequest;
import org.acme.mcp.model.McpResponse;
import org.acme.mcp.model.ToolDefinition;
import org.acme.web.ToolCallRecorder;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class McpService {

    private static final Logger LOG = Logger.getLogger(McpService.class);

    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of(
            "2025-06-18",
            "2025-03-26",
            "2024-11-05",
            "2024-10-07",
            "2024-06-25"
    );

    @Inject
    Instance<McpToolHandler> toolHandlers;

    @Inject
    CommandStorage commandStorage;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ToolCallRecorder recorder;

    private Map<String, McpToolHandler> handlerMap;

    /** 是否注册 dev_* 开发工具 */
    private boolean devToolsEnabled;

    public synchronized void initialize() {
        devToolsEnabled = isDevToolsEnabled();
        LOG.infof("Dev tools enabled: %s", devToolsEnabled);

        Map<String, McpToolHandler> map = new LinkedHashMap<>();
        for (McpToolHandler handler : toolHandlers) {
            String toolName = handler.getToolName();
            // 非源码启动（jar 启动）时不注册 dev_* 开发工具
            if (!devToolsEnabled && toolName.startsWith("dev_")) {
                LOG.infof("Skipped dev tool (not source launch): %s", toolName);
                continue;
            }
            map.put(toolName, handler);
            LOG.infof("Registered tool: %s", toolName);
        }
        // 原子替换引用：避免其他线程遍历旧 map 时被并发写入
        handlerMap = map;

        // 注册用户通过 add-command 保存的动态命令为工具
        reloadDynamicCommands();
    }

    /**
     * 根据存储中的最新命令列表重新注册动态命令工具（add/edit/delete 后调用，立即生效，无需重启）。
     */
    public synchronized void reloadDynamicCommands() {
        if (handlerMap == null) {
            initialize();
            return;
        }
        // 移除旧的动态命令工具（按创建时间顺序）重新注册
        handlerMap.values().removeIf(handler -> handler instanceof CustomCommandHandler);
        for (CommandConfig command : commandStorage.listAll()) {
            String toolName = command.getName();
            if (handlerMap.containsKey(toolName)) {
                LOG.warnf("Command name conflicts with existing tool, skipped: %s", toolName);
                continue;
            }
            handlerMap.put(toolName, new CustomCommandHandler(command));
            LOG.infof("Registered dynamic command tool: %s", toolName);
        }
    }

    /**
     * 是否启用 dev_* 开发工具：按 Quarkus 启动模式自动检测。
     * 源码启动（quarkus:dev）为 DEVELOPMENT，jar 启动为 NORMAL。
     */
    private boolean isDevToolsEnabled() {
        return LaunchMode.current() == LaunchMode.DEVELOPMENT;
    }

    public McpResponse processRequest(McpRequest request) {
        try {
            if (!"2.0".equals(request.getJsonrpc())) {
                return McpResponse.error(request.getId(), -32600, "Invalid JSON-RPC version");
            }

            String method = request.getMethod();

            if ("initialize".equals(method)) {
                return handleInitialize(request);
            } else if ("ping".equals(method)) {
                return McpResponse.success(request.getId(), Map.of());
            } else if ("tools/list".equals(method)) {
                return handleListTools(request);
            } else if ("tools/call".equals(method)) {
                return handleToolCall(request);
            }

            if (request.getId() == null) {
                LOG.debugf("Ignoring notification: %s", method);
                return null;
            }

            return McpResponse.error(request.getId(), -32601, "Method not found: " + method);

        } catch (Exception e) {
            LOG.error("Error processing request", e);
            return McpResponse.error(request.getId(), -32603, "Internal error: " + e.getMessage());
        }
    }

    private McpResponse handleInitialize(McpRequest request) {
        String requestedVersion = null;
        if (request.getParams() != null) {
            requestedVersion = (String) request.getParams().get("protocolVersion");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("protocolVersion", negotiateProtocolVersion(requestedVersion));
        result.put("serverInfo", Map.of(
                "name", "javatool-mcp",
                "version", "1.0.0"
        ));
        result.put("capabilities", Map.of(
                "tools", Map.of("supported", true)
        ));
        return McpResponse.success(request.getId(), result);
    }

    private String negotiateProtocolVersion(String requestedVersion) {
        if (requestedVersion != null && SUPPORTED_PROTOCOL_VERSIONS.contains(requestedVersion)) {
            return requestedVersion;
        }
        return SUPPORTED_PROTOCOL_VERSIONS.get(0);
    }

    private McpResponse handleListTools(McpRequest request) {
        // 每次都重建注册表：Quarkus 热重载保留旧 bean 实例，
        // 若只在首次初始化，dev_add 新增的 Handler 将永远不会注册
        initialize();

        List<ToolDefinition> tools = orderedToolHandlers().stream()
                // list 仅返回工具名与调用所需的 inputSchema，描述等详情由 get-tool-desc 按需获取
                .map(handler -> new ToolDefinition(
                        handler.getToolName(),
                        null,
                        handler.getInputSchema()
                ))
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("tools", tools);

        return McpResponse.success(request.getId(), result);
    }

    /**
     * 返回与 MCP tools/list 一致排序的 handler 列表（按能力分组、组内字母序，动态命令保持创建顺序）。
     * 控制台 /api/tools 复用，保证 Web 下拉顺序与 MCP 客户端看到的一致。
     * 注意：先在锁内复制快照再排序——直接对流式遍历活 map，并发写入会导致 sized sort 数组越界。
     */
    public synchronized List<McpToolHandler> orderedToolHandlers() {
        if (handlerMap == null) {
            initialize();
        }
        List<McpToolHandler> snapshot = new ArrayList<>(handlerMap.values());
        snapshot.sort((a, b) -> {
            int ga = toolGroupOrder(a.getToolName());
            int gb = toolGroupOrder(b.getToolName());
            if (ga != gb) {
                return Integer.compare(ga, gb);
            }
            if (ga == GROUP_DYNAMIC) {
                // 动态命令保持注册（创建时间）顺序，不按名称排序
                return 0;
            }
            return a.getToolName().compareTo(b.getToolName());
        });
        return snapshot;
    }

    /** 工具管理/元工具 */
    private static final int GROUP_META = 0;
    /** Java 执行 */
    private static final int GROUP_JAVA = 1;
    /** 开发/文件操作 */
    private static final int GROUP_DEV = 2;
    /** 数据库操作 */
    private static final int GROUP_JDBC = 3;
    /** 用户动态命令 */
    private static final int GROUP_DYNAMIC = 4;

    private static int toolGroupOrder(String toolName) {
        if (isMetaTool(toolName)) {
            return GROUP_META;
        }
        if ("java-exec".equals(toolName)) {
            return GROUP_JAVA;
        }
        if (toolName.startsWith("dev_")) {
            return GROUP_DEV;
        }
        if (toolName.startsWith("jdbc-")) {
            return GROUP_JDBC;
        }
        return GROUP_DYNAMIC;
    }

    private static boolean isMetaTool(String toolName) {
        return "add-command".equals(toolName) || "edit-command".equals(toolName)
                || "delete-command".equals(toolName) || "get-command".equals(toolName)
                || "list-commands".equals(toolName) || "tool-desc".equals(toolName);
    }

    private McpResponse handleToolCall(McpRequest request) {
        if (handlerMap == null) {
            initialize();
        }

        Map<String, Object> params = request.getParams();
        if (params == null) {
            return McpResponse.error(request.getId(), -32602, "Missing params");
        }

        String toolName = (String) params.get("name");
        if (toolName == null) {
            return McpResponse.error(request.getId(), -32602, "Missing tool name");
        }

        McpToolHandler handler = handlerMap.get(toolName);
        if (handler == null) {
            // 热重载后可能新增了 Handler，重建注册表后重试一次
            initialize();
            handler = handlerMap.get(toolName);
        }
        if (handler == null) {
            return toolError(request.getId(), "Unknown tool: " + toolName);
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = (Map<String, Object>) params.get("arguments");
            if (arguments == null) {
                arguments = new HashMap<>();
            }

            Object result = callTool(toolName, arguments, "stdio");

            Map<String, Object> content = new HashMap<>();
            content.put("type", "text");
            content.put("text", objectMapper.writeValueAsString(result));

            Map<String, Object> response = new HashMap<>();
            response.put("content", List.of(content));

            return McpResponse.success(request.getId(), response);

        } catch (Exception e) {
            LOG.errorf(e, "Tool execution failed: %s", toolName);
            return toolError(request.getId(), "Tool execution failed: " + e.getMessage());
        }
    }

    /** 返回当前已注册的全部工具（Web 控制台读取注册表用） */
    public synchronized Map<String, McpToolHandler> getHandlerMap() {
        if (handlerMap == null) {
            initialize();
        }
        return handlerMap;
    }

    /**
     * 工具调用统一入口：MCP 与 Web 控制台共用，负责查找工具、执行并记录调用历史。
     * 支持 MCP 热重载后新增的 Handler（调度时若首次未命中则重建注册表重试一次）。
     */
    public Object callTool(String toolName, Map<String, Object> arguments, String source) throws Exception {
        if (handlerMap == null) {
            initialize();
        }
        if (arguments == null) {
            arguments = new HashMap<>();
        }

        McpToolHandler handler = handlerMap.get(toolName);
        if (handler == null) {
            // 热重载后可能新增了 Handler，重建注册表后重试一次
            initialize();
            handler = handlerMap.get(toolName);
        }
        if (handler == null) {
            throw new IllegalArgumentException("Unknown tool: " + toolName);
        }

        long start = System.currentTimeMillis();
        try {
            Object result = handler.execute(arguments);
            recorder.record(toolName, source, arguments, System.currentTimeMillis() - start, true, result, null);
            return result;
        } catch (Exception e) {
            LOG.errorf(e, "Tool execution failed: %s", toolName);
            recorder.record(toolName, source, arguments, System.currentTimeMillis() - start, false, null, e.getMessage());
            throw e;
        }
    }

    private McpResponse toolError(Object id, String message) {
        Map<String, Object> content = new HashMap<>();
        content.put("type", "text");
        content.put("text", message);

        Map<String, Object> response = new HashMap<>();
        response.put("content", List.of(content));
        response.put("isError", true);

        return McpResponse.success(id, response);
    }
}
