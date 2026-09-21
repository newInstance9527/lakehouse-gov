package vip.xiaonuo.lh.modular.aimodel.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import vip.xiaonuo.lh.modular.aimodel.param.GovAiModelEnableParam;
import vip.xiaonuo.lh.modular.aimodel.param.GovAiModelPageParam;
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

    Map<String, Object> test(String id);

    GovAiModelVo enable(String id, GovAiModelEnableParam param);

    List<GovAiRouteVo> listRoutes(String ws);

    List<GovAiRouteVo> saveRoutes(List<GovAiRouteUpsertParam> params);

    Map<String, Object> usage(String range, String group, String ws);

    /** 累加当日用量（chat / embed 调用后） */
    void recordUsage(String ws, String modelId, long promptTokens, long completionTokens);
}
