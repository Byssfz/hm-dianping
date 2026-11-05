package com.hmdp.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.controller.BlogDraftController;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BlogDraftControllerTest {
    BlogDraftService service = mock(BlogDraftService.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    MockMvc mvc;

    @BeforeEach void setup() {
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries(anyString())).thenReturn(Collections.emptyMap());
        mvc = MockMvcBuilders.standaloneSetup(new BlogDraftController(service))
                .addInterceptors(new RefreshTokenInterceptor(redis), new LoginInterceptor()).build();
    }

    @AfterEach void cleanup() { UserHolder.removeUser(); }

    @Test void anonymousRequestIs401BeforeGeneration() throws Exception {
        mvc.perform(post("/ai/blog/draft").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keywords\":\"蟹黄面\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void tokenSuppliesTrustedUserAndResponseUsesResult() throws Exception {
        when(hashes.entries("login:token:valid-token")).thenReturn(Collections.singletonMap("id", "7"));
        when(service.draft(any(), eq(7L))).thenReturn(new ObjectMapper().createObjectNode()
                .put("title", "标题").put("content", "正文"));
        mvc.perform(post("/ai/blog/draft").header("authorization", "valid-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keywords\":\"蟹黄面\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.title").value("标题"));
        assertNull(UserHolder.getUser());
    }

    @Test void aiFailureUsesSafeResultMessage() throws Exception {
        UserDTO user = new UserDTO(); user.setId(7L); UserHolder.saveUser(user);
        when(service.draft(any(), eq(7L))).thenThrow(new AiException("AI 服务暂未启用"));
        mvc.perform(post("/ai/blog/draft").contentType(MediaType.APPLICATION_JSON).content("{\"keywords\":\"蟹黄面\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("AI 服务暂未启用"));
    }

    @Test void invalidJsonIsReportedSafely() throws Exception {
        UserDTO user = new UserDTO(); user.setId(7L); UserHolder.saveUser(user);
        mvc.perform(post("/ai/blog/draft").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("请求JSON格式无效"));
        verifyNoInteractions(service);
    }
}
