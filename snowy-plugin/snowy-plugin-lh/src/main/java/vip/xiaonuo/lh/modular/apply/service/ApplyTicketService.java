package vip.xiaonuo.lh.modular.apply.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import vip.xiaonuo.lh.modular.apply.entity.ApplyTicket;
import vip.xiaonuo.lh.modular.apply.param.ApplyTicketCreateParam;
import vip.xiaonuo.lh.modular.apply.param.ApplyTicketDecideParam;
import vip.xiaonuo.lh.modular.apply.param.ApplyTicketPageParam;

import java.util.Map;

public interface ApplyTicketService {

    ApplyTicket create(ApplyTicketCreateParam param);

    Page<ApplyTicket> pageMine(ApplyTicketPageParam param);

    Page<ApplyTicket> pagePending(ApplyTicketPageParam param);

    /**
     * 看板 KPI：待我审批 / 我申请的 / 本月通过 / 本月驳回（含 metric 等全票种）。
     * @param ws 工作空间；空=不过滤
     */
    Map<String, Object> kpi(String ws);

    Map<String, Object> approve(ApplyTicketDecideParam param);

    ApplyTicket reject(ApplyTicketDecideParam param);

    /**
     * ETL 出湖校验：ticketNo 必须存在、类型 lake_export、状态 approved。
     */
    void assertApprovedExportTicket(String ticketNo);

    /**
     * 合规删除执行门闩：ticketNo 必须存在、类型 compliance_delete、状态 approved。
     */
    void assertApprovedComplianceTicket(String ticketNo);

    /**
     * 数据服务发布门闩：ticketNo 须为 api_publish 且 approved；可选校验绑定 id。
     */
    void assertApprovedApiPublishTicket(String ticketNo, String apiBindingId);

    /**
     * 脚本/发布包门闩：ticketNo 须为 script_publish 且 approved；可选校验 releaseId。
     */
    void assertApprovedScriptPublishTicket(String ticketNo, String releaseId);

    /**
     * 按单号取申请元信息（ticketNo/status/ticketType）；无则 null。
     */
    Map<String, Object> findTicketMeta(String ticketNo);

    /**
     * 取绑定最近一张已审批的 api_publish 单号；无则返回 null。
     */
    String findLatestApprovedApiPublishTicketNo(String apiBindingId);

    /**
     * 取绑定最近一张 api_publish（任意状态）元信息：ticketNo / status。
     */
    Map<String, Object> findLatestApiPublishTicket(String apiBindingId);
}
