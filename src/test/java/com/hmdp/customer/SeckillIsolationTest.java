package com.hmdp.customer;

import com.hmdp.listener.SckillConsumerListener;
import com.hmdp.reliability.SeckillOutboxRelay;
import com.hmdp.service.IVoucherOrderService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeckillIsolationTest {
    private ApplicationContextRunner context() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getLock(anyString())).thenReturn(mock(RLock.class));
        return new ApplicationContextRunner()
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(RocketMQTemplate.class, () -> mock(RocketMQTemplate.class))
                .withBean(IVoucherOrderService.class, () -> mock(IVoucherOrderService.class))
                .withBean(RedissonClient.class, () -> redisson)
                .withUserConfiguration(SeckillOutboxRelay.class, SckillConsumerListener.class);
    }

    @Test void explicitFalseDisablesBothBackgroundBeans() {
        context().withPropertyValues("hmdp.seckill-outbox.enabled=false", "hmdp.seckill-consumer.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(SeckillOutboxRelay.class);
                    assertThat(ctx).doesNotHaveBean(SckillConsumerListener.class);
                });
    }

    @Test void defaultStillRegistersBothBackgroundBeans() {
        context().run(ctx -> {
            assertThat(ctx).hasSingleBean(SeckillOutboxRelay.class);
            assertThat(ctx).hasSingleBean(SckillConsumerListener.class);
        });
    }
}
