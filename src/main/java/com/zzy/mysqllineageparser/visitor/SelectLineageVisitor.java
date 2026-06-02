package com.zzy.mysqllineageparser.visitor;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import com.zzy.mysqllineageparser.model.ColumnInfo;
import com.zzy.mysqllineageparser.model.ColumnLineage;
import com.zzy.mysqllineageparser.model.LineageResult;
import com.zzy.mysqllineageparser.model.TableInfo;
import com.zzy.mysqllineageparser.visitor.context.QueryScopeCache;
import com.zzy.mysqllineageparser.visitor.context.TableSourceKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SELECT 语句深度遍历访问者
 * <p>
 * 手动控制访问顺序：先 FROM（建立表来源缓存）→ 再 SELECT（关联列血缘）→ 最后 WHERE。
 * FROM 表来源缓存在 {@link #tableSourceCache} 中，Key 为 {@link TableSourceKey}，Value 为 {@link QueryScopeCache}。
 */
public class SelectLineageVisitor extends MySqlASTVisitorAdapter {

    private final LineageResult lineageResult;

    /**
     * FROM 表来源缓存：Key=库名/表名/别名，Value=查询作用域缓存
     */
    private final Map<TableSourceKey, QueryScopeCache> tableSourceCache = new LinkedHashMap<>();

    /**
     * 当前查询块作用域
     */
    private QueryScopeCache currentScope;

    /**
     * 最近一次 visit(MySqlSelectQueryBlock) 创建的作用域（供子查询挂载）
     */
    private QueryScopeCache lastQueryBlockScope;

    /**
     * 输出表（根查询结果集）
     */
    private TableInfo outputTable;

    private final AtomicInteger nodeIdSeq = new AtomicInteger(0);

    public SelectLineageVisitor(LineageResult lineageResult) {
        this.lineageResult = lineageResult;
    }

    @Override
    public boolean visit(SQLSelectStatement x) {
        if (x.getSelect() != null) {
            x.getSelect().accept(this);
        }
        return false;
    }

    /**
     * 手动控制子节点访问顺序：FROM → SELECT → WHERE
     */
    @Override
    public boolean visit(MySqlSelectQueryBlock x) {
        tableSourceCache.clear();

        QueryScopeCache parentScope = currentScope;
        currentScope = createQueryScope(parentScope, "query_result");
        lastQueryBlockScope = currentScope;

        // 1. 先访问 FROM，将表来源写入 tableSourceCache
        if (x.getFrom() != null) {
            collectFromAndCache(x.getFrom(), currentScope);
        }

        // 2. 再访问 SELECT 字段，利用缓存关联列血缘
        extractSelectColumns(x);

        // 3. 最后处理 WHERE 过滤条件
        if (x.getWhere() != null) {
            applyWhereFilter(x.getWhere().toString());
        }

        currentScope = parentScope;
        return false;
    }

    /**
     * 创建查询作用域节点
     */
    private QueryScopeCache createQueryScope(QueryScopeCache parent, String resultSetAlias) {
        QueryScopeCache scope = new QueryScopeCache();
        scope.setNodeId("n" + nodeIdSeq.incrementAndGet());
        scope.setResultSetAlias(resultSetAlias);
        if (parent != null) {
            scope.setParentNodeId(parent.getNodeId());
            scope.setParentNodeName(parent.getResultSetAlias());
        }
        return scope;
    }

    /**
     * 递归收集 FROM / JOIN 中的表来源并写入缓存 Map
     */
    private void collectFromAndCache(SQLTableSource tableSource, QueryScopeCache parentScope) {
        if (tableSource instanceof SQLExprTableSource) {
            cacheExprTableSource((SQLExprTableSource) tableSource, parentScope);
        } else if (tableSource instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) tableSource;
            collectFromAndCache(join.getLeft(), parentScope);
            collectFromAndCache(join.getRight(), parentScope);
        } else if (tableSource instanceof SQLSubqueryTableSource) {
            cacheSubqueryTableSource((SQLSubqueryTableSource) tableSource, parentScope);
        }
    }

    /**
     * 缓存物理表来源
     */
    private void cacheExprTableSource(SQLExprTableSource tableSource, QueryScopeCache parentScope) {
        TableInfo tableInfo = extractTableInfo(tableSource.getExpr(), tableSource.getAlias());
        if (tableInfo == null) {
            return;
        }

        TableSourceKey key = TableSourceKey.from(tableInfo);
        QueryScopeCache cache = buildTableSourceCache(tableInfo, parentScope);
        tableSourceCache.put(key, cache);

        lineageResult.addInputTable(tableInfo);
        mergeInvolvedTables(parentScope, cache.getInvolvedTables());
    }

    /**
     * 缓存子查询派生表来源
     */
    private void cacheSubqueryTableSource(SQLSubqueryTableSource tableSource, QueryScopeCache parentScope) {
        String derivedAlias = tableSource.getAlias() != null ? tableSource.getAlias() : "subquery";

        TableSourceKey key = new TableSourceKey(null, derivedAlias, derivedAlias);
        QueryScopeCache cache = createQueryScope(parentScope, derivedAlias);

        // 递归解析内层子查询，内层作用域挂到 subQueryCache
        if (tableSource.getSelect() != null && tableSource.getSelect().getQuery() != null) {
            QueryScopeCache savedScope = currentScope;
            currentScope = cache;
            tableSource.getSelect().getQuery().accept(this);
            if (lastQueryBlockScope != null) {
                cache.addSubQueryCache(lastQueryBlockScope);
                mergeInvolvedTables(cache, lastQueryBlockScope.getInvolvedTables());
            }
            currentScope = savedScope;
        }

        tableSourceCache.put(key, cache);
        mergeInvolvedTables(parentScope, cache.getInvolvedTables());
    }

    private QueryScopeCache buildTableSourceCache(TableInfo tableInfo, QueryScopeCache parentScope) {
        QueryScopeCache cache = createQueryScope(parentScope, tableInfo.getAlias());
        cache.addInvolvedTable(tableInfo);
        return cache;
    }

    private void mergeInvolvedTables(QueryScopeCache target, List<TableInfo> tables) {
        if (target == null || tables == null) {
            return;
        }
        for (TableInfo table : tables) {
            target.addInvolvedTable(table);
        }
    }

    /**
     * 从 SELECT 子句提取列血缘，依赖 tableSourceCache 解析表引用
     */
    private void extractSelectColumns(MySqlSelectQueryBlock x) {
        if (x.getSelectList() == null) {
            return;
        }

        ensureOutputTable();

        for (SQLSelectItem item : x.getSelectList()) {
            SQLExpr expr = item.getExpr();
            String alias = item.getAlias();

            String outputColumnName = resolveOutputColumnName(expr, alias);
            ColumnInfo outputColumn = new ColumnInfo(outputTable, outputColumnName);

            ColumnLineage lineage = new ColumnLineage(outputColumn, outputTable);
            resolveSourceColumns(expr, lineage);

            lineageResult.addColumnLineage(lineage);
        }
    }

    private void applyWhereFilter(String whereCondition) {
        for (ColumnLineage cl : lineageResult.getColumnLineages()) {
            cl.setFilterCondition(whereCondition);
        }
    }

    private void ensureOutputTable() {
        if (outputTable == null) {
            outputTable = new TableInfo("", "query_result");
            lineageResult.addOutputTable(outputTable);
        }
    }

    private TableInfo extractTableInfo(SQLExpr expr, String alias) {
        if (expr instanceof SQLPropertyExpr) {
            SQLPropertyExpr prop = (SQLPropertyExpr) expr;
            String db = prop.getOwner() != null ? prop.getOwner().toString() : null;
            return new TableInfo(db, prop.getName(), alias);
        } else if (expr instanceof SQLIdentifierExpr) {
            return new TableInfo(null, ((SQLIdentifierExpr) expr).getName(), alias);
        }
        return null;
    }

    private String resolveOutputColumnName(SQLExpr expr, String alias) {
        if (alias != null && !alias.isEmpty()) {
            return alias;
        }
        if (expr instanceof SQLIdentifierExpr) {
            return ((SQLIdentifierExpr) expr).getName();
        }
        if (expr instanceof SQLPropertyExpr) {
            return ((SQLPropertyExpr) expr).getName();
        }
        if (expr instanceof SQLAllColumnExpr) {
            return "*";
        }
        return expr.toString();
    }

    private void resolveSourceColumns(SQLExpr expr, ColumnLineage lineage) {
        if (expr instanceof SQLIdentifierExpr) {
            String colName = ((SQLIdentifierExpr) expr).getName();
            TableInfo tableInfo = resolveSingleTable();
            lineage.addSourceColumn(new ColumnInfo(tableInfo, colName));
            lineage.setTransformation("direct mapping");
        } else if (expr instanceof SQLPropertyExpr) {
            SQLPropertyExpr prop = (SQLPropertyExpr) expr;
            String owner = prop.getOwner().toString();
            String colName = prop.getName();
            TableInfo tableInfo = resolveTableByReference(owner);
            lineage.addSourceColumn(new ColumnInfo(tableInfo, colName));
            lineage.setTransformation("direct mapping");
        } else if (expr instanceof SQLAllColumnExpr) {
            lineage.setTransformation("SELECT *");
        } else {
            lineage.setTransformation(expr.toString());
            for (ColumnInfo col : extractReferencedColumns(expr)) {
                lineage.addSourceColumn(col);
            }
        }
    }

    /**
     * 单表场景：缓存 Map 中仅有一个表来源时，无前缀列默认关联该表
     */
    private TableInfo resolveSingleTable() {
        if (tableSourceCache.size() == 1) {
            return tableSourceCache.keySet().iterator().next().toTableInfo();
        }
        return null;
    }

    /**
     * 通过别名或表名从 tableSourceCache 查找物理表
     */
    private TableInfo resolveTableByReference(String reference) {
        for (TableSourceKey key : tableSourceCache.keySet()) {
            if (reference.equals(key.getReferenceName())) {
                return key.toTableInfo();
            }
        }
        return null;
    }

    private List<ColumnInfo> extractReferencedColumns(SQLExpr expr) {
        List<ColumnInfo> columns = new ArrayList<>();
        collectReferencedColumns(expr, columns);
        return columns;
    }

    private void collectReferencedColumns(SQLExpr expr, List<ColumnInfo> columns) {
        if (expr == null) {
            return;
        }
        if (expr instanceof SQLPropertyExpr) {
            SQLPropertyExpr prop = (SQLPropertyExpr) expr;
            TableInfo tableInfo = resolveTableByReference(prop.getOwner().toString());
            columns.add(new ColumnInfo(tableInfo, prop.getName()));
        } else if (expr instanceof SQLIdentifierExpr) {
            TableInfo tableInfo = resolveSingleTable();
            columns.add(new ColumnInfo(tableInfo, ((SQLIdentifierExpr) expr).getName()));
        } else if (expr instanceof SQLBinaryOpExpr) {
            SQLBinaryOpExpr binary = (SQLBinaryOpExpr) expr;
            collectReferencedColumns(binary.getLeft(), columns);
            collectReferencedColumns(binary.getRight(), columns);
        } else if (expr instanceof SQLMethodInvokeExpr) {
            SQLMethodInvokeExpr method = (SQLMethodInvokeExpr) expr;
            if (method.getArguments() != null) {
                for (SQLExpr arg : method.getArguments()) {
                    collectReferencedColumns(arg, columns);
                }
            }
        }
    }

    /**
     * 获取 FROM 表来源缓存（供调试或后续子查询扩展使用）
     */
    public Map<TableSourceKey, QueryScopeCache> getTableSourceCache() {
        return tableSourceCache;
    }

    public LineageResult getLineageResult() {
        return lineageResult;
    }
}
