package org.acme.web.api;

import io.quarkus.runtime.LaunchMode;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.acme.mcp.service.McpService;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 只读监控接口：运行状态、已注册工具统计。
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class StatusResource {

    private static final long START = System.currentTimeMillis();

    @Inject
    McpService mcpService;

    @ConfigProperty(name = "console.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "console.token")
    Optional<String> token;

    @GET
    @Path("/auth/info")
    public Map<String, Object> authInfo() {
        boolean tokenConfigured = enabled && token.orElse("").trim().length() > 0;
        Map<String, Object> m = new HashMap<>();
        // 未配置 CONSOLE_TOKEN 时与 AuthFilter(503) 语义保持一致：视为禁用
        m.put("enabled", tokenConfigured);
        m.put("tokenConfigured", tokenConfigured);
        return m;
    }

    /** mask 心跳端点（每 1s 一次）；计时刷新统一由 AuthFilter 完成，这里仅立即应答 */
    @POST
    @Path("/heartbeat")
    public Map<String, Object> heartbeat() {
        return Map.of("ok", true);
    }

    @GET
    @Path("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new HashMap<>();
        m.put("mode", LaunchMode.current() == LaunchMode.DEVELOPMENT ? "dev" : "prod");
        m.put("uptimeMs", System.currentTimeMillis() - START);
        m.put("pid", ProcessHandle.current().pid());
        m.put("javaVersion", System.getProperty("java.version"));
        m.put("toolCount", mcpService.getHandlerMap().size());
        m.put("consoleEnabled", enabled);
        m.put("consoleAuthInfo", authInfo());
        return m;
    }
}