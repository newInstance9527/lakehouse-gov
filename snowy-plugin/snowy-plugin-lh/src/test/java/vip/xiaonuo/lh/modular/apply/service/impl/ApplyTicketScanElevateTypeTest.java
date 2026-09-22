package vip.xiaonuo.lh.modular.apply.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplyTicketScanElevateTypeTest {

    @Test
    void normalizeAcceptsElevateAliases() {
        assertEquals(ApplyTicketServiceImpl.TYPE_SCAN_ELEVATE,
                ApplyTicketServiceImpl.normalizeTicketType("scan_elevate"));
        assertEquals(ApplyTicketServiceImpl.TYPE_SCAN_ELEVATE,
                ApplyTicketServiceImpl.normalizeTicketType("elevated"));
        assertEquals(ApplyTicketServiceImpl.TYPE_SCAN_ELEVATE,
                ApplyTicketServiceImpl.normalizeTicketType("Elevate"));
        assertEquals(ApplyTicketServiceImpl.TYPE_SCAN_ELEVATE,
                ApplyTicketServiceImpl.normalizeTicketType("scan_quota"));
        assertEquals(ApplyTicketServiceImpl.TYPE_SCAN_ELEVATE,
                ApplyTicketServiceImpl.normalizeTicketType("adhoc_elevate"));
    }
}
