package org.acme.command.storage;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.command.model.CommandConfig;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@ApplicationScoped
public class CommandStorage {

    private static final Logger LOG = Logger.getLogger(CommandStorage.class);

    @ConfigProperty(name = "custom.commands.path", defaultValue = "./data/commands.yaml")
    String storagePath;

    private final Map<String, CommandConfig> commands = new ConcurrentHashMap<>();
    /** YAML 序列化器：LocalDateTime 输出为 ISO-8601 字符串，便于直接编辑文件 */
    private final YAMLMapper yamlMapper;
    /** 序列化保存/删除/加载，避免并发写导致文件损坏 */
    private final ReentrantLock fileLock = new ReentrantLock();

    public CommandStorage() {
        this.yamlMapper = new YAMLMapper();
        this.yamlMapper.registerModule(new JavaTimeModule());
        this.yamlMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void initialize() {
        loadFromFile();
    }

    /** 返回所有命令（按创建时间排序，先创建的在前，保证动态命令注册顺序稳定） */
    public List<CommandConfig> listAll() {
        List<CommandConfig> result = new ArrayList<>(commands.values());
        result.sort(Comparator.comparing(CommandConfig::getCreatedAt));
        return result;
    }

    public Optional<CommandConfig> getByName(String name) {
        return Optional.ofNullable(commands.get(name));
    }

    public boolean exists(String name) {
        return commands.containsKey(name);
    }

    public CommandConfig save(CommandConfig config) {
        if (config.getId() == null || config.getId().isEmpty()) {
            config.setId(UUID.randomUUID().toString());
        }
        config.setUpdatedAt(java.time.LocalDateTime.now());

        fileLock.lock();
        try {
            commands.put(config.getName(), config);
            saveToFile();
        } finally {
            fileLock.unlock();
        }

        return config;
    }

    public boolean delete(String name) {
        fileLock.lock();
        try {
            boolean removed = commands.remove(name) != null;
            if (removed) {
                saveToFile();
            }
            return removed;
        } finally {
            fileLock.unlock();
        }
    }

    private void loadFromFile() {
        fileLock.lock();
        try {
            File file = new File(storagePath);
            if (!file.exists()) {
                LOG.info("Command storage file not found, starting with empty storage");
                return;
            }

            try {
                CommandConfig[] configs = yamlMapper.readValue(file, CommandConfig[].class);
                commands.clear();
                for (CommandConfig config : configs) {
                    if (config.getName() != null) {
                        commands.put(config.getName(), config);
                    }
                }
                LOG.infof("Loaded %d commands from storage", configs.length);
            } catch (IOException e) {
                LOG.error("Failed to load commands from file", e);
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
            byte[] content = yamlMapper.writeValueAsBytes(commands.values());
            Files.write(temp, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.debug("Saved commands to file");
        } catch (IOException e) {
            LOG.error("Failed to save commands to file", e);
        }
    }
}
