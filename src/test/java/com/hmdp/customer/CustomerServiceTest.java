package com.hmdp.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.ai.AiTestFixtures;
import com.hmdp.ai.ModelClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerServiceTest {
    ObjectMapper mapper = new ObjectMapper();
    ModelClient model = mock(ModelClient.class);
    CustomerServiceTools tools = mock(CustomerServiceTools.class);
    ConversationStore store = mock(ConversationStore.class);
    CustomerService service;
    ChatRequest request;

    @BeforeEach void setup() {
        service = new CustomerService(AiTestFixtures.configured(), model, tools, store, mapper);
        request = new ChatRequest();
        request.setMessage("查询我的订单");
        when(store.acquire(anyLong(), anyString(), anyString())).thenReturn(true);
        when(store.load(anyLong(), anyString(), anyBoolean())).thenAnswer(i -> mapper.createArrayNode());
        when(tools.definitions()).thenReturn(mapper.createArrayNode());
        when(tools.references(any())).thenReturn(mapper.createArrayNode());
    }

    ObjectNode answer(String text) { return mapper.createObjectNode().put("role", "assistant").put("content", text); }

    ObjectNode call(String id, String name, String args) {
        ObjectNode message = mapper.createObjectNode().put("role", "assistant");
        message.putArray("tool_calls").addObject().put("id", id).put("type", "function")
                .putObject("function").put("name", name).put("arguments", args);
        return message;
    }

    @Test void toolLoopUsesTrustedUserAndPersistsOnlySuccessfulTurn() {
        when(model.complete(any(), any())).thenReturn(call("a", "listMyOrders", "{}"), answer("未找到订单"));
        when(tools.execute("listMyOrders", "{}", 7L)).thenReturn(mapper.createObjectNode().set("items", mapper.createArrayNode()));
        ObjectNode result = service.chat(request, 7L);
        assertEquals("未找到订单", result.path("reply").asText());
        assertTrue(result.path("conversationId").asText().matches("[0-9a-f-]{36}"));
        verify(tools).execute("listMyOrders", "{}", 7L);
        verify(store).save(eq(7L), eq(result.path("conversationId").asText()), anyString(), argThat(turns ->
                turns.size() == 1 && turns.get(0).path("assistant").path("content").asText().equals("未找到订单")));
        verify(store).release(eq(7L), anyString(), anyString());
    }

    @Test void followupReceivesCandidateIdsAndRequeriesCurrentDetails() {
        request.setConversationId("00000000-0000-0000-0000-000000000001");
        request.setMessage("第二家几点关门？");
        ArrayNode history = mapper.createArrayNode();
        ObjectNode turn = history.addObject();
        turn.set("user", mapper.createObjectNode().put("role", "user").put("content", "找茶店"));
        turn.set("assistant", answer("找到两家，请选择"));
        turn.putArray("references").addObject().put("shopId", "1").put("name", "茶店一");
        ((ArrayNode) turn.get("references")).addObject().put("shopId", "2").put("name", "茶店二");
        when(store.load(7L, request.getConversationId(), false)).thenReturn(history);
        when(tools.execute("getShop", "{\"shopId\":\"2\"}", 7L))
                .thenReturn(mapper.createObjectNode().put("openHours", "10:00-22:00"));
        when(model.complete(any(), any())).thenAnswer(i -> {
            ArrayNode messages = i.getArgument(0);
            assertTrue(messages.toString().contains("茶店二"));
            assertTrue(messages.toString().contains("需重新查询当前事实"));
            if (messages.get(messages.size() - 1).path("role").asText().equals("tool")) return answer("晚上22点关门");
            return call("b", "getShop", "{\"shopId\":\"2\"}");
        });
        assertEquals("晚上22点关门", service.chat(request, 7L).path("reply").asText());
        verify(tools).execute("getShop", "{\"shopId\":\"2\"}", 7L);
    }

    @Test void modelFailureDoesNotSaveHistoryAndReleasesLock() {
        when(model.complete(any(), any())).thenThrow(new CustomerServiceException("模型超时"));
        assertThrows(CustomerServiceException.class, () -> service.chat(request, 7L));
        verify(store, never()).save(anyLong(), anyString(), anyString(), any());
        verify(store).release(eq(7L), anyString(), anyString());
    }

    @Test void busyConversationNeverCallsModelOrUnlocksAnotherRequest() {
        when(store.acquire(anyLong(), anyString(), anyString())).thenReturn(false);
        assertThrows(CustomerServiceException.class, () -> service.chat(request, 7L));
        verifyNoInteractions(model);
        verify(store, never()).release(anyLong(), anyString(), anyString());
    }

    @Test void expiredConversationDoesNotFallBackToNewHistory() {
        request.setConversationId("00000000-0000-0000-0000-000000000001");
        when(store.load(anyLong(), anyString(), eq(false))).thenThrow(new CustomerServiceException("会话已过期"));
        assertThrows(CustomerServiceException.class, () -> service.chat(request, 7L));
        verifyNoInteractions(model);
    }

    @Test void enforcesModelRoundLimit() {
        when(model.complete(any(), any())).thenReturn(call("1", "listMyOrders", "{}"), call("2", "listMyOrders", "{}"),
                call("3", "listMyOrders", "{}"), call("4", "listMyOrders", "{}"));
        when(tools.execute(anyString(), anyString(), anyLong())).thenReturn(mapper.createObjectNode());
        assertThrows(CustomerServiceException.class, () -> service.chat(request, 7L));
        verify(model, times(4)).complete(any(), any());
        verify(tools, times(3)).execute(anyString(), anyString(), eq(7L));
        verify(store, never()).save(anyLong(), anyString(), anyString(), any());
    }

    @Test void enforcesTotalToolLimitBeforeExecutingOversizedBatch() {
        ObjectNode batch = call("1", "listMyOrders", "{}");
        ArrayNode calls = (ArrayNode) batch.get("tool_calls");
        for (int n = 2; n <= 7; n++) calls.add(call(String.valueOf(n), "listMyOrders", "{}").path("tool_calls").get(0));
        when(model.complete(any(), any())).thenReturn(batch);
        assertThrows(CustomerServiceException.class, () -> service.chat(request, 7L));
        verify(tools, never()).execute(anyString(), anyString(), anyLong());
    }

    @Test void invalidToolArgumentsCanBeCorrectedByModel() {
        when(model.complete(any(), any())).thenReturn(call("1", "getMyOrder", "{}"),
                call("2", "getMyOrder", "{\"orderId\":\"1\"}"), answer("订单未支付"));
        when(tools.execute(eq("getMyOrder"), anyString(), eq(7L)))
                .thenReturn(mapper.createObjectNode().put("error", "参数无效"), mapper.createObjectNode().put("status", "未支付"));
        assertEquals("订单未支付", service.chat(request, 7L).path("reply").asText());
        verify(model, times(3)).complete(any(), any());
    }

    @Test void rejectsMalformedModelOutputAndBlankQuestion() {
        when(model.complete(any(), any())).thenReturn(answer(""));
        assertThrows(CustomerServiceException.class, () -> service.chat(request, 7L));
        request.setMessage(" ");
        assertThrows(CustomerServiceException.class, () -> service.chat(request, 7L));
    }

    @Test void redisFailureGetsFriendlyError() {
        when(store.acquire(anyLong(), anyString(), anyString())).thenThrow(new RedisConnectionFailureException("secret-host"));
        assertEquals("业务数据或会话服务暂时不可用，请稍后重试",
                assertThrows(CustomerServiceException.class, () -> service.chat(request, 7L)).getMessage());
    }
}
