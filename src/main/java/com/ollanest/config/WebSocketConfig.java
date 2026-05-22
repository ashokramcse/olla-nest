package com.ollanest.config;

import com.ollanest.service.TerminalService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TerminalService terminalService;
    private final WebSocketAuthInterceptor authInterceptor;

    public WebSocketConfig(TerminalService terminalService, WebSocketAuthInterceptor authInterceptor) {
        this.terminalService = terminalService;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalService, "/api/terminal")
                .setAllowedOriginPatterns("*")
                .addInterceptors(authInterceptor);
    }
}
