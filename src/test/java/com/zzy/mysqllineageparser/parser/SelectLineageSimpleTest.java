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
 * 简单 SELECT 语句血缘解析测试类
 * <p>
 * 简单查询判定标准：SQL 中没有 JOIN、没有 GROUP BY、没有子查询。
 */
class SelectLineageSimpleTest {

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
        assertEquals(1, result.getColumnLineages().size());

        List<String> outputColumns = result.getColumnLineages().stream()
                .map(cl -> cl.getOutputColumn().getColumnName())
                .collect(Collectors.toList());
        assertTrue(outputColumns.contains("id"), "应展开出 id");
        assertTrue(outputColumns.contains("name"), "应展开出 name");
        assertTrue(outputColumns.contains("salary"), "应展开出 salary");
        assertFalse(outputColumns.contains("*"), "不应残留未展开的 '*'");
    }

    @Test
    void testSelectAllColumnsWithDatabaseWildcard() {
        // db.* 通配符：owner 为库名，应按库名匹配到该库下的来源表并展开其全部列
        String sql = "SELECT mydb.* FROM mydb.employees;";

        TableMetaSupport tableMetaSupport = tableName -> "employees".equals(tableName)
                ? Arrays.asList(
                        new ColumnInfo(null, "id"),
                        new ColumnInfo(null, "name"),
                        new ColumnInfo(null, "salary"))
                : Collections.emptyList();

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
        assertEquals("mydb", result.getInputTables().get(0).getDatabaseName());

        // 通配符血缘：outputColumn="*"，sourceColumns 展开为 mydb.employees 的全部列
        assertNotNull(result.getColumnLineages());
        assertEquals(1, result.getColumnLineages().size());

        ColumnLineage lineage = result.getColumnLineages().get(0);
        assertEquals("*", lineage.getOutputColumn().getColumnName());
        assertEquals("SELECT *", lineage.getTransformation());

        List<String> sourceColNames = lineage.getSourceColumns().stream()
                .map(ColumnInfo::getColumnName)
                .collect(Collectors.toList());
        assertTrue(sourceColNames.contains("id"), "应展开出 id");
        assertTrue(sourceColNames.contains("name"), "应展开出 name");
        assertTrue(sourceColNames.contains("salary"), "应展开出 salary");
        assertEquals(3, lineage.getSourceColumns().size(), "应展开出全部 3 列");
    }

    @Test
    void testSelectAllColumnsWithFullyQualifiedWildcard() {
        // db.table.* 通配符：owner 为两级限定，应按 库名.表名 精确匹配并展开其全部列
        String sql = "SELECT mydb.employees.* FROM mydb.employees;";

        TableMetaSupport tableMetaSupport = tableName -> "employees".equals(tableName)
                ? Arrays.asList(
                        new ColumnInfo(null, "id"),
                        new ColumnInfo(null, "name"))
                : Collections.emptyList();

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
        assertEquals("mydb", result.getInputTables().get(0).getDatabaseName());

        assertNotNull(result.getColumnLineages());
        assertEquals(1, result.getColumnLineages().size());

        ColumnLineage lineage = result.getColumnLineages().get(0);
        assertEquals("*", lineage.getOutputColumn().getColumnName());
        assertEquals("SELECT *", lineage.getTransformation());

        List<String> sourceColNames = lineage.getSourceColumns().stream()
                .map(ColumnInfo::getColumnName)
                .collect(Collectors.toList());
        assertTrue(sourceColNames.contains("id"), "应展开出 id");
        assertTrue(sourceColNames.contains("name"), "应展开出 name");
        assertEquals(2, lineage.getSourceColumns().size(), "应展开出全部 2 列");
    }

    @Test
    void testSelectDatabaseWildcardAcrossTablesInSameDb() {
        // db.* 应展开该库下的所有来源表（多表场景）
        String sql = "SELECT mydb.* FROM mydb.users JOIN mydb.orders ON mydb.users.id = mydb.orders.uid;";

        TableMetaSupport tableMetaSupport = tableName -> {
            switch (tableName) {
                case "users":
                    return Arrays.asList(new ColumnInfo(null, "uid"), new ColumnInfo(null, "uname"));
                case "orders":
                    return Arrays.asList(new ColumnInfo(null, "oid"), new ColumnInfo(null, "amount"));
                default:
                    return Collections.emptyList();
            }
        };

        List<StatementParseStrategy> strategies = new ArrayList<>();
        strategies.add(new CreateTableParseStrategy());
        strategies.add(new InsertParseStrategy());
        strategies.add(new SelectParseStrategy(tableMetaSupport));
        strategies.add(new UpdateParseStrategy());
        strategies.add(new DeleteParseStrategy());
        SqlLineageParser metaParser = new MysqlLineageParserImpl(new ParseStrategyFactory(strategies));

        LineageResult result = metaParser.parse(sql);

        assertNotNull(result);
        assertEquals(2, result.getInputTables().size());

        ColumnLineage lineage = result.getColumnLineages().get(0);
        assertEquals("*", lineage.getOutputColumn().getColumnName());

        List<String> sourceColNames = lineage.getSourceColumns().stream()
                .map(ColumnInfo::getColumnName)
                .collect(Collectors.toList());
        // users 与 orders 同属 mydb，db.* 应同时展开两表的列
        assertTrue(sourceColNames.contains("uid"));
        assertTrue(sourceColNames.contains("uname"));
        assertTrue(sourceColNames.contains("oid"));
        assertTrue(sourceColNames.contains("amount"));
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

    // ========== 带表前缀的列引用（单表） ==========

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
}
