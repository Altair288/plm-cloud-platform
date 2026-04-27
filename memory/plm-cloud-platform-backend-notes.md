- Meta category topology transfer endpoint: POST /api/meta/categories/batch-transfer/topology.
- Topology MVP is MOVE-only, defaults atomic=true, supports dependsOnOperationIds, and only allows descendant-first split ordering.
- expectedSourceParentId in topology ops is optional concurrency protection; validate it only when provided.
- Moving a node under its effective descendant should return CATEGORY_EFFECTIVE_TARGET_IN_DESCENDANT.

- Attribute enum/multi-enum writes use internal lov_def binding keys in AUTO mode; LOV code rules govern enum value code generation, and existing value codes should be reused by name to preserve update/import idempotency.
- Code rule version hashes are runtime-normalized as MD5(rule_json) via CodeRuleSupport and V24 migration; attribute/lov version jsonHash remains SHA-256-based and is a separate concern.

- plm-attribute-service now bootstraps built-in MATERIAL code rules CATEGORY/ATTRIBUTE/LOV and the default MATERIAL code rule set at startup when those tables were cleared, so service ITs no longer depend on preserved seed rows.

- For plm-cloud-platform Java IT/controller validation, default to VS Code-integrated execution via the runTests tool first; do not substitute terminal Maven unless editor-side execution is unavailable or CLI parity is explicitly needed. If editor-command parity is needed, quick-open the target Java test file and trigger java.test.editor.run.
- Workbook import review progress: JobState concurrency handling was tightened by documenting the model and funneling status/log access through synchronized helper methods; workbook service/controller tests passed 8/0 after that change.
- Maven test lifecycle is now standardized in the parent pom: surefire runs only *Test during test, and failsafe runs *IT/*ITCase during integration-test and verify. For CI lifecycle checks, use targeted Maven test/verify even if normal Java validation still prefers VS Code runTests.
- plm-infrastructure now packages an executable Spring Boot jar and exits automatically after startup migration, so it can be used as a one-shot Docker/CI database init job; compose/service dependencies should wait on service_completed_successfully from the plm-infrastructure container.



- Attribute list endpoint GET /api/meta/attribute-defs treats categoryCode as an exact category filter, not a subtree/prefix filter; descendant loading should use category-tree APIs separately.

- MetaCategoryImportControllerIT 在 VS Code 集成测试/覆盖率运行下可能触发编辑器卡死，疑似与闭包表相关输出或覆盖率采集体量有关；该类及同类大输出 controller IT 优先使用终端 Maven 精确运行验证。

- Workbook import jobs should be submitted through the workbookImportTaskExecutor directly; self-invoking @Async methods inside the same service will bypass the proxy and can silently become synchronous.

- Workbook import phase2 now preloads existing category/path, attribute, and enum-value indexes during dry-run and stores them in ImportSessionState.existingData; import resolveActions should reuse that snapshot instead of re-querying row-by-row.

- Workbook import phase2 alignment update: dry-run now stores ImportSessionState.executionPlan alongside existingData, and import execution builds work items from that snapshot instead of reconstructing them from preview rows.
- Dry-run job stages are now aligned closer to the optimization draft with PRELOADING, VALIDATING_CATEGORIES, VALIDATING_ATTRIBUTES, VALIDATING_ENUMS, and BUILDING_PREVIEW before completion.
- Workbook import snapshot persistence is now backed by plm_meta.meta_workbook_import_snapshot; runtimeService falls back to DB for session/result recovery, and import can resolve sessions from either importSessionId or dryRunJobId.
- ImportSessionState now carries expiresAt; snapshot retention defaults to 2h via workbookImportProperties.runtime.snapshotRetentionMillis and runtime cleanup deletes expired DB snapshots.
- Workbook import snapshot cleanup runs from @Scheduled in WorkbookImportRuntimeService; DB deletion must be wrapped in a transaction (e.g. WorkbookImportSnapshotStore.deleteExpiredSnapshots), otherwise derived JPA delete methods can throw "No EntityManager with actual transaction available" on the scheduler thread.


- Workbook import P1 now uses MetaCodeRuleService.reserveCodes/CodeRuleGenerator.reserve for category/attribute/enum auto codes; category reservation must recurse by actual parent finalPath, and root reservation logs cannot use Map.of with null parentCode.

- Workbook import 7.4 now precomputes no-op overwrite rows during dry-run: categories compare resolvedFinalPath + latestName, attributes compare AttributeLovImportUtils.jsonHash of the would-be structureJson (reusing existing lovKey for enum updates), and enum options compare existing name + label; import treats SKIP_NO_CHANGE the same as other skip actions, including mixed enum groups.
- Workbook import execution plan now carries entity-specific resolvedWriteMode (CATEGORY_/ATTRIBUTE_/ENUM_ create/update/skip/conflict); import executes against writeMode instead of reinterpreting generic UPDATE, and FINALIZING logs CATEGORY_CLOSURE_INCREMENTAL_REUSED because category create/move already reuse MetaCategoryCrudService incremental closure maintenance rather than a global rebuild.
- Workbook-related tests now run normally in the VS Code integrated runner/editor; prefer the runTests tool or editor-integrated execution by default, and only fall back to command-line Maven when CLI parity is specifically needed.
- Workbook category dry-run auto-code preview must batch both root siblings and child siblings per parent/businessDomain; previewing each root individually will reuse the same next sequence and collapse P01/P02-style top-level categories onto one generated code.

- Workbook import execution must reserve auto codes only for CREATE work items. If CATEGORY/ATTRIBUTE/ENUM skip or update rows are included in reserve batches, they will burn sequence/audit entries without business-row writes and shift later created codes away from dry-run preview.







- Workbook export draft constraint: export is download-only, not a round-trip import contract; do not design ROUND_TRIP mode or workbook manifest metadata unless requirements change.

- Workbook export implementation now exposes schema + async job/status/log/stream/download under /api/meta/exports/workbook and sources rows from category def/latest version, attribute def/latest version structureJson, and lov def/latest version valueJson.
- After adding new DTO classes in plm-common, the VS Code runTests runner may hit stale target/classpath and throw NoClassDefFoundError until the dependent Maven modules are rebuilt; if that happens, do a targeted reactor rebuild before trusting the test result.

- User clarified the old runtime schema/data can be discarded for the new user-domain work; discuss and design against plm_runtime as the formal runtime naming, not the older plm schema label still visible in legacy baseline files.
- Current user-domain phase is intentionally limited to tenant/user/member/role/permission/auth scope; do not pull metadata push-back, approval workflow, or long-term governance requirements into the first-phase design unless the user explicitly asks.
- User-domain design direction changed from tenant-first SaaS to user -> workspace first; current phase should model workspace/member/role/auth context as the primary collaboration boundary, while preserving future extensibility toward a fuller tenant/SaaS layer.


- Auth workspace bootstrap endpoint GET /auth/public/workspace-bootstrap-options returns dictionary option fields as code/label/description/sortOrder/isDefault; backend ITs should assert against label, not a docs-only name field.
- Workspace create no longer accepts request.workspaceCode; auth-service now generates workspace_code as ws_{ownerUserId8}_{workspaceNameSlug}_{workspaceId8}, and manual workspaceCode input should return INVALID_ARGUMENT.


- 在当前 plm-auth-service 编译配置下，新加的 Spring MVC `@PathVariable` / `@RequestParam` 不要依赖方法参数名推断；显式写出注解名（如 `@PathVariable("token")`、`@RequestParam("workspaceId")`）可避免邀请类 GET 接口出现 400 绑定失败。

- plm-auth-service password RSA transport now uses Redis-backed key pairs with a 24h TTL keyed by dynamic encryptionKeyId whenever fixed PEM keys are not configured; keep fixed PEM support as higher priority, and require Redis connectivity/authentication instead of falling back to in-process keys.

- platform admin auth now uses dedicated endpoints POST /auth/public/platform-admin/login/password and GET /auth/platform-admin/me, backed by plm_platform.platform_role/platform_user_role; note that platform_role currently has created_at/created_by only and does not include updated_at/updated_by, so JPA entities must not map those columns.
