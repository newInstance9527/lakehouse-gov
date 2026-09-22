package vip.xiaonuo.lh.modular.query.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 即席扫描抬额闸门：elevated=true 须持有已审批的 scan_elevate → SCAN_ELEVATE grant。
 */
public final class CpQueryElevateGate {

    /** 申请票种 */
    public static final String TICKET_TYPE = "scan_elevate";
    /** sec_auth_grant.privilege */
    public static final String PRIVILEGE = "SCAN_ELEVATE";
    /** sec_auth_grant.resource_type */
    public static final String RESOURCE_TYPE = "adhoc";
    /** 平台级抬额（一期不分 ws） */
    public static final String RESOURCE_ID = "platform";
    public static final String ERROR_CODE = "ELEVATE_DENIED";
    public static final String APPLY_PATH = "/apply?type=scan_elevate";

    private CpQueryElevateGate() {
    }

    public static String denyMessage() {
        return "扫描抬额（硬顶 50GB）须先经申请中心审批通过；请提交「扫描抬额」申请，审批后再勾选 elevated";
    }

    public static Map<String, Object> deniedPayload() {
        Map<String, Object> m = CpQueryScanGuard.blockedPayload(denyMessage());
        m.put("errorCode", ERROR_CODE);
        m.put("applyHint", true);
        m.put("applyPath", APPLY_PATH);
        m.put("elevated", false);
        m.put("elevatedAllowed", false);
        m.put("scanLimitBytes", CpQueryScanGuard.ADHOC_DEFAULT_SCAN_BYTES);
        return m;
    }
}
