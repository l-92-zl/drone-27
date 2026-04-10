package myproject.controller;

import com.fasterxml.jackson.databind.ObjectMapper; // 1. 引入 Jackson 用于 JSON 转换
import myproject.service.AlgorithmDispatchService;
import myproject.service.ImageMemoryService;
import myproject.handle.ResultHandler;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
@Slf4j
@RequiredArgsConstructor
public class ApiController {

    private final ImageMemoryService memoryService;
    private final ResultHandler resultHandler;
    private final AlgorithmDispatchService dispatchService;
    // 2. 注入 Spring 提供的 ObjectMapper (单例，性能更好) // 用于推送给前端时序列化
    private final ObjectMapper objectMapper;
    /**
     * 1. 视频帧上传接口
     */
    @PostMapping("/upload")
    public ResponseEntity<String> uploadFrame(
            @RequestParam("file") MultipartFile file,
            @RequestParam("taskId") String taskId) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Empty File");
        }

        try {
            byte[] imageData = file.getBytes();
            memoryService.saveRawFrame(taskId, imageData);
            dispatchService.dispatchToPython(taskId, imageData);
            return ResponseEntity.ok("UPLOADED");
        } catch (Exception e) {
            log.error("Upload failed", e);
            return ResponseEntity.internalServerError().body("ERROR");
        }
    }

    /**
     * 内部静态类：接收 Python 回调的包装对象
     */
    @Data
    public static class AlgorithmCallbackRequest {
        private String taskId;
        private String annotatedImageBase64;
        private DetectionResult result; // 这里直接引用你定义的 DetectionResult 类
    }

    /**
     * 2. 算法结果回调接口
     */
    @PostMapping("/callback")
    public ResponseEntity<String> receiveAlgorithmResult(@RequestBody AlgorithmCallbackRequest request) {

        // 参数校验
        if (request.getTaskId() == null || request.getResult() == null) {
            log.warn("收到无效的回调请求：{}", request);
            return ResponseEntity.badRequest().body("Missing Params or Result");
        }

        String taskId = request.getTaskId();
        DetectionResult resultData = request.getResult();
        String imageBase64 = request.getAnnotatedImageBase64();

        try {
            byte[] imgBytes = null;
            if (imageBase64 != null && !imageBase64.isEmpty()) {
                imgBytes = Base64.getDecoder().decode(imageBase64);
            }

            // 直接传对象，让 Service 内部处理 JSON 转换
            memoryService.saveProcessingResult(taskId, imgBytes, resultData);

            // 推送给前端
            // 既然 resultData 已经在手里了，直接序列化推送，不需要去 Service 里再取一遍
            String resultJson = objectMapper.writeValueAsString(resultData);
            resultHandler.broadcastResult(taskId, resultJson);

            return ResponseEntity.ok("PROCESSED");
        } catch (IllegalArgumentException e) {
            log.error("Base64 decode failed", e);
            return ResponseEntity.badRequest().body("Invalid Image");
        } catch (Exception e) {
            log.error("Processing failed", e);
            return ResponseEntity.internalServerError().body("Error");
        }
    }

    @GetMapping("/download/{taskId}")
    public ResponseEntity<byte[]> downloadProcessedFrame(@PathVariable String taskId) {
        byte[] imageData = memoryService.getProcessedFrame(taskId);
        if (imageData == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(imageData);
    }

    @GetMapping("/health")
    public String health() {
        return "System Running";
    }
}
