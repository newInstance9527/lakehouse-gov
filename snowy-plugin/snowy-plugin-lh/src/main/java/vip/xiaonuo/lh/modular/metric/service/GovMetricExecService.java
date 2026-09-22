package vip.xiaonuo.lh.modular.metric.service;

import vip.xiaonuo.lh.modular.metric.param.GovMetricQueryParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricTrialParam;

import java.util.Map;

/**
 * 指标引擎执行（M1 Trino；M2 Redis 缓存 + JOB 采样）
 */
public interface GovMetricExecService {

    /** 正式查询：仅 active；走 metric grant；可命中 Redis 短缓存 */
    Map<String, Object> query(GovMetricQueryParam param);

    /** 试跑：允许草稿/评审；截断更严；不写缓存 */
    Map<String, Object> trial(String metricCode, GovMetricTrialParam param);

    /**
     * 日波动采样：JOB 身份跑 Trino，跳过门户 grant（系统作业）。
     * 不写 query 缓存。
     */
    Map<String, Object> sampleQuery(String metricCode, String ws, Map<String, Object> params, int maxRows);
}