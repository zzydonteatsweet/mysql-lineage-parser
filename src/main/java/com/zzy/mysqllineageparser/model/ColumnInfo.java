package com.zzy.mysqllineageparser.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
