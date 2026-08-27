package com.billing.billing_service.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>职责：把 Controller 层抛出的各类异常统一转换为结构化的 JSON 错误响应，
 * 保证客户端拿到的始终是清晰可读的信息，而非 Java 异常堆栈。
 *
 * <p>处理优先级：Spring 会选择与异常类型最匹配的 {@link ExceptionHandler}，
 * 因此 {@link BizException} 会优先命中 {@link #handleBizException}，
 * 而不会落入兜底的 {@link #handleIllegalArgument}。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常（JPY 不支持、账户不存在等）：按异常携带的状态码返回 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiError> handleBizException(BizException ex) {
        HttpStatus status = ex.getStatus();
        return ResponseEntity.status(status)
                .body(new ApiError(status.value(), ex.getMessage()));
    }

    /** 请求体参数校验失败（@Valid）：汇总各字段错误信息 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(new ApiError(HttpStatus.BAD_REQUEST.value(), message));
    }

    /** 请求体 JSON 无法解析或含非法枚举值（如 currency 传 "ABC"） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError(HttpStatus.BAD_REQUEST.value(), "请求体格式错误或包含非法值"));
    }

    /** 兜底：其他 IllegalArgumentException（如参数为 null） */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
    }

    /** 最终兜底：未预期异常，只返回通用信息，不暴露内部细节 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务器内部错误"));
    }
}
