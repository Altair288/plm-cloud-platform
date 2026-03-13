# 分类批量删除接口集成测试经验总结

更新时间：2026-03-13
适用模块：plm-attribute-service

## 1. 背景

本次新增了分类批量删除接口（/api/meta/categories/batch-delete）的集成测试，重点验证两类事务语义：

- non-atomic：单项失败不影响其他项提交
- atomic：任一项失败触发整批回滚

## 2. 遇到的问题与结论

### 2.1 PowerShell 下 Maven -D 参数被错误解析

现象：

- 运行测试时出现 Unknown lifecycle phase，类似 .profiles.active=dev / .failIfNoSpecifiedTests=false

原因：

- 在 pwsh 中，-Dxxx 参数在某些情况下会被错误拆分

经验与处理：

- 在 pwsh 中执行 Maven 命令优先使用 --% 停止参数解析
- 推荐：mvn --% <args>

示例：

```bash
mvn --% -pl plm-attribute-service -am clean test -Dtest=MetaCategoryCrudServiceBatchDeleteIT -Dsurefire.failIfNoSpecifiedTests=false
```

### 2.2 测试上下文启动失败（DataSource 未就绪）

现象：

- Spring 测试上下文无法初始化，报 DataSource/Flyway 相关错误

原因：

- 未使用 dev profile，导致 plm.datasource 配置未加载

经验与处理：

- 集成测试类显式声明 @ActiveProfiles("dev")
- 依赖本地 PostgreSQL/Flyway 环境时，先确认数据库可连通

### 2.3 NoClassDefFoundError（批量删除 DTO）

现象：

- 启动测试时出现 NoClassDefFoundError: MetaCategoryBatchDeleteResponseDto

原因：

- 编译产物不一致或陈旧 class 被复用

经验与处理：

- 对相关模块执行 clean + 全链路重编译（-am）
- 不要只跑增量 test，优先 clean test 保证字节码一致

### 2.4 事务断言不稳定（atomic/non-atomic）

现象：

- non-atomic 预期部分成功但实际为 0
- atomic 预期回滚但状态未回滚

原因：

- 测试数据创建与被测事务不在同一提交边界
- 测试外层事务可能影响可见性

经验与处理：

- 测试数据准备使用 REQUIRES_NEW 提交
- atomic 批量执行放入独立事务模板（requires-new）
- 断言前重新加载实体，不依赖缓存态

### 2.5 Web 层初始化对 Service 集成测试的干扰

现象：

- 与测试目标无关的 MVC 映射或 Controller 反射失败导致测试中断

经验与处理：

- 纯 Service 集成测试优先使用非 Web 上下文
- 建议 @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)

## 3. 最终稳定执行方式

1) 保证数据库可用（dev profile 对应实例）
2) 使用 clean 全链路构建
3) 指定单测试类验证

推荐命令：

```bash
mvn --% -pl plm-attribute-service -am clean test -Dtest=MetaCategoryCrudServiceBatchDeleteIT -Dsurefire.failIfNoSpecifiedTests=false
```

预期结果：

- Tests run: 2, Failures: 0, Errors: 0
- Reactor 模块均 SUCCESS

## 4. 可复用检查清单

- [ ] 测试类已声明 @ActiveProfiles("dev")
- [ ] 需要时设置 non-web 测试上下文
- [ ] 测试数据创建使用独立提交事务（REQUIRES_NEW）
- [ ] 使用 clean test 消除陈旧 class 干扰
- [ ] 在 pwsh 下使用 mvn --% 防止参数解析问题
