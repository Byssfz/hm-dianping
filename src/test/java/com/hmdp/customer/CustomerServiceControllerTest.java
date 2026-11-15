package com.hmdp.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.controller.CustomerServiceController;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CustomerServiceControllerTest {
    CustomerService service = mock(CustomerService.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    MockMvc mvc;

    @BeforeEach void setup() {
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(anyString())).thenReturn(Collections.emptyMap());
        mvc = MockMvcBuilders.standaloneSetup(new CustomerServiceController(service))
                .addInterceptors(new RefreshTokenInterceptor(redis), new LoginInterceptor()).build();
    }

    @AfterEach void cleanup() { UserHolder.removeUser(); }

    @Test void anonymousRequestIs401BeforeModelCall() throws Exception {
        mvc.perform(post("/customer-service/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"你好\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void tokenSuppliesTrustedUserAndResponseUsesResult() throws Exception {
        when(hashes.entries("login:token:valid-token")).thenReturn(Collections.singletonMap("id", "7"));
        when(service.chat(any(), eq(7L))).thenReturn(new ObjectMapper().createObjectNode()
                .put("conversationId", "session").put("reply", "你好"));
        mvc.perform(post("/customer-service/chat").header("authorization", "valid-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reply").value("你好"));
        assertNull(UserHolder.getUser());
    }

    @Test void customerFailureUsesSafeResultMessage() throws Exception {
        UserDTO user = new UserDTO(); user.setId(7L); UserHolder.saveUser(user);
        when(service.chat(any(), eq(7L))).thenThrow(new CustomerServiceException("智能客服暂未启用"));
        mvc.perform(post("/customer-service/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("智能客服暂未启用"));
    }
}
