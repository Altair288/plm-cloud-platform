# Attribute & LOV 导入说明

## Excel 模板列顺序

| 列序号 | 列名         | 说明 |
|--------|--------------|------|
| 0      | 分类编号      | 对应 meta_category_def.code_key 或 external_code 映射 |
| 1      | 分类名称      | 可选（目前不使用，仅人工核对） |
| 2      | 属性名称      | 枚举属性可读名称，将生成 slug 作为属性 key |
| 3      | 属性类型      | 仅支持 `enum`，其他类型会被拒绝 |
| 4      | 单位          | 可选 |
| 5..N   | 枚举值        | 任意数量枚举值；空单元格忽略 |

## 版本与幂等逻辑

1. 按 (分类编号, 属性名称) 分组生成 AttrGroup。  
2. 属性 key = slug(属性名称)。若不存在使用 insertIgnore 插入；存在则跳过插入。  
3. 计算 structure_json 的 hash 与最新版本 hash 比较，未变化跳过新属性版本。  
4. LOV key = generateLovKey(分类编号, 属性名称)。同样 insertIgnore。  
5. LOV values 计算 value_json hash；未变化跳过新 LOV 版本。  
6. 新版本 version_no = 最新版本 version_no + 1，否则为 1；旧版本 is_latest=false，新版本 is_latest=true。  

## JSON 结构示例

Attribute structure_json:

```json
{
  "displayName": "颜色",
  "dataType": "enum",
  "unit": "",
  "lovKey": "catA.color"
}
```

LOV value_json:

```json
{
  "values": [
    {"code": "RED", "name": "RED", "order": 1, "active": true},
    {"code": "BLUE", "name": "BLUE", "order": 2, "active": true}
  ]
}
```

## 返回摘要字段

| 字段 | 说明 |
|------|------|
| totalRows | Excel 最后一行索引 |
| attributeGroupCount | 去重后的 (分类, 属性) 组数 |
| createdAttributeDefs | 新增属性定义数量 |
| createdAttributeVersions | 新增属性版本数量 |
| createdLovDefs | 新增 LOV 定义数量 |
| createdLovVersions | 新增 LOV 版本数量 |
| skippedUnchanged | 因 hash 未变化而跳过的版本数 |
| errorCount | 错误数量 |

## 错误处理

当前错误行使用首行行号；后续可扩展显示全部来源行号。

## 后续优化建议

* 增加数据类型扩展支持 (string, number)。
* 引入验证列（必填/唯一）写入 structure_json。
* 批量原生插入版本表进一步降低 JPA flush 成本。
