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

    public WebSocketConfig(TerminalService terminalService) {
        this.terminalService = terminalService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalService, "/api/terminal")
                .setAllowedOrigins("*");
    }
}
