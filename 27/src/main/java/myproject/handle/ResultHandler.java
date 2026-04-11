package myproject.handle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import myproject.controller.DetectionResult;
import myproject.service.ImageMemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class ResultHandler extends TextWebSocketHandler {

    @Autowired
    private ImageMemoryService memoryService;

    // 使用 Jackson 自动把对象转成 JSON
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Random random = new Random();

    // 会话池：TaskID -> Session 列表
    private static final Map<String, List<WebSocketSession>> SESSION_POOL = new ConcurrentHashMap<>();

    // 模拟任务池：TaskID -> 定时任务 (为了在断开时能取消任务)
    private static final Map<String, ScheduledFuture<?>> MOCK_TASK_POOL = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String taskId = extractTaskId(session.getUri());
        if (taskId != null) {
            SESSION_POOL.computeIfAbsent(taskId, k -> new ArrayList<>()).add(session);
            log.info("🟢 前端连接成功: TaskID = {}, 当前在线数: {}", taskId, SESSION_POOL.get(taskId).size());

            // --- 🚀 核心修改：连接成功后，启动一个模拟数据生成器 ---
            startMockDataGenerator(taskId);
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
                memoryService.removeTask(taskId);

                // 取消该任务的模拟定时器
                ScheduledFuture<?> future = MOCK_TASK_POOL.remove(taskId);
                if (future != null) {
                    future.cancel(true);
                }
                log.info("🔴 任务无人观看，已停止模拟并清理内存: {}", taskId);
            } else {
                log.info("🟡 前端断开: TaskID = {}, 剩余在线数: {}", taskId, sessions.size());
            }
        }
    }

    /**
     * 模拟算法生成数据的逻辑
     */
    private void startMockDataGenerator(String taskId) {
        // 防止重复启动
        if (MOCK_TASK_POOL.containsKey(taskId)) {
            return;
        }

        // 创建一个单线程调度器，模拟每 50ms 产生一帧数据 (约 20 FPS)
        ScheduledFuture<?> future = Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                // 1. 构造假的检测结果对象
                DetectionResult mockResult = generateFakeResult(taskId);

                // 2. 转为 JSON 字符串
                String json = objectMapper.writeValueAsString(mockResult);

                // 3. 存入内存 (可选，方便后续查询)
                memoryService.saveProcessingResult(taskId, null, mockResult);

                // 4. 推送给前端
                broadcastResult(taskId, json);

            } catch (Exception e) {
                log.error("模拟数据生成失败", e);
            }
        }, 0, 50, TimeUnit.MILLISECONDS); // 0延迟启动，每50ms执行一次

        MOCK_TASK_POOL.put(taskId, future);
        log.info("⚡️ 已启动模拟数据生成器: {}", taskId);
    }

    /**
     * 随机生成检测框数据
     */
    private DetectionResult generateFakeResult(String taskId) {
        DetectionResult result = new DetectionResult();
        result.setFrameId(random.nextInt(10000));
        result.setTimestamp(System.currentTimeMillis());
        result.setWidth(1280); // 假设视频宽
        result.setHeight(720); // 假设视频高

        List<DetectionResult.TargetObject> objects = new ArrayList<>();

        // 随机生成 1-3 个目标
        int objectCount = random.nextInt(3) + 1;
        for (int i = 0; i < objectCount; i++) {
            DetectionResult.TargetObject obj = new DetectionResult.TargetObject();
            obj.setLabel("person"); // 固定为人
            obj.setConfidence(0.8 + random.nextDouble() * 0.19); // 0.8 - 0.99

            // 随机坐标 (在 1280x720 范围内跳动)
            int x = random.nextInt(1000);
            int y = random.nextInt(500);
            int w = 100 + random.nextInt(100);
            int h = 200 + random.nextInt(100);

            obj.setBbox(new int[]{x, y, w, h});
            objects.add(obj);
        }

        result.setObjects(objects);
        return result;
    }

    /**
     * 对外暴露方法：供 Controller 调用 (保留原逻辑)
     */
    public void broadcastResult(String taskId, String jsonMessage) {
        // ★★★ 新增逻辑：如果收到真实数据，说明算法开始工作了，停止 Mock 定时器 ★★★
        if (MOCK_TASK_POOL.containsKey(taskId)) {
            ScheduledFuture<?> future = MOCK_TASK_POOL.remove(taskId);
            if (future != null) {
                future.cancel(true);
                log.info("✅ 收到真实数据，已自动关闭 Mock 生成器: {}", taskId);
            }
        }

        List<WebSocketSession> sessions = SESSION_POOL.get(taskId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        TextMessage message = new TextMessage(jsonMessage);
        // 使用迭代器避免并发修改异常
        sessions.removeIf(session -> {
            if (session.isOpen()) {
                try {
                    session.sendMessage(message);
                    return false; // 保留
                } catch (IOException e) {
                    log.error("❌ 推送失败", e);
                    return true; // 移除
                }
            }
            return true; // 移除关闭的会话
        });
    }

    private String extractTaskId(URI uri) {
        if (uri == null) return null;
        String path = uri.getPath();
        if (path == null || path.isEmpty()) return null;
        String[] parts = path.split("/");
        return parts.length >= 3 ? parts[parts.length - 1] : null;
    }
}
