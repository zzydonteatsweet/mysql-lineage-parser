package com.zzy.mysqllineageparser.parser;

import com.zzy.mysqllineageparser.model.LineageResult;
import com.zzy.mysqllineageparser.parser.strategy.ParseStrategyFactory;
import com.zzy.mysqllineageparser.parser.strategy.StatementParseStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * MySQL SQL血缘解析器实现
 */
@Component
public class MysqlLineageParserImpl implements SqlLineageParser {

    private final ParseStrategyFactory strategyFactory;

    @Autowired
    public MysqlLineageParserImpl(ParseStrategyFactory strategyFactory) {
        this.strategyFactory = strategyFactory;
    }

    @Override
    public LineageResult parse(String sql) {
        return parse(sql, ParseContext.createDefault());
    }

    @Override
    public LineageResult parse(String sql, ParseContext context) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL语句不能为空");
        }

        String trimmedSql = sql.trim().toUpperCase();
        LineageResult result = new LineageResult(sql);

        // 提取SQL类型
        String sqlType = extractSqlType(trimmedSql);
        result.setSqlType(sqlType);

        // 使用策略模式获取对应的解析策略
        if (strategyFactory.isSupported(sqlType)) {
            StatementParseStrategy strategy = strategyFactory.getStrategy(sqlType);
            strategy.parse(sql, result, context);
        }
        

        return result;
    }

    /**
     * 从SQL语句中提取SQL类型
     *
     * @param trimmedSql 已转换为大写并去除了首尾空格的SQL语句
     * @return SQL类型
     */
    private String extractSqlType(String trimmedSql) {
        if (trimmedSql.startsWith("CREATE")) {
            return "CREATE";
        } else if (trimmedSql.startsWith("INSERT")) {
            return "INSERT";
        } else if (trimmedSql.startsWith("SELECT")) {
            return "SELECT";
        } else if (trimmedSql.startsWith("UPDATE")) {
            return "UPDATE";
        } else if (trimmedSql.startsWith("DELETE")) {
            return "DELETE";
        } else {
            return "UNKNOWN";
        }
    }
}
