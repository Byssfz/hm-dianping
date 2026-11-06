package com.hmdp.orderclose;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class OrderCloseTransaction {
    private final JdbcTemplate jdbc;

    public OrderCloseTransaction(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public boolean close(long id, LocalDateTime start, LocalDateTime cutoff) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT voucher_id,user_id FROM tb_voucher_order WHERE id=? AND status=1 AND create_time>=? AND create_time<=?",
                id, Timestamp.valueOf(start), Timestamp.valueOf(cutoff));
        if (rows.isEmpty()) return false;
        Map<String, Object> row = rows.get(0);
        long voucherId = ((Number) row.get("voucher_id")).longValue();
        long userId = ((Number) row.get("user_id")).longValue();
        int changed = jdbc.update("UPDATE tb_voucher_order SET status=4 WHERE id=? AND status=1 AND create_time>=? AND create_time<=?",
                id, Timestamp.valueOf(start), Timestamp.valueOf(cutoff));
        if (changed == 0) return false;
        if (jdbc.update("UPDATE tb_seckill_voucher SET stock=stock+1 WHERE voucher_id=?", voucherId) != 1)
            throw new IllegalStateException("missing seckill voucher for order " + id);
        if (jdbc.update("INSERT INTO tb_order_stock_recovery(order_id,voucher_id,user_id,status) VALUES (?,?,?,0)", id, voucherId, userId) != 1)
            throw new IllegalStateException("failed to create recovery for order " + id);
        return true;
    }
}
