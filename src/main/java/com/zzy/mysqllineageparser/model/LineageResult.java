package com.zzy.mysqllineageparser.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL血缘解析结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LineageResult {

    /**
     * 输入表列表（去重）
     */
    private List<TableInfo> inputTables;

    /**
     * 输出表列表
     */
    private List<TableInfo> outputTables;

    /**
     * 列血缘关系列表
     */
    private List<ColumnLineage> columnLineages;

    /**
     * 原始SQL语句
     */
    private String sql;

    /**
     * SQL类型（SELECT, INSERT, UPDATE, DELETE, CREATE等）
     */
    private String sqlType;

    public LineageResult(String sql) {
        this.sql = sql;
        this.inputTables = new ArrayList<>();
        this.outputTables = new ArrayList<>();
        this.columnLineages = new ArrayList<>();
    }

    /**
     * 添加输入表
     */
    public void addInputTable(TableInfo table) {
        if (this.inputTables == null) {
            this.inputTables = new ArrayList<>();
        }
        // 避免重复添加
        if (!this.inputTables.contains(table)) {
            this.inputTables.add(table);
        }
    }

    /**
     * 添加输出表
     */
    public void addOutputTable(TableInfo table) {
        if (this.outputTables == null) {
            this.outputTables = new ArrayList<>();
        }
        if (!this.outputTables.contains(table)) {
            this.outputTables.add(table);
        }
    }

    /**
     * 添加列血缘
     */
    public void addColumnLineage(ColumnLineage columnLineage) {
        if (this.columnLineages == null) {
            this.columnLineages = new ArrayList<>();
        }
        this.columnLineages.add(columnLineage);
    }

    /**
     * 获取输入表的字符串列表
     */
    public List<String> getInputTableNames() {
        List<String> names = new ArrayList<>();
        if (inputTables != null) {
            for (TableInfo table : inputTables) {
                names.add(table.getFullName());
            }
        }
        return names;
    }

    /**
     * 获取输出表的字符串列表
     */
    public List<String> getOutputTableNames() {
        List<String> names = new ArrayList<>();
        if (outputTables != null) {
            for (TableInfo table : outputTables) {
                names.add(table.getFullName());
            }
        }
        return names;
    }

    /**
     * 按输出表分组列血缘
     */
    public Map<TableInfo, List<ColumnLineage>> getColumnLineagesByOutputTable() {
        Map<TableInfo, List<ColumnLineage>> result = new HashMap<>();
        if (columnLineages != null) {
            for (ColumnLineage lineage : columnLineages) {
                List<TableInfo> outputTables = lineage.getOutputTables();
                if (outputTables != null) {
                    for (TableInfo outputTable : outputTables) {
                        result.computeIfAbsent(outputTable, k -> new ArrayList<>()).add(lineage);
                    }
                }
            }
        }
        return result;
    }

    /**
     * 生成血缘报告
     */
    public String generateReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== SQL Lineage Report ===\n");
        sb.append("SQL Type: ").append(sqlType != null ? sqlType : "UNKNOWN").append("\n");
        sb.append("Input Tables: ").append(getInputTableNames()).append("\n");
        sb.append("Output Tables: ").append(getOutputTableNames()).append("\n");
        sb.append("\nColumn Lineages:\n");

        if (columnLineages != null) {
            for (ColumnLineage lineage : columnLineages) {
                sb.append("  - ").append(lineage.getLineageDescription()).append("\n");
            }
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        return generateReport();
    }
}
