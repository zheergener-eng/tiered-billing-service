package com.billing.billing_service.dto;

import com.billing.billing_service.enums.Currency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 扣款接口（POST /api/v1/billing/deduct）请求体。
 *
 * <p>字段严格对应任务书接口定义，并使用 Jakarta Validation 注解在进入
 * Controller 前完成参数校验，非法请求直接返回 400，不进入业务层。
 */
public class DeductRequest {

    /** 用户 ID */
    @NotNull(message = "userId 不能为空")
    private Long userId;

    /** 原始订单金额（以 currency 计），必须大于 0 */
    @NotNull(message = "originalAmount 不能为空")
    @DecimalMin(value = "0.01", message = "originalAmount 必须大于 0")
    private BigDecimal originalAmount;

    /** 币种：CNY / USD / JPY */
    @NotNull(message = "currency 不能为空")
    private Currency currency;

    /** 全局唯一幂等键 */
    @NotBlank(message = "requestId 不能为空")
    private String requestId;

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

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}
