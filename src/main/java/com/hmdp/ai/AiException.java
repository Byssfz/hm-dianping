package com.hmdp.ai;

/** Only safe, user-facing messages belong in this exception. */
public class AiException extends RuntimeException {
    public AiException(String message) {
        super(message);
    }
}
