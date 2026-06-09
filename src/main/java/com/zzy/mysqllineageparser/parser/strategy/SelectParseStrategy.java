package com.zzy.mysqllineageparser.parser.strategy;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.util.JdbcConstants;
import com.zzy.mysqllineageparser.model.LineageResult;
import com.zzy.mysqllineageparser.parser.ParseContext;
import com.zzy.mysqllineageparser.visitor.SelectLineageVisitor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SELECT 语句解析策略
 */
@Component
public class SelectParseStrategy implements StatementParseStrategy {

    @Override
    public LineageResult parse(String sql, ParseContext context) {
        LineageResult result = new LineageResult(sql);
        try {
            // 1. 使用 Druid 解析 SQL
            List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);

            if (statements.isEmpty()) {
                throw new IllegalArgumentException("无法解析 SQL 语句");
            }

            SQLStatement stmt = statements.get(0);
            if (!(stmt instanceof SQLSelectStatement)) {
                throw new IllegalArgumentException("期望 SELECT 语句，实际: " + stmt.getClass().getSimpleName());
            }

            // 2. 创建 Visitor 并遍历 AST
            SQLSelectStatement selectStmt = (SQLSelectStatement) stmt;
            String defaultDatabase = context != null ? context.getDefaultDatabase() : null;
            SelectLineageVisitor visitor = new SelectLineageVisitor(result, defaultDatabase);
            selectStmt.accept(visitor);
        } catch (Exception e) {
            throw new RuntimeException("解析 SELECT 语句失败: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public String getSupportedType() {
        return "SELECT";
    }
}
