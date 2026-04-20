package com.zzy.mysqllineageparser.parser.strategy;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlCreateTableStatement;
import com.alibaba.druid.util.JdbcConstants;
import com.zzy.mysqllineageparser.model.ColumnInfo;
import com.zzy.mysqllineageparser.model.LineageResult;
import com.zzy.mysqllineageparser.model.TableInfo;
import com.zzy.mysqllineageparser.parser.ParseContext;
import com.zzy.mysqllineageparser.visitor.LineageVisitor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CREATE TABLE 语句解析策略
 */
@Component
public class CreateTableParseStrategy implements StatementParseStrategy {

    @Override
    public void parse(String sql, LineageResult result, ParseContext context) {
        try {
            // 1. 使用 Druid 解析 SQL
            List<SQLStatement> statements = SQLUtils.parseStatements(sql, JdbcConstants.MYSQL);

            if (statements.isEmpty()) {
                throw new IllegalArgumentException("无法解析 SQL 语句");
            }

            SQLStatement stmt = statements.get(0);
            if (!(stmt instanceof MySqlCreateTableStatement)) {
                throw new IllegalArgumentException("期望 CREATE TABLE 语句，实际: " + stmt.getClass().getSimpleName());
            }

            // 2. 创建 Visitor 并遍历 AST
            MySqlCreateTableStatement createTableStmt = (MySqlCreateTableStatement) stmt;
            LineageVisitor visitor = new LineageVisitor();
            createTableStmt.accept(visitor);

            // 3. 获取解析结果
            LineageVisitor.TableMetadata metadata = visitor.getMetadata();

            // 4. 如果没有指定数据库，使用上下文中的默认数据库
            String databaseName = metadata.getDatabaseName();
            if (databaseName == null || databaseName.isEmpty()) {
                databaseName = context.getDefaultDatabase();
            }

            // 5. 添加输出表信息
            TableInfo tableInfo = new TableInfo(databaseName, metadata.getTableName(), metadata.getAlias());
            result.addOutputTable(tableInfo);

            // 6. 存储列信息（用于后续血缘分析）
            if (metadata.getColumns() != null) {
                for (ColumnInfo col : metadata.getColumns()) {
                    // 转换为项目的 ColumnInfo 模型
                    ColumnInfo columnInfo = new ColumnInfo(tableInfo, col.getColumnName());
                    columnInfo.setDataType(col.getDataType());
                    result.addColumnLineage(new com.zzy.mysqllineageparser.model.ColumnLineage(columnInfo, tableInfo));
                }
            }

            // 7. 存储表选项（可选）
            if (metadata.getEngine() != null) {
                // 可以在 TableInfo 中添加 ENGINE 等属性
            }

        } catch (Exception e) {
            throw new RuntimeException("解析 CREATE TABLE 语句失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String getSupportedType() {
        return "CREATE";
    }
}
