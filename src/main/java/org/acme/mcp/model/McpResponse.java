package org.acme.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpResponse {
    
    @JsonProperty("jsonrpc")
    private String jsonrpc = "2.0";
    
    private Object result;
    
    private McpError error;
    
    private Object id;
    
    public static McpResponse success(Object id, Object result) {
        McpResponse response = new McpResponse();
        response.setId(id);
        response.setResult(result);
        return response;
    }
    
    public static McpResponse error(Object id, int code, String message) {
        McpResponse response = new McpResponse();
        response.setId(id);
        response.setError(new McpError(code, message));
        return response;
    }
    
    public String getJsonrpc() {
        return jsonrpc;
    }
    
    public void setJsonrpc(String jsonrpc) {
        this.jsonrpc = jsonrpc;
    }
    
    public Object getResult() {
        return result;
    }
    
    public void setResult(Object result) {
        this.result = result;
    }
    
    public McpError getError() {
        return error;
    }
    
    public void setError(McpError error) {
        this.error = error;
    }
    
    public Object getId() {
        return id;
    }
    
    public void setId(Object id) {
        this.id = id;
    }
    
    public static class McpError {
        private int code;
        private String message;
        
        public McpError() {}
        
        public McpError(int code, String message) {
            this.code = code;
            this.message = message;
        }
        
        public int getCode() {
            return code;
        }
        
        public void setCode(int code) {
            this.code = code;
        }
        
        public String getMessage() {
            return message;
        }
        
        public void setMessage(String message) {
            this.message = message;
        }
    }
}
