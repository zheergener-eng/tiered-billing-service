package com.billing.billing_service.service;

import com.billing.billing_service.entity.Account;
import com.billing.billing_service.entity.BillingTransaction;
import com.billing.billing_service.enums.Currency;
import com.billing.billing_service.enums.TransactionStatus;
import com.billing.billing_service.repository.AccountRepository;
import com.billing.billing_service.repository.BillingTransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BillingService 集成测试（连接真实 MySQL）。
 *
 * <p>类级别 {@link Transactional}：每个测试方法在独立事务中执行、结束后自动回滚，
 * 保证测试不污染数据库、测试之间相互隔离。
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BillingServiceIntegrationTest {

    @Autowired
    private BillingService billingService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private BillingTransactionRepository transactionRepository;

    /** 金额比较：用 compareTo 避免 BigDecimal.equals 对 scale 的敏感 */
    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望金额 " + expected + "，实际金额 " + actual);
    }

    /** 在当前测试事务内创建一个测试账户（测试结束自动回滚） */
    private Account createAccount(Long userId, String balance, String monthlyTotal) {
        Account account = new Account();
        account.setUserId(userId);
        account.setBalance(new BigDecimal(balance));
        account.setMonthlyTotal(new BigDecimal(monthlyTotal));
        account.setMonth(YearMonth.now().toString());
        return accountRepository.saveAndFlush(account);
    }

    @Test
    @DisplayName("正常 CNY 扣款：累计 0 处于原价档，扣 100 元")
    void shouldDeductCnyNormally() {
        createAccount(2001L, "1000.00", "0.00");

        BillingTransaction tx = billingService.deduct(2001L, new BigDecimal("100"), Currency.CNY, "req-cny-2001");

        assertEquals(TransactionStatus.SUCCESS, tx.getStatus());
        assertAmount("100.00", tx.getFinalDeductAmount());
        assertAmount("900.00", tx.getBalanceAfter());
    }

    @Test
    @DisplayName("USD 换算后扣款：10 USD × 7.2 = 72 CNY")
    void shouldDeductUsdAfterConversion() {
        createAccount(2002L, "1000.00", "0.00");

        BillingTransaction tx = billingService.deduct(2002L, new BigDecimal("10"), Currency.USD, "req-usd-2002");

        assertEquals(TransactionStatus.SUCCESS, tx.getStatus());
        assertAmount("72.00", tx.getConvertedAmount());
        assertAmount("72.00", tx.getFinalDeductAmount());
        assertAmount("928.00", tx.getBalanceAfter());
    }

    @Test
    @DisplayName("余额不足：返回 INSUFFICIENT_FUNDS，余额不变")
    void shouldReturnInsufficientFunds() {
        createAccount(2003L, "50.00", "0.00");

        BillingTransaction tx = billingService.deduct(2003L, new BigDecimal("100"), Currency.CNY, "req-insufficient-2003");

        assertEquals(TransactionStatus.INSUFFICIENT_FUNDS, tx.getStatus());
        assertAmount("0.00", tx.getFinalDeductAmount());
        assertAmount("50.00", tx.getBalanceAfter());
    }

    @Test
    @DisplayName("同一 requestId 重复调用只扣款一次")
    void shouldDeductOnlyOnceForSameRequestId() {
        createAccount(2004L, "1000.00", "0.00");
        String requestId = "req-idempotent-2004";

        BillingTransaction first = billingService.deduct(2004L, new BigDecimal("100"), Currency.CNY, requestId);
        BillingTransaction second = billingService.deduct(2004L, new BigDecimal("100"), Currency.CNY, requestId);

        // 两次返回同一条流水（幂等命中），状态一致
        assertEquals(first.getId(), second.getId());
        assertEquals(TransactionStatus.SUCCESS, second.getStatus());

        // 余额只扣了一次：1000 → 900（而非 800）
        Account after = accountRepository.findByUserId(2004L).orElseThrow();
        assertAmount("900.00", after.getBalance());

        // 数据库中该 requestId 只有一条流水
        BillingTransaction persisted = transactionRepository.findByRequestId(requestId).orElseThrow();
        assertEquals(first.getId(), persisted.getId());
    }

    @Test
    @DisplayName("100 元折扣边界：累计 99.99 原价，累计 100.00 打 8 折")
    void shouldApplyBoundaryAt100() {
        createAccount(2005L, "1000.00", "99.99");
        BillingTransaction below = billingService.deduct(2005L, new BigDecimal("100"), Currency.CNY, "req-boundary-100-below");
        assertEquals(TransactionStatus.SUCCESS, below.getStatus());
        assertAmount("100.00", below.getFinalDeductAmount()); // 原价

        createAccount(2006L, "1000.00", "100.00");
        BillingTransaction at = billingService.deduct(2006L, new BigDecimal("100"), Currency.CNY, "req-boundary-100-at");
        assertEquals(TransactionStatus.SUCCESS, at.getStatus());
        assertAmount("80.00", at.getFinalDeductAmount()); // 8 折
    }

    @Test
    @DisplayName("500 元折扣边界：累计 499.99 打 8 折，累计 500.00 打 5 折")
    void shouldApplyBoundaryAt500() {
        createAccount(2007L, "1000.00", "499.99");
        BillingTransaction below = billingService.deduct(2007L, new BigDecimal("100"), Currency.CNY, "req-boundary-500-below");
        assertEquals(TransactionStatus.SUCCESS, below.getStatus());
        assertAmount("80.00", below.getFinalDeductAmount()); // 8 折

        createAccount(2008L, "1000.00", "500.00");
        BillingTransaction at = billingService.deduct(2008L, new BigDecimal("100"), Currency.CNY, "req-boundary-500-at");
        assertEquals(TransactionStatus.SUCCESS, at.getStatus());
        assertAmount("50.00", at.getFinalDeductAmount()); // 5 折
    }
}
