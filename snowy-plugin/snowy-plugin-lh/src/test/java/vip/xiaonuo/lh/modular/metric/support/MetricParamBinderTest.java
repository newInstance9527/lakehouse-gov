package vip.xiaonuo.lh.modular.metric.support;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class MetricParamBinderTest {

    @Test
    void bindDtPlaceholder() {
        String sql = "SELECT 1 FROM t WHERE dt = DATE '{{dt}}'";
        MetricParamBinder.BindOut out = MetricParamBinder.bind(sql, Map.of("dt", "2026-09-20"), "近1天", List.of("dt"));
        Assertions.assertTrue(out.sql().contains("DATE '2026-09-20'"));
        Assertions.assertFalse(out.sql().contains("{{"));
    }

    @Test
    void timePredicate近7天() {
        String p = MetricParamBinder.timePredicate("近7天", "dt");
        Assertions.assertTrue(p.contains("{{from}}"));
        Assertions.assertTrue(p.contains("{{to}}"));
    }

    @Test
    void rejectBadDim() {
        Assertions.assertThrows(IllegalArgumentException.class, () ->
                MetricParamBinder.bind(
                        "SELECT 1 WHERE channel = '{{dim.channel}}'",
                        Map.of("dims", Map.of("channel", "App';drop")),
                        "近1天",
                        List.of("channel")));
    }

    @Test
    void multiDimInPredicate() {
        List<String> preds = MetricParamBinder.dimFilterPredicates(
                Map.of("dims", Map.of("region", List.of("xm", "qz"))),
                List.of("region", "dt"));
        Assertions.assertEquals(1, preds.size());
        Assertions.assertTrue(preds.get(0).contains("IN ('xm', 'qz')"));
    }

    @Test
    void singleDimStillPlaceholder() {
        List<String> preds = MetricParamBinder.dimFilterPredicates(
                Map.of("dims", Map.of("region", "xm")),
                List.of("region"));
        Assertions.assertEquals(List.of("region = '{{dim.region}}'"), preds);
    }

    @Test
    void dimEqualsOrInClause() {
        Assertions.assertEquals("org = 'a'", MetricParamBinder.dimEqualsOrInClause("org", "a"));
        Assertions.assertEquals("org IN ('a', 'b')",
                MetricParamBinder.dimEqualsOrInClause("org", List.of("a", "b")));
    }
}
