package com.zzy.mysqllineageparser.parser.strategy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * SQL语句解析策略工厂
 */
@Component
public class ParseStrategyFactory {

    private final Map<String, StatementParseStrategy> strategyMap;

    @Autowired
    public ParseStrategyFactory(List<StatementParseStrategy> strategies) {
        // 将所有策略实现类按其支持的SQL类型进行映射
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(
                        StatementParseStrategy::getSupportedType,
                        Function.identity()
                ));
    }

    /**
     * 根据SQL类型获取对应的解析策略
     *
     * @param sqlType SQL类型（如 CREATE, INSERT, SELECT, UPDATE, DELETE）
     * @return 对应的解析策略
     * @throws IllegalArgumentException 如果没有找到匹配的策略
     */
    public StatementParseStrategy getStrategy(String sqlType) {
        StatementParseStrategy strategy = strategyMap.get(sqlType);
        if (strategy == null) {
            throw new IllegalArgumentException("不支持的SQL类型: " + sqlType);
        }
        return strategy;
    }

    /**
     * 检查是否支持指定的SQL类型
     *
     * @param sqlType SQL类型
     * @return 如果支持返回true，否则返回false
     */
    public boolean isSupported(String sqlType) {
        return strategyMap.containsKey(sqlType);
    }
}
