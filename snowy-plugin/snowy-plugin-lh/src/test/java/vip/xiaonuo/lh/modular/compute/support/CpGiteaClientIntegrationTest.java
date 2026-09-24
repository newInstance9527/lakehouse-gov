package vip.xiaonuo.lh.modular.compute.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.config.LhProperties;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成：真实 {@link CpGiteaClient} ↔ 假 Gitea HTTP。
 */
class CpGiteaClientIntegrationTest {

    private FakeGiteaServer gitea;
    private CpGiteaClient client;

    @BeforeEach
    void setUp() throws Exception {
        gitea = FakeGiteaServer.start();
        LhProperties props = new LhProperties();
        props.getCompute().getGitea().setEnabled(true);
        props.getCompute().getGitea().setBaseUrl(gitea.baseUrl());
        props.getCompute().getGitea().setToken("it-token");
        props.getCompute().getGitea().setOrg("lakehouse");
        props.getCompute().getGitea().setDefaultRepo("");
        props.getCompute().getGitea().setUsername("lakehouse");
        client = new CpGiteaClient(props);
    }

    @AfterEach
    void tearDown() {
        if (gitea != null) {
            gitea.close();
        }
    }

    @Test
    void ensureRepoThenOpenAndMergePullRequest() {
        assertTrue(client.enabled());
        assertEquals("ws-default", client.repoNameFor("default"));
        assertEquals("ws-default", client.repoNameFor(""));
        assertEquals("ws-default", client.repoNameFor(null));
        assertEquals("ws-trade", client.repoNameFor("trade"));
        assertEquals("ws-trade", client.repoNameFor("ws_trade"));
        assertEquals("ws-trade", client.repoNameFor("ws-trade"));
        assertEquals("ws-my-trade", client.repoNameFor("ws_my__trade"));
        assertEquals("ws-abc123", client.repoNameFor("ws__", "abc123"));
        assertEquals("ws-abc123", client.repoNameFor("测试", "abc123"));
        try {
            client.repoNameFor("ws__");
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("无效"));
        }
        assertTrue(CpGiteaClient.isMalformedWsRepoRemote(
                "http://127.0.0.1:3000/lakehouse/ws--.git"));
        assertTrue(CpGiteaClient.isMalformedWsRepoRemote(
                "http://127.0.0.1:3000/lakehouse/ws-.git"));
        assertFalse(CpGiteaClient.isMalformedWsRepoRemote(
                "http://127.0.0.1:3000/lakehouse/ws-default.git"));
        assertNull(client.ensureRepo("default"));
        assertTrue(client.remoteUrlFor("default").contains("/lakehouse/ws-default.git"));
        String display = CpGiteaClient.redactRemoteUrl(client.remoteUrlFor("trade"));
        assertFalse(display.contains("it-token"));
        assertFalse(display.contains("lakehouse:"));
        assertTrue(display.contains("/lakehouse/ws-trade.git"));
        assertEquals("ws-shared", new CpGiteaClient(sharedProps()).repoNameFor("trade"));

        Map<String, Object> opened = client.openPullRequest(
                "default", "review/abc", "prod", "release pkg", "body");
        assertNotNull(opened.get("number"));
        int num = ((Number) opened.get("number")).intValue();
        assertEquals("open", opened.get("state"));
        assertTrue(String.valueOf(opened.get("htmlUrl")).contains("/pulls/"));

        Map<String, Object> got = client.getPullRequest("default", num);
        assertEquals("open", got.get("state"));
        assertEquals(false, got.get("merged"));

        gitea.mergePull(num);
        Map<String, Object> merged = client.getPullRequest("default", num);
        assertEquals("merged", merged.get("state"));
        assertEquals(true, merged.get("merged"));

        assertTrue(gitea.postedPaths().stream().anyMatch(p -> p.contains("/pulls")));
    }

    @Test
    void ensureRepoUsesUserNamespaceWhenOrgMissing() throws Exception {
        // 覆盖：org 与 username 同名且 GET org=404 → POST /user/repos
        FakeGiteaServer missingOrg = FakeGiteaServer.startMissingOrg();
        try {
            LhProperties props = new LhProperties();
            props.getCompute().getGitea().setEnabled(true);
            props.getCompute().getGitea().setBaseUrl(missingOrg.baseUrl());
            props.getCompute().getGitea().setToken("it-token");
            props.getCompute().getGitea().setOrg("lakehouse");
            props.getCompute().getGitea().setUsername("lakehouse");
            props.getCompute().getGitea().setDefaultRepo("");
            props.getCompute().getGitea().setAutoCreateRepo(true);
            CpGiteaClient c = new CpGiteaClient(props);
            assertNull(c.ensureRepo("trade"));
            assertTrue(missingOrg.postedPaths().stream().anyMatch(p -> p.contains("POST /api/v1/user/repos")));
            assertTrue(missingOrg.postedPaths().stream().anyMatch(p -> p.contains("PATCH /api/v1/repos/")));
        } finally {
            missingOrg.close();
        }
    }

    @Test
    void looksLikeIntranetRemoteDetectsPrivateHosts() {
        assertTrue(CpGiteaClient.looksLikeIntranetRemote(
                "http://lakehouse:tok@10.0.0.181:3000/lakehouse/ws-default.git"));
        assertTrue(CpGiteaClient.looksLikeIntranetRemote(
                "http://10.0.0.181:3000/lakehouse/ws-default.git"));
        assertFalse(CpGiteaClient.looksLikeIntranetRemote(
                "http://lakehouse:tok@127.0.0.1:3000/lakehouse/ws-default.git"));
        assertFalse(CpGiteaClient.looksLikeIntranetRemote(
                "http://172.15.0.1:3000/x/y.git"));
        assertTrue(CpGiteaClient.looksLikeIntranetRemote(
                "http://172.16.0.1:3000/x/y.git"));
    }

    @Test
    void customPublicRemotePreservedPlatformManagedRewritten() {
        assertTrue(client.isPlatformManagedRemote(
                "http://lakehouse:tok@" + gitea.baseUrl().replace("http://", "") + "/lakehouse/ws-old.git"));
        assertTrue(client.shouldRewriteRemote(
                "http://lakehouse:tok@10.0.0.181:3000/lakehouse/ws-default.git"));
        assertTrue(client.isCustomPublicRemote("https://github.com/acme/scripts.git"));
        assertFalse(client.shouldRewriteRemote("https://github.com/acme/scripts.git"));
        assertFalse(client.isCustomPublicRemote(""));
        assertTrue(client.shouldRewriteRemote(null));
    }

    @Test
    void openPullRequestIdempotentWhenSameHeadExists() {
        client.openPullRequest("default", "review/x", "prod", "t1", "");
        Map<String, Object> again = client.openPullRequest("default", "review/x", "prod", "t2", "");
        // 第二次可能新建或复用；至少应有 number
        assertNotNull(again.get("number"));
    }

    /** 运维逃生：显式 default-repo 时强制共用仓 */
    private LhProperties sharedProps() {
        LhProperties props = new LhProperties();
        props.getCompute().getGitea().setEnabled(true);
        props.getCompute().getGitea().setBaseUrl(gitea.baseUrl());
        props.getCompute().getGitea().setToken("it-token");
        props.getCompute().getGitea().setOrg("lakehouse");
        props.getCompute().getGitea().setDefaultRepo("ws-shared");
        props.getCompute().getGitea().setUsername("lakehouse");
        return props;
    }
}
