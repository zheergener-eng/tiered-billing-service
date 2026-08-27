package com.billing.billing_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 账户实体，对应数据库表 account。
 *
 * <p>作用：存储每个用户的「当前可用余额」和「本月已累计消费额」，
 * 是阶梯折扣判定与余额扣减的直接数据来源。
 */
@Entity
@Table(
        name = "account",
        uniqueConstraints = @UniqueConstraint(name = "uk_account_user_id", columnNames = "user_id")
)
public class Account {

    /** 主键，自增（由数据库生成） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 ID（业务侧全局唯一） */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 当前可用余额（统一按 CNY 记账） */
    @Column(name = "balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal balance;

    /** 本月已累计消费额（CNY，用于阶梯折扣判定） */
    @Column(name = "monthly_total", nullable = false, precision = 15, scale = 2)
    private BigDecimal monthlyTotal;

    /** 统计月份，格式 YYYY-MM，跨月时用于重置 monthly_total */
    @Column(name = "month", nullable = false, length = 7)
    private String month;

    /** 创建时间（由 JPA 回调自动填充） */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间（由 JPA 回调自动填充） */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA 规范要求提供无参构造器 */
    public Account() {
    }

    /** 新增前自动填充时间戳 */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 更新前自动刷新更新时间 */
    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ===== getter / setter =====

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public BigDecimal getMonthlyTotal() {
        return monthlyTotal;
    }

    public void setMonthlyTotal(BigDecimal monthlyTotal) {
        this.monthlyTotal = monthlyTotal;
    }

    public String getMonth() {
        return month;
    }

    public void setMonth(String month) {
        this.month = month;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
