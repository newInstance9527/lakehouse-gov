package vip.xiaonuo.lh.modular.apply.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 发布包申请类型：前端 publish 须落到 script_publish，不得误进 api_publish。
 */
class ApplyTicketScriptPublishTypeTest {

    @Test
    void normalizeMapsPublishTabToScriptPublish() {
        assertEquals(ApplyTicketServiceImpl.TYPE_SCRIPT_PUBLISH,
                ApplyTicketServiceImpl.normalizeTicketType("publish"));
        assertEquals(ApplyTicketServiceImpl.TYPE_SCRIPT_PUBLISH,
                ApplyTicketServiceImpl.normalizeTicketType("script_publish"));
        assertEquals(ApplyTicketServiceImpl.TYPE_SCRIPT_PUBLISH,
                ApplyTicketServiceImpl.normalizeTicketType("package_publish"));
        assertEquals(ApplyTicketServiceImpl.TYPE_SCRIPT_PUBLISH,
                ApplyTicketServiceImpl.normalizeTicketType("release_publish"));
    }

    @Test
    void normalizeKeepsApiPublishDistinct() {
        assertEquals(ApplyTicketServiceImpl.TYPE_API_PUBLISH,
                ApplyTicketServiceImpl.normalizeTicketType("api_publish"));
        assertEquals(ApplyTicketServiceImpl.TYPE_API_PUBLISH,
                ApplyTicketServiceImpl.normalizeTicketType("publish_api"));
        assertEquals(ApplyTicketServiceImpl.TYPE_API_PUBLISH,
                ApplyTicketServiceImpl.normalizeTicketType("dataapi_publish"));
        assertNotEquals(ApplyTicketServiceImpl.TYPE_API_PUBLISH,
                ApplyTicketServiceImpl.normalizeTicketType("publish"));
    }
}
