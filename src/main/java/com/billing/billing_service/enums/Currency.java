package com.billing.billing_service.enums;

import java.math.BigDecimal;

/**
 * 币种枚举。
 *
 * <p>计费服务的基准币种为 CNY（人民币）：
 * <ul>
 *   <li>CNY：基准币种，汇率 1；</li>
 *   <li>USD：美元，按固定汇率 7.2 折算为 CNY；</li>
 *   <li>JPY：日元，不在允许范围内（汇率为 null）。</li>
 * </ul>
 *
 * <p>本类只负责定义「币种及其汇率」，不包含金额转换逻辑
 * （转换逻辑在后续的 CurrencyService 中实现）。
 */
public enum Currency {

    /** 人民币（基准币种） */
    CNY("CNY", new BigDecimal("1")),

    /** 美元（固定汇率 7.2） */
    USD("USD", new BigDecimal("7.2")),

    /** 日元（不支持） */
    JPY("JPY", null);

    /** 币种代码，如 "CNY" */
    private final String code;

    /** 兑 CNY 的汇率；null 表示该币种不受支持 */
    private final BigDecimal rateToCny;

    Currency(String code, BigDecimal rateToCny) {
        this.code = code;
        this.rateToCny = rateToCny;
    }

    public String getCode() {
        return code;
    }

    public BigDecimal getRateToCny() {
        return rateToCny;
    }

    /** 该币种是否受支持（有有效汇率） */
    public boolean isSupported() {
        return rateToCny != null;
    }
}
