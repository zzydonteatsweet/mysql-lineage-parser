package com.zzy.mysqllineageparser.parser;

import com.zzy.mysqllineageparser.model.LineageResult;

/**
 * SQL血缘解析器接口
 */
public interface SqlLineageParser {

    /**
     * 解析SQL语句，返回血缘关系结果
     *
     * @param sql 要解析的SQL语句
     * @return 血缘关系解析结果
     */
    LineageResult parse(String sql);

    /**
     * 解析SQL语句，返回血缘关系结果
     *
     * @param sql     要解析的SQL语句
     * @param context 解析上下文
     * @return 血缘关系解析结果
     */
    LineageResult parse(String sql, ParseContext context);
}
