package com.billing.billing_service.controller;

import com.billing.billing_service.dto.DeductRequest;
import com.billing.billing_service.dto.DeductResponse;
import com.billing.billing_service.entity.BillingTransaction;
import com.billing.billing_service.service.BillingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 扣款接口控制器。
 *
 * <p>职责边界：只负责「接收请求 → 调 Service → 组装响应」，
 * 不包含折扣计算、币种转换、余额扣减等任何业务逻辑（这些都在 {@link BillingService} 中）。
 */
@RestController
@RequestMapping("/api/v1/billing")
public class DeductController {

    private final BillingService billingService;

    public DeductController(BillingService billingService) {
        this.billingService = billingService;
    }

    /**
     * 扣款接口：POST /api/v1/billing/deduct
     *
     * @param request 扣款请求体（经 {@link Valid} 校验）
     * @return 扣款结果（状态、实际扣费金额、扣款后余额、描述）
     */
    @PostMapping("/deduct")
    public DeductResponse deduct(@Valid @RequestBody DeductRequest request) {
        BillingTransaction tx = billingService.deduct(
                request.getUserId(),
                request.getOriginalAmount(),
                request.getCurrency(),
                request.getRequestId());
        return DeductResponse.from(tx);
    }
}
