# 属性类型与值存储规范（Meta Attribute / LOV）

更新时间：2026-02-24  
适用范围：`plm_meta.meta_attribute_def/version`、`plm_meta.meta_lov_def/version`、`plm.attribute_value`

---

## 1. 目标

统一前后端对属性数据类型的定义、校验与存储方式，确保：

1. 属性设计（元数据）可表达完整约束；
2. 运行时属性值落库字段明确；
3. 枚举/多值枚举格式一致，便于查询与扩展。

---

## 2. 元数据模型说明

### 2.1 表职责

- `meta_attribute_def`：属性定义主键、归属分类、状态、是否 LOV 属性。
- `meta_attribute_version`：属性版本快照（核心在 `structure_json`）。
- `meta_lov_def`：LOV 定义（归属属性）。
- `meta_lov_version`：LOV 值版本（核心在 `value_json`）。

### 2.2 `structure_json` 建议字段

```json
{
  "displayName": "颜色",
  "description": "物料颜色",
  "dataType": "enum",
  "unit": null,
  "defaultValue": null,
  "required": true,
  "unique": false,
  "hidden": false,
  "readOnly": false,
  "searchable": true,
  "lovKey": "COLOR_LOV",
  "multiple": false
}
```

> 说明：
> - `dataType` 是类型主判定字段；
> - `lovKey` 仅在 `enum/multi-enum` 需要；
> - `multiple` 用于区分单值枚举与多值枚举（建议保留）。

---

## 3. 类型枚举与落库映射（推荐标准）

| dataType | 含义 | 值存储字段（plm.attribute_value） | 备注 |
|---|---|---|---|
| `string` | 字符串 | `value_text` | 长文本也可落此字段 |
| `number` | 数值 | `value_number` | 统一小数精度由 DB `NUMERIC(30,8)` 保证 |
| `boolean` | 布尔 | `value_bool` | 建议统一 `true/false` |
| `enum` | 单值枚举 | `value_text`（存 code）或 `value_json`（存对象） | 推荐统一存 code，显示时再映射 LOV |
| `multi-enum` | 多值枚举 | `value_json`（数组） | 推荐存 code 数组，避免名称变更影响历史 |

> 当前系统状态：
> - 数据库层已具备上述类型承载能力（`value_text/value_number/value_bool/value_json`）；
> - 元数据接口层对 `enum` 支撑最完整（含 `lovKey` 与 LOV 查询）；
> - 其他类型可用，但业务校验仍建议按本规范补齐。

---

## 4. LOV 值结构规范

### 4.1 `meta_lov_version.value_json` 标准格式

```json
{
  "values": [
    { "code": "RED", "name": "红色", "order": 1, "active": true },
    { "code": "BLUE", "name": "蓝色", "order": 2, "active": true }
  ]
}
```

字段约定：

- `code`：稳定编码（推荐英文/系统码）；
- `name`：展示名称（可本地化）；
- `order`：排序；
- `active`：启停状态。

### 4.2 运行时多值枚举推荐格式（`attribute_value.value_json`）

```json
{
  "codes": ["RED", "BLUE"]
}
```

可扩展格式（如需展示快照）：

```json
{
  "codes": ["RED", "BLUE"],
  "labels": ["红色", "蓝色"]
}
```

> 推荐主存 `codes`，`labels` 仅用于快照展示（可选）。

---

## 5. 前后端校验规则（建议）

1. `string`：
   - 值类型必须字符串；
   - 可额外支持长度、正则（后续扩展字段）。

2. `number`：
   - 值必须可解析为数值；
   - 单位建议通过 `unit` 定义，不写进值本身。

3. `boolean`：
   - 仅接受 `true/false`。

4. `enum`：
   - 必须存在 `lovKey`；
   - 值必须命中 LOV 的 `code`。

5. `multi-enum`：
   - 必须存在 `lovKey`；
   - 值必须是数组；
   - 数组内每个元素必须命中 LOV `code`；
   - 建议去重并保序。

---

## 6. 接口约定示例

## 6.1 创建/更新属性（元数据）

### `string`

```json
{
  "key": "materialName",
  "displayName": "物料名称",
  "dataType": "string",
  "required": true,
  "searchable": true
}
```

### `number`

```json
{
  "key": "length",
  "displayName": "长度",
  "dataType": "number",
  "unit": "mm",
  "defaultValue": "0"
}
```

### `boolean`

```json
{
  "key": "isStandard",
  "displayName": "是否标准件",
  "dataType": "boolean",
  "defaultValue": "false"
}
```

### `enum`

```json
{
  "key": "color",
  "displayName": "颜色",
  "dataType": "enum",
  "lovKey": "COLOR_LOV"
}
```

### `multi-enum`

```json
{
  "key": "availableColors",
  "displayName": "可选颜色",
  "dataType": "multi-enum",
  "lovKey": "COLOR_LOV"
}
```

---

## 7. 兼容与演进建议

1. `dataType` 建议在后端统一做白名单：`string/number/boolean/enum/multi-enum`。  
2. 对 `multi-enum` 建议新增 generated column（例如 `multiple_flag`）提升列表筛选能力（可选）。  
3. 后续若要支持日期、时间、对象类型，可继续沿 `value_json` 扩展，不影响现有四类基础字段。  
4. 若需要严格 schema，可为 `structure_json` 增加 JSON Schema 校验（应用层或 DB CHECK）。

---

## 8. 结论

- 当前数据库设计并不只支持枚举；
- 字符/数字/布尔/枚举/多值枚举都可承载；
- 现阶段主要差异在“业务校验与接口规范完整度”，本规范可作为前后端联调基线。
