package com.hmdp.controller;

import com.hmdp.ai.AiException;
import com.hmdp.customer.ChatRequest;
import com.hmdp.customer.CustomerService;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/customer-service")
public class CustomerServiceController {
    private final CustomerService service;

    public CustomerServiceController(CustomerService service) { this.service = service; }

    @PostMapping("/chat")
    public Result chat(@RequestBody ChatRequest request) {
        UserDTO user = UserHolder.getUser();
        return Result.ok(service.chat(request, user == null ? null : user.getId()));
    }

    @ExceptionHandler(AiException.class)
    public Result handleCustomerError(AiException error) { return Result.fail(error.getMessage()); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result handleInvalidJson() { return Result.fail("请求JSON格式无效"); }

    @ExceptionHandler(Exception.class)
    public Result handleUnexpected(Exception error) {
        log.warn("customer_service failed category={}", error.getClass().getSimpleName());
        return Result.fail("客服服务暂时不可用，请稍后重试");
    }
}
