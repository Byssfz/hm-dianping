package com.hmdp.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.ai.AiException;
import com.hmdp.ai.AiProperties;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.FinishReason;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** LangChain4j model adapter; business permissions and tool execution stay server-side. */
@Component
public class CustomerChatModelClient {
    private final AiProperties properties;
    private final ObjectMapper mapper;

    public CustomerChatModelClient(AiProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public ObjectNode complete(ArrayNode messages, ArrayNode tools) {
        properties.checkReady();
        String endpoint = properties.getEndpoint();
        try {
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .baseUrl("http://localhost")
                    .httpClientBuilder(new EndpointClientBuilder(endpoint, tools))
                    .apiKey(properties.getApiKey()).modelName(properties.getModel())
                    .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                    .maxRetries(0).maxTokens(2048).returnThinking(true).sendThinking(true)
                    .logRequests(false).logResponses(false).build();
            ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(convertMessages(messages)).toolSpecifications(convertTools(tools))
                    .toolChoice(ToolChoice.AUTO).build());
            if (response.finishReason() != FinishReason.STOP && response.finishReason() != FinishReason.TOOL_EXECUTION)
                throw new AiException("模型未返回完整回答，请稍后重试");
            AiMessage answer = response.aiMessage();
            if (answer == null) throw new AiException("模型返回格式异常，请稍后重试");
            ObjectNode result = mapper.createObjectNode().put("role", "assistant");
            if (answer.text() != null) result.put("content", answer.text());
            else result.putNull("content");
            if (answer.thinking() != null) result.put("reasoning_content", answer.thinking());
            if (answer.hasToolExecutionRequests()) {
                ArrayNode calls = result.putArray("tool_calls");
                for (ToolExecutionRequest call : answer.toolExecutionRequests()) {
                    ObjectNode node = calls.addObject().put("id", call.id()).put("type", "function");
                    node.putObject("function").put("name", call.name()).put("arguments", call.arguments());
                }
            }
            return result;
        } catch (AiException e) {
            throw e;
        } catch (RateLimitException e) {
            throw new AiException("模型服务繁忙，请稍后重试");
        } catch (HttpException e) {
            if (e.statusCode() == 429) throw new AiException("模型服务繁忙，请稍后重试");
            throw new AiException("模型服务调用失败，请联系管理员或稍后重试");
        } catch (RuntimeException e) {
            throw new AiException("模型连接失败或返回格式异常，请稍后重试");
        }
    }

    /** Preserve the configured full endpoint and exact existing tool schemas on the wire. */
    private final class EndpointClientBuilder implements HttpClientBuilder {
        private final String endpoint;
        private final ArrayNode tools;
        EndpointClientBuilder(String endpoint, ArrayNode tools) { this.endpoint = endpoint; this.tools = tools; }
        public Duration connectTimeout() { return Duration.ofMillis(properties.getConnectTimeoutMs()); }
        public Duration readTimeout() { return Duration.ofMillis(properties.getReadTimeoutMs()); }
        public HttpClientBuilder connectTimeout(Duration ignored) { return this; }
        public HttpClientBuilder readTimeout(Duration ignored) { return this; }
        public HttpClient build() {
            return new HttpClient() {
                public SuccessfulHttpResponse execute(HttpRequest request) {
                    try {
                        ObjectNode body = (ObjectNode) mapper.readTree(request.body());
                        body.set("tools", tools);
                        SuccessfulHttpResponse response = executeRequest(request, endpoint, body.toString());
                        validateResponse(response.body());
                        return response;
                    } catch (java.io.IOException e) {
                        throw new AiException("模型连接失败或超时，请稍后重试");
                    }
                }
                public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
                    throw new UnsupportedOperationException("Streaming is not supported");
                }
            };
        }
    }

    /** Socket read timeout also applies after response headers arrive. */
    private SuccessfulHttpResponse executeRequest(HttpRequest request, String endpoint, String body) throws java.io.IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(properties.getConnectTimeoutMs());
            connection.setReadTimeout(properties.getReadTimeoutMs());
            connection.setRequestMethod(request.method().name());
            request.headers().forEach((name, values) -> values.forEach(value -> connection.addRequestProperty(name, value)));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new HttpException(status, "Model request failed");
            byte[] response;
            try (java.io.InputStream input = connection.getInputStream()) { response = input.readAllBytes(); }
            Map<String, List<String>> headers = new HashMap<>();
            connection.getHeaderFields().forEach((name, values) -> { if (name != null) headers.put(name, values); });
            return SuccessfulHttpResponse.builder().statusCode(status).headers(headers).body(response).build();
        } finally {
            connection.disconnect();
        }
    }

    private void validateResponse(String body) throws java.io.IOException {
        JsonNode choice = mapper.readTree(body).path("choices").path(0);
        JsonNode message = choice.path("message");
        String finish = choice.path("finish_reason").asText();
        if (!message.isObject() || !"assistant".equals(message.path("role").asText())
                || !("stop".equals(finish) || "tool_calls".equals(finish))) {
            throw new AiException("模型未返回完整回答，请稍后重试");
        }
        // This endpoint uses the tool_calls protocol only. LangChain4j can normalize
        // legacy function_call into an additional tool request, so reject it before
        // parsing rather than risk executing part of a mixed-protocol response.
        if (message.hasNonNull("function_call"))
            throw new AiException("模型返回格式异常，请稍后重试");
        JsonNode calls = message.get("tool_calls");
        if (calls == null || calls.isNull()) return;
        if (!calls.isArray()) throw new AiException("模型返回格式异常，请稍后重试");
        for (JsonNode call : calls) {
            JsonNode function = call.path("function");
            if (!call.path("id").isTextual() || call.path("id").asText().isEmpty()
                    || call.path("id").asText().length() > 200
                    || !"function".equals(call.path("type").asText())
                    || !function.path("name").isTextual() || !function.path("arguments").isTextual())
                throw new AiException("模型返回格式异常，请稍后重试");
        }
    }

    private List<ChatMessage> convertMessages(ArrayNode messages) {
        List<ChatMessage> result = new ArrayList<>();
        for (JsonNode node : messages) {
            String role = node.path("role").asText();
            String text = node.hasNonNull("content") ? node.get("content").asText() : null;
            switch (role) {
                case "system": result.add(SystemMessage.from(text)); break;
                case "user": result.add(UserMessage.from(text)); break;
                case "tool": result.add(ToolExecutionResultMessage.from(node.path("tool_call_id").asText(), "", text)); break;
                case "assistant":
                    List<ToolExecutionRequest> calls = new ArrayList<>();
                    if (node.path("tool_calls").isArray()) for (JsonNode call : node.path("tool_calls")) {
                        calls.add(ToolExecutionRequest.builder().id(call.path("id").asText())
                                .name(call.path("function").path("name").asText())
                                .arguments(call.path("function").path("arguments").asText()).build());
                    }
                    result.add(AiMessage.builder().text(text).thinking(node.hasNonNull("reasoning_content")
                            ? node.get("reasoning_content").asText() : null).toolExecutionRequests(calls).build());
                    break;
                default: throw new AiException("模型消息格式异常，请稍后重试");
            }
        }
        return result;
    }

    private List<ToolSpecification> convertTools(ArrayNode tools) {
        List<ToolSpecification> result = new ArrayList<>();
        for (JsonNode tool : tools) {
            JsonNode function = tool.path("function");
            JsonNode params = function.path("parameters");
            JsonObjectSchema.Builder schema = JsonObjectSchema.builder().additionalProperties(false);
            List<String> required = new ArrayList<>();
            params.path("required").forEach(item -> required.add(item.asText()));
            schema.required(required);
            Iterator<Map.Entry<String, JsonNode>> fields = params.path("properties").fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode definition = field.getValue();
                // LangChain4j's typed schema lacks numeric and string constraints. Keep them
                // in the description as well as the original schema restored by the HTTP adapter.
                String description = definition.path("description").asText("") + " " + definition.toString();
                if ("integer".equals(definition.path("type").asText())) schema.addIntegerProperty(field.getKey(), description);
                else schema.addStringProperty(field.getKey(), description);
            }
            result.add(ToolSpecification.builder().name(function.path("name").asText())
                    .description(function.path("description").asText()).parameters(schema.build()).build());
        }
        return result;
    }
}
