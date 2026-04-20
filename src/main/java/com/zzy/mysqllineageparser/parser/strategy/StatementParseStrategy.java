package com.zzy.mysqllineageparser.parser.strategy;

import com.zzy.mysqllineageparser.model.LineageResult;
import com.zzy.mysqllineageparser.parser.ParseContext;

/**
 * SQL语句解析策略接口
 */
public interface StatementParseStrategy {

    /**
     * 解析SQL语句
     *
     * @param sql     SQL语句
     * @param result  解析结果
     * @param context 解析上下文
     */
    void parse(String sql, LineageResult result, ParseContext context);

    /**
     * 获取支持的SQL类型
     *
     * @return SQL类型（如 CREATE, INSERT, SELECT, UPDATE, DELETE）
     */
    String getSupportedType();
}
