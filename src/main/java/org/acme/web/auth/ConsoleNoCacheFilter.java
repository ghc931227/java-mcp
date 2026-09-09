package org.acme.web.auth;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;

/**
 * 控制台接口禁用浏览器缓存，避免 DevTools/浏览器缓存旧版页面资源。
 */
@Provider
public class ConsoleNoCacheFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) throws IOException {
        String path = request.getUriInfo().getPath();
        if (path.startsWith("console") || path.startsWith("/console") || path.startsWith("api") || path.startsWith("/api")) {
            response.getHeaders().putSingle("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.getHeaders().putSingle("Pragma", "no-cache");
            response.getHeaders().putSingle("Expires", "0");
        }
    }
}