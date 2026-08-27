package com.billing.billing_service.service;

import com.billing.billing_service.enums.Currency;
import com.billing.billing_service.exception.BizException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 币种转换服务（纯业务逻辑，无数据库依赖）。
 *
 * <p>职责：将支持的币种金额统一转换为基准币种 CNY。
 * <ul>
 *   <li>CNY：基准币种，不转换；</li>
 *   <li>USD：按固定汇率 7.2 转换为 CNY；</li>
 *   <li>JPY：不受支持，抛出明确异常。</li>
 * </ul>
 */
@Service
public class CurrencyService {

    /** 金额统一保留 2 位小数 */
    private static final int SCALE = 2;

    /**
     * 将指定币种的金额转换为 CNY。
     *
     * @param amount   原始金额（以 currency 计）
     * @param currency 币种
     * @return 转换后的 CNY 金额
     * @throws BizException 当币种不受支持（如 JPY）时
     * @throws IllegalArgumentException 当参数为 null 时
     */
    public BigDecimal convertToCny(BigDecimal amount, Currency currency) {
        if (amount == null) {
            throw new IllegalArgumentException("amount 不能为 null");
        }
        if (currency == null) {
            throw new IllegalArgumentException("currency 不能为 null");
        }
        if (!currency.isSupported()) {
            throw new BizException(HttpStatus.BAD_REQUEST, "币种 " + currency.getCode() + " 不在允许范围内");
        }
        return amount.multiply(currency.getRateToCny())
                .setScale(SCALE, RoundingMode.HALF_UP);
    }
}
