package com.hmdp.customer;

/** Only safe, user-facing messages belong in this exception. */
public class CustomerServiceException extends com.hmdp.ai.AiException {
    public CustomerServiceException(String message) {
        super(message);
    }
}
