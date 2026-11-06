package com.hmdp.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.ai.AiException;
import com.hmdp.ai.AiProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CustomerChatModelClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void sendsExactEndpointSchemaAndThinkingAcrossToolCalls() throws Exception {
        List<JsonNode> requests = new ArrayList<>();
        AtomicInteger count = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom", exchange -> {
            assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
            requests.add(mapper.readTree(exchange.getRequestBody()));
            String response = count.getAndIncrement() == 0
                    ? "{\"id\":\"a\",\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":null,\"reasoning_content\":\"internal\",\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"getShop\",\"arguments\":\"{\\\"shopId\\\":\\\"1\\\"}\"}}]}}]}"
                    : "{\"id\":\"b\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        CustomerChatModelClient client = client();
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject().put("role", "system").put("content", "rules");
        messages.addObject().put("role", "user").put("content", "query");
        ArrayNode tools = (ArrayNode) mapper.readTree("[{\"type\":\"function\",\"function\":{\"name\":\"getShop\",\"description\":\"shop\",\"parameters\":{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"shopId\"],\"properties\":{\"shopId\":{\"type\":\"string\",\"pattern\":\"^[1-9]+$\"}}}}}]");
        ObjectNode answer = client.complete(messages, tools);
        assertEquals("internal", answer.path("reasoning_content").asText());
        assertEquals("call-1", answer.path("tool_calls").path(0).path("id").asText());
        messages.add(answer);
        messages.addObject().put("role", "tool").put("tool_call_id", "call-1").put("content", "{} ");
        assertEquals("ok", client.complete(messages, tools).path("content").asText());
        assertEquals(tools, requests.get(0).path("tools"));
        assertEquals(2048, requests.get(0).path("max_tokens").asInt());
        assertEquals("internal", requests.get(1).path("messages").get(2).path("reasoning_content").asText());
        assertEquals("call-1", requests.get(1).path("messages").get(3).path("tool_call_id").asText());
    }

    @Test void rejectsTruncatedAnswerWithoutRetry() throws Exception {
        AtomicInteger count = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom", exchange -> {
            count.incrementAndGet();
            byte[] bytes = "{\"choices\":[{\"index\":0,\"finish_reason\":\"length\",\"message\":{\"role\":\"assistant\",\"content\":\"partial\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject().put("role", "user").put("content", "query");
        assertThrows(AiException.class, () -> client().complete(messages, mapper.createArrayNode()));
        assertEquals(1, count.get());
    }

    @Test void rateLimitIsSafeAndNotRetried() throws Exception {
        AtomicInteger count = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom", exchange -> {
            count.incrementAndGet();
            byte[] bytes = "test-key private response".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject().put("role", "user").put("content", "query");
        AiException error = assertThrows(AiException.class, () -> client().complete(messages, mapper.createArrayNode()));
        assertEquals("模型服务繁忙，请稍后重试", error.getMessage());
        assertEquals(1, count.get());
    }

    @Test void rejectsMixedValidAndInvalidToolCallsBeforeExecutingAny() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom", exchange -> {
            String response = "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"tool_calls\":["
                    + "{\"id\":\"a\",\"type\":\"function\",\"function\":{\"name\":\"listMyOrders\",\"arguments\":\"{}\"}},"
                    + "{\"id\":\"b\",\"type\":\"not_function\",\"function\":{\"name\":\"listMyOrders\",\"arguments\":\"{}\"}}]}}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject().put("role", "user").put("content", "query");
        assertThrows(AiException.class, () -> client().complete(messages, mapper.createArrayNode()));
    }

    @Test void rejectsMixedLegacyFunctionCallAndToolCalls() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom", exchange -> {
            String response = "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\","
                    + "\"function_call\":{\"name\":\"listMyOrders\",\"arguments\":\"{}\"},"
                    + "\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\","
                    + "\"function\":{\"name\":\"listMyOrders\",\"arguments\":\"{}\"}}]}}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject().put("role", "user").put("content", "query");
        AiException error = assertThrows(AiException.class, () -> client().complete(messages, mapper.createArrayNode()));
        assertEquals("模型返回格式异常，请稍后重试", error.getMessage());
    }

    @Test void timesOutWhenResponseBodyStallsAfterHeaders() throws Exception {
        AtomicInteger count = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom", exchange -> {
            count.incrementAndGet();
            byte[] body = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try {
                Thread.sleep(2500);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException ignored) { /* client timed out */ }
            finally { exchange.close(); }
        });
        server.start();
        AiProperties p = new AiProperties();
        p.setEnabled(true); p.setApiKey("test-key"); p.setModel("test-model");
        p.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/custom");
        p.setReadTimeoutMs(500);
        CustomerChatModelClient client = new CustomerChatModelClient(p, mapper);
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject().put("role", "user").put("content", "query");
        long started = System.nanoTime();
        AiException error = assertThrows(AiException.class, () -> client.complete(messages, mapper.createArrayNode()));
        long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(error.getMessage().contains("连接失败"), "a connection timeout must be reported to the caller");
        assertTrue(elapsed >= 300, "a failure before the configured timeout does not prove body timeout handling");
        assertTrue(elapsed < 2000, "stalled response body must time out before the server writes it");
        assertEquals(1, count.get());
    }

    @Test void doesNotFollowRedirectsOrForwardCredentials() throws Exception {
        AtomicInteger redirectHits = new AtomicInteger();
        AtomicInteger targetHits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom", exchange -> {
            int status = redirectHits.getAndIncrement() == 0 ? 302 : 307;
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        CustomerChatModelClient client = client();
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject().put("role", "user").put("content", "query");
        for (int i = 0; i < 2; i++)
            assertThrows(AiException.class, () -> client.complete(messages, mapper.createArrayNode()));
        assertEquals(2, redirectHits.get());
        assertEquals(0, targetHits.get());
    }

    private CustomerChatModelClient client() {
        AiProperties p = new AiProperties();
        p.setEnabled(true); p.setApiKey("test-key"); p.setModel("test-model");
        p.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/custom");
        return new CustomerChatModelClient(p, mapper);
    }
}
