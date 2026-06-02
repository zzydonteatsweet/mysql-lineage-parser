package com.zzy.mysqllineageparser.parser;

import com.alibaba.druid.DbType;
import com.zzy.mysqllineageparser.visitor.helper.AstTreePrinter;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlCreateTableStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlInsertStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlUpdateStatement;
import com.alibaba.druid.util.JdbcConstants;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用原生 Druid 解析器解析 MySQL 语句的测试类
 */
class DruidNativeParserTest {

    // ==================== CREATE 语句测试 ====================

    @Test
    void testCreateTableSimple() {
        String sql = "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100));";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        assertEquals(1, statements.size());

        MySqlCreateTableStatement stmt = (MySqlCreateTableStatement) statements.get(0);
        assertNotNull(stmt);

        // 验证表名
        assertEquals("users", stmt.getTableSource().getName().toString());
        assertNull(stmt.getTableSource().getSchema());

        // 验证列定义
        assertEquals(2, stmt.getTableElementList().size());

        // 验证 SQL 格式化输出
        String formatted = SQLUtils.toSQLString(stmt, DbType.mysql);
        assertNotNull(formatted);
        assertTrue(formatted.toUpperCase().contains("CREATE TABLE"));

        // 打印 AST 语法树结构
        System.out.println("===== AST 语法树 =====");
        System.out.println(AstTreePrinter.print(stmt));
        System.out.println("======================");
    }

    private void printAst(SQLObject obj, int indent) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
        String prefix = sb.toString();
        String content = obj.toString().replace("\n", "\n" + prefix);
        System.out.println(prefix + obj.getClass().getSimpleName() + ": " + content);
        List<SQLObject> children = getChildSqlObjects(obj);
        for (SQLObject child : children) {
            printAst(child, indent + 1);
        }
    }

    private List<SQLObject> getChildSqlObjects(SQLObject obj) {
        List<SQLObject> children = new ArrayList<SQLObject>();
        try {
            for (Class<?> clazz = obj.getClass(); clazz != Object.class; clazz = clazz.getSuperclass()) {
                for (Field field : clazz.getDeclaredFields()) {
                    field.setAccessible(true);
                    Object value = field.get(obj);
                    if (value instanceof SQLObject) {
                        children.add((SQLObject) value);
                    } else if (value instanceof List) {
                        for (Object item : (List<?>) value) {
                            if (item instanceof SQLObject) {
                                children.add((SQLObject) item);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return children;
    }

    @Test
    void testCreateTableWithDatabase() {
        String sql = "CREATE TABLE testdb.users (id INT PRIMARY KEY, name VARCHAR(100));";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        MySqlCreateTableStatement stmt = (MySqlCreateTableStatement) statements.get(0);

        // 验证数据库名和表名
        assertEquals("testdb", stmt.getTableSource().getSchema());
        assertEquals("users", stmt.getTableSource().getName());
    }

    @Test
    void testCreateTableWithBackticks() {
        String sql = "CREATE TABLE `mydb`.`employees` (\n" +
                "  `id` INT PRIMARY KEY,\n" +
                "  `first_name` VARCHAR(50),\n" +
                "  `last_name` VARCHAR(50)\n" +
                ");";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        MySqlCreateTableStatement stmt = (MySqlCreateTableStatement) statements.get(0);

        // 验证表名（反引号应被正确解析）
        assertEquals("mydb", stmt.getTableSource().getSchema());
        assertEquals("employees", stmt.getTableSource().getName());
        assertEquals(3, stmt.getTableElementList().size());
    }

    @Test
    void testCreateTableIfNotExists() {
        String sql = "CREATE TABLE IF NOT EXISTS orders (id INT PRIMARY KEY, order_date DATE);";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        MySqlCreateTableStatement stmt = (MySqlCreateTableStatement) statements.get(0);

        assertTrue(stmt.isIfNotExists());
        assertEquals("orders", stmt.getTableSource().getName());
    }

    @Test
    void testCreateTableWithConstraints() {
        String sql = "CREATE TABLE orders (\n" +
                "  id BIGINT NOT NULL AUTO_INCREMENT,\n" +
                "  order_no VARCHAR(50) NOT NULL,\n" +
                "  user_id BIGINT NOT NULL,\n" +
                "  amount DECIMAL(10, 2) DEFAULT 0.00,\n" +
                "  PRIMARY KEY (id),\n" +
                "  UNIQUE KEY uk_order_no (order_no),\n" +
                "  KEY idx_user_id (user_id)\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        MySqlCreateTableStatement stmt = (MySqlCreateTableStatement) statements.get(0);

        assertEquals("orders", stmt.getTableSource().getName());
        assertTrue(stmt.getTableElementList().size() >= 7);

        // 验证 ENGINE 属性
        assertNotNull(stmt.getTableOptions());
    }

    @Test
    void testCreateTableWithPartition() {
        String sql = "CREATE TABLE sales (\n" +
                "  id INT NOT NULL,\n" +
                "  sale_date DATE NOT NULL,\n" +
                "  amount DECIMAL(10,2),\n" +
                "  PRIMARY KEY (id, sale_date)\n" +
                ") \n" +
                "PARTITION BY RANGE (YEAR(sale_date)) (\n" +
                "  PARTITION p2020 VALUES LESS THAN (2021),\n" +
                "  PARTITION p2021 VALUES LESS THAN (2022),\n" +
                "  PARTITION pmax VALUES LESS THAN MAXVALUE\n" +
                ");";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        MySqlCreateTableStatement stmt = (MySqlCreateTableStatement) statements.get(0);

        assertEquals("sales", stmt.getTableSource().getName());
        assertNotNull(stmt.getPartitioning());
    }

    // ==================== INSERT 语句测试 ====================

    @Test
    void testInsertSimple() {
        String sql = "INSERT INTO users (id, name, email) VALUES (1, 'Alice', 'alice@example.com');";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        assertEquals(1, statements.size());

        MySqlInsertStatement stmt = (MySqlInsertStatement) statements.get(0);
        assertNotNull(stmt);

        // 验证目标表
        assertEquals("users", stmt.getTableSource().getName());
        assertNull(stmt.getTableSource().getSchema());

        // 验证插入的列
        assertEquals(3, stmt.getColumns().size());
        assertEquals("id", ((SQLIdentifierExpr) stmt.getColumns().get(0)).getName());
        assertEquals("name", ((SQLIdentifierExpr) stmt.getColumns().get(1)).getName());
        assertEquals("email", ((SQLIdentifierExpr) stmt.getColumns().get(2)).getName());

        // 验证 VALUES 子句
        SQLInsertStatement.ValuesClause values = stmt.getValuesList().get(0);
        assertEquals(3, values.getValues().size());
    }

    @Test
    void testInsertWithDatabase() {
        String sql = "INSERT INTO testdb.users (id, name) VALUES (1, 'Bob');";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        MySqlInsertStatement stmt = (MySqlInsertStatement) statements.get(0);

        // 验证带数据库前缀的表名
        assertEquals("testdb", stmt.getTableSource().getSchema());
        assertEquals("users", stmt.getTableSource().getName());
    }

    @Test
    void testInsertSelect() {
        String sql = "INSERT INTO target_users (id, name, email) " +
                "SELECT id, name, email FROM source_users WHERE status = 1;";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        MySqlInsertStatement stmt = (MySqlInsertStatement) statements.get(0);

        // 验证目标表
        assertEquals("target_users", stmt.getTableSource().getName());

        // 验证插入列
        assertEquals(3, stmt.getColumns().size());

        // 验证 SELECT 查询
        SQLSelect select = stmt.getQuery();

        SQLSelectQueryBlock selectQuery = (SQLSelectQueryBlock) select.getQuery();
        // 验证来源表
        SQLTableSource fromTable = selectQuery.getFrom();
        assertEquals("source_users", ((SQLExprTableSource) fromTable).getName());

        // 验证 WHERE 条件
        assertNotNull(selectQuery.getWhere());
        String whereStr = selectQuery.getWhere().toString();
        assertTrue(whereStr.contains("status"));
        assertTrue(whereStr.contains("1"));
    }

    @Test
    void testInsertMultipleValues() {
        String sql = "INSERT INTO products (id, name, price) VALUES " +
                "(1, 'Product A', 10.50), " +
                "(2, 'Product B', 20.00), " +
                "(3, 'Product C', 15.75);";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        MySqlInsertStatement stmt = (MySqlInsertStatement) statements.get(0);

        // 验证多行插入
        assertEquals(3, stmt.getValuesList().size());

        for (SQLInsertStatement.ValuesClause values : stmt.getValuesList()) {
            assertEquals(3, values.getValues().size());
        }
    }

    @Test
    void testInsertWithBackticks() {
        String sql = "INSERT INTO `mydb`.`orders` (`id`, `order_no`, `amount`) " +
                "VALUES (1001, 'ORD-001', 99.99);";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        MySqlInsertStatement stmt = (MySqlInsertStatement) statements.get(0);

        // 反引号应被正确解析
//        assertEquals("mydb", stmt.getTableSource().getSchema().getSimpleName());
        assertEquals("orders", stmt.getTableSource().getName());
        assertEquals(3, stmt.getColumns().size());
    }

    // ==================== SELECT 语句测试 ====================

    @Test
    void testSelectSimple() {
        String sql = "SELECT id, name, email FROM users WHERE age > 18;";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        assertEquals(1, statements.size());

        SQLSelectStatement stmt = (SQLSelectStatement) statements.get(0);
        assertNotNull(stmt);

        SQLSelectQueryBlock query = (SQLSelectQueryBlock) stmt.getSelect().getQuery();
        assertNotNull(query);

        // 验证来源表
        assertEquals("users", ((SQLExprTableSource) query.getFrom()).getName());

        // 验证查询列
        assertEquals(3, query.getSelectList().size());

        // 验证 WHERE 条件
        assertNotNull(query.getWhere());
    }

    @Test
    void testSelectWithAlias() {
        String sql = "SELECT u.id, u.name AS user_name, o.order_no " +
                "FROM users u " +
                "JOIN orders o ON u.id = o.user_id;";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        SQLSelectStatement stmt = (SQLSelectStatement) statements.get(0);
        SQLSelectQueryBlock query = (SQLSelectQueryBlock) stmt.getSelect().getQuery();

        // 验证来源表和别名
        SQLJoinTableSource joinSource = (SQLJoinTableSource) query.getFrom();
        assertEquals("users", ((SQLExprTableSource) joinSource.getLeft()).getName());
        assertEquals("u", ((SQLExprTableSource) joinSource.getLeft()).getAlias());

        // 验证查询列
        assertEquals(3, query.getSelectList().size());
    }

    @Test
    void testSelectWithSubquery() {
        String sql = "SELECT * FROM users WHERE id IN " +
                "(SELECT user_id FROM orders WHERE amount > 100);";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        SQLSelectStatement stmt = (SQLSelectStatement) statements.get(0);
        SQLSelectQueryBlock query = (SQLSelectQueryBlock) stmt.getSelect().getQuery();

        // 验证外层查询表
        assertEquals("users", ((SQLExprTableSource) query.getFrom()).getName());

        // 验证子查询存在
        assertNotNull(query.getWhere());
        String whereStr = SQLUtils.toMySqlString(query.getWhere());
        assertTrue(whereStr.toUpperCase().contains("SELECT"));
        assertTrue(whereStr.contains("orders"));
    }

    @Test
    void testSelectWithFromSubquery() {
        String sql = "SELECT t.dept_id, t.emp_count, t.avg_salary " +
                "FROM (SELECT dept_id, COUNT(*) AS emp_count, AVG(salary) AS avg_salary " +
                "       FROM employees " +
                "       WHERE status = 1 " +
                "       GROUP BY dept_id) t " +
                "WHERE t.avg_salary > 5000;";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        assertEquals(1, statements.size());

        SQLSelectStatement stmt = (SQLSelectStatement) statements.get(0);
        assertNotNull(stmt);

        // 打印 AST 语法树结构
        System.out.println("===== FROM 子查询 AST 语法树 =====");
        System.out.println(AstTreePrinter.print(stmt));
        System.out.println("==================================");
    }

    @Test
    void testSelectWithGroupByAndOrderBy() {
        String sql = "SELECT dept_id, COUNT(*) AS emp_count, AVG(salary) AS avg_salary " +
                "FROM employees " +
                "WHERE status = 1 " +
                "GROUP BY dept_id " +
                "HAVING COUNT(*) > 5 " +
                "ORDER BY avg_salary DESC " +
                "LIMIT 10;";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        SQLSelectStatement stmt = (SQLSelectStatement) statements.get(0);
        MySqlSelectQueryBlock query = (MySqlSelectQueryBlock) stmt.getSelect().getQuery();

        // 验证来源表
        assertEquals("employees", ((SQLExprTableSource) query.getFrom()).getName());

        // 验证查询列
        assertEquals(3, query.getSelectList().size());

        // 验证 GROUP BY
        assertNotNull(query.getGroupBy());

        // 验证 HAVING
//        assertNotNull(query.getHaving());

        // 验证 ORDER BY
        assertNotNull(query.getOrderBy());

        // 验证 LIMIT
        assertNotNull(query.getLimit());
    }

    @Test
    void testSelectWithDatabasePrefix() {
        String sql = "SELECT id, name FROM testdb.users;";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        SQLSelectStatement stmt = (SQLSelectStatement) statements.get(0);
        SQLSelectQueryBlock query = (SQLSelectQueryBlock) stmt.getSelect().getQuery();

        SQLExprTableSource tableSource = (SQLExprTableSource) query.getFrom();
//        assertEquals("testdb", tableSource.getSchema().getSimpleName());
        assertEquals("users", tableSource.getName());
    }

    @Test
    void testSelectMultiTableJoin() {
        String sql = "SELECT u.name, o.order_no, p.product_name " +
                "FROM users u " +
                "INNER JOIN orders o ON u.id = o.user_id " +
                "INNER JOIN order_items oi ON o.id = oi.order_id " +
                "INNER JOIN products p ON oi.product_id = p.id " +
                "WHERE u.status = 1;";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        SQLSelectStatement stmt = (SQLSelectStatement) statements.get(0);
        SQLSelectQueryBlock query = (SQLSelectQueryBlock) stmt.getSelect().getQuery();

        // 验证多表 JOIN
        assertNotNull(query.getFrom());
        String fromStr = SQLUtils.toMySqlString(query.getFrom());
        assertTrue(fromStr.contains("users"));
        assertTrue(fromStr.contains("orders"));
        assertTrue(fromStr.contains("order_items"));
        assertTrue(fromStr.contains("products"));

        // 验证查询列
        assertEquals(3, query.getSelectList().size());
    }

    // ==================== UPDATE 语句测试 ====================

    @Test
    void testUpdateSimple() {
        String sql = "UPDATE users SET name = 'Alice', email = 'alice@new.com' WHERE id = 1;";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        assertEquals(1, statements.size());

        SQLUpdateStatement stmt = (SQLUpdateStatement) statements.get(0);
        assertNotNull(stmt);

        // 验证目标表
//        assertEquals("users", stmt.getTableSource().getName());

        // 验证 SET 子句
        assertEquals(2, stmt.getItems().size());

        // 验证 WHERE 条件
        assertNotNull(stmt.getWhere());
    }

    @Test
    void testUpdateWithDatabase() {
        String sql = "UPDATE testdb.users SET status = 0 WHERE id = 100;";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        SQLUpdateStatement stmt = (SQLUpdateStatement) statements.get(0);

        // 验证带数据库前缀的表名
//        assertEquals("testdb", stmt.getTableSource().get);
//        assertEquals("users", stmt.getTableSource().getName());

        // 验证 SET 子句
        assertEquals(1, stmt.getItems().size());
    }

    @Test
    void testUpdateWithJoin() {
        String sql = "UPDATE users u " +
                "INNER JOIN departments d ON u.dept_id = d.id " +
                "SET u.salary = u.salary * 1.1 " +
                "WHERE d.name = 'Engineering';";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        SQLUpdateStatement stmt = (SQLUpdateStatement) statements.get(0);

        // 验证目标表
//        assertEquals("users", stmt.getTableSource().getName());
        assertEquals("u", stmt.getTableSource().getAlias());

        // 验证 JOIN
        assertNotNull(stmt.getFrom());
        String fromStr = SQLUtils.toMySqlString(stmt.getFrom());
        assertTrue(fromStr.contains("departments"));

        // 验证 SET 子句
        assertEquals(1, stmt.getItems().size());

        // 验证 WHERE 条件
        assertNotNull(stmt.getWhere());
    }

    @Test
    void testUpdateMultipleColumns() {
        String sql = "UPDATE products " +
                "SET name = 'New Product', " +
                "    price = 29.99, " +
                "    stock = stock + 100, " +
                "    updated_at = NOW() " +
                "WHERE id = 42;";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        SQLUpdateStatement stmt = (SQLUpdateStatement) statements.get(0);

        // 验证目标表
//        assertEquals("products", stmt.getTableSource().getName());

        // 验证多列更新
        assertEquals(4, stmt.getItems().size());
    }

    @Test
    void testUpdateWithSubquery() {
        String sql = "UPDATE employees SET salary = salary * 1.2 " +
                "WHERE dept_id IN (SELECT id FROM departments WHERE budget > 100000);";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        SQLUpdateStatement stmt = (SQLUpdateStatement) statements.get(0);

        // 验证目标表
//        assertEquals("employees", stmt.getTableSource().getName());

        // 验证 SET 子句
        assertEquals(1, stmt.getItems().size());

        // 验证 WHERE 子查询
        assertNotNull(stmt.getWhere());
        String whereStr = SQLUtils.toMySqlString(stmt.getWhere());
        assertTrue(whereStr.toUpperCase().contains("SELECT"));
        assertTrue(whereStr.contains("departments"));
    }

    @Test
    void testUpdateWithOrderByAndLimit() {
        String sql = "UPDATE orders SET status = 'processed' " +
                "WHERE create_time < '2024-01-01' " +
                "ORDER BY create_time ASC " +
                "LIMIT 100;";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        MySqlUpdateStatement stmt = (MySqlUpdateStatement) statements.get(0);

        // 验证目标表
//        assertEquals("orders", stmt.getTableSource().getName());

        // 验证 SET 子句
        assertEquals(1, stmt.getItems().size());

        // 验证 LIMIT
        assertNotNull(stmt.getLimit());

        // 验证 ORDER BY
        assertNotNull(stmt.getOrderBy());
    }

    // ==================== 跨类型综合测试 ====================

    @Test
    void testParseMultipleStatements() {
        String sql = "CREATE TABLE t1 (id INT); " +
                "INSERT INTO t1 VALUES (1); " +
                "SELECT * FROM t1; " +
                "UPDATE t1 SET id = 2;";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        assertEquals(4, statements.size());

        assertTrue(statements.get(0) instanceof MySqlCreateTableStatement);
        assertTrue(statements.get(1) instanceof MySqlInsertStatement);
        assertTrue(statements.get(2) instanceof SQLSelectStatement);
        assertTrue(statements.get(3) instanceof SQLUpdateStatement);
    }

    @Test
    void testSqlFormatting() {
        // 验证 Druid 格式化输出
        String sql = "select id,name,email from  users  where  age>18  order  by  id  limit  10";
        String expected = "SELECT id, name, email\n" +
                "FROM users\n" +
                "WHERE age > 18\n" +
                "ORDER BY id\n" +
                "LIMIT 10";

        List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);
        String formatted = SQLUtils.toMySqlString(statements.get(0));
        assertEquals(expected, formatted);

        // 使用 AstTreePrinter 输出语法树
        String tree = AstTreePrinter.print(statements.get(0));
        System.out.println("=== AstTreePrinter Output ===");
        System.out.println(tree);
    }

}
