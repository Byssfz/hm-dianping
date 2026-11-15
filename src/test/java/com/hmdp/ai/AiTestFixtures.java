package com.hmdp.ai;

/** Shared AI configuration for tests that must never reach a real model service. */
public final class AiTestFixtures {
    private AiTestFixtures() {
    }

    public static AiProperties configured() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setEndpoint("https://model.example/chat/completions");
        properties.setModel("test-model");
        properties.setApiKey("test-secret");
        return properties;
    }
}
