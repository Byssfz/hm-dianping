package com.hmdp.orderclose;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import javax.annotation.PostConstruct;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "hmdp.order-close.enabled", havingValue = "true")
public class OrderCloseTask {
    private static final DefaultRedisScript<Long> RESTORE = new DefaultRedisScript<>();
    static {
        RESTORE.setLocation(new ClassPathResource("restore-seckill-stock.lua"));
        RESTORE.setResultType(Long.class);
    }
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final OrderCloseTransaction transaction;
    private final String startText;
    private final long timeoutMinutes;
    private final int batchSize;
    private LocalDateTime start;
    private long scanCursor;

    public OrderCloseTask(JdbcTemplate jdbc, StringRedisTemplate redis, OrderCloseTransaction transaction,
            @Value("${hmdp.order-close.start-time:}") String startText,
            @Value("${hmdp.order-close.timeout-minutes:15}") long timeoutMinutes,
            @Value("${hmdp.order-close.batch-size:100}") int batchSize) {
        this.jdbc = jdbc; this.redis = redis; this.transaction = transaction;
        this.startText = startText; this.timeoutMinutes = timeoutMinutes; this.batchSize = batchSize;
    }

    @PostConstruct
    public void validate() {
        if (startText.isEmpty() || timeoutMinutes <= 0 || batchSize < 1 || batchSize > 1000)
            throw new IllegalArgumentException("order close requires start-time, positive timeout and batch-size 1..1000");
        start = LocalDateTime.parse(startText);
    }

    @Scheduled(fixedDelayString = "${hmdp.order-close.interval-ms:60000}")
    public void run() {
        try { closeExpired(); } catch (RuntimeException e) { log.error("order close scan failed", e); }
        try { recoverStock(); } catch (RuntimeException e) { log.error("stock recovery scan failed", e); }
    }

    private void closeExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(timeoutMinutes);
        List<Long> ids = jdbc.queryForList("SELECT id FROM tb_voucher_order WHERE status=1 AND create_time>=? AND create_time<=? AND id>? ORDER BY id LIMIT ?",
                Long.class, Timestamp.valueOf(start), Timestamp.valueOf(cutoff), scanCursor, batchSize);
        if (ids.isEmpty()) { scanCursor = 0; return; }
        for (Long id : ids) {
            try { transaction.close(id, start, cutoff); }
            catch (RuntimeException e) { log.error("order close failed id={}", id, e); }
            scanCursor = id;
        }
    }

    private void recoverStock() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT order_id,voucher_id,user_id FROM tb_order_stock_recovery WHERE status=0 AND next_attempt_time<=CURRENT_TIMESTAMP ORDER BY next_attempt_time,order_id LIMIT ?", batchSize);
        for (Map<String, Object> row : rows) {
            long order = ((Number) row.get("order_id")).longValue();
            long voucher = ((Number) row.get("voucher_id")).longValue();
            long user = ((Number) row.get("user_id")).longValue();
            try {
                Long result = redis.execute(RESTORE, Arrays.asList("seckill:stock:" + voucher,
                        "seckill:order:" + voucher, "seckill:recovered:" + order,
                        "seckill:reservation:" + order), Long.toString(user), Long.toString(voucher));
                if (Long.valueOf(1).equals(result))
                    jdbc.update("UPDATE tb_order_stock_recovery SET status=1 WHERE order_id=? AND status=0", order);
                else {
                    log.warn("stock recovery deferred order={} result={}", order, result);
                    defer(order);
                }
            } catch (RuntimeException e) {
                log.error("stock recovery failed order={}", order, e);
                try { defer(order); } catch (RuntimeException ex) { log.error("stock recovery defer failed order={}", order, ex); }
            }
        }
    }

    private void defer(long order) {
        jdbc.update("UPDATE tb_order_stock_recovery SET next_attempt_time=DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 5 MINUTE) WHERE order_id=? AND status=0", order);
    }
}
