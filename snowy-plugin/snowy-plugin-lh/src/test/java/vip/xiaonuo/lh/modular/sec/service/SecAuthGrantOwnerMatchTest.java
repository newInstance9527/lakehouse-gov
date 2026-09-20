package vip.xiaonuo.lh.modular.sec.service;

import org.junit.jupiter.api.Test;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.sec.service.impl.SecAuthGrantServiceImpl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecAuthGrantOwnerMatchTest {

    @Test
    void ownerMatchesCreateUserAndTechOwner() {
        SaBaseLoginUser user = new SaBaseLoginUser() {
            @Override
            public Boolean getEnabled() {
                return true;
            }
        };
        user.setId("u100");
        user.setAccount("zhangsan");
        user.setName("张三");

        GovAsset byCreate = new GovAsset();
        byCreate.setCreateUser("u100");
        assertTrue(SecAuthGrantServiceImpl.isAssetOwner(byCreate, user));

        GovAsset byTech = new GovAsset();
        byTech.setTechOwner("张三");
        assertTrue(SecAuthGrantServiceImpl.isAssetOwner(byTech, user));

        GovAsset byTechParen = new GovAsset();
        byTechParen.setTechOwner("张三(交易域)");
        assertTrue(SecAuthGrantServiceImpl.isAssetOwner(byTechParen, user));

        GovAsset byAccount = new GovAsset();
        byAccount.setBizOwner("zhangsan");
        assertTrue(SecAuthGrantServiceImpl.isAssetOwner(byAccount, user));

        GovAsset stranger = new GovAsset();
        stranger.setTechOwner("李四");
        stranger.setCreateUser("u999");
        assertFalse(SecAuthGrantServiceImpl.isAssetOwner(stranger, user));
    }
}
