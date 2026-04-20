package com.zzy.mysqllineageparser.parser;

import lombok.Data;

/**
 * SQL解析上下文
 */
@Data
public class ParseContext {

    /**
     * 默认数据库名称
     */
    private String defaultDatabase;

    /**
     * 是否忽略大小写
     */
    private boolean caseInsensitive = true;

    /**
     * 是否收集列级血缘
     */
    private boolean collectColumnLineage = true;

    public ParseContext() {
    }

    public ParseContext(String defaultDatabase) {
        this.defaultDatabase = defaultDatabase;
    }

    /**
     * 创建默认上下文
     */
    public static ParseContext createDefault() {
        return new ParseContext();
    }

    /**
     * 创建带默认数据库的上下文
     */
    public static ParseContext withDatabase(String database) {
        return new ParseContext(database);
    }
}
