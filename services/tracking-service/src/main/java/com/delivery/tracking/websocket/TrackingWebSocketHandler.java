package com.delivery.tracking.websocket;

import com.delivery.tracking.model.WebSocketMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class TrackingWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TrackingWebSocketHandler.class);

    private final ObjectMapper objectMapper;

    // All active connected WebSocket sessions
    private final Set<WebSocketSession> activeSessions = new CopyOnWriteArraySet<>();

    // Map of Courier ID -> Set of subscribed sessions
    private final Map<UUID, Set<WebSocketSession>> courierSubscriptions = new ConcurrentHashMap<>();

    // Map of Session ID -> Set of Couriers subscribed to
    private final Map<String, Set<UUID>> sessionSubscriptions = new ConcurrentHashMap<>();

    public TrackingWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        activeSessions.add(session);
        log.info("WebSocket connection established: sessionId={}", session.getId());

        // Check if session URI contains courierId parameter or path
        UUID courierIdFromUri = extractCourierId(session.getUri());
        if (courierIdFromUri != null) {
            subscribe(session, courierIdFromUri);
            log.info("Auto-subscribed session {} to courier {}", session.getId(), courierIdFromUri);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            String action = root.path("action").asText("");

            if ("SUBSCRIBE".equalsIgnoreCase(action)) {
                String courierIdStr = root.path("courierId").asText(null);
                if (courierIdStr != null && !courierIdStr.isBlank()) {
                    try {
                        UUID courierId = UUID.fromString(courierIdStr);
                        subscribe(session, courierId);
                        sendMessage(session, new WebSocketMessage("SUBSCRIBED", courierId, null, null, null, null, null, "Subscription active"));
                    } catch (IllegalArgumentException e) {
                        sendMessage(session, new WebSocketMessage("ERROR", null, null, null, null, null, null, "Invalid courierId format"));
                    }
                }
            } else if ("UNSUBSCRIBE".equalsIgnoreCase(action)) {
                String courierIdStr = root.path("courierId").asText(null);
                if (courierIdStr != null && !courierIdStr.isBlank()) {
                    try {
                        UUID courierId = UUID.fromString(courierIdStr);
                        unsubscribe(session, courierId);
                        sendMessage(session, new WebSocketMessage("UNSUBSCRIBED", courierId, null, null, null, null, null, "Subscription removed"));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            } else if ("PING".equalsIgnoreCase(action)) {
                sendMessage(session, new WebSocketMessage("PONG", null, null, null, null, null, null, "pong"));
            }
        } catch (Exception e) {
            log.warn("Failed to process WebSocket message from session {}: {}", session.getId(), e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        activeSessions.remove(session);
        Set<UUID> subs = sessionSubscriptions.remove(session.getId());
        if (subs != null) {
            for (UUID courierId : subs) {
                Set<WebSocketSession> sessions = courierSubscriptions.get(courierId);
                if (sessions != null) {
                    sessions.remove(session);
                    if (sessions.isEmpty()) {
                        courierSubscriptions.remove(courierId);
                    }
                }
            }
        }
        log.info("WebSocket connection closed: sessionId={}, status={}", session.getId(), status);
    }

    public void broadcastTelemetry(WebSocketMessage message) {
        if (message == null || message.courierId() == null) {
            return;
        }

        byte[] payloadBytes;
        try {
            payloadBytes = objectMapper.writeValueAsBytes(message);
        } catch (Exception e) {
            log.error("Failed to serialize WebSocket telemetry message: {}", e.getMessage());
            return;
        }

        TextMessage textMessage = new TextMessage(payloadBytes);

        // Send to specific subscribers for this courier
        Set<WebSocketSession> subscribers = courierSubscriptions.get(message.courierId());
        if (subscribers != null && !subscribers.isEmpty()) {
            for (WebSocketSession session : subscribers) {
                sendSafely(session, textMessage);
            }
        }

        // Also broadcast to sessions listening to all telemetry (sessions with 0 specific subscriptions)
        for (WebSocketSession session : activeSessions) {
            Set<UUID> subs = sessionSubscriptions.get(session.getId());
            if (subs == null || subs.isEmpty()) {
                sendSafely(session, textMessage);
            }
        }
    }

    private void subscribe(WebSocketSession session, UUID courierId) {
        courierSubscriptions.computeIfAbsent(courierId, k -> new CopyOnWriteArraySet<>()).add(session);
        sessionSubscriptions.computeIfAbsent(session.getId(), k -> new CopyOnWriteArraySet<>()).add(courierId);
    }

    private void unsubscribe(WebSocketSession session, UUID courierId) {
        Set<WebSocketSession> sessions = courierSubscriptions.get(courierId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                courierSubscriptions.remove(courierId);
            }
        }
        Set<UUID> subs = sessionSubscriptions.get(session.getId());
        if (subs != null) {
            subs.remove(courierId);
        }
    }

    private void sendSafely(WebSocketSession session, TextMessage message) {
        if (session != null && session.isOpen()) {
            try {
                synchronized (session) {
                    session.sendMessage(message);
                }
            } catch (IOException e) {
                log.warn("Failed to dispatch WebSocket message to session {}: {}", session.getId(), e.getMessage());
            }
        }
    }

    private void sendMessage(WebSocketSession session, WebSocketMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            sendSafely(session, new TextMessage(json));
        } catch (Exception e) {
            log.error("Failed to encode direct WebSocket message: {}", e.getMessage());
        }
    }

    private UUID extractCourierId(URI uri) {
        if (uri == null) {
            return null;
        }
        // Check query string: ?courierId=...
        String query = uri.getQuery();
        if (query != null && query.contains("courierId=")) {
            for (String param : query.split("&")) {
                if (param.startsWith("courierId=")) {
                    try {
                        return UUID.fromString(param.substring("courierId=".length()));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        // Check path: /ws/tracking/{courierId}
        String path = uri.getPath();
        if (path != null) {
            String[] parts = path.split("/");
            for (String part : parts) {
                if (part.length() == 36 && part.contains("-")) {
                    try {
                        return UUID.fromString(part);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return null;
    }

    public int getActiveSessionCount() {
        return activeSessions.size();
    }
}
