package org.acme.jdbc.storage;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.jdbc.model.ConnectionConfig;
import org.acme.jdbc.security.EncryptionService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@ApplicationScoped
public class ConnectionStorage {

    private static final Logger LOG = Logger.getLogger(ConnectionStorage.class);
    
    @ConfigProperty(name = "jdbc.storage.path", defaultValue = "./data/connections.yaml")
    String storagePath;
    
    @Inject
    EncryptionService encryptionService;
    
    private final Map<String, ConnectionConfig> connections = new ConcurrentHashMap<>();
    /** YAML 序列化器：LocalDateTime 输出为 ISO-8601 字符串，便于直接编辑文件 */
    private final YAMLMapper yamlMapper;
    /** 序列化保存/删除/加载，避免并发写导致文件损坏 */
    private final ReentrantLock fileLock = new ReentrantLock();
    
    public ConnectionStorage() {
        this.yamlMapper = new YAMLMapper();
        this.yamlMapper.registerModule(new JavaTimeModule());
        this.yamlMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    
    public void initialize() {
        loadFromFile();
    }
    
    public List<ConnectionConfig> listAll() {
        return new ArrayList<>(connections.values());
    }
    
    public Optional<ConnectionConfig> getById(String id) {
        return Optional.ofNullable(connections.get(id));
    }
    
    public ConnectionConfig save(ConnectionConfig config) {
        if (config.getId() == null || config.getId().isEmpty()) {
            config.setId(UUID.randomUUID().toString());
        }
        config.setUpdatedAt(java.time.LocalDateTime.now());
        
        String encryptedPassword = encryptionService.encrypt(config.getPassword());
        config.setPassword(encryptedPassword);
        
        fileLock.lock();
        try {
            connections.put(config.getId(), config);
            saveToFile();
        } finally {
            fileLock.unlock();
        }
        
        return config;
    }
    
    public boolean delete(String id) {
        fileLock.lock();
        try {
            boolean removed = connections.remove(id) != null;
            if (removed) {
                saveToFile();
            }
            return removed;
        } finally {
            fileLock.unlock();
        }
    }
    
    public ConnectionConfig getDecryptedConfig(String id) {
        ConnectionConfig config = connections.get(id);
        if (config == null) {
            return null;
        }
        
        ConnectionConfig decrypted = cloneConfig(config);
        decrypted.setPassword(encryptionService.decrypt(config.getPassword()));
        return decrypted;
    }
    
    private void loadFromFile() {
        fileLock.lock();
        try {
            File file = new File(storagePath);
            if (!file.exists()) {
                LOG.info("Storage file not found, starting with empty storage");
                return;
            }
            
            try {
                ConnectionConfig[] configs = yamlMapper.readValue(file, ConnectionConfig[].class);
                connections.clear();
                for (ConnectionConfig config : configs) {
                    if (config.getId() != null) {
                        connections.put(config.getId(), config);
                    }
                }
                LOG.infof("Loaded %d connections from storage", configs.length);
            } catch (IOException e) {
                LOG.error("Failed to load connections from file", e);
            }
        } finally {
            fileLock.unlock();
        }
    }
    
    private void saveToFile() {
        try {
            Path target = Paths.get(storagePath);
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            
            // 先写入临时文件，再原子替换，避免进程崩溃导致存储文件损坏
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            byte[] content = yamlMapper.writeValueAsBytes(connections.values());
            Files.write(temp, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.debug("Saved connections to file");
        } catch (IOException e) {
            LOG.error("Failed to save connections to file", e);
        }
    }
    
    private ConnectionConfig cloneConfig(ConnectionConfig source) {
        try {
            String yaml = yamlMapper.writeValueAsString(source);
            return yamlMapper.readValue(yaml, ConnectionConfig.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to clone config", e);
        }
    }
}
