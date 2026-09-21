package vip.xiaonuo.lh.modular.metric.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import vip.xiaonuo.lh.modular.metric.param.GovMetricCompileParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricPageParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricQueryParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricTransitionParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricTrialParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricUpsertParam;
import vip.xiaonuo.lh.modular.metric.result.GovMetricVo;

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

    GovMetricVo transition(GovMetricTransitionParam param);

    Map<String, Object> compile(GovMetricCompileParam param);

    Map<String, Object> query(GovMetricQueryParam param);

    Map<String, Object> trial(String metricCode, GovMetricTrialParam param);

    Map<String, Object> lineage(String metricCode, String ws);
}
