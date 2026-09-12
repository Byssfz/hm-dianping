package com.hmdp.customer;

/** Only safe, user-facing messages belong in this exception. */
public class CustomerServiceException extends RuntimeException {
    public CustomerServiceException(String message) {
        super(message);
    }
}
