package com.zzy.mysqllineageparser.mybatis.support.impl;

import com.zzy.mysqllineageparser.mybatis.entity.ColumnMetaDO;
import com.zzy.mysqllineageparser.mybatis.mapper.TableMetaMapper;
import com.zzy.mysqllineageparser.model.ColumnInfo;
import com.zzy.mysqllineageparser.model.TableInfo;
import com.zzy.mysqllineageparser.mybatis.support.TableMetaSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 表元数据防腐层实现
 * 将 MyBatis 层的 DO 对象转换为业务模型，隔离持久层与业务层的耦合
 */
@Component
public class TableMetaSupportImpl implements TableMetaSupport {

    private final TableMetaMapper tableMetaMapper;

    @Autowired
    public TableMetaSupportImpl(TableMetaMapper tableMetaMapper) {
        this.tableMetaMapper = tableMetaMapper;
    }

    @Override
    public List<ColumnInfo> getTableColumns(String tableName) {
        List<ColumnMetaDO> columns = tableMetaMapper.getTableColumns(tableName);
        if (columns == null || columns.isEmpty()) {
            return Collections.emptyList();
        }

        TableInfo tableInfo = new TableInfo(columns.get(0).getTableSchema(), tableName);

        return columns.stream()
                .map(meta -> convertToColumnInfo(meta, tableInfo))
                .collect(Collectors.toList());
    }

    /**
     * 将 ColumnMetaDO 转换为 ColumnInfo
     */
    private ColumnInfo convertToColumnInfo(ColumnMetaDO meta, TableInfo tableInfo) {
        ColumnInfo columnInfo = new ColumnInfo();
        columnInfo.setTable(tableInfo);
        columnInfo.setColumnName(meta.getColumnName());
        columnInfo.setDataType(meta.getDataType());
        columnInfo.setComment(meta.getColumnComment());
        columnInfo.setDefaultValue(meta.getColumnDefault());
        columnInfo.setNullable("YES".equalsIgnoreCase(meta.getIsNullable()));
        columnInfo.setAutoIncrement(
                meta.getExtra() != null && meta.getExtra().toLowerCase().contains("auto_increment")
        );
        return columnInfo;
    }
}
