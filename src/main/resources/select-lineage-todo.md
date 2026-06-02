# SELECT 语句血缘解析 - 待实现语法清单

## 已支持的语法

- 简单 `SELECT col FROM table`
- `SELECT * FROM table`
- 带别名的 `SELECT col AS alias` / `FROM table AS t`
- `WHERE` 条件（作为 filter 记录到列血缘上）
- `JOIN`（INNER JOIN / LEFT JOIN，递归收集左右表）
- 带表前缀的列引用 `SELECT t.col FROM table t`
- 二元表达式 `a + b`、函数调用 `CONCAT(a,b)`、聚合函数 `COUNT(*)`
- 反引号标识符 `` `column` ``
- `db.table` 格式的库表前缀

---

## 第一梯队：高频基础语法

| # | 语法类型 | 示例 | 说明 |
|---|---------|------|------|
| 1 | 子查询 (Subquery in FROM) | `SELECT a FROM (SELECT id AS a FROM t1) sub` | FROM 子句中的派生表 (Derived Table)，需要递归解析内层查询并建立血缘 |
| 2 | 子查询 (Subquery in SELECT) | `SELECT (SELECT MAX(id) FROM t2) AS max_id FROM t1` | SELECT 列表中的标量子查询，需要将其作为转换表达式的一部分 |
| 3 | 子查询 (Subquery in WHERE) | `SELECT * FROM t1 WHERE id IN (SELECT id FROM t2)` | WHERE 中的子查询，涉及新表来源的发现 |
| 4 | UNION / UNION ALL | `SELECT a FROM t1 UNION SELECT b FROM t2` | 合并多个查询的结果，需要分别解析每个分支的血缘 |
| 5 | GROUP BY / HAVING | `SELECT dept, COUNT(*) FROM t GROUP BY dept HAVING cnt > 5` | 需要从 GROUP BY 提取分组列，HAVING 中的过滤条件 |
| 6 | DISTINCT | `SELECT DISTINCT col FROM table` | 去重语义，影响转换类型的标记 |
| 7 | ORDER BY / LIMIT | `SELECT * FROM t ORDER BY col LIMIT 10` | 排序和分页，影响输出元数据 |
| 8 | CASE WHEN | `SELECT CASE WHEN score>90 THEN 'A' ELSE 'B' END FROM t` | 条件表达式中的列引用提取 |

---

## 第二梯队：中等复杂度语法

| # | 语法类型 | 示例 | 说明 |
|---|---------|------|------|
| 9 | CTE (WITH 子句) | `WITH cte AS (SELECT * FROM t1) SELECT * FROM cte` | 公共表表达式，需要建立 CTE 定义与引用之间的血缘关系 |
| 10 | EXISTS 子查询 | `SELECT * FROM t1 WHERE EXISTS (SELECT 1 FROM t2 WHERE t2.id = t1.id)` | 关联子查询，涉及外部引用列的解析 |
| 11 | INSERT INTO ... SELECT | `INSERT INTO t2 SELECT * FROM t1` | 虽然是 INSERT 语句，但核心是 SELECT 的血缘解析 |
| 12 | CREATE TABLE AS SELECT (CTAS) | `CREATE TABLE t2 AS SELECT * FROM t1` | DDL + SELECT 的组合 |
| 13 | 多表 JOIN (3+ tables) | `SELECT * FROM t1 JOIN t2 ON ... JOIN t3 ON ...` | 当前代码已支持，但需验证和补充测试 |
| 14 | CROSS JOIN / 自连接 | `SELECT * FROM t1 a, t1 b`, `SELECT * FROM t1 CROSS JOIN t2` | 隐式连接语法和自连接场景 |
| 15 | USING 子句 | `SELECT * FROM t1 JOIN t2 USING (id)` | JOIN USING 代替 ON 的场景 |
| 16 | 窗口函数 (Window Functions) | `SELECT ROW_NUMBER() OVER (PARTITION BY dept ORDER BY salary) FROM t` | 窗口函数中的 PARTITION BY / ORDER BY 列引用 |
| 17 | CAST / CONVERT 类型转换 | `SELECT CAST(col AS CHAR) FROM t` | 类型转换函数中的列引用 |

---

## 第三梯队：高级语法

| # | 语法类型 | 示例 | 说明 |
|---|---------|------|------|
| 18 | LATERAL 派生表 | `SELECT * FROM t1, LATERAL (SELECT * FROM t2 WHERE t2.id = t1.id) sub` | MySQL 8.0+ 支持，横向关联子查询 |
| 19 | NATURAL JOIN | `SELECT * FROM t1 NATURAL JOIN t2` | 自动匹配同名列 |
| 20 | FULL OUTER JOIN | `SELECT * FROM t1 FULL OUTER JOIN t2 ON ...` | MySQL 8.0+ 通过 UNION 模拟 |
| 21 | 递归 CTE | `WITH RECURSIVE cte AS (SELECT ... UNION ALL SELECT ... FROM cte) SELECT * FROM cte` | 递归公用表表达式 |
| 22 | VALUES 子句 | `SELECT * FROM (VALUES ROW(1,'a'), ROW(2,'b')) AS t(id,name)` | 内联值表 |
| 23 | JSON 函数 | `SELECT JSON_EXTRACT(data, '$.name') FROM t` | MySQL JSON 函数中的列引用 |
| 24 | 条件聚合 (FILTER / IF) | `SELECT COUNT(*) FILTER (WHERE status = 'active') FROM t` | 带条件过滤的聚合 |
| 25 | 索引提示 (Index Hints) | `SELECT * FROM t USE INDEX (idx_name)` | 不影响血缘但需要忽略解析 |

---

## 关键实现建议

1. **子查询是最核心的扩展点** — FROM 子句中的子查询、WHERE 中的关联子查询、SELECT 中的标量子查询都需要递归创建 `SelectLineageVisitor` 来处理内层查询
2. **UNION/CTE 需要引入分层血缘模型** — 当前 `LineageResult` 是扁平的，需要支持嵌套结构来表示 CTE 和 UNION 分支
3. **窗口函数需要新增 AST 节点处理** — Druid 中对应 `SQLOver` 节点，需要在 `collectReferencedColumns` 中增加对 `SQLOver` 的递归处理
4. **CASE WHEN 需要 AST 类型识别** — Druid 中对应 `SQLCaseExpr`，同样需要在列引用提取中补充处理
