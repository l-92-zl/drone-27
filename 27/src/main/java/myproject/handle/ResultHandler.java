package myproject.handle;

import lombok.extern.slf4j.Slf4j;
import myproject.service.ImageMemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;

@Component
@Slf4j
public class ResultHandler extends TextWebSocketHandler {

    @Autowired
    private ImageMemoryService memoryService;

    // 会话池：TaskID -> Session 列表 (支持多人同时观看同一无人机画面)
    private static final Map<String, List<WebSocketSession>> SESSION_POOL = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String taskId = extractTaskId(session.getUri());
        if (taskId != null) {
            SESSION_POOL.computeIfAbsent(taskId, k -> new ArrayList<>()).add(session);
            log.info("🟢 前端连接成功: TaskID = {}, 当前在线数: {}", taskId, SESSION_POOL.get(taskId).size());

        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String taskId = extractTaskId(session.getUri());
        if (taskId != null && SESSION_POOL.containsKey(taskId)) {
            List<WebSocketSession> sessions = SESSION_POOL.get(taskId);
            sessions.remove(session);
            if (sessions.isEmpty()) {
                SESSION_POOL.remove(taskId);
                memoryService.removeTask(taskId); // 无人观看时清理内存
                log.info("🔴 任务无人观看，已清理内存: {}", taskId);
            } else {
                log.info("🟡 前端断开: TaskID = {}, 剩余在线数: {}", taskId, sessions.size());
            }
        }
    }

    /**
     * 对外暴露方法：供 Controller 调用
     */
    public void broadcastResult(String taskId, String jsonMessage) {
        List<WebSocketSession> sessions = SESSION_POOL.get(taskId);
        if (sessions == null || sessions.isEmpty()) {
            return; // 无人观看，直接丢弃，不阻塞主线程
        }

        TextMessage message = new TextMessage(jsonMessage);
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(message);
                } catch (IOException e) {
                    log.error("❌ 推送失败: {}", taskId, e);
                    // 可在异步线程中移除失效 session
                }
            }
        }
    }


    private String extractTaskId(URI uri) {
        if (uri == null) return null;
        String path = uri.getPath();
        if (path == null || path.isEmpty()) return null; // 增加空值校验
        String[] parts = path.split("/");
        // 防止数组越界（比如 URL 是 /ws/ 这种不完整格式）
        return parts.length >= 3 ? parts[parts.length - 1] : null;
    }
}
