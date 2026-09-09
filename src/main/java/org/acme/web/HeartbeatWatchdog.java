package org.acme.web;

import io.quarkus.runtime.Quarkus;
import org.jboss.logging.Logger;

/**
 * Mask 心跳看门狗。
 *
 * <p>后端被 mask（stdio 桥接代理）自动拉起时，mask 会注入环境变量 {@code MASK_HEARTBEAT=1} 并每 1s
 * 调用一次 {@code POST /api/heartbeat} 报告存活。看门狗仅在该变量存在时启用（即 mask 拉起、
 * 无直接 stdio MCP 的场景）：收到第一个包后开始计时，若连续 {@value #TIMEOUT_MS}ms 没有收到任何
 * 包（任何 /api 请求均刷新计时，见 AuthFilter），判定 mask 已退出，后端自动关闭，避免 jar 进程残留。
 *
 * <p>直接作为 stdio MCP 运行、或手动拉起的纯 Web 控制台（未设置 MASK_HEARTBEAT）完全不受影响：
 * 前者靠 stdin EOF 退出，后者保持常驻。
 *
 * <p>计时从首个包开始而非进程启动：后端启动期间 mask 正在轮询探测、HTTP 未就绪时无包属正常，
 * 不能误杀。
 */
public final class HeartbeatWatchdog {

    private static final Logger LOG = Logger.getLogger(HeartbeatWatchdog.class);

    private static final String ENV_KEY = "MASK_HEARTBEAT";
    /** 无包超时：mask 心跳间隔 1s，3s 即连续丢 3 次心跳 */
    private static final long TIMEOUT_MS = 3000;
    private static final long CHECK_INTERVAL_MS = 500;

    /** 最近一次收到包（任何 /api 请求）的时间戳；0 = 尚未收到任何包（未武装） */
    private static volatile long lastPacketMs = 0;

    private HeartbeatWatchdog() {
    }

    /** 任何 /api 请求均视为来自客户端（mask）的包，刷新计时（AuthFilter 中调用，含未授权请求） */
    public static void touch() {
        lastPacketMs = System.currentTimeMillis();
    }

    /** MASK_HEARTBEAT 存在时启动看门狗守护线程（StdioMcpServer 启动完成后调用） */
    public static void startIfEnabled() {
        String v = System.getenv(ENV_KEY);
        if (v == null || v.isBlank()) {
            return;
        }
        Thread t = new Thread(HeartbeatWatchdog::watch, "mask-heartbeat-watchdog");
        t.setDaemon(true);
        t.start();
        LOG.infof("Mask heartbeat watchdog enabled: backend exits after %dms without packets", TIMEOUT_MS);
    }

    private static void watch() {
        while (true) {
            try {
                Thread.sleep(CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long last = lastPacketMs;
            // 首包之后才计时，避免启动期误杀
            if (last > 0 && System.currentTimeMillis() - last > TIMEOUT_MS) {
                LOG.infof("No packet received for %dms, mask is gone. Shutting down backend.", TIMEOUT_MS);
                Quarkus.asyncExit();
                return;
            }
        }
    }
}
