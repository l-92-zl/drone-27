package myproject.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import myproject.controller.DetectionResult;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class ImageMemoryService implements DisposableBean {

    // 引入 ObjectMapper 用于自动转换对象为 JSON
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final long EXPIRE_TIME_MS = 60000L; // 60秒过期
    private static final long CLEANUP_INTERVAL_MS = 5000L; // 5秒清理一次

    // 存储原始图片帧
    private final Map<String, byte[]> rawFrameStore = new ConcurrentHashMap<>();
    // 存储处理后的结果图片
    private final Map<String, byte[]> processedFrameStore = new ConcurrentHashMap<>();
    // 存储结构化检测结果 (存 JSON 字符串)
    private final Map<String, String> detectionResultStore = new ConcurrentHashMap<>();
    // 记录最后活跃时间
    private final Map<String, Long> timestampStore = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Memory-Cleaner");
        t.setDaemon(true);
        return t;
    });

    public ImageMemoryService() {
        log.info("🚀 内存服务启动：过期时间={}ms", EXPIRE_TIME_MS);
        cleaner.scheduleAtFixedRate(this::cleanupExpiredData, 5, CLEANUP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void saveRawFrame(String taskId, byte[] imageData) {
        if (taskId == null || imageData == null) return;
        rawFrameStore.put(taskId, imageData);
        updateTimestamp(taskId);
    }

    /**
     * 【关键修改】
     * 接收 DetectionResult 对象，而不是 String。
     * Service 内部负责将其转换为 JSON 存储。
     */
    public void saveProcessingResult(String taskId, byte[] annotatedImage, DetectionResult resultData) {
        if (taskId == null) return;

        // 1. 保存图片
        if (annotatedImage != null) {
            processedFrameStore.put(taskId, annotatedImage);
        }

        // 2. 保存结果 (自动转为 JSON)
        if (resultData != null) {
            try {
                String jsonResult = objectMapper.writeValueAsString(resultData);
                detectionResultStore.put(taskId, jsonResult);
                log.debug("✅ 结果已序列化存储: {}", taskId);
            } catch (JsonProcessingException e) {
                log.error("❌ JSON 序列化失败: {}", taskId, e);
            }
        }
        updateTimestamp(taskId);
    }

    public String getJsonResult(String taskId) {
        updateTimestamp(taskId);
        return detectionResultStore.get(taskId);
    }

    public byte[] getProcessedFrame(String taskId) {
        updateTimestamp(taskId);
        return processedFrameStore.get(taskId);
    }

    private void updateTimestamp(String taskId) {
        if (taskId != null) timestampStore.put(taskId, System.currentTimeMillis());
    }

    private void cleanupExpiredData() {
        long now = System.currentTimeMillis();
        List<String> expiredIds = new ArrayList<>();

        for (Map.Entry<String, Long> entry : timestampStore.entrySet()) {
            if (now - entry.getValue() > EXPIRE_TIME_MS) {
                expiredIds.add(entry.getKey());
            }
        }

        if (!expiredIds.isEmpty()) {
            for (String id : expiredIds) {
                removeTask(id);
            }
            log.debug("🧹 清理了 {} 个过期任务", expiredIds.size());
        }
    }

    public void removeTask(String taskId) {
        if (taskId == null) return;
        rawFrameStore.remove(taskId);
        processedFrameStore.remove(taskId);
        detectionResultStore.remove(taskId);
        timestampStore.remove(taskId);
    }

    @Override
    public void destroy() {
        cleaner.shutdown();
        try { if (!cleaner.awaitTermination(5, TimeUnit.SECONDS)) cleaner.shutdownNow(); }
        catch (InterruptedException e) { cleaner.shutdownNow(); }
    }
}
