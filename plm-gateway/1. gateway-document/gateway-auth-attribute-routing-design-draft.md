# Gateway 多服务访问路由设计草案

更新时间：2026-04-13  
阶段：本地联调网关草案

---

## 1. 目标

当前本地开发阶段，需要把多个后端服务的对外访问入口统一收敛到 gateway，对前端只暴露一个固定入口：

- gateway：8080
- auth-service：8081
- attribute-service：8082

前端统一访问 gateway，由 gateway 按路径转发到下游服务，避免前端直接感知多个本地端口。

---

## 2. 当前服务边界

### 2.1 auth-service

当前认证接口统一以 /auth 开头，例如：

- /auth/public/register/email-code
- /auth/public/register
- /auth/public/login/password
- /auth/me
- /auth/workspaces

因此 auth-service 路由规则可直接收敛为：

- Path = /auth/**

### 2.2 attribute-service

当前属性与元数据接口统一以 /api/meta 开头，例如：

- /api/meta/categories/**
- /api/meta/attribute-defs/**
- /api/meta/code-rules/**
- /api/meta/imports/workbook/**
- /api/meta/exports/workbook/**

因此 attribute-service 路由规则可直接收敛为：

- Path = /api/meta/**

---

## 3. 端口规划

本地开发端口规划如下：

- gateway：8080
- auth-service：8081
- attribute-service：8082

设计约束：

1. gateway 继续作为前端唯一入口。
2. auth-service 与 attribute-service 不再共用 8080，避免本地启动冲突。
3. 下游服务仍保留各自原始路径，不在 gateway 做 StripPrefix，减少联调路径漂移。

---

## 4. 路由设计

### 4.1 auth 路由

- id：auth-service
- uri：`http://localhost:8081`
- predicates：Path=/auth/**

说明：

- gateway 直接保留 /auth/** 原始路径转发到 auth-service。

### 4.2 attribute 路由

- id：attribute-service
- uri：`http://localhost:8082`
- predicates：Path=/api/meta/**

说明：

- gateway 直接保留 /api/meta/** 原始路径转发到 attribute-service。
- workbook import/export、分类、属性、字典、编码规则等接口均已覆盖在该前缀下。

---

## 5. 配置落地策略

### 5.1 auth-service

在 dev profile 中设置：

- server.port = 8081

### 5.2 attribute-service

在 dev profile 中设置：

- server.port = 8082

### 5.3 gateway

在 dev profile 中设置：

- server.port = 8080
- spring.cloud.gateway.server.webflux.globalcors
- spring.cloud.gateway.server.webflux.routes[auth-service]
- spring.cloud.gateway.server.webflux.routes[attribute-service]

---

## 6. 前端联调约定

前端本地联调时只访问 gateway：

- 认证相关接口走 `http://localhost:8080/auth/**`
- 属性与元数据相关接口走 `http://localhost:8080/api/meta/**`

这样前端不需要分别维护 8081 和 8082 两套后端 base URL。

补充约定：

1. 前端本地环境统一把 `http://localhost:8080` 作为唯一后端 base URL。
2. 下游服务端口 8081、8082 只用于服务自身启动和后端排查，不作为前端直连入口。

---

## 7. 风险与后续扩展

当前草案只覆盖本地开发阶段最小路由闭环，不包含：

- 服务发现注册
- 负载均衡
- 统一鉴权前置过滤
- 统一限流
- 完整链路追踪体系
- 前端静态资源托管

---

## 8. CORS 设计

本地开发阶段，前端可能运行在不同端口的 localhost 或 127.0.0.1，因此 gateway 需要统一承担跨域放行职责。

当前建议：

- 对 `/**` 启用全局 CORS。
- 允许来源模式：`http://localhost:*`、`https://localhost:*`、`http://127.0.0.1:*`、`https://127.0.0.1:*`。
- 允许方法：GET、POST、PUT、DELETE、PATCH、OPTIONS。
- 允许请求头：全部。
- 允许凭证：true。

这样前端在本地切换端口时，不需要逐个后端服务重复配置 CORS。

---

## 9. 基础访问日志设计

本地联调阶段，gateway 需要直接在终端输出最基础的接口访问日志，便于前端和后端快速确认请求是否正确命中下游服务。

当前建议：

- 使用一个 GlobalFilter 输出请求进入日志和响应完成日志。
- 日志至少包含：HTTP 方法、请求路径、命中 routeId、响应状态码、耗时。
- 日志级别使用 INFO，默认打印到终端。

示例日志：

- `gateway request method=POST path=/auth/public/login/password`
- `gateway response method=POST path=/auth/public/login/password routeId=auth-service status=200 elapsedMs=18`

---

## 10. 依赖管理

当前 gateway 依赖改为走父项目统一 BOM 管理：

- 父 pom 引入 `spring-cloud-dependencies` `2025.0.0`
- gateway 直接使用 `spring-cloud-starter-gateway-server-webflux`，不再在子模块显式指定版本

这样可以避免 gateway 单模块手写版本与 Spring Boot 3.5.x 漂移。

---

## 11. 最小错误透传策略

当前 gateway 采用“下游业务错误原样透传，gateway 自身错误统一补齐 JSON”的策略。

规则如下：

1. 如果请求已经命中 auth-service 或 attribute-service，且下游返回了自己的 4xx/5xx JSON，gateway 不改写该响应体。
2. 如果请求没有命中任何 route，gateway 返回 404，错误码为 `GATEWAY_ROUTE_NOT_FOUND`。
3. 如果请求命中了 route，但下游服务未启动、端口不可达或连接超时，gateway 返回 502，错误码为 `GATEWAY_DOWNSTREAM_UNAVAILABLE`。
4. 如果 gateway 自身处理链内部出现未分类异常，gateway 返回 500，错误码为 `GATEWAY_INTERNAL_ERROR`。

返回体字段继续沿用现有后端风格：

- `timestamp`
- `status`
- `error`
- `code`
- `message`
- `path`

后续如果要把 gateway 从“本地开发路由器”提升到“统一入口网关”，可以继续补：

1. 全局异常透传与统一错误头。
2. 请求链路 traceId 透传。
3. Sa-Token 或 JWT 统一前置校验。
4. 本地与测试环境的多 profile 路由配置。
