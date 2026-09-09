package org.acme.mcp.handler.dev;

import jakarta.enterprise.context.ApplicationScoped;
import org.acme.mcp.handler.McpToolHandler;
import org.acme.util.DevPathUtil;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class DevRestartHandler implements McpToolHandler {

    private static final Logger LOG = Logger.getLogger(DevRestartHandler.class);
    
    @Override
    public String getToolName() {
        return "dev_restart";
    }
    
    @Override
    public String getDescription() {
        return "Trigger Quarkus dev mode hot reload by touching a file";
    }
    
    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }
    
    @Override
    public Object execute(Map<String, Object> params) throws Exception {
        // Quarkus dev mode watches for file changes
        // We can trigger a reload by touching any source file
        Path triggerFile = findTriggerFile();
        
        if (triggerFile == null) {
            throw new IllegalStateException("No suitable file found to trigger reload");
        }
        
        // Touch the file to update its modification time
        touchFile(triggerFile);
        
        LOG.info("Triggered Quarkus hot reload");
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Quarkus hot reload triggered. Application will restart shortly.");
        result.put("triggerFile", DevPathUtil.PROJECT_ROOT + "/" + triggerFile.toString().replace("\\", "/"));
        
        return result;
    }
    
    private Path findTriggerFile() throws IOException {
        // Try to find application.properties first
        Path appProperties = Paths.get(DevPathUtil.PROJECT_ROOT.toString(), "src/main/resources/application.properties");
        if (Files.exists(appProperties)) {
            return appProperties;
        }
        
        // Try to find any Java file in the project
        Path srcMain = Paths.get(DevPathUtil.PROJECT_ROOT.toString(), "src/main/java");
        if (Files.exists(srcMain)) {
            try (var stream = Files.walk(srcMain)) {
                return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .findFirst()
                    .orElse(null);
            }
        }
        
        return null;
    }
    
    private void touchFile(Path file) throws IOException {
        // Read current content
        byte[] content = Files.readAllBytes(file);
        
        // Write it back (this updates the modification time)
        Files.write(file, content, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
