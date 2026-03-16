package myproject.controller;

import myproject.handle.ResultHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // 允许跨域

public class ApiController {

    /**
     * 算法团队调用此接口
     * URL: POST http://localhost:8080/api/push?taskId=xxx
     * Body: 任意 JSON (例如 {"frame":1, "boxes":[...]})
     */
    @PostMapping("/push")
    public ResponseEntity<String> receiveResult(
            @RequestParam String taskId,
            @RequestBody String jsonBody) {

        // 1. 打印日志
        // System.out.println("收到任务 [" + taskId + "] 的数据");

        // 2. 直接传给前端
        ResultHandler.broadcast(taskId, jsonBody);

        return ResponseEntity.ok("OK");
    }
//    简单的健康检查接口
//    @GetMapping("/health")
//    public String health() {
//        return "System Running";
//    }
}