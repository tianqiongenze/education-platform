-- D-132: sp03-payment TiDB DDL. Snowflake PK (NOT AUTO_INCREMENT) to avoid write hotspot.
CREATE TABLE IF NOT EXISTS ep_payment (
    payment_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT 'Snowflake ID',
    order_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    currency VARCHAR(8),
    channel VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    channel_tx_id VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at TIMESTAMP NULL,
    lock_version INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_order_id (order_id),
    INDEX idx_status (status)
) COMMENT '支付记录表';

CREATE TABLE IF NOT EXISTS ep_refund (
    refund_id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT 'Snowflake ID',
    payment_id VARCHAR(64) NOT NULL,
    order_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    reason VARCHAR(256),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    refunded_at TIMESTAMP NULL,
    lock_version INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_payment_id (payment_id),
    INDEX idx_order_id (order_id)
) COMMENT '退款记录表';
