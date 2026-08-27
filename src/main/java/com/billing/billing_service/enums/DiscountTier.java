package com.billing.billing_service.enums;

import java.math.BigDecimal;

/**
 * 阶梯折扣档位。
 *
 * <p>按「当月已累计消费额」判断所处档位，累计越多折扣越大：
 * <ul>
 *   <li>累计 &lt; 100 元：按原价扣费（折扣系数 1.00）；</li>
 *   <li>100 ≤ 累计 &lt; 500 元：按 8 折扣费（折扣系数 0.80）；</li>
 *   <li>累计 ≥ 500 元：按 5 折扣费（折扣系数 0.50）。</li>
 * </ul>
 *
 * <p>本类只负责定义「档位及折扣系数」，不包含「如何判定档位」的逻辑
 * （判定逻辑在后续的 DiscountService 中实现）。
 */
public enum DiscountTier {

    /** 累计 &lt; 100：原价 */
    ORIGINAL(new BigDecimal("1.00")),

    /** 100 ≤ 累计 &lt; 500：8 折 */
    EIGHTY_PERCENT(new BigDecimal("0.80")),

    /** 累计 ≥ 500：5 折 */
    FIFTY_PERCENT(new BigDecimal("0.50"));

    /** 折扣系数：实际扣费 = 原始金额(CNY) × 折扣系数 */
    private final BigDecimal rate;

    DiscountTier(BigDecimal rate) {
        this.rate = rate;
    }

    public BigDecimal getRate() {
        return rate;
    }
}
