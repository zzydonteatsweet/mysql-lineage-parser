package com.zzy.mysqllineageparser.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 表信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TableInfo {

    /**
     * 数据库名
     */
    private String databaseName;

    /**
     * 表名
     */
    private String tableName;

    /**
     * 表别名（在SQL查询中使用）
     */
    private String alias;

    public TableInfo(String databaseName, String tableName) {
        this.databaseName = databaseName;
        this.tableName = tableName;
    }

    /**
     * 获取完整表名
     */
    public String getFullName() {
        return databaseName != null && !databaseName.isEmpty()
                ? databaseName + "." + tableName
                : tableName;
    }

    @Override
    public String toString() {
        return getFullName();
    }
}
