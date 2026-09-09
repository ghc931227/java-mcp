package org.acme.jdbc.pool;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

/**
 * SQL 语句超时配置：默认值与上限均可通过环境变量覆盖
 * （JDBC_QUERY_DEFAULT_TIMEOUT / JDBC_QUERY_MAX_TIMEOUT）。
 */
@ApplicationScoped
public class SqlTimeoutConfig {

    @ConfigProperty(name = "jdbc.query.default-timeout", defaultValue = "180")
    int defaultTimeoutSeconds;

    @ConfigProperty(name = "jdbc.query.max-timeout", defaultValue = "1800")
    int maxTimeoutSeconds;

    /**
     * 解析可选的 timeoutSeconds 参数：缺省或 0 使用默认值，
     * 负数抛错，超过上限钳制到上限。均非必填。
     */
    public int resolve(Map<String, Object> params) {
        if (params == null || !params.containsKey("timeoutSeconds") || params.get("timeoutSeconds") == null) {
            return defaultTimeoutSeconds;
        }
        int value = ((Number) params.get("timeoutSeconds")).intValue();
        if (value < 0) {
            throw new IllegalArgumentException("timeoutSeconds cannot be negative");
        }
        if (value == 0) {
            return defaultTimeoutSeconds;
        }
        return Math.min(value, maxTimeoutSeconds);
    }
}
