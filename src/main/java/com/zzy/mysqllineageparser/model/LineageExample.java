package com.zzy.mysqllineageparser.model;

import java.util.Arrays;

/**
 * 血缘结构使用示例
 */
public class LineageExample {

    public static void main(String[] args) {
        // 示例1: 简单的 SELECT 查询血缘
        // SQL: SELECT id, name, salary * 1.1 AS new_salary FROM db1.employees WHERE department = 'IT'
        simpleSelectExample();

        System.out.println("\n" + createRepeatedString('=', 60) + "\n");

        // 示例2: 带连接的查询血缘
        // SQL: INSERT INTO db2.order_summary
        //      SELECT u.user_id, u.name, o.order_id, o.amount * 0.9 AS discount_amount
        //      FROM db1.users u JOIN db1.orders o ON u.user_id = o.user_id
        joinQueryExample();
    }

    private static String createRepeatedString(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    /**
     * 示例1: 简单查询
     */
    private static void simpleSelectExample() {
        System.out.println("示例1: 简单查询血缘");
        System.out.println("SQL: SELECT id, name, salary * 1.1 AS new_salary FROM db1.employees WHERE department = 'IT'");

        LineageResult result = new LineageResult("SELECT id, name, salary * 1.1 AS new_salary FROM db1.employees WHERE department = 'IT'");
        result.setSqlType("SELECT");

        // 输入表
        TableInfo employeesTable = new TableInfo("db1", "employees");
        result.addInputTable(employeesTable);

        // 输出表（这里是虚拟的查询结果，实际可能没有输出表）
        TableInfo outputTable = new TableInfo("", "query_result");
        result.addOutputTable(outputTable);

        // 列血缘1: id -> id
        ColumnInfo idColumn = new ColumnInfo(employeesTable, "id");
        ColumnInfo outputId = new ColumnInfo(outputTable, "id");
        ColumnLineage idLineage = new ColumnLineage(outputId, outputTable);
        idLineage.addSourceColumn(idColumn);
        idLineage.setTransformation("direct mapping");
        idLineage.setFilterCondition("department = 'IT'");
        result.addColumnLineage(idLineage);

        // 列血缘2: name -> name
        ColumnInfo nameColumn = new ColumnInfo(employeesTable, "name");
        ColumnInfo outputName = new ColumnInfo(outputTable, "name");
        ColumnLineage nameLineage = new ColumnLineage(outputName, outputTable);
        nameLineage.addSourceColumn(nameColumn);
        nameLineage.setTransformation("direct mapping");
        nameLineage.setFilterCondition("department = 'IT'");
        result.addColumnLineage(nameLineage);

        // 列血缘3: salary * 1.1 -> new_salary
        ColumnInfo salaryColumn = new ColumnInfo(employeesTable, "salary");
        ColumnInfo outputNewSalary = new ColumnInfo(outputTable, "new_salary");
        ColumnLineage salaryLineage = new ColumnLineage(outputNewSalary, outputTable);
        salaryLineage.addSourceColumn(salaryColumn);
        salaryLineage.setTransformation("salary * 1.1");
        salaryLineage.setFilterCondition("department = 'IT'");
        result.addColumnLineage(salaryLineage);

        System.out.println(result.generateReport());
    }

    /**
     * 示例2: 带连接的 INSERT ... SELECT
     */
    private static void joinQueryExample() {
        System.out.println("示例2: INSERT ... SELECT 血缘");
        System.out.println("SQL: INSERT INTO db2.order_summary SELECT u.user_id, u.name, o.order_id, o.amount * 0.9 FROM db1.users u JOIN db1.orders o ON u.user_id = o.user_id");

        LineageResult result = new LineageResult("INSERT INTO db2.order_summary ...");
        result.setSqlType("INSERT");

        // 输入表
        TableInfo usersTable = new TableInfo("db1", "users", "u");
        TableInfo ordersTable = new TableInfo("db1", "orders", "o");
        result.addInputTable(usersTable);
        result.addInputTable(ordersTable);

        // 输出表
        TableInfo outputTable = new TableInfo("db2", "order_summary");
        result.addOutputTable(outputTable);

        // 列血缘1: user_id -> user_id
        ColumnInfo userIdColumn = new ColumnInfo(usersTable, "user_id");
        ColumnInfo outputUserId = new ColumnInfo(outputTable, "user_id");
        ColumnLineage userIdLineage = new ColumnLineage(outputUserId, outputTable);
        userIdLineage.addSourceColumn(userIdColumn);
        userIdLineage.setTransformation("direct mapping");
        result.addColumnLineage(userIdLineage);

        // 列血缘2: name -> name
        ColumnInfo nameColumn = new ColumnInfo(usersTable, "name");
        ColumnInfo outputName = new ColumnInfo(outputTable, "name");
        ColumnLineage nameLineage = new ColumnLineage(outputName, outputTable);
        nameLineage.addSourceColumn(nameColumn);
        nameLineage.setTransformation("direct mapping");
        result.addColumnLineage(nameLineage);

        // 列血缘3: order_id -> order_id
        ColumnInfo orderIdColumn = new ColumnInfo(ordersTable, "order_id");
        ColumnInfo outputOrderId = new ColumnInfo(outputTable, "order_id");
        ColumnLineage orderIdLineage = new ColumnLineage(outputOrderId, outputTable);
        orderIdLineage.addSourceColumn(orderIdColumn);
        orderIdLineage.setTransformation("direct mapping");
        result.addColumnLineage(orderIdLineage);

        // 列血缘4: amount * 0.9 -> discount_amount
        ColumnInfo amountColumn = new ColumnInfo(ordersTable, "amount");
        ColumnInfo outputDiscountAmount = new ColumnInfo(outputTable, "discount_amount");
        ColumnLineage amountLineage = new ColumnLineage(outputDiscountAmount, outputTable);
        amountLineage.addSourceColumn(amountColumn);
        amountLineage.setTransformation("amount * 0.9");
        result.addColumnLineage(amountLineage);

        System.out.println(result.generateReport());

        // 按输出表分组展示
        System.out.println("\n按输出表分组的列血缘:");
        result.getColumnLineagesByOutputTable().forEach((table, lineages) -> {
            System.out.println("表: " + table.getFullName());
            lineages.forEach(lineage -> System.out.println("  " + lineage.getLineageDescription()));
        });
    }
}
