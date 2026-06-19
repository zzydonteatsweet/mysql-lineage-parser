# 子查询列血缘穿透 Implementation Plan 审查记录

- 日期：2026-06-19
- 状态：待修订 plan
- 审查对象：`docs/superpowers/plans/2026-06-19-subquery-column-passthrough.md`
- 关联：
  - spec：`docs/superpowers/specs/2026-06-19-subquery-column-passthrough-design.md`
  - spec 审查：`docs/superpowers/specs/2026-06-19-subquery-column-passthrough-design-review.md`
- 审查方式：逐 Task 比对 `SelectLineageVisitor.java` 实际代码 + 实跑全量测试 baseline + grep 核实死代码/引用

---

## 0. 结论

**计划的 Task 分解与代码是对的**——已追踪 Task 1 重构对 `SelectLineageVisitorTest` 行为保持、`43→21→18` 算术正确、且正确落地了 spec 审查的两条修订（P1.1 保留 `:202 put`；P1.2 去重写成**新代码**而非依赖 `:612`）。

但 plan 的**验证网只罩了 8 个测试类里的 1 个**，且把一个注定失败的 `mvn clean verify` BUILD SUCCESS 写进了完成判据——这是主要问题。

---

## 1. 全量 baseline（实跑，供对照）

```
全仓:  Tests run: 184, Failures: 62, Errors: 0
  └ SelectLineageVisitorTest      42 / 23 fail   ← 本计划唯一验证的类
  └ CreateLineageParserImplTest   19 / 2
  └ DruidNativeParserTest         26 / 15
  └ SelectLineageComplexTest      14 / 3
  └ SelectLineageFirstTierTest    29 / 9
  └ SelectLineageSimpleTest       18 / 4
  └ ProbeAliasTest                 3 / 3
  └ SelectLineageJoinTest         30 / 3
```

62 个失败里只有 23 个在 `SelectLineageVisitorTest`，**其余 39 个分散在 7 个其它类、与本次工作无关**。

---

## 2. P0 — 完成判据不可达成 + 验证范围不足

### P0.1 `mvn clean verify` BUILD SUCCESS 永远达不到

因全仓有 39 个无关红用例，`mvn clean verify`（含全量测试）**照计划做完必然 BUILD FAILURE**。但 Task 3 Step 4 与"完成判据"都把它当硬门槛 → 执行者会误判任务失败、或去追无关红用例。Step 4"若其它测试类有额外数字，以 43/18 为准"与同行"Expected: BUILD SUCCESS"**自相矛盾**。

### P0.2 每个 Task 只验证 `SelectLineageVisitorTest`（`-Dtest=SelectLineageVisitorTest`）

`SelectLineageFirstTierTest` / `JoinTest` / `ComplexTest` / `SimpleTest` **同样走 `SelectLineageVisitor`**——本计划对 visitor 的改动（尤其 Task 2 裸列解析、Task 3 穿透）会**波及这些类**，可能转绿也可能回归。计划全程不查，导致：

- Task 1"纯重构，行为不变"只对 1/8 类验证过；
- Task 2/3 的 no-regression 声明没有安全网。

### P0 修法（很便宜）

每个验证步骤改跑**全量**（或至少 `-Dtest='SelectLineage*'`），把总数钉在 184/62 baseline：

- **Task 1（纯重构）** → 全量必须**仍 184/62，一个不动**。
- **Task 2** → 预测全量降幅（`SelectLineageVisitorTest` 23→21 确定；其它类可能也动，需预测 delta）。
- **Task 3** → 全量只降不升，降幅 = 预测转绿数。

"完成判据"改为三条：
1. 静态检查对改动文件过（`mvn clean verify -DskipTests` → BUILD SUCCESS，隔离 PMD/SpotBugs/Checkstyle）；
2. `SelectLineageVisitorTest` 到 43/18；
3. 全量失败数 ≤ "baseline 减去预测转绿数"，证明其它 7 类零回归。

---

## 3. P1 — Task 1 对 UNION 派生表并非严格行为不变

当前 `cacheSubqueryTableSource` 的 `if (lastQueryBlockScope != null)`：内层是 UNION 派生表时 `visit(MySqlSelectQueryBlock)` 不被调，`lastQueryBlockScope` 保留**上一次陈旧值**，guard 仍可能通过并拷错数据。重构改成 `if (inner instanceof MySqlSelectQueryBlock)` 是**更对**的（只对 select-block 内层拷），但对 UNION 派生表确实是**行为变化**（"可能拷陈旧" → "跳过"）。

`SelectLineageVisitorTest` 里没有 `FROM (SELECT ... UNION ...)` 的用例，所以查不出来——别的类可能有。这恰好是 P0.2"必须查全量"的具体落脚点。

---

## 4. P2 — 小问题

| 项 | 位置 | 问题 |
|---|---|---|
| 路径拼写错误 | Task 1 Files 行 | 写成 `com/zzy/mysq**slq**lineageparser`（多了 `s`、`lq`），应为 `mysqllineageparser`。提交命令路径正确，仅 Files 标注错 |
| 行号不一致（无害） | Task 1 字段标 `:47-55`（实际 `:40-55` 区间，删 `:50`/`:55` 两字段）；Task 2 标头 `:327-342` vs Step 6 `:316-342`；`:582-607` vs `:574-607` | javadoc 含/不含口径不一，建议统一 |
| 注释措辞 | Task 2 Step 5 注 | "SQLSelectQuery 已用于 Task 1（statement 包）"误导——它**当前未在本文件具名使用**，仅被现有 `import com.alibaba.druid.sql.ast.statement.*;` 通配覆盖（已确认可编译）。改"由现有 statement 通配 import 覆盖"更准 |

---

## 5. 已核实无误的部分（无需改动）

- `nowScopeColumnLineages`（`:55`）**确认全仓零引用**（仅声明处出现）→ 可安全删除。
- Task 1 重构对 `SelectLineageVisitorTest` 行为保持：`child` scope == 旧 `lastQueryBlockScope`；`addSubQueryCache`/`mergeInvolvedTables`/`copyOutputColumnsFrom` 三动作等价；`:202 put` 与 `:203 mergeInvolvedTables` 已保留。
- `43→21→18` 算术正确；`directMappingTransformation`/`selfJoinWithAliases` 确由 Task 2 转绿。
- spec 审查 P1.1（保留 `:202 put`）、P1.2（去重新代码：`sourceDedupKey` + 物理分支与穿透分支共享同一 `seen` 集合）落地正确。
- `Set`/`HashSet` 由 `import java.util.*;` 覆盖；`SQLIdentifierExpr`/`SQLPropertyExpr` 由 `expr.*` 覆盖；`SQLSelectQuery` 由 `statement.*` 覆盖 → 编译无忧。

---

## 6. 修订动作清单（给执行者）

| 优先级 | 位置 | 动作 |
|---|---|---|
| P0 | Task 1 Step 1/6 | 验证改跑全量，钉 184/62 一个不动 |
| P0 | Task 2 Step 7、Task 3 Step 3 | 验证改跑全量（或 `SelectLineage*`），预测并钉住全量降幅 |
| P0 | Task 3 Step 4 + 完成判据 | 删 `mvn clean verify` BUILD SUCCESS 硬门槛；改三条判据（静态检查 / SelectLineageVisitorTest 43-18 / 全量零回归） |
| P1 | Task 1 Step 4 注释 | 标注对 UNION 派生表为行为变化（非纯保持），依赖全量验证兜底 |
| P2 | Task 1 Files 行 | 路径 `mysqslqlineageparser` → `mysqllineageparser` |
| P2 | Task 1/2 行号标注 | 统一 javadoc 含/不含口径 |
| P2 | Task 2 Step 5 注 | "SQLSelectQuery 已用于 Task 1" → "由现有 statement 通配 import 覆盖" |

> 注：计划的 Task 分解、代码、TDD 顺序（先写红测试 → 实现 → 转绿）均无需调整；改动集中在"验证命令范围"与"完成判据表述"。
