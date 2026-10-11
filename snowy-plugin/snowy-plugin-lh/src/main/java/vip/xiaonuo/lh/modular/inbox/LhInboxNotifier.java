package vip.xiaonuo.lh.modular.inbox;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.dev.api.DevMessageApi;
import vip.xiaonuo.lh.modular.apply.entity.ApplyTicket;
import vip.xiaonuo.lh.modular.workspace.entity.GovWsMember;
import vip.xiaonuo.lh.modular.workspace.mapper.GovWsMemberMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 湖仓业务 → Snowy 站内信（{@link DevMessageApi}）。
 * <p>发送失败软降级，不阻断主流程。分类固定 BIZ。</p>
 */
@Slf4j
@Component
public class LhInboxNotifier {

    private static final String CATEGORY_BIZ = "BIZ";
    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String STATUS_ACTIVE = "active";

    @Resource
    private DevMessageApi devMessageApi;
    @Resource
    private GovWsMemberMapper memberMapper;

    /** 通用投递：去重、过滤空 id。 */
    public boolean sendBiz(List<String> receiverIds, String subject, String content) {
        List<String> receivers = normalizeReceivers(receiverIds);
        if (receivers.isEmpty() || StrUtil.isBlank(subject)) {
            return false;
        }
        try {
            devMessageApi.sendMessageWithContent(
                    receivers,
                    CATEGORY_BIZ,
                    subject.trim(),
                    StrUtil.blankToDefault(content, subject));
            return true;
        } catch (Exception e) {
            log.warn("站内信投递失败 subject={} receivers={}: {}",
                    subject, receivers.size(), e.getMessage());
            return false;
        }
    }

    /** 申请单审批通过 / 驳回 → 通知申请人。 */
    public void notifyApplicantDecision(ApplyTicket ticket, String decision) {
        if (ticket == null || StrUtil.isBlank(ticket.getApplicant())) {
            return;
        }
        boolean approved = "approved".equalsIgnoreCase(decision);
        String no = StrUtil.blankToDefault(ticket.getTicketNo(), ticket.getId());
        String title = StrUtil.blankToDefault(ticket.getTitle(), no);
        String subject = approved
                ? "申请已通过 · " + no
                : "申请已驳回 · " + no;
        String remark = StrUtil.blankToDefault(ticket.getRemark(), "");
        String content = approved
                ? "您的申请「" + title + "」已通过。"
                + (StrUtil.isNotBlank(remark) ? "\n审批意见：" + remark : "")
                + "\n请到门户「申请中心」查看：/apply"
                : "您的申请「" + title + "」已被驳回。"
                + (StrUtil.isNotBlank(remark) ? "\n驳回原因：" + remark : "")
                + "\n请到门户「申请中心」查看：/apply";
        sendBiz(List.of(ticket.getApplicant()), subject, content);
    }

    /** 新申请单 → 通知空间 Owner（不含申请人本人）。 */
    public void notifyOwnersNewTicket(ApplyTicket ticket) {
        if (ticket == null) {
            return;
        }
        List<String> owners = listWsOwnerUserIds(ticket.getWs());
        owners.removeIf(id -> id.equals(StrUtil.trim(ticket.getApplicant())));
        if (owners.isEmpty()) {
            return;
        }
        String no = StrUtil.blankToDefault(ticket.getTicketNo(), ticket.getId());
        String title = StrUtil.blankToDefault(ticket.getTitle(), no);
        String subject = "待审批申请 · " + no;
        String content = "空间 " + StrUtil.blankToDefault(ticket.getWs(), "default")
                + " 有新申请「" + title + "」，请到门户「申请中心」处理：/apply";
        sendBiz(owners, subject, content);
    }

    /**
     * 根因处置 → 通知 owner（body.ownerId / notifyUserId，否则当前处置人，否则空间 Owner）。
     */
    public boolean notifyRemediateOwner(String ownerId, String operatorId, String ws,
                                       String dagId, String markValue) {
        Set<String> receivers = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(ownerId)) {
            receivers.add(ownerId.trim());
        }
        if (receivers.isEmpty() && StrUtil.isNotBlank(operatorId)) {
            receivers.add(operatorId.trim());
        }
        if (receivers.isEmpty()) {
            receivers.addAll(listWsOwnerUserIds(ws));
        }
        if (receivers.isEmpty()) {
            return false;
        }
        String subject = "根因处置已发起"
                + (StrUtil.isNotBlank(dagId) ? " · " + dagId : "");
        String content = "已触发补数/处置通知。"
                + (StrUtil.isNotBlank(dagId) ? "\n任务：" + dagId : "")
                + (StrUtil.isNotBlank(markValue) ? "\n分区：" + markValue : "")
                + "\n详情见门户运维：/ops";
        return sendBiz(new ArrayList<>(receivers), subject, content);
    }

    public List<String> listWsOwnerUserIds(String wsCode) {
        String raw = StrUtil.blankToDefault(wsCode, "default").trim();
        String wsLower = raw.toLowerCase(Locale.ROOT);
        List<GovWsMember> members = memberMapper.selectList(new QueryWrapper<GovWsMember>().lambda()
                .eq(GovWsMember::getDeleteFlag, NOT_DELETE)
                .eq(GovWsMember::getStatus, STATUS_ACTIVE)
                .eq(GovWsMember::getSubjectType, "user")
                .eq(GovWsMember::getRoleCode, "Owner")
                .and(w -> w.eq(GovWsMember::getWsCode, raw).or().eq(GovWsMember::getWsCode, wsLower)));
        List<String> ids = new ArrayList<>();
        if (members == null) {
            return ids;
        }
        for (GovWsMember m : members) {
            if (StrUtil.isNotBlank(m.getSubjectId())) {
                ids.add(m.getSubjectId().trim());
            }
        }
        return normalizeReceivers(ids);
    }

    private static List<String> normalizeReceivers(List<String> receiverIds) {
        if (receiverIds == null || receiverIds.isEmpty()) {
            return List.of();
        }
        Set<String> set = new LinkedHashSet<>();
        for (String id : receiverIds) {
            if (StrUtil.isNotBlank(id)) {
                set.add(id.trim());
            }
        }
        return new ArrayList<>(set);
    }
}
