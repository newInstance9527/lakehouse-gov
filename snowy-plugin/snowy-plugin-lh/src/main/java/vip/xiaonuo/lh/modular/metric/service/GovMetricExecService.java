package vip.xiaonuo.lh.modular.metric.service;

import vip.xiaonuo.lh.modular.metric.param.GovMetricQueryParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricTrialParam;

import java.util.Map;

/**
 * 指标引擎执行（M1：Trino）
 */
public interface GovMetricExecService {

    /** 正式查询：仅 active */
    Map<String, Object> query(GovMetricQueryParam param);

    /** 试跑：允许草稿/评审；截断更严 */
    Map<String, Object> trial(String metricCode, GovMetricTrialParam param);
}
