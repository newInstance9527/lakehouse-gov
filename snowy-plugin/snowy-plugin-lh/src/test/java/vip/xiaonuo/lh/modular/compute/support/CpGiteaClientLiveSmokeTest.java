package vip.xiaonuo.lh.modular.compute.support;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.config.LhProperties;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可选：对真实 Gitea 跑 ensureRepo（含 PATCH），验证 JDK HttpClient 路径。
 * 需 -Dgitea.live.base=http://127.0.0.1:3000 -Dgitea.live.token=...
 */
class CpGiteaClientLiveSmokeTest {

    @Test
    void ensureRepoAgainstLiveGitea() {
        String base = System.getProperty("gitea.live.base", "").trim();
        String token = System.getProperty("gitea.live.token", "").trim();
        Assumptions.assumeTrue(!base.isEmpty() && !token.isEmpty(), "skip: no gitea.live.* props");

        LhProperties props = new LhProperties();
        props.getCompute().getGitea().setEnabled(true);
        props.getCompute().getGitea().setBaseUrl(base);
        props.getCompute().getGitea().setToken(token);
        props.getCompute().getGitea().setOrg("lakehouse");
        props.getCompute().getGitea().setUsername("lakehouse");
        props.getCompute().getGitea().setAutoCreateRepo(true);
        props.getCompute().getGitea().setDefaultRepo("");

        CpGiteaClient client = new CpGiteaClient(props);
        String ws = "smoke-" + Long.toString(System.currentTimeMillis() % 1_000_000_000L, 36);
        String err = client.ensureRepo(ws);
        assertNull(err, () -> "ensureRepo failed: " + err);
        String remote = client.remoteUrlFor(ws);
        assertTrue(remote.contains("/lakehouse/ws-" + ws + ".git")
                || remote.contains("/lakehouse/" + client.repoNameFor(ws) + ".git"));
        String display = CpGiteaClient.redactRemoteUrl(remote);
        assertTrue(display.contains("127.0.0.1") || display.contains(base.replaceFirst("^https?://", "").split("/")[0]));
        // 脱敏后不得含 token
        assertTrue(!display.contains(token));
    }
}
