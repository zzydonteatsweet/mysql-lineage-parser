package com.zzy.mysqllineageparser.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 列信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ColumnInfo {

    /**
     * 所属表
     */
    private TableInfo table;

    /**
     * 列名
     */
    private String columnName;

    /**
     * 列别名（在SQL查询中使用）
     */
    private String alias;

    /**
     * 数据类型
     */
    private String dataType;

    /**
     * 是否为空
     */
    private Boolean nullable;

    /**
     * 默认值
     */
    private String defaultValue;

    /**
     * 注释
     */
    private String comment;

    /**
     * 是否为主键
     */
    private Boolean isPrimaryKey;

    /**
     * 是否自增
     */
    private Boolean autoIncrement;

    /**
     * 列约束列表（UNIQUE, NOT NULL 等）
     */
    private List<String> constraints;

    /**
     * 来源列链路（子查询穿透用）
     * 物理表列为 null，子查询派生列指向子查询内部的源列，形成链式追溯
     * 例如：query_result.col1 → [a.col1 → [table_c.col3]]
     */
    private List<ColumnInfo> sourceColumns;

    public ColumnInfo(TableInfo table, String columnName) {
        this.table = table;
        this.columnName = columnName;
    }

    /**
     * 获取完整列名
     */
    public String getFullName() {
        return table != null
                ? table.getFullName() + "." + columnName
                : columnName;
    }

    @Override
    public String toString() {
        return getFullName();
    }
}
