package com.billing.billing_service.repository;

import com.billing.billing_service.entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 账户表数据访问接口。
 *
 * <p>继承 {@link JpaRepository} 即可获得常用的增删改查能力；
 * 自定义查询通过方法命名或 {@link Query} 注解声明。
 */
public interface AccountRepository extends JpaRepository<Account, Long> {

    /** 按用户 ID 查询账户（无锁，用于普通查询） */
    Optional<Account> findByUserId(Long userId);

    /**
     * 按用户 ID 查询账户并加「悲观写锁」（SELECT ... FOR UPDATE）。
     *
     * <p>必须在事务内调用；锁住账户行后，同一账户的并发扣款会排队执行，
     * 从而保证余额扣减与累计消费统计的原子性（余额永不为负）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.userId = :userId")
    Optional<Account> findByUserIdForUpdate(@Param("userId") Long userId);
}
