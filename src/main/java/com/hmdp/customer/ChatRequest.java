package com.hmdp.customer;

import lombok.Data;

@Data
public class ChatRequest {
    private String message;
    private String conversationId;
    private String shopId;
}
