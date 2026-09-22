package vip.xiaonuo.lh.modular.query.support;

import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.modular.query.support.CpQueryColumnMaskResolver.TableRef;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpQueryRowFilterInjectorTest {

    @Test
    void isSafePredicate_rejectsInjectionShapes() {
        assertTrue(CpQueryRowFilterInjector.isSafePredicate("tenant_id = 'A'"));
        assertTrue(CpQueryRowFilterInjector.isSafePredicate("(dept_id IN (1,2)) AND status = 'ok'"));
        assertFalse(CpQueryRowFilterInjector.isSafePredicate("1=1; DROP TABLE t"));
        assertFalse(CpQueryRowFilterInjector.isSafePredicate("tenant_id = 'A' -- bypass"));
        assertFalse(CpQueryRowFilterInjector.isSafePredicate("x = 1 UNION SELECT 1"));
        assertFalse(CpQueryRowFilterInjector.isSafePredicate(""));
    }

    @Test
    void combinePredicates_andsSafeParts() {
        assertEquals("(a = 1) AND (b = 2)",
                CpQueryRowFilterInjector.combinePredicates(List.of("a = 1", "b = 2")));
        assertEquals(null,
                CpQueryRowFilterInjector.combinePredicates(List.of("1; drop", "")));
    }

    @Test
    void isSelectLike_skipsShowDescribe() {
        assertTrue(CpQueryRowFilterInjector.isSelectLike("SELECT 1"));
        assertTrue(CpQueryRowFilterInjector.isSelectLike("WITH t AS (SELECT 1) SELECT * FROM t"));
        assertTrue(CpQueryRowFilterInjector.isSelectLike("EXPLAIN SELECT * FROM x"));
        assertFalse(CpQueryRowFilterInjector.isSelectLike("SHOW CATALOGS"));
        assertFalse(CpQueryRowFilterInjector.isSelectLike("DESCRIBE iceberg.s.t"));
    }

    @Test
    void wrapMatchingTableRefs_injectsSubqueryWithAlias() {
        TableRef target = new TableRef("iceberg", "ods_trade", "s_order");
        String sql = "SELECT a.id FROM iceberg.ods_trade.s_order a WHERE a.dt = '2026-01-01'";
        CpQueryRowFilterInjector.WrapOutcome out = CpQueryRowFilterInjector.wrapMatchingTableRefs(
                sql, target, "(tenant_id = 'A')", 0);
        assertEquals(1, out.wrapCount);
        assertTrue(out.sql.contains("(SELECT * FROM \"iceberg\".\"ods_trade\".\"s_order\" WHERE (tenant_id = 'A')) AS a"));
        assertTrue(out.sql.contains("WHERE a.dt = '2026-01-01'"));
    }

    @Test
    void wrapMatchingTableRefs_generatesAliasWhenMissing() {
        TableRef target = new TableRef("iceberg", "dwd", "user");
        String sql = "SELECT * FROM iceberg.dwd.user JOIN other.t ON 1=1";
        CpQueryRowFilterInjector.WrapOutcome out = CpQueryRowFilterInjector.wrapMatchingTableRefs(
                sql, target, "(ws = 'default')", 0);
        assertEquals(1, out.wrapCount);
        assertTrue(out.sql.contains(") AS __lh_rf0"));
        assertTrue(out.sql.contains("JOIN other.t"));
    }

    @Test
    void wrapMatchingTableRefs_doesNotSwallowOnKeyword() {
        TableRef target = new TableRef(null, "dwd", "orders");
        String sql = "SELECT * FROM a JOIN dwd.orders ON a.id = orders.id";
        CpQueryRowFilterInjector.WrapOutcome out = CpQueryRowFilterInjector.wrapMatchingTableRefs(
                sql, target, "(tenant_id = 'X')", 0);
        assertEquals(1, out.wrapCount);
        String lower = out.sql.toLowerCase();
        assertTrue(lower.contains(") as __lh_rf0 on a.id"));
        assertFalse(lower.contains("join dwd.orders on"));
    }
}
