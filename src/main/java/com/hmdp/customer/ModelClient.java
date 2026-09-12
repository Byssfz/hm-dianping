package com.hmdp.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

import java.io.IOException;
import java.net.HttpURLConnection;

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

    public ObjectNode complete(ArrayNode messages, ArrayNode tools) {
        properties.checkReady();
        ObjectNode body = mapper.createObjectNode();
        body.put("model", properties.getModel());
        body.put("stream", false);
        body.put("max_tokens", 2048);
        body.set("messages", messages);
        body.set("tools", tools);
        body.put("tool_choice", "auto");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getApiKey());
        try {
            ResponseEntity<JsonNode> response = http.exchange(properties.getEndpoint(), HttpMethod.POST,
                    new HttpEntity<>(body, headers), JsonNode.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new CustomerServiceException("模型返回格式异常，请稍后重试");
            }
            JsonNode choice = response.getBody().path("choices").path(0);
            JsonNode message = choice.path("message");
            String reason = choice.path("finish_reason").asText();
            if (!message.isObject() || !"assistant".equals(message.path("role").asText())
                    || !("stop".equals(reason) || "tool_calls".equals(reason))) {
                throw new CustomerServiceException("模型未返回完整回答，请稍后重试");
            }
            ObjectNode result = mapper.createObjectNode().put("role", "assistant");
            // Preserve provider reasoning within this tool loop, but never return or persist it.
            for (String field : new String[]{"content", "tool_calls", "reasoning_content"}) {
                if (message.has(field)) result.set(field, message.get(field));
            }
            return result;
        } catch (HttpStatusCodeException e) {
            if (e.getRawStatusCode() == 429) throw new CustomerServiceException("模型服务繁忙，请稍后重试");
            throw new CustomerServiceException("模型服务调用失败，请联系管理员或稍后重试");
        } catch (ResourceAccessException e) {
            throw new CustomerServiceException("模型连接失败或超时，请稍后重试");
        } catch (RestClientException e) {
            throw new CustomerServiceException("模型返回格式异常，请稍后重试");
        }
    }
}
