package com.billing.billing_service.service;

import com.billing.billing_service.enums.DiscountTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DiscountService 单元测试，重点覆盖阶梯折扣的边界值（100、500）。
 */
class DiscountServiceTest {

    private final DiscountService discountService = new DiscountService();

    /** 金额比较辅助方法：用 compareTo 比较，避免 BigDecimal.equals 对 scale 的敏感 */
    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望金额 " + expected + "，实际金额 " + actual);
    }

    // ===== determineTier：折扣档位判定 =====

    @Test
    @DisplayName("累计 < 100 元：返回原价档")
    void shouldReturnOriginalWhenBelow100() {
        assertEquals(DiscountTier.ORIGINAL, discountService.determineTier(new BigDecimal("0")));
        assertEquals(DiscountTier.ORIGINAL, discountService.determineTier(new BigDecimal("99.99")));
    }

    @Test
    @DisplayName("累计 = 100 元：返回 8 折档（下边界）")
    void shouldReturnEightyPercentWhenExactly100() {
        assertEquals(DiscountTier.EIGHTY_PERCENT, discountService.determineTier(new BigDecimal("100.00")));
    }

    @Test
    @DisplayName("100 < 累计 < 500 元：返回 8 折档")
    void shouldReturnEightyPercentBetween100And500() {
        assertEquals(DiscountTier.EIGHTY_PERCENT, discountService.determineTier(new BigDecimal("499.99")));
    }

    @Test
    @DisplayName("累计 = 500 元：返回 5 折档（下边界）")
    void shouldReturnFiftyPercentWhenExactly500() {
        assertEquals(DiscountTier.FIFTY_PERCENT, discountService.determineTier(new BigDecimal("500.00")));
    }

    @Test
    @DisplayName("累计 > 500 元：返回 5 折档")
    void shouldReturnFiftyPercentAbove500() {
        assertEquals(DiscountTier.FIFTY_PERCENT, discountService.determineTier(new BigDecimal("500.01")));
        assertEquals(DiscountTier.FIFTY_PERCENT, discountService.determineTier(new BigDecimal("1000.00")));
    }

    @Test
    @DisplayName("累计消费额为 null：抛出异常")
    void shouldThrowWhenMonthlyTotalIsNull() {
        assertThrows(IllegalArgumentException.class, () -> discountService.determineTier(null));
    }

    // ===== calculateDeductAmount：扣费金额计算 =====

    @Test
    @DisplayName("原价档：扣费金额 = 原始金额")
    void shouldKeepOriginalAmountForOriginalTier() {
        assertAmount("99.50", discountService.calculateDeductAmount(new BigDecimal("99.50"), DiscountTier.ORIGINAL));
    }

    @Test
    @DisplayName("8 折档：扣费金额 = 原始金额 × 0.80")
    void shouldApplyEightyPercent() {
        assertAmount("80.00", discountService.calculateDeductAmount(new BigDecimal("100.00"), DiscountTier.EIGHTY_PERCENT));
    }

    @Test
    @DisplayName("5 折档：扣费金额 = 原始金额 × 0.50")
    void shouldApplyFiftyPercent() {
        assertAmount("50.00", discountService.calculateDeductAmount(new BigDecimal("100.00"), DiscountTier.FIFTY_PERCENT));
    }

    @Test
    @DisplayName("金额四舍五入到 2 位小数（99.99 × 0.80 = 79.992 → 79.99）")
    void shouldRoundTo2DecimalPlaces() {
        assertAmount("79.99", discountService.calculateDeductAmount(new BigDecimal("99.99"), DiscountTier.EIGHTY_PERCENT));
    }

    @Test
    @DisplayName("扣费金额参数为 null：抛出异常")
    void shouldThrowWhenAmountOrTierIsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> discountService.calculateDeductAmount(null, DiscountTier.ORIGINAL));
        assertThrows(IllegalArgumentException.class,
                () -> discountService.calculateDeductAmount(new BigDecimal("100"), null));
    }
}
