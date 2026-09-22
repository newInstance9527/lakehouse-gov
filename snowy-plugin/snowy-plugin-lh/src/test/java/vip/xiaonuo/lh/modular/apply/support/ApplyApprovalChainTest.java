package vip.xiaonuo.lh.modular.apply.support;

import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyApprovalChainTest {

    @Test
    void confidentialSensitivityTriggersCosign() {
        assertTrue(ApplyApprovalChain.isConfidentialSensitivity("机密"));
        assertTrue(ApplyApprovalChain.isConfidentialSensitivity("confidential"));
        assertTrue(ApplyApprovalChain.isConfidentialSensitivity("秘密"));
        assertTrue(ApplyApprovalChain.requiresSecurityCosign("机密"));
        assertFalse(ApplyApprovalChain.requiresSecurityCosign("内部"));
        assertFalse(ApplyApprovalChain.requiresSecurityCosign((String) null));
        assertFalse(ApplyApprovalChain.requiresSecurityCosign((JSONObject) null));
    }

    @Test
    void stampChainOnCreateMarksOwnerSecurity() {
        JSONObject p = new JSONObject();
        ApplyApprovalChain.stampChainOnCreate(p, "机密");
        assertTrue(p.getBool("requiresSecurityCosign"));
        assertEquals(ApplyApprovalChain.CHAIN_OWNER_SECURITY, p.getStr("approvalChain"));
        assertEquals(ApplyApprovalChain.STEP_OWNER, p.getStr("approvalStep"));
        assertEquals("机密", p.getStr("sensitivity"));

        JSONObject plain = new JSONObject();
        ApplyApprovalChain.stampChainOnCreate(plain, "公开");
        assertFalse(Boolean.TRUE.equals(plain.getBool("requiresSecurityCosign")));
        assertEquals(ApplyApprovalChain.CHAIN_OWNER_ONLY, plain.getStr("approvalChain"));
    }

    @Test
    void currentStepFollowsStatus() {
        JSONObject multi = new JSONObject();
        multi.set("requiresSecurityCosign", true);
        assertEquals(ApplyApprovalChain.STEP_OWNER,
                ApplyApprovalChain.currentStep(ApplyApprovalChain.STATUS_PENDING, multi));
        assertEquals(ApplyApprovalChain.STEP_SECURITY,
                ApplyApprovalChain.currentStep(ApplyApprovalChain.STATUS_PENDING_SECURITY, multi));
        assertTrue(ApplyApprovalChain.isOpenForDecision(ApplyApprovalChain.STATUS_PENDING_SECURITY));
        assertFalse(ApplyApprovalChain.isOpenForDecision(ApplyApprovalChain.STATUS_APPROVED));
    }
}
