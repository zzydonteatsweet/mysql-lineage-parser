package com.zzy.mysqllineageparser.mybatis.mapper;

import com.zzy.mysqllineageparser.mybatis.entity.ColumnMetaDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TableMetaMapper {

    /**
     * 获取指定表的 DDL（CREATE TABLE 语句）
     *
     * @param tableName 表名
     * @return DDL 字符串
     */
    String getTableDdl(@Param("tableName") String tableName);

    /**
     * 获取当前数据库中所有表的名称列表
     *
     * @return 表名列表
     */
    List<String> getAllTableNames();

    /**
     * 获取指定表的所有列元信息
     *
     * @param tableName 表名
     * @return 列元信息列表
     */
    List<ColumnMetaDO> getTableColumns(@Param("tableName") String tableName);
}
