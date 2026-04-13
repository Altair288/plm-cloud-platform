package com.plm.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Component
public class GatewayAccessLogFilter implements GlobalFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(GatewayAccessLogFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startNanos = System.nanoTime();
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getURI().getRawPath();
        String query = exchange.getRequest().getURI().getRawQuery();
        String requestPath = query == null || query.isBlank() ? path : path + "?" + query;

        log.info("gateway request method={} path={}", method, requestPath);

        return chain.filter(exchange)
                .doOnSuccess(unused -> logCompletion(exchange, method, requestPath, startNanos, null))
                .doOnError(error -> logCompletion(exchange, method, requestPath, startNanos, error));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private void logCompletion(ServerWebExchange exchange,
                               String method,
                               String requestPath,
                               long startNanos,
                               Throwable error) {
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        String routeId = route == null ? "unmatched" : route.getId();
        HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
        int status = statusCode == null ? 0 : statusCode.value();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        if (error == null) {
            log.info("gateway response method={} path={} routeId={} status={} elapsedMs={}", method, requestPath, routeId, status, elapsedMillis);
            return;
        }

        log.warn("gateway response method={} path={} routeId={} status={} elapsedMs={} error={}", method, requestPath, routeId, status, elapsedMillis, error.toString());
    }
}