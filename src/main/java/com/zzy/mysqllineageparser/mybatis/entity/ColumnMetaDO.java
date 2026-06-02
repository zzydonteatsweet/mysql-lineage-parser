package com.zzy.mysqllineageparser.mybatis.entity;

import lombok.Data;

/**
 * 列元信息 DO（Data Object）
 * 对应 INFORMATION_SCHEMA.COLUMNS 查询结果
 */
@Data
public class ColumnMetaDO {

    /**
     * 数据库名
     */
    private String tableSchema;

    /**
     * 表名
     */
    private String tableName;

    /**
     * 列名
     */
    private String columnName;

    /**
     * 列序号
     */
    private Integer ordinalPosition;

    /**
     * 数据类型（如 varchar, int, datetime）
     */
    private String dataType;

    /**
     * 完整类型定义（如 varchar(255), int(11)）
     */
    private String columnType;

    /**
     * 是否允许 NULL
     */
    private String isNullable;

    /**
     * 默认值
     */
    private String columnDefault;

    /**
     * 列注释
     */
    private String columnComment;

    /**
     * 列键（PRI=主键, UNI=唯一, MUL=普通索引）
     */
    private String columnKey;

    /**
     * 额外属性（如 auto_increment）
     */
    private String extra;
}
