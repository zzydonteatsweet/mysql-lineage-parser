package com.zzy.mysqllineageparser.parser;

import com.zzy.mysqllineageparser.model.ColumnInfo;
import com.zzy.mysqllineageparser.model.ColumnLineage;
import com.zzy.mysqllineageparser.model.LineageResult;
import com.zzy.mysqllineageparser.model.TableInfo;
import com.zzy.mysqllineageparser.parser.strategy.CreateTableParseStrategy;
import com.zzy.mysqllineageparser.parser.strategy.DeleteParseStrategy;
import com.zzy.mysqllineageparser.parser.strategy.InsertParseStrategy;
import com.zzy.mysqllineageparser.parser.strategy.ParseStrategyFactory;
import com.zzy.mysqllineageparser.parser.strategy.SelectParseStrategy;
import com.zzy.mysqllineageparser.parser.strategy.StatementParseStrategy;
import com.zzy.mysqllineageparser.parser.strategy.UpdateParseStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 别名 SELECT 语句血缘解析测试类
 * <p>
 * 在最简单的 SELECT 查询（单表、无 JOIN、无子查询）基础上，
 * 同时为 SELECT 列名与来源表加上别名（alias），验证列别名、表别名
 * 以及带别名列的来源解析均正确。
 */
class SelectLineageAliasTest {

    private SqlLineageParser parser;

    @BeforeEach
    void setUp() {
        List<StatementParseStrategy> strategies = new ArrayList<>();
        strategies.add(new CreateTableParseStrategy());
        strategies.add(new InsertParseStrategy());
        strategies.add(new SelectParseStrategy(null));
        strategies.add(new UpdateParseStrategy());
        strategies.add(new DeleteParseStrategy());

        ParseStrategyFactory factory = new ParseStrategyFactory(strategies);
        parser = new MysqlLineageParserImpl(factory);
    }

    // ========== 列别名 + 表别名 ==========

    /**
     * 列别名 + 表别名组合场景：
     * SELECT u.id AS user_id, u.name AS user_name FROM users AS u
     */
    @Test
    void testSelectWithColumnAndTableAlias() {
        String sql = "SELECT u.id AS user_id, u.name AS user_name FROM users AS u;";

        LineageResult result = parser.parse(sql);

        assertNotNull(result);
        assertEquals("SELECT", result.getSqlType());

        // 来源表应解析为 users，并带上别名 u
        assertEquals(1, result.getInputTables().size());
        TableInfo inputTable = result.getInputTables().get(0);
        assertEquals("users", inputTable.getTableName());
        assertEquals("u", inputTable.getAlias());

        // 列血缘应为 2 列，输出列名取列别名
        assertNotNull(result.getColumnLineages());
        assertEquals(2, result.getColumnLineages().size());
        assertEquals("user_id", result.getColumnLineages().get(0).getOutputColumn().getColumnName());
        assertEquals("user_name", result.getColumnLineages().get(1).getOutputColumn().getColumnName());
    }

    /**
     * 验证带列别名的血缘仍能解析回带别名的来源表（users AS u）
     */
    @Test
    void testAliasColumnSourceShouldResolveToAliasedTable() {
        String sql = "SELECT u.id AS user_id, u.name AS user_name FROM users AS u;";

        LineageResult result = parser.parse(sql);

        for (ColumnLineage cl : result.getColumnLineages()) {
            assertFalse(cl.getSourceColumns().isEmpty(),
                    "应解析出来源列: " + cl.getOutputColumn().getColumnName());
            for (ColumnInfo src : cl.getSourceColumns()) {
                assertNotNull(src.getTable());
                assertEquals("users", src.getTable().getTableName(), "来源应指向 users 表");
                assertEquals("u", src.getTable().getAlias(), "来源表应带别名 u");
            }
        }
    }
}
