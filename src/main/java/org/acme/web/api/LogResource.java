package org.acme.web.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.acme.mcp.StdioMcpServer;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 日志中心：读取 DATA_DIR 下的日志文件。
 * 支持按文件、行数 tail，以及基于字节偏移的增量拉取（前端轮询）。
 */
@Path("/api/logs")
@Produces(MediaType.APPLICATION_JSON)
public class LogResource {

    private static java.nio.file.Path logDir() {
        return StdioMcpServer.resolveDataDir();
    }

    /** 列出数据目录下可读的日志文件（按修改时间倒序） */
    @GET
    @Path("/files")
    public List<Map<String, Object>> files() {
        List<Map<String, Object>> result = new ArrayList<>();
        try (var stream = Files.list(logDir())) {
            stream.filter(p -> p.getFileName().toString().startsWith("mcp.log"))
                  .forEach(p -> {
                      try {
                          Map<String, Object> f = new LinkedHashMap<>();
                          f.put("name", p.getFileName().toString());
                          f.put("size", Files.size(p));
                          f.put("modified", Files.getLastModifiedTime(p).toMillis());
                          result.add(f);
                      } catch (IOException ignored) {
                      }
                  });
        } catch (IOException ignored) {
        }
        result.sort((a, b) -> Long.compare((Long) b.get("modified"), (Long) a.get("modified")));
        return result;
    }

    /**
     * 读取日志内容。offset 为字节偏移，用于增量；不传则返回文件末尾 tail 行。
     */
    @GET
    public Map<String, Object> tail(@QueryParam("file") String file,
                                    @QueryParam("offset") Long offset,
                                    @QueryParam("tail") Integer tail,
                                    @QueryParam("keyword") String keyword) {
        String name = (file == null || file.isBlank()) ? "mcp.log" : file.trim();
        java.nio.file.Path p = logDir().resolve(name);
        Map<String, Object> resp = new LinkedHashMap<>();
        try {
            if (!Files.exists(p)) {
                resp.put("exists", false);
                resp.put("lines", List.of());
                resp.put("size", 0L);
                return resp;
            }
            long size = Files.size(p);
            List<String> lines;
            if (offset != null && offset > 0) {
                // 增量模式：从偏移处读取新增内容
                String content = Files.readString(p);
                String slice = offset < content.length() ? content.substring(offset.intValue()) : "";
                lines = slice.isEmpty() ? List.of() : slice.lines().collect(Collectors.toList());
            } else {
                int t = (tail == null || tail < 1) ? 200 : tail;
                // 只读末尾约 tail 行（简单起见读全量后截取）
                String content = Files.readString(p);
                List<String> all = content.lines().collect(Collectors.toList());
                int from = Math.max(0, all.size() - t);
                lines = new ArrayList<>(all.subList(from, all.size()));
            }
            if (keyword != null && !keyword.isBlank()) {
                lines = lines.stream().filter(l -> l.toLowerCase().contains(keyword.toLowerCase()))
                        .collect(Collectors.toList());
            }
            resp.put("exists", true);
            resp.put("lines", lines);
            resp.put("size", size);
        } catch (IOException e) {
            resp.put("exists", false);
            resp.put("error", e.getMessage());
            resp.put("lines", List.of());
        }
        return resp;
    }
}