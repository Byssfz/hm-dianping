package com.hmdp.orderclose;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderCloseTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LocalDateTime start = LocalDateTime.of(2026, 11, 6, 0, 0);
    private final LocalDateTime cutoff = start.plusMinutes(15);

    @Test void successfulCasRestoresStockAndCreatesRecoveryOnce() {
        Map<String, Object> row = new HashMap<>();
        row.put("voucher_id", 2L); row.put("user_id", 3L);
        when(jdbc.queryForList(anyString(), anyLong(), any(java.sql.Timestamp.class), any(java.sql.Timestamp.class))).thenReturn(Collections.singletonList(row));
        when(jdbc.update(startsWith("UPDATE tb_voucher_order"), any(), any(), any())).thenReturn(1, 0);
        when(jdbc.update(startsWith("UPDATE tb_seckill_voucher"), anyLong())).thenReturn(1);
        when(jdbc.update(startsWith("INSERT INTO tb_order_stock_recovery"), any(), any(), any())).thenReturn(1);
        OrderCloseTransaction worker = new OrderCloseTransaction(jdbc);
        assertTrue(worker.close(5, start, cutoff));
        assertFalse(worker.close(5, start, cutoff));
        verify(jdbc).update(startsWith("UPDATE tb_seckill_voucher"), eq(2L));
        verify(jdbc).update(startsWith("INSERT INTO tb_order_stock_recovery"), eq(5L), eq(2L), eq(3L));
    }

    @Test void stockFailureThrowsToRollbackTransaction() {
        Map<String, Object> row = new HashMap<>();
        row.put("voucher_id", 2L); row.put("user_id", 3L);
        when(jdbc.queryForList(anyString(), anyLong(), any(java.sql.Timestamp.class), any(java.sql.Timestamp.class))).thenReturn(Collections.singletonList(row));
        when(jdbc.update(startsWith("UPDATE tb_voucher_order"), any(), any(), any())).thenReturn(1);
        when(jdbc.update(startsWith("UPDATE tb_seckill_voucher"), anyLong())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> new OrderCloseTransaction(jdbc).close(5, start, cutoff));
        verify(jdbc, never()).update(startsWith("INSERT INTO tb_order_stock_recovery"), any(), any(), any());
    }

    @Test void scansContinuePastFailuresThenWrap() {
        OrderCloseTransaction worker = mock(OrderCloseTransaction.class);
        OrderCloseTask task = new OrderCloseTask(jdbc, mock(StringRedisTemplate.class), worker, "2026-11-06T00:00:00", 15, 2);
        task.validate();
        when(jdbc.queryForList(anyString(), eq(Long.class), any(), any(), any(), any()))
                .thenReturn(Arrays.asList(1L, 2L), Collections.singletonList(3L), Collections.emptyList(), Collections.singletonList(1L));
        when(worker.close(eq(1L), any(), any())).thenThrow(new IllegalStateException("fail"));
        for (int i=0; i<4; i++) ReflectionTestUtils.invokeMethod(task, "closeExpired");
        verify(worker, times(2)).close(eq(1L), any(), any());
        verify(worker).close(eq(2L), any(), any());
        verify(worker).close(eq(3L), any(), any());
        verify(jdbc).queryForList(anyString(), eq(Long.class), any(), any(), eq(2L), eq(2));
        verify(jdbc).queryForList(anyString(), eq(Long.class), any(), any(), eq(3L), eq(2));
    }

    @Test void enabledTaskRequiresExplicitStartAndValidLimits() {
        assertThrows(IllegalArgumentException.class, () -> new OrderCloseTask(jdbc, null, null, "", 15, 1).validate());
        assertThrows(IllegalArgumentException.class, () -> new OrderCloseTask(jdbc, null, null, "2026-11-06T00:00:00", 0, 1).validate());
    }

    @Test void schedulerIsNotRegisteredByDefault() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(OrderCloseTask.class);
            context.refresh();
            assertTrue(context.getBeansOfType(OrderCloseTask.class).isEmpty());
        }
    }

    @Test void enablingWithoutStartTimeFailsContextStartup() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Collections.singletonMap("hmdp.order-close.enabled", "true")));
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));
            context.registerBean(OrderCloseTransaction.class, () -> mock(OrderCloseTransaction.class));
            context.register(OrderCloseTask.class);
            assertThrows(org.springframework.beans.factory.BeanCreationException.class, context::refresh);
        }
    }
}
