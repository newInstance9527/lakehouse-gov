package vip.xiaonuo.lh.modular.metric.support;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.modular.metric.entity.GovMetric;
import vip.xiaonuo.lh.modular.metric.entity.GovMetricVer;

class GovMetricSqlCompilerTest {

    @Test
    void qualifyTable() {
        Assertions.assertEquals("iceberg.dwd_trade.dwd_order_detail",
                GovMetricSqlCompiler.qualifyTable("dwd_trade.dwd_order_detail"));
        Assertions.assertEquals("iceberg.dwd.x.y",
                GovMetricSqlCompiler.qualifyTable("iceberg.dwd.x.y".replace("iceberg.dwd.x.y", "iceberg.dwd.x.y")));
    }

    @Test
    void compileAtomHasFrom() {
        GovMetric h = new GovMetric();
        h.setMetricCode("A-0012");
        h.setKind("原子");
        h.setStatus("active");
        GovMetricVer v = new GovMetricVer();
        v.setVer("v1");
        v.setAgg("COUNT DISTINCT");
        v.setBindTable("dwd_trade.dwd_order_detail");
        v.setBindField("order_id");
        v.setQualifierJson("[\"pay_status=SUCCESS\"]");
        GovMetricSqlCompiler.CompileOut out = GovMetricSqlCompiler.compile(h, v, "trino", c -> null);
        Assertions.assertTrue(out.sqlText().contains("iceberg.dwd_trade.dwd_order_detail"));
        Assertions.assertTrue(out.sqlText().contains("count(DISTINCT order_id)"));
        Assertions.assertTrue(out.sqlText().contains("pay_status=SUCCESS"));
    }

    @Test
    void compileDerivedHasDatePlaceholder() {
        GovMetric atomH = new GovMetric();
        atomH.setMetricCode("A-0012");
        atomH.setKind("原子");
        atomH.setStatus("active");
        GovMetricVer atomV = new GovMetricVer();
        atomV.setAgg("SUM");
        atomV.setBindTable("dwd_trade.dwd_order_detail");
        atomV.setBindField("pay_amt");

        GovMetric derH = new GovMetric();
        derH.setMetricCode("M-0001");
        derH.setKind("衍生");
        derH.setStatus("active");
        GovMetricVer derV = new GovMetricVer();
        derV.setVer("v3");
        derV.setAtomRef("A-0012");
        derV.setGrainJson("[\"dt\"]");
        derV.setTimeWindow("近1天");
        derV.setQualifierJson("[\"pay_status=SUCCESS\"]");

        GovMetricSqlCompiler.CompileOut out = GovMetricSqlCompiler.compile(derH, derV, "trino",
                c -> new GovMetricSqlCompiler.MetricNode("A-0012", atomH, atomV));
        Assertions.assertTrue(out.sqlText().contains("DATE '{{dt}}'"));
        Assertions.assertTrue(out.sqlText().contains("GROUP BY dt"));
    }
}
