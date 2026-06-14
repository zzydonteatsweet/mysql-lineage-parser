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
import com.zzy.mysqllineageparser.mybatis.support.TableMetaSupport;
import com.zzy.mysqllineageparser.visitor.context.QueryScopeCache;
import com.zzy.mysqllineageparser.visitor.context.TableSourceKey;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SELECT 语句深度遍历访问者
 * <p>
 * 手动控制访问顺序：先 FROM（建立表来源缓存）→ 再 SELECT（关联列血缘）→ 最后 WHERE。
 * 支持子查询穿透：外层查询可通过 scope 的 outputColumnMap 追溯子查询内部的物理源列。
 */
public class SelectLineageVisitor extends MySqlASTVisitorAdapter {

    private final LineageResult lineageResult;

    private final TableMetaSupport tableMetaSupport;

    /**
     * 默认数据库名称（当表名无库前缀时使用）
     */
    private final String defaultDatabase;

    /**
     * FROM 表来源缓存：Key=库名/表名/别名，Value=查询作用域缓存
     * 每个查询块使用独立的缓存，通过 save/restore 保证嵌套子查询不会破坏外层缓存
     */
    private Map<TableSourceKey, QueryScopeCache> tableSourceCacheMap = new LinkedHashMap<>();

    /**
     * 当前查询块作用域
     */
    private QueryScopeCache currentScope;

    /**
     * 最近一次 visit(MySqlSelectQueryBlock) 创建的作用域（供子查询挂载）
     */
    private QueryScopeCache lastQueryBlockScope;

    /**
     * 当前查询域涉及到的血缘
     */
    private List<ColumnLineage> nowScopeColumnLineages;

    /**
     * 输出表（根查询结果集）
     */
    private TableInfo outputTable;

    private final AtomicInteger nodeIdSeq = new AtomicInteger(0);

    /**
     * 子查询嵌套深度，0=最外层查询
     */
    private int nestingLevel = 0;

    public SelectLineageVisitor(LineageResult lineageResult) {
        this(lineageResult, null, null);
    }

    public SelectLineageVisitor(LineageResult lineageResult, String defaultDatabase) {
        this(lineageResult, defaultDatabase, null);
    }

    public SelectLineageVisitor(LineageResult lineageResult, String defaultDatabase, TableMetaSupport tableMetaSupport) {
        this.lineageResult = lineageResult;
        this.defaultDatabase = defaultDatabase;
        this.tableMetaSupport = tableMetaSupport;
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
     * 每个查询块使用独立的 tableSourceCache，避免嵌套子查询破坏外层缓存
     */
    @Override
    public boolean visit(MySqlSelectQueryBlock x) {
        // 保存外层缓存，为当前查询块创建独立缓存
        Map<TableSourceKey, QueryScopeCache> outerCache = new LinkedHashMap<>(tableSourceCacheMap);
        tableSourceCacheMap.clear();

        QueryScopeCache parentScope = currentScope;
        currentScope = createQueryScope(parentScope, "query_result");
        lastQueryBlockScope = currentScope;

        // 1. 先访问 FROM，将表来源写入当前层的 tableSourceCache
        if (x.getFrom() != null) {
            collectFromAndCache(x.getFrom(), currentScope);
        }

        // 2. 再访问 SELECT 字段，利用缓存关联列血缘
        List<ColumnLineage> queryLineages = new ArrayList<>();
        extractSelectColumns(x, queryLineages);

        // 3. 最后处理 WHERE 过滤条件
        if (x.getWhere() != null) {
            applyWhereFilter(x.getWhere().toString(), queryLineages);
        }

        currentScope = parentScope;
        // 恢复外层缓存
        tableSourceCacheMap.clear();
        tableSourceCacheMap.putAll(outerCache);
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
        tableSourceCacheMap.put(key, cache);

        addInputTableDeduped(tableInfo);
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
            nestingLevel++;
            QueryScopeCache savedScope = currentScope;
            currentScope = cache;

            // 为内层子查询保存和重置缓存
            Map<TableSourceKey, QueryScopeCache> outerCache = new LinkedHashMap<>(tableSourceCacheMap);
            tableSourceCacheMap.clear();

            tableSource.getSelect().getQuery().accept(this);

            // 恢复缓存
            tableSourceCacheMap.clear();
            tableSourceCacheMap.putAll(outerCache);

            if (lastQueryBlockScope != null) {
                cache.addSubQueryCache(lastQueryBlockScope);
                mergeInvolvedTables(cache, lastQueryBlockScope.getInvolvedTables());
                // 将内层查询的输出列传递到派生表 scope，供外层穿透查找
                cache.copyOutputColumnsFrom(lastQueryBlockScope);
            }
            currentScope = savedScope;
            nestingLevel--;
        }

        tableSourceCacheMap.put(key, cache);
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
     * 添加输入表（按 databaseName + tableName 去重）
     * 同一个物理表在不同层级出现（如外层 employees e 和子查询 employees）只记录一次
     */
    private void addInputTableDeduped(TableInfo tableInfo) {
        for (TableInfo existing : lineageResult.getInputTables()) {
            if (Objects.equals(existing.getTableName(), tableInfo.getTableName())
                    && Objects.equals(existing.getDatabaseName(), tableInfo.getDatabaseName())) {
                return;
            }
        }
        lineageResult.getInputTables().add(tableInfo);
        lineageResult.getInputTableNames().add(tableInfo.getFullName());
    }

    /**
     * 从 SELECT 子句提取列血缘，依赖 tableSourceCache 解析表引用
     * 内层查询的 lineage 仅存入 scope 的 outputColumnMap，不添加到 lineageResult
     */
    private void extractSelectColumns(MySqlSelectQueryBlock x, List<ColumnLineage> queryLineages) {
        if (x.getSelectList() == null) {
            return;
        }

        for (SQLSelectItem item : x.getSelectList()) {
            SQLExpr expr = item.getExpr();
            String alias = item.getAlias();

            String outputColumnName = resolveOutputColumnName(expr, alias);


            ColumnInfo outputColumn = new ColumnInfo(outputTable, outputColumnName);

            ColumnLineage lineage = new ColumnLineage(outputColumn, outputTable);
//            resolveSourceColumns(expr, lineage);

            // 关联列与来源表
            resolveColumnSourceTables(outputColumnName, lineage);

            queryLineages.add(lineage);

            // 仅最外层查询添加到血缘结果
            if (nestingLevel == 0) {
                lineageResult.addColumnLineage(lineage);
            }

            // 存入当前 scope，供外层穿透查找
            if (currentScope != null) {
                currentScope.addOutputColumnLineage(outputColumnName, lineage);
            }
        }
    }

    /**
     * 对当前查询块的 lineage 设置 WHERE 过滤条件
     */
    private void applyWhereFilter(String whereCondition, List<ColumnLineage> queryLineages) {
        for (ColumnLineage cl : queryLineages) {
            cl.setFilterCondition(whereCondition);
        }
    }

    /**
     * 去除标识符两端的反引号
     */
    private String stripBackticks(String name) {
        if (name != null && name.startsWith("`") && name.endsWith("`") && name.length() >= 2) {
            return name.substring(1, name.length() - 1);
        }
        return name;
    }

    private TableInfo extractTableInfo(SQLExpr expr, String alias) {
        if (expr instanceof SQLPropertyExpr) {
            SQLPropertyExpr prop = (SQLPropertyExpr) expr;
            String db = prop.getOwner() != null ? stripBackticks(prop.getOwner().toString()) : null;
            return new TableInfo(db, stripBackticks(prop.getName()), alias);
        } else if (expr instanceof SQLIdentifierExpr) {
            return new TableInfo(defaultDatabase, stripBackticks(((SQLIdentifierExpr) expr).getName()), alias);
        }
        return null;
    }

    private String resolveOutputColumnName(SQLExpr expr, String alias) {
        if (alias != null && !alias.isEmpty()) {
            return stripBackticks(alias);
        }
        if (expr instanceof SQLIdentifierExpr) {
            return stripBackticks(((SQLIdentifierExpr) expr).getName());
        }
        if (expr instanceof SQLPropertyExpr) {
            return stripBackticks(((SQLPropertyExpr) expr).getName());
        }
        if (expr instanceof SQLAllColumnExpr) {
            return "*";
        }
        return expr.toString();
    }

    /**
     * 解析列所属的来源表，并添加到 lineage 的 sourceColumns 中
     * <p>
     * 解析策略（按优先级）：
     * 1. 从 tableSourceCacheMap 中遍历，检查子查询 outputColumnMap 是否有同名列，有则使用该 key 对应的表
     * 2. 若未找到，使用 tableMetaSupport 查询每个表的实际字段，有匹配则选择该表
     * 3. 若仍未找到，将 map 中所有的表作为来源表
     *
     * @param colName 输出列名
     * @param lineage 待填充的列血缘对象
     */
    private void resolveColumnSourceTables(String colName, ColumnLineage lineage) {
        List<TableInfo> matchedTables = new ArrayList<>();

        // 策略1：从 tableSourceCacheMap 中遍历，检查 outputColumnMap 是否有同名列
        for (Map.Entry<TableSourceKey, QueryScopeCache> entry : tableSourceCacheMap.entrySet()) {
            QueryScopeCache cache = entry.getValue();
            if (cache.getOutputColumnLineage(colName) != null) {
                matchedTables.add(entry.getKey().toTableInfo());
            }
        }

        if (!matchedTables.isEmpty()) {
            addSourceColumnsForTables(matchedTables, colName, lineage);
            return;
        }

        // 策略2：使用 tableMetaSupport 查询每个表的字段
        if (tableMetaSupport != null) {
            for (Map.Entry<TableSourceKey, QueryScopeCache> entry : tableSourceCacheMap.entrySet()) {
                String tableName = entry.getKey().getTableName();
                List<ColumnInfo> tableColumns = tableMetaSupport.getTableColumns(tableName);
                if (tableColumns != null) {
                    for (ColumnInfo col : tableColumns) {
                        if (colName.equalsIgnoreCase(col.getColumnName())) {
                            matchedTables.add(entry.getKey().toTableInfo());
                            break;
                        }
                    }
                }
            }
        }

        if (!matchedTables.isEmpty()) {
            addSourceColumnsForTables(matchedTables, colName, lineage);
            return;
        }

        // 策略3：未找到匹配，将 map 中所有的表作为来源表
        for (TableSourceKey key : tableSourceCacheMap.keySet()) {
            matchedTables.add(key.toTableInfo());
        }

        addSourceColumnsForTables(matchedTables, colName, lineage);
    }

    /**
     * 将匹配到的来源表添加为 lineage 的 sourceColumns
     */
    private void addSourceColumnsForTables(List<TableInfo> tables, String colName, ColumnLineage lineage) {
        for (TableInfo table : tables) {
            lineage.addSourceColumn(new ColumnInfo(table, colName));
        }
    }

    private void resolveSourceColumns(SQLExpr expr, ColumnLineage lineage) {
        if (expr instanceof SQLIdentifierExpr) {
            String colName = stripBackticks(((SQLIdentifierExpr) expr).getName());
            // 尝试子查询穿透（单表无前缀场景）
            if (tryResolveSubqueryColumn(null, colName, lineage)) {
                return;
            }
            TableInfo tableInfo = resolveSingleTable();
            lineage.addSourceColumn(new ColumnInfo(tableInfo, colName));
            lineage.setTransformation("direct mapping");
        } else if (expr instanceof SQLPropertyExpr) {
            SQLPropertyExpr prop = (SQLPropertyExpr) expr;
            String owner = stripBackticks(prop.getOwner().toString());
            String colName = stripBackticks(prop.getName());
            // 尝试子查询穿透（有表前缀场景）
            if (tryResolveSubqueryColumn(owner, colName, lineage)) {
                return;
            }
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
     * 尝试子查询穿透解析：从子查询 scope 的 outputColumnMap 查找列
     *
     * @param tableRef 表引用（别名或表名），null 表示单表无前缀场景
     * @param colName  列名
     * @param lineage  待填充的血缘对象
     * @return 是否穿透成功
     */
    private boolean tryResolveSubqueryColumn(String tableRef, String colName, ColumnLineage lineage) {
        if (tableRef != null) {
            // 有表前缀：t.col → 查找子查询 scope
            for (Map.Entry<TableSourceKey, QueryScopeCache> entry : tableSourceCacheMap.entrySet()) {
                if (tableRef.equals(entry.getKey().getReferenceName())) {
                    ColumnLineage innerLineage = entry.getValue().getOutputColumnLineage(colName);
                    if (innerLineage != null) {
                        copySourcesFromInnerLineage(innerLineage, lineage);
                        return true;
                    }
                }
            }
        } else {
            // 无表前缀：单表 → 检查唯一表来源是否为子查询
            if (tableSourceCacheMap.size() == 1) {
                Map.Entry<TableSourceKey, QueryScopeCache> entry =
                        tableSourceCacheMap.entrySet().iterator().next();
                ColumnLineage innerLineage = entry.getValue().getOutputColumnLineage(colName);
                if (innerLineage != null) {
                    copySourcesFromInnerLineage(innerLineage, lineage);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 从内层查询 lineage 复制源列和转换信息到外层 lineage
     */
    private void copySourcesFromInnerLineage(ColumnLineage innerLineage, ColumnLineage outerLineage) {
        if (innerLineage.getSourceColumns() != null) {
            for (ColumnInfo source : innerLineage.getSourceColumns()) {
                outerLineage.addSourceColumn(source);
            }
        }
        String transformation = innerLineage.getTransformation();
        outerLineage.setTransformation(transformation != null ? transformation : "direct mapping");
    }

    /**
     * 单表场景：缓存 Map 中仅有一个表来源时，无前缀列默认关联该表
     */
    private TableInfo resolveSingleTable() {
        if (tableSourceCacheMap.size() == 1) {
            return tableSourceCacheMap.keySet().iterator().next().toTableInfo();
        }
        return null;
    }

    /**
     * 通过别名或表名从 tableSourceCache 查找物理表
     */
    private TableInfo resolveTableByReference(String reference) {
        for (TableSourceKey key : tableSourceCacheMap.keySet()) {
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
            TableInfo tableInfo = resolveTableByReference(stripBackticks(prop.getOwner().toString()));
            columns.add(new ColumnInfo(tableInfo, stripBackticks(prop.getName())));
        } else if (expr instanceof SQLIdentifierExpr) {
            TableInfo tableInfo = resolveSingleTable();
            columns.add(new ColumnInfo(tableInfo, stripBackticks(((SQLIdentifierExpr) expr).getName())));
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
    public Map<TableSourceKey, QueryScopeCache> getTableSourceCacheMap() {
        return tableSourceCacheMap;
    }

    public LineageResult getLineageResult() {
        return lineageResult;
    }
}
