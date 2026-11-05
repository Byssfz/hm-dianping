package com.hmdp.controller;

import com.hmdp.ai.AiException;
import com.hmdp.ai.BlogDraftRequest;
import com.hmdp.ai.BlogDraftService;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/ai/blog")
public class BlogDraftController {
    private final BlogDraftService service;

    public BlogDraftController(BlogDraftService service) { this.service = service; }

    @PostMapping("/draft")
    public Result draft(@RequestBody BlogDraftRequest request) {
        UserDTO user = UserHolder.getUser();
        return Result.ok(service.draft(request, user == null ? null : user.getId()));
    }

    @ExceptionHandler(AiException.class)
    public Result handleAiError(AiException error) { return Result.fail(error.getMessage()); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result handleInvalidJson() { return Result.fail("请求JSON格式无效"); }

    @ExceptionHandler(Exception.class)
    public Result handleUnexpected(Exception error) {
        log.warn("ai_blog_draft failed category={}", error.getClass().getSimpleName());
        return Result.fail("文案生成服务暂时不可用，请稍后重试");
    }
}
