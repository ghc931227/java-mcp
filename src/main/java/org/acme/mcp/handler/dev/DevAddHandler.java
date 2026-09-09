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
public class DevAddHandler implements McpToolHandler {

    private static final Logger LOG = Logger.getLogger(DevAddHandler.class);
    
    @Override
    public String getToolName() {
        return "dev_add";
    }
    
    @Override
    public String getDescription() {
        return "Add a new file. Path: relative to src/ (e.g. main/java/org/acme/Foo.java).";
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
        properties.put("content", Map.of(
            "type", "string",
            "description", "Content for the new file (default: empty)"
        ));
        
        schema.put("properties", properties);
        schema.put("required", new String[]{"path"});
        
        return schema;
    }
    
    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        String relativePath = (String) params.get("path");
        String content = (String) params.getOrDefault("content", "");
        
        if (relativePath == null || relativePath.trim().isEmpty()) {
            throw new IllegalArgumentException("path is required");
        }
        
        // 解析源码相对路径（仅允许 src/ 下的源码文件）
        Path filePath = DevPathUtil.resolve(relativePath);
        
        if (Files.exists(filePath)) {
            throw new IllegalArgumentException("File already exists: " + relativePath);
        }
        
        // Create parent directories if needed
        Path parentDir = filePath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }
        
        // Create the file
        Files.writeString(filePath, content);
        
        LOG.infof("File created: %s", relativePath);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("path", relativePath);
        result.put("message", "File created successfully. Quarkus will auto-reload if needed.");
        result.put("size", content.length());
        
        return result;
    }
}
