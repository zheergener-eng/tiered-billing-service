package com.billing.billing_service.enums;

/**
 * 扣费流水状态枚举。
 *
 * <p>对应任务书接口 A 响应体中的 status 字段：
 * <ul>
 *   <li>SUCCESS：扣费成功；</li>
 *   <li>INSUFFICIENT_FUNDS：余额不足。</li>
 * </ul>
 */
public enum TransactionStatus {

    /** 扣费成功 */
    SUCCESS,

    /** 余额不足 */
    INSUFFICIENT_FUNDS
}
