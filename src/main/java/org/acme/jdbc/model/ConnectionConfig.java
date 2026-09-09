package org.acme.jdbc.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class ConnectionConfig {
    
    private String id;
    private String name;
    private DatabaseType databaseType;
    private String host;
    private int port;
    private String database;
    private String username;
    private String password;
    private String driverJarPath;
    private String customDriverClass;
    private String customJdbcUrl;
    private Map<String, String> properties;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    public ConnectionConfig() {
        this.properties = new HashMap<>();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
    
    @JsonIgnore
    public String getJdbcUrl() {
        if (customJdbcUrl != null && !customJdbcUrl.isEmpty()) {
            return customJdbcUrl;
        }
        return databaseType.buildUrl(host, port, database);
    }
    
    @JsonIgnore
    public String getDriverClassName() {
        if (customDriverClass != null && !customDriverClass.isEmpty()) {
            return customDriverClass;
        }
        return databaseType.getDriverClassName();
    }
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public DatabaseType getDatabaseType() {
        return databaseType;
    }
    
    public void setDatabaseType(DatabaseType databaseType) {
        this.databaseType = databaseType;
    }
    
    public String getHost() {
        return host;
    }
    
    public void setHost(String host) {
        this.host = host;
    }
    
    public int getPort() {
        return port;
    }
    
    public void setPort(int port) {
        this.port = port;
    }
    
    public String getDatabase() {
        return database;
    }
    
    public void setDatabase(String database) {
        this.database = database;
    }
    
    public String getUsername() {
        return username;
    }
    
    public void setUsername(String username) {
        this.username = username;
    }
    
    public String getPassword() {
        return password;
    }
    
    public void setPassword(String password) {
        this.password = password;
    }
    
    public String getDriverJarPath() {
        return driverJarPath;
    }
    
    public void setDriverJarPath(String driverJarPath) {
        this.driverJarPath = driverJarPath;
    }
    
    public String getCustomDriverClass() {
        return customDriverClass;
    }
    
    public void setCustomDriverClass(String customDriverClass) {
        this.customDriverClass = customDriverClass;
    }
    
    public String getCustomJdbcUrl() {
        return customJdbcUrl;
    }
    
    public void setCustomJdbcUrl(String customJdbcUrl) {
        this.customJdbcUrl = customJdbcUrl;
    }
    
    public Map<String, String> getProperties() {
        return properties;
    }
    
    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
