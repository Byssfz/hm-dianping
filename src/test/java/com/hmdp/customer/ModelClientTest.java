package com.hmdp.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ModelClientTest {
    ObjectMapper mapper = new ObjectMapper();
    AiProperties properties;
    ModelClient client;
    MockRestServiceServer server;

    static AiProperties configured() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setEndpoint("https://model.example/chat/completions");
        properties.setModel("test-model");
        properties.setApiKey("test-secret");
        return properties;
    }

    @BeforeEach void setup() {
        properties = configured();
        client = new ModelClient(properties, mapper);
        server = MockRestServiceServer.bindTo(client.http()).build();
    }

    @Test void sendsCompatibleProtocolAndPreservesToolReasoning() {
        server.expect(requestTo(properties.getEndpoint())).andExpect(header("Authorization", "Bearer test-secret"))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.tools").isArray())
                .andRespond(withSuccess("{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\","
                        + "\"content\":null,\"reasoning_content\":\"internal\",\"tool_calls\":[]}}]}", MediaType.APPLICATION_JSON));
        assertEquals("internal", client.complete(mapper.createArrayNode(), mapper.createArrayNode()).path("reasoning_content").asText());
        server.verify();
    }

    @Test void missingConfigurationFailsBeforeHttp() {
        properties.setApiKey("");
        assertThrows(CustomerServiceException.class, () -> client.complete(mapper.createArrayNode(), mapper.createArrayNode()));
        server.verify();
    }

    @Test void disabledConfigurationDoesNotNeedCredentials() {
        AiProperties disabled = new AiProperties();
        assertDoesNotThrow(() -> new ModelClient(disabled, mapper));
        assertEquals("智能客服暂未启用", assertThrows(CustomerServiceException.class, disabled::checkReady).getMessage());
    }

    @Test void rejectsInvalidTimeoutAndEndpoint() {
        properties.setReadTimeoutMs(0);
        assertThrows(CustomerServiceException.class, properties::checkReady);
        properties.setReadTimeoutMs(30000);
        properties.setEndpoint("file:///secret");
        assertThrows(CustomerServiceException.class, properties::checkReady);
    }

    @Test void rateLimitDoesNotExposeProviderResponseOrKey() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("secret-provider-detail"));
        String message = assertThrows(CustomerServiceException.class,
                () -> client.complete(mapper.createArrayNode(), mapper.createArrayNode())).getMessage();
        assertEquals("模型服务繁忙，请稍后重试", message);
        server.verify();
    }

    @Test void timeoutIsFriendly() {
        server.expect(anything()).andRespond(request -> { throw new IOException("sensitive connection details"); });
        assertEquals("模型连接失败或超时，请稍后重试", assertThrows(CustomerServiceException.class,
                () -> client.complete(mapper.createArrayNode(), mapper.createArrayNode())).getMessage());
    }

    @Test void malformedResponseIsRejected() {
        server.expect(anything()).andRespond(withSuccess("{\"unexpected\":true}", MediaType.APPLICATION_JSON));
        assertThrows(CustomerServiceException.class, () -> client.complete(mapper.createArrayNode(), mapper.createArrayNode()));
    }

    @Test void truncatedAnswerIsNotReportedAsSuccess() {
        server.expect(anything()).andRespond(withSuccess("{\"choices\":[{\"finish_reason\":\"length\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"partial\"}}]}", MediaType.APPLICATION_JSON));
        assertThrows(CustomerServiceException.class, () -> client.complete(mapper.createArrayNode(), mapper.createArrayNode()));
    }
}
