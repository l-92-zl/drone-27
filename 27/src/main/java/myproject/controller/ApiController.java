package myproject.controller;

import myproject.service.AlgorithmDispatchService; // 引入新服务
import myproject.service.ImageMemoryService;
import myproject.handle.ResultHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
@Slf4j
@RequiredArgsConstructor // 构造器注入
public class ApiController {

    private final ImageMemoryService memoryService;
    private final ResultHandler resultHandler;
    private final AlgorithmDispatchService dispatchService;

    /**
     * 1. 视频帧上传接口
     * URL: POST /api/upload?taskId=xxx
     * 前端推送原始帧到 Java 内存
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

            // 1. 存入内存 (极快，<1ms)
            memoryService.saveRawFrame(taskId, imageData);

            // 2. 触发异步任务 (立即返回，不阻塞)
            dispatchService.dispatchToPython(taskId, imageData);

            return ResponseEntity.ok("UPLOADED");
        } catch (Exception e) {
            log.error("Upload failed", e);
            return ResponseEntity.internalServerError().body("ERROR");
        }
    }


    /**
     * 2. 算法结果回调接口
     * URL: POST /api/callback
     * 用途：Python 处理完后，将结果推回 Java，Java 再转发给前端
     * Body: { "taskId": "xxx", "resultJson": "{...}", "annotatedImageBase64": "..." }
     */

    @PostMapping("/callback")
    public ResponseEntity<String> receiveAlgorithmResult(@RequestBody Map<String, Object> payload) {
        String taskId = (String) payload.get("taskId");
        String resultJson = (String) payload.get("resultJson");
        String imageBase64 = (String) payload.get("annotatedImageBase64");

        if (taskId == null || resultJson == null) {
            return ResponseEntity.badRequest().body("Missing Params");
        }

        try {
            byte[] imgBytes = null;
            if (imageBase64 != null && !imageBase64.isEmpty()) {
                imgBytes = Base64.getDecoder().decode(imageBase64);
            }

            memoryService.saveProcessingResult(taskId, imgBytes, resultJson);
            resultHandler.broadcastResult(taskId, resultJson);

            return ResponseEntity.ok("PROCESSED");
        } catch (IllegalArgumentException e) {
            log.error("Base64 decode failed", e);
            return ResponseEntity.badRequest().body("Invalid Image");
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
