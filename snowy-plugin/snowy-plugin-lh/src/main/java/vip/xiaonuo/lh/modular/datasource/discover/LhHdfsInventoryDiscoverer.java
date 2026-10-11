package vip.xiaonuo.lh.modular.datasource.discover;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.support.LhDatasourceProtocolProbe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HDFS：WebHDFS LISTSTATUS（顶层限流）；失败时回落路径清单（ZK/RPC 可达时可手工）。
 */
@Slf4j
@Component
@Order(10)
public class LhHdfsInventoryDiscoverer implements LhInventoryDiscoverer {

    private static final int MAX_ENTRIES = 200;

    @Resource
    private LhVaultClient vaultClient;

    @Override
    public boolean supports(String typeCode) {
        return "hdfs".equalsIgnoreCase(StrUtil.blankToDefault(typeCode, ""));
    }

    @Override
    public String objectKind(String typeCode) {
        return LhInventoryObjectKinds.PATH;
    }

    @Override
    public LhInventoryDiscoverResult discover(LhDatasource ds) {
        Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
        String nn = first(secret, "nameNode", "host", "endpoint");
        if (StrUtil.isBlank(nn) && StrUtil.isNotBlank(ds.getEndpointHost())) {
            nn = ds.getEndpointHost();
        }
        String port = first(secret, "port");
        if (StrUtil.isBlank(port) && StrUtil.isNotBlank(ds.getEndpointPort())) {
            port = ds.getEndpointPort();
        }
        String user = StrUtil.blankToDefault(first(secret, "user", "username"), "hdfs");
        if (StrUtil.isBlank(nn)) {
            throw new CommonException("HDFS 清单同步需要 nameNode/host");
        }
        nn = nn.replaceFirst("^hdfs://", "").replaceFirst("^https?://", "").replaceAll("/+$", "");
        if (nn.contains(":") && !nn.startsWith("[")) {
            String[] hp = nn.split(":", 2);
            nn = hp[0];
            if (StrUtil.isBlank(port)) {
                port = hp[1];
            }
        }

        // 优先登记路径；否则列 /
        List<String> roots = new ArrayList<>();
        String summary = StrUtil.blankToDefault(ds.getSchemaSummary(), first(secret, "schema", "path"));
        if (StrUtil.isNotBlank(summary)) {
            for (String part : summary.split("[,，;；\\n\\r]+")) {
                String p = part.trim();
                if (StrUtil.isNotBlank(p)) {
                    roots.add(p.startsWith("/") ? p : "/" + p);
                }
            }
        }
        if (roots.isEmpty()) {
            roots.add("/");
        }

        String httpPort = LhDatasourceProtocolProbe.resolveWebHdfsPort(port);
        List<LhRemoteInventoryItem> items = new ArrayList<>();
        Exception last = null;
        for (String root : roots) {
            try {
                List<String> names = LhDatasourceProtocolProbe.listWebHdfs(nn, httpPort, user, root, MAX_ENTRIES);
                if (names.isEmpty() && !"/".equals(root)) {
                    // 路径本身可作为对象
                    LhRemoteInventoryItem self = new LhRemoteInventoryItem();
                    self.name = root;
                    self.engine = "hdfs";
                    self.comment = "webhdfs_path";
                    items.add(self);
                    continue;
                }
                for (String name : names) {
                    String full = "/".equals(root) ? "/" + name
                            : (root.endsWith("/") ? root + name : root + "/" + name);
                    if (items.stream().anyMatch(i -> full.equals(i.name))) {
                        continue;
                    }
                    LhRemoteInventoryItem m = new LhRemoteInventoryItem();
                    m.name = full;
                    m.engine = "hdfs";
                    m.encoding = "UTF-8";
                    m.comment = "webhdfs";
                    items.add(m);
                    if (items.size() >= MAX_ENTRIES) {
                        break;
                    }
                }
            } catch (Exception e) {
                last = e;
                log.warn("WebHDFS list {}: {}", root, e.getMessage());
            }
            if (items.size() >= MAX_ENTRIES) {
                break;
            }
        }
        if (!items.isEmpty()) {
            return LhInventoryDiscoverResult.remote("webhdfs", LhInventoryObjectKinds.PATH, items);
        }
        // 回落：保留登记路径 + 提示
        List<LhRemoteInventoryItem> manual = new ArrayList<>();
        for (String root : roots) {
            if ("/".equals(root)) {
                continue;
            }
            LhRemoteInventoryItem m = new LhRemoteInventoryItem();
            m.name = root;
            m.engine = "hdfs";
            m.comment = "manual:path";
            manual.add(m);
        }
        String hint = "WebHDFS 不可用（需 NameNode HTTP 9870/50070）；已回落路径清单。"
                + (last != null ? " cause=" + last.getMessage() : "");
        return LhInventoryDiscoverResult.manual(LhInventoryObjectKinds.PATH, hint, manual);
    }

    private static String first(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                return String.valueOf(v).trim();
            }
        }
        return "";
    }
}
