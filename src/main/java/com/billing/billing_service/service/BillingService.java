package com.billing.billing_service.service;

import com.billing.billing_service.entity.Account;
import com.billing.billing_service.entity.BillingTransaction;
import com.billing.billing_service.enums.Currency;
import com.billing.billing_service.enums.DiscountTier;
import com.billing.billing_service.enums.TransactionStatus;
import com.billing.billing_service.exception.BizException;
import com.billing.billing_service.repository.AccountRepository;
import com.billing.billing_service.repository.BillingTransactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Optional;

/**
 * 计费核心服务：串联「幂等检查 → 查账户 → 币种转换 → 折扣计算 → 余额校验
 * → 事务内扣款 → 更新累计消费 → 写流水」的完整计费流程。
 *
 * <p><strong>事务边界设计（数据幂等 → 响应幂等）</strong>：{@link #deduct} 方法本身
 * 不开启事务，而是把「事务核心逻辑」下沉到 {@link #doDeduct}（由
 * {@link TransactionTemplate} 包裹在独立事务中），把「幂等恢复逻辑」留在外层。
 * 当并发相同 requestId 触发唯一索引冲突时，{@link #doDeduct} 所在事务会整体回滚，
 * 异常被外层捕获后，再<strong>重新开启查询</strong>已成功提交的流水返回——
 * 从而避免在已被标记 rollback-only 的事务里继续查询，也不把数据库异常暴露给调用方。
 */
@Service
public class BillingService {

    private final AccountRepository accountRepository;
    private final BillingTransactionRepository transactionRepository;
    private final CurrencyService currencyService;
    private final DiscountService discountService;
    private final TransactionTemplate transactionTemplate;

    public BillingService(AccountRepository accountRepository,
                          BillingTransactionRepository transactionRepository,
                          CurrencyService currencyService,
                          DiscountService discountService,
                          PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.currencyService = currencyService;
        this.discountService = discountService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 执行计费与扣费（外层：幂等恢复 + 事务边界）。
     *
     * @param userId         用户 ID
     * @param originalAmount 原始订单金额（以 currency 计）
     * @param currency       币种
     * @param requestId      全局唯一幂等键
     * @return 本次扣费对应的流水记录（幂等命中时返回历史流水）
     */
    public BillingTransaction deduct(Long userId, BigDecimal originalAmount, Currency currency, String requestId) {
        // ① 快速幂等检查：顺序重复调用的主路径，已处理过直接返回，不再进入事务
        Optional<BillingTransaction> existing = transactionRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            // ② 在独立事务中执行核心扣款（事务内不含任何幂等恢复逻辑）
            return transactionTemplate.execute(status ->
                    doDeduct(userId, originalAmount, currency, requestId));
        } catch (DataIntegrityViolationException e) {
            // ③ 并发相同 requestId 撞唯一索引：doDeduct 的事务已完整回滚，
            //    这里重新查询已成功提交的流水，返回与首次请求一致的业务结果
            return transactionRepository.findByRequestId(requestId)
                    .orElseThrow(() -> new IllegalStateException(
                            "幂等冲突但未找到已提交流水: requestId=" + requestId, e));
        }
    }

    /**
     * 核心扣费逻辑（在 {@link TransactionTemplate} 包裹的独立事务内执行）。
     *
     * <p>此方法只包含「真正可能产生数据变更」的业务步骤；一旦抛出唯一索引冲突，
     * 由外层捕获并完成幂等恢复，本事务不会继续查询或返回。
     */
    private BillingTransaction doDeduct(Long userId, BigDecimal originalAmount, Currency currency, String requestId) {
        // ② 查询账户并加悲观锁，保证同一账户的并发扣款串行执行
        Account account = accountRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new BizException(HttpStatus.NOT_FOUND, "账户不存在: userId=" + userId));

        // ③ 跨月重置：若记录的统计月份不是当前月，则清零本月累计消费
        resetMonthlyTotalIfNewMonth(account);

        // ④ 币种转换：统一折算为 CNY（JPY 会在此抛出异常）
        BigDecimal convertedAmount = currencyService.convertToCny(originalAmount, currency);

        // ⑤ 折扣判定：按「本笔之前」的当月累计消费额判断档位
        DiscountTier tier = discountService.determineTier(account.getMonthlyTotal());

        // ⑥ 计算实际扣费金额
        BigDecimal finalDeductAmount = discountService.calculateDeductAmount(convertedAmount, tier);

        // ⑦ 余额校验：不足则记录失败流水并返回（不扣款、不累计）
        if (account.getBalance().compareTo(finalDeductAmount) < 0) {
            return saveTransaction(requestId, userId, originalAmount, currency,
                    convertedAmount, tier, BigDecimal.ZERO, account.getBalance(),
                    TransactionStatus.INSUFFICIENT_FUNDS);
        }

        // ⑧ 事务内扣款 + 累计本月消费（累计的是折扣前、换算为 CNY 后的原始金额）
        account.setBalance(account.getBalance().subtract(finalDeductAmount));
        account.setMonthlyTotal(account.getMonthlyTotal().add(convertedAmount));
        accountRepository.save(account);

        // ⑨ 写入成功流水
        return saveTransaction(requestId, userId, originalAmount, currency,
                convertedAmount, tier, finalDeductAmount, account.getBalance(),
                TransactionStatus.SUCCESS);
    }

    /** 跨月时清零本月累计消费额，并更新统计月份 */
    private void resetMonthlyTotalIfNewMonth(Account account) {
        String currentMonth = YearMonth.now().toString();
        if (!currentMonth.equals(account.getMonth())) {
            account.setMonthlyTotal(BigDecimal.ZERO);
            account.setMonth(currentMonth);
        }
    }

    /** 构造并保存一条流水记录 */
    private BillingTransaction saveTransaction(String requestId, Long userId, BigDecimal originalAmount,
                                               Currency currency, BigDecimal convertedAmount,
                                               DiscountTier tier, BigDecimal finalDeductAmount,
                                               BigDecimal balanceAfter, TransactionStatus status) {
        BillingTransaction tx = new BillingTransaction();
        tx.setRequestId(requestId);
        tx.setUserId(userId);
        tx.setOriginalAmount(originalAmount);
        tx.setCurrency(currency);
        tx.setConvertedAmount(convertedAmount);
        tx.setDiscountRate(tier.getRate());
        tx.setFinalDeductAmount(finalDeductAmount);
        tx.setBalanceAfter(balanceAfter);
        tx.setStatus(status);
        return transactionRepository.save(tx);
    }
}
