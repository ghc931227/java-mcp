package org.acme.command.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommandConfig {

    private String id;
    private String name;
    private String description;
    private String javaCode;
    /** 外部 JAR 依赖路径列表, 编译执行时加入 -classpath 与运行时 URLClassLoader */
    private List<String> jarPaths = new ArrayList<>();
    private Map<String, ParameterDef> parameters;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    public CommandConfig() {
        this.parameters = new HashMap<>();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
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
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public String getJavaCode() {
        return javaCode;
    }
    
    public void setJavaCode(String javaCode) {
        this.javaCode = javaCode;
    }

    public List<String> getJarPaths() {
        return jarPaths;
    }

    public void setJarPaths(List<String> jarPaths) {
        this.jarPaths = jarPaths == null ? new ArrayList<>() : jarPaths;
    }
    
    public Map<String, ParameterDef> getParameters() {
        return parameters;
    }
    
    public void setParameters(Map<String, ParameterDef> parameters) {
        this.parameters = parameters;
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
    
    public static class ParameterDef {
        private String type;
        private String description;
        private boolean required;
        private Object defaultValue;
        
        public String getType() {
            return type;
        }
        
        public void setType(String type) {
            this.type = type;
        }
        
        public String getDescription() {
            return description;
        }
        
        public void setDescription(String description) {
            this.description = description;
        }
        
        public boolean isRequired() {
            return required;
        }
        
        public void setRequired(boolean required) {
            this.required = required;
        }
        
        public Object getDefaultValue() {
            return defaultValue;
        }
        
        public void setDefaultValue(Object defaultValue) {
            this.defaultValue = defaultValue;
        }
    }
}
