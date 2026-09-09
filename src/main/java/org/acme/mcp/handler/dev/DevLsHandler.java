package org.acme.mcp.handler.dev;

import jakarta.enterprise.context.ApplicationScoped;
import org.acme.mcp.handler.McpToolHandler;
import org.acme.util.DevPathUtil;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@ApplicationScoped
public class DevLsHandler implements McpToolHandler {

    private static final Logger LOG = Logger.getLogger(DevLsHandler.class);
    
    @Override
    public String getToolName() {
        return "dev_ls";
    }
    
    @Override
    public String getDescription() {
        return "Show file tree. Path: relative to src/ (default: src/).";
    }
    
    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        properties.put("path", Map.of(
            "type", "string",
            "description", "Path: relative to src/ (default: src/)"
        ));
        properties.put("maxDepth", Map.of(
            "type", "integer",
            "description", "Maximum depth to traverse (default: 6)"
        ));
        
        schema.put("properties", properties);
        return schema;
    }
    
    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String relativePath = (String) params.getOrDefault("path", "");
        int maxDepth = params.containsKey("maxDepth") ?
            ((Number) params.get("maxDepth")).intValue() : 6;
        
        // 解析源码相对路径（仅允许 src/ 下的源码文件）
        Path startPath = (relativePath == null || relativePath.isEmpty())
                ? DevPathUtil.SOURCE_ROOT
                : DevPathUtil.resolve(relativePath);
        
        if (!Files.exists(startPath)) {
            throw new IllegalArgumentException("Path not found: " + relativePath);
        }
        
        return buildJsonTree(startPath, maxDepth, 0);
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildJsonTree(Path path, int maxDepth, int currentDepth) throws IOException {
        Map<String, Object> node = new HashMap<>();
        String relPath = DevPathUtil.PROJECT_ROOT.relativize(path).toString().replace("\\", "/");
        node.put("path", relPath.isEmpty() ? "/" : relPath);

        // 显式标记类型：达到 maxDepth 的目录不再返回 children，前端需靠 type 区分文件/目录
        boolean isDir = Files.isDirectory(path);
        node.put("type", isDir ? "dir" : "file");
        if (!isDir || currentDepth >= maxDepth) {
            return node;
        }
        
        List<Map<String, Object>> children = new ArrayList<>();
        try (var stream = Files.list(path)) {
            stream.filter(p -> !shouldIgnore(p))
                  .sorted((p1, p2) -> {
                      boolean d1 = Files.isDirectory(p1);
                      boolean d2 = Files.isDirectory(p2);
                      if (d1 != d2) return d1 ? -1 : 1;
                      return p1.getFileName().toString().compareTo(p2.getFileName().toString());
                  })
                  .forEach(p -> {
                      try {
                          children.add(buildJsonTree(p, maxDepth, currentDepth + 1));
                      } catch (IOException e) {
                          // skip inaccessible entries
                      }
                  });
        }
        
        node.put("children", children);
        return node;
    }
    
    private boolean shouldIgnore(Path path) {
        String name = path.getFileName().toString();
        return name.equals("target") || 
               name.equals(".git") || 
               name.equals(".idea") ||
               name.equals("node_modules") ||
               name.startsWith(".");
    }
}
