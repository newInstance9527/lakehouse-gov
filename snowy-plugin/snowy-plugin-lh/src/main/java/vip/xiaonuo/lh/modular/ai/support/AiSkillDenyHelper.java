package vip.xiaonuo.lh.modular.ai.support;

import cn.hutool.core.util.StrUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Skill 统一缺权协议：error=forbidden + 操作步骤 + 申请/门户深链。
 * 供 {@link vip.xiaonuo.lh.modular.ai.agent.AiToolRegistry} 与 Agent 提示共用。
 */
public final class AiSkillDenyHelper {

    public static final String ERROR_FORBIDDEN = "forbidden";

    private AiSkillDenyHelper() {
    }

    /** 表读权不足（schema / preview / 查数）。 */
    public static Map<String, Object> denyRead(String assetId, String assetCode, String reason) {
        Map<String, Object> out = base(StrUtil.blankToDefault(reason, "当前账号对该资产无 SELECT 读权限"));
        if (StrUtil.isNotBlank(assetId)) {
            out.put("assetId", assetId);
        }
        if (StrUtil.isNotBlank(assetCode)) {
            out.put("assetCode", assetCode);
        }
        out.put("howTo", List.of(
                "打开申请中心，类型选择「表读权限 / 权限申请」",
                "选中目标资产（或粘贴资产编码），权限选 SELECT，填写事由与时效",
                "提交后等待 Owner / 审批人通过；通过后可在资产目录预览或即席查询",
                "亦可在对话中调用 create_apply_ticket（ticketType=table_read）代填申请单"));
        out.put("applyHint", "申请表读权（SELECT）后即可预览与查数；目录可见不等于可读");
        out.put("applyHref", applyPermHref(assetId, assetCode));
        out.put("portalHref", catalogHref(assetId, assetCode));
        out.put("href", out.get("applyHref"));
        return out;
    }

    /** 数据源使用/清单权限不足。 */
    public static Map<String, Object> denyDs(String dsId, String reason) {
        Map<String, Object> out = base(StrUtil.blankToDefault(reason,
                "无权使用该数据源（须为拥有者或持有 EDIT/MANAGE）"));
        if (StrUtil.isNotBlank(dsId)) {
            out.put("dsId", dsId);
        }
        out.put("howTo", List.of(
                "联系数据源 Owner 将你加入协作者，或到申请中心提交「资源管理权」",
                "申请时 resourceType=datasource，resourceId=数据源 id，privilege 选 EDIT 或 MANAGE",
                "审批通过后可测连、拉表清单；密码不会经 AI 工具返回"));
        out.put("applyHint", "申请数据源资源管理权（EDIT/MANAGE）");
        String href = "/apply?type=manage&resourceType=datasource"
                + (StrUtil.isNotBlank(dsId) ? "&resourceId=" + enc(dsId) : "");
        out.put("applyHref", href);
        out.put("portalHref", "/datasource");
        out.put("href", href);
        return out;
    }

    /** 通用运维/模块操作权不足。 */
    public static Map<String, Object> denyOps(String resourceType, String resourceId, String reason) {
        Map<String, Object> out = base(StrUtil.blankToDefault(reason, "当前账号无该资源的操作权限"));
        if (StrUtil.isNotBlank(resourceType)) {
            out.put("resourceType", resourceType);
        }
        if (StrUtil.isNotBlank(resourceId)) {
            out.put("resourceId", resourceId);
        }
        out.put("howTo", List.of(
                "确认资源类型与 id 是否正确",
                "到申请中心提交「资源管理」类申请（或联系 Owner 授权）",
                "审批通过后重试本操作；破坏性动作（删除/发布/执行删除）不在 AI 工具范围内"));
        out.put("applyHint", "通过申请中心获取资源管理权");
        StringBuilder href = new StringBuilder("/apply?type=manage");
        if (StrUtil.isNotBlank(resourceType)) {
            href.append("&resourceType=").append(enc(resourceType));
        }
        if (StrUtil.isNotBlank(resourceId)) {
            href.append("&resourceId=").append(enc(resourceId));
        }
        out.put("applyHref", href.toString());
        out.put("portalHref", "/apply");
        out.put("href", href.toString());
        return out;
    }

    /** 无法代建申请（资源不可见等）。 */
    public static Map<String, Object> denyApplyCreate(String reason) {
        Map<String, Object> out = base(StrUtil.blankToDefault(reason, "无法代为创建申请单"));
        out.put("howTo", List.of(
                "确认资产/资源在目录中可见，或换用正确的 assetId / resourceId",
                "手动打开申请中心选择类型并提交",
                "若完全不可见，请联系 Owner 先开放目录可见性或登记资产"));
        out.put("applyHint", "请到申请中心手动创建申请");
        out.put("applyHref", "/apply");
        out.put("portalHref", "/apply");
        out.put("href", "/apply");
        return out;
    }

    /** 为列表行附加缺权提示（不改 error，仅提示字段）。 */
    public static void attachNeedApplyHints(Map<String, Object> row, String assetId, String assetCode) {
        if (row == null) {
            return;
        }
        row.put("hint", "目录可见，查数需到申请中心授权");
        row.put("applyHint", "申请表读权（SELECT）");
        row.put("applyHref", applyPermHref(assetId, assetCode));
        row.put("href", row.get("applyHref"));
    }

    public static String applyPermHref(String assetId, String assetCode) {
        StringBuilder sb = new StringBuilder("/apply?type=perm");
        if (StrUtil.isNotBlank(assetId)) {
            sb.append("&assetId=").append(enc(assetId));
        }
        if (StrUtil.isNotBlank(assetCode)) {
            sb.append("&assetCode=").append(enc(assetCode));
        }
        return sb.toString();
    }

    public static String catalogHref(String assetId, String assetCode) {
        if (StrUtil.isNotBlank(assetId)) {
            return "/catalog?asset=" + enc(assetId);
        }
        if (StrUtil.isNotBlank(assetCode)) {
            return "/catalog?q=" + enc(assetCode);
        }
        return "/catalog";
    }

    private static Map<String, Object> base(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", ERROR_FORBIDDEN);
        out.put("message", message);
        return out;
    }

    private static String enc(String raw) {
        return URLEncoder.encode(StrUtil.nullToEmpty(raw), StandardCharsets.UTF_8);
    }

    /** 从 tool JSON 结果解析 applyHref，供 Agent 追加 deeplink。 */
    public static String extractApplyHref(String toolJson) {
        if (StrUtil.isBlank(toolJson) || !toolJson.contains(ERROR_FORBIDDEN)) {
            return null;
        }
        int i = toolJson.indexOf("\"applyHref\"");
        if (i < 0) {
            i = toolJson.indexOf("\"href\"");
        }
        if (i < 0) {
            return "/apply";
        }
        int colon = toolJson.indexOf(':', i);
        int q1 = toolJson.indexOf('"', colon + 1);
        int q2 = q1 < 0 ? -1 : toolJson.indexOf('"', q1 + 1);
        if (q1 < 0 || q2 < 0) {
            return "/apply";
        }
        String href = toolJson.substring(q1 + 1, q2).replace("\\/", "/");
        return StrUtil.blankToDefault(href, "/apply");
    }

    public static List<Map<String, Object>> deeplinkActions(String applyHref, String portalHref) {
        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, Object> apply = new LinkedHashMap<>();
        apply.put("type", "deeplink");
        apply.put("label", "去申请中心");
        apply.put("href", StrUtil.blankToDefault(applyHref, "/apply"));
        actions.add(apply);
        if (StrUtil.isNotBlank(portalHref)) {
            Map<String, Object> portal = new LinkedHashMap<>();
            portal.put("type", "deeplink");
            portal.put("label", "打开相关页面");
            portal.put("href", portalHref);
            actions.add(portal);
        }
        return actions;
    }
}
