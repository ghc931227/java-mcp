package org.acme.mcp.handler.dev;

import jakarta.enterprise.context.ApplicationScoped;
import org.acme.mcp.handler.McpToolHandler;
import org.acme.util.DevPathUtil;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class DevGetFileHandler implements McpToolHandler {

    private static final Logger LOG = Logger.getLogger(DevGetFileHandler.class);
    
    @Override
    public String getToolName() {
        return "dev_get_file";
    }
    
    @Override
    public String getDescription() {
        return "Get file content. Path: relative to src/ (e.g. main/java/org/acme/Foo.java).";
    }
    
    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        properties.put("path", Map.of(
            "type", "string",
            "description", "Path: relative to src/ (e.g. main/java/org/acme/Foo.java)"
        ));
        
        schema.put("properties", properties);
        schema.put("required", new String[]{"path"});
        
        return schema;
    }
    
    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String relativePath = (String) params.get("path");
        
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw new IllegalArgumentException("path is required");
        }
        
        // 解析源码相对路径（仅允许 src/ 下的源码文件）
        Path filePath = DevPathUtil.resolve(relativePath);
        
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("File not found: " + relativePath);
        }
        
        if (!Files.isRegularFile(filePath)) {
            throw new IllegalArgumentException("Path is not a regular file: " + relativePath);
        }
        
        // Read file content
        String content = Files.readString(filePath);
        
        LOG.infof("File read: %s (%d bytes)", relativePath, content.length());
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("path", relativePath);
        result.put("content", content);
        result.put("size", content.length());
        result.put("lines", content.split("\n").length);
        
        return result;
    }
}
