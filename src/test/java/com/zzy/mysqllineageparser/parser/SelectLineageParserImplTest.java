package com.zzy.mysqllineageparser.parser;

import com.zzy.mysqllineageparser.model.ColumnInfo;
import com.zzy.mysqllineageparser.model.ColumnLineage;
import com.zzy.mysqllineageparser.model.LineageResult;
import com.zzy.mysqllineageparser.model.TableInfo;
import com.zzy.mysqllineageparser.mybatis.support.TableMetaSupport;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SELECT 语句血缘解析测试类
 */
class SelectLineageParserImplTest {

    private SqlLineageParser parser;

    @BeforeEach
    void setUp() {
        List<StatementParseStrategy> strategies = new ArrayList<>();
        strategies.add(new CreateTableParseStrategy());
        strategies.add(new InsertParseStrategy());
        strategies.add(new SelectParseStrategy(null));
        strategies.add(new UpdateParseStrategy());
        strategies.add(new DeleteParseStrategy());

        ParseStrategyFactory factory = new ParseStrategyFactory(strategies);
        parser = new MysqlLineageParserImpl(factory);
    }

    // ========== 简单 SELECT ==========

    @Test
    void testSelectSimple() {
        String sql = "SELECT id, name FROM users;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());

        TableInfo inputTable = result.getInputTables().get(0);
        assertEquals("users", inputTable.getTableName());

        // 验证列血缘
        assertNotNull(result.getColumnLineages());
        assertEquals(2, result.getColumnLineages().size());

        assertEquals("id", result.getColumnLineages().get(0).getOutputColumn().getColumnName());
        assertEquals("name", result.getColumnLineages().get(1).getOutputColumn().getColumnName());
    }

    @Test
    void testSelectWithDatabasePrefix() {
        String sql = "SELECT id, name FROM testdb.users;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());

        TableInfo inputTable = result.getInputTables().get(0);
        assertEquals("users", inputTable.getTableName());
        assertEquals("testdb", inputTable.getDatabaseName());
        assertEquals("testdb.users", inputTable.getFullName());
    }

    @Test
    void testSelectAllColumns() {
        String sql = "SELECT * FROM employees;";

        // 注入 employees 表元数据，列：id / name / salary
        TableMetaSupport tableMetaSupport = tableName -> "employees".equals(tableName)
                ? Arrays.asList(
                        new ColumnInfo(null, "id"),
                        new ColumnInfo(null, "name"),
                        new ColumnInfo(null, "salary"))
                : Collections.emptyList();

        // 构建带元数据的 parser（隔离于共享 parser，不影响其他用例）
        List<StatementParseStrategy> strategies = new ArrayList<>();
        strategies.add(new CreateTableParseStrategy());
        strategies.add(new InsertParseStrategy());
        strategies.add(new SelectParseStrategy(tableMetaSupport));
        strategies.add(new UpdateParseStrategy());
        strategies.add(new DeleteParseStrategy());
        SqlLineageParser metaParser = new MysqlLineageParserImpl(new ParseStrategyFactory(strategies));

        LineageResult result = metaParser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());
        assertEquals("employees", result.getInputTables().get(0).getTableName());

        // 验证列血缘：SELECT * 应展开为 employees 的具体列，而非单个 "*"
        assertNotNull(result.getColumnLineages());
        assertEquals(3, result.getColumnLineages().size());

        List<String> outputColumns = result.getColumnLineages().stream()
                .map(cl -> cl.getOutputColumn().getColumnName())
                .collect(Collectors.toList());
        assertTrue(outputColumns.contains("id"), "应展开出 id");
        assertTrue(outputColumns.contains("name"), "应展开出 name");
        assertTrue(outputColumns.contains("salary"), "应展开出 salary");
        assertFalse(outputColumns.contains("*"), "不应残留未展开的 '*'");
    }

    // ========== 带别名的 SELECT ==========

    @Test
    void testSelectWithAlias() {
        String sql = "SELECT u.id, u.name FROM users AS u;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());

        TableInfo inputTable = result.getInputTables().get(0);
        assertEquals("users", inputTable.getTableName());
        assertEquals("u", inputTable.getAlias());
    }

    @Test
    void testSelectWithColumnAlias() {
        String sql = "SELECT id AS user_id, name AS user_name FROM users;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals(2, result.getColumnLineages().size());

        assertEquals("user_id", result.getColumnLineages().get(0).getOutputColumn().getColumnName());
        assertEquals("user_name", result.getColumnLineages().get(1).getOutputColumn().getColumnName());
    }

    // ========== WHERE 条件 ==========

    @Test
    void testSelectWithWhere() {
        String sql = "SELECT id, name FROM users WHERE status = 1;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());
        assertEquals("users", result.getInputTables().get(0).getTableName());

        // 验证列血缘
        assertNotNull(result.getColumnLineages());
        assertEquals(2, result.getColumnLineages().size());

        // 验证过滤条件
        ColumnLineage lineage = result.getColumnLineages().get(0);
        assertNotNull(lineage.getFilterCondition());
        assertTrue(lineage.getFilterCondition().contains("status"));
    }

    // ========== JOIN 查询 ==========

    @Test
    void testSelectInnerJoin() {
        String sql = "SELECT o.id, o.amount, u.name " +
                "FROM orders o INNER JOIN users u ON o.user_id = u.id;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(2, result.getInputTables().size());

        // 验证输入表
        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("orders"));
        assertTrue(inputTableNames.contains("users"));

        // 验证列血缘
        assertNotNull(result.getColumnLineages());
        assertEquals(3, result.getColumnLineages().size());
    }

    @Test
    void testSelectLeftJoin() {
        String sql = "SELECT e.id, e.name, d.dept_name " +
                "FROM employees e LEFT JOIN departments d ON e.dept_id = d.id;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(2, result.getInputTables().size());

        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("employees"));
        assertTrue(inputTableNames.contains("departments"));

        assertNotNull(result.getColumnLineages());
        assertEquals(3, result.getColumnLineages().size());
    }

    @Test
    void testSelectJoinWithDatabasePrefix() {
        String sql = "SELECT a.id, b.name " +
                "FROM db1.table_a a JOIN db2.table_b b ON a.id = b.aid;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(2, result.getInputTables().size());

        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("db1.table_a"));
        assertTrue(inputTableNames.contains("db2.table_b"));
    }

    // ========== 表达式与函数 ==========

    @Test
    void testSelectWithExpression() {
        String sql = "SELECT salary * 1.1 AS new_salary FROM employees;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());
        assertEquals("employees", result.getInputTables().get(0).getTableName());

        // 验证列血缘
        assertNotNull(result.getColumnLineages());
        assertEquals(1, result.getColumnLineages().size());

        ColumnLineage lineage = result.getColumnLineages().get(0);
        assertEquals("new_salary", lineage.getOutputColumn().getColumnName());
        assertNotNull(lineage.getTransformation());
        // 表达式中应包含 salary
        assertTrue(lineage.getSourceColumns().stream()
                .anyMatch(col -> "salary".equals(col.getColumnName())));
    }

    @Test
    void testSelectWithFunction() {
        String sql = "SELECT CONCAT(first_name, ' ', last_name) AS full_name FROM employees;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());

        // 验证列血缘
        assertNotNull(result.getColumnLineages());
        assertEquals(1, result.getColumnLineages().size());

        ColumnLineage lineage = result.getColumnLineages().get(0);
        assertEquals("full_name", lineage.getOutputColumn().getColumnName());
        assertNotNull(lineage.getTransformation());

        // 验证来源列包含 first_name 和 last_name
        List<String> sourceColNames = new ArrayList<>();
        for (ColumnInfo col : lineage.getSourceColumns()) {
            sourceColNames.add(col.getColumnName());
        }
        assertTrue(sourceColNames.contains("first_name"));
        assertTrue(sourceColNames.contains("last_name"));
    }

    @Test
    void testSelectWithAggregateFunction() {
        String sql = "SELECT dept_id, COUNT(*) AS cnt, AVG(salary) AS avg_salary FROM employees GROUP BY dept_id;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());
        assertEquals("employees", result.getInputTables().get(0).getTableName());

        // 验证列血缘
        assertNotNull(result.getColumnLineages());
        assertEquals(3, result.getColumnLineages().size());
    }

    // ========== 带反引号的标识符 ==========

    @Test
    void testSelectWithBackticks() {
        String sql = "SELECT `id`, `name` FROM `users`;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());
        assertEquals("users", result.getInputTables().get(0).getTableName());

        assertNotNull(result.getColumnLineages());
        assertTrue(result.getColumnLineages().size() >= 1);
    }

    @Test
    void testSelectWithBackticksAndDatabase() {
        String sql = "SELECT `u`.`id`, `u`.`name` FROM `mydb`.`users` AS `u`;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());

        TableInfo inputTable = result.getInputTables().get(0);
        assertEquals("users", inputTable.getTableName());
        assertEquals("mydb", inputTable.getDatabaseName());
    }

    // ========== 上下文与报告 ==========

    @Test
    void testSelectWithContext() {
        String sql = "SELECT id, name FROM users;";
        ParseContext context = ParseContext.withDatabase("myapp");

        LineageResult result = parser.parse(sql, context);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());
    }

    @Test
    void testSelectGenerateReport() {
        String sql = "SELECT id, name FROM users WHERE status = 1;";
        LineageResult result = parser.parse(sql);

        String report = result.generateReport();

        assertNotNull(report);
        assertTrue(report.contains("SQL Type: SELECT"));
        assertTrue(report.contains("Input Tables:"));
        assertTrue(report.contains("users"));
    }

    // ========== 空值和异常 ==========

    @Test
    void testSelectEmptySql() {
        assertThrows(IllegalArgumentException.class, () -> {
            parser.parse("");
        });
    }

    @Test
    void testSelectNullSql() {
        assertThrows(IllegalArgumentException.class, () -> {
            parser.parse(null);
        });
    }

    // ========== 带表前缀的列引用 ==========

    @Test
    void testSelectWithTablePrefixColumns() {
        String sql = "SELECT users.id, users.name FROM users;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());

        // 验证列血缘
        assertNotNull(result.getColumnLineages());
        assertEquals(2, result.getColumnLineages().size());

        ColumnLineage idLineage = result.getColumnLineages().get(0);
        assertEquals("id", idLineage.getOutputColumn().getColumnName());
        assertEquals("direct mapping", idLineage.getTransformation());
    }

    @Test
    void testSelectWithAliasTablePrefixColumns() {
        String sql = "SELECT e.id, e.name, d.dept_name " +
                "FROM employees e, departments d WHERE e.dept_id = d.id;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(2, result.getInputTables().size());

        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("employees"));
        assertTrue(inputTableNames.contains("departments"));

        // 验证列血缘
        assertNotNull(result.getColumnLineages());
        assertEquals(3, result.getColumnLineages().size());
    }

    // ========== 子查询 ==========

    @Test
    void testSelectFromSubquery() {
        String sql = "SELECT t.id, t.name FROM (SELECT id, name FROM users) t;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());
        assertEquals("users", result.getInputTables().get(0).getTableName());

        // 验证列血缘
        assertNotNull(result.getColumnLineages());
        assertEquals(2, result.getColumnLineages().size());

        assertEquals("id", result.getColumnLineages().get(0).getOutputColumn().getColumnName());
        assertEquals("name", result.getColumnLineages().get(1).getOutputColumn().getColumnName());
    }

    @Test
    void testSelectFromSubqueryWithAlias() {
        String sql = "SELECT t.user_id, t.user_name " +
                "FROM (SELECT id AS user_id, name AS user_name FROM users) t;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());
        assertEquals("users", result.getInputTables().get(0).getTableName());

        // 验证列血缘
        assertNotNull(result.getColumnLineages());
        assertEquals(2, result.getColumnLineages().size());

        ColumnLineage idLineage = result.getColumnLineages().get(0);
        assertEquals("user_id", idLineage.getOutputColumn().getColumnName());

        ColumnLineage nameLineage = result.getColumnLineages().get(1);
        assertEquals("user_name", nameLineage.getOutputColumn().getColumnName());
    }

    @Test
    void testSelectFromSubqueryWithWhere() {
        String sql = "SELECT t.id, t.name FROM (SELECT id, name FROM users WHERE age > 18) t WHERE t.id > 100;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());
        assertEquals("users", result.getInputTables().get(0).getTableName());

        // 验证列血缘
        assertNotNull(result.getColumnLineages());
        assertEquals(2, result.getColumnLineages().size());

        // 验证外层 WHERE 过滤条件
        ColumnLineage lineage = result.getColumnLineages().get(0);
        assertNotNull(lineage.getFilterCondition());
        assertTrue(lineage.getFilterCondition().contains("id"));
    }

    @Test
    void testSelectFromSubqueryWithExpression() {
        String sql = "SELECT t.total FROM (SELECT id, salary * 1.1 AS total FROM employees) t;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());
        assertEquals("employees", result.getInputTables().get(0).getTableName());

        // 验证列血缘
        assertNotNull(result.getColumnLineages());
        assertEquals(1, result.getColumnLineages().size());

        ColumnLineage lineage = result.getColumnLineages().get(0);
        assertEquals("total", lineage.getOutputColumn().getColumnName());

        // 验证来源列包含 salary（通过子查询穿透）
        List<String> sourceColNames = new ArrayList<>();
        for (ColumnInfo col : lineage.getSourceColumns()) {
            sourceColNames.add(col.getColumnName());
        }
        assertTrue(sourceColNames.contains("salary"));
    }

    // ========== 子查询 + JOIN ==========

    @Test
    void testSubqueryJoinTable() {
        String sql = "SELECT t.user_name, o.amount " +
                "FROM (SELECT id, name AS user_name FROM users) t " +
                "JOIN orders o ON t.id = o.user_id;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(2, result.getInputTables().size());

        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("users"));
        assertTrue(inputTableNames.contains("orders"));

        // 验证列血缘
        assertNotNull(result.getColumnLineages());
        assertEquals(2, result.getColumnLineages().size());

        assertEquals("user_name", result.getColumnLineages().get(0).getOutputColumn().getColumnName());
        assertEquals("amount", result.getColumnLineages().get(1).getOutputColumn().getColumnName());
    }

    @Test
    void testTableJoinSubquery() {
        String sql = "SELECT u.name, t.total " +
                "FROM users u " +
                "JOIN (SELECT user_id, SUM(amount) AS total FROM orders GROUP BY user_id) t " +
                "ON u.id = t.user_id;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(2, result.getInputTables().size());

        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("users"));
        assertTrue(inputTableNames.contains("orders"));

        // 验证列血缘
        assertNotNull(result.getColumnLineages());
        assertEquals(2, result.getColumnLineages().size());

        assertEquals("name", result.getColumnLineages().get(0).getOutputColumn().getColumnName());
        assertEquals("total", result.getColumnLineages().get(1).getOutputColumn().getColumnName());
    }

    @Test
    void testSubqueryJoinSubquery() {
        String sql = "SELECT u.name, o.total " +
                "FROM (SELECT id, name FROM users) u " +
                "JOIN (SELECT user_id, SUM(amount) AS total FROM orders GROUP BY user_id) o " +
                "ON u.id = o.user_id;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(2, result.getInputTables().size());

        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("users"));
        assertTrue(inputTableNames.contains("orders"));

        // 验证列血缘
        assertNotNull(result.getColumnLineages());
        assertEquals(2, result.getColumnLineages().size());

        // 验证 name 的来源列
        ColumnLineage nameLineage = result.getColumnLineages().get(0);
        assertEquals("name", nameLineage.getOutputColumn().getColumnName());
        assertNotNull(nameLineage.getSourceColumns());
        assertEquals(1, nameLineage.getSourceColumns().size());
        assertEquals("name", nameLineage.getSourceColumns().get(0).getColumnName());

        // 验证 total 的来源列和聚合转换
        ColumnLineage totalLineage = result.getColumnLineages().get(1);
        assertEquals("total", totalLineage.getOutputColumn().getColumnName());
        assertNotNull(totalLineage.getSourceColumns());
        assertEquals(1, totalLineage.getSourceColumns().size());
        assertEquals("amount", totalLineage.getSourceColumns().get(0).getColumnName());
        assertNotNull(totalLineage.getTransformation());
        assertTrue(totalLineage.getTransformation().toUpperCase().contains("SUM"));
    }

    @Test
    void testSubqueryLeftJoinWithWhere() {
        String sql = "SELECT e.name, s.avg_salary " +
                "FROM employees e " +
                "LEFT JOIN (SELECT dept_id, AVG(salary) AS avg_salary FROM employees GROUP BY dept_id) s " +
                "ON e.dept_id = s.dept_id " +
                "WHERE e.status = 1;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(1, result.getInputTables().size());
        assertEquals("employees", result.getInputTables().get(0).getTableName());

        // 验证列血缘
        assertNotNull(result.getColumnLineages());
        assertEquals(2, result.getColumnLineages().size());

        assertEquals("name", result.getColumnLineages().get(0).getOutputColumn().getColumnName());
        assertEquals("avg_salary", result.getColumnLineages().get(1).getOutputColumn().getColumnName());

        // 验证 WHERE 过滤条件
        ColumnLineage lineage = result.getColumnLineages().get(0);
        assertNotNull(lineage.getFilterCondition());
        assertTrue(lineage.getFilterCondition().contains("status"));
    }

    // ========== 通配符 + 具体字段 + 表与子查询混合 ==========

    @Test
    void testSelectWithWildcardAndSpecificColumnsFromTableAndSubquery() {
        String sql = "SELECT t.*, o.amount, o.status "
                + "FROM (SELECT * FROM users) t "
                + "JOIN orders o ON t.id = o.user_id;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());
        assertEquals(2, result.getInputTables().size());

        List<String> inputTableNames = result.getInputTableNames();
        assertTrue(inputTableNames.contains("users"));
        assertTrue(inputTableNames.contains("orders"));

        // 通配符展开或保留，验证结果不为空
        assertNotNull(result.getColumnLineages());
        assertTrue(result.getColumnLineages().size() >= 3);

        // 收集所有输出列名，便于按名称断言
        List<String> outputColNames = new ArrayList<>();
        for (ColumnLineage cl : result.getColumnLineages()) {
            outputColNames.add(cl.getOutputColumn().getColumnName());
        }

        // 1) t.* 通配符：输出列名为 "*"，transformation 为 "SELECT *"
        ColumnLineage wildcardLineage = result.getColumnLineages().stream()
                .filter(cl -> "*".equals(cl.getOutputColumn().getColumnName()))
                .findFirst().orElse(null);
        assertNotNull(wildcardLineage, "应存在通配符 t.* 的列血缘");
        assertEquals("SELECT *", wildcardLineage.getTransformation());

        // 2) o.amount：来源为 orders.amount，直接映射
        ColumnLineage amountLineage = result.getColumnLineages().stream()
                .filter(cl -> "amount".equals(cl.getOutputColumn().getColumnName()))
                .findFirst().orElse(null);
        assertNotNull(amountLineage, "应存在 amount 列血缘");
        assertEquals("direct mapping", amountLineage.getTransformation());
        assertEquals(1, amountLineage.getSourceColumns().size());
        assertEquals("amount", amountLineage.getSourceColumns().get(0).getColumnName());
        assertEquals("orders", amountLineage.getSourceColumns().get(0).getTable().getTableName());

        // 3) o.status：来源为 orders.status，直接映射
        ColumnLineage statusLineage = result.getColumnLineages().stream()
                .filter(cl -> "status".equals(cl.getOutputColumn().getColumnName()))
                .findFirst().orElse(null);
        assertNotNull(statusLineage, "应存在 status 列血缘");
        assertEquals("direct mapping", statusLineage.getTransformation());
        assertEquals(1, statusLineage.getSourceColumns().size());
        assertEquals("status", statusLineage.getSourceColumns().get(0).getColumnName());
        assertEquals("orders", statusLineage.getSourceColumns().get(0).getTable().getTableName());
    }
}
