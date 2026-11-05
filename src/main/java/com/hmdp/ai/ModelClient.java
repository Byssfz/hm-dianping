package com.hmdp.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

import java.io.IOException;
import java.net.HttpURLConnection;

@Slf4j
@Component
public class ModelClient {
    private final AiProperties properties;
    private final ObjectMapper mapper;
    private final RestTemplate http;

    public ModelClient(AiProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(Math.max(1, Math.min(properties.getConnectTimeoutMs(), 10000)));
        factory.setReadTimeout(Math.max(1, Math.min(properties.getReadTimeoutMs(), 30000)));
        this.http = new RestTemplate(factory);
    }

    // Package visibility allows MockRestServiceServer tests without opening a real network connection.
    RestTemplate http() { return http; }

    /** Chat completion with tool calling, used by the customer-service loop. */
    public ObjectNode complete(ArrayNode messages, ArrayNode tools) {
        properties.checkReady();
        ObjectNode body = baseBody(messages);
        body.set("tools", tools);
        body.put("tool_choice", "auto");
        return send(body, true);
    }

    /** Plain chat completion without tools, used by single-shot generation features. */
    public ObjectNode complete(ArrayNode messages) {
        properties.checkReady();
        return send(baseBody(messages), false);
    }

    private ObjectNode baseBody(ArrayNode messages) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", properties.getModel());
        body.put("stream", false);
        body.put("max_tokens", 2048);
        body.set("messages", messages);
        return body;
    }

    private ObjectNode send(ObjectNode body, boolean toolsAllowed) {
        properties.checkReady();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getApiKey());
        try {
            ResponseEntity<JsonNode> response = http.exchange(properties.getEndpoint(), HttpMethod.POST,
                    new HttpEntity<>(body, headers), JsonNode.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new AiException("模型返回格式异常，请稍后重试");
            }
            JsonNode choice = response.getBody().path("choices").path(0);
            JsonNode message = choice.path("message");
            String reason = choice.path("finish_reason").asText();
            // A truncated answer (finish_reason=length) must never be reported as a complete result.
            if (!message.isObject() || !"assistant".equals(message.path("role").asText())
                    || !("stop".equals(reason) || (toolsAllowed && "tool_calls".equals(reason)))) {
                throw new AiException("模型未返回完整回答，请稍后重试");
            }
            ObjectNode result = mapper.createObjectNode().put("role", "assistant");
            // Preserve provider reasoning within the tool loop, but never return or persist it.
            for (String field : new String[]{"content", "tool_calls", "reasoning_content"}) {
                if (message.has(field)) result.set(field, message.get(field));
            }
            return result;
        } catch (HttpStatusCodeException e) {
            log.warn("ai model http error status={} category={}",
                    e.getRawStatusCode(), e.getClass().getSimpleName());
            if (e.getRawStatusCode() == 429) throw new AiException("模型服务繁忙，请稍后重试");
            throw new AiException("模型服务调用失败，请联系管理员或稍后重试");
        } catch (ResourceAccessException e) {
            log.warn("ai model connection error category={}", e.getClass().getSimpleName());
            throw new AiException("模型连接失败或超时，请稍后重试");
        } catch (RestClientException e) {
            log.warn("ai model response error category={}", e.getClass().getSimpleName());
            throw new AiException("模型返回格式异常，请稍后重试");
        }
    }
}
