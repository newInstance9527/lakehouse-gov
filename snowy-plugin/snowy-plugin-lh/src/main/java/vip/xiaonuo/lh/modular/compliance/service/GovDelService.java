package vip.xiaonuo.lh.modular.compliance.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import vip.xiaonuo.lh.modular.compliance.param.GovDelActionParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelBackfillGateParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelEvidenceDownloadParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelExportGateParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelHoldParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelPlanEditParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelRequestCreateParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelRequestPageParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelRestrictParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelRevealParam;
import vip.xiaonuo.lh.modular.compliance.param.GovDelSubjectMapUpsertParam;
import vip.xiaonuo.lh.modular.compliance.result.GovDelRequestVo;
import vip.xiaonuo.lh.modular.compliance.result.GovDelSubjectMapVo;

import java.util.List;
import java.util.Map;

/**
 * 合规删除（doc/合规删除.md）。状态机：
 * assessing → pending_approval → scheduled → executing → verifying → done → archived → destroyed；
 * 旁路：restricted（限制处理）、on_hold（法务冻结）、rejected、partial_failed。
 */
public interface GovDelService {

    Map<String, Object> summary(String ws);

    Page<GovDelRequestVo> pageRequests(GovDelRequestPageParam param);

    GovDelRequestVo detail(String reqIdOrNo);

    GovDelRequestVo create(GovDelRequestCreateParam param);

    /**
     * 二次授权查看主体 ID 明文：须回填 req_no + 用途；从 Vault 读取并写审计流水。
     * 响应含明文，不得写入列表/详情默认字段。
     */
    Map<String, Object> revealSubjectPlain(GovDelRevealParam param);

    /** 展开主体索引 + 血缘锚点，生成/刷新删除计划。 */
    GovDelRequestVo assess(GovDelActionParam param);

    /** 计划编辑：补充载体 / 排除载体（排除必填理由）。 */
    GovDelRequestVo editPlan(GovDelPlanEditParam param);

    /** 试算：命中行数与影响面，不落删除动作。 */
    Map<String, Object> dryRun(GovDelActionParam param);

    /** 提交审批：写 apply_ticket(compliance_delete)，回填 ticket_no。 */
    GovDelRequestVo submit(GovDelActionParam param);

    /** 排期：审批通过后设置执行窗口。 */
    GovDelRequestVo schedule(GovDelActionParam param);

    /** 执行：按载体顺序推进；Iceberg 三件套复用 /lh/lifecycle/*。 */
    GovDelRequestVo execute(GovDelActionParam param);

    /** 验证：残留行反查 + 时间旅行不可读，回写 rows_verified。 */
    GovDelRequestVo verify(GovDelActionParam param);

    /** 限制处理（个保法 §47）：撤 ACL + 强制脱敏 + 禁出湖/API/训练 + 到期复查。 */
    GovDelRequestVo restrict(GovDelRestrictParam param);

    GovDelRequestVo hold(GovDelHoldParam param);

    GovDelRequestVo releaseHold(GovDelHoldParam param);

    GovDelRequestVo abort(GovDelActionParam param);

    /**
     * 证据包：组装清单 + ZIP 落对象存储（WORM 保留期）；返回 checklist / sha256 / objectPath。
     * MinIO 不可达时 soft-fail：仍回 checklist，{@code stored=false}。
     */
    Map<String, Object> evidence(String reqIdOrNo);

    /**
     * 二次授权下载证据包 ZIP：须回填 req_no + 用途；从对象存储读取并写审计。
     * 响应含 contentBase64，不得写入列表默认字段。
     */
    Map<String, Object> downloadEvidence(GovDelEvidenceDownloadParam param);

    List<GovDelSubjectMapVo> listSubjectMaps(String ws, String subjectType, String carrier);

    GovDelSubjectMapVo upsertSubjectMap(GovDelSubjectMapUpsertParam param);

    /** 覆盖率：高敏资产中已登记主体索引的比例 + 缺口清单。 */
    Map<String, Object> coverage(String ws);

    /** E7：补数预检——表×分区是否命中已执行删除。 */
    Map<String, Object> backfillGateCheck(GovDelBackfillGateParam param);

    /** E7：出湖预检——源表是否命中 restricted。 */
    Map<String, Object> exportGateCheck(GovDelExportGateParam param);
}
