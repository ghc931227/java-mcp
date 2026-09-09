package org.acme.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import org.acme.command.storage.CommandStorage;
import org.acme.jdbc.storage.ConnectionStorage;
import org.acme.mcp.model.McpRequest;
import org.acme.mcp.model.McpResponse;
import org.acme.mcp.service.McpService;
import org.acme.web.HeartbeatWatchdog;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@QuarkusMain
public class StdioMcpServer implements QuarkusApplication {

    private static final Logger LOG = Logger.getLogger(StdioMcpServer.class);
    
    @Inject
    McpService mcpService;
    
    @Inject
    ConnectionStorage connectionStorage;

    @Inject
    CommandStorage commandStorage;
    
    @Inject
    ObjectMapper objectMapper;
    
    public static void main(String... args) {
        preCreateLogDir();
        Quarkus.run(StdioMcpServer.class, args);
    }

    /** Quarkus 不会自动创建日志文件的父目录，需在日志初始化前预建（默认 ~/.javatool-mcp，可被 DATA_DIR/QUARKUS_LOG_FILE_PATH 覆盖） */
    private static void preCreateLogDir() {
        try {
            Path logFile = resolveDataDir().resolve("mcp.log");
            String customLogPath = System.getenv("QUARKUS_LOG_FILE_PATH");
            if (customLogPath != null && !customLogPath.isEmpty()) {
                logFile = Path.of(customLogPath);
            }
            Files.createDirectories(logFile.toAbsolutePath().getParent());
        } catch (Exception ignored) {
            // 目录创建失败仅影响文件日志，不阻断启动
        }
    }

    /** 与 application.properties 的 DATA_DIR 默认值保持一致 */
    public static Path resolveDataDir() {
        String dataDir = System.getenv("DATA_DIR");
        if (dataDir == null || dataDir.isEmpty()) {
            return Path.of(System.getProperty("user.home"), ".javatool-mcp");
        }
        return Path.of(dataDir);
    }
    
    @Override
    public int run(String... args) throws Exception {
        LOG.info("Starting JavaTool MCP Server via stdio...");
        
        // Write PID file for cleanup on next startup
        long pid = ProcessHandle.current().pid();
        Path pidFile = resolveDataDir().resolve("mcp.pid");
        Files.createDirectories(pidFile.getParent());
        Files.writeString(pidFile, String.valueOf(pid));
        LOG.infof("PID: %d, written to %s", pid, pidFile.toAbsolutePath());
        
        connectionStorage.initialize();
        commandStorage.initialize();
        mcpService.initialize();

        // mask 自动拉起（注入 MASK_HEARTBEAT=1）时启用心跳看门狗：3s 无任何包自动退出，避免 jar 进程残留；
        // 直接 stdio / 手动 Web 控制台模式未设置该变量，不受影响
        HeartbeatWatchdog.startIfEnabled();
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter writer = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        Object writeLock = new Object();
        // 异步处理请求：慢工具（如大文件导入）不再阻塞读循环和其他请求；响应按 id 匹配，允许乱序写回
        ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "mcp-request");
            t.setDaemon(true);
            return t;
        });

        // stdin 读循环放到独立守护线程：run() 不能阻塞在 readLine() / sleep 上，
        // 否则 Quarkus dev 热加载时旧实例的 run() 永不返回，重启被永久挂起（HTTP 假死）
        Thread stdinThread = new Thread(() -> {
            try {
                String requestLine;
                while ((requestLine = reader.readLine()) != null) {
                    final String req = requestLine;
                    executor.submit(() -> {
                        try {
                            McpRequest request = objectMapper.readValue(req, McpRequest.class);

                            // 只记录方法名，避免请求正文（含数据库密码等敏感信息）落入日志
                            LOG.debugf("Received request, method: %s", request.getMethod());

                            McpResponse response = mcpService.processRequest(request);

                            // JSON-RPC 通知（无 id）不需要响应
                            if (response == null) {
                                return;
                            }

                            String responseJson = objectMapper.writeValueAsString(response);
                            synchronized (writeLock) {
                                writer.println(responseJson);
                                writer.flush();
                            }

                            LOG.debugf("Sent response, method: %s", request.getMethod());

                        } catch (Exception e) {
                            LOG.error("Error processing request", e);
                            try {
                                McpResponse errorResponse = McpResponse.error(null, -32700, "Parse error: " + e.getMessage());
                                String errorJson = objectMapper.writeValueAsString(errorResponse);
                                synchronized (writeLock) {
                                    writer.println(errorJson);
                                    writer.flush();
                                }
                            } catch (Exception ex) {
                                LOG.error("Failed to send error response", ex);
                            }
                        }
                    });
                }
            } catch (IOException ignored) {
                // stdin 读取异常按 EOF 处理
            }

            // stdin closed (EOF) — client disconnected
            LOG.info("stdin closed (client disconnected).");
            executor.shutdownNow();
            // 默认退出进程：MCP 主机通过关闭 stdin 停止子进程，保持标准协议语义。
            // 设置 MCP_STDIN_KEEPALIVE（如仅运行 Web 控制台/无 MCP 客户端）时保持进程存活。
            if (System.getenv("MCP_STDIN_KEEPALIVE") == null) {
                LOG.info("Exiting on stdin EOF.");
                Quarkus.asyncExit();
            } else {
                LOG.info("MCP_STDIN_KEEPALIVE set, keeping process alive for Web console.");
            }
        }, "mcp-stdio");
        stdinThread.setDaemon(true);
        stdinThread.start();

        // 阻塞直到 Quarkus 停止；dev 热加载时旧实例随 stop 返回，应用才能正常重启
        Quarkus.waitForExit();
        return 0;
    }
}
