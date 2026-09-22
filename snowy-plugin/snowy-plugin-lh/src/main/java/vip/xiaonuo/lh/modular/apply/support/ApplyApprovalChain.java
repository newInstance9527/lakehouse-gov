package vip.xiaonuo.lh.modular.apply.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.Locale;
import java.util.Set;

/**
 * J2 / A8：机密明文多级审批链（Owner → 安全加签）。
 * <p>触发：关联资产敏感级 ∈ 机密类；链路写入 payload，状态机 {@code pending → pending_security → approved}。</p>
 */
public final class ApplyApprovalChain {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_PENDING_SECURITY = "pending_security";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";

    public static final String STEP_OWNER = "owner";
    public static final String STEP_SECURITY = "security";
    public static final String CHAIN_OWNER_SECURITY = "owner_security";
    public static final String CHAIN_OWNER_ONLY = "owner_only";

    private static final Set<String> CONFIDENTIAL = Set.of(
            "机密", "confidential", "secret", "秘密");

    private ApplyApprovalChain() {
    }

    public static boolean isConfidentialSensitivity(String sensitivity) {
        if (StrUtil.isBlank(sensitivity)) {
            return false;
        }
        String s = sensitivity.trim().toLowerCase(Locale.ROOT);
        if (CONFIDENTIAL.contains(s)) {
            return true;
        }
        // 中文原样再比一次（toLowerCase 对中文无影响，但集合含原文）
        return CONFIDENTIAL.contains(sensitivity.trim());
    }

    public static boolean requiresSecurityCosign(String sensitivity) {
        return isConfidentialSensitivity(sensitivity);
    }

    public static boolean requiresSecurityCosign(JSONObject payload) {
        if (payload == null) {
            return false;
        }
        if (Boolean.TRUE.equals(payload.getBool("requiresSecurityCosign"))) {
            return true;
        }
        return isConfidentialSensitivity(payload.getStr("sensitivity"));
    }

    public static boolean requiresSecurityCosignFromPayloadJson(String payloadJson) {
        return requiresSecurityCosign(parsePayload(payloadJson));
    }

    public static String currentStep(String status, JSONObject payload) {
        if (STATUS_PENDING_SECURITY.equals(status)) {
            return STEP_SECURITY;
        }
        if (STATUS_PENDING.equals(status) && requiresSecurityCosign(payload)) {
            return STEP_OWNER;
        }
        if (STATUS_PENDING.equals(status)) {
            return STEP_OWNER;
        }
        return null;
    }

    public static boolean isOpenForDecision(String status) {
        return STATUS_PENDING.equals(status) || STATUS_PENDING_SECURITY.equals(status);
    }

    public static void stampChainOnCreate(JSONObject payload, String sensitivity) {
        if (payload == null) {
            return;
        }
        String sens = StrUtil.trim(sensitivity);
        if (StrUtil.isNotBlank(sens)) {
            payload.set("sensitivity", sens);
        }
        boolean multi = requiresSecurityCosign(sens);
        payload.set("requiresSecurityCosign", multi);
        payload.set("approvalChain", multi ? CHAIN_OWNER_SECURITY : CHAIN_OWNER_ONLY);
        if (multi) {
            payload.set("approvalStep", STEP_OWNER);
        }
    }

    public static JSONObject parsePayload(String raw) {
        return JSONUtil.parseObj(StrUtil.blankToDefault(raw, "{}"));
    }
}
