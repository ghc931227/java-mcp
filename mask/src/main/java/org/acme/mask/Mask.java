package org.acme.mask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * JavaTool MCP stdio 代理（mask）。
 *
 * <p>对 MCP 宿主表现为标准 stdio server；自身不监听端口，把请求翻译为 HTTP 调用后端 REST：
 * <ul>
 *   <li>initialize / ping —— 本地应答</li>
 *   <li>tools/list —— GET /api/tools</li>
 *   <li>tools/call —— POST /api/tools/{name}/invoke，结果包装为 MCP text content</li>
 * </ul>
 *
 * <p>启动时若后端未就绪且存在启动脚本，自动调用脚本拉起后端并等待就绪（Windows 用 start.bat，
 * 其他系统用 start.sh）；启动方式由环境变量 BACKEND_LAUNCH_MODE 控制（source=mvn quarkus:dev /
 * jar=quarkus-run.jar / binary=native 二进制，默认 jar）。运行期间每 1s 向后端发送心跳
 * （POST /api/heartbeat）；stdin 关闭（宿主退出）后等在途请求完成即退出，停止心跳后由后端
 * 看门狗（MASK_HEARTBEAT）在 3s 无包时自动关闭后端，避免 jar 进程残留。
 *
 * <p>环境变量：CONSOLE_PORT（默认 8080）、CONSOLE_TOKEN（必须）、
 * MASK_START_SCRIPT（默认按平台 start.bat/start.sh，相对 mask 工作目录）、
 * BACKEND_LAUNCH_MODE（source/jar/binary）。向拉起的后端注入 MCP_STDIN_KEEPALIVE=1
 * 与 MASK_HEARTBEAT=1（后端心跳看门狗开关，仅 mask 拉起场景生效）。
 * 日志一律走 stderr，不污染 stdout 协议。
 */
public class Mask {

    private static final String PORT = env("CONSOLE_PORT", "8080");
    private static final String TOKEN = env("CONSOLE_TOKEN", "");
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final String START_SCRIPT = env("MASK_START_SCRIPT", WINDOWS ? "start.bat" : "start.sh");
    private static final String LAUNCH_MODE = env("BACKEND_LAUNCH_MODE", "jar");
    private static final String BASE = "http://127.0.0.1:" + PORT;

    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final Object WRITE_LOCK = new Object();
    private static final PrintStream STDOUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    private static volatile boolean backendReady = false;

    public static void main(String[] args) throws Exception {
        log("mask starting, backend=" + BASE + ", launchMode=" + LAUNCH_MODE + ", startScript=" + START_SCRIPT);
        ensureBackend();
        startHeartbeat();

        ExecutorService executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mask-req");
            t.setDaemon(true);
            return t;
        });

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            final String request = line;
            executor.submit(() -> handleSafely(request));
        }
        // stdin 关闭后等待在途请求完成（工具执行最长 1800s）
        log("stdin closed, waiting for in-flight requests...");
        executor.shutdown();
        log("mask exiting");
        System.exit(0);
    }

    // ---------- 后端就绪保障 ----------

    /** 探测后端；未就绪则调用 start.bat/start.sh（按平台选择，BACKEND_LAUNCH_MODE 决定 source/jar/binary）拉起并轮询等待。 */
    private static void ensureBackend() {
        if (probe()) {
            backendReady = true;
            log("backend already alive");
            return;
        }
        Path script = Path.of(START_SCRIPT);
        if (!Files.exists(script)) {
            log("backend not alive and start script not found at: " + script.toAbsolutePath()
                    + ", waiting for backend to appear...");
            waitForBackend(Duration.ofSeconds(120));
            return;
        }
        log("backend not alive, spawning backend via: " + script.toAbsolutePath());
        try {
            // 脚本内部 cd 到自身所在目录；CONSOLE_PORT/CONSOLE_TOKEN/DATA_DIR/BACKEND_LAUNCH_MODE 等环境变量自动继承
            ProcessBuilder pb = WINDOWS
                    ? new ProcessBuilder("cmd", "/c", script.toAbsolutePath().toString())
                    : new ProcessBuilder("bash", script.toAbsolutePath().toString());
            pb.environment().put("MCP_STDIN_KEEPALIVE", "1");
            // 启用后端心跳看门狗：后端 3s 收不到任何包（含本进程心跳）即自动退出
            pb.environment().put("MASK_HEARTBEAT", "1");
            // mask 自身即 JDK21 启动，注入 JAVA_HOME 供脚本的 jar/source 模式使用
            pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
            // 后端 stdout/stderr 不能桥接到 mask 的 stdout（stdout 是 MCP 协议通道）；日志已写 DATA_DIR/mcp.log
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start();
        } catch (Exception e) {
            log("failed to spawn backend: " + e.getMessage());
        }
        waitForBackend(Duration.ofSeconds(180));
    }

    /** 就绪判定：/api/tools 返回非空数组（HTTP 存活且工具注册完成）。 */
    private static boolean probe() {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + "/api/tools"))
                    .timeout(Duration.ofSeconds(3)).GET();
            if (!TOKEN.isBlank()) {
                b.header("Authorization", "Bearer " + TOKEN);
            }
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && !M.readTree(r.body()).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static void waitForBackend(Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (probe()) {
                backendReady = true;
                log("backend is ready");
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log("WARN: backend not ready after " + timeout.getSeconds() + "s, requests will fail until it starts");
    }

    // ---------- 心跳 ----------

    /**
     * 每 1s 向后端发一次心跳（POST /api/heartbeat），证明 mask 仍在运行。
     * 后端看门狗（MASK_HEARTBEAT）连续 3s 收不到任何包即自行退出。
     * 后端未就绪/已关闭时静默重试；stdin 关闭后的在途请求等待期间持续发送，
     * 保证长工具调用不被中断，mask 最终退出后后端才随之关闭。
     */
    private static void startHeartbeat() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + "/api/heartbeat"))
                            .timeout(Duration.ofSeconds(2))
                            .POST(HttpRequest.BodyPublishers.noBody());
                    if (!TOKEN.isBlank()) {
                        b.header("Authorization", "Bearer " + TOKEN);
                    }
                    HTTP.send(b.build(), HttpResponse.BodyHandlers.discarding());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignored) {
                    // 后端未就绪或已关闭：静默重试
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "mask-heartbeat");
        t.setDaemon(true);
        t.start();
        log("heartbeat started (interval 1s)");
    }

    // ---------- 协议处理 ----------

    private static void handleSafely(String line) {
        try {
            handle(line);
        } catch (Throwable e) {
            // 捕获 Throwable：小堆下 OutOfMemoryError 等也返回明确错误，避免静默丢响应
            log("error handling request: " + e);
        }
    }

    private static void handle(String line) {
        JsonNode req;
        try {
            req = M.readTree(line);
        } catch (Exception e) {
            log("ignore non-JSON input");
            return;
        }
        JsonNode id = req.get("id");
        if (id == null || id.isNull()) {
            return; // 通知（initialized/cancelled 等）：忽略
        }
        String method = req.path("method").asText("");
        ObjectNode resp = M.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id);
        try {
            switch (method) {
                case "initialize" -> resp.set("result", initializeResult());
                case "ping" -> resp.set("result", M.createObjectNode());
                case "tools/list" -> resp.set("result", listTools());
                case "tools/call" -> resp.set("result", callTool(req.path("params")));
                default -> jsonRpcError(resp, -32601, "Method not found: " + method);
            }
        } catch (Throwable e) {
            // Throwable：OOM 等严重错误也返回 JSON-RPC error，客户端可感知
            jsonRpcError(resp, -32000, summarize(e));
        }
        write(resp);
    }

    private static void jsonRpcError(ObjectNode resp, int code, String message) {
        ObjectNode err = resp.putObject("error");
        err.put("code", code);
        err.put("message", message);
    }

    private static ObjectNode initializeResult() {
        ObjectNode result = M.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        result.putObject("serverInfo").put("name", "javatool-mcp-mask").put("version", "1.0.0");
        result.putObject("capabilities").putObject("tools").put("supported", true);
        return result;
    }

    private static ObjectNode listTools() throws Exception {
        JsonNode tools = M.readTree(http("GET", "/api/tools", null, true));
        ObjectNode result = M.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    private static ObjectNode callTool(JsonNode params) throws Exception {
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Missing tool name");
        }
        ObjectNode body = M.createObjectNode();
        body.set("arguments", params.path("arguments"));

        JsonNode rest = M.readTree(http("POST", "/api/tools/" + name + "/invoke", body.toString(), false));
        ObjectNode result = M.createObjectNode();
        if (rest.path("ok").asBoolean(false)) {
            ObjectNode content = result.putArray("content").addObject();
            content.put("type", "text");
            content.put("text", M.writeValueAsString(rest.path("result")));
        } else {
            String message = rest.path("error").asText("Tool call failed");
            ObjectNode content = result.putArray("content").addObject();
            content.put("type", "text");
            content.put("text", message);
            result.put("isError", true);
        }
        return result;
    }

    // ---------- HTTP ----------

    private static String http(String method, String path, String jsonBody, boolean mustBeReady) throws Exception {
        if (mustBeReady && !backendReady && !probe()) {
            throw new IllegalStateException("backend not reachable at " + BASE);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path));
        if (!TOKEN.isBlank()) {
            b.header("Authorization", "Bearer " + TOKEN);
        }
        if (jsonBody != null) {
            b.header("Content-Type", "application/json");
            b.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            b.GET();
        }
        // 不设请求超时：工具执行可达 1800s
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            String msg = extractMessage(r.body());
            throw new IllegalStateException("HTTP " + r.statusCode() + " " + path
                    + (msg.isBlank() ? "" : ": " + msg));
        }
        return r.body();
    }

    /** 从错误响应体中提取 message 字段，失败则返回原文（截断）。 */
    private static String extractMessage(String body) {
        try {
            JsonNode n = M.readTree(body);
            String msg = n.path("message").asText("");
            if (!msg.isBlank()) {
                return msg;
            }
        } catch (Exception ignored) {
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    private static String summarize(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.toString() : m;
    }

    // ---------- 杂项 ----------

    private static void write(ObjectNode resp) {
        String s;
        try {
            s = M.writeValueAsString(resp);
        } catch (Exception e) {
            return;
        }
        synchronized (WRITE_LOCK) {
            STDOUT.println(s);
        }
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static void log(String msg) {
        System.err.println("[mask] " + msg);
    }
}
