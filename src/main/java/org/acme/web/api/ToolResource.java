package org.acme.web.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.acme.mcp.handler.McpToolHandler;
import org.acme.mcp.model.ToolDefinition;
import org.acme.mcp.service.McpService;
import org.acme.web.ToolCallRecorder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具元数据与通用调用接口。
 * 将 MCP 的 handlerMap 暴露为 REST，前端据此动态渲染表单（inputSchema）并调用。
 */
@Path("/api/tools")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ToolResource {

    @Inject
    McpService mcpService;

    @Inject
    ToolCallRecorder recorder;

    @GET
    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> result = new ArrayList<>();
        // 与 MCP tools/list 相同的分组排序，保证控制台下拉顺序一致
        for (McpToolHandler handler : mcpService.orderedToolHandlers()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", handler.getToolName());
            item.put("description", handler.getDescription());
            item.put("inputSchema", handler.getInputSchema());
            result.add(item);
        }
        return result;
    }

    @GET
    @Path("/{name}")
    public Map<String, Object> getTool(@PathParam("name") String name) {
        McpToolHandler handler = mcpService.getHandlerMap().get(name);
        if (handler == null) {
            throw new jakarta.ws.rs.NotFoundException("Tool not found: " + name);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", handler.getToolName());
        item.put("description", handler.getDescription());
        item.put("inputSchema", handler.getInputSchema());
        return item;
    }

    /** 通用调用：arguments 直接透传给工具 handler */
    @POST
    @Path("/{name}/invoke")
    public Map<String, Object> invoke(@PathParam("name") String name,
                                     Map<String, Object> requestBody) {
        Map<String, Object> arguments = requestBody == null
                ? new LinkedHashMap<>()
                : (Map<String, Object>) requestBody.getOrDefault("arguments", new LinkedHashMap<>());
        try {
            Object result = mcpService.callTool(name, arguments, "web");
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("result", result);
            return resp;
        } catch (Exception e) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", false);
            resp.put("error", e.getMessage());
            return resp;
        }
    }
}