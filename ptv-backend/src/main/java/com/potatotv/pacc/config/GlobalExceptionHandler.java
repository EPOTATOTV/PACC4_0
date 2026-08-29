package com.potatotv.pacc.config;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;

/**
 * 全局异常处理（统一脱敏 + 不泄露内部细节）：
 * <ul>
 *   <li>客户端输入类异常：400 直回，不回显堆栈；</li>
 *   <li>服务器异常：500 通用兜底，详细堆栈/根因仅写入服务端日志。</li>
 * </ul>
 * 错误信息禁止包含数据库结构、堆栈、SQL 或敏感字段名映射关系。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class})
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", safe(e.getMessage(), "非法请求参数")));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            MismatchedInputException.class})
    public ResponseEntity<?> validation(Exception e) {
        String msg = "请求参数不合法";
        if (e instanceof BindException be && be.getBindingResult() != null) {
            FieldError fe = be.getBindingResult().getFieldError();
            if (fe != null) {
                msg = "字段不合法: " + fe.getField();
            }
        }
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> serverError(Exception e) {
        // 服务端日志：记录完整堆栈与请求上下文；客户端只拿到通用提示
        log.error("未处理异常: class={} msg={}", e.getClass().getName(), safe(e.getMessage(), ""), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "服务器内部错误，请稍后重试"));
    }

    /** 过滤可能含敏感信息（SQL、堆栈片段、换行）的错误文案。 */
    private static String safe(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        // 去除换行/控制字符，降低日志注入与回显污染
        String cleaned = raw.replaceAll("[\\r\\n\\t]", " ");
        if (cleaned.length() > 120) cleaned = cleaned.substring(0, 120);
        return cleaned;
    }
}