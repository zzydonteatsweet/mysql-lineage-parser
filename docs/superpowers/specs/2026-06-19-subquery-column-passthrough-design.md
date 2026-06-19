# 子查询列血缘穿透修复 设计文档（Spec）

- 日期：2026-06-19
- 状态：待实现
- 作者：协作产出（用户 + Claude）
- 关联文档：`docs/subquery-lineage-bug-analysis.md`（首轮排查，本轮修正其归因）

---

## 1. 背景与问题

`SELECT ... FROM (SELECT ...) t` 这类派生表（子查询）场景，外层列血缘无法穿透派生表追到物理源列。
首轮排查文档把它归为一个 Bug A + 一个 Bug B。**本轮对着真代码 + 实跑探针复核后，修正归因：所谓「Bug A」其实是两个独立子问题 A1 / A2。**

### 1.1 三个子问题

| 编号 | 名称 | 现象 | 根因位置 |
|---|---|---|---|
| **B** | `lastQueryBlockScope` 寄存器嵌套腐化 | 内层 SELECT 自己还套子查询时，派生表 scope 挂上了**孙子层**的 scope 而非直接子层 | `SelectLineageVisitor.java:50` 字段；`:98` 单写点；`:192-197` 读取；`visit` 退出（`:114/:116`）只还原 `currentScope` 和缓存，**从不还原 `lastQueryBlockScope`** |
| **A1** | 第二跳源列未拷贝（穿透结构） | 外层命中派生表后，只盖 `derived.col`，不拷内层已解析到物理的 `sourceColumns` | `resolveColumnSourceTables`(`:327`) → `findMatchedTablesByOutputColumn`(`:348-356`，`:351` 只把内层 lineage 当布尔判定) → `addSourceColumnsForTables`(`:405-409`，`:407` 盖派生别名) |
| **A2** | 内层列解析丢表达式（列内容） | `SELECT id AS a` 记成 `t1.a` 而非 `t1.id`——source 列名永远=输出名 | `extractSelectColumns`(`:240-268`) 在 `:254` 只传输出名 `outputColumnName`，`:246` 拿到的表达式 `id` 被丢弃 |

### 1.2 实跑探针证据（已验证，非推演）

临时探针对三条 SQL 打印真实 `sourceColumns`（探针用完即删）：

| SQL | output | **实际** sourceColumns |
|---|---|---|
| `SELECT id AS a FROM t1` | a | `t1.**a**`（不是 `t1.id`） |
| `SELECT salary AS annual FROM employees` | annual | `employees.**annual**`（不是 `employees.salary`） |
| `SELECT a FROM (SELECT id AS a FROM t1) sub` | a | `sub.**a**`（停在派生别名） |

结论：**当前代码里 sourceColumns 的列名永远等于输出名**，跟表达式里真正引用的列无关。`a→id` 的映射只活在 SELECT 表达式 `id AS a` 里，而表达式被丢弃（A2）。

### 1.3 与现有测试的关系

现有测试套件**本来就编码了正确契约**（source = 物理列），并非写错；当前因代码达不到而全红：

- `subqueryColumnPassthroughSingleLevel`（`SelectLineageVisitorTest.java:154`）：断言 source = `t1.id`
- `multipleExpressionsShouldEachHaveCorrectSources`（`:134`）：断言 `salary*12 AS annual` 的 source = `salary`
- `arithmeticExpressionShouldExtractAllReferencedColumns`（`:88`）：断言 source 含 `price/quantity/discount`

即：**实现与测试目标一致，只是实现未达标。**

---

## 2. 目标契约

修复后须满足的列血缘契约：

1. **`outputColumn` 可以是别名**（如 `a`、`annual`、`total`）。
2. **`sourceColumns` 必须是物理表.物理列**（如 `t1.id`、`employees.salary`），且：
   - 物理表名：穿透派生表到底层物理表，**不在派生别名（`sub`/`t2d`）处停留**（flatten）。
   - 物理列名：来自表达式里真正引用的列，**不取输出别名**。

> 设计原则：**递归顺序保证 flatten 可行**。内层子查询先于外层被解析（`cacheSubqueryTableSource` 先递归进内层），所以当外层解析某列命中派生表时，派生表 `outputColumnMap` 里那条 lineage 的 `sourceColumns` **早已解析到底层物理列**。外层直接 flatten 拷上来即可得到物理叶。

---

## 3. 范围

### 3.1 本轮 In-scope（B + A1 + A2-minimal）

- **B**：删掉 `lastQueryBlockScope` 寄存器，改为「递归回传 scope」。
- **A1**：外层命中派生表时，拷内层 `sourceColumns` 上来（flatten）。
- **A2-minimal**：SELECT 项是**裸列引用**（`SQLIdentifierExpr`、`SQLPropertyExpr` 非 `*`）时，按表达式列名 + FROM 表解析成物理列。

→ 交付：3 个子查询穿透测试全绿；裸列引用按表达式解析到物理列（含别名场景，如 `id AS a` → `t1.id`）；嵌套任意层正确穿透。top-level 裸列**别名**（output≠expr）当前无专门断言用例，已在 §6 列为必绿直测 `bareColumnAliasShouldResolveToPhysicalColumn`（审查 P3）。

### 3.2 本轮 Out-of-scope（同契约的未来工作，spec 第 9 节展开）

- **一般表达式**：算术（`a+b`）、函数（`CONCAT/UPPER`）、`CASE WHEN`、`IF/COALESCE`、聚合（`SUM/COUNT(DISTINCT)`）。这些 source 同样要求物理列，但需表达式遍历器，本轮不做 → 对应测试继续红。
- **UNION 派生表**（`SQLUnionQuery`）：`processQueryBlock` 入参是 `MySqlSelectQueryBlock`，UNION 需另开分支。
- 自连接别名、前缀忽略等 JOIN 精度问题（`SelectLineageJoinTest` §11 已知缺口）。

---

## 4. 设计

### 4.1 B：递归回传 scope，删除 `lastQueryBlockScope`

把「处理一个查询块」抽成**返回它建的 scope** 的方法，派生表侧**局部捕获**子层 scope，不再用全局寄存器。

**4.1.1 新增方法** `processQueryBlock`（把现 `visit(MySqlSelectQueryBlock)` 的 body 搬进来，改为返回 scope）：

```java
private QueryScopeCache processQueryBlock(MySqlSelectQueryBlock x, QueryScopeCache parentScope) {
    QueryScopeCache scope = createQueryScope(parentScope, "query_result");

    Map<TableSourceKey, QueryScopeCache> outerCache = new LinkedHashMap<>(tableSourceCacheMap);
    tableSourceCacheMap.clear();
    QueryScopeCache saved = currentScope;
    currentScope = scope;

    if (x.getFrom() != null) collectFromAndCache(x.getFrom(), scope);
    List<ColumnLineage> l = new ArrayList<>();
    extractSelectColumns(x, l);
    if (x.getWhere() != null) applyWhereFilter(x.getWhere().toString(), l);

    currentScope = saved;
    tableSourceCacheMap.clear();
    tableSourceCacheMap.putAll(outerCache);
    return scope;                  // ← 子节点把自己的 scope 直接交回父节点
}
```

**4.1.2** `visit(MySqlSelectQueryBlock)` 退化为 shim（仅 Druid 最外层入口走它）：

```java
@Override
public boolean visit(MySqlSelectQueryBlock x) {
    processQueryBlock(x, null);    // 最外层 parentScope=null；nestingLevel 仍为 0，extractSelectColumns 照常把结果写进 lineageResult
    return false;
}
```

**4.1.3** `cacheSubqueryTableSource` 改为局部捕获子层 scope（`:186` 的 `accept(this)` 替换为直接调 `processQueryBlock`）：

```java
SQLSelectQuery inner = tableSource.getSelect().getQuery();
if (inner instanceof MySqlSelectQueryBlock) {
    nestingLevel++;
    QueryScopeCache child = processQueryBlock((MySqlSelectQueryBlock) inner, cache);  // ← 回传
    nestingLevel--;
    cache.addSubQueryCache(child);                          // 可选，仅树/调试
    mergeInvolvedTables(cache, child.getInvolvedTables());  // 最底层物理表
    cache.copyOutputColumnsFrom(child);                     // 子节点输出列
} else {
    // UNION 等其它形态：本轮不处理，保留现状或留 TODO（见 §9.2）
}
```

> **重要（审查 P1.1 修订）**：上面 `if/else` 仅替换原 `:186` 的 `accept(this)` + `:192-197` 的 `lastQueryBlockScope` 读取块。**原 `:202` `tableSourceCacheMap.put(key, cache)`（派生表 `sub` 注册进外层 `tableSourceCacheMap` 的唯一入口）与 `:203` `mergeInvolvedTables(parentScope, cache.getInvolvedTables())` 必须保留**——§4.3 里 `resolveBareColumnRef` 之所以能"扫到 sub scope"全靠 `:202` 这次 put。丢了它 → 派生表不注册 → 外层找不到 sub → 3 个穿透用例继续 FAIL。

**4.1.4** 删除字段 `lastQueryBlockScope`（`:50`）及其所有读写。`QueryScopeCache` 无需改动。

> **Bug B 自动消失**：每个父节点拿到的是「自己的直接子层」（局部返回值），嵌套几层互不干扰，不存在「被孙子层覆盖又无还原」的机制。

### 4.2 A1 + A2-minimal：表达式感知的列解析（统一在解析路径做）

关键：**A1 与 A2 在同一条解析路径里一起做、每一层都跑同一套逻辑**。内层先解析成物理叶（靠 A2），外层命中派生表时把物理叶 flatten 拷上来（靠 A1）。

**4.2.1** `extractSelectColumns` 的调用点（`:252-254`）改为传入表达式：

```java
ColumnLineage lineage = isWildcardExpr(expr)
        ? resolveWildcardColumn(expr)
        : resolveColumnLineage(expr, outputColumnName);   // 旧 resolveColumnSourceTables(outputColumnName) 改为吃 expr
```

**4.2.2** 新方法 `resolveColumnLineage(SQLExpr expr, String outputName)`（统一解析，替代旧 `resolveColumnSourceTables(String)`）：

```
resolveColumnLineage(expr, outputName) -> ColumnLineage:
    lineage = new ColumnLineage(new ColumnInfo(null, outputName), null)

    if 是裸列引用(expr):                       # SQLIdentifierExpr 或 SQLPropertyExpr(name != "*")
        colName   = 表达式里的列名              # A2：取表达式列名，不是 outputName
        tableRef  = 表达式里的表前缀(别名/表名)  # SQLIdentifierExpr → null；SQLPropertyExpr → owner
        resolveBareColumnRef(tableRef, colName, lineage)
        lineage.setTransformation("direct mapping")
    else:
        # 一般表达式 / 常量 / 字面量：本轮保持现状（输出名匹配 + 全表兜底）
        matched = findMatchedTablesByOutputColumn(outputName)
        if matched.isEmpty(): matched = findMatchedTablesByTableMeta(outputName)
        if matched.isEmpty(): matched = collectAllCachedTables()
        addSourceColumnsForTables(matched, outputName, lineage)

    return lineage
```

**4.2.3** `resolveBareColumnRef(tableRef, colName, lineage)`（同时承担 A2 物理解析 + A1 派生表穿透）：

```
resolveBareColumnRef(tableRef, colName, lineage):
    for (key, scope) in tableSourceCacheMap.entrySet():          # key=TableSourceKey, scope=QueryScopeCache
        if tableRef != null 且 key.getReferenceName() != tableRef:
            continue                                             # 带前缀：只看引用命中的来源
        inner = scope.getOutputColumnLineage(colName)
        if inner != null:
            # A1 穿透：派生表能输出 colName → flatten 拷其已解析到物理的 sourceColumns
            copySourcesFromInnerLineage(inner, lineage)          # 复用 :612 拷源列；去重需另加（审查 P1.2，见下）
        else:
            # A2 物理解析：物理表 → 用 key 的 TableInfo 记 物理表.colName
            lineage.addSourceColumn(new ColumnInfo(key.toTableInfo(), colName))
```

> 说明：
> - `copySourcesFromInnerLineage`（`:612`）已实现「拷 sourceColumns + 设 transformation」，是现成死代码，本轮接线复用。**但它与 `ColumnLineage.addSourceColumn`(`:60`) 都不去重**（审查 P1.2）——本轮需在拷完后按 `databaseName+tableName+columnName` 去重（或改其内部去重）。另：其 transformation 设置在裸列路径下多余，统一由 `resolveColumnLineage` 设一次即可（审查 P3）。`tryResolveSubqueryColumn`(`:582`) 由 `resolveBareColumnRef` 覆盖后删除。
> - `toTableInfo()` 在 `TableSourceKey` 上（非 `QueryScopeCache`），故必须遍历 entry、用 `key.toTableInfo()`，与现有 `findMatchedTablesByOutputColumn`(`:352`) 一致。
> - 多物理表裸列（`tableRef==null` 且多表且无派生命中）：得到多个物理候选，列名取自表达式（比现状用输出名更准），无现有测试约束，不构成回归；单表场景（`sourceColumnWithoutPrefixShouldResolveToSingleTable` 等）仍唯一命中。

### 4.3 端到端追踪证明

**单层** `SELECT a FROM (SELECT id AS a FROM t1) sub`：

```
内层（processQueryBlock 返回 child scope）:
  SELECT id AS a：expr=id(SQLIdentifierExpr) → A2 裸列
    resolveBareColumnRef(null, "id", lin): 扫描 → t1(物理), outputColumnLineage("id")=null → 加 t1.id
    transformation="direct mapping"
  child.outputColumnMap["a"] = lineage(sourceColumns=[t1.id])
  cacheSubqueryTableSource: cache.copyOutputColumnsFrom(child) → sub scope.outputColumnMap["a"]=[t1.id]

外层（processQueryBlock）:
  SELECT a：expr=a(SQLIdentifierExpr) → A2 裸列
    resolveBareColumnRef(null, "a", lin): 扫描 → sub scope, outputColumnLineage("a") != null（=[t1.id]）
      → A1 拷 [t1.id] 上来（flatten）
  外层 lineage.sourceColumns = [t1.id], output=a ✓ 测试断言 sources.get(0)=id/t1 通过
```

**多层** `SELECT x FROM (SELECT a AS x FROM (SELECT id AS a FROM t1) i) o`：归纳成立——
最内层 `id`→`[t1.id]`；中层 `a` 命中 `i.outputColumnMap["a"]`→拷 `[t1.id]`；外层 `x` 命中 `o.outputColumnMap["x"]`→拷 `[t1.id]`。每层都是 A2 裸列 + A1 穿透同一套逻辑。

---

## 5. 涉及文件

| 文件 | 改动 |
|---|---|
| `src/main/java/.../visitor/SelectLineageVisitor.java` | 主体：新增 `processQueryBlock`、`resolveColumnLineage`、`resolveBareColumnRef`；改 `visit(MySqlSelectQueryBlock)` 为 shim；改 `cacheSubqueryTableSource` 局部捕获；改 `extractSelectColumns` 传 expr；删 `lastQueryBlockScope` 字段与死字段 `nowScopeColumnLineages`(`:55`)；删/复用死代码 `tryResolveSubqueryColumn`/`copySourcesFromInnerLineage`/`resolveColumnSourceTables(String)` |
| `src/main/java/.../visitor/context/QueryScopeCache.java` | **无改动**（`outputColumnMap`/`involvedTables`/`copyOutputColumnsFrom` 均已具备） |
| `src/test/.../visitor/SelectLineageVisitorTest.java` | 不改断言；现有穿透用例由红转绿 |

---

## 6. 测试策略

> **实现结果（post-implementation，修订原预测）**：全仓 `mvn clean test` = **184/49**；`SelectLineageVisitorTest` = **44/18**。实际必绿交付 **8 个**（原列 6 + `prefixedDerivedColumnPassthrough` + `metadataFilterShouldKeepDerivedTablePassthrough`，后者验元数据+派生表交互）。下文原预测的 43/18、23→18 未计入 A2 元数据去歧义修复与硬化补丁——以本段为准。详见 plan《实现记录》。

- **TDD 顺序**：5 个必绿用例已存在且当前红 → 先确认红 → 实现 → 验证转绿。
- **必绿（本轮交付，共 6 个）**：
  - 穿透目标 3 个：`subqueryColumnPassthroughSingleLevel`（`id AS a`）、`multiLevelSubqueryColumnPassthrough`（两层）、`threeLevelSubqueryColumnPassthrough`（三层）。
  - 附带转绿 2 个（审查 P2.1）：`directMappingTransformation`、`selfJoinWithAliases`——当前 `resolveColumnSourceTables` 从不 `setTransformation`，裸列路径设 `"direct mapping"` 会顺手修绿（`selfJoinWithAliases` 的 `a.id` 经 `getReferenceName()=="a"` 命中 employees-a，已核 `TableSourceKey.java:31`）。
  - 新增直测 1 个（审查 P3，本轮随实现一并补）：`bareColumnAliasShouldResolveToPhysicalColumn`，直测 A2-minimal 别名契约（output=别名、source=物理列）。断言：`SELECT salary AS annual FROM employees` → output=`annual`、source=`employees.salary`、transformation=`direct mapping`。
- **无回归（必须保持绿）**：裸列基础用例 `sourceColumnShouldCarryCorrectTableInfo`、`sourceColumnWithoutPrefixShouldResolveToSingleTable`、`sourceColumnWithDatabasePrefixShouldCarryDatabaseName`；无 FROM 常量 `selectConstantsOnly`、`selectNoFromClauseShouldProduceNoInputTables`；`SELECT *` 通配符用例等。
- **本轮仍红（out-of-scope，不要求转绿，不得新增失败）**：
  - 一般表达式/常量：`arithmeticExpressionShouldExtractAllReferencedColumns`、`nestedFunctionCallShouldExtractAllArguments`、`aggregationWithDistinctShouldExtractColumn`、`multipleExpressionsShouldEachHaveCorrectSources`、`aliasColumnShouldRetainTransformation`、CASE/IF/COALESCE 类、`selectMixedConstantsAndColumns`（审查 P2.2：带 FROM 的常量 `1 AS flag` 仍兜底成 `users.flag`，本轮不修，保持红）。
  - 子查询其它路径：`subqueryInJoinColumnPassthrough`（`total`=SUM）、`subqueryInSelectWithMultipleColumns`、`existsSubquery`/`notExistsSubquery`/`inSubqueryWithCorrelation`、`fromSubqueryPlusScalarSubqueryInSelect`、`subqueryInFromWithSubqueryInWhere`、`havingFilterConditionShouldBeRecorded`。
- **构建命令**：`mvn test -Dtest=SelectLineageVisitorTest` 验证；最终 `mvn clean verify` 过静态检查（PMD/SpotBugs/Checkstyle）。

**实测基线（审查 P2.3，已跑）**：当前 `Tests run: 42, Failures: 23, Errors: 0`。实现后预期 **23 → 18 fail**（5 个现有红转绿），且本轮**新增 1 个直测** `bareColumnAliasShouldResolveToPhysicalColumn`（实现后绿）→ 最终 **43 tests / 18 fail / 25 green**。其余 18 红 + 19 绿状态不变。23 个失败完整名单（`←` 标本轮转绿）：

```
arithmeticExpressionShouldExtractAllReferencedColumns
aggregationWithDistinctShouldExtractColumn
aliasColumnShouldRetainTransformation
caseWhenSimpleFormatWithColumn / caseWhenWithMultipleColumnReferences
coalesceFunctionShouldExtractArguments / ifFunctionShouldExtractArguments
directMappingTransformation                       ← 本轮转绿
existsSubquery / notExistsSubquery / inSubqueryWithCorrelation
fromSubqueryPlusScalarSubqueryInSelect
havingFilterConditionShouldBeRecorded
multiLevelSubqueryColumnPassthrough               ← 本轮转绿
multipleExpressionsShouldEachHaveCorrectSources / nestedFunctionCallShouldExtractAllArguments
selectMixedConstantsAndColumns
selfJoinWithAliases                               ← 本轮转绿
subqueryColumnPassthroughSingleLevel              ← 本轮转绿
subqueryInFromWithSubqueryInWhere / subqueryInJoinColumnPassthrough / subqueryInSelectWithMultipleColumns
threeLevelSubqueryColumnPassthrough               ← 本轮转绿
```

---

## 7. 已敲定的决策

| 决策点 | 结论 |
|---|---|
| transformation 取值 | 裸列引用（含穿透）→ `"direct mapping"`，**在 `resolveColumnLineage` 裸列分支统一设一次**（审查 P3：避免 `copySourcesFromInnerLineage` 再设一次造成双重赋值）；一般表达式路径暂不设置（保持现状） |
| 裸列 + 多表无 meta 回退 | 保留现状「全表当来源」语义（`resolveBareColumnRef` 多 scope 自然得到多物理候选） |
| 穿透拷来源列去重 | 按 `databaseName+tableName+columnName` 去重，避免 `sub.total` 类重复（审查 P1.2：`:612`/`addSourceColumn` 都不去重，属净新增逻辑） |
| flatten vs chain | **flatten**：单层测试要求 `sources.get(0)` 直接是物理叶；多层测试用 `findLeafSource`/链遍历兼容 flatten。统一 flatten |
| `outputColumn` 是否带表 | 否：`outputColumn` 仅列名（别名或列名），与现状一致；物理信息只进 `sourceColumns` |

---

## 8. 风险与边界

- **递归顺序依赖**：flatten 成立的前提是「内层先解析完」。当前 `cacheSubqueryTableSource` 确实先 `processQueryBlock` 内层再处理外层——重构后该顺序必须保留（实现时勿打乱）。
- **UNION 派生表**：`inner instanceof MySqlSelectQueryBlock` 不成立时会进 else 分支；本轮至少不得抛 `ClassCastException`，需保留兜底或显式 TODO。
- **关联子查询 / scalar subquery in SELECT**（如 `fromSubqueryPlusScalarSubqueryInSelect`）：scalar 子查询出现在 SELECT 列表而非 FROM，走的是表达式路径，本轮不解析其内部列（out-of-scope），不得破坏其输入表收集。
- **死代码删除范围**：`tryResolveSubqueryColumn`、`resolveColumnSourceTables(String)` 被 `resolveBareColumnRef`/`resolveColumnLineage` 取代后删除；`copySourcesFromInnerLineage` **保留并接线**（实现中改为 3 参带去重版，transformation 统一由 `resolveColumnLineage` 设）；死字段 `nowScopeColumnLineages`(`:55`，全仓零引用) 一并删。
- **【实现偏离】死码簇已全删**：原计划"暂留"的 `collectReferencedColumns`/`extractReferencedColumns`/`resolveSingleTable`/`resolveTableByReference`，因 `extractReferencedColumns` 触发 PMD `UnusedPrivateMethod` 挡构建且确为死码，实现中已**全部删除**（commit `5fcb1d9`）。§9.1 一般表达式将来须新写遍历器，不复用它们。

---

## 9. 未来工作（同契约的后续步骤）

### 9.1 一般表达式物理列提取
扩展 `resolveColumnLineage` 的 else 分支：**新写**一个表达式遍历器（处理 `SQLBinaryOpExpr`/`SQLMethodInvokeExpr`/`SQLCaseExpr`/`SQLAggregateExpr`，收集叶子列引用）——原 `collectReferencedColumns` 死码簇已在本次清理中删除（见 §8 偏离），不复用。逐个叶子走 `resolveBareColumnRef`（复用本轮 A1+A2），`transformation = expr.toString()`。一次性转绿算术/函数/CASE/聚合类测试。

### 9.2 UNION 派生表
`processQueryBlock` 之外补 `SQLUnionQuery` 分支：按分支收集各 `MySqlSelectQueryBlock` 的输出，按列位置合并 lineage。

### 9.3 JOIN 精度
自连接别名（`a.id`）、前缀忽略等（`SelectLineageJoinTest` §11）。

---

## 10. 代码索引（实现时定位用）

| 关注点 | 位置 |
|---|---|
| `lastQueryBlockScope` 字段/写/读 | `SelectLineageVisitor.java:50 / :98 / :192-197` |
| `visit(MySqlSelectQueryBlock)` | `:90-119` |
| `cacheSubqueryTableSource` | `:170-204`（`:186` accept 调用点） |
| `extractSelectColumns` / 调用解析 | `:240-268`（`:252-254` 分支、`:254` 传输出名） |
| `resolveColumnSourceTables(String)` | `:327-342` |
| `findMatchedTablesByOutputColumn` | `:348-356`（`:351` 布尔判定丢 lineage） |
| `addSourceColumnsForTables` | `:405-409`（`:407` 盖派生别名） |
| 死代码：`tryResolveSubqueryColumn` / `copySourcesFromInnerLineage` / `collectReferencedColumns` | `:582 / :612 / :650` |
| 可复用：`resolveSingleTable` / `resolveTableByReference` | `:625 / :635` |
| `QueryScopeCache`：`outputColumnMap`/`copyOutputColumnsFrom`/`involvedTables` | `QueryScopeCache.java:53 / :90 / :47` |
