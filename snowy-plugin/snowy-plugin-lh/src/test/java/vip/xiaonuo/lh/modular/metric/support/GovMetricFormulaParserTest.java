package vip.xiaonuo.lh.modular.metric.support;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class GovMetricFormulaParserTest {

    @Test
    void parseCompositeOk() {
        GovMetricFormulaParser.Result r = GovMetricFormulaParser.parse("M-0001 / A-0012");
        Assertions.assertTrue(r.ok());
        Assertions.assertEquals(2, r.refs().size());
        Assertions.assertTrue(r.refs().contains("M-0001"));
        Assertions.assertTrue(r.refs().contains("A-0012"));
    }

    @Test
    void rejectDimInFormula() {
        GovMetricFormulaParser.Result r = GovMetricFormulaParser.parse("A-0012 · 按天");
        Assertions.assertFalse(r.ok());
    }
}
