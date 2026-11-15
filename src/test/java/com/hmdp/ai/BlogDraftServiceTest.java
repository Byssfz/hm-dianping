package com.hmdp.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BlogDraftServiceTest {
    ObjectMapper mapper = new ObjectMapper();
    ModelClient model = mock(ModelClient.class);
    IShopService shops = mock(IShopService.class);
    AiQuota quota = mock(AiQuota.class);
    BlogDraftService service;

    private static final String DRAFT = "{\"title\":\"蟹黄面天花板\",\"content\":"
            + "\"今天打卡了蟹家大院，蟹黄面真的很实在。环境安静，适合慢慢吃，人均一百八左右。\","
            + "\"tags\":[\"#蟹黄面\",\"静安寺\"]}";

    @BeforeEach void setup() {
        service = new BlogDraftService(AiTestFixtures.configured(), model, shops, quota, mapper);
    }

    BlogDraftRequest request(String keywords) {
        BlogDraftRequest request = new BlogDraftRequest();
        request.setKeywords(keywords);
        return request;
    }

    ObjectNode answer(String json) {
        return mapper.createObjectNode().put("role", "assistant").put("content", json);
    }

    Shop shop() {
        return new Shop().setId(12L).setName("蟹家大院").setArea("静安寺").setAddress("南京西路 1 号")
                .setOpenHours("11:00-22:00").setAvgPrice(180L).setScore(48);
    }

    @Test void generatesDraftFromShopFactsAndKeywords() {
        when(shops.getById(12L)).thenReturn(shop());
        when(model.complete(any(ArrayNode.class))).thenReturn(answer(DRAFT));
        BlogDraftRequest request = request(" 蟹黄面, 环境安静 ");
        request.setShopId("12");
        request.setStyle("casual");

        ObjectNode data = service.draft(request, 7L);

        assertEquals("蟹黄面天花板", data.path("title").asText());
        assertTrue(data.path("content").asText().contains("蟹家大院"));
        assertEquals("蟹黄面", data.path("tags").get(0).asText());
        assertEquals("静安寺", data.path("tags").get(1).asText());
        assertEquals("12", data.path("shopId").asText());
        assertEquals("蟹家大院", data.path("shopName").asText());
        assertEquals("casual", data.path("style").asText());
        verify(quota).consume(7L);

        ArgumentCaptor<ArrayNode> captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(model).complete(captor.capture());
        String prompt = captor.getValue().toString();
        assertTrue(prompt.contains("蟹家大院"), "shop facts must reach the model");
        assertTrue(prompt.contains("南京西路 1 号"), "address must reach the model");
        assertTrue(prompt.contains("蟹黄面, 环境安静"), "keywords must be trimmed and passed through");
        assertTrue(prompt.contains("不是指令"), "input must be framed as data, not instructions");
        assertEquals("system", captor.getValue().get(0).path("role").asText());
    }

    @Test void draftWithoutShopDoesNotQueryOrInventOne() {
        when(model.complete(any(ArrayNode.class))).thenReturn(answer(DRAFT));
        ObjectNode data = service.draft(request("随便写写"), 7L);
        assertFalse(data.has("shopId"));
        assertFalse(data.has("shopName"));
        verifyNoInteractions(shops);
        verify(quota).consume(7L);
        assertEquals("xiaohongshu", data.path("style").asText());
    }

    @Test void acceptsMarkdownFencedJson() {
        when(model.complete(any(ArrayNode.class))).thenReturn(answer("```json\n" + DRAFT + "\n```"));
        assertEquals("蟹黄面天花板", service.draft(request("蟹黄面"), 7L).path("title").asText());
    }

    @Test void rejectsUnknownStyleBeforeCallingModel() {
        BlogDraftRequest request = request("蟹黄面");
        request.setStyle("poem");
        assertEquals("不支持的写作风格", assertThrows(AiException.class, () -> service.draft(request, 7L)).getMessage());
        verifyNoInteractions(model, quota, shops);
    }

    @Test void rejectsBlankAndOversizedKeywords() {
        assertEquals("请填写探店关键词", assertThrows(AiException.class, () -> service.draft(request("   "), 7L)).getMessage());
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 121; i++) longText.append('蟹');
        assertEquals("关键词不能超过120字",
                assertThrows(AiException.class, () -> service.draft(request(longText.toString()), 7L)).getMessage());
        verifyNoInteractions(model, quota);
    }

    @Test void rejectsInvalidShopIdAndMissingShop() {
        BlogDraftRequest invalid = request("蟹黄面");
        invalid.setShopId("-1");
        assertEquals("shopId必须为有效的正整数ID字符串",
                assertThrows(AiException.class, () -> service.draft(invalid, 7L)).getMessage());

        BlogDraftRequest missing = request("蟹黄面");
        missing.setShopId("9999999999999999999");
        assertEquals("shopId必须为有效的正整数ID字符串",
                assertThrows(AiException.class, () -> service.draft(missing, 7L)).getMessage());

        BlogDraftRequest absent = request("蟹黄面");
        absent.setShopId("404");
        when(shops.getById(404L)).thenReturn(null);
        assertEquals("未找到关联商铺，请重新选择",
                assertThrows(AiException.class, () -> service.draft(absent, 7L)).getMessage());
        verifyNoInteractions(model, quota);
    }

    @Test void rejectsMalformedModelOutput() {
        when(model.complete(any(ArrayNode.class))).thenReturn(answer("好的，我来写一篇：今天去了蟹家大院……"));
        assertEquals("文案生成失败，请稍后重试",
                assertThrows(AiException.class, () -> service.draft(request("蟹黄面"), 7L)).getMessage());
    }

    @Test void rejectsOversizedTitleFromModel() {
        when(model.complete(any(ArrayNode.class))).thenReturn(answer(
                "{\"title\":\"" + repeat('长', 41) + "\",\"content\":\"这是一段足够长的探店正文内容。\"}"));
        assertEquals("文案生成失败，请稍后重试",
                assertThrows(AiException.class, () -> service.draft(request("蟹黄面"), 7L)).getMessage());
    }

    @Test void quotaFailureStopsModelCall() {
        doThrow(new AiException("AI 文案生成次数已达上限，请稍后再试")).when(quota).consume(7L);
        assertEquals("AI 文案生成次数已达上限，请稍后再试",
                assertThrows(AiException.class, () -> service.draft(request("蟹黄面"), 7L)).getMessage());
        verifyNoInteractions(model);
    }

    @Test void anonymousRequestIsRejectedBeforeAnythingElse() {
        assertEquals("请先登录", assertThrows(AiException.class, () -> service.draft(request("蟹黄面"), null)).getMessage());
        verifyNoInteractions(model, quota, shops);
    }

    private static String repeat(char value, int times) {
        StringBuilder builder = new StringBuilder(times);
        for (int i = 0; i < times; i++) builder.append(value);
        return builder.toString();
    }
}
