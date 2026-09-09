package org.acme.jdbc.loader;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class JdbcDriverLoader {

    private static final Logger LOG = Logger.getLogger(JdbcDriverLoader.class);

    private final Map<String, URLClassLoader> driverClassLoaders = new ConcurrentHashMap<>();
    private final Map<String, Driver> loadedDrivers = new ConcurrentHashMap<>();

    public Driver loadDriver(String jarPath, String driverClassName) throws Exception {
        String cacheKey = jarPath + ":" + driverClassName;

        if (loadedDrivers.containsKey(cacheKey)) {
            LOG.infof("Driver already loaded: %s", cacheKey);
            return loadedDrivers.get(cacheKey);
        }

        File jarFile = new File(jarPath);
        if (!jarFile.exists()) {
            throw new IllegalArgumentException("Driver jar file not found: " + jarPath);
        }

        LOG.infof("Loading JDBC driver from: %s, class: %s", jarPath, driverClassName);

        URLClassLoader classLoader = driverClassLoaders.get(jarPath);
        if (classLoader == null) {
            URL[] urls = new URL[]{jarFile.toURI().toURL()};
            classLoader = new URLClassLoader(urls, Thread.currentThread().getContextClassLoader());
            driverClassLoaders.put(jarPath, classLoader);
        }

        Class<?> driverClass = Class.forName(driverClassName, true, classLoader);
        Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();

        // 注册到 DriverManager，保证基于 DriverManager.getDriver(url) 的调用路径也能工作
        try {
            DriverManager.registerDriver(driver);
        } catch (SQLException e) {
            LOG.warnf("Failed to register driver with DriverManager: %s (%s)",
                    driverClassName, e.getMessage());
        }

        loadedDrivers.put(cacheKey, driver);
        LOG.infof("Successfully loaded driver: %s", cacheKey);

        return driver;
    }

    public void unloadDriver(String jarPath, String driverClassName) throws Exception {
        String cacheKey = jarPath + ":" + driverClassName;

        Driver driver = loadedDrivers.remove(cacheKey);
        if (driver != null) {
            try {
                DriverManager.deregisterDriver(driver);
            } catch (SQLException e) {
                LOG.warnf("Failed to deregister driver: %s (%s)", driverClassName, e.getMessage());
            }
        }

        URLClassLoader classLoader = driverClassLoaders.remove(jarPath);
        if (classLoader != null) {
            classLoader.close();
            LOG.infof("Unloaded driver: %s", cacheKey);
        }
    }

    public boolean isDriverLoaded(String jarPath, String driverClassName) {
        String cacheKey = jarPath + ":" + driverClassName;
        return loadedDrivers.containsKey(cacheKey);
    }

    /**
     * 获取驱动 jar 对应的类加载器，供连接池按该加载器解析驱动类。
     */
    public ClassLoader getDriverClassLoader(String jarPath) {
        return driverClassLoaders.get(jarPath);
    }
}
