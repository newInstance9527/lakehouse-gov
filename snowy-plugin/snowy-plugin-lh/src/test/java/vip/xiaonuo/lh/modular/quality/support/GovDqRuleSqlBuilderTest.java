package vip.xiaonuo.lh.modular.quality.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovDqRuleSqlBuilderTest {

    private static final String T = "\"iceberg\".\"ods_trade\".\"s_order\"";

    @Test
    void defaultNotNull() {
        GovDqRuleSqlBuilder.Plan p = GovDqRuleSqlBuilder.build(
                "非空", "NULL_CHECK", "field", "user_id", null, T, "trino");
        assertEquals("not_null", p.mode);
        assertTrue(p.sql.contains("user_id"));
        assertTrue(p.sql.contains("IS NULL"));
        assertTrue(p.sql.contains("total_cnt"));
        assertTrue(p.sql.contains("fail_cnt"));
    }

    @Test
    void defaultUnique() {
        GovDqRuleSqlBuilder.Plan p = GovDqRuleSqlBuilder.build(
                "主键唯一", "PK_UNIQUE", "field", "order_id", "", T, "trino");
        assertEquals("unique", p.mode);
        assertTrue(p.sql.contains("COUNT(DISTINCT"));
    }

    @Test
    void rowPredicate() {
        GovDqRuleSqlBuilder.Plan p = GovDqRuleSqlBuilder.build(
                "范围", "RANGE", "field", "pay_amt", "{field} >= 0", T, "trino");
        assertEquals("row_predicate", p.mode);
        assertTrue(p.sql.contains("NOT (\"pay_amt\" >= 0)"));
    }

    @Test
    void selectHavingWrapped() {
        GovDqRuleSqlBuilder.Plan p = GovDqRuleSqlBuilder.build(
                "主键唯一", "PK_UNIQUE", "field", "order_id",
                "SELECT {field}, COUNT(*) c FROM T GROUP BY {field} HAVING c > 1",
                T, "trino");
        assertEquals("select_dup", p.mode);
        assertTrue(p.sql.contains("__dq_dup"));
        assertTrue(p.sql.contains("FROM " + T));
    }

    @Test
    void rejectBadField() {
        assertThrows(IllegalArgumentException.class, () ->
                GovDqRuleSqlBuilder.build("非空", null, "field", "a;drop", null, T, "trino"));
    }

    @Test
    void enumFromStdCodes() {
        GovDqRuleSqlBuilder.Plan p = GovDqRuleSqlBuilder.buildEnum(
                "order_status", java.util.List.of("NEW", "PAID", "CANCEL"), T, "trino");
        assertEquals("enum_std", p.mode);
        assertTrue(p.sql.contains("NOT IN"));
        assertTrue(p.sql.contains("'NEW'"));
        assertTrue(p.sql.contains("'PAID'"));
        assertTrue(p.sql.contains("order_status"));
    }

    @Test
    void enumRequiresItems() {
        assertThrows(IllegalArgumentException.class, () ->
                GovDqRuleSqlBuilder.buildEnum("status", java.util.List.of(), T, "trino"));
    }
}
