package vip.xiaonuo.lh.modular.etl.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import vip.xiaonuo.lh.modular.etl.param.IgEtlBackfillParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlDagAddParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlDagEditParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlDeployParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlEngineResolveParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlGraphSaveParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlIdParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlNodeConfigParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlPageParam;
import vip.xiaonuo.lh.modular.etl.param.IgEtlTrialParam;

import java.util.List;
import java.util.Map;

/**
 * ETL 编排服务
 *
 * @author lakehouse
 * @date 2026/9/19
 */
public interface IgEtlService {

    Page<Map<String, Object>> pageDags(IgEtlPageParam param);

    Map<String, Object> detail(String id);

    Map<String, Object> addDag(IgEtlDagAddParam param);

    Map<String, Object> editDag(IgEtlDagEditParam param);

    Map<String, Object> graph(String id);

    Map<String, Object> saveGraph(IgEtlGraphSaveParam param);

    Map<String, Object> updateNodeConfig(IgEtlNodeConfigParam param);

    Map<String, Object> validate(IgEtlIdParam param);

    Map<String, Object> trial(IgEtlTrialParam param);

    Map<String, Object> deploy(IgEtlDeployParam param);

    /** 补数：按 mark_key/mark_value 触发 DS 实例并登记 run */
    Map<String, Object> backfill(IgEtlBackfillParam param);

    Page<Map<String, Object>> pageRuns(String dagId, String ws);

    Map<String, Object> runDetail(String runId);

    /** DS/Worker 回调回写运行态 */
    Map<String, Object> applyRunCallback(Map<String, Object> body);

    Map<String, Object> resolveEngine(IgEtlEngineResolveParam param);

    Map<String, Object> nodeTypes();
}
