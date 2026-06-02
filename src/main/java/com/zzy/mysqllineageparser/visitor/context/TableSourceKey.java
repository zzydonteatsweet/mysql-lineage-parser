package com.zzy.mysqllineageparser.visitor.context;

import com.zzy.mysqllineageparser.model.TableInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FROM 表来源缓存 Map 的 Key
 * 包含库名、表名、别名
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TableSourceKey {

    private String databaseName;
    private String tableName;
    private String alias;

    /**
     * 转换为 TableInfo
     */
    public TableInfo toTableInfo() {
        return new TableInfo(databaseName, tableName, alias);
    }

    /**
     * 获取在 SQL 中用于列引用的名称（优先别名，否则表名）
     */
    public String getReferenceName() {
        return alias != null && !alias.isEmpty() ? alias : tableName;
    }

    public static TableSourceKey from(TableInfo tableInfo) {
        return new TableSourceKey(
                tableInfo.getDatabaseName(),
                tableInfo.getTableName(),
                tableInfo.getAlias()
        );
    }
}
