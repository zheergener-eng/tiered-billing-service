-- H2 测试数据库建表脚本（去掉 MySQL 不兼容的语法）

-- 账户表
CREATE TABLE account (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT        NOT NULL UNIQUE,
    balance       DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    monthly_total DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    month         VARCHAR(7)    NOT NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 扣费流水表（兼幂等记录）
CREATE TABLE billing_transaction (
    id                  BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id          VARCHAR(64)   NOT NULL UNIQUE,
    user_id             BIGINT        NOT NULL,
    original_amount     DECIMAL(15,2) NOT NULL,
    currency            VARCHAR(8)    NOT NULL,
    converted_amount    DECIMAL(15,2) NOT NULL,
    discount_rate       DECIMAL(4,2)  NOT NULL,
    final_deduct_amount DECIMAL(15,2) NOT NULL,
    balance_after       DECIMAL(15,2) NOT NULL,
    status              VARCHAR(32)   NOT NULL,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 为提高查询效率创建索引
CREATE INDEX idx_tx_user_id ON billing_transaction(user_id);


