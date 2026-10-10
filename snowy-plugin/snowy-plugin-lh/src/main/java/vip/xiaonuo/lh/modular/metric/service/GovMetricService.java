package vip.xiaonuo.lh.modular.metric.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import vip.xiaonuo.lh.modular.metric.param.GovMetricBoardQueryParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricCompileParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricMaterializeParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricPageParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricQueryParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricTransitionParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricTrialParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricUpsertParam;
import vip.xiaonuo.lh.modular.metric.result.GovMetricVo;

import java.util.List;
import java.util.Map;

/**
 * 指标中心 Service（CRUD + 状态机 + 编译；执行见 {@link GovMetricExecService}）
 */
public interface GovMetricService {

    Map<String, Object> overview(String ws);

    Page<GovMetricVo> page(GovMetricPageParam param);

    GovMetricVo detail(String metricCode, String ws);

    GovMetricVo create(GovMetricUpsertParam param);

    GovMetricVo update(GovMetricUpsertParam param);

    /** 软删除：本人或超管；有下游依赖时拒绝 */
    Map<String, Object> delete(String metricCode, String ws);

    GovMetricVo transition(GovMetricTransitionParam param);

    Map<String, Object> compile(GovMetricCompileParam param);

    Map<String, Object> query(GovMetricQueryParam param);

    Map<String, Object> trial(String metricCode, GovMetricTrialParam param);

    Map<String, Object> lineage(String metricCode, String ws);

    /**
     * 指标试跑/查询执行审计（读 cp_query_exec；不含历史结果行集）。
     * @param limit 默认 20，硬顶 100
     */
    Map<String, Object> listExecs(String metricCode, String ws, Integer limit);

    /** 日波动摘要（读 gov_metric_sample） */
    Map<String, Object> anomaly(String metricCode, String ws, Integer days);

    /** 手动触发日波动采样（运维） */
    Map<String, Object> sampleRerun(String ws);

    /** 就绪指标卡片：未对账 / 分区失败 → ready=false（数据未就绪） */
    Map<String, Object> board(String ws);

    /**
     * 就绪指标按维查询：filters.dims ≡ params.dims；仅对 ready 卡执行 query。
     */
    Map<String, Object> boardQuery(GovMetricBoardQueryParam param);

    /** 物化登记列表 */
    List<Map<String, Object>> listMaterialize(String metricCode, String ws);

    /** 登记物化目标并可选启动 DS 作业模板 */
    Map<String, Object> materialize(String metricCode, GovMetricMaterializeParam param);
}
