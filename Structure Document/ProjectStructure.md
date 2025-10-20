# 一、项目总体结构（Maven 多模块父子结构）

> 思路：顶层是聚合项目（Parent），下面按业务域拆分微服务（如 attribute、product、bom、document、workflow、search），
> 同时有公共模块（common、infrastructure、gateway、auth 等）。

```shell
plm-cloud-platform/
├── pom.xml                            # 父级 POM，统一管理依赖与版本
│
├── plm-common/                        # 公共模块（通用工具类、统一异常、DTO、常量等）
│   ├── src/main/java/com/plm/common/
│   │   ├── api/                       # 统一响应体、分页对象、错误码等
│   │   ├── exception/                 # 异常定义与全局异常处理
│   │   ├── utils/                     # 工具类（日期、JSON、字符串、Bean 拷贝等）
│   │   ├── dto/                       # 通用 DTO（分页、响应包装、通用请求体）
│   │   └── constants/                 # 常量与枚举
│   └── pom.xml
│
├── plm-infrastructure/                # 基础设施模块（Redis、MinIO、MQ、ES、邮件、日志、AOP）
│   ├── src/main/java/com/plm/infrastructure/
│   │   ├── config/                    # Spring 配置类（缓存、消息队列、数据库）
│   │   ├── mq/                        # 消息队列封装（Kafka/RabbitMQ 生产与消费）
│   │   ├── storage/                   # 文件存储（MinIO、S3 客户端封装）
│   │   ├── search/                    # 搜索引擎适配层（Elasticsearch/OpenSearch 客户端）
│   │   ├── cache/                     # Redis 缓存封装
│   │   ├── tracing/                   # 链路追踪、日志 MDC
│   │   └── security/                  # 安全工具类（加密、签名）
│   └── pom.xml
│
├── plm-auth-service/                  # 认证与授权中心（OAuth2 / Keycloak / JWT）
│   ├── src/main/java/com/plm/auth/
│   │   ├── controller/                # 登录、注册、刷新令牌、用户权限接口
│   │   ├── service/                   # 用户、角色、Token、权限服务
│   │   ├── domain/                    # 实体类（User、Role、Permission）
│   │   ├── repository/                # DAO 层（UserRepository、RoleRepository）
│   │   ├── config/                    # 安全配置（Spring Security / OAuth2 配置）
│   │   └── dto/                       # Auth相关 DTO（LoginRequest、TokenResponse）
│   └── pom.xml
│
├── plm-attribute-service/             # 属性与分类管理（Attribute/Classification Service）
│   ├── src/main/java/com/plm/attribute/
│   │   ├── controller/                # 提供属性CRUD、分类CRUD接口
│   │   ├── service/                   # 业务逻辑层（AttributeService、TemplateService）
│   │   ├── domain/                    # 领域模型（Attribute、Classification、Template）
│   │   ├── repository/                # 数据访问（AttributeRepository、Mapper）
│   │   ├── dto/                       # 请求/响应对象
│   │   ├── mapper/                    # MyBatis-Plus 映射文件
│   │   └── config/                    # 本模块配置（Swagger、ES同步）
│   └── pom.xml
│
├── plm-product-service/               # 产品与部件主数据（Product / Part Service）
│   ├── src/main/java/com/plm/product/
│   │   ├── controller/
│   │   ├── service/
│   │   ├── domain/
│   │   ├── repository/
│   │   ├── dto/
│   │   ├── events/                    # 事件定义（产品创建/修改事件）
│   │   └── listener/                  # 事件监听器（同步索引、触发ECO）
│   └── pom.xml
│
├── plm-bom-service/                   # 物料清单管理（BOM）
│   ├── src/main/java/com/plm/bom/
│   │   ├── controller/
│   │   ├── service/
│   │   ├── domain/
│   │   ├── repository/
│   │   ├── dto/
│   │   ├── diff/                      # BOM 对比算法模块
│   │   └── listener/                  # 监听产品变化事件
│   └── pom.xml
│
├── plm-document-service/              # 文档与CAD管理（PDM）
│   ├── src/main/java/com/plm/document/
│   │   ├── controller/
│   │   ├── service/
│   │   ├── domain/
│   │   ├── repository/
│   │   ├── storage/                   # MinIO/S3 集成
│   │   └── version/                   # 文档版本控制、Check-in/out逻辑
│   └── pom.xml
│
├── plm-workflow-service/              # 变更流程与ECO管理
│   ├── src/main/java/com/plm/workflow/
│   │   ├── controller/
│   │   ├── service/
│   │   ├── domain/
│   │   ├── repository/
│   │   ├── dto/
│   │   └── engine/                    # 流程引擎封装（Activiti / Flowable / Camunda）
│   └── pom.xml
│
├── plm-search-service/                # 搜索聚合模块（Elasticsearch）
│   ├── src/main/java/com/plm/search/
│   │   ├── controller/
│   │   ├── service/
│   │   ├── indexer/                   # 索引同步逻辑
│   │   ├── domain/                    # 索引文档对象（ProductIndex、AttributeIndex）
│   │   ├── query/                     # 构建查询语法、聚合、分页
│   │   └── listener/                  # 监听 Kafka 事件，更新索引
│   └── pom.xml
│
├── plm-gateway/                       # API 网关（Spring Cloud Gateway / Nginx）
│   ├── src/main/java/com/plm/gateway/
│   │   ├── config/
│   │   └── filter/                    # 鉴权过滤器、请求日志
│   └── pom.xml
│
├── plm-admin/                         # 平台管理后台（可选：内部接口）
│   ├── src/main/java/com/plm/admin/
│   │   ├── controller/
│   │   ├── service/
│   │   └── domain/
│   └── pom.xml
│
└── plm-deployment/                    # 部署相关文件
    ├── docker/
    │   ├── Dockerfile-auth
    │   ├── Dockerfile-product
    │   └── Dockerfile-bom
    ├── k8s/
    │   ├── auth-deployment.yaml
    │   ├── product-deployment.yaml
    │   └── ...
    └── helm/
        ├── values.yaml
        └── charts/
```

---

# 二、单个模块内部目录规范（Spring Boot 标准 + DDD思维）

以 `plm-product-service` 为例：

```shell
plm-product-service/
└── src/main/java/com/plm/product/
    ├── controller/             # 控制层，REST 接口
    │   └── ProductController.java
    │
    ├── service/                # 业务逻辑层
    │   ├── ProductService.java
    │   └── impl/
    │       └── ProductServiceImpl.java
    │
    ├── domain/                 # 领域模型（实体对象、聚合根、值对象）
    │   ├── entity/
    │   │   └── Product.java
    │   ├── vo/
    │   │   └── ProductDetailVO.java
    │   └── event/
    │       └── ProductCreatedEvent.java
    │
    ├── repository/             # 数据访问层（DAO）
    │   ├── ProductRepository.java
    │   └── mapper/
    │       └── ProductMapper.xml   # MyBatis XML映射
    │
    ├── dto/                    # 数据传输对象
    │   ├── ProductCreateRequest.java
    │   ├── ProductResponse.java
    │   └── PageQuery.java
    │
    ├── config/                 # 本模块配置（DB、Swagger、消息队列等）
    │   └── SwaggerConfig.java
    │
    ├── listener/               # 消息/事件监听器
    │   └── ProductEventListener.java
    │
    ├── converter/              # 对象转换（DTO ↔ Entity）
    │   └── ProductConverter.java
    │
    └── PlmProductApplication.java   # 启动类
```

🟢 **核心规范点：**

* **controller** 只负责接收请求/返回响应，不写业务逻辑。
* **service** 处理业务逻辑，可调多个 repository 或调用其他服务。
* **domain** 存放实体、聚合根、领域事件，保持高内聚。
* **repository** 层只负责数据访问。
* **dto/vo** 严格区分输入输出对象，防止实体泄露。
* **converter** 使用 MapStruct 或 BeanUtils 完成对象映射。
* **config** 独立配置，避免散落。
* **listener/event** 负责异步事件解耦，服务内通信靠事件驱动。

---

# 三、项目管理（父级 pom 示例逻辑）

父级 `pom.xml`（聚合 + 依赖管理）：

```xml
<modules>
    <module>plm-common</module>
    <module>plm-infrastructure</module>
    <module>plm-auth-service</module>
    <module>plm-attribute-service</module>
    <module>plm-product-service</module>
    <module>plm-bom-service</module>
    <module>plm-document-service</module>
    <module>plm-workflow-service</module>
    <module>plm-search-service</module>
    <module>plm-gateway</module>
</modules>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-dependencies</artifactId>
            <version>3.3.3</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

# 四、命名规范与说明

| 模块名                     | 作用               |
| ----------------------- | ---------------- |
| `plm-common`            | 公共依赖与通用工具        |
| `plm-infrastructure`    | 系统级支撑（缓存、消息、搜索）  |
| `plm-auth-service`      | 用户认证授权           |
| `plm-attribute-service` | 属性/分类管理核心        |
| `plm-product-service`   | 产品/部件主数据         |
| `plm-bom-service`       | 物料清单管理           |
| `plm-document-service`  | 文档、CAD 文件        |
| `plm-workflow-service`  | 变更与审批流           |
| `plm-search-service`    | 统一搜索与聚合查询        |
| `plm-gateway`           | API 网关           |
| `plm-admin`             | 后台管理入口           |
| `plm-deployment`        | 部署、Docker、K8s 文件 |

---

# 五、扩展建议

✅ 未来可扩展方向：

* 加入 `plm-analytics-service` 做报表/可视化；
* 加入 `plm-integration-service` 对接外部 ERP/MES；
* 拆分 `plm-eventbus` 模块专门做 Kafka Topic 管理；
* 前端 React / Vue 直接访问 `plm-gateway` 统一 API；
* 模块注册到 Nacos 或 Consul，支持服务发现。

---
