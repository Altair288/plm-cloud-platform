# 分类批量移动/复制接口集成测试经验总结

更新时间：2026-03-17
适用模块：plm-attribute-service

## 1. 背景

本次新增了分类批量移动/复制接口（/api/meta/categories/batch-transfer）的集成测试，重点验证：

- MOVE / COPY 两条主流程
- source overlap 归一化
- atomic 回滚与中止语义
- copiedFromCategoryId 落库与详情回传

## 2. 遇到的问题与结论

### 2.1 PowerShell 下 Maven -D 参数被错误解析

现象：

- 在 pwsh 中执行 Maven 指定测试时，出现 Unknown lifecycle phase，例如 `.surefire.failIfNoSpecifiedTests=false`

原因：

- PowerShell 会对 `-Dxxx` 参数做额外拆分，导致 Maven 属性未按预期传入

经验与处理：

- 在 pwsh 中优先使用 `mvn --% <args>`
- 如果不使用 `--%`，则至少对每个 `-D` 参数加双引号

推荐：

```bash
mvn --% -pl plm-attribute-service -am clean test -Dtest=MetaCategoryCrudServiceBatchTransferIT -Dsurefire.failIfNoSpecifiedTests=false
```

### 2.2 DTO / 实体新增后，增量测试可能命中陈旧编译产物

现象：

- 运行单测时出现 `NoClassDefFoundError`、`cannot be resolved to a type`、`Unresolved compilation problem`

原因：

- batch-transfer 涉及 `plm-common`、`plm-infrastructure`、`plm-attribute-service` 三模块联动
- 仅在单模块内增量 test，可能不会触发依赖模块重新编译

经验与处理：

- 新增 DTO、实体字段、Flyway 脚本后，优先执行 `clean test`
- Maven 命令务必带 `-am`，保证依赖模块一起重编译

### 2.3 REQUIRES_NEW 测试数据会真正写入本地库

现象：

- 测试通过后，本地数据库中残留多条 `IT-*` 分类测试数据

原因：

- 为了让服务内 `REQUIRES_NEW` 事务看见测试数据，测试准备阶段使用了独立提交事务
- 这类数据不会被外层测试事务自动回滚

经验与处理：

- 批量 transfer 集成测试必须在 `@AfterEach` 中主动清理已提交测试数据
- 清理顺序建议：
  1. category_hierarchy
  2. meta_category_version
  3. meta_category_def

### 2.4 全库最小 root depth 会污染真实 level 计算

现象：

- 通过接口测试创建测试根节点后，原有真实数据的 `level` 整体从 1 变成 2

原因：

- 历史实现按“全库最小 root depth”推导 level
- 当测试数据插入 depth=0 的根节点后，全局基线被拉低，导致真实根节点被重新解释为 level=2

经验与处理：

- `level` 不能依赖全库最小 root depth 这种脆弱的全局状态
- 更稳妥的做法：
  - 根节点 depth 基线固定为 1
  - level 优先按 path 段数推导
- 集成测试中的测试根节点也应使用与生产一致的 root depth 基线

### 2.5 外层事务缓存会影响对 MOVE 结果的断言

现象：

- batch-transfer 执行成功后，测试里立即读取实体，看到的仍是旧 parent / 旧状态

原因：

- 服务内部使用 `REQUIRES_NEW` 独立事务提交
- 测试方法外层事务中的持久化上下文可能仍持有旧缓存态

经验与处理：

- 断言前使用新的独立事务重新加载实体
- 不要直接信任当前测试事务中的缓存对象

### 2.6 纯 Service 集成测试仍建议使用非 Web 上下文

现象：

- 与当前测试目标无关的 Web 层初始化或映射问题，会拖慢或中断测试

经验与处理：

- 使用 `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)`
- 配合 `@ActiveProfiles("dev")` 明确启用本地数据库配置

## 3. 最终稳定执行方式

1. 保证 dev profile 对应数据库可连接
2. 使用 `-am clean test` 让依赖模块一起重编译
3. 使用单测类精确验证 batch-transfer

推荐命令：

```bash
mvn --% -pl plm-attribute-service -am clean test -Dtest=MetaCategoryCrudServiceBatchTransferIT -Dsurefire.failIfNoSpecifiedTests=false
```

预期结果：

- Tests run: 3, Failures: 0, Errors: 0
- batch-transfer 测试结束后不会再持续污染本地库

## 4. 可复用检查清单

- [ ] 测试类已声明 `@ActiveProfiles("dev")`
- [ ] 使用 non-web 测试上下文
- [ ] 测试数据准备使用独立提交事务（REQUIRES_NEW）
- [ ] 断言前在新事务中重新加载实体
- [ ] 测试结束后主动清理已提交测试数据
- [ ] 不用全库最小 root depth 推导 level
- [ ] 在 pwsh 下使用 `mvn --%`
- [ ] 新增 DTO / 实体改动后使用 `-am clean test`
