package com.zzy.mysqllineageparser.visitor;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.util.JdbcConstants;
import com.zzy.mysqllineageparser.model.ColumnInfo;
import com.zzy.mysqllineageparser.model.ColumnLineage;
import com.zzy.mysqllineageparser.model.LineageResult;
import com.zzy.mysqllineageparser.mybatis.support.TableMetaSupport;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JOIN 语句血缘解析准确性测试 — 直接测试 {@link SelectLineageVisitor}。
 * <p>
 * 专门用于检测解析器对 JOIN 语句的解析是否准确，覆盖：
 * <ul>
 *   <li>各类 JOIN 类型（INNER / LEFT / RIGHT / CROSS / NATURAL / 逗号关联）的输入表提取</li>
 *   <li>表别名、库名前缀在 JOIN 中的保留</li>
 *   <li>链式 / 多表 JOIN、混合 JOIN 类型</li>
 *   <li>ON 多条件、USING 关联条件</li>
 *   <li>带表元数据时的列到表来源解析</li>
 *   <li>JOIN + 子查询、JOIN + WHERE、JOIN + 通配符</li>
 *   <li>自关联（self-join）输入表去重</li>
 * </ul>
 * <p>
 * 说明：测试通过注入 {@link TableMetaSupport}（列名在表间唯一）使列来源解析结果确定，
 * 从而验证"带表前缀的具名列"能否正确关联到对应物理表。
 * <p>
 * 文末「已知精度缺口」一节中的用例编码了<b>理想</b>行为（应遵循表前缀 / 别名解析来源），
 * 用于暴露当前 visitor 忽略表前缀、仅按输出列名匹配的限制——这些用例在 visitor 增强前
 * 预期会失败，失败即代表发现了一处解析精度缺口。
 */
class SelectLineageJoinTest {

    /**
     * 不注入表元数据直接解析（适用于输入表提取、输出列名等无需精确列来源的断言）
     */
    private LineageResult parse(String sql) {
        LineageResult result = new LineageResult(sql);
        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        SQLSelectStatement selectStmt = (SQLSelectStatement) statements.get(0);
        SelectLineageVisitor visitor = new SelectLineageVisitor(result);
        selectStmt.accept(visitor);
        return result;
    }

    /**
     * 注入表元数据后解析（适用于需要确定性地把列关联到具体表的断言）
     */
    private LineageResult parse(String sql, TableMetaSupport tableMetaSupport) {
        LineageResult result = new LineageResult(sql);
        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        SQLSelectStatement selectStmt = (SQLSelectStatement) statements.get(0);
        SelectLineageVisitor visitor = new SelectLineageVisitor(result, null, tableMetaSupport);
        selectStmt.accept(visitor);
        return result;
    }

    /**
     * 按输出列名查找列血缘
     */
    private ColumnLineage lineageByName(LineageResult result, String name) {
        return result.getColumnLineages().stream()
                .filter(cl -> name.equals(cl.getOutputColumn().getColumnName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 收集列血缘的全部来源列名
     */
    private List<String> sourceColumnNames(ColumnLineage lineage) {
        return lineage.getSourceColumns().stream()
                .map(ColumnInfo::getColumnName)
                .collect(Collectors.toList());
    }

    // ==================== 1. 各 JOIN 类型：输入表与输出列 ====================

    @Test
    void innerJoinShouldExtractBothTables() {
        String sql = "SELECT o.id, o.amount, u.name " +
                "FROM orders o INNER JOIN users u ON o.user_id = u.id";

        LineageResult result = parse(sql);

        assertEquals(2, result.getInputTables().size());
        assertTrue(result.getInputTableNames().contains("orders"));
        assertTrue(result.getInputTableNames().contains("users"));
        assertEquals(3, result.getColumnLineages().size());
    }

    @Test
    void plainJoinEqualsInnerJoin() {
        String sql = "SELECT a.id, b.name FROM t_a a JOIN t_b b ON a.id = b.aid";

        LineageResult result = parse(sql);

        assertEquals(2, result.getInputTables().size());
        assertTrue(result.getInputTableNames().contains("t_a"));
        assertTrue(result.getInputTableNames().contains("t_b"));
        assertEquals(2, result.getColumnLineages().size());
    }

    @Test
    void leftJoinShouldExtractBothTables() {
        String sql = "SELECT e.id, e.name, d.dept_name " +
                "FROM employees e LEFT JOIN departments d ON e.dept_id = d.id";

        LineageResult result = parse(sql);

        assertEquals(2, result.getInputTables().size());
        assertTrue(result.getInputTableNames().contains("employees"));
        assertTrue(result.getInputTableNames().contains("departments"));
        assertEquals(3, result.getColumnLineages().size());
    }

    @Test
    void leftOuterJoinShouldExtractBothTables() {
        String sql = "SELECT e.id, d.dept_name " +
                "FROM employees e LEFT OUTER JOIN departments d ON e.dept_id = d.id";

        LineageResult result = parse(sql);

        assertEquals(2, result.getInputTables().size());
        assertTrue(result.getInputTableNames().contains("employees"));
        assertTrue(result.getInputTableNames().contains("departments"));
    }

    @Test
    void rightJoinShouldExtractBothTables() {
        String sql = "SELECT e.name, d.dept_name " +
                "FROM employees e RIGHT JOIN departments d ON e.dept_id = d.id";

        LineageResult result = parse(sql);

        assertEquals(2, result.getInputTables().size());
        assertTrue(result.getInputTableNames().contains("employees"));
        assertTrue(result.getInputTableNames().contains("departments"));
    }

    @Test
    void rightOuterJoinShouldExtractBothTables() {
        String sql = "SELECT e.name, d.dept_name " +
                "FROM employees e RIGHT OUTER JOIN departments d ON e.dept_id = d.id";

        LineageResult result = parse(sql);

        assertEquals(2, result.getInputTables().size());
        assertTrue(result.getInputTableNames().contains("employees"));
        assertTrue(result.getInputTableNames().contains("departments"));
    }

    @Test
    void crossJoinShouldExtractBothTables() {
        String sql = "SELECT a.x, b.y FROM t1 a CROSS JOIN t2 b";

        LineageResult result = parse(sql);

        assertEquals(2, result.getInputTables().size());
        assertTrue(result.getInputTableNames().contains("t1"));
        assertTrue(result.getInputTableNames().contains("t2"));
        assertEquals(2, result.getColumnLineages().size());
    }

    @Test
    void naturalJoinShouldExtractBothTables() {
        String sql = "SELECT a.id FROM t1 a NATURAL JOIN t2 b";

        LineageResult result = parse(sql);

        assertEquals(2, result.getInputTables().size());
        assertTrue(result.getInputTableNames().contains("t1"));
        assertTrue(result.getInputTableNames().contains("t2"));
        assertEquals(1, result.getColumnLineages().size());
    }

    @Test
    void commaJoinShouldExtractBothTables() {
        String sql = "SELECT a.id, b.name FROM t1 a, t2 b WHERE a.id = b.aid";

        LineageResult result = parse(sql);

        assertEquals(2, result.getInputTables().size());
        assertTrue(result.getInputTableNames().contains("t1"));
        assertTrue(result.getInputTableNames().contains("t2"));
        assertEquals(2, result.getColumnLineages().size());
    }

    // ==================== 2. 表别名保留 ====================

    @Test
    void inputTableShouldCarryAliasFromJoin() {
        String sql = "SELECT o.id, u.name FROM orders o INNER JOIN users u ON o.uid = u.id";

        LineageResult result = parse(sql);

        result.getInputTables().forEach(t -> {
            if ("orders".equals(t.getTableName())) {
                assertEquals("o", t.getAlias(), "orders 应保留别名 o");
            }
            if ("users".equals(t.getTableName())) {
                assertEquals("u", t.getAlias(), "users 应保留别名 u");
            }
        });
    }

    // ==================== 3. 库名前缀 JOIN ====================

    @Test
    void joinWithDatabasePrefixShouldExtractQualifiedTables() {
        String sql = "SELECT a.id, b.name FROM db1.t_a a JOIN db2.t_b b ON a.id = b.aid";

        LineageResult result = parse(sql);

        assertEquals(2, result.getInputTables().size());
        assertTrue(result.getInputTableNames().contains("db1.t_a"));
        assertTrue(result.getInputTableNames().contains("db2.t_b"));

        result.getInputTables().forEach(t -> {
            if ("t_a".equals(t.getTableName())) {
                assertEquals("db1", t.getDatabaseName());
            }
            if ("t_b".equals(t.getTableName())) {
                assertEquals("db2", t.getDatabaseName());
            }
        });
    }

    // ==================== 4. 链式 / 多表 JOIN ====================

    @Test
    void chainedThreeTableJoinShouldExtractAllTables() {
        String sql = "SELECT a.id, b.name, c.amount " +
                "FROM t_a a " +
                "INNER JOIN t_b b ON a.id = b.aid " +
                "INNER JOIN t_c c ON b.id = c.bid";

        LineageResult result = parse(sql);

        assertEquals(3, result.getInputTables().size());
        assertTrue(result.getInputTableNames().contains("t_a"));
        assertTrue(result.getInputTableNames().contains("t_b"));
        assertTrue(result.getInputTableNames().contains("t_c"));
        assertEquals(3, result.getColumnLineages().size());
    }

    @Test
    void mixedJoinTypesChainShouldExtractAllTables() {
        String sql = "SELECT a.id, b.name, c.amount " +
                "FROM t_a a " +
                "LEFT JOIN t_b b ON a.id = b.aid " +
                "RIGHT JOIN t_c c ON b.id = c.bid";

        LineageResult result = parse(sql);

        assertEquals(3, result.getInputTables().size());
        assertTrue(result.getInputTableNames().contains("t_a"));
        assertTrue(result.getInputTableNames().contains("t_b"));
        assertTrue(result.getInputTableNames().contains("t_c"));
    }

    @Test
    void fourTableJoinShouldExtractAllTables() {
        String sql = "SELECT a.id, b.name, c.code, d.value " +
                "FROM t_a a " +
                "JOIN t_b b ON a.id = b.aid " +
                "JOIN t_c c ON b.id = c.bid " +
                "JOIN t_d d ON c.id = c.cid";

        LineageResult result = parse(sql);

        assertEquals(4, result.getInputTables().size());
        assertEquals(4, result.getColumnLineages().size());
    }

    // ==================== 5. JOIN 关联条件 ====================

    @Test
    void joinWithMultipleOnConditionsAnd() {
        String sql = "SELECT a.id, b.code FROM t_a a " +
                "INNER JOIN t_b b ON a.key = b.key AND a.type = b.type";

        LineageResult result = parse(sql);

        assertEquals(2, result.getInputTables().size());
        assertEquals(2, result.getColumnLineages().size());
    }

    @Test
    void joinWithMultipleOnConditionsOr() {
        String sql = "SELECT a.id, b.code FROM t_a a " +
                "INNER JOIN t_b b ON a.key = b.key OR a.fallback = b.fallback";

        LineageResult result = parse(sql);

        assertEquals(2, result.getInputTables().size());
        assertEquals(2, result.getColumnLineages().size());
    }

    @Test
    void joinUsingClauseShouldExtractBothTables() {
        String sql = "SELECT a.id, b.name FROM t_a a INNER JOIN t_b b USING (dept_id)";

        LineageResult result = parse(sql);

        assertEquals(2, result.getInputTables().size());
        assertTrue(result.getInputTableNames().contains("t_a"));
        assertTrue(result.getInputTableNames().contains("t_b"));
        assertEquals(2, result.getColumnLineages().size());
    }

    // ==================== 6. 列血缘：带元数据的确定性来源解析 ====================

    /**
     * orders 与 users 列名互不相同，保证按列名匹配能确定地命中唯一表
     */
    private TableMetaSupport disjointMeta() {
        return tableName -> {
            switch (tableName) {
                case "orders":
                    return Arrays.asList(
                            new ColumnInfo(null, "id"),
                            new ColumnInfo(null, "user_id"),
                            new ColumnInfo(null, "amount"));
                case "users":
                    return Arrays.asList(
                            new ColumnInfo(null, "id"),
                            new ColumnInfo(null, "email"),
                            new ColumnInfo(null, "name"));
                default:
                    return Collections.emptyList();
            }
        };
    }

    @Test
    void qualifiedColumnResolvesToCorrectTable() {
        String sql = "SELECT o.amount FROM orders o INNER JOIN users u ON o.user_id = u.id";

        LineageResult result = parse(sql, disjointMeta());

        ColumnLineage lineage = result.getColumnLineages().get(0);
        assertEquals("amount", lineage.getOutputColumn().getColumnName());
        assertEquals(1, lineage.getSourceColumns().size(), "amount 仅 orders 拥有，来源应唯一");
        ColumnInfo source = lineage.getSourceColumns().get(0);
        assertEquals("amount", source.getColumnName());
        assertEquals("orders", source.getTable().getTableName());
        assertEquals("o", source.getTable().getAlias());
    }

    @Test
    void eachQualifiedColumnResolvesIndependently() {
        String sql = "SELECT o.amount, u.email FROM orders o INNER JOIN users u ON o.user_id = u.id";

        LineageResult result = parse(sql, disjointMeta());

        ColumnLineage amount = lineageByName(result, "amount");
        assertNotNull(amount);
        assertEquals(1, amount.getSourceColumns().size());
        assertEquals("orders", amount.getSourceColumns().get(0).getTable().getTableName());

        ColumnLineage email = lineageByName(result, "email");
        assertNotNull(email);
        assertEquals(1, email.getSourceColumns().size());
        assertEquals("users", email.getSourceColumns().get(0).getTable().getTableName());
    }

    @Test
    void bareColumnResolvesByMetadata() {
        String sql = "SELECT amount FROM orders o INNER JOIN users u ON o.user_id = u.id";

        LineageResult result = parse(sql, disjointMeta());

        ColumnLineage lineage = result.getColumnLineages().get(0);
        assertEquals("amount", lineage.getOutputColumn().getColumnName());
        assertEquals(1, lineage.getSourceColumns().size());
        assertEquals("orders", lineage.getSourceColumns().get(0).getTable().getTableName());
    }

    /**
     * 元数据 + 派生表穿透回归：无前缀裸列 a 选自多源 FROM（含派生表 sub），且元数据在场。
     * <p>
     * 关键：元数据知道物理表 users 拥有列 a，但没有任何物理表叫 sub（派生别名）。
     * 旧实现的元数据过滤会用别名 sub 查真实元数据、查不到而误删派生表候选；又因 users 拥有 a
     * 使 filtered 非空，于是只剩 users，派生表 sub 的 a → t1.id 穿透被静默丢弃，
     * 输出错误地解析到 users.a。修复后，派生表能输出 a（scope.getOutputColumnLineage 命中）
     * 须被保留，a 正确穿透到 t1.id，绝不应解析到 users.a。
     */
    @Test
    void metadataFilterShouldKeepDerivedTablePassthrough() {
        TableMetaSupport meta = tableName -> {
            switch (tableName) {
                case "t1":
                    return Arrays.asList(new ColumnInfo(null, "id"));
                case "users":
                    // users 也声明拥有列 a，用来诱导旧元数据过滤把 sub 删掉、只剩 users
                    return Arrays.asList(new ColumnInfo(null, "id"), new ColumnInfo(null, "a"));
                default:
                    return Collections.emptyList();
            }
        };
        String sql = "SELECT a FROM (SELECT id AS a FROM t1) sub, users u";

        LineageResult result = parse(sql, meta);

        ColumnLineage lineage = result.getColumnLineages().get(0);
        assertEquals("a", lineage.getOutputColumn().getColumnName());

        List<String> sources = sourceColumnNames(lineage);
        assertTrue(sources.contains("id"),
                "派生表 sub.a 必须穿透到 t1.id，不应被元数据过滤删除");

        boolean resolvesToT1 = lineage.getSourceColumns().stream()
                .anyMatch(sc -> "t1".equals(sc.getTable().getTableName()));
        assertTrue(resolvesToT1,
                "a 应穿透到 t1（派生表候选须保留）；旧实现会因用别名 sub 查元数据查不到而把 sub 删掉，导致只剩 users.a、t1.id 穿透丢失");
    }

    // ==================== 7. JOIN + 通配符 ====================

    @Test
    void qualifiedWildcardShouldExpandOnlyReferencedTable() {
        String sql = "SELECT o.* FROM orders o INNER JOIN users u ON o.user_id = u.id";

        LineageResult result = parse(sql, disjointMeta());

        ColumnLineage wildcard = result.getColumnLineages().get(0);
        assertEquals("*", wildcard.getOutputColumn().getColumnName());
        assertEquals("SELECT *", wildcard.getTransformation());

        // o.* 应仅展开 orders 的列，不包含 users 的列
        List<String> sources = sourceColumnNames(wildcard);
        assertTrue(sources.contains("amount"), "o.* 应展开 orders.amount");
        assertTrue(sources.contains("user_id"), "o.* 应展开 orders.user_id");
        assertFalse(sources.contains("email"), "o.* 不应展开 users 的列 email");
    }

    @Test
    void bareStarShouldExpandAllJoinedTables() {
        String sql = "SELECT * FROM orders o INNER JOIN users u ON o.user_id = u.id";

        LineageResult result = parse(sql, disjointMeta());

        ColumnLineage wildcard = result.getColumnLineages().get(0);
        assertEquals("*", wildcard.getOutputColumn().getColumnName());

        List<String> sources = sourceColumnNames(wildcard);
        assertTrue(sources.contains("amount"), "裸 * 应展开 orders 的列");
        assertTrue(sources.contains("email"), "裸 * 应展开 users 的列");
    }

    // ==================== 8. 自关联 ====================

    @Test
    void selfJoinInputTableShouldBeDeduplicated() {
        String sql = "SELECT a.id, b.name AS manager_name " +
                "FROM employees a INNER JOIN employees b ON a.manager_id = b.id";

        LineageResult result = parse(sql);

        long employeesCount = result.getInputTables().stream()
                .filter(t -> "employees".equals(t.getTableName()))
                .count();
        assertEquals(1, employeesCount, "自关联同一张物理表应去重为一条输入表");
        assertEquals(2, result.getColumnLineages().size());
    }

    // ==================== 9. JOIN + 子查询 ====================

    @Test
    void tableJoinSubqueryShouldExtractInnerTable() {
        String sql = "SELECT u.name, t.total " +
                "FROM users u " +
                "JOIN (SELECT user_id, SUM(amount) AS total FROM orders GROUP BY user_id) t " +
                "ON u.id = t.user_id";

        LineageResult result = parse(sql);

        assertEquals(2, result.getInputTables().size());
        assertTrue(result.getInputTableNames().contains("users"), "应发现外层 users");
        assertTrue(result.getInputTableNames().contains("orders"), "应发现内层子查询的 orders");
        assertEquals(2, result.getColumnLineages().size());
    }

    @Test
    void subqueryJoinTableShouldExtractInnerTable() {
        String sql = "SELECT t.user_name, o.amount " +
                "FROM (SELECT id, name AS user_name FROM users) t " +
                "JOIN orders o ON t.id = o.user_id";

        LineageResult result = parse(sql);

        assertEquals(2, result.getInputTables().size());
        assertTrue(result.getInputTableNames().contains("users"));
        assertTrue(result.getInputTableNames().contains("orders"));
        assertEquals(2, result.getColumnLineages().size());
    }

    // ==================== 10. JOIN + WHERE ====================

    @Test
    void joinWithWhereShouldRecordFilterCondition() {
        String sql = "SELECT a.id, b.name " +
                "FROM t_a a INNER JOIN t_b b ON a.id = b.aid " +
                "WHERE a.status = 1";

        LineageResult result = parse(sql);

        assertEquals(2, result.getInputTables().size());
        assertEquals(2, result.getColumnLineages().size());
        for (ColumnLineage cl : result.getColumnLineages()) {
            assertNotNull(cl.getFilterCondition(), "JOIN 查询的 WHERE 条件应被记录");
            assertTrue(cl.getFilterCondition().contains("status"),
                    "过滤条件应包含 status: " + cl.getFilterCondition());
        }
    }

    @Test
    void joinConditionItselfShouldNotBeRecordedAsWhereFilter() {
        // ON 条件属于 JOIN，不属于 WHERE；当前 visitor 仅记录 WHERE，不应将 ON 误记为 filter
        String sql = "SELECT a.id, b.name FROM t_a a INNER JOIN t_b b ON a.key = b.key";

        LineageResult result = parse(sql);

        for (ColumnLineage cl : result.getColumnLineages()) {
            assertNull(cl.getFilterCondition(), "无 WHERE 时不应记录过滤条件");
        }
    }

    // ==================== 11. 已知精度缺口（编码理想行为，用于暴露 visitor 限制） ====================
    // 以下用例断言"理想"行为。当前 SelectLineageVisitor 在具名列解析时只使用输出列名字符串、
    // 丢弃表前缀与原始列表达式，因此当列名在多表间冲突、或列带别名时来源会被错误地展开为多表。
    // 这些用例在 visitor 增强前预期失败；失败本身即是对解析精度缺口的检测。

    /**
     * 缺口：表前缀未生效。o.shared 应唯一来自 orders，但因列名在两表都存在，
     * 当前 visitor 会把 orders 与 users 都判为来源。
     */
    @Test
    void qualifiedColumnShouldHonorPrefixWhenNamesCollide() {
        TableMetaSupport meta = tableName ->
                "orders".equals(tableName) || "users".equals(tableName)
                        ? Arrays.asList(new ColumnInfo(null, "shared"))
                        : Collections.emptyList();
        String sql = "SELECT o.shared FROM orders o INNER JOIN users u ON o.uid = u.id";

        LineageResult result = parse(sql, meta);

        ColumnLineage lineage = result.getColumnLineages().get(0);
        assertEquals("shared", lineage.getOutputColumn().getColumnName());
        assertEquals(1, lineage.getSourceColumns().size(), "o.shared 应唯一关联 orders（遵循表前缀）");
        assertEquals("orders", lineage.getSourceColumns().get(0).getTable().getTableName());
    }

    /**
     * 缺口：列别名导致来源丢失。o.amount AS amt 应解析回 orders.amount，
     * 但当前 visitor 用别名 amt 作为列名去匹配元数据，命中失败后回退为全部表。
     */
    @Test
    void aliasedColumnShouldResolveToUnderlyingSourceColumn() {
        String sql = "SELECT o.amount AS amt FROM orders o INNER JOIN users u ON o.user_id = u.id";

        LineageResult result = parse(sql, disjointMeta());

        ColumnLineage lineage = lineageByName(result, "amt");
        assertNotNull(lineage);
        assertEquals(1, lineage.getSourceColumns().size(), "amt 应解析回 orders.amount");
        assertEquals("amount", lineage.getSourceColumns().get(0).getColumnName(),
                "来源列名应为原始列名 amount 而非别名 amt");
        assertEquals("orders", lineage.getSourceColumns().get(0).getTable().getTableName());
    }

    /**
     * 缺口：自关联列前缀未生效。a.id 应唯一来自别名 a 对应的 employees，
     * 但当前 visitor 会把别名 a 与 b 都判为来源。
     */
    @Test
    void selfJoinColumnShouldRespectAliasPrefix() {
        TableMetaSupport meta = tableName -> "employees".equals(tableName)
                ? Arrays.asList(new ColumnInfo(null, "id"), new ColumnInfo(null, "manager_id"))
                : Collections.emptyList();
        String sql = "SELECT a.id FROM employees a INNER JOIN employees b ON a.manager_id = b.id";

        LineageResult result = parse(sql, meta);

        ColumnLineage lineage = result.getColumnLineages().get(0);
        assertEquals("id", lineage.getOutputColumn().getColumnName());
        assertEquals(1, lineage.getSourceColumns().size(), "a.id 应唯一关联别名 a 对应的 employees");
        assertEquals("a", lineage.getSourceColumns().get(0).getTable().getAlias(),
                "来源表别名应为 a");
    }
}
