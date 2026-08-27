-- =====================================================================
-- 阶梯折扣计费服务 · 数据库建表脚本
-- 数据库：MySQL 8.x
-- 引擎：InnoDB（支持事务 + 行锁，保证并发一致性）
-- 字符集：utf8mb4
-- 说明：本脚本创建计费服务所需的两张表，可重复执行（IF NOT EXISTS）
-- =====================================================================

-- 1. 创建数据库（按需执行；数据库名可自行修改）
CREATE DATABASE IF NOT EXISTS billing_db
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE billing_db;

-- =====================================================================
-- 表1：account（账户表）
-- 作用：存储每个用户的「当前可用余额」和「本月已累计消费额」。
--       是阶梯折扣判定、余额扣减的直接数据来源。
-- =====================================================================
CREATE TABLE IF NOT EXISTS account (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    user_id       BIGINT        NOT NULL COMMENT '用户 ID（业务侧全局唯一）',
    balance       DECIMAL(15,2) NOT NULL DEFAULT 0.00 COMMENT '当前可用余额（CNY）',
    monthly_total DECIMAL(15,2) NOT NULL DEFAULT 0.00 COMMENT '本月已累计消费额（CNY，用于阶梯折扣判定）',
    month         VARCHAR(7)    NOT NULL COMMENT '统计月份，格式 YYYY-MM，跨月时用于重置 monthly_total',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '账户表：用户余额与本月累计消费额';

-- =====================================================================
-- 表2：billing_transaction（扣费流水表，兼作幂等记录）
-- 作用：记录每一笔扣费明细；同时通过 request_id 唯一索引承担幂等职责，
--       保证同一个 requestId 只能成功扣费一次。
-- =====================================================================
CREATE TABLE IF NOT EXISTS billing_transaction (
    id                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    request_id          VARCHAR(64)   NOT NULL COMMENT '全局唯一幂等键（调用方传入）',
    user_id             BIGINT        NOT NULL COMMENT '用户 ID（逻辑关联 account.user_id）',
    original_amount     DECIMAL(15,2) NOT NULL COMMENT '原始订单金额（原始币种）',
    currency            VARCHAR(8)    NOT NULL COMMENT '币种：CNY / USD',
    converted_amount    DECIMAL(15,2) NOT NULL COMMENT '转换为 CNY 后的金额（用于阶梯判定）',
    discount_rate       DECIMAL(4,2)  NOT NULL COMMENT '折扣系数：1.00 / 0.80 / 0.50',
    final_deduct_amount DECIMAL(15,2) NOT NULL COMMENT '实际扣费金额（CNY）',
    balance_after       DECIMAL(15,2) NOT NULL COMMENT '扣款后账户余额（CNY）',
    status              VARCHAR(32)   NOT NULL COMMENT '状态：SUCCESS / INSUFFICIENT_FUNDS',
    created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '扣款时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tx_request_id (request_id),
    KEY idx_tx_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '扣费流水表（兼幂等记录）';

-- =====================================================================
-- 测试账户数据（便于后续接口测试）
-- 覆盖三个折扣档位（按当月已累计消费额 monthly_total 判断）：
--   1001：monthly_total = 0    → 原价档（累计 < 100）
--   1002：monthly_total = 150  → 8 折档（100 ≤ 累计 < 500）
--   1003：monthly_total = 600  → 5 折档（累计 ≥ 500）
-- 说明：month 填当前月份；跨月后首次扣款会自动清零并更新 month。
--       如需重置测试数据，先 DELETE FROM account 再重新执行本脚本。
-- =====================================================================
INSERT IGNORE INTO account (user_id, balance, monthly_total, month) VALUES
    (1001, 1000.00,   0.00, '2026-08'),
    (1002, 1000.00, 150.00, '2026-08'),
    (1003, 1000.00, 600.00, '2026-08');
