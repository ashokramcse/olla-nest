package com.ollanest.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket terminal using ProcessBuilder (replaces node-pty).
 * Bridges stdin/stdout between WebSocket messages and a bash process.
 */
@Service
public class TerminalService extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TerminalService.class);
    private final ConcurrentHashMap<String, Process> processes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OutputStream> stdinMap = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase();
        ProcessBuilder pb;
        if (os.contains("win")) {
            pb = new ProcessBuilder("cmd.exe");
        } else {
            pb = new ProcessBuilder("/bin/bash", "-i");
        }
        pb.environment().put("TERM", "xterm-256color");
        pb.redirectErrorStream(true);

        Process process = pb.start();
        processes.put(session.getId(), process);
        stdinMap.put(session.getId(), process.getOutputStream());

        // Reader thread: forward process stdout to WebSocket
        Thread reader = new Thread(() -> {
            try {
                InputStream is = process.getInputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) {
                    if (!session.isOpen()) break;
                    String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                    session.sendMessage(new TextMessage(chunk));
                }
            } catch (Exception e) {
                // Terminal closed
            } finally {
                try { session.close(); } catch (Exception ignored) {}
            }
        });
        reader.setDaemon(true);
        reader.start();

        log.info("[terminal] Session started: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        OutputStream stdin = stdinMap.get(session.getId());
        if (stdin != null) {
            stdin.write(message.getPayload().getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        OutputStream stdin = stdinMap.get(session.getId());
        if (stdin != null) {
            stdin.write(message.getPayload().array());
            stdin.flush();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        String id = session.getId();
        Process process = processes.remove(id);
        if (process != null) {
            process.destroyForcibly();
        }
        stdinMap.remove(id);
        log.info("[terminal] Session closed: {}", id);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("[terminal] Transport error on {}: {}", session.getId(), exception.getMessage());
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
    }
}
