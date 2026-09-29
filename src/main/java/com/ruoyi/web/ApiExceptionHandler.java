package com.ruoyi.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一的错误响应。
 *
 * <p>把内核抛出的业务异常翻译成带说明的 JSON，前端直接把 {@code message} 显示给玩家，
 * 不用再去猜 HTTP 状态码的含义。</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 会话不存在。
     *
     * @param e 异常
     * @return 404 + 说明
     */
    @ExceptionHandler(GameSessionService.SessionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> onSessionMissing(
            GameSessionService.SessionNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * 关卡未解锁。
     *
     * @param e 异常
     * @return 403 + 说明
     */
    @ExceptionHandler(GameSessionService.LevelLockedException.class)
    public ResponseEntity<Map<String, Object>> onLevelLocked(
            GameSessionService.LevelLockedException e) {
        return build(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /**
     * 参数不合法（方向名、种子格式、槽位编号等）。
     *
     * @param e 异常
     * @return 400 + 说明
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> onBadRequest(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * 请求体不是合法 JSON。
     *
     * @param e 异常
     * @return 400 + 说明
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> onUnreadableBody(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "请求体不是合法的 JSON");
    }

    /**
     * 路径/查询参数类型不对（例如槽位编号写了非数字）。
     *
     * @param e 异常
     * @return 400 + 说明
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> onTypeMismatch(MethodArgumentTypeMismatchException e) {
        return build(HttpStatus.BAD_REQUEST, "参数 " + e.getName() + " 的类型不对：" + e.getValue());
    }

    /**
     * 路径不存在。
     *
     * <p>注意必须显式接住：否则会被下面的兜底分支当成未知异常，把正常的 404 变成 500。</p>
     *
     * @param e 异常
     * @return 404 + 说明
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> onNotFound(NoResourceFoundException e) {
        return build(HttpStatus.NOT_FOUND, "接口不存在：" + e.getResourcePath());
    }

    /**
     * 请求方法不对（例如对该用 POST 的接口发了 GET）。
     *
     * @param e 异常
     * @return 405 + 说明
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> onMethodNotAllowed(
            HttpRequestMethodNotSupportedException e) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, e.getMessage());
    }

    /**
     * 兜底：任何没被上面接住的异常都记日志并返回 500，避免把堆栈直接暴露给前端。
     *
     * @param e 异常
     * @return 500 + 说明
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onUnexpected(Exception e) {
        log.error("接口处理失败", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", status.value());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
