package com.zzy.mysqllineageparser.parser;

import com.zzy.mysqllineageparser.model.ColumnInfo;
import com.zzy.mysqllineageparser.model.ColumnLineage;
import com.zzy.mysqllineageparser.model.LineageResult;
import com.zzy.mysqllineageparser.model.TableInfo;
import com.zzy.mysqllineageparser.parser.strategy.CreateTableParseStrategy;
import com.zzy.mysqllineageparser.parser.strategy.DeleteParseStrategy;
import com.zzy.mysqllineageparser.parser.strategy.InsertParseStrategy;
import com.zzy.mysqllineageparser.parser.strategy.ParseStrategyFactory;
import com.zzy.mysqllineageparser.parser.strategy.SelectParseStrategy;
import com.zzy.mysqllineageparser.parser.strategy.StatementParseStrategy;
import com.zzy.mysqllineageparser.parser.strategy.UpdateParseStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SELECT 语句第一梯队语法血缘解析测试
 * <p>
 * 覆盖 select-lineage-todo.md 中定义的第一梯队（高频基础语法）：
 * <ul>
 *   <li>1. 子查询 (Subquery in FROM)</li>
 *   <li>2. 子查询 (Subquery in SELECT)</li>
 *   <li>3. 子查询 (Subquery in WHERE)</li>
 *   <li>4. UNION / UNION ALL</li>
 *   <li>5. GROUP BY / HAVING</li>
 *   <li>6. DISTINCT</li>
 *   <li>7. ORDER BY / LIMIT</li>
 *   <li>8. CASE WHEN</li>
 * </ul>
 */
class SelectLineageFirstTierTest {

    private SqlLineageParser parser;

    @BeforeEach
    void setUp() {
        List<StatementParseStrategy> strategies = new ArrayList<>();
        strategies.add(new CreateTableParseStrategy());
        strategies.add(new InsertParseStrategy());
        strategies.add(new SelectParseStrategy());
        strategies.add(new UpdateParseStrategy());
        strategies.add(new DeleteParseStrategy());

        ParseStrategyFactory factory = new ParseStrategyFactory(strategies);
        parser = new MysqlLineageParserImpl(factory);
    }

    // ========== #1 子查询 (Subquery in FROM) ==========

    @Test
    void testSubqueryInFrom() {
        String sql = "SELECT a FROM (SELECT id AS a FROM t1) sub;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());

        // 内层查询的表 t1 应被发现
        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("t1"),
                "应包含内层子查询中的表 t1，实际输入表: " + inputTableNames);

        // 外层 SELECT 输出列名为 a
        assertNotNull(result.getColumnLineages());
        assertTrue(result.getColumnLineages().size() >= 1,
                "应有至少一条列血缘");

        // 输出列应为 a（来自子查询的 a 别名）
        ColumnLineage lineage = result.getColumnLineages().get(0);
        assertEquals("a", lineage.getOutputColumn().getColumnName());
    }

    @Test
    void testSubqueryInFromWithJoin() {
        String sql = "SELECT sub.total, u.name " +
                "FROM (SELECT user_id, SUM(amount) AS total FROM orders GROUP BY user_id) sub " +
                "INNER JOIN users u ON sub.user_id = u.id;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);

        // 应发现内层子查询中的 orders 表和外层的 users 表
        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("orders"),
                "应包含内层子查询中的 orders，实际: " + inputTableNames);
        assertTrue(inputTableNames.contains("users"),
                "应包含外层的 users，实际: " + inputTableNames);
    }

    @Test
    void testSubqueryInFromNested() {
        String sql = "SELECT x FROM (SELECT a AS x FROM (SELECT id AS a FROM t1) inner_sub) outer_sub;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("t1"),
                "嵌套子查询中的最终源表 t1 应被发现，实际: " + inputTableNames);
    }

    // ========== #2 子查询 (Subquery in SELECT) ==========

    @Test
    void testSubqueryInSelect() {
        String sql = "SELECT (SELECT MAX(id) FROM t2) AS max_id FROM t1;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);

        // SELECT 子查询中引用的 t2 和 FROM 子句中的 t1 都应被发现
        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("t1"),
                "FROM 子句中的 t1 应被发现，实际: " + inputTableNames);
        assertTrue(inputTableNames.contains("t2"),
                "SELECT 子查询中的 t2 应被发现，实际: " + inputTableNames);
    }

    @Test
    void testSubqueryInSelectCorrelated() {
        String sql = "SELECT id, (SELECT name FROM t2 WHERE t2.id = t1.id) AS t2_name FROM t1;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("t1"));
        assertTrue(inputTableNames.contains("t2"));

        // 输出列应包含 id 和 t2_name
        assertNotNull(result.getColumnLineages());
        List<String> outputColNames = result.getColumnLineages().stream()
                .map(cl -> cl.getOutputColumn().getColumnName())
                .collect(Collectors.toList());
        assertTrue(outputColNames.contains("id"), "输出列应包含 id");
        assertTrue(outputColNames.contains("t2_name"), "输出列应包含 t2_name");
    }

    // ========== #3 子查询 (Subquery in WHERE) ==========

    @Test
    void testSubqueryInWhere() {
        String sql = "SELECT * FROM t1 WHERE id IN (SELECT id FROM t2);";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);

        // WHERE 子查询中的 t2 和 FROM 中的 t1 都应被发现
        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("t1"),
                "FROM 子句中的 t1 应被发现，实际: " + inputTableNames);
        assertTrue(inputTableNames.contains("t2"),
                "WHERE 子查询中的 t2 应被发现，实际: " + inputTableNames);
    }

    @Test
    void testSubqueryInWhereNotExists() {
        String sql = "SELECT * FROM t1 WHERE id NOT IN (SELECT id FROM t2);";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("t1"));
        assertTrue(inputTableNames.contains("t2"));
    }

    @Test
    void testSubqueryInWhereComparison() {
        String sql = "SELECT * FROM t1 WHERE salary > (SELECT AVG(salary) FROM t2);";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("t1"));
        assertTrue(inputTableNames.contains("t2"));
    }

    // ========== #4 UNION / UNION ALL ==========

    @Test
    void testUnion() {
        String sql = "SELECT a FROM t1 UNION SELECT b FROM t2;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);

        // UNION 两侧的表都应被发现
        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("t1"),
                "UNION 第一个分支的 t1 应被发现，实际: " + inputTableNames);
        assertTrue(inputTableNames.contains("t2"),
                "UNION 第二个分支的 t2 应被发现，实际: " + inputTableNames);
    }

    @Test
    void testUnionAll() {
        String sql = "SELECT id, name FROM employees UNION ALL SELECT id, name FROM contractors;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("employees"));
        assertTrue(inputTableNames.contains("contractors"));
    }

    @Test
    void testUnionMultipleBranches() {
        String sql = "SELECT id FROM t1 UNION SELECT id FROM t2 UNION SELECT id FROM t3;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("t1"));
        assertTrue(inputTableNames.contains("t2"));
        assertTrue(inputTableNames.contains("t3"));
    }

    // ========== #5 GROUP BY / HAVING ==========

    @Test
    void testGroupBy() {
        String sql = "SELECT dept, COUNT(*) AS cnt FROM employees GROUP BY dept;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());
        assertEquals("employees", result.getInputTables().get(0).getTableName());

        // 应有列血缘记录
        assertNotNull(result.getColumnLineages());
        assertTrue(result.getColumnLineages().size() >= 2,
                "应有至少 2 条列血缘（dept 和 cnt），实际: " + result.getColumnLineages().size());

        // 输出列应包含 dept 和 cnt
        List<String> outputColNames = result.getColumnLineages().stream()
                .map(cl -> cl.getOutputColumn().getColumnName())
                .collect(Collectors.toList());
        assertTrue(outputColNames.contains("dept"), "输出列应包含 dept");
        assertTrue(outputColNames.contains("cnt"), "输出列应包含 cnt");
    }

    @Test
    void testGroupByWithHaving() {
        String sql = "SELECT dept, COUNT(*) AS cnt FROM employees GROUP BY dept HAVING cnt > 5;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);

        // HAVING 中的过滤条件应被记录
        assertNotNull(result.getColumnLineages());
        boolean hasHavingFilter = result.getColumnLineages().stream()
                .anyMatch(cl -> cl.getFilterCondition() != null && cl.getFilterCondition().contains("cnt"));
        assertTrue(hasHavingFilter,
                "HAVING 中的过滤条件应被记录到列血缘的 filterCondition 中");
    }

    @Test
    void testGroupByMultipleColumns() {
        String sql = "SELECT dept, role, AVG(salary) AS avg_sal " +
                "FROM employees GROUP BY dept, role;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertNotNull(result.getColumnLineages());
        assertTrue(result.getColumnLineages().size() >= 3,
                "应有至少 3 条列血缘（dept, role, avg_sal），实际: " + result.getColumnLineages().size());
    }

    // ========== #6 DISTINCT ==========

    @Test
    void testDistinct() {
        String sql = "SELECT DISTINCT col FROM table1;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());
        assertEquals("table1", result.getInputTables().get(0).getTableName());

        // 列血缘应被记录
        assertNotNull(result.getColumnLineages());
        assertTrue(result.getColumnLineages().size() >= 1,
                "应有至少 1 条列血缘");
    }

    @Test
    void testDistinctMultipleColumns() {
        String sql = "SELECT DISTINCT dept, role FROM employees;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertNotNull(result.getColumnLineages());

        List<String> outputColNames = result.getColumnLineages().stream()
                .map(cl -> cl.getOutputColumn().getColumnName())
                .collect(Collectors.toList());
        assertTrue(outputColNames.contains("dept"), "输出列应包含 dept");
        assertTrue(outputColNames.contains("role"), "输出列应包含 role");
    }

    @Test
    void testDistinctWithWhere() {
        String sql = "SELECT DISTINCT category FROM products WHERE active = 1;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("products", result.getInputTables().get(0).getTableName());

        // WHERE 条件应被记录
        assertNotNull(result.getColumnLineages());
        boolean hasFilter = result.getColumnLineages().stream()
                .anyMatch(cl -> cl.getFilterCondition() != null && cl.getFilterCondition().contains("active"));
        assertTrue(hasFilter, "WHERE 过滤条件应被记录到 filterCondition 中");
    }

    // ========== #7 ORDER BY / LIMIT ==========

    @Test
    void testOrderBy() {
        String sql = "SELECT * FROM employees ORDER BY salary DESC;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());
        assertEquals("employees", result.getInputTables().get(0).getTableName());
    }

    @Test
    void testOrderByMultipleColumns() {
        String sql = "SELECT id, name FROM users ORDER BY dept ASC, name DESC;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("users", result.getInputTables().get(0).getTableName());
    }

    @Test
    void testLimit() {
        String sql = "SELECT * FROM orders LIMIT 10;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());
        assertEquals("orders", result.getInputTables().get(0).getTableName());
    }

    @Test
    void testOrderByWithLimit() {
        String sql = "SELECT * FROM employees ORDER BY hire_date LIMIT 10;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());
        assertEquals("employees", result.getInputTables().get(0).getTableName());
    }

    @Test
    void testLimitOffset() {
        String sql = "SELECT * FROM products ORDER BY price LIMIT 20 OFFSET 10;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("products", result.getInputTables().get(0).getTableName());
    }

    // ========== #8 CASE WHEN ==========

    @Test
    void testCaseWhenSimple() {
        String sql = "SELECT CASE WHEN score > 90 THEN 'A' ELSE 'B' END AS grade FROM students;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());
        assertEquals("students", result.getInputTables().get(0).getTableName());

        // 列血缘应记录 CASE WHEN 表达式
        assertNotNull(result.getColumnLineages());
        assertTrue(result.getColumnLineages().size() >= 1,
                "应有至少 1 条列血缘");

        // 输出列名为 grade
        ColumnLineage lineage = result.getColumnLineages().get(0);
        assertEquals("grade", lineage.getOutputColumn().getColumnName());

        // transformation 应包含 CASE WHEN
        assertNotNull(lineage.getTransformation());
        assertTrue(lineage.getTransformation().toLowerCase().contains("case"),
                "transformation 应包含 CASE 关键字，实际: " + lineage.getTransformation());

        // 来源列应包含 score（CASE WHEN 中引用的列）
        List<String> sourceColNames = lineage.getSourceColumns().stream()
                .map(ColumnInfo::getColumnName)
                .collect(Collectors.toList());
        assertTrue(sourceColNames.contains("score"),
                "来源列应包含 score，实际: " + sourceColNames);
    }

    @Test
    void testCaseWhenMultipleConditions() {
        String sql = "SELECT CASE " +
                "WHEN score >= 90 THEN 'A' " +
                "WHEN score >= 80 THEN 'B' " +
                "WHEN score >= 60 THEN 'C' " +
                "ELSE 'D' " +
                "END AS grade " +
                "FROM exam_results;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertNotNull(result.getColumnLineages());

        ColumnLineage lineage = result.getColumnLineages().get(0);
        assertEquals("grade", lineage.getOutputColumn().getColumnName());
        assertNotNull(lineage.getTransformation());

        // 来源列应包含 score
        List<String> sourceColNames = lineage.getSourceColumns().stream()
                .map(ColumnInfo::getColumnName)
                .collect(Collectors.toList());
        assertTrue(sourceColNames.contains("score"),
                "多条件 CASE WHEN 来源列应包含 score，实际: " + sourceColNames);
    }

    @Test
    void testCaseWhenWithColumnReference() {
        String sql = "SELECT name, CASE WHEN status = 1 THEN 'active' ELSE 'inactive' END AS status_text FROM users;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertNotNull(result.getColumnLineages());
        assertTrue(result.getColumnLineages().size() >= 2,
                "应有至少 2 条列血缘（name 和 status_text）");

        // 检查 CASE WHEN 列的来源列包含 status
        ColumnLineage caseLineage = result.getColumnLineages().stream()
                .filter(cl -> "status_text".equals(cl.getOutputColumn().getColumnName()))
                .findFirst()
                .orElse(null);
        assertNotNull(caseLineage, "应找到 status_text 的列血缘");

        List<String> sourceColNames = caseLineage.getSourceColumns().stream()
                .map(ColumnInfo::getColumnName)
                .collect(Collectors.toList());
        assertTrue(sourceColNames.contains("status"),
                "CASE WHEN 中引用的 status 应出现在来源列中，实际: " + sourceColNames);
    }

    // ========== 综合组合测试 ==========

    @Test
    void testGroupByWithOrderByAndLimit() {
        String sql = "SELECT dept, COUNT(*) AS cnt FROM employees GROUP BY dept ORDER BY cnt DESC LIMIT 10;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("employees", result.getInputTables().get(0).getTableName());

        List<String> outputColNames = result.getColumnLineages().stream()
                .map(cl -> cl.getOutputColumn().getColumnName())
                .collect(Collectors.toList());
        assertTrue(outputColNames.contains("dept"));
        assertTrue(outputColNames.contains("cnt"));
    }

    @Test
    void testDistinctWithOrderBy() {
        String sql = "SELECT DISTINCT category FROM products ORDER BY category;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("products", result.getInputTables().get(0).getTableName());
    }

    @Test
    void testCaseWhenWithGroupBy() {
        String sql = "SELECT dept, " +
                "CASE WHEN salary > 10000 THEN 'high' ELSE 'normal' END AS level, " +
                "COUNT(*) AS cnt " +
                "FROM employees GROUP BY dept, level;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("employees", result.getInputTables().get(0).getTableName());

        // 应有列血缘
        assertNotNull(result.getColumnLineages());
        assertTrue(result.getColumnLineages().size() >= 3,
                "应有至少 3 条列血缘（dept, level, cnt），实际: " + result.getColumnLineages().size());
    }

    @Test
    void testUnionWithOrderByAndLimit() {
        String sql = "SELECT id, name FROM t1 UNION ALL SELECT id, name FROM t2 ORDER BY name LIMIT 20;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("t1"));
        assertTrue(inputTableNames.contains("t2"));
    }
}
