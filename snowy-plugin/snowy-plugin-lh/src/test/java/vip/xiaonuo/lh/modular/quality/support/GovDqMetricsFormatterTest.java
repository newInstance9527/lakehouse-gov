package vip.xiaonuo.lh.modular.quality.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GovDqMetricsFormatterTest {

    @Test
    void formatsPassBlockedAndOkPct() {
        List<String> lines = GovDqMetricsFormatter.format(
                "PK_UNIQUE", "dwd.dwd_order", "default", "block",
                false, true, 88.5, 1_700_000_000_000L);
        String body = GovDqMetricsFormatter.joinBody(lines);
        assertTrue(body.contains("lh_dq_rule_pass{"));
        assertTrue(body.contains("lh_dq_rule_blocked{"));
        assertTrue(body.contains("lh_dq_ok_pct{"));
        assertTrue(body.contains("rule_code=\"PK_UNIQUE\""));
        assertTrue(body.contains(" 0 "));
        assertTrue(body.contains(" 1 "));
    }
}
