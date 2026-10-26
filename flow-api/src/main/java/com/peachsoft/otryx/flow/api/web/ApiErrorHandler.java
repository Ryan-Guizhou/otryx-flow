package com.peachsoft.otryx.flow.api.web;

import com.peachsoft.otryx.api.RpcRemoteException;
import com.peachsoft.otryx.api.RpcTimeoutException;
import com.peachsoft.otryx.api.RpcUnavailableException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一处理对外错误，避免泄露 RPC 或数据库内部信息。 */
@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrorHandler.class);

    /** 处理下游 RPC 暂时不可用或超时错误。 */
    @ExceptionHandler({RpcUnavailableException.class, RpcTimeoutException.class})
    public ResponseEntity<Map<String,String>> unavailable(RuntimeException problem) {
        LOGGER.warn("RPC dependency unavailable: {}", problem.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", "RPC_UNAVAILABLE"));
    }

    /** 处理 RPC Provider 错误，不泄露堆栈。 */
    @ExceptionHandler(RpcRemoteException.class)
    public ResponseEntity<Map<String,String>> remote(RpcRemoteException problem) {
        LOGGER.warn("RPC remote failure: {}", problem.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("code", "RPC_REMOTE_ERROR"));
    }

    /** 处理不合法的用户请求。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, MissingRequestHeaderException.class})
    public ResponseEntity<Map<String,String>> invalid(Exception problem) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST"));
    }
}
