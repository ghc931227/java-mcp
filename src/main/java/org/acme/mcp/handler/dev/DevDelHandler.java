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
public class DevDelHandler implements McpToolHandler {

    private static final Logger LOG = Logger.getLogger(DevDelHandler.class);
    
    @Override
    public String getToolName() {
        return "dev_del";
    }
    
    @Override
    public String getDescription() {
        return "Delete a file. Path: relative to src/ (e.g. main/java/org/acme/Foo.java).";
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

        // Prevent deletion of critical files/directories
        if (relativePath.equals("pom.xml") || 
            relativePath.startsWith(".git") ||
            relativePath.equals("src/main") ||
            relativePath.equals("src/test")) {
            throw new SecurityException("Cannot delete critical project files/directories");
        }

        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("File not found: " + relativePath);
        }
        
        if (!Files.isRegularFile(filePath)) {
            throw new IllegalArgumentException("Can only delete regular files, not directories: " + relativePath);
        }
        
        // Delete the file
        Files.delete(filePath);
        
        LOG.infof("File deleted: %s", relativePath);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("path", relativePath);
        result.put("message", "File deleted successfully. Quarkus will auto-reload if needed.");
        
        return result;
    }
}
