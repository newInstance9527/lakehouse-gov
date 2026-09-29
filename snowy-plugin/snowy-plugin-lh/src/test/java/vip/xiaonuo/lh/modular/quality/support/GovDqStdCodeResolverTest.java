package vip.xiaonuo.lh.modular.quality.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRule;

class GovDqStdCodeResolverTest {

    @Test
    void parseCodeSetVariants() {
        assertEquals("ORDER_STATUS", GovDqStdCodeResolver.parseCodeSetFromExpr("codeSet:ORDER_STATUS"));
        assertEquals("ORDER_STATUS", GovDqStdCodeResolver.parseCodeSetFromExpr("code_set_id=ORDER_STATUS"));
        assertEquals("S1", GovDqStdCodeResolver.parseCodeSetFromExpr("std_code = S1"));
        assertNull(GovDqStdCodeResolver.parseCodeSetFromExpr("status IN ('A','B')"));
    }

    @Test
    void isEnumRule() {
        GovDqRule r = new GovDqRule();
        r.setRuleType("枚举");
        assertTrue(GovDqStdCodeResolver.isEnumRule(r));
        r.setRuleType("码值合规");
        assertTrue(GovDqStdCodeResolver.isEnumRule(r));
        r.setRuleType(null);
        r.setRuleCode("ENUM_STATUS");
        assertTrue(GovDqStdCodeResolver.isEnumRule(r));
    }
}
