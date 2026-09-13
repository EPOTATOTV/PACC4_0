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
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;

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

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler({IllegalArgumentException.class})
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(error("error.badRequest", safe(e.getMessage(), null), "BAD_REQUEST"));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            MismatchedInputException.class})
    public ResponseEntity<?> validation(Exception e) {
        String key = "error.validation";
        if (e instanceof BindException be && be.getBindingResult() != null) {
            FieldError fe = be.getBindingResult().getFieldError();
            if (fe != null) {
                key = "error.field.invalid";
                return ResponseEntity.badRequest().body(error(key, resolve("error.field.invalid", fe.getField()), "VALIDATION"));
            }
        }
        return ResponseEntity.badRequest().body(error(key, null, "VALIDATION"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> serverError(Exception e) {
        // 服务端日志：记录完整堆栈与请求上下文；客户端只拿到通用提示
        log.error("未处理异常: class={} msg={}", e.getClass().getName(), safe(e.getMessage(), ""), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("error.internal", "服务器内部错误，请稍后重试", "INTERNAL"));
    }

    /** 统一错误响应：error 为展示文案，errorKey 为可编程的错误键（i18n 外键）。 */
    private Map<String, Object> error(String key, String fallback, String code) {
        Map<String, Object> m = new HashMap<>();
        m.put("error", fallback != null ? fallback : resolve(key));
        m.put("errorKey", key);
        m.put("code", code);
        return m;
    }

    private String resolve(String key, String... args) {
        try {
            return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
        } catch (Exception e) {
            return key;
        }
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