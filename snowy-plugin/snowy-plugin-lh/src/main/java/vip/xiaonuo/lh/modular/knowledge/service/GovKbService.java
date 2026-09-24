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
}
