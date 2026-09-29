package vip.xiaonuo.lh.modular.sec.support;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.engine.VictoriaMetricsClient;
import vip.xiaonuo.lh.core.vault.LhVaultRotateMetricsFormatter;
import vip.xiaonuo.lh.modular.apply.param.ApplyTicketCreateParam;
import vip.xiaonuo.lh.modular.apply.service.ApplyTicketService;
import vip.xiaonuo.lh.modular.apply.service.impl.ApplyTicketServiceImpl;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Vault 轮换失败：写 VM + 开工单（均 soft-fail）。
 */
@Component
public class SecVaultRotateAlertSupport {

    private static final Logger log = LoggerFactory.getLogger(SecVaultRotateAlertSupport.class);

    @Resource
    private VictoriaMetricsClient victoriaMetricsClient;
    @Resource
    private ApplyTicketService applyTicketService;

    public Map<String, Object> onSuccess(String vaultPath, String kind) {
        writeVm(vaultPath, kind, true);
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("vmOk", true);
        return alert;
    }

    public Map<String, Object> onFailure(String vaultPath, String kind, String detail) {
        writeVm(vaultPath, kind, false);
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("vmFail", true);
        try {
            ApplyTicketCreateParam p = new ApplyTicketCreateParam();
            p.setTicketType(ApplyTicketServiceImpl.TYPE_VAULT_ROTATE_FAIL);
            p.setTitle("Vault 轮换失败 · " + StrUtil.blankToDefault(vaultPath, "path"));
            p.setReason(StrUtil.blankToDefault(detail, "Vault 凭证轮换失败"));
            p.setResourceType("vault");
            p.setResourceId(vaultPath);
            var ticket = applyTicketService.create(p);
            if (ticket != null) {
                alert.put("ticketId", ticket.getId());
                alert.put("ticketNo", ticket.getTicketNo());
            }
        } catch (Exception e) {
            log.warn("[sec-vault] create vault_rotate_fail ticket soft-fail: {}", e.getMessage());
            alert.put("ticketError", e.getMessage());
        }
        return alert;
    }

    private void writeVm(String vaultPath, String kind, boolean ok) {
        try {
            long ts = System.currentTimeMillis();
            String body = LhVaultRotateMetricsFormatter.joinBody(
                    LhVaultRotateMetricsFormatter.format(vaultPath, kind, ok, ts));
            Map<String, Object> wr = victoriaMetricsClient.importPrometheus(body);
            if (Boolean.TRUE.equals(wr.get("degraded")) || Boolean.TRUE.equals(wr.get("skipped"))) {
                log.debug("[sec-vault] VM write skipped/degraded: {}", wr.get("message"));
            }
        } catch (Exception e) {
            log.warn("[sec-vault] VM write soft-fail: {}", e.getMessage());
        }
    }
}
