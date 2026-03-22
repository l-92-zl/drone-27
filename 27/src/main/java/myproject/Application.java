package myproject;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.socket.config.annotation.EnableWebSocket;

@SpringBootApplication
@EnableWebSocket // 开启 WebSocket 支持
@EnableAsync

public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);

        System.out.println("🚀 后端已启动！端口: 8080");

    }
}
