package org.acme.web.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.acme.command.model.CommandConfig;
import org.acme.command.storage.CommandStorage;
import org.acme.jdbc.model.ConnectionConfig;
import org.acme.jdbc.pool.ConnectionPoolManager;
import org.acme.jdbc.storage.ConnectionStorage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元信息：数据库连接、自定义命令列表（供仪表盘/下拉选择使用）。
 */
@Path("/api/meta")
@Produces(MediaType.APPLICATION_JSON)
public class MetaResource {

    @Inject
    ConnectionStorage connectionStorage;

    @Inject
    CommandStorage commandStorage;

    @Inject
    ConnectionPoolManager poolManager;

    @GET
    @Path("/connections")
    public List<Map<String, Object>> connections() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ConnectionConfig c : connectionStorage.listAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("databaseType", c.getDatabaseType());
            m.put("host", c.getHost());
            m.put("port", c.getPort());
            m.put("database", c.getDatabase());
            result.add(m);
        }
        return result;
    }

    @GET
    @Path("/commands")
    public List<Map<String, Object>> commands() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CommandConfig c : commandStorage.listAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.getName());
            m.put("description", c.getDescription());
            m.put("jarPaths", c.getJarPaths());
            result.add(m);
        }
        return result;
    }

    @GET
    @Path("/pool")
    public Map<String, Object> poolStatus() {
        return poolManager.allPoolStatus();
    }

    @GET
    @Path("/pool/{id}")
    public Map<String, Object> poolStatusOf(@jakarta.ws.rs.PathParam("id") String id) {
        return poolManager.poolStatus(id);
    }
}