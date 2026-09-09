package org.acme.web.auth;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.acme.web.HeartbeatWatchdog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Web 控制台访问控制。
 * - 控制台可执行任意 SQL 与 Java 代码，必须鉴权。
 * - 所有 /api/* 除 /api/auth/info 外均需携带 Authorization: Bearer &lt;CONSOLE_TOKEN&gt;。
 * - 未配置 CONSOLE_TOKEN 或 CONSOLE_ENABLED=false 时，数据接口返回 503 禁用。
 */
@Provider
@Priority(100)
public class AuthFilter implements ContainerRequestFilter {

    @Inject
    @ConfigProperty(name = "console.token")
    Optional<String> token;

    @Inject
    @ConfigProperty(name = "console.enabled", defaultValue = "true")
    boolean enabled;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        // 静态资源与登录探测接口放行；仅保护数据 API
        if (!path.startsWith("/api/")) {
            return;
        }
        // 任何 /api 请求（含心跳/未授权）均视为来自 mask 的包，刷新心跳看门狗计时
        HeartbeatWatchdog.touch();
        if ("/api/auth/info".equals(path)) {
            return;
        }

        String configured = token.orElse("").trim();
        if (!enabled || configured.isEmpty()) {
            ctx.abortWith(Response.status(503)
                    .entity(java.util.Map.of(
                            "code", "CONSOLE_DISABLED",
                            "message", "Console disabled: set CONSOLE_TOKEN (and CONSOLE_ENABLED=true) to enable"))
                    .build());
            return;
        }

        String auth = ctx.getHeaderString("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            ctx.abortWith(Response.status(401)
                    .entity(java.util.Map.of("code", "UNAUTHORIZED", "message", "Missing bearer token"))
                    .build());
            return;
        }
        String presented = auth.substring("Bearer ".length()).trim();
        if (!constantTimeEquals(configured, presented)) {
            ctx.abortWith(Response.status(401)
                    .entity(java.util.Map.of("code", "UNAUTHORIZED", "message", "Invalid token"))
                    .build());
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}