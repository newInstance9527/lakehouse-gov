package vip.xiaonuo.lh.modular.metric.service;

import vip.xiaonuo.lh.modular.metric.param.GovMetricDimUpsertParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricQualifierUpsertParam;

import java.util.List;
import java.util.Map;

/**
 * 修饰词 / 公共维度主数据
 */
public interface GovMetricOntologyService {

    List<Map<String, Object>> listQualifiers(String ws, String status, String domain, String q);

    List<Map<String, Object>> qualifierOptions(String ws, String domain, String tableHint);

    Map<String, Object> upsertQualifier(GovMetricQualifierUpsertParam param, boolean create);

    Map<String, Object> disableQualifier(String code, String ws);

    List<Map<String, Object>> listDims(String ws, String status, String domain, String q);

    List<Map<String, Object>> dimOptions(String ws, String domain);

    Map<String, Object> upsertDim(GovMetricDimUpsertParam param, boolean create);

    Map<String, Object> disableDim(String code, String ws);

    /**
     * 从 DIM 层表拉取成员（dim_table 权威）；未绑定返回空列表。
     */
    Map<String, Object> listDimMembers(String code, String ws, String q, Integer limit);

    /**
     * 对 params.dims 中已挂接 DIM 层的维键做成员存在性校验；缺省维键（全国）跳过。
     */
    void assertDimMembers(Map<String, Object> params, List<String> grainKeys, String ws);

    /**
     * 将表单限定项解析为 ver 快照 SQL 片段（code → expr_sql；已是 SQL 则校验安全）。
     */
    List<String> resolveQualifierSnapshots(List<String> items, String ws);
}
