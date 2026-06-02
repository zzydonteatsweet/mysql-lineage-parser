package com.zzy.mysqllineageparser.mybatis.support;

import com.zzy.mysqllineageparser.model.ColumnInfo;

import java.util.List;

/**
 * 表元数据防腐层接口
 * 将持久层与业务层解耦，提供表列元数据的查询能力
 */
public interface TableMetaSupport {

    /**
     * 获取指定表的所有列信息
     *
     * @param tableName 表名
     * @return 列信息列表，表不存在或查询异常时返回空列表
     */
    List<ColumnInfo> getTableColumns(String tableName);
}
