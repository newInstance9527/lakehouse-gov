package vip.xiaonuo.lh.core.auth;

import org.junit.jupiter.api.Test;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LhOwnerGuardTest {

    @Test
    void matchesCreateUserAndOwnerFields() {
        SaBaseLoginUser user = new SaBaseLoginUser() {
            @Override
            public Boolean getEnabled() {
                return true;
            }
        };
        user.setId("u100");
        user.setAccount("zhangsan");
        user.setName("张三");

        assertTrue(LhOwnerGuard.isOwner(user, "u100"));
        assertTrue(LhOwnerGuard.isOwner(user, null, "张三"));
        assertTrue(LhOwnerGuard.isOwner(user, null, "张三(交易域)"));
        assertTrue(LhOwnerGuard.isOwner(user, null, "zhangsan"));
        assertFalse(LhOwnerGuard.isOwner(user, "u999", "李四"));
    }
}
