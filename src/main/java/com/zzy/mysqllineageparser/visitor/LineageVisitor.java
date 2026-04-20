package com.zzy.mysqllineageparser.visitor;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLIndexDefinition;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLColumnDefinition;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlCreateTableStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.MySqlPrimaryKey;
import com.alibaba.druid.sql.dialect.mysql.ast.MySqlUnique;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import com.zzy.mysqllineageparser.model.ColumnInfo;
import com.zzy.mysqllineageparser.model.TableInfo;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * CREATE TABLE 深度遍历访问者
 * 使用 Druid SQL 解析器的 Visitor 模式提取表结构信息
 */
public class LineageVisitor extends MySqlASTVisitorAdapter {

    private final TableMetadata metadata = new TableMetadata();

    @Override
    public boolean visit(MySqlCreateTableStatement x) {
        // 提取表名
        extractTableName(x);

        // 提取表选项
        extractTableOptions(x);

        // 继续遍历子节点（列、约束等）
        return true;
    }

    @Override
    public boolean visit(SQLColumnDefinition x) {
        ColumnInfo column = new ColumnInfo();

        // 列名
        column.setColumnName(x.getColumnName());

        // 数据类型
        if (x.getDataType() != null) {
            column.setDataType(x.getDataType().getName());
        }

        // 默认值
        if (x.getDefaultExpr() != null) {
            column.setDefaultValue(x.getDefaultExpr().toString());
        }

        // 是否可为空
        column.setNullable(true); // 默认可为 NULL
        if (x.containsNotNullConstraint()) {
            column.setNullable(false);
        }

        // AUTO_INCREMENT
        if (x.getAutoIncrement() != null) {
            column.setAutoIncrement(true);
        }

        // 主键
        if (x.containsPrimaryKeyConstraint()) {
            column.setPrimaryKey(true);
        }

        // 唯一约束
        if (x.containsUniqueConstraint()) {
            column.setUnique(true);
        }

        // 注释
        if (x.getComment() != null) {
            column.setComment(x.getComment().toString());
        }

        metadata.addColumn(column);

        // 继续遍历列的子节点
        return true;
    }

    @Override
    public boolean visit(MySqlPrimaryKey x) {
        ConstraintInfo constraint = new ConstraintInfo();
        constraint.setConstraintType("PRIMARY_KEY");

        // 主键名称
        if (x.getName() != null) {
            constraint.setConstraintName(x.getName().toString());
        } else {
            constraint.setConstraintName("PRIMARY");
        }

        // 主键列
        List<String> columns = new ArrayList<>();
        for (SQLExpr expr : x.getColumns()) {
            columns.add(expr.toString());
        }
        constraint.setColumns(columns);

        metadata.addConstraint(constraint);

        return true;
    }

    @Override
    public boolean visit(MySqlUnique x) {
        ConstraintInfo constraint = new ConstraintInfo();
        constraint.setConstraintType("UNIQUE");

        // 约束名称
        if (x.getName() != null) {
            constraint.setConstraintName(x.getName().toString());
        }

        // 唯一约束列
        List<String> columns = new ArrayList<>();
        for (SQLExpr expr : x.getColumns()) {
            columns.add(expr.toString());
        }
        constraint.setColumns(columns);

        metadata.addConstraint(constraint);

        return true;
    }

    @Override
    public boolean visit(SQLIndexDefinition x) {
        IndexInfo index = new IndexInfo();

        // 索引名称
        if (x.getName() != null) {
            index.setIndexName(x.getName().toString());
        }

        // 索引类型
        if (x.getIndexType() != null) {
            index.setIndexType(x.getIndexType().name());
        }

        // 索引列
        List<String> columns = new ArrayList<>();
        for (SQLExpr expr : x.getColumns()) {
            columns.add(expr.toString());
        }
        index.setColumns(columns);

        metadata.addIndex(index);

        return true;
    }

    /**
     * 提取表名和数据库信息
     */
    private void extractTableName(MySqlCreateTableStatement x) {
        SQLExpr expr = x.getTableSource().getExpr();

        if (expr instanceof SQLPropertyExpr) {
            // database.table 格式
            SQLPropertyExpr propertyExpr = (SQLPropertyExpr) expr;

            // 获取数据库名
            if (propertyExpr.getOwner() != null) {
                metadata.setDatabaseName(propertyExpr.getOwner().toString());
            }

            // 获取表名
            metadata.setTableName(propertyExpr.getName());
        } else if (expr instanceof SQLIdentifierExpr) {
            // 单表名格式
            metadata.setTableName(((SQLIdentifierExpr) expr).getName());
        }

        // 获取别名
        metadata.setAlias(x.getTableSource().getAlias());
    }

    /**
     * 提取表选项（ENGINE, CHARSET 等）
     */
    private void extractTableOptions(MySqlCreateTableStatement x) {
        if (x.getEngine() != null) {
            metadata.setEngine(x.getEngine().toString());
        }
        if (x.getCharset() != null) {
            // todo
            x.getTableOptions().stream().filter(sqlAssignItem -> {
                sqlAssignItem.getTarget().computeDataType()
            })
            metadata.setCharset(x.getCharset().toString());
        }
        if (x.getTableOptionsString("COLLATE") != null) {
            metadata.setCollation(x.getTableOptionsString("COLLATE"));
        }
        if (x.getTableOptionsString("COMMENT") != null) {
            metadata.setComment(x.getTableOptionsString("COMMENT"));
        }
        if (x.getTableOptionsInt("AUTO_INCREMENT") != null) {
            metadata.setAutoIncrement(x.getTableOptionsInt("AUTO_INCREMENT"));
        }
    }

    public TableMetadata getMetadata() {
        return metadata;
    }

    /**
     * CREATE TABLE 元数据
     */
    @Data
    public static class TableMetadata {
        private String databaseName;
        private String tableName;
        private String alias;
        private String engine;
        private String charset;
        private String collation;
        private String comment;
        private Integer autoIncrement;
        private List<ColumnInfo> columns = new ArrayList<>();
        private List<ConstraintInfo> constraints = new ArrayList<>();
        private List<IndexInfo> indexes = new ArrayList<>();

        public void addColumn(ColumnInfo column) {
            this.columns.add(column);
        }

        public void addConstraint(ConstraintInfo constraint) {
            this.constraints.add(constraint);
        }

        public void addIndex(IndexInfo index) {
            this.indexes.add(index);
        }

        /**
         * 转换为 TableInfo 对象
         */
        public TableInfo toTableInfo() {
            return new TableInfo(databaseName, tableName, alias);
        }
    }

    /**
     * 约束信息
     */
    @Data
    public static class ConstraintInfo {
        private String constraintType;    // PRIMARY_KEY, UNIQUE, FOREIGN_KEY
        private String constraintName;
        private List<String> columns;
    }

    /**
     * 索引信息
     */
    @Data
    public static class IndexInfo {
        private String indexName;
        private String indexType;         // BTREE, HASH, FULLTEXT
        private List<String> columns;
    }
}
