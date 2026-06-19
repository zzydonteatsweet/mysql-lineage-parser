# 子查询列血缘穿透 Spec 审查记录

- 日期：2026-06-19
- 状态：待修订 spec
- 审查对象：`docs/superpowers/specs/2026-06-19-subquery-column-passthrough-design.md`
- 关联：`docs/subquery-lineage-bug-analysis.md`（首轮排查）
- 审查方式：逐行比对 `SelectLineageVisitor.java` / `QueryScopeCache.java` / `TableSourceKey.java` / 模型类 + 实跑 `SelectLineageVisitorTest` baseline

---

## 0. 结论

**设计架构本身站得住，实现后能达到目标，不用动设计。**
但 spec 有 **2 处事实/陷阱级问题（P1）+ 测试矩阵不准（P2）+ 若干措辞清理（P3）**，建议实现前修订。

已核对正确的部分：
- §10 代码索引的**行号全部准确**（`:50` / `:90-119` / `:98` / `:170-204` / `:186` / `:240-268` / `:252-254` / `:327-342` / `:348-356` / `:405-409` / `:582` / `:612` / `:625` / `:635` / `:644` / `:650`）。
- **死代码判定属实**：`tryResolveSubqueryColumn` / `copySourcesFromInnerLineage` / `collectReferencedColumns` / `extractReferencedColumns` / `lastQueryBlockScope` 全仓只出现在 `SelectLineageVisitor.java` 与文档中（grep 确认）。
- `copyOutputColumnsFrom`（`QueryScopeCache:90`）确实把内层 outputColumnMap 拷进派生表 scope。
- "flatten 而非 chain" 取舍与三个穿透测试断言一致（单层 `sources.get(0)` 直接要物理叶；多层 `findLeafSource` 兼容 flatten）。
- 删 `lastQueryBlockScope` 改"递归回传 scope" → Bug B 自动消失（每层局部拿自己的直接子层，无全局寄存器被孙层覆盖）。

---

## 1. P1 — 实现陷阱 / 事实错误（实现前必改）

### P1.1 §4.1.3 片段漏掉派生表注册

**问题**：§4.1.3 的 `cacheSubqueryTableSource` 片段只展示了 `if/else` 块，**没有保留当前 `:202` 的 `tableSourceCacheMap.put(key, cache)`**。该 `put` 是派生表 `sub` 进入外层 `tableSourceCacheMap` 的唯一入口——§4.3 追踪里 `resolveBareColumnRef` 能"扫到 sub scope"完全依赖它。

片段边界模糊：它同时删了原 `:183-190` 的 `outerCache` save/restore、`:179-198` 的 `currentScope` save、把 `if(lastQueryBlockScope!=null)` 换成 `instanceof`，读起来像"整块重写"。实现者很可能连带丢掉 `:202/:203` → **派生表不注册 → 外层找不到 sub → 3 个穿透用例全继续 FAIL**。

**修订**：在 §4.1.3 显式补一句——"保留当前 `:202` 的 `tableSourceCacheMap.put(key, cache)` 与 `:203` 的 `mergeInvolvedTables(parentScope, cache.getInvolvedTables())`"。

### P1.2 §4.2.3 / §7 把"去重"说成 :612 自带——错误

**问题**：§4.2.3 写 `copySourcesFromInnerLineage(inner, lineage)  # 复用 :612（按 table+col 去重）`。但 `:612` 实际：

```java
private void copySourcesFromInnerLineage(ColumnLineage innerLineage, ColumnLineage outerLineage) {
    if (innerLineage.getSourceColumns() != null) {
        for (ColumnInfo source : innerLineage.getSourceColumns()) {
            outerLineage.addSourceColumn(source);   // 无去重
        }
    }
    String transformation = innerLineage.getTransformation();
    outerLineage.setTransformation(transformation != null ? transformation : "direct mapping");
}
```

`addSourceColumn`（`ColumnLineage.java:60`）也不去重，直接 `add`。**"复用即得去重"是假的，去重是净新增逻辑**。§7 把它列为"决策"是对的，但 §4.2.3 的注释会误导实现者以为复用就够。对 3 个单源穿透用例无影响，但 §9.1（表达式/JOIN 多源穿透）一上就出重复。

**修订**：改成"需在 `resolveBareColumnRef` 或 `copySourcesFromInnerLineage` 中新增按 `databaseName+tableName+columnName` 去重"。

---

## 2. P2 — §6 测试矩阵不准 / 不完整

实跑当前 baseline：**42 run / 23 fail**。对照 §6：

### P2.1 漏掉 2 个"本轮由红转绿"的用例

`directMappingTransformation`（`SELECT id, name FROM users`）和 `selfJoinWithAliases`（`a.id`）当前**都是红的**（失败信息：`expected: <direct mapping> but was: <null>`）——原因是当前 `resolveColumnSourceTables` 从不 `setTransformation`。设计的 `resolveColumnLineage` 对裸列设 `"direct mapping"`（§4.2.2/§7），**会顺手修绿这两个**。但 §6"必绿"只列 3 个穿透用例，既没列为必绿、也没预测翻转。

**修订**：把这两个加进"必绿"，实现后断言守住。

### P2.2 `selectMixedConstantsAndColumns` 被错分到"必须保持绿"

`SELECT id, 1 AS flag, 'active' AS status FROM users` 当前**红**（`flag` 经 `collectAllCachedTables` 兜底拿到 `users.flag`，测试期望常量无来源列）。§6 把"常量/无 FROM 用例"笼统算必绿，但带 FROM 的混合常量用例现在红、设计也不修（需表达式路径），它是**保持红、非回归**。

**修订**：区分——`selectConstantsOnly` / `selectNoFromClause`（无 FROM，当前绿，保持绿）vs `selectMixedConstantsAndColumns`（带 FROM，当前红，out-of-scope，保持红）。

### P2.3 "本轮仍红"清单不完整，缺可比对 baseline

当前红、§6 未列的还有：`existsSubquery` / `notExistsSubquery` / `inSubqueryWithCorrelation` / `subqueryInFromWithSubqueryInWhere` / `fromSubqueryPlusScalarSubqueryInSelect` / `subqueryInSelectWithMultipleColumns` / `havingFilterConditionShouldBeRecorded`。这些是 WHERE/EXISTS/scalar-subquery 类，设计不动它们，**确认不新增失败**，但没枚举则"不得新增失败"缺可验证基线。

**修订**：§6 附当前 23 个失败用例完整名单（见本文 §4），实现后做 diff——预期 `23 → 18`，新增绿的恰好 5 个（3 穿透 + `directMappingTransformation` + `selfJoinWithAliases`），其余不变。

---

## 3. P3 — 措辞 / 清理

- **§3.1**：删掉无对应测试的"裸列别名 `salary AS annual → employees.salary` 达标"措辞，或补一个用例。当前仓内无纯别名裸列用例，A2 仅被穿透用例的内层查询间接验证。
- transformation 在裸列+穿透路径被设两次（`copySourcesFromInnerLineage` 设一次、`resolveColumnLineage` 外层又设一次 `"direct mapping"`），无害，可只在一处设。
- `nowScopeColumnLineages` 字段（`:55`）是死字段（`extractSelectColumns` 用的是局部 `queryLineages` 参数），重构 `visit` 时顺手删。

---

## 4. 当前失败 baseline（23 个，供实现后 diff）

```
arithmeticExpressionShouldExtractAllReferencedColumns
aggregationWithDistinctShouldExtractColumn
aliasColumnShouldRetainTransformation
caseWhenSimpleFormatWithColumn
caseWhenWithMultipleColumnReferences
coalesceFunctionShouldExtractArguments
directMappingTransformation                 ← 本轮会转绿（P2.1）
existsSubquery
fromSubqueryPlusScalarSubqueryInSelect
havingFilterConditionShouldBeRecorded
ifFunctionShouldExtractArguments
inSubqueryWithCorrelation
multiLevelSubqueryColumnPassthrough         ← 本轮目标（必绿）
multipleExpressionsShouldEachHaveCorrectSources
nestedFunctionCallShouldExtractAllArguments
notExistsSubquery
selectMixedConstantsAndColumns
selfJoinWithAliases                          ← 本轮会转绿（P2.1）
subqueryColumnPassthroughSingleLevel        ← 本轮目标（必绿）
subqueryInFromWithSubqueryInWhere
subqueryInJoinColumnPassthrough
subqueryInSelectWithMultipleColumns
threeLevelSubqueryColumnPassthrough         ← 本轮目标（必绿）
```

实现后预期：**23 → 18 fail**。新增绿 5 个 = 3 穿透 + `directMappingTransformation` + `selfJoinWithAliases`。

---

## 5. 修订动作清单（给执行者）

| 优先级 | 位置 | 动作 |
|---|---|---|
| P1 | §4.1.3 | 补"保留 `:202` put 与 `:203` mergeInvolvedTables"说明 |
| P1 | §4.2.3 / §7 | 去重描述改为"需新增"，不写"复用 :612 即得" |
| P2 | §6 必绿 | 加 `directMappingTransformation`、`selfJoinWithAliases` |
| P2 | §6 常量分类 | `selectMixedConstantsAndColumns` 移到"本轮仍红" |
| P2 | §6 | 附 §4 的 23 个失败 baseline |
| P3 | §3.1 | 删/补"裸列别名达标"措辞 |
| P3 | §4.2.2 | transformation 只在一处设（可选） |
| P3 | §5 | 重构时顺手删 `nowScopeColumnLineages`（`:55`） |

> 注：设计架构（`processQueryBlock` 回传 scope、`resolveBareColumnRef` flatten 穿透、A2 表达式感知）无需调整。
