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

    GovAiModelVo create(GovAiModelUpsertParam param);

    GovAiModelVo update(String id, GovAiModelUpsertParam param);

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

    /** 累加当日用量（chat / embed 调用后） */
    void recordUsage(String ws, String modelId, long promptTokens, long completionTokens);
}
