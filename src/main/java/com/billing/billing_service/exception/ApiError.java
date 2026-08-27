package com.billing.billing_service.exception;

/**
 * 统一错误响应体。
 *
 * <p>作用：向客户端返回结构化、可读的错误信息（状态码 + 描述），
 * 避免直接暴露 Java 异常堆栈或内部实现细节。
 */
public class ApiError {

    /** HTTP 状态码，如 400、404 */
    private final int code;

    /** 面向调用方的错误描述 */
    private final String message;

    public ApiError(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
