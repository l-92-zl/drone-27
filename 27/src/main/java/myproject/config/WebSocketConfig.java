package myproject.config;

import myproject.handle.ResultHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket// 开启 WebSocket 功能
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private ResultHandler resultHandler;// 注入自定义的处理器

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 把处理器绑定到 /ws/{taskId}
        // setAllowedOrigins("*") 允许前端接口到 /ws/{taskId}
        registry.addHandler(resultHandler, "/ws/{taskId}")
                .setAllowedOrigins("*");
    }
}




