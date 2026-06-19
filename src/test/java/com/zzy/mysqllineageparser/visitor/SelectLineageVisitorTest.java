package com.zzy.mysqllineageparser.visitor;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.util.JdbcConstants;
import com.zzy.mysqllineageparser.model.ColumnInfo;
import com.zzy.mysqllineageparser.model.ColumnLineage;
import com.zzy.mysqllineageparser.model.LineageResult;
import com.zzy.mysqllineageparser.model.TableInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SELECT 语句深度血缘解析测试 — 直接测试 SelectLineageVisitor
 * <p>
 * 覆盖范围：
 * <ul>
 *   <li>深度列级来源链验证（子查询穿透）</li>
 *   <li>复杂表达式（嵌套运算、多层函数）的 sourceColumns 提取</li>
 *   <li>自关联、逗号关联、RIGHT JOIN、CROSS JOIN</li>
 *   <li>关联子查询 / EXISTS / 多层混合子查询</li>
 *   <li>常量、字面量、无 FROM 等边界场景</li>
 *   <li>COUNT(DISTINCT col)、HAVING、UNION 列映射</li>
 * </ul>
 */
class SelectLineageVisitorTest {

    private LineageResult parse(String sql) {
        LineageResult result = new LineageResult(sql);
        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        SQLSelectStatement selectStmt = (SQLSelectStatement) statements.get(0);
        SelectLineageVisitor visitor = new SelectLineageVisitor(result);
        selectStmt.accept(visitor);
        return result;
    }

    // ==================== 1. 来源列表深度验证 ====================

    @Test
    void sourceColumnShouldCarryCorrectTableInfo() {
        String sql = "SELECT u.id, u.name FROM users u";
        LineageResult result = parse(sql);

        for (ColumnLineage cl : result.getColumnLineages()) {
            assertEquals(1, cl.getSourceColumns().size(),
                    "每列应有且仅有一个来源列: " + cl.getOutputColumn().getColumnName());
            ColumnInfo src = cl.getSourceColumns().get(0);
            assertEquals("users", src.getTable().getTableName(),
                    "来源列表指向 users 表");
            assertEquals("u", src.getTable().getAlias(),
                    "来源列表应带有别名 u");
        }
    }

    @Test
    void sourceColumnWithoutPrefixShouldResolveToSingleTable() {
        String sql = "SELECT id, name FROM users";
        LineageResult result = parse(sql);

        for (ColumnLineage cl : result.getColumnLineages()) {
            assertEquals(1, cl.getSourceColumns().size());
            ColumnInfo src = cl.getSourceColumns().get(0);
            assertEquals("users", src.getTable().getTableName());
        }
    }

    @Test
    void sourceColumnWithDatabasePrefixShouldCarryDatabaseName() {
        String sql = "SELECT u.id FROM mydb.users u";
        LineageResult result = parse(sql);

        ColumnLineage cl = result.getColumnLineages().get(0);
        ColumnInfo src = cl.getSourceColumns().get(0);
        TableInfo table = src.getTable();
        assertNotNull(table);
        assertEquals("mydb", table.getDatabaseName());
        assertEquals("users", table.getTableName());
        assertEquals("u", table.getAlias());
    }

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

    // ==================== 2. 复杂表达式来源列提取 ====================

    @Test
    void arithmeticExpressionShouldExtractAllReferencedColumns() {
        String sql = "SELECT (price * quantity * (1 - discount)) AS total FROM orders";
        LineageResult result = parse(sql);

        assertEquals(1, result.getColumnLineages().size());
        ColumnLineage cl = result.getColumnLineages().get(0);
        assertEquals("total", cl.getOutputColumn().getColumnName());

        // 应提取 price, quantity, discount 三列
        List<String> sourceNames = cl.getSourceColumns().stream()
                .map(ColumnInfo::getColumnName)
                .collect(java.util.stream.Collectors.toList());
        assertTrue(sourceNames.contains("price"), "应包含 price");
        assertTrue(sourceNames.contains("quantity"), "应包含 quantity");
        assertTrue(sourceNames.contains("discount"), "应包含 discount");
        assertNotNull(cl.getTransformation());
    }

    @Test
    void nestedFunctionCallShouldExtractAllArguments() {
        String sql = "SELECT CONCAT(UPPER(first_name), ' ', LOWER(last_name)) AS display_name FROM employees";
        LineageResult result = parse(sql);

        ColumnLineage cl = result.getColumnLineages().get(0);
        List<String> sourceNames = cl.getSourceColumns().stream()
                .map(ColumnInfo::getColumnName)
                .collect(java.util.stream.Collectors.toList());
        assertTrue(sourceNames.contains("first_name"));
        assertTrue(sourceNames.contains("last_name"));
    }

    @Test
    void aggregationWithDistinctShouldExtractColumn() {
        String sql = "SELECT COUNT(DISTINCT category) AS distinct_cats FROM products";
        LineageResult result = parse(sql);

        ColumnLineage cl = result.getColumnLineages().get(0);
        assertEquals("distinct_cats", cl.getOutputColumn().getColumnName());
        List<String> sourceNames = cl.getSourceColumns().stream()
                .map(ColumnInfo::getColumnName)
                .collect(java.util.stream.Collectors.toList());
        assertTrue(sourceNames.contains("category"),
                "COUNT(DISTINCT category) 应提取 category 列");
    }

    @Test
    void multipleExpressionsShouldEachHaveCorrectSources() {
        String sql = "SELECT salary * 12 AS annual, salary / 30 AS daily FROM employees";
        LineageResult result = parse(sql);

        assertEquals(2, result.getColumnLineages().size());

        ColumnLineage annual = result.getColumnLineages().get(0);
        assertEquals("annual", annual.getOutputColumn().getColumnName());
        assertEquals(1, annual.getSourceColumns().size());
        assertEquals("salary", annual.getSourceColumns().get(0).getColumnName());

        ColumnLineage daily = result.getColumnLineages().get(1);
        assertEquals("daily", daily.getOutputColumn().getColumnName());
        assertEquals(1, daily.getSourceColumns().size());
        assertEquals("salary", daily.getSourceColumns().get(0).getColumnName());
    }

    // ==================== 3. 子查询列穿透链验证 ====================

    @Test
    void subqueryColumnPassthroughSingleLevel() {
        String sql = "SELECT a FROM (SELECT id AS a FROM t1) sub";
        LineageResult result = parse(sql);

        assertEquals(1, result.getColumnLineages().size());
        ColumnLineage cl = result.getColumnLineages().get(0);
        assertEquals("a", cl.getOutputColumn().getColumnName());

        // 外层的列应能穿透子查询找到物理源列 t1.id
        List<ColumnInfo> sources = cl.getSourceColumns();
        assertTrue(sources.size() >= 1, "应有来源列");
        // sourceColumns 链中的第一个元素应为 t1.id
        ColumnInfo source = sources.get(0);
        assertEquals("id", source.getColumnName());
        assertEquals("t1", source.getTable().getTableName());
    }

    @Test
    void prefixedDerivedColumnPassthrough() {
        // 带前缀引用派生表列：sub.a 应穿透到 t1.id（tableRef 过滤命中 sub，再 A1 flatten）
        String sql = "SELECT sub.a FROM (SELECT id AS a FROM t1) sub";
        LineageResult result = parse(sql);

        ColumnLineage cl = result.getColumnLineages().get(0);
        assertEquals("a", cl.getOutputColumn().getColumnName());
        assertTrue(cl.getSourceColumns().size() >= 1);
        ColumnInfo src = cl.getSourceColumns().get(0);
        assertEquals("id", src.getColumnName());
        assertEquals("t1", src.getTable().getTableName());
    }

    @Test
    void multiLevelSubqueryColumnPassthrough() {
        String sql = "SELECT x FROM (SELECT a AS x FROM (SELECT id AS a FROM t1) inner_sub) outer_sub";
        LineageResult result = parse(sql);

        ColumnLineage cl = result.getColumnLineages().get(0);
        assertEquals("x", cl.getOutputColumn().getColumnName());

        // 最外层 x 应能穿透两层子查询找到 t1.id
        List<ColumnInfo> sources = cl.getSourceColumns();
        assertTrue(sources.size() >= 1, "应有穿透后的来源列");
        ColumnInfo deepestSource = findLeafSource(sources);
        assertEquals("id", deepestSource.getColumnName());
        assertEquals("t1", deepestSource.getTable().getTableName());
    }

    /** 递归查找 sourceColumns 链中最底层的物理列 */
    private ColumnInfo findLeafSource(List<ColumnInfo> columns) {
        ColumnInfo current = columns.get(0);
        while (current.getSourceColumns() != null && !current.getSourceColumns().isEmpty()) {
            current = current.getSourceColumns().get(0);
        }
        return current;
    }

    @Test
    void subqueryInJoinColumnPassthrough() {
        String sql = "SELECT sub.total, u.name " +
                "FROM (SELECT user_id, SUM(amount) AS total FROM orders GROUP BY user_id) sub " +
                "INNER JOIN users u ON sub.user_id = u.id";
        LineageResult result = parse(sql);

        assertEquals(2, result.getColumnLineages().size());

        // sub.total -> orders.amount
        ColumnLineage totalLineage = result.getColumnLineages().stream()
                .filter(cl -> "total".equals(cl.getOutputColumn().getColumnName()))
                .findFirst().orElse(null);
        assertNotNull(totalLineage);
        List<String> sourceNames = totalLineage.getSourceColumns().stream()
                .map(ColumnInfo::getColumnName)
                .collect(java.util.stream.Collectors.toList());
        assertTrue(sourceNames.contains("amount"));
    }

    // ==================== 4. 自关联与多表关联 ====================

    @Test
    void selfJoinWithAliases() {
        String sql = "SELECT a.id, b.name AS manager_name " +
                "FROM employees a INNER JOIN employees b ON a.manager_id = b.id";
        LineageResult result = parse(sql);

        assertTrue(result.getInputTableNames().contains("employees"),
                "自关联中 employees 应为输入表");
        assertEquals(2, result.getColumnLineages().size());

        // a.id 应来自 employees.id（带别名 a）
        ColumnLineage idLineage = result.getColumnLineages().stream()
                .filter(cl -> "id".equals(cl.getOutputColumn().getColumnName()))
                .findFirst().orElse(null);
        assertNotNull(idLineage);
        assertEquals("direct mapping", idLineage.getTransformation());
    }

    @Test
    void commaSeparatedCrossJoin() {
        String sql = "SELECT e.id, d.name FROM employees e, departments d WHERE e.dept_id = d.id";
        LineageResult result = parse(sql);

        List<String> tableNames = result.getInputTableNames();
        assertTrue(tableNames.contains("employees"));
        assertTrue(tableNames.contains("departments"));
        assertEquals(2, result.getColumnLineages().size());

        // 验证过滤器已记录
        assertNotNull(result.getColumnLineages().get(0).getFilterCondition());
    }

    @Test
    void rightJoin() {
        String sql = "SELECT e.name, d.dept_name " +
                "FROM employees e RIGHT JOIN departments d ON e.dept_id = d.id";
        LineageResult result = parse(sql);

        List<String> tableNames = result.getInputTableNames();
        assertTrue(tableNames.contains("employees"));
        assertTrue(tableNames.contains("departments"));
        assertEquals(2, result.getColumnLineages().size());
    }

    @Test
    void crossJoin() {
        String sql = "SELECT e.name, d.dept_name FROM employees e CROSS JOIN departments d";
        LineageResult result = parse(sql);

        List<String> tableNames = result.getInputTableNames();
        assertTrue(tableNames.contains("employees"));
        assertTrue(tableNames.contains("departments"));
        assertEquals(2, result.getColumnLineages().size());
    }

    // ==================== 5. WHERE 子查询 ====================

    @Test
    void correlatedSubqueryInWhere() {
        String sql = "SELECT id, name FROM employees e " +
                "WHERE salary > (SELECT AVG(salary) FROM employees WHERE dept_id = e.dept_id)";
        LineageResult result = parse(sql);

        assertTrue(result.getInputTableNames().contains("employees"));
        assertEquals(2, result.getColumnLineages().size());

        // WHERE 条件应记录过滤
        assertNotNull(result.getColumnLineages().get(0).getFilterCondition());
    }

    @Test
    void existsSubquery() {
        String sql = "SELECT id, name FROM customers c " +
                "WHERE EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.id)";
        LineageResult result = parse(sql);

        assertTrue(result.getInputTableNames().contains("customers"));
        assertTrue(result.getInputTableNames().contains("orders"));
        assertEquals(2, result.getColumnLineages().size());
    }

    @Test
    void notExistsSubquery() {
        String sql = "SELECT id, name FROM customers c " +
                "WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.id)";
        LineageResult result = parse(sql);

        assertTrue(result.getInputTableNames().contains("customers"));
        assertTrue(result.getInputTableNames().contains("orders"));
    }

    @Test
    void inSubqueryWithCorrelation() {
        String sql = "SELECT id FROM users WHERE id IN " +
                "(SELECT user_id FROM orders WHERE amount > 100)";
        LineageResult result = parse(sql);

        assertTrue(result.getInputTableNames().contains("users"));
        assertTrue(result.getInputTableNames().contains("orders"));
    }

    // ==================== 6. 常量与边界场景 ====================

    @Test
    void selectConstantsOnly() {
        String sql = "SELECT 1 AS num, 'hello' AS str, NULL AS empty";
        LineageResult result = parse(sql);

        assertEquals(3, result.getColumnLineages().size());
        // 常量不应产生来源列
        for (ColumnLineage cl : result.getColumnLineages()) {
            assertTrue(cl.getSourceColumns() == null || cl.getSourceColumns().isEmpty(),
                    "常量不应有来源列: " + cl.getOutputColumn().getColumnName());
        }
        // 不应有输入表
        assertTrue(result.getInputTables() == null || result.getInputTables().isEmpty(),
                "常量 SELECT 不应有输入表");
    }

    @Test
    void selectMixedConstantsAndColumns() {
        String sql = "SELECT id, 1 AS flag, 'active' AS status FROM users";
        LineageResult result = parse(sql);

        assertEquals(3, result.getColumnLineages().size());

        // id 应有来源列
        ColumnLineage idLineage = result.getColumnLineages().get(0);
        assertTrue(idLineage.getSourceColumns() != null && !idLineage.getSourceColumns().isEmpty());

        // flag 和 status 是常量，不应有来源列
        for (int i = 1; i < 3; i++) {
            assertTrue(result.getColumnLineages().get(i).getSourceColumns() == null
                            || result.getColumnLineages().get(i).getSourceColumns().isEmpty(),
                    "常量列不应有来源列: " + result.getColumnLineages().get(i).getOutputColumn().getColumnName());
        }
    }

    @Test
    void selectNoFromClauseShouldProduceNoInputTables() {
        // MySQL 允许 SELECT 1 不带 FROM
        String sql = "SELECT 1, 2, 3";
        LineageResult result = parse(sql);

        assertTrue(result.getInputTables() == null || result.getInputTables().isEmpty(),
                "无 FROM 的 SQL 不应有输入表");
        assertEquals(3, result.getColumnLineages().size());
    }

    // ==================== 7. UNION / UNION ALL ====================

    @Test
    void unionWithDifferentColumnNames() {
        String sql = "SELECT id AS user_id, name FROM t1 UNION SELECT uid, uname FROM t2";
        LineageResult result = parse(sql);

        assertTrue(result.getInputTableNames().contains("t1"));
        assertTrue(result.getInputTableNames().contains("t2"));

        // UNION 各分支的列按位置映射，输出列名取自第一分支
        List<String> outputNames = result.getColumnLineages().stream()
                .map(cl -> cl.getOutputColumn().getColumnName())
                .collect(java.util.stream.Collectors.toList());
        assertTrue(outputNames.contains("user_id") || outputNames.contains("name"));
    }

    @Test
    void unionAllWithJoin() {
        String sql = "SELECT o.id, o.total FROM orders o " +
                "UNION ALL " +
                "SELECT r.id, r.total FROM refunds r";
        LineageResult result = parse(sql);

        assertTrue(result.getInputTableNames().contains("orders"));
        assertTrue(result.getInputTableNames().contains("refunds"));
    }

    // ==================== 8. HAVING 与 ORDER BY ====================

    @Test
    void havingFilterConditionShouldBeRecorded() {
        String sql = "SELECT dept_id, COUNT(*) AS cnt FROM employees " +
                "GROUP BY dept_id HAVING cnt > 10";
        LineageResult result = parse(sql);

        // HAVING 条件应记录到 filterCondition
        boolean hasHavingFilter = result.getColumnLineages().stream()
                .anyMatch(cl -> cl.getFilterCondition() != null
                        && cl.getFilterCondition().toLowerCase().contains("cnt"));
        assertTrue(hasHavingFilter, "HAVING 条件应记录到 filterCondition");
    }

    @Test
    void orderByDoesNotAffectColumnLineageCount() {
        String sql = "SELECT id, name FROM users ORDER BY name DESC";
        LineageResult result = parse(sql);

        assertEquals(2, result.getColumnLineages().size());
        assertEquals("users", result.getInputTables().get(0).getTableName());
    }

    // ==================== 9. 多层混合子查询 ====================

    @Test
    void fromSubqueryPlusScalarSubqueryInSelect() {
        String sql = "SELECT sub.dept_name, " +
                "(SELECT COUNT(*) FROM employees e WHERE e.dept_id = sub.dept_id) AS emp_count " +
                "FROM (SELECT id, name AS dept_name FROM departments) sub";
        LineageResult result = parse(sql);

        assertTrue(result.getInputTableNames().contains("departments"));
        assertTrue(result.getInputTableNames().contains("employees"));

        // 应有 dept_name 和 emp_count 两列
        List<String> outputNames = result.getColumnLineages().stream()
                .map(cl -> cl.getOutputColumn().getColumnName())
                .collect(java.util.stream.Collectors.toList());
        assertTrue(outputNames.contains("dept_name"));
        assertTrue(outputNames.contains("emp_count"));
    }

    @Test
    void subqueryInFromWithSubqueryInWhere() {
        String sql = "SELECT * FROM (SELECT id, status FROM orders) o " +
                "WHERE o.id IN (SELECT order_id FROM payments WHERE amount > 1000)";
        LineageResult result = parse(sql);

        assertTrue(result.getInputTableNames().contains("orders"));
        assertTrue(result.getInputTableNames().contains("payments"));
    }

    // ==================== 10. CASE WHEN 深度验证 ====================

    @Test
    void caseWhenWithMultipleColumnReferences() {
        String sql = "SELECT CASE WHEN score >= 90 THEN grade_level " +
                "WHEN score >= 60 THEN 'pass' " +
                "ELSE 'fail' END AS result " +
                "FROM exam_results";
        LineageResult result = parse(sql);

        ColumnLineage cl = result.getColumnLineages().get(0);
        assertEquals("result", cl.getOutputColumn().getColumnName());

        // CASE WHEN 应提取 score 和 grade_level 两列
        List<String> sourceNames = cl.getSourceColumns().stream()
                .map(ColumnInfo::getColumnName)
                .collect(java.util.stream.Collectors.toList());
        assertTrue(sourceNames.contains("score"), "CASE WHEN 条件引用的 score 应出现在来源列");
        assertTrue(sourceNames.contains("grade_level"), "CASE WHEN THEN 子句中引用的 grade_level 应出现在来源列");
    }

    @Test
    void caseWhenSimpleFormatWithColumn() {
        String sql = "SELECT CASE status WHEN 1 THEN 'active' WHEN 0 THEN 'inactive' ELSE 'unknown' END AS status_text FROM users";
        LineageResult result = parse(sql);

        ColumnLineage cl = result.getColumnLineages().get(0);
        assertEquals("status_text", cl.getOutputColumn().getColumnName());
        List<String> sourceNames = cl.getSourceColumns().stream()
                .map(ColumnInfo::getColumnName)
                .collect(java.util.stream.Collectors.toList());
        assertTrue(sourceNames.contains("status"),
                "CASE value WHEN 格式中的 value 列应出现在来源列");
    }

    // ==================== 11. JOIN USING 场景 ====================

    @Test
    void joinUsingClause() {
        String sql = "SELECT e.id, e.name, d.dept_name " +
                "FROM employees e INNER JOIN departments d USING (dept_id)";
        LineageResult result = parse(sql);

        assertTrue(result.getInputTableNames().contains("employees"));
        assertTrue(result.getInputTableNames().contains("departments"));
        assertEquals(3, result.getColumnLineages().size());
    }

    // ==================== 12. 链式子查询穿透验证 ====================

    @Test
    void threeLevelSubqueryColumnPassthrough() {
        String sql = "SELECT final_val FROM (" +
                "SELECT derived_val AS final_val FROM (" +
                "SELECT raw_val AS derived_val FROM (" +
                "SELECT id AS raw_val FROM source_table" +
                ") l1" +
                ") l2" +
                ") l3";
        LineageResult result = parse(sql);

        assertEquals(1, result.getColumnLineages().size());
        ColumnLineage cl = result.getColumnLineages().get(0);

        // 收集完整的穿透列名链
        List<String> chain = new java.util.ArrayList<>();
        java.util.List<ColumnInfo> currentSources = cl.getSourceColumns();
        while (currentSources != null && !currentSources.isEmpty()) {
            ColumnInfo first = currentSources.get(0);
            chain.add(first.getColumnName());
            currentSources = first.getSourceColumns();
        }

        // 最终应穿透到 source_table.id
        String leafColumn = chain.get(chain.size() - 1);
        assertEquals("id", leafColumn, "三层子查询应穿透至物理列 id");
    }

    // ==================== 13. Join 多条件 ON ====================

    @Test
    void joinWithMultipleOnConditions() {
        String sql = "SELECT a.id, b.code FROM table_a a " +
                "INNER JOIN table_b b ON a.key = b.key AND a.type = b.type";
        LineageResult result = parse(sql);

        assertTrue(result.getInputTableNames().contains("table_a"));
        assertTrue(result.getInputTableNames().contains("table_b"));
        assertEquals(2, result.getColumnLineages().size());
    }

    // ==================== 14. 函数与管道表达式 ====================

    @Test
    void ifFunctionShouldExtractArguments() {
        String sql = "SELECT IF(status = 1, name, 'N/A') AS display_name FROM users";
        LineageResult result = parse(sql);

        ColumnLineage cl = result.getColumnLineages().get(0);
        assertEquals("display_name", cl.getOutputColumn().getColumnName());

        List<String> sourceNames = cl.getSourceColumns().stream()
                .map(ColumnInfo::getColumnName)
                .collect(java.util.stream.Collectors.toList());
        assertTrue(sourceNames.contains("status"),
                "IF 函数的条件参数 status 应出现在来源列");
        assertTrue(sourceNames.contains("name"),
                "IF 函数的真值参数 name 应出现在来源列");
    }

    @Test
    void coalesceFunctionShouldExtractArguments() {
        String sql = "SELECT COALESCE(phone, mobile, email) AS contact FROM customers";
        LineageResult result = parse(sql);

        ColumnLineage cl = result.getColumnLineages().get(0);
        List<String> sourceNames = cl.getSourceColumns().stream()
                .map(ColumnInfo::getColumnName)
                .collect(java.util.stream.Collectors.toList());
        assertTrue(sourceNames.contains("phone"));
        assertTrue(sourceNames.contains("mobile"));
        assertTrue(sourceNames.contains("email"));
    }

    // ==================== 15. SELECT * 场景 ====================

    @Test
    void selectStarShouldSetTransformation() {
        String sql = "SELECT * FROM employees";
        LineageResult result = parse(sql);

        assertEquals(1, result.getColumnLineages().size());
        assertEquals("*", result.getColumnLineages().get(0).getOutputColumn().getColumnName());
        assertEquals("SELECT *", result.getColumnLineages().get(0).getTransformation());
    }

    @Test
    void selectStarWithTablePrefix() {
        String sql = "SELECT e.* FROM employees e";
        LineageResult result = parse(sql);

        ColumnLineage cl = result.getColumnLineages().get(0);
        assertEquals("*", cl.getOutputColumn().getColumnName());
        assertEquals("SELECT *", cl.getTransformation());
    }

    // ==================== 16. SELECT 子查询多列映射 ====================

    @Test
    void subqueryInSelectWithMultipleColumns() {
        String sql = "SELECT id, " +
                "(SELECT MAX(amount) FROM orders WHERE orders.user_id = users.id) AS max_amt, " +
                "(SELECT COUNT(*) FROM orders WHERE orders.user_id = users.id) AS order_cnt " +
                "FROM users";
        LineageResult result = parse(sql);

        assertTrue(result.getInputTableNames().contains("users"));
        assertTrue(result.getInputTableNames().contains("orders"));

        List<String> outputNames = result.getColumnLineages().stream()
                .map(cl -> cl.getOutputColumn().getColumnName())
                .collect(java.util.stream.Collectors.toList());
        assertTrue(outputNames.contains("id"));
        assertTrue(outputNames.contains("max_amt"));
        assertTrue(outputNames.contains("order_cnt"));
    }

    // ==================== 17. 列血缘 transformation 验证 ====================

    @Test
    void directMappingTransformation() {
        String sql = "SELECT id, name FROM users";
        LineageResult result = parse(sql);

        for (ColumnLineage cl : result.getColumnLineages()) {
            assertEquals("direct mapping", cl.getTransformation(),
                    "简单列引用应为 direct mapping: " + cl.getOutputColumn().getColumnName());
        }
    }

    @Test
    void aliasColumnShouldRetainTransformation() {
        String sql = "SELECT salary * 1.1 AS raised_salary FROM employees";
        LineageResult result = parse(sql);

        ColumnLineage cl = result.getColumnLineages().get(0);
        assertEquals("raised_salary", cl.getOutputColumn().getColumnName());
        assertNotNull(cl.getTransformation());
        assertNotEquals("direct mapping", cl.getTransformation(),
                "表达式列不应是 direct mapping");
    }

    // ==================== 18. 输入表去重 ====================

    @Test
    void duplicateInputTableShouldBeDeduplicated() {
        // 自关联时同一张表只应出现一次
        String sql = "SELECT a.id, b.name FROM employees a, employees b WHERE a.manager_id = b.id";
        LineageResult result = parse(sql);

        long employeesCount = result.getInputTables().stream()
                .filter(t -> "employees".equals(t.getTableName()))
                .count();
        assertEquals(1, employeesCount, "同一张物理表多次出现应去重");
    }

    // ==================== 19. 聚合函数与 GROUP BY 组合 ====================

    @Test
    void multipleAggregatesWithGroupBy() {
        String sql = "SELECT dept_id, " +
                "COUNT(*) AS total, " +
                "SUM(salary) AS total_salary, " +
                "AVG(salary) AS avg_salary, " +
                "MIN(hire_date) AS first_hire, " +
                "MAX(hire_date) AS last_hire " +
                "FROM employees GROUP BY dept_id";
        LineageResult result = parse(sql);

        List<String> outputNames = result.getColumnLineages().stream()
                .map(cl -> cl.getOutputColumn().getColumnName())
                .collect(java.util.stream.Collectors.toList());

        assertTrue(outputNames.contains("dept_id"));
        assertTrue(outputNames.contains("total"));
        assertTrue(outputNames.contains("total_salary"));
        assertTrue(outputNames.contains("avg_salary"));
        assertTrue(outputNames.contains("first_hire"));
        assertTrue(outputNames.contains("last_hire"));
    }

    // ==================== 20. 空结果 / 非法 SQL 异常 ====================

    @Test
    void invalidSqlShouldThrowException() {
        String sql = "SELECT FROM";
        assertThrows(Exception.class, () -> parse(sql),
                "非法 SQL 应抛出异常");
    }
}
