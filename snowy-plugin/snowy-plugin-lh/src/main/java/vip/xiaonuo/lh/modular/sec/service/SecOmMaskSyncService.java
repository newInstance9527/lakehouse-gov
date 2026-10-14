package vip.xiaonuo.lh.modular.sec.service;

import vip.xiaonuo.lh.modular.sec.param.SecOmMaskSyncParam;

import java.util.Map;

/**
 * OM 列敏感标签 → {@code sec_mask_policy_proj} 同步。
 */
public interface SecOmMaskSyncService {

    /** 同步（单资产或按 ws 批处理）。 */
    Map<String, Object> syncFromOm(SecOmMaskSyncParam param);

    /** 表读申请预填：建议列 + 行过滤模板 + mask 提示。 */
    Map<String, Object> tableReadPrefill(String assetId);
}
