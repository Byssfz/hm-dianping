package com.hmdp.customer;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "customer-service.ai")
public class AiProperties {
    private boolean enabled;
    private String endpoint = "";
    private String model = "";
    private String apiKey = "";
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 30000;

    // Validate on the chat path: a missing AI configuration must not prevent normal business startup.
    public void checkReady() {
        if (!enabled) {
            throw new CustomerServiceException("智能客服暂未启用");
        }
        if (!StringUtils.hasText(endpoint) || !StringUtils.hasText(model) || !StringUtils.hasText(apiKey)) {
            throw new CustomerServiceException("智能客服配置不完整，请联系管理员");
        }
        try {
            URI uri = URI.create(endpoint);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || connectTimeoutMs < 1 || connectTimeoutMs > 10000
                    || readTimeoutMs < 1 || readTimeoutMs > 30000) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new CustomerServiceException("智能客服地址或超时配置无效，请联系管理员");
        }
    }
}
