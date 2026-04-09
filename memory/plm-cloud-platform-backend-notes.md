- Meta category topology transfer endpoint: POST /api/meta/categories/batch-transfer/topology.
- Topology MVP is MOVE-only, defaults atomic=true, supports dependsOnOperationIds, and only allows descendant-first split ordering.
- expectedSourceParentId in topology ops is optional concurrency protection; validate it only when provided.
- Moving a node under its effective descendant should return CATEGORY_EFFECTIVE_TARGET_IN_DESCENDANT.

- Attribute enum/multi-enum writes use internal lov_def binding keys in AUTO mode; LOV code rules govern enum value code generation, and existing value codes should be reused by name to preserve update/import idempotency.
- Code rule version hashes are runtime-normalized as MD5(rule_json) via CodeRuleSupport and V24 migration; attribute/lov version jsonHash remains SHA-256-based and is a separate concern.

- plm-attribute-service now bootstraps built-in MATERIAL code rules CATEGORY/ATTRIBUTE/LOV and the default MATERIAL code rule set at startup when those tables were cleared, so service ITs no longer depend on preserved seed rows.

- For plm-cloud-platform Java IT/controller validation, prefer VS Code-integrated test execution: use the runTests tool first; if editor-command parity is needed, quick-open the target Java test file and trigger java.test.editor.run instead of terminal Maven.
- Workbook import review progress: JobState concurrency handling was tightened by documenting the model and funneling status/log access through synchronized helper methods; workbook service/controller tests passed 8/0 after that change.

- Attribute list endpoint GET /api/meta/attribute-defs treats categoryCode as an exact category filter, not a subtree/prefix filter; descendant loading should use category-tree APIs separately.。

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
