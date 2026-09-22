package vip.xiaonuo.lh.modular.ai.support;

import cn.hutool.core.util.StrUtil;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.modular.aimodel.entity.GovAiModel;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * D5：外发（egress）模型安全岗策略。
 * <p>local=内网/自建；egress=云厂商。生产路由绑定外发模型须 {@code egress_approved=true}；sandbox 豁免。</p>
 */
public final class AiEgressPolicy {

    public static final String KIND_LOCAL = "local";
    public static final String KIND_EGRESS = "egress";

    private static final Pattern PRIVATE_HOST = Pattern.compile(
            "(?i)^https?://("
                    + "localhost"
                    + "|127\\.\\d+\\.\\d+\\.\\d+"
                    + "|10\\.\\d+\\.\\d+\\.\\d+"
                    + "|192\\.168\\.\\d+\\.\\d+"
                    + "|172\\.(1[6-9]|2\\d|3[0-1])\\.\\d+\\.\\d+"
                    + ")(:\\d+)?(/.*)?$");

    private AiEgressPolicy() {
    }

    /** 按厂商 / baseUrl / 计价推断 local 或 egress */
    public static String classify(String vendor, String baseUrl, String priceUnit) {
        String v = StrUtil.blankToDefault(vendor, "").trim();
        if ("自建".equals(v) || "local".equalsIgnoreCase(v) || "onprem".equalsIgnoreCase(v)) {
            return KIND_LOCAL;
        }
        if ("free".equalsIgnoreCase(StrUtil.blankToDefault(priceUnit, ""))) {
            return KIND_LOCAL;
        }
        String url = StrUtil.blankToDefault(baseUrl, "").trim();
        if (StrUtil.isNotBlank(url) && PRIVATE_HOST.matcher(url).matches()) {
            return KIND_LOCAL;
        }
        return KIND_EGRESS;
    }

    public static boolean isLocal(String egressKind) {
        return KIND_LOCAL.equalsIgnoreCase(StrUtil.blankToDefault(egressKind, KIND_EGRESS));
    }

    /**
     * 路由保存 / chat 选用：非 sandbox 场景绑定未评估外发模型则拒绝。
     */
    public static void assertAllowed(GovAiModel model, String scene) {
        if (model == null) {
            return;
        }
        String sc = StrUtil.blankToDefault(scene, "").trim().toLowerCase(Locale.ROOT);
        if ("sandbox".equals(sc)) {
            return;
        }
        String kind = StrUtil.blankToDefault(model.getEgressKind(),
                classify(model.getVendor(), model.getBaseUrl(), model.getPriceUnit()));
        if (isLocal(kind)) {
            return;
        }
        if (!Boolean.TRUE.equals(model.getEgressApproved())) {
            throw new CommonException(
                    "外发模型「{}」尚未经安全岗标记（egress_approved）。请在模型管理完成评估后再用于生产路由。",
                    StrUtil.blankToDefault(model.getName(), model.getId()));
        }
    }
}
