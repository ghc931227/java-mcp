package org.acme.web;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 工具调用历史记录器。
 * 保存最近 N 条调用（内存环形缓冲），供 Web 控制台的"调用历史"页展示。
 * 敏感字段（password、token 等）在写入前脱敏。
 */
@ApplicationScoped
public class ToolCallRecorder {

    private static final int MAX_SIZE = 1000;
    /** 命中即脱敏的参数键 */
    private static final List<String> SENSITIVE_KEYS =
            List.of("password", "secret", "token", "pwd", "credential", "privateKey");

    private final Deque<Map<String, Object>> calls = new ConcurrentLinkedDeque<>();

    public void record(String toolName, String source, Object arguments, long costMs,
                       boolean ok, Object result, String error) {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("time", java.time.LocalTime.now().withNano(0).toString());
        entry.put("tool", toolName);
        entry.put("source", source);
        entry.put("costMs", costMs);
        entry.put("ok", ok);
        entry.put("params", maskArguments(arguments));
        entry.put("result", result == null ? null : String.valueOf(result));
        entry.put("error", error);

        synchronized (calls) {
            calls.addFirst(entry);
            while (calls.size() > MAX_SIZE) {
                calls.removeLast();
            }
        }
    }

    public List<Map<String, Object>> list() {
        synchronized (calls) {
            return new ArrayList<>(calls);
        }
    }

    public void clear() {
        synchronized (calls) {
            calls.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private Object maskArguments(Object args) {
        if (!(args instanceof Map)) {
            return args;
        }
        Map<String, Object> source = (Map<String, Object>) args;
        Map<String, Object> masked = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            if (isSensitive(e.getKey())) {
                masked.put(e.getKey(), "******");
            } else {
                masked.put(e.getKey(), e.getValue());
            }
        }
        return masked;
    }

    private static boolean isSensitive(String key) {
        String lower = key.toLowerCase();
        for (String s : SENSITIVE_KEYS) {
            if (lower.contains(s)) {
                return true;
            }
        }
        return false;
    }
}