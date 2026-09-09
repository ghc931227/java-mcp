package org.acme.jdbc.pool;

import com.alibaba.druid.pool.DruidDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.jdbc.loader.JdbcDriverLoader;
import org.acme.jdbc.model.ConnectionConfig;
import org.acme.jdbc.model.DatabaseType;
import org.acme.jdbc.storage.ConnectionStorage;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.sql.SQLException;

@ApplicationScoped
public class ConnectionPoolManager {

    private static final Logger LOG = Logger.getLogger(ConnectionPoolManager.class);

    /** 池最大连接数，可通过环境变量 JDBC_POOL_MAX_ACTIVE 覆盖 */
    @ConfigProperty(name = "jdbc.pool.max-active", defaultValue = "10")
    int maxActive;

    @ConfigProperty(name = "jdbc.pool.max-wait", defaultValue = "30000")
    long maxWaitMillis;

    @ConfigProperty(name = "jdbc.pool.initial-size", defaultValue = "1")
    int initialSize;

    @ConfigProperty(name = "jdbc.pool.min-idle", defaultValue = "1")
    int minIdle;

    @ConfigProperty(name = "jdbc.pool.login-timeout", defaultValue = "5")
    int loginTimeoutSeconds;

    @ConfigProperty(name = "jdbc.pool.connect-timeout", defaultValue = "5000")
    int connectTimeoutMillis;

    @ConfigProperty(name = "jdbc.pool.socket-timeout", defaultValue = "1800000")
    int socketTimeoutMillis;

    @Inject
    JdbcDriverLoader driverLoader;

    @Inject
    ConnectionStorage connectionStorage;

    private final Map<String, DruidDataSource> dataSources = new ConcurrentHashMap<>();

    public Connection getConnection(String connectionId) throws Exception {
        DruidDataSource dataSource = dataSources.get(connectionId);

        if (dataSource == null || dataSource.isClosed()) {
            dataSource = createDataSource(connectionId);
            // concurrent-safe putIfAbsent
            DruidDataSource existing = dataSources.putIfAbsent(connectionId, dataSource);
            if (existing != null && !existing.isClosed()) {
                dataSource.close();
                dataSource = existing;
            }
        }

        return dataSource.getConnection();
    }

    private DruidDataSource createDataSource(String connectionId) throws Exception {
        ConnectionConfig config = connectionStorage.getDecryptedConfig(connectionId);
        if (config == null) {
            throw new IllegalArgumentException("Connection config not found: " + connectionId);
        }

        if (config.getDriverJarPath() != null && !config.getDriverJarPath().isEmpty()) {
            driverLoader.loadDriver(
                    config.getDriverJarPath(),
                    config.getDriverClassName()
            );
        }

        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setUrl(config.getJdbcUrl());
        dataSource.setUsername(config.getUsername());
        dataSource.setPassword(config.getPassword());
        dataSource.setDriverClassName(config.getDriverClassName());

        if (config.getDriverJarPath() != null && !config.getDriverJarPath().isEmpty()) {
            ClassLoader driverClassLoader = driverLoader.getDriverClassLoader(config.getDriverJarPath());
            if (driverClassLoader != null) {
                dataSource.setDriverClassLoader(driverClassLoader);
            }
        }

        dataSource.setInitialSize(initialSize);
        dataSource.setMinIdle(minIdle);
        dataSource.setMaxActive(maxActive);
        dataSource.setMaxWait(maxWaitMillis);
        dataSource.setLoginTimeout(loginTimeoutSeconds);
        dataSource.setConnectTimeout(connectTimeoutMillis);
        dataSource.setSocketTimeout(socketTimeoutMillis);
        dataSource.setBreakAfterAcquireFailure(Boolean.TRUE);
        dataSource.setConnectionErrorRetryAttempts(0);
        dataSource.setTestWhileIdle(false);
        dataSource.setTestOnBorrow(false);
        dataSource.setTestOnReturn(false);
        dataSource.setFilters("stat");

        if (config.getProperties() != null) {
            config.getProperties().forEach(dataSource::addConnectionProperty);
        }

        LOG.infof("Creating connection pool for: %s", connectionId);

        try {
            dataSource.init();
        } catch (SQLException e) {
            throw new Exception("Failed to initialize Druid DataSource: " + e.getMessage() + " | SQLState: " + e.getSQLState() + " | code: " + e.getErrorCode(), e);
        }

        return dataSource;
    }

    public void closeConnection(String connectionId) {
        DruidDataSource dataSource = dataSources.remove(connectionId);
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            LOG.infof("Closed connection pool: %s", connectionId);
        }
    }

    public void closeAllConnections() {
        dataSources.forEach((id, ds) -> {
            if (!ds.isClosed()) {
                ds.close();
            }
        });
        dataSources.clear();
        LOG.info("Closed all connection pools");
    }

    public boolean testConnection(String connectionId) {
        try (Connection conn = getConnection(connectionId)) {
            return conn.isValid(5);
        } catch (Exception e) {
            LOG.errorf(e, "Connection test failed for: %s", connectionId);
            return false;
        }
    }

    /** 连接池实时状态快照（仅统计已初始化的池），供 Web 控制台监控 */
    public Map<String, Object> poolStatus(String connectionId) {
        DruidDataSource ds = dataSources.get(connectionId);
        if (ds == null || ds.isClosed()) {
            return Map.of("initialized", false);
        }
        Map<String, Object> m = new HashMap<>();
        m.put("initialized", true);
        m.put("activeCount", ds.getActiveCount());
        // Druid 空闲连接以 poolingCount 表示
        m.put("idleCount", ds.getPoolingCount());
        m.put("poolingCount", ds.getPoolingCount());
        m.put("maxActive", ds.getMaxActive());
        m.put("minIdle", ds.getMinIdle());
        m.put("connectCount", ds.getConnectCount());
        m.put("closeCount", ds.getCloseCount());
        m.put("waitThreadCount", ds.getWaitThreadCount());
        return m;
    }

    /** 所有已初始化连接池的状态，key 为连接 ID */
    public Map<String, Object> allPoolStatus() {
        Map<String, Object> result = new HashMap<>();
        dataSources.forEach((id, ds) -> result.put(id, poolStatus(id)));
        return result;
    }
}