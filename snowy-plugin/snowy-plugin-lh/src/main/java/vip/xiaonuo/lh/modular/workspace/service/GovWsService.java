package vip.xiaonuo.lh.modular.workspace.service;

import vip.xiaonuo.lh.modular.workspace.param.GovWsCreateParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsCurrentParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsMemberItemParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsMembersReplaceParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsQuotaUpdateParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsTagAddParam;
import vip.xiaonuo.lh.modular.workspace.param.GovWsTagsReplaceParam;
import vip.xiaonuo.lh.modular.workspace.result.GovWsMemberVo;
import vip.xiaonuo.lh.modular.workspace.result.GovWsQuotaVo;
import vip.xiaonuo.lh.modular.workspace.result.GovWsVo;

import java.util.List;
import java.util.Map;

public interface GovWsService {

    Map<String, Object> overview();

    List<GovWsVo> listSpaces();

    GovWsVo detail(String wsCode);

    GovWsVo create(GovWsCreateParam param);

    /**
     * 归档空间（软删 status=archived；与 delete 同语义）。
     */
    GovWsVo archive(String wsCode);

    /**
     * 删除空间：软删 status=archived；禁止 default / 最后一个活跃空间；
     * 用户偏好指向该空间时回落 default。
     */
    GovWsVo delete(String wsCode);

    /** 同步 Git 远程：空/内网/平台托管 → 确保 per-ws 仓并回写；自定义公网 → 保留 */
    GovWsVo syncGitRemote(String wsCode);

    /**
     * 追加空间展示标签（Owner/超管；写入 {@code gov_ws.tags_json}）。
     */
    GovWsVo addTag(String wsCode, GovWsTagAddParam param);

    /**
     * 按文案移除一条标签（Owner/超管）。
     */
    GovWsVo removeTag(String wsCode, String text);

    /**
     * 全量替换标签（Owner/超管；null/空=清空）。
     */
    GovWsVo replaceTags(String wsCode, GovWsTagsReplaceParam param);

    List<GovWsMemberVo> listMembers(String wsCode);

    List<GovWsMemberVo> replaceMembers(String wsCode, GovWsMembersReplaceParam param);

    GovWsMemberVo addMember(String wsCode, GovWsMemberItemParam param);

    void removeMember(String wsCode, String memberId);

    GovWsQuotaVo quota(String wsCode);

    List<GovWsQuotaVo> listQuotas();

    /**
     * 更新配额（空间 Owner / 超管）。字段 null = 不改；AI 传 0 = 不限。
     */
    GovWsQuotaVo updateQuota(String wsCode, GovWsQuotaUpdateParam param);

    Map<String, Object> getCurrent();

    Map<String, Object> setCurrent(GovWsCurrentParam param);

    /**
     * 配额水位告警：任一维度 ≥60% 提示、≥80% 告警（与 §3.3 对齐）。
     */
    List<Map<String, Object>> listQuotaAlerts();
}
