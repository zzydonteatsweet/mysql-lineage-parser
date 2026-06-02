package com.zzy.mysqllineageparser.visitor.context;

import com.zzy.mysqllineageparser.model.TableInfo;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询作用域缓存结构体
 * 描述当前查询块或 FROM 表来源的上下文信息
 */
@Data
public class QueryScopeCache {

    /**
     * 当前查询结果集别名（根查询为 query_result，派生表/子查询为 FROM 别名）
     */
    private String resultSetAlias;

    /**
     * 子查询缓存列表（FROM 为子查询时，内层查询的作用域）
     */
    private List<QueryScopeCache> subQueryCache = new ArrayList<>();

    /**
     * 父节点名称
     */
    private String parentNodeName;

    /**
     * 当前节点 ID
     */
    private String nodeId;

    /**
     * 父节点 ID
     */
    private String parentNodeId;

    /**
     * 当前节点涉及的物理表
     */
    private List<TableInfo> involvedTables = new ArrayList<>();

    public void addInvolvedTable(TableInfo table) {
        if (involvedTables == null) {
            involvedTables = new ArrayList<>();
        }
        if (!involvedTables.contains(table)) {
            involvedTables.add(table);
        }
    }

    public void addSubQueryCache(QueryScopeCache subScope) {
        if (subQueryCache == null) {
            subQueryCache = new ArrayList<>();
        }
        if (subScope != null) {
            subQueryCache.add(subScope);
        }
    }
}
