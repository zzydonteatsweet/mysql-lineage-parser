# 子查询列血缘穿透修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `SELECT ... FROM (SELECT ...)` 的外层列血缘穿透派生表，追到底层物理表.物理列；输出列保留别名。

**Architecture:** 三块改动，全在 `SelectLineageVisitor.java`（+1 个测试方法）：**B** 把查询块处理抽成 `processQueryBlock` 返回 scope，删掉 `lastQueryBlockScope` 寄存器（行为不变的纯重构）；**A2** 新增 `resolveColumnLineage`/`resolveBareColumnRef`，裸列引用按表达式解析到物理列；**A1** 在 `resolveBareColumnRef` 命中派生表时 flatten 拷内层已解析的物理源列。内层先于外层解析，故外层拷到的就是物理叶。

**Tech Stack:** Java 8，Alibaba Druid SQL Parser，Spring Boot 2.6.13，JUnit 5，Maven（PMD/SpotBugs/Checkstyle）。

**Spec:** `docs/superpowers/specs/2026-06-19-subquery-column-passthrough-design.md`

**基线（实测，`mvn clean test` 全仓）：** `181 tests / 59 fail`。其中 **17 个与本次 visitor 无关**（`DruidNativeParserTest` 15 + `CreateLineageParserImplTest` 2，本就红）；visitor 相关 42 个红分散在 6 个类：`SelectLineageVisitorTest` 23、`FirstTier` 9、`Simple` 4、`Join` 3、`Complex` 3（`Alias` 0）。**这 6 个类都走 `SelectLineageVisitor`**（直接 `new` 或经 `MysqlLineageParserImpl`→strategy→visitor），故 Task 2/3 改动会波及它们——必须全量验证。

> ⚠️ `mvn clean verify`（含测试）会因那 17 个无关红用例 **BUILD FAILURE**，**不作为完成门槛**。本计划：静态检查用 `mvn clean verify -DskipTests`，回归用 `mvn clean test`。**必须 `clean`**——否则 `target/` 里陈旧 `.class`（如已删的 `ProbeAliasTest`）会被 surefire 拾起、虚增计数。

**目标终点：** `SelectLineageVisitorTest` **43/18**（新增 1 测试 + 5 现有红转绿）；全仓失败数**只降不升**（无回归）。

---

## File Structure

| 文件 | 责任 | 本计划改动 |
|---|---|---|
| `src/main/java/com/zzy/mysqllineageparser/visitor/SelectLineageVisitor.java` | SELECT AST 遍历、列血缘解析 | Task 1/2/3 全部在此 |
| `src/test/java/com/zzy/mysqllineageparser/visitor/SelectLineageVisitorTest.java` | SELECT 解析测试 | Task 2 新增 1 个测试方法 |

`QueryScopeCache.java`、`TableSourceKey.java`、模型类**不改**（已具备 `outputColumnMap`/`copyOutputColumnsFrom`/`getReferenceName()`/`toTableInfo()`）。

---

## 约定

- 快速单类（仅迭代用）：`mvn test -Dtest=SelectLineageVisitorTest -Dpmd.skip=true -Dspotbugs.skip=true -Dcheckstyle.skip=true`
- 全量测试（验证回归，**必须 `clean`** 去陈旧 `.class`）：`mvn clean test -Dpmd.skip=true -Dspotbugs.skip=true -Dcheckstyle.skip=true`
- 静态检查（隔离无关测试失败）：`mvn clean verify -DskipTests` —— 注意：`mvn clean verify` 不加 `-DskipTests` 会因 17 个无关红用例 BUILD FAILURE，**不作门槛**。
- 取合计行：`... | grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$"`（只匹配无 `Time elapsed` 的 reactor 合计行）。
- 所有提交在 `main` 分支（用户已确认）。
- 提交信息末尾加 `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`。

---

## Task 1: B — 重构 scope 交接为递归回传（纯重构，行为不变）

**Files:**
- Modify: `src/main/java/com/zzy/mysqllineageparser/visitor/SelectLineageVisitor.java`（字段 `:42-55` 区间删两个：`lastQueryBlockScope` 约 `:47-50`、`nowScopeColumnLineages` 约 `:52-55`；`visit(MySqlSelectQueryBlock) :90-119`；`cacheSubqueryTableSource :170-204`）

本任务**不新增测试**——全仓现有测试是安全网：重构前后 `mvn clean test` 必须仍是 **181/59、逐用例状态完全不变**。含一个对 UNION 派生表的行为变化（旧"拷陈旧末块输出"→ 新"跳过"，更对），靠全量验证兜底——见 Step 4 注。

- [ ] **Step 1: 记录重构前全量基线**

Run:
```bash
mvn clean test -Dpmd.skip=true -Dspotbugs.skip=true -Dcheckstyle.skip=true 2>&1 | grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$"
```
Expected（合计行）: `Tests run: 181, Failures: 59, Errors: 0, Skipped: 0`。记下，重构后逐用例必须完全不变。

- [ ] **Step 2: 新增 `processQueryBlock` 方法**

在 `visit(MySqlSelectQueryBlock)` 方法**之后**（当前 `:119` 的 `}` 之后）插入：

```java
    /**
     * 处理一个查询块：建独立 scope → FROM（建表来源缓存）→ SELECT（关联列血缘）→ WHERE。
     * 返回它创建的 scope，使子查询可通过返回值把 scope 交给父层，
     * 不再依赖 lastQueryBlockScope 寄存器。
     *
     * @param x           查询块
     * @param parentScope 父作用域（最外层为 null；派生表内层为派生表 scope）
     * @return 本次查询块创建的 scope
     */
    private QueryScopeCache processQueryBlock(MySqlSelectQueryBlock x, QueryScopeCache parentScope) {
        // 每个查询块使用独立的 tableSourceCache，避免嵌套子查询破坏外层缓存
        Map<TableSourceKey, QueryScopeCache> outerCache = new LinkedHashMap<>(tableSourceCacheMap);
        tableSourceCacheMap.clear();

        QueryScopeCache savedScope = currentScope;
        QueryScopeCache scope = createQueryScope(parentScope, "query_result");
        currentScope = scope;

        // 1. 先访问 FROM，将表来源写入当前层 tableSourceCache
        if (x.getFrom() != null) {
            collectFromAndCache(x.getFrom(), scope);
        }
        // 2. 再访问 SELECT 字段，利用缓存关联列血缘
        List<ColumnLineage> queryLineages = new ArrayList<>();
        extractSelectColumns(x, queryLineages);
        // 3. 最后处理 WHERE 过滤条件
        if (x.getWhere() != null) {
            applyWhereFilter(x.getWhere().toString(), queryLineages);
        }

        currentScope = savedScope;
        // 恢复外层缓存
        tableSourceCacheMap.clear();
        tableSourceCacheMap.putAll(outerCache);
        return scope;
    }
```

- [ ] **Step 3: 把 `visit(MySqlSelectQueryBlock)` 退化为 shim**

把现有 `visit(MySqlSelectQueryBlock)` 整个方法体（当前 `:90-119`）替换为：

```java
    @Override
    public boolean visit(MySqlSelectQueryBlock x) {
        // 最外层入口：parentScope=null，nestingLevel 仍为 0，
        // extractSelectColumns 照常把结果写进 lineageResult。
        // 子查询不走这里，由 cacheSubqueryTableSource 直接调 processQueryBlock。
        processQueryBlock(x, null);
        return false;
    }
```

- [ ] **Step 4: 重构 `cacheSubqueryTableSource` 为局部捕获子层 scope**

把现有 `cacheSubqueryTableSource` 方法体（当前 `:170-204`）整个替换为：

```java
    /**
     * 缓存子查询派生表来源：直接调用 processQueryBlock 拿到内层 scope（局部返回值），
     * 把内层的物理表与输出列折进派生表 scope。不再使用 lastQueryBlockScope 寄存器。
     */
    private void cacheSubqueryTableSource(SQLSubqueryTableSource tableSource, QueryScopeCache parentScope) {
        String derivedAlias = tableSource.getAlias() != null ? tableSource.getAlias() : "subquery";

        TableSourceKey key = new TableSourceKey(null, derivedAlias, derivedAlias);
        QueryScopeCache cache = createQueryScope(parentScope, derivedAlias);

        SQLSelectQuery inner = (tableSource.getSelect() != null) ? tableSource.getSelect().getQuery() : null;
        if (inner instanceof MySqlSelectQueryBlock) {
            nestingLevel++;
            // 递归解析内层子查询，直接拿回它建的 scope（递归回传，替代 lastQueryBlockScope）
            QueryScopeCache child = processQueryBlock((MySqlSelectQueryBlock) inner, cache);
            nestingLevel--;

            cache.addSubQueryCache(child);
            mergeInvolvedTables(cache, child.getInvolvedTables());
            // 将内层查询的输出列传递到派生表 scope，供外层穿透查找
            cache.copyOutputColumnsFrom(child);
        }
        // else: UNION 等其它派生表形态本轮不解析（spec §9.2），留待后续；此处不抛异常、不破坏外层缓存

        // 【审查 P1.1】必须保留：派生表注册进外层 tableSourceCache 的唯一入口
        tableSourceCacheMap.put(key, cache);
        mergeInvolvedTables(parentScope, cache.getInvolvedTables());
    }
```

> 注意：`processQueryBlock` 已自管 `currentScope` 与 `tableSourceCacheMap` 的 save/restore，故本方法**不再**需要原 `:179-198` 的 savedScope/outerCache 保存还原。`:202` 的 `put` 与 `:203` 的 `mergeInvolvedTables(parentScope, ...)` **已保留**（丢了它们 → 派生表不注册 → 外层找不到 sub → 穿透全 FAIL）。
>
> **审查 P1（行为变化，非纯保持）**：对 UNION 派生表（`FROM (SELECT ... UNION ...) sub`），旧代码 `if (lastQueryBlockScope != null)` 会拷到**陈旧的末个 union 块**输出；新代码 `inner instanceof MySqlSelectQueryBlock` 对 UNION 直接跳过（不拷）。这是预期改善（旧行为本就错），但属行为变化——Step 6 全量验证若发现 UNION 派生表相关用例状态变动，即此所致，需逐用例确认是转绿而非回归。

- [ ] **Step 5: 删除 `lastQueryBlockScope` 与 `nowScopeColumnLineages` 字段**

删除以下两段字段声明（当前 `:42-55`）：

```java
    /**
     * 最近一次 visit(MySqlSelectQueryBlock) 创建的作用域（供子查询挂载）
     */
    private QueryScopeCache lastQueryBlockScope;
```

```java
    /**
     * 当前查询域涉及到的血缘
     */
    private List<ColumnLineage> nowScopeColumnLineages;
```

> 确认无残留引用：`lastQueryBlockScope` 的写（旧 `:98`）与读（旧 `:192-197`）已随 Step 3/4 移除；`nowScopeColumnLineages` 全仓零引用。

- [ ] **Step 6: 编译并跑全量测试，确认行为不变**

Run:
```bash
mvn clean test -Dpmd.skip=true -Dspotbugs.skip=true -Dcheckstyle.skip=true 2>&1 | grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$"
```
Expected（合计行）: `Tests run: 181, Failures: 59, Errors: 0, Skipped: 0` —— **与 Step 1 逐用例完全一致**（纯重构不应改变任何 pass/fail；唯一允许的差异是 Step 4 注里的 UNION 派生表行为变化，若出现需逐用例确认是转绿）。若失败数变动，回查 Step 2-4。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/zzy/mysqllineageparser/visitor/SelectLineageVisitor.java
git commit -m "refactor: SELECT visitor 改递归回传 scope，移除 lastQueryBlockScope 寄存器" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: A2 — 裸列引用按表达式解析到物理列

**Files:**
- Modify: `src/main/java/com/zzy/mysqllineageparser/visitor/SelectLineageVisitor.java`（`extractSelectColumns :252-254`；新增方法；删除 `resolveColumnSourceTables(String) :316-342`、`tryResolveSubqueryColumn :574-607`；均含 javadoc）
- Test: `src/test/java/com/zzy/mysqllineageparser/visitor/SelectLineageVisitorTest.java`（新增 1 个方法）

- [ ] **Step 1: 写失败测试**

在 `SelectLineageVisitorTest.java` 中新增（建议放在 `sourceColumnWithDatabasePrefixShouldCarryDatabaseName` 方法之后，约 `:83`）：

```java
    @Test
    void bareColumnAliasShouldResolveToPhysicalColumn() {
        // 直测 A2-minimal：裸列别名 output≠expr。output 取别名，source 必须是物理列。
        String sql = "SELECT salary AS annual FROM employees";
        LineageResult result = parse(sql);

        assertEquals(1, result.getColumnLineages().size());
        ColumnLineage cl = result.getColumnLineages().get(0);
        assertEquals("annual", cl.getOutputColumn().getColumnName(), "output 取别名");
        assertEquals(1, cl.getSourceColumns().size());
        ColumnInfo src = cl.getSourceColumns().get(0);
        assertEquals("salary", src.getColumnName(), "source 取表达式里的物理列名，非输出别名");
        assertEquals("employees", src.getTable().getTableName());
        assertEquals("direct mapping", cl.getTransformation());
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
mvn test -Dtest=SelectLineageVisitorTest#bareColumnAliasShouldResolveToPhysicalColumn -Dpmd.skip=true -Dspotbugs.skip=true -Dcheckstyle.skip=true 2>&1 | grep -E "Tests run|expected"
```
Expected: FAIL —— `expected: <salary> but was: <annual>`（当前代码用输出名 `annual` 当列名）。`Tests run: 1, Failures: 1`。

- [ ] **Step 3: 改 `extractSelectColumns` 调用点，改吃表达式**

把 `extractSelectColumns` 内的分支（当前 `:252-254`）：

```java
            ColumnLineage lineage = isWildcardExpr(expr)
                    ? resolveWildcardColumn(expr)
                    : resolveColumnSourceTables(outputColumnName);
```

改为：

```java
            ColumnLineage lineage = isWildcardExpr(expr)
                    ? resolveWildcardColumn(expr)
                    : resolveColumnLineage(expr, outputColumnName);
```

- [ ] **Step 4: 新增 `resolveColumnLineage` + 裸列判定/取值辅助方法**

在 `resolveColumnSourceTables(String)` 方法**之前**（当前 `:327` 前）新增以下方法：

```java
    /**
     * 判断是否为裸列引用：SQLIdentifierExpr（col）或 SQLPropertyExpr 且 name 非 "*"（t.col）。
     */
    private boolean isBareColumnRef(SQLExpr expr) {
        if (expr instanceof SQLIdentifierExpr) {
            return true;
        }
        if (expr instanceof SQLPropertyExpr) {
            return !"*".equals(stripBackticks(((SQLPropertyExpr) expr).getName()));
        }
        return false;
    }

    /**
     * 取裸列引用里的列名（来自表达式，非输出别名）。
     */
    private String bareColumnName(SQLExpr expr) {
        if (expr instanceof SQLIdentifierExpr) {
            return stripBackticks(((SQLIdentifierExpr) expr).getName());
        }
        return stripBackticks(((SQLPropertyExpr) expr).getName());
    }

    /**
     * 取裸列引用里的表前缀（别名/表名）。SQLIdentifierExpr 返回 null；SQLPropertyExpr 返回 owner 文本。
     */
    private String bareTableRef(SQLExpr expr) {
        if (expr instanceof SQLIdentifierExpr) {
            return null;
        }
        SQLExpr owner = ((SQLPropertyExpr) expr).getOwner();
        return owner != null ? stripBackticks(owner.toString()) : null;
    }

    /**
     * 构建列血缘并解析其来源，按表达式形态分流：
     * - 裸列引用：A2 按表达式列名解析到物理列（resolveBareColumnRef），transformation="direct mapping"。
     * - 一般表达式/常量/字面量：保持现状（输出名匹配 + 全表兜底），transformation 暂不设置。
     *
     * @param expr       SELECT 项的表达式
     * @param outputName 输出列名（别名优先）
     */
    private ColumnLineage resolveColumnLineage(SQLExpr expr, String outputName) {
        ColumnInfo outputColumn = new ColumnInfo(null, outputName);
        ColumnLineage lineage = new ColumnLineage(outputColumn, (TableInfo) null);

        if (isBareColumnRef(expr)) {
            String colName = bareColumnName(expr);
            String tableRef = bareTableRef(expr);
            resolveBareColumnRef(tableRef, colName, lineage);
            lineage.setTransformation("direct mapping");
        } else {
            // 一般表达式/常量/字面量：本轮保持现状
            List<TableInfo> matchedTables = findMatchedTablesByOutputColumn(outputName);
            if (matchedTables.isEmpty()) {
                matchedTables = findMatchedTablesByTableMeta(outputName);
            }
            if (matchedTables.isEmpty()) {
                matchedTables = collectAllCachedTables();
            }
            addSourceColumnsForTables(matchedTables, outputName, lineage);
        }
        return lineage;
    }
```

- [ ] **Step 5: 新增 `resolveBareColumnRef`（本任务只做 A2 物理分支）+ 去重键**

在 `resolveColumnLineage` 之后新增（A1 穿透分支留到 Task 3）：

```java
    /**
     * 解析裸列引用到 sourceColumns：
     * 本任务（A2）只做物理分支——按 tableRef 过滤来源，记 物理表.colName。
     * Task 3 会补上「命中派生表输出列时 flatten 拷内层源列」的 A1 分支。
     * 按 databaseName+tableName+columnName 去重（审查 P1.2：addSourceColumn 不去重）。
     */
    private void resolveBareColumnRef(String tableRef, String colName, ColumnLineage lineage) {
        Set<String> seen = new HashSet<>();
        for (Map.Entry<TableSourceKey, QueryScopeCache> entry : tableSourceCacheMap.entrySet()) {
            if (tableRef != null && !tableRef.equals(entry.getKey().getReferenceName())) {
                continue;   // 带前缀：只看引用命中的来源
            }
            ColumnInfo src = new ColumnInfo(entry.getKey().toTableInfo(), colName);
            if (seen.add(sourceDedupKey(src))) {
                lineage.addSourceColumn(src);
            }
        }
    }

    /**
     * 来源列去重键：databaseName|tableName|columnName（审查 P1.2）。
     */
    private static String sourceDedupKey(ColumnInfo c) {
        TableInfo t = c.getTable();
        String db = (t != null && t.getDatabaseName() != null) ? t.getDatabaseName() : "";
        String tbl = (t != null && t.getTableName() != null) ? t.getTableName() : "";
        String col = (c.getColumnName() != null) ? c.getColumnName() : "";
        return db + "|" + tbl + "|" + col;
    }
```

> `Set`/`HashSet` 由 `import java.util.*;`（`:16`）覆盖；`SQLIdentifierExpr`/`SQLPropertyExpr` 由 `import com.alibaba.druid.sql.ast.expr.*;`（`:4`）覆盖；`SQLSelectQuery` 由现有 `import com.alibaba.druid.sql.ast.statement.*;`（`:5`）通配覆盖（Task 1 已用，可编译）。

- [ ] **Step 6: 删除被取代的死代码**

删除 `resolveColumnSourceTables(String colName)` 整个方法（当前 `:316-342`，含其上 javadoc）——已被 `resolveColumnLineage` 取代。

删除 `tryResolveSubqueryColumn(...)` 整个方法（当前 `:574-607`，含其上 javadoc）——已被 `resolveBareColumnRef` 取代。

> 保留：`findMatchedTablesByOutputColumn`/`findMatchedTablesByTableMeta`/`collectAllCachedTables`/`addSourceColumnsForTables`（`resolveColumnLineage` 的 else 分支仍用）；`copySourcesFromInnerLineage`（Task 3 接线）；`collectReferencedColumns`/`extractReferencedColumns`/`resolveSingleTable`/`resolveTableByReference`（spec §9.1 未来用）。

- [ ] **Step 7: 跑全量测试，确认转绿且零回归**

Run:
```bash
mvn clean test -Dpmd.skip=true -Dspotbugs.skip=true -Dcheckstyle.skip=true 2>&1 | grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$"
```
Expected（合计行）: `Tests run: 182, Failures: 57, Errors: 0, Skipped: 0`（181 + 1 新测试；59 − 2 转绿）。
- `SelectLineageVisitorTest` 应到 **43/21**：新增 `bareColumnAliasShouldResolveToPhysicalColumn` 绿；附带转绿 `directMappingTransformation`、`selfJoinWithAliases`（裸列路径设 `"direct mapping"`）；3 个穿透用例**仍红**（等 Task 3）。
- **零回归门槛**：合计失败数 **≤ 57**（低于 57 表示其它 visitor 类也有用例转绿，bonus）。若 > 57，说明改动让某原本绿用例变红（可能在 `SelectLineageSimple/Complex/FirstTier/Join`），用 `mvn clean test -Dtest=<类>` 逐类定位。

若 `directMappingTransformation`/`selfJoinWithAliases` 未转绿，检查 Step 4 是否对裸列设了 `"direct mapping"`。

- [ ] **Step 8: 提交**

```bash
git add src/main/java/com/zzy/mysqllineageparser/visitor/SelectLineageVisitor.java src/test/java/com/zzy/mysqllineageparser/visitor/SelectLineageVisitorTest.java
git commit -m "feat: 裸列引用按表达式解析到物理列（A2），裸列 transformation=direct mapping" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: A1 — 派生表列血缘 flatten 穿透

**Files:**
- Modify: `src/main/java/com/zzy/mysqllineageparser/visitor/SelectLineageVisitor.java`（`resolveBareColumnRef`、`copySourcesFromInnerLineage :612`）

- [ ] **Step 1: 改 `copySourcesFromInnerLineage` 为「带去重、不动 transformation」**

先改被调方（改签名 + 去重），保证下一步改调用方后即可编译。把现有 `copySourcesFromInnerLineage(ColumnLineage innerLineage, ColumnLineage outerLineage)`（当前 `:609-620`，含 javadoc）整个替换为：

```java
    /**
     * 从内层查询 lineage 复制源列到外层 lineage（A1 穿透用）：
     * - 按传入的 seen 集合去重（与 resolveBareColumnRef 共享同一集合，跨多来源统一去重）。
     * - 不在此设 transformation：裸列路径统一由 resolveColumnLineage 设 "direct mapping"（审查 P3）。
     */
    private void copySourcesFromInnerLineage(ColumnLineage innerLineage, ColumnLineage outerLineage, Set<String> seen) {
        if (innerLineage == null || innerLineage.getSourceColumns() == null) {
            return;
        }
        for (ColumnInfo source : innerLineage.getSourceColumns()) {
            if (source != null && seen.add(sourceDedupKey(source))) {
                outerLineage.addSourceColumn(source);
            }
        }
    }
```

> 此时该方法变 3 参、暂无调用方（Task 2 已删其旧调用方 `tryResolveSubqueryColumn`），可正常编译（私有方法暂未使用不报错）。

- [ ] **Step 2: 给 `resolveBareColumnRef` 加 A1 穿透分支**

再改调用方。把 Task 2 写的 `resolveBareColumnRef` 整个方法体替换为（加 `inner != null` 分支，调用上一步的 3 参版本）：

```java
    /**
     * 解析裸列引用到 sourceColumns（A2 物理 + A1 派生表穿透，统一去重）：
     * - 命中派生表输出列（inner != null）：A1 flatten 拷其已解析到物理的 sourceColumns
     *   （内层先于外层解析，故 inner 的 sourceColumns 已是物理叶）。
     * - 否则物理表：A2 记 物理表.colName。
     * 按 databaseName+tableName+columnName 去重（审查 P1.2）。
     */
    private void resolveBareColumnRef(String tableRef, String colName, ColumnLineage lineage) {
        Set<String> seen = new HashSet<>();
        for (Map.Entry<TableSourceKey, QueryScopeCache> entry : tableSourceCacheMap.entrySet()) {
            if (tableRef != null && !tableRef.equals(entry.getKey().getReferenceName())) {
                continue;   // 带前缀：只看引用命中的来源
            }
            ColumnLineage inner = entry.getValue().getOutputColumnLineage(colName);
            if (inner != null) {
                // A1 穿透：派生表能输出 colName → flatten 拷内层物理源列
                copySourcesFromInnerLineage(inner, lineage, seen);
            } else {
                // A2 物理解析
                ColumnInfo src = new ColumnInfo(entry.getKey().toTableInfo(), colName);
                if (seen.add(sourceDedupKey(src))) {
                    lineage.addSourceColumn(src);
                }
            }
        }
    }
```

- [ ] **Step 3: 跑全量测试，确认 3 个穿透用例转绿、零回归、到达终点**

Run:
```bash
mvn clean test -Dpmd.skip=true -Dspotbugs.skip=true -Dcheckstyle.skip=true 2>&1 | grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$"
```
Expected（合计行）: `Tests run: 182, Failures: 54, Errors: 0, Skipped: 0`（57 − 3 转绿）。
- `SelectLineageVisitorTest` 应到 **43/18**：3 个穿透用例转绿（`subqueryColumnPassthroughSingleLevel`、`multiLevelSubqueryColumnPassthrough`、`threeLevelSubqueryColumnPassthrough`）。
- **零回归门槛**：合计失败数 **≤ 54**（低于 54 表示其它 visitor 类也受益，bonus）。若 > 54，有原本绿用例变红，逐类定位。

若穿透用例仍红：检查 `cacheSubqueryTableSource` 的 `cache.copyOutputColumnsFrom(child)` 是否保留（Task 1 Step 4）；检查内层 `outputColumnMap` 是否在 Task 2 后已含物理源列（A2 应已让内层 `id AS a` → `t1.id`）。

- [ ] **Step 4: 静态检查（隔离无关测试失败）**

Run:
```bash
mvn clean verify -DskipTests 2>&1 | tail -20
```
Expected: BUILD SUCCESS（PMD/SpotBugs/Checkstyle 全过；`-DskipTests` 跳过测试执行，故 17 个无关红用例不挡构建）。
> 不要用裸 `mvn clean verify`：它会跑全量测试并因 `DruidNativeParserTest`/`CreateLineageParserImplTest` 的 17 个无关红用例 BUILD FAILURE，与本次工作无关。测试回归已在 Step 3 的 `mvn clean test` 验过。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/zzy/mysqllineageparser/visitor/SelectLineageVisitor.java
git commit -m "feat: 子查询派生表列血缘 flatten 穿透到物理源列（A1）" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## 实现记录（实现后补，与原计划的偏离）

本计划已实现，5 个提交落在 `main`：`d505454`(B)、`bfaec7e`(A2)、`85d25a7`(A1)、`5fcb1d9`(死代码清理)、`1c6cc05`(硬化)。相对原计划的偏离（均为 review 驱动，已在代码落地）：

1. **A2 增加元数据去歧义**（原计划 `resolveBareColumnRef` 是纯物理遍历）：无前缀裸列 + 多表 + 有 `tableMetaSupport` 时，按 `tableHasColumn` 筛掉不含该列的物理表（空则兜底全留）。原因：review 发现 `SelectLineageJoinTest.bareColumnResolvesByMetadata` 回归——旧路径用元数据去歧义，纯物理遍历丢了。
2. **删除死代码簇**（原 spec §8 说"暂留"）：`extractReferencedColumns`/`collectReferencedColumns`/`resolveSingleTable`/`resolveTableByReference` 全删。原因：`extractReferencedColumns` 触发 PMD `UnusedPrivateMethod` 挡构建，且确为死码；§9.1 一般表达式将来重写自己的遍历器。
3. **硬化（final review 发现的 latent bug）**：元数据过滤改为**保留派生表候选**——`tableHasColumn(派生别名, col)` 查真实元数据查不到，会误删派生表导致 A1 穿透丢失。修法：`scope.getOutputColumnLineage(col) != null`（派生表能输出该列）的候选一并保留。补 2 测试：`prefixedDerivedColumnPassthrough`（VisitorTest）、`metadataFilterShouldKeepDerivedTablePassthrough`（JoinTest，red-before/green-after 验证）。
4. **静态检查判据修正**：原"`mvn clean verify -DskipTests` BUILD SUCCESS"不可达——全仓有预存 Checkstyle(`sun_checks.xml` 635) + SpotBugs(`EI_EXPOSE_REP` 52) 债，遍布模型类，非本次引入。实际判据：PMD 0 违规（本次还修了预存 PMD）、本次无新增 SpotBugs/Checkstyle、测试零回归。
5. **最终测试数**：全仓 `mvn clean test` = **184/49**（baseline 181/59 → +3 测试、-10 失败、0 绿→红）；`SelectLineageVisitorTest` = **44/18**。原计划写的 182/49、43/18 是未计入元数据修复 + 2 新测试 + 硬化前的预测。

---

## 完成判据（按实现结果修订）

1. **静态检查**：PMD 0 违规（实测达成，且修复了预存 PMD）；本次改动未新增 SpotBugs/Checkstyle 违规。全仓预存 Checkstyle 635 + SpotBugs 52 为历史债（非本次引入），`mvn clean verify` 仍因它们 BUILD FAILURE，不在本次范围。
2. **目标类达标**：`SelectLineageVisitorTest` → **44 tests / 18 fail**（转绿 5：3 穿透 + `directMappingTransformation` + `selfJoinWithAliases`；新增绿 2：`bareColumnAliasShouldResolveToPhysicalColumn` + `prefixedDerivedColumnPassthrough`）。
3. **全仓零回归**：`mvn clean test` = **184/49**，0 个绿用例变红。

附：
- `SELECT a FROM (SELECT id AS a FROM t1) sub` 外层 `a` 的 sourceColumns = `[t1.id]`（output 仍为 `a`）。
- 已删除：`lastQueryBlockScope`、`nowScopeColumnLineages`、`tryResolveSubqueryColumn`、`resolveColumnSourceTables(String)`、`extractReferencedColumns`、`collectReferencedColumns`、`resolveSingleTable`、`resolveTableByReference`。
