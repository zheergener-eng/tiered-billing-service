package com.billing.billing_service.dto;

import java.math.BigDecimal;

/**
 * 余额查询接口（GET /api/v1/billing/balance/{userId}）响应体。
 */
public class BalanceResponse {

    /** 用户 ID */
    private final Long userId;

    /** 当前可用余额（CNY） */
    private final BigDecimal balance;

    /** 本月已累计消费额（CNY，用于阶梯折扣判定） */
    private final BigDecimal monthlyTotal;

    public BalanceResponse(Long userId, BigDecimal balance, BigDecimal monthlyTotal) {
        this.userId = userId;
        this.balance = balance;
        this.monthlyTotal = monthlyTotal;
    }

    public Long getUserId() {
        return userId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public BigDecimal getMonthlyTotal() {
        return monthlyTotal;
    }
}
