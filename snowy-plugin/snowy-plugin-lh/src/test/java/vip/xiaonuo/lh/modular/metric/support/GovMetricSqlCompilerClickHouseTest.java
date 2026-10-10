package vip.xiaonuo.lh.modular.metric.support;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class GovMetricSqlCompilerClickHouseTest {

    @Test
    void rewritesDateTruncAndQuotes() {
        String trino = "SELECT date_trunc('day', \"stat_date\") AS d, count(id) AS metric_value\n"
                + "FROM iceberg.dws.fact_order\nGROUP BY \"stat_date\"";
        String ck = GovMetricSqlCompiler.toClickHouseDialect(trino);
        Assertions.assertTrue(ck.contains("toStartOfDay("), ck);
        Assertions.assertTrue(ck.contains("`stat_date`"), ck);
        Assertions.assertTrue(ck.contains("dws.fact_order"), ck);
        Assertions.assertFalse(ck.contains("iceberg."), ck);
    }
}
