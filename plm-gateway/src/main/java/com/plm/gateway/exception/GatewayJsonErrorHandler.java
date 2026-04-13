package com.plm.gateway.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayJsonErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayJsonErrorHandler.class);

    private final ObjectMapper objectMapper;

    public GatewayJsonErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }

        HttpStatus status = resolveStatus(ex);
        String code = resolveCode(status);
        String message = resolveMessage(status);
        String path = exchange.getRequest().getURI().getRawPath();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("code", code);
        body.put("message", message);
        body.put("path", path);

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        log.warn("gateway error path={} status={} code={} error={}", path, status.value(), code, ex.toString());

        return exchange.getResponse().writeWith(Mono.just(
                exchange.getResponse().bufferFactory().wrap(writeBody(body))
        ));
    }

    private HttpStatus resolveStatus(Throwable ex) {
        if (ex instanceof ResponseStatusException responseStatusException) {
            HttpStatusCode statusCode = responseStatusException.getStatusCode();
            HttpStatus resolved = HttpStatus.resolve(statusCode.value());
            if (resolved != null) {
                return resolved;
            }
        }

        if (contains(ex, ConnectException.class)
                || contains(ex, UnknownHostException.class)
                || contains(ex, TimeoutException.class)) {
            return HttpStatus.BAD_GATEWAY;
        }

        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private String resolveCode(HttpStatus status) {
        if (status == HttpStatus.NOT_FOUND) {
            return "GATEWAY_ROUTE_NOT_FOUND";
        }
        if (status == HttpStatus.BAD_GATEWAY
                || status == HttpStatus.SERVICE_UNAVAILABLE
                || status == HttpStatus.GATEWAY_TIMEOUT) {
            return "GATEWAY_DOWNSTREAM_UNAVAILABLE";
        }
        return "GATEWAY_INTERNAL_ERROR";
    }

    private String resolveMessage(HttpStatus status) {
        if (status == HttpStatus.NOT_FOUND) {
            return "gateway route not found";
        }
        if (status == HttpStatus.BAD_GATEWAY
                || status == HttpStatus.SERVICE_UNAVAILABLE
                || status == HttpStatus.GATEWAY_TIMEOUT) {
            return "downstream service is unavailable";
        }
        return "gateway request failed";
    }

    private boolean contains(Throwable ex, Class<? extends Throwable> type) {
        Throwable current = ex;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private byte[] writeBody(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException ex) {
            return ("{\"code\":\"GATEWAY_INTERNAL_ERROR\",\"message\":\"failed to render gateway error payload\"}")
                    .getBytes();
        }
    }
}