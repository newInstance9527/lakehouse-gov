package vip.xiaonuo.lh.modular.bi.service;

import vip.xiaonuo.lh.modular.bi.param.GovBiDashboardPinParam;
import vip.xiaonuo.lh.modular.bi.param.GovBiEmbedParam;
import vip.xiaonuo.lh.modular.bi.param.GovBiOpenDatasetParam;
import vip.xiaonuo.lh.modular.bi.param.GovBiPublishMetricParam;
import vip.xiaonuo.lh.modular.datasource.param.LhDatasourceIdParam;

import java.util.List;
import java.util.Map;

/**
 * BI 门户：Superset 投影、Guest Token 内嵌看数、OIDC 做表（无 AUTH_DB 裸深链降级）。
 */
public interface GovBiService {

    Map<String, Object> status();

    /** Trino query_gateway 清单 + binding 态 */
    List<Map<String, Object>> projectionStatus();

    /** 投影到 Superset Database（仅 Trino） */
    Map<String, Object> projectDatasources(List<LhDatasourceIdParam> ids);

    Map<String, Object> publishMetricDataset(GovBiPublishMetricParam param);

    List<Map<String, Object>> listDatasets(String ws);

    /** 从 Superset 拉取 Dashboard 写入 gov_bi_dashboard */
    Map<String, Object> syncDashboards(String ws);

    /** 门户已回写看板（pinned 优先） */
    List<Map<String, Object>> listDashboards(String ws, Boolean pinnedOnly);

    Map<String, Object> pinDashboard(GovBiDashboardPinParam param);

    /**
     * 按 filters.dims 重编译并刷新虚拟数据集 SQL；看数请再 {@link #embedSession}。
     */
    Map<String, Object> openDatasetWithDims(GovBiOpenDatasetParam param);

    /** Guest Token 内嵌会话（看数唯一入口） */
    Map<String, Object> embedSession(GovBiEmbedParam param);

    /** OIDC 做表入口；未启用则失败（不降级 AUTH_DB） */
    Map<String, Object> authoringEntry(String kind, String id);
}
