package com.billing.billing_service.repository;

import com.billing.billing_service.entity.BillingTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 扣费流水表数据访问接口。
 *
 * <p>按 requestId 查询流水是实现「幂等」的关键：同一个 requestId
 * 若已存在流水，则说明该请求已处理过，直接返回历史结果即可。
 */
public interface BillingTransactionRepository extends JpaRepository<BillingTransaction, Long> {

    /** 按幂等键 requestId 查询流水 */
    Optional<BillingTransaction> findByRequestId(String requestId);
}
