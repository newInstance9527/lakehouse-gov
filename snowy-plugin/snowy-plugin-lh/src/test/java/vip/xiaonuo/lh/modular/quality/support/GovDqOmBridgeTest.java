package vip.xiaonuo.lh.modular.quality.support;

import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GovDqOmBridgeTest {

    @Test
    void mapNotNull() {
        GovDqRule r = new GovDqRule();
        r.setFieldName("user_id");
        r.setRuleType("非空检查");
        assertEquals("columnValuesToBeNotNull", GovDqOmBridge.mapTestDefinition(r));
    }

    @Test
    void mapUnique() {
        GovDqRule r = new GovDqRule();
        r.setFieldName("order_id");
        r.setRuleCode("PK_UNIQUE");
        assertEquals("columnValuesToBeUnique", GovDqOmBridge.mapTestDefinition(r));
    }

    @Test
    void mapUnsupported() {
        GovDqRule r = new GovDqRule();
        r.setFieldName("amt");
        r.setRuleType("范围");
        assertNull(GovDqOmBridge.mapTestDefinition(r));
    }

    @Test
    void mapNeedsField() {
        GovDqRule r = new GovDqRule();
        r.setRuleType("非空");
        assertNull(GovDqOmBridge.mapTestDefinition(r));
    }
}
