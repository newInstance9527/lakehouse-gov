package vip.xiaonuo.lh.modular.observability.service;

import java.util.Map;

/**
 * 可观测用量 / 成本聚合（§24.3 FinOps · group=ws）
 */
public interface LhObsCostService {

    /**
     * 按 {@code group} 聚合存储 / 计算扫描 / AI 用量与估算成本。
     * <p>一期默认且验收口径：{@code group=ws}。
     */
    Map<String, Object> costs(String range, String group, String ws);
}
