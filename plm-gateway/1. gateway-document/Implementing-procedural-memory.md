# Gateway 实现过程记忆

## 2026-04-13 本地多服务访问入口收敛

- 当前本地开发阶段的目标是将 auth-service 与 attribute-service 的前端访问统一收敛到 plm-gateway。
- 端口规划已确定为：gateway 8080、auth-service 8081、attribute-service 8082。
- auth-service 当前接口前缀稳定为 /auth/**，适合直接按 Path=/auth/** 转发。
- attribute-service 当前接口前缀稳定为 /api/meta/**，适合直接按 Path=/api/meta/** 转发。
- 当前实现策略为“保留原始路径转发”，不使用 StripPrefix，避免前后端联调路径发生二次偏移。
- 当前阶段只覆盖本地开发联调，不引入服务注册发现、负载均衡、统一鉴权过滤器和限流。
- 已为 plm-gateway 新增 GatewayRoutingConfigIT，使用 dev profile 校验 server.port=8080，以及 auth-service 与 attribute-service 两条路由已正确装配。
- 父 pom 已新增 spring-cloud-dependencies 2025.0.0 BOM，plm-gateway 已改为直接依赖 spring-cloud-starter-gateway-server-webflux 而不再手写版本。
- gateway 已新增全局 CORS 配置，覆盖 localhost 与 127.0.0.1 的本地端口联调场景。
- gateway 已新增 GatewayAccessLogFilter，在终端打印请求方法、路径、routeId、状态码和耗时。
- 由于 Spring Cloud Gateway 2025.0.x 对配置前缀做了收敛，当前路由与 CORS 配置已迁移到 `spring.cloud.gateway.server.webflux.*`。
- gateway 已新增 GatewayJsonErrorHandler，对 gateway 自身产生的 404/502/500 统一输出 JSON 错误体，并保持下游业务错误原样透传。
- 前端联调文档已补充统一 gateway base URL 约定：本地只对接 8080，不直接请求 8081/8082。
