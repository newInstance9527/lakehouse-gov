package vip.xiaonuo.lh.modular.sec.enums;

import cn.hutool.core.util.StrUtil;
import vip.xiaonuo.common.exception.CommonException;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 操作权限资源类型注册表：启用 asset/datasource/etl/metric/script/dataservice；其余 reserved 供扩展。
 */
public enum LhOpsResourceTypeEnum {

    ASSET("asset", "资产", true),
    DATASOURCE("datasource", "数据源", true),
    ETL("etl", "ETL任务", true),
    QUALITY("quality", "质量", false),
    SCRIPT("script", "开发脚本", true),
    STANDARD("standard", "数据标准", false),
    METRIC("metric", "指标", true),
    DATASERVICE("dataservice", "数据服务", true),
    EXPORT("export", "出湖作业", false);

    private final String value;
    private final String label;
    private final boolean enabled;

    LhOpsResourceTypeEnum(String value, String label, boolean enabled) {
        this.value = value;
        this.label = label;
        this.enabled = enabled;
    }

    public String getValue() {
        return value;
    }

    public String getLabel() {
        return label;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public static Optional<LhOpsResourceTypeEnum> of(String raw) {
        if (StrUtil.isBlank(raw)) {
            return Optional.empty();
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(e -> e.value.equals(v)).findFirst();
    }

    /** 创建/审批：必须是已启用类型 */
    public static LhOpsResourceTypeEnum requireEnabled(String raw) {
        LhOpsResourceTypeEnum e = of(raw)
                .orElseThrow(() -> new CommonException("不支持的操作权限资源类型: {}", raw));
        if (!e.enabled) {
            throw new CommonException("资源类型「{}」尚未开放操作权限申请", e.label);
        }
        return e;
    }
}
