package com.billing.billing_service.service;

import com.billing.billing_service.enums.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CurrencyService 单元测试，覆盖币种转换与不支持币种的处理。
 */
class CurrencyServiceTest {

    private final CurrencyService currencyService = new CurrencyService();

    /** 金额比较辅助方法：用 compareTo 比较，避免 BigDecimal.equals 对 scale 的敏感 */
    private void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望金额 " + expected + "，实际金额 " + actual);
    }

    @Test
    @DisplayName("CNY：不转换，金额保持不变")
    void shouldKeepAmountForCny() {
        assertAmount("99.50", currencyService.convertToCny(new BigDecimal("99.50"), Currency.CNY));
    }

    @Test
    @DisplayName("USD：按 7.2 汇率转换为 CNY")
    void shouldConvertUsdWithFixedRate() {
        assertAmount("72.00", currencyService.convertToCny(new BigDecimal("10.00"), Currency.USD));
    }

    @Test
    @DisplayName("USD：转换结果四舍五入到 2 位小数（0.33 × 7.2 = 2.376 → 2.38）")
    void shouldRoundUsdTo2DecimalPlaces() {
        assertAmount("2.38", currencyService.convertToCny(new BigDecimal("0.33"), Currency.USD));
    }

    @Test
    @DisplayName("JPY：不支持，抛出明确异常")
    void shouldThrowForUnsupportedJpy() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> currencyService.convertToCny(new BigDecimal("1000"), Currency.JPY));
        assertTrue(ex.getMessage().contains("JPY"), "异常消息应包含币种代码 JPY");
    }

    @Test
    @DisplayName("金额为 null：抛出异常")
    void shouldThrowForNullAmount() {
        assertThrows(IllegalArgumentException.class,
                () -> currencyService.convertToCny(null, Currency.CNY));
    }

    @Test
    @DisplayName("币种为 null：抛出异常")
    void shouldThrowForNullCurrency() {
        assertThrows(IllegalArgumentException.class,
                () -> currencyService.convertToCny(new BigDecimal("100"), null));
    }
}
