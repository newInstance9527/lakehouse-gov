package vip.xiaonuo.lh.modular.datasource.discover;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.admin.PulsarAdmin;
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
 * Pulsar：Admin HTTP 列举 tenant 下 namespaces / topics（限流）。
 */
@Slf4j
@Component
@Order(10)
public class LhPulsarInventoryDiscoverer implements LhInventoryDiscoverer {

    private static final int MAX_TOPICS = 300;

    @Resource
    private LhVaultClient vaultClient;

    @Override
    public boolean supports(String typeCode) {
        return "pulsar".equalsIgnoreCase(StrUtil.blankToDefault(typeCode, ""));
    }

    @Override
    public String objectKind(String typeCode) {
        return LhInventoryObjectKinds.TOPIC;
    }

    @Override
    public LhInventoryDiscoverResult discover(LhDatasource ds) {
        Map<String, Object> secret = vaultClient.readOrEmpty(ds.getVaultPath());
        if (StrUtil.isBlank(str(secret.get("host"))) && StrUtil.isNotBlank(ds.getEndpointHost())) {
            secret.put("host", ds.getEndpointHost());
        }
        String adminUrl = LhDatasourceProtocolProbe.resolvePulsarAdminHttp(secret);
        String tenant = StrUtil.blankToDefault(str(secret.get("tenant")), "public");
        try (PulsarAdmin admin = PulsarAdmin.builder().serviceHttpUrl(adminUrl).build()) {
            List<String> namespaces = admin.namespaces().getNamespaces(tenant);
            List<LhRemoteInventoryItem> items = new ArrayList<>();
            for (String ns : namespaces) {
                if (StrUtil.isBlank(ns)) {
                    continue;
                }
                List<String> topics;
                try {
                    topics = admin.topics().getList(ns);
                } catch (Exception e) {
                    log.warn("Pulsar list topics {}: {}", ns, e.getMessage());
                    continue;
                }
                for (String topic : topics) {
                    if (StrUtil.isBlank(topic)) {
                        continue;
                    }
                    LhRemoteInventoryItem m = new LhRemoteInventoryItem();
                    m.name = topic;
                    m.engine = "pulsar";
                    m.encoding = "UTF-8";
                    m.comment = "ns=" + ns;
                    items.add(m);
                    if (items.size() >= MAX_TOPICS) {
                        break;
                    }
                }
                if (items.size() >= MAX_TOPICS) {
                    break;
                }
            }
            // 表单维护的 topics 合并
            String registered = str(secret.get("topics"));
            if (StrUtil.isBlank(registered)) {
                registered = StrUtil.blankToDefault(ds.getSchemaSummary(), "");
            }
            if (StrUtil.isNotBlank(registered)) {
                for (String part : registered.split("[,，;；\\n\\r]+")) {
                    String n = part.trim();
                    if (n.isEmpty() || items.stream().anyMatch(i -> n.equals(i.name))) {
                        continue;
                    }
                    LhRemoteInventoryItem m = new LhRemoteInventoryItem();
                    m.name = n;
                    m.engine = "pulsar";
                    m.comment = "registered";
                    items.add(m);
                }
            }
            return LhInventoryDiscoverResult.remote("pulsar_admin", LhInventoryObjectKinds.TOPIC, items);
        } catch (CommonException e) {
            throw e;
        } catch (Exception e) {
            throw new CommonException("Pulsar 清单同步失败（admin={}）: {}", adminUrl, e.getMessage());
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
