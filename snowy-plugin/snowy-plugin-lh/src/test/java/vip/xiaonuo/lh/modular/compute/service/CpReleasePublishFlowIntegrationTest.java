package vip.xiaonuo.lh.modular.compute.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.modular.apply.entity.ApplyTicket;
import vip.xiaonuo.lh.modular.apply.param.ApplyTicketCreateParam;
import vip.xiaonuo.lh.modular.apply.service.ApplyTicketService;
import vip.xiaonuo.lh.modular.apply.service.impl.ApplyTicketServiceImpl;
import vip.xiaonuo.lh.modular.compute.entity.CpRelease;
import vip.xiaonuo.lh.modular.compute.entity.CpScriptIndex;
import vip.xiaonuo.lh.modular.compute.mapper.CpReleaseMapper;
import vip.xiaonuo.lh.modular.compute.mapper.CpScriptIndexMapper;
import vip.xiaonuo.lh.modular.compute.mapper.CpScriptRunMapper;
import vip.xiaonuo.lh.modular.compute.param.CpReleaseCreateParam;
import vip.xiaonuo.lh.modular.compute.support.CpEnvIsolationSupport;
import vip.xiaonuo.lh.modular.compute.support.CpGiteaClient;
import vip.xiaonuo.lh.modular.compute.support.CpScriptGitStore;
import vip.xiaonuo.lh.modular.compute.support.FakeGiteaServer;
import vip.xiaonuo.lh.modular.lineage.service.GovLineageService;
import vip.xiaonuo.lh.modular.quality.service.GovDqService;
import vip.xiaonuo.lh.modular.workspace.mapper.GovWsMapper;

import java.nio.file.Path;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 集成链路：Git 正文 + 假 Gitea PR + SCR 申请门闩 + createRelease → gates → publish。
 * 外边界：DS/Gravitino/MinIO 不连；质量/血缘 stub 为 skip。
 */
class CpReleasePublishFlowIntegrationTest {

    @TempDir
    Path temp;

    private FakeGiteaServer gitea;
    private CpDevelopService develop;
    private CpGiteaClient giteaClient;
    private final ConcurrentHashMap<String, CpScriptIndex> scripts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CpRelease> releases = new ConcurrentHashMap<>();
    private final AtomicReference<ApplyTicket> ticket = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        gitea = FakeGiteaServer.start();

        LhProperties props = new LhProperties();
        props.getCompute().setGitRoot(temp.toString());
        props.getCompute().setForbidProdLayerWrite(true);
        props.getCompute().setPublishGateHardFail(true);
        props.getCompute().setRequireMrMerge(true);
        props.getCompute().setRequireScriptPublishTicket(true);
        props.getCompute().getEnvIsolation().setEnabled(true);
        props.getCompute().getEnvIsolation().setAutoEnsure(false);
        props.getCompute().getGitea().setEnabled(true);
        props.getCompute().getGitea().setBaseUrl(gitea.baseUrl());
        props.getCompute().getGitea().setToken("it-token");
        props.getCompute().getGitea().setOrg("lakehouse");
        props.getCompute().getGitea().setDefaultRepo("ws-it");
        props.getCompute().getGitea().setUsername("lakehouse");
        props.getCompute().getGitea().setProdBranch("prod");
        props.getCompute().getGitea().setAutoCreateRepo(true);

        giteaClient = new CpGiteaClient(props);
        CpScriptGitStore gitStore = new CpScriptGitStore(props);
        CpEnvIsolationSupport isolation = new CpEnvIsolationSupport();
        ReflectionTestUtils.setField(isolation, "lhProperties", props);

        CpScriptIndexMapper scriptMapper = mock(CpScriptIndexMapper.class);
        CpReleaseMapper releaseMapper = mock(CpReleaseMapper.class);
        CpScriptRunMapper runMapper = mock(CpScriptRunMapper.class);
        GovWsMapper govWsMapper = mock(GovWsMapper.class);
        GovDqService dq = mock(GovDqService.class);
        GovLineageService lineage = mock(GovLineageService.class);
        ApplyTicketService applyTickets = mock(ApplyTicketService.class);

        when(scriptMapper.selectById(anyString())).thenAnswer(inv -> scripts.get(inv.getArgument(0)));
        when(scriptMapper.updateById(any(CpScriptIndex.class))).thenAnswer(inv -> {
            CpScriptIndex s = inv.getArgument(0);
            scripts.put(s.getId(), s);
            return 1;
        });

        when(releaseMapper.insert(any(CpRelease.class))).thenAnswer(inv -> {
            CpRelease r = inv.getArgument(0);
            releases.put(r.getId(), r);
            return 1;
        });
        when(releaseMapper.selectById(anyString())).thenAnswer(inv -> {
            CpRelease r = releases.get(inv.getArgument(0));
            return r == null ? null : copyRelease(r);
        });
        when(releaseMapper.updateById(any(CpRelease.class))).thenAnswer(inv -> {
            CpRelease r = inv.getArgument(0);
            releases.put(r.getId(), r);
            return 1;
        });

        when(runMapper.selectCount(any())).thenReturn(1L);
        when(govWsMapper.selectOne(any())).thenReturn(null);

        Map<String, Object> skip = new LinkedHashMap<>();
        skip.put("status", "skip");
        skip.put("detail", "it-skip");
        when(dq.assessPublishGate(any(), any(), any())).thenReturn(skip);
        when(lineage.assessLineageIngestGate(any(), any())).thenReturn(skip);
        when(lineage.assessPublishGate(any(), any())).thenReturn(skip);

        when(applyTickets.create(any(ApplyTicketCreateParam.class))).thenAnswer(inv -> {
            ApplyTicketCreateParam p = inv.getArgument(0);
            assertEquals(ApplyTicketServiceImpl.TYPE_SCRIPT_PUBLISH, p.getTicketType());
            ApplyTicket t = new ApplyTicket();
            t.setId("tk-1");
            t.setTicketNo("SCR-IT9001");
            t.setTicketType(ApplyTicketServiceImpl.TYPE_SCRIPT_PUBLISH);
            t.setStatus("pending");
            t.setTitle(p.getTitle());
            t.setPayload("{\"releaseId\":\"" + p.getResourceId() + "\"}");
            ticket.set(t);
            return t;
        });
        when(applyTickets.findTicketMeta(anyString())).thenAnswer(inv -> {
            ApplyTicket t = ticket.get();
            if (t == null || !t.getTicketNo().equals(inv.getArgument(0))) {
                return null;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ticketNo", t.getTicketNo());
            m.put("status", t.getStatus());
            m.put("ticketType", t.getTicketType());
            return m;
        });
        doAnswer(inv -> {
            String no = inv.getArgument(0);
            String releaseId = inv.getArgument(1);
            ApplyTicket t = ticket.get();
            if (t == null || !t.getTicketNo().equals(no)) {
                throw new CommonException("脚本发布申请单不存在: " + no);
            }
            if (!"approved".equals(t.getStatus())) {
                throw new CommonException("脚本发布申请尚未通过审批: " + no + "（status=" + t.getStatus() + "）");
            }
            if (releaseId != null && t.getPayload() != null && !t.getPayload().contains(releaseId)) {
                throw new CommonException("发布单与当前发布包 id 不匹配");
            }
            return null;
        }).when(applyTickets).assertApprovedScriptPublishTicket(any(), any());

        // seed script
        String scriptId = "script-it-1";
        String path = "scripts/trade/demo.sql";
        String sql = "INSERT INTO stg_iceberg.dwd.t SELECT 1 WHERE dt = '2026-01-01';\n";
        String sha = gitStore.commit("default", path, sql, "seed", "it", "it@local");
        CpScriptIndex script = new CpScriptIndex();
        script.setId(scriptId);
        script.setWs("default");
        script.setPath(path);
        script.setName("demo.sql");
        script.setFolder("trade");
        script.setEngine("trino");
        script.setEnv("PRE");
        script.setGitSha(sha);
        script.setStatus("DRAFT");
        script.setDeleteFlag("NOT_DELETE");
        script.setCreateTime(new Date());
        scripts.put(scriptId, script);

        develop = new CpDevelopService();
        ReflectionTestUtils.setField(develop, "scriptMapper", scriptMapper);
        ReflectionTestUtils.setField(develop, "releaseMapper", releaseMapper);
        ReflectionTestUtils.setField(develop, "runMapper", runMapper);
        ReflectionTestUtils.setField(develop, "govWsMapper", govWsMapper);
        ReflectionTestUtils.setField(develop, "gitStore", gitStore);
        ReflectionTestUtils.setField(develop, "giteaClient", giteaClient);
        ReflectionTestUtils.setField(develop, "envIsolation", isolation);
        ReflectionTestUtils.setField(develop, "applyTicketService", applyTickets);
        ReflectionTestUtils.setField(develop, "lhProperties", props);
        ReflectionTestUtils.setField(develop, "govDqService", dq);
        ReflectionTestUtils.setField(develop, "govLineageService", lineage);
    }

    @AfterEach
    void tearDown() {
        if (gitea != null) {
            gitea.close();
        }
    }

    @Test
    void createReleaseOpensPrAndScrThenPublishAfterMergeAndApprove() {
        CpReleaseCreateParam param = new CpReleaseCreateParam();
        param.setScriptId("script-it-1");
        param.setEngine("trino");
        param.setEnv("stg");

        Map<String, Object> created = develop.createRelease(param);
        assertEquals("IN_REVIEW", created.get("status"));
        assertNotNull(created.get("id"));
        assertEquals("SCR-IT9001", created.get("applyTicketNo"));
        assertNotNull(created.get("prNumber"));
        int pr = ((Number) created.get("prNumber")).intValue();
        assertEquals("open", created.get("prState"));
        assertNotNull(ticket.get());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gates1 = (List<Map<String, Object>>) created.get("gates");
        assertEquals("wait", statusOf(gates1, "MR 评审"));
        assertEquals("wait", statusOf(gates1, "申请审批"));

        String releaseId = String.valueOf(created.get("id"));
        assertThrows(CommonException.class, () -> develop.publish(releaseId),
                "未合并 PR / 未批 SCR 时发布须失败");

        // 审批 SCR + 合并 PR
        ticket.get().setStatus("approved");
        gitea.mergePull(pr);

        Map<String, Object> refreshed = develop.gates(releaseId);
        assertEquals("merged", refreshed.get("prState"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gates2 = (List<Map<String, Object>>) refreshed.get("gates");
        assertEquals("pass", statusOf(gates2, "MR 评审"));
        assertEquals("pass", statusOf(gates2, "申请审批"));

        Map<String, Object> published = develop.publish(releaseId);
        assertEquals("PUBLISHED", published.get("status"));
        assertTrue(String.valueOf(published.get("tag")).startsWith("rel-")
                || String.valueOf(published.get("gitTag")).startsWith("rel-"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gates3 = (List<Map<String, Object>>) published.get("gates");
        assertEquals("pass", statusOf(gates3, "生产发布"));
    }

    private static String statusOf(List<Map<String, Object>> gates, String name) {
        return gates.stream()
                .filter(g -> name.equals(String.valueOf(g.get("name"))))
                .map(g -> String.valueOf(g.get("status")))
                .findFirst()
                .orElse("missing");
    }

    /** selectById 返回副本，避免测试误改内存态与 refresh 更新打架。 */
    private static CpRelease copyRelease(CpRelease src) {
        CpRelease r = new CpRelease();
        r.setId(src.getId());
        r.setWs(src.getWs());
        r.setScriptId(src.getScriptId());
        r.setPkg(src.getPkg());
        r.setScriptName(src.getScriptName());
        r.setScriptPath(src.getScriptPath());
        r.setEngine(src.getEngine());
        r.setEnv(src.getEnv());
        r.setGitSha(src.getGitSha());
        r.setGitTag(src.getGitTag());
        r.setStatus(src.getStatus());
        r.setResultLabel(src.getResultLabel());
        r.setGatesJson(src.getGatesJson());
        r.setDsWorkflowCode(src.getDsWorkflowCode());
        r.setRolledToTag(src.getRolledToTag());
        r.setApplyTicketNo(src.getApplyTicketNo());
        r.setPrNumber(src.getPrNumber());
        r.setPrUrl(src.getPrUrl());
        r.setPrState(src.getPrState());
        r.setReviewBranch(src.getReviewBranch());
        r.setDeleteFlag(src.getDeleteFlag());
        r.setCreateTime(src.getCreateTime());
        r.setCreateUser(src.getCreateUser());
        r.setUpdateTime(src.getUpdateTime());
        r.setUpdateUser(src.getUpdateUser());
        return r;
    }
}
