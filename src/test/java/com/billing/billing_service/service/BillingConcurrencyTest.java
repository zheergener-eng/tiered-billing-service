package com.billing.billing_service.service;

import com.billing.billing_service.entity.Account;
import com.billing.billing_service.entity.BillingTransaction;
import com.billing.billing_service.enums.Currency;
import com.billing.billing_service.enums.TransactionStatus;
import com.billing.billing_service.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发一致性与幂等测试（真实 MySQL + 多线程 + 真实事务）。
 *
 * <p>与普通集成测试不同，本类<strong>不加 {@code @Transactional}</strong>：
 * 每个工作线程调用 {@link BillingService#deduct} 时各自开启独立事务，
 * 悲观锁（SELECT ... FOR UPDATE）才能在真实并发下生效。
 *
 * <p>测试数据使用独立 userId 段（5001~5004），{@link BeforeEach} 阶段清理，
 * 避免测试残留污染。
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BillingConcurrencyTest {

    @Autowired
    private BillingService billingService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM billing_transaction WHERE user_id BETWEEN 5001 AND 5004");
        jdbcTemplate.update("DELETE FROM account WHERE user_id BETWEEN 5001 AND 5004");
    }

    private Account createAccount(Long userId, String balance, String monthlyTotal) {
        Account account = new Account();
        account.setUserId(userId);
        account.setBalance(new BigDecimal(balance));
        account.setMonthlyTotal(new BigDecimal(monthlyTotal));
        account.setMonth(YearMonth.now().toString());
        return accountRepository.saveAndFlush(account);
    }

    private Account reload(Long userId) {
        return accountRepository.findByUserId(userId).orElseThrow();
    }

    private long countTransactions(Long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_transaction WHERE user_id = ?", Long.class, userId);
        return n == null ? 0 : n;
    }

    /** 让 threads 个线程「同时」执行 task（双门闩同步启动），返回每个线程的结果 */
    private <T> List<T> runConcurrently(int threads, IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return task.apply(idx);
            }));
        }
        ready.await();
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        return results;
    }

    private void printSummary(String title, int threads, int success, int failed, Account account) {
        System.out.println("===== " + title + " =====");
        System.out.println("并发线程数 = " + threads);
        System.out.println("成功次数 = " + success);
        System.out.println("失败次数 = " + failed);
        System.out.println("最终余额 = " + account.getBalance());
        System.out.println("最终 monthlyTotal = " + account.getMonthlyTotal());
        System.out.println("流水条数 = " + countTransactions(account.getUserId()));
        System.out.println("================================");
    }

    @Test
    @DisplayName("同一账户 10 线程并发扣款（不同 requestId）")
    void concurrentDeductionSameAccount() throws Exception {
        createAccount(5001L, "1000.00", "500.00");
        int threads = 10;
        BigDecimal each = new BigDecimal("10"); // 5 折档：每笔实扣 5

        List<BillingTransaction> txs = runConcurrently(threads, i ->
                billingService.deduct(5001L, each, Currency.CNY, "conc-5001-" + i));

        long success = txs.stream().filter(t -> t.getStatus() == TransactionStatus.SUCCESS).count();
        Account account = reload(5001L);

        assertTrue(account.getBalance().compareTo(BigDecimal.ZERO) >= 0, "余额不应为负");
        assertEquals(threads, success, "10 笔应全部成功");
        assertEquals(0, new BigDecimal("950.00").compareTo(account.getBalance()), "余额应为 1000 - 10×5 = 950");
        assertEquals(0, new BigDecimal("600.00").compareTo(account.getMonthlyTotal()), "monthlyTotal 应为 500 + 10×10 = 600");
        assertEquals(threads, countTransactions(5001L), "应有 10 条流水");

        printSummary("同一账户并发扣款", threads, (int) success, 0, account);
    }

    @Test
    @DisplayName("同一 requestId 10 线程并发：10 个调用都返回一致结果且不抛异常")
    void concurrentSameRequestId() throws Exception {
        createAccount(5002L, "1000.00", "0.00");
        String requestId = "conc-idempotent-5002";
        int threads = 10;
        BigDecimal each = new BigDecimal("100");

        // 双门闩并发执行；任一线程抛出异常都会通过 f.get() 抛出 ExecutionException，
        // 直接使本测试失败，从而严格保证「10 个调用都不抛数据库异常」。
        List<BillingTransaction> txs = runConcurrently(threads, i ->
                billingService.deduct(5002L, each, Currency.CNY, requestId));

        Account account = reload(5002L);

        // 数据幂等：数据库只有 1 条流水，账户只扣款 1 次（1000 - 100 = 900）
        assertEquals(1, countTransactions(5002L), "只应有一条流水");
        assertEquals(0, new BigDecimal("900.00").compareTo(account.getBalance()), "余额应只扣一次");

        // 响应幂等：10 个调用返回的核心字段必须完全一致
        BillingTransaction first = txs.get(0);
        for (BillingTransaction tx : txs) {
            assertEquals(first.getId(), tx.getId(), "所有调用应返回同一笔流水");
            assertEquals(first.getStatus(), tx.getStatus(), "status 应一致");
            assertEquals(0, first.getFinalDeductAmount().compareTo(tx.getFinalDeductAmount()),
                    "finalDeductAmount 应一致");
            assertEquals(0, first.getBalanceAfter().compareTo(tx.getBalanceAfter()),
                    "currentBalance 应一致");
        }

        printSummary("同一 requestId 并发（响应幂等）", threads, threads, 0, account);
    }

    @Test
    @DisplayName("不同 requestId 20 线程并发：悲观锁防止 lost update")
    void concurrentDeductionNoLostUpdate() throws Exception {
        createAccount(5003L, "1000.00", "500.00");
        int threads = 20;
        BigDecimal each = new BigDecimal("10"); // 5 折档：每笔实扣 5

        List<BillingTransaction> txs = runConcurrently(threads, i ->
                billingService.deduct(5003L, each, Currency.CNY, "conc-5003-" + i));

        long success = txs.stream().filter(t -> t.getStatus() == TransactionStatus.SUCCESS).count();
        Account account = reload(5003L);

        assertEquals(threads, success, "20 笔应全部成功");
        assertEquals(0, new BigDecimal("900.00").compareTo(account.getBalance()), "余额应为 1000 - 20×5 = 900（无 lost update）");
        assertEquals(0, new BigDecimal("700.00").compareTo(account.getMonthlyTotal()), "monthlyTotal 应为 500 + 20×10 = 700（无 lost update）");
        assertEquals(threads, countTransactions(5003L));

        printSummary("不同 requestId 并发（防 lost update）", threads, (int) success, 0, account);
    }

    @Test
    @DisplayName("余额临界：30 线程并发扣 50（余额仅 100），部分成功部分余额不足")
    void concurrentInsufficientFundsBoundary() throws Exception {
        createAccount(5004L, "100.00", "0.00");
        int threads = 30;
        BigDecimal each = new BigDecimal("50");

        List<BillingTransaction> txs = runConcurrently(threads, i ->
                billingService.deduct(5004L, each, Currency.CNY, "conc-5004-" + i));

        long success = txs.stream().filter(t -> t.getStatus() == TransactionStatus.SUCCESS).count();
        long insufficient = txs.stream().filter(t -> t.getStatus() == TransactionStatus.INSUFFICIENT_FUNDS).count();
        Account account = reload(5004L);

        // 100 / 50 = 2 次成功，其余 28 次余额不足
        assertEquals(2, success, "应恰好成功 2 次");
        assertEquals(threads - 2, insufficient, "应有 28 次余额不足");
        assertTrue(account.getBalance().compareTo(BigDecimal.ZERO) >= 0, "余额不得为负");
        assertEquals(0, new BigDecimal("0.00").compareTo(account.getBalance()), "最终余额应为 0");
        // 仅成功交易累计 monthlyTotal：2 × 50 = 100
        assertEquals(0, new BigDecimal("100.00").compareTo(account.getMonthlyTotal()));
        assertEquals(threads, countTransactions(5004L), "30 笔请求都应有流水（含余额不足）");

        printSummary("余额临界并发", threads, (int) success, (int) insufficient, account);
    }
}
