package vip.xiaonuo.lh.modular.apply.support;

import org.junit.jupiter.api.Test;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.lh.core.auth.LhOwnerGuard;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G1 候选人规则纯逻辑：资产 Owner 匹配复用 LhOwnerGuard（与 ApplyApprovalCandidateService 一致）。
 */
class ApplyApprovalCandidateServiceTest {

    @Test
    void assetOwnerMatchesTechBizOrCreateUser() {
        SaBaseLoginUser user = new SaBaseLoginUser() {
            @Override
            public Boolean getEnabled() {
                return true;
            }
        };
        user.setId("u200");
        user.setAccount("lisi");
        user.setName("李四");

        GovAsset a = new GovAsset();
        a.setCreateUser("other");
        a.setTechOwner("李四(交易)");
        a.setBizOwner("王五");
        assertTrue(LhOwnerGuard.isOwner(user, a.getCreateUser(), a.getTechOwner(), a.getBizOwner()));

        a.setTechOwner("王五");
        a.setBizOwner("lisi");
        assertTrue(LhOwnerGuard.isOwner(user, a.getCreateUser(), a.getTechOwner(), a.getBizOwner()));

        a.setBizOwner("王五");
        a.setCreateUser("u200");
        assertTrue(LhOwnerGuard.isOwner(user, a.getCreateUser(), a.getTechOwner(), a.getBizOwner()));

        a.setCreateUser("u999");
        assertFalse(LhOwnerGuard.isOwner(user, a.getCreateUser(), a.getTechOwner(), a.getBizOwner()));
    }
}
