# SELECT 血缘解析 - 实现计划（TODO）

> 跟踪 SELECT 语句血缘解析的实现进度，按里程碑组织。
> 详细的语法清单与示例见 [`src/main/resources/select-lineage-todo.md`](src/main/resources/select-lineage-todo.md)。

## ✅ 已完成

- [x] **简单 SELECT 语句**
  - 基础列查询 `SELECT col FROM table`
  - 表别名 / 列别名（`FROM table AS t`、`col AS alias`）
  - `WHERE` 过滤条件（记录到列血缘的 `filterCondition`）
  - 反引号标识符 `` `col` ``、`db.table` 库表前缀
  - 解析入口与策略/访问者骨架（`SelectParseStrategy` + `SelectLineageVisitor`）
  - 测试：`SelectLineageSimpleTest`

- [x] **带通配符的查询语句**
  - `SELECT *` 与 `table.*` 的列血缘解析及展开
  - 依赖表元数据（`TableMetaSupport`）展开为具体列
  - 测试：`SelectLineageSimpleTest#testSelectAllColumns`、`SelectLineageComplexTest#testSelectWithWildcardAndSpecificColumnsFromTableAndSubquery`

---

## ⏳ 待完成

> 顺序仅为建议优先级，可按需调整。

- [ ] JOIN 多表查询
  - 基础 INNER / LEFT JOIN 已可用；待完善：跨表列来源归属、`USING`、3+ 表 JOIN、自连接 / CROSS JOIN
- [ ] 子查询
  - FROM 子查询（派生表）、子查询列穿透、子查询 + JOIN 组合
  - 标量子查询（SELECT 列表中）、WHERE 子查询（`IN` / `EXISTS`）
- [ ] 聚合与分组
  - `GROUP BY` / `HAVING`、聚合函数（`COUNT`/`SUM`/`AVG`）的转换记录
- [ ] 表达式与函数的 transformation
  - 二元表达式（`salary * 1.1`）、函数调用（`CONCAT(...)`）当前 `transformation` 落地为空，需补全
- [ ] `UNION` / `UNION ALL` 分支合并
- [ ] `DISTINCT`、`ORDER BY` / `LIMIT`、`CASE WHEN`
- [ ] CTE（`WITH` 子句）、递归 CTE
- [ ] `INSERT INTO ... SELECT`、`CREATE TABLE AS SELECT`
- [ ] 窗口函数（`OVER` / `PARTITION BY`）、类型转换（`CAST`）

---

## 备注

- 用例拆分：简单查询见 `SelectLineageSimpleTest`，复杂/暂存用例见 `SelectLineageComplexTest`，一阶语法探索见 `SelectLineageFirstTierTest`。
- 部分用例当前为失败状态（visitor 支持未完成，属预期），随各里程碑推进逐个转绿。
