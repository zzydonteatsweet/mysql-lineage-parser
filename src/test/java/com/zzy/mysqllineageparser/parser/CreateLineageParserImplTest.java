package com.zzy.mysqllineageparser.parser;

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

import static org.junit.jupiter.api.Assertions.*;

/**
 * MySQL血缘解析器测试类
 */
class CreateLineageParserImplTest {

    private SqlLineageParser parser;

    @BeforeEach
    void setUp() {
        // 创建所有策略实例
        List<StatementParseStrategy> strategies = new ArrayList<>();
        strategies.add(new CreateTableParseStrategy());
        strategies.add(new InsertParseStrategy());
        strategies.add(new SelectParseStrategy(null));
        strategies.add(new UpdateParseStrategy());
        strategies.add(new DeleteParseStrategy());

        // 创建策略工厂
        ParseStrategyFactory factory = new ParseStrategyFactory(strategies);

        // 创建解析器
        parser = new MysqlLineageParserImpl(factory);
    }

    @Test
    void testCreateTableSimple() {
        String sql = "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100));";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("CREATE", result.getSqlType());
        assertEquals(1, result.getOutputTables().size());

        TableInfo table = result.getOutputTables().get(0);
        assertEquals("users", table.getTableName());
        assertNull(table.getDatabaseName());
        assertEquals("users", table.getFullName());
    }

    @Test
    void testCreateTableWithDatabase() {
        String sql = "CREATE TABLE testdb.users (id INT PRIMARY KEY, name VARCHAR(100));";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("CREATE", result.getSqlType());
        assertEquals(1, result.getOutputTables().size());

        TableInfo table = result.getOutputTables().get(0);
        assertEquals("users", table.getTableName());
        assertEquals("testdb", table.getDatabaseName());
        assertEquals("testdb.users", table.getFullName());
    }

    @Test
    void testCreateTableWithBackticks() {
        String sql = "CREATE TABLE `mydb`.`employees` (\n" +
                "  `id` INT PRIMARY KEY,\n" +
                "  `first_name` VARCHAR(50),\n" +
                "  `last_name` VARCHAR(50)\n" +
                ");";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("CREATE", result.getSqlType());
        assertEquals(1, result.getOutputTables().size());

        TableInfo table = result.getOutputTables().get(0);
        assertEquals("employees", table.getTableName());
        assertEquals("mydb", table.getDatabaseName());
        assertEquals("mydb.employees", table.getFullName());
    }

    @Test
    void testCreateTableIfNotExists() {
        String sql = "CREATE TABLE IF NOT EXISTS orders (id INT PRIMARY KEY, order_date DATE);";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("CREATE", result.getSqlType());
        assertEquals(1, result.getOutputTables().size());

        TableInfo table = result.getOutputTables().get(0);
        assertEquals("orders", table.getTableName());
    }

    @Test
    void testCreateTableWithContext() {
        String sql = "CREATE TABLE products (id INT PRIMARY KEY, name VARCHAR(100));";
        ParseContext context = ParseContext.withDatabase("inventory");

        LineageResult result = parser.parse(sql, context);

        assertNotNull(result);
        assertEquals("CREATE", result.getSqlType());
        assertEquals(1, result.getOutputTables().size());

        TableInfo table = result.getOutputTables().get(0);
        assertEquals("products", table.getTableName());
        assertEquals("inventory", table.getDatabaseName());
        assertEquals("inventory.products", table.getFullName());
    }

    @Test
    void testEmptySql() {
        assertThrows(IllegalArgumentException.class, () -> {
            parser.parse("");
        });
    }

    @Test
    void testNullSql() {
        assertThrows(IllegalArgumentException.class, () -> {
            parser.parse(null);
        });
    }

    @Test
    void testGenerateReport() {
        String sql = "CREATE TABLE testdb.users (id INT PRIMARY KEY, name VARCHAR(100));";
        LineageResult result = parser.parse(sql);

        String report = result.generateReport();

        assertNotNull(report);
        assertTrue(report.contains("SQL Type: CREATE"));
        assertTrue(report.contains("Output Tables:"));
        assertTrue(report.contains("testdb.users"));
    }

    @Test
    void testCreateTableWithColumnDefinitions() {
        String sql = "CREATE TABLE users (\n" +
                "  id BIGINT NOT NULL AUTO_INCREMENT,\n" +
                "  username VARCHAR(50) NOT NULL COMMENT '用户名',\n" +
                "  email VARCHAR(100) DEFAULT 'test@example.com',\n" +
                "  age INT DEFAULT 18,\n" +
                "  status TINYINT(1) NOT NULL DEFAULT 1,\n" +
                "  PRIMARY KEY (id)\n" +
                ");";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("CREATE", result.getSqlType());
        assertEquals(1, result.getOutputTables().size());

        // 验证列血缘信息
        assertNotNull(result.getColumnLineages());
        assertTrue(result.getColumnLineages().size() >= 5); // 至少5个列

        // 验证第一列（id）
        com.zzy.mysqllineageparser.model.ColumnLineage idLineage = result.getColumnLineages().get(0);
        assertEquals("id", idLineage.getOutputColumn().getColumnName());
        assertEquals("BIGINT", idLineage.getOutputColumn().getDataType());
    }

    @Test
    void testCreateTableWithConstraints() {
        String sql = "CREATE TABLE orders (\n" +
                "  id BIGINT NOT NULL AUTO_INCREMENT,\n" +
                "  order_no VARCHAR(50) NOT NULL,\n" +
                "  user_id BIGINT NOT NULL,\n" +
                "  amount DECIMAL(10, 2) DEFAULT 0.00,\n" +
                "  status TINYINT NOT NULL DEFAULT 0,\n" +
                "  PRIMARY KEY (id),\n" +
                "  UNIQUE KEY uk_order_no (order_no),\n" +
                "  KEY idx_user_id (user_id),\n" +
                "  KEY idx_status (status, create_time)\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("CREATE", result.getSqlType());
        assertEquals(1, result.getOutputTables().size());

        TableInfo table = result.getOutputTables().get(0);
        assertEquals("orders", table.getTableName());
    }

    @Test
    void testCreateTableComplex() {
        String sql = "CREATE TABLE `employees` (\n" +
                "  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '员工ID',\n" +
                "  `emp_no` varchar(20) NOT NULL COMMENT '员工编号',\n" +
                "  `name` varchar(50) NOT NULL COMMENT '姓名',\n" +
                "  `dept_id` bigint(20) NOT NULL COMMENT '部门ID',\n" +
                "  `salary` decimal(10,2) DEFAULT NULL COMMENT '薪资',\n" +
                "  `hire_date` date NOT NULL COMMENT '入职日期',\n" +
                "  `status` tinyint(1) NOT NULL DEFAULT '1' COMMENT '状态 1:在职 0:离职',\n" +
                "  PRIMARY KEY (`id`),\n" +
                "  UNIQUE KEY `uk_emp_no` (`emp_no`),\n" +
                "  KEY `idx_dept_id` (`dept_id`),\n" +
                "  KEY `idx_status` (`status`, `hire_date`),\n" +
                "  CONSTRAINT `fk_emp_dept` FOREIGN KEY (`dept_id`) REFERENCES `departments` (`id`) ON DELETE CASCADE\n" +
                ") ENGINE=InnoDB AUTO_INCREMENT=1000 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='员工表';";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("CREATE", result.getSqlType());
        assertEquals(1, result.getOutputTables().size());

        TableInfo table = result.getOutputTables().get(0);
        assertEquals("employees", table.getTableName());

        // 验证列信息
        assertNotNull(result.getColumnLineages());
        assertTrue(result.getColumnLineages().size() >= 7);

        // 验证注释列
        com.zzy.mysqllineageparser.model.ColumnLineage nameLineage = result.getColumnLineages().stream()
                .filter(cl -> "name".equals(cl.getOutputColumn().getColumnName()))
                .findFirst()
                .orElse(null);
        assertNotNull(nameLineage);
        assertEquals("VARCHAR", nameLineage.getOutputColumn().getDataType());
    }

    @Test
    void testCreateTableWithTableOptions() {
        String sql = "CREATE TABLE products (\n" +
                "  id INT PRIMARY KEY,\n" +
                "  name VARCHAR(100)\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='产品表';";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("CREATE", result.getSqlType());
        assertEquals(1, result.getOutputTables().size());

        TableInfo table = result.getOutputTables().get(0);
        assertEquals("products", table.getTableName());
    }

    @Test
    void testCreateTableTemporary() {
        String sql = "CREATE TEMPORARY TABLE temp_users (\n" +
                "  id INT PRIMARY KEY,\n" +
                "  name VARCHAR(50)\n" +
                ");";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("CREATE", result.getSqlType());
        assertEquals(1, result.getOutputTables().size());

        TableInfo table = result.getOutputTables().get(0);
        assertEquals("temp_users", table.getTableName());
    }

    @Test
    void testCreateTableWithPartitionByRange() {
        String sql = "CREATE TABLE sales (\n" +
                "  id INT NOT NULL,\n" +
                "  sale_date DATE NOT NULL,\n" +
                "  amount DECIMAL(10,2),\n" +
                "  PRIMARY KEY (id, sale_date)\n" +
                ") \n" +
                "PARTITION BY RANGE (YEAR(sale_date)) (\n" +
                "  PARTITION p2020 VALUES LESS THAN (2021),\n" +
                "  PARTITION p2021 VALUES LESS THAN (2022),\n" +
                "  PARTITION p2022 VALUES LESS THAN (2023),\n" +
                "  PARTITION pmax VALUES LESS THAN MAXVALUE\n" +
                ");";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("CREATE", result.getSqlType());
        assertEquals(1, result.getOutputTables().size());

        TableInfo table = result.getOutputTables().get(0);
        assertEquals("sales", table.getTableName());
    }

    @Test
    void testCreateTableWithPartitionByList() {
        String sql = "CREATE TABLE employees (\n" +
                "  id INT NOT NULL,\n" +
                "  name VARCHAR(50),\n" +
                "  region VARCHAR(20),\n" +
                "  PRIMARY KEY (id, region)\n" +
                ") \n" +
                "PARTITION BY LIST COLUMNS(region) (\n" +
                "  PARTITION p_north VALUES IN ('Beijing', 'Tianjin', 'Shanghai'),\n" +
                "  PARTITION p_south VALUES IN ('Guangzhou', 'Shenzhen'),\n" +
                "  PARTITION p_east VALUES IN ('Nanjing', 'Hangzhou'),\n" +
                "  PARTITION p_other VALUES IN (DEFAULT)\n" +
                ");";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("CREATE", result.getSqlType());
        assertEquals(1, result.getOutputTables().size());

        TableInfo table = result.getOutputTables().get(0);
        assertEquals("employees", table.getTableName());
    }

    @Test
    void testCreateTableWithPartitionByHash() {
        String sql = "CREATE TABLE orders (\n" +
                "  id INT NOT NULL,\n" +
                "  customer_id INT NOT NULL,\n" +
                "  order_date DATE,\n" +
                "  amount DECIMAL(10,2),\n" +
                "  PRIMARY KEY (id, customer_id)\n" +
                ") \n" +
                "PARTITION BY HASH(customer_id) \n" +
                "PARTITIONS 4;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("CREATE", result.getSqlType());
        assertEquals(1, result.getOutputTables().size());

        TableInfo table = result.getOutputTables().get(0);
        assertEquals("orders", table.getTableName());
    }

    @Test
    void testCreateTableWithPartitionByKey() {
        String sql = "CREATE TABLE products (\n" +
                "  id INT NOT NULL,\n" +
                "  product_name VARCHAR(100),\n" +
                "  category VARCHAR(50),\n" +
                "  price DECIMAL(10,2),\n" +
                "  PRIMARY KEY (id)\n" +
                ") \n" +
                "PARTITION BY KEY(id) \n" +
                "PARTITIONS 6;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("CREATE", result.getSqlType());
        assertEquals(1, result.getOutputTables().size());

        TableInfo table = result.getOutputTables().get(0);
        assertEquals("products", table.getTableName());
    }

    @Test
    void testCreateTableWithPartitionByRangeColumns() {
        String sql = "CREATE TABLE logs (\n" +
                "  id BIGINT NOT NULL,\n" +
                "  log_date DATE NOT NULL,\n" +
                "  log_time DATETIME NOT NULL,\n" +
                "  message TEXT,\n" +
                "  level VARCHAR(20),\n" +
                "  PRIMARY KEY (id, log_date)\n" +
                ") \n" +
                "PARTITION BY RANGE COLUMNS(log_date) (\n" +
                "  PARTITION p_2024_01 VALUES LESS THAN ('2024-02-01'),\n" +
                "  PARTITION p_2024_02 VALUES LESS THAN ('2024-03-01'),\n" +
                "  PARTITION p_2024_03 VALUES LESS THAN ('2024-04-01'),\n" +
                "  PARTITION p_2024_04 VALUES LESS THAN ('2024-05-01'),\n" +
                "  PARTITION p_future VALUES LESS THAN MAXVALUE\n" +
                ") \n" +
                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("CREATE", result.getSqlType());
        assertEquals(1, result.getOutputTables().size());

        TableInfo table = result.getOutputTables().get(0);
        assertEquals("logs", table.getTableName());
    }

    @Test
    void testCreateTableWithSubpartition() {
        String sql = "CREATE TABLE sales_detail (\n" +
                "  id BIGINT NOT NULL,\n" +
                "  sale_date DATE NOT NULL,\n" +
                "  region VARCHAR(20) NOT NULL,\n" +
                "  amount DECIMAL(10,2),\n" +
                "  PRIMARY KEY (id, sale_date, region)\n" +
                ") \n" +
                "PARTITION BY RANGE (YEAR(sale_date)) \n" +
                "SUBPARTITION BY HASH(region) \n" +
                "SUBPARTITIONS 2 (\n" +
                "  PARTITION p2023 VALUES LESS THAN (2024),\n" +
                "  PARTITION p2024 VALUES LESS THAN (2025),\n" +
                "  PARTITION p2025 VALUES LESS THAN (2026)\n" +
                ");";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("CREATE", result.getSqlType());
        assertEquals(1, result.getOutputTables().size());

        TableInfo table = result.getOutputTables().get(0);
        assertEquals("sales_detail", table.getTableName());
    }
}
