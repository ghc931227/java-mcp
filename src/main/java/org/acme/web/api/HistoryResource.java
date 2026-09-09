package org.acme.web.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.acme.web.ToolCallRecorder;

import java.util.List;
import java.util.Map;

/**
 * 调用历史：读取 ToolCallRecorder 记录的内存环形缓冲。
 */
@Path("/api/history")
@Produces(MediaType.APPLICATION_JSON)
public class HistoryResource {

    @Inject
    ToolCallRecorder recorder;

    @GET
    public List<Map<String, Object>> list() {
        return recorder.list();
    }

    @DELETE
    public Map<String, Object> clear() {
        recorder.clear();
        return Map.of("cleared", true);
    }
}