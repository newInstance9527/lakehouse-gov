package vip.xiaonuo.lh.modular.lineage.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import vip.xiaonuo.lh.modular.lineage.param.GovLineageEdgeUpsertParam;
import vip.xiaonuo.lh.modular.lineage.param.GovLineageIdParam;
import vip.xiaonuo.lh.modular.lineage.param.GovLineagePageParam;
import vip.xiaonuo.lh.modular.lineage.result.GovLineageEdgeVo;

import java.util.Map;

/**
 * 字段血缘
 *
 * @author lakehouse
 * @date 2026/9/19
 */
public interface GovLineageService {

    Map<String, Object> graph(String node, String focus, String omFqn, Integer upDepth, Integer downDepth, String ws);

    Map<String, Object> impact(String node, String focus, String omFqn, Integer upDepth, Integer downDepth, String ws);

    Page<GovLineageEdgeVo> pageFields(GovLineagePageParam param);

    GovLineageEdgeVo upsertField(GovLineageEdgeUpsertParam param);

    void deleteField(GovLineageIdParam param);

    /** 软删某 ETL 作业下全部活动字段边（发布/同步前清旧边） */
    int retireEdgesByEtlJob(String ws, String etlJobId);

    Map<String, Object> syncFields(String ws, String etlJobId);

    Map<String, Object> syncStatus(String ws);

    Map<String, Object> changeEval(String table, String field, String toType, String ws);

    /** 评估单审批：decision=approved|rejected */
    Map<String, Object> decideChangeEval(String id, String decision, String ws);

    Map<String, Object> blockDdl(String table, String field, String reason, String ws);

    /**
     * 发布门禁：读 {@code gov_lineage_ddl_block} / 待审评估单；命中 active 阻断则 fail。
     */
    Map<String, Object> assessPublishGate(String ws, String tableHint);

    /** 血缘解析是否已入库（门禁 gate 2）：表相关字段边计数。 */
    Map<String, Object> assessLineageIngestGate(String ws, String tableHint);

    Map<String, Object> marquezNamespaces();
}
