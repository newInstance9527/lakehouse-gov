package vip.xiaonuo.lh.modular.knowledge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.web.multipart.MultipartFile;
import vip.xiaonuo.lh.modular.knowledge.param.GovKbCiteAckParam;
import vip.xiaonuo.lh.modular.knowledge.param.GovKbPageParam;
import vip.xiaonuo.lh.modular.knowledge.param.GovKbSearchParam;
import vip.xiaonuo.lh.modular.knowledge.param.GovKbUpsertParam;
import vip.xiaonuo.lh.modular.knowledge.result.GovKbEntryVo;

import java.util.List;
import java.util.Map;

/**
 * 知识库
 */
public interface GovKbService {

    Map<String, Object> overview(String ws);

    /** KPI；scope=platform 时忽略 ws，统计公用知识库 */
    Map<String, Object> overview(String ws, String scope);

    /** Milvus 向量探针（D2）；未启用/不可达 → mode=keyword */
    Map<String, Object> vectorProbe();

    Page<GovKbEntryVo> page(GovKbPageParam param);

    GovKbEntryVo create(GovKbUpsertParam param);

    /**
     * 上传文档：真实解析正文 → 写入 body → 分片索引（新建或替换已有条目正文）。
     *
     * @param entryId 非空则更新该条目；空则新建
     */
    GovKbEntryVo upload(MultipartFile file, GovKbUpsertParam meta, String entryId);

    GovKbEntryVo detail(String id);

    GovKbEntryVo update(String id, GovKbUpsertParam param);

    void delete(String id);

    Map<String, Object> rebuild(String entryId);

    List<Map<String, Object>> search(GovKbSearchParam param);

    Map<String, Object> stats(String ws);

    Map<String, Object> ackCitation(GovKbCiteAckParam param);

    /** 回写最后访问时间（检索命中 / 引用） */
    void touchLastAccess(List<String> entryIds);

    /** 纪要质量打分（启发式；人工分不覆盖） */
    void scoreMinutesEntry(String entryId);

    /**
     * 纪要老化巡检：低分且长期未访问 → archive（去向量）→ 再过窗口 soft-delete。
     */
    Map<String, Object> memoryPatrol();

    /**
     * 业务术语只读图谱（节点=术语/指标/标准/资产/同义词；边=类型化）。
     *
     * @param rootEntryId 可选根术语；空则返回全量术语子图
     * @param depth       1–3
     */
    Map<String, Object> glossaryGraph(String rootEntryId, Integer depth);

    /** 术语覆盖率 / 断链率等治理 KPI */
    Map<String, Object> glossaryStats();

    /** 从现有 term 条目 refs_json 全量重建物化边 */
    Map<String, Object> rebuildGlossaryEdges();

    /**
     * 幂等为指标补业务术语（cat=term + implements_metric 边）。
     *
     * @param metricCode 指标编码
     */
    Map<String, Object> ensureTermForMetric(String metricCode);

    /** 为全部未覆盖的已启用指标批量 ensure-term（上限 50） */
    Map<String, Object> ensureTermsForUncovered();
}
