package com.billing.billing_service.controller;

import com.billing.billing_service.dto.BalanceResponse;
import com.billing.billing_service.entity.Account;
import com.billing.billing_service.exception.BizException;
import com.billing.billing_service.repository.AccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 余额查询接口控制器。
 *
 * <p>职责边界：只负责「接收请求 → 查账户 → 组装响应」，
 * 不做任何余额计算或业务加工。
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BalanceController {

    private final AccountRepository accountRepository;

    public BalanceController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * 余额查询接口：GET /api/v1/billing/balance/{userId}
     *
     * @param userId 用户 ID
     * @return 用户余额与本月累计消费额
     */
    @GetMapping("/balance/{userId}")
    public BalanceResponse getBalance(@PathVariable Long userId) {
        Account account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> new BizException(HttpStatus.NOT_FOUND, "账户不存在: userId=" + userId));
        return new BalanceResponse(account.getUserId(), account.getBalance(), account.getMonthlyTotal());
    }
}
