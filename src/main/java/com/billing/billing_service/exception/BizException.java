package com.billing.billing_service.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：用于在 Service 层抛出「可预期、可明确映射为 HTTP 状态码」的错误。
 *
 * <p>典型场景：
 * <ul>
 *   <li>JPY 币种不支持 → 400（BAD_REQUEST）；</li>
 *   <li>用户不存在 → 404（NOT_FOUND）。</li>
 * </ul>
 *
 * <p>继承 {@link IllegalArgumentException}，因此对已有「按 IllegalArgumentException
 * 断言」的单元测试保持兼容；同时额外携带 {@link HttpStatus}，供全局异常处理器
 * {@link GlobalExceptionHandler} 精确转换为对应的 HTTP 状态码。
 */
public class BizException extends IllegalArgumentException {

    /** 对应的 HTTP 状态码 */
    private final HttpStatus status;

    public BizException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
