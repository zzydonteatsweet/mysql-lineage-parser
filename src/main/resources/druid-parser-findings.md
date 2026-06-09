# Druid SQL 解析器发现记录

## 2026-05-27: Druid 解析是从顶至下的遍历过程

### 发现内容

通过实际代码调试和源码分析，确认 Druid 的 SQL AST 遍历采用的是**从顶至下（Top-Down）**的方式。

### 具体表现

以 `SELECT col FROM t WHERE cond` 为例，Druid 构建的 AST 结构和遍历顺序如下：

```
SQLSelectStatement              ← 最外层包装
  └─ SQLSelect                  ← 查询对象
       └─ MySqlSelectQueryBlock ← 实际查询块
            ├─ getSelectList()  ← SELECT 子句（列列表）
            ├─ getFrom()        ← FROM 子句（表来源）
            ├─ getWhere()       ← WHERE 子句（过滤条件）
            ├─ getGroupBy()     ← GROUP BY 子句
            ├─ getHaving()      ← HAVING 子句
            └─ getOrderBy()     ← ORDER BY 子句
```

遍历时，调用链为：

```
visitor.visit(SQLSelectStatement)
  → visitor.visit(SQLSelect)
    → visitor.visit(MySqlSelectQueryBlock)
```

**每一层 visit 方法通过手动调用子节点的 `accept(this)` 来决定是否继续向下遍历。**

### visit 方法的返回值含义

`visit()` 方法的 `boolean` 返回值控制遍历行为：

- **返回 `true`**：告知 Druid 框架继续遍历当前节点的子节点（框架自动递归子节点）
- **返回 `false`**：阻止框架继续遍历当前节点的子节点（由开发者手动控制子节点的遍历）

### 本项目中的实践

在本项目的两个 Visitor 中可以看到两种不同的使用方式：

#### 1. 手动控制遍历（SelectLineageVisitor）

```java
// 返回 false，手动控制子节点遍历
@Override
public boolean visit(MySqlSelectQueryBlock x) {
    extractFromTables(x);       // 先提取 FROM 中的表（建立别名映射）
    extractSelectColumns(x);    // 再提取 SELECT 中的列（依赖别名映射）
    // WHERE 条件最后处理
    return false;
}
```

**关键点**：这里返回 `false` 是因为需要**控制子节点的遍历顺序**。先收集 FROM/JOIN 中的表和别名，再解析 SELECT 列表中引用的列，这样在解析 `t.col` 时才能通过别名找到对应的 `TableInfo`。如果让框架自动遍历（返回 `true`），无法保证这个顺序。

#### 2. 框架自动遍历（CreateLineageVisitor）

```java
// 返回 true，让框架自动遍历子节点
@Override
public boolean visit(SQLColumnDefinition x) {
    ColumnInfo column = new ColumnInfo();
    column.setColumnName(x.getColumnName());
    // ...
    metadata.addColumn(column);
    return true;  // 继续遍历列定义的子节点
}
```

### 对后续开发的指导意义

1. **子查询处理**：当 FROM 子句中包含子查询时，需要手动创建新的 `SelectLineageVisitor` 来解析内层查询，不能依赖框架自动递归（因为需要独立的 `aliasEntries` 上下文）

2. **遍历顺序很重要**：表别名必须在列引用之前收集完毕，否则无法正确解析 `t.col` 中的 `t` 对应哪个表。这在处理复杂 SQL（多表 JOIN、子查询）时尤为关键

3. **UNION 等复合查询**：UNION 的 AST 结构为 `SQLUnionQuery`，包含多个 `SQLSelectQuery` 分支。遍历时需要分别处理每个分支，各自建立独立的上下文
