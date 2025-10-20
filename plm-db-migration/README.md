# plm-db-migration

数据库版本管理模块，使用 Flyway 维护 DDL 演进。

## 目录结构

```text
plm-db-migration/
  pom.xml
  src/main/resources/db/migration/
    V1__init.sql
```

Flyway 根据文件名的前缀版本号执行（递增），双下划线后是描述。建议保持语义化：`V2__create_user_table.sql`。

## 配置方式

在执行 Maven Flyway 插件时通过命令行传入数据库连接信息（避免在源码里硬编码敏感信息）：

```powershell
mvn -pl plm-db-migration flyway:clean flyway:migrate \
  -Dflyway.url=jdbc:postgresql://localhost:5432/plm \
  -Dflyway.user=plm_user \
  -Dflyway.password=Secret123 \
  -Dflyway.schemas=public
```

支持添加多个 `locations`，当前默认：`src/main/resources/db/migration`。

## 常用命令

```powershell
# 只迁移
mvn -pl plm-db-migration flyway:migrate -Dflyway.url=... -Dflyway.user=... -Dflyway.password=...

# 校验版本
mvn -pl plm-db-migration flyway:info -Dflyway.url=... -Dflyway.user=... -Dflyway.password=...

# 回滚（会清空 schema，谨慎）
mvn -pl plm-db-migration flyway:clean -Dflyway.url=... -Dflyway.user=... -Dflyway.password=...
```

## 新建迁移文件步骤

1. 在 `src/main/resources/db/migration` 下创建文件：`V2__create_xxx_table.sql`
2. 编写 DDL，保持幂等（避免使用不兼容的特性）。
3. 执行 `flyway:migrate` 验证。

## 注意事项

- 不要修改已发布版本的 SQL 内容，如需调整结构请新增版本。
- 如果要支持多数据库（例如 MySQL + PostgreSQL），可使用不同目录：`db/migration/postgres` 和 `db/migration/mysql` 并在 pom 中新增 `<locations>`。
- 建议后续加入数据库驱动依赖（如 PostgreSQL、MySQL）。

## 后续可扩展

- 集成到 CI：构建前自动执行 `flyway:info` 检查是否有未应用的迁移。
- 添加测试模块对关键表结构进行断言。
- 可与 Spring Boot 集成（在服务启动时自动迁移），当前保持独立模块用于集中管理。
