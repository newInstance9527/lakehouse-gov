package vip.xiaonuo.lh.core.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenMetadataClientGlossaryNameTest {

    @Test
    void sanitize_keeps_field_and_code_ids() {
        assertEquals("pay_amt", OpenMetadataClient.sanitizeGlossaryName("pay_amt"));
        assertEquals("STD-C0021", OpenMetadataClient.sanitizeGlossaryName("STD-C0021"));
    }

    @Test
    void sanitize_replaces_illegal_and_prefixes() {
        assertEquals("order_status", OpenMetadataClient.sanitizeGlossaryName("order status"));
        assertTrue(OpenMetadataClient.sanitizeGlossaryName("9bad").startsWith("t_"));
        assertNull(OpenMetadataClient.sanitizeGlossaryName("  "));
    }
}
