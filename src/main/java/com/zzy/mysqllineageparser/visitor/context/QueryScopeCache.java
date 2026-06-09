package com.zzy.mysqllineageparser.visitor.context;

import com.zzy.mysqllineageparser.model.ColumnLineage;
import com.zzy.mysqllineageparser.model.TableInfo;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 子查询输出列映射：输出列名 → ColumnLineage（包含源列和转换信息）
     * 用于外层查询穿透子查询边界追溯物理源列
     */
    private Map<String, ColumnLineage> outputColumnMap = new LinkedHashMap<>();

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

    /**
     * 添加子查询输出列映射
     */
    public void addOutputColumnLineage(String colName, ColumnLineage lineage) {
        outputColumnMap.put(colName, lineage);
    }

    /**
     * 获取子查询输出列的 Lineage
     */
    public ColumnLineage getOutputColumnLineage(String colName) {
        return outputColumnMap.get(colName);
    }

    /**
     * 从内层查询 scope 复制输出列映射到派生表 scope
     */
    public void copyOutputColumnsFrom(QueryScopeCache other) {
        if (other != null && other.outputColumnMap != null) {
            this.outputColumnMap.putAll(other.outputColumnMap);
        }
    }
}
