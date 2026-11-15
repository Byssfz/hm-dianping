-- Apply explicitly before enabling order-close.enabled; never run automatically.
CREATE TABLE IF NOT EXISTS tb_order_stock_recovery (
  order_id BIGINT NOT NULL PRIMARY KEY,
  voucher_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  next_attempt_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_stock_recovery_pending (status, next_attempt_time, order_id)
) ENGINE=InnoDB;

-- For larger tables, add an index on tb_voucher_order(status, create_time, id)
-- after checking existing indexes and deployment requirements.
