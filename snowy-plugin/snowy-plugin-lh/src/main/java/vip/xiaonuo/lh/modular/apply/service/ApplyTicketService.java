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

    Map<String, Object> approve(ApplyTicketDecideParam param);

    ApplyTicket reject(ApplyTicketDecideParam param);

    /**
     * ETL 出湖校验：ticketNo 必须存在、类型 lake_export、状态 approved。
     */
    void assertApprovedExportTicket(String ticketNo);
}
