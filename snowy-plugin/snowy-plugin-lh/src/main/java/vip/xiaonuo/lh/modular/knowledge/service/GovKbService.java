package vip.xiaonuo.lh.modular.knowledge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

    /** Milvus 向量探针（D2）；未启用/不可达 → mode=keyword */
    Map<String, Object> vectorProbe();

    Page<GovKbEntryVo> page(GovKbPageParam param);

    GovKbEntryVo create(GovKbUpsertParam param);

    GovKbEntryVo detail(String id);

    GovKbEntryVo update(String id, GovKbUpsertParam param);

    void delete(String id);

    Map<String, Object> rebuild(String entryId);

    List<Map<String, Object>> search(GovKbSearchParam param);

    Map<String, Object> stats(String ws);

    Map<String, Object> ackCitation(GovKbCiteAckParam param);
}
