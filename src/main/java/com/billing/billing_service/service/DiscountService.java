package com.billing.billing_service.service;

import com.billing.billing_service.enums.DiscountTier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 阶梯折扣计算服务（纯业务逻辑，无数据库依赖）。
 *
 * <p>职责：
 * <ol>
 *   <li>根据「当月已累计消费额」判断折扣档位；</li>
 *   <li>根据折扣档位计算实际扣费金额。</li>
 * </ol>
 */
@Service
public class DiscountService {

    /** 第二档（8 折）门槛：累计 ≥ 100 元 */
    private static final BigDecimal TIER_2_THRESHOLD = new BigDecimal("100");

    /** 第三档（5 折）门槛：累计 ≥ 500 元 */
    private static final BigDecimal TIER_3_THRESHOLD = new BigDecimal("500");

    /** 金额统一保留 2 位小数 */
    private static final int SCALE = 2;

    /**
     * 根据当月已累计消费额判断折扣档位。
     *
     * <p>规则：
     * <ul>
     *   <li>累计 &lt; 100：原价；</li>
     *   <li>100 ≤ 累计 &lt; 500：8 折；</li>
     *   <li>累计 ≥ 500：5 折。</li>
     * </ul>
     *
     * @param monthlyTotal 当月已累计消费额（CNY，不含本笔）
     * @return 对应的折扣档位
     */
    public DiscountTier determineTier(BigDecimal monthlyTotal) {
        if (monthlyTotal == null) {
            throw new IllegalArgumentException("monthlyTotal 不能为 null");
        }
        if (monthlyTotal.compareTo(TIER_2_THRESHOLD) < 0) {
            return DiscountTier.ORIGINAL;        // < 100：原价
        }
        if (monthlyTotal.compareTo(TIER_3_THRESHOLD) < 0) {
            return DiscountTier.EIGHTY_PERCENT;  // 100 ≤ x < 500：8 折
        }
        return DiscountTier.FIFTY_PERCENT;       // ≥ 500：5 折
    }

    /**
     * 根据折扣档位计算实际扣费金额（CNY）。
     *
     * @param originalAmountCny 原始金额（已转换为 CNY）
     * @param tier              折扣档位
     * @return 实际扣费金额 = 原始金额 × 折扣系数（四舍五入到 2 位小数）
     */
    public BigDecimal calculateDeductAmount(BigDecimal originalAmountCny, DiscountTier tier) {
        if (originalAmountCny == null) {
            throw new IllegalArgumentException("originalAmountCny 不能为 null");
        }
        if (tier == null) {
            throw new IllegalArgumentException("tier 不能为 null");
        }
        return originalAmountCny.multiply(tier.getRate())
                .setScale(SCALE, RoundingMode.HALF_UP);
    }
}
