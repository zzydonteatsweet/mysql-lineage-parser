package com.zzy.mysqllineageparser.parser.strategy;

import com.zzy.mysqllineageparser.model.LineageResult;
import com.zzy.mysqllineageparser.parser.ParseContext;
import org.springframework.stereotype.Component;

/**
 * INSERT 语句解析策略
 */
@Component
public class InsertParseStrategy implements StatementParseStrategy {

    @Override
    public LineageResult parse(String sql, ParseContext context) {
        // TODO: 待实现 INSERT 语句解析逻辑
        return new LineageResult(sql);
    }

    @Override
    public String getSupportedType() {
        return "INSERT";
    }
}
