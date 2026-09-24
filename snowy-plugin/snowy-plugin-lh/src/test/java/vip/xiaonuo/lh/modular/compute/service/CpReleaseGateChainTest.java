package vip.xiaonuo.lh.modular.compute.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.modular.compute.support.CpGiteaClient;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 发布门禁链路完整性：MR 态 + SCR 审批态 → blocking 判定。
 * （不启 Spring；反射调用 package 私有评估方法）
 */
class CpReleaseGateChainTest {

    private CpDevelopService service;
    private LhProperties props;
    private CpGiteaClient gitea;

    @BeforeEach
    void setUp() {
        service = new CpDevelopService();
        props = new LhProperties();
        props.getCompute().setRequireMrMerge(true);
        props.getCompute().setRequireScriptPublishTicket(true);
        gitea = mock(CpGiteaClient.class);
        when(gitea.enabled()).thenReturn(true);
        ReflectionTestUtils.setField(service, "lhProperties", props);
        ReflectionTestUtils.setField(service, "giteaClient", gitea);
    }

    @Test
    void mrGatePassOnlyWhenMerged() throws Exception {
        assertEquals("pass", assessMr("merged").get("status"));
        assertEquals("wait", assessMr("open").get("status"));
        assertEquals("fail", assessMr("closed").get("status"));
        assertEquals("wait", assessMr(null).get("status"));
    }

    @Test
    void mrGateSkipWhenGiteaDisabled() throws Exception {
        when(gitea.enabled()).thenReturn(false);
        assertEquals("skip", assessMr("open").get("status"));
    }

    @Test
    void ticketGatePassOnlyWhenApproved() throws Exception {
        assertEquals("pass", assessTicket("SCR-1", "approved").get("status"));
        assertEquals("wait", assessTicket("SCR-1", "pending").get("status"));
        assertEquals("fail", assessTicket("SCR-1", "rejected").get("status"));
        assertEquals("wait", assessTicket(null, null).get("status"));
    }

    @Test
    void chainBlocksUntilMrMergedAndTicketApproved() throws Exception {
        List<Map<String, Object>> gates = eightStepGates("open", "SCR-9", "pending");
        assertTrue(blocking(gates) || hasWait(gates), "未合并/未审批时不得视为可发布");

        gates = eightStepGates("merged", "SCR-9", "approved");
        // wait 在生产发布步仍存在，但不算 fail；blocking 仅 fail
        assertFalse(blocking(gates), "MR 已合且 SCR 已批时前 7 步不应 fail");
        assertEquals("pass", statusOf(gates, "MR 评审"));
        assertEquals("pass", statusOf(gates, "申请审批"));
        assertEquals("wait", statusOf(gates, "生产发布"));
    }

    @Test
    void rejectedTicketMakesChainBlocking() throws Exception {
        List<Map<String, Object>> gates = eightStepGates("merged", "SCR-9", "rejected");
        assertTrue(blocking(gates));
        assertEquals("fail", statusOf(gates, "申请审批"));
    }

    private List<Map<String, Object>> eightStepGates(String prState, String ticketNo, String ticketStatus)
            throws Exception {
        List<Map<String, Object>> gates = new ArrayList<>();
        gates.add(gate(1, "静态检查", "ok", "pass"));
        gates.add(gate(2, "血缘解析入库", "ok", "skip"));
        gates.add(gate(3, "质量规则绑定", "ok", "skip"));
        gates.add(gate(4, "stg 试跑", "ok", "pass"));
        gates.add(gate(5, "变更影响", "ok", "skip"));
        Map<String, Object> mr = assessMr(prState);
        gates.add(gate(6, "MR 评审", String.valueOf(mr.get("detail")), String.valueOf(mr.get("status"))));
        Map<String, Object> tk = assessTicket(ticketNo, ticketStatus);
        gates.add(gate(7, "申请审批", String.valueOf(tk.get("detail")), String.valueOf(tk.get("status"))));
        gates.add(gate(8, "生产发布", "等待发布", "wait"));
        return gates;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> assessMr(String prState) throws Exception {
        Method m = CpDevelopService.class.getDeclaredMethod("assessMrGate", String.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(service, prState);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> assessTicket(String ticketNo, String ticketStatus) throws Exception {
        Method m = CpDevelopService.class.getDeclaredMethod("assessTicketGate", String.class, String.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(service, ticketNo, ticketStatus);
    }

    private boolean blocking(List<Map<String, Object>> gates) throws Exception {
        Method m = CpDevelopService.class.getDeclaredMethod("blocking", List.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, gates);
    }

    private static boolean hasWait(List<Map<String, Object>> gates) {
        return gates.stream().anyMatch(g -> "wait".equalsIgnoreCase(String.valueOf(g.get("status"))));
    }

    private static String statusOf(List<Map<String, Object>> gates, String name) {
        return gates.stream()
                .filter(g -> name.equals(String.valueOf(g.get("name"))))
                .map(g -> String.valueOf(g.get("status")))
                .findFirst()
                .orElse("missing");
    }

    private static Map<String, Object> gate(int step, String name, String detail, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", step);
        m.put("name", name);
        m.put("detail", detail);
        m.put("status", status);
        return m;
    }
}
