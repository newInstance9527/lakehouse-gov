package vip.xiaonuo.lh.modular.aimodel.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import vip.xiaonuo.lh.modular.aimodel.param.GovAiModelEnableParam;
import vip.xiaonuo.lh.modular.aimodel.param.GovAiModelPageParam;
import vip.xiaonuo.lh.modular.aimodel.param.GovAiModelRotateParam;
import vip.xiaonuo.lh.modular.aimodel.param.GovAiModelUpsertParam;
import vip.xiaonuo.lh.modular.aimodel.param.GovAiRouteUpsertParam;
import vip.xiaonuo.lh.modular.aimodel.result.GovAiModelVo;
import vip.xiaonuo.lh.modular.aimodel.result.GovAiRouteVo;

import java.util.List;
import java.util.Map;

/**
 * AI 模型管理
 */
public interface GovAiModelService {

    Map<String, Object> overview(String ws);

    Page<GovAiModelVo> page(GovAiModelPageParam param);

    /** 模型详情（Key 仅脱敏） */
    GovAiModelVo detail(String id);

    GovAiModelVo create(GovAiModelUpsertParam param);

    GovAiModelVo update(String id, GovAiModelUpsertParam param);

    /** 软删模型；尽力停用 LiteLLM 别名 */
    void delete(String id);

    /** 轮换 Key → Vault；列表仅脱敏 */
    GovAiModelVo rotate(String id, GovAiModelRotateParam param);

    Map<String, Object> test(String id);

    GovAiModelVo enable(String id, GovAiModelEnableParam param);

    /**
     * 批量巡检：LiteLLM 健康 + Vault Key 存在 + 过期预警 + 启用模型连通。
     *
     * @param ws 可选；空则全量启用模型
     */
    Map<String, Object> patrol(String ws);

    /**
     * LiteLLM 同步探针（D3）：enabled / reachable / syncCapable / mode。
     * 现网未起网关时 mode=skipped|degraded，不阻断门户。
     */
    Map<String, Object> gatewayProbe();

    List<GovAiRouteVo> listRoutes(String ws);

    List<GovAiRouteVo> saveRoutes(List<GovAiRouteUpsertParam> params);

    Map<String, Object> usage(String range, String group, String ws);

    /** 累加当日用量（chat / embed 调用后）；latencyMs 可空，有值则计入平均响应 */
    void recordUsage(String ws, String modelId, long promptTokens, long completionTokens, Integer latencyMs);

    /** @see #recordUsage(String, String, long, long, Integer) */
    default void recordUsage(String ws, String modelId, long promptTokens, long completionTokens) {
        recordUsage(ws, modelId, promptTokens, completionTokens, null);
    }

    /**
     * AI 用量硬门禁（须在调用 LiteLLM / OpenAI 兼容上游之前）：
     * <ol>
     *   <li>工作空间日配额 {@code gov_ws_quota.ai_*}</li>
     *   <li>所选模型总限额 {@code gov_ai_model.token_quota}/{@code cost_quota}
     *       （按 modelId 汇总 {@code gov_ai_usage_daily} 全量）</li>
     * </ol>
     * null/0 = 该维不限。
     */
    void assertDailyQuota(String ws, String modelId);

    /** @see #assertDailyQuota(String, String) */
    default void assertDailyQuota(String ws) {
        assertDailyQuota(ws, null);
    }
}
