package com.billing.billing_service.entity;

import com.billing.billing_service.enums.Currency;
import com.billing.billing_service.enums.TransactionStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 扣费流水实体，对应数据库表 billing_transaction。
 *
 * <p>作用：记录每一笔扣费明细；同时通过 request_id 唯一索引承担幂等职责，
 * 保证同一个 requestId 只能成功扣费一次。
 */
@Entity
@Table(
        name = "billing_transaction",
        uniqueConstraints = @UniqueConstraint(name = "uk_tx_request_id", columnNames = "request_id"),
        indexes = @Index(name = "idx_tx_user_id", columnList = "user_id")
)
public class BillingTransaction {

    /** 主键，自增（由数据库生成） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 全局唯一幂等键（调用方传入） */
    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    /** 用户 ID（逻辑关联 account.user_id） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 原始订单金额（原始币种） */
    @Column(name = "original_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal originalAmount;

    /** 币种：CNY / USD */
    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 8)
    private Currency currency;

    /** 转换为 CNY 后的金额（用于阶梯判定） */
    @Column(name = "converted_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal convertedAmount;

    /** 折扣系数：1.00 / 0.80 / 0.50 */
    @Column(name = "discount_rate", nullable = false, precision = 4, scale = 2)
    private BigDecimal discountRate;

    /** 实际扣费金额（CNY） */
    @Column(name = "final_deduct_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal finalDeductAmount;

    /** 扣款后账户余额（CNY） */
    @Column(name = "balance_after", nullable = false, precision = 15, scale = 2)
    private BigDecimal balanceAfter;

    /** 状态：SUCCESS / INSUFFICIENT_FUNDS */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TransactionStatus status;

    /** 扣款时间（由 JPA 回调自动填充） */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** JPA 规范要求提供无参构造器 */
    public BillingTransaction() {
    }

    /** 新增前自动填充扣款时间 */
    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // ===== getter / setter =====

    public Long getId() {
        return id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public void setOriginalAmount(BigDecimal originalAmount) {
        this.originalAmount = originalAmount;
    }

    public Currency getCurrency() {
        return currency;
    }

    public void setCurrency(Currency currency) {
        this.currency = currency;
    }

    public BigDecimal getConvertedAmount() {
        return convertedAmount;
    }

    public void setConvertedAmount(BigDecimal convertedAmount) {
        this.convertedAmount = convertedAmount;
    }

    public BigDecimal getDiscountRate() {
        return discountRate;
    }

    public void setDiscountRate(BigDecimal discountRate) {
        this.discountRate = discountRate;
    }

    public BigDecimal getFinalDeductAmount() {
        return finalDeductAmount;
    }

    public void setFinalDeductAmount(BigDecimal finalDeductAmount) {
        this.finalDeductAmount = finalDeductAmount;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public void setBalanceAfter(BigDecimal balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
