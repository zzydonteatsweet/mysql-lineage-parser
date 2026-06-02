package com.zzy.mysqllineageparser.visitor.helper;

import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.mysql.ast.MySqlPrimaryKey;
import com.alibaba.druid.sql.dialect.mysql.ast.MySqlUnique;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlCreateTableStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlTableIndex;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;

/**
 * AST 语法树打印器
 * 以树形结构输出 Druid 解析后的 SQL 语法树，包含类型和名称信息
 */
public class AstTreePrinter extends MySqlASTVisitorAdapter {

    private final StringBuilder sb = new StringBuilder();
    private int indent = 0;

    /**
     * 打印 SQL 语法树
     */
    public static String print(SQLObject root) {
        AstTreePrinter printer = new AstTreePrinter();
        root.accept(printer);
        return printer.sb.toString();
    }

    private void appendNode(String type, String name) {
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
        if (name != null && !name.isEmpty()) {
            sb.append(type).append(": ").append(name).append("\n");
        } else {
            sb.append(type).append("\n");
        }
    }

    private void enter() {
        indent++;
    }

    private void exit() {
        indent--;
    }

    // ==================== Statement ====================

    @Override
    public boolean visit(MySqlCreateTableStatement x) {
        String tableName = x.getTableSource() != null ? x.getTableSource().toString() : null;
        appendNode("MySqlCreateTableStatement", tableName);
        enter();
        return true;
    }

    @Override
    public void endVisit(MySqlCreateTableStatement x) {
        exit();
    }

    // ==================== TableSource ====================

    @Override
    public boolean visit(SQLExprTableSource x) {
        appendNode("SQLExprTableSource", x.getExpr() + (x.getAlias() != null ? " AS " + x.getAlias() : ""));
        return false;
    }

    // ==================== ColumnDefinition ====================

    @Override
    public boolean visit(SQLColumnDefinition x) {
        StringBuilder name = new StringBuilder();
        name.append(x.getColumnName());
        if (x.getDataType() != null) {
            name.append(" ").append(x.getDataType().getName());
        }
        appendNode("SQLColumnDefinition", name.toString());
        enter();
        return true;
    }

    @Override
    public void endVisit(SQLColumnDefinition x) {
        exit();
    }

    // ==================== Constraint ====================

    @Override
    public boolean visit(MySqlPrimaryKey x) {
        appendNode("MySqlPrimaryKey", x.getName() != null ? x.getName().toString() : null);
        enter();
        return true;
    }

    @Override
    public void endVisit(MySqlPrimaryKey x) {
        exit();
    }

    @Override
    public boolean visit(MySqlUnique x) {
        appendNode("MySqlUnique", x.getName() != null ? x.getName().toString() : null);
        enter();
        return true;
    }

    @Override
    public void endVisit(MySqlUnique x) {
        exit();
    }

    // ==================== Index ====================

    @Override
    public boolean visit(MySqlTableIndex x) {
        appendNode("MySqlTableIndex", x.getName() != null ? x.getName().toString() : null);
        enter();
        return true;
    }

    @Override
    public void endVisit(MySqlTableIndex x) {
        exit();
    }

    // ==================== SELECT Statement ====================

    @Override
    public boolean visit(SQLSelectStatement x) {
        appendNode("SQLSelectStatement", null);
        enter();
        return true;
    }

    @Override
    public void endVisit(SQLSelectStatement x) {
        exit();
    }

    @Override
    public boolean visit(SQLSelect x) {
        appendNode("SQLSelect", null);
        enter();
        return true;
    }

    @Override
    public void endVisit(SQLSelect x) {
        exit();
    }

    @Override
    public boolean visit(SQLSelectQueryBlock x) {
        appendNode("SQLSelectQueryBlock", null);
        enter();
        if (x.getHints() != null && !x.getHints().isEmpty()) {
            appendNode("hints", x.getHints().toString());
        }
        return true;
    }

    @Override
    public void endVisit(SQLSelectQueryBlock x) {
        exit();
    }

    @Override
    public boolean visit(MySqlSelectQueryBlock x) {
        appendNode("MySqlSelectQueryBlock", null);
        enter();
        if (x.getHints() != null && !x.getHints().isEmpty()) {
            appendNode("hints", x.getHints().toString());
        }
        return true;
    }

    @Override
    public void endVisit(MySqlSelectQueryBlock x) {
        exit();
    }

    @Override
    public boolean visit(SQLSelectItem x) {
        String alias = x.getAlias() != null ? " AS " + x.getAlias() : "";
        appendNode("SQLSelectItem", x.getExpr().toString() + alias);
        enter();
        return true;
    }

    @Override
    public void endVisit(SQLSelectItem x) {
        exit();
    }

    @Override
    public boolean visit(SQLJoinTableSource x) {
        appendNode("SQLJoinTableSource", x.getJoinType().name());
        enter();
        return true;
    }

    @Override
    public void endVisit(SQLJoinTableSource x) {
        exit();
    }

    @Override
    public boolean visit(SQLSubqueryTableSource x) {
        String alias = x.getAlias() != null ? " AS " + x.getAlias() : "";
        appendNode("SQLSubqueryTableSource", alias);
        enter();
        return true;
    }

    @Override
    public void endVisit(SQLSubqueryTableSource x) {
        exit();
    }

    // ==================== Expr ====================

    @Override
    public boolean visit(SQLIdentifierExpr x) {
        appendNode("SQLIdentifierExpr", x.getName());
        return false;
    }

    @Override
    public boolean visit(SQLPropertyExpr x) {
        appendNode("SQLPropertyExpr", x.toString());
        return false;
    }

    @Override
    public boolean visit(SQLCharExpr x) {
        appendNode("SQLCharExpr", "'" + x.getText() + "'");
        return false;
    }

    @Override
    public boolean visit(SQLIntegerExpr x) {
        appendNode("SQLIntegerExpr", String.valueOf(x.getNumber()));
        return false;
    }

    @Override
    public boolean visit(SQLNumberExpr x) {
        appendNode("SQLNumberExpr", x.toString());
        return false;
    }

    @Override
    public boolean visit(SQLDefaultExpr x) {
        appendNode("SQLDefaultExpr", x.toString() != null ? x.toString() : "DEFAULT");
        return false;
    }

    @Override
    public boolean visit(SQLNullExpr x) {
        appendNode("SQLNullExpr", null);
        return false;
    }

    @Override
    public boolean visit(SQLBinaryOpExpr x) {
        appendNode("SQLBinaryOpExpr", x.getOperator().getName());
        enter();
        return true;
    }

    @Override
    public void endVisit(SQLBinaryOpExpr x) {
        exit();
    }

    // ==================== SelectItem (约束列引用) ====================

    @Override
    public boolean visit(SQLSelectOrderByItem x) {
        appendNode("SQLSelectOrderByItem", x.getExpr().toString());
        return false;
    }
}
