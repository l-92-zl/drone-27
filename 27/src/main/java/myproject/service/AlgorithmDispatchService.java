package myproject.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;

@Service
@Slf4j
@RequiredArgsConstructor
public class AlgorithmDispatchService {

    private final RestTemplate restTemplate;
    private static final String PYTHON_URL = "http://localhost:8000/process";

    @Async
    public void dispatchToPython(String taskId, byte[] imageData) {
        try {
            // 【关键修改】构建 multipart/form-data 请求，直接传二进制
            LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("taskId", taskId);

            // 将 byte[] 包装成 Resource，RestTemplate 会自动识别为文件流
            body.add("file", new ByteArrayResource(imageData) {
                @Override
                public String getFilename() {
                    return taskId + ".jpg"; // 给个临时文件名
                }
            });

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            // 发送请求 (Fire-and-Forget)
            // 注意：Python 端需要修改为接收 form-data 中的 'file' 字段，而不是 JSON
            restTemplate.postForObject(PYTHON_URL, requestEntity, String.class);

            log.debug("🚀 [二进制模式] 已分发任务: {}", taskId);

        } catch (Exception e) {
            log.error("❌ 调用 Python 服务失败 (Task: {}): {}", taskId, e.getMessage());
            // 生产环境可在此记录错误日志到数据库
        }
    }
}