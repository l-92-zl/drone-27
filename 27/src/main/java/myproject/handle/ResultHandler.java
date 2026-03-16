package myproject.handle;


import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ResultHandler extends TextWebSocketHandler {

    // 内存存储：TaskID -> 对应的所有前端连接会话
    private static final Map<String, List<WebSocketSession>> SESSION_POOL = new ConcurrentHashMap<>();

    // 当有前端连接时
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String taskId = extractTaskId(session.getUri());
        if (taskId != null) {
            SESSION_POOL.computeIfAbsent(taskId, k -> new ArrayList<>()).add(session);
            System.out.println("✅ 前端连接成功: TaskID = " + taskId);
        }
    }

    // 当前端断开时,关闭连接
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String taskId = extractTaskId(session.getUri());
        if (taskId != null && SESSION_POOL.containsKey(taskId)) {
            SESSION_POOL.get(taskId).remove(session);

            if (SESSION_POOL.get(taskId).isEmpty()) {
                SESSION_POOL.remove(taskId);
                System.out.println("❌ 任务无人观看，清理: " + taskId);
            }
        }
    }

    /**
     * 供 Controller 调用，广播消息
     * @param taskId 任务ID
     * @param jsonMessage 算法传来的原始 JSON 字符串
     */
    public static void broadcast(String taskId, String jsonMessage) {
        List<WebSocketSession> sessions = SESSION_POOL.get(taskId);

        if (sessions == null || sessions.isEmpty()) {
            return; // 没人看，直接丢弃，不报错
        }

        TextMessage message = new TextMessage(jsonMessage);
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(message);
                } catch (IOException e) {

                }
            }
        }
    }

    // 辅助：从 URL 中提取 taskId
    private String extractTaskId(URI uri) {
        if (uri == null) return null;
        String path = uri.getPath();
        String[] parts = path.split("/");
        return parts.length > 2 ? parts[parts.length - 1] : null;
    }
}