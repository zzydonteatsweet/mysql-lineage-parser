# mysql-lineage-parser

## 项目简介

一个 **MySQL SQL 数据血缘解析器**。输入一条 SQL，输出它的**表级**和**字段级**数据血缘 —— 也就是弄清楚「最终的结果数据，到底来自哪些源表、哪些字段，中间经过了怎样的转换与过滤」。

底层基于 Alibaba Druid SQL Parser 解析 SQL 的 AST，再通过自定义的访问者（Visitor）遍历这棵树，把血缘关系抽取成一个 `LineageResult`，主要包含：

- **输入表 / 输出表**（input tables / output tables）
- **字段到字段的映射**（column lineages）：源列 → 输出列，附带转换表达式（如 `salary * 1.1`、`CONCAT(...)`）、过滤条件（WHERE / HAVING）等

目前已支持 `CREATE TABLE` 的元数据提取和 `SELECT` 的列血缘解析（含子查询、JOIN、UNION 等场景）。

## 功能特性

### 表级血缘
自动识别 SQL 的**输入表 / 输出表**，覆盖 `FROM`、多种 `JOIN`、`UNION`，以及**子查询**中的物理表 —— 嵌套子查询最内层的物理表也会被纳入输入表。

### 列级血缘
- 建立「源列 → 输出列」映射，并解析每列的来源表与列名。
- 支持**表别名 + 列别名**：`SELECT u.id AS user_id FROM users AS u` 中 `user_id` 的来源正确指向带别名 `u` 的 `users.id`。
- 支持**表达式与函数转换**并记录 `transformation`：
  - 运算：`salary * 1.1`
  - 函数：`CONCAT(first_name, ' ', last_name)`
  - 聚合：`SUM(amount)`、`AVG(salary)`
  - `CASE WHEN ... END`
  - 直接映射的列 `transformation` 标记为 `direct mapping`
- 记录 `WHERE` / `HAVING` 等**过滤条件**（`filterCondition`）。

### 子查询列血缘穿透
本解析器的核心能力之一。对于 `SELECT ... FROM (SELECT ...) t` 这类带派生表的子查询：

- 外层列血缘可以**穿透派生表，逐层追溯到最内层的物理源列**，而不会停在派生表的输出别名上。
  - 例：`SELECT a FROM (SELECT id AS a FROM t1) sub` 中 `a` 的来源直接指向 `t1.id`。
- 支持**多层嵌套子查询**：沿作用域的 `outputColumnMap` 一路回传到物理列。
- 即使列引用带 `db.table.col` 前缀、或来源表信息需要物理元数据补充，穿透链路依然完整。

## 技术栈

| 分类 | 选型 |
| --- | --- |
| 语言 / 运行时 | Java 8 |
| 框架 | Spring Boot 2.6.13 |
| 构建 | Maven |
| SQL 解析 | Alibaba Druid SQL Parser 1.2.20 |
| 元数据访问 | MyBatis Spring Boot Starter 2.2.2 + MySQL Connector/J |
| 样板代码 | Lombok |
| 静态检查 | PMD / SpotBugs / Checkstyle |

## 为什么自己写一个

公司在做数仓相关的数据血缘需求 —— 理清数据在层层表之间的来龙去脉。这类血缘解析通常依赖大数据 / Hive 生态（比如直接解析 HiveQL、连 Hive Metastore 拿元数据），但**我个人的电脑上目前没有 Hive 环境**，为了一个验证思路的小功能去搭一整套 Hadoop / Hive 依赖太重，也不方便随手跑、随手调试。

于是退一步，用 **Druid + MySQL** 在本地重新实现一个血缘解析器：

- 用 **Druid** 解析 MySQL 方言的 SQL，拿到结构化的 AST；
- 自己写 **Visitor** 控制访问顺序（FROM → SELECT → WHERE）、解析列引用、处理子查询穿透；
- 用 **MySQL + MyBatis** 提供物理表的列元数据（用于 `SELECT *` 展开、列归属解析等）。

这样既能在本机轻量地把整套血缘解析的思路验证清楚，又不被大数据环境的部署成本拖住。

## 架构设计

### 解析链路

```
SQL 字符串
  │
  ▼  MysqlLineageParserImpl.parse()       入口：按前缀(CREATE/SELECT/...)识别 SQL 类型
  │
  ▼  ParseStrategyFactory.getStrategy()   按 SQL 类型字符串分发到对应策略 Bean
  │
  ▼  StatementParseStrategy.parse()       策略：Druid 解析 SQL → 创建 Visitor 遍历 AST → 组装结果
  │
  ▼  *LineageVisitor                      访问者：遍历 Druid AST，抽取血缘
  │
  ▼  LineageResult                        输出：inputTables / outputTables / columnLineages
```

### 策略模式（`parser/strategy/`）

`ParseStrategyFactory` 在启动时自动收集所有 `StatementParseStrategy` Bean（`@Autowired List<>`），按 `getSupportedType()` 返回的类型字符串注册。每条 SQL 根据类型分发到对应策略：

- 已实现：`CreateTableParseStrategy`（"CREATE"）、`SelectParseStrategy`（"SELECT"）
- 占位：`InsertParseStrategy` / `UpdateParseStrategy` / `DeleteParseStrategy`

新增一种语句只需：实现 `StatementParseStrategy` + `@Component` + 在 `getSupportedType()` 返回类型字符串 + 配套一个 Visitor。

### 访问者模式（`visitor/`）

Visitor 继承 Druid 的 `MySqlASTVisitorAdapter`，手动控制遍历顺序：

- **`CreateLineageVisitor`**：从 `MySqlCreateTableStatement` 提取表元数据（列、约束、引擎）。
- **`SelectLineageVisitor`**：按 **FROM → SELECT → WHERE** 顺序遍历 —— 先建立「表来源缓存」，再关联列血缘，最后记录过滤条件；支持**子查询穿透**，外层查询可经 scope 的 `outputColumnMap` 追溯到内层子查询的物理源列。

### 数据模型（`model/`）

| 模型 | 关键字段 |
| --- | --- |
| `LineageResult` | `inputTables` / `outputTables` / `columnLineages` / `sql` / `sqlType`，含 `generateReport()` 文本报告 |
| `TableInfo` | `databaseName` / `tableName` / `alias` |
| `ColumnInfo` | `table` / `columnName` / `alias` / `dataType` 等 |
| `ColumnLineage` | `outputColumn` / `sourceColumns` / `transformation` / `filterCondition` / `outputTables` |

### 调用示例

Spring Boot 中直接注入即可（`MysqlLineageParserImpl` 是 `@Component`）：

```java
@Autowired
private SqlLineageParser parser;

public void parseSql(String sql) {
    LineageResult result = parser.parse(sql);
    System.out.println(result.generateReport());

    // 也可以按需读取结构化结果
    result.getInputTableNames();   // 输入表
    result.getColumnLineages();    // 列血缘明细
}
```

脱离 Spring 容器时，可手动装配（与单元测试一致）：

```java
List<StatementParseStrategy> strategies = Arrays.asList(
        new CreateTableParseStrategy(),
        new SelectParseStrategy(tableMetaSupport));   // tableMetaSupport 可为 null
ParseStrategyFactory factory = new ParseStrategyFactory(strategies);
SqlLineageParser parser = new MysqlLineageParserImpl(factory);

LineageResult result = parser.parse("SELECT u.id, u.name FROM users u");
System.out.println(result.generateReport());
```

## 快速开始

```bash
# 完整构建（编译 + PMD / SpotBugs / Checkstyle）
mvn clean verify

# 跳过静态检查只编译
mvn clean compile -Dpmd.skip=true -Dspotbugs.skip=true -Dcheckstyle.skip=true

# 运行测试
mvn test
```
