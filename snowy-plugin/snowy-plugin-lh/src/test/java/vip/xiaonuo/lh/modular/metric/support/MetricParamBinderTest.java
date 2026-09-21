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
}
