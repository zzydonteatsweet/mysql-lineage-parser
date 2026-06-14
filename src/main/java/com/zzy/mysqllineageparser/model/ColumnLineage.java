package com.zzy.mysqllineageparser.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 列血缘信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ColumnLineage {

    /**
     * 解析出来的输出列名
     */
    private ColumnInfo outputColumn;

    /**
     * 来源字段列表（支持多字段组合）
     */
    private List<ColumnInfo> sourceColumns;

    /**
     * 计算出的条件或转换表达式
     * 例如：salary * 1.1, CONCAT(first_name, ' ', last_name), CASE WHEN ... END
     */
    private String transformation;

    /**
     * 过滤条件（WHERE, HAVING等）
     */
    private String filterCondition;

    /**
     * 输出表（支持多表场景，如 UNION ALL、多源列未匹配时）
     */
    private List<TableInfo> outputTables;

    public ColumnLineage(ColumnInfo outputColumn, List<TableInfo> outputTables) {
        this.outputColumn = outputColumn;
        this.outputTables = outputTables;
        this.sourceColumns = new ArrayList<>();
    }

    public ColumnLineage(ColumnInfo outputColumn, TableInfo outputTable) {
        this.outputColumn = outputColumn;
        this.outputTables = new ArrayList<>();
        this.outputTables.add(outputTable);
        this.sourceColumns = new ArrayList<>();
    }

    /**
     * 添加来源字段
     */
    public void addSourceColumn(ColumnInfo column) {
        if (this.sourceColumns == null) {
            this.sourceColumns = new ArrayList<>();
        }
        this.sourceColumns.add(column);
    }

    /**
     * 获取血缘描述
     */
    public String getLineageDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append(outputColumn.getFullName()).append(" derived from ");

        if (sourceColumns != null && !sourceColumns.isEmpty()) {
            for (int i = 0; i < sourceColumns.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(sourceColumns.get(i).getFullName());
            }
        }

        if (transformation != null && !transformation.isEmpty()) {
            sb.append(" via: ").append(transformation);
        }

        if (filterCondition != null && !filterCondition.isEmpty()) {
            sb.append(" where: ").append(filterCondition);
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        return getLineageDescription();
    }
}
