package myproject.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.DisposableBean;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class ImageMemoryService implements DisposableBean {
    // 【关键修改】过期时间调整为 60 秒 (60000ms)
    // 理由：防止因算法耗时波动或网络抖动导致数据在回调前被清理
    private static final long EXPIRE_TIME_MS = 60000L;
    // 配置：数据保留时间 (60秒)，清理间隔 (5秒)
    private static final long CLEANUP_INTERVAL_MS = 5000L;

    // 存储原始图片帧: TaskID -> Byte[]
    private final Map<String, byte[]> rawFrameStore = new ConcurrentHashMap<>();
    // 存储处理后的结果图片 (带框): TaskID -> Byte[]
    private final Map<String, byte[]> processedFrameStore = new ConcurrentHashMap<>();
    // 存储结构化检测结果: TaskID -> JSON String
    private final Map<String, String> detectionResultStore = new ConcurrentHashMap<>();

    // 关键：记录最后活跃时间，用于清理
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
//    //展示图片给前端
//    public byte[] getRawFrame(String taskId) {
//        updateTimestamp(taskId);
//        return rawFrameStore.get(taskId);
//    }

    public void saveProcessingResult(String taskId, byte[] annotatedImage, String jsonResult) {
        if (taskId == null) return;
        if (annotatedImage != null) processedFrameStore.put(taskId, annotatedImage);
        if (jsonResult != null) detectionResultStore.put(taskId, jsonResult);
        updateTimestamp(taskId);
        log.debug("✅ 结果已存: {}", taskId);
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
        if (taskId != null)
            timestampStore.put(taskId, System.currentTimeMillis());
    }

    /**
     * 真正的清理逻辑：删除超过 EXPIRE_TIME_MS 未更新的数据
     */
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