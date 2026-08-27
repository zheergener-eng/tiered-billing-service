package com.billing.billing_service.dto;

import com.billing.billing_service.entity.BillingTransaction;
import com.billing.billing_service.enums.TransactionStatus;

import java.math.BigDecimal;

/**
 * 扣款接口（POST /api/v1/billing/deduct）响应体。
 *
 * <p>由扣费流水 {@link BillingTransaction} 组装而来，不包含任何业务计算逻辑。
 */
public class DeductResponse {

    /** 扣款状态：SUCCESS / INSUFFICIENT_FUNDS */
    private TransactionStatus status;

    /** 实际扣费金额（CNY） */
    private BigDecimal finalDeductAmount;

    /** 扣款后账户余额（CNY） */
    private BigDecimal currentBalance;

    /** 结果描述 */
    private String message;

    /** 从扣费流水组装响应对象 */
    public static DeductResponse from(BillingTransaction tx) {
        DeductResponse response = new DeductResponse();
        response.status = tx.getStatus();
        response.finalDeductAmount = tx.getFinalDeductAmount();
        response.currentBalance = tx.getBalanceAfter();
        response.message = (tx.getStatus() == TransactionStatus.SUCCESS) ? "扣费成功" : "余额不足";
        return response;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public BigDecimal getFinalDeductAmount() {
        return finalDeductAmount;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public String getMessage() {
        return message;
    }
}
