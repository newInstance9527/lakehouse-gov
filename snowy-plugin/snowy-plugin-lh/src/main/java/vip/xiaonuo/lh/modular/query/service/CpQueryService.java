package vip.xiaonuo.lh.modular.query.service;

import vip.xiaonuo.lh.core.engine.TrinoClient;
import vip.xiaonuo.lh.modular.query.param.CpQueryCancelParam;
import vip.xiaonuo.lh.modular.query.param.CpQueryDatasetSaveParam;
import vip.xiaonuo.lh.modular.query.param.CpQueryExecParam;
import vip.xiaonuo.lh.modular.query.param.CpQueryExportParam;
import vip.xiaonuo.lh.modular.query.param.CpQueryHistoryParam;

import java.util.List;
import java.util.Map;

/**
 * 即席查询（Trino + 审计）
 */
public interface CpQueryService {

    Map<String, Object> exec(CpQueryExecParam param);

    Map<String, Object> exec(CpQueryExecParam param, TrinoClient.ProgressListener onProgress);

    Map<String, Object> cancel(CpQueryCancelParam param);

    List<Map<String, Object>> history(CpQueryHistoryParam param);

    /**
     * 门户资产目录。{@code ws} 缺省 default。空列表表示没有已登记的表，不是降级。
     */
    List<Map<String, Object>> schemaTree(String ws);

    /**
     * 懒加载表列。优先 {@code assetId}；{@code fqn} 为平台 {@code layer.domain.assetCode}。
     * 列来自门户快照，仅漂移或快照为空时按指针回源一次。
     */
    Map<String, Object> tableColumns(String assetId, String fqn);

    Map<String, Object> exportAudit(CpQueryExportParam param);

    Map<String, Object> govOverview();

    Map<String, Object> explain(CpQueryExecParam param);

    Map<String, Object> detectParams(String sql);

    Map<String, Object> saveDataset(CpQueryDatasetSaveParam param);

    List<Map<String, Object>> listDatasets(String ws, Integer limit);

    /** 即席查询面快照：白名单 ∩ SHOW CATALOGS */
    Map<String, Object> querySurface();

    /** Grav→Trino catalog 映射列表 */
    List<Map<String, Object>> listCatalogMaps(String ws);

    /** 联邦源开通：写入/更新映射并返回 checklist */
    Map<String, Object> upsertCatalogMap(Map<String, Object> body);
}
